package forge.ai;

import java.util.List;

import org.testng.Assert;
import org.testng.annotations.Test;

import com.google.common.collect.Lists;

import forge.game.Game;
import forge.game.ability.ApiType;
import forge.game.card.Card;
import forge.game.combat.Combat;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.trigger.Trigger;
import forge.game.trigger.TriggerType;
import forge.game.zone.ZoneType;

/**
 * The five cards the merge of upstream e5e2f900616 (2d339921360) moved from Pump to upstream's TargetOnly without a
 * conflict, asked as the game asks the AI (SpellApiToAi.Converter.get(sa).canPlayWithSubs for a spell, as
 * TargetOnlySameNameTieTest does; doTriggerNoCostWithSubs for Carpet of Flowers' trigger), each on a board where the
 * fork's hook and TargetOnlyAi's choice part ways. Through TargetOnlyAi, Breaking of the Fellowship reached
 * FightAi.canFight, which finds no creature of ours for an OppCtrl root (MissingNeededCards on every consult);
 * Domineering Will named an opponent, the lowest-life one, as the player who gains control, in any combat step; Legions
 * to Ashes and Maelstrom Pulse took the best opposing permanent without their evaluators' group rules; Carpet of
 * Flowers' trigger took the lowest-life opponent, not the one with the most Islands. Each test fails that way with the
 * scripts on TargetOnly and passes with their Pump lines.
 * <p>
 * The merge of upstream ca782d4e6ff (#12876) moved Aetherspouts' root from Pump to Bookkeeping without a conflict:
 * NothingAi refuses every Bookkeeping root it is asked to start (CantPlayAi), so the Aetherspouts hook at the end of
 * PumpAi.checkApiLogic's untargeted branch never ran. Its test fails that way with the script on Bookkeeping and passes
 * with its Pump line.
 */
public class ForkPumpRoutingTest extends AITest {

    // the AI's spell from its hand, its activator set, as getOriginalAndAltCostAbilities sets it
    private SpellAbility spell(final String name, final Player ai) {
        final Card card = addCardToZone(name, ai, ZoneType.Hand);
        final SpellAbility sa = card.getFirstSpellAbility();
        sa.setActivatingPlayer(ai);
        return sa;
    }

    private static AiAbilityDecision ask(final Player ai, final SpellAbility sa) {
        return SpellApiToAi.Converter.get(sa).canPlayWithSubs(ai, sa);
    }

    /** The AI's Main 1, an opponent with two Hill Giants (3/3, worth more than Breaking's floor): one deals 3 damage to
     *  the other, which kills it. The AI casts Breaking of the Fellowship with one Giant as the source and the other as
     *  the creature dealt the damage. */
    @Test
    public void breakingOfTheFellowshipTurnsALethalOpposingPairOnItself() {
        final Game game = initAndCreateGame();
        final Player ai = game.getPlayers().get(1);
        final Player opp = game.getPlayers().get(0);
        final Card giantA = addCard("Hill Giant", opp);
        final Card giantB = addCard("Hill Giant", opp);
        addCards("Mountain", 2, ai);
        final SpellAbility sa = spell("Breaking of the Fellowship", ai);

        final AiAbilityDecision decision = ask(ai, sa);
        Assert.assertTrue(decision.willingToPlay(), "the AI casts Breaking of the Fellowship: " + decision.decision());
        final Card source = sa.getTargets().getFirstTargetedCard();
        final SpellAbility damage = sa.getSubAbility();
        Assert.assertNotNull(damage, "the damage sub-ability");
        Assert.assertEquals(damage.getApi(), ApiType.DealDamage);
        final Card victim = damage.getTargets().getFirstTargetedCard();
        Assert.assertTrue(source == giantA && victim == giantB || source == giantB && victim == giantA,
                "one Hill Giant deals the damage to the other: source " + source + ", victim " + victim);
    }

