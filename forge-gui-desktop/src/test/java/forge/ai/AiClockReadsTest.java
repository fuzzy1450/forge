package forge.ai;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.fail;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.testng.annotations.Test;

/**
 * The clock check (MTG_DeckMaker's tools/combo-ai/flat-mc/SHIP.md, design 4, for its anti-goal: an engine whose games
 * differ by machine). Every seat of every harness game plays Flat MC, and one seed has to play one game on every
 * machine, beside other games or alone, as verify replays it, so nothing the AI decides may follow the machine's clock,
 * an unseeded random stream or the runtime. This test scans the AI's sources for such reads and fails on any that is
 * not on {@link #ALLOWED}, its short list of known reads that are inert in a harness game: an upstream merge that adds
 * one fails the build, naming its file, before an engine is built from it. A read that steers a harness game is not
 * added to the list: it goes through the counted clock ({@code FlatMcGame.nanoTime()}), the scope's random stream
 * ({@code MyRandom.getRandom()}) or behind a scope test, as the storm solver's and the picker's heap reads do.
 */
public class AiClockReadsTest {
    /** The AI's sources, seen from forge-gui-desktop, the directory its tests run in. */
    private static final Path AI_SOURCES = Path.of("..", "forge-ai", "src", "main", "java");

    /**
     * Every file of the AI's sources that reads, by its path under forge-ai/src/main/java, with its number of reads,
     * each inert in a harness game for the reason on the line above it. A file that is not here reads nothing.
     */
    static final Map<String, Integer> ALLOWED = Map.of(
            // the AI eval thread's wait: GameRunner gives every game no AI timeout, so its deadline is decades away
            "forge/ai/AiController.java", 1,
            // the GUI's actions sweep: only a human seat's controller runs it, and a harness game seats none
            "forge/ai/AvailableActions.java", 2,
            // the counted clock itself: the machine's clock only unbound, and wall figures that only stderr reads
            "forge/ai/simulation/FlatMcGame.java", 3,
            // the picker's heap, on its GUI path: under a scope nothing is constrained (FlatMcHeapTest)
            "forge/ai/simulation/FlatMonteCarloPicker.java", 2,
            // the hybrid picker's log line: the time a choice took is printed, never compared
            "forge/ai/simulation/SpellAbilityPicker.java", 2,
            // the storm solver's GUI path: under a scope it reads no clock and no heap (StormSolverScopeTest)
            "forge/ai/simulation/StormSolver.java", 5);

