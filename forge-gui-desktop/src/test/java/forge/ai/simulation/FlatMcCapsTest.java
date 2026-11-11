package forge.ai.simulation;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNotEquals;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import forge.ai.simulation.FlatMcGame.Tally;
import forge.ai.simulation.FlatMonteCarloPicker.Config;
import forge.ai.simulation.FlatMonteCarloPicker.Result;
import forge.game.Game;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import forge.util.SimScope;

/**
 * The limits on a flat_mc seat's work and its tally (MTG_DeckMaker's tools/combo-ai/flat-mc/SPEC.md, sections 4.4 and
 * 4.7): the cap on stack resolutions per rollout, the per-game cap on rollouts, the turn and decision budgets, and the
 * counts a searching seat's record carries. The picker's decisions compare {@link #twoBears()}'s two lines at
 * {@link #twoLineConfig()}, as FlatMcClockTest does, so "the 450 ms decision" makes eight rollouts and "the 2 ms
 * decision" four; the controller's decisions run under FlatMcProfiles' profiles with a flat_mc mark. Everything is
 * counted under a bound scope only, so every decision here runs through {@link Scoped}.
 */
public class FlatMcCapsTest extends FlatMcTestBase {
    /** SHA-256 of no bytes: a tally's hash before any fold. */
    private static final String SHA256_OF_NOTHING = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";

    private List<SpellAbility> two;
    private Config cfg;
    /** The seat's tally, as the last {@link #decide} left it. */
    private Tally tally;

    @BeforeMethod
    public void profilesAndTwoLines() {
        FlatMcProfiles.install();
        setUpGame();
        two = twoBears();
        assertEquals(two.size(), 2, String.valueOf(two));
        cfg = twoLineConfig();
        tally = null;
    }

    @AfterClass
    public void shippedProfiles() {
        FlatMcProfiles.restore();
    }

    /**
     * At one resolution per rollout each line's rollout is stopped as its own spell resolves, so neither line has a
     * value and the decision keeps the heuristic's choice; the stops are counted apart from rollout failures. At 500
     * the same decision runs as it always has.
     */
    @Test
    public void theResolutionCapInvalidatesALineAtItsLimit() {
        cfg.resolutionsPerRollout = 1;
        final Result capped = decide(two, 4L);
        assertEquals(capped.reason, "heuristic-unsimulatable", String.valueOf(capped));
        assertTrue(tally.resolutionCapStops >= 2, String.valueOf(tally.toRecord()));
        assertEquals(tally.rolloutFailures, 0L);
        assertEquals(tally.rollouts, tally.resolutionCapStops, "every rollout reached the cap");
        assertEquals(tally.invalidLines, tally.resolutionCapStops, "and left its line without a value");

        cfg.resolutionsPerRollout = 500;
        final Result free = decide(two, 4L);
        assertEquals(tally.resolutionCapStops, 0L);
        assertNotEquals(free.reason, "heuristic-unsimulatable", String.valueOf(free));
    }

    /**
     * Inside an open rollout each resolver counts its entry and each pass of its loop: two of the seat's loots on the
     * stack are one entry and two passes for resolveOwnTop, and one more loot is one entry and one pass for
     * resolveStack. The loots resolve on a copy of the game, as a rollout's do, since the bracket checks that the game
     * itself is left as it was.
     */
    @Test
    public void eachEntryAndEachPassOfAResolverIsCounted() {
        fillLibrary(ai, 3);
        final int aiAt = game.getPlayers().indexOf(ai);
        final int oppAt = game.getPlayers().indexOf(opp);
        final long[] seen = Scoped.under(new SimScope(14L), () -> {
            final long[] counts = new long[2];
            FlatMcGame.bracket(ai, 0, () -> {
                final Tally t = FlatMcGame.current().tally(ai.getId());
                final Game copy = new GameCopier(game).makeCopy();
                final Player copyAi = copy.getPlayers().get(aiAt);
                final Player copyOpp = copy.getPlayers().get(oppAt);
                pushAbilities(copy, copyAi, 2);
                GameSimulator.resolveOwnTop(copy, copyAi, copyOpp);
                counts[0] = t.resolutions;
                pushAbilities(copy, copyAi, 1);
                GameSimulator.resolveStack(copy, copyOpp);
                counts[1] = t.resolutions;
                return 0L;
            });
            return counts;
        });
        assertEquals(seen[0], 3L, "resolveOwnTop: its entry and two passes");
        assertEquals(seen[1], 5L, "resolveStack: its entry and one pass more");
    }

