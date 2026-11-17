package forge.ai;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Supplier;

import org.testng.AssertJUnit;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.Test;

import forge.ai.simulation.ActivationGuard;
import forge.ai.simulation.ComboCatalog;
import forge.ai.simulation.ComboGuard;
import forge.ai.simulation.ComboPlan;
import forge.ai.simulation.FlatMonteCarloPicker;
import forge.game.Game;
import forge.game.card.Card;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;

/**
 * Activated abilities with nothing to act on are not discovered by Flat Monte Carlo.
 *
 * <p>Report: the AI tapped Oran-Rief, the Vastwood every turn with no green creature that entered this turn. The
 * heuristics refuse it, but discovery re-adds counter effects and the rollouts cannot see the wasted tap.
 */
public class FlatMonteCarloActivationTest extends AITest {
    private Game game;
    private Player ai;

    private void setUp() {
        game = initAndCreateGame();
        game.AI_FLAT_MONTE_CARLO = true;
        game.AI_TIMEOUT = 10;
        ai = game.getPlayers().get(1);
    }

    private SpellAbility ability(final Card c) {
        for (final SpellAbility sa : c.getSpellAbilities()) {
            if (sa.isActivatedAbility() && !sa.isManaAbility()) {
                sa.setActivatingPlayer(ai);
                return sa;
            }
        }
        throw new IllegalStateException("no activated ability on " + c);
    }

    private SpellAbility oran() {
        return ability(addCardToZone("Oran-Rief, the Vastwood", ai, ZoneType.Battlefield));
    }

    @Test
    public void noCreaturesAtAll() {
        setUp();
        AssertJUnit.assertEquals("nothing-to-affect", ActivationGuard.problem(ai, oran()));
    }

    @Test
    public void aNonGreenCreatureThatEnteredThisTurn() {
        setUp();
        final SpellAbility sa = oran();
        addCardToZone("Hill Giant", ai, ZoneType.Battlefield).setSickness(true);
        AssertJUnit.assertEquals("nothing-to-affect", ActivationGuard.problem(ai, sa));
    }

    @Test
    public void aGreenCreatureOfTheOpponentIsNotAHelp() {
        setUp();
        final SpellAbility sa = oran();
        addCardToZone("Grizzly Bears", game.getPlayers().get(0), ZoneType.Battlefield);
        AssertJUnit.assertEquals("nothing-to-affect", ActivationGuard.problem(ai, sa));
    }

    @Test
    public void aGreenCreatureThatJustEnteredIsPlayed() {
        setUp();
        final SpellAbility sa = oran();
        game.getPhaseHandler().devModeSet(PhaseType.MAIN2, ai);
        addCardToZone("Grizzly Bears", ai, ZoneType.Battlefield);
        AssertJUnit.assertNull("a Bears that entered this turn", ActivationGuard.problem(ai, sa));
    }

    @Test
    public void theComboGuardRefusesTheIdleAbility() {
        setUp();
        AssertJUnit.assertEquals("nothing-to-affect", ComboGuard.problem(ai, oran(), null, ComboPlan.EMPTY));
    }

    @Test
    public void aTargetedAbilityWithNoTargetIsIdle() {
        setUp();
        // Prodigal Sorcerer: tap to deal 1 damage to any target, always has a player to hit: never idle
        final SpellAbility ping = ability(addCardToZone("Prodigal Sorcerer", ai, ZoneType.Battlefield));
        AssertJUnit.assertNull("a player is always a legal target", ActivationGuard.problem(ai, ping));
        // Icy Manipulator-like: tap target artifact, creature or land - there is always a land? use a creature-only one
        final SpellAbility tapper = ability(addCardToZone("Master Decoy", ai, ZoneType.Battlefield));
        // the only creature is the Decoy itself, which is a legal target of "tap target creature"
        AssertJUnit.assertNull(ActivationGuard.problem(ai, tapper));
    }

    @Test
    public void spellsAndManaAbilitiesAreNotJudged() {
        setUp();
        final Card forest = addCardToZone("Forest", ai, ZoneType.Battlefield);
        AssertJUnit.assertFalse(ActivationGuard.isActivated(forest.getManaAbilities().get(0)));
    }

    /** Cards put straight into play have no counters: a Planeswalker its loyalty, a Carnifex Demon its -1/-1 counters. */
    private Card withCounters(final Card c, final String type, final int amount) {
        c.addCounterInternal(forge.game.card.CounterType.getType(type), amount, ai, false, null, null);
        return c;
    }

