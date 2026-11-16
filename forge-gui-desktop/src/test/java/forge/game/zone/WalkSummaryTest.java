package forge.game.zone;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import org.testng.AssertJUnit;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.Test;

import com.google.common.collect.LinkedHashMultiset;
import com.google.common.collect.Lists;
import com.google.common.collect.Multiset;

import forge.ai.AITest;
import forge.ai.simulation.GameCopier;
import forge.card.CardStateName;
import forge.game.Game;
import forge.game.ability.AbilityKey;
import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.card.CardCopyService;
import forge.game.card.CardState;
import forge.game.card.CounterEnumType;
import forge.game.card.CounterType;
import forge.game.player.Player;
import forge.game.replacement.ReplacementEffect;
import forge.game.replacement.ReplacementHandler;
import forge.game.replacement.ReplacementLayer;
import forge.game.replacement.ReplacementType;
import forge.game.undo.UndoManager;
import forge.util.Visitor;

/**
 * The replacement walk's zone summaries (Zone#summarizeWalk, Zone#visitSummarized): a summary passes the cards known to
 * have no replacement effect for the walk's event, the walk visits every other card exactly as forEachCardInGame
 * does, and every write to what a passed card's answer reads, to a zone's list or to the counters, ends the summary
 * before the next walk could pass a card it should visit.
 */
public class WalkSummaryTest extends AITest {

    private static final long UNTAP = 1L << ReplacementType.Untap.ordinal();
    private static final long MOVED = 1L << ReplacementType.Moved.ordinal();
    private static final long DAMAGE = 1L << ReplacementType.DamageDone.ordinal();

    private static final String CANT_UNTAP = "Event$ Untap | ValidCard$ Card.Self | Layer$ CantHappen"
            + " | Description$ CARDNAME doesn't untap.";
    private static final String NONE_UNTAP = "Event$ Untap | ValidCard$ Permanent | Layer$ CantHappen"
            + " | Description$ Permanents don't untap.";

    @AfterMethod
    public void probeOff() {
        WalkSummaryProbe.stop();
    }

    /** A card known to have no replacement effect (its effects were read). */
    private Card quiet(final Player p, final ZoneType zone) {
        Card c = addCardToZone("Grizzly Bears", p, zone);
        AssertJUnit.assertTrue(c.getReplacementEffects().isEmpty());
        AssertJUnit.assertEquals(0L, c.knownReplacementEvents());
        return c;
    }

    /** A card whose one effect, of {@code effect}, has been read. */
    private Card withEffect(final Player p, final ZoneType zone, final String effect) {
        Card c = addCardToZone("Grizzly Bears", p, zone);
        c.addReplacementEffect(ReplacementHandler.parseReplacement(effect, c, true));
        AssertJUnit.assertEquals(1, c.getReplacementEffects().size());
        return c;
    }

    /** Every card the walk calls visit for. */
    private static List<Card> visited(final Game game, final long events) {
        final List<Card> out = new ArrayList<>();
        game.forEachCardInGameSummarized(c -> {
            out.add(c);
            return true;
        }, events, null);
        return out;
    }

    /** The cards a visitor like the replacement walk's works on: those it does not return at once for. */
    private static List<Card> worked(final Game game, final long events, final boolean summarized) {
        final List<Card> out = new ArrayList<>();
        final Visitor<Card> v = c -> {
            if ((c.knownReplacementEvents() & events) != 0) {
                out.add(c);
            }
            return true;
        };
        if (summarized) {
            game.forEachCardInGameSummarized(v, events, null);
        } else {
            game.forEachCardInGame(v);
        }
        return out;
    }

    /** Walks twice: the first walk notes the zones, the second summarizes the unchanged ones. */
    private static void summarize(final Game game) {
        visited(game, UNTAP);
        visited(game, UNTAP);
    }

    private static void assertSameWork(final Game game) {
        for (long events : new long[] { UNTAP, MOVED, DAMAGE, -1L }) {
            AssertJUnit.assertEquals(worked(game, events, false), worked(game, events, true));
        }
    }

    @Test
    public void aSummaryPassesTheCardsKnownToHaveNoEffectOfTheEvent() {
        Game game = initAndCreateGame();
        Player p = game.getPlayers().get(0);
        Zone library = p.getZone(ZoneType.Library);
        List<Card> cards = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            cards.add(quiet(p, ZoneType.Library));
        }
        Card frozen = withEffect(p, ZoneType.Library, CANT_UNTAP);
        cards.add(3, frozen);
        library.reorder(frozen, 3);
        AssertJUnit.assertEquals(cards, List.copyOf(library.getCards()));

