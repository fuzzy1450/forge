package forge.ai.ability;

import forge.ai.*;
import forge.game.ability.AbilityUtils;
import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.card.CardLists;
import forge.game.cost.Cost;
import forge.game.cost.CostPart;
import forge.game.cost.CostPayLife;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;

public class ConniveAi extends SpellAbilityAi {
    /** SpellAbilityAi.willPayCosts' own checkLifeCost floor: the least life a payment may leave. */
    static final int STOCK_LIFE_FLOOR = 4;

    @Override
    protected AiAbilityDecision canPlay(Player ai, SpellAbility sa) {
        if (!ai.canDraw()) {
            return new AiAbilityDecision(0, AiPlayDecision.CantPlayAi);
        }

        // canPlay is overridden whole, so the stock willPayCosts life floor never runs here
        if (!lifePaymentAcceptable(ai, sa)) {
            return new AiAbilityDecision(0, AiPlayDecision.CostNotAcceptable);
        }

        Card host = sa.getHostCard();

        final int num = AbilityUtils.calculateAmount(host, sa.getParamOrDefault("ConniveNum", "1"), sa);
        if (num == 0) {
            return new AiAbilityDecision(0, AiPlayDecision.CantPlayAi);
        }

        CardCollection list = CardLists.getTargetableCards(ai.getCardsIn(ZoneType.Battlefield), sa);

        // Filter AI-specific targets if provided
        list = ComputerUtil.filterAITgts(sa, ai, list, false);

        if ("X".equals(sa.getParam("TargetMax")) && "Count$xPaid".equals(sa.getSVar("X"))) {
            // TODO: consider making the library margin (currently hardcoded to 5) a configurable AI parameter
            int maxTargets = Math.min(list.size(), Math.max(0, ai.getCardsIn(ZoneType.Library).size() - 5));
            maxTargets = Math.min(maxTargets, ComputerUtilMana.getAvailableManaEstimate(ai));
            sa.setXManaCostPaid(maxTargets);
        }

        sa.resetTargets();
        while (sa.canAddMoreTarget()) {
            if ((list.isEmpty() && sa.isTargetNumberValid() && !sa.getTargets().isEmpty())) {
                return new AiAbilityDecision(100, AiPlayDecision.WillPlay);
            }

            if (list.isEmpty()) {
                // Still an empty list, but we have to choose something (mandatory); expand targeting to
                // include AI's own cards to see if there's anything targetable (e.g. Plague Belcher).
                list = CardLists.getTargetableCards(ai.getCardsIn(ZoneType.Battlefield), sa);
            }

            if (list.isEmpty()) {
                // Not mandatory, or the the list was regenerated and is still empty,
                // so return whether or not we found enough targets
                return new AiAbilityDecision(sa.isTargetNumberValid() ? 100 : 0, sa.isTargetNumberValid() ? AiPlayDecision.WillPlay : AiPlayDecision.CantPlayAi);
            }

            Card choice = ComputerUtilCard.getBestCreatureAI(list);

            if (choice != null) {
                sa.getTargets().add(choice);
                list.remove(choice);
            } else {
                // Didn't want to choose anything?
                list.clear();
            }
        }
        if (!sa.usesTargeting() || (!sa.getTargets().isEmpty() && sa.isTargetNumberValid())) {
            return new AiAbilityDecision(100, AiPlayDecision.WillPlay);
        }
        return new AiAbilityDecision(0, AiPlayDecision.TargetingFailed);
    }

