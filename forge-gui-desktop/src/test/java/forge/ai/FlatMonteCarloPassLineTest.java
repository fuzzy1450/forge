package forge.ai;

import java.util.List;

import org.testng.AssertJUnit;
import org.testng.annotations.Test;

import forge.game.Game;
import forge.game.card.Card;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;

/**
 * The fair pass line through the controller (MTG_DeckMaker docs/superpowers/specs/2026-11-17-flatmc-fair-pass-line-design.md):
 * Lightning Greaves on a ready Grizzly Bears, a ready Runeclaw Bear beside it, four Forests, and Balduvian Bears in hand,
 * which the heuristics keep for main 2, in the AI's main 1. The heuristics approve nothing there, so the search compares
 * passing with the Greaves' free Equip, which discovery adds. Before the fair pass line the pass scored a turn in which
 * the AI cast nothing, and the Equip won at every priority: the Lightning Greaves loop of the engine-performance traces.
 */
public class FlatMonteCarloPassLineTest extends AITest {
    private Game game;
    private Player ai;
    private Player opp;
    private PlayerControllerAi pc;

    private void setUp(final int oppLife) {
        game = initAndCreateGame();
        game.AI_FLAT_MONTE_CARLO = true;
        game.AI_TIMEOUT = 10;
        ai = game.getPlayers().get(1);
        opp = game.getPlayers().get(0);
        pc = (PlayerControllerAi) ai.getController();
        fillLibrary(ai, 20);
        fillLibrary(opp, 20);
        final Card greaves = addCard("Lightning Greaves", ai);
        final Card grizzly = addCard("Grizzly Bears", ai);
        grizzly.setSickness(false);
        addCard("Runeclaw Bear", ai).setSickness(false);
        greaves.attachToEntity(grizzly, null);
        for (int i = 0; i < 4; i++) {
            addCard("Forest", ai);
        }
        addCardToZone("Balduvian Bears", ai, ZoneType.Hand);
        opp.setLife(oppLife, null);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, ai);
        game.getAction().checkStateEffects(true);
    }

    /** Moving the Greaves between two ready bears gains nothing: main 1 moves them at most once. */
    @Test(timeOut = 240_000)
    public void aFreeEquipIsNotShuffledInMainOne() {
        setUp(20);
        int equips = 0;
        for (int i = 0; i < 6; i++) {
            final List<SpellAbility> chosen = pc.chooseSpellAbilityToPlay();
            if (chosen == null || chosen.isEmpty()) {
                break;
            }
            final SpellAbility sa = chosen.get(0);
            if (sa.isEquip()) {
                equips++;
            }
            pc.playChosenSpellAbility(sa);
            playUntilStackClear(game);
        }
        AssertJUnit.assertTrue("the Greaves moved " + equips + " times in main 1", equips <= 1);
    }

    /** The two ready bears attack for 4 against 4 life: the pass line wins, so the AI passes to its attack. */
    @Test(timeOut = 240_000)
    public void aWonBoardPassesToItsAttack() {
        setUp(4);
        final List<SpellAbility> chosen = pc.chooseSpellAbilityToPlay();
        AssertJUnit.assertTrue("the AI should pass to its lethal attack, not " + chosen, chosen == null || chosen.isEmpty());
        final forge.ai.simulation.FlatMonteCarloPicker.Result last = pc.getAi().getLastFlatMonteCarloResult();
        AssertJUnit.assertNotNull("the decision was searched", last);
        AssertJUnit.assertEquals("wins", last.reason);
    }

    /** A Craw Wurm cast this turn attacks for 6 only with the Greaves' haste: that move is worth it, before and after. */
    @Test(timeOut = 240_000)
    public void aMoveThatGivesHasteStillHappens() {
        setUp(20);
        final Card wurm = addCard("Craw Wurm", ai);
        wurm.setSickness(true);
        game.getAction().checkStateEffects(true);
        final List<SpellAbility> chosen = pc.chooseSpellAbilityToPlay();
        AssertJUnit.assertTrue("an Equip first: " + chosen, chosen != null && !chosen.isEmpty() && chosen.get(0).isEquip());
        AssertJUnit.assertEquals("onto the Wurm", wurm, chosen.get(0).getTargetCard());
    }
}
