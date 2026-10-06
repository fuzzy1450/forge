package forge.util;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

/**
 * One simulated game's share of the state Forge keeps in statics: its random stream, its id
 * counters, a cancelled flag and a scratch map. Bound to the game's thread with {@link #enter};
 * {@link MyRandom} and the id counters consult {@link #current()} and fall back to their statics
 * when no scope is bound, so the GUI, which never binds one, behaves exactly as before. Under
 * {@link #setStrict strict mode} an unbound access throws {@link UnscopedAccessError} instead of falling back.
 *
 * <p>Thread confinement: a scope is used by one game thread and, through {@link #adopting}, by a
 * child thread that runs while the game thread blocks on it, so nothing here is synchronized.
 */
public final class SimScope {
    public enum Counter { GAME, SPELL_ABILITY, STACK_INSTANCE, STATIC_ABILITY, TRIGGER, REPLACEMENT, COMBAT_VIEW }

    private static final ThreadLocal<SimScope> BOUND = new ThreadLocal<>();
    private static volatile boolean strict = false;

    private Random random;
    private final EnumMap<Counter, int[]> counters = new EnumMap<>(Counter.class);
    private final Map<Object, Object> scratch = new HashMap<>();
    private volatile boolean cancelled = false;

    /** Counters start where a fresh JVM's statics start: 0 for the pre-incremented {@code maxId}
     *  counters, -2 for {@code CombatView.nextId}. */
    public SimScope(long seed) {
        this.random = new Random(seed);
        for (Counter c : Counter.values()) {
            counters.put(c, new int[] { c == Counter.COMBAT_VIEW ? -2 : 0 });
        }
    }

    public Random random() {
        checkpoint();
        return random;
    }

    /** {@link MyRandom#setRandom} re-seeds a bound scope through this. */
    void setRandom(Random r) {
        this.random = r;
    }

    /** The next id of kind {@code c}, stepping exactly as the static it replaces: {@code ++maxId}
     *  for every kind but COMBAT_VIEW, which is {@code nextId--}. */
    public int nextId(Counter c) {
        checkpoint();
        int[] slot = counters.get(c);
        if (c == Counter.COMBAT_VIEW) {
            return slot[0]--;
        }
        return ++slot[0];
    }

    public void reset(Counter c, int value) {
        counters.get(c)[0] = value;
    }

    public void cancel() {
        cancelled = true;
    }

    public boolean isCancelled() {
        return cancelled;
    }

    @SuppressWarnings("unchecked")
    public <T> T scratch(Object key, Supplier<T> create) {
        return (T) scratch.computeIfAbsent(key, k -> create.get());
    }

    private void checkpoint() {
        if (cancelled) {
            throw new GameAbandoned();
        }
    }

    // ------------------------------------------------------------------ binding

    public static SimScope current() {
        return BOUND.get();
    }

    public static void enter(SimScope s) {
        if (s == null) {
            throw new IllegalArgumentException("SimScope.enter(null)");
        }
        if (BOUND.get() != null) {
            throw new IllegalStateException("thread " + Thread.currentThread().getName() + " already has a SimScope");
        }
        BOUND.set(s);
    }

    public static void exit() {
        if (BOUND.get() == null) {
            throw new IllegalStateException("thread " + Thread.currentThread().getName() + " has no SimScope to exit");
        }
        BOUND.remove();
    }

    public static void setStrict(boolean on) {
        strict = on;
    }

    public static boolean isStrict() {
        return strict;
    }

    /** Every unbound access strict mode has refused in this process, counted before the refusal is thrown: Forge's
     *  code can catch and swallow the {@link UnscopedAccessError} (AbilityFactory wraps it, a CompletableFuture
     *  carries it, a catch reads the failure as "no decision"), and the count still shows it. The runner records each
     *  game's delta; the battery fails a play with any. */
    public static final AtomicLong VIOLATIONS = new AtomicLong();

    /** Throws {@link UnscopedAccessError} under strict mode: the caller is about to touch a
     *  static that a simulation must never reach unscoped. An Error, so that Forge's many
     *  {@code catch (Exception)} sites cannot swallow the violation into a crippled game. */
    public static void requireUnboundAllowed(String seam) {
        if (strict) {
            VIOLATIONS.incrementAndGet();
            throw new UnscopedAccessError("SimScope: unbound access to " + seam + " on thread "
                    + Thread.currentThread().getName() + " with strict mode on");
        }
    }

    /**
     * The Error to rethrow from a failure that Forge's AI is about to read as "no decision", when this thread runs a
     * simulation (a scope bound here, or strict mode on): the first Error anywhere in {@code failure}'s cause chain,
     * through CompletionException and ExecutionException wrappers and the RuntimeException AbilityFactory wraps around
     * an Error raised while an ability is built. A strict-mode refusal or an abandoned game is never a decision. Null
     * when the chain holds no Error, and always null unbound and not strict -- the GUI and the unbound farm harness --
     * whose call sites keep the one-level checks they had.
     */
    public static Error simulationError(Throwable failure) {
        if (BOUND.get() == null && !strict) {
            return null;
        }
        Throwable t = failure;
        for (int hops = 0; t != null && hops < 16; hops++) {      // bounded: a cause chain can loop
            if (t instanceof Error e) {
                return e;
            }
            t = t.getCause();
        }
        return null;
    }

    // ------------------------------------------------------------------ the seams Forge's statics call

    public static Random random(Supplier<Random> unboundFallback) {
        SimScope s = BOUND.get();
        if (s != null) {
            return s.random();
        }
        requireUnboundAllowed("MyRandom.getRandom");
        return unboundFallback.get();
    }

    public static int nextId(Counter c, IntSupplier unboundFallback) {
        SimScope s = BOUND.get();
        if (s != null) {
            return s.nextId(c);
        }
        requireUnboundAllowed("nextId(" + c + ")");
        return unboundFallback.getAsInt();
    }

    public static void reset(Counter c, int value, Runnable unboundFallback) {
        SimScope s = BOUND.get();
        if (s != null) {
            s.reset(c, value);
            return;
        }
        requireUnboundAllowed("reset(" + c + ")");
        unboundFallback.run();
    }

    /** Explicit adoption at a thread hop: the returned task runs under the scope bound on the
     *  CALLING thread (read now), entering it on the executing thread and exiting in a finally.
     *  With no scope bound here, the task is returned as it is. */
    public static Runnable adopting(Runnable task) {
        SimScope s = BOUND.get();
        if (s == null) {
            return task;
        }
        return () -> {
            enter(s);
            try {
                task.run();
            } finally {
                exit();
            }
        };
    }
}
