package forge.ai.ability;

import forge.ai.AiAbilityDecision;
import forge.ai.AiPlayDecision;
import forge.ai.ComputerUtilAbility;
import forge.ai.SpellAbilityAi;
import forge.game.ability.ApiType;
import forge.game.player.Player;
import forge.game.player.PlayerActionConfirmMode;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;

import java.util.Map;

public class AlwaysPlayAi extends SpellAbilityAi {
    /* (non-Javadoc)
     * @see forge.card.abilityfactory.SpellAiLogic#canPlayAI(forge.game.player.Player, java.util.Map, forge.card.spellability.SpellAbility)
     */
    @Override
    protected AiAbilityDecision canPlay(Player aiPlayer, SpellAbility sa) {
        return new AiAbilityDecision(100, AiPlayDecision.WillPlay);
    }

    @Override
    public AiAbilityDecision chkDrawback(Player ai, SpellAbility sa) {
        if ("Great Intelligence's Plan".equals(ComputerUtilAbility.getAbilitySourceName(sa))
                && sa.getApi() == ApiType.VillainousChoice && sa.usesTargeting()) {
            // The inherited chkDrawback vetoes every targeted sub-ability whose
            // handler does not pick a target, so the spell was never cast. The
            // root DrawAi has already judged the draw; this sub only needs its
            // opponent. Either outcome of a villainous choice only costs that
            // opponent (the caster's free cast is Optional), and an AI opponent
            // always takes the first Choice (Discard), so target the fullest
            // hand; ties keep getOpponents() order. No RNG is consumed.
            sa.resetTargets();
            Player best = null;
            for (Player opp : ai.getOpponents()) {
                if (!sa.canTarget(opp)) {
                    continue;
                }
                if (best == null || opp.getCardsIn(ZoneType.Hand).size() > best.getCardsIn(ZoneType.Hand).size()) {
                    best = opp;
                }
            }
            if (best == null) {
                return new AiAbilityDecision(0, AiPlayDecision.TargetingFailed);
            }
            sa.getTargets().add(best);
            return new AiAbilityDecision(100, AiPlayDecision.WillPlay);
        }
        return super.chkDrawback(ai, sa);
    }

    @Override
    public boolean confirmAction(Player player, SpellAbility sa, PlayerActionConfirmMode mode, String message, Map<String, Object> params) {
        return true;
    }
}