        // the first walk only notes the zone; nothing is passed
        AssertJUnit.assertEquals(cards, visited(game, UNTAP));
        AssertJUnit.assertNull(library.validWalkSummary());
        // the second, finding it unchanged, summarizes it first
        AssertJUnit.assertEquals(List.of(frozen), visited(game, UNTAP));
        Zone.WalkSummary s = library.validWalkSummary();
        AssertJUnit.assertNotNull(s);
        AssertJUnit.assertEquals(1, s.index.length);
        AssertJUnit.assertEquals(3, s.index[0]);
        AssertJUnit.assertEquals(UNTAP, s.events[0]);
        for (Card c : cards) {
            AssertJUnit.assertSame(library, c.getWalkSummaryZone());
        }
        // a walk for another event passes the card whose one effect is an Untap one; with every event, it is visited
        AssertJUnit.assertEquals(List.of(), visited(game, MOVED));
        AssertJUnit.assertEquals(List.of(frozen), visited(game, -1L));
        AssertJUnit.assertSame(s, library.validWalkSummary());
        assertSameWork(game);
    }

    @Test
    public void theReplacementWalkStillFindsWhatItShould() {
        Game game = initAndCreateGame();
        Player p = game.getPlayers().get(0);
        Zone battlefield = p.getZone(ZoneType.Battlefield);
        quiet(p, ZoneType.Battlefield);
        Card first = withEffect(p, ZoneType.Battlefield, NONE_UNTAP);
        quiet(p, ZoneType.Battlefield);
        Card second = withEffect(p, ZoneType.Battlefield, NONE_UNTAP);
        quiet(p, ZoneType.Battlefield);
        Map<AbilityKey, Object> params = AbilityKey.mapFromAffected(second);
        // a CantHappen effect stops the walk: only the first is found, every time
        for (int i = 0; i < 3; i++) {
            AssertJUnit.assertEquals(List.of(first.getReplacementEffects().getFirst()), game.getReplacementHandler()
                    .getReplacementList(ReplacementType.Untap, params, ReplacementLayer.CantHappen));
        }
        AssertJUnit.assertNotNull(battlefield.validWalkSummary());
        AssertJUnit.assertFalse(second.canUntap(p, true));
    }

    /** A battlefield of quiet cards and {@code target}, summarized, with target untapping. */
    private Card summarizedTarget(final Game game, final Player p) {
        quiet(p, ZoneType.Battlefield);
        Card target = quiet(p, ZoneType.Battlefield);
        quiet(p, ZoneType.Battlefield);
        AssertJUnit.assertTrue(target.canUntap(p, true));
        AssertJUnit.assertTrue(target.canUntap(p, true));
        AssertJUnit.assertNotNull(p.getZone(ZoneType.Battlefield).validWalkSummary());
        AssertJUnit.assertSame(p.getZone(ZoneType.Battlefield), target.getWalkSummaryZone());
        return target;
    }

    /** The write dropped target's summary, and the walk now finds target's CantHappen Untap effect. */
    private static void assertSeen(final Player p, final Card target) {
        AssertJUnit.assertNull(p.getZone(ZoneType.Battlefield).validWalkSummary());
        AssertJUnit.assertNull(target.getWalkSummaryZone());
        AssertJUnit.assertFalse(target.canUntap(p, true));
        AssertJUnit.assertFalse(target.canUntap(p, true));
        AssertJUnit.assertFalse(target.canUntap(p, true));
    }

    @Test
    public void anEffectAddedToAPassedCardIsSeen() {
        Game game = initAndCreateGame();
        Player p = game.getPlayers().get(0);
        Card target = summarizedTarget(game, p);
        // CardState.addReplacementEffect moves the trait version (Card#traitsChanged)
        target.addReplacementEffect(ReplacementHandler.parseReplacement(CANT_UNTAP, target, true));
        assertSeen(p, target);
    }

    @Test
    public void aStateSwitchIsSeen() {
        Game game = initAndCreateGame();
        Player p = game.getPlayers().get(0);
        Card target = addCardToZone("Grizzly Bears", p, ZoneType.Battlefield);
        target.addAlternateState(CardStateName.Flipped, false);
        target.getState(CardStateName.Flipped).addReplacementEffect(
                ReplacementHandler.parseReplacement(CANT_UNTAP, target, true));
        AssertJUnit.assertTrue(target.getReplacementEffects().isEmpty());
        quiet(p, ZoneType.Battlefield);
        AssertJUnit.assertTrue(target.canUntap(p, true));
        AssertJUnit.assertTrue(target.canUntap(p, true));
        AssertJUnit.assertSame(p.getZone(ZoneType.Battlefield), target.getWalkSummaryZone());
        target.setState(CardStateName.Flipped, true);
        assertSeen(p, target);
    }

    @Test
    public void aSplitStateIsSeen() {
        Game game = initAndCreateGame();
        Player p = game.getPlayers().get(0);
        Card target = summarizedTarget(game, p);
        CardState left = new CardState(target, CardStateName.LeftSplit);
        left.addReplacementEffect(ReplacementHandler.parseReplacement(CANT_UNTAP, target, true));
        // the left half was made for the card, which moved its version: summarize again
        AssertJUnit.assertTrue(target.canUntap(p, true));
        AssertJUnit.assertTrue(target.canUntap(p, true));
        AssertJUnit.assertSame(p.getZone(ZoneType.Battlefield), target.getWalkSummaryZone());
        Map<CardStateName, CardState> states = new EnumMap<>(CardStateName.class);
        states.put(CardStateName.Original, target.getCurrentState());
        states.put(CardStateName.LeftSplit, left);
        target.setStates(states);
        assertSeen(p, target);
    }

    @Test
    public void aGrantedEffectIsSeen() {
        Game game = initAndCreateGame();
        Player p = game.getPlayers().get(0);
        Card target = summarizedTarget(game, p);
        ReplacementEffect re = ReplacementHandler.parseReplacement(CANT_UNTAP, target, false);
        AssertJUnit.assertSame(p.getZone(ZoneType.Battlefield), target.getWalkSummaryZone());
        target.addChangedCardTraits(null, null, List.of(re), null, null, game.getNextTimestamp(), 0);
        assertSeen(p, target);
    }

    @Test
    public void installedKeywordsAreSeen() {
        Game game = initAndCreateGame();
        Player p = game.getPlayers().get(0);
        Zone battlefield = p.getZone(ZoneType.Battlefield);
        Card riot = quiet(p, ZoneType.Battlefield);
        riot.addChangedCardKeywords(List.of("Riot"), null, false, game.getNextTimestamp(), null, false);
        // the change reaches the traits only when the keywords are installed (CardState.setCachedKeywords)
        AssertJUnit.assertTrue(riot.getReplacementEffects().isEmpty());
        summarize(game);
        AssertJUnit.assertSame(battlefield, riot.getWalkSummaryZone());
        AssertJUnit.assertEquals(List.of(), worked(game, MOVED, true));
        riot.updateKeywordsCache();
        AssertJUnit.assertNull(battlefield.validWalkSummary());
        AssertJUnit.assertEquals(List.of(riot), worked(game, MOVED, true));
        AssertJUnit.assertEquals(1, riot.getReplacementEffects().size());
        summarize(game);
        AssertJUnit.assertEquals(List.of(riot), worked(game, MOVED, true));
        assertSameWork(game);
    }

    @Test
    public void aCounterOnAPassedCardIsSeen() {
        Game game = initAndCreateGame();
        Player p = game.getPlayers().get(0);
        Zone library = p.getZone(ZoneType.Library);
        quiet(p, ZoneType.Library);
        Card target = quiet(p, ZoneType.Library);
        summarize(game);
        AssertJUnit.assertSame(library, target.getWalkSummaryZone());
        AssertJUnit.assertEquals(List.of(), visited(game, UNTAP));
        // a +1/+1 counter brings no effect, but the card is no longer known to have none
        target.setCounters(CounterEnumType.P1P1, 1);
        AssertJUnit.assertNull(library.validWalkSummary());
        AssertJUnit.assertEquals(List.of(target), worked(game, UNTAP, true));
        summarize(game);
        AssertJUnit.assertEquals(List.of(target), visited(game, UNTAP));

        // on the battlefield a stun counter brings an Untap replacement the walk must find
        Card stunned = summarizedTarget(game, p);
        stunned.setCounters(CounterEnumType.STUN, 1);
        AssertJUnit.assertNull(p.getZone(ZoneType.Battlefield).validWalkSummary());
        for (int i = 0; i < 3; i++) {
            AssertJUnit.assertEquals(1, game.getReplacementHandler().getReplacementList(ReplacementType.Untap,
                    AbilityKey.mapFromAffected(stunned), ReplacementLayer.Other).size());
        }

        // and so does one that comes with a whole set of counters the card takes
        Card adopting = summarizedTarget(game, p);
        Multiset<CounterType> stun = LinkedHashMultiset.create();
        stun.add(CounterEnumType.STUN);
        adopting.setCounters(stun);
        AssertJUnit.assertNull(p.getZone(ZoneType.Battlefield).validWalkSummary());
        for (int i = 0; i < 3; i++) {
            AssertJUnit.assertEquals(1, game.getReplacementHandler().getReplacementList(ReplacementType.Untap,
                    AbilityKey.mapFromAffected(adopting), ReplacementLayer.Other).size());
        }
    }

    @Test
    public void aCounterPutThroughAnotherCardHoldingTheSameCountersIsSeen() {
        Game game = initAndCreateGame();
        Player p = game.getPlayers().get(0);
        Card target = summarizedTarget(game, p);
        // as ComputerUtilCard.getPumpedCreature does: the copy holds the card's own counters
        Card pumped = new CardCopyService(target).copyCard(false);
        pumped.setCounters(target.getCounters());
        AssertJUnit.assertSame(game, pumped.getGame());
        AssertJUnit.assertNotNull(p.getZone(ZoneType.Battlefield).validWalkSummary());
        AssertJUnit.assertSame(p.getZone(ZoneType.Battlefield), target.getWalkSummaryZone());
        long epoch = game.getWalkCounterEpoch();
        pumped.setCounters(CounterEnumType.STUN, 1);
        AssertJUnit.assertEquals(1, target.getCounters(CounterEnumType.STUN));
        AssertJUnit.assertEquals(epoch + 1, game.getWalkCounterEpoch());
        AssertJUnit.assertNull(p.getZone(ZoneType.Battlefield).validWalkSummary());
        for (int i = 0; i < 3; i++) {
            AssertJUnit.assertEquals(1, game.getReplacementHandler().getReplacementList(ReplacementType.Untap,
                    AbilityKey.mapFromAffected(target), ReplacementLayer.Other).size());
        }
    }

    @Test
    public void aCardOfAnotherGameIsNeverPassed() {
        Game game = initAndCreateGame();
        Game other = initAndCreateGame();
        Player p = game.getPlayers().get(0);
        Zone library = p.getZone(ZoneType.Library);
        quiet(p, ZoneType.Library);
        Card target = quiet(p, ZoneType.Library);
        summarize(game);
        AssertJUnit.assertSame(library, target.getWalkSummaryZone());
        target.dangerouslySetGame(other);
        AssertJUnit.assertNull(library.validWalkSummary());
        AssertJUnit.assertNull(target.getWalkSummaryZone());
        summarize(game);
        AssertJUnit.assertNotNull(library.validWalkSummary());
        AssertJUnit.assertNull(target.getWalkSummaryZone());
        AssertJUnit.assertEquals(List.of(target), visited(game, UNTAP));
        // back in its game it stays a card to visit until the summary is made again
        target.dangerouslySetGame(game);
        AssertJUnit.assertEquals(List.of(target), visited(game, UNTAP));
        assertSameWork(game);
    }

    @Test
    public void zoneWritesEndTheSummary() {
        Game game = initAndCreateGame();
        Player p = game.getPlayers().get(0);
        Zone library = p.getZone(ZoneType.Library);
        List<Card> cards = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            cards.add(i % 3 == 1 ? withEffect(p, ZoneType.Library, CANT_UNTAP) : quiet(p, ZoneType.Library));
        }
        List<Runnable> writes = List.of(
                () -> library.reorder(cards.get(0), 5),
                library::shuffle,
                () -> library.remove(cards.get(2)),
                () -> library.add(cards.get(2), 0),
                () -> library.setCards(new CardCollection(Lists.reverse(List.copyOf(library.getCards())))),
                () -> library.removeAllCards(true));
        for (Runnable write : writes) {
            summarize(game);
            Zone.WalkSummary s = library.validWalkSummary();
            AssertJUnit.assertNotNull(s);
            write.run();
            AssertJUnit.assertNull(library.validWalkSummary());
            assertSameWork(game);
            summarize(game);
            AssertJUnit.assertNotSame(s, library.validWalkSummary());
            assertSameWork(game);
        }
    }

    @Test
    public void aVisitThatChangesTheListIsFollowedAsThePlainLoopFollowsIt() {
        Game game = initAndCreateGame();
        Player p = game.getPlayers().get(0);
        Zone library = p.getZone(ZoneType.Library);
        quiet(p, ZoneType.Library);
        quiet(p, ZoneType.Library);
        Card f1 = withEffect(p, ZoneType.Library, CANT_UNTAP);
        quiet(p, ZoneType.Library);
        Card f2 = withEffect(p, ZoneType.Library, CANT_UNTAP);
        quiet(p, ZoneType.Library);
        Card f3 = withEffect(p, ZoneType.Hand, CANT_UNTAP);
        p.getZone(ZoneType.Hand).remove(f3);
        summarize(game);
        AssertJUnit.assertNotNull(library.validWalkSummary());
        // the visit of f1 puts f3 right after it: the plain loop visits f3 next, then f2
        final List<Card> out = new ArrayList<>();
        game.forEachCardInGameSummarized(c -> {
            if ((c.knownReplacementEvents() & UNTAP) != 0) {
                out.add(c);
            }
            if (c == f1) {
                library.add(f3, 3);
            }
            return true;
        }, UNTAP, null);
        AssertJUnit.assertEquals(List.of(f1, f3, f2), out);
    }

    @Test
    public void aStoppedWalkStopsAtTheSameCard() {
        Game game = initAndCreateGame();
        Player p = game.getPlayers().get(0);
        quiet(p, ZoneType.Library);
        Card f1 = withEffect(p, ZoneType.Library, CANT_UNTAP);
        quiet(p, ZoneType.Library);
        withEffect(p, ZoneType.Library, CANT_UNTAP);
        summarize(game);
        final List<Card> out = new ArrayList<>();
        game.forEachCardInGameSummarized(c -> {
            out.add(c);
            return c != f1;
        }, UNTAP, null);
        AssertJUnit.assertEquals(List.of(f1), out);
    }

    @Test
    public void aWalkStopsPassingWhenItsConditionEnds() {
        Game game = initAndCreateGame();
        Player p = game.getPlayers().get(0);
        Card f1 = withEffect(p, ZoneType.Library, CANT_UNTAP);
        Card q1 = quiet(p, ZoneType.Library);
        Card q2 = quiet(p, ZoneType.Library);
        summarize(game);
        AtomicBoolean usable = new AtomicBoolean(true);
        final List<Card> out = new ArrayList<>();
        game.forEachCardInGameSummarized(c -> {
            out.add(c);
            usable.set(false);
            return true;
        }, UNTAP, usable::get);
        AssertJUnit.assertEquals(List.of(f1, q1, q2), out);
    }

    @Test
    public void callsWithAPreListOrALastStateWalkEveryCard() {
        Game game = initAndCreateGame();
        Player p = game.getPlayers().get(0);
        Card frozen = withEffect(p, ZoneType.Battlefield, CANT_UNTAP);
        Card target = summarizedTarget(game, p);
        AssertJUnit.assertFalse(frozen.canUntap(p, true));
        AssertJUnit.assertSame(p.getZone(ZoneType.Battlefield), target.getWalkSummaryZone());
        // the card's last state, before the effect began, had a Moved replacement: with a last state the walk reads it
        Card lki = CardCopyService.getLKICopy(target);
        AssertJUnit.assertNull(lki.getWalkSummaryZone());
        AssertJUnit.assertSame(p.getZone(ZoneType.Battlefield), target.getWalkSummaryZone());
        AssertJUnit.assertNotNull(p.getZone(ZoneType.Battlefield).validWalkSummary());
        ReplacementEffect dies = ReplacementHandler.parseReplacement("Event$ Moved | ValidCard$ Card.Self"
                + " | Origin$ Battlefield | Destination$ Graveyard | Description$ If CARDNAME would die, nothing.",
                lki, true);
        lki.addReplacementEffect(dies);
        AssertJUnit.assertNotNull(p.getZone(ZoneType.Battlefield).validWalkSummary());
        Map<AbilityKey, Object> params = AbilityKey.mapFromAffected(target);
        params.put(AbilityKey.Origin, ZoneType.Battlefield);
        params.put(AbilityKey.Destination, ZoneType.Graveyard);
        params.put(AbilityKey.LastStateBattlefield, new CardCollection(lki));
        WalkSummaryProbe.start(false);
        AssertJUnit.assertEquals(List.of(dies), game.getReplacementHandler()
                .getReplacementList(ReplacementType.Moved, params, ReplacementLayer.Other));
        AssertJUnit.assertEquals(0L, WalkSummaryProbe.walks());
        // without one the walk is summarized
        AssertJUnit.assertTrue(target.canUntap(p, true));
        AssertJUnit.assertEquals(1L, WalkSummaryProbe.walks());
        AssertJUnit.assertEquals(0L, WalkSummaryProbe.failures());
    }

    @Test
    public void aCardInTwoListsIsVisitedInTheSecond() {
        Game game = initAndCreateGame();
        Player p = game.getPlayers().get(0);
        Zone library = p.getZone(ZoneType.Library);
        Zone hand = p.getZone(ZoneType.Hand);
        quiet(p, ZoneType.Hand);
        Card twice = quiet(p, ZoneType.Library);
        // the hand's list gets it too, while the library's still holds it; the walk takes the hand first
        hand.add(twice);
        summarize(game);
        AssertJUnit.assertSame(hand, twice.getWalkSummaryZone());
        Zone.WalkSummary l = library.validWalkSummary();
        AssertJUnit.assertNotNull(l);
        AssertJUnit.assertEquals(1, l.index.length);
        AssertJUnit.assertEquals(-1L, l.events[0]);
        AssertJUnit.assertEquals(List.of(twice), visited(game, UNTAP));
        // a write to it drops the hand's summary, which marks it; the library's never passed it
        twice.addReplacementEffect(ReplacementHandler.parseReplacement(CANT_UNTAP, twice, true));
        AssertJUnit.assertNull(hand.validWalkSummary());
        AssertJUnit.assertSame(l, library.validWalkSummary());
        AssertJUnit.assertEquals(List.of(twice, twice), worked(game, UNTAP, true));
        assertSameWork(game);
    }

    @Test
    public void copiesStartWithoutSummariesAndLeaveTheOriginalsAlone() {
        Game game = initAndCreateGame();
        Player p = game.getPlayers().get(0);
        Zone library = p.getZone(ZoneType.Library);
        List<Card> cards = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            cards.add(quiet(p, ZoneType.Library));
        }
        Card target = summarizedTarget(game, p);
        summarize(game);
        Zone.WalkSummary s = library.validWalkSummary();
        Zone.WalkSummary b = p.getZone(ZoneType.Battlefield).validWalkSummary();
        AssertJUnit.assertNotNull(s);
        AssertJUnit.assertNotNull(b);

        Game copy = new GameCopier(game).makeCopy();
        Player cp = copy.getPlayers().get(0);
        AssertJUnit.assertNull(cp.getZone(ZoneType.Library).validWalkSummary());
        AssertJUnit.assertNull(cp.getZone(ZoneType.Battlefield).validWalkSummary());
        for (Card c : cp.getZone(ZoneType.Library).getCards()) {
            AssertJUnit.assertNull(c.getWalkSummaryZone());
        }
        AssertJUnit.assertSame(s, library.validWalkSummary());
        AssertJUnit.assertSame(b, p.getZone(ZoneType.Battlefield).validWalkSummary());
        for (Card c : cards) {
            AssertJUnit.assertSame(library, c.getWalkSummaryZone());
        }
        AssertJUnit.assertSame(p.getZone(ZoneType.Battlefield), target.getWalkSummaryZone());
        // the copy's walks summarize its own zones
        summarize(copy);
        AssertJUnit.assertNotNull(cp.getZone(ZoneType.Library).validWalkSummary());
        AssertJUnit.assertSame(s, library.validWalkSummary());
        assertSameWork(copy);
        assertSameWork(game);

        Card lki = CardCopyService.getLKICopy(target);
        AssertJUnit.assertNull(lki.getWalkSummaryZone());
        AssertJUnit.assertSame(b, p.getZone(ZoneType.Battlefield).validWalkSummary());
    }

    @Test
    public void phasedOutCardsAreWalkedAsBefore() {
        Game game = initAndCreateGame();
        Player p = game.getPlayers().get(0);
        Zone battlefield = p.getZone(ZoneType.Battlefield);
        quiet(p, ZoneType.Battlefield);
        Card frozen = withEffect(p, ZoneType.Battlefield, CANT_UNTAP);
        Card out = quiet(p, ZoneType.Battlefield);
        summarize(game);
        out.setPhasedOut(game.getPlayers().get(1));
        frozen.setPhasedOut(game.getPlayers().get(1));
        AssertJUnit.assertEquals(3, battlefield.getCards(false).size());
        assertSameWork(game);
        summarize(game);
        AssertJUnit.assertNotNull(battlefield.validWalkSummary());
        AssertJUnit.assertEquals(List.of(frozen), visited(game, UNTAP));
        frozen.setPhasedOut(null);
        out.setPhasedOut(null);
        assertSameWork(game);
        AssertJUnit.assertEquals(List.of(frozen), visited(game, UNTAP));
    }

    @Test
    public void shieldAmountsAreStillCounted() {
        Game game = initAndCreateGame();
        Player p = game.getPlayers().get(0);
        quiet(p, ZoneType.Battlefield);
        Card shield = addCardToZone("Grizzly Bears", p, ZoneType.Battlefield);
        shield.setSVar("DBShield", "DB$ ReplaceDamage | Amount$ 3");
        shield.addReplacementEffect(ReplacementHandler.parseReplacement("Event$ DamageDone | ActiveZones$ Battlefield"
                + " | ValidTarget$ Card.Self | PreventionEffect$ True | ReplaceWith$ DBShield"
                + " | Description$ Prevent the next 3 damage.", shield, true));
        withEffect(p, ZoneType.Battlefield, CANT_UNTAP);
        quiet(p, ZoneType.Library);
        for (int i = 0; i < 4; i++) {
            AssertJUnit.assertEquals(3, game.getReplacementHandler().getTotalPreventionShieldAmount(shield));
        }
        AssertJUnit.assertNotNull(p.getZone(ZoneType.Battlefield).validWalkSummary());
    }

    @Test
    public void theProbeFindsNothingWrong() {
        Game game = initAndCreateGame();
        Player p = game.getPlayers().get(0);
        for (int i = 0; i < 6; i++) {
            if (i % 2 == 0) {
                quiet(p, ZoneType.Library);
            } else {
                withEffect(p, ZoneType.Battlefield, CANT_UNTAP);
            }
        }
        Card target = summarizedTarget(game, p);
        WalkSummaryProbe.start(false);
        for (int i = 0; i < 3; i++) {
            AssertJUnit.assertTrue(target.canUntap(p, true));
        }
        target.addReplacementEffect(ReplacementHandler.parseReplacement(CANT_UNTAP, target, true));
        for (int i = 0; i < 3; i++) {
            AssertJUnit.assertFalse(target.canUntap(p, true));
        }
        AssertJUnit.assertEquals(6L, WalkSummaryProbe.walks());
        AssertJUnit.assertTrue(WalkSummaryProbe.skipped() > 0);
        AssertJUnit.assertEquals(1L, WalkSummaryProbe.DROPPED[0].sum());
        AssertJUnit.assertEquals(0L, WalkSummaryProbe.failures());
    }

    @Test
    public void aSummaryMadeByAWalkAVisitMadeIsFollowedFromThePositionReached() {
        Game game = initAndCreateGame();
        Player p = game.getPlayers().get(0);
        Zone library = p.getZone(ZoneType.Library);
        Card f0 = withEffect(p, ZoneType.Library, CANT_UNTAP);
        Card f1 = withEffect(p, ZoneType.Library, CANT_UNTAP);
        Card f2 = withEffect(p, ZoneType.Library, CANT_UNTAP);
        Card f3 = withEffect(p, ZoneType.Library, CANT_UNTAP);
        quiet(p, ZoneType.Library);
        quiet(p, ZoneType.Library);
        quiet(p, ZoneType.Library);
        Card h = withEffect(p, ZoneType.Hand, CANT_UNTAP);
        p.getZone(ZoneType.Hand).remove(h);
        summarize(game);
        Zone.WalkSummary s = library.validWalkSummary();
        AssertJUnit.assertNotNull(s);
        AssertJUnit.assertEquals(4, s.index.length);
        // at f3 (position 3, the old summary's fourth entry) the visit takes f0 and f1 out and puts h last: the list
        // is f2 f3 q q q h, the plain loop goes on at position 4 and visits h at 5. Two walks made by the visit note
        // the changed zone and then summarize it again, so the outer walk goes on with a summary whose entries
        // (f2 f3 h at 0 1 5) are not the ones it was reading
        final List<Card> out = new ArrayList<>();
        final AtomicBoolean done = new AtomicBoolean(false);
        final List<Zone.WalkSummary> made = new ArrayList<>();
        game.forEachCardInGameSummarized(c -> {
            if ((c.knownReplacementEvents() & UNTAP) != 0) {
                out.add(c);
            }
            if (c == f3 && !done.getAndSet(true)) {
                library.remove(f0);
                library.remove(f1);
                library.add(h);
                summarize(game);
                made.add(library.validWalkSummary());
            }
            return true;
        }, UNTAP, null);
        AssertJUnit.assertEquals(List.of(f0, f1, f2, f3, h), out);
        AssertJUnit.assertNotNull(made.get(0));
        AssertJUnit.assertNotSame(s, made.get(0));
        AssertJUnit.assertEquals(3, made.get(0).index.length);
        AssertJUnit.assertEquals(5, made.get(0).index[2]);
    }

    @Test
    public void anUndoBringsBackTheSummaryTogetherWithWhatItPasses() {
        Game game = initAndCreateGame();
        Player p = game.getPlayers().get(0);
        Zone battlefield = p.getZone(ZoneType.Battlefield);
        Card target = summarizedTarget(game, p);
        Zone.WalkSummary s = battlefield.validWalkSummary();
        long epoch = game.getWalkCounterEpoch();
        UndoManager.Mark mark = game.getUndoManager().markForRollback(game);
        AssertJUnit.assertNotNull(mark);

        target.setCounters(CounterEnumType.STUN, 1);
        AssertJUnit.assertNull(battlefield.validWalkSummary());
        AssertJUnit.assertEquals(1, untapReplacements(game, target));
        summarize(game);
        AssertJUnit.assertNotNull(battlefield.validWalkSummary());
        AssertJUnit.assertNotSame(s, battlefield.validWalkSummary());
        AssertJUnit.assertEquals(epoch + 1, game.getWalkCounterEpoch());

        // the undo sets the counters, the epoch, the summary and the mark back to the capture, all at once
        AssertJUnit.assertTrue(game.getUndoManager().rollbackTo(game, mark));
        AssertJUnit.assertEquals(0, target.getCounters(CounterEnumType.STUN));
        AssertJUnit.assertEquals(epoch, game.getWalkCounterEpoch());
        AssertJUnit.assertSame(s, battlefield.validWalkSummary());
        AssertJUnit.assertSame(battlefield, target.getWalkSummaryZone());
        WalkSummaryProbe.start(false);
        for (int i = 0; i < 3; i++) {
            AssertJUnit.assertEquals(0, untapReplacements(game, target));
            AssertJUnit.assertTrue(target.canUntap(p, true));
        }
        // and a counter put after it is seen again, though the epoch comes back to the same value as before
        target.setCounters(CounterEnumType.STUN, 1);
        AssertJUnit.assertEquals(epoch + 1, game.getWalkCounterEpoch());
        AssertJUnit.assertNull(battlefield.validWalkSummary());
        for (int i = 0; i < 3; i++) {
            AssertJUnit.assertEquals(1, untapReplacements(game, target));
        }
        AssertJUnit.assertTrue(WalkSummaryProbe.skipped() > 0);
        AssertJUnit.assertEquals(0L, WalkSummaryProbe.failures());
    }

    @Test
    public void anUndoOfAChangedListBringsBackASummaryThatNoLongerHolds() {
        Game game = initAndCreateGame();
        Player p = game.getPlayers().get(0);
        Zone library = p.getZone(ZoneType.Library);
        List<Card> cards = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            cards.add(i == 2 ? withEffect(p, ZoneType.Library, CANT_UNTAP) : quiet(p, ZoneType.Library));
        }
        for (int round = 0; round < 2; round++) {
            summarize(game);
            Zone.WalkSummary s = library.validWalkSummary();
            AssertJUnit.assertNotNull(s);
            UndoManager.Mark mark = game.getUndoManager().markForRollback(game);
            AssertJUnit.assertNotNull(mark);
            library.reorder(cards.get(0), 3);
            if (round == 1) {
                // and back: the same cards in the same order, but the list has moved on
                library.reorder(cards.get(0), 0);
            }
            summarize(game);
            AssertJUnit.assertTrue(game.getUndoManager().rollbackTo(game, mark));
            AssertJUnit.assertEquals(cards, List.copyOf(library.getCards()));
            AssertJUnit.assertNull(library.validWalkSummary());
            WalkSummaryProbe.start(false);
            assertSameWork(game);
            summarize(game);
            AssertJUnit.assertEquals(List.of(cards.get(2)), visited(game, UNTAP));
            AssertJUnit.assertEquals(0L, WalkSummaryProbe.failures());
            WalkSummaryProbe.stop();
        }
    }

    @Test
    public void aCardTurnedFaceDownIsSeen() {
        Game game = initAndCreateGame();
        Player p = game.getPlayers().get(0);
        Card target = summarizedTarget(game, p);
        target.setOriginalStateAsFaceDown();
        AssertJUnit.assertNull(p.getZone(ZoneType.Battlefield).validWalkSummary());
        AssertJUnit.assertNull(target.getWalkSummaryZone());
        AssertJUnit.assertEquals(List.of(target), worked(game, UNTAP, true));
        assertSameWork(game);
    }

    private static int untapReplacements(final Game game, final Card c) {
        return game.getReplacementHandler().getReplacementList(ReplacementType.Untap, AbilityKey.mapFromAffected(c),
                ReplacementLayer.Other).size();
    }
}
