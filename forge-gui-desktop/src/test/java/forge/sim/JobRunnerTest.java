package forge.sim;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import org.testng.Assert;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import forge.game.Game;
import forge.sim.GameRunner.GameResult;
import forge.sim.GameRunner.GameSpec;
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

    static final String CREATE_FAILED = "java.lang.IllegalStateException: createGame failed";

    /** A runner whose every game fails before its observer runs, as when createGame() throws: its play returns the
     *  runner's own Error result, carrying {@code violations}, and never calls the observer. */
    static GameRunner neverObserving(long violations) {
        return new GameRunner(1) {
            @Override
            public GameResult play(GameSpec spec, Consumer<Game> observer) {
                return new GameResult(spec.seed(), null, "Error", 0, null, false, CREATE_FAILED, 5L, "ERROR",
                        List.of(), violations);
            }
        };
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

    @Test
    public void aGameWhoseObserverNeverRanIsRecordedAndTheJobContinues() throws Exception {
        List<Map<String, Object>> out = new ArrayList<>();
        Map<String, Integer> counts = new JobRunner(neverObserving(0)).run(job(2, 9L, 300, 2), out::add);
        Assert.assertEquals(out.size(), 3, "two records and a summary, no exception");
        for (int g = 0; g < 2; g++) {
            Map<String, Object> rec = out.get(g);
            Assert.assertEquals(new ArrayList<>(rec.keySet()), GameRecordsTest.RECORD_KEYS);
            Assert.assertEquals(rec.get("game"), g);
            Assert.assertEquals(rec.get("seed"), 9L + g);
            Assert.assertEquals(rec.get("end_reason"), "Error");
            Assert.assertEquals(rec.get("error"), CREATE_FAILED, "the runner's error, not a failure to build the record");
            Assert.assertNull(rec.get("winner_seat"));
            Assert.assertEquals(rec.get("turns"), 0);
            @SuppressWarnings("unchecked") List<Map<String, Object>> seats = (List<Map<String, Object>>) rec.get("seats");
            Assert.assertEquals(seats.size(), 2);
            for (int i = 0; i < 2; i++) {
                Assert.assertEquals(seats.get(i).get("deck_hash"), "hash" + i);
                Assert.assertNull(seats.get(i).get("final_life"), "no game, no live read");
            }
            Assert.assertEquals(rec.get("cards"), List.of());
        }
        Assert.assertEquals(counts.get("Error"), (Integer) 2);
        Assert.assertEquals(GameRecords.Json.write(out.get(2)),
                "{\"summary\":{\"games\":2,\"AllOpponentsLost\":0,\"WinsGameSpellEffect\":0,\"Draw\":0,\"Timeout\":0,\"Error\":2,\"ms\":10}}");
    }

    @Test
    public void aNonZeroViolationCountRidesOnTheRecordAsItsLastKey() throws Exception {
        List<Map<String, Object>> out = new ArrayList<>();
        new JobRunner(neverObserving(2)).run(job(1, 9L, 300, 2), out::add);
        Map<String, Object> rec = out.get(0);
        List<String> keys = new ArrayList<>(rec.keySet());
        Assert.assertEquals(keys.subList(keys.size() - 2, keys.size()), List.of("cards", "violations"));
        Assert.assertEquals(keys.subList(0, keys.size() - 1), GameRecordsTest.RECORD_KEYS);
        Assert.assertEquals(rec.get("violations"), 2L);
        Assert.assertTrue(GameRecords.Json.write(rec).endsWith(",\"cards\":[],\"violations\":2}"), GameRecords.Json.write(rec));
        List<Map<String, Object>> clean = new ArrayList<>();
        new JobRunner(neverObserving(0)).run(job(1, 9L, 300, 2), clean::add);
        Assert.assertFalse(clean.get(0).containsKey("violations"), "a zero count: no key");
        Assert.assertEquals(new ArrayList<>(clean.get(0).keySet()), GameRecordsTest.RECORD_KEYS);
    }
}
