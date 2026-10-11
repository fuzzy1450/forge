package forge.ai.controller;

import forge.ai.ComputerUtilMana;
import forge.ai.simulation.GameSimulator;
import forge.ai.simulation.Plan;
import forge.ai.simulation.SimulationTest;
import forge.ai.simulation.SpellAbilityPicker;
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

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;


public class AutoPaymentTest extends SimulationTest {

    @Test
    public void dontPayWithAshnodsAltar() {
        Game game = initAndCreateGame();
        Player p = game.getPlayers().get(1);

        String llanowar = "Llanowar Elves";

        Card elf = addCard(llanowar,  p);
        elf.setSickness(false);
        Card altar = addCard("Ashnod's Altar", p);
        Card treasure = addToken("c_a_treasure_sac", p);

        // Two choices tap elf and sac treasure
        // OR Sac elf to Altar

        String stone = "Mind Stone";
        Card mindstone = addCardToZone(stone, p, ZoneType.Hand);

        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, p);
        game.getAction().checkStateEffects(true);

        GameSimulator sim = createSimulator(p);
        int score = sim.simulateSpellAbility(mindstone.getFirstSpellAbility()).value;

        AssertJUnit.assertTrue(score > 0);
        Game simGame = sim.getSimulatedGameState();

        Card mindstoneBF = findCardWithName(simGame, stone);
        AssertJUnit.assertNotNull(mindstoneBF);

        Card elfCopy = findCardWithName(simGame, llanowar);
        AssertJUnit.assertNotNull(elfCopy);
    }

    @Test
    public void payWithTreasuresOverPhyrexianAltar() {
        Game game = initAndCreateGame();
        Player p = game.getPlayers().get(1);

        String squire = "Squire";

        List<Card> squires = addCards(squire, 6,  p);
        Card altar = addCard("Phyrexian Altar", p);
        List<Card> treasures = addTokens("c_a_treasure_sac", 6, p);

        String shivan = "Shivan Dragon";
        Card dragon = addCardToZone(shivan, p, ZoneType.Hand);

        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, p);
        game.getAction().checkStateEffects(true);

        GameSimulator sim = createSimulator(p);
        int score = sim.simulateSpellAbility(dragon.getFirstSpellAbility()).value;

        AssertJUnit.assertTrue(score > 0);
        Game simGame = sim.getSimulatedGameState();

        Card dragonBF = findCardWithName(simGame, shivan);
        AssertJUnit.assertNotNull(dragonBF);
        AssertJUnit.assertEquals(dragonBF.getZone().getZoneType(), ZoneType.Battlefield);

        Card squireCopy = findCardWithName(simGame, squire);
        AssertJUnit.assertNotNull(squireCopy);

        Card treasureCopy = findCardWithName(simGame, "Treasure Token");
        AssertJUnit.assertNull(treasureCopy);
    }

    @Test
    public void payWithCreaturesOverSacrificeLands() {
        // Do not sacrifice debris. It can be tapped for Blue or Plains tapped for white. Tap elf instead.
        Game game = initAndCreateGame();
        Player p = game.getPlayers().get(1);

        Card elf = addCard("Llanowar Elves",  p);
        addCard("Seafloor Debris", p);
        addCard("Plains", p);
        addCard("Fervor", p);

        String griz = "Grizzly Bears";
        Card bears = addCardToZone(griz, p, ZoneType.Hand);

        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, p);
        game.getAction().checkStateEffects(true);

        GameSimulator sim = createSimulator(p);
        int score = sim.simulateSpellAbility(bears.getFirstSpellAbility()).value;

        AssertJUnit.assertTrue(score > 0);
        Game simGame = sim.getSimulatedGameState();

        // Grizzly cast, Seafloor not sacrificed, Elf tapped
        Card grizBF = findCardWithName(simGame, griz);
        AssertJUnit.assertNotNull(grizBF);
        AssertJUnit.assertEquals(ZoneType.Battlefield, grizBF.getZone().getZoneType());

        Card debrisCopy = findCardWithName(simGame, "Seafloor Debris");
        AssertJUnit.assertNotNull(debrisCopy);

        Card elfCopy = findCardWithName(simGame, "Llanowar Elves");
        AssertJUnit.assertNotNull(elfCopy);
        AssertJUnit.assertTrue(elfCopy.isTapped());
    }

    @Test
    public void testKeepColorsOpen() {
        Game game = initAndCreateGame();
        Player p = game.getPlayers().get(1);

        addCards("Forest", 2, p);
        addCards("Swamp", 2, p);
        addCardToZone("Bear Cub", p, ZoneType.Hand);
        addCardToZone("Bear Cub", p, ZoneType.Hand);

        game.getPhaseHandler().devModeSet(PhaseType.MAIN2, p);
        game.getAction().checkStateEffects(true);

        SpellAbilityPicker picker = new SpellAbilityPicker(p);
        SpellAbility sa = picker.chooseSpellAbilityToPlay(null);
        AssertJUnit.assertTrue(sa.getHostCard().isCreature());

        // AI able to cast both creatures
        Plan plan = picker.getPlan();
        AssertJUnit.assertEquals(2, plan.getDecisions().size());
    }

    @Test
    public void useRequiredMultiManaSourceFirst() {
        Game game = initAndCreateGame();
        Player p = game.getPlayers().get(1);

        Card ring = addCard("Sol Ring", p);
        addCard("Chromatic Orrery", p);
        Card golem = addCardToZone("Steel Golem", p, ZoneType.Hand);

        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, p);
        game.getAction().checkStateEffects(true);

        SpellAbility sa = golem.getFirstSpellAbility();
        sa.setActivatingPlayer(p);
        AssertJUnit.assertTrue(ComputerUtilMana.payManaCost(sa.getPayCosts(), p, sa, false));

        // the Orrery is needed either way and covers the cost alone, so the Ring stays untapped
        AssertJUnit.assertTrue(ring.isUntapped());
    }

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
