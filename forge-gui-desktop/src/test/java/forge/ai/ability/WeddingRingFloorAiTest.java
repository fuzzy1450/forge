package forge.ai.ability;

import forge.ai.AITest;
import forge.ai.AiController;
import forge.ai.AiPlayDecision;
import forge.ai.LobbyPlayerAi;
import forge.ai.PlayerControllerAi;
import forge.ai.SpecialCardAi;
import forge.game.Game;
import forge.game.card.Card;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.trigger.Trigger;
import forge.game.trigger.TriggerType;
import forge.game.zone.ZoneType;
import forge.util.MyRandom;
import org.testng.AssertJUnit;
import org.testng.annotations.Test;

/**
 * Wedding Ring (dead-card batch 4, row 14, part 14B). AiController.checkETBEffectsPreparedCard dry-runs the
 * enters trigger before every judged cast, and CopyPermanentAi's card-target loop answered TargetingFailed for
 * its player target, so canPlaySa vetoed every cast (BadEtbEffects). SpecialCardAi.WeddingRing's dry-run branch
 * judges the cast instead: G1, a library of twenty (FLOOR L), no draw thief or punisher (FLOOR P) and a floored
 * opponent (FLOOR H). No hand here holds a creature, so part 14C's FLOOR S' never answers. The seat plays the
 * Default profile, as the stored games do. dryRun builds the trigger copy the way checkETBEffects does (the cast
 * SA on the host, the ability copied for the caster) and checks that the floor draws nothing.
 */
public class WeddingRingFloorAiTest extends AITest {

    @Test
    public void castsInOurMainTwo() {
        Player ai = setup(4, 30, 30, 4);
        AssertJUnit.assertEquals(AiPlayDecision.WillPlay, dryRun(ai));
        AssertJUnit.assertEquals(AiPlayDecision.WillPlay, canPlay(ai));
    }

    @Test
    public void mainOneKeepsTheStockWait() {
        // PermanentAi's Main-2 hold answers before the dry run
        Player ai = setup(4, 30, 30, 4);
        ai.getGame().getPhaseHandler().devModeSet(PhaseType.MAIN1, ai);
        AssertJUnit.assertEquals(AiPlayDecision.CantPlayAi, canPlay(ai));
    }

    @Test
    public void aDeclineIsStillTheEtbVeto() {
        // three lands: G1 declines, and canPlaySa answers what A answered
        Player ai = setup(3, 30, 30, 4);
        AssertJUnit.assertEquals(AiPlayDecision.CantAfford, dryRun(ai));
        AssertJUnit.assertEquals(AiPlayDecision.BadEtbEffects, canPlay(ai));
    }

    @Test
    public void aFreeCastPricesZero() {
        // a Play-effect copy (no mana cost) passes G1 with no land at all
        Player ai = setup(0, 30, 30, 4);
        AssertJUnit.assertEquals(AiPlayDecision.CantAfford, dryRun(ai));
        AssertJUnit.assertEquals(AiPlayDecision.WillPlay, dryRun(ai, spell(ai).copyWithNoManaCost(ai)));
    }

    @Test
    public void needsTwentyCardsInOurLibrary() {
        AssertJUnit.assertEquals(AiPlayDecision.CantPlayAi, dryRun(setup(4, 19, 10, 4)));
        AssertJUnit.assertEquals(AiPlayDecision.WillPlay, dryRun(setup(4, 20, 10, 4)));
    }

    @Test
    public void notIntoADrawPunisher() {
        Player ai = setup(4, 30, 30, 4);
        addCard("Underworld Dreams", opponent(ai));
        AssertJUnit.assertEquals(AiPlayDecision.CurseEffects, dryRun(ai));
    }

    @Test
    public void ourOwnRingPassesAndTheirsVetoes() {
        // our Ring's Drawn trigger names a side (Card.OppOwn); the opponent's, a token of an earlier cast, draws
        // them a card for each of ours on our turn
        Player ai = setup(4, 30, 30, 4);
        addCard("Wedding Ring", ai);
        AssertJUnit.assertEquals(AiPlayDecision.WillPlay, dryRun(ai));
        addCard("Wedding Ring", opponent(ai));
        AssertJUnit.assertEquals(AiPlayDecision.CurseEffects, dryRun(ai));
    }

