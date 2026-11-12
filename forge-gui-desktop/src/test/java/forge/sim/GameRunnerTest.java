package forge.sim;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;

import org.testng.Assert;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import forge.game.GameStage;
import forge.game.phase.PhaseType;
import forge.game.zone.ZoneType;
import forge.sim.GameRunner.GameResult;
import forge.sim.GameRunner.GameSpec;
import forge.sim.GameRunner.SeatSpec;
import forge.util.AITimeoutTracker;
import forge.util.GameAbandoned;
import forge.util.SimScope;
import forge.util.UnscopedAccessError;

public class GameRunnerTest {
    static final Set<String> DECIDED = Set.of("AllOpponentsLost", "WinsGameSpellEffect", "Draw");

    @BeforeClass
    public void boot() {
        GameRunner.boot();
    }

    @AfterClass
    public void relaxStrict() {
        SimScope.setStrict(false);
    }

    static GameSpec spec(long seed, int timeoutSeconds) {
        List<Path> precons = Precons.commander();
        Assert.assertTrue(precons.size() >= 2, "Forge ships Commander precons; found " + precons);
        return new GameSpec(seed, timeoutSeconds, List.of(
                new SeatSpec(precons.get(0), "Default", "default"),
                new SeatSpec(precons.get(1), "Default", "default")));
    }

    /** A four-seat pod of the first four Commander precons. */
    static GameSpec pod(long seed, int timeoutSeconds) {
        List<Path> precons = Precons.commander();
        return new GameSpec(seed, timeoutSeconds, List.of(
                new SeatSpec(precons.get(0), "Default", "default"),
                new SeatSpec(precons.get(1), "Default", "default"),
                new SeatSpec(precons.get(2), "Default", "default"),
                new SeatSpec(precons.get(3), "Default", "default")));
    }

    /** Blocks until a game thread exists: the caller that started it is then inside play()'s wait for the game. */
    static void awaitGameThread() throws InterruptedException {
        long deadline = System.nanoTime() + 60_000_000_000L;
        while (System.nanoTime() < deadline) {
            for (Thread t : Thread.getAllStackTraces().keySet()) {
                if (t.getName().startsWith("Game-sim-")) {
                    return;
                }
            }
            Thread.sleep(10);
        }
        Assert.fail("no game thread started within 60 s");
    }

    @Test(timeOut = 600_000)
    public void sameSeedSameDigest() throws Exception {
        GameRunner runner = new GameRunner(1);
        GameResult a = runner.play(spec(7_000_000L, 300));
        GameResult b = runner.play(spec(7_000_000L, 300));
        Assert.assertTrue(DECIDED.contains(a.endReason()), a.toString());
        Assert.assertNull(a.error(), a.toString());
        Assert.assertEquals(a.digest().length(), 64);
        Assert.assertTrue(a.digestLines().size() > 10, "the digest covers the game log and the zones");
        Assert.assertEquals(b.digest(), a.digest(), "the same seed replays byte-identically in one JVM");
        Assert.assertEquals(b.digestLines(), a.digestLines());
        Assert.assertEquals(a.violations(), 0L, "strict mode refused nothing while the game played: " + a);
        Assert.assertEquals(b.violations(), 0L, "strict mode refused nothing while the game played: " + b);
        Assert.assertFalse(runner.isPoisoned());
        Assert.assertEquals(runner.exitCodeAfterDrain(), 0);
    }

