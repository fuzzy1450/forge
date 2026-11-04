package forge.ai.combo;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.google.common.collect.Iterables;

import forge.ai.ComputerUtilCombat;
import forge.ai.LoopTracker;
import forge.game.Game;
import forge.game.GameEntity;
import forge.game.ability.AbilityKey;
import forge.game.ability.AbilityUtils;
import forge.game.ability.ApiType;
import forge.game.card.Card;
import forge.game.card.CardCopyService;
import forge.game.card.CounterEnumType;
import forge.game.phase.PhaseHandler;
import forge.game.phase.PhaseType;
import forge.game.player.GameLossReason;
import forge.game.player.Player;
import forge.game.replacement.ReplacementEffect;
import forge.game.replacement.ReplacementLayer;
import forge.game.replacement.ReplacementType;
import forge.game.spellability.SpellAbility;
import forge.game.staticability.StaticAbility;
import forge.game.staticability.StaticAbilityMode;
import forge.game.trigger.Trigger;
import forge.game.trigger.TriggerType;
import forge.game.zone.ZoneType;
import forge.util.FileSection;

/**
 * The sinks a mana loop spends on (spec 2026-10-29-combo-aware-ai-loops-design.md section 6.4): Walking Ballista and
 * Thrasios, Triton Hero. First the loss blockers: the creatures whose "can't happen" effect stops an opponent losing
 * or us winning and which the pings can kill, which Walking Ballista kills first (section 6.4, my call), read by
 * signal 2 for a loop line too ({@link ComboSignals#loopDenied}). Then Ballista's step, step 1 of section 6.4, read
 * from the board at each decision: the counters a kill needs ({@link #lethalNeeded}), whether Ballista can shoot
 * ({@link #ballistaCanShoot}), and its next action ({@link #ballista}) with the pool that action needs first
 * ({@link #ballistaPool}). With a kill in reach a Ballista of ours on our battlefield is pumped with {4} until their
 * counters together cover it, then shoots, every loss blocker first, then each opponent, one ping a decision; with
 * none on our battlefield, one is cast from our hand with X from the pool. With no kill in reach (a hexproof player)
 * Ballista is pumped, or cast, until our board is five times lethal (LoopTracker's decisive). A go-off in response to
 * removal reads what the volley takes ({@link #volleyCost}) to know whether it fits in what the caps have left (R28).
 *
 * <p>Then Thrasios's dig, step 3 of section 6.4, taken when the stock AI has nothing it wants to play: its {4}, one
 * activation a decision ({@link #thrasios}), while our library holds more than {@link #plainFloor} cards, a card in it
 * would add to our board ({@link #digWorth}, {@link #addsToBoard}) and our board is short of five times lethal. The
 * floor keeps our next four draw steps and every card our own mandatory draw triggers will draw us before the last of
 * them, a permanent card of ours in our hand or library counted as if it were on our battlefield (32 with
 * Jin-Gitaxias, Core Augur among those cards); a draw on a player's Nth card each turn is counted at its bound, once
 * for each player it can match in each turn (Faerie Mastermind's, on an opponent's second card: 13 in our Main 1 of a
 * 1v1); and no plain dig runs beside a mandatory draw trigger on our battlefield whose draws cannot be counted
 * (Nezahal, Primal Tide's). With Laboratory Maniac or Jace, Wielder of Mysteries on our battlefield the dig goes to
 * empty, whatever the library holds, and one activation more, whose draw wins: below {@link #LIBRARY_FLOOR} only while
 * we can win and no opponent has mana to answer the finish (R22).
 *
 * <p>Thassa's Oracle in our hand while it can win now ({@link #digsForOracle}: its cast not barred, its entering
 * trigger not disabled) is the Oracle line's (section 6.4 step 3, amended 2026-11-04 (the Oracle window)), and no dig
 * toward it runs in a main phase: the driver builds the pool to what a dig of our whole library costs
 * ({@link #oracleWindowNeed}) and Oracle's own price ({@link #oraclePool}), casts Oracle ({@link #oracleCast}), and
 * above its entering trigger digs to empty ({@link #windowDig}), one activation a decision, whatever mana the opponents
 * have open. The trigger then resolves on an empty library, its count of cards, none, no more than any devotion of ours
 * to blue, whatever has become of Oracle meanwhile; and a countered Oracle has cost no card. Beside a mandatory draw
 * trigger of ours on our battlefield whose draws cannot be counted ({@link #drawsUncounted}: Nezahal, Primal Tide's)
 * the line waits, Oracle held, as no plain dig runs there either.
 *
 * <p>What a step costs us in life is priced by the driver (R30, LoopDriver.lifeCost): Thrasios digs only for none, and
 * the rest of Ballista's volley is priced here ({@link #volleyLife}), which the driver weighs against our life.
 *
 * <p>Nothing here draws a random number, and no card of the game is written but the abilities read or returned: their
 * activator, a cast's X and a ping's target on the action returned, and a cast's X for a moment while its price
 * beyond {X}{X} is read. The Ballista whose ping is aimed in a reading is an LKI copy.
 */
final class LoopSinks {
    /** Walking Ballista, the sink whose pings kill, as the table and the deck lists name it. */
    static final String BALLISTA = "Walking Ballista";

    /** Thrasios, Triton Hero, the sink that digs, as the deck lists name it. */
    static final String THRASIOS = "Thrasios, Triton Hero";

    /** Thrasios activates only while our library holds more cards than this (section 6.4 step 3): DrawAi's own floor,
     *  which refuses a draw at 4 cards or fewer, our next four draw steps (R23); the dig to empty with Laboratory
     *  Maniac or Jace may go below it ({@link #thrasios}, R22), the draws our own triggers are due to make raise the
     *  plain dig's ({@link #plainFloor}), and the Oracle line's window digs to empty ({@link #windowDig}). */
    static final int LIBRARY_FLOOR = 4;

    // Thassa's Oracle's own blue pips, its {U}{U}: its devotion to blue once it has entered, which its win counts
    private static final int ORACLE_PIPS = 2;

    // a reading with no Walking Ballista of ours on our battlefield or in our hand: no volley
    private static final Volley NONE = new Volley(null, List.of(), List.of(), 0);

    // one reading of the volley: the aim (the ping of an LKI copy of our first Walking Ballista, on our battlefield or
    // else in our hand, placed on our battlefield, us its activator), the loss blockers it can kill, the opponents it
    // can kill (each in its order), and the counters the kill needs
    private record Volley(SpellAbility aim, List<Card> blockers, List<Player> killable, int needed) {
    }

    // Ballista's next step: the ability, the pool it needs first, a cast's X for the pool as it stands, a ping's
    // target
    private record Step(SpellAbility sa, int pool, int x, GameEntity target) {
    }

    private LoopSinks() {
    }

    /**
     * The +1/+1 counters Walking Ballista's volley needs to kill (section 6.4 step 1): the life of every opponent the
     * volley can kill, one the ping can target, who can lose life, and who loses for having 0 or less life or would
     * but for loss blockers the volley kills (every source of that "can't happen" is one of {@link #lossBlockers});
     * plus the toughness left of each loss blocker. The counters on our Ballistas count toward it. 0 while no Walking
     * Ballista of ours is on our battlefield or in our hand.
     */
    static int lethalNeeded(final Player ai) {
        return volley(ai).needed();
    }

