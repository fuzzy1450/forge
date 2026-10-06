package forge.sim;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.testng.Assert;
import org.testng.annotations.Test;

import com.google.common.collect.HashBasedTable;

import forge.util.FileSection;

/** FileSection.parseToMap memoizes parsed lines in a static table that every game thread fills while
 *  cards are built. The table must be safe to fill from several threads at once; the parse itself is
 *  pure, so a lost or repeated write changes no result. */
public class FileSectionCacheTest {

    @Test(timeOut = 120_000)
    public void parseToMapFillsItsCacheFromManyThreadsWithConsistentResults() throws Exception {
        final int threads = 8, lines = 4_000;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<?>> done = new ArrayList<>();
        try {
            for (int t = 0; t < threads; t++) {
                done.add(pool.submit(() -> {
                    for (int i = 0; i < lines; i++) {
                        Map<String, String> m = FileSection.parseToMap("Alpha:" + i + "|Beta:" + (i * 7), FileSection.COLON_KV_SEPARATOR);
                        Assert.assertEquals(m.get("Alpha"), String.valueOf(i));
                        Assert.assertEquals(m.get("Beta"), String.valueOf(i * 7));
                    }
                }));
            }
            for (Future<?> f : done) {
                f.get(100, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdown();
        }
        for (int i = 0; i < lines; i++) {
            Map<String, String> m = FileSection.parseToMap("Alpha:" + i + "|Beta:" + (i * 7), FileSection.COLON_KV_SEPARATOR);
            Assert.assertEquals(m.get("Alpha"), String.valueOf(i), "a cached entry answers the same after the concurrent fill");
        }
    }

    @Test
    public void parseCacheIsNotAPlainHashBasedTable() throws Exception {
        Field f = FileSection.class.getDeclaredField("parseToMapCache");
        f.setAccessible(true);
        Object table = f.get(null);
        Assert.assertNotNull(table);
        Assert.assertFalse(table instanceof HashBasedTable,
                "FileSection.parseToMapCache must be a thread-safe table, not a bare HashBasedTable");
    }
}
