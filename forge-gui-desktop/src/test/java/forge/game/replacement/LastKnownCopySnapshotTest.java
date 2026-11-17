package forge.game.replacement;

import org.testng.AssertJUnit;
import org.testng.annotations.Test;

import forge.ai.ComputerUtil;
import forge.ai.simulation.SimulationTest;
import forge.game.Game;
import forge.game.card.Card;
import forge.game.card.CardCopyService;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;

/**
 * A card of a battlefield snapshot must not carry what its replacement effect's last run left on the effect's ability.
 *
 * <p>ReplacementHandler hands the ability of a replacement effect the snapshot of the battlefield and the graveyards
 * each time the effect runs, and marks it as a replacement ability; the ability keeps both (its own later readers, a
 * prevention effect's buffered sub-ability among them, use them). The next snapshot copies every card with its
 * replacement effects, and a copy that took the reference along made every snapshot keep the one before it alive:
 * with Nyxbloom Ancient in play, one whole battlefield more for every tap for mana, for the rest of the game (4 GB in
 * a stored game's long turn).
 */
public class LastKnownCopySnapshotTest extends SimulationTest {

    private static SpellAbility manaReplacement(final Card card) {
        for (final ReplacementEffect re : card.getReplacementEffects()) {
            if (re.getMode() == ReplacementType.ProduceMana) {
                return re.ensureAbility();
            }
        }
        throw new IllegalStateException("no mana replacement effect on " + card);
    }

    private static Card lastKnown(final Game game, final Card card) {
        for (final Card c : game.getLastStateBattlefield()) {
            if (c.getId() == card.getId()) {
                return c;
            }
        }
        throw new IllegalStateException(card + " is not in the last battlefield snapshot");
    }

    private static void tapForMana(final Game game, final Player p, final Card land) {
        land.setTapped(false);
        final SpellAbility mana = land.getManaAbilities().get(0);
        mana.setActivatingPlayer(p);
        final int before = p.getManaPool().totalMana();
        AssertJUnit.assertTrue(ComputerUtil.playNoStack(p, mana, game, false));
        AssertJUnit.assertEquals("the Ancient's replacement ran: three mana for one", before + 3, p.getManaPool().totalMana());
    }

