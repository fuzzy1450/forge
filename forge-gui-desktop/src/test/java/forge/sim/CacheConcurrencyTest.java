package forge.sim;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.testng.Assert;
import org.testng.annotations.Test;

import forge.card.CardEdition;
import forge.game.card.Card;
import forge.game.card.CounterCustomType;
import forge.game.card.CounterType;
import forge.game.keyword.Keyword;
import forge.trackable.TrackableTypes;

/** The lazily filled static caches a shared JVM fills from several game threads at once.
 *
 *  <p>{@code customCounterTypesSurviveConcurrentFirstUse} is the race detector: the threads
 *  register the same keys at the same time, and against the unsynchronized HashMap it failed
 *  nondeterministically (two objects for one key, a lost entry); after the fix it never fails.
 *
 *  <p>{@code sortableCollectorNumbersAgreeAcrossThreads} is a read-path smoke test only: its
 *  cache is filled before the pool starts, so the threads never write and it also passes
 *  against a plain HashMap.
 *
 *  <p>{@code lookupCachesAreConcurrentMaps} pins the intent for the four lookup caches (Card,
 *  Keyword, TrackableTypes, CardEdition), which have no red-then-green test of their own. */
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

    /** The four lookup caches (a card's keyword set, a trackable enum type, a sortable collector
     *  number, the UI card cache) are pure caches whose only hazard is the unsynchronized write,
     *  so each must be a ConcurrentMap. Nothing but a race tells the map types apart, so this
     *  reads the private static fields. */
    @Test
    public void lookupCachesAreConcurrentMaps() throws Exception {
        assertConcurrentMap(Card.class, "cp2card");
        assertConcurrentMap(Keyword.class, "cardKeywordSetLookup");
        assertConcurrentMap(TrackableTypes.class, "enumTypes");
        assertConcurrentMap(CardEdition.class, "sortableCollNumberLookup");
    }

    private static void assertConcurrentMap(Class<?> owner, String fieldName) throws Exception {
        Field field = owner.getDeclaredField(fieldName);
        field.setAccessible(true);
        Object map = field.get(null);
        Assert.assertTrue(map instanceof ConcurrentMap, owner.getSimpleName() + "#" + fieldName
                + " must be a ConcurrentMap, but is " + (map == null ? "null" : map.getClass().getName()));
    }
}
