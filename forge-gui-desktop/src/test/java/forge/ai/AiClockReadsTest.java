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
 * differ by machine). Every default seat of a harness game (every seat the farm deals) plays Flat MC, and one seed has
 * to play one game on every machine, beside other games or alone, as verify replays it, so nothing the AI or the rules
 * engine decides may follow the machine's clock, an unseeded random stream or the runtime. This test scans the AI's
 * sources and forge-game's for such reads and fails on any that is not on {@link #ALLOWED} or {@link #GAME_ALLOWED},
 * their short lists of known reads that are inert in a harness game: after an upstream merge that adds one, the fork's
 * suite fails here, naming its file, before an engine is built from the merge (the engine build itself skips the
 * tests). A read that steers a harness game is not added to a list: it goes through the counted clock
 * ({@code FlatMcGame.nanoTime()}), the scope's random stream ({@code MyRandom.getRandom()}) or behind a scope test, as
 * the storm solver's and the picker's heap reads do.
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

    /** forge-game's sources, the rules engine every harness game runs, seen from forge-gui-desktop. */
    private static final Path GAME_SOURCES = Path.of("..", "forge-game", "src", "main", "java");

    /**
     * Every file of forge-game's sources that reads, by its path under forge-game/src/main/java, with its number of
     * reads, each inert in a harness game for the reason on the line above it, as {@link #ALLOWED} lists the AI's.
     */
    static final Map<String, Integer> GAME_ALLOWED = Map.of(
            // a format file's date that does not parse is taken as today's: read where the formats load, for the
            // GUI's format lists and deck legality, and no harness game asks a format for its date
            "forge/game/GameFormat.java", 1,
            // the match's start time, which only the log's "match duration" line reads, written once the game is over
            "forge/game/GameLog.java", 1,
            // that line itself: game-log text, written after the game's outcome, which nothing decides from
            "forge/game/GameLogFormatter.java", 1,
            // the heap budget of the undo history and the AI's rollback snapshot: checkpoint() runs only before a
            // human seat's priority prompt, and a harness game seats none; markForRollback only when
            // PlayerControllerAi.AI_ROLLBACK, a constant false
            "forge/game/undo/UndoManager.java", 2);

    /**
     * Every spelling of a read is seen, once: each file of a scratch tree reads once in a spelling of its own (the
     * plain call, fully qualified, spaced across lines, with explicit type arguments, a method reference, a bare call
     * under a static import of the member or of its class, a read after literals that look like comments), and one
     * file reads three times.
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
        files.put("clock/LocalDateNow.java", "import java.time.LocalDate; class A { LocalDate t = LocalDate.now(); }");
        files.put("clock/ZonedDateTimeNow.java",
                "import java.time.ZonedDateTime; class A { ZonedDateTime t = ZonedDateTime.now(); }");
        files.put("clock/OffsetDateTimeNow.java",
                "import java.time.OffsetDateTime; class A { OffsetDateTime t = OffsetDateTime.now(); }");
        files.put("clock/OffsetTimeNow.java", "import java.time.OffsetTime; class A { OffsetTime t = OffsetTime.now(); }");
        files.put("clock/YearNow.java", "import java.time.Year; class A { Year t = Year.now(); }");
        files.put("clock/YearMonthNow.java", "import java.time.YearMonth; class A { YearMonth t = YearMonth.now(); }");
        files.put("clock/MonthDayNow.java", "import java.time.MonthDay; class A { MonthDay t = MonthDay.now(); }");
        files.put("clock/ClockSystemUTC.java", "import java.time.Clock; class A { Clock c = Clock.systemUTC(); }");
        files.put("clock/ClockSystemDefaultZone.java",
                "import java.time.Clock; class A { Clock c = Clock.systemDefaultZone(); }");
        files.put("clock/ClockSystem.java", "import java.time.*; class A { Clock c = Clock.system(ZoneId.of(\"UTC\")); }");
        files.put("clock/NewDate.java", "import java.util.Date; class A { Date d = new Date(); }");
        files.put("clock/CalendarGetInstance.java",
                "import java.util.Calendar; class A { Calendar c = Calendar.getInstance(); }");
        files.put("random/NewRandom.java", "import java.util.Random; class A { Random r = new Random(); }");
        files.put("random/MathRandom.java", "class A { double d = Math.random(); }");
        files.put("random/StrictMathRandom.java", "class A { double d = StrictMath.random(); }");
        files.put("random/NewSecureRandom.java", "class A { Object r = new java.security.SecureRandom(); }");
        files.put("random/SecureRandomInstance.java", "import java.security.*;\n"
                + "class A { Object r() throws Exception { return SecureRandom.getInstanceStrong(); } }");
        files.put("random/NewSplittableRandom.java",
                "import java.util.SplittableRandom; class A { Object r = new SplittableRandom(); }");
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
        files.put("imports/StaticClockSystemUTC.java", "import static java.time.Clock.systemUTC;\n"
                + "class A { Object c = systemUTC(); }");
        files.put("imports/StaticLocalDateWildcard.java", "import static java.time.LocalDate.*;\n"
                + "class A { Object d = now(); }");
        files.put("imports/StaticStrictMathRandom.java", "import static java.lang.StrictMath.random;\n"
                + "class A { double d = random(); }");
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
        files.put("qualified/TypedShuffle.java",
                "import java.util.*; class A { void f(List<String> l) { Collections.<String>shuffle(l); } }");
        files.put("qualified/TypedShuffleOfAGeneric.java", "import java.util.*; class A { void f() {\n"
                + "    java.util.Collections . <Map.Entry<String, Integer>> shuffle(new ArrayList<>()); } }");
        files.put("qualified/NewJavaUtilDate.java", "class A { Object d = new java.util.Date ( ); }");
        files.put("references/NanoTimeReference.java",
                "import java.util.function.LongSupplier; class A { LongSupplier t = System::nanoTime; }");
        files.put("references/SpacedReference.java",
                "class A { java.util.function.LongSupplier t = java.lang.System :: currentTimeMillis; }");
        files.put("references/RandomConstructorReference.java", "import java.util.Random;\n"
                + "import java.util.function.Supplier; class A { Supplier<Random> r = Random::new; }");
        files.put("references/ShuffleReference.java", "import java.util.*; import java.util.function.Consumer;\n"
                + "class A { Consumer<List<?>> s = Collections::shuffle; }");
        files.put("references/TypedShuffleReference.java", "import java.util.*; import java.util.function.Consumer;\n"
                + "class A { Consumer<List<String>> s = Collections::<String>shuffle; }");
        files.put("references/ClockReference.java", "import java.time.Clock; import java.util.function.Supplier;\n"
                + "class A { Supplier<Clock> c = Clock::systemUTC; }");
        files.put("references/DateConstructorReference.java", "import java.util.Date;\n"
                + "import java.util.function.Supplier; class A { Supplier<Date> d = Date::new; }");
        files.put("references/SplittableRandomConstructorReference.java", "import java.util.SplittableRandom;\n"
                + "import java.util.function.Supplier; class A { Supplier<SplittableRandom> r = SplittableRandom::new; }");
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

    /**
     * A seeded stream is no read: new Random(seed), the scope's MyRandom.getRandom(), a shuffle handed either (with
     * explicit type arguments too), a SplittableRandom of a seed and what it splits; nor is a Date of a given time.
     */
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
        files.put("TypedSeededShuffle.java", "import java.util.*;\n"
                + "class A { void f(List<String> l, Random r) { Collections.<String>shuffle(l, r); } }");
        files.put("SeededSplittableRandom.java", "import java.util.SplittableRandom;\n"
                + "class A {\n"
                + "    SplittableRandom r = new SplittableRandom(42L);\n"
                + "    SplittableRandom s = r.split();\n"
                + "    Object u = new java.util.SplittableRandom(7);\n"
                + "}\n");
        files.put("GivenDate.java", "import java.util.Date;\n"
                + "class A { Date d = new Date(0L); Date e = new java.util.Date(1_000L); }");
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
                + "    Object y = MyClock.systemUTC();\n"
                + "    Object z = myCalendar.getInstance();\n"
                + "    Object d = new DateTime();\n"
                + "    Object s = new NotSecureRandomAtAll();\n"
                + "    Object m = OffsetTimes.now();\n"
                + "    static long nanoTime() { return 0; }\n"
                + "}\n");
        files.put("MoreComments.java", "/** {@link java.time.Clock#systemUTC()}, new Date(), Calendar.getInstance(). */\n"
                + "class A {\n"
                + "    // new SplittableRandom() and SecureRandom would be the machine's\n"
                + "    String s = \"LocalDate.now() StrictMath.random() Collections.<T>shuffle(l)\";\n"
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
        assertKnownReads("the AI's sources", AI_SOURCES, "ALLOWED", ALLOWED);
    }

    /**
     * forge-game's reads are GAME_ALLOWED's, as the AI's are ALLOWED's: the rules engine runs every harness game too,
     * so an upstream merge that adds a read there fails here just the same.
     */
    @Test
    public void theGameReadsAreTheKnownOnes() {
        assertKnownReads("forge-game's sources", GAME_SOURCES, "GAME_ALLOWED", GAME_ALLOWED);
    }

    /**
     * Fails unless the reads under {@code root} are {@code allowed}'s, file by file and count by count, naming each
     * file that differs with its count and lines and the list's count.
     */
    private static void assertKnownReads(final String what, final Path root, final String listName,
                                         final Map<String, Integer> allowed) {
        assertTrue(Files.isDirectory(root), what + " are not at " + root.toAbsolutePath());
        final Map<String, Integer> found = scan(root);
        final Set<String> files = new TreeSet<>(found.keySet());
        files.addAll(allowed.keySet());
        final List<String> wrong = new ArrayList<>();
        for (final String file : files) {
            final int reads = found.getOrDefault(file, 0);
            final Integer listed = allowed.get(file);
            if (listed == null || reads != listed) {
                wrong.add(file + ": " + reads + " read(s)"
                        + (reads == 0 ? "" : " on line(s) " + readLines(read(root.resolve(file))))
                        + ", " + listName + " " + (listed == null ? "has no entry" : "says " + listed));
            }
        }
        if (!wrong.isEmpty()) {
            fail(what + " read the clock, an unseeded random stream or the runtime where " + listName + " does not say"
                    + " so: route a read that steers a harness game through FlatMcGame.nanoTime(),"
                    + " MyRandom.getRandom() or a scope test, and give an inert one its count in " + listName
                    + " and why\n  " + String.join("\n  ", wrong));
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
            this.call = Pattern.compile("(?<![\\w$])" + owner + "\\s*\\.\\s*" + TYPE_ARGUMENTS + name + "\\s*\\(");
            this.reference = Pattern.compile("(?<![\\w$])" + owner + "\\s*::\\s*" + TYPE_ARGUMENTS + name + "(?![\\w$])");
        }
    }

    /** Explicit type arguments, which a call or a method reference may give before the member's name:
     *  {@code Collections.<String>shuffle(l)}, {@code Collections::<String>shuffle}. */
    private static final String TYPE_ARGUMENTS = "(?:<[^()]*?>\\s*)?";

    private static final List<Member> MEMBERS = List.of(
            new Member("java.lang", "System", "nanoTime", false),
            new Member("java.lang", "System", "currentTimeMillis", false),
            new Member("java.time", "Instant", "now", false),
            new Member("java.time", "LocalDateTime", "now", false),
            new Member("java.time", "LocalTime", "now", false),
            new Member("java.time", "LocalDate", "now", false),
            new Member("java.time", "ZonedDateTime", "now", false),
            new Member("java.time", "OffsetDateTime", "now", false),
            new Member("java.time", "OffsetTime", "now", false),
            new Member("java.time", "Year", "now", false),
            new Member("java.time", "YearMonth", "now", false),
            new Member("java.time", "MonthDay", "now", false),
            new Member("java.time", "Clock", "systemUTC", false),
            new Member("java.time", "Clock", "systemDefaultZone", false),
            new Member("java.time", "Clock", "system", false),
            new Member("java.util", "Calendar", "getInstance", false),
            new Member("java.lang", "Math", "random", false),
            new Member("java.lang", "StrictMath", "random", false),
            new Member("java.lang", "Runtime", "getRuntime", false),
            new Member("java.util", "Collections", "shuffle", true));

    /** The classes whose constructor with no argument reads: Random and SplittableRandom take no seed, Date the time. */
    private static final String UNSEEDED = "(?:Random|SplittableRandom|Date)";
    /** {@code new Random()}, {@code new SplittableRandom()} or {@code new Date()}, named plainly or fully qualified. */
    private static final Pattern NEW_UNSEEDED = Pattern.compile(
            "(?<![\\w$])new\\s+(?:java\\s*\\.\\s*util\\s*\\.\\s*)?" + UNSEEDED + "\\s*\\(\\s*\\)");
    /** {@code Random::new} and the like, which may name the constructor with no argument. */
    private static final Pattern UNSEEDED_REFERENCE = Pattern.compile("(?<![\\w$])" + UNSEEDED + "\\s*::\\s*new(?![\\w$])");
    /** The names ThreadLocalRandom and SecureRandom, whose every stream is the machine's. */
    private static final Pattern MACHINE_RANDOM =
            Pattern.compile("(?<![\\w$])(?:ThreadLocalRandom|SecureRandom)(?![\\w$])");
    /** An import declaration: group 1 is set for a static one, group 2 is the name imported. */
    private static final Pattern IMPORT =
            Pattern.compile("(?<![\\w$])import\\s+(static\\s+)?([\\w$]+(?:\\s*\\.\\s*(?:[\\w$]+|\\*))*)\\s*;");

    /**
     * The machine-dependent reads in each {@code .java} file under {@code root}, by the file's path relative to
     * {@code root} with {@code /}; a file with none is left out. A read is a call of System.nanoTime,
     * System.currentTimeMillis, the now() of Instant, LocalDateTime, LocalTime, LocalDate, ZonedDateTime,
     * OffsetDateTime, OffsetTime, Year, YearMonth or MonthDay, Clock.systemUTC, Clock.systemDefaultZone, Clock.system,
     * Calendar.getInstance, Math.random, StrictMath.random or Runtime.getRuntime, a {@code new Random()} or
     * {@code new SplittableRandom()} with no seed, a {@code new Date()} of no given time, a one-argument
     * Collections.shuffle, or the name ThreadLocalRandom or SecureRandom: in any spacing, fully qualified or not, with
     * explicit type arguments or without, as a method reference too, and as a bare call of a member the file imports
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
     * members a bare call reads, and one naming ThreadLocalRandom or SecureRandom is a read itself.
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
        for (final Pattern p : List.of(NEW_UNSEEDED, UNSEEDED_REFERENCE, MACHINE_RANDOM)) {
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
