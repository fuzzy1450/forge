package forge.ai.combo;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertSame;
import static org.testng.Assert.assertTrue;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.Set;
import java.util.function.Predicate;

import org.testng.Reporter;
import org.testng.annotations.Test;

import forge.ai.AITest;
import forge.ai.LobbyPlayerAi;
import forge.deck.Deck;
import forge.deck.io.DeckSerializer;
import forge.game.Game;
import forge.game.GameEndReason;
import forge.game.GameLogEntry;
import forge.game.card.Card;
import forge.game.card.CounterEnumType;
import forge.game.phase.PhaseHandler;
import forge.game.phase.PhaseType;
import forge.game.player.GameLossReason;
import forge.game.player.Player;
import forge.game.spellability.SpellAbilityStackInstance;
import forge.game.zone.ZoneType;
import forge.item.PaperCard;
import forge.localinstance.properties.ForgeConstants;
import forge.util.MyRandom;

/**
 * Whole turns of the loop driver (spec 2026-10-29-combo-aware-ai-loops-design.md sections 4, 5, 6.5 and 6.6; the
 * plan's Task B10 and R3): each loop kind wins from a set board in our main phase, each instant-speed kind wins in
 * response to removal on the opponent's turn, and the anti-goals hold through the whole game loop: a loop with nothing
 * to buy ends quietly, an opponent whose life the caps cannot shoot down meets the turn's cap with one line in the log
 * and the game goes on, Thrasios never decks us, and a deck holding no line plays as stock, its game log and its random
 * draws alike. One more game measures what a driver action costs against a real precon, the opponent's priorities
 * included: the measure the caps (LoopRounds) are set from.
 *
 * <p>Every game is played through PhaseHandler.mainLoopStep, every seat deciding as it does in a served game. Nothing
 * of ours is put on the stack by hand; the response tests put the opponent's Murder there
 * ({@link ComboLoopBoards#theirSpellOn}: the stock AI may hold a Murder of its own). Unless a test says otherwise the
 * board is Kinnan's list registered, the tracker reading the shipped lines, both seats on the Default profile, no AI
 * deadline, our Main 1 of the game's third turn, twenty Runeclaw Bears in our library, the opponent at 40 with forty
 * Runeclaw Bears in its library and nothing else, and the random stream seeded with {@link #SEED}. Play is bounded
 * (ComboLinesEndToEndAiTest's bound, no timeOut): at most {@link #MAX_TURNS} turns and {@link #MAX_STEPS} steps. The
 * driver's actions are counted from its own round record (a {@link LoopRounds} with the shipped caps, handed to the
 * driver before the game starts), and each test writes its steps and the driver's actions to TestNG's Reporter log.
 */
public class ComboLoopEndToEndAiTest extends AITest {
    private static final String KINNAN = "Kinnan, Bonder Prodigy";
    private static final String BASALT = "Basalt Monolith";
    private static final String BALLISTA = LoopSinks.BALLISTA;
    private static final String THRASIOS = LoopSinks.THRASIOS;
    private static final String PALINCHRON = "Palinchron";
    private static final String DEADEYE = "Deadeye Navigator";
    private static final String NYXBLOOM = "Nyxbloom Ancient";
    private static final String HULLBREAKER = "Hullbreaker Horror";
    private static final String SOL_RING = "Sol Ring";
    private static final String MANA_VAULT = "Mana Vault";
    private static final String MEMNITE = "Memnite";
    private static final String COUNTERSPELL = "Counterspell";
    private static final String MURDER = "Murder";
    private static final String ISLAND = "Island";
    private static final String FOREST = "Forest";
    private static final String SCEPTER = "Isochron Scepter";
    private static final String REVERSAL = "Dramatic Reversal";
    private static final String BIRGI = "Birgi, God of Storytelling";
    private static final String WRATH = "Wrath of God";
    /** The flicker of Palinchron and Deadeye Navigator. */
    private static final String FLICKER = "1409-3542";
    /** The precon the measure plays against: Forge's own, as the build's combo smokes do. */
    private static final String PRECON = "Arcane Wizardry";
    /** The turns a game may take after its first: a bound, not a measure. */
    private static final int MAX_TURNS = 20;
    /** The main-loop steps a game may take (LoopProgressTest's playOut: bounded, no timeOut). */
    private static final int MAX_STEPS = 20_000;
    /** The steps each of the parity test's two games takes. */
    private static final int PARITY_STEPS = 200;
    /** The seed every game starts from, so each plays the same way on every run. */
    private static final long SEED = 7;
    /** The game's turn the boards are set on: our third. */
    private static final int OUR_TURN = 3;
    /** The opponent's life: Commander's 40. */
    private static final int LIFE = 40;
    /** Kinnan's list beside its commander: every piece of the 19 loop rows, the sinks, lands and Counterspell. */
    private static final List<String> KINNANS_LIST = kinnansList();

