package forge.ai.combo;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertSame;
import static org.testng.Assert.assertTrue;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import org.testng.annotations.Test;

import forge.ai.AITest;
import forge.ai.AiController;
import forge.ai.AiPlayDecision;
import forge.ai.LobbyPlayerAi;
import forge.ai.LoopTracker;
import forge.card.mana.ManaAtom;
import forge.game.Game;
import forge.game.GameEndReason;
import forge.game.GameLogEntry;
import forge.game.ability.ApiType;
import forge.game.card.Card;
import forge.game.card.CounterEnumType;
import forge.game.mana.Mana;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.spellability.SpellAbilityStackInstance;
import forge.game.zone.ZoneType;
import forge.util.MyRandom;

/**
 * The stock AI's plays on a topped-up pool, and Thrasios, Triton Hero, the loop's dig (spec
 * 2026-10-29-combo-aware-ai-loops-design.md section 6.4 steps 2 and 3, section 6.5; the plan's Task B6, R2, R8, R20 and
 * R22). Below the stock target the driver refills, at it the stock walk plays, paying from the pool; when the walk
 * chooses nothing Thrasios digs, one activation a decision, refilling to its {4} first, while our library holds more
 * than four cards, a card in it would add to our board, and our board is short of five times lethal. A dig toward a
 * library-out winner goes on whatever the library holds: with Thassa's Oracle in hand to our devotion to blue and
 * Oracle's own {U}{U}, the driver holding Oracle from the stock AI meanwhile and casting it there; with Laboratory
 * Maniac on ours to empty and one draw more; below the floor only while we can win and no opponent has mana to answer
 * it. Unless a test says otherwise the board is Kinnan's list registered, the tracker reading the shipped lines, every
 * seat on the Default profile, the AI in its own Main 1 with no AI deadline, Kinnan and Thrasios (ready) and Basalt
 * Monolith on our battlefield, our library built card by card, top first, and the opponent at 20 with nothing.
 * {@link #play} runs the whole game loop, where the stock AI decides too; {@link #drive} plays the driver's own
 * decisions with a walk that never chooses anything, and {@link #slot} the go-off slot's alone.
 */
public class ComboLoopThrasiosAiTest extends AITest {
    private static final String KINNAN = "Kinnan, Bonder Prodigy";
    private static final String BASALT = "Basalt Monolith";
    private static final String NYXBLOOM = "Nyxbloom Ancient";
    private static final String THRASIOS = "Thrasios, Triton Hero";
    private static final String ORACLE = "Thassa's Oracle";
    private static final String CONSULTATION = "Demonic Consultation";
    private static final String LAB = "Laboratory Maniac";
    private static final String MEMNITE = "Memnite";
    private static final String ENDLESS_ONE = "Endless One";
    private static final String COUNTERSPELL = "Counterspell";
    private static final String DIVINATION = "Divination";
    private static final String ISLAND = "Island";
    private static final String FOREST = "Forest";
    private static final String JIN = "Jin-Gitaxias, Core Augur";
    private static final String NEZAHAL = "Nezahal, Primal Tide";
    private static final String RHYSTIC = "Rhystic Study";
    /** The rock untap of Kinnan and Basalt Monolith. */
    private static final String KINNAN_BASALT = "129-4131";
    /** The rock untap of Basalt Monolith and Nyxbloom Ancient. */
    private static final String BASALT_NYXBLOOM = "3430-4131";
    /** The game-loop steps a stepping test may take: a dig of twenty-two activations at four cycles each, and slack. */
    private static final int MAX_STEPS = 5_000;
    /** The driver's actions a driving test may take. */
    private static final int MAX_ACTIONS = 1_000;
    /** Kinnan's list beside its commander: every piece of the 19 loop rows, the sinks and lands. */
    private static final List<String> KINNANS_LIST = kinnansList();
    /** The opponents' hosers of Thassa's Oracle's win: two disable its entering trigger, two bar its cast (Iona,
     *  Shield of Emeria naming blue; Void Winnower, Oracle's mana value 2 being even). */
    private static final List<String> ORACLE_HOSERS = List.of("Torpor Orb", "Hushbringer", "Iona, Shield of Emeria",
            "Void Winnower");

    private Game game;
    private Player ai;
    private Player opp;
    private ComboTracker t;
    private LoopDriver driver;
    private Card basalt;
    private Card thrasios;
    /** The random draws made inside the driver's decisions while {@link #drive} or {@link #slot} ran. */
    private long decisionDraws;
    /** The times {@link #drive} found the go-off slot pass the decision on (null): the walk's turns. */
    private int asks;

    /** One stack item of ours as the game loop first put it there: the ability, its host, our library's size, our
     *  attack power (LoopTracker's) and our hand's names then, and our pool after the step before. */
    private record Seen(SpellAbility sa, Card host, int library, int power, List<String> hand, int poolBefore) {
        boolean dig() {
            return THRASIOS.equals(ComboTracker.nameOf(host)) && !sa.isSpell() && sa.getApi() == ApiType.Scry;
        }

        boolean cast(final String name) {
            return sa.isSpell() && name.equals(ComboTracker.nameOf(host));
        }
    }

    /** An action the driver returned, with its host, our pool just before it and our library's size then. */
    private record Act(SpellAbility sa, Card host, int poolBefore, int library) {
        boolean dig() {
            return THRASIOS.equals(ComboTracker.nameOf(host)) && !sa.isSpell() && sa.getApi() == ApiType.Scry;
        }
    }

    private static List<String> kinnansList() {
        final Set<String> names = new LinkedHashSet<>();
        for (final ComboLines.Line l : ComboLoopBoards.shippedLoopLines(LoopRows.ids().toArray(new String[0]))) {
            names.addAll(l.names());
        }
        names.remove(KINNAN);
        names.addAll(List.of(LoopSinks.BALLISTA, THRASIOS, FOREST, ISLAND));
        return List.copyOf(names);
    }

    /** The seats in a two-player game, every one on the Default profile, the AI (player 1) in its own Main 1 with no
     *  AI deadline and Kinnan's list registered; nothing placed. */
    private void game() {
        board(initAndCreateGame());
    }

    /** The seats of a three-player game, as {@link #game}: the AI is player 1, its opponents players 0 and 2. */
    private void pod() {
        board(initAndCreateThreePlayerGame());
    }

    private void board(final Game g) {
        game = g;
        ComboLoopBoards.noDeadline(game);
        ai = game.getPlayers().get(1);
        opp = game.getPlayers().get(0);
        for (final Player p : game.getPlayers()) {
            ((LobbyPlayerAi) p.getLobbyPlayer()).setAiProfile("Default");
        }
        ComboLoopBoards.deck(ai, KINNAN, KINNANS_LIST.toArray(new String[0]));
        t = ComboLoopBoards.aiOf(ai).getComboTracker();
        driver = ComboLoopBoards.aiOf(ai).getLoopDriver();
        decisionDraws = 0;
        asks = 0;
    }

    /** The default board: {@link #rock} and Thrasios, ready, on our battlefield. */
    private void start() {
        game();
        rock();
        thrasios = ComboLoopBoards.ready(addCard(THRASIOS, ai));
    }

    /** {@code c} under the opponent's Turn to Frog, put on the stack and resolved (its payment aside): it loses all its
     *  abilities until end of turn. */
    private void frog(final Card c) {
        final Card frog = createCard("Turn to Frog", opp);
        frog.setGameTimestamp(game.getNextTimestamp());
        game.getStackZone().add(frog);
        final SpellAbility sa = frog.getFirstSpellAbility();
        sa.setActivatingPlayer(opp);
        sa.getTargets().add(c);
        game.getStack().add(sa);
        game.getStack().resolveStack();
        assertTrue(game.getStack().isEmpty(), "Turn to Frog is still on the stack");
    }

    /** Kinnan, ready, and Basalt Monolith on our battlefield: the rock untap 129-4131, one mana a cycle. */
    private void rock() {
        ComboLoopBoards.ready(addCard(KINNAN, ai));
        basalt = addCard(BASALT, ai);
    }

    /** {@code n} cards of {@code name} put into our library below what it holds: the library is built top first. */
    private void library(final String name, final int n) {
        for (int i = 0; i < n; i++) {
            addCardToZone(name, ai, ZoneType.Library);
        }
    }

    /** Two Counterspells in our hand and two untapped Islands of ours: a stock target of 2 the stock AI never
     *  spends. */
    private void counterspells() {
        addCardToZone(COUNTERSPELL, ai, ZoneType.Hand);
        addCardToZone(COUNTERSPELL, ai, ZoneType.Hand);
        addCard(ISLAND, ai);
        addCard(ISLAND, ai);
    }

    /** {@code n} colourless mana into our pool, as a rock's tap makes it. */
    private void floatColourless(final int n) {
        for (int i = 0; i < n; i++) {
            ai.getManaPool().addMana(new Mana((byte) ManaAtom.COLORLESS, basalt, null, ai));
        }
    }

    private int pool() {
        return ComboLoopBoards.pool(ai);
    }

    private int library() {
        return ai.getCardsIn(ZoneType.Library).size();
    }

    /** Our attack power, as LoopTracker's snapshot reads it (the five-times-lethal test's). */
    private int power() {
        return LoopTracker.Snapshot.of(ai).ownPower();
    }

    /** Our cards' names in that zone, in its order. */
    private List<String> names(final ZoneType zone) {
        final List<String> out = new ArrayList<>();
        for (final Card c : ai.getCardsIn(zone)) {
            out.add(ComboTracker.nameOf(c));
        }
        return out;
    }

    /** How many of our cards in that zone are named {@code name}. */
    private int count(final ZoneType zone, final String name) {
        int n = 0;
        for (final Card c : ai.getCardsIn(zone)) {
            if (name.equals(ComboTracker.nameOf(c))) {
                n++;
            }
        }
        return n;
    }

    /** Our card on our battlefield by its printed name (ComboTracker.nameOf), or null. */
    private Card onOurBattlefield(final String name) {
        for (final Card c : ai.getCardsIn(ZoneType.Battlefield)) {
            if (name.equals(ComboTracker.nameOf(c))) {
                return c;
            }
        }
        return null;
    }

    /** The deck line of that id. */
    private ComboLines.Line line(final String id) {
        for (final ComboLines.Line l : t.deckLines()) {
            if (l.variant().id().equals(id)) {
                return l;
            }
        }
        throw new AssertionError("the deck holds no line " + id);
    }

    private static List<String> ids(final List<ComboLines.Line> lines) {
        return lines.stream().map(l -> l.variant().id()).collect(Collectors.toList());
    }

