package forge.util;

import java.util.Arrays;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.Spliterator;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.StreamSupport;

/**
 * Provides helper methods for Iterables and Predicates similar
 * to the Guava library, but supporting Java 8's implementation
 * of Predicates instead.
 */
public class IterableUtil {

    /**
     * Merges a collection of predicates into a single predicate,
     * which requires the subject to match each of the component predicates.
     */
    public static <T> Predicate<T> and(Iterable<? extends Predicate<? super T>> components) {
        if(components instanceof List && ((List<?>) components).size() == 1)
            return ((List<? extends Predicate<? super T>>) components).get(0)::test;
        return x -> all(components, i -> i.test(x));
    }

    /**
     * Merges a collection of predicates into a single predicate,
     * which requires the subject to match at least one of the component predicates.
     */
    public static <T> Predicate<T> or(Iterable<? extends Predicate<? super T>> components) {
        if(components instanceof List && ((List<?>) components).size() == 1)
            return ((List<? extends Predicate<? super T>>) components).get(0)::test;
        return x -> any(components, i -> i.test(x));
    }

    public static <T> Iterable<T> filter(Iterable<T> iterable, Predicate<? super T> filter) {
        // StreamSupport.stream(iterable.spliterator(), false).filter(filter).iterator(), without the stream
        return () -> new FilterIterator<>(iterable, filter);
    }

    public static <T> Iterable<T> filter(Collection<T> iterable, Predicate<? super T> filter) {
        return () -> iterable.stream().filter(filter).iterator();
    }

    public static <T> Iterable<T> filter(final Iterable<?> iterable, final Class<T> desiredType) {
        return () -> StreamSupport.stream(iterable.spliterator(), false)
                .filter(desiredType::isInstance)
                .map(desiredType::cast)
                .iterator();
    }

    public static <T> boolean any(Iterable<T> iterable, Predicate<? super T> test) {
        // StreamSupport.stream(iterable.spliterator(), false).anyMatch(test), without the stream
        return match(iterable, test, true);
    }

    public static <T> boolean all(Iterable<T> iterable, Predicate<? super T> test) {
        // StreamSupport.stream(iterable.spliterator(), false).allMatch(test), without the stream
        return match(iterable, test, false);
    }

    public static <T> T find(Iterable<T> iterable, Predicate<? super T> predicate) {
        // StreamSupport.stream(iterable.spliterator(), false).filter(predicate).findFirst().get(), without the stream
        final First<T> first = findFirst(iterable, predicate);
        if (!first.found) {
            throw new NoSuchElementException("No value present");
        }
        // as Optional.of, which refuses a null
        return Objects.requireNonNull(first.value);
    }

    public static <T> T find(Iterable<T> iterable, Predicate<? super T> predicate, T defaultValue) {
        // StreamSupport.stream(iterable.spliterator(), false).filter(predicate).findFirst().orElse(defaultValue),
        // without the stream
        final First<T> first = findFirst(iterable, predicate);
        if (!first.found) {
            return defaultValue;
        }
        // as Optional.of, which refuses a null
        return Objects.requireNonNull(first.value);
    }

    public static <T> Optional<T> tryFind(Iterable<T> iterable, Predicate<? super T> predicate) {
        return StreamSupport.stream(iterable.spliterator(), false).filter(predicate).findFirst();
    }

    public static <T> int indexOf(Iterable<T> iterable, Predicate<? super T> predicate) {
        int index = 0;
        for(T i : iterable) {
            if(predicate.test(i))
                return index;
            index++;
        }
        return -1;
    }

    public static <F, T> Iterable<T> transform(final Iterable<F> iterable, final Function<? super F, T> function) {
        //Should probably also be ? extends T in the function type
        return () -> StreamSupport.stream(iterable.spliterator(), false).map(function).iterator();
    }

    // any, all, find and the filter above make the calls a stream over iterable.spliterator() makes, as JDK 23
    // evaluates it, on the same spliterator and the same predicate, in the same order, without building the stream:
    // StreamSupport.stream checks the spliterator for null and asks its characteristics() (and its getComparator()
    // when they say SORTED); anyMatch, allMatch and filter check the predicate for null; a short-circuiting terminal
    // (anyMatch, allMatch, findFirst) asks getExactSizeIfKnown() and then calls tryAdvance until its sink has the
    // answer (AbstractPipeline.copyIntoWithCancel, ReferencePipeline.forEachWithCancel); a filter's iterator works
    // as Spliterators.iterator over StreamSpliterators.WrappingSpliterator (FilterIterator)

    private static <T> Spliterator<T> streamSource(final Iterable<T> iterable) {
        final Spliterator<T> source = Objects.requireNonNull(iterable.spliterator());
        if ((source.characteristics() & Spliterator.SORTED) != 0) {
            source.getComparator();
        }
        return source;
    }

