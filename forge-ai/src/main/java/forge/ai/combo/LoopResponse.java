package forge.ai.combo;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import com.google.common.collect.Iterables;

import forge.ai.ComputerUtilCombat;
import forge.game.GameObject;
import forge.game.ability.AbilityUtils;
import forge.game.ability.ApiType;
import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.card.CardLists;
import forge.game.card.CardPredicates;
import forge.game.card.CounterEnumType;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.spellability.SpellAbilityStackInstance;
import forge.game.trigger.WrappedAbility;
import forge.game.zone.ZoneType;

/**
 * Going off in response to removal (spec 2026-10-29-combo-aware-ai-loops-design.md section 6.6): the reading of an
 * opponent's spell or ability on the stack that would take one of our cards off our battlefield, and of whether Walking
 * Ballista's volley fits in what the loop driver's caps have left (R28). The driver runs the go-off itself
 * (LoopDriver.responseAction). The stock threat reader (ComputerUtil.predictThreatenedObjects) is not used: it reads
 * destroy, exile, damage, -X/-X and control only, and our own items as threats too.
 *
 * <p>An item is read part by part: its root (a trigger's wrapped ability) and each sub-ability in turn, a modal spell's
 * chosen modes among them, which the engine chains as its subs. A part's targets are read as the engine reads a target
 * at resolution (MagicStack.hasFizzled): the card it targeted, if it is still that object (the same timestamp) and one
 * the part can still target; a part that targets nothing names its Defined cards. The "all" forms take in each card of
 * ours their kind does, as the engine filters it (AbilityUtils.filterListByType), within the player they target when
 * they target one. Not read: a fight, a counterspell aimed at a piece on the stack, a curse aura other than one that
 * gains control, damage divided among targets (read as its whole), damage something prevents once (a shield no static
 * shows), and any part whose amounts the engine may draw at random (a Count$Random among its params or the SVars it
 * reads), which is not read at all, so that nothing here draws a random number. Nothing here writes a card.
 */
final class LoopResponse {
    // the count the engine draws at random (AbilityUtils.xCount's Random), which no reading here evaluates
    private static final String RANDOM = "Count$Random";

    private LoopResponse() {
    }

    /** Whether {@code item}'s activator is an opponent of {@code ai}'s. */
    static boolean opposes(final Player ai, final SpellAbilityStackInstance item) {
        final Player activator = item.getActivatingPlayer();
        return activator != null && activator.isOpponentOf(ai);
    }

