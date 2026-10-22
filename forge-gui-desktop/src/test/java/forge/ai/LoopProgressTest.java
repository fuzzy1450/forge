package forge.ai;

import static org.testng.AssertJUnit.assertEquals;
import static org.testng.AssertJUnit.assertFalse;
import static org.testng.AssertJUnit.assertNotSame;
import static org.testng.AssertJUnit.assertNull;
import static org.testng.AssertJUnit.assertSame;
import static org.testng.AssertJUnit.assertTrue;

import java.util.Set;

import org.testng.annotations.Test;

import forge.ai.LoopTracker.Loop;
import forge.ai.LoopTracker.Progress;
import forge.ai.LoopTracker.Snapshot;
import forge.card.CardStateName;
import forge.game.Game;
import forge.game.GameEndReason;
import forge.game.ability.AbilityFactory;
import forge.game.ability.AbilityKey;
import forge.game.ability.effects.CopyPermanentEffect;
import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.card.CardLists;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.trigger.Trigger;
import forge.game.trigger.WrappedAbility;
import forge.game.zone.MagicStack;
import forge.game.zone.ZoneType;

/**
 * The AI plays the loops it controls the way a strong player does (docs/superpowers/specs/2026-10-21-loop-aware-ai-design.md
 * in MTG_DeckMaker): it keeps a loop going while each round pays toward lethal, pushes a winning one to ten times lethal,
 * stops one that changes nothing after two rounds, and breaks a mandatory copy loop by its target choice. The judge is
 * {@link LoopTracker}, which reads the stack's rounds of a trigger's loop key (CR 732.2a's count, MagicStack.getTriggerRepeats)
 * and compares a cheap board snapshot from round to round.
 */
public class LoopProgressTest extends AITest {

    private Game game;
    private Player ai;
    private Player opponent;

    private void start() {
        game = initAndCreateGame();
        ai = game.getPlayers().get(1);
        opponent = game.getPlayers().get(0);
        opponent.setLife(20, null);
        ai.setLife(20, null);
    }

    private void settle() {
        game.getAction().checkStateEffects(true);
    }

    /** Win progress is an opponent who can lose moving toward a loss or the AI's attack power rising; material is the AI's own stuff rising; idle is nothing. */
    @Test(timeOut = 300_000)
    public void aSnapshotReadsWinMaterialAndIdle() {
        start();
        addCard("Grizzly Bears", ai);
        addCard("Forest", ai);
        Card bear = addCard("Grizzly Bears", opponent);
        settle();

        Snapshot s0 = Snapshot.of(ai);
        addCard("Grizzly Bears", ai);
        settle();
        assertEquals("the AI's attack power rose", Progress.WIN, Snapshot.of(ai).progressSince(s0));

        Snapshot s1 = Snapshot.of(ai);
        addCard("Forest", ai);
        settle();
        assertEquals("a permanent with no power is material", Progress.MATERIAL, Snapshot.of(ai).progressSince(s1));

        Snapshot s2 = Snapshot.of(ai);
        assertEquals("nothing changed", Progress.IDLE, Snapshot.of(ai).progressSince(s2));

        opponent.loseLife(1, false, false, null);
        assertEquals("the opponent's life fell", Progress.WIN, Snapshot.of(ai).progressSince(s2));

        Snapshot s3 = Snapshot.of(ai);
        game.getAction().moveToGraveyard(bear, null);
        settle();
        assertEquals("the opponent's creature power fell", Progress.WIN, Snapshot.of(ai).progressSince(s3));
    }

    /** Platinum Angel: a strong player knows the Angel is there. */
    @Test(timeOut = 300_000)
    public void lifeLostByAPlayerWhoCannotLoseIsNoProgress() {
        start();
        addCard("Platinum Angel", opponent);
        settle();
        assertTrue(opponent.cantLose());

        Snapshot s0 = Snapshot.of(ai);
        opponent.loseLife(5, false, false, null);
        assertEquals(Progress.IDLE, Snapshot.of(ai).progressSince(s0));
        addCard("Grizzly Bears", ai);
        settle();
        assertFalse("nobody who can lose: never decisive", Snapshot.of(ai).isDecisive());
    }

    @Test(timeOut = 300_000)
    public void decisiveIsTenTimesTheLifeOfOpponentsWhoCanLose() {
        start();
        opponent.setLife(2, null);
        addCards("Grizzly Bears", 10, ai);
        settle();
        assertEquals(20, Snapshot.of(ai).ownPower());
        assertTrue("20 power against 2 life", Snapshot.of(ai).isDecisive());

        opponent.setLife(3, null);
        assertFalse("20 power against 3 life", Snapshot.of(ai).isDecisive());

        addCard("Wall of Reverence", ai);
        settle();
        assertEquals("a defender adds no attack power", 20, Snapshot.of(ai).ownPower());
    }

