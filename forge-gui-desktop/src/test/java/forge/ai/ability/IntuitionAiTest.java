package forge.ai.ability;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import forge.ai.AITest;
import forge.ai.AiPlayDecision;
import forge.ai.LobbyPlayerAi;
import forge.ai.PlayerControllerAi;
import forge.ai.SpecialCardAi;
import forge.game.Game;
import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import forge.util.MyRandom;
import org.testng.AssertJUnit;
import org.testng.annotations.Test;

/**
 * Intuition (dead-card batch 4, row 9). Its "target opponent chooses one" sub-ability had no IsCurse$, so
 * the AI aimed it at itself and refused every cast; with IsCurse$ True (9A) the stock hidden-origin path
 * approves from Main 2 on, and SpecialCardAi.IntuitionPile.worthSearching (9B1) floors that approval right
 * after ChangeZoneAi.checkApiLogic's stock fill of the per-game choice scratch: a library that keeps four
 * cards, and a search nobody else controls, limits or profits from. The fill
 * (SpecialCardAi.Intuition.considerMultiple) draws MyRandom in its test payments, in the stock path and here
 * alike, so every decline must draw exactly what the fill draws and leave the fill in the scratch, as the
 * stock path did at every consult. The seat plays the Default profile, as the stored games do
 * (INTUITION_ALTERNATIVE_LOGIC and RESERVE_MANA_FOR_MAIN2_CHANCE are its values).
 */
public class IntuitionAiTest extends AITest {

    private static final List<String> NAMES = Arrays.asList("Counterspell", "Divination", "Opt", "Cancel",
            "Essence Scatter", "Mulldrifter", "Air Elemental", "Serra Angel", "Grizzly Bears", "Hill Giant");

    @Test
    public void castsFromMainTwoWithASearchableLibrary() {
        // the 9A gate: the sub now targets the opponent instead of refusing the AI as its target
        Player ai = setup(7, true);
        AssertJUnit.assertEquals(AiPlayDecision.WillPlay, decide(ai));
    }