    /** The Ancient and a Forest in play, a first snapshot taken, the Forest tapped for mana once. */
    private Card ancientAfterOneRun(final Game game, final Player p) {
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, p);
        final Card ancient = addCard("Nyxbloom Ancient", p);
        final Card forest = addCard("Forest", p);
        game.getAction().checkStateEffects(true);
        game.copyLastState();
        tapForMana(game, p, forest);
        return ancient;
    }

    @Test
    public void aSnapshotsCopyDoesNotInheritWhatTheEffectsLastRunLeft() {
        final Game game = initAndCreateGame();
        final Player p = game.getPlayers().get(0);
        final Card ancient = ancientAfterOneRun(game, p);
        final SpellAbility live = manaReplacement(ancient);
        AssertJUnit.assertNotNull("the game's own effect keeps the snapshot of its run", live.getLastStateBattlefield());
        AssertJUnit.assertEquals("and it is that snapshot: the Ancient and the Forest", 2, live.getLastStateBattlefield().size());
        AssertJUnit.assertTrue("and the mark of having run as a replacement", live.isReplacementAbility());

        game.copyLastState();
        final Card copy = lastKnown(game, ancient);
        AssertJUnit.assertNotSame(ancient, copy);
        final SpellAbility copied = manaReplacement(copy);
        AssertJUnit.assertNull("the snapshot's copy does not take the original's snapshot along", copied.getLastStateBattlefield());
        AssertJUnit.assertNull(copied.getLastStateGraveyard());
        AssertJUnit.assertFalse("nor the mark: it is an effect that has not run", copied.isReplacementAbility());
        AssertJUnit.assertEquals("taking the snapshot left the game's own effect as it was", 2, live.getLastStateBattlefield().size());
        AssertJUnit.assertNotNull(live.getLastStateGraveyard());
        AssertJUnit.assertTrue(live.isReplacementAbility());
    }

    @Test
    public void aSnapshotDoesNotLeadBackToEarlierSnapshotsThroughAReplacementEffect() {
        final Game game = initAndCreateGame();
        final Player p = game.getPlayers().get(0);
        final Card ancient = ancientAfterOneRun(game, p);
        final Card forest = p.getLandsInPlay().get(0);
        game.copyLastState();
        for (int i = 0; i < 4; i++) {
            tapForMana(game, p, forest);
            game.copyLastState();
        }
        // the snapshot the live effect holds is the one its last run saw; no replacement effect in it leads further back
        final SpellAbility live = manaReplacement(ancient);
        AssertJUnit.assertNotNull(live.getLastStateBattlefield());
        AssertJUnit.assertEquals("the kept snapshot is a whole battlefield", 2, live.getLastStateBattlefield().size());
        for (final Card old : live.getLastStateBattlefield()) {
            for (final ReplacementEffect re : old.getReplacementEffects()) {
                if (re.getOverridingAbility() != null) {
                    AssertJUnit.assertNull("a replacement effect of the kept snapshot holds no earlier one (" + old + ")",
                            re.getOverridingAbility().getLastStateBattlefield());
                    AssertJUnit.assertNull(re.getOverridingAbility().getLastStateGraveyard());
                }
            }
        }
    }

    @Test
    public void aCardRefreshedInTheSnapshotIsASnapshotsCopyToo() {
        final Game game = initAndCreateGame();
        final Player p = game.getPlayers().get(0);
        final Card ancient = ancientAfterOneRun(game, p);
        final Card before = lastKnown(game, ancient);

        game.updateLastStateForCard(ancient); // what a counter put on the card, or a cost paid with it, does
        final Card refreshed = lastKnown(game, ancient);
        AssertJUnit.assertNotSame("the snapshot holds a new copy of the card", before, refreshed);
        final SpellAbility copied = manaReplacement(refreshed);
        AssertJUnit.assertNull("which takes the original's snapshot along no more than the others", copied.getLastStateBattlefield());
        AssertJUnit.assertNull(copied.getLastStateGraveyard());
        AssertJUnit.assertFalse(copied.isReplacementAbility());
        AssertJUnit.assertTrue("the game's own effect is as it was", manaReplacement(ancient).isReplacementAbility());
        AssertJUnit.assertFalse("and the snapshot is over", game.isTakingZoneSnapshot());
    }

    @Test
    public void snapshotsInProgressAreCounted() {
        final Game game = initAndCreateGame();
        AssertJUnit.assertFalse(game.isTakingZoneSnapshot());
        game.beginZoneSnapshot();
        game.beginZoneSnapshot();
        game.endZoneSnapshot();
        AssertJUnit.assertTrue("one of two is still being taken", game.isTakingZoneSnapshot());
        game.endZoneSnapshot();
        AssertJUnit.assertFalse(game.isTakingZoneSnapshot());
    }

    @Test
    public void aLastKnownCopyMadeOutsideASnapshotKeepsWhatTheOriginalHas() {
        final Game game = initAndCreateGame();
        final Player p = game.getPlayers().get(0);
        final Card ancient = ancientAfterOneRun(game, p);
        final SpellAbility live = manaReplacement(ancient);

        // the copy the engine makes of one card (a card that changes zones, a cost, a trigger's object): as before
        final Card single = CardCopyService.getLKICopy(ancient);
        AssertJUnit.assertNotSame(ancient, single);
        final SpellAbility copied = manaReplacement(single);
        AssertJUnit.assertSame("a single last-known copy still shares the original's snapshot", live.getLastStateBattlefield(),
                copied.getLastStateBattlefield());
        AssertJUnit.assertSame(live.getLastStateGraveyard(), copied.getLastStateGraveyard());
        AssertJUnit.assertTrue("and its mark", copied.isReplacementAbility());

        game.copyLastState();
        AssertJUnit.assertSame("and a snapshot taken afterwards does not touch it", live.getLastStateBattlefield(),
                copied.getLastStateBattlefield());
    }
}
