package forge.game.staticability;

import java.util.List;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import java.util.function.Supplier;

import forge.game.Game;
import forge.game.card.Card;

/**
 * A hook for tests that check the static ability scans' shortcuts against the scans they stand for. Off (no listener)
 * unless a test installs one; while off, a shortcut reads one static field and nothing else.
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
    }

    /** null: off. */
    public static Listener listener;

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