    @Test
    public void neverBeforeMainTwo() {
        // the stock hidden-origin timing gate (a Library destination) is unchanged, after the same fill
        Game game = initAndCreateGame();
        Player ai = game.getPlayers().get(1);
        ((LobbyPlayerAi) ai.getLobbyPlayer()).setAiProfile("Default");
        library(ai, 8);
        board(ai);
        addCardToZone("Intuition", ai, ZoneType.Hand);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, ai);
        game.getAction().checkStateEffects(true);
        AssertJUnit.assertEquals(AiPlayDecision.CantPlayAi, declinesWithTheFillsDrawsOnly(ai));
    }

    @Test
    public void keepsFourCardsInTheLibrary() {
        // three leave, at least four stay: 6 declines, 7 casts
        AssertJUnit.assertEquals(AiPlayDecision.CantPlayAi, declinesWithTheFillsDrawsOnly(setup(6, true)));
        AssertJUnit.assertEquals(AiPlayDecision.WillPlay, decide(setup(7, true)));
    }

    @Test
    public void theFillDrawsInThisSetup() {
        // so the draw checks above and below are not vacuous: the stock fill's test payments draw
        Player ai = setup(6, true);
        long before = MyRandom.callCount();
        SpecialCardAi.Intuition.considerMultiple(ai, intuition(ai));
        AssertJUnit.assertTrue("the stock fill drew nothing", MyRandom.callCount() > before);
    }

    @Test
    public void notWhileAnOpponentControlsTheSearch() {
        // the search-control record Opposition Agent's static writes on us
        Player ai = setup(8, true);
        Player opponent = ai.getGame().getPlayers().get(0);
        ai.addControlledWhileSearching(ai.getGame().getNextTimestamp(), opponent);
        AssertJUnit.assertEquals(AiPlayDecision.CantPlayAi, declinesWithTheFillsDrawsOnly(ai));
    }

    @Test
    public void notIntoOppositionAgent() {
        // an opposing ControlOpponentsSearchingLibrary static
        AssertJUnit.assertEquals(AiPlayDecision.CantPlayAi, declinesWithTheFillsDrawsOnly(setupWith("Opposition Agent")));
    }

    @Test
    public void notUnderAvenMindcensor() {
        // LimitSearchLibrary: the search would see only the top four
        Player ai = setupWith("Aven Mindcensor");
        AssertJUnit.assertTrue(ai.hasKeyword("LimitSearchLibrary"));
        AssertJUnit.assertEquals(AiPlayDecision.CantPlayAi, declinesWithTheFillsDrawsOnly(ai));
    }

    @Test
    public void notIntoArchivistOfOghma() {
        // an opposing SearchedLibrary trigger: a card and a life for them per search
        AssertJUnit.assertEquals(AiPlayDecision.CantPlayAi, declinesWithTheFillsDrawsOnly(setupWith("Archivist of Oghma")));
    }

    @Test
    public void notIntoWidespreadPanic() {
        // a Shuffled trigger whose ValidPlayer matches us: the closing shuffle fires it
        AssertJUnit.assertEquals(AiPlayDecision.CantPlayAi, declinesWithTheFillsDrawsOnly(setupWith("Widespread Panic")));
    }

    @Test
    public void aDeclineLeavesTheStockFillInTheScratch() throws ReflectiveOperationException {
        // the floor sits after ChangeZoneAi.checkApiLogic's clear and fill of the per-game scratch, so a decline
        // leaves exactly the cards the stock path left there at every consult (a foreign card seeded first must go)
        Player ai = setup(6, true);
        Player opponent = ai.getGame().getPlayers().get(0);
        Card foreign = addCardToZone("Hill Giant", opponent, ZoneType.Library);
        List<Card> expected = new ArrayList<>(SpecialCardAi.Intuition.considerMultiple(ai, intuition(ai)));
        AssertJUnit.assertFalse(expected.isEmpty());
        Field f = ChangeZoneAi.class.getDeclaredField("multipleCardsToChoose");
        f.setAccessible(true);
        CardCollection scratch = (CardCollection) f.get(null);
        scratch.clear();
        scratch.add(foreign);
        AssertJUnit.assertEquals(AiPlayDecision.CantPlayAi, decide(ai));
        AssertJUnit.assertEquals(expected, new ArrayList<>(scratch));
    }

    @Test
    public void handlesAFaceDownCastByItsCardState() {
        Game game = initAndCreateGame();
        Player ai = game.getPlayers().get(1);
        Card intuition = addCardToZone("Intuition", ai, ZoneType.Exile);
        SpellAbility sa = intuition.getFirstSpellAbility();
        AssertJUnit.assertTrue(SpecialCardAi.IntuitionPile.handles(sa));
        intuition.turnFaceDown(true);
        AssertJUnit.assertFalse(SpecialCardAi.IntuitionPile.NAME.equals(intuition.getName()));
        AssertJUnit.assertTrue(SpecialCardAi.IntuitionPile.handles(sa));
    }

    @Test
    public void burningRuneDemonKeepsTheStockPath() {
        // the other AILogic$ Intuition card: its enters trigger is not Intuition's
        Game game = initAndCreateGame();
        Player ai = game.getPlayers().get(1);
        Card demon = createCard("Burning-Rune Demon", ai);
        SpellAbility trig = demon.getTriggers().get(0).ensureAbility();
        AssertJUnit.assertEquals("Intuition", trig.getParam("AILogic"));
        AssertJUnit.assertFalse(SpecialCardAi.IntuitionPile.handles(trig));
    }

    // ai: player 1 on the Default profile, a NAMES-first library of libSize cards, three Islands, Intuition in
    // hand, Main 2 of its own turn or of the opponent's
    private Player setup(int libSize, boolean ourTurn) {
        Game game = initAndCreateGame();
        Player ai = game.getPlayers().get(1);
        Player opponent = game.getPlayers().get(0);
        ((LobbyPlayerAi) ai.getLobbyPlayer()).setAiProfile("Default");
        library(ai, libSize);
        board(ai);
        addCardToZone("Intuition", ai, ZoneType.Hand);
        moveToMain2(game, ourTurn ? ai : opponent);
        return ai;
    }

    private Player setupWith(String opposingPermanent) {
        Game game = initAndCreateGame();
        Player ai = game.getPlayers().get(1);
        Player opponent = game.getPlayers().get(0);
        ((LobbyPlayerAi) ai.getLobbyPlayer()).setAiProfile("Default");
        library(ai, 8);
        board(ai);
        addCardToZone("Intuition", ai, ZoneType.Hand);
        Card c = addCard(opposingPermanent, opponent);
        c.setSickness(false);
        moveToMain2(game, ai);
        return ai;
    }

    private void library(Player ai, int size) {
        for (int i = 0; i < size; i++) {
            addCardToZone(NAMES.get(i % NAMES.size()), ai, ZoneType.Library);
        }
    }

    private void board(Player ai) {
        for (int i = 0; i < 3; i++) {
            addCard("Island", ai);
        }
    }

    private SpellAbility intuition(Player ai) {
        Card intuition = null;
        for (Card c : ai.getCardsIn(ZoneType.Hand)) {
            if (SpecialCardAi.IntuitionPile.NAME.equals(c.getName())) {
                intuition = c;
            }
        }
        AssertJUnit.assertNotNull(intuition);
        SpellAbility sa = intuition.getFirstSpellAbility();
        sa.setActivatingPlayer(ai);
        return sa;
    }

    private AiPlayDecision decide(Player ai) {
        return ((PlayerControllerAi) ai.getController()).getAi().canPlaySa(intuition(ai));
    }

    // the stock path's own draws: the fill, measured alone on the same board, then the whole consult
    private AiPlayDecision declinesWithTheFillsDrawsOnly(Player ai) {
        long f0 = MyRandom.callCount();
        SpecialCardAi.Intuition.considerMultiple(ai, intuition(ai));
        long fill = MyRandom.callCount() - f0;
        long before = MyRandom.callCount();
        AiPlayDecision d = decide(ai);
        AssertJUnit.assertEquals("a decline drew other than the stock fill's draws", fill, MyRandom.callCount() - before);
        return d;
    }
}
