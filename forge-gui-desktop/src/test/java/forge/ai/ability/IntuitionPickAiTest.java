package forge.ai.ability;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import forge.ai.AITest;
import forge.ai.LobbyPlayerAi;
import forge.ai.SpecialCardAi;
import forge.game.Game;
import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import org.testng.AssertJUnit;
import org.testng.annotations.Test;

/**
 * Intuition (dead-card batch 4, row 9, part 9C). Its root's three picks went through
 * ChangeZoneAi.chooseCardToHiddenOriginChangeZone's "Intuition" branch, which returned the per-game choice
 * scratch's first card unchecked: a scratch another consult had cleared lost the pick, and one an opposing
 * Intuition's consult had refilled named a card of the other player's library (logged in the skeptic's skf
 * probes). Now the searching player's picks are read afresh from the stock evaluator
 * (SpecialCardAi.IntuitionPile.choosePick -> SpecialCardAi.Intuition.considerMultiple): the first of its cards
 * still in fetchList, else the stock pick; Burning-Rune Demon, the other AILogic$ Intuition card, keeps the
 * scratch branch. The seat plays the Default profile (INTUITION_ALTERNATIVE_LOGIC on), as the stored games do.
 */
public class IntuitionPickAiTest extends AITest {

    private static final List<String> NAMES = Arrays.asList("Counterspell", "Divination", "Opt", "Cancel",
            "Essence Scatter", "Mulldrifter", "Air Elemental", "Serra Angel", "Grizzly Bears", "Hill Giant");

    @Test
    public void picksItsOwnLibraryWhenTheScratchHoldsTheOpponentsCards() throws ReflectiveOperationException {
        // the mirror: an opposing Intuition's consult refilled the scratch with the other library's cards
        Player ai = setup(true);
        Player opponent = ai.getGame().getPlayers().get(0);
        CardCollection foreign = new CardCollection();
        for (String name : NAMES) {
            foreign.add(addCardToZone(name, opponent, ZoneType.Library));
        }
        SpellAbility sa = intuition(ai);
        List<Card> expected = expected(ai, sa);
        scratch().clear();
        scratch().addAll(foreign);
        CardCollection fetchList = new CardCollection(ai.getCardsIn(ZoneType.Library));
        Card pick = pick(ai, sa, fetchList);
        AssertJUnit.assertTrue("picked a card outside the search", fetchList.contains(pick));
        AssertJUnit.assertEquals(ai, pick.getOwner());
        AssertJUnit.assertEquals(expected.get(0), pick);
    }

    @Test
    public void picksAfreshWhenTheScratchIsEmpty() throws ReflectiveOperationException {
        // a consult between the cast and the resolution cleared the scratch (a fetch land, a tutor in hand)
        Player ai = setup(true);
        SpellAbility sa = intuition(ai);
        List<Card> expected = expected(ai, sa);
        scratch().clear();
        CardCollection fetchList = new CardCollection(ai.getCardsIn(ZoneType.Library));
        AssertJUnit.assertEquals(expected.get(0), pick(ai, sa, fetchList));
    }

