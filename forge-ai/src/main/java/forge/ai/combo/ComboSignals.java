package forge.ai.combo;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import com.google.common.collect.Iterables;

import forge.ai.ComputerUtilCombat;
import forge.ai.ComputerUtilMana;
import forge.ai.SpecialCardAi;
import forge.ai.combo.ComboTracker.Where;
import forge.game.Game;
import forge.game.ability.AbilityKey;
import forge.game.ability.AbilityUtils;
import forge.game.ability.ApiType;
import forge.game.card.Card;
import forge.game.card.CardCopyService;
import forge.game.combat.CombatUtil;
import forge.game.phase.PhaseHandler;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.replacement.ReplacementEffect;
import forge.game.replacement.ReplacementType;
import forge.game.spellability.SpellAbility;
import forge.game.staticability.StaticAbility;
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
 * {@link ComboTracker.View}: the inputs no line changes (whether we can win, whether every opponent cannot lose, the
 * danger, the surer kill, next turn's mana, whether a CantBeCast static is in force) are read once for every line the
 * decision asks about, and nothing outlives the decision. Nothing here draws a random number or reads the library's
 * order: the stock danger and lethal readers both draw (aiLifeInDanger through the blocker's lifeInDanger roll,
 * doAssault and possibleNonCombatDamage through canPayCost's test payments), so DANGER and SURER_KILL are private
 * floors in the AdNauseam.floor idiom, damageIfUnblocked without abilities.
 */
public final class ComboSignals {
    /** Why a line is not chased, in the order {@link #abandon} reads them. DENIED stops everything, execution
     *  included; the other three stop holding, protecting and fetching, never execution (a win now beats surviving,
     *  and each execution window checks its own mana). */
    public enum Signal { DENIED, NO_MANA, DANGER, SURER_KILL }

    private ComboSignals() {
    }

    /** The first signal that holds for {@code l}, in {@link Signal} order, or null: chase it. */
    public static Signal abandon(final Player ai, final ComboLines.Line l, final ComboTracker t) {
        return abandon(t.view(), l);
    }

    /** {@link #abandon} read through the decision's view. */
    static Signal abandon(final ComboTracker.View v, final ComboLines.Line l) {
        if (v.denied(l)) {
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
     * Signal 2, the opponents can deny the line, read from visible state: we cannot win; every opponent cannot lose; a
     * piece we have not yet put on the battlefield cannot be cast (a CantBeCast static covers its first spell, Void
     * Winnower's even mana values); Thassa's Oracle's entering trigger is disabled (Torpor Orb, Hushbringer); a winner
     * of ours on the battlefield has lost its win ({@link #disarmed}: Imprisoned in the Moon, Lignify, Humility, a
     * face-down copy), or Doctor Doom's {T} draw cannot be activated (Linvala, Keeper of Silence).
     */
    public static boolean denied(final Player ai, final ComboLines.Line l, final ComboTracker t) {
        return t.view().denied(l);
    }

    /** {@link #denied}, read through the view (which keeps it for a deck line). */
    static boolean deniedNow(final ComboTracker.View v, final ComboLines.Line l) {
        if (v.cantWin()) {
            return true;
        }
        if (v.everyOpponentCantLose()) {
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

    static boolean everyOpponentCantLose(final Player ai) {
        for (final Player opp : ai.getOpponents()) {
            if (!opp.cantLose()) {
                return false;
            }
        }
        return true;
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
        final Where w = v.where(name);
        final Card c = unplayed(v, name);
        if (c == null) {
            return false;
        }
        final SpellAbility spell = Iterables.getFirst(c.getSpells(), null);
        if (spell == null || (!v.castBarsInForce() && !barsCasts(c))) {
            return false;
        }
        final Player ai = v.ai();
        final Card cast = CardCopyService.getLKICopy(c);
        cast.setLKICMC(-1);
        cast.setLastKnownZone(ai.getGame().getStackZone());
        cast.setCastFrom(w == Where.COMMAND ? c.getZone() : ai.getZone(ZoneType.Hand));
        return StaticAbilityCantBeCast.cantBeCastAbility(spell, cast, ai);
    }

    // whether a CantBeCast static is in force now on a card in a static-ability source zone (cantBeCastAbility's scan)
    static boolean castBarsInForceNow(final Game game) {
        for (final Card ca : game.getCardsIn(ZoneType.STATIC_ABILITIES_SOURCE_ZONES)) {
            if (barsCasts(ca)) {
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
     * ({@link ComboTracker#costNow}) come to more than next turn's mana ({@link #nextTurnManaNow}).
     */
    private static boolean noMana(final ComboTracker.View v, final ComboLines.Line l) {
        return v.costNow(l) > v.nextTurnMana();
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
     * Signal 3, about to die: what the opponents' creatures that can attack next turn would deal us unblocked, after
     * each creature of ours chumps one of the largest, reaches our life. Never while we cannot lose.
     */
    static boolean dangerNow(final Player ai) {
        if (ai.cantLose()) {
            return false;
        }
        final List<Integer> hits = new ArrayList<>();
        for (final Player opp : ai.getOpponents()) {
            for (final Card c : opp.getCreaturesInPlay()) {
                if (ComputerUtilCombat.canAttackNextTurn(c, ai)) {
                    hits.add(ComputerUtilCombat.damageIfUnblocked(c, ai, null, true));
                }
            }
        }
        hits.sort(Collections.reverseOrder());
        final int chumps = Math.min(ai.getCreaturesInPlay().size(), hits.size());
        int incoming = 0;
        for (final int hit : hits.subList(chumps, hits.size())) {
            incoming += hit;
        }
        return incoming >= ai.getLife();
    }

    /**
     * Signal 4, a surer kill: in our own turn, before attackers are declared, every opponent still in the game is
     * killable, by combat or by one burn spell.
     */
    static boolean surerKillNow(final Player ai) {
        final PhaseHandler ph = ai.getGame().getPhaseHandler();
        if (!ph.isPlayerTurn(ai) || ph.getPhase() == null
                || !ph.getPhase().isBefore(PhaseType.COMBAT_DECLARE_ATTACKERS)) {
            return false;
        }
        for (final Player opp : ai.getOpponents()) {
            if (!killable(ai, opp)) {
                return false;
            }
        }
        return true;
    }

    // an opponent who can lose, whose life our unblockable ready attackers or one burn spell in hand reach
    private static boolean killable(final Player ai, final Player opp) {
        if (opp.cantLose()) {
            return false;
        }
        final int life = opp.getLife();
        int combat = 0;
        for (final Card c : ai.getCreaturesInPlay()) {
            if (c.isSick() || c.isTapped() || !CombatUtil.canAttack(c, opp) || blockable(c, opp)) {
                continue;
            }
            combat += ComputerUtilCombat.damageIfUnblocked(c, opp, null, true);
        }
        if (combat >= life) {
            return true;
        }
        for (final Card c : ai.getCardsIn(ZoneType.Hand)) {
            if (!c.isInstant() && !c.isSorcery()) {
                continue;
            }
            for (final SpellAbility spell : c.getSpells()) {
                if (burns(spell, life) && c.getCMC() <= SpecialCardAi.HonestMana.of(ai, spell, true).total()) {
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
    // amount draws when calculated (AbilityUtils' Count$Random.<Min>.<Max>), so one is never calculated: at
    // a4d2a245fc4 the only instant or sorcery whose damage is one, Ashuza's Breath, has no ValidTgts at all.
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
            if (s.hasSVar(amount) && s.getSVar(amount).contains("Random")) {
                continue;
            }
            if (AbilityUtils.calculateAmount(s.getHostCard(), amount, s) >= life) {
                return true;
            }
        }
        return false;
    }

    // our card of that name in our hand, library or command zone, where the view places it; null on our battlefield,
    // on our stack, or gone
    private static Card unplayed(final ComboTracker.View v, final String name) {
        final Where w = v.where(name);
        return w == Where.HAND || w == Where.COMMAND || w == Where.LIBRARY ? v.card(name) : null;
    }
}
