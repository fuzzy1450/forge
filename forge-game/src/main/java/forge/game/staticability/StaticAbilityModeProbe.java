package forge.game.staticability;

import java.util.List;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import java.util.function.Supplier;

import forge.game.Game;
import forge.game.card.Card;

/**
 * A test seam: tests that check the static ability scans' shortcuts against the scans they stand for install a listener
 * here through {@link #setListener}. No production launch ever sets one, so the listener is null there, and a
 * shortcut, or a write of a game's modes, reads the one static field and nothing else.
 */
public final class StaticAbilityModeProbe {
    private StaticAbilityModeProbe() {
    }

    public interface Listener {
        /**
         * A scan of {@code game} for {@code modes} was skipped, as {@link Game#mayHaveStaticAbilityMode} answered false
         * for each. {@code scanFindsNothing} runs the scan the skip stands for and answers whether it came to the
         * answer the skip gave; {@code extraCards} are the cards beside the game's zones that it visits.
         */
        void skipped(Game game, Set<StaticAbilityMode> modes, List<Card> extraCards, BooleanSupplier scanFindsNothing);

        /**
         * A scan whose cards were once gathered in a {@code CardCollection} is about to run: {@code collectionOrder} is
         * that collection, built as it was. Each card the scan visits is passed to {@link #visited} while {@code scan}
         * runs; {@code noMatch} tells the answer of a scan that visited every card.
         */
        <T> T visits(List<Card> collectionOrder, Supplier<T> scan, Predicate<T> noMatch);

        void visited(Card c);

        /** {@code game} answered true for {@code mode}, so the scan runs; {@code saturated}: every answer is true. */
        default void mayHave(Game game, StaticAbilityMode mode, boolean saturated) { }

        /** {@code game} noted modes it had not noted before. */
        default void noted(Game game, Set<StaticAbilityMode> added) { }

        /** {@code game} now answers true for every mode, for {@code cause}. */
        default void saturated(Game game, Object cause) { }
    }

    // the test seam: null in every production launch. Plain (neither public nor volatile), so a read can be hoisted
    static Listener listener;

    /** For tests: installs {@code l}, or removes the listener with {@code null}. */
    public static void setListener(final Listener l) {
        listener = l;
    }

    /** The listener, null in every production launch. */
    public static Listener listener() {
        return listener;
    }

    static void skipped(final Game game, final StaticAbilityMode mode, final BooleanSupplier scanFindsNothing) {
        skipped(game, Set.of(mode), List.of(), scanFindsNothing);
    }

    static void skipped(final Game game, final Set<StaticAbilityMode> modes, final List<Card> extraCards,
            final BooleanSupplier scanFindsNothing) {
        final Listener l = listener;
        if (l != null) {
            l.skipped(game, modes, extraCards, scanFindsNothing);
        }
    }

    static void visited(final Card c) {
        final Listener l = listener;
        if (l != null) {
            l.visited(c);
        }
    }
}
