package forge.ai.ability;

import forge.ai.AiAbilityDecision;
import forge.ai.AiPlayDecision;
import forge.ai.SpecialCardAi;
import forge.ai.SpellAbilityAi;
import forge.game.Game;
import forge.game.card.Card;
import forge.game.cost.Cost;
import forge.game.phase.PhaseHandler;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.player.PlayerActionConfirmMode;
import forge.game.spellability.SpellAbility;

import java.util.Map;

public class RollDiceAi extends SpellAbilityAi {
    @Override
    protected AiAbilityDecision checkApiLogic(Player aiPlayer, SpellAbility sa) {
        Card source = sa.getHostCard();
        Game game = aiPlayer.getGame();
        PhaseHandler ph = game.getPhaseHandler();
        Cost cost = sa.getPayCosts();
        String logic = sa.getParamOrDefault("AILogic", "");

        // Dead-card batch 2, row 80: berserkers_frenzy.txt is the only script with this logic.
        // The mana-cost default below accepts only at the opponent's end step, which the card's
        // own ActivationPhases (Upkeep->Declare Attackers) excludes. RollDiceAi draws no RNG on
        // any path and every decline in the evaluator is RNG-free, so the router's position is
        // parity-neutral. Play-effect casts (doTriggerNoCost) never reach checkApiLogic.
        if ("BerserkersFrenzy".equals(logic)) {
            return SpecialCardAi.BerserkersFrenzy.consider(aiPlayer, sa);
        }
        // Dead-card batch 2, row 96: valiant_endeavor.txt is the only script with this logic. The
        // same mana-cost default refuses this sorcery on every pass (sorcery timing never reaches
        // the opponent's end step). As for row 80, RollDiceAi draws no RNG on any path and every
        // decline in the evaluator is RNG-free, so the router's position is parity-neutral;
        // Play-effect casts (doTriggerNoCost) never reach checkApiLogic.
        if ("ValiantEndeavor".equals(logic)) {
            return SpecialCardAi.ValiantEndeavor.consider(aiPlayer, sa);
        }
        // Dead-card batch 3, row 34: wild_endeavor.txt is the only script with this logic. The same
        // mana-cost default refuses this sorcery on every pass (sorcery timing never reaches the
        // opponent's end step). As for rows 80 and 96, RollDiceAi draws no RNG on any path and every
        // decline in the evaluator is RNG-free, so the router's position is parity-neutral;
        // Play-effect casts (doTriggerNoCost) never reach checkApiLogic.
        if ("WildEndeavor".equals(logic)) {
            return SpecialCardAi.WildEndeavor.consider(aiPlayer, sa);
        }

        if (logic.equals("Combat")) {
            boolean result = ph.inCombat() && ((game.getCombat().isAttacking(source) && game.getCombat().isUnblocked(source)) || game.getCombat().isBlocking(source));
            return result ? new AiAbilityDecision(100, AiPlayDecision.WillPlay) : new AiAbilityDecision(0, AiPlayDecision.CantPlayAi);
        } else if (logic.equals("CombatEarly")) {
            boolean result = ph.inCombat() && (game.getCombat().isAttacking(source) || game.getCombat().isBlocking(source));
            return result ? new AiAbilityDecision(100, AiPlayDecision.WillPlay) : new AiAbilityDecision(0, AiPlayDecision.CantPlayAi);
        } else if (logic.equals("Main2")) {
            boolean result = ph.is(PhaseType.MAIN2, aiPlayer);
            return result ? new AiAbilityDecision(100, AiPlayDecision.WillPlay) : new AiAbilityDecision(0, AiPlayDecision.CantPlayAi);
        }

        if (cost != null && (sa.getPayCosts().hasManaCost() || sa.getPayCosts().hasTapCost())) {
            boolean result = ph.getNextTurn() == aiPlayer && ph.is(PhaseType.END_OF_TURN);
            // Dead-card batch 4, row 54: myrkuls_edict.txt is the only script with this logic. This default
            // refuses the sorcery at every own-turn consult (it accepts only at the opponent's end step).
            // Unlike rows 80, 96 and 34 the router sits inside the default and runs only on its refusal, so
            // every consult the stock approves keeps the stock answer verbatim: a no-cost copy (ManaAi's
            // ritual look-ahead, with the carriers' Dark Ritual), a free alternative cost (the WillPlay
            // below), the end step before our turn under a flash grant (result). RollDiceAi draws nothing and
            // consider() draws nothing on a decline; Play-effect casts (doTriggerNoCost) never get here.
            if (!result && "MyrkulsEdict".equals(logic)) {
                return SpecialCardAi.MyrkulsEdict.consider(aiPlayer, sa);
            }
            return result ? new AiAbilityDecision(100, AiPlayDecision.WillPlay) : new AiAbilityDecision(0, AiPlayDecision.CantPlayAi);
        }

        return new AiAbilityDecision(100, AiPlayDecision.WillPlay);
    }

    @Override
    protected AiAbilityDecision doTriggerNoCost(Player aiPlayer, SpellAbility sa, boolean mandatory) {
        return new AiAbilityDecision(100, AiPlayDecision.WillPlay);
    }

    @Override
    public boolean confirmAction(Player player, SpellAbility sa, PlayerActionConfirmMode mode, String message, Map<String, Object> params) {
        return true;
    }
}
