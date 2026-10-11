package forge.game.card;

import java.util.AbstractCollection;
import java.util.Collection;
import java.util.EnumMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import org.testng.AssertJUnit;
import org.testng.annotations.Test;

import com.google.common.collect.Lists;

import forge.ai.AITest;
import forge.card.CardStateName;
import forge.card.CardType;
import forge.game.Game;
import forge.game.keyword.Keyword;
import forge.game.player.Player;
import forge.game.replacement.ReplacementEffect;
import forge.game.replacement.ReplacementHandler;
import forge.game.staticability.StaticAbility;
import forge.game.trigger.Trigger;
import forge.game.trigger.TriggerHandler;
import forge.util.collect.FCollection;
import forge.util.collect.FCollectionView;

/**
 * Card keeps, for each of its three trait getters, the state last found to have none of those traits and the trait
 * version it was found at, and while the version has not moved answers "none" again without the rebuild. Each test
 * asks a getter first, so that its answer is kept, then changes one thing the rebuild reads, and asks again: the
 * change must be seen. Every trait a test adds is made before the first ask, so that only the change under test can
 * move the version in between.
 */
public class CardTraitMemoTest extends AITest {

    private static final String REPLACEMENT = "Event$ Moved | ValidCard$ Card.Self | Destination$ Battlefield"
            + " | ReplacementResult$ Updated | Description$ CARDNAME enters.";
    private static final String STATIC = "Mode$ Continuous | Affected$ Creature.YouCtrl | AddPower$ 1"
            + " | AddToughness$ 1 | Description$ Creatures you control get +1/+1.";
    private static final String TRIGGER = "Mode$ Attacks | ValidCard$ Card.Self"
            + " | TriggerDescription$ Whenever CARDNAME attacks, nothing happens.";

    private static <T> List<T> listOf(final FCollectionView<T> traits) {
        return Lists.newArrayList(traits);
    }

    /** Asks the three getters of a card that has none of the traits, so that each answer is kept. */
    private static void assertHasNoTraits(final Card c) {
        AssertJUnit.assertTrue(c.getReplacementEffects().isEmpty());
        AssertJUnit.assertTrue(c.knownToHaveNoReplacementEffects());
        AssertJUnit.assertTrue(c.getStaticAbilities().isEmpty());
        AssertJUnit.assertTrue(c.getTriggers().isEmpty());
    }

    @Test
    public void aTraitAddedToTheCardIsSeen() {
        Game game = initAndCreateGame();
        Player p = game.getPlayers().get(0);

        Card withReplacement = createCard("Grizzly Bears", p);
        ReplacementEffect re = ReplacementHandler.parseReplacement(REPLACEMENT, withReplacement, true);
        assertHasNoTraits(withReplacement);
        withReplacement.addReplacementEffect(re);
        AssertJUnit.assertFalse(withReplacement.knownToHaveNoReplacementEffects());
        AssertJUnit.assertEquals(List.of(re), listOf(withReplacement.getReplacementEffects()));

        Card withStatic = createCard("Grizzly Bears", p);
        StaticAbility st = StaticAbility.create(STATIC, withStatic, withStatic.getCurrentState(), true);
        assertHasNoTraits(withStatic);
        withStatic.addStaticAbility(st);
        AssertJUnit.assertEquals(List.of(st), listOf(withStatic.getStaticAbilities()));

        Card withTrigger = createCard("Grizzly Bears", p);
        Trigger tr = TriggerHandler.parseTrigger(TRIGGER, withTrigger, true);
        assertHasNoTraits(withTrigger);
        withTrigger.addTrigger(tr);
        AssertJUnit.assertEquals(List.of(tr), listOf(withTrigger.getTriggers()));
    }

    @Test
    public void traitsCopiedIntoTheStateAreSeen() {
        Game game = initAndCreateGame();
        Player p = game.getPlayers().get(0);

        // as copies of cards are made: the whole state, the trait lists among it
        Card pool = createCard("Breeding Pool", p);
        Card bears = createCard("Grizzly Bears", p);
        assertHasNoTraits(bears);
        bears.getCurrentState().copyFrom(pool.getCurrentState(), false);
        AssertJUnit.assertEquals(1, bears.getReplacementEffects().size());

        Card anthem = createCard("Glorious Anthem", p);
        bears = createCard("Grizzly Bears", p);
        assertHasNoTraits(bears);
        bears.getCurrentState().copyFrom(anthem.getCurrentState(), false);
        AssertJUnit.assertEquals(1, bears.getStaticAbilities().size());

        Card warden = createCard("Soul Warden", p);
        bears = createCard("Grizzly Bears", p);
        assertHasNoTraits(bears);
        bears.getCurrentState().copyFrom(warden.getCurrentState(), false);
        AssertJUnit.assertEquals(1, bears.getTriggers().size());
    }