    /**
     * Resolutions are counted only while a rollout is open: after a decision whose rollouts all hit a cap of 1, two
     * resolutions of the real game's stack on the same scope count nothing and are refused nothing. A rollout left open
     * after the decision would refuse them at its stale cap of 1.
     */
    @Test
    public void resolutionsAreNotCountedOutsideARollout() {
        cfg.resolutionsPerRollout = 1;
        final long[] seen = Scoped.under(new SimScope(4L), () -> {
            FlatMonteCarloPicker.choose(ai, two, cfg, null);
            final Tally t = FlatMcGame.current().tally(ai.getId());
            final long[] counts = { t.resolutionCapStops, t.resolutions, 0L };
            GameSimulator.resolveStack(game, opp);
            GameSimulator.resolveStack(game, opp);
            counts[2] = t.resolutions;
            return counts;
        });
        assertTrue(seen[0] >= 2, "the decision's rollouts hit the cap of 1");
        assertTrue(seen[1] > 0, "and their resolutions were counted");
        assertEquals(seen[2], seen[1], "the real game's resolutions were not");
    }

    /**
     * Once a seat's rollouts reach its word's per-game cap, its main-phase decisions get no configuration: each such
     * refusal is counted, and the turn of the first is kept.
     */
    @Test
    public void theGameCapRefusesTheDecisionsAfterIt() {
        searchingSeat("FlatSearch", "flat_mc:4:500");
        setTurn(5);
        Scoped.under(new SimScope(5L), () -> {
            assertNotNull(Reflect.call(brain(), "mainPhaseConfig"));
            for (int i = 0; i < 4; i++) {
                FlatMcGame.current().charge(ai.getId());
            }
            assertNull(Reflect.call(brain(), "mainPhaseConfig"));
            final Tally t = FlatMcGame.current().tally(ai.getId());
            assertEquals(t.gameCapStops, 1L);
            assertEquals(t.gameCapTurn, Integer.valueOf(5));
            setTurn(7);
            assertNull(Reflect.call(brain(), "mainPhaseConfig"), "the cap holds for the rest of the game");
            assertEquals(t.gameCapStops, 2L);
            assertEquals(t.gameCapTurn, Integer.valueOf(5), "the turn of the first refusal is kept");
            assertEquals(t.turnBudgetStops, 0L);
            return null;
        });
    }

    /**
     * With the turn's 6 ms spent, FlatSearchTight's seat gets no configuration until the next turn. The refusal is
     * counted where the main-phase search asks for its configuration, through chooseSpellAbilityToPlay as well, and
     * not where a land decision asks (flatMonteCarloConfig alone).
     */
    @Test
    public void theTurnBudgetRefusesOnceItIsSpent() {
        searchingSeat("FlatSearchTight", "flat_mc:40:8");
        setTurn(5);
        Reflect.set(brain(), "flatMcTurn", 5);
        Reflect.set(brain(), "flatMcTurnSpentNanos", 6_000_000L);
        Scoped.under(new SimScope(6L), () -> {
            final Tally t = FlatMcGame.current().tally(ai.getId());
            assertNull(Reflect.call(brain(), "flatMonteCarloConfig"));
            assertEquals(t.turnBudgetStops, 0L, "the call a land decision makes counts nothing");
            assertNull(Reflect.call(brain(), "mainPhaseConfig"));
            assertEquals(t.turnBudgetStops, 1L);
            assertNotNull(brain().chooseSpellAbilityToPlay(), "the heuristics still play a bear");
            assertEquals(t.turnBudgetStops, 2L, "the main-phase decision counts its refusal");
            setTurn(6);
            assertNotNull(Reflect.call(brain(), "mainPhaseConfig"));
            assertEquals(t.turnBudgetStops, 2L);
            assertEquals(t.gameCapStops, 0L);
            return null;
        });
    }

