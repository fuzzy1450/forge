package forge.ai.combo;

import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;

import java.util.List;
import java.util.Set;

import org.testng.annotations.Test;

import forge.ai.AITest;
import forge.ai.simulation.ComboCatalog;
import forge.game.Game;
import forge.game.card.Card;
import forge.game.player.Player;
import forge.game.zone.ZoneType;

/**
 * Upstream's #12858 gave the deck plan's tutor pick (DeckPlan.tutorPicks) a step of its own: a combo of its catalog
 * (ai-combos.txt) one card short fetches that card (ComboCatalog.missingPieces). The fork's combo program already
 * decides those fetches for every combo its table holds (ComboFetch, ahead of the deck plan in ChangeZoneAi), and when
 * it refuses one (danger, a denied line, a hoser, a search another player controls) it wants the stock pick with the
 * stock draws, which the new step overrode: Thassa's Oracle in hand and Demonic Consultation fetched whatever the
 * fork's lines said (ComboFetchAiTest, ComboTrackerTest). So the deck plan's catalog step leaves to the fork's combo
 * program each combo the table holds as a line of exactly those cards, and keeps every other one: Devoted Druid and
 * Quillspike, a pair the table holds no line of, is still the catalog's to fetch.
 */
public class CatalogOverlapTest extends AITest {

    @Test
    public void theTableHoldsOracleAndConsultationButNotDruidAndQuillspike() {
        assertTrue(CatalogOverlap.held(List.of(Set.of("thassa's oracle")), "demonic consultation"));
        assertFalse(CatalogOverlap.held(List.of(Set.of("devoted druid")), "quillspike"));
        assertFalse(CatalogOverlap.held(List.of(Set.of("demonic consultation")), "thassa's oracle, the second"),
                "a name the table does not know");
    }

    @Test
    public void aComboTheTableHoldsIsLeftToTheForkCombos() {
        final Game game = initAndCreateGame();
        final Player ai = game.getPlayers().get(1);
        addCardToZone("Thassa's Oracle", ai, ZoneType.Hand);
        final Card consultation = addCardToZone("Demonic Consultation", ai, ZoneType.Library);
        final List<Card> library = List.copyOf(ai.getCardsIn(ZoneType.Library));

        assertTrue(ComboCatalog.missingPieces(ai, library).contains(consultation), "upstream's catalog finds it");
        assertFalse(ComboCatalog.missingPieces(ai, library, CatalogOverlap::held).contains(consultation),
                "the deck plan leaves it to the fork's combo program");
    }

    @Test
    public void aComboTheTableDoesNotHoldIsStillTheCataloguesToFetch() {
        final Game game = initAndCreateGame();
        final Player ai = game.getPlayers().get(1);
        addCard("Devoted Druid", ai);
        final Card quillspike = addCardToZone("Quillspike", ai, ZoneType.Library);
        final List<Card> library = List.copyOf(ai.getCardsIn(ZoneType.Library));

        assertTrue(ComboCatalog.missingPieces(ai, library, CatalogOverlap::held).contains(quillspike));
    }
}
