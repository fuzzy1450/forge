package forge.ai.combo;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import forge.ai.LoopTracker;
import forge.ai.PlayerControllerAi;
import forge.game.Game;
import forge.game.GameObject;
import forge.game.card.Card;
import forge.game.card.CardCollectionView;
import forge.game.card.CardCopyService;
import forge.game.phase.PhaseHandler;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.spellability.SpellAbilityStackInstance;
import forge.game.zone.MagicStack;
import forge.game.zone.ZoneType;

/**
 * One AI player's loop driver (spec 2026-10-29-combo-aware-ai-loops-design.md sections 6.2 to 6.5): it runs the loop
 * lines its deck holds, one action per priority decision, through the AI's own play path, held on the player's
 * AiController, so per seat and per game: nothing here is static but the constants. It reads nothing until its first
 * decision, and is inert, null at once, for a deck holding no loop line.
 *
 * <p>The go-off ({@link #mainPhaseAction}, AiController.getSpellAbilityToPlay's slot after piece 1's go-off): in our
 * main phase on an empty stack, an executable loop line (the tracker's view: ready and not denied, the one signal that
 * stops the driver) whose pieces are where its cycle needs them ({@link LoopCycles#ready}; several: the highest net a
 * cycle, then the variant id) starts a go-off when its kind can stop, some opponent can lose or Walking Ballista can
 * kill the loss blocker that stops one losing, the board is not five times lethal already (unless Ballista can shoot:
 * a kill this turn comes first), and there is something to spend the mana on: Ballista's step, Thrasios's dig,
 * Thassa's Oracle's cast at the end of a dig toward it, or what the stock AI could spend ({@link LoopPool#stockTarget}
 * above 0) while our hand and battlefield are not those of the last go-off that spent nothing (the futility gate).
 * Ballista's step comes first ({@link LoopSinks#ballista}): the cycle's next step ({@link LoopCycles#next}) refills
 * the pool until it holds what Ballista's action needs, a spend taken at a boundary, and the driver owns every
 * decision while Ballista has a step. Then, once a dig toward Oracle (an Oracle able to win now: its cast not barred,
 * its entering trigger not disabled) has brought our library to Oracle's threshold ({@link LoopSinks#oracleThreshold}),
 * Oracle's cast, at any decision the driver's own check passes it; one the check refuses for its price (a tax, the
 * blue that only Chromatic Orrery lets the pool pay) waits, the decision going on as one with no cast due, so that the
 * refill makes the pool that pays it. Otherwise each decision returns the cycle's next step until, at a boundary, the
 * pool holds the target, where the driver passes the decision to the stock AI's walk, which plays on the pool
 * ({@link #noteStockPlay}); when it plays nothing Thrasios, Triton Hero digs ({@link #afterStockWalk},
 * {@link LoopSinks#thrasios}), one activation a decision, the refill going on to its {4} first, its spend taken at a
 * boundary and the walk not asked again until it has dug, so that what it draws is played before the next
 * activation; with nothing for Thrasios either, the go-off ends. While a dig toward Oracle could run, our hand cast of
 * Oracle waits ({@link #holdsOracle}, ComboHold.vetoesCast), so the stock AI never casts it before the dig has brought
 * our library to Oracle's threshold. Every action passes the driver's own check (playable, not refused by a static,
 * its cost paid by the pool and our untapped lands, its targets legal; never canPlayAndPayFor, whose throttle and
 * stock judges would refuse it) and counts toward the caps ({@link LoopRounds}); a sink's action the check refuses is
 * not taken, and ends nothing. A ping that changed neither its target's life nor its toughness left ends Ballista's
 * volley for the phase (R25).
 *
 * <p>A step that fails or gains nothing never burns the caps (R21): at the next decision an action that was skipped
 * (its payment failed) or changed nothing among the pool, our hand, battlefield and stack fails its line for the
 * phase, and so does a cycle that raised the pool by less than 1 from one boundary to the next (a cost tax, Damping
 * Sphere: what the printed net cannot see). The driver then falls to its next line, or to the stock walk. A Thrasios
 * activation that moved no card out of our library (a draw limit: Spirit of the Labyrinth, Leovold, Emissary of Trest,
 * Narset, Parter of Veils) ends Thrasios's dig for the phase, its {4} spent being no gain. A step of the cycle that the
 * check refuses mid-go-off (a hoser arrived) ends the go-off for the phase, as does losing its line, the board reaching
 * five times lethal, every opponent unable to lose, or two judged rounds in a row that bought nothing.
 *
 * <p>The driver draws no random number (a payment it triggers draws as the engine's payer does), reads no clock, and
 * checks that the game is still on before it returns an action. Its turn stamp lifts the runaway throttle for the rest
 * of a turn it acted in ({@link #liftsThrottleFor}, read on the AI's evaluation thread, hence volatile), and it steers
 * Sakashima of a Thousand Faces to copy Kinnan when that completes the Grim Monolith line ({@link #copyChoice}).
 */