    private Card theirs(final String name) {
        return addCardToZone(name, game.getPlayers().get(0), ZoneType.Battlefield);
    }

    private SpellAbility mine(final String name) {
        return ability(addCardToZone(name, ai, ZoneType.Battlefield));
    }

    // ------------------------------------------------------------------------------------------ targeted abilities

    @Test
    public void royalAssassinWithNoTappedCreatureHasNoTarget() {
        setUp();
        final SpellAbility sa = mine("Royal Assassin");
        theirs("Hill Giant"); // untapped: not a legal target of "target tapped creature"
        AssertJUnit.assertEquals("no-valid-targets", ActivationGuard.problem(ai, sa));
    }

    @Test
    public void royalAssassinWithATappedCreatureIsPlayed() {
        setUp();
        final SpellAbility sa = mine("Royal Assassin");
        theirs("Hill Giant").setTapped(true);
        AssertJUnit.assertNull(ActivationGuard.problem(ai, sa));
    }

    @Test
    public void royalAssassinIsRefusedByTheComboGuardToo() {
        setUp();
        AssertJUnit.assertEquals("no-valid-targets", ComboGuard.problem(ai, mine("Royal Assassin"), null, ComboPlan.EMPTY));
    }

    // ------------------------------------------------------------------------------------------ damage to the opposing side

    @Test
    public void ashenFirebeastWithNothingToHurtIsIdle() {
        setUp();
        final SpellAbility sa = mine("Ashen Firebeast");
        AssertJUnit.assertEquals("only itself (the AI's own) is hit", "nothing-to-affect", ActivationGuard.problem(ai, sa));
    }

    @Test
    public void ashenFirebeastIgnoresAFlyer() {
        setUp();
        final SpellAbility sa = mine("Ashen Firebeast");
        theirs("Wind Drake");
        AssertJUnit.assertEquals("a flyer is not hit", "nothing-to-affect", ActivationGuard.problem(ai, sa));
    }

    @Test
    public void ashenFirebeastWithAnOpposingGroundCreatureIsPlayed() {
        setUp();
        final SpellAbility sa = mine("Ashen Firebeast");
        theirs("Grizzly Bears");
        AssertJUnit.assertNull(ActivationGuard.problem(ai, sa));
    }

    // ------------------------------------------------------------------------------------------ tap all

    @Test
    public void thundercloudElementalNeedsAnUntappedSmallOpposingCreature() {
        setUp();
        final SpellAbility sa = mine("Thundercloud Elemental");
        theirs("Hill Giant"); // toughness 3
        AssertJUnit.assertEquals("too big to be tapped by it", "nothing-to-affect", ActivationGuard.problem(ai, sa));
        final Card bears = theirs("Grizzly Bears");
        bears.setTapped(true);
        AssertJUnit.assertEquals("already tapped", "nothing-to-affect", ActivationGuard.problem(ai, sa));
        bears.setTapped(false);
        AssertJUnit.assertNull("an untapped Bears", ActivationGuard.problem(ai, sa));
    }

    // ------------------------------------------------------------------------------------------ untap all

    @Test
    public void myrGalvanizerNeedsAnotherTappedMyr() {
        setUp();
        final SpellAbility sa = mine("Myr Galvanizer");
        AssertJUnit.assertEquals("no other Myr", "nothing-to-affect", ActivationGuard.problem(ai, sa));
        final Card other = addCardToZone("Myr Galvanizer", ai, ZoneType.Battlefield);
        AssertJUnit.assertEquals("another Myr, but untapped", "nothing-to-affect", ActivationGuard.problem(ai, sa));
        other.setTapped(true);
        AssertJUnit.assertNull("a tapped Myr to untap", ActivationGuard.problem(ai, sa));
    }

    // ------------------------------------------------------------------------------------------ a boost and a curse

    @Test
    public void aBoostForOwnCreaturesIsPlayedWhenThereIsOne() {
        setUp();
        // Akroan Phalanx is itself a creature the AI controls: Creature.YouCtrl always matches it
        AssertJUnit.assertNull(ActivationGuard.problem(ai, mine("Akroan Phalanx")));
    }

    @Test
    public void aBoostIgnoresTheOpponentsCreatures() {
        setUp();
        final SpellAbility sa = mine("Akroan Phalanx");
        AssertJUnit.assertNull("its own source is enough", ActivationGuard.problem(ai, sa));
        theirs("Hill Giant");
        AssertJUnit.assertNull("their creature changes nothing", ActivationGuard.problem(ai, sa));
    }

