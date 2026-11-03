package forge.ai.combo;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertSame;
import static org.testng.Assert.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.testng.annotations.Test;

import forge.ai.AITest;
import forge.ai.AiController;
import forge.ai.ComputerUtil;
import forge.ai.LobbyPlayerAi;
import forge.ai.LoopTracker;
import forge.ai.PlayerControllerAi;
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
import forge.game.zone.ZoneType;
import forge.gamesimulationtests.util.LobbyPlayerForTests;
import forge.util.MyRandom;

/**
 * The loop driver (spec 2026-10-29-combo-aware-ai-loops-design.md sections 6.2 to 6.5; the plan's Task B4): the go-off
 * slot runs a rock untap, Kinnan, Bonder Prodigy's or Nyxbloom Ancient's mana from Basalt or Grim Monolith, one action
 * per decision, builds the pool to what the stock AI could spend and lets it play; it never starts without a sink,
 * outside our main phase, above a stack, under a hoser or for a futile build it already ran; a step that fails or gains
 * nothing fails its line (R21); two caps stop it; its turn stamp lifts the runaway throttle; Sakashima copies Kinnan
 * for the Grim line; a deck holding no loop line plays as stock. Unless a test says otherwise the board is Kinnan's
 * list registered (every piece of the 19 loop rows, the two sinks, lands, Endless One and Counterspell), the tracker
 * reading the shipped lines, both seats on the Default profile, the AI in its own Main 1 with no AI deadline, its
 * creatures ready, and the opponent at 20 with nothing. {@link #drive} plays the driver's own decisions through the
 * AI's play path; the stepping tests run the whole game loop.
 */
public class ComboLoopDriverAiTest extends AITest {
    private static final String KINNAN = "Kinnan, Bonder Prodigy";
    private static final String BASALT = "Basalt Monolith";
    private static final String GRIM = "Grim Monolith";
    private static final String NYXBLOOM = "Nyxbloom Ancient";
    private static final String SAKASHIMA = "Sakashima of a Thousand Faces";
    private static final String ENDLESS_ONE = "Endless One";
    private static final String COUNTERSPELL = "Counterspell";
    private static final String BEAR = "Runeclaw Bear";
    private static final String DREADMAW = "Colossal Dreadmaw";
    private static final String NULL_ROD = "Null Rod";
    private static final String SUPPRESSION_FIELD = "Suppression Field";
    private static final String HARSH_MENTOR = "Harsh Mentor";
    private static final String EIDOLON = "Eidolon of the Great Revel";
    private static final String HULLBREAKER = "Hullbreaker Horror";
    /** The rock untap of Kinnan and Basalt Monolith. */
    private static final String KINNAN_BASALT = "129-4131";
    /** The rock untap of Kinnan, Grim Monolith and Sakashima as a copy of Kinnan. */
    private static final String GRIM_LINE = "129-2585-2719";
    /** The game-loop steps a stepping test may take: 200 cycles at two steps each, and slack. */
    private static final int MAX_STEPS = 2_000;
    /** Kinnan's list beside its commander: every piece of the 19 loop rows, the sinks, lands and the boards' spells. */
    private static final List<String> KINNANS_LIST = kinnansList();

    private Game game;
    private Player ai;
    private Player opp;
    private ComboTracker t;
    private LoopDriver driver;
    /** The random draws made inside the driver's decisions while {@link #drive} ran, since {@link #start}. */
    private long decisionDraws;

    private static List<String> kinnansList() {
        final Set<String> names = new LinkedHashSet<>();
        for (final ComboLines.Line l : ComboLoopBoards.shippedLoopLines(LoopRows.ids().toArray(new String[0]))) {
            names.addAll(l.names());
        }
        names.remove(KINNAN);
        names.addAll(List.of(LoopSinks.BALLISTA, "Thrasios, Triton Hero", "Forest", "Island", ENDLESS_ONE,
                COUNTERSPELL));
        return List.copyOf(names);
    }

    /** A two-player game whose AI (player 1, in its own Main 1, no AI deadline) has Kinnan's list registered, both
     *  seats on the Default profile; nothing placed yet. */
    private void start() {
        game = initAndCreateGame();
        ComboLoopBoards.noDeadline(game);
        ai = game.getPlayers().get(1);
        opp = game.getPlayers().get(0);
        ((LobbyPlayerAi) ai.getLobbyPlayer()).setAiProfile("Default");
        ((LobbyPlayerAi) opp.getLobbyPlayer()).setAiProfile("Default");
        ComboLoopBoards.deck(ai, KINNAN, KINNANS_LIST.toArray(new String[0]));
        t = ComboLoopBoards.aiOf(ai).getComboTracker();
        driver = ComboLoopBoards.aiOf(ai).getLoopDriver();
        decisionDraws = 0;
    }

    private Card kinnan() {
        return ComboLoopBoards.ready(addCard(KINNAN, ai));
    }

    /** Endless One in our hand: an X spell the pool pays, so the stock AI's target is 200. */
    private Card endlessOne() {
        return addCardToZone(ENDLESS_ONE, ai, ZoneType.Hand);
    }

    /** Two Counterspells in our hand and two untapped Islands of ours: a target of 2 the stock AI never spends. */
    private void counterspells() {
        addCardToZone(COUNTERSPELL, ai, ZoneType.Hand);
        addCardToZone(COUNTERSPELL, ai, ZoneType.Hand);
        addCard("Island", ai);
        addCard("Island", ai);
    }

    private int pool() {
        return ComboLoopBoards.pool(ai);
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

    /** The card's own activated Untap ability. */
    private static SpellAbility untapOf(final Card c) {
        for (final SpellAbility sa : c.getNonManaAbilities()) {
            if (sa.getApi() == ApiType.Untap) {
                return sa;
            }
        }
        throw new AssertionError(c + " has no untap");
    }

    /** Kinnan's {5}{G}{U} dig. */
    private static SpellAbility digOf(final Card kinnan) {
        for (final SpellAbility sa : kinnan.getNonManaAbilities()) {
            if (sa.isActivatedAbility() && sa.getApi() == ApiType.Dig) {
                return sa;
            }
        }
        throw new AssertionError("Kinnan has no dig");
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

    /** Plays the driver's go-off actions, one decision at a time, each through the AI's own play path
     *  (PlayerControllerAi.playChosenSpellAbility: paid, then resolved at once or put on the stack) with the stack then
     *  resolved, until it returns null or {@code max} actions; returns them in order. Before each decision the
     *  state-based actions are checked, as the game loop checks them before every priority (which also makes the
     *  triggers of the cards a board placed active: Kinnan's mana). The random draws made inside the driver's
     *  decisions are added to {@link #decisionDraws}. */
    private List<SpellAbility> drive(final int max) {
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
            ai.getController().playChosenSpellAbility(sa);
            while (!game.getStack().isEmpty()) {
                game.getStack().resolveStack();
            }
        }
        return acts;
    }

    /** Steps the whole game loop until one more untap of {@code rock} has resolved; returns what the pool gained. */
    private int oneCycle(final Card rock) {
        final int from = pool();
        final SpellAbility untap = untapOf(rock);
        final int untaps = untap.getActivationsThisTurn();
        ComboLoopBoards.stepUntil(game,
                g -> untap.getActivationsThisTurn() > untaps && rock.isUntapped() && g.getStack().isEmpty(), 20);
        assertEquals(untap.getActivationsThisTurn(), untaps + 1, "one untap");
        assertTrue(rock.isUntapped(), "the untap resolved");
        return pool() - from;
    }

    /** Our card {@code name}, cast: put on the stack as our spell and resolved (its payment aside); returns it on our
     *  battlefield. */
    private Card resolvesOurs(final String name) {
        final Card card = createCard(name, ai);
        card.setGameTimestamp(game.getNextTimestamp());
        game.getStackZone().add(card);
        final SpellAbility sa = card.getFirstSpellAbility();
        sa.setActivatingPlayer(ai);
        game.getStack().add(sa);
        game.getStack().resolveStack();
        assertTrue(game.getStack().isEmpty(), name + " is still on the stack");
        final Card in = onOurBattlefield(name);
        assertNotNull(in, name + " did not enter");
        return in;
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

    /** {@code sa} played through the AI's own play path, the stack then resolved. */
    private void playOut(final SpellAbility sa) {
        ai.getController().playChosenSpellAbility(sa);
        while (!game.getStack().isEmpty()) {
            game.getStack().resolveStack();
        }
    }

    /** The go-off slot's decision, the state-based actions checked first, as the game loop checks them before every
     *  priority (which makes the triggers of the cards a board placed active: Kinnan's mana). */
    private SpellAbility decide() {
        game.getAction().checkStateEffects(true);
        return driver.mainPhaseAction(List.of());
    }

    /** Palinchron and Deadeye Navigator, ready on our battlefield and paired: Deadeye's flicker granted to both.
     *  Returns Palinchron. */
    private Card paired() {
        final Card palinchron = ComboLoopBoards.ready(addCard("Palinchron", ai));
        final Card deadeye = ComboLoopBoards.ready(addCard("Deadeye Navigator", ai));
        palinchron.setPairedWith(deadeye);
        deadeye.setPairedWith(palinchron);
        game.getAction().checkStaticAbilities();
        return palinchron;
    }

    // ---- the plan's tests ----

    // The go-off's first action is the rock's mana ability: nothing floats before the driver taps.
    @Test
    public void startsWithTheRocksManaAbility() {
        start();
        kinnan();
        final Card basalt = addCard(BASALT, ai);
        endlessOne();
        final SpellAbility first = driver.mainPhaseAction(List.of());
        assertNotNull(first, "a go-off starts");
        assertSame(first, manaOf(basalt), "Basalt's own mana ability");
        assertSame(first.getActivatingPlayer(), ai);
    }

    // Through the whole game loop: the pool reaches 200 at a boundary before anything is cast, then the stock AI casts
    // Endless One from it, at the stock X search's cap of 99.
    @Test
    public void buildsThePoolTo200ThenLetsTheStockPlay() {
        start();
        kinnan();
        final Card basalt = addCard(BASALT, ai);
        final Card endless = endlessOne();
        ComboLoopBoards.stepUntil(game, g -> pool() >= LoopPool.TARGET && g.getStack().isEmpty(), MAX_STEPS);
        assertEquals(pool(), LoopPool.TARGET, "the pool at its target");
        assertTrue(basalt.isUntapped(), "the refill ended at a boundary");
        assertTrue(ai.getCardsIn(ZoneType.Hand).contains(endless), "nothing was cast before the pool reached 200");
        ComboLoopBoards.stepUntil(game, g -> onOurBattlefield(ENDLESS_ONE) != null && g.getStack().isEmpty(), 20);
        final Card cast = onOurBattlefield(ENDLESS_ONE);
        assertNotNull(cast, "the stock AI cast Endless One");
        assertEquals(cast.getCounters(CounterEnumType.P1P1), 99, "the stock X search's cap");
    }

    // Basalt's three and Kinnan's one, less the untap's three.
    @Test
    public void oneCycleNetsOneWithKinnan() {
        start();
        kinnan();
        final Card basalt = addCard(BASALT, ai);
        endlessOne();
        assertEquals(LoopCycles.net(ai, line(KINNAN_BASALT)), 1);
        assertEquals(oneCycle(basalt), 1);
    }

    // Basalt's three tripled, less the untap's three; Kinnan is not on the battlefield.
    @Test
    public void basaltNyxbloomNetsSix() {
        start();
        final Card basalt = addCard(BASALT, ai);
        ComboLoopBoards.ready(addCard(NYXBLOOM, ai));
        endlessOne();
        assertEquals(LoopCycles.net(ai, line("3430-4131")), 6);
        assertEquals(oneCycle(basalt), 6);
    }

    // Nyxbloom never triples Kinnan's extra one: nine and one, less three.
    @Test
    public void basaltNyxbloomKinnanNetsSeven() {
        start();
        kinnan();
        final Card basalt = addCard(BASALT, ai);
        ComboLoopBoards.ready(addCard(NYXBLOOM, ai));
        endlessOne();
        assertEquals(LoopCycles.net(ai, line(KINNAN_BASALT)), 7);
        assertEquals(LoopCycles.net(ai, line("3430-4131")), 7);
        assertEquals(oneCycle(basalt), 7);
    }

    // Grim's three tripled, less its untap's four.
    @Test
    public void grimNyxbloomNetsFive() {
        start();
        final Card grim = addCard(GRIM, ai);
        ComboLoopBoards.ready(addCard(NYXBLOOM, ai));
        endlessOne();
        assertEquals(LoopCycles.net(ai, line("2585-3430")), 5);
        assertEquals(oneCycle(grim), 5);
    }

    // Sakashima of a Thousand Faces, cast with Kinnan, Grim and a Runeclaw Bear on ours, enters as Kinnan (the stock
    // copy prefers our nonlegendary Bear); then each Grim cycle makes three and two Kinnans' two, less its untap's
    // four.
    @Test
    public void twoKinnansGrimNetsOne() {
        start();
        kinnan();
        final Card grim = addCard(GRIM, ai);
        ComboLoopBoards.ready(addCard(BEAR, ai));
        endlessOne();
        final Card sakashima = resolvesOurs(SAKASHIMA);
        assertEquals(sakashima.getName(), KINNAN, "Sakashima entered as a copy of Kinnan");
        assertEquals(LoopCycles.net(ai, line(GRIM_LINE)), 1);
        assertEquals(oneCycle(grim), 1);
    }

    // Kinnan and Grim alone net 0 a cycle: no go-off. A Sakashima that entered as itself is no second Kinnan either:
    // Kinnans are counted by their TapsForMana triggers, not by the row's pieces.
    @Test
    public void kinnanAndGrimAloneNeverStart() {
        start();
        kinnan();
        addCard(GRIM, ai);
        endlessOne();
        assertEquals(LoopCycles.net(ai, line(GRIM_LINE)), 0);
        assertNull(driver.mainPhaseAction(List.of()));
        ComboLoopBoards.ready(addCard(SAKASHIMA, ai));
        assertEquals(LoopCycles.net(ai, line(GRIM_LINE)), 0, "Sakashima as itself makes no Kinnan's mana");
        assertFalse(LoopCycles.ready(ai, line(GRIM_LINE)));
        assertNull(driver.mainPhaseAction(List.of()));
    }

    // Nothing in hand and no land to pay Kinnan's dig: nothing to spend the pool on, so no go-off; an X spell in hand
    // starts one.
    @Test
    public void noStartWithoutASink() {
        start();
        kinnan();
        addCard(BASALT, ai);
        assertEquals(LoopPool.stockTarget(ai, line(KINNAN_BASALT)), 0);
        assertNull(driver.mainPhaseAction(List.of()));
        endlessOne();
        assertEquals(LoopPool.stockTarget(ai, line(KINNAN_BASALT)), LoopPool.TARGET);
        assertNotNull(driver.mainPhaseAction(List.of()), "an X spell in hand is something to spend on");
    }

    // Only our own main phase on an empty stack: the board that goes off in ours does not in another step of our turn,
    // nor in the opponent's main phase, nor with an item on the stack. Each refusal is read on a driver that has
    // returned nothing yet; the control comes last.
    @Test
    public void noStartOnTheirTurnOrAboveAStack() {
        start();
        kinnan();
        final Card basalt = addCard(BASALT, ai);
        endlessOne();
        game.getPhaseHandler().devModeSet(PhaseType.COMBAT_BEGIN, ai);
        assertNull(driver.mainPhaseAction(List.of()), "our beginning of combat");
        game.getPhaseHandler().devModeSet(PhaseType.END_OF_TURN, ai);
        assertNull(driver.mainPhaseAction(List.of()), "our end step");
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, opp);
        assertNull(driver.mainPhaseAction(List.of()), "the opponent's turn");
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, ai);
        pushAbilities(game, opp, 1);
        assertFalse(game.getStack().isEmpty());
        assertNull(driver.mainPhaseAction(List.of()), "an item on the stack");
        game.getStack().clear();
        assertSame(driver.mainPhaseAction(List.of()), manaOf(basalt), "the control: our main phase, an empty stack");
    }