public final class LoopDriver {
    private static final String KINNAN = "Kinnan, Bonder Prodigy";
    private static final String GRIM = "Grim Monolith";
    private static final String SAKASHIMA = "Sakashima of a Thousand Faces";
    /** The rock untap of Kinnan, Grim Monolith and Sakashima as a copy of Kinnan. */
    private static final String GRIM_LINE = "129-2585-2719";

    private final Player ai;
    // the caps and the round record (tests swap in smaller caps: useRounds)
    private LoopRounds rounds = new LoopRounds();
    // the turn the driver last returned an action in, -1 before any: the runaway throttle is lifted for the rest of it
    private volatile int actedTurn = -1;
    // the go-off of the phase stamped here: the pool lasts to the end of the step (section 6.3), so each phase starts
    // afresh. Running; over for the phase; something spent in it; this decision passed to the stock walk at the target;
    // the line it runs; the lines failed for the phase (R21), by variant id
    private int phaseTurn = -1;
    private PhaseType phase;
    private boolean active;
    private boolean ended;
    private boolean spent;
    private boolean handedToWalk;
    // the walk chose nothing at the target, and Thrasios digs next (section 6.4 step 3): the refill goes on to its {4},
    // the walk not asked again until Thrasios has dug
    private boolean thrasiosDue;
    private ComboLines.Line line;
    private final List<String> failed = new ArrayList<>();
    // R21: the action returned last, its line and the state when it was returned, judged at the next decision
    private SpellAbility last;
    private ComboLines.Line lastLine;
    private int[] lastState;
    // R21: the pool at the last boundary of this line, -1 when there is none to compare with (the go-off has not passed
    // one yet, or the stock AI or Walking Ballista spent since)
    private ComboLines.Line boundaryLine;
    private int boundaryPool = -1;
    // R25: the ping returned last, its target and what the ping is to change on it (a player's life, a creature's
    // toughness left) when it was returned, judged at the next decision; and whether a ping that changed nothing has
    // ended Walking Ballista's volley for the phase
    private GameObject pinged;
    private int pingedHad;
    private boolean volleyOver;
    // the Thrasios activation returned last: our library's size when it was returned, judged at the next decision (-1
    // when none is to be judged); and whether one that moved no card out of our library has ended Thrasios's dig for
    // the phase
    private int dugFrom = -1;
    private boolean digOver;
    // the futility gate: our hand's and battlefield's card names, each sorted, when the last go-off that spent nothing
    // ended; null when none did
    private List<List<String>> futile;

    /** The driver of {@code ai}'s loops, made with its AiController: it reads nothing yet, since the player is not yet
     *  registered with its game. */
    public LoopDriver(final Player ai) {
        this.ai = ai;
    }

    /** The driver of the AI deciding for {@code p}, or null when {@code p}'s controller is not a PlayerControllerAi, or
     *  while another player controls {@code p} (ComboTracker.of's guards). */
    public static LoopDriver of(final Player p) {
        if (p == null || p.isControlled() || !(p.getController() instanceof PlayerControllerAi)) {
            return null;
        }
        return ((PlayerControllerAi) p.getController()).getAi().getLoopDriver();
    }

