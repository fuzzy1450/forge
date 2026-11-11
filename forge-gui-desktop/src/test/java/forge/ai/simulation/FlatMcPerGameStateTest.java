package forge.ai.simulation;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNotEquals;
import static org.testng.Assert.assertTrue;

import java.util.List;
import java.util.concurrent.TimeUnit;

import org.testng.annotations.Test;

import forge.ai.AITest;
import forge.game.Game;
import forge.util.AITimeoutTracker;
import forge.util.SimScope;

/**
 * What the games of one JVM share is kept per game (MTG_DeckMaker's tools/combo-ai/flat-mc/SPEC.md, sections 4.3 and
 * 4.7). Every game of a serve JVM takes its ids from its own scope, so games played side by side are all game 1, with
 * the same player ids: AITimeoutTracker keeps the sessions of a game played under a scope in that scope's own map, and
 * with no scope bound, the GUI's case, in the JVM-wide map as before. DoomsdayPile's JVM-wide answer cache keys a pool
 * by its names, not by their 32-bit hash. And a game that may not use an AI timeout, every harness game and so every
 * copy of one, reports none, so the 2 s the picker gives a rollout's copy limit nothing.
 */
public class FlatMcPerGameStateTest extends AITest {
    /**
     * Two scopes, one key: the case of two games of a serve JVM, timing-free. A session one scope begins is that
     * scope's alone, its deadline included, which is what one game's rollouts would otherwise spend of another's.
     */
    @Test
    public void twoScopesWithOneGameAndPlayerIdKeepTwoSessions() {
        final long k = AITimeoutTracker.key(1, 0);
        final long fallback = TimeUnit.SECONDS.toNanos(1);
        final SimScope a = new SimScope(7L);
        final SimScope b = new SimScope(8L);
        Scoped.under(a, () -> {
            AITimeoutTracker.begin(k, TimeUnit.SECONDS.toNanos(60));
            return null;
        });
        try {
            assertFalse(Scoped.under(b, () -> AITimeoutTracker.isActive(k)), "scope B sees no session of scope A's");
            final long leftInB = Scoped.under(b, () -> AITimeoutTracker.deadlineNanos(k, fallback) - System.nanoTime());
            assertTrue(leftInB <= fallback, "scope B's deadline is its own fallback, not A's session's: " + leftInB);
            assertTrue(Scoped.under(a, () -> AITimeoutTracker.isActive(k)), "scope A keeps its session");
            final long leftInA = Scoped.under(a, () -> AITimeoutTracker.deadlineNanos(k, fallback) - System.nanoTime());
            assertTrue(leftInA > fallback, "and its session's deadline: " + leftInA);
            assertFalse(AITimeoutTracker.isActive(k), "the shared map holds neither");
        } finally {
            Scoped.under(a, () -> {
                AITimeoutTracker.end(k);
                return null;
            });
        }
        assertFalse(Scoped.under(a, () -> AITimeoutTracker.isActive(k)), "the session ends in scope A's map");
    }

    @Test
    public void unboundSessionsStayInTheSharedMap() {
        final long k = AITimeoutTracker.key(9, 1);
        AITimeoutTracker.begin(k, TimeUnit.SECONDS.toNanos(60));
        try {
            assertTrue(AITimeoutTracker.isActive(k), "unbound, as in the GUI, a session is kept as before");
        } finally {
            AITimeoutTracker.end(k);
        }
        assertFalse(AITimeoutTracker.isActive(k), "and ends there");
    }

    /**
     * The picker lowers a rollout's copy to 2 s (FlatMonteCarloPicker.ROLLOUT_AI_TIMEOUT_SECONDS), and the copy carries
     * the game's AI_CAN_USE_TIMEOUT, false in every harness game: such a copy reports no timeout, so no AI session in
     * it is cut by the machine's clock. A game that may use one, the GUI's, reports its own.
     */
    @Test
    public void aCopyOfAHarnessGameReportsNoAiTimeout() {
        final Game g = initAndCreateGame();
        g.AI_CAN_USE_TIMEOUT = false;
        g.AI_TIMEOUT = 2;
        assertEquals(g.getAITimeout(), Integer.MAX_VALUE, "a game that may not use an AI timeout reports none");
        final Game copy = new GameCopier(g).makeCopy();
        assertEquals(copy.AI_TIMEOUT, 2, "the copy carries the 2 s");
        assertEquals(copy.getAITimeout(), Integer.MAX_VALUE, "and, like the game it copies, reports no AI timeout");
        g.AI_CAN_USE_TIMEOUT = true;
        assertEquals(g.getAITimeout(), 2, "a game that may use one reports its own");
    }

    @Test
    public void thePileCacheKeysOnTheNamesNotTheirHash() {
        assertEquals(List.of("Aa").hashCode(), List.of("BB").hashCode(), "the two pools' names collide in hashCode");
        assertNotEquals(Reflect.callStatic(DoomsdayPile.class, "poolKey", List.of("Aa")),
                Reflect.callStatic(DoomsdayPile.class, "poolKey", List.of("BB")));
    }
}