    /** The card's own {T} mana ability, found here and not through the driver. */
    private static SpellAbility manaOf(final Card c) {
        for (final SpellAbility sa : c.getManaAbilities()) {
            if (sa.getApi() == ApiType.Mana) {
                return sa;
            }
        }
        throw new AssertionError(c + " has no mana ability");
    }

    /** Thrasios's {4}, its Scry ability, found here and not through LoopSinks. */
    private static SpellAbility digOf(final Card c) {
        for (final SpellAbility sa : c.getNonManaAbilities()) {
            if (sa.isActivatedAbility() && sa.getApi() == ApiType.Scry) {
                return sa;
            }
        }
        throw new AssertionError(c + " has no Scry ability");
    }

    /** The card's own cast, its owner the activator. */
    private static SpellAbility castOf(final Card c) {
        final SpellAbility sa = c.getFirstSpellAbility();
        sa.setActivatingPlayer(c.getOwner());
        return sa;
    }

    private static <T> List<T> only(final List<T> items, final Predicate<T> kind) {
        return items.stream().filter(kind).collect(Collectors.toList());
    }

    /** How many entries of the game log read exactly {@code message}. */
    private int logged(final String message) {
        int n = 0;
        for (final GameLogEntry e : game.getGameLog().getAllEntries()) {
            if (message.equals(e.message())) {
                n++;
            }
        }
        return n;
    }

