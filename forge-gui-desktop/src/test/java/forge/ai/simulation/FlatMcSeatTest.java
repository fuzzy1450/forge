package forge.ai.simulation;

import java.util.List;

import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.expectThrows;

/**
 * The seat words of a Flat Monte Carlo seat. The default word is a full seat, plain Flat MC with the shipped caps
 * (MTG_DeckMaker's tools/combo-ai/flat-mc/SHIP.md, design 2). The flat_mc test's words keep their search-only meaning
 * (its SPEC.md, section 4.1): plain flat_mc is flat_mc:4000:500, flat_mc_zero takes no caps, and a malformed word that
 * begins flat_mc is refused by name, never played as a default seat. No game and no cards: only the words.
 */
public class FlatMcSeatTest {

    @Test
    public void theWordsParseToTheirCaps() {
        assertEquals(FlatMcSeat.parse("flat_mc"), new FlatMcSeat(4000, 500, false, false));
        assertEquals(FlatMcSeat.parse("flat_mc:40:8"), new FlatMcSeat(40, 8, false, false));
        assertEquals(FlatMcSeat.parse("flat_mc_zero"), new FlatMcSeat(4000, 500, true, false));
        assertNull(FlatMcSeat.parse("hybrid_sim"));
        assertNull(FlatMcSeat.parse("FLAT_MC"));
    }

    /** The default word, every farm and local seat's, names the full seat with the shipped caps. */
    @Test
    public void defaultNamesAFullSeat() {
        FlatMcSeat s = FlatMcSeat.parse("default");
        assertTrue(s.full());
        assertFalse(s.zeroRollout());
        assertEquals(s.rolloutsPerGame(), 4000);
        assertEquals(s.resolutionsPerRollout(), 500);
    }

    /** The flat_mc test's words stay search-only. */
    @Test
    public void theTestWordsStaySearchOnly() {
        assertFalse(FlatMcSeat.parse("flat_mc").full());
        assertFalse(FlatMcSeat.parse("flat_mc_zero").full());
        assertFalse(FlatMcSeat.parse("flat_mc:40:8").full());
    }

    /** hybrid_sim and full_sim do their own lookahead: they name no Flat MC seat. */
    @Test
    public void otherWordsAreNoSeat() {
        assertNull(FlatMcSeat.parse("hybrid_sim"));
        assertNull(FlatMcSeat.parse("full_sim"));
    }

    @Test
    public void aMalformedFlatWordIsRefusedByName() {
        for (String w : List.of("flat_mc:", "flat_mc:40", "flat_mc:40:8:1", "flat_mc:0:8", "flat_mc:40:0", "flat_mc:-1:8",
                "flat_mc: 40:8", "flat_mc:4o:8", "flat_mc:99999999999:8", "flat_mcx", "flat_mc_zero:40:8", "flat_mc ")) {
            IllegalArgumentException e = expectThrows(IllegalArgumentException.class, () -> FlatMcSeat.parse(w));
            assertTrue(e.getMessage().startsWith("unknown ai mode " + w + " ("), e.getMessage());
            assertEquals(e.getMessage(), "unknown ai mode " + w + " " + FlatMcSeat.AI_MODES);
        }
    }

    /** A count is decimal digits alone (Integer.parseInt would also take a sign), and nothing follows the second one,
     *  not even an empty field after a colon. */
    @Test
    public void aSignOrATrailingColonIsRefused() {
        for (String w : List.of("flat_mc:+40:8", "flat_mc:40:+8", "flat_mc:40:8:")) {
            IllegalArgumentException e = expectThrows(IllegalArgumentException.class, () -> FlatMcSeat.parse(w));
            assertEquals(e.getMessage(), "unknown ai mode " + w + " " + FlatMcSeat.AI_MODES);
        }
    }
}