    /** A 3-player game: an opponent gone between two rounds is a win, never an exception. */
    @Test(timeOut = 300_000)
    public void anOpponentWhoLeftTheGameIsWinProgress() {
        Game three = initAndCreateThreePlayerGame();
        Player us = three.getPlayers().get(1);
        Player gone = three.getPlayers().get(2);
        for (Player p : three.getPlayers()) {
            p.setLife(20, null);
        }
        assertEquals("both others are opponents", 2, us.getOpponents().size());
        three.getAction().checkStateEffects(true);

        Snapshot s0 = Snapshot.of(us);
        gone.setLife(0, null);
        three.getAction().checkStateEffects(true);
        assertTrue(gone.hasLost());
        assertEquals(Progress.WIN, Snapshot.of(us).progressSince(s0));
    }

    /** The per-key record: a key below zero is nothing; the same count is the same round; a new count compares; a falling count forgets. */
    @Test(timeOut = 300_000)
    public void theTrackerFoldsRoundsAndForgetsOnAReset() {
        start();
        settle();
        LoopTracker t = new LoopTracker();
        Snapshot s = Snapshot.of(ai);
        assertNull("not a trigger's ability: never tracked", t.observe(-1, 3, s));

        Loop loop = t.observe(7, 1, s);
        assertEquals(0, loop.comparedRounds());
        assertNull(loop.last());
        assertSame("the same count is the same round", loop, t.observe(7, 1, s));
        assertEquals(0, t.loop(7).comparedRounds());

        t.observe(7, 2, s);
        assertEquals(1, t.loop(7).comparedRounds());
        assertEquals(1, t.loop(7).idleRounds());
        assertEquals(Progress.IDLE, t.loop(7).last());
        assertFalse("one idle round is not spent", t.loop(7).isSpent(s));

        t.observe(7, 3, s);
        assertEquals(2, t.loop(7).idleRounds());
        assertTrue("two idle rounds: spent", t.loop(7).isSpent(s));
        assertTrue(t.loop(7).declinesOptional(s));

        Loop fresh = t.observe(7, 1, s);
        assertNotSame("the count fell: a new chain", loop, fresh);
        assertEquals(0, fresh.comparedRounds());
        assertFalse(fresh.isSpent(s));
    }

    /** Venser's shape: the names chosen before the loop was judged spent stay avoided through a paying round, until the reset. */
    @Test(timeOut = 300_000)
    public void aSpentLoopAvoidsItsNamesUntilTheReset() {
        start();
        settle();
        LoopTracker t = new LoopTracker();
        Snapshot s = Snapshot.of(ai);
        t.observe(7, 0, s).remember("Venser, Fervent Forger");
        t.observe(7, 1, s).remember("Venser, Fervent Forger");
        assertTrue(t.loop(7).avoided().isEmpty());
        t.observe(7, 2, s);
        assertEquals(Set.of("Venser, Fervent Forger"), t.loop(7).avoided());

        t.loop(7).remember("Grizzly Bears");
        addCard("Grizzly Bears", ai);
        settle();
        Snapshot paid = Snapshot.of(ai);
        Loop loop = t.observe(7, 3, paid);
        assertEquals(Progress.WIN, loop.last());
        assertEquals(0, loop.idleRounds());
        assertTrue("spent stays spent", loop.isSpent(paid));
        assertEquals("the avoided set is frozen", Set.of("Venser, Fervent Forger"), loop.avoided());

        assertTrue("the reset forgets", t.observe(7, 0, paid).avoided().isEmpty());
    }

    /** Decisive is read only inside a repeating loop; a key with no comparison keeps the old count rule alone. */
    @Test(timeOut = 300_000)
    public void aKeyNeverComparedIsNeverDeclinedByALoopRule() {
        start();
        opponent.setLife(1, null);
        addCards("Grizzly Bears", 5, ai);
        settle();
        Snapshot s = Snapshot.of(ai);
        assertTrue(s.isDecisive());
        assertFalse(new LoopTracker().observe(7, 1, s).declinesOptional(s));
        assertTrue("the old count rule still applies", new LoopTracker().observe(7, 51, s).declinesOptional(s));
    }