    // Review Focus 3: two Basalt Monoliths, the tapped one first on our battlefield. The first action is the untapped
    // one's mana ability, and the go-off taps and untaps each copy, by object.
    @Test
    public void actsOnTheUntappedCopyOfTwoMonoliths() {
        start();
        kinnan();
        final Card tapped = addCard(BASALT, ai);
        tapped.setTapped(true);
        final Card untapped = addCard(BASALT, ai);
        counterspells();
        assertSame(t.find(BASALT, ZoneType.Battlefield), tapped, "the first Basalt by name is the tapped one");
        final List<SpellAbility> acts = drive(50);
        assertFalse(acts.isEmpty());
        assertSame(acts.get(0), manaOf(untapped), "the untapped copy's mana ability first");
        for (final SpellAbility sa : acts) {
            assertTrue(sa.getHostCard() == tapped || sa.getHostCard() == untapped, "an action of a Basalt: " + sa);
        }
        for (final Card b : List.of(tapped, untapped)) {
            assertTrue(manaOf(b).getActivationsThisTurn() >= 1, "tapped for mana: " + b);
            assertTrue(untapOf(b).getActivationsThisTurn() >= 1, "untapped: " + b);
        }
        assertEquals(pool(), 2, "built to the Counterspells' 2");
    }

    // Review Focus 5: under the opponent's Null Rod the rock lines are denied, so no go-off starts, and the AI's whole
    // decision is the stock one, its answer and its random draws.
    @Test
    public void noGoOffUnderNullRod() {
        start();
        kinnan();
        addCard(BASALT, ai);
        endlessOne();
        addCard(NULL_ROD, opp);
        assertNull(driver.mainPhaseAction(List.of()));
        final AiController brains = ComboLoopBoards.aiOf(ai);
        t.useLines(List.of());
        brains.chooseSpellAbilityToPlay(); // warm: a stock pick's first call can draw what later calls do not
        ComboLoopBoards.assertStockParity(brains::chooseSpellAbilityToPlay, ai, ComboLines.shipped());
        assertFalse(driver.liftsThrottle(), "the driver never acted");
    }

    // A go-off ends at a boundary: the rock untapped, its untap resolved, on a two-cycle build and a one-cycle one.
    @Test
    public void afterAGoOffTheRockIsUntapped() {
        start();
        kinnan();
        final Card basalt = addCard(BASALT, ai);
        counterspells();
        assertEquals(drive(50).size(), 4, "two cycles build Kinnan's 2");
        assertTrue(basalt.isUntapped());
        assertEquals(pool(), 2);

        start();
        final Card basalt2 = addCard(BASALT, ai);
        ComboLoopBoards.ready(addCard(NYXBLOOM, ai));
        counterspells();
        assertEquals(drive(50).size(), 2, "one cycle of Nyxbloom's 6 covers the 2");
        assertTrue(basalt2.isUntapped());
        assertEquals(pool(), 6);
    }

    // A go-off whose only spender, the stock AI, played nothing is not started again until our hand or battlefield
    // changes: Main 1 builds to 2 for two Counterspells nobody casts; in Main 2 the same board starts nothing, and a
    // Forest arriving starts it again. With the fingerprint forgotten the same Main 2 starts at once: the gate is what
    // refuses.
    @Test
    public void aFutileGoOffIsNotRepeated() {
        start();
        kinnan();
        final Card basalt = addCard(BASALT, ai);
        counterspells();
        futileMainOne(basalt);
        moveToMain2(game, ai);
        assertNull(driver.mainPhaseAction(List.of()), "the same hand and battlefield");
        addCard("Forest", ai);
        assertSame(driver.mainPhaseAction(List.of()), manaOf(basalt), "the battlefield changed");

        start();
        kinnan();
        final Card basalt2 = addCard(BASALT, ai);
        counterspells();
        futileMainOne(basalt2);
        driver.forgetFutility();
        moveToMain2(game, ai);
        assertSame(driver.mainPhaseAction(List.of()), manaOf(basalt2), "no fingerprint, no refusal");
    }

    // Main 1 through the game loop: two cycles build the pool to 2, the rock ends untapped, the stock AI casts nothing
    private void futileMainOne(final Card basalt) {
        ComboLoopBoards.stepUntil(game, g -> !g.getPhaseHandler().is(PhaseType.MAIN1), 200);
        assertFalse(game.getPhaseHandler().is(PhaseType.MAIN1), "Main 1 ended");
        assertEquals(manaOf(basalt).getActivationsThisTurn(), 2, "two taps");
        assertEquals(untapOf(basalt).getActivationsThisTurn(), 2, "two untaps");
        assertTrue(basalt.isUntapped());
        assertEquals(ai.getCardsIn(ZoneType.Hand).size(), 2, "neither counterspell cast");
    }

    // DANGER (an opposing board lethal next turn) stops holding and fetching, never a win now: the go-off starts.
    @Test
    public void aGoOffUnderDangerStillHappens() {
        start();
        kinnan();
        final Card basalt = addCard(BASALT, ai);
        endlessOne();
        ai.setLife(3, null);
        ComboLoopBoards.ready(addCard(DREADMAW, opp));
        assertEquals(ComboSignals.abandon(ai, line(KINNAN_BASALT), t), ComboSignals.Signal.DANGER);
        assertSame(driver.mainPhaseAction(List.of()), manaOf(basalt));
    }

    // Kinnan's own dig, {5}{G}{U}, paid from the loop's colourless and a Forest and an Island, is something to spend
    // on: a go-off starts, with a target of 7. A Forest alone pays no {U}.
    @Test
    public void kinnansDigIsSomethingToSpendOn() {
        start();
        kinnan();
        final Card basalt = addCard(BASALT, ai);
        addCard("Forest", ai);
        assertEquals(LoopPool.stockTarget(ai, line(KINNAN_BASALT)), 0, "a Forest alone");
        addCard("Island", ai);
        assertEquals(LoopPool.stockTarget(ai, line(KINNAN_BASALT)), 7);
        assertSame(driver.mainPhaseAction(List.of()), manaOf(basalt));
    }

    // The opponent's Null Rod arriving after the first cycle denies the line: the driver's next pick is null, and it
    // acts no more this phase, even once the Rod is gone; the next phase starts afresh.
    @Test
    public void aNullRodArrivingMidGoOffStopsIt() {
        start();
        kinnan();
        final Card basalt = addCard(BASALT, ai);
        endlessOne();
        assertEquals(drive(2).size(), 2, "one cycle");
        assertEquals(pool(), 1);
        final Card rod = addCard(NULL_ROD, opp);
        assertNull(driver.mainPhaseAction(List.of()));
        game.getAction().moveToGraveyard(rod, null);
        assertNull(driver.mainPhaseAction(List.of()), "over for the phase");
        moveToMain2(game, ai);
        assertSame(driver.mainPhaseAction(List.of()), manaOf(basalt), "a new phase");
    }

