package forge;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertSame;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.expectThrows;

import java.util.ArrayList;
import java.util.ConcurrentModificationException;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;

import org.testng.annotations.Test;

import forge.util.collect.FCollection;

/**
 * The copy constructor leaves an empty source alone instead of copying its list, and {@link FCollection#get(Object)}
 * answers for an empty collection without iterating it. These pin that a copy of any empty collection behaves as
 * a new, empty one on every later operation, past the hash threshold included; that a copy of a filled one holds
 * its elements in order, independently of its source; and that get returns the collection's equal element, or its
 * argument when there is none.
 */
public class FCollectionCopyAndGetTest {

    /** Equal by value, so that get returning the held element can be told apart from it returning its argument. */
    private static final class Key {
        private final int value;

        Key(final int value) {
            this.value = value;
        }

        @Override
        public boolean equals(final Object o) {
            return o instanceof Key && ((Key) o).value == value;
        }

        @Override
        public int hashCode() {
            return value;
        }

        @Override
        public String toString() {
            return "Key" + value;
        }
    }

    /** FCollection's hash threshold, protected there, so read through a subclass. */
    private static final class Threshold extends FCollection<Object> {
        private static final long serialVersionUID = 1L;
        private static final int VALUE = HASH_THRESHOLD;
    }

    private static List<Key> keys(final int from, final int to) {
        final List<Key> result = new ArrayList<>();
        for (int i = from; i < to; i++) {
            result.add(new Key(i));
        }
        return result;
    }

    /** Every kind of empty collection a copy can start from. */
    private static List<FCollection<Key>> emptySources() {
        final List<FCollection<Key>> sources = new ArrayList<>();
        // never held anything: its list still has the shared empty array
        sources.add(new FCollection<>());
        // held a few and was cleared: its list has an array of its own
        final FCollection<Key> cleared = new FCollection<>(keys(0, 3));
        cleared.clear();
        sources.add(cleared);
        // grew past the threshold and lost every element again: it has an empty hash set
        final FCollection<Key> emptied = new FCollection<>(keys(0, 12));
        for (final Key k : keys(0, 12)) {
            assertTrue(emptied.remove(k));
        }
        sources.add(emptied);
        // the shared, unmodifiable empty collection
        sources.add(FCollection.getEmpty());
        // itself a copy of an empty one
        sources.add(new FCollection<>(new FCollection<Key>()));
        for (final FCollection<Key> source : sources) {
            assertTrue(source.isEmpty());
        }
        return sources;
    }

    /**
     * What a new, empty collection does on every later operation, checked on the one given: its answers while
     * empty, then a walk past the hash threshold in which a repeat below it is refused by the list scan and one
     * above it by the hash set that the add passing the threshold builds, then its answers after. Nothing before
     * the walk may call asSet(), which would build the set early; assertHashedEarlyAsNewEmpty covers that case.
     */
    private static void assertBehavesAsNewEmpty(final FCollection<Key> c) {
        final Key probe = new Key(1000);
        assertEquals(c.size(), 0);
        assertTrue(c.isEmpty());
        assertFalse(c.iterator().hasNext());
        assertFalse(c.contains(probe));
        assertEquals(c.indexOf(probe), -1);
        assertSame(c.get(probe), probe);
        assertEquals(c.toArray().length, 0);
        assertEquals(c.hashCode(), new FCollection<Key>().hashCode());
        assertEquals(c.toString(), "[]");

        // an iterator taken now still fails fast once the collection changes
        final Iterator<Key> before = c.iterator();
        final Key first = new Key(-1);
        assertTrue(c.add(first));
        expectThrows(ConcurrentModificationException.class, before::next);
        assertTrue(c.remove(first));
        assertTrue(c.isEmpty());

        // add past the hash threshold one at a time: a repeat below it is refused by the list scan, one above it
        // by the hash set built at the add that passes the threshold
        final List<Key> added = keys(0, Threshold.VALUE * 3);
        for (final Key k : added) {
            assertTrue(c.add(k), "adding " + k);
            if (k.value == 3 || k.value == Threshold.VALUE + 3) {
                assertFalse(c.add(new Key(k.value)), "a repeat of " + k);
            }
        }

        // the answers after the walk, the set's among them
        assertEquals(c.size(), added.size());
        assertEquals(new ArrayList<>(c), added);
        for (final Key k : added) {
            assertTrue(c.contains(new Key(k.value)));
            assertSame(c.get(new Key(k.value)), k);
        }
        assertEquals(c.asSet(), new LinkedHashSet<>(added));
        assertTrue(c.remove(new Key(5)));
        assertFalse(c.contains(new Key(5)));
        assertEquals(c.size(), added.size() - 1);
        assertEquals(c.asSet().size(), added.size() - 1);
    }

