package forge.sim;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import org.testng.Assert;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.Test;

import forge.ai.simulation.GameSimulator;
import forge.util.SimScope;

/** GameSimulator's debug lines: every hybrid or full-sim decision constructs a GameSimulator, which starts a list,
 *  fills it while it scores the game and drops it again. With one process-wide list two games simulating at once
 *  appended to each other's list or nulled it under each other, so an add could throw inside the AI's evaluation.
 *  A bound scope keeps its own list; unbound, the static is the list, as before. */
public class GameSimulatorScopeTest {

    @AfterMethod
    public void relax() {
        if (SimScope.current() != null) {
            SimScope.exit();
        }
        SimScope.setStrict(false);
        GameSimulator.debugLines = null;      // unbound: the static, as a GameSimulator leaves it
    }

    private static <T> T under(SimScope scope, Supplier<T> body) throws Exception {
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
        }, "Game-sim-debuglines-test");
        t.setDaemon(true);
        t.start();
        t.join(30_000);
        if (t.isAlive()) {
            throw new AssertionError("the body did not finish within 30 s");
        }
        if (failed.get() != null) {
            throw new AssertionError(failed.get());
        }
        return out.get();
    }

    @Test(timeOut = 60_000)
    public void eachScopeKeepsItsOwnDebugLines() throws Exception {
        SimScope a = new SimScope(1L), b = new SimScope(2L);
        under(a, () -> {
            GameSimulator.setDebugLines(new ArrayList<>());   // what a GameSimulator does before it scores a game
            GameSimulator.debugPrint("a's line");
            return null;
        });
        Assert.assertNull(under(b, GameSimulator::getDebugLines), "b started no lines of its own: a's list is not b's");
        under(b, () -> {
            GameSimulator.debugPrint("b's line");               // nothing started under b: the line is not kept
            return null;
        });
        Assert.assertEquals(under(a, GameSimulator::getDebugLines), List.of("a's line"),
                "a's line is still there, and b's print did not land in a's list");
    }

    @Test
    public void unboundTheStaticIsTheList() {
        List<String> lines = new ArrayList<>();
        GameSimulator.debugLines = lines;
        GameSimulator.debugPrint("unbound");
        Assert.assertEquals(lines, List.of("unbound"), "unbound: debugPrint appends to the static list, as before");
    }
}
