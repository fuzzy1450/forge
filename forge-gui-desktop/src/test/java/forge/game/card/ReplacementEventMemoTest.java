package forge.game.card;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import org.testng.AssertJUnit;
import org.testng.annotations.Test;

import forge.ai.AITest;
import forge.card.CardStateName;
import forge.game.Game;
import forge.game.ability.AbilityKey;
import forge.game.player.Player;
import forge.game.replacement.ReplacementEffect;
import forge.game.replacement.ReplacementHandler;
import forge.game.replacement.ReplacementLayer;
import forge.game.replacement.ReplacementType;
import forge.game.zone.ZoneType;

/**
 * Card keeps, beside the state known to have no replacement effect, the events the replacement effects it last found
 * can accept (ReplacementEffect#modeCheckEvents), and Card#knownToHaveNoReplacementEffectOf answers from them while
 * the trait version has not moved and the card has no split state, changed trait or counter. Each test reads the
 * effects first, so that the events are kept, then changes one thing the rebuild reads: the change must be seen.
 */
public class ReplacementEventMemoTest extends AITest {

    private static final String MOVED = "Event$ Moved | ValidCard$ Card.Self | Destination$ Battlefield"
            + " | ReplacementResult$ Updated | Description$ CARDNAME enters.";
    private static final String CANT_UNTAP = "Event$ Untap | ValidCard$ Card.Self | Layer$ CantHappen"
            + " | Description$ CARDNAME doesn't untap.";
    private static final String ADD_COUNTER = "Event$ AddCounter | ActiveZones$ Battlefield | ValidCard$ Card.Self"
            + " | ValidCounterType$ P1P1 | Description$ Nothing.";
    private static final String PREVENT = "Event$ DamageDone | ActiveZones$ Battlefield | ValidTarget$ Card.Self"
            + " | Prevent$ True | Description$ Prevent all damage that would be dealt to CARDNAME.";

    /** A card with one replacement effect of its own, {@code effect}, read once so that its events are kept. */
    private Card withEffect(final Player p, final String effect) {
        Card c = createCard("Grizzly Bears", p);
        c.addReplacementEffect(ReplacementHandler.parseReplacement(effect, c, true));
        AssertJUnit.assertEquals(1, c.getReplacementEffects().size());
        return c;
    }

    @Test
    public void theEventsOfTheEffectsFoundAreKept() {
        Game game = initAndCreateGame();
        Player p = game.getPlayers().get(0);

        Card moved = createCard("Grizzly Bears", p);
        moved.addReplacementEffect(ReplacementHandler.parseReplacement(MOVED, moved, true));
        // nothing is known before a read
        AssertJUnit.assertFalse(moved.knownToHaveNoReplacementEffectOf(ReplacementType.Untap));
        AssertJUnit.assertEquals(1, moved.getReplacementEffects().size());
        AssertJUnit.assertFalse(moved.knownToHaveNoReplacementEffects());
        AssertJUnit.assertFalse(moved.knownToHaveNoReplacementEffectOf(ReplacementType.Moved));
        AssertJUnit.assertTrue(moved.knownToHaveNoReplacementEffectOf(ReplacementType.Untap));
        AssertJUnit.assertTrue(moved.knownToHaveNoReplacementEffectOf(ReplacementType.DamageDone));
        AssertJUnit.assertFalse(moved.knownToHaveNoReplacementEffectOf(null));

        // a card known to have none has none of any event
        Card bears = createCard("Grizzly Bears", p);
        AssertJUnit.assertTrue(bears.getReplacementEffects().isEmpty());
        AssertJUnit.assertTrue(bears.knownToHaveNoReplacementEffectOf(ReplacementType.Moved));
        AssertJUnit.assertTrue(bears.knownToHaveNoReplacementEffectOf(ReplacementType.Untap));

        // the bits fit one long
        AssertJUnit.assertTrue(ReplacementType.values().length <= 64);
    }

