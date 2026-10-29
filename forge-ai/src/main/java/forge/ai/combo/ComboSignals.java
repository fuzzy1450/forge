package forge.ai.combo;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.google.common.collect.Iterables;

import forge.ai.ComputerUtilCombat;
import forge.ai.ComputerUtilMana;
import forge.ai.combo.ComboTracker.Where;
import forge.card.mana.ManaCost;
import forge.game.Game;
import forge.game.GameEntity;
import forge.game.ability.AbilityKey;
import forge.game.ability.AbilityUtils;
import forge.game.ability.ApiType;
import forge.game.card.Card;
import forge.game.card.CardCopyService;
import forge.game.card.CardLists;
import forge.game.combat.Combat;
import forge.game.combat.CombatUtil;
import forge.game.cost.Cost;
import forge.game.cost.CostPartMana;
import forge.game.keyword.Keyword;
import forge.game.phase.PhaseHandler;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.replacement.ReplacementEffect;
import forge.game.replacement.ReplacementType;
import forge.game.spellability.SpellAbility;
import forge.game.staticability.StaticAbility;
import forge.game.staticability.StaticAbilityAttackRestrict;
import forge.game.staticability.StaticAbilityBlockRestrict;
import forge.game.staticability.StaticAbilityCantBeCast;
import forge.game.staticability.StaticAbilityDisableTriggers;
import forge.game.staticability.StaticAbilityMode;
import forge.game.trigger.Trigger;
import forge.game.trigger.TriggerType;
import forge.game.zone.ZoneType;

/**
 * Evan's four abandon signals (spec 2026-10-22-combo-aware-ai-core-design.md section 6.5): while one holds, a line is
 * not chased. Nothing is held, protected or fetched for it, and it is live again once the signal clears.
 *
 * <p>Each signal is read from what a player can see, at the moment it is asked, through the decision's
 * {@link ComboTracker.View}: the inputs no line changes (whether we can win, the
 * danger, the surer kill, next turn's mana, the CantBeCast statics on the board and whether one is in force) are read
 * once for every line the decision asks about, and nothing outlives the decision. A denial is read at the line's own
 * window ({@link #deniedAtWindow}: each piece still to be cast at its moment there, on the turn the line would go off,
 * from the same visible statics), which holding, fetching and protection read, and now ({@link #deniedNow}), which a
 * window open now reads. Nothing here draws a random number or
 * reads the library's order: the stock danger and lethal readers both draw (aiLifeInDanger through the blocker's
 * lifeInDanger roll, doAssault and possibleNonCombatDamage through canPayCost's test payments), so DANGER and
 * SURER_KILL are private floors in the AdNauseam.floor idiom, damageIfUnblocked without abilities.
 */
public final class ComboSignals {
    /** Why a line is not chased, in the order {@link #abandon} reads them. DENIED, read at the line's own window
     *  ({@link #deniedAtWindow}), stops holding, protecting and fetching, and keeps the line from the executable ones;
     *  each execution reads its own moment besides (a window open now the denial now, the go-off its window this
     *  turn). The other three stop holding, protecting and fetching, never execution (a win now beats surviving, and
     *  each execution window checks its own mana). */
    public enum Signal { DENIED, NO_MANA, DANGER, SURER_KILL }

    private ComboSignals() {
    }

    /** The first signal that holds for {@code l}, in {@link Signal} order, or null: chase it. */
    public static Signal abandon(final Player ai, final ComboLines.Line l, final ComboTracker t) {
        return abandon(t.view(), l);
    }

    /** {@link #abandon} read through the decision's view. */
    static Signal abandon(final ComboTracker.View v, final ComboLines.Line l) {
        return abandon(v, l, v.denied(l));
    }

    /**
     * {@link #abandon} for a Laboratory Maniac or Jace line whose win comes in our next turn's main phase, the emptier
     * and a draw spell right after it there (follow-up 3, H3; ComboFetch's win in time under danger, for a piece put on
     * top of our library at the end step before our turn): its denial is read at that main-phase window
     * ({@link #deniedAtMainNextTurn}), not at that end step, the line's own window, which the piece comes too late for.
     * The other three signals as {@link #abandon} reads them.
     */
    static Signal abandonAtMainNextTurn(final ComboTracker.View v, final ComboLines.Line l) {
        return abandon(v, l, deniedAtMainNextTurn(v, l));
    }

    // the first signal that holds for l, in Signal order, its denial (signal 2) given
    private static Signal abandon(final ComboTracker.View v, final ComboLines.Line l, final boolean denied) {
        if (denied) {
            return Signal.DENIED;
        }
        if (noMana(v, l)) {
            return Signal.NO_MANA;
        }
        if (v.danger()) {
            return Signal.DANGER;
        }
        if (v.surerKill()) {
            return Signal.SURER_KILL;
        }
        return null;
    }

    /**
     * Signal 2, the opponents can deny the line, read from visible state at the line's own window
     * ({@link #deniedAtWindow}): we cannot win, a piece still to be cast cannot be cast at its moment in the window
     * (Teferi, Time Raveler or Rule of Law against the go-off in response to Oracle's trigger, the opponent's Grand
     * Abolisher against the opponent's end step, Teferi against Fblthp's trigger), Oracle's entering trigger is
     * disabled, a winner of ours in play has lost its win.
     *
     * <p>Every opponent unable to lose denies nothing (follow-up 7, L1, Evan's decision of 2026-10-28: spec section
     * 6.5's "opponents cannot lose" struck). Every winner of piece 1 wins through WinsGame, whose
     * Player.altWinBySpellEffect reads only the winner's cantWin; the opponents then lose for
     * GameLossReason.OpponentWon, for which Player.loseConditionMet runs no GameLoss replacement (its guard,
     * {@code state != GameLossReason.OpponentWon}, Player.java:1990). So the opponent's Lich's Mastery stops no such
     * win, and the game ends at once (CR 104.1). Two engine comments still say that a player who cannot lose keeps the
     * game going, GameAction.checkGameOverCondition's "they cannot lose!" and Player.hasWon's "the game appears to
     * continue": that guard has made both dead. A card with both halves (Platinum Angel, Angel's Grace) still denies,
     * through our cantWin. The other two cantLose reads stay, where a loss of life is what kills: the danger's (we
     * cannot die while we cannot lose) and the surer kill's (lethal combat or burn kills no opponent who cannot lose).
     */
    public static boolean denied(final Player ai, final ComboLines.Line l, final ComboTracker t) {
        return t.view().denied(l);
    }

