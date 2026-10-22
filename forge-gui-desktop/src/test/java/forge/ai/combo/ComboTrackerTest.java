package forge.ai.combo;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNotSame;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertSame;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.expectThrows;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import org.testng.annotations.Test;

import forge.ai.AITest;
import forge.ai.AiController;
import forge.ai.LobbyPlayerAi;
import forge.ai.PlayerControllerAi;
import forge.ai.combo.ComboTracker.Where;
import forge.card.CardStateName;
import forge.deck.Deck;
import forge.deck.DeckSection;
import forge.game.Game;
import forge.game.ability.AbilityKey;
import forge.game.card.Card;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import forge.util.MyRandom;

/** The per-AI combo tracker: which of piece 1's lines the AI's own deck holds, and where each piece is (spec
 *  2026-10-22-combo-aware-ai-core-design.md section 6.2). */
public class ComboTrackerTest extends AITest {
    private static final String WIN = "F|2|S|Win the game";
    private static final String EXILE = "F|88|H|Exile your library";
    private static final String ORACLE_CONSULTATION = "V|742-1295|3|{U}{U}{B}|2,88|H:Demonic Consultation|H:Thassa's Oracle";
    private static final String ORACLE_PACT = "V|1295-3093|4|{1}{U}{U}{B}|2|H:Tainted Pact|H:Thassa's Oracle";
    private static final String LAB_CONSULTATION = "V|742-2836|1|{B}|2,88|H:Demonic Consultation|B:Laboratory Maniac";

    private static final String ORACLE = "Thassa's Oracle";
    private static final String CONSULTATION = "Demonic Consultation";
    private static final String LAB = "Laboratory Maniac";

    // ten distinct nonland names (GiftsUngivenAiTest's): fillLibrary makes ten of one name
    private static final List<String> NAMES = Arrays.asList("Runeclaw Bear", "Grizzly Bears", "Hill Giant",
            "Craw Wurm", "Serra Angel", "Shivan Dragon", "Llanowar Elves", "Counterspell", "Divination", "Opt");

    private static AiController aiOf(final Player p) {
        return ((PlayerControllerAi) p.getController()).getAi();
    }

    private static List<ComboLines.Line> lines(final String... rows) {
        final List<String> all = new ArrayList<>(List.of(WIN, EXILE));
        all.addAll(Arrays.asList(rows));
        return ComboLines.claimed(ComboTable.parse(all));
    }

    private static List<String> ids(final List<ComboLines.Line> lines) {
        return lines.stream().map(l -> l.variant().id()).collect(Collectors.toList());
    }

    private static ComboLines.Line line(final String row) {
        return lines(row).get(0);
    }

    private static Deck deckOf(final Player p) {
        return p.getRegisteredPlayer().getDeck();
    }

    @Test
    public void matchesTheRegisteredDeck() {
        final Game game = initAndCreateGame();
        final Player ai = game.getPlayers().get(1);
        deckOf(ai).getMain().add(ORACLE, 1);
        deckOf(ai).getMain().add(CONSULTATION, 1);
        final ComboTracker t = aiOf(ai).getComboTracker();
        t.useLines(lines(ORACLE_CONSULTATION, ORACLE_PACT));

        assertEquals(ids(t.deckLines()), List.of("742-1295"));
        assertFalse(t.isPiece("Tainted Pact"));
        assertTrue(t.isPiece(ORACLE));
        assertTrue(t.isPiece(CONSULTATION));
        // the opponent's registered deck is its own copy (RegisteredPlayer copies the Deck AITest hands both seats)
        final ComboTracker theirs = aiOf(game.getPlayers().get(0)).getComboTracker();
        theirs.useLines(lines(ORACLE_CONSULTATION, ORACLE_PACT));
        assertEquals(theirs.deckLines(), List.of());
    }

    @Test
    public void fallsBackToOwnedCards() {
        final Game game = initAndCreateGame();
        final Player ai = game.getPlayers().get(1);
        addCardToZone(ORACLE, ai, ZoneType.Hand);
        addCardToZone(CONSULTATION, ai, ZoneType.Library);
        final ComboTracker t = aiOf(ai).getComboTracker();
        t.useLines(lines(ORACLE_CONSULTATION, ORACLE_PACT));

        assertEquals(ids(t.deckLines()), List.of("742-1295"));
    }

