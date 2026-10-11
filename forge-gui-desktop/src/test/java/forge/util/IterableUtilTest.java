package forge.util;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Spliterator;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.StreamSupport;

import org.testng.annotations.Test;

import forge.util.collect.FCollection;

/**
 * IterableUtil's any, all, find and filter, and FCollection's anyMatch, do without a stream what the streams they used
 * did. Each test runs them and a copy of the stream code they replace on two equal sources that write down every call
 * made on them, with predicates that write down every element they test, and requires the same calls in the same
 * order, the same results and the same exceptions.
 */
public class IterableUtilTest {

    // ---- the stream code the methods replace, as it was ----

    private static <T> Iterable<T> streamFilter(Iterable<T> iterable, Predicate<? super T> filter) {
        return () -> StreamSupport.stream(iterable.spliterator(), false).filter(filter).iterator();
    }

    private static <T> boolean streamAny(Iterable<T> iterable, Predicate<? super T> test) {
        return StreamSupport.stream(iterable.spliterator(), false).anyMatch(test);
    }

    private static <T> boolean streamAll(Iterable<T> iterable, Predicate<? super T> test) {
        return StreamSupport.stream(iterable.spliterator(), false).allMatch(test);
    }

    private static <T> T streamFind(Iterable<T> iterable, Predicate<? super T> predicate) {
        return StreamSupport.stream(iterable.spliterator(), false).filter(predicate).findFirst().get();
    }

    private static <T> T streamFind(Iterable<T> iterable, Predicate<? super T> predicate, T defaultValue) {
        return StreamSupport.stream(iterable.spliterator(), false).filter(predicate).findFirst().orElse(defaultValue);
    }

    // ---- sources and predicates that write down what is done with them ----

    /** How a source behaves: its elements and its spliterator's ways. */
    private static final class Shape {
        final List<Integer> elements;
        int characteristics = Spliterator.ORDERED | Spliterator.SIZED | Spliterator.SUBSIZED;
        Comparator<Integer> comparator;
        // elements handed over by one tryAdvance, to stand for a source that breaks the rule of one
        int perAdvance = 1;
        // elements a source finds after it said it had no more, to stand for a source that grows
        List<Integer> late = List.of();
        boolean nullSpliterator;
        final String name;

        Shape(final String name, final Integer... elements) {
            this.name = name;
            this.elements = Arrays.asList(elements);
        }

        @Override
        public String toString() {
            return name;
        }
    }

    /** An iterable over a shape whose spliterators write every call made on them to the log. */
    private static final class Source implements Iterable<Integer> {
        final Shape shape;
        final List<String> log;

        Source(final Shape shape, final List<String> log) {
            this.shape = shape;
            this.log = log;
        }

        @Override
        public Iterator<Integer> iterator() {
            log.add("iterator");
            throw new UnsupportedOperationException("neither version asks for an iterator");
        }

        @Override
        public Spliterator<Integer> spliterator() {
            log.add("spliterator");
            return shape.nullSpliterator ? null : new Recording();
        }

        private final class Recording implements Spliterator<Integer> {
            private int index;
            private int lateIndex;
            private boolean saidNoMore;

            private void hand(final Consumer<? super Integer> action, final Integer e) {
                log.add("hand " + e);
                action.accept(e);
            }

            @Override
            public boolean tryAdvance(final Consumer<? super Integer> action) {
                log.add("tryAdvance");
                if (index < shape.elements.size()) {
                    for (int i = 0; i < shape.perAdvance && index < shape.elements.size(); i++) {
                        hand(action, shape.elements.get(index++));
                    }
                    return true;
                }
                if (!saidNoMore) {
                    saidNoMore = true;
                    return false;
                }
                if (lateIndex < shape.late.size()) {
                    hand(action, shape.late.get(lateIndex++));
                    return true;
                }
                return false;
            }

            @Override
            public void forEachRemaining(final Consumer<? super Integer> action) {
                log.add("forEachRemaining");
                while (index < shape.elements.size()) {
                    hand(action, shape.elements.get(index++));
                }
            }

            @Override
            public Spliterator<Integer> trySplit() {
                log.add("trySplit");
                return null;
            }

            @Override
            public long estimateSize() {
                log.add("estimateSize");
                return shape.elements.size() - index;
            }

            @Override
            public long getExactSizeIfKnown() {
                log.add("getExactSizeIfKnown");
                return (shape.characteristics & SIZED) == 0 ? -1 : shape.elements.size() - index;
            }

            @Override
            public int characteristics() {
                log.add("characteristics");
                return shape.characteristics;
            }

            @Override
            public Comparator<? super Integer> getComparator() {
                log.add("getComparator");
                if ((shape.characteristics & SORTED) == 0) {
                    throw new IllegalStateException();
                }
                return shape.comparator;
            }
        }
    }