    /**
     * Signal 2 at the line's own window (follow-up 2, G2; spec section 6.5, "Denial at the line's own window"), the
     * denial that live and executable read, so that holding, fetching and protection follow the moment the line would
     * fire, never this instant: we cannot win (every opponent unable to lose is no denial: {@link #denied}); a piece of
     * the line still to be cast cannot be cast at its moment in the window ({@link #barredAtWindow}); Thassa's Oracle's
     * entering trigger is disabled; a winner of ours on the battlefield has lost its win, Doctor Doom's {T} draw read
     * at Doom's window, our turn ({@link #disarmedAtWindow}).
     */
    static boolean deniedAtWindow(final ComboTracker.View v, final ComboLines.Line l) {
        if (v.cantWin()) {
            return true;
        }
        if (barredAtWindow(v, l)) {
            return true;
        }
        if (l.winner() == ComboLines.Winner.ORACLE && v.oracleTriggerDisabled()) {
            return true;
        }
        return disarmedAtWindow(v, l.winner());
    }

    /**
     * Signal 2 for a Laboratory Maniac or Jace line at its main-phase window on our next turn (follow-up 3, H3): the
     * emptier cast in our Main 1 and a draw spell right after it, the window that a piece put on top of our library at
     * the end step before our turn opens (ComboFetch's win in time under danger). As {@link #deniedAtWindow}, but each
     * piece still to be cast is read in our main phase on an empty stack, a sorcery's moment, nothing of this turn's
     * carried over: the winner still to be cast after no spell, then the emptier after it. So the opponent's Grand
     * Abolisher (in its owner's turn only) and Teferi, Time Raveler (away from a sorcery's moment) bar neither there,
     * though both bar that end step. The draw spell's own bar is the win in time's ({@link #drawBarredAt}).
     */
    static boolean deniedAtMainNextTurn(final ComboTracker.View v, final ComboLines.Line l) {
        if (v.cantWin()) {
            return true;
        }
        final ComboLines.Winner w = l.winner();
        final Player ai = v.ai();
        final Card winner = unplayed(v, w.card);
        if (winner != null && barredAt(v, winner, w.card, ai, PhaseType.MAIN1, true, List.of())) {
            return true;
        }
        final String name = l.emptier().card;
        final Card emptier = unplayed(v, name);
        if (emptier != null && barredAt(v, emptier, name, ai, PhaseType.MAIN1, true,
                winner == null ? List.of() : List.of(winner))) {
            return true;
        }
        return disarmedAtWindow(v, w);
    }

    /**
     * Whether our follow-up draw spell {@code draw}, a spell of a card in our hand, cannot be cast at its moment
     * (follow-up 3, H3): right after the emptier, in {@code turn}'s {@code step}, {@code sorcery} whether a sorcery
     * could be cast then, after {@code castThatTurn}, the spells cast that turn before it, the emptier among them.
     * The CantBeCast statics are read as at a piece's moment in a line's window (barredAt, barsAt): Rule of Law and
     * Deafening Silence count the emptier, the opponent's Grand Abolisher bars only its owner's turn, Teferi, Time
     * Raveler only away from a sorcery's moment. Read on a copy of its cast from our hand, made only while some
     * CantBeCast static exists (the view's castBarStatics, else the card's own); the card is read, never written.
     */
    static boolean drawBarredAt(final ComboTracker.View v, final SpellAbility draw, final Player turn,
            final PhaseType step, final boolean sorcery, final List<Card> castThatTurn) {
        return barredAt(v, draw.getHostCard(), draw, false, turn, step, sorcery, castThatTurn);
    }

    /** Signal 2 as read now, the moment of a window that is open now (the view keeps it for a deck line): we cannot
     *  win (every opponent unable to lose is no denial: {@link #denied}); a piece we have not yet put on the
     *  battlefield cannot be cast now, its first spell barred by a CantBeCast static (Void Winnower's even mana
     *  values); Thassa's Oracle's entering trigger is disabled (Torpor Orb, Hushbringer); a winner of ours on the
     *  battlefield has lost its win ({@link #disarmed}: Imprisoned in the Moon, Lignify, Humility, a face-down copy),
     *  or Doctor Doom's {T} draw cannot be activated (Linvala, Keeper of Silence). */
    static boolean deniedNow(final ComboTracker.View v, final ComboLines.Line l) {
        if (v.cantWin()) {
            return true;
        }
        for (final String name : l.names()) {
            if (v.castBarred(name)) {
                return true;
            }
        }
        if (l.winner() == ComboLines.Winner.ORACLE && v.oracleTriggerDisabled()) {
            return true;
        }
        return v.disarmed(l.winner());
    }

    // a winner of ours on the battlefield without its win right now, read from what it has, never its name (the
    // tracker's name is the printed card's, which an ability-removing effect leaves as it is): Laboratory Maniac or
    // Jace without the Draw replacement that wins instead; Doctor Doom without its {T} draw that wins, or with that
    // draw barred (Linvala); Fblthp without its combat-damage trigger, unless one of its triggers is on our stack
    // already, which resolves whatever Fblthp has now. Thassa's Oracle's own reading is oracleTriggerDisabled and the
    // tracker's spent-Oracle rule. A winner in our hand or library is not read: it has its win when it enters.
    static boolean disarmedNow(final ComboTracker.View v, final ComboLines.Winner w) {
        if (w == ComboLines.Winner.ORACLE || v.where(w.card) != Where.BATTLEFIELD) {
            return false;
        }
        final Card c = v.card(w.card);
        if (w == ComboLines.Winner.DOOM) {
            final SpellAbility draw = doomsDraw(c);
            return draw == null || StaticAbilityCantBeCast.cantBeActivatedAbility(draw, c, v.ai());
        }
        if (w == ComboLines.Winner.FBLTHP) {
            return !v.triggerOnOurStack(w.card) && !hasCombatDamageTrigger(c);
        }
        return !winsOnDraw(c);
    }