    @Test
    public void whereReadsEachZone() {
        final Game game = initAndCreateGame();
        final Player ai = game.getPlayers().get(1);
        addCardToZone(ORACLE, ai, ZoneType.Hand);
        final Card consultation = addCardToZone(CONSULTATION, ai, ZoneType.Library);
        addCard(LAB, ai);
        addCardToZone("Jace, Wielder of Mysteries", ai, ZoneType.Command);
        final ComboTracker t = aiOf(ai).getComboTracker();
        t.useLines(lines(ORACLE_CONSULTATION, LAB_CONSULTATION));

        assertEquals(t.where(ORACLE), Where.HAND);
        assertEquals(t.where(CONSULTATION), Where.LIBRARY);
        assertEquals(t.where(LAB), Where.BATTLEFIELD);
        assertEquals(t.where("Jace, Wielder of Mysteries"), Where.COMMAND);

        final Card inGraveyard = game.getAction().moveTo(ZoneType.Graveyard, consultation, null, AbilityKey.newMap());
        assertEquals(t.where(CONSULTATION), Where.GONE);
        game.getAction().moveTo(ZoneType.Exile, inGraveyard, null, AbilityKey.newMap());
        assertEquals(t.where(CONSULTATION), Where.GONE);
    }

    @Test
    public void missingAndReachable() {
        final Game game = initAndCreateGame();
        final Player ai = game.getPlayers().get(1);
        addCardToZone(ORACLE, ai, ZoneType.Hand);
        final Card consultation = addCardToZone(CONSULTATION, ai, ZoneType.Library);
        final ComboTracker t = aiOf(ai).getComboTracker();
        t.useLines(lines(ORACLE_CONSULTATION));
        final ComboLines.Line l = t.deckLines().get(0);

        assertEquals(t.missing(l), List.of(CONSULTATION));
        assertTrue(t.reachable(l));

        game.getAction().moveTo(ZoneType.Graveyard, consultation, null, AbilityKey.newMap());
        assertFalse(t.reachable(l));
    }

    @Test
    public void singletonLibrary() {
        final Game game = initAndCreateGame();
        final Player ai = game.getPlayers().get(1);
        for (final String name : NAMES) {
            addCardToZone(name, ai, ZoneType.Library);
        }
        final ComboTracker t = aiOf(ai).getComboTracker();
        assertTrue(t.singletonLibrary());

        addCardToZone("Island", ai, ZoneType.Library);
        assertTrue(t.singletonLibrary(), "one Island shares its name with nothing");
        addCardToZone("Island", ai, ZoneType.Library);
        assertFalse(t.singletonLibrary());
    }

    @Test
    public void noLinesMeansEmpty() {
        final Game game = initAndCreateGame();
        final Player ai = game.getPlayers().get(1);
        for (final String name : NAMES) {
            deckOf(ai).getMain().add(name, 1);
        }
        final ComboTracker t = aiOf(ai).getComboTracker();
        // the field's own lines, the shipped ones, then the test's
        assertEquals(t.deckLines(), List.of());
        t.useLines(lines(ORACLE_CONSULTATION, ORACLE_PACT, LAB_CONSULTATION));
        assertEquals(t.deckLines(), List.of());
        assertFalse(t.isPiece(ORACLE));
    }

    // ---- beyond the plan's six ----

    @Test
    public void theFieldHoldsTheShippedLinesAndMatchesOnFirstQuery() {
        final Game game = initAndCreateGame();
        final Player ai = game.getPlayers().get(1);
        deckOf(ai).getMain().add(ORACLE, 1);
        deckOf(ai).getMain().add(CONSULTATION, 1);
        final ComboTracker t = aiOf(ai).getComboTracker();
        assertSame(ComboTracker.of(ai), t);
        assertNotSame(ComboTracker.of(game.getPlayers().get(0)), t);
        assertNull(ComboTracker.of(null));

        // no useLines: the AiController built it over ComboLines.shipped(), whose 742-1295 this deck holds and whose
        // 1295-3093 (Tainted Pact) it does not
        final List<ComboLines.Line> first = t.deckLines();
        assertEquals(ids(first), List.of("742-1295"));
        // cached: a deck change after the match is not read again, until useLines drops the match
        deckOf(ai).getMain().add("Tainted Pact", 1);
        assertSame(t.deckLines(), first);
        t.useLines(ComboLines.shipped());
        assertEquals(ids(t.deckLines()), List.of("1295-3093", "742-1295"));
        expectThrows(UnsupportedOperationException.class, () -> t.deckLines().clear());
    }

