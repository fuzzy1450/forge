package forge.ai.simulation;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertTrue;

import java.util.List;
import java.util.concurrent.TimeUnit;

import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import forge.ai.simulation.FlatMcGame.Tally;
import forge.ai.simulation.FlatMonteCarloPicker.Config;
import forge.ai.simulation.FlatMonteCarloPicker.Mode;
import forge.ai.simulation.FlatMonteCarloPicker.Result;
import forge.game.card.Card;
import forge.game.phase.PhaseType;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import forge.util.SimScope;

/**
 * The fair pass line (MTG_DeckMaker docs/superpowers/specs/2026-11-17-flatmc-fair-pass-line-design.md): the pass line of a
 * main-phase decision whose heuristics approved nothing plays combat and main 2 in its rollout, as every play line does,
 * and its winning rollout ends the decision as a pass. Each decision here compares the pass with Runeclaw Bear, a play
 * the heuristics did not approve, at one round, so each line is rolled out once. A decision runs under a bound scope
 * ({@link Scoped}), whose tally counts each line's playouts: in main 1 a play line makes two (the rest of main 1, then
 * main 2), the pass line one, a hold none.
 */
public class FlatMcPassLineTest extends FlatMcTestBase {
    /** The Grizzly Bears in the AI's hand: PermanentAi keeps a plain creature for main 2. */
    private Card grizzly;
    /** The seat's tally, as the last {@link #decide} left it. */
    private Tally tally;

    @BeforeMethod
    public void bearAndForests() {
        setUpGame();
        grizzly = put("Grizzly Bears", ai, ZoneType.Hand);
        put("Forest", 4, ai, ZoneType.Battlefield);
        noDeadline();
        tally = null;
    }

    /** {@code card}'s first spell at {@code phase} of the AI's turn, its activating player set. */
    private SpellAbility castOf(final Card card, final PhaseType phase) {
        at(phase, ai);
        game.getAction().checkStateEffects(true);
        final SpellAbility sa = card.getFirstSpellAbility();
        sa.setActivatingPlayer(ai);
        return sa;
    }

    /** Runeclaw Bear's cast: the discovered play each test compares with passing. */
    private SpellAbility runeclawAt(final PhaseType phase) {
        return castOf(put("Runeclaw Bear", ai, ZoneType.Hand), phase);
    }

    /** {@link #twoLineConfig()} with one round and no extra rounds: each line is rolled out exactly once. */
    private Config oneRound() {
        final Config cfg = twoLineConfig();
        cfg.rolloutsPerCandidate = 1;
        cfg.extraRounds = 0;
        return cfg;
    }

    /** One main-phase decision under a fresh scope of seed {@code seed}, which leaves the seat's tally in {@link #tally}. */
    private Result decide(final List<SpellAbility> approved, final List<SpellAbility> extras, final Config cfg,
                          final long seed) {
        return decide(approved, extras, cfg, seed, Mode.MAIN);
    }

    /** One decision in {@code mode} under a fresh scope of seed {@code seed}, which leaves the seat's tally in {@link #tally}. */
    private Result decide(final List<SpellAbility> approved, final List<SpellAbility> extras, final Config cfg,
                          final long seed, final Mode mode) {
        return Scoped.under(new SimScope(seed), () -> {
            final Result r = FlatMonteCarloPicker.choose(ai, approved, extras, cfg, null, mode);
            tally = FlatMcGame.current().tally(ai.getId());
            return r;
        });
    }

    /**
     * Main 1, nothing approved: the pass line's rollout casts both bears in main 2, as the AI does after passing, so
     * casting Runeclaw Bear now gains nothing and the pass stands. Before the fix the pass line cast nothing, and the
     * bear won by the two bears the AI would have cast anyway.
     */
    @Test
    public void thePassLinePlaysMainTwo() {
        final Result r = decide(List.of(), List.of(runeclawAt(PhaseType.MAIN1)), oneRound(), 31L);
        assertEquals(tally.rollouts, 2L);
        assertEquals(tally.playouts, 3L, "the pass line's main 2; the play's main 1 and main 2");
        assertTrue(tally.plays >= 2, "the pass line's main 2 casts the bears: " + tally.toRecord());
        assertTrue(r.hold, String.valueOf(r));
        assertEquals(r.reason, "hold");
    }

    /** Something approved and an instant to hold the mana for: the hold line, line 1, still plays no more of the turn. */
    @Test
    public void theHoldLinePlaysNoMainTwo() {
        put("Giant Growth", ai, ZoneType.Hand);
        final Config cfg = oneRound();
        cfg.considerHolding = true;
        decide(List.of(castOf(grizzly, PhaseType.MAIN1)), List.of(), cfg, 32L);
        assertEquals(tally.rollouts, 2L, "the play and the hold");
        assertEquals(tally.playouts, 2L, "the play's main 1 and main 2; the hold none");
    }

