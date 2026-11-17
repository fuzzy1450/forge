package forge.ai;

import org.testng.AssertJUnit;
import org.testng.annotations.Test;

import forge.ai.simulation.ComboGuard;
import forge.ai.simulation.ComboPlan;
import forge.game.Game;
import forge.game.card.Card;
import forge.game.card.CounterEnumType;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;

/**
 * Pacts (Pact of Negation, Summoner's Pact, Slaughter Pact, Pact of the Titan, Intervention Pact): the AI loses the game
 * when it cannot pay at its next upkeep, so Flat Monte Carlo must not cast one it cannot pay for.
 */
public class FlatMonteCarloPactGuardTest extends AITest {

    private SpellAbility pact(final Game game, final Player ai, final String name) {
        game.getPhaseHandler().devModeSet(PhaseType.MAIN2, ai);
        final SpellAbility sa = addCardToZone(name, ai, ZoneType.Hand).getFirstSpellAbility();
        sa.setActivatingPlayer(ai);
        return sa;
    }

    @Test
    public void thePactCostIsReadFromTheScript() {
        final Game game = initAndCreateGame();
        final Player ai = game.getPlayers().get(1);
        AssertJUnit.assertEquals("3 U U", ComboGuard.pactCost(pact(game, ai, "Pact of Negation")));
        AssertJUnit.assertEquals("2 B", ComboGuard.pactCost(pact(game, ai, "Slaughter Pact")));
        AssertJUnit.assertNull(ComboGuard.pactCost(pact(game, ai, "Lightning Bolt")));
    }

    @Test
    public void aPactIsRefusedWithTooFewLands() {
        final Game game = initAndCreateGame();
        final Player ai = game.getPlayers().get(1);
        for (int i = 0; i < 3; i++) {
            addCard("Island", ai);
        }
        final SpellAbility sa = pact(game, ai, "Pact of Negation");
        AssertJUnit.assertEquals("pact-unpayable", ComboGuard.problem(ai, sa, null, ComboPlan.EMPTY));
    }

    @Test
    public void aPactIsRefusedWithoutTheColor() {
        final Game game = initAndCreateGame();
        final Player ai = game.getPlayers().get(1);
        for (int i = 0; i < 6; i++) {
            addCard("Mountain", ai);
        }
        final SpellAbility sa = pact(game, ai, "Pact of Negation");
        AssertJUnit.assertEquals("pact-unpayable", ComboGuard.problem(ai, sa, null, ComboPlan.EMPTY));
    }

    @Test
    public void aPactIsAllowedWhenTheCostCanBePaid() {
        final Game game = initAndCreateGame();
        final Player ai = game.getPlayers().get(1);
        for (int i = 0; i < 3; i++) {
            addCard("Island", ai);
        }
        for (int i = 0; i < 2; i++) {
            addCard("Swamp", ai);
        }
        final SpellAbility sa = pact(game, ai, "Pact of Negation");
        AssertJUnit.assertNull(ComboGuard.problem(ai, sa, null, ComboPlan.EMPTY));
    }

    @Test
    public void oneShotManaDoesNotPayForThePact() {
        final Game game = initAndCreateGame();
        final Player ai = game.getPlayers().get(1);
        for (int i = 0; i < 3; i++) {
            addCard("Island", ai);
        }
        addCard("Lotus Petal", ai);
        addCard("Lion's Eye Diamond", ai);
        final SpellAbility sa = pact(game, ai, "Pact of Negation");
        AssertJUnit.assertEquals("pact-unpayable", ComboGuard.problem(ai, sa, null, ComboPlan.EMPTY));
    }

    @Test
    public void aBlackPactIsRefusedWithOnlyIslands() {
        final Game game = initAndCreateGame();
        final Player ai = game.getPlayers().get(1);
        for (int i = 0; i < 5; i++) {
            addCard("Island", ai);
        }
        AssertJUnit.assertEquals("pact-unpayable",
                ComboGuard.problem(ai, pact(game, ai, "Slaughter Pact"), null, ComboPlan.EMPTY));
    }

