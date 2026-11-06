package forge.ai;

import static org.testng.AssertJUnit.assertEquals;
import static org.testng.AssertJUnit.assertFalse;
import static org.testng.AssertJUnit.assertNotSame;
import static org.testng.AssertJUnit.assertTrue;

import java.lang.reflect.Method;

import org.testng.annotations.Test;

import forge.card.MagicColor;
import forge.game.Game;
import forge.game.card.Card;
import forge.game.mana.Mana;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;

/**
 * The upstream merge's rule R8 for a payment the AI cannot make (ComputerUtil.handlePlayingSpellAbility): with the
 * stack not frozen on entry the fork's seed-11 rollback takes the cast back alone, counted by AiController's own
 * two-tries-a-phase rule and never by upstream's one-failure-a-turn memory; with the stack frozen on entry (a cast
 * inside a resolution) upstream's abandonCast takes it back and the rollback does not run. Both empty the ability's
 * paying lists before anything is paid or refunded, so no earlier activation's mana comes back.
 */
public class MergeR8PaymentTest extends AITest {
    private static final String ELVES = "Llanowar Elves";
    private static final String BEARS = "Grizzly Bears";
    private static final String BOLT = "Lightning Bolt";

    private Game game;
    private Player ai;
    private PlayerControllerAi pc;
    private AiController brain;

    private void start() {
        game = initAndCreateGame();
        ai = game.getPlayers().get(1);
        for (final Player p : game.getPlayers()) {
            fillLibrary(p, 20);
        }
        game.getPhaseHandler().devModeSet(PhaseType.MAIN2, ai);
        game.getAction().checkStateEffects(true);
        pc = (PlayerControllerAi) ai.getController();
        brain = pc.getAi();
    }

    private SpellAbility spellIn(final String name) {
        final SpellAbility sa = addCardToZone(name, ai, ZoneType.Hand).getFirstSpellAbility();
        sa.setActivatingPlayer(ai);
        return sa;
    }

    private static int count(final Player p, final ZoneType zone, final String name) {
        int n = 0;
        for (final Card c : p.getCardsIn(zone)) {
            if (c.getName().equals(name)) {
                n++;
            }
        }
        return n;
    }

    private static Card first(final Player p, final ZoneType zone, final String name) {
        for (final Card c : p.getCardsIn(zone)) {
            if (c.getName().equals(name)) {
                return c;
            }
        }
        return null;
    }

    /** Mana an earlier activation of the same ability paid with: a refund of it would float it again. */
    private Mana staleMana() {
        final Card mountain = addCard("Mountain", ai);
        mountain.setTapped(true);   // it paid the earlier activation, and pays nothing now
        return new Mana((byte) MagicColor.RED, mountain, mountain.getManaAbilities().get(0).getManaPart(), ai);
    }

    @Test(timeOut = 120_000)
    public void aFailedPaymentIsTakenBackByTheSeed11RollbackAlone() {
        start();
        final SpellAbility sa = spellIn(ELVES);
        assertTrue("the harness needs a card with a second ability", sa.getHostCard().getSpellAbilities().size() > 1);
        sa.getPayingMana().add(staleMana());

        final boolean played = pc.playWithRollback(sa, null);

        assertFalse(played);
        assertEquals("back in hand", 1, count(ai, ZoneType.Hand, ELVES));
        assertEquals("not lost in the stack zone", 0, game.getCardsIn(ZoneType.Stack).size());
        assertFalse("the stack frozen for the cast is unfrozen again", game.getStack().isFrozen());
        assertEquals("the rollback counted the failed payment", 1, brain.getRolledBackPaymentCount());
        assertFalse("and upstream's one-failure-a-turn memory did not", brain.hasFailedToCast(sa));
        assertEquals("the stale paying mana of an earlier activation was not refunded", 0,
                ai.getManaPool().totalMana());
        for (final SpellAbility csa : sa.getHostCard().getSpellAbilities()) {
            assertTrue("every ability of the card sits out the next choice: " + csa, csa.isSkip());
        }
    }

    /** An earlier activation's source and its mana, still floating: a refund of the stale paying list would undo it. */
    @Test(timeOut = 120_000)
    public void aFailedPaymentDoesNotUntapTheSourceOfAnEarlierActivation() {
        start();
        final SpellAbility sa = spellIn(ELVES);
        final Card mountain = addCard("Mountain", ai);
        mountain.setTapped(true);
        final SpellAbility tap = mountain.getManaAbilities().get(0);
        ai.getManaPool().addMana(new Mana((byte) MagicColor.RED, mountain, tap.getManaPart(), ai));
        sa.getPayingManaAbilities().add(tap);

        assertFalse(pc.playWithRollback(sa, null));

        assertEquals(1, count(ai, ZoneType.Hand, ELVES));
        assertTrue("the Mountain that paid the earlier activation stays tapped", mountain.isTapped());
        assertEquals("and its mana stays where it was", 1, ai.getManaPool().totalMana());
    }

