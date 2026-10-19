package forge.ai.ability;

import forge.ai.*;
import forge.game.ability.AbilityUtils;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;

public class ChooseNumberAi extends SpellAbilityAi {

    @Override
    protected AiAbilityDecision checkApiLogic(Player aiPlayer, SpellAbility sa) {
        if ("Squee's Revenge".equals(ComputerUtilAbility.getAbilitySourceName(sa))) {
            // Picks its own flip count with a value floor; the generic path below
            // refuses (no AILogic), and any AILogic would cast unconditionally and
            // let chooseNumber answer 99. Every other ChooseNumber card takes
            // exactly the path it took before this branch.
            return SpecialCardAi.SqueesRevenge.consider(aiPlayer, sa);
        }
        if (SpecialCardAi.SanctumPrelate.ownHandProbe(aiPlayer, sa)) {
            // Dead-card batch 4, row 47: checkETBEffects' probe of OUR OWN hand cast of Sanctum Prelate gets
            // the lock floor; a theft, a Play-effect cast, a copy or a held evaluation of the real card keeps
            // MissingLogic below (B3-2)
            return SpecialCardAi.SanctumPrelate.worthCasting(aiPlayer)
                    ? new AiAbilityDecision(100, AiPlayDecision.WillPlay)
                    : new AiAbilityDecision(0, AiPlayDecision.CantPlayAi);
        }
        String aiLogic = sa.getParamOrDefault("AILogic", "");

        if (aiLogic.isEmpty()) {
            return new AiAbilityDecision(0, AiPlayDecision.MissingLogic);
        } else if (aiLogic.equals("SweepCreatures")) {
            int maxChoiceLimit = AbilityUtils.calculateAmount(sa.getHostCard(), sa.getParam("Max"), sa);
            int ownCreatureCount = aiPlayer.getCreaturesInPlay().size();
            int oppMaxCreatureCount = 0;
            Player refOpp = null;
            for (Player opp : aiPlayer.getOpponents()) {
                int oppCreatureCount = Math.max(oppMaxCreatureCount, opp.getCreaturesInPlay().size());
                if (oppCreatureCount > oppMaxCreatureCount) {
                    oppMaxCreatureCount = oppCreatureCount;
                    refOpp = opp;
                }
            }

            if (refOpp == null) {
                return new AiAbilityDecision(0, AiPlayDecision.CantPlayAi);
            }

            int evalAI = ComputerUtilCard.evaluateCreatureList(aiPlayer.getCreaturesInPlay());
            int evalOpp = ComputerUtilCard.evaluateCreatureList(refOpp.getCreaturesInPlay());

            if (aiPlayer.getLifeLostLastTurn() + aiPlayer.getLifeLostThisTurn() == 0 && evalAI > evalOpp) {
                // we're not pressured and our stuff seems better, don't do it yet
                return new AiAbilityDecision(0, AiPlayDecision.AnotherTime);
            }

            if (ownCreatureCount > oppMaxCreatureCount + 2 || ownCreatureCount < Math.min(oppMaxCreatureCount, maxChoiceLimit)) {
                // we have more creatures than the opponent, or we have less than the opponent but more than the max choice limit
                return new AiAbilityDecision(100, AiPlayDecision.WillPlay);
            } else {
                // we have less creatures than the opponent and less than the max choice limit
                return new AiAbilityDecision(0, AiPlayDecision.CantPlayAi);
            }
        }

        if (sa.usesTargeting()) {
            sa.resetTargets();
            Player opp = AiAttackController.choosePreferredDefenderPlayer(aiPlayer);
            if (sa.canTarget(opp)) {
                sa.getTargets().add(opp);
            } else {
                return new AiAbilityDecision(0, AiPlayDecision.CantPlayAi);
            }
        }
        return new AiAbilityDecision(100, AiPlayDecision.WillPlay);
    }

    @Override
    protected AiAbilityDecision doTriggerNoCost(Player ai, SpellAbility sa, boolean mandatory) {
        if (mandatory) {
            return new AiAbilityDecision(100, AiPlayDecision.WillPlay);
        }
        return canPlay(ai, sa);
    }
}
