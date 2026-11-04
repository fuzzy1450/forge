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
import forge.ai.AiPlayDecision;
import forge.ai.LobbyPlayerAi;
import forge.card.mana.ManaAtom;
import forge.deck.Deck;
import forge.deck.io.DeckSerializer;
import forge.game.Game;
import forge.game.GameEndReason;
import forge.game.GameLogEntry;
import forge.game.ability.AbilityFactory;
import forge.game.ability.ApiType;
import forge.game.card.Card;
import forge.game.card.CounterEnumType;
import forge.game.mana.Mana;
import forge.game.phase.PhaseHandler;
import forge.game.phase.PhaseType;
import forge.game.player.GameLossReason;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.spellability.SpellAbilityStackInstance;
import forge.game.trigger.Trigger;
import forge.game.trigger.WrappedAbility;
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
    private static final String HARSH_MENTOR = "Harsh Mentor";
    private static final String YAVIMAYA_COAST = "Yavimaya Coast";
    private static final String JIN = "Jin-Gitaxias, Core Augur";
    private static final String SCEPTER = "Isochron Scepter";
    private static final String REVERSAL = "Dramatic Reversal";
    private static final String BIRGI = "Birgi, God of Storytelling";
    private static final String WRATH = "Wrath of God";
    private static final String NEZAHAL = "Nezahal, Primal Tide";
    private static final String EIDOLON = "Eidolon of the Great Revel";
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
     *  card's name and " ability", a trigger its card's name and " trigger"); among them, our abilities that move
     *  cards off the battlefield, each its card's name, " to " and the zone they go to ("Palinchron to Exile" for
     *  Deadeye Navigator's flicker, "Palinchron to Hand" for Palinchron's own return); its wall time; the most items
     *  of ours on the stack at once after a step; and our abilities among our stack items, each its card's name, a
     *  space and its API ("Walking Ballista DealDamage" for a ping, "Walking Ballista PutCounter" for a pump). */
    private record Play(int steps, int actions, List<String> phases, List<String> ours, List<String> moves,
            long millis, int deepest, List<String> acts) {
        int count(final String item) {
            int n = 0;
            for (final String s : ours) {
                if (s.equals(item)) {
                    n++;
                }
            }
            return n;
        }

        /** How many of our abilities moved the card named {@code name} off the battlefield to {@code to}. */
        int moved(final String name, final ZoneType to) {
            int n = 0;
            for (final String s : moves) {
                if (s.equals(name + " to " + to.name())) {
                    n++;
                }
            }
            return n;
        }

        /** How many of our abilities on the stack were the card named {@code name}'s of that API. */
        int acted(final String name, final ApiType api) {
            int n = 0;
            for (final String s : acts) {
                if (s.equals(name + " " + api)) {
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

    /** {@code n} colourless mana floating in our pool, made by our Basalt Monolith ({@link #rock} first). */
    private void floating(final int n) {
        final Card basalt = onOurBattlefield(BASALT);
        for (int i = 0; i < n; i++) {
            ai.getManaPool().addMana(new Mana((byte) ManaAtom.COLORLESS, basalt, null, ai));
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
        return ourCardIn(ZoneType.Battlefield, name);
    }

    /** Our first card in that zone by its printed name (ComboTracker.nameOf), or null. */
    private Card ourCardIn(final ZoneType zone, final String name) {
        for (final Card c : ai.getCardsIn(zone)) {
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

    // an ability of ours on the stack that moves cards off the battlefield (a ChangeZone from it): its card's name,
    // " to " and the zone it moves them to; null for a spell, a trigger or any other ability
    private static String move(final SpellAbilityStackInstance si) {
        final SpellAbility sa = si.getSpellAbility();
        if (si.isSpell() || si.isTrigger() || sa.getApi() != ApiType.ChangeZone || !sa.hasParam("Destination")
                || !"Battlefield".equals(sa.getParam("Origin"))) {
            return null;
        }
        return ComboTracker.nameOf(si.getSourceCard()) + " to "
                + ZoneType.smartValueOf(sa.getParam("Destination")).name();
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
        final List<String> moves = new ArrayList<>();
        final List<String> acts = new ArrayList<>();
        final Set<Integer> seen = new HashSet<>();
        int steps = 0;
        int actions = 0;
        int deepest = 0;
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
            int mine = 0;
            for (final SpellAbilityStackInstance si : game.getStack()) {
                final boolean ofOurs = ai.equals(si.getActivatingPlayer());
                mine += ofOurs ? 1 : 0;
                if (seen.add(si.getId()) && ofOurs) {
                    ours.add(label(si));
                    final String move = move(si);
                    if (move != null) {
                        moves.add(move);
                    }
                    if (!si.isSpell() && !si.isTrigger()) {
                        acts.add(ComboTracker.nameOf(si.getSourceCard()) + " " + si.getSpellAbility().getApi());
                    }
                }
            }
            deepest = Math.max(deepest, mine);
        }
        final Play p = new Play(steps, actions, List.copyOf(phases), List.copyOf(ours), List.copyOf(moves),
                (System.nanoTime() - from) / 1_000_000, deepest, List.copyOf(acts));
        Reporter.log(label + ": " + p.steps() + " steps, " + p.actions() + " driver actions in " + p.phases() + ", "
                + p.millis() + " ms, " + p.ours().size() + " stack items of ours, at most " + p.deepest()
                + " at once; " + outcome());
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

    // Finding 1, R30: the opponent's Harsh Mentor deals 2 to us for each ability of an artifact, creature or land we
    // activate that is no mana ability, so each untap of the rock loop costs 2 life (Kinnan and Basalt Monolith,
    // Walking Ballista in our hand, both at 40). A cycle that costs life is no unlimited loop, and Ballista's volley
    // through it (forty pings at 2 each, beside the untaps) would cost more than our life: the driver takes no step,
    // and our Main 1 ends with our life at 40, where the go-off used to pay its untaps until the twentieth killed us.
    @Test
    public void harshMentorNeverKillsUs() {
        start();
        ai.setLife(LIFE, null);
        rock();
        ballistaInHand();
        addCard(HARSH_MENTOR, opp);
        final Play p = play("harshMentorNeverKillsUs", g -> !g.getPhaseHandler().is(PhaseType.MAIN1));
        assertFalse(game.isGameOver(), "the game ended: " + outcome() + "; " + p);
        assertEquals(ai.getLife(), LIFE, "the loop paid life: " + p);
        assertEquals(p.actions(), 0, "the driver acted: " + p);
    }

    // R30, Walking Ballista's side: under the opponent's Harsh Mentor each pump and each ping costs us 2. x-2's cycles
    // cast its rocks and tap them for mana, so they cost no life; Ballista on ours with one counter, the opponent at
    // 11, we at 40: the volley's ten pumps and eleven pings would cost 42, all our life, so Ballista takes no step and
    // our Main 1 ends with our life at 40, where the volley used to go on until a ping's trigger killed us.
    @Test
    public void ballistaHoldsItsFireWhenAPunisherWouldKillUs() {
        start();
        ai.setLife(LIFE, null);
        opp.setLife(11, null);
        bounce();
        ballista(1);
        addCard(HARSH_MENTOR, opp);
        final Play p = play("ballistaHoldsItsFireWhenAPunisherWouldKillUs",
                g -> !g.getPhaseHandler().is(PhaseType.MAIN1));
        assertFalse(game.isGameOver(), "the game ended: " + outcome() + "; " + p);
        assertEquals(ai.getLife(), LIFE, "Ballista paid life: " + p);
        assertEquals(p.count(BALLISTA + " ability"), 0, "Ballista acted: " + p);
    }

    // The same with the opponent at 10: nine pumps and ten pings cost 38, less than our 40, so the volley goes and
    // wins in our Main 1, our life left at 2.
    @Test
    public void ballistaShootsThroughAPunisherWithinOurLife() {
        start();
        ai.setLife(LIFE, null);
        opp.setLife(10, null);
        bounce();
        ballista(1);
        addCard(HARSH_MENTOR, opp);
        final Play p = play("ballistaShootsThroughAPunisherWithinOurLife", g -> false);
        assertOpponentLostByLife(p, ai, OUR_TURN);
        assertEquals(ai.getLife(), LIFE - 38, "our life after nine pumps and ten pings at 2 each: " + p);
    }

    // R30, a cycle that costs life, toward a kill this turn that our life affords: the rock loop under the opponent's
    // Harsh Mentor (2 for each untap), Ballista in our hand, the opponent at 2, we at 40. The rest of the volley, the
    // cycles for its cast at X of 2 and two pings, is priced at 14 (an upper bound) against our 40, so the rock line
    // runs: four untaps build the pool of 4, Ballista is cast with two counters and pings twice, and the opponent loses
    // in our Main 1, our life at 28 (four untaps and two pings at 2 each).
    @Test
    public void aCheapKillPaysTheRockLinesUntaps() {
        start();
        ai.setLife(LIFE, null);
        opp.setLife(2, null);
        rock();
        ballistaInHand();
        addCard(HARSH_MENTOR, opp);
        final Play p = play("aCheapKillPaysTheRockLinesUntaps", g -> false);
        assertOpponentLostByLife(p, ai, OUR_TURN);
        assertEquals(ai.getLife(), LIFE - 12, "our life after four untaps and two pings at 2 each: " + p);
    }

    // R30, a cost no price reads: Yavimaya Coast's blue deals 1 to us, and the flicker cycle taps it for blue
    // (Palinchron and Deadeye Navigator paired, six Islands and Yavimaya Coast, Ballista with one counter, both at 40).
    // The first cycle's tap of it costs 1, which the driver measures at its next decision: the line fails for the
    // phase, Palinchron is never flickered, and our Main 1 ends with our life at 39, where the loop used to pay 1 a
    // cycle for the rest of the volley.
    @Test
    public void aLifeCostNoPriceReadsEndsTheLine() {
        start();
        ai.setLife(LIFE, null);
        paired();
        lands(ISLAND, 6);
        addCard(YAVIMAYA_COAST, ai);
        ballista(1);
        final Play p = play("aLifeCostNoPriceReadsEndsTheLine", g -> !g.getPhaseHandler().is(PhaseType.MAIN1));
        assertFalse(game.isGameOver(), "the game ended: " + outcome() + "; " + p);
        assertEquals(ai.getLife(), LIFE - 1, "the painland's one tap: " + p);
        assertEquals(p.count(PALINCHRON + " ability"), 0, "Palinchron flickered: " + p);
        assertTrue(p.actions() > 0, "the driver never acted: " + p);
    }

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
    // holding the flicker line nothing is flickered. Palinchron's own {2}{U}{U} return to our hand does save it, and
    // once made cannot be made again, so it stays the stock's (the final-fix compliance review's round 1): Palinchron
    // is returned to our hand, Wrath destroys Deadeye Navigator, and our next turn comes within the bound.
    @Test
    public void aWrathDrawsNoFlicker() {
        start();
        paired();
        lands(ISLAND, 7);
        theirTurn();
        ComboLoopBoards.theirSpellOn(game, opp, WRATH);
        final Play p = play("aWrathDrawsNoFlicker", g -> g.getPhaseHandler().getTurn() > OUR_TURN + 1);
        assertEquals(game.getPhaseHandler().getTurn(), OUR_TURN + 2, "our next turn never came: " + p);
        assertEquals(p.moved(PALINCHRON, ZoneType.Exile), 0, "Palinchron's flickers: " + p);
        assertNull(onOurBattlefield(DEADEYE), "Wrath of God did not resolve: " + p);
        assertEquals(p.moved(PALINCHRON, ZoneType.Hand), 1, "Palinchron's returns to our hand: " + p);
        assertNotNull(ourCardIn(ZoneType.Hand, PALINCHRON), "Palinchron is not in our hand: " + p);
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
        assertFalse(refuses(List.of(aimed)), "Murder aims at Palinchron as it is now");
        start();
        final Card palinchron = paired();
        theirTurn();
        ComboLoopBoards.theirSpellOn(game, opp, WRATH);
        assertTrue(refuses(List.of(palinchron)), "nothing targets Palinchron");
        t.useLines(List.of());
        assertFalse(refuses(List.of(palinchron)), "a deck holding no flicker line");
    }

    // Finding 4, the anti-goal "decking itself" with Jin-Gitaxias, Core Augur on ours: thrasiosNeverDecksUs's board
    // beside Jin, whose trigger draws us seven at each of our end steps, whatever our library holds, with fifty cards.
    // The plain dig keeps our next four draw steps and every card Jin draws us before the last of them, four and Jin's
    // seven four times, 32 (amended by the final-fix compliance review's round 1), and Jin's draw leaves 25: the
    // opponent's turn comes and the game goes on, where the dig used to stop at four and Jin's draw decked us, and
    // then, at eleven, Jin's draw left four, one turn of draws.
    @Test
    public void jinNeverDecksUs() {
        board(KINNAN, KINNANS_LIST);
        fillLibrary(opp, 40);
        rock();
        ComboLoopBoards.ready(addCard(THRASIOS, ai));
        ComboLoopBoards.ready(addCard(JIN, ai));
        for (int i = 0; i < 25; i++) {
            addCardToZone(MEMNITE, ai, ZoneType.Library);
            addCardToZone(FOREST, ai, ZoneType.Library);
        }
        final Play p = play("jinNeverDecksUs", g -> g.getPhaseHandler().getPlayerTurn() == opp);
        final int library = ai.getCardsIn(ZoneType.Library).size();
        assertFalse(game.isGameOver(), "the game ended: " + outcome() + "; " + p);
        assertSame(game.getPhaseHandler().getPlayerTurn(), opp, "the opponent's turn never came: " + p);
        assertTrue(library >= 25, "our library after Jin's draw: " + library + "; " + p);
        assertTrue(p.count(THRASIOS + " ability") > 0, "Thrasios never dug: " + p);
    }

    // ---- the final review's round 1: what its mutation pass found untested ----

    // The blink refusal (LoopDriver.refusesBlink) reads an opponent's items alone (LoopResponse.targetsNow): our own
    // Murder on the stack, aimed at Palinchron, is no opponent's removal, and a flicker to save Palinchron from it is
    // refused.
    @Test
    public void onlyAnOpponentsItemWarrantsABlink() {
        start();
        final Card palinchron = paired();
        ComboLoopBoards.theirSpellOn(game, ai, MURDER, palinchron);
        assertTrue(refuses(List.of(palinchron)), "our own Murder is no opponent's item");
    }

    // It reads every item on the stack, not the top one alone: their Murder aimed at Palinchron beneath their Wrath of
    // God, and, in a second game, above it.
    @Test
    public void anOpponentsItemBeneathOthersWarrantsABlink() {
        start();
        final Card palinchron = paired();
        theirTurn();
        ComboLoopBoards.theirSpellOn(game, opp, MURDER, palinchron);
        ComboLoopBoards.theirResponse(game, opp, WRATH);
        assertEquals(game.getStack().size(), 2, "Murder beneath Wrath of God");
        assertFalse(refuses(List.of(palinchron)), "Murder beneath Wrath of God");
        start();
        final Card again = paired();
        theirTurn();
        ComboLoopBoards.theirSpellOn(game, opp, WRATH);
        ComboLoopBoards.theirResponse(game, opp, MURDER, again);
        assertEquals(game.getStack().size(), 2, "Murder above Wrath of God");
        assertFalse(refuses(List.of(again)), "Murder above Wrath of God");
    }

    // It reads every part of a spell: their Severed Strands, a gain of life that targets nothing and then a destroy,
    // the destroy aimed at Palinchron.
    @Test
    public void aTargetInASecondPartWarrantsABlink() {
        start();
        final Card palinchron = paired();
        theirTurn();
        ComboLoopBoards.theirSpellOn(game, opp, "Severed Strands", palinchron);
        final SpellAbility root = game.getStack().peek().getSpellAbility();
        assertFalse(root.usesTargeting(), "the gain of life targets nothing");
        assertTrue(root.getSubAbility().usesTargeting(), "the destroy targets");
        assertFalse(refuses(List.of(palinchron)), "Severed Strands's destroy aims at Palinchron");
    }

    // It reads a trigger's ability: Flametongue Kavu's 4 damage aimed at Palinchron, wrapped as the trigger handler
    // wraps a mandatory trigger. A pin: the wrapper hands on its targets and its parts, so the reading needs no
    // unwrapping to see them.
    @Test
    public void aTriggeredAbilityThatTargetsWarrantsABlink() {
        start();
        final Card palinchron = paired();
        theirTurn();
        final Card ftk = addCard("Flametongue Kavu", opp);
        final Trigger trigger = ftk.getTriggers().get(0);
        final SpellAbility inner = AbilityFactory.getAbility(ftk, trigger.getParam("Execute"));
        inner.setActivatingPlayer(opp);
        inner.setTrigger(trigger);
        inner.getTargets().add(palinchron);
        game.getStack().add(new WrappedAbility(trigger, inner, null));
        assertTrue(game.getStack().peek().getSpellAbility().isWrapper(), "a triggered ability");
        assertFalse(refuses(List.of(palinchron)), "the trigger's damage aims at Palinchron");
    }

    // It reads a target as the engine reads one at resolution: their Murder aimed at Palinchron, which Shalai, Voice of
    // Plenty now gives hexproof, would fizzle, and a flicker saves Palinchron from nothing.
    @Test
    public void aTargetNoLongerTargetableWarrantsNoBlink() {
        start();
        final Card palinchron = paired();
        addCard("Shalai, Voice of Plenty", ai);
        game.getAction().checkStaticAbilities();
        theirTurn();
        ComboLoopBoards.theirSpellOn(game, opp, MURDER, palinchron);
        assertTrue(refuses(List.of(palinchron)), "Murder can no longer target Palinchron");
    }

    // R30's affordability is strict: a volley that costs exactly our life is not taken. Under the opponent's Harsh
    // Mentor, Ballista (one counter) fed by x-2's cycles, which cost no life, needs nine pumps and ten pings against
    // the opponent's 10: 38 life, all of ours.
    @Test
    public void aVolleyThatCostsExactlyOurLifeIsNotTaken() {
        start();
        ai.setLife(38, null);
        opp.setLife(10, null);
        bounce();
        ballista(1);
        addCard(HARSH_MENTOR, opp);
        final Play p = play("aVolleyThatCostsExactlyOurLifeIsNotTaken", g -> !g.getPhaseHandler().is(PhaseType.MAIN1));
        assertFalse(game.isGameOver(), "the game ended: " + outcome() + "; " + p);
        assertEquals(ai.getLife(), 38, "Ballista paid life: " + p);
        assertEquals(p.count(BALLISTA + " ability"), 0, "Ballista acted: " + p);
    }

    // The same with one life more: the volley is taken and wins in our Main 1, and leaves us at 1.
    @Test
    public void aVolleyThatCostsOneLessThanOurLifeIsTaken() {
        start();
        ai.setLife(39, null);
        opp.setLife(10, null);
        bounce();
        ballista(1);
        addCard(HARSH_MENTOR, opp);
        final Play p = play("aVolleyThatCostsOneLessThanOurLifeIsTaken", g -> false);
        assertOpponentLostByLife(p, ai, OUR_TURN);
        assertEquals(ai.getLife(), 1, "our life after nine pumps and ten pings at 2 each: " + p);
    }

    // R30 prices a cycle by all its priced steps: x-2's cast and bounce under the opponent's Eidolon of the Great Revel
    // (2 damage to a player for each spell of mana value 3 or less they cast) casts Mana Vault and Sol Ring, 2 each, 4
    // a cycle, where Ballista's pump and ping, abilities, cost nothing. Ballista (one counter) against the opponent's 2
    // needs one pump, {4}: two cycles of net 3, 8 life. The budget, three cycles priced (12), is less than our 13, so
    // the volley is taken and wins in our Main 1, with 5 life left.
    @Test
    public void aCheapKillPaysTheBounceLinesCasts() {
        start();
        ai.setLife(13, null);
        opp.setLife(2, null);
        bounce();
        ballista(1);
        addCard(EIDOLON, opp);
        final Play p = play("aCheapKillPaysTheBounceLinesCasts", g -> false);
        assertOpponentLostByLife(p, ai, OUR_TURN);
        assertEquals(ai.getLife(), 5, "our life after four casts at 2 each: " + p);
    }

    // The same at our 8, the two cycles' cost exactly: the budget (12) is not less than our life, and the driver takes
    // no step.
    @Test
    public void aKillTheCastsWouldCostAllOurLifeIsNotTaken() {
        start();
        ai.setLife(8, null);
        opp.setLife(2, null);
        bounce();
        ballista(1);
        addCard(EIDOLON, opp);
        final Play p = play("aKillTheCastsWouldCostAllOurLifeIsNotTaken",
                g -> !g.getPhaseHandler().is(PhaseType.MAIN1));
        assertFalse(game.isGameOver(), "the game ended: " + outcome() + "; " + p);
        assertEquals(p.actions(), 0, "the driver acted: " + p);
    }

    // ---- the final review's round 1: what its mutation pass's round 2 found untested ----

    // The blink refusal reads every card of the retrieval and warrants the blink when an opponent's item targets any
    // one of them, as the stock reading of the threat does: their Murder aimed at Palinchron warrants a blink of
    // Palinchron and Deadeye Navigator together, listed either way round, and none of Deadeye Navigator alone.
    @Test
    public void aBlinkOfSeveralCardsIsWarrantedByAnyOneTheyTarget() {
        start();
        final Card palinchron = paired();
        final Card deadeye = palinchron.getPairedWith();
        theirTurn();
        ComboLoopBoards.theirSpellOn(game, opp, MURDER, palinchron);
        assertFalse(refuses(List.of(palinchron, deadeye)), "Murder aims at the first of them");
        assertFalse(refuses(List.of(deadeye, palinchron)), "Murder aims at the second of them");
        assertTrue(refuses(List.of(deadeye)), "Murder aims at none of these");
    }

    // It reads every target of a part: their Curtains' Call (destroy two target creatures) aimed at Deadeye Navigator
    // and then at Palinchron warrants a blink of Palinchron, its second target.
    @Test
    public void aSecondTargetOfAPartWarrantsABlink() {
        start();
        final Card palinchron = paired();
        final Card deadeye = palinchron.getPairedWith();
        theirTurn();
        ComboLoopBoards.theirSpellOn(game, opp, "Curtains' Call", deadeye, palinchron);
        final SpellAbility destroy = game.getStack().peek().getSpellAbility();
        assertEquals(destroy.getTargets().size(), 2, "one part, two targets");
        assertSame(destroy.getTargets().get(1), palinchron, "Palinchron the second");
        assertFalse(refuses(List.of(palinchron)), "Curtains' Call's second target is Palinchron");
    }

    // LoopDriver.refusesBlink for Deadeye Navigator's flicker of the retrieval's first card, a save that returns it to
    // the battlefield at once
    private boolean refuses(final List<Card> retrieval) {
        return refuses(flickerOf(retrieval.get(0)), retrieval);
    }

    // LoopDriver.refusesBlink(ai, sa, retrieval), which draws no random number (spec section 7): every reading of the
    // stack the tests make is bracketed, an opponent's spell, a triggered ability, a part after the first and an
    // item of ours among them
    private boolean refuses(final SpellAbility sa, final List<Card> retrieval) {
        final long before = MyRandom.callCount();
        final boolean refused = LoopDriver.refusesBlink(ai, sa, retrieval);
        assertEquals(MyRandom.callCount() - before, 0, "the blink refusal drew");
        return refused;
    }

    // The hook in ChangeZoneAi (LoopDriver.refusesBlink), through the AI's own reading of Palinchron's flicker, Deadeye
    // Navigator's grant, draws no random number and leaves a deck holding no line the stock's answer. Their Murder
    // aimed at Palinchron: a yes for either deck. Palinchron flickered once (Murder's target gone, the new
    // Palinchron paired again): the stock reads card ids, which a flicker keeps, and says yes again, which the deck
    // holding the flicker line is refused (spec section 9). Nothing is drawn in any of the four.
    @Test
    public void theHookInTheStockBlinkReadingDrawsNothingAndKeepsTheStocksYesForADeckHoldingNoLine() {
        start();
        final Card palinchron = paired();
        final Card deadeye = palinchron.getPairedWith();
        theirTurn();
        ComboLoopBoards.theirSpellOn(game, opp, MURDER, palinchron);
        assertEquals(weighs(flickerOf(palinchron)), AiPlayDecision.WillPlay, "Murder aims at Palinchron");
        t.useLines(List.of());
        assertEquals(weighs(flickerOf(palinchron)), AiPlayDecision.WillPlay, "the stock: a deck holding no line");
        t.useLines(ComboLines.shipped());
        final Card back = game.getAction().moveToPlay(game.getAction().exile(palinchron, null, null), null, null);
        back.setPairedWith(deadeye);
        deadeye.setPairedWith(back);
        game.getAction().checkStaticAbilities();
        assertEquals(weighs(flickerOf(back)), AiPlayDecision.CantPlayAi, "Murder's target is gone: no second flicker");
        t.useLines(List.of());
        assertEquals(weighs(flickerOf(back)), AiPlayDecision.WillPlay, "the stock reads card ids: yes again");
    }

    // the AI's reading of that ability (AiController.canPlaySa), which draws no random number here
    private AiPlayDecision weighs(final SpellAbility sa) {
        sa.setActivatingPlayer(ai);
        final long before = MyRandom.callCount();
        final AiPlayDecision decision = ComboLoopBoards.aiOf(ai).canPlaySa(sa);
        assertEquals(MyRandom.callCount() - before, 0, "the stock blink reading drew");
        return decision;
    }

    // the flicker Deadeye Navigator grants the card: its activated ChangeZone to exile that a static grants
    private static SpellAbility flickerOf(final Card c) {
        for (final SpellAbility sa : c.getNonManaAbilities()) {
            if (sa.isActivatedAbility() && sa.getApi() == ApiType.ChangeZone && sa.getGrantorStatic() != null
                    && "Exile".equals(sa.getParam("Destination"))) {
                return sa;
            }
        }
        throw new AssertionError(c + " has no flicker");
    }

    // a save of c's own built from its text as a script writes one: an activated move of c from the battlefield to
    // that zone, remembering it, then those parts in their order (at least one), each a sub-ability's text
    private static SpellAbility saveOf(final Card c, final String to, final String... parts) {
        for (int i = 0; i < parts.length; i++) {
            c.setSVar("TestPart" + i, parts[i] + (i + 1 < parts.length ? " | SubAbility$ TestPart" + (i + 1) : ""));
        }
        return AbilityFactory.getAbility("AB$ ChangeZone | Cost$ 1 | Defined$ Self | Origin$ Battlefield"
                + " | Destination$ " + to + " | RememberChanged$ True | SubAbility$ TestPart0", c);
    }

    // a part after a save that moves the card the save moved (Remembered) on to that zone
    private static String moveOn(final String zone) {
        return "DB$ ChangeZone | Defined$ Remembered | Origin$ All | Destination$ " + zone;
    }

    // the card's own exile of itself: its activated ChangeZone to exile that no static grants (Nezahal, Primal Tide's,
    // whose delayed trigger returns it at the beginning of the next end step)
    private static SpellAbility exileOf(final Card c) {
        for (final SpellAbility sa : c.getNonManaAbilities()) {
            if (sa.isActivatedAbility() && sa.getApi() == ApiType.ChangeZone && sa.getGrantorStatic() == null
                    && "Exile".equals(sa.getParam("Destination"))) {
                return sa;
            }
        }
        throw new AssertionError(c + " has no exile of its own");
    }

    // the card's own return to our hand: its activated ChangeZone to the hand that no static grants (Palinchron's
    // {2}{U}{U})
    private static SpellAbility bounceOf(final Card c) {
        for (final SpellAbility sa : c.getNonManaAbilities()) {
            if (sa.isActivatedAbility() && sa.getApi() == ApiType.ChangeZone && sa.getGrantorStatic() == null
                    && "Hand".equals(sa.getParam("Destination"))) {
                return sa;
            }
        }
        throw new AssertionError(c + " has no return to hand");
    }

    // ---- the final review's round 1: what its mutation pass's round 3 found untested ----

    // The blink refusal draws no random number, for a deck holding the flicker line or not (section 7: where the stock
    // path drew none a new path draws none, and a deck holding no line makes the stock's draws): their Murder aimed at
    // Palinchron, the refusal asked for it and for Deadeye Navigator, which nothing aims at, with the deck's lines and
    // then with the tracker holding none.
    @Test
    public void theBlinkRefusalDrawsNoRandomNumber() {
        start();
        final Card palinchron = paired();
        final Card deadeye = palinchron.getPairedWith();
        theirTurn();
        ComboLoopBoards.theirSpellOn(game, opp, MURDER, palinchron);
        final long before = MyRandom.callCount();
        assertFalse(refuses(List.of(palinchron)), "Murder aims at Palinchron");
        assertTrue(refuses(List.of(deadeye)), "nothing aims at Deadeye Navigator");
        assertEquals(MyRandom.callCount() - before, 0, "the refusal drew, a deck holding the flicker line");
        t.useLines(List.of());
        assertFalse(refuses(List.of(palinchron)), "a deck holding no flicker line");
        assertEquals(MyRandom.callCount() - before, 0, "the refusal drew, a deck holding no line");
    }

    // ---- the final-fix compliance review's round 1 ----

    // Its finding 1: the opponent's Wrath of God, which targets nothing, against Palinchron unpaired, four Islands for
    // its {2}{U}{U}, no Ballista. Palinchron's own return to our hand saves it, a card in our hand being on no
    // battlefield when Wrath resolves, and once made it cannot be made again, so for a deck holding the flicker line it
    // stays the stock's: Palinchron is returned to our hand once and our next turn comes within the bound, where the
    // blink refusal let Wrath destroy it.
    @Test
    public void aWrathLeavesPalinchronsReturnToHandToTheStock() {
        start();
        ComboLoopBoards.ready(addCard(PALINCHRON, ai));
        lands(ISLAND, 4);
        theirTurn();
        ComboLoopBoards.theirSpellOn(game, opp, WRATH);
        final Play p = play("aWrathLeavesPalinchronsReturnToHandToTheStock",
                g -> g.getPhaseHandler().getTurn() > OUR_TURN + 1);
        assertEquals(game.getPhaseHandler().getTurn(), OUR_TURN + 2, "our next turn never came: " + p);
        assertEquals(p.moved(PALINCHRON, ZoneType.Hand), 1, "Palinchron's returns to our hand: " + p);
        assertNotNull(ourCardIn(ZoneType.Hand, PALINCHRON), "Wrath of God destroyed Palinchron: " + p);
    }

    // The hook in ChangeZoneAi (LoopDriver.refusesBlink) through the AI's own reading of Palinchron's return to our
    // hand. Against the opponent's Wrath of God the return saves Palinchron: the stock's yes, for the deck holding the
    // flicker line as for a deck holding no line. Against their Murder aimed at Palinchron, which has left the
    // battlefield and come back since (Murder's target is gone, and it will fizzle), the return saves it from nothing:
    // the deck holding the flicker line is refused it, where the stock reading, which compares card ids, says yes.
    // Nothing is drawn in any of the four.
    @Test
    public void theHookKeepsTheStocksReturnToHandFromARemovalThatTargetsNothing() {
        start();
        final Card palinchron = ComboLoopBoards.ready(addCard(PALINCHRON, ai));
        theirTurn();
        ComboLoopBoards.theirSpellOn(game, opp, WRATH);
        assertEquals(weighs(bounceOf(palinchron)), AiPlayDecision.WillPlay, "Wrath, the flicker line's deck");
        t.useLines(List.of());
        assertEquals(weighs(bounceOf(palinchron)), AiPlayDecision.WillPlay, "Wrath, a deck holding no line");
        start();
        final Card aimed = ComboLoopBoards.ready(addCard(PALINCHRON, ai));
        theirTurn();
        ComboLoopBoards.theirSpellOn(game, opp, MURDER, aimed);
        final Card back = game.getAction().moveToPlay(game.getAction().exile(aimed, null, null), null, null);
        game.getAction().checkStaticAbilities();
        assertEquals(weighs(bounceOf(back)), AiPlayDecision.CantPlayAi, "Murder's target is gone: no return");
        t.useLines(List.of());
        assertEquals(weighs(bounceOf(back)), AiPlayDecision.WillPlay, "the stock reads card ids: yes");
    }

    // The blink refusal's reading of the save (LoopDriver.refusesBlink). Against the opponent's Wrath of God, which
    // targets nothing, Deadeye Navigator's flicker of Palinchron, which returns it to the battlefield at once, saves it
    // from nothing and is refused, while Palinchron's own return to our hand and Nezahal, Primal Tide's exile until
    // the next end step do save the card and are not. Of saves written as scripts write them, an exile whose part
    // after it puts the card into our hand saves it; a return to our hand whose part after it puts the card back onto
    // the battlefield does not, and is refused, as are an exile whose third part does and one whose part after it puts
    // every card it exiled onto the battlefield (a ChangeZoneAll). Their Murder above Wrath, aimed
    // at Deadeye Navigator, names Palinchron as no target, and Palinchron's return is still not refused. For a deck
    // holding no flicker line nothing is refused. With their Murder aimed at Palinchron as it is now, its return is not
    // refused; once Palinchron has left the battlefield and come back (Murder's target gone), its return is refused,
    // Murder still naming the card it was.
    @Test
    public void theBlinkRefusalRefusesOnlyAFlickerFromARemovalThatTargetsNothing() {
        start();
        final Card palinchron = paired();
        final Card nezahal = ComboLoopBoards.ready(addCard(NEZAHAL, ai));
        theirTurn();
        ComboLoopBoards.theirSpellOn(game, opp, WRATH);
        assertTrue(refuses(flickerOf(palinchron), List.of(palinchron)), "a flicker from Wrath of God");
        assertFalse(refuses(bounceOf(palinchron), List.of(palinchron)), "a return to our hand from Wrath of God");
        assertFalse(refuses(exileOf(nezahal), List.of(nezahal)), "an exile until the end step from Wrath of God");
        assertFalse(refuses(saveOf(palinchron, "Exile", moveOn("Hand")), List.of(palinchron)),
                "an exile, then to our hand");
        assertTrue(refuses(saveOf(palinchron, "Hand", moveOn("Battlefield")), List.of(palinchron)),
                "a return to our hand, then onto the battlefield at once");
        assertTrue(refuses(saveOf(palinchron, "Exile", "DB$ Pump | Defined$ Remembered | NumAtt$ +1",
                moveOn("Battlefield")), List.of(palinchron)), "an exile, a pump, then onto the battlefield at once");
        assertTrue(refuses(saveOf(palinchron, "Exile", "DB$ ChangeZoneAll | ChangeType$ Card.IsRemembered"
                + " | Origin$ Exile | Destination$ Battlefield"), List.of(palinchron)),
                "an exile, then all it exiled onto the battlefield at once");
        ComboLoopBoards.theirResponse(game, opp, MURDER, palinchron.getPairedWith());
        assertFalse(refuses(bounceOf(palinchron), List.of(palinchron)), "Murder names Deadeye, not Palinchron");
        t.useLines(List.of());
        assertFalse(refuses(flickerOf(palinchron), List.of(palinchron)), "a deck holding no flicker line");
        start();
        final Card aimed = ComboLoopBoards.ready(addCard(PALINCHRON, ai));
        theirTurn();
        ComboLoopBoards.theirSpellOn(game, opp, MURDER, aimed);
        assertFalse(refuses(bounceOf(aimed), List.of(aimed)), "Murder aims at Palinchron as it is now");
        final Card back = game.getAction().moveToPlay(game.getAction().exile(aimed, null, null), null, null);
        assertTrue(refuses(bounceOf(back), List.of(back)), "Murder aims at the card Palinchron was");
    }

    // Whose item and which removal: our own Murder aimed at Palinchron as it is now is no opponent's, and a return of
    // Palinchron to our hand is refused while it names the card; and beside the opponent's Wrath of God, their Murder
    // aimed at the card Palinchron was refuses the return too, a cost spec section 9 names (the return would have
    // saved Palinchron from Wrath). A part that names Palinchron after one that targets nothing counts, and a trigger's
    // wrapped ability: their Severed Strands, and Flametongue Kavu's trigger, each aimed at the card it was.
    @Test
    public void aRemovalWhoseTargetIsGoneOrOursRefusesTheReturnToHand() {
        start();
        final Card palinchron = ComboLoopBoards.ready(addCard(PALINCHRON, ai));
        ComboLoopBoards.theirSpellOn(game, ai, MURDER, palinchron);
        assertTrue(refuses(bounceOf(palinchron), List.of(palinchron)), "our own Murder names Palinchron");
        start();
        final Card aimed = ComboLoopBoards.ready(addCard(PALINCHRON, ai));
        theirTurn();
        ComboLoopBoards.theirSpellOn(game, opp, MURDER, aimed);
        final Card back = game.getAction().moveToPlay(game.getAction().exile(aimed, null, null), null, null);
        ComboLoopBoards.theirResponse(game, opp, WRATH);
        assertTrue(refuses(bounceOf(back), List.of(back)), "Murder names the card it was, beneath Wrath of God");
        start();
        final Card struck = ComboLoopBoards.ready(addCard(PALINCHRON, ai));
        theirTurn();
        ComboLoopBoards.theirSpellOn(game, opp, "Severed Strands", struck);
        final Card again = game.getAction().moveToPlay(game.getAction().exile(struck, null, null), null, null);
        assertFalse(game.getStack().peek().getSpellAbility().usesTargeting(), "the gain of life targets nothing");
        assertTrue(refuses(bounceOf(again), List.of(again)), "Severed Strands's destroy names the card it was");
        start();
        final Card burnt = ComboLoopBoards.ready(addCard(PALINCHRON, ai));
        theirTurn();
        final Card ftk = addCard("Flametongue Kavu", opp);
        final Trigger trigger = ftk.getTriggers().get(0);
        final SpellAbility inner = AbilityFactory.getAbility(ftk, trigger.getParam("Execute"));
        inner.setActivatingPlayer(opp);
        inner.setTrigger(trigger);
        inner.getTargets().add(burnt);
        game.getStack().add(new WrappedAbility(trigger, inner, null));
        final Card anew = game.getAction().moveToPlay(game.getAction().exile(burnt, null, null), null, null);
        assertTrue(refuses(bounceOf(anew), List.of(anew)), "the trigger's damage names the card it was");
    }

    // Its finding 2, spec section 4's anti-goal "decking itself" beside Jin-Gitaxias, Core Augur: round 2's board (a),
    // Jin on ours, Kinnan, Basalt and Thrasios, eighteen Islands above four Memnites (worth a dig, no win), the
    // opponent at 1,000 so that no attack of ours ends the game. A plain dig keeps our next four draw steps and every
    // card Jin draws us before the last of them, 32, so with 22 cards it does not run, and the game goes on past our
    // turn 5, where the dig took the library to eleven and Jin's sevens milled us at our turn 5's end step.
    @Test
    public void besideJinADigThatFindsNoWinLeavesUsFourTurns() {
        board(KINNAN, KINNANS_LIST);
        fillLibrary(opp, 40);
        opp.setLife(1_000, null);
        rock();
        ComboLoopBoards.ready(addCard(THRASIOS, ai));
        ComboLoopBoards.ready(addCard(JIN, ai));
        for (int i = 0; i < 18; i++) {
            addCardToZone(ISLAND, ai, ZoneType.Library);
        }
        for (int i = 0; i < 4; i++) {
            addCardToZone(MEMNITE, ai, ZoneType.Library);
        }
        final Play p = play("besideJinADigThatFindsNoWinLeavesUsFourTurns",
                g -> g.getPhaseHandler().getTurn() > OUR_TURN + 2);
        assertFalse(game.isGameOver(), "the game ended: " + outcome() + "; " + p);
        assertEquals(game.getPhaseHandler().getTurn(), OUR_TURN + 3, "the opponent's next turn never came: " + p);
    }

    // The same anti-goal with Jin in our hand (round 2's board (c), the skeptic's): Kinnan, Basalt and Thrasios on
    // ours, two Islands, Jin-Gitaxias in our hand, a library of 25 Memnites and 25 Forests in turn, the opponent at
    // 1,000. The plain dig keeps what Jin would draw us were it on our battlefield, since the go-off's pool can cast
    // it: it stops at 32, and the game goes on past our turn 5, where the dig ran below what Jin's arrival needed and
    // Jin's sevens milled us by our turn 5's end step.
    @Test
    public void jinInOurHandRaisesThePlainDigsFloor() {
        board(KINNAN, KINNANS_LIST);
        fillLibrary(opp, 40);
        opp.setLife(1_000, null);
        rock();
        ComboLoopBoards.ready(addCard(THRASIOS, ai));
        lands(ISLAND, 2);
        addCardToZone(JIN, ai, ZoneType.Hand);
        for (int i = 0; i < 25; i++) {
            addCardToZone(MEMNITE, ai, ZoneType.Library);
            addCardToZone(FOREST, ai, ZoneType.Library);
        }
        final Play p = play("jinInOurHandRaisesThePlainDigsFloor", g -> g.getPhaseHandler().getTurn() > OUR_TURN + 2);
        assertFalse(game.isGameOver(), "the game ended: " + outcome() + "; " + p);
        assertEquals(game.getPhaseHandler().getTurn(), OUR_TURN + 3, "the opponent's next turn never came: " + p);
        assertTrue(p.count(THRASIOS + " ability") > 0, "Thrasios never dug: " + p);
    }

    // And with Jin in our library near its bottom (round 2's board (b)): Kinnan, Basalt and Thrasios on ours, two
    // Islands, a library of 25 Memnites and 25 Forests in turn with Jin-Gitaxias the 46th of its 51 cards, the opponent
    // at 1,000. The plain dig keeps what Jin would draw us were it on our battlefield, since the dig may draw it and
    // the go-off's pool cast it: it stops at 32, above Jin, and the game goes on past our turn 5, where the dig ran at
    // the floor of four, drew Jin near the bottom, the stock walk cast it, and its seven decked us.
    @Test
    public void jinInOurLibraryRaisesThePlainDigsFloor() {
        board(KINNAN, KINNANS_LIST);
        fillLibrary(opp, 40);
        opp.setLife(1_000, null);
        rock();
        ComboLoopBoards.ready(addCard(THRASIOS, ai));
        lands(ISLAND, 2);
        for (int i = 0; i < 50; i++) {
            if (i == 45) {
                addCardToZone(JIN, ai, ZoneType.Library);
            }
            addCardToZone(i % 2 == 0 ? MEMNITE : FOREST, ai, ZoneType.Library);
        }
        final Play p = play("jinInOurLibraryRaisesThePlainDigsFloor",
                g -> g.getPhaseHandler().getTurn() > OUR_TURN + 2);
        assertFalse(game.isGameOver(), "the game ended: " + outcome() + "; " + p);
        assertEquals(game.getPhaseHandler().getTurn(), OUR_TURN + 3, "the opponent's next turn never came: " + p);
        assertTrue(p.count(THRASIOS + " ability") > 0, "Thrasios never dug: " + p);
    }

    // ---- the final-fix compliance review's follow-up round 1 ----

    // R30 in response to removal (sections 6.5 and 6.6, amended): theResponseWinsOnTheirTurn's board under the
    // opponent's Harsh Mentor, which deals 2 to us for each ability of an artifact, creature or land we activate that
    // is no mana ability: the rock and Ballista with one counter on ours, the opponent at 3, we at 40, their Murder on
    // Kinnan. The rock line's volley, its cycles, two pumps and three pings at 2 each, is priced at 28, less than our
    // 40, so the response goes off, and each of its steps waits beneath Harsh Mentor's trigger for it until both have
    // resolved: no item of ours is ever on the stack beside another, and the opponent loses in their Main 1, Kinnan on
    // our battlefield and our life at 14 (eight untaps, two pumps and three pings). The response used to act above each
    // trigger on a board where nothing pending had landed: an untap of a Basalt whose untap was still on the stack,
    // both charged to one untap's price, so that the line failed; Murder resolved, Kinnan died and the opponent lived.
    @Test
    public void aResponseUnderAPunisherTakesOneStepAtATime() {
        start();
        ai.setLife(LIFE, null);
        opp.setLife(3, null);
        final Card kinnan = rock();
        ballista(1);
        addCard(HARSH_MENTOR, opp);
        theirTurn();
        ComboLoopBoards.theirSpellOn(game, opp, MURDER, kinnan);
        final Play p = play("aResponseUnderAPunisherTakesOneStepAtATime",
                g -> g.getPhaseHandler().getTurn() > OUR_TURN + 1);
        assertEquals(p.deepest(), 1, "items of ours on the stack at once: " + p);
        assertOpponentLostByLife(p, opp, OUR_TURN + 1);
        assertTrue(game.getPhaseHandler().is(PhaseType.MAIN1, opp), "not in their Main 1: " + outcome());
        assertSame(onOurBattlefield(KINNAN), kinnan, "Murder resolved: Kinnan left our battlefield");
        assertEquals(p.acted(BASALT, ApiType.Untap), 8, "Basalt's untaps: " + p);
        assertEquals(p.acted(BALLISTA, ApiType.PutCounter), 2, "Ballista's pumps: " + p);
        assertEquals(p.acted(BALLISTA, ApiType.DealDamage), 3, "Ballista's pings: " + p);
        assertEquals(ai.getLife(), LIFE - 26, "our life after eight untaps, two pumps and three pings at 2 each: " + p);
    }

    // The same with ten untapped Forests of ours beside the rock, which could pay for another untap: still one step
    // at a time, and the same win, our life at 14. The response used to put four untaps of the one Basalt on the stack
    // at once, the Forests paying for those the pool could not, and ended as the board without them did.
    @Test
    public void aResponseUnderAPunisherTakesOneStepAtATimeBesideLands() {
        start();
        ai.setLife(LIFE, null);
        opp.setLife(3, null);
        final Card kinnan = rock();
        ballista(1);
        lands(FOREST, 10);
        addCard(HARSH_MENTOR, opp);
        theirTurn();
        ComboLoopBoards.theirSpellOn(game, opp, MURDER, kinnan);
        final Play p = play("aResponseUnderAPunisherTakesOneStepAtATimeBesideLands",
                g -> g.getPhaseHandler().getTurn() > OUR_TURN + 1);
        assertEquals(p.deepest(), 1, "items of ours on the stack at once: " + p);
        assertOpponentLostByLife(p, opp, OUR_TURN + 1);
        assertSame(onOurBattlefield(KINNAN), kinnan, "Murder resolved: Kinnan left our battlefield");
        assertEquals(p.acted(BASALT, ApiType.Untap), 8, "Basalt's untaps: " + p);
        assertEquals(ai.getLife(), LIFE - 26, "our life after eight untaps, two pumps and three pings at 2 each: " + p);
    }

    // R30 in response in our own Main 1: the rock and Ballista with one counter on ours, the opponent at 10 under their
    // Harsh Mentor, we at 40, 200 mana floating in our pool, their Murder on Ballista. The volley, nine pumps and ten
    // pings at 2 each, 38, is less than our 40: the response goes off one step at a time, the AI passing while each
    // waits beneath Harsh Mentor's trigger for it, and the opponent loses in our Main 1, our life at 2. The response
    // used to put 42 pumps and 43 of the triggers on the stack in one priority window, each priced against our 40, and
    // we lost for 0 life, the opponent at 9; and a wait that left that window to the stock AI let it ping Ballista's
    // last counter away while a pump waited, so that Ballista died a 0/0 and the response failed.
    @Test
    public void aResponseInOurMainPhaseUnderAPunisherTakesOneStepAtATime() {
        start();
        ai.setLife(LIFE, null);
        opp.setLife(10, null);
        rock();
        final Card ballista = ballista(1);
        addCard(HARSH_MENTOR, opp);
        floating(200);
        ComboLoopBoards.theirSpellOn(game, opp, MURDER, ballista);
        final Play p = play("aResponseInOurMainPhaseUnderAPunisherTakesOneStepAtATime",
                g -> g.getPhaseHandler().getTurn() > OUR_TURN);
        assertEquals(p.deepest(), 1, "items of ours on the stack at once: " + p);
        assertOpponentLostByLife(p, ai, OUR_TURN);
        assertTrue(game.getPhaseHandler().is(PhaseType.MAIN1, ai), "not in our Main 1: " + outcome());
        assertEquals(p.acted(BALLISTA, ApiType.PutCounter), 9, "Ballista's pumps: " + p);
        assertEquals(p.acted(BALLISTA, ApiType.DealDamage), 10, "Ballista's pings: " + p);
        assertEquals(p.acted(BASALT, ApiType.Untap), 0, "Basalt's untaps, the pool paying for the volley: " + p);
        assertEquals(ai.getLife(), LIFE - 38, "our life after nine pumps and ten pings at 2 each: " + p);
    }

    // Exact lethal in response (section 6.6): their turn, the rock and Ballista with eight counters on ours, the
    // opponent at 3 under their Harsh Mentor, we at 9, their Murder on Ballista. The volley, three pings at 2 each, 6,
    // is less than our 9: three pings, one at a time, and the opponent loses in their Main 1, our life at 3. The
    // response used to put eight pings of ours on the stack at once, six of them the driver's, for a kill of three.
    @Test
    public void aVolleyInResponseReachesExactLethalAndNoFurther() {
        start();
        ai.setLife(9, null);
        opp.setLife(3, null);
        rock();
        final Card ballista = ballista(8);
        addCard(HARSH_MENTOR, opp);
        theirTurn();
        ComboLoopBoards.theirSpellOn(game, opp, MURDER, ballista);
        final Play p = play("aVolleyInResponseReachesExactLethalAndNoFurther",
                g -> g.getPhaseHandler().getTurn() > OUR_TURN + 1);
        assertEquals(p.deepest(), 1, "items of ours on the stack at once: " + p);
        assertOpponentLostByLife(p, opp, OUR_TURN + 1);
        assertEquals(p.acted(BALLISTA, ApiType.DealDamage), 3, "Ballista's pings: " + p);
        assertEquals(ai.getLife(), 3, "our life after three pings at 2 each: " + p);
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
