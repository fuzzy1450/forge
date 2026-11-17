package forge.ai.simulation;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.testng.annotations.Test;

import forge.ai.PlayerControllerAi;
import forge.game.ability.ApiType;
import forge.game.card.Card;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import forge.util.SimScope;

/**
 * The storm solver never plays a delayed-loss card (MTG_DeckMaker's self-kill fix, DESIGN.md S2): no line it finds,
 * kill or setup, holds a step of one of the eleven cards (a Pact, an extra turn that loses, Glorious End), so a kill
 * that needs one is not taken, and every other kill still is. And a step of its line that the real game's debt
 * protection refuses is never returned (RULINGS-R3 M19), at any of the four places next() matches one (a new line's
 * first step, a followed step, a response step, a setup's first step): the solver drops the line and marks the state
 * as searched, so the final veto never meets the step. The class names nothing the self-kill fix added, so it
 * compiles at the parent 65940c9d323, where every row that says "today" fails with the play it gives. Each body runs
 * on a fresh game thread bound to a SimScope ({@link Scoped}), where a search reads no clock and its state cap alone
 * stops it; the rows that set the line themselves hand next() a stub search through its package-private seam,
 * StormSolver.search, as StormSolverScopeTest does, and put the real one back.
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

    /** A card cast as far as the stack by {@code p}, with nothing paid. */
    private SpellAbility onStack(final String name, final Player p) {
        return onStack(name, p, null);
    }

    /** A card cast as far as the stack by {@code p}, with nothing paid, aimed at {@code target} when one is given. */
    private SpellAbility onStack(final String name, final Player p, final Player target) {
        final Card c = createCard(name, p);
        c.setGameTimestamp(game.getNextTimestamp());
        game.getStackZone().add(c);
        final SpellAbility sa = c.getFirstSpellAbility();
        sa.setActivatingPlayer(p);
        if (target != null) {
            sa.getTargets().add(target);
        }
        game.getStack().add(sa);
        return sa;
    }

    /** The name of the card a play is of, or null for no play. */
    private static String nameOf(final SpellAbility sa) {
        return sa == null || sa.getHostCard() == null ? null : sa.getHostCard().getName();
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

    // --------------------------------------------------------------------------------------------- M19

    /**
     * RULINGS-R3 M19: a tutor storm seat resolved Summoner's Pact in its first main phase and owes {2}{G}{G} at its
     * next upkeep, which Forest x2 and Mountain x2 pay exactly. Crop Rotation (an additional cost: sacrifice a land)
     * and Grapeshot are in its hand, the opponent at 3, one spell cast this turn: Crop Rotation's cast makes Grapeshot
     * lethal, so the search's line is [Crop Rotation, Grapeshot]. In the real game its sacrifice would leave three
     * lands for the debt, which debt protection refuses ("debt-unprotected"): the solver returns nothing, and asked
     * again in the same state it does not search it again. The AI's own decision then never makes the play, and the
     * final veto never meets it: Crop Rotation is not among its failed casts. Today the solver returns Crop Rotation.
     */
    @Test(timeOut = 300_000)
    public void aStepDebtProtectionRefusesIsNeverReturned() {
        final List<Object> seen = Scoped.under(new SimScope(109L), () -> {
            setUpGame();
            noDeadline();
            addTutorStormDeck(); // no green creature in it: the Pact finds nothing
            fillLibrary(opp, 10);
            at(PhaseType.MAIN1, ai);
            onStack("Summoner's Pact", ai);
            game.getPhaseHandler().onStackResolved();
            playUntilStackClear(game);
            assertTrue(game.getPhaseHandler().is(PhaseType.MAIN1, ai), "still the AI's first main phase");
            put("Forest", 2, ai, ZoneType.Battlefield);
            put("Mountain", 2, ai, ZoneType.Battlefield);
            final SpellAbility cropRotation = put("Crop Rotation", ai, ZoneType.Hand).getFirstSpellAbility();
            put("Grapeshot", ai, ZoneType.Hand);
            opp.setLife(3, null);
            game.getAction().checkStateEffects(true);
            stormCount(1);
            final ComboPlan combos = tutorStorm();
            final StormSolver.Line line = StormSolver.solve(ai, combos, STATES, BUDGET_NANOS);
            assertNotNull(line, "Crop Rotation, then a lethal Grapeshot: " + StormSolver.lastDiagnostic());
            assertEquals(line.steps.get(0).host, "Crop Rotation", "the search's line: " + line.steps);

            final StormSolver solver = new StormSolver();
            final String first = nameOf(solver.next(ai, combos));
            final String firstNote = solver.lastNote;
            final String second = nameOf(solver.next(ai, combos));
            final String secondNote = solver.lastNote;
            game.AI_FLAT_MONTE_CARLO = true; // the AI's own solver runs under Flat MC alone
            final List<SpellAbility> chosen = ((PlayerControllerAi) ai.getController()).chooseSpellAbilityToPlay();
            final String decision = chosen == null || chosen.isEmpty() ? null : nameOf(chosen.get(0));
            return Arrays.asList(first, second, secondNote, "Crop Rotation".equals(decision),
                    brain().hasFailedToCast(cropRotation), "first note: " + firstNote + "; decision: " + decision);
        });
        assertEquals(seen.subList(0, 5), Arrays.asList(null, null, "same state, no winning line", false, false),
                "the solver returns nothing, twice, the second time without searching again; the AI's decision does"
                        + " not make the play, and the final veto never met it: [first, second, second note, decision"
                        + " is Crop Rotation, a failed cast] = " + seen);
    }

    /**
     * The board of M19's stub rows: a tutor storm seat resolved Summoner's Pact in its first main phase and owes
     * {2}{G}{G} at its next upkeep, which Forest x2 and Mountain x2 pay exactly; Lightning Bolt and Crop Rotation (an
     * instant whose additional cost sacrifices a land) in hand. Bolt's {R} leaves the debt covered; Crop Rotation's
     * sacrifice would leave three lands for it, which debt protection refuses ("debt-unprotected"). Returns the two
     * cards' spell abilities, Bolt's first: the rows' stub lines are made of them.
     */
    private List<SpellAbility> boltAndCropRotationUnderASummonersPactDebt() {
        setUpGame();
        noDeadline();
        addTutorStormDeck(); // no green creature in it: the Pact finds nothing
        fillLibrary(opp, 10);
        at(PhaseType.MAIN1, ai);
        onStack("Summoner's Pact", ai);
        game.getPhaseHandler().onStackResolved();
        playUntilStackClear(game);
        assertTrue(game.getPhaseHandler().is(PhaseType.MAIN1, ai), "still the AI's first main phase");
        put("Forest", 2, ai, ZoneType.Battlefield);
        put("Mountain", 2, ai, ZoneType.Battlefield);
        final SpellAbility bolt = put("Lightning Bolt", ai, ZoneType.Hand).getFirstSpellAbility();
        final SpellAbility cropRotation = put("Crop Rotation", ai, ZoneType.Hand).getFirstSpellAbility();
        game.getAction().checkStateEffects(true);
        return List.of(bolt, cropRotation);
    }

    /**
     * RULINGS-R3 M19 at a followed step (review TA, finding 3): a step past the line's first, as Crop Rotation is in
     * Glint-Eye's [ritual, Crop Rotation, Grapeshot]. On {@link #boltAndCropRotationUnderASummonersPactDebt}'s board
     * the search is a stub whose line is [Lightning Bolt, Crop Rotation]: the solver starts the line with Bolt, which
     * leaves the debt covered, and asked again it returns nothing, its note "step refused (debt-unprotected)". Today
     * it follows the line into Crop Rotation.
     */
    @Test(timeOut = 300_000)
    public void aFollowedStepDebtProtectionRefusesIsNeverReturned() {
        final List<Object> seen = Scoped.under(new SimScope(113L), () -> {
            final List<SpellAbility> hand = boltAndCropRotationUnderASummonersPactDebt();
            final ComboPlan combos = tutorStorm();
            final StormSolver.Step cropRotation = new StormSolver.Step(hand.get(1));
            final StormSolver.Line line = new StormSolver.Line(
                    List.of(new StormSolver.Step(hand.get(0)), cropRotation), 0, 0L);
            final StormSolver.Search real = StormSolver.search;
            StormSolver.search = (p, c, n, b) -> line;
            try {
                final StormSolver solver = new StormSolver();
                final String first = nameOf(solver.next(ai, combos));
                final String second = nameOf(solver.next(ai, combos));
                return Arrays.asList(first, second, solver.lastNote,
                        "step refused (debt-unprotected): " + cropRotation);
            } finally {
                StormSolver.search = real;
            }
        });
        assertEquals(seen.subList(0, 3), Arrays.asList("Lightning Bolt", null, seen.get(3)),
                "the stub line's first step, then nothing for its refused second step: [first, second, note] = "
                        + seen.subList(0, 3));
    }

    /**
     * RULINGS-R3 M19 at a response step (review TA, finding 3): a step played in answer to the spell the line cast just
     * before, as Lion's Eye Diamond answers Infernal Tutor. On {@link #boltAndCropRotationUnderASummonersPactDebt}'s
     * board the search is a stub whose line is [Lightning Bolt, Crop Rotation in response]: the solver starts the line
     * with Bolt; with the AI's Bolt on the stack it expects the response, and it returns nothing, its note "step
     * refused (debt-unprotected)". Today it returns Crop Rotation in response to the Bolt.
     */
    @Test(timeOut = 300_000)
    public void aResponseStepDebtProtectionRefusesIsNeverReturned() {
        final List<Object> seen = Scoped.under(new SimScope(127L), () -> {
            final List<SpellAbility> hand = boltAndCropRotationUnderASummonersPactDebt();
            final ComboPlan combos = tutorStorm();
            final StormSolver.Step cropRotation = new StormSolver.Step(hand.get(1), true);
            final StormSolver.Line line = new StormSolver.Line(
                    List.of(new StormSolver.Step(hand.get(0)), cropRotation), 0, 0L);
            final StormSolver.Search real = StormSolver.search;
            StormSolver.search = (p, c, n, b) -> line;
            try {
                final StormSolver solver = new StormSolver();
                final String first = nameOf(solver.next(ai, combos));
                onStack("Lightning Bolt", ai, opp); // the line's Bolt cast: the AI's own spell is on the stack
                final boolean expects = solver.expectsResponse(ai);
                final String second = nameOf(solver.next(ai, combos));
                return Arrays.asList(first, expects, second, solver.lastNote,
                        "step refused (debt-unprotected): " + cropRotation);
            } finally {
                StormSolver.search = real;
            }
        });
        assertEquals(seen.subList(0, 4), Arrays.asList("Lightning Bolt", true, null, seen.get(4)),
                "the stub line's first step, then nothing for its refused response: [first, the response expected,"
                        + " second, note] = " + seen.subList(0, 4));
    }

    /**
     * RULINGS-R3 M19 at a setup's first step (review TA, finding 3): when the search finds no kill, the solver plays
     * the setup it left (StormSolver.lastSetup) as it plays a line. On
     * {@link #boltAndCropRotationUnderASummonersPactDebt}'s board the search is a stub that finds no line and leaves
     * the setup [Crop Rotation], through the private setter solve uses (by reflection, which reaches it at the parent
     * too): the solver returns nothing, its note "step refused (debt-unprotected)". Today it returns Crop Rotation, the
     * setup's first step.
     */
    @Test(timeOut = 300_000)
    public void aSetupStepDebtProtectionRefusesIsNeverReturned() {
        final List<Object> seen = Scoped.under(new SimScope(131L), () -> {
            final List<SpellAbility> hand = boltAndCropRotationUnderASummonersPactDebt();
            final ComboPlan combos = tutorStorm();
            final StormSolver.Step cropRotation = new StormSolver.Step(hand.get(1));
            final StormSolver.Search real = StormSolver.search;
            StormSolver.search = (p, c, n, b) -> {
                Reflect.callStatic(StormSolver.class, "setLastSetup", List.of(cropRotation)); // as solve leaves one
                return null;
            };
            try {
                final StormSolver solver = new StormSolver();
                final String first = nameOf(solver.next(ai, combos));
                return Arrays.asList(first, solver.lastNote, "step refused (debt-unprotected): " + cropRotation);
            } finally {
                StormSolver.search = real;
            }
        });
        assertEquals(seen.subList(0, 2), Arrays.asList(null, seen.get(2)),
                "nothing for the refused setup step: [first, note] = " + seen.subList(0, 2));
    }
}
