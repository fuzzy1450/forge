package forge.sim;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.testng.Assert;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import forge.sim.GameRunner.GameResult;
import forge.sim.GameRunner.GameSpec;
import forge.util.SimScope;

public class GameRecordsTest {
    static final List<String> RECORD_KEYS = List.of("game", "seed", "winner_seat", "end_reason", "turns",
            "first_seat", "ms", "error", "win_reason", "win_card", "seats", "cards");
    static final List<String> SEAT_KEYS = List.of("seat", "deck_hash", "profile", "ai", "final_life", "mulligans",
            "lands_played", "spells_cast", "commander_casts", "eliminated_turn");
    static final List<String> CARD_KEYS = List.of("seat", "name", "in_opening_hand", "first_drawn_turn",
            "times_cast", "first_cast_turn", "owner_casts", "owner_first_cast_turn", "died", "final_zone");

    @BeforeClass
    public static void boot() {
        GameRunner.boot();
    }

    @AfterClass
    public static void relaxStrict() {
        SimScope.setStrict(false);
    }

    static List<JobFile.Seat> seatsOf(GameSpec spec) {
        List<JobFile.Seat> out = new ArrayList<>();
        for (GameRunner.SeatSpec s : spec.seats()) {
            out.add(new JobFile.Seat(s.deckFile().toString(), "hash-" + out.size(), s.profile(), s.ai()));
        }
        return out;
    }

    /** One played game: the runner's result and the record built from it. */
    record Recorded(GameResult result, Map<String, Object> rec) { }

    /** Plays one game with a Stats attached the way JobRunner does, and returns the runner's result and the record. */
    static Recorded play(GameRunner runner, GameSpec spec, int g) throws Exception {
        AtomicReference<GameRecords.Stats> stats = new AtomicReference<>();
        GameResult r = runner.play(spec, game -> {
            stats.set(new GameRecords.Stats(game, spec.seats().size()));
            game.subscribeToEvents(stats.get());
        });
        GameRecords.Stats seen = stats.get();                      // read once; null when the observer never ran
        GameRecords.Stats collected = seen != null ? seen : new GameRecords.Stats(spec.seats().size());
        return new Recorded(r, GameRecords.record(collected, g, spec.seed(), seatsOf(spec), r.timedOut(), r.error(), r.ms()));
    }

    @Test(timeOut = 600_000)
    public void aDecidedGameRecordsEveryKeyInTodaysOrder() throws Exception {
        GameRunner runner = new GameRunner(1);
        GameSpec spec = GameRunnerTest.spec(7_000_000L, 300);
        Recorded played = play(runner, spec, 0);
        Map<String, Object> rec = played.rec();
        GameResult r = played.result();
        Assert.assertEquals(new ArrayList<>(rec.keySet()), RECORD_KEYS);
        Assert.assertEquals(rec.get("game"), 0);
        Assert.assertEquals(rec.get("seed"), 7_000_000L);
        Assert.assertTrue(List.of("AllOpponentsLost", "WinsGameSpellEffect", "Draw").contains(rec.get("end_reason")), String.valueOf(rec.get("end_reason")));
        // The record finds its winner by player name, the runner by registration order: the two must agree, on two
        // decided games won by different seats, so that a record naming one seat whatever happened cannot pass.
        Recorded other = play(runner, GameRunnerTest.spec(7_000_003L, 300), 1);
        for (Recorded p : List.of(played, other)) {
            Assert.assertEquals(p.rec().get("winner_seat"), p.result().winnerSeat(), "the record's winner is the runner's");
            Assert.assertEquals(p.rec().get("end_reason"), p.result().endReason(), "the record's end_reason is the runner's");
            Assert.assertEquals(p.rec().get("turns"), p.result().turns(), "the record's turns are the runner's");
            Assert.assertEquals(p.rec().get("first_seat"), p.result().firstSeat(), "the record's first_seat is the runner's");
        }
        Assert.assertNotNull(r.winnerSeat(), "seed 7_000_000 has a winner");
        Assert.assertNotNull(other.result().winnerSeat(), "seed 7_000_003 has a winner");
        Assert.assertNotEquals(other.result().winnerSeat(), r.winnerSeat(),
                "seeds 7_000_000 and 7_000_003 are won by different seats; if an engine change breaks that, pick two that are");
        if (rec.get("winner_seat") != null) {
            Assert.assertNotNull(rec.get("win_reason"), "a decided two-seat game says how the loser lost");
        }
        @SuppressWarnings("unchecked") List<Map<String, Object>> seats = (List<Map<String, Object>>) rec.get("seats");
        Assert.assertEquals(seats.size(), 2);
        Assert.assertEquals(new ArrayList<>(seats.get(0).keySet()), SEAT_KEYS);
        Assert.assertEquals(seats.get(1).get("deck_hash"), "hash-1");
        Assert.assertNotNull(seats.get(0).get("final_life"), "a decided game reads final life");
        @SuppressWarnings("unchecked") List<Map<String, Object>> cards = (List<Map<String, Object>>) rec.get("cards");
        Assert.assertFalse(cards.isEmpty());
        Assert.assertEquals(new ArrayList<>(cards.get(0).keySet()), CARD_KEYS);
        Assert.assertTrue(GameRecords.Json.write(rec).startsWith("{\"game\":0,\"seed\":7000000,"), "today's JSON text");
    }