    @Test
    public void traitsAddedFromAnotherStateAreSeen() {
        Game game = initAndCreateGame();
        Player p = game.getPlayers().get(0);

        // as a mutated permanent gets the abilities of the cards under it: only the trait lists are written
        Card pool = createCard("Breeding Pool", p);
        Card bears = createCard("Grizzly Bears", p);
        assertHasNoTraits(bears);
        bears.getCurrentState().addAbilitiesFrom(pool.getCurrentState(), false);
        AssertJUnit.assertEquals(1, bears.getReplacementEffects().size());

        Card anthem = createCard("Glorious Anthem", p);
        bears = createCard("Grizzly Bears", p);
        assertHasNoTraits(bears);
        bears.getCurrentState().addAbilitiesFrom(anthem.getCurrentState(), false);
        AssertJUnit.assertEquals(1, bears.getStaticAbilities().size());

        Card warden = createCard("Soul Warden", p);
        bears = createCard("Grizzly Bears", p);
        assertHasNoTraits(bears);
        bears.getCurrentState().addAbilitiesFrom(warden.getCurrentState(), false);
        AssertJUnit.assertEquals(1, bears.getTriggers().size());
    }

    @Test
    public void aSubtypeAddedToTheStateIsSeen() {
        Game game = initAndCreateGame();
        Player p = game.getPlayers().get(0);

        // a Saga enters with a lore counter, a replacement the rebuild makes when the type says Saga
        Card bears = createCard("Grizzly Bears", p);
        assertHasNoTraits(bears);
        bears.addType("Saga");
        AssertJUnit.assertTrue(bears.getType().isSaga());
        AssertJUnit.assertEquals(List.of(bears.getCurrentState().getSagaRep()), listOf(bears.getReplacementEffects()));
    }

    @Test
    public void aTypeChangeIsSeen() {
        Game game = initAndCreateGame();
        Player p = game.getPlayers().get(0);

        // a change by text writes no type itself: only the type with changes that updateTypes makes from it changes
        Card bears = createCard("Grizzly Bears", p);
        CardType saga = new CardType(List.of("Enchantment", "Saga"), false);
        assertHasNoTraits(bears);
        bears.addChangedCardTypesByText(saga, game.getNextTimestamp(), 0);
        AssertJUnit.assertTrue(bears.getType().isSaga());
        AssertJUnit.assertEquals(List.of(bears.getCurrentState().getSagaRep()), listOf(bears.getReplacementEffects()));
    }

    @Test
    public void creatureTypesSetWithoutRemovingAnyAreSeen() {
        Game game = initAndCreateGame();
        Player p = game.getPlayers().get(0);

        // a creature without a creature type
        Card bears = createCard("Grizzly Bears", p);
        bears.setCreatureTypes(List.of());
        AssertJUnit.assertTrue(bears.getType().getCreatureTypes().isEmpty());
        assertHasNoTraits(bears);
        // nothing to remove, so CardType.setCreatureTypes adds the subtype and reports no change, and the state's
        // updateTypes does not run
        bears.setCreatureTypes(List.of("Saga"));
        AssertJUnit.assertTrue(bears.getType().isSaga());
        AssertJUnit.assertEquals(List.of(bears.getCurrentState().getSagaRep()), listOf(bears.getReplacementEffects()));
    }

    @Test
    public void creatureTypesFromACollectionThatAsksWhileTheyAreSetAreSeen() {
        Game game = initAndCreateGame();
        Player p = game.getPlayers().get(0);

        Card bears = createCard("Grizzly Bears", p);
        bears.setCreatureTypes(List.of());
        assertHasNoTraits(bears);
        // a collection whose iterator asks for the card's replacement effects before it yields "Saga": that rebuild
        // runs in the middle of the write, still finds no Saga, and its answer must not outlive the write
        Collection<String> asking = new AbstractCollection<String>() {
            @Override
            public Iterator<String> iterator() {
                AssertJUnit.assertTrue(bears.getReplacementEffects().isEmpty());
                return List.of("Saga").iterator();
            }

            @Override
            public int size() {
                return 1;
            }
        };
        bears.setCreatureTypes(asking);
        AssertJUnit.assertTrue(bears.getType().isSaga());
        AssertJUnit.assertEquals(List.of(bears.getCurrentState().getSagaRep()), listOf(bears.getReplacementEffects()));
    }

