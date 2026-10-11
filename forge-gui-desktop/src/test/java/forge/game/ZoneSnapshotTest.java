package forge.game;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNotSame;
import static org.testng.Assert.assertSame;
import static org.testng.Assert.assertTrue;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;

import org.testng.annotations.Test;

import forge.ai.AITest;
import forge.card.GamePieceType;
import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.card.CardCollectionView;
import forge.game.card.CardCopyService;
import forge.game.cost.CostAdjustment;
import forge.game.mana.ManaCostBeingPaid;
import forge.game.player.Player;
import forge.game.player.PlayerCollection;
import forge.game.spellability.SpellAbility;
import forge.game.staticability.StaticAbilityCantAttackBlock;
import forge.game.zone.Zone;
import forge.game.zone.ZoneType;

/**
 * {@link Game#getCardsIn(Iterable)} gathers the zones' cards and leaves out repeats once, through
 * {@link CardCollection#copyOf(List)}. These pin that the result is the collection adding the cards one at
 * a time makes, card for card, whether or not two of them are equal.
 * <p>
 * The snapshots {@link Game#getCardsIn(ZoneType)} and {@link Game#getCardsIn(Iterable)} keep for a zone type and
 * for the static ability source zones are pinned the same way: whatever happened to the zones in between, a new
 * snapshot holds the cards the old loops would have collected, and so do the battlefields of a game that has never
 * had a phased-out card, the player unions and the collections the static ability, combine and cost adjustment
 * code builds from snapshots.
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
        assertSameCards(actual, expected, "");
    }

    private static void assertSameCards(Iterable<Card> actual, List<Card> expected, String what) {
        List<Card> got = new ArrayList<>();
        actual.forEach(got::add);
        assertEquals(got.size(), expected.size(), what + " size");
        for (int i = 0; i < expected.size(); i++) {
            assertSame(got.get(i), expected.get(i), what + " card " + i);
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

    // ---- the kept snapshots ---------------------------------------------------------------------------------------

    /** The players' cards in one zone added one player at a time to a {@link CardCollection}, as getCardsIn used to. */
    private static List<Card> zoneAddedOneAtATime(Game game, ZoneType zone) {
        CardCollection cards = new CardCollection();
        for (Player p : game.getPlayers()) {
            cards.addAll(p.getCardsIn(zone));
        }
        return new ArrayList<>(cards);
    }

    /**
     * Every snapshot the game keeps, taken now, holds what the old loops collect now. Taking them also leaves each one
     * kept, so that a change made after this call is one the next call has to see.
     */
    private static void assertSnapshotsAsAddedOneAtATime(Game game) {
        assertSameCards(game.getCardsIn(ZoneType.STATIC_ABILITIES_SOURCE_ZONES),
                addedOneAtATime(game, ZoneType.STATIC_ABILITIES_SOURCE_ZONES), "static ability sources");
        for (ZoneType z : ZoneType.values()) {
            if (z != ZoneType.Stack && z != ZoneType.Flashback) {
                assertSameCards(game.getCardsIn(z), zoneAddedOneAtATime(game, z), z.name());
            }
        }
    }

    /** An effect card in a player's command zone, as the game makes them. */
    private static Card addEffect(Player p, String name) {
        Game game = p.getGame();
        Card effect = new Card(game.nextCardId(), null, game);
        effect.setOwner(p);
        effect.setGamePieceType(GamePieceType.EFFECT);
        effect.setName(name);
        p.getZone(ZoneType.Command).add(effect);
        return effect;
    }

    /** A game with cards in every zone a kept snapshot reads, more than eight on the battlefields, one on the stack. */
    private Game gameWithCardsInEveryZone() {
        Game game = initAndCreateGame();
        Player p0 = game.getPlayers().get(0);
        Player p1 = game.getPlayers().get(1);
        for (int i = 0; i < 5; i++) {
            addCard("Grizzly Bears", p0);
            addCard("Forest", p1);
        }
        addCardToZone("Lightning Bolt", p0, ZoneType.Graveyard);
        addCardToZone("Shock", p0, ZoneType.Graveyard);
        addCardToZone("Giant Growth", p1, ZoneType.Graveyard);
        addCardToZone("Shock", p1, ZoneType.Exile);
        for (int i = 0; i < 3; i++) {
            addCardToZone("Island", p0, ZoneType.Library);
            addCardToZone("Mountain", p1, ZoneType.Library);
        }
        addCardToZone("Lightning Bolt", p1, ZoneType.Hand);
        addCardToZone("Grizzly Bears", p1, ZoneType.Hand);
        addCardToZone("Shock", p1, ZoneType.Hand);
        addEffect(p0, "Test Effect");
        game.getStackZone().add(createCard("Giant Growth", p0));
        return game;
    }

    @Test
    public void testAKeptSnapshotIsANewCollectionOfTheSameCards() {
        Game game = gameWithCardsInEveryZone();
        Player p0 = game.getPlayers().get(0);
        assertSnapshotsAsAddedOneAtATime(game);
        CardCollectionView first = game.getCardsIn(ZoneType.STATIC_ABILITIES_SOURCE_ZONES);
        CardCollectionView second = game.getCardsIn(ZoneType.STATIC_ABILITIES_SOURCE_ZONES);
        assertNotSame(second, first);
        assertSameCards(second, addedOneAtATime(game, ZoneType.STATIC_ABILITIES_SOURCE_ZONES));
        CardCollectionView battlefield = game.getCardsIn(ZoneType.Battlefield);
        assertNotSame(game.getCardsIn(ZoneType.Battlefield), battlefield);

        // a card put on the battlefield through its zone's list, which fires nothing, so that the next snapshots
        // are made anew; what they and the kept ones after them hand out may be changed without changing the next
        p0.getZone(ZoneType.Battlefield).getCards(false).add(createCard("Forest", p0));
        for (int i = 0; i < 3; i++) {
            ((CardCollection) game.getCardsIn(ZoneType.STATIC_ABILITIES_SOURCE_ZONES)).clear();
            ((CardCollection) game.getCardsIn(ZoneType.Battlefield)).clear();
        }
        assertSnapshotsAsAddedOneAtATime(game);
        assertEquals(game.getCardsIn(ZoneType.Battlefield).size(), 11);

        // the stack is still the zone's own list
        assertSame(game.getCardsIn(ZoneType.Stack), game.getStackZone().getCards());
    }

    @Test
    public void testNoZoneHoldsNoCards() {
        // ZoneType.smartValueOf gives null for "All": getCardsIn has always answered no zone with an empty collection
        assertTrue(gameWithCardsInEveryZone().getCardsIn((ZoneType) null).isEmpty());
    }

    @Test
    public void testASnapshotSeesACardAddedToAZone() {
        Game game = gameWithCardsInEveryZone();
        Player p1 = game.getPlayers().get(1);
        assertSnapshotsAsAddedOneAtATime(game);
        Card shock = addCardToZone("Shock", p1, ZoneType.Graveyard);
        assertSnapshotsAsAddedOneAtATime(game);
        assertTrue(game.getCardsIn(ZoneType.Graveyard).contains(shock));
        assertTrue(game.getCardsIn(ZoneType.STATIC_ABILITIES_SOURCE_ZONES).contains(shock));
    }

    @Test
    public void testASnapshotSeesTheLastCardItGatheredRemoved() {
        Game game = gameWithCardsInEveryZone();
        Player p1 = game.getPlayers().get(1);
        assertSnapshotsAsAddedOneAtATime(game);
        // the stack comes last in the static ability source zones
        Card onStack = game.getStackZone().get(0);
        game.getStackZone().remove(onStack);
        assertSnapshotsAsAddedOneAtATime(game);
        assertFalse(game.getCardsIn(ZoneType.STATIC_ABILITIES_SOURCE_ZONES).contains(onStack));
        // and p1 last among the players
        Card lastInGraveyard = p1.getZone(ZoneType.Graveyard).get(0);
        p1.getZone(ZoneType.Graveyard).remove(lastInGraveyard);
        assertSnapshotsAsAddedOneAtATime(game);
        assertFalse(game.getCardsIn(ZoneType.Graveyard).contains(lastInGraveyard));
    }

    @Test
    public void testASnapshotSeesAZoneReordered() {
        Game game = gameWithCardsInEveryZone();
        Player p0 = game.getPlayers().get(0);
        Zone graveyard = p0.getZone(ZoneType.Graveyard);
        assertSnapshotsAsAddedOneAtATime(game);
        Card last = graveyard.get(graveyard.size() - 1);
        graveyard.reorder(last, 0);
        assertSnapshotsAsAddedOneAtATime(game);
        assertSame(game.getCardsIn(ZoneType.Graveyard).get(0), last);
        assertSame(game.getCardsIn(ZoneType.STATIC_ABILITIES_SOURCE_ZONES).get(0), last);
    }

    @Test
    public void testASnapshotSeesAZoneSorted() throws Exception {
        Game game = gameWithCardsInEveryZone();
        Player p1 = game.getPlayers().get(1);
        Zone hand = p1.getZone(ZoneType.Hand);
        assertSnapshotsAsAddedOneAtATime(game);
        List<Card> before = new ArrayList<>(game.getCardsIn(ZoneType.Hand));
        // Zone.sort orders a hand for a controller that asks for it; called here as onChanged would
        Method sort = Zone.class.getDeclaredMethod("sort");
        sort.setAccessible(true);
        sort.invoke(hand);
        assertSnapshotsAsAddedOneAtATime(game);
        List<Card> after = new ArrayList<>(game.getCardsIn(ZoneType.Hand));
        assertEquals(after.size(), before.size());
        assertFalse(after.equals(before), "sorting changed the order");
        assertEquals(after.get(2).getName(), "Grizzly Bears");
    }

    @Test
    public void testASnapshotSeesAZoneShuffled() {
        Game game = gameWithCardsInEveryZone();
        Player p0 = game.getPlayers().get(0);
        Zone library = p0.getZone(ZoneType.Library);
        assertSnapshotsAsAddedOneAtATime(game);
        List<Card> before = new ArrayList<>(library.getCards());
        for (int i = 0; i < 50 && before.equals(new ArrayList<>(library.getCards())); i++) {
            library.shuffle();
        }
        assertFalse(before.equals(new ArrayList<>(library.getCards())), "shuffling changed the order");
        assertSnapshotsAsAddedOneAtATime(game);
    }

    @Test
    public void testASnapshotSeesAZoneGivenOtherCards() {
        Game game = gameWithCardsInEveryZone();
        Player p1 = game.getPlayers().get(1);
        Zone exile = p1.getZone(ZoneType.Exile);
        assertSnapshotsAsAddedOneAtATime(game);
        Card bolt = createCard("Lightning Bolt", p1);
        List<Card> cards = new ArrayList<>(exile.getCards());
        cards.add(0, bolt);
        exile.setCards(cards);
        assertSnapshotsAsAddedOneAtATime(game);
        assertTrue(game.getCardsIn(ZoneType.Exile).contains(bolt));
        assertTrue(game.getCardsIn(ZoneType.STATIC_ABILITIES_SOURCE_ZONES).contains(bolt));
    }

    @Test
    public void testASnapshotSeesAChangeMadeThroughTheZonesOwnCollection() {
        Game game = gameWithCardsInEveryZone();
        Player p1 = game.getPlayers().get(1);
        assertSnapshotsAsAddedOneAtATime(game);
        // a player's cards are handed out as the zone's own list, which can be changed as any Collection is
        Collection<Card> hand = p1.getCardsIn(ZoneType.Hand);
        Card first = p1.getZone(ZoneType.Hand).get(0);
        assertTrue(hand.remove(first));
        assertSnapshotsAsAddedOneAtATime(game);
        assertFalse(game.getCardsIn(ZoneType.Hand).contains(first));
        Collection<Card> battlefield = p1.getCardsIn(ZoneType.Battlefield);
        assertTrue(battlefield.add(first));
        assertSnapshotsAsAddedOneAtATime(game);
        assertTrue(game.getCardsIn(ZoneType.STATIC_ABILITIES_SOURCE_ZONES).contains(first));
    }

    @Test
    public void testASnapshotKeepsTheCopyOfACardInTheMiddleOfItsZoneChange() {
        Game game = initAndCreateGame();
        Player p = game.getPlayers().get(1);
        for (int i = 0; i < 9; i++) {
            addCard("Grizzly Bears", p);
        }
        Card bear = addCard("Grizzly Bears", p);
        assertSnapshotsAsAddedOneAtATime(game);
        // the copy goes to the graveyard first and the bear leaves the battlefield after, as in a zone change
        Card copy = CardCopyService.getLKICopy(bear);
        p.getZone(ZoneType.Graveyard).add(copy);
        assertSnapshotsAsAddedOneAtATime(game);
        CardCollectionView snapshot = game.getCardsIn(ZoneType.STATIC_ABILITIES_SOURCE_ZONES);
        assertEquals(snapshot.size(), 10);
        assertSame(snapshot.get(0), copy);
        assertFalse(snapshot.stream().anyMatch(c -> c == bear));
        p.getZone(ZoneType.Battlefield).remove(bear);
        assertSnapshotsAsAddedOneAtATime(game);
        assertEquals(game.getCardsIn(ZoneType.STATIC_ABILITIES_SOURCE_ZONES).size(), 10);
    }

    @Test
    public void testASnapshotTellsACardFromItsCopyInTheSamePlace() {
        Game game = initAndCreateGame();
        Player p = game.getPlayers().get(1);
        for (int i = 0; i < 9; i++) {
            addCard("Grizzly Bears", p);
        }
        Card bear = addCard("Grizzly Bears", p);
        assertSnapshotsAsAddedOneAtATime(game);
        // the copy takes the bear's place: the same id and class in the same position, another object
        Card copy = CardCopyService.getLKICopy(bear);
        Zone battlefield = p.getZone(ZoneType.Battlefield);
        List<Card> cards = new ArrayList<>(battlefield.getCards(false));
        cards.set(cards.indexOf(bear), copy);
        battlefield.setCards(cards);
        assertSnapshotsAsAddedOneAtATime(game);
        assertSame(game.getCardsIn(ZoneType.Battlefield).get(9), copy);
        assertSame(game.getCardsIn(ZoneType.STATIC_ABILITIES_SOURCE_ZONES).get(9), copy);
    }

    // ---- the phased-out flag --------------------------------------------------------------------------------------

    @Test
    public void testABattlefieldWithNoPhasedOutCardIsTheZonesOwnList() {
        Game game = gameWithCardsInEveryZone();
        Player p0 = game.getPlayers().get(0);
        assertFalse(game.mayHavePhasedOutCards());
        Zone battlefield = p0.getZone(ZoneType.Battlefield);
        assertSame(battlefield.getCards(), battlefield.getCards(false));
        assertSame(p0.getCardsIn(ZoneType.Battlefield), battlefield.getCards(false));
    }

    @Test
    public void testAPhasedOutCardIsLeftOutOfTheSnapshots() {
        Game game = gameWithCardsInEveryZone();
        Player p0 = game.getPlayers().get(0);
        Card bear = p0.getZone(ZoneType.Battlefield).get(2);
        assertSnapshotsAsAddedOneAtATime(game);
        assertFalse(game.mayHavePhasedOutCards());
        bear.phase(false);
        assertTrue(bear.isPhasedOut());
        assertTrue(game.mayHavePhasedOutCards());
        assertSnapshotsAsAddedOneAtATime(game);
        assertFalse(p0.getCardsIn(ZoneType.Battlefield).contains(bear));
        assertFalse(game.getCardsIn(ZoneType.Battlefield).contains(bear));
        assertFalse(game.getCardsIn(ZoneType.STATIC_ABILITIES_SOURCE_ZONES).contains(bear));
        assertTrue(game.getCardsIncludePhasingIn(ZoneType.Battlefield).contains(bear));
        assertEquals(game.getCardsIn(ZoneType.Battlefield).size(), 9);
        // it stays raised: the battlefields are searched again, and find the card back
        bear.phase(false);
        assertFalse(bear.isPhasedOut());
        assertTrue(game.mayHavePhasedOutCards());
        assertSnapshotsAsAddedOneAtATime(game);
        assertTrue(game.getCardsIn(ZoneType.Battlefield).contains(bear));
    }

    @Test
    public void testAPhasedOutCardMovedIntoAnotherGameIsLeftOutThere() {
        Game second = gameWithCardsInEveryZone();
        Game first = initAndCreateGame();
        // cards are numbered per game: the first game's numbers start past the second's, so that the card that
        // moves is equal to none of the second game's cards
        for (int i = 0; i < 1000; i++) {
            first.nextCardId();
        }
        Player owner = first.getPlayers().get(0);
        Player other = second.getPlayers().get(0);
        Card bear = addCard("Grizzly Bears", owner);
        bear.phase(false);
        assertTrue(first.mayHavePhasedOutCards());
        assertFalse(second.mayHavePhasedOutCards());
        owner.getZone(ZoneType.Battlefield).remove(bear);
        bear.dangerouslySetGame(second);
        assertTrue(second.mayHavePhasedOutCards());
        other.getZone(ZoneType.Battlefield).add(bear);
        assertTrue(other.getZone(ZoneType.Battlefield).getCards(false).stream().anyMatch(c -> c == bear));
        assertFalse(other.getCardsIn(ZoneType.Battlefield).stream().anyMatch(c -> c == bear));
        assertFalse(second.getCardsIn(ZoneType.Battlefield).stream().anyMatch(c -> c == bear));
        assertEquals(second.getCardsIn(ZoneType.Battlefield).size(), 10);
        assertSnapshotsAsAddedOneAtATime(second);
    }

    @Test
    public void testACardWithNoGameCanBePhasedOutAndGivenNone() {
        Game game = initAndCreateGame();
        Card card = new Card(1, null);
        card.setPhasedOut(game.getPlayers().get(0));
        assertTrue(card.isPhasedOut());
        card.dangerouslySetGame(null);
        assertTrue(card.getGame() == null);
        assertFalse(game.mayHavePhasedOutCards());
    }

    // ---- the collections built from snapshots --------------------------------------------------------------------

    /** A collection that adding the cards one at a time makes, or rather its list. */
    private static List<Card> addedTo(List<Card> held, Iterable<Card> cards) {
        CardCollection result = new CardCollection(held);
        result.addAll(cards);
        return new ArrayList<>(result);
    }

    @Test
    public void testAddAllUniqueAddsWhatAddAllAdds() {
        List<Card> snapshot = eightOthers();
        snapshot.addAll(Arrays.asList(new Card(1, null), new Card(2, null), new Card(3, null)));
        CardCollection unique = new CardCollection(snapshot);
        Card first = snapshot.get(0);
        Card middle = snapshot.get(8);
        Card last = snapshot.get(10);
        Card copyOfMiddle = new Card(middle.getId(), null);
        Card otherClass = new Card(middle.getId(), null) { };
        // nine held, eight of them equal to the first eight of the snapshot: a hash set answers the test
        List<Card> nineHeld = eightOthers();
        nineHeld.add(middle);
        List<List<Card>> prefixes = Arrays.asList(
                new ArrayList<>(),
                Arrays.asList(middle),
                Arrays.asList(first, last),
                Arrays.asList(last, middle),
                Arrays.asList(copyOfMiddle),
                Arrays.asList(otherClass),
                Arrays.asList(new Card(50, null)),
                nineHeld);
        for (List<Card> prefix : prefixes) {
            CardCollection list = new CardCollection(prefix);
            list.addAllUnique(unique);
            List<Card> expected = addedTo(prefix, unique);
            assertSameCards(list, expected, "after " + prefix);
            for (Card c : expected) {
                assertTrue(list.contains(c));
                assertFalse(list.add(c));
            }
        }
        // the copy shadows the card it was made from, the card of another class does not
        CardCollection shadowed = new CardCollection(copyOfMiddle);
        shadowed.addAllUnique(unique);
        assertEquals(shadowed.size(), 11);
        assertFalse(shadowed.stream().anyMatch(c -> c == middle));
        CardCollection kept = new CardCollection(otherClass);
        kept.addAllUnique(unique);
        assertEquals(kept.size(), 12);
    }

    @Test
    public void testAddAllUniqueSkipsANullAndNothing() {
        Card held = new Card(1, null);
        CardCollection list = new CardCollection(held);
        list.addAllUnique(null);
        list.addAllUnique(new CardCollection());
        list.addAllUnique(CardCollection.EMPTY);
        assertSameCards(list, Arrays.asList(held));
        // a collection holding a null, as one changed by position can
        CardCollection withNull = new CardCollection(eightOthers());
        withNull.add(new Card(2, null));
        withNull.set(3, null);
        list.addAllUnique(withNull);
        assertSameCards(list, addedTo(Arrays.asList(held), withNull));
        assertFalse(list.contains(null));
        assertEquals(list.size(), 9);
    }

    @Test
    public void testTheBlockingChecksLetACopyShadowItsCard() {
        Game game = initAndCreateGame();
        Player p0 = game.getPlayers().get(0);
        Player p1 = game.getPlayers().get(1);
        for (int i = 0; i < 8; i++) {
            addCard("Forest", p0);
        }
        Card attacker = addCard("Grizzly Bears", p0);
        Card blocker = addCard("Grizzly Bears", p1);
        Card phantom = addCard("Phantom Warrior", p0);
        Card craven = addCard("Craven Giant", p1);
        assertTrue(StaticAbilityCantAttackBlock.cantBlockBy(phantom, blocker));
        assertFalse(StaticAbilityCantAttackBlock.cantBlockBy(attacker, blocker));
        assertTrue(StaticAbilityCantAttackBlock.cantBlock(craven));
        assertFalse(StaticAbilityCantAttackBlock.cantBlock(blocker));

        // copies taken before the cards gained the ability: each copy comes first in its check and leaves its
        // card out of the snapshot that follows, so the ability the card has now is not seen
        Card attackerBefore = CardCopyService.getLKICopy(attacker);
        Card blockerBefore = CardCopyService.getLKICopy(blocker);
        attacker.addStaticAbility("Mode$ CantBlockBy | ValidAttacker$ Creature.Self | Description$ CARDNAME can't be blocked.");
        blocker.addStaticAbility("Mode$ CantBlock | ValidCard$ Card.Self | Description$ CARDNAME can't block.");
        assertTrue(StaticAbilityCantAttackBlock.cantBlockBy(attacker, blocker));
        assertFalse(StaticAbilityCantAttackBlock.cantBlockBy(attackerBefore, blockerBefore));
        assertTrue(StaticAbilityCantAttackBlock.cantBlock(blocker));
        assertFalse(StaticAbilityCantAttackBlock.cantBlock(blockerBefore));
    }

    /** The collection combine made before: the first collection with cards copied, the others' added one at a time. */
    private static List<Card> combinedOneAtATime(CardCollectionView... views) {
        CardCollection result = null;
        for (CardCollectionView v : views) {
            if (v.isEmpty()) {
                continue;
            }
            if (result == null) {
                result = new CardCollection(v);
            } else {
                result.addAll(v);
            }
        }
        return result == null ? new ArrayList<>() : new ArrayList<>(result);
    }

    @Test
    public void testCombineMakesWhatItMadeBefore() {
        CardCollection empty = new CardCollection();
        CardCollection three = new CardCollection(Arrays.asList(new Card(1, null), new Card(2, null), new Card(3, null)));
        CardCollection eight = new CardCollection(eightOthers());
        assertSame(CardCollection.combine(empty, CardCollection.EMPTY), CardCollection.EMPTY);
        assertSame(CardCollection.combine(empty, three, empty), three);

        // more than eight cards, no two with one id
        assertSameCards(CardCollection.combine(three, empty, eight), combinedOneAtATime(three, empty, eight));
        assertEquals(CardCollection.combine(three, eight).size(), 11);
        // a later card equal to an earlier one is left out
        CardCollection again = new CardCollection(Arrays.asList(new Card(4, null), new Card(2, null)));
        assertSameCards(CardCollection.combine(three, eight, again), combinedOneAtATime(three, eight, again));
        assertEquals(CardCollection.combine(three, eight, again).size(), 12);
        // eight or fewer, with a repeat
        assertSameCards(CardCollection.combine(three, again), combinedOneAtATime(three, again));
        // the first collection is copied as it is, even holding a card twice
        CardCollection twice = new CardCollection(eightOthers());
        twice.set(1, twice.get(0));
        CardCollectionView combined = CardCollection.combine(twice, three);
        assertSameCards(combined, combinedOneAtATime(twice, three));
        assertEquals(combined.size(), 11);
        assertSame(combined.get(1), twice.get(0));
    }

    @Test
    public void testThePlayerUnionsAreTheCardsAddedOneAtATime() {
        Game game = gameWithCardsInEveryZone();
        PlayerCollection players = game.getPlayers();
        Player p0 = players.get(0);
        Player p1 = players.get(1);
        for (int i = 0; i < 4; i++) {
            addCard("Llanowar Elves", p1);
        }
        EnumSet<ZoneType> zones = EnumSet.of(ZoneType.Hand, ZoneType.Graveyard, ZoneType.Battlefield);
        for (int round = 0; round < 2; round++) {
            CardCollection inBattlefields = new CardCollection();
            CardCollection inZones = new CardCollection();
            CardCollection creatures = new CardCollection();
            for (Player p : players) {
                inBattlefields.addAll(p.getCardsIn(ZoneType.Battlefield));
                inZones.addAll(p.getCardsIn(zones));
                creatures.addAll(p.getCreaturesInPlay());
            }
            assertSameCards(players.getCardsIn(ZoneType.Battlefield), new ArrayList<>(inBattlefields), "battlefields");
            assertSameCards(players.getCardsIn(zones), new ArrayList<>(inZones), "zones");
            assertSameCards(players.getCreaturesInPlay(), new ArrayList<>(creatures), "creatures");
            for (Player p : players) {
                CardCollection own = new CardCollection();
                CardCollection withPhasedOut = new CardCollection();
                CardCollection listed = new CardCollection();
                for (ZoneType z : zones) {
                    own.addAll(p.getCardsIn(z));
                    withPhasedOut.addAll(p.getCardsIn(z, false));
                }
                listed.addAll(p.getCardsIn(ZoneType.Graveyard));
                listed.addAll(p.getCardsIn(ZoneType.Battlefield));
                assertSameCards(p.getCardsIn(zones), new ArrayList<>(own), "own zones");
                assertSameCards(p.getCardsIn(zones, false), new ArrayList<>(withPhasedOut), "own zones, phased out too");
                assertSameCards(p.getCardsIn(ZoneType.Graveyard, ZoneType.Battlefield), new ArrayList<>(listed), "listed zones");
            }
            // the second round with cards in two places at once: one of p0's bears also on p1's battlefield, p1's
            // first card in hand also in its graveyard, a copy of p1's first forest in p0's graveyard
            Card bear = p0.getZone(ZoneType.Battlefield).get(0);
            p1.getZone(ZoneType.Battlefield).getCards(false).add(bear);
            p1.getZone(ZoneType.Graveyard).getCards().add(p1.getZone(ZoneType.Hand).get(0));
            p0.getZone(ZoneType.Graveyard).add(CardCopyService.getLKICopy(p1.getZone(ZoneType.Battlefield).get(0)));
        }
        assertEquals(players.getCardsIn(ZoneType.Battlefield).size(), 14);
    }

    @Test
    public void testCostAdjustmentFindsWhatChangesACostWhereverItLooks() {
        Game game = initAndCreateGame();
        Player p = game.getPlayers().get(1);
        for (int i = 0; i < 9; i++) {
            addCard("Island", p);
        }
        Card bolt = addCardToZone("Lightning Bolt", p, ZoneType.Hand);
        SpellAbility boltSpell = bolt.getFirstSpellAbility();
        boltSpell.setActivatingPlayer(p);
        assertEquals(CostAdjustment.adjust(boltSpell.getPayCosts(), boltSpell, false).getTotalMana().getCMC(), 1);
        // a raise on the battlefield, one in the command zone, one on the stack and one on the spell itself
        addCard("Sphere of Resistance", p);
        assertEquals(CostAdjustment.adjust(boltSpell.getPayCosts(), boltSpell, false).getTotalMana().getCMC(), 2);
        addEffect(p, "Test Tax").addStaticAbility("Mode$ RaiseCost | ValidCard$ Card | Type$ Spell | EffectZone$ Command | Amount$ 1 | Description$ Spells cost {1} more to cast.");
        assertEquals(CostAdjustment.adjust(boltSpell.getPayCosts(), boltSpell, false).getTotalMana().getCMC(), 3);
        Card onStack = createCard("Giant Growth", p);
        onStack.addStaticAbility("Mode$ RaiseCost | ValidCard$ Card | Type$ Spell | EffectZone$ Stack | Amount$ 1 | Description$ Spells cost {1} more to cast.");
        game.getStackZone().add(onStack);
        assertEquals(CostAdjustment.adjust(boltSpell.getPayCosts(), boltSpell, false).getTotalMana().getCMC(), 4);
        bolt.addStaticAbility("Mode$ RaiseCost | ValidCard$ Card.Self | Type$ Spell | EffectZone$ All | Amount$ 1 | Description$ This spell costs {1} more to cast.");
        assertEquals(CostAdjustment.adjust(boltSpell.getPayCosts(), boltSpell, false).getTotalMana().getCMC(), 5);

        // reductions, found the same way
        Card elemental = addCardToZone("Air Elemental", p, ZoneType.Hand);
        SpellAbility elementalSpell = elemental.getFirstSpellAbility();
        elementalSpell.setActivatingPlayer(p);
        int[] expected = {5 + 3, 5 + 3 - 1, 5 + 3 - 2, 5 + 3 - 3, 5 + 3 - 4};
        String reduce = "Mode$ ReduceCost | ValidCard$ Card | Type$ Spell | Amount$ 1 | Description$ Spells cost {1} less to cast.";
        for (int step = 0; step < expected.length; step++) {
            if (step == 1) {
                addCard("Helm of Awakening", p);
            } else if (step == 2) {
                addEffect(p, "Test Discount").addStaticAbility(reduce + " | EffectZone$ Command");
            } else if (step == 3) {
                Card discount = createCard("Giant Growth", p);
                discount.addStaticAbility(reduce + " | EffectZone$ Stack");
                game.getStackZone().add(discount);
            } else if (step == 4) {
                elemental.addStaticAbility("Mode$ ReduceCost | ValidCard$ Card.Self | Type$ Spell | EffectZone$ All | Amount$ 1 | Description$ This spell costs {1} less to cast.");
            }
            ManaCostBeingPaid cost = new ManaCostBeingPaid(CostAdjustment.adjust(elementalSpell.getPayCosts(), elementalSpell, false).getTotalMana());
            CostAdjustment.adjust(cost, elementalSpell, p, null, true, false);
            assertEquals(cost.getConvertedManaCost(), expected[step], "step " + step);
        }
    }
}
