package forge.game.trigger;

import static org.testng.AssertJUnit.assertEquals;
import static org.testng.AssertJUnit.assertFalse;
import static org.testng.AssertJUnit.assertTrue;

import org.testng.annotations.Test;

import forge.ai.AITest;
import forge.game.Game;
import forge.game.GameEndReason;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.zone.MagicStack;

/**
 * MagicStack.MAX_STACK_SIZE, the stack size past which the game is a draw: the last stop for a loop nothing else
 * catches (upstream #8254 put it at 999 against the heap running out). The stack holds the limit plus one, and the
 * next item draws the game.
 *
 * The Ondu Spiritdancer crowds that used to pin the limit here (51 fresh Ondus under Secret Arcade triggering on one
 * enchantment entering, 1,276 items at the peak, which 999 drew and 1,500 resolves) are the AI's business now: it
 * declines every optional trigger while the stack is past half the limit (forge.ai.LoopTracker.stackHeadroom), so a
 * crowd it feeds never reaches the limit at any size (forge.ai.LoopProgressTest.aCrowdNeverPassesTheHeadroom).
 */
public class StackSizeLimitTest extends AITest {

    @Test(timeOut = 300_000)
    public void theStackHoldsTheLimitPlusOneAndDrawsOnTheNext() {
        Game game = initAndCreateGame();
        Player ai = game.getPlayers().get(1);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, ai);
        game.getAction().checkStateEffects(true);

        pushAbilities(game, ai, MagicStack.MAX_STACK_SIZE + 1);
        assertEquals(MagicStack.MAX_STACK_SIZE + 1, game.getStack().size());
        assertFalse("the limit plus one holds", game.isGameOver());

        pushAbilities(game, ai, 1);
        assertTrue("the next item draws the game", game.isGameOver());
        assertEquals(GameEndReason.Draw, game.getOutcome().getWinCondition());
    }
}
