package forge.ai.combo;

import java.lang.reflect.Field;
import java.util.Collections;
import java.util.List;
import java.util.function.Supplier;

import forge.ai.simulation.ComboCatalog;

/**
 * Upstream's combo catalogue ({@link ComboCatalog}, ai-combos.txt) emptied while one test method runs, then put back:
 * for ComboFetchAiTest and ComboTrackerTest, whose boards show that {@link ComboFetch} refuses a fetch on purpose
 * (danger with no win in time, a line denied at its window, a hoser such as Rule of Law or Platinum Angel, a search
 * another player controls) by the pick that a game without the catalogue makes there.
 * <p>
 * Evan's ruling of 2026-11-14 ("go with a - upstream decides everywhere") makes a refusal no veto on upstream's deck
 * plan: its catalogue step (DeckPlan.tutorPicks over ComboCatalog.missingPieces, upstream #12858) fills it, and with
 * Thassa's Oracle in hand it fetches Demonic Consultation, the line's own piece. On these boards a refusal and a fetch
 * would then pick the same card, and the 22 guards that failed at the merge of upstream e5e2f900616 (2d339921360,
 * each "expected not same [Demonic Consultation]") could tell nothing. Without the catalogue a board's refusal is the
 * pick of a game without it, the pick those guards tell a fetch from: the merge's rule R7, a stock quantity re-pinned
 * with a named cause (the ruling), no assertion loosened. MergeRefusalFillTest pins what the catalogue picks at a
 * refusal: Consultation, on these boards. With the gate of f602a0c1949 in place, every test of both classes passed
 * with the catalogue emptied as well, and the one reader of it they reach, the deck plan's catalogue step through
 * ChangeZoneAi, answered nothing on any of their boards either way: emptying it changes no other test.
 * <p>
 * Per method, not per class: surefire runs the module's tests in one forked JVM that every class shares (forkCount 1,
 * reuseForks true, no parallel run, a class order of its own), and AITest configures the catalogue in that JVM once,
 * at the first test's FModel.initialize, from its own {@code @BeforeMethod}, which runs before a subclass's. So the
 * catalogue is emptied after that, and what was configured is put back by an {@code @AfterMethod(alwaysRun = true)},
 * after a failure or a skip too: no other class ever reads the empty catalogue, in whatever order the classes run.
 */
final class NoComboCatalog {
    /** What the catalogue was configured with when {@link #begin} emptied it (null for nothing configured). */
    private final Supplier<List<String>> configured;

    private NoComboCatalog(final Supplier<List<String>> configured) {
        this.configured = configured;
    }

    /** Empties the catalogue; {@link #end} on the result puts back what was configured. */
    static NoComboCatalog begin() {
        synchronized (ComboCatalog.class) {
            final NoComboCatalog off = new NoComboCatalog(configured());
            ComboCatalog.configure(Collections::emptyList);
            return off;
        }
    }

    /** Puts back the catalogue {@link #begin} found, which is read again at its next question. */
    void end() {
        ComboCatalog.configure(configured);
    }

    // ComboCatalog keeps its file to itself; configure takes the same supplier back, whatever FModel or a test set
    @SuppressWarnings("unchecked")
    private static Supplier<List<String>> configured() {
        try {
            final Field file = ComboCatalog.class.getDeclaredField("file");
            file.setAccessible(true);
            return (Supplier<List<String>>) file.get(null);
        } catch (final ReflectiveOperationException e) {
            throw new IllegalStateException("cannot read the combo catalogue's configured file (ComboCatalog.file)", e);
        }
    }
}
