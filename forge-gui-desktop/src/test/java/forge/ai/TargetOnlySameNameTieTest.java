package forge.ai;

import org.testng.Assert;
import org.testng.annotations.Test;

import forge.game.Game;
import forge.game.card.Card;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;

/**
 * Declaration in Stone's choice of target (AILogic SameName, TargetOnlyAi.sameName) scores each opponent's best
 * creature name and takes the best with Collections.max, which keeps the first entry on a tie. Upstream keyed those
 * scores by Player in a HashMap, deterministically: a Player's hashCode is its id (GameEntity.hashCode), so between two
 * opponents with equally good creatures the one targeted was the one with the lowest player id, in every run. The fork
 * breaks such ties in turn order: its same scan, PumpAi.doSameNameLogic, had a LinkedHashMap for that since
 * 0f39ee161f8, and upstream's merge moved the scan to TargetOnlyAi with a HashMap again. Here twenty fresh three-player
 * games, each opponent with one Grizzly Bears: the AI targets its first opponent in turn order in every one (the
 * HashMap's choice, the lowest id, was the second).
 */
public class TargetOnlySameNameTieTest extends AITest {

    @Test
    public void tiedOpponentsBreakInTurnOrderInEveryGame() {
        for (int game = 0; game < 20; game++) {
            final Game g = initAndCreateThreePlayerGame();
            final Player ai = g.getPlayers().get(1);
            for (final Player opp : ai.getOpponents()) {
                addCard("Grizzly Bears", opp);
            }
            addCards("Plains", 2, ai);
            final Card declaration = addCardToZone("Declaration in Stone", ai, ZoneType.Hand);
            final SpellAbility sa = declaration.getFirstSpellAbility();
            sa.setActivatingPlayer(ai);

            Assert.assertTrue(SpellApiToAi.Converter.get(sa).canPlayWithSubs(ai, sa).willingToPlay(),
                    "game " + game + ": the AI casts Declaration in Stone at a Grizzly Bears");
            final Card target = sa.getTargets().getFirstTargetedCard();
            Assert.assertNotNull(target, "game " + game + ": a target");
            Assert.assertSame(target.getController(), ai.getOpponents().iterator().next(),
                    "game " + game + ": the tie between the two opponents' Grizzly Bears goes to the first in turn order");
        }
    }
}