    @Test
    public void aCurseNeedsAnOpposingCreature() {
        setUp();
        final SpellAbility sa = ability(withCounters(addCardToZone("Carnifex Demon", ai, ZoneType.Battlefield), "M1M1", 2));
        AssertJUnit.assertEquals("no other creature at all", "nothing-to-affect", ActivationGuard.problem(ai, sa));
        addCardToZone("Grizzly Bears", ai, ZoneType.Battlefield);
        AssertJUnit.assertEquals("only the AI's own creature would get the -1/-1", "nothing-to-affect",
                ActivationGuard.problem(ai, sa));
        theirs("Hill Giant");
        AssertJUnit.assertNull("an opposing creature", ActivationGuard.problem(ai, sa));
    }

    // ------------------------------------------------------------------------------------------ read from the ability

    /** The activated ability of the card whose cost script starts with the given text (a loyalty cost, say). */
    private SpellAbility withCost(final Card c, final String cost) {
        for (final SpellAbility sa : c.getSpellAbilities()) {
            if (sa.isActivatedAbility() && sa.hasParam("Cost") && sa.getParam("Cost").startsWith(cost)) {
                sa.setActivatingPlayer(ai);
                return sa;
            }
        }
        throw new IllegalStateException("no ability with cost " + cost + " on " + c);
    }

    @Test
    public void aPlusAbilityIsWorthItsLoyaltyEvenWithNothingToTarget() {
        setUp();
        final Card ajani = withCounters(addCardToZone("Ajani, Caller of the Pride", ai, ZoneType.Battlefield), "LOYALTY", 4);
        AssertJUnit.assertNull("+1 on up to one creature, none in play: the loyalty is the point",
                ActivationGuard.problem(ai, withCost(ajani, "AddCounter<1/LOYALTY>")));
    }

    @Test
    public void aMinusAbilityWithNothingToDoIsIdle() {
        setUp();
        final Card ajani = withCounters(addCardToZone("Ajani Goldmane", ai, ZoneType.Battlefield), "LOYALTY", 4);
        final SpellAbility counters = withCost(ajani, "SubCounter<1/LOYALTY>");
        AssertJUnit.assertEquals("-1: a counter on each creature, and there is none", "nothing-to-affect",
                ActivationGuard.problem(ai, counters));
        addCardToZone("Grizzly Bears", ai, ZoneType.Battlefield);
        AssertJUnit.assertNull("with a creature of the AI's", ActivationGuard.problem(ai, counters));
    }

    @Test
    public void aPlusAbilityWithNoEffectAtAllStillGainsLife() {
        setUp();
        final Card ajani = withCounters(addCardToZone("Ajani Goldmane", ai, ZoneType.Battlefield), "LOYALTY", 4);
        AssertJUnit.assertNull("+1: gain 2 life", ActivationGuard.problem(ai, withCost(ajani, "AddCounter<1/LOYALTY>")));
    }

    @Test
    public void anUpToAbilityWithNoCreatureAndAnEffectOnWhatItTargetedIsIdle() {
        setUp();
        final Card ajani = withCounters(addCardToZone("Ajani, Sleeper Agent", ai, ZoneType.Battlefield), "LOYALTY", 4);
        final SpellAbility minus = withCost(ajani, "SubCounter<3/LOYALTY>");
        AssertJUnit.assertEquals("up to three creatures, none in play; the vigilance goes to those targets too",
                "no-valid-targets", ActivationGuard.problem(ai, minus));
        addCardToZone("Grizzly Bears", ai, ZoneType.Battlefield);
        AssertJUnit.assertNull("a creature to put counters on", ActivationGuard.problem(ai, minus));
    }

    @Test
    public void anAbilityThatNeedsTwoTargetsNeedsTwoCandidates() {
        setUp();
        final SpellAbility untap = mine("Argothian Elder");
        addCardToZone("Forest", ai, ZoneType.Battlefield);
        AssertJUnit.assertEquals("one land: not the two it untaps", "no-valid-targets", ActivationGuard.problem(ai, untap));
        addCardToZone("Forest", ai, ZoneType.Battlefield);
        AssertJUnit.assertNull("two lands", ActivationGuard.problem(ai, untap));
    }

