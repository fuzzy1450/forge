package forge.sim;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import org.testng.Assert;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import forge.ai.AiController;
import forge.ai.PlayerControllerAi;
import forge.ai.simulation.FlatMcGame;
import forge.ai.simulation.FlatMcProfiles;
import forge.ai.simulation.FlatMcSeat;
import forge.game.Game;
import forge.game.player.Player;
import forge.sim.GameRunner.GameResult;
import forge.sim.GameRunner.GameSpec;
import forge.sim.GameRunner.SeatSpec;
import forge.util.SimScope;

/**
 * The flat_mc seat words in a game the runner plays (MTG_DeckMaker's tools/combo-ai/flat-mc/SPEC.md, section 4.1): the
 * runner takes the words as no AI option, and switches the search on for the seat that names one and for no other,
 * while the game-wide flags stay off. And what such a game leaves behind (sections 4.6 and 4.7): its search lines in
 * its digest, its searching seats' tallies in its result and its job record, and its wall-clock line on stderr. The
 * class loads the four private profiles beside the shipped ones (FlatMcProfiles).
 */
public class FlatMcRunnerTest {

    @BeforeClass
    public void boot() {
        GameRunner.boot();
        FlatMcProfiles.install();
    }

    @AfterClass
    public void relaxStrict() {
        SimScope.setStrict(false);
    }

    @AfterClass
    public void restoreProfiles() {
        FlatMcProfiles.restore();
    }

    /** GameRunnerTest.spec(seed, 600) with seat {@code seat}'s precon played by {@code profile} and {@code ai}. */
    static GameSpec seated(long seed, int seat, String profile, String ai) {
        return withSeat(GameRunnerTest.spec(seed, 600), seat, profile, ai);
    }

    /** {@code spec} with seat {@code seat}'s deck played by {@code profile} and {@code ai}, the other seats as they
     *  were. */
    static GameSpec withSeat(GameSpec spec, int seat, String profile, String ai) {
        List<SeatSpec> seats = new ArrayList<>(spec.seats());
        seats.set(seat, new SeatSpec(seats.get(seat).deckFile(), profile, ai));
        return new GameSpec(spec.seed(), spec.timeoutSeconds(), List.copyOf(seats));
    }

    /** The tight seat, (flat_mc:40:8, FlatSearchTight), in seat {@code seat} of a 600 s duel. */
    static GameSpec tight(long seed, int seat) {
        return seated(seed, seat, "FlatSearchTight", "flat_mc:40:8");
    }

    /** The budgeted seat, (flat_mc, FlatSearchBudget). */
    static GameSpec budget(long seed, int seat) {
        return seated(seed, seat, "FlatSearchBudget", "flat_mc");
    }

    /** The no-budget seat, (flat_mc, FlatSearchOff). */
    static GameSpec off(long seed, int seat) {
        return seated(seed, seat, "FlatSearchOff", "flat_mc");
    }

    /** The zero-rollout seat, (flat_mc_zero, FlatSearch): the plan's pairing, since the shipped Default profile has
     *  land choice on, and land choice has no zero-rollout branch. */
    static GameSpec zero(long seed, int seat) {
        return seated(seed, seat, "FlatSearch", "flat_mc_zero");
    }

    /** The lines of {@code r}'s digest that begin {@code prefix}, in order. */
    static List<String> linesStarting(GameResult r, String prefix) {
        return r.digestLines().stream().filter(l -> l.startsWith(prefix)).collect(Collectors.toList());
    }

    /** The rollouts of entry {@code i} of {@code r}'s flat_mc tallies. */
    static long rollouts(GameResult r, int i) {
        return (Long) r.flatMc().get(i).get("rollouts");
    }

    /** A game's result and the flat_mc wall lines written to System.err while it played. */
    record Played(GameResult result, List<String> wall) {
        /** The wall lines of the game of {@code seed}. Another game's thread can write meanwhile, as a timed-out game's
         *  does when it outlives its play, and its line names its own seed. */
        List<String> wallOf(long seed) {
            String prefix = "flat_mc wall seed=" + seed + " ";
            return wall.stream().filter(l -> l.startsWith(prefix)).collect(Collectors.toList());
        }
    }

