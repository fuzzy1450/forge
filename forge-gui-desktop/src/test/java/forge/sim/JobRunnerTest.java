package forge.sim;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.testng.Assert;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import forge.util.SimScope;

public class JobRunnerTest {
    @BeforeClass
    public static void boot() { GameRunner.boot(); }

    @AfterClass
    public static void relaxStrict() { SimScope.setStrict(false); }

    /** A job over the first two quest precons, as JobFile would parse it. */
    static JobFile job(int games, long seed, int timeoutS, int seats) {
        List<String> lines = new ArrayList<>(List.of("games=" + games, "seed=" + seed, "timeout_s=" + timeoutS));
        List<java.nio.file.Path> precons = Precons.commander();
        for (int i = 0; i < seats; i++) {
            lines.add("seat." + i + ".deck_file=" + precons.get(i));
            lines.add("seat." + i + ".deck_hash=hash" + i);
        }
        return JobFile.parse(lines);
    }

    @Test(timeOut = 600_000)
    public void everyGameIsRecordedInOrderAndTheSummaryClosesTheJob() throws Exception {
        List<Map<String, Object>> out = new ArrayList<>();
        Map<String, Integer> counts = new JobRunner(new GameRunner(1)).run(job(2, 7_000_000L, 300, 2), out::add);
        Assert.assertEquals(out.size(), 3, "two records and a summary");
        Assert.assertEquals(out.get(0).get("game"), 0);
        Assert.assertEquals(out.get(1).get("game"), 1);
        Assert.assertEquals(out.get(1).get("seed"), 7_000_001L);
        Assert.assertTrue(out.get(2).containsKey("summary"));
        @SuppressWarnings("unchecked") Map<String, Object> summary = (Map<String, Object>) out.get(2).get("summary");
        Assert.assertEquals(summary.get("games"), 2);
        Assert.assertEquals(new ArrayList<>(summary.keySet()), List.of("games", "AllOpponentsLost", "WinsGameSpellEffect", "Draw", "Timeout", "Error", "ms"));
        int decided = counts.get("AllOpponentsLost") + counts.get("WinsGameSpellEffect") + counts.get("Draw");
        Assert.assertEquals(decided, 2);
        Assert.assertFalse(out.get(0).containsKey("violations"), "no violations: no key");
    }

    @Test(timeOut = 600_000)
    public void aTimeoutIsRecordedAndTheJobContinues() throws Exception {
        List<Map<String, Object>> out = new ArrayList<>();
        Map<String, Integer> counts = new JobRunner(new GameRunner(1)).run(job(2, 7_000_000L, 1, 4), out::add);
        Assert.assertEquals(out.size(), 3, "both pods time out and are recorded, then the summary");
        Assert.assertEquals(out.get(0).get("end_reason"), "Timeout");
        Assert.assertEquals(out.get(1).get("end_reason"), "Timeout");
        Assert.assertEquals(counts.get("Timeout"), (Integer) 2);
    }

    @Test(timeOut = 600_000)
    public void aPoisonedRunnerStopsTheJobWithoutASummary() throws Exception {
        GameRunner runner = new GameRunner(1);
        runner.poisonForTest();
        List<Map<String, Object>> out = new ArrayList<>();
        try {
            new JobRunner(runner).run(job(1, 7_000_000L, 300, 2), out::add);
            Assert.fail("expected PoisonedException");
        } catch (GameRunner.PoisonedException expected) {
            Assert.assertTrue(out.isEmpty(), "nothing was played, nothing was written");
        }
    }
}
