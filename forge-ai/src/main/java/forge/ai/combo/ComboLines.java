package forge.ai.combo;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The rows of the combo table the AI can execute, two families of them. Piece 1's lines, the empty-library family
 * (spec 2026-10-22-combo-aware-ai-core-design.md section 6.4): exactly two pieces, an emptier and a library-out winner.
 * Piece 2's loop lines (spec 2026-10-29-combo-aware-ai-loops-design.md sections 6.1 and 6.7): the 19 rows of
 * {@link LoopRows}, Kinnan, Bonder Prodigy's mana loops, each carrying the kind of cycle that runs it. Every other row
 * of the table is ignored until its executor exists (piece 1's section 6.2), so a deck holding none of these plays as
 * it did before.
 */
public final class ComboLines {
    /** The cards that empty our library. */
    public enum Emptier {
        CONSULTATION("Demonic Consultation"), PACT("Tainted Pact");

        /** The card's Forge name, as the table writes it. */
        public final String card;

        Emptier(final String card) {
            this.card = card;
        }
    }

    /** The cards that win with an empty library: Oracle off its own trigger, the rest from play. */
    public enum Winner {
        ORACLE("Thassa's Oracle"), LAB("Laboratory Maniac"), JACE("Jace, Wielder of Mysteries"),
        DOOM("Doctor Doom, Unrivaled"), FBLTHP("Fblthp, Impossibly Lost");

        /** The card's Forge name, as the table writes it. */
        public final String card;

        Winner(final String card) {
            this.card = card;
        }
    }

    /** One line: the table's row; for piece 1's family which emptier and which winner its two pieces are, for piece 2's
     *  the kind of cycle that runs it ({@code loop}, null for piece 1's lines; a loop line's emptier and winner are
     *  null); and its pieces' card names, made once with the line: piece 1's the emptier's then the winner's (whatever
     *  order the row writes them in), a loop line's in the row's order. */
    public record Line(ComboTable.Variant variant, Emptier emptier, Winner winner, LoopKind loop, List<String> names) {
        /** Piece 1's line of the row {@code variant} whose pieces are {@code emptier} and {@code winner}: no loop. */
        public Line(final ComboTable.Variant variant, final Emptier emptier, final Winner winner) {
            this(variant, emptier, winner, null, List.of(emptier.card, winner.card));
        }

        /** The loop line of the row {@code variant}, run by a cycle of {@code kind}: no emptier and no winner, its
         *  pieces the row's, in the row's order. */
        public Line(final ComboTable.Variant variant, final LoopKind kind) {
            this(variant, null, null, Objects.requireNonNull(kind, "kind"), pieceNames(variant));
        }

        /** Whether this is a loop line (piece 2's family), not piece 1's. */
        public boolean isLoop() {
            return loop != null;
        }

        // the row's piece names, in its order; immutable
        private static List<String> pieceNames(final ComboTable.Variant variant) {
            final List<String> names = new ArrayList<>(variant.pieces().size());
            for (final ComboTable.Piece p : variant.pieces()) {
                names.add(p.name());
            }
            return List.copyOf(names);
        }
    }

    private ComboLines() {
    }

    /** The line {@code v} is, or null. Piece 1's rule first: exactly two pieces, one emptier and one winner, in either
     *  order, and Tainted Pact empties only for Thassa's Oracle, Laboratory Maniac and Jace (section 6.4). Then a loop
     *  row's ({@link LoopRows#kindOf}, by id): a loop line of its kind, whatever its pieces. */
    public static Line claim(final ComboTable.Variant v) {
        final Line emptyLibrary = emptyLibrary(v);
        if (emptyLibrary != null) {
            return emptyLibrary;
        }
        final LoopKind kind = LoopRows.kindOf(v.id());
        return kind == null ? null : new Line(v, kind);
    }

    // piece 1's line of v, or null
    private static Line emptyLibrary(final ComboTable.Variant v) {
        if (v.pieces().size() != 2) {
            return null;
        }
        final String a = v.pieces().get(0).name();
        final String b = v.pieces().get(1).name();
        Emptier emptier = emptier(a);
        Winner winner = winner(b);
        if (emptier == null || winner == null) {
            emptier = emptier(b);
            winner = winner(a);
        }
        if (emptier == null || winner == null) {
            return null;
        }
        if (emptier == Emptier.PACT && !pactFeeds(winner)) {
            return null;
        }
        return new Line(v, emptier, winner);
    }

    /** Every line {@code t} holds, in its file order; immutable. */
    public static List<Line> claimed(final ComboTable t) {
        final List<Line> lines = new ArrayList<>();
        for (final ComboTable.Variant v : t.variants()) {
            final Line l = claim(v);
            if (l != null) {
                lines.add(l);
            }
        }
        return List.copyOf(lines);
    }

    /** The lines of the table the engine ships ({@link ComboTable#shipped}), claimed once and held for the JVM's life.
     *  The shipped table's own failure is an IllegalStateException on every call, as it is there. */
    public static List<Line> shipped() {
        if (Holder.LINES == null) {
            throw new IllegalStateException(Holder.FAILURE);
        }
        return Holder.LINES;
    }

    /** The shipped lines, or why there are none: both final, set once when the class initialises. */
    private static final class Holder {
        static final List<Line> LINES;
        static final String FAILURE;

        static {
            List<Line> lines = null;
            String failure = null;
            try {
                lines = claimed(ComboTable.shipped());
            } catch (final RuntimeException e) {
                failure = e.getMessage();
            }
            LINES = lines;
            FAILURE = failure;
        }
    }

    private static Emptier emptier(final String name) {
        for (final Emptier e : Emptier.values()) {
            if (e.card.equals(name)) {
                return e;
            }
        }
        return null;
    }

    private static Winner winner(final String name) {
        for (final Winner w : Winner.values()) {
            if (w.card.equals(name)) {
                return w;
            }
        }
        return null;
    }

    /** Whether Tainted Pact empties for {@code w}: Spellbook pairs it with these three only. */
    private static boolean pactFeeds(final Winner w) {
        return w == Winner.ORACLE || w == Winner.LAB || w == Winner.JACE;
    }
}