    @Test
    public void aPactIsAllowedWhenTheLandDropIsStillToCome() {
        final Game game = initAndCreateGame();
        final Player ai = game.getPlayers().get(1);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, ai);
        addCard("Swamp", ai);
        addCard("Swamp", ai);
        addCardToZone("Swamp", ai, ZoneType.Hand);
        final SpellAbility sa = addCardToZone("Slaughter Pact", ai, ZoneType.Hand).getFirstSpellAbility();
        sa.setActivatingPlayer(ai);
        AssertJUnit.assertNull(ComboGuard.problem(ai, sa, null, ComboPlan.EMPTY));
    }

    /** From a real game: Summoner's Pact with a Chrome Mox and one land in play cannot be paid (2GG) and lost the game. */
    @Test
    public void summonersPactWithAChromeMoxAndOneLandIsRefused() {
        final Game game = initAndCreateGame();
        final Player ai = game.getPlayers().get(1);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, ai);
        addCard("Chrome Mox", ai);
        addCard("Valakut Stoneforge", ai);
        for (final String storm : new String[] { "Tendrils of Agony", "Infernal Tutor", "Dark Ritual", "Cabal Ritual", "Elvish Spirit Guide" }) {
            addCardToZone(storm, ai, ZoneType.Library);
        }
        final SpellAbility sa = addCardToZone("Summoner's Pact", ai, ZoneType.Hand).getFirstSpellAbility();
        sa.setActivatingPlayer(ai);
        final ComboPlan plan = ComboPlan.scan(ai);
        AssertJUnit.assertEquals("with the plan " + plan.summary(), "pact-unpayable", ComboGuard.problem(ai, sa, null, plan));
        AssertJUnit.assertEquals("pact-unpayable", ComboGuard.problem(ai, sa, null, ComboPlan.EMPTY));
    }

    /** From a real game: two Gemstone Mines and a Vault of Whispers make three mana, not 2GG. */
    @Test
    public void summonersPactWithTwoGemstoneMinesAndABlackLandIsRefused() {
        final Game game = initAndCreateGame();
        final Player ai = game.getPlayers().get(1);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, ai);
        addCard("Gemstone Mine", ai);
        addCard("Gemstone Mine", ai);
        addCard("Vault of Whispers", ai);
        final SpellAbility sa = addCardToZone("Summoner's Pact", ai, ZoneType.Hand).getFirstSpellAbility();
        sa.setActivatingPlayer(ai);
        AssertJUnit.assertEquals("pact-unpayable", ComboGuard.problem(ai, sa, null, ComboPlan.EMPTY));
    }

    // ------------------------------------------------------------------------ the self-kill fix (DESIGN.md section 4)
    // The count of the mana the AI will certainly have at its next upkeep (forge.ai.simulation.DelayedLoss), asked
    // through ComboGuard.problem. A Pact of Negation board is the opponent's first main phase unless a test says the
    // AI's own.

    private static Player opponentOf(final Game game, final Player ai) {
        return game.getPlayers().get(0) == ai ? game.getPlayers().get(1) : game.getPlayers().get(0);
    }

    /** A card put into the AI's hand, its first spell ability with the AI as activator, the phase left as it is. */
    private SpellAbility fromHand(final Player ai, final String name) {
        final SpellAbility sa = addCardToZone(name, ai, ZoneType.Hand).getFirstSpellAbility();
        sa.setActivatingPlayer(ai);
        return sa;
    }

    /** A Pact cast in the opponent's first main phase. */
    private SpellAbility onTheirTurn(final Game game, final Player ai, final String name) {
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, opponentOf(game, ai));
        return fromHand(ai, name);
    }

    private static String verdict(final Player ai, final SpellAbility sa) {
        return ComboGuard.problem(ai, sa, null, ComboPlan.EMPTY);
    }

    /** A card cast as far as the stack by {@code p}, aimed at {@code target} when one is given. */
    private SpellAbility onStack(final Game game, final String name, final Player p, final SpellAbility target) {
        final Card c = createCard(name, p);
        c.setGameTimestamp(game.getNextTimestamp());
        game.getStackZone().add(c);
        final SpellAbility sa = c.getFirstSpellAbility();
        sa.setActivatingPlayer(p);
        if (target != null) {
            sa.getTargets().add(target);
        }
        game.getStack().add(sa);
        return sa;
    }

    /** Alchemist's Gambit's normal cast, or its cleave cast, from the AI's hand. */
    private SpellAbility gambit(final Player ai, final boolean cleave) {
        final Card c = addCardToZone("Alchemist's Gambit", ai, ZoneType.Hand);
        for (final SpellAbility sa : c.getSpellAbilities()) {
            if (sa.hasParam("ExtraTurnDelayedTriggerExecute")
                    && "TrigEffect2".equals(sa.getParam("ExtraTurnDelayedTriggerExecute")) == cleave) {
                sa.setActivatingPlayer(ai);
                return sa;
            }
        }
        throw new AssertionError("Alchemist's Gambit has no " + (cleave ? "cleave" : "normal") + " cast");
    }

    /** The play is allowed: no refusal code. The message names the code there is when it fails. */
    private static void assertAllowed(final String why, final String verdict) {
        AssertJUnit.assertNull(why + " (verdict: " + verdict + ")", verdict);
    }

    @Test
    public void aManaVaultNeverPaysThePact() {
        final Game game = initAndCreateGame();
        final Player ai = game.getPlayers().get(1);
        addCards("Island", 3, ai);
        final Card vault = addCard("Mana Vault", ai);
        final SpellAbility sa = onTheirTurn(game, ai, "Pact of Negation");
        AssertJUnit.assertEquals("an untapped Mana Vault can be tapped before the upkeep and does not untap",
                "pact-unpayable", verdict(ai, sa));
        vault.setTapped(true);
        AssertJUnit.assertEquals("a tapped one does not untap", "pact-unpayable", verdict(ai, sa));
    }

    @Test
    public void aGrimMonolithNeverPaysThePact() {
        final Game game = initAndCreateGame();
        final Player ai = game.getPlayers().get(1);
        addCards("Island", 3, ai);
        addCard("Grim Monolith", ai);
        AssertJUnit.assertEquals("pact-unpayable", verdict(ai, onTheirTurn(game, ai, "Pact of Negation")));
    }

    /** Mirrors stored game 3684639: Mox Opal with two artifacts makes nothing. */
    @Test
    public void moxOpalWithoutMetalcraftDoesNotPay() {
        final Game game = initAndCreateGame();
        final Player ai = game.getPlayers().get(1);
        addCard("Island", ai);
        addCard("Underground Sea", ai);
        addCard("Sol Ring", ai);
        addCard("Mox Opal", ai);
        AssertJUnit.assertEquals("pact-unpayable", verdict(ai, onTheirTurn(game, ai, "Pact of Negation")));
    }

    @Test
    public void moxOpalWithMetalcraftPays() {
        final Game game = initAndCreateGame();
        final Player ai = game.getPlayers().get(1);
        addCard("Island", ai);
        addCard("Underground Sea", ai);
        addCard("Sol Ring", ai);
        addCard("Mox Opal", ai);
        addCard("Ornithopter", ai);
        assertAllowed("allowed", verdict(ai, onTheirTurn(game, ai, "Pact of Negation")));
    }

    @Test
    public void moxOpalCountsOnlyArtifactsThatStay() {
        final Game game = initAndCreateGame();
        final Player ai = game.getPlayers().get(1);
        addCard("Island", ai);
        addCard("Underground Sea", ai);
        addCard("Sol Ring", ai);
        addCard("Mox Opal", ai);
        addCard("Lotus Petal", ai);
        addCard("Lion's Eye Diamond", ai);
        AssertJUnit.assertEquals("Lotus Petal and Lion's Eye Diamond sacrifice themselves: two lasting artifacts",
                "pact-unpayable", verdict(ai, onTheirTurn(game, ai, "Pact of Negation")));
        addCard("Ornithopter", ai);
        assertAllowed("a third lasting artifact", verdict(ai, fromHand(ai, "Pact of Negation")));
    }

    @Test
    public void moxAmberWithoutALegendDoesNotPay() {
        final Game game = initAndCreateGame();
        final Player ai = game.getPlayers().get(1);
        addCards("Island", 4, ai);
        addCard("Mox Amber", ai);
        AssertJUnit.assertEquals("pact-unpayable", verdict(ai, onTheirTurn(game, ai, "Pact of Negation")));
    }

    @Test
    public void chromeMoxWithoutAnImprintDoesNotPay() {
        final Game game = initAndCreateGame();
        final Player ai = game.getPlayers().get(1);
        addCards("Island", 4, ai);
        addCard("Chrome Mox", ai);
        AssertJUnit.assertEquals("pact-unpayable", verdict(ai, onTheirTurn(game, ai, "Pact of Negation")));
    }

    @Test
    public void anArcaneSignetWithNoColorsDoesNotPay() {
        final Game game = initAndCreateGame();
        final Player ai = game.getPlayers().get(1);
        addCards("Island", 4, ai);
        addCard("Arcane Signet", ai);
        AssertJUnit.assertEquals("no commander, no color: no unit", "pact-unpayable",
                verdict(ai, onTheirTurn(game, ai, "Pact of Negation")));
    }

    /** Mirrors stored game 3683528: Bloom Tender is the fifth unit, of its own green. */
    @Test
    public void bloomTenderPaysItsOwnColor() {
        final Game game = initAndCreateGame();
        final Player ai = game.getPlayers().get(1);
        addCard("Delighted Halfling", ai);
        addCard("Mana Confluence", ai);
        addCard("Savannah", ai);
        addCard("Volcanic Island", ai);
        addCard("Bloom Tender", ai);
        ai.setLife(20, null);
        assertAllowed("allowed", verdict(ai, onTheirTurn(game, ai, "Pact of Negation")));
    }

    /** Mirrors stored game 3684225: a Simian Spirit Guide on the battlefield cannot be exiled from the hand. */
    @Test
    public void aSpiritGuideOnTheBattlefieldDoesNotPay() {
        final Game game = initAndCreateGame();
        final Player ai = game.getPlayers().get(1);
        addCard("Island", ai);
        addCard("Underground Sea", ai);
        addCard("Steam Vents", ai);
        addCard("Badlands", ai);
        addCard("Simian Spirit Guide", ai);
        AssertJUnit.assertEquals("pact-unpayable", verdict(ai, onTheirTurn(game, ai, "Pact of Negation")));
    }

    @Test
    public void aSpiritGuideInHandDoesNotPay() {
        final Game game = initAndCreateGame();
        final Player ai = game.getPlayers().get(1);
        addCards("Island", 2, ai);
        addCards("Mountain", 2, ai);
        addCardToZone("Simian Spirit Guide", ai, ZoneType.Hand);
        AssertJUnit.assertEquals("pact-unpayable", verdict(ai, onTheirTurn(game, ai, "Pact of Negation")));
    }

    /** Mirrors stored game 3685600: Vivi Ornitier's {0} ability is no filter, and the payment never uses it. */
    @Test
    public void viviOrnitierDoesNotPay() {
        final Game game = initAndCreateGame();
        final Player ai = game.getPlayers().get(1);
        addCards("Island", 2, ai);
        addCards("Mountain", 2, ai);
        final Card vivi = addCard("Vivi Ornitier", ai);
        vivi.setSickness(false);
        vivi.setCounters(CounterEnumType.P1P1, 3);
        AssertJUnit.assertEquals("Vivi has power 3", 3, vivi.getNetPower());
        AssertJUnit.assertEquals("pact-unpayable", verdict(ai, onTheirTurn(game, ai, "Pact of Negation")));
    }

    /** Mirrors stored game 3686108: The Cabbage Merchant taps two Foods, not itself. */
    @Test
    public void theCabbageMerchantWithoutFoodDoesNotPay() {
        final Game game = initAndCreateGame();
        final Player ai = game.getPlayers().get(1);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN2, ai);
        addCard("Ancient Tomb", ai);
        addCard("Tropical Island", ai);
        addCard("The Cabbage Merchant", ai).setSickness(false);
        AssertJUnit.assertEquals("pact-unpayable", verdict(ai, fromHand(ai, "Summoner's Pact")));
    }

    @Test
    public void springleafDrumDoesNotPay() {
        final Game game = initAndCreateGame();
        final Player ai = game.getPlayers().get(1);
        addCards("Island", 4, ai);
        addCard("Springleaf Drum", ai);
        addCard("Grizzly Bears", ai).setSickness(false);
        AssertJUnit.assertEquals("pact-unpayable", verdict(ai, onTheirTurn(game, ai, "Pact of Negation")));
    }

    /** Mirrors N's stored game 3656764: at 5 life the payment refuses Ancient Tomb's 2 damage (2 + 3 >= 5). */
    @Test
    public void ancientTombAtLowLifeDoesNotPay() {
        AssertJUnit.assertEquals("pact-unpayable", ancientTombAt(5));
    }

    @Test
    public void ancientTombJustAboveTheMarginPays() {
        assertAllowed("2 + 3 < 6", ancientTombAt(6));
    }

    @Test
    public void ancientTombAtHighLifePays() {
        assertAllowed("allowed", ancientTombAt(40));
    }

    private String ancientTombAt(final int life) {
        final Game game = initAndCreateGame();
        final Player ai = game.getPlayers().get(1);
        addCard("Ancient Tomb", ai);
        addCards("Island", 3, ai);
        ai.setLife(life, null);
        return verdict(ai, onTheirTurn(game, ai, "Pact of Negation"));
    }

    @Test
    public void manaConfluenceAtLifeTwoPays() {
        final Game game = initAndCreateGame();
        final Player ai = game.getPlayers().get(1);
        addCard("Mana Confluence", ai);
        addCard("Island", ai);
        addCards("Mountain", 3, ai);
        ai.setLife(2, null);
        assertAllowed("the payment's floor of 1: 2 - 1 >= 1", verdict(ai, onTheirTurn(game, ai, "Pact of Negation")));
    }

    @Test
    public void cityOfBrassAtLifeOneDoesNotPay() {
        final Game game = initAndCreateGame();
        final Player ai = game.getPlayers().get(1);
        addCard("City of Brass", ai);
        addCard("Island", ai);
        addCards("Mountain", 3, ai);
        ai.setLife(1, null);
        AssertJUnit.assertEquals("tapping City of Brass would kill the AI", "pact-unpayable",
                verdict(ai, onTheirTurn(game, ai, "Pact of Negation")));
    }

    @Test
    public void painIsBudgetedInTotal() {
        assertAllowed("pain 2 <= 3 - 1", painAt(3));
        AssertJUnit.assertEquals("each fits alone, the two together do not", "pact-unpayable", painAt(2));
    }

    private String painAt(final int life) {
        final Game game = initAndCreateGame();
        final Player ai = game.getPlayers().get(1);
        addCard("Mana Confluence", ai);
        addCard("City of Brass", ai);
        addCards("Island", 2, ai);
        addCard("Mountain", ai);
        ai.setLife(life, null);
        return verdict(ai, onTheirTurn(game, ai, "Pact of Negation"));
    }

    /** One debt per upkeep (D23): a Pact of the Titan resolved earlier is owed, whatever this turn's cast list holds. */
    @Test(timeOut = 120_000)
    public void aPendingDebtRefusesASecondPact() {
        final Game game = initAndCreateGame();
        final Player ai = game.getPlayers().get(1);
        final Player opp = opponentOf(game, ai);
        fillLibrary(ai, 10);
        fillLibrary(opp, 10);
        addCards("Island", 11, ai);
        addCards("Mountain", 2, ai);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, ai);
        onStack(game, "Pact of the Titan", ai, null);
        playUntilStackClear(game);
        AssertJUnit.assertTrue("Pact of the Titan resolved: its Giant is on the battlefield",
                ai.getCreaturesInPlay().anyMatch(Card::isToken));
        game.getStack().getSpellsCastThisTurn().clear();
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, opp);
        AssertJUnit.assertEquals("pact-debt-pending", verdict(ai, fromHand(ai, "Pact of Negation")));
    }

    @Test
    public void aPactOnTheStackIsOwed() {
        final Game game = initAndCreateGame();
        final Player ai = game.getPlayers().get(1);
        addCards("Island", 6, ai);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, opponentOf(game, ai));
        onStack(game, "Summoner's Pact", ai, null);
        AssertJUnit.assertEquals("pact-debt-pending", verdict(ai, fromHand(ai, "Pact of Negation")));
    }

    @Test
    public void aCounteredPactOwesNothing() {
        final Game game = initAndCreateGame();
        final Player ai = game.getPlayers().get(1);
        addCards("Island", 3, ai);
        addCards("Swamp", 2, ai);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, opponentOf(game, ai));
        // cast this turn and countered: in the cast list, never resolved, so no debt
        final Card countered = createCard("Pact of Negation", ai);
        final SpellAbility cast = countered.getFirstSpellAbility();
        cast.setActivatingPlayer(ai);
        game.getStack().getSpellsCastThisTurn().add(cast);
        assertAllowed("allowed", verdict(ai, fromHand(ai, "Pact of Negation")));
    }

    @Test
    public void theLandDropDoesNotCountAtTheEndStep() {
        final Game game = initAndCreateGame();
        final Player ai = game.getPlayers().get(1);
        game.getPhaseHandler().devModeSet(PhaseType.END_OF_TURN, ai);
        addCards("Swamp", 2, ai);
        addCardToZone("Swamp", ai, ZoneType.Hand);
        AssertJUnit.assertEquals("pact-unpayable", verdict(ai, fromHand(ai, "Slaughter Pact")));
    }

    @Test
    public void theLandDropCountsOnlyColorsEveryHandLandMakes() {
        final Game game = initAndCreateGame();
        final Player ai = game.getPlayers().get(1);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, ai);
        addCard("Forest", ai);
        addCards("Island", 2, ai);
        addCardToZone("Forest", ai, ZoneType.Hand);
        addCardToZone("Island", ai, ZoneType.Hand);
        AssertJUnit.assertEquals("the drop is generic ({G} and {U} share nothing): one G for GG", "pact-unpayable",
                verdict(ai, fromHand(ai, "Summoner's Pact")));
    }

    @Test
    public void aFetchLandInHandGivesNoLandDrop() {
        final Game game = initAndCreateGame();
        final Player ai = game.getPlayers().get(1);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, ai);
        addCards("Swamp", 2, ai);
        addCardToZone("Swamp", ai, ZoneType.Hand);
        addCardToZone("Polluted Delta", ai, ZoneType.Hand);
        AssertJUnit.assertEquals("pact-unpayable", verdict(ai, fromHand(ai, "Slaughter Pact")));
    }

    @Test
    public void cityOfTraitorsCountsNothingWhileALandMayBePlayed() {
        final Game game = initAndCreateGame();
        final Player ai = game.getPlayers().get(1);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, ai);
        addCard("City of Traitors", ai);
        addCards("Mountain", 3, ai);
        AssertJUnit.assertEquals("a land may still be played, which would sacrifice City of Traitors (3 < 5)",
                "pact-unpayable", verdict(ai, fromHand(ai, "Pact of the Titan")));
        game.getPhaseHandler().devModeSet(PhaseType.MAIN2, ai);
        ai.setLandsPlayedThisTurn(1);
        assertAllowed("the land drop is used: City of Traitors makes {C}{C}",
                verdict(ai, fromHand(ai, "Pact of the Titan")));
    }

    @Test
    public void aLethalAttackNoLongerWaivesAPact() {
        final Game game = initAndCreateGame();
        final Player ai = game.getPlayers().get(1);
        final Player opp = opponentOf(game, ai);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, ai);
        addCard("Craw Wurm", ai).setSickness(false);
        opp.setLife(5, null);
        addCard("Forest", ai);
        AssertJUnit.assertEquals("pact-unpayable", verdict(ai, fromHand(ai, "Summoner's Pact")));
    }

    @Test
    public void finalFortuneIsRefused() {
        final Game game = initAndCreateGame();
        final Player ai = game.getPlayers().get(1);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, ai);
        addCard("Craw Wurm", ai).setSickness(false);
        opponentOf(game, ai).setLife(5, null);
        addCards("Mountain", 2, ai);
        AssertJUnit.assertEquals("lethal on board or not, the extra turn's loss is not taken", "extra-turn-loses",
                verdict(ai, fromHand(ai, "Final Fortune")));
    }

    @Test
    public void theOtherExtraTurnLossesAreRefused() {
        final Game game = initAndCreateGame();
        final Player ai = game.getPlayers().get(1);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, ai);
        for (final String name : new String[] { "Last Chance", "Warrior's Oath", "Chance for Glory" }) {
            AssertJUnit.assertEquals(name, "extra-turn-loses", verdict(ai, fromHand(ai, name)));
        }
        AssertJUnit.assertEquals("Alchemist's Gambit", "extra-turn-loses", verdict(ai, gambit(ai, false)));
        AssertJUnit.assertEquals("Glorious End", "end-step-loses", verdict(ai, fromHand(ai, "Glorious End")));
    }

    @Test
    public void alchemistsGambitsCleaveIsAllowed() {
        final Game game = initAndCreateGame();
        final Player ai = game.getPlayers().get(1);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, ai);
        assertAllowed("allowed", verdict(ai, gambit(ai, true)));
    }

    @Test
    public void timeWalkIsAllowed() {
        final Game game = initAndCreateGame();
        final Player ai = game.getPlayers().get(1);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, ai);
        assertAllowed("allowed", verdict(ai, fromHand(ai, "Time Walk")));
    }

    @Test
    public void aLandWithATriggerInHandGivesNoLandDrop() {
        Game game = initAndCreateGame();
        Player ai = game.getPlayers().get(1);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, ai);
        addCards("Swamp", 2, ai);
        addCardToZone("Dimir Aqueduct", ai, ZoneType.Hand);
        AssertJUnit.assertEquals("a Karoo's entry takes a land back (2 < 3)", "pact-unpayable",
                verdict(ai, fromHand(ai, "Slaughter Pact")));
        game = initAndCreateGame();
        ai = game.getPlayers().get(1);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, ai);
        addCards("Swamp", 2, ai);
        addCardToZone("Swamp", ai, ZoneType.Hand);
        assertAllowed("a Swamp in hand is the third B", verdict(ai, fromHand(ai, "Slaughter Pact")));
    }

    @Test
    public void aLandWhoseColorsNeedAnActivatorGivesNoLandDrop() {
        final Game game = initAndCreateGame();
        final Player ai = game.getPlayers().get(1);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, ai);
        addCards("Swamp", 2, ai);
        addCardToZone("Command Tower", ai, ZoneType.Hand);
        AssertJUnit.assertEquals("Command Tower's colors need its commander: no unit, not a generic one (2 < 3)",
                "pact-unpayable", verdict(ai, fromHand(ai, "Slaughter Pact")));
    }

    /** RULINGS-R3 B5; mirrors stored game 3684526: Shang-Chi's mana pays only creatures' abilities. */
    @Test
    public void aRestrictedManaSourceDoesNotPay() {
        final Game game = initAndCreateGame();
        final Player ai = game.getPlayers().get(1);
        addCard("Ancient Tomb", ai);
        addCard("Breeding Pool", ai);
        addCard("Emergence Zone", ai);
        addCard("Shang-Chi, Master of Kung Fu", ai).setSickness(false);
        ai.setLife(34, null);
        AssertJUnit.assertEquals("pact-unpayable", verdict(ai, onTheirTurn(game, ai, "Pact of Negation")));
    }

    /** RULINGS-R3 B5: Delighted Halfling's any-color mana is for legendary spells only; its {C} still counts. */
    @Test
    public void delightedHalflingPaysOnlyColorless() {
        final Game game = initAndCreateGame();
        final Player ai = game.getPlayers().get(1);
        addCard("Island", ai);
        addCards("Mountain", 3, ai);
        addCard("Delighted Halfling", ai).setSickness(false);
        AssertJUnit.assertEquals("one U unit for UU", "pact-unpayable",
                verdict(ai, onTheirTurn(game, ai, "Pact of Negation")));
    }

    /** Review t1-1, proof A: a Signet's {1} is owed beside the debt (DESIGN.md 1.4, 1.6). */
    @Test
    public void reviewSignetInputIsOwed() {
        final Game game = initAndCreateGame();
        final Player ai = game.getPlayers().get(1);
        addCards("Island", 3, ai);
        addCard("Dimir Signet", ai);
        AssertJUnit.assertEquals("3 Islands and a Signet make 4, one Island paying the Signet", "pact-unpayable",
                verdict(ai, onTheirTurn(game, ai, "Pact of Negation")));
        addCard("Island", ai);
        assertAllowed("a fourth Island", verdict(ai, fromHand(ai, "Pact of Negation")));
    }

    /** Review t1-1, proof B: on the AI's own turn after combat the opponent's attack still comes first (1.4). */
    @Test
    public void reviewThreatOnOwnTurnAfterCombat() {
        for (final boolean attacker : new boolean[] { false, true }) {
            final Game game = initAndCreateGame();
            final Player ai = game.getPlayers().get(1);
            game.getPhaseHandler().devModeSet(PhaseType.MAIN2, ai);
            ai.setLandsPlayedThisTurn(1);
            addCard("Ancient Tomb", ai);
            addCards("Forest", 2, ai);
            ai.setLife(6, null);
            if (attacker) {
                addCard("Memnite", opponentOf(game, ai));
            }
            final String v = verdict(ai, fromHand(ai, "Summoner's Pact"));
            if (attacker) {
                AssertJUnit.assertEquals("6 life - 1 threat: 2 + 3 < 5 fails, the Tomb is not counted", "pact-unpayable", v);
            } else {
                assertAllowed("2 + 3 < 6", v);
            }
        }
    }

    /** Review t1-1, proof C: Exotic Orchard makes only what the opponents' lands make (1.4, Produce). */
    @Test
    public void reviewExoticOrchardOnlyTheirColors() {
        for (final String theirs : new String[] { "Mountain", "Island" }) {
            final Game game = initAndCreateGame();
            final Player ai = game.getPlayers().get(1);
            addCard(theirs, opponentOf(game, ai));
            addCard("Island", ai);
            addCards("Swamp", 3, ai);
            addCard("Exotic Orchard", ai);
            final String v = verdict(ai, onTheirTurn(game, ai, "Pact of Negation"));
            if ("Mountain".equals(theirs)) {
                AssertJUnit.assertEquals("their Mountain: the Orchard makes R, one U for UU", "pact-unpayable", v);
            } else {
                assertAllowed("their Island: the Orchard makes U", v);
            }
        }
    }

    /** Review t1-1, proof D: 0 counts 0, no max(1, ...) (1.4). */
    @Test
    public void reviewGaeasCradleWithNoCreature() {
        final Game game = initAndCreateGame();
        final Player ai = game.getPlayers().get(1);
        addCards("Island", 4, ai);
        addCard("Gaea's Cradle", ai);
        AssertJUnit.assertEquals("no creature: the Cradle makes nothing", "pact-unpayable",
                verdict(ai, onTheirTurn(game, ai, "Pact of Negation")));
        addCard("Memnite", ai);
        assertAllowed("one creature: one G", verdict(ai, fromHand(ai, "Pact of Negation")));
    }

    /** Review t1-1, proof E: a Combo ability makes only the letters canProduce answers (1.4). */
    @Test
    public void reviewTalismanOnlyItsColors() {
        for (final String talisman : new String[] { "Talisman of Creativity", "Talisman of Dominance" }) {
            final Game game = initAndCreateGame();
            final Player ai = game.getPlayers().get(1);
            addCards("Island", 2, ai);
            addCard(talisman, ai);
            final String v = verdict(ai, onTheirTurn(game, ai, "Slaughter Pact"));
            if ("Talisman of Creativity".equals(talisman)) {
                AssertJUnit.assertEquals("U or R: no B for 2B", "pact-unpayable", v);
            } else {
                assertAllowed("U or B: the B", v);
            }
        }
    }

    /** Review t1-1, proof F: Bloom Tender gives one unit of each of its own colors, no other (1.4, L1). */
    @Test
    public void reviewBloomTenderOnlyItsOwnColor() {
        for (final String other : new String[] { "Forest", "Island" }) {
            final Game game = initAndCreateGame();
            final Player ai = game.getPlayers().get(1);
            addCard("Island", ai);
            addCard(other, ai);
            addCard("Sol Ring", ai);
            addCard("Bloom Tender", ai);
            final String v = verdict(ai, onTheirTurn(game, ai, "Pact of Negation"));
            if ("Forest".equals(other)) {
                AssertJUnit.assertEquals("Bloom Tender is green: one U for UU", "pact-unpayable", v);
            } else {
                assertAllowed("two Islands", v);
            }
        }
    }

    /**
     * T1 review finding 9: Mirrex makes any color only on the turn it entered ({@code IsPresent$
     * Card.Self+ThisTurnEntered}), which today's board holds and the next upkeep's cannot. A timing condition is judged
     * at the due time (RULINGS-R3 M22), so Mirrex counts {C} for the upkeep.
     */
    @Test
    public void mirrexMakesOnlyColorlessAtTheNextUpkeep() {
        for (final String third : new String[] { "Mountain", "Forest" }) {
            final Game game = initAndCreateGame();
            final Player ai = game.getPlayers().get(1);
            game.getPhaseHandler().devModeSet(PhaseType.MAIN2, ai);
            ai.setLandsPlayedThisTurn(1);
            addCard("Forest", ai);
            addCard("Mountain", ai);
            addCard(third, ai);
            final Card mirrex = addCard("Mirrex", ai);
            AssertJUnit.assertTrue("Mirrex is the land played this turn", mirrex.enteredThisTurn());
            final String v = verdict(ai, fromHand(ai, "Summoner's Pact"));
            if ("Mountain".equals(third)) {
                AssertJUnit.assertEquals("Mirrex makes {C} at the upkeep: one G for GG", "pact-unpayable", v);
            } else {
                assertAllowed("two Forests", v);
            }
        }
    }
}