    @Test(timeOut = 120_000)
    public void aFailedPaymentGivesBackWhatWasPaid() {
        start();
        final Card forest = addCard("Forest", ai);
        final SpellAbility sa = spellIn(BEARS);     // {1}{G} against one Forest: the Forest pays, the generic fails

        assertFalse(pc.playWithRollback(sa, null));

        assertEquals(1, count(ai, ZoneType.Hand, BEARS));
        assertFalse("the Forest it tapped is untapped again", forest.isTapped());
        assertEquals(0, ai.getManaPool().totalMana());
    }

    @Test(timeOut = 120_000)
    public void aCastInsideAResolutionIsTakenBackByAbandonCast() {
        start();
        final Card forest = addCard("Forest", ai);
        final SpellAbility sa = spellIn(BEARS);
        sa.getPayingMana().add(staleMana());
        game.getStack().freezeStack(null);   // as MagicStack.resolveStack freezes it for a resolution

        final boolean played = ComputerUtil.handlePlayingSpellAbility(ai, sa, null);

        assertFalse(played);
        assertEquals("back in hand", 1, count(ai, ZoneType.Hand, BEARS));
        assertEquals("not lost in the stack zone", 0, game.getCardsIn(ZoneType.Stack).size());
        assertEquals("the seed-11 rollback did not run", 0, brain.getRolledBackPaymentCount());
        assertTrue("the resolving stack stays frozen", game.getStack().isFrozen());
        assertFalse("what was paid is refunded: the Forest is untapped again", forest.isTapped());
        assertEquals("and the stale mana of an earlier activation is not refunded with it", 0,
                ai.getManaPool().totalMana());
        for (final SpellAbility csa : first(ai, ZoneType.Hand, BEARS).getSpellAbilities()) {
            assertTrue("the card sits out the next choice: " + csa, csa.isSkip());
        }
    }

    @Test(timeOut = 120_000)
    public void aCastThatFailedEarlierThisTurnIsNotPaidFor() {
        start();
        final Card forestA = addCard("Forest", ai);
        final Card forestB = addCard("Forest", ai);
        final SpellAbility sa = spellIn(BEARS);
        assertNotSame(AiPlayDecision.CantPlayAi, brain.canPlaySa(sa));

        brain.noteFailedCast(AiController.castKey(sa));

        assertEquals("canPlaySa refuses it", AiPlayDecision.CantPlayAi, brain.canPlaySa(sa));
        assertFalse("and so does the cast, before it pays", ComputerUtil.handlePlayingSpellAbility(ai, sa, null));
        assertEquals(1, count(ai, ZoneType.Hand, BEARS));
        assertEquals(0, game.getCardsIn(ZoneType.Stack).size());
        assertEquals(0, game.getStack().size());
        assertFalse(forestA.isTapped());
        assertFalse(forestB.isTapped());
        assertEquals("nothing was rolled back either", 0, brain.getRolledBackPaymentCount());
    }

    @Test(timeOut = 120_000)
    public void aCastGivenUpBeforeItsPaymentEmptiesThePayingListsFirst() {
        start();
        final SpellAbility sa = spellIn(BOLT);
        sa.getPayingMana().add(staleMana());

        final boolean played = pc.playWithRollback(sa, s -> { });   // the deferred target choice finds nothing

        assertFalse(played);
        assertEquals(1, count(ai, ZoneType.Hand, BOLT));
        assertEquals(0, game.getCardsIn(ZoneType.Stack).size());
        assertEquals("the stale paying mana of an earlier activation was not refunded", 0,
                ai.getManaPool().totalMana());
    }

    private AiPlayDecision payability(final SpellAbility sa) throws Exception {
        final Method m = AiController.class.getDeclaredMethod("canPlayAndPayFor", SpellAbility.class);
        m.setAccessible(true);
        return (AiPlayDecision) m.invoke(brain, sa);
    }

    @Test(timeOut = 120_000)
    public void aCardWhosePaymentFailedTwiceInAPhaseIsRefusedForThatPhaseOnly() throws Exception {
        start();
        final SpellAbility sa = spellIn(BEARS);
        final Card host = sa.getHostCard();

        brain.rememberFailedCast(host);
        assertTrue("one failure leaves its retry", AiPlayDecision.CantPlaySa != payability(sa));
        brain.rememberFailedCast(host);
        assertEquals("two failures end its tries", AiPlayDecision.CantPlaySa, payability(sa));

        // the next phase begins afresh, and its own two failures end its tries again
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, ai);
        assertTrue("the next phase begins afresh", AiPlayDecision.CantPlaySa != payability(sa));
        brain.rememberFailedCast(host);
        assertTrue("one failure in a new phase is one", AiPlayDecision.CantPlaySa != payability(sa));
        brain.rememberFailedCast(host);
        assertEquals("two in the new phase end its tries", AiPlayDecision.CantPlaySa, payability(sa));
    }
}
