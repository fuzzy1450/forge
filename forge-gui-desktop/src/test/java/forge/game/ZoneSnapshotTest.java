package forge.game;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertSame;
import static org.testng.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.testng.annotations.Test;

import forge.ai.AITest;
import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.card.CardCollectionView;
import forge.game.card.CardCopyService;
import forge.game.player.Player;
import forge.game.zone.ZoneType;

/**
 * {@link Game#getCardsIn(Iterable)} gathers the zones' cards and leaves out repeats once, through
 * {@link CardCollection#copyOf(List)}. These pin that the result is the collection adding the cards one at
 * a time makes, card for card, whether or not two of them are equal.
 */
public class ZoneSnapshotTest extends AITest {

    /** The zones' cards added one zone at a time to a {@link CardCollection}, as getCardsIn used to. */
    private static List<Card> addedOneAtATime(Game game, Iterable<ZoneType> zones) {
        CardCollection cards = new CardCollection();
        for (ZoneType z : zones) {
            if (z == ZoneType.Stack) {
                cards.addAll(game.getStackZone().getCards());
                continue;
            }
            for (Player p : game.getPlayers()) {
                cards.addAll(p.getCardsIn(z));
            }
        }
        return new ArrayList<>(cards);
    }

    /** Cards with ids 100 to 107, to take a list past FCollection's hash threshold. */
    private static List<Card> eightOthers() {
        List<Card> cards = new ArrayList<>();
        for (int id = 100; id < 108; id++) {
            cards.add(new Card(id, null));
        }
        return cards;
    }

    private static void assertSameCards(Iterable<Card> actual, List<Card> expected) {
        List<Card> got = new ArrayList<>();
        actual.forEach(got::add);
        assertEquals(got.size(), expected.size());
        for (int i = 0; i < expected.size(); i++) {
            assertSame(got.get(i), expected.get(i), "card " + i);
        }
    }

    @Test
    public void testCopyOfKeepsDistinctCardsInOrder() {
        List<Card> cards = new ArrayList<>();
        for (int id : new int[] {40, 3, 17, 900, 5, 6, 41, 2, 33, 12, 7}) {
            cards.add(new Card(id, null));
        }
        CardCollection copy = CardCollection.copyOf(cards);
        assertSameCards(copy, cards);
        for (Card c : cards) {
            assertTrue(copy.contains(c));
        }
        assertFalse(copy.contains(new Card(1, null)));
    }

    @Test
    public void testCopyOfLeavesOutLaterEqualCards() {
        Card first = new Card(7, null);
        // the same id and class, as an LKI copy has: equal to the first
        Card again = new Card(7, null);
        List<Card> cards = new ArrayList<>();
        for (int id = 1; id <= 10; id++) {
            cards.add(id == 7 ? first : new Card(id, null));
        }
        cards.add(again);
        cards.add(new Card(11, null));
        CardCollection copy = CardCollection.copyOf(cards);
        assertSameCards(copy, new ArrayList<>(new CardCollection(cards)));
        assertEquals(copy.size(), 11);
        assertSame(copy.get(6), first);
    }

    @Test
    public void testCopyOfKeepsCardsOfDifferentClassesWithOneId() {
        Card card = new Card(5, null);
        // a Card subclass with the same id is not equal to it
        Card other = new Card(5, null) { };
        List<Card> cards = eightOthers();
        cards.add(card);
        cards.add(other);
        assertSameCards(CardCollection.copyOf(cards), cards);
    }

    @Test
    public void testCopyOfSkipsNulls() {
        List<Card> cards = eightOthers();
        List<Card> expected = new ArrayList<>(cards);
        Card card = new Card(1, null);
        cards.add(null);
        cards.add(card);
        expected.add(card);
        assertSameCards(CardCollection.copyOf(cards), expected);
    }

    @Test
    public void testCopyOfHandlesFarApartIds() {
        Card low = new Card(1, null);
        Card high = new Card(Integer.MAX_VALUE, null);
        Card negative = new Card(-1, null);
        List<Card> cards = eightOthers();
        cards.addAll(Arrays.asList(low, high, negative));
        List<Card> expected = new ArrayList<>(cards);
        assertSameCards(CardCollection.copyOf(cards), expected);
        cards.add(new Card(1, null));
        assertSameCards(CardCollection.copyOf(cards), expected);
    }

    @Test
    public void testCopyOfShortLists() {
        Card card = new Card(3, null);
        Card other = new Card(3, null) { };
        assertEquals(CardCollection.copyOf(new ArrayList<>()).size(), 0);
        assertSameCards(CardCollection.copyOf(Arrays.asList(card, null, new Card(3, null), other)), Arrays.asList(card, other));
    }

    @Test
    public void testZoneSnapshotIsTheZonesCardsInOrder() {
        Game game = initAndCreateGame();
        Player p0 = game.getPlayers().get(0);
        Player p1 = game.getPlayers().get(1);
        for (int i = 0; i < 6; i++) {
            addCard("Grizzly Bears", p0);
            addCard("Forest", p1);
        }
        addCardToZone("Lightning Bolt", p0, ZoneType.Graveyard);
        addCardToZone("Shock", p1, ZoneType.Exile);
        CardCollectionView snapshot = game.getCardsIn(ZoneType.STATIC_ABILITIES_SOURCE_ZONES);
        assertEquals(snapshot.size(), 14);
        assertSameCards(snapshot, addedOneAtATime(game, ZoneType.STATIC_ABILITIES_SOURCE_ZONES));
    }

    @Test
    public void testZoneSnapshotKeepsTheFirstOfACardAndItsCopy() {
        Game game = initAndCreateGame();
        Player p = game.getPlayers().get(1);
        for (int i = 0; i < 9; i++) {
            addCard("Grizzly Bears", p);
        }
        Card bear = addCard("Grizzly Bears", p);
        // a copy with the bear's id in the graveyard while the bear is still on the battlefield, as during a
        // zone change; STATIC_ABILITIES_SOURCE_ZONES puts the graveyard first, so the copy is kept
        Card copy = CardCopyService.getLKICopy(bear);
        p.getZone(ZoneType.Graveyard).add(copy);
        CardCollectionView snapshot = game.getCardsIn(ZoneType.STATIC_ABILITIES_SOURCE_ZONES);
        assertSameCards(snapshot, addedOneAtATime(game, ZoneType.STATIC_ABILITIES_SOURCE_ZONES));
        assertEquals(snapshot.size(), 10);
        assertSame(snapshot.get(0), copy);
        for (Card c : snapshot) {
            assertTrue(c != bear);
        }
    }
}
