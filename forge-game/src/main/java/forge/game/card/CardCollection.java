package forge.game.card;

import forge.util.collect.FCollection;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import java.util.stream.Stream;

public class CardCollection extends FCollection<Card> implements CardCollectionView {
    private static final long serialVersionUID = -8133537013727100275L;

    /**
     * An empty, immutable {@link CardCollectionView}.
     */
    public static final CardCollectionView EMPTY = new EmptyCardCollection();

    /**
     * Get the view corresponding to an {@link Iterable} of {@link Card}
     * objects.
     *
     * @param cards
     *            a collection.
     * @return an unmodifiable view of the collection.
     */
    public static CardCollectionView getView(final Iterable<Card> cards) {
        return getView(cards, false);
    }

    /**
     * Get the view corresponding to an {@link Iterable} of {@link Card}
     * objects.
     *
     * @param cards
     *            a collection.
     * @param allowModify
     *            whether to make a shallow copy of the collection to make the
     *            returned view independent from the original collection.
     * @return an unmodifiable view of the collection.
     */
    public static CardCollectionView getView(final Iterable<Card> cards, final boolean allowModify) {
        if (cards == null) {
            return EMPTY;
        }
        if (allowModify) { //create copy to allow modifying original set while iterating
            return new CardCollection(cards);
        }

        if (cards instanceof CardCollectionView) {
            return (CardCollectionView) cards;
        }
        return new CardCollection(cards);
    }

    /**
     * Combine multiple instances of {@link CardCollectionView} into a single
     * view. The returned value is a view of the collections at the moment this
     * method is called, and is not backed by those collections. The returned
     * collection does respect the order, both of the order in which the
     * collections are supplied, and of the elements of those collections.
     *
     * @param views
     *            an array of card collections.
     * @return the elements of the collections in {@code views} combined into a
     *         single collection.
     * @throws NullPointerException
     *             if {@code views} is {@code null}.
     */
    public static CardCollectionView combine(final CardCollectionView... views) {
        if (views == null) {
            throw new NullPointerException("The 'views' parameter was null when CardCollection.combine was called");
        }

        CardCollectionView viewWithCards = null;
        List<Card> cards = null; //if multiple views have cards, all of their cards, in order
        int firstSize = 0;
        for (final CardCollectionView v : views) {
            if (!v.isEmpty()) {
                if (viewWithCards == null) {
                    viewWithCards = v;
                } else {
                    if (cards == null) {
                        cards = new ArrayList<>();
                        appendTo(cards, viewWithCards);
                        firstSize = cards.size();
                    }
                    appendTo(cards, v);
                }
            }
        }
        if (viewWithCards == null) {
            return CardCollection.EMPTY;
        }
        if (cards == null) {
            return viewWithCards;
        }
        if (cards.size() > HASH_THRESHOLD && haveDistinctIds(cards)) {
            // no two of the cards share an id, so adding them one at a time would keep them all, in this order
            final CardCollection newCol = new CardCollection();
            newCol.addAllDistinct(cards);
            return newCol;
        }
        // the first view's cards as they are, then the other views' cards added one at a time
        final CardCollection newCol = new CardCollection(viewWithCards);
        newCol.addAll(cards.subList(firstSize, cards.size()));
        return newCol;
    }

    /**
     * Append the cards of a collection to a list, in their order, without looking for repeats: for gathering
     * several collections' cards for {@link #copyOf(List)}. A missing or empty collection appends nothing, and
     * iterating copies no array, as ArrayList.addAll would.
     *
     * @param cards
     *            the list to append to.
     * @param from
     *            a collection of cards, or {@code null}.
     */
    public static void appendTo(final List<Card> cards, final CardCollectionView from) {
        if (from == null || from.isEmpty()) {
            return;
        }
        for (final Card c : from) {
            cards.add(c);
        }
    }

    /**
     * Create a new {@link CardCollection} of the given cards, in their order,
     * leaving out every card equal to an earlier one: the collection that
     * adding them one at a time makes. Cards are equal only when their ids
     * are, so when no two of them share an id none is left out, and a list
     * long enough to need a hash set is taken as it is instead of each card
     * being checked against the ones before it.
     *
     * @param cards
     *            a list of cards, possibly with repeats.
     * @return a new collection.
     */
    public static CardCollection copyOf(final List<Card> cards) {
        final CardCollection result = new CardCollection();
        if (cards.size() > HASH_THRESHOLD && haveDistinctIds(cards)) {
            result.addAllDistinct(cards);
        } else {
            result.addAll(cards);
        }
        return result;
    }

    /**
     * Whether no card of a non-empty list is null and no two have the same id,
     * with a bit per id from the smallest to the largest. Ids spread wider than
     * about four longs a card give false, which only means the cards get checked.
     */
    private static boolean haveDistinctIds(final List<Card> cards) {
        int min = Integer.MAX_VALUE;
        int max = Integer.MIN_VALUE;
        for (final Card c : cards) {
            if (c == null) {
                return false;
            }
            final int id = c.getId();
            min = Math.min(min, id);
            max = Math.max(max, id);
        }
        if ((long) max - min >= 256L * cards.size()) {
            return false;
        }
        final long[] seen = new long[((max - min) >>> 6) + 1];
        for (final Card c : cards) {
            final int bit = c.getId() - min;
            if ((seen[bit >>> 6] & (1L << bit)) != 0) {
                return false;
            }
            seen[bit >>> 6] |= 1L << bit;
        }
        return true;
    }

    /**
     * Construct a new, empty {@link CardCollection}.
     */
    public CardCollection() {
        super();
    }

    /**
     * Add the cards of a collection in which no two cards are equal, such as a zone snapshot, in their order,
     * leaving out each one equal to a card this collection already holds. That is what {@link #addAll(Iterable)}
     * adds; but as none of the cards can equal one added before it, each is looked for only among the cards this
     * collection held before.
     *
     * @param unique
     *            cards no two of which are equal.
     */
    public void addAllUnique(final CardCollectionView unique) {
        if (unique == null || unique.isEmpty()) {
            return;
        }
        final List<Card> added = new ArrayList<>(unique.size());
        for (final Card c : unique) {
            if (c != null && !contains(c)) {
                added.add(c);
            }
        }
        addAllDistinct(added);
    }

    /**
     * Construct a new {@link CardCollection} containing a single element.
     *
     * @param card
     *            the element contained by the new collection.
     */
    public CardCollection(final Card card) {
        super(card);
    }

    /**
     * Construct a new {@link CardCollection} from an iterable of {@link Card}
     * objects, respecting the order in which those objects appear.
     *
     * @param cards
     *            an {@link Iterable}.
     */
    public CardCollection(final Iterable<Card> cards) {
        super(cards);
    }

    public CardCollection(final Stream<Card> cards) {
        this(cards::iterator);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public CardCollection subList(final int fromIndex, final int toIndex) {
        return new CardCollection(super.subList(fromIndex, toIndex));
    }

    /**
     * Creates a new CardCollection containing the elements from this collection which match
     * the given predicate.
     */
    public CardCollection filter(Predicate<? super Card> test) {
        CardCollection out = new CardCollection();
        this.stream().filter(test).forEach(out::add);
        return out;
    }

    /**
     * An unmodifiable, empty {@link CardCollection}.
     */
    private final static class EmptyCardCollection extends EmptyFCollection<Card> implements CardCollectionView {
        private static final long serialVersionUID = -3218771134502034727L;

        private EmptyCardCollection() {
            super();
        }
    }
}
