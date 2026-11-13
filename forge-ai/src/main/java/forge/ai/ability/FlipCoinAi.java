package forge.ai.ability;

import forge.ai.AiAbilityDecision;
import forge.ai.AiPlayDecision;
import forge.ai.ComputerUtil;
import forge.ai.ComputerUtilAbility;
import forge.ai.SpecialCardAi;
import forge.ai.SpellAbilityAi;
import forge.game.card.Card;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;

public class FlipCoinAi extends SpellAbilityAi {

    /* (non-Javadoc)
     * @see forge.card.abilityfactory.SpellAiLogic#checkApiLogic(forge.game.player.Player, java.util.Map, forge.card.spellability.SpellAbility)
     */
    @Override
    protected AiAbilityDecision checkApiLogic(Player ai, SpellAbility sa) {
        if ("Invert Polarity".equals(ComputerUtilAbility.getAbilitySourceName(sa))) {
            // Steal-or-counter an opponent's untargeted haymaker; every other
            // FlipCoin card takes exactly the path it took before this branch.
            return SpecialCardAi.InvertPolarity.consider(ai, sa);
        }
        if ("FreneticSliver".equals(sa.getParam("AILogic"))) {
            // Frenetic Sliver's granted flip, on every Sliver (so keyed on the AILogic value, not
            // the source name): owner only, one flip per host on the stack, and only when a lethal
            // damage effect already dooms the host. Only frenetic_sliver.txt carries this value;
            // Frenetic Efreet keeps the stock PhaseOut branch below.
            return SpecialCardAi.FreneticSliver.consider(ai, sa);
        }
        if (SpecialCardAi.GoblinArchaeologist.NAME.equals(ComputerUtilAbility.getAbilitySourceName(sa))) {
            // Artifact removal on a coin flip. The stock fallthrough below never picks a target
            // (isTargetNumberValid on an empty list), so the ability was TargetingFailed forever even
            // without the RemoveDeck hint. The owner's and a thief's alike; reads game state only, no
            // random draw. Every other FlipCoin card takes exactly the path it took before this branch.
            return SpecialCardAi.GoblinArchaeologist.consider(ai, sa);
        }
        if (sa.hasParam("AILogic")) {
            String ailogic = sa.getParam("AILogic");
            if (ailogic.equals("PhaseOut")) {
                if (!ComputerUtil.predictThreatenedObjects(sa.getActivatingPlayer(), sa).contains(sa.getHostCard())) {
                    return new AiAbilityDecision(0, AiPlayDecision.CantPlayAi);
                }
            } else if (ailogic.equals("Bangchuckers")) {
                if (ai.getGame().getPhaseHandler().getPhase().isBefore(PhaseType.END_OF_TURN) ) {
                    return new AiAbilityDecision(0, AiPlayDecision.CantPlayAi);
                }
                sa.resetTargets();
                for (Player o : ai.getOpponents()) {
                    if (sa.canTarget(o) && o.canLoseLife() && !o.cantLoseForZeroOrLessLife()) {
                        sa.getTargets().add(o);
                        return new AiAbilityDecision(100, AiPlayDecision.WillPlay);
                    }
                }
                for (Card c : ai.getOpponents().getCreaturesInPlay()) {
                    if (sa.canTarget(c)) {
                        sa.getTargets().add(c);
                        return new AiAbilityDecision(100, AiPlayDecision.WillPlay);
                    }
                }
                return new AiAbilityDecision(0, AiPlayDecision.CantPlayAi);
            } else if (ailogic.equals("KillOrcs")) {
            	if (ai.getGame().getPhaseHandler().getPhase().isBefore(PhaseType.END_OF_TURN) ) {
                    return new AiAbilityDecision(0, AiPlayDecision.CantPlayAi);
            	}
            	sa.resetTargets();
                for (Card c : ai.getOpponents().getCreaturesInPlay()) {
                    if (sa.canTarget(c)) {
                        sa.getTargets().add(c);
                        return new AiAbilityDecision(100, AiPlayDecision.WillPlay);
                    }
            	}
            	return new AiAbilityDecision(0, AiPlayDecision.CantPlayAi);
            }
        }
        if (sa.usesTargeting() && sa.getTargetRestrictions().getZone().contains(ZoneType.Stack)) {
            return targetSpell(ai, sa);
        }
        if (sa.isTargetNumberValid()) {
            return new AiAbilityDecision(100, AiPlayDecision.WillPlay);
        } else {
            return new AiAbilityDecision(0, AiPlayDecision.TargetingFailed);
        }
    }

    /** A spell worth a coin flip: either result (control of it, or a counter) is good for the AI, and it costs three or more. */
    private static final int MIN_SPELL_CMC = 3;

    /**
     * Invert Polarity: win the flip and the opponent's spell is the AI's, lose it and the spell is countered. Both are worth
     * the mana for a spell that matters (#5897), so it is aimed at the opponent's spell on top of the stack.
     */
    private AiAbilityDecision targetSpell(final Player ai, final SpellAbility sa) {
        sa.resetTargets();
        if (ai.getGame().getStack().isEmpty()) {
            return new AiAbilityDecision(0, AiPlayDecision.CantPlayAi);
        }
        final SpellAbility top = ai.getGame().getStack().peekAbility();
        if (top == null || !top.isSpell() || top.getActivatingPlayer() == null || !top.getActivatingPlayer().isOpponentOf(ai)
                || top.getHostCard() == null || top.getHostCard().getCMC() < MIN_SPELL_CMC || !sa.canTarget(top)) {
            return new AiAbilityDecision(0, AiPlayDecision.CantPlayAi);
        }
        sa.getTargets().add(top);
        return new AiAbilityDecision(100, AiPlayDecision.WillPlay);
    }

    @Override
    public AiAbilityDecision chkDrawback(Player ai, SpellAbility sa) {
        return canPlay(ai, sa);
    }
}
