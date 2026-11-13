package forge.ai.simulation;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertSame;

import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import forge.card.mana.ManaAtom;
import forge.game.card.Card;
import forge.game.mana.Mana;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;

/**
 * Floating mana in a copied game (MTG_DeckMaker's tools/combo-ai/flat-mc/SHIP-PLAN.md, Task 7a: the Error of the census
 * on 2.0.15+23fd6633b91 in job-162 game 0, "Seat1's floating mana 5 -> 6"). The copy's pool holds the real pool's mana
 * from the moment the copy is made, and a Mana record names the player whose pool a refund gives it back to
 * (ManaRefundService groups the paying mana by Mana.getPlayer), so the copy's records name the copy's player. When they
 * named the real one, a cast the copy rolled back gave the real pool that mana: a Signet paid from the floating mana,
 * undone with the cast it paid for, refunded its {1} to the real pool.
 */
public class GameCopierManaPoolTest extends FlatMcTestBase {
    @BeforeMethod
    public void mainOne() {
        setUpGame();
        noDeadline();
        at(PhaseType.MAIN1, ai);
    }

    /**
     * The seat floats one {C} (from its tapped Sol Ring) beside an untapped Azorius Signet, with Thassa's Oracle in its
     * hand. In a copy the Oracle's {U}{U} is paid as far as it goes: the Signet's {1} from the floating {C}, then the
     * Signet's {U}; nothing makes the second {U}, so the cast is rolled back, the Signet undone and its {1} refunded.
     * That {C} goes back to the copy's pool, the real pool still holds its one mana, and nothing else the real-game
     * check reads has moved.
     */
    @Test
    public void aCastRolledBackInTheCopyRefundsTheCopysPool() {
        final Card ring = put("Sol Ring", ai, ZoneType.Battlefield);
        ring.setTapped(true);
        put("Azorius Signet", ai, ZoneType.Battlefield);
        final SpellAbility oracle = put("Thassa's Oracle", ai, ZoneType.Hand).getFirstSpellAbility();
        oracle.setActivatingPlayer(ai);
        ai.getManaPool().addMana(new Mana((byte) ManaAtom.COLORLESS, ring, null, ai));
        game.getAction().checkStateEffects(true);

        final RealGameSnapshot before = RealGameSnapshot.of(game);
        final GameSimulator sim = GameSimulator.forRollout(game, ai, false);
        final Player copy = sim.getSimulatedAiPlayer();
        assertEquals(copy.getManaPool().totalMana(), 1, "the copy's pool starts with the real pool's mana");
        assertFalse(sim.applySpellAbility(oracle), "nothing pays the Oracle's second {U}");
        assertEquals(ai.getManaPool().totalMana(), 1, "the real pool keeps its one mana");
        before.requireSame(game);
        assertEquals(copy.getManaPool().totalMana(), 1, "the copy's pool has its mana back");
        for (final Mana m : copy.getManaPool()) {
            assertSame(m.getPlayer(), copy, "a record in the copy's pool names the copy's player");
        }
    }
}