    @Test
    public void anAddCounterEffectAlsoAcceptsMoved() {
        Game game = initAndCreateGame();
        Player p = game.getPlayers().get(0);

        // ReplaceAddCounter.modeCheck accepts Moved when the run params carry a counter map
        Card c = withEffect(p, ADD_COUNTER);
        ReplacementEffect re = c.getReplacementEffects().getFirst();
        AssertJUnit.assertTrue(re.modeCheck(ReplacementType.Moved, Map.of(AbilityKey.CounterMap, Map.of())));
        AssertJUnit.assertEquals(1L << ReplacementType.AddCounter.ordinal() | 1L << ReplacementType.Moved.ordinal(),
                re.modeCheckEvents());
        AssertJUnit.assertFalse(c.knownToHaveNoReplacementEffectOf(ReplacementType.Moved));
        AssertJUnit.assertFalse(c.knownToHaveNoReplacementEffectOf(ReplacementType.AddCounter));
        AssertJUnit.assertTrue(c.knownToHaveNoReplacementEffectOf(ReplacementType.Untap));
    }

    @Test
    public void anEffectAddedAfterTheAnswerIsSeen() {
        Game game = initAndCreateGame();
        Player p = game.getPlayers().get(0);

        Card c = withEffect(p, MOVED);
        AssertJUnit.assertTrue(c.knownToHaveNoReplacementEffectOf(ReplacementType.Untap));
        c.addReplacementEffect(ReplacementHandler.parseReplacement(CANT_UNTAP, c, true));
        AssertJUnit.assertFalse(c.knownToHaveNoReplacementEffectOf(ReplacementType.Untap));
        AssertJUnit.assertEquals(2, c.getReplacementEffects().size());
        AssertJUnit.assertFalse(c.knownToHaveNoReplacementEffectOf(ReplacementType.Untap));
        AssertJUnit.assertTrue(c.knownToHaveNoReplacementEffectOf(ReplacementType.Tap));
    }

    @Test
    public void countersEndTheAnswer() {
        Game game = initAndCreateGame();
        Player p = game.getPlayers().get(0);

        // a shield counter brings a DamageDone replacement the kept events do not name
        Card c = withEffect(p, MOVED);
        AssertJUnit.assertTrue(c.knownToHaveNoReplacementEffectOf(ReplacementType.DamageDone));
        c.setCounters(CounterEnumType.SHIELD, 1);
        AssertJUnit.assertFalse(c.knownToHaveNoReplacementEffectOf(ReplacementType.DamageDone));
        AssertJUnit.assertEquals(3, c.getReplacementEffects().size());
        AssertJUnit.assertFalse(c.knownToHaveNoReplacementEffectOf(ReplacementType.DamageDone));
        AssertJUnit.assertFalse(c.knownToHaveNoReplacementEffectOf(ReplacementType.Untap));
        // without it the effects are those found before again
        c.setCounters(CounterEnumType.SHIELD, 0);
        AssertJUnit.assertTrue(c.knownToHaveNoReplacementEffectOf(ReplacementType.DamageDone));
        AssertJUnit.assertEquals(1, c.getReplacementEffects().size());
    }

    @Test
    public void aTypeChangeIsSeen() {
        Game game = initAndCreateGame();
        Player p = game.getPlayers().get(0);

        // a Saga enters with a lore counter, a Moved replacement the rebuild makes when the type says Saga
        Card c = withEffect(p, CANT_UNTAP);
        AssertJUnit.assertTrue(c.knownToHaveNoReplacementEffectOf(ReplacementType.Moved));
        c.addType("Saga");
        AssertJUnit.assertFalse(c.knownToHaveNoReplacementEffectOf(ReplacementType.Moved));
        AssertJUnit.assertTrue(c.getReplacementEffects().contains(c.getCurrentState().getSagaRep()));
        AssertJUnit.assertFalse(c.knownToHaveNoReplacementEffectOf(ReplacementType.Moved));
    }

    @Test
    public void anotherStateIsNotAnswered() {
        Game game = initAndCreateGame();
        Player p = game.getPlayers().get(0);

        // the back face is a planeswalker, which enters with its loyalty: a Moved replacement
        Card jace = createCard("Jace, Vryn's Prodigy", p);
        jace.addReplacementEffect(ReplacementHandler.parseReplacement(CANT_UNTAP, jace, true));
        AssertJUnit.assertEquals(1, jace.getReplacementEffects().size());
        AssertJUnit.assertTrue(jace.knownToHaveNoReplacementEffectOf(ReplacementType.Moved));
        jace.setState(CardStateName.Backside, true);
        AssertJUnit.assertFalse(jace.knownToHaveNoReplacementEffectOf(ReplacementType.Moved));
        AssertJUnit.assertEquals(List.of(jace.getCurrentState().getLoyaltyRep()),
                List.copyOf(jace.getReplacementEffects()));
        AssertJUnit.assertFalse(jace.knownToHaveNoReplacementEffectOf(ReplacementType.Moved));
        AssertJUnit.assertTrue(jace.knownToHaveNoReplacementEffectOf(ReplacementType.Untap));
    }

