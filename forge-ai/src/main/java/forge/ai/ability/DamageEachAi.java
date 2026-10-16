package forge.ai.ability;


import forge.ai.AiAbilityDecision;
import forge.ai.AiPlayDecision;
import forge.ai.ComputerUtilAbility;
import forge.ai.SpecialCardAi;
import forge.game.ability.AbilityUtils;
import forge.game.player.Player;
import forge.game.player.PlayerCollection;
import forge.game.player.PlayerPredicates;
import forge.game.spellability.SpellAbility;

public class DamageEachAi extends DamageAiBase {

    /* (non-Javadoc)
     * @see forge.card.abilityfactory.SpellAiLogic#canPlayAI(forge.game.player.Player, java.util.Map, forge.card.spellability.SpellAbility)
     */
    @Override
    protected AiAbilityDecision canPlay(Player ai, SpellAbility sa) {
        if ("Wave of Reckoning".equals(ComputerUtilAbility.getAbilitySourceName(sa))) {
            // NumDmg$ Count$CardPower is read per creature at resolution
            // (EachToItself); the generic path below reads the sorcery's own
            // power (0) and only knows "burn a player", so it always refused.
            // Every other EachDamage card takes exactly the path it took before.
            return SpecialCardAi.WaveOfReckoning.consider(ai, sa);
        }

        final String logic = sa.getParam("AILogic");

        PlayerCollection targetableOpps = ai.getOpponents().filter(PlayerPredicates.isTargetableBy(sa));
        Player weakestOpp = targetableOpps.min(PlayerPredicates.compareByLife());

        if (sa.usesTargeting() && weakestOpp != null) {
            if ("MadSarkhanUltimate".equals(logic) && !SpecialCardAi.SarkhanTheMad.considerUltimate(ai, sa, weakestOpp)) {
                return new AiAbilityDecision(0, AiPlayDecision.CantPlayAi);
            }
            sa.resetTargets();
            if (weakestOpp.canLoseLife() && !weakestOpp.cantLoseForZeroOrLessLife()) {
                sa.getTargets().add(weakestOpp);
                return new AiAbilityDecision(100, AiPlayDecision.WillPlay);
            }
        }
        
        final String damage = sa.getParam("NumDmg");
        final int iDmg = AbilityUtils.calculateAmount(sa.getHostCard(), damage, sa);

        if (shouldTgtP(ai, sa, iDmg, false)) {
            return new AiAbilityDecision(100, AiPlayDecision.WillPlay);
        }

        return new AiAbilityDecision(0, AiPlayDecision.CantPlayAi);
    }

    @Override
    public AiAbilityDecision chkDrawback(Player aiPlayer, SpellAbility sa) {
        if (SpecialCardAi.SignatureSlam.NAME.equals(ComputerUtilAbility.getAbilitySourceName(sa))) {
            // Dead-card batch 3, row 89. Signature Slam's "target creature you don't control" is
            // this sub's own target, and the stock answer below never chose one: the AI paid
            // {2}{G}, MagicStack.add refused the chain (hasLegalTargeting walks every sub) and the
            // card stayed in the stack zone uncast (358 of its 615 rows under 2.0.15+55e709fa6ba).
            // Reached only after the root PutCounter's stock approval, which made every draw A
            // made; the chooser draws none. With no victim a regular cast keeps A's answer, and
            // AiController.canPlayAndPayForFace refuses it after canPayCost. Every other
            // EachDamage sub takes exactly the path it took before.
            return SpecialCardAi.SignatureSlam.chooseVictim(aiPlayer, sa);
        }
        // check AI life before playing this drawback?
        return new AiAbilityDecision(100, AiPlayDecision.WillPlay);
    }

    /* (non-Javadoc)
     * @see forge.card.abilityfactory.SpellAiLogic#doTriggerAINoCost(forge.game.player.Player, java.util.Map, forge.card.spellability.SpellAbility, boolean)
     */
    @Override
    protected AiAbilityDecision doTriggerNoCost(Player ai, SpellAbility sa, boolean mandatory) {
        if (mandatory) {
            return new AiAbilityDecision(100, AiPlayDecision.WillPlay);
        }

        return canPlay(ai, sa);
    }

}
