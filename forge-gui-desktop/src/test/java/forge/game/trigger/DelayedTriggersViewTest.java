package forge.game.trigger;

import java.util.List;

import org.testng.AssertJUnit;
import org.testng.annotations.Test;

import forge.ai.AITest;
import forge.game.Game;
import forge.game.card.Card;
import forge.game.player.Player;

/**
 * {@link TriggerHandler#getDelayedTriggersView()}: the delayed triggers waiting to run, as the AI's self-kill guard
 * reads them (forge.ai.simulation.DelayedLoss, the "pay or lose" debts the AI owes). A view of the handler's own list,
 * so it follows every registration and clearing, and read-only, so a reader can change nothing.
 */
public class DelayedTriggersViewTest extends AITest {

    private static final String UPKEEP = "Mode$ Phase | Phase$ Upkeep | ValidPlayer$ You | TriggerDescription$ test";

    @Test
    public void theViewListsTheDelayedTriggersAndFollowsTheHandler() {
        final Game game = initAndCreateGame();
        final Player p = game.getPlayers().get(1);
        final TriggerHandler handler = game.getTriggerHandler();
        final List<Trigger> view = handler.getDelayedTriggersView();
        AssertJUnit.assertTrue("a new game has no delayed trigger", view.isEmpty());

        final Card host = addCard("Island", p);
        final Trigger first = TriggerHandler.parseTrigger(UPKEEP, host, false);
        final Trigger second = TriggerHandler.parseTrigger(UPKEEP, host, false);
        handler.registerDelayedTrigger(first);
        handler.registerThisTurnDelayedTrigger(second);
        AssertJUnit.assertEquals("both registrations, in their order", List.of(first, second), view);
        AssertJUnit.assertEquals("a second view reads the same list", view, handler.getDelayedTriggersView());

        handler.clearThisTurnDelayedTrigger();
        AssertJUnit.assertEquals("this turn's trigger is gone from the view", List.of(first), view);
        handler.clearDelayedTrigger();
        AssertJUnit.assertTrue("and the view follows a clearing", view.isEmpty());
    }

    @Test
    public void theViewRefusesWrites() {
        final Game game = initAndCreateGame();
        final Player p = game.getPlayers().get(1);
        final TriggerHandler handler = game.getTriggerHandler();
        final Trigger t = TriggerHandler.parseTrigger(UPKEEP, addCard("Island", p), false);
        handler.registerDelayedTrigger(t);
        final List<Trigger> view = handler.getDelayedTriggersView();
        for (final Runnable write : List.<Runnable>of(() -> view.add(t), () -> view.remove(t), view::clear,
                () -> view.set(0, t))) {
            try {
                write.run();
                AssertJUnit.fail("the view must refuse a write");
            } catch (final UnsupportedOperationException expected) {
                // read-only
            }
        }
        AssertJUnit.assertEquals("the handler's list is untouched", List.of(t), handler.getDelayedTriggersView());
    }
}
