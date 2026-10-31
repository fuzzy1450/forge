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
import forge.game.player.GameLossReason;
import forge.game.player.Player;
import forge.game.replacement.ReplacementEffect;
import forge.game.replacement.ReplacementLayer;
import forge.game.replacement.ReplacementType;
import forge.game.spellability.SpellAbility;
import forge.game.staticability.StaticAbility;
import forge.game.staticability.StaticAbilityMode;
import forge.game.zone.ZoneType;

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
 * Ballista is pumped, or cast, until our board is five times lethal (LoopTracker's decisive).
 *
 * <p>Then Thrasios's dig, step 3 of section 6.4, taken when the stock AI has nothing it wants to play: its {4}, one
 * activation a decision ({@link #thrasios}), while our library holds more than {@link #LIBRARY_FLOOR} cards, a card in
 * it would add to our board ({@link #digWorth}, {@link #addsToBoard}) and our board is short of five times lethal. A
 * dig toward a library-out winner goes on whatever the library holds: toward Thassa's Oracle in our hand while it can
 * win now ({@link #digsForOracle}: its cast not barred, its entering trigger not disabled) until our library holds no
 * more than our devotion to blue and Oracle's own {U}{U} ({@link #oracleThreshold}), when Oracle's cast wins
 * ({@link #oracleCast}); with Laboratory Maniac or Jace, Wielder of Mysteries on our battlefield, to empty and one
 * activation more, whose draw wins. Below the floor only while we can win and no opponent has mana to answer the
 * finish (R22).
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
     *  which refuses a draw at 4 cards or fewer; a dig toward a library-out winner may go below it ({@link #thrasios},
     *  R22). */
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
     * Thrasios's next activation (section 6.4 step 3), us its activator; null when Thrasios has nothing to do. It is
     * the {4} of our first Thrasios, Triton Hero on our battlefield whose activation the driver may make now
     * (LoopDriver.allowed: under Cursed Totem, none), while
     * <ul>
     * <li>a dig toward Thassa's Oracle runs ({@link #digsForOracle}): our library holds more than
     * {@link #oracleThreshold} cards, whatever it holds, the library's size being the dig's goal;</li>
     * <li>with none, a Laboratory Maniac or Jace, Wielder of Mysteries of ours on our battlefield holds its win (a Draw
     * replacement that wins instead): a dig to empty, whatever the library holds, and once it is empty one activation
     * more, whose draw wins;</li>
     * <li>with neither, our library holds a card worth the dig ({@link #digWorth}) and our board is short of five times
     * lethal (LoopTracker's decisive).</li>
     * </ul>
     * Each while our library holds more than {@link #LIBRARY_FLOOR} cards; a dig toward a library-out winner goes on
     * below the floor only while we can win and no opponent has an untapped permanent with a mana ability, or mana in
     * their pool, to answer the finish (R22: only a free counterspell can stop it), and otherwise stops at the floor.
     */
    static SpellAbility thrasios(final Player ai, final ComboLines.Line l) {
        final SpellAbility dig = readyThrasios(ai);
        if (dig == null) {
            return null;
        }
        final int library = ai.getCardsIn(ZoneType.Library).size();
        final boolean aboveTheFloor = library > LIBRARY_FLOOR;
        if (oracleDig(ai, l)) {
            return library > oracleThreshold(ai) && (aboveTheFloor || belowTheFloor(ai)) ? dig : null;
        }
        if (winsOnEmpty(ai)) {
            return aboveTheFloor || belowTheFloor(ai) ? dig : null;
        }
        return aboveTheFloor && digWorth(ai, l) && !LoopTracker.Snapshot.of(ai).isDecisive() ? dig : null;
    }

    /** Thassa's Oracle wins once our library holds this many cards or fewer: our devotion to blue on our battlefield,
     *  AbilityUtils' own count (Count$Devotion.Blue, read through our first Thrasios's {4}, us its activator: the count
     *  needs an ability), plus Oracle's own {U}{U}, its devotion once it has entered. With no Thrasios of ours there is
     *  no dig, and this is Oracle's two alone. */
    static int oracleThreshold(final Player ai) {
        final Card thrasios = firstNamed(ai, ZoneType.Battlefield, THRASIOS);
        final SpellAbility dig = thrasios == null ? null : digOf(thrasios);
        if (dig == null) {
            return ORACLE_PIPS;
        }
        dig.setActivatingPlayer(ai);
        return AbilityUtils.calculateAmount(thrasios, "Count$Devotion.Blue", dig) + ORACLE_PIPS;
    }

    /** Whether a dig toward Thassa's Oracle can run (section 6.4 step 3): our Oracle in our hand, its {U}{U} payable
     *  from the pool, the kinds loop line {@code l} makes, our untapped lands and Chromatic Orrery
     *  ({@link LoopPool#payable}); Oracle able to win now, as piece 1 reads its own Oracle lines: its cast not barred
     *  (Iona, Shield of Emeria naming blue, Void Winnower) and its entering trigger not disabled (Torpor Orb,
     *  Hushbringer); and a Thrasios of ours ready: its {4} one the driver may make now. */
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

    // our first Thrasios on our battlefield whose {4} the driver may make now (LoopDriver.allowed), us its activator;
    // null with none
    private static SpellAbility readyThrasios(final Player ai) {
        for (final Card c : ai.getCardsIn(ZoneType.Battlefield)) {
            if (THRASIOS.equals(ComboTracker.nameOf(c))) {
                final SpellAbility dig = digOf(c);
                if (dig != null && LoopDriver.allowed(ai, dig)) {
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

    // a dig toward our Thassa's Oracle in our hand: its printed {U}{U} payable (LoopPool.payable), and its win possible
    // now, as piece 1 reads its own Oracle lines (ComboSignals.deniedNow, through the tracker's view): its cast not
    // barred by a CantBeCast static (castBarredNow, on a copy of the cast from our hand) and its entering trigger not
    // disabled (oracleTriggerDisabledNow). An Oracle that cannot win is no library-out winner, and a dig toward it
    // would only empty our library. With no tracker (no AI of ours decides) there is no dig
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

    // R22: a dig below the floor toward a library-out winner, which needs us able to win and no opponent with an
    // untapped permanent that has a mana ability, or mana in their pool, so that only a free counterspell can stop the
    // finish
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
