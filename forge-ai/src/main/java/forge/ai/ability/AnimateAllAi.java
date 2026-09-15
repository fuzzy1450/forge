package forge.ai.ability;

import forge.ai.AiAbilityDecision;
import forge.ai.AiPlayDecision;
import forge.ai.ComputerUtilAbility;
import forge.ai.ComputerUtilCard;
import forge.ai.SpecialCardAi;
import forge.ai.SpellAbilityAi;
import forge.game.card.Card;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;

public class AnimateAllAi extends SpellAbilityAi {

    @Override
    protected AiAbilityDecision canPlay(Player aiPlayer, SpellAbility sa) {
        if ("Mass Diminish".equals(ComputerUtilAbility.getAbilitySourceName(sa))) {
            // Needs a player target and a value floor; every other AnimateAll
            // card takes exactly the path it took before this branch.
            return SpecialCardAi.MassDiminish.consider(aiPlayer, sa);
        }
        if (SpecialCardAi.CombatShrinkAll.NAMES.contains(ComputerUtilAbility.getAbilitySourceName(sa))) {
            // Combat-locked "their creatures have base power and toughness N/M" tricks, with their
            // own target and value floors; every other AnimateAll card takes exactly the path it
            // took before this branch.
            return SpecialCardAi.CombatShrinkAll.consider(aiPlayer, sa);
        }
        String logic = sa.getParamOrDefault("AILogic", "");

        if ("Predators' Hour".equals(ComputerUtilAbility.getAbilitySourceName(sa))) {
            // Menace plus a steal-on-combat-damage trigger for our own team,
            // judged against the AI's own attack plan. Every other AnimateAll
            // card takes exactly the path it took before this branch.
            return SpecialCardAi.PredatorsHour.consider(aiPlayer, sa);
        }

        if ("CreatureAdvantage".equals(logic) && !aiPlayer.getCreaturesInPlay().isEmpty()) {
            // TODO: improve this or implement a better logic for abilities like Oko, the Trickster ultimate
            for (Card c : aiPlayer.getCreaturesInPlay()) {
                if (ComputerUtilCard.doesCreatureAttackAI(aiPlayer, c)) {
                    return new AiAbilityDecision(100, AiPlayDecision.WillPlay);
                }
            }
        }

        if ("Always".equals(logic)) {
            return new AiAbilityDecision(100, AiPlayDecision.WillPlay);
        }
        return new AiAbilityDecision(0, AiPlayDecision.CantPlayAi);
    }

    @Override
    protected AiAbilityDecision doTriggerNoCost(Player aiPlayer, SpellAbility sa, boolean mandatory) {
        if (mandatory) {
            return new AiAbilityDecision(100, AiPlayDecision.WillPlay);
        }
        return canPlay(aiPlayer, sa);
    }

}