    private Game game;
    private Player ai;
    private Player opp;
    private ComboTracker t;
    /** The driver's round record and caps: the shipped caps, the record the driver counts its actions in. */
    private LoopRounds rounds;

    /** What a bounded play saw: the steps it took; the driver's actions; the phases they came in ("turn 3 MAIN1"),
     *  each once, in their order; our stack items as each was first seen (a spell its card's name, an ability its
     *  card's name and " ability", a trigger its card's name and " trigger"); its wall time. */
    private record Play(int steps, int actions, List<String> phases, List<String> ours, long millis) {
        int count(final String item) {
            int n = 0;
            for (final String s : ours) {
                if (s.equals(item)) {
                    n++;
                }
            }
            return n;
        }

        @Override
        public String toString() {
            final List<String> last = ours.subList(Math.max(0, ours.size() - 20), ours.size());
            return steps + " steps, " + actions + " driver actions in " + phases + ", " + millis + " ms; our last "
                    + last.size() + " of " + ours.size() + " stack items: " + last;
        }
    }

    private static List<String> kinnansList() {
        final Set<String> names = new LinkedHashSet<>();
        for (final ComboLines.Line l : ComboLoopBoards.shippedLoopLines(LoopRows.ids().toArray(new String[0]))) {
            names.addAll(l.names());
        }
        names.remove(KINNAN);
        names.addAll(List.of(BALLISTA, THRASIOS, FOREST, ISLAND, COUNTERSPELL));
        return List.copyOf(names);
    }

    /** The default board's game: Kinnan's list registered, twenty Runeclaw Bears in our library, the opponent at 40
     *  with forty in theirs; nothing placed. */
    private void start() {
        board(KINNAN, KINNANS_LIST);
        fillLibrary(ai, 20);
        fillLibrary(opp, 40);
    }

