package forge.ai.ability;

import forge.ai.*;
import forge.card.mana.ManaCost;
import forge.game.Game;
import forge.game.card.Card;
import forge.game.mana.ManaCostBeingPaid;
import forge.game.player.Player;
import forge.game.player.PlayerActionConfirmMode;
import forge.game.spellability.AbilitySub;
import forge.game.spellability.SpellAbility;
import forge.game.spellability.TargetChoices;

import java.util.Map;

public class ChangeTargetsAi extends SpellAbilityAi {

    /*
     * (non-Javadoc)
     * 
     * @see forge.ai.SpellAbilityAi#checkApiLogic(forge.game.player.Player,
     * forge.game.spellability.SpellAbility)
     */
    @Override
    protected AiAbilityDecision checkApiLogic(Player ai, SpellAbility sa) {
        if ("Deflecting Swat".equals(ComputerUtilAbility.getAbilitySourceName(sa))) {
            // Redirect an opponent's removal off something we value; every other
            // ChangeTargets card takes exactly the path it took before this branch.
            return SpecialCardAi.DeflectingSwat.consider(ai, sa);
        }
        if ("Wild Ricochet".equals(ComputerUtilAbility.getAbilitySourceName(sa)) && !(sa instanceof AbilitySub)) {
            // Copy an opponent's instant or sorcery for ourselves; the "choose new
            // targets" half is a no-op for the AI (see confirmAction below). Every
            // other ChangeTargets card takes exactly the path it took before.
            return SpecialCardAi.WildRicochet.consider(ai, sa);
        }
        final Game game = sa.getHostCard().getGame();
        final SpellAbility topSa = game.getStack().isEmpty() ? null
                : ComputerUtilAbility.getTopSpellAbilityOnStack(game, sa);

        if ("Self".equals(sa.getParam("DefinedMagnet"))) {
            return doSpellMagnet(sa, topSa, ai);
        }

        // The AI can't otherwise play this ability, but should at least not
        // miss mandatory activations (e.g. triggers).
        if (sa.isMandatory()) {
            return new AiAbilityDecision(50, AiPlayDecision.MandatoryPlay);
        }
        return new AiAbilityDecision(0, AiPlayDecision.CantPlayAi);
    }

    @Override
    public boolean confirmAction(Player player, SpellAbility sa, PlayerActionConfirmMode mode, String message, Map<String, Object> params) {
        if ("Wild Ricochet".equals(ComputerUtilAbility.getAbilitySourceName(sa))) {
            // The AI cannot choose new targets for the original spell
            // (PlayerControllerAi.chooseNewTargetsFor is a stub outside Deflecting
            // Swat): skip the optional retarget, the copy is the whole value.
            return false;
        }
        return super.confirmAction(player, sa, mode, message, params);
    }

    private AiAbilityDecision doSpellMagnet(SpellAbility sa, SpellAbility topSa, Player aiPlayer) {
        // For cards like Spellskite that retarget spells to itself
        if (topSa == null) {
            // nothing on stack, so nothing to target
            return new AiAbilityDecision(0, AiPlayDecision.CantPlayAi);
        }
        final TargetChoices topTargets = topSa.getTargets();
        final Card topHost = topSa.getHostCard();

        if (!sa.getTargets().isEmpty() && sa.isTrigger()) {
            // something was already chosen before (e.g. in response to a trigger - Mizzium Meddler), so just proceed
            return new AiAbilityDecision(100, AiPlayDecision.WillPlay);
        }

        if (!topSa.usesTargeting() || topTargets.getTargetCards().contains(sa.getHostCard())) {
            // if this does not target at all or already targets host, no need to redirect it again
            return new AiAbilityDecision(0, AiPlayDecision.CantPlayAi);
        }

        for (Card tgt : topTargets.getTargetCards()) {
            if (ComputerUtilAbility.getAbilitySourceName(sa).equals(tgt.getName()) && tgt.getController().equals(aiPlayer)) {
                // We are already targeting at least one card with the same name (e.g. in presence of 2+ Spellskites),
                // no need to retarget again to another one
                return new AiAbilityDecision(0, AiPlayDecision.CantPlayAi);
            }
        }

        if (topHost != null && !topHost.getController().isOpponentOf(aiPlayer)) {
            // make sure not to redirect our own abilities
            return new AiAbilityDecision(0, AiPlayDecision.CantPlayAi);
        }
        if (!topSa.canTarget(sa.getHostCard())) {
            // don't try targeting it if we can't legally target the host card with it in the first place
            return new AiAbilityDecision(0, AiPlayDecision.CantPlayAi);
        }
        if (!sa.canTarget(topSa)) {
            // don't try retargeting a spell that the current card can't legally retarget (e.g. Muck Drubb + Lightning Bolt to the face)
            return new AiAbilityDecision(0, AiPlayDecision.CantPlayAi);
        }

        if (sa.getPayCosts().getCostMana() != null && sa.getPayCosts().getCostMana().getMana().hasPhyrexian()) {
            ManaCost manaCost = sa.getPayCosts().getCostMana().getMana();
            int payDamage = manaCost.getPhyrexianCount() * 2;
            // e.g. Spellskite or a creature receiving its ability that requires Phyrexian mana P/U
            int potentialDmg = ComputerUtil.predictDamageFromSpell(topSa, aiPlayer);
            ManaCost normalizedMana = manaCost.getNormalizedMana();
            boolean canPay = ComputerUtilMana.canPayManaCost(new ManaCostBeingPaid(normalizedMana), sa, aiPlayer, false);
            if (potentialDmg != -1 && potentialDmg <= payDamage && !canPay
                    && topTargets.contains(aiPlayer)) {
                // do not pay Phyrexian mana if the spell is a damaging one but it deals less damage or the same damage as we'll pay life
                return new AiAbilityDecision(0, AiPlayDecision.CantPlayAi);
            }
        }

        Card firstCard = topTargets.getFirstTargetedCard();
        // if we're not the target don't intervene unless we can steal a buff
        if (firstCard != null && !aiPlayer.equals(firstCard.getController()) && !topHost.getController().equals(firstCard.getController()) && !topHost.getController().getAllies().contains(firstCard.getController())) {
            return new AiAbilityDecision(0, AiPlayDecision.CantPlayAi);
        }
        Player firstPlayer = topTargets.getFirstTargetedPlayer();
        if (firstPlayer != null && !aiPlayer.equals(firstPlayer)) {
            return new AiAbilityDecision(0, AiPlayDecision.CantPlayAi);
        }

        sa.resetTargets();
        sa.getTargets().add(topSa);
        return new AiAbilityDecision(100, AiPlayDecision.WillPlay);
    }
}
