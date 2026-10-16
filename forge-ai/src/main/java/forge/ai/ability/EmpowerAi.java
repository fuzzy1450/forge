package forge.ai.ability;

import forge.ai.AiAbilityDecision;
import forge.ai.SpecialCardAi;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;

/**
 * AI for ApiType.Empower ("empower Jace N": put N loyalty counters on a Jace token you
 * control, first creating one if you control none).
 *
 * Empower had no SpellApiToAi entry, so every lookup fell back to CannotPlayAi (and printed
 * "No AI assigned for API: Empower"). One shape is understood so far: Hexhaven Battalion's
 * rider under its Token spell, judged by SpecialCardAi.HexhavenBattalion. Every other Empower
 * keeps CannotPlayAi's answers: canPlay, doTriggerNoCost and the choosers are inherited, and
 * chkDrawback falls through to super. So Fatehold Charm's draw mode, Jace, Reality Sculptor's
 * +1 and every other Empower script behave exactly as before, and Way of the Mentor's and Plan
 * for All Outcomes' trigger roots still resolve through the inherited doTriggerNoCost.
 * CopySpellAbilityAi:83's and SpecialCardAi's partsSafe "instanceof CannotPlayAi" stay true.
 */
public class EmpowerAi extends CannotPlayAi {
    @Override
    public AiAbilityDecision chkDrawback(Player aiPlayer, SpellAbility sa) {
        if (SpecialCardAi.HexhavenBattalion.handles(sa)) {
            return SpecialCardAi.HexhavenBattalion.considerEmpower(aiPlayer, sa);
        }
        return super.chkDrawback(aiPlayer, sa);
    }
}