    /**
     * Whether Walking Ballista can shoot now: {@link #lethalNeeded} above 0, and a Ballista of ours on our battlefield
     * whose ping passes the driver's own check but its payment ({@link LoopDriver#allowed}: a counter to remove, no
     * static refusing it) and can target the volley's next target (the first loss blocker left, else the first
     * opponent it can kill); with none on our battlefield, one in our hand whose cast the driver may make now and
     * whose ping, read on it as it would be on our battlefield, no static refuses.
     */
    static boolean ballistaCanShoot(final Player ai) {
        final Volley v = volley(ai);
        return v.needed() > 0 && shoots(ai, v);
    }

    /**
     * Walking Ballista's next action, read from the board, us its activator: a cast from our hand with X set on it (on
     * the root), a pump with {4}, or a ping with its target set ({@code resetTargets}, then the target added); null
     * when Ballista has nothing to do. The pool is to hold {@link #ballistaPool} first: a cast's X is what the pool
     * pays now, at most what the kill needs, and never 0 (then null).
     * <ul>
     * <li>A kill in reach ({@link #ballistaCanShoot}): with no Ballista on our battlefield, the first in our hand is
     * cast with X up to {@link #lethalNeeded}; with some there, while their counters together are below it, the one
     * with the most (the first of those) is pumped; at or above it, the first whose ping the driver may make shoots,
     * every loss blocker first, then each opponent it can kill.</li>
     * <li>No kill in reach and our board short of five times lethal against the opponents who can lose (a hexproof
     * player): the one on our battlefield with the most counters is pumped; with none there, the one in our hand is
     * cast with X up to the counters that make the board five times lethal (R4), its pump read on it as it would be on
     * our battlefield first.</li>
     * </ul>
     * Every action here is one the driver may make now (LoopDriver.allowed); the driver checks its payment and its
     * target again before it returns it. Under Pithing Needle naming Walking Ballista, or Cursed Totem, Ballista has
     * nothing to do.
     */
    static SpellAbility ballista(final Player ai) {
        final Step s = step(ai);
        if (s == null) {
            return null;
        }
        final SpellAbility sa = s.sa();
        sa.setActivatingPlayer(ai);
        if (sa.isSpell()) {
            if (s.x() < 1) {
                return null;
            }
            sa.getRootAbility().setXManaCostPaid(s.x());
        } else if (s.target() != null) {
            sa.resetTargets();
            sa.getTargets().add(s.target());
        }
        return sa;
    }

    /**
     * The mana the pool must hold before {@link #ballista}'s action is taken; -1 when Ballista has nothing to do. A
     * ping or a pump needs its cost as the engine prices it now (a ping none, a pump {4}, a tax included); a cast
     * twice the counters it is for and what the engine adds to the cast beyond {X}{X} (a tax), at most the loop's pool
     * of {@link LoopPool#TARGET}, the rest made up by pumps.
     */
    static int ballistaPool(final Player ai) {
        final Step s = step(ai);
        return s == null ? -1 : s.pool();
    }

    /** What Walking Ballista's volley takes ({@link #volleyCost}): its pumps, its pings, and the mana they cost. */
    record VolleyCost(long pumps, long pings, long mana) {
    }

    /**
     * What Walking Ballista's volley takes from the board as it stands, which a go-off in response reads to know
     * whether the volley fits in what the driver's caps have left (R28): its pings, one for each counter the kill
     * needs ({@link #lethalNeeded}); its pumps, the counters our Ballistas on our battlefield lack of it; and the mana
     * they cost as the engine prices our first Ballista's ping and pump now (a ping none, a pump {4}, a tax included).
     * Null with no Ballista of ours on our battlefield (one in our hand is not cast above the removal), with no kill
     * to make, and for a Ballista without its ping or its pump.
     */
    static VolleyCost volleyCost(final Player ai) {
        final List<Card> ours = ours(ai);
        final int needed = lethalNeeded(ai);
        if (ours.isEmpty() || needed <= 0) {
            return null;
        }
        final SpellAbility ping = pingOf(ours.get(0));
        final SpellAbility pump = pumpOf(ours.get(0));
        if (ping == null || pump == null) {
            return null;
        }
        final long pumps = Math.max(0, needed - counters(ours));
        final long mana = needed * (long) ComboExecute.priced(ai, ping).getCMC()
                + pumps * ComboExecute.priced(ai, pump).getCMC();
        return new VolleyCost(pumps, needed, mana);
    }

    /**
     * R30: the life the rest of Walking Ballista's volley costs us, an upper bound, each of its actions priced as the
     * driver prices one (LoopDriver.lifeCost): its pings, one for each counter the kill needs ({@link #lethalNeeded}),
     * at the price of our first Ballista's ping, read on it as it is on our battlefield; its pumps, the counters our
     * Ballistas on our battlefield lack of it (with none there, those a cast from our hand at the loop's pool would
     * leave), at its pump's price; that cast, when none is on our battlefield; and the cycles of the loop line feeding
     * the volley, {@code perCycle} each, as many as the mana its pumps, pings and cast still need beyond the pool takes
     * at {@code net} a cycle, and one more. 0 when none of these costs life, and with no Ballista of ours on our
     * battlefield or in our hand. With no kill in reach (a hexproof player: the five-times path), Long.MAX_VALUE as
     * soon as one of them costs life, since only a kill this turn is worth our life.
     */
    static long volleyLife(final Player ai, final int net, final int perCycle) {
        final Volley v = volley(ai);
        if (v.aim() == null) {
            return 0;
        }
        final List<Card> ours = ours(ai);
        final SpellAbility pump = pumpOf(v.aim().getHostCard());
        final SpellAbility cast = ours.isEmpty() ? castOf(first(ai, ZoneType.Hand)) : null;
        if (pump != null) {
            pump.setActivatingPlayer(ai);
        }
        if (cast != null) {
            cast.setActivatingPlayer(ai);
        }
        final long pingLife = LoopDriver.lifeCost(ai, v.aim());
        final long pumpLife = pump == null ? 0 : LoopDriver.lifeCost(ai, pump);
        final long castLife = cast == null ? 0 : LoopDriver.lifeCost(ai, cast);
        if (pingLife + pumpLife + castLife + perCycle == 0) {
            return 0;
        }
        if (v.needed() <= 0 || !shoots(ai, v) || (ours.isEmpty() && cast == null)) {
            return Long.MAX_VALUE;
        }
        final long needed = v.needed();
        final long pumpMana = pump == null ? 0 : ComboExecute.priced(ai, pump).getCMC();
        long pumps;
        long mana = needed * ComboExecute.priced(ai, v.aim()).getCMC();
        if (!ours.isEmpty()) {
            pumps = Math.max(0, needed - counters(ours));
        } else {
            final int beyond = beyondX(ai, cast);
            pumps = Math.max(0, needed - Math.max(0, (LoopPool.TARGET - beyond) / 2));
            mana += Math.min(2L * needed + beyond, LoopPool.TARGET);
        }
        mana += pumps * pumpMana;
        long cycles = 0;
        final long lacking = mana - LoopPool.floating(ai);
        if (perCycle > 0 && lacking > 0) {
            if (net <= 0) {
                return Long.MAX_VALUE;
            }
            cycles = (lacking + net - 1) / net + 1;
        }
        return castLife + pumps * pumpLife + needed * pingLife + cycles * perCycle;
    }

