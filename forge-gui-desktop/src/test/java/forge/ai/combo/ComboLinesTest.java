package forge.ai.combo;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertSame;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.expectThrows;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import org.testng.annotations.Test;

import forge.ai.combo.ComboLines.Emptier;
import forge.ai.combo.ComboLines.Line;
import forge.ai.combo.ComboLines.Winner;

/** Piece 1's line registry: which table rows are the empty-library family it can execute (spec
 *  2026-10-22-combo-aware-ai-core-design.md sections 6.2 and 6.4). */
public class ComboLinesTest {
    private static final String WIN = "F|2|S|Win the game";
    private static final String EXILE = "F|88|H|Exile your library";
    private static final String EXILE_BOTH = "F|1691|H|Exile your library and graveyard";

    // the eight piece-1 rows, as the shipped table writes them (Spellbook's shapes, plus the extras file's x-1)
    private static final List<String> PIECE_ONE = List.of(
            "V|742-1295|3|{U}{U}{B}|2,88|H:Demonic Consultation|H:Thassa's Oracle",
            "V|1295-3093|4|{1}{U}{U}{B}|2|H:Tainted Pact|H:Thassa's Oracle",
            "V|742-2836|1|{B}|2,88|H:Demonic Consultation|B:Laboratory Maniac",
            "V|742-3096|1|{B}|2,88|H:Demonic Consultation|B:Jace, Wielder of Mysteries",
            "V|742-8266|1|{B}|2,88|B:Fblthp, Impossibly Lost|H:Demonic Consultation",
            "V|2836-3093|2|{1}{B}|2|H:Tainted Pact|B:Laboratory Maniac",
            "V|3093-3096|2|{1}{B}|2|H:Tainted Pact|B:Jace, Wielder of Mysteries",
            "V|x-1|1|{B}|2,88|H:Demonic Consultation|B:Doctor Doom, Unrivaled");

    private static ComboTable table(final String... rows) {
        final List<String> lines = new ArrayList<>(List.of(WIN, EXILE, EXILE_BOTH));
        lines.addAll(Arrays.asList(rows));
        return ComboTable.parse(lines);
    }

    private static List<String> ids(final List<Line> lines) {
        return lines.stream().map(l -> l.variant().id()).collect(Collectors.toList());
    }

    private static Line only(final String row) {
        return ComboLines.claim(table(row).variants().get(0));
    }

    @Test
    public void claimsTheEmptyLibraryPairs() {
        final List<String> rows = new ArrayList<>(PIECE_ONE);
        // Approach of the Second Sun + Consultation: Consultation is an emptier, Approach no library-out winner
        rows.add("V|553-742|15|{12}{W}{W}{B}|2|H:Approach of the Second Sun|H:Demonic Consultation");
        // three pieces, Oracle among them (Spellbook's shape)
        rows.add("V|1209-1295-2503|6|{2}{U}{U}{B}{B}|2,1691|H:Inverter of Truth|H:Thassa's Oracle"
                + "|HB:Kozilek, Butcher of Truth");
        // three pieces holding an emptier and a winner: a pair plus one is not the pair
        rows.add("V|742-1295-9|3|{U}{U}{B}|2,88|H:Demonic Consultation|H:Thassa's Oracle|H:Brainstorm");
        // Tainted Pact with a winner it cannot feed: Pact empties only for Oracle, Laboratory Maniac and Jace
        rows.add("V|9-9|2||2|H:Tainted Pact|B:Doctor Doom, Unrivaled");
        final List<Line> claimed = ComboLines.claimed(table(rows.toArray(new String[0])));

        assertEquals(ids(claimed), List.of("742-1295", "1295-3093", "742-2836", "742-3096", "742-8266", "2836-3093",
                "3093-3096", "x-1"));
        final List<List<Object>> pairs = claimed.stream().map(l -> List.<Object>of(l.emptier(), l.winner()))
                .collect(Collectors.toList());
        assertEquals(pairs, List.of(
                List.of(Emptier.CONSULTATION, Winner.ORACLE),
                List.of(Emptier.PACT, Winner.ORACLE),
                List.of(Emptier.CONSULTATION, Winner.LAB),
                List.of(Emptier.CONSULTATION, Winner.JACE),
                List.of(Emptier.CONSULTATION, Winner.FBLTHP),
                List.of(Emptier.PACT, Winner.LAB),
                List.of(Emptier.PACT, Winner.JACE),
                List.of(Emptier.CONSULTATION, Winner.DOOM)));
    }

    @Test
    public void shippedLinesHoldTheDoomExtra() {
        assertTrue(ComboLines.shipped().stream().anyMatch(l -> l.variant().id().equals("x-1")),
                "the shipped lines lack x-1: " + ids(ComboLines.shipped()));
    }

    // ---- beyond the plan's two ----

