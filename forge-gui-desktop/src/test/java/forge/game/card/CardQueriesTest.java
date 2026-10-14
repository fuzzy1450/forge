package forge.game.card;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

import org.apache.commons.lang3.tuple.Pair;
import org.testng.AssertJUnit;
import org.testng.annotations.Test;

import com.google.common.collect.Lists;

import forge.ai.AITest;
import forge.card.CardStateName;
import forge.game.Game;
import forge.game.ability.AbilityFactory;
import forge.game.ability.ApiType;
import forge.game.keyword.KeywordCollection;
import forge.game.keyword.KeywordInterface;
import forge.game.player.Player;
import forge.game.replacement.ReplacementEffect;
import forge.game.replacement.ReplacementHandler;
import forge.game.spellability.SpellAbility;
import forge.game.spellability.SpellPermanent;
import forge.game.staticability.StaticAbility;
import forge.game.trigger.Trigger;
import forge.game.trigger.TriggerHandler;
import forge.game.zone.ZoneType;
import forge.util.collect.FCollection;
import forge.util.collect.FCollectionView;

/**
 * A card answers for its spell abilities, keywords, power and toughness and hidden static abilities, and hashes its
 * traits, as it did before these queries skipped the work that finds nothing and built each state's spell abilities
 * once for both of its lists. Each test asks the query and the computation it stands for, through the card's other
 * public methods, and requires the same answer.
 */
public class CardQueriesTest extends AITest {

    private static final String REPLACEMENT = "Event$ Moved | ValidCard$ Card.Self | Destination$ Battlefield"
            + " | ReplacementResult$ Updated | Description$ CARDNAME enters.";
    private static final String STATIC = "Mode$ Continuous | Affected$ Creature.YouCtrl | AddPower$ 1"
            + " | AddToughness$ 1 | Description$ Creatures you control get +1/+1.";
    private static final String TRIGGER = "Mode$ Attacks | ValidCard$ Card.Self"
            + " | TriggerDescription$ Whenever CARDNAME attacks, nothing happens.";

    private static final List<String> CARDS = List.of("Forest", "Breeding Pool", "Mutavault", "Dryad Arbor",
            "Llanowar Elves", "Chromatic Sphere", "Pacifism", "Arbiter of Woe", "Bridge from Below", "Fire // Ice",
            "Delver of Secrets", "Bonecrusher Giant", "Jace, Vryn's Prodigy", "Akroma, Angel of Wrath");

    // ---- each state's spell abilities, built once ----

    /** What getAllSpellAbilities is: each state's non-mana abilities and then its mana abilities, from a build each. */
    private static List<SpellAbility> fromTwoBuilds(final Card c) {
        final FCollection<SpellAbility> res = new FCollection<>();
        for (final CardStateName key : c.getStates()) {
            res.addAll(c.getState(key).getNonManaAbilities());
            res.addAll(c.getState(key).getManaAbilities());
        }
        return Lists.newArrayList(res);
    }

    private static void assertAllSpellAbilitiesAreFromTwoBuilds(final Card c) {
        // asked first, so that the abilities a state makes when first asked are made by the query itself
        final List<SpellAbility> all = Lists.newArrayList(c.getAllSpellAbilities());
        final List<SpellAbility> expected = fromTwoBuilds(c);
        AssertJUnit.assertEquals(c + ": " + expected + " / " + all, expected.size(), all.size());
        for (int i = 0; i < all.size(); i++) {
            AssertJUnit.assertSame(c + " at " + i, expected.get(i), all.get(i));
        }
    }

    /** The next id of each kind of trait: a spell ability, a trigger, a static ability, a replacement effect. */
    private static int[] nextIds(final Card host) {
        return new int[] {new SpellAbility.EmptySa(host).getId(),
                TriggerHandler.parseTrigger(TRIGGER, host, true).getId(),
                StaticAbility.create(STATIC, host, host.getCurrentState(), true).getId(),
                ReplacementHandler.parseReplacement(REPLACEMENT, host, true).getId()};
    }

    /** How many ids of each kind a call takes, with the measuring's own share in it. */
    private static List<Integer> idsTakenBy(final Card host, final Runnable call) {
        final int[] before = nextIds(host);
        call.run();
        final int[] after = nextIds(host);
        final List<Integer> taken = new ArrayList<>();
        for (int i = 0; i < before.length; i++) {
            taken.add(after[i] - before[i]);
        }
        return taken;
    }