    /**
     * The go-off slot (AiController.getSpellAbilityToPlay, after piece 1's go-off): Walking Ballista's next action once
     * the pool holds what it needs; else Thassa's Oracle's cast once a dig toward it has brought our library to its
     * threshold, when the driver's own check passes it (one it refuses waits for the refill below); else, the walk
     * having chosen nothing at this board, Thrasios's activation once the pool holds its {4};
     * else the next step of the loop line the go-off runs, its activator us and its targets (a cast's X) set; or null:
     * at once outside our main phase, above a stack, once the game is over, for a deck holding no loop line, while a
     * cap or the idle rule stops the driver; when no line can run, a start gate refuses, or the go-off ends; and, with
     * nothing for Ballista, Oracle or Thrasios, at a boundary with the pool at the target, where the stock walk plays
     * next. {@code saList} is what the AI may play now (the walk's list); the steps are read from the pieces
     * themselves.
     */
    public SpellAbility mainPhaseAction(final List<SpellAbility> saList) {
        handedToWalk = false;
        final Game game = ai.getGame();
        if (game.isGameOver()) {
            return null;
        }
        final PhaseHandler ph = game.getPhaseHandler();
        if (!ph.isPlayerTurn(ai) || !ph.getPhase().isMain() || !game.getStack().isEmpty()) {
            return null;
        }
        final ComboTracker t = ComboTracker.of(ai);
        if (t == null || !holdsLoop(t)) {
            return null;
        }
        sync(ph);
        if (ended || rounds.stoppedForGame() || rounds.stoppedForTurn(ai)) {
            return null;
        }
        if (rounds.idleStop(ai)) {
            end();
            return null;
        }
        judgeLast();
        judgeVolley();
        judgeDig();
        if (!active) {
            // a go-off measures its cycles from its own first boundary
            boundaryLine = null;
            boundaryPool = -1;
        }
        final ComboLines.Line l = choose(t.view());
        // a go-off that runs ends for the phase when its line is gone and when it may go on no more; one that has not
        // started yet simply does not start, and the next decision reads the gates again
        if (l == null || (active ? !goesOn() : !(starts(l) && goesOn()))) {
            if (active) {
                end();
            }
            return null;
        }
        // Walking Ballista's step first (section 6.4 step 1): its action once the pool holds what it needs, a spend at
        // a boundary only (the pool there is the loop's surplus), a ping at any decision; and while Ballista has a
        // step the driver owns every decision, refilling for it, never passing the priority to the walk between a
        // pump and the volley
        final int need = ballistaNeed();
        final boolean boundary = LoopCycles.boundary(ai, l);
        if (need >= 0) {
            if (LoopPool.floating(ai) >= need && (need == 0 || boundary)) {
                return ballistaStep(l, ph);
            }
            return refill(l, ph);
        }
        if (oracleDue(l)) {
            // a dig toward Thassa's Oracle has brought our library to its threshold (section 6.4 step 3): its cast
            // wins, at any decision the driver's own check passes it. One the check refuses for its price, beyond
            // what the pool and our lands pay now (a tax; the blue that only Chromatic Orrery lets the pool pay),
            // waits: the decision goes on as one with no cast due, and the refill makes the pool that pays it
            final SpellAbility cast = spend(l, ph, LoopSinks.oracleCast(ai));
            if (cast != null) {
                return cast;
            }
        }
        // Thrasios's turn (section 6.4 step 3), once the walk chose nothing at this board: the refill goes on to its
        // {4}, a spend taken at a boundary; else, at a boundary with the pool at the target, the walk's turn
        final SpellAbility dig = thrasiosDue ? dig(l) : null;
        thrasiosDue = dig != null;
        if (dig != null) {
            if (boundary && LoopPool.floating(ai) >= ComboExecute.priced(ai, dig).getCMC()) {
                return thrasiosStep(l, ph, dig);
            }
        } else if (boundary && LoopPool.floating(ai) >= target(l)) {
            active = true;
            line = l;
            handedToWalk = true;
            return null;
        }
        return refill(l, ph);
    }

    /** The stock walk's pick, when this decision's {@link #mainPhaseAction} passed it the priority at the target: a
     *  judged round of the go-off begins (the round record reads the board, not the pick), something is spent, and the
     *  next boundary measures afresh. Inert otherwise. */
    public void noteStockPlay(final SpellAbility chosen) {
        if (!handedToWalk) {
            return;
        }
        handedToWalk = false;
        spent = true;
        boundaryPool = -1;
        rounds.beginRound(ai, true, line);
    }

