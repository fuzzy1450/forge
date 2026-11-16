package forge.game.staticability;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectOutputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import java.util.function.Supplier;

import forge.game.Game;
import forge.game.card.Card;
import forge.game.player.Player;
import forge.game.zone.PlayerZone;
import forge.game.zone.PlayerZoneBattlefield;
import forge.game.zone.ZoneType;
import forge.util.SimScope;

/**
 * The test side of {@link StaticAbilityModeProbe}: every skipped scan is checked against the scan it stands for, and
 * every direct visit order against the CardCollection order it replaced. A mismatch is recorded, never thrown, so a
 * game the probe watches plays on; the caller asserts {@link #mismatches} is empty.
 *
 * <p>For a skip (on a thread with a bound, uncancelled SimScope): the seven SimScope id counters, the game's card id
 * counter and the scope's random stream (the object, and its state as Java serialization writes it, which draws
 * nothing) are read; the skipped scan runs and must come to the skip's answer; every static ability of every card it
 * visits (the zone snapshot and the extra cards), and on every {@code fullWalkEvery}-th skip of every card in every zone
 * of the game, must have every mode inside the game's flags, and each of those cards must be a card of the game; then
 * none of the counters and not the stream may have moved. Without a bound scope the counters cannot be read: the
 * answer and the flags are checked, and the skip counts as unscoped.</p>
 */
public final class ModeProbeChecker implements StaticAbilityModeProbe.Listener {
    public final AtomicLong skips = new AtomicLong();
    public final AtomicLong scopedSkips = new AtomicLong();
    public final AtomicLong unscopedSkips = new AtomicLong();
    public final AtomicLong fullWalks = new AtomicLong();
    public final AtomicLong visitCalls = new AtomicLong();
    public final AtomicLong visitCallsWithMatch = new AtomicLong();
    public final List<String> mismatches = Collections.synchronizedList(new ArrayList<>());
    private final int fullWalkEvery;
    private final ThreadLocal<Deque<List<Card>>> frames = ThreadLocal.withInitial(ArrayDeque::new);

    public ModeProbeChecker(final int fullWalkEvery) {
        this.fullWalkEvery = fullWalkEvery;
    }

    public void install() {
        StaticAbilityModeProbe.setListener(this);
    }

    public static void uninstall() {
        StaticAbilityModeProbe.setListener(null);
    }

    // what the probe saw of the flags themselves (counts by a key naming the event and the kind of game)
    public final Map<String, AtomicLong> saturations = new ConcurrentSkipListMap<>();
    public final Map<String, AtomicLong> newModesAfterTurnOne = new ConcurrentSkipListMap<>();
    public final Map<String, AtomicLong> fullScans = new ConcurrentSkipListMap<>();
    // the checks themselves ask the flags: those answers are not the game's
    private final ThreadLocal<int[]> checking = ThreadLocal.withInitial(() -> new int[1]);

    private static String kind(final Game game) {
        return game.isSimulationCopy() ? "rollout copy" : "real game";
    }

    private static void count(final Map<String, AtomicLong> map, final String key) {
        map.computeIfAbsent(key, k -> new AtomicLong()).incrementAndGet();
    }

    @Override
    public void mayHave(final Game game, final StaticAbilityMode mode, final boolean saturated) {
        if (checking.get()[0] == 0) {
            count(fullScans, (saturated ? "saturated" : "mode noted") + ", " + kind(game));
        }
    }

    @Override
    public void noted(final Game game, final Set<StaticAbilityMode> added) {
        if (game.getPhaseHandler().getTurn() >= 1) {
            for (final StaticAbilityMode m : added) {
                count(newModesAfterTurnOne, m + ", " + kind(game));
            }
        }
    }

    @Override
    public void saturated(final Game game, final Object cause) {
        count(saturations, (cause instanceof ZoneType ? "R6 card of another game in " + cause : String.valueOf(cause))
                + ", " + kind(game) + ", turn " + game.getPhaseHandler().getTurn());
    }

    public String flagCounts() {
        return "saturations=" + saturations + " | new modes after turn 1 began=" + newModesAfterTurnOne
                + " | fast-path-eligible calls that scanned=" + fullScans;
    }

    private void mismatch(final String what) {
        if (mismatches.size() < 200) {
            mismatches.add(what);
        }
    }

    @Override
    public void skipped(final Game game, final Set<StaticAbilityMode> modes, final List<Card> extraCards,
            final BooleanSupplier scanFindsNothing) {
        final long n = skips.incrementAndGet();
        checking.get()[0]++;
        try {
            checkSkip(game, modes, extraCards, scanFindsNothing, n);
        } finally {
            checking.get()[0]--;
        }
    }

