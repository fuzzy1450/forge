package forge.sim;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.testng.Assert;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.Test;

import forge.ai.AiCache;
import forge.util.SimScope;

/** AiCache.cache()'s three branches (Task 7b): a bound scope has its own map, an unbound thread under strict
 *  mode caches nothing, and an unbound thread otherwise shares the global map. */
public class AiCacheScopeTest {

    @AfterMethod
    public void relax() {
        if (SimScope.current() != null) {
            SimScope.exit();
        }
        SimScope.setStrict(false);
        AiCache.clear();                      // unbound, non-strict: the global map
    }

    private static <T> T under(SimScope scope, java.util.function.Supplier<T> body) throws Exception {
        AtomicReference<T> out = new AtomicReference<>();
        AtomicReference<Throwable> failed = new AtomicReference<>();
        Thread t = new Thread(() -> {
            SimScope.enter(scope);
            try {
                out.set(body.get());
            } catch (Throwable e) {
                failed.set(e);
            } finally {
                SimScope.exit();
            }
        }, "Game-sim-aicache-test");
        t.start();
        t.join();
        if (failed.get() != null) {
            throw new AssertionError(failed.get());
        }
        return out.get();
    }

    @Test
    public void oneScopesClearDoesNotTouchAnothersEntries() throws Exception {
        SimScope a = new SimScope(1L), b = new SimScope(2L);
        Object arg = new Object();
        Assert.assertEquals(under(a, () -> AiCache.getCached("AiCacheScopeTest.k", () -> "a1", null, arg)), "a1");
        Assert.assertEquals(under(b, () -> {
            AiCache.clear();                                             // b's own map only
            return AiCache.getCached("AiCacheScopeTest.k", () -> "b1", null, arg);
        }), "b1", "b computes its own value: a's entry is not b's");
        Assert.assertEquals(under(a, () -> AiCache.getCached("AiCacheScopeTest.k", () -> "a2", null, arg)), "a1",
                "a's entry survived b's clear and b's fill");
    }

    @Test
    public void unboundStrictComputesEveryTimeAndFillsNothing() {
        AtomicInteger computed = new AtomicInteger();
        Object arg = new Object();
        SimScope.setStrict(true);
        Assert.assertEquals(AiCache.getCached("AiCacheScopeTest.strict", () -> computed.incrementAndGet(), null, arg), (Integer) 1);
        Assert.assertEquals(AiCache.getCached("AiCacheScopeTest.strict", () -> computed.incrementAndGet(), null, arg), (Integer) 2,
                "strict and unbound: no cache, so the supplier runs again");
        SimScope.setStrict(false);
        Assert.assertEquals(AiCache.getCached("AiCacheScopeTest.strict", () -> computed.incrementAndGet(), null, arg), (Integer) 3,
                "the global map received nothing while strict was on");
        Assert.assertEquals(AiCache.getCached("AiCacheScopeTest.strict", () -> computed.incrementAndGet(), null, arg), (Integer) 3,
                "unbound and not strict: the global map caches");
    }
}