    @Test
    public void theDeckIsItsMainSectionAndItsCommander() {
        final Game game = initAndCreateGame();
        final Player ai = game.getPlayers().get(1);
        deckOf(ai).getOrCreate(DeckSection.Commander).add(ORACLE, 1);
        deckOf(ai).getMain().add(CONSULTATION, 1);
        deckOf(ai).getOrCreate(DeckSection.Sideboard).add("Tainted Pact", 1);
        // owned cards are read only for an empty deck: this Laboratory Maniac does not complete 742-2836
        addCard(LAB, ai);
        final ComboTracker t = aiOf(ai).getComboTracker();
        t.useLines(lines(ORACLE_CONSULTATION, ORACLE_PACT, LAB_CONSULTATION));

        assertEquals(ids(t.deckLines()), List.of("742-1295"));
        assertFalse(t.isPiece(LAB));
    }

    @Test
    public void fallsBackWhenThePlayerIsNotRegistered() {
        final Game game = initAndCreateGame();
        // a player built as the engine builds one, its AiController and tracker included, that the game never
        // registered: getRegisteredPlayer() throws, as it does while that constructor runs, so the tracker reads the
        // cards the player has
        final Player stray = new LobbyPlayerAi("stray", null).createIngamePlayer(game, 99);
        addCardToZone(ORACLE, stray, ZoneType.Hand);
        addCardToZone(CONSULTATION, stray, ZoneType.Graveyard);
        expectThrows(IndexOutOfBoundsException.class, stray::getRegisteredPlayer);
        final ComboTracker t = ComboTracker.of(stray);
        t.useLines(lines(ORACLE_CONSULTATION, ORACLE_PACT));

        assertEquals(ids(t.deckLines()), List.of("742-1295"));
        assertEquals(new ComboTracker(stray, lines(ORACLE_CONSULTATION, ORACLE_PACT)).deckLines(), t.deckLines());
        assertEquals(t.where(CONSULTATION), Where.GONE);
    }

    @Test
    public void whereTakesTheFirstPlaceInOrder() {
        final Game game = initAndCreateGame();
        final Player ai = game.getPlayers().get(1);
        final ComboTracker t = aiOf(ai).getComboTracker();
        t.useLines(lines(ORACLE_CONSULTATION));
        addCardToZone(ORACLE, ai, ZoneType.Library);
        assertEquals(t.where(ORACLE), Where.LIBRARY);
        castOnStack(game, ai, ai, ORACLE);
        assertEquals(t.where(ORACLE), Where.STACK);
        addCardToZone(ORACLE, ai, ZoneType.Command);
        assertEquals(t.where(ORACLE), Where.COMMAND);
        addCardToZone(ORACLE, ai, ZoneType.Hand);
        assertEquals(t.where(ORACLE), Where.HAND);
        final Card inPlay = addCard(ORACLE, ai);
        assertEquals(t.where(ORACLE), Where.BATTLEFIELD);
        assertSame(t.find(ORACLE, ZoneType.Battlefield), inPlay);
    }

    @Test
    public void whereReadsOnlyOurs() {
        final Game game = initAndCreateGame();
        final Player ai = game.getPlayers().get(1);
        final Player opp = game.getPlayers().get(0);
        final ComboTracker t = aiOf(ai).getComboTracker();
        t.useLines(lines(ORACLE_CONSULTATION, LAB_CONSULTATION));
        // the opponent's cards, in each of the zones we read, are not ours
        addCard(LAB, opp);
        addCardToZone(ORACLE, opp, ZoneType.Hand);
        addCardToZone(ORACLE, opp, ZoneType.Command);
        addCardToZone(CONSULTATION, opp, ZoneType.Library);
        castOnStack(game, opp, opp, CONSULTATION);
        assertEquals(t.where(LAB), Where.GONE);
        assertEquals(t.where(ORACLE), Where.GONE);
        assertEquals(t.where(CONSULTATION), Where.GONE);
        // a spell we cast is ours whoever owns it; so is a permanent on our battlefield (one we control)
        castOnStack(game, ai, opp, CONSULTATION);
        assertEquals(t.where(CONSULTATION), Where.STACK);
        final Card stolen = createCard(LAB, opp);
        stolen.setGameTimestamp(game.getNextTimestamp());
        ai.getZone(ZoneType.Battlefield).add(stolen);
        assertEquals(t.where(LAB), Where.BATTLEFIELD);
    }

