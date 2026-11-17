package forge.ai.simulation;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.testng.annotations.Test;

import forge.game.ability.ApiType;
import forge.game.card.Card;
import forge.game.phase.PhaseType;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import forge.util.SimScope;

/**
 * The storm solver never plays a delayed-loss card (MTG_DeckMaker's self-kill fix, DESIGN.md S2): no line it finds,
 * kill or setup, holds a step of one of the eleven cards (a Pact, an extra turn that loses, Glorious End), so a kill
 * that needs one is not taken, and every other kill still is. The class names nothing the self-kill fix added, so it
 * compiles at the parent 65940c9d323, where every row that says "today" fails with the play it gives. Each body runs
 * on a fresh game thread bound to a SimScope ({@link Scoped}), where a search reads no clock and its state cap alone
 * stops it.
 */
public class StormSolverDelayedLossTest extends FlatMcTestBase {
    /** The eleven cards of the self-kill fix's three classes. */
    private static final List<String> ELEVEN = List.of("Pact of Negation", "Summoner's Pact", "Slaughter Pact",
            "Intervention Pact", "Pact of the Titan", "Final Fortune", "Last Chance", "Warrior's Oath",
            "Chance for Glory", "Alchemist's Gambit", "Glorious End");
    /** The states a search here may expand: far more than any of these positions needs. */
    private static final int STATES = 200;
    /** A search's time budget, which a search under a scope never reads. */
    private static final long BUDGET_NANOS = 20_000_000_000L;

    // --------------------------------------------------------------------------------------------- helpers

    /** The seat's combos, as the AI reads them: a tutor storm deck. */
    private ComboPlan tutorStorm() {
        final ComboPlan combos = DeckPlan.forSetting("auto", ai).combos;
        assertTrue(combos.has(ComboPlan.Kind.TUTOR_STORM), "the library reads as tutor storm: " + combos.summary());
        return combos;
    }

    /** Pretends that {@code count} spells were cast this turn, the storm count: placeholders, as the solver's own. */
    private void stormCount(final int count) {
        final Card any = ai.getCardsIn(ZoneType.Hand).get(0);
        final List<SpellAbility> cast = game.getStack().getSpellsCastThisTurn();
        cast.clear();
        for (int i = 0; i < count; i++) {
            cast.add(new SpellAbility.EmptySa(ApiType.Draw, any));
        }
    }

    /** The steps of a line or a setup, as "host: ability", or "none". */
    private static String describe(final List<StormSolver.Step> steps) {
        return steps == null ? "none" : steps.toString();
    }

    /** The steps among {@code steps} whose host is one of the eleven cards. */
    private static List<String> flagged(final List<StormSolver.Step> steps) {
        final List<String> out = new ArrayList<>();
        if (steps != null) {
            for (final StormSolver.Step step : steps) {
                if (ELEVEN.contains(step.host)) {
                    out.add(step.host);
                }
            }
        }
        return out;
    }

    /**
     * A tutor storm seat at its first main phase with Grapeshot and Final Fortune in hand, four Mountains for both
     * ({R}{R}, then {1}{R}), one spell cast this turn and the opponent at {@code oppLife}.
     */
    private void grapeshotAndFinalFortune(final int oppLife) {
        setUpGame();
        noDeadline();
        addTutorStormDeck();
        put("Grapeshot", ai, ZoneType.Hand);
        put("Final Fortune", ai, ZoneType.Hand);
        put("Mountain", 4, ai, ZoneType.Battlefield);
        opp.setLife(oppLife, null);
        at(PhaseType.MAIN1, ai);
        game.getAction().checkStateEffects(true);
        stormCount(1);
    }

    // --------------------------------------------------------------------------------------------- S2

    /**
     * DESIGN.md S2 and round 2's M11: a tutor storm seat at its first main phase with Summoner's Pact in hand, an
     * untapped Forest, Birds of Paradise in its library, nothing else castable, and Tendrils of Agony far from lethal.
     * Birds of Paradise on the battlefield is a setup worth 35 to ComboPlan.setupScore, against the 30 a setup must
     * gain, and only the Pact can fetch it: today the search leaves the setup [Summoner's Pact, Birds of Paradise]. No
     * step of the line solve returns, nor of the setup it leaves, may be one of the eleven cards.
     */
    @Test(timeOut = 300_000)
    public void aSearchNeverPlaysADelayedLossMove() {
        final List<Object> seen = Scoped.under(new SimScope(101L), () -> {
            setUpGame();
            noDeadline();
            addTutorStormDeck();
            put("Birds of Paradise", ai, ZoneType.Library);
            put("Summoner's Pact", ai, ZoneType.Hand);
            put("Forest", ai, ZoneType.Battlefield);
            opp.setLife(20, null);
            at(PhaseType.MAIN1, ai);
            game.getAction().checkStateEffects(true);
            final StormSolver.Line line = StormSolver.solve(ai, tutorStorm(), STATES, BUDGET_NANOS);
            final List<StormSolver.Step> setup = StormSolver.lastSetup();
            final List<String> steps = new ArrayList<>(flagged(line == null ? null : line.steps));
            steps.addAll(flagged(setup));
            return Arrays.asList(steps, "line " + describe(line == null ? null : line.steps) + ", setup "
                    + describe(setup));
        });
        assertEquals(seen.get(0), List.of(), "no step of the eleven cards in the line or the setup: " + seen.get(1));
    }

    /**
     * DESIGN.md S2: the opponent at 2, one spell cast this turn: Grapeshot and its copy take the 2, so the kill needs
     * no Final Fortune, which is in the hand beside it with the Mountains for both. The line ends with Grapeshot and
     * holds no Final Fortune step, today and with the fix: the filter keeps every kill that needs none of the eleven
     * cards.
     */
    @Test(timeOut = 300_000)
    public void aKillWithoutFinalFortuneIsStillFound() {
        final StormSolver.Line line = Scoped.under(new SimScope(103L), () -> {
            grapeshotAndFinalFortune(2);
            return StormSolver.solve(ai, tutorStorm(), STATES, BUDGET_NANOS);
        });
        assertNotNull(line, "Grapeshot and the copy one spell cast before it makes take the opponent's 2");
        assertEquals(line.steps.get(line.steps.size() - 1).host, "Grapeshot", "the kill: " + line.steps);
        assertEquals(flagged(line.steps), List.of(), "no Final Fortune step: " + line.steps);
    }

    /**
     * DESIGN.md S2: the same with the opponent at 3. Grapeshot and one copy take 2: only Final Fortune's cast, one more
     * spell before Grapeshot, makes it lethal (three times 1), and today the search returns [Final Fortune,
     * Grapeshot]. The kill is not taken: solve returns no line.
     */
    @Test(timeOut = 300_000)
    public void aKillThatNeedsFinalFortuneIsNotTaken() {
        final StormSolver.Line line = Scoped.under(new SimScope(107L), () -> {
            grapeshotAndFinalFortune(3);
            return StormSolver.solve(ai, tutorStorm(), STATES, BUDGET_NANOS);
        });
        assertNull(line, "a kill that needs Final Fortune is not taken, found " + describe(line == null ? null
                : line.steps));
    }
}
