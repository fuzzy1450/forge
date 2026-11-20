package forge.ai.simulation;

import java.util.List;

import org.testng.AssertJUnit;
import org.testng.annotations.Test;

import forge.game.Game;
import forge.game.card.Card;
import forge.game.combat.CombatUtil;
import forge.game.cost.CostAdjustment;
import forge.game.mana.ManaCostBeingPaid;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.spellability.AlternativeCost;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;

/**
 * When a card entered its zone, in a rollout copy (MTG_DeckMaker
 * docs/superpowers/specs/2026-11-20-flatmc-copier-turninzone-design.md). The copier adds each copied card to its zone
 * with Zone.add, which reads a fresh copy as arriving now and stamps it with the copy's turn: before the fix every
 * copied card outside the stack "entered this turn" in every rollout (Keldon Strike Team's haste for all, a Power-up
 * always discounted, a foretold card never castable from exile). The copy must carry the turn the card really entered.
 */
public class GameCopierTurnInZoneTest extends SimulationTest {
    private static final int NOW = 5;
    private static final int EARLIER = 2;

    private Game game;
    private Player ai;

    /** Main 1 of the AI's turn 5: every card added after this is stamped with turn 5, as one that entered now. */
    private void setUp() {
        game = initAndCreateGame();
        ai = game.getPlayers().get(1);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, ai, NOW);
    }

    /** A card that entered its zone on turn 2, as GameState puts a card there. */
    private static Card old(final Card c) {
        c.setTurnInZone(EARLIER);
        return c;
    }

    private static SpellAbility powerUpOf(final Card c, final Player activator) {
        for (final SpellAbility sa : c.getSpellAbilities()) {
            if (sa.isPowerUp()) {
                sa.setActivatingPlayer(activator);
                return sa;
            }
        }
        throw new IllegalStateException("no Power-up ability on " + c);
    }

    /** The mana a Power-up ability costs once CostAdjustment has applied its "entered this turn" discount. */
    private static int powerUpCost(final Card c, final Player activator) {
        final SpellAbility sa = powerUpOf(c, activator);
        final ManaCostBeingPaid cost = new ManaCostBeingPaid(sa.getPayCosts().getTotalMana());
        CostAdjustment.adjust(cost, sa, activator, null, true, false);
        return cost.getConvertedManaCost();
    }

    private static boolean offersForetoldCast(final Card c, final Player p) {
        for (final SpellAbility sa : c.getAllPossibleAbilities(p, false)) {
            if (sa.getAlternativeCost() == AlternativeCost.Foretold) {
                return true;
            }
        }
        return false;
    }

    /** Behold the Multiverse foretold on {@code turn}: face down in exile, with the foretold mark. */
    private Card foretold(final int turn) {
        final Card c = addCardToZone("Behold the Multiverse", ai, ZoneType.Exile);
        c.turnFaceDown(true);
        c.setForetold(true);
        c.setTurnInZone(turn);
        return c;
    }

    @Test
    public void aCardThatEnteredBeforeThisTurnHasNotEnteredThisTurnInTheCopy() {
        setUp();
        final List<Card> oldCards = List.of(
                old(addCard("Hill Giant", ai)), old(addToken("w_1_1_soldier", ai)),
                old(addCardToZone("Grizzly Bears", ai, ZoneType.Hand)),
                old(addCardToZone("Lightning Bolt", ai, ZoneType.Graveyard)),
                old(addCardToZone("Behold the Multiverse", ai, ZoneType.Exile)),
                old(addCardToZone("Hill Giant", ai, ZoneType.Library)),
                old(addCardToZone("Llanowar Elves", ai, ZoneType.Command)));
        final Card fresh = addCard("Hill Giant", ai);
        final GameCopier copier = new GameCopier(game);
        copier.makeCopy();
        for (final Card c : oldCards) {
            final Card copy = copier.find(c);
            AssertJUnit.assertEquals(c + " keeps its turn in the copy", EARLIER, copy.getTurnInZone());
            AssertJUnit.assertFalse(c + " did not enter this turn", copy.enteredThisTurn());
            AssertJUnit.assertEquals("the original is untouched", EARLIER, c.getTurnInZone());
        }
        AssertJUnit.assertTrue("a card that entered this turn still did", copier.find(fresh).enteredThisTurn());
        AssertJUnit.assertEquals(NOW, copier.find(fresh).getTurnInZone());
    }

    /** A copy of a copy, as the storm solver and the evaluator make inside a rollout: the turn carries through both. */
    @Test
    public void theTurnSurvivesACopyOfACopy() {
        setUp();
        final Card giant = old(addCard("Hill Giant", ai));
        final GameCopier copier = new GameCopier(game);
        copier.makeCopy();
        final GameCopier copier2 = new GameCopier(copier.getCopiedGame());
        copier2.makeCopy();
        final Card copy2 = copier2.find(copier.find(giant));
        AssertJUnit.assertEquals(EARLIER, copy2.getTurnInZone());
        AssertJUnit.assertFalse(copy2.enteredThisTurn());
    }

    /** The card property every "entered this turn" script reads (Forge of Heroes, Romana II, Oran-Rief, ...). */
    @Test
    public void thisTurnEnteredReadsAsInTheGame() {
        setUp();
        final Card oldGiant = old(addCard("Hill Giant", ai));
        final Card freshGiant = addCard("Hill Giant", ai);
        final GameCopier copier = new GameCopier(game);
        copier.makeCopy();
        AssertJUnit.assertFalse(oldGiant.isValid("Card.ThisTurnEntered", ai, oldGiant, null));
        final Card cOld = copier.find(oldGiant);
        AssertJUnit.assertFalse("an old card did not enter this turn in the copy either",
                cOld.isValid("Card.ThisTurnEntered", cOld.getController(), cOld, null));
        AssertJUnit.assertTrue(freshGiant.isValid("Card.ThisTurnEntered", ai, freshGiant, null));
        final Card cFresh = copier.find(freshGiant);
        AssertJUnit.assertTrue(cFresh.isValid("Card.ThisTurnEntered", cFresh.getController(), cFresh, null));
    }

    private void keldonScenario(final int teamEntered, final boolean expectHaste) {
        setUp();
        final Card team = addCard("Keldon Strike Team", ai);
        team.setTurnInZone(teamEntered);
        team.setSickness(teamEntered == NOW);
        final Card giant = addCard("Hill Giant", ai);
        giant.setSickness(true); // it arrived this turn
        game.getAction().checkStateEffects(true);
        AssertJUnit.assertEquals("the original", expectHaste, CombatUtil.canAttack(giant));

        final GameCopier copier = new GameCopier(game);
        copier.makeCopy();
        AssertJUnit.assertEquals("the copy, as the original", expectHaste, CombatUtil.canAttack(copier.find(giant)));
    }

    /** "As long as Keldon Strike Team entered this turn, creatures you control have haste": not on a later turn. */
    @Test
    public void anOldKeldonStrikeTeamGivesNoHasteInTheCopy() {
        keldonScenario(EARLIER, false);
    }

    @Test
    public void aFreshKeldonStrikeTeamGivesHasteInTheCopy() {
        keldonScenario(NOW, true);
    }

    /** Pet Avengers' Power-up costs {6}{G}, less its mana cost {3}{G} only on the turn it entered. */
    @Test
    public void anOldPowerUpCostsItsFullCostInTheCopy() {
        setUp();
        final Card oldPet = old(addCard("Pet Avengers", ai));
        final Card freshPet = addCard("Pet Avengers", ai);
        final GameCopier copier = new GameCopier(game);
        copier.makeCopy();
        final Card cOld = copier.find(oldPet);
        final Card cFresh = copier.find(freshPet);
        AssertJUnit.assertEquals(7, powerUpCost(oldPet, ai));
        AssertJUnit.assertEquals("no discount in the copy either", 7, powerUpCost(cOld, cOld.getController()));
        AssertJUnit.assertEquals(3, powerUpCost(freshPet, ai));
        AssertJUnit.assertEquals(3, powerUpCost(cFresh, cFresh.getController()));
    }

    /** A card foretold on an earlier turn can be cast for its foretell cost: in the copy too. */
    @Test
    public void anOldForetoldCardCanBeCastFromExileInTheCopy() {
        setUp();
        final Card card = foretold(EARLIER);
        final GameCopier copier = new GameCopier(game);
        copier.makeCopy();
        AssertJUnit.assertTrue("the original offers the foretold cast", offersForetoldCast(card, ai));
        final Card copy = copier.find(card);
        AssertJUnit.assertTrue("so does the copy", offersForetoldCast(copy, copy.getOwner()));
    }

    /** A card foretold this turn cannot be cast for its foretell cost until a later turn, in the copy as in the game. */
    @Test
    public void aCardForetoldThisTurnCannotBeCastInTheCopy() {
        setUp();
        final Card card = foretold(NOW);
        final GameCopier copier = new GameCopier(game);
        copier.makeCopy();
        AssertJUnit.assertFalse(offersForetoldCast(card, ai));
        final Card copy = copier.find(card);
        AssertJUnit.assertFalse(offersForetoldCast(copy, copy.getOwner()));
    }

    /** Zone.add never stamps a card it puts on the stack, and the change leaves the stack's copies as they were. */
    @Test
    public void theStackKeepsItsCopiesUnstamped() {
        setUp();
        final Card bolt = createCard("Lightning Bolt", ai);
        bolt.setGameTimestamp(game.getNextTimestamp());
        game.getStackZone().add(bolt);
        bolt.setTurnInZone(NOW);
        final GameCopier copier = new GameCopier(game);
        copier.makeCopy();
        AssertJUnit.assertEquals(0, copier.find(bolt).getTurnInZone());
    }
}
