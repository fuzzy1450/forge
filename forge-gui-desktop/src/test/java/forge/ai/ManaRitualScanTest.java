package forge.ai;

import static org.testng.AssertJUnit.assertEquals;
import static org.testng.AssertJUnit.assertNotNull;
import static org.testng.AssertJUnit.assertTrue;

import java.util.List;

import org.testng.annotations.Test;

import forge.ai.ability.ManaAi;
import forge.game.Game;
import forge.game.ability.ApiType;
import forge.game.ability.effects.CharmEffect;
import forge.game.card.Card;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;

/**
 * ManaAi.doManaRitualLogic's hand scan asks canPlaySa about every other spell in hand, skipping its own card and every
 * spell that carries AINoRecursiveCheck (Rite of Flame, Dark Ritual and most rituals; not Jeska's Will or Rousing
 * Refrain). Some of the spells it asks about ask about the ritual in turn, and such a pair asked each other until the
 * AI's thread overflowed its stack, ending the game in an Error.
 *
 * The libraries here are empty, so Jeska's Will's exile mode is never willing and its charm always asks the ritual
 * mode. That is the shape of a game whose AI controls a commander, where the charm takes both modes and such a pair
 * recursed every time. Without a commander the charm takes one mode after a shuffle, and the recursion stopped by
 * chance.
 */
public class ManaRitualScanTest extends AITest {

    private static AiController aiOf(Player p) {
        return ((PlayerControllerAi) p.getController()).getAi();
    }

    private void fillHand(Player p, int cards) {
        for (int i = 0; i < cards; i++) {
            addCardToZone("Runeclaw Bear", p, ZoneType.Hand);
        }
    }

    private static void mainTwo(Game game, Player p) {
        game.getPhaseHandler().devModeSet(PhaseType.MAIN2, p);
        game.getAction().checkStateEffects(true);
    }

    @Test(timeOut = 120_000)
    public void flareOfDuplicationAndAJeskasWillOnTheStackDoNotAskEachOtherForever() {
        Game game = initAndCreateGame();
        Player ai = game.getPlayers().get(1);
        Player opp = game.getPlayers().get(0);
        addCards("Mountain", 3, ai);
        addCardToZone("Flare of Duplication", ai, ZoneType.Hand);
        fillHand(opp, 4);
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
        fillHand(opp, 4);
        // the ritual's scan asks about Galvanic Iteration, whose SpellCopy logic (DelayedTriggerAi) looks for the
        // next instant or sorcery it would copy and asks about Rousing Refrain
        mainTwo(game, ai);

        aiOf(ai).chooseSpellAbilityToPlay();
    }

    @Test(timeOut = 120_000)
    public void jeskasWillAndRousingRefrainInHandDoNotAskEachOtherForever() {
        Game game = initAndCreateGame();
        Player ai = game.getPlayers().get(1);
        Player opp = game.getPlayers().get(0);
        addCards("Mountain", 5, ai);
        addCardToZone("Jeska's Will", ai, ZoneType.Hand);
        addCardToZone("Rousing Refrain", ai, ZoneType.Hand);
        fillHand(opp, 4);
        // two rituals without AINoRecursiveCheck: each one's scan asks about the other, whose scan asks back
        mainTwo(game, ai);

        aiOf(ai).chooseSpellAbilityToPlay();
    }

    @Test(timeOut = 120_000)
    public void aRitualAskedAboutInsideAnotherRitualsScanStillScans() {
        Game game = initAndCreateGame();
        Player ai = game.getPlayers().get(1);
        Player opp = game.getPlayers().get(0);
        addCards("Mountain", 2, ai);
        addCardToZone("Rite of Flame", ai, ZoneType.Hand);
        addCardToZone("Jeska's Will", ai, ZoneType.Hand);
        addCardToZone("Shivan Dragon", ai, ZoneType.Hand);
        fillHand(opp, 5);
        mainTwo(game, ai);

        // Two Mountains cannot pay for Jeska's Will, but with Rite of Flame's RR they can. Rite of Flame is worth
        // casting only if its scan finds Jeska's Will worth casting, which takes Jeska's Will's own scan, run inside
        // Rite of Flame's, finding Shivan Dragon. A different ritual's scan, so it must still run: refusing every
        // nested scan would leave the AI nothing to cast.
        List<SpellAbility> choice = aiOf(ai).chooseSpellAbilityToPlay();
        assertNotNull("Rite of Flame should be cast toward Jeska's Will", choice);
        assertEquals("Rite of Flame", choice.get(0).getHostCard().getName());
    }

    @Test(timeOut = 120_000)
    public void aRitualsScanLeavesNothingBehindOnItsThread() {
        Game game = initAndCreateGame();
        Player ai = game.getPlayers().get(1);
        Player opp = game.getPlayers().get(0);
        addCards("Mountain", 4, ai);
        Card refrain = addCardToZone("Rousing Refrain", ai, ZoneType.Hand);
        addCardToZone("Shivan Dragon", ai, ZoneType.Hand);
        fillHand(opp, 7);
        mainTwo(game, ai);
        SpellAbility sa = refrain.getFirstSpellAbility();
        sa.setActivatingPlayer(ai);

        // the same question twice on one thread: a scan that kept its own name after it ended would answer no the
        // second time, as if it were still asking
        assertTrue(ManaAi.doManaRitualLogic(ai, sa, false));
        assertTrue(ManaAi.doManaRitualLogic(ai, sa, false));
    }
}
