package forge.ai;

import org.testng.Assert;
import org.testng.annotations.Test;

import forge.game.Game;
import forge.game.card.Card;
import forge.game.card.CounterEnumType;
import forge.game.cost.CostRemoveCounter;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;

/**
 * Moxite Refinery's cost removes X counters of any kind from an artifact or creature its controller picks
 * (SubCounter&lt;X/Any/Artifact.YouCtrl;Creature.YouCtrl/...&gt;): a CostRemoveCounter whose counter type is null, as
 * CounterType.getType answers "Any", paid from another permanent. ComputerUtilCost.checkRemoveCounterCost asked that
 * null type whether it was a +1/+1 counter, so the AI's eval thread threw a NullPointerException whenever it weighed
 * the ability and read the whole priority as nothing to play: 31 traces in the Flat MC ship's 1,700-game census
 * (none in N's), which full Flat MC reaches where N's heuristics did not.
 * <p>
 * Weighed, that null type was approved, while the AI's payer, AiCostDecision.visit(CostRemoveCounter), removes no
 * counters of a null type (nothing in the AI chooses which kinds to remove yet, its TODO says): every activation the
 * approval let through failed at payment and was rolled back, two tries a phase. The approval now asks the payer, as
 * it asks it for a CostRemoveAnyCounter, so the two agree.
 */
public class RemoveAnyCounterCostTest extends AITest {

    /**
     * Weighed without throwing, and refused as the AI's payer refuses it. Before the approval asked the payer this
     * expected an approval ("no objection"), which the payment then failed.
     */
    @Test
    public void aCostRemovingCountersOfAnyKindFromAnotherPermanentIsWeighed() {
        final Game game = initAndCreateGame();
        final Player ai = game.getPlayers().get(1);
        final Card refinery = addCard("Moxite Refinery", ai);
        addCard("Grizzly Bears", ai);
        final SpellAbility ability = refinery.getSpellAbilities().stream()
                .filter(SpellAbility::isActivatedAbility).findFirst().orElseThrow();
        ability.setActivatingPlayer(ai);
        final CostRemoveCounter anyKind = ability.getPayCosts().getCostParts().stream()
                .filter(CostRemoveCounter.class::isInstance).map(CostRemoveCounter.class::cast).findFirst().orElseThrow();
        Assert.assertNull(anyKind.counter, "a counter of any kind has no type");
        Assert.assertFalse(anyKind.payCostFromSource(), "it is paid from another permanent");
        Assert.assertNull(new AiCostDecision(ai, ability, false).visit(anyKind),
                "the AI's payer removes no counters of any kind");

        Assert.assertFalse(ComputerUtilCost.checkRemoveCounterCost(ability.getPayCosts(), refinery, ability),
                "weighed, and refused as the payer refuses it");
    }

    /**
     * The board an approved activation failed on: the rules let the AI remove X = 2 counters of any kind from its
     * Everflowing Chalice, which holds two charge counters, but its payer removes none, so the approval refuses them
     * too.
     */
    @Test
    public void countersOfAnyKindAreApprovedOnlyWhenThePayerRemovesThem() {
        final Game game = initAndCreateGame();
        final Player ai = game.getPlayers().get(1);
        final Card refinery = addCard("Moxite Refinery", ai);
        addCard("Everflowing Chalice", ai).setCounters(CounterEnumType.CHARGE, 2);
        final SpellAbility ability = refinery.getSpellAbilities().stream()
                .filter(SpellAbility::isActivatedAbility).findFirst().orElseThrow();
        ability.setActivatingPlayer(ai);
        ability.setXManaCostPaid(2);
        final CostRemoveCounter anyKind = ability.getPayCosts().getCostParts().stream()
                .filter(CostRemoveCounter.class::isInstance).map(CostRemoveCounter.class::cast).findFirst().orElseThrow();
        Assert.assertNull(anyKind.counter, "a counter of any kind has no type");
        Assert.assertFalse(anyKind.payCostFromSource(), "it is paid from another permanent");
        Assert.assertTrue(anyKind.canPay(ability, ai, false), "the rules let the AI remove the Chalice's two counters");
        Assert.assertNull(new AiCostDecision(ai, ability, false).visit(anyKind), "but its payer removes none");

        Assert.assertFalse(ComputerUtilCost.checkRemoveCounterCost(ability.getPayCosts(), refinery, ability),
                "so the approval refuses them as well, rather than an activation failing at payment");
    }
}
