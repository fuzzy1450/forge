package forge.ai;

import java.util.Collections;

import org.testng.AssertJUnit;
import org.testng.annotations.Test;

import forge.ai.ability.ChangeTargetsAi;
import forge.ai.ability.MayDecisions;
import forge.game.Game;
import forge.game.card.Card;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.player.PlayerActionConfirmMode;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;

/**
 * ChangeTargetsAi.confirmAction after the merge of 2026-11-22 (RU-32's M07): two overrides met there, the fork's
 * Wild Ricochet answer (no to the optional retarget: PlayerControllerAi.chooseNewTargetsFor cannot choose new targets
 * for the original spell, the copy is the whole value) and upstream's MayDecisions.changeTargets for every other
 * ChangeTargets card; the resolver wrote one method, the fork's branch first. On the opponent's Lightning Bolt aimed at
 * the AI, upstream's answer is yes (the spell is an opponent's): Wild Ricochet still answers no, and Redirect, another
 * ChangeTargets card, answers what MayDecisions answers.
 */
public class WildRicochetConfirmTest extends AITest {

    /** The opponent's Lightning Bolt on the stack, aimed at {@code ai}. */
    private SpellAbility opponentsBolt(final Game game, final Player opp, final Player ai) {
        final Card bolt = createCard("Lightning Bolt", opp);
        bolt.setGameTimestamp(game.getNextTimestamp());
        game.getStackZone().add(bolt);
        final SpellAbility sa = bolt.getFirstSpellAbility();
        sa.setActivatingPlayer(opp);
        sa.getTargets().add(ai);
        game.getStack().add(sa);
        return sa;
    }

    /** {@code name}'s spell from the AI's hand, aimed at {@code target}. */
    private SpellAbility aimedAt(final Player ai, final String name, final SpellAbility target) {
        final SpellAbility sa = addCardToZone(name, ai, ZoneType.Hand).getFirstSpellAbility();
        sa.setActivatingPlayer(ai);
        sa.getTargets().add(target);
        return sa;
    }

    @Test
    public void wildRicochetDeclinesTheRetargetEveryOtherCardAsksUpstream() {
        final Game game = initAndCreateGame();
        final Player ai = game.getPlayers().get(1);
        final Player opp = game.getPlayers().get(0);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, opp);
        final SpellAbility bolt = opponentsBolt(game, opp, ai);
        final SpellAbility ricochet = aimedAt(ai, "Wild Ricochet", bolt);
        final SpellAbility redirect = aimedAt(ai, "Redirect", bolt);
        final ChangeTargetsAi changeTargets = new ChangeTargetsAi();

        AssertJUnit.assertTrue("upstream's answer for Wild Ricochet's target, an opponent's spell, is yes",
                MayDecisions.changeTargets(ai, ricochet));
        AssertJUnit.assertFalse("Wild Ricochet declines its optional retarget",
                changeTargets.confirmAction(ai, ricochet, PlayerActionConfirmMode.OptionalChoose, "", Collections.emptyMap()));
        AssertJUnit.assertTrue("Redirect at the same spell: upstream's answer, yes", MayDecisions.changeTargets(ai, redirect));
        AssertJUnit.assertEquals("Redirect's answer is upstream's", MayDecisions.changeTargets(ai, redirect),
                changeTargets.confirmAction(ai, redirect, PlayerActionConfirmMode.OptionalChoose, "", Collections.emptyMap()));
    }
}