    @Test
    public void allSpellAbilitiesAreEachStatesNonManaThenManaAbilities() {
        Game game = initAndCreateGame();
        Player p = game.getPlayers().get(0);
        for (final String name : CARDS) {
            assertAllSpellAbilitiesAreFromTwoBuilds(addCardToZone(name, p, ZoneType.Hand));
            assertAllSpellAbilitiesAreFromTwoBuilds(addCard(name, p));
        }
        // a land with a mana ability scripted before its other ability: the mana ability still comes last
        final Card mutavault = createCard("Mutavault", p);
        final List<SpellAbility> all = Lists.newArrayList(mutavault.getAllSpellAbilities());
        AssertJUnit.assertTrue(all.get(0).isLandAbility());
        AssertJUnit.assertEquals(ApiType.Animate, all.get(1).getApi());
        AssertJUnit.assertTrue(all.get(2).isManaAbility());
        AssertJUnit.assertEquals(3, all.size());
    }

    @Test
    public void allSpellAbilitiesFollowChangedTraitsAndCloneStates() {
        Game game = initAndCreateGame();
        Player p = game.getPlayers().get(0);

        // Blood Moon takes the lands' own abilities and land types and gives them a Mountain's
        final Card pool = addCard("Breeding Pool", p);
        final Card mutavault = addCard("Mutavault", p);
        addCard("Blood Moon", p);
        game.getAction().checkStaticAbilities();
        assertAllSpellAbilitiesAreFromTwoBuilds(pool);
        assertAllSpellAbilitiesAreFromTwoBuilds(mutavault);

        // a double-faced card cloning a one-faced card: its back face is made as a clone state when first asked
        final Card elves = addCard("Llanowar Elves", p);
        final Card delver = addCard("Delver of Secrets", p);
        delver.addCloneState(CardFactory.getCloneStates(elves, delver, elves.getFirstSpellAbility()),
                game.getNextTimestamp());
        assertAllSpellAbilitiesAreFromTwoBuilds(delver);

        final Card split = addCard("Grizzly Bears", p);
        final Card fireIce = addCardToZone("Fire // Ice", p, ZoneType.Hand);
        split.addCloneState(CardFactory.getCloneStates(fireIce, split, fireIce.getFirstSpellAbility()),
                game.getNextTimestamp());
        assertAllSpellAbilitiesAreFromTwoBuilds(split);
    }

    @Test
    public void theFirstAskMakesWhatTwoBuildsMade() {
        Game game = initAndCreateGame();
        Player p = game.getPlayers().get(0);
        final Card host = createCard("Grizzly Bears", p);
        final List<Integer> nothing = idsTakenBy(host, () -> { });
        for (final String name : CARDS) {
            final Card once = addCard(name, p);
            final Card twice = addCard(name, p);
            final List<Integer> byOneBuild = idsTakenBy(host, once::getAllSpellAbilities);
            final List<Integer> byTwoBuilds = idsTakenBy(host, () -> fromTwoBuilds(twice));
            AssertJUnit.assertEquals(name, byTwoBuilds, byOneBuild);
            // and once made, nothing more
            AssertJUnit.assertEquals(name, nothing, idsTakenBy(host, once::getAllSpellAbilities));
            AssertJUnit.assertEquals(name, nothing, idsTakenBy(host, () -> fromTwoBuilds(once)));
        }

        // the clone state made for the back face takes its ids when first asked, as before
        final Card elves = addCard("Llanowar Elves", p);
        final Card first = addCard("Delver of Secrets", p);
        final Card second = addCard("Delver of Secrets", p);
        first.addCloneState(CardFactory.getCloneStates(elves, first, elves.getFirstSpellAbility()),
                game.getNextTimestamp());
        second.addCloneState(CardFactory.getCloneStates(elves, second, elves.getFirstSpellAbility()),
                game.getNextTimestamp());
        AssertJUnit.assertEquals(idsTakenBy(host, () -> fromTwoBuilds(second)),
                idsTakenBy(host, first::getAllSpellAbilities));
    }

    // ---- the permanent spell and the land types the rebuild asks about ----

    private static List<SpellAbility> spells(final Card c) {
        final List<SpellAbility> spells = new ArrayList<>();
        for (final SpellAbility sa : c.getSpellAbilities()) {
            if (sa.isSpell()) {
                spells.add(sa);
            }
        }
        return spells;
    }

