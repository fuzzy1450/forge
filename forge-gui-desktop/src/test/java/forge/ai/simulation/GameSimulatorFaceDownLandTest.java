package forge.ai.simulation;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertTrue;

import java.util.Collections;
import java.util.List;

import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import forge.ai.simulation.FlatMonteCarloPicker.Config;
import forge.ai.simulation.FlatMonteCarloPicker.Mode;
import forge.ai.simulation.FlatMonteCarloPicker.Result;
import forge.game.ability.AbilityUtils;
import forge.game.card.Card;
import forge.game.phase.PhaseType;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import forge.util.SimScope;

/**
 * A land that the seat's Petty Larceny exiled face down, which the seat may play for as long as it stays exiled, as a
 * rollout plays it (MTG_DeckMaker's tools/combo-ai/flat-mc/SHIP-PLAN.md, Task 7a: 23 of the 25 Errors of the census on
 * 2.0.15+23fd6633b91, H:/mtg-combo-lanes/flatmc-ship/diag/drake.md). The real land ability's canPlay() turns an LKI
 * copy of the face-down card face up (LandAbility.getAlternateHost), which takes the real game's next timestamp, so a
 * rollout that asked it wrote the real game, and the real-game check ended the game as an Error. The simulator asks
 * and plays the copy's own land ability instead.
 */
public class GameSimulatorFaceDownLandTest extends FlatMcTestBase {
    /** The opponent's Exotic Orchard, face down in exile where the seat's Petty Larceny put it. */
    private Card orchard;

    /** The seat's Main 1 with the AI deadline off: Petty Larceny, resolved for the seat against the opponent, exiles
     *  the opponent's whole library, an Exotic Orchard and an Island, face down, with the Larceny's grant to play
     *  them. */
    @BeforeMethod
    public void pettyLarceny() {
        setUpGame();
        noDeadline();
        at(PhaseType.MAIN1, ai);
        final Card inLibrary = put("Exotic Orchard", opp, ZoneType.Library);
        put("Island", opp, ZoneType.Library);
        final SpellAbility larceny = put("Petty Larceny", ai, ZoneType.Hand).getFirstSpellAbility();
        larceny.setActivatingPlayer(ai);
        larceny.getTargets().add(opp);
        AbilityUtils.resolve(larceny);
        game.getAction().checkStateEffects(true);
        orchard = game.getCardState(inLibrary);
        assertTrue(orchard.isFaceDown() && orchard.isInZone(ZoneType.Exile), "the Larceny exiled it face down");
    }

    /**
     * The copy plays the land through its own land ability: the copy's Orchard is on the copy's battlefield, face up,
     * the real game's timestamp is as it was, nothing else the real-game check reads has moved, and the real Orchard
     * is still face down in exile. The real ability's own canPlay() moves the real timestamp by one (upstream's
     * LandAbility.getAlternateHost): what a rollout must not ask.
     */
    @Test
    public void theCopyAsksAndPlaysItsOwnLandAbility() {
        final SpellAbility play = landAbilityOf(orchard);
        assertNotNull(play.getMayPlay(), "the Orchard is played by the Larceny's grant");
        final long asked = game.getTimestamp();
        assertTrue(play.canPlay());
        assertEquals(game.getTimestamp(), asked + 1, "the real ability's canPlay() takes a real timestamp");

        final long before = game.getTimestamp();
        final RealGameSnapshot snapshot = RealGameSnapshot.of(game);
        final GameSimulator sim = GameSimulator.forRollout(game, ai, false);
        assertTrue(sim.applySpellAbility(play), "the copy plays the Orchard");
        assertEquals(game.getTimestamp(), before, "the real game's timestamp");
        snapshot.requireSame(game);
        final Card real = game.getCardState(orchard);
        assertTrue(real.isFaceDown() && real.isInZone(ZoneType.Exile), "the real Orchard stays face down in exile");
        final Card copy = sim.getSimulatedGameState().findById(orchard.getId());
        assertTrue(copy.isInZone(ZoneType.Battlefield) && !copy.isFaceDown(), copy + " in " + copy.getZone());
        assertEquals(copy.getName(), "Exotic Orchard");
        assertEquals(copy.getController(), sim.getSimulatedAiPlayer());
    }

    /**
     * A land choice between a Mountain from the seat's hand and the face-down Orchard rolls both out under a bound
     * scope, with the configuration a full Flat MC seat's land choice uses, and every rollout passes the real-game
     * check: the decision returns after its eight rollouts and the real timestamp is as it was.
     */
    @Test
    public void aLandChoiceOverTheFaceDownLandPassesTheRealGameCheck() {
        final List<SpellAbility> lands = List.of(landAbilityOf(put("Mountain", ai, ZoneType.Hand)),
                landAbilityOf(orchard));
        final Config cfg = twoLineConfig();
        cfg.holdForbidden = true;
        cfg.extraCandidates = 0;
        final long before = game.getTimestamp();
        final Result r = Scoped.under(new SimScope(23L), () -> FlatMonteCarloPicker.choose(ai, lands,
                Collections.emptyList(), cfg, null, Mode.MAIN));
        assertEquals(r.rollouts, 8, String.valueOf(r));
        assertEquals(game.getTimestamp(), before, "the real game's timestamp");
    }

    /** The first land ability {@code land} offers the seat, as the controller's land choice takes it. */
    private SpellAbility landAbilityOf(final Card land) {
        for (final SpellAbility sa : land.getAllPossibleAbilities(ai, true)) {
            if (sa.isLandAbility()) {
                return sa;
            }
        }
        throw new AssertionError(land + " offers no land ability");
    }
}
