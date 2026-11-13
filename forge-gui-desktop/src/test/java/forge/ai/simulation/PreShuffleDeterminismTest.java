package forge.ai.simulation;

import java.lang.reflect.Method;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

import forge.deck.CardPool;
import forge.game.Match;
import forge.game.card.Card;
import forge.game.player.Player;
import forge.game.zone.ZoneType;
import forge.item.PaperCard;
import forge.model.FModel;
import forge.util.MyRandom;

import org.testng.AssertJUnit;
import org.testng.annotations.Test;

/**
 * Zone preparation builds the library in the deck file's own order. The fork's
 * ItemPool is a LinkedHashMap (d12cadeb324), so a CardPool iterates in the order
 * its .dck file lists it, and Match.preparePlayerZone walks the pool as it
 * stands: upstream's #11285 sorted it by name there, and the fork takes that
 * sort back out. The pre-shuffle library is then a pure function of the deck
 * file on every JVM, upstream's goal, and a walk's one-card variant (the added
 * card on the cut card's line, mtglib/sim/bridge.py swap_in_place) builds its
 * champion's library with one position changed, so one seed shuffles the two
 * alike outside the swapped slot. A name sort would move the added card to its
 * sorted place and shift every card between the two names.
 */
public class PreShuffleDeterminismTest extends SimulationTest {

    /** A deck file's [Main] lines, in the file's order (not sorted). */
    private static final String[][] DECK = {
            {"Plains", "12"}, {"Island", "12"}, {"Runeclaw Bear", "4"},
            {"Grizzly Bears", "4"}, {"Lightning Bolt", "4"}, {"Counterspell", "4"},
            {"Sol Ring", "1"}, {"Fireball", "3"}, {"Shock", "4"},
            {"Giant Growth", "4"}, {"Llanowar Elves", "4"}, {"Divination", "4"},
    };

    private static List<String[]> deckLines() {
        List<String[]> lines = new ArrayList<>();
        Collections.addAll(lines, DECK);
        return lines;
    }

    /**
     * bridge.swap_in_place's rule: the first line naming {@code cut} loses one
     * copy where it stands and {@code added} goes right after it, so a singleton's
     * line becomes the added card's.
     */
    private static List<String[]> swapInPlace(List<String[]> lines, String cut, String added) {
        List<String[]> out = new ArrayList<>();
        boolean found = false;
        for (String[] line : lines) {
            if (!found && line[0].equals(cut)) {
                found = true;
                int n = Integer.parseInt(line[1]);
                if (n > 1) {
                    out.add(new String[] {line[0], String.valueOf(n - 1)});
                }
                out.add(new String[] {added, "1"});
            } else {
                out.add(line);
            }
        }
        AssertJUnit.assertTrue(cut + " is in the deck", found);
        return out;
    }

    private static CardPool pool(List<String[]> lines) {
        CardPool p = new CardPool();
        for (String[] line : lines) {
            PaperCard pc = FModel.getMagicDb().getCommonCards().getCard(line[0]);
            AssertJUnit.assertNotNull(line[0], pc);
            p.add(pc, Integer.parseInt(line[1]));
        }
        return p;
    }

    /** The lines' names, each repeated by its count, in the lines' order. */
    private static List<String> expanded(List<String[]> lines) {
        List<String> names = new ArrayList<>();
        for (String[] line : lines) {
            for (int i = Integer.parseInt(line[1]); i > 0; i--) {
                names.add(line[0]);
            }
        }
        return names;
    }

    private static List<Integer> differingPositions(List<String> a, List<String> b) {
        AssertJUnit.assertEquals(a.size(), b.size());
        List<Integer> at = new ArrayList<>();
        for (int i = 0; i < a.size(); i++) {
            if (!a.get(i).equals(b.get(i))) {
                at.add(i);
            }
        }
        return at;
    }

    private List<String> prepare(Player player, CardPool section, long seed) throws Exception {
        Method m = Match.class.getDeclaredMethod("preparePlayerZone",
                Player.class, ZoneType.class, CardPool.class, boolean.class);
        m.setAccessible(true);
        MyRandom.setRandom(new Random(seed));
        try {
            m.invoke(null, player, ZoneType.Library, section, true);
        } finally {
            MyRandom.setRandom(new SecureRandom());
        }
        List<String> names = new ArrayList<>();
        for (Card c : player.getZone(ZoneType.Library)) {
            names.add(c.getName());
        }
        return names;
    }

    @Test
    public void theSamePoolAndSeedGiveTheSameLibrary() throws Exception {
        Player p = initAndCreateGame().getPlayers().get(0);
        CardPool pool = pool(deckLines());

        List<String> first = prepare(p, pool, 7L);
        AssertJUnit.assertEquals(60, first.size());
        AssertJUnit.assertEquals(first, prepare(p, pool, 7L));
        // the same deck file read into a pool again
        AssertJUnit.assertEquals(first, prepare(p, pool(deckLines()), 7L));
    }

    @Test
    public void thePreShuffleLibraryIsThePoolsInsertionOrder() throws Exception {
        Player p = initAndCreateGame().getPlayers().get(0);
        List<String[]> forward = deckLines();
        List<String[]> reversed = deckLines();
        Collections.reverse(reversed);

        List<String> a = prepare(p, pool(forward), 7L);
        List<String> b = prepare(p, pool(reversed), 7L);

        AssertJUnit.assertEquals(expanded(forward), a);
        AssertJUnit.assertEquals(expanded(reversed), b);
        AssertJUnit.assertFalse("a reversed deck file builds its own library, not the forward one's", a.equals(b));
    }

    @Test
    public void anInPlaceOneCardSwapChangesExactlyOnePosition() throws Exception {
        Player p = initAndCreateGame().getPlayers().get(0);
        List<String[]> champion = deckLines();
        List<String> library = prepare(p, pool(champion), 7L);

        // a singleton: the added card takes the cut card's line
        int ring = expanded(champion).indexOf("Sol Ring");
        List<String> signet = prepare(p, pool(swapInPlace(champion, "Sol Ring", "Arcane Signet")), 7L);
        AssertJUnit.assertEquals(Collections.singletonList(ring), differingPositions(library, signet));
        AssertJUnit.assertEquals("Arcane Signet", signet.get(ring));

        // one copy of a 4-of: its line keeps its place with three, and the added
        // card goes right after it, at the cut card's last position
        int lastShock = expanded(champion).lastIndexOf("Shock");
        List<String> spike = prepare(p, pool(swapInPlace(champion, "Shock", "Lava Spike")), 7L);
        AssertJUnit.assertEquals(Collections.singletonList(lastShock), differingPositions(library, spike));
        AssertJUnit.assertEquals("Lava Spike", spike.get(lastShock));
    }
}
