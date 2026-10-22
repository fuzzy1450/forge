package forge.ai;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.card.CardLists;
import forge.game.keyword.Keyword;
import forge.game.player.Player;
import forge.game.zone.MagicStack;
import forge.game.zone.ZoneType;

/**
 * How the AI judges a loop it controls (MTG_DeckMaker, docs/superpowers/specs/2026-10-21-loop-aware-ai-design.md).
 * <p>
 * A loop is a trigger's loop key ({@link forge.game.trigger.Trigger#getLoopKey()}); a round is one count of
 * {@link MagicStack#getTriggerRepeats(int)}, which already folds a crowd into one round and forgets the key at every
 * reset (a player did something, a loss of life by a player who can lose, an empty stack), each reset one more of
 * {@link MagicStack#getTriggerRepeatsEpoch()}. The tracker adds nothing to that counting. Per key it keeps the
 * generation and the count it last saw, the board {@link Snapshot} it took when it first saw that count, how many
 * consecutive rounds changed nothing, and the kind of change the last compared round made. From those it answers two
 * questions: is this loop <em>spent</em> (two idle rounds, or a loop winning by the AI's own board whose board is
 * decisive, {@link #LETHAL_MULTIPLE} times the life of the opponents who can lose, or a winning one which has gone round more than
 * {@link #MAX_PROGRESS_ROUNDS} times),
 * which stops an optional loop in PlayerControllerAi.confirmTrigger (CR 732.2a: the controller of an optional loop
 * chooses how many times it repeats) and breaks a mandatory copy loop by its target choice in CopyPermanentAi; and, for
 * that choice, which names the spent loop's rounds kept choosing, to be avoided until the key resets.
 * <p>
 * Nothing here draws RNG, allocates an id or reads the game through anything that moves it; a snapshot is sums over
 * zones the engine already holds. The records keep card names and numbers only, never a Card or a SpellAbility.
 */
public final class LoopTracker {

    /**
     * Evan's rule for an unbounded loop: push it to a multiple of lethal. Ten was his first word ("good practice when you
     * have an infinite loop is to go for 10x lethal"); five is his pick of 2026-10-22, after stored game 1749529 showed
     * what ten costs the engine: a second Ondu Spiritdancer chain against a lifegain opponent at 67 life ran to about 190
     * copies and that one turn took 1,021 s (the chain, then combat planning over 200 attackers), a 10-s win turned Timeout.
     */
    public static final int LETHAL_MULTIPLE = 5;

    /**
     * The budget for a loop that pays toward lethal but is not yet decisive. A bare-board Ondu Spiritdancer chain costs
     * about 0.4 ms times the square of its length (101 copies in 3 s, 201 in 9 s, 1,001 in 391 s) and about ten times
     * that on a real board, and past about a hundred creatures the AI's combat planning slows: 200 rounds is about two
     * minutes on such a board and 600 power at 3/3s, five times lethal against 120 life.
     */
    public static final int MAX_PROGRESS_ROUNDS = 200;

    /** Consecutive rounds that changed nothing the snapshot sees before the loop is given up. */
    public static final int IDLE_ROUNDS = 2;

    /**
     * While the stack holds more than this many items the AI declines every optional trigger: resolving what is already
     * there must never pass {@link MagicStack#MAX_STACK_SIZE}, the stack-size draw. n fresh Ondus copying a creature peak
     * at about n(n + 1)/2 items, so a chain that has run past 50 copies would otherwise draw the game it had just won
     * on the next turn's crowd. Half the limit leaves an accepted trigger's consequences the other half.
     */
    public static int stackHeadroom() {
        return MagicStack.MAX_STACK_SIZE / 2;
    }

    /** What one round of a loop did to the board. */
    public enum Progress {
        /** Nothing the snapshot sees changed. */
        IDLE,
        /** The AI's permanents, hand or life rose, and nothing moved toward lethal. */
        MATERIAL,
        /** An opponent who can lose moved toward a loss, or the AI's attack power rose. */
        WIN
    }

