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

    /** Plays one game with a Stats attached the way JobRunner will, and returns its record. */
    static Map<String, Object> play(GameRunner runner, GameSpec spec, int g) throws Exception {
        AtomicReference<GameRecords.Stats> stats = new AtomicReference<>();
        GameResult r = runner.play(spec, game -> {
            stats.set(new GameRecords.Stats(game, spec.seats().size()));
            game.subscribeToEvents(stats.get());
        });
        return GameRecords.record(stats.get(), g, spec.seed(), seatsOf(spec), r.timedOut(), r.error(), r.ms());
    }

    @Test(timeOut = 600_000)
    public void aDecidedGameRecordsEveryKeyInTodaysOrder() throws Exception {
        GameRunner runner = new GameRunner(1);
        GameSpec spec = GameRunnerTest.spec(7_000_000L, 300);
        Map<String, Object> rec = play(runner, spec, 0);
        Assert.assertEquals(new ArrayList<>(rec.keySet()), RECORD_KEYS);
        Assert.assertEquals(rec.get("game"), 0);
        Assert.assertEquals(rec.get("seed"), 7_000_000L);
        Assert.assertTrue(List.of("AllOpponentsLost", "WinsGameSpellEffect", "Draw").contains(rec.get("end_reason")), String.valueOf(rec.get("end_reason")));
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
        Map<String, Object> a = play(runner, spec, 0), b = play(runner, spec, 0);
        a.remove("ms"); b.remove("ms");
        Assert.assertEquals(GameRecords.Json.write(a), GameRecords.Json.write(b), "the stats, not only the outcome, are a function of the seed");
    }

    @Test(timeOut = 600_000)
    public void aTimedOutGameReportsNoLiveState() throws Exception {
        GameRunner runner = new GameRunner(1);
        GameSpec pod = GameRunnerTest.pod(7_000_001L, 1);          // four precons, 1 s: cannot finish
        Map<String, Object> rec = play(runner, pod, 3);
        Assert.assertEquals(rec.get("end_reason"), "Timeout");
        Assert.assertNull(rec.get("winner_seat"));
        Assert.assertNull(rec.get("win_reason"));
        @SuppressWarnings("unchecked") List<Map<String, Object>> seats = (List<Map<String, Object>>) rec.get("seats");
        Assert.assertEquals(seats.size(), 4);
        Assert.assertNull(seats.get(0).get("final_life"), "no live read after a timeout");
        Assert.assertEquals(rec.get("game"), 3);
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
