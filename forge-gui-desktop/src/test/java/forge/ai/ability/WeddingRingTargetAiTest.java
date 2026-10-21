package forge.ai.ability;

import forge.ai.AITest;
import forge.ai.AiController;
import forge.ai.PlayerControllerAi;
import forge.game.Game;
import forge.game.card.Card;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.trigger.Trigger;
import forge.game.trigger.TriggerType;
import forge.game.zone.ZoneType;
import forge.util.MyRandom;
import org.testng.AssertJUnit;
import org.testng.annotations.Test;

/**
 * Wedding Ring (dead-card batch 4, row 14, part 14A). Its enters trigger, "target opponent creates a token that's
 * a copy of it" (DB$ CopyPermanent | ValidTgts$ Opponent | Defined$ Self | Controller$ TargetedPlayer), reached
 * CopyPermanentAi.doTriggerNoCost, whose loop lists cards only, so even the mandatory trigger of a forced cast
 * answered TargetingFailed and PlayerControllerAi.prepareSingleSa never put it on the stack. The name gate's
 * mandatory branch targets an opponent: the floored pick (SpecialCardAi.WeddingRing's FLOOR H), else the
 * targetable opponent with the largest hand. Each test asks AiController.doTrigger with the trigger's own
 * ability, copied for its controller as the trigger handler does.
 */
public class WeddingRingTargetAiTest extends AITest {

    @Test
    public void theRealTriggerTargetsTheOpponent() {
        Game game = initAndCreateGame();
        Player ai = game.getPlayers().get(1);
        Player opp = game.getPlayers().get(0);
        library(ai, 30);
        library(opp, 30);
        hand(opp, 4);
        SpellAbility etb = etb(addCard("Wedding Ring", ai), ai);
        long before = MyRandom.callCount();
        AssertJUnit.assertTrue(brains(ai).doTrigger(etb, true));
        AssertJUnit.assertEquals("the target choice drew", before, MyRandom.callCount());
        AssertJUnit.assertEquals(opp, etb.getTargets().getFirstTargetedPlayer());
        AssertJUnit.assertEquals(1, etb.getTargets().size());
    }

    @Test
    public void aMandatoryTriggerStillTargetsAnOpponentTheFloorRefuses() {
        // an empty hand fails FLOOR H; the trigger is mandatory and the opponent a legal target
        Game game = initAndCreateGame();
        Player ai = game.getPlayers().get(1);
        Player opp = game.getPlayers().get(0);
        library(ai, 30);
        library(opp, 30);
        SpellAbility etb = etb(addCard("Wedding Ring", ai), ai);
        AssertJUnit.assertTrue(brains(ai).doTrigger(etb, true));
        AssertJUnit.assertEquals(opp, etb.getTargets().getFirstTargetedPlayer());
    }

    @Test
    public void theFlooredPickComesBeforeTheLargestHand() {
        // a pod: the largest hand sits on a library our 30 do not outlast, so the floored pick is the other
        Game game = initAndCreateThreePlayerGame();
        Player ai = game.getPlayers().get(1);
        Player big = game.getPlayers().get(0);
        Player floored = game.getPlayers().get(2);
        AssertJUnit.assertTrue(ai.isOpponentOf(big) && ai.isOpponentOf(floored));
        library(ai, 30);
        library(big, 35);
        hand(big, 6);
        library(floored, 20);
        hand(floored, 4);
        SpellAbility etb = etb(addCard("Wedding Ring", ai), ai);
        AssertJUnit.assertTrue(brains(ai).doTrigger(etb, true));
        AssertJUnit.assertEquals(floored, etb.getTargets().getFirstTargetedPlayer());
    }

    @Test
    public void withNoFlooredOpponentTheLargestHandIsTargeted() {
        // both hands are under MIN_OPP_HAND: the fallback takes the larger
        Game game = initAndCreateThreePlayerGame();
        Player ai = game.getPlayers().get(1);
        Player small = game.getPlayers().get(0);
        Player larger = game.getPlayers().get(2);
        library(ai, 30);
        library(small, 20);
        hand(small, 1);
        library(larger, 20);
        hand(larger, 2);
        SpellAbility etb = etb(addCard("Wedding Ring", ai), ai);
        AssertJUnit.assertTrue(brains(ai).doTrigger(etb, true));
        AssertJUnit.assertEquals(larger, etb.getTargets().getFirstTargetedPlayer());
    }

    // the enters trigger's ability, copied for the Ring's controller as the trigger handler does
    static SpellAbility etb(Card ring, Player ai) {
        for (Trigger t : ring.getTriggers()) {
            if (t.getMode() == TriggerType.ChangesZone) {
                SpellAbility sa = t.ensureAbility().copy(ai);
                sa.setTrigger(t);
                return sa;
            }
        }
        AssertJUnit.fail("no enters trigger on " + ring);
        return null;
    }

    static AiController brains(Player ai) {
        return ((PlayerControllerAi) ai.getController()).getAi();
    }

    private void library(Player p, int n) {
        for (int i = 0; i < n; i++) {
            addCardToZone("Grizzly Bears", p, ZoneType.Library);
        }
    }

    private void hand(Player p, int n) {
        for (int i = 0; i < n; i++) {
            addCardToZone("Hill Giant", p, ZoneType.Hand);
        }
    }
}
