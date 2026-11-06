package forge.ai.simulation;

import static org.testng.AssertJUnit.assertEquals;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Set;
import java.util.TreeSet;

import org.testng.annotations.Test;

/**
 * Every instance field of the four classes a deck plan is made of, pinned as in the signature or out of it (MTG_DeckMaker
 * tools/combo-ai/f5-guard/SPEC.md, section 3.2, which says why for each). A plan text is stored once per list and engine
 * and a swap is copied on two equal texts, so a field a decision reads and the signature does not is a wrong copy nobody
 * sees. An upstream merge that adds a field fails here: read what reads it, then pin it.
 */
public class PlanSignatureFieldsTest {
    private static final String IN = "in", OUT = "out";

    /** {@code pins}: name, then {@link #IN} or {@link #OUT}, in turn. The word is for the reader: the code the signature is
     *  in says which it means, and section 3.2 says why. */
    private static void pinned(final Class<?> c, final String... pins) {
        final Set<String> asPinned = new TreeSet<>();
        for (int i = 0; i < pins.length; i += 2) {
            asPinned.add(pins[i]);
        }
        final Set<String> found = new TreeSet<>();
        for (final Field f : c.getDeclaredFields()) {
            if (!Modifier.isStatic(f.getModifiers()) && !f.isSynthetic()) {
                found.add(f.getName());
            }
        }
        assertEquals("a field of " + c.getSimpleName() + " moved: read what reads it, then pin it in or out", asPinned, found);
    }

    @Test
    public void everyFieldOfADeckPlanIsPinned() {
        pinned(DeckPlan.class, "archetype", IN, "combos", IN, "commander", IN, "opponentLifeExtra", OUT, "ownLifeExtra", OUT,
                "riskPercent", OUT, "manaDevelopmentExtra", OUT, "longPlayouts", OUT);
    }

    @Test
    public void everyFieldOfAComboPlanIsPinned() {
        pinned(ComboPlan.class, "combos", IN, "cardsScanned", OUT, "scanComplete", OUT);
    }

    @Test
    public void everyFieldOfAComboIsPinned() {
        pinned(ComboPlan.Combo.class, "kind", IN, "strength", IN, "payoffs", IN, "enablers", IN, "cascaders", IN,
                "cascadeSafe", OUT, "detail", OUT);
    }

    @Test
    public void everyFieldOfACommanderPlanIsPinned() {
        pinned(CommanderPlan.class, "commanders", IN, "combos", IN, "active", OUT, "strategies", OUT, "cardsScanned", OUT,
                "tribe", OUT);
    }
}
