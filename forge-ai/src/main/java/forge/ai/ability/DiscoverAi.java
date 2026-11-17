package forge.ai.ability;

import forge.ai.*;
import forge.ai.simulation.DelayedLoss;
import forge.game.ability.AbilityUtils;
import forge.game.card.Card;
import forge.game.cost.Cost;
import forge.game.player.Player;
import forge.game.player.PlayerActionConfirmMode;
import forge.game.spellability.AbilitySub;
import forge.game.spellability.Spell;
import forge.game.spellability.SpellAbility;
import forge.util.AITimeoutTracker;

import java.util.Map;

public class DiscoverAi extends SpellAbilityAi {

    @Override
    protected AiAbilityDecision checkApiLogic(final Player ai, final SpellAbility sa) {
        if (sa instanceof AbilitySub) {
            // If its a subability, just let the AI do it. Casting spells is fun!
            return new AiAbilityDecision(100, AiPlayDecision.WillPlay);
        }

        Card c = sa.getHostCard();
        Cost cost = sa.getPayCosts();
        if (cost != null) {
            // IF self sacrifice, make sure its "work it". For lands, make sure we still have a decent amount of lands. Or not losing a color source we might need
            if (ComputerUtilCost.isSacrificeSelfCost(cost)) {
                if (c.isLand()) {
                    // The discover lands cost 5 plus tapping this land to activate.
                    // Let's make sure we don't have too few lands in play before we sacrifice this land.
                    if (ai.getLandsInPlay().size() <= 6) {
                        return new AiAbilityDecision(0, AiPlayDecision.CantPlayAi);
                    }
                }
            }

            // Other discover costs that we should probably consider:
            // Sub Loyalty
            // Exile creature card from graveyard
            // Tapping creatures/artifacts
         }

        return new AiAbilityDecision(100, AiPlayDecision.WillPlay);
    }

    /**
     * <p>
     * doTriggerAINoCost
     * </p>
     * @param sa
     *            a {@link forge.game.spellability.SpellAbility} object.
     * @param mandatory
     *            a boolean.
     *
     * @return a boolean.
     */
    @Override
    protected AiAbilityDecision doTriggerNoCost(final Player ai, final SpellAbility sa, final boolean mandatory) {
        if (mandatory) {
            return new AiAbilityDecision(100, AiPlayDecision.WillPlay);
        }

        // If cost is free, and we're just resolving a trigger, lets just do it.
        if (sa.getPayCosts() == null || sa.getPayCosts().isFree()) {
            return new AiAbilityDecision(100, AiPlayDecision.WillPlay);
        }

        return checkApiLogic(ai, sa);
    }

    @Override
    public boolean confirmAction(Player ai, SpellAbility sa, PlayerActionConfirmMode mode, String message, Map<String, Object> params) {
        Card c = (Card)params.get("Card");
        for (SpellAbility s : AbilityUtils.getBasicSpellsFromPlayEffect(c, ai)) {
            if (s.isLandAbility()) {
                // return false or we get a ClassCastException later if the AI encounters MDFC with land backside
                return false;
            }
            Spell spell = (Spell) s;
            if (AiPlayDecision.WillPlay == ((PlayerControllerAi)ai.getController()).getAi().canPlayFromEffectAI(spell, false, true)) {
                // Before accepting, see if the spell has a valid number of targets (it should at this point).
                // Proceeding past this point if the spell is not correctly targeted will result
                // in "Failed to add to stack" error and the card disappearing from the game completely.
                if (!spell.isTargetNumberValid()) {
                    // if we won't be able to pay the cost, don't choose the card
                    return false;
                }
                // MTG_DeckMaker's self-kill fix, S5b (DESIGN.md D13, round 1's C5): a delayed-loss card (a Pact, an
                // extra turn that loses, Glorious End), a copy of one, or, while a pay-or-lose debt is pending, a play
                // with a cost beyond its waived mana goes into the hand instead. Discover marks the free cast's cost
                // mandatory, so playSaFromPlayEffect's S5 does not see it; asked after the stock judgment, whose draws
                // stay.
                if (declinesFreeCast(ai, spell)) {
                    return false;
                }
                return true;
            }
        }
        return false;
    }

    /**
     * Whether DelayedLoss declines the discovered card's free cast (MTG_DeckMaker's self-kill fix, DESIGN.md S5b),
     * counting no mana, with the sites' catches (DESIGN.md 1.11): AITimeoutTracker.Expired is rethrown, and a cast that
     * cannot be judged is declined only when it is a Pact or a card DelayedLoss flags. In a game whose decks list none
     * of the eleven cards it answers false after reads only (DESIGN.md 1.10).
     */
    private static boolean declinesFreeCast(final Player ai, final SpellAbility spell) {
        try {
            return DelayedLoss.declinesFreeCast(ai, spell);
        } catch (final AITimeoutTracker.Expired e) {
            throw e;
        } catch (final RuntimeException e) {
            return DelayedLoss.refusalWhenUnread(spell) != null;
        }
    }
}
