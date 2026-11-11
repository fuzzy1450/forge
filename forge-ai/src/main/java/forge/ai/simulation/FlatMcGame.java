package forge.ai.simulation;

import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import forge.game.Game;
import forge.game.player.Player;
import forge.util.SimScope;

/**
 * One game's Flat Monte Carlo state, kept in the scratch of the game's {@link SimScope} (MTG_DeckMaker's
 * tools/combo-ai/flat-mc/SPEC.md, sections 4.3, 4.4, 4.6 and 4.7): the counted clock, the bracket around every rollout
 * with its cap on stack resolutions and its check of the real game, one {@link Tally} per searching seat for the
 * game's record with the hash of the seat's rollouts for the game's digest, and the game's wall-clock figures.
 *
 * <p>Every clock read in {@link FlatMonteCarloPicker} goes through {@link #nanoTime()}. Under a bound scope it reads
 * this clock, which moves only when a rollout starts, by {@link #CHARGE_NANOS} each time, so the profile's decision and
 * turn budgets count rollouts, whatever the machine's speed. With no scope bound, as in the GUI, it reads the machine's
 * clock, and nothing is counted, capped, checked or hashed.
 *
 * <p>The wall-clock figures, {@link #slowestRolloutNanos()} and {@link #rolloutsOver5s()}, are the one thing here read
 * from the machine's clock, and only the game's stderr line reads them: never the tally, the hash or a decision, so a
 * machine's speed cannot reach a comparison.
 *
 * <p>Like the scope, this is used by one game thread at a time (an AI eval thread adopts the scope while the game
 * thread waits for it), so nothing here is synchronized.
 */
public final class FlatMcGame {
    /** What one rollout costs on the counted clock: one virtual millisecond. */
    public static final long CHARGE_NANOS = 1_000_000L;

    /** A rollout that takes longer than this on the machine's clock is counted in {@link #rolloutsOver5s()}. */
    private static final long SLOW_ROLLOUT_NANOS = TimeUnit.SECONDS.toNanos(5);

    /** The most bytes one {@link #fold} adds to its seat's hash: the decision, the line and the round, the value's
     *  mark and the value, the rollout's plays and resolutions, the scope's id counters and the final draw. */
    private static final int FOLD_BYTES = Long.BYTES + 2 * Integer.BYTES + 1 + Long.BYTES + 2 * Long.BYTES
            + SimScope.Counter.values().length * Integer.BYTES + Long.BYTES;

    /** The keys of {@link Tally#toRecord()}, in its order: the {@code flat_mc} record key holds one per seat. */
    public static final List<String> TALLY_KEYS = List.of("seat", "decisions", "rollouts", "playouts", "plays",
            "resolutions", "cards_copied", "max_decision_rollouts", "max_decision_lines", "reasons", "invalid_lines",
            "rollout_failures", "decision_budget_stops", "turn_budget_stops", "game_cap_stops", "game_cap_turn",
            "resolution_cap_stops", "playouts_at_play_limit", "tutor_storm", "hash");

    /** Why a searching seat's controller got no configuration for a decision, which the heuristics then make alone. */
    public enum NullCause {
        /** The profile gives a decision no budget, so the seat never searches; a refusal for it is no stop. */
        NO_BUDGET,
        /** The seat has spent its budget for this turn. */
        TURN_BUDGET,
        /** The seat's rollouts have reached its word's cap for the game. */
        GAME_CAP
    }