    /** What a ping changes on a creature: its toughness left, the damage that kills it less the damage marked on it
     *  (none below 0). */
    static int toughnessLeft(final Card c) {
        return Math.max(0, c.getLethal() - c.getDamage());
    }

    /** The first of {@link #lossBlockers}, or null. */
    static Card lossBlocker(final Player ai) {
        final List<Card> blockers = lossBlockers(ai);
        return blockers.isEmpty() ? null : blockers.get(0);
    }

    /**
     * Every loss blocker: a creature on the battlefield, whoever controls it, one of whose "can't happen" effects in
     * force stops {@code ai} winning (GameWin) or stops an opponent of {@code ai} losing for a life total of 0 or less
     * (GameLoss), the loss a ping or an attack deals (the opponent's Platinum Angel holds both, {@code ai}'s own
     * Abyssal Persecutor the first), which the ping of a Walking Ballista of {@code ai}'s can target, read as the
     * engine reads a target (hexproof, shroud, protection), and which the pings can kill (R25): it can be destroyed
     * (no indestructible), and a ping's damage to it is not prevented, as the AI predicts damage
     * (ComputerUtilCombat.predictDamageTo: the static prevention and replacement effects in force). In the
     * battlefield's order (the players' order, then each one's battlefield); immutable. Empty while no Walking
     * Ballista of {@code ai}'s is on its battlefield or in its hand: there is no ping to aim.
     */
    static List<Card> lossBlockers(final Player ai) {
        final SpellAbility ping = aim(ai);
        return ping == null ? List.of() : lossBlockers(ai, ping);
    }

    /**
     * Whether Walking Ballista can free {@code ai}'s win, which some "can't happen" effect stops now (signal 2 for a
     * loop line): a Walking Ballista of {@code ai}'s is on its battlefield or in its hand, and every source of
     * {@link Player#cantWin}, each card of the game one of whose GameWin "can't happen" effects in force stops
     * {@code ai} winning (the engine's own reading: every card it visits, each effect's zones, requirements and whether
     * it can replace), is a loss blocker ({@link #lossBlockers}).
     */
    static boolean freesTheWin(final Player ai) {
        final SpellAbility ping = aim(ai);
        if (ping == null) {
            return false;
        }
        final List<Card> blockers = lossBlockers(ai, ping);
        final Map<AbilityKey, Object> ours = AbilityKey.mapFromAffected(ai);
        for (final Card c : ai.getGame().getCardsInGame()) {
            if (cantHappen(c, ReplacementType.GameWin, ours) && !blockers.contains(c)) {
                return false;
            }
        }
        return true;
    }

    // the loss blockers the ping can target and the pings can kill (R25)
    private static List<Card> lossBlockers(final Player ai, final SpellAbility ping) {
        final List<Card> out = new ArrayList<>(1);
        for (final Card c : ai.getGame().getCardsIn(ZoneType.Battlefield)) {
            if (c.isCreature() && stopsOurKill(c, ai) && ping.canTarget(c) && pingsKill(c, ping.getHostCard())) {
                out.add(c);
            }
        }
        return List.copyOf(out);
    }

    // R25: the pings of that Ballista can kill the creature: it can be destroyed, so lethal damage destroys it (no
    // indestructible: Darksteel Plate), and a ping's damage to it is not prevented (Inviolability, Bubble Matrix), as
    // the AI predicts a ping's 1 damage. A shield that prevents the next damage only, which no static reading sees, is
    // the volley's own judge's (LoopDriver: a ping that changes nothing ends the volley)
    private static boolean pingsKill(final Card c, final Card ballista) {
        return c.canBeDestroyed() && ComputerUtilCombat.predictDamageTo(c, 1, ballista, false) > 0;
    }

    // whether one of c's "can't happen" effects in force stops ai winning, or an opponent of ai losing for a life total
    // of 0 or less
    private static boolean stopsOurKill(final Card c, final Player ai) {
        if (cantHappen(c, ReplacementType.GameWin, AbilityKey.mapFromAffected(ai))) {
            return true;
        }
        for (final Player opp : ai.getOpponents()) {
            final Map<AbilityKey, Object> runParams = AbilityKey.mapFromAffected(opp);
            runParams.put(AbilityKey.LoseReason, GameLossReason.LifeReachedZero);
            if (cantHappen(c, ReplacementType.GameLoss, runParams)) {
                return true;
            }
        }
        return false;
    }

    // whether one of c's "can't happen" effects of that event, in force, would replace the event: read as the engine
    // reads one (ReplacementHandler.getReplacementList for the CantHappen layer: the effect's layer and mode, whether
    // it has run, its host's zone, its requirements, then whether it can replace these run params)
    private static boolean cantHappen(final Card c, final ReplacementType event,
            final Map<AbilityKey, Object> runParams) {
        if (c.knownToHaveNoReplacementEffects()) {
            return false;
        }
        final Game game = c.getGame();
        for (final ReplacementEffect re : c.getReplacementEffects()) {
            if (re.getLayer() == ReplacementLayer.CantHappen && !re.hasRun() && re.modeCheck(event, runParams)
                    && re.zonesCheck(game.getZoneOf(c)) && re.requirementsCheck(game) && re.canReplace(runParams)) {
                return true;
            }
        }
        return false;
    }

    // the aim: the ping of ai's Walking Ballista, on ai's battlefield, else in ai's hand, on an LKI copy of that
    // Ballista last known on ai's battlefield, ai its activator, for the readings of what it can target; null with none
    private static SpellAbility aim(final Player ai) {
        Card ballista = first(ai, ZoneType.Battlefield);
        if (ballista == null) {
            ballista = first(ai, ZoneType.Hand);
        }
        if (ballista == null) {
            return null;
        }
        final Card copy = CardCopyService.getLKICopy(ballista);
        copy.setLastKnownZone(ai.getZone(ZoneType.Battlefield));
        final SpellAbility sa = pingOf(copy);
        if (sa != null) {
            sa.setActivatingPlayer(ai);
        }
        return sa;
    }

    // ai's first Walking Ballista in that zone of ai's (by ComboTracker.nameOf, in the zone's order), or null
    private static Card first(final Player ai, final ZoneType zone) {
        for (final Card c : ai.getCardsIn(zone)) {
            if (BALLISTA.equals(ComboTracker.nameOf(c))) {
                return c;
            }
        }
        return null;
    }

    // ai's Walking Ballistas on ai's battlefield, in its order
    private static List<Card> ours(final Player ai) {
        final List<Card> out = new ArrayList<>(1);
        for (final Card c : ai.getCardsIn(ZoneType.Battlefield)) {
            if (BALLISTA.equals(ComboTracker.nameOf(c))) {
                out.add(c);
            }
        }
        return out;
    }

    // a Ballista's ping: its activated DealDamage ability that targets; null with none
    private static SpellAbility pingOf(final Card b) {
        for (final SpellAbility sa : b.getNonManaAbilities()) {
            if (sa.isActivatedAbility() && sa.getApi() == ApiType.DealDamage && sa.usesTargeting()) {
                return sa;
            }
        }
        return null;
    }

