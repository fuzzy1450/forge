package forge.ai.ability;

import java.lang.reflect.Method;

import forge.ai.AITest;
import forge.ai.AiController;
import forge.ai.AiPlayDecision;
import forge.ai.LobbyPlayerAi;
import forge.ai.PlayerControllerAi;
import forge.ai.SpecialCardAi;
import forge.ai.SpellApiToAi;
import forge.game.Game;
import forge.game.ability.ApiType;
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
 * Cyberdrive Awakener (dead card of 2026-11-22). AiController.checkETBEffectsPreparedCard dry-runs its enters
 * trigger, a root DB$ AnimateAll that AnimateAllAi.canPlay had no branch for (CantPlayAi), so canPlaySa vetoed every
 * cast (BadEtbEffects at saSideEffects). The script's AILogic$ Always lets the dry run through; the two floors of
 * SpecialCardAi.CyberdriveAwakener then answer in AiController.canPlayAndPayForFace after canPlaySa and before
 * canPayCost: no cast while we control an Equipment attached to a creature (CantPlayAi), none in a window real mana
 * cannot pay (G1, CantAfford). A decline must add no MyRandom draw to canPlaySa's own, as A's veto drew nothing after
 * canPlaySa. The seat plays the Default profile, as the stored games do: its RESERVE_MANA_FOR_MAIN2_CHANCE of 100 is
 * what makes canPayCost's test payment draw (ComputerUtilMana.isManaSourceReserved). Furystoke Giant, another
 * creature whose enters trigger is a root AnimateAll with no AILogic, keeps the veto: the fix is the one script and a
 * name compare.
 */
public class CyberdriveAwakenerCastAiTest extends AITest {

    private static final String CARD = "Cyberdrive Awakener";

    @Test
    public void castsInOurMainTwoWithSixIslandsAndASolRing() throws ReflectiveOperationException {
        // red before the fix: canPlaySa answered BadEtbEffects here
        Player ai = setup(CARD, "Island", 6, true);
        AssertJUnit.assertEquals(AiPlayDecision.WillPlay, brains(ai).canPlaySa(spell(ai, CARD)));
        AssertJUnit.assertEquals(AiPlayDecision.WillPlay, canPlayAndPayFor(ai, CARD));
    }

    @Test
    public void theEtbDryRunApprovesAndDrawsNothing() {
        Player ai = setup(CARD, "Island", 6, true);
        SpellAbility cast = spell(ai, CARD);
        long before = MyRandom.callCount();
        AssertJUnit.assertTrue("the dry run refused the animation",
                brains(ai).checkETBEffects(cast.getHostCard(), cast, null));
        AssertJUnit.assertEquals("the dry run drew", before, MyRandom.callCount());
    }

    @Test
    public void refusesAnUnpayableWindowWithCanPlaySasDrawsOnly() throws ReflectiveOperationException {
        // three Islands and the Sol Ring make five: G1 refuses {5}{U}, and the refusal adds nothing to canPlaySa's
        // draws
        Player ai = setup(CARD, "Island", 3, true);
        AssertJUnit.assertFalse(SpecialCardAi.CyberdriveAwakener.castAffordable(ai, spell(ai, CARD)));
        long e0 = MyRandom.callCount();
        AssertJUnit.assertEquals(AiPlayDecision.WillPlay, brains(ai).canPlaySa(spell(ai, CARD)));
        long evaluation = MyRandom.callCount() - e0;
        long before = MyRandom.callCount();
        AssertJUnit.assertEquals(AiPlayDecision.CantAfford, canPlayAndPayFor(ai, CARD));
        AssertJUnit.assertEquals("the G1 refusal drew beyond canPlaySa", evaluation, MyRandom.callCount() - before);
    }

    @Test
    public void refusesWhileOurEquipmentIsAttachedDrawingNothingBeyondCanPlaySa() throws ReflectiveOperationException {
        // the animation would make the Bonesplitter a creature and unattach it for good (rule 704.5q)
        Player ai = setup(CARD, "Island", 6, true);
        Card bears = addCard("Grizzly Bears", ai);
        addCard("Bonesplitter", ai).attachToEntity(bears, null);
        AssertJUnit.assertTrue(SpecialCardAi.CyberdriveAwakener.stripsEquipment(ai));
        long e0 = MyRandom.callCount();
        AssertJUnit.assertEquals(AiPlayDecision.WillPlay, brains(ai).canPlaySa(spell(ai, CARD)));
        long evaluation = MyRandom.callCount() - e0;
        long before = MyRandom.callCount();
        AssertJUnit.assertEquals(AiPlayDecision.CantPlayAi, canPlayAndPayFor(ai, CARD));
        AssertJUnit.assertEquals("the Equipment floor drew beyond canPlaySa", evaluation,
                MyRandom.callCount() - before);
    }