    @Test
    public void aPermanentSpellOfTheCardsOwnStandsForTheGenericOne() {
        Game game = initAndCreateGame();
        Player p = game.getPlayers().get(0);

        final List<SpellAbility> arbiter = spells(createCard("Arbiter of Woe", p));
        AssertJUnit.assertEquals(1, arbiter.size());
        AssertJUnit.assertFalse(arbiter.get(0) instanceof SpellPermanent);
        AssertJUnit.assertEquals(ApiType.PermanentCreature, arbiter.get(0).getApi());

        final List<SpellAbility> bridge = spells(createCard("Bridge from Below", p));
        AssertJUnit.assertEquals(1, bridge.size());
        AssertJUnit.assertFalse(bridge.get(0) instanceof SpellPermanent);
        AssertJUnit.assertEquals(ApiType.PermanentNoncreature, bridge.get(0).getApi());

        final List<SpellAbility> bears = spells(createCard("Grizzly Bears", p));
        AssertJUnit.assertEquals(1, bears.size());
        AssertJUnit.assertTrue(bears.get(0) instanceof SpellPermanent);
    }

    /** The mana each of a card's mana abilities makes, in their order. */
    private static List<String> produced(final Card c) {
        final List<String> produced = new ArrayList<>();
        for (final SpellAbility sa : c.getManaAbilities()) {
            produced.add(sa.getManaPart().getOrigProduced());
        }
        return produced;
    }

    @Test
    public void landsMakeTheManaOfTheirBasicLandTypes() {
        Game game = initAndCreateGame();
        Player p = game.getPlayers().get(0);
        final Card pool = addCard("Breeding Pool", p);
        final Card arbor = addCard("Dryad Arbor", p);
        final Card forest = addCard("Forest", p);
        final Card mutavault = addCard("Mutavault", p);
        game.getAction().checkStaticAbilities();
        AssertJUnit.assertEquals(List.of("U", "G"), produced(pool));
        AssertJUnit.assertEquals(List.of("G"), produced(arbor));
        AssertJUnit.assertEquals(List.of("G"), produced(forest));
        AssertJUnit.assertEquals(List.of("C"), produced(mutavault));

        // Urborg makes each land a Swamp as well
        addCard("Urborg, Tomb of Yawgmoth", p);
        game.getAction().checkStaticAbilities();
        AssertJUnit.assertEquals(List.of("U", "B", "G"), produced(pool));
        AssertJUnit.assertEquals(List.of("B", "G"), produced(arbor));
        AssertJUnit.assertEquals(List.of("B", "G"), produced(forest));
        AssertJUnit.assertEquals(List.of("C", "B"), produced(mutavault));
    }

    @Test
    public void landsUnderBloodMoonMakeRedMana() {
        Game game = initAndCreateGame();
        Player p = game.getPlayers().get(0);
        final Card pool = addCard("Breeding Pool", p);
        final Card forest = addCard("Forest", p);
        addCard("Blood Moon", p);
        game.getAction().checkStaticAbilities();
        AssertJUnit.assertEquals(List.of("R"), produced(pool));
        AssertJUnit.assertEquals(List.of("G"), produced(forest));
    }

    // ---- trait hash codes ----

    /** By class NAME, not Class object: a Class's hashCode is an identity hash that changes from one JVM launch to
     *  the next, so hash sets of traits would iterate in a per-JVM order and one seed could play different games. */
    @Test
    public void traitHashCodesAreObjectsHashOfTheirClassNameAndId() {
        Game game = initAndCreateGame();
        Player p = game.getPlayers().get(0);
        final Card bears = createCard("Grizzly Bears", p);
        final List<SpellAbility> abilities = new ArrayList<>();
        abilities.add(bears.getFirstSpellAbility());
        abilities.add(AbilityFactory.getAbility("AB$ Pump | Cost$ 1 | Defined$ Self | NumAtt$ +1", bears));
        abilities.add(new SpellAbility.EmptySa(bears));
        abilities.addAll(createCard("Forest", p).getAllSpellAbilities());
        for (final SpellAbility sa : abilities) {
            AssertJUnit.assertEquals(sa.toString(), Objects.hash(SpellAbility.class.getName(), sa.getId()), sa.hashCode());
        }
        for (int i = 0; i < 3; i++) {
            final Trigger tr = TriggerHandler.parseTrigger(TRIGGER, bears, true);
            AssertJUnit.assertEquals(Objects.hash(Trigger.class.getName(), tr.getId()), tr.hashCode());
            final StaticAbility st = StaticAbility.create(STATIC, bears, bears.getCurrentState(), true);
            AssertJUnit.assertEquals(Objects.hash(StaticAbility.class.getName(), st.getId()), st.hashCode());
            final ReplacementEffect re = ReplacementHandler.parseReplacement(REPLACEMENT, bears, true);
            AssertJUnit.assertEquals(Objects.hash(ReplacementEffect.class.getName(), re.getId()), re.hashCode());
        }
    }