    // a Ballista's {4} pump: its activated PutCounter ability, untargeted; null with none
    private static SpellAbility pumpOf(final Card b) {
        for (final SpellAbility sa : b.getNonManaAbilities()) {
            if (sa.isActivatedAbility() && sa.getApi() == ApiType.PutCounter && !sa.usesTargeting()) {
                return sa;
            }
        }
        return null;
    }

    // the volley as the board stands: the aim, the loss blockers it can kill, the opponents it can kill and the
    // counters the kill needs (see lethalNeeded); NONE with no Ballista of ours on our battlefield or in our hand
    private static Volley volley(final Player ai) {
        final SpellAbility aim = aim(ai);
        if (aim == null) {
            return NONE;
        }
        final List<Card> blockers = lossBlockers(ai, aim);
        final List<Player> killable = new ArrayList<>(2);
        long needed = 0;
        for (final Card b : blockers) {
            needed += toughnessLeft(b);
        }
        for (final Player o : ai.getOpponents()) {
            if (killable(o, aim, blockers)) {
                killable.add(o);
                needed += Math.max(0, o.getLife());
            }
        }
        return new Volley(aim, blockers, List.copyOf(killable), (int) Math.min(needed, Integer.MAX_VALUE));
    }

    // whether the volley can kill opponent o: the aim can target o, o can lose life, and o loses for having 0 or less
    // life, or would but for loss blockers the volley kills: every source of that "can't happen" in force, each card
    // of the game read as the engine reads one, is one of them
    private static boolean killable(final Player o, final SpellAbility aim, final List<Card> blockers) {
        if (!aim.canTarget(o) || !o.canLoseLife()) {
            return false;
        }
        if (!o.cantLoseForZeroOrLessLife()) {
            return true;
        }
        final Map<AbilityKey, Object> runParams = AbilityKey.mapFromAffected(o);
        runParams.put(AbilityKey.LoseReason, GameLossReason.LifeReachedZero);
        for (final Card c : o.getGame().getCardsInGame()) {
            if (cantHappen(c, ReplacementType.GameLoss, runParams) && !blockers.contains(c)) {
                return false;
            }
        }
        return true;
    }

    // the volley's next target: the first loss blocker with toughness left, else the first opponent it can kill with
    // life left; null with neither
    private static GameEntity nextTarget(final Volley v) {
        for (final Card b : v.blockers()) {
            if (toughnessLeft(b) > 0) {
                return b;
            }
        }
        for (final Player o : v.killable()) {
            if (o.getLife() > 0) {
                return o;
            }
        }
        return null;
    }

    // whether a Ballista of ours can aim the volley's next ping (see ballistaCanShoot)
    private static boolean shoots(final Player ai, final Volley v) {
        final GameEntity target = nextTarget(v);
        if (target == null) {
            return false;
        }
        final List<Card> ours = ours(ai);
        if (!ours.isEmpty()) {
            return pinger(ai, ours, target) != null;
        }
        final SpellAbility cast = castOf(first(ai, ZoneType.Hand));
        return cast != null && LoopDriver.allowed(ai, cast) && v.aim().checkRestrictions(ai);
    }

    // Ballista's next step, read from the board (see ballista); null when Ballista has nothing to do
    private static Step step(final Player ai) {
        final Volley v = volley(ai);
        if (v.aim() == null) {
            return null;
        }
        final List<Card> ours = ours(ai);
        if (v.needed() > 0 && shoots(ai, v)) {
            if (ours.isEmpty()) {
                return cast(ai, v.needed());
            }
            if (counters(ours) >= v.needed()) {
                return ping(ai, ours, nextTarget(v));
            }
            return pump(ai, ours);
        }
        final int toFive = toFiveTimes(ai);
        if (toFive <= 0) {
            return null;
        }
        if (!ours.isEmpty()) {
            return pump(ai, ours);
        }
        final SpellAbility pump = pumpOf(v.aim().getHostCard());
        if (pump == null) {
            return null;
        }
        pump.setActivatingPlayer(ai);
        return pump.checkRestrictions(ai) ? cast(ai, toFive) : null;
    }

    // the ping at the target as a step: the pool it needs is its cost as the engine prices it now, none but a tax
    // (Suppression Field's {2}); null with no ping for it, or no target
    private static Step ping(final Player ai, final List<Card> ours, final GameEntity target) {
        final SpellAbility ping = target == null ? null : pinger(ai, ours, target);
        return ping == null ? null : new Step(ping, ComboExecute.priced(ai, ping).getCMC(), 0, target);
    }

    // the first ping, in our battlefield's order, that the driver may make now and that can target the target; null
    // with none
    private static SpellAbility pinger(final Player ai, final List<Card> ours, final GameEntity target) {
        for (final Card b : ours) {
            final SpellAbility ping = pingOf(b);
            if (ping != null && LoopDriver.allowed(ai, ping) && ping.canTarget(target)) {
                return ping;
            }
        }
        return null;
    }

    // the pump of the Ballista with the most counters (the first of those, in our battlefield's order) among those
    // whose pump the driver may make now; the pool it needs is its cost as the engine prices it now
    private static Step pump(final Player ai, final List<Card> ours) {
        Card best = null;
        SpellAbility bestPump = null;
        for (final Card b : ours) {
            final SpellAbility pump = pumpOf(b);
            if (pump != null && LoopDriver.allowed(ai, pump) && (best == null
                    || b.getCounters(CounterEnumType.P1P1) > best.getCounters(CounterEnumType.P1P1))) {
                best = b;
                bestPump = pump;
            }
        }
        if (bestPump == null) {
            return null;
        }
        return new Step(bestPump, ComboExecute.priced(ai, bestPump).getCMC(), 0, null);
    }

    // the cast of our first Walking Ballista in our hand for up to wanted counters, when the driver may make it now:
    // the pool built to twice them and what the engine adds beyond {X}{X} (a tax), at most LoopPool.TARGET; X what
    // the pool pays now beyond that, at most wanted. Null when no X of 1 fits under the cap
    private static Step cast(final Player ai, final int wanted) {
        final SpellAbility cast = castOf(first(ai, ZoneType.Hand));
        if (cast == null || !LoopDriver.allowed(ai, cast)) {
            return null;
        }
        final int beyond = beyondX(ai, cast);
        final int pool = (int) Math.min(2L * wanted + beyond, LoopPool.TARGET);
        if (pool - beyond < 2) {
            return null;
        }
        final int x = Math.min(wanted, Math.max(0, LoopPool.floating(ai) - beyond) / 2);
        return new Step(cast, pool, x, null);
    }

    // the card's cast: the first of its spells; null for no card, or one with none
    private static SpellAbility castOf(final Card c) {
        return c == null ? null : Iterables.getFirst(c.getSpells(), null);
    }

    // what the engine adds to our cast beyond {X}{X} now (a tax: Sphere of Resistance), read as the engine prices the
    // cast with X at 0, the X it had put back after
    private static int beyondX(final Player ai, final SpellAbility cast) {
        final SpellAbility root = cast.getRootAbility();
        final Integer had = root.getXManaCostPaid();
        root.setXManaCostPaid(0);
        try {
            return ComboExecute.priced(ai, cast).getCMC();
        } finally {
            root.setXManaCostPaid(had);
        }
    }