    /** A two-player game in our Main 1 of the game's third turn (the AI is player 1, the opponent player 0; both on the
     *  Default profile; no AI deadline), {@code main} and {@code commander} as the AI's registered deck, the opponent
     *  at 40 with nothing, the driver counting its actions in {@link #rounds}, the random stream seeded; no library
     *  filled. */
    private void board(final String commander, final List<String> main) {
        game = initAndCreateGame();
        ComboLoopBoards.noDeadline(game);
        ai = game.getPlayers().get(1);
        opp = game.getPlayers().get(0);
        for (final Player p : game.getPlayers()) {
            ((LobbyPlayerAi) p.getLobbyPlayer()).setAiProfile("Default");
        }
        ComboLoopBoards.deck(ai, commander, main.toArray(new String[0]));
        t = ComboLoopBoards.aiOf(ai).getComboTracker();
        rounds = new LoopRounds();
        ComboLoopBoards.aiOf(ai).getLoopDriver().useRounds(rounds);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, ai, OUR_TURN);
        opp.setLife(LIFE, null);
        MyRandom.setRandom(new Random(SEED));
    }

    /** Kinnan, ready, and Basalt Monolith on our battlefield: the rock untap 129-4131. Returns Kinnan. */
    private Card rock() {
        final Card kinnan = ComboLoopBoards.ready(addCard(KINNAN, ai));
        addCard(BASALT, ai);
        return kinnan;
    }

    /** A Walking Ballista of ours on our battlefield, ready, with {@code counters} +1/+1 counters. */
    private Card ballista(final int counters) {
        final Card b = ComboLoopBoards.ready(addCard(BALLISTA, ai));
        b.setCounters(CounterEnumType.P1P1, counters);
        return b;
    }

    private Card ballistaInHand() {
        return addCardToZone(BALLISTA, ai, ZoneType.Hand);
    }

    /** Palinchron and Deadeye Navigator, ready on our battlefield and paired: Deadeye's {1}{U} flicker granted to both.
     *  Returns Palinchron. */
    private Card paired() {
        final Card palinchron = ComboLoopBoards.ready(addCard(PALINCHRON, ai));
        final Card deadeye = ComboLoopBoards.ready(addCard(DEADEYE, ai));
        palinchron.setPairedWith(deadeye);
        deadeye.setPairedWith(palinchron);
        game.getAction().checkStaticAbilities();
        return palinchron;
    }

    /** {@code n} untapped lands of ours named {@code name}. */
    private void lands(final String name, final int n) {
        for (int i = 0; i < n; i++) {
            addCard(name, ai);
        }
    }

    /** Hullbreaker Horror, ready, and Sol Ring on our battlefield, Mana Vault in our hand: x-2's cast and bounce, whose
     *  cycles cast its two rocks and tap them for mana. */
    private void bounce() {
        ComboLoopBoards.ready(addCard(HULLBREAKER, ai));
        addCard(SOL_RING, ai);
        addCardToZone(MANA_VAULT, ai, ZoneType.Hand);
    }

    /** The opponent's Main 1 of the game's fourth turn, the turn after ours. */
    private void theirTurn() {
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, opp, OUR_TURN + 1);
    }

    /** Our first card on our battlefield by its printed name (ComboTracker.nameOf), or null. */
    private Card onOurBattlefield(final String name) {
        for (final Card c : ai.getCardsIn(ZoneType.Battlefield)) {
            if (name.equals(ComboTracker.nameOf(c))) {
                return c;
            }
        }
        return null;
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

    /** The whole game log, an entry a line: its type and its message. */
    private List<String> gameLog() {
        final List<String> out = new ArrayList<>();
        for (final GameLogEntry e : game.getGameLog().getAllEntries()) {
            out.add(e.type() + ": " + e.message());
        }
        return out;
    }

    private static List<String> ids(final List<ComboLines.Line> lines) {
        final List<String> out = new ArrayList<>();
        for (final ComboLines.Line l : lines) {
            out.add(l.variant().id());
        }
        return out;
    }

    private static String label(final SpellAbilityStackInstance si) {
        final String name = ComboTracker.nameOf(si.getSourceCard());
        return si.isSpell() ? name : si.isTrigger() ? name + " trigger" : name + " ability";
    }

    /** {@link #play(String, Predicate, int)} to {@link #MAX_STEPS}. */
    private Play play(final String label, final Predicate<Game> done) {
        return play(label, done, MAX_STEPS);
    }

    /**
     * Plays the game, bounded, until it is over, {@code done} holds, {@link #MAX_TURNS} turns have passed or
     * {@code maxSteps} steps were taken, and writes what it saw to the Reporter log under {@code label}. The driver's
     * actions in a step are what the actions it has left before a cap stops it ({@link LoopRounds#left}) lost in it:
     * each action it returns is counted there once. A step in which the AI acted ends in the turn it began in
     * (mainLoopStep advances the phase only when the player with priority passed at once), so a step that ends in
     * another turn holds no action of the driver's, and the count it has left starts afresh with the turn.
     */
    private Play play(final String label, final Predicate<Game> done, final int maxSteps) {
        final PhaseHandler ph = game.getPhaseHandler();
        final int lastTurn = ph.getTurn() + MAX_TURNS;
        final Set<String> phases = new LinkedHashSet<>();
        final List<String> ours = new ArrayList<>();
        final Set<Integer> seen = new HashSet<>();
        int steps = 0;
        int actions = 0;
        final long from = System.nanoTime();
        while (!game.isGameOver() && !done.test(game) && ph.getTurn() <= lastTurn && steps < maxSteps) {
            final int turn = ph.getTurn();
            final String at = "turn " + turn + " " + ph.getPhase();
            final int left = rounds.left(ai);
            ph.mainLoopStep();
            steps++;
            final int acted = ph.getTurn() == turn ? left - rounds.left(ai) : 0;
            if (acted > 0) {
                actions += acted;
                phases.add(at);
            }
            for (final SpellAbilityStackInstance si : game.getStack()) {
                if (seen.add(si.getId()) && ai.equals(si.getActivatingPlayer())) {
                    ours.add(label(si));
                }
            }
        }
        final Play p = new Play(steps, actions, List.copyOf(phases), List.copyOf(ours),
                (System.nanoTime() - from) / 1_000_000);
        Reporter.log(label + ": " + p.steps() + " steps, " + p.actions() + " driver actions in " + p.phases() + ", "
                + p.millis() + " ms, " + p.ours().size() + " stack items of ours; " + outcome());
        return p;
    }

    /** How the game stands: its outcome once it is over, else whose turn and phase it is. */
    private String outcome() {
        final PhaseHandler ph = game.getPhaseHandler();
        if (!game.isGameOver()) {
            return "the game goes on in turn " + ph.getTurn() + ", " + ph.getPlayerTurn() + "'s " + ph.getPhase();
        }
        return "the game ended in turn " + ph.getTurn() + ", " + ph.getPlayerTurn() + "'s " + ph.getPhase() + ": "
                + game.getOutcome().getWinCondition() + ", the opponent's loss "
                + (opp.getOutcome() == null ? null : opp.getOutcome().lossState);
    }

    /** The opponent lost for having 0 or less life, the game over, the AI its winner, in {@code turnOf}'s turn
     *  {@code turn}, the driver having acted. */
    private void assertOpponentLostByLife(final Play p, final Player turnOf, final int turn) {
        assertTrue(game.isGameOver(), "the game went on: " + p + "; " + outcome() + "\n" + gameStateToString(game));
        assertTrue(ai.hasWon(), "the AI did not win: " + outcome() + "; " + p);
        assertEquals(game.getOutcome().getWinCondition(), GameEndReason.AllOpponentsLost, "the game's end");
        assertNotNull(opp.getOutcome(), "the opponent is still in the game");
        assertEquals(opp.getOutcome().lossState, GameLossReason.LifeReachedZero, "the opponent's loss");
        assertSame(game.getPhaseHandler().getPlayerTurn(), turnOf, "whose turn the game ended in: " + outcome());
        assertEquals(game.getPhaseHandler().getTurn(), turn, "the turn the game ended in: " + outcome());
        assertTrue(p.actions() > 0, "the driver never acted: " + p);
    }

    // ---- the plan's tests ----

    // The rock untap (Kinnan and Basalt Monolith), Walking Ballista in our hand: the go-off builds the pool, Ballista
    // is cast from it with X at the opponent's 40, and its pings kill: the opponent loses for having 0 life in our
    // own Main 1.
    @Test
    public void theRockLoopWinsThisTurn() {
        start();
        rock();
        ballistaInHand();
        final Play p = play("theRockLoopWinsThisTurn", g -> false);
        assertOpponentLostByLife(p, ai, OUR_TURN);
        assertTrue(game.getPhaseHandler().is(PhaseType.MAIN1, ai), "not in our Main 1: " + outcome());
        assertEquals(p.count(BALLISTA), 1, "Ballista cast once: " + p);
    }

    // The flicker (Palinchron and Deadeye Navigator paired), seven Islands, Ballista on our battlefield with one
    // counter: each cycle taps the seven Islands and flickers Palinchron, whose entering untap puts them back; the pool
    // pumps Ballista to the opponent's 40, and its pings kill in our own Main 1.
    @Test
    public void theFlickerLoopWinsThisTurn() {
        start();
        paired();
        lands(ISLAND, 7);
        ballista(1);
        final Play p = play("theFlickerLoopWinsThisTurn", g -> false);
        assertOpponentLostByLife(p, ai, OUR_TURN);
        assertTrue(game.getPhaseHandler().is(PhaseType.MAIN1, ai), "not in our Main 1: " + outcome());
        assertTrue(p.count(PALINCHRON + " ability") > 0, "Palinchron was never flickered: " + p);
    }

    // The return and recast (Palinchron and Nyxbloom Ancient, no Deadeye: R5), four Islands and three Forests, Ballista
    // in our hand: each cycle taps the seven lands, tripled, returns Palinchron and casts it again; Ballista is cast
    // from the pool with X at the opponent's 40, and its pings kill in our own Main 1.
    @Test
    public void theRecastLoopWinsThisTurn() {
        start();
        ComboLoopBoards.ready(addCard(PALINCHRON, ai));
        ComboLoopBoards.ready(addCard(NYXBLOOM, ai));
        lands(ISLAND, 4);
        lands(FOREST, 3);
        ballistaInHand();
        final Play p = play("theRecastLoopWinsThisTurn", g -> false);
        assertOpponentLostByLife(p, ai, OUR_TURN);
        assertTrue(game.getPhaseHandler().is(PhaseType.MAIN1, ai), "not in our Main 1: " + outcome());
        assertTrue(p.count(PALINCHRON) > 0, "Palinchron was never cast again: " + p);
        assertEquals(p.count(BALLISTA), 1, "Ballista cast once: " + p);
    }

    // The cast and bounce of row x-2 (Hullbreaker Horror, Sol Ring and Mana Vault): Kinnan, Hullbreaker and Sol Ring on
    // our battlefield, Mana Vault and Ballista in our hand. Each cycle taps one rock, casts the other, whose cast
    // trigger returns the first to our hand; Ballista is cast from the pool with X at the opponent's 40 (its own cast
    // trigger the stock AI's), and its pings kill in our own Main 1.
    @Test
    public void theBounceLoopWinsThisTurn() {
        start();
        ComboLoopBoards.ready(addCard(KINNAN, ai));
        ComboLoopBoards.ready(addCard(HULLBREAKER, ai));
        addCard(SOL_RING, ai);
        addCardToZone(MANA_VAULT, ai, ZoneType.Hand);
        ballistaInHand();
        final Play p = play("theBounceLoopWinsThisTurn", g -> false);
        assertOpponentLostByLife(p, ai, OUR_TURN);
        assertTrue(game.getPhaseHandler().is(PhaseType.MAIN1, ai), "not in our Main 1: " + outcome());
        assertTrue(p.count(MANA_VAULT) > 0, "Mana Vault was never cast: " + p);
        assertEquals(p.count(BALLISTA), 1, "Ballista cast once: " + p);
    }

    // The rock board with Ballista on our battlefield (one counter), set in the opponent's Main 1 of the turn after
    // ours: their Murder on Kinnan is put on the stack, and whole turns are played. The rock loop goes off above it and
    // Ballista shoots to the opponent's 40 before it resolves: the opponent loses in their own Main 1, and Kinnan never
    // leaves our battlefield.
    @Test
    public void theResponseWinsOnTheirTurn() {
        start();
        final Card kinnan = rock();
        ballista(1);
        theirTurn();
        ComboLoopBoards.theirSpellOn(game, opp, MURDER, kinnan);
        final Play p = play("theResponseWinsOnTheirTurn", g -> false);
        assertOpponentLostByLife(p, opp, OUR_TURN + 1);
        assertTrue(game.getPhaseHandler().is(PhaseType.MAIN1, opp), "not in their Main 1: " + outcome());
        assertSame(onOurBattlefield(KINNAN), kinnan, "Murder resolved: Kinnan left our battlefield");
    }

    // The same with the flicker: Palinchron and Deadeye Navigator paired, seven Islands, Ballista with one counter, and
    // the opponent's Murder on Palinchron. The flicker goes off above it (the first flicker makes Palinchron a new
    // object), and the opponent loses in their own Main 1, Palinchron and Deadeye still on our battlefield.
    @Test
    public void theFlickerResponseWinsOnTheirTurn() {
        start();
        final Card palinchron = paired();
        lands(ISLAND, 7);
        ballista(1);
        theirTurn();
        ComboLoopBoards.theirSpellOn(game, opp, MURDER, palinchron);
        final Play p = play("theFlickerResponseWinsOnTheirTurn", g -> false);
        assertOpponentLostByLife(p, opp, OUR_TURN + 1);
        assertTrue(game.getPhaseHandler().is(PhaseType.MAIN1, opp), "not in their Main 1: " + outcome());
        assertNotNull(onOurBattlefield(PALINCHRON), "Palinchron left our battlefield");
        assertNotNull(onOurBattlefield(DEADEYE), "Deadeye left our battlefield");
    }

    // A loop with nothing to buy: the rock untap, two Counterspells in our hand and two Islands to cast them (a stock
    // target of 2 that the stock AI never spends, with no spell to counter). The game reaches the opponent's turn
    // within the bound (no Timeout) and goes on (no Draw), and the driver went off in two phases at most (on this
    // board in Main 1 alone: Main 2's hand and battlefield are the futile go-off's). Beyond the plan: it went off at
    // least once, so the board is a loop that ran.
    @Test
    public void aLoopWithNothingToBuyEndsQuietly() {
        start();
        rock();
        addCardToZone(COUNTERSPELL, ai, ZoneType.Hand);
        addCardToZone(COUNTERSPELL, ai, ZoneType.Hand);
        lands(ISLAND, 2);
        final Play p = play("aLoopWithNothingToBuyEndsQuietly", g -> g.getPhaseHandler().getPlayerTurn() == opp);
        assertFalse(game.isGameOver(), "the game ended: " + outcome() + "; " + p);
        assertSame(game.getPhaseHandler().getPlayerTurn(), opp, "the opponent's turn never came: " + p);
        assertEquals(game.getPhaseHandler().getTurn(), OUR_TURN + 1, "the turn after ours");
        assertTrue(p.phases().size() <= 2, "more than two go-offs: " + p);
        assertFalse(p.phases().isEmpty(), "the loop never went off: " + p);
    }

    // Review Focus 2: the opponent at 100,000 with nothing on its battlefield, the rock untap and Ballista on ours
    // (one counter). Ballista is pumped toward a kill the caps cannot reach: the turn's cap stops the driver, its line
    // in the game log once (the game's never), the driver's actions that turn exactly the cap, and the game reaches the
    // next turn within the bound (no Timeout) and goes on (no Draw). The caps are those Step 2 kept from this class's
    // measure (2,000 a turn, 5,000 a game: the ledger's Task B10 section), the turn's line the plan's own words.
    @Test
    public void aHugeLifeTotalEndsAtTheCapAndPlayGoesOn() {
        assertEquals(LoopRounds.TURN_CAP, 2_000, "the turn's cap");
        assertEquals(LoopRounds.GAME_CAP, 5_000, "the game's cap");
        start();
        opp.setLife(100_000, null);
        rock();
        ballista(1);
        final Play p = play("aHugeLifeTotalEndsAtTheCapAndPlayGoesOn", g -> g.getPhaseHandler().getTurn() > OUR_TURN);
        assertEquals(logged("Loop driver: stopped for the turn at 2000 actions."), 1, "the turn cap's line: " + p);
        assertEquals(logged("Loop driver: stopped for the game at 5000 actions."), 0, "the game cap's line: " + p);
        assertFalse(game.isGameOver(), "the game ended: " + outcome() + "; " + p);
        assertEquals(game.getPhaseHandler().getTurn(), OUR_TURN + 1, "the next turn never came: " + p);
        assertEquals(p.actions(), LoopRounds.TURN_CAP, "the driver's actions in our turn: " + p);
    }

    // The anti-goal "decking itself": Kinnan, Basalt and Thrasios, no Ballista, a library of fifteen Memnites and
    // fifteen Forests in turn, Memnite on top, the opponent at 40. Thrasios digs while its dig can add to our board,
    // the stock AI casting each Memnite ({0}) it draws, and at the end of our turn our library holds four cards or
    // more: Thrasios's floor. (Beyond the plan: Thrasios dug.)
    @Test
    public void thrasiosNeverDecksUs() {
        board(KINNAN, KINNANS_LIST);
        fillLibrary(opp, 40);
        rock();
        ComboLoopBoards.ready(addCard(THRASIOS, ai));
        for (int i = 0; i < 15; i++) {
            addCardToZone(MEMNITE, ai, ZoneType.Library);
            addCardToZone(FOREST, ai, ZoneType.Library);
        }
        final Play p = play("thrasiosNeverDecksUs", g -> g.getPhaseHandler().getTurn() > OUR_TURN);
        final int library = ai.getCardsIn(ZoneType.Library).size();
        Reporter.log("thrasiosNeverDecksUs: our library " + library + " at the end of our turn, "
                + p.count(THRASIOS + " ability") + " Thrasios activations, "
                + ai.getCardsIn(ZoneType.Battlefield).size() + " permanents of ours");
        assertEquals(game.getPhaseHandler().getTurn(), OUR_TURN + 1, "our turn never ended: " + p);
        assertTrue(library >= LoopSinks.LIBRARY_FLOOR, "Thrasios decked us: " + library + "; " + p);
        assertTrue(p.count(THRASIOS + " ability") > 0, "Thrasios never dug: " + p);
    }

    // A deck holding no line plays as stock (Global Constraints, Scope): the rock board (Kinnan, Basalt, Ballista in
    // hand), its deck Kinnan beside Basalt and Ballista, seed 7, played twice for 200 steps: once with the tracker
    // holding no line, once with a loop line the deck does not hold (the flicker's: no Palinchron, no Deadeye). The
    // two game logs are equal, and so are the random draws each made; the driver never acted in either.
    @Test
    public void aDeckWithNoLinePlaysAsStock() {
        final List<String> noLine = new ArrayList<>();
        final long noLineDraws = parityGame("aDeckWithNoLinePlaysAsStock, no line", List.of(), noLine);
        final List<String> unheld = new ArrayList<>();
        final long unheldDraws = parityGame("aDeckWithNoLinePlaysAsStock, an unheld line",
                ComboLoopBoards.shippedLoopLines(FLICKER), unheld);
        assertEquals(unheld, noLine, "the game logs");
        assertEquals(unheldDraws, noLineDraws, "the random draws");
    }

    // One of the parity test's games: the rock board, the tracker holding {@code lines}, played for 200 steps from
    // seed 7; its game log into {@code log}; returns the random draws it made
    private long parityGame(final String label, final List<ComboLines.Line> lines, final List<String> log) {
        board(KINNAN, List.of(BASALT, BALLISTA));
        fillLibrary(ai, 20);
        fillLibrary(opp, 40);
        t.useLines(lines);
        rock();
        ballistaInHand();
        assertTrue(t.deckLines().isEmpty(), "the deck holds a line: " + ids(t.deckLines()));
        MyRandom.setRandom(new Random(SEED));
        final long before = MyRandom.callCount();
        final Play p = play(label, g -> false, PARITY_STEPS);
        final long draws = MyRandom.callCount() - before;
        log.addAll(gameLog());
        Reporter.log(label + ": " + draws + " random draws, " + log.size() + " log entries");
        assertEquals(p.actions(), 0, "the driver acted: " + p);
        return draws;
    }

    // The measure the caps are set from (Step 2, R3): the rock untap and Ballista on our battlefield (one counter, so a
    // removal of theirs meets the response), against a real precon: Arcane Wizardry's cards, shuffled from seed 7, as
    // the opponent's hand of seven and library, seven of its basics untapped on their battlefield, the opponent at 40.
    // A precon's cards hold no line, so the opponent's tracker holds none. Our turn is played whole; no assertion on
    // time: the driver's actions and the wall time, the opponent's priorities included, go to the Reporter log.
    @Test
    public void measureMsPerDriverAction() {
        board(KINNAN, KINNANS_LIST);
        fillLibrary(ai, 20);
        rock();
        ballista(1);
        precon();
        ComboLoopBoards.aiOf(opp).getComboTracker().useLines(List.of());
        MyRandom.setRandom(new Random(SEED));
        final Play p = play("measureMsPerDriverAction", g -> g.getPhaseHandler().getTurn() > OUR_TURN);
        Reporter.log(String.format(Locale.ROOT, "measureMsPerDriverAction: %.3f ms per driver action, the opponent's "
                + "priorities included (%d ms, %d actions, %d steps); the opponent's hand %s", (double) p.millis()
                / Math.max(1, p.actions()), p.millis(), p.actions(), p.steps(), opp.getCardsIn(ZoneType.Hand)));
        assertTrue(p.actions() > 0, "the driver never acted: " + p);
    }

    // ---- the final review's round 1 ----

    // Finding 2, spec section 4's anti-goal against the stock AI's own loop on a turn the driver acted in (section 6.8,
    // amended): x-2's cast and bounce beside Isochron Scepter, Dramatic Reversal imprinted on it, and Birgi, God of
    // Storytelling. The driver goes off, which makes the turn its own, and the stock AI copies Reversal with the
    // Scepter, each copy untapping Sol Ring and the Scepter to pay for the next: from the tenth activation the stock
    // roll ends that loop, as it ends any repeatable ability's, and the opponent's turn comes within the bound.
    @Test
    public void aStockLoopOnADriverTurnStillEnds() {
        start();
        bounce();
        ComboLoopBoards.ready(addCard(BIRGI, ai));
        final Card scepter = addCard(SCEPTER, ai);
        final Card reversal = addCardToZone(REVERSAL, ai, ZoneType.Exile);
        scepter.addImprintedCard(reversal);
        scepter.addExiledCard(reversal);
        reversal.setExiledWith(scepter);
        reversal.setExiledBy(ai);
        final Play p = play("aStockLoopOnADriverTurnStillEnds", g -> g.getPhaseHandler().getPlayerTurn() == opp);
        assertSame(game.getPhaseHandler().getPlayerTurn(), opp, "our turn never ended: " + p);
        assertTrue(p.actions() > 0, "the driver never acted: " + p);
        assertTrue(p.count(SCEPTER + " ability") >= 10, "the Scepter's loop never reached the stock roll: " + p);
    }

    // Finding 3 (spec section 9's stock flicker loop): B10's probe board, Palinchron and Deadeye Navigator paired,
    // seven Islands, no Walking Ballista (the driver has no response to make), the opponent's Murder on Palinchron on
    // their turn. The stock blink-to-save flickers Palinchron once, which saves it: Murder's target is gone, and it
    // fizzles. For a deck holding the flicker line a card is flickered to save it only while an opponent's item targets
    // it as the object it is now, so the new Palinchron is not flickered again (the stock reading compares card ids,
    // which a flicker keeps: 429 flickers in 3,000 steps), and our next turn comes within the bound.
    @Test
    public void theStockFlickerSavesPalinchronOnce() {
        start();
        final Card palinchron = paired();
        lands(ISLAND, 7);
        theirTurn();
        ComboLoopBoards.theirSpellOn(game, opp, MURDER, palinchron);
        final Play p = play("theStockFlickerSavesPalinchronOnce",
                g -> g.getPhaseHandler().getTurn() > OUR_TURN + 1);
        assertEquals(game.getPhaseHandler().getTurn(), OUR_TURN + 2, "our next turn never came: " + p);
        assertNotNull(onOurBattlefield(PALINCHRON), "Murder resolved: Palinchron left our battlefield");
        assertEquals(p.count(PALINCHRON + " ability"), 1, "Palinchron's flickers: " + p);
    }

    // The same with Ballista on ours (one counter) and the opponent at 100,000: the volley cannot fit in what the caps
    // have left (R28), so the driver declines Murder, and the stock blink-to-save flickers Palinchron once.
    @Test
    public void aDeclinedResponseFlickersOnce() {
        start();
        opp.setLife(100_000, null);
        final Card palinchron = paired();
        lands(ISLAND, 7);
        ballista(1);
        theirTurn();
        ComboLoopBoards.theirSpellOn(game, opp, MURDER, palinchron);
        final Play p = play("aDeclinedResponseFlickersOnce", g -> g.getPhaseHandler().getTurn() > OUR_TURN + 1);
        assertEquals(game.getPhaseHandler().getTurn(), OUR_TURN + 2, "our next turn never came: " + p);
        assertEquals(p.actions(), 0, "the driver responded: " + p);
        assertNotNull(onOurBattlefield(PALINCHRON), "Murder resolved: Palinchron left our battlefield");
        assertEquals(p.count(PALINCHRON + " ability"), 1, "Palinchron's flickers: " + p);
    }

    // An untargeted removal: the opponent's Wrath of God, Palinchron and Deadeye paired, seven Islands, no Ballista. A
    // flicker saves nothing from it, since it destroys what is there when it resolves, and the stock reading, which
    // takes in every creature it would destroy, reads the new Palinchron as threatened after each flicker: for a deck
    // holding the flicker line nothing is flickered, Wrath resolves, and our next turn comes within the bound.
    @Test
    public void aWrathDrawsNoFlicker() {
        start();
        paired();
        lands(ISLAND, 7);
        theirTurn();
        ComboLoopBoards.theirSpellOn(game, opp, WRATH);
        final Play p = play("aWrathDrawsNoFlicker", g -> g.getPhaseHandler().getTurn() > OUR_TURN + 1);
        assertEquals(game.getPhaseHandler().getTurn(), OUR_TURN + 2, "our next turn never came: " + p);
        assertEquals(p.count(PALINCHRON + " ability"), 0, "Palinchron's flickers: " + p);
        assertNull(onOurBattlefield(PALINCHRON), "Wrath of God did not resolve");
    }

    // The blink refusal's reading (LoopDriver.refusesBlink): with the opponent's Murder aimed at Palinchron as it is
    // now, a flicker saves it, and it is not refused; with Wrath of God, which targets nothing, a flicker saves
    // nothing, and it is refused; and for a deck holding no flicker line (the tracker holding none) the stock choice
    // stands, Wrath or not.
    @Test
    public void theBlinkRefusalReadsTheTargetAndNeedsTheFlickerLine() {
        start();
        final Card aimed = paired();
        theirTurn();
        ComboLoopBoards.theirSpellOn(game, opp, MURDER, aimed);
        assertFalse(LoopDriver.refusesBlink(ai, List.of(aimed)), "Murder aims at Palinchron as it is now");
        start();
        final Card palinchron = paired();
        theirTurn();
        ComboLoopBoards.theirSpellOn(game, opp, WRATH);
        assertTrue(LoopDriver.refusesBlink(ai, List.of(palinchron)), "nothing targets Palinchron");
        t.useLines(List.of());
        assertFalse(LoopDriver.refusesBlink(ai, List.of(palinchron)), "a deck holding no flicker line");
    }

    // Arcane Wizardry's main deck as the opponent's: three Islands, two Swamps and two Mountains of it untapped on
    // their battlefield, the rest shuffled from seed 7 (a Random of its own, not the game's), the first seven in their
    // hand and the others their library
    private void precon() {
        final Deck deck = DeckSerializer.fromFile(new File(ForgeConstants.QUEST_PRECON_DIR, PRECON + ".dck"));
        assertNotNull(deck, "no precon " + PRECON);
        final List<String> cards = new ArrayList<>();
        for (final PaperCard pc : deck.getMain().toFlatList()) {
            cards.add(pc.getName());
        }
        for (final String land : List.of(ISLAND, ISLAND, ISLAND, "Swamp", "Swamp", "Mountain", "Mountain")) {
            assertTrue(cards.remove(land), "the precon lacks a " + land);
            addCard(land, opp);
        }
        Collections.shuffle(cards, new Random(SEED));
        for (int i = 0; i < cards.size(); i++) {
            addCardToZone(cards.get(i), opp, i < 7 ? ZoneType.Hand : ZoneType.Library);
        }
    }
}