    @Test
    public void aCostThatNeedsAnotherCreatureIsRefusedWithoutOne() {
        setUp();
        final SpellAbility sa = mine("Blood Host");
        AssertJUnit.assertEquals("sacrifice another creature, and there is none", "cost-unpayable",
                ActivationGuard.problem(ai, sa));
        final Card bears = addCardToZone("Grizzly Bears", ai, ZoneType.Battlefield);
        AssertJUnit.assertEquals("a creature worth more than the outlet gives is not fodder", "valuable-sacrifice",
                ActivationGuard.problem(ai, sa));
        bears.setSVar("SacMe", "1");
        AssertJUnit.assertNull("with fodder to sacrifice", ActivationGuard.problem(ai, sa));
    }

    @Test
    public void aSacrificeWithNothingToSacrificeAndNothingToReturnIsIdle() {
        setUp();
        final Card daretti = withCounters(addCardToZone("Daretti, Scrap Savant", ai, ZoneType.Battlefield), "LOYALTY", 3);
        final SpellAbility minus = withCost(daretti, "SubCounter<2/LOYALTY>");
        AssertJUnit.assertEquals("no artifact to sacrifice, none in the graveyard to return", "nothing-to-affect",
                ActivationGuard.problem(ai, minus));
        addCardToZone("Sol Ring", ai, ZoneType.Battlefield);
        AssertJUnit.assertNull("an artifact to sacrifice", ActivationGuard.problem(ai, minus));
    }

    // ------------------------------------------------------- an Equipment whose Equip was already used this turn

    private List<SpellAbility> equips(final Card equipment) {
        final List<SpellAbility> found = new ArrayList<>();
        for (final SpellAbility sa : equipment.getSpellAbilities()) {
            if (sa.isEquip()) {
                sa.setActivatingPlayer(ai);
                found.add(sa);
            }
        }
        if (found.isEmpty()) {
            throw new IllegalStateException("no equip ability on " + equipment);
        }
        return found;
    }

    private SpellAbility equip(final Card equipment) {
        return equips(equipment).get(0);
    }

    /** A creature the AI has had since before this turn. */
    private Card old(final String name) {
        final Card c = addCardToZone(name, ai, ZoneType.Battlefield);
        c.setSickness(false);
        return c;
    }

    /** A creature that came under the AI's control this turn. */
    private Card arrived(final String name) {
        final Card c = addCardToZone(name, ai, ZoneType.Battlefield);
        c.setSickness(true);
        return c;
    }

    /** This Equipment on this creature, as the turn finds them. */
    private Card attached(final String equipmentName, final Card creature) {
        final Card equipment = addCardToZone(equipmentName, ai, ZoneType.Battlefield);
        equipment.attachToEntity(creature, null);
        game.getAction().checkStateEffects(true);
        return equipment;
    }

    /** Lightning Greaves on this creature, as the turn finds them. */
    private Card greavesOn(final Card creature) {
        return attached("Lightning Greaves", creature);
    }

    /** What the combo catalogue was configured with before {@link #catalogue} replaced it for a test method. */
    private Supplier<List<String>> catalogueBefore;
    private boolean catalogueReplaced;

    /**
     * The combo catalogue (ai-combos.txt) holding only these lines until the test method ends. The catalogue is one
     * for the JVM, which every test class shares, so {@link #putTheCatalogueBack} restores it after a failure too (as
     * forge.ai.combo.NoComboCatalog does for its classes, and reading what was configured the same way).
     */
    @SuppressWarnings("unchecked")
    private void catalogue(final String... lines) {
        synchronized (ComboCatalog.class) {
            if (!catalogueReplaced) {
                try {
                    final Field file = ComboCatalog.class.getDeclaredField("file");
                    file.setAccessible(true);
                    catalogueBefore = (Supplier<List<String>>) file.get(null);
                } catch (final ReflectiveOperationException e) {
                    throw new IllegalStateException("cannot read the combo catalogue's configured file", e);
                }
                catalogueReplaced = true;
            }
            final List<String> text = Arrays.asList(lines);
            ComboCatalog.configure(() -> text);
        }
    }

    @AfterMethod(alwaysRun = true)
    public void putTheCatalogueBack() {
        if (catalogueReplaced) {
            ComboCatalog.configure(catalogueBefore);
            catalogueReplaced = false;
        }
    }

    /** Puts the ability on the stack aimed at the creature, as an activation does, and resolves it. */
    private void activate(final SpellAbility sa, final Card to) {
        sa.setActivatingPlayer(ai);
        sa.resetTargets();
        sa.getTargets().add(to);
        game.getStack().add(sa);
        game.getStack().resolveStack();
        game.getAction().checkStateEffects(true);
    }