    /**
     * Whether {@code item}, an opponent's spell or ability on the stack, would take one of {@code cards} off our
     * battlefield (section 6.6): one of them on our battlefield, as it is now, that a part of the item destroys (one
     * that can be destroyed now: not indestructible, no shield counter, no regeneration shield), moves to another zone
     * (exile, a hand, a library, a graveyard) or back onto the battlefield under its own control (GainControl), puts
     * under its controller's control (GainControl, ExchangeControl, an aura that gains control), makes us sacrifice
     * (an edict aimed at us, R27: we choose the sacrifice, so only while too few of our other permanents of its kind
     * are there to sacrifice instead; one at random may be any of them), or deals lethal damage to, gives lethal -X/-X
     * or lethal -1/-1 counters (one counter more, for a proliferate in the same item, on a card that has one or gets
     * one). False for an item of ours or of no opponent's, and for none of {@code cards} on our battlefield. Draws no
     * random number.
     */
    static boolean threatens(final Player ai, final SpellAbilityStackInstance item, final Collection<Card> cards) {
        if (item == null || !opposes(ai, item)) {
            return false;
        }
        final List<Card> ours = new ArrayList<>();
        for (final Card c : ai.getCardsIn(ZoneType.Battlefield)) {
            if (cards.contains(c)) {
                ours.add(c);
            }
        }
        if (ours.isEmpty()) {
            return false;
        }
        final List<SpellAbility> parts = parts(item);
        boolean proliferates = false;
        for (final SpellAbility s : parts) {
            proliferates |= s.getApi() == ApiType.Proliferate;
        }
        for (final Card c : ours) {
            int counters = 0;
            for (final SpellAbility s : parts) {
                if (takes(ai, s, c, ours)) {
                    return true;
                }
                counters += minusCounters(s, c);
            }
            if (proliferates && (counters > 0 || c.getCounters(CounterEnumType.M1M1) > 0)) {
                counters++;
            }
            if (counters > 0 && c.canReceiveCounters(CounterEnumType.M1M1) && shrinks(c, counters)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether an opponent's item on the stack targets our card {@code c} as the object it is now (the final review's
     * round 1, LoopDriver.refusesBlink): a part of it, its root (a trigger's wrapped ability) or a sub-ability, holds a
     * target that is {@code c} with its game timestamp and can still target it, as the engine reads a target at
     * resolution (MagicStack.hasFizzled). A card that left the battlefield and came back is a new object: the removal
     * aimed at the old one names it no more. Reads targets alone, no amount, so it draws no random number.
     */
    static boolean targetsNow(final Player ai, final Card c) {
        for (final SpellAbilityStackInstance si : ai.getGame().getStack()) {
            if (!opposes(ai, si)) {
                continue;
            }
            SpellAbility root = si.getSpellAbility();
            if (root.isWrapper()) {
                root = ((WrappedAbility) root).getWrappedAbility();
            }
            for (SpellAbility s = root; s != null; s = s.getSubAbility()) {
                if (s.usesTargeting() && hits(s, c)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * The actions Walking Ballista's volley takes from the board as it stands, every one the driver returns (R28: a
     * go-off in response starts only when they fit in what the caps have left): the volley's pumps and pings
     * ({@link LoopSinks#volleyCost}), and the cycles of loop line {@code l} that make the mana they cost, at its
     * net a cycle ({@link LoopCycles#net}), each cycle's actions: a rock untap's two, the tap and the untap; the
     * flicker's, a tap of each land Palinchron's untap puts back, and the flicker. At a boundary the pool floating
     * there counts toward that mana; mid-cycle it does not, and the cycle in progress counts in full, so the count is
     * never short and errs high by less than a cycle there. Long.MAX_VALUE with no volley to price (no Ballista of
     * ours on our battlefield) or a line that nets nothing. Draws no random number.
     */
    static long volleyActions(final Player ai, final ComboLines.Line l) {
        final LoopSinks.VolleyCost cost = LoopSinks.volleyCost(ai);
        final int net = LoopCycles.net(ai, l);
        if (cost == null || net <= 0) {
            return Long.MAX_VALUE;
        }
        final boolean boundary = LoopCycles.boundary(ai, l);
        final long lacking = cost.mana() - (boundary ? LoopPool.floating(ai) : 0);
        long cycles = 0;
        if (lacking > 0) {
            cycles = (lacking + net - 1) / net + (boundary ? 0 : 1);
        }
        return cycles * cycleActions(ai, l) + cost.pumps() + cost.pings();
    }

    // the actions one cycle of l takes, each one the driver returns: the flicker's tap of each land Palinchron's untap
    // puts back, and the flicker; a rock untap's two, the tap and the untap. The response runs these two kinds alone
    private static int cycleActions(final Player ai, final ComboLines.Line l) {
        return l.loop() == LoopKind.FLICKER ? LoopCycles.putBack(ai).size() + 1 : 2;
    }

    // the item's parts that are read, in order: its root (a trigger's wrapped ability) and each sub-ability, each with
    // an effect, none whose amounts the engine may draw at random (atRandom)
    private static List<SpellAbility> parts(final SpellAbilityStackInstance item) {
        SpellAbility root = item.getSpellAbility();
        if (root.isWrapper()) {
            root = ((WrappedAbility) root).getWrappedAbility();
        }
        final List<SpellAbility> out = new ArrayList<>();
        for (SpellAbility s = root; s != null; s = s.getSubAbility()) {
            if (s.getApi() != null && !atRandom(s)) {
                out.add(s);
            }
        }
        return out;
    }

    // whether reading part s's amounts could draw a random number: a Count$Random among its params or the SVars it
    // reads, its own and its card's, which AbilityUtils.calculateAmount draws (a count it never evaluates here)
    private static boolean atRandom(final SpellAbility s) {
        for (final String value : s.getMapParams().values()) {
            if (value.contains(RANDOM)) {
                return true;
            }
        }
        for (final String value : s.getSVars().values()) {
            if (value.contains(RANDOM)) {
                return true;
            }
        }
        return false;
    }

    // whether part s takes our card c off our battlefield (see threatens), its -1/-1 counters aside (minusCounters)
    private static boolean takes(final Player ai, final SpellAbility s, final Card c, final List<Card> ours) {
        return switch (s.getApi()) {
            case Destroy -> destroyable(c) && hits(s, c);
            case DestroyAll -> destroyable(c) && takesIn(s, c, "ValidCards", false);
            case ChangeZone -> leaves(s) && hits(s, c);
            case ChangeZoneAll -> leaves(s) && fromTheBattlefield(s) && takesIn(s, c, "ChangeType", true);
            case GainControl -> s.hasParam("AllValid") ? takesIn(s, c, "AllValid", false) : hits(s, c);
            case ExchangeControl -> hits(s, c);
            case Attach -> "GainControl".equals(s.getParam("AILogic")) && hits(s, c);
            case Sacrifice -> sacrifices(ai, s, c, ours);
            case SacrificeAll -> s.hasParam("Defined") ? defined(s, c) : takesIn(s, c, "ValidCards", true);
            case DealDamage -> hits(s, c) && lethal(s, c);
            case DamageAll -> takesIn(s, c, "ValidCards", false) && lethal(s, c);
            case Pump -> hits(s, c) && shrinks(c, -amount(s, "NumDef", 0));
            case PumpAll -> takesIn(s, c, "ValidCards", false) && shrinks(c, -amount(s, "NumDef", 0));
            default -> false;
        };
    }

    // whether part s names our card c: one of its targets, read as the engine reads a target at resolution (still that
    // object, the same timestamp, and one s can still target); with no targeting, one of its Defined cards
    private static boolean hits(final SpellAbility s, final Card c) {
        if (!s.usesTargeting()) {
            return defined(s, c);
        }
        for (final GameObject o : s.getTargets()) {
            if (o instanceof Card target && c.equalsWithGameTimestamp(target) && s.canTarget(c, true)) {
                return true;
            }
        }
        return false;
    }

    // whether one of part s's Defined cards is our card c, still that object
    private static boolean defined(final SpellAbility s, final Card c) {
        if (!s.hasParam("Defined")) {
            return false;
        }
        for (final Card d : AbilityUtils.getDefinedCards(s.getHostCard(), s.getParam("Defined"), s)) {
            if (c.equalsWithGameTimestamp(d)) {
                return true;
            }
        }
        return false;
    }

    // whether an "all" part s takes in our card c: c's controller among the players s targets, when it targets (the
    // other "all" forms read their Defined players, PumpAll and ChangeZoneAll), and c among the cards of its kind,
    // the param named, as the engine filters them; with no such param every card when orAll, else none
    private static boolean takesIn(final SpellAbility s, final Card c, final String kind, final boolean orAll) {
        final Player controller = c.getController();
        if (s.usesTargeting()) {
            if (!Iterables.contains(s.getTargets().getTargetPlayers(), controller)) {
                return false;
            }
        } else if (s.hasParam("Defined") && (s.getApi() == ApiType.PumpAll || s.getApi() == ApiType.ChangeZoneAll)
                && !AbilityUtils.getDefinedPlayers(s.getHostCard(), s.getParam("Defined"), s).contains(controller)) {
            return false;
        }
        final String valid = s.getParam(kind);
        if (valid == null) {
            return orAll;
        }
        return AbilityUtils.filterListByType(new CardCollection(c), valid, s).contains(c);
    }

    // whether a ChangeZone part s moves what it names off the battlefield: to another zone, or onto the battlefield
    // under another player's control (GainControl); with no destination, nowhere
    private static boolean leaves(final SpellAbility s) {
        final String to = s.getParam("Destination");
        return to != null && (ZoneType.smartValueOf(to) != ZoneType.Battlefield || s.hasParam("GainControl"));
    }

    // whether a ChangeZoneAll part s moves cards from the battlefield: one of its origins
    private static boolean fromTheBattlefield(final SpellAbility s) {
        return s.hasParam("Origin") && ZoneType.listValueOf(s.getParam("Origin")).contains(ZoneType.Battlefield);
    }

    // whether a Sacrifice part s makes us sacrifice our card c (R27): it names us (its targeted players, else its
    // Defined ones, its activator by default), and for one of its kinds (SacValid, " & " between the kinds of a
    // SacEachValid), c is one of our permanents of it that can be sacrificed, and fewer of those are none of the
    // threatened cards than it takes (Amount): we choose the sacrifice. One at random (Random) may be any of them. A
    // sacrifice of its own host (Self) is its controller's
    private static boolean sacrifices(final Player ai, final SpellAbility s, final Card c, final List<Card> ours) {
        final String valid = s.getParamOrDefault("SacValid", "Self");
        if ("Self".equals(valid) || !names(s, ai)) {
            return false;
        }
        final int amount = amount(s, "Amount", 1);
        for (final String kind : valid.split(" & ")) {
            final CardCollection able = CardLists.filter(
                    AbilityUtils.filterListByType(ai.getCardsIn(ZoneType.Battlefield), kind, s),
                    CardPredicates.canBeSacrificedBy(s, true));
            if (!able.contains(c)) {
                continue;
            }
            if (s.hasParam("Random")) {
                return true;
            }
            int spare = 0;
            for (final Card a : able) {
                if (!ours.contains(a)) {
                    spare++;
                }
            }
            if (spare < amount) {
                return true;
            }
        }
        return false;
    }

    // whether part s names player p: among its targeted players, else among its Defined players (its activator by
    // default), as the engine reads a sacrifice's players (SpellAbilityEffect.getTargetPlayers)
    private static boolean names(final SpellAbility s, final Player p) {
        if (s.usesTargeting()) {
            return Iterables.contains(s.getTargets().getTargetPlayers(), p);
        }
        for (final String d : s.getParamOrDefault("Defined", "You").split(" & ")) {
            if (AbilityUtils.getDefinedPlayers(s.getHostCard(), d, s).contains(p)) {
                return true;
            }
        }
        return false;
    }

    // the -1/-1 counters part s puts on our card c: a PutCounter's aimed at it, or a PutCounterAll's that takes it in,
    // CounterNum of them (one by default); none from any other part
    private static int minusCounters(final SpellAbility s, final Card c) {
        if (!"M1M1".equals(s.getParam("CounterType"))) {
            return 0;
        }
        final boolean on = s.getApi() == ApiType.PutCounter ? hits(s, c)
                : s.getApi() == ApiType.PutCounterAll && takesIn(s, c, "ValidCards", false);
        return on ? Math.max(0, amount(s, "CounterNum", 1)) : 0;
    }

    // whether our card c can be destroyed now: not indestructible, no shield counter and no regeneration shield (the
    // stock reader's reading)
    private static boolean destroyable(final Card c) {
        return c.canBeDestroyed() && c.getCounters(CounterEnumType.SHIELD) == 0 && c.getShieldCount() == 0;
    }

    // whether part s's damage (NumDmg) is lethal to our creature c, as the stock reader reads it: c can be destroyed
    // now, and the damage the AI predicts it takes (the static prevention and replacement effects in force) reaches
    // what kills it
    private static boolean lethal(final SpellAbility s, final Card c) {
        if (!c.isCreature() || !destroyable(c)) {
            return false;
        }
        final int dmg = amount(s, "NumDmg", 0);
        return dmg > 0 && ComputerUtilCombat.predictDamageTo(c, dmg, s.getHostCard(), false)
                >= ComputerUtilCombat.getDamageToKill(c, false);
    }

    // whether our creature c dies of losing x toughness (a -X/-X, x -1/-1 counters): its toughness 0 or less then, or
    // the damage marked on it lethal then, c able to be destroyed (the stock reader's reading of a -X/-X)
    private static boolean shrinks(final Card c, final int x) {
        return x > 0 && c.isCreature()
                && (c.getNetToughness() <= x || (destroyable(c) && x >= ComputerUtilCombat.getDamageToKill(c, false)));
    }

    // part s's amount param as the engine reads it (AbilityUtils.calculateAmount: a number, a count, an X paid), else
    // otherwise
    private static int amount(final SpellAbility s, final String param, final int otherwise) {
        return s.hasParam(param) ? AbilityUtils.calculateAmount(s.getHostCard(), s.getParam(param), s) : otherwise;
    }
}