    // the +1/+1 counters on our Ballistas, together
    private static long counters(final List<Card> ours) {
        long n = 0;
        for (final Card b : ours) {
            n += b.getCounters(CounterEnumType.P1P1);
        }
        return n;
    }

    // the counters that would make our board five times lethal (LoopTracker's decisive: our attack power against
    // LoopTracker.LETHAL_MULTIPLE times the summed life of the opponents who can lose, as its Snapshot reads both); 0
    // once it is, and while no opponent can lose, where it never is
    private static int toFiveTimes(final Player ai) {
        long life = 0;
        for (final Player o : ai.getOpponents()) {
            if (!o.cantLose()) {
                life += Math.max(0, o.getLife());
            }
        }
        if (life == 0) {
            return 0;
        }
        final long missing = LoopTracker.LETHAL_MULTIPLE * life - LoopTracker.Snapshot.of(ai).ownPower();
        return (int) Math.max(0, Math.min(missing, Integer.MAX_VALUE));
    }

    /**
     * Whether {@code c} adds to our board (section 6.4 step 3: a card that would raise our attack power, or Chromatic
     * Orrery, which lets the pool pay for the rest): a creature card, Walking Ballista and Ulamog among them; a card a
     * part of one of whose spells puts a creature onto the battlefield (a ChangeZone to the battlefield whose
     * ChangeType names a creature: Finale of Devastation, Green Sun's Zenith; not one that puts a land there, R20:
     * Cultivate) or pumps (a Pump or PumpAll that is no curse: Overwhelming Stampede); or a card with a ManaConvert
     * static (Chromatic Orrery). Read from the card's own parts, never its name.
     */
    static boolean addsToBoard(final Card c) {
        if (c.isCreature()) {
            return true;
        }
        for (final SpellAbility spell : c.getSpells()) {
            for (SpellAbility s = spell; s != null; s = s.getSubAbility()) {
                if (putsACreature(s) || pumps(s)) {
                    return true;
                }
            }
        }
        for (final StaticAbility st : c.getStaticAbilities()) {
            if (st.checkMode(StaticAbilityMode.ManaConvert)) {
                return true;
            }
        }
        return false;
    }

