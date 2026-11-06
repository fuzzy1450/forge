package forge.ai.combo;

import static org.testng.AssertJUnit.assertEquals;
import static org.testng.AssertJUnit.assertNotNull;
import static org.testng.AssertJUnit.assertNotSame;
import static org.testng.AssertJUnit.assertSame;
import static org.testng.AssertJUnit.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.testng.annotations.Test;

import forge.ai.AITest;
import forge.ai.AiController;
import forge.ai.LobbyPlayerAi;
import forge.ai.PlayerControllerAi;
import forge.ai.SpecialCardAi;
import forge.ai.simulation.ComboPlan;
import forge.game.Game;
import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;

/**
 * The merge's rule R5 at AiController.chooseCardToHiddenOriginChangeZone: for a deck holding one of the fork's combo
 * lines and one of upstream's combos (a tutor storm), our own search takes its line's missing piece ahead of the
 * deck plan's pick (ComboPlan.choose), which upstream's code asked first.
 */
public class MergeR5PicksTest extends AITest {
    private static final String ORACLE_CONSULTATION =
            "V|742-1295|3|{U}{U}{B}|2,88|H:Demonic Consultation|H:Thassa's Oracle";
    private static final Object[] STORM_LIST = {
            "Ad Nauseam", 1, "Beseech the Mirror", 2, "Burning Wish", 2, "Chrome Mox", 4, "City of Traitors", 1,
            "Dark Ritual", 4, "Duress", 2, "Gaea's Will", 1, "Giant's Boulder", 4, "Infernal Tutor", 4,
            "Lion's Eye Diamond", 4, "Lotus Petal", 4, "Mox Opal", 4, "Skateboard", 1, "Song of Creation", 1,
            "Spire of Industry", 3, "Tendrils of Agony", 1, "Thoughtseize", 4, "Urza's Saga", 4, "Vault of Whispers", 4,
            "Veil of Summer", 2, "Wishclaw Talisman", 3 };

    private static Card named(final Player p, final ZoneType zone, final String name) {
        for (final Card c : p.getCardsIn(zone)) {
            if (c.getName().equals(name)) {
                return c;
            }
        }
        return null;
    }

    @Test(timeOut = 120_000)
    public void aLineOfOursTakesItsMissingPieceAheadOfATutorStormsPick() {
        final Game game = initAndCreateGame();
        final Player ai = game.getPlayers().get(1);
        ((LobbyPlayerAi) ai.getLobbyPlayer()).setAiProfile("Default");
        final AiController brain = ((PlayerControllerAi) ai.getController()).getAi();
        final List<String> rows = new ArrayList<>(
                List.of("F|2|S|Win the game", "F|88|H|Exile your library", ORACLE_CONSULTATION));
        brain.getComboTracker().useLines(ComboLines.claimed(ComboTable.parse(rows)));
        for (final String land : Arrays.asList("Island", "Island", "Swamp")) {
            addCard(land, ai);
        }
        addCardToZone("Thassa's Oracle", ai, ZoneType.Hand);
        for (int i = 0; i < STORM_LIST.length; i += 2) {
            for (int n = 0; n < (Integer) STORM_LIST[i + 1]; n++) {
                addCardToZone((String) STORM_LIST[i], ai, ZoneType.Library);
            }
        }
        final Card consultation = addCardToZone("Demonic Consultation", ai, ZoneType.Library);
        final Card petal = named(ai, ZoneType.Library, "Lotus Petal");
        final SpellAbility tutor = addCardToZone("Demonic Tutor", ai, ZoneType.Hand).getFirstSpellAbility();
        tutor.setActivatingPlayer(ai);
        assertNotNull(petal);
        final CardCollection fetch = new CardCollection(List.of(petal, consultation));
        final List<ZoneType> fromLibrary = List.of(ZoneType.Library);

        final ComboPlan combos = brain.deckPlan(ai).combos;
        assertTrue(combos.summary(), combos.has(ComboPlan.Kind.TUTOR_STORM));
        final Card planPick = combos.choose(ai, ZoneType.Hand, fromLibrary, fetch);
        assertSame("the harness: the deck plan would take the fast mana", petal, planPick);
        assertNotSame(consultation, planPick);

        assertSame(consultation, brain.chooseCardToHiddenOriginChangeZone(ZoneType.Hand, fromLibrary, tutor,
                new CardCollection(List.of(petal, consultation)), ai, ai));
        assertEquals(1, brain.getComboTracker().oneShort().size());
    }
    /** A loop line by its row's id, whatever its pieces (ComboLines.claim): Sol Ring in play and {@code piece} to find. */
    private static List<ComboLines.Line> lineOf(final String piece) {
        return ComboLines.claimed(ComboTable.parse(
                List.of("F|11|S|Infinite colorless mana", "V|129-4131|0||11|B:" + piece + "|B:Sol Ring")));
    }