    /**
     * When the walk chose nothing, after this decision's {@link #mainPhaseAction} passed it the priority at the target,
     * a boundary: Thrasios digs (section 6.4 step 3, {@link LoopSinks#thrasios}, unless an activation that moved no
     * card has ended its dig for the phase), its activation now when the pool holds its {4}, else the refill toward
     * it, which goes on at the next decisions without the walk asked again (it chose nothing at this board), each
     * through the driver's own check and the caps. With nothing for Thrasios to do, or the game over, the go-off ends,
     * and when nothing was spent in it, our hand's and battlefield's names are kept, so the same futile build is not
     * started again until one of them changes (section 6.2): null. Inert otherwise.
     */
    public SpellAbility afterStockWalk() {
        if (!handedToWalk) {
            return null;
        }
        handedToWalk = false;
        final Game game = ai.getGame();
        // a game that timed out is marked over while the walk runs
        final SpellAbility dig = game.isGameOver() ? null : dig(line);
        if (dig != null) {
            final PhaseHandler ph = game.getPhaseHandler();
            if (LoopPool.floating(ai) >= ComboExecute.priced(ai, dig).getCMC()) {
                return thrasiosStep(line, ph, dig);
            }
            thrasiosDue = true;
            return refill(line, ph);
        }
        if (!spent) {
            futile = fingerprint();
        }
        active = false;
        line = null;
        return null;
    }

    /**
     * Whether our hand cast {@code sa} of Thassa's Oracle waits for the dig toward it (section 6.4 step 3;
     * ComboHold.vetoesCast): while a loop line of the deck can run (its pieces where its cycle needs them, ready and
     * not denied: {@link LoopCycles#ready} and the tracker's own reading), a dig toward Oracle runs on it
     * ({@link LoopSinks#digsForOracle}: Oracle in our hand and able to win now, its {U}{U} payable, Thrasios ready)
     * and our library holds more than {@link LoopSinks#oracleThreshold} cards. Read from the board at every decision,
     * a go-off running or not, so the stock walk, which runs before Thrasios's first activation, never casts Oracle
     * early. False for any other spell, for a deck holding no loop line, and while piece 1's own Oracle go-off holds
     * (R2): while an executable piece-1 line has Oracle as its winner, the go-off (ComboExecute.goOffNow, which reads
     * those very lines) and the hold (ComboHold.oracleWaits) are piece 1's, and an immediate Consultation win is never
     * traded for a dig. Draws nothing.
     */
    public boolean holdsOracle(final SpellAbility sa) {
        if (!ownOracleCast(sa)) {
            return false;
        }
        final ComboTracker t = ComboTracker.of(ai);
        if (t == null || !holdsLoop(t)) {
            return false;
        }
        final ComboTracker.View v = t.view();
        for (final ComboLines.Line l : v.executable()) {
            if (l.winner() == ComboLines.Winner.ORACLE) {
                return false;
            }
        }
        for (final ComboLines.Line l : v.lines()) {
            if (l.isLoop() && LoopCycles.ready(ai, l) && v.ready(l) && !v.denied(l)
                    && LoopSinks.digsForOracle(ai, l)) {
                return ai.getCardsIn(ZoneType.Library).size() > LoopSinks.oracleThreshold(ai);
            }
        }
        return false;
    }

    /** Going off in response to removal of a loop piece or Walking Ballista (section 6.6): Task B9. Null until then. */
    public SpellAbility responseAction() {
        return null;
    }

    /** Whether the driver returned an action this turn: ComputerUtil.preventRunAwayActivations is lifted for the rest
     *  of it (section 6.8), since the driver's own activations count toward it. */
    public boolean liftsThrottle() {
        final int acted = actedTurn;
        return acted >= 0 && acted == ai.getGame().getPhaseHandler().getTurn();
    }

    /** {@link #liftsThrottle} for the driver of the AI deciding for {@code p}; false with none ({@link #of}), and for a
     *  null player. */
    public static boolean liftsThrottleFor(final Player p) {
        final LoopDriver d = of(p);
        return d != null && d.liftsThrottle();
    }