    /** A predicate that writes down each element it is asked about. */
    private static Predicate<Integer> logged(final List<String> log, final Predicate<Integer> p) {
        return x -> {
            log.add("test " + x);
            return p.test(x);
        };
    }

    private static List<Shape> shapes() {
        final List<Shape> shapes = new ArrayList<>();
        shapes.add(new Shape("empty"));
        shapes.add(new Shape("one to five", 1, 2, 3, 4, 5));
        final Shape unsized = new Shape("unsized", 1, 2, 3, 4, 5);
        unsized.characteristics = Spliterator.ORDERED;
        shapes.add(unsized);
        final Shape natural = new Shape("sorted naturally", 1, 2, 3, 4, 5);
        natural.characteristics |= Spliterator.SORTED;
        shapes.add(natural);
        final Shape reversed = new Shape("sorted by a comparator", 5, 4, 3, 2, 1);
        reversed.characteristics |= Spliterator.SORTED;
        reversed.comparator = Comparator.reverseOrder();
        shapes.add(reversed);
        final Shape twice = new Shape("two at a time", 1, 2, 3, 4, 5);
        twice.perAdvance = 2;
        shapes.add(twice);
        final Shape growing = new Shape("more after the end", 1, 2, 3);
        growing.late = List.of(4, 5);
        shapes.add(growing);
        shapes.add(new Shape("with a null", 1, null, 3, 4));
        final Shape none = new Shape("no spliterator", 1, 2);
        none.nullSpliterator = true;
        shapes.add(none);
        return shapes;
    }

    private static List<Predicate<Integer>> predicates() {
        final List<Predicate<Integer>> predicates = new ArrayList<>();
        predicates.add(x -> false);
        predicates.add(x -> true);
        predicates.add(x -> x != null && x == 3);
        predicates.add(x -> x != null && x == 1);
        predicates.add(x -> x != null && x % 2 == 0);
        predicates.add(x -> x == null);
        predicates.add(x -> {
            if (x != null && x == 4) {
                throw new IllegalStateException("four");
            }
            return x != null && x == 5;
        });
        predicates.add(null);
        return predicates;
    }

    /** What a call returned, or what it threw. */
    private static String outcome(final Supplier<Object> call) {
        try {
            return "returns " + call.get();
        } catch (RuntimeException e) {
            return "throws " + e.getClass().getName() + ": " + e.getMessage();
        }
    }

    private interface Run {
        Object run(Iterable<Integer> source, Predicate<Integer> predicate);
    }

    /** Runs the stream code and the method on equal sources and requires the same calls, results and exceptions. */
    private static void assertSameAsStream(final String what, final Run stream, final Run method) {
        for (final Shape shape : shapes()) {
            final List<Predicate<Integer>> predicates = predicates();
            for (int i = 0; i < predicates.size(); i++) {
                final Predicate<Integer> p = predicates.get(i);
                final List<String> streamLog = new ArrayList<>();
                final Source streamSource = new Source(shape, streamLog);
                final Predicate<Integer> streamTest = p == null ? null : logged(streamLog, p);
                streamLog.add(outcome(() -> stream.run(streamSource, streamTest)));

                final List<String> methodLog = new ArrayList<>();
                final Source methodSource = new Source(shape, methodLog);
                final Predicate<Integer> methodTest = p == null ? null : logged(methodLog, p);
                methodLog.add(outcome(() -> method.run(methodSource, methodTest)));

                assertEquals(methodLog, streamLog, what + " over " + shape + " with predicate " + i);
            }
        }
    }

    @Test
    public void anyMakesTheStreamsCalls() {
        assertSameAsStream("any", IterableUtilTest::streamAny, IterableUtil::any);
    }

    @Test
    public void allMakesTheStreamsCalls() {
        assertSameAsStream("all", IterableUtilTest::streamAll, IterableUtil::all);
    }

    @Test
    public void findMakesTheStreamsCalls() {
        assertSameAsStream("find", IterableUtilTest::streamFind, IterableUtil::find);
    }

    @Test
    public void findWithADefaultMakesTheStreamsCalls() {
        assertSameAsStream("find with a default", (s, p) -> streamFind(s, p, -1),
                (s, p) -> IterableUtil.find(s, p, -1));
    }

