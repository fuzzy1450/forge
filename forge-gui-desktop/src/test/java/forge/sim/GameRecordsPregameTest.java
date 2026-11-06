package forge.sim;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicReference;

import org.testng.Assert;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import com.google.common.eventbus.Subscribe;

import forge.game.Game;
import forge.game.card.Card;
import forge.game.card.CardView;
import forge.game.event.GameEventCardChangeZone;
import forge.game.player.Player;
import forge.game.zone.ZoneType;
import forge.game.zone.ZoneView;
import forge.sim.GameRunner.GameResult;
import forge.sim.GameRunner.GameSpec;
import forge.sim.GameRunner.SeatSpec;
import forge.trackable.Tracker;
import forge.util.SimScope;

/**
 * The record key seen_not_kept (MTG_DeckMaker tools/combo-ai/f5-guard/SPEC.md, section 3.3): the sorted distinct names of
 * the seat's deck cards that left its hand before turn 1 and were not back in it when turn 1 began.
 * <p>
 * GameWrapper skips mulligans and PlayerControllerForTests always keeps, so three levels. Events with no game: the maps
 * seeded and {@code onZone} called. Real flows stopped at the first-turn hook, with a temp .dck forcing the case. And an
 * independent oracle for those, {@link Oracle}, a subscriber that logs each pre-game move and works the answer out from
 * the log and the hook's hands, not the way Stats does.
 */
public class GameRecordsPregameTest {
    private static final List<JobFile.Seat> TWO_SEATS = List.of(new JobFile.Seat("a.dck", "hash-0", "Default", "default"),
            new JobFile.Seat("b.dck", "hash-1", "Default", "default"));
    private static final ZoneView HAND = new ZoneView(null, ZoneType.Hand);
    private static final ZoneView LIBRARY = new ZoneView(null, ZoneType.Library);
    private static final ZoneView BATTLEFIELD = new ZoneView(null, ZoneType.Battlefield);
    private static final ZoneView EXILE = new ZoneView(null, ZoneType.Exile);
    private static final ZoneView GRAVEYARD = new ZoneView(null, ZoneType.Graveyard);

    private static final String COMMANDER = "Isamaru, Hound of Konda";
    /** Twenty names, so that a key is more than one word. */
    private static final List<String> SPELLS = List.of("Hill Giant", "Grizzly Bears", "Serra Angel", "Air Elemental",
            "Sengir Vampire", "Shivan Dragon", "Craw Wurm", "Mahamoti Djinn", "Lightning Bolt", "Counterspell", "Doom Blade",
            "Divination", "Giant Growth", "Healing Salve", "Disenchant", "Naturalize", "Terror", "Unsummon", "Shock", "Pacifism");

    /** Every temp deck the tests made: deleted after the class. */
    private static final List<Path> TEMP = Collections.synchronizedList(new ArrayList<>());

    @BeforeClass
    public void boot() {
        GameRunner.boot();
    }

    @AfterClass
    public void relaxStrict() {
        SimScope.setStrict(false);
    }

    @AfterClass
    public void deleteTempFiles() throws IOException {
        for (Path p : TEMP) {
            Files.deleteIfExists(p);
        }
        TEMP.clear();
    }

    // ------------------------------------------------------------------ events, no game

    private static GameRecords.Stats tracked(Object... idSeatName) {
        GameRecords.Stats stats = new GameRecords.Stats(2);
        for (int i = 0; i < idSeatName.length; i += 3) {
            stats.cardSeat.put((Integer) idSeatName[i], (Integer) idSeatName[i + 1]);
            stats.cardName.put((Integer) idSeatName[i], (String) idSeatName[i + 2]);
        }
        return stats;
    }

    private static void move(GameRecords.Stats stats, int id, ZoneView from, ZoneView to) {
        stats.onZone(new GameEventCardChangeZone(new CardView(id, new Tracker()), from, to));
    }