    /** Sakashima of a Thousand Faces's copy (CloneAi.chooseSingleCard, section 6.8): our Kinnan, Bonder Prodigy among
     *  {@code options} when the effect's host is Sakashima, the deck holds the Grim Monolith line (129-2585-2719, which
     *  needs Sakashima as a second Kinnan) and Grim Monolith is in our hand or on our battlefield; else null, the stock
     *  choice. */
    public Card copyChoice(final SpellAbility sa, final Iterable<Card> options) {
        final Card host = sa.getHostCard();
        if (host == null || !SAKASHIMA.equals(ComboTracker.nameOf(host))) {
            return null;
        }
        final ComboTracker t = ComboTracker.of(ai);
        if (t == null || !holds(t, GRIM_LINE)) {
            return null;
        }
        if (t.find(GRIM, ZoneType.Hand) == null && t.find(GRIM, ZoneType.Battlefield) == null) {
            return null;
        }
        for (final Card c : options) {
            if (KINNAN.equals(ComboTracker.nameOf(c))) {
                return c;
            }
        }
        return null;
    }

    /** Tests only: these caps and this round record instead. */
    void useRounds(final LoopRounds rounds) {
        this.rounds = rounds;
    }

    /** Tests only: forgets the futility gate's fingerprint. */
    void forgetFutility() {
        futile = null;
    }

    // a new phase starts the go-off afresh
    private void sync(final PhaseHandler ph) {
        if (ph.getTurn() == phaseTurn && ph.getPhase() == phase) {
            return;
        }
        phaseTurn = ph.getTurn();
        phase = ph.getPhase();
        active = false;
        ended = false;
        spent = false;
        thrasiosDue = false;
        line = null;
        failed.clear();
        last = null;
        lastLine = null;
        lastState = null;
        boundaryLine = null;
        boundaryPool = -1;
        pinged = null;
        volleyOver = false;
        dugFrom = -1;
        digOver = false;
    }

    // the go-off is over for the phase
    private void end() {
        active = false;
        ended = true;
        line = null;
    }

    // R21: the action returned last, judged now: skipped (its payment failed and was rolled back), or nothing changed
    // among the pool, our hand, our battlefield and the stack, fails its line for the phase
    private void judgeLast() {
        if (last == null) {
            return;
        }
        if (last.isSkip() || Arrays.equals(lastState, state())) {
            failed.add(lastLine.variant().id());
        }
        last = null;
        lastLine = null;
        lastState = null;
    }

    // the line to run now: the best line that can run, past each whose last cycle the pool measured short (R21), which
    // is failed for the phase
    private ComboLines.Line choose(final ComboTracker.View v) {
        while (true) {
            final ComboLines.Line l = pick(v);
            if (l == null || measured(l)) {
                return l;
            }
            failed.add(l.variant().id());
        }
    }

    // the line that can run with the highest net a cycle, then the lowest variant id. A rock line's net does not depend
    // on which rock is tapped, so the pick does not move mid-cycle
    private ComboLines.Line pick(final ComboTracker.View v) {
        ComboLines.Line best = null;
        int bestNet = 0;
        for (final ComboLines.Line l : v.lines()) {
            if (!runs(v, l)) {
                continue;
            }
            final int net = LoopCycles.net(ai, l);
            if (best == null || net > bestNet
                    || (net == bestNet && l.variant().id().compareTo(best.variant().id()) < 0)) {
                best = l;
                bestNet = net;
            }
        }
        return best;
    }

    // a loop line not failed this phase whose pieces are where its cycle needs them and that is executable: ready and
    // not denied, the tracker's own reading (its executable list's test, read only for these lines)
    private boolean runs(final ComboTracker.View v, final ComboLines.Line l) {
        return l.isLoop() && !failed.contains(l.variant().id()) && LoopCycles.ready(ai, l) && v.ready(l)
                && !v.denied(l);
    }

    // R21: at a boundary, the cycle that ended there must have raised the pool by 1 or more since the line's previous
    // boundary (none to compare with: true); the pool at every boundary is kept for the next
    private boolean measured(final ComboLines.Line l) {
        if (!LoopCycles.boundary(ai, l)) {
            return true;
        }
        final int pool = LoopPool.floating(ai);
        final boolean rose = l != boundaryLine || boundaryPool < 0 || pool - boundaryPool >= 1;
        boundaryLine = l;
        boundaryPool = pool;
        return rose;
    }

