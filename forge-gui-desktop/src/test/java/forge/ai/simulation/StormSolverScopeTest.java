package forge.ai.simulation;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertSame;
import static org.testng.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.testng.annotations.Test;

import forge.ai.AITest;
import forge.game.Game;
import forge.game.card.Card;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.zone.ZoneType;
import forge.util.SimScope;

/**
 * The storm solver on a step budget (MTG_DeckMaker's tools/combo-ai/flat-mc/SHIP.md, design 3, for its anti-goal: an
 * engine whose games differ by machine). Every default seat plays Flat MC, so a tutor storm seat runs the solver in the
 * harness's games, where one seed has to play one game on every machine, beside other games or alone, as verify replays
 * it. Under a bound {@link SimScope} the solver therefore reads no clock and no heap: no time budget, nothing
 * constrained, no memory cutoff and no collection, so its state cap is what stops a search. Unbound, the GUI's case, it
 * is as it was.
 *
 * <p>The position is the Beseech line FlatMonteCarloStormTest's theSolverFindsTheBeseechLine sets up, in the Beseech
 * Storm main deck StormGoldfishTest plays: Dark Ritual, Beseech the Mirror bargained with Wishclaw Talisman to cast
 * Gaea's Will for free, then Tendrils of Agony at a storm count of three, four copies of two life against the
 * opponent's eight. Its game is made as StormGoldfishTest makes its games, on {@link AITest}: two AI seats with no AI
 * option, as a served game's default seats are. The solver's copies take their seats' controllers from the lobby player
 * (GameCopier.clonePlayer), so on SimulationTest's game, whose seat has the full simulation, they would play their
 * choices by it. A scoped search runs on a fresh game thread in a fresh scope of {@link #SEED}, as {@link Scoped} runs
 * one, so two of them start from the same random stream and the same id counters.
 */
public class StormSolverScopeTest extends AITest {
    /** The seed of every scope here. */
    private static final long SEED = 1L;
    /** The state cap of the AI's own searches. */
    private static final int CAP = StormSolver.DEFAULT_MAX_NODES;
    /** StormGoldfishTest's Beseech Storm main deck, the position's library. */
    private static final Object[] BESEECH_STORM = {
        "Dark Ritual", 4, "Lion's Eye Diamond", 4, "Lotus Petal", 4, "Chrome Mox", 4, "Mox Opal", 4,
        "Giant's Boulder", 2, "Infernal Tutor", 4, "Beseech the Mirror", 2, "Burning Wish", 3, "Thoughtseize", 4,
        "Duress", 3, "Hexing Squelcher", 2, "Gaea's Will", 1, "Tendrils of Agony", 1, "Wishclaw Talisman", 1,
        "Urza's Saga", 4, "Vault of Whispers", 4, "Spire of Industry", 3, "City of Traitors", 2, "Swamp", 4 };

    /**
     * Under a scope a time budget cuts nothing: with 1 ns, or none at all, a search finds the same line in as many
     * states as with all the time there is, and it reports no time taken, having read no clock.
     */
    @Test
    public void underAScopeATinyBudgetCutsNothing() {
        final Player p = beseechLine();
        final StormSolver.Line full = scoped(p, CAP, Long.MAX_VALUE);
        assertNotNull(full, "with all the time there is, the Beseech line is found: " + StormSolver.lastDiagnostic());
        for (final long tiny : new long[] { 1L, 0L }) {
            final StormSolver.Line line = scoped(p, CAP, tiny);
            assertEquals(nodes(line), full.nodes, "with " + tiny + " ns as many states are searched as with no limit");
            assertEquals(steps(line), steps(full), "with " + tiny + " ns the same line is found");
        }
        assertEquals(full.nanos, 0L, "and the search reports no time taken: under a scope it reads no clock");
    }

    /** Unbound, the GUI's case, the budget still cuts: with 1 ns a search ends before the states its line needs. */
    @Test
    public void outsideAScopeTheBudgetStillCuts() {
        assertNull(SimScope.current(), "the test's own thread has no scope bound");
        final Player p = beseechLine();
        final ComboPlan combos = tutorStorm(p);
        final StormSolver.Line full = StormSolver.solve(p, combos, CAP, Long.MAX_VALUE);
        assertNotNull(full, "with all the time there is, the Beseech line is found: " + StormSolver.lastDiagnostic());
        final StormSolver.Line tiny = StormSolver.solve(p, combos, CAP, 1L);
        assertTrue(nodes(tiny) < full.nodes, "unbound, 1 ns finds the line in fewer states, or none: " + nodes(tiny)
                + " of " + full.nodes);
    }

