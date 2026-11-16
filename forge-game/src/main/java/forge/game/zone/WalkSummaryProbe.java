package forge.game.zone;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectOutputStream;
import java.util.Arrays;
import java.util.Random;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.BooleanSupplier;

import forge.game.Game;
import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.util.SimScope;
import forge.util.Visitor;

/**
 * A test seam for the replacement walk's summaries (Zone#visitSummarized). It is off in every production launch: only
 * a test turns it on, through {@link #start}, before its games start, and off through {@link #stop}. While it is off
 * nothing here runs but a flag read once per walk, per zone walk, per summary built or dropped and per counter epoch
 * move, never per card.
 *
 * <p>While it is on, Zone#visitSummarized does not jump over the cards its summary passes: it steps through every
 * position, and at each one it would pass it checks, right then, that the card is known to have no replacement effect
 * for the walk's events (so the visit would have returned at once), that the card is marked with the zone and is a
 * card of its game, and around each stretch of passed cards that no id counter of the bound SimScope and no card id
 * of the game moved and that the scope's random stream is the same object in the same state. It counts what it saw.
 * Turning it on changes no game: the same cards are visited and passed. With {@code plain} it passes no card at all
 * (the walk as forEachCardInGame makes it), as a reference run.</p>
 */
public final class WalkSummaryProbe {
    static boolean verify = false;
    static boolean plain = false;

    static final LongAdder WALKS = new LongAdder();
    static final LongAdder ZONE_WALKS = new LongAdder();
    /** Zone walks by what they found first: a summary that held, or none kept (one that no longer held is ended). */
    static final LongAdder START_VALID = new LongAdder();
    static final LongAdder START_NONE = new LongAdder();
    static final LongAdder VISITED = new LongAdder();
    static final LongAdder SKIPPED = new LongAdder();
    static final LongAdder BUILDS = new LongAdder();
    /** Summaries ended, [0] in real games, [1] in simulation copies: dropped by a card's write, found with the list
     *  changed, found with the counter epoch moved. */
    static final LongAdder[] DROPPED = { new LongAdder(), new LongAdder() };
    static final LongAdder[] ENDED_VERSION = { new LongAdder(), new LongAdder() };
    static final LongAdder[] ENDED_EPOCH = { new LongAdder(), new LongAdder() };
    /** Counter epoch moves, [real game, simulation copy], by whether the card that got its first counter is the very
     *  object its last known zone's list holds, or a detached one (an LKI or other copy). */
    static final LongAdder[] BUMPS_IN_ZONE = { new LongAdder(), new LongAdder() };
    static final LongAdder[] BUMPS_DETACHED = { new LongAdder(), new LongAdder() };
    static final LongAdder STRETCHES = new LongAdder();
    static final LongAdder STRETCHES_SCOPED = new LongAdder();
    static final LongAdder STRETCHES_UNSCOPED = new LongAdder();
    static final LongAdder FAILURES = new LongAdder();
    static final AtomicReference<String> FIRST_FAILURE = new AtomicReference<>();

    private WalkSummaryProbe() {
    }

    /** Clears the counts and turns the checks on; with {@code plainWalk}, no card is passed. */
    public static void start(final boolean plainWalk) {
        for (LongAdder a : all()) {
            a.reset();
        }
        FIRST_FAILURE.set(null);
        plain = plainWalk;
        verify = true;
    }

    /** Turns the checks off (the counts stay readable). */
    public static void stop() {
        verify = false;
        plain = false;
    }

    public static long walks() {
        return WALKS.sum();
    }

    public static long skipped() {
        return SKIPPED.sum();
    }

    public static long failures() {
        return FAILURES.sum();
    }

    public static String firstFailure() {
        return FIRST_FAILURE.get();
    }

    private static LongAdder[] all() {
        return new LongAdder[] { WALKS, ZONE_WALKS, START_VALID, START_NONE, VISITED, SKIPPED, BUILDS, DROPPED[0],
                DROPPED[1], ENDED_VERSION[0], ENDED_VERSION[1], ENDED_EPOCH[0], ENDED_EPOCH[1], BUMPS_IN_ZONE[0],
                BUMPS_IN_ZONE[1], BUMPS_DETACHED[0], BUMPS_DETACHED[1], STRETCHES, STRETCHES_SCOPED,
                STRETCHES_UNSCOPED, FAILURES };
    }

