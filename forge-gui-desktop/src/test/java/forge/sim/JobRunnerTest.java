package forge.sim;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
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
    public void boot() { GameRunner.boot(); }

    @AfterClass
    public void relaxStrict() { SimScope.setStrict(false); }

    /** A job over the first two quest precons, as JobFile would parse it. */
    static JobFile job(int games, long seed, int timeoutS, int seats) {
        return job(games, seed, timeoutS, seats, "hash");
    }

    /** The same job with seat i's deck hash {@code hashPrefix + i}; "hash-" gives GameRecordsTest.seatsOf's. */
    static JobFile job(int games, long seed, int timeoutS, int seats, String hashPrefix) {
        List<String> lines = new ArrayList<>(List.of("games=" + games, "seed=" + seed, "timeout_s=" + timeoutS));
        List<java.nio.file.Path> precons = Precons.commander();
        for (int i = 0; i < seats; i++) {
            lines.add("seat." + i + ".deck_file=" + precons.get(i));
            lines.add("seat." + i + ".deck_hash=" + hashPrefix + i);
        }
        return JobFile.parse(lines);
    }

    /** A default job's record keys: GameRecordsTest.RECORD_KEYS, then {@code middle}, then the game's digest and its
     *  flat_mc tallies, which every default job's record carries (MTG_DeckMaker's tools/combo-ai/flat-mc/SHIP.md,
     *  design 2). */
    static List<String> defaultJobKeys(String... middle) {
        List<String> keys = new ArrayList<>(GameRecordsTest.RECORD_KEYS);
        keys.addAll(List.of(middle));
        keys.add("digest");
        keys.add("flat_mc");
        return keys;
    }

    static final String CREATE_FAILED = "java.lang.IllegalStateException: createGame failed";

    /** The runner's own Error result for a game that failed before its observer ran, as when createGame() throws. */
    static GameResult createFailed(GameSpec spec, long violations) {
        return new GameResult(spec.seed(), null, "Error", 0, null, false, CREATE_FAILED, 5L, "ERROR", List.of(),
                violations);
    }

    /** A runner whose every game fails before its observer runs, as when createGame() throws: its play returns the
     *  runner's own Error result, carrying {@code violations}, and never calls the observer. */
    static GameRunner neverObserving(long violations) {
        return new GameRunner(1) {
            @Override
            public GameResult play(GameSpec spec, Consumer<Game> observer) {
                return createFailed(spec, violations);
            }
        };
    }

    /** A runner that poisons itself on its second play, as a game thread that outlived its grace period would: its
     *  own poison check then refuses that play and every later one with a PoisonedException. The observer form of
     *  DeterminismBatteryTest.poisonedOnSecondPlay. */
    static GameRunner poisonedOnSecondPlay() {
        return new GameRunner(1) {
            private final AtomicInteger plays = new AtomicInteger();

            @Override
            public GameResult play(GameSpec spec, Consumer<Game> observer) throws InterruptedException {
                if (plays.incrementAndGet() == 2) {
                    poisonForTest();
                }
                return super.play(spec, observer);
            }
        };
    }

    @Test(timeOut = 600_000)
    public void everyGameIsRecordedInOrderAndTheSummaryClosesTheJob() throws Exception {
        List<Map<String, Object>> out = new ArrayList<>();
        JobFile job = job(2, 7_000_000L, 300, 2, "hash-");                 // GameRecordsTest.seatsOf's deck hashes
        Map<String, Integer> counts = new JobRunner(new GameRunner(1)).run(job, out::add);
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
        // The values above come from the outcome or from JobRunner itself. What only the subscriber knows -- first
        // seat, mulligans, lands, spells, every card row -- is pinned by comparing each record, ms aside, with the one
        // GameRecordsTest's reference attach builds for the same spec: a fresh Stats per game, subscribed before the
        // game starts. A default job's record then ends with the game's digest and flat_mc tallies, which JobRunner
        // takes from the runner's result and the reference attach does not write: they are the reference game's.
        for (int g = 0; g < 2; g++) {
            GameRecordsTest.Recorded played = GameRecordsTest.play(new GameRunner(1), job.specs().get(g), g);
            Map<String, Object> rec = out.get(g);
            Assert.assertEquals(new ArrayList<>(rec.keySet()), defaultJobKeys(), "game " + g);
            Assert.assertEquals(rec.remove("digest"), played.result().digest(), "game " + g + "'s digest");
            Assert.assertEquals(rec.remove("flat_mc"), played.result().flatMc(), "game " + g + "'s tallies");
            Map<String, Object> reference = played.rec();
            rec.remove("ms");
            reference.remove("ms");
            Assert.assertEquals(GameRecords.Json.write(rec), GameRecords.Json.write(reference),
                    "game " + g + "'s record is the reference attach's");
        }
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

    @Test(timeOut = 600_000)
    public void theRecordsBeforeAMidJobPoisonAreHandedOverAndNoSummaryFollows() throws Exception {
        List<Map<String, Object>> out = new ArrayList<>();
        try {
            new JobRunner(poisonedOnSecondPlay()).run(job(3, 7_000_000L, 300, 2), out::add);
            Assert.fail("expected PoisonedException");
        } catch (GameRunner.PoisonedException expected) {
            Assert.assertEquals(out.size(), 1, "the first game's record, handed over before the poison, and no summary");
            Assert.assertEquals(out.get(0).get("game"), 0);
        }
    }

    @Test
    public void aDeckTheRunnerCannotLoadStopsTheJobBeforeItsFirstGame() {
        JobFile job = JobFile.parse(List.of("games=2", "seed=9", "timeout_s=300", "seat.0.deck_file=no-such-deck.dck",
                "seat.1.deck_file=" + Precons.commander().get(1)));
        List<Map<String, Object>> out = new ArrayList<>();
        IllegalArgumentException refused = Assert.expectThrows(IllegalArgumentException.class,
                () -> new JobRunner(new GameRunner(1)).run(job, out::add));
        Assert.assertTrue(refused.getMessage().contains("no-such-deck.dck"), refused.getMessage());
        Assert.assertTrue(out.isEmpty(), "no record and no summary");
    }

    /** A job of no games has no first game to refuse its deck at: the job's first spec is validated before the loop,
     *  so the sink is never called, not even for the summary. */
    @Test
    public void aJobOfNoGamesWhoseDeckCannotBeLoadedIsRefusedWithNoSummary() {
        JobFile job = JobFile.parse(List.of("games=0", "seed=9", "timeout_s=300", "seat.0.deck_file=no-such-deck.dck",
                "seat.1.deck_file=" + Precons.commander().get(1)));
        List<Map<String, Object>> out = new ArrayList<>();
        IllegalArgumentException refused = Assert.expectThrows(IllegalArgumentException.class,
                () -> new JobRunner(new GameRunner(1)).run(job, out::add));
        Assert.assertTrue(refused.getMessage().contains("no-such-deck.dck"), refused.getMessage());
        Assert.assertTrue(out.isEmpty(), "the sink was never called: " + out);
    }

    @Test
    public void anInterruptStopsTheJobWithNoRecordForThatGameAndNoSummary() {
        GameRunner runner = new GameRunner(1) {
            private final AtomicInteger plays = new AtomicInteger();

            @Override
            public GameResult play(GameSpec spec, Consumer<Game> observer) throws InterruptedException {
                if (plays.incrementAndGet() == 2) {
                    throw new InterruptedException("interrupted while waiting for the game with seed " + spec.seed());
                }
                return createFailed(spec, 0);
            }
        };
        List<Map<String, Object>> out = new ArrayList<>();
        Assert.assertThrows(InterruptedException.class, () -> new JobRunner(runner).run(job(3, 9L, 300, 2), out::add));
        Assert.assertEquals(out.size(), 1, "the first game's record; none for the interrupted game, and no summary");
        Assert.assertEquals(out.get(0).get("game"), 0);
    }

    @Test
    public void aPoisonCausedByTheJobsLastGameIsTheRunnersToReport() throws Exception {
        GameRunner runner = new GameRunner(1) {
            @Override
            public GameResult play(GameSpec spec, Consumer<Game> observer) {
                poisonForTest();                    // as play() does when this game's thread outlives its grace period
                return new GameResult(spec.seed(), null, "Timeout", 0, null, true, null, 5L, "TIMEOUT", List.of(), 0L);
            }
        };
        List<Map<String, Object>> out = new ArrayList<>();
        Map<String, Integer> counts = new JobRunner(runner).run(job(1, 9L, 300, 2), out::add);
        Assert.assertEquals(out.size(), 2, "the game's record, then the summary: run returned normally");
        Assert.assertEquals(out.get(0).get("end_reason"), "Timeout");
        Assert.assertTrue(out.get(1).containsKey("summary"));
        Assert.assertEquals(counts.get("Timeout"), (Integer) 1);
        Assert.assertTrue(runner.isPoisoned(), "the runner, not run, says the JVM is poisoned");
    }

    @Test
    public void aGameWhoseObserverNeverRanIsRecordedAndTheJobContinues() throws Exception {
        List<Map<String, Object>> out = new ArrayList<>();
        Map<String, Integer> counts = new JobRunner(neverObserving(0)).run(job(2, 9L, 300, 2), out::add);
        Assert.assertEquals(out.size(), 3, "two records and a summary, no exception");
        for (int g = 0; g < 2; g++) {
            Map<String, Object> rec = out.get(g);
            Assert.assertEquals(new ArrayList<>(rec.keySet()), defaultJobKeys());
            Assert.assertEquals(rec.get("digest"), "ERROR", "an unfinished game's literal digest");
            Assert.assertNull(rec.get("flat_mc"), "and no tallies: nothing live is read");
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

    /** A non-zero violation count rides on the record right after its cards, before a default job's digest and
     *  flat_mc (MTG_DeckMaker's tools/combo-ai/flat-mc/SHIP.md, design 2), and a zero count writes no key. */
    @Test
    public void aNonZeroViolationCountRidesOnTheRecordAfterItsCards() throws Exception {
        List<Map<String, Object>> out = new ArrayList<>();
        new JobRunner(neverObserving(2)).run(job(1, 9L, 300, 2), out::add);
        Map<String, Object> rec = out.get(0);
        Assert.assertEquals(new ArrayList<>(rec.keySet()), defaultJobKeys("violations"));
        Assert.assertEquals(rec.get("violations"), 2L);
        Assert.assertTrue(GameRecords.Json.write(rec).endsWith(
                ",\"cards\":[],\"violations\":2,\"digest\":\"ERROR\",\"flat_mc\":null}"), GameRecords.Json.write(rec));
        List<Map<String, Object>> clean = new ArrayList<>();
        new JobRunner(neverObserving(0)).run(job(1, 9L, 300, 2), clean::add);
        Assert.assertFalse(clean.get(0).containsKey("violations"), "a zero count: no key");
        Assert.assertEquals(new ArrayList<>(clean.get(0).keySet()), defaultJobKeys());
    }
}