    /**
     * Under a scope the solver reads nothing of the heap: on a phone's heap, small and full, nothing is constrained,
     * ten games are kept, the search has no time budget and no memory cutoff, and the heap is never asked anything, not
     * even for a collection. Unbound, the same heap makes the GUI's solver keep fewer games, search for half as long
     * and stop, which shows that the heap it reads is the one the test hands it.
     */
    @Test
    public void underAScopeTheHeapIsNotRead() {
        final PhoneHeap phone = new PhoneHeap();
        final StormSolver.Heap jvm = StormSolver.heap;
        StormSolver.heap = phone;
        try {
            assertTrue(StormSolver.constrained(), "unbound, a 256 MB heap is a phone's");
            assertEquals(StormSolver.cacheSize(), 4, "unbound, a phone's search keeps four games");
            assertEquals(StormSolver.budgetNanos(), StormSolver.DEFAULT_BUDGET_NANOS / 2, "and has half the time");
            assertTrue(StormSolver.lowMemory(), "and on a full heap it stops");
            assertTrue(phone.reads > 0, "unbound, the solver reads the heap it is handed");
            phone.reads = 0;
            phone.collections = 0;
            Scoped.under(new SimScope(SEED), () -> {
                assertFalse(StormSolver.constrained(), "under a scope nothing is constrained");
                assertEquals(StormSolver.cacheSize(), 10, "under a scope ten games are kept");
                assertEquals(StormSolver.budgetNanos(), Long.MAX_VALUE, "under a scope there is no time budget");
                assertFalse(StormSolver.lowMemory(), "under a scope there is no memory cutoff");
                return null;
            });
            assertEquals(phone.reads, 0, "under a scope the heap is not read once");
            assertEquals(phone.collections, 0, "nor asked for a collection");
        } finally {
            StormSolver.heap = jvm;
        }
    }

    /**
     * Under a scope the state cap still binds: allowed one state fewer than the line needs, a search finds none and
     * says the cap stopped it, with ten games kept, on a phone's heap too, which it never reads, its report included.
     */
    @Test
    public void underAScopeTheStateCapStillCuts() {
        final Player p = beseechLine();
        final StormSolver.Line line = scoped(p, CAP, Long.MAX_VALUE);
        assertNotNull(line, "with all the time there is, the Beseech line is found: " + StormSolver.lastDiagnostic());
        final PhoneHeap phone = new PhoneHeap();
        final StormSolver.Heap jvm = StormSolver.heap;
        StormSolver.heap = phone;
        try {
            assertNull(scoped(p, line.nodes - 1, Long.MAX_VALUE), "one state short of the line, no line is found");
            final String why = StormSolver.lastDiagnostic();
            assertTrue(why.contains("(state limit, games kept 10)"), "the cap stopped it, ten games kept: " + why);
            assertEquals(phone.reads, 0, "and the search read nothing of the heap, its report included: " + why);
        } finally {
            StormSolver.heap = jvm;
        }
    }

    /**
     * Under a scope an Error in a search is no decision, as everywhere else in a harness game: an OutOfMemoryError,
     * or a StackOverflowError inside a RuntimeException, comes out of next() instead of switching the solver off,
     * since a heap or a stack that ran short on one machine and not on another would play one seed two ways. A plain
     * RuntimeException, which every machine meets alike, still switches it off. Unbound, the GUI's case, both Errors
     * switch it off as before. next() is handed searches that fail at once through its package-private seam; the real
     * search would find the Beseech line, so a solver that gives no play here ran the failing one.
     */
    @Test
    public void aSearchErrorComesOutOfNextOnlyUnderAScope() {
        assertNull(SimScope.current(), "the test's own thread has no scope bound");
        assertFalse(SimScope.isStrict(), "nor strict mode on: the GUI's case");
        final Player p = beseechLine();
        final OutOfMemoryError oom = new OutOfMemoryError("a search that ran short of heap");
        final StackOverflowError deep = new StackOverflowError();
        final RuntimeException wrapped = new RuntimeException(deep);
        final RuntimeException plain = new IllegalStateException("a failure every machine meets alike");
        final StormSolver.Search solve = StormSolver.search;
        try {
            StormSolver.search = failingWith(oom);
            assertEquals(noteOfNext(p), "out of memory: solver off", "unbound, a search out of memory switches it off");
            StormSolver.search = failingWith(wrapped);
            final String unboundNote = noteOfNext(p);
            assertTrue(unboundNote.startsWith("solver error, solver off"),
                    "unbound, so does a StackOverflowError inside a failure: " + unboundNote);
            StormSolver.search = failingWith(plain);
            final String scopedNote = Scoped.under(new SimScope(SEED), () -> noteOfNext(p));
            assertTrue(scopedNote.startsWith("solver error, solver off"),
                    "under a scope a plain failure still switches it off: " + scopedNote);
            StormSolver.search = failingWith(oom);
            assertSame(thrownByNext(p), oom, "under a scope the OutOfMemoryError comes out of next()");
            StormSolver.search = failingWith(wrapped);
            assertSame(thrownByNext(p), deep, "and so does the StackOverflowError inside a failure, unwrapped");
        } finally {
            StormSolver.search = solve;
        }
    }

