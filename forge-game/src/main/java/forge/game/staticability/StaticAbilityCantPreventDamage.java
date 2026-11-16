package forge.game.staticability;

import java.util.List;
import java.util.Set;

import forge.game.Game;
import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.zone.ZoneType;

public class StaticAbilityCantPreventDamage {

    public static boolean cantPreventDamage(final Card source, final boolean isCombat) {
        final Game game = source.getGame();
        if (!game.mayHaveStaticAbilityMode(StaticAbilityMode.CantPreventDamage)) {
            // no static ability of this game, the source's included, has the mode, so the scan would find none
            if (StaticAbilityModeProbe.listener != null) {
                StaticAbilityModeProbe.skipped(game, Set.of(StaticAbilityMode.CantPreventDamage), List.of(source),
                        () -> !scan(source, isCombat));
            }
            return false;
        }
        return scan(source, isCombat);
    }

    private static boolean scan(final Card source, final boolean isCombat) {
        CardCollection list = new CardCollection(source.getGame().getCardsIn(ZoneType.STATIC_ABILITIES_SOURCE_ZONES));
        list.add(source);
        for (final Card ca : list) {
            for (final StaticAbility stAb : ca.getStaticAbilities()) {
                if (!stAb.checkConditions(StaticAbilityMode.CantPreventDamage)) {
                    continue;
                }
                if (applyCantPreventDamage(stAb, source, isCombat)) {
                    return true;
                }
            }
        }
        return false;
    }

    public static boolean applyCantPreventDamage(final StaticAbility stAb, final Card source, final boolean isCombat) {
        if (stAb.hasParam("IsCombat")) {
            if (stAb.getParam("IsCombat").equals("True") != isCombat) {
                return false;
            }
        }

        if (!stAb.matchesValidParam("ValidSource", source)) {
            return false;
        }
        return true;
    }

}
