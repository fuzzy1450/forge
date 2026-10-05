package forge.sim;

import org.testng.Assert;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.Test;

import forge.game.combat.CombatView;
import forge.game.spellability.SpellAbilityStackInstance;
import forge.game.trigger.Trigger;
import forge.trackable.Tracker;
import forge.util.SimScope;

public class ScopedIdsTest {

    @AfterMethod
    public void unbind() {
        if (SimScope.current() != null) {
            SimScope.exit();
        }
    }

    @Test
    public void stackInstanceIdsRestartPerScope() {
        int unboundBefore = SpellAbilityStackInstance.nextId();      // the static, wherever it stands
        SimScope.enter(new SimScope(1L));
        Assert.assertEquals(SpellAbilityStackInstance.nextId(), 1);
        Assert.assertEquals(SpellAbilityStackInstance.nextId(), 2);
        SimScope.exit();
        SimScope.enter(new SimScope(2L));
        Assert.assertEquals(SpellAbilityStackInstance.nextId(), 1, "a new scope starts over");
        SimScope.exit();
        Assert.assertEquals(SpellAbilityStackInstance.nextId(), unboundBefore + 1,
                "the static stepped once, by the unbound call, and never by the scoped ones");
    }

    @Test
    public void combatViewIdsStartAtMinusTwoPerScope() {
        SimScope.enter(new SimScope(1L));
        Assert.assertEquals(new CombatView(new Tracker()).getId(), -2);
        Assert.assertEquals(new CombatView(new Tracker()).getId(), -3);
        SimScope.exit();
        SimScope.enter(new SimScope(2L));
        Assert.assertEquals(new CombatView(new Tracker()).getId(), -2);
    }

    @Test
    public void triggerResetMovesTheBoundScopeOnly() {
        SimScope s = new SimScope(1L);
        SimScope.enter(s);
        Trigger.resetIDs();
        Assert.assertEquals(s.nextId(SimScope.Counter.TRIGGER), 50001,
                "Match.prepareAllZones' reset lands on the scope");
    }
}
