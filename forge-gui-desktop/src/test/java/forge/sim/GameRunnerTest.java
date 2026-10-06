package forge.sim;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.testng.Assert;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import forge.sim.GameRunner.GameResult;
import forge.sim.GameRunner.GameSpec;
import forge.sim.GameRunner.SeatSpec;
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
        List<Path> precons = Precons.commander();
        GameSpec pod = new GameSpec(7_000_001L, 1, List.of(
                new SeatSpec(precons.get(0), "Default", "default"),
                new SeatSpec(precons.get(1), "Default", "default"),
                new SeatSpec(precons.get(2), "Default", "default"),
                new SeatSpec(precons.get(3), "Default", "default")));
        GameResult r = runner.play(pod);                      // a four-seat pod runs well over ten seconds, so the one-second timeout always fires
        Assert.assertTrue(r.timedOut(), r.toString());
        Assert.assertEquals(r.endReason(), "Timeout");
        Assert.assertNull(r.winnerSeat());
        Assert.assertEquals(r.digest(), "TIMEOUT");
        Assert.assertFalse(runner.isPoisoned(), "the game thread unwound inside the grace period");
        for (Thread t : Thread.getAllStackTraces().keySet()) {
            Assert.assertFalse(t.getName().startsWith("Game-sim-"), "game thread still alive: " + t.getName());
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
        GameResult plain = runner.play(spec);
        Assert.assertEquals(observed.digest(), plain.digest(), "an observer that only listens changes no digest");
    }
}
