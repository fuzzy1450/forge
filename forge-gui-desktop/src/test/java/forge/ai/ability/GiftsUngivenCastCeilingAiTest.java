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
 * <p>
 * Retry 1: with the AI's own item on top of the stack and no mustRespond mark, the same block declines
 * before G1, drawing nothing (getSpellAbilityToPlay drops every choice there but the first copy SA, so a
 * Gifts approval was never a cast); an opponent's item on top, or an own item the AI responds to
 * (Sensei's Divining Top's AIRespondsToOwnAbility), goes on to G1 as before.
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

    @Test
    public void declinesUnderItsOwnItemOnTheStackDrawingNothing() throws ReflectiveOperationException {
        Player ai = setup(4);
        castOntoStack(ai, "Runeclaw Bear");
        long before = MyRandom.callCount();
        AssertJUnit.assertEquals(AiPlayDecision.AnotherTime, canPlayAndPayFor(ai));
        AssertJUnit.assertEquals("the own-stack-top decline drew from MyRandom", before, MyRandom.callCount());
        // canPlaySa itself approves there, so the decline is the block's, after canPlaySa
        AiController aic = ((PlayerControllerAi) ai.getController()).getAi();
        AssertJUnit.assertEquals(AiPlayDecision.WillPlay, aic.canPlaySa(gifts(ai)));
    }

    @Test
    public void castsOverAnOpponentsItem() throws ReflectiveOperationException {
        Player ai = setup(4);
        castOntoStack(ai.getGame().getPlayers().get(0), "Runeclaw Bear");
        AssertJUnit.assertEquals(AiPlayDecision.WillPlay, canPlayAndPayFor(ai));
    }

    @Test
    public void castsOverAnOwnItemItRespondsTo() throws ReflectiveOperationException {
        Player ai = setup(4);
        Card top = addCard("Sensei's Divining Top", ai);
        SpellAbility look = null;
        for (SpellAbility ab : top.getSpellAbilities()) {
            if (ab.hasParam("AIRespondsToOwnAbility")) {
                look = ab;
            }
        }
        AssertJUnit.assertNotNull(look);
        look.setActivatingPlayer(ai);
        ai.getGame().getStack().add(look);
        SpellAbility onTop = ai.getGame().getStack().peekAbility();
        AssertJUnit.assertEquals(ai, onTop.getActivatingPlayer());
        AssertJUnit.assertTrue(onTop.hasParam("AIRespondsToOwnAbility"));
        AssertJUnit.assertEquals(AiPlayDecision.WillPlay, canPlayAndPayFor(ai));
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

    // `name`'s spell on the stack, cast by `caster` (unpaid: only the top of the stack matters here)
    private void castOntoStack(Player caster, String name) {
        Game game = caster.getGame();
        Card card = createCard(name, caster);
        card.setGameTimestamp(game.getNextTimestamp());
        game.getStackZone().add(card);
        SpellAbility spell = card.getFirstSpellAbility();
        spell.setActivatingPlayer(caster);
        game.getStack().add(spell);
        AssertJUnit.assertEquals(caster, game.getStack().peekAbility().getActivatingPlayer());
    }

    private SpellAbility gifts(Player ai) {
        Card gifts = null;
        for (Card c : ai.getCardsIn(ZoneType.Hand)) {
            if ("Gifts Ungiven".equals(c.getName())) {
                gifts = c;
            }
        }
        AssertJUnit.assertNotNull(gifts);
        SpellAbility sa = gifts.getFirstSpellAbility();
        sa.setActivatingPlayer(ai);
        return sa;
    }

    private AiPlayDecision canPlayAndPayFor(Player ai) throws ReflectiveOperationException {
        SpellAbility sa = gifts(ai);
        AiController aic = ((PlayerControllerAi) ai.getController()).getAi();
        // the regular-cast entry the playable list uses (private): canPlayFromHost, then canPlayAndPayForFace
        Method m = AiController.class.getDeclaredMethod("canPlayAndPayFor", SpellAbility.class);
        m.setAccessible(true);
        return (AiPlayDecision) m.invoke(aic, sa);
    }
}
