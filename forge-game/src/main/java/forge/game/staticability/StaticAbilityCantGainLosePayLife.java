package forge.game.staticability;

import java.util.List;
import java.util.Set;

import forge.game.Game;
import forge.game.card.Card;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;

public class StaticAbilityCantGainLosePayLife {

    public static boolean anyCantGainLife(final Player player) {
        final Game game = player.getGame();
        if (!game.mayHaveStaticAbilityMode(StaticAbilityMode.CantGainLife)
                && !game.mayHaveStaticAbilityMode(StaticAbilityMode.CantChangeLife)) {
            // no static ability of this game has either mode, so the scan would find none
            if (StaticAbilityModeProbe.listener != null) {
                StaticAbilityModeProbe.skipped(game,
                        Set.of(StaticAbilityMode.CantGainLife, StaticAbilityMode.CantChangeLife), List.of(),
                        () -> !anyCantGainLifeScan(player));
            }
            return false;
        }
        return anyCantGainLifeScan(player);
    }

    private static boolean anyCantGainLifeScan(final Player player) {
        final Game game = player.getGame();
        for (final Card ca : game.getCardsIn(ZoneType.STATIC_ABILITIES_SOURCE_ZONES)) {
            for (final StaticAbility stAb : ca.getStaticAbilities()) {
                if (!(stAb.checkMode(StaticAbilityMode.CantGainLife) || stAb.checkMode(StaticAbilityMode.CantChangeLife))) {
                    continue;
                }

                if (!stAb.checkConditions()) {
                    continue;
                }

                if (applyCommonAbility(stAb, player)) {
                    return true;
                }
            }
        }
        return false;
    }

    public static boolean anyCantLoseLife(final Player player)  {
        final Game game = player.getGame();
        if (!game.mayHaveStaticAbilityMode(StaticAbilityMode.CantLoseLife)
                && !game.mayHaveStaticAbilityMode(StaticAbilityMode.CantChangeLife)) {
            // no static ability of this game has either mode, so the scan would find none
            if (StaticAbilityModeProbe.listener != null) {
                StaticAbilityModeProbe.skipped(game,
                        Set.of(StaticAbilityMode.CantLoseLife, StaticAbilityMode.CantChangeLife), List.of(),
                        () -> !anyCantLoseLifeScan(player));
            }
            return false;
        }
        return anyCantLoseLifeScan(player);
    }

    private static boolean anyCantLoseLifeScan(final Player player) {
        final Game game = player.getGame();
        for (final Card ca : game.getCardsIn(ZoneType.STATIC_ABILITIES_SOURCE_ZONES)) {
            for (final StaticAbility stAb : ca.getStaticAbilities()) {
                if (!(stAb.checkMode(StaticAbilityMode.CantLoseLife) || stAb.checkMode(StaticAbilityMode.CantChangeLife))) {
                    continue;
                }

                if (!stAb.checkConditions()) {
                    continue;
                }

                if (applyCommonAbility(stAb, player)) {
                    return true;
                }
            }
        }

        return false;
    }

    public static boolean anyCantPayLife(final Player player, final boolean effect, final SpellAbility cause)  {
        final Game game = player.getGame();
        if (!game.mayHaveStaticAbilityMode(StaticAbilityMode.CantPayLife)
                && !game.mayHaveStaticAbilityMode(StaticAbilityMode.CantLoseLife)
                && !game.mayHaveStaticAbilityMode(StaticAbilityMode.CantChangeLife)) {
            // no static ability of this game has any of the modes, so the scan would find none
            if (StaticAbilityModeProbe.listener != null) {
                StaticAbilityModeProbe.skipped(game, Set.of(StaticAbilityMode.CantPayLife,
                        StaticAbilityMode.CantLoseLife, StaticAbilityMode.CantChangeLife), List.of(),
                        () -> !anyCantPayLifeScan(player, effect, cause));
            }
            return false;
        }
        return anyCantPayLifeScan(player, effect, cause);
    }

    private static boolean anyCantPayLifeScan(final Player player, final boolean effect, final SpellAbility cause) {
        final Game game = player.getGame();
        for (final Card ca : game.getCardsIn(ZoneType.STATIC_ABILITIES_SOURCE_ZONES)) {
            for (final StaticAbility stAb : ca.getStaticAbilities()) {
                if (!(stAb.checkMode(StaticAbilityMode.CantPayLife) || stAb.checkMode(StaticAbilityMode.CantLoseLife) || stAb.checkMode(StaticAbilityMode.CantChangeLife))) {
                    continue;
                }

                if (!stAb.checkConditions()) {
                    continue;
                }

                if (stAb.hasParam("ForCost")) {
                    if ("True".equalsIgnoreCase(stAb.getParam("ForCost")) == effect) {
                        continue;
                    }
                }

                if (!stAb.matchesValidParam("ValidCause", cause)) {
                    continue;
                }

                if (applyCommonAbility(stAb, player)) {
                    return true;
                }
            }
        }
        return false;
    }

    public static boolean applyCommonAbility(final StaticAbility stAb, final Player player) {
        if (!stAb.matchesValidParam("ValidPlayer", player)) {
            return false;
        }
        return true;
    }
}
