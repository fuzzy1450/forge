package forge.ai.ability;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.List;

import forge.ai.AITest;
import forge.ai.AiPlayDecision;
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
 * Gifts Ungiven (dead-card batch 4, row 32). Its "target opponent chooses two" sub-ability had no
 * IsCurse$, so the AI aimed it at itself and refused every cast; with IsCurse$ True (32A) the stock
 * hidden-origin path approves from Main 2 on, and SpecialCardAi.GiftsUngiven.worthSearching (32B1)
 * floors that approval: a library that keeps four cards, four different names, a search nobody else
 * controls, sees only the top of or profits from, and room in hand for the two cards. Every decline
 * draws nothing from MyRandom (with one opponent the stock path before it draws nothing either) and
 * leaves ChangeZoneAi's per-game choice scratch as the stock path left it.
 */
public class GiftsUngivenAiTest extends AITest {

    private static final List<String> NAMES = Arrays.asList("Runeclaw Bear", "Grizzly Bears", "Hill Giant",
            "Craw Wurm", "Serra Angel", "Shivan Dragon", "Llanowar Elves", "Counterspell", "Divination", "Opt");

    @Test
    public void castsFromMainTwoWithRoom() {
        // the 32A gate: the sub now targets the opponent instead of refusing the AI as its target
        Player ai = setup(8, 0, true);
        AssertJUnit.assertEquals(AiPlayDecision.WillPlay, decide(ai));
    }