    // The turn's cap: 10 actions, then null for the rest of the turn, one log line; the next turn it acts again.
    @Test
    public void theTurnCapStopsAndLogsOnce() {
        start();
        kinnan();
        addCard(BASALT, ai);
        endlessOne();
        driver.useRounds(new LoopRounds(10, 100));
        assertEquals(drive(50).size(), 10);
        assertNull(driver.mainPhaseAction(List.of()));
        moveToMain2(game, ai);
        assertNull(driver.mainPhaseAction(List.of()), "the rest of the turn");
        final String line = "Loop driver: stopped for the turn at 10 actions.";
        assertEquals(logged(line), 1);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, ai, 3);
        assertNotNull(driver.mainPhaseAction(List.of()), "the next turn");
        assertEquals(logged(line), 1);
    }

    // The game's budget: after 10 actions the driver never acts again this game, with one log line.
    @Test
    public void theGameBudgetStopsForTheGame() {
        start();
        kinnan();
        addCard(BASALT, ai);
        endlessOne();
        driver.useRounds(new LoopRounds(1_000, 10));
        assertEquals(drive(50).size(), 10);
        assertNull(driver.mainPhaseAction(List.of()));
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, ai, 3);
        assertNull(driver.mainPhaseAction(List.of()), "a later turn");
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, ai, 5);
        assertNull(driver.mainPhaseAction(List.of()), "and the next");
        assertEquals(logged("Loop driver: stopped for the game at 10 actions."), 1);
        assertEquals(logged("Loop driver: stopped for the turn at 1000 actions."), 0);
    }

    // An ability activated twelve times this turn: preventRunAwayActivations draws one number on a turn the driver has
    // not acted in, and on a turn it has returns false with no draw. The lift ends with the turn.
    @Test
    public void theThrottleIsLiftedOnlyOnADriverTurn() {
        start();
        final Card kinnan = kinnan();
        addCard(BASALT, ai);
        endlessOne();
        final SpellAbility dig = digOf(kinnan);
        dig.setActivatingPlayer(ai);
        for (int i = 0; i < 12; i++) {
            kinnan.addAbilityActivated(dig);
        }
        assertEquals(dig.getActivationsThisTurn(), 12);
        assertFalse(driver.liftsThrottle());
        long before = MyRandom.callCount();
        ComputerUtil.preventRunAwayActivations(dig);
        assertEquals(MyRandom.callCount() - before, 1, "a turn the driver did not act in draws");

        assertNotNull(driver.mainPhaseAction(List.of()), "the driver acts");
        assertTrue(driver.liftsThrottle());
        assertTrue(LoopDriver.liftsThrottleFor(ai));
        assertFalse(LoopDriver.liftsThrottleFor(opp), "per player");
        before = MyRandom.callCount();
        assertFalse(ComputerUtil.preventRunAwayActivations(dig), "lifted");
        assertEquals(MyRandom.callCount() - before, 0, "no draw on the driver's turn");

        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, ai, 3);
        assertFalse(driver.liftsThrottle(), "a later turn");
        before = MyRandom.callCount();
        ComputerUtil.preventRunAwayActivations(dig);
        assertEquals(MyRandom.callCount() - before, 1, "the draw is back");
    }

    // The final review's round 1 (spec section 6.8, amended): on a turn the driver acted in, the throttle is lifted
    // only for the abilities whose count a go-off inflates, Walking Ballista's {4} and ping, Thrasios's {4} and
    // Kinnan's dig. Every other ability keeps the stock roll, which is what ends the stock AI's own loops (Isochron
    // Scepter copying Dramatic Reversal). Each ability here is activated twelve times this turn: the four lifted ones
    // draw nothing, and the Scepter's copy draws its one number on the driver's turn too.
    @Test
    public void theThrottleIsLiftedOnlyForTheSinksAndKinnansDig() {
        start();
        final Card kinnan = kinnan();
        addCard(BASALT, ai);
        endlessOne();
        final Card ballista = ComboLoopBoards.ready(addCard(LoopSinks.BALLISTA, ai));
        ballista.setCounters(CounterEnumType.P1P1, 1);
        final Card thrasios = ComboLoopBoards.ready(addCard(LoopSinks.THRASIOS, ai));
        final Card scepter = addCard("Isochron Scepter", ai);
        final List<SpellAbility> lifted = new ArrayList<>();
        lifted.add(digOf(kinnan));
        for (final Card c : List.of(ballista, thrasios)) {
            for (final SpellAbility sa : c.getNonManaAbilities()) {
                if (sa.isActivatedAbility()) {
                    lifted.add(sa);
                }
            }
        }
        assertEquals(lifted.size(), 4, "Kinnan's dig, Ballista's {4} and ping, Thrasios's {4}: " + lifted);
        final SpellAbility copy = scepter.getNonManaAbilities().stream().filter(SpellAbility::isActivatedAbility)
                .findFirst().orElseThrow();
        final List<SpellAbility> all = new ArrayList<>(lifted);
        all.add(copy);
        for (final SpellAbility sa : all) {
            sa.setActivatingPlayer(ai);
            for (int i = 0; i < 12; i++) {
                sa.getHostCard().addAbilityActivated(sa);
            }
            assertEquals(sa.getActivationsThisTurn(), 12, "twelve activations of " + sa);
        }
        assertNotNull(driver.mainPhaseAction(List.of()), "the driver acts");
        assertTrue(driver.liftsThrottle(), "a turn the driver acted in");
        for (final SpellAbility sa : lifted) {
            final long before = MyRandom.callCount();
            assertFalse(ComputerUtil.preventRunAwayActivations(sa), "lifted: " + sa);
            assertEquals(MyRandom.callCount() - before, 0, "a draw for " + sa);
        }
        final long before = MyRandom.callCount();
        ComputerUtil.preventRunAwayActivations(copy);
        assertEquals(MyRandom.callCount() - before, 1, "Isochron Scepter's copy keeps the stock roll");
    }

    // Finding 1, R30: an action's price in life, as the stock AI prices a play. The opponent's Harsh Mentor makes
    // Basalt Monolith's untap cost 2 (an ability of an artifact that is no mana ability) and its tap nothing, and a
    // cast nothing; Rampaging Ferocidon makes a creature's cast cost 1, for its entering, and an artifact's nothing;
    // Eidolon of the Great Revel makes a spell of mana value 3 or less cost 2. No price draws a random number.
    @Test
    public void aStepsPriceInLifeReadsItsTriggers() {
        start();
        kinnan();
        final Card basalt = addCard(BASALT, ai);
        endlessOne();
        final SpellAbility tap = manaOf(basalt);
        final SpellAbility untap = untapOf(basalt);
        tap.setActivatingPlayer(ai);
        untap.setActivatingPlayer(ai);
        final SpellAbility bear = addCardToZone(BEAR, ai, ZoneType.Hand).getFirstSpellAbility();
        final SpellAbility sol = addCardToZone("Sol Ring", ai, ZoneType.Hand).getFirstSpellAbility();
        bear.setActivatingPlayer(ai);
        sol.setActivatingPlayer(ai);
        final long before = MyRandom.callCount();
        assertEquals(LoopDriver.lifeCost(ai, untap), 0, "no punisher");
        addCard("Harsh Mentor", opp);
        assertEquals(LoopDriver.lifeCost(ai, untap), 2, "Harsh Mentor's 2 for the untap");
        assertEquals(LoopDriver.lifeCost(ai, tap), 0, "a mana ability");
        assertEquals(LoopDriver.lifeCost(ai, bear), 0, "a cast is no activation");
        addCard("Rampaging Ferocidon", opp);
        assertEquals(LoopDriver.lifeCost(ai, bear), 1, "Ferocidon's 1 as a creature enters");
        assertEquals(LoopDriver.lifeCost(ai, sol), 0, "an artifact enters free");
        addCard("Eidolon of the Great Revel", opp);
        assertEquals(LoopDriver.lifeCost(ai, sol), 2, "Eidolon's 2 for a spell of mana value 3 or less");
        assertEquals(MyRandom.callCount() - before, 0, "a price drew");
    }

    // R7: the AI's whole decision on the rock board, with no line and with a loop line the board does not hold
    // (Palinchron and Deadeye's, held by the deck, not on the battlefield), is the stock one, answer and draws.
    @Test
    public void aDeckWithNoLoopLinePlaysAsStock() {
        start();
        kinnan();
        addCard(BASALT, ai);
        endlessOne();
        final AiController brains = ComboLoopBoards.aiOf(ai);
        t.useLines(List.of());
        brains.chooseSpellAbilityToPlay(); // warm
        final List<ComboLines.Line> unheld = ComboLoopBoards.shippedLoopLines("1409-3542");
        ComboLoopBoards.assertStockParity(brains::chooseSpellAbilityToPlay, ai, unheld);
        assertEquals(ids(t.deckLines()), List.of("1409-3542"), "the driver read a loop line the deck holds");
        assertFalse(driver.liftsThrottle(), "the driver never acted");
    }

    // R21: under the opponent's Suppression Field Basalt's untap costs 5, so a cycle nets -1 with Kinnan, the printed
    // net 1 cannot see it. One cycle runs (an Island pays the untap's fifth mana), the pool measured no higher at the
    // next boundary, and the line is failed for the phase: no more actions for it.
    @Test
    public void aTaxedUntapStopsTheLine() {
        start();
        kinnan();
        final Card basalt = addCard(BASALT, ai);
        endlessOne();
        addCard("Island", ai);
        addCard(SUPPRESSION_FIELD, opp);
        assertEquals(ComboExecute.priced(ai, untapOf(basalt)).getCMC(), 5, "the untap as the engine prices it");
        assertEquals(LoopCycles.net(ai, line(KINNAN_BASALT)), 1, "the printed net");
        final List<SpellAbility> acts = drive(50);
        assertEquals(acts.size(), 2, "one tap, one untap");
        assertSame(acts.get(1), untapOf(basalt));
        assertTrue(basalt.isUntapped());
        assertEquals(pool(), 0, "the cycle raised the pool by nothing");
        assertNull(driver.mainPhaseAction(List.of()), "the line stays failed");
    }

    // R21: the untap returned, its cost made unpayable before it is played (the pool emptied, no land), so its payment
    // fails and it is skipped: the next decision fails the line for the phase, though the untap is payable again (three
    // floating, not the four it was returned with, so the skip alone reads it), and the AI's whole decision does not
    // offer the untap again.
    @Test
    public void aSkippedActionMarksTheLineFailed() {
        start();
        kinnan();
        final Card basalt = addCard(BASALT, ai);
        endlessOne();
        assertEquals(drive(1).size(), 1, "the tap");
        final SpellAbility untap = driver.mainPhaseAction(List.of());
        assertSame(untap, untapOf(basalt));
        ai.getManaPool().clearPool(false);
        ai.getController().playChosenSpellAbility(untap);
        assertTrue(untap.isSkip(), "its payment failed");
        assertTrue(basalt.isTapped());
        assertTrue(game.getStack().isEmpty());
        for (int i = 0; i < 3; i++) {
            ai.getManaPool().addMana(new Mana((byte) ManaAtom.COLORLESS, basalt, null, ai));
        }
        assertTrue(LoopCycles.ready(ai, line(KINNAN_BASALT)), "the line could run again");
        assertNull(driver.mainPhaseAction(List.of()), "failed for the phase");
        final List<SpellAbility> offered = ComboLoopBoards.aiOf(ai).chooseSpellAbilityToPlay();
        assertTrue(offered == null || !offered.contains(untap), "offered again: " + offered);
    }

    // Once the game is over the driver returns nothing, mid-cycle (Basalt tapped, its untap paid by the pool) included.
    @Test
    public void nothingAfterTheGameEnds() {
        start();
        kinnan();
        final Card basalt = addCard(BASALT, ai);
        endlessOne();
        assertEquals(drive(1).size(), 1, "the tap");
        assertTrue(basalt.isTapped());
        game.setGameOver(GameEndReason.Draw);
        assertNull(driver.mainPhaseAction(List.of()));
        assertNull(driver.afterStockWalk());
    }

    // ---- beyond the plan's tests ----

    // Sakashima's copy is Kinnan only for the Grim line: Sakashima the effect's host, the deck holding 129-2585-2719,
    // Grim Monolith in our hand or on our battlefield, and Kinnan among the options; else null, the stock choice.
    @Test
    public void sakashimaCopiesKinnanOnlyForTheGrimLine() {
        start();
        final Card kinnan = kinnan();
        final Card bear = ComboLoopBoards.ready(addCard(BEAR, ai));
        final SpellAbility copy = addCardToZone(SAKASHIMA, ai, ZoneType.Hand).getFirstSpellAbility();
        final List<Card> options = List.of(bear, kinnan);
        assertNull(driver.copyChoice(copy, options), "no Grim Monolith in hand or on the battlefield");
        addCardToZone(GRIM, ai, ZoneType.Hand);
        assertSame(driver.copyChoice(copy, options), kinnan, "Grim in hand");
        assertNull(driver.copyChoice(copy, List.of(bear)), "no Kinnan among the options");
        assertNull(driver.copyChoice(bear.getFirstSpellAbility(), options), "an effect whose host is not Sakashima");
        t.useLines(ComboLoopBoards.shippedLoopLines(KINNAN_BASALT));
        assertNull(driver.copyChoice(copy, options), "the deck holds no Grim line");
    }

    // CloneAi asks the driver for every AI deck's clone choice: it draws nothing, inert (an effect whose host is not
    // Sakashima) or live (Sakashima, the Grim line held, Grim Monolith in hand: Kinnan).
    @Test
    public void theCopyChoiceDrawsNothing() {
        start();
        final Card kinnan = kinnan();
        final Card bear = ComboLoopBoards.ready(addCard(BEAR, ai));
        final SpellAbility copy = addCardToZone(SAKASHIMA, ai, ZoneType.Hand).getFirstSpellAbility();
        addCardToZone(GRIM, ai, ZoneType.Hand);
        final List<Card> options = List.of(bear, kinnan);
        long before = MyRandom.callCount();
        assertNull(driver.copyChoice(bear.getFirstSpellAbility(), options), "inert");
        assertEquals(MyRandom.callCount() - before, 0, "the inert choice drew");
        before = MyRandom.callCount();
        assertSame(driver.copyChoice(copy, options), kinnan, "live");
        assertEquals(MyRandom.callCount() - before, 0, "the live choice drew");
    }

    // The round record judges a judged round (a stock play) by each kind of progress, and two judged rounds in a row
    // that bought nothing stop the driver for the phase; a round that is not judged breaks the run, and a new phase
    // starts the record afresh.
    @Test
    public void theRoundRecordJudgesEachProgress() {
        start();
        final Card kinnan = kinnan();
        addCard(BASALT, ai);
        final ComboLines.Line l = line(KINNAN_BASALT);
        final LoopRounds rounds = new LoopRounds();
        rounds.beginRound(ai, true, l);
        addCard("Forest", ai);
        addCardToZone("Forest", ai, ZoneType.Hand);
        assertFalse(rounds.idleStop(ai), "one idle round: a land, on the battlefield or in hand, is nothing new");
        rounds.beginRound(ai, true, l);
        addCardToZone(DREADMAW, ai, ZoneType.Hand);
        assertTrue(rounds.idleStop(ai), "two idle rounds: {4}{G}{G} is paid by no source of ours");
        assertTrue(rounds.idleStop(ai), "and so it stays for the phase");
        moveToMain2(game, ai);
        assertFalse(rounds.idleStop(ai), "a new phase");

        rounds.beginRound(ai, true, l);
        rounds.beginRound(ai, false, l);
        rounds.beginRound(ai, true, l);
        assertFalse(rounds.idleStop(ai), "an unjudged round between two idle ones breaks the run");

        final List<Runnable> progress = List.of(
                () -> opp.setLife(19, null), // an opponent who can lose moved toward losing
                () -> kinnan.setCounters(CounterEnumType.P1P1, 1), // our attack power rose
                () -> addCard("Sol Ring", ai), // a nonland permanent arrived
                () -> addCardToZone("Mind Stone", ai, ZoneType.Hand)); // a card the loop's colourless pays
        final PhaseType[] phases = {PhaseType.MAIN1, PhaseType.MAIN2};
        for (int i = 0; i < progress.size(); i++) {
            game.getPhaseHandler().devModeSet(phases[i % 2], ai); // each in a phase of its own
            rounds.beginRound(ai, true, l);
            assertFalse(rounds.idleStop(ai), "one idle round");
            rounds.beginRound(ai, true, l);
            progress.get(i).run();
            assertFalse(rounds.idleStop(ai), "a round that bought something, kind " + i);
            rounds.beginRound(ai, true, l);
            assertFalse(rounds.idleStop(ai), "the run starts again");
            rounds.beginRound(ai, true, l);
            assertTrue(rounds.idleStop(ai), "two idle rounds after it");
        }
    }

    // The round record is per phase, and the same phase of a later turn is another one: two idle rounds in Main 1 of
    // turn 1 do not stop Main 1 of turn 3.
    @Test
    public void theIdleRecordEndsWithTheTurn() {
        start();
        kinnan();
        addCard(BASALT, ai);
        final ComboLines.Line l = line(KINNAN_BASALT);
        final LoopRounds rounds = new LoopRounds();
        rounds.beginRound(ai, true, l);
        assertFalse(rounds.idleStop(ai), "one idle round");
        rounds.beginRound(ai, true, l);
        assertTrue(rounds.idleStop(ai), "two idle rounds in Main 1 of turn 1");
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, ai, 3);
        assertFalse(rounds.idleStop(ai), "Main 1 of turn 3 starts afresh");
    }

    // R21, nothing changed: an action returned and never played leaves the board as it was, so the next decision fails
    // its line for the phase. Mid-cycle (the untap after a tap), where no cycle is measured, the comparison alone reads
    // it. The next phase reads the line afresh.
    @Test
    public void anActionThatChangesNothingFailsItsLine() {
        start();
        kinnan();
        final Card basalt = addCard(BASALT, ai);
        endlessOne();
        assertEquals(drive(1).size(), 1, "the tap");
        assertSame(driver.mainPhaseAction(List.of()), untapOf(basalt), "the untap, never played");
        assertNull(driver.mainPhaseAction(List.of()), "nothing changed: the line is failed");
        assertNull(driver.mainPhaseAction(List.of()), "for the phase");
        moveToMain2(game, ai);
        assertSame(driver.mainPhaseAction(List.of()), untapOf(basalt), "a new phase");
    }

    // R21, a cycle that loses mana: each completed cycle must raise the pool by at least 1, as measured, from a pool
    // above 0 too. The opponent's Suppression Field arrives after four good cycles (the pool at 4): the next untap
    // costs 5, so the cycle leaves 3, and the line is failed for the phase after that one cycle, not run on until the
    // pool is spent.
    @Test
    public void aTaxArrivingMidGoOffStopsTheLineThatLosesMana() {
        start();
        kinnan();
        final Card basalt = addCard(BASALT, ai);
        endlessOne();
        assertEquals(drive(8).size(), 8, "four good cycles");
        assertEquals(pool(), 4);
        addCard(SUPPRESSION_FIELD, opp);
        assertEquals(ComboExecute.priced(ai, untapOf(basalt)).getCMC(), 5, "the untap as the engine prices it");
        assertEquals(drive(50).size(), 2, "one more cycle, which lost a mana: the line is failed");
        assertEquals(pool(), 3, "the pool fell by one");
        assertTrue(basalt.isUntapped());
    }

    // A go-off's phase is its turn's too: a go-off that ended in Main 1 (the opponent's Null Rod denied its line) stays
    // over once the Rod is gone, and Main 1 of a later turn, a phase of the same name, starts afresh.
    @Test
    public void theSamePhaseOfALaterTurnStartsAfresh() {
        start();
        kinnan();
        final Card basalt = addCard(BASALT, ai);
        endlessOne();
        assertEquals(drive(2).size(), 2, "one cycle");
        final Card rod = addCard(NULL_ROD, opp);
        assertNull(driver.mainPhaseAction(List.of()), "denied: the go-off ends for the phase");
        game.getAction().moveToGraveyard(rod, null);
        assertNull(driver.mainPhaseAction(List.of()), "over for the phase");
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, ai, 3);
        assertSame(driver.mainPhaseAction(List.of()), manaOf(basalt), "Main 1 of turn 3");
    }

    // Several lines can run: the one with the highest net a cycle runs, though another comes first in the deck's
    // order. Basalt with Nyxbloom nets 6 (3430-4131), Grim with Nyxbloom 5 (2585-3430, the first of the two).
    @Test
    public void theHighestNetLineRunsFirst() {
        start();
        addCard(GRIM, ai);
        final Card basalt = addCard(BASALT, ai);
        ComboLoopBoards.ready(addCard(NYXBLOOM, ai));
        endlessOne();
        final List<String> order = ids(t.deckLines());
        assertTrue(order.indexOf("2585-3430") < order.indexOf("3430-4131"));
        assertTrue(LoopCycles.ready(ai, line("2585-3430")), "Grim's line can run too");
        assertSame(driver.mainPhaseAction(List.of()), manaOf(basalt));
    }

    // And with the lines the other way round: the line with the highest net runs though a lower one comes after it,
    // Kinnan and Basalt's (7 with Nyxbloom) before Grim and Nyxbloom's (6).
    @Test
    public void theHighestNetLineRunsEvenWhenALowerOneComesLast() {
        start();
        kinnan();
        final Card basalt = addCard(BASALT, ai);
        addCard(GRIM, ai);
        ComboLoopBoards.ready(addCard(NYXBLOOM, ai));
        endlessOne();
        t.useLines(ComboLoopBoards.shippedLoopLines(KINNAN_BASALT, "2585-3430"));
        assertEquals(ids(t.deckLines()), List.of(KINNAN_BASALT, "2585-3430"), "the higher net first");
        assertEquals(LoopCycles.net(ai, line(KINNAN_BASALT)), 7);
        assertEquals(LoopCycles.net(ai, line("2585-3430")), 6);
        assertTrue(LoopCycles.ready(ai, line("2585-3430")), "Grim's line can run too");
        assertSame(driver.mainPhaseAction(List.of()), manaOf(basalt));
    }

    // Some opponent must be able to lose (section 6.2): under the opponent's Lich's Mastery none can, so no go-off
    // starts, though the line can run and signal 2 does not deny it (an opponent unable to lose denies nothing).
    @Test
    public void noStartWhileNoOpponentCanLose() {
        start();
        kinnan();
        addCard(BASALT, ai);
        endlessOne();
        addCard("Lich's Mastery", opp);
        assertTrue(opp.cantLose());
        assertTrue(LoopCycles.ready(ai, line(KINNAN_BASALT)));
        assertFalse(ComboSignals.denied(ai, line(KINNAN_BASALT), t));
        assertNull(driver.mainPhaseAction(List.of()));
    }

    // And for a go-off that is running (spec 6.5's stop rule, read at each of its decisions): the opponent's Lich's
    // Mastery arriving after the first cycle ends it, though its line can still run and signal 2 does not deny it.
    @Test
    public void aRunningGoOffEndsWhenNoOpponentCanLoseAnyMore() {
        start();
        kinnan();
        addCard(BASALT, ai);
        endlessOne();
        assertEquals(drive(2).size(), 2, "one cycle");
        addCard("Lich's Mastery", opp);
        assertTrue(opp.cantLose());
        assertTrue(LoopCycles.ready(ai, line(KINNAN_BASALT)));
        assertFalse(ComboSignals.denied(ai, line(KINNAN_BASALT), t));
        assertNull(driver.mainPhaseAction(List.of()), "the go-off ends");
    }

    // Spec 6.5, "in a pod the driver goes on against those left": in a three-player game, with one opponent unable to
    // lose (Lich's Mastery) and the other able to, a go-off starts and runs a cycle; once the other can lose no more
    // either, it ends. Each opponent is locked first in turn, so whichever the driver reads first, one run has it
    // locked.
    @Test
    public void aPodGoesOnAgainstTheOpponentsWhoCanStillLose() {
        for (final int locked : new int[] {0, 2}) {
            game = initAndCreateThreePlayerGame();
            ComboLoopBoards.noDeadline(game);
            ai = game.getPlayers().get(1);
            opp = game.getPlayers().get(0);
            ((LobbyPlayerAi) ai.getLobbyPlayer()).setAiProfile("Default");
            ComboLoopBoards.deck(ai, KINNAN, KINNANS_LIST.toArray(new String[0]));
            t = ComboLoopBoards.aiOf(ai).getComboTracker();
            driver = ComboLoopBoards.aiOf(ai).getLoopDriver();
            kinnan();
            final Card basalt = addCard(BASALT, ai);
            endlessOne();
            final Player first = game.getPlayers().get(locked);
            final Player other = game.getPlayers().get(2 - locked);
            assertEquals(ai.getOpponents().size(), 2, "two opponents");
            addCard("Lich's Mastery", first);
            assertTrue(first.cantLose());
            assertFalse(other.cantLose());
            final List<SpellAbility> acts = drive(2);
            assertEquals(acts.size(), 2, "the other can still lose: a cycle, locked " + locked);
            assertSame(acts.get(0), manaOf(basalt));
            addCard("Lich's Mastery", other);
            assertTrue(other.cantLose());
            assertNull(driver.mainPhaseAction(List.of()), "nobody can lose any more: it ends, locked " + locked);
        }
    }

    // Already five times lethal (our attack power against the life of the opponents who can lose): no go-off starts
    // (a kill this turn is Ballista's, Task B5); with the opponent's life back up the same phase starts one.
    @Test
    public void noStartOnADecisiveBoard() {
        start();
        kinnan();
        final Card basalt = addCard(BASALT, ai);
        endlessOne();
        ComboLoopBoards.ready(addCard(DREADMAW, ai));
        ComboLoopBoards.ready(addCard(BEAR, ai));
        opp.setLife(2, null); // Kinnan's 2, Dreadmaw's 6 and the Bear's 2: ten, five times 2
        assertTrue(LoopTracker.Snapshot.of(ai).isDecisive());
        assertNull(driver.mainPhaseAction(List.of()));
        opp.setLife(20, null);
        assertSame(driver.mainPhaseAction(List.of()), manaOf(basalt), "not decisive: a go-off starts");
    }

    // And for a go-off that is running, spec 6.5's "stop when the board is 5x lethal": through the game loop the stock
    // AI casts the first of two Endless Ones from the pool of 200, and Kinnan's 2 and its 99 are 101 power against the
    // opponent's 20. The second in hand would take the pool back to its target, yet the driver refills nothing, then
    // or for the rest of the phase, though the opponent's life goes back up.
    @Test
    public void aRunningGoOffEndsWhenTheBoardIsFiveTimesLethal() {
        start();
        kinnan();
        addCard(BASALT, ai);
        endlessOne();
        endlessOne();
        ComboLoopBoards.stepUntil(game, g -> countCardsWithName(g, ENDLESS_ONE) == 1 && g.getStack().isEmpty(),
                MAX_STEPS);
        assertEquals(countCardsWithName(game, ENDLESS_ONE), 1, "the first cast");
        assertTrue(game.getPhaseHandler().is(PhaseType.MAIN1, ai), "still our Main 1");
        assertTrue(LoopTracker.Snapshot.of(ai).isDecisive(), "five times lethal");
        assertEquals(pool(), LoopPool.TARGET - 99);
        assertNull(driver.mainPhaseAction(List.of()), "the go-off ends: no refill");
        opp.setLife(500, null);
        assertFalse(LoopTracker.Snapshot.of(ai).isDecisive());
        assertNull(driver.mainPhaseAction(List.of()), "and it is over for the phase");
    }

    // A rock tapped when the go-off starts: its untap, paid by our lands (the pool is empty), is the first action, and
    // the cycles run from there; with no land to pay it the line cannot run.
    @Test
    public void aTappedRockStartsWithItsUntapPaidByLands() {
        start();
        kinnan();
        final Card basalt = addCard(BASALT, ai);
        basalt.setTapped(true);
        endlessOne();
        assertFalse(LoopCycles.ready(ai, line(KINNAN_BASALT)), "no land to pay the untap");
        assertNull(driver.mainPhaseAction(List.of()));
        for (int i = 0; i < 3; i++) {
            addCard("Island", ai);
        }
        final List<SpellAbility> acts = drive(5);
        assertEquals(acts.size(), 5);
        assertSame(acts.get(0), untapOf(basalt), "the untap first, the Islands paying it");
        assertSame(acts.get(1), manaOf(basalt));
        assertSame(acts.get(2), untapOf(basalt));
        assertEquals(pool(), 2, "two cycles of 1 after it");
    }

    // The pool's reading: the loop's colourless pays any generic or colourless mana; a coloured pip needs a source of
    // its colour, the pool's floating mana or one untapped land of ours each, or Chromatic Orrery, which lets any mana
    // pay any colour.
    @Test
    public void thePoolPaysWithTheLoopsColourlessOurLandsAndOrrery() {
        start();
        kinnan();
        addCard(BASALT, ai);
        final ComboLines.Line l = line(KINNAN_BASALT);
        final Card dreadmaw = addCardToZone(DREADMAW, ai, ZoneType.Hand);
        assertFalse(LoopPool.payable(ai, dreadmaw, l), "{4}{G}{G}: no green");
        addCard("Forest", ai);
        assertFalse(LoopPool.payable(ai, dreadmaw, l), "one green of two");
        final Card second = addCard("Forest", ai);
        second.setTapped(true);
        assertFalse(LoopPool.payable(ai, dreadmaw, l), "a tapped Forest pays nothing");
        second.setTapped(false);
        assertTrue(LoopPool.payable(ai, dreadmaw, l), "the Forests' {G}{G} and the loop's {4}");
        assertFalse(LoopPool.payable(ai, dreadmaw, null), "with no loop the Forests alone pay no {4} beside");
        assertEquals(LoopPool.stockTarget(ai, l), 6);
        final Card counter = addCardToZone(COUNTERSPELL, ai, ZoneType.Hand);
        assertFalse(LoopPool.payable(ai, counter, l), "{U}{U}: no blue");
        ai.getManaPool().addMana(new Mana((byte) ManaAtom.BLUE, counter, null, ai));
        ai.getManaPool().addMana(new Mana((byte) ManaAtom.BLUE, counter, null, ai));
        assertTrue(LoopPool.payable(ai, counter, l), "two blue floating");

        start();
        kinnan();
        addCard(BASALT, ai);
        final Card dreadmaw2 = addCardToZone(DREADMAW, ai, ZoneType.Hand);
        assertFalse(LoopPool.payable(ai, dreadmaw2, line(KINNAN_BASALT)));
        addCard("Chromatic Orrery", ai);
        assertTrue(LoopPool.payable(ai, dreadmaw2, line(KINNAN_BASALT)), "Orrery: the loop's colourless pays {G}{G}");
    }

    // A land that makes any colour pays a coloured pip: Mana Confluence pays Giant Growth's {G}, which the loop's
    // colourless cannot, so Giant Growth's 1 is the stock target.
    @Test
    public void aLandThatMakesAnyColourPaysAColouredPip() {
        start();
        kinnan();
        addCard(BASALT, ai);
        final ComboLines.Line l = line(KINNAN_BASALT);
        final Card growth = addCardToZone("Giant Growth", ai, ZoneType.Hand);
        assertFalse(LoopPool.payable(ai, growth, l), "{G}: no land");
        assertEquals(LoopPool.stockTarget(ai, l), 0);
        addCard("Mana Confluence", ai);
        assertTrue(LoopPool.payable(ai, growth, l), "Mana Confluence makes any colour");
        assertEquals(LoopPool.stockTarget(ai, l), 1);
    }

    // A land with two mana abilities (a typed dual: Breeding Pool is a Forest and an Island) makes either colour, and
    // the kinds of all its abilities count: {G} for Giant Growth and {U} for Opt, each paid by the one land.
    @Test
    public void aLandWithTwoManaAbilitiesMakesEitherColour() {
        start();
        kinnan();
        addCard(BASALT, ai);
        final ComboLines.Line l = line(KINNAN_BASALT);
        final Card growth = addCardToZone("Giant Growth", ai, ZoneType.Hand);
        final Card opt = addCardToZone("Opt", ai, ZoneType.Hand);
        assertFalse(LoopPool.payable(ai, growth, l), "{G}: no land");
        ComboLoopBoards.ready(addCard("Breeding Pool", ai));
        assertTrue(LoopPool.payable(ai, growth, l), "Breeding Pool's {G}");
        assertTrue(LoopPool.payable(ai, opt, l), "Breeding Pool's {U}");
    }

    // A land that makes one mana of a colour in our commander's identity (Command Tower, a combination mana ability)
    // pays a coloured pip of that colour: with Kinnan, green and blue, as our commander it pays Giant Growth's {G},
    // which the loop's colourless cannot, and Giant Growth's 1 is the stock target.
    @Test
    public void aCommandTowerPaysAColouredPipOfTheCommandersColours() {
        start();
        final Card kinnan = kinnan();
        ai.addCommander(kinnan);
        addCard(BASALT, ai);
        final ComboLines.Line l = line(KINNAN_BASALT);
        final Card growth = addCardToZone("Giant Growth", ai, ZoneType.Hand);
        assertFalse(LoopPool.payable(ai, growth, l), "{G}: no land");
        assertEquals(LoopPool.stockTarget(ai, l), 0, "nothing to spend on");
        ComboLoopBoards.ready(addCard("Command Tower", ai));
        assertTrue(LoopPool.payable(ai, growth, l), "Command Tower makes {G} or {U} for Kinnan");
        assertEquals(LoopPool.stockTarget(ai, l), 1);
    }

    // Hall's condition over the pips: {G}{U} wants two sources of those colours. One dual land (Hinterland Harbor, {G}
    // or {U}) and a colourless one are two sources, but only one of them pays either pip, and the loop's colourless
    // pays neither. With an Island beside the dual land the cost is paid: the Harbor's {G} and the Island's {U}.
    @Test
    public void aTwoColouredCostNeedsTwoSourcesOfThoseColours() {
        start();
        kinnan();
        addCard(BASALT, ai);
        final ComboLines.Line l = line(KINNAN_BASALT);
        final Card charm = addCardToZone("Simic Charm", ai, ZoneType.Hand);
        ComboLoopBoards.ready(addCard("Hinterland Harbor", ai));
        addCard("Wastes", ai);
        assertFalse(LoopPool.payable(ai, charm, l), "{G}{U} from a G-or-U land and a {C} one");
        addCard("Island", ai);
        assertTrue(LoopPool.payable(ai, charm, l), "the Harbor's {G} and the Island's {U}");
    }

    // The stock target is the largest payable cost, not the last or the smallest one read: Colossal Dreadmaw's 6
    // before Giant Growth's 1 in our hand, Kinnan's dig's 7 before Mind Stone's 1 on our battlefield; and a card
    // nobody can pay for is nothing to spend on.
    @Test
    public void theStockTargetIsTheLargestPayableCost() {
        start();
        kinnan();
        addCard(BASALT, ai);
        final ComboLines.Line l = line(KINNAN_BASALT);
        addCardToZone(DREADMAW, ai, ZoneType.Hand);
        assertEquals(LoopPool.stockTarget(ai, l), 0, "{4}{G}{G}: no green, nothing to spend on");
        addCard("Forest", ai);
        addCard("Forest", ai);
        addCardToZone("Giant Growth", ai, ZoneType.Hand);
        assertEquals(LoopPool.stockTarget(ai, l), 6, "Colossal Dreadmaw's 6, Giant Growth's 1 after it");
        addCard("Island", ai);
        addCard("Mind Stone", ai);
        assertEquals(LoopPool.stockTarget(ai, l), 7, "Kinnan's dig, Mind Stone's {1} after it");
    }

    // Something to spend on is a stock target above 0, so a spend of exactly 1 starts a go-off: Mind Stone's {1}, {T},
    // sacrifice: draw a card, with an empty hand and no land.
    @Test
    public void aSingleManaSpendIsSomethingToSpendOn() {
        start();
        kinnan();
        final Card basalt = addCard(BASALT, ai);
        addCard("Mind Stone", ai);
        assertEquals(LoopPool.stockTarget(ai, line(KINNAN_BASALT)), 1);
        assertSame(driver.mainPhaseAction(List.of()), manaOf(basalt), "a go-off for a spend of 1");
    }

    // The idle rule in the driver: each time it passes the decision to the stock walk at its target, the walk's play
    // begins a judged round; two in a row that buy nothing stop the driver for the phase, where one does not, and the
    // next phase starts afresh.
    @Test
    public void twoIdleStockRoundsStopTheDriverForThePhase() {
        start();
        kinnan();
        addCard(BASALT, ai);
        counterspells();
        final SpellAbility counter = ai.getCardsIn(ZoneType.Hand).get(0).getFirstSpellAbility();
        assertEquals(drive(50).size(), 4, "built to 2, then the walk's turn");
        driver.noteStockPlay(counter); // the walk's play, which buys nothing
        ai.getManaPool().clearPool(false);
        assertEquals(drive(50).size(), 4, "one idle round: the driver refills");
        driver.noteStockPlay(counter);
        ai.getManaPool().clearPool(false);
        assertNull(driver.mainPhaseAction(List.of()), "two idle rounds: stopped for the phase");
        assertNull(driver.mainPhaseAction(List.of()));
        moveToMain2(game, ai);
        assertNotNull(driver.mainPhaseAction(List.of()), "a new phase");
    }

    // A stock play the driver did not pass the priority to is no round of a go-off: the AiController reports every
    // walk's pick, and two of them on a board that has not gone off judge nothing, so the go-off that follows is not
    // idle-stopped.
    @Test
    public void aStockPlayOutsideAGoOffIsNoRound() {
        start();
        kinnan();
        addCard(BASALT, ai);
        counterspells();
        final SpellAbility counter = ai.getCardsIn(ZoneType.Hand).get(0).getFirstSpellAbility();
        driver.noteStockPlay(counter);
        driver.noteStockPlay(counter);
        assertNotNull(driver.mainPhaseAction(List.of()), "no round was begun, so none was idle");
    }

    // The driver's round record reads a card that arrives in our hand against the loop's colours: after the walk's
    // play a Wurmcoil Engine arrives, whose {6} our two Islands cannot pay but the loop's colourless can, so it is
    // something new; the driver refills to its 6, and the next judged round that buys nothing is the first idle one,
    // not the second.
    @Test
    public void aCardTheLoopPaysIsProgressForTheDriversRound() {
        start();
        kinnan();
        addCard(BASALT, ai);
        counterspells();
        final SpellAbility counter = ai.getCardsIn(ZoneType.Hand).get(0).getFirstSpellAbility();
        assertEquals(drive(50).size(), 4, "built to 2, then the walk's turn");
        driver.noteStockPlay(counter);
        addCardToZone("Wurmcoil Engine", ai, ZoneType.Hand);
        ai.getManaPool().clearPool(false);
        assertEquals(drive(50).size(), 12, "the round bought something: the driver refills, now to 6");
        driver.noteStockPlay(counter);
        ai.getManaPool().clearPool(false);
        assertNotNull(driver.mainPhaseAction(List.of()), "one idle round, not two");
    }

    // The sinks' activations are the driver's own steps, never the stock AI's spend (Tasks B5 and B6 run them):
    // Thrasios and a Walking Ballista on our battlefield set no stock target, where any other ability the pool pays
    // does (Mind Stone's {1} draw).
    @Test
    public void theSinksAreNoStockSpend() {
        start();
        kinnan();
        addCard(BASALT, ai);
        ComboLoopBoards.ready(addCard("Thrasios, Triton Hero", ai));
        ComboLoopBoards.ready(addCard(LoopSinks.BALLISTA, ai)).setCounters(CounterEnumType.P1P1, 2);
        assertEquals(LoopPool.stockTarget(ai, line(KINNAN_BASALT)), 0);
        addCard("Mind Stone", ai);
        assertEquals(LoopPool.stockTarget(ai, line(KINNAN_BASALT)), 1, "Mind Stone's {1}, {T}, sacrifice: draw");
        addCard(NULL_ROD, opp);
        assertEquals(LoopPool.stockTarget(ai, line(KINNAN_BASALT)), 0, "an artifact's ability Null Rod refuses");
    }

    // A loop line runs only with every piece of it on our battlefield (spec 6.1), whatever its net: with two Kinnans
    // (a direct reading: no state-based action has run) Grim Monolith nets 1 a cycle, but its Nyxbloom line
    // (2585-3430) has Nyxbloom in our library, and its Sakashima line (129-2585-2719) has no Sakashima.
    @Test
    public void aLineRunsOnlyWithEveryPieceOnOurBattlefield() {
        start();
        kinnan();
        kinnan();
        addCard(GRIM, ai);
        addCardToZone(NYXBLOOM, ai, ZoneType.Library);
        endlessOne();
        assertEquals(LoopCycles.net(ai, line("2585-3430")), 1);
        assertFalse(LoopCycles.ready(ai, line("2585-3430")), "Nyxbloom is not on our battlefield");
        assertTrue(t.view().ready(line("2585-3430")), "the tracker's readiness: every piece reachable");
        assertNull(driver.mainPhaseAction(List.of()));
    }

    // The net reads the effects a rock's tap fires, never names: Wild Growth's TapsForMana trigger fires for its
    // enchanted land only, so it is no Kinnan; Virtue of Strength triples a basic land's mana only, so it is no
    // Nyxbloom; Mana Reflection doubles the rock's mana (3 x 2 and Kinnan's 1, less 3), and the cycle measures what
    // the net says; a Nyxbloom Ancient that lost its abilities (the opponent's Turn to Frog) triples nothing, so Grim's
    // line nets -1 and does not run.
    @Test
    public void theNetReadsTheEffectsTheRocksTapFires() {
        start();
        kinnan();
        final Card basalt = addCard(BASALT, ai);
        endlessOne();
        addCard("Wild Growth", ai);
        assertEquals(LoopCycles.net(ai, line(KINNAN_BASALT)), 1, "Wild Growth is no Kinnan");
        addCard("Virtue of Strength", ai);
        assertEquals(LoopCycles.net(ai, line(KINNAN_BASALT)), 1, "Virtue of Strength triples no rock");
        addCard("Mana Reflection", ai);
        assertEquals(LoopCycles.net(ai, line(KINNAN_BASALT)), 4);
        assertEquals(oneCycle(basalt), 4);

        start();
        final Card grim = addCard(GRIM, ai);
        final Card nyxbloom = ComboLoopBoards.ready(addCard(NYXBLOOM, ai));
        endlessOne();
        assertEquals(LoopCycles.net(ai, line("2585-3430")), 5);
        final Card frog = createCard("Turn to Frog", opp);
        frog.setGameTimestamp(game.getNextTimestamp());
        game.getStackZone().add(frog);
        final SpellAbility sa = frog.getFirstSpellAbility();
        sa.setActivatingPlayer(opp);
        sa.getTargets().add(nyxbloom);
        game.getStack().add(sa);
        game.getStack().resolveStack();
        assertTrue(game.getStack().isEmpty());
        assertEquals(LoopCycles.net(ai, line("2585-3430")), -1, "Grim's 3, less its untap's 4");
        assertFalse(LoopCycles.ready(ai, line("2585-3430")));
        assertFalse(ComboSignals.denied(ai, line("2585-3430"), t), "no reading of signal 2");
        assertNull(driver.mainPhaseAction(List.of()));
        assertTrue(grim.isUntapped());
    }

    // R21: Kinnans are counted by their TapsForMana triggers alone: a card of ours with a trigger of another mode and
    // no ValidCard (Phyrexian Arena's upkeep draw) adds nothing to a cycle.
    @Test
    public void aTriggerThatIsNoTapsForManaIsNoKinnan() {
        start();
        kinnan();
        addCard(BASALT, ai);
        endlessOne();
        addCard("Phyrexian Arena", ai);
        assertEquals(LoopCycles.net(ai, line(KINNAN_BASALT)), 1);
    }

    // Through the whole game loop, the walk's plays reach the driver: the stock AI spends the pool on Endless One, then
    // plays nothing more, and the go-off ends with no futility fingerprint, since something was spent; so the same
    // hand and battlefield start a go-off in Main 2. The opponent at 40, so Endless One's 99 leave the board short of
    // five times lethal.
    @Test
    public void aGoOffThatSpentLeavesNoFingerprint() {
        start();
        kinnan();
        final Card basalt = addCard(BASALT, ai);
        endlessOne();
        counterspells();
        opp.setLife(40, null);
        ComboLoopBoards.stepUntil(game, g -> !g.getPhaseHandler().is(PhaseType.MAIN1), MAX_STEPS);
        assertFalse(game.getPhaseHandler().is(PhaseType.MAIN1), "Main 1 ended");
        final Card cast = onOurBattlefield(ENDLESS_ONE);
        assertNotNull(cast, "the stock AI spent the pool on Endless One");
        assertEquals(cast.getCounters(CounterEnumType.P1P1), 99);
        assertEquals(ai.getCardsIn(ZoneType.Hand).size(), 2, "and cast no Counterspell");
        assertFalse(LoopTracker.Snapshot.of(ai).isDecisive());
        moveToMain2(game, ai);
        assertSame(driver.mainPhaseAction(List.of()), manaOf(basalt), "no fingerprint: a go-off starts");
    }

    // The walk choosing nothing keeps a futile build's fingerprint only when the driver passed it the priority at its
    // target: the AiController reports every walk that chose nothing, and with no go-off running the board that can go
    // off is not refused for it.
    @Test
    public void aWalkThatChoseNothingOutsideAGoOffKeepsNoFingerprint() {
        start();
        kinnan();
        addCard(BASALT, ai);
        counterspells();
        assertNull(driver.afterStockWalk(), "the driver passed the walk no priority");
        assertNotNull(driver.mainPhaseAction(List.of()), "so no futile build was kept");
    }

    // The futility fingerprint is our hand's and our battlefield's card names, each sorted: after a futile build with
    // Counterspell and Mana Leak in hand, the same names in another order (the hand's first card gone to the library
    // and back, now last) are still refused, and another name in the place of one (Memory Lapse for Mana Leak, the
    // same count) starts the go-off again, the battlefield as it was.
    @Test
    public void theFutilityFingerprintIsTheSortedNamesOfHandAndBattlefield() {
        start();
        kinnan();
        final Card basalt = addCard(BASALT, ai);
        addCardToZone(COUNTERSPELL, ai, ZoneType.Hand);
        addCardToZone("Mana Leak", ai, ZoneType.Hand);
        addCard("Island", ai);
        addCard("Island", ai);
        futileMainOne(basalt);
        moveToMain2(game, ai);
        assertNull(driver.mainPhaseAction(List.of()), "the same hand and battlefield");

        final Card first = ai.getCardsIn(ZoneType.Hand).get(0);
        final String name = first.getName();
        game.getAction().moveToHand(game.getAction().moveToLibrary(first, null), null);
        assertEquals(ai.getCardsIn(ZoneType.Hand).get(1).getName(), name, "the first card of the hand is last now");
        assertNull(driver.mainPhaseAction(List.of()), "the same names in another order");

        Card leak = null;
        for (final Card c : ai.getCardsIn(ZoneType.Hand)) {
            if (c.getName().equals("Mana Leak")) {
                leak = c;
            }
        }
        assertNotNull(leak);
        game.getAction().moveToGraveyard(leak, null);
        addCardToZone("Memory Lapse", ai, ZoneType.Hand);
        assertEquals(ai.getCardsIn(ZoneType.Hand).size(), 2, "the same count");
        assertSame(driver.mainPhaseAction(List.of()), manaOf(basalt), "another name in its place");
    }

    // The fingerprint reads our hand as well as our battlefield: after a futile build, a third Counterspell arriving in
    // our hand, the battlefield as it was, starts the go-off again.
    @Test
    public void theFutilityFingerprintReadsTheHand() {
        start();
        kinnan();
        final Card basalt = addCard(BASALT, ai);
        counterspells();
        futileMainOne(basalt);
        moveToMain2(game, ai);
        assertNull(driver.mainPhaseAction(List.of()), "the same hand and battlefield");
        addCardToZone(COUNTERSPELL, ai, ZoneType.Hand);
        assertSame(driver.mainPhaseAction(List.of()), manaOf(basalt), "the hand changed");
    }

    // The driver's own check of each step: a rock that cannot be tapped (Basalt a summoning-sick creature under our
    // March of the Machines) starts no go-off, though the line can run and signal 2 does not deny it.
    @Test
    public void aRockThatCannotTapStartsNothing() {
        start();
        kinnan();
        final Card basalt = addCard(BASALT, ai);
        endlessOne();
        addCard("March of the Machines", ai);
        game.getAction().checkStaticAbilities();
        assertTrue(basalt.isSick(), "Basalt is a summoning-sick creature");
        assertTrue(LoopCycles.ready(ai, line(KINNAN_BASALT)));
        assertFalse(ComboSignals.denied(ai, line(KINNAN_BASALT), t));
        assertNull(driver.mainPhaseAction(List.of()));
        basalt.setSickness(false);
        assertSame(driver.mainPhaseAction(List.of()), manaOf(basalt), "no longer sick: the go-off starts");
    }

    // Signal 2 stops the driver beyond what its own check of the next step sees: the opponent's Pithing Needle naming
    // Basalt Monolith spares its tap (a mana ability), which the driver's check passes, and bars its untap, so no cycle
    // can close: the line is denied and no go-off starts.
    @Test
    public void noGoOffUnderAPithingNeedleOnTheUntap() {
        start();
        kinnan();
        final Card basalt = addCard(BASALT, ai);
        endlessOne();
        addCard("Pithing Needle", opp).setNamedCards(List.of(BASALT));
        final SpellAbility tap = manaOf(basalt);
        tap.setActivatingPlayer(ai);
        assertTrue(tap.checkRestrictions(ai), "the tap is not barred");
        assertTrue(ComboSignals.denied(ai, line(KINNAN_BASALT), t));
        assertNull(driver.mainPhaseAction(List.of()), "the cycle cannot close: no go-off");
    }

    // A check that fails in the middle of a go-off ends it for the phase (the plan's step 5): after one cycle our own
    // March of the Machines makes Basalt a summoning-sick creature, so its tap cannot be played; with the sickness gone
    // the go-off does not come back this phase. One more mana floats first, so that reading the same boundary again
    // measures a cycle that rose and fails no line by R21.
    @Test
    public void aStepThatFailsItsCheckMidGoOffEndsTheGoOff() {
        start();
        kinnan();
        final Card basalt = addCard(BASALT, ai);
        endlessOne();
        assertEquals(drive(2).size(), 2, "one cycle");
        addCard("March of the Machines", ai);
        game.getAction().checkStaticAbilities();
        assertTrue(basalt.isSick(), "Basalt is a summoning-sick creature");
        assertNull(driver.mainPhaseAction(List.of()), "the tap cannot be played");
        basalt.setSickness(false);
        ai.getManaPool().addMana(new Mana((byte) ManaAtom.COLORLESS, basalt, null, ai));
        assertNull(driver.mainPhaseAction(List.of()), "over for the phase");
    }

    // R21: a step that fails the driver's check never burns the caps: with room for three actions a turn, ten
    // decisions the check refuses (March of the Machines makes Basalt a summoning-sick creature) count none, and the
    // go-off that starts once the check passes has its three.
    @Test
    public void aFailedCheckBurnsNoCap() {
        start();
        kinnan();
        final Card basalt = addCard(BASALT, ai);
        endlessOne();
        addCard("March of the Machines", ai);
        game.getAction().checkStaticAbilities();
        assertTrue(basalt.isSick());
        driver.useRounds(new LoopRounds(3, 100));
        for (int i = 0; i < 10; i++) {
            assertNull(driver.mainPhaseAction(List.of()), "the check refuses the tap");
        }
        assertEquals(logged("Loop driver: stopped for the turn at 3 actions."), 0);
        basalt.setSickness(false);
        assertEquals(drive(50).size(), 3, "the turn's three actions are all there");
    }

    // The caps count the actions the driver returns, and passing the priority to the walk is none: with room for
    // exactly the four actions that build the pool to 2, the hand-off logs no cap; the fifth action, the refill's
    // first, is refused, with the cap's one line.
    @Test
    public void theHandOffBurnsNoCap() {
        start();
        kinnan();
        addCard(BASALT, ai);
        counterspells();
        driver.useRounds(new LoopRounds(4, 100));
        assertEquals(drive(50).size(), 4, "built to 2");
        final String line = "Loop driver: stopped for the turn at 4 actions.";
        assertEquals(logged(line), 0, "the hand-off is no action");
        driver.noteStockPlay(ai.getCardsIn(ZoneType.Hand).get(0).getFirstSpellAbility());
        ai.getManaPool().clearPool(false);
        assertNull(driver.mainPhaseAction(List.of()), "the fifth action is refused");
        assertEquals(logged(line), 1);
    }

    // The throttle is lifted for a turn in which the driver returned an action, and only then: a go-off whose first
    // decision passes the priority to the walk (the pool already at its target, at a boundary) returns none and lifts
    // nothing; once the walk has spent the pool, the refill's first action lifts it.
    @Test
    public void aHandOffAloneLiftsNoThrottle() {
        start();
        kinnan();
        final Card basalt = addCard(BASALT, ai);
        final Card endless = endlessOne();
        for (int i = 0; i < LoopPool.TARGET; i++) {
            ai.getManaPool().addMana(new Mana((byte) ManaAtom.COLORLESS, basalt, null, ai));
        }
        assertNull(driver.mainPhaseAction(List.of()), "the pool at its target at a boundary: the walk's turn");
        assertFalse(driver.liftsThrottle(), "the driver returned no action");
        assertFalse(LoopDriver.liftsThrottleFor(ai));
        driver.noteStockPlay(endless.getFirstSpellAbility());
        ai.getManaPool().clearPool(false);
        assertSame(driver.mainPhaseAction(List.of()), manaOf(basalt), "the walk spent the pool: the refill");
        assertTrue(driver.liftsThrottle(), "an action returned");
    }

    // Spec 6.3, "whenever mana is spent, it should refill it": the stock AI casts the first of two Endless Ones from
    // the pool of 200 (99 of it), and the next priority refills the pool to 200 before the second is cast, 99 cycles
    // more. The opponent at 100, so the board stays short of five times lethal.
    @Test
    public void theNextPriorityRefillsWhatTheStockSpent() {
        start();
        kinnan();
        final Card basalt = addCard(BASALT, ai);
        endlessOne();
        endlessOne();
        opp.setLife(100, null);
        ComboLoopBoards.stepUntil(game, g -> countCardsWithName(g, ENDLESS_ONE) == 1 && g.getStack().isEmpty(),
                MAX_STEPS);
        assertEquals(countCardsWithName(game, ENDLESS_ONE), 1, "the first cast");
        assertEquals(pool(), LoopPool.TARGET - 99);
        ComboLoopBoards.stepUntil(game, g -> pool() >= LoopPool.TARGET && g.getStack().isEmpty(), MAX_STEPS);
        assertEquals(pool(), LoopPool.TARGET, "refilled");
        assertTrue(basalt.isUntapped());
        assertEquals(countCardsWithName(game, ENDLESS_ONE), 1, "the second still in hand");
        assertEquals(untapOf(basalt).getActivationsThisTurn(), 299, "200 cycles, then 99 more");
    }

    // The driver's decisions draw no random number over a go-off of ten cycles. Its steps, played through the engine,
    // draw as the engine does: the stock trigger AI rolls once for Kinnan's mana trigger at each tap after its first
    // this turn (SpellAbilityAi.checkApiLogic), and plays the mandatory trigger whatever the roll.
    @Test
    public void theDriversDecisionsDrawNothing() {
        start();
        kinnan();
        addCard(BASALT, ai);
        endlessOne();
        final long before = MyRandom.callCount();
        assertEquals(drive(20).size(), 20);
        assertEquals(decisionDraws, 0, "the driver's decisions drew");
        assertEquals(MyRandom.callCount() - before, 9, "the engine's draws: the nine taps after the first");
        assertEquals(pool(), 10, "each of the ten taps made Kinnan's mana");
    }

    // AiController.getSpellAbilityToPlay: the loop slot sits after piece 1's go-off. A deck that holds both piece 1's
    // Oracle line (Thassa's Oracle and Demonic Consultation in hand, Island, Island and Swamp) and the Kinnan loop,
    // with a go-off the driver could start (Kinnan and Basalt Monolith on ours), casts Oracle first, not Basalt's tap;
    // asked itself on the same board, the driver would have gone off.
    @Test
    public void theLoopSlotComesAfterPieceOnesGoOff() {
        start();
        ComboLoopBoards.deck(ai, null, "Thassa's Oracle", "Demonic Consultation");
        kinnan();
        final Card basalt = addCard(BASALT, ai);
        addCard("Island", ai);
        addCard("Island", ai);
        addCard("Swamp", ai);
        final Card oracle = addCardToZone("Thassa's Oracle", ai, ZoneType.Hand);
        addCardToZone("Demonic Consultation", ai, ZoneType.Hand);
        fillLibrary(ai, 20);
        assertTrue(ids(t.deckLines()).contains("742-1295"), "piece 1's Oracle line is held: " + ids(t.deckLines()));
        assertTrue(LoopPool.stockTarget(ai, line(KINNAN_BASALT)) > 0, "a go-off the driver could start");
        final List<SpellAbility> offered = ComboLoopBoards.aiOf(ai).chooseSpellAbilityToPlay();
        assertNotNull(offered, "the AI plays something");
        assertSame(offered.get(0).getHostCard(), oracle, "piece 1's go-off goes first: " + offered);
        assertFalse(driver.liftsThrottle(), "the loop driver did not act");
        assertSame(driver.mainPhaseAction(List.of()), manaOf(basalt), "the driver's own go-off was ready");
    }

    // The AiController calls noteStockPlay or afterStockWalk after every walk, for every AI deck: neither draws, inert
    // (no go-off passed the walk the priority) or live (the round it begins, the go-off it ends). The parity tests
    // cannot see a draw here: they compare two runs of the same code, and a draw in a hook is in both.
    @Test
    public void theHooksDrawNothing() {
        start();
        kinnan();
        addCard(BASALT, ai);
        counterspells();
        final SpellAbility counter = ai.getCardsIn(ZoneType.Hand).get(0).getFirstSpellAbility();
        long before = MyRandom.callCount();
        driver.noteStockPlay(counter);
        assertNull(driver.afterStockWalk());
        assertEquals(MyRandom.callCount() - before, 0, "inert");
        assertEquals(drive(50).size(), 4, "built to 2, then the walk's turn");
        before = MyRandom.callCount();
        driver.noteStockPlay(counter);
        assertEquals(MyRandom.callCount() - before, 0, "a round begins");
        ai.getManaPool().clearPool(false);
        assertEquals(drive(50).size(), 4, "refilled to 2, then the walk's turn");
        before = MyRandom.callCount();
        assertNull(driver.afterStockWalk());
        assertEquals(MyRandom.callCount() - before, 0, "the go-off ends");
    }

    // LoopDriver.of has ComboTracker.of's guards: no driver for no player, for a player a scripted controller decides
    // for, or for an AI while another player controls it, which lifts no throttle though its own driver acted this
    // turn; the controlled AI's fresh driver (its master's PlayerControllerAi holds it) goes off for nobody, and its
    // clone choice is the stock one. Once control ends, its own driver's stamp is read again.
    @Test
    public void theDriverOfAPlayerNoAiDecidesForIsNull() {
        start();
        kinnan();
        addCard(BASALT, ai);
        endlessOne();
        assertSame(LoopDriver.of(ai), driver);
        assertNull(LoopDriver.of(null));
        assertFalse(LoopDriver.liftsThrottleFor(null));
        final Player scripted = new LobbyPlayerForTests("scripted", null).createIngamePlayer(game, 98);
        assertFalse(scripted.getController() instanceof PlayerControllerAi);
        assertNull(LoopDriver.of(scripted));
        assertFalse(LoopDriver.liftsThrottleFor(scripted));

        assertEquals(drive(2).size(), 2, "the driver acted this turn");
        assertTrue(LoopDriver.liftsThrottleFor(ai));
        final long timestamp = game.getNextTimestamp();
        ai.addController(timestamp, opp);
        try {
            assertTrue(ai.isControlled());
            assertNull(LoopDriver.of(ai));
            assertFalse(LoopDriver.liftsThrottleFor(ai), "a controlled player is nobody's driver");
            assertNull(ComboLoopBoards.aiOf(ai).getLoopDriver().mainPhaseAction(List.of()),
                    "the master's fresh driver goes off for nobody");
            // CloneAi asks LoopDriver.of too: Sakashima of a Thousand Faces, resolved for the controlled AI, takes the
            // stock copy and throws nothing
            resolvesOurs(SAKASHIMA);
        } finally {
            ai.removeController(timestamp);
        }
        assertFalse(ai.isControlled());
        assertTrue(LoopDriver.liftsThrottleFor(ai), "control back: its own driver's stamp");
    }

    // ---- the final review's round 1: what its mutation pass found untested ----

    // R30 picks a line whose cycle costs no life first: the rock untap (Kinnan and Basalt Monolith, net 1) beside x-2's
    // cast and bounce (Hullbreaker Horror, Sol Ring, Mana Vault in hand: net 5 with Kinnan), Ballista on ours with one
    // counter, the opponent at 2, and Eidolon of the Great Revel on their battlefield. The casts cost 2 life each and
    // the untap nothing, and the kill is affordable through either line: the rock line goes first, its tap of Basalt,
    // not x-2's tap of Sol Ring.
    @Test
    public void aLineWhoseCycleCostsNothingIsPickedFirst() {
        start();
        kinnan();
        final Card basalt = addCard(BASALT, ai);
        ComboLoopBoards.ready(addCard(HULLBREAKER, ai));
        addCard("Sol Ring", ai);
        addCardToZone("Mana Vault", ai, ZoneType.Hand);
        ComboLoopBoards.ready(addCard(LoopSinks.BALLISTA, ai)).setCounters(CounterEnumType.P1P1, 1);
        opp.setLife(2, null);
        addCard(EIDOLON, opp);
        assertEquals(LoopCycles.net(ai, line(KINNAN_BASALT)), 1, "the rock untap's net");
        assertEquals(LoopCycles.net(ai, line("x-2")), 5, "x-2's net, the higher");
        final SpellAbility first = driver.mainPhaseAction(List.of());
        assertSame(first, manaOf(basalt), "the untap costs nothing: Basalt's tap, not Sol Ring's");
    }

    // A line whose cycle costs life runs toward a kill our life affords even when Ballista's next step needs no mana:
    // the rock untap under Harsh Mentor (2 for each untap), Ballista on ours with two counters, the opponent at 2. The
    // volley is two pings at 2 each, and the go-off's first action is a ping.
    @Test
    public void aCostlyLineRunsToAPingThatNeedsNoMana() {
        start();
        kinnan();
        addCard(BASALT, ai);
        final Card ballista = ComboLoopBoards.ready(addCard(LoopSinks.BALLISTA, ai));
        ballista.setCounters(CounterEnumType.P1P1, 2);
        opp.setLife(2, null);
        addCard(HARSH_MENTOR, opp);
        assertEquals(LoopSinks.ballistaPool(ai), 0, "the ping needs no mana");
        final SpellAbility first = driver.mainPhaseAction(List.of());
        assertNotNull(first, "the kill is affordable: a go-off");
        assertSame(first.getHostCard(), ballista, "Ballista's step: " + first);
        assertEquals(first.getApi(), ApiType.DealDamage, "a ping first: " + first);
    }

    // R30 prices a cycle by its actions that are no mana ability (LoopCycles.lifeSteps), us each one's activator: the
    // rock untap's is its rock's untap; the flicker's, Palinchron's flicker; the return and recast's, Palinchron's
    // return while it is on our battlefield, then its cast, and with it in our hand its cast alone.
    @Test
    public void aCyclesPricedStepsAreItsNonManaActions() {
        start();
        kinnan();
        final Card basalt = addCard(BASALT, ai);
        final List<SpellAbility> rock = LoopCycles.lifeSteps(ai, line(KINNAN_BASALT));
        assertEquals(rock.size(), 1, "the rock's untap: " + rock);
        assertSame(rock.get(0), untapOf(basalt));
        assertSame(rock.get(0).getActivatingPlayer(), ai);

        final Card palinchron = paired();
        final List<SpellAbility> flicker = LoopCycles.lifeSteps(ai, line("1409-3542"));
        assertEquals(flicker.size(), 1, "Palinchron's flicker: " + flicker);
        assertSame(flicker.get(0).getHostCard(), palinchron);
        assertEquals(flicker.get(0).getApi(), ApiType.ChangeZone);
        assertSame(flicker.get(0).getActivatingPlayer(), ai);

        ComboLoopBoards.ready(addCard(NYXBLOOM, ai));
        final List<SpellAbility> recast = LoopCycles.lifeSteps(ai, line("3430-3542"));
        assertEquals(recast.size(), 2, "its return, then its cast: " + recast);
        assertSame(recast.get(0).getHostCard(), palinchron);
        assertFalse(recast.get(0).isSpell(), "the return");
        assertEquals(recast.get(0).getParam("Destination"), "Hand");
        assertSame(recast.get(1).getHostCard(), palinchron);
        assertTrue(recast.get(1).isSpell(), "the cast");
        assertSame(recast.get(0).getActivatingPlayer(), ai);
        assertSame(recast.get(1).getActivatingPlayer(), ai);

        game.getAction().moveToHand(palinchron, null);
        final List<SpellAbility> inHand = LoopCycles.lifeSteps(ai, line("3430-3542"));
        assertEquals(inHand.size(), 1, "with Palinchron in our hand the cycle is past its return: its cast alone");
        assertTrue(inHand.get(0).isSpell(), "the cast");
        assertSame(inHand.get(0).getActivatingPlayer(), ai);
    }

    // The cast and bounce's priced steps are its two pieces' casts: Sol Ring's, on our battlefield, and Mana Vault's,
    // in our hand.
    @Test
    public void theBouncesPricedStepsAreItsTwoCasts() {
        start();
        ComboLoopBoards.ready(addCard(HULLBREAKER, ai));
        final Card ring = addCard("Sol Ring", ai);
        final Card vault = addCardToZone("Mana Vault", ai, ZoneType.Hand);
        final List<SpellAbility> steps = LoopCycles.lifeSteps(ai, line("x-2"));
        assertEquals(steps.size(), 2, "the two casts: " + steps);
        int rings = 0;
        int vaults = 0;
        for (final SpellAbility sa : steps) {
            assertTrue(sa.isSpell(), "a cast: " + sa);
            assertSame(sa.getActivatingPlayer(), ai);
            rings += sa.getHostCard() == ring ? 1 : 0;
            vaults += sa.getHostCard() == vault ? 1 : 0;
        }
        assertEquals(rings, 1, "Sol Ring's cast");
        assertEquals(vaults, 1, "Mana Vault's cast");
    }

    // An entering is priced for the card a flicker returns, Rampaging Ferocidon's 1 as Palinchron comes back, and for
    // no other ability: Ballista's pump and ping put nothing onto the battlefield.
    @Test
    public void anEnteringIsPricedForTheCardAFlickerReturnsAndForNoOtherAbility() {
        start();
        final Card ballista = ComboLoopBoards.ready(addCard(LoopSinks.BALLISTA, ai));
        ballista.setCounters(CounterEnumType.P1P1, 1);
        paired();
        final SpellAbility flicker = LoopCycles.lifeSteps(ai, line("1409-3542")).get(0);
        SpellAbility pump = null;
        SpellAbility ping = null;
        for (final SpellAbility sa : ballista.getNonManaAbilities()) {
            if (sa.isActivatedAbility() && sa.getApi() == ApiType.PutCounter) {
                pump = sa;
            } else if (sa.isActivatedAbility() && sa.getApi() == ApiType.DealDamage) {
                ping = sa;
            }
        }
        assertNotNull(pump, "Ballista's pump");
        assertNotNull(ping, "Ballista's ping");
        pump.setActivatingPlayer(ai);
        ping.setActivatingPlayer(ai);
        assertEquals(LoopDriver.lifeCost(ai, flicker), 0, "no punisher");
        addCard("Rampaging Ferocidon", opp);
        assertEquals(LoopDriver.lifeCost(ai, flicker), 1, "Ferocidon's 1 as Palinchron comes back");
        assertEquals(LoopDriver.lifeCost(ai, pump), 0, "a pump puts nothing onto the battlefield");
        assertEquals(LoopDriver.lifeCost(ai, ping), 0, "a ping puts nothing onto the battlefield");
    }

    // Nothing is priced while we cannot lose life: Harsh Mentor's 2 for Basalt Monolith's untap, then 0 once our
    // Platinum Emperion (our life total cannot change) is on our battlefield.
    @Test
    public void nothingIsPricedWhileWeCannotLoseLife() {
        start();
        kinnan();
        final Card basalt = addCard(BASALT, ai);
        final SpellAbility untap = untapOf(basalt);
        untap.setActivatingPlayer(ai);
        addCard(HARSH_MENTOR, opp);
        assertEquals(LoopDriver.lifeCost(ai, untap), 2, "Harsh Mentor's 2 for the untap");
        addCard("Platinum Emperion", ai);
        assertEquals(LoopDriver.lifeCost(ai, untap), 0, "our life total cannot change");
    }

    // R30's measure for Walking Ballista: a step that cost more life than its price (a cost no price reads; here a
    // life lost by hand) ends the volley for the phase, and the go-off goes on without it. Kinnan, Basalt and Ballista
    // (three counters), the opponent at 3, Endless One in hand (a stock target of 200): the first action is a ping; we
    // lose a life as it resolves; the next decision is Basalt's tap, the refill toward what the stock AI spends, not a
    // second ping and not a pass.
    @Test
    public void aBallistaStepThatCostsMoreThanItsPriceEndsTheVolleyNotTheGoOff() {
        start();
        kinnan();
        final Card basalt = addCard(BASALT, ai);
        endlessOne();
        final Card ballista = ComboLoopBoards.ready(addCard(LoopSinks.BALLISTA, ai));
        ballista.setCounters(CounterEnumType.P1P1, 3);
        opp.setLife(3, null);
        final SpellAbility ping = decide();
        assertNotNull(ping, "a go-off starts with Ballista's ping");
        assertSame(ping.getHostCard(), ballista);
        assertEquals(ping.getApi(), ApiType.DealDamage);
        playOut(ping);
        assertEquals(opp.getLife(), 2, "the ping landed");
        ai.setLife(ai.getLife() - 1, null);
        assertSame(decide(), manaOf(basalt), "the volley is over, the go-off goes on");
    }

    // R30's judge runs once: an action judged at a decision is not judged again at the next, so a life lost between
    // them (a stock play's cost) is not charged to it. Two Counterspells and two Islands make a stock target of 2: one
    // mana floats, a tap and an untap bring the pool to 2 at a boundary, where the walk plays; it spends the pool, we
    // lose a life, and the refill goes on.
    @Test
    public void anActionIsJudgedOnce() {
        start();
        kinnan();
        final Card basalt = addCard(BASALT, ai);
        counterspells();
        ai.getManaPool().addMana(new Mana((byte) ManaAtom.COLORLESS, basalt, null, ai));
        final SpellAbility tap = decide();
        assertSame(tap, manaOf(basalt), "the refill");
        playOut(tap);
        final SpellAbility untap = decide();
        assertSame(untap, untapOf(basalt), "the untap");
        playOut(untap);
        game.getAction().checkStateEffects(true);
        assertNull(driver.mainPhaseAction(List.of()), "the pool is at its target at a boundary: the walk's turn");
        driver.noteStockPlay(ai.getCardsIn(ZoneType.Hand).get(0).getFirstSpellAbility());
        ai.getManaPool().clearPool(false);
        ai.setLife(ai.getLife() - 1, null);
        assertSame(decide(), manaOf(basalt), "the walk spent the pool: the refill goes on");
    }

    // A new phase forgets the action armed: a tap and an untap in Main 1, then in Main 2 a life lost, and the go-off
    // starts afresh, its first action Basalt's tap.
    @Test
    public void aNewPhaseForgetsTheActionArmed() {
        start();
        kinnan();
        final Card basalt = addCard(BASALT, ai);
        endlessOne();
        final SpellAbility tap = decide();
        assertSame(tap, manaOf(basalt), "the refill");
        playOut(tap);
        final SpellAbility untap = decide();
        assertSame(untap, untapOf(basalt), "the untap");
        playOut(untap);
        moveToMain2(game, ai);
        ai.getManaPool().clearPool(false);
        ai.setLife(ai.getLife() - 1, null);
        assertSame(decide(), manaOf(basalt), "Main 2's go-off starts afresh");
    }
}
