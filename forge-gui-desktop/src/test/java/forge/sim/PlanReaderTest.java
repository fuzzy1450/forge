package forge.sim;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.testng.Assert;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import forge.ai.AiController;
import forge.ai.PlayerControllerAi;
import forge.game.player.Player;
import forge.sim.GameRunner.GameResult;
import forge.sim.GameRunner.GameSpec;
import forge.sim.GameRunner.SeatSpec;
import forge.util.SimScope;

public class PlanReaderTest {
    @BeforeClass
    public void boot() {
        GameRunner.boot();
    }

    @AfterClass
    public void relaxStrict() {
        SimScope.setStrict(false);
    }

    /** The field a keep question fills: nothing reads a deck plan before the first keep question of a game. */
    private static Object planHeldBy(Player p) throws ReflectiveOperationException {
        AiController brain = ((PlayerControllerAi) p.getController()).getAi();
        Field f = AiController.class.getDeclaredField("flatMcDeckPlan");
        f.setAccessible(true);
        return f.get(brain);
    }

    /**
     * What section 3.1 hangs on, written before anything reads a plan: no mirror has ever been played, in the store or in
     * a fork test, and the verb plays one per list. Each of the first four precons, on both seats: the game reaches the
     * hook, and each seat was asked to keep, which is what filled its plan. Failing this takes 3.1's fallback.
     */
    @Test(timeOut = 300_000)
    public void aMirrorOfARealListReachesTheHook() throws Exception {
        GameRunner runner = new GameRunner(1);
        List<Path> precons = Precons.commander();
        Assert.assertTrue(precons.size() >= 4, "Forge ships Commander precons; found " + precons);
        for (Path deck : precons.subList(0, 4)) {
            SeatSpec seat = new SeatSpec(deck, "Default", "default");
            AtomicInteger hooks = new AtomicInteger();
            AtomicReference<String> failed = new AtomicReference<>();
            List<Object> plans = new ArrayList<>();
            GameResult r = runner.play(new GameSpec(1L, 120, List.of(seat, seat)), g -> { }, g -> {
                hooks.incrementAndGet();
                try {
                    for (Player p : g.getRegisteredPlayers()) {
                        plans.add(planHeldBy(p));
                    }
                } catch (ReflectiveOperationException e) {
                    failed.set(e.toString());
                }
            });
            String say = deck.getFileName() + ": " + r;
            Assert.assertNull(failed.get(), say);
            Assert.assertEquals(hooks.get(), 1, "the hook ran once: " + say);
            Assert.assertEquals(plans.size(), 2, "two seats: " + say);
            Assert.assertNotNull(plans.get(0), "seat 0 was asked to keep: " + say);
            Assert.assertNotNull(plans.get(1), "seat 1 was asked to keep: " + say);
            Assert.assertEquals(r.error(), "game ended without finishing", "nothing was thrown on the way: " + say);
            Assert.assertEquals(r.violations(), 0L, say);
            Assert.assertFalse(runner.isPoisoned(), say);
        }
    }
}