    /**
     * The Beseech line: StormGoldfishTest's main deck as the library of the seat that plays, Beseech the Mirror, Dark
     * Ritual and Tendrils of Agony taken from it into the hand, six Swamps and Wishclaw Talisman (the Bargain's
     * sacrifice) in play, the opponent at 8 and no spell cast yet this turn, at the seat's first main phase with the AI
     * deadline off, as served games have it. Dark Ritual makes the six Swamps eight mana, for Beseech (four) and
     * Tendrils (four).
     */
    private Player beseechLine() {
        final Game game = initAndCreateGame();
        game.AI_TIMEOUT = Integer.MAX_VALUE;
        game.AI_CAN_USE_TIMEOUT = false;
        final Player p = game.getPlayers().get(1);
        for (int i = 0; i < BESEECH_STORM.length; i += 2) {
            for (int n = 0; n < (Integer) BESEECH_STORM[i + 1]; n++) {
                addCardToZone((String) BESEECH_STORM[i], p, ZoneType.Library);
            }
        }
        for (final String name : List.of("Beseech the Mirror", "Dark Ritual", "Tendrils of Agony")) {
            final Card c = fromLibrary(p, name);
            p.getZone(ZoneType.Library).remove(c);
            p.getZone(ZoneType.Hand).add(c);
        }
        addCards("Swamp", 6, p);
        addCard("Wishclaw Talisman", p);
        game.getPlayers().get(0).setLife(8, null);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, p);
        game.getAction().checkStateEffects(true);
        game.getStack().getSpellsCastThisTurn().clear();
        return p;
    }

    private static Card fromLibrary(final Player p, final String name) {
        for (final Card c : p.getCardsIn(ZoneType.Library)) {
            if (c.getName().equals(name)) {
                return c;
            }
        }
        throw new AssertionError(name + " is not in the library");
    }

    /** One search of {@code p}'s position, as a harness game's thread runs it: on a fresh thread, in a fresh scope. */
    private static StormSolver.Line scoped(final Player p, final int maxNodes, final long budgetNanos) {
        return Scoped.under(new SimScope(SEED), () -> StormSolver.solve(p, tutorStorm(p), maxNodes, budgetNanos));
    }

    /** A search that fails at once with {@code failure}, an Error or a RuntimeException, as a search can. */
    private static StormSolver.Search failingWith(final Throwable failure) {
        return (ai, combos, maxNodes, budgetNanos) -> {
            if (failure instanceof Error e) {
                throw e;
            }
            throw (RuntimeException) failure;
        };
    }

    /** The note a fresh solver leaves when its next() for {@code p}'s position, on this thread, gives no play. */
    private static String noteOfNext(final Player p) {
        final StormSolver solver = new StormSolver();
        assertNull(solver.next(p, tutorStorm(p)), "the solver gives no play");
        return solver.lastNote;
    }

    /** What a fresh solver's next() for {@code p}'s position throws on a harness game's thread; null if it returns. */
    private static Throwable thrownByNext(final Player p) {
        return Scoped.thrownBy(new SimScope(SEED), () -> new StormSolver().next(p, tutorStorm(p)));
    }

    /** The seat's combos, read in the scope bound here, if any, as the AI reads them. */
    private static ComboPlan tutorStorm(final Player p) {
        final ComboPlan combos = DeckPlan.forSetting("auto", p).combos;
        assertTrue(combos.has(ComboPlan.Kind.TUTOR_STORM), "the deck reads as tutor storm: " + combos.summary());
        return combos;
    }

    /** How many states a search took to find its line; 0 when it found none. */
    private static int nodes(final StormSolver.Line line) {
        return line == null ? 0 : line.nodes;
    }

    /** A line's plays as text; null when there is none. */
    private static List<String> steps(final StormSolver.Line line) {
        if (line == null) {
            return null;
        }
        final List<String> out = new ArrayList<>();
        for (final StormSolver.Step s : line.steps) {
            out.add(s.toString());
        }
        return out;
    }

    /** A phone's heap, 256 MB and all of it in use, that counts what the solver asks of it. */
    private static final class PhoneHeap implements StormSolver.Heap {
        private static final long SIZE = 256L << 20;
        int reads;
        int collections;

        @Override
        public long max() {
            reads++;
            return SIZE;
        }

        @Override
        public long used() {
            reads++;
            return SIZE;
        }

        @Override
        public void collect() {
            collections++;
        }
    }
}
