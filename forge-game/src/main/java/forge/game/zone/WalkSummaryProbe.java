package forge.game.zone;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectOutputStream;
import java.util.Arrays;
import java.util.Random;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.BooleanSupplier;

import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.util.SimScope;
import forge.util.Visitor;

/**
 * A check of the replacement walk's summaries for tests, off unless a test turns it on (before its games start).
 * With {@link #verify} on, Zone#visitSummarized does not jump over the cards its summary passes: it steps through
 * every position, and at each one it would pass it checks, right then, that the card is known to have no
 * replacement effect for the walk's events (so the visit would have returned at once), that the card is marked with
 * the zone and is a card of its game, and around each stretch of passed cards that no id counter of the bound
 * SimScope moved and that its random stream is the same object in the same state. It counts what it saw. Turning it
 * on changes no game: the same cards are visited and passed.
 */
public final class WalkSummaryProbe {
    /** Read by the walk; set by a test before any game it checks starts. */
    public static boolean verify = false;
    /** With verify on, pass no card: every card is visited, as forEachCardInGame visits it (a reference run). */
    public static boolean plain = false;

    public static final LongAdder WALKS = new LongAdder();
    public static final LongAdder ZONE_WALKS = new LongAdder();
    /** Zone walks that found a summary that holds, and the summary usable, at their first card. */
    public static final LongAdder ZONE_WALKS_SUMMARIZED = new LongAdder();
    public static final LongAdder VISITED = new LongAdder();
    public static final LongAdder SKIPPED = new LongAdder();
    public static final LongAdder BUILDS = new LongAdder();
    /** Summaries dropped by a card's write (Card#walkInputsChanged). */
    public static final LongAdder DROPPED = new LongAdder();
    /** Zone walks that started without a summary that holds: none kept, the list changed, the counter epoch moved. */
    public static final LongAdder START_NONE = new LongAdder();
    public static final LongAdder START_VERSION = new LongAdder();
    public static final LongAdder START_EPOCH = new LongAdder();
    public static final LongAdder STRETCHES = new LongAdder();
    /** Stretches checked against a bound scope, and those that ran with none bound (only the cards were checked). */
    public static final LongAdder STRETCHES_SCOPED = new LongAdder();
    public static final LongAdder STRETCHES_UNSCOPED = new LongAdder();
    public static final LongAdder FAILURES = new LongAdder();
    public static final AtomicReference<String> FIRST_FAILURE = new AtomicReference<>();

    private WalkSummaryProbe() {
    }

    public static void reset() {
        for (LongAdder a : new LongAdder[] { WALKS, ZONE_WALKS, ZONE_WALKS_SUMMARIZED, VISITED, SKIPPED, BUILDS,
                DROPPED, START_NONE, START_VERSION, START_EPOCH, STRETCHES, STRETCHES_SCOPED, STRETCHES_UNSCOPED,
                FAILURES }) {
            a.reset();
        }
        FIRST_FAILURE.set(null);
    }

    public static String counts() {
        return "walks=" + WALKS.sum() + " zoneWalks=" + ZONE_WALKS.sum() + " zoneWalksSummarized="
                + ZONE_WALKS_SUMMARIZED.sum() + " visited=" + VISITED.sum() + " skipped=" + SKIPPED.sum()
                + " builds=" + BUILDS.sum() + " dropped=" + DROPPED.sum() + " startNone=" + START_NONE.sum()
                + " startVersion=" + START_VERSION.sum() + " startEpoch=" + START_EPOCH.sum() + " stretches="
                + STRETCHES.sum() + " stretchesScoped=" + STRETCHES_SCOPED.sum() + " stretchesUnscoped="
                + STRETCHES_UNSCOPED.sum() + " failures=" + FAILURES.sum();
    }

    static void fail(final String what) {
        FAILURES.increment();
        FIRST_FAILURE.compareAndSet(null, what);
    }

    static void noteInvalid(final Zone.WalkSummary kept, final int listVersion) {
        if (kept == null) {
            START_NONE.increment();
        } else if (kept.version != listVersion) {
            START_VERSION.increment();
        } else {
            START_EPOCH.increment();
        }
    }

