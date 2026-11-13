/*
 * Forge: Play Magic: the Gathering.
 * Copyright (C) 2011  Forge Team
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package forge.util;

import java.security.SecureRandom;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * <p>
 * MyRandom class.<br>
 * Preferably all Random numbers should be retrieved using this wrapper class
 * </p>
 * 
 * @author Forge
 * @version $Id$
 */
public class MyRandom {
    /**
     * Every Random method (nextInt, nextBoolean, shuffle, ...) funnels through next(bits).
     * The draw counter lives on the stream, so a game's undo detection only sees its own draws.
     */
    private static final class CountingRandom extends Random {
        private final Random delegate;
        private final AtomicLong calls = new AtomicLong();
        CountingRandom(Random delegate) { super(0); this.delegate = delegate; }
        @Override protected int next(int bits) {
            calls.incrementAndGet();
            if (TRACE) { trace(); }
            return delegate.nextInt() >>> (32 - bits);
        }
        @Override public void setSeed(long seed) {
            if (delegate != null) delegate.setSeed(seed);   // null during super(0)
        }
        long callCount() { return calls.get(); }
    }

    /**
     * Per-thread random source. Thread-local so concurrent games in one JVM
     * (headless simulation workers, parallel AI harnesses) that seed their
     * stream via {@link #setRandom(Random)} cannot contaminate each other's
     * sequences. Inheritable so threads spawned from a seeded thread (AI
     * evaluation helpers) continue the parent's stream instead of silently
     * falling back to an unseeded one. Single-threaded behavior is unchanged.
     */
    private static final ThreadLocal<CountingRandom> random = new InheritableThreadLocal<CountingRandom>() {
        @Override
        protected CountingRandom initialValue() {
            return new CountingRandom(new SecureRandom());
        }
    };

    /** Number of random draws made so far on the current thread's stream. Used by undo to detect that randomness was consumed. */
    public static long callCount() { return random.get().callCount(); }

    // Debug only (-Dforge.undo.debug=true): tally draws by thread and call site, to find who consumes randomness.
    private static final boolean TRACE = Boolean.getBoolean("forge.undo.debug");
    private static final Map<String, LongAdder> SITES = new ConcurrentHashMap<>();

    private static void trace() {
        final StringBuilder key = new StringBuilder(Thread.currentThread().getName()).append(" <- ");
        int shown = 0;
        for (final StackTraceElement e : new Throwable().getStackTrace()) {
            final String c = e.getClassName();
            if (c.startsWith("java.") || c.startsWith("jdk.") || c.startsWith("forge.util.MyRandom")) { continue; }
            key.append(c.substring(c.lastIndexOf('.') + 1)).append('.').append(e.getMethodName())
                    .append(':').append(e.getLineNumber()).append(shown < 3 ? " < " : "");
            if (++shown == 4) { break; }
        }
        SITES.computeIfAbsent(key.toString(), k -> new LongAdder()).increment();
    }

    /** Debug only: the {@code top} busiest draw sites since the last call, as text; clears the tally. */
    public static String drainTrace(final int top) {
        final StringBuilder sb = new StringBuilder();
        SITES.entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue().sum(), a.getValue().sum()))
                .limit(top)
                .forEach(e -> sb.append("\n    ").append(e.getValue().sum()).append("x ").append(e.getKey()));
        SITES.clear();
        return sb.toString();
    }

    /**
     * Randomness for picks that change how the game LOOKS and never what happens in it: which
     * printing and art a token shows. Deliberately not the game's stream. A seeded game used to
     * draw one number per art variant each time a token was made -- or merely looked up, as the
     * AI's creature-type census does for every token-making card it counts -- so a deck change
     * that only moved a token-making card in or out of what the AI counted shifted every later
     * shuffle and decision of the game.
     */
    private static final Random cosmeticRandom = new Random();

    public static boolean percentTrue(final int percent) {
        return percent > MyRandom.getRandom().nextInt(100);
    }

    /**
     * Gets the random: the bound {@link SimScope}'s stream when a simulated game is running on
     * this thread, else this thread's own stream (the GUI's case), which the threads it spawns
     * afterwards continue.
     *
     * @return the random for the current thread
     */
    public static Random getRandom() {
        return SimScope.random(() -> MyRandom.random.get());
    }

    /**
     * Sets the random provider. Used for deterministic simulation: re-seeds the bound scope's
     * stream when one is bound, else sets the calling thread's stream (and that of the threads it
     * spawns afterwards), wrapped in a {@code CountingRandom} so undo sees its draws (a
     * java.util.Random delegate gives the same values, draw for draw). A scope's stream is not
     * counted: undo is the GUI's and never runs in a scope-bound game.
     * @param random the random
     */
    public static void setRandom(Random random) {
        SimScope s = SimScope.current();
        if (s != null) {
            s.setRandom(random);
        } else {
            SimScope.requireUnboundAllowed("MyRandom.setRandom");
            MyRandom.random.set(random instanceof CountingRandom ? (CountingRandom) random : new CountingRandom(random));
        }
    }

    /** The stream for cosmetic picks only (see {@link #cosmeticRandom}): never the game's. */
    public static Random getCosmeticRandom() {
        return MyRandom.cosmeticRandom;
    }

    public static int[] splitIntoRandomGroups(final int value, final int numGroups) {
        int[] groups = new int[numGroups];
        Random r = getRandom();
        for (int i = 0; i < value; i++) {
            groups[r.nextInt(numGroups)]++;
        }
        return groups;
    }
}
