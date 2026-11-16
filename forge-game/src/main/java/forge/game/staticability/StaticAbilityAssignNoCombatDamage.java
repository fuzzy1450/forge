package forge.game.staticability;

import java.util.List;
import java.util.Set;

import forge.game.Game;
import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.zone.ZoneType;

public class StaticAbilityAssignNoCombatDamage {

    public static boolean assignNoCombatDamage(final Card card) {
        final Game game = card.getGame();
        if (!game.mayHaveStaticAbilityMode(StaticAbilityMode.AssignNoCombatDamage)) {
            // no static ability of this game, the card's included, has the mode, so the scan would find none
            if (StaticAbilityModeProbe.listener != null) {
                StaticAbilityModeProbe.skipped(game, Set.of(StaticAbilityMode.AssignNoCombatDamage), List.of(card),
                        () -> !scan(card));
            }
            return false;
        }
        return scan(card);
    }

    private static boolean scan(final Card card) {
        CardCollection list = new CardCollection(card.getGame().getCardsIn(ZoneType.STATIC_ABILITIES_SOURCE_ZONES));
        list.add(card);
        for (final Card ca : list) {
            for (final StaticAbility stAb : ca.getStaticAbilities()) {
                if (!stAb.checkConditions(StaticAbilityMode.AssignNoCombatDamage)) {
                    continue;
                }
                if (applyAssignNoCombatDamage(stAb, card)) {
                    return true;
                }
            }
        }
        return false;
    }

    public static boolean applyAssignNoCombatDamage(final StaticAbility stAb, final Card card) {
        if (!stAb.matchesValidParam("ValidCard", card)) {
            return false;
        }
        return true;
    }

}
