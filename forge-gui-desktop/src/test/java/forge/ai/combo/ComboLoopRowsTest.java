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

import forge.ai.combo.ComboTable.Piece;
import forge.ai.combo.ComboTable.Variant;

/** Piece 2's loop rows: which rows of the shipped combo table are Kinnan's mana loops, and the kind of cycle each one
 *  runs (spec 2026-10-29-combo-aware-ai-loops-design.md section 6.1). No game is played: the table and the map. */
public class ComboLoopRowsTest {
    private static final String KINNAN = "Kinnan, Bonder Prodigy";
    private static final String BASALT = "Basalt Monolith";
    private static final String GRIM = "Grim Monolith";
    private static final String NYXBLOOM = "Nyxbloom Ancient";
    private static final String PALINCHRON = "Palinchron";
    private static final String DEADEYE = "Deadeye Navigator";
    private static final String HULLBREAKER = "Hullbreaker Horror";

    @Test
    public void everyLoopRowIsShipped() {
        final ComboTable t = ComboTable.shipped();
        for (final String id : LoopRows.ids()) {
            assertNotNull(t.variant(id), id);
        }
        assertEquals(LoopRows.ids().size(), 19);
    }

    @Test
    public void kindsMatchTheSpec() {
        assertEquals(LoopRows.kindOf("129-4131"), LoopKind.ROCK_UNTAP);
        assertEquals(LoopRows.kindOf("129-2585-2719"), LoopKind.ROCK_UNTAP);
        assertEquals(LoopRows.kindOf("1409-3542"), LoopKind.FLICKER);
        assertEquals(LoopRows.kindOf("3430-3542"), LoopKind.RECAST);
        assertEquals(LoopRows.kindOf("x-14"), LoopKind.BOUNCE);
        assertNull(LoopRows.kindOf("513-3682"));
        assertNull(LoopRows.kindOf("742-1295"));   // a piece-1 row
    }

    @Test
    public void speedsAndStops() {
        assertTrue(LoopKind.ROCK_UNTAP.instantSpeed() && LoopKind.FLICKER.instantSpeed());
        assertFalse(LoopKind.RECAST.instantSpeed() || LoopKind.BOUNCE.instantSpeed());
        for (final LoopKind k : LoopKind.values()) {
            assertTrue(k.canStop(), k.name());
        }
    }

    @Test
    public void noLoopRowNamesASink() {
        // Walking Ballista and Thrasios spend the mana; no row names them, so no loop line holds them (spec 6.7, 9)
        final ComboTable t = ComboTable.shipped();
        for (final String id : LoopRows.ids()) {
            final Variant v = t.variant(id);
            assertNotNull(v, id);
            for (final Piece p : v.pieces()) {
                assertFalse(p.name().equals("Walking Ballista") || p.name().equals("Thrasios, Triton Hero"),
                        id + " names " + p.name());
            }
        }
    }

    // ---- beyond the plan's four ----

    @Test
    public void theIdsAreTheCallsOrderHeldOnceAndCannotBeChanged() {
        // the six Spellbook rows, rock untaps first, then the flicker and the recast; then the extras file's x-2 to
        // x-14 by number, as the main repo's PIECE2_IDS lists them (not the table's file order, which sorts as strings)
        assertEquals(LoopRows.ids(), List.of("129-4131", "3430-4131", "2585-3430", "129-2585-2719", "1409-3542",
                "3430-3542", "x-2", "x-3", "x-4", "x-5", "x-6", "x-7", "x-8", "x-9", "x-10", "x-11", "x-12", "x-13",
                "x-14"));
        assertSame(LoopRows.ids(), LoopRows.ids());
        expectThrows(UnsupportedOperationException.class, () -> LoopRows.ids().add("513-3682"));
        expectThrows(UnsupportedOperationException.class, () -> LoopRows.ids().remove(0));
        // nor rewritten in place: a fixed-size list such as Arrays.asList refuses add and remove alone, while a set, a
        // sort or a replaceAll on the one list every caller shares would reorder Calls' order for every game in the JVM
        final List<String> ids = LoopRows.ids();
        expectThrows(UnsupportedOperationException.class, () -> ids.set(0, "x-2"));
        expectThrows(UnsupportedOperationException.class, () -> ids.sort(null));
        expectThrows(UnsupportedOperationException.class, ids::clear);
        expectThrows(UnsupportedOperationException.class, () -> ids.replaceAll(s -> s));
        assertEquals(LoopRows.ids().size(), 19);
    }

