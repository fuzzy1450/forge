package forge.ai.combo;

import java.util.ArrayList;
import java.util.List;

import forge.ai.LoopTracker;
import forge.game.Game;
import forge.game.GameLogEntryType;
import forge.game.card.Card;
import forge.game.event.GameEventAddLog;
import forge.game.phase.PhaseHandler;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.zone.ZoneType;

/**
 * The loop driver's round record and its two caps (spec 2026-10-29-combo-aware-ai-loops-design.md section 6.5), kept
 * on the AI player's {@link LoopDriver}, so per seat and per game: nothing here is static but the constants.
 *
 * <p>The caps are counts of the actions the driver returns, never clock readings: {@link #TURN_CAP} a turn, against a
 * fault in one turn, and {@link #GAME_CAP} a game, against a loop that keeps making progress without winning (an
 * opponent at an enormous life total). Reaching either stops the driver for the turn or for the game, with one line in
 * the game log, so a replay shows it.
 *
 * <p>A round is one spend (a stock play, a Ballista counter or ping, a Thrasios activation) with the cycles of refill
 * it calls for. Each is judged once, at the driver's first decision after it, against a {@link LoopTracker.Snapshot}
 * taken before it and the record's own readings: it made progress if an opponent who can lose moved toward losing, our
 * attack power rose, a nonland permanent of ours arrived, or a card arrived in our hand that the loop's pool and our
 * untapped lands can pay for ({@link LoopPool#payable}). {@link #IDLE_ROUNDS} judged rounds in a row (stock plays)
 * that bought nothing stop the driver for the phase; a round that is not judged (Ballista's, Thrasios's, which have
 * stops of their own) breaks such a run. The record is per phase, as the pool is. It draws no random number and
 * writes no card: {@code Snapshot} reads, the rest is card identity.
 */
final class LoopRounds {
    /** The most actions the driver returns in one turn, and in one game; and the judged rounds in a row that buy
     *  nothing before it stops for the phase. */
    static final int TURN_CAP = 2_000, GAME_CAP = 5_000, IDLE_ROUNDS = 2;

    private final int turnCap;
    private final int gameCap;
    // the caps' counts: the turn they are for, its actions and the game's, and whether either cap stopped the driver
    private int turn = -1;
    private int turnActions;
    private int gameActions;
    private boolean turnStopped;
    private boolean gameStopped;
    // the round record, for the phase stamped here
    private int roundTurn = -1;
    private PhaseType roundPhase;
    private int idle;
    // the round open now, null when none: its snapshot, whether it is judged, the loop line whose pool it spends, and
    // our nonland permanents and hand before it (by object: a card that moves is a new one)
    private LoopTracker.Snapshot before;
    private boolean judged;
    private ComboLines.Line line;
    private final List<Card> permanents = new ArrayList<>();
    private final List<Card> hand = new ArrayList<>();

    /** The record with the caps above. */
    LoopRounds() {
        this(TURN_CAP, GAME_CAP);
    }

    /** Tests only: the record with smaller caps. */
    LoopRounds(final int turnCap, final int gameCap) {
        this.turnCap = turnCap;
        this.gameCap = gameCap;
    }

    /** Counts one action the driver returns for {@code ai}, true; false, and one line in the game log the first time,
     *  once either cap is reached: the game's first, then the turn's. A stopped driver stays stopped for that turn or
     *  for the game. */
    boolean act(final Player ai) {
        syncTurn(ai);
        if (gameStopped || turnStopped) {
            return false;
        }
        if (gameActions >= gameCap) {
            gameStopped = true;
            log(ai, "Loop driver: stopped for the game at " + gameCap + " actions.");
            return false;
        }
        if (turnActions >= turnCap) {
            turnStopped = true;
            log(ai, "Loop driver: stopped for the turn at " + turnCap + " actions.");
            return false;
        }
        turnActions++;
        gameActions++;
        return true;
    }

    /** Opens a round before a spend from the pool of loop line {@code l}: a snapshot of {@code ai}'s board, and our
     *  nonland permanents and hand; {@code judged} for a stock play, which the idle rule judges. The round open before
     *  it, if any, is judged first. */
    void beginRound(final Player ai, final boolean judged, final ComboLines.Line l) {
        syncPhase(ai);
        close(ai);
        before = LoopTracker.Snapshot.of(ai);
        this.judged = judged;
        line = l;
        permanents.clear();
        for (final Card c : ai.getCardsIn(ZoneType.Battlefield)) {
            if (!c.isLand()) {
                permanents.add(c);
            }
        }
        hand.clear();
        hand.addAll(ai.getCardsIn(ZoneType.Hand));
    }

    /** Whether {@link #IDLE_ROUNDS} judged rounds in a row bought nothing this phase: the round open now, if any, is
     *  judged first, as the board stands. */
    boolean idleStop(final Player ai) {
        syncPhase(ai);
        close(ai);
        return idle >= IDLE_ROUNDS;
    }

    /** Whether the turn's cap stopped the driver this turn. */
    boolean stoppedForTurn(final Player ai) {
        syncTurn(ai);
        return turnStopped;
    }

    /** Whether the game's cap stopped the driver. */
    boolean stoppedForGame() {
        return gameStopped;
    }

    // a new turn starts the turn's count afresh
    private void syncTurn(final Player ai) {
        final int now = ai.getGame().getPhaseHandler().getTurn();
        if (now != turn) {
            turn = now;
            turnActions = 0;
            turnStopped = false;
        }
    }

    // a new phase starts the round record afresh, dropping a round left open
    private void syncPhase(final Player ai) {
        final PhaseHandler ph = ai.getGame().getPhaseHandler();
        if (ph.getTurn() != roundTurn || ph.getPhase() != roundPhase) {
            roundTurn = ph.getTurn();
            roundPhase = ph.getPhase();
            idle = 0;
            before = null;
            line = null;
            permanents.clear();
            hand.clear();
        }
    }

    // judges the round open now, if any: a judged one that bought nothing extends the run of idle rounds, any other
    // ends it
    private void close(final Player ai) {
        if (before == null) {
            return;
        }
        if (judged && !progressed(ai)) {
            idle++;
        } else {
            idle = 0;
        }
        before = null;
        line = null;
        permanents.clear();
        hand.clear();
    }

    // whether the open round moved toward a win: an opponent who can lose moved toward losing or our attack power rose
    // (the snapshot's readings), a nonland permanent of ours arrived, or a card arrived in our hand that the loop's
    // pool and our untapped lands can pay for
    private boolean progressed(final Player ai) {
        final LoopTracker.Snapshot now = LoopTracker.Snapshot.of(ai);
        if (now.opponentMovedSince(before) || now.ownPower() > before.ownPower()) {
            return true;
        }
        for (final Card c : ai.getCardsIn(ZoneType.Battlefield)) {
            if (!c.isLand() && !holds(permanents, c)) {
                return true;
            }
        }
        for (final Card c : ai.getCardsIn(ZoneType.Hand)) {
            if (!holds(hand, c) && LoopPool.payable(ai, c, line)) {
                return true;
            }
        }
        return false;
    }

    // whether the cards hold that very object (Card.equals compares ids, which a card keeps as it moves)
    private static boolean holds(final List<Card> cards, final Card c) {
        for (final Card in : cards) {
            if (in == c) {
                return true;
            }
        }
        return false;
    }

    // one line of the game log, as game code adds one (GameEventAddLog through the game's event bus)
    private static void log(final Player ai, final String message) {
        final Game game = ai.getGame();
        game.fireEvent(new GameEventAddLog(GameLogEntryType.INFORMATION, message));
    }
}