    /**
     * Every spelling of a read is seen, once: each file of a scratch tree reads once in a spelling of its own (the
     * plain call, fully qualified, spaced across lines, a method reference, a bare call under a static import of the
     * member or of its class, a read after literals that look like comments), and one file reads three times.
     */
    @Test
    public void theScannerSeesEverySpelling() {
        final Map<String, String> files = new TreeMap<>();
        files.put("clock/NanoTime.java", "class A { long t = System.nanoTime(); }");
        files.put("clock/CurrentTimeMillis.java", "class A { long t = System.currentTimeMillis(); }");
        files.put("clock/InstantNow.java", "import java.time.Instant; class A { Instant t = Instant.now(); }");
        files.put("clock/LocalDateTimeNow.java",
                "import java.time.LocalDateTime; class A { LocalDateTime t = LocalDateTime.now(); }");
        files.put("clock/LocalTimeNow.java", "import java.time.LocalTime; class A { LocalTime t = LocalTime.now(); }");
        files.put("random/NewRandom.java", "import java.util.Random; class A { Random r = new Random(); }");
        files.put("random/MathRandom.java", "class A { double d = Math.random(); }");
        files.put("random/ThreadLocalRandomCurrent.java", "import java.util.concurrent.ThreadLocalRandom;\n"
                + "class A { int i = ThreadLocalRandom.current().nextInt(); }");
        files.put("random/ShuffleOneArgument.java",
                "import java.util.*; class A { void f(List<String> l) { Collections.shuffle(l); } }");
        files.put("heap/GetRuntime.java", "class A { long m = Runtime.getRuntime().maxMemory(); }");
        files.put("imports/StaticNanoTime.java", "import static java.lang.System.nanoTime;\n"
                + "class A { long t = nanoTime(); }");
        files.put("imports/StaticCurrentTimeMillis.java", "import static java.lang.System.currentTimeMillis;\n"
                + "class A { long t = currentTimeMillis(); }");
        files.put("imports/StaticSystemWildcard.java", "import static java.lang.System.*;\n"
                + "class A { long t = nanoTime(); }");
        files.put("imports/StaticShuffle.java", "import static java.util.Collections.shuffle;\n"
                + "class A { void f(java.util.List<?> l) { shuffle(l); } }");
        files.put("imports/StaticThreadLocalRandom.java",
                "import static java.util.concurrent.ThreadLocalRandom.current;\n"
                + "class A { int i = current().nextInt(); }");
        files.put("qualified/JavaLangSystemNanoTime.java", "class A { long t = java.lang.System.nanoTime ( ); }");
        files.put("qualified/SpacedAcrossLines.java", "class A {\n"
                + "    long t = java . lang . System\n"
                + "        .\n"
                + "        currentTimeMillis\n"
                + "        (\n"
                + "        );\n"
                + "}\n");
        files.put("qualified/CommentBetweenTheParts.java", "class A { long t = System./* the clock */nanoTime(); }");
        files.put("qualified/NewJavaUtilRandom.java", "class A { Object r = new java.util.Random ( ); }");
        files.put("qualified/ShuffleOfAGeneric.java", "import java.util.*; class A { void f() {\n"
                + "    java.util.Collections.shuffle(new ArrayList<Map.Entry<String, Integer>>(List.of())); } }");
        files.put("references/NanoTimeReference.java",
                "import java.util.function.LongSupplier; class A { LongSupplier t = System::nanoTime; }");
        files.put("references/SpacedReference.java",
                "class A { java.util.function.LongSupplier t = java.lang.System :: currentTimeMillis; }");
        files.put("references/RandomConstructorReference.java", "import java.util.Random;\n"
                + "import java.util.function.Supplier; class A { Supplier<Random> r = Random::new; }");
        files.put("references/ShuffleReference.java", "import java.util.*; import java.util.function.Consumer;\n"
                + "class A { Consumer<List<?>> s = Collections::shuffle; }");
        files.put("literals/ReadAfterTrickyLiterals.java", "class A {\n"
                + "    char q = '\"';\n"
                + "    String s = \"/* no comment\";\n"
                + "    String e = \"\\\" // still the string\";\n"
                + "    String b = \"\"\"\n"
                + "        a \"quote\" and \\\"\"\" inside\n"
                + "        \"\"\";\n"
                + "    long t = System.nanoTime(); // */ System.nanoTime()\n"
                + "}\n");
        files.put("Three.java", "class A {\n"
                + "    long t = System.nanoTime();\n"
                + "    double d = Math.random();\n"
                + "    long m = Runtime.getRuntime().totalMemory();\n"
                + "}\n");
        final Map<String, Integer> expected = new TreeMap<>();
        for (final String file : files.keySet()) {
            expected.put(file, file.equals("Three.java") ? 3 : 1);
        }
        final Map<String, Integer> found = scanOf(files);
        assertEquals(found, expected, "each spelling is one read, keyed by its path with /; the scan found " + found);
    }

