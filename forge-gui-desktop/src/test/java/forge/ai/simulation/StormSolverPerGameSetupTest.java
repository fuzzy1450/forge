package forge.ai.simulation;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertSame;
import static org.testng.Assert.assertTrue;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import org.testng.annotations.Test;

import forge.game.card.Card;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.zone.ZoneType;
import forge.util.SimScope;

/**
 * The storm solver's setup is its game's own (MTG_DeckMaker's tools/combo-ai/flat-mc/SHIP.md, the anti-goal: an engine
 * whose games differ by machine). A search that finds no kill this turn may leave the plays that make the next turn's
 * kill likelier ({@link StormSolver#lastSetup()}), and the solver plays the first of them at once. Every default seat
 * plays Flat MC, so a tutor storm seat runs the solver in harness games, and a serve JVM plays its games side by side on
 * threads of their own: with one JVM-wide setup, a game whose search found none could play the setup another game's
 * search had just found, or lose its own to another game's search, and a seed played one game beside other games and
 * another alone, as verify replays it. Under a scope a search keeps its setup in that scope; unbound, the GUI's case, in
 * the JVM-wide field as before. Each case binds its scopes in turn on one thread, timing-free, so the setup is shown to
 * follow the scope, not the thread.
 */
public class StormSolverPerGameSetupTest extends FlatMcTestBase {
    /** How long a test waits for its body before it fails, as {@link Scoped} waits. */
    private static final long JOIN_MS = 120_000;
    /** Far more than either position's search needs: no search here stops for time. */
    private static final long BUDGET_NANOS = 20_000_000_000L;

    /** Scope B's search finds no setup, then scope A's finds one: B, reading again, still has none. */
    @Test
    public void aGameDoesNotReadTheSetupAnotherGameFound() {
        final Player none = withNothingToSetUp();
        final Player manalith = withManalithToCast();
        final SimScope b = new SimScope(1L);
        final SimScope a = new SimScope(2L);
        onOneThread(() -> {
            assertNull(searchIn(b, none), "B's search finds no setup");
            final List<StormSolver.Step> found = searchIn(a, manalith);
            assertNotNull(found, "A's search finds one");
            assertEquals(found.get(0).host, "Manalith");
            assertNull(in(b, StormSolver::lastSetup), "B keeps its own, none, after A's search");
        });
    }

    /** Scope A's search finds a setup, then scope B's, which starts by clearing its own, finds none: A keeps its own. */
    @Test
    public void aGameKeepsItsSetupThroughAnotherGamesSearch() {
        final Player manalith = withManalithToCast();
        final Player none = withNothingToSetUp();
        final SimScope a = new SimScope(2L);
        final SimScope b = new SimScope(1L);
        onOneThread(() -> {
            final List<StormSolver.Step> found = searchIn(a, manalith);
            assertNotNull(found, "A's search finds a setup");
            assertNull(searchIn(b, none), "B's search finds none");
            assertSame(in(a, StormSolver::lastSetup), found, "A keeps its own after B's search");
        });
    }

    /** A game's next search starts from no setup, as every search did: once Manalith is gone, A's next leaves none. */
    @Test
    public void aGamesNextSearchReplacesItsOwnSetup() {
        final Player manalith = withManalithToCast();
        final SimScope a = new SimScope(2L);
        onOneThread(() -> {
            assertNotNull(searchIn(a, manalith), "the first search finds Manalith");
            final Card stone = manalith.getCardsIn(ZoneType.Hand).get(0);
            manalith.getZone(ZoneType.Hand).remove(stone);
            manalith.getZone(ZoneType.Graveyard).add(stone);
            assertNull(searchIn(a, manalith), "with nothing left to cast, the next search leaves none");
        });
    }

    /** Unbound, as in the GUI, the setup is the JVM's one field as before: the next search replaces it, whatever game. */
    @Test
    public void unboundTheSetupIsTheJvmsOneAsBefore() {
        final Player manalith = withManalithToCast();
        final Player none = withNothingToSetUp();
        onOneThread(() -> {
            final List<StormSolver.Step> found = search(manalith);
            assertNotNull(found, "an unbound search keeps its setup");
            assertEquals(found.get(0).host, "Manalith");
            assertNull(search(none), "and the next unbound search, of another game, replaces it");
        });
    }

    /**
     * Manalith and three Swamps: no kill, and Manalith in play is a setup, an artifact that makes mana and stays, worth
     * 35 to ComboPlan.setupScore against the 30 a setup must gain, and safe, since it spends nothing.
     */
    private Player withManalithToCast() {
        return stormSeatAtMainOne("Manalith", 3);
    }

    /** Tendrils of Agony and four Swamps: Tendrils is not lethal, so it is no play of the search, and nothing else is. */
    private Player withNothingToSetUp() {
        return stormSeatAtMainOne("Tendrils of Agony", 4);
    }

    /**
     * A fresh game's tutor storm seat at its first main phase, with {@code inHand} in its hand and {@code swamps} Swamps
     * in play, its opponent at 20 and the AI deadline off: nothing wins this turn. The game is made unbound, as the fork's
     * other scoped tests make theirs; its searches run under the scopes the tests bind.
     */
    private Player stormSeatAtMainOne(final String inHand, final int swamps) {
        setUpGame();
        noDeadline();
        addTutorStormDeck();
        put(inHand, ai, ZoneType.Hand);
        put("Swamp", swamps, ai, ZoneType.Battlefield);
        opp.setLife(20, null);
        at(PhaseType.MAIN1, ai);
        game.getAction().checkStateEffects(true);
        return ai;
    }

    /** One search of {@code p}'s position with {@code scope} bound, and the setup it leaves there. */
    private static List<StormSolver.Step> searchIn(final SimScope scope, final Player p) {
        return in(scope, () -> search(p));
    }

    /** One search of {@code p}'s position on this thread, bound as it is, and the setup lastSetup reads after it. */
    private static List<StormSolver.Step> search(final Player p) {
        final ComboPlan combos = DeckPlan.forSetting("auto", p).combos;
        assertTrue(combos.has(ComboPlan.Kind.TUTOR_STORM), "the library reads as tutor storm: " + combos.summary());
        assertNull(StormSolver.solve(p, combos, 50, BUDGET_NANOS), "no line wins this turn");
        return StormSolver.lastSetup();
    }

    /** What {@code body} returns with {@code scope} bound on this thread, which has none bound before or after it. */
    private static <T> T in(final SimScope scope, final Supplier<T> body) {
        SimScope.enter(scope);
        try {
            return body.get();
        } finally {
            SimScope.exit();
        }
    }

    /**
     * Runs {@code body} on one fresh thread named as a game thread, as {@link Scoped} runs one, but with no scope bound:
     * the body binds its scopes itself, in turn, so they share the thread. What the body throws is rethrown here.
     */
    private static void onOneThread(final Runnable body) {
        final AtomicReference<Throwable> thrown = new AtomicReference<>();
        final Thread t = new Thread(() -> {
            try {
                body.run();
            } catch (final Throwable e) {
                thrown.set(e);
            }
        }, "Game-storm-setup-test");
        t.setDaemon(true);
        t.start();
        try {
            t.join(JOIN_MS);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted while waiting for the body", e);
        }
        if (t.isAlive()) {
            throw new AssertionError("the body did not finish within " + JOIN_MS / 1000 + " s");
        }
        final Throwable e = thrown.get();
        if (e instanceof Error err) {
            throw err;
        }
        if (e instanceof RuntimeException re) {
            throw re;
        }
        if (e != null) {
            throw new RuntimeException(e);
        }
    }
}
