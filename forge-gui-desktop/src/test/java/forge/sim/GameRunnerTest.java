package forge.sim;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

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

    @Test
    public void refusesABadSpecBeforeTakingASlot() {
        GameRunner runner = new GameRunner(1);
        Assert.assertThrows(IllegalArgumentException.class,
                () -> runner.play(new GameSpec(1L, 0, spec(1L, 300).seats())));
        Assert.assertThrows(IllegalArgumentException.class,
                () -> runner.play(new GameSpec(1L, 300, List.of(spec(1L, 300).seats().get(0)))));
        Assert.assertThrows(IllegalArgumentException.class, () -> new GameRunner(0));
    }
}
