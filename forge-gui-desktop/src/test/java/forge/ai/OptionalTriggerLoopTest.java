package forge.ai;

import static org.testng.AssertJUnit.assertEquals;
import static org.testng.AssertJUnit.assertFalse;
import static org.testng.AssertJUnit.assertTrue;

import java.util.HashSet;
import java.util.Set;

import org.testng.annotations.Test;

import forge.game.Game;
import forge.game.GameEntityCounterTable;
import forge.game.card.Card;
import forge.game.card.CounterEnumType;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.MagicStack;

/**
 * precon:Blight Curse's Blowfly Infestation and Flourishing Defenses loop for ever once the opponents have no
 * creature: Blowfly's mandatory, targeted -1/-1 counter falls back to the AI's own 1/1 Elf token, the Elf dies with
 * the counter (Blowfly again), and the counter made Flourishing's optional "you may create a 1/1 Elf" trigger, which
 * the AI always accepts. The draw detector (MagicStack.isRepeatingTrigger) counts only mandatory, untargeted
 * triggers, so it never sees this loop and the stack never empties (stored game 994130 ran out of heap; the active
 * engine's Blight Curse Timeouts are the same loop). CR 732.2a: the controller of an optional loop names a number
 * of iterations and then stops.
 */
public class OptionalTriggerLoopTest extends AITest {

    /** Ten times the steps the chain takes when the AI stops (four a round); without a stop it runs out of steps. */
    private static final int MAX_STEPS = 2000;

    private static boolean chainGoesOn(Game game) {
        return !game.isGameOver() && (!game.getStack().isEmpty() || game.getStack().hasSimultaneousStackEntries());
    }

    private static int triggerId(Card card) {
        return card.getTriggers().iterator().next().getId();
    }

    /**
     * Blowfly, Flourishing and two 1/1 Elf tokens for the AI, no creature for the opponent, and a -1/-1 counter on
     * the first Elf, put the way a resolving effect puts it: Flourishing triggers, and the Elf dies with the counter
     * at the next priority, so Blowfly triggers with only the other Elf to target. Returns Blowfly and Flourishing.
     */
    private Card[] startTheLoop(Game game, Player ai) {
        Player opponent = game.getPlayers().get(0);
        Card blowfly = addCard("Blowfly Infestation", ai);
        Card flourishing = addCard("Flourishing Defenses", ai);
        Card first = addToken("g_1_1_elf_warrior", ai);
        addToken("g_1_1_elf_warrior", ai);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, ai);
        game.getAction().checkStateEffects(true);
        assertTrue("the opponent has no creature for Blowfly", opponent.getCreaturesInPlay().isEmpty());