    @Test
    public void everyIdHasItsKindAndNoOtherRowIsALoop() {
        final List<String> ids = LoopRows.ids();
        assertEquals(ids.stream().map(LoopRows::kindOf).collect(Collectors.toList()), List.of(
                LoopKind.ROCK_UNTAP, LoopKind.ROCK_UNTAP, LoopKind.ROCK_UNTAP, LoopKind.ROCK_UNTAP,
                LoopKind.FLICKER, LoopKind.RECAST,
                LoopKind.BOUNCE, LoopKind.BOUNCE, LoopKind.BOUNCE, LoopKind.BOUNCE, LoopKind.BOUNCE, LoopKind.BOUNCE,
                LoopKind.BOUNCE, LoopKind.BOUNCE, LoopKind.BOUNCE, LoopKind.BOUNCE, LoopKind.BOUNCE, LoopKind.BOUNCE,
                LoopKind.BOUNCE));
        // x-1 is piece 1's Doom row; x-15 and x-0 do not exist; an id is matched exactly
        for (final String other : List.of("x-1", "x-15", "x-0", "x-02", "X-2", "129-4131 ", "4131-129", "",
                "1295-3093", "742-8266")) {
            assertNull(LoopRows.kindOf(other), other);
        }
        // the shipped table's other rows are no loop rows either
        final long others = ComboTable.shipped().variants().stream()
                .filter(v -> !ids.contains(v.id()))
                .filter(v -> LoopRows.kindOf(v.id()) != null)
                .count();
        assertEquals(others, 0L);
        assertEquals(List.of(LoopKind.values()),
                List.of(LoopKind.ROCK_UNTAP, LoopKind.FLICKER, LoopKind.RECAST, LoopKind.BOUNCE));
    }

    @Test
    public void eachShippedLoopRowNamesTheEngineOfItsKind() {
        // the kinds are mapped by hand (spec 6.1); the shipped rows' pieces must be the cycles they are mapped to
        final ComboTable t = ComboTable.shipped();
        for (final String id : LoopRows.ids()) {
            final Variant v = t.variant(id);
            assertNotNull(v, id);
            final List<String> names = v.pieces().stream().map(Piece::name).collect(Collectors.toList());
            assertTrue(names.size() >= 2, id + ": " + names);
            switch (LoopRows.kindOf(id)) {
                case ROCK_UNTAP -> {
                    // a Monolith to tap and untap, and the bonus that nets mana from it
                    assertTrue(names.contains(BASALT) || names.contains(GRIM), id + ": " + names);
                    assertTrue(names.contains(KINNAN) || names.contains(NYXBLOOM), id + ": " + names);
                    assertFalse(names.contains(PALINCHRON) || names.contains(HULLBREAKER), id + ": " + names);
                }
                case FLICKER -> {
                    assertTrue(names.contains(PALINCHRON) && names.contains(DEADEYE), id + ": " + names);
                }
                case RECAST -> {
                    assertTrue(names.contains(PALINCHRON) && names.contains(NYXBLOOM), id + ": " + names);
                    assertFalse(names.contains(DEADEYE), id + ": " + names);
                }
                case BOUNCE -> {
                    // one Hullbreaker, on the battlefield, bouncing the pieces its controller casts
                    final List<Piece> hullbreakers = v.pieces().stream().filter(p -> p.name().equals(HULLBREAKER))
                            .collect(Collectors.toList());
                    assertEquals(hullbreakers.size(), 1, id + ": " + names);
                    assertEquals(hullbreakers.get(0).zones(), "B", id);
                }
            }
        }
    }

