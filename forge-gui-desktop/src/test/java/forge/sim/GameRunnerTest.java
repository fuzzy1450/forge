package forge.sim;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import org.testng.Assert;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import forge.sim.GameRunner.GameResult;
import forge.sim.GameRunner.GameSpec;
import forge.sim.GameRunner.SeatSpec;
import forge.util.SimScope;

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
        Assert.assertFalse(runner.isPoisoned());
        Assert.assertEquals(runner.exitCodeAfterDrain(), 0);
    }

    @Test(timeOut = 120_000)
    public void timeoutIsCooperativeAndLeavesNoThread() throws Exception {
        GameRunner runner = new GameRunner(1);
        GameResult r = runner.play(spec(7_000_001L, 1));      // a Commander game never ends inside one second
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
}