        GameEntityCounterTable table = new GameEntityCounterTable();
        first.addCounter(CounterEnumType.M1M1, 1, ai, table);
        table.replaceCounterEffect(game, null);
        // the board a dying creature's trigger looks back at, Blowfly and the counter on it (GameState copies it too)
        game.copyLastState();
        assertTrue("Flourishing triggers on the counter", game.getStack().hasSimultaneousStackEntries());
        return new Card[] {blowfly, flourishing};
    }

    @Test(timeOut = 300_000)
    public void blowflyAndFlourishingStopOnceTheOptionalElfHasRepeatedFiftyTimes() {
        Game game = initAndCreateGame();
        Player ai = game.getPlayers().get(1);
        startTheLoop(game, ai);

        Set<Integer> elves = new HashSet<>();
        for (Card c : ai.getCreaturesInPlay()) {
            elves.add(c.getId());
        }
        int steps = 0;
        while (chainGoesOn(game) && steps < MAX_STEPS) {
            game.getPhaseHandler().mainLoopStep();
            steps++;
            for (Card c : ai.getCreaturesInPlay()) {
                elves.add(c.getId());
            }
        }

        assertFalse("the loop never ended: " + elves.size() + " Elves in " + steps + " steps", chainGoesOn(game));
        assertFalse("an optional loop is no draw", game.isGameOver());
        assertTrue("Blowfly killed the last Elf", ai.getCreaturesInPlay().isEmpty());
        // The two Elves we started with, then one for each of Flourishing's first 50 triggers: its 51st is declined.
        assertEquals(MagicStack.MAX_TRIGGER_REPEATS + 2, elves.size());
    }

    /** The count the AI reads: every trigger, optional and targeted alike, on the draw detector's own resets. */
    @Test(timeOut = 300_000)
    public void theStackCountsTheLoopsOptionalAndTargetedTriggersUntilAReset() {
        Game game = initAndCreateGame();
        Player ai = game.getPlayers().get(1);
        Player opponent = game.getPlayers().get(0);
        Card forest = addCard("Forest", opponent);
        Card[] enchantments = startTheLoop(game, ai);
        int blowfly = triggerId(enchantments[0]);
        int flourishing = triggerId(enchantments[1]);
        MagicStack stack = game.getStack();
        assertEquals(0, stack.getTriggerRepeats(flourishing));

        // Each round puts one Blowfly and one Flourishing trigger on the stack together.
        int steps = 0;
        while (chainGoesOn(game) && stack.getTriggerRepeats(flourishing) < 10 && steps++ < MAX_STEPS) {
            game.getPhaseHandler().mainLoopStep();
            assertEquals(stack.getTriggerRepeats(flourishing), stack.getTriggerRepeats(blowfly));
        }
        assertEquals("the optional trigger is counted", 10, stack.getTriggerRepeats(flourishing));
        assertEquals("the targeted trigger is counted", 10, stack.getTriggerRepeats(blowfly));
        assertEquals("nothing else was counted", 0, stack.getTriggerRepeats(-1));

        // A player doing something (here a mana ability, which goes through the stack) starts the count again ...
        SpellAbility mana = forest.getManaAbilities().getFirst();
        mana.setActivatingPlayer(opponent);
        stack.add(mana);
        assertEquals(0, stack.getTriggerRepeats(flourishing));
        assertEquals(0, stack.getTriggerRepeats(blowfly));

        while (chainGoesOn(game) && stack.getTriggerRepeats(flourishing) < 3 && steps++ < MAX_STEPS) {
            game.getPhaseHandler().mainLoopStep();
        }
        assertEquals(3, stack.getTriggerRepeats(flourishing));
        // ... and so do a loss of life ...
        opponent.loseLife(1, false, false, null);
        assertEquals(0, stack.getTriggerRepeats(flourishing));
        assertEquals(0, stack.getTriggerRepeats(blowfly));

        while (chainGoesOn(game) && stack.getTriggerRepeats(flourishing) < 3 && steps++ < MAX_STEPS) {
            game.getPhaseHandler().mainLoopStep();
        }
        assertEquals(3, stack.getTriggerRepeats(flourishing));
        // ... and a reset of the whole stack.
        stack.reset();
        assertEquals(0, stack.getTriggerRepeats(flourishing));
        assertEquals(0, stack.getTriggerRepeats(blowfly));
    }

    /** The empty stack's reset: once the chain is over, the next priority forgets its count. */
    @Test(timeOut = 300_000)
    public void theCountIsForgottenOnceTheStackEmpties() {
        Game game = initAndCreateGame();
        Player ai = game.getPlayers().get(1);
        int flourishing = triggerId(startTheLoop(game, ai)[1]);
        MagicStack stack = game.getStack();

        int most = 0;
        for (int steps = 0; chainGoesOn(game) && steps < MAX_STEPS; steps++) {
            game.getPhaseHandler().mainLoopStep();
            most = Math.max(most, stack.getTriggerRepeats(flourishing));
        }
        assertFalse(chainGoesOn(game));
        // Flourishing triggers on every counter: the first Elf's, then each of Blowfly's 51 (one per Elf but the last).
        assertEquals(MagicStack.MAX_TRIGGER_REPEATS + 2, most);

        game.getPhaseHandler().mainLoopStep();
        assertEquals(0, stack.getTriggerRepeats(flourishing));
    }
}
