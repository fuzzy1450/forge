package forge.ai.combo;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * The combo table the AI reads: Commander Spellbook's variants, facts only, plus the hand-written x- lines, keyed by
 * Forge's card names (spec 2026-10-22-combo-aware-ai-core-design.md section 6.1). The main repo's
 * scripts/build_combo_table.py writes it into this module's resources when an engine release is cut, so it rides the
 * engine jar and the engine id pins it; {@link #shipped} reads it once per JVM and it never changes afterwards.
 *
 * <p>One record a line; '#' lines and blank lines are skipped and a trailing CR is dropped:
 * <ul>
 * <li>{@code F|feature id|status|name}: a result feature ("Win the game");</li>
 * <li>{@code V|variant id|mana value|mana|feature ids|piece|piece...}: a combo line. Mana is blank where Spellbook
 * gives it as prose; feature ids are comma-separated, each one an F line above defines; a piece is
 * {@code zones[*]:name}, its start zones from {@link #ZONES} (any one of them), then {@code *} when it must be the
 * commander. A card the line uses twice is two pieces.</li>
 * </ul>
 * A malformed line refuses the whole table ({@code IllegalArgumentException "combos.txt line N: <why>"}): a table
 * read in part would play a different AI under the same engine id.
 */
public final class ComboTable {
    /** A result feature: Spellbook's id, its status letter and its name. */
    public record Feature(int id, String status, String name) {}

    /** One card of a line: its Forge name, the zones it may start in (letters of {@link #ZONES}, any one of them)
     *  and whether it must be the commander. */
    public record Piece(String name, String zones, boolean commander) {
        /** Whether {@code zone} is one of this piece's start zones. */
        public boolean needs(char zone) {
            return zones.indexOf(zone) >= 0;
        }
    }

    /** One combo line: its id, the mana value it needs, its mana ("" where Spellbook gives prose), its result
     *  feature ids and its pieces, each as the table writes them. Both lists are immutable copies. */
    public record Variant(String id, int manaValue, String mana, List<Integer> features, List<Piece> pieces) {
        public Variant {
            features = List.copyOf(features);
            pieces = List.copyOf(pieces);
        }
    }

    /** The shipped table's classpath name, inside forge-ai's jar. */
    public static final String RESOURCE = "/forge/ai/combo/combos.txt";

    /** Spellbook's zone letters, in the order the builder writes them: hand, battlefield, command zone, exile,
     *  graveyard, library. */
    static final String ZONES = "HBCEGL";

    private static final Pattern MANA = Pattern.compile("(\\{[^{}|]+\\})+");

    private final List<Variant> variants;
    private final Map<Integer, Feature> features;
    /** Every line by its id, built with {@link #variants} by {@link #parse}; only ever looked up, never iterated. */
    private final Map<String, Variant> byId;

    private ComboTable(final List<Variant> variants, final Map<Integer, Feature> features,
                       final Map<String, Variant> byId) {
        this.variants = variants;
        this.features = features;
        this.byId = byId;
    }

    /** The table these lines hold (a file's lines, in order: line N of the message is {@code lines.get(N - 1)}).
     *  Names, zone strings, mana strings, feature lists and pieces are interned through one map for the parse, so
     *  every row naming a piece shares one object. */
    public static ComboTable parse(final List<String> lines) {
        final Map<Object, Object> pool = new HashMap<>();
        final Map<Integer, Feature> features = new TreeMap<>();
        final List<Variant> variants = new ArrayList<>();
        final Map<String, Variant> byId = new HashMap<>();
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.endsWith("\r")) {
                line = line.substring(0, line.length() - 1);
            }
            if (line.isBlank() || line.startsWith("#")) {
                continue;
            }
            final int n = i + 1;
            final String[] f = line.split("\\|", -1);
            switch (f[0]) {
                case "F" -> {
                    final Feature feature = feature(f, n, pool);
                    if (features.put(feature.id(), feature) != null) {
                        throw bad(n, "feature " + feature.id() + " appears twice");
                    }
                }
                case "V" -> {
                    final Variant variant = variant(f, n, features, pool);
                    if (byId.putIfAbsent(variant.id(), variant) != null) {
                        throw bad(n, "variant " + variant.id() + " appears twice");
                    }
                    variants.add(variant);
                }
                default -> throw bad(n, "the record type '" + f[0] + "' is neither F nor V");
            }
        }
        return new ComboTable(List.copyOf(variants), Collections.unmodifiableMap(new LinkedHashMap<>(features)),
                Collections.unmodifiableMap(byId));
    }

    /** The table shipped in forge-ai's jar, read on first use and held for the JVM's life. A missing, unreadable
     *  or malformed resource is an IllegalStateException on every call (never an ExceptionInInitializerError,
     *  which a game thread would meet as an Error). */
    public static ComboTable shipped() {
        if (Holder.TABLE == null) {
            throw new IllegalStateException(Holder.FAILURE);
        }
        return Holder.TABLE;
    }

    /** Every line, in file order. */
    public List<Variant> variants() {
        return variants;
    }

    /** The line whose id is {@code id} (ids are unique: the parse refuses a second), or null when the table holds
     *  none; the very object {@link #variants} holds. */
    public Variant variant(final String id) {
        return byId.get(id);
    }

    /** Every feature, in id order. */
    public Map<Integer, Feature> features() {
        return features;
    }

    /** The number of lines (V records). */
    public int size() {
        return variants.size();
    }

    /** The table a classpath resource holds, read as UTF-8 (malformed bytes refuse). */
    static ComboTable load(final String resource) {
        final InputStream in = ComboTable.class.getResourceAsStream(resource);
        if (in == null) {
            throw new IllegalStateException("the combo table " + resource + " is not on the classpath");
        }
        final List<String> lines = new ArrayList<>();
        try (BufferedReader reader =
                new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8.newDecoder()))) {
            for (String line = reader.readLine(); line != null; line = reader.readLine()) {
                lines.add(line);
            }
        } catch (final IOException e) {
            throw new IllegalStateException("the combo table " + resource + " cannot be read: " + e, e);
        }
        try {
            return parse(lines);
        } catch (final IllegalArgumentException e) {
            throw new IllegalStateException("the combo table " + resource + " is malformed: " + e.getMessage(), e);
        }
    }

    /** The shipped table, or why there is none: both final, set once when the class initialises. */
    private static final class Holder {
        static final ComboTable TABLE;
        static final String FAILURE;

        static {
            ComboTable table = null;
            String failure = null;
            try {
                table = load(RESOURCE);
            } catch (final RuntimeException e) {
                failure = e.getMessage();
            }
            TABLE = table;
            FAILURE = failure;
        }
    }

    private static Feature feature(final String[] f, final int n, final Map<Object, Object> pool) {
        if (f.length != 4) {
            throw bad(n, "an F line has 4 fields, not " + f.length);
        }
        final int id = whole(f[1]);
        if (id < 0) {
            throw bad(n, "feature id '" + f[1] + "' is not a whole number");
        }
        if (f[2].isEmpty()) {
            throw bad(n, "feature " + id + " has no status");
        }
        if (f[3].isEmpty()) {
            throw bad(n, "feature " + id + " has no name");
        }
        return new Feature(id, intern(pool, f[2]), intern(pool, f[3]));
    }

    private static Variant variant(final String[] f, final int n, final Map<Integer, Feature> features,
                                   final Map<Object, Object> pool) {
        if (f.length < 6) {
            throw bad(n, "a V line has at least 6 fields (id, mana value, mana, feature ids, pieces), not " + f.length);
        }
        final String id = f[1];
        if (id.isEmpty()) {
            throw bad(n, "the variant has no id");
        }
        final int manaValue = whole(f[2]);
        if (manaValue < 0) {
            throw bad(n, "variant " + id + "'s mana value '" + f[2] + "' is not a whole number");
        }
        final String mana = f[3];
        if (!mana.isEmpty() && !MANA.matcher(mana).matches()) {
            throw bad(n, "variant " + id + "'s mana '" + mana + "' is neither blank nor mana symbols");
        }
        if (f[4].isEmpty()) {
            throw bad(n, "variant " + id + " has no feature ids");
        }
        final List<Integer> ids = new ArrayList<>();
        for (final String raw : f[4].split(",", -1)) {
            final int fid = whole(raw);
            if (fid < 0) {
                throw bad(n, "variant " + id + "'s feature id '" + raw + "' is not a whole number");
            }
            if (ids.contains(fid)) {
                throw bad(n, "variant " + id + " names feature " + fid + " twice");
            }
            if (!features.containsKey(fid)) {
                throw bad(n, "variant " + id + " names feature " + fid + ", which no F line above defines");
            }
            ids.add(fid);
        }
        final List<Piece> pieces = new ArrayList<>(f.length - 5);
        for (int j = 5; j < f.length; j++) {
            pieces.add(intern(pool, piece(f[j], id, n, pool)));
        }
        return new Variant(id, manaValue, intern(pool, mana), intern(pool, List.copyOf(ids)), pieces);
    }

    private static Piece piece(final String raw, final String id, final int n, final Map<Object, Object> pool) {
        final int colon = raw.indexOf(':');
        if (colon < 0) {
            throw bad(n, "variant " + id + "'s piece '" + raw + "' is not zones[*]:name");
        }
        final String name = raw.substring(colon + 1);
        if (name.isEmpty()) {
            throw bad(n, "variant " + id + "'s piece '" + raw + "' has no name");
        }
        final boolean commander = colon > 0 && raw.charAt(colon - 1) == '*';
        final String zones = raw.substring(0, commander ? colon - 1 : colon);
        for (int k = 0; k < zones.length(); k++) {
            final char z = zones.charAt(k);
            if (ZONES.indexOf(z) < 0) {
                throw bad(n, "variant " + id + " puts " + name + " in zone '" + z + "', which is not one of " + ZONES);
            }
            if (zones.indexOf(z) < k) {
                throw bad(n, "variant " + id + " puts " + name + " in zone '" + z + "' twice");
            }
        }
        return new Piece(intern(pool, name), intern(pool, zones), commander);
    }

    /** The number {@code s} spells as ASCII digits, or -1 when it is not one an int holds. */
    private static int whole(final String s) {
        if (s.isEmpty()) {
            return -1;
        }
        for (int i = 0; i < s.length(); i++) {
            final char c = s.charAt(i);
            if (c < '0' || c > '9') {
                return -1;
            }
        }
        try {
            return Integer.parseInt(s);
        } catch (final NumberFormatException e) {
            return -1;
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> T intern(final Map<Object, Object> pool, final T value) {
        return (T) pool.computeIfAbsent(value, k -> k);
    }

    private static IllegalArgumentException bad(final int n, final String why) {
        return new IllegalArgumentException("combos.txt line " + n + ": " + why);
    }
}