    /** The id counters and the random stream of the bound scope, read without stepping or drawing. */
    private static final class ScopeState {
        final SimScope scope;
        final int[] ids;
        final Random random;
        final byte[] randomState;

        ScopeState(final SimScope scope) {
            this.scope = scope;
            final SimScope.Counter[] all = SimScope.Counter.values();
            ids = new int[all.length];
            for (int i = 0; i < all.length; i++) {
                ids[i] = scope.peek(all[i]);
            }
            random = scope.random();
            randomState = serialize(random);
        }

        /** Null when no scope is bound, or a cancelled one (whose random stream throws). */
        static ScopeState read() {
            final SimScope scope = SimScope.current();
            if (scope == null || scope.isCancelled()) {
                return null;
            }
            return new ScopeState(scope);
        }

        void checkUnmoved(final ScopeState after, final Zone zone) {
            if (after == null || after.scope != scope) {
                fail("scope changed across a passed stretch in " + zone);
                return;
            }
            if (!Arrays.equals(ids, after.ids)) {
                fail("an id counter moved across a passed stretch in " + zone + ": " + Arrays.toString(ids) + " -> "
                        + Arrays.toString(after.ids));
            }
            if (random != after.random || !Arrays.equals(randomState, after.randomState)) {
                fail("the random stream moved across a passed stretch in " + zone);
            }
        }

        private static byte[] serialize(final Random r) {
            // java.util.Random writes its seed: no number is drawn
            try {
                final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                try (ObjectOutputStream out = new ObjectOutputStream(bytes)) {
                    out.writeObject(r);
                }
                return bytes.toByteArray();
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }
    }

    /** Zone#visitSummarized's loop, one position at a time, checking every card it passes. */
    static boolean visitLockStep(final Zone zone, final CardCollection list, final Visitor<Card> visitor,
            final long events, final BooleanSupplier summaryUsable) {
        ZONE_WALKS.increment();
        if (!list.isEmpty() && zone.validWalkSummary() != null
                && (summaryUsable == null || summaryUsable.getAsBoolean())) {
            ZONE_WALKS_SUMMARIZED.increment();
        }
        boolean inStretch = false;
        boolean scoped = false;
        ScopeState before = null;
        int i = 0;
        while (i < list.size()) {
            final Zone.WalkSummary s = plain ? null : zone.validWalkSummary();
            boolean pass = false;
            if (s != null && (summaryUsable == null || summaryUsable.getAsBoolean())) {
                final int k = Zone.firstAtOrAfter(s.index, i);
                pass = k == s.index.length || s.index[k] != i || (s.events[k] & events) == 0;
            }
            if (pass) {
                if (!inStretch) {
                    inStretch = true;
                    before = ScopeState.read();
                    scoped = before != null;
                }
                final Card c = list.get(i);
                if ((c.knownReplacementEvents() & events) != 0) {
                    fail("passed " + c + " at " + i + " in " + zone + " though it may have an effect for events "
                            + Long.toHexString(events) + " (known " + Long.toHexString(c.knownReplacementEvents())
                            + ")");
                }
                if (c.getWalkSummaryZone() != zone) {
                    fail("passed " + c + " at " + i + " in " + zone + " though it is marked "
                            + c.getWalkSummaryZone());
                }
                if (c.getGame() != zone.game) {
                    fail("passed " + c + " at " + i + " in " + zone + " though it is a card of another game");
                }
                SKIPPED.increment();
                i++;
                continue;
            }
            if (inStretch) {
                endStretch(zone, before, scoped);
                inStretch = false;
            }
            VISITED.increment();
            if (!visitor.visit(list.get(i))) {
                return false;
            }
            i++;
        }
        if (inStretch) {
            endStretch(zone, before, scoped);
        }
        return true;
    }

    private static void endStretch(final Zone zone, final ScopeState before, final boolean scoped) {
        STRETCHES.increment();
        if (scoped) {
            STRETCHES_SCOPED.increment();
            before.checkUnmoved(ScopeState.read(), zone);
        } else {
            STRETCHES_UNSCOPED.increment();
        }
    }
}
