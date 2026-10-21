package forge.game.trigger;

import static org.testng.AssertJUnit.assertEquals;
import static org.testng.AssertJUnit.assertFalse;
import static org.testng.AssertJUnit.assertTrue;

import org.testng.annotations.Test;

import forge.ai.AITest;
import forge.card.CardStateName;
import forge.game.Game;
import forge.game.GameEndReason;
import forge.game.ability.AbilityKey;
import forge.game.ability.effects.CopyPermanentEffect;
import forge.game.card.Card;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.zone.MagicStack;
import forge.game.zone.ZoneType;

/**
 * MagicStack.MAX_STACK_SIZE, the stack size past which the game is a draw. At 999 (upstream #8254) it drew a legal,
 * finite crowd: 51 Ondu Spiritdancers under Secret Arcade, fresh on their controller's turn, all trigger on one
 * enchantment entering, and every copy that resolves triggers the ones that have not copied yet, 1,326 triggers in
 * all with 1,276 on the stack at once (MTG_DeckMaker stored games 1594009, 1682203, 1889860, 2058086 and 2590011, which
 * the self-copy-loop stop otherwise finishes). At 1500 that crowd resolves, and the limit is exact either side of it.
 *
 * The crowd's size is arithmetic. With the optional-loop stop at MAX_TRIGGER_REPEATS (50) rounds, n fresh Ondus put n,
 * n - 1, ..., n - 50 triggers on the stack, one round after each copy resolves, so the stack peaks at 51 n - 1,325
 * items: 1,276 for 51 Ondus, 1,480 for 55, 1,531 for 56. In a real game each other card that triggers on an enchantment
 * entering adds about 50 more: with Fear of Sleep Paralysis, stored game 1682203's stack held 1,326 items at once.
 */
public class StackSizeLimitTest extends AITest {

    private static final int MAX_STEPS = 200_000;

    /**
     * Secret Arcade unlocked, then {@code n} Ondu Spiritdancers (one, and n - 1 copies of it made as a token copy is,
     * so none has entered this turn or used its once each turn), then a Sol Ring entering, which Secret Arcade makes
     * an enchantment. Plays the chain out and returns the most items the stack held.
     */
    private int crowd(Game game, Player ai, int n) {
        Card arcade = addCard("Secret Arcade // Dusty Parlor", ai);
        arcade.unlockRoom(ai, CardStateName.LeftSplit);
        Card ondu = addCard("Ondu Spiritdancer", ai);
        for (int i = 1; i < n; i++) {
            Card copy = CopyPermanentEffect.getProtoType(ondu.getFirstSpellAbility(), ondu, ai);
            copy.setGameTimestamp(game.getNextTimestamp());
            ai.getZone(ZoneType.Battlefield).add(copy);
        }
        game.getPhaseHandler().devModeSet(PhaseType.MAIN2, ai);
        game.getAction().checkStateEffects(true);
        assertEquals(n, countCardsWithName(game, "Ondu Spiritdancer"));

        game.getAction().moveToPlay(addCardToZone("Sol Ring", ai, ZoneType.Hand), null, AbilityKey.newMap());
        int most = 0;
        int steps = 0;
        do {
            game.getPhaseHandler().mainLoopStep();
            most = Math.max(most, game.getStack().size());
        } while (!game.isGameOver() && (!game.getStack().isEmpty() || game.getStack().hasSimultaneousStackEntries())
                && ++steps < MAX_STEPS);
        return most;
    }

    @Test(timeOut = 300_000)
    public void fiftyOneOndusResolveTheirCrowdWithoutADraw() {
        Game game = initAndCreateGame();
        Player ai = game.getPlayers().get(1);
        int most = crowd(game, ai, 51);

        assertFalse("the crowd drew the game", game.isGameOver());
        assertEquals("the crowd's peak, which the old 999 limit drew", 1276, most);
        assertTrue("the crowd never finished", game.getStack().isEmpty());
        // the original and 50 copies: an Ondu copies it in each of the crowd's first 50 rounds, and the optional-loop
        // stop declines the 51st round's copy (MAX_TRIGGER_REPEATS, CR 732.2a); the leftover triggers do nothing
        assertEquals(MagicStack.MAX_TRIGGER_REPEATS + 1, countCardsWithName(game, "Sol Ring"));
    }

    @Test(timeOut = 300_000)
    public void aCrowdThatStaysUnderTheLimitResolves() {
        Game game = initAndCreateGame();
        Player ai = game.getPlayers().get(1);
        int most = crowd(game, ai, 55);

        assertFalse("a crowd of 1,480 items drew the game", game.isGameOver());
        assertEquals(1480, most);
        assertTrue(game.getStack().isEmpty());
    }

    @Test(timeOut = 300_000)
    public void aCrowdThatWouldPassTheLimitDrawsTheGame() {
        Game game = initAndCreateGame();
        Player ai = game.getPlayers().get(1);
        // 56 Ondus would peak at 1,531 items: the item after the 1,501st draws the game
        int most = crowd(game, ai, 56);

        assertTrue("the crowd should have drawn the game", game.isGameOver());
        assertEquals(GameEndReason.Draw, game.getOutcome().getWinCondition());
        assertEquals(1501, most);
        assertEquals(MagicStack.MAX_STACK_SIZE + 1, game.getStack().size());
    }
}