    /** The count play() reports is the process-wide count's delta over the game, so a refusal anywhere while the game
     *  plays is in it: here a deliberate one on this thread, unbound under strict mode. */
    @Test(timeOut = 600_000)
    public void aStrictRefusalDuringAPlayIsInItsViolations() throws Exception {
        GameRunner runner = new GameRunner(1);
        AtomicReference<GameResult> result = new AtomicReference<>();
        AtomicReference<Throwable> thrown = new AtomicReference<>();
        Thread caller = new Thread(() -> {
            try {
                result.set(runner.play(spec(7_000_000L, 300)));
            } catch (Throwable t) {
                thrown.set(t);
            }
        }, "play-caller");
        caller.start();
        awaitGameThread();                                    // the game is under way: its count started before its thread
        try {
            SimScope.requireUnboundAllowed("GameRunnerTest.deliberate");
            Assert.fail("strict mode is on and this thread has no scope: the access must be refused");
        } catch (UnscopedAccessError expected) {
            // refused, and counted before it was thrown
        }
        caller.join(300_000);
        Assert.assertFalse(caller.isAlive(), "the play returned");
        Assert.assertNull(thrown.get(), String.valueOf(thrown.get()));
        Assert.assertTrue(result.get().violations() >= 1, "the refusal while the game played is in its count, which is "
                + result.get().violations() + " (" + result.get().endReason() + " after " + result.get().ms() + " ms)");
    }

    @Test(timeOut = 120_000)
    public void timeoutIsCooperativeAndLeavesNoThread() throws Exception {
        GameRunner runner = new GameRunner(1);
        GameResult r = runner.play(pod(7_000_001L, 1));       // a four-seat pod runs well over ten seconds, so the one-second timeout always fires
        Assert.assertTrue(r.timedOut(), r.toString());
        Assert.assertEquals(r.endReason(), "Timeout");
        Assert.assertNull(r.winnerSeat());
        Assert.assertEquals(r.digest(), "TIMEOUT");
        Assert.assertFalse(runner.isPoisoned(), "the game thread unwound inside the grace period");
        for (Thread t : Thread.getAllStackTraces().keySet()) {
            Assert.assertFalse(t.getName().startsWith("Game-sim-"), "game thread still alive: " + t.getName());
        }
    }