    /** Runs {@code play} with System.err captured; what was captured is then written to the real System.err, so the
     *  test log keeps it. */
    static Played capturingErr(Callable<GameResult> play) throws Exception {
        PrintStream real = System.err;
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        GameResult r;
        try {
            System.setErr(new PrintStream(bytes, true, StandardCharsets.UTF_8));
            r = play.call();
        } finally {
            System.setErr(real);
        }
        String text = bytes.toString(StandardCharsets.UTF_8);
        real.print(text);
        return new Played(r, text.lines().filter(l -> l.startsWith("flat_mc wall ")).collect(Collectors.toList()));
    }

    /** {@code wall} is one line, and it matches {@code regex}. */
    static void assertOneWallLine(List<String> wall, String regex) {
        Assert.assertEquals(wall.size(), 1, "one wall line for the game: " + wall);
        Assert.assertTrue(wall.get(0).matches(regex), wall.get(0) + " does not match " + regex);
    }

    /**
     * A mixed-seat twin of GameRunnerTest.flatMonteCarloIsOffOnTheGameAndForEverySeat: seat 1 names flat_mc:40:8, so its
     * controller carries the override and the seat, and seat 0 neither. The seat is the search-only mark, so
     * flatMonteCarloActive() stays false on the flat seat too, and the switch is read through the getters. The observer
     * throws once it has looked, so no game is played.
     */
    @Test(timeOut = 300_000)
    public void onlyTheFlatSeatIsSwitchedOnAndNoSeatIsFlatMonteCarloActive() throws Exception {
        List<Path> precons = Precons.commander();
        Assert.assertTrue(precons.size() >= 2, "Forge ships Commander precons; found " + precons);
        GameSpec spec = new GameSpec(7_000_000L, 300, List.of(
                new SeatSpec(precons.get(0), "Default", "default"),
                new SeatSpec(precons.get(1), "Default", "flat_mc:40:8")));
        GameRunner runner = new GameRunner(1);
        AtomicReference<String> seen = new AtomicReference<>();
        GameResult r = runner.play(spec, g -> {
            StringBuilder sb = new StringBuilder();
            sb.append(g.getAIFlatMonteCarlo()).append(',').append(g.getAIFlatMonteCarloMax()).append(',')
                    .append(g.getAIFlatMonteCarloLite());
            for (Player p : g.getPlayers()) {
                AiController ai = ((PlayerControllerAi) p.getController()).getAi();
                sb.append('|').append(ai.getFlatMonteCarloOverride()).append(',').append(ai.getFlatMcSeat())
                        .append(',').append(ai.flatMonteCarloActive());
            }
            seen.set(sb.toString());
            throw new IllegalStateException("looked");
        });
        Assert.assertEquals(seen.get(), "false,false,false|null,null,false"
                + "|true,FlatMcSeat[rolloutsPerGame=40, resolutionsPerRollout=8, zeroRollout=false],false", r.toString());
        Assert.assertEquals(r.endReason(), "Error", r.toString());
    }

    @Test
    public void theRunnerJudgesTheFlatWords() {
        Assert.assertNull(GameRunner.aiOptions("flat_mc"));
        Assert.assertNull(GameRunner.aiOptions("flat_mc:40:8"));
        Assert.assertNull(GameRunner.aiOptions("flat_mc_zero"));
        for (String word : List.of("flat_mc:40", "no_such_ai")) {
            IllegalArgumentException e = Assert.expectThrows(IllegalArgumentException.class,
                    () -> GameRunner.aiOptions(word));
            Assert.assertTrue(e.getMessage().startsWith("unknown ai mode " + word + " ("), e.getMessage());
            Assert.assertEquals(e.getMessage(), "unknown ai mode " + word + " " + FlatMcSeat.AI_MODES);
        }
    }

