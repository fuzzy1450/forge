package forge.ai.ability;

import forge.ai.AITest;
import forge.ai.AiAbilityDecision;
import forge.ai.SpellApiToAi;
import forge.game.Game;
import forge.game.card.Card;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import org.testng.AssertJUnit;
import org.testng.annotations.Test;

/**
 * Reanimate costs its target's mana value in life, which the AI could not see when it judged the
 * cast (the loss reads the card the resolution remembers): it reanimated the most expensive creature
 * card at any life. It now keeps only the targets that leave it at the stock floor and above the
 * profile's danger threshold (SpecialCardAi.Reanimate, SpecialCardAi.LifeFloor).
 */
public class ReanimateAiTest extends AITest {

    @Test
    public void takesTheMostExpensiveTargetItCanAfford() {
        // 20 - 6 = 14: both are affordable, so the stock pick (the most expensive) stands
        AssertJUnit.assertEquals("Shivan Dragon", target(setup(20, false)));
        // 10 - 6 = 4 is not above the danger threshold of 4; 10 - 5 = 5 is
        AssertJUnit.assertEquals("Serra Angel", target(setup(10, false)));
    }

    @Test
    public void castsNothingItCannotAfford() {
        // 9 - 5 = 4: nothing is affordable (the stock pick took Shivan Dragon, 9 -> 3)
        AssertJUnit.assertNull(target(setup(9, false)));
        // the stock pick took Shivan Dragon, 5 -> -1
        AssertJUnit.assertNull(target(setup(5, false)));
    }

    @Test
    public void anOpposingBoardDoesNotMoveTheFloor() {
        // the floor reads the AI's own life, not the opponents' next attack: the creature can block it
        AssertJUnit.assertEquals("Serra Angel", target(setup(10, true)));
    }

    private Player setup(int life, boolean opposingAttacker) {
        Game game = initAndCreateGame();
        Player ai = game.getPlayers().get(1);
        Player opponent = game.getPlayers().get(0);
        addCard("Swamp", ai);
        addCardToZone("Shivan Dragon", ai, ZoneType.Graveyard);
        addCardToZone("Serra Angel", ai, ZoneType.Graveyard);
        if (opposingAttacker) {
            Card wurm = addCard("Craw Wurm", opponent);
            wurm.setSickness(false);
        }
        addCardToZone("Reanimate", ai, ZoneType.Hand);
        ai.setLife(life, null);
        game.getAction().checkStateEffects(true);
        return ai;
    }

    // the creature card Reanimate would target, or null when the AI declines to cast it
    private String target(Player ai) {
        Card reanimate = ai.getCardsIn(ZoneType.Hand).getFirst();
        SpellAbility sa = reanimate.getFirstSpellAbility();
        sa.setActivatingPlayer(ai);
        AiAbilityDecision decision = SpellApiToAi.Converter.get(sa).canPlayWithSubs(ai, sa);
        if (!decision.willingToPlay()) {
            return null;
        }
        Card t = sa.getTargets().getFirstTargetedCard();
        AssertJUnit.assertNotNull("an approved cast has a target", t);
        return t.getName();
    }
}
