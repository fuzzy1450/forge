package forge.ai;

import java.util.List;

import org.testng.Assert;
import org.testng.annotations.Test;

import forge.game.Game;
import forge.game.GameObject;
import forge.game.ability.ApiType;
import forge.game.card.Card;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.spellability.AbilitySub;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;

/**
 * Jund Charm's save (SpecialCardAi.JundCharm, line B): with an opponent's item on top of the stack, its counters mode
 * goes on the AI's best premium creature the item kills and two +1/+1 counters save. A cheap pre-check, threatens,
 * passes only a damage or -X/-X item, and it read the item's root API. Upstream's #12858 moved the roots of the bites
 * (Ram Through and the other PowerDmg scripts) from Pump to TargetOnly, a part that only chooses the creature that
 * deals the damage, the damage being the part after it, so a bite no longer raised the save. The pre-check reads the
 * item's first part that is not TargetOnly. Each board: the opponent's main phase, their spell on the stack (unpaid:
 * only the top of the stack is read), the AI with Serra Angel (4/4 flying vigilance, premium), an untapped Swamp,
 * Mountain and Forest and Jund Charm in hand, asked as the game asks it
 * (SpellApiToAi.Converter.get(sa).canPlayWithSubs).
 */
public class JundCharmBiteTest extends AITest {

    private static final class Board {
        Game game;
        Player ai;
        Player opp;
        Card angel;
        SpellAbility charm;
    }

    private Board board() {
        final Board b = new Board();
        b.game = initAndCreateGame();
        b.ai = b.game.getPlayers().get(1);
        b.opp = b.game.getPlayers().get(0);
        b.game.getPhaseHandler().devModeSet(PhaseType.MAIN1, b.opp);
        b.angel = addCard("Serra Angel", b.ai);
        addCard("Swamp", b.ai);
        addCard("Mountain", b.ai);
        addCard("Forest", b.ai);
        final Card jund = addCardToZone("Jund Charm", b.ai, ZoneType.Hand);
        b.charm = jund.getFirstSpellAbility();
        b.charm.setActivatingPlayer(b.ai);
        return b;
    }

    // `name` cast by the opponent and on top of the stack, each part of its chain aimed, root first, at the targets
    // given for it
    private SpellAbility opponentCasts(final Board b, final String name, final GameObject[]... parts) {
        final Card card = createCard(name, b.opp);
        card.setGameTimestamp(b.game.getNextTimestamp());
        b.game.getStackZone().add(card);
        final SpellAbility sa = card.getFirstSpellAbility();
        sa.setActivatingPlayer(b.opp);
        SpellAbility part = sa;
        for (final GameObject[] targets : parts) {
            Assert.assertNotNull(part, name + ": fewer parts than target lists");
            for (final GameObject t : targets) {
                part.getTargets().add(t);
            }
            part = part.getSubAbility();
        }
        b.game.getStack().add(sa);
        Assert.assertSame(b.game.getStack().peekAbility(), sa, name + " is on top of the stack");
        return sa;
    }

    private static AiAbilityDecision ask(final Board b) {
        return SpellApiToAi.Converter.get(b.charm).canPlayWithSubs(b.ai, b.charm);
    }

    private static void assertSaves(final Board b, final AiAbilityDecision decision, final String item) {
        Assert.assertTrue(decision.willingToPlay(), "the AI answers " + item + " with Jund Charm: " + decision.decision());
        final List<AbilitySub> modes = b.charm.getChosenList();
        Assert.assertNotNull(modes, "a chosen mode");
        Assert.assertEquals(modes.size(), 1, "one mode");
        Assert.assertEquals(modes.get(0).getApi(), ApiType.PutCounter, "the counters mode");
        Assert.assertSame(modes.get(0).getTargets().getFirstTargetedCard(), b.angel, "the counters go on Serra Angel");
    }

    /** The opponent's Ram Through (root TargetOnly since #12858): their Air Elemental deals damage equal to its power,
     *  4, to Serra Angel, which dies, and would not after +2/+2. The AI casts Jund Charm's counters mode on the Angel. */
    @Test
    public void anOpposingBiteRaisesTheSave() {
        final Board b = board();
        final Card elemental = addCard("Air Elemental", b.opp);
        final SpellAbility ram = opponentCasts(b, "Ram Through", new GameObject[] {elemental},
                new GameObject[] {b.angel});
        Assert.assertEquals(ram.getApi(), ApiType.TargetOnly, "Ram Through's root only chooses the creature");
        Assert.assertEquals(ram.getSubAbility().getApi(), ApiType.DealDamage, "its damage is the part after the root");

        assertSaves(b, ask(b), "Ram Through");
    }

    /** Unchanged: the opponent's Rending Volley, 4 damage to Serra Angel, a DealDamage root. The AI casts Jund Charm's
     *  counters mode on the Angel. */
    @Test
    public void anOpposingDirectDamageSpellStillRaisesTheSave() {
        final Board b = board();
        final SpellAbility volley = opponentCasts(b, "Rending Volley", new GameObject[] {b.angel});
        Assert.assertEquals(volley.getApi(), ApiType.DealDamage, "Rending Volley's root deals the damage");

        assertSaves(b, ask(b), "Rending Volley");
    }

    /** Unchanged: the opponent's The Fall of Kroog aimed at the AI, a TargetOnly root (target opponent) whose next part
     *  destroys a land; only the parts after that deal damage, 3 to the AI and 1 to each of its creatures, which kills
     *  Serra Angel with 3 damage marked and would not after +2/+2. The pre-check reads the Destroy, so the AI does not
     *  cast Jund Charm. */
    @Test
    public void aTargetOnlyRootedItemWhoseNextPartIsNoDamageRaisesNoSave() {
        final Board b = board();
        final Card forest = addCard("Forest", b.ai);
        b.angel.setDamage(3);
        final SpellAbility kroog = opponentCasts(b, "The Fall of Kroog", new GameObject[] {b.ai},
                new GameObject[] {forest});
        Assert.assertEquals(kroog.getApi(), ApiType.TargetOnly, "The Fall of Kroog's root only chooses the opponent");
        Assert.assertEquals(kroog.getSubAbility().getApi(), ApiType.Destroy, "its next part destroys a land");

        final AiAbilityDecision decision = ask(b);
        Assert.assertFalse(decision.willingToPlay(),
                "the AI answers The Fall of Kroog with Jund Charm: " + b.charm.getChosenList());
    }
}
