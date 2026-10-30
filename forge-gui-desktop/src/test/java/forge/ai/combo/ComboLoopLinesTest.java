package forge.ai.combo;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertSame;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.expectThrows;

import java.util.List;
import java.util.stream.Collectors;

import org.testng.annotations.Test;

import forge.ai.combo.ComboLines.Emptier;
import forge.ai.combo.ComboLines.Line;
import forge.ai.combo.ComboLines.Winner;

/** Piece 2's loop lines: the shipped table's 19 loop rows claimed beside piece 1's eight lines, each carrying its kind
 *  of cycle and its pieces (spec 2026-10-29-combo-aware-ai-loops-design.md section 6.7). No game is played. */
public class ComboLoopLinesTest {
    private static final String KINNAN = "Kinnan, Bonder Prodigy";
    private static final String BASALT = "Basalt Monolith";

    private static List<String> ids(final List<Line> lines) {
        return lines.stream().map(l -> l.variant().id()).collect(Collectors.toList());
    }

    // the shipped line of that id
    private static Line shipped(final String id) {
        for (final Line l : ComboLines.shipped()) {
            if (l.variant().id().equals(id)) {
                return l;
            }
        }
        throw new AssertionError("no shipped line " + id);
    }

    // the line one row makes, or null when the claim takes none
    private static Line only(final String row) {
        final List<Line> lines = ComboLoopBoards.loopLines(row);
        assertTrue(lines.size() <= 1, row);
        return lines.isEmpty() ? null : lines.get(0);
    }

    private static List<String> pieceNames(final ComboTable.Variant v) {
        return v.pieces().stream().map(ComboTable.Piece::name).collect(Collectors.toList());
    }

    @Test
    public void shippedClaimsPieceOneAndTheLoops() {
        final List<Line> all = ComboLines.shipped();
        assertEquals(all.size(), 27);                                    // 8 + 19
        assertEquals(all.stream().filter(l -> !l.isLoop()).count(), 8L);  // piece 1's eight, unchanged
    }

    @Test
    public void aLoopLineCarriesItsKindAndPieces() {
        final Line l = shipped("129-4131");
        assertTrue(l.isLoop());
        assertEquals(l.loop(), LoopKind.ROCK_UNTAP);
        assertNull(l.emptier());
        assertNull(l.winner());
        assertEquals(l.names(), List.of(KINNAN, BASALT));
    }

    // ---- beyond the plan's two ----

    @Test
    public void everyLoopRowIsALineOfItsKindNamingItsPiecesInTheRowsOrder() {
        final ComboTable table = ComboTable.shipped();
        final List<Line> loops = ComboLines.shipped().stream().filter(Line::isLoop).collect(Collectors.toList());
        assertEquals(loops.size(), LoopRows.ids().size());
        for (final String id : LoopRows.ids()) {
            final Line l = shipped(id);
            assertTrue(loops.contains(l), id);
            // the very row of the shipped table, the kind LoopRows maps it to, its pieces' names in the row's order
            assertSame(l.variant(), table.variant(id), id);
            assertEquals(l.loop(), LoopRows.kindOf(id), id);
            assertNull(l.emptier(), id);
            assertNull(l.winner(), id);
            assertEquals(l.names(), pieceNames(l.variant()), id);
            expectThrows(UnsupportedOperationException.class, () -> l.names().add("Walking Ballista"));
        }
        // piece 1's lines carry no kind
        for (final Line l : ComboLines.shipped()) {
            if (LoopRows.kindOf(l.variant().id()) == null) {
                assertFalse(l.isLoop(), l.variant().id());
                assertNull(l.loop(), l.variant().id());
                assertNotNull(l.emptier(), l.variant().id());
                assertNotNull(l.winner(), l.variant().id());
            }
        }
    }

    @Test
    public void claimTakesPieceOnesRuleFirstThenTheLoopRowsById() {
        // a loop row's id makes a loop line of the pieces the row names, two or three of them
        final Line rock = only("V|129-4131|0||11|B:" + KINNAN + "|B:" + BASALT);
        assertNotNull(rock);
        assertEquals(rock.loop(), LoopKind.ROCK_UNTAP);
        assertEquals(rock.names(), List.of(KINNAN, BASALT));
        final Line two = only("V|129-2585-2719|0||11|B:" + KINNAN + "|B:Grim Monolith|B:Sakashima of a Thousand Faces");
        assertNotNull(two);
        assertEquals(two.names(), List.of(KINNAN, "Grim Monolith", "Sakashima of a Thousand Faces"));
        assertEquals(only("V|x-14|0||5,17|B:Hullbreaker Horror|H:Mox Amber|H:Mox Opal|B:" + KINNAN).loop(),
                LoopKind.BOUNCE);
        // the same pieces under an id no loop row has make no line, nor does Hullbreaker's unclaimed 513-3682
        assertNull(only("V|129-4132|0||11|B:" + KINNAN + "|B:" + BASALT));
        assertNull(only("V|513-3682|0||3,4,5,17|B:Hullbreaker Horror|B:Mox Amber"));
        // piece 1's rule comes first: an emptier and a winner make piece 1's line, even under a loop row's id
        final Line first = only("V|x-2|3|{U}{U}{B}|2,88|H:Demonic Consultation|H:Thassa's Oracle");
        assertNotNull(first);
        assertFalse(first.isLoop());
        assertNull(first.loop());
        assertEquals(List.of(first.emptier(), first.winner()), List.of(Emptier.CONSULTATION, Winner.ORACLE));
        // and piece 1's own extra stays piece 1's line
        final Line doom = only("V|x-1|1|{B}|2,88|H:Demonic Consultation|B:Doctor Doom, Unrivaled");
        assertNotNull(doom);
        assertFalse(doom.isLoop());
        assertEquals(doom.winner(), Winner.DOOM);
    }

    @Test
    public void eachConstructorMakesItsFamily() {
        final ComboTable.Variant oracleRow = ComboLoopBoards.loopLines(
                "V|742-1295|3|{U}{U}{B}|2,88|H:Demonic Consultation|H:Thassa's Oracle").get(0).variant();
        final Line oracle = new Line(oracleRow, Emptier.CONSULTATION, Winner.ORACLE);
        assertFalse(oracle.isLoop());
        assertNull(oracle.loop());
        assertEquals(oracle.names(), List.of("Demonic Consultation", "Thassa's Oracle"));

        final ComboTable.Variant rockRow = shipped("129-4131").variant();
        final Line rock = new Line(rockRow, LoopKind.ROCK_UNTAP);
        assertTrue(rock.isLoop());
        assertNull(rock.emptier());
        assertNull(rock.winner());
        assertSame(rock.variant(), rockRow);
        assertEquals(rock.names(), List.of(KINNAN, BASALT));
        assertEquals(rock, shipped("129-4131"));
        // the kind is the one given, not looked up again; a loop line always has one
        assertEquals(new Line(rockRow, LoopKind.BOUNCE).loop(), LoopKind.BOUNCE);
        expectThrows(NullPointerException.class, () -> new Line(rockRow, (LoopKind) null));
    }
}