    /** Main 2 has no later phase: the pass line plays nothing there and the play only its own continuation. */
    @Test
    public void mainTwoIsUnchanged() {
        final Result r = decide(List.of(), List.of(runeclawAt(PhaseType.MAIN2)), oneRound(), 33L);
        assertEquals(tally.playouts, 1L, "main 2 has no later phase: the play's own continuation alone");
        assertEquals(r.reason, "override", String.valueOf(r));
    }

    /** A ready Grizzly Bears against 2 life: the pass line's combat wins, and that ends the decision as a pass. */
    @Test
    public void aWinningPassEndsTheDecision() {
        put("Grizzly Bears", ai, ZoneType.Battlefield).setSickness(false);
        opp.setLife(2, null);
        final Result r = decide(List.of(), List.of(runeclawAt(PhaseType.MAIN1)), oneRound(), 34L);
        assertEquals(r.reason, "wins", String.valueOf(r));
        assertTrue(r.hold, "the pass, not the bear");
        assertNull(r.choice);
        assertEquals(r.rollouts, 1, "line 0 rolls first, and its win ends the decision");
    }

    /**
     * A pass line whose new main-2 playout passes the resolution cap leaves line 0 invalid: the decision keeps the pass,
     * and throws nothing. At 9 resolutions a rollout gets through this board's combat but not the pass line's main 2;
     * without that playout (65940c9d323) the same cap leaves line 0 valid and the decision holds.
     */
    @Test
    public void anUnsimulatablePassLineKeepsThePass() {
        final Config cfg = oneRound();
        cfg.resolutionsPerRollout = 9;
        final Result r = decide(List.of(), List.of(runeclawAt(PhaseType.MAIN1)), cfg, 35L);
        assertEquals(r.reason, "heuristic-unsimulatable", String.valueOf(r));
        assertTrue(r.hold);
        assertEquals(tally.playouts, 3L, "the pass line's main 2, where the cap is passed; the play's main 1 and main 2");
    }

    /**
     * A reactive decision's pass is not the pass line (spec section 5.1: the flag is set in Mode.MAIN alone). At the
     * opponent's end step, with nothing approved and a won position (a ready Grizzly Bears against 2 life, the AI's
     * turn next), the pass's winning rollout does not end the decision: Fog, the discovered play, is rolled out too, and
     * its win ends the decision, as at 65940c9d323. Whether a reactive pass should end a won decision is a question of
     * its own, which this pins as the spec left it.
     */
    @Test
    public void aReactivePassThatWinsDoesNotEndTheDecision() {
        fillLibrary(ai, 20); // the AI's next turn draws
        fillLibrary(opp, 20);
        put("Grizzly Bears", ai, ZoneType.Battlefield).setSickness(false);
        opp.setLife(2, null);
        final Card fog = put("Fog", ai, ZoneType.Hand);
        at(PhaseType.END_OF_TURN, opp);
        game.getAction().checkStateEffects(true);
        final SpellAbility sa = fog.getFirstSpellAbility();
        sa.setActivatingPlayer(ai);
        final Result r = decide(List.of(), List.of(sa), oneRound(), 37L, Mode.END_STEP);
        assertEquals(r.rollouts, 2, "the pass's winning rollout does not end a reactive decision: " + r);
        assertEquals(r.reason, "wins", "the position is won, and the next line's winning rollout ends it: " + r);
        assertFalse(r.hold, String.valueOf(r));
    }

    /** With no scope bound, as in the GUI, the pass line plays main 2 all the same. */
    @Test
    public void unboundThePassLinePasses() {
        final Config cfg = oneRound();
        cfg.budgetNanos = TimeUnit.SECONDS.toNanos(60); // the machine's clock decides nothing here
        final Result r = FlatMonteCarloPicker.choose(ai, List.of(), List.of(runeclawAt(PhaseType.MAIN1)), cfg, null,
                Mode.MAIN);
        assertTrue(r.decidedByRollouts, String.valueOf(r));
        assertTrue(r.hold, String.valueOf(r));
    }

    /** With the opponent's turn looked at, as on the GUI's maximized settings, it follows the pass line's main 2. */
    @Test
    public void theLookaheadFollowsThePassLinesMainTwo() {
        fillLibrary(ai, 20); // with an empty library the opponent loses at its draw step and plays no turn
        fillLibrary(opp, 20);
        final Config cfg = oneRound();
        cfg.opponentLookaheadPercent = 50;
        final Result r = decide(List.of(), List.of(runeclawAt(PhaseType.MAIN1)), cfg, 36L);
        assertEquals(tally.playouts, 7L, "each line's own playouts, plus two in the opponent's turn for each");
        assertTrue(r.hold, String.valueOf(r));
    }
}