    /**
     * A connive paid for in life: "Pay 3 life: M.O.D.O.K. connives. Activate only during your
     * turn." is the only such ability in cardsfolder (the other canPlay connives, Hypnotic
     * Grifter, Spymaster's Vault, Valentina Allegra de Fontaine, Villainous Hideout and Change
     * of Plans, pay mana or tap, and every DB$ Connive is a trigger or sub-ability that never
     * reaches canPlay). Because canPlay above replaces SpellAbilityAi.canPlay whole, neither
     * willPayCosts' checkLifeCost floor nor preventRunAwayActivations ever ran for it: the AI
     * re-activated it at every priority of its turn until it could not pay, and from a life
     * total divisible by 3 the last payment took it to 0 (45 of the 63 f969 games in which
     * oops-all-black cast M.O.D.O.K. ended that way).
     *
     * A payment is refused when the life it leaves is under the floor or inside the opponents'
     * next attack. The floor is the profile's price for buying card selection with life,
     * SurveilAi's SURVEIL_LIFEPERC_AFTER_PAYING_LIFE of the starting life (Default 60%: 24 of
     * Commander's 40), never under the stock 4. The attack is the opponents' creatures that can
     * attack us next turn, unblocked, as SpecialCardAi.PromiseOfPower.drawSafe reads it: what
     * is left after it must stay above AI_IN_DANGER_MAX_THRESHOLD. No can't-lose exemption
     * (checkLifeCost has one): a payment made under Platinum Angel stays made when it leaves.
     *
     * Draws no RNG: no AI roll, damageIfUnblocked withoutAbilities (canPayCost's reservation
     * roll is never reached), canAttackNextTurn a rules check. A cost with no life part returns
     * true at once, so every other ability reaches exactly the code it reached before, and an
     * approved payment goes on into the unchanged stock path with the stream untouched.
     */
    static boolean lifePaymentAcceptable(final Player ai, final SpellAbility sa) {
        final Cost cost = sa.getPayCosts();
        if (cost == null) {
            return true;
        }
        int payment = 0;
        for (final CostPart part : cost.getCostParts()) {
            if (part instanceof CostPayLife) {
                payment += part.getAbilityAmount(sa);
            }
        }
        if (payment <= 0) {
            return true;
        }
        final int lifeAfter = ai.getLife() - payment;
        final int floor = Math.max(STOCK_LIFE_FLOOR, ai.getStartingLife()
                * AiProfileUtil.getIntProperty(ai, AiProps.SURVEIL_LIFEPERC_AFTER_PAYING_LIFE) / 100);
        if (lifeAfter < floor) {
            return false;
        }
        int unblocked = 0;
        for (final Player opp : ai.getOpponents()) {
            for (final Card c : opp.getCreaturesInPlay()) {
                if (ComputerUtilCombat.canAttackNextTurn(c, ai)) {
                    unblocked += ComputerUtilCombat.damageIfUnblocked(c, ai, null, true);
                }
            }
        }
        return lifeAfter - unblocked > AiProfileUtil.getIntProperty(ai, AiProps.AI_IN_DANGER_MAX_THRESHOLD);
    }

    @Override
    protected AiAbilityDecision doTriggerNoCost(Player ai, SpellAbility sa, boolean mandatory) {
        if (!ai.canDraw() && !mandatory) {
            return new AiAbilityDecision(0, AiPlayDecision.CantPlayAi);
        }

        boolean preferred = true;
        CardCollection list = CardLists.getTargetableCards(ai.getCardsIn(ZoneType.Battlefield), sa);

        // Filter AI-specific targets if provided
        list = ComputerUtil.filterAITgts(sa, ai, list, false);

        sa.resetTargets();
        while (sa.canAddMoreTarget()) {
            if (mandatory) {
                if ((list.isEmpty() || !preferred) && sa.isTargetNumberValid()) {
                    return new AiAbilityDecision(100, AiPlayDecision.WillPlay);
                }

                if (list.isEmpty() && preferred) {
                    // If it's required to choose targets and the list is empty, get a new list
                    list = CardLists.getTargetableCards(ai.getOpponents().getCardsIn(ZoneType.Battlefield), sa);
                    preferred = false;
                }

                if (list.isEmpty()) {
                    // Still an empty list, but we have to choose something (mandatory); expand targeting to
                    // include AI's own cards to see if there's anything targetable (e.g. Plague Belcher).
                    list = CardLists.getTargetableCards(ai.getCardsIn(ZoneType.Battlefield), sa);
                }
            }

            if (list.isEmpty()) {
                // Not mandatory, or the the list was regenerated and is still empty,
                // so return whether or not we found enough targets
                return new AiAbilityDecision(sa.isTargetNumberValid() ? 100 : 0, sa.isTargetNumberValid() ? AiPlayDecision.WillPlay : AiPlayDecision.CantPlayAi);
            }

            Card choice = ComputerUtilCard.getBestCreatureAI(list);

            if (choice != null) {
                sa.getTargets().add(choice);
                list.remove(choice);
            } else {
                // Didn't want to choose anything?
                list.clear();
            }
        }
        return new AiAbilityDecision(
                sa.isTargetNumberValid() ? 100 : 0,
                sa.isTargetNumberValid() ? AiPlayDecision.WillPlay : AiPlayDecision.TargetingFailed
        );
    }

}
