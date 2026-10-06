package forge.sim;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicReference;

import org.testng.Assert;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.Test;

import forge.util.GameAbandoned;
import forge.util.SimScope;
import forge.util.UnscopedAccessError;

public class SimScopeTest {

    @AfterMethod
    public void unbindAndRelax() {
        if (SimScope.current() != null) {
            SimScope.exit();
        }
        SimScope.setStrict(false);
    }

    private static List<Integer> draw(SimScope s, int n) {
        List<Integer> out = new ArrayList<>();
        Random r = s.random();
        for (int i = 0; i < n; i++) {
            out.add(r.nextInt(1000));
        }
        return out;
    }

    @Test
    public void streamsAndIdsAreSeedDeterminedAndIndependentAcrossThreads() throws Exception {
        List<Integer> expected = new ArrayList<>();
        Random ref = new Random(42L);
        for (int i = 0; i < 20; i++) {
            expected.add(ref.nextInt(1000));
        }

        AtomicReference<List<Integer>> fromA = new AtomicReference<>();
        AtomicReference<List<Integer>> fromB = new AtomicReference<>();
        AtomicReference<List<Integer>> idsA = new AtomicReference<>();
        AtomicReference<List<Integer>> idsB = new AtomicReference<>();
        Thread a = new Thread(() -> {
            SimScope s = new SimScope(42L);
            SimScope.enter(s);
            try {
                fromA.set(draw(s, 20));
                idsA.set(List.of(s.nextId(SimScope.Counter.SPELL_ABILITY), s.nextId(SimScope.Counter.SPELL_ABILITY),
                                 s.nextId(SimScope.Counter.COMBAT_VIEW), s.nextId(SimScope.Counter.COMBAT_VIEW)));
            } finally {
                SimScope.exit();
            }
        }, "Game-sim-test-a");
        Thread b = new Thread(() -> {
            SimScope s = new SimScope(42L);
            SimScope.enter(s);
            try {
                s.nextId(SimScope.Counter.TRIGGER); // a step on B's counters must not move A's
                fromB.set(draw(s, 20));
                idsB.set(List.of(s.nextId(SimScope.Counter.SPELL_ABILITY), s.nextId(SimScope.Counter.SPELL_ABILITY),
                                 s.nextId(SimScope.Counter.COMBAT_VIEW), s.nextId(SimScope.Counter.COMBAT_VIEW)));
            } finally {
                SimScope.exit();
            }
        }, "Game-sim-test-b");
        a.start();
        b.start();
        a.join();
        b.join();

        Assert.assertEquals(fromA.get(), expected, "scope A plays the seed's stream");
        Assert.assertEquals(fromB.get(), expected, "scope B plays the same seed's stream, untouched by A");
        Assert.assertEquals(idsA.get(), List.of(1, 2, -2, -3),
                "maxId counters start at 0 and pre-increment; CombatView starts at -2 and post-decrements");
        Assert.assertEquals(idsB.get(), idsA.get(), "B's ids do not depend on A's");
        Assert.assertNull(SimScope.current(), "nothing leaked onto the test thread");
    }

    @Test
    public void enterTwiceAndExitWithoutEnterThrow() {
        SimScope.enter(new SimScope(1L));
        Assert.assertThrows(IllegalStateException.class, () -> SimScope.enter(new SimScope(2L)));
        SimScope.exit();
        Assert.assertThrows(IllegalStateException.class, SimScope::exit);
    }

