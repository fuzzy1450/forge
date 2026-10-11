package forge.ai;

import java.util.ArrayList;
import java.util.List;

import forge.game.Game;
import forge.game.ability.ApiType;
import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import org.testng.AssertJUnit;
import org.testng.annotations.Test;

/**
 * AiController.getCardsToDiscard's worst-card fallback throws away the cheapest artifact first
 * (ComputerUtilCard.getWorstAI), a connive the cheapest nonland card (ConniveAi.chooseDiscard): a Sol
 * Ring before a spell either way. While the AI is short of mana it now keeps its mana sources
 * (AiController.keepManaSourcesWhileShort). And a card is uncastable only beyond the near term (every
 * permanent that makes mana, a land in hand, one more mana), so 5 Swamps keep Liliana, Dreadhorde
 * General: a cheap spell pays for it, not the mana rock that brings her within reach.
 */
public class DiscardAiTest extends AITest {

    @Test
    public void keepsSolRingWhileShortOfMana() {
        // 4 Swamps, under the profile's 7 mana-producing lands: Sol Ring stays, a cleanup or a connive
        List<String> cleanup = discards(setup(4, false, "Sol Ring", "Hill Giant", "Divination"), false);
        AssertJUnit.assertEquals(1, cleanup.size());
        AssertJUnit.assertFalse(cleanup.contains("Sol Ring"));
        List<String> connive = discards(setup(4, false, "Sol Ring", "Hill Giant", "Divination"), true);
        AssertJUnit.assertEquals(1, connive.size());
        AssertJUnit.assertFalse(connive.contains("Sol Ring"));
    }

    @Test
    public void discardsSolRingWithManaEnough() {
        // 8 Swamps, past the profile's 7 and the hand's 4: Sol Ring is the cheapest artifact, as before
        AssertJUnit.assertEquals(List.of("Sol Ring"),
                discards(setup(8, false, "Sol Ring", "Hill Giant", "Divination"), false));
    }

    @Test
    public void keepsLilianaAtFiveLands() {
        // 5 Swamps + 1 = 6: Liliana is one land drop away, so M.O.D.O.K.'s connive discards a cheap spell
        List<String> out = discards(setup(5, false, "Liliana, Dreadhorde General", "Stitch Together", "Drain Life"), true);
        AssertJUnit.assertEquals(1, out.size());
        AssertJUnit.assertFalse(out.contains("Liliana, Dreadhorde General"));
    }

    @Test
    public void keepsLilianaAndTheManaRockThatReachesHer() {
        // 4 Swamps + Arcane Signet + 1 = 6 keeps Liliana; short of mana, the rock stays too: a cleanup would
        // take Mind Stone as the cheapest artifact, a connive Sol Ring as the cheapest nonland card
        AssertJUnit.assertEquals(List.of("Drain Life"),
                discards(setup(4, true, "Liliana, Dreadhorde General", "Mind Stone", "Drain Life"), false));
        AssertJUnit.assertEquals(List.of("Drain Life"),
                discards(setup(4, true, "Liliana, Dreadhorde General", "Sol Ring", "Drain Life"), true));
    }

    @Test
    public void stillDiscardsACardFarOutOfReach() {
        // 4 Swamps + 1 = 5: Liliana (6) is out of reach, and goes first as before
        AssertJUnit.assertEquals(List.of("Liliana, Dreadhorde General"),
                discards(setup(4, false, "Liliana, Dreadhorde General", "Stitch Together", "Drain Life"), false));
    }

    protected Player setup(int swamps, boolean signet, String... hand) {
        Game game = initAndCreateGame();
        Player ai = game.getPlayers().get(1);
        addCards("Swamp", swamps, ai);
        if (signet) {
            addCard("Arcane Signet", ai);
        }
        for (String name : hand) {
            addCardToZone(name, ai, ZoneType.Hand);
        }
        return ai;
    }

    // one discard from the whole hand: a cleanup's (no spell ability, as
    // chooseCardsToDiscardToMaximumHandSize asks) or M.O.D.O.K.'s connive
    protected List<String> discards(Player ai, boolean connive) {
        SpellAbility sa = null;
        if (connive) {
            Card modok = addCard("M.O.D.O.K.", ai);
            for (SpellAbility s : modok.getSpellAbilities()) {
                if (s.getApi() == ApiType.Connive) {
                    sa = s;
                    break;
                }
            }
            AssertJUnit.assertNotNull("no connive on M.O.D.O.K.", sa);
            sa.setActivatingPlayer(ai);
        }
        CardCollection hand = new CardCollection(ai.getCardsIn(ZoneType.Hand));
        CardCollection out = ((PlayerControllerAi) ai.getController()).getAi().getCardsToDiscard(1, 1, hand, sa);
        List<String> names = new ArrayList<>();
        for (Card c : out) {
            names.add(c.getName());
        }
        return names;
    }
}
