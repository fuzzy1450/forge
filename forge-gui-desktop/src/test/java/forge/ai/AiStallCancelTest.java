package forge.ai;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.testng.Assert;
import org.testng.annotations.Test;

import forge.game.Game;
import forge.game.card.Card;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import forge.util.GameAbandoned;
import forge.util.SimScope;

/**
 * The stall that poisoned the sim runner's JVM in pods-p58-s2 (MTG_DeckMaker, 2026-11-12), on the default AI.
 * <p>
 * A pump spell judged in the AI's first main phase asks, for every creature it could target, whether that creature
 * would attack: a whole attack plan, which predicts the opponent's next combat once per possible blocker. Each
 * prediction runs AiBlockController.makeGangBlocks, whose triple-block search walks every pair of blockers for each
 * attacker. It draws no random number and asks for no id inside that walk; makeGangBlocks' blocker filter draws once
 * per blocker (wouldLikeToRandomlyTrade), but only between attackers. With hundreds of blockers one attacker's walk
 * outlasts the runner's 15 s grace, and before AITimeoutTracker.check() answered a cancelled scope nothing in the
 * walk could see the cancel.
 * <p>
 * The board here is that shape: three hundred 2/2s against fifteen 9/9s, which no three blockers can kill, so the
 * walk never ends early. The AI is at 20 life, where the bears are blockers worth spending
 * (AiBlockController.assignBlockers' diff). The opponent is at 100,000, so no swing of the bears is lethal and the
 * attack plan takes its normal path into the block prediction. The decision runs on a game thread under a SimScope,
 * as GameRunner runs a game, with GameRunner's AI settings: no deadline, so only the cancel can end it. Once the
 * evaluation is inside the walk, the test cancels the scope. The evaluation must then end at the walk's own check(),
 * which only the fix can do. Without it, the GameAbandoned can only come from a later random draw, so the thrower
 * names the fix.
 */
public class AiStallCancelTest extends AITest {
    private static final String BLOCKS = "forge.ai.AiBlockController";

    @Test(timeOut = 300_000)
    public void aDecisionStalledInTheGangBlockSearchEndsAtTheSearchsCheckOnceItsGameIsCancelled() throws Exception {
        final SimScope scope = new SimScope(11L);
        final Set<Thread> evaluationsBefore = evaluationThreads();
        final AtomicReference<Throwable> ended = new AtomicReference<>();
        final AtomicReference<List<SpellAbility>> chose = new AtomicReference<>();
        final Thread game = new Thread(() -> {
            SimScope.enter(scope);                            // as GameRunner binds a game's thread: the evaluation adopts it
            try {
                final Game g = initAndCreateGame();
                g.AI_TIMEOUT = Integer.MAX_VALUE;            // GameRunner's: no AI deadline
                g.AI_CAN_USE_TIMEOUT = false;
                final Player ai = g.getPlayers().get(1);     // initAndCreateGame gives this seat its first main phase
                final Player opp = g.getPlayers().get(0);
                addCards("Grizzly Bears", 300, ai);
                addCard("Forest", ai);
                addCardToZone("Might of Old Krosa", ai, ZoneType.Hand);   // AILogic Main1IfAble: judged in main 1
                addCards("Ancient Brontodon", 15, opp);
                // No lethal swing: at 20 life the bears' unblocked damage would make doAssault attack with all of them
                // and skip the attack plan's block prediction. The AI's own life, which the search reads, stays 20.
                opp.setLife(100_000, null);
                for (final Card c : g.getCardsIn(ZoneType.Battlefield)) {
                    c.setSickness(false);                    // addCard leaves creatures summoning-sick
                }
                g.getAction().checkStateEffects(true);
                chose.set(((PlayerControllerAi) ai.getController()).getAi().chooseSpellAbilityToPlay());
            } catch (Throwable t) {
                ended.set(t);
            } finally {
                SimScope.exit();
            }
        }, "Game-stall-test");
        game.setDaemon(true);                                // a failed run's search must not keep the JVM up
        game.start();

        final Thread evaluation = awaitEvaluationWalkingGangBlocks(evaluationsBefore, game, chose, ended);
        scope.cancel();                                      // what GameRunner does at a game's time limit
        game.join(5_000);
        Assert.assertFalse(game.isAlive(), "the decision ended within 5 s of its game's cancel");
        Assert.assertTrue(ended.get() instanceof GameAbandoned, "abandoned, not decided: chose " + chose.get()
                + ", threw " + ended.get());
        final StackTraceElement[] thrower = ended.get().getStackTrace();
        Assert.assertTrue(thrower.length > 2, "a GameAbandoned with a stack");
        Assert.assertEquals(thrower[0].getClassName() + "." + thrower[0].getMethodName(),
                "forge.util.SimScope.checkCancelled", "thrown by the cancel point, not by a later draw");
        Assert.assertEquals(thrower[1].getClassName() + "." + thrower[1].getMethodName(),
                "forge.util.AITimeoutTracker.check", "reached through check()");
        Assert.assertEquals(thrower[2].getClassName() + "." + thrower[2].getMethodName(), BLOCKS + ".makeGangBlocks",
                "the check() of the search it stalled in");
        evaluation.join(5_000);
        Assert.assertFalse(evaluation.isAlive(), "its evaluation thread ended too");
    }

    private static Set<Thread> evaluationThreads() {
        final Set<Thread> out = new HashSet<>();
        for (final Thread t : Thread.getAllStackTraces().keySet()) {
            if (t.getName().startsWith("Game AI Eval")) {
                out.add(t);
            }
        }
        return out;
    }

    /**
     * The decision's evaluation thread once its innermost AiBlockController frame is makeGangBlocks itself: in the
     * double- or triple-block walk, not in the blocker filter's lambda, which draws. Fails if the decision ends first,
     * or after 120 s. Either way the board did not reach the search, and the test would prove nothing.
     */
    private static Thread awaitEvaluationWalkingGangBlocks(final Set<Thread> before, final Thread game,
            final AtomicReference<List<SpellAbility>> chose, final AtomicReference<Throwable> ended)
            throws InterruptedException {
        final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(120);
        while (System.nanoTime() < deadline) {
            for (final Map.Entry<Thread, StackTraceElement[]> e : Thread.getAllStackTraces().entrySet()) {
                if (!e.getKey().getName().startsWith("Game AI Eval") || before.contains(e.getKey())) {
                    continue;
                }
                for (final StackTraceElement f : e.getValue()) {
                    if (f.getClassName().equals(BLOCKS)) {
                        if (f.getMethodName().equals("makeGangBlocks")) {
                            return e.getKey();
                        }
                        break;                               // the innermost block frame is another method
                    }
                }
            }
            if (!game.isAlive()) {
                Assert.fail("the decision ended before its evaluation walked makeGangBlocks: chose " + chose.get()
                        + ", threw " + ended.get());
            }
            Thread.sleep(5);
        }
        Assert.fail("no evaluation walked makeGangBlocks within 120 s");
        return null;
    }
}