    private static Map<String, Object> record(GameRecords.Stats stats) {
        return GameRecords.record(stats, 0, 1L, TWO_SEATS, false, "boom", 1L);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> seat(Map<String, Object> rec, int seat) {
        return ((List<Map<String, Object>>) rec.get("seats")).get(seat);
    }

    private static Object key(GameRecords.Stats stats, int seat) {
        return seat(record(stats), seat).get("seen_not_kept");
    }

    @Test
    public void aHandThatWentBackIsListed() {
        String[] names = {"Sol Ring", "Forest", "Giant Growth", "Mountain", "Arcane Signet", "Doom Blade", "Elvish Mystic"};
        GameRecords.Stats stats = new GameRecords.Stats(2);
        for (int i = 0; i < names.length; i++) {
            stats.cardSeat.put(i, 0);
            stats.cardName.put(i, names[i]);
            move(stats, i, LIBRARY, HAND);
        }
        for (int i = 0; i < names.length; i++) {
            move(stats, i, HAND, LIBRARY);
        }
        Assert.assertEquals(key(stats, 0), List.of("Arcane Signet", "Doom Blade", "Elvish Mystic", "Forest", "Giant Growth",
                "Mountain", "Sol Ring"), "all seven, sorted");
        Assert.assertEquals(key(stats, 1), List.of(), "nothing of the other seat's");
    }

    @Test
    public void aCardRedrawnIntoTheKeptHandIsNotListed() {
        GameRecords.Stats stats = tracked(1, 0, "Sol Ring", 2, 0, "Forest");
        for (int id : new int[] {1, 2}) {
            move(stats, id, LIBRARY, HAND);
            move(stats, id, HAND, LIBRARY);
        }
        move(stats, 1, LIBRARY, HAND);                              // the second hand holds it again
        Assert.assertEquals(key(stats, 0), List.of("Forest"), "out, then in, clears it");
    }

    @Test
    public void theFinalRoundsTuckIsListed() {
        GameRecords.Stats stats = tracked(1, 0, "Sol Ring", 2, 0, "Forest");
        move(stats, 1, LIBRARY, HAND);
        move(stats, 2, LIBRARY, HAND);
        move(stats, 1, HAND, LIBRARY);                              // the tuck: nothing draws after it
        Assert.assertEquals(key(stats, 0), List.of("Sol Ring"));
    }

    @Test
    public void aCardDrawnLaterStaysListedAndGainsItsRow() {
        GameRecords.Stats stats = tracked(1, 0, "Sol Ring");
        move(stats, 1, LIBRARY, HAND);
        move(stats, 1, HAND, LIBRARY);
        stats.turn = 3;
        move(stats, 1, LIBRARY, HAND);
        Assert.assertEquals(key(stats, 0), List.of("Sol Ring"), "after turn 1 onZone leaves the set alone: both say seen");
        GameRecords.CardStat row = stats.cards.get(0).get("Sol Ring");
        Assert.assertNotNull(row, "and the draw has its row");
        Assert.assertEquals(row.firstDrawnTurn, Integer.valueOf(3));
    }

    @Test
    public void leavingTheHandForAnyZoneIsListed() {
        GameRecords.Stats stats = tracked(1, 0, "Leyline of the Void", 2, 0, "Gemstone Caverns", 3, 0, "Fatal Push");
        for (int id : new int[] {1, 2, 3}) {
            move(stats, id, LIBRARY, HAND);
        }
        move(stats, 1, HAND, BATTLEFIELD);
        move(stats, 2, HAND, EXILE);
        move(stats, 3, HAND, GRAVEYARD);
        Assert.assertEquals(key(stats, 0), List.of("Fatal Push", "Gemstone Caverns", "Leyline of the Void"));
    }

    @Test
    public void movesFromTurnOneOnChangeNothing() {
        GameRecords.Stats stats = tracked(1, 0, "Sol Ring", 2, 0, "Forest");
        stats.turn = 1;
        move(stats, 1, HAND, BATTLEFIELD);
        move(stats, 2, LIBRARY, EXILE);
        Assert.assertEquals(key(stats, 0), List.of());
    }

    @Test
    public void aCardNobodyTrackedIsIgnored() {
        GameRecords.Stats stats = tracked(1, 0, "Sol Ring");
        move(stats, 99, HAND, LIBRARY);                             // no paper card: an effect, a token
        stats.onZone(new GameEventCardChangeZone(null, HAND, LIBRARY));
        Assert.assertEquals(key(stats, 0), List.of());
        Assert.assertEquals(key(stats, 1), List.of());
        Assert.assertTrue(stats.notKept.get(0).isEmpty() && stats.notKept.get(1).isEmpty(), "no entry");
    }

    @Test
    public void twoCopiesListTheNameOnce() {
        GameRecords.Stats stats = tracked(1, 0, "Forest", 2, 0, "Forest");
        for (int id : new int[] {1, 2}) {
            move(stats, id, LIBRARY, HAND);
            move(stats, id, HAND, LIBRARY);
        }
        Assert.assertEquals(key(stats, 0), List.of("Forest"));
    }

    /** The view names the state a card shows; the key names the paper card, as the card rows do. */
    @Test
    public void theNameIsTheRowsName() {
        GameRecords.Stats stats = tracked(1, 0, "Fire // Ice");
        CardView face = new CardView(1, new Tracker(), "Fire", "", null);
        stats.onZone(new GameEventCardChangeZone(face, LIBRARY, HAND));
        stats.onZone(new GameEventCardChangeZone(face, HAND, LIBRARY));
        Assert.assertEquals(key(stats, 0), List.of("Fire // Ice"));
    }

    /** {@code []} means recorded, nothing: true of a game that dealt no hand. Absent means not recorded: the fallback's
     *  contract is to read no collection this subscriber keeps, so it cannot know. */
    @Test
    public void aStatsWithNoGameCarriesAnEmptyListAndTheFallbackNone() {
        Map<String, Object> rec = GameRecords.record(new GameRecords.Stats(2), 4, 11L, TWO_SEATS, false, "boom", 7L);
        Assert.assertEquals(seat(rec, 0).get("seen_not_kept"), List.of());
        Assert.assertEquals(seat(rec, 1).get("seen_not_kept"), List.of());
        Map<String, Object> fallback = GameRecords.fallbackRecord(new GameRecords.Stats(2), 4, 11L, TWO_SEATS, false, "boom",
                new IllegalStateException("x"), 7L);
        Assert.assertFalse(seat(fallback, 0).containsKey("seen_not_kept"), "the fallback writes no key");
        Assert.assertFalse(seat(fallback, 1).containsKey("seen_not_kept"), "the fallback writes no key");
    }

    // ------------------------------------------------------------------ real flows, stopped at the hook

    private static Path deck(List<String> main) throws IOException {
        Path f = Files.createTempFile("pregame", ".dck");
        TEMP.add(f);
        List<String> lines = new ArrayList<>(List.of("[metadata]", "Name=" + f.getFileName(), "[Commander]", "1 " + COMMANDER,
                "[Main]"));
        lines.addAll(main);
        Files.write(f, lines, StandardCharsets.UTF_8);
        return f;
    }

    /** 2 lands in 99: nearly every hand has no land, and a seat mulligans till it stops at three. */
    private static Path fewLands() throws IOException {
        List<String> main = new ArrayList<>(List.of("2 Plains"));
        for (int i = 0; i < SPELLS.size(); i++) {
            main.add((i < SPELLS.size() - 1 ? 5 : 2) + " " + SPELLS.get(i));
        }
        return deck(main);
    }

    private static GameSpec mirror(long seed, int seats, Path deck) {
        SeatSpec s = new SeatSpec(deck, "Default", "default");
        return new GameSpec(seed, 120, Collections.nCopies(seats, s));
    }

    /** A pre-game move, as the bus delivered it. */
    private record Move(int id, ZoneType from, ZoneType to) { }

    /** The answer worked out apart from Stats: from every pre-game move, the cards that ever reached a hand, less what
     *  the hook sees in hand; named by the card's paper card, read from the game at the hook. Public for the event bus. */
    public static final class Oracle {
        final List<Move> moves = new ArrayList<>();
        final Map<Integer, Integer> seatOfCard = new HashMap<>();
        final Map<Integer, String> paperName = new HashMap<>();
        final Set<Integer> heldAtHook = new HashSet<>();
        final List<List<String>> battlefield = new ArrayList<>();
        final List<List<String>> exile = new ArrayList<>();
        final List<Integer> handSize = new ArrayList<>();
        Integer firstSeat;
        boolean atHook;

        @Subscribe
        public void onZone(GameEventCardChangeZone ev) {
            if (!atHook && ev.card() != null) {
                moves.add(new Move(ev.card().getId(), ev.from() == null ? null : ev.from().zoneType(),
                        ev.to() == null ? null : ev.to().zoneType()));
            }
        }

        void atFirstTurn(Game g) {
            atHook = true;
            firstSeat = GameRunner.seatOf(g.getStartingPlayer().getName());
            for (int i = 0; i < g.getRegisteredPlayers().size(); i++) {
                battlefield.add(new ArrayList<>());
                exile.add(new ArrayList<>());
                handSize.add(0);
            }
            for (Player p : g.getRegisteredPlayers()) {
                int seat = GameRunner.seatOf(p.getName());
                for (Card c : p.getAllCards()) {
                    if (c.getPaperCard() != null) {
                        seatOfCard.put(c.getId(), seat);
                        paperName.put(c.getId(), c.getPaperCard().getName());
                    }
                }
                for (Card c : p.getCardsIn(ZoneType.Hand)) {
                    heldAtHook.add(c.getId());
                }
                for (Card c : p.getCardsIn(ZoneType.Battlefield)) {
                    battlefield.get(seat).add(c.getPaperCard().getName());
                }
                for (Card c : p.getCardsIn(ZoneType.Exile)) {
                    exile.get(seat).add(c.getPaperCard().getName());
                }
                handSize.set(seat, p.getCardsIn(ZoneType.Hand).size());
            }
        }

        List<String> expected(int seat) {
            Set<Integer> reached = new HashSet<>();
            for (Move m : moves) {
                if (m.to() == ZoneType.Hand) {
                    reached.add(m.id());
                }
            }
            reached.removeAll(heldAtHook);
            TreeSet<String> names = new TreeSet<>();
            for (int id : reached) {
                if (Integer.valueOf(seat).equals(seatOfCard.get(id))) {
                    names.add(paperName.get(id));
                }
            }
            return new ArrayList<>(names);
        }
    }

    /** One game stopped at the hook, read the way JobRunner reads a played one, beside the oracle's answer. */
    private record Stopped(GameResult result, Map<String, Object> rec, Oracle oracle) {
        int mulligans(int seat) {
            return (Integer) seat(rec, seat).get("mulligans");
        }

        Object key(int seat) {
            return seat(rec, seat).get("seen_not_kept");
        }

        int seats() {
            return ((List<?>) rec.get("seats")).size();
        }

        void matchesTheOracle() {
            for (int i = 0; i < seats(); i++) {
                Assert.assertEquals(key(i), oracle.expected(i), "seat " + i + " at seed " + result.seed());
            }
        }
    }

    private static Stopped stopAtFirstTurn(GameRunner runner, GameSpec spec) throws Exception {
        AtomicReference<GameRecords.Stats> stats = new AtomicReference<>();
        Oracle oracle = new Oracle();
        GameResult r = runner.play(spec, game -> {
            stats.set(new GameRecords.Stats(game, spec.seats().size()));
            game.subscribeToEvents(stats.get());
            game.subscribeToEvents(oracle);
        }, oracle::atFirstTurn);
        Assert.assertTrue(oracle.atHook, "the game reached the hook: " + r.endReason() + " " + r.error());
        return new Stopped(r, GameRecords.record(stats.get(), 0, spec.seed(), GameRecordsTest.seatsOf(spec), r.timedOut(),
                r.error(), r.ms()), oracle);
    }

    @Test(timeOut = 300_000)
    public void aRealLondonMulliganMatchesTheOracle() throws Exception {
        GameRunner runner = new GameRunner(1);
        Path deck = fewLands();
        Stopped found = null;
        for (long seed = 1; seed <= 40 && found == null; seed++) {
            Stopped s = stopAtFirstTurn(runner, mirror(seed, 2, deck));
            if (s.mulligans(0) >= 2 || s.mulligans(1) >= 2) {
                found = s;
            }
        }
        Assert.assertNotNull(found, "no seed of forty gave a seat two mulligans");
        found.matchesTheOracle();
        int seat = found.mulligans(0) >= 2 ? 0 : 1;
        Assert.assertFalse(((List<?>) found.key(seat)).isEmpty(), "a seat that sent two hands back saw cards it did not keep");
        Assert.assertEquals((int) found.oracle().handSize.get(seat), 7 - found.mulligans(seat), "London: a card a mulligan");
    }

    /** Three seats: the first mulligan of a pod is free, seven cards back with no tuck. */
    @Test(timeOut = 300_000)
    public void aPodsFreeMulliganMatchesTheOracle() throws Exception {
        GameRunner runner = new GameRunner(1);
        Path deck = fewLands();
        Stopped found = null;
        for (long seed = 1; seed <= 40 && found == null; seed++) {
            Stopped s = stopAtFirstTurn(runner, mirror(seed, 3, deck));
            if (s.mulligans(0) >= 1 || s.mulligans(1) >= 1 || s.mulligans(2) >= 1) {
                found = s;
            }
        }
        Assert.assertNotNull(found, "no seed of forty gave a seat a mulligan");
        found.matchesTheOracle();
        for (int seat = 0; seat < 3; seat++) {
            if (found.mulligans(seat) >= 1) {
                Assert.assertFalse(((List<?>) found.key(seat)).isEmpty(), "seat " + seat);
                Assert.assertEquals((int) found.oracle().handSize.get(seat), 8 - found.mulligans(seat), "the first is free");
            }
        }
    }

    /** The AI begins with every Leyline it holds. At a seat that kept seven, what left its hand is exactly what the hook
     *  sees on its battlefield. Today such a card may have no row at all: the turn-1 snapshot reads the hand. */
    @Test(timeOut = 300_000)
    public void aLeylineInPlayBeforeTurnOneIsListed() throws Exception {
        GameRunner runner = new GameRunner(1);
        Path deck = deck(List.of("39 Plains", "12 Leyline of the Void", "12 Leyline of Sanctity", "12 Leyline of Vitality",
                "12 Leyline of Anticipation", "12 Leyline of Lightning"));
        int seen = 0;
        for (long seed = 1; seed <= 40 && seen < 2; seed++) {
            Stopped s = stopAtFirstTurn(runner, mirror(seed, 2, deck));
            s.matchesTheOracle();
            for (int seat = 0; seat < 2; seat++) {
                if (s.mulligans(seat) == 0) {
                    List<String> inPlay = new ArrayList<>(new TreeSet<>(s.oracle().battlefield.get(seat)));
                    Assert.assertEquals(s.key(seat), inPlay, "seat " + seat + " at seed " + seed);
                    seen += inPlay.isEmpty() ? 0 : 1;
                }
            }
        }
        Assert.assertTrue(seen >= 2, "no seat of forty seeds kept seven and began with a Leyline: " + seen);
    }

    /** Gemstone Caverns, for a seat on the draw: it leaves the hand for the battlefield, and the card it exiles for exile. */
    @Test(timeOut = 300_000)
    public void gemstoneCavernsAndWhatItExiledAreListed() throws Exception {
        GameRunner runner = new GameRunner(1);
        List<String> main = new ArrayList<>(List.of("30 Plains", "20 Gemstone Caverns"));
        for (int i = 0; i < 10; i++) {
            main.add("5 " + SPELLS.get(i));
        }
        Path deck = deck(main);
        boolean seen = false;
        for (long seed = 1; seed <= 40 && !seen; seed++) {
            Stopped s = stopAtFirstTurn(runner, mirror(seed, 2, deck));
            s.matchesTheOracle();
            int onTheDraw = 1 - s.oracle().firstSeat;
            List<String> exiled = s.oracle().exile.get(onTheDraw);
            if (s.oracle().battlefield.get(onTheDraw).contains("Gemstone Caverns")) {
                Assert.assertEquals(exiled.size(), 1, "it exiles one card of the hand");
                List<?> key = (List<?>) s.key(onTheDraw);
                Assert.assertTrue(key.contains("Gemstone Caverns"), String.valueOf(key));
                Assert.assertTrue(key.contains(exiled.get(0)), exiled + " in " + key);
                seen = true;
            }
            Assert.assertTrue(s.oracle().battlefield.get(s.oracle().firstSeat).isEmpty(), "!PlayFirst: never the seat going first");
        }
        Assert.assertTrue(seen, "no seed of forty put Gemstone Caverns in play for the seat on the draw");
    }

    /** The key's proof at its smallest: at a seed with a mulligan, the game with Stats attached is the game without it. */
    @Test(timeOut = 600_000)
    public void theRecordChangesNoGame() throws Exception {
        GameRunner runner = new GameRunner(1);
        GameSpec spec = null;
        for (long seed = 7_100_000L; seed < 7_100_060L && spec == null; seed++) {
            GameSpec candidate = GameRunnerTest.spec(seed, 300);
            Stopped s = stopAtFirstTurn(runner, candidate);
            if (s.mulligans(0) + s.mulligans(1) > 0) {
                spec = candidate;
            }
        }
        Assert.assertNotNull(spec, "no seed of sixty had a mulligan");
        GameResult plain = runner.play(spec);
        GameRecordsTest.Recorded recorded = GameRecordsTest.play(runner, spec, 0);
        Assert.assertEquals(recorded.result().digest(), plain.digest());
        Assert.assertEquals(recorded.result().digestLines(), plain.digestLines());
        Assert.assertTrue(GameRunnerTest.DECIDED.contains(plain.endReason()), plain.toString());
        int listed = 0;
        for (int i = 0; i < 2; i++) {
            listed += ((List<?>) seat(recorded.rec(), i).get("seen_not_kept")).size();
        }
        Assert.assertTrue(listed > 0, "the game with the mulligan carries a key");
    }
}
