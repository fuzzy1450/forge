package forge.game.card;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.testng.AssertJUnit;
import org.testng.annotations.Test;

import forge.StaticData;
import forge.ai.AITest;
import forge.card.CardRules;
import forge.card.CardStateName;
import forge.card.ICardFace;
import forge.game.Game;
import forge.game.player.Player;

/**
 * Collections keyed by CardStateName iterate in the enum's declaration order. An enum constant's hashCode is an
 * identity hash that changes from one JVM launch to the next, so a HashSet or HashMap of states iterated in a
 * per-JVM order, and one seed could play different games in different JVMs (the same hazard as the four card
 * traits' hashCodes, MTG_DeckMaker stored game 973410).
 */
public class EnumOrderTest extends AITest {

    /** A Room with both doors locked offers the left door first: UnlockDoorEffect hands the AI this order and
     *  UnlockDoorAi takes the first door, or the first of its best-ranked doors. */
    @Test
    public void lockedRoomsIterateLeftDoorFirst() {
        Game game = initAndCreateGame();
        Player p = game.getPlayers().get(0);
        Card room = createCard("Bottomless Pool // Locker Room", p);
        Set<CardStateName> locked = room.getLockedRooms();
        AssertJUnit.assertTrue(locked.getClass().getName(), locked instanceof EnumSet);
        AssertJUnit.assertEquals(List.of(CardStateName.LeftSplit, CardStateName.RightSplit), new ArrayList<>(locked));
    }

    /** A Specialize card's faces are read in color order, so their traits take their ids in the same order. */
    @Test
    public void specializeFacesIterateInColorOrder() {
        initAndCreateGame();
        CardRules rules = StaticData.instance().getCommonCards().getRules("Lae'zel, Githyanki Warrior", false);
        Map<CardStateName, ICardFace> parts = rules.getSpecializeParts();
        AssertJUnit.assertTrue(parts.getClass().getName(), parts instanceof EnumMap);
        AssertJUnit.assertEquals(List.of(CardStateName.SpecializeW, CardStateName.SpecializeU, CardStateName.SpecializeB,
                CardStateName.SpecializeR, CardStateName.SpecializeG), new ArrayList<>(parts.keySet()));
    }
}