    /**
     * A tight seat's game replayed on a fresh runner gives the same digest, digest lines and tallies. Its digest closes
     * with seat 1's search line and is the hash of every line, that one included, and the seat's rollouts stay within
     * the word's 40 plus the last decision's few: the cap is read before a decision, and a 2 ms decision charges at
     * most 4.
     */
    @Test(timeOut = 600_000)
    public void aSearchedGameReplaysToTheSameDigestAndTally() throws Exception {
        GameResult x = new GameRunner(1).play(tight(7_100_000L, 1)), y = new GameRunner(1).play(tight(7_100_000L, 1));
        Assert.assertNull(x.error(), x.toString());
        Assert.assertNull(y.error(), y.toString());
        Assert.assertEquals(y.digest(), x.digest());
        Assert.assertEquals(y.digestLines(), x.digestLines());
        Assert.assertEquals(y.flatMc(), x.flatMc());
        List<String> search = linesStarting(x, "search\t");
        Assert.assertEquals(search.size(), 1, search.toString());
        Assert.assertTrue(search.get(0).startsWith("search\t1\t"), search.toString());
        Assert.assertEquals(x.digestLines().get(x.digestLines().size() - 1), search.get(0),
                "the search line comes last");
        Assert.assertEquals(x.digest(), GameRunner.sha256(x.digestLines()), "the digest covers the search line");
        Assert.assertEquals(x.flatMc().size(), 1, x.flatMc().toString());
        Assert.assertEquals(x.flatMc().get(0).get("seat"), 1);
        Assert.assertTrue(rollouts(x, 0) <= 44L, x.flatMc().toString());
    }