    /** A seeded stream is no read: new Random(seed), the scope's MyRandom.getRandom(), a shuffle handed either. */
    @Test
    public void seededRandomsAreNoRead() {
        final Map<String, String> files = new TreeMap<>();
        files.put("SeededRandom.java", "import java.util.Random;\n"
                + "class A {\n"
                + "    Random r = new Random(42L);\n"
                + "    Random s = new Random(seed());\n"
                + "    Random u = new java.util.Random(7);\n"
                + "    static long seed() { return 1; }\n"
                + "}\n");
        files.put("ScopeStream.java", "import forge.util.MyRandom;\n"
                + "class A {\n"
                + "    java.util.Random r = MyRandom.getRandom();\n"
                + "    int i = MyRandom.getRandom().nextInt(3);\n"
                + "}\n");
        files.put("SeededShuffle.java", "import java.util.*;\n"
                + "import forge.util.MyRandom;\n"
                + "class A {\n"
                + "    void f(List<?> l, Random r) {\n"
                + "        Collections.shuffle(l, r);\n"
                + "        Collections.shuffle(l, MyRandom.getRandom());\n"
                + "        java.util.Collections.shuffle(new ArrayList<Map.Entry<String, Integer>>(List.of()),\n"
                + "                new Random(1));\n"
                + "        Collections . shuffle ( l ,\n"
                + "                r );\n"
                + "    }\n"
                + "}\n");
        files.put("StaticSeededShuffle.java", "import static java.util.Collections.shuffle;\n"
                + "class A { void f(java.util.List<?> l, java.util.Random r) { shuffle(l, r); } }");
        final Map<String, Integer> found = scanOf(files);
        assertEquals(found, Map.of(), "a seeded stream is no read, but the scan found " + found);
    }

    /**
     * What only looks like a read is none: a read's name in a comment, a string, a text block or a character literal,
     * another class's member of the same name, a bare call with no static import, an import alone, and a file that is
     * not Java source.
     */
    @Test
    public void aCommentAStringOrAnotherClassIsNoRead() {
        final Map<String, String> files = new TreeMap<>();
        files.put("Comments.java", "/** {@link System#nanoTime()}, System.currentTimeMillis(), Math.random(). */\n"
                + "class A {\n"
                + "    // new Random() would be unseeded\n"
                + "    /* Runtime.getRuntime() */ int i;\n"
                + "}\n");
        files.put("Strings.java", "class A {\n"
                + "    String s = \"new Random() and Runtime.getRuntime()\";\n"
                + "    char c = '\\'';\n"
                + "    String t = \"\"\"\n"
                + "        System.nanoTime() \"quoted\" ThreadLocalRandom\n"
                + "        \"\"\";\n"
                + "}\n");
        files.put("OtherClasses.java", "class A {\n"
                + "    long t = FlatMcGame.nanoTime();\n"
                + "    long u = MySystem.nanoTime();\n"
                + "    Object v = myRuntime.getRuntime();\n"
                + "    Object w = new MyRandom();\n"
                + "    long x = nanoTime();\n"
                + "    static long nanoTime() { return 0; }\n"
                + "}\n");
        files.put("Imports.java", "import java.util.concurrent.ThreadLocalRandom;\n"
                + "import static java.lang.System.nanoTime;\n"
                + "import java.util.Random;\n"
                + "class A { }\n");
        files.put("notes.txt", "System.nanoTime() Math.random()\n");
        final Map<String, Integer> found = scanOf(files);
        assertEquals(found, Map.of(), "none of these is a read, but the scan found " + found);
    }

    /**
     * The AI's reads are ALLOWED's, file by file and count by count: a new file that reads, or a read more or fewer in
     * a listed one, fails, naming the file, its count and lines, and ALLOWED's count.
     */
    @Test
    public void theAiReadsAreTheKnownOnes() {
        assertTrue(Files.isDirectory(AI_SOURCES), "the AI's sources are not at " + AI_SOURCES.toAbsolutePath());
        final Map<String, Integer> found = scan(AI_SOURCES);
        final Set<String> files = new TreeSet<>(found.keySet());
        files.addAll(ALLOWED.keySet());
        final List<String> wrong = new ArrayList<>();
        for (final String file : files) {
            final int reads = found.getOrDefault(file, 0);
            final Integer allowed = ALLOWED.get(file);
            if (allowed == null || reads != allowed) {
                wrong.add(file + ": " + reads + " read(s)"
                        + (reads == 0 ? "" : " on line(s) " + readLines(read(AI_SOURCES.resolve(file))))
                        + ", ALLOWED " + (allowed == null ? "has no entry" : "says " + allowed));
            }
        }
        if (!wrong.isEmpty()) {
            fail("the AI's sources read the clock, an unseeded random stream or the runtime where ALLOWED does not say"
                    + " so: route a read that steers a harness game through FlatMcGame.nanoTime(),"
                    + " MyRandom.getRandom() or a scope test, and give an inert one its count in ALLOWED and why\n  "
                    + String.join("\n  ", wrong));
        }
    }

