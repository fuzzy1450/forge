package forge.ai;

import static org.testng.AssertJUnit.assertEquals;
import static org.testng.AssertJUnit.assertFalse;
import static org.testng.AssertJUnit.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;

import org.testng.annotations.Test;

import forge.game.Game;
import forge.game.card.Card;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;

/**
 * Two adaptations of the merge's R6 to the APIs upstream deleted or renamed: SpecialCardAi's library-search permission
 * (Player.canSearchLibraryWith became getSearchableLibrary(sa, target) != null) and the payment-filter count of
 * Prismatic Ending's colour scan (getGenericCostToActivate became getManaToActivate).
 */
public class MergeSpecialCardAiTest extends AITest {
    private static final String LONG_KEYWORD =
            "Spells and abilities you control can't cause you to search your library.";

    private static boolean canSearch(final Player searcher, final SpellAbility sa, final Player target)
            throws Exception {
        final Method m = SpecialCardAi.class.getDeclaredMethod("canSearchLibrary", Player.class, SpellAbility.class,
                Player.class);
        m.setAccessible(true);
        return (Boolean) m.invoke(null, searcher, sa, target);
    }

    @Test(timeOut = 120_000)
    public void aSearchIsAllowedUnlessTheSearcherMayNotCauseIt() throws Exception {
        final Game game = initAndCreateGame();
        final Player a = game.getPlayers().get(1);
        final Player b = game.getPlayers().get(0);
        fillLibrary(a, 10);
        fillLibrary(b, 10);
        final SpellAbility fromA = addCardToZone("Demonic Tutor", a, ZoneType.Hand).getFirstSpellAbility();
        fromA.setActivatingPlayer(a);
        assertTrue("no static yet", canSearch(a, fromA, a));
        assertTrue(canSearch(b, fromA, a));

        // what Ashiok, Dream Render gives its controller's opponents: their spells and abilities cannot
        // make them search
        b.addChangedKeywords(List.of(LONG_KEYWORD), List.of(), 1L, 0L);
        assertTrue("the harness needs the keyword on the opponent", b.hasKeyword(LONG_KEYWORD));
        assertFalse(a.hasKeyword(LONG_KEYWORD));

        assertTrue("its controller still may", canSearch(a, fromA, a));
        assertFalse("the opponent may not search for a spell of the controller's: the library is not given",
                canSearch(b, fromA, a));
        final SpellAbility fromB = addCardToZone("Demonic Tutor", b, ZoneType.Hand).getFirstSpellAbility();
        fromB.setActivatingPlayer(b);
        assertFalse("nor for its own", canSearch(b, fromB, b));
        assertTrue("but may search the controller's library for its own ability", canSearch(b, fromB, a));
    }

    private static int filtersOf(final Player ai, final SpellAbility sa) throws Exception {
        final Class<?> colours = Class.forName("forge.ai.SpecialCardAi$PrismaticEnding$Colours");
        final Method scan = colours.getDeclaredMethod("scan", Player.class, SpellAbility.class);
        scan.setAccessible(true);
        final Object col = scan.invoke(null, ai, sa);
        assertTrue("the scan reads the board", col != null);
        final Field f = colours.getDeclaredField("filters");
        f.setAccessible(true);
        return f.getInt(col);
    }

    @Test(timeOut = 120_000)
    public void aSignetIsAPaymentFilterOfPrismaticEndingsColourScan() throws Exception {
        final Game game = initAndCreateGame();
        final Player ai = game.getPlayers().get(1);
        addCard("Plains", ai);
        final SpellAbility ending = addCardToZone("Prismatic Ending", ai, ZoneType.Hand).getFirstSpellAbility();
        ending.setActivatingPlayer(ai);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN2, ai);
        game.getAction().checkStateEffects(true);
        assertEquals("a Plains is no filter", 0, filtersOf(ai, ending));

        final Card signet = addCard("Azorius Signet", ai);
        assertTrue(signet.isInPlay());

        assertEquals("the Signet's {1} is a mana in its activation cost: one payment filter", 1, filtersOf(ai, ending));
    }
}
