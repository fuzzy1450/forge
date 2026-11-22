package forge.ai.controller;

import forge.ai.ComputerUtilMana;
import forge.ai.simulation.SimulationTest;
import forge.card.CardRarity;
import forge.card.CardRules;
import forge.game.Game;
import forge.game.card.Card;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import forge.item.PaperCard;
import org.testng.AssertJUnit;
import org.testng.annotations.Test;

import java.util.concurrent.atomic.AtomicReference;


/**
 * The fork's mana payment test that upstream's ComputerUtilManaOrderTest (#12947, which moved every other test of this
 * class there and deleted it) does not carry: it pins the fork's fix in ComputerUtilMana.payManaCost (7a241d90134,
 * the reordered sources are for the choice only).
 */
public class AutoPaymentTest extends SimulationTest {

    // A two-mana source whose own cost the AI declines to pay must not be chosen again once its payment has failed:
    // AiCostDecision gives no payment for discarding cards with the same name, a cost
    // CostPayment.canPayAdditionalCosts accepts. requiredSourcesFirst puts the source first in a reordered copy of the
    // shard's sources, and the removal after the failed payment has to reach the sources themselves, not that copy;
    // otherwise every pass of payManaCost chooses the same source again and the payment never ends.
    @Test
    public void refusedRequiredSourceIsNotChosenForever() throws InterruptedException {
        Game game = initAndCreateGame();
        Player p = game.getPlayers().get(1);

        addCard("Plains", p);
        CardRules.Reader reader = new CardRules.Reader();
        for (String line : new String[] {
                "Name:Twin Discard Engine",
                "ManaCost:0",
                "Types:Artifact",
                "A:AB$ Mana | Cost$ Discard<2/Card+WithSameName/two cards with the same name> | Produced$ C | Amount$ 2 | SpellDescription$ Add {C}{C}.",
                "Oracle:Discard two cards with the same name: Add {C}{C}."}) {
            reader.parseLine(line);
        }
        Card engine = Card.fromPaperCard(new PaperCard(reader.getCard(), "M11", CardRarity.Common), p);
        engine.setGameTimestamp(game.getNextTimestamp());
        p.getZone(ZoneType.Battlefield).add(engine);
        addCardToZone("Grizzly Bears", p, ZoneType.Hand);
        addCardToZone("Grizzly Bears", p, ZoneType.Hand);
        Card signet = addCardToZone("Arcane Signet", p, ZoneType.Hand);

        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, p);
        game.getAction().checkStateEffects(true);

        SpellAbility sa = signet.getFirstSpellAbility();
        sa.setActivatingPlayer(p);
        AtomicReference<Boolean> paid = new AtomicReference<>();
        Thread payment = new Thread(() -> paid.set(ComputerUtilMana.payManaCost(sa.getPayCosts(), p, sa, false)),
                "refused-source-payment");
        payment.setDaemon(true);   // a payment that never ends must not keep the test JVM alive
        payment.start();
        payment.join(20000);

        AssertJUnit.assertFalse("payManaCost still running after 20 s: the refused source is chosen again and again",
                payment.isAlive());
        // the Plains alone cannot pay {2}, and the two Bears are still in hand
        AssertJUnit.assertEquals(Boolean.FALSE, paid.get());
        AssertJUnit.assertEquals(2, countCardsWithName(game, "Grizzly Bears", ZoneType.Hand));
    }
}
