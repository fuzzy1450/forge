package forge.ai.ability;

import static org.testng.AssertJUnit.assertEquals;
import static org.testng.AssertJUnit.assertFalse;
import static org.testng.AssertJUnit.assertNotNull;
import static org.testng.AssertJUnit.assertTrue;

import org.testng.annotations.Test;

import forge.ai.AITest;
import forge.ai.AiPlayDecision;
import forge.ai.SpellAbilityAi;
import forge.ai.SpellApiToAi;
import forge.game.Game;
import forge.game.GameEntityCounterTable;
import forge.game.ability.ApiType;
import forge.game.card.Card;
import forge.game.card.CounterEnumType;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.trigger.Trigger;
import forge.game.trigger.TriggerType;

/**
 * Two Enduring Scalelords feed each other: every +1/+1 counter one of them gets fires the other's "you may put a
 * +1/+1 counter on CARDNAME" trigger. An AI that always says yes never leaves that loop (the 2026-10-06
 * determinism battery's pod game P1-P4 seed 7000003: turn 58 reached in a minute, then 1200 s of ~100 trigger
 * resolutions a second). The AI must stop accepting once a Scalelord is big enough to matter no further.
 */
public class EnduringScalelordAiTest extends AITest {

    private static int enough(Player opponent) {
        return Math.max(40, 2 * opponent.getLife());
    }

    /** The loop through the real stack: without a stop this never returns, and the timeOut fails the test. */
    @Test(timeOut = 180_000)
    public void twoScalelordsStopGrowingOnceBigEnough() {
        Game game = initAndCreateGame();
        Player ai = game.getPlayers().get(1);
        Player opponent = game.getPlayers().get(0);
        Card first = addCard("Enduring Scalelord", ai);
        Card second = addCard("Enduring Scalelord", ai);
        Card bear = addCard("Runeclaw Bear", ai);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, ai);
        game.getAction().checkStateEffects(true);

        // One +1/+1 counter on another creature, put the way a resolving effect puts it: both Scalelords trigger.
        GameEntityCounterTable table = new GameEntityCounterTable();
        bear.addCounter(CounterEnumType.P1P1, 1, ai, table);
        table.replaceCounterEffect(game, null);
        game.getStack().addAllTriggeredAbilitiesToStack();
        assertFalse("the bear's counter must have triggered the Scalelords", game.getStack().isEmpty());

        playUntilStackClear(game);

        assertTrue(game.getStack().isEmpty());
        assertFalse(game.isGameOver());
        // They grow in lockstep, one counter each per round, and the first to reach the bound declines; the
        // other then catches up to the bound from one below and is declined in turn.
        assertEquals(enough(opponent), first.getNetPower());
        assertEquals(enough(opponent), second.getNetPower());
    }

    /** The decision itself, at the handler: yes below the bound, no at it, and a mandatory trigger is never declined. */
    @Test
    public void theOptionalCounterIsDeclinedOnlyFromTheBoundUp() {
        Game game = initAndCreateGame();
        Player ai = game.getPlayers().get(1);
        Player opponent = game.getPlayers().get(0);
        Card scalelord = addCard("Enduring Scalelord", ai);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, ai);
        game.getAction().checkStateEffects(true);

        SpellAbility trigger = null;
        for (Trigger t : scalelord.getTriggers()) {
            if (t.getMode() == TriggerType.CounterAddedOnce) {
                trigger = t.ensureAbility();
            }
        }
        assertNotNull("Enduring Scalelord's counter trigger", trigger);
        trigger.setActivatingPlayer(ai);   // as the trigger handler does before the controller is asked
        SpellAbilityAi logic = SpellApiToAi.Converter.get(ApiType.PutCounter);
        int enough = enough(opponent);
        int basePower = 4;

        scalelord.setCounters(CounterEnumType.P1P1, enough - basePower - 1);
        assertEquals(enough - 1, scalelord.getNetPower());
        assertEquals(AiPlayDecision.WillPlay, logic.doTriggerNoCostWithSubs(ai, trigger, false).decision());

        scalelord.setCounters(CounterEnumType.P1P1, enough - basePower);
        assertEquals(enough, scalelord.getNetPower());
        assertEquals(AiPlayDecision.CantPlayAi, logic.doTriggerNoCostWithSubs(ai, trigger, false).decision());
        assertTrue("a mandatory trigger is never declined", logic.doTriggerNoCostWithSubs(ai, trigger, true).willingToPlay());

        // The bound follows the opponents' life totals: a richer opponent keeps the counters coming.
        opponent.setLife(100, null);
        assertEquals(AiPlayDecision.WillPlay, logic.doTriggerNoCostWithSubs(ai, trigger, false).decision());
    }
}