    @Test
    public void shippedLinesAreThePieceOneLinesReadOnce() {
        // the shipped table claims piece 1's eight lines and piece 2's 19 loop lines, each family in its file order,
        // which sorts the ids as strings (R12), and the list is held, not rebuilt
        final List<Line> shipped = ComboLines.shipped();
        assertEquals(ids(shipped.stream().filter(l -> !l.isLoop()).collect(Collectors.toList())), List.of("1295-3093",
                "2836-3093", "3093-3096", "742-1295", "742-2836", "742-3096", "742-8266", "x-1"));
        assertEquals(ids(shipped.stream().filter(Line::isLoop).collect(Collectors.toList())), List.of("129-2585-2719",
                "129-4131", "1409-3542", "2585-3430", "3430-3542", "3430-4131", "x-10", "x-11", "x-12", "x-13", "x-14",
                "x-2", "x-3", "x-4", "x-5", "x-6", "x-7", "x-8", "x-9"));
        // the two families interleaved, as the file holds them
        assertEquals(ids(shipped), List.of("129-2585-2719", "129-4131", "1295-3093", "1409-3542", "2585-3430",
                "2836-3093", "3093-3096", "3430-3542", "3430-4131", "742-1295", "742-2836", "742-3096", "742-8266",
                "x-1", "x-10", "x-11", "x-12", "x-13", "x-14", "x-2", "x-3", "x-4", "x-5", "x-6", "x-7", "x-8", "x-9"));
        assertSame(ComboLines.shipped(), shipped);
        assertEquals(shipped, ComboLines.claimed(ComboTable.shipped()));
        expectThrows(UnsupportedOperationException.class, () -> shipped.add(shipped.get(0)));
    }

    @Test
    public void aLineKeepsItsVariantAndNamesTheEmptierThenTheWinner() {
        final ComboTable t = table("V|742-8266|1|{B}|2,88|B:Fblthp, Impossibly Lost|H:Demonic Consultation");
        final Line fblthp = ComboLines.claim(t.variants().get(0));
        assertSame(fblthp.variant(), t.variants().get(0));
        assertEquals(fblthp.names(), List.of("Demonic Consultation", "Fblthp, Impossibly Lost"));
        assertEquals(only("V|1295-3093|4|{1}{U}{U}{B}|2|H:Tainted Pact|H:Thassa's Oracle").names(),
                List.of("Tainted Pact", "Thassa's Oracle"));
        for (final Emptier e : Emptier.values()) {
            assertTrue(e.card.equals("Demonic Consultation") || e.card.equals("Tainted Pact"), e.card);
        }
        assertEquals(Arrays.stream(Winner.values()).map(w -> w.card).collect(Collectors.toList()), List.of(
                "Thassa's Oracle", "Laboratory Maniac", "Jace, Wielder of Mysteries", "Doctor Doom, Unrivaled",
                "Fblthp, Impossibly Lost"));
    }

    @Test
    public void claimsNothingButOneEmptierAndOneWinner() {
        // exactly two pieces, one of each kind, and Pact only beside the three winners it feeds
        for (final String row : List.of(
                "V|1|1|{B}|2|H:Demonic Consultation|H:Tainted Pact",
                "V|2|1|{B}|2|B:Laboratory Maniac|B:Jace, Wielder of Mysteries",
                "V|3|1|{B}|2|H:Demonic Consultation|H:Demonic Consultation",
                "V|4|1|{B}|2|H:Demonic Consultation",
                "V|5|1|{B}|2|H:Thassa's Oracle",
                "V|6|2||2|H:Tainted Pact|B:Fblthp, Impossibly Lost",
                "V|7|2||2|B:Doctor Doom, Unrivaled|H:Tainted Pact",
                "V|8|1|{B}|2|H:Demonic Tutor|H:Thassa's Oracle",
                "V|9|1|{B}|2|H:demonic consultation|H:Thassa's Oracle")) {
            assertNull(only(row), row);
        }
        // either order of the two pieces claims the same pair
        final Line reversed = only("V|10|3|{U}{U}{B}|2|H:Thassa's Oracle|H:Demonic Consultation");
        assertEquals(List.of(reversed.emptier(), reversed.winner()), List.of(Emptier.CONSULTATION, Winner.ORACLE));
    }

    @Test
    public void claimedKeepsTableOrderAndCannotBeChanged() {
        final List<Line> claimed = ComboLines.claimed(table(
                "V|x-1|1|{B}|2,88|H:Demonic Consultation|B:Doctor Doom, Unrivaled",
                "V|9-9|2||2|H:Tainted Pact|B:Doctor Doom, Unrivaled",
                "V|742-1295|3|{U}{U}{B}|2,88|H:Demonic Consultation|H:Thassa's Oracle"));
        assertEquals(ids(claimed), List.of("x-1", "742-1295"));
        expectThrows(UnsupportedOperationException.class, () -> claimed.remove(0));
        assertEquals(ComboLines.claimed(table()), List.of());
    }
}