    /**
     * One searching seat's counts over its game, as the {@code flat_mc} record carries them (spec 4.7). The fields are
     * the record's keys in camel case; only this game's state writes them. Nothing here reads the machine's clock, so
     * no machine figure can reach the record.
     */
    public static final class Tally {
        /** The seat's place at the table, as {@link #registerSeat} gave it; -1 until then. */
        public int seat = -1;
        /** Decisions the seat's search made: each picker result and each zero-rollout result. */
        public long decisions;
        /** Rollouts charged to the seat: one for each started, whether or not its line gets a value. */
        public long rollouts;
        /** Playouts in those rollouts. */
        public long playouts;
        /** Plays the heuristics made in those playouts. */
        public long plays;
        /** Stack resolutions counted in those rollouts: each entry to a resolver and each pass of its loop. */
        public long resolutions;
        /** The cards in each rollout's copy of the game, summed over the rollouts: it follows the board's size. */
        public long cardsCopied;
        /** The most rollouts one decision charged. */
        public long maxDecisionRollouts;
        /** The most lines one decision evaluated. */
        public long maxDecisionLines;
        /** How many decisions ended with each reason (override, kept-heuristic, hold, ...). */
        public final TreeMap<String, Long> reasons = new TreeMap<>();
        /** Rollouts that ended without a value, which leaves their line out of the decision. */
        public long invalidLines;
        /** Rollouts that ended in an unexpected RuntimeException. */
        public long rolloutFailures;
        /** Decisions the decision budget ended before their rounds ran out. */
        public long decisionBudgetStops;
        /** Main-phase decisions refused because the seat's turn budget was spent. */
        public long turnBudgetStops;
        /** Main-phase decisions refused because the seat's rollouts had reached its cap for the game. */
        public long gameCapStops;
        /** The turn of the first refusal by the cap for the game; null until the cap first refuses. */
        public Integer gameCapTurn;
        /** Rollouts the resolution cap ended; each leaves its line without a value. */
        public long resolutionCapStops;
        /** Playouts that ended at their play limit. */
        public long playoutsAtPlayLimit;
        /** Whether the seat's deck reads as tutor storm, whose copies hold storm pieces the real seat does not. */
        public boolean tutorStorm;

        /** {@link #rollouts} when the seat's previous decision was noted. */
        private long rolloutsAtLastDecision;
        /** The seat's folds: the record's hash is its SHA-256. */
        private final MessageDigest folds = sha256();

        private Tally() {
        }

        /**
         * The seat's record: {@link #TALLY_KEYS} in order, every value a count, a boolean, a map of counts or the
         * 64-hex SHA-256 of the seat's folds; {@code game_cap_turn} is null until the cap first refuses.
         */
        public Map<String, Object> toRecord() {
            final Map<String, Object> rec = new LinkedHashMap<>();
            rec.put("seat", seat);
            rec.put("decisions", decisions);
            rec.put("rollouts", rollouts);
            rec.put("playouts", playouts);
            rec.put("plays", plays);
            rec.put("resolutions", resolutions);
            rec.put("cards_copied", cardsCopied);
            rec.put("max_decision_rollouts", maxDecisionRollouts);
            rec.put("max_decision_lines", maxDecisionLines);
            rec.put("reasons", new TreeMap<>(reasons));
            rec.put("invalid_lines", invalidLines);
            rec.put("rollout_failures", rolloutFailures);
            rec.put("decision_budget_stops", decisionBudgetStops);
            rec.put("turn_budget_stops", turnBudgetStops);
            rec.put("game_cap_stops", gameCapStops);
            rec.put("game_cap_turn", gameCapTurn);
            rec.put("resolution_cap_stops", resolutionCapStops);
            rec.put("playouts_at_play_limit", playoutsAtPlayLimit);
            rec.put("tutor_storm", tutorStorm);
            rec.put("hash", hex(folds));
            return rec;
        }
    }

    /** A rollout passed the resolution cap of its seat's word: its line is invalid (spec 4.4). */
    static final class ResolutionCapReached extends RuntimeException {
        private static final long serialVersionUID = 1L;

        ResolutionCapReached() {
            super("a rollout passed its resolution cap", null, false, false);
        }
    }

    /**
     * One rollout: whose it is, its stack resolutions against its cap (0 for none), its plays, and the last draw of its
     * private stream. Its figures are counted while it is open, and the picker's {@link #fold} reads them once the
     * bracket has closed it.
     */
    private static final class Rollout {
        final Tally tally;
        final int resolutionCap;
        long resolutions;
        long plays;
        long finalDraw;

        Rollout(final Tally tally, final int resolutionCap) {
            this.tally = tally;
            this.resolutionCap = resolutionCap;
        }
    }