    // ---- keywords ----

    /** The keyword queries answer as a walk over the card's installed keywords would. */
    private static void assertKeywordQueries(final Card c) {
        final List<KeywordInterface> walked = Lists.newArrayList(c.getUnhiddenKeywords());
        final List<KeywordInterface> listed = c.getKeywords();
        AssertJUnit.assertEquals(c.toString(), walked.size(), listed.size());
        for (int i = 0; i < walked.size(); i++) {
            AssertJUnit.assertSame(c + " at " + i, walked.get(i), listed.get(i));
        }
        for (final KeywordInterface k : walked) {
            final String original = k.getOriginal();
            int count = 0;
            for (final KeywordInterface other : walked) {
                if (other.getOriginal().equals(original)) {
                    count++;
                }
            }
            AssertJUnit.assertEquals(original, count, c.getAmountOfKeyword(original));
            AssertJUnit.assertTrue(original, c.hasKeyword(original));
            AssertJUnit.assertTrue(original, c.hasStartOfKeyword(original.substring(0, 3)));
            AssertJUnit.assertTrue(original, c.hasStartOfUnHiddenKeyword(original.substring(0, 3)));
        }
        AssertJUnit.assertEquals(0, c.getAmountOfKeyword("Banding"));
        AssertJUnit.assertFalse(c.hasKeyword("Banding"));
        AssertJUnit.assertFalse(c.hasStartOfKeyword("Bandi"));
    }

    @Test
    public void keywordQueriesWalkTheInstalledKeywordsInOrder() {
        Game game = initAndCreateGame();
        Player p = game.getPlayers().get(0);
        final Card akroma = createCard("Akroma, Angel of Wrath", p);
        AssertJUnit.assertTrue(akroma.getKeywords().size() > 3);
        assertKeywordQueries(akroma);

        final long timestamp = game.getNextTimestamp();
        akroma.addChangedCardKeywords(List.of("Lifelink", "Exalted", "Exalted"), null, false, timestamp, null, false);
        akroma.updateKeywordsCache();
        AssertJUnit.assertEquals(2, akroma.getAmountOfKeyword("Exalted"));
        assertKeywordQueries(akroma);

        akroma.removeChangedCardKeywords(timestamp, 0, false);
        akroma.updateKeywordsCache();
        AssertJUnit.assertFalse(akroma.hasKeyword("Lifelink"));
        assertKeywordQueries(akroma);

        final Card bears = createCard("Grizzly Bears", p);
        AssertJUnit.assertTrue(bears.getKeywords().isEmpty());
        assertKeywordQueries(bears);
    }

    @Test
    public void aVisitStopsWhereTheVisitorSaysAndSeesEveryChange() {
        final KeywordCollection keywords = new KeywordCollection();
        keywords.add("Flying");
        keywords.add("Haste");
        keywords.add("Trample");
        final List<KeywordInterface> seen = new ArrayList<>();
        keywords.visit(k -> {
            seen.add(k);
            return seen.size() < 2;
        });
        AssertJUnit.assertEquals(Lists.newArrayList(keywords).subList(0, 2), seen);
        assertVisitsInOrder(keywords);

        keywords.add("Vigilance");
        assertVisitsInOrder(keywords);
        keywords.remove("Haste");
        assertVisitsInOrder(keywords);
        AssertJUnit.assertEquals(3, Lists.newArrayList(keywords).size());
        keywords.clear();
        assertVisitsInOrder(keywords);
        new KeywordCollection().visit(k -> {
            throw new AssertionError("an empty collection has nothing to visit");
        });
    }

    private static void assertVisitsInOrder(final KeywordCollection keywords) {
        final List<KeywordInterface> seen = new ArrayList<>();
        keywords.visit(k -> {
            seen.add(k);
            return true;
        });
        AssertJUnit.assertEquals(Lists.newArrayList(keywords), seen);
    }

    // ---- hidden static abilities ----