    private static String pair(final LongAdder[] a) {
        return a[0].sum() + "/" + a[1].sum();
    }

    /** The counts; each pair is real games / simulation copies. */
    public static String counts() {
        return "walks=" + WALKS.sum() + " zoneWalks=" + ZONE_WALKS.sum() + " startValid=" + START_VALID.sum()
                + " startNone=" + START_NONE.sum() + " visited=" + VISITED.sum() + " skipped=" + SKIPPED.sum()
                + " builds=" + BUILDS.sum() + " endedByDrop=" + pair(DROPPED) + " endedByVersion="
                + pair(ENDED_VERSION) + " endedByEpoch=" + pair(ENDED_EPOCH) + " bumpsInZone=" + pair(BUMPS_IN_ZONE)
                + " bumpsDetached=" + pair(BUMPS_DETACHED) + " stretches=" + STRETCHES.sum() + " stretchesScoped="
                + STRETCHES_SCOPED.sum() + " stretchesUnscoped=" + STRETCHES_UNSCOPED.sum() + " failures="
                + FAILURES.sum();
    }

    private static int kind(final Game game) {
        return game != null && game.isSimulationCopy() ? 1 : 0;
    }

    static void fail(final String what) {
        FAILURES.increment();
        FIRST_FAILURE.compareAndSet(null, what);
    }

    /** Game#forEachCardInGameSummarized, once per walk. */
    public static void noteWalk() {
        if (verify) {
            WALKS.increment();
        }
    }

    /** Card#countersBecomeNonEmpty, after it moved {@code game}'s counter epoch. */
    public static void noteEpochBump(final Card c, final Game game) {
        if (!verify) {
            return;
        }
        final Zone z = c.getLastKnownZone();
        final boolean inZone = z != null && z.game == game && z.holdsObject(c);
        (inZone ? BUMPS_IN_ZONE : BUMPS_DETACHED)[kind(game)].increment();
    }

    static void noteDropped(final Zone zone) {
        DROPPED[kind(zone.game)].increment();
    }

    static void noteBuild(final Zone zone) {
        BUILDS.increment();
    }

    static void noteValid(final Zone zone) {
        START_VALID.increment();
    }

    static void noteNone(final Zone zone) {
        START_NONE.increment();
    }

    static void noteInvalid(final Zone zone, final Zone.WalkSummary kept, final int listVersion) {
        (kept.version != listVersion ? ENDED_VERSION : ENDED_EPOCH)[kind(zone.game)].increment();
    }

    /** The id counters and the random stream of the bound scope, and the game's card id, read without stepping or
     *  drawing. */
    private static final class ScopeState {
        final SimScope scope;
        final int[] ids;
        final int cardId;
        final Random random;
        final byte[] randomState;

        ScopeState(final SimScope scope, final Game game) {
            this.scope = scope;
            final SimScope.Counter[] all = SimScope.Counter.values();
            ids = new int[all.length];
            for (int i = 0; i < all.length; i++) {
                ids[i] = scope.peek(all[i]);
            }
            cardId = game.getCardIdCounter();
            random = scope.random();
            randomState = serialize(random);
        }

        /** Null when no scope is bound, or a cancelled one (whose random stream throws). */
        static ScopeState read(final Game game) {
            final SimScope scope = SimScope.current();
            if (scope == null || scope.isCancelled()) {
                return null;
            }
            return new ScopeState(scope, game);
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
            if (cardId != after.cardId) {
                fail("a card id was taken across a passed stretch in " + zone + ": " + cardId + " -> "
                        + after.cardId);
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
                    before = ScopeState.read(zone.game);
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
            before.checkUnmoved(ScopeState.read(zone.game), zone);
        } else {
            STRETCHES_UNSCOPED.increment();
        }
    }
}
