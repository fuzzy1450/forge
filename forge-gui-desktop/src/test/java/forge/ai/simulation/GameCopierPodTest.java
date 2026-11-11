package forge.ai.simulation;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertTrue;

import org.testng.annotations.Test;

import forge.game.Game;
import forge.game.card.Card;
import forge.game.player.Player;
import forge.game.zone.ZoneType;

/**
 * A copy of a pod in which a player has already lost (MTG_DeckMaker's tools/combo-ai/flat-mc/SPEC.md, sections 4.2
 * and 4.7). GameCopier builds a copy from every player the match registered, so it made the loser again as a player
 * still in the game, at the default starting life and with no cards: one more opponent in the score, and a copy
 * that went on when the last real opponent died. And a commander that left the game with its owner is still named
 * by another player's commander damage: copyCommandersToSnapshot skips a commander the copy does not hold, but the
 * map it was handed threw "Couldn't map" instead of answering nothing, and the whole copy failed.
 */
public class GameCopierPodTest extends SimulationTest {
    private Player a;
    private Player b;
    private Player c;

    /**
     * Three players, a on turn, each the others' opponent. c's commander, a Grizzly Bears, has dealt 5 commander damage
     * to a, and then c concedes: c leaves the game and its cards with it (CR 800.4a), while a's commander damage still
     * names the commander.
     */
    private Game podWithALoser() {
        final Game game = initAndCreateThreePlayerGame();
        a = game.getPlayers().get(1);
        b = game.getPlayers().get(0);
        c = game.getPlayers().get(2);
        final Card cmd = addCardToZone("Grizzly Bears", c, ZoneType.Battlefield);
        c.addCommander(cmd);
        a.addCommanderDamage(cmd, 5);
        c.concede();
        game.getAction().checkGameOverCondition(); // as ComboSignalsTest.surerKillNeedsASingleOpponent does
        assertFalse(game.isGameOver(), "a and b are still in the game");
        assertEquals(game.getPlayers().size(), 2, "in the game");
        assertEquals(game.getLostPlayers().size(), 1, "lost");
        assertNull(game.getCardState(cmd, null), "the commander left the game with c");
        assertTrue(a.getCommanderDamage().iterator().hasNext(), "and a's commander damage still names it");
        return game;
    }

    @Test
    public void aPodCopyLeavesTheEliminatedPlayerOutAndSkipsItsCommander() {
        final Game copy = new GameCopier(podWithALoser()).makeCopy(); // threw "Couldn't map" before the repair
        assertEquals(copy.getPlayers().size(), 2, "in the copy's game");
        assertEquals(copy.getLostPlayers().size(), 1, "lost in the copy");
        final Player cc = copy.getLostPlayers().get(0);
        assertEquals(cc.getId(), c.getId());
        assertNotNull(cc.getOutcome(), "the loser has an outcome in the copy");
        assertFalse(cc.getOutcome().hasWon(), "and it is a loss");
        assertFalse(copy.getPlayer(a.getId()).getCommanderDamage().iterator().hasNext(),
                "the commander the copy does not hold is skipped");
    }

    @Test
    public void theLastRealOpponentDyingEndsTheCopy() {
        final Game copy = new GameCopier(podWithALoser()).makeCopy();
        copy.getPlayer(b.getId()).setLife(0, null);
        copy.getAction().checkStateEffects(true);
        assertTrue(copy.isGameOver(), "no player who lost comes back to keep the copy going");
        assertTrue(copy.getPlayer(a.getId()).hasWon(), "a is the one left");
    }

    @Test
    public void aCopyOfTheCopyRepeatsTheRepair() {
        final Game copy = new GameCopier(podWithALoser()).makeCopy();
        final Game again = new GameCopier(copy).makeCopy();
        assertEquals(again.getPlayers().size(), 2, "in the game of the copy's copy");
        assertEquals(again.getLostPlayers().size(), 1, "lost in the copy's copy");
        assertEquals(again.getLostPlayers().get(0).getId(), c.getId());
        assertFalse(again.getLostPlayers().get(0).getOutcome().hasWon(), "still a loss");
    }
}
