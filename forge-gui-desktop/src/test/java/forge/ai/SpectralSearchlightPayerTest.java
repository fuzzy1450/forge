package forge.ai;

import org.testng.AssertJUnit;
import org.testng.annotations.Test;

import forge.game.Game;
import forge.game.ability.ApiType;
import forge.game.card.Card;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;

/**
 * Spectral Searchlight's mana is predicted once (merge 2026-11-22, ruling RU-3). The fork's ritual tap
 * (SpecialCardAi.SpectralSearchlight, 80465751e52) credits the one mana it adds and then asks the payer whether the rest
 * of a spell can be paid; it was written while the payer never tapped the Searchlight. Since upstream's d9dc6298db5 the
 * payer takes ChoosePlayer-rooted mana abilities (getAIPlayableMana), so that question counted the Searchlight's mana a
 * second time. Upstream's payer is the one prediction kept: it pays a spell with the Searchlight's mana directly.
 */
public class SpectralSearchlightPayerTest extends AITest {

    private static SpellAbility tapOf(final Card searchlight, final Player ai) {
        for (final SpellAbility sa : searchlight.getManaAbilities()) {
            if (sa.getApi() == ApiType.ChoosePlayer) {
                sa.setActivatingPlayer(ai);
                return sa;
            }
        }
        throw new AssertionError("no ChoosePlayer mana ability on " + searchlight);
    }

    /**
     * Two Mountains and the Searchlight make three mana, a Hill Giant costs four. The ritual tap would float one mana the
     * Giant cannot use: its own credit and the payer's count of the same Searchlight made the Giant look payable.
     */
    @Test
    public void theSearchlightIsNotTappedForASpellItsManaCannotFinish() {
        final Game game = initAndCreateGame();
        final Player ai = game.getPlayers().get(1);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN2, ai);
        addCards("Mountain", 2, ai);
        final Card searchlight = addCard("Spectral Searchlight", ai);
        final SpellAbility giant = addCardToZone("Hill Giant", ai, ZoneType.Hand).getFirstSpellAbility();
        giant.setActivatingPlayer(ai);
        game.getAction().checkStateEffects(true);
        AssertJUnit.assertTrue("the payer counts the Searchlight (upstream d9dc6298db5)",
                ComputerUtilMana.getAIPlayableMana(searchlight).contains(tapOf(searchlight, ai)));
        AssertJUnit.assertFalse("three mana do not pay {3}{R}", ComputerUtilCost.canPayCost(giant, ai, false));
        AssertJUnit.assertEquals("the AI wants the Giant in main 2, so only the mana count decides",
                AiPlayDecision.WillPlay, ((PlayerControllerAi) ai.getController()).getAi().canPlaySa(giant));
        AssertJUnit.assertFalse(SpecialCardAi.SpectralSearchlight.consider(ai, tapOf(searchlight, ai)));
    }

    /** Three Mountains and the Searchlight: the payer pays the Hill Giant with the Searchlight's mana, no ritual needed. */
    @Test
    public void thePayerPaysWithTheSearchlightItself() {
        final Game game = initAndCreateGame();
        final Player ai = game.getPlayers().get(1);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN2, ai);
        addCards("Mountain", 3, ai);
        final Card searchlight = addCard("Spectral Searchlight", ai);
        final SpellAbility giant = addCardToZone("Hill Giant", ai, ZoneType.Hand).getFirstSpellAbility();
        giant.setActivatingPlayer(ai);
        game.getAction().checkStateEffects(true);
        AssertJUnit.assertTrue("the Searchlight's mana finishes the Giant", ComputerUtilCost.canPayCost(giant, ai, false));
        AssertJUnit.assertFalse(SpecialCardAi.SpectralSearchlight.consider(ai, tapOf(searchlight, ai)));
    }
}