    // the start gates (section 6.2), read while no go-off runs this phase: the kind can stop, and something to spend
    // the mana on: Walking Ballista's step (on our battlefield, or castable from our hand), what the stock AI could
    // spend while our hand and battlefield are not those of the last go-off that spent nothing (the futility gate holds
    // back a go-off whose only spender is the stock AI), Thassa's Oracle's cast at the end of a dig toward it, or
    // Thrasios's dig
    private boolean starts(final ComboLines.Line l) {
        if (!l.loop().canStop()) {
            return false;
        }
        if (ballistaNeed() < 0 && !stockSpends(l) && !oracleDue(l) && dig(l) == null) {
            return false;
        }
        spent = false;
        return true;
    }

    // what the stock AI could spend (LoopPool.stockTarget above 0), while our hand and battlefield are not those of the
    // last go-off that spent nothing (the futility gate)
    private boolean stockSpends(final ComboLines.Line l) {
        return LoopPool.stockTarget(ai, l) > 0 && (futile == null || !futile.equals(fingerprint()));
    }

    // a go-off goes on while some opponent can lose and our board is not five times lethal already (sections 6.2 and
    // 6.5, LoopTracker's decisive), or while Walking Ballista can shoot now: a kill this turn comes first, so on a board
    // already five times lethal the driver goes on for Ballista's shot alone, and with no opponent able to lose it
    // goes on while Ballista can kill the loss blocker that stops one (its volley's first target), and not merely
    // while a loss blocker its ping could target stands: under a Pithing Needle naming Ballista it cannot kill it
    private boolean goesOn() {
        for (final Player p : ai.getOpponents()) {
            if (!p.cantLose()) {
                return !LoopTracker.Snapshot.of(ai).isDecisive() || shotNow();
            }
        }
        return shotNow();
    }

    // whether Walking Ballista can shoot now (LoopSinks.ballistaCanShoot) and has a step to take toward it
    private boolean shotNow() {
        return ballistaNeed() >= 0 && LoopSinks.ballistaCanShoot(ai);
    }

    // the pool Walking Ballista's next action needs (LoopSinks.ballistaPool); -1 when Ballista has nothing to do, or
    // once a ping that changed nothing has ended its volley for the phase (R25)
    private int ballistaNeed() {
        return volleyOver ? -1 : LoopSinks.ballistaPool(ai);
    }

    // Walking Ballista's action now (LoopSinks.ballista), spent as a sink's (spend); a ping's target and what it had
    // are kept for R25's judge
    private SpellAbility ballistaStep(final ComboLines.Line l, final PhaseHandler ph) {
        final SpellAbility sa = spend(l, ph, LoopSinks.ballista(ai));
        if (sa != null && sa.usesTargeting()) {
            pinged = sa.getTargets().get(0);
            pingedHad = had(pinged);
        }
        return sa;
    }

    // Thrasios's activation now, spent as a sink's (spend): the refill toward it is over, and the next decision is the
    // walk's again, to play what it drew; our library's size is kept for the activation's judge (judgeDig)
    private SpellAbility thrasiosStep(final ComboLines.Line l, final PhaseHandler ph, final SpellAbility dig) {
        thrasiosDue = false;
        final SpellAbility sa = spend(l, ph, dig);
        if (sa != null) {
            dugFrom = ai.getCardsIn(ZoneType.Library).size();
        }
        return sa;
    }

    // Thrasios's next activation (LoopSinks.thrasios); none once an activation that moved no card has ended its dig
    // for the phase (judgeDig)
    private SpellAbility dig(final ComboLines.Line l) {
        return digOver ? null : LoopSinks.thrasios(ai, l);
    }

    // a sink's action now (Walking Ballista's, Thrasios's, Thassa's Oracle's cast), through the driver's own check and
    // the caps: it opens a round the idle rule does not judge (each has stops of its own), it is something spent, and
    // the next boundary is measured afresh (R21: a spend at a boundary is no cycle). An action the check refuses is not
    // taken and ends nothing (null): Ballista's and Thrasios's steps are read through the same check before they come
    // here, and Oracle's cast, refused for its price, waits for the refill (mainPhaseAction)
    private SpellAbility spend(final ComboLines.Line l, final PhaseHandler ph, final SpellAbility sa) {
        if (sa == null || !passes(sa)) {
            return null;
        }
        if (!rounds.act(ai)) {
            return null;
        }
        rounds.beginRound(ai, false, l);
        active = true;
        line = l;
        spent = true;
        boundaryPool = -1;
        last = sa;
        lastLine = l;
        lastState = state();
        actedTurn = ph.getTurn();
        return sa;
    }