    @Test
    public void aLandsOwnAbilityReturnsWhenBloodMoonLeaves() {
        Game game = initAndCreateGame();
        Player p = game.getPlayers().get(0);

        Card urborg = addCard("Urborg, Tomb of Yawgmoth", p);
        Card bloodMoon = addCard("Blood Moon", p);
        game.getAction().checkStaticAbilities();
        // CR 305.7: as a Mountain it loses the abilities of its rules text
        AssertJUnit.assertTrue(urborg.getType().hasSubtype("Mountain"));
        AssertJUnit.assertTrue(urborg.getStaticAbilities().isEmpty());
        AssertJUnit.assertTrue(urborg.getStaticAbilities().isEmpty());

        game.getAction().moveToGraveyard(bloodMoon, null);
        game.getAction().checkStaticAbilities();
        AssertJUnit.assertFalse(urborg.getType().hasSubtype("Mountain"));
        AssertJUnit.assertEquals(1, urborg.getStaticAbilities().size());
    }

    @Test
    public void keywordsInstalledAfterTheAnswerAreSeen() {
        Game game = initAndCreateGame();
        Player p = game.getPlayers().get(0);

        // a keyword change made without updating the view reaches the traits only when the keywords are installed
        Card riot = createCard("Grizzly Bears", p);
        riot.addChangedCardKeywords(List.of("Riot"), null, false, game.getNextTimestamp(), null, false);
        assertHasNoTraits(riot);
        riot.updateKeywordsCache();
        AssertJUnit.assertTrue(riot.hasKeyword(Keyword.RIOT));
        AssertJUnit.assertEquals(1, riot.getReplacementEffects().size());

        Card defender = createCard("Grizzly Bears", p);
        defender.addChangedCardKeywords(List.of("Defender"), null, false, game.getNextTimestamp(), null, false);
        assertHasNoTraits(defender);
        defender.updateKeywordsCache();
        AssertJUnit.assertEquals(1, defender.getStaticAbilities().size());

        Card exalted = createCard("Grizzly Bears", p);
        exalted.addChangedCardKeywords(List.of("Exalted"), null, false, game.getNextTimestamp(), null, false);
        assertHasNoTraits(exalted);
        exalted.updateKeywordsCache();
        AssertJUnit.assertEquals(1, exalted.getTriggers().size());
    }

    @Test
    public void readAheadLeavingIsSeen() {
        Game game = initAndCreateGame();
        Player p = game.getPlayers().get(0);

        Card saga = createCard("History of Benalia", p);
        CardState state = saga.getCurrentState();
        AssertJUnit.assertEquals(List.of(state.getSagaRep()), listOf(saga.getReplacementEffects()));
        // Read ahead, here without traits of its own, takes the lore counter replacement away
        state.addIntrinsicKeyword("Read ahead", false);
        saga.updateKeywordsCache();
        AssertJUnit.assertTrue(saga.getReplacementEffects().isEmpty());
        AssertJUnit.assertTrue(saga.knownToHaveNoReplacementEffects());
        state.removeIntrinsicKeyword(Keyword.READ_AHEAD);
        saga.updateKeywordsCache();
        AssertJUnit.assertEquals(List.of(state.getSagaRep()), listOf(saga.getReplacementEffects()));
    }

    @Test
    public void traitsMadeForInstalledKeywordsAreSeen() {
        Game game = initAndCreateGame();
        Player p = game.getPlayers().get(0);

        // as CardFactory builds a card: the keywords are installed first, their traits made after
        Card riot = createCard("Grizzly Bears", p);
        riot.getCurrentState().addIntrinsicKeyword("Riot", false);
        riot.updateKeywordsCache();
        assertHasNoTraits(riot);
        CardFactoryUtil.setupKeywordedAbilities(riot);
        AssertJUnit.assertEquals(1, riot.getReplacementEffects().size());

        Card defender = createCard("Grizzly Bears", p);
        defender.getCurrentState().addIntrinsicKeyword("Defender", false);
        defender.updateKeywordsCache();
        assertHasNoTraits(defender);
        CardFactoryUtil.setupKeywordedAbilities(defender);
        AssertJUnit.assertEquals(1, defender.getStaticAbilities().size());

        Card exalted = createCard("Grizzly Bears", p);
        exalted.getCurrentState().addIntrinsicKeyword("Exalted", false);
        exalted.updateKeywordsCache();
        assertHasNoTraits(exalted);
        CardFactoryUtil.setupKeywordedAbilities(exalted);
        AssertJUnit.assertEquals(1, exalted.getTriggers().size());
    }

