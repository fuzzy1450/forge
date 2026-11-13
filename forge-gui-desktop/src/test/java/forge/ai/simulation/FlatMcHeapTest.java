package forge.ai.simulation;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;

import org.testng.annotations.Test;

import forge.util.SimScope;

/**
 * The Flat MC picker's heap in a harness game (MTG_DeckMaker's tools/combo-ai/flat-mc/SHIP.md, the anti-goal: an engine
 * whose games differ by machine). Every default seat plays Flat MC, and on a small heap the picker keeps the heuristic
 * choice once the heap looks full, which in a serve JVM follows the games in the other slots and the heap its machine's
 * operator gave it, while verify replays the game alone. Under a bound {@link SimScope} the picker therefore reads
 * nothing of the heap: nothing is constrained, so there is no memory cutoff and no phone's settings, whatever the heap.
 * Unbound, the GUI's case, it reads the heap as it did. AiClockReadsTest counts the picker's two heap reads as its GUI
 * path on this test's word.
 */
public class FlatMcHeapTest {
    /**
     * On a phone's heap, small and full, the unbound picker is constrained, short of memory and on a phone's settings,
     * which shows that the heap it reads is the one the test hands it; under a scope it is none of these and never
     * reads the heap.
     */
    @Test
    public void underAScopeTheHeapIsNotRead() {
        final PhoneHeap phone = new PhoneHeap();
        final StormSolver.Heap jvm = FlatMonteCarloPicker.heap;
        FlatMonteCarloPicker.heap = phone;
        try {
            assertTrue(FlatMonteCarloPicker.constrainedHeap(), "unbound, a 256 MB heap is a phone's");
            assertTrue(FlatMonteCarloPicker.lowMemory(), "and on a full one the picker keeps the heuristic choice");
            assertEquals(lookaheadLimitOfMaximized(), FlatMonteCarloPicker.MOBILE_LOOKAHEAD_MAX_CANDIDATES,
                    "and the desktop strength falls back to a phone's");
            assertTrue(phone.reads > 0, "unbound, the picker reads the heap it is handed");
            phone.reads = 0;
            Scoped.under(new SimScope(1L), () -> {
                assertFalse(FlatMonteCarloPicker.constrainedHeap(), "under a scope nothing is constrained");
                assertFalse(FlatMonteCarloPicker.lowMemory(), "under a scope there is no memory cutoff");
                assertEquals(lookaheadLimitOfMaximized(), 0, "under a scope the desktop strength stays a desktop's");
                return null;
            });
            assertEquals(phone.reads, 0, "under a scope the heap is not read once");
            assertEquals(phone.collections, 0, "and the picker asks for no collection, bound or not");
        } finally {
            FlatMonteCarloPicker.heap = jvm;
        }
    }

    /**
     * The desktop strength's limit on the decisions whose lines play out the opponent's next turn: none (0) on a
     * desktop, a phone's {@link FlatMonteCarloPicker#MOBILE_LOOKAHEAD_MAX_CANDIDATES} where the heap is constrained.
     */
    private static int lookaheadLimitOfMaximized() {
        return new FlatMonteCarloPicker.Config().maximized().lookaheadMaxCandidates;
    }

    /** A phone's heap, 256 MB and all of it in use, that counts what the picker asks of it. */
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
