package forge.ai;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.testng.Assert;
import org.testng.annotations.Test;

import forge.game.Game;
import forge.game.ability.AbilityKey;
import forge.game.card.Card;
import forge.game.card.CounterEnumType;
import forge.game.player.Player;
import forge.game.trigger.TriggerType;
import forge.util.GameAbandoned;
import forge.util.SimScope;

/**
 * The stall that still poisoned the sim runner's JVM in N's farm games 3499239 and 3522652 (MTG_DeckMaker,
 * 2026-11-12) once AITimeoutTracker.check() was a cancel point. It is no AI decision at all: Mossborn Hydra's landfall
 * doubles its +1/+1 counters, and Card.addCounterInternal runs the CounterAdded trigger once per counter it adds, so
 * after k doublings one resolution makes about 2^(k-1) trigger runs. None of them draws a random number or takes an id,
 * so nothing in that loop could see the runner's cancel before TriggerHandler.runTrigger became a cancel point.
 */
public class CounterTriggerStallCancelTest extends AITest {

    @Test
    public void aTriggerRunUnderAnAbandonedGameUnwinds() {
        final Game game = initAndCreateGame();
        final Card bear = addCard("Grizzly Bears", game.getPlayers().get(1));
        final SimScope scope = new SimScope(13L);
        SimScope.enter(scope);
        try {
            game.getTriggerHandler().runTrigger(TriggerType.CounterAdded, AbilityKey.mapFromCard(bear), false);
            scope.cancel();                                  // what GameRunner does at a game's time limit
            try {
                game.getTriggerHandler().runTrigger(TriggerType.CounterAdded, AbilityKey.mapFromCard(bear), false);
                Assert.fail("a trigger run under an abandoned game must unwind");
            } catch (GameAbandoned e) {
                final StackTraceElement top = e.getStackTrace()[0];
                Assert.assertEquals(top.getClassName() + "." + top.getMethodName(), "forge.util.SimScope.checkCancelled",
                        "thrown by the cancel point, not by a draw or an id");
            }
        } finally {
            SimScope.exit();
        }
    }

    /**
     * The loop itself, on a game thread under a scope as GameRunner runs a game: one counter addition of 2^28, minutes
     * of trigger runs. Once the thread is inside it, the test cancels the scope; the loop must end at its next trigger
     * run. Without the cancel point it runs on, and nothing else in it could throw.
     */
    @Test(timeOut = 300_000)
    public void aCounterLoopUnderAnAbandonedGameEndsAtItsNextTriggerRun() throws Exception {
        final SimScope scope = new SimScope(17L);
        final AtomicReference<Throwable> ended = new AtomicReference<>();
        final AtomicReference<String> finished = new AtomicReference<>();
        final Thread game = new Thread(() -> {
            SimScope.enter(scope);
            try {
                final Game g = initAndCreateGame();
                final Player p = g.getPlayers().get(1);
                final Card creature = addCard("Grizzly Bears", p);  // any creature: the loop is addCounterInternal's own
                final Map<AbilityKey, Object> params = AbilityKey.newMap();
                creature.addCounterInternal(CounterEnumType.P1P1, 1 << 28, p, true, null, params);
                finished.set("the loop ran to its end");
            } catch (Throwable t) {
                ended.set(t);
            } finally {
                SimScope.exit();
            }
        }, "Game-stall-test");
        game.setDaemon(true);                                // a failed run's loop must not keep the JVM up
        game.start();

        awaitInLoop(game, finished, ended);
        scope.cancel();
        game.join(5_000);
        Assert.assertFalse(game.isAlive(), "the loop ended within 5 s of its game's cancel");
        Assert.assertTrue(ended.get() instanceof GameAbandoned, "abandoned: threw " + ended.get() + ", " + finished.get());
        final StackTraceElement[] thrower = ended.get().getStackTrace();
        Assert.assertTrue(thrower.length > 1, "a GameAbandoned with a stack");
        Assert.assertEquals(thrower[0].getClassName() + "." + thrower[0].getMethodName(),
                "forge.util.SimScope.checkCancelled", "thrown by the cancel point");
        Assert.assertEquals(thrower[1].getClassName() + "." + thrower[1].getMethodName(),
                "forge.game.trigger.TriggerHandler.runTrigger", "the one at the top of runTrigger");
    }

    /** Waits until `game` is in addCounterInternal's loop, a trigger run under it on its stack; fails if the thread
     *  ends first or after 120 s, when the test would prove nothing. */
    private static void awaitInLoop(final Thread game, final AtomicReference<String> finished,
                                    final AtomicReference<Throwable> ended) throws InterruptedException {
        final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(120);
        while (System.nanoTime() < deadline) {
            final List<String> frames = new ArrayList<>();
            for (final StackTraceElement f : game.getStackTrace()) {
                frames.add(f.getClassName() + "." + f.getMethodName());
            }
            if (frames.contains("forge.game.card.Card.addCounterInternal")
                    && frames.contains("forge.game.trigger.TriggerHandler.runTrigger")) {
                return;
            }
            if (!game.isAlive()) {
                Assert.fail("the thread ended before the loop: " + finished.get() + ", threw " + ended.get());
            }
            Thread.sleep(5);
        }
        Assert.fail("the thread never reached addCounterInternal's loop within 120 s");
    }
}
