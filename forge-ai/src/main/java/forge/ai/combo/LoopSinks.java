package forge.ai.combo;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import forge.game.Game;
import forge.game.ability.AbilityKey;
import forge.game.ability.ApiType;
import forge.game.card.Card;
import forge.game.card.CardCopyService;
import forge.game.player.GameLossReason;
import forge.game.player.Player;
import forge.game.replacement.ReplacementEffect;
import forge.game.replacement.ReplacementLayer;
import forge.game.replacement.ReplacementType;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;

/**
 * The sinks a mana loop spends on (spec 2026-10-29-combo-aware-ai-loops-design.md section 6.4). For now the loss
 * blockers alone: the creatures whose "can't happen" effect stops an opponent losing or us winning, which Walking
 * Ballista kills first (section 6.4, my call), read by signal 2 for a loop line ({@link ComboSignals#loopDenied}).
 * Nothing here draws a random number, and no card of the game is written: the Ballista whose ping is aimed is an LKI
 * copy.
 */
final class LoopSinks {
    /** Walking Ballista, the sink whose pings kill, as the table and the deck lists name it. */
    static final String BALLISTA = "Walking Ballista";

    private LoopSinks() {
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
     * Abyssal Persecutor the first), and which the ping of a Walking Ballista of {@code ai}'s can target, read as the
     * engine reads a target (hexproof, shroud, protection). In the battlefield's order (the players' order, then each
     * one's battlefield); immutable. Empty while no Walking Ballista of {@code ai}'s is on its battlefield or in its
     * hand: there is no ping to aim.
     */
    static List<Card> lossBlockers(final Player ai) {
        final SpellAbility ping = ping(ai);
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
        final SpellAbility ping = ping(ai);
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

    // the loss blockers the ping can target
    private static List<Card> lossBlockers(final Player ai, final SpellAbility ping) {
        final List<Card> out = new ArrayList<>(1);
        for (final Card c : ai.getGame().getCardsIn(ZoneType.Battlefield)) {
            if (c.isCreature() && stopsOurKill(c, ai) && ping.canTarget(c)) {
                out.add(c);
            }
        }
        return List.copyOf(out);
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

    // the ping of ai's Walking Ballista, on ai's battlefield, else in ai's hand: its activated DealDamage ability that
    // targets, on an LKI copy of that Ballista last known on ai's battlefield, ai its activator; null with none
    private static SpellAbility ping(final Player ai) {
        Card ballista = first(ai, ZoneType.Battlefield);
        if (ballista == null) {
            ballista = first(ai, ZoneType.Hand);
        }
        if (ballista == null) {
            return null;
        }
        final Card copy = CardCopyService.getLKICopy(ballista);
        copy.setLastKnownZone(ai.getZone(ZoneType.Battlefield));
        for (final SpellAbility sa : copy.getNonManaAbilities()) {
            if (sa.isActivatedAbility() && sa.getApi() == ApiType.DealDamage && sa.usesTargeting()) {
                sa.setActivatingPlayer(ai);
                return sa;
            }
        }
        return null;
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
}