    // the cycle's next step (LoopCycles.next), a refill, through the driver's own check and the caps; a step the check
    // refuses (a hoser arrived) ends the go-off for the phase
    private SpellAbility refill(final ComboLines.Line l, final PhaseHandler ph) {
        final SpellAbility next = LoopCycles.next(ai, l);
        if (next == null || !passes(next)) {
            if (active) {
                end();
            }
            return null;
        }
        if (!rounds.act(ai)) {
            return null;
        }
        active = true;
        line = l;
        last = next;
        lastLine = l;
        lastState = state();
        actedTurn = ph.getTurn();
        return next;
    }

    // a dig toward Thassa's Oracle at its end (section 6.4 step 3): the dig runs (LoopSinks.digsForOracle: Oracle able
    // to win now), we can win, and our library holds Oracle's threshold of cards or fewer (LoopSinks.oracleThreshold),
    // so Oracle's cast wins
    private boolean oracleDue(final ComboLines.Line l) {
        return LoopSinks.digsForOracle(ai, l) && !ai.cantWin()
                && ai.getCardsIn(ZoneType.Library).size() <= LoopSinks.oracleThreshold(ai);
    }

    // our own hand cast of Thassa's Oracle: a spell, not a copy and not a Play-effect cast, whose host is our Oracle in
    // our hand, its activator us or none yet (ComboExecute.ownHandCast's reading)
    private boolean ownOracleCast(final SpellAbility sa) {
        if (sa == null || !sa.isSpell() || sa.isCopied() || sa.isCastFromPlayEffect()) {
            return false;
        }
        final Card host = sa.getHostCard();
        final Player activator = sa.getActivatingPlayer();
        return host != null && ComboLines.Winner.ORACLE.card.equals(ComboTracker.nameOf(host))
                && host.isInZone(ZoneType.Hand) && ai.equals(host.getOwner())
                && (activator == null || ai.equals(activator));
    }

    // R25: the ping returned last, judged now that the stack it went on is empty again (resolved, countered or
    // fizzled): one that changed neither its target player's life nor its target creature's toughness left (a Fog,
    // Teferi's Protection, a prevention shield) ends Walking Ballista's volley for the phase, so Ballista takes no more
    // step in it. No margin otherwise: a ping that landed is followed by a fresh count at the next decision
    private void judgeVolley() {
        if (pinged == null) {
            return;
        }
        if (had(pinged) == pingedHad) {
            volleyOver = true;
        }
        pinged = null;
    }

    // Thrasios's activation returned last, judged now that the stack it went on is empty again (resolved or
    // countered): one that moved no card out of our library, where each that resolves moves its top card (onto our
    // battlefield, else into our hand), ends Thrasios's dig for the phase, so Thrasios takes no more activation in it
    // (R21: a step that gains nothing never burns the caps; the {4} it spent is no gain). A draw limit stops it so
    // (Spirit of the Labyrinth, Leovold, Emissary of Trest, Narset, Parter of Veils: a second draw in a turn draws
    // nothing), as does a countered activation; the plain dig, the dig toward Oracle and the dig to empty alike
    private void judgeDig() {
        if (dugFrom < 0) {
            return;
        }
        if (ai.getCardsIn(ZoneType.Library).size() >= dugFrom) {
            digOver = true;
        }
        dugFrom = -1;
    }

    // what a ping changes on its target: a player's life, a creature's toughness left (LoopSinks.toughnessLeft) on the
    // battlefield; Integer.MIN_VALUE once the player has lost, or the creature has left the battlefield
    private int had(final GameObject target) {
        if (target instanceof Player p) {
            return p.hasLost() ? Integer.MIN_VALUE : p.getLife();
        }
        for (final Card c : ai.getGame().getCardsIn(ZoneType.Battlefield)) {
            if (c.equals(target)) {
                return LoopSinks.toughnessLeft(c);
            }
        }
        return Integer.MIN_VALUE;
    }

