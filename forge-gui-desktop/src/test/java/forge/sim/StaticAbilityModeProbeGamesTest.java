package forge.sim;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.testng.Assert;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import forge.ai.simulation.FlatMcProfiles;
import forge.game.staticability.ModeProbeChecker;
import forge.sim.GameRunner.GameResult;
import forge.sim.GameRunner.GameSpec;
import forge.sim.GameRunner.SeatSpec;
import forge.util.SimScope;

/**
 * The static ability mode probe over real games: Commander precon games whose seats search with Flat MC (so every
 * rollout's game copies are probed too). Every skipped static ability scan is run anyway and must find nothing, move
 * no id counter and draw nothing; every direct scan must visit the cards in the order the CardCollection it replaced
 * held them (ModeProbeChecker). Every 97th skip also checks every card of every zone of the game.
 */
public class StaticAbilityModeProbeGamesTest {

    @BeforeClass
    public void boot() {
        GameRunner.boot();
        FlatMcProfiles.install();
    }

    @AfterClass(alwaysRun = true)
    public void restore() {
        ModeProbeChecker.uninstall();
        FlatMcProfiles.restore();
        SimScope.setStrict(false);
    }

    private static GameSpec duel(long seed, Path a, Path b) {
        return new GameSpec(seed, 900, List.of(
                new SeatSpec(a, "FlatSearchTight", "flat_mc:40:8"),
                new SeatSpec(b, "FlatSearchTight", "flat_mc:40:8")));
    }

    @Test(timeOut = 3_600_000)
    public void everySkipAndEveryDirectScanAgreesWithTheOldScanInSearchedGames() throws Exception {
        List<Path> precons = Precons.commander();
        Assert.assertTrue(precons.size() >= 8, "Forge ships Commander precons; found " + precons);
        List<GameSpec> specs = new ArrayList<>();
        specs.add(duel(9_100_001L, precons.get(0), precons.get(1)));
        specs.add(duel(9_100_002L, precons.get(2), precons.get(3)));
        specs.add(duel(9_100_003L, precons.get(4), precons.get(5)));
        specs.add(new GameSpec(9_100_004L, 900, List.of(
                new SeatSpec(precons.get(6), "FlatSearchTight", "flat_mc:40:8"),
                new SeatSpec(precons.get(7), "FlatSearchTight", "flat_mc:40:8"),
                new SeatSpec(precons.get(1), "FlatSearchTight", "flat_mc:40:8"),
                new SeatSpec(precons.get(3), "FlatSearchTight", "flat_mc:40:8"))));

        ModeProbeChecker checker = new ModeProbeChecker(97);
        checker.install();
        List<String> results = new ArrayList<>();
        try {
            GameRunner runner = new GameRunner(1);
            for (GameSpec spec : specs) {
                GameResult r = runner.play(spec);
                results.add(r.toString());
                Assert.assertNull(r.error(), r.toString());
            }
        } finally {
            ModeProbeChecker.uninstall();
        }
        System.err.println("static ability mode probe: " + checker.summary() + "\n  " + String.join("\n  ", results));
        Assert.assertTrue(checker.mismatches.isEmpty(), checker.summary() + " " + checker.mismatches);
        Assert.assertTrue(checker.scopedSkips.get() > 1000, checker.summary());
        Assert.assertTrue(checker.visitCalls.get() > 1000, checker.summary());
        Assert.assertTrue(checker.fullWalks.get() > 10, checker.summary());
    }

    /** With the probe on, a game plays exactly as with it off: the skipped scans it runs change nothing. */
    @Test(timeOut = 1_800_000)
    public void theProbeLeavesTheGameAsItWas() throws Exception {
        List<Path> precons = Precons.commander();
        GameSpec spec = duel(9_200_001L, precons.get(0), precons.get(2));
        GameResult off = new GameRunner(1).play(spec);
        ModeProbeChecker checker = new ModeProbeChecker(97);
        checker.install();
        GameResult on;
        try {
            on = new GameRunner(1).play(spec);
        } finally {
            ModeProbeChecker.uninstall();
        }
        Assert.assertNull(off.error(), off.toString());
        Assert.assertEquals(on.digest(), off.digest(), on + " vs " + off);
        Assert.assertEquals(on.digestLines(), off.digestLines());
        Assert.assertTrue(checker.mismatches.isEmpty(), checker.summary() + " " + checker.mismatches);
        Assert.assertTrue(checker.skips.get() > 0, checker.summary());
    }
}