    /**
     * The shape of the stall that poisoned pods-p58-s2. The game thread waits on an evaluation thread adopted from its
     * scope, as AiController's does, and the evaluation loops through AITimeoutTracker.check() under the runner's AI
     * deadline (2^31-1 s away), drawing no random number and asking for no id. The cancel at the game's limit must end
     * it at its next check(): the game reads as a Timeout soon after its limit, and the runner is not poisoned.
     */
    @Test(timeOut = 120_000)
    public void aStalledEvaluationEndsAtItsNextCheckAfterTheCancelAndPoisonsNothing() throws Exception {
        GameRunner runner = new GameRunner(1);
        AtomicBoolean giveUp = new AtomicBoolean(false);
        AtomicReference<Thread> evalThread = new AtomicReference<>();
        AtomicReference<Throwable> evaluationEnded = new AtomicReference<>();
        GameResult r;
        try {
            r = runner.play(spec(7_000_003L, 10), g -> {
                FutureTask<Void> evaluation = new FutureTask<>(() -> {
                    Object limit = AITimeoutTracker.arm(System.nanoTime() + TimeUnit.SECONDS.toNanos(Integer.MAX_VALUE), true);
                    try {
                        while (!giveUp.get()) {
                            AITimeoutTracker.check();
                            LockSupport.parkNanos(1_000_000L);      // a stall, not a busy core: 1 ms between checks
                        }
                        return null;
                    } finally {
                        AITimeoutTracker.disarm(limit);
                    }
                });
                Thread eval = new Thread(SimScope.adopting(evaluation), "Game AI Eval");
                eval.setDaemon(true);
                evalThread.set(eval);
                eval.start();
                try {
                    evaluation.get();
                } catch (ExecutionException e) {
                    evaluationEnded.set(e.getCause());
                    if (e.getCause() instanceof Error err) {
                        throw err;                                    // as AiController rethrows its evaluation's Error
                    }
                    throw new IllegalStateException(e);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
            });
        } finally {
            giveUp.set(true);                                         // a failed run must not leave the loop spinning
        }
        Assert.assertTrue(r.timedOut(), r.toString());
        Assert.assertEquals(r.endReason(), "Timeout", r.toString());
        Assert.assertTrue(evaluationEnded.get() instanceof GameAbandoned,
                "the evaluation was abandoned at a check(): " + evaluationEnded.get());
        Assert.assertFalse(runner.isPoisoned(), "the game thread unwound inside the grace period");
        Assert.assertTrue(r.ms() < 10_000 + 5_000, "the game ended within 5 s of its 10 s limit: " + r.ms() + " ms");
        evalThread.get().join(10_000);
        Assert.assertFalse(evalThread.get().isAlive(), "the evaluation thread ended");
        for (Thread t : Thread.getAllStackTraces().keySet()) {
            Assert.assertFalse(t.getName().startsWith("Game-sim-"), "game thread still alive: " + t.getName());
        }
    }

    /**
     * A stall the cancel cannot reach outlives the grace and poisons the runner. Here the game thread waits on an
     * evaluation thread adopted from its scope, and that thread waits on a latch, which draws nothing and calls no
     * check(). The runner's report on stderr names both threads with their states and stacks, so a poisoned log says
     * where the game stalled. Its last line holds their innermost frames, for a reader that keeps only stderr's tail.
     * Takes the 10 s limit plus the 15 s grace.
     */
    @Test(timeOut = 180_000)
    public void aGameThatOutlivesItsGraceIsPoisonedAndItsStuckThreadsAreReported() throws Exception {
        GameRunner runner = new GameRunner(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<Thread> evalThread = new AtomicReference<>();
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        PrintStream savedErr = System.err;
        GameResult r;
        System.setErr(new PrintStream(captured, true, StandardCharsets.UTF_8));
        try {
            r = runner.play(spec(7_000_004L, 10), g -> {
                Thread eval = new Thread(SimScope.adopting(() -> awaitQuietly(release)), "Game AI Eval");
                eval.setDaemon(true);
                evalThread.set(eval);
                eval.start();
                try {
                    eval.join();                                      // as the game thread waits on its evaluation
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        } finally {
            System.setErr(savedErr);
            release.countDown();                                      // the stall ends; the released game unwinds at its next draw
        }
        String report = captured.toString(StandardCharsets.UTF_8);
        Assert.assertTrue(r.timedOut(), r.toString());
        Assert.assertTrue(runner.isPoisoned(), "the game thread outlived its grace");
        Assert.assertEquals(runner.exitCodeAfterDrain(), 5);
        Assert.assertTrue(report.contains("GameRunner: the game with seed 7000004 outlived its 15000 ms grace"), report);
        Assert.assertTrue(report.contains("\"Game-sim-0\" WAITING\n"), "the game thread, by name and state: " + report);
        Assert.assertTrue(report.contains("\"Game AI Eval\" WAITING\n"),
                "the evaluation thread adopted from its scope: " + report);
        Assert.assertTrue(report.contains("\tat forge.sim.GameRunnerTest.awaitQuietly("),
                "with the frames it stalled in: " + report);
        Assert.assertTrue(report.contains("GameRunner: stall of seed 7000004: \"Game-sim-0\" WAITING at "),
                "and a last line of innermost frames: " + report);
        evalThread.get().join(30_000);
        Assert.assertFalse(evalThread.get().isAlive(), "released, the evaluation thread ended");
        awaitNoGameThread();
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Waits up to 60 s for every game thread to end, then fails naming one that did not. */
    static void awaitNoGameThread() throws InterruptedException {
        long deadline = System.nanoTime() + 60_000_000_000L;
        while (true) {
            String alive = null;
            for (Thread t : Thread.getAllStackTraces().keySet()) {
                if (t.getName().startsWith("Game-sim-")) {
                    alive = t.getName();
                }
            }
            if (alive == null) {
                return;
            }
            if (System.nanoTime() > deadline) {
                Assert.fail("game thread still alive: " + alive);
            }
            Thread.sleep(10);
        }
    }

    @Test(timeOut = 120_000)
    public void interruptedCallerCancelsTheGameAndKeepsTheSlot() throws Exception {
        GameRunner runner = new GameRunner(1);
        GameSpec toBeInterrupted = spec(7_000_002L, 300);
        AtomicReference<Throwable> thrown = new AtomicReference<>();
        Thread caller = new Thread(() -> {
            try {
                runner.play(toBeInterrupted);
            } catch (Throwable t) {
                thrown.set(t);
            }
        }, "play-caller");
        caller.start();
        awaitGameThread();                                    // the caller is now inside play()'s wait for the game
        Thread.sleep(500);
        caller.interrupt();
        caller.join(60_000);
        Assert.assertFalse(caller.isAlive(), "an interrupted play() returns");
        Assert.assertTrue(thrown.get() instanceof InterruptedException, "play() reports the interrupt: " + thrown.get());
        Assert.assertFalse(runner.isPoisoned(), "the game thread unwound inside the grace period");
        for (Thread t : Thread.getAllStackTraces().keySet()) {
            Assert.assertFalse(t.getName().startsWith("Game-sim-"), "game thread still alive: " + t.getName());
        }
        GameResult r = runner.play(spec(7_000_000L, 300));     // the one slot came back
        Assert.assertTrue(DECIDED.contains(r.endReason()), r.toString());
        Assert.assertNull(r.error(), r.toString());
    }

    @Test
    public void haltHandlerIsReassertedByBoot() {
        Thread.UncaughtExceptionHandler savedDefault = Thread.getDefaultUncaughtExceptionHandler();
        Thread.UncaughtExceptionHandler savedThread = Thread.currentThread().getUncaughtExceptionHandler();
        try {
            GameRunner.installHaltOnUncaught();
            Assert.assertSame(Thread.getDefaultUncaughtExceptionHandler(), GameRunner.HALT_ON_UNCAUGHT);
            Thread.setDefaultUncaughtExceptionHandler((t, e) -> { });    // what Forge's boot does to a handler installed before it
            GameRunner.boot();
            Assert.assertSame(Thread.getDefaultUncaughtExceptionHandler(), GameRunner.HALT_ON_UNCAUGHT,
                    "boot re-asserts the halt handler");
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(savedDefault);       // no other test class in this JVM runs under a halting handler
            Thread.currentThread().setUncaughtExceptionHandler(savedThread);
            GameRunner.haltInstalled = false;
        }
    }

    @Test(timeOut = 120_000)
    public void refusesABadSpecBeforeTakingASlot() throws Exception {
        GameRunner runner = new GameRunner(1);
        Assert.assertThrows(IllegalArgumentException.class,
                () -> runner.play(new GameSpec(1L, 0, spec(1L, 300).seats())));
        Assert.assertThrows(IllegalArgumentException.class,
                () -> runner.play(new GameSpec(1L, 300, List.of(spec(1L, 300).seats().get(0)))));
        Assert.assertThrows(IllegalArgumentException.class, () -> new GameRunner(0));
        Assert.assertThrows(IllegalArgumentException.class,
                () -> runner.play(new GameSpec(1L, 300, List.of(
                        new SeatSpec(Paths.get("no-such-deck.dck"), "Default", "default"),
                        spec(1L, 300).seats().get(1)))));
        Assert.assertThrows(NullPointerException.class, () -> runner.play(spec(1L, 300), null));
        GameResult r = runner.play(spec(7_000_000L, 300));        // the refusals left the one slot free
        Assert.assertTrue(DECIDED.contains(r.endReason()), r.toString());
        Assert.assertNull(r.error(), r.toString());
    }

    @Test(timeOut = 300_000)
    public void anObserverSeesTheGameBeforeItStartsOnTheGameThreadUnderItsScope() throws Exception {
        GameRunner runner = new GameRunner(1);
        GameSpec spec = spec(7_000_000L, 300);
        AtomicReference<String> thread = new AtomicReference<>();
        AtomicBoolean underScope = new AtomicBoolean(false);
        AtomicBoolean overBeforeStart = new AtomicBoolean(true);
        AtomicBoolean sawStart = new AtomicBoolean(false);
        Object startWatcher = new Object() {
            @com.google.common.eventbus.Subscribe
            public void onStarted(forge.game.event.GameEventGameStarted ev) {
                sawStart.set(true);
            }
        };
        GameResult observed = runner.play(spec, g -> {
            thread.set(Thread.currentThread().getName());
            underScope.set(SimScope.current() != null);
            overBeforeStart.set(g.isGameOver());
            g.subscribeToEvents(startWatcher);
        });
        Assert.assertTrue(thread.get().startsWith("Game-sim-"), thread.get());
        Assert.assertTrue(underScope.get(), "the observer runs under the game's scope");
        Assert.assertFalse(overBeforeStart.get(), "the observer runs before the game starts");
        Assert.assertTrue(sawStart.get(), "a subscriber attached by the observer sees GameEventGameStarted");
        Assert.assertTrue(DECIDED.contains(observed.endReason()), observed.toString());
        Assert.assertNull(observed.error(), observed.toString());
        GameResult plain = runner.play(spec);
        Assert.assertEquals(observed.digest(), plain.digest(), "an observer that only listens changes no digest");
    }

    @Test(timeOut = 300_000)
    public void aThrowingObserverEndsItsGameAsAnErrorAndNothingMore() throws Exception {
        GameRunner runner = new GameRunner(1);
        GameResult r = runner.play(spec(7_000_000L, 300), g -> {
            throw new IllegalStateException("boom");
        });
        Assert.assertEquals(r.endReason(), "Error", r.toString());
        Assert.assertTrue(r.error() != null && r.error().contains("boom"), r.toString());
        Assert.assertFalse(runner.isPoisoned(), "a throwing observer does not poison the runner");
        GameResult next = runner.play(spec(7_000_000L, 300));     // the same runner: its one slot came back
        Assert.assertTrue(DECIDED.contains(next.endReason()), next.toString());
        Assert.assertNull(next.error(), next.toString());
    }

    /**
     * Upstream's Flat Monte Carlo search (#12308) decides on wall-clock budgets (FLAT_MC_DECISION_BUDGET_MS,
     * FLAT_MC_TURN_BUDGET_MS) and only the GUI's HostedMatch and the SimulateMatch CLI switch it on: in a game the runner
     * plays it is off on the game and for every seat, so a seed follows its own stream, never the machine's speed. The
     * observer throws once it has looked, so no game is played.
     */
    @Test(timeOut = 300_000)
    public void flatMonteCarloIsOffOnTheGameAndForEverySeat() throws Exception {
        GameRunner runner = new GameRunner(1);
        AtomicReference<String> seen = new AtomicReference<>();
        GameResult r = runner.play(spec(7_000_000L, 300), g -> {
            StringBuilder sb = new StringBuilder();
            sb.append(g.getAIFlatMonteCarlo()).append(',').append(g.getAIFlatMonteCarloMax()).append(',')
                    .append(g.getAIFlatMonteCarloLite());
            for (forge.game.player.Player p : g.getPlayers()) {
                sb.append(',').append(((forge.ai.PlayerControllerAi) p.getController()).getAi().flatMonteCarloActive());
            }
            seen.set(sb.toString());
            throw new IllegalStateException("looked");
        });
        Assert.assertEquals(seen.get(), "false,false,false,false,false", r.toString());
        Assert.assertEquals(r.endReason(), "Error", r.toString());
    }

    /**
     * The stop the plan verb stands on (F5 guard spec 3.1): the hook runs once, on the game thread under the game's scope,
     * with the mulligans done and turn 1 begun, before any priority, and the game ends there. It reads as Error, the
     * verdict of a game that did not finish, and the runner is none the worse: no thread is left, the slot came back, and
     * the next game of the same runner plays what a fresh runner plays.
     */
    @Test(timeOut = 300_000)
    public void theFirstTurnHookStopsTheGameAndLeavesNoThread() throws Exception {
        GameRunner runner = new GameRunner(1);
        AtomicInteger calls = new AtomicInteger();
        AtomicReference<String> thread = new AtomicReference<>();
        AtomicBoolean underScope = new AtomicBoolean(false);
        AtomicReference<String> where = new AtomicReference<>();
        AtomicInteger inHands = new AtomicInteger();
        GameResult stopped = runner.play(spec(7_000_000L, 300), g -> { }, g -> {
            calls.incrementAndGet();
            thread.set(Thread.currentThread().getName());
            underScope.set(SimScope.current() != null);
            where.set(g.getAge() + " turn " + g.getPhaseHandler().getTurn() + " " + g.getPhaseHandler().getPhase()
                    + " stack " + g.getStack().size());
            for (forge.game.player.Player p : g.getRegisteredPlayers()) {
                inHands.addAndGet(p.getCardsIn(ZoneType.Hand).size());
            }
        });
        Assert.assertEquals(calls.get(), 1, "the hook ran once");
        Assert.assertTrue(thread.get().startsWith("Game-sim-"), thread.get());
        Assert.assertTrue(underScope.get(), "the hook runs under the game's scope");
        Assert.assertEquals(where.get(), GameStage.Play + " turn 1 " + PhaseType.UNTAP + " stack 0",
                "the mulligans are done, turn 1 has begun and nobody has had priority");
        Assert.assertTrue(inHands.get() > 0, "the hands were dealt: " + inHands.get());
        Assert.assertEquals(stopped.endReason(), "Error", stopped.toString());
        Assert.assertEquals(stopped.error(), "game ended without finishing", stopped.toString());
        Assert.assertFalse(stopped.timedOut(), stopped.toString());
        Assert.assertEquals(stopped.turns(), 1, "the game stopped on the turn it began: " + stopped);
        Assert.assertEquals(stopped.violations(), 0L, stopped.toString());
        Assert.assertFalse(runner.isPoisoned());
        for (Thread t : Thread.getAllStackTraces().keySet()) {
            Assert.assertFalse(t.getName().startsWith("Game-sim-"), "game thread still alive: " + t.getName());
        }
        GameSpec another = spec(7_000_001L, 300);
        GameResult next = runner.play(another);                    // the same runner: its one slot came back
        GameResult fresh = new GameRunner(1).play(another);
        Assert.assertTrue(DECIDED.contains(next.endReason()), next.toString());
        Assert.assertEquals(next.digest(), fresh.digest(), "the stopped game left nothing the next game reads");
        Assert.assertEquals(next.digestLines(), fresh.digestLines());
    }

    /** With no hook a game keeps its path: play(spec) is play(spec, observer, null), and a null hook is what
     *  Match.startGame(game) passes on. */
    @Test(timeOut = 300_000)
    public void aGamesPathIsUnchanged() throws Exception {
        GameRunner runner = new GameRunner(1);
        GameSpec spec = spec(7_000_000L, 300);
        GameResult plain = runner.play(spec);
        GameResult noHook = runner.play(spec, g -> { }, null);
        Assert.assertTrue(DECIDED.contains(plain.endReason()), plain.toString());
        Assert.assertNull(plain.error(), plain.toString());
        Assert.assertTrue(plain.turns() > 1, "a whole game, not one stopped at its first turn: " + plain);
        Assert.assertEquals(noHook.digest(), plain.digest());
        Assert.assertEquals(noHook.digestLines(), plain.digestLines());
    }
}
