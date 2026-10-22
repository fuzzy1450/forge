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
import forge.game.ability.effects.CopyPermanentEffect;
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
 * the AI always accepted. The draw detector (MagicStack.isRepeatingTrigger) counts only mandatory, untargeted
 * triggers, so it never sees this loop and the stack never empties (stored game 994130 ran out of heap; the active
 * engine's Blight Curse Timeouts are the same loop). CR 732.2a: the controller of an optional loop names a number
 * of iterations and then stops. The AI first stopped at MagicStack.MAX_TRIGGER_REPEATS rounds; since
 * LoopTracker (MTG_DeckMaker, docs/superpowers/specs/2026-10-21-loop-aware-ai-design.md) it stops a loop whose rounds
 * change nothing after LoopTracker.IDLE_ROUNDS of them, keeps one that pays toward lethal going to ten times lethal,
 * and keeps the old count for a loop that only adds material.
 */
public class OptionalTriggerLoopTest extends AITest {

    /** Ten times the steps the chain takes when the AI stops (four a round); without a stop it runs out of steps. */
    private static final int MAX_STEPS = 2000;

    static boolean chainGoesOn(Game game) {
        return !game.isGameOver() && (!game.getStack().isEmpty() || game.getStack().hasSimultaneousStackEntries());
    }

    private static int triggerId(Card card) {
        return card.getTriggers().iterator().next().getId();
    }

    /** One -1/-1 counter on a creature, put the way a resolving effect puts it: Flourishing triggers on it. */
    static void putACounterOn(Game game, Player ai, Card creature) {
        GameEntityCounterTable table = new GameEntityCounterTable();
        creature.addCounter(CounterEnumType.M1M1, 1, ai, table);
        table.replaceCounterEffect(game, null);
    }

    private Card[] startTheLoop(Game game, Player ai) {
        return startTheLoop(game, ai, false, 0);
    }

    /**
     * Blowfly, Flourishing and two 1/1 Elf tokens for the AI, {@code opposingElves} 1/1 Elf tokens for the opponent
     * (none, and the loop turns on the AI's own Elves at once; a 2/2 would survive its first counter and end the loop
     * in a round), and a -1/-1 counter on the first Elf, put the way a resolving effect puts it: Flourishing triggers,
     * and the Elf dies with the counter at the next priority, so Blowfly triggers with the opponent's Elves, a curse's
     * first choice, or else only the other Elf, to target. With a waiting trigger, the AI's Grizzly
     * Bears first takes a counter it survives, and the Flourishing trigger for it goes on the stack before the Elf's
     * counter, to wait below the loop. Returns Blowfly and Flourishing.
     */
    private Card[] startTheLoop(Game game, Player ai, boolean aWaitingTrigger, int opposingElves) {
        Player opponent = game.getPlayers().get(0);
        Card blowfly = addCard("Blowfly Infestation", ai);
        Card flourishing = addCard("Flourishing Defenses", ai);
        Card first = addToken("g_1_1_elf_warrior", ai);
        addToken("g_1_1_elf_warrior", ai);
        Card bears = aWaitingTrigger ? addCard("Grizzly Bears", ai) : null;
        for (int i = 0; i < opposingElves; i++) {
            addToken("g_1_1_elf_warrior", opponent);
        }
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, ai);
        game.getAction().checkStateEffects(true);
        assertEquals("the opponent's creatures for Blowfly", opposingElves, opponent.getCreaturesInPlay().size());

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

    /**
     * Each round makes an Elf and loses one to Blowfly's counter, so the board after a round is the board before it:
     * the loop changes nothing, and the AI gives it up after LoopTracker.IDLE_ROUNDS such rounds instead of after
     * MagicStack.MAX_TRIGGER_REPEATS. The first round has nothing to compare and is the handler's call.
     */
    @Test(timeOut = 300_000)
    public void blowflyAndFlourishingStopOnceTwoRoundsAddedNothing() {
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
        // The two Elves we started with, then one for the first round and one for each idle round the AI allows
        // before it declines: far fewer than the 52 of the old count.
        assertEquals(2 + LoopTracker.IDLE_ROUNDS, elves.size());
        assertTrue(elves.size() < MagicStack.MAX_TRIGGER_REPEATS + 2);
    }

    /**
     * The same loop pays while the opponent has creatures: Blowfly's counter is a curse, so it goes on an opposing
     * 1/1 Elf, which dies of it, and each round the AI's Elves grow and the opponent's shrink, win progress both
     * ways. The AI keeps going until the opponent's Elves are gone, then the loop turns idle on its own and stops.
     */
    @Test(timeOut = 300_000)
    public void blowflyAgainstARowOfOpposingElvesKeepsGoingWhileTheyDie() {
        Game game = initAndCreateGame();
        Player ai = game.getPlayers().get(1);
        Player opponent = game.getPlayers().get(0);
        startTheLoop(game, ai, false, 20);

        int mostElves = 0;
        int steps = 0;
        while (chainGoesOn(game) && steps++ < 10 * MAX_STEPS) {
            game.getPhaseHandler().mainLoopStep();
            mostElves = Math.max(mostElves, ai.getCreaturesInPlay().size());
        }

        assertFalse("the loop never ended", chainGoesOn(game));
        assertFalse(game.isGameOver());
        assertTrue("Blowfly cleared the opponent's Elves", opponent.getCreaturesInPlay().isEmpty());
        // an Elf a counter: the AI's Elves grew past twenty while the opponent's died, before Blowfly, with nothing
        // else to hit, turned on them and the AI gave the idle loop up
        assertTrue("the Elves that paid: " + mostElves, mostElves >= 20);
        assertTrue("Blowfly then killed the Elves", ai.getCreaturesInPlay().isEmpty());
    }

    /**
     * Decisive is read only inside a repeating loop: a one-off optional trigger with ten times lethal on the board is
     * still the handler's call, and the AI takes its Elf.
     */
    @Test(timeOut = 300_000)
    public void decisiveIsReadOnlyInsideALoop() {
        Game game = initAndCreateGame();
        Player ai = game.getPlayers().get(1);
        Player opponent = game.getPlayers().get(0);
        opponent.setLife(1, null);
        addCards("Grizzly Bears", 5, ai);
        addCard("Flourishing Defenses", ai);
        Card bear = addCard("Grizzly Bears", opponent);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, ai);
        game.getAction().checkStateEffects(true);
        assertTrue(LoopTracker.Snapshot.of(ai).isDecisive());

        putACounterOn(game, ai, bear);
        int steps = 0;
        while (chainGoesOn(game) && steps++ < MAX_STEPS) {
            game.getPhaseHandler().mainLoopStep();
        }
        int tokens = 0;
        for (Card c : ai.getCreaturesInPlay()) {
            if (c.isToken()) {
                tokens++;
            }
        }
        assertEquals("a one-off Elf is still taken", 1, tokens);
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
     * the stack after something resolved, so each is a round and the AI judges the loop as it does with nothing
     * waiting: idle, and given up after LoopTracker.IDLE_ROUNDS rounds. The waiting trigger is the spent loop's too
     * and is declined at the end, or its Elf would still be there. Counting only a trigger with no instance already
     * on the stack never stopped it: each round saw the waiting one, and the stack, three deep, never reaches the item
     * limit that would draw the game (MagicStack.MAX_STACK_SIZE).
     */
    @Test(timeOut = 300_000)
    public void aFlourishingTriggerWaitingBelowTheLoopDoesNotHideItsRepeats() {
        Game game = initAndCreateGame();
        Player ai = game.getPlayers().get(1);
        int flourishing = triggerId(startTheLoop(game, ai, true, 0)[1]);
        MagicStack stack = game.getStack();
        SpellAbilityStackInstance waiting = stack.peek();

        Set<Integer> elves = new HashSet<>();
        int deepest = 0;
        boolean idleRoundsReached = false;
        boolean stillWaitingThen = false;
        int steps = 0;
        do {
            for (Card c : ai.getCreaturesInPlay()) {
                if (c.isToken()) {
                    elves.add(c.getId());
                }
            }
            deepest = Math.max(deepest, stack.size());
            if (!idleRoundsReached && stack.getTriggerRepeats(flourishing) >= 1 + LoopTracker.IDLE_ROUNDS) {
                idleRoundsReached = true;
                for (SpellAbilityStackInstance si : stack) {
                    stillWaitingThen |= si == waiting;
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
        assertTrue("the Bears' Flourishing trigger still waited when the loop's rounds turned idle", stillWaitingThen);
        assertEquals("the stack holds the waiting trigger and one round, no more", 3, deepest);
        assertTrue("Blowfly killed every creature", ai.getCreaturesInPlay().isEmpty());
        // The two Elves we started with, then one for the loop's first round (which went on with nothing resolved
        // since the waiting one and so shares its count) and one for each idle round the AI allows. The waiting one is
        // declined at the end, as the spent loop's.
        assertEquals(2 + LoopTracker.IDLE_ROUNDS, elves.size());
    }

    /**
     * The stop is for an optional trigger only: a mandatory one is never declined, however the loop stands. With the
     * loop's rounds idle and its next trigger on the stack, Flourishing's trigger made optional is declined, and the
     * same trigger mandatory is confirmed.
     */
    @Test(timeOut = 300_000)
    public void aMandatoryTriggerIsNeverDeclinedWhereTheOptionalOneIs() {
        Game game = initAndCreateGame();
        Player ai = game.getPlayers().get(1);
        Card flourishing = startTheLoop(game, ai)[1];
        Trigger trigger = flourishing.getTriggers().iterator().next();
        MagicStack stack = game.getStack();
        int steps = 0;
        while (chainGoesOn(game) && stack.getTriggerRepeats(trigger.getLoopKey()) < 1 + LoopTracker.IDLE_ROUNDS
                && steps++ < MAX_STEPS) {
            game.getPhaseHandler().mainLoopStep();
        }
        assertEquals(1 + LoopTracker.IDLE_ROUNDS, stack.getTriggerRepeats(trigger.getLoopKey()));

        WrappedAbility mandatory = wrap(trigger, flourishing, ai);
        assertTrue(mandatory.isMandatory());
        assertTrue("a mandatory trigger is never declined", ai.getController().confirmTrigger(mandatory));

        WrappedAbility optional = wrap(trigger, flourishing, ai);
        optional.setOptionalTrigger(true);
        assertFalse(optional.isMandatory());
        assertFalse("the same trigger, optional, is declined: its loop is spent",
                ai.getController().confirmTrigger(optional));
    }

    /** The trigger's ability as TriggerHandler wraps it when it goes off, with the AI as the decider. */
    static WrappedAbility wrap(Trigger trigger, Card host, Player ai) {
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

    /**
     * The count the AI reads: every trigger, optional and targeted alike, on the draw detector's own resets. Against
     * a row of opposing 1/1 Elves the loop pays, so the AI keeps it going long enough to read the counts.
     */
    @Test(timeOut = 300_000)
    public void theStackCountsTheLoopsOptionalAndTargetedTriggersUntilAReset() {
        Game game = initAndCreateGame();
        Player ai = game.getPlayers().get(1);
        Player opponent = game.getPlayers().get(0);
        Card forest = addCard("Forest", opponent);
        Card[] enchantments = startTheLoop(game, ai, false, 20);
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
        // Flourishing triggers on every counter: the first Elf's, then each of Blowfly's, one per Elf but the last,
        // the loop's rounds being the first, the idle ones the AI allows, the declined one, and the one for the
        // counter that kills the last Elf, declined too.
        assertEquals(2 + LoopTracker.IDLE_ROUNDS, most);

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
     * loop key, so the AI reads the chain as one loop; each copy is a 3/3 that attacks next turn, win progress, and
     * the AI keeps copying until the board is ten times the opponent's life (Evan's rule for an unbounded loop), 67
     * Ondus against 20 life, where the old count stopped at 51.
     */
    @Test(timeOut = 300_000)
    public void onduSpiritdancerCopyingItselfUnderSecretArcadeStopsAtTenTimesLethal() {
        Game game = initAndCreateGame();
        Player ai = game.getPlayers().get(1);
        Player opponent = game.getPlayers().get(0);
        opponent.setLife(20, null);
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
        // 3 power a copy against 20 life: the copy that first reaches 200 power is the last the AI makes.
        assertEquals((LoopTracker.LETHAL_MULTIPLE * 20 + 2) / 3, ondus.size());
        assertTrue("past the old count", ondus.size() > MagicStack.MAX_TRIGGER_REPEATS + 1);
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

    /**
     * A crowd of copies is one repeat, as a crowd of one trigger is: Flourishing Defenses and 50 copies of it, made the
     * way a token copy is (CopyPermanentEffect.getProtoType, so 51 trigger ids under one loop key), all trigger on one
     * -1/-1 counter together. That is one repeat of the loop key, and the AI takes all 51 Elves, as it did when each
     * copy was counted by its own id. Counting the crowd by id but the repeats by key would make it 51 repeats, and
     * the AI would decline every Elf.
     */
    @Test(timeOut = 300_000)
    public void aCrowdOfCopiesOfOneTriggerIsOneRepeatAndTheAiTakesEveryElf() {
        Game game = initAndCreateGame();
        Player ai = game.getPlayers().get(1);
        Player opponent = game.getPlayers().get(0);
        Card flourishing = addCard("Flourishing Defenses", ai);
        int loopKey = flourishing.getTriggers().iterator().next().getLoopKey();
        Set<Integer> ids = new HashSet<>();
        ids.add(flourishing.getTriggers().iterator().next().getId());
        int crowd = MagicStack.MAX_TRIGGER_REPEATS + 1;
        for (int i = 1; i < crowd; i++) {
            Card copy = CopyPermanentEffect.getProtoType(flourishing.getFirstSpellAbility(), flourishing, ai);
            copy.setGameTimestamp(game.getNextTimestamp());
            ai.getZone(ZoneType.Battlefield).add(copy);
            Trigger t = copy.getTriggers().iterator().next();
            assertEquals("a copy keeps the original's loop key", loopKey, t.getLoopKey());
            ids.add(t.getId());
        }
        assertEquals("every copy's trigger has an id of its own", crowd, ids.size());
        Card bear = addCard("Grizzly Bears", opponent);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, ai);
        game.getAction().checkStateEffects(true);
        assertTrue("the AI starts with no creature", ai.getCreaturesInPlay().isEmpty());

        putACounterOn(game, ai, bear);
        MagicStack stack = game.getStack();
        int together = 0;
        int most = 0;
        int steps = 0;
        do {
            game.getPhaseHandler().mainLoopStep();
            together = Math.max(together, stack.size());
            most = Math.max(most, stack.getTriggerRepeats(loopKey));
        } while (chainGoesOn(game) && steps++ < MAX_STEPS);

        assertFalse(chainGoesOn(game));
        assertEquals("the 51 triggers were on the stack together", crowd, together);
        assertEquals("a crowd of copies is one repeat", 1, most);
        assertEquals("the AI took every Elf", crowd, ai.getCreaturesInPlay().size());
    }
}
