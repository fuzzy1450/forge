package forge.ai.combo;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNotSame;
import static org.testng.Assert.assertSame;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.expectThrows;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.testng.annotations.Test;

import forge.ai.combo.ComboTable.Feature;
import forge.ai.combo.ComboTable.Piece;
import forge.ai.combo.ComboTable.Variant;

/** The combo table's parser, and the table the fork ships as a classpath resource of forge-ai (spec
 *  2026-10-22-combo-aware-ai-core-design.md section 6.1; the main repo's scripts/build_combo_table.py writes it). */
public class ComboTableTest {
    private static final String WIN = "F|2|S|Win the game";
    private static final String EXILE = "F|88|H|Exile your library";

    @Test
    public void parsesFeatureAndVariantLines() {
        final ComboTable table = ComboTable.parse(List.of(
                "# c",
                "",
                WIN,
                EXILE,
                "V|742-1295|3|{U}{U}{B}|2,88|H:Demonic Consultation|H:Thassa's Oracle\r"));
        assertEquals(table.size(), 1);
        final Variant v = table.variants().get(0);
        assertEquals(v.manaValue(), 3);
        assertEquals(v.mana(), "{U}{U}{B}");
        assertEquals(v.features(), List.of(2, 88));
        assertEquals(v.pieces().get(1), new Piece("Thassa's Oracle", "H", false));
        assertEquals(table.features().get(2).name(), "Win the game");
    }

    @Test
    public void commanderFlagAndZones() {
        final Piece kinnan = ComboTable.parse(List.of(WIN,
                "V|1-2|4||2|BC*:Kinnan, Bonder Prodigy|H:Basalt Monolith")).variants().get(0).pieces().get(0);
        assertEquals(kinnan.zones(), "BC");
        assertTrue(kinnan.commander());
        assertTrue(kinnan.needs('C'));
        assertFalse(kinnan.needs('H'));
    }

    @Test
    public void rejectsMalformedLines() {
        // Each line, and a fragment of the reason it is refused for: the message names the line and why.
        final List<List<String>> cases = List.of(
                List.of("V|742-1295|three|{U}{U}{B}|2|H:Demonic Consultation|H:Thassa's Oracle", "mana value 'three'"),
                List.of("Q|742-1295", "'Q'"),
                List.of("V|742-1295|3|{U}{U}{B}|2|H:Demonic Consultation|H:Thassa's Oracle", "names feature 2"));
        for (final List<String> c : cases) {
            final IllegalArgumentException e = expectThrows(IllegalArgumentException.class,
                    () -> ComboTable.parse(List.of(c.get(0))));
            assertTrue(e.getMessage().startsWith("combos.txt line 1:"), e.getMessage());
            assertTrue(e.getMessage().contains(c.get(1)), e.getMessage());
        }
    }

    @Test
    public void internsNames() {
        final ComboTable table = ComboTable.parse(List.of(WIN, EXILE,
                "V|742-1295|3|{U}{U}{B}|2,88|H:Demonic Consultation|H:Thassa's Oracle",
                "V|1295-3093|3|{U}{U}{B}|2,88|H:Tainted Pact|H:Thassa's Oracle"));
        assertSame(table.variants().get(0).pieces().get(1), table.variants().get(1).pieces().get(1));
    }

    @Test
    public void shippedTableCarriesThePieceOneLines() {
        final ComboTable shipped = ComboTable.shipped();
        assertTrue(shipped.size() > 1000, "the shipped table holds " + shipped.size() + " rows");
        final Set<String> ids = shipped.variants().stream().map(Variant::id).collect(Collectors.toSet());
        for (final String id : List.of("742-1295", "1295-3093", "x-1")) {
            assertTrue(ids.contains(id), "the shipped table lacks variant " + id);
        }
    }

    // ------------------------------------------------------------------ beyond the plan's five