    /** A seat with no decision budget never searches: its refusals are no stops, and nothing is counted. */
    @Test
    public void aSeatWithNoDecisionBudgetCountsNoStop() {
        searchingSeat("FlatSearchOff", "flat_mc");
        final Tally t = Scoped.under(new SimScope(7L), () -> {
            assertNull(Reflect.call(brain(), "mainPhaseConfig"));
            assertNotNull(brain().chooseSpellAbilityToPlay());
            return FlatMcGame.current().tally(ai.getId());
        });
        assertEquals(t.turnBudgetStops, 0L);
        assertEquals(t.gameCapStops, 0L);
        assertEquals(t.decisions, 0L);
        assertEquals(t.rollouts, 0L);
    }

    /** The 2 ms decision stops before its third round (the round would end past the soft deadline); the 450 ms
     *  decision runs all four rounds and stops nowhere. */
    @Test
    public void theBudgetStopIsCounted() {
        decide(two, 7L);
        assertEquals(tally.rollouts, 8L);
        assertEquals(tally.decisionBudgetStops, 0L);
        cfg.budgetNanos = 2_000_000L;
        decide(two, 8L);
        assertEquals(tally.rollouts, 4L);
        assertEquals(tally.decisionBudgetStops, 1L);
    }

    /**
     * With a third line the 2 ms decision stops inside its first round, at the per-line check: the third rollout would
     * end at 3 ms, past the soft deadline of 2 ms, while the second may run to the hard one.
     */
    @Test
    public void theBudgetStopInsideARoundIsCounted() {
        put("Balduvian Bears", ai, ZoneType.Hand);
        final List<SpellAbility> three = playsToCompare(ai);
        assertEquals(three.size(), 3, String.valueOf(three));
        cfg.budgetNanos = 2_000_000L;
        decide(three, 9L);
        assertEquals(tally.rollouts, 2L);
        assertEquals(tally.decisionBudgetStops, 1L);
    }

    /**
     * Each rollout's one playout casts the other bear. At the profile's limit it then stops because the heuristics
     * have nothing more to play; at a limit of one play (adaptive playouts off, which would raise it to the hand's
     * spells and two) it stops at its limit.
     */
    @Test
    public void aPlayoutAtItsPlayLimitIsCounted() {
        decide(two, 11L);
        assertTrue(tally.plays > 0, String.valueOf(tally.toRecord()));
        assertEquals(tally.playoutsAtPlayLimit, 0L);
        cfg.adaptivePlayout = false;
        cfg.maxPlayoutPlays = 1;
        decide(two, 11L);
        assertEquals(tally.playouts, tally.rollouts, String.valueOf(tally.toRecord()));
        assertEquals(tally.plays, tally.playouts);
        assertEquals(tally.playoutsAtPlayLimit, tally.playouts);
    }

    /**
     * A decision's rollouts in the tally are its charges, and the largest decision is kept. With the re-check refusing
     * the second line, that line is never rolled out: the picker's result counts it among its rollouts, the tally
     * counts the one charge (the spec's "rollouts"). The 450 ms decision then charges eight.
     */
    @Test
    public void theTallyKeepsTheLargestDecisionsCharges() {
        final SpellAbility first = two.get(0);
        Scoped.under(new SimScope(10L), () -> {
            final FlatMcGame st = FlatMcGame.current();
            final Tally t = st.tally(ai.getId());
            final Result refused = FlatMonteCarloPicker.choose(ai, two, cfg, sa -> sa == first);
            st.noteDecision(ai.getId(), refused);
            assertEquals(refused.rollouts, 2, String.valueOf(refused));
            assertEquals(t.maxDecisionRollouts, 1L, "the refused line was never rolled out, so never charged");
            assertEquals(t.invalidLines, 0L, "and is no rollout's invalid line");
            st.noteDecision(ai.getId(), FlatMonteCarloPicker.choose(ai, two, cfg, null));
            assertEquals(t.maxDecisionRollouts, 8L);
            st.noteDecision(ai.getId(), FlatMonteCarloPicker.choose(ai, two, cfg, sa -> sa == first));
            assertEquals(t.maxDecisionRollouts, 8L, "the largest decision is kept");
            assertEquals(t.decisions, 3L);
            assertEquals(t.rollouts, 10L);
            assertEquals(t.maxDecisionLines, 2L);
            return null;
        });
    }

