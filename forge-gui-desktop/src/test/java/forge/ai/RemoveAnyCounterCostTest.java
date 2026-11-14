package forge.ai;

import org.testng.Assert;
import org.testng.annotations.Test;

import forge.game.Game;
import forge.game.card.Card;
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
 */
public class RemoveAnyCounterCostTest extends AITest {

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

        Assert.assertTrue(ComputerUtilCost.checkRemoveCounterCost(ability.getPayCosts(), refinery, ability),
                "no objection to removing counters of any kind from another permanent");
    }
}