    /** A one-round key seen again at count 1 on a later turn is a new chain, not a round to compare. */
    @Test(timeOut = 300_000)
    public void theSameCountOnALaterTurnIsAFreshChain() {
        start();
        settle();
        LoopTracker t = new LoopTracker();
        Loop first = t.observe(7, 1, Snapshot.of(ai));
        int turn = game.getPhaseHandler().getTurn();
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, ai, turn + 1);
        addCard("Grizzly Bears", ai);
        settle();
        Loop again = t.observe(7, 1, Snapshot.of(ai));
        assertNotSame(first, again);
        assertEquals("nothing compared across turns", 0, again.comparedRounds());
    }

    /** The rules drop, never empty: with no other candidate the full list stands. */
    @Test(timeOut = 300_000)
    public void withoutAvoidedKeepsTheFullListWhenNothingElseIsLeft() {
        start();
        settle();
        LoopTracker t = new LoopTracker();
        Snapshot s = Snapshot.of(ai);
        for (int c = 0; c <= 2; c++) {
            t.observe(7, c, s).remember("Grizzly Bears");
        }
        assertEquals(Set.of("Grizzly Bears"), t.loop(7).avoided());

        CardCollection only = new CardCollection(addCard("Grizzly Bears", opponent));
        assertSame(only, t.loop(7).withoutAvoided(only));
        CardCollection two = new CardCollection(only);
        two.add(addCard("Forest", opponent));
        assertEquals(1, t.loop(7).withoutAvoided(two).size());
        assertEquals("Forest", t.loop(7).withoutAvoided(two).getFirst().getName());
    }

    /** Steps enough for a 201-copy chain or an 80-Ondu crowd; without a stop they run out. */
    private static final int MAX_STEPS = 200_000;

    /**
     * Secret Arcade unlocked and an Ondu Spiritdancer entering from hand in the AI's main 2 (OptionalTriggerLoopTest
     * has why that loops); plays the chain out and returns the Ondus on the battlefield.
     */
    private int runOnduChain() {
        addCard("Secret Arcade // Dusty Parlor", ai).unlockRoom(ai, CardStateName.LeftSplit);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN2, ai);
        settle();
        game.getAction().moveToPlay(addCardToZone("Ondu Spiritdancer", ai, ZoneType.Hand), null, AbilityKey.newMap());
        int steps = 0;
        do {
            game.getPhaseHandler().mainLoopStep();
        } while (OptionalTriggerLoopTest.chainGoesOn(game) && ++steps < MAX_STEPS);
        assertFalse("the chain never ended", OptionalTriggerLoopTest.chainGoesOn(game));
        return countCardsWithName(game, "Ondu Spiritdancer");
    }

    /**
     * Secret Arcade unlocked, then {@code n} Ondu Spiritdancers (one, and n - 1 copies of it made as a token copy is,
     * so none has entered this turn or used its once each turn), then a Sol Ring entering, which Secret Arcade makes an
     * enchantment: every Ondu triggers, and every copy that resolves triggers the ones that have not copied yet. Plays
     * the crowd out and returns the most items the stack held.
     */
    private int crowd(int n) {
        addCard("Secret Arcade // Dusty Parlor", ai).unlockRoom(ai, CardStateName.LeftSplit);
        Card ondu = addCard("Ondu Spiritdancer", ai);
        for (int i = 1; i < n; i++) {
            Card copy = CopyPermanentEffect.getProtoType(ondu.getFirstSpellAbility(), ondu, ai);
            copy.setGameTimestamp(game.getNextTimestamp());
            ai.getZone(ZoneType.Battlefield).add(copy);
        }
        game.getPhaseHandler().devModeSet(PhaseType.MAIN2, ai);
        settle();
        assertEquals(n, countCardsWithName(game, "Ondu Spiritdancer"));

        game.getAction().moveToPlay(addCardToZone("Sol Ring", ai, ZoneType.Hand), null, AbilityKey.newMap());
        int most = 0;
        int steps = 0;
        do {
            game.getPhaseHandler().mainLoopStep();
            most = Math.max(most, game.getStack().size());
        } while (OptionalTriggerLoopTest.chainGoesOn(game) && ++steps < MAX_STEPS);
        assertFalse("the crowd never ended", OptionalTriggerLoopTest.chainGoesOn(game));
        return most;
    }

    /** Evan's rule: an unbounded loop that pays goes to ten times lethal, past the old count of 50. 3 power a copy against 40 life is 134 Ondus. */
    @Test(timeOut = 300_000)
    public void onduChainRunsPastFiftyToTenTimesLethal() {
        start();
        opponent.setLife(40, null);
        int ondus = runOnduChain();
        assertFalse(game.isGameOver());
        assertEquals((LoopTracker.LETHAL_MULTIPLE * 40 + 2) / 3, ondus);
        assertTrue(ondus > MagicStack.MAX_TRIGGER_REPEATS + 1);
    }

    /** Against a player who cannot lose nothing is decisive, and the budget for a winning loop ends it. */
    @Test(timeOut = 300_000)
    public void onduChainAgainstAPlayerWhoCannotLoseStopsAtTheCap() {
        start();
        addCard("Platinum Angel", opponent);
        settle();
        assertEquals(LoopTracker.MAX_PROGRESS_ROUNDS + 1, runOnduChain());
        assertFalse(game.isGameOver());
    }

    /**
     * 80 fresh Ondus would put about 3,240 items on the stack and draw the game at MagicStack.MAX_STACK_SIZE. The AI
     * declines every optional trigger while the stack is past half the limit, so the crowd never passes the headroom
     * by more than its own size, and the copies, material, stay under the old count.
     */
    @Test(timeOut = 300_000)
    public void aCrowdNeverPassesTheHeadroom() {
        start();
        int most = crowd(80);
        assertFalse("the crowd drew the game", game.isGameOver());
        assertTrue(game.getStack().isEmpty());
        assertTrue("peak " + most, most <= LoopTracker.stackHeadroom() + 80);
        int rings = countCardsWithName(game, "Sol Ring");
        assertTrue("Sol Rings: " + rings, rings >= 2 && rings <= MagicStack.MAX_TRIGGER_REPEATS + 1);
    }

    /**
     * The headroom guard reads optional triggers only: a mandatory one is confirmed whatever the stack holds. The
     * opponent's Grizzly Bears gives Blowfly's counter a target the handler takes, so the optional decline is the
     * guard's and nobody else's.
     */
    @Test(timeOut = 300_000)
    public void theHeadroomGuardNeverDeclinesAMandatoryTrigger() {
        start();
        Card blowfly = addCard("Blowfly Infestation", ai);
        addCard("Grizzly Bears", opponent);
        settle();
        Trigger trigger = blowfly.getTriggers().iterator().next();
        pushAbilities(game, ai, LoopTracker.stackHeadroom() + 1);
        assertTrue(game.getStack().size() > LoopTracker.stackHeadroom());

        WrappedAbility mandatory = OptionalTriggerLoopTest.wrap(trigger, blowfly, ai);
        assertTrue(mandatory.isMandatory());
        assertTrue(ai.getController().confirmTrigger(mandatory));
        WrappedAbility optional = OptionalTriggerLoopTest.wrap(trigger, blowfly, ai);
        optional.setOptionalTrigger(true);
        assertTrue("the handler itself would take the counter",
                ((PlayerControllerAi) ai.getController()).getAi().doTrigger(optional.getWrappedAbility(), false));
        assertFalse("an optional trigger above the headroom is declined", ai.getController().confirmTrigger(optional));
    }

    private static int tokensNamed(Player p, String name) {
        return CardLists.filter(p.getCreaturesInPlay(), c -> c.isToken() && c.getName().equals(name)).size();
    }

    /** The AI's own Venser, Fervent Forger entering from hand: its trigger chooses a mode and a target as the engine does. */
    private Card enterOurVenser() {
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, ai);
        settle();
        return game.getAction().moveToPlay(addCardToZone("Venser, Fervent Forger", ai, ZoneType.Hand), null,
                AbilityKey.newMap());
    }

    /** Plays the chain out, stepping first: right after moveToPlay the trigger waits in the trigger handler, not yet on the stack. */
    private int playOut(int maxSteps) {
        int steps = 0;
        do {
            game.getPhaseHandler().mainLoopStep();
        } while (OptionalTriggerLoopTest.chainGoesOn(game) && ++steps < maxSteps);
        return steps;
    }

    /**
     * Cursed Mirror entering as a copy of the opponent's Venser, Fervent Forger copied that Venser twice, the legend
     * rule kept one of the three, each token's trigger did the same, and the game ended at the stack-size draw (16 of
     * the active engine's 65 stored Draws). A legendary candidate whose name the AI already controls a legendary of is
     * dropped: the trigger copies the Grizzly Bears, and no loop starts.
     */
    @Test(timeOut = 300_000)
    public void vensersTriggerNeverCopiesTheLegendTheAiAlreadyControls() {
        start();
        addCard("Venser, Fervent Forger", opponent);
        addCard("Grizzly Bears", opponent);
        Card venser = enterOurVenser();
        int key = venser.getTriggers().iterator().next().getLoopKey();

        int most = 0;
        int steps = 0;
        do {
            game.getPhaseHandler().mainLoopStep();
            most = Math.max(most, game.getStack().getTriggerRepeats(key));
        } while (OptionalTriggerLoopTest.chainGoesOn(game) && ++steps < 2000);
        assertFalse(OptionalTriggerLoopTest.chainGoesOn(game));
        assertFalse(game.isGameOver());
        assertEquals("one round: the trigger copied the Bears, not Venser", 1, most);
        assertEquals("two hasty Bears", 2, tokensNamed(ai, "Grizzly Bears"));
        assertEquals("no Venser token was ever kept", 0, tokensNamed(ai, "Venser, Fervent Forger"));
    }

    /**
     * A mandatory copy loop that pays: the opponent's Venser is a nonlegendary token (a copy made with NonLegendary$
     * True, as the legend rule read-ahead reads only legendary candidates), so every copy of it stays, attacks for 5
     * and triggers again. The AI rides it to ten times the opponent's life, then its target choice takes the Bears
     * and the loop drains, instead of running to the stack-size draw.
     */
    @Test(timeOut = 300_000)
    public void aPayingMandatoryCopyLoopIsBrokenOnceTheBoardIsDecisive() {
        start();
        Card oppVenser = addCard("Venser, Fervent Forger", opponent);
        SpellAbility maker = AbilityFactory.getAbility("DB$ CopyPermanent | Defined$ Self | NonLegendary$ True", oppVenser);
        Card token = CopyPermanentEffect.getProtoType(maker, oppVenser, opponent);
        token.setGameTimestamp(game.getNextTimestamp());
        opponent.getZone(ZoneType.Battlefield).add(token);
        game.getAction().moveToGraveyard(oppVenser, null);
        addCard("Grizzly Bears", opponent);
        opponent.setLife(4, null);
        settle();
        assertFalse("the opponent's Venser is a nonlegendary token", token.getType().isLegendary());
        assertEquals(2, opponent.getCreaturesInPlay().size());

        enterOurVenser();
        // a bounded number of steps, not a timeout: a loop that never breaks grows the board by two creatures a
        // resolution and slows every step, and a test interrupted mid-game leaves the shared engine state behind
        playOut(2_000);

        assertFalse("the loop never broke", OptionalTriggerLoopTest.chainGoesOn(game));
        assertFalse("drawn at the stack limit", game.isGameOver());
        assertTrue("ten times 4 life: " + Snapshot.of(ai).ownPower(), Snapshot.of(ai).ownPower() >= LoopTracker.LETHAL_MULTIPLE * 4);
        assertTrue("the spent loop took the Bears", tokensNamed(ai, "Grizzly Bears") >= 2);
    }

    /** With no other legal target the rules drop nothing, the trigger takes the one it has, and the game's own draw rule is the last word. */
    @Test(timeOut = 300_000)
    public void withNoOtherTargetTheFullListIsUsed() {
        start();
        addCard("Venser, Fervent Forger", opponent);
        enterOurVenser();
        playOut(20_000);

        assertTrue("a loop with no other choice is the engine's draw", game.isGameOver());
        assertEquals(GameEndReason.Draw, game.getOutcome().getWinCondition());
    }

    /**
     * The two rules read a trigger's ability only. Rite of Replication cast from hand goes through the same target
     * loop (CopyPermanentAi.checkApiLogic calls it with mandatory false), and with the opponent's Venser the best
     * creature it is still the pick, our own Venser notwithstanding.
     */
    @Test(timeOut = 300_000)
    public void aCastCopySpellKeepsTodaysPick() {
        start();
        addCard("Venser, Fervent Forger", opponent);
        addCard("Grizzly Bears", opponent);
        addCard("Venser, Fervent Forger", ai);
        addCards("Island", 9, ai);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, ai);
        settle();
        Card rite = addCardToZone("Rite of Replication", ai, ZoneType.Hand);
        SpellAbility cast = rite.getFirstSpellAbility();
        cast.setActivatingPlayer(ai);
        assertFalse(cast.isTrigger());

        assertTrue("the AI would cast it", SpellApiToAi.Converter.get(cast).doTrigger(ai, cast, false));
        assertEquals("Venser, Fervent Forger", cast.getTargets().getTargetCards().get(0).getName());
    }
}
