package forge.game.staticability;

import java.util.List;
import java.util.Set;

import forge.game.Game;
import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.card.CardCollectionView;
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
        final CardCollectionView cards = card.getGame().getCardsIn(ZoneType.STATIC_ABILITIES_SOURCE_ZONES);
        if (StaticAbilityModeProbe.listener != null) {
            final CardCollection list = new CardCollection(cards);
            list.add(card);
            return StaticAbilityModeProbe.listener.visits(list, () -> visits(card, cards), found -> !found);
        }
        return visits(card, cards);
    }

    // the cards, then the card (an LKI copy, say) unless it equals one of them: the order of a CardCollection of the
    // cards to which the card was added, without building one
    private static boolean visits(final Card card, final CardCollectionView cards) {
        boolean cardVisited = false;
        for (final Card ca : cards) {
            if (ca.equals(card)) {
                cardVisited = true;
            }
            if (any(ca, card)) {
                return true;
            }
        }
        return !cardVisited && any(card, card);
    }

    private static boolean any(final Card ca, final Card card) {
        StaticAbilityModeProbe.visited(ca);
        for (final StaticAbility stAb : ca.getStaticAbilities()) {
            if (!stAb.checkConditions(StaticAbilityMode.AssignNoCombatDamage)) {
                continue;
            }
            if (applyAssignNoCombatDamage(stAb, card)) {
                return true;
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
