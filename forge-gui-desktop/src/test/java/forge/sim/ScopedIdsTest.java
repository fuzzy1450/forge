package forge.sim;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

import org.testng.Assert;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.Test;

import forge.ai.ability.ChangeZoneAi;
import forge.game.combat.CombatView;
import forge.game.spellability.SpellAbilityStackInstance;
import forge.game.trigger.Trigger;
import forge.trackable.Tracker;
import forge.util.SimScope;
import forge.util.UnscopedAccessError;

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

    /** ChangeZoneAi's Intuition cards, like AiCache: bound, the scope's own; unbound under strict mode, refused (and
     *  counted) rather than quietly the GUI's static. */
    @Test
    public void changeZoneAiRefusesTheGuisCardsUnboundUnderStrictMode() throws Exception {
        Method cardsToChoose = ChangeZoneAi.class.getDeclaredMethod("cardsToChoose");
        cardsToChoose.setAccessible(true);
        long before = SimScope.VIOLATIONS.get();
        SimScope.setStrict(true);
        try {
            InvocationTargetException thrown = Assert.expectThrows(InvocationTargetException.class, () -> cardsToChoose.invoke(null));
            Assert.assertTrue(thrown.getCause() instanceof UnscopedAccessError, "unbound under strict mode: " + thrown.getCause());
            Assert.assertEquals(SimScope.VIOLATIONS.get() - before, 1L, "the refusal is counted");
        } finally {
            SimScope.setStrict(false);
        }
    }
}
