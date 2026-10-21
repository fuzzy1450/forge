package forge.ai.ability;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;

import forge.ai.AITest;
import forge.ai.AiController;
import forge.ai.AiPlayDecision;
import forge.ai.LobbyPlayerAi;
import forge.ai.PlayerControllerAi;
import forge.ai.SpecialCardAi;
import forge.game.Game;
import forge.game.card.Card;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import forge.util.MyRandom;
import org.testng.AssertJUnit;
import org.testng.annotations.Test;

/**
 * Intuition (dead-card batch 4, row 9, part 9B2). Once its sub-ability can target an opponent (9A), canPlaySa
 * approves it from Main 2 on, so an unpayable window would reach canPayCost's test payment and its MyRandom
 * draws where the stock refusal drew nothing after canPlaySa. G1 (SpecialCardAi.CommanderCastCeiling.affordable)
 * now refuses those windows in AiController.canPlayAndPayForFace before canPayCost, drawing nothing; a payable
 * window goes on as before. The seat plays the Default profile, as the stored games do: its
 * RESERVE_MANA_FOR_MAIN2_CHANCE of 100 is what makes canPayCost's test payment draw
 * (ComputerUtilMana.isManaSourceReserved), and its INTUITION_ALTERNATIVE_LOGIC is what makes the stock fill inside
 * canPlaySa (SpecialCardAi.Intuition.considerMultiple) draw too: the refusal must add nothing to the fill's draws.
 */
public class IntuitionCastCeilingAiTest extends AITest {

    private static final List<String> NAMES = Arrays.asList("Counterspell", "Divination", "Opt", "Cancel",
            "Essence Scatter", "Mulldrifter", "Air Elemental", "Serra Angel");

    @Test
    public void refusesAnUnpayableWindowDrawingNothing() throws ReflectiveOperationException {
        // an all-land library: the stock fill finds no spell and draws nothing, so the whole consult must not draw
        Player ai = setup(2, false);
        AiController aic = ((PlayerControllerAi) ai.getController()).getAi();
        AssertJUnit.assertEquals(AiPlayDecision.WillPlay, aic.canPlaySa(intuition(ai)));
        long before = MyRandom.callCount();
        AssertJUnit.assertEquals(AiPlayDecision.CantAfford, canPlayAndPayFor(ai));
        AssertJUnit.assertEquals("the G1 refusal drew from MyRandom", before, MyRandom.callCount());
    }

    @Test
    public void refusesAnUnpayableWindowWithTheFillsDrawsOnly() throws ReflectiveOperationException {
        // a spell library: the fill inside canPlaySa draws (as in the stock path); the refusal adds nothing to it
        Player ai = setup(2, true);
        long f0 = MyRandom.callCount();
        SpecialCardAi.Intuition.considerMultiple(ai, intuition(ai));
        long fill = MyRandom.callCount() - f0;
        AssertJUnit.assertTrue("the stock fill drew nothing", fill > 0);
        long before = MyRandom.callCount();
        AssertJUnit.assertEquals(AiPlayDecision.CantAfford, canPlayAndPayFor(ai));
        AssertJUnit.assertEquals("the G1 refusal drew beyond the stock fill", fill, MyRandom.callCount() - before);
    }

    @Test
    public void castsInAPayableWindow() throws ReflectiveOperationException {
        AssertJUnit.assertEquals(AiPlayDecision.WillPlay, canPlayAndPayFor(setup(3, true)));
        AssertJUnit.assertEquals(AiPlayDecision.WillPlay, canPlayAndPayFor(setup(3, false)));
    }

    // ai: player 1 on the Default profile in its own Main 2, `islands` untapped Islands, Intuition in hand, and
    // eight library cards (NAMES, or eight Islands), so canPlaySa's floor approves
    private Player setup(int islands, boolean spells) {
        Game game = initAndCreateGame();
        Player ai = game.getPlayers().get(1);
        ((LobbyPlayerAi) ai.getLobbyPlayer()).setAiProfile("Default");
        for (String name : NAMES) {
            addCardToZone(spells ? name : "Island", ai, ZoneType.Library);
        }
        for (int i = 0; i < islands; i++) {
            addCard("Island", ai);
        }
        addCardToZone("Intuition", ai, ZoneType.Hand);
        moveToMain2(game, ai);
        return ai;
    }

    private SpellAbility intuition(Player ai) {
        Card intuition = null;
        for (Card c : ai.getCardsIn(ZoneType.Hand)) {
            if ("Intuition".equals(c.getName())) {
                intuition = c;
            }
        }
        AssertJUnit.assertNotNull(intuition);
        SpellAbility sa = intuition.getFirstSpellAbility();
        sa.setActivatingPlayer(ai);
        return sa;
    }

    private AiPlayDecision canPlayAndPayFor(Player ai) throws ReflectiveOperationException {
        SpellAbility sa = intuition(ai);
        AiController aic = ((PlayerControllerAi) ai.getController()).getAi();
        // the regular-cast entry the playable list uses (private): canPlayFromHost, then canPlayAndPayForFace
        Method m = AiController.class.getDeclaredMethod("canPlayAndPayFor", SpellAbility.class);
        m.setAccessible(true);
        return (AiPlayDecision) m.invoke(aic, sa);
    }
}