    /** The scope whose scratch holds this state: a fold reads its id counters. */
    private final SimScope scope;
    private long clock;
    /** Each player's tally, by player id, made on first use. */
    private final Map<Integer, Tally> tallies = new HashMap<>();
    /** The searching seats' tallies by seat, as {@link #registerSeat} gave them: the search lines come in this
     *  order. */
    private final TreeMap<Integer, Tally> seats = new TreeMap<>();
    /** The rollout {@link #bracket} has open, null between rollouts. */
    private Rollout open;
    /** The rollout {@link #bracket} closed last, whose figures the picker's {@link #fold} reads; an empty one before
     *  the first. */
    private Rollout closed = new Rollout(null, 0);
    /** The slowest rollout of the game on the machine's clock, in nanoseconds: for the wall-clock figures alone. */
    private long slowestRolloutNanos;
    /** The game's rollouts that took more than 5 s on the machine's clock: for the wall-clock figures alone. */
    private int rolloutsOver5s;

    private FlatMcGame(final SimScope scope) {
        this.scope = scope;
    }

    /** The bound scope's state, made on first use; null when no scope is bound. */
    public static FlatMcGame current() {
        final SimScope scope = SimScope.current();
        return scope == null ? null : scope.scratch(FlatMcGame.class, () -> new FlatMcGame(scope));
    }

    /** {@code scope}'s state, or null when nothing has made it yet; it creates nothing. Another thread may read it once
     *  the game's thread is done with the scope. */
    public static FlatMcGame peek(final SimScope scope) {
        return scope.scratch(FlatMcGame.class, () -> null);
    }

    /** The picker's clock: the bound scope's counted clock, or {@link System#nanoTime()} when no scope is bound. */
    public static long nanoTime() {
        final FlatMcGame st = current();
        return st == null ? System.nanoTime() : st.clock;
    }

    /** The counted clock in virtual nanoseconds: {@link #CHARGE_NANOS} for every rollout charged so far. */
    public long clock() {
        return clock;
    }

    /** Player {@code playerId}'s tally, made on first use. */
    public Tally tally(final int playerId) {
        return tallies.computeIfAbsent(playerId, id -> new Tally());
    }

    /** Player {@code playerId} searches from seat {@code seat}: its tally carries the seat, and the game's search lines
     *  list it in seat order. Once per searching seat, before the game is played. */
    public void registerSeat(final int seat, final int playerId) {
        final Tally t = tally(playerId);
        t.seat = seat;
        seats.put(seat, t);
    }

    /** A rollout of player {@code playerId}'s search starts: the clock moves on by one charge, which the seat's tally
     *  counts. Nothing else moves the clock. */
    void charge(final int playerId) {
        clock += CHARGE_NANOS;
        tally(playerId).rollouts++;
    }

    /**
     * Player {@code playerId}'s search decided {@code r}: one decision with its reason, the rollouts it charged (those
     * charged since the seat's previous decision was noted; every search that charges is noted here but land choice
     * and the attack search, which the flat_mc profiles keep off) and the lines it evaluated.
     */
    public void noteDecision(final int playerId, final FlatMonteCarloPicker.Result r) {
        final Tally t = tally(playerId);
        t.decisions++;
        t.reasons.merge(r.reason, 1L, Long::sum);
        t.maxDecisionRollouts = Math.max(t.maxDecisionRollouts, t.rollouts - t.rolloutsAtLastDecision);
        t.rolloutsAtLastDecision = t.rollouts;
        t.maxDecisionLines = Math.max(t.maxDecisionLines, r.candidatesEvaluated);
    }

    /** Player {@code playerId}'s main-phase decision at turn {@code turn} got no configuration for {@code cause}: the
     *  turn budget and the cap for the game are stops and counted, the first cap's turn kept; no budget is not. */
    public void noteNullConfig(final int playerId, final NullCause cause, final int turn) {
        final Tally t = tally(playerId);
        switch (cause) {
            case TURN_BUDGET -> t.turnBudgetStops++;
            case GAME_CAP -> {
                t.gameCapStops++;
                if (t.gameCapTurn == null) {
                    t.gameCapTurn = turn;
                }
            }
            case NO_BUDGET -> {
                // the seat never searches: nothing was stopped
            }
        }
    }

