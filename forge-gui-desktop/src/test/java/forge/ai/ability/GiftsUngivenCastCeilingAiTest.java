package forge.ai.ability;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;

import forge.ai.AITest;
import forge.ai.AiController;
import forge.ai.AiPlayDecision;
import forge.ai.LobbyPlayerAi;
import forge.ai.PlayerControllerAi;
import forge.game.Game;
import forge.game.card.Card;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import forge.util.MyRandom;
import org.testng.AssertJUnit;
import org.testng.annotations.Test;

/**
 * Gifts Ungiven (dead-card batch 4, row 32, part 32B2). Once its sub-ability can target an opponent
 * (32A), canPlaySa approves it from Main 2 on, so an unpayable window would reach canPayCost's test
 * payment and its MyRandom draws where the stock refusal drew nothing. G1
 * (SpecialCardAi.CommanderCastCeiling.affordable) now refuses those windows in
 * AiController.canPlayAndPayForFace before canPayCost, drawing nothing; a payable window goes on as
 * before. The seat plays the Default profile, as the stored games do: its RESERVE_MANA_FOR_MAIN2_CHANCE
 * of 100 is what makes canPayCost's test payment draw (ComputerUtilMana.isManaSourceReserved), and a
 * profile-less seat reads 0 and never draws there.
 */
public class GiftsUngivenCastCeilingAiTest extends AITest {

    private static final List<String> NAMES = Arrays.asList("Runeclaw Bear", "Grizzly Bears", "Hill Giant",
            "Craw Wurm", "Serra Angel", "Shivan Dragon", "Llanowar Elves", "Counterspell");

    @Test
    public void refusesAnUnpayableWindowDrawingNothing() throws ReflectiveOperationException {
        Player ai = setup(2);
        long before = MyRandom.callCount();
        AssertJUnit.assertEquals(AiPlayDecision.CantAfford, canPlayAndPayFor(ai));
        AssertJUnit.assertEquals("the G1 refusal drew from MyRandom", before, MyRandom.callCount());
    }

    @Test
    public void castsInAPayableWindow() throws ReflectiveOperationException {
        AssertJUnit.assertEquals(AiPlayDecision.WillPlay, canPlayAndPayFor(setup(4)));
    }

    // ai: player 1 on the Default profile in its own Main 2, `islands` untapped Islands, Gifts in hand,
    // eight different names in the library (so canPlaySa's floor approves)
    private Player setup(int islands) {
        Game game = initAndCreateGame();
        Player ai = game.getPlayers().get(1);
        ((LobbyPlayerAi) ai.getLobbyPlayer()).setAiProfile("Default");
        for (String name : NAMES) {
            addCardToZone(name, ai, ZoneType.Library);
        }
        for (int i = 0; i < islands; i++) {
            addCard("Island", ai);
        }
        addCardToZone("Gifts Ungiven", ai, ZoneType.Hand);
        moveToMain2(game, ai);
        return ai;
    }

    private AiPlayDecision canPlayAndPayFor(Player ai) throws ReflectiveOperationException {
        Card gifts = null;
        for (Card c : ai.getCardsIn(ZoneType.Hand)) {
            if ("Gifts Ungiven".equals(c.getName())) {
                gifts = c;
            }
        }
        AssertJUnit.assertNotNull(gifts);
        SpellAbility sa = gifts.getFirstSpellAbility();
        sa.setActivatingPlayer(ai);
        AiController aic = ((PlayerControllerAi) ai.getController()).getAi();
        // the regular-cast entry the playable list uses (private): canPlayFromHost, then canPlayAndPayForFace
        Method m = AiController.class.getDeclaredMethod("canPlayAndPayFor", SpellAbility.class);
        m.setAccessible(true);
        return (AiPlayDecision) m.invoke(aic, sa);
    }
}
