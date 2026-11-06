package forge.game.card;

import static org.testng.AssertJUnit.assertEquals;
import static org.testng.AssertJUnit.assertFalse;
import static org.testng.AssertJUnit.assertTrue;

import java.lang.reflect.Field;
import java.util.Map;

import org.testng.annotations.Test;

import forge.ai.AITest;
import forge.card.CardStateName;
import forge.card.CardType;
import forge.game.Game;
import forge.game.player.Player;
import forge.game.replacement.ReplacementEffect;
import forge.game.zone.MagicStack;
import forge.game.zone.ZoneType;
import forge.util.collect.FCollectionView;

/**
 * The merge keeps the fork's trait memos beside upstream's validated trait caches (CardState.getReplacementEffects),
 * and a few small upstream guards in files the merge resolved by hand (Game.getCardsIn, MagicStack.reset).
 */
public class MergeTraitCacheTest extends AITest {

    private static boolean hasVengeance(final FCollectionView<ReplacementEffect> effects) {
        for (final ReplacementEffect re : effects) {
            if ("Vengeance".equals(re.getParam("Description"))) {
                return true;
            }
        }
        return false;
    }

    /** CardState.addType moves the card's trait version, which Card's emptiness memo is keyed on. */
    @Test(timeOut = 120_000)
    public void aTypeWrittenOnTheStateIsSeenThroughTheEmptinessMemo() {
        final Game game = initAndCreateGame();
        final Player p = game.getPlayers().get(0);
        final Card bears = createCard("Grizzly Bears", p);
        final CardState state = bears.getCurrentState();

        assertTrue(bears.getReplacementEffects().isEmpty());
        assertTrue("the emptiness is kept", bears.knownToHaveNoReplacementEffects());

        state.addType("Vengeance");

        assertTrue("the Vengeance replacement is seen", hasVengeance(bears.getReplacementEffects()));
    }

    /** replacementFlags reads the Vengeance subtype: a write to the state's own type object is seen without any
     *  version moving. */
    @Test(timeOut = 120_000)
    public void aVengeanceSubtypeWrittenStraightToTheTypeIsSeenByTheReplacementCache() {
        final Game game = initAndCreateGame();
        final Player p = game.getPlayers().get(0);
        final Card hela = createCard("Hela, Mistress of Darkness", p);
        final CardState tutor = hela.getState(CardStateName.Secondary);

        tutor.setType(CardType.parse("Sorcery", false));
        assertFalse(hasVengeance(tutor.getReplacementEffects()));

        ((CardType) tutor.getType()).add("Vengeance");

        assertTrue(hasVengeance(tutor.getReplacementEffects()));
    }

    @Test(timeOut = 120_000)
    public void noZonesIsNoCards() {
        final Game game = initAndCreateGame();
        addCardToZone("Grizzly Bears", game.getPlayers().get(0), ZoneType.Hand);

        assertTrue(game.getCardsIn((Iterable<ZoneType>) null).isEmpty());
    }

    /** The count of illegal additions is a turn's memory, and a reset of the stack (a restarted game) forgets it. */
    @Test(timeOut = 120_000)
    @SuppressWarnings("unchecked")
    public void resettingTheStackForgetsTheIllegalAdditions() throws Exception {
        final Game game = initAndCreateGame();
        final Card bears = addCardToZone("Grizzly Bears", game.getPlayers().get(0), ZoneType.Hand);
        final MagicStack stack = game.getStack();
        final Field f = MagicStack.class.getDeclaredField("illegalAdditions");
        f.setAccessible(true);
        ((Map<Integer, Integer>) f.get(stack)).put(bears.getId(), 2);
        assertEquals(2, stack.getIllegalAdditionCount(bears));

        stack.reset();

        assertEquals(0, stack.getIllegalAdditionCount(bears));
    }
}
