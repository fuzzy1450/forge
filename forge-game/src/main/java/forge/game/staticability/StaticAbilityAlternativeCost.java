package forge.game.staticability;

import java.util.List;
import java.util.Set;

import com.google.common.collect.Lists;

import forge.card.mana.ManaCostParser;
import forge.game.Game;
import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.card.CardCollectionView;
import forge.game.cost.Cost;
import forge.game.player.Player;
import forge.game.spellability.OptionalCost;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;

public class StaticAbilityAlternativeCost {

    public static List<SpellAbility> alternativeCosts(final SpellAbility sa, final Card source, final Player pl) {
        List<SpellAbility> result = Lists.newArrayList();
        final Game game = source.getGame();
        if (!game.mayHaveStaticAbilityMode(StaticAbilityMode.AlternativeCost)) {
            // no static ability of this game, the source's included, has the mode, so the scan would add nothing
            if (StaticAbilityModeProbe.listener != null) {
                StaticAbilityModeProbe.skipped(game, Set.of(StaticAbilityMode.AlternativeCost), List.of(source),
                        () -> scan(sa, source, pl, Lists.newArrayList()).isEmpty());
            }
            return result;
        }
        return scan(sa, source, pl, result);
    }

    private static List<SpellAbility> scan(final SpellAbility sa, final Card source, final Player pl,
            final List<SpellAbility> result) {
        final CardCollectionView cards = source.getGame().getCardsIn(ZoneType.STATIC_ABILITIES_SOURCE_ZONES);
        if (StaticAbilityModeProbe.listener != null) {
            final CardCollection list = new CardCollection(source);
            list.addAllUnique(cards);
            // every card is visited
            return StaticAbilityModeProbe.listener.visits(list, () -> visits(sa, source, pl, result, cards),
                    added -> true);
        }
        return visits(sa, source, pl, result, cards);
    }

    // source first in case it's LKI (alternate host), then each of the cards not equal to it: the order of a
    // CardCollection of the source to which the cards were added, without building one
    private static List<SpellAbility> visits(final SpellAbility sa, final Card source, final Player pl,
            final List<SpellAbility> result, final CardCollectionView cards) {
        for (int i = -1; i < cards.size(); i++) {
            final Card ca = i < 0 ? source : cards.get(i);
            if (i >= 0 && (ca == null || ca.equals(source))) {
                continue;
            }
            StaticAbilityModeProbe.visited(ca);
            for (final StaticAbility stAb : ca.getStaticAbilities()) {
                if (!stAb.checkMode(StaticAbilityMode.AlternativeCost)) {
                    continue;
                }

                // apply() is cheap, checkConditions() may scan the battlefield
                if (!apply(stAb, sa, source, pl)) {
                    continue;
                }

                if (!stAb.checkConditions()) {
                    continue;
                }

                String costTemplate = stAb.getParam("Cost");
                costTemplate = costTemplate.replace("ConvertedManaCost", Integer.toString(source.getCMC()));

                Cost cost = new Cost(costTemplate, sa.isAbility());
                // set the cost to this directly to bypass non mana cost
                final SpellAbility newSA = sa.isAbility() ? sa.copyWithDefinedCost(cost) : sa.copyWithManaCostReplaced(pl, cost);
                newSA.setActivatingPlayer(pl);
                newSA.setBasicSpell(false);

                if (stAb.hasParam("XAlternative")) {
                    newSA.putParam("XAlternative", stAb.getParam("XAlternative"));
                }

                if (stAb.hasParam("Announce")) {
                    newSA.putParam("Announce", stAb.getParam("Announce"));
                }

                if (stAb.hasParam("ManaRestriction")) {
                    newSA.putParam("ManaRestriction", stAb.getParam("ManaRestriction"));
                }

                if (!stAb.getHostCard().isImmutable()) {
                    Set<ZoneType> zones = stAb.getActiveZone();
                    if (zones != null && zones.size() == 1) {
                        newSA.getRestrictions().setZone(zones.stream().findFirst().get());
                    }
                }

                if (stAb.hasParam("StackDescription")) {
                    newSA.putParam("StackDescription", stAb.getParam("StackDescription"));
                }

                // makes new SpellDescription
                final StringBuilder sb = new StringBuilder();

                // CostDesc only for ManaCost?
                if (sa.isAbility()) {
                    newSA.putParam("CostDesc", stAb.hasParam("CostDesc") ? ManaCostParser.parse(stAb.getParam("CostDesc")) : cost.toSimpleString());
                    sb.append(newSA.getCostDescription());
                }

                // skip reminder text for now, Keywords might be too complicated
                //sb.append("(").append(newKi.getReminderText()).append(")");
                if (sa.isSpell()) {
                    sb.append(sa.getDescription());
                    if (source.equals(stAb.getHostCard())) {
                        newSA.addOptionalCost(OptionalCost.AltCost);
                        sb.append(" ("+ stAb.getParam("Description") +") ");
                    } else {
                        sb.append(" (by paying " + cost.toSimpleString() + " instead of its mana cost)");
                    }
                }
                newSA.setDescription(sb.toString());

                // Support for custom cards alternative costs targeting
                if (stAb.hasParam("Named")) {
                    newSA.setName(stAb.getParam("Named"));
                }

                result.add(newSA);
            }
        }
        return result;
    }

    private static boolean apply(final StaticAbility stAb, final SpellAbility sa, final Card source, final Player pl) {
        if (!stAb.matchesValidParam("ValidSA", sa)) {
            return false;
        }
        if (!stAb.matchesValidParam("ValidCard", source)) {
            return false;
        }
        if (!stAb.matchesValidParam("ValidPlayer", pl)) {
            return false;
        }

        return true;
    }

}
