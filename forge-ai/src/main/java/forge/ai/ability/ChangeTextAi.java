package forge.ai.ability;

import forge.ai.AiAbilityDecision;
import forge.ai.ComputerUtilAbility;
import forge.ai.SpecialCardAi;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;

/**
 * AI for ApiType.ChangeText ("change the text of target ... by replacing ...").
 *
 * ChangeText had no SpellApiToAi entry, so every lookup fell back to
 * CannotPlayAi. Only one shape is understood so far: New Blood's rider sub
 * under its GainControl spell, judged by SpecialCardAi.NewBlood. Every other
 * ChangeText card keeps CannotPlayAi's refusal (canPlay, doTriggerNoCost and
 * the rest are inherited; chkDrawback falls through to super), so Alter
 * Reality, Artificial Evolution, Balduvian Shaman, Crystal Spray, Glamerdye,
 * Magical Hack, Mind Bend, Sleight of Mind, Spectral Shift, Trait Doctoring
 * and Whim of Volrath behave exactly as before.
 */
public class ChangeTextAi extends CannotPlayAi {
    @Override
    public AiAbilityDecision chkDrawback(Player aiPlayer, SpellAbility sa) {
        if ("New Blood".equals(ComputerUtilAbility.getAbilitySourceName(sa))) {
            return SpecialCardAi.NewBlood.considerTextChange(aiPlayer, sa);
        }
        return super.chkDrawback(aiPlayer, sa);
    }
}
