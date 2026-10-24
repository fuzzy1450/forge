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
import forge.game.Game;
import forge.game.ability.AbilityKey;
import forge.game.ability.AbilityUtils;
import forge.game.ability.ApiType;
import forge.game.card.Card;
import forge.game.card.CardCopyService;
import forge.game.card.CardLists;
import forge.game.combat.CombatUtil;
import forge.game.keyword.Keyword;
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
 * danger, the surer kill, next turn's mana, the CantBeCast statics on the board and whether one is in force) are read
 * once for every line the decision asks about, and nothing outlives the decision. A denial is read twice over: now
 * ({@link #deniedNow}), and for the emptier at the moment its window needs it ({@link #barredAtWindowNow}: Oracle's
 * trigger on the stack, the opponent's end step), from the same visible statics. Nothing here draws a random number or
 * reads the library's order: the stock danger and lethal readers both draw (aiLifeInDanger through the blocker's
 * lifeInDanger roll, doAssault and possibleNonCombatDamage through canPayCost's test payments), so DANGER and
 * SURER_KILL are private floors in the AdNauseam.floor idiom, damageIfUnblocked without abilities.
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
     * Signal 2, the opponents can deny the line, read from visible state: the line is denied now ({@link #deniedNow}:
     * we cannot win, every opponent cannot lose, a piece cannot be cast now, Oracle's entering trigger is disabled, a
     * winner of ours in play has lost its win), or its emptier cannot be cast at the moment its window needs it
     * ({@link #barredAtWindowNow}: Teferi, Time Raveler or Rule of Law against the go-off in response to Oracle's
     * trigger, the opponent's Grand Abolisher against the opponent's end step).
     */
    public static boolean denied(final Player ai, final ComboLines.Line l, final ComboTracker t) {
        return t.view().denied(l);
    }

    /** Signal 2 as read now, the moment of a window that is open now (the view keeps it for a deck line): we cannot
     *  win; every opponent cannot lose; a piece we have not yet put on the battlefield cannot be cast now (a CantBeCast
     *  static covers its first spell, Void Winnower's even mana values); Thassa's Oracle's entering trigger is
     *  disabled (Torpor Orb, Hushbringer); a winner of ours on the battlefield has lost its win ({@link #disarmed}:
     *  Imprisoned in the Moon, Lignify, Humility, a face-down copy), or Doctor Doom's {T} draw cannot be activated
     *  (Linvala, Keeper of Silence). */
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

    /**
     * Signal 2 at the window (follow-up F3): whether {@code l}'s emptier, in our hand, library or command zone, cannot
     * be cast at the moment its window needs it, where {@link #deniedNow} reads it now. The CantBeCast statics are read
     * as StaticAbilityCantBeCast reads them ({@link #barsAt}), with that moment's turn, stack and spells cast:
     * <ul>
     * <li>a Thassa's Oracle line: in response to Oracle's entering trigger, Oracle cast that turn, in our turn. The
     * stack is not empty (OnlySorcerySpeed bars); the turn, which a Condition$ PlayerTurn reads, is ours; the spells
     * cast that turn (NumLimitEachTurn) are this turn's while the go-off can still come this turn, in our turn before
     * our end step, else none, and Oracle;
     * <li>a Laboratory Maniac or Jace line: at the end step of the player whose turn comes right before ours, our draw
     * step next. No main phase; that player's turn; the spells cast that turn are this turn's while it is that
     * player's turn now, else none. A draw spell that follows the emptier in our main phase opens a window now, which
     * ComboExecute reads with deniedNow (the view's executableNow).
     * </ul>
     * False for a Doctor Doom line (its window is now: Consultation, then Doom's {T}) and a Fblthp line (left to the
     * reading now: its window, Fblthp's combat-damage trigger on our stack, is now when it is asked), for an Oracle
     * line whose trigger of ours is on the stack (that window is now), and for an emptier on our stack or gone. The
     * copy of the cast is made only while some CantBeCast static exists (the view's castBarStatics, else the
     * emptier's own); nothing here draws a random number, and the cards are read, never written.
     */
    static boolean barredAtWindowNow(final ComboTracker.View v, final ComboLines.Line l) {
        final ComboLines.Winner w = l.winner();
        final boolean oracle = w == ComboLines.Winner.ORACLE;
        if (!oracle && w != ComboLines.Winner.LAB && w != ComboLines.Winner.JACE) {
            return false;
        }
        final String name = l.emptier().card;
        final Card emptier = unplayed(v, name);
        if (emptier == null || (oracle && v.triggerOnOurStack(w.card))) {
            return false;
        }
        final SpellAbility spell = Iterables.getFirst(emptier.getSpells(), null);
        if (spell == null || (v.castBarStatics().isEmpty() && !hasCastBar(emptier))) {
            return false;
        }
        final Player ai = v.ai();
        final Game game = ai.getGame();
        final PhaseHandler ph = game.getPhaseHandler();
        final List<Card> castThatTurn = new ArrayList<>();
        final Player turn;
        if (oracle) {
            turn = ai;
            if (ph.isPlayerTurn(ai) && ph.getPhase() != null && ph.getPhase().isBefore(PhaseType.END_OF_TURN)) {
                castThatTurn.addAll(game.getStack().getSpellCardsCastThisTurn());
            }
            final Card card = unplayed(v, w.card);
            if (card != null) {
                castThatTurn.add(card);
            }
        } else {
            turn = beforeUs(game, ai);
            if (turn == null) {
                return false;
            }
            if (ph.isPlayerTurn(turn)) {
                castThatTurn.addAll(game.getStack().getSpellCardsCastThisTurn());
            }
        }
        final Card cast = castCopy(ai, emptier, v.where(name) == Where.COMMAND);
        cast.setCastSA(spell);
        for (final StaticAbility st : v.castBarStatics()) {
            if (barsAt(st, cast, ai, turn, castThatTurn)) {
                return true;
            }
        }
        for (final StaticAbility st : cast.getStaticAbilities()) {
            if (st.checkMode(StaticAbilityMode.CantBeCast) && barsAt(st, cast, ai, turn, castThatTurn)) {
                return true;
            }
        }
        return false;
    }

    // StaticAbilityCantBeCast.applyCantBeCastAbility for a CantBeCast static at the moment, after its conditions
    // (conditionsAt, with the moment's turn): ValidCard, Caster, the players it ignores, Origin and cmcGT as Forge
    // reads them; OnlySorcerySpeed lets nothing through, since neither moment lets us cast a sorcery (the stack holds
    // Oracle's trigger, an end step is no main phase); NumLimitEachTurn counts the cards cast that turn that its
    // ValidCard matches and we control, as CardUtil.getThisTurnCast and filterControlledByAsList count this turn's
    private static boolean barsAt(final StaticAbility st, final Card cast, final Player ai, final Player turn,
            final List<Card> castThatTurn) {
        if (!conditionsAt(st, turn) || !st.matchesValidParam("ValidCard", cast) || !st.matchesValidParam("Caster", ai)
                || st.getIgnoreEffectPlayers().contains(ai)) {
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
    // static but one whose Condition$ is PlayerTurn, which holds only in its controller's turn. That one is false when
    // the moment is another player's turn; Forge's own reading when the moment's turn is the turn now; else its zones
    // alone (zonesCheck), its other conditions read as met (in the tree only Conqueror's Flail carries one beside the
    // turn, its IsPresent)
    private static boolean conditionsAt(final StaticAbility st, final Player turn) {
        if (!"PlayerTurn".equals(st.getParam("Condition"))) {
            return st.checkConditions();
        }
        final Player controller = st.getHostCard().getController();
        if (!controller.equals(turn)) {
            return false;
        }
        return controller.getGame().getPhaseHandler().isPlayerTurn(controller) ? st.checkConditions()
                : st.zonesCheck();
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
    // 2 for each earlier cast of that commander from the command zone (Player.getCommanderCast), on a commander only
    private static int commanderTax(final ComboTracker.View v, final ComboLines.Line l) {
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
     * another's. Never while we cannot lose.
     */
    static boolean dangerNow(final Player ai) {
        if (ai.cantLose()) {
            return false;
        }
        final List<Card> ours = ai.getCreaturesInPlay();
        for (final Player opp : ai.getOpponents()) {
            if (lethal(ai, opp, ours)) {
                return true;
            }
        }
        return false;
    }

    // whether what opp's creatures that can attack us next turn would deal us (damageIfUnblocked) reaches our life
    // after our creatures block them: the largest hits are blocked first, each by as many of our spare creatures able
    // to block it (CombatUtil.canBlock) as it needs (CombatUtil.getMinNumBlockersForAttacker: two for menace), taking
    // those able to block the fewest of the hits still to come first, then in battlefield order. An attacker without
    // enough spare blockers comes through whole, a blocked one deals us nothing, and a blocked trampler puts through
    // its hit less its blockers' toughness. A board whose whole unblocked attack falls short is never blocked.
    private static boolean lethal(final Player ai, final Player opp, final List<Card> ours) {
        final List<Card> attackers = new ArrayList<>();
        final List<Integer> hits = new ArrayList<>();
        int unblocked = 0;
        for (final Card c : opp.getCreaturesInPlay()) {
            if (ComputerUtilCombat.canAttackNextTurn(c, ai)) {
                final int hit = ComputerUtilCombat.damageIfUnblocked(c, ai, null, true);
                attackers.add(c);
                hits.add(hit);
                unblocked += hit;
            }
        }
        final int life = ai.getLife();
        if (unblocked < life) {
            return false;
        }
        // the attackers' indexes, the largest hit first (a stable sort: equal hits in battlefield order)
        final List<Integer> order = new ArrayList<>();
        for (int i = 0; i < attackers.size(); i++) {
            order.add(i);
        }
        order.sort((a, b) -> Integer.compare(hits.get(b), hits.get(a)));
        final boolean[][] blocks = new boolean[attackers.size()][ours.size()];
        for (int i = 0; i < attackers.size(); i++) {
            for (int j = 0; j < ours.size(); j++) {
                blocks[i][j] = CombatUtil.canBlock(attackers.get(i), ours.get(j));
            }
        }
        final boolean[] spent = new boolean[ours.size()];
        int through = 0;
        for (int k = 0; k < order.size(); k++) {
            final int i = order.get(k);
            final Card attacker = attackers.get(i);
            final List<Integer> blockers = blockers(blocks, spent, order, k,
                    CombatUtil.getMinNumBlockersForAttacker(attacker, ai));
            if (blockers == null) {
                through += hits.get(i);
                continue;
            }
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
    // cost paid colour by colour from the mana we can make now (ComboMana: a Swamp is no Lightning Bolt's {R})
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
