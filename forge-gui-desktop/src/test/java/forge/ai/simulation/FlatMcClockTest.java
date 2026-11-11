package forge.ai.simulation;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertTrue;

import java.util.List;
import java.util.concurrent.TimeUnit;

import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import forge.ai.simulation.FlatMonteCarloPicker.Config;
import forge.ai.simulation.FlatMonteCarloPicker.Result;
import forge.game.spellability.SpellAbility;
import forge.util.SimScope;

/**
 * The counted clock (MTG_DeckMaker's tools/combo-ai/flat-mc/SPEC.md, section 4.3). The picker reads its clock through
 * {@link FlatMcGame#nanoTime()}: under a bound scope that is the game's own clock, which only rollouts move, one virtual
 * millisecond each; unbound it is the machine's clock, as the GUI has always had it. The decision tests compare
 * {@link #twoBears()}'s two lines with no hold, no target variants and no pruning, four rounds at most, so how many
 * rollouts a decision makes, and so how far its clock moves, is known exactly.
 */
public class FlatMcClockTest extends FlatMcTestBase {
    private List<SpellAbility> two;
    private Config cfg;

    @BeforeMethod
    public void twoLines() {
        setUpGame();
        two = twoBears();
        assertEquals(two.size(), 2, String.valueOf(two));
        cfg = twoLineConfig();
    }

    @Test
    public void unboundTheClockIsTheMachinesClock() throws InterruptedException {
        final long a = FlatMcGame.nanoTime();
        Thread.sleep(20);
        assertTrue(FlatMcGame.nanoTime() - a >= 20_000_000L, "unbound, 20 ms of sleep are 20 ms on the clock");
    }

    @Test
    public void aBoundScopeCountsItsOwnClockFromZero() {
        final long[] t = Scoped.under(new SimScope(1L), () -> {
            final long a = FlatMcGame.nanoTime();
            Thread.sleep(20);
            final long b = FlatMcGame.nanoTime();
            FlatMcGame.current().charge(0);
            return new long[] { a, b, FlatMcGame.nanoTime() };
        });
        assertEquals(t, new long[] { 0L, 0L, 1_000_000L },
                "a fresh scope's clock reads 0, does not move while the thread sleeps, and one charge moves it 1 ms");
    }

    @Test
    public void twoScopesKeepTwoClocks() {
        final SimScope a = new SimScope(1L);
        final SimScope b = new SimScope(2L);
        Scoped.under(a, () -> charges(1));
        Scoped.under(b, () -> charges(1));
        Scoped.under(a, () -> charges(2));
        assertEquals(Scoped.under(a, FlatMcGame::nanoTime).longValue(), 3_000_000L, "A's clock: its own three charges");
        assertEquals(Scoped.under(b, FlatMcGame::nanoTime).longValue(), 1_000_000L, "B's clock: its one charge, none of A's");
        assertEquals(FlatMcGame.peek(a).clock(), 3_000_000L, "peek reads a scope's clock from a thread it is not bound to");
        final SimScope untouched = new SimScope(3L);
        assertNull(FlatMcGame.peek(untouched), "a scope nothing has charged or read has no state");
        assertNull(FlatMcGame.peek(untouched), "and peeking made none");
    }

    /** The default budget of 450 ms holds all four rounds of the two lines: eight rollouts, eight virtual milliseconds. */
    @Test
    public void aDecisionAdvancesTheClockOneMillisecondPerRollout() {
        assertEquals(cfg.budgetNanos, TimeUnit.MILLISECONDS.toNanos(450));
        final SimScope scope = new SimScope(2L);
        final Result r = Scoped.under(scope, () -> FlatMonteCarloPicker.choose(ai, two, cfg, null));
        assertEquals(r.rollouts, 8, String.valueOf(r));
        assertEquals(r.elapsedNanos, 8_000_000L, String.valueOf(r));
        assertEquals(FlatMcGame.peek(scope).clock(), 8_000_000L, "the decision ran on the scope's clock");
    }

    /**
     * A 2 ms budget puts the soft deadline at 2 ms and the hard one at 4 ms. Rounds 0 and 1, which may run to the hard
     * deadline, fit exactly; round 2's two rollouts would end at 6 ms, past the soft deadline, so it never starts.
     */
    @Test
    public void aTwoMillisecondBudgetStopsAfterFourRollouts() {
        cfg.budgetNanos = 2_000_000L;
        final Result r = Scoped.under(new SimScope(2L), () -> FlatMonteCarloPicker.choose(ai, two, cfg, null));
        assertEquals(r.rollouts, 4, String.valueOf(r));
        assertEquals(r.elapsedNanos, 4_000_000L, String.valueOf(r));
    }

    /**
     * The playout deadline is one static that every game in the JVM shares: under a bound scope its check returns at
     * once, a playout being bounded by its play count, while unbound a deadline in the past still throws.
     */
    @Test
    public void theSharedPlayoutDeadlineIsInertUnderAScope() {
        Reflect.setStatic(FlatMonteCarloPicker.class, "rolloutDeadlineNanos", Long.MIN_VALUE);
        try {
            final Throwable unbound = Reflect.thrownBy(FlatMonteCarloPicker.class, "checkRolloutDeadline");
            assertNotNull(unbound, "unbound, a deadline in the past throws");
            assertEquals(unbound.getClass().getSimpleName(), "RolloutTooSlow");
            Scoped.under(new SimScope(3L), () -> Reflect.callStatic(FlatMonteCarloPicker.class, "checkRolloutDeadline"));
        } finally {
            Reflect.setStatic(FlatMonteCarloPicker.class, "rolloutDeadlineNanos", Long.MAX_VALUE);
        }
    }

    /** {@code n} charges on the bound scope's clock. */
    private static Object charges(final int n) {
        for (int i = 0; i < n; i++) {
            FlatMcGame.current().charge(0);
        }
        return null;
    }
}
