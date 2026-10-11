package forge.ai.ability;

import forge.ai.AITest;
import forge.ai.AiPlayDecision;
import forge.ai.PlayerControllerAi;
import forge.game.Game;
import forge.game.card.Card;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import org.testng.AssertJUnit;
import org.testng.annotations.Test;

/**
 * Necropotence's script sets AILifeThreshold$ 1, so the AI paid 1 life a card down to 1 life. Each
 * payment now has to leave it at the stock floor and above the profile's danger threshold
 * (SpecialCardAi.Necropotence, SpecialCardAi.LifeFloor).
 */
public class NecropotenceAiTest extends AITest {

    @Test
    public void paysAboveTheFloor() {
        AssertJUnit.assertTrue(activates(setup(20, false)));
        // 6 - 1 = 5 is above the danger threshold of 4
        AssertJUnit.assertTrue(activates(setup(6, false)));
    }

    @Test
    public void stopsAtTheFloor() {
        // 5 - 1 = 4 is not above 4; the script's threshold of 1 paid on down to 1
        AssertJUnit.assertFalse(activates(setup(5, false)));
        AssertJUnit.assertFalse(activates(setup(2, false)));
    }

    @Test
    public void anOpposingBoardDoesNotMoveTheFloor() {
        // a refused payment is a turn without a card: the floor reads the AI's own life, not the next attack
        AssertJUnit.assertTrue(activates(setup(8, true)));
    }

    private Player setup(int life, boolean opposingAttacker) {
        Game game = initAndCreateGame();
        Player ai = game.getPlayers().get(1);
        Player opponent = game.getPlayers().get(0);
        addCard("Necropotence", ai);
        fillLibrary(ai, 10);
        if (opposingAttacker) {
            Card wurm = addCard("Craw Wurm", opponent);
            wurm.setSickness(false);
        }
        ai.setLife(life, null);
        moveToMain2(game, ai);
        return ai;
    }

    private boolean activates(Player ai) {
        Card necro = findCardWithName(ai.getGame(), "Necropotence");
        AssertJUnit.assertNotNull(necro);
        SpellAbility sa = null;
        for (SpellAbility s : necro.getSpellAbilities()) {
            if (s.isActivatedAbility()) {
                sa = s;
                break;
            }
        }
        AssertJUnit.assertNotNull("no activated ability on Necropotence", sa);
        sa.setActivatingPlayer(ai);
        return ((PlayerControllerAi) ai.getController()).getAi().canPlaySa(sa) == AiPlayDecision.WillPlay;
    }
}
