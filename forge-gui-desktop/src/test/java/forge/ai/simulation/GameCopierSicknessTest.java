package forge.ai.simulation;

import java.util.List;

import org.testng.AssertJUnit;
import org.testng.annotations.Test;

import forge.game.Game;
import forge.game.card.Card;
import forge.game.combat.CombatUtil;
import forge.game.phase.PhaseType;
import forge.game.player.Player;

/**
 * Summoning sickness in a rollout copy (MTG_DeckMaker docs/superpowers/specs/2026-11-18-flatmc-copier-sickness-design.md).
 * The copy must carry each permanent's raw flag: the copy recomputes its own keywords, so a creature whose only haste is
 * something that can leave (an Equipment, a lord) is sick in the copy once that has left, as it is in the real game.
 * Before the fix the copier copied {@code hasSickness()}, the flag masked by haste, and such a creature kept attacking in
 * every rollout that moved its haste away (the Lightning Greaves "wins" storms).
 */
public class GameCopierSicknessTest extends SimulationTest {
    private Game game;
    private Player ai;

    private void setUp(final boolean pod) {
        game = pod ? initAndCreateThreePlayerGame() : initAndCreateGame();
        ai = game.getPlayers().get(1);
        // a copy of a game whose first turn has not started fires a turn-phase event with no player
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, ai);
    }

    /** A creature that came under the AI's control this turn. */
    private static Card arrived(final Card c) {
        c.setSickness(true);
        return c;
    }

    /** A creature the AI has controlled since before this turn began. */
    private static Card old(final Card c) {
        c.setSickness(false);
        return c;
    }

    /** Lightning Greaves on this creature, as the turn finds them. */
    private Card greavesOn(final Card holder) {
        final Card greaves = addCard("Lightning Greaves", ai);
        greaves.attachToEntity(holder, null);
        game.getAction().checkStateEffects(true);
        return greaves;
    }

    /** Moves an Equipment in {@code g} as an Equip's resolution attaches it, then checks the state there. */
    private static void move(final Game g, final Card equipment, final Card to) {
        equipment.attachToEntity(to, null);
        g.getAction().checkStateEffects(true);
    }

    /** The storm's position: two Demon tokens that arrived this turn, the Greaves on the first. */
    private void greavesMoveScenario(final boolean pod) {
        setUp(pod);
        final Card first = arrived(addToken("b_5_5_demon_flying", ai));
        final Card second = arrived(addToken("b_5_5_demon_flying", ai));
        final Card greaves = greavesOn(first);
        AssertJUnit.assertTrue(first.isFirstTurnControlled());
        AssertJUnit.assertFalse("the Greaves' haste", first.hasSickness());
        AssertJUnit.assertTrue(CombatUtil.canAttack(first));
        AssertJUnit.assertFalse(CombatUtil.canAttack(second));

        final GameCopier copier = new GameCopier(game);
        final Game copy = copier.makeCopy();
        final Card cFirst = copier.find(first);
        final Card cSecond = copier.find(second);
        AssertJUnit.assertTrue("the copy keeps the flag the rules read", cFirst.isFirstTurnControlled());
        AssertJUnit.assertTrue("the copy's Greaves gives it haste", CombatUtil.canAttack(cFirst));
        AssertJUnit.assertFalse(CombatUtil.canAttack(cSecond));

        move(copy, copier.find(greaves), cSecond);
        AssertJUnit.assertTrue("without the Greaves it is a creature that arrived this turn", cFirst.hasSickness());
        AssertJUnit.assertFalse("so it cannot attack in the copy, as in the game", CombatUtil.canAttack(cFirst));
        AssertJUnit.assertTrue(CombatUtil.canAttack(cSecond));
        AssertJUnit.assertTrue("the original is untouched", CombatUtil.canAttack(first));
        AssertJUnit.assertSame(first, greaves.getEquipping());
    }

    @Test
    public void aCreatureHastedOnlyByGreavesIsStillSickInTheCopyOnceTheGreavesMoves() {
        greavesMoveScenario(false);
    }

    @Test
    public void aCreatureHastedOnlyByGreavesIsStillSickInAPodsCopy() {
        greavesMoveScenario(true);
    }

    /** A copy of a copy, as the storm solver makes inside a rollout: the flag carries through both. */
    @Test
    public void theFlagSurvivesACopyOfACopy() {
        setUp(false);
        final Card first = arrived(addToken("b_5_5_demon_flying", ai));
        final Card second = arrived(addToken("b_5_5_demon_flying", ai));
        final Card greaves = greavesOn(first);

        final GameCopier copier = new GameCopier(game);
        copier.makeCopy();
        final GameCopier copier2 = new GameCopier(copier.getCopiedGame());
        final Game copy2 = copier2.makeCopy();
        final Card c2First = copier2.find(copier.find(first));
        final Card c2Second = copier2.find(copier.find(second));
        AssertJUnit.assertTrue(c2First.isFirstTurnControlled());
        AssertJUnit.assertTrue(CombatUtil.canAttack(c2First));

        move(copy2, copier2.find(copier.find(greaves)), c2Second);
        AssertJUnit.assertFalse(CombatUtil.canAttack(c2First));
        AssertJUnit.assertTrue(CombatUtil.canAttack(c2Second));
    }

    /** Printed haste is the creature's own: nothing in the copy can take it away by moving an Equipment. */
    @Test
    public void aPrintedHasteArrivalStillAttacksInTheCopy() {
        setUp(false);
        final Card goblin = arrived(addCard("Raging Goblin", ai));
        final Card giant = old(addCard("Hill Giant", ai));
        final Card other = old(addCard("Hill Giant", ai));
        final Card greaves = greavesOn(giant);

        final GameCopier copier = new GameCopier(game);
        final Game copy = copier.makeCopy();
        final Card cGoblin = copier.find(goblin);
        AssertJUnit.assertTrue(CombatUtil.canAttack(cGoblin));
        move(copy, copier.find(greaves), copier.find(other));
        AssertJUnit.assertTrue(CombatUtil.canAttack(cGoblin));
    }

    /** Haste given the way a pump or a steal gives it ("it gains haste"): the copy keeps it, so the creature attacks. */
    @Test
    public void aPumpsHasteSurvivesTheCopy() {
        setUp(false);
        final Card giant = arrived(addCard("Hill Giant", ai));
        giant.addChangedCardKeywords(List.of("Haste"), null, false, game.getNextTimestamp(), null);
        game.getAction().checkStateEffects(true);
        AssertJUnit.assertTrue(CombatUtil.canAttack(giant));

        final GameCopier copier = new GameCopier(game);
        copier.makeCopy();
        AssertJUnit.assertTrue(CombatUtil.canAttack(copier.find(giant)));
    }

    /** Haste from another permanent's static that is not an Equipment: it leaves with that permanent, in the copy too. */
    @Test
    public void aLordsHasteLeavesWithTheLord() {
        setUp(false);
        final Card giant = arrived(addCard("Hill Giant", ai));
        final Card other = arrived(addCard("Hill Giant", ai));
        final Card fervor = addCard("Fervor", ai);
        game.getAction().checkStateEffects(true);
        AssertJUnit.assertTrue(CombatUtil.canAttack(giant));
        AssertJUnit.assertTrue(CombatUtil.canAttack(other));

        final GameCopier copier = new GameCopier(game);
        final Game copy = copier.makeCopy();
        final Card cGiant = copier.find(giant);
        final Card cOther = copier.find(other);
        AssertJUnit.assertTrue(CombatUtil.canAttack(cGiant));
        AssertJUnit.assertTrue(CombatUtil.canAttack(cOther));

        copy.getAction().moveToGraveyard(copier.find(fervor), null);
        copy.getAction().checkStateEffects(true);
        AssertJUnit.assertFalse(CombatUtil.canAttack(cGiant));
        AssertJUnit.assertFalse(CombatUtil.canAttack(cOther));
        AssertJUnit.assertTrue("the original keeps its Fervor", CombatUtil.canAttack(giant));
    }

    /** A creature there since the turn began is not sick in the copy, with the Greaves or without. */
    @Test
    public void anOldCreatureIsNotSickInTheCopy() {
        setUp(false);
        final Card giant = old(addCard("Hill Giant", ai));
        final Card fresh = arrived(addCard("Hill Giant", ai));
        final Card greaves = greavesOn(giant);

        final GameCopier copier = new GameCopier(game);
        final Game copy = copier.makeCopy();
        final Card cGiant = copier.find(giant);
        AssertJUnit.assertFalse(cGiant.isFirstTurnControlled());
        AssertJUnit.assertTrue(CombatUtil.canAttack(cGiant));

        move(copy, copier.find(greaves), copier.find(fresh));
        AssertJUnit.assertTrue(CombatUtil.canAttack(cGiant));
        AssertJUnit.assertTrue(CombatUtil.canAttack(copier.find(fresh)));
    }

    /** The card property Siren's Call and its kind read (firstTurnControlled) says on the copy what it says in the game. */
    @Test
    public void theFirstTurnControlledPropertyReadsAsInTheGame() {
        setUp(false);
        final Card demon = arrived(addToken("b_5_5_demon_flying", ai));
        greavesOn(demon);
        AssertJUnit.assertTrue(demon.isValid("Creature.firstTurnControlled", ai, demon, null));

        final GameCopier copier = new GameCopier(game);
        copier.makeCopy();
        final Card cDemon = copier.find(demon);
        AssertJUnit.assertTrue(cDemon.isValid("Creature.firstTurnControlled", cDemon.getController(), cDemon, null));
    }
}