    @Test
    public void rejectsEveryOtherMalformedLine() {
        final String ok = "V|1-2|3|{U}{U}{B}|2|H:Demonic Consultation|H:Thassa's Oracle";
        final List<List<String>> cases = List.of(
                List.of("F|2|S", "4 fields"),
                List.of("F|2|S|Win the game|x", "4 fields"),
                List.of("F|two|S|Win the game", "feature id 'two'"),
                List.of("F|2||Win the game", "no status"),
                List.of("F|2|S|", "no name"),
                List.of("V|1-2|3|{U}{U}{B}|2", "at least 6 fields"),
                List.of("V||3|{U}{U}{B}|2|H:Demonic Consultation", "no id"),
                List.of("V|1-2|-3|{U}{U}{B}|2|H:Demonic Consultation", "mana value '-3'"),
                List.of("V|1-2|99999999999|{U}{U}{B}|2|H:Demonic Consultation", "mana value '99999999999'"),
                List.of("V|1-2|3|three blue|2|H:Demonic Consultation", "mana 'three blue'"),
                List.of("V|1-2|3|{U}{U}x|2|H:Demonic Consultation", "mana '{U}{U}x'"),
                List.of("V|1-2|3|{U}{U}{B}||H:Demonic Consultation", "no feature ids"),
                List.of("V|1-2|3|{U}{U}{B}|2,|H:Demonic Consultation", "feature id ''"),
                List.of("V|1-2|3|{U}{U}{B}|2,x|H:Demonic Consultation", "feature id 'x'"),
                List.of("V|1-2|3|{U}{U}{B}|2,2|H:Demonic Consultation", "names feature 2 twice"),
                List.of("V|1-2|3|{U}{U}{B}|2|Demonic Consultation", "not zones[*]:name"),
                List.of("V|1-2|3|{U}{U}{B}|2|HQ:Demonic Consultation", "zone 'Q'"),
                List.of("V|1-2|3|{U}{U}{B}|2|*H:Demonic Consultation", "zone '*'"),
                List.of("V|1-2|3|{U}{U}{B}|2|HH:Demonic Consultation", "zone 'H' twice"),
                List.of("V|1-2|3|{U}{U}{B}|2|H:", "no name"),
                List.of("V|1-2|3|{U}{U}{B}|2|H:Demonic Consultation|", "not zones[*]:name"),
                List.of(ok, "variant 1-2 appears twice"));
        for (final List<String> c : cases) {
            final List<String> lines = new ArrayList<>(List.of("# header", "", WIN));
            if (c.get(1).contains("appears twice")) {
                lines.add(ok);
            }
            lines.add(c.get(0));
            final IllegalArgumentException e = expectThrows(IllegalArgumentException.class,
                    () -> ComboTable.parse(lines));
            assertTrue(e.getMessage().startsWith("combos.txt line " + lines.size() + ": "), e.getMessage());
            assertTrue(e.getMessage().contains(c.get(1)), e.getMessage());
        }
        final IllegalArgumentException twice = expectThrows(IllegalArgumentException.class,
                () -> ComboTable.parse(List.of(WIN, "F|2|S|Win the game")));
        assertTrue(twice.getMessage().startsWith("combos.txt line 2: feature 2 appears twice"), twice.getMessage());
        // A feature id needs its F line ABOVE the V line, as the builder writes every F line first.
        final IllegalArgumentException below = expectThrows(IllegalArgumentException.class,
                () -> ComboTable.parse(List.of(ok, WIN)));
        assertTrue(below.getMessage().startsWith("combos.txt line 1: "), below.getMessage());
    }

    @Test
    public void keepsFileOrderAndIdOrderAndCannotBeChanged() {
        final ComboTable table = ComboTable.parse(List.of(EXILE, WIN, "F|1|S|Infinite mana",
                "V|9-9|2|{U}{B}|88,2|H:Demonic Consultation|H:Thassa's Oracle",
                "V|10-1|0||1|B:Basalt Monolith|B:Rings of Brighthearth",
                "V|x-1|1|{B}|2,88|H:Demonic Consultation|B:Doctor Doom, Unrivaled"));
        assertEquals(table.variants().stream().map(Variant::id).collect(Collectors.toList()),
                List.of("9-9", "10-1", "x-1"));
        assertEquals(new ArrayList<>(table.features().keySet()), List.of(1, 2, 88));
        assertEquals(table.features().get(88), new Feature(88, "H", "Exile your library"));
        assertEquals(table.variants().get(0).features(), List.of(88, 2));   // as written
        assertEquals(table.variants().get(1).mana(), "");
        assertEquals(table.variants().get(1).manaValue(), 0);
        expectThrows(UnsupportedOperationException.class, () -> table.variants().remove(0));
        expectThrows(UnsupportedOperationException.class, () -> table.features().remove(2));
        expectThrows(UnsupportedOperationException.class, () -> table.variants().get(0).pieces().remove(0));
        expectThrows(UnsupportedOperationException.class, () -> table.variants().get(0).features().remove(0));
        // A Variant built by hand cannot be changed through the lists it was given either.
        final List<Piece> pieces = new ArrayList<>(List.of(new Piece("Thassa's Oracle", "H", false)));
        final Variant made = new Variant("1-1", 2, "{U}{U}", new ArrayList<>(List.of(2)), pieces);
        pieces.clear();
        assertEquals(made.pieces().size(), 1);
    }