    @Test
    public void theShippedLoopRowsAreTheBuildsLetterForLetter() {
        // every field of the 19 rows as the build writes them, in Calls' order: Spellbook's six from the pinned dump,
        // then x-2 to x-14 from sim/combos/extras.txt in the templates' form (mana value 0, mana blank, Hullbreaker
        // and the named rock on the battlefield, the fills in hand). The test above pins each row's engine; this pins
        // the rest, among it what the plan's Calls decide: among the bounce rows Kinnan is a piece of x-4, x-12 and
        // x-14 alone, the loops that net mana only with his bonus; no fill is Mox Diamond; no row names a sink. A
        // parsed Variant compares the id, the mana value, the mana, the feature ids and every piece's name, zones and
        // commander flag, in order
        final ComboTable expected = ComboTable.parse(List.of(
                "F|3|H|Infinite creature LTB",
                "F|4|H|Infinite creature ETB",
                "F|5|S|Infinite colored mana",
                "F|11|S|Infinite colorless mana",
                "F|17|H|Infinite storm count",
                "F|28|S|Infinite mana lands you control can produce",
                "F|87|H|Infinite blinking",
                "F|173|C|Infinite untap of lands you control",
                "V|129-4131|0||11|B:Kinnan, Bonder Prodigy|B:Basalt Monolith",
                "V|3430-4131|0||11|B:Basalt Monolith|B:Nyxbloom Ancient",
                "V|2585-3430|0||11|B:Grim Monolith|B:Nyxbloom Ancient",
                "V|129-2585-2719|0||11|B:Kinnan, Bonder Prodigy|B:Grim Monolith|B:Sakashima of a Thousand Faces",
                "V|1409-3542|0||3,4,28,87,173|B:Palinchron|B:Deadeye Navigator",
                "V|3430-3542|7|{5}{U}{U}|3,4,17,28|B:Palinchron|B:Nyxbloom Ancient",
                "V|x-2|0||11,17|B:Hullbreaker Horror|B:Sol Ring|H:Mana Vault",
                "V|x-3|0||11,17|B:Hullbreaker Horror|B:Sol Ring|H:Mox Opal",
                "V|x-4|0||11,17|B:Hullbreaker Horror|B:Sol Ring|H:Sensei's Divining Top|B:Kinnan, Bonder Prodigy",
                "V|x-5|0||11,17|B:Hullbreaker Horror|B:Sol Ring|H:Mox Amber",
                "V|x-6|0||11,17|B:Hullbreaker Horror|B:Mana Vault|H:Sensei's Divining Top",
                "V|x-7|0||11,17|B:Hullbreaker Horror|B:Mana Vault|H:Mox Opal",
                "V|x-8|0||11,17|B:Hullbreaker Horror|B:Mana Vault|H:Grim Monolith",
                "V|x-9|0||11,17|B:Hullbreaker Horror|B:Mana Vault|H:Arcane Signet",
                "V|x-10|0||11,17|B:Hullbreaker Horror|B:Mana Vault|H:Simic Signet",
                "V|x-11|0||11,17|B:Hullbreaker Horror|B:Mana Vault|H:Talisman of Curiosity",
                "V|x-12|0||11,17|B:Hullbreaker Horror|B:Mana Vault|H:Scroll Rack|B:Kinnan, Bonder Prodigy",
                "V|x-13|0||11,17|B:Hullbreaker Horror|B:Mana Vault|H:Mox Amber",
                "V|x-14|0||5,17|B:Hullbreaker Horror|H:Mox Amber|H:Mox Opal|B:Kinnan, Bonder Prodigy"));
        assertEquals(expected.size(), 19);
        assertEquals(LoopRows.ids(), expected.variants().stream().map(Variant::id).collect(Collectors.toList()));
        final ComboTable shipped = ComboTable.shipped();
        for (final Variant row : expected.variants()) {
            assertEquals(shipped.variant(row.id()), row, row.id());
        }
        // and each is found by its whole id only: no proper prefix or suffix of a loop row's id finds a row or a kind
        for (final String part : List.of("x-", "129", "129-", "129-413", "3430-41", "4131", "3542", "-4131")) {
            assertNull(shipped.variant(part), part);
            assertNull(LoopRows.kindOf(part), part);
        }
    }

    @Test
    public void variantFindsEachRowByItsIdAndNullForAnyOther() {
        final ComboTable t = ComboTable.parse(List.of("F|2|S|Win the game", "F|11|S|Infinite colorless mana",
                "V|129-4131|0||11|B:" + KINNAN + "|B:" + BASALT,
                "V|x-1|1|{B}|2|H:Demonic Consultation|B:Doctor Doom, Unrivaled",
                "V|3430-4131|0||11|B:" + BASALT + "|B:" + NYXBLOOM));
        assertSame(t.variant("129-4131"), t.variants().get(0));
        assertSame(t.variant("x-1"), t.variants().get(1));
        assertSame(t.variant("3430-4131"), t.variants().get(2));
        for (final String absent : List.of("x-2", "X-1", "129-4131 ", "4131", "", "2585-3430")) {
            assertNull(t.variant(absent), absent);
        }
        assertNull(ComboTable.parse(List.of("# no rows")).variant("x-1"));
        // the shipped table finds every one of its rows, each the very object its list holds
        final ComboTable shipped = ComboTable.shipped();
        for (final Variant v : shipped.variants()) {
            assertSame(shipped.variant(v.id()), v, v.id());
        }
        assertNull(shipped.variant("513-3682-0"));
        assertNull(shipped.variant("x-15"));
    }
}
