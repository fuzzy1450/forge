package forge.game.replacement;

import org.testng.AssertJUnit;
import org.testng.annotations.Test;

import forge.ai.ComputerUtil;
import forge.ai.simulation.SimulationTest;
import forge.game.Game;
import forge.game.card.Card;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;

/**
 * A last-known copy of a card must not carry the battlefield snapshot its replacement effect kept from its last run.
 *
 * <p>ReplacementHandler hands the ability of a replacement effect the snapshot of the battlefield and the graveyards
 * each time the effect runs, and the ability keeps it (effects that run later, a prevention effect's buffered
 * sub-ability among them, read it). The next snapshot copies every card with its replacement effects, and a copy
 * that took the reference along made every snapshot keep the one before it alive: with Nyxbloom Ancient in play,
 * one whole battlefield more for every tap for mana, for the rest of the game (4 GB in a stored game's long turn).
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

    @Test
    public void aLastKnownCopyDoesNotInheritTheSnapshotOfTheEffectsLastRun() {
        final Game game = initAndCreateGame();
        final Player p = game.getPlayers().get(0);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, p);
        final Card ancient = addCard("Nyxbloom Ancient", p);
        final Card forest = addCard("Forest", p);
        game.getAction().checkStateEffects(true);
        game.copyLastState();

        tapForMana(game, p, forest);
        final SpellAbility live = manaReplacement(ancient);
        AssertJUnit.assertNotNull("the game's own effect keeps the snapshot of its run, for effects that run later",
                live.getLastStateBattlefield());
        AssertJUnit.assertEquals("and it is that snapshot: the Ancient and the Forest", 2, live.getLastStateBattlefield().size());

        game.copyLastState();
        final Card copy = lastKnown(game, ancient);
        AssertJUnit.assertNotSame(ancient, copy);
        final SpellAbility copied = manaReplacement(copy);
        AssertJUnit.assertNull("the copy does not take the original's snapshot along", copied.getLastStateBattlefield());
        AssertJUnit.assertNull(copied.getLastStateGraveyard());
    }

    @Test
    public void aSnapshotDoesNotKeepEarlierSnapshotsAlive() {
        final Game game = initAndCreateGame();
        final Player p = game.getPlayers().get(0);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, p);
        final Card ancient = addCard("Nyxbloom Ancient", p);
        final Card forest = addCard("Forest", p);
        game.getAction().checkStateEffects(true);
        game.copyLastState();

        for (int i = 0; i < 5; i++) {
            tapForMana(game, p, forest);
            game.copyLastState();
        }
        // the snapshot the live effect holds is the one its last run saw; nothing in it leads further back
        final SpellAbility live = manaReplacement(ancient);
        int cards = 0;
        for (final Card old : live.getLastStateBattlefield()) {
            cards++;
            for (final ReplacementEffect re : old.getReplacementEffects()) {
                if (re.getOverridingAbility() != null) {
                    AssertJUnit.assertNull("a card of the kept snapshot leads to no earlier one (" + old + ")",
                            re.getOverridingAbility().getLastStateBattlefield());
                    AssertJUnit.assertNull(re.getOverridingAbility().getLastStateGraveyard());
                }
            }
        }
        AssertJUnit.assertEquals("the kept snapshot is a whole battlefield", 2, cards);
    }
}