    // ------------------------------------------------------------------------------------------------- the scanner

    /**
     * A read of a JDK class's member: {@code Owner.name(...)} or the method reference {@code Owner::name}, in any
     * spacing and fully qualified or not, or a bare {@code name(...)} in a file that imports the member, or its class,
     * statically.
     */
    private static final class Member {
        /** The member's name, which a bare call under a static import spells alone. */
        final String name;
        /** Whether only a call with one argument reads: Collections.shuffle, which with two shuffles by the caller's
         *  stream. A method reference counts whatever its arity, which its source does not show. */
        final boolean oneArgumentOnly;
        /** The static imports that make a bare call of {@link #name} this member's: the member's and its class's. */
        final Set<String> imports;
        final Pattern call;
        final Pattern reference;

        Member(final String pkg, final String owner, final String name, final boolean oneArgumentOnly) {
            this.name = name;
            this.oneArgumentOnly = oneArgumentOnly;
            this.imports = Set.of(pkg + "." + owner + "." + name, pkg + "." + owner + ".*");
            this.call = Pattern.compile("(?<![\\w$])" + owner + "\\s*\\.\\s*" + name + "\\s*\\(");
            this.reference = Pattern.compile("(?<![\\w$])" + owner + "\\s*::\\s*" + name + "(?![\\w$])");
        }
    }

    private static final List<Member> MEMBERS = List.of(
            new Member("java.lang", "System", "nanoTime", false),
            new Member("java.lang", "System", "currentTimeMillis", false),
            new Member("java.time", "Instant", "now", false),
            new Member("java.time", "LocalDateTime", "now", false),
            new Member("java.time", "LocalTime", "now", false),
            new Member("java.lang", "Math", "random", false),
            new Member("java.lang", "Runtime", "getRuntime", false),
            new Member("java.util", "Collections", "shuffle", true));

    /** {@code new Random()} with no seed, the class named plainly or fully qualified. */
    private static final Pattern NEW_RANDOM =
            Pattern.compile("(?<![\\w$])new\\s+(?:java\\s*\\.\\s*util\\s*\\.\\s*)?Random\\s*\\(\\s*\\)");
    /** {@code Random::new}, which may name the constructor with no seed. */
    private static final Pattern RANDOM_REFERENCE = Pattern.compile("(?<![\\w$])Random\\s*::\\s*new(?![\\w$])");
    /** The name ThreadLocalRandom, whose every stream is the machine's. */
    private static final Pattern THREAD_LOCAL_RANDOM = Pattern.compile("(?<![\\w$])ThreadLocalRandom(?![\\w$])");
    /** An import declaration: group 1 is set for a static one, group 2 is the name imported. */
    private static final Pattern IMPORT =
            Pattern.compile("(?<![\\w$])import\\s+(static\\s+)?([\\w$]+(?:\\s*\\.\\s*(?:[\\w$]+|\\*))*)\\s*;");

    /**
     * The machine-dependent reads in each {@code .java} file under {@code root}, by the file's path relative to
     * {@code root} with {@code /}; a file with none is left out. A read is a call of System.nanoTime,
     * System.currentTimeMillis, Instant.now, LocalDateTime.now, LocalTime.now, Math.random or Runtime.getRuntime, a
     * {@code new Random()} with no seed, a one-argument Collections.shuffle, or the name ThreadLocalRandom: in any
     * spacing, fully qualified or not, as a method reference too, and as a bare call of a member the file imports
     * statically, or whose class it does (see {@link #readLines}).
     */
    static Map<String, Integer> scan(final Path root) {
        final Map<String, Integer> found = new TreeMap<>();
        for (final Path file : javaFiles(root)) {
            final int reads = readLines(read(file)).size();
            if (reads > 0) {
                found.put(key(root, file), reads);
            }
        }
        return found;
    }

