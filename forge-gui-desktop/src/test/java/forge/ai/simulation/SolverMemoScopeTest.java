package forge.ai.simulation;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNull;

import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

import org.testng.annotations.Test;

import forge.ai.AITest;
import forge.game.Game;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.zone.ZoneType;
import forge.util.SimScope;

/**
 * Upstream's TideSolver (bb04045b2b2, #12912) and ElfLoop (2f9dfd90fee, #12921) remember what playing their loop out
 * in a copy answered, in one static map every game of the JVM shares, keyed by the game's identity hash, and clear it
 * whole once it holds more than 64 answers. In a serve JVM, where many games run side by side, whether a game finds
 * its own answer then depends on the games beside it: their answers fill the map and clear it, and the game plays its
 * loop out again (taking ids and random draws) where a game alone would have answered from memory. Under a
 * {@link SimScope} each game keeps its own memo, as DeckPlan's plans are kept (59de92e15d3); unbound, the GUI's case,
 * the JVM's map stays.
 *
 * <p>The observable is the solver's {@code lastNote}: a run of the loop writes it, an answer from memory does not.
 */
public class SolverMemoScopeTest extends AITest {
    private static final long SEED = 1L;
    /** More answers than either memo holds before it is cleared. */
    private static final int FLOOD = 66;
    private static final String MARK = "(answered from memory)";

    @Test
    public void tideSolverAnswerUnderAScopeOutlivesAnotherScopesTraffic() {
        outlives(TideSolver::wins, () -> TideSolver.lastNote, n -> TideSolver.lastNote = n, p -> { });
    }

    @Test
    public void elfLoopAnswerUnderAScopeOutlivesAnotherScopesTraffic() {
        outlives(ElfLoop::wins, () -> ElfLoop.lastNote, n -> ElfLoop.lastNote = n, this::elfDeck);
    }

    @Test
    public void outsideAScopeTheJvmMemoStillAnswers() {
        assertNull(SimScope.current(), "the test's own thread has no scope bound");
        final Player p = seat(p0 -> { });
        TideSolver.wins(p);
        // a second question first: should other games of this JVM have filled the memo, it is cleared at most here
        TideSolver.wins(p);
        TideSolver.lastNote = MARK;
        TideSolver.wins(p);
        assertEquals(TideSolver.lastNote, MARK, "unbound, the second question is answered from the JVM's memo");
    }

    /**
     * Game A asks under its scope; game B, under another, asks about {@link #FLOOD} different hands; A asks again about
     * the same position and must be answered from its own memo, the same answer, without playing the loop out again.
     */
    private void outlives(final Function<Player, Boolean> wins, final Supplier<String> note,
            final Consumer<String> setNote, final Consumer<Player> deck) {
        final Player a = seat(deck);
        final Player b = seat(deck);
        final SimScope scopeA = new SimScope(SEED);
        final SimScope scopeB = new SimScope(SEED);
        final boolean first = Scoped.under(scopeA, () -> wins.apply(a));
        Scoped.under(scopeB, () -> {
            for (int i = 0; i < FLOOD; i++) {
                addCardToZone("Island", b, ZoneType.Hand);
                wins.apply(b);
            }
            return null;
        });
        setNote.accept(MARK);
        final boolean again = Scoped.under(scopeA, () -> wins.apply(a));
        assertEquals(again, first, "game A's answer is the one it had");
        assertEquals(note.get(), MARK, "and it came from game A's own memo: the loop was not played out again");
    }

    /** The second seat of a fresh two-AI game at its first main phase, with {@code deck} applied. */
    private Player seat(final Consumer<Player> deck) {
        final Game game = initAndCreateGame();
        game.AI_TIMEOUT = Integer.MAX_VALUE;
        game.AI_CAN_USE_TIMEOUT = false;
        final Player p = game.getPlayers().get(1);
        deck.accept(p);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, p);
        game.getAction().checkStateEffects(true);
        return p;
    }

    /** What ElfLoop asks before it plays anything out: Glimpse of Nature in hand and a finisher owned. */
    private void elfDeck(final Player p) {
        addCardToZone("Glimpse of Nature", p, ZoneType.Hand);
        addCardToZone("Craterhoof Behemoth", p, ZoneType.Library);
        addCards("Forest", 2, p);
    }
}