    /**
     * ChangeZoneAi's Reshape filter comes before the fork's search pick, as it did before the merge: Reshape's owner
     * never fetches an artifact whose activated ability reaches DestroyAll (Boompile), not for a line either. Here a
     * test line wants Boompile: no shipped line has such a piece.
     */
    @Test(timeOut = 120_000)
    public void reshapesHarmFilterComesBeforeTheSearchPick() {
        final Game game = initAndCreateGame();
        final Player ai = game.getPlayers().get(1);
        ((LobbyPlayerAi) ai.getLobbyPlayer()).setAiProfile("Default");
        final AiController brain = ((PlayerControllerAi) ai.getController()).getAi();
        brain.getComboTracker().useLines(lineOf("Boompile"));
        addCard("Sol Ring", ai);
        addCards("Island", 6, ai);
        final Card boompile = addCardToZone("Boompile", ai, ZoneType.Library);
        final Card stone = addCardToZone("Mind Stone", ai, ZoneType.Library);
        fillLibrary(ai, 10);
        final SpellAbility reshape = addCardToZone("Reshape", ai, ZoneType.Hand).getFirstSpellAbility();
        reshape.setActivatingPlayer(ai);
        final List<ZoneType> fromLibrary = List.of(ZoneType.Library);

        assertTrue(SpecialCardAi.Reshape.isHarmful(boompile));
        assertSame("the harness: the line takes its piece from a raw list", boompile, ComboFetch.searchPick(reshape,
                ZoneType.Battlefield, new CardCollection(List.of(boompile, stone)), ai, ai));

        assertSame(stone, brain.chooseCardToHiddenOriginChangeZone(ZoneType.Battlefield, fromLibrary, reshape,
                new CardCollection(List.of(boompile, stone)), ai, ai));
    }

    /** The filter's own control: a piece it lets through is still the pick, ahead of the stock pick's Mind Stone. */
    @Test(timeOut = 120_000)
    public void reshapeStillFetchesAPieceThatDoesNoHarm() {
        final Game game = initAndCreateGame();
        final Player ai = game.getPlayers().get(1);
        ((LobbyPlayerAi) ai.getLobbyPlayer()).setAiProfile("Default");
        final AiController brain = ((PlayerControllerAi) ai.getController()).getAi();
        brain.getComboTracker().useLines(lineOf("Mana Vault"));
        addCard("Sol Ring", ai);
        addCards("Island", 6, ai);
        final Card vault = addCardToZone("Mana Vault", ai, ZoneType.Library);
        final Card stone = addCardToZone("Mind Stone", ai, ZoneType.Library);
        fillLibrary(ai, 10);
        final SpellAbility reshape = addCardToZone("Reshape", ai, ZoneType.Hand).getFirstSpellAbility();
        reshape.setActivatingPlayer(ai);
        final List<ZoneType> fromLibrary = List.of(ZoneType.Library);

        assertSame(vault, brain.chooseCardToHiddenOriginChangeZone(ZoneType.Battlefield, fromLibrary, reshape,
                new CardCollection(List.of(vault, stone)), ai, ai));

        brain.getComboTracker().useLines(List.of());
        assertSame("the harness: the stock pick", stone, brain.chooseCardToHiddenOriginChangeZone(ZoneType.Battlefield,
                fromLibrary, reshape, new CardCollection(List.of(vault, stone)), ai, ai));
    }
}
