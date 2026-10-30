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
 * cycle, then the variant id) starts a go-off when its kind can stop, some opponent can lose, the board is not five
 * times lethal already, there is something to spend the mana on ({@link LoopPool#stockTarget} above 0) and our hand
 * and battlefield are not those of the last go-off that spent nothing (the futility gate). Then each decision returns
 * the cycle's next step ({@link LoopCycles#next}) until, at a boundary, the pool holds the target, where the driver
 * passes the decision to the stock AI's walk, which plays on the pool ({@link #noteStockPlay}); when it plays nothing
 * the go-off ends ({@link #afterStockWalk}). Every action passes the driver's own check (playable, not refused by a
 * static, its cost paid by the pool and our untapped lands, its targets legal; never canPlayAndPayFor, whose throttle
 * and stock judges would refuse it) and counts toward the caps ({@link LoopRounds}).
 *
 * <p>A step that fails or gains nothing never burns the caps (R21): at the next decision an action that was skipped
 * (its payment failed) or changed nothing among the pool, our hand, battlefield and stack fails its line for the
 * phase, and so does a cycle that raised the pool by less than 1 from one boundary to the next (a cost tax, Damping
 * Sphere: what the printed net cannot see). The driver then falls to its next line, or to the stock walk. A check
 * that fails mid-go-off (a hoser arrived) ends the go-off for the phase, as does losing its line, the board reaching
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
    private ComboLines.Line line;
    private final List<String> failed = new ArrayList<>();
    // R21: the action returned last, its line and the state when it was returned, judged at the next decision
    private SpellAbility last;
    private ComboLines.Line lastLine;
    private int[] lastState;
    // R21: the pool at the last boundary of this line, -1 when there is none to compare with (the go-off has not passed
    // one yet, or the stock AI spent since)
    private ComboLines.Line boundaryLine;
    private int boundaryPool = -1;
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
     * The go-off slot (AiController.getSpellAbilityToPlay, after piece 1's go-off): the next step of the loop line
     * the go-off runs, its activator us and its targets set, or null: at once outside our main phase, above a stack,
     * once the game is over, for a deck holding no loop line, while a cap or the idle rule stops the driver; when no
     * line can run, a start gate refuses, or the go-off ends; and at a boundary with the pool at the target, where the
     * stock walk plays next. {@code saList} is what the AI may play now (the walk's list); the steps are read from the
     * pieces themselves.
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
        if (LoopCycles.boundary(ai, l) && LoopPool.floating(ai) >= target(l)) {
            active = true;
            line = l;
            handedToWalk = true;
            return null;
        }
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

    /** When the walk chose nothing, after this decision's {@link #mainPhaseAction} passed it the priority at the
     *  target: the go-off ends, and when nothing was spent in it, our hand's and battlefield's names are kept, so the
     *  same futile build is not started again until one of them changes (section 6.2). Null: nothing of the driver's
     *  own to play yet (Task B6 adds Thrasios here). Inert otherwise. */
    public SpellAbility afterStockWalk() {
        if (!handedToWalk) {
            return null;
        }
        handedToWalk = false;
        if (!spent) {
            futile = fingerprint();
        }
        active = false;
        line = null;
        return null;
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
        line = null;
        failed.clear();
        last = null;
        lastLine = null;
        lastState = null;
        boundaryLine = null;
        boundaryPool = -1;
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

    // the start gates (section 6.2), read while no go-off runs this phase: the kind can stop, something to spend the
    // mana on, and our hand and battlefield not those of the last go-off that spent nothing
    private boolean starts(final ComboLines.Line l) {
        if (!l.loop().canStop() || LoopPool.stockTarget(ai, l) <= 0) {
            return false;
        }
        if (futile != null && futile.equals(fingerprint())) {
            return false;
        }
        spent = false;
        return true;
    }

    // a go-off goes on while some opponent can lose (section 6.2) and our board is not five times lethal already
    // (section 6.5, LoopTracker's decisive)
    private boolean goesOn() {
        boolean canLose = false;
        for (final Player p : ai.getOpponents()) {
            if (!p.cantLose()) {
                canLose = true;
                break;
            }
        }
        return canLose && !LoopTracker.Snapshot.of(ai).isDecisive();
    }

    // the pool a refill builds to while line l runs: the stock AI's view (section 6.3)
    private int target(final ComboLines.Line l) {
        return LoopPool.stockTarget(ai, l);
    }

    // the driver's own check of an action it returns (the plan's Calls): playable now, not refused by a
    // CantBeActivated or CantBeCast static (a spell read on an LKI copy on the stack, as AiController.canPlaySa reads
    // it), its cost as the engine prices it now paid by the pool and our untapped lands, and each target it has set one
    // it can target. A skipped action is R21's to read (judgeLast), which fails its line, not the go-off
    private boolean passes(final SpellAbility sa) {
        sa.setActivatingPlayer(ai);
        if (!sa.canPlay() || !sa.checkRestrictions(restrictionHost(sa), ai)) {
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
    private Card restrictionHost(final SpellAbility sa) {
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