    private void checkSkip(final Game game, final Set<StaticAbilityMode> modes, final List<Card> extraCards,
            final BooleanSupplier scanFindsNothing, final long n) {
        for (final StaticAbilityMode m : modes) {
            if (game.mayHaveStaticAbilityMode(m)) {
                mismatch("skipped a scan for " + m + ", which the game may have");
            }
        }
        final SimScope scope = SimScope.current();
        if (scope == null || scope.isCancelled()) {
            unscopedSkips.incrementAndGet();
            checkScan(game, modes, extraCards, scanFindsNothing, n);
            return;
        }
        final int[] before = counters(scope, game);
        final Random random = scope.random();
        final byte[] randomBefore = state(random);
        checkScan(game, modes, extraCards, scanFindsNothing, n);
        final int[] after = counters(scope, game);
        if (!Arrays.equals(before, after)) {
            mismatch("the skipped scan for " + modes + " moved the id counters " + Arrays.toString(before) + " -> "
                    + Arrays.toString(after));
        }
        if (scope.isCancelled()) {
            return; // the game was abandoned meanwhile: its stream can no longer be read
        }
        if (scope.random() != random || !Arrays.equals(state(random), randomBefore)) {
            mismatch("the skipped scan for " + modes + " moved the random stream");
        }
        scopedSkips.incrementAndGet();
    }

    private void checkScan(final Game game, final Set<StaticAbilityMode> modes, final List<Card> extraCards,
            final BooleanSupplier scanFindsNothing, final long n) {
        if (!scanFindsNothing.getAsBoolean()) {
            mismatch("the skipped scan for " + modes + " finds something");
        }
        for (final Card c : game.getCardsIn(ZoneType.STATIC_ABILITIES_SOURCE_ZONES)) {
            checkCard(game, c, "a zone snapshot card");
        }
        for (final Card c : extraCards) {
            checkCard(game, c, "an extra card");
        }
        if (fullWalkEvery > 0 && n % fullWalkEvery == 0) {
            fullWalks.incrementAndGet();
            for (final Player p : game.getPlayers()) {
                for (final ZoneType zt : ZoneType.values()) {
                    final PlayerZone z = p.getZone(zt);
                    if (z == null) {
                        continue;
                    }
                    for (final Card c : z.getCards(false)) {
                        checkCard(game, c, zt + " card");
                    }
                    if (z instanceof PlayerZoneBattlefield bf) {
                        for (final Card c : bf.getMeldedCards()) {
                            checkCard(game, c, "melded card");
                        }
                    }
                }
                for (final Card c : p.getInboundTokens()) {
                    checkCard(game, c, "inbound token");
                }
            }
            for (final Card c : game.getStackZone().getCards()) {
                checkCard(game, c, "stack card");
            }
        }
    }

    private void checkCard(final Game game, final Card c, final String what) {
        if (c.getGame() != game) {
            mismatch(what + " " + c + " is a card of another game");
        }
        for (final StaticAbility st : c.getStaticAbilities()) {
            if (st.getMode() == null) {
                mismatch(what + " " + c + " has a static ability without a mode");
                continue;
            }
            for (final StaticAbilityMode m : st.getMode()) {
                if (!game.mayHaveStaticAbilityMode(m)) {
                    mismatch(what + " " + c + " has a static ability of " + m + ", which the game never noted");
                }
            }
        }
    }

    private static int[] counters(final SimScope scope, final Game game) {
        final SimScope.Counter[] all = SimScope.Counter.values();
        final int[] result = new int[all.length + 1];
        for (int i = 0; i < all.length; i++) {
            result[i] = scope.peek(all[i]);
        }
        result[all.length] = game.getCardIdCounter();
        return result;
    }

    private static byte[] state(final Random random) {
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream out = new ObjectOutputStream(bytes)) {
            out.writeObject(random);
        } catch (final IOException e) {
            throw new IllegalStateException("the scope's random stream does not serialize", e);
        }
        return bytes.toByteArray();
    }

    @Override
    public <T> T visits(final List<Card> collectionOrder, final Supplier<T> scan, final Predicate<T> noMatch) {
        final Deque<List<Card>> stack = frames.get();
        final List<Card> visited = new ArrayList<>();
        stack.push(visited);
        final T result;
        try {
            result = scan.get();
        } finally {
            stack.pop();
        }
        visitCalls.incrementAndGet();
        final boolean full = noMatch.test(result);
        if (!full) {
            visitCallsWithMatch.incrementAndGet();
        }
        final List<Card> order = new ArrayList<>(collectionOrder);
        final boolean same = full ? sameCards(visited, order)
                : visited.size() <= order.size() && sameCards(visited, order.subList(0, visited.size()));
        if (!same) {
            mismatch("visited " + visited + " where the collection held " + order + (full ? "" : " (a match ended it)"));
        }
        return result;
    }

    private static boolean sameCards(final List<Card> a, final List<Card> b) {
        if (a.size() != b.size()) {
            return false;
        }
        for (int i = 0; i < a.size(); i++) {
            if (a.get(i) != b.get(i)) {
                return false;
            }
        }
        return true;
    }

    @Override
    public void visited(final Card c) {
        final List<Card> top = frames.get().peek();
        if (top == null) {
            mismatch("a card was visited outside a scan: " + c);
            return;
        }
        top.add(c);
    }

    public String summary() {
        return "skips=" + skips.get() + " scoped=" + scopedSkips.get() + " unscoped=" + unscopedSkips.get()
                + " fullWalks=" + fullWalks.get() + " visitCalls=" + visitCalls.get() + " (ended by a match "
                + visitCallsWithMatch.get() + ") mismatches=" + mismatches.size();
    }
}
