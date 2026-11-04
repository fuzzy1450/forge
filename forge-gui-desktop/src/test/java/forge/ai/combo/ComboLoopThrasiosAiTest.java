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
import forge.deck.Deck;
import forge.game.Game;
import forge.game.GameEndReason;
import forge.game.GameLogEntry;
import forge.game.GameRules;
import forge.game.GameStage;
import forge.game.GameType;
import forge.game.Match;
import forge.game.ability.ApiType;
import forge.game.card.Card;
import forge.game.card.CounterEnumType;
import forge.game.mana.Mana;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.player.RegisteredPlayer;
import forge.game.spellability.SpellAbility;
import forge.game.spellability.SpellAbilityStackInstance;
import forge.game.trigger.Trigger;
import forge.game.trigger.TriggerHandler;
import forge.game.zone.ZoneType;
import forge.util.MyRandom;

/**
 * The stock AI's plays on a topped-up pool, and Thrasios, Triton Hero, the loop's dig (spec
 * 2026-10-29-combo-aware-ai-loops-design.md section 6.4 steps 2 and 3, section 6.5; the plan's Task B6, R2, R8, R20 and
 * R22). Below the stock target the driver refills, at it the stock walk plays, paying from the pool; when the walk
 * chooses nothing Thrasios digs, one activation a decision, refilling to its {4} first, while our library holds more
 * than its floor, a card in it would add to our board, and our board is short of five times lethal. With Laboratory
 * Maniac on ours the dig goes to empty and one draw more, whatever the library holds, below the floor only while we can
 * win and no opponent has mana to answer it. With Thassa's Oracle in hand the Oracle line runs instead (section 6.4
 * step 3 and section 6.6, amended 2026-11-04 (the Oracle window)): no dig toward Oracle in a main phase; the driver
 * builds the pool to what a dig of our whole library costs and Oracle's own price, holding Oracle from the stock AI
 * meanwhile, casts Oracle, and above its entering trigger digs to empty, one activation a decision, then passes, the
 * stock AI playing nothing there. Unless a test says otherwise the board is Kinnan's list registered, the tracker
 * reading the shipped lines, every seat on the Default profile, the AI in its own Main 1 with no AI deadline, Kinnan
 * and Thrasios (ready) and Basalt Monolith on our battlefield, our library built card by card, top first, and the
 * opponent at 20 with nothing. {@link #play} runs the whole game loop, where the stock AI decides too; {@link #drive}
 * plays the driver's own decisions with a walk that never chooses anything, and {@link #slot} the go-off slot's alone;
 * {@link #toTheOracleCast}, {@link #castAndTrigger} and {@link #answers} play the Oracle line's build, its cast and its
 * window one decision at a time.
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
    private static final String MURDER = "Murder";
    /** Draws its controller a card as it enters: a trigger of ours that is not Thassa's Oracle's. */
    private static final String VISIONARY = "Elvish Visionary";
    private static final String DIVINATION = "Divination";
    private static final String ISLAND = "Island";
    private static final String FOREST = "Forest";
    private static final String JIN = "Jin-Gitaxias, Core Augur";
    private static final String NEZAHAL = "Nezahal, Primal Tide";
    private static final String RHYSTIC = "Rhystic Study";
    private static final String UNRAVELER = "Fate Unraveler";
    private static final String EIDOLON = "Eidolon of the Great Revel";
    private static final String BURNING_TREE = "Burning-Tree Shaman";
    /** Draws us one whenever an opponent draws their second card each turn (Kinnan's list). */
    private static final String MASTERMIND = "Faerie Mastermind";
    /** Draws us one whenever a player draws their second card each turn, ours included. */
    private static final String TCHALLA = "King T'Challa";
    /** Draws us one whenever a player draws their second card during their turn (ValidPlayer$ Player.Active). */
    private static final String COUNCIL = "The Council of Four";
    /** Draws us two whenever an opponent draws their second card each turn. */
    private static final String UNAGI = "The Unagi of Kyoshi Island";
    /** Draws us one on an opponent's second card each turn, on their attack with two creatures or on their second
     *  spell each turn. */
    private static final String TROUBLE = "Trouble in Pairs";
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

    /** The seats of a four-player game, as {@link #game}, built as AITest builds its three-player one: the AI is
     *  player 1, its opponents players 0, 2 and 3, in that turn order. */
    private void fourPlayerPod() {
        initAndCreateGame();
        final List<RegisteredPlayer> players = new ArrayList<>();
        final Deck deck = new Deck();
        for (final String name : List.of("opponent", "ai", "second", "third")) {
            players.add(new RegisteredPlayer(deck).setPlayer(new LobbyPlayerAi(name, null)));
        }
        final GameRules rules = new GameRules(GameType.Constructed);
        final Game g = new Game(players, rules, new Match(rules, players, "Test"));
        g.setAge(GameStage.Play);
        g.getPhaseHandler().devModeSet(PhaseType.MAIN1, g.getPlayers().get(1));
        g.getPhaseHandler().onStackResolved();
        board(g);
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
            final SpellAbility sa = act();
            decisionDraws += MyRandom.callCount() - before;
            assertEquals(MyRandom.callCount() - before, 0, "a driver decision drew");
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
            SpellAbility sa = act();
            if (sa == null) {
                asks++;
                sa = driver.afterStockWalk();
            }
            decisionDraws += MyRandom.callCount() - before;
            assertEquals(MyRandom.callCount() - before, 0, "a driver decision drew");
            if (sa == null) {
                break;
            }
            acts.add(new Act(sa, sa.getHostCard(), pool(), library()));
            playAndResolve(sa);
        }
        return acts;
    }

    /** {@link #drive}, but with the triggers each action causes put on the stack, as the game loop puts them, before
     *  it resolves (Fate Unraveler's damage for a card drawn). */
    private List<Act> driveWithTriggers(final int max) {
        final List<Act> acts = new ArrayList<>();
        while (acts.size() < max && !game.isGameOver()) {
            game.getAction().checkStateEffects(true);
            SpellAbility sa = act();
            if (sa == null) {
                sa = driver.afterStockWalk();
            }
            if (sa == null) {
                break;
            }
            acts.add(new Act(sa, sa.getHostCard(), pool(), library()));
            ai.getController().playChosenSpellAbility(sa);
            game.getStack().addAllTriggeredAbilitiesToStack();
            while (!game.getStack().isEmpty()) {
                game.getStack().resolveStack();
                game.getStack().addAllTriggeredAbilitiesToStack();
            }
        }
        return acts;
    }

    /** Steps the whole game loop (PhaseHandler.mainLoopStep: every seat decides, the stock AI included) while the game
     *  is not over and {@code done} does not hold, at most {@link #MAX_STEPS} steps; returns our stack items in the
     *  order the loop put them there, each as first seen. */
    private List<Seen> play(final Predicate<Game> done) {
        return play(done, false);
    }

    /** {@link #play}, and with {@code counter} the opponent's Counterspell put above our cast of Thassa's Oracle the
     *  moment the game loop first puts it on the stack, once (ComboLoopBoards.theirResponse: nothing paid, the opponent
     *  holding priority). */
    private List<Seen> play(final Predicate<Game> done, final boolean counter) {
        final List<Seen> seen = new ArrayList<>();
        final Set<Integer> ids = new HashSet<>();
        int poolBefore = pool();
        boolean countered = !counter;
        for (int step = 0; step < MAX_STEPS && !game.isGameOver() && !done.test(game); step++) {
            game.getPhaseHandler().mainLoopStep();
            SpellAbility oracle = null;
            for (final SpellAbilityStackInstance si : game.getStack()) {
                final Card host = si.getSourceCard();
                if (ids.add(si.getId()) && host != null && ai.equals(si.getActivatingPlayer())) {
                    final Seen s = new Seen(si.getSpellAbility(), host, library(), power(), names(ZoneType.Hand),
                            poolBefore);
                    seen.add(s);
                    if (s.cast(ORACLE)) {
                        oracle = si.getSpellAbility();
                    }
                }
            }
            if (oracle != null && !countered) {
                ComboLoopBoards.theirResponse(game, opp, COUNTERSPELL, oracle);
                countered = true;
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
        assertNull(thrasiosOf(line(KINNAN_BASALT)), "then none: the floor");
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
        assertNull(thrasiosOf(l));
        final List<Seen> seen = play(g -> !g.getPhaseHandler().is(PhaseType.MAIN1));
        assertTrue(only(seen, Seen::dig).isEmpty(), "Thrasios dug for nothing: " + seen);
        assertEquals(library(), 10);
        addCardToZone(MEMNITE, ai, ZoneType.Library);
        assertTrue(LoopSinks.digWorth(ai, l), "a Memnite at the bottom");
        assertSame(thrasiosOf(l), digOf(thrasios));
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
        assertNull(thrasiosOf(line(KINNAN_BASALT)), "Thrasios's own stop");
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

    // R22 amended for Oracle (Evan, 2026-11-04; section 6.4 step 3 and section 6.6, amended 2026-11-04 (the Oracle
    // window)): the dig in response to Oracle's trigger goes to empty whatever mana the opponents have open, since only
    // a counter of the trigger can stop that finish, where a stop above empty loses to any removal of a blue permanent
    // of ours. nyxbloomOracleBoard (Basalt Monolith and Nyxbloom Ancient, 3430-4131, six a cycle; devotion to blue
    // Thrasios's 1) with the opponent's two untapped Islands, in our Main 2: through the whole game loop Oracle is cast
    // with our library at ten, nothing dug before it, ten digs then take the library to empty above its trigger, and
    // the game ends WinsGameSpellEffect. Was noDigBelowTheFloorIntoOpenMana, which pinned R22's Oracle clause: the dig
    // toward Oracle stopped at the floor, four, into open mana, and Oracle stayed held.
    @Test
    public void theWindowDigsToEmptyAgainstOpenMana() {
        nyxbloomOracleBoard();
        addCard(ISLAND, opp);
        addCard(ISLAND, opp);
        final List<Seen> seen = play(g -> false);
        final List<Seen> casts = only(seen, s -> s.cast(ORACLE));
        assertEquals(casts.size(), 1, "one Oracle cast: " + seen);
        assertEquals(casts.get(0).library(), 10, "cast with the library whole: " + seen);
        assertTrue(only(seen.subList(0, seen.indexOf(casts.get(0))), Seen::dig).isEmpty(),
                "a dig before the cast: " + seen);
        assertWonBy(ORACLE, seen);
        assertEquals(only(seen, Seen::dig).size(), 10, "ten to empty, above the trigger: " + seen);
        assertTrue(ai.getCardsIn(ZoneType.Library).isEmpty());
    }

    // The Oracle line (section 6.4 step 3 and section 6.6, amended 2026-11-04 (the Oracle window)): Thassa's Oracle in
    // hand, two untapped Islands and a library of ten Forests, in our Main 2. Devotion to blue is Kinnan's and
    // Thrasios's 2, so Oracle's threshold is four, and Oracle is held from before the go-off's first decision. The
    // driver digs nothing first: every action before the cast is Basalt Monolith's, until at a boundary the pool holds
    // what a dig of our whole library costs, Thrasios's 4 for each of the ten cards, and Oracle's own 2 ({U}{U}, which
    // the Islands pay): 42, after 42 cycles of Basalt's tap and untap. It casts Oracle with the library at ten. Above
    // Oracle's entering trigger the stack-response slot answers Thrasios's activation, one a decision, each resolved
    // alone above the trigger, the library falling from ten to none, and then a pass; the trigger resolves on the empty
    // library and the game ends WinsGameSpellEffect, the pool's 2 left. Was theOracleDigHoldsOracleAndWins, which
    // pinned the dig first: ten to four in the main phase, then Oracle's cast at its threshold.
    @Test
    public void theOracleLineCastsOracleFirstAndDigsInResponse() {
        final Card oracle = oracleBoard(10);
        assertTrue(LoopSinks.digsForOracle(ai, line(KINNAN_BASALT)));
        assertEquals(LoopSinks.oracleThreshold(ai), 4, "Kinnan's {U}, Thrasios's {U} and Oracle's {U}{U}");
        assertTrue(driver.holdsOracle(castOf(oracle)), "held before the go-off's first decision");
        final List<SpellAbility> built = new ArrayList<>();
        final SpellAbility cast = toTheOracleCast(built);
        assertNotNull(cast, "the driver never cast Oracle; its actions: " + built);
        assertSame(cast.getHostCard(), oracle);
        for (final SpellAbility sa : built) {
            assertSame(sa.getHostCard(), basalt, "an action before the cast that is not Basalt's: " + built);
        }
        assertEquals(built.size(), 2 * 42, "42 cycles of Basalt's tap and untap");
        assertEquals(library(), 10, "no dig before the cast");
        assertEquals(pool(), 4 * 10 + 2, "the pool at the cast: Thrasios's 4 a card and Oracle's 2");
        assertTrue(basalt.isUntapped(), "the cast at a boundary");
        final SpellAbilityStackInstance top = castAndTrigger(cast);
        assertTrue(top.isTrigger() && ai.equals(top.getActivatingPlayer()), "our trigger on top: " + top);
        assertEquals(ComboTracker.nameOf(top.getSourceCard()), ORACLE);
        assertEquals(pool(), 4 * 10 + 2, "the Islands paid Oracle's {U}{U}");
        final List<LoopDriver.Answer> answers = answers(MAX_ACTIONS);
        assertEquals(answers.size(), 11, "ten digs and a pass: " + answers);
        for (int i = 0; i < 10; i++) {
            assertSame(answers.get(i).action(), digOf(thrasios), "the window's answer " + i + ": " + answers);
        }
        assertNotNull(answers.get(10), "the stock's answer after the dig, not a pass");
        assertNull(answers.get(10).action(), "a pass once the library is empty");
        assertEquals(library(), 0, "dug to empty");
        assertEquals(pool(), 2);
        resolveTheTrigger();
        assertWonBy(ORACLE, List.of());
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
        assertNull(thrasiosOf(line(KINNAN_BASALT)), "Thrasios's {4} refused");
        final List<Seen> seen = play(g -> !g.getPhaseHandler().is(PhaseType.MAIN1));
        assertTrue(only(seen, Seen::dig).isEmpty(), "a Thrasios activation: " + seen);
        final Card cast = onOurBattlefield(ENDLESS_ONE);
        assertNotNull(cast, "the stock plays go on: " + seen);
        assertEquals(cast.getCounters(CounterEnumType.P1P1), 99);
        assertFalse(LoopTracker.Snapshot.of(ai).isDecisive(), "short of five times lethal");
        game.getAction().moveToGraveyard(totem, null);
        assertSame(thrasiosOf(line(KINNAN_BASALT)), digOf(thrasios), "the Totem gone: a dig");
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
        assertNull(act(), "two idle judged rounds: the driver refills no more");
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
        assertNull(act(), "no loop runs: no go-off");
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
        assertSame(thrasiosOf(l), digOf(thrasios), "below the floor, the opponent with nothing");
        addCard("Platinum Angel", opp);
        game.getAction().checkStateEffects(true);
        assertTrue(ai.cantWin());
        assertNull(thrasiosOf(l), "we cannot win: the floor holds");
        library(FOREST, 2);
        assertSame(thrasiosOf(l), digOf(thrasios), "five cards: above the floor");
    }

    // The hold's edge (its shape unchanged, amended 2026-11-04 (the Oracle window): it waits for the Oracle line's
    // window): our library above Oracle's threshold holds Oracle, at the threshold it does not. Thassa's Oracle in
    // hand, two Islands, Kinnan, Basalt and Thrasios (a threshold of 4), in our Main 2: five cards, held and the stock
    // cast vetoed, the reading drawing nothing, and no dig toward Oracle in a main phase, the line digging in its
    // window; four, neither held nor dug for. Only our hand cast of Oracle is held: not another spell, nor a cast of an
    // Oracle from our graveyard. And none while no loop line can run (the opponent's Null Rod denies the rock lines) or
    // Thrasios cannot dig (the opponent's Cursed Totem). Its dig half, at four "nor a dig: Oracle's cast is due",
    // pinned the dig toward Oracle's stop at the threshold, retired with that dig.
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
        assertNull(thrasiosOf(line(KINNAN_BASALT)), "five cards: a main-phase dig toward Oracle");
        assertFalse(driver.holdsOracle(castOf(counterspell)), "no other spell is held");
        assertFalse(driver.holdsOracle(castOf(buried)), "nor an Oracle cast from anywhere but our hand");
        game.getAction().moveToGraveyard(ai.getCardsIn(ZoneType.Library).get(0), null);
        assertEquals(library(), 4);
        assertFalse(driver.holdsOracle(castOf(oracle)), "at the threshold: no hold");
        assertFalse(ComboHold.vetoesCast(ai, castOf(oracle)));
        assertNull(thrasiosOf(line(KINNAN_BASALT)), "four cards: a main-phase dig toward Oracle");
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
    // and Orrery on ours, no land at all, Thassa's Oracle in hand and a library of ten Forests. The Oracle line can run
    // and holds Oracle; no dig toward Oracle runs in a main phase (amended 2026-11-04 (the Oracle window)): the
    // go-off's first decision is the line's build, Basalt's tap. Its old dig half, "the dig toward Oracle runs", pinned
    // the main-phase dig toward Oracle, retired.
    @Test
    public void chromaticOrreryLetsTheLoopPayOraclesBluePips() {
        start();
        addCard("Chromatic Orrery", ai);
        final Card oracle = addCardToZone(ORACLE, ai, ZoneType.Hand);
        library(FOREST, 10);
        final ComboLines.Line l = line(KINNAN_BASALT);
        assertTrue(LoopSinks.digsForOracle(ai, l), "no land, but the loop's mana pays {U}{U} through Orrery");
        assertTrue(driver.holdsOracle(castOf(oracle)), "and holds Oracle");
        assertNull(thrasiosOf(l), "a main-phase dig toward Oracle");
        assertSame(act(), manaOf(basalt), "the line's build");
    }

    // Oracle's cast through Chromatic Orrery is paid from the line's pool (section 6.4 step 3, amended 2026-11-04 (the
    // Oracle window)): Orrery's board with a library of four, in our Main 2. No land pays Oracle's {U}{U}, so the pool
    // the line builds holds it beside the window's need: Thrasios's 4 for each of the four cards and Oracle's 2, 18 at
    // a boundary, after 18 cycles of Basalt's tap and untap. The cast takes its {U}{U} through Orrery and leaves the
    // window's 16 at least; above the trigger the driver digs four and passes, and the game ends WinsGameSpellEffect.
    // Was theOracleCastThroughChromaticOrreryWaitsForThePool, which pinned the cast at the first pool that paid it, 4,
    // at Oracle's threshold.
    @Test
    public void theOracleLinePaysOraclesBlueFromItsPoolThroughOrrery() {
        start();
        moveToMain2(game, ai);
        addCard("Chromatic Orrery", ai);
        final Card oracle = addCardToZone(ORACLE, ai, ZoneType.Hand);
        library(FOREST, 4);
        final List<SpellAbility> built = new ArrayList<>();
        final SpellAbility cast = toTheOracleCast(built);
        assertNotNull(cast, "the driver never cast Oracle; its actions: " + built);
        assertSame(cast.getHostCard(), oracle);
        assertEquals(pool(), 4 * 4 + 2, "the pool at the cast: " + built);
        assertEquals(built.size(), 2 * 18, "18 cycles of Basalt's tap and untap");
        castAndTrigger(cast);
        assertTrue(pool() >= 4 * 4, "the window's need kept: " + pool());
        final List<LoopDriver.Answer> answers = answers(MAX_ACTIONS);
        assertEquals(answers.size(), 5, "four digs and a pass: " + answers);
        assertNull(answers.get(4).action(), "a pass: " + answers);
        assertEquals(library(), 0, "dug to empty");
        resolveTheTrigger();
        assertWonBy(ORACLE, List.of());
    }

    // A tax on Oracle's cast is built for (section 6.4 step 3, amended 2026-11-04 (the Oracle window)): under the
    // opponent's Sphere of Resistance (spells cost {1} more) Oracle costs {1}{U}{U}, which our two Islands alone do not
    // pay; the Oracle line's board with a library of five. The line builds to Thrasios's 4 for each of the five cards
    // and Oracle's 3, 23, casts Oracle, the pool paying what the Islands do not, and keeps the window's 20 at least:
    // five digs, a pass, and the game ends WinsGameSpellEffect. Was aTaxedOracleCastWaitsForThePoolToPayIt, which
    // pinned a cast the check refused for its price waiting for the refill that paid it, at Oracle's threshold.
    @Test
    public void aTaxedOracleCastIsPaidFromTheBuild() {
        final Card oracle = oracleBoard(5);
        addCard("Sphere of Resistance", opp);
        assertEquals(ComboExecute.priced(ai, LoopSinks.oracleCast(ai)).getCMC(), 3, "Oracle's {U}{U} and the tax");
        final List<SpellAbility> built = new ArrayList<>();
        final SpellAbility cast = toTheOracleCast(built);
        assertNotNull(cast, "the driver never cast Oracle; its actions: " + built);
        assertSame(cast.getHostCard(), oracle);
        assertEquals(pool(), 4 * 5 + 3, "the pool at the cast: " + built);
        castAndTrigger(cast);
        assertTrue(pool() >= 4 * 5, "the window's need kept: " + pool());
        final List<LoopDriver.Answer> answers = answers(MAX_ACTIONS);
        assertEquals(answers.size(), 6, "five digs and a pass: " + answers);
        assertNull(answers.get(5).action(), "a pass: " + answers);
        resolveTheTrigger();
        assertWonBy(ORACLE, List.of());
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
        assertSame(thrasiosOf(l), digOf(thrasios), "their Island tapped: no mana to answer");
        island.setTapped(false);
        assertNull(thrasiosOf(l), "their Island untapped");
        island.setTapped(true);
        opp.getManaPool().addMana(new Mana((byte) ManaAtom.BLUE, island, null, opp));
        assertNull(thrasiosOf(l), "mana in their pool");
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
        assertSame(thrasiosOf(l), digOf(thrasios), "no opponent has mana");
        addCard(MEMNITE, opp);
        assertSame(thrasiosOf(l), digOf(thrasios), "an untapped Memnite makes no mana");
        final Card island = addCard(ISLAND, other);
        assertNull(thrasiosOf(l), "the second opponent's untapped Island");
        island.setTapped(true);
        assertSame(thrasiosOf(l), digOf(thrasios), "their Island tapped");
        final Card ring = addCard("Sol Ring", opp);
        assertNull(thrasiosOf(l), "the first opponent's untapped Sol Ring, a permanent that is no land");
        ring.setTapped(true);
        assertSame(thrasiosOf(l), digOf(thrasios), "their Sol Ring tapped");
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
        assertSame(thrasiosOf(l), digOf(thrasios), "Jace: a dig below the floor");
        game.getAction().moveToGraveyard(jace, null);
        assertNull(thrasiosOf(l), "no winner: the floor");
        final Card lab = addCard(LAB, ai);
        assertSame(thrasiosOf(l), digOf(thrasios), "Laboratory Maniac");
        frog(lab);
        assertNull(thrasiosOf(l), "a Laboratory Maniac without its abilities wins nothing");
    }

    // Thrasios's {4} is Thrasios's, found by its name: with Thrasios gone, another card of ours with an activated Scry
    // ability (Crystal Ball) is no dig, though a library of Memnites would be worth one.
    @Test
    public void aScryAbilityOfAnotherCardIsNoDig() {
        start();
        game.getAction().moveToGraveyard(thrasios, null);
        addCard("Crystal Ball", ai);
        library(MEMNITE, 10);
        assertNull(thrasiosOf(line(KINNAN_BASALT)), "Crystal Ball is not Thrasios");
    }

    // A Thrasios that lost its abilities (the opponent's Turn to Frog) has no {4}: no dig, nothing thrown, and with no
    // dig Oracle's threshold is Oracle's own {U}{U}; the driver's decisions make no dig either.
    @Test
    public void aThrasiosThatLostItsAbilitiesIsNoDig() {
        start();
        library(MEMNITE, 10);
        frog(thrasios);
        final ComboLines.Line l = line(KINNAN_BASALT);
        assertNull(thrasiosOf(l), "no ability, no dig");
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
        assertNull(thrasiosOf(l));
        addCard(FOREST, ai);
        addCard(FOREST, ai);
        assertTrue(LoopSinks.digWorth(ai, l), "two Forests pay the {G}{G}");
        assertSame(thrasiosOf(l), digOf(thrasios));
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
    // Oracle, which could not win, nor building the Oracle line's pool for it (amended 2026-11-04 (the Oracle window)):
    // two cycles to Oracle's {U}{U}, then the walk's turn.
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
        final SpellAbility ping = act();
        assertNotNull(ping, "a go-off: Ballista can kill the Angel");
        assertEquals(ping.getApi(), ApiType.DealDamage, "the ping first: " + ping);
        assertSame(ping.getTargets().get(0), angel, "at the Angel");
        playAndResolve(ping);
        angel.setDamage(0);
        final SpellAbility next = act();
        assertTrue(ai.cantWin(), "the Angel stands");
        assertNotNull(next, "the go-off goes on for the other opponent");
        assertSame(next, manaOf(basalt), "a refill, no Oracle cast: " + next);
        playAndResolve(next);
        final List<SpellAbility> rest = slot(MAX_ACTIONS);
        assertTrue(only(rest, ComboLoopThrasiosAiTest::isOracleCast).isEmpty(), "Oracle was cast: " + rest);
        assertEquals(rest.size(), 3, "to the stock target, Oracle's {U}{U}, then the walk's turn: " + rest);
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
            assertNull(thrasiosOf(l), hoser);
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
            assertSame(act(), manaOf(basalt), hoser);
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

    // The futility gate never holds back the Oracle line (section 6.2, amended 2026-11-04 (the Oracle window): Thassa's
    // Oracle's cast at the head of its line still starts a go-off): the Oracle line's board with room for 40 actions,
    // where the line takes 95 (84 cycle steps, the cast and ten digs) and does not fit. The go-off builds to the stock
    // target, Oracle's {U}{U}, the walk chooses nothing (Oracle held) and ten Forests hold nothing worth a plain dig: a
    // futile build, its fingerprint kept, and the same board starts no go-off again. Our library milled to three, our
    // hand and battlefield as they were, the line fits, 28 of the 36 actions left (twelve cycles from the pool's 2 to
    // 14, the cast and three digs), and the same board starts a go-off that casts Oracle. Was
    // theOracleCastStartsAGoOffPastTheFutilityGate, whose futile build was the dig toward Oracle's stop at the floor.
    @Test
    public void theOracleLineStartsAGoOffPastTheFutilityGate() {
        final Card oracle = oracleBoard(10);
        driver.useRounds(new LoopRounds(40, LoopRounds.GAME_CAP));
        final List<Act> acts = drive(MAX_ACTIONS);
        assertEquals(acts.size(), 4, "built to Oracle's 2, then the walk's turn: " + acts);
        assertEquals(asks, 1, "the walk's turn, once");
        assertTrue(only(acts, Act::dig).isEmpty(), "a dig: " + acts);
        assertNull(act(), "the futile build is not repeated");
        while (library() > 3) {
            game.getAction().moveToGraveyard(ai.getCardsIn(ZoneType.Library).get(0), null);
        }
        final List<SpellAbility> built = new ArrayList<>();
        final SpellAbility cast = toTheOracleCast(built);
        assertNotNull(cast, "a go-off past the futility gate; its actions: " + built);
        assertSame(cast.getHostCard(), oracle, "Oracle's cast: " + cast);
        assertEquals(built.size(), 2 * 12, "twelve cycles from the pool's 2 to 14");
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
        assertNull(act(), "the walk's turn first");
        final SpellAbility tap = driver.afterStockWalk();
        assertSame(tap, manaOf(basalt), "the walk chose nothing: the refill toward Thrasios's {4}");
        playAndResolve(tap);
        moveToMain2(game, ai);
        ai.getManaPool().clearPool(false);
        basalt.setTapped(false);
        assertNull(act(), "a new phase: the walk asked first again");
        final SpellAbility tap2 = driver.afterStockWalk();
        assertSame(tap2, manaOf(basalt), "it chose nothing: the refill toward Thrasios");
        playAndResolve(tap2);
        final SpellAbility untap = act();
        assertSame(untap, untapOf(basalt), "the refill goes on, the walk not asked");
        playAndResolve(untap);
        final Card totem = addCard("Cursed Totem", opp);
        assertNull(act(), "Thrasios cannot dig: the walk's turn at the boundary");
        assertNull(driver.afterStockWalk(), "nothing for Thrasios: the go-off ends");
        game.getAction().moveToGraveyard(totem, null);
        assertNull(act(), "the Totem gone: the walk asked first");
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
        assertNotNull(act(), "one idle round since Thrasios's, not two");
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
            assertNull(act(), "the walk's turn first");
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
        assertNull(thrasiosOf(line(KINNAN_BASALT)), "no Thrasios step");
        assertSame(act(), manaOf(basalt), "not futile: Thrasios spent in it");
    }

    // ---- the final review's round 1 ----

    // Finding 4 (spec section 6.4, amended): the plain dig's floor keeps our next four draw steps and every card our
    // own mandatory draw triggers will draw us before the last of them (amended by the final-fix compliance review's
    // round 1). Jin-Gitaxias, Core Augur on ours draws seven at each of our end steps, whatever our library holds: a
    // library of 35 Memnites, Thrasios digs while it holds more than 32 (four, and Jin's seven at this turn's end step
    // and at each of the three after it), three activations, and stops at 32.
    @Test
    public void jinsEndStepDrawRaisesTheFloor() {
        start();
        ComboLoopBoards.ready(addCard(JIN, ai));
        library(MEMNITE, 35);
        final List<Seen> seen = play(g -> !g.getPhaseHandler().is(PhaseType.MAIN1));
        assertEquals(only(seen, Seen::dig).size(), 3, "Thrasios's activations: " + seen);
        assertEquals(library(), LoopSinks.LIBRARY_FLOOR + 7 * 4, "the floor and Jin's sevens");
        assertNull(thrasiosOf(line(KINNAN_BASALT)), "then none");
    }

    // A mandatory draw trigger of ours that waits on another player's play cannot be counted: Nezahal, Primal Tide
    // draws us a card for each noncreature spell an opponent casts. While one is on our battlefield the plain dig does
    // not run: a library of ten Memnites, no activation.
    @Test
    public void nezahalStopsThePlainDig() {
        start();
        ComboLoopBoards.ready(addCard(NEZAHAL, ai));
        library(MEMNITE, 10);
        assertNull(thrasiosOf(line(KINNAN_BASALT)), "a plain dig beside Nezahal");
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
        assertSame(thrasiosOf(line(KINNAN_BASALT)), digOf(thrasios), "the floor is four");
    }

    // Finding 1, R30: a draw that costs us life ends Thrasios's dig for the phase. The opponent's Fate Unraveler deals
    // 1 to us for each card we draw, which no price of the activation reads: a library of ten Memnites, one activation
    // draws one, its 1 damage is measured at the next decision, and Thrasios digs no more (the floor alone would have
    // taken the library to four, at 1 a draw).
    @Test
    public void aDrawThatCostsLifeEndsTheDig() {
        start();
        addCard(UNRAVELER, opp);
        library(MEMNITE, 10);
        final int life = ai.getLife();
        final List<Seen> seen = play(g -> !g.getPhaseHandler().is(PhaseType.MAIN1));
        assertEquals(only(seen, Seen::dig).size(), 1, "Thrasios's activations: " + seen);
        assertEquals(ai.getLife(), life - 1, "one draw's damage");
    }

    // ---- the final review's round 1: what its mutation pass found untested ----

    // The plain dig's floor reads a count as it is written (LoopSinks.plainFloor's plain number): a whole number of one
    // to three digits, each read in base ten, one card when none is written; any other count cannot be read, and beside
    // it no plain dig runs, beside a count it can read or not. Each draw here is our upkeep trigger's, once at each of
    // our next four upkeeps. Moved from the floor of the dig toward Thassa's Oracle, retired 2026-11-04 (the Oracle
    // window), which counted it once, before our next draw step.
    @Test
    public void thePlainDigsFloorReadsAPlainNumberOfCardsAsWritten() {
        start();
        final int base = LoopSinks.LIBRARY_FLOOR;
        final String up = phaseTrigger("Upkeep", "You");
        assertEquals(plainFloorWith(up, "DB$ Draw | Defined$ You | NumCards$ 2"), base + 2 * 4, "2");
        assertEquals(plainFloorWith(up, "DB$ Draw | Defined$ You"), base + 4, "no NumCards: one card");
        assertEquals(plainFloorWith(up, "DB$ Draw | NumCards$ 3"), base + 3 * 4, "no Defined: us");
        assertEquals(plainFloorWith(up, "DB$ Draw | Defined$ You | NumCards$ 19"), base + 19 * 4, "19");
        assertEquals(plainFloorWith(up, "DB$ Draw | Defined$ You | NumCards$ 10"), base + 10 * 4, "10");
        assertEquals(plainFloorWith(up, "DB$ Draw | Defined$ You | NumCards$ 123"), base + 123 * 4, "123");
        assertEquals(plainFloorWith(up, "DB$ Draw | Defined$ You | NumCards$ 0"), base, "0");
        assertEquals(plainFloorWith(up, "DB$ Draw | Defined$ You | NumCards$ 1234"), Integer.MAX_VALUE,
                "four digits are no plain number");
        assertEquals(plainFloorWith(up, "DB$ Draw | Defined$ You | NumCards$ X"), Integer.MAX_VALUE, "X");
        assertEquals(plainFloorWith(up, "DB$ Draw | Defined$ You | NumCards$ 5."), Integer.MAX_VALUE, "5.");
        assertEquals(plainFloorWithEach(new String[] {up, "DB$ Draw | Defined$ You | NumCards$ 7"},
                new String[] {up, "DB$ Draw | Defined$ You | NumCards$ X"}), Integer.MAX_VALUE,
                "a count that cannot be read, beside one that can");
    }

    // The plain dig's floor counts a draw of ours that is mandatory: not one with a may on its trigger or on its Draw,
    // nor one with an UnlessCost, nor an opponent's draw, nor, as spec section 9 reads it, the triggered player's; a
    // draw by each player is ours too. A mandatory draw of ours on a trigger of another mode, or on a phase trigger
    // that names no phase, cannot be counted. A suppressed trigger draws nothing, nor does one that works from another
    // zone. Each draw that counts here is our upkeep trigger's three, at each of our next four upkeeps. Moved from the
    // floor of the dig toward Thassa's Oracle, retired 2026-11-04 (the Oracle window).
    @Test
    public void thePlainDigsFloorCountsOnlyWhatWeMustDraw() {
        start();
        final int base = LoopSinks.LIBRARY_FLOOR;
        final String up = phaseTrigger("Upkeep", "You");
        assertEquals(plainFloorWith(up + " | OptionalDecider$ You", "DB$ Draw | Defined$ You | NumCards$ 3"), base,
                "a may on the trigger");
        assertEquals(plainFloorWith(up, "DB$ Draw | Defined$ You | NumCards$ 3 | OptionalDecider$ You"), base,
                "a may on the Draw");
        assertEquals(plainFloorWith(up, "DB$ Draw | Defined$ You | NumCards$ 3 | UnlessCost$ 1"), base,
                "an UnlessCost");
        assertEquals(plainFloorWith(up, "DB$ Draw | Defined$ Player | NumCards$ 3"), base + 3 * 4,
                "each player draws");
        assertEquals(plainFloorWith(up, "DB$ Draw | Defined$ Opponent | NumCards$ 3"), base, "an opponent draws");
        assertEquals(plainFloorWith(up, "DB$ Draw | Defined$ TriggeredPlayer | NumCards$ 3"), base,
                "the triggered player draws");
        assertEquals(plainFloorWith("Mode$ SpellCast | Phase$ Upkeep | ValidPlayer$ You | TriggerZones$ Battlefield"
                + " | Execute$ TrigDraw", "DB$ Draw | Defined$ You | NumCards$ 3"), Integer.MAX_VALUE,
                "another mode, whatever else it says");
        assertEquals(plainFloorWith("Mode$ Phase | ValidPlayer$ You | TriggerZones$ Battlefield | Execute$ TrigDraw",
                "DB$ Draw | Defined$ You | NumCards$ 3"), Integer.MAX_VALUE, "a phase trigger of no phase");
        final Card host = addCard(MEMNITE, ai);
        host.setSVar("TrigDraw", "DB$ Draw | Defined$ You | NumCards$ 3");
        final Trigger trigger = TriggerHandler.parseTrigger(up, host, true);
        host.addTrigger(trigger);
        assertEquals(plainFloorOf(), base + 3 * 4, "the control");
        trigger.setSuppressed(true);
        assertEquals(plainFloorOf(), base, "a suppressed trigger draws nothing");
        trigger.setSuppressed(false);
        assertEquals(plainFloorOf(), base + 3 * 4, "and draws again once it is not");
        game.getAction().moveToGraveyard(host, null);
        assertEquals(plainFloorWith(up.replace("Battlefield", "Graveyard"), "DB$ Draw | Defined$ You"), base,
                "a trigger that works from another zone draws nothing here");
    }

    // The plain dig's floor counts each draw once for each firing before the fourth of our draw steps from now: at our
    // next untap and upkeep (not at our draw step), in the rest of this turn of ours, in each other player's turn still
    // in the game, where the trigger's players take that turn's player, and once more for each of those phases in each
    // of the three rounds of turns after our next draw step. A draw of three: four firings, twelve cards, for a phase
    // of ours or of the opponent's still to come before our next draw step, three, nine, for one that is not, and
    // eight, 24, for each player's. Moved from the floor of the dig toward Thassa's Oracle, retired 2026-11-04 (the
    // Oracle window), which counted the firings before our next draw step alone.
    @Test
    public void thePlainDigsFloorCountsEachFiringOfEachPhase() {
        start();
        final int base = LoopSinks.LIBRARY_FLOOR;
        final String draw = "DB$ Draw | Defined$ You | NumCards$ 3";
        assertEquals(plainFloorWith(phaseTrigger("Upkeep", "Player"), draw), base + 3 * 8,
                "each player's upkeep: ours and the opponent's");
        assertEquals(plainFloorWith(phaseTrigger("Upkeep", "Opponent"), draw), base + 3 * 4, "the opponent's alone");
        assertEquals(plainFloorWith(phaseTrigger("Untap", "You"), draw), base + 3 * 4, "our next untap");
        assertEquals(plainFloorWith(phaseTrigger("Draw", "You"), draw), base + 3 * 3,
                "our draw step comes after our next draw");
        assertEquals(plainFloorWith(phaseTrigger("Main1", "You"), draw), base + 3 * 3,
                "this phase is not still to come");
        assertEquals(plainFloorWith(phaseTrigger("End of Turn", "You"), draw), base + 3 * 4, "the rest of our turn");
        assertEquals(plainFloorWith(phaseTrigger("End of Turn", "Opponent"), draw), base + 3 * 4,
                "the opponent's end step, not ours");
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, opp, 4);
        assertEquals(plainFloorWith(phaseTrigger("End of Turn", "You"), draw), base + 3 * 3,
                "in the opponent's turn our end step comes after our next draw");
        assertEquals(plainFloorWith(phaseTrigger("Upkeep", "You"), draw), base + 3 * 4, "our next upkeep");
    }

    // The dig to empty keeps the floor of four, whatever our triggers will draw: Laboratory Maniac wins on a draw from
    // an empty library, Jin's seven among them. Jin on ours, the opponent's untapped Island, eight cards: the dig goes
    // on, below the plain dig's floor.
    @Test
    public void theDigToEmptyKeepsTheFloorOfFour() {
        start();
        addCard(LAB, ai);
        ComboLoopBoards.ready(addCard(JIN, ai));
        addCard(ISLAND, opp);
        library(MEMNITE, 8);
        assertEquals(plainFloorOf(), LoopSinks.LIBRARY_FLOOR + 7 * 4, "the plain dig's floor");
        assertSame(thrasiosOf(line(KINNAN_BASALT)), digOf(thrasios), "above four: the dig to empty");
    }

    // Thrasios takes no dig that costs life (R30: a dig buys no kill this turn): the opponent's Burning-Tree Shaman
    // deals 1 to a player who activates an ability that is no mana ability.
    @Test
    public void thrasiosTakesNoDigThatCostsLife() {
        start();
        library(MEMNITE, 10);
        assertSame(thrasiosOf(line(KINNAN_BASALT)), digOf(thrasios), "no punisher: the dig");
        addCard(BURNING_TREE, opp);
        assertNull(thrasiosOf(line(KINNAN_BASALT)), "a dig that costs 1 life");
    }

    // R30's measure for Thrasios: a dig that cost more life than its price (the opponent's Fate Unraveler's 1 for the
    // card it drew) ends the dig, not the go-off: the refill goes on toward the stock target (Endless One in hand), so
    // the driver's actions follow the one activation.
    @Test
    public void aDigThatCostsMoreThanItsPriceEndsTheDigNotTheGoOff() {
        start();
        addCard(UNRAVELER, opp);
        library(MEMNITE, 10);
        addCardToZone(ENDLESS_ONE, ai, ZoneType.Hand);
        final List<Act> acts = driveWithTriggers(MAX_ACTIONS);
        final List<Act> digs = only(acts, Act::dig);
        assertEquals(digs.size(), 1, "one activation: its draw cost 1 life and no price read it");
        final int at = acts.indexOf(digs.get(0));
        assertTrue(acts.size() > at + 1, "the go-off went on after the dig ended: " + at + " of " + acts.size());
    }

    // Thassa's Oracle is not cast at a price of our whole life (the stock refusal's mirror): the opponent's Eidolon of
    // the Great Revel deals 2 to us for it, its trigger resolving before Oracle does; nyxbloomOracleBoard's library
    // milled to three. At our 2 the Oracle line does not run (amended 2026-11-04 (the Oracle window): it builds for no
    // cast the driver may not make): the go-off builds to the stock target, one cycle of six, then passes the decision
    // to the walk, and never casts Oracle, where a build for the refused cast would run on to the turn's cap. At our 3
    // the line builds its pool, Thrasios's 4 for each of the three cards and Oracle's 2, 14 at least (18 at the third
    // boundary, six a cycle), and casts Oracle.
    @Test
    public void oracleIsNotCastAtAPriceOfOurWholeLife() {
        oracleAtLife(2);
        final List<SpellAbility> refused = slot(MAX_ACTIONS);
        assertTrue(only(refused, ComboLoopThrasiosAiTest::isOracleCast).isEmpty(),
                "Oracle's cast costs 2 life, all we have: " + refused);
        assertEquals(refused.size(), 2, "one cycle to the stock target, Oracle's {U}{U}, then the walk's turn: "
                + refused);
        oracleAtLife(3);
        final List<SpellAbility> built = new ArrayList<>();
        final SpellAbility cast = toTheOracleCast(built);
        assertNotNull(cast, "a life more: a go-off that casts Oracle; its actions: " + built);
        assertEquals(ComboTracker.nameOf(cast.getHostCard()), ORACLE, "Oracle's cast: " + cast);
        assertTrue(pool() >= 4 * 3 + 2, "the window's need and Oracle's 2 built first: " + pool());
    }

    // ---- the final review's round 1: what its mutation pass's round 2 found untested ----

    // A trigger of several phases fires at each of them: the plain dig's floor counts its draw once for each of its
    // phases still to come before our next draw step and once more for each of its phases in each of the three rounds
    // after it. In our Main 1 a trigger of our upkeep and end step fires at this turn's end step and at our next
    // upkeep, then twice a round, eight firings; one of each of our main phases (Main1,Main2, as the scripts write "at
    // the beginning of each of your main phases") at this turn's Main 2 alone, then twice a round, seven. Moved from
    // the floor of the dig toward Thassa's Oracle, retired 2026-11-04 (the Oracle window), which counted two and one.
    @Test
    public void thePlainDigsFloorCountsEachPhaseOfATriggerOnce() {
        start();
        final int base = LoopSinks.LIBRARY_FLOOR;
        final String draw = "DB$ Draw | Defined$ You | NumCards$ 3";
        assertEquals(plainFloorWith(phaseTrigger("Upkeep,End of Turn", "You"), draw), base + 3 * 8,
                "this turn's end step and our next upkeep, then two a round");
        assertEquals(plainFloorWith(phaseTrigger("Main1,Main2", "You"), draw), base + 3 * 7,
                "this turn's Main 2, its Main 1 not still to come, then two a round");
    }

    // ---- the final review's round 1: what its mutation pass's round 3 found untested ----

    // Only our own triggers raise the plain dig's floor (section 6.4: our own mandatory draw triggers): a draw on the
    // opponent's battlefield draws them, its Defined You being its controller, and one that names each player is left
    // uncounted (section 9), nor does a trigger of theirs of another mode, which would make the floor uncountable were
    // it ours, make anything of it. The control: the same upkeep draw on ours, three at each of our next four upkeeps.
    // Moved from the floor of the dig toward Thassa's Oracle, retired 2026-11-04 (the Oracle window).
    @Test
    public void anOpponentsDrawTriggersRaiseNoPlainFloorOfOurs() {
        start();
        final int base = LoopSinks.LIBRARY_FLOOR;
        final String draw = "DB$ Draw | Defined$ You | NumCards$ 3";
        assertEquals(plainFloorWithTheirs(phaseTrigger("Upkeep", "You"), draw), base, "their upkeep draw draws them");
        assertEquals(plainFloorWithTheirs(phaseTrigger("Upkeep", "You"), "DB$ Draw | Defined$ Player | NumCards$ 3"),
                base, "a draw by each player at their upkeep is not counted (section 9)");
        assertEquals(plainFloorWithTheirs("Mode$ SpellCast | Phase$ Upkeep | ValidPlayer$ You"
                + " | TriggerZones$ Battlefield | Execute$ TrigDraw", draw), base, "another mode of theirs");
        assertEquals(plainFloorWith(phaseTrigger("Upkeep", "You"), draw), base + 3 * 4, "the control: ours");
    }

    // The plain dig's floor draws no random number (section 7: nothing the driver reads draws one) with the draw
    // triggers it counts in play, which is the path its readers of a trigger take: each player's upkeep draw of 3 (ours
    // and the opponent's, eight firings to the fourth of our draw steps) and our own end step's (four), in our Main 1,
    // 24 and 12 on the floor of 4. Moved from the floor of the dig toward Thassa's Oracle, retired 2026-11-04 (the
    // Oracle window).
    @Test
    public void thePlainDigsFloorDrawsNoRandomNumber() {
        start();
        final String draw = "DB$ Draw | Defined$ You | NumCards$ 3";
        final Card upkeep = addCard(MEMNITE, ai);
        upkeep.setSVar("TrigDraw", draw);
        upkeep.addTrigger(TriggerHandler.parseTrigger(phaseTrigger("Upkeep", "Player"), upkeep, true));
        final Card end = addCard(MEMNITE, ai);
        end.setSVar("TrigDraw", draw);
        end.addTrigger(TriggerHandler.parseTrigger(phaseTrigger("End of Turn", "You"), end, true));
        final long before = MyRandom.callCount();
        final int floor = plainFloorOf();
        assertEquals(MyRandom.callCount() - before, 0, "the plain dig's floor drew");
        assertEquals(floor, LoopSinks.LIBRARY_FLOOR + 3 * 8 + 3 * 4, "each player's upkeep and our end step");
    }

    // No draw trigger of ours in our graveyard or the command zone raises the plain dig's floor, which reads our
    // battlefield, and our permanent cards in our hand and library as if they were on it: a mandatory upkeep draw of 3
    // on a card of ours in our graveyard, or in the command zone, where the trigger works from there, draws nothing
    // here; the control, the same draw on our battlefield. Moved from the floor of the dig toward Thassa's Oracle,
    // retired 2026-11-04 (the Oracle window), which read our battlefield alone.
    @Test
    public void aDrawTriggerInOurGraveyardOrCommandZoneRaisesNoPlainFloor() {
        start();
        final int base = LoopSinks.LIBRARY_FLOOR;
        final String draw = "DB$ Draw | Defined$ You | NumCards$ 3";
        for (final ZoneType zone : List.of(ZoneType.Graveyard, ZoneType.Command)) {
            final Card host = addCardToZone(MEMNITE, ai, zone);
            host.setSVar("TrigDraw", draw);
            host.addTrigger(TriggerHandler.parseTrigger(
                    phaseTrigger("Upkeep", "You").replace("Battlefield", zone.name()), host, true));
            assertEquals(plainFloorOf(), base, "a draw that works from our " + zone + " is not counted");
        }
        assertEquals(plainFloorWith(phaseTrigger("Upkeep", "You"), draw), base + 3 * 4, "the control");
    }

    // ---- the final-fix compliance review's round 1 ----

    // Its finding 2 (spec section 6.4, amended): the plain dig's floor (LoopSinks.plainFloor) keeps our next four draw
    // steps, R23's four turns of draws, and every card our own mandatory draw triggers will draw us before the last of
    // them. In our Main 1: four with no draw trigger of ours; Phyrexian Arena's one at each of our next four upkeeps,
    // four more; Jin-Gitaxias's seven at this turn's end step and at those of our next three turns, 28 more; Rhystic
    // Study's optional draw nothing; and beside Nezahal, Primal Tide, whose draws cannot be counted, no plain dig.
    @Test
    public void thePlainDigsFloorKeepsFourTurnsOfOurDraws() {
        start();
        final int base = LoopSinks.LIBRARY_FLOOR;
        assertEquals(plainFloorOf(), base, "no draw trigger of ours");
        addCard("Phyrexian Arena", ai);
        assertEquals(plainFloorOf(), base + 4, "Phyrexian Arena's one at each of our next four upkeeps");
        ComboLoopBoards.ready(addCard(JIN, ai));
        assertEquals(plainFloorOf(), base + 4 + 28, "and Jin's seven at four end steps of ours");
        addCard(RHYSTIC, ai);
        assertEquals(plainFloorOf(), base + 32, "an optional draw adds nothing");
        ComboLoopBoards.ready(addCard(NEZAHAL, ai));
        assertEquals(plainFloorOf(), Integer.MAX_VALUE, "Nezahal's draws cannot be counted");
    }

    // The plain dig's floor counts each draw once for each firing before the fourth of our draw steps from now: those
    // before our next draw step, and once more for each of the trigger's phases in each of the three rounds of turns
    // after that step, where the trigger's players take that turn's player. A draw of three, in our Main 1 and then in
    // the opponent's.
    @Test
    public void thePlainDigsFloorCountsEachFiringBeforeOurFourthDrawStep() {
        start();
        final int base = LoopSinks.LIBRARY_FLOOR;
        final String draw = "DB$ Draw | Defined$ You | NumCards$ 3";
        assertEquals(plainFloorWith(phaseTrigger("Upkeep", "You"), draw), base + 3 * (1 + 3), "our next four upkeeps");
        assertEquals(plainFloorWith(phaseTrigger("Upkeep", "Player"), draw), base + 3 * (2 + 3 * 2),
                "each player's upkeep: ours and the opponent's");
        assertEquals(plainFloorWith(phaseTrigger("Upkeep", "Opponent"), draw), base + 3 * (1 + 3), "the opponent's");
        assertEquals(plainFloorWith(phaseTrigger("Draw", "You"), draw), base + 3 * 3,
                "our draw step: after each of our next three draws");
        assertEquals(plainFloorWith(phaseTrigger("Main1", "You"), draw), base + 3 * 3,
                "our Main 1: this one is not still to come");
        assertEquals(plainFloorWith(phaseTrigger("End of Turn", "You"), draw), base + 3 * (1 + 3), "our end steps");
        assertEquals(plainFloorWith(phaseTrigger("Upkeep,End of Turn", "You"), draw), base + 3 * (2 + 3 * 2),
                "two phases of ours");
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, opp, 4);
        assertEquals(plainFloorWith(phaseTrigger("End of Turn", "You"), draw), base + 3 * 3,
                "in the opponent's turn our end step comes after our next draw step");
        assertEquals(plainFloorWith(phaseTrigger("Upkeep", "You"), draw), base + 3 * (1 + 3),
                "in the opponent's turn: our next upkeep");
    }

    // A player who has lost has no more turns in the rounds the plain dig's floor counts either: in a three-player
    // game an upkeep draw of each opponent's counts for each opponent still in the game.
    @Test
    public void thePlainDigsFloorCountsNoTurnOfAPlayerWhoHasLost() {
        pod();
        final Player third = game.getPlayers().get(2);
        final String draw = "DB$ Draw | Defined$ You | NumCards$ 3";
        final int base = LoopSinks.LIBRARY_FLOOR;
        assertEquals(plainFloorWith(phaseTrigger("Upkeep", "Opponent"), draw), base + 3 * (2 + 3 * 2),
                "two opponents");
        third.concede();
        assertTrue(third.hasLost(), "the third player conceded");
        assertEquals(plainFloorWith(phaseTrigger("Upkeep", "Opponent"), draw), base + 3 * (1 + 3), "one is left");
    }

    // The plain dig's floor counts the draw triggers of our permanent cards in our hand and in our library as if each
    // were on our battlefield now, since the go-off's pool may cast one, or the dig draw it: Jin-Gitaxias in our hand
    // keeps 32, and in our library 32. Not counted:
    // Jin in exile or our graveyard; the opponent's Jins in their hand and library; a draw of ours that works from
    // another zone than the battlefield, on a card of ours in our hand; one on a card that is no permanent (a sorcery
    // of ours), or a suppressed one; and the draws that cannot be counted, which off our battlefield stop no plain dig:
    // a count not written plainly, a draw of another mode, a phase trigger of no phase, and Nezahal, Primal Tide's in
    // our hand and library. A draw that names no zone works on the battlefield and is counted, and one that names no
    // number of cards draws one.
    @Test
    public void thePlainDigsFloorCountsOurPermanentCardsInHandAndLibrary() {
        start();
        final int base = LoopSinks.LIBRARY_FLOOR;
        final Card inHand = addCardToZone(JIN, ai, ZoneType.Hand);
        assertEquals(plainFloorOf(), base + 28, "Jin in our hand");
        game.getAction().exile(inHand, null, null);
        assertEquals(plainFloorOf(), base, "Jin in exile");
        final Card inLibrary = addCardToZone(JIN, ai, ZoneType.Library);
        assertEquals(plainFloorOf(), base + 28, "Jin in our library");
        game.getAction().moveToGraveyard(inLibrary, null);
        assertEquals(plainFloorOf(), base, "Jin in our graveyard");
        addCardToZone(JIN, opp, ZoneType.Hand);
        addCardToZone(JIN, opp, ZoneType.Library);
        assertEquals(plainFloorOf(), base, "the opponent's Jins");
        final String draw = "DB$ Draw | Defined$ You | NumCards$ 3";
        final String up = phaseTrigger("Upkeep", "You");
        assertEquals(plainFloorInHand(MEMNITE, up, draw, false), base + 3 * 4, "the control: a permanent of ours");
        assertEquals(plainFloorInHand(MEMNITE, up.replace(" | TriggerZones$ Battlefield", ""), draw, false),
                base + 3 * 4, "a draw that names no zone");
        assertEquals(plainFloorInHand(MEMNITE, up.replace("Battlefield", "Graveyard"), draw, false), base,
                "a draw that works from our graveyard");
        assertEquals(plainFloorInHand(DIVINATION, up, draw, false), base, "a sorcery's");
        assertEquals(plainFloorInHand(MEMNITE, up, draw, true), base, "a suppressed one");
        assertEquals(plainFloorInHand(MEMNITE, up, "DB$ Draw | Defined$ You", false), base + 4,
                "no NumCards: one card at each of our next four upkeeps");
        assertEquals(plainFloorInHand(MEMNITE, up, "DB$ Draw | Defined$ You | NumCards$ X", false), base,
                "a count not written plainly");
        assertEquals(plainFloorInHand(MEMNITE, "Mode$ SpellCast | Phase$ Upkeep | ValidPlayer$ You"
                + " | TriggerZones$ Battlefield | Execute$ TrigDraw", draw, false), base, "a draw of another mode");
        assertEquals(plainFloorInHand(MEMNITE, "Mode$ Phase | ValidPlayer$ You | TriggerZones$ Battlefield"
                + " | Execute$ TrigDraw", draw, false), base, "a phase trigger of no phase");
        addCardToZone(NEZAHAL, ai, ZoneType.Hand);
        addCardToZone(NEZAHAL, ai, ZoneType.Library);
        assertEquals(plainFloorOf(), base, "Nezahal's draws, not counted off our battlefield");
    }

    // The plain dig stops at that floor: Jin-Gitaxias in our hand, 32 Memnites, no dig; one more Memnite, the dig. (Its
    // second half, the dig toward Oracle above the floor of four beside Jin in our hand, retired with that dig on
    // 2026-11-04 (the Oracle window).)
    @Test
    public void jinInOurHandStopsThePlainDigAtItsFloor() {
        start();
        addCardToZone(JIN, ai, ZoneType.Hand);
        library(MEMNITE, 32);
        assertNull(thrasiosOf(line(KINNAN_BASALT)), "32 cards are not above Jin's 32");
        library(MEMNITE, 1);
        assertSame(thrasiosOf(line(KINNAN_BASALT)), digOf(thrasios), "33 are");
    }

    // ---- Faerie Mastermind's bound (Evan, 2026-11-04) ----

    // Spec section 6.4, amended 2026-11-04 (Faerie Mastermind's bound): a mandatory draw of ours on a trigger that
    // fires on a player's Nth card each turn (Mode$ Drawn with a plain Number$) is counted at its bound. The engine
    // fires it only when a player's count of the cards drawn this turn reaches N (TriggerDrawn), so at most once for
    // each player it can match in each turn; each turn of the plain dig's floor's horizon counts once, whole, by its
    // player. Faerie Mastermind draws us one on an opponent's second card. In our Main 1 of a 1v1, nobody having drawn
    // this turn, the horizon is this turn, the opponent's and our next through its draw step, and the three rounds of
    // two turns after to the fourth of our draw steps: nine firings, 4 + 9 = 13. In the opponent's Main 1 it is their
    // turn, our next and the three rounds: eight turns, 4 + 8 = 12. (The floor of the dig toward Thassa's Oracle, its
    // horizon our next draw step, 7 and 6 here, retired on 2026-11-04 (the Oracle window).)
    @Test
    public void mastermindsDrawIsCountedAtItsBound() {
        start();
        addCard(MASTERMIND, ai);
        final int base = LoopSinks.LIBRARY_FLOOR;
        assertEquals(plainFloorOf(), base + 9, "our Main 1: nine turns to our fourth draw step");
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, opp, 4);
        assertEquals(plainFloorOf(), base + 8, "the opponent's Main 1: eight turns to our fourth draw step");
    }

    // The bound in a four-player pod: in our Main 1, nobody having drawn this turn, each of the three opponents can
    // fire Mastermind once in each turn. The plain dig's floor's horizon is this turn, the three opponents' and our
    // next, and three rounds of four turns: seventeen turns, 3 * 17 = 51 firings, 4 + 51 = 55. So beside Mastermind in
    // a pod the plain dig keeps 55 cards (spec section 9). A player who has lost has no more turns and draws no more
    // cards: once one opponent concedes, two are left, in thirteen turns, 4 + 2 * 13 = 30.
    @Test
    public void mastermindsBoundInAFourPlayerPod() {
        fourPlayerPod();
        assertEquals(game.getPlayers().size(), 4, "four players");
        addCard(MASTERMIND, ai);
        final int base = LoopSinks.LIBRARY_FLOOR;
        assertEquals(plainFloorOf(), base + 3 * 17, "three opponents in each of seventeen turns");
        final Player third = game.getPlayers().get(3);
        third.concede();
        assertTrue(third.hasLost(), "the fourth player conceded");
        assertEquals(plainFloorOf(), base + 2 * 13, "two opponents in each of thirteen turns");
    }

    // King T'Challa draws us one whenever a player draws their second card each turn (ValidPlayer$ Player): ours too,
    // which Thrasios's dig can itself make. In our Main 1 of a 1v1, nobody having drawn this turn, both players can
    // fire it once in each turn: two in each of the plain dig's nine, 4 + 18 = 22. Once we have drawn two cards this
    // turn we cannot fire it again this turn: 4 + 17 = 21.
    @Test
    public void kingTChallaCountsOurOwnSecondCardToo() {
        start();
        addCard(TCHALLA, ai);
        final int base = LoopSinks.LIBRARY_FLOOR;
        assertEquals(plainFloorOf(), base + 2 * 9, "both players in each of nine turns");
        library(MEMNITE, 2);
        ai.drawCards(2);
        assertEquals(ai.getNumDrawnThisTurn(), 2, "we drew our second card this turn");
        assertEquals(plainFloorOf(), base + 17, "not us in the rest of this turn");
    }

    // The Council of Four draws us one whenever a player draws their second card during their own turn (ValidPlayer$
    // Player.Active). A property that narrows ValidPlayer may be read tighter, as the turn's player, and Active is: it
    // fires at most once a turn, for that turn's player. In our Main 1 of a 1v1: once in each of the plain dig's nine
    // turns, 4 + 9 = 13. In a four-player pod: seventeen, 4 + 17 = 21, where every player in every turn would be 4 +
    // 68.
    @Test
    public void theCouncilOfFourCountsOneFiringATurn() {
        start();
        addCard(COUNCIL, ai);
        final int base = LoopSinks.LIBRARY_FLOOR;
        assertEquals(plainFloorOf(), base + 9, "a 1v1: once in each of nine turns");
        fourPlayerPod();
        addCard(COUNCIL, ai);
        assertEquals(plainFloorOf(), base + 17, "a pod: once in each of seventeen turns");
    }

    // The players a bounded draw can match are read from its ValidPlayer's words, each part's word alone and of its
    // properties Active: a draw of one on our own second card (ValidPlayer$ You) counts us alone, once in each turn,
    // 4 + 9 = 13, in our Main 1 of a 1v1, nobody having drawn this turn; one that names no player counts any player's
    // second card, as King T'Challa's does, 4 + 18 = 22; one on an opponent's second card during their own turn
    // (Opponent.Active) counts the opponent's turns alone, not this one: four, 4 + 4 = 8; and a property other than
    // Active is not read, which keeps the count an upper bound: Player.Opponent counts us too, as Player does, 4 + 18.
    @Test
    public void theBoundReadsTheWordsOfValidPlayer() {
        start();
        final int base = LoopSinks.LIBRARY_FLOOR;
        final String draw = "DB$ Draw | Defined$ You | NumCards$ 1";
        final String you = "Mode$ Drawn | ValidPlayer$ You | Number$ 2 | TriggerZones$ Battlefield | Execute$ TrigDraw";
        assertEquals(plainFloorWith(you, draw), base + 9, "You: us in each of nine turns");
        final String any = "Mode$ Drawn | Number$ 2 | TriggerZones$ Battlefield | Execute$ TrigDraw";
        assertEquals(plainFloorWith(any, draw), base + 18, "no player named: both in each of nine turns");
        final String theirs = you.replace("You", "Opponent.Active");
        assertEquals(plainFloorWith(theirs, draw), base + 4, "Opponent.Active: the opponent's four turns");
        assertEquals(plainFloorWith(you.replace("You", "Player.Opponent"), draw), base + 18,
                "Player.Opponent: its property not read, both in each of nine turns");
    }

    // The Unagi of Kyoshi Island draws us two whenever an opponent draws their second card each turn: Mastermind's
    // firings, two cards each. In our Main 1 of a 1v1, 4 + 2 * 9 = 22.
    @Test
    public void theUnagiCountsTwoCardsAFiring() {
        start();
        addCard(UNAGI, ai);
        assertEquals(plainFloorOf(), LoopSinks.LIBRARY_FLOOR + 2 * 9, "nine firings of two");
    }

    // In the current turn a player who has already drawn N cards or more cannot fire the trigger again, and is not
    // counted in it. Mastermind in our Main 1 of a 1v1, the opponent drawing one card at a time: with one drawn their
    // second is still to come, 4 + 9 = 13; with two, this turn's firing is spent, 4 + 8 = 12, while a draw on an
    // opponent's third card (Number$ 3) can still fire this turn, nine more, 4 + 8 + 9 = 21; with three, the same 12.
    // The Council of Four alike, this turn's player us: once we have drawn two, 4 + 8 = 12.
    @Test
    public void aPlayerWhoHasDrawnTheirNthCardIsNotCountedThisTurn() {
        start();
        fillLibrary(opp, 3);
        opp.drawCards(1);
        final Card mastermind = addCard(MASTERMIND, ai);
        final int base = LoopSinks.LIBRARY_FLOOR;
        assertEquals(plainFloorOf(), base + 9, "one drawn: their second is still to come");
        opp.drawCards(1);
        assertEquals(opp.getNumDrawnThisTurn(), 2, "the opponent drew their second card this turn");
        assertEquals(plainFloorOf(), base + 8, "two drawn: not in the rest of this turn");
        final String third = "Mode$ Drawn | ValidPlayer$ Opponent | Number$ 3 | TriggerZones$ Battlefield"
                + " | Execute$ TrigDraw";
        assertEquals(plainFloorWith(third, "DB$ Draw | Defined$ You"), base + 8 + 9,
                "their third card is still to come");
        opp.drawCards(1);
        assertEquals(plainFloorOf(), base + 8, "three drawn: the same");
        game.getAction().moveToGraveyard(mastermind, null);
        addCard(COUNCIL, ai);
        library(MEMNITE, 2);
        ai.drawCards(2);
        assertEquals(plainFloorOf(), base + 8, "The Council of Four, this turn's player us with two drawn");
    }

    // Every other mandatory draw trigger of ours stays uncountable, as before the bound: beside Mastermind's 13, the
    // control, a Drawn trigger with no Number$ (a draw on each card an opponent draws), one whose Number$ is no plain
    // number, a bounded one whose NumCards is no plain number, and a trigger of another mode that carries a Number$;
    // Nezahal, Primal Tide's draw on each noncreature spell an opponent casts; and Trouble in Pairs, whose draw on an
    // opponent's second card each turn is bounded but whose draws on their attack and on their second spell are not.
    // No plain dig runs beside any of them. A second bounded draw beside Mastermind's adds its own nine: 4 + 18 = 22.
    @Test
    public void aDrawTriggerWithNoBoundStaysUncountable() {
        start();
        addCard(MASTERMIND, ai);
        final int base = LoopSinks.LIBRARY_FLOOR;
        assertEquals(plainFloorOf(), base + 9, "the control: Mastermind's bound");
        final String draw = "DB$ Draw | Defined$ You | NumCards$ 1";
        final String drawn = "Mode$ Drawn | ValidPlayer$ Opponent | TriggerZones$ Battlefield | Execute$ TrigDraw";
        assertEquals(plainFloorWith(drawn, draw), Integer.MAX_VALUE, "no Number$: each card they draw");
        assertEquals(plainFloorWith(drawn + " | Number$ X", draw), Integer.MAX_VALUE, "Number$ X");
        assertEquals(plainFloorWith(drawn + " | Number$ 2", "DB$ Draw | Defined$ You | NumCards$ X"),
                Integer.MAX_VALUE, "a bounded draw of NumCards$ X");
        assertEquals(plainFloorWith(drawn.replace("Drawn", "SpellCast") + " | Number$ 2", draw), Integer.MAX_VALUE,
                "another mode, a Number$ beside it");
        assertEquals(plainFloorWith(drawn + " | Number$ 2", draw), base + 9 + 9,
                "the control: a second bounded draw, its own nine");
        final Card nezahal = addCard(NEZAHAL, ai);
        assertEquals(plainFloorOf(), Integer.MAX_VALUE, "Nezahal's draws cannot be counted");
        game.getAction().moveToGraveyard(nezahal, null);
        addCard(TROUBLE, ai);
        assertEquals(plainFloorOf(), Integer.MAX_VALUE,
                "Trouble in Pairs: its draws on an attack and on a second spell");
    }

    // The plain dig's floor counts Mastermind on a card of ours in our hand or library as if it were on our
    // battlefield now, as it counts a phase trigger there (spec section 6.4). On our battlefield, the control, 13; in
    // our hand, 13; in our library, 13. The opponent's Mastermind in their hand adds nothing to ours.
    @Test
    public void mastermindInOurHandOrLibraryRaisesThePlainDigsFloorAlone() {
        start();
        final int base = LoopSinks.LIBRARY_FLOOR;
        final Card onOurs = addCard(MASTERMIND, ai);
        assertEquals(plainFloorOf(), base + 9, "the control: on our battlefield");
        game.getAction().exile(onOurs, null, null);
        final Card inHand = addCardToZone(MASTERMIND, ai, ZoneType.Hand);
        assertEquals(plainFloorOf(), base + 9, "in our hand");
        game.getAction().exile(inHand, null, null);
        addCardToZone(MASTERMIND, ai, ZoneType.Library);
        assertEquals(plainFloorOf(), base + 9, "in our library");
        addCardToZone(MASTERMIND, opp, ZoneType.Hand);
        assertEquals(plainFloorOf(), base + 9, "the opponent's Mastermind in their hand");
    }

    // The bounded readers draw no random number (spec section 7: nothing the driver reads draws one), read here inside
    // one MyRandom.callCount() bracket around the plain dig's floor, as plainFloorOf brackets it: Mastermind, King
    // T'Challa, The Council of Four and The Unagi on our battlefield in our Main 1 of a 1v1, 9 + 18 + 9 + 18 = 54 cards
    // on the floor of 4.
    @Test
    public void theBoundedReadersDrawNoRandomNumber() {
        start();
        for (final String name : List.of(MASTERMIND, TCHALLA, COUNCIL, UNAGI)) {
            addCard(name, ai);
        }
        final long before = MyRandom.callCount();
        final int plain = LoopSinks.plainFloor(ai);
        assertEquals(MyRandom.callCount() - before, 0, "the plain dig's floor drew");
        assertEquals(plain, LoopSinks.LIBRARY_FLOOR + 9 + 18 + 9 + 18, "the four triggers' draws, the plain dig's");
    }

    // The plain dig runs beside Mastermind, whose draw, uncountable, used to stop it, and stops at the bound: in our
    // Main 1 of a 1v1 a library of 13 Memnites is not above the plain floor of 13, no dig; 14 are.
    @Test
    public void thePlainDigRunsBesideMastermindAboveItsBound() {
        start();
        addCard(MASTERMIND, ai);
        library(MEMNITE, 13);
        assertEquals(plainFloorOf(), LoopSinks.LIBRARY_FLOOR + 9, "the plain dig's floor beside Mastermind");
        assertNull(thrasiosOf(line(KINNAN_BASALT)), "13 cards are not above Mastermind's 13");
        library(MEMNITE, 1);
        assertSame(thrasiosOf(line(KINNAN_BASALT)), digOf(thrasios), "14 are");
    }

    // ---- Faerie Mastermind's bound: what its review's mutation pass, round 1, found unpinned ----

    // An inverted ValidPlayer (InvertValidPlayer: CardTraitBase.matchesValidParam then matches the players its
    // ValidPlayer does not) is read as if it named none, every living player, not by its word. ValidPlayer$ You
    // inverted fires on an opponent's second card. In our Main 1 of a four-player pod, nobody having drawn this turn,
    // the three opponents can fire it in each of the plain dig's seventeen turns, 51 firings, which its word read
    // alone, us, would undercount, 17: every player is 4 + 4 * 17 = 72. In our Main 1 of a 1v1, both players in each of
    // nine turns, 4 + 18 = 22, above the opponent's 9.
    @Test
    public void anInvertedValidPlayerCountsEveryPlayer() {
        fourPlayerPod();
        final int base = LoopSinks.LIBRARY_FLOOR;
        final String draw = "DB$ Draw | Defined$ You | NumCards$ 1";
        final String inverted = "Mode$ Drawn | ValidPlayer$ You | InvertValidPlayer$ True | Number$ 2"
                + " | TriggerZones$ Battlefield | Execute$ TrigDraw";
        assertEquals(plainFloorWith(inverted, draw), base + 4 * 17,
                "inverted You in a pod: every player in each of seventeen turns, not us alone");
        start();
        assertEquals(plainFloorWith(inverted, draw), base + 18, "inverted You: both in each of nine turns");
    }

    // Nor is an inverted Active read as that turn's player alone (activeOnly): Player.Active inverted fires on the
    // second card of a player whose turn it is not, so it is read as no ValidPlayer, every player in every turn. In our
    // Main 1 of a four-player pod, nobody having drawn this turn, the three players whose turn it is not can fire it in
    // each turn, 51 firings in the plain dig's seventeen, which that turn's player alone would undercount, 17: every
    // player is 4 + 4 * 17 = 72. In our Main 1 of a 1v1, 4 + 18 = 22, above one player a turn, 9.
    @Test
    public void anInvertedActiveCountsEveryPlayer() {
        fourPlayerPod();
        final int base = LoopSinks.LIBRARY_FLOOR;
        final String draw = "DB$ Draw | Defined$ You | NumCards$ 1";
        final String inverted = "Mode$ Drawn | ValidPlayer$ Player.Active | InvertValidPlayer$ True | Number$ 2"
                + " | TriggerZones$ Battlefield | Execute$ TrigDraw";
        assertEquals(plainFloorWith(inverted, draw), base + 4 * 17,
                "inverted Active in a pod: every player in each of seventeen turns, not the turn's player alone");
        start();
        assertEquals(plainFloorWith(inverted, draw), base + 18, "inverted Active: both, nine turns");
    }

    // A bounded draw whose ValidPlayer is You counts us alone in a pod too: once in each turn, on our own second card,
    // not once for each opponent, whom a 1v1 cannot tell from us (one opponent, as many turns). In our Main 1 of a
    // four-player pod, nobody having drawn this turn: the plain dig's seventeen turns, 4 + 17 = 21, where the three
    // opponents would be 4 + 51 = 55.
    @Test
    public void aDrawOnOurOwnSecondCardCountsUsAloneInAPod() {
        fourPlayerPod();
        final int base = LoopSinks.LIBRARY_FLOOR;
        final String draw = "DB$ Draw | Defined$ You | NumCards$ 1";
        final String you = "Mode$ Drawn | ValidPlayer$ You | Number$ 2 | TriggerZones$ Battlefield | Execute$ TrigDraw";
        assertEquals(plainFloorWith(you, draw), base + 17, "You in a pod: us in each of seventeen turns");
    }

    // ---- the Oracle window (Evan, 2026-11-04: the Jin brainstorm's option B) ----

    // A countered Oracle costs no cards (section 6.4 step 3, amended 2026-11-04 (the Oracle window)): the Oracle line's
    // board, in our Main 2, and the opponent's Counterspell put above our Oracle the moment it is cast. Through the
    // whole game loop Oracle is cast with our library at ten, nothing dug before it, and countered; the pool floats on
    // for the rest of the go-off, whose walk has nothing to play and whose plain dig finds nothing worth it in ten
    // Forests, so Main 2 ends with the library whole and Oracle in our graveyard. (Before it the dig ran first, ten to
    // four, and Oracle, cast at four and countered, cost six cards.)
    @Test
    public void aCounteredOracleCostsNoCards() {
        oracleBoard(10);
        final List<Seen> seen = play(g -> !g.getPhaseHandler().is(PhaseType.MAIN2), true);
        final List<Seen> casts = only(seen, s -> s.cast(ORACLE));
        assertEquals(casts.size(), 1, "one Oracle cast: " + seen);
        assertEquals(casts.get(0).library(), 10, "cast with the library whole: " + seen);
        assertTrue(only(seen, Seen::dig).isEmpty(), "a dig: " + seen);
        assertEquals(library(), 10, "no card dug");
        assertEquals(count(ZoneType.Graveyard, ORACLE), 1, "Oracle countered");
        assertFalse(game.isGameOver());
    }

    // Oracle removed in the window still wins (section 6.6, amended 2026-11-04 (the Oracle window); the brainstorm's
    // probe p1): the Oracle line's board, in our Main 2, its Oracle cast and its trigger on the stack, the window digs
    // five. Then the opponent's Murder on Oracle, the object on our battlefield, comes above the trigger, where the
    // driver answers nothing (it threatens no loop piece: the stock's decision), and destroys it. With the trigger on
    // top again the window digs on, five more, to empty, and passes; the trigger resolves on an empty library, our
    // devotion to blue Kinnan's and Thrasios's 2, at least its 0 cards, and the game ends WinsGameSpellEffect.
    @Test
    public void oracleRemovedInTheWindowStillWins() {
        oracleBoard(10);
        toTheWindow();
        final List<LoopDriver.Answer> first = answers(5);
        assertEquals(first.size(), 5, "five answers: " + first);
        for (final LoopDriver.Answer a : first) {
            assertSame(a.action(), digOf(thrasios), "a dig: " + first);
        }
        assertEquals(library(), 5);
        final Card onOurs = onOurBattlefield(ORACLE);
        assertNotNull(onOurs, "Oracle on our battlefield");
        ComboLoopBoards.theirResponse(game, opp, MURDER, onOurs);
        assertNull(driver.respond(), "an answer above their Murder");
        game.getStack().resolveStack();
        game.getAction().checkStateEffects(true);
        assertNull(onOurBattlefield(ORACLE), "Murder destroyed Oracle");
        assertTrue(game.getStack().peek().isTrigger(), "Oracle's trigger on top again: " + game.getStack());
        final List<LoopDriver.Answer> rest = answers(MAX_ACTIONS);
        assertEquals(rest.size(), 6, "five more digs and a pass: " + rest);
        for (int i = 0; i < 5; i++) {
            assertSame(rest.get(i).action(), digOf(thrasios), "a dig: " + rest);
        }
        assertNotNull(rest.get(5), "the stock's answer, not a pass");
        assertNull(rest.get(5).action(), "a pass: " + rest);
        assertEquals(library(), 0, "dug to empty");
        resolveTheTrigger();
        assertWonBy(ORACLE, List.of());
    }

    // The stock AI plays nothing in the window (section 6.6, amended 2026-11-04 (the Oracle window); the brainstorm's
    // probe p2, where with Oracle's trigger on top the stock AI's pick was Jin-Gitaxias's cast): Thassa's Oracle and
    // Jin-Gitaxias, Core Augur in hand, four untapped Islands, 200 colourless floating and a library of ten Forests, in
    // our Main 1. The pool holds the line's 42 already, so through the whole game loop the first item of ours is
    // Oracle's cast, with the library at ten; above its trigger the driver digs ten and passes, and the stock AI never
    // casts Jin, whose {8}{U}{U} the pool and our two Islands left could pay: the game ends WinsGameSpellEffect, Jin in
    // our hand. (Before it the walk cast Jin at the go-off's first hand-off.)
    @Test
    public void theStockWalkNeverPlaysInTheWindow() {
        start();
        addCardToZone(ORACLE, ai, ZoneType.Hand);
        final Card jin = addCardToZone(JIN, ai, ZoneType.Hand);
        for (int i = 0; i < 4; i++) {
            addCard(ISLAND, ai);
        }
        floatColourless(200);
        library(FOREST, 10);
        final List<Seen> seen = play(g -> false);
        assertTrue(only(seen, s -> s.cast(JIN)).isEmpty(), "Jin cast: " + seen);
        assertFalse(seen.isEmpty(), "nothing of ours on the stack");
        assertTrue(seen.get(0).cast(ORACLE), "Oracle's cast first: " + seen);
        assertEquals(seen.get(0).library(), 10, "cast with the library whole: " + seen);
        assertWonBy(ORACLE, seen);
        assertEquals(only(seen, Seen::dig).size(), 10, "ten digs above the trigger: " + seen);
        assertTrue(ai.getCardsIn(ZoneType.Hand).contains(jin), "Jin in our hand");
    }

    // The Oracle line runs only when its whole cost fits what the caps have left (section 6.4 step 3, amended
    // 2026-11-04 (the Oracle window), R28's shape): on the Oracle line's board it takes 95 actions, 42 cycles of
    // Basalt's tap and untap for the pool of 42, Oracle's cast and ten digs. With room for 94 the line waits: the
    // go-off builds to the stock target, Oracle's {U}{U}, and passes the decision to the walk, no cast and no dig,
    // Oracle held. With room for 95 it runs: the go-off casts Oracle after its 84 cycle steps.
    @Test
    public void theOracleLineWaitsForTheCapsToFit() {
        final Card oracle = oracleBoard(10);
        driver.useRounds(new LoopRounds(94, LoopRounds.GAME_CAP));
        final List<SpellAbility> acts = slot(MAX_ACTIONS);
        assertEquals(acts.size(), 4, "built to Oracle's 2, then the walk's turn: " + acts);
        assertTrue(only(acts, ComboLoopThrasiosAiTest::isOracleCast).isEmpty(), "Oracle was cast: " + acts);
        assertEquals(library(), 10, "no dig");
        assertTrue(driver.holdsOracle(castOf(oracle)), "Oracle held");
        assertTrue(ComboHold.vetoesCast(ai, castOf(oracle)));
        oracleBoard(10);
        driver.useRounds(new LoopRounds(95, LoopRounds.GAME_CAP));
        final List<SpellAbility> built = new ArrayList<>();
        assertNotNull(toTheOracleCast(built), "room for 95: the line runs; its actions: " + built);
        assertEquals(built.size(), 2 * 42, "42 cycles of Basalt's tap and untap");
    }

    // No dig toward Oracle runs in a main phase any more (section 6.4 step 3, amended 2026-11-04 (the Oracle window)):
    // on the Oracle line's board Thrasios's reading is no dig, ten Forests holding nothing worth a plain dig; and with
    // a library of ten Memnites instead, which a plain dig would dig for, the whole game loop casts Oracle first, the
    // library at ten, every Thrasios activation coming above its trigger, and the game ends WinsGameSpellEffect.
    @Test
    public void noDigTowardOracleRunsInAMainPhase() {
        oracleBoard(10);
        assertNull(thrasiosOf(line(KINNAN_BASALT)), "a main-phase dig toward Oracle");
        start();
        moveToMain2(game, ai);
        addCardToZone(ORACLE, ai, ZoneType.Hand);
        addCard(ISLAND, ai);
        addCard(ISLAND, ai);
        library(MEMNITE, 10);
        assertSame(thrasiosOf(line(KINNAN_BASALT)), digOf(thrasios), "ten Memnites: worth a plain dig");
        final List<Seen> seen = play(g -> false);
        final List<Seen> casts = only(seen, s -> s.cast(ORACLE));
        assertEquals(casts.size(), 1, "one Oracle cast: " + seen);
        assertEquals(casts.get(0).library(), 10, "cast with the library whole: " + seen);
        assertTrue(only(seen.subList(0, seen.indexOf(casts.get(0))), Seen::dig).isEmpty(),
                "a dig before the cast: " + seen);
        assertWonBy(ORACLE, seen);
        assertEquals(only(seen, Seen::dig).size(), 10, "ten above the trigger: " + seen);
    }

    // The window is the Oracle line's alone, and its trigger's (section 6.6, amended 2026-11-04 (the Oracle window)):
    // above the trigger of an Oracle the driver did not cast, the stack-response slot answers nothing, the stock's
    // decision, even with the pool and Thrasios able to dig: the Oracle line's board, 42 colourless floating, Oracle
    // cast by hand through the AI's own play path, resolved and its trigger on top. On the same board the line's own
    // Oracle cast and its trigger on the stack, another trigger of ours above it (Elvish Visionary's draw, the
    // Visionary put onto our battlefield) draws no answer either; that trigger resolved, the line's trigger on top
    // again, the slot answers Thrasios's activation.
    @Test
    public void theWindowIsTheOracleLinesAlone() {
        final Card oracle = oracleBoard(10);
        floatColourless(42);
        final SpellAbilityStackInstance top = castAndTrigger(castOf(oracle));
        assertTrue(top.isTrigger(), "Oracle's trigger on top: " + top);
        assertNull(driver.respond(), "a window for an Oracle the driver did not cast");
        oracleBoard(10);
        toTheWindow();
        game.getAction().moveToPlay(addCardToZone(VISIONARY, ai, ZoneType.Hand), null, null);
        game.getAction().checkStateEffects(true);
        game.getStack().addAllTriggeredAbilitiesToStack();
        assertEquals(ComboTracker.nameOf(game.getStack().peek().getSourceCard()), VISIONARY,
                "the Visionary's trigger on top: " + game.getStack());
        assertNull(driver.respond(), "an answer above another trigger of ours");
        game.getStack().resolveStack();
        game.getAction().checkStateEffects(true);
        final LoopDriver.Answer a = driver.respond();
        assertNotNull(a, "no window for the line's Oracle");
        assertSame(a.action(), digOf(thrasios));
    }

    // A window whose dig has stopped still passes (section 6.6, amended 2026-11-04 (the Oracle window)): the Oracle
    // line's board, the window digs three; the opponent's Murder on Thrasios comes above the trigger and destroys it.
    // With the trigger on top again and no Thrasios to dig with, the driver passes, so the stock AI plays nothing there
    // either; the trigger resolves on seven cards, more than our devotion to blue, and the game goes on.
    @Test
    public void aWindowWithNoDigLeftPasses() {
        oracleBoard(10);
        toTheWindow();
        assertEquals(answers(3).size(), 3);
        assertEquals(library(), 7);
        ComboLoopBoards.theirResponse(game, opp, MURDER, thrasios);
        game.getStack().resolveStack();
        game.getAction().checkStateEffects(true);
        assertNull(onOurBattlefield(THRASIOS), "Murder destroyed Thrasios");
        final List<LoopDriver.Answer> rest = answers(MAX_ACTIONS);
        assertEquals(rest.size(), 1, "one answer: " + rest);
        assertNotNull(rest.get(0), "the stock's answer, not a pass");
        assertNull(rest.get(0).action(), "a pass: " + rest);
        assertEquals(library(), 7);
        resolveTheTrigger();
        assertFalse(game.isGameOver(), "seven cards, more than our devotion to blue");
    }

    // A window dig that moves no card ends the window's dig, as a main phase's does (R21, judgeDig; section 6.6,
    // amended 2026-11-04 (the Oracle window)): the opponent's Spirit of the Labyrinth (each player can't draw more than
    // one card each turn) and the Oracle line's board with a library of ten Memnites, in our Main 2. Oracle cast and
    // its trigger on the stack, the window's first dig draws a Memnite, the turn's one draw; its second draws nothing,
    // and the window passes: two digs and a pass, the pool's 34 left, where a window that kept no library size would
    // spend the whole pool for nothing. The trigger resolves on nine cards and the game goes on.
    @Test
    public void aWindowDigThatMovesNoCardEndsTheDig() {
        start();
        moveToMain2(game, ai);
        addCardToZone(ORACLE, ai, ZoneType.Hand);
        addCard(ISLAND, ai);
        addCard(ISLAND, ai);
        library(MEMNITE, 10);
        addCard("Spirit of the Labyrinth", opp);
        toTheWindow();
        final List<LoopDriver.Answer> answers = answers(MAX_ACTIONS);
        assertEquals(answers.size(), 3, "two digs and a pass: " + answers);
        assertNull(answers.get(2).action(), "a pass: " + answers);
        assertEquals(library(), 9, "one card drawn");
        assertEquals(pool(), 4 * 10 + 2 - 2 * 4, "two digs paid");
        resolveTheTrigger();
        assertFalse(game.isGameOver());
    }

    // The window passes once a cap stops the driver (section 6.5's caps; section 6.6, amended 2026-11-04 (the Oracle
    // window)), so the stock AI plays nothing above the trigger then either: the Oracle line played to its window, then
    // room for two actions in the turn. Two digs; the third is refused, with the cap's one line in the game log, and
    // the answer is a pass, and so is the next.
    @Test
    public void theWindowPassesWhenACapStopsIt() {
        oracleBoard(10);
        toTheWindow();
        driver.useRounds(new LoopRounds(2, LoopRounds.GAME_CAP));
        final List<LoopDriver.Answer> answers = answers(MAX_ACTIONS);
        assertEquals(answers.size(), 3, "two digs and a pass: " + answers);
        assertNotNull(answers.get(2), "the stock's answer, not a pass");
        assertNull(answers.get(2).action(), "a pass: " + answers);
        assertEquals(logged("Loop driver: stopped for the turn at 2 actions."), 1);
        final LoopDriver.Answer again = driver.respond();
        assertNotNull(again, "the stock's answer once the cap has stopped the driver");
        assertNull(again.action(), "a pass");
        assertEquals(library(), 8);
    }

    // A tax on Thrasios is built for (section 6.4 step 3, amended 2026-11-04 (the Oracle window): Thrasios's price as
    // the engine prices it now): under the opponent's Suppression Field (abilities that are no mana abilities cost {2}
    // more) Thrasios's {4} costs 6 and Basalt's untap 5, with Nyxbloom Ancient beside Kinnan and Basalt (a cycle makes
    // 10 and its untap costs 5); Thassa's Oracle in hand, two Islands and a library of five Forests, in our Main 2. The
    // line builds Thrasios's 6 for each of the five cards and Oracle's 2, 32 (35 at the seventh boundary), casts Oracle
    // and digs five above its trigger, each at 6, and the game ends WinsGameSpellEffect, where a window priced at the
    // printed {4} would stop a card short.
    @Test
    public void aTaxOnThrasiosIsBuiltForTheWholeWindow() {
        start();
        ComboLoopBoards.ready(addCard(NYXBLOOM, ai));
        moveToMain2(game, ai);
        addCardToZone(ORACLE, ai, ZoneType.Hand);
        addCard(ISLAND, ai);
        addCard(ISLAND, ai);
        library(FOREST, 5);
        addCard("Suppression Field", opp);
        assertEquals(ComboExecute.priced(ai, digOf(thrasios)).getCMC(), 6, "Thrasios's 4 and the tax");
        final List<SpellAbility> built = new ArrayList<>();
        final SpellAbility cast = toTheOracleCast(built);
        assertNotNull(cast, "the driver never cast Oracle; its actions: " + built);
        assertTrue(pool() >= 6 * 5 + 2, "the pool at the cast: " + pool());
        castAndTrigger(cast);
        final List<LoopDriver.Answer> answers = answers(MAX_ACTIONS);
        assertEquals(answers.size(), 6, "five digs and a pass: " + answers);
        assertNull(answers.get(5).action(), "a pass: " + answers);
        assertEquals(library(), 0, "dug to empty");
        resolveTheTrigger();
        assertWonBy(ORACLE, List.of());
    }

    // The Oracle line waits while Thrasios's dig is over for the phase (section 6.4 step 3, amended 2026-11-04 (the
    // Oracle window): its window could not dig): the opponent's Fate Unraveler deals 1 to us for each card we draw,
    // which no price reads; two Islands and a library of ten Memnites, in our Main 1. The plain dig draws a Memnite,
    // its 1 damage is measured, and Thrasios digs no more this phase (R30). Thassa's Oracle then in our hand, the
    // go-off never casts it this phase; in our Main 2, the Unraveler gone, the line runs and casts it.
    @Test
    public void aDigOverForThePhaseHoldsTheOracleLine() {
        start();
        addCard(ISLAND, ai);
        addCard(ISLAND, ai);
        final Card unraveler = addCard(UNRAVELER, opp);
        library(MEMNITE, 10);
        final List<Act> acts = driveWithTriggers(MAX_ACTIONS);
        assertEquals(only(acts, Act::dig).size(), 1, "one dig, its draw costing 1 life: " + acts);
        final Card oracle = addCardToZone(ORACLE, ai, ZoneType.Hand);
        assertTrue(LoopSinks.digsForOracle(ai, line(KINNAN_BASALT)), "the line could run but for the dig");
        final List<SpellAbility> built = new ArrayList<>();
        assertNull(toTheOracleCast(built), "the line ran with Thrasios's dig over for the phase: " + built);
        game.getAction().moveToGraveyard(unraveler, null);
        moveToMain2(game, ai);
        ai.getManaPool().clearPool(false);
        built.clear();
        final SpellAbility cast = toTheOracleCast(built);
        assertNotNull(cast, "Main 2: the line runs; its actions: " + built);
        assertSame(cast.getHostCard(), oracle);
    }

    // ---- the Oracle window: its review's round 1 ----

    // Beside a mandatory draw trigger of ours whose draws cannot be counted the Oracle line waits, Oracle held (section
    // 6.4 step 3, amended 2026-11-04 (the Oracle window, its review's round 1)): Nezahal, Primal Tide draws us a card
    // whenever an opponent casts a noncreature spell, so with our library dug to empty above Oracle's trigger any such
    // spell in response, a removal, a counterspell or a cantrip, would draw us from it before the trigger resolved. The
    // Oracle line's board with Nezahal on ours: the line could run but for it, and Oracle is held; through the whole
    // game loop of our Main 2 Oracle is never cast and nothing is dug (the plain dig's floor is Integer.MAX_VALUE
    // beside Nezahal too), Oracle staying in our hand. Beside Faerie Mastermind, whose draw on an opponent's second
    // card is counted at its bound, the line runs, as it does with Nezahal in our hand, whose draw no floor reads.
    @Test
    public void besideNezahalTheOracleLineWaits() {
        final Card oracle = oracleBoard(10);
        ComboLoopBoards.ready(addCard(NEZAHAL, ai));
        assertTrue(LoopSinks.digsForOracle(ai, line(KINNAN_BASALT)), "the line could run but for Nezahal");
        assertEquals(plainFloorOf(), Integer.MAX_VALUE, "Nezahal's draws cannot be counted");
        assertTrue(driver.holdsOracle(castOf(oracle)), "Oracle held");
        final List<Seen> seen = play(g -> !g.getPhaseHandler().is(PhaseType.MAIN2));
        assertTrue(only(seen, s -> s.cast(ORACLE)).isEmpty(), "Oracle cast beside Nezahal: " + seen);
        assertTrue(only(seen, Seen::dig).isEmpty(), "a dig beside Nezahal: " + seen);
        assertEquals(library(), 10);
        assertTrue(ai.getCardsIn(ZoneType.Hand).contains(oracle), "Oracle in our hand");
        assertFalse(game.isGameOver());
        oracleBoard(10);
        addCard(MASTERMIND, ai);
        final List<SpellAbility> built = new ArrayList<>();
        assertNotNull(toTheOracleCast(built), "beside Mastermind the line runs; its actions: " + built);
        oracleBoard(10);
        addCardToZone(NEZAHAL, ai, ZoneType.Hand);
        built.clear();
        assertNotNull(toTheOracleCast(built), "Nezahal in our hand: the line runs; its actions: " + built);
    }

    // Thrasios's next activation (LoopSinks.thrasios), which draws no random number (spec section 7)
    private SpellAbility thrasiosOf(final ComboLines.Line l) {
        final long before = MyRandom.callCount();
        final SpellAbility dig = LoopSinks.thrasios(ai, l);
        assertEquals(MyRandom.callCount() - before, 0, "the dig's reading drew");
        return dig;
    }

    // the go-off slot's decision (LoopDriver.mainPhaseAction), which draws no random number (the driver's decision
    // code draws none, spec section 7)
    private SpellAbility act() {
        final long before = MyRandom.callCount();
        final SpellAbility sa = driver.mainPhaseAction(List.of());
        assertEquals(MyRandom.callCount() - before, 0, "a driver decision drew");
        return sa;
    }

    // the plain dig's floor (LoopSinks.plainFloor), which draws no random number (spec section 7), whatever trigger it
    // reads, wherever its card is
    private int plainFloorOf() {
        final long before = MyRandom.callCount();
        final int floor = LoopSinks.plainFloor(ai);
        assertEquals(MyRandom.callCount() - before, 0, "the plain dig's floor drew");
        return floor;
    }

    // our plain dig's floor with that trigger, whose TrigDraw is that text, on a Memnite of ours put onto our
    // battlefield for the reading and then into our graveyard
    private int plainFloorWith(final String trigger, final String drawText) {
        final Card host = addCard(MEMNITE, ai);
        host.setSVar("TrigDraw", drawText);
        host.addTrigger(TriggerHandler.parseTrigger(trigger, host, true));
        final int floor = plainFloorOf();
        game.getAction().moveToGraveyard(host, null);
        return floor;
    }

    // our plain dig's floor with that trigger, whose TrigDraw is that text, suppressed or not, on a card of that name
    // of ours put into our hand for the reading and then exiled
    private int plainFloorInHand(final String name, final String trigger, final String drawText,
            final boolean suppressed) {
        final Card host = addCardToZone(name, ai, ZoneType.Hand);
        host.setSVar("TrigDraw", drawText);
        final Trigger parsed = TriggerHandler.parseTrigger(trigger, host, true);
        parsed.setSuppressed(suppressed);
        host.addTrigger(parsed);
        final int floor = plainFloorOf();
        game.getAction().exile(host, null, null);
        return floor;
    }

    // a phase trigger that executes TrigDraw, for that phase and the players valid
    private static String phaseTrigger(final String phase, final String valid) {
        return "Mode$ Phase | Phase$ " + phase + " | ValidPlayer$ " + valid
                + " | TriggerZones$ Battlefield | Execute$ TrigDraw";
    }

    // our plain dig's floor with each pair {a trigger, its TrigDraw's text} one more trigger of ours, each on a Memnite
    // of ours put onto our battlefield for the reading and then put into our graveyard
    private int plainFloorWithEach(final String[]... triggers) {
        final List<Card> hosts = new ArrayList<>();
        for (final String[] tr : triggers) {
            final Card host = addCard(MEMNITE, ai);
            host.setSVar("TrigDraw", tr[1]);
            host.addTrigger(TriggerHandler.parseTrigger(tr[0], host, true));
            hosts.add(host);
        }
        final int floor = plainFloorOf();
        for (final Card host : hosts) {
            game.getAction().moveToGraveyard(host, null);
        }
        return floor;
    }

    // our plain dig's floor with that trigger, whose TrigDraw is that text, on a Memnite of the opponent's put onto
    // their battlefield for the reading and then into their graveyard
    private int plainFloorWithTheirs(final String trigger, final String drawText) {
        final Card host = addCard(MEMNITE, opp);
        host.setSVar("TrigDraw", drawText);
        host.addTrigger(TriggerHandler.parseTrigger(trigger, host, true));
        final int floor = plainFloorOf();
        game.getAction().moveToGraveyard(host, null);
        return floor;
    }

    // the Oracle line's board: start's, in our Main 2, Thassa's Oracle in hand, two untapped Islands and a library of
    // that many Forests; returns Oracle
    private Card oracleBoard(final int forests) {
        start();
        moveToMain2(game, ai);
        final Card oracle = addCardToZone(ORACLE, ai, ZoneType.Hand);
        addCard(ISLAND, ai);
        addCard(ISLAND, ai);
        library(FOREST, forests);
        return oracle;
    }

    // whether sa is a cast of Thassa's Oracle
    private static boolean isOracleCast(final SpellAbility sa) {
        return sa != null && sa.isSpell() && ORACLE.equals(ComboTracker.nameOf(sa.getHostCard()));
    }

    // plays the go-off slot's decisions (LoopDriver.mainPhaseAction) through the AI's own play path, each resolved and
    // the state-based actions checked before each, until a decision returns a cast of Thassa's Oracle, which it returns
    // unplayed, the actions before it in built; null when a decision returns nothing first, or after MAX_ACTIONS
    // actions. Each decision draws no random number (act)
    private SpellAbility toTheOracleCast(final List<SpellAbility> built) {
        while (built.size() < MAX_ACTIONS) {
            game.getAction().checkStateEffects(true);
            final SpellAbility sa = act();
            if (sa == null || isOracleCast(sa)) {
                return sa;
            }
            built.add(sa);
            playAndResolve(sa);
        }
        return null;
    }

    // our cast of Thassa's Oracle played through the AI's own play path; with Oracle's spell on the stack's top the
    // stack-response slot answers nothing, so no dig is taken before Oracle resolves; then Oracle resolved and its
    // entering trigger put on the stack (the brainstorm probe's idiom); returns the stack's top
    private SpellAbilityStackInstance castAndTrigger(final SpellAbility cast) {
        ai.getController().playChosenSpellAbility(cast);
        assertTrue(game.getStack().peek().isSpell(), "Oracle's spell on the stack: " + game.getStack());
        assertNull(driver.respond(), "an answer above our own Oracle's spell");
        game.getStack().resolveStack();
        game.getStack().addAllTriggeredAbilitiesToStack();
        return game.getStack().peek();
    }

    // the Oracle line played to its window: the go-off slot's decisions to its cast of Thassa's Oracle
    // (toTheOracleCast), played and resolved, its trigger then on the stack's top (castAndTrigger); fails the test when
    // the go-off casts no Oracle
    private void toTheWindow() {
        final List<SpellAbility> built = new ArrayList<>();
        final SpellAbility cast = toTheOracleCast(built);
        assertNotNull(cast, "the driver never cast Oracle; its actions: " + built);
        castAndTrigger(cast);
    }

    // asks the stack-response slot (LoopDriver.respond) at each decision while the stack holds anything, as the
    // AiController asks it above an item, the state-based actions checked before each: each answer's action played
    // through the AI's own play path and resolved alone, above what lies beneath (Oracle's trigger), the triggers it
    // caused then put on the stack; until an answer is a pass (no action), there is none (null, the stock's), the game
    // is over, or max answers. Returns the answers in order, the last of them the pass or the null that ended it. Each
    // decision draws no random number
    private List<LoopDriver.Answer> answers(final int max) {
        final List<LoopDriver.Answer> out = new ArrayList<>();
        while (out.size() < max && !game.isGameOver() && !game.getStack().isEmpty()) {
            game.getAction().checkStateEffects(true);
            final long before = MyRandom.callCount();
            final LoopDriver.Answer a = driver.respond();
            assertEquals(MyRandom.callCount() - before, 0, "a window decision drew");
            out.add(a);
            if (a == null || a.action() == null) {
                break;
            }
            ai.getController().playChosenSpellAbility(a.action());
            game.getStack().resolveStack();
            game.getStack().addAllTriggeredAbilitiesToStack();
        }
        return out;
    }

    // the stack's top resolved (Oracle's trigger), the state-based actions checked after it
    private void resolveTheTrigger() {
        game.getStack().resolveStack();
        game.getAction().checkStateEffects(true);
    }

    // nyxbloomOracleBoard's board with our library milled to three cards, Oracle's threshold, the opponent's Eidolon of
    // the Great Revel out and our life at that
    private void oracleAtLife(final int life) {
        nyxbloomOracleBoard();
        addCard(EIDOLON, opp);
        while (library() > 3) {
            game.getAction().moveToGraveyard(ai.getCardsIn(ZoneType.Library).get(0), null);
        }
        ai.setLife(life, null);
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
