package forge.ai.ability;


import forge.ai.AiAbilityDecision;
import forge.ai.AiPlayDecision;
import forge.ai.ComputerUtilAbility;
import forge.ai.SpellAbilityAi;
import forge.game.Game;
import forge.game.ability.ApiType;
import forge.game.combat.Combat;
import forge.game.phase.PhaseHandler;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;

/** 
 * TODO: Write javadoc for this type.
 *
 */
public class EndTurnAi extends SpellAbilityAi  {

    @Override
    protected AiAbilityDecision doTriggerNoCost(Player aiPlayer, SpellAbility sa, boolean mandatory) {
        if (mandatory) {
            return new AiAbilityDecision(100, AiPlayDecision.WillPlay);
        } else {
            return new AiAbilityDecision(0, AiPlayDecision.CantPlayAi);
        }
    }

    @Override
    public AiAbilityDecision chkDrawback(Player aiPlayer, SpellAbility sa) {
        // Mandate of Peace, the only EndCombatPhase script: its root SP$ Effect is judged by
        // EffectAi's AILogic$ Fog branch (FogAi.canPlay: the opponent's declare-blockers step,
        // an empty stack, lifeInDanger). Ending combat is only ever wanted as the defender, so
        // this sub re-checks that side on its own instead of trusting the parent's logic.
        if ("Mandate of Peace".equals(ComputerUtilAbility.getAbilitySourceName(sa))
                && sa.getApi() == ApiType.EndCombatPhase) {
            final Game game = aiPlayer.getGame();
            final Combat combat = game.getCombat();
            final PhaseHandler ph = game.getPhaseHandler();
            // An extra combat queued after this one (Hexplate Wallbreaker, Scourge of the Throne,
            // Combat Celebrant): endCombatPhaseByEffect advances into it without onPhaseBegin, so no
            // Combat object exists and COMBAT_DECLARE_ATTACKERS throws (PhaseHandler:306). Hold the
            // card for the last combat instead.
            for (PhaseType step : PhaseType.PHASE_GROUPS.get(2)) {
                if (ph.hasExtraPhaseAfter(PhaseType.COMBAT_END, step)) {
                    return new AiAbilityDecision(0, AiPlayDecision.CantPlayAi);
                }
            }
            if (combat != null && !ph.isPlayerTurn(aiPlayer)
                    && !combat.getAttackersOf(aiPlayer).isEmpty()) {
                return new AiAbilityDecision(100, AiPlayDecision.WillPlay);
            }
        }
        return new AiAbilityDecision(0, AiPlayDecision.CantPlayAi);
    }

    /* (non-Javadoc)
     * @see forge.card.abilityfactory.SpellAiLogic#canPlayAI(forge.game.player.Player, java.util.Map, forge.card.spellability.SpellAbility)
     */
    @Override
    protected AiAbilityDecision canPlay(Player aiPlayer, SpellAbility sa) {
        return new AiAbilityDecision(0, AiPlayDecision.CantPlayAi);
    }
}