    @Test
    public void splitStatesAndChangedTraitsEndTheAnswer() {
        Game game = initAndCreateGame();
        Player p = game.getPlayers().get(0);

        Card split = withEffect(p, MOVED);
        AssertJUnit.assertTrue(split.knownToHaveNoReplacementEffectOf(ReplacementType.Untap));
        CardState left = new CardState(split, CardStateName.LeftSplit);
        left.addReplacementEffect(ReplacementHandler.parseReplacement(CANT_UNTAP, split, true));
        Map<CardStateName, CardState> states = new EnumMap<>(CardStateName.class);
        states.put(CardStateName.Original, split.getCurrentState());
        states.put(CardStateName.LeftSplit, left);
        split.setStates(states);
        AssertJUnit.assertFalse(split.knownToHaveNoReplacementEffectOf(ReplacementType.Untap));
        AssertJUnit.assertEquals(2, split.getReplacementEffects().size());

        Card changed = withEffect(p, MOVED);
        ReplacementEffect re = ReplacementHandler.parseReplacement(CANT_UNTAP, changed, false);
        AssertJUnit.assertTrue(changed.knownToHaveNoReplacementEffectOf(ReplacementType.Untap));
        changed.addChangedCardTraits(null, null, List.of(re), null, null, game.getNextTimestamp(), 0);
        AssertJUnit.assertFalse(changed.knownToHaveNoReplacementEffectOf(ReplacementType.Untap));
        AssertJUnit.assertTrue(changed.getReplacementEffects().contains(re));
    }

    @Test
    public void theScansStillFindWhatTheEventNeeds() {
        Game game = initAndCreateGame();
        Player p = game.getPlayers().get(0);

        // the Untap scan passes a card whose only effect is a Moved one, and still finds a CantHappen Untap one
        Card moved = addCard("Grizzly Bears", p);
        moved.addReplacementEffect(ReplacementHandler.parseReplacement(MOVED, moved, true));
        Card frozen = addCard("Grizzly Bears", p);
        frozen.addReplacementEffect(ReplacementHandler.parseReplacement(CANT_UNTAP, frozen, true));
        frozen.setTapped(true);
        moved.setTapped(true);
        AssertJUnit.assertFalse(frozen.canUntap(p, true));
        AssertJUnit.assertTrue(moved.knownToHaveNoReplacementEffectOf(ReplacementType.Untap));
        AssertJUnit.assertTrue(moved.canUntap(p, true));
        AssertJUnit.assertFalse(frozen.canUntap(p, true));
        Map<AbilityKey, Object> params = AbilityKey.mapFromAffected(frozen);
        AssertJUnit.assertEquals(List.copyOf(frozen.getReplacementEffects()), game.getReplacementHandler()
                .getReplacementList(ReplacementType.Untap, params, ReplacementLayer.CantHappen));

        // damage prevention passes a card without a DamageDone effect, and still finds one that has it
        Card source = addCard("Grizzly Bears", game.getPlayers().get(1));
        AssertJUnit.assertTrue(moved.knownToHaveNoReplacementEffectOf(ReplacementType.DamageDone));
        AssertJUnit.assertEquals(2, moved.staticDamagePrevention(2, 0, source, false));
        Card shielded = addCard("Grizzly Bears", p);
        shielded.addReplacementEffect(ReplacementHandler.parseReplacement(PREVENT, shielded, true));
        AssertJUnit.assertEquals(1, shielded.getReplacementEffects().size());
        AssertJUnit.assertFalse(shielded.knownToHaveNoReplacementEffectOf(ReplacementType.DamageDone));
        AssertJUnit.assertEquals(0, shielded.staticDamagePrevention(2, 0, source, false));
        AssertJUnit.assertEquals(2, moved.staticDamagePrevention(2, 0, source, false));
        AssertJUnit.assertTrue(p.getZone(ZoneType.Battlefield).contains(shielded));
    }
}