    @Test
    public void castsWithTheSameEquipmentUnattached() throws ReflectiveOperationException {
        Player ai = setup(CARD, "Island", 6, true);
        addCard("Grizzly Bears", ai);
        addCard("Bonesplitter", ai);
        AssertJUnit.assertFalse(SpecialCardAi.CyberdriveAwakener.stripsEquipment(ai));
        AssertJUnit.assertEquals(AiPlayDecision.WillPlay, canPlayAndPayFor(ai, CARD));
    }

    @Test
    public void mainOneKeepsTheStockWaitAsForAnOrdinaryCreature() throws ReflectiveOperationException {
        // PermanentAi's Main-2 hold answers inside canPlaySa (castPermanentInMain1 finds no reason on this board), and
        // an ordinary 4/4 flyer with no enters trigger gets the same answer on the same board
        Player ai = setup(CARD, "Island", 6, true);
        ai.getGame().getPhaseHandler().devModeSet(PhaseType.MAIN1, ai);
        AssertJUnit.assertEquals(AiPlayDecision.CantPlayAi, brains(ai).canPlaySa(spell(ai, CARD)));
        AssertJUnit.assertEquals(AiPlayDecision.CantPlayAi, canPlayAndPayFor(ai, CARD));

        Player control = setup("Air Elemental", "Island", 6, true);
        control.getGame().getPhaseHandler().devModeSet(PhaseType.MAIN1, control);
        AssertJUnit.assertEquals(AiPlayDecision.CantPlayAi, brains(control).canPlaySa(spell(control, "Air Elemental")));
    }

    @Test
    public void furystokeGiantKeepsTheEtbVeto() {
        // the same class of card (a root AnimateAll enters trigger, no AILogic, no OptionalDecider): unchanged
        Player ai = setup("Furystoke Giant", "Mountain", 5, false);
        SpellAbility etb = etb(createCard("Furystoke Giant", ai));
        AssertJUnit.assertEquals(ApiType.AnimateAll, etb.getApi());
        AssertJUnit.assertNull(etb.getParam("AILogic"));
        AssertJUnit.assertEquals(AiPlayDecision.BadEtbEffects, brains(ai).canPlaySa(spell(ai, "Furystoke Giant")));
    }

    @Test
    public void theScriptsTriggerLineCarriesAiLogicAlways() {
        // the line SpellAbilityAi.doTriggerNoCostWithSubs and AnimateAllAi.canPlay read (AILogic Always): an upstream
        // rewrite of the script that drops the hint fails here, by name, instead of turning the fix off without a word
        Game game = initAndCreateGame();
        Player p = game.getPlayers().get(1);
        SpellAbility etb = etb(createCard(CARD, p));
        AssertJUnit.assertEquals(ApiType.AnimateAll, etb.getApi());
        AssertJUnit.assertEquals("Always", etb.getParam("AILogic"));
        AssertJUnit.assertTrue(SpellApiToAi.Converter.get(etb) instanceof AnimateAllAi);
    }

    // ai: player 1 on the Default profile in its own Main 2, `lands` untapped basics of `land`, a Sol Ring when
    // `solRing`, and `card` in hand
    private Player setup(String card, String land, int lands, boolean solRing) {
        Game game = initAndCreateGame();
        Player ai = game.getPlayers().get(1);
        ((LobbyPlayerAi) ai.getLobbyPlayer()).setAiProfile("Default");
        for (int i = 0; i < lands; i++) {
            addCard(land, ai);
        }
        if (solRing) {
            addCard("Sol Ring", ai);
        }
        addCardToZone(card, ai, ZoneType.Hand);
        moveToMain2(game, ai);
        return ai;
    }

    private static SpellAbility spell(Player ai, String name) {
        for (Card c : ai.getCardsIn(ZoneType.Hand)) {
            if (name.equals(c.getName())) {
                SpellAbility sa = c.getFirstSpellAbility();
                sa.setActivatingPlayer(ai);
                return sa;
            }
        }
        AssertJUnit.fail("no " + name + " in hand");
        return null;
    }

    // the ability of the card's one enters trigger
    private static SpellAbility etb(Card card) {
        SpellAbility found = null;
        for (Trigger t : card.getTriggers()) {
            if (t.getMode() == TriggerType.ChangesZone && "Battlefield".equals(t.getParam("Destination"))) {
                AssertJUnit.assertNull("more than one enters trigger on " + card, found);
                found = t.ensureAbility();
            }
        }
        AssertJUnit.assertNotNull("no enters trigger on " + card, found);
        return found;
    }

    private static AiController brains(Player ai) {
        return ((PlayerControllerAi) ai.getController()).getAi();
    }

    private static AiPlayDecision canPlayAndPayFor(Player ai, String name) throws ReflectiveOperationException {
        SpellAbility sa = spell(ai, name);
        // the regular-cast entry the playable list uses (private): canPlayFromHost, then canPlayAndPayForFace
        Method m = AiController.class.getDeclaredMethod("canPlayAndPayFor", SpellAbility.class);
        m.setAccessible(true);
        return (AiPlayDecision) m.invoke(brains(ai), sa);
    }
}