    /** Whether our library holds a card that adds to our board ({@link #addsToBoard}) and that the pool, the kinds loop
     *  line {@code l} makes, our untapped lands and Chromatic Orrery could pay for ({@link LoopPool#payable}): what the
     *  library holds, never its order (section 6.4: a player knows its own list). */
    static boolean digWorth(final Player ai, final ComboLines.Line l) {
        for (final Card c : ai.getCardsIn(ZoneType.Library)) {
            if (addsToBoard(c) && LoopPool.payable(ai, c, l)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Thrasios's next activation in a main phase (section 6.4 step 3), us its activator; null when Thrasios has nothing
     * to do. It is the {4} of our first Thrasios, Triton Hero on our battlefield whose activation the driver may make
     * now (LoopDriver.allowed: under Cursed Totem, none), while
     * <ul>
     * <li>a Laboratory Maniac or Jace, Wielder of Mysteries of ours on our battlefield holds its win (a Draw
     * replacement that wins instead): a dig to empty, whatever the library holds, and once it is empty one activation
     * more, whose draw wins; while our library holds more than {@link #LIBRARY_FLOOR} cards, and below that only while
     * we can win and no opponent has an untapped permanent with a mana ability, or mana in their pool, to answer the
     * finish (R22: only a free counterspell can stop it), a draw from an empty library winning for it;</li>
     * <li>with neither, our library holds more than {@link #plainFloor} cards (our next four draw steps and the draws
     * due before the last of them, our own triggers' and those of our permanent cards in our hand and library, as if on
     * our battlefield), a card worth the dig ({@link #digWorth}), and our board is short of five times lethal
     * (LoopTracker's decisive).</li>
     * </ul>
     * No dig toward Thassa's Oracle runs here (amended 2026-11-04 (the Oracle window)): while the Oracle line can run
     * the driver builds for it and casts Oracle first, digging in its window ({@link #windowDig}); otherwise, Oracle in
     * our hand, the dig is a plain one.
     */
    static SpellAbility thrasios(final Player ai, final ComboLines.Line l) {
        final SpellAbility dig = readyThrasios(ai);
        if (dig == null) {
            return null;
        }
        final int library = ai.getCardsIn(ZoneType.Library).size();
        if (winsOnEmpty(ai)) {
            return library > LIBRARY_FLOOR || belowTheFloor(ai) ? dig : null;
        }
        return library > plainFloor(ai) && digWorth(ai, l) && !LoopTracker.Snapshot.of(ai).isDecisive() ? dig : null;
    }

    /**
     * The cards our library keeps when a plain dig stops at the floor (section 6.4 step 3, amended by the final
     * review's round 1, its compliance review's round 1 and on 2026-11-04 for Faerie Mastermind's bound; the floor of
     * the dig toward Thassa's Oracle, which counted the draws before our next draw step alone, retired with that dig on
     * 2026-11-04 (the Oracle window)): enough for our next {@link #LIBRARY_FLOOR} draw steps, R23's four turns of
     * draws, and every card our own mandatory draw triggers will draw us before the last of them, whatever our library
     * holds, so that the floor still holds after them. A draw is ours to count when there is no OptionalDecider on its
     * trigger or on its Draw, the Draw's own Defined is us (You, or none) or each player, its NumCards is a plain
     * number, and its trigger is
     * <ul>
     * <li>a phase's (Mode$ Phase): counted once for each of its phases still to come before our next draw step, in the
     * rest of this turn of ours, in every other player's turn and at our next untap and upkeep, and once more for each
     * of its phases in each of the three rounds of turns after that step, each where its ValidPlayer takes that turn's
     * player;</li>
     * <li>or a Drawn trigger whose Number is a plain number N (Mode$ Drawn, Number$ 2: a player's second card each
     * turn), which the engine fires only when a player's count of the cards drawn this turn reaches N (TriggerDrawn),
     * so at most once for each player in each turn: counted at that bound, once for each living player its ValidPlayer
     * can match in each turn from this one to the fourth of our draw steps from now, each turn counted once, whole, by
     * its player, whatever the turn order: the rest of this turn, which a player who has drawn N cards or more this
     * turn cannot fire again, one turn of each other living player but the one whose turn this is, our next, and three
     * rounds of each living player's turns. Of what follows a word of its ValidPlayer only Active is read (The Council
     * of Four's Player.Active: that turn's player alone); the rest only narrows the word, so the count, leaving it out,
     * stays an upper bound.</li>
     * </ul>
     * Faerie Mastermind's one on an opponent's second card keeps 13 in our Main 1 of a 1v1, and 55 in a four-player
     * pod's. With them the draws of the permanent cards of ours in our hand and in our library, each as if it were on
     * our battlefield now, since the go-off's pool may cast one, or the dig draw it, once the dig has gone below what
     * its arrival needs: so Jin-Gitaxias, Core Augur's seven at each of our end steps keeps 32 in our Main 1, wherever
     * Jin is among those cards, where the dig used to stop at four and Jin, cast after it or drawn by it, decked us.
     * Integer.MAX_VALUE, so that no plain dig runs, while a mandatory draw of ours on our battlefield is due on any
     * other trigger, which waits on what another player does (Nezahal, Primal Tide's, on each noncreature spell an
     * opponent casts; a Drawn trigger with no Number, on each card an opponent draws), or draws a number not written
     * plainly: its draws cannot be counted; one on a card in our hand or library is not counted (Nezahal, Primal Tide's
     * in our library would stop every plain dig). An optional draw (Rhystic Study's "you may") is the stock AI's to
     * decline, batch 4's library floor in its confirmTrigger. The triggers' abilities are read from their text, never
     * built. Draws no random number.
     */
    static int plainFloor(final Player ai) {
        final long due = drawsDue(ai);
        return due < 0 ? Integer.MAX_VALUE : (int) Math.min(LIBRARY_FLOOR + due, Integer.MAX_VALUE);
    }

    /** Whether a mandatory draw trigger of ours on our battlefield draws what no floor can count, so that no plain dig
     *  runs ({@link #plainFloor}: Integer.MAX_VALUE) and the Oracle line waits, Oracle held (LoopDriver.oracleLine,
     *  amended 2026-11-04 (the Oracle window, its review's round 1)): one that waits on what another player does
     *  (Nezahal, Primal Tide's, on each noncreature spell an opponent casts; a Drawn trigger with no plain Number, on
     *  each card an opponent draws), or draws a number not written plainly. Above Oracle's trigger, our library dug to
     *  empty, any such play in response would draw us from it before the trigger resolved. Read from our battlefield
     *  alone, as the plain floor reads such a draw: one on a card in our hand or library is not read. Draws no random
     *  number. */
    static boolean drawsUncounted(final Player ai) {
        return drawsOnOurBattlefield(ai, ai.getGame().getPhaseHandler()) < 0;
    }

    // the draws plainFloor counts, those of the triggers on our battlefield and of our permanent cards in our hand and
    // library (drawsToCome); -1 when one on our battlefield cannot be counted (see plainFloor)
    private static long drawsDue(final Player ai) {
        final PhaseHandler ph = ai.getGame().getPhaseHandler();
        final long due = drawsOnOurBattlefield(ai, ph);
        return due < 0 ? -1 : due + drawsToCome(ai, ph);
    }

    // the draws plainFloor counts for the triggers on our battlefield; -1 when one cannot be counted (see plainFloor)
    private static long drawsOnOurBattlefield(final Player ai, final PhaseHandler ph) {
        long due = 0;
        for (final Card c : ai.getCardsIn(ZoneType.Battlefield)) {
            for (final Trigger t : c.getTriggers()) {
                if (t.isSuppressed() || !t.zonesCheck(ai.getGame().getZoneOf(c))) {
                    continue;
                }
                final Map<String, String> draw = ourDraw(t);
                if (draw == null) {
                    continue;
                }
                final long n = draws(ai, t, draw, ph);
                if (n < 0) {
                    return -1;
                }
                due += n;
            }
        }
        return due;
    }

    // the draws plainFloor counts for the permanent cards of ours in our hand and in our library, each as if it were on
    // our battlefield now: those of its triggers that work on the battlefield and are not suppressed, each a countable
    // mandatory draw of ours, a phase trigger's or a bounded Drawn trigger's (see plainFloor); a draw that cannot be
    // counted is not
    private static long drawsToCome(final Player ai, final PhaseHandler ph) {
        long due = 0;
        for (final ZoneType zone : List.of(ZoneType.Hand, ZoneType.Library)) {
            for (final Card c : ai.getCardsIn(zone)) {
                if (!c.isPermanent()) {
                    continue;
                }
                for (final Trigger t : c.getTriggers()) {
                    if (t.isSuppressed() || !worksOnTheBattlefield(t)) {
                        continue;
                    }
                    final Map<String, String> draw = ourDraw(t);
                    final long n = draw == null ? -1 : draws(ai, t, draw, ph);
                    if (n > 0) {
                        due += n;
                    }
                }
            }
        }
        return due;
    }

    // whether trigger t works from the battlefield: its TriggerZones name the battlefield, or no zone
    private static boolean worksOnTheBattlefield(final Trigger t) {
        return t.getActiveZone() == null || t.getActiveZone().isEmpty()
                || t.getActiveZone().contains(ZoneType.Battlefield);
    }

    // the params of trigger t's Execute when it is a mandatory Draw of ours: no OptionalDecider on t or on the Draw, no
    // UnlessCost, the Draw's Defined us (You, or none) or each player (Player); read from the built ability when
    // there is one, else from the SVar's text; null otherwise
    private static Map<String, String> ourDraw(final Trigger t) {
        if (t.hasParam("OptionalDecider") || !t.hasParam("Execute")) {
            return null;
        }
        final SpellAbility built = t.getOverridingAbility();
        final Map<String, String> params;
        if (built != null) {
            if (built.getApi() != ApiType.Draw) {
                return null;
            }
            params = built.getMapParams();
        } else {
            final String text = t.getSVar(t.getParam("Execute"));
            if (text.isEmpty()) {
                return null;
            }
            params = FileSection.parseToMap(text, FileSection.DOLLAR_SIGN_KV_SEPARATOR);
            if (!ApiType.Draw.name().equals(params.getOrDefault("DB", params.get("AB")))) {
                return null;
            }
        }
        final String defined = params.getOrDefault("Defined", "You");
        if (params.containsKey("OptionalDecider") || params.containsKey("UnlessCost")
                || !("You".equals(defined) || "Player".equals(defined))) {
            return null;
        }
        return params;
    }

    // a plain whole number of one to three digits, else -1
    private static int plainNumber(final String n) {
        if (n == null || n.isEmpty() || n.length() > 3) {
            return -1;
        }
        int v = 0;
        for (int i = 0; i < n.length(); i++) {
            final char ch = n.charAt(i);
            if (ch < '0' || ch > '9') {
                return -1;
            }
            v = v * 10 + (ch - '0');
        }
        return v;
    }

    // how often phase trigger t fires before the LIBRARY_FLOOR-th of our draw steps from now (see plainFloor): for each
    // of its phases, in the rest of this turn of ours (the phases after the current one), in each other player's turn
    // still in the game, and at our next untap and upkeep, each where its ValidPlayer (any player, when it names none)
    // takes that turn's player; and once more for each of its phases in every turn of each of the LIBRARY_FLOOR - 1
    // rounds of turns between our next draw step and that one, each where its ValidPlayer takes that turn's player
    private static long firings(final Player ai, final Trigger t, final PhaseHandler ph) {
        long n = 0;
        long round = 0;
        final boolean mine = t.matchesValidParam("ValidPlayer", ai);
        for (final PhaseType p : PhaseType.parseRange(t.getParam("Phase"))) {
            if (mine && ph.isPlayerTurn(ai) && p.isAfter(ph.getPhase())) {
                n++;
            }
            if (mine && (p == PhaseType.UNTAP || p == PhaseType.UPKEEP)) {
                n++;
            }
            if (mine) {
                round++;
            }
            for (final Player o : ai.getGame().getPlayers()) {
                if (o != ai && !o.hasLost() && t.matchesValidParam("ValidPlayer", o)) {
                    n++;
                    round++;
                }
            }
        }
        return n + (LIBRARY_FLOOR - 1) * round;
    }

    // the cards trigger t's mandatory Draw of ours, whose params are draw, draws us until plainFloor's horizon ends:
    // its plain number of cards once for each of the trigger's firings, a phase trigger's as firings counts them and a
    // Drawn trigger's whose Number is a plain number at its bound; -1 when they cannot be counted: a number of cards
    // not written plainly, a trigger of any other mode, a phase trigger that names no phase, a Drawn trigger whose
    // Number is missing or not written plainly (see plainFloor)
    private static long draws(final Player ai, final Trigger t, final Map<String, String> draw, final PhaseHandler ph) {
        final int n = plainNumber(draw.getOrDefault("NumCards", "1"));
        if (n < 0) {
            return -1;
        }
        if (t.getMode() == TriggerType.Phase) {
            return t.hasParam("Phase") ? n * firings(ai, t, ph) : -1;
        }
        final int nth = t.getMode() == TriggerType.Drawn ? plainNumber(t.getParam("Number")) : -1;
        return nth < 0 ? -1 : n * bound(ai, t, nth, ph);
    }

    // how often Drawn trigger t can fire until plainFloor's horizon ends: the engine fires it only when a player's
    // count of the cards drawn this turn reaches nth (TriggerDrawn's Number; Player's count is reset at each cleanup),
    // so at most once for each player it can match (see valid and activeOnly) in each turn. Each turn is counted once,
    // whole, by its player, whatever the turn order: this turn, in which a player who has drawn nth cards or more
    // cannot fire it again; one turn of each other living player but the one whose turn this is; our next, through its
    // draw step; and LIBRARY_FLOOR - 1 more of each living player's, the rounds of turns to the LIBRARY_FLOOR-th of our
    // draw steps from now (firings' rounds). No extra turn is counted, as firings counts none
    private static long bound(final Player ai, final Trigger t, final int nth, final PhaseHandler ph) {
        final Player now = ph.getPlayerTurn();
        final boolean active = activeOnly(t);
        final List<Player> valid = valid(ai, t);
        long n = 0;
        for (final Player p : valid) {
            if ((!active || p == now) && p.getNumDrawnThisTurn() < nth) {
                n++;
            }
        }
        for (final Player q : ai.getGame().getPlayers()) {
            if (!q.hasLost()) {
                final long turns = ((q == ai || q != now) ? 1 : 0) + LIBRARY_FLOOR - 1;
                n += turns * (active ? (valid.contains(q) ? 1 : 0) : valid.size());
            }
        }
        return n;
    }

    // the living players Drawn trigger t's ValidPlayer can match, read from its words, not by the engine's own
    // matching, which reads the turn as it is now (Player.Active) where the trigger fires in a later one: every living
    // player when it names none, or for Player, Any or any other word; us for You; each living opponent of ours for
    // Opponent; for a list, those any part of it can match. What follows a word only narrows what it matches and is
    // not read here, so this is an upper bound (Active is read, by activeOnly). An inverted ValidPlayer is read as none
    private static List<Player> valid(final Player ai, final Trigger t) {
        final String valid = t.hasParam("InvertValidPlayer") ? null : t.getParam("ValidPlayer");
        final List<Player> out = new ArrayList<>(4);
        for (final Player p : ai.getGame().getPlayers()) {
            if (!p.hasLost() && (valid == null || matchesAWord(valid, ai, p))) {
                out.add(p);
            }
        }
        return out;
    }

    // whether a part of that ValidPlayer can match p by its word alone (see valid)
    private static boolean matchesAWord(final String valid, final Player ai, final Player p) {
        for (final String part : valid.split(",")) {
            final String word = part.trim().split("\\.", 2)[0];
            if ("You".equals(word) ? p == ai : (!"Opponent".equals(word) || p.isOpponentOf(ai))) {
                return true;
            }
        }
        return false;
    }

    // whether Drawn trigger t can fire only in the turn of the player who draws: every part of its ValidPlayer has the
    // property Active, the player whose turn it is (The Council of Four's Player.Active); false with no ValidPlayer or
    // an inverted one
    private static boolean activeOnly(final Trigger t) {
        if (!t.hasParam("ValidPlayer") || t.hasParam("InvertValidPlayer")) {
            return false;
        }
        for (final String part : t.getParam("ValidPlayer").split(",")) {
            final String[] word = part.trim().split("\\.", 2);
            if (word.length < 2 || !List.of(word[1].split("\\+")).contains("Active")) {
                return false;
            }
        }
        return true;
    }

    /** Thassa's Oracle in our hand wins once our library holds this many cards or fewer: our devotion to blue on our
     *  battlefield, AbilityUtils' own count (Count$Devotion.Blue, read through our first Thrasios's {4}, us its
     *  activator: the count needs an ability), plus Oracle's own {U}{U}, its devotion once it has entered. With no
     *  Thrasios of ours there is no dig, and this is Oracle's two alone. The hold reads it (LoopDriver.holdsOracle);
     *  the Oracle line's window reads none, Oracle's pips being in our devotion once it is on our battlefield, and
     *  digs to empty ({@link #windowDig}). */
    static int oracleThreshold(final Player ai) {
        final Card thrasios = firstNamed(ai, ZoneType.Battlefield, THRASIOS);
        final SpellAbility dig = thrasios == null ? null : digOf(thrasios);
        if (dig == null) {
            return ORACLE_PIPS;
        }
        dig.setActivatingPlayer(ai);
        return AbilityUtils.calculateAmount(thrasios, "Count$Devotion.Blue", dig) + ORACLE_PIPS;
    }

    /** Whether the Oracle line can run on loop line {@code l} (section 6.4 step 3, amended 2026-11-04 (the Oracle
     *  window)): our Oracle in our hand, its {U}{U} payable from the pool, the kinds loop line {@code l} makes, our
     *  untapped lands and Chromatic Orrery ({@link LoopPool#payable}); Oracle able to win now, as piece 1 reads its own
     *  Oracle lines: its cast not barred (Iona, Shield of Emeria naming blue, Void Winnower) and its entering trigger
     *  not disabled (Torpor Orb, Hushbringer); and a Thrasios of ours ready to dig in its window: its {4} one the
     *  driver may make now. */
    static boolean digsForOracle(final Player ai, final ComboLines.Line l) {
        return oracleDig(ai, l) && readyThrasios(ai) != null;
    }

    /** The cast of our first Thassa's Oracle in our hand, us its activator; null with none. */
    static SpellAbility oracleCast(final Player ai) {
        final SpellAbility cast = castOf(firstNamed(ai, ZoneType.Hand, ComboLines.Winner.ORACLE.card));
        if (cast != null) {
            cast.setActivatingPlayer(ai);
        }
        return cast;
    }

    /** What the Oracle line's window spends (section 6.4 step 3, amended 2026-11-04 (the Oracle window)): a dig of our
     *  whole library, Thrasios's price as the engine prices it now (its {4}, a tax included: Suppression Field's {2})
     *  once for each card in our library; -1 with no Thrasios of ours ready. */
    static int oracleWindowNeed(final Player ai) {
        final SpellAbility dig = readyThrasios(ai);
        if (dig == null) {
            return -1;
        }
        final long need = (long) ComboExecute.priced(ai, dig).getCMC() * ai.getCardsIn(ZoneType.Library).size();
        return (int) Math.min(need, Integer.MAX_VALUE);
    }

    /** The pool the Oracle line builds before it casts Thassa's Oracle: the window's need ({@link #oracleWindowNeed})
     *  and Oracle's own price as the engine prices it now ({U}{U}, a tax included), the most of that cast the pool can
     *  pay (Chromatic Orrery's colourless for its blue, a tax's generic), so that the need is left whatever pays it; -1
     *  with no Thrasios of ours ready, and with no Oracle in our hand. */
    static int oraclePool(final Player ai) {
        final int need = oracleWindowNeed(ai);
        final SpellAbility cast = oracleCast(ai);
        if (need < 0 || cast == null) {
            return -1;
        }
        return (int) Math.min((long) need + ComboExecute.priced(ai, cast).getCMC(), Integer.MAX_VALUE);
    }

    /** The Oracle line's next dig in its window, above Thassa's Oracle's entering trigger (section 6.6, amended
     *  2026-11-04 (the Oracle window)): the {4} of our first ready Thrasios, us its activator, while our library holds
     *  a card, whatever else it holds and whatever mana the opponents have open (R22, amended: a stop above empty loses
     *  to any removal of a blue permanent of ours, where a stop at empty loses only to a counter of the trigger or to a
     *  draw an opponent's play makes us take before it resolves, Faerie Mastermind's on their second card; beside a
     *  draw of ours that cannot be counted, which any of their spells could set off, the line never casts Oracle:
     *  {@link #drawsUncounted}); null once the library is empty, and with no Thrasios ready. No threshold is read:
     *  Oracle's pips are in our devotion once it is on our battlefield, and an empty library needs none. Draws no
     *  random number. */
    static SpellAbility windowDig(final Player ai) {
        return ai.getCardsIn(ZoneType.Library).isEmpty() ? null : readyThrasios(ai);
    }

    // our first Thrasios on our battlefield whose {4} the driver may make now (LoopDriver.allowed) and that costs us no
    // life (R30, LoopDriver.lifeCost: Harsh Mentor's 2 for it; a dig buys no kill this turn), us its activator; null
    // with none
    private static SpellAbility readyThrasios(final Player ai) {
        for (final Card c : ai.getCardsIn(ZoneType.Battlefield)) {
            if (THRASIOS.equals(ComboTracker.nameOf(c))) {
                final SpellAbility dig = digOf(c);
                if (dig != null && LoopDriver.allowed(ai, dig) && LoopDriver.lifeCost(ai, dig) == 0) {
                    return dig;
                }
            }
        }
        return null;
    }

    // Thrasios's {4}: its activated Scry ability (scry 1, then reveal the top card: a land onto the battlefield tapped,
    // else a draw); null with none (an ability-removing effect)
    private static SpellAbility digOf(final Card thrasios) {
        for (final SpellAbility sa : thrasios.getNonManaAbilities()) {
            if (sa.isActivatedAbility() && sa.getApi() == ApiType.Scry) {
                return sa;
            }
        }
        return null;
    }

    // the Oracle line's Thassa's Oracle in our hand: its printed {U}{U} payable (LoopPool.payable), and its win
    // possible now, as piece 1 reads its own Oracle lines (ComboSignals.deniedNow, through the tracker's view): its
    // cast not barred by a CantBeCast static (castBarredNow, on a copy of the cast from our hand) and its entering
    // trigger not disabled (oracleTriggerDisabledNow). An Oracle that cannot win is no library-out winner, and a window
    // dug for it would only empty our library. With no tracker (no AI of ours decides) there is no line
    private static boolean oracleDig(final Player ai, final ComboLines.Line l) {
        final Card oracle = firstNamed(ai, ZoneType.Hand, ComboLines.Winner.ORACLE.card);
        if (oracle == null || !LoopPool.payable(ai, oracle, l)) {
            return false;
        }
        final ComboTracker t = ComboTracker.of(ai);
        if (t == null) {
            return false;
        }
        final ComboTracker.View v = t.view();
        return !v.castBarred(ComboLines.Winner.ORACLE.card) && !v.oracleTriggerDisabled();
    }

    // a Laboratory Maniac or Jace, Wielder of Mysteries of ours on our battlefield holding its win, a Draw replacement
    // that wins the game instead (ComboSignals.winsOnDraw: none under Humility, Lignify or face down)
    private static boolean winsOnEmpty(final Player ai) {
        for (final Card c : ai.getCardsIn(ZoneType.Battlefield)) {
            final String name = ComboTracker.nameOf(c);
            if ((ComboLines.Winner.LAB.card.equals(name) || ComboLines.Winner.JACE.card.equals(name))
                    && ComboSignals.winsOnDraw(c)) {
                return true;
            }
        }
        return false;
    }

    // R22: a dig below the floor to empty with Laboratory Maniac or Jace, which needs us able to win and no opponent
    // with an untapped permanent that has a mana ability, or mana in their pool, so that only a free counterspell can
    // stop the finish (for Thassa's Oracle R22 is amended, 2026-11-04: its window digs to empty whatever is open)
    private static boolean belowTheFloor(final Player ai) {
        if (ai.cantWin()) {
            return false;
        }
        for (final Player o : ai.getOpponents()) {
            if (o.getManaPool().totalMana() > 0) {
                return false;
            }
            for (final Card c : o.getCardsIn(ZoneType.Battlefield)) {
                if (c.isUntapped() && !c.getManaAbilities().isEmpty()) {
                    return false;
                }
            }
        }
        return true;
    }

    // our first card of that name in that zone of ours (by ComboTracker.nameOf, in the zone's order), or null
    private static Card firstNamed(final Player ai, final ZoneType zone, final String name) {
        for (final Card c : ai.getCardsIn(zone)) {
            if (name.equals(ComboTracker.nameOf(c))) {
                return c;
            }
        }
        return null;
    }

    // a part of a spell that puts a creature onto the battlefield: a ChangeZone to the battlefield whose ChangeType
    // names a creature
    private static boolean putsACreature(final SpellAbility s) {
        return s.getApi() == ApiType.ChangeZone && s.hasParam("Destination")
                && ZoneType.listValueOf(s.getParam("Destination")).contains(ZoneType.Battlefield)
                && namesACreature(s.getParam("ChangeType"));
    }

    // a ChangeType one of whose terms is a creature card: its type, before any restriction, Creature
    // (Creature.Green+cmcLEX); Land.Basic is none
    private static boolean namesACreature(final String changeType) {
        if (changeType == null) {
            return false;
        }
        for (final String term : changeType.split(",")) {
            if ("Creature".equals(term.trim().split("\\.", 2)[0])) {
                return true;
            }
        }
        return false;
    }

    // a part of a spell that pumps: a Pump or PumpAll that is no curse
    private static boolean pumps(final SpellAbility s) {
        return (s.getApi() == ApiType.Pump || s.getApi() == ApiType.PumpAll) && !s.isCurse();
    }
}