    // the pool a refill builds to while line l runs: the stock AI's view (section 6.3)
    private int target(final ComboLines.Line l) {
        return LoopPool.stockTarget(ai, l);
    }

    /**
     * The driver's own check of an action (the plan's Calls) but its payment and its targets: {@code ai} its activator,
     * playable now, and not refused by a CantBeActivated or CantBeCast static (a spell read on an LKI copy on the
     * stack, as AiController.canPlaySa reads it). LoopSinks reads Walking Ballista's steps through it, before the pool
     * is built for them.
     */
    static boolean allowed(final Player ai, final SpellAbility sa) {
        sa.setActivatingPlayer(ai);
        return sa.canPlay() && sa.checkRestrictions(restrictionHost(ai, sa), ai);
    }

    // the driver's own check of an action it returns (the plan's Calls): allowed, its cost as the engine prices it now
    // paid by the pool and our untapped lands, and each target it has set one it can target. A skipped action is R21's
    // to read (judgeLast), which fails its line, not the go-off
    private boolean passes(final SpellAbility sa) {
        if (!allowed(ai, sa)) {
            return false;
        }
        if (!LoopPool.pays(ai, ComboExecute.priced(ai, sa), null)) {
            return false;
        }
        if (sa.usesTargeting()) {
            if (!sa.isTargetNumberValid()) {
                return false;
            }
            for (final GameObject o : sa.getTargets()) {
                if (!sa.canTarget(o)) {
                    return false;
                }
            }
        }
        return true;
    }

    // the card a CantBeActivated or CantBeCast static is read against: an ability's host; for a spell an LKI copy of it
    // on the stack, cast from its zone (AiController.canPlaySa's reading)
    private static Card restrictionHost(final Player ai, final SpellAbility sa) {
        final Card host = sa.getHostCard();
        if (!sa.isSpell()) {
            return host;
        }
        final Card copy = CardCopyService.getLKICopy(host);
        copy.setLKICMC(-1);
        copy.setLastKnownZone(ai.getGame().getStackZone());
        copy.setCastFrom(host.getZone());
        return copy;
    }

    // what R21 compares: the pool's total; our hand, each card by id; our battlefield, each card by id, tapped or not,
    // and its counters; the stack, each item by id
    private int[] state() {
        final CardCollectionView hand = ai.getCardsIn(ZoneType.Hand);
        final CardCollectionView battlefield = ai.getCardsIn(ZoneType.Battlefield);
        final MagicStack stack = ai.getGame().getStack();
        final int[] s = new int[4 + hand.size() + 3 * battlefield.size() + stack.size()];
        int i = 0;
        s[i++] = ai.getManaPool().totalMana();
        s[i++] = hand.size();
        for (final Card c : hand) {
            s[i++] = c.getId();
        }
        s[i++] = battlefield.size();
        for (final Card c : battlefield) {
            s[i++] = c.getId();
            s[i++] = c.isTapped() ? 1 : 0;
            s[i++] = c.getNumAllCounters();
        }
        s[i++] = stack.size();
        for (final SpellAbilityStackInstance si : stack) {
            s[i++] = si.getId();
        }
        return s;
    }

    // our hand's and battlefield's card names, each sorted: the futility gate's fingerprint
    private List<List<String>> fingerprint() {
        return List.of(sortedNames(ZoneType.Hand), sortedNames(ZoneType.Battlefield));
    }

    private List<String> sortedNames(final ZoneType zone) {
        final List<String> names = new ArrayList<>();
        for (final Card c : ai.getCardsIn(zone)) {
            names.add(ComboTracker.nameOf(c));
        }
        Collections.sort(names);
        return List.copyOf(names);
    }

    // whether the deck holds a loop line
    private static boolean holdsLoop(final ComboTracker t) {
        for (final ComboLines.Line l : t.deckLines()) {
            if (l.isLoop()) {
                return true;
            }
        }
        return false;
    }

    // whether the deck holds the line of that id
    private static boolean holds(final ComboTracker t, final String id) {
        for (final ComboLines.Line l : t.deckLines()) {
            if (l.variant().id().equals(id)) {
                return true;
            }
        }
        return false;
    }
}