    /** A cheap board reading: for each opponent still in the game, life, poison, library, creature power and whether they can lose; for the AI, attack power, permanents, hand and life. */
    public static final class Snapshot {
        private static final int LIFE = 0, POISON = 1, LIBRARY = 2, POWER = 3, CAN_LOSE = 4;

        private final int turn;
        private final int ownPower;
        private final int ownPermanents;
        private final int ownHand;
        private final int ownLife;
        private final Map<Integer, int[]> opponents = new HashMap<>();

        private Snapshot(final Player ai) {
            turn = ai.getGame().getPhaseHandler().getTurn();
            int power = 0;
            for (final Card c : ai.getCreaturesInPlay()) {
                if (!c.hasKeyword(Keyword.DEFENDER)) {
                    power += Math.max(0, c.getNetPower());
                }
            }
            ownPower = power;
            ownPermanents = ai.getCardsIn(ZoneType.Battlefield).size();
            ownHand = ai.getCardsIn(ZoneType.Hand).size();
            ownLife = ai.getLife();
            for (final Player p : ai.getOpponents()) {
                int theirs = 0;
                for (final Card c : p.getCreaturesInPlay()) {
                    theirs += Math.max(0, c.getNetPower());
                }
                opponents.put(p.getId(), new int[] {p.getLife(), p.getPoisonCounters(),
                        p.getCardsIn(ZoneType.Library).size(), theirs, p.cantLose() ? 0 : 1});
            }
        }

        public static Snapshot of(final Player ai) {
            return new Snapshot(ai);
        }

        public int turn() {
            return turn;
        }

        /** The power the AI could attack with next turn: its creatures without defender, tapped and summoning-sick ones included. */
        public int ownPower() {
            return ownPower;
        }

        /** What changed since {@code earlier}, classified as the spec's section 4.2 says. */
        public Progress progressSince(final Snapshot earlier) {
            if (ownPower > earlier.ownPower || opponentMovedSince(earlier)) {
                return Progress.WIN;
            }
            if (ownPermanents > earlier.ownPermanents || ownHand > earlier.ownHand || ownLife > earlier.ownLife) {
                return Progress.MATERIAL;
            }
            return Progress.IDLE;
        }

        /**
         * Whether an opponent who could lose moved toward a loss since {@code earlier}: life down, poison up, library down,
         * creature power down, or gone from the game. The half of win progress that decisive never cuts: a poison or mill
         * loop one round from the kill runs on however big the AI's board is.
         */
        public boolean opponentMovedSince(final Snapshot earlier) {
            for (final Map.Entry<Integer, int[]> e : earlier.opponents.entrySet()) {
                final int[] was = e.getValue();
                final int[] now = opponents.get(e.getKey());
                if (now == null) {
                    return true; // gone from the game
                }
                if (was[CAN_LOSE] == 0) {
                    continue; // a strong player knows the Angel is there
                }
                if (now[LIFE] < was[LIFE] || now[POISON] > was[POISON] || now[LIBRARY] < was[LIBRARY]
                        || now[POWER] < was[POWER]) {
                    return true;
                }
            }
            return false;
        }

        /** {@link #LETHAL_MULTIPLE} times lethal: the AI's attack power against the summed life of the opponents who can lose (none: never). */
        public boolean isDecisive() {
            int sum = 0;
            for (final int[] o : opponents.values()) {
                if (o[CAN_LOSE] == 1) {
                    sum += Math.max(0, o[LIFE]);
                }
            }
            return sum > 0 && (long) ownPower >= (long) LETHAL_MULTIPLE * sum;
        }
    }

    /** One loop key's record. */
    public static final class Loop {
        private final int epoch;
        private int count;
        private Snapshot at;
        private int compared;
        private int idle;
        private Progress last;
        private boolean lastMovedOpponent;
        private boolean spent;
        private final Set<String> chosen = new HashSet<>();
        private Set<String> avoided = Collections.emptySet();

