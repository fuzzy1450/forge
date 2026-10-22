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
import forge.game.Game;
import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.phase.PhaseType;
import forge.game.player.Player;

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
}