    @Test
    public void findTakesOurFirstCardOfThatName() {
        final Game game = initAndCreateGame();
        final Player ai = game.getPlayers().get(1);
        final Player opp = game.getPlayers().get(0);
        addCardToZone(CONSULTATION, opp, ZoneType.Library);
        addCardToZone("Island", ai, ZoneType.Library);
        final Card first = addCardToZone(CONSULTATION, ai, ZoneType.Library);
        addCardToZone(CONSULTATION, ai, ZoneType.Library);
        final Card inHand = addCardToZone(CONSULTATION, ai, ZoneType.Hand);
        final ComboTracker t = aiOf(ai).getComboTracker();

        assertSame(t.find(CONSULTATION, ZoneType.Library), first);
        assertSame(t.find(CONSULTATION, ZoneType.Hand), inHand);
        assertNull(t.find(CONSULTATION, ZoneType.Battlefield));
        assertNull(t.find(ORACLE, ZoneType.Library));
        assertEquals(ComboTracker.nameOf(first), CONSULTATION);
    }

    @Test
    public void nameOfReadsThePrintedCard() {
        final Game game = initAndCreateGame();
        final Player ai = game.getPlayers().get(1);
        final ComboTracker t = aiOf(ai).getComboTracker();
        // a transformed double-faced card goes by its back face's name in play; the table knows it by its front face
        final Card jace = addCard("Jace, Vryn's Prodigy", ai);
        jace.setState(CardStateName.Backside, true);
        assertEquals(jace.getName(), "Jace, Telepath Unbound");
        assertEquals(ComboTracker.nameOf(jace), "Jace, Vryn's Prodigy");
        assertEquals(t.where("Jace, Vryn's Prodigy"), Where.BATTLEFIELD);
        assertSame(t.find("Jace, Vryn's Prodigy", ZoneType.Battlefield), jace);
        assertEquals(t.where("Jace, Telepath Unbound"), Where.GONE);
    }

    @Test
    public void queriesDrawNoRandomNumbers() {
        final Game game = initAndCreateGame();
        final Player ai = game.getPlayers().get(1);
        addCardToZone(ORACLE, ai, ZoneType.Hand);
        addCardToZone(CONSULTATION, ai, ZoneType.Library);
        for (final String name : NAMES) {
            addCardToZone(name, ai, ZoneType.Library);
        }
        final ComboTracker t = aiOf(ai).getComboTracker();
        t.useLines(lines(ORACLE_CONSULTATION, ORACLE_PACT, LAB_CONSULTATION));
        final long draws = MyRandom.callCount();
        final ComboLines.Line l = t.deckLines().get(0);
        t.isPiece(ORACLE);
        t.where(LAB);
        t.missing(l);
        t.reachable(l);
        t.singletonLibrary();
        t.find(CONSULTATION, ZoneType.Library);
        assertEquals(MyRandom.callCount(), draws, "a tracker query drew");
        assertEquals(t.missing(line(LAB_CONSULTATION)), List.of(CONSULTATION));
        assertFalse(t.reachable(line(LAB_CONSULTATION)));
    }

    /** {@code name}, owned by {@code owner}, cast by {@code caster}: on the stack, nothing paid or resolved. */
    private Card castOnStack(final Game game, final Player caster, final Player owner, final String name) {
        final Card card = createCard(name, owner);
        card.setGameTimestamp(game.getNextTimestamp());
        game.getStackZone().add(card);
        final SpellAbility sa = card.getFirstSpellAbility();
        sa.setActivatingPlayer(caster);
        game.getStack().add(sa);
        return card;
    }
}