    /**
     * What a new, empty collection does when asked for its set before anything is added: an empty set, built at
     * once, through which every add then goes, with the same answers.
     */
    private static void assertHashedEarlyAsNewEmpty(final FCollection<Key> c) {
        assertTrue(c.asSet().isEmpty());
        final List<Key> added = keys(0, Threshold.VALUE + 4);
        for (final Key k : added) {
            assertTrue(c.add(k), "adding " + k);
            if (k.value == 3 || k.value == Threshold.VALUE + 3) {
                assertFalse(c.add(new Key(k.value)), "a repeat of " + k);
            }
        }
        assertEquals(new ArrayList<>(c), added);
        assertEquals(c.asSet(), new LinkedHashSet<>(added));
        for (final Key k : added) {
            assertSame(c.get(new Key(k.value)), k);
        }
        assertTrue(c.remove(new Key(5)));
        assertFalse(c.contains(new Key(5)));
    }

    @Test
    public void aNewCollectionBehavesAsANewOne() {
        // the yardstick for the copies below, and the other way to start from nothing
        assertBehavesAsNewEmpty(new FCollection<>());
        assertBehavesAsNewEmpty(new FCollection<>(new ArrayList<Key>()));
        assertHashedEarlyAsNewEmpty(new FCollection<>());
        assertHashedEarlyAsNewEmpty(new FCollection<>(new ArrayList<Key>()));
    }

    @Test
    public void aCopyOfAnEmptyCollectionBehavesAsANewOne() {
        for (final FCollection<Key> source : emptySources()) {
            // the walk past the threshold on one copy, the set asked for at once on another
            assertBehavesAsNewEmpty(new FCollection<>(source));
            assertHashedEarlyAsNewEmpty(new FCollection<>(source));
            assertTrue(source.isEmpty(), "the source is left as it was");
        }
    }

    @Test
    public void anEmptySourceAndItsCopyAreIndependent() {
        final FCollection<Key> source = new FCollection<>();
        final FCollection<Key> copy = new FCollection<>(source);
        source.addAll(keys(0, 12));
        assertTrue(copy.isEmpty());
        assertTrue(copy.add(new Key(99)));
        assertEquals(source.size(), 12);
        assertFalse(source.contains(new Key(99)));
    }

    @Test
    public void aCopyOfAFilledCollectionHoldsItsElementsInOrder() {
        for (final int n : new int[] {1, Threshold.VALUE, Threshold.VALUE + 1, 30}) {
            final List<Key> elements = keys(0, n);
            final FCollection<Key> source = new FCollection<>(elements);
            final FCollection<Key> copy = new FCollection<>(source);
            assertEquals(new ArrayList<>(copy), elements);
            for (final Key k : elements) {
                assertSame(copy.get(new Key(k.value)), k);
                assertFalse(copy.add(new Key(k.value)), "a repeat of " + k);
            }
            assertEquals(copy.asSet(), new LinkedHashSet<>(elements));
            // the copy and its source are independent
            assertTrue(copy.add(new Key(n)));
            assertEquals(source.size(), n);
            assertTrue(source.remove(elements.get(0)));
            assertTrue(copy.contains(elements.get(0)));
            assertEquals(copy.size(), n + 1);
        }
    }

    @Test
    public void getReturnsTheHeldEqualElementOrItsArgument() {
        final Key probe = new Key(5);
        // an empty collection of any kind, and a copy of one, gives the argument back, and null for null
        for (final FCollection<Key> source : emptySources()) {
            assertSame(source.get(probe), probe);
            assertNull(source.get(null));
            assertSame(new FCollection<>(source).get(probe), probe);
        }
        // a held equal element is returned in place of the argument, below and past the threshold
        for (final int n : new int[] {1, Threshold.VALUE, 30}) {
            final List<Key> elements = keys(0, n);
            final FCollection<Key> c = new FCollection<>(elements);
            for (final Key k : elements) {
                assertSame(c.get(new Key(k.value)), k);
            }
            final Key absent = new Key(n + 1);
            assertSame(c.get(absent), absent);
            assertNull(c.get(null));
        }
    }
}
