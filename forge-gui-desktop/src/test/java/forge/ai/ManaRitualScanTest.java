package forge.ai;

import static org.testng.AssertJUnit.assertEquals;
import static org.testng.AssertJUnit.assertNotNull;
import static org.testng.AssertJUnit.assertTrue;

import java.util.List;

import org.testng.annotations.Test;

import forge.game.Game;
import forge.game.ability.ApiType;
import forge.game.ability.effects.CharmEffect;
import forge.game.card.Card;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;

/**
 * ManaAi.doManaRitualLogic's hand scan asks canPlaySa about every other spell in hand and skips only its own card. Some
 * of the spells it asks about ask about the ritual in turn, and such a pair asked each other until the AI's thread
 * overflowed its stack, ending the game in an Error. Each test here holds one such pair, and passes when the AI comes
 * back with any answer.
 */
public class ManaRitualScanTest extends AITest {

    private static AiController aiOf(Player p) {
        return ((PlayerControllerAi) p.getController()).getAi();
    }

    @Test(timeOut = 120_000)
    public void flareOfDuplicationAndAJeskasWillOnTheStackDoNotAskEachOtherForever() {
        Game game = initAndCreateGame();
        Player ai = game.getPlayers().get(1);
        Player opp = game.getPlayers().get(0);
        addCards("Mountain", 3, ai);
        addCardToZone("Flare of Duplication", ai, ZoneType.Hand);
        for (int i = 0; i < 4; i++) {
            addCardToZone("Runeclaw Bear", opp, ZoneType.Hand);
        }
        // the opponent's Jeska's Will on the stack, its mode chosen as a cast chooses it; its mana mode, if chosen,
        // targets the AI. Flare copies an opponent's spell whenever it can (CopySpellAbilityAi), so the AI judges a
        // copy of this one, whose ritual mode scans the AI's hand and finds Flare again.
        Card will = createCard("Jeska's Will", opp);
        will.setGameTimestamp(game.getNextTimestamp());
        game.getStackZone().add(will);
        SpellAbility cast = will.getFirstSpellAbility();
        cast.setActivatingPlayer(opp);
        assertTrue(CharmEffect.makeChoices(cast));
        for (SpellAbility sub = cast.getSubAbility(); sub != null; sub = sub.getSubAbility()) {
            if (sub.getApi() == ApiType.Mana) {
                sub.getTargets().add(ai);
            }
        }
        game.getStack().add(cast);

        aiOf(ai).chooseSpellAbilityToPlay();
    }

    @Test(timeOut = 120_000)
    public void rousingRefrainAndGalvanicIterationInHandDoNotAskEachOtherForever() {
        Game game = initAndCreateGame();
        Player ai = game.getPlayers().get(1);
        Player opp = game.getPlayers().get(0);
        addCards("Mountain", 4, ai);
        addCards("Island", 2, ai);
        addCardToZone("Rousing Refrain", ai, ZoneType.Hand);
        addCardToZone("Galvanic Iteration", ai, ZoneType.Hand);
        for (int i = 0; i < 4; i++) {
            addCardToZone("Runeclaw Bear", opp, ZoneType.Hand);
        }
        // the ritual's scan asks about Galvanic Iteration, whose SpellCopy logic (DelayedTriggerAi) looks for the
        // next instant or sorcery it would copy and asks about Rousing Refrain
        game.getPhaseHandler().devModeSet(PhaseType.MAIN2, ai);
        game.getAction().checkStateEffects(true);

        aiOf(ai).chooseSpellAbilityToPlay();
    }

    @Test(timeOut = 120_000)
    public void aRitualAskedAboutInsideAnotherRitualsScanStillAnswers() {
        Game game = initAndCreateGame();
        Player ai = game.getPlayers().get(1);
        Player opp = game.getPlayers().get(0);
        addCards("Mountain", 5, ai);
        addCardToZone("Jeska's Will", ai, ZoneType.Hand);
        addCardToZone("Rite of Flame", ai, ZoneType.Hand);
        addCardToZone("Shivan Dragon", ai, ZoneType.Hand);
        for (int i = 0; i < 4; i++) {
            addCardToZone("Runeclaw Bear", opp, ZoneType.Hand);
        }
        game.getPhaseHandler().devModeSet(PhaseType.MAIN2, ai);
        game.getAction().checkStateEffects(true);

        // Jeska's Will's scan asks about Rite of Flame, whose own scan runs inside it and ends: a different ritual,
        // which the guard leaves alone. The AI plays Rite of Flame first, as it did before the guard.
        List<SpellAbility> choice = aiOf(ai).chooseSpellAbilityToPlay();
        assertNotNull(choice);
        assertEquals("Rite of Flame", choice.get(0).getHostCard().getName());
    }
}