    /** One decision of a full-count seat through the controller fills its tally, and its record has every key in
     *  order. */
    @Test
    public void aSearchedDecisionFillsTheTally() {
        searchingSeat("FlatSearch", "flat_mc");
        final int seat = game.getPlayers().indexOf(ai);
        final Map<String, Object> rec = Scoped.under(new SimScope(13L), () -> {
            FlatMcGame.current().registerSeat(seat, ai.getId());
            assertNotNull(brain().chooseSpellAbilityToPlay());
            tally = FlatMcGame.current().tally(ai.getId());
            return tally.toRecord();
        });
        final Result decided = brain().getLastFlatMonteCarloResult();
        final Tally t = tally;
        assertEquals(t.decisions, 1L);
        assertTrue(t.rollouts > 0, String.valueOf(rec));
        assertEquals(t.maxDecisionRollouts, t.rollouts);
        assertEquals(t.maxDecisionLines, (long) decided.candidatesEvaluated);
        assertTrue(t.playouts >= t.rollouts, String.valueOf(rec));
        assertTrue(t.plays > 0, String.valueOf(rec));
        assertTrue(t.resolutions > 0, String.valueOf(rec));
        assertTrue(t.cardsCopied > 0, String.valueOf(rec));
        assertEquals(t.reasons, Map.of(decided.reason, 1L));
        assertFalse(t.reasons.containsKey("too-slow"));
        assertFalse(t.reasons.containsKey("no-time"));
        assertFalse(t.tutorStorm);
        assertEquals(t.seat, seat);

        assertEquals(new ArrayList<>(rec.keySet()), FlatMcGame.TALLY_KEYS);
        assertEquals(rec.get("seat"), Integer.valueOf(seat));
        assertEquals(rec.get("rollouts"), Long.valueOf(t.rollouts));
        assertEquals(rec.get("reasons"), t.reasons);
        assertNull(rec.get("game_cap_turn"));
        assertEquals(rec.get("tutor_storm"), Boolean.FALSE);
        assertTrue(String.valueOf(rec.get("hash")).matches("[0-9a-f]{64}"), String.valueOf(rec.get("hash")));
        assertNotEquals(rec.get("hash"), SHA256_OF_NOTHING, "the decision's rollouts are folded into the seat's hash");
    }

    /** A seat whose deck reads as tutor storm is marked so in its tally when its configuration is read. The game is
     *  fresh: the deck plan is read once, and the two bears' configuration has read this one's. */
    @Test
    public void aTutorStormDeckIsNoted() {
        setUpGame();
        addTutorStormDeck();
        searchingSeat("FlatSearch", "flat_mc");
        final Tally t = Scoped.under(new SimScope(12L), () -> {
            Reflect.call(brain(), "flatMonteCarloConfig");
            return FlatMcGame.current().tally(ai.getId());
        });
        assertTrue(brain().deckPlan(ai).combos.has(ComboPlan.Kind.TUTOR_STORM), "the library reads as tutor storm");
        assertTrue(t.tutorStorm);
    }

    /** With no scope bound, as in the GUI, the bracket runs its body alone: nothing is counted and no cap applies. */
    @Test
    public void unboundTheBracketRunsItsBodyAlone() {
        assertEquals(FlatMcGame.bracket(ai, 1, () -> {
            FlatMcGame.countResolution();
            FlatMcGame.countResolution();
            FlatMcGame.notePlayout();
            return 7L;
        }), Long.valueOf(7L));
    }

    /** The seat under test as a searching seat: the profile, its flat_mc word as the mark, and the override on. */
    private void searchingSeat(final String profileName, final String word) {
        profile(profileName);
        brain().setFlatMcSeat(FlatMcSeat.parse(word));
        brain().setFlatMonteCarloOverride(true);
    }

    /** One picker decision of {@code lines} at {@link #cfg} under a fresh scope of seed {@code seed}, which leaves the
     *  seat's tally in {@link #tally}. */
    private Result decide(final List<SpellAbility> lines, final long seed) {
        return Scoped.under(new SimScope(seed), () -> {
            final Result r = FlatMonteCarloPicker.choose(ai, lines, cfg, null);
            tally = FlatMcGame.current().tally(ai.getId());
            return r;
        });
    }
}