    @Test
    public void neverBeforeMainTwo() {
        // the stock hidden-origin timing gate (a Library destination) is unchanged
        Game game = initAndCreateGame();
        Player ai = game.getPlayers().get(1);
        library(ai, 8);
        addCardToZone("Gifts Ungiven", ai, ZoneType.Hand);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, ai);
        game.getAction().checkStateEffects(true);
        AssertJUnit.assertEquals(AiPlayDecision.CantPlayAi, declinesDrawingNothing(ai));
    }

    @Test
    public void keepsFourCardsInTheLibrary() {
        // F1: four leave, at least four stay
        AssertJUnit.assertEquals(AiPlayDecision.CantPlayAi, declinesDrawingNothing(setup(7, 0, true)));
    }

    @Test
    public void needsFourDifferentNames() {
        // F2: three names over twelve cards put one card in hand, so no cast; four names cast
        Game game = initAndCreateGame();
        Player ai = game.getPlayers().get(1);
        for (int i = 0; i < 4; i++) {
            addCardToZone("Island", ai, ZoneType.Library);
            addCardToZone("Forest", ai, ZoneType.Library);
            addCardToZone("Plains", ai, ZoneType.Library);
        }
        addCardToZone("Gifts Ungiven", ai, ZoneType.Hand);
        moveToMain2(game, ai);
        AssertJUnit.assertEquals(AiPlayDecision.CantPlayAi, declinesDrawingNothing(ai));
        addCardToZone("Swamp", ai, ZoneType.Library);
        AssertJUnit.assertEquals(AiPlayDecision.WillPlay, decide(ai));
    }

    @Test
    public void notWhileAnOpponentControlsTheSearch() {
        // F3: the search-control record Opposition Agent's static writes on us
        Player ai = setup(8, 0, true);
        Player opponent = ai.getGame().getPlayers().get(0);
        ai.addControlledWhileSearching(ai.getGame().getNextTimestamp(), opponent);
        AssertJUnit.assertEquals(AiPlayDecision.CantPlayAi, declinesDrawingNothing(ai));
    }

    @Test
    public void notIntoOppositionAgent() {
        // an opposing ControlOpponentsSearchingLibrary static
        AssertJUnit.assertEquals(AiPlayDecision.CantPlayAi, declinesDrawingNothing(setupWith("Opposition Agent")));
    }

    @Test
    public void notUnderAvenMindcensor() {
        // LimitSearchLibrary: the search would see only the top four
        Player ai = setupWith("Aven Mindcensor");
        AssertJUnit.assertTrue(ai.hasKeyword("LimitSearchLibrary"));
        AssertJUnit.assertEquals(AiPlayDecision.CantPlayAi, declinesDrawingNothing(ai));
    }

    @Test
    public void notIntoArchivistOfOghma() {
        // an opposing SearchedLibrary trigger: a card and a life for them per search
        AssertJUnit.assertEquals(AiPlayDecision.CantPlayAi, declinesDrawingNothing(setupWith("Archivist of Oghma")));
    }

    @Test
    public void notIntoWidespreadPanic() {
        // a Shuffled trigger whose ValidPlayer matches us: the closing shuffle fires it
        AssertJUnit.assertEquals(AiPlayDecision.CantPlayAi, declinesDrawingNothing(setupWith("Widespread Panic")));
    }

    @Test
    public void leavesRoomInHandOnItsOwnTurn() {
        // F4: 5 other cards + 2 = 7 casts; 6 + 2 = 8 would discard at cleanup
        AssertJUnit.assertEquals(AiPlayDecision.WillPlay, decide(setup(8, 5, true)));
        AssertJUnit.assertEquals(AiPlayDecision.CantPlayAi, declinesDrawingNothing(setup(8, 6, true)));
    }

    @Test
    public void onAnOpponentsTurnOnlyNeverAboveTheMaximum() {
        // F4: on an opponent's turn 7 other cards still cast (DrawAi's rule); 8 do not
        AssertJUnit.assertEquals(AiPlayDecision.WillPlay, decide(setup(8, 7, false)));
        AssertJUnit.assertEquals(AiPlayDecision.CantPlayAi, declinesDrawingNothing(setup(8, 8, false)));
    }

    @Test
    public void aDeclineLeavesTheChoiceScratchAsTheStockPathDid() throws ReflectiveOperationException {
        // the floor sits after ChangeZoneAi.checkApiLogic's clear of the per-game scratch an Intuition
        // resolution reads, so a decline clears it exactly as the stock path did at every consult
        Player ai = setup(7, 0, true);
        Field f = ChangeZoneAi.class.getDeclaredField("multipleCardsToChoose");
        f.setAccessible(true);
        CardCollection scratch = (CardCollection) f.get(null);
        scratch.clear();
        scratch.add(ai.getCardsIn(ZoneType.Library).getFirst());
        AssertJUnit.assertEquals(AiPlayDecision.CantPlayAi, declinesDrawingNothing(ai));
        AssertJUnit.assertTrue(scratch.isEmpty());
    }

    @Test
    public void handlesAFaceDownCastByItsCardState() {
        Game game = initAndCreateGame();
        Player ai = game.getPlayers().get(1);
        Card gifts = addCardToZone("Gifts Ungiven", ai, ZoneType.Exile);
        SpellAbility sa = gifts.getFirstSpellAbility();
        AssertJUnit.assertTrue(SpecialCardAi.GiftsUngiven.handles(sa));
        AssertJUnit.assertFalse(SpecialCardAi.GiftsUngiven.handles(sa.getSubAbility()));
        gifts.turnFaceDown(true);
        AssertJUnit.assertFalse(SpecialCardAi.GiftsUngiven.NAME.equals(gifts.getName()));
        AssertJUnit.assertTrue(SpecialCardAi.GiftsUngiven.handles(sa));
    }

    // ai: player 1, NAMES-first library of libSize cards (all names distinct up to ten), Gifts plus
    // `others` Islands in hand, Main 2 of its own turn or of the opponent's
    private Player setup(int libSize, int others, boolean ourTurn) {
        Game game = initAndCreateGame();
        Player ai = game.getPlayers().get(1);
        Player opponent = game.getPlayers().get(0);
        library(ai, libSize);
        addCardToZone("Gifts Ungiven", ai, ZoneType.Hand);
        for (int i = 0; i < others; i++) {
            addCardToZone("Island", ai, ZoneType.Hand);
        }
        moveToMain2(game, ourTurn ? ai : opponent);
        return ai;
    }

    private Player setupWith(String opposingPermanent) {
        Game game = initAndCreateGame();
        Player ai = game.getPlayers().get(1);
        Player opponent = game.getPlayers().get(0);
        library(ai, 8);
        addCardToZone("Gifts Ungiven", ai, ZoneType.Hand);
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

    private AiPlayDecision decide(Player ai) {
        Card gifts = null;
        for (Card c : ai.getCardsIn(ZoneType.Hand)) {
            if (SpecialCardAi.GiftsUngiven.NAME.equals(c.getName())) {
                gifts = c;
            }
        }
        AssertJUnit.assertNotNull(gifts);
        SpellAbility sa = gifts.getFirstSpellAbility();
        sa.setActivatingPlayer(ai);
        return ((PlayerControllerAi) ai.getController()).getAi().canPlaySa(sa);
    }

    private AiPlayDecision declinesDrawingNothing(Player ai) {
        long before = MyRandom.callCount();
        AiPlayDecision d = decide(ai);
        AssertJUnit.assertEquals("a decline drew from MyRandom", before, MyRandom.callCount());
        return d;
    }
}