    /**
     * The line of each read in {@code source}, a Java file's text, in order: a line twice for two reads on it. Only
     * code is read: comments, string literals, text blocks and character literals are not code, and a plain import
     * declaration only names a class whose uses are counted where they are. A static import is code: it names the
     * members a bare call reads, and one of ThreadLocalRandom's is a read itself.
     */
    static List<Integer> readLines(final String source) {
        final StringBuilder blanked = new StringBuilder(code(source));
        final Set<String> staticImports = new HashSet<>();
        final List<int[]> plainImports = new ArrayList<>();
        final Matcher imports = IMPORT.matcher(blanked);
        while (imports.find()) {
            if (imports.group(1) != null) {
                staticImports.add(imports.group(2).replaceAll("\\s+", ""));
            } else {
                plainImports.add(new int[] { imports.start(), imports.end() });
            }
        }
        for (final int[] range : plainImports) {
            blank(blanked, range[0], range[1]);
        }
        final String code = blanked.toString();
        final List<Integer> at = new ArrayList<>();
        final Map<String, Boolean> bare = new TreeMap<>();
        for (final Member m : MEMBERS) {
            final Matcher call = m.call.matcher(code);
            while (call.find()) {
                if (!m.oneArgumentOnly || oneArgument(code, call.end())) {
                    at.add(call.start());
                }
            }
            final Matcher reference = m.reference.matcher(code);
            while (reference.find()) {
                at.add(reference.start());
            }
            if (!Collections.disjoint(m.imports, staticImports)) {
                bare.merge(m.name, m.oneArgumentOnly, Boolean::logicalAnd);
            }
        }
        for (final Map.Entry<String, Boolean> name : bare.entrySet()) {
            final Matcher call = Pattern.compile("(?<![\\w$])" + name.getKey() + "\\s*\\(").matcher(code);
            while (call.find()) {
                if (before(code, call.start()) != '.' && (!name.getValue() || oneArgument(code, call.end()))) {
                    at.add(call.start());
                }
            }
        }
        for (final Pattern p : List.of(NEW_RANDOM, RANDOM_REFERENCE, THREAD_LOCAL_RANDOM)) {
            final Matcher m = p.matcher(code);
            while (m.find()) {
                at.add(m.start());
            }
        }
        final List<Integer> lines = new ArrayList<>();
        for (final int offset : at) {
            lines.add(lineOf(code, offset));
        }
        Collections.sort(lines);
        return lines;
    }

    /**
     * {@code source} with every comment, string literal, text block and character literal blanked to spaces, its line
     * breaks kept, so that a read is looked for in code alone and its line is its line in the file.
     */
    static String code(final String source) {
        final StringBuilder out = new StringBuilder(source);
        int i = 0;
        while (i < source.length()) {
            final int end = literalEnd(source, i);
            if (end > i) {
                blank(out, i, end);
                i = end;
            } else {
                i++;
            }
        }
        return out.toString();
    }

    /** Where the comment or literal that starts at {@code i} ends, or {@code i} when none starts there. */
    private static int literalEnd(final String s, final int i) {
        if (s.startsWith("//", i)) {
            int j = i;
            while (j < s.length() && s.charAt(j) != '\n' && s.charAt(j) != '\r') {
                j++;
            }
            return j;
        }
        if (s.startsWith("/*", i)) {
            final int close = s.indexOf("*/", i + 2);
            return close < 0 ? s.length() : close + 2;
        }
        if (s.startsWith("\"\"\"", i)) {
            int j = i + 3;
            while (j < s.length() && !s.startsWith("\"\"\"", j)) {
                j += s.charAt(j) == '\\' ? 2 : 1;
            }
            return Math.min(s.length(), j + 3);
        }
        final char quote = s.charAt(i);
        if (quote == '"' || quote == '\'') {
            int j = i + 1;
            while (j < s.length() && s.charAt(j) != quote && s.charAt(j) != '\n' && s.charAt(j) != '\r') {
                j += s.charAt(j) == '\\' ? 2 : 1;
            }
            return Math.min(s.length(), j + 1);
        }
        return i;
    }

