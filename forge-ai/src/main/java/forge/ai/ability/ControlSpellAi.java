package forge.ai.ability;

import forge.ai.AiAbilityDecision;
import forge.ai.AiPlayDecision;
import forge.ai.ComputerUtilAbility;
import forge.ai.SpecialCardAi;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;

/**
 * AI for ApiType.ControlSpell ("gain control of target spell").
 *
 * Only Commandeer and Aethersnatch (Commandeer's window plus vetoes for a
 * stolen permanent spell) are understood so far as a root, and Sudden
 * Substitution's ControlSpell sub as a drawback (the exchange is judged
 * whole in SpecialCardAi.SuddenSubstitution, routed from PumpAi); every
 * other ControlSpell card keeps CannotPlayAi's unconditional refusal, so
 * behavior is unchanged for Chef's Kiss, Invert Polarity and Perplexing
 * Chimera (whose trigger/drawback paths are inherited unchanged).
 */
public class ControlSpellAi extends CannotPlayAi {
    @Override
    protected AiAbilityDecision canPlay(Player aiPlayer, SpellAbility sa) {
        if ("Commandeer".equals(ComputerUtilAbility.getAbilitySourceName(sa))) {
            return SpecialCardAi.Commandeer.consider(aiPlayer, sa);
        }
        if ("Aethersnatch".equals(ComputerUtilAbility.getAbilitySourceName(sa))) {
            return SpecialCardAi.Aethersnatch.consider(aiPlayer, sa);
        }
        return super.canPlay(aiPlayer, sa);
    }

    @Override
    public AiAbilityDecision chkDrawback(Player aiPlayer, SpellAbility sa) {
        if ("Sudden Substitution".equals(ComputerUtilAbility.getAbilitySourceName(sa))) {
            // The swap's ControlSpell half, judged whole in
            // SpecialCardAi.SuddenSubstitution.consider (routed from PumpAi).
            return new AiAbilityDecision(100, AiPlayDecision.WillPlay);
        }
        return super.chkDrawback(aiPlayer, sa);
    }
}