    @Test
    public void countersThatBringReplacementsAreSeen() {
        Game game = initAndCreateGame();
        Player p = game.getPlayers().get(0);

        Card shielded = createCard("Grizzly Bears", p);
        assertHasNoTraits(shielded);
        shielded.setCounters(CounterEnumType.SHIELD, 1);
        AssertJUnit.assertFalse(shielded.knownToHaveNoReplacementEffects());
        // a shield counter prevents damage and stops destruction
        AssertJUnit.assertEquals(2, shielded.getReplacementEffects().size());

        Card stunned = createCard("Grizzly Bears", p);
        assertHasNoTraits(stunned);
        stunned.setCounters(CounterEnumType.STUN, 1);
        AssertJUnit.assertEquals(1, stunned.getReplacementEffects().size());

        Card finality = createCard("Grizzly Bears", p);
        assertHasNoTraits(finality);
        finality.setCounters(CounterEnumType.FINALITY, 1);
        AssertJUnit.assertEquals(1, finality.getReplacementEffects().size());
    }

    @Test
    public void splitStatesGivenToTheCardAreSeen() {
        Game game = initAndCreateGame();
        Player p = game.getPlayers().get(0);

        // the same Original state with a left half holding one trait of each kind
        Card bears = createCard("Grizzly Bears", p);
        CardState left = new CardState(bears, CardStateName.LeftSplit);
        ReplacementEffect re = ReplacementHandler.parseReplacement(REPLACEMENT, bears, true);
        StaticAbility st = StaticAbility.create(STATIC, bears, left, true);
        Trigger tr = TriggerHandler.parseTrigger(TRIGGER, bears, true);
        left.addReplacementEffect(re);
        left.addStaticAbility(st);
        left.addTrigger(tr);
        Map<CardStateName, CardState> states = new EnumMap<>(CardStateName.class);
        states.put(CardStateName.Original, bears.getCurrentState());
        states.put(CardStateName.LeftSplit, left);
        assertHasNoTraits(bears);

        bears.setStates(states);
        AssertJUnit.assertSame(states.get(CardStateName.Original), bears.getCurrentState());
        AssertJUnit.assertFalse(bears.knownToHaveNoReplacementEffects());
        AssertJUnit.assertEquals(List.of(re), listOf(bears.getReplacementEffects()));
        AssertJUnit.assertEquals(List.of(st), listOf(bears.getStaticAbilities()));
        AssertJUnit.assertEquals(List.of(tr), listOf(bears.getTriggers()));
    }

    @Test
    public void changedTraitsAreSeen() {
        Game game = initAndCreateGame();
        Player p = game.getPlayers().get(0);

        Card bears = createCard("Grizzly Bears", p);
        ReplacementEffect re = ReplacementHandler.parseReplacement(REPLACEMENT, bears, false);
        StaticAbility st = StaticAbility.create(STATIC, bears, bears.getCurrentState(), false);
        Trigger tr = TriggerHandler.parseTrigger(TRIGGER, bears, false);
        assertHasNoTraits(bears);

        bears.addChangedCardTraits(null, List.of(tr), List.of(re), List.of(st), null, game.getNextTimestamp(), 0);
        AssertJUnit.assertFalse(bears.knownToHaveNoReplacementEffects());
        AssertJUnit.assertEquals(List.of(re), listOf(bears.getReplacementEffects()));
        AssertJUnit.assertEquals(List.of(st), listOf(bears.getStaticAbilities()));
        AssertJUnit.assertEquals(List.of(tr), listOf(bears.getTriggers()));
    }

    @Test
    public void theOtherFaceHasItsOwnTraits() {
        Game game = initAndCreateGame();
        Player p = game.getPlayers().get(0);

        Card jace = createCard("Jace, Vryn's Prodigy", p);
        AssertJUnit.assertTrue(jace.getReplacementEffects().isEmpty());
        AssertJUnit.assertTrue(jace.knownToHaveNoReplacementEffects());
        // the back face is a planeswalker, which enters with its loyalty
        jace.setState(CardStateName.Backside, true);
        AssertJUnit.assertFalse(jace.knownToHaveNoReplacementEffects());
        AssertJUnit.assertEquals(List.of(jace.getCurrentState().getLoyaltyRep()), listOf(jace.getReplacementEffects()));
    }