    private static <T> boolean match(final Iterable<T> iterable, final Predicate<? super T> test, final boolean any) {
        final Spliterator<T> source = streamSource(iterable);
        final Match<T> sink = new Match<>(Objects.requireNonNull(test), any);
        source.getExactSizeIfKnown();
        while (!sink.stop && source.tryAdvance(sink)) {
            // the sink tests each element the source hands it
        }
        return sink.value;
    }

    private static <T> First<T> findFirst(final Iterable<T> iterable, final Predicate<? super T> predicate) {
        final Spliterator<T> source = streamSource(iterable);
        final First<T> sink = new First<>(Objects.requireNonNull(predicate));
        source.getExactSizeIfKnown();
        while (!sink.found && source.tryAdvance(sink)) {
            // the sink tests each element the source hands it
        }
        return sink;
    }

    // MatchOps' sink: anyMatch stops at the first element the test passes, allMatch at the first it fails
    private static final class Match<T> implements Consumer<T> {
        private final Predicate<? super T> test;
        private final boolean stopOn;
        private boolean stop;
        private boolean value;

        private Match(final Predicate<? super T> test, final boolean any) {
            this.test = test;
            this.stopOn = any;
            this.value = !any;
        }

        @Override
        public void accept(final T t) {
            if (!stop && test.test(t) == stopOn) {
                stop = true;
                value = stopOn;
            }
        }
    }

    // a filter's sink and FindOps' sink after it: the first element the predicate passes
    private static final class First<T> implements Consumer<T> {
        private final Predicate<? super T> predicate;
        private boolean found;
        private T value;

        private First(final Predicate<? super T> predicate) {
            this.predicate = predicate;
        }

        @Override
        public void accept(final T t) {
            if (predicate.test(t) && !found) {
                found = true;
                value = t;
            }
        }
    }

    // the iterator of a stream's filter: Spliterators.iterator's adapter over StreamSpliterators.WrappingSpliterator,
    // which advances the source only when asked for an element and keeps what the filter passed in a buffer
    private static final class FilterIterator<T> implements Iterator<T>, Consumer<T> {
        private final Spliterator<T> source;
        private final Predicate<? super T> filter;
        // WrappingSpliterator's: the buffer (none before the first advance), how many it holds, the one handed on
        // last, and whether the source has said it has no more
        private Object[] buffer;
        private int count;
        private int nextToConsume;
        private boolean finished;
        // the adapter's: an element taken from the buffer that next() has not returned yet
        private boolean valueReady;
        private T nextElement;

        private FilterIterator(final Iterable<T> iterable, final Predicate<? super T> filter) {
            this.source = streamSource(iterable);
            this.filter = Objects.requireNonNull(filter);
        }

        // the filter's sink, in front of the buffer
        @Override
        public void accept(final T t) {
            if (filter.test(t)) {
                if (count == buffer.length) {
                    buffer = Arrays.copyOf(buffer, count * 2);
                }
                buffer[count++] = t;
            }
        }

        // WrappingSpliterator.doAdvance: whether buffer[nextToConsume] is the next element
        private boolean doAdvance() {
            if (buffer == null) {
                if (finished) {
                    return false;
                }
                buffer = new Object[1];
                nextToConsume = 0;
                source.getExactSizeIfKnown();
                return fillBuffer();
            }
            ++nextToConsume;
            if (nextToConsume < count) {
                return true;
            }
            nextToConsume = 0;
            count = 0;
            return fillBuffer();
        }

        // WrappingSpliterator.fillBuffer: the source is asked once more after it first says it has no more
        private boolean fillBuffer() {
            while (count == 0) {
                if (!source.tryAdvance(this)) {
                    if (finished) {
                        return false;
                    }
                    finished = true;
                }
            }
            return true;
        }

        @SuppressWarnings("unchecked")
        private T buffered() {
            return (T) buffer[nextToConsume];
        }

        @Override
        public boolean hasNext() {
            if (!valueReady && doAdvance()) {
                valueReady = true;
                nextElement = buffered();
            }
            return valueReady;
        }

        @Override
        public T next() {
            if (!valueReady && !hasNext()) {
                throw new NoSuchElementException();
            }
            valueReady = false;
            final T t = nextElement;
            nextElement = null;
            return t;
        }

        @Override
        public void forEachRemaining(final Consumer<? super T> action) {
            Objects.requireNonNull(action);
            if (valueReady) {
                valueReady = false;
                final T t = nextElement;
                nextElement = null;
                action.accept(t);
            }
            if (buffer == null && !finished) {
                // WrappingSpliterator.forEachRemaining before any advance: the source walks itself through the filter
                source.getExactSizeIfKnown();
                source.forEachRemaining(t -> {
                    if (filter.test(t)) {
                        action.accept(t);
                    }
                });
                finished = true;
            } else {
                while (doAdvance()) {
                    action.accept(buffered());
                }
            }
        }
    }
}