        private Loop(final int epoch, final int count, final Snapshot now) {
            this.epoch = epoch;
            this.count = count;
            this.at = now;
        }

        private void round(final int newCount, final Snapshot now) {
            final Progress p = now.progressSince(at);
            compared++;
            idle = p == Progress.IDLE ? idle + 1 : 0;
            last = p;
            lastMovedOpponent = p == Progress.WIN && now.opponentMovedSince(at);
            at = now;
            count = newCount;
        }

        private void markSpentIf(final Snapshot now) {
            if (!spent && isSpent(now)) {
                spent = true;
                avoided = new HashSet<>(chosen);
            }
        }

        /** The count last seen. */
        public int count() {
            return count;
        }

        /** Rounds compared so far: a key seen at one count only has none. */
        public int comparedRounds() {
            return compared;
        }

        /** Consecutive compared rounds that changed nothing. */
        public int idleRounds() {
            return idle;
        }

        /** The last compared round's change, null before any comparison. */
        public Progress last() {
            return last;
        }

        /** Whether the last compared round's win progress moved an opponent toward a loss (and not only the AI's board). */
        public boolean lastMovedOpponent() {
            return lastMovedOpponent;
        }

        /**
         * Two idle rounds; or a winning loop that has gone round more than {@link #MAX_PROGRESS_ROUNDS} times; or a loop
         * winning by the AI's own board alone whose board is decisive (one that moved an opponent toward a loss, a poison
         * or mill loop, is never cut by decisive). Once spent, spent until the key resets.
         */
        public boolean isSpent(final Snapshot now) {
            if (spent) {
                return true;
            }
            if (compared < 1) {
                return false;
            }
            if (idle >= IDLE_ROUNDS) {
                return true;
            }
            return last == Progress.WIN && (count > MAX_PROGRESS_ROUNDS || (!lastMovedOpponent && now.isDecisive()));
        }

        /** The optional-trigger stop: spent, or, for a loop not winning, past the old count (CR 732.2a's 50). */
        public boolean declinesOptional(final Snapshot now) {
            return isSpent(now) || (last != Progress.WIN && count > MagicStack.MAX_TRIGGER_REPEATS);
        }

        /** A target chosen for this loop (the mandatory path), by name. */
        public void remember(final String name) {
            chosen.add(name);
        }

        /** The names chosen before the loop was judged spent, frozen then; empty while it is not spent. */
        public Set<String> avoided() {
            return Collections.unmodifiableSet(avoided);
        }

        /** The candidates without the avoided names, or the candidates themselves when nothing else would be left. */
        public CardCollection withoutAvoided(final CardCollection candidates) {
            if (avoided.isEmpty()) {
                return candidates;
            }
            final CardCollection kept = CardLists.filter(candidates, c -> !avoided.contains(c.getName()));
            return kept.isEmpty() ? candidates : kept;
        }
    }

    private final Map<Integer, Loop> loops = new HashMap<>();

    /**
     * Folds the current round of {@code loopKey} in and returns its record (null for a key below zero: not a trigger's
     * ability). {@code epoch} is {@link MagicStack#getTriggerRepeatsEpoch()}: a key seen in another generation is another
     * chain and starts fresh, whatever the counts (so does a count below the one last seen). The same count in the same
     * generation is the same round. A higher count is a new round, compared against the previous one's snapshot.
     */
    public Loop observe(final int loopKey, final int count, final int epoch, final Snapshot now) {
        if (loopKey < 0) {
            return null;
        }
        Loop loop = loops.get(loopKey);
        if (loop == null || epoch != loop.epoch || count < loop.count) {
            loop = new Loop(epoch, count, now);
            loops.put(loopKey, loop);
        } else if (count > loop.count) {
            loop.round(count, now);
        }
        loop.markSpentIf(now);
        return loop;
    }

    /** The record as it stands, null when the key has never been observed since its last reset. */
    public Loop loop(final int loopKey) {
        return loops.get(loopKey);
    }
}