    @Test
    public void aStateReadingAnotherStatesTypeKeepsNoAnswer() {
        Game game = initAndCreateGame();
        Player p = game.getPlayers().get(0);

        // copied with last known information, a state takes its source's type with changes as it is: with no type
        // changes, the source's own type, which only the source's writers change
        Card bears = createCard("Grizzly Bears", p);
        Card other = createCard("Grizzly Bears", p);
        other.getCurrentState().copyFrom(bears.getCurrentState(), true);
        AssertJUnit.assertTrue(other.getReplacementEffects().isEmpty());
        AssertJUnit.assertFalse(other.knownToHaveNoReplacementEffects());

        bears.addType("Saga");
        AssertJUnit.assertTrue(other.getType().isSaga());
        AssertJUnit.assertEquals(List.of(other.getCurrentState().getSagaRep()), listOf(other.getReplacementEffects()));
    }

    @Test
    public void theReplacementScanSeesAReplacementAddedAfterItFoundNone() {
        Game game = initAndCreateGame();
        Player p = game.getPlayers().get(0);

        Card bears = addCard("Grizzly Bears", p);
        ReplacementEffect cantTap = ReplacementHandler.parseReplacement("Event$ Tap | ValidCard$ Card.Self"
                + " | Layer$ CantHappen | Description$ CARDNAME can't become tapped.", bears, true);
        // the scan rebuilds the card's replacement effects, finds none and keeps that, then skips the card
        AssertJUnit.assertTrue(bears.canTap());
        AssertJUnit.assertTrue(bears.knownToHaveNoReplacementEffects());
        AssertJUnit.assertTrue(bears.canTap());

        bears.addReplacementEffect(cantTap);
        AssertJUnit.assertFalse(bears.knownToHaveNoReplacementEffects());
        AssertJUnit.assertFalse(bears.canTap());

        // a card that has a replacement effect is never known to have none
        Card pool = createCard("Breeding Pool", p);
        AssertJUnit.assertFalse(pool.knownToHaveNoReplacementEffects());
        AssertJUnit.assertEquals(1, pool.getReplacementEffects().size());
        AssertJUnit.assertFalse(pool.knownToHaveNoReplacementEffects());
    }

    @Test
    public void aKeptAnswerIsANewEmptyCollection() {
        Game game = initAndCreateGame();
        Player p = game.getPlayers().get(0);

        // what the rebuild returns when it finds none: a new collection of its own, which the caller may fill
        Card bears = createCard("Grizzly Bears", p);
        Card donor = createCard("Grizzly Bears", p);
        ReplacementEffect re = ReplacementHandler.parseReplacement(REPLACEMENT, donor, true);
        StaticAbility st = StaticAbility.create(STATIC, donor, donor.getCurrentState(), true);
        Trigger tr = TriggerHandler.parseTrigger(TRIGGER, donor, true);
        assertHasNoTraits(bears);

        FCollectionView<ReplacementEffect> replacements = bears.getReplacementEffects();
        AssertJUnit.assertEquals(FCollection.class, replacements.getClass());
        AssertJUnit.assertNotSame(replacements, bears.getReplacementEffects());
        AssertJUnit.assertTrue(replacements.add(re));
        AssertJUnit.assertEquals(List.of(re), listOf(replacements));
        AssertJUnit.assertTrue(bears.getReplacementEffects().isEmpty());

        FCollectionView<StaticAbility> statics = bears.getStaticAbilities();
        AssertJUnit.assertEquals(FCollection.class, statics.getClass());
        AssertJUnit.assertNotSame(statics, bears.getStaticAbilities());
        AssertJUnit.assertTrue(statics.add(st));
        AssertJUnit.assertEquals(List.of(st), listOf(statics));
        AssertJUnit.assertTrue(bears.getStaticAbilities().isEmpty());

        FCollectionView<Trigger> triggers = bears.getTriggers();
        AssertJUnit.assertEquals(FCollection.class, triggers.getClass());
        AssertJUnit.assertNotSame(triggers, bears.getTriggers());
        AssertJUnit.assertTrue(triggers.add(tr));
        AssertJUnit.assertEquals(List.of(tr), listOf(triggers));
        AssertJUnit.assertTrue(bears.getTriggers().isEmpty());
    }
}