    /** Spaces over {@code text}'s characters from {@code from} to {@code to}, but its line breaks. */
    private static void blank(final StringBuilder text, final int from, final int to) {
        for (int j = from; j < to; j++) {
            final char c = text.charAt(j);
            if (c != '\n' && c != '\r') {
                text.setCharAt(j, ' ');
            }
        }
    }

    /**
     * Whether the argument list that opens just before {@code from} holds one argument: no comma at its own level
     * before its closing parenthesis. Parentheses, brackets, braces and a generic's angle brackets nest, and an angle
     * bracket still open at a closing one was a less-than; a less-than in the first argument hides the comma after it,
     * so such a call counts, as does a list that never closes: the check errs toward a read.
     */
    private static boolean oneArgument(final CharSequence code, final int from) {
        final Deque<Character> open = new ArrayDeque<>();
        for (int i = from; i < code.length(); i++) {
            final char c = code.charAt(i);
            if (c == '(' || c == '[' || c == '{' || c == '<') {
                open.push(c);
            } else if (c == '>') {
                if (!open.isEmpty() && open.peek() == '<') {
                    open.pop();
                }
            } else if (c == ')' || c == ']' || c == '}') {
                while (!open.isEmpty() && open.peek() == '<') {
                    open.pop();
                }
                if (open.isEmpty()) {
                    return true;
                }
                open.pop();
            } else if (c == ',' && open.isEmpty()) {
                return false;
            }
        }
        return true;
    }

    /** The last character before {@code at} that is not white space, or a space when there is none. */
    private static char before(final CharSequence code, final int at) {
        for (int i = at - 1; i >= 0; i--) {
            if (!Character.isWhitespace(code.charAt(i))) {
                return code.charAt(i);
            }
        }
        return ' ';
    }

    /** The line, counted from 1, that holds {@code code}'s character at {@code at}. */
    private static int lineOf(final CharSequence code, final int at) {
        int line = 1;
        for (int i = 0; i < at; i++) {
            if (code.charAt(i) == '\n') {
                line++;
            }
        }
        return line;
    }

    // ------------------------------------------------------------------------------------------------- files

    private static List<Path> javaFiles(final Path root) {
        try (Stream<Path> paths = Files.walk(root)) {
            return paths.filter(p -> Files.isRegularFile(p) && p.getFileName().toString().endsWith(".java"))
                    .sorted().collect(Collectors.toList());
        } catch (final IOException e) {
            throw new UncheckedIOException("cannot list " + root, e);
        }
    }

    private static String read(final Path file) {
        try {
            return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        } catch (final IOException e) {
            throw new UncheckedIOException("cannot read " + file, e);
        }
    }

    /** {@code file}'s path relative to {@code root}, its names joined with {@code /} on every platform. */
    private static String key(final Path root, final Path file) {
        final List<String> names = new ArrayList<>();
        for (final Path name : root.relativize(file)) {
            names.add(name.toString());
        }
        return String.join("/", names);
    }

    /** What {@link #scan} finds in a scratch tree holding {@code files}, each path's text, deleted afterwards. */
    private static Map<String, Integer> scanOf(final Map<String, String> files) {
        final Path root;
        try {
            root = Files.createTempDirectory("ai-clock-reads");
        } catch (final IOException e) {
            throw new UncheckedIOException("cannot make a scratch tree", e);
        }
        try {
            for (final Map.Entry<String, String> file : files.entrySet()) {
                final Path path = root.resolve(file.getKey());
                Files.createDirectories(path.getParent());
                Files.writeString(path, file.getValue(), StandardCharsets.UTF_8);
            }
            return scan(root);
        } catch (final IOException e) {
            throw new UncheckedIOException("cannot write the scratch tree " + root, e);
        } finally {
            delete(root);
        }
    }

    private static void delete(final Path root) {
        try (Stream<Path> paths = Files.walk(root)) {
            for (final Path p : paths.sorted(Comparator.reverseOrder()).collect(Collectors.toList())) {
                Files.deleteIfExists(p);
            }
        } catch (final IOException e) {
            throw new UncheckedIOException("cannot delete the scratch tree " + root, e);
        }
    }
}
