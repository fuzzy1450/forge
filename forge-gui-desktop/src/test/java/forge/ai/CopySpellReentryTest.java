package forge.ai;

import static org.testng.AssertJUnit.assertEquals;
import static org.testng.AssertJUnit.assertNotNull;
import static org.testng.AssertJUnit.assertNull;
import static org.testng.AssertJUnit.assertTrue;

import java.util.List;

import org.testng.annotations.Test;

import com.google.common.collect.Lists;

import forge.game.Game;
import forge.game.ability.ApiType;
import forge.game.ability.effects.CharmEffect;
import forge.game.card.Card;
import forge.game.player.Player;
import forge.game.spellability.AbilitySub;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;

/**
 * CopySpellAbilityAi judges whether to copy the top of the stack by judging a copy of it, and judging that copy can
 * judge copying the top of the stack again: Return the Favor's own copy mode does. Such a loop judged the same copy
 * until the AI's thread overflowed its stack, ending the game in an Error. A copy of a spell already being judged on
 * the thread is now answered no; a copy judged once, or twice in turn, is judged as before.
 */
public class CopySpellReentryTest extends AITest {

    private static AiController aiOf(Player p) {
        return ((PlayerControllerAi) p.getController()).getAi();
    }

    /** The opponent's Lightning Bolt aimed at the AI, on the stack. */
    private SpellAbility opponentsBolt(Game game, Player opp, Player ai) {
        Card bolt = createCard("Lightning Bolt", opp);
        bolt.setGameTimestamp(game.getNextTimestamp());
        game.getStackZone().add(bolt);
        SpellAbility sa = bolt.getFirstSpellAbility();
        sa.setActivatingPlayer(opp);
        sa.getTargets().add(ai);
        game.getStack().add(sa);
        return sa;
    }

    @Test(timeOut = 120_000)
    public void aCopySpellAgainstAReturnTheFavorOnTheStackDoesNotJudgeItForever() {
        Game game = initAndCreateGame();
        Player ai = game.getPlayers().get(1);
        Player opp = game.getPlayers().get(0);
        addCards("Mountain", 3, ai);
        addCardToZone("Flare of Duplication", ai, ZoneType.Hand);
        fillLibrary(ai, 10);
        fillLibrary(opp, 10);
        SpellAbility bolt = opponentsBolt(game, opp, ai);
        // above it, the opponent's Return the Favor with its copy mode, copying the Bolt
        Card favor = createCard("Return the Favor", opp);
        favor.setGameTimestamp(game.getNextTimestamp());
        game.getStackZone().add(favor);
        SpellAbility cast = favor.getFirstSpellAbility();
        cast.setActivatingPlayer(opp);
        AbilitySub copyMode = CharmEffect.makePossibleOptions(cast).stream()
                .filter(s -> s.getApi() == ApiType.CopySpellAbility).findFirst().orElseThrow();
        copyMode.getTargets().add(bolt);
        CharmEffect.chainAbilities(cast, Lists.newArrayList(copyMode));
        game.getStack().add(cast);

        // Flare judges a copy of Return the Favor, whose copy mode judges copying the top of the stack: that same
        // Return the Favor, and so on. Answered no inside, the copy has nothing it is willing to do, so the AI does
        // not copy it, as CopySpellAbilityAi already declines to copy a spell whose own ability is a copy
        assertNull("the AI should not copy a Return the Favor whose copy it cannot judge",
                aiOf(ai).chooseSpellAbilityToPlay());
    }

    @Test(timeOut = 120_000)
    public void aCopySpellStillCopiesAnOpponentsSpell() {
        Game game = initAndCreateGame();
        Player ai = game.getPlayers().get(1);
        Player opp = game.getPlayers().get(0);
        addCards("Mountain", 3, ai);
        addCardToZone("Flare of Duplication", ai, ZoneType.Hand);
        opponentsBolt(game, opp, ai);

        // one judgment of one copy, nothing nested: the guard never answers, and Flare copies the Bolt as before
        List<SpellAbility> choice = aiOf(ai).chooseSpellAbilityToPlay();
        assertNotNull("Flare of Duplication should copy the opponent's Lightning Bolt", choice);
        assertEquals("Flare of Duplication", choice.get(0).getHostCard().getName());
    }

    @Test(timeOut = 120_000)
    public void judgingTheSameCopyTwiceInTurnAnswersTheSameBothTimes() {
        Game game = initAndCreateGame();
        Player ai = game.getPlayers().get(1);
        Player opp = game.getPlayers().get(0);
        addCards("Mountain", 3, ai);
        Card flare = addCardToZone("Flare of Duplication", ai, ZoneType.Hand);
        opponentsBolt(game, opp, ai);
        SpellAbility sa = flare.getFirstSpellAbility();
        sa.setActivatingPlayer(ai);

        // the same question twice on one thread: a judgment that kept the Bolt after it ended would answer no the
        // second time, as if it were still judging
        assertTrue(SpellApiToAi.Converter.get(sa).canPlayWithSubs(ai, sa).willingToPlay());
        sa.resetTargets();
        assertTrue(SpellApiToAi.Converter.get(sa).canPlayWithSubs(ai, sa).willingToPlay());
    }
}
