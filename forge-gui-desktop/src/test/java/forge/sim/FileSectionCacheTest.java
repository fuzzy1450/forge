package forge.sim;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.testng.Assert;
import org.testng.annotations.Test;

import com.google.common.collect.HashBasedTable;
import com.google.common.collect.Table;

import forge.util.FileSection;

/** FileSection.parseToMap memoizes parsed lines in a static table that every game thread fills while
 *  cards are built. The table must be safe to fill from several threads at once and no write may be
 *  lost. A lost write shows in no parsed value (the line is simply parsed again), so the fill test
 *  checks that each cell it wrote is present; a count of the table's cells would let any other
 *  thread's stray cell spoil it. */
public class FileSectionCacheTest {

    @Test(timeOut = 120_000)
    public void distinctLinesAllLandUnderConcurrentFill() throws Exception {
        Field f = FileSection.class.getDeclaredField("parseToMapCache");
        f.setAccessible(true);
        Table<?, ?, ?> table = (Table<?, ?, ?>) f.get(null);
        final int threads = 8, lines = 4_000;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CyclicBarrier go = new CyclicBarrier(threads);                  // every thread starts writing at the same moment
        List<Future<Object>> done = new ArrayList<>();
        try {
            for (int t = 0; t < threads; t++) {
                final int id = t;
                done.add(pool.submit(() -> {
                    go.await();
                    for (int i = 0; i < lines; i++) {                    // this thread's own lines: every parse adds a cell
                        FileSection.parseToMap("T" + id + ":" + i, FileSection.COLON_KV_SEPARATOR);
                    }
                    return null;
                }));
            }
            for (Future<Object> fu : done) {
                fu.get(100, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }
        int missing = 0;
        for (int id = 0; id < threads; id++) {
            for (int i = 0; i < lines; i++) {
                if (!table.contains("T" + id + ":" + i, FileSection.COLON_KV_SEPARATOR)) {
                    missing++;
                }
            }
        }
        Assert.assertEquals(missing, 0, "cells lost to concurrent writes");
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