    // a winner of ours on the battlefield without its win at its window (G2): disarmedNow, but Doctor Doom's {T} draw,
    // barred now, is read again at Doom's window, our main phase, as StaticAbilityCantBeCast.cantBeActivatedAbility
    // reads the CantBeActivated statics, each one's conditions at that moment (conditionsAt): a bar in force now only
    // in its controller's turn (the opponent's Grand Abolisher, Myrel) or only in combat does not reach it there. What
    // the other winners have no turn changes. A bar not in force now is in force at the window in no card of the tree
    static boolean disarmedAtWindow(final ComboTracker.View v, final ComboLines.Winner w) {
        if (w != ComboLines.Winner.DOOM || !v.disarmed(w)) {
            return v.disarmed(w);
        }
        final Card doom = v.card(w.card);
        final SpellAbility draw = doomsDraw(doom);
        if (draw == null) {
            return true;
        }
        final Player ai = v.ai();
        for (final Card ca : ai.getGame().getCardsIn(ZoneType.STATIC_ABILITIES_SOURCE_ZONES)) {
            for (final StaticAbility st : ca.getStaticAbilities()) {
                if (st.checkMode(StaticAbilityMode.CantBeActivated) && conditionsAt(st, ai, PhaseType.MAIN1)
                        && StaticAbilityCantBeCast.applyCantBeActivatedAbility(st, draw, doom, ai)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Whether {@code c} has a Draw replacement that wins the game instead (Laboratory Maniac's and Jace's). */
    static boolean winsOnDraw(final Card c) {
        for (final ReplacementEffect re : c.getReplacementEffects()) {
            if (re.getMode() != ReplacementType.Draw) {
                continue;
            }
            final SpellAbility with = re.getOverridingAbility();
            if (with != null ? wins(with) : (re.hasParam("ReplaceWith")
                    && c.getSVar(re.getParam("ReplaceWith")).contains("WinsGame"))) {
                return true;
            }
        }
        return false;
    }

    // Doctor Doom's own {T} draw: an activated, non-mana ability with a tap cost whose root draws and one of whose
    // parts wins the game; null when it has none (an ability-removing effect)
    private static SpellAbility doomsDraw(final Card doom) {
        for (final SpellAbility ab : doom.getSpellAbilities()) {
            if (ab.isActivatedAbility() && !ab.isManaAbility() && ab.getApi() == ApiType.Draw
                    && ab.getPayCosts() != null && ab.getPayCosts().hasTapCost() && wins(ab)) {
                return ab;
            }
        }
        return null;
    }

    // Fblthp's "when one or more of your opponents are dealt combat damage during your turn" trigger
    private static boolean hasCombatDamageTrigger(final Card c) {
        for (final Trigger tr : c.getTriggers()) {
            if (tr.getMode() == TriggerType.DamageAll && "True".equals(tr.getParam("CombatDamage"))) {
                return true;
            }
        }
        return false;
    }

    // the ability or one of its sub-abilities wins the game
    private static boolean wins(final SpellAbility sa) {
        for (SpellAbility s = sa; s != null; s = s.getSubAbility()) {
            if (s.getApi() == ApiType.WinsGame) {
                return true;
            }
        }
        return false;
    }

    // a piece in our hand, library or command zone whose first spell a CantBeCast static bars, read as the cast reads
    // it (AiController.canPlayAndPayForFace's idiom): on an LKI copy last known on the stack, its castFrom the zone the
    // spell comes from, our hand for a piece in our hand or library (a tutor or a draw brings a library piece to the
    // hand first) and the command zone for one there. Read on the card itself, an uncast card has no castFrom, so
    // Drannith Magistrate's "Card.!wasCastFromTheirHand" would bar every piece. One on our battlefield or our stack has
    // been cast already. The copy is made only while some CantBeCast static is in force (cantBeCastAbility's own
    // scan, read once a decision: the view's castBarsInForce, then the card's own), and takes the castSA
    // cantBeCastAbility writes: the card itself is never written.
    static boolean castBarredNow(final ComboTracker.View v, final String name) {
        final Card c = unplayed(v, name);
        if (c == null) {
            return false;
        }
        final SpellAbility spell = Iterables.getFirst(c.getSpells(), null);
        if (spell == null || (!v.castBarsInForce() && !barsCasts(c))) {
            return false;
        }
        final Player ai = v.ai();
        return StaticAbilityCantBeCast.cantBeCastAbility(spell, castCopy(ai, c, v.where(name) == Where.COMMAND), ai);
    }

    // the CantBeCast statics of the cards in a static-ability source zone, whatever their conditions now
    // (cantBeCastAbility's scan, before it reads them): one pass over those zones, read once a decision
    static List<StaticAbility> castBarStaticsNow(final Game game) {
        List<StaticAbility> out = null;
        for (final Card ca : game.getCardsIn(ZoneType.STATIC_ABILITIES_SOURCE_ZONES)) {
            for (final StaticAbility st : ca.getStaticAbilities()) {
                if (st.checkMode(StaticAbilityMode.CantBeCast)) {
                    if (out == null) {
                        out = new ArrayList<>(2);
                    }
                    out.add(st);
                }
            }
        }
        return out == null ? List.of() : List.copyOf(out);
    }

    // whether one of those statics is in force now, its conditions met (cantBeCastAbility's checkConditions)
    static boolean castBarsInForceNow(final List<StaticAbility> bars) {
        for (final StaticAbility st : bars) {
            if (st.checkConditions()) {
                return true;
            }
        }
        return false;
    }

    private static boolean barsCasts(final Card ca) {
        for (final StaticAbility st : ca.getStaticAbilities()) {
            if (st.checkConditions(StaticAbilityMode.CantBeCast)) {
                return true;
            }
        }
        return false;
    }

    // the cast bars of the line at its own window (G2). A window open now, our Oracle's or Fblthp's trigger on our
    // stack, reads every piece still to be cast now, as deniedNow does. Otherwise the window as the board opens it
    // (barredAtWindowNow: this turn while it can still open this turn, else the next turn); and when that is barred,
    // a window we open by a cast of our own (Thassa's Oracle's, Laboratory Maniac's, Jace's, Doctor Doom's) is read
    // again as on the next turn the line could run, this turn's spells not carried over: the line waits for that
    // turn, so a tutor resolving under Deafening Silence still fetches for it, and Oracle is held for it. Fblthp's
    // window is not ours to put off: its trigger comes on combat damage, and resolving unanswered it shuffles Fblthp
    // away, so a trigger that can come this turn is the window (Rule of Law, a hasty Fblthp cast this turn); one that
    // cannot (a summoning-sick Fblthp, nothing of ours able to attack) leaves the next turn's reading (opensThisTurn)
    private static boolean barredAtWindow(final ComboTracker.View v, final ComboLines.Line l) {
        if (windowNow(v, l)) {
            for (final String name : l.names()) {
                if (v.castBarred(name)) {
                    return true;
                }
            }
            return false;
        }
        if (!v.windowBarred(l)) {
            return false;
        }
        return l.winner() == ComboLines.Winner.FBLTHP || !opensThisTurn(v, l) || barredOn(v, l, false);
    }

    /**
     * Signal 2 at the window, on the turn the board opens it (follow-up F3; follow-up 2, G2, for every piece still to
     * be cast and for Doctor Doom's and Fblthp's windows too): whether a piece of {@code l} still to be cast, ours in
     * our hand, library or command zone, cannot be cast at its moment in the line's window, read this turn while the
     * board can still open the window this turn ({@link #opensThisTurn}: the spells cast this turn count there; for
     * Fblthp, while its combat-damage trigger can still come this turn), else as on the next turn it could run (none
     * carried over); {@link #deniedNow} reads them now. The CantBeCast statics
     * are read as StaticAbilityCantBeCast reads them ({@link #barsAt}), with the moment's turn, step, stack and spells
     * cast:
     * <ul>
     * <li>a Thassa's Oracle line: Oracle in our main phase on an empty stack, a sorcery's moment, after that turn's
     * spells; the emptier in response to Oracle's entering trigger, on the stack (OnlySorcerySpeed bars), after that
     * turn's spells and Oracle;
     * <li>a Laboratory Maniac or Jace line: the emptier at the end step of the player whose turn comes right before
     * ours, our draw step next (no main phase; that player's turn), after that turn's spells. A draw spell that follows
     * the emptier in our main phase opens a window now, which ComboExecute reads with deniedNow (the view's
     * executableNow) and the draw spell's own bar ({@link #drawBarredAt}); danger's win in time next turn reads that
     * main-phase window ({@link #deniedAtMainNextTurn});
     * <li>a Doctor Doom line: the emptier in our main phase on an empty stack, a sorcery's moment, then Doom's {T};
     * <li>a Fblthp line: the emptier in response to Fblthp's combat-damage trigger, in our combat damage step;
     * <li>a winner other than Oracle still to be cast: in our main phase on an empty stack, on a turn of ours before
     * its window's, after no spell (Laboratory Maniac before that end step, Doctor Doom free of summoning sickness by
     * its window, Fblthp on our battlefield before combat damage).
     * </ul>
     * False while the window is now (our Oracle's or Fblthp's trigger on our stack: the reading now), and with no piece
     * still to be cast. A copy of a cast is made only while some CantBeCast static exists (the view's castBarStatics,
     * else the piece's own); nothing here draws a random number, and the cards are read, never written.
     */
    static boolean barredAtWindowNow(final ComboTracker.View v, final ComboLines.Line l) {
        return !windowNow(v, l) && barredOn(v, l, opensThisTurn(v, l));
    }

    // the line's window is now: our trigger of Thassa's Oracle or of Fblthp is on our stack, fired already
    private static boolean windowNow(final ComboTracker.View v, final ComboLines.Line l) {
        final ComboLines.Winner w = l.winner();
        return (w == ComboLines.Winner.ORACLE || w == ComboLines.Winner.FBLTHP) && v.triggerOnOurStack(w.card);
    }

    // whether the board can still open the line's window this turn, so that the spells cast this turn count at it (an
    // if chain, not an enum switch: javac would add a synthetic switch-map class):
    // - Thassa's Oracle: our turn, before our end step (a main phase of ours now or still to come);
    // - Laboratory Maniac, Jace: the turn of the player before us is now;
    // - Doctor Doom: our turn, before our end step, Doom on our battlefield untapped and free of summoning sickness
    //   (ComboExecute.staged);
    // - Fblthp: our turn, before combat damage, Fblthp on our battlefield, and its combat-damage trigger can still come
    //   this turn (follow-up 3, H5: combatDamageCanCome). A summoning-sick Fblthp with nothing else of ours able to
    //   attack opens no window this turn: its line is read on the next turn it could run, as every other line is
    private static boolean opensThisTurn(final ComboTracker.View v, final ComboLines.Line l) {
        final ComboLines.Winner w = l.winner();
        final Player ai = v.ai();
        final PhaseHandler ph = ai.getGame().getPhaseHandler();
        if (w == ComboLines.Winner.LAB || w == ComboLines.Winner.JACE) {
            final Player before = beforeUs(ai.getGame(), ai);
            return before != null && ph.isPlayerTurn(before);
        }
        final PhaseType phase = ph.getPhase();
        if (!ph.isPlayerTurn(ai) || phase == null) {
            return false;
        }
        if (w == ComboLines.Winner.ORACLE) {
            return phase.isBefore(PhaseType.END_OF_TURN);
        }
        if (w == ComboLines.Winner.DOOM) {
            return phase.isBefore(PhaseType.END_OF_TURN) && ComboExecute.staged(v, w);
        }
        return phase.isBefore(PhaseType.COMBAT_DAMAGE) && v.where(w.card) == Where.BATTLEFIELD
                && combatDamageCanCome(ai, phase);
    }

    // whether combat damage can still reach an opponent of ours this turn, in our turn before combat damage (phase),
    // read from the board as it is (follow-up 3, H5). Fblthp's trigger is on combat damage dealt to an opponent during
    // our turn by any source ("Mode$ DamageAll | ValidTarget$ Opponent | CombatDamage$ True"), so Fblthp's own attack
    // is one way among ours: before attackers are declared, a creature of ours that can attack an opponent
    // (CombatUtil.canAttack, as the surer kill reads ours: untapped, free of summoning sickness or hasty, nothing such
    // as defender or a CantAttack static stopping it), and from the declare attackers step on, a creature of ours
    // attacking an opponent, not a planeswalker or a battle.
    // Pure reads: nothing here draws a random number or writes the game.
    private static boolean combatDamageCanCome(final Player ai, final PhaseType phase) {
        if (phase.isBefore(PhaseType.COMBAT_DECLARE_ATTACKERS)) {
            for (final Card c : ai.getCreaturesInPlay()) {
                for (final Player opp : ai.getOpponents()) {
                    if (CombatUtil.canAttack(c, opp)) {
                        return true;
                    }
                }
            }
            return false;
        }
        final Combat combat = ai.getGame().getCombat();
        if (combat == null) {
            return false;
        }
        for (final Player opp : ai.getOpponents()) {
            if (!combat.getAttackersOf(opp).isEmpty()) {
                return true;
            }
        }
        return false;
    }

    // whether a piece of l still to be cast cannot be cast at its moment in the line's window on that turn: this
    // turn's (thisTurn: the spells cast this turn count) or the next turn the line could run (none carried over).
    // Nothing more is read while no CantBeCast static exists (the view's castBarStatics, else a piece's own)
    private static boolean barredOn(final ComboTracker.View v, final ComboLines.Line l, final boolean thisTurn) {
        final ComboLines.Winner w = l.winner();
        final Card winner = unplayed(v, w.card);
        final String name = l.emptier().card;
        final Card emptier = unplayed(v, name);
        if (v.castBarStatics().isEmpty() && (winner == null || !hasCastBar(winner))
                && (emptier == null || !hasCastBar(emptier))) {
            return false;
        }
        final Player ai = v.ai();
        final Game game = ai.getGame();
        final boolean endStep = w == ComboLines.Winner.LAB || w == ComboLines.Winner.JACE;
        final Player turn = endStep ? beforeUs(game, ai) : ai;
        if (turn == null) {
            return false;
        }
        final List<Card> spells = thisTurn ? game.getStack().getSpellCardsCastThisTurn() : List.of();
        final boolean oracle = w == ComboLines.Winner.ORACLE;
        if (winner != null
                && barredAt(v, winner, w.card, ai, PhaseType.MAIN1, true, oracle ? spells : List.of())) {
            return true;
        }
        if (emptier == null) {
            return false;
        }
        List<Card> before = spells;
        if (oracle && winner != null) {
            before = new ArrayList<>(spells);
            before.add(winner);
        }
        final PhaseType step = endStep ? PhaseType.END_OF_TURN
                : w == ComboLines.Winner.FBLTHP ? PhaseType.COMBAT_DAMAGE : PhaseType.MAIN1;
        return barredAt(v, emptier, name, turn, step, w == ComboLines.Winner.DOOM, before);
    }

    // whether our card c, the piece named name, cannot be cast at the moment: whose turn it is, the step, whether a
    // sorcery could be cast then (our main phase on an empty stack), the spells cast that turn before it. Read on a
    // copy of its cast (castCopy), made only while some CantBeCast static exists (the view's castBarStatics, else c's
    // own)
    private static boolean barredAt(final ComboTracker.View v, final Card c, final String name, final Player turn,
            final PhaseType step, final boolean sorcery, final List<Card> castThatTurn) {
        return barredAt(v, c, Iterables.getFirst(c.getSpells(), null), v.where(name) == Where.COMMAND, turn, step,
                sorcery, castThatTurn);
    }

    // barredAt for spell, a spell of our card c (none: false) cast from our command zone (fromCommand) or else our
    // hand: its first spell for a piece, the follow-up draw's own for a draw spell (drawBarredAt)
    private static boolean barredAt(final ComboTracker.View v, final Card c, final SpellAbility spell,
            final boolean fromCommand, final Player turn, final PhaseType step, final boolean sorcery,
            final List<Card> castThatTurn) {
        if (spell == null || (v.castBarStatics().isEmpty() && !hasCastBar(c))) {
            return false;
        }
        final Player ai = v.ai();
        final Card cast = castCopy(ai, c, fromCommand);
        cast.setCastSA(spell);
        for (final StaticAbility st : v.castBarStatics()) {
            if (barsAt(st, cast, ai, turn, step, sorcery, castThatTurn)) {
                return true;
            }
        }
        for (final StaticAbility st : cast.getStaticAbilities()) {
            if (st.checkMode(StaticAbilityMode.CantBeCast) && barsAt(st, cast, ai, turn, step, sorcery, castThatTurn)) {
                return true;
            }
        }
        return false;
    }

    // StaticAbilityCantBeCast.applyCantBeCastAbility for a CantBeCast static at the moment, after its conditions
    // (conditionsAt, with the moment's turn and step): ValidCard, Caster, the players it ignores, Origin and cmcGT as
    // Forge reads them; OnlySorcerySpeed lets the cast through only at a sorcery's moment (Player.canCastSorcery: our
    // main phase on an empty stack), never with a trigger of ours on the stack or at an end step; NumLimitEachTurn
    // counts the cards cast that turn that its ValidCard matches and we control, as CardUtil.getThisTurnCast and
    // filterControlledByAsList count this turn's
    private static boolean barsAt(final StaticAbility st, final Card cast, final Player ai, final Player turn,
            final PhaseType step, final boolean sorcery, final List<Card> castThatTurn) {
        if (!conditionsAt(st, turn, step) || !st.matchesValidParam("ValidCard", cast)
                || !st.matchesValidParam("Caster", ai) || st.getIgnoreEffectPlayers().contains(ai)) {
            return false;
        }
        if (sorcery && st.hasParam("OnlySorcerySpeed")) {
            return false;
        }
        if (st.hasParam("Origin")) {
            final List<ZoneType> from = ZoneType.listValueOf(st.getParam("Origin"));
            if (cast.getCastFrom() == null || !from.contains(cast.getCastFrom().getZoneType())) {
                return false;
            }
        }
        if (st.hasParam("cmcGT")) {
            final String gt = st.getParam("cmcGT");
            final int most = "Turns".equals(gt) ? ai.getTurn()
                    : CardLists.getType(ai.getCardsIn(ZoneType.Battlefield), gt).size();
            if (cast.getCMC() <= most) {
                return false;
            }
        }
        if (st.hasParam("NumLimitEachTurn")) {
            final int limit = Integer.parseInt(st.getParam("NumLimitEachTurn"));
            final List<Card> counted = CardLists.getValidCardsAsList(castThatTurn,
                    st.getParamOrDefault("ValidCard", "Card"), ai, cast, st);
            return CardLists.filterControlledByAsList(counted, ai).size() >= limit;
        }
        return true;
    }

    // a static's conditions at the moment (cantBeCastAbility's checkConditions): Forge's own reading, now, for any
    // static but one whose Condition$ is PlayerTurn, which holds only in its controller's turn, or one with Phases,
    // which holds only in those steps (Basandra, Battle Seraph and Marisi, Breaker of the Coil: combat). Such a static
    // is false when the moment's turn or step is not its own; Forge's own reading when now's turn and step are its
    // own as well; else its zones alone (zonesCheck), its other conditions read as met (in the tree only Conqueror's
    // Flail carries one beside the turn, its IsPresent)
    private static boolean conditionsAt(final StaticAbility st, final Player turn, final PhaseType step) {
        final boolean byTurn = "PlayerTurn".equals(st.getParam("Condition"));
        final boolean byStep = st.hasParam("Phases");
        if (!byTurn && !byStep) {
            return st.checkConditions();
        }
        final Player controller = st.getHostCard().getController();
        if (byTurn && !controller.equals(turn)) {
            return false;
        }
        final Set<PhaseType> steps = byStep ? PhaseType.parseRange(st.getParam("Phases")) : null;
        if (byStep && !steps.contains(step)) {
            return false;
        }
        final PhaseHandler ph = controller.getGame().getPhaseHandler();
        final boolean asNow = (!byTurn || ph.isPlayerTurn(controller)) && (!byStep || steps.contains(ph.getPhase()));
        return asNow ? st.checkConditions() : st.zonesCheck();
    }

    // the player whose turn comes right before ours: the last of the players in turn order from us; null alone
    private static Player beforeUs(final Game game, final Player ai) {
        final List<Player> order = game.getPlayersInTurnOrder(ai);
        return order.size() < 2 ? null : order.get(order.size() - 1);
    }

    // whether the card carries a CantBeCast static of its own, whatever its conditions
    private static boolean hasCastBar(final Card c) {
        for (final StaticAbility st : c.getStaticAbilities()) {
            if (st.checkMode(StaticAbilityMode.CantBeCast)) {
                return true;
            }
        }
        return false;
    }

    // an LKI copy of our card c as its cast reads it: last known on the stack, its castFrom the zone the spell comes
    // from, the command zone for a card there, else our hand
    private static Card castCopy(final Player ai, final Card c, final boolean fromCommand) {
        final Card cast = CardCopyService.getLKICopy(c);
        cast.setLKICMC(-1);
        cast.setLastKnownZone(ai.getGame().getStackZone());
        cast.setCastFrom(fromCommand ? c.getZone() : ai.getZone(ZoneType.Hand));
        return cast;
    }

    // Oracle's entering trigger, read as AiController.checkETBEffectsPreparedCard reads an ETB hoser (a private copy).
    // A trigger of our Oracle already on the stack has fired: nothing can disable it now.
    static boolean oracleTriggerDisabledNow(final ComboTracker.View v) {
        final String name = ComboLines.Winner.ORACLE.card;
        final Game game = v.ai().getGame();
        if (v.triggerOnOurStack(name)) {
            return false;
        }
        final Card oracle = v.card(name);
        if (oracle == null) {
            return false;
        }
        for (final Trigger tr : oracle.getTriggers()) {
            if (tr.getMode() != TriggerType.ChangesZone
                    || !ZoneType.Battlefield.toString().equals(tr.getParam("Destination"))) {
                continue;
            }
            final Map<AbilityKey, Object> runParams = AbilityKey.mapFromCard(tr.getHostCard());
            runParams.put(AbilityKey.Destination, ZoneType.Battlefield.name());
            if (StaticAbilityDisableTriggers.disabled(game, tr, runParams)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Signal 1, no mana for the whole line: the mana values of its pieces in our hand, library or command zone
     * ({@link ComboTracker#costNow}), plus the commander tax on those in our command zone ({@link #commanderTax}),
     * come to more than next turn's mana ({@link #nextTurnManaNow}).
     */
    private static boolean noMana(final ComboTracker.View v, final ComboLines.Line l) {
        return v.costNow(l) + commanderTax(v, l) > v.nextTurnMana();
    }

    // Forge's own commander tax (CostAdjustment.adjust) on the pieces of l in our command zone, each cast from there:
    // 2 for each earlier cast of that commander from the command zone (Player.getCommanderCast), on a commander only.
    // ComboFetch's win in time under danger reads it inside the engine's own price instead (ComboExecute.priced, whose
    // test-mode calculateManaCost casts a card from the zone it is in: follow-up final review, round 1)
    static int commanderTax(final ComboTracker.View v, final ComboLines.Line l) {
        int tax = 0;
        for (final String name : l.names()) {
            if (v.where(name) == Where.COMMAND) {
                final Card c = v.card(name);
                if (c.isCommander()) {
                    tax += 2 * v.ai().getCommanderCast(c);
                }
            }
        }
        return tax;
    }

    /** Next turn's mana: every mana source we control whether tapped or not ({@code getAvailableManaEstimate(ai,
     *  false)}), plus one when a land is left in our hand or library to play. */
    static int nextTurnManaNow(final Player ai) {
        return ComputerUtilMana.getAvailableManaEstimate(ai, false)
                + (hasLand(ai, ZoneType.Hand) || hasLand(ai, ZoneType.Library) ? 1 : 0);
    }

    private static boolean hasLand(final Player ai, final ZoneType zone) {
        for (final Card c : ai.getCardsIn(zone)) {
            if (c.isLand()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Signal 3, about to die: one opponent's own next-turn attack reaches our life after our creatures block it
     * ({@link #lethal}). Each opponent is read alone: they attack in their own turns, so one's creatures never add to
     * another's. Never while we cannot lose. Read once a view, when the view is first asked ({@code View.danger}); an
     * opponent's attackers-by-blockers matrix, a game-wide static scan for each pair, is built only when that
     * opponent's attack, unblocked, reaches our life (the cheap sum first), and the view counts each build (follow-up
     * 2, G5). The attack is the one its limits let through (follow-up 3, H2: {@link #attack}): no more creatures than
     * an AttackRestrict static lets attack us, each one's CantAttackUnless tax paid from the mana that opponent will
     * have in it; and no more of ours block it than a BlockRestrict static lets block.
     */
    static boolean dangerNow(final ComboTracker.View v) {
        final Player ai = v.ai();
        if (ai.cantLose()) {
            return false;
        }
        final List<Card> ours = ai.getCreaturesInPlay();
        for (final Player opp : ai.getOpponents()) {
            if (lethal(v, ai, opp, ours)) {
                return true;
            }
        }
        return false;
    }

    // whether what opp's creatures that can attack us next turn would deal us (damageIfUnblocked) reaches our life
    // after our creatures block them. The attack is the one its limits let through (attack: the largest hits first,
    // capped by the AttackRestrict statics on attacks on us and by what opp's mana pays of the CantAttackUnless taxes
    // on them), and its cheap sum is that attack's. The largest hits are blocked first, each by as many of our spare
    // creatures able to block it (CombatUtil.canBlock) as it needs (CombatUtil.getMinNumBlockersForAttacker: two for
    // menace), taking those able to block the fewest of the hits still to come first, then in battlefield order, while
    // the creatures blocking stay within what a BlockRestrict static lets block (StaticAbilityBlockRestrict.
    // blockRestrictNum, as CombatUtil.canBlock reads it in a combat: Silent Arbiter, Caverns of Despair). An attacker
    // without enough spare blockers, or past that limit, comes through whole, a blocked one deals us nothing, and a
    // blocked trampler puts through its hit less its blockers' toughness. An attack whose unblocked sum falls short is
    // never blocked: its block matrix is never built (blockMatrix)
    private static boolean lethal(final ComboTracker.View v, final Player ai, final Player opp,
            final List<Card> ours) {
        final List<Card> able = new ArrayList<>();
        final List<Integer> ableHits = new ArrayList<>();
        for (final Card c : opp.getCreaturesInPlay()) {
            if (ComputerUtilCombat.canAttackNextTurn(c, ai)) {
                able.add(c);
                ableHits.add(ComputerUtilCombat.damageIfUnblocked(c, ai, null, true));
            }
        }
        if (able.isEmpty()) {
            return false;
        }
        // the attack, the largest hit first: its attackers, their hits, and their indexes in that order
        final List<Card> attackers = new ArrayList<>();
        final List<Integer> hits = new ArrayList<>();
        final List<Integer> order = new ArrayList<>();
        int unblocked = 0;
        for (final int i : attack(able, ableHits, ai, attackLimit(ai), opp, true)) {
            order.add(attackers.size());
            attackers.add(able.get(i));
            hits.add(ableHits.get(i));
            unblocked += ableHits.get(i);
        }
        final int life = ai.getLife();
        if (unblocked < life) {
            return false;
        }
        final boolean[][] blocks = blockMatrix(v, attackers, ours);
        final int mostBlockers = StaticAbilityBlockRestrict.blockRestrictNum(ai);
        final boolean[] spent = new boolean[ours.size()];
        int blocking = 0;
        int through = 0;
        for (int k = 0; k < order.size(); k++) {
            final int i = order.get(k);
            final Card attacker = attackers.get(i);
            final int need = CombatUtil.getMinNumBlockersForAttacker(attacker, ai);
            final List<Integer> blockers = need > mostBlockers - blocking ? null
                    : blockers(blocks, spent, order, k, need);
            if (blockers == null) {
                through += hits.get(i);
                continue;
            }
            blocking += blockers.size();
            int toughness = 0;
            for (final int j : blockers) {
                spent[j] = true;
                toughness += ours.get(j).getNetToughness();
            }
            if (attacker.hasKeyword(Keyword.TRAMPLE)) {
                through += Math.max(0, hits.get(i) - toughness);
            }
        }
        return through >= life;
    }

    // the most creatures that can attack the defender in one combat, as GlobalAttackRestrictions.getGlobalRestrictions
    // reads the AttackRestrict statics in force now, Forge's own readers: the least of every MaxAttackers on all attacks
    // (StaticAbilityAttackRestrict.globalAttackRestrict: Silent Arbiter, Caverns of Despair) and of every one whose
    // ValidDefender the defender is (attackRestrictNum: Crawlspace on its controller); Integer.MAX_VALUE when none
    private static int attackLimit(final GameEntity defender) {
        int most = Integer.MAX_VALUE;
        final Integer all = StaticAbilityAttackRestrict.globalAttackRestrict(defender.getGame());
        if (all != null) {
            most = Math.min(most, all);
        }
        final Integer own = StaticAbilityAttackRestrict.attackRestrictNum(defender);
        if (own != null) {
            most = Math.min(most, own);
        }
        return most;
    }

    // the attack the defender's limits let through (follow-up 3, H2): the indexes of the able attackers that attack,
    // the largest hit first (a stable sort: equal hits in battlefield order), each taken while fewer than most are
    // taken and its CantAttackUnless tax against the defender (CombatUtil.getAttackCost, the reader AiAttackController
    // and the declaration's checkPropagandaEffects use: Propaganda, Ghostly Prison) can be paid from the payer's mana
    // together with the taxes of those taken before it; one whose tax cannot be paid is passed over for the next. The
    // mana is read only once an attacker is taxed: for the opponent's attack on us (theirs), every mana source the
    // payer controls, untapped, as their next turn will have it (ComboMana.nextTurn); for ours, the mana we can make
    // now (ComboMana.of, held sources left out, as the burn reads it). A tax is no spell, so a source whose mana
    // carries a spending restriction pays none (ComboMana, sa null)
    private static List<Integer> attack(final List<Card> able, final List<Integer> hits, final GameEntity defender,
            final int most, final Player payer, final boolean theirs) {
        final List<Integer> order = new ArrayList<>();
        for (int i = 0; i < able.size(); i++) {
            order.add(i);
        }
        order.sort((a, b) -> Integer.compare(hits.get(b), hits.get(a)));
        final Game game = payer.getGame();
        final List<Integer> taken = new ArrayList<>();
        final List<ManaCost> taxes = new ArrayList<>();
        ComboMana mana = null;
        for (final int i : order) {
            if (taken.size() >= most) {
                break;
            }
            final ManaCost due = due(CombatUtil.getAttackCost(game, able.get(i), defender), theirs);
            if (due == null) {
                continue;
            }
            if (!due.isZero()) {
                if (mana == null) {
                    mana = theirs ? ComboMana.nextTurn(payer, null) : ComboMana.of(payer, null, true);
                }
                taxes.add(due);
                if (!mana.pays(taxes.toArray(new ManaCost[0]))) {
                    taxes.remove(taxes.size() - 1);
                    continue;
                }
            }
            taken.add(i);
        }
        return taken;
    }

    // the mana an attacker's tax asks, read on each signal's safe side: none without a tax. For the opponent's attack
    // on us (theirs) the tax's mana part alone, every other part read as paid (a land to sacrifice, life to pay), and
    // none when that part holds a Phyrexian pip, which they may pay with life: the attack is over-read, as it was
    // before the taxes were read. For ours, null when the tax asks anything but mana, which no mana prices: that
    // attacker is not counted, so no surer kill rests on it; else its mana, a Phyrexian pip as its colour (ComboMana)
    private static ManaCost due(final Cost tax, final boolean theirs) {
        if (tax == null) {
            return ManaCost.ZERO;
        }
        if (!theirs && !tax.isOnlyManaCost()) {
            return null;
        }
        final CostPartMana part = tax.getCostMana();
        if (part == null || (theirs && part.getMana().hasPhyrexian())) {
            return ManaCost.ZERO;
        }
        return part.getMana();
    }

    // whether each of our creatures can block each attacker (CombatUtil.canBlock, Forge's own reading: two or three
    // game-wide static scans a pair), by the attacker's index, then ours: built once for each opponent whose attack,
    // as its limits let it through, reaches our life unblocked, over that attack's attackers, the build counted on the
    // view
    private static boolean[][] blockMatrix(final ComboTracker.View v, final List<Card> attackers,
            final List<Card> ours) {
        v.blockMatrixBuilt();
        final boolean[][] blocks = new boolean[attackers.size()][ours.size()];
        for (int i = 0; i < attackers.size(); i++) {
            for (int j = 0; j < ours.size(); j++) {
                blocks[i][j] = CombatUtil.canBlock(attackers.get(i), ours.get(j));
            }
        }
        return blocks;
    }

    // the need spare blockers of ours for the k-th attacker in order, those able to block the fewest of the attackers
    // after it first, then in battlefield order; null when fewer than need are spare and able
    private static List<Integer> blockers(final boolean[][] blocks, final boolean[] spent, final List<Integer> order,
            final int k, final int need) {
        final int i = order.get(k);
        final List<Integer> able = new ArrayList<>();
        final int[] later = new int[spent.length];
        for (int j = 0; j < spent.length; j++) {
            if (spent[j] || !blocks[i][j]) {
                continue;
            }
            able.add(j);
            for (int q = k + 1; q < order.size(); q++) {
                if (blocks[order.get(q)][j]) {
                    later[j]++;
                }
            }
        }
        if (able.size() < need) {
            return null;
        }
        able.sort((a, b) -> Integer.compare(later[a], later[b]));
        return able.subList(0, need);
    }

    /**
     * Signal 4, a surer kill: in our own turn, before attackers are declared, with exactly one opponent left in the
     * game, that opponent is killable, by combat or by one burn spell. One combat cannot spend the same attackers on
     * two players, nor one burn spell kill two, so a pod never reads as a surer kill.
     */
    static boolean surerKillNow(final Player ai) {
        final PhaseHandler ph = ai.getGame().getPhaseHandler();
        if (!ph.isPlayerTurn(ai) || ph.getPhase() == null
                || !ph.getPhase().isBefore(PhaseType.COMBAT_DECLARE_ATTACKERS)) {
            return false;
        }
        final List<Player> opps = ai.getOpponents();
        return opps.size() == 1 && killable(ai, opps.get(0));
    }

    // an opponent who can lose, whose life our unblockable ready attackers or one burn spell in hand reach, its mana
    // cost paid colour by colour from the mana we can make now (ComboMana: a Swamp is no Lightning Bolt's {R}). The
    // attackers are those the opponent's limits let through (follow-up 3, H2: attack): no more than an AttackRestrict
    // static lets attack the opponent (Silent Arbiter, the opponent's Crawlspace), the largest hits first, each one's
    // CantAttackUnless tax against the opponent (the opponent's Ghostly Prison, Propaganda) paid from the mana we can
    // make now
    private static boolean killable(final Player ai, final Player opp) {
        if (opp.cantLose()) {
            return false;
        }
        final int life = opp.getLife();
        final List<Card> able = new ArrayList<>();
        final List<Integer> hits = new ArrayList<>();
        for (final Card c : ai.getCreaturesInPlay()) {
            if (c.isSick() || c.isTapped() || !CombatUtil.canAttack(c, opp) || blockable(c, opp)) {
                continue;
            }
            able.add(c);
            hits.add(ComputerUtilCombat.damageIfUnblocked(c, opp, null, true));
        }
        int combat = 0;
        if (!able.isEmpty()) {
            for (final int i : attack(able, hits, opp, attackLimit(opp), ai, false)) {
                combat += hits.get(i);
            }
        }
        if (combat >= life) {
            return true;
        }
        for (final Card c : ai.getCardsIn(ZoneType.Hand)) {
            if (!c.isInstant() && !c.isSorcery()) {
                continue;
            }
            for (final SpellAbility spell : c.getSpells()) {
                if (burns(spell, life) && ComboMana.of(ai, spell, true).pays(c.getManaCost())) {
                    return true;
                }
            }
        }
        return false;
    }

    // an untapped creature of the opponent's could block the attacker
    private static boolean blockable(final Card attacker, final Player opp) {
        for (final Card b : opp.getCreaturesInPlay()) {
            if (!b.isTapped() && CombatUtil.canBlock(attacker, b)) {
                return true;
            }
        }
        return false;
    }

    // a DealDamage part (the root or a sub) able to aim at a player, whose damage reaches the life. A Count$Random
    // amount draws when calculated (AbilityUtils' Count$Random.<Min>.<Max>), so one is never calculated
    // (drawsWhenCalculated): at a4d2a245fc4 the only instant or sorcery whose damage is one, Ashuza's Breath, has no
    // ValidTgts at all.
    private static boolean burns(final SpellAbility root, final int life) {
        for (SpellAbility s = root; s != null; s = s.getSubAbility()) {
            if (s.getApi() != ApiType.DealDamage || !s.hasParam("NumDmg")) {
                continue;
            }
            final String tgts = s.getParamOrDefault("ValidTgts", "");
            if (!tgts.contains("Player") && !tgts.contains("Any") && !tgts.contains("Opponent")) {
                continue;
            }
            final String amount = s.getParam("NumDmg");
            if (drawsWhenCalculated(s, amount)) {
                continue;
            }
            if (AbilityUtils.calculateAmount(s.getHostCard(), amount, s) >= life) {
                return true;
            }
        }
        return false;
    }

    // whether the amount, or an SVar of s it names at any depth, holds a Random count. calculateAmount follows an SVar
    // named anywhere in an amount (SVar$Y/Plus.Z reads Y and Z), so every word of each text that names an SVar of s
    // (its own, its card state's or its host's, as s.getSVar reads them) is followed, each SVar once: names that cycle
    // end here, and a word that names no SVar is never read
    private static boolean drawsWhenCalculated(final SpellAbility s, final String amount) {
        final Deque<String> texts = new ArrayDeque<>();
        final Set<String> read = new HashSet<>();
        texts.push(amount);
        while (!texts.isEmpty()) {
            final String text = texts.pop();
            if (text.contains("Random")) {
                return true;
            }
            int start = -1;
            for (int i = 0; i <= text.length(); i++) {
                final boolean word = i < text.length() && isWordChar(text.charAt(i));
                if (word && start < 0) {
                    start = i;
                } else if (!word && start >= 0) {
                    final String name = text.substring(start, i);
                    if (s.hasSVar(name) && read.add(name)) {
                        texts.push(s.getSVar(name));
                    }
                    start = -1;
                }
            }
        }
        return false;
    }

    // a character of an SVar name: a letter, a digit or an underscore
    private static boolean isWordChar(final char ch) {
        return ch == '_' || Character.isLetterOrDigit(ch);
    }

    // our card of that name in our hand, library or command zone, where the view places it; null on our battlefield,
    // on our stack, or gone
    private static Card unplayed(final ComboTracker.View v, final String name) {
        final Where w = v.where(name);
        return w == Where.HAND || w == Where.COMMAND || w == Where.LIBRARY ? v.card(name) : null;
    }
}