    /** An opponent's declare attackers step, their Craw Wurm (6/4) attacking the AI and their Grizzly Bears home and
     *  untapped. The AI casts Domineering Will naming itself as the player who gains control, and takes the Bears to
     *  block the Wurm (6 damage absorbed, over the floor of 4). */
    @Test
    public void domineeringWillNamesTheAiInAnOpponentsDeclareAttackersStep() {
        final Game game = initAndCreateGame();
        final Player ai = game.getPlayers().get(1);
        final Player opp = game.getPlayers().get(0);
        final Card wurm = addCard("Craw Wurm", opp);
        final Card bears = addCard("Grizzly Bears", opp);
        addCards("Island", 4, ai);
        final SpellAbility sa = spell("Domineering Will", ai);
        attack(game, opp, ai, wurm, PhaseType.COMBAT_DECLARE_ATTACKERS);

        final AiAbilityDecision decision = ask(ai, sa);
        assertNamesNoOpponent(ai, sa, "in the opponent's declare attackers step");
        Assert.assertTrue(decision.willingToPlay(), "the AI casts Domineering Will: " + decision.decision());
        Assert.assertEquals(Lists.newArrayList(sa.getTargets().getTargetPlayers()), List.of(ai),
                "the AI is the player who gains control");
        final SpellAbility steal = sa.getSubAbility();
        Assert.assertNotNull(steal, "the gain-control sub-ability");
        Assert.assertEquals(steal.getApi(), ApiType.GainControl);
        Assert.assertEquals(Lists.newArrayList(steal.getTargets().getTargetCards()), List.of(bears),
                "the AI takes the Grizzly Bears to block the Wurm");
    }

    /** Domineering Will elsewhere: in the AI's own beginning of combat, and in an opponent's declare blockers step with
     *  their Craw Wurm attacking it. The AI does not cast it, and names no opponent as the player who gains control. */
    @Test
    public void domineeringWillIsRefusedInAnotherStepAndNeverNamesAnOpponent() {
        {
            final Game game = initAndCreateGame();
            final Player ai = game.getPlayers().get(1);
            final Player opp = game.getPlayers().get(0);
            addCard("Grizzly Bears", opp);
            addCards("Island", 4, ai);
            final SpellAbility sa = spell("Domineering Will", ai);
            game.getPhaseHandler().devModeSet(PhaseType.COMBAT_BEGIN, ai);

            final AiAbilityDecision decision = ask(ai, sa);
            assertNamesNoOpponent(ai, sa, "in the AI's own beginning of combat");
            Assert.assertFalse(decision.willingToPlay(),
                    "the AI casts Domineering Will in its own beginning of combat: " + decision.decision());
        }
        {
            final Game game = initAndCreateGame();
            final Player ai = game.getPlayers().get(1);
            final Player opp = game.getPlayers().get(0);
            final Card wurm = addCard("Craw Wurm", opp);
            addCard("Grizzly Bears", opp);
            addCards("Island", 4, ai);
            final SpellAbility sa = spell("Domineering Will", ai);
            attack(game, opp, ai, wurm, PhaseType.COMBAT_DECLARE_BLOCKERS);

            final AiAbilityDecision decision = ask(ai, sa);
            assertNamesNoOpponent(ai, sa, "in the opponent's declare blockers step");
            Assert.assertFalse(decision.willingToPlay(),
                    "the AI casts Domineering Will in the opponent's declare blockers step: " + decision.decision());
        }
    }

    /** The AI's Main 1, an opponent with four 1/1 Soldier tokens and a Grizzly Bears: the four tokens share a name and
     *  together are worth more than the Bears (Legions to Ashes' multi-for-one rule). The AI casts Legions to Ashes at a
     *  Soldier, where the best single permanent is the Bears. */
    @Test
    public void legionsToAshesTakesTheTokenArmyBeforeTheBestSingleCreature() {
        final Game game = initAndCreateGame();
        final Player ai = game.getPlayers().get(1);
        final Player opp = game.getPlayers().get(0);
        addCard("Grizzly Bears", opp);
        final List<Card> soldiers = addTokens("w_1_1_soldier", 4, opp);
        addCards("Plains", 2, ai);
        addCard("Swamp", ai);
        final SpellAbility sa = spell("Legions to Ashes", ai);

        final AiAbilityDecision decision = ask(ai, sa);
        Assert.assertTrue(decision.willingToPlay(), "the AI casts Legions to Ashes: " + decision.decision());
        final Card target = sa.getTargets().getFirstTargetedCard();
        Assert.assertTrue(soldiers.contains(target), "Legions to Ashes exiles the Soldier tokens, not " + target);
    }

