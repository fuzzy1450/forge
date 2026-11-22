package forge.ai;

import org.testng.AssertJUnit;
import org.testng.annotations.Test;

import forge.ai.ability.DrawAi;
import forge.game.Game;
import forge.game.ability.ApiType;
import forge.game.card.Card;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.trigger.Trigger;
import forge.game.zone.ZoneType;

/**
 * Tataru Taru's tempo floor in DrawAi.chkDrawback after the merge of 2026-11-22 (RU-32's M08). Upstream's targetAI
 * flag became "a trigger, not an optional one" (d1619928091); the fork's gate kept its own off-resolution test, "not
 * a trigger of a host in play", as written. The AI's own first main phase, two Plains and Mind Stone in hand, so a
 * cast of Tataru ({1}{W}) would take the mana Mind Stone needs: the symmetric draw (the entering trigger's opponent
 * draw, OptionalDecider$ Opponent) is refused off resolution, Tataru in hand, and approved on resolution, the trigger
 * of a Tataru on the battlefield.
 */
public class TataruTaruDrawGateTest extends AITest {

    /** Tataru's entering trigger's opponent draw (DBDraw, the sub of its own draw), the AI its activator. */
    private static SpellAbility opponentDrawOf(final Card tataru, final Player ai) {
        for (final Trigger t : tataru.getTriggers()) {
            if (!"ChangesZone".equals(t.getParam("Mode"))) {
                continue;
            }
            final SpellAbility root = t.ensureAbility();
            root.setActivatingPlayer(ai);
            final SpellAbility sub = root.getSubAbility();
            sub.setActivatingPlayer(ai);
            AssertJUnit.assertEquals(ApiType.Draw, sub.getApi());
            AssertJUnit.assertTrue("the draw is a trigger's", sub.isTrigger());
            return sub;
        }
        throw new AssertionError("Tataru Taru has no entering trigger");
    }

    private Player board(final Game game) {
        final Player ai = game.getPlayers().get(1);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, ai);
        addCards("Plains", 2, ai);
        addCardToZone("Mind Stone", ai, ZoneType.Hand);
        fillLibrary(ai, 10);
        fillLibrary(game.getPlayers().get(0), 10);
        return ai;
    }

    @Test
    public void offResolutionTheSymmetricDrawIsRefused() {
        final Game game = initAndCreateGame();
        final Player ai = board(game);
        final Card tataru = addCardToZone("Tataru Taru", ai, ZoneType.Hand);
        final SpellAbility draw = opponentDrawOf(tataru, ai);
        AssertJUnit.assertEquals(AiPlayDecision.TargetingFailed, new DrawAi().chkDrawback(ai, draw).decision());
    }

    @Test
    public void theTriggerOfATataruInPlayIsApproved() {
        final Game game = initAndCreateGame();
        final Player ai = board(game);
        final Card tataru = addCard("Tataru Taru", ai);
        AssertJUnit.assertTrue(tataru.isInPlay());
        final SpellAbility draw = opponentDrawOf(tataru, ai);
        AssertJUnit.assertEquals(AiPlayDecision.WillPlay, new DrawAi().chkDrawback(ai, draw).decision());
    }
}
