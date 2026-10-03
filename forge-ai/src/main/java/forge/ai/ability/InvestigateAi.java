package forge.ai.ability;

import forge.ai.AiAbilityDecision;
import forge.ai.AiPlayDecision;
import forge.ai.SpecialCardAi;
import forge.ai.SpellAbilityAi;
import forge.game.phase.PhaseHandler;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.player.PlayerActionConfirmMode;
import forge.game.spellability.SpellAbility;

import java.util.Map;

public class InvestigateAi extends SpellAbilityAi {
    /* (non-Javadoc)
     * @see forge.card.abilityfactory.SpellAiLogic#canPlayAI(forge.game.player.Player, java.util.Map, forge.card.spellability.SpellAbility)
     */
    @Override
    protected AiAbilityDecision canPlay(Player aiPlayer, SpellAbility sa) {
        PhaseHandler ph = aiPlayer.getGame().getPhaseHandler();
        boolean result = ph.is(PhaseType.END_OF_TURN) && ph.getNextTurn() == aiPlayer;
        if (!result && sa.isSpell() && sa.getHostCard() != null
                && SpecialCardAi.FollowTheBodies.NAME.equals(sa.getHostCard().getName())) {
            // Follow the Bodies is a sorcery: the end-step window above never opens
            // for it, so its owner held it all game. It is judged in its own Main 2
            // instead, and only where the stock line declines: a window that line
            // approves (a flash enabler) keeps exactly the stock answer, and every
            // other Investigate card takes exactly the path it took before this branch.
            return SpecialCardAi.FollowTheBodies.consider(aiPlayer, sa);
        }
        return result ? new AiAbilityDecision(100, AiPlayDecision.WillPlay) : new AiAbilityDecision(0, AiPlayDecision.TimingRestrictions);
    }

    @Override
    public boolean confirmAction(Player player, SpellAbility sa, PlayerActionConfirmMode mode, String message, Map<String, Object> params) {
        return true;
    }
}