    @Test
    public void filterInsideOtherCallsMakesTheStreamsCalls() {
        // a filter read by the others, and copied into a collection as CardLists.filter does
        final Predicate<Integer> odd = x -> x == null || x % 2 != 0;
        assertSameAsStream("any of a filter", (s, p) -> streamAny(streamFilter(s, odd), p),
                (s, p) -> IterableUtil.any(IterableUtil.filter(s, odd), p));
        assertSameAsStream("all of a filter", (s, p) -> streamAll(streamFilter(s, odd), p),
                (s, p) -> IterableUtil.all(IterableUtil.filter(s, odd), p));
        assertSameAsStream("find in a filter", (s, p) -> streamFind(streamFilter(s, odd), p),
                (s, p) -> IterableUtil.find(IterableUtil.filter(s, odd), p));
        assertSameAsStream("a filter copied", (s, p) -> new FCollection<>(streamFilter(s, p)),
                (s, p) -> new FCollection<>(IterableUtil.filter(s, p)));
        assertSameAsStream("a filter of a filter copied",
                (s, p) -> new FCollection<>(streamFilter(streamFilter(s, odd), p)),
                (s, p) -> new FCollection<>(IterableUtil.filter(IterableUtil.filter(s, odd), p)));
    }

    // ---- the filter's iterator, step by step ----

    /** One way of using an iterator, each step written to the log with its outcome. */
    private interface Script {
        void play(Iterable<Integer> filtered, List<String> log);
    }

    private static void step(final List<String> log, final String name, final Supplier<Object> call) {
        log.add("step " + name);
        log.add(outcome(call));
    }

    private static List<Script> scripts() {
        final List<Script> scripts = new ArrayList<>();
        // a for-each, then asked again after the end
        scripts.add((filtered, log) -> {
            final Iterator<Integer>[] it = iteratorOf(filtered, log);
            if (it == null) {
                return;
            }
            for (int i = 0; i < 12; i++) {
                step(log, "hasNext", () -> it[0].hasNext());
                step(log, "next", () -> it[0].next());
            }
        });
        // next() alone
        scripts.add((filtered, log) -> {
            final Iterator<Integer>[] it = iteratorOf(filtered, log);
            if (it == null) {
                return;
            }
            for (int i = 0; i < 8; i++) {
                step(log, "next", () -> it[0].next());
            }
        });
        // asked twice before each next(), then remove()
        scripts.add((filtered, log) -> {
            final Iterator<Integer>[] it = iteratorOf(filtered, log);
            if (it == null) {
                return;
            }
            for (int i = 0; i < 6; i++) {
                step(log, "hasNext", () -> it[0].hasNext());
                step(log, "hasNext", () -> it[0].hasNext());
                step(log, "next", () -> it[0].next());
            }
            step(log, "remove", () -> {
                it[0].remove();
                return null;
            });
        });
        // forEachRemaining at once, then asked again
        scripts.add((filtered, log) -> {
            final Iterator<Integer>[] it = iteratorOf(filtered, log);
            if (it == null) {
                return;
            }
            step(log, "forEachRemaining", () -> {
                it[0].forEachRemaining(x -> log.add("action " + x));
                return null;
            });
            step(log, "hasNext", () -> it[0].hasNext());
            step(log, "forEachRemaining", () -> {
                it[0].forEachRemaining(x -> log.add("action " + x));
                return null;
            });
        });
        // forEachRemaining after hasNext
        scripts.add((filtered, log) -> {
            final Iterator<Integer>[] it = iteratorOf(filtered, log);
            if (it == null) {
                return;
            }
            step(log, "hasNext", () -> it[0].hasNext());
            step(log, "forEachRemaining", () -> {
                it[0].forEachRemaining(x -> log.add("action " + x));
                return null;
            });
            step(log, "hasNext", () -> it[0].hasNext());
        });
        // forEachRemaining after next, twice
        scripts.add((filtered, log) -> {
            final Iterator<Integer>[] it = iteratorOf(filtered, log);
            if (it == null) {
                return;
            }
            step(log, "next", () -> it[0].next());
            step(log, "forEachRemaining", () -> {
                it[0].forEachRemaining(x -> log.add("action " + x));
                return null;
            });
            step(log, "forEachRemaining", () -> {
                it[0].forEachRemaining(x -> log.add("action " + x));
                return null;
            });
            step(log, "next", () -> it[0].next());
        });
        // two iterators of one filter, used in turn
        scripts.add((filtered, log) -> {
            final Iterator<Integer>[] first = iteratorOf(filtered, log);
            if (first == null) {
                return;
            }
            step(log, "first hasNext", () -> first[0].hasNext());
            final Iterator<Integer>[] second = iteratorOf(filtered, log);
            step(log, "second next", () -> second[0].next());
            step(log, "first next", () -> first[0].next());
            step(log, "second hasNext", () -> second[0].hasNext());
        });
        return scripts;
    }