    @Test
    public void unboundAccessFallsBackUnlessStrict() {
        Random fallback = new Random(7L);
        Assert.assertSame(SimScope.random(() -> fallback), fallback, "unbound, non-strict: the fallback answers");
        int[] counter = { 10 };
        Assert.assertEquals(SimScope.nextId(SimScope.Counter.TRIGGER, () -> ++counter[0]), 11,
                "unbound, non-strict: the static steps");

        SimScope.setStrict(true);
        Assert.assertThrows(UnscopedAccessError.class, () -> SimScope.random(() -> fallback));
        Assert.assertThrows(UnscopedAccessError.class, () -> SimScope.nextId(SimScope.Counter.TRIGGER, () -> ++counter[0]));
        Assert.assertThrows(UnscopedAccessError.class,
                () -> SimScope.reset(SimScope.Counter.TRIGGER, 50000, () -> counter[0] = 50000));
        Assert.assertThrows(UnscopedAccessError.class, () -> SimScope.requireUnboundAllowed("test"));
        Assert.assertEquals(counter[0], 11, "strict mode never ran a fallback");

        SimScope s = new SimScope(3L);
        SimScope.enter(s);
        Assert.assertSame(SimScope.random(() -> fallback), s.random(), "bound: the scope answers even under strict mode");
        Assert.assertEquals(SimScope.nextId(SimScope.Counter.TRIGGER, () -> ++counter[0]), 1);
        SimScope.reset(SimScope.Counter.TRIGGER, 50000, () -> counter[0] = 50000);
        Assert.assertEquals(SimScope.nextId(SimScope.Counter.TRIGGER, () -> ++counter[0]), 50001,
                "reset moved the scope's counter, not the static");
        Assert.assertEquals(counter[0], 11);
    }

    @Test
    public void aStrictRefusalIsCountedBeforeItThrows() {
        long before = SimScope.VIOLATIONS.get();
        SimScope.setStrict(true);
        Assert.assertThrows(UnscopedAccessError.class, () -> SimScope.requireUnboundAllowed("x"));
        Assert.assertEquals(SimScope.VIOLATIONS.get() - before, 1L,
                "counted once, before the throw, so a refusal that Forge's code swallows still shows");
        SimScope.setStrict(false);
        SimScope.requireUnboundAllowed("x");
        Assert.assertEquals(SimScope.VIOLATIONS.get() - before, 1L, "not strict: nothing refused, nothing counted");
    }

    @Test
    public void simulationErrorFindsAWrappedErrorOnlyUnderASimulation() {
        UnscopedAccessError violation = new UnscopedAccessError("x");
        Throwable wrapped = new CompletionException(new RuntimeException("AbilityFactory's wrapper", violation));
        Assert.assertNull(SimScope.simulationError(wrapped),
                "unbound and not strict, the GUI's case: no walk, so its one-level checks stay as they were");
        SimScope.setStrict(true);
        Assert.assertSame(SimScope.simulationError(wrapped), violation, "strict: found two wrappers down");
        Assert.assertNull(SimScope.simulationError(new CompletionException(new IllegalStateException())),
                "no Error in the chain: nothing to rethrow");
        SimScope.setStrict(false);
        SimScope.enter(new SimScope(1L));
        Assert.assertSame(SimScope.simulationError(wrapped), violation, "bound: found as well");
    }

    @Test
    public void cancelledScopeThrowsGameAbandoned() {
        SimScope s = new SimScope(5L);
        s.random();
        s.cancel();
        Assert.assertTrue(s.isCancelled());
        Assert.assertThrows(GameAbandoned.class, s::random);
        Assert.assertThrows(GameAbandoned.class, () -> s.nextId(SimScope.Counter.GAME));
    }

    @Test
    public void adoptingCarriesTheCallersScopeToAnotherThread() throws Exception {
        SimScope s = new SimScope(9L);
        SimScope.enter(s);
        AtomicReference<SimScope> seen = new AtomicReference<>();
        AtomicReference<SimScope> after = new AtomicReference<>();
        Runnable task = SimScope.adopting(() -> seen.set(SimScope.current()));
        Thread t = new Thread(() -> {
            task.run();
            after.set(SimScope.current());
        }, "Game AI Eval test");
        t.start();
        t.join();
        Assert.assertSame(seen.get(), s, "the child ran under the parent's scope");
        Assert.assertNull(after.get(), "and the binding did not outlive the task");
        SimScope.exit();

        Runnable bare = () -> { };
        Assert.assertSame(SimScope.adopting(bare), bare, "with no scope bound, adopting is a no-op");
    }

    @Test
    public void scratchIsPerScope() {
        SimScope a = new SimScope(1L);
        SimScope b = new SimScope(1L);
        List<String> la = a.scratch("k", ArrayList::new);
        la.add("x");
        Assert.assertSame(a.scratch("k", ArrayList::new), la, "same key, same scope, same object");
        Assert.assertTrue(b.<List<String>>scratch("k", ArrayList::new).isEmpty(), "another scope has its own");
    }
}