    /** The AI's cast entry for the walk's list (AiController.canPlayAndPayFor, private), piece 1's tests' idiom. */
    private AiPlayDecision canPlayAndPayFor(final SpellAbility sa) {
        try {
            final Method m = AiController.class.getDeclaredMethod("canPlayAndPayFor", SpellAbility.class);
            m.setAccessible(true);
            return (AiPlayDecision) m.invoke(ComboLoopBoards.aiOf(ai), sa);
        } catch (final ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    /** {@code sa} played through the AI's own play path, the stack then resolved. */
    private void playAndResolve(final SpellAbility sa) {
        ai.getController().playChosenSpellAbility(sa);
        while (!game.getStack().isEmpty()) {
            game.getStack().resolveStack();
        }
    }

    /** Plays the go-off slot's decisions (LoopDriver.mainPhaseAction) one at a time, each through the AI's own play
     *  path with the stack then resolved, until it returns null or {@code max} actions; the state-based actions are
     *  checked before each decision, as the game loop checks them before every priority. */
    private List<SpellAbility> slot(final int max) {
        final List<SpellAbility> acts = new ArrayList<>();
        while (acts.size() < max) {
            game.getAction().checkStateEffects(true);
            final long before = MyRandom.callCount();
            final SpellAbility sa = driver.mainPhaseAction(List.of());
            decisionDraws += MyRandom.callCount() - before;
            if (sa == null) {
                break;
            }
            acts.add(sa);
            playAndResolve(sa);
        }
        return acts;
    }

    /** Plays the driver's decisions with a walk that never chooses anything: the go-off slot first, and when it
     *  passes the decision on (null, counted into {@link #asks}) the slot after the walk (LoopDriver.afterStockWalk),
     *  as the AiController asks it when its walk chose nothing; each action through the AI's own play path with the
     *  stack then resolved, until neither returns one, the game is over or {@code max} actions. The state-based
     *  actions are checked before each decision; the random draws made inside the driver's decisions are added to
     *  {@link #decisionDraws}. */
    private List<Act> drive(final int max) {
        final List<Act> acts = new ArrayList<>();
        while (acts.size() < max && !game.isGameOver()) {
            game.getAction().checkStateEffects(true);
            final long before = MyRandom.callCount();
            SpellAbility sa = driver.mainPhaseAction(List.of());
            if (sa == null) {
                asks++;
                sa = driver.afterStockWalk();
            }
            decisionDraws += MyRandom.callCount() - before;
            if (sa == null) {
                break;
            }
            acts.add(new Act(sa, sa.getHostCard(), pool(), library()));
            playAndResolve(sa);
        }
        return acts;
    }

    /** Steps the whole game loop (PhaseHandler.mainLoopStep: every seat decides, the stock AI included) while the game
     *  is not over and {@code done} does not hold, at most {@link #MAX_STEPS} steps; returns our stack items in the
     *  order the loop put them there, each as first seen. */
    private List<Seen> play(final Predicate<Game> done) {
        final List<Seen> seen = new ArrayList<>();
        final Set<Integer> ids = new HashSet<>();
        int poolBefore = pool();
        for (int step = 0; step < MAX_STEPS && !game.isGameOver() && !done.test(game); step++) {
            game.getPhaseHandler().mainLoopStep();
            for (final SpellAbilityStackInstance si : game.getStack()) {
                final Card host = si.getSourceCard();
                if (ids.add(si.getId()) && host != null && ai.equals(si.getActivatingPlayer())) {
                    seen.add(new Seen(si.getSpellAbility(), host, library(), power(), names(ZoneType.Hand),
                            poolBefore));
                }
            }
            poolBefore = pool();
        }
        return seen;
    }

    private void assertWonBy(final String card, final List<Seen> seen) {
        assertTrue(game.isGameOver(), "the game went on; our stack items: " + seen);
        assertTrue(ai.hasWon(), "the AI lost: " + game.getOutcome().getWinCondition());
        assertEquals(game.getOutcome().getWinCondition(), GameEndReason.WinsGameSpellEffect);
        assertEquals(ai.getOutcome().altWinSourceName, card, "the card that won");
    }

    // ---- the plan's tests ----

    // Section 6.4 step 2, the stock AI's plays with the pool topped up: Kinnan and Basalt Monolith, no Thrasios,
    // Endless One in hand. Through the whole game loop the pool is built to 200 and the stock AI casts Endless One from
    // it at the stock X search's cap: it enters with 99 counters, 101 mana left. The X search's time over the 200 pool
    // is this test's duration, recorded in the ledger against the plan's 2,000 ms bar.
    @Test
    public void aStockXSpellWithA200Pool() {
        game();
        rock();
        addCardToZone(ENDLESS_ONE, ai, ZoneType.Hand);
        final List<Seen> seen = play(g -> onOurBattlefield(ENDLESS_ONE) != null && g.getStack().isEmpty());
        final Card cast = onOurBattlefield(ENDLESS_ONE);
        assertNotNull(cast, "the stock AI cast Endless One: " + seen);
        assertEquals(cast.getCounters(CounterEnumType.P1P1), 99, "the stock X search's cap");
        final List<Seen> casts = only(seen, s -> s.cast(ENDLESS_ONE));
        assertEquals(casts.size(), 1);
        assertEquals(casts.get(0).poolBefore(), LoopPool.TARGET, "cast from the pool of 200");
        assertEquals(pool(), LoopPool.TARGET - 99, "paid from the pool");
    }

    // Section 6.4 steps 2 and 3, one activation at a time: a library of six Memnites above twenty Islands, an empty
    // hand. Through the whole game loop Thrasios digs whenever the stock AI has nothing to play, and the stock AI casts
    // each Memnite it draws ({0}) before the next activation: at every activation no Memnite is in our hand, and after
    // the go-off every Memnite that left the library is on our battlefield, cast. (The stock scry sends some Memnites
    // to the bottom while we have few lands.)
    @Test
    public void thrasiosDrawsOneAtATimeAndTheStockCasts() {
        start();
        library(MEMNITE, 6);
        library(ISLAND, 20);
        final List<Seen> seen = play(g -> !g.getPhaseHandler().is(PhaseType.MAIN1));
        assertFalse(game.getPhaseHandler().is(PhaseType.MAIN1), "Main 1 ended");
        final List<Seen> digs = only(seen, Seen::dig);
        assertFalse(digs.isEmpty(), "Thrasios dug: " + seen);
        for (final Seen s : digs) {
            assertFalse(s.hand().contains(MEMNITE), "a Memnite drawn and not cast before the next activation: " + s);
        }
        final int drawn = 6 - count(ZoneType.Library, MEMNITE);
        assertTrue(drawn > 0, "no Memnite drawn: " + seen);
        assertEquals(count(ZoneType.Battlefield, MEMNITE), drawn, "every Memnite drawn is on our battlefield");
        assertEquals(only(seen, s -> s.cast(MEMNITE)).size(), drawn, "each cast");
        assertTrue(library() <= LoopSinks.LIBRARY_FLOOR || count(ZoneType.Library, MEMNITE) == 0,
                "Thrasios stopped with a Memnite to dig for above the floor: " + library());
    }

    // The library floor (section 6.4: no draw that would leave 3 cards or fewer): a library of five Memnites. One
    // activation takes it from five to four, the drawn Memnite cast; then none, though a Memnite is left to dig for.
    @Test
    public void thrasiosStopsAtTheFloor() {
        start();
        library(MEMNITE, 5);
        final List<Seen> seen = play(g -> !g.getPhaseHandler().is(PhaseType.MAIN1));
        final List<Seen> digs = only(seen, Seen::dig);
        assertEquals(digs.size(), 1, "one activation: " + seen);
        assertEquals(digs.get(0).library(), 5, "from five");
        assertEquals(library(), LoopSinks.LIBRARY_FLOOR, "to four");
        assertEquals(count(ZoneType.Battlefield, MEMNITE), 1, "the drawn Memnite cast");
        assertTrue(LoopSinks.digWorth(ai, line(KINNAN_BASALT)), "Memnites left to dig for");
        assertNull(LoopSinks.thrasios(ai, line(KINNAN_BASALT)), "then none: the floor");
    }

    // Thrasios digs only while the library holds a card that adds to our board (section 6.4): a library of ten
    // Counterspells, which our two Islands could pay for but which add nothing, so no activation, nor a go-off for one.
    // One Memnite at the bottom of it makes the dig worth it: what the library holds, never its order.
    @Test
    public void thrasiosNeverDigsForNothing() {
        start();
        addCard(ISLAND, ai);
        addCard(ISLAND, ai);
        library(COUNTERSPELL, 10);
        final ComboLines.Line l = line(KINNAN_BASALT);
        assertFalse(LoopSinks.digWorth(ai, l));
        assertNull(LoopSinks.thrasios(ai, l));
        final List<Seen> seen = play(g -> !g.getPhaseHandler().is(PhaseType.MAIN1));
        assertTrue(only(seen, Seen::dig).isEmpty(), "Thrasios dug for nothing: " + seen);
        assertEquals(library(), 10);
        addCardToZone(MEMNITE, ai, ZoneType.Library);
        assertTrue(LoopSinks.digWorth(ai, l), "a Memnite at the bottom");
        assertSame(LoopSinks.thrasios(ai, l), digOf(thrasios));
    }

    // Thrasios stops once the board is five times lethal (section 6.4, Evan: "until the board is 5x lethal"): the
    // opponent at 2, a library of twenty Memnites. Kinnan's 2 and Thrasios's 1, then a Memnite cast for each
    // activation: seven activations, each made below 10 power, and none once our power reaches 10, five times the
    // opponent's 2, though Memnites are left to dig for.
    @Test
    public void thrasiosStopsAtFiveTimesLethal() {
        start();
        opp.setLife(2, null);
        library(MEMNITE, 20);
        final List<Seen> seen = play(g -> !g.getPhaseHandler().is(PhaseType.MAIN1));
        final List<Seen> digs = only(seen, Seen::dig);
        for (final Seen s : digs) {
            assertTrue(s.power() < 10, "an activation on a board five times lethal: " + s);
        }
        assertEquals(digs.size(), 7, "Memnites from 3 power to 10: " + seen);
        assertEquals(power(), 10);
        assertTrue(LoopTracker.Snapshot.of(ai).isDecisive());
        assertTrue(LoopSinks.digWorth(ai, line(KINNAN_BASALT)), "Memnites left to dig for");
        assertNull(LoopSinks.thrasios(ai, line(KINNAN_BASALT)), "Thrasios's own stop");
    }

    // What adds to our board (section 6.4: a card that would raise our attack power, or Chromatic Orrery), read from a
    // card's own parts: a creature; a spell that puts a creature onto the battlefield or pumps; a ManaConvert static.
    // Not a counterspell, a mana rock, a draw, nor a spell that puts a land onto the battlefield (R20). Beyond the
    // plan's list: a part that is no spell's root counts (Last March of the Ents draws, then puts creatures from our
    // hand onto the battlefield); a creature tutor to another zone (Worldly Tutor, to the top of our library) and a
    // pump that is a curse (Disfigure's -2/-2) do not.
    @Test
    public void addsToBoardReadsCreaturesPumpsAndOrrery() {
        game();
        for (final String name : List.of(LoopSinks.BALLISTA, "Ulamog, the Infinite Gyre", "Finale of Devastation",
                "Green Sun's Zenith", "Overwhelming Stampede", "Craterhoof Behemoth", "Chromatic Orrery",
                "Last March of the Ents")) {
            assertTrue(LoopSinks.addsToBoard(addCardToZone(name, ai, ZoneType.Library)), name);
        }
        for (final String name : List.of(COUNTERSPELL, "Sol Ring", "Brainstorm", "Cultivate", "Worldly Tutor",
                "Disfigure")) {
            assertFalse(LoopSinks.addsToBoard(addCardToZone(name, ai, ZoneType.Library)), name);
        }
    }

    // R2: an immediate Consultation win is never traded for a dig. A deck holding Thassa's Oracle and Demonic
    // Consultation (742-1295) beside Kinnan's list; Kinnan, Basalt and Thrasios on ours, Oracle and Consultation in
    // hand, Island, Island and Swamp. The dig toward Oracle could run, but goOffFirst's Oracle cast is still WillPlay.
    // With Consultation in the library (the go-off not ready, its line still executable) the hold is piece 1's own,
    // not the driver's; with piece 1's line read out of the tracker, the driver holds Oracle for the dig.
    @Test
    public void piece1sOracleGoOffIsNotHeld() {
        start();
        ComboLoopBoards.deck(ai, null, ORACLE, CONSULTATION);
        final Card oracle = addCardToZone(ORACLE, ai, ZoneType.Hand);
        final Card consultation = addCardToZone(CONSULTATION, ai, ZoneType.Hand);
        addCard(ISLAND, ai);
        addCard(ISLAND, ai);
        addCard("Swamp", ai);
        library(FOREST, 20);
        assertTrue(ids(t.deckLines()).contains("742-1295"), "piece 1's Oracle line is held: " + ids(t.deckLines()));
        assertTrue(LoopSinks.digsForOracle(ai, line(KINNAN_BASALT)), "the dig toward Oracle could run");
        assertTrue(ComboExecute.goOffNow(ai, castOf(oracle)), "piece 1's go-off is ready");
        assertFalse(driver.holdsOracle(castOf(oracle)), "the driver holds piece 1's go-off");
        final SpellAbility goOff = ComboExecute.goOffFirst(ai, List.of(castOf(oracle)));
        assertSame(goOff, castOf(oracle));
        assertEquals(canPlayAndPayFor(goOff), AiPlayDecision.WillPlay);

        game.getAction().moveToLibrary(consultation, null);
        assertFalse(ComboExecute.goOffNow(ai, castOf(oracle)), "Consultation in the library");
        assertTrue(ids(t.executable()).contains("742-1295"), "its line still executable: " + ids(t.executable()));
        assertFalse(driver.holdsOracle(castOf(oracle)), "an executable piece-1 Oracle line is piece 1's");
        assertTrue(ComboHold.vetoesCast(ai, castOf(oracle)), "piece 1's own hold");

        t.useLines(ComboLoopBoards.shippedLoopLines(LoopRows.ids().toArray(new String[0])));
        assertTrue(driver.holdsOracle(castOf(oracle)), "the loop lines alone: the dig holds Oracle");
        assertTrue(ComboHold.vetoesCast(ai, castOf(oracle)));
    }

    // R22 on the Oracle dig's board, made so its threshold lies below the floor: Basalt Monolith and Nyxbloom Ancient
    // (3430-4131) for Kinnan, so devotion to blue is Thrasios's 1 and Oracle wins from three cards; in our Main 2,
    // where the stock AI would cast Oracle. The opponent has an untapped Island: Thrasios digs from ten to the floor,
    // four, and no further, and Oracle is not cast, still held.
    @Test
    public void noDigBelowTheFloorIntoOpenMana() {
        final Card oracle = nyxbloomOracleBoard();
        addCard(ISLAND, opp);
        assertEquals(LoopSinks.oracleThreshold(ai), 3, "Thrasios's {U} and Oracle's {U}{U}");
        final List<Seen> seen = play(g -> !g.getPhaseHandler().is(PhaseType.MAIN2));
        assertEquals(only(seen, Seen::dig).size(), 6, "ten to four: " + seen);
        assertEquals(library(), LoopSinks.LIBRARY_FLOOR, "the floor");
        assertTrue(only(seen, s -> s.cast(ORACLE)).isEmpty(), "Oracle was cast: " + seen);
        assertTrue(ai.getCardsIn(ZoneType.Hand).contains(oracle));
        assertFalse(game.isGameOver());
        assertTrue(driver.holdsOracle(castOf(oracle)), "still held: four cards, above Oracle's three");
    }

    // The Oracle dig (section 6.4 step 3): Thassa's Oracle in hand, two untapped Islands and a library of ten Forests,
    // in our Main 2, where the stock AI would cast Oracle (in Main 1 it waits for Main 2 whatever the hold). Devotion
    // to blue is Kinnan's and Thrasios's 2, so Oracle wins from four cards. The stock AI never casts Oracle, from
    // before the go-off's first decision on; Thrasios digs from ten to four, a Forest onto our battlefield each time;
    // at four the driver casts Oracle, and the game ends WinsGameSpellEffect.
    @Test
    public void theOracleDigHoldsOracleAndWins() {
        start();
        moveToMain2(game, ai);
        final Card oracle = addCardToZone(ORACLE, ai, ZoneType.Hand);
        addCard(ISLAND, ai);
        addCard(ISLAND, ai);
        library(FOREST, 10);
        assertTrue(LoopSinks.digsForOracle(ai, line(KINNAN_BASALT)));
        assertEquals(LoopSinks.oracleThreshold(ai), 4, "Kinnan's {U}, Thrasios's {U} and Oracle's {U}{U}");
        assertTrue(driver.holdsOracle(castOf(oracle)), "held before the go-off's first decision");
        final List<Seen> dug = play(g -> library() == 4 && g.getStack().isEmpty());
        assertEquals(only(dug, Seen::dig).size(), 6, "ten to four: " + dug);
        assertTrue(only(dug, s -> s.cast(ORACLE)).isEmpty(), "the stock AI cast Oracle: " + dug);
        assertTrue(ai.getCardsIn(ZoneType.Hand).contains(oracle));
        final SpellAbility cast = driver.mainPhaseAction(List.of());
        assertNotNull(cast, "the driver casts Oracle at four");
        assertTrue(cast.isSpell());
        assertSame(cast.getHostCard(), oracle);
        ai.getController().playChosenSpellAbility(cast);
        assertWonBy(ORACLE, play(g -> false));
    }

    // Cursed Totem (activated abilities of creatures can't be activated) stops Thrasios, and the stock plays go on:
    // the opponent at 40, Endless One in hand and a library of Memnites. Through the whole game loop the stock AI casts
    // Endless One from the pool of 200, and Thrasios is never activated; with the Totem gone it would dig.
    @Test
    public void cursedTotemStopsThrasios() {
        start();
        opp.setLife(40, null);
        final Card totem = addCard("Cursed Totem", opp);
        library(MEMNITE, 10);
        addCardToZone(ENDLESS_ONE, ai, ZoneType.Hand);
        assertNull(LoopSinks.thrasios(ai, line(KINNAN_BASALT)), "Thrasios's {4} refused");
        final List<Seen> seen = play(g -> !g.getPhaseHandler().is(PhaseType.MAIN1));
        assertTrue(only(seen, Seen::dig).isEmpty(), "a Thrasios activation: " + seen);
        final Card cast = onOurBattlefield(ENDLESS_ONE);
        assertNotNull(cast, "the stock plays go on: " + seen);
        assertEquals(cast.getCounters(CounterEnumType.P1P1), 99);
        assertFalse(LoopTracker.Snapshot.of(ai).isDecisive(), "short of five times lethal");
        game.getAction().moveToGraveyard(totem, null);
        assertSame(LoopSinks.thrasios(ai, line(KINNAN_BASALT)), digOf(thrasios), "the Totem gone: a dig");
    }

    // The Oracle dig's board with two Forests for the Islands, in our Main 2: no {U}{U} for Oracle, so no dig toward
    // it, and nothing else in the library is worth a dig: no activation, the library ends at 4 or more (all ten).
    @Test
    public void oracleWithoutBlueKeepsTheFloor() {
        start();
        moveToMain2(game, ai);
        final Card oracle = addCardToZone(ORACLE, ai, ZoneType.Hand);
        addCard(FOREST, ai);
        addCard(FOREST, ai);
        library(FOREST, 10);
        assertFalse(LoopSinks.digsForOracle(ai, line(KINNAN_BASALT)), "no {U}{U}");
        final List<Seen> seen = play(g -> !g.getPhaseHandler().is(PhaseType.MAIN2));
        assertTrue(only(seen, Seen::dig).isEmpty(), "an Oracle dig: " + seen);
        assertTrue(library() >= LoopSinks.LIBRARY_FLOOR, "the floor kept");
        assertEquals(library(), 10);
        assertTrue(ai.getCardsIn(ZoneType.Hand).contains(oracle));
        addCard(ISLAND, ai);
        addCard(ISLAND, ai);
        assertTrue(LoopSinks.digsForOracle(ai, line(KINNAN_BASALT)), "two Islands: the dig would run");
    }

    // With Laboratory Maniac on our battlefield the floor does not apply (section 6.4 step 3): a library of six
    // Forests, each activation putting one onto our battlefield, Thrasios digs to empty, and its next activation's
    // draw wins.
    @Test
    public void labManiacDigsToEmptyAndWins() {
        start();
        addCard(LAB, ai);
        library(FOREST, 6);
        final List<Seen> seen = play(g -> false);
        assertWonBy(LAB, seen);
        final List<Seen> digs = only(seen, Seen::dig);
        assertEquals(digs.size(), 7, "six to empty, and the draw: " + seen);
        assertEquals(digs.get(6).library(), 0, "the last activation on an empty library");
        assertTrue(ai.getCardsIn(ZoneType.Library).isEmpty());
    }

    // R8, the idle rule on the stock AI's own plays: Kinnan and Basalt, no Thrasios, two Divinations in hand, two
    // untapped Islands, a library of Forests and our land drop already used, in our Main 2 (DrawAi waits for it). The
    // go-off builds to Divination's 3 and the stock AI casts one; it draws two Forests, nothing new (no nonland
    // permanent, no card the pool and our lands pay for, no power, no opponent moved); the driver refills to 3 again
    // and the stock AI casts the other, which buys nothing either. After those two idle judged rounds the driver
    // refills no more.
    @Test
    public void twoIdleStockRoundsEndTheGoOff() {
        game();
        rock();
        moveToMain2(game, ai);
        addCardToZone(DIVINATION, ai, ZoneType.Hand);
        addCardToZone(DIVINATION, ai, ZoneType.Hand);
        addCard(ISLAND, ai);
        addCard(ISLAND, ai);
        library(FOREST, 20);
        ai.setLandsPlayedThisTurn(ai.getMaxLandPlays());
        assertEquals(LoopPool.stockTarget(ai, line(KINNAN_BASALT)), 3, "Divination's {2}{U}");
        final List<Seen> seen = play(g -> count(ZoneType.Hand, FOREST) == 4 && g.getStack().isEmpty());
        final List<Integer> divinations = new ArrayList<>();
        for (int i = 0; i < seen.size(); i++) {
            if (seen.get(i).cast(DIVINATION)) {
                divinations.add(i);
            }
        }
        assertEquals(divinations.size(), 2, "both Divinations cast: " + seen);
        assertEquals(count(ZoneType.Hand, FOREST), 4, "four Forests drawn");
        assertFalse(only(seen.subList(divinations.get(0), divinations.get(1)), s -> s.host() == basalt).isEmpty(),
                "one idle round: the driver refilled for the second");
        final int untaps = untapsOf(basalt);
        assertNull(driver.mainPhaseAction(List.of()), "two idle judged rounds: the driver refills no more");
        ComboLoopBoards.stepUntil(game, g -> !g.getPhaseHandler().is(PhaseType.MAIN2), MAX_STEPS);
        assertEquals(untapsOf(basalt), untaps, "no refill after the two idle rounds");
    }

    // afterStockWalk is null with no loop that runs: Thrasios on ours (its {4} payable by four Islands) and a library
    // of Memnites, but no Kinnan and no Basalt Monolith. Neither of the driver's slots acts; and in our Main 2,
    // Thassa's Oracle in hand, the AI's whole decision is the stock one, answer and draws, with no line and with a loop
    // line the deck holds and the board does not: Oracle is cast, not held.
    @Test
    public void afterTheWalkIsNullWithoutALoop() {
        game();
        thrasios = ComboLoopBoards.ready(addCard(THRASIOS, ai));
        for (int i = 0; i < 4; i++) {
            addCard(ISLAND, ai);
        }
        library(MEMNITE, 10);
        assertNull(driver.mainPhaseAction(List.of()), "no loop runs: no go-off");
        assertNull(driver.afterStockWalk(), "nor anything after the walk");
        moveToMain2(game, ai);
        final Card oracle = addCardToZone(ORACLE, ai, ZoneType.Hand);
        final AiController brains = ComboLoopBoards.aiOf(ai);
        t.useLines(List.of());
        brains.chooseSpellAbilityToPlay(); // warm
        final List<SpellAbility> stock = ComboLoopBoards.assertStockParity(brains::chooseSpellAbilityToPlay, ai,
                ComboLoopBoards.shippedLoopLines(KINNAN_BASALT));
        assertNotNull(stock, "the AI plays something");
        assertSame(stock.get(0).getHostCard(), oracle, "the stock AI casts Oracle: " + stock);
        assertFalse(driver.holdsOracle(castOf(oracle)), "no loop runs: no hold");
    }

    // ---- beyond the plan's tests ----

    // R22's other half on the Oracle dig's below-the-floor board (Basalt, Nyxbloom and Thrasios; Oracle wins from
    // three cards): with no opponent able to answer, Thrasios digs past the floor to three, and the driver casts
    // Oracle there, at its threshold and not one card sooner; the game ends WinsGameSpellEffect.
    @Test
    public void theOracleDigGoesBelowTheFloorWhenNoOpponentHasMana() {
        nyxbloomOracleBoard();
        final List<Seen> seen = play(g -> false);
        assertWonBy(ORACLE, seen);
        assertEquals(only(seen, Seen::dig).size(), 7, "ten to three: " + seen);
        final List<Seen> casts = only(seen, s -> s.cast(ORACLE));
        assertEquals(casts.size(), 1, "one Oracle cast: " + seen);
        assertEquals(casts.get(0).library(), 3, "cast at the threshold, below the floor");
    }

    // R22 for Laboratory Maniac's dig: the opponent's untapped Island, Laboratory Maniac on ours, a library of six
    // Forests. Thrasios digs to the floor and no further, the game still on.
    @Test
    public void aLabDigStopsAtTheFloorIntoOpenMana() {
        start();
        addCard(LAB, ai);
        library(FOREST, 6);
        addCard(ISLAND, opp);
        final List<Seen> seen = play(g -> !g.getPhaseHandler().is(PhaseType.MAIN1));
        assertEquals(only(seen, Seen::dig).size(), 2, "six to four: " + seen);
        assertEquals(library(), LoopSinks.LIBRARY_FLOOR);
        assertFalse(game.isGameOver());
    }

    // R22's other condition, read directly: a dig below the floor needs us able to win. Laboratory Maniac on ours, a
    // library of three Forests, the opponent with nothing: Thrasios digs; with the opponent's Platinum Angel (our
    // opponents can't win the game) it does not, and the dig above the floor is no dig below it: with a fourth and a
    // fifth Forest it does again.
    @Test
    public void aDigBelowTheFloorNeedsUsAbleToWin() {
        start();
        addCard(LAB, ai);
        library(FOREST, 3);
        final ComboLines.Line l = line(KINNAN_BASALT);
        assertSame(LoopSinks.thrasios(ai, l), digOf(thrasios), "below the floor, the opponent with nothing");
        addCard("Platinum Angel", opp);
        game.getAction().checkStateEffects(true);
        assertTrue(ai.cantWin());
        assertNull(LoopSinks.thrasios(ai, l), "we cannot win: the floor holds");
        library(FOREST, 2);
        assertSame(LoopSinks.thrasios(ai, l), digOf(thrasios), "five cards: above the floor");
    }

    // The hold's edge: our library above Oracle's threshold holds Oracle, at the threshold it does not, the driver
    // then casting it itself. Thassa's Oracle in hand, two Islands, Kinnan, Basalt and Thrasios (a threshold of 4), in
    // our Main 2: five cards, held and the stock cast vetoed, the reading drawing nothing; four, neither, and no dig
    // either, Oracle's cast being due. Only our hand cast of Oracle is held: not another spell, nor a cast of an Oracle
    // from our graveyard. And none while no loop line can run (the opponent's Null Rod denies the rock lines) or
    // Thrasios cannot dig (the opponent's Cursed Totem).
    @Test
    public void theOracleHoldEndsAtTheThreshold() {
        start();
        moveToMain2(game, ai);
        final Card oracle = addCardToZone(ORACLE, ai, ZoneType.Hand);
        final Card counterspell = addCardToZone(COUNTERSPELL, ai, ZoneType.Hand);
        final Card buried = addCardToZone(ORACLE, ai, ZoneType.Graveyard);
        addCard(ISLAND, ai);
        addCard(ISLAND, ai);
        library(FOREST, 5);
        final long before = MyRandom.callCount();
        assertTrue(driver.holdsOracle(castOf(oracle)), "five cards, above the threshold");
        assertEquals(MyRandom.callCount() - before, 0, "the hold drew");
        assertTrue(ComboHold.vetoesCast(ai, castOf(oracle)));
        assertFalse(driver.holdsOracle(castOf(counterspell)), "no other spell is held");
        assertFalse(driver.holdsOracle(castOf(buried)), "nor an Oracle cast from anywhere but our hand");
        game.getAction().moveToGraveyard(ai.getCardsIn(ZoneType.Library).get(0), null);
        assertEquals(library(), 4);
        assertFalse(driver.holdsOracle(castOf(oracle)), "at the threshold: no hold");
        assertFalse(ComboHold.vetoesCast(ai, castOf(oracle)));
        assertNull(LoopSinks.thrasios(ai, line(KINNAN_BASALT)), "nor a dig: Oracle's cast is due");
        library(FOREST, 1);
        assertTrue(driver.holdsOracle(castOf(oracle)), "five again");
        final Card rod = addCard("Null Rod", opp);
        assertFalse(driver.holdsOracle(castOf(oracle)), "the rock lines denied: no loop line can run");
        game.getAction().moveToGraveyard(rod, null);
        assertTrue(driver.holdsOracle(castOf(oracle)), "the Rod gone");
        addCard("Cursed Totem", opp);
        assertFalse(driver.holdsOracle(castOf(oracle)), "Thrasios cannot dig: no hold");
    }

    // The hold is for a go-off: none while no loop line can run, though the dig toward Oracle could. Kinnan and
    // Thrasios on ours, Basalt Monolith in our hand, Thassa's Oracle in hand, two Islands and a library of ten
    // Forests, in our Main 2: no hold, and the stock AI may cast Oracle. With a Basalt on our battlefield the hold is
    // on; tapped, its untap of {3} unpayable (our two Islands pay 2 of it, the pool none), it is off again.
    @Test
    public void noHoldWhileNoLoopLineCanRun() {
        game();
        ComboLoopBoards.ready(addCard(KINNAN, ai));
        thrasios = ComboLoopBoards.ready(addCard(THRASIOS, ai));
        addCardToZone(BASALT, ai, ZoneType.Hand);
        moveToMain2(game, ai);
        final Card oracle = addCardToZone(ORACLE, ai, ZoneType.Hand);
        addCard(ISLAND, ai);
        addCard(ISLAND, ai);
        library(FOREST, 10);
        final ComboLines.Line l = line(KINNAN_BASALT);
        assertTrue(LoopSinks.digsForOracle(ai, l), "the dig toward Oracle could run");
        assertFalse(driver.holdsOracle(castOf(oracle)), "Basalt Monolith is in our hand: no loop can run");
        assertFalse(ComboHold.vetoesCast(ai, castOf(oracle)));
        final Card rock = addCard(BASALT, ai);
        assertTrue(driver.holdsOracle(castOf(oracle)), "Basalt Monolith on our battlefield");
        rock.setTapped(true);
        assertFalse(driver.holdsOracle(castOf(oracle)), "tapped, with its untap unpayable: no loop can run");
    }

    // R2 gives the hold up to piece 1's own Oracle go-off and to no other line: an executable piece-1 line whose winner
    // is another card leaves the hold on. Laboratory Maniac and Demonic Consultation registered with Kinnan's list
    // before the tracker reads it; Kinnan, Basalt, Thrasios and Laboratory Maniac on ours, Thassa's Oracle and Demonic
    // Consultation in hand, two Islands and a Swamp, a library of twenty Forests, in our Main 2; the tracker reads the
    // loop lines and the Laboratory Maniac line (742-2836) alone. That line is executable, its winner Laboratory
    // Maniac, and no line of Oracle's is: the dig toward Oracle holds Oracle, and the stock cast is vetoed.
    @Test
    public void anotherWinnersExecutableLineLeavesTheOracleHoldOn() {
        start();
        ComboLoopBoards.deck(ai, null, LAB, CONSULTATION);
        moveToMain2(game, ai);
        addCard(LAB, ai);
        final Card oracle = addCardToZone(ORACLE, ai, ZoneType.Hand);
        addCardToZone(CONSULTATION, ai, ZoneType.Hand);
        addCard(ISLAND, ai);
        addCard(ISLAND, ai);
        addCard("Swamp", ai);
        library(FOREST, 20);
        final List<ComboLines.Line> lines = new ArrayList<>(
                ComboLoopBoards.shippedLoopLines(LoopRows.ids().toArray(new String[0])));
        lines.addAll(ComboLoopBoards.shippedLoopLines("742-2836"));
        t.useLines(lines);
        assertTrue(ids(t.executable()).contains("742-2836"),
                "piece 1's Laboratory Maniac line: " + ids(t.executable()));
        assertEquals(line("742-2836").winner(), ComboLines.Winner.LAB);
        assertTrue(LoopSinks.digsForOracle(ai, line(KINNAN_BASALT)), "the dig toward Oracle could run");
        assertTrue(driver.holdsOracle(castOf(oracle)), "a Laboratory Maniac line is no Oracle line: the hold stays");
        assertTrue(ComboHold.vetoesCast(ai, castOf(oracle)));
    }

    // Chromatic Orrery lets the loop's colourless mana pay Oracle's {U}{U} (spec section 9): Kinnan, Basalt, Thrasios
    // and Orrery on ours, no land at all, Thassa's Oracle in hand and a library of ten Forests. The dig toward Oracle
    // could run, Thrasios digs for it, and Oracle is held.
    @Test
    public void chromaticOrreryLetsTheLoopPayOraclesBluePips() {
        start();
        addCard("Chromatic Orrery", ai);
        final Card oracle = addCardToZone(ORACLE, ai, ZoneType.Hand);
        library(FOREST, 10);
        final ComboLines.Line l = line(KINNAN_BASALT);
        assertTrue(LoopSinks.digsForOracle(ai, l), "no land, but the loop's mana pays {U}{U} through Orrery");
        assertSame(LoopSinks.thrasios(ai, l), digOf(thrasios), "the dig toward Oracle runs");
        assertTrue(driver.holdsOracle(castOf(oracle)), "and holds Oracle");
    }

    // Oracle's cast through Chromatic Orrery waits for the pool that pays it: Orrery's board with a library of four,
    // Oracle's threshold, in our Main 2. No land pays Oracle's {U}{U} and the pool is empty, so the driver's first
    // decision is not the cast, which would fail its payment, but Basalt's tap; then the cast, the pool's colourless
    // paying the blue through Orrery, and the game ends WinsGameSpellEffect.
    @Test
    public void theOracleCastThroughChromaticOrreryWaitsForThePool() {
        start();
        moveToMain2(game, ai);
        addCard("Chromatic Orrery", ai);
        final Card oracle = addCardToZone(ORACLE, ai, ZoneType.Hand);
        library(FOREST, 4);
        final SpellAbility tap = driver.mainPhaseAction(List.of());
        assertSame(tap, manaOf(basalt), "the cast waits for the pool: " + tap);
        playAndResolve(tap);
        final SpellAbility cast = driver.mainPhaseAction(List.of());
        assertNotNull(cast, "Oracle's cast from the pool");
        assertTrue(cast.isSpell());
        assertSame(cast.getHostCard(), oracle);
        ai.getController().playChosenSpellAbility(cast);
        assertWonBy(ORACLE, play(g -> false));
    }

    // Oracle's cast passes the driver's own check, its price as the engine prices it now (the plan's rules), and a
    // cast the check refuses ends nothing. Under the opponent's Sphere of Resistance (spells cost {1} more) Oracle
    // costs {1}{U}{U}, which our two Islands alone do not pay; Thassa's Oracle in hand, our library of five Forests.
    // The go-off builds to the stock target, Oracle's 2, and the walk plays (its round spends the pool); our library
    // then milled to Oracle's threshold of four, the driver does not return the cast, which would fail its payment:
    // it refills, Basalt's tap, the go-off running on, and then casts Oracle, the pool paying the {1}.
    @Test
    public void aTaxedOracleCastWaitsForThePoolToPayIt() {
        start();
        moveToMain2(game, ai);
        final Card oracle = addCardToZone(ORACLE, ai, ZoneType.Hand);
        addCard(ISLAND, ai);
        addCard(ISLAND, ai);
        library(FOREST, 5);
        addCard("Sphere of Resistance", opp);
        assertEquals(ComboExecute.priced(ai, LoopSinks.oracleCast(ai)).getCMC(), 3, "Oracle's {U}{U} and the tax");
        assertEquals(slot(MAX_ACTIONS).size(), 4, "built to Oracle's 2, then the walk's turn");
        driver.noteStockPlay(castOf(oracle));
        ai.getManaPool().clearPool(false);
        game.getAction().moveToGraveyard(ai.getCardsIn(ZoneType.Library).get(0), null);
        assertEquals(library(), 4, "Oracle's threshold");
        final SpellAbility tap = driver.mainPhaseAction(List.of());
        assertSame(tap, manaOf(basalt), "the cast waits for the pool: " + tap);
        playAndResolve(tap);
        final SpellAbility cast = driver.mainPhaseAction(List.of());
        assertNotNull(cast, "the refused cast ended nothing: Oracle's cast, the pool paying its {1}");
        assertTrue(cast.isSpell());
        assertSame(cast.getHostCard(), oracle);
        ai.getController().playChosenSpellAbility(cast);
        assertWonBy(ORACLE, play(g -> false));
    }

    // A dig below the floor reads the opponents' mana to answer it (R22): Laboratory Maniac on ours and a library of
    // three Forests. The opponent's Island tapped: Thrasios digs; untapped: it does not; tapped again, but with mana
    // floating in the opponent's pool: it does not.
    @Test
    public void aDigBelowTheFloorReadsTheOpponentsMana() {
        start();
        addCard(LAB, ai);
        library(FOREST, 3);
        final ComboLines.Line l = line(KINNAN_BASALT);
        final Card island = addCard(ISLAND, opp);
        island.setTapped(true);
        assertSame(LoopSinks.thrasios(ai, l), digOf(thrasios), "their Island tapped: no mana to answer");
        island.setTapped(false);
        assertNull(LoopSinks.thrasios(ai, l), "their Island untapped");
        island.setTapped(true);
        opp.getManaPool().addMana(new Mana((byte) ManaAtom.BLUE, island, null, opp));
        assertNull(LoopSinks.thrasios(ai, l), "mana in their pool");
    }

    // R22 reads every opponent, and every untapped permanent of theirs that can make mana, a land or not, and no other:
    // a three-player game, Laboratory Maniac on ours and a library of three Forests. The first opponent's untapped
    // Memnite makes no mana and stops nothing; the second opponent's untapped Island stops the dig below the floor,
    // tapped it does not; and so does the first opponent's Sol Ring, a permanent that is no land.
    @Test
    public void aDigBelowTheFloorReadsEveryOpponentsManaPermanents() {
        pod();
        rock();
        thrasios = ComboLoopBoards.ready(addCard(THRASIOS, ai));
        addCard(LAB, ai);
        library(FOREST, 3);
        final Player other = game.getPlayers().get(2);
        final ComboLines.Line l = line(KINNAN_BASALT);
        assertSame(LoopSinks.thrasios(ai, l), digOf(thrasios), "no opponent has mana");
        addCard(MEMNITE, opp);
        assertSame(LoopSinks.thrasios(ai, l), digOf(thrasios), "an untapped Memnite makes no mana");
        final Card island = addCard(ISLAND, other);
        assertNull(LoopSinks.thrasios(ai, l), "the second opponent's untapped Island");
        island.setTapped(true);
        assertSame(LoopSinks.thrasios(ai, l), digOf(thrasios), "their Island tapped");
        final Card ring = addCard("Sol Ring", opp);
        assertNull(LoopSinks.thrasios(ai, l), "the first opponent's untapped Sol Ring, a permanent that is no land");
        ring.setTapped(true);
        assertSame(LoopSinks.thrasios(ai, l), digOf(thrasios), "their Sol Ring tapped");
    }

    // The winners a dig to empty reads: Jace, Wielder of Mysteries as Laboratory Maniac is (a library of three
    // Forests, below the floor: Thrasios digs), and only one holding its win: a Laboratory Maniac that has lost its
    // abilities (the opponent's Turn to Frog) is none, so Thrasios keeps the floor.
    @Test
    public void aDigToEmptyNeedsAWinnerHoldingItsWin() {
        start();
        final Card jace = addCard("Jace, Wielder of Mysteries", ai);
        jace.setCounters(CounterEnumType.LOYALTY, 4);
        library(FOREST, 3);
        final ComboLines.Line l = line(KINNAN_BASALT);
        assertSame(LoopSinks.thrasios(ai, l), digOf(thrasios), "Jace: a dig below the floor");
        game.getAction().moveToGraveyard(jace, null);
        assertNull(LoopSinks.thrasios(ai, l), "no winner: the floor");
        final Card lab = addCard(LAB, ai);
        assertSame(LoopSinks.thrasios(ai, l), digOf(thrasios), "Laboratory Maniac");
        frog(lab);
        assertNull(LoopSinks.thrasios(ai, l), "a Laboratory Maniac without its abilities wins nothing");
    }

    // Thrasios's {4} is Thrasios's, found by its name: with Thrasios gone, another card of ours with an activated Scry
    // ability (Crystal Ball) is no dig, though a library of Memnites would be worth one.
    @Test
    public void aScryAbilityOfAnotherCardIsNoDig() {
        start();
        game.getAction().moveToGraveyard(thrasios, null);
        addCard("Crystal Ball", ai);
        library(MEMNITE, 10);
        assertNull(LoopSinks.thrasios(ai, line(KINNAN_BASALT)), "Crystal Ball is not Thrasios");
    }

    // A Thrasios that lost its abilities (the opponent's Turn to Frog) has no {4}: no dig, nothing thrown, and with no
    // dig Oracle's threshold is Oracle's own {U}{U}; the driver's decisions make no dig either.
    @Test
    public void aThrasiosThatLostItsAbilitiesIsNoDig() {
        start();
        library(MEMNITE, 10);
        frog(thrasios);
        final ComboLines.Line l = line(KINNAN_BASALT);
        assertNull(LoopSinks.thrasios(ai, l), "no ability, no dig");
        assertFalse(LoopSinks.digsForOracle(ai, l));
        assertEquals(LoopSinks.oracleThreshold(ai), 2, "Oracle's own {U}{U}");
        assertTrue(only(drive(MAX_ACTIONS), Act::dig).isEmpty());
    }

    // Thrasios digs only for a card the pool and our lands could pay for (digWorth): a library of ten Colossal
    // Dreadmaws and no green source ({4}{G}{G}: the loop's colourless pays the {4} alone) is no dig; two Forests make
    // it one.
    @Test
    public void aCardNobodyCanPayForIsNoDig() {
        start();
        library("Colossal Dreadmaw", 10);
        final ComboLines.Line l = line(KINNAN_BASALT);
        assertFalse(LoopSinks.digWorth(ai, l));
        assertNull(LoopSinks.thrasios(ai, l));
        addCard(FOREST, ai);
        addCard(FOREST, ai);
        assertTrue(LoopSinks.digWorth(ai, l), "two Forests pay the {G}{G}");
        assertSame(LoopSinks.thrasios(ai, l), digOf(thrasios));
    }

    // The go-off's line reaches Thrasios's readings at every call, the start's, the walk's slot's and the driver's
    // own: a library of ten Colossal Dreadmaws ({4}{G}{G}), which only the loop's colourless mana beside our two
    // Forests pays for, is dug to the floor, the walk asked once an activation and once more when Thrasios stops.
    @Test
    public void thrasiosDigsForCardsOnlyTheLoopCanPayFor() {
        start();
        addCard(FOREST, ai);
        addCard(FOREST, ai);
        library("Colossal Dreadmaw", 10);
        final List<Act> acts = drive(MAX_ACTIONS);
        assertEquals(only(acts, Act::dig).size(), 6, "ten to four: " + acts);
        assertEquals(library(), LoopSinks.LIBRARY_FLOOR);
        assertEquals(asks, 7, "the walk asked once an activation, and once when Thrasios stopped");
    }

    // What adds to our board, the arms the plan's list leaves unread: a plain Pump (Giant Growth), and a ChangeZone to
    // the battlefield whose ChangeType lists several types, a creature among them (Incoming!); not a flicker, whose
    // return to the battlefield names no ChangeType (Cloudshift, Ephemerate), nor a static that is no ManaConvert
    // (Cursed Totem).
    @Test
    public void addsToBoardReadsAPlainPumpACommaListAndNotAFlicker() {
        game();
        for (final String name : List.of("Giant Growth", "Incoming!")) {
            assertTrue(LoopSinks.addsToBoard(addCardToZone(name, ai, ZoneType.Library)), name);
        }
        for (final String name : List.of("Cloudshift", "Ephemerate", "Cursed Totem")) {
            assertFalse(LoopSinks.addsToBoard(addCardToZone(name, ai, ZoneType.Library)), name);
        }
    }

    // The Oracle cast waits while we cannot win: a three-player game, one opponent's Platinum Angel (we can't win, and
    // its controller, at 1, can't lose), the other opponent at 3; Walking Ballista on ours with 10 counters (the kill
    // needs 8: the Angel's 4 and the two lives), Thassa's Oracle in hand, two Islands and a library of four Forests,
    // Oracle's threshold, in our Main 2. Ballista pings the Angel first; with that ping undone (its damage removed) the
    // volley is over for the phase and the Angel stands, so we still cannot win. The go-off goes on for the other
    // opponent (our 12 power short of five times their 3), and the driver refills toward the stock target, casting no
    // Oracle, which could not win.
    @Test
    public void noOracleCastWhileWeCannotWin() {
        pod();
        rock();
        thrasios = ComboLoopBoards.ready(addCard(THRASIOS, ai));
        moveToMain2(game, ai);
        final Player other = game.getPlayers().get(2);
        final Card angel = addCard("Platinum Angel", opp);
        opp.setLife(1, null);
        other.setLife(3, null);
        ComboLoopBoards.ready(addCard(LoopSinks.BALLISTA, ai)).setCounters(CounterEnumType.P1P1, 10);
        addCardToZone(ORACLE, ai, ZoneType.Hand);
        addCard(ISLAND, ai);
        addCard(ISLAND, ai);
        library(FOREST, 4);
        game.getAction().checkStateEffects(true);
        assertTrue(ai.cantWin(), "the Angel");
        assertEquals(LoopSinks.lethalNeeded(ai), 8, "the Angel's 4, and 1 and 3");
        assertEquals(LoopSinks.oracleThreshold(ai), 4);
        final SpellAbility ping = driver.mainPhaseAction(List.of());
        assertNotNull(ping, "a go-off: Ballista can kill the Angel");
        assertEquals(ping.getApi(), ApiType.DealDamage, "the ping first: " + ping);
        assertSame(ping.getTargets().get(0), angel, "at the Angel");
        playAndResolve(ping);
        angel.setDamage(0);
        final SpellAbility next = driver.mainPhaseAction(List.of());
        assertTrue(ai.cantWin(), "the Angel stands");
        assertNotNull(next, "the go-off goes on for the other opponent");
        assertSame(next, manaOf(basalt), "a refill, no Oracle cast: " + next);
    }

    // A dig toward Thassa's Oracle needs Oracle able to win now, as piece 1 reads its own Oracle lines: on the Oracle
    // dig's board with Endless One in hand too, the opponent's Torpor Orb or Hushbringer disables Oracle's entering
    // trigger, and Iona, Shield of Emeria naming blue or Void Winnower (Oracle's mana value 2 is even) bars its cast.
    // Under each, no dig toward Oracle (it would only empty our library) and no hold, and a library of Forests is
    // worth no other dig: the driver builds the pool of 200 for Endless One and hands the decision to the walk once,
    // Thrasios taking no activation, our library still at ten.
    @Test
    public void anOracleThatCannotWinIsNoDigNorHold() {
        for (final String hoser : ORACLE_HOSERS) {
            final Card oracle = hoserBoard(10);
            final ComboLines.Line l = line(KINNAN_BASALT);
            assertTrue(LoopSinks.digsForOracle(ai, l), "the dig toward Oracle before " + hoser);
            hoser(hoser);
            assertFalse(LoopSinks.digsForOracle(ai, l), hoser);
            assertNull(LoopSinks.thrasios(ai, l), hoser);
            assertFalse(driver.holdsOracle(castOf(oracle)), hoser);
            assertFalse(ComboHold.vetoesCast(ai, castOf(oracle)), hoser);
            final List<Act> acts = drive(MAX_ACTIONS);
            assertTrue(only(acts, Act::dig).isEmpty(), hoser + ": " + acts);
            assertEquals(library(), 10, hoser);
            assertEquals(pool(), LoopPool.TARGET, hoser + ": the pool built for Endless One");
            assertEquals(asks, 1, hoser + ": the walk's turn at the target");
        }
    }

    // At its threshold an Oracle that cannot win is no cast of the driver's, and the driver goes on without it: the
    // hosers' board with a library of four, Oracle's threshold. Under each hoser the go-off's first decision is
    // Basalt's tap, the build toward the 200 for Endless One: not Oracle's cast (under Torpor Orb or Hushbringer it
    // would resolve and win nothing), nor nothing at all (a cast the driver may not make answered every decision).
    @Test
    public void noOracleCastAtItsThresholdWhileItCannotWin() {
        for (final String hoser : ORACLE_HOSERS) {
            final Card oracle = hoserBoard(4);
            hoser(hoser);
            assertSame(driver.mainPhaseAction(List.of()), manaOf(basalt), hoser);
            assertTrue(ai.getCardsIn(ZoneType.Hand).contains(oracle), hoser);
        }
    }

    // Through the whole game loop, Iona, Shield of Emeria naming blue on the opponent's side and Oracle at its
    // threshold (a library of four): the stock AI's plays go on with the pool topped up, Endless One cast from the
    // pool of 200 at the stock X search's 99, and Oracle never cast.
    @Test
    public void aBarredOracleLeavesThePoolToTheStockAI() {
        final Card oracle = hoserBoard(4);
        hoser("Iona, Shield of Emeria");
        final List<Seen> seen = play(g -> onOurBattlefield(ENDLESS_ONE) != null && g.getStack().isEmpty());
        final Card cast = onOurBattlefield(ENDLESS_ONE);
        assertNotNull(cast, "the stock AI cast Endless One: " + seen);
        assertEquals(cast.getCounters(CounterEnumType.P1P1), 99, "the stock X search's cap");
        final List<Seen> casts = only(seen, s -> s.cast(ENDLESS_ONE));
        assertEquals(casts.get(0).poolBefore(), LoopPool.TARGET, "cast from the pool of 200");
        assertTrue(only(seen, s -> s.cast(ORACLE)).isEmpty(), "Oracle was cast: " + seen);
        assertTrue(ai.getCardsIn(ZoneType.Hand).contains(oracle));
    }

    // The futility gate never holds back Oracle's winning cast: on the below-the-floor board (Basalt, Nyxbloom and
    // Thrasios; Oracle wins from three cards) with a library of four and the opponent's untapped Island, the go-off
    // builds to the stock target (Oracle's {U}{U}), the walk chooses nothing (Oracle held) and Thrasios keeps the
    // floor: a futile build, its fingerprint kept. One card of our library milled (our hand and battlefield as they
    // were), Oracle's cast is due, and the same board starts a go-off that casts it.
    @Test
    public void theOracleCastStartsAGoOffPastTheFutilityGate() {
        final Card oracle = nyxbloomOracleBoard();
        addCard(ISLAND, opp);
        while (library() > 4) {
            game.getAction().moveToGraveyard(ai.getCardsIn(ZoneType.Library).get(0), null);
        }
        final List<Act> acts = drive(MAX_ACTIONS);
        assertTrue(only(acts, Act::dig).isEmpty(), "Thrasios kept the floor: " + acts);
        assertNull(driver.mainPhaseAction(List.of()), "the futile build is not repeated");
        game.getAction().moveToGraveyard(ai.getCardsIn(ZoneType.Library).get(0), null);
        assertEquals(library(), 3, "Oracle's threshold");
        final SpellAbility cast = driver.mainPhaseAction(List.of());
        assertNotNull(cast, "a go-off past the futility gate");
        assertTrue(cast.isSpell());
        assertSame(cast.getHostCard(), oracle, "Oracle's cast: " + cast);
    }

    // Thrasios's turn (the walk chose nothing at the target, the refill going on to Thrasios's {4} without asking it
    // again) ends with the phase, and when Thrasios has nothing left to do: a library of Memnites, an empty hand. The
    // walk chooses nothing at the first hand-off and the refill toward Thrasios starts; in Main 2 (Basalt untapped
    // again) the go-off starts afresh, asking the walk first. It chooses nothing again, the refill goes on, and the
    // opponent's Cursed Totem arrives: the walk's turn at the next boundary, and Thrasios having nothing to do the
    // go-off ends; with the Totem gone the next go-off asks the walk first again.
    @Test
    public void thrasiosTurnEndsWithThePhaseAndWhenThrasiosCannotDig() {
        start();
        library(MEMNITE, 10);
        assertNull(driver.mainPhaseAction(List.of()), "the walk's turn first");
        final SpellAbility tap = driver.afterStockWalk();
        assertSame(tap, manaOf(basalt), "the walk chose nothing: the refill toward Thrasios's {4}");
        playAndResolve(tap);
        moveToMain2(game, ai);
        ai.getManaPool().clearPool(false);
        basalt.setTapped(false);
        assertNull(driver.mainPhaseAction(List.of()), "a new phase: the walk asked first again");
        final SpellAbility tap2 = driver.afterStockWalk();
        assertSame(tap2, manaOf(basalt), "it chose nothing: the refill toward Thrasios");
        playAndResolve(tap2);
        final SpellAbility untap = driver.mainPhaseAction(List.of());
        assertSame(untap, untapOf(basalt), "the refill goes on, the walk not asked");
        playAndResolve(untap);
        final Card totem = addCard("Cursed Totem", opp);
        assertNull(driver.mainPhaseAction(List.of()), "Thrasios cannot dig: the walk's turn at the boundary");
        assertNull(driver.afterStockWalk(), "nothing for Thrasios: the go-off ends");
        game.getAction().moveToGraveyard(totem, null);
        assertNull(driver.mainPhaseAction(List.of()), "the Totem gone: the walk asked first");
        assertSame(driver.afterStockWalk(), manaOf(basalt), "then the refill toward Thrasios");
    }

    // The driver's decisions draw no random number over a Thrasios dig, the walk choosing nothing at each hand-off: a
    // library of ten Memnites, an empty hand. Thrasios digs to the floor, six activations of four cycles each, and the
    // walk is asked once for each and once more when Thrasios has nothing left to do: the refill to Thrasios's {4}
    // never asks it again, since it chose nothing at that board.
    @Test
    public void theThrasiosStepDrawsNothing() {
        start();
        library(MEMNITE, 10);
        final List<Act> acts = drive(MAX_ACTIONS);
        final List<Act> digs = only(acts, Act::dig);
        assertEquals(digs.size(), 6, "ten to four: " + acts);
        for (final Act a : digs) {
            assertEquals(a.poolBefore(), 4, "Thrasios's {4}: " + a);
        }
        assertEquals(acts.size(), 6 * 9, "four cycles and the activation, six times: " + acts);
        assertEquals(library(), LoopSinks.LIBRARY_FLOOR);
        assertEquals(asks, 7, "the walk asked once an activation, and once when Thrasios stopped");
        assertEquals(decisionDraws, 0, "the driver's decisions drew");
    }

    // A tax on Thrasios's activation is paid in full: under the opponent's Suppression Field (abilities that are no
    // mana abilities cost {2} more), with Nyxbloom Ancient beside Kinnan and Basalt (a cycle nets 5: Basalt's 3 tripled
    // and Kinnan's 1, less the untap's 5), a library of six Memnites: each activation waits for its 6.
    @Test
    public void aTaxedThrasiosIsPaidInFull() {
        start();
        ComboLoopBoards.ready(addCard(NYXBLOOM, ai));
        addCard("Suppression Field", opp);
        library(MEMNITE, 6);
        assertEquals(ComboExecute.priced(ai, digOf(thrasios)).getCMC(), 6, "Thrasios's 4 and the tax");
        final List<Act> acts = drive(MAX_ACTIONS);
        final List<Act> digs = only(acts, Act::dig);
        assertEquals(digs.size(), 2, "six to four: " + acts);
        for (final Act a : digs) {
            assertTrue(a.poolBefore() >= 6, "paid in full: " + a);
        }
    }

    // A Thrasios round breaks a run of idle stock rounds (spec 6.5: a round is one spend, a Thrasios activation among
    // them, which the idle rule never judges): two Counterspells and two Islands (a stock target of 2), our library a
    // Forest above nine Memnites. The go-off builds to 2 for the walk, whose play buys nothing (an idle judged round);
    // it builds to 2 again, and the walk chooses nothing: Thrasios digs, refilling to its 4 first, and reveals the
    // Forest (onto our battlefield tapped: nothing new for a judged round); the go-off builds to 2 again, and the
    // walk's play buys nothing again: one idle round since Thrasios's, not two, so the driver goes on.
    @Test
    public void aThrasiosRoundBreaksARunOfIdleRounds() {
        start();
        counterspells();
        library(FOREST, 1);
        library(MEMNITE, 9);
        final SpellAbility counter = ai.getCardsIn(ZoneType.Hand).get(0).getFirstSpellAbility();
        assertEquals(slot(MAX_ACTIONS).size(), 4, "built to 2, then the walk's turn");
        driver.noteStockPlay(counter);
        ai.getManaPool().clearPool(false);
        assertEquals(slot(MAX_ACTIONS).size(), 4, "one idle round: built to 2 again, then the walk's turn");
        final SpellAbility tap = driver.afterStockWalk();
        assertSame(tap, manaOf(basalt), "the walk chose nothing: a refill toward Thrasios's 4");
        playAndResolve(tap);
        final List<SpellAbility> acts = slot(MAX_ACTIONS);
        assertEquals(acts.size(), 8, "the refill to 4, Thrasios, and the build to 2 again: " + acts);
        assertSame(acts.get(3), digOf(thrasios), "Thrasios's activation: " + acts);
        assertNotNull(onOurBattlefield(FOREST), "Thrasios revealed the Forest");
        driver.noteStockPlay(counter);
        ai.getManaPool().clearPool(false);
        assertNotNull(driver.mainPhaseAction(List.of()), "one idle round since Thrasios's, not two");
    }

    // Every Thrasios activation counts toward the caps and stamps the turn for the runaway throttle's lift: room for
    // one action a turn, the pool already at Thrasios's 4 and a library of Memnites, the walk choosing nothing.
    // Thrasios digs once; the refill that would follow is refused, with the cap's one log line.
    @Test
    public void theCapsCountThrasiosActions() {
        start();
        library(MEMNITE, 10);
        floatColourless(4);
        driver.useRounds(new LoopRounds(1, 100));
        final List<Act> acts = drive(MAX_ACTIONS);
        assertEquals(acts.size(), 1, "one action: " + acts);
        assertTrue(acts.get(0).dig(), "Thrasios's: " + acts);
        assertTrue(driver.liftsThrottle(), "a Thrasios activation lifts the throttle for the turn");
        assertEquals(logged("Loop driver: stopped for the turn at 1 actions."), 1);
    }

    // A Thrasios activation that moves no card ends its dig for the phase (R21: a step that gains nothing never burns
    // the caps): under the opponent's Spirit of the Labyrinth, Leovold, Emissary of Trest or Narset, Parter of Veils
    // (we can't draw more than one card each turn), a library of twenty Memnites, the opponent at 40. Through the whole
    // game loop Thrasios draws one Memnite, which the stock AI casts; its next activation draws nothing, and Thrasios
    // takes no more: two activations, no cap's line, and Main 1 over with the game on.
    @Test
    public void aDigThatMovesNoCardEndsForThePhase() {
        for (final String limit : List.of("Spirit of the Labyrinth", "Leovold, Emissary of Trest",
                "Narset, Parter of Veils")) {
            start();
            opp.setLife(40, null);
            final Card c = addCard(limit, opp);
            if (c.isPlaneswalker()) {
                c.setCounters(CounterEnumType.LOYALTY, 5);
            }
            library(MEMNITE, 20);
            final List<Seen> seen = play(g -> !g.getPhaseHandler().is(PhaseType.MAIN1));
            assertEquals(only(seen, Seen::dig).size(), 2, limit + ": " + seen);
            assertEquals(library(), 19, limit + ": one Memnite drawn");
            assertEquals(count(ZoneType.Battlefield, MEMNITE), 1, limit + ": the stock AI cast it");
            assertEquals(logged("Loop driver: stopped for the turn at " + LoopRounds.TURN_CAP + " actions."), 0,
                    limit);
            assertFalse(game.isGameOver(), limit);
        }
    }

    // The dig's end is for the phase, as the pool is: under the opponent's Spirit of the Labyrinth, a library of twenty
    // Memnites, the walk choosing nothing. In Main 1 Thrasios draws one Memnite and its next activation draws nothing:
    // two activations. In Main 2 Thrasios digs again, once, its draw refused again, and stops.
    @Test
    public void aDigEndedForThePhaseDigsAgainInTheNext() {
        start();
        addCard("Spirit of the Labyrinth", opp);
        library(MEMNITE, 20);
        assertEquals(only(drive(MAX_ACTIONS), Act::dig).size(), 2, "Main 1: one draw, then none");
        assertEquals(library(), 19);
        moveToMain2(game, ai);
        ai.getManaPool().clearPool(false);
        assertEquals(only(drive(MAX_ACTIONS), Act::dig).size(), 1, "Main 2: the dig again, its draw refused");
        assertEquals(library(), 19);
    }

    // The walk's slot judges the activations it returns as the go-off slot judges its own: under the opponent's Spirit
    // of the Labyrinth, a library of twenty Memnites, an empty hand (a stock target of 0) and twelve colourless mana
    // floating, each decision passes to the walk at once, and afterStockWalk, the walk choosing nothing, returns
    // Thrasios's activation paid from the pool, no refill between. The first draws a Memnite; the second moves no card
    // out of our library and ends the dig for the phase: two activations, the walk asked before each and once more,
    // and the third {4} left in the pool, where a walk's slot that kept no library size would dig on.
    @Test
    public void aWalkSlotDigThatMovesNoCardEndsForThePhase() {
        start();
        addCard("Spirit of the Labyrinth", opp);
        library(MEMNITE, 20);
        floatColourless(12);
        final List<Act> acts = drive(MAX_ACTIONS);
        assertEquals(only(acts, Act::dig).size(), 2, "one draw, then none: " + acts);
        assertEquals(acts.size(), 2, "no refill: each activation the walk's slot's, paid from the pool: " + acts);
        assertEquals(asks, 3, "the walk asked before each activation, and once when Thrasios stopped");
        assertEquals(library(), 19);
        assertEquals(pool(), 4, "the third {4} not spent");
    }

    // Once the game is over the driver returns nothing, after the walk too: the pool at Thrasios's 4 at a boundary and
    // a library of Memnites, the go-off passes the decision to the walk; the game over, the slot after the walk returns
    // no Thrasios activation, where on the same board with the game on it does.
    @Test
    public void afterTheWalkNothingOnceTheGameIsOver() {
        for (final boolean over : new boolean[] {false, true}) {
            start();
            library(MEMNITE, 10);
            floatColourless(4);
            assertNull(driver.mainPhaseAction(List.of()), "the walk's turn first");
            if (over) {
                game.setGameOver(GameEndReason.Draw);
                assertNull(driver.afterStockWalk(), "the game is over");
            } else {
                assertSame(driver.afterStockWalk(), digOf(thrasios), "Thrasios digs");
            }
        }
    }

    // A go-off in which Thrasios dug is no futile one: two Counterspells and two Islands, a library of five Memnites.
    // The go-off builds to 2, the walk chooses nothing, Thrasios digs once (five to four) and then has nothing to do,
    // and the go-off ends. In Main 2 the same hand and battlefield start a go-off again, the stock AI its only
    // spender now (Thrasios at the floor), which is what the futility gate reads.
    @Test
    public void aGoOffThrasiosDugInIsNotFutile() {
        start();
        counterspells();
        library(MEMNITE, 5);
        final List<Act> acts = drive(MAX_ACTIONS);
        assertEquals(only(acts, Act::dig).size(), 1, "one activation: " + acts);
        assertEquals(library(), LoopSinks.LIBRARY_FLOOR);
        ai.getManaPool().clearPool(false);
        moveToMain2(game, ai);
        assertNull(LoopSinks.thrasios(ai, line(KINNAN_BASALT)), "no Thrasios step");
        assertSame(driver.mainPhaseAction(List.of()), manaOf(basalt), "not futile: Thrasios spent in it");
    }

    // ---- the final review's round 1 ----

    // Finding 4 (spec section 6.4, amended): the floor counts the cards our own mandatory draw triggers will draw us
    // before our next draw step. Jin-Gitaxias, Core Augur on ours draws seven at our end step, whatever our library
    // holds: a library of fourteen Memnites, Thrasios digs while it holds more than eleven (four, and Jin's seven),
    // three activations, and stops at eleven, so that Jin's draw leaves four.
    @Test
    public void jinsEndStepDrawRaisesTheFloor() {
        start();
        ComboLoopBoards.ready(addCard(JIN, ai));
        library(MEMNITE, 14);
        final List<Seen> seen = play(g -> !g.getPhaseHandler().is(PhaseType.MAIN1));
        assertEquals(only(seen, Seen::dig).size(), 3, "Thrasios's activations: " + seen);
        assertEquals(library(), LoopSinks.LIBRARY_FLOOR + 7, "the floor and Jin's seven");
        assertNull(LoopSinks.thrasios(ai, line(KINNAN_BASALT)), "then none");
    }

    // A mandatory draw trigger of ours that waits on another player's play cannot be counted: Nezahal, Primal Tide
    // draws us a card for each noncreature spell an opponent casts. While one is on our battlefield the plain dig does
    // not run: a library of ten Memnites, no activation.
    @Test
    public void nezahalStopsThePlainDig() {
        start();
        ComboLoopBoards.ready(addCard(NEZAHAL, ai));
        library(MEMNITE, 10);
        assertNull(LoopSinks.thrasios(ai, line(KINNAN_BASALT)), "a plain dig beside Nezahal");
        final List<Seen> seen = play(g -> !g.getPhaseHandler().is(PhaseType.MAIN1));
        assertTrue(only(seen, Seen::dig).isEmpty(), "Thrasios dug: " + seen);
        assertEquals(library(), 10);
    }

    // An optional draw is the stock AI's to decline (its confirmTrigger keeps batch 4's library floor), so it raises
    // no floor: Rhystic Study on ours ("you may draw"), a library of five Memnites, and Thrasios digs from five.
    @Test
    public void anOptionalDrawRaisesNoFloor() {
        start();
        addCard(RHYSTIC, ai);
        library(MEMNITE, 5);
        assertSame(LoopSinks.thrasios(ai, line(KINNAN_BASALT)), digOf(thrasios), "the floor is four");
    }

    // The floor's reading (LoopSinks.floor), in our Main 1: four with no draw trigger of ours; one more for Phyrexian
    // Arena (a draw at our next upkeep, before our draw step) and seven more for Jin-Gitaxias (at our end step);
    // Rhystic Study's optional draw adds nothing; and beside Nezahal, Primal Tide, whose draws wait on the opponents'
    // spells, no dig that stops at the floor runs.
    @Test
    public void theFloorCountsTheDrawsOurTriggersAreDue() {
        start();
        assertEquals(LoopSinks.floor(ai), LoopSinks.LIBRARY_FLOOR, "no draw trigger of ours");
        addCard("Phyrexian Arena", ai);
        assertEquals(LoopSinks.floor(ai), LoopSinks.LIBRARY_FLOOR + 1, "Phyrexian Arena's one at our next upkeep");
        ComboLoopBoards.ready(addCard(JIN, ai));
        assertEquals(LoopSinks.floor(ai), LoopSinks.LIBRARY_FLOOR + 8, "and Jin's seven at our end step");
        addCard(RHYSTIC, ai);
        assertEquals(LoopSinks.floor(ai), LoopSinks.LIBRARY_FLOOR + 8, "an optional draw adds nothing");
        ComboLoopBoards.ready(addCard(NEZAHAL, ai));
        assertEquals(LoopSinks.floor(ai), Integer.MAX_VALUE, "Nezahal's draws cannot be counted");
    }

    // R22's board: Basalt Monolith and Nyxbloom Ancient (3430-4131; no Kinnan) and Thrasios, ready, on ours, in our
    // Main 2, Thassa's Oracle in hand, two untapped Islands and a library of ten Forests. Devotion to blue is
    // Thrasios's 1 alone, so Oracle wins from three cards, one below the floor; returns Oracle
    private Card nyxbloomOracleBoard() {
        game();
        basalt = addCard(BASALT, ai);
        ComboLoopBoards.ready(addCard(NYXBLOOM, ai));
        thrasios = ComboLoopBoards.ready(addCard(THRASIOS, ai));
        moveToMain2(game, ai);
        final Card oracle = addCardToZone(ORACLE, ai, ZoneType.Hand);
        addCard(ISLAND, ai);
        addCard(ISLAND, ai);
        library(FOREST, 10);
        assertTrue(LoopSinks.digsForOracle(ai, line(BASALT_NYXBLOOM)), "the dig toward Oracle runs");
        return oracle;
    }

    // the Oracle dig's board for the hosers' tests: start's, in our Main 2, Thassa's Oracle and Endless One in hand,
    // two untapped Islands and a library of that many Forests; returns Oracle
    private Card hoserBoard(final int forests) {
        start();
        moveToMain2(game, ai);
        final Card oracle = addCardToZone(ORACLE, ai, ZoneType.Hand);
        addCardToZone(ENDLESS_ONE, ai, ZoneType.Hand);
        addCard(ISLAND, ai);
        addCard(ISLAND, ai);
        library(FOREST, forests);
        return oracle;
    }

    // that hoser of Oracle's win (ORACLE_HOSERS) on the opponent's battlefield, Iona, Shield of Emeria naming blue
    private void hoser(final String name) {
        final Card c = addCard(name, opp);
        if ("Iona, Shield of Emeria".equals(name)) {
            c.setChosenColors(new ArrayList<>(List.of("blue")));
        }
    }

    // the rock's own activated Untap ability
    private static SpellAbility untapOf(final Card rock) {
        for (final SpellAbility sa : rock.getNonManaAbilities()) {
            if (sa.getApi() == ApiType.Untap) {
                return sa;
            }
        }
        throw new AssertionError(rock + " has no untap");
    }

    // how many times the rock's untap has run this turn
    private static int untapsOf(final Card rock) {
        return untapOf(rock).getActivationsThisTurn();
    }
}
