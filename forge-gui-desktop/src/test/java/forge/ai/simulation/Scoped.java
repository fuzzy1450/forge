package forge.ai.simulation;

import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicReference;

import forge.util.SimScope;

/**
 * Runs a test's body as a served game runs: on a fresh daemon thread bound to a {@link SimScope}, while the test's own
 * thread waits for it (as GameSimulatorScopeTest does). The thread's name carries the "Game" prefix that
 * ThreadUtil.isGameThread() reads, and a fresh thread has no scope left over from another test and no AI deadline armed.
 */
public final class Scoped {
    /** How long the test waits for a body before it fails. */
    private static final long JOIN_MS = 120_000;

    private Scoped() {
    }

    /**
     * What {@code body} returns, run under {@code scope}. What it throws is rethrown: an Error or a RuntimeException as
     * it is, a checked exception wrapped in a RuntimeException.
     */
    public static <T> T under(final SimScope scope, final Callable<T> body) {
        final AtomicReference<T> out = new AtomicReference<>();
        final Throwable thrown = thrownBy(scope, () -> {
            out.set(body.call());
            return null;
        });
        if (thrown instanceof Error e) {
            throw e;
        }
        if (thrown instanceof RuntimeException e) {
            throw e;
        }
        if (thrown != null) {
            throw new RuntimeException(thrown);
        }
        return out.get();
    }

    /** What {@code body} throws, run under {@code scope}, or null when it returns. */
    public static Throwable thrownBy(final SimScope scope, final Callable<?> body) {
        // checked here: SimScope.enter would refuse a null on the body's thread, outside the catch, and the test would
        // read the body as having returned
        Objects.requireNonNull(scope, "scope");
        final AtomicReference<Throwable> thrown = new AtomicReference<>();
        final Thread t = new Thread(() -> {
            SimScope.enter(scope);
            try {
                body.call();
            } catch (final Throwable e) {
                thrown.set(e);
            } finally {
                SimScope.exit();
            }
        }, "Game-sim-scoped-test");
        t.setDaemon(true);
        t.start();
        try {
            t.join(JOIN_MS);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted while waiting for the scoped body", e);
        }
        if (t.isAlive()) {
            scope.cancel(); // the body abandons its game at its next draw or id instead of running on beside later tests
            throw new AssertionError("the scoped body did not finish within " + JOIN_MS / 1000 + " s");
        }
        return thrown.get();
    }
}
