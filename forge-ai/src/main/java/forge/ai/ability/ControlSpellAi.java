package forge.ai.ability;

import forge.ai.AiAbilityDecision;
import forge.ai.ComputerUtilAbility;
import forge.ai.SpecialCardAi;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;

/**
 * AI for ApiType.ControlSpell ("gain control of target spell").
 *
 * Only Commandeer is understood so far; every other ControlSpell card keeps
 * CannotPlayAi's unconditional refusal, so behavior is unchanged for
 * Aethersnatch, Chef's Kiss, Invert Polarity, Perplexing Chimera and
 * Sudden Substitution (whose trigger/drawback paths never consulted
 * CannotPlayAi.canPlay in the first place and are inherited unchanged).
 */
public class ControlSpellAi extends CannotPlayAi {
    @Override
    protected AiAbilityDecision canPlay(Player aiPlayer, SpellAbility sa) {
        if ("Commandeer".equals(ComputerUtilAbility.getAbilitySourceName(sa))) {
            return SpecialCardAi.Commandeer.consider(aiPlayer, sa);
        }
        return super.canPlay(aiPlayer, sa);
    }
}
