package forge.sim;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.testng.Assert;
import org.testng.annotations.Test;

import forge.card.CardEdition;
import forge.game.card.CounterCustomType;
import forge.game.card.CounterType;

/** The lazily filled static caches a shared JVM fills from several game threads at once.
 *  Against the unsynchronized HashMaps these tests fail NONDETERMINISTICALLY (lost entries,
 *  two objects for one key); after the fix they never fail. */
public class CacheConcurrencyTest {

    @Test(timeOut = 120_000)
    public void customCounterTypesSurviveConcurrentFirstUse() throws Exception {
        final int threads = 8, keys = 5_000;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<?>> done = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            done.add(pool.submit(() -> {
                for (int i = 0; i < keys; i++) {
                    CounterCustomType a = CounterCustomType.get("CacheTest-" + i);
                    CounterCustomType b = CounterCustomType.get("CacheTest-" + i);
                    Assert.assertSame(a, b, "one object per key, whoever registered it first");
                }
            }));
        }
        for (Future<?> f : done) {
            f.get(100, TimeUnit.SECONDS);
        }
        pool.shutdown();
        Set<CounterType> all = CounterCustomType.getValues();
        long ours = all.stream().filter(c -> c.toString().startsWith("CacheTest-")).count();
        Assert.assertEquals(ours, keys, "every key registered exactly once");
    }

    @Test(timeOut = 120_000)
    public void sortableCollectorNumbersAgreeAcrossThreads() throws Exception {
        final int threads = 8;
        String[] inputs = { "1", "10", "2a", "100b", "7", "007", "WS6", "S1", "12", "3" };
        String[] expected = new String[inputs.length];
        for (int i = 0; i < inputs.length; i++) {
            expected[i] = CardEdition.getSortableCollectorNumber(inputs[i]);
        }
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<?>> done = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            done.add(pool.submit(() -> {
                for (int round = 0; round < 2_000; round++) {
                    for (int i = 0; i < inputs.length; i++) {
                        Assert.assertEquals(CardEdition.getSortableCollectorNumber(inputs[i]), expected[i]);
                    }
                }
            }));
        }
        for (Future<?> f : done) {
            f.get(100, TimeUnit.SECONDS);
        }
        pool.shutdown();
    }
}
