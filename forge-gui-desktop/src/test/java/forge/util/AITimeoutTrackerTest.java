package forge.util;

import org.testng.Assert;
import org.testng.annotations.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public class AITimeoutTrackerTest {
    private static final long SEC = TimeUnit.SECONDS.toNanos(1);
    /** The AI deadline a game of the sim runner arms: GameRunner sets AI_TIMEOUT to Integer.MAX_VALUE seconds. */
    private static final long HARNESS_DEADLINE_NANOS = TimeUnit.SECONDS.toNanos(Integer.MAX_VALUE);

    @Test
    public void checkIsNoOpWhenNotArmed() {
        AITimeoutTracker.check();
        Assert.assertEquals(AITimeoutTracker.remainingNanos(), Long.MAX_VALUE);
    }

    @Test(expectedExceptions = AITimeoutTracker.Expired.class)
    public void checkThrowsAfterDeadline() {
        final Object t = AITimeoutTracker.arm(System.nanoTime() - 1, false);
        try {
            AITimeoutTracker.check();
        } finally {
            AITimeoutTracker.disarm(t);
        }
    }

    @Test
    public void disarmRestoresPreviousState() {
        final Object t = AITimeoutTracker.arm(System.nanoTime() - 1, false);
        AITimeoutTracker.disarm(t);
        AITimeoutTracker.check(); // must not throw
    }

    @Test
    public void nestedArmCanOnlyTighten() {
        final long now = System.nanoTime();
        final Object outer = AITimeoutTracker.arm(now + 10 * SEC, false);
        final Object inner = AITimeoutTracker.arm(now + 60 * SEC, false);
        try {
            Assert.assertTrue(AITimeoutTracker.remainingNanos() <= 10 * SEC);
        } finally {
            AITimeoutTracker.disarm(inner);
            AITimeoutTracker.disarm(outer);
        }
        Assert.assertEquals(AITimeoutTracker.remainingNanos(), Long.MAX_VALUE);
    }

    @Test
    public void interruptIsHonouredOnlyWhenRequested() {
        final Object t = AITimeoutTracker.arm(System.nanoTime() + 10 * SEC, true);
        Thread.currentThread().interrupt();
        try {
            AITimeoutTracker.check();
            Assert.fail("expected Expired");
        } catch (AITimeoutTracker.Expired expected) {
            // ok
        } finally {
            Thread.interrupted(); // clear the flag for other tests
            AITimeoutTracker.disarm(t);
        }
    }

    @Test
    public void unarmedCallerGetsABoundedWait() {
        Assert.assertTrue(AITimeoutTracker.remainingNanos(910L, 5 * SEC) <= 5 * SEC);
    }

    @Test
    public void barIsHiddenBeforeShowThresholdAndAfterEnd() {
        AITimeoutTracker.begin(911, 10 * SEC);
        Assert.assertTrue(AITimeoutTracker.isActive(911));
        Assert.assertTrue(AITimeoutTracker.getRemainingFraction(911) < 0f);
        AITimeoutTracker.end(911);
        Assert.assertFalse(AITimeoutTracker.isActive(911));
    }

    @Test
    public void leakedSessionDoesNotPoisonNextOne() throws Exception {
        final long old = AITimeoutTracker.staleGraceNanos;
        AITimeoutTracker.staleGraceNanos = TimeUnit.MILLISECONDS.toNanos(50);
        try {
            AITimeoutTracker.begin(912, TimeUnit.MILLISECONDS.toNanos(10)); // never ended
            Thread.sleep(150);
            AITimeoutTracker.begin(912, 5 * SEC);
            try {
                Assert.assertTrue(AITimeoutTracker.deadlineNanos(912, 0) - System.nanoTime() > 4 * SEC);
            } finally {
                AITimeoutTracker.end(912);
            }
        } finally {
            AITimeoutTracker.staleGraceNanos = old;
        }
    }

    @Test
    public void workerThreadStopsAtDeadline() throws Exception {
        final long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(100);
        final boolean[] stopped = {false};
        final Thread w = new Thread(() -> {
            final Object t = AITimeoutTracker.arm(deadline, true);
            try {
                while (true) { AITimeoutTracker.check(); }
            } catch (AITimeoutTracker.Expired e) {
                stopped[0] = true;
            } finally {
                AITimeoutTracker.disarm(t);
            }
        });
        w.start();
        w.join(2000);
        Assert.assertTrue(stopped[0], "busy loop with check() must abort at the deadline");
    }

    /** Armed or not: a check() on a thread that armed no deadline must stop an abandoned game too. */
    @Test
    public void checkThrowsGameAbandonedOnceTheBoundScopeIsCancelled() {
        final SimScope scope = new SimScope(1L);
        SimScope.enter(scope);
        try {
            AITimeoutTracker.check(); // a game in play: nothing stops it
            scope.cancel();           // the runner abandons the game at its time limit
            Assert.assertThrows(GameAbandoned.class, AITimeoutTracker::check);
        } finally {
            SimScope.exit();
        }
    }

    /** Expired is read by the AI as "use what you have" and the game plays on; an abandoned game must unwind. */
    @Test
    public void anAbandonedGameIsNotReadAsAnExpiredDeadline() {
        final SimScope scope = new SimScope(1L);
        scope.cancel();
        SimScope.enter(scope);
        final Object t = AITimeoutTracker.arm(System.nanoTime() - 1, false); // the deadline has passed too
        try {
            Assert.assertThrows(GameAbandoned.class, AITimeoutTracker::check);
        } finally {
            AITimeoutTracker.disarm(t);
            SimScope.exit();
        }
    }

    /**
     * How AiController runs a decision: on a "Game AI Eval" thread adopted from the game's scope, armed with the
     * game's deadline and calling check() in its loops. Under the sim runner the deadline is 2^31-1 s away and the
     * runner interrupts nothing, so only the cancel can end such a loop; it must do so at the loop's next check().
     */
    @Test
    public void anAdoptedEvaluationEndsAtItsNextCheckOnceItsGameIsCancelled() throws Exception {
        final SimScope scope = new SimScope(2L);
        final CountDownLatch looping = new CountDownLatch(1);
        final AtomicBoolean giveUp = new AtomicBoolean(false);
        final AtomicReference<Throwable> ended = new AtomicReference<>();
        final Runnable evaluation;
        SimScope.enter(scope);
        try {
            evaluation = SimScope.adopting(() -> {
                final Object t = AITimeoutTracker.arm(System.nanoTime() + HARNESS_DEADLINE_NANOS, true);
                try {
                    looping.countDown();
                    while (!giveUp.get()) {
                        AITimeoutTracker.check();
                    }
                } catch (Throwable e) {
                    ended.set(e);
                } finally {
                    AITimeoutTracker.disarm(t);
                }
            });
        } finally {
            SimScope.exit();
        }
        final Thread w = new Thread(evaluation, "Game AI Eval test");
        w.setDaemon(true);
        w.start();
        try {
            Assert.assertTrue(looping.await(10, TimeUnit.SECONDS), "the evaluation started");
            w.join(200);
            Assert.assertTrue(w.isAlive(), "while its game is in play the evaluation runs on");
            scope.cancel();
            w.join(2000);
            Assert.assertFalse(w.isAlive(), "the evaluation ended at its next check() after the cancel");
            Assert.assertTrue(ended.get() instanceof GameAbandoned, "it ended abandoned: " + ended.get());
        } finally {
            giveUp.set(true); // a failed run must not leave the loop spinning
            w.join(2000);
        }
    }

    @Test
    public void sessionsOfDifferentGamesDoNotShareDeadlinesOrBars() {
        // every game numbers its players from 0: same player id, different game
        final long a = AITimeoutTracker.key(7001, 0);
        final long b = AITimeoutTracker.key(7002, 0);
        Assert.assertNotEquals(a, b);
        AITimeoutTracker.begin(a, 10 * SEC);
        try {
            Assert.assertTrue(AITimeoutTracker.isActive(a));
            Assert.assertFalse(AITimeoutTracker.isActive(b), "a session in game 1 must not be visible in game 2");
            Assert.assertTrue(AITimeoutTracker.deadlineNanos(b, 1 * SEC) - System.nanoTime() <= 1 * SEC,
                    "game 2 must get its own fallback deadline, not game 1's");
        } finally {
            AITimeoutTracker.end(a);
        }
    }

    @Test
    public void idleSlotsArePurgedAndKeysStayUsable() {
        for (int g = 0; g < 500; g++) { // many finished games
            final long k = AITimeoutTracker.key(8000 + g, 1);
            AITimeoutTracker.begin(k, 5 * SEC);
            AITimeoutTracker.end(k);
        }
        final long live = AITimeoutTracker.key(9000, 1);
        AITimeoutTracker.begin(live, 5 * SEC);
        try {
            Assert.assertTrue(AITimeoutTracker.isActive(live));
        } finally {
            AITimeoutTracker.end(live);
        }
    }
}
