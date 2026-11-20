package forge.ai.simulation;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import forge.ai.simulation.FlatMonteCarloPicker.Config;
import forge.ai.simulation.FlatMonteCarloPicker.Result;
import forge.game.GameEntity;
import forge.game.card.Card;
import forge.game.combat.Combat;
import forge.game.phase.PhaseType;
import forge.game.zone.ZoneType;
import forge.util.SimScope;

/**
 * The attack search's early win: an attack set whose rollout wins the game ends the decision, and that set is the one
 * the search returns and the AI declares. Before the fix the early win returned no attack set, so the controller kept
 * the heuristics' attack even when another set's rollout had won. Each decision runs under a bound scope
 * ({@link Scoped}), whose counted clock makes the budget a number of rollouts, so every line of the first round is rolled
 * out on any machine.
 */
public class FlatMcAttackEarlyWinTest extends FlatMcTestBase {

    @BeforeMethod
    public void libraries() {
        setUpGame();
        noDeadline();
        put("Mountain", 10, ai, ZoneType.Library);
        put("Plains", 10, opp, ZoneType.Library);
    }

    private Card ready(final String name, final boolean ours) {
        final Card c = put(name, ours ? ai : opp, ZoneType.Battlefield);
        c.setSickness(false);
        return c;
    }

    /**
     * Hill Giant and Grizzly Bears against 5 life with nothing to block them, the bears alone in the combat as the
     * heuristics' attack. The lines are the bears alone (2 damage), no attack, and everything (5 damage, lethal); one
     * round rolls them out in that order, and the third line's win ends the decision: its set is the one returned.
     */
    @Test
    public void aWinningAttackSetIsTheOneReturned() {
        final Card giant = ready("Hill Giant", true);
        final Card bears = ready("Grizzly Bears", true);
        opp.setLife(5, null);
        at(PhaseType.COMBAT_DECLARE_ATTACKERS, ai);
        game.getAction().checkStateEffects(true);
        final Combat combat = game.getCombat();
        combat.addAttacker(bears, opp);
        final Config cfg = Config.fromProfile(brain(), ai);
        cfg.rolloutsPerCandidate = 1;
        cfg.extraRounds = 0;
        cfg.pruning = false;
        final Result r = Scoped.under(new SimScope(41L), () -> FlatMonteCarloPicker.chooseAttack(ai, combat, cfg));
        assertEquals(r.reason, "wins", String.valueOf(r));
        assertEquals(r.rollouts, 3, "the bears alone and no attack do not win, everything (the third line) does");
        final Map<Card, GameEntity> everything = new LinkedHashMap<>();
        everything.put(bears, opp);
        everything.put(giant, opp);
        assertEquals(r.attackers, everything, "the set whose rollout won, returned: " + r.attackers);
    }

    /**
     * Hellrider and two Grizzly Bears, all ready, against a ready Craw Wurm and 6 life, the AI at 6 life. Attacking with
     * all three is lethal whatever the Wurm blocks: Hellrider deals 1 damage for each attacker (3), and at least 4 of the
     * 7 power gets through. The heuristics count combat damage alone, with the Wurm blocking the strongest attacker (4 of
     * 6: AiAttackController.doAssault, where ComputerUtil.possibleNonCombatDamage counts no DealDamage attack trigger),
     * and the Wurm's attack back would kill the AI, so they keep a blocker home.
     */
    private void hellriderBoard() {
        ready("Hellrider", true);
        ready("Grizzly Bears", true);
        ready("Grizzly Bears", true);
        ready("Craw Wurm", false);
        opp.setLife(6, null);
        ai.setLife(6, null);
    }

    /** Who attacks, sorted, when the AI declares its attack with the attack search on or off, under a bound scope. */
    private List<String> declared(final boolean search, final long seed) {
        brain().setFlatMonteCarloOverride(true);
        brain().setFlatMonteCarloAttackOverride(search);
        return Scoped.under(new SimScope(seed), () -> {
            game.getPhaseHandler().devModeSet(PhaseType.MAIN1, ai);
            game.getAction().checkStateEffects(true);
            game.getPhaseHandler().devAdvanceToPhase(PhaseType.COMBAT_DECLARE_ATTACKERS);
            final List<String> names = new ArrayList<>();
            for (final Card c : game.getCombat().getAttackers()) {
                names.add(c.getName());
            }
            Collections.sort(names);
            return names;
        });
    }

    /** The premise of {@link #aWinningAllInIsTheOneDeclared}: without the search the AI does not attack with all three. */
    @Test
    public void theHeuristicsKeepABlockerHome() {
        hellriderBoard();
        final List<String> attack = declared(false, 42L);
        assertTrue(attack.size() < 3, "the heuristics' attack: " + attack);
    }

    /**
     * The search compares the heuristics' attack with no attack and with everything; the all-in's rollout wins, which
     * ends the decision, and the AI declares the all-in.
     */
    @Test
    public void aWinningAllInIsTheOneDeclared() {
        hellriderBoard();
        final List<String> attack = declared(true, 43L);
        final Result r = brain().getLastFlatMonteCarloResult();
        assertNotNull(r, "the attack was searched");
        assertEquals(r.reason, "wins", String.valueOf(r));
        assertEquals(attack, List.of("Grizzly Bears", "Grizzly Bears", "Hellrider"), "declared: " + attack);
    }
}