    /** Whether player {@code playerId}'s deck reads as tutor storm; once it has, the tally keeps it. */
    public void noteTutorStorm(final int playerId, final boolean tutorStorm) {
        tally(playerId).tutorStorm |= tutorStorm;
    }

    /** One of player {@code playerId}'s decisions stopped at its decision budget. */
    void noteBudgetStop(final int playerId) {
        tally(playerId).decisionBudgetStops++;
    }

    /** One of player {@code playerId}'s rollouts ended in an unexpected RuntimeException. */
    void noteFailure(final int playerId) {
        tally(playerId).rolloutFailures++;
    }

    /** The open rollout passed its resolution cap; nothing when no rollout is open. */
    void noteResolutionCap() {
        if (open != null) {
            open.tally.resolutionCapStops++;
        }
    }

    /**
     * One rollout of {@code player}'s search: {@code body}'s value, null for a line that cannot be simulated. With no
     * scope bound, {@code body} alone. Under a bound scope the rollout is charged first, the real game is snapshotted
     * and the machine's clock read, and {@code body} then runs with the rollout open, so its playouts, plays, copied
     * cards and stack resolutions are counted and its resolutions capped at {@code resolutionCap} (0: no cap).
     *
     * <p>On every return from {@code body}, a catch arm's null included, the real game is checked against the snapshot
     * ({@link RealGameSnapshot#requireSame}, which throws {@link RolloutWroteRealGame}), then the rollout's real time
     * is kept for the wall-clock figures, a null value is counted as an invalid line, and the rollout is kept for the
     * picker's {@link #fold}. A throwable escaping {@code body} skips all of this: it is an Error that ends the game,
     * or the AI deadline, which no harness game reaches.
     */
    public static Long bracket(final Player player, final int resolutionCap, final Supplier<Long> body) {
        final FlatMcGame st = current();
        if (st == null) {
            return body.get();
        }
        st.charge(player.getId());
        final Game real = player.getGame();
        final RealGameSnapshot before = RealGameSnapshot.of(real);
        final long start = System.nanoTime(); // the machine's clock, outside the seam: for the wall-clock figures alone
        final Rollout rollout = new Rollout(st.tally(player.getId()), resolutionCap);
        final Rollout outer = st.open;
        st.open = rollout;
        final Long value;
        try {
            value = body.get();
        } finally {
            st.open = outer;
        }
        final long nanos = System.nanoTime() - start;
        before.requireSame(real);
        st.slowestRolloutNanos = Math.max(st.slowestRolloutNanos, nanos);
        if (nanos > SLOW_ROLLOUT_NANOS) {
            st.rolloutsOver5s++;
        }
        if (value == null) {
            rollout.tally.invalidLines++;
        }
        st.closed = rollout;
        return value;
    }

    /** The open rollout's private stream ends on {@code draw}, one last draw before the stream is thrown away, for the
     *  picker's {@link #fold}; nothing when no rollout is open. */
    void noteFinalDraw(final long draw) {
        if (open != null) {
            open.finalDraw = draw;
        }
    }

    /**
     * Folds the rollout {@link #bracket} closed last, player {@code playerId}'s rollout of line {@code line} in round
     * {@code round}, which gave {@code value} (null for none), into the seat's hash (spec 4.6): SHA-256 over the
     * big-endian encoding of the decision's number (the seat's decisions noted before this one), the line, the round,
     * a byte 0 for no value or 1 and the value, the rollout's plays and stack resolutions, the scope's seven id
     * counters in {@link SimScope.Counter} order, and the rollout's final private draw. So a rollout that wobbles
     * without moving a decision still moves the hash, which the game's search lines carry into its digest.
     */
    void fold(final int playerId, final int line, final int round, final Long value) {
        final Tally t = tally(playerId);
        final ByteBuffer b = ByteBuffer.allocate(FOLD_BYTES); // big-endian, as every ByteBuffer starts
        b.putLong(t.decisions).putInt(line).putInt(round);
        if (value == null) {
            b.put((byte) 0);
        } else {
            b.put((byte) 1).putLong(value);
        }
        b.putLong(closed.plays).putLong(closed.resolutions);
        for (final SimScope.Counter c : SimScope.Counter.values()) {
            b.putInt(scope.peek(c));
        }
        b.putLong(closed.finalDraw);
        t.folds.update(b.array(), 0, b.position());
    }

