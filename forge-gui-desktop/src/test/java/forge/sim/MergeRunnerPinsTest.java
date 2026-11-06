package forge.sim;

import java.util.concurrent.atomic.AtomicReference;

import org.testng.Assert;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import forge.sim.GameRunner.GameResult;
import forge.util.SimScope;

/**
 * GameRunner builds a game with no per-decision AI time budget (an abandoned evaluation has drawn from the seeded
 * stream already, so a game would follow the clock instead of its seed): the budget is pinned to Integer.MAX_VALUE
 * and the game says it may not use it. The pins are what upstream's AITimeoutTracker, keyed on the scope's game ids,
 * relies on. The observer throws once it has looked, so no game is played.
 */
public class MergeRunnerPinsTest {

    @BeforeClass
    public void boot() {
        GameRunner.boot();
    }

    @AfterClass
    public void relaxStrict() {
        SimScope.setStrict(false);
    }

    @Test(timeOut = 600_000)
    public void theRunnerPinsTheAiTimeoutAndForbidsUsingIt() throws Exception {
        final GameRunner runner = new GameRunner(1);
        final AtomicReference<String> seen = new AtomicReference<>();
        final GameResult r = runner.play(GameRunnerTest.spec(7_000_000L, 300), g -> {
            seen.set(g.getAITimeout() + "," + g.canUseTimeout());
            throw new IllegalStateException("looked");
        });
        Assert.assertEquals(seen.get(), Integer.MAX_VALUE + ",false", r.toString());
        Assert.assertEquals(r.endReason(), "Error", r.toString());
    }
}