    @Test(timeOut = 600_000)
    public void theSameSeedRecordsTheSameStats() throws Exception {
        GameRunner runner = new GameRunner(1);
        GameSpec spec = GameRunnerTest.spec(7_000_001L, 300);
        Map<String, Object> a = play(runner, spec, 0).rec(), b = play(runner, spec, 0).rec();
        a.remove("ms"); b.remove("ms");
        Assert.assertEquals(GameRecords.Json.write(a), GameRecords.Json.write(b), "the stats, not only the outcome, are a function of the seed");
    }

    @Test(timeOut = 600_000)
    public void aTimedOutGameReportsNoLiveState() throws Exception {
        GameRunner runner = new GameRunner(1);
        GameSpec pod = GameRunnerTest.pod(7_000_001L, 1);          // four precons, 1 s: cannot finish
        Map<String, Object> rec = play(runner, pod, 3).rec();
        Assert.assertEquals(rec.get("end_reason"), "Timeout");
        Assert.assertNull(rec.get("winner_seat"));
        Assert.assertNull(rec.get("win_reason"));
        @SuppressWarnings("unchecked") List<Map<String, Object>> seats = (List<Map<String, Object>>) rec.get("seats");
        Assert.assertEquals(seats.size(), 4);
        Assert.assertNull(seats.get(0).get("final_life"), "no live read after a timeout");
        Assert.assertEquals(rec.get("game"), 3);
    }

    @Test
    public void aStatsWithNoGameIsRecordedWithoutLiveReads() {
        List<JobFile.Seat> seats = List.of(new JobFile.Seat("a.dck", "hash-0", "Default", "default"),
                new JobFile.Seat("b.dck", "hash-1", "Default", "default"));
        Map<String, Object> rec = GameRecords.record(new GameRecords.Stats(2), 4, 11L, seats, false, "boom", 7L);
        Assert.assertEquals(new ArrayList<>(rec.keySet()), RECORD_KEYS);
        Assert.assertEquals(rec.get("end_reason"), "Error");
        Assert.assertEquals(rec.get("error"), "boom", "the runner's error, not a failure to build the record");
        Assert.assertNull(rec.get("winner_seat"));
        Assert.assertEquals(rec.get("turns"), 0);
        Assert.assertNull(rec.get("first_seat"));
        @SuppressWarnings("unchecked") List<Map<String, Object>> recSeats = (List<Map<String, Object>>) rec.get("seats");
        Assert.assertEquals(recSeats.size(), 2);
        for (Map<String, Object> s : recSeats) {
            Assert.assertEquals(new ArrayList<>(s.keySet()), SEAT_KEYS);
            Assert.assertNull(s.get("final_life"), "no game, no live read");
        }
        Assert.assertEquals(rec.get("cards"), List.of());
        Map<String, Object> noError = GameRecords.record(new GameRecords.Stats(2), 4, 11L, seats, false, null, 7L);
        Assert.assertEquals(noError.get("end_reason"), "Error");
        Assert.assertEquals(noError.get("error"), "game ended without an outcome");
        Map<String, Object> timedOut = GameRecords.record(new GameRecords.Stats(2), 4, 11L, seats, true, null, 7L);
        Assert.assertEquals(timedOut.get("end_reason"), "Timeout");
        Map<String, Object> fallback = GameRecords.fallbackRecord(new GameRecords.Stats(2), 4, 11L, seats, false, "boom",
                new IllegalStateException("x"), 7L);
        Assert.assertEquals(new ArrayList<>(fallback.keySet()), RECORD_KEYS);
        Assert.assertEquals(fallback.get("end_reason"), "Error");
        Assert.assertEquals(fallback.get("error"), "boom | record failed: java.lang.IllegalStateException: x");
    }