    /** Whether a seat of the game has started a rollout: only such a game has search lines (spec 4.6). */
    public boolean anyRollout() {
        for (final Tally t : tallies.values()) {
            if (t.rollouts > 0) {
                return true;
            }
        }
        return false;
    }

    /**
     * The game's search lines for its digest (spec 4.6), one per registered seat in seat order:
     * {@code search<TAB>seat<TAB>rollouts<TAB>playouts<TAB>plays<TAB>resolutions<TAB>hash}, the hash its record's.
     * None for a game with no rollout, which adds nothing to its digest, so a default seat's game is untouched.
     */
    public List<String> searchLines() {
        final List<String> lines = new ArrayList<>();
        if (!anyRollout()) {
            return lines;
        }
        for (final Tally t : seats.values()) {
            lines.add("search\t" + t.seat + "\t" + t.rollouts + "\t" + t.playouts + "\t" + t.plays + "\t"
                    + t.resolutions + "\t" + hex(t.folds));
        }
        return lines;
    }

    /** The slowest of the game's rollouts so far on the machine's clock, in nanoseconds (0 before the first): for the
     *  game's stderr line alone. */
    public long slowestRolloutNanos() {
        return slowestRolloutNanos;
    }

    /** How many of the game's rollouts so far took more than 5 s on the machine's clock: for the game's stderr line
     *  alone. */
    public int rolloutsOver5s() {
        return rolloutsOver5s;
    }

    /**
     * A stack resolution in the open rollout (GameSimulator's resolvers call this at their entry and on each pass of
     * their loops): counted, or refused with {@link ResolutionCapReached} once the rollout's count would pass its cap,
     * and on every call after, so a refusal that something catches cannot let the rollout resolve on. Nothing outside
     * a rollout, so hybrid seats' simulations are untouched.
     */
    static void countResolution() {
        final Rollout r = openRollout();
        if (r == null) {
            return;
        }
        if (r.resolutionCap > 0 && r.resolutions >= r.resolutionCap) {
            throw new ResolutionCapReached();
        }
        r.resolutions++;
        r.tally.resolutions++;
    }

    /** A playout starts in the open rollout; nothing outside a rollout. */
    static void notePlayout() {
        final Rollout r = openRollout();
        if (r != null) {
            r.tally.playouts++;
        }
    }

    /** A playout of the open rollout made a play; nothing outside a rollout. */
    static void notePlay() {
        final Rollout r = openRollout();
        if (r != null) {
            r.plays++;
            r.tally.plays++;
        }
    }

    /** A playout of the open rollout ended at its play limit; nothing outside a rollout. */
    static void notePlayLimit() {
        final Rollout r = openRollout();
        if (r != null) {
            r.tally.playoutsAtPlayLimit++;
        }
    }

    /** The open rollout copied the game into {@code copy}: its cards are counted; nothing outside a rollout. */
    static void noteCopy(final Game copy) {
        final Rollout r = openRollout();
        if (r != null) {
            r.tally.cardsCopied += copy.getCardsInGame().size();
        }
    }

    /** The bound scope's open rollout, or null; it makes no state. */
    private static Rollout openRollout() {
        final SimScope scope = SimScope.current();
        final FlatMcGame st = scope == null ? null : peek(scope);
        return st == null ? null : st.open;
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (final NoSuchAlgorithmException e) {
            throw new IllegalStateException("every Java platform has SHA-256", e);
        }
    }

    /** {@code d}'s digest in lower-case hex, read from a copy so that {@code d} keeps what it has taken in. */
    private static String hex(final MessageDigest d) {
        try {
            return HexFormat.of().formatHex(((MessageDigest) d.clone()).digest());
        } catch (final CloneNotSupportedException e) {
            throw new IllegalStateException("SHA-256 cannot be read without being reset", e);
        }
    }
}
