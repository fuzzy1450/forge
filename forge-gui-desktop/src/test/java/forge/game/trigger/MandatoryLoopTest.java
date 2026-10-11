package forge.game.trigger;

import forge.ai.AITest;
import forge.game.Game;
import forge.game.GameEndReason;
import forge.game.ability.AbilityKey;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.zone.ZoneType;
import org.testng.AssertJUnit;
import org.testng.annotations.Test;

public class MandatoryLoopTest extends AITest {

    @Test
    public void endlessMandatoryTriggersDrawTheGame() {
        Game game = initAndCreateGame();
        Player p = game.getPlayers().get(0);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, p);
        addCard("Gremlin Tamer", p);
        addCard("Secret Arcade", p);
        addCard("Night of Souls' Betrayal", p);
        game.getAction().checkStateEffects(true);

        game.getAction().moveToPlay(addCardToZone("Sterling Grove", p, ZoneType.Hand), null, AbilityKey.newMap());
        for (int i = 0; i < 1000 && !game.isGameOver(); i++) {
            game.getPhaseHandler().mainLoopStep();
        }

        AssertJUnit.assertTrue(game.isGameOver());
        AssertJUnit.assertEquals(GameEndReason.Draw, game.getOutcome().getWinCondition());
    }

    // Bloodthirsty Conqueror and Marauding Blight-Priest drain a player who can't lose (Darksteel Angel) for ever:
    // each mandatory trigger feeds the other, and every loss of life used to restart the repeat count
    // (MTG_DeckMaker stored game 939583, engine 2.0.15+2a2cc6ebb77, 1200 s Timeout at turn 29)
    @Test
    public void endlessLifeDrainAgainstAPlayerWhoCantLoseDrawsTheGame() {
        Game game = initAndCreateGame();
        Player p = game.getPlayers().get(0);
        Player opp = game.getPlayers().get(1);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, p);
        addCard("Bloodthirsty Conqueror", p);
        addCard("Marauding Blight-Priest", p);
        addCard("Darksteel Angel", opp);
        opp.setLife(3, null);
        game.getAction().checkStateEffects(true);

        opp.loseLife(1, false, false, null);
        for (int i = 0; i < 5000 && !game.isGameOver(); i++) {
            game.getPhaseHandler().mainLoopStep();
        }

        AssertJUnit.assertTrue(game.isGameOver());
        AssertJUnit.assertEquals(GameEndReason.Draw, game.getOutcome().getWinCondition());
        AssertJUnit.assertTrue(opp.getLife() <= 0);
    }

    // the same chain against a player who can lose runs past the repeat limit and still ends by that player's death
    @Test
    public void lifeDrainChainStillEndsByDeath() {
        Game game = initAndCreateGame();
        Player p = game.getPlayers().get(0);
        Player opp = game.getPlayers().get(1);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, p);
        addCard("Bloodthirsty Conqueror", p);
        addCard("Marauding Blight-Priest", p);
        opp.setLife(80, null);
        game.getAction().checkStateEffects(true);

        opp.loseLife(1, false, false, null);
        for (int i = 0; i < 5000 && !game.isGameOver(); i++) {
            game.getPhaseHandler().mainLoopStep();
        }

        AssertJUnit.assertTrue(game.isGameOver());
        AssertJUnit.assertEquals(GameEndReason.AllOpponentsLost, game.getOutcome().getWinCondition());
        AssertJUnit.assertTrue(opp.hasLost());
    }
}
