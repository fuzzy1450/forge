package forge.game.staticability;

import forge.game.Game;
import forge.game.card.Card;
import forge.game.zone.ZoneType;

public class StaticAbilityCombatDamageNegatePower {
    public static boolean combatDamageNegatePower(final Card card)  {
        final Game game = card.getGame();
        if (!game.mayHaveStaticAbilityMode(StaticAbilityMode.CombatDamageNegatePower)) {
            // no static ability of this game has the mode, so the scan would find none
            if (StaticAbilityModeProbe.listener != null) {
                StaticAbilityModeProbe.skipped(game, StaticAbilityMode.CombatDamageNegatePower, () -> !scan(card));
            }
            return false;
        }
        return scan(card);
    }

    private static boolean scan(final Card card) {
        final Game game = card.getGame();
        for (final Card ca : game.getCardsIn(ZoneType.STATIC_ABILITIES_SOURCE_ZONES)) {
            for (final StaticAbility stAb : ca.getStaticAbilities()) {
                if (!stAb.checkConditions(StaticAbilityMode.CombatDamageNegatePower)) {
                    continue;
                }

                if (applyCombatDamageNegatePowerAbility(stAb, card)) {
                    return true;
                }
            }
        }
        return false;
    }

    public static boolean applyCombatDamageNegatePowerAbility(final StaticAbility stAb, final Card card) {
        if (!stAb.matchesValidParam("ValidCard", card)) {
            return false;
        }
        return true;
    }

}