    @Test
    public void threePicksComeOutInTheEvaluatorsOrder() throws ReflectiveOperationException {
        // ChangeZoneEffect asks three times, removing each pick from fetchList while the library keeps it
        Player ai = setup(true);
        SpellAbility sa = intuition(ai);
        List<Card> expected = expected(ai, sa);
        AssertJUnit.assertEquals(3, expected.size());
        scratch().clear();
        CardCollection fetchList = new CardCollection(ai.getCardsIn(ZoneType.Library));
        List<Card> picks = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            Card c = pick(ai, sa, fetchList);
            picks.add(c);
            fetchList.remove(c);
        }
        AssertJUnit.assertEquals(expected, picks);
    }

    @Test
    public void fallsBackToTheStockPickWhenTheEvaluatorWantsNothing() throws ReflectiveOperationException {
        // an all-land library: the evaluator returns nothing, so the stock pick below takes a searchable card
        Player ai = setup(false);
        SpellAbility sa = intuition(ai);
        AssertJUnit.assertTrue(SpecialCardAi.Intuition.considerMultiple(ai, sa).isEmpty());
        scratch().clear();
        CardCollection fetchList = new CardCollection(ai.getCardsIn(ZoneType.Library));
        Card pick = pick(ai, sa, fetchList);
        AssertJUnit.assertNotNull(pick);
        AssertJUnit.assertTrue(fetchList.contains(pick));
    }

    @Test
    public void aFaceDownHostPicksItsOwnLibrary() throws ReflectiveOperationException {
        // a Gonti-class thief's face-down cast: named by its card state, not getAbilitySourceName
        Player ai = setup(true);
        Player opponent = ai.getGame().getPlayers().get(0);
        Card host = addCardToZone("Intuition", ai, ZoneType.Exile);
        SpellAbility sa = host.getFirstSpellAbility();
        sa.setActivatingPlayer(ai);
        host.turnFaceDown(true);
        AssertJUnit.assertFalse(SpecialCardAi.IntuitionPile.NAME.equals(host.getName()));
        List<Card> expected = expected(ai, sa);
        scratch().clear();
        scratch().add(addCardToZone("Hill Giant", opponent, ZoneType.Library));
        CardCollection fetchList = new CardCollection(ai.getCardsIn(ZoneType.Library));
        AssertJUnit.assertEquals(expected.get(0), pick(ai, sa, fetchList));
    }

    @Test
    public void burningRuneDemonKeepsTheScratchPick() throws ReflectiveOperationException {
        // the other AILogic$ Intuition card is untouched: its pick is still the scratch's first card
        Player ai = setup(true);
        Card demon = addCard("Burning-Rune Demon", ai);
        SpellAbility trig = demon.getTriggers().get(0).ensureAbility();
        trig.setActivatingPlayer(ai);
        AssertJUnit.assertEquals("Intuition", trig.getParam("AILogic"));
        CardCollection fetchList = new CardCollection(ai.getCardsIn(ZoneType.Library));
        Card marked = fetchList.getLast();
        scratch().clear();
        scratch().add(marked);
        AssertJUnit.assertEquals(marked, pick(ai, trig, fetchList));
        AssertJUnit.assertTrue(scratch().isEmpty());
    }

    // ai: player 1 on the Default profile, a ten-name spell library (or ten Islands), three Islands, Intuition in
    // hand, its own Main 2
    private Player setup(boolean spells) {
        Game game = initAndCreateGame();
        Player ai = game.getPlayers().get(1);
        ((LobbyPlayerAi) ai.getLobbyPlayer()).setAiProfile("Default");
        for (String name : NAMES) {
            addCardToZone(spells ? name : "Island", ai, ZoneType.Library);
        }
        for (int i = 0; i < 3; i++) {
            addCard("Island", ai);
        }
        addCardToZone("Intuition", ai, ZoneType.Hand);
        moveToMain2(game, ai);
        return ai;
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

    // the stock evaluator's own list on this board: what the three picks must be, in order
    private List<Card> expected(Player ai, SpellAbility sa) {
        List<Card> expected = new ArrayList<>(SpecialCardAi.Intuition.considerMultiple(ai, sa));
        AssertJUnit.assertFalse(expected.isEmpty());
        for (Card c : expected) {
            AssertJUnit.assertEquals(ai, c.getOwner());
        }
        return expected;
    }

    private Card pick(Player ai, SpellAbility sa, CardCollection fetchList) {
        return ChangeZoneAi.chooseCardToHiddenOriginChangeZone(ZoneType.Library, Arrays.asList(ZoneType.Library), sa,
                fetchList, ai, ai);
    }

    private static CardCollection scratch() throws ReflectiveOperationException {
        Field f = ChangeZoneAi.class.getDeclaredField("multipleCardsToChoose");
        f.setAccessible(true);
        return (CardCollection) f.get(null);
    }
}
