package forge.sim;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.testng.Assert;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.Test;

import forge.ai.AiCache;
import forge.util.SimScope;

/** AiCache.cache()'s three branches (Task 7b): a bound scope has its own map, an unbound thread under strict
 *  mode caches nothing, and an unbound thread otherwise shares the global map. Also pinned: the farm's own
 *  configuration, a bound scope under strict mode, still caches in its scope's map, and a scope's own clear
 *  empties that map and no other. */
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
        t.setDaemon(true);                    // a deadlocked cache must fail the test, not hold the surefire fork open
        t.start();
        t.join(30_000);
        if (t.isAlive()) {
            throw new AssertionError("the body did not finish within 30 s: a deadlock in AiCache?");
        }
        if (failed.get() != null) {
            throw new AssertionError(failed.get());
        }
        return out.get();
    }

    @Test(timeOut = 60_000)
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

    @Test(timeOut = 60_000)
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

    @Test(timeOut = 60_000)
    public void aBoundScopeStillCachesUnderStrictMode() throws Exception {
        SimScope.setStrict(true);             // GameRunner.boot() leaves strict mode on for every game the farm plays
        SimScope a = new SimScope(1L);
        Object arg = new Object();
        AtomicInteger computed = new AtomicInteger();
        Assert.assertEquals(under(a, () -> AiCache.getCached("AiCacheScopeTest.bound", () -> computed.incrementAndGet(), null, arg)), (Integer) 1);
        Assert.assertEquals(under(a, () -> AiCache.getCached("AiCacheScopeTest.bound", () -> computed.incrementAndGet(), null, arg)), (Integer) 1,
                "bound and strict (the farm's configuration): the scope's own map caches");
    }

    @Test(timeOut = 60_000)
    public void aScopesOwnClearEmptiesItsOwnMap() throws Exception {
        SimScope a = new SimScope(1L);
        Object arg = new Object();
        AtomicInteger computed = new AtomicInteger();
        Assert.assertEquals(under(a, () -> AiCache.getCached("AiCacheScopeTest.own", () -> computed.incrementAndGet(), null, arg)), (Integer) 1);
        under(a, () -> {
            AiCache.clear();                  // a's own map, under a's own scope
            return null;
        });
        Assert.assertEquals(under(a, () -> AiCache.getCached("AiCacheScopeTest.own", () -> computed.incrementAndGet(), null, arg)), (Integer) 2,
                "the owner's own clear emptied its map, so the supplier ran again");
    }
}
