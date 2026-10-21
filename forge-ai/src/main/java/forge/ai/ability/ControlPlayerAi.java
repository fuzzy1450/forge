package forge.ai.ability;

import forge.ai.AiAbilityDecision;
import forge.ai.AiPlayDecision;
import forge.ai.SpecialCardAi;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;

/**
 * AI for ApiType.ControlPlayer ("target player controls ... during ... next turn").
 *
 * ControlPlayer was mapped straight to CannotPlayAi. Only Cruel Entertainment's two subs are understood
 * (dead-card batch 4, row 21), judged whole in SpecialCardAi.CruelEntertainment (routed from ChoosePlayerAi);
 * every other ControlPlayer script (Mindslaver, Emrakul, the Promised End, Sorin Markov, Worst Fears, Secret
 * of Bloodbending, Construct a Cosmic Cube, The Dominion Bracelet, Urza, Academy Headmaster) keeps CannotPlayAi's
 * answers byte for byte (canPlay, doTriggerNoCost -- Emrakul's mandatory targeting -- and the choosers
 * inherited), and CopySpellAbilityAi:83's "instanceof CannotPlayAi" stays true.
 */
public class ControlPlayerAi extends CannotPlayAi {
    @Override
    public AiAbilityDecision chkDrawback(Player aiPlayer, SpellAbility sa) {
        if (SpecialCardAi.CruelEntertainment.approvesSub(aiPlayer, sa)) {
            return new AiAbilityDecision(100, AiPlayDecision.WillPlay);
        }
        return super.chkDrawback(aiPlayer, sa);
    }
}
