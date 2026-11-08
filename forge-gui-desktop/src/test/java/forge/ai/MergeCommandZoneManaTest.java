package forge.ai;

import static org.testng.AssertJUnit.assertEquals;
import static org.testng.AssertJUnit.assertFalse;
import static org.testng.AssertJUnit.assertTrue;

import org.testng.annotations.Test;

import forge.game.Game;
import forge.game.ability.AbilityFactory;
import forge.game.card.Card;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;

/**
 * Upstream's Channel support (79f6fc2ec91, #12701) counts mana in the Command zone beside the battlefield. With
 * checkPlayable false, the "mana next turn" reading that many deciders ask (twelve dead-card hooks, ComboSignals,
 * hasEnoughManaSourcesToCast's callers), nothing asked whether the ability could be activated there, so a commander
 * with a mana ability of its own counted as mana while it waited in the command zone. The top-up (e2984100667) counts a
 * Command-zone source only while its ability can be activated there: Channel's effect (ActivationZone$ Command) does,
 * the waiting commander does not, as upstream's own comment says unrelated commanders keep their behaviour.
 */
public class MergeCommandZoneManaTest extends AITest {

    @Test
    public void aCommanderWaitingInTheCommandZoneIsNoManaButChannelsEffectIs() {
        final Game game = initAndCreateGame();
        final Player ai = game.getPlayers().get(1);
        ai.setLife(20, null);
        addCard("Forest", ai);
        final Card commander = addCardToZone("Gilanra, Caller of Wirewood", ai, ZoneType.Command);
        ai.addCommander(commander);
        for (final SpellAbility ma : commander.getManaAbilities()) {
            ma.setActivatingPlayer(ai);
            assertFalse("the premise: the commander's {T}: Add {G} cannot be activated from the command zone",
                    ma.canPlay());
        }

        assertEquals("the Forest alone: a commander waiting in the command zone is no mana next turn", 1,
                ComputerUtilMana.getAvailableManaEstimate(ai, false));
        assertFalse("nor a source next turn",
                ComputerUtilMana.getAvailableManaSources(ai, false).contains(commander));
        assertEquals("nor mana now", 1, ComputerUtilMana.getAvailableManaEstimate(ai, true));
        assertFalse("nor a source now", ComputerUtilMana.getAvailableManaSources(ai, true).contains(commander));

        // Channel's effect, "Pay 1 life: Add {C}" with ActivationZone$ Command, as EffectEffect grants it
        final Card channel = addCardToZone("Channel", ai, ZoneType.Command);
        final SpellAbility channelMana = AbilityFactory.getAbility(channel, "ABMana");
        channel.addSpellAbility(channelMana);
        channelMana.setActivatingPlayer(ai);

        assertTrue("Channel's effect is a source next turn, as it is now",
                ComputerUtilMana.getAvailableManaSources(ai, false).contains(channel));
        assertTrue(ComputerUtilMana.getAvailableManaSources(ai, true).contains(channel));
        assertEquals("next turn: the Forest and fifteen Channel activations keeping five life, the commander still none",
                16, ComputerUtilMana.getAvailableManaEstimate(ai, false));
        assertEquals("now: the same", 16, ComputerUtilMana.getAvailableManaEstimate(ai, true));
        assertFalse("and the commander is still no source",
                ComputerUtilMana.getAvailableManaSources(ai, false).contains(commander));
    }
}
