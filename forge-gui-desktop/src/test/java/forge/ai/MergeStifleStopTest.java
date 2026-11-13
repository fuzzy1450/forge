package forge.ai;

import static org.testng.AssertJUnit.assertEquals;
import static org.testng.AssertJUnit.assertNotNull;
import static org.testng.AssertJUnit.assertNull;

import java.util.List;

import org.testng.annotations.Test;

import forge.game.Game;
import forge.game.ability.AbilityKey;
import forge.game.card.Card;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;

/**
 * StifleCombo's two picks in AiController.getSpellAbilityToPlay, the Dreadnought's cast and the Stifle of its own trigger,
 * return without canPlayAndPayFor, so they stood outside the seed-11 rule's two tries a phase and upstream's memory of a
 * cast that did not go on the stack: a payment that disagrees with canPayCost for good was picked again until
 * PhaseHandler's cap of 999, and the Stifle ignored even the one-choice skip. They answer to both now, as ComboPlan's PLAY
 * ruling does (MergeR8PaymentTest), and each stop ends with the phase or the turn as it does there. Since upstream's
 * clean-up of the Flat MC sure plays (Card-Forge #12764) both picks are made only where Flat Monte Carlo is on, so the
 * seat here has it on.
 */
public class MergeStifleStopTest extends AITest {
    private static final String DREADNOUGHT = "Phyrexian Dreadnought";
    private static final String STIFLE = "Stifle";

    private Game game;
    private Player ai;
    private AiController brain;

    private void start() {
        game = initAndCreateGame();
        ai = game.getPlayers().get(1);
        ((LobbyPlayerAi) ai.getLobbyPlayer()).setAiProfile("Default");
        fillLibrary(ai, 10);
        addCard("Underground Sea", ai);
        addCard("Tundra", ai);
        brain = ((PlayerControllerAi) ai.getController()).getAi();
        brain.setFlatMonteCarloOverride(true); // StifleCombo's picks are Flat MC's since Card-Forge #12764
    }

    private SpellAbility nextPlay() {
        final List<SpellAbility> chosen = brain.chooseSpellAbilityToPlay();
        return chosen == null || chosen.isEmpty() ? null : chosen.get(0);
    }

    private SpellAbility expect(final String name) {
        final SpellAbility pick = nextPlay();
        assertNotNull("the harness: the pick of " + name, pick);
        assertEquals(name, pick.getHostCard().getName());
        return pick;
    }

    /** Our Main 1 with the Dreadnought and its Stifle in hand and both payable: StifleCombo.sureCast's board. */
    private Card castBoard() {
        start();
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, ai);
        game.getAction().checkStateEffects(true);
        final Card dreadnought = addCardToZone(DREADNOUGHT, ai, ZoneType.Hand);
        addCardToZone(STIFLE, ai, ZoneType.Hand);
        return dreadnought;
    }

    /** The Dreadnought in play with its trigger on the stack, a Stifle in hand: StifleCombo.stifleOwnTrigger's board. */
    private Card triggerBoard() {
        start();
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, ai);
        final Card stifle = addCardToZone(STIFLE, ai, ZoneType.Hand);
        game.getAction().moveTo(ZoneType.Battlefield, addCardToZone(DREADNOUGHT, ai, ZoneType.Hand), null,
                AbilityKey.newMap());
        game.getAction().checkStateEffects(true);
        game.getStack().addAllTriggeredAbilitiesToStack();
        assertEquals("the harness: the Dreadnought's trigger", DREADNOUGHT, game.getStack().peek().getSourceCard().getName());
        return stifle;
    }

    @Test(timeOut = 120_000)
    public void theCastStaysUnderTheTwoTriesAPhaseRule() {
        final Card dreadnought = castBoard();
        expect(DREADNOUGHT);

        brain.rememberFailedCast(dreadnought);
        expect(DREADNOUGHT);

        brain.rememberFailedCast(dreadnought);
        assertNull("two failures in the phase end its tries", nextPlay());

        game.getPhaseHandler().devModeSet(PhaseType.MAIN2, ai);
        expect(DREADNOUGHT);
    }

    @Test(timeOut = 120_000)
    public void theCastStaysUnderUpstreamsFailedCastMemory() {
        castBoard();
        final SpellAbility cast = expect(DREADNOUGHT);

        brain.noteFailedCast(AiController.castKey(cast));

        assertNull("a cast that could not go on the stack is not tried again this turn", nextPlay());
    }

    @Test(timeOut = 120_000)
    public void theStifleStaysUnderTheTwoTriesAPhaseRule() {
        final Card stifle = triggerBoard();
        expect(STIFLE);

        brain.rememberFailedCast(stifle);
        expect(STIFLE);

        brain.rememberFailedCast(stifle);
        assertNull("two failures in the phase end its tries", nextPlay());

        game.getPhaseHandler().devModeSet(PhaseType.MAIN2, ai);
        expect(STIFLE);
    }

    @Test(timeOut = 120_000)
    public void theStifleStaysUnderUpstreamsFailedCastMemory() {
        triggerBoard();
        final SpellAbility cast = expect(STIFLE);

        brain.noteFailedCast(AiController.castKey(cast));

        assertNull("a cast that could not go on the stack is not tried again this turn", nextPlay());
    }
}
