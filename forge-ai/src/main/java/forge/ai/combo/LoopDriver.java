package forge.ai.combo;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import forge.ai.ComputerUtil;
import forge.ai.LoopTracker;
import forge.ai.PlayerControllerAi;
import forge.game.Game;
import forge.game.GameObject;
import forge.game.ability.AbilityKey;
import forge.game.ability.ApiType;
import forge.game.card.Card;
import forge.game.card.CardCollectionView;
import forge.game.card.CardCopyService;
import forge.game.phase.PhaseHandler;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.spellability.AbilitySub;
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
 * kill the loss blocker that stops one losing, the board is not five times lethal already (unless Ballista can shoot: a
 * kill this turn comes first), and there is something to spend the mana on: Ballista's step, Thrasios's dig, Thassa's
 * Oracle's cast at the head of its line, or what the stock AI could spend ({@link LoopPool#stockTarget} above 0) while
 * our hand and battlefield are not those of the last go-off that spent nothing (the futility gate). Ballista's step
 * comes first ({@link LoopSinks#ballista}): the cycle's next step ({@link LoopCycles#next}) refills the pool until it
 * holds what Ballista's action needs, a spend taken at a boundary, and the driver owns every decision while Ballista
 * has a step. Then the Oracle line (section 6.4 step 3, amended 2026-11-04 (the Oracle window)), while it can run:
 * Thassa's Oracle in our hand able to win now, its {U}{U} payable and a Thrasios ready
 * ({@link LoopSinks#digsForOracle}); we able to win; no mandatory draw trigger of ours on our battlefield whose draws
 * cannot be counted ({@link LoopSinks#drawsUncounted}: Nezahal, Primal Tide's, which a noncreature spell of theirs in
 * the window would set off on our emptied library); Thrasios's dig not over for the phase; Oracle's cast one the
 * driver may make, at a price in life below ours; and the line's whole cost, its build, its cast and one activation for
 * each card in our library, within what the caps have left, read at each decision (R28's shape). The refill builds
 * the pool to what a dig of our whole library costs and Oracle's own price ({@link LoopSinks#oraclePool}), the driver
 * owning every decision meanwhile, so that the walk neither taps the blue sources nor spends the pool first; at a
 * boundary there, Oracle's cast, through the driver's own check (one it refuses for its payment waits for the refill).
 * Nothing is dug before Oracle resolves, so a countered Oracle costs no card: the pool floats on for the rest of the
 * go-off. Otherwise each decision returns the cycle's next step until, at a boundary, the pool holds the target, where
 * the driver passes the decision to the stock AI's walk, which plays on the pool ({@link #noteStockPlay}); when it
 * plays nothing Thrasios, Triton Hero digs ({@link #afterStockWalk}, {@link LoopSinks#thrasios}), one activation a
 * decision, the refill going on to its {4} first, its spend taken at a boundary and the walk not asked again until it
 * has dug, so that what it draws is played before the next activation; with nothing for Thrasios either, the go-off
 * ends. While the Oracle line could run, our hand cast of Oracle waits above Oracle's threshold ({@link #holdsOracle},
 * ComboHold.vetoesCast), so the stock AI never casts it there: the line casts it and digs in its window. Every action
 * passes the driver's own check (playable, not refused by a static, its cost paid by the pool and our untapped lands,
 * its targets legal; never canPlayAndPayFor, whose throttle and stock judges would refuse it) and counts toward the
 * caps ({@link LoopRounds}); a sink's action the check refuses is not taken, and ends nothing. A ping that changed
 * neither its target's life nor its toughness left ends Ballista's volley for the phase (R25).
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
 * <p>A step that costs us life is priced before it is taken (R30, the final review's round 1): what the stock AI
 * prices a play at ({@link #lifeCost}: the cast and activation triggers that damage us, Harsh Mentor's 2 for each
 * ability of an artifact, creature or land we activate; the triggers of what it puts onto the battlefield, Rampaging
 * Ferocidon's). A cycle whose steps cost life is no unlimited loop: its line runs, and its steps are taken, only toward
 * Walking Ballista's kill this turn whose whole remaining cost in life, the cycles its mana needs, its pumps, its pings
 * and its cast, is less than the life we will have once what is pending has resolved ({@link LoopSinks#volleyLife};
 * that life is ours less the price of each action returned that is not yet judged, each taken whole: in a main phase,
 * which acts on an empty stack, our life); otherwise the line fails for the phase. Ballista takes a step that costs
 * life only toward such a kill, Thrasios none, and Thassa's Oracle is not cast for a cost of that whole life. Then
 * each action is measured on its own: our life when it was returned, against our life at the first decision at which
 * neither it nor anything put on the stack after it is there, less what the actions returned after it lost us
 * meanwhile, each of them measured first (the final-fix compliance review's follow-up round 1). One that cost more
 * than its price (a painland's damage, a punisher's for each card we draw: costs no price reads) fails its line for
 * the phase, if a cycle's step, and ends Ballista's volley, or Thrasios's dig, for the phase.
 *
 * <p>In response to removal ({@link #respond}, section 6.6, AiController's stack-response block, ahead of the
 * counterspells): with an opponent's spell or ability on the stack that would take a piece of a loop line of an
 * instant-speed kind (a rock untap, the flicker) or a Walking Ballista of ours off our battlefield, the driver goes off
 * above it, on either player's turn, and spends on Ballista alone, its volley to exact lethal, when that volley fits in
 * what the caps have left (R28). Each of its actions goes on the stack above the removal and resolves first, and the
 * next decision with the removal on top asks the driver again; the go-off goes on while the removal is on the stack,
 * whether or not its target is still there (the first flicker makes Palinchron a new object). It takes one step at a
 * time, as a main phase does on an empty stack (the final-fix compliance review's follow-up round 1): while an action
 * it returned is not yet judged, beneath an opponent's triggered ability that threatens nothing it protects (a
 * punisher's trigger for that action, Harsh Mentor's), it waits, and the AI passes, the stock AI playing nothing
 * either; above an opponent's spell or activated ability, a new threat among them, it acts at once. The stop rule's
 * parts that read Ballista, the caps and R21's judges hold as in a main phase.
 *
 * <p>In the Oracle line's window ({@link #respond}, section 6.6's second case, amended 2026-11-04 (the Oracle window)):
 * above the entering trigger of the Thassa's Oracle the line cast this phase, on the stack's top (its
 * AIRespondsToOwnAbility gives us priority above it, and AiController's stack-response block asks the driver there),
 * Thrasios digs from the floating pool ({@link LoopSinks#windowDig}), one activation a decision, each resolved alone
 * above the trigger before the next, until our library is empty, whatever mana the opponents have open; then, and
 * whenever it has no dig to take (Thrasios gone, its dig over, the pool short, a cap), the driver passes, so the stock
 * AI plays nothing above the trigger either. The trigger then resolves on an empty library: the win, whatever has
 * become of Oracle meanwhile. Above an opponent's item there the decision is the removal response's, above any other
 * item of ours the stock's, and the trigger of an Oracle the line did not cast opens no window.
 *
 * <p>The driver draws no random number (a payment it triggers draws as the engine's payer does), reads no clock, and
 * checks that the game is still on before it returns an action. Its turn stamp lifts the runaway throttle for the rest
 * of a turn it acted in ({@link #liftsThrottleFor}, read on the AI's evaluation thread, hence volatile) for the
 * abilities whose counts a go-off inflates alone, Walking Ballista's, Thrasios's and Kinnan's dig
 * ({@link #liftsThrottleOn}): every other ability keeps the stock roll, which ends the stock AI's own loops. It steers
 * Sakashima of a Thousand Faces to copy Kinnan when that completes the Grim Monolith line ({@link #copyChoice}). For
 * Palinchron's loops it makes two choices: during one of its flicker or recast cycles Palinchron's entering untap takes
 * our lands that make blue first ({@link #untapChoice}), and Deadeye Navigator's own soulbond pairs it with Palinchron
 * whenever the deck holds the flicker line ({@link #partnerChoice}). During one of its cast and bounce cycles
 * Hullbreaker Horror's cast trigger returns the cycle's other piece to our hand ({@link #charmModes}), and our hand
 * cast of the cycle's piece in hand waits for the cycle ({@link #holdsCycleCast}, ComboHold.vetoesCast), so the stock
 * walk, which plays at the cycle's boundary, never casts it. For a deck holding the flicker line the stock flicker
 * that saves a card from removal (ChangeZoneAi) is refused once no opponent's item targets the card as the object it is
 * now ({@link #refusesBlink}), so a flicker that saved Palinchron is not made again with each untap paying the next;
 * a save that does not return the card to the battlefield at once, a return to our hand or an exile until a later
 * step, is refused only while an item on the stack names the card as a target by its card id, as the stock reading
 * does: the removal whose target left and came back, or an item of ours.
 */
public final class LoopDriver {
    private static final String KINNAN = "Kinnan, Bonder Prodigy";
    private static final String GRIM = "Grim Monolith";
    private static final String SAKASHIMA = "Sakashima of a Thousand Faces";
    /** The rock untap of Kinnan, Grim Monolith and Sakashima as a copy of Kinnan. */
    private static final String GRIM_LINE = "129-2585-2719";
    /** The flicker of Palinchron and Deadeye Navigator. */
    private static final String FLICKER_LINE = "1409-3542";
    // the cards whose activations a go-off inflates, by printed name, for which the runaway throttle is lifted on a
    // turn the driver acted in (section 6.8): Walking Ballista's {4} and ping and Thrasios's {4}, the driver's own
    // sinks, and Kinnan's dig, which the stock walk takes on the pool
    private static final List<String> LIFTED = List.of(LoopSinks.BALLISTA, LoopSinks.THRASIOS, KINNAN);
    // the answer of a go-off in response that waits for its own action (respond): a pass
    private static final Answer WAIT = new Answer(null);

    // R30: what an action returned was, for the judge of its cost in life
    private enum Spent { CYCLE, BALLISTA, THRASIOS, ORACLE }

    // R30: an action returned whose cost in life is still to be judged: it, our life and the stack's size when it was
    // returned, the life it was priced at (lifeCost), what it was, its line, and what the actions returned after it
    // lost us while it waited, each judged before it (their windows lie inside its own)
    private record Owed(SpellAbility sa, int had, int stack, int priced, Spent kind, ComboLines.Line line, int later) {
        Owed after(final int lost) {
            return new Owed(sa, had, stack, priced, kind, line, later + lost);
        }
    }

    /** The driver's answer at a decision above an opponent's item ({@link #respond}): its next action, or, null, a
     *  pass, the stock AI playing nothing either. */
    public record Answer(SpellAbility action) {
    }

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
    // toughness left) when it was returned, judged at the next decision once no item of the ping is on the stack (in
    // response, an opponent's item may come above it first); and whether a ping that changed nothing has ended Walking
    // Ballista's volley for the phase
    private SpellAbility pingedWith;
    private GameObject pinged;
    private int pingedHad;
    private boolean volleyOver;
    // the opponent's item on the stack that a go-off in response answers (section 6.6), the removal that started it;
    // null while none does. The go-off goes on while that item is on the stack
    private SpellAbilityStackInstance respondedTo;
    // the Thrasios activation returned last: our library's size when it was returned, judged at the next decision (-1
    // when none is to be judged); and whether one that moved no card out of our library has ended Thrasios's dig for
    // the phase
    private int dugFrom = -1;
    private boolean digOver;
    // the futility gate: our hand's and battlefield's card names, each sorted, when the last go-off that spent nothing
    // ended; null when none did
    private List<List<String>> futile;
    // R30: the actions returned whose cost in life is still to be judged, the earliest first, each judged at the first
    // decision at which neither it nor anything put on the stack after it is there (the final-fix compliance review's
    // follow-up round 1). One returned while another waits goes on the stack above that one's items (in response,
    // above a new threat), so it is judged first, and what it lost us is taken out of that one's
    private final List<Owed> owed = new ArrayList<>();
    // whether responseAction's last answer was the wait of a go-off in response for its own action (waits), or the
    // Oracle line's window with no dig to take, which respond answers with a pass
    private boolean waiting;
    // the Oracle line of the phase (section 6.4 step 3, amended 2026-11-04 (the Oracle window)): the card id of the
    // Thassa's Oracle it cast, -1 while it has cast none, whose entering trigger opens its window; and the loop line
    // whose go-off cast it, the window's digs' line
    private int windowOracle = -1;
    private ComboLines.Line windowLine;

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
     * the pool holds what it needs; else, while the Oracle line can run (amended 2026-11-04 (the Oracle window)),
     * Thassa's Oracle's cast at a boundary once the pool holds what a dig of our whole library costs and Oracle's own
     * price ({@link LoopSinks#oraclePool}), when the driver's own check passes it, and the refill toward that pool
     * before, the walk never asked meanwhile; else, the walk having chosen nothing at this board, Thrasios's activation
     * once the pool holds its {4}; else the next step of the loop line the go-off runs, its activator us and its
     * targets (a cast's X) set; or null: at once outside our main phase, above a stack, once the game is over, for a
     * deck holding no loop line, while a cap or the idle rule stops the driver; when no line can run, a start gate
     * refuses, or the go-off ends; and, with nothing for Ballista, the Oracle line or Thrasios, at a boundary with the
     * pool at the target, where the stock walk plays next. {@code saList} is what the AI may play now (the walk's
     * list); the steps are read from the pieces themselves.
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
        judgeLife();
        if (!active) {
            // a go-off measures its cycles from its own first boundary
            boundaryLine = null;
            boundaryPool = -1;
        }
        final ComboLines.Line l = choose(t.view(), false);
        // a go-off that runs ends for the phase when its line is gone and when it may go on no more; one that has not
        // started yet simply does not start, and the next decision reads the gates again
        if (l == null || (active ? !goesOn(l) : !(starts(l) && goesOn(l)))) {
            if (active) {
                end();
            }
            return null;
        }
        // Walking Ballista's step first (section 6.4 step 1): its action once the pool holds what it needs, a spend at
        // a boundary only (the pool there is the loop's surplus), a ping at any decision; and while Ballista has a
        // step the driver owns every decision, refilling for it, never passing the priority to the walk between a
        // pump and the volley
        final int need = ballistaNeed(l);
        final boolean boundary = LoopCycles.boundary(ai, l);
        if (need >= 0) {
            if (LoopPool.floating(ai) >= need && (need == 0 || boundary)) {
                return ballistaStep(l, ph);
            }
            return refill(l, ph);
        }
        if (oracleLine(l)) {
            // the Oracle line (section 6.4 step 3, amended 2026-11-04 (the Oracle window)): the refill builds the pool
            // to what a dig of our whole library costs and Oracle's own price, the driver owning every decision
            // meanwhile, so that the walk neither taps the blue sources nor spends the pool; at a boundary there,
            // Oracle's cast, through the driver's own check, one it refuses for its payment (a colour the pool has not
            // made yet) waiting for the refill. Nothing is dug before Oracle resolves: the window digs (respond)
            if (boundary && LoopPool.floating(ai) >= LoopSinks.oraclePool(ai)) {
                final SpellAbility cast = spend(l, ph, LoopSinks.oracleCast(ai), Spent.ORACLE);
                if (cast != null) {
                    windowOracle = cast.getHostCard().getId();
                    windowLine = l;
                    return cast;
                }
            }
            return refill(l, ph);
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
     * Whether our hand cast {@code sa} of Thassa's Oracle waits for the Oracle line (section 6.4 step 3, amended
     * 2026-11-04 (the Oracle window): the hold, its shape unchanged, waits for the line's window, where the dig toward
     * Oracle now runs; ComboHold.vetoesCast): while a loop line of the deck can run (its pieces where its cycle needs
     * them, ready and not denied: {@link LoopCycles#ready} and the tracker's own reading), the Oracle line could run on
     * it ({@link LoopSinks#digsForOracle}: Oracle in our hand and able to win now, its {U}{U} payable, Thrasios ready)
     * and our library holds more than {@link LoopSinks#oracleThreshold} cards. Read from the board at every decision,
     * a go-off running or not, so the stock walk never casts Oracle above its threshold: the line casts it itself,
     * through its own check, and waits too while it does not fit the caps, or beside a draw trigger of ours whose draws
     * cannot be counted (amended 2026-11-04 (the Oracle window, its review's round 1): Oracle is held there while
     * the line waits, never released to the stock AI, whose cast above the threshold would not win). False for any
     * other spell, for a deck holding no loop line, and while piece 1's own Oracle go-off holds (R2): while an
     * executable piece-1 line has Oracle as its winner, the go-off (ComboExecute.goOffNow, which reads those very
     * lines) and the hold (ComboHold.oracleWaits) are piece 1's, and an immediate Consultation win is never traded for
     * a dig. Draws nothing.
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

    /**
     * The stack-response slot (AiController.getSpellAbilityToPlay's stack-response block, ahead of the counterspell
     * pick): the next action of the go-off in response or of the Oracle line's window ({@link #responseAction}), as an
     * answer; else, while that go-off waits for its own action beneath an opponent's triggered ability that threatens
     * nothing it protects (a punisher's trigger for that action), a pass, so that the stock AI plays nothing in that
     * window either: it would read the board as the driver would have, nothing pending landed (it pinged Walking
     * Ballista's last counter away while a pump waited); a pass too above the Oracle line's own trigger once the window
     * has no dig to take (amended 2026-11-04 (the Oracle window)), so that the stock AI neither casts there nor spends
     * the pool (the brainstorm's probe p2: Jin-Gitaxias, Core Augur); else null, the stock answer. Null at once for a
     * deck holding no loop line. Draws no random number.
     */
    public Answer respond() {
        final SpellAbility sa = responseAction();
        if (sa != null) {
            return new Answer(sa);
        }
        return waiting ? WAIT : null;
    }

    /**
     * Going off in response to removal (section 6.6; AiController.getSpellAbilityToPlay's stack-response block, ahead
     * of the counterspell pick: a win now beats countering the removal), with an opponent's spell or ability on the
     * stack's top: Walking Ballista's next action toward its volley once the pool holds what it needs (a pump at a
     * boundary, a ping at any decision), else the next step of the loop line that can run (executable, its pieces where
     * its cycle needs them, not failed for the phase) of an instant-speed kind, a rock untap or the flicker, the
     * highest net a cycle first, its activator us and its targets set; each through the driver's own check and the
     * caps, as in a main phase. Ballista is the only sink: Thrasios and the stock AI's plays wait for the stack. The
     * go-off in response starts when the item on top threatens a piece of such a line that can run, or a Walking
     * Ballista of ours ({@link LoopResponse#threatens}), and the volley fits in what the caps have left (R28,
     * {@link LoopResponse#volleyActions}), a count that needs a Ballista of ours on our battlefield (one in our hand is
     * not cast above the removal); it measures its cycles from its own first boundary (R21: a main-phase go-off's untap
     * left beneath the removal is paid again above it). It goes on at each decision with an opponent's item on top
     * while that removal is on the stack, neither read again: the first flicker leaves the removal no target, and the
     * volley's count, which errs high mid-cycle, is a start's (the caps stop a go-off in response as they stop one in a
     * main phase). Either way Ballista can shoot ({@link LoopSinks#ballistaCanShoot}: a kill in reach, a ping the
     * driver may make) and has a step to take toward the volley (none once a ping that changed nothing has ended it,
     * R25). While it goes on it takes one step at a time (the final-fix compliance review's follow-up round 1): at a
     * decision at which an action it returned is not yet judged and the item on top is an opponent's triggered ability,
     * not that removal, that threatens nothing it protects ({@link #waits}: a punisher's trigger for that action), it
     * waits, null, which {@link #respond} answers with a pass. Ahead of all of that, above the Oracle line's own
     * trigger (amended 2026-11-04 (the Oracle window)), the window's dig or its pass ({@link #windowAction}). Null
     * otherwise: at once with the game over, with no opponent's item on top (any other item of ours on top is the stock
     * own-top pass's), for a deck holding no loop line, and while a cap stops the driver. Draws no random number.
     * AiController asks {@link #respond}.
     */
    SpellAbility responseAction() {
        waiting = false;
        final Game game = ai.getGame();
        if (game.isGameOver()) {
            return null;
        }
        final SpellAbilityStackInstance top = game.getStack().peek();
        if (top != null && windowOf(top)) {
            return windowAction();
        }
        if (top == null || !LoopResponse.opposes(ai, top)) {
            return null;
        }
        final ComboTracker t = ComboTracker.of(ai);
        if (t == null || !holdsLoop(t)) {
            return null;
        }
        final PhaseHandler ph = game.getPhaseHandler();
        sync(ph);
        if (rounds.stoppedForGame() || rounds.stoppedForTurn(ai)) {
            return null;
        }
        judgeLast();
        judgeVolley();
        judgeLife();
        final ComboTracker.View v = t.view();
        final boolean going = stackHolds(respondedTo);
        if (going && waits(top, v)) {
            waiting = true;
            return null;
        }
        final ComboLines.Line l = going ? choose(v, true) : pick(v, true);
        if (l == null) {
            return null;
        }
        final int need = ballistaNeed(l);
        if (need < 0 || !LoopSinks.ballistaCanShoot(ai)
                || (!going && (!LoopResponse.threatens(ai, top, threatened(v))
                        || LoopResponse.volleyActions(ai, l) > rounds.left(ai)))) {
            return null;
        }
        if (!going) {
            // a go-off in response measures its cycles from its own first boundary
            respondedTo = top;
            boundaryLine = null;
            boundaryPool = -1;
            measured(l);
        }
        if (LoopPool.floating(ai) >= need && (need == 0 || LoopCycles.boundary(ai, l))) {
            return ballistaStep(l, ph);
        }
        return refill(l, ph);
    }

    // whether the go-off in response waits at this decision (the final-fix compliance review's follow-up round 1): an
    // action it returned is not yet judged (owed: it, or what was put on the stack after it, is there), and the item on
    // top is an opponent's triggered ability, not the removal the go-off answers, that threatens none of the cards it
    // protects (threatened): a punisher's trigger for that action, Harsh Mentor's. A step taken above it would read a
    // board where nothing pending has landed: Basalt Monolith tapped beneath its untap, Walking Ballista's counters and
    // the opponent's life before its pump and its ping, our life before the trigger's damage. Read before the line is
    // chosen, so that a boundary is measured (R21) only on a board where the go-off's own actions have landed. Above an
    // opponent's spell or activated ability, a new threat among them, the go-off acts at once
    private boolean waits(final SpellAbilityStackInstance top, final ComboTracker.View v) {
        return !owed.isEmpty() && top.isTrigger() && top != respondedTo
                && !LoopResponse.threatens(ai, top, threatened(v));
    }

    // whether the stack's top is the Oracle line's own trigger (section 6.6, amended 2026-11-04 (the Oracle window)): a
    // triggered ability we control, in the phase the line cast its Oracle in, whose host, as the stack holds it, is
    // that Thassa's Oracle by its card id, which a card keeps as it moves (the object it is now, on our battlefield or
    // wherever a removal has put it since, never the hand card the cast was read from), and is named Thassa's Oracle.
    // Oracle's spell on the stack is no trigger, so nothing is dug before it resolves; the trigger of an Oracle the
    // line did not cast (the stock AI's, piece 1's go-off) opens no window, and its decisions stay theirs
    private boolean windowOf(final SpellAbilityStackInstance top) {
        final Card host = top.getSourceCard();
        return windowOracle >= 0 && stampedNow() && top.isTrigger() && ai.equals(top.getActivatingPlayer())
                && host != null && host.getId() == windowOracle
                && ComboLines.Winner.ORACLE.card.equals(ComboTracker.nameOf(host));
    }

    // the Oracle line's window (section 6.6's second case, amended 2026-11-04 (the Oracle window)), its trigger on the
    // stack's top: the action returned last judged first (R21's, the dig's and R30's judges, as in a main phase), then
    // Thrasios's next activation from the floating pool (LoopSinks.windowDig: our library holds a card, a Thrasios
    // ready), through the driver's own check and the caps; else null with the wait set, which respond answers with a
    // pass, the stock AI playing nothing above the trigger: once the library is empty, and whenever the dig cannot go
    // on (its dig over for the phase, Thrasios gone, the pool short of its price, a cap reached). Each activation
    // resolves alone above the trigger before the next decision here, since the AI passes above its own item. Null, the
    // stock's, for a deck holding no loop line. No threshold is read and no cycle runs: the pool was built before
    // Oracle's cast, so a Kinnan or Basalt Monolith removed in response changes nothing
    private SpellAbility windowAction() {
        final ComboTracker t = ComboTracker.of(ai);
        if (t == null || !holdsLoop(t)) {
            return null;
        }
        final PhaseHandler ph = ai.getGame().getPhaseHandler();
        sync(ph);
        waiting = true;
        if (rounds.stoppedForGame() || rounds.stoppedForTurn(ai)) {
            return null;
        }
        judgeLast();
        judgeDig();
        judgeLife();
        final SpellAbility dig = digOver ? null : LoopSinks.windowDig(ai);
        if (dig == null || LoopPool.floating(ai) < ComboExecute.priced(ai, dig).getCMC()) {
            return null;
        }
        return thrasiosStep(windowLine, ph, dig);
    }

    /** Whether the driver returned an action this turn: ComputerUtil.preventRunAwayActivations is lifted for the rest
     *  of it (section 6.8) for the abilities whose counts the go-off inflates ({@link #liftsThrottleOn}). */
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

    /**
     * Whether ComputerUtil.preventRunAwayActivations is lifted for {@code sa} (section 6.8, amended by the final
     * review's round 1): an activated ability of a Walking Ballista, a Thrasios, Triton Hero or a Kinnan, Bonder
     * Prodigy (by printed name), whose counts the go-off inflates (its pumps, pings and digs; the stock walk's digs of
     * Kinnan on the pool), on a turn in which the driver of the AI deciding for its activator acted
     * ({@link #liftsThrottleFor}). Every other ability keeps the stock roll, which ends the stock AI's own loops, as on
     * a turn the driver did not act in: Isochron Scepter copying Dramatic Reversal, each copy untapping the rocks that
     * pay the next, would otherwise run until the game's timeout (spec section 4's anti-goal). False for null.
     */
    public static boolean liftsThrottleOn(final SpellAbility sa) {
        if (sa == null) {
            return false;
        }
        final Card host = sa.getHostCard();
        return host != null && LIFTED.contains(ComboTracker.nameOf(host))
                && liftsThrottleFor(sa.getActivatingPlayer());
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

    /**
     * Palinchron's entering untap during one of the driver's flicker or recast cycles (UntapAi.chooseSingleCard,
     * section 6.8), asked once a land, up to seven: among {@code options}, never an opponent's, our first land that
     * makes blue, else our first other land that makes mana, in the order of LoopCycles' put-back set, which the
     * boundary reads: the lands the cycle pays its blue with are put back first, and a land that makes no mana (a Maze
     * of Ith) never takes the untap of one that does. Null, the stock choice, once no land of ours that makes mana is
     * left among them (the stock then takes our other lands among them, else none); for any untap whose host is not a
     * Palinchron (its entering untap is Palinchron's only Untap); and while the go-off of this step runs no flicker or
     * recast line: Palinchron's first cast, or a flicker that saves it from removal, is the stock AI's.
     */
    public Card untapChoice(final SpellAbility sa, final Iterable<Card> options) {
        final Card host = sa.getHostCard();
        if (host == null || sa.getApi() != ApiType.Untap || !LoopCycles.PALINCHRON.equals(ComboTracker.nameOf(host))
                || !palinchronCycle()) {
            return null;
        }
        Card rest = null;
        for (final Card c : options) {
            if (!ai.equals(c.getController())) {
                continue;
            }
            if (LoopCycles.makesBlue(c)) {
                return c;
            }
            if (rest == null && LoopCycles.landMana(c) != null) {
                rest = c;
            }
        }
        return rest;
    }

    /**
     * Deadeye Navigator's own pairing as it enters (BondAi.chooseSingleCard, section 6.8): our nontoken Palinchron
     * among {@code options}, the first, when the effect's host is Deadeye and the deck holds the flicker line
     * (1409-3542), so that the pair the flicker needs is made; else null, the stock choice, which takes our best
     * creature (Hullbreaker Horror or Nyxbloom Ancient before Palinchron). A token Palinchron is never the partner: a
     * flicker would exile it for ever. When another creature enters, Deadeye alone is the option and the stock pairs it
     * (the flicker's re-pair).
     */
    public Card partnerChoice(final SpellAbility sa, final Iterable<Card> options) {
        final Card host = sa.getHostCard();
        if (host == null || !LoopCycles.DEADEYE.equals(ComboTracker.nameOf(host))) {
            return null;
        }
        final ComboTracker t = ComboTracker.of(ai);
        if (t == null || !holds(t, FLICKER_LINE)) {
            return null;
        }
        for (final Card c : options) {
            if (LoopCycles.PALINCHRON.equals(ComboTracker.nameOf(c)) && !c.isToken()) {
                return c;
            }
        }
        return null;
    }

    /**
     * Whether the stock save {@code sa} of {@code retrieval}, cards of ours ChangeZoneAi would move off our battlefield
     * to save them from what the stack threatens (its blink-to-save: a flicker, an exile until a later step, a return
     * to our hand), is refused (spec section 9's stock flicker loop, fixed by the final review's round 1 and scoped by
     * its compliance review's round 1): for a deck holding the flicker line (1409-3542), while no opponent's item on
     * the stack targets one of them as the object it is now ({@link LoopResponse#targetsNow}), when {@code sa} returns
     * them to the battlefield at once (returnsAtOnce: Deadeye Navigator's flicker), or else when an item on the stack,
     * anyone's, targets one of them by its card id, as it is now or as it was before it left the battlefield and came
     * back ({@link LoopResponse#aimedAt}), so a removal whose target is gone, or an item of ours. After one flicker
     * the removal's target is the card that left, and it fizzles; the stock threat reader compares card ids, which a
     * flicker keeps, so it would flicker the new card again, and Palinchron, whose untap pays the next flicker, without
     * end, or return to our hand a card the fizzling removal threatens no more. A removal that targets nothing (destroy
     * all) takes what is on the battlefield when it resolves: a flicker does not save the card from it, and is refused
     * (with the rare removal a new object escapes, Settle the Wreckage's exile of the attacking creatures); a return to
     * our hand (Palinchron's own {2}{U}{U}) or an exile until a later step (Nezahal, Primal Tide's) does save it, and,
     * made once, cannot loop, so it stays the stock's, but beside a removal whose target is gone. False, the stock
     * choice, for every other deck, and for a player no AI of ours decides for. Draws nothing.
     */
    public static boolean refusesBlink(final Player ai, final SpellAbility sa, final Iterable<Card> retrieval) {
        final ComboTracker t = ComboTracker.of(ai);
        if (t == null || !holds(t, FLICKER_LINE)) {
            return false;
        }
        for (final Card c : retrieval) {
            if (LoopResponse.targetsNow(ai, c)) {
                return false;
            }
        }
        if (returnsAtOnce(sa)) {
            return true;
        }
        for (final Card c : retrieval) {
            if (LoopResponse.aimedAt(ai, c)) {
                return true;
            }
        }
        return false;
    }

    // whether the save sa returns what it moves to the battlefield at once: one of its sub-abilities moves cards onto
    // the battlefield (its Destination the battlefield: Deadeye Navigator's flicker, an exile and then a return, a
    // ChangeZone or a ChangeZoneAll); never a return to a hand that stays there, nor an exile whose return waits for a
    // later step (a delayed trigger's, Nezahal, Primal Tide's)
    private static boolean returnsAtOnce(final SpellAbility sa) {
        for (SpellAbility s = sa.getSubAbility(); s != null; s = s.getSubAbility()) {
            if ("Battlefield".equals(s.getParam("Destination"))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Hullbreaker Horror's cast trigger during one of the driver's cast and bounce cycles (AiController's
     * chooseModeForAbility, section 6.8), when the spell whose cast triggered it ({@code sa}'s triggering card) is one
     * of the two pieces of the line the go-off of this step runs: a mutable list (CharmEffect.chainAbilities sorts it
     * in place) holding a copy of the mode among {@code possible} that returns a permanent on the battlefield to its
     * owner's hand, its target our card of the line's other piece on our battlefield, a tapped one first
     * (LoopCycles.bounceTarget: Sensei's Divining Top and Scroll Rack never tap, Mox Opal taps only with metalcraft);
     * the copy carries the target, so none is left on the trigger's own mode, which the engine reuses. Empty, the
     * trigger choosing nothing, when no such card is on our battlefield (R19: x-14's start, both pieces in our hand) or
     * the mode cannot target it. Null, the stock choice, for any other host's charm, for the trigger of a spell that is
     * no piece of the line, and while no cast and bounce cycle runs (R19: our own Hullbreaker's trigger outside a
     * cycle; an opponent's spell never triggers it). Draws nothing.
     */
    public List<AbilitySub> charmModes(final SpellAbility sa, final List<AbilitySub> possible) {
        final Card host = sa.getHostCard();
        if (host == null || !LoopCycles.HULLBREAKER.equals(ComboTracker.nameOf(host)) || !bounceCycle()) {
            return null;
        }
        final List<String> pieces = LoopCycles.bouncePieces(line);
        final int cast = sa.getTriggeringObject(AbilityKey.Card) instanceof Card spell
                ? pieces.indexOf(ComboTracker.nameOf(spell)) : -1;
        if (cast < 0) {
            return null;
        }
        final List<AbilitySub> modes = new ArrayList<>(1);
        final Card target = LoopCycles.bounceTarget(ai, pieces.get(1 - cast));
        if (target == null) {
            return modes;
        }
        for (final AbilitySub mode : possible) {
            if (mode.usesTargeting() && mode.getTargetRestrictions().getZone().contains(ZoneType.Battlefield)) {
                final AbilitySub bounce = (AbilitySub) mode.copy(ai);
                bounce.resetTargets();
                if (bounce.canTarget(target)) {
                    bounce.getTargets().add(target);
                    modes.add(bounce);
                }
                break;
            }
        }
        return modes;
    }

    /** Whether our hand cast {@code sa} waits because the go-off of this step runs a cast and bounce line of which it
     *  is a step (ComboHold.vetoesCast): the cycle casts its pieces itself, one a decision, and the stock walk plays at
     *  the cycle's boundary with the line's second piece in our hand. Cast by the walk, that piece would be returned
     *  and cast again by the next cycle, each time a round the idle rule reads as progress (a nonland permanent
     *  arrived, a card arrived in our hand that the pool pays for), so the go-off would never end of itself, nor would
     *  the walk's casts once a cap stopped the driver ({@link #charmModes} reads the same stamp). False while no cast
     *  and bounce cycle runs. Draws nothing. */
    public boolean holdsCycleCast(final SpellAbility sa) {
        return bounceCycle() && LoopCycles.isStep(ai, line, sa);
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
        pingedWith = null;
        pinged = null;
        volleyOver = false;
        respondedTo = null;
        dugFrom = -1;
        digOver = false;
        owed.clear();
        windowOracle = -1;
        windowLine = null;
    }

    // whether the go-off of this step runs a flicker or recast line now: active, on a line of either kind, in the
    // phase it was stamped in (stampedNow)
    private boolean palinchronCycle() {
        return active && (line.loop() == LoopKind.FLICKER || line.loop() == LoopKind.RECAST) && stampedNow();
    }

    // whether the go-off of this step runs a cast and bounce line now: active, on a line of that kind, in the phase it
    // was stamped in (stampedNow)
    private boolean bounceCycle() {
        return active && line.loop() == LoopKind.BOUNCE && stampedNow();
    }

    // whether this is the phase the go-off was stamped in: the active flag outlives its step when the go-off's last
    // decision returned nothing without ending it (a cap stopped it, or its check refused a sink's action)
    private boolean stampedNow() {
        final PhaseHandler ph = ai.getGame().getPhaseHandler();
        return ph.getTurn() == phaseTurn && ph.getPhase() == phase;
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

    // the line to run now: the best line that can run (of an instant-speed kind only, when instantOnly: a go-off in
    // response), past each whose last cycle the pool measured short (R21), which is failed for the phase
    private ComboLines.Line choose(final ComboTracker.View v, final boolean instantOnly) {
        while (true) {
            final ComboLines.Line l = pick(v, instantOnly);
            if (l == null || measured(l)) {
                return l;
            }
            failed.add(l.variant().id());
        }
    }

    // the line that can run (of an instant-speed kind only, when instantOnly): one whose cycle costs no life (R30)
    // first, then the highest net a cycle, then the lowest variant id. A rock line's net does not depend on which rock
    // is tapped, so the pick does not move mid-cycle
    private ComboLines.Line pick(final ComboTracker.View v, final boolean instantOnly) {
        ComboLines.Line best = null;
        int bestNet = 0;
        boolean bestFree = false;
        for (final ComboLines.Line l : v.lines()) {
            if (!runs(v, l) || (instantOnly && !l.loop().instantSpeed())) {
                continue;
            }
            final int net = LoopCycles.net(ai, l);
            final boolean free = cycleLife(l) == 0;
            if (best == null || (free && !bestFree) || (free == bestFree && (net > bestNet
                    || (net == bestNet && l.variant().id().compareTo(best.variant().id()) < 0)))) {
                best = l;
                bestNet = net;
                bestFree = free;
            }
        }
        return best;
    }

    // a loop line not failed this phase whose pieces are where its cycle needs them and that is executable: ready and
    // not denied, the tracker's own reading (its executable list's test, read only for these lines); and whose cycle
    // costs no life, or feeds Walking Ballista's kill this turn that our life affords (R30)
    private boolean runs(final ComboTracker.View v, final ComboLines.Line l) {
        return l.isLoop() && !failed.contains(l.variant().id()) && LoopCycles.ready(ai, l) && v.ready(l)
                && !v.denied(l) && (cycleLife(l) == 0 || ballistaNeed(l) >= 0);
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
    // back a go-off whose only spender is the stock AI), Thassa's Oracle's cast at the head of its line (oracleLine),
    // or Thrasios's dig
    private boolean starts(final ComboLines.Line l) {
        if (!l.loop().canStop()) {
            return false;
        }
        if (ballistaNeed(l) < 0 && !stockSpends(l) && !oracleLine(l) && dig(l) == null) {
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
    private boolean goesOn(final ComboLines.Line l) {
        for (final Player p : ai.getOpponents()) {
            if (!p.cantLose()) {
                return !LoopTracker.Snapshot.of(ai).isDecisive() || shotNow(l);
            }
        }
        return shotNow(l);
    }

    // whether Walking Ballista can shoot now (LoopSinks.ballistaCanShoot) and has a step to take toward it, fed by l
    private boolean shotNow(final ComboLines.Line l) {
        return ballistaNeed(l) >= 0 && LoopSinks.ballistaCanShoot(ai);
    }

    // the pool Walking Ballista's next action needs (LoopSinks.ballistaPool); -1 when Ballista has nothing to do, once
    // a ping that changed nothing (R25) or a step that cost more life than its price (R30) has ended its volley for
    // the phase, and while the volley, fed by loop line l, would pay life our life does not afford (R30, affords)
    private int ballistaNeed(final ComboLines.Line l) {
        if (volleyOver) {
            return -1;
        }
        final int pool = LoopSinks.ballistaPool(ai);
        return pool < 0 || !affords(l) ? -1 : pool;
    }

    // R30: whether Walking Ballista's volley, fed by loop line l, may pay what it costs us in life: the rest of it
    // (LoopSinks.volleyLife: its pumps, pings and cast, and l's cycles its mana needs, each priced) costs none, or,
    // with a kill in reach, less than the life we will have once what is pending has resolved (lifeAhead)
    private boolean affords(final ComboLines.Line l) {
        final long life = LoopSinks.volleyLife(ai, LoopCycles.net(ai, l), cycleLife(l));
        return life == 0 || (life < lifeAhead() && LoopSinks.ballistaCanShoot(ai));
    }

    // R30: the life we will have once what is pending has resolved (the final-fix compliance review's follow-up round
    // 1): our life less the price of each action returned that is not yet judged (owed), each taken whole though part
    // of it may have been paid already, a lower bound. In a main phase, which acts on an empty stack where every
    // action returned before has been judged, our life; in response, where the go-off acts with an action of its own
    // still to be judged only above a new threat, or above a main phase's action the removal came above, less their
    // prices: a punisher's trigger for such an action may still wait beneath the item on top
    private int lifeAhead() {
        int life = ai.getLife();
        for (final Owed o : owed) {
            life -= o.priced();
        }
        return life;
    }

    // R30: the life one cycle of loop line l costs us: its actions that are no mana ability (LoopCycles.lifeSteps),
    // each priced (lifeCost); a mana ability fires no cast or activation trigger
    private int cycleLife(final ComboLines.Line l) {
        int life = 0;
        for (final SpellAbility step : LoopCycles.lifeSteps(ai, l)) {
            life += lifeCost(ai, step);
        }
        return life;
    }

    /**
     * R30: the life action {@code sa} costs {@code ai}, as the stock AI prices a play (AiController.saSideEffects): the
     * cast and activation triggers in force that damage its activator or make them lose life
     * (ComputerUtil.getDamageForPlaying: Harsh Mentor, Burning-Tree Shaman, Eidolon of the Great Revel), and, for an
     * action that puts a permanent onto the battlefield (a permanent spell's card, the card a flicker returns:
     * {@link LoopCycles#entersWith}), the triggers of its entering (ComputerUtil.getDamageFromETB: Rampaging
     * Ferocidon); 0 while {@code ai} cannot lose life. What no trigger of a cast or an activation does (a painland's
     * own damage, a punisher's for each card drawn) is not read here: the driver measures it (judgeLife). Draws no
     * random number.
     */
    static int lifeCost(final Player ai, final SpellAbility sa) {
        if (!ai.canLoseLife()) {
            return 0;
        }
        int life = ComputerUtil.getDamageForPlaying(ai, sa);
        final Card enters = LoopCycles.entersWith(sa);
        if (enters != null) {
            life += ComputerUtil.getDamageFromETB(ai, enters);
        }
        return life;
    }

    // Walking Ballista's action now (LoopSinks.ballista), spent as a sink's (spend); a ping, its target and what it had
    // are kept for R25's judge
    private SpellAbility ballistaStep(final ComboLines.Line l, final PhaseHandler ph) {
        final SpellAbility sa = spend(l, ph, LoopSinks.ballista(ai), Spent.BALLISTA);
        if (sa != null && sa.usesTargeting()) {
            pingedWith = sa;
            pinged = sa.getTargets().get(0);
            pingedHad = had(pinged);
        }
        return sa;
    }

    // Thrasios's activation now, spent as a sink's (spend): the refill toward it is over, and the next decision is the
    // walk's again, to play what it drew; our library's size is kept for the activation's judge (judgeDig)
    private SpellAbility thrasiosStep(final ComboLines.Line l, final PhaseHandler ph, final SpellAbility dig) {
        thrasiosDue = false;
        final SpellAbility sa = spend(l, ph, dig, Spent.THRASIOS);
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

    // a sink's action now (Walking Ballista's, Thrasios's, Thassa's Oracle's cast: kind), through the driver's own
    // check and the caps: it opens a round the idle rule does not judge (each has stops of its own), it is something
    // spent, and the next boundary is measured afresh (R21: a spend at a boundary is no cycle); its cost in life is
    // judged at a later decision (R30, judgeLife). An action the check refuses is not taken and ends nothing (null):
    // Ballista's and Thrasios's steps are read through the same check before they come here, and Oracle's cast,
    // refused for its price, waits for the refill (mainPhaseAction)
    private SpellAbility spend(final ComboLines.Line l, final PhaseHandler ph, final SpellAbility sa,
            final Spent kind) {
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
        arm(sa, kind, lifeCost(ai, sa), l);
        actedTurn = ph.getTurn();
        return sa;
    }

    // the cycle's next step (LoopCycles.next), a refill, through the driver's own check and the caps; a step the check
    // refuses (a hoser arrived) ends the go-off for the phase. A step that costs us life (R30, lifeCost) is taken only
    // toward Walking Ballista's kill this turn that our life affords; otherwise its line fails for the phase, and the
    // next decision falls to another line, or to the walk. Its cost in life is judged at a later decision (judgeLife)
    private SpellAbility refill(final ComboLines.Line l, final PhaseHandler ph) {
        final SpellAbility next = LoopCycles.next(ai, l);
        final int life = next == null ? 0 : lifeCost(ai, next);
        if (life > 0 && ballistaNeed(l) < 0) {
            failed.add(l.variant().id());
            return null;
        }
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
        arm(next, Spent.CYCLE, life, l);
        actedTurn = ph.getTurn();
        return next;
    }

    // R30: the judge of sa's cost in life, armed as the driver returns it: our life and the stack's size now, its
    // price, what it is; owed after any action returned before it that is not yet judged, never in its place (the
    // final-fix compliance review's follow-up round 1)
    private void arm(final SpellAbility sa, final Spent kind, final int priced, final ComboLines.Line l) {
        owed.add(new Owed(sa, ai.getLife(), ai.getGame().getStack().size(), priced, kind, l, 0));
    }

    // R30: each action returned, judged at the first decision at which neither it nor anything put on the stack after
    // it is there, the stack no taller than when it was returned (in a main phase empty again; in response the removal
    // beneath): it and all it triggered have resolved, a punisher's trigger for the card a draw of ours drew among
    // them, which comes to the stack after the draw. The latest first: one returned while an earlier one waited went on
    // the stack above that one's items (in response, above a new threat), so its window lies inside the earlier one's,
    // and what it lost us is taken out of the earlier one's. One that cost us more life than its price (a painland's
    // own damage, a punisher's for each card we draw, which no price reads) fails its line for the phase, if it was a
    // cycle's step, and ends Walking Ballista's volley or Thrasios's dig for the phase, if it was theirs
    private void judgeLife() {
        while (!owed.isEmpty()) {
            final Owed o = owed.get(owed.size() - 1);
            if (stackHolds(o.sa()) || ai.getGame().getStack().size() > o.stack()) {
                return;
            }
            owed.remove(owed.size() - 1);
            final int lost = o.had() - ai.getLife();
            if (lost - o.later() > o.priced()) {
                if (o.kind() == Spent.CYCLE) {
                    failed.add(o.line().variant().id());
                } else if (o.kind() == Spent.BALLISTA) {
                    volleyOver = true;
                } else if (o.kind() == Spent.THRASIOS) {
                    digOver = true;
                }
            }
            if (!owed.isEmpty()) {
                owed.set(owed.size() - 1, owed.get(owed.size() - 1).after(lost));
            }
        }
    }

    // whether the Oracle line runs on loop line l now (section 6.4 step 3, amended 2026-11-04 (the Oracle window)):
    // Oracle in our hand able to win now, its {U}{U} payable and a Thrasios ready (LoopSinks.digsForOracle); we can
    // win; no mandatory draw trigger of ours on our battlefield draws what no floor can count
    // (LoopSinks.drawsUncounted, amended 2026-11-04 (the Oracle window, its review's round 1): Nezahal, Primal Tide's,
    // which any noncreature spell an opponent cast in the window would set off on our emptied library); Thrasios's dig
    // is not over for the phase (judgeDig, R30), so that its window can dig; Oracle's cast is one the driver may make
    // now, at a price in life below what we will have (mayTake: a build for a cast the check would refuse for good is
    // never begun); and its whole cost from here fits what the caps have left (windowFits), read at every decision: at
    // a boundary the count is exact for a rock line, and mid-cycle, where it errs high by a cycle, a line that reads
    // as no fit costs nothing, the go-off refilling either way. Otherwise the line waits, Oracle held (holdsOracle),
    // and at a boundary the walk takes the pool built so far
    private boolean oracleLine(final ComboLines.Line l) {
        if (digOver || !LoopSinks.digsForOracle(ai, l) || ai.cantWin() || LoopSinks.drawsUncounted(ai)) {
            return false;
        }
        final SpellAbility cast = LoopSinks.oracleCast(ai);
        return cast != null && mayTake(cast) && windowFits(l);
    }

    // R28's shape for the Oracle line (THE RULE's fit, section 6.4 step 3, amended 2026-11-04 (the Oracle window)): the
    // actions it takes from this decision, every one the driver returns, at most what the caps have left
    // (LoopRounds.left): the cycles of loop line l that bring the pool to Oracle's (LoopSinks.oraclePool) at its net a
    // cycle (LoopCycles.net), each LoopCycles.actions; Oracle's cast; and one Thrasios activation for each card in our
    // library. At a boundary the pool floating there counts; mid-cycle it does not, and the cycle in progress counts in
    // full, so the count is never short. False for a line that nets nothing
    private boolean windowFits(final ComboLines.Line l) {
        final int pool = LoopSinks.oraclePool(ai);
        final int net = LoopCycles.net(ai, l);
        if (pool < 0 || net <= 0) {
            return false;
        }
        final boolean boundary = LoopCycles.boundary(ai, l);
        final long lacking = pool - (boundary ? LoopPool.floating(ai) : 0L);
        final long cycles = (lacking > 0 ? (lacking + net - 1) / net : 0) + (boundary ? 0 : 1);
        final long actions = cycles * LoopCycles.actions(ai, l) + 1 + ai.getCardsIn(ZoneType.Library).size();
        return actions <= rounds.left(ai);
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

    // R25: the ping returned last, judged at the first decision at which no item of it is on the stack any more
    // (resolved, countered or fizzled: in a main phase the stack it went on is empty again; in response an opponent's
    // item may come above it first, and the judge waits): one that changed neither its target player's life nor its
    // target creature's toughness left (a Fog, Teferi's Protection, a prevention shield) ends Walking Ballista's volley
    // for the phase, so Ballista takes no more step in it. No margin otherwise: a ping that landed is followed by a
    // fresh count at the next decision
    private void judgeVolley() {
        if (pinged == null || stackHolds(pingedWith)) {
            return;
        }
        if (had(pinged) == pingedHad) {
            volleyOver = true;
        }
        pingedWith = null;
        pinged = null;
    }

    // whether the stack holds an item of sa: sa itself, or the copy the engine made of it to put it there, whose
    // original it is (MagicStack.add); false for none
    private boolean stackHolds(final SpellAbility sa) {
        if (sa == null) {
            return false;
        }
        for (final SpellAbilityStackInstance si : ai.getGame().getStack()) {
            final SpellAbility on = si.getSpellAbility();
            if (on == sa || on.getOriginalAbility() == sa) {
                return true;
            }
        }
        return false;
    }

    // whether the stack holds that very item; false for none
    private boolean stackHolds(final SpellAbilityStackInstance item) {
        if (item == null) {
            return false;
        }
        for (final SpellAbilityStackInstance si : ai.getGame().getStack()) {
            if (si == item) {
                return true;
            }
        }
        return false;
    }

    // the cards an opponent's item on the stack must threaten for a go-off in response to start (section 6.6): ours on
    // our battlefield of a piece of any loop line of an instant-speed kind that can run (runs), whichever runs, and our
    // Walking Ballistas there
    private List<Card> threatened(final ComboTracker.View v) {
        final List<String> pieces = new ArrayList<>();
        for (final ComboLines.Line l : v.lines()) {
            if (runs(v, l) && l.loop().instantSpeed()) {
                pieces.addAll(l.names());
            }
        }
        final List<Card> out = new ArrayList<>();
        for (final Card c : ai.getCardsIn(ZoneType.Battlefield)) {
            final String name = ComboTracker.nameOf(c);
            if (pieces.contains(name) || LoopSinks.BALLISTA.equals(name)) {
                out.add(c);
            }
        }
        return out;
    }

    // Thrasios's activation returned last, judged now that the stack it went on is empty again (resolved or
    // countered): one that moved no card out of our library, where each that resolves moves its top card (onto our
    // battlefield, else into our hand), ends Thrasios's dig for the phase, so Thrasios takes no more activation in it
    // (R21: a step that gains nothing never burns the caps; the {4} it spent is no gain). A draw limit stops it so
    // (Spirit of the Labyrinth, Leovold, Emissary of Trest, Narset, Parter of Veils: a second draw in a turn draws
    // nothing), as does a countered activation; the plain dig, the dig to empty and the Oracle line's window alike
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

    // the driver's own check of an action it returns (the plan's Calls): one it may take (mayTake), its cost as the
    // engine prices it now paid by the pool and our untapped lands, and each target it has set one it can target. A
    // skipped action is R21's to read (judgeLast), which fails its line, not the go-off
    private boolean passes(final SpellAbility sa) {
        if (!mayTake(sa)) {
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

    // the part of the driver's own check that no pool can change: allowed, and not priced at the whole of the life we
    // will have once what is pending has resolved, or more (R30, lifeAhead: the stock refusal,
    // AiController.saSideEffects', which the driver's own pricing narrows)
    private boolean mayTake(final SpellAbility sa) {
        if (!allowed(ai, sa)) {
            return false;
        }
        final int life = lifeCost(ai, sa);
        return life <= 0 || life < lifeAhead();
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
