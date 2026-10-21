package forge.ai.ability;

import forge.ai.AiAbilityDecision;
import forge.ai.AiPlayDecision;
import forge.ai.SpecialCardAi;
import forge.ai.SpellAbilityAi;
import forge.game.cost.Cost;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.trigger.WrappedAbility;
import forge.util.collect.FCollectionView;

public class StoreSVarAi extends SpellAbilityAi {

    @Override
    protected AiAbilityDecision canPlay(Player ai, SpellAbility sa) {
        return new AiAbilityDecision(100, AiPlayDecision.WillPlay);
    }

    @Override
    protected AiAbilityDecision doTriggerNoCost(Player aiPlayer, SpellAbility sa, boolean mandatory) {
        if (sa instanceof WrappedAbility) {
            SpellAbility origSa = ((WrappedAbility)sa).getWrappedAbility();
            if (origSa.getHostCard().getName().equals("Maralen of the Mornsong Avatar")) {
                origSa.setXManaCostPaid(2);
            }
        }

        // Phyrexian Processor: "As this enters, pay any amount of life" is a replacement whose
        // StoreSVar is paid by ComputerUtil.playNoStack with no X announced (Count$xPaid would read
        // 0). PlayerControllerAi.playSpellAbilityNoStack calls doTrigger(effectSA, true) right
        // before that payment, so the X is chosen here, on the replacement's own SA, as the human
        // announce does. checkETBEffects never comes here (that ability is AbilityApiBased, not an
        // AbilitySub, so its doTrigger veto skips it). Any controller, a thief included: the
        // payment and the floor are the payer's own. Draws nothing.
        if (mandatory && sa.isReplacementAbility() && sa.getHostCard() != null
                && SpecialCardAi.PhyrexianProcessor.NAME.equals(sa.getHostCard().getName())) {
            sa.setXManaCostPaid(SpecialCardAi.PhyrexianProcessor.lifeToPay(aiPlayer, sa));
        }

        return new AiAbilityDecision(100, AiPlayDecision.WillPlay);
    }

    @Override
    public boolean willPayUnlessCost(Player payer, SpellAbility sa, Cost cost, boolean alreadyPaid, FCollectionView<Player> payers) {
        // Join Forces cards
        if (sa.hasParam("UnlessSwitched") && payers.size() > 1) {
            final Player p = sa.getActivatingPlayer();
            // not me or team mate
            if (!p.sameTeam(payer)) {
                return false;
            }
        }

        return super.willPayUnlessCost(payer, sa, cost, alreadyPaid, payers);
    }
}
