package forge.ai.ability;

import forge.ai.AITest;
import forge.ai.AiController;
import forge.ai.AiPlayDecision;
import forge.ai.LobbyPlayerAi;
import forge.ai.PlayerControllerAi;
import forge.ai.SpecialCardAi;
import forge.game.Game;
import forge.game.card.Card;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.trigger.Trigger;
import forge.game.trigger.TriggerType;
import forge.game.zone.ZoneType;
import forge.util.MyRandom;
import org.testng.AssertJUnit;
import org.testng.annotations.Test;

/**
 * Wedding Ring (dead-card batch 4, row 14, part 14C). FLOOR S' defers the Ring's cast while our hand holds a
 * creature the whole pool casts now, by colour, and the Ring's four would leave unpaid (AnotherTime): the AI's
 * sort tries the 4-drop first. Only such a creature: a held non-creature card (FLOOR S on mana value alone kept
 * game 1667004's Ring dead for 25 turns on an O-Ring and situational removal), a creature the colours cannot cast,
 * a flash creature, and one the spare mana still pays never defer it. Every board here passes 14B's other floors
 * (four or more Plains, a library of 30 against 30, their 4 cards against our 1 after the cast), so S' alone
 * answers. The seat plays the Default profile.
 */
public class WeddingRingSpareManaAiTest extends AITest {

    @Test
    public void aCastableCreatureTheRingWouldStarveDefersIt() {
        // Glory Seeker {1}{W} against four Plains: the Ring leaves 0
        Player ai = setup(4, "Glory Seeker");
        AssertJUnit.assertEquals(AiPlayDecision.AnotherTime, dryRun(ai));
        AssertJUnit.assertEquals(AiPlayDecision.BadEtbEffects, canPlay(ai));
    }

    @Test
    public void aNonCreatureNeverDefersIt() {
        // Pacifism {1}{W}: castable now and starved by the Ring, but held cards are not counted (the amendment)
        AssertJUnit.assertEquals(AiPlayDecision.WillPlay, dryRun(setup(4, "Pacifism")));
        AssertJUnit.assertEquals(AiPlayDecision.WillPlay, canPlay(setup(4, "Pacifism")));
    }

    @Test
    public void aCreatureTheColoursCannotCastNeverDefersIt() {
        // Grizzly Bears {1}{G} with Plains only
        AssertJUnit.assertEquals(AiPlayDecision.WillPlay, dryRun(setup(4, "Grizzly Bears")));
    }

    @Test
    public void aCreatureOverTheWholePoolNeverDefersIt() {
        // Serra Angel {3}{W}{W}: five, more than the four Plains make
        AssertJUnit.assertEquals(AiPlayDecision.WillPlay, dryRun(setup(4, "Serra Angel")));
        AssertJUnit.assertEquals(AiPlayDecision.AnotherTime, dryRun(setup(5, "Serra Angel")));
    }

    @Test
    public void aFlashCreatureNeverDefersIt() {
        // Benalish Knight {2}{W}, flash: cast at instant speed, not displaced
        AssertJUnit.assertEquals(AiPlayDecision.WillPlay, dryRun(setup(4, "Benalish Knight")));
    }

    @Test
    public void theSpareManaDecides() {
        // Glory Seeker's two against what the Ring's four leave: 1 of five Plains defers, 2 of six does not
        AssertJUnit.assertEquals(AiPlayDecision.AnotherTime, dryRun(setup(5, "Glory Seeker")));
        AssertJUnit.assertEquals(AiPlayDecision.WillPlay, dryRun(setup(6, "Glory Seeker")));
    }

    // ai (player 1, Default profile): `lands` Plains, a library of 30, Wedding Ring and `card` in hand; the
    // opponent: a library of 30 and 4 cards in hand; our Main 2
    private Player setup(int lands, String card) {
        Game game = initAndCreateGame();
        Player ai = game.getPlayers().get(1);
        Player opp = game.getPlayers().get(0);
        ((LobbyPlayerAi) ai.getLobbyPlayer()).setAiProfile("Default");
        for (int i = 0; i < 30; i++) {
            addCardToZone("Grizzly Bears", ai, ZoneType.Library);
            addCardToZone("Grizzly Bears", opp, ZoneType.Library);
        }
        for (int i = 0; i < 4; i++) {
            addCardToZone("Hill Giant", opp, ZoneType.Hand);
        }
        for (int i = 0; i < lands; i++) {
            addCard("Plains", ai);
        }
        addCardToZone("Wedding Ring", ai, ZoneType.Hand);
        addCardToZone(card, ai, ZoneType.Hand);
        moveToMain2(game, ai);
        return ai;
    }

    private static Card ring(Player ai) {
        for (Card c : ai.getCardsIn(ZoneType.Hand)) {
            if (SpecialCardAi.WeddingRing.NAME.equals(c.getName())) {
                return c;
            }
        }
        AssertJUnit.fail("no Wedding Ring in hand");
        return null;
    }

    private static SpellAbility spell(Player ai) {
        SpellAbility sa = ring(ai).getFirstSpellAbility();
        sa.setActivatingPlayer(ai);
        return sa;
    }

    private static AiController brains(Player ai) {
        return ((PlayerControllerAi) ai.getController()).getAi();
    }

    private static AiPlayDecision canPlay(Player ai) {
        return brains(ai).canPlaySa(spell(ai));
    }

    // the trigger copy checkETBEffects judges: the cast SA set on the host, the ability copied for the caster
    private static AiPlayDecision dryRun(Player ai) {
        SpellAbility cast = spell(ai);
        Card host = cast.getHostCard();
        SpellAbility before = host.getCastSA();
        host.setCastSA(cast);
        try {
            SpellAbility etb = null;
            for (Trigger t : host.getTriggers()) {
                if (t.getMode() == TriggerType.ChangesZone) {
                    etb = t.ensureAbility().copy(ai);
                    etb.setTrigger(t);
                }
            }
            AssertJUnit.assertNotNull(etb);
            long draws = MyRandom.callCount();
            AiPlayDecision d = SpecialCardAi.WeddingRing.consider(ai, etb, false).decision();
            AssertJUnit.assertEquals("the dry run drew", draws, MyRandom.callCount());
            return d;
        } finally {
            host.setCastSA(before);
        }
    }
}
