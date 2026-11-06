package forge.ai;

import static org.testng.AssertJUnit.assertEquals;
import static org.testng.AssertJUnit.assertFalse;
import static org.testng.AssertJUnit.assertTrue;

import org.testng.annotations.Test;

import forge.game.Game;
import forge.game.card.Card;
import forge.game.card.CounterEnumType;
import forge.game.cost.Cost;
import forge.game.cost.CostPart;
import forge.game.cost.CostPayEnergy;
import forge.game.keyword.Keyword;
import forge.game.keyword.KeywordInterface;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;

/**
 * Reiterating Bolt's replicate cost is {E}{E}{E}, and the AI's chooser adds the cost up until a running total cannot be
 * paid. Cost.add merged life, discard, sacrifice, tap, exile, draw and add-mana parts but not energy, so the total was a
 * list of separate three-energy parts, each paid alone against the same pool, and no total ever failed: the loop ran
 * toward Integer.MAX_VALUE until the game's 300-second cap. Sataya, ETB Genius (fa8c7420), the only list holding the
 * card, lost 50 of 985 games on the active engine to it. Two energy parts are now one, as two life parts are.
 */
public class ReiteratingBoltReplicateTest extends AITest {
    private static final String BOLT = "Reiterating Bolt";
    private static final String REPLICATE = "PayEnergy<3>";
    /** Past any answer of a board here, and bounded, so a chooser that never stops says so with a number. */
    private static final int BOUND = 64;

    private Game game;
    private Player ai;
    private Player opp;

    private void board(final int energy) {
        game = initAndCreateGame();
        ai = game.getPlayers().get(1);
        opp = game.getPlayers().get(0);
        ai.setCounters(CounterEnumType.ENERGY, energy, null, false);
        fillLibrary(ai, 10);
        fillLibrary(opp, 10);
    }

    private SpellAbility boltInHand() {
        addCards("Mountain", 2, ai);
        final Card bolt = addCardToZone(BOLT, ai, ZoneType.Hand);
        final SpellAbility cast = bolt.getFirstSpellAbility();
        cast.setActivatingPlayer(ai);
        return cast;
    }

    private static int energyParts(final Cost cost) {
        int parts = 0;
        for (final CostPart part : cost.getCostParts()) {
            if (part instanceof CostPayEnergy) {
                parts++;
            }
        }
        return parts;
    }

    private int chosen(final SpellAbility cast, final int max) {
        KeywordInterface replicate = null;
        for (final KeywordInterface k : cast.getHostCard().getKeywords()) {
            if (k.getKeyword() == Keyword.REPLICATE) {
                replicate = k;
            }
        }
        return ((PlayerControllerAi) ai.getController()).chooseNumberForKeywordCost(cast, new Cost(REPLICATE, false),
                replicate, "test", max);
    }

    @Test
    public void twoEnergyPartsAreOnePart() {
        final Cost total = new Cost(REPLICATE, false);
        total.add(new Cost(REPLICATE, false));
        assertEquals(1, energyParts(total));
        assertEquals("every reader of getCostEnergy reads the total", Integer.valueOf(6),
                total.getCostEnergy().convertAmount());
        assertEquals("Pay {E}{E}{E}{E}{E}{E}", total.getCostEnergy().toString());
    }

    @Test
    public void theMergedCostIsPaidAgainstTheWholePool() {
        board(4);
        final SpellAbility cast = boltInHand();
        final Cost total = new Cost(REPLICATE, false);
        total.add(new Cost(REPLICATE, false));
        assertFalse("3 + 3 against 4", total.canPay(cast, ai, false));
        ai.setCounters(CounterEnumType.ENERGY, 6, null, false);
        assertTrue("3 + 3 against 6", total.canPay(cast, ai, false));
    }

    /** As every merged kind: mergeAdditional false keeps costs of different causes apart, and an X is no number yet. */
    @Test
    public void whatStaysApartStaysApart() {
        final Cost causes = new Cost(REPLICATE, false);
        causes.add(new Cost(REPLICATE, false), false);
        assertEquals(2, energyParts(causes));
        final Cost x = new Cost(REPLICATE, false);
        x.add(new Cost("PayEnergy<X>", false));
        assertEquals(2, energyParts(x));
    }

    @Test
    public void theChooserStopsAtTheEnergyThereIs() {
        board(2);
        assertEquals(0, chosen(boltInHand(), BOUND));
        board(4);
        assertEquals(1, chosen(boltInHand(), BOUND));
        board(6);
        assertEquals(2, chosen(boltInHand(), BOUND));
        board(7);
        assertEquals(2, chosen(boltInHand(), BOUND));
    }

    /** GameActionUtil.addExtraKeywordCost's own bound. */
    @Test(timeOut = 60_000)
    public void theChooserTerminatesAtGameActionUtilsBound() {
        board(6);
        assertEquals(2, chosen(boltInHand(), Integer.MAX_VALUE));
    }

    /** The AI's own cast, by the game loop: its target, its payment, the replicate trigger and the copies. */
    @Test(timeOut = 120_000)
    public void aCastPaysThreeEnergyACopyAndMakesThatManyCopies() {
        board(7);
        final SpellAbility cast = boltInHand();
        addCards("Grizzly Bears", 3, opp);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN2, ai);
        game.getAction().checkStateEffects(true);
        int mostOnTheStack = 0;
        for (int i = 0; i < 200 && !game.isGameOver(); i++) {
            game.getPhaseHandler().mainLoopStep();
            mostOnTheStack = Math.max(mostOnTheStack, game.getStack().size());
            if (cast.getHostCard().isInZone(ZoneType.Graveyard) && game.getStack().isEmpty()) {
                break;
            }
        }
        assertTrue("the AI cast it", cast.getHostCard().isInZone(ZoneType.Graveyard));
        assertEquals("two copies at three each, of seven", 1, ai.getCounters(CounterEnumType.ENERGY));
        assertEquals("the Bolt and its two copies", 3, mostOnTheStack);
    }

    /** Short of a copy's energy the spell is still cast, for its mana alone. */
    @Test(timeOut = 120_000)
    public void aCastShortOfACopysEnergyPaysNone() {
        board(2);
        final SpellAbility cast = boltInHand();
        addCards("Grizzly Bears", 1, opp);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN2, ai);
        game.getAction().checkStateEffects(true);
        int mostOnTheStack = 0;
        for (int i = 0; i < 200 && !game.isGameOver(); i++) {
            game.getPhaseHandler().mainLoopStep();
            mostOnTheStack = Math.max(mostOnTheStack, game.getStack().size());
            if (cast.getHostCard().isInZone(ZoneType.Graveyard) && game.getStack().isEmpty()) {
                break;
            }
        }
        assertTrue("the AI cast it", cast.getHostCard().isInZone(ZoneType.Graveyard));
        assertEquals(2, ai.getCounters(CounterEnumType.ENERGY));
        assertEquals(1, mostOnTheStack);
    }
}
