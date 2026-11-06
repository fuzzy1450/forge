package forge.ai;

import static org.testng.AssertJUnit.assertEquals;
import static org.testng.AssertJUnit.assertNotNull;
import static org.testng.AssertJUnit.assertNotSame;
import static org.testng.AssertJUnit.assertSame;
import static org.testng.AssertJUnit.assertTrue;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import org.testng.annotations.AfterMethod;
import org.testng.annotations.Test;

import forge.ai.simulation.DeckPlan;
import forge.ai.simulation.FlatMonteCarloStats;
import forge.game.Game;
import forge.game.player.Player;
import forge.game.zone.ZoneType;
import forge.util.SimScope;

/**
 * Upstream's deck plan (DeckPlan) keeps the plans it has read in a JVM-wide table and counts them in Flat Monte
 * Carlo's JVM-wide statistics. The merge keeps a game played under a SimScope (the sim harness: many games side by
 * side in one serve JVM) out of both: it reads its deck once, at the same point of its every run, in a table of its
 * own, so a seed plays the same game whichever game of its JVM ran first, and its plan is not counted.
 */
public class MergeDeckPlanScopeTest extends AITest {

    @AfterMethod
    public void leaveScope() {
        if (SimScope.current() != null) {
            SimScope.exit();
        }
    }

    private static <T> T under(final SimScope scope, final Supplier<T> body) throws Exception {
        final AtomicReference<T> out = new AtomicReference<>();
        final AtomicReference<Throwable> failed = new AtomicReference<>();
        final Thread t = new Thread(() -> {
            SimScope.enter(scope);
            try {
                out.set(body.get());
            } catch (Throwable e) {
                failed.set(e);
            } finally {
                SimScope.exit();
            }
        }, "Game-sim-deckplan-test");
        t.setDaemon(true);
        t.start();
        t.join(60_000);
        if (t.isAlive()) {
            throw new AssertionError("the body did not finish within 60 s");
        }
        if (failed.get() != null) {
            throw new AssertionError(failed.get());
        }
        return out.get();
    }

    /** A burn list: twelve or more spells that are mostly damage, so the plan is not the plain midrange one. */
    private static void burnDeck(final AITest t, final Player p) {
        for (int i = 0; i < 16; i++) {
            t.addCardToZone("Lightning Bolt", p, ZoneType.Library);
        }
    }

    @SuppressWarnings("unchecked")
    private static int plansCounted() throws Exception {
        final Field f = FlatMonteCarloStats.class.getDeclaredField("PLANS");
        f.setAccessible(true);
        int n = 0;
        synchronized (FlatMonteCarloStats.class) {
            for (final int[] c : ((Map<String, int[]>) f.get(null)).values()) {
                n += c[0];
            }
        }
        return n;
    }

    private static AiController brainOf(final Player p) {
        return ((PlayerControllerAi) p.getController()).getAi();
    }

    @Test(timeOut = 120_000)
    public void aGameUnderAScopeReadsItsDeckInATableOfItsOwn() throws Exception {
        final Game game = initAndCreateGame();
        final Player p = game.getPlayers().get(1);
        burnDeck(this, p);

        final DeckPlan first = DeckPlan.forSetting("auto", p);
        assertNotNull(first);
        assertSame("outside a scope the JVM's table remembers a plan", first, DeckPlan.forSetting("auto", p));

        final DeckPlan[] inA = under(new SimScope(1L),
                () -> new DeckPlan[] { DeckPlan.forSetting("auto", p), DeckPlan.forSetting("auto", p) });
        assertSame("a scope remembers its own plan", inA[0], inA[1]);
        assertNotSame("but does not take the JVM's, which would skip the ids of the deck's reading", first, inA[0]);

        final DeckPlan[] inB = under(new SimScope(2L), () -> new DeckPlan[] { DeckPlan.forSetting("auto", p) });
        assertNotSame("another scope reads the same list again", inA[0], inB[0]);
    }

    @Test(timeOut = 120_000)
    public void aGameUnderAScopeStaysOutOfTheStatisticsOfTheJvm() throws Exception {
        final Game game = initAndCreateGame();
        final Player scoped = game.getPlayers().get(1);
        final Player unscoped = game.getPlayers().get(0);
        burnDeck(this, scoped);
        burnDeck(this, unscoped);
        FlatMonteCarloStats.reset();

        final DeckPlan inScope = under(new SimScope(3L), () -> brainOf(scoped).deckPlan(scoped));
        assertNotNull(inScope);
        assertTrue("the burn list is a plan worth counting: " + inScope,
                inScope.archetype != DeckPlan.Archetype.MIDRANGE || !inScope.combos.isEmpty());
        assertEquals("a game under a scope is not counted", 0, plansCounted());

        brainOf(unscoped).deckPlan(unscoped);
        assertTrue("a game outside a scope is", plansCounted() > 0);
    }
}
