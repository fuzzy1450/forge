package forge.ai.ability;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertTrue;

import org.testng.annotations.Test;

import forge.ai.AITest;
import forge.game.Game;
import forge.game.ability.AbilityFactory;
import forge.game.card.Card;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.player.PlayerActionConfirmMode;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;

/**
 * Sail into the West's wheel ("each player may discard their hand and draw seven cards"): the fork's
 * SpecialCardAi.SailIntoTheWest prices the cast on its caster taking the wheel, with up to four other cards in hand.
 * Upstream's MayDecisions.discardHandForNew (852e7a4f741, #12972) keeps a hand of four cheap cards, so the caster
 * would decline the wheel it paid for. The caster takes it; every other player keeps upstream's judgment.
 */
public class DiscardAiSailWheelTest extends AITest {

    @Test
    public void theCasterTakesTheWheelItPaidFor() {
        final Game game = initAndCreateGame();
        final Player ai = game.getPlayers().get(1);
        final Player opp = game.getPlayers().get(0);
        game.getPhaseHandler().devModeSet(PhaseType.END_OF_TURN, opp);
        for (final Player p : game.getPlayers()) {
            addCards("Forest", 3, p);
            for (int i = 0; i < 4; i++) {
                addCardToZone("Grizzly Bears", p, ZoneType.Hand);
            }
        }
        game.getAction().checkStateEffects(true);

        final Card sail = createCard("Sail into the West", ai);
        final SpellAbility wheel = AbilityFactory.getAbility(sail, "DBWheel");
        assertNotNull(wheel);
        wheel.setActivatingPlayer(ai);

        final boolean oppJudgment = MayDecisions.discardHandForNew(opp, wheel);
        assertFalse(MayDecisions.discardHandForNew(ai, wheel),
                "the board is the one in question: upstream's judgment keeps four cheap cards");
        assertTrue(new DiscardAi().confirmAction(ai, wheel, PlayerActionConfirmMode.Random, "", null),
                "the caster discards its four cheap cards and draws seven, as its cast was priced");
        assertEquals(new DiscardAi().confirmAction(opp, wheel, PlayerActionConfirmMode.Random, "", null), oppJudgment,
                "an opponent keeps upstream's judgment");
    }
}