    @Test
    public void hiddenStaticAbilitiesComeFromSuspicionAndKeywordCounters() {
        Game game = initAndCreateGame();
        Player p = game.getPlayers().get(0);
        final Card bears = addCard("Grizzly Bears", p);

        // none: a new collection each time, which its caller may change
        final FCollectionView<StaticAbility> first = bears.getHiddenStaticAbilities();
        final FCollectionView<StaticAbility> second = bears.getHiddenStaticAbilities();
        AssertJUnit.assertTrue(first.isEmpty());
        AssertJUnit.assertTrue(second.isEmpty());
        AssertJUnit.assertNotSame(first, second);
        AssertJUnit.assertNotSame(FCollection.getEmpty(), first);
        final StaticAbility other = StaticAbility.create(STATIC, bears, bears.getCurrentState(), true);
        AssertJUnit.assertTrue(((FCollection<StaticAbility>) first).add(other));
        AssertJUnit.assertTrue(second.isEmpty());
        AssertJUnit.assertTrue(bears.getHiddenStaticAbilities().isEmpty());

        // suspected while in play
        bears.setSuspected(true);
        AssertJUnit.assertEquals(List.of(bears.getSuspectedStatic()),
                Lists.newArrayList(bears.getHiddenStaticAbilities()));

        // and with a keyword counter, its static too, while the counter is there
        final CounterType flying = CounterType.getType("Flying");
        bears.addCounterInternal(flying, 1, p, false, null, null);
        final List<StaticAbility> both = Lists.newArrayList(bears.getHiddenStaticAbilities());
        AssertJUnit.assertEquals(2, both.size());
        AssertJUnit.assertSame(bears.getSuspectedStatic(), both.get(0));
        bears.subtractCounter(flying, 1, p);
        AssertJUnit.assertEquals(List.of(bears.getSuspectedStatic()),
                Lists.newArrayList(bears.getHiddenStaticAbilities()));
        bears.setSuspected(false);
        AssertJUnit.assertTrue(bears.getHiddenStaticAbilities().isEmpty());

        // a suspected card out of play has none
        final Card inHand = addCardToZone("Grizzly Bears", p, ZoneType.Hand);
        inHand.setSuspected(true);
        AssertJUnit.assertTrue(inHand.getHiddenStaticAbilities().isEmpty());
    }

    // ---- power and toughness ----

    private static void assertPowerAndToughness(final Card c, final int power, final int toughness) {
        AssertJUnit.assertEquals(power, c.getCurrentPower());
        AssertJUnit.assertEquals(toughness, c.getCurrentToughness());
        // as the walk over the set values finds them
        int walkedPower = c.getBasePower();
        int walkedToughness = c.getBaseToughness();
        for (final Pair<Integer, Integer> pt : c.getPTIterable()) {
            if (pt.getLeft() != null) {
                walkedPower = pt.getLeft();
            }
            if (pt.getRight() != null) {
                walkedToughness = pt.getRight();
            }
        }
        AssertJUnit.assertEquals(walkedPower, c.getCurrentPower());
        AssertJUnit.assertEquals(walkedToughness, c.getCurrentToughness());
    }

    @Test
    public void powerAndToughnessAreTheLastSetInEachLayer() {
        Game game = initAndCreateGame();
        Player p = game.getPlayers().get(0);
        final Card bears = createCard("Grizzly Bears", p);
        assertPowerAndToughness(bears, 2, 2);

        final long set = game.getNextTimestamp();
        bears.addNewPT(5, null, set, 0);
        assertPowerAndToughness(bears, 5, 2);
        bears.removeNewPT(set, 0);
        assertPowerAndToughness(bears, 2, 2);

        final long defining = game.getNextTimestamp();
        bears.addNewPT(null, 7, defining, 0, true, false);
        assertPowerAndToughness(bears, 2, 7);
        bears.removeNewPT(defining, 0);
        assertPowerAndToughness(bears, 2, 2);

        final long text = game.getNextTimestamp();
        bears.addNewPTByText(4, 3, text, 0);
        assertPowerAndToughness(bears, 4, 3);
        bears.removeNewPTbyText(text, 0);
        assertPowerAndToughness(bears, 2, 2);

        // text first, then characteristic-defining, then set
        bears.addNewPTByText(1, 1, game.getNextTimestamp(), 0);
        bears.addNewPT(3, null, game.getNextTimestamp(), 0, true, false);
        bears.addNewPT(null, 9, game.getNextTimestamp(), 0);
        assertPowerAndToughness(bears, 3, 9);
        AssertJUnit.assertTrue(bears.clearNewPT());
        assertPowerAndToughness(bears, 2, 2);

        for (final Card c : Arrays.asList(createCard("Llanowar Elves", p), createCard("Akroma, Angel of Wrath", p))) {
            assertPowerAndToughness(c, c.getBasePower(), c.getBaseToughness());
        }
    }
}
