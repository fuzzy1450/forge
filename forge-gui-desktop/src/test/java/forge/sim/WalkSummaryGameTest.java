package forge.sim;

import java.util.ArrayList;
import java.util.List;

import org.testng.Assert;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import forge.game.zone.WalkSummaryProbe;
import forge.sim.GameRunner.GameResult;
import forge.sim.GameRunner.GameSpec;
import forge.util.SimScope;

/**
 * Commander games with Flat MC searching, each played three times on one seed: with every card visited by the
 * replacement walk (WalkSummaryProbe.plain, the walk as forEachCardInGame makes it), with the probe stepping through
 * the summaries and checking every card they pass, and on the fast path. The three digests must agree, and the probe
 * must find nothing wrong. {@code -Dwalkprobe.full=true} plays more seeds, a four-seat pod among them.
 */
public class WalkSummaryGameTest {

    @BeforeClass
    public void boot() {
        GameRunner.boot();
    }

    @AfterClass
    public void relax() {
        WalkSummaryProbe.verify = false;
        WalkSummaryProbe.plain = false;
        SimScope.setStrict(false);
    }

    @Test
    public void summariesChangeNoGame() throws Exception {
        final boolean full = Boolean.getBoolean("walkprobe.full");
        final List<GameSpec> specs = new ArrayList<>();
        specs.add(GameRunnerTest.spec(11L, 900));
        if (full) {
            specs.add(GameRunnerTest.spec(12L, 900));
            specs.add(GameRunnerTest.spec(13L, 900));
            specs.add(GameRunnerTest.pod(14L, 1800));
        }
        final GameRunner runner = new GameRunner(1);
        for (GameSpec spec : specs) {
            WalkSummaryProbe.reset();
            WalkSummaryProbe.verify = true;
            WalkSummaryProbe.plain = true;
            final GameResult reference = runner.play(spec);
            final String referenceCounts = WalkSummaryProbe.counts();

            WalkSummaryProbe.reset();
            WalkSummaryProbe.plain = false;
            final GameResult checked = runner.play(spec);
            final String checkedCounts = WalkSummaryProbe.counts();
            final long failures = WalkSummaryProbe.FAILURES.sum();
            final String firstFailure = WalkSummaryProbe.FIRST_FAILURE.get();

            WalkSummaryProbe.verify = false;
            final GameResult fast = runner.play(spec);

            System.out.println("walk probe seed=" + spec.seed() + " seats=" + spec.seats().size() + " turns="
                    + checked.turns() + " end=" + checked.endReason() + " timedOut=" + checked.timedOut()
                    + " ms(reference/checked/fast)=" + reference.ms() + "/" + checked.ms() + "/" + fast.ms());
            System.out.println("walk probe seed=" + spec.seed() + " reference " + referenceCounts);
            System.out.println("walk probe seed=" + spec.seed() + " checked " + checkedCounts);
            Assert.assertEquals(failures, 0L, "the probe found a card passed wrongly: " + firstFailure);
            Assert.assertFalse(reference.timedOut() || checked.timedOut() || fast.timedOut(),
                    "a game timed out, so its digest says nothing");
            Assert.assertNull(reference.error(), reference.error());
            Assert.assertEquals(checked.digest(), reference.digest(), "checked vs reference, seed " + spec.seed());
            Assert.assertEquals(fast.digest(), reference.digest(), "fast vs reference, seed " + spec.seed());
            Assert.assertTrue(WalkSummaryProbe.SKIPPED.sum() > 0, "nothing was passed");
        }
    }
}
