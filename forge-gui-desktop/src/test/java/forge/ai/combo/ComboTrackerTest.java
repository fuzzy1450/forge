package forge.ai.combo;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertNotSame;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertSame;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.expectThrows;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.stream.Collectors;

import org.testng.annotations.Test;

import forge.ai.AITest;
import forge.ai.AiController;
import forge.ai.LobbyPlayerAi;
import forge.ai.PlayerControllerAi;
import forge.ai.ability.ChangeZoneAi;
import forge.ai.combo.ComboTracker.Where;
import forge.card.CardStateName;
import forge.deck.Deck;
import forge.deck.DeckSection;
import forge.game.Game;
import forge.game.ability.AbilityKey;
import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import forge.gamesimulationtests.util.LobbyPlayerForTests;
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
    private static final String DOOM = "Doctor Doom, Unrivaled";

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
        // an opponent's response on top does not hide our item under it: every item is read, not the top one alone
        castOnStack(game, opp, opp, "Grizzly Bears");
        assertEquals(game.getStack().size(), 3);
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

    @Test
    public void missingAndReachableReadEveryPlaceAPieceCanBe() {
        final Game game = initAndCreateGame();
        final Player ai = game.getPlayers().get(1);
        // the library holds Oracle above Consultation, the reverse of the line's own order
        final Card oracleInLibrary = addCardToZone(ORACLE, ai, ZoneType.Library);
        addCardToZone(CONSULTATION, ai, ZoneType.Library);
        final ComboTracker t = aiOf(ai).getComboTracker();
        t.useLines(lines(ORACLE_CONSULTATION));
        final ComboLines.Line l = t.deckLines().get(0);

        // both only in the library: both missing, in the line's order (the emptier, then the winner), not the library's
        assertEquals(libraryNames(ai), List.of(ORACLE, CONSULTATION));
        assertEquals(l.names(), List.of(CONSULTATION, ORACLE));
        assertEquals(t.missing(l), List.of(CONSULTATION, ORACLE));
        assertTrue(t.reachable(l));

        // Oracle in each other place we can use it from, one at a time: reachable there, and not missing
        ai.getZone(ZoneType.Library).remove(oracleInLibrary);
        final Card inHand = addCardToZone(ORACLE, ai, ZoneType.Hand);
        assertEquals(t.where(ORACLE), Where.HAND);
        assertEquals(t.missing(l), List.of(CONSULTATION), "a piece in our hand is not missing");
        assertTrue(t.reachable(l), "a piece in our hand is reachable");

        ai.getZone(ZoneType.Hand).remove(inHand);
        final Card inPlay = addCard(ORACLE, ai);
        assertEquals(t.where(ORACLE), Where.BATTLEFIELD);
        assertEquals(t.missing(l), List.of(CONSULTATION), "a piece on our battlefield is not missing");
        assertTrue(t.reachable(l), "a piece on our battlefield is reachable");

        ai.getZone(ZoneType.Battlefield).remove(inPlay);
        final Card inCommand = addCardToZone(ORACLE, ai, ZoneType.Command);
        assertEquals(t.where(ORACLE), Where.COMMAND);
        assertEquals(t.missing(l), List.of(CONSULTATION), "a piece in our command zone is not missing");
        assertTrue(t.reachable(l), "a piece in our command zone is reachable");

        ai.getZone(ZoneType.Command).remove(inCommand);
        castOnStack(game, ai, ai, ORACLE);
        assertEquals(t.where(ORACLE), Where.STACK);
        assertEquals(t.missing(l), List.of(CONSULTATION), "a piece we have on the stack is not missing");
        assertTrue(t.reachable(l), "a piece we have on the stack is reachable");
    }

    @Test
    public void singletonLibraryFindsAPairWhereverItsCardsLie() {
        final Game game = initAndCreateGame();
        final Player ai = game.getPlayers().get(1);
        final ComboTracker t = aiOf(ai).getComboTracker();
        // a pair with another card between them, the first of the pair on top
        addCardToZone("Island", ai, ZoneType.Library);
        addCardToZone("Grizzly Bears", ai, ZoneType.Library);
        assertTrue(t.singletonLibrary());
        addCardToZone("Island", ai, ZoneType.Library);
        assertEquals(libraryNames(ai), List.of("Island", "Grizzly Bears", "Island"));
        assertFalse(t.singletonLibrary(), "two Islands apart");

        // the pair on top, above ten distinct names: the top card is read too
        ai.getZone(ZoneType.Library).removeAllCards(true);
        addCardToZone("Island", ai, ZoneType.Library);
        addCardToZone("Island", ai, ZoneType.Library);
        for (final String name : NAMES) {
            addCardToZone(name, ai, ZoneType.Library);
        }
        assertEquals(libraryNames(ai).subList(0, 3), List.of("Island", "Island", NAMES.get(0)));
        assertEquals(libraryNames(ai).size(), 12);
        assertFalse(t.singletonLibrary(), "two Islands on top");
    }

    @Test
    public void whereCountsAnAbilityWeActivatedOnTheStack() {
        final Game game = initAndCreateGame();
        final Player ai = game.getPlayers().get(1);
        final Player opp = game.getPlayers().get(0);
        final ComboTracker t = aiOf(ai).getComboTracker();
        // Doctor Doom's {T} ability on the stack after Doom itself left play (killed in response): its host is in none
        // of our zones, so the ability is the only place it is. The opponent's activation of a Doom is not ours.
        activateOnStack(game, opp, DOOM);
        assertFalse(game.getStack().peek().isSpell());
        assertEquals(t.where(DOOM), Where.GONE);
        final Card host = activateOnStack(game, ai, DOOM);
        assertFalse(game.getStack().peek().isSpell());
        assertSame(game.getStack().peek().getSourceCard(), host);
        assertNull(t.find(DOOM, ZoneType.Battlefield));
        assertEquals(t.where(DOOM), Where.STACK);
    }

    @Test
    public void ofIsNullForAPlayerNoAiDecidesFor() {
        final Game game = initAndCreateGame();
        // a player the simulation tests' scripted controller decides for, and a bare one with no controller at all
        final Player scripted = new LobbyPlayerForTests("scripted", null).createIngamePlayer(game, 98);
        assertNotNull(scripted.getController());
        assertFalse(scripted.getController() instanceof PlayerControllerAi);
        assertNull(ComboTracker.of(scripted));
        final Player bare = new Player("bare", game, 97);
        assertNull(bare.getController());
        assertNull(ComboTracker.of(bare));
    }

    // ---- follow-up F1 (tracker scope and safety) ----

    @Test
    public void ofIsNullForAControlledPlayer() {
        final Game game = initAndCreateGame();
        final Player ai = game.getPlayers().get(1);
        final Player opp = game.getPlayers().get(0);
        final ComboTracker ours = aiOf(ai).getComboTracker();
        ours.useLines(lines(ORACLE_CONSULTATION));
        for (final String land : List.of("Island", "Island", "Swamp")) {
            addCard(land, ai);
        }
        addCardToZone(ORACLE, ai, ZoneType.Hand);
        final Card consultation = addCardToZone(CONSULTATION, ai, ZoneType.Library);
        for (final String name : NAMES) {
            addCardToZone(name, ai, ZoneType.Library);
        }
        final SpellAbility tutor = addCardToZone("Demonic Tutor", ai, ZoneType.Hand).getFirstSpellAbility();
        tutor.setActivatingPlayer(ai);
        assertSame(ComboTracker.of(ai), ours);
        assertSame(ComboFetch.searchPick(tutor, ZoneType.Hand, library(ai), ai, ai), consultation);

        // Opposition Agent's search as ChangeZoneEffect runs it (the searcher's addController, with the Agent's
        // controller as the master), as a mind-slave turn does: a fresh PlayerControllerAi decides for us, whose own
        // tracker reads our deck and would chase our line for the master, whose Agent exiles what we find
        final long timestamp = game.getNextTimestamp();
        ai.addController(timestamp, opp);
        assertTrue(ai.isControlled());
        assertSame(ai.getControllingPlayer(), opp);
        assertTrue(ai.getController() instanceof PlayerControllerAi);
        final ComboTracker fresh = aiOf(ai).getComboTracker();
        assertNotSame(fresh, ours);

        // the chooser makes the stock pick with the stock draws, whatever lines the master's fresh tracker holds
        fresh.useLines(List.of());
        MyRandom.setRandom(new Random(7));
        long before = MyRandom.callCount();
        final Card stock = ChangeZoneAi.chooseCardToHiddenOriginChangeZone(ZoneType.Hand,
                List.of(ZoneType.Library), tutor, library(ai), ai, ai);
        final long stockDraws = MyRandom.callCount() - before;
        assertNotSame(stock, consultation, "the stock pick is the piece itself: this board tells nothing");
        fresh.useLines(lines(ORACLE_CONSULTATION));
        MyRandom.setRandom(new Random(7));
        before = MyRandom.callCount();
        assertSame(ChangeZoneAi.chooseCardToHiddenOriginChangeZone(ZoneType.Hand, List.of(ZoneType.Library), tutor,
                library(ai), ai, ai), stock, "the answer moved");
        assertEquals(MyRandom.callCount() - before, stockDraws, "the draws moved");

        assertNull(ComboTracker.of(ai));
        assertNull(ComboFetch.searchPick(tutor, ZoneType.Hand, library(ai), ai, ai));

        // control ends: our own tracker answers again
        ai.removeController(timestamp);
        assertFalse(ai.isControlled());
        assertSame(ComboTracker.of(ai), ours);
        assertSame(ComboFetch.searchPick(tutor, ZoneType.Hand, library(ai), ai, ai), consultation);
    }

    @Test
    public void protectsOnlyOurOwnCards() {
        final Game game = initAndCreateGame();
        final Player ai = game.getPlayers().get(1);
        final Player opp = game.getPlayers().get(0);
        final ComboTracker t = aiOf(ai).getComboTracker();
        t.useLines(lines(ORACLE_CONSULTATION, LAB_CONSULTATION));
        for (final String land : List.of("Island", "Island", "Swamp", "Swamp")) {
            addCard(land, ai);
        }
        final Card ours = addCardToZone(ORACLE, ai, ZoneType.Hand);
        final Card consultation = addCardToZone(CONSULTATION, ai, ZoneType.Library);
        final Card ownLab = addCard(LAB, ai);
        final Card theirs = addCardToZone(ORACLE, opp, ZoneType.Hand);
        // a Laboratory Maniac the opponent owns that we control (stolen), and a Thassa's Oracle we own that they
        // control, each on its controller's battlefield
        final Card stolen = createCard(LAB, opp);
        stolen.setGameTimestamp(game.getNextTimestamp());
        stolen.setController(ai, game.getNextTimestamp());
        ai.getZone(ZoneType.Battlefield).add(stolen);
        final Card lent = createCard(ORACLE, ai);
        lent.setGameTimestamp(game.getNextTimestamp());
        lent.setController(opp, game.getNextTimestamp());
        opp.getZone(ZoneType.Battlefield).add(lent);
        final Card buried = addCardToZone(ORACLE, ai, ZoneType.Graveyard);
        final Card commanded = addCardToZone(ORACLE, ai, ZoneType.Command);
        final Card cast = castOnStack(game, ai, ai, CONSULTATION);
        // the stack's state check left each permanent on its controller's battlefield
        assertSame(stolen.getZone(), ai.getZone(ZoneType.Battlefield));
        assertSame(lent.getZone(), opp.getZone(ZoneType.Battlefield));
        assertEquals(t.where(ORACLE), Where.HAND);
        assertEquals(ids(t.live()), List.of("742-1295", "742-2836"));

        assertTrue(t.protects(ours), "our Thassa's Oracle in our hand");
        assertFalse(t.protects(theirs), "the opponent's Thassa's Oracle in the opponent's hand");
        assertTrue(t.protects(consultation), "ours in our library");
        assertTrue(t.protects(ownLab), "ours on our battlefield");
        assertTrue(t.protects(commanded), "ours in our command zone");
        assertFalse(t.protects(stolen), "the opponent's, on our battlefield");
        assertFalse(t.protects(lent), "ours, on the opponent's battlefield");
        assertFalse(t.protects(buried), "ours, in our graveyard");
        assertFalse(t.protects(cast), "ours, on the stack");
        assertFalse(t.view().protects(theirs), "the view reads the owner as the tracker does");
    }

    @Test
    public void nearestPrefersTheFewestMissing() {
        final Game game = initAndCreateGame();
        final Player ai = game.getPlayers().get(1);
        final ComboTracker t = aiOf(ai).getComboTracker();
        // Consultation's line first in the source and first by the one-short rank (its pieces still to cast cost 3,
        // Pact's 4): only the count of missing pieces puts Pact's line ahead of it
        t.useLines(lines(ORACLE_CONSULTATION, ORACLE_PACT));
        for (final String land : List.of("Island", "Island", "Swamp", "Swamp")) {
            addCard(land, ai);
        }
        final Card oracle = addCardToZone(ORACLE, ai, ZoneType.Hand);
        final Card pact = addCardToZone("Tainted Pact", ai, ZoneType.Hand);
        addCardToZone(CONSULTATION, ai, ZoneType.Library);
        for (final String name : NAMES) {
            addCardToZone(name, ai, ZoneType.Library);
        }
        assertEquals(ids(t.live()), List.of("742-1295", "1295-3093"));
        final ComboLines.Line consultationLine = t.live().get(0);
        final ComboLines.Line pactLine = t.live().get(1);
        assertEquals(t.missing(consultationLine), List.of(CONSULTATION));
        assertEquals(t.missing(pactLine), List.of());
        assertEquals(t.costNow(consultationLine), 3);
        assertEquals(t.costNow(pactLine), 4);
        assertSame(t.nearest(), pactLine, "nothing missing beats one short");
        assertSame(t.view().nearest(), pactLine);

        // both one short, Pact's line first in the source now: the one-short ranking decides
        t.useLines(lines(ORACLE_PACT, ORACLE_CONSULTATION));
        game.getAction().moveTo(ZoneType.Library, pact, null, AbilityKey.newMap());
        assertEquals(ids(t.live()), List.of("1295-3093", "742-1295"));
        assertEquals(ids(t.oneShort()), List.of("742-1295", "1295-3093"));
        assertSame(t.nearest(), t.oneShort().get(0));
        assertEquals(t.nearest().variant().id(), "742-1295");

        // nothing live: Oracle gone to the graveyard; no line at all
        game.getAction().moveTo(ZoneType.Graveyard, oracle, null, AbilityKey.newMap());
        assertEquals(t.live(), List.of());
        assertNull(t.nearest());
        t.useLines(List.of());
        assertNull(t.nearest());
    }

    @Test
    public void nearestBreaksATieByTheOneShortRankInEitherSourceOrder() {
        // both lines one short (Oracle in hand, Pact and Consultation in the library): Consultation's pieces still to
        // cast cost 3 and Pact's 4, so Consultation's line is the nearest whether it comes first in the source or last
        for (final boolean consultationFirst : new boolean[] {true, false}) {
            final String order = "Consultation's line first in the source: " + consultationFirst;
            final Game game = initAndCreateGame();
            final Player ai = game.getPlayers().get(1);
            final ComboTracker t = nearestBoard(ai, consultationFirst, ORACLE_CONSULTATION, List.of(ORACLE),
                    List.of("Tainted Pact", CONSULTATION));
            assertEquals(ids(t.live()), consultationFirst ? List.of("742-1295", "1295-3093")
                    : List.of("1295-3093", "742-1295"), order);
            assertEquals(t.missing(byId(t.live(), "742-1295")), List.of(CONSULTATION), order);
            assertEquals(t.missing(byId(t.live(), "1295-3093")), List.of("Tainted Pact"), order);
            assertEquals(ids(t.oneShort()), List.of("742-1295", "1295-3093"), order);
            assertEquals(t.nearest().variant().id(), "742-1295", order);
            assertSame(t.view().nearest(), t.nearest(), order);
        }
    }

    @Test
    public void nearestCountsTheMissingPiecesBeforeTheirCost() {
        for (final boolean consultationFirst : new boolean[] {true, false}) {
            final String order = "Consultation's line first in the source: " + consultationFirst;
            // Oracle and Pact in hand, Consultation in the library: Pact's line misses nothing and costs 4 to cast,
            // Consultation's misses one piece and costs 3; nothing missing is the nearer
            Game game = initAndCreateGame();
            Player ai = game.getPlayers().get(1);
            ComboTracker t = nearestBoard(ai, consultationFirst, ORACLE_CONSULTATION, List.of(ORACLE, "Tainted Pact"),
                    List.of(CONSULTATION));
            assertEquals(t.missing(byId(t.live(), "1295-3093")), List.of(), order);
            assertEquals(t.missing(byId(t.live(), "742-1295")), List.of(CONSULTATION), order);
            assertEquals(t.costNow(byId(t.live(), "1295-3093")), 4, order);
            assertEquals(t.costNow(byId(t.live(), "742-1295")), 3, order);
            assertEquals(t.nearest().variant().id(), "1295-3093", "nothing missing beats one short; " + order);

            // Pact in hand, Oracle and Consultation in the library: Consultation's line misses two pieces and costs 3,
            // Pact's misses one and costs 4; one missing is the nearer
            game = initAndCreateGame();
            ai = game.getPlayers().get(1);
            t = nearestBoard(ai, consultationFirst, ORACLE_CONSULTATION, List.of("Tainted Pact"),
                    List.of(ORACLE, CONSULTATION));
            assertEquals(t.missing(byId(t.live(), "742-1295")), List.of(CONSULTATION, ORACLE), order);
            assertEquals(t.missing(byId(t.live(), "1295-3093")), List.of(ORACLE), order);
            assertEquals(t.costNow(byId(t.live(), "742-1295")), 3, order);
            assertEquals(t.costNow(byId(t.live(), "1295-3093")), 4, order);
            assertEquals(t.nearest().variant().id(), "1295-3093", "one short beats two short; " + order);
        }
    }

    @Test
    public void nearestRanksATieByTheManaStillToCastNotTheTableValue() {
        // the table rates Consultation's row 9, above Pact's 4; both lines one short (Oracle in hand, Pact and
        // Consultation in the library), Consultation's pieces still to cast cost 3 and Pact's 4: the mana still to
        // cast decides, so Consultation's line is the nearest in either source order
        final String dearConsultation = "V|742-1295|9|{U}{U}{B}|2,88|H:Demonic Consultation|H:Thassa's Oracle";
        for (final boolean consultationFirst : new boolean[] {true, false}) {
            final String order = "Consultation's line first in the source: " + consultationFirst;
            final Game game = initAndCreateGame();
            final Player ai = game.getPlayers().get(1);
            final ComboTracker t = nearestBoard(ai, consultationFirst, dearConsultation, List.of(ORACLE),
                    List.of("Tainted Pact", CONSULTATION));
            final ComboLines.Line consultationLine = byId(t.live(), "742-1295");
            final ComboLines.Line pactLine = byId(t.live(), "1295-3093");
            assertEquals(consultationLine.variant().manaValue(), 9, order);
            assertEquals(pactLine.variant().manaValue(), 4, order);
            assertEquals(t.costNow(consultationLine), 3, order);
            assertEquals(t.costNow(pactLine), 4, order);
            assertEquals(ids(t.oneShort()), List.of("742-1295", "1295-3093"), order);
            assertSame(t.nearest(), consultationLine, order);
        }
    }

    @Test
    public void nearestReadsOnlyTheLiveLines() {
        // no lands: the NO_MANA signal abandons Consultation's line, which may still be executed (no mana never stops
        // a win now), so it is executable but not live, and no line is the nearest live combo
        final Game game = initAndCreateGame();
        final Player ai = game.getPlayers().get(1);
        final ComboTracker t = aiOf(ai).getComboTracker();
        t.useLines(lines(ORACLE_CONSULTATION));
        addCardToZone(ORACLE, ai, ZoneType.Hand);
        addCardToZone(CONSULTATION, ai, ZoneType.Library);
        for (final String name : NAMES) {
            addCardToZone(name, ai, ZoneType.Library);
        }
        assertEquals(ComboSignals.abandon(ai, t.deckLines().get(0), t), ComboSignals.Signal.NO_MANA);
        assertEquals(ids(t.executable()), List.of("742-1295"));
        assertEquals(t.live(), List.of());
        assertNull(t.nearest());
        assertNull(t.view().nearest());
    }

    /** {@code ai}'s tracker reading {@code consultationRow} and {@link #ORACLE_PACT}, Consultation's row first when
     *  {@code consultationFirst}, with two Islands and two Swamps on our battlefield, {@code hand} in our hand and
     *  {@code library} then the ten {@link #NAMES} in our library (a singleton library, as Pact's line needs). */
    private ComboTracker nearestBoard(final Player ai, final boolean consultationFirst, final String consultationRow,
            final List<String> hand, final List<String> library) {
        final ComboTracker t = aiOf(ai).getComboTracker();
        t.useLines(consultationFirst ? lines(consultationRow, ORACLE_PACT) : lines(ORACLE_PACT, consultationRow));
        for (final String land : List.of("Island", "Island", "Swamp", "Swamp")) {
            addCard(land, ai);
        }
        for (final String name : hand) {
            addCardToZone(name, ai, ZoneType.Hand);
        }
        for (final String name : library) {
            addCardToZone(name, ai, ZoneType.Library);
        }
        for (final String name : NAMES) {
            addCardToZone(name, ai, ZoneType.Library);
        }
        return t;
    }

    private static ComboLines.Line byId(final List<ComboLines.Line> lines, final String id) {
        for (final ComboLines.Line l : lines) {
            if (l.variant().id().equals(id)) {
                return l;
            }
        }
        throw new AssertionError("no line " + id + " in " + ids(lines));
    }

    private static CardCollection library(final Player p) {
        return new CardCollection(p.getCardsIn(ZoneType.Library));
    }

    private static List<String> libraryNames(final Player p) {
        return p.getCardsIn(ZoneType.Library).stream().map(ComboTracker::nameOf).collect(Collectors.toList());
    }

    /** A fresh {@code name} owned by {@code activator}, in no zone, whose first activated non-mana ability
     *  {@code activator} put on the stack: nothing paid or resolved. */
    private Card activateOnStack(final Game game, final Player activator, final String name) {
        final Card card = createCard(name, activator);
        card.setGameTimestamp(game.getNextTimestamp());
        SpellAbility ability = null;
        for (final SpellAbility sa : card.getSpellAbilities()) {
            if (sa.isActivatedAbility() && !sa.isManaAbility()) {
                ability = sa;
                break;
            }
        }
        assertNotNull(ability, name + " has no activated ability");
        ability.setActivatingPlayer(activator);
        final int before = game.getStack().size();
        game.getStack().add(ability);
        assertEquals(game.getStack().size(), before + 1, name + "'s ability did not reach the stack");
        return card;
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
