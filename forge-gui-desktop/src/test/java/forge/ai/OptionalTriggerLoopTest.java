package forge.ai;

import static org.testng.AssertJUnit.assertEquals;
import static org.testng.AssertJUnit.assertFalse;
import static org.testng.AssertJUnit.assertTrue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.testng.annotations.Test;

import forge.card.CardStateName;
import forge.game.Game;
import forge.game.GameEntityCounterTable;
import forge.game.ability.AbilityKey;
import forge.game.card.Card;
import forge.game.card.CounterEnumType;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.spellability.SpellAbilityStackInstance;
import forge.game.trigger.Trigger;
import forge.game.trigger.WrappedAbility;
import forge.game.zone.MagicStack;
import forge.game.zone.ZoneType;

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

    /** One -1/-1 counter on a creature, put the way a resolving effect puts it: Flourishing triggers on it. */
    private static void putACounterOn(Game game, Player ai, Card creature) {
        GameEntityCounterTable table = new GameEntityCounterTable();
        creature.addCounter(CounterEnumType.M1M1, 1, ai, table);
        table.replaceCounterEffect(game, null);
    }

    private Card[] startTheLoop(Game game, Player ai) {
        return startTheLoop(game, ai, false);
    }

    /**
     * Blowfly, Flourishing and two 1/1 Elf tokens for the AI, no creature for the opponent, and a -1/-1 counter on
     * the first Elf, put the way a resolving effect puts it: Flourishing triggers, and the Elf dies with the counter
     * at the next priority, so Blowfly triggers with only the other Elf to target. With a waiting trigger, the AI's
     * Grizzly Bears first takes a counter it survives, and the Flourishing trigger for it goes on the stack before the
     * Elf's counter, to wait below the loop. Returns Blowfly and Flourishing.
     */
    private Card[] startTheLoop(Game game, Player ai, boolean aWaitingTrigger) {
        Player opponent = game.getPlayers().get(0);
        Card blowfly = addCard("Blowfly Infestation", ai);
        Card flourishing = addCard("Flourishing Defenses", ai);
        Card first = addToken("g_1_1_elf_warrior", ai);
        addToken("g_1_1_elf_warrior", ai);
        Card bears = aWaitingTrigger ? addCard("Grizzly Bears", ai) : null;
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, ai);
        game.getAction().checkStateEffects(true);
        assertTrue("the opponent has no creature for Blowfly", opponent.getCreaturesInPlay().isEmpty());

        if (bears != null) {
            putACounterOn(game, ai, bears);
            game.getStack().addAllTriggeredAbilitiesToStack();
            assertEquals("Flourishing's trigger for the Bears waits on the stack", 1, game.getStack().size());
            assertEquals(triggerId(flourishing), game.getStack().peekAbility().getSourceTrigger());
        }
        putACounterOn(game, ai, first);
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

    /**
     * CR 732.2a is about a loop, not a crowd, as the draw detector's CR 104.4b is: 51 -1/-1 counters put at once
     * trigger Flourishing 51 times together, which is one repeat of it, so the AI takes every one of the 51 Elves.
     */
    @Test(timeOut = 300_000)
    public void aCrowdOfOptionalTriggersIsOneRepeatAndTheAiTakesEveryElf() {
        Game game = initAndCreateGame();
        Player ai = game.getPlayers().get(1);
        Player opponent = game.getPlayers().get(0);
        Card flourishing = addCard("Flourishing Defenses", ai);
        int crowd = MagicStack.MAX_TRIGGER_REPEATS + 1;
        List<Card> bears = new ArrayList<>();
        for (int i = 0; i < crowd; i++) {
            bears.add(addCard("Grizzly Bears", opponent));
        }
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, ai);
        game.getAction().checkStateEffects(true);
        assertTrue("the AI starts with no creature", ai.getCreaturesInPlay().isEmpty());

        // one -1/-1 counter on each Bear, all put by one effect: Flourishing triggers once a counter, all together
        GameEntityCounterTable table = new GameEntityCounterTable();
        for (Card bear : bears) {
            bear.addCounter(CounterEnumType.M1M1, 1, ai, table);
        }
        table.replaceCounterEffect(game, null);

        MagicStack stack = game.getStack();
        int id = triggerId(flourishing);
        int together = 0;
        int most = 0;
        int steps = 0;
        while (chainGoesOn(game) && steps++ < MAX_STEPS) {
            game.getPhaseHandler().mainLoopStep();
            together = Math.max(together, stack.size());
            most = Math.max(most, stack.getTriggerRepeats(id));
        }

        assertFalse(chainGoesOn(game));
        assertEquals("the 51 triggers were on the stack together", crowd, together);
        assertEquals("the AI took every Elf", crowd, ai.getCreaturesInPlay().size());
        assertEquals("a crowd is one repeat", 1, most);
    }

    /**
     * The loop can start above an earlier Flourishing trigger that then waits below it for as long as the loop goes
     * on (here the one for a counter the AI's Grizzly Bears survived). Every round's Flourishing trigger still goes on
     * the stack after something resolved, so each is a repeat and the AI stops the loop as it does with nothing
     * waiting. Counting only a trigger with no instance already on the stack never stopped it: each round saw the
     * waiting one, and the stack, three deep, never reaches the 999-item limit that would draw the game.
     */
    @Test(timeOut = 300_000)
    public void aFlourishingTriggerWaitingBelowTheLoopDoesNotHideItsRepeats() {
        Game game = initAndCreateGame();
        Player ai = game.getPlayers().get(1);
        int flourishing = triggerId(startTheLoop(game, ai, true)[1]);
        MagicStack stack = game.getStack();
        SpellAbilityStackInstance waiting = stack.peek();

        Set<Integer> elves = new HashSet<>();
        int deepest = 0;
        boolean pastTheLimit = false;
        boolean stillWaitingAtTheLimit = false;
        int steps = 0;
        do {
            for (Card c : ai.getCreaturesInPlay()) {
                if (c.isToken()) {
                    elves.add(c.getId());
                }
            }
            deepest = Math.max(deepest, stack.size());
            if (!pastTheLimit && stack.getTriggerRepeats(flourishing) > MagicStack.MAX_TRIGGER_REPEATS) {
                pastTheLimit = true;
                for (SpellAbilityStackInstance si : stack) {
                    stillWaitingAtTheLimit |= si == waiting;
                }
            }
            if (!chainGoesOn(game) || steps == MAX_STEPS) {
                break;
            }
            game.getPhaseHandler().mainLoopStep();
            steps++;
        } while (true);

        assertFalse("the loop never ended: " + elves.size() + " Elves in " + steps + " steps, Flourishing counted "
                + stack.getTriggerRepeats(flourishing) + " times", chainGoesOn(game));
        assertFalse("an optional loop is no draw", game.isGameOver());
        assertTrue("the Bears' Flourishing trigger still waited when the count passed the limit",
                stillWaitingAtTheLimit);
        assertEquals("the stack holds the waiting trigger and one round, no more", 3, deepest);
        assertTrue("Blowfly killed every creature", ai.getCreaturesInPlay().isEmpty());
        // The two Elves we started with, then one for each Flourishing trigger that resolved before the count passed
        // 50: the first Elf's, which went on with nothing resolved since the waiting one and so shares its count of 1,
        // and those for Blowfly's first 49 counters (2 to 50). The one for its 50th is declined, and so is the waiting
        // one at the end, or its Elf would still be there.
        assertEquals(MagicStack.MAX_TRIGGER_REPEATS + 2, elves.size());
    }

    /**
     * The stop is for an optional trigger only: a mandatory one is never declined, however often it has repeated.
     * Blowfly's targeted trigger is counted with Flourishing's, past the limit, and the AI still takes it, where the
     * same trigger made optional is declined at that count.
     */
    @Test(timeOut = 300_000)
    public void aMandatoryTriggerPastTheLimitIsNeverDeclined() {
        Game game = initAndCreateGame();
        Player ai = game.getPlayers().get(1);
        Card blowfly = startTheLoop(game, ai)[0];
        Trigger trigger = blowfly.getTriggers().iterator().next();
        MagicStack stack = game.getStack();
        int steps = 0;
        while (chainGoesOn(game) && stack.getTriggerRepeats(trigger.getId()) <= MagicStack.MAX_TRIGGER_REPEATS
                && steps++ < MAX_STEPS) {
            game.getPhaseHandler().mainLoopStep();
        }
        assertEquals(MagicStack.MAX_TRIGGER_REPEATS + 1, stack.getTriggerRepeats(trigger.getId()));

        WrappedAbility mandatory = wrap(trigger, blowfly, ai);
        assertTrue(mandatory.isMandatory());
        assertTrue("a mandatory trigger is never declined", ai.getController().confirmTrigger(mandatory));

        WrappedAbility optional = wrap(trigger, blowfly, ai);
        optional.setOptionalTrigger(true);
        assertFalse(optional.isMandatory());
        assertFalse("the same trigger, optional, is declined at this count",
                ai.getController().confirmTrigger(optional));
    }

    /** The trigger's ability as TriggerHandler wraps it when it goes off, with the AI as the decider. */
    private static WrappedAbility wrap(Trigger trigger, Card host, Player ai) {
        return new WrappedAbility(trigger, trigger.ensureAbility().copy(host, ai, false, true), ai);
    }

    /**
     * Each reset forgets when a trigger was last counted along with its count: nothing resolves here at all, so a
     * Flourishing trigger put on the stack after a reset would otherwise pass for one more of the crowd before it.
     */
    @Test(timeOut = 300_000)
    public void aResetForgetsWhenATriggerWasCountedToo() {
        Game game = initAndCreateGame();
        Player ai = game.getPlayers().get(1);
        Player opponent = game.getPlayers().get(0);
        Card forest = addCard("Forest", opponent);
        int flourishing = triggerId(addCard("Flourishing Defenses", ai));
        List<Card> bears = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            bears.add(addCard("Grizzly Bears", ai));
        }
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, ai);
        game.getAction().checkStateEffects(true);
        MagicStack stack = game.getStack();

        putACounterOn(game, ai, bears.get(0));
        stack.addAllTriggeredAbilitiesToStack();
        assertEquals(1, stack.getTriggerRepeats(flourishing));

        // a loss of life (clearTriggerRepeats) ...
        opponent.loseLife(1, false, false, null);
        assertEquals(0, stack.getTriggerRepeats(flourishing));
        putACounterOn(game, ai, bears.get(1));
        stack.addAllTriggeredAbilitiesToStack();
        assertEquals("counted again after a loss of life", 1, stack.getTriggerRepeats(flourishing));

        // ... a player doing something ...
        SpellAbility mana = forest.getManaAbilities().getFirst();
        mana.setActivatingPlayer(opponent);
        stack.add(mana);
        assertEquals(0, stack.getTriggerRepeats(flourishing));
        putACounterOn(game, ai, bears.get(2));
        stack.addAllTriggeredAbilitiesToStack();
        assertEquals("counted again after a mana ability", 1, stack.getTriggerRepeats(flourishing));
        assertEquals("nothing resolved", 3, stack.size());

        // ... and a reset of the whole stack
        stack.reset();
        assertEquals(0, stack.getTriggerRepeats(flourishing));
        putACounterOn(game, ai, bears.get(3));
        stack.addAllTriggeredAbilitiesToStack();
        assertEquals("counted again after a reset", 1, stack.getTriggerRepeats(flourishing));
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

    /**
     * Secret Arcade makes its controller's nonland permanents enchantments, Ondu Spiritdancer included, so Ondu's
     * "whenever an enchantment you control enters, you may create a token that's a copy of it. Do this only once each
     * turn." triggers on Ondu itself. Each copy is a new Ondu whose own once each turn is unused, so it triggers on
     * itself in turn and copies itself again. Every round's trigger comes from a new copy, with a trigger id of its
     * own, so the count by id never passed 1 and the AI never stopped: precon:Miracle Worker's turn never ended
     * (stored games 1929599 and 1934332, 20-minute Timeouts on turns 16 and 17). The copies share the first Ondu's
     * loop key, so the AI declines the 51st copy, as it declines Flourishing's 51st Elf.
     */
    @Test(timeOut = 300_000)
    public void onduSpiritdancerCopyingItselfUnderSecretArcadeStopsOnceItHasRepeatedFiftyTimes() {
        Game game = initAndCreateGame();
        Player ai = game.getPlayers().get(1);
        Card arcade = addCard("Secret Arcade // Dusty Parlor", ai);
        arcade.unlockRoom(ai, CardStateName.LeftSplit);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN2, ai);
        game.getAction().checkStateEffects(true);
        Card ondu = game.getAction().moveToPlay(addCardToZone("Ondu Spiritdancer", ai, ZoneType.Hand), null,
                AbilityKey.newMap());
        assertTrue("Secret Arcade makes Ondu an enchantment", ondu.isEnchantment());
        int loopKey = ondu.getTriggers().iterator().next().getLoopKey();

        Set<Integer> ondus = new HashSet<>();
        Set<Integer> triggerIds = new HashSet<>();
        int steps = 0;
        do {
            // the first step puts the waiting trigger on the stack (moveToPlay only ran it)
            game.getPhaseHandler().mainLoopStep();
            steps++;
            for (Card c : ai.getCreaturesInPlay()) {
                if (c.getName().equals("Ondu Spiritdancer") && ondus.add(c.getId())) {
                    for (Trigger t : c.getTriggers()) {
                        triggerIds.add(t.getId());
                        assertEquals("every copy keeps the first Ondu's loop key", loopKey, t.getLoopKey());
                    }
                }
            }
        } while (chainGoesOn(game) && steps < MAX_STEPS);

        assertFalse("the loop never ended: " + ondus.size() + " Ondus in " + steps + " steps", chainGoesOn(game));
        assertFalse("an optional loop is no draw", game.isGameOver());
        // The Ondu we started with, then one copy for each of the first 50 triggers: the 51st is declined.
        assertEquals(MagicStack.MAX_TRIGGER_REPEATS + 1, ondus.size());
        assertEquals("each copy's trigger has an id of its own", ondus.size(), triggerIds.size());
    }

    /**
     * A copy of a trigger gets an id of its own and keeps its original's loop key, through a chain of copies; an LKI
     * copy keeps both; another card's trigger, even of the same name, is a loop of its own.
     */
    @Test(timeOut = 300_000)
    public void aCopiedTriggerKeepsItsOriginalsLoopKey() {
        Game game = initAndCreateGame();
        Player ai = game.getPlayers().get(1);
        Card ondu = addCard("Ondu Spiritdancer", ai);
        Card another = addCard("Ondu Spiritdancer", ai);
        Trigger original = ondu.getTriggers().iterator().next();
        assertEquals("a trigger never copied is keyed by its own id", original.getId(), original.getLoopKey());

        Trigger copy = original.copy(another, false);
        Trigger copyOfCopy = copy.copy(ondu, false);
        assertTrue("a copy has an id of its own", copy.getId() != original.getId());
        assertTrue("so has a copy of a copy", copyOfCopy.getId() != copy.getId() && copyOfCopy.getId() != original.getId());
        assertEquals(original.getLoopKey(), copy.getLoopKey());
        assertEquals(original.getLoopKey(), copyOfCopy.getLoopKey());

        Trigger lki = original.copy(ondu, true);
        assertEquals(original.getId(), lki.getId());
        assertEquals(original.getLoopKey(), lki.getLoopKey());

        assertTrue("another card's trigger is another loop",
                another.getTriggers().iterator().next().getLoopKey() != original.getLoopKey());
    }
}
