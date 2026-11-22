package forge.deck;

import forge.ai.AITest;
import forge.card.CardRules;
import forge.deck.io.DeckSerializer;
import forge.item.PaperCard;
import forge.model.FModel;
import org.testng.annotations.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertTrue;

/**
 * A legendary Spacecraft with a station threshold that makes it a creature (CR 903.3: a P/T box) can be a
 * commander. Upstream's cce3395b313 dropped the PT line from Station scripts and tested for "Station$ True",
 * while every Station script writes a number ("Station$ 8"), so both EOC precons led by one (Counter
 * Intelligence: Inspirit, Flagship Vessel; World Shaper: Hearthhull, the Worldseed) lost their commander.
 */
public class SpacecraftCommanderTest extends AITest {

    private static PaperCard card(final String name) {
        final PaperCard card = FModel.getMagicDb().getCommonCards().getCard(name);
        assertNotNull(card, name);
        return card;
    }

    private static CardRules rules(final String name) {
        return card(name).getRules();
    }

    @Test
    public void legendarySpacecraftWithAStationCreatureThresholdCanBeCommander() {
        assertTrue(rules("Hearthhull, the Worldseed").canBeCommander());
        assertTrue(rules("Inspirit, Flagship Vessel").canBeCommander());
    }

    @Test
    public void legendaryNonStationArtifactThatIsNoCreatureCannotBeCommander() {
        final CardRules ring = rules("The One Ring");
        assertTrue(ring.getType().isLegendary());
        assertFalse(ring.canBeCreature());
        assertFalse(ring.canBeCommander());
    }

    @Test
    public void deckFileKeepsItsSpacecraftCommander() throws IOException {
        final File file = File.createTempFile("spacecraft-commander", ".dck");
        try {
            Files.write(file.toPath(), String.join("\n",
                    "[metadata]",
                    "Name=World Shaper test",
                    "[Commander]",
                    "1 Hearthhull, the Worldseed",
                    "[Main]",
                    "1 Sol Ring",
                    "10 Forest",
                    "").getBytes(StandardCharsets.UTF_8));
            final Deck loaded = DeckSerializer.fromFile(file);
            assertTrue(loaded.has(DeckSection.Commander), "the deck has a commander section");
            assertEquals(loaded.get(DeckSection.Commander).countByName("Hearthhull, the Worldseed"), 1);
            assertEquals(loaded.getMain().countByName("Hearthhull, the Worldseed"), 0);
        } finally {
            assertTrue(file.delete());
        }
    }
}