    @Test
    public void theRefillGuard() {
        // they hold max(our hand after the cast + 1, 3) cards or more
        AssertJUnit.assertEquals(AiPlayDecision.DoesntImpactGame, dryRun(setup(4, 30, 30, 2)));
        AssertJUnit.assertEquals(AiPlayDecision.WillPlay, dryRun(setup(4, 30, 30, 3)));
        Player ai = setup(4, 30, 30, 3);
        for (int i = 0; i < 3; i++) {
            addCardToZone("Plains", ai, ZoneType.Hand); // our hand after the cast: 3, against their 3
        }
        AssertJUnit.assertEquals(AiPlayDecision.DoesntImpactGame, dryRun(ai));
        addCardToZone("Hill Giant", opponent(ai), ZoneType.Hand);
        AssertJUnit.assertEquals(AiPlayDecision.WillPlay, dryRun(ai));
    }

    @Test
    public void theDeckingRace() {
        // our library must outlast theirs: at least theirs, or LIB_SAFE
        AssertJUnit.assertEquals(AiPlayDecision.DoesntImpactGame, dryRun(setup(4, 30, 35, 4)));
        AssertJUnit.assertEquals(AiPlayDecision.WillPlay, dryRun(setup(4, 35, 35, 4)));
        AssertJUnit.assertEquals(AiPlayDecision.WillPlay, dryRun(setup(4, 40, 60, 4)));
    }

    @Test
    public void withNoCastSaTheAnswerIsAs() {
        // a host with no cast SA (asked outside checkETBEffects): A's TargetingFailed
        Player ai = setup(4, 30, 30, 4);
        SpellAbility etb = etb(ring(ai), ai);
        AssertJUnit.assertEquals(AiPlayDecision.TargetingFailed,
                SpecialCardAi.WeddingRing.consider(ai, etb, false).decision());
    }

    // ai (player 1, Default profile): `lands` Plains, a library of ourLib, Wedding Ring in hand; the opponent: a
    // library of oppLib and oppHand cards in hand; our Main 2
    private Player setup(int lands, int ourLib, int oppLib, int oppHand) {
        Game game = initAndCreateGame();
        Player ai = game.getPlayers().get(1);
        Player opp = game.getPlayers().get(0);
        ((LobbyPlayerAi) ai.getLobbyPlayer()).setAiProfile("Default");
        for (int i = 0; i < ourLib; i++) {
            addCardToZone("Grizzly Bears", ai, ZoneType.Library);
        }
        for (int i = 0; i < oppLib; i++) {
            addCardToZone("Grizzly Bears", opp, ZoneType.Library);
        }
        for (int i = 0; i < oppHand; i++) {
            addCardToZone("Hill Giant", opp, ZoneType.Hand);
        }
        for (int i = 0; i < lands; i++) {
            addCard("Plains", ai);
        }
        addCardToZone("Wedding Ring", ai, ZoneType.Hand);
        moveToMain2(game, ai);
        return ai;
    }

    private static Player opponent(Player ai) {
        return ai.getGame().getPlayers().get(0);
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

    private static SpellAbility etb(Card ring, Player ai) {
        for (Trigger t : ring.getTriggers()) {
            if (t.getMode() == TriggerType.ChangesZone) {
                SpellAbility sa = t.ensureAbility().copy(ai);
                sa.setTrigger(t);
                return sa;
            }
        }
        AssertJUnit.fail("no enters trigger on " + ring);
        return null;
    }

    private static AiController brains(Player ai) {
        return ((PlayerControllerAi) ai.getController()).getAi();
    }

    private static AiPlayDecision canPlay(Player ai) {
        return brains(ai).canPlaySa(spell(ai));
    }

    private static AiPlayDecision dryRun(Player ai) {
        return dryRun(ai, spell(ai));
    }

    // the trigger copy checkETBEffects judges: the cast SA set on the host, the ability copied for the caster
    private static AiPlayDecision dryRun(Player ai, SpellAbility cast) {
        Card host = cast.getHostCard();
        SpellAbility before = host.getCastSA();
        host.setCastSA(cast);
        try {
            SpellAbility etb = etb(host, ai);
            long draws = MyRandom.callCount();
            AiPlayDecision d = SpecialCardAi.WeddingRing.consider(ai, etb, false).decision();
            AssertJUnit.assertEquals("the dry run drew", draws, MyRandom.callCount());
            return d;
        } finally {
            host.setCastSA(before);
        }
    }
}