    /**
     * Two budgeted seats' games, each alone on a runner of its own, then both at once on one two-slot runner: each
     * concurrent game equals its solo game in digest, digest lines and tallies, so neither game's search reads the
     * other's clock, caps or counts. Each seat did search, so the equality covers rollouts.
     */
    @Test(timeOut = 900_000)
    public void twoSearchedGamesSideBySideEqualEachAlone() throws Exception {
        List<GameSpec> specs = List.of(budget(7_100_001L, 0), budget(7_100_002L, 1));
        List<GameResult> alone = new ArrayList<>();
        for (GameSpec s : specs) {
            alone.add(new GameRunner(1).play(s));
        }
        AtomicInteger inFlight = new AtomicInteger();
        AtomicInteger mostInFlight = new AtomicInteger();
        GameRunner two = new GameRunner(2) {
            @Override
            public GameResult play(GameSpec spec, Consumer<Game> observer) throws InterruptedException {
                mostInFlight.accumulateAndGet(inFlight.incrementAndGet(), Math::max);
                try {
                    return super.play(spec, observer);
                } finally {
                    inFlight.decrementAndGet();
                }
            }
        };
        List<GameResult> together = new ArrayList<>();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<GameResult>> futures = new ArrayList<>();
            for (GameSpec s : specs) {
                futures.add(pool.submit(() -> two.play(s)));
            }
            for (Future<GameResult> f : futures) {
                together.add(f.get());
            }
        } finally {
            pool.shutdownNow();
        }
        Assert.assertEquals(mostInFlight.get(), 2, "the two games played at once");
        for (int i = 0; i < specs.size(); i++) {
            GameResult solo = alone.get(i);
            GameResult side = together.get(i);
            Assert.assertNull(solo.error(), solo.toString());
            Assert.assertNull(side.error(), side.toString());
            Assert.assertEquals(solo.flatMc().get(0).get("seat"), i, solo.flatMc().toString());
            Assert.assertTrue(rollouts(solo, 0) > 0, "the budgeted seat searched: " + solo.flatMc());
            Assert.assertEquals(side.digest(), solo.digest(), "game " + i);
            Assert.assertEquals(side.digestLines(), solo.digestLines(), "game " + i);
            Assert.assertEquals(side.flatMc(), solo.flatMc(), "game " + i);
        }
    }

    /**
     * A seat whose profile gives a decision no budget never searches: its game is the default seats' game, digest for
     * digest, and its tally counts no rollout. The default seats' game carries no tally and prints no wall line.
     */
    @Test(timeOut = 600_000)
    public void aNoBudgetSeatPlaysTheDefaultSeatsGame() throws Exception {
        GameResult off = new GameRunner(1).play(off(7_100_003L, 1));
        Played def = capturingErr(() -> new GameRunner(1).play(GameRunnerTest.spec(7_100_003L, 600)));
        Assert.assertEquals(off.digest(), def.result().digest());
        Assert.assertEquals(off.flatMc().get(0).get("rollouts"), 0L);
        Assert.assertNull(def.result().flatMc(), def.result().toString());
        Assert.assertEquals(def.wallOf(7_100_003L), List.of(), "a game with no flat_mc word prints no wall line");
    }

    /**
     * The zero-rollout seat takes line 0 wherever its search would roll out, so its game has no search line, and its
     * tally, which it still gets, counts no rollout or playout. The seat did reach its search, every decision a
     * zero-rollout one, so it is the zero branch that kept the rollouts at 0 (spec 4.6; Review Focus 5).
     */
    @Test(timeOut = 600_000)
    public void aGameWithNoRolloutAddsNoSearchLine() throws Exception {
        GameResult r = new GameRunner(1).play(zero(7_100_006L, 1));
        Assert.assertNull(r.error(), r.toString());
        Assert.assertEquals(linesStarting(r, "search"), List.of());
        Assert.assertEquals(r.flatMc().size(), 1, r.flatMc().toString());
        Map<String, Object> tally = r.flatMc().get(0);
        Assert.assertEquals(new ArrayList<>(tally.keySet()), FlatMcGame.TALLY_KEYS);
        Assert.assertEquals(tally.get("seat"), 1);
        Assert.assertEquals(tally.get("rollouts"), 0L);
        Assert.assertEquals(tally.get("playouts"), 0L);
        long decisions = (Long) tally.get("decisions");
        Assert.assertTrue(decisions > 0, "the seat reached its search: " + tally);
        Assert.assertEquals(tally.get("reasons"), Map.of("zero-rollout", decisions), tally.toString());
    }

    /**
     * A job whose seat 1 names flat_mc:40:8 under FlatSearchTight, read by JobFile as a job file's lines are, writes a
     * record that ends with the game's digest and its flat_mc tallies: one entry, seat 1's. The same job without those
     * two lines writes today's record.
     */
    @Test(timeOut = 600_000)
    public void aFlatJobRecordEndsWithItsDigestAndTally() throws Exception {
        List<String> lines = new ArrayList<>(List.of("games=1", "seed=7100004", "timeout_s=600"));
        List<Path> precons = Precons.commander();
        for (int i = 0; i < 2; i++) {
            lines.add("seat." + i + ".deck_file=" + precons.get(i));
            lines.add("seat." + i + ".deck_hash=hash" + i);
        }
        List<String> flat = new ArrayList<>(lines);
        flat.add("seat.1.ai=flat_mc:40:8");
        flat.add("seat.1.profile=FlatSearchTight");
        List<Map<String, Object>> out = new ArrayList<>();
        new JobRunner(new GameRunner(1)).run(JobFile.parse(flat), out::add);
        Map<String, Object> rec = out.get(0);
        List<String> expected = new ArrayList<>(GameRecordsTest.RECORD_KEYS);
        expected.add("digest");
        expected.add("flat_mc");
        Assert.assertEquals(new ArrayList<>(rec.keySet()), expected);
        Assert.assertNotEquals(rec.get("end_reason"), "Error", String.valueOf(rec.get("error")));
        Assert.assertTrue(((String) rec.get("digest")).matches("[0-9a-f]{64}"), String.valueOf(rec.get("digest")));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> tallies = (List<Map<String, Object>>) rec.get("flat_mc");
        Assert.assertEquals(tallies.size(), 1, tallies.toString());
        Assert.assertEquals(new ArrayList<>(tallies.get(0).keySet()), FlatMcGame.TALLY_KEYS);
        Assert.assertEquals(tallies.get(0).get("seat"), 1);
        List<Map<String, Object>> plain = new ArrayList<>();
        new JobRunner(new GameRunner(1)).run(JobFile.parse(lines), plain::add);
        Assert.assertEquals(new ArrayList<>(plain.get(0).keySet()), GameRecordsTest.RECORD_KEYS);
    }

    /** A searched game's wall-clock line on stderr names its seed and its searching seat with that seat's profile,
     *  beside the two figures read from the machine's clock (spec 4.7). */
    @Test(timeOut = 600_000)
    public void theWallClockLineNamesTheSeedAndTheSearchingSeat() throws Exception {
        Played p = capturingErr(() -> new GameRunner(1).play(tight(7_100_005L, 1)));
        assertOneWallLine(p.wallOf(7_100_005L),
                "flat_mc wall seed=7100005 search=1:FlatSearchTight slowest_rollout_ms=\\d+ rollouts_over_5s=0");
    }

    /** A pod with a full-count seat, at a one-second limit, times out: its digest is the literal TIMEOUT and it carries
     *  no tally, since nothing live is read after a timeout. */
    @Test(timeOut = 300_000)
    public void aTimedOutSearchedGameCarriesTheLiteralDigest() throws Exception {
        GameResult r = new GameRunner(1).play(withSeat(GameRunnerTest.pod(7_100_007L, 1), 1, "FlatSearch", "flat_mc"));
        Assert.assertEquals(r.endReason(), "Timeout", r.toString());
        Assert.assertEquals(r.digest(), "TIMEOUT");
        Assert.assertNull(r.flatMc(), r.toString());
    }

    /**
     * A searched game stopped before its first priority never finishes: it keeps the literal ERROR digest and no tally,
     * and it still prints its wall line, which the game thread writes on its way out whatever ended the game.
     */
    @Test(timeOut = 300_000)
    public void anUnfinishedSearchedGameKeepsTheLiteralDigestAndPrintsItsWallLine() throws Exception {
        Played p = capturingErr(() -> new GameRunner(1).play(tight(7_100_009L, 1), g -> { }, g -> { }));
        GameResult r = p.result();
        Assert.assertEquals(r.endReason(), "Error", r.toString());
        Assert.assertEquals(r.error(), "game ended without finishing", r.toString());
        Assert.assertEquals(r.digest(), "ERROR");
        Assert.assertNull(r.flatMc(), r.toString());
        assertOneWallLine(p.wallOf(7_100_009L),
                "flat_mc wall seed=7100009 search=1:FlatSearchTight slowest_rollout_ms=\\d+ rollouts_over_5s=0");
    }

    /**
     * Both seats tight: each is registered, so the digest has two search lines in seat order, the tallies two entries
     * reading seats 0 and 1 in that order, each within the cap, and the wall line names both seats.
     */
    @Test(timeOut = 600_000)
    public void twoSearchingSeatsAreListedInSeatOrder() throws Exception {
        Played p = capturingErr(() -> new GameRunner(1).play(
                withSeat(tight(7_100_008L, 0), 1, "FlatSearchTight", "flat_mc:40:8")));
        GameResult r = p.result();
        Assert.assertNull(r.error(), r.toString());
        List<String> search = linesStarting(r, "search\t");
        Assert.assertEquals(search.size(), 2, search.toString());
        Assert.assertTrue(search.get(0).startsWith("search\t0\t"), search.toString());
        Assert.assertTrue(search.get(1).startsWith("search\t1\t"), search.toString());
        Assert.assertEquals(r.flatMc().size(), 2, r.flatMc().toString());
        for (int i = 0; i < 2; i++) {
            Assert.assertEquals(r.flatMc().get(i).get("seat"), i, r.flatMc().toString());
            Assert.assertTrue(rollouts(r, i) <= 44L, r.flatMc().toString());
        }
        assertOneWallLine(p.wallOf(7_100_008L), "flat_mc wall seed=7100008 search=0:FlatSearchTight,1:FlatSearchTight"
                + " slowest_rollout_ms=\\d+ rollouts_over_5s=0");
    }
}