    private void move(final Card equipment, final Card to) {
        activate(equip(equipment), to);
        AssertJUnit.assertSame("the Equipment moved", to, equipment.getEquipping());
    }

    @Test
    public void theTurnsFirstEquipIsAimedAsItAlwaysWas() {
        setUp();
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, ai);
        final Card angel = old("Serra Angel");
        final Card bears = old("Grizzly Bears");
        final Card giant = old("Hill Giant");
        // Swiftfoot Boots give hexproof, not shroud: the creature they are on stays a legal target of their Equip
        final Card boots = attached("Swiftfoot Boots", angel);
        final SpellAbility sa = equip(boots);
        AssertJUnit.assertNull("no Equip yet this turn, another creature to move to", ActivationGuard.problem(ai, sa));
        AssertJUnit.assertFalse(ActivationGuard.equipTargetClosed(ai, sa, giant));
        AssertJUnit.assertFalse("the filter says nothing about the turn's first Equip, the creature it is on included",
                ActivationGuard.equipTargetClosed(ai, sa, angel));
        AssertJUnit.assertSame("so the search aims it at the best creature, as it did before the rule, the Boots' own",
                angel, FlatMonteCarloPicker.loyaltyTarget(ai, sa));
        AssertJUnit.assertTrue(FlatMonteCarloPicker.applyTarget(sa, bears)); // the heuristics' aim, say
        AssertJUnit.assertEquals("and tries every other creature in place of the heuristics' aim, as before",
                Arrays.asList(angel, giant), FlatMonteCarloPicker.alternativeTargets(ai, sa, 3));
        move(boots, bears);
        AssertJUnit.assertTrue("after an Equip this turn the creature it is on is closed",
                ActivationGuard.equipTargetClosed(ai, sa, bears));
        AssertJUnit.assertTrue("and so is the one it came from", ActivationGuard.equipTargetClosed(ai, sa, angel));
        AssertJUnit.assertNull("nothing the search may aim at: no target", FlatMonteCarloPicker.loyaltyTarget(ai, sa));
    }

    @Test
    public void anEquipWhoseOnlyTargetIsItsOwnCreatureIsIdle() {
        setUp();
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, ai);
        final Card boots = attached("Swiftfoot Boots", old("Grizzly Bears"));
        AssertJUnit.assertEquals("the only legal target is the creature the Boots are on: the move does nothing",
                "no-valid-targets", ActivationGuard.problem(ai, equip(boots)));
        old("Hill Giant");
        AssertJUnit.assertNull("with another creature there is a move to make", ActivationGuard.problem(ai, equip(boots)));
    }

    @Test
    public void afterAnEquipACreatureOfEarlierTurnsIsClosedUntilTheNextTurn() {
        setUp();
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, ai);
        final Card bears = old("Grizzly Bears");
        final Card giant = old("Hill Giant");
        final Card greaves = greavesOn(bears);
        move(greaves, giant); // through the stack: the record the rule reads is the stack's own
        final SpellAbility sa = equip(greaves);
        AssertJUnit.assertTrue("moved this turn: not onto a creature that was there all along",
                ActivationGuard.equipTargetClosed(ai, sa, bears));
        AssertJUnit.assertTrue("nor onto the creature it is on", ActivationGuard.equipTargetClosed(ai, sa, giant));
        AssertJUnit.assertEquals("nowhere left to go: the Equip is idle", "no-valid-targets",
                ActivationGuard.problem(ai, sa));
        AssertJUnit.assertEquals("the combo guard takes the heuristics' own move out with it", "no-valid-targets",
                ComboGuard.problem(ai, sa, null, ComboPlan.EMPTY));
        game.getStack().onNextTurn(); // the stack forgets the turn's activations
        AssertJUnit.assertNull("the next turn it may move again", ActivationGuard.problem(ai, sa));
        AssertJUnit.assertFalse(ActivationGuard.equipTargetClosed(ai, sa, bears));
    }

    @Test
    public void aCreatureThatArrivedThisTurnTakesItOnce() {
        setUp();
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, ai);
        final Card bears = old("Grizzly Bears");
        final Card giant = old("Hill Giant");
        final Card greaves = greavesOn(bears);
        move(greaves, giant); // the turn's first move
        final Card elves = arrived("Llanowar Elves");
        final Card ogre = arrived("Gray Ogre");
        final SpellAbility sa = equip(greaves);
        AssertJUnit.assertFalse("a creature just cast: haste, to tap for its ability at once",
                ActivationGuard.equipTargetClosed(ai, sa, elves));
        AssertJUnit.assertNull(ActivationGuard.problem(ai, sa));
        move(greaves, elves);
        AssertJUnit.assertFalse("and the next one just cast", ActivationGuard.equipTargetClosed(ai, sa, ogre));
        move(greaves, ogre);
        AssertJUnit.assertTrue("it has had the Greaves this turn", ActivationGuard.equipTargetClosed(ai, sa, elves));
        AssertJUnit.assertTrue(ActivationGuard.equipTargetClosed(ai, sa, bears));
        AssertJUnit.assertTrue(ActivationGuard.equipTargetClosed(ai, sa, giant));
        AssertJUnit.assertEquals("no-valid-targets", ActivationGuard.problem(ai, sa));
        final Card goblin = arrived("Raging Goblin");
        AssertJUnit.assertFalse("a creature with haste of its own arrived this turn all the same",
                ActivationGuard.equipTargetClosed(ai, sa, goblin));
        AssertJUnit.assertNull(ActivationGuard.problem(ai, sa));
    }

    @Test
    public void itGoesBackOntoACommanderButNotFromOneCommanderToAnother() {
        setUp();
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, ai);
        final Card bears = old("Grizzly Bears");
        final Card giant = old("Hill Giant");
        final Card commander = old("Serra Angel");
        commander.setCommander(true);
        final Card partner = old("Air Elemental");
        partner.setCommander(true);
        final Card greaves = greavesOn(commander);
        move(greaves, bears); // the turn's first move, off the commander
        final SpellAbility sa = equip(greaves);
        AssertJUnit.assertFalse("back onto the commander", ActivationGuard.equipTargetClosed(ai, sa, commander));
        AssertJUnit.assertTrue("not onto another creature of earlier turns", ActivationGuard.equipTargetClosed(ai, sa, giant));
        AssertJUnit.assertNull(ActivationGuard.problem(ai, sa));
        move(greaves, commander);
        AssertJUnit.assertTrue("from one commander to the other is no return",
                ActivationGuard.equipTargetClosed(ai, sa, partner));
        AssertJUnit.assertTrue(ActivationGuard.equipTargetClosed(ai, sa, bears));
        AssertJUnit.assertEquals("no-valid-targets", ActivationGuard.problem(ai, sa));
        final Card elves = arrived("Llanowar Elves"); // a creature is cast: off the commander once more, and back
        AssertJUnit.assertFalse(ActivationGuard.equipTargetClosed(ai, sa, elves));
        move(greaves, elves);
        AssertJUnit.assertFalse("back onto the commander a second time", ActivationGuard.equipTargetClosed(ai, sa, commander));
        move(greaves, commander);
        AssertJUnit.assertTrue("the creature that arrived has had it", ActivationGuard.equipTargetClosed(ai, sa, elves));
        AssertJUnit.assertEquals("no-valid-targets", ActivationGuard.problem(ai, sa));
    }

    @Test
    public void anEquipmentWhoseCreatureLeftIsFreeAgain() {
        setUp();
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, ai);
        final Card bears = old("Grizzly Bears");
        final Card giant = old("Hill Giant");
        final Card greaves = greavesOn(bears);
        move(greaves, giant);
        game.getAction().moveToGraveyard(giant, null); // the creature it moved onto dies
        game.getAction().checkStateEffects(true);
        AssertJUnit.assertFalse(greaves.isEquipping());
        final SpellAbility sa = equip(greaves);
        AssertJUnit.assertFalse("attached to nothing: any creature", ActivationGuard.equipTargetClosed(ai, sa, bears));
        AssertJUnit.assertNull(ActivationGuard.problem(ai, sa));
    }

    @Test
    public void anEquipmentJustCastHasItsFirstEquipOfTheTurnToo() {
        setUp();
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, ai);
        final Card bears = old("Grizzly Bears");
        final Card giant = old("Hill Giant");
        final Card greaves = addCardToZone("Lightning Greaves", ai, ZoneType.Battlefield); // on nothing yet
        final SpellAbility sa = equip(greaves);
        AssertJUnit.assertFalse(ActivationGuard.equipTargetClosed(ai, sa, bears));
        AssertJUnit.assertNull(ActivationGuard.problem(ai, sa));
        move(greaves, bears);
        AssertJUnit.assertTrue("that was its Equip of the turn: not on to a creature of earlier turns",
                ActivationGuard.equipTargetClosed(ai, sa, giant));
        AssertJUnit.assertEquals("no-valid-targets", ActivationGuard.problem(ai, sa));
    }

    @Test
    public void everyEquipAbilityOfAnEquipmentIsItsEquip() {
        setUp();
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, ai);
        // Hand of Vecna: "Equip - Pay 1 life for each card in your hand" beside "Equip {2}"
        final Card bears = old("Grizzly Bears");
        final Card giant = old("Hill Giant");
        final Card ogre = old("Gray Ogre");
        final Card hand = attached("Hand of Vecna", bears);
        final List<SpellAbility> both = equips(hand);
        AssertJUnit.assertEquals("its two Equip abilities", 2, both.size());
        AssertJUnit.assertFalse(ActivationGuard.equipTargetClosed(ai, both.get(1), ogre));
        activate(both.get(0), giant);
        AssertJUnit.assertSame(giant, hand.getEquipping());
        AssertJUnit.assertTrue("the other Equip is the same Equipment's: closed as well",
                ActivationGuard.equipTargetClosed(ai, both.get(1), ogre));
        AssertJUnit.assertEquals("no-valid-targets", ActivationGuard.problem(ai, both.get(1)));
    }

    @Test
    public void aComboThatPaysForTheAttachmentSwitchesTheRuleOff() {
        setUp();
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, ai);
        final Card bears = old("Grizzly Bears");
        final Card giant = old("Hill Giant");
        final Card greaves = greavesOn(bears);
        move(greaves, giant);
        final SpellAbility sa = equip(greaves);
        AssertJUnit.assertTrue("no combo: the rule holds", ActivationGuard.equipTargetClosed(ai, sa, bears));
        AssertJUnit.assertEquals("no-valid-targets", ActivationGuard.problem(ai, sa));
        // a combo of the catalogue, whole on the AI's side of the table, with the Equipment as one of its pieces
        catalogue("win | Lightning Greaves + Grizzly Bears | Lightning Greaves | Grizzly Bears");
        AssertJUnit.assertFalse("the combo pays for every attachment: the search aims as the combo needs",
                ActivationGuard.equipTargetClosed(ai, sa, bears));
        AssertJUnit.assertNull("and the Equip is no idle play", ActivationGuard.problem(ai, sa));
        AssertJUnit.assertSame(bears, FlatMonteCarloPicker.loyaltyTarget(ai, sa));
    }

    @Test
    public void theSearchAimsOnlyWhereTheEquipmentMayGo() {
        setUp();
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, ai);
        final Card bears = old("Grizzly Bears");
        final Card giant = old("Hill Giant");
        final Card angel = old("Serra Angel");
        final Card greaves = greavesOn(bears);
        final SpellAbility sa = equip(greaves);
        AssertJUnit.assertSame("the turn's first move: the best creature", angel, FlatMonteCarloPicker.loyaltyTarget(ai, sa));
        move(greaves, giant);
        final Card elves = arrived("Llanowar Elves");
        AssertJUnit.assertSame("after a move the best creature is closed, the one that arrived is not", elves,
                FlatMonteCarloPicker.loyaltyTarget(ai, sa));
        AssertJUnit.assertTrue(FlatMonteCarloPicker.applyTarget(sa, elves)); // the heuristics' aim, say
        AssertJUnit.assertTrue("no creature of earlier turns is offered in its place",
                FlatMonteCarloPicker.alternativeTargets(ai, sa, 3).isEmpty());
        final Card ogre = arrived("Gray Ogre");
        AssertJUnit.assertEquals("another creature that arrived is", 1, FlatMonteCarloPicker.alternativeTargets(ai, sa, 3).size());
        AssertJUnit.assertSame(ogre, FlatMonteCarloPicker.alternativeTargets(ai, sa, 3).get(0));
    }

    @Test
    public void anotherEquipmentsMovesAreItsOwn() {
        setUp();
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, ai);
        final Card bears = old("Grizzly Bears");
        final Card giant = old("Hill Giant");
        final Card ogre = old("Gray Ogre");
        final Card greaves = greavesOn(bears);
        final Card shuko = addCardToZone("Shuko", ai, ZoneType.Battlefield);
        shuko.attachToEntity(giant, null);
        game.getAction().checkStateEffects(true);
        move(shuko, ogre); // the Shuko's first move of the turn
        final SpellAbility sa = equip(greaves);
        AssertJUnit.assertFalse("the Greaves have not moved this turn", ActivationGuard.equipTargetClosed(ai, sa, giant));
        AssertJUnit.assertNull(ActivationGuard.problem(ai, sa));
        AssertJUnit.assertTrue("the Shuko has", ActivationGuard.equipTargetClosed(ai, equip(shuko), giant));
    }

    @Test
    public void anotherAttachAbilityIsNoMoveOfTheEquip() {
        setUp();
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, ai);
        // Cranial Plating: "{B}{B}: Attach to target creature you control" beside its Equip {1}
        final Card bears = old("Grizzly Bears");
        final Card giant = old("Hill Giant");
        final Card plating = addCardToZone("Cranial Plating", ai, ZoneType.Battlefield);
        plating.attachToEntity(bears, null);
        game.getAction().checkStateEffects(true);
        SpellAbility attach = null;
        int others = 0;
        for (final SpellAbility other : plating.getSpellAbilities()) {
            if (other.isActivatedAbility() && !other.isEquip() && !other.isManaAbility()) {
                attach = other;
                others++;
            }
        }
        AssertJUnit.assertEquals("the Plating's own attach ability", 1, others);
        activate(attach, giant); // where the AI lets it resolve to is its own affair: the activation is on record
        AssertJUnit.assertEquals("the stack recorded the activation", 1, game.getStack().getAbilityActivatedThisTurn().size());
        final Card elsewhere = plating.getEquipping() == bears ? giant : bears;
        final SpellAbility sa = equip(plating);
        AssertJUnit.assertFalse("only an Equip counts as the turn's Equip: its first is still open",
                ActivationGuard.equipTargetClosed(ai, sa, elsewhere));
        AssertJUnit.assertNull(ActivationGuard.problem(ai, sa));
        final Card from = plating.getEquipping();
        move(plating, elsewhere); // the Equip's first of the turn
        AssertJUnit.assertTrue("the Equip has been used: a creature of earlier turns is closed for it",
                ActivationGuard.equipTargetClosed(ai, sa, from));
        AssertJUnit.assertFalse("and the rule has nothing to say about the other ability, aimed at that creature",
                ActivationGuard.equipTargetClosed(ai, attach, from));
        AssertJUnit.assertFalse("or at the creature the Plating is on",
                ActivationGuard.equipTargetClosed(ai, attach, elsewhere));
    }

    // ------------------------------------------------------------------------------------------ outside its remit

    @Test
    public void aSpellInHandIsNotJudged() {
        setUp();
        final Card bolt = addCardToZone("Lightning Bolt", ai, ZoneType.Hand);
        final SpellAbility sa = bolt.getFirstSpellAbility();
        sa.setActivatingPlayer(ai);
        AssertJUnit.assertFalse(ActivationGuard.isActivated(sa));
        AssertJUnit.assertNull(ActivationGuard.problem(ai, sa));
    }

    @Test
    public void anAbilityInHandIsNotJudged() {
        setUp();
        final Card firebeast = addCardToZone("Ashen Firebeast", ai, ZoneType.Hand);
        for (final SpellAbility sa : firebeast.getSpellAbilities()) {
            if (sa.isActivatedAbility()) {
                AssertJUnit.assertFalse("only battlefield abilities are judged", ActivationGuard.isActivated(sa));
            }
        }
    }

    @Test
    public void aNullAiOrAbilityIsHarmless() {
        setUp();
        AssertJUnit.assertNull(ActivationGuard.problem(null, null));
        AssertJUnit.assertNull(ActivationGuard.problem(ai, null));
        AssertJUnit.assertFalse(ActivationGuard.isActivated(null));
    }

    @Test(timeOut = 240_000)
    public void flatMcDoesNotTapOranRiefForNothing() {
        setUp();
        fillLibrary(ai, 20);
        fillLibrary(game.getPlayers().get(0), 20);
        addCardToZone("Oran-Rief, the Vastwood", ai, ZoneType.Battlefield);
        addCard("Hill Giant", ai);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN2, ai);
        game.getAction().checkStateEffects(true);
        final java.util.List<SpellAbility> chosen = ((PlayerControllerAi) ai.getController()).chooseSpellAbilityToPlay();
        final String play = chosen == null || chosen.isEmpty() ? null : chosen.get(0).getHostCard().getName();
        AssertJUnit.assertFalse("no green creature entered: the AI chose " + play, "Oran-Rief, the Vastwood".equals(play));
    }
}