    /** The AI's Main 1, an opponent with two Grizzly Bears and a Hill Giant: the two Bears share a name and together
     *  are worth more than the Giant, the best single creature. The AI casts Maelstrom Pulse at a Bears. */
    @Test
    public void maelstromPulseTakesTheNamedPairBeforeTheBestSingleCreature() {
        final Game game = initAndCreateGame();
        final Player ai = game.getPlayers().get(1);
        final Player opp = game.getPlayers().get(0);
        final Card bearsA = addCard("Grizzly Bears", opp);
        final Card bearsB = addCard("Grizzly Bears", opp);
        addCard("Hill Giant", opp);
        addCards("Swamp", 2, ai);
        addCard("Forest", ai);
        final SpellAbility sa = spell("Maelstrom Pulse", ai);

        final AiAbilityDecision decision = ask(ai, sa);
        Assert.assertTrue(decision.willingToPlay(), "the AI casts Maelstrom Pulse: " + decision.decision());
        final Card target = sa.getTargets().getFirstTargetedCard();
        Assert.assertTrue(target == bearsA || target == bearsB, "Maelstrom Pulse destroys the two Bears, not " + target);
    }

    /** A three-player game, Carpet of Flowers on the AI's battlefield at its main phase trigger: one opponent has two
     *  Islands and 20 life, the other no Island and 10 life. The trigger aims at the one with the Islands (the X it
     *  counts), where the lowest-life opponent has none. */
    @Test
    public void carpetOfFlowersAimsAtTheOpponentWithTheMostIslands() {
        final Game game = initAndCreateThreePlayerGame();
        final Player ai = game.getPlayers().get(1);
        final Player islands = game.getPlayers().get(0);
        final Player lowLife = game.getPlayers().get(2);
        Assert.assertTrue(islands.isOpponentOf(ai) && lowLife.isOpponentOf(ai), "both are the AI's opponents");
        addCards("Island", 2, islands);
        lowLife.setLife(10, null);
        final Card carpet = addCard("Carpet of Flowers", ai);
        SpellAbility trigger = null;
        for (final Trigger t : carpet.getTriggers()) {
            if (t.getMode() == TriggerType.Phase) {
                trigger = t.ensureAbility();
            }
        }
        Assert.assertNotNull(trigger, "Carpet of Flowers' main phase trigger");
        trigger.setActivatingPlayer(ai);

        final AiAbilityDecision decision =
                SpellApiToAi.Converter.get(trigger).doTriggerNoCostWithSubs(ai, trigger, false);
        Assert.assertTrue(decision.willingToPlay(), "the AI takes Carpet of Flowers' mana: " + decision.decision());
        Assert.assertSame(trigger.getTargets().getFirstTargetedPlayer(), islands,
                "the trigger counts the Islands of the opponent who has them");
    }

    /** An opponent's declare blockers step, their two Craw Wurms (6/4) attacking the AI unblocked at 10 life, and five
     *  untapped Islands of the AI's: the attack would leave it dead. The AI casts Aetherspouts (SpecialCardAi
     *  .Aetherspouts' danger rule). */
    @Test
    public void aetherspoutsAnswersALethalAttackInTheDeclareBlockersStep() {
        final Game game = initAndCreateGame();
        final Player ai = game.getPlayers().get(1);
        final Player opp = game.getPlayers().get(0);
        final Card wurmA = addCard("Craw Wurm", opp);
        final Card wurmB = addCard("Craw Wurm", opp);
        addCards("Island", 5, ai);
        ai.setLife(10, null);
        final SpellAbility sa = spell("Aetherspouts", ai);
        game.getPhaseHandler().devModeSet(PhaseType.COMBAT_DECLARE_BLOCKERS, opp);
        final Combat combat = new Combat(opp);
        for (final Card wurm : List.of(wurmA, wurmB)) {
            wurm.setSickness(false);
            wurm.setTapped(true);
            combat.addAttacker(wurm, ai);
        }
        game.getPhaseHandler().setCombat(combat);

        Assert.assertEquals(sa.getApi(), ApiType.Pump, "Aetherspouts' root is the fork's Pump line");
        final AiAbilityDecision decision = ask(ai, sa);
        Assert.assertTrue(decision.willingToPlay(), "the AI casts Aetherspouts: " + decision.decision());
    }

    // `attacker` attacks `defender` with `creature` in `step` of the attacker's turn
    private static void attack(final Game game, final Player attacker, final Player defender, final Card creature,
            final PhaseType step) {
        creature.setSickness(false);
        creature.setTapped(true);
        game.getPhaseHandler().devModeSet(step, attacker);
        final Combat combat = new Combat(attacker);
        combat.addAttacker(creature, defender);
        game.getPhaseHandler().setCombat(combat);
    }

    private static void assertNamesNoOpponent(final Player ai, final SpellAbility sa, final String when) {
        for (final Player p : sa.getTargets().getTargetPlayers()) {
            Assert.assertFalse(p.isOpponentOf(ai), "Domineering Will names an opponent " + when + ": " + p);
        }
    }
}
