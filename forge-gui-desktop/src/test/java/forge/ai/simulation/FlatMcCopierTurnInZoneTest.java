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
 * A haste that ended on an earlier turn, decided by the search (MTG_DeckMaker
 * docs/superpowers/specs/2026-11-20-flatmc-copier-turninzone-design.md, test T5). Keldon Strike Team gives creatures
 * haste only on the turn it entered. Two Hill Giants that arrived this turn are sick in the game, so the Team attacks
 * alone and leaves the opponent at 1. Before the fix every card of a rollout copy had "entered this turn", the Team's
 * haste reached the Giants in every rollout, and the winning rollout ended the decision as {@code wins}.
 */
public class FlatMcCopierTurnInZoneTest extends FlatMcTestBase {

    @BeforeMethod
    public void mainOne() {
        setUpGame();
        noDeadline();
        at(PhaseType.MAIN1, ai);
        setTurn(5); // at() sets the turn back to 1 (the two-argument devModeSet); the board is turn 5's
        fillLibrary(ai, 20);
        fillLibrary(opp, 20);
    }

    /** The board: the Team (entered on {@code teamEntered}), two Giants that arrived this turn, the opponent at 4. */
    private void board(final int teamEntered) {
        final Card team = put("Keldon Strike Team", ai, ZoneType.Battlefield);
        team.setTurnInZone(teamEntered);
        team.setSickness(teamEntered == 5); // an old Team has been there since its turn; a fresh one only has its haste
        for (int i = 0; i < 2; i++) {
            put("Hill Giant", ai, ZoneType.Battlefield).setSickness(true);
        }
        opp.setLife(4, null); // the Team alone leaves 1; with the Giants it takes 9
        game.getAction().checkStateEffects(true);
    }

    /** A Forest from hand: a line the heuristics would play, and one that needs no mana. */
    private SpellAbility line() {
        final Card forest = put("Forest", ai, ZoneType.Hand);
        for (final SpellAbility sa : forest.getSpellAbilities()) {
            if (sa.isLandAbility()) {
                sa.setActivatingPlayer(ai);
                return sa;
            }
        }
        throw new IllegalStateException("no land ability on " + forest);
    }

    /** Every line rolled out once: a winning rollout would end the decision at once. */
    private Result decide(final SpellAbility play, final long seed) {
        final Config cfg = twoLineConfig();
        cfg.rolloutsPerCandidate = 1;
        cfg.extraRounds = 0;
        return Scoped.under(new SimScope(seed),
                () -> FlatMonteCarloPicker.choose(ai, List.of(), List.of(play), cfg, null, Mode.MAIN));
    }

    @Test
    public void aHasteThatEndedOnAnEarlierTurnWinsNoRollout() {
        board(2);
        final Result r = decide(line(), 43L);
        assertNotEquals(r.reason, "wins", String.valueOf(r));
        // and not by failing: both lines were rolled out and compared
        assertEquals(r.candidatesEvaluated, 2, String.valueOf(r));
        assertFalse(Set.of("error", "deadline", "heuristic-unsimulatable", "no-time", "too-slow", "low-memory",
                "single-candidate").contains(r.reason), String.valueOf(r));
    }

    /** A preservation pin: on the turn the Team entered, its haste is real and the attack wins, before and after. */
    @Test
    public void aHasteFromThisTurnStillWins() {
        board(5);
        final Result r = decide(line(), 44L);
        assertEquals(r.reason, "wins", String.valueOf(r));
    }
}
