package forge.ai.ability;

import forge.ai.AiAbilityDecision;
import forge.ai.AiPlayDecision;
import forge.ai.ComputerUtilAbility;
import forge.ai.SpecialCardAi;
import forge.game.ability.ApiType;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;

/**
 * AI for ApiType.Incubate.
 *
 * Incubate had no SpellApiToAi entry, so every lookup fell back to
 * CannotPlayAi. Two sub-abilities are understood so far: Excise the
 * Imperfect's "its controller incubates X" (name-gated, priced in
 * SpecialCardAi.ExciseTheImperfect) and Blight Titan's "mill yourself two,
 * then incubate X". Every other Incubate keeps CannotPlayAi's refusal. That
 * covers top-level SP$/AB$ Incubate (canPlay is inherited) and every other
 * Incubate hung under another effect (chkDrawback falls through to super).
 * Assimilate Essence, Merciless Repurposing, Searing Barb, Sunder the
 * Gateway, Sunfall, Tangled Skyline and Traumatic Revelation therefore behave
 * exactly as before.
 */
public class IncubateAi extends CannotPlayAi {
    @Override
    public AiAbilityDecision chkDrawback(Player aiPlayer, SpellAbility sa) {
        // Excise the Imperfect: the only Incubate script whose recipient is the
        // exiled target's controller, so the incubated X/X is a gift to them.
        if ("Excise the Imperfect".equals(ComputerUtilAbility.getAbilitySourceName(sa))
                && "TargetedController".equals(sa.getParam("Defined"))) {
            return SpecialCardAi.ExciseTheImperfect.considerIncubate(aiPlayer, sa);
        }
        final SpellAbility parent = sa.getParent();
        // Blight Titan (the only Mill -> Incubate script): we mill ourselves
        // two, then WE incubate. The ETB copy of this chain is what
        // AiController.checkETBEffects asks about before casting the Titan.
        if (parent != null && parent.getApi() == ApiType.Mill
                && "You".equals(parent.getParam("Defined"))
                && (!sa.hasParam("Defined") || "You".equals(sa.getParam("Defined")))) {
            // Mirror MillAi.checkApiLogic's own self-mill floor: stay off a
            // small library, because every attack mills two more.
            if (aiPlayer.getCardsIn(ZoneType.Library).size() < 10) {
                return new AiAbilityDecision(0, AiPlayDecision.CantPlayAi);
            }
            // X = 0 is inert: SetStateAi.compareCards refuses to transform
            // the Incubator into a 0/0.
            return new AiAbilityDecision(100, AiPlayDecision.WillPlay);
        }
        return super.chkDrawback(aiPlayer, sa);
    }
}
