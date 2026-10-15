package forge.game.card;

import org.testng.AssertJUnit;
import org.testng.annotations.Test;

import forge.ai.AITest;
import forge.card.CardStateName;
import forge.card.CardType;
import forge.game.Game;
import forge.game.player.Player;
import forge.game.replacement.ReplacementEffect;
import forge.game.spellability.SpellAbility;

/**
 * A state whose type has the Vengeance subtype gets the Vengeance replacement from every replacement effect rebuild
 * (the spell is exiled as it leaves the stack), and its spell, on the Secondary state, is a Vengeance spell, which only
 * the graveyard may cast. Both answers come from the type's kept Vengeance lookup: these pin them on a real Vengeance
 * card, Hela, Mistress of Darkness // Dark Tutor, and that both follow the subtype when the state's type loses it, and
 * gains it again, after they were asked.
 */
public class CardStateVengeanceTest extends AITest {

    private static boolean hasVengeanceReplacement(final CardState state) {
        for (final ReplacementEffect re : state.getReplacementEffects()) {
            if ("Vengeance".equals(re.getParam("Description"))) {
                return true;
            }
        }
        return false;
    }

    private static void assertVengeance(final boolean expected, final CardState state, final SpellAbility spell) {
        AssertJUnit.assertEquals(expected, state.getType().isVengeance());
        AssertJUnit.assertEquals(expected, state.getType().hasSubtype("Vengeance"));
        AssertJUnit.assertEquals(expected, hasVengeanceReplacement(state));
        AssertJUnit.assertEquals(expected, spell.isVengeance());
    }

    @Test
    public void theVengeanceFaceFollowsItsSubtype() {
        Game game = initAndCreateGame();
        Player p = game.getPlayers().get(0);

        Card hela = createCard("Hela, Mistress of Darkness", p);
        CardState creature = hela.getState(CardStateName.Original);
        CardState tutor = hela.getState(CardStateName.Secondary);
        SpellAbility tutorSpell = tutor.getFirstAbility();
        AssertJUnit.assertNotNull(tutorSpell);
        AssertJUnit.assertSame(tutor, tutorSpell.getCardState());

        assertVengeance(true, tutor, tutorSpell);
        // the creature face is not a Vengeance face
        assertVengeance(false, creature, creature.getFirstAbility());

        tutor.setType(CardType.parse("Sorcery", false));
        assertVengeance(false, tutor, tutorSpell);

        tutor.addType("Vengeance");
        assertVengeance(true, tutor, tutorSpell);
    }

    @Test
    public void aSecondaryStateOnlyIsAVengeanceSpell() {
        Game game = initAndCreateGame();
        Player p = game.getPlayers().get(0);

        // the subtype on a card's only face makes the replacement, but its spell is no Vengeance spell
        Card bears = createCard("Grizzly Bears", p);
        CardState state = bears.getCurrentState();
        SpellAbility spell = state.getFirstAbility();
        AssertJUnit.assertFalse(hasVengeanceReplacement(state));
        AssertJUnit.assertFalse(spell.isVengeance());
        bears.addType("Vengeance");
        AssertJUnit.assertTrue(state.getType().isVengeance());
        AssertJUnit.assertTrue(hasVengeanceReplacement(state));
        AssertJUnit.assertFalse(spell.isVengeance());
    }
}