    /** The filter's iterator in a holder, or null after writing down what iterator() threw. */
    @SuppressWarnings("unchecked")
    private static Iterator<Integer>[] iteratorOf(final Iterable<Integer> filtered, final List<String> log) {
        log.add("step iterator");
        try {
            final Iterator<Integer>[] holder = new Iterator[] {filtered.iterator()};
            log.add("returns an iterator");
            return holder;
        } catch (RuntimeException e) {
            log.add("throws " + e.getClass().getName() + ": " + e.getMessage());
            return null;
        }
    }

    @Test
    public void filterIteratesAsTheStreamsIteratorDid() {
        final List<Script> scripts = scripts();
        for (final Shape shape : shapes()) {
            final List<Predicate<Integer>> predicates = predicates();
            for (int i = 0; i < predicates.size(); i++) {
                final Predicate<Integer> p = predicates.get(i);
                for (int k = 0; k < scripts.size(); k++) {
                    final List<String> streamLog = new ArrayList<>();
                    scripts.get(k).play(streamFilter(new Source(shape, streamLog),
                            p == null ? null : logged(streamLog, p)), streamLog);

                    final List<String> methodLog = new ArrayList<>();
                    scripts.get(k).play(IterableUtil.filter(new Source(shape, methodLog),
                            p == null ? null : logged(methodLog, p)), methodLog);

                    assertEquals(methodLog, streamLog, "script " + k + " over " + shape + " with predicate " + i);
                }
            }
        }
    }

    @Test
    public void theTestsSeeTheCallsTheyCompare() {
        // the logs these tests compare hold the calls they are about: one source, one predicate, one script
        final Shape growing = new Shape("more after the end", 1, 2, 3);
        growing.late = List.of(4);
        final List<String> log = new ArrayList<>();
        final Iterator<Integer> it = IterableUtil.filter(new Source(growing, log), logged(log, x -> x > 2)).iterator();
        assertEquals(log, List.of("spliterator", "characteristics"));
        assertTrue(it.hasNext());
        assertEquals(it.next(), Integer.valueOf(3));
        // the source says it has no more, is asked again and finds 4
        assertTrue(it.hasNext());
        assertEquals(it.next(), Integer.valueOf(4));
        assertEquals(log, List.of("spliterator", "characteristics", "getExactSizeIfKnown", "tryAdvance", "hand 1",
                "test 1", "tryAdvance", "hand 2", "test 2", "tryAdvance", "hand 3", "test 3", "tryAdvance",
                "tryAdvance", "hand 4", "test 4"));
    }

    // ---- FCollection.anyMatch against the stream of its list ----

    private static final class Collected {
        final FCollection<String> collection = new FCollection<>(List.of("a", "b", "c"));
        final List<String> log = new ArrayList<>();
    }

    private static void assertAnyMatchIsTheStreams(final String what,
            final Function<Collected, Predicate<String>> test) {
        final Collected viaStream = new Collected();
        final Predicate<String> streamTest = test.apply(viaStream);
        viaStream.log.add(outcome(() -> viaStream.collection.stream().anyMatch(streamTest)));
        viaStream.log.add("then " + viaStream.collection);

        final Collected viaMethod = new Collected();
        final Predicate<String> methodTest = test.apply(viaMethod);
        viaMethod.log.add(outcome(() -> viaMethod.collection.anyMatch(methodTest)));
        viaMethod.log.add("then " + viaMethod.collection);

        assertEquals(viaMethod.log, viaStream.log, what);
    }

    @Test
    public void anyMatchOfACollectionIsItsListsStream() {
        assertAnyMatchIsTheStreams("none", c -> x -> {
            c.log.add("test " + x);
            return false;
        });
        assertAnyMatchIsTheStreams("the second", c -> x -> {
            c.log.add("test " + x);
            return x.equals("b");
        });
        assertAnyMatchIsTheStreams("a test that throws", c -> x -> {
            c.log.add("test " + x);
            if (x.equals("b")) {
                throw new IllegalStateException("b");
            }
            return false;
        });
        assertAnyMatchIsTheStreams("no test", c -> null);
        // tests that change the collection under the walk: the list's spliterator decides what follows
        assertAnyMatchIsTheStreams("adds while testing", c -> x -> {
            c.log.add("test " + x);
            if (x.equals("a")) {
                c.collection.add("d");
            }
            return false;
        });
        assertAnyMatchIsTheStreams("removes the last while testing the first", c -> x -> {
            c.log.add("test " + x);
            if (x.equals("a")) {
                c.collection.remove("c");
                c.collection.remove("b");
            }
            return false;
        });
        assertAnyMatchIsTheStreams("removes the one it passes", c -> x -> {
            c.log.add("test " + x);
            if (x.equals("b")) {
                c.collection.remove("b");
                return true;
            }
            return false;
        });
        assertAnyMatchIsTheStreams("an empty collection", c -> {
            c.collection.clear();
            return x -> {
                c.log.add("test " + x);
                return true;
            };
        });
    }
}
