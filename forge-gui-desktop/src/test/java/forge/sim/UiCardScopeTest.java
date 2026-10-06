package forge.sim;

import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import org.testng.Assert;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import forge.game.card.Card;
import forge.item.PaperCard;
import forge.model.FModel;
import forge.util.SimScope;

/** Card.getCardForUi's cache: CardView builds the merged-card view of a mutated permanent through it, and a miss
 *  builds a whole Card -- its spell, abilities, triggers, statics and replacement effects, each taking an id from the
 *  scope bound on the thread. With one process-wide map only the first game in the JVM to view a card paid those ids,
 *  so a later game's ids differed from a fresh JVM's first game. A simulated game keeps its own map, so every game
 *  pays alike; unbound, the GUI keeps the static map. */
public class UiCardScopeTest {

    @BeforeClass
    public void boot() {
        GameRunner.boot();                    // the card database
    }

    @AfterClass
    public void relaxStrict() {
        SimScope.setStrict(false);            // boot() arms strict mode
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
        }, "Game-sim-uicard-test");
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

    /** Asks for the UI card of {@code pc}, then takes the bound scope's next SpellAbility id. */
    private static int nextAbilityIdAfterUiCard(PaperCard pc) {
        Card.getCardForUi(pc);
        return SimScope.current().nextId(SimScope.Counter.SPELL_ABILITY);
    }

    @Test(timeOut = 60_000)
    public void everyScopePaysTheSameIdsForAUiCard() throws Exception {
        PaperCard elves = FModel.getMagicDb().getCommonCards().getCard("Llanowar Elves");
        int first = under(new SimScope(1L), () -> nextAbilityIdAfterUiCard(elves));
        int second = under(new SimScope(2L), () -> nextAbilityIdAfterUiCard(elves));
        Assert.assertTrue(first > 1, "building the card took SpellAbility ids from the first scope: next id " + first);
        Assert.assertEquals(second, first, "the second scope built the card again and paid the same ids: its map started"
                + " empty, as a fresh JVM's first game's does");
    }

    @Test(timeOut = 60_000)
    public void unboundTheGuiKeepsOneCardPerPrinting() {
        SimScope.setStrict(false);            // the GUI never runs strict
        PaperCard ring = FModel.getMagicDb().getCommonCards().getCard("Sol Ring");
        Assert.assertSame(Card.getCardForUi(ring), Card.getCardForUi(ring), "unbound: the static map, as before");
    }
}
