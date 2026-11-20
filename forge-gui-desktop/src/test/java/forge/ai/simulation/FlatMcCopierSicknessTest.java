package forge.ai.simulation;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNotEquals;

import java.util.List;
import java.util.Set;

import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import forge.ai.simulation.FlatMonteCarloPicker.Config;
import forge.ai.simulation.FlatMonteCarloPicker.Mode;
import forge.ai.simulation.FlatMonteCarloPicker.Result;
import forge.game.card.Card;
import forge.game.phase.PhaseType;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import forge.util.SimScope;

/**
 * The Lightning Greaves storm's position, decided by the search (MTG_DeckMaker
 * docs/superpowers/specs/2026-11-18-flatmc-copier-sickness-design.md, test T5). Two creatures arrived this turn and the
 * Greaves is on the first. Moving it to the second leaves the first sick in the real game, so the move adds no attacker;
 * a rollout must not count the first as one. Before the fix the copy took the first's sickness masked by the Greaves'
 * haste, both attacked in the move's rollout, and that one winning rollout ended the decision as {@code wins}.
 */
public class FlatMcCopierSicknessTest extends FlatMcTestBase {

    @BeforeMethod
    public void mainOne() {
        setUpGame();
        noDeadline();
        at(PhaseType.MAIN1, ai);
        fillLibrary(ai, 20);
        fillLibrary(opp, 20);
    }

    private Card creature(final boolean arrivedThisTurn) {
        final Card c = put("Hill Giant", ai, ZoneType.Battlefield);
        c.setSickness(arrivedThisTurn);
        return c;
    }

    private Card greavesOn(final Card holder) {
        final Card greaves = put("Lightning Greaves", ai, ZoneType.Battlefield);
        greaves.attachToEntity(holder, null);
        return greaves;
    }

    /** The Greaves' Equip ability, aimed at {@code to}. */
    private SpellAbility equipMove(final Card greaves, final Card to) {
        for (final SpellAbility sa : greaves.getSpellAbilities()) {
            if (sa.isEquip()) {
                sa.setActivatingPlayer(ai);
                sa.resetTargets();
                sa.getTargets().add(to);
                return sa;
            }
        }
        throw new IllegalStateException("no Equip ability on " + greaves);
    }

    /** Every line rolled out once: a winning rollout of the move would end the decision at once. */
    private Result decide(final SpellAbility equip, final long seed) {
        final Config cfg = twoLineConfig();
        cfg.rolloutsPerCandidate = 1;
        cfg.extraRounds = 0;
        return Scoped.under(new SimScope(seed),
                () -> FlatMonteCarloPicker.choose(ai, List.of(), List.of(equip), cfg, null, Mode.MAIN));
    }

    @Test
    public void aMoveThatOnlyLooksLikeAWinDoesNotWin() {
        final Card first = creature(true);
        final Card second = creature(true);
        final Card greaves = greavesOn(first);
        opp.setLife(4, null); // one Hill Giant leaves 1; two take 6
        game.getAction().checkStateEffects(true);

        final Result r = decide(equipMove(greaves, second), 41L);
        assertNotEquals(r.reason, "wins", String.valueOf(r));
        // and not by failing: both lines were rolled out and compared
        assertEquals(r.candidatesEvaluated, 2, String.valueOf(r));
        assertFalse(Set.of("error", "deadline", "heuristic-unsimulatable", "no-time", "too-slow", "low-memory",
                "single-candidate").contains(r.reason), String.valueOf(r));
    }

    /** A preservation pin: the move that really adds an attacker still wins, before the fix and after it. */
    @Test
    public void aMoveThatReallyWinsStillWins() {
        final Card old = creature(false);
        final Card fresh = creature(true);
        final Card greaves = greavesOn(old);
        opp.setLife(4, null);
        game.getAction().checkStateEffects(true);

        final Result r = decide(equipMove(greaves, fresh), 42L);
        assertEquals(r.reason, "wins", String.valueOf(r));
        assertFalse(r.hold, String.valueOf(r));
    }
}