    /** Game-free: fallbackRecord's keys and its error text with the harness's 400-character cut, and Json's escapes. */
    @Test
    public void fallbackRecordAndJsonKeepTheHarnessText() {
        List<JobFile.Seat> seats = List.of(new JobFile.Seat("a.dck", "hash-0", "Default", "default"),
                new JobFile.Seat("b.dck", "hash-1", "Default", "default"));
        String longMessage = "m".repeat(500);
        Map<String, Object> rec = GameRecords.fallbackRecord(new GameRecords.Stats(2), 5, 12L, seats, true,
                "the runner's error", new IllegalStateException(longMessage), 9L);
        Assert.assertEquals(new ArrayList<>(rec.keySet()), RECORD_KEYS);
        @SuppressWarnings("unchecked") List<Map<String, Object>> recSeats = (List<Map<String, Object>>) rec.get("seats");
        Assert.assertEquals(recSeats.size(), 2);
        for (Map<String, Object> s : recSeats) {
            Assert.assertEquals(new ArrayList<>(s.keySet()), SEAT_KEYS);
        }
        String full = "the runner's error | record failed: java.lang.IllegalStateException: " + longMessage;
        Assert.assertEquals(rec.get("error"), full.substring(0, 400), "the runner's error, then the failure, cut at 400");
        Assert.assertEquals(rec.get("end_reason"), "Timeout");
        Map<String, Object> alone = GameRecords.fallbackRecord(new GameRecords.Stats(2), 5, 12L, seats, false, null,
                new IllegalStateException("short"), 9L);
        Assert.assertEquals(alone.get("error"), "record failed: java.lang.IllegalStateException: short");
        Assert.assertEquals(alone.get("end_reason"), "Error");

        Assert.assertEquals(GameRecords.Json.write("a\"b"), "\"a\\\"b\"");
        Assert.assertEquals(GameRecords.Json.write("a\\b"), "\"a\\\\b\"");
        Assert.assertEquals(GameRecords.Json.write("a\nb"), "\"a\\nb\"");
        Assert.assertEquals(GameRecords.Json.write("a\rb"), "\"a\\rb\"");
        Assert.assertEquals(GameRecords.Json.write("a\tb"), "\"a\\tb\"");
        // A control character as Harness.Json writes it: four hex digits, lower case (ESC's hex has a letter).
        Assert.assertEquals(GameRecords.Json.write("a" + (char) 0x1b + "b"), "\"a\\" + "u001bb\"");
    }

    @Test
    public void theSummaryHasTodaysShape() {
        Map<String, Integer> counts = new java.util.LinkedHashMap<>();
        for (String r : GameRecords.END_REASONS) counts.put(r, 0);
        counts.put("AllOpponentsLost", 2);
        String json = GameRecords.Json.write(GameRecords.summary(2, counts, 1234L));
        Assert.assertEquals(json, "{\"summary\":{\"games\":2,\"AllOpponentsLost\":2,\"WinsGameSpellEffect\":0,\"Draw\":0,\"Timeout\":0,\"Error\":0,\"ms\":1234}}");
    }
}
