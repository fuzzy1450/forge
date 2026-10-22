package forge.ai.combo;

import java.util.ArrayList;
import java.util.List;

/**
 * Piece 1's lines: the rows of the combo table the AI can execute, the empty-library family (spec
 * 2026-10-22-combo-aware-ai-core-design.md section 6.4). A line is exactly two pieces, an emptier and a library-out
 * winner; every other row of the table is ignored until its executor exists (section 6.2), so a deck holding none of
 * these plays as it did before.
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

    /** One line: the table's row, and which emptier and which winner its two pieces are. */
    public record Line(ComboTable.Variant variant, Emptier emptier, Winner winner) {
        /** The line's two card names: the emptier's, then the winner's (whatever order the row writes them in). */
        public List<String> names() {
            return List.of(emptier.card, winner.card);
        }
    }

    private ComboLines() {
    }

    /** The line {@code v} is, or null: a line is exactly two pieces, one emptier and one winner, in either order, and
     *  Tainted Pact empties only for Thassa's Oracle, Laboratory Maniac and Jace (section 6.4). */
    public static Line claim(final ComboTable.Variant v) {
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