    @Test
    public void aPieceMayRepeatAndANameMayHoldAColon() {
        // The builder writes a card Spellbook uses twice (its quantity) as two pieces, and splits at the first colon.
        final ComboTable table = ComboTable.parse(List.of(WIN,
                "V|5-5|2|{1}{B}|2|B:Relentless Rats|B:Relentless Rats|BG:Circle of Protection: Red"));
        final List<Piece> pieces = table.variants().get(0).pieces();
        assertEquals(pieces.size(), 3);
        assertSame(pieces.get(0), pieces.get(1));
        assertEquals(pieces.get(2), new Piece("Circle of Protection: Red", "BG", false));
        assertTrue(pieces.get(2).needs('G'));
        assertFalse(pieces.get(2).needs('L'));
        // Interning spans the whole parse: one object per distinct piece, distinct pieces stay distinct.
        assertNotSame(pieces.get(0), pieces.get(2));
    }

    @Test
    public void theShippedTableIsReadOnceAndAnAbsentResourceRefuses() {
        assertSame(ComboTable.shipped(), ComboTable.shipped());
        assertEquals(ComboTable.RESOURCE, "/forge/ai/combo/combos.txt");
        final IllegalStateException e = expectThrows(IllegalStateException.class,
                () -> ComboTable.load("/forge/ai/combo/no-such-table.txt"));
        assertTrue(e.getMessage().contains("/forge/ai/combo/no-such-table.txt"), e.getMessage());
        assertTrue(ComboTable.parse(List.of("# only a comment", "")).size() == 0);
    }

    @Test
    public void needsEveryStartZoneTheFirstOneIncluded() {
        // A piece's first zone letter is a start zone like the rest: most pieces have only one (H:Thassa's Oracle),
        // and the tracker asks needs() of exactly that letter.
        for (final char z : ComboTable.ZONES.toCharArray()) {
            final Piece one = new Piece("Thassa's Oracle", String.valueOf(z), false);
            for (final char other : ComboTable.ZONES.toCharArray()) {
                assertEquals(one.needs(other), other == z, "a piece in zone " + z + " asked of zone " + other);
            }
        }
        final ComboTable table = ComboTable.parse(List.of(WIN, EXILE,
                "V|742-1295|3|{U}{U}{B}|2,88|H:Demonic Consultation|H:Thassa's Oracle",
                "V|1-2|4||2|BC*:Kinnan, Bonder Prodigy|H:Basalt Monolith"));
        assertTrue(table.variants().get(0).pieces().get(1).needs('H'));
        final Piece kinnan = table.variants().get(1).pieces().get(0);
        assertTrue(kinnan.needs('B'));
        assertTrue(kinnan.needs('C'));
        assertFalse(kinnan.needs('H'));
    }

    @Test
    public void theShippedTableReadsItsNamesAsUtf8() {
        // The builder writes UTF-8, in Forge's spelling; read in another charset these names would be mojibake that
        // never meets a card. Twenty distinct names in the 2026-10-22 build are not ASCII.
        final Set<String> names = ComboTable.shipped().variants().stream()
                .flatMap(v -> v.pieces().stream()).map(Piece::name).collect(Collectors.toSet());
        for (final String name : List.of("Bartolom\u00e9 del Presidio", "Alta\u00efr Ibn-La'Ahad",
                "\u00c9omer, Marshal of Rohan")) {
            assertTrue(names.contains(name), "the shipped table lacks a piece named " + name);
        }
    }
}
