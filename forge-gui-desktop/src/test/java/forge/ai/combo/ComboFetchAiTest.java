package forge.ai.combo;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNotEquals;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertNotSame;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertSame;
import static org.testng.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import org.apache.commons.lang3.tuple.ImmutablePair;
import org.testng.annotations.Test;

import forge.ai.AITest;
import forge.ai.AiCardMemory;
import forge.ai.AiController;
import forge.ai.ComputerUtil;
import forge.ai.ComputerUtilMana;
import forge.ai.LobbyPlayerAi;
import forge.ai.PlayerControllerAi;
import forge.ai.SpecialCardAi;
import forge.ai.ability.ChangeZoneAi;
import forge.ai.ability.DigAi;
import forge.card.mana.ManaAtom;
import forge.game.Game;
import forge.game.ability.AbilityKey;
import forge.game.ability.AbilityUtils;
import forge.game.ability.ApiType;
import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.card.CounterEnumType;
import forge.game.cost.Cost;
import forge.game.mana.Mana;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import forge.util.MyRandom;

/**
 * The fetch rules (spec 2026-10-22-combo-aware-ai-core-design.md section 6.3): a library search, a dig, a scry, a
 * library reordering and Lim-Dul's Vault take the missing piece of a combo line exactly one piece short, keep a live
 * line's pieces on top, and otherwise make the stock decision with the stock draws. Unless a test says otherwise the
 * board is line 742-1295 with Thassa's Oracle in hand, a ten-name library holding Demonic Consultation and Craw Wurm,
 * and Island, Island and Swamp in play (the line's whole cost, so NO_MANA is quiet), on the Default profile.
 */
public class ComboFetchAiTest extends AITest {
    private static final String WIN = "F|2|S|Win the game";
    private static final String EXILE = "F|88|H|Exile your library";
    private static final String ORACLE_CONSULTATION = "V|742-1295|3|{U}{U}{B}|2,88|H:Demonic Consultation|H:Thassa's Oracle";
    private static final String LAB_CONSULTATION = "V|742-2836|1|{B}|2,88|H:Demonic Consultation|B:Laboratory Maniac";
    private static final String JACE_CONSULTATION = "V|742-3096|1|{B}|2,88|H:Demonic Consultation|B:Jace, Wielder of Mysteries";
    private static final String DOOM_CONSULTATION = "V|x-1|1|{B}|2,88|H:Demonic Consultation|B:Doctor Doom, Unrivaled";
    private static final String FBLTHP_CONSULTATION = "V|742-8266|1|{B}|2,88|B:Fblthp, Impossibly Lost|H:Demonic Consultation";

    private static final String ORACLE = "Thassa's Oracle";
    private static final String CONSULTATION = "Demonic Consultation";
    private static final String LAB = "Laboratory Maniac";
    private static final String JACE = "Jace, Wielder of Mysteries";
    private static final String DOOM = "Doctor Doom, Unrivaled";
    private static final String FBLTHP = "Fblthp, Impossibly Lost";
    private static final String OPT = "Opt";
    private static final String WURM = "Craw Wurm";
    private static final String MEETING = "Meeting of Minds";

    // ten distinct names, Consultation on top and Craw Wurm second (fillLibrary makes ten of one name)
    private static final List<String> LIBRARY = Arrays.asList(CONSULTATION, WURM, "Runeclaw Bear", "Grizzly Bears",
            "Hill Giant", "Serra Angel", "Shivan Dragon", "Llanowar Elves", "Counterspell", "Divination");

    // Two cards kept on top shuffle with one draw, nextInt(2): seed 1 leaves the pair as it is (so does every seed
    // from 1 to 12) and seed 12345 swaps it, so these two try both orders
    private static final List<Long> SHUFFLE_SEEDS = List.of(1L, 12345L);

    private Game game;
    private Player ai;
    private Player opp;
    private ComboTracker t;

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

    private static List<Card> list(final Iterable<Card> cards) {
        final List<Card> out = new ArrayList<>();
        cards.forEach(out::add);
        return out;
    }

    /** A two-player game whose AI (player 1, on the Default profile, in its own MAIN1) tracks {@code rows}, with
     *  Island, Island and Swamp in play; nothing in hand or library yet. */
    private void start(final String... rows) {
        game = initAndCreateGame();
        ai = game.getPlayers().get(1);
        opp = game.getPlayers().get(0);
        ((LobbyPlayerAi) ai.getLobbyPlayer()).setAiProfile("Default");
        t = aiOf(ai).getComboTracker();
        t.useLines(lines(rows));
        for (final String land : List.of("Island", "Island", "Swamp")) {
            addCard(land, ai);
        }
    }

    /** The default board: line 742-1295, Oracle in hand, the ten-name library; the library's Consultation. */
    private Card oracleBoard() {
        start(ORACLE_CONSULTATION);
        addCardToZone(ORACLE, ai, ZoneType.Hand);
        for (final String name : LIBRARY) {
            addCardToZone(name, ai, ZoneType.Library);
        }
        assertEquals(ids(t.oneShort()), List.of("742-1295"));
        return t.find(CONSULTATION, ZoneType.Library);
    }

    /** {@code name}'s first spell, the card put in our hand, with us as its activator. */
    private SpellAbility spell(final String name) {
        final SpellAbility sa = addCardToZone(name, ai, ZoneType.Hand).getFirstSpellAbility();
        sa.setActivatingPlayer(ai);
        return sa;
    }

    /** The chooser as IntuitionPickAiTest's pick calls it: our own search of our whole library, decided by us. */
    private Card pick(final ZoneType destination, final SpellAbility sa) {
        return pick(destination, sa, ai);
    }

    /** The same search of our library, decided by {@code decider}; a fresh list each call, since the stock pick
     *  edits the list it is given. */
    private Card pick(final ZoneType destination, final SpellAbility sa, final Player decider) {
        return ChangeZoneAi.chooseCardToHiddenOriginChangeZone(destination, Arrays.asList(ZoneType.Library), sa,
                new CardCollection(ai.getCardsIn(ZoneType.Library)), ai, decider);
    }

    /** Stock parity: {@code decision} reseeded alike, once with no line and once with {@code others}, gives the same
     *  answer and makes the same number of draws; returns the stock answer. */
    private <T> T assertStockParity(final Supplier<T> decision, final List<ComboLines.Line> others) {
        t.useLines(List.of());
        MyRandom.setRandom(new Random(7));
        long before = MyRandom.callCount();
        final T stock = decision.get();
        final long stockDraws = MyRandom.callCount() - before;
        t.useLines(others);
        MyRandom.setRandom(new Random(7));
        before = MyRandom.callCount();
        final T ours = decision.get();
        assertEquals(ours, stock, "the answer moved");
        assertEquals(MyRandom.callCount() - before, stockDraws, "the draws moved");
        return stock;
    }

    @Test
    public void tutorTakesTheMissingPiece() {
        final Card consultation = oracleBoard();
        final SpellAbility tutor = spell("Demonic Tutor");

        assertSame(pick(ZoneType.Hand, tutor), consultation);
    }

    @Test
    public void comboPickGoesAheadOfKeyCards() {
        final Card consultation = oracleBoard();
        ai.getRegisteredPlayer().getDeck().addKeyCard(WURM);
        final SpellAbility tutor = spell("Demonic Tutor");
        // with no line the deck's KeyCards decide: Craw Wurm
        t.useLines(List.of());
        assertEquals(pick(ZoneType.Hand, tutor).getName(), WURM);

        t.useLines(lines(ORACLE_CONSULTATION));
        assertSame(pick(ZoneType.Hand, tutor), consultation);
    }

    @Test
    public void twoShortTakesTheStockPick() {
        oracleBoard();
        final Card oracle = t.find(ORACLE, ZoneType.Hand);
        game.getAction().moveTo(ZoneType.Library, oracle, null, AbilityKey.newMap());
        assertEquals(ids(t.live()), List.of("742-1295"));
        assertEquals(t.missing(t.live().get(0)).size(), 2);
        assertEquals(t.oneShort(), List.of());
        final SpellAbility tutor = spell("Demonic Tutor");

        assertStockParity(() -> pick(ZoneType.Hand, tutor), lines(ORACLE_CONSULTATION));
    }

    @Test
    public void noLineIsStock() {
        oracleBoard();
        final SpellAbility tutor = spell("Demonic Tutor");

        // 742-2836 needs a Laboratory Maniac the deck lacks
        assertStockParity(() -> pick(ZoneType.Hand, tutor), lines(LAB_CONSULTATION));
        assertEquals(t.deckLines(), List.of());
    }

    @Test
    public void battlefieldSearchTakesOnlyBattlefieldPieces() {
        start(LAB_CONSULTATION);
        addCard("Swamp", ai);   // Laboratory Maniac (3) and Consultation (1) cost 4
        addCardToZone(CONSULTATION, ai, ZoneType.Hand);
        for (final String name : LIBRARY) {
            addCardToZone(CONSULTATION.equals(name) ? LAB : name, ai, ZoneType.Library);
        }
        assertEquals(ids(t.oneShort()), List.of("742-2836"));
        final Card lab = t.find(LAB, ZoneType.Library);

        assertSame(pick(ZoneType.Battlefield, spell("Chord of Calling")), lab);

        // Oracle's piece starts in hand only (H): a Battlefield search leaves 742-1295 to the stock pick
        start(ORACLE_CONSULTATION);
        addCardToZone(CONSULTATION, ai, ZoneType.Hand);
        for (final String name : LIBRARY) {
            addCardToZone(CONSULTATION.equals(name) ? ORACLE : name, ai, ZoneType.Library);
        }
        assertEquals(ids(t.oneShort()), List.of("742-1295"));
        final SpellAbility chord = spell("Chord of Calling");

        assertStockParity(() -> pick(ZoneType.Battlefield, chord), lines(ORACLE_CONSULTATION));
    }

    @Test
    public void topOfLibraryTutor() {
        final Card consultation = oracleBoard();
        final SpellAbility vampiric = spell("Vampiric Tutor");
        assertEquals(vampiric.getParam("Destination"), "Library");
        assertEquals(vampiric.getParam("LibraryPosition"), "0");

        assertSame(pick(ZoneType.Library, vampiric), consultation);
    }

    @Test
    public void digTakesThePiece() {
        final Card consultation = oracleBoard();
        final Card wurm = t.find(WURM, ZoneType.Library);
        final Card island = addCardToZone("Island", ai, ZoneType.Library);
        final SpellAbility impulse = spell("Impulse");
        final List<Card> shown = List.of(wurm, consultation, island);
        // with no line the stock pick keeps something else
        t.useLines(List.of());
        assertNotEquals(new DigAi().chooseSingleCard(ai, impulse, shown, false, null, Map.of()), consultation);

        t.useLines(lines(ORACLE_CONSULTATION));
        assertSame(new DigAi().chooseSingleCard(ai, impulse, shown, false, null, Map.of()), consultation);
    }

    @Test
    public void scryKeepsALivePiece() {
        final Card consultation = oracleBoard();
        assertFalse(ComputerUtil.scryWillMoveCardToBottomOfLibrary(ai, consultation));
        // land-light (three lands, none in hand), the stock rule scries every nonland away
        t.useLines(List.of());
        assertTrue(ComputerUtil.scryWillMoveCardToBottomOfLibrary(ai, consultation));

        // Oracle exiled: the line is dead, and the answer is the empty tracker's
        t.useLines(lines(ORACLE_CONSULTATION));
        game.getAction().moveTo(ZoneType.Exile, t.find(ORACLE, ZoneType.Hand), null, AbilityKey.newMap());
        assertEquals(ids(t.deckLines()), List.of("742-1295"));
        assertEquals(t.live(), List.of());
        final boolean dead = ComputerUtil.scryWillMoveCardToBottomOfLibrary(ai, consultation);
        t.useLines(List.of());
        assertEquals(dead, ComputerUtil.scryWillMoveCardToBottomOfLibrary(ai, consultation));
    }

    @Test
    public void arrangeForScryPutsThePieceOnTop() {
        final Card consultation = oracleBoard();
        final Card island = addCardToZone("Island", ai, ZoneType.Library);
        final PlayerControllerAi pc = (PlayerControllerAi) ai.getController();
        // whatever the shuffle of the cards kept on top does, the piece ends first: on top. The shuffle's draw is the
        // scry's only one, so it is each seed's first, and SHUFFLE_SEEDS give it both answers
        assertNotEquals(new Random(1).nextInt(2), new Random(12345).nextInt(2));
        for (final long seed : SHUFFLE_SEEDS) {
            MyRandom.setRandom(new Random(seed));
            final long before = MyRandom.callCount();
            final ImmutablePair<CardCollection, CardCollection> scry =
                    pc.arrangeForScry(new CardCollection(List.of(island, consultation)));
            assertEquals(MyRandom.callCount() - before, 1, "seed " + seed);
            assertSame(scry.getLeft().get(0), consultation, "seed " + seed);
            assertEquals(list(scry.getRight()), List.of(), "seed " + seed);
        }

        // with no line the stock rule bottoms it
        t.useLines(List.of());
        final ImmutablePair<CardCollection, CardCollection> stock =
                pc.arrangeForScry(new CardCollection(List.of(island, consultation)));
        assertEquals(list(stock.getLeft()), List.of(island));
        assertEquals(list(stock.getRight()), List.of(consultation));
    }

    @Test
    public void orderMovePutsThePieceOnTop() {
        final Card consultation = oracleBoard();
        final Card wurm = t.find(WURM, ZoneType.Library);
        final Card island = addCardToZone("Island", ai, ZoneType.Library);
        final SpellAbility reorder = vaultReorder();
        final PlayerControllerAi pc = (PlayerControllerAi) ai.getController();

        // moved to the top one at a time, the list comes back reversed (PlayerControllerAi's Library branch): the
        // last card moved ends on top
        final List<Card> order = list(pc.orderMoveToZoneList(new CardCollection(List.of(wurm, consultation, island)),
                ZoneType.Library, reorder));
        assertEquals(order.size(), 3);
        assertSame(order.get(2), consultation);

        // with no line it is the stock order: Island moved last (on top), the two the scry rule bottoms under it
        t.useLines(List.of());
        assertEquals(list(pc.orderMoveToZoneList(new CardCollection(List.of(wurm, consultation, island)),
                ZoneType.Library, reorder)), List.of(consultation, wurm, island));
    }

    @Test
    public void orderingAnOpponentsLibraryReadsNoTrackerOfTheirs() {
        // final review, round 1: Portent cast by an AI holding no line at an opponent whose live line 742-1295 has
        // Demonic Consultation among the top three. The order and its draws are the stock ones, as with the opponent's
        // tracker emptied: a deck holding none of piece 1's lines plays as today, and no decision reads another
        // player's hidden combo state (spec sections 4, 6.2 and 6.3)
        start();
        assertEquals(t.deckLines(), List.of());
        final ComboTracker theirs = aiOf(opp).getComboTracker();
        theirs.useLines(lines(ORACLE_CONSULTATION));
        ((LobbyPlayerAi) opp.getLobbyPlayer()).setAiProfile("Default");
        for (final String land : List.of("Island", "Island", "Swamp")) {
            addCard(land, opp);
        }
        addCardToZone(ORACLE, opp, ZoneType.Hand);
        final Card consultation = addCardToZone(CONSULTATION, opp, ZoneType.Library);
        final Card wurm = addCardToZone(WURM, opp, ZoneType.Library);
        final Card island = addCardToZone("Island", opp, ZoneType.Library);
        assertEquals(ids(theirs.live()), List.of("742-1295"));
        assertTrue(ComboFetch.keepsOnTop(opp, consultation), "their own scry keeps it");

        final SpellAbility portent = spell("Portent");
        assertEquals(portent.getApi(), ApiType.RearrangeTopOfLibrary);
        final PlayerControllerAi pc = (PlayerControllerAi) ai.getController();
        final Supplier<List<Card>> order = () -> list(pc.orderMoveToZoneList(
                new CardCollection(List.of(consultation, wurm, island)), ZoneType.Library, portent));

        MyRandom.setRandom(new Random(7));
        long before = MyRandom.callCount();
        final List<Card> withTheirLine = order.get();
        final long draws = MyRandom.callCount() - before;
        theirs.useLines(List.of());
        MyRandom.setRandom(new Random(7));
        before = MyRandom.callCount();
        final List<Card> stock = order.get();
        assertEquals(withTheirLine, stock, "the order moved");
        assertEquals(draws, MyRandom.callCount() - before, "the draws moved");
        // the stock rule bottoms a three-land opponent's nonlands and puts them first ("screw the opponent up"); the
        // list is moved to the top one card at a time, so Consultation, moved last, ends on top
        assertEquals(stock, List.of(island, wurm, consultation));
    }

    @Test
    public void vaultStopsOnThePiece() {
        final SpellAbility look = vaultLook(Arrays.asList("Grizzly Bears", "Hill Giant", CONSULTATION, "Serra Angel",
                "Shivan Dragon", WURM, "Runeclaw Bear", "Llanowar Elves", "Counterspell", "Divination", "Opt",
                "Cancel", "Mulldrifter", "Air Elemental"));
        // with no line the stock rule digs: the five hold no two cards it keeps
        t.useLines(List.of());
        assertTrue(willDig(look));

        t.useLines(lines(ORACLE_CONSULTATION));
        assertEquals(ids(t.oneShort()), List.of("742-1295"));
        assertFalse(willDig(look));
    }

    @Test
    public void vaultDigsForThePiece() {
        // Consultation eighth from the top; the five hold two Islands, which the land-light stock rule keeps
        final SpellAbility look = vaultLook(Arrays.asList("Island", "Grizzly Bears", "Island", "Hill Giant",
                "Serra Angel", "Shivan Dragon", WURM, CONSULTATION, "Runeclaw Bear", "Llanowar Elves", "Counterspell",
                "Divination", "Opt", "Cancel"));
        t.useLines(List.of());
        assertFalse(willDig(look));

        t.useLines(lines(ORACLE_CONSULTATION));
        assertEquals(ids(t.oneShort()), List.of("742-1295"));
        assertTrue(willDig(look));
    }

    @Test
    public void vaultNoLineIsStock() {
        // a five the stock rule digs past, and one it keeps; 742-2836 needs a Laboratory Maniac the deck lacks
        final SpellAbility digs = vaultLook(Arrays.asList("Grizzly Bears", "Hill Giant", CONSULTATION, "Serra Angel",
                "Shivan Dragon", WURM, "Runeclaw Bear", "Llanowar Elves", "Counterspell", "Divination", "Opt",
                "Cancel", "Mulldrifter", "Air Elemental"));
        assertTrue(assertStockParity(() -> willDig(digs), lines(LAB_CONSULTATION)));
        assertEquals(t.deckLines(), List.of());

        final SpellAbility keeps = vaultLook(Arrays.asList("Island", "Grizzly Bears", "Island", "Hill Giant",
                "Serra Angel", "Shivan Dragon", WURM, CONSULTATION, "Runeclaw Bear", "Llanowar Elves", "Counterspell",
                "Divination", "Opt", "Cancel"));
        assertFalse(assertStockParity(() -> willDig(keeps), lines(LAB_CONSULTATION)));
    }

    // beyond the plan's tests

    @Test
    public void arrangeForSurveilPutsThePieceOnTop() {
        final Card consultation = oracleBoard();
        // eleven cards: more than the Default profile's SURVEIL_NUM_CARDS_IN_LIBRARY_TO_BAIL (10), so the rule runs
        final Card island = addCardToZone("Island", ai, ZoneType.Library);
        final PlayerControllerAi pc = (PlayerControllerAi) ai.getController();
        // both orders of the shuffle, as in arrangeForScryPutsThePieceOnTop
        for (final long seed : SHUFFLE_SEEDS) {
            MyRandom.setRandom(new Random(seed));
            final long before = MyRandom.callCount();
            final ImmutablePair<CardCollection, CardCollection> surveil =
                    pc.arrangeForSurveil(new CardCollection(List.of(island, consultation)));
            assertEquals(MyRandom.callCount() - before, 1, "seed " + seed);
            assertSame(surveil.getLeft().get(0), consultation, "seed " + seed);
            assertEquals(list(surveil.getRight()), List.of(), "seed " + seed);
        }

        // with no line the stock rule puts it in the graveyard
        t.useLines(List.of());
        assertEquals(list(pc.arrangeForSurveil(new CardCollection(List.of(island, consultation))).getRight()),
                List.of(consultation));
    }

    @Test
    public void searchPickIsOnlyOurOwnSearchToHandTopOrBattlefield() {
        final Card consultation = oracleBoard();
        final CardCollection library = new CardCollection(ai.getCardsIn(ZoneType.Library));
        final SpellAbility tutor = spell("Demonic Tutor");
        assertSame(ComboFetch.searchPick(tutor, ZoneType.Hand, library, ai, ai), consultation);

        // someone else decides, or the search goes anywhere else than hand, the top or the battlefield
        assertNull(ComboFetch.searchPick(tutor, ZoneType.Hand, library, opp, ai));
        assertNull(ComboFetch.searchPick(tutor, ZoneType.Graveyard, library, ai, ai));
        assertNull(ComboFetch.searchPick(tutor, ZoneType.Exile, library, ai, ai));
        // a Library search that is not to the top: Intuition and Gifts Ungiven (their piles are Task 9's) put
        // their cards elsewhere, and are left alone whatever the destination
        final SpellAbility intuition = spell("Intuition");
        assertEquals(intuition.getParam("AILogic"), "Intuition");
        assertNull(ComboFetch.searchPick(intuition, ZoneType.Library, library, ai, ai));
        assertNull(ComboFetch.searchPick(intuition, ZoneType.Hand, library, ai, ai));
        final SpellAbility gifts = spell("Gifts Ungiven");
        assertTrue(SpecialCardAi.GiftsUngiven.handles(gifts));
        assertNull(ComboFetch.searchPick(gifts, ZoneType.Hand, library, ai, ai));

        // a card of the piece's name that is not ours is never the pick
        final CardCollection theirs = new CardCollection(List.of(addCardToZone(CONSULTATION, opp, ZoneType.Library)));
        assertNull(ComboFetch.searchPick(tutor, ZoneType.Hand, theirs, ai, ai));
    }

    @Test
    public void digPickIsOnlyOurOwnDigToHandTopOrBattlefield() {
        final Card consultation = oracleBoard();
        final List<Card> shown = List.of(t.find(WURM, ZoneType.Library), consultation);
        final SpellAbility impulse = spell("Impulse");
        assertSame(ComboFetch.digPick(ai, impulse, shown, null), consultation);
        assertSame(ComboFetch.digPick(ai, impulse, shown, ai), consultation);
        // a dig of another player's library
        assertNull(ComboFetch.digPick(ai, impulse, shown, opp));

        // Thassa's Oracle's own look puts one card on top (LibraryPosition$ 0); Jace's +2 and the Vault's GoToBottom
        // put theirs on the bottom (no LibraryPosition, or -1): the piece is never sent there
        final SpellAbility top = addCardToZone(ORACLE, ai, ZoneType.Hand).getTriggers().get(0).ensureAbility();
        top.setActivatingPlayer(ai);
        assertEquals(top.getParam("DestinationZone"), "Library");
        assertEquals(top.getParam("LibraryPosition"), "0");
        assertSame(ComboFetch.digPick(ai, top, shown, ai), consultation);
        final SpellAbility jace = addCardToZone("Jace, the Mind Sculptor", ai, ZoneType.Hand).getSpellAbilities()
                .stream().filter(sa -> "Library".equals(sa.getParam("DestinationZone"))).findFirst().orElseThrow();
        assertFalse(jace.hasParam("LibraryPosition"));
        assertNull(ComboFetch.digPick(ai, jace, shown, ai));

        // a dig to the battlefield takes only a piece that may start there: Consultation's is H
        final SpellAbility company = spell("Collected Company");
        assertEquals(company.getParam("DestinationZone"), "Battlefield");
        assertNull(ComboFetch.digPick(ai, company, shown, ai));
    }

    @Test
    public void digToTheBattlefieldTakesOnlyBattlefieldPieces() {
        start(LAB_CONSULTATION);
        addCard("Swamp", ai);   // Laboratory Maniac (3) and Consultation (1) cost 4
        addCardToZone(CONSULTATION, ai, ZoneType.Hand);
        for (final String name : LIBRARY) {
            addCardToZone(CONSULTATION.equals(name) ? LAB : name, ai, ZoneType.Library);
        }
        assertEquals(ids(t.oneShort()), List.of("742-2836"));
        final Card lab = t.find(LAB, ZoneType.Library);
        final List<Card> shown = List.of(t.find("Hill Giant", ZoneType.Library), lab,
                t.find("Grizzly Bears", ZoneType.Library));
        final SpellAbility company = spell("Collected Company");
        // with no line the stock pick (getBestAI) takes another creature
        t.useLines(List.of());
        assertNotEquals(new DigAi().chooseSingleCard(ai, company, shown, true, ai, Map.of()), lab);

        t.useLines(lines(LAB_CONSULTATION));
        assertSame(new DigAi().chooseSingleCard(ai, company, shown, true, ai, Map.of()), lab);
    }

    @Test
    public void keepsOnTopReadsOnlyALiveLinesPieceInOurLibrary() {
        final Card consultation = oracleBoard();
        final Card wurm = t.find(WURM, ZoneType.Library);
        final Card oracle = t.find(ORACLE, ZoneType.Hand);
        assertTrue(ComboFetch.keepsOnTop(ai, consultation));
        assertFalse(ComboFetch.keepsOnTop(ai, wurm));
        // the piece in our hand is in no library; the opponent's Consultation is in theirs
        assertFalse(ComboFetch.keepsOnTop(ai, oracle));
        final Card theirs = addCardToZone(CONSULTATION, opp, ZoneType.Library);
        assertFalse(ComboFetch.keepsOnTop(ai, theirs));
        assertFalse(ComboFetch.keepsOnTop(opp, consultation));

        // pieceFirst is stable and in place, and leaves a list with no piece as it was
        final List<Card> cards = new ArrayList<>(List.of(wurm, theirs, consultation));
        ComboFetch.pieceFirst(ai, cards);
        assertEquals(cards, List.of(consultation, wurm, theirs));
        final List<Card> none = new ArrayList<>(List.of(theirs, wurm));
        ComboFetch.pieceFirst(ai, none);
        assertEquals(none, List.of(theirs, wurm));

        // a dead line keeps nothing: Oracle exiled
        game.getAction().moveTo(ZoneType.Exile, oracle, null, AbilityKey.newMap());
        assertFalse(ComboFetch.keepsOnTop(ai, consultation));
    }

    // review round 1

    @Test
    public void theCheapestOneShortLineThatTheListHoldsIsServed() {
        start(LAB_CONSULTATION, ORACLE_CONSULTATION);
        addCard("Swamp", ai);   // Laboratory Maniac (3) and Consultation (1) cost 4
        addCardToZone(CONSULTATION, ai, ZoneType.Hand);
        final Card oracle = addCardToZone(ORACLE, ai, ZoneType.Library);
        final Card lab = addCardToZone(LAB, ai, ZoneType.Library);
        final Card wurm = addCardToZone(WURM, ai, ZoneType.Library);
        // two lines one piece short: Oracle's (Consultation's 1 and Oracle's 2 still to cast) ranks ahead of
        // Laboratory Maniac's (1 and 3), whatever the table's mana values (final review, round 1)
        assertEquals(ids(t.oneShort()), List.of("742-1295", "742-2836"));
        final SpellAbility tutor = spell("Demonic Tutor");
        final SpellAbility impulse = spell("Impulse");

        // both missing pieces offered, Lab first: the first line's piece is the pick, not the first card
        assertSame(ComboFetch.searchPick(tutor, ZoneType.Hand, new CardCollection(List.of(lab, oracle, wurm)), ai, ai),
                oracle);
        assertSame(ComboFetch.digPick(ai, impulse, List.of(lab, oracle, wurm), ai), oracle);
        // the first line's piece not offered: the next line is served
        assertSame(ComboFetch.searchPick(tutor, ZoneType.Hand, new CardCollection(List.of(wurm, lab)), ai, ai),
                lab);
        assertSame(ComboFetch.digPick(ai, impulse, List.of(wurm, lab), ai), lab);
        // the stock chooser itself, over the whole library, takes Thassa's Oracle
        assertSame(pick(ZoneType.Hand, tutor), oracle);
    }

    @Test
    public void aBattlefieldFetchSkipsALineWhosePieceCannotStartThereForTheNextLine() {
        start(LAB_CONSULTATION, ORACLE_CONSULTATION);
        addCard("Swamp", ai);   // Laboratory Maniac (3) and Consultation (1) cost 4
        addCardToZone(CONSULTATION, ai, ZoneType.Hand);
        final Card oracle = addCardToZone(ORACLE, ai, ZoneType.Library);
        final Card lab = addCardToZone(LAB, ai, ZoneType.Library);
        // Oracle's line ranks first (3 still to cast against Lab's 4), and its missing piece starts in hand only (H)
        assertEquals(ids(t.oneShort()), List.of("742-1295", "742-2836"));
        final List<Card> both = List.of(oracle, lab);

        // to the battlefield the first line is skipped, not the end of the pick: Laboratory Maniac (B)
        assertSame(ComboFetch.searchPick(spell("Chord of Calling"), ZoneType.Battlefield, new CardCollection(both),
                ai, ai), lab);
        assertSame(ComboFetch.digPick(ai, spell("Collected Company"), both, ai), lab);
        // to the hand the first line's piece is the pick
        assertSame(ComboFetch.searchPick(spell("Demonic Tutor"), ZoneType.Hand, new CardCollection(both), ai, ai),
                oracle);
    }

    @Test
    public void aBattlefieldFetchTakesNothingWhenTheMissingPieceIsTheOneThatCannotStartThere() {
        start(LAB_CONSULTATION);
        addCard("Swamp", ai);   // Laboratory Maniac (3) and Consultation (1) cost 4
        addCardToZone(LAB, ai, ZoneType.Hand);   // the line's piece that may start on the battlefield is held
        for (final String name : LIBRARY) {
            addCardToZone(name, ai, ZoneType.Library);
        }
        assertEquals(ids(t.oneShort()), List.of("742-2836"));
        final CardCollection library = new CardCollection(ai.getCardsIn(ZoneType.Library));
        final Card consultation = t.find(CONSULTATION, ZoneType.Library);
        assertSame(ComboFetch.searchPick(spell("Demonic Tutor"), ZoneType.Hand, library, ai, ai), consultation);

        // the missing piece, Consultation, is H: nothing goes to the battlefield, though the line's other piece is B
        assertNull(ComboFetch.searchPick(spell("Chord of Calling"), ZoneType.Battlefield, library, ai, ai));
        assertNull(ComboFetch.digPick(ai, spell("Collected Company"), list(library), ai));
    }

    @Test
    public void aSearchDecidedByAnotherPlayerIsTheStockPickThroughTheChooser() {
        final Card consultation = oracleBoard();
        final SpellAbility tutor = spell("Demonic Tutor");
        // through the chooser, our library searched with an opponent deciding: the stock pick and the stock draws,
        // although we hold the line; told counts the seeds whose stock pick is not the piece itself
        int told = 0;
        for (final long seed : List.of(7L, 8L, 9L, 10L, 11L, 12L)) {
            t.useLines(List.of());
            MyRandom.setRandom(new Random(seed));
            long before = MyRandom.callCount();
            final Card stock = pick(ZoneType.Hand, tutor, opp);
            final long stockDraws = MyRandom.callCount() - before;
            t.useLines(lines(ORACLE_CONSULTATION));
            MyRandom.setRandom(new Random(seed));
            before = MyRandom.callCount();
            assertSame(pick(ZoneType.Hand, tutor, opp), stock, "seed " + seed);
            assertEquals(MyRandom.callCount() - before, stockDraws, "seed " + seed);
            told += stock == consultation ? 0 : 1;
        }
        assertTrue(told > 0, "every seed's stock pick was the piece");
        // decided by us, the same search takes the piece
        assertSame(pick(ZoneType.Hand, tutor), consultation);
    }

    @Test
    public void bothPiecesOfALineStillTwoShortStayOnTopInOrder() {
        final Card consultation = oracleBoard();
        final Card wurm = t.find(WURM, ZoneType.Library);
        game.getAction().moveTo(ZoneType.Library, t.find(ORACLE, ZoneType.Hand), null, AbilityKey.newMap());
        final Card oracle = t.find(ORACLE, ZoneType.Library);
        assertEquals(t.oneShort(), List.of());
        assertEquals(ids(t.live()), List.of("742-1295"));

        // two short, yet live: both pieces stay on top (any live line, not only a one-short one)
        assertTrue(ComboFetch.keepsOnTop(ai, consultation));
        assertTrue(ComboFetch.keepsOnTop(ai, oracle));
        assertFalse(ComputerUtil.scryWillMoveCardToBottomOfLibrary(ai, consultation));
        assertFalse(ComputerUtil.scryWillMoveCardToBottomOfLibrary(ai, oracle));
        // and pieceFirst keeps the pieces' own order
        final List<Card> cards = new ArrayList<>(List.of(wurm, oracle, consultation));
        ComboFetch.pieceFirst(ai, cards);
        assertEquals(cards, List.of(oracle, consultation, wurm));
    }

    @Test
    public void aPieceSixthFromTheTopIsDugForNotKept() {
        // the five in view are the top five: Consultation, sixth, is below them, and the AI has not seen it
        final SpellAbility look = vaultLook(vaultLibrary(14, 5));
        t.useLines(List.of());
        assertFalse(willDig(look));

        t.useLines(lines(ORACLE_CONSULTATION));
        assertTrue(willDig(look));
    }

    @Test
    public void aComboDigIsRememberedSoThatMaxDigsBoundsIt() {
        final SpellAbility look = vaultLook(vaultLibrary(14, 7));
        final List<Card> top = list(ai.getCardsIn(ZoneType.Library)).subList(0, 5);
        assertTrue(willDig(look));
        // the five it bottoms are recorded as the stock dig records them: digsSoFar counts them against MAX_DIGS
        for (final Card c : top) {
            assertTrue(AiCardMemory.isRememberedCard(ai, c, AiCardMemory.MemorySet.LIM_DULS_VAULT_DUG));
        }
    }

    @Test
    public void aComboDigStaysBehindTheDigCapTheLibraryMarginAndTheLifeFloor() {
        // each board digs for Consultation below the five until a stock guard stands in front of it
        assertTrue(willDig(vaultLook(vaultLibrary(25, 8))));
        // three fives already bottomed: MAX_DIGS
        final SpellAbility capped = vaultLook(vaultLibrary(25, 8));
        final List<Card> lib = list(ai.getCardsIn(ZoneType.Library));
        for (int i = lib.size() - 15; i < lib.size(); i++) {
            AiCardMemory.rememberCard(ai, lib.get(i), AiCardMemory.MemorySet.LIM_DULS_VAULT_DUG);
        }
        assertFalse(willDig(capped));

        // the first dig needs two unseen fives and the margin of 3: 13 cards dig, 12 do not
        assertTrue(willDig(vaultLook(vaultLibrary(13, 7))));
        assertFalse(willDig(vaultLook(vaultLibrary(12, 7))));

        // life 10: paying 1 goes below the floor
        assertTrue(willDig(vaultLook(vaultLibrary(14, 7))));
        final SpellAbility floored = vaultLook(vaultLibrary(14, 7));
        ai.setLife(10, null);
        assertFalse(willDig(floored));
    }

    // review round 2

    @Test
    public void aComboDigStaysBehindTheDigCapAloneInALongLibrary() {
        // 40 cards leave the library margin far behind (LOOK x (digs + 2) + 3, 28 once three fives are bottomed), so
        // only MAX_DIGS stops a dig here; the 25-card capped board above is stopped by the margin as well
        final List<String> forty = longLibrary(vaultLibrary(25, 8));   // two Islands in the five, Consultation ninth
        assertTrue(willDig(vaultLook(forty)));
        final SpellAbility twoDug = vaultLook(forty);
        rememberBottomed(2);
        assertTrue(willDig(twoDug));
        // three fives already bottomed: the cap, though the piece is still below the five
        final SpellAbility capped = vaultLook(forty);
        rememberBottomed(3);
        assertFalse(willDig(capped));
    }

    @Test
    public void aSecondLookIsServedByTheSameRule() {
        // one five already bottomed (digsSoFar 1): the combo rule reads the new five as it read the first. Each board
        // asks first the question whose answer is false, which writes no memory, so both are asked at the second look
        // two Islands in the five, Consultation ninth: the stock rule keeps the five, the line digs on
        final SpellAbility digsOn = vaultLook(longLibrary(vaultLibrary(25, 8)));
        rememberBottomed(1);
        t.useLines(List.of());
        assertFalse(willDig(digsOn));
        t.useLines(lines(ORACLE_CONSULTATION));
        assertEquals(ids(t.oneShort()), List.of("742-1295"));
        assertTrue(willDig(digsOn));

        // nothing in the five the stock rule keeps, Consultation fourth: the line keeps the five, the stock rule digs
        final SpellAbility stops = vaultLook(longLibrary(Arrays.asList("Grizzly Bears", "Hill Giant", "Serra Angel",
                CONSULTATION, "Shivan Dragon")));
        rememberBottomed(1);
        assertFalse(willDig(stops));
        t.useLines(List.of());
        assertTrue(willDig(stops));
    }

    @Test
    public void aPieceFifthFromTheTopIsKeptNotDugPast() {
        // the fifth card is the last of the five in view: Consultation there keeps them
        final SpellAbility look = vaultLook(Arrays.asList("Grizzly Bears", "Hill Giant", "Serra Angel", "Shivan Dragon",
                CONSULTATION, WURM, "Runeclaw Bear", "Llanowar Elves", "Counterspell", "Divination", "Opt", "Cancel",
                "Mulldrifter", "Air Elemental"));
        t.useLines(List.of());
        assertTrue(willDig(look));    // the stock rule: nothing in the five is a keep
        t.useLines(lines(ORACLE_CONSULTATION));
        assertEquals(ids(t.oneShort()), List.of("742-1295"));
        assertFalse(willDig(look));
    }

    @Test
    public void aTwoShortLineIsNotDugForByTheVault() {
        // two Islands in the five, Consultation eighth: the stock rule keeps the five, the one-short line digs on
        assertTrue(willDig(vaultLook(vaultLibrary(14, 7))));
        final SpellAbility look = vaultLook(vaultLibrary(14, 7));
        // Oracle leaves the hand for the library's bottom, the five in view untouched: 742-1295 is live, two short
        game.getAction().moveToBottomOfLibrary(t.find(ORACLE, ZoneType.Hand), null, AbilityKey.newMap());
        assertEquals(t.oneShort(), List.of());
        assertEquals(ids(t.live()), List.of("742-1295"));
        // the stock rule's answer and draws: the Vault digs only for a line exactly one piece short
        assertFalse(assertStockParity(() -> willDig(look), lines(ORACLE_CONSULTATION)));
    }

    // review round 3

    @Test
    public void aTwoShortLineWithItsPieceInTheFiveIsNotKeptByTheVault() {
        // Consultation fifth from the top and nothing else in the five worth keeping. For a line one piece short the
        // Vault keeps the five (aPieceFifthFromTheTopIsKeptNotDugPast). Oracle moved to the library's bottom makes the
        // line two short: the Vault then makes the stock rule's decision, which digs on, with the stock draws.
        final SpellAbility look = vaultLook(Arrays.asList("Grizzly Bears", "Hill Giant", "Serra Angel", "Shivan Dragon",
                CONSULTATION, WURM, "Runeclaw Bear", "Llanowar Elves", "Counterspell", "Divination", "Opt", "Cancel",
                "Mulldrifter", "Air Elemental"));
        assertEquals(ids(t.oneShort()), List.of("742-1295"));
        assertFalse(willDig(look));    // one short, the same five: kept (an answer that writes no memory)
        game.getAction().moveToBottomOfLibrary(t.find(ORACLE, ZoneType.Hand), null, AbilityKey.newMap());
        assertEquals(t.oneShort(), List.of());
        assertEquals(ids(t.live()), List.of("742-1295"));
        assertTrue(assertStockParity(() -> willDig(look), lines(ORACLE_CONSULTATION)));
    }

    // ---- follow-up F4: a win this turn beats danger; known tops ----

    /** The danger board: the default board with a second Swamp, so that Island, Island, Swamp and Swamp are open in
     *  our Main 1, at 3 life against an opposing Colossal Dreadmaw that nothing of ours can block. DANGER is line
     *  742-1295's only signal, so no line is one short. Returns the library's Consultation. */
    private Card dangerBoard() {
        final Card consultation = oracleBoard();
        addCard("Swamp", ai);
        ai.setLife(3, null);
        addCard("Colossal Dreadmaw", opp).setSickness(false);
        assertEquals(ComboSignals.abandon(ai, t.deckLines().get(0), t), ComboSignals.Signal.DANGER);
        assertFalse(ComboSignals.surerKillNow(ai));
        assertEquals(t.oneShort(), List.of());
        return consultation;
    }

    private PlayerControllerAi pc() {
        return (PlayerControllerAi) ai.getController();
    }

    @Test
    public void dangerStillFetchesAWinThisTurn() {
        // spec section 6.5.3: a combo that wins this turn beats surviving. Oracle's {U}{U} and the fetched
        // Consultation's {B} are paid by the four lands open now, in our Main 1
        final Card consultation = dangerBoard();
        final SpellAbility tutor = spell("Demonic Tutor");
        // with no line the stock pick is another card
        t.useLines(List.of());
        assertNotSame(pick(ZoneType.Hand, tutor), consultation);

        t.useLines(lines(ORACLE_CONSULTATION));
        assertSame(pick(ZoneType.Hand, tutor), consultation);
        // and Demonic Tutor resolving (its {1}{B} unpaid: the four lands stay open) puts it in our hand
        AbilityUtils.resolve(tutor);
        assertEquals(t.where(CONSULTATION), ComboTracker.Where.HAND);

        // our Main 2 is our main phase too (F4 review): the same board after combat fetches the same way
        final Card later = dangerBoard();
        game.getPhaseHandler().devModeSet(PhaseType.MAIN2, ai);
        assertEquals(game.getPhaseHandler().getPhase(), PhaseType.MAIN2);
        assertTrue(game.getPhaseHandler().isPlayerTurn(ai));
        assertEquals(ComboSignals.abandon(ai, t.deckLines().get(0), t), ComboSignals.Signal.DANGER);
        final SpellAbility main2 = spell("Demonic Tutor");
        t.useLines(List.of());
        assertNotSame(pick(ZoneType.Hand, main2), later);
        t.useLines(lines(ORACLE_CONSULTATION));
        assertSame(pick(ZoneType.Hand, main2), later);
    }

    /** Line 742-2836 with Laboratory Maniac in play, Opt in hand, the ten-name library, and Island, Island and Swamp
     *  open in our Main 1, at 3 life against an opposing Colossal Dreadmaw (Lab chumps it, and its trample puts 4
     *  through): DANGER is the line's only signal and Consultation its one piece missing, so no line is one short.
     *  Consultation fetched now wins this turn: cast with Lab in play, Opt follows it on the emptied library, and the
     *  three lands pay Consultation's {B} and Opt's {U} together. Returns the library's Consultation. */
    private Card labDangerBoard() {
        start(LAB_CONSULTATION);
        addCard(LAB, ai);
        addCardToZone(OPT, ai, ZoneType.Hand);
        for (final String name : LIBRARY) {
            addCardToZone(name, ai, ZoneType.Library);
        }
        ai.setLife(3, null);
        addCard("Colossal Dreadmaw", opp).setSickness(false);
        assertEquals(t.where(LAB), ComboTracker.Where.BATTLEFIELD);
        assertEquals(t.missing(t.deckLines().get(0)), List.of(CONSULTATION));
        assertEquals(ComboSignals.abandon(ai, t.deckLines().get(0), t), ComboSignals.Signal.DANGER);
        assertEquals(t.oneShort(), List.of());
        return t.find(CONSULTATION, ZoneType.Library);
    }

    @Test
    public void dangerDoesNotRecountAPieceAlreadyInPlay() {
        // beyond the plan's tests (F4 review): a piece on our battlefield is cast already, so the win this turn costs
        // only Consultation's {B} and the following Opt's {U}, which Island, Island and Swamp pay; Laboratory Maniac's
        // {2}{U} counted beside them would need five
        final Card consultation = labDangerBoard();
        final SpellAbility tutor = spell("Demonic Tutor");
        t.useLines(List.of());
        assertNotSame(pick(ZoneType.Hand, tutor), consultation);

        t.useLines(lines(LAB_CONSULTATION));
        assertSame(pick(ZoneType.Hand, tutor), consultation);
    }

    @Test
    public void dangerSingleCostNeedsItsManaOpen() {
        // beyond the plan's tests (F4 review): a line with one cast left still needs that cast's mana open now. The
        // Swamp tapped: next turn's mana counts it (no NO_MANA), but nothing open now makes Consultation's {B}, though
        // the Islands make the following Opt's {U}
        labDangerBoard();
        for (final Card land : ai.getLandsInPlay()) {
            if ("Swamp".equals(land.getName())) {
                land.setTapped(true);
            }
        }
        assertEquals(ComboSignals.abandon(ai, t.deckLines().get(0), t), ComboSignals.Signal.DANGER);
        final SpellAbility tutor = spell("Demonic Tutor");
        assertWarmStockParity(() -> pick(ZoneType.Hand, tutor), lines(LAB_CONSULTATION));
    }

    /** Our {@code name} cast: on our stack, nothing paid or resolved (ComboSignalsTest's castOnStack). */
    private Card castOnStack(final String name) {
        final Card card = createCard(name, ai);
        card.setGameTimestamp(game.getNextTimestamp());
        game.getStackZone().add(card);
        final SpellAbility sa = card.getFirstSpellAbility();
        sa.setActivatingPlayer(ai);
        game.getStack().add(sa);
        return card;
    }

    @Test
    public void dangerDoesNotRecountAPieceOnOurStack() {
        // beyond the plan's tests (F4 review): a piece on our stack is cast already too. Thassa's Oracle cast, its
        // {U}{U} paid by the two Islands (tapped), and Consultation in the library at 3 life against the Dreadmaw: the
        // win this turn costs only Consultation's {B}, which the Swamp pays; Oracle's {U}{U} counted beside it would
        // not be open
        start(ORACLE_CONSULTATION);
        for (final String name : LIBRARY) {
            addCardToZone(name, ai, ZoneType.Library);
        }
        for (final Card land : ai.getLandsInPlay()) {
            if ("Island".equals(land.getName())) {
                land.setTapped(true);
            }
        }
        castOnStack(ORACLE);
        ai.setLife(3, null);
        addCard("Colossal Dreadmaw", opp).setSickness(false);
        assertEquals(t.where(ORACLE), ComboTracker.Where.STACK);
        assertEquals(t.missing(t.deckLines().get(0)), List.of(CONSULTATION));
        assertEquals(ComboSignals.abandon(ai, t.deckLines().get(0), t), ComboSignals.Signal.DANGER);
        assertEquals(t.oneShort(), List.of());
        final Card consultation = t.find(CONSULTATION, ZoneType.Library);
        final SpellAbility tutor = spell("Demonic Tutor");
        t.useLines(List.of());
        assertNotSame(pick(ZoneType.Hand, tutor), consultation);

        t.useLines(lines(ORACLE_CONSULTATION));
        assertSame(pick(ZoneType.Hand, tutor), consultation);
    }

    /** The opponent's {@code name}, cast and on the stack, nothing paid or resolved, aimed at nothing, the opponent its
     *  activator (ComboExecuteAiTest's theirSpell). */
    private void theirSpell(final String name) {
        final Card card = createCard(name, opp);
        card.setGameTimestamp(game.getNextTimestamp());
        game.getStackZone().add(card);
        final SpellAbility sa = card.getFirstSpellAbility();
        sa.setActivatingPlayer(opp);
        game.getStack().add(sa);
        assertSame(game.getStack().peek().getSourceCard(), card, name + " is not on the stack");
        assertSame(game.getStack().peek().getActivatingPlayer(), opp);
    }

    /** Follow-up 4, I1 review, round 1 (the plan's "followUpDraws is unchanged"): danger's win in time reads the main
     *  phase of ours the fetched piece arrives for, so no stack closes it, though the Laboratory Maniac window now
     *  opens only on an empty one. A tutor resolves on the stack: MagicStack.resolveStack leaves its spell there until
     *  it finishes. On the Laboratory Maniac danger board ({@link #labDangerBoard}: our Main 1, Opt to follow),
     *  Demonic Tutor on our stack alone, then above the opponent's Opt; and at the opponent's end step, our turn next,
     *  Vampiric Tutor on our stack, with Divination, a sorcery, to follow in our next Main 1 and every land tapped now.
     *  Each time the follow-up draws (ComboExecute.followUpDraws) still hold that draw spell, and the tutor takes
     *  Consultation, as on the empty stack (dangerDoesNotRecountAPieceAlreadyInPlay,
     *  dangerAtTheEndStepBeforeOurTurnReadsEachWinnersWindowNextTurn); with no line, another card. */
    @Test
    public void dangersWinInTimeReadsNoStack() {
        final List<ComboLines.Line> lab = lines(LAB_CONSULTATION);
        for (final boolean theirs : List.of(false, true)) {
            final String why = theirs ? "Demonic Tutor above the opponent's Opt" : "Demonic Tutor alone";
            final Card consultation = labDangerBoard();
            if (theirs) {
                theirSpell(OPT);
            }
            final SpellAbility tutor = castOnStack("Demonic Tutor").getFirstSpellAbility();
            assertEquals(game.getStack().size(), theirs ? 2 : 1, why);
            assertSame(game.getStack().peek().getSourceCard(), tutor.getHostCard(), why);
            assertEquals(ComboSignals.abandon(ai, t.deckLines().get(0), t), ComboSignals.Signal.DANGER, why);
            assertEquals(t.oneShort(), List.of(), why);
            assertFollowUpDraw(OPT, why);
            t.useLines(List.of());
            assertNotSame(pick(ZoneType.Hand, tutor), consultation, why);
            t.useLines(lab);
            assertSame(pick(ZoneType.Hand, tutor), consultation, why);
        }

        final String why = "Vampiric Tutor at the end step before our turn";
        final Card consultation = labDangerBoard();
        game.getAction().moveTo(ZoneType.Graveyard, t.find(OPT, ZoneType.Hand), null, AbilityKey.newMap());
        addCardToZone("Divination", ai, ZoneType.Hand);
        addCard("Island", ai);
        for (final Card land : ai.getLandsInPlay()) {
            land.setTapped(true);
        }
        game.getPhaseHandler().devModeSet(PhaseType.END_OF_TURN, opp);
        assertSame(game.getPhaseHandler().getNextTurn(), ai, why);
        final SpellAbility vampiric = castOnStack("Vampiric Tutor").getFirstSpellAbility();
        assertEquals(game.getStack().size(), 1, why);
        assertEquals(ComboSignals.abandon(ai, t.deckLines().get(0), t), ComboSignals.Signal.DANGER, why);
        assertFollowUpDraw("Divination", why);
        t.useLines(List.of());
        assertNotSame(pick(ZoneType.Library, vampiric), consultation, why);
        t.useLines(lab);
        assertSame(pick(ZoneType.Library, vampiric), consultation, why);
        assertEquals(t.knownTops(), Set.of(consultation.getId()), why);
    }

    // the follow-up draws as danger's win in time reads them (ComboExecute.followUpDraws, this turn's spells before):
    // one, the spell of our hand's card of that name
    private void assertFollowUpDraw(final String name, final String why) {
        final List<SpellAbility> draws = ComboExecute.followUpDraws(t.view(),
                new ArrayList<>(game.getStack().getSpellCardsCastThisTurn()));
        assertEquals(draws.size(), 1, why + ": " + draws);
        assertSame(draws.get(0).getHostCard(), t.find(name, ZoneType.Hand), why);
    }

    @Test
    public void dangerWithoutTheManaToWinTakesTheStockPick() {
        // the danger board on one land: Oracle's two and Consultation's one outrun next turn's one, so NO_MANA holds
        // too, and DANGER is not the line's only signal
        dangerBoard();
        final List<Card> lands = new ArrayList<>(ai.getLandsInPlay());
        for (final Card land : lands.subList(1, lands.size())) {
            game.getAction().moveTo(ZoneType.Graveyard, land, null, AbilityKey.newMap());
        }
        assertEquals(ai.getLandsInPlay().size(), 1);
        assertEquals(ComboSignals.abandon(ai, t.deckLines().get(0), t), ComboSignals.Signal.NO_MANA);
        assertNotNull(t.find(CONSULTATION, ZoneType.Library));
        final SpellAbility tutor = spell("Demonic Tutor");

        assertStockParity(() -> pick(ZoneType.Hand, tutor), lines(ORACLE_CONSULTATION));
    }

    /** {@link #assertStockParity} after one stock call with no line: on some boards the stock pick's first call makes
     *  draws its later calls do not (a throwaway probe of the tapped-Swamps board below: 22 draws, then 12 at every
     *  later call, with no line at all), so the parity compares two warm calls. */
    private <T> T assertWarmStockParity(final Supplier<T> decision, final List<ComboLines.Line> others) {
        t.useLines(List.of());
        decision.get();
        return assertStockParity(decision, others);
    }

    @Test
    public void dangerYieldsAfterOurMainPhasesWithoutTheManaNowAndToTheOtherSignals() {
        // beyond the plan's tests: the win this turn needs DANGER as the line's only signal, a main phase of ours still
        // to come this turn (follow-up 2, G4: our upkeep, draw step and combat too, dangerToHandInOurTurnUnchanged) and
        // the mana open now; each board below takes the stock pick with the stock draws
        final List<ComboLines.Line> held = lines(ORACLE_CONSULTATION);

        // the Swamps tapped: next turn's mana counts them (no NO_MANA), but nothing open now makes Consultation's {B}
        dangerBoard();
        for (final Card land : ai.getLandsInPlay()) {
            if ("Swamp".equals(land.getName())) {
                land.setTapped(true);
            }
        }
        assertEquals(ComboSignals.abandon(ai, t.deckLines().get(0), t), ComboSignals.Signal.DANGER);
        final SpellAbility tapped = spell("Demonic Tutor");
        assertWarmStockParity(() -> pick(ZoneType.Hand, tapped), held);

        // one Island tapped: Consultation's {B} is open now, Oracle's {U}{U} is not
        dangerBoard();
        final Card island = ai.getLandsInPlay().get(0);
        assertEquals(island.getName(), "Island");
        island.setTapped(true);
        assertEquals(ComboSignals.abandon(ai, t.deckLines().get(0), t), ComboSignals.Signal.DANGER);
        final SpellAbility oneIsland = spell("Demonic Tutor");
        assertWarmStockParity(() -> pick(ZoneType.Hand, oneIsland), held);

        // both Swamps held for the AI's next spell (AiCardMemory's HELD_MANA_SOURCES_FOR_NEXT_SPELL, which
        // isManaSourceReserved keeps from other payments): next turn's mana counts them, but Consultation's {B} is not
        // open to the win now
        dangerBoard();
        for (final Card land : ai.getLandsInPlay()) {
            if ("Swamp".equals(land.getName())) {
                AiCardMemory.rememberCard(ai, land, AiCardMemory.MemorySet.HELD_MANA_SOURCES_FOR_NEXT_SPELL);
            }
        }
        assertEquals(ComboSignals.abandon(ai, t.deckLines().get(0), t), ComboSignals.Signal.DANGER);
        final SpellAbility reserved = spell("Demonic Tutor");
        assertWarmStockParity(() -> pick(ZoneType.Hand, reserved), held);

        // Oracle exiled: the line is dead (a piece gone), though DANGER is its only signal, Consultation is its one
        // piece in the library and its {B} is open
        dangerBoard();
        game.getAction().moveTo(ZoneType.Exile, t.find(ORACLE, ZoneType.Hand), null, AbilityKey.newMap());
        assertEquals(ComboSignals.abandon(ai, t.deckLines().get(0), t), ComboSignals.Signal.DANGER);
        assertEquals(t.missing(t.deckLines().get(0)), List.of(CONSULTATION));
        final SpellAbility gone = spell("Demonic Tutor");
        assertWarmStockParity(() -> pick(ZoneType.Hand, gone), held);

        // Oracle in the library: two short, though the four lands pay both
        dangerBoard();
        game.getAction().moveTo(ZoneType.Library, t.find(ORACLE, ZoneType.Hand), null, AbilityKey.newMap());
        assertEquals(ComboSignals.abandon(ai, t.deckLines().get(0), t), ComboSignals.Signal.DANGER);
        assertEquals(t.missing(t.deckLines().get(0)), List.of(CONSULTATION, ORACLE));
        final SpellAbility twoShort = spell("Demonic Tutor");
        assertWarmStockParity(() -> pick(ZoneType.Hand, twoShort), held);

        // our end step, and the opponent's Main 1: no main phase of ours before the opponent's attack (follow-up 2, G4:
        // this case read our upkeep before, which a piece fetched to our hand now wins from, in our Main 1)
        dangerBoard();
        game.getPhaseHandler().devModeSet(PhaseType.END_OF_TURN, ai);
        assertEquals(ComboSignals.abandon(ai, t.deckLines().get(0), t), ComboSignals.Signal.DANGER);
        final SpellAbility endStep = spell("Demonic Tutor");
        assertWarmStockParity(() -> pick(ZoneType.Hand, endStep), held);
        dangerBoard();
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, opp);
        assertEquals(ComboSignals.abandon(ai, t.deckLines().get(0), t), ComboSignals.Signal.DANGER);
        final SpellAbility theirs = spell("Demonic Tutor");
        assertWarmStockParity(() -> pick(ZoneType.Hand, theirs), held);

        // a surer kill too: our Serra Angel flies over the Dreadmaw into the opponent's 4 life, and at our 2 life it
        // chumps the Dreadmaw, whose trample still puts 2 through
        dangerBoard();
        ai.setLife(2, null);
        opp.setLife(4, null);
        addCard("Serra Angel", ai).setSickness(false);
        assertEquals(ComboSignals.abandon(ai, t.deckLines().get(0), t), ComboSignals.Signal.DANGER);
        assertTrue(ComboSignals.surerKillNow(ai));
        final SpellAbility surer = spell("Demonic Tutor");
        assertWarmStockParity(() -> pick(ZoneType.Hand, surer), held);

        // denied: an opposing Platinum Angel, so we cannot win
        dangerBoard();
        addCard("Platinum Angel", opp);
        game.getAction().checkStateEffects(true);
        assertEquals(ComboSignals.abandon(ai, t.deckLines().get(0), t), ComboSignals.Signal.DENIED);
        final SpellAbility denied = spell("Demonic Tutor");
        assertWarmStockParity(() -> pick(ZoneType.Hand, denied), held);
    }

    @Test
    public void dangerTakesTheNearestWinFirst() {
        // beyond the plan's tests: two lines one short whose only signal is DANGER rank as the one-short lines do
        // (theCheapestOneShortLineThatTheListHoldsIsServed): Oracle's (3 still to cast) before Laboratory Maniac's
        // (4), though the deck lists Lab's first. Oracle's is won this turn on Island, Island, Swamp and Swamp; Lab's
        // only with a draw to follow Consultation (follow-up final review, round 1): Opt in hand and a fifth land,
        // which pay Lab's {2}{U}, Consultation's {B} and Opt's {U} together
        start(LAB_CONSULTATION, ORACLE_CONSULTATION);
        addCard("Swamp", ai);
        addCardToZone(CONSULTATION, ai, ZoneType.Hand);
        final Card oracle = addCardToZone(ORACLE, ai, ZoneType.Library);
        final Card lab = addCardToZone(LAB, ai, ZoneType.Library);
        final Card wurm = addCardToZone(WURM, ai, ZoneType.Library);
        assertEquals(ids(t.oneShort()), List.of("742-1295", "742-2836"));
        ai.setLife(3, null);
        addCard("Colossal Dreadmaw", opp).setSickness(false);
        assertEquals(ids(t.deckLines()), List.of("742-2836", "742-1295"));
        for (final ComboLines.Line l : t.deckLines()) {
            assertEquals(ComboSignals.abandon(ai, l, t), ComboSignals.Signal.DANGER, l.variant().id());
        }
        assertEquals(t.oneShort(), List.of());
        final SpellAbility tutor = spell("Demonic Tutor");

        assertSame(ComboFetch.searchPick(tutor, ZoneType.Hand, new CardCollection(List.of(lab, oracle, wurm)), ai, ai),
                oracle);
        // the first line's piece not offered, and no draw follows Laboratory Maniac: the next line wins nothing this
        // turn, and the stock pick decides
        assertNull(ComboFetch.searchPick(tutor, ZoneType.Hand, new CardCollection(List.of(wurm, lab)), ai, ai));

        // Opt in hand and a fifth land: the next line is served
        addCardToZone(OPT, ai, ZoneType.Hand);
        addCard("Swamp", ai);
        for (final ComboLines.Line l : t.deckLines()) {
            assertEquals(ComboSignals.abandon(ai, l, t), ComboSignals.Signal.DANGER, l.variant().id());
        }
        assertSame(ComboFetch.searchPick(tutor, ZoneType.Hand, new CardCollection(List.of(lab, oracle, wurm)), ai, ai),
                oracle);
        assertSame(ComboFetch.searchPick(tutor, ZoneType.Hand, new CardCollection(List.of(wurm, lab)), ai, ai), lab);
    }

    @Test
    public void dangerLabOrJaceWinsThisTurnOnlyWithADrawToFollow() {
        // follow-up final review, round 1: Laboratory Maniac and Jace win off a draw of ours after the emptier, and
        // under danger in our main phase only a draw spell of our hand can follow it this turn (the opponent's end step
        // comes after their attack). Without one, or without the mana for it beside the line's, the stock pick, with
        // the stock draws
        final List<ComboLines.Line> lab = lines(LAB_CONSULTATION);

        // Laboratory Maniac in play, no draw spell in hand
        labDangerBoard();
        game.getAction().moveTo(ZoneType.Graveyard, t.find(OPT, ZoneType.Hand), null, AbilityKey.newMap());
        assertEquals(ComboSignals.abandon(ai, t.deckLines().get(0), t), ComboSignals.Signal.DANGER);
        final SpellAbility noDraw = spell("Demonic Tutor");
        assertWarmStockParity(() -> pick(ZoneType.Hand, noDraw), lab);

        // Opt in hand, but both Islands tapped: Consultation's {B} is open now, Opt's {U} is not
        labDangerBoard();
        for (final Card land : ai.getLandsInPlay()) {
            if ("Island".equals(land.getName())) {
                land.setTapped(true);
            }
        }
        assertEquals(ComboSignals.abandon(ai, t.deckLines().get(0), t), ComboSignals.Signal.DANGER);
        final SpellAbility noBlue = spell("Demonic Tutor");
        assertWarmStockParity(() -> pick(ZoneType.Hand, noBlue), lab);

        // Laboratory Maniac in hand, Consultation in the library (the finding's Sauron state): Island, Island, Swamp
        // and Swamp pay Lab's {2}{U} and Consultation's {B}, but no draw follows
        start(LAB_CONSULTATION);
        addCard("Swamp", ai);
        addCardToZone(LAB, ai, ZoneType.Hand);
        for (final String name : LIBRARY) {
            addCardToZone(name, ai, ZoneType.Library);
        }
        ai.setLife(3, null);
        addCard("Colossal Dreadmaw", opp).setSickness(false);
        assertEquals(t.where(LAB), ComboTracker.Where.HAND);
        assertEquals(t.missing(t.deckLines().get(0)), List.of(CONSULTATION));
        assertEquals(ComboSignals.abandon(ai, t.deckLines().get(0), t), ComboSignals.Signal.DANGER);
        final SpellAbility inHand = spell("Demonic Tutor");
        assertWarmStockParity(() -> pick(ZoneType.Hand, inHand), lab);
        // Opt in hand too: Lab's, Consultation's and Opt's five mana outrun the four lands
        addCardToZone(OPT, ai, ZoneType.Hand);
        assertEquals(ComboSignals.abandon(ai, t.deckLines().get(0), t), ComboSignals.Signal.DANGER);
        assertWarmStockParity(() -> pick(ZoneType.Hand, inHand), lab);
        // a fifth land: Lab cast now, then Consultation and Opt, win this turn
        addCard("Island", ai);
        assertEquals(ComboSignals.abandon(ai, t.deckLines().get(0), t), ComboSignals.Signal.DANGER);
        final Card consultation = t.find(CONSULTATION, ZoneType.Library);
        t.useLines(List.of());
        assertNotSame(pick(ZoneType.Hand, inHand), consultation);
        t.useLines(lab);
        assertSame(pick(ZoneType.Hand, inHand), consultation);

        // Jace, Wielder of Mysteries in play: the same window, without and then with Opt in hand
        final List<ComboLines.Line> jace = lines(JACE_CONSULTATION);
        start(JACE_CONSULTATION);
        addCard(JACE, ai).setCounters(CounterEnumType.LOYALTY, 4);
        for (final String name : LIBRARY) {
            addCardToZone(name, ai, ZoneType.Library);
        }
        ai.setLife(3, null);
        addCard("Colossal Dreadmaw", opp).setSickness(false);
        assertEquals(t.where(JACE), ComboTracker.Where.BATTLEFIELD);
        assertEquals(t.missing(t.deckLines().get(0)), List.of(CONSULTATION));
        assertEquals(ComboSignals.abandon(ai, t.deckLines().get(0), t), ComboSignals.Signal.DANGER);
        final SpellAbility jaceTutor = spell("Demonic Tutor");
        assertWarmStockParity(() -> pick(ZoneType.Hand, jaceTutor), jace);
        addCardToZone(OPT, ai, ZoneType.Hand);
        final Card jaceConsultation = t.find(CONSULTATION, ZoneType.Library);
        t.useLines(List.of());
        assertNotSame(pick(ZoneType.Hand, jaceTutor), jaceConsultation);
        t.useLines(jace);
        assertSame(pick(ZoneType.Hand, jaceTutor), jaceConsultation);
    }

    @Test
    public void dangerFetchesLabOntoTheBattlefieldUncast() {
        // follow-up final review, round 1: a search to the battlefield puts Laboratory Maniac there uncast, so the win
        // this turn costs only Consultation's {B} and the following Opt's {U}, which Island, Island, Swamp and Swamp
        // pay; Lab's {2}{U} counted beside them would need five. Without Opt no draw follows: the stock pick
        start(LAB_CONSULTATION);
        addCard("Swamp", ai);
        addCardToZone(CONSULTATION, ai, ZoneType.Hand);
        addCardToZone(OPT, ai, ZoneType.Hand);
        for (final String name : LIBRARY) {
            addCardToZone(CONSULTATION.equals(name) ? LAB : name, ai, ZoneType.Library);
        }
        ai.setLife(3, null);
        addCard("Colossal Dreadmaw", opp).setSickness(false);
        assertEquals(t.missing(t.deckLines().get(0)), List.of(LAB));
        assertEquals(ComboSignals.abandon(ai, t.deckLines().get(0), t), ComboSignals.Signal.DANGER);
        assertEquals(t.oneShort(), List.of());
        final Card lab = t.find(LAB, ZoneType.Library);
        final SpellAbility chord = spell("Chord of Calling");
        t.useLines(List.of());
        assertNotSame(pick(ZoneType.Battlefield, chord), lab);
        t.useLines(lines(LAB_CONSULTATION));
        assertSame(pick(ZoneType.Battlefield, chord), lab);

        game.getAction().moveTo(ZoneType.Graveyard, t.find(OPT, ZoneType.Hand), null, AbilityKey.newMap());
        assertEquals(ComboSignals.abandon(ai, t.deckLines().get(0), t), ComboSignals.Signal.DANGER);
        assertWarmStockParity(() -> pick(ZoneType.Battlefield, chord), lines(LAB_CONSULTATION));
    }

    /** Line x-1 with Doctor Doom, Unrivaled in {@code zone} (summoning sick on our battlefield), the ten-name library,
     *  and Island, Island and three Swamps open in our Main 1, at 2 life against an opposing Colossal Dreadmaw (a Doom
     *  of ours in play blocks it, and its trample puts 2 through): DANGER is the line's only signal and Consultation
     *  its one piece missing, so no line is one short. Returns the Doom. */
    private Card doomDangerBoard(final ZoneType zone) {
        start(DOOM_CONSULTATION);
        addCard("Swamp", ai);
        addCard("Swamp", ai);
        final Card doom = addCardToZone(DOOM, ai, zone);
        for (final String name : LIBRARY) {
            addCardToZone(name, ai, ZoneType.Library);
        }
        ai.setLife(2, null);
        addCard("Colossal Dreadmaw", opp).setSickness(false);
        assertEquals(t.where(DOOM),
                zone == ZoneType.Hand ? ComboTracker.Where.HAND : ComboTracker.Where.BATTLEFIELD);
        assertEquals(t.missing(t.deckLines().get(0)), List.of(CONSULTATION));
        assertEquals(ComboSignals.abandon(ai, t.deckLines().get(0), t), ComboSignals.Signal.DANGER);
        assertEquals(t.oneShort(), List.of());
        return doom;
    }

    @Test
    public void dangerDoomWinsThisTurnOnlyReadyOnOurBattlefield() {
        // follow-up final review, round 1: Doctor Doom wins off its own {T} after Consultation, so this turn only a
        // Doom already on our battlefield, untapped and free of summoning sickness, wins
        final List<ComboLines.Line> doom = lines(DOOM_CONSULTATION);

        // Doom in hand (the finding's Sauron state): the five lands pay Doom's {2}{B}{B} and Consultation's {B}, but a
        // Doom cast now is summoning sick
        doomDangerBoard(ZoneType.Hand);
        final SpellAbility inHand = spell("Demonic Tutor");
        assertWarmStockParity(() -> pick(ZoneType.Hand, inHand), doom);

        // Doom on our battlefield, summoning sick, then tapped: no {T} this turn
        final Card inPlay = doomDangerBoard(ZoneType.Battlefield);
        assertTrue(inPlay.isSick());
        final SpellAbility tutor = spell("Demonic Tutor");
        assertWarmStockParity(() -> pick(ZoneType.Hand, tutor), doom);
        inPlay.setSickness(false);
        inPlay.setTapped(true);
        assertEquals(ComboSignals.abandon(ai, t.deckLines().get(0), t), ComboSignals.Signal.DANGER);
        assertWarmStockParity(() -> pick(ZoneType.Hand, tutor), doom);

        // untapped and free of summoning sickness: Consultation now, then Doom's {T}, win this turn
        inPlay.setTapped(false);
        assertEquals(ComboSignals.abandon(ai, t.deckLines().get(0), t), ComboSignals.Signal.DANGER);
        final Card consultation = t.find(CONSULTATION, ZoneType.Library);
        t.useLines(List.of());
        assertNotSame(pick(ZoneType.Hand, tutor), consultation);
        t.useLines(doom);
        assertSame(pick(ZoneType.Hand, tutor), consultation);
    }

    @Test
    public void dangerNeverFetchesForFblthp() {
        // follow-up final review, round 1: Fblthp, Impossibly Lost wins off its trigger on combat damage to an
        // opponent, which no fetch in a main phase can count on: none comes after combat, and before it Fblthp may not
        // attack, or be blocked. Line 742-8266, Island, Island and Swamp, at 3 life against an opposing Colossal
        // Dreadmaw
        final List<ComboLines.Line> fblthp = lines(FBLTHP_CONSULTATION);

        // Consultation in hand, Fblthp in the library (the finding's Yuriko state): Fblthp's {1}{U} and
        // Consultation's {B} are open, but a Fblthp cast now is summoning sick
        start(FBLTHP_CONSULTATION);
        addCardToZone(CONSULTATION, ai, ZoneType.Hand);
        addCardToZone(FBLTHP, ai, ZoneType.Library);
        for (final String name : LIBRARY.subList(1, LIBRARY.size())) {
            addCardToZone(name, ai, ZoneType.Library);
        }
        ai.setLife(3, null);
        addCard("Colossal Dreadmaw", opp).setSickness(false);
        assertEquals(t.missing(t.deckLines().get(0)), List.of(FBLTHP));
        assertEquals(ComboSignals.abandon(ai, t.deckLines().get(0), t), ComboSignals.Signal.DANGER);
        final SpellAbility tutor = spell("Demonic Tutor");
        assertWarmStockParity(() -> pick(ZoneType.Hand, tutor), fblthp);

        // Fblthp ready on our battlefield (it chumps the Dreadmaw, whose trample puts 5 through), Consultation in the
        // library: in our Main 1, and in our Main 2, after combat
        for (final PhaseType phase : List.of(PhaseType.MAIN1, PhaseType.MAIN2)) {
            start(FBLTHP_CONSULTATION);
            game.getPhaseHandler().devModeSet(phase, ai);
            addCard(FBLTHP, ai).setSickness(false);
            for (final String name : LIBRARY) {
                addCardToZone(name, ai, ZoneType.Library);
            }
            ai.setLife(3, null);
            addCard("Colossal Dreadmaw", opp).setSickness(false);
            assertEquals(game.getPhaseHandler().getPhase(), phase);
            assertEquals(t.missing(t.deckLines().get(0)), List.of(CONSULTATION), phase.name());
            assertEquals(ComboSignals.abandon(ai, t.deckLines().get(0), t), ComboSignals.Signal.DANGER, phase.name());
            final SpellAbility main = spell("Demonic Tutor");
            assertWarmStockParity(() -> pick(ZoneType.Hand, main), fblthp);
        }
    }

    @Test
    public void dangerNeverFetchesToTheTopInOurMainPhase() {
        // follow-up final review, round 1: a piece put on top of our library in our main phase is not in our hand this
        // turn, so a search or a dig to the top, and the Vault's stack, keep the one-short lines under danger. On the
        // danger board each makes the stock decision with the stock draws, and remembers no known top (follow-up 2, G4:
        // in our upkeep, and at the end step before our turn, the piece arrives in time and is fetched:
        // vampiricInOurUpkeepFetchesUnderDanger, vaultAtTheEndStepBeforeOurTurnStacksThePieceUnderDanger)
        final List<ComboLines.Line> held = lines(ORACLE_CONSULTATION);
        for (final String name : List.of("Imperial Seal", "Vampiric Tutor", "Mystical Tutor")) {
            final Card consultation = dangerBoard();
            final SpellAbility top = spell(name);
            assertEquals(top.getParam("LibraryPosition"), "0", name);
            assertNotSame(assertWarmStockParity(() -> pick(ZoneType.Library, top), held), consultation, name);
            assertEquals(t.knownTops(), Set.of(), name);
        }

        // Thassa's Oracle's own look (LibraryPosition$ 0)
        Card consultation = dangerBoard();
        final List<Card> shown = List.of(t.find(WURM, ZoneType.Library), consultation);
        final SpellAbility look = addCardToZone(ORACLE, ai, ZoneType.Hand).getTriggers().get(0).ensureAbility();
        look.setActivatingPlayer(ai);
        assertEquals(look.getParam("LibraryPosition"), "0");
        assertNull(ComboFetch.digPick(ai, look, shown, ai));
        assertEquals(t.knownTops(), Set.of());

        // the Vault: Consultation on top among the five shown, then five that miss it with it still in our library
        consultation = dangerBoard();
        final List<Card> lib = list(ai.getCardsIn(ZoneType.Library));
        assertSame(lib.get(0), consultation);
        assertNull(ComboFetch.vaultDig(ai, lib.subList(0, 5)));
        assertNull(ComboFetch.vaultDig(ai, lib.subList(1, 6)));
    }

    @Test
    public void dangerReadsTheCommanderTax() {
        // beyond the plan's tests: our commander Thassa's Oracle in the command zone, cast once before from there (2
        // more, Forge's tax), so the win this turn costs Oracle's {U}{U} and 2 and Consultation's {B}: five lands pay
        // it
        start(ORACLE_CONSULTATION);
        addCard("Swamp", ai);
        final Card swamp = addCard("Swamp", ai);
        final Card oracle = addCardToZone(ORACLE, ai, ZoneType.Command);
        oracle.setCommander(true);
        ai.incCommanderCast(oracle);
        for (final String name : LIBRARY) {
            addCardToZone(name, ai, ZoneType.Library);
        }
        ai.setLife(3, null);
        addCard("Colossal Dreadmaw", opp).setSickness(false);
        assertEquals(t.where(ORACLE), ComboTracker.Where.COMMAND);
        assertEquals(ComboSignals.abandon(ai, t.deckLines().get(0), t), ComboSignals.Signal.DANGER);
        final Card consultation = t.find(CONSULTATION, ZoneType.Library);
        final SpellAbility tutor = spell("Demonic Tutor");
        assertSame(pick(ZoneType.Hand, tutor), consultation);

        // one Swamp tapped: next turn's five still cover the 5 (no NO_MANA), but the four open now pay Oracle and
        // Consultation only without the tax
        swamp.setTapped(true);
        assertEquals(ComboSignals.abandon(ai, t.deckLines().get(0), t), ComboSignals.Signal.DANGER);
        assertWarmStockParity(() -> pick(ZoneType.Hand, tutor), lines(ORACLE_CONSULTATION));
    }

    @Test
    public void dangerStillDigsForAWinThisTurn() {
        // the win this turn holds for a dig to our hand as for a search (a dig to the top, and the Vault, never in our
        // main phase: dangerNeverFetchesToTheTopInOurMainPhase)
        final Card consultation = dangerBoard();
        final SpellAbility impulse = spell("Impulse");
        final List<Card> shown = List.of(t.find(WURM, ZoneType.Library), consultation);
        assertSame(ComboFetch.digPick(ai, impulse, shown, ai), consultation);
        assertSame(new DigAi().chooseSingleCard(ai, impulse, shown, false, null, Map.of()), consultation);

        // with no line, it is the stock rule
        t.useLines(List.of());
        assertNull(ComboFetch.digPick(ai, impulse, shown, ai));
    }

    // ---- follow-up 2, G2: denial at each line's own window ----

    /** Review Focus 2: an opposing Deafening Silence, one noncreature spell a turn. Demonic Tutor resolving in our
     *  Main 1 is this turn's noncreature spell, so Consultation cannot be cast this turn, and the Oracle line goes off
     *  on our next turn instead, where Consultation is the first noncreature spell: the line is not denied, and the
     *  tutor takes Consultation. Once it has resolved, Oracle is held for that turn: cast now, as a 1/3 in Main 2, it
     *  would be spent, and this turn's go-off is barred. With no line both choices are the stock ones. */
    @Test
    public void deafeningSilenceAfterATutorKeepsTheLineLive() {
        final Card consultation = oracleBoard();
        addCard("Deafening Silence", opp);
        final Card tutorCard = castOnStack("Demonic Tutor");
        final SpellAbility tutor = tutorCard.getFirstSpellAbility();
        assertEquals(game.getStack().getSpellCardsCastThisTurn(), List.of(tutorCard));
        final ComboLines.Line l = t.deckLines().get(0);
        assertTrue(ComboSignals.deniedNow(t.view(), l), "read now, Consultation would be this turn's second");
        assertTrue(ComboSignals.barredAtWindowNow(t.view(), l), "this turn's go-off is barred");
        assertNull(ComboSignals.abandon(ai, l, t));
        assertEquals(ids(t.live()), List.of("742-1295"));
        assertEquals(ids(t.oneShort()), List.of("742-1295"));
        // with no line the stock pick is another card
        t.useLines(List.of());
        assertNotSame(pick(ZoneType.Hand, tutor), consultation);
        t.useLines(lines(ORACLE_CONSULTATION));
        assertSame(pick(ZoneType.Hand, tutor), consultation);
        // and the tutor's resolution puts it in our hand
        AbilityUtils.resolve(tutor);
        assertEquals(t.where(CONSULTATION), ComboTracker.Where.HAND);

        // the tutor resolved and gone, still this turn's noncreature spell: Oracle and Consultation both in hand, the
        // line's mana open, in our Main 2
        game.getStack().clear();
        game.getStackZone().remove(tutorCard);
        ai.getZone(ZoneType.Graveyard).add(tutorCard);
        moveToMain2(game, ai);
        assertTrue(game.getStack().isEmpty());
        assertEquals(game.getStack().getSpellCardsCastThisTurn(), List.of(tutorCard));
        final Card oracle = t.find(ORACLE, ZoneType.Hand);
        final SpellAbility cast = oracle.getFirstSpellAbility();
        cast.setActivatingPlayer(ai);
        assertEquals(ids(t.live()), List.of("742-1295"));
        assertTrue(t.protects(oracle));
        assertTrue(t.protects(t.find(CONSULTATION, ZoneType.Hand)));
        assertFalse(ComboExecute.goOffNow(ai, cast), "this turn's go-off is barred");
        assertTrue(ComboHold.vetoesCast(ai, cast), "Oracle is held for next turn's go-off");
        assertNull(decision(), "nothing is cast: Oracle waits");
        // with no line the stock decision casts the 1/3
        t.useLines(List.of());
        assertSame(decision(), oracle);
    }

    /** The win this turn under danger reads the go-off's own bars this turn (G2): an opposing Deafening Silence and
     *  Opt cast this turn. The line goes off on our next turn, so DANGER is its only signal; but Consultation fetched
     *  now cannot be cast this turn, so no win this turn comes of it, and the tutor takes the stock pick. */
    @Test
    public void dangerWinThisTurnReadsThisTurnsBars() {
        dangerBoard();
        addCard("Deafening Silence", opp);
        castThisTurn("Opt");
        final ComboLines.Line l = t.deckLines().get(0);
        assertTrue(ComboSignals.barredAtWindowNow(t.view(), l));
        assertEquals(ComboSignals.abandon(ai, l, t), ComboSignals.Signal.DANGER);
        final SpellAbility tutor = spell("Demonic Tutor");
        assertWarmStockParity(() -> pick(ZoneType.Hand, tutor), lines(ORACLE_CONSULTATION));
    }

    /** The win this turn under danger reads the go-off's bars this turn, not only now (G2): our own Fires of Invention,
     *  two spells a turn. With no spell cast yet Oracle and Consultation are this turn's first two, and the tutor takes
     *  Consultation. After Opt, Consultation alone could be cast now, but Oracle then Consultation would be the second
     *  and third: this turn's go-off is barred, so no win this turn comes of the fetch, and the tutor takes the stock
     *  pick. */
    @Test
    public void dangerWinThisTurnReadsAFiresLimitOfTwo() {
        final Card consultation = dangerBoard();
        addCard("Fires of Invention", ai);
        assertFalse(ComboSignals.barredAtWindowNow(t.view(), t.deckLines().get(0)), "no spell of ours this turn");
        final SpellAbility first = spell("Demonic Tutor");
        assertSame(pick(ZoneType.Hand, first), consultation);

        dangerBoard();
        addCard("Fires of Invention", ai);
        castThisTurn("Opt");
        final ComboLines.Line l = t.deckLines().get(0);
        assertFalse(ComboSignals.deniedNow(t.view(), l), "read now, Consultation would be this turn's second");
        assertTrue(ComboSignals.barredAtWindowNow(t.view(), l), "Consultation would be this turn's third");
        assertEquals(ComboSignals.abandon(ai, l, t), ComboSignals.Signal.DANGER);
        final SpellAbility tutor = spell("Demonic Tutor");
        assertWarmStockParity(() -> pick(ZoneType.Hand, tutor), lines(ORACLE_CONSULTATION));
    }

    /** The win this turn under danger reads the bars of our main phase now too (G2): Laboratory Maniac's win this turn
     *  casts Consultation now, then Opt on the emptied library. With Opt cast this turn and no hoser the tutor takes
     *  Consultation. Under an opposing Rule of Law after that Opt, Consultation cannot be cast now, so no win this turn
     *  comes of the fetch, though the line's own window (the opponent's end step, a turn of its own) is not barred:
     *  the tutor takes the stock pick. */
    @Test
    public void dangerWinThisTurnReadsTheBarsOfTheMainPhaseNow() {
        final List<ComboLines.Line> lab = lines(LAB_CONSULTATION);
        final Card consultation = labDangerBoard();
        castThisTurn("Opt");
        final SpellAbility first = spell("Demonic Tutor");
        assertSame(pick(ZoneType.Hand, first), consultation);

        labDangerBoard();
        addCard("Rule of Law", opp);
        castThisTurn("Opt");
        final ComboLines.Line l = t.deckLines().get(0);
        assertTrue(ComboSignals.deniedNow(t.view(), l), "read now, Consultation would be this turn's second");
        assertFalse(ComboSignals.barredAtWindowNow(t.view(), l), "the end step is in a turn of its own");
        assertEquals(ComboSignals.abandon(ai, l, t), ComboSignals.Signal.DANGER);
        final SpellAbility tutor = spell("Demonic Tutor");
        assertWarmStockParity(() -> pick(ZoneType.Hand, tutor), lab);
    }

    /** Our {@code name} cast this turn and gone from the stack to our graveyard: the turn's record of its casts holds
     *  it (ComboSignalsTest's castThisTurn). */
    private void castThisTurn(final String name) {
        final Card card = castOnStack(name);
        game.getStack().clear();
        game.getStackZone().remove(card);
        ai.getZone(ZoneType.Graveyard).add(card);
        assertTrue(game.getStack().isEmpty());
    }

    /** The AI's choice of a spell to play now: the host of its first, or null for none. */
    private Card decision() {
        final List<SpellAbility> choice = aiOf(ai).chooseSpellAbilityToPlay();
        return choice == null || choice.isEmpty() ? null : choice.get(0).getHostCard();
    }

    // ---- follow-up 2, G4: under danger, a fetch takes the piece that arrives in time ----

    /** The danger board ({@link #dangerBoard}) at {@code phase} of our turn ({@code ours}) or of the opponent's
     *  (devModeSet: the stack, the lands and the library as they are). DANGER is still line 742-1295's only signal, so
     *  no line is one short. Returns the library's Consultation, the top card. */
    private Card dangerBoardAt(final PhaseType phase, final boolean ours) {
        final Card consultation = dangerBoard();
        final Player turn = ours ? ai : opp;
        game.getPhaseHandler().devModeSet(phase, turn);
        assertEquals(game.getPhaseHandler().getPhase(), phase);
        assertSame(game.getPhaseHandler().getPlayerTurn(), turn);
        assertEquals(ComboSignals.abandon(ai, t.deckLines().get(0), t), ComboSignals.Signal.DANGER, phase.name());
        assertEquals(t.oneShort(), List.of(), phase.name());
        assertSame(ai.getZone(ZoneType.Library).get(0), consultation);
        return consultation;
    }

    /** Taps every land of ours named {@code name}. */
    private void tapLands(final String name) {
        for (final Card land : ai.getLandsInPlay()) {
            if (name.equals(land.getName())) {
                land.setTapped(true);
            }
        }
    }

    /** Review Focus 4: Vampiric Tutor in our upkeep, on the danger board. The piece it puts on top of our library is
     *  drawn in our draw step and cast in our Main 1, before the opponent's attack, and this turn's mana (the four lands
     *  open now) pays Oracle's {U}{U} and Consultation's {B}: the tutor takes Consultation, a known top; with no line it
     *  is the stock pick. In our draw step the piece would be drawn only next turn, after the attack: the stock pick,
     *  with the stock draws. So it is in our upkeep with the Swamps tapped (next turn's mana counts them, this turn's
     *  makes no {B}), and under an opposing Deafening Silence with Vampiric Tutor this turn's noncreature spell (this
     *  turn's go-off is barred, though next turn's is not, so DANGER is still the only signal). */
    @Test
    public void vampiricInOurUpkeepFetchesUnderDanger() {
        final List<ComboLines.Line> held = lines(ORACLE_CONSULTATION);
        Card consultation = dangerBoardAt(PhaseType.UPKEEP, true);
        final SpellAbility vampiric = spell("Vampiric Tutor");
        assertEquals(vampiric.getParam("LibraryPosition"), "0");
        t.useLines(List.of());
        assertNotSame(pick(ZoneType.Library, vampiric), consultation);
        assertEquals(t.knownTops(), Set.of());
        t.useLines(held);
        assertSame(pick(ZoneType.Library, vampiric), consultation);
        assertEquals(t.knownTops(), Set.of(consultation.getId()));
        // Vampiric Tutor resolving: our search, the shuffle, then Consultation on top, where our draw step takes it
        AbilityUtils.resolve(vampiric);
        assertSame(ai.getZone(ZoneType.Library).get(0), consultation);
        assertEquals(t.knownTops(), Set.of(consultation.getId()));

        // our draw step: the piece is drawn only next turn
        consultation = dangerBoardAt(PhaseType.DRAW, true);
        final SpellAbility drawStep = spell("Vampiric Tutor");
        assertNotSame(assertWarmStockParity(() -> pick(ZoneType.Library, drawStep), held), consultation);
        assertEquals(t.knownTops(), Set.of());

        // this turn's mana short
        dangerBoardAt(PhaseType.UPKEEP, true);
        tapLands("Swamp");
        assertEquals(ComboSignals.abandon(ai, t.deckLines().get(0), t), ComboSignals.Signal.DANGER);
        final SpellAbility tapped = spell("Vampiric Tutor");
        assertWarmStockParity(() -> pick(ZoneType.Library, tapped), held);
        assertEquals(t.knownTops(), Set.of());

        // this turn's go-off barred
        dangerBoardAt(PhaseType.UPKEEP, true);
        addCard("Deafening Silence", opp);
        final Card cast = castOnStack("Vampiric Tutor");
        assertEquals(game.getStack().getSpellCardsCastThisTurn(), List.of(cast));
        final ComboLines.Line l = t.deckLines().get(0);
        assertTrue(ComboSignals.barredAtWindowNow(t.view(), l), "this turn's go-off is barred");
        assertEquals(ComboSignals.abandon(ai, l, t), ComboSignals.Signal.DANGER, "next turn's is not");
        final SpellAbility silenced = cast.getFirstSpellAbility();
        assertWarmStockParity(() -> pick(ZoneType.Library, silenced), held);
        assertEquals(t.knownTops(), Set.of());
    }

    /** The Vault on the board as it is, Consultation the library's top card, its holds checked against the stock rule
     *  with no line: the five on top (holding Consultation) and the five under it (missing it, Consultation still in our
     *  library); and its stack (the last move, Craw Wurm, Consultation and an Island put back on top in the AI's order:
     *  the last card of the list ends on top). {@code inTime}: the Vault keeps the first five, digs past the second
     *  and stacks Consultation on top, a known top; else each is the stock rule. The stock stack puts another card on
     *  top. Either way the same three put on the bottom (the Vault's GoToBottom, LibraryPosition$ -1) keep the stock
     *  order, with the stock draws: only a move to the top takes the piece first. */
    private void assertVault(final List<ComboLines.Line> held, final boolean inTime, final String why) {
        final List<Card> lib = list(ai.getCardsIn(ZoneType.Library));
        final Card consultation = lib.get(0);
        assertEquals(consultation.getName(), CONSULTATION, why);
        final List<Card> five = List.copyOf(lib.subList(0, 5));
        final List<Card> below = List.copyOf(lib.subList(1, 6));
        final Card wurm = t.find(WURM, ZoneType.Library);
        final Card island = addCardToZone("Island", ai, ZoneType.Library);
        final SpellAbility reorder = vaultReorder();
        final SpellAbility bottom = vaultGoToBottom();
        final Supplier<Card> top = () -> {
            final List<Card> order = list(pc().orderMoveToZoneList(new CardCollection(List.of(wurm, consultation,
                    island)), ZoneType.Library, reorder));
            return order.get(order.size() - 1);
        };
        t.useLines(List.of());
        assertNull(ComboFetch.vaultDig(ai, five), why);
        assertNotSame(top.get(), consultation, why);
        assertEquals(t.knownTops(), Set.of(), why);
        final List<Card> bottomed = assertStockParity(() -> list(pc().orderMoveToZoneList(new CardCollection(
                List.of(wurm, consultation, island)), ZoneType.Library, bottom)), held);
        assertNotSame(bottomed.get(0), consultation, why);
        assertEquals(t.knownTops(), Set.of(), why);
        if (!inTime) {
            assertNull(ComboFetch.vaultDig(ai, five), why);
            assertNull(ComboFetch.vaultDig(ai, below), why);
            assertNotSame(assertStockParity(top, held), consultation, why);
            assertEquals(t.knownTops(), Set.of(), why);
            return;
        }
        assertEquals(ComboFetch.vaultDig(ai, five), Boolean.FALSE, why);
        assertEquals(ComboFetch.vaultDig(ai, below), Boolean.TRUE, why);
        assertSame(top.get(), consultation, why);
        assertEquals(t.knownTops(), Set.of(consultation.getId()), why);
    }

    /** Review Focus 4: Lim-Dul's Vault at the end step of the opponent whose turn comes right before ours, on the
     *  danger board. The five it stacks on top of our library are drawn from in our draw step, so Consultation stacked
     *  first is in our hand for our Main 1, before that opponent attacks again, and next turn's untapped mana pays
     *  Oracle's {U}{U} and Consultation's {B}: the Vault keeps a five holding Consultation, digs past one missing it,
     *  and stacks Consultation on top ({@link #assertVault}); a scry's kept cards there keep the stock order (the Vault's
     *  stack is the order of a move to the top). So it is in that turn's cleanup ("or later in their
     *  turn"), with every land of ours tapped now (our untap step untaps them), and under an opposing Deafening Silence
     *  with the Vault this turn's noncreature spell (Consultation is next turn's first: denied now, not at its window).
     *  The stock rule in the opponent's Main 1 (before their attack) and Main 2, and at our own end step (the
     *  opponent's turn comes before our next main phase). With three seats, at the end step of the opponent right
     *  before us the Vault stacks Consultation; at the end step of the one right after us, the other opponent's turn
     *  comes first: the stock rule. */
    @Test
    public void vaultAtTheEndStepBeforeOurTurnStacksThePieceUnderDanger() {
        final List<ComboLines.Line> held = lines(ORACLE_CONSULTATION);
        for (final PhaseType phase : List.of(PhaseType.END_OF_TURN, PhaseType.CLEANUP)) {
            dangerBoardAt(phase, false);
            assertSame(game.getPhaseHandler().getNextTurn(), ai);
            assertVault(held, true, phase.name());
        }

        // a scry's kept cards at that end step are not the Vault's stack: Runeclaw Bear and Consultation, both kept,
        // come back in the stock order, whichever order the shuffle gives them, with no live line to protect the piece
        final Card consultation = dangerBoardAt(PhaseType.END_OF_TURN, false);
        final Card bear = t.find("Runeclaw Bear", ZoneType.Library);
        final CardCollection scried = new CardCollection(List.of(bear, consultation));
        final List<List<Card>> orders = new ArrayList<>();
        for (final long seed : SHUFFLE_SEEDS) {
            t.useLines(List.of());
            MyRandom.setRandom(new Random(seed));
            final ImmutablePair<CardCollection, CardCollection> stock = pc().arrangeForScry(scried);
            t.useLines(held);
            MyRandom.setRandom(new Random(seed));
            final ImmutablePair<CardCollection, CardCollection> ours = pc().arrangeForScry(scried);
            assertEquals(list(ours.getLeft()), list(stock.getLeft()), "seed " + seed);
            assertEquals(list(ours.getRight()), list(stock.getRight()), "seed " + seed);
            orders.add(list(stock.getLeft()));
        }
        assertEquals(orders.get(0).size(), 2, "both kept");
        assertNotEquals(orders.get(0), orders.get(1), "the two seeds give both orders");
        assertEquals(t.knownTops(), Set.of());

        dangerBoardAt(PhaseType.END_OF_TURN, false);
        for (final Card land : ai.getLandsInPlay()) {
            land.setTapped(true);
        }
        assertEquals(ComboSignals.abandon(ai, t.deckLines().get(0), t), ComboSignals.Signal.DANGER);
        assertVault(held, true, "every land tapped now");

        dangerBoardAt(PhaseType.END_OF_TURN, false);
        addCard("Deafening Silence", opp);
        castOnStack(SpecialCardAi.LimDulsVault.NAME);
        final ComboLines.Line l = t.deckLines().get(0);
        assertTrue(ComboSignals.deniedNow(t.view(), l), "read now, Consultation would be this turn's second");
        assertEquals(ComboSignals.abandon(ai, l, t), ComboSignals.Signal.DANGER, "next turn's go-off is not barred");
        assertVault(held, true, "Deafening Silence");

        dangerBoardAt(PhaseType.MAIN1, false);
        assertVault(held, false, "the opponent's Main 1");
        dangerBoardAt(PhaseType.MAIN2, false);
        assertVault(held, false, "the opponent's Main 2");
        dangerBoardAt(PhaseType.END_OF_TURN, true);
        assertNotSame(game.getPhaseHandler().getNextTurn(), ai);
        assertVault(held, false, "our end step");

        // three seats: the opponent right before us (the Dreadmaw's), us, the opponent right after us
        for (final int seat : List.of(0, 2)) {
            game = initAndCreateThreePlayerGame();
            ai = game.getPlayers().get(1);
            opp = game.getPlayers().get(0);
            ((LobbyPlayerAi) ai.getLobbyPlayer()).setAiProfile("Default");
            t = aiOf(ai).getComboTracker();
            t.useLines(held);
            for (final String land : List.of("Island", "Island", "Swamp", "Swamp")) {
                addCard(land, ai);
            }
            addCardToZone(ORACLE, ai, ZoneType.Hand);
            for (final String name : LIBRARY) {
                addCardToZone(name, ai, ZoneType.Library);
            }
            ai.setLife(3, null);
            addCard("Colossal Dreadmaw", opp).setSickness(false);
            final Player endStep = game.getPlayers().get(seat);
            game.getPhaseHandler().devModeSet(PhaseType.END_OF_TURN, endStep);
            assertEquals(game.getPhaseHandler().getNextTurn() == ai, seat == 0);
            assertEquals(ComboSignals.abandon(ai, t.deckLines().get(0), t), ComboSignals.Signal.DANGER);
            assertVault(held, seat == 0, "the end step of seat " + seat);
        }
    }

    /** Imperial Seal in our Main 1, on the danger board: its piece goes on top of our library, drawn only in our next
     *  draw step, after the opponent's attack. No win comes in time, so it is the stock pick, with the stock draws, and
     *  nothing is a known top. So it is for a fetch to the top in every other step of our turn past our upkeep (Vampiric
     *  Tutor in our draw step, combat, Main 2 and end step), in the opponent's turn before their end step (their Main 1
     *  and Main 2), and for the Vault in our Main 1 (its holds and its stack the stock rule's). */
    @Test
    public void imperialSealInMainOneStillTakesTheStockPick() {
        final List<ComboLines.Line> held = lines(ORACLE_CONSULTATION);
        Card consultation = dangerBoardAt(PhaseType.MAIN1, true);
        final SpellAbility seal = spell("Imperial Seal");
        assertEquals(seal.getParam("LibraryPosition"), "0");
        assertNotSame(assertWarmStockParity(() -> pick(ZoneType.Library, seal), held), consultation);
        assertEquals(t.knownTops(), Set.of());

        final Object[][] moments = {{PhaseType.DRAW, true}, {PhaseType.COMBAT_END, true}, {PhaseType.MAIN2, true},
                {PhaseType.END_OF_TURN, true}, {PhaseType.MAIN1, false}, {PhaseType.MAIN2, false}};
        for (final Object[] moment : moments) {
            final PhaseType phase = (PhaseType) moment[0];
            final boolean ours = (Boolean) moment[1];
            final String why = phase.name() + (ours ? " ours" : " theirs");
            consultation = dangerBoardAt(phase, ours);
            final SpellAbility vampiric = spell("Vampiric Tutor");
            assertNotSame(assertWarmStockParity(() -> pick(ZoneType.Library, vampiric), held), consultation, why);
            assertEquals(t.knownTops(), Set.of(), why);
        }

        dangerBoardAt(PhaseType.MAIN1, true);
        assertVault(held, false, "our Main 1");
    }

    /** A fetch to our hand under danger, in our turn: the piece is in our hand at once and the win comes in a main phase
     *  of ours this turn, read with this turn's mana and this turn's denial. Demonic Tutor on the danger board takes
     *  Consultation in every step of our turn before our end step: Main 1 and Main 2 as before, and (follow-up 2, G4)
     *  our upkeep, draw step and combat, whose piece is in our hand before a main phase of ours as well (the base read
     *  our main phases only). With no line it is the stock pick. In our Main 1 with the Swamps tapped (next turn's mana
     *  counts them, this turn's makes no {B}) the stock pick: next turn's mana is never read for a fetch to our hand. At
     *  our end step (our next main phase comes after the opponent's attack) and in the opponent's turn (their Main 1,
     *  before their attack, and their end step: a fetch to our hand there keeps today's rule), the stock pick. */
    @Test
    public void dangerToHandInOurTurnUnchanged() {
        final List<ComboLines.Line> held = lines(ORACLE_CONSULTATION);
        for (final PhaseType phase : List.of(PhaseType.UPKEEP, PhaseType.DRAW, PhaseType.MAIN1, PhaseType.COMBAT_END,
                PhaseType.MAIN2)) {
            final Card consultation = dangerBoardAt(phase, true);
            final SpellAbility tutor = spell("Demonic Tutor");
            t.useLines(List.of());
            assertNotSame(pick(ZoneType.Hand, tutor), consultation, phase.name());
            t.useLines(held);
            assertSame(pick(ZoneType.Hand, tutor), consultation, phase.name());
            assertEquals(t.knownTops(), Set.of(), phase.name());
        }

        dangerBoardAt(PhaseType.MAIN1, true);
        tapLands("Swamp");
        assertEquals(ComboSignals.abandon(ai, t.deckLines().get(0), t), ComboSignals.Signal.DANGER);
        final SpellAbility tapped = spell("Demonic Tutor");
        assertWarmStockParity(() -> pick(ZoneType.Hand, tapped), held);

        final Object[][] moments = {{PhaseType.END_OF_TURN, true}, {PhaseType.MAIN1, false},
                {PhaseType.END_OF_TURN, false}};
        for (final Object[] moment : moments) {
            final PhaseType phase = (PhaseType) moment[0];
            final boolean ours = (Boolean) moment[1];
            final String why = phase.name() + (ours ? " ours" : " theirs");
            final Card consultation = dangerBoardAt(phase, ours);
            final SpellAbility tutor = spell("Demonic Tutor");
            assertNotSame(assertWarmStockParity(() -> pick(ZoneType.Hand, tutor), held), consultation, why);
        }
    }

    /** Vampiric Tutor at the end step of the opponent whose turn comes right before ours (follow-up 2, G4): the piece
     *  is drawn in our draw step, and each winner's window is read in our next Main 1 (F4's per-winner rules), with
     *  next turn's untapped mana. With no line each is the stock pick.
     *  <ul>
     *  <li>Laboratory Maniac in play: Consultation cast in our Main 1 with a draw spell of our hand to follow it, paid
     *  beside it: Opt (an instant), and Divination (a sorcery: none can be cast now, but our main phase casts it), on
     *  four lands, every one tapped now. With three lands Divination's {2}{U} and Consultation's {B} outrun them, and
     *  with no draw spell no draw follows: the stock pick.
     *  <li>Doctor Doom on our battlefield, summoning sick now, then tapped too: our next turn untaps it, free of
     *  summoning sickness, and its {T} follows Consultation. Doom in our hand would be cast summoning sick: the stock
     *  pick.
     *  <li>Fblthp, Impossibly Lost ready on our battlefield: never (its win needs combat damage).
     *  <li>Thassa's Oracle on the danger board, both Swamps held now for the AI's next spell: the AI's card memory resets
     *  at each end step, so next turn's mana counts them, and it takes Consultation.
     *  <li>Thassa's Oracle with Island and Island in play and {B} floating: the pool empties before our turn, so next
     *  turn's mana makes no {B} for Consultation: the stock pick, though next turn's mana count (signal 1's) is met.
     *  </ul> */
    @Test
    public void dangerAtTheEndStepBeforeOurTurnReadsEachWinnersWindowNextTurn() {
        final List<ComboLines.Line> lab = lines(LAB_CONSULTATION);
        Card consultation = labDangerBoard();
        game.getPhaseHandler().devModeSet(PhaseType.END_OF_TURN, opp);
        assertEquals(ComboSignals.abandon(ai, t.deckLines().get(0), t), ComboSignals.Signal.DANGER);
        assertEndStepFetch(consultation, lab, "Opt");

        consultation = labDangerBoard();
        game.getAction().moveTo(ZoneType.Graveyard, t.find(OPT, ZoneType.Hand), null, AbilityKey.newMap());
        addCardToZone("Divination", ai, ZoneType.Hand);
        addCard("Island", ai);
        for (final Card land : ai.getLandsInPlay()) {
            land.setTapped(true);
        }
        game.getPhaseHandler().devModeSet(PhaseType.END_OF_TURN, opp);
        assertEquals(ComboSignals.abandon(ai, t.deckLines().get(0), t), ComboSignals.Signal.DANGER);
        assertEndStepFetch(consultation, lab, "Divination");

        // Divination on three lands
        labDangerBoard();
        game.getAction().moveTo(ZoneType.Graveyard, t.find(OPT, ZoneType.Hand), null, AbilityKey.newMap());
        addCardToZone("Divination", ai, ZoneType.Hand);
        game.getPhaseHandler().devModeSet(PhaseType.END_OF_TURN, opp);
        assertEquals(ComboSignals.abandon(ai, t.deckLines().get(0), t), ComboSignals.Signal.DANGER);
        final SpellAbility threeLands = spell("Vampiric Tutor");
        assertWarmStockParity(() -> pick(ZoneType.Library, threeLands), lab);
        // no draw spell
        labDangerBoard();
        game.getAction().moveTo(ZoneType.Graveyard, t.find(OPT, ZoneType.Hand), null, AbilityKey.newMap());
        game.getPhaseHandler().devModeSet(PhaseType.END_OF_TURN, opp);
        assertEquals(ComboSignals.abandon(ai, t.deckLines().get(0), t), ComboSignals.Signal.DANGER);
        final SpellAbility noDraw = spell("Vampiric Tutor");
        assertWarmStockParity(() -> pick(ZoneType.Library, noDraw), lab);

        // Doctor Doom
        final List<ComboLines.Line> doom = lines(DOOM_CONSULTATION);
        final Card sick = doomDangerBoard(ZoneType.Battlefield);
        assertTrue(sick.isSick());
        game.getPhaseHandler().devModeSet(PhaseType.END_OF_TURN, opp);
        assertEquals(ComboSignals.abandon(ai, t.deckLines().get(0), t), ComboSignals.Signal.DANGER);
        assertEndStepFetch(t.find(CONSULTATION, ZoneType.Library), doom, "Doom summoning sick");
        sick.setTapped(true);
        assertEquals(ComboSignals.abandon(ai, t.deckLines().get(0), t), ComboSignals.Signal.DANGER);
        assertEndStepFetch(t.find(CONSULTATION, ZoneType.Library), doom, "Doom tapped");
        doomDangerBoard(ZoneType.Hand);
        game.getPhaseHandler().devModeSet(PhaseType.END_OF_TURN, opp);
        assertEquals(ComboSignals.abandon(ai, t.deckLines().get(0), t), ComboSignals.Signal.DANGER);
        final SpellAbility doomInHand = spell("Vampiric Tutor");
        assertWarmStockParity(() -> pick(ZoneType.Library, doomInHand), doom);

        // Fblthp
        final List<ComboLines.Line> fblthp = lines(FBLTHP_CONSULTATION);
        start(FBLTHP_CONSULTATION);
        addCard(FBLTHP, ai).setSickness(false);
        for (final String name : LIBRARY) {
            addCardToZone(name, ai, ZoneType.Library);
        }
        ai.setLife(3, null);
        addCard("Colossal Dreadmaw", opp).setSickness(false);
        game.getPhaseHandler().devModeSet(PhaseType.END_OF_TURN, opp);
        assertEquals(t.missing(t.deckLines().get(0)), List.of(CONSULTATION));
        assertEquals(ComboSignals.abandon(ai, t.deckLines().get(0), t), ComboSignals.Signal.DANGER);
        final SpellAbility never = spell("Vampiric Tutor");
        assertWarmStockParity(() -> pick(ZoneType.Library, never), fblthp);

        // Thassa's Oracle, both Swamps held for the AI's next spell (AiCardMemory's HELD_MANA_SOURCES_FOR_NEXT_SPELL,
        // which isManaSourceReserved keeps from other payments now): the memory resets at each end step, so next
        // turn's mana counts them
        final Card reserved = dangerBoardAt(PhaseType.END_OF_TURN, false);
        for (final Card land : ai.getLandsInPlay()) {
            if ("Swamp".equals(land.getName())) {
                AiCardMemory.rememberCard(ai, land, AiCardMemory.MemorySet.HELD_MANA_SOURCES_FOR_NEXT_SPELL);
            }
        }
        assertEndStepFetch(reserved, lines(ORACLE_CONSULTATION), "Swamps held now");

        // Thassa's Oracle, {B} floating
        dangerBoardAt(PhaseType.END_OF_TURN, false);
        for (final Card land : new ArrayList<>(ai.getLandsInPlay())) {
            if ("Swamp".equals(land.getName())) {
                game.getAction().moveTo(ZoneType.Graveyard, land, null, AbilityKey.newMap());
            }
        }
        ai.getManaPool().addMana(new Mana((byte) ManaAtom.BLACK, createCard("Swamp", ai), null, ai));
        assertEquals(ai.getManaPool().totalMana(), 1);
        assertEquals(ComboSignals.nextTurnManaNow(ai), 3, "Island, Island and the floating {B}");
        assertEquals(ComboSignals.abandon(ai, t.deckLines().get(0), t), ComboSignals.Signal.DANGER);
        final SpellAbility floating = spell("Vampiric Tutor");
        assertWarmStockParity(() -> pick(ZoneType.Library, floating), lines(ORACLE_CONSULTATION));
    }

    /** Vampiric Tutor on the board as it is: with no line another card, with {@code held} {@code consultation}, a known
     *  top. */
    private void assertEndStepFetch(final Card consultation, final List<ComboLines.Line> held, final String why) {
        final SpellAbility vampiric = spell("Vampiric Tutor");
        t.useLines(List.of());
        assertNotSame(pick(ZoneType.Library, vampiric), consultation, why);
        t.useLines(held);
        assertSame(pick(ZoneType.Library, vampiric), consultation, why);
        assertEquals(t.knownTops(), Set.of(consultation.getId()), why);
    }

    /** A line denied at its own window on our next turn takes no fetch at the end step before that turn (follow-up 2,
     *  G4 review, round 1; spec section 6.5: DENIED still blocks under danger). There the fetch reads no denial of
     *  this turn's (the view's deniedThisTurn: the piece is cast only next turn), so the abandon signal's DENIED, at
     *  the line's own window on that turn, is the one gate. On the danger board at the end step and in the cleanup of
     *  the opponent whose turn comes right before ours, with no hoser, Vampiric Tutor takes Consultation
     *  ({@link #assertEndStepFetch}). Under an opposing Platinum Angel (we cannot win) or Rule of Law (Consultation
     *  would be our next turn's second spell, cast in response to Oracle's trigger) the line is DENIED, DANGER still
     *  holding: Vampiric Tutor takes the stock pick with the stock draws and leaves no known top, and the Vault keeps
     *  the stock holds and stack ({@link #assertVault}). */
    @Test
    public void aLineDeniedAtItsWindowNextTurnTakesNoFetchAtTheEndStepBeforeIt() {
        final List<ComboLines.Line> held = lines(ORACLE_CONSULTATION);
        for (final PhaseType phase : List.of(PhaseType.END_OF_TURN, PhaseType.CLEANUP)) {
            assertEndStepFetch(dangerBoardAt(phase, false), held, phase.name() + ", no hoser");
            for (final String hoser : List.of("Platinum Angel", "Rule of Law")) {
                final String why = phase.name() + ", " + hoser;
                final Card consultation = deniedAtTheEndStepBeforeOurTurn(phase, hoser, why);
                final SpellAbility vampiric = spell("Vampiric Tutor");
                assertNotSame(assertWarmStockParity(() -> pick(ZoneType.Library, vampiric), held), consultation, why);
                assertEquals(t.knownTops(), Set.of(), why);

                deniedAtTheEndStepBeforeOurTurn(phase, hoser, why);
                assertVault(held, false, why);
            }
        }
    }

    /** The danger board ({@link #dangerBoardAt}) at {@code phase} of the opponent's turn, ours next, under the
     *  opponent's {@code hoser}: line 742-1295 is DENIED, and DANGER still holds with no surer kill. Returns the
     *  library's Consultation, the top card. */
    private Card deniedAtTheEndStepBeforeOurTurn(final PhaseType phase, final String hoser, final String why) {
        final Card consultation = dangerBoardAt(phase, false);
        assertSame(game.getPhaseHandler().getNextTurn(), ai, why);
        addCard(hoser, opp);
        game.getAction().checkStateEffects(true);
        assertEquals(game.getPhaseHandler().getPhase(), phase, why);
        assertEquals(ComboSignals.abandon(ai, t.deckLines().get(0), t), ComboSignals.Signal.DENIED, why);
        assertTrue(t.view().danger(), why);
        assertFalse(ComboSignals.surerKillNow(ai), why);
        assertEquals(t.oneShort(), List.of(), why);
        assertSame(ai.getZone(ZoneType.Library).get(0), consultation, why);
        return consultation;
    }

    // ---- follow-up final review, round 1: danger's win in time at engine prices ----

    /** The danger board ({@link #dangerBoardAt}) at {@code phase} of our turn ({@code ours}) or of the opponent's,
     *  under the opponent's {@code tax}, with {@code lands} lands of ours in play: three (Island, Island and Swamp, its
     *  second Swamp gone), its four, or five (an Island more). Signal 1 reads printed mana values (Oracle's two and
     *  Consultation's one against next turn's three to five), so DANGER is still line 742-1295's only signal, with no
     *  surer kill, and no line is one short. Returns the library's Consultation, the top card. */
    private Card taxedDangerBoardAt(final PhaseType phase, final boolean ours, final String tax, final int lands) {
        final Card consultation = dangerBoardAt(phase, ours);
        if (lands == 3) {
            Card swamp = null;
            for (final Card land : ai.getLandsInPlay()) {
                if ("Swamp".equals(land.getName())) {
                    swamp = land;
                }
            }
            game.getAction().moveTo(ZoneType.Graveyard, swamp, null, AbilityKey.newMap());
        } else if (lands == 5) {
            addCard("Island", ai);
        }
        addCard(tax, opp);
        game.getAction().checkStateEffects(true);
        final String why = tax + ", " + lands + " lands, " + phase.name();
        assertEquals(ai.getLandsInPlay().size(), lands, why);
        assertEquals(game.getPhaseHandler().getPhase(), phase, why);
        assertEquals(ComboSignals.abandon(ai, t.deckLines().get(0), t), ComboSignals.Signal.DANGER, why);
        assertFalse(ComboSignals.surerKillNow(ai), why);
        assertEquals(t.oneShort(), List.of(), why);
        assertSame(ai.getZone(ZoneType.Library).get(0), consultation, why);
        return consultation;
    }

    /** Danger's win in time reads each cost as the engine prices it (follow-up final review, round 1; spec section
     *  6.5, the line's own window, whose mana section 6.4 reads at engine prices), as the go-off it predicts does
     *  (ComboExecute.goOffNow): a fetch for a go-off that the tax leaves unpaid would give up the stock pick, the
     *  AI's play to live, for a win that cannot come. Demonic Tutor in our Main 1 under an opposing Sphere of
     *  Resistance: Oracle's {1}{U}{U} and Consultation's {1}{B} outrun the danger board's four lands, though the
     *  printed {U}{U} and {B} do not, so the stock pick, with the stock draws; five lands pay them, and it takes
     *  Consultation. Under an opposing Thalia, Guardian of Thraben (noncreature spells: Consultation's {1}{B} beside
     *  Oracle's {U}{U}), three lands the stock pick, four Consultation. With no line each is another card. The draw
     *  that follows a Laboratory Maniac line is priced the same way (ComboExecute.drawFollows): with Lab in play and
     *  Opt in hand ({@link #labDangerBoard}) under Thalia, Consultation's {1}{B} and Opt's {1}{U} outrun Island, Island
     *  and Swamp, though the printed {B} and {U} do not: the stock pick; an Island more pays them: Consultation. */
    @Test
    public void dangerReadsEngineCostsForAWinThisTurn() {
        final List<ComboLines.Line> held = lines(ORACLE_CONSULTATION);
        final Object[][] boards = {{"Sphere of Resistance", 4, false}, {"Sphere of Resistance", 5, true},
                {"Thalia, Guardian of Thraben", 3, false}, {"Thalia, Guardian of Thraben", 4, true}};
        for (final Object[] board : boards) {
            final String tax = (String) board[0];
            final int lands = (Integer) board[1];
            final String why = tax + ", " + lands + " lands";
            final Card consultation = taxedDangerBoardAt(PhaseType.MAIN1, true, tax, lands);
            final SpellAbility tutor = spell("Demonic Tutor");
            if ((Boolean) board[2]) {
                t.useLines(List.of());
                assertNotSame(pick(ZoneType.Hand, tutor), consultation, why);
                t.useLines(held);
                assertSame(pick(ZoneType.Hand, tutor), consultation, why);
            } else {
                assertNotSame(assertWarmStockParity(() -> pick(ZoneType.Hand, tutor), held), consultation, why);
            }
        }

        final List<ComboLines.Line> lab = lines(LAB_CONSULTATION);
        for (final int lands : List.of(3, 4)) {
            final String why = "Laboratory Maniac, Thalia, " + lands + " lands";
            final Card consultation = labDangerBoard();
            if (lands == 4) {
                addCard("Island", ai);
            }
            addCard("Thalia, Guardian of Thraben", opp);
            game.getAction().checkStateEffects(true);
            assertEquals(ai.getLandsInPlay().size(), lands, why);
            assertEquals(ComboSignals.abandon(ai, t.deckLines().get(0), t), ComboSignals.Signal.DANGER, why);
            assertFalse(ComboSignals.surerKillNow(ai), why);
            assertEquals(t.oneShort(), List.of(), why);
            final SpellAbility tutor = spell("Demonic Tutor");
            if (lands == 4) {
                t.useLines(List.of());
                assertNotSame(pick(ZoneType.Hand, tutor), consultation, why);
                t.useLines(lab);
                assertSame(pick(ZoneType.Hand, tutor), consultation, why);
            } else {
                assertNotSame(assertWarmStockParity(() -> pick(ZoneType.Hand, tutor), lab), consultation, why);
            }
        }
    }

    /** Follow-up 3, H1 (Review Focus 1): danger's win in time reads the follow-up draws as the Laboratory Maniac window
     *  does (ComboExecute.followUpDraws), so Meeting of Minds, whose price asks the convoke chooser, is none, and
     *  pricing draws nothing. Line 742-2836 in our Main 2: Laboratory Maniac on our battlefield, Meeting of Minds in
     *  hand, the ten-name library (Consultation on top), our life 3 against the opponent's Colossal Dreadmaw (DANGER),
     *  Island, Island and Swamp and one Island more (one short of Consultation's {B} and Meeting of Minds' {3}{U}
     *  together, so only the draws could move), or two (the mana for both). There the engine's price of Meeting of
     *  Minds draws: in our turn after combat began, the convoke chooser holds likely blockers back
     *  (ComputerUtilCard.getLikelyBlockers, which reaches MyRandom). Demonic Tutor takes the stock pick with the stock
     *  draws. */
    @Test
    public void aConvokeDrawIsNoWinInTimeUnderDanger() {
        for (final int more : List.of(1, 2)) {
            final String why = (3 + more) + " lands";
            start(LAB_CONSULTATION);
            addCard(LAB, ai);
            final Card meeting = addCardToZone(MEETING, ai, ZoneType.Hand);
            for (final String name : LIBRARY) {
                addCardToZone(name, ai, ZoneType.Library);
            }
            for (int i = 0; i < more; i++) {
                addCard("Island", ai);
            }
            ai.setLife(3, null);
            addCard("Colossal Dreadmaw", opp).setSickness(false);
            moveToMain2(game, ai);
            assertEquals(ComboSignals.abandon(ai, t.deckLines().get(0), t), ComboSignals.Signal.DANGER, why);
            assertEquals(t.oneShort(), List.of(), why);

            final SpellAbility price = meeting.getFirstSpellAbility();
            price.setActivatingPlayer(ai);
            MyRandom.setRandom(new Random(7));
            final long before = MyRandom.callCount();
            ComputerUtilMana.calculateManaCost(price.getPayCosts(), price, ai, true, 0, false);
            assertTrue(MyRandom.callCount() > before, why + ": pricing Meeting of Minds draws nothing on this board");

            final Card consultation = t.find(CONSULTATION, ZoneType.Library);
            final SpellAbility tutor = spell("Demonic Tutor");
            assertNotSame(assertWarmStockParity(() -> pick(ZoneType.Hand, tutor), lines(LAB_CONSULTATION)),
                    consultation, why);
        }
    }

    /** The same next turn (follow-up 2, G4: at the end step of the opponent whose turn comes right before ours, the
     *  piece drawn in our draw step, next turn's untapped mana). Under an opposing Thalia, Guardian of Thraben, on
     *  Island, Island and Swamp, next turn's mana pays Oracle's {U}{U} and Consultation's printed {B} but not its {1}{B}
     *  beside them: Vampiric Tutor takes the stock pick, with the stock draws and no known top, and the Vault keeps the
     *  stock holds and stack ({@link #assertVault}). On the danger board's four lands the tutor takes Consultation, a
     *  known top, and the Vault stacks it. Under an opposing Sphere of Resistance, four lands the stock rule, five
     *  Consultation. */
    @Test
    public void dangerReadsEngineCostsForAWinNextTurn() {
        final List<ComboLines.Line> held = lines(ORACLE_CONSULTATION);
        final Object[][] boards = {{"Thalia, Guardian of Thraben", 3, false}, {"Thalia, Guardian of Thraben", 4, true},
                {"Sphere of Resistance", 4, false}, {"Sphere of Resistance", 5, true}};
        for (final Object[] board : boards) {
            final String tax = (String) board[0];
            final int lands = (Integer) board[1];
            final boolean inTime = (Boolean) board[2];
            final String why = tax + ", " + lands + " lands";
            final Card consultation = taxedDangerBoardAt(PhaseType.END_OF_TURN, false, tax, lands);
            assertSame(game.getPhaseHandler().getNextTurn(), ai, why);
            if (inTime) {
                assertEndStepFetch(consultation, held, why);
            } else {
                final SpellAbility vampiric = spell("Vampiric Tutor");
                assertNotSame(assertWarmStockParity(() -> pick(ZoneType.Library, vampiric), held), consultation, why);
                assertEquals(t.knownTops(), Set.of(), why);
            }
            taxedDangerBoardAt(PhaseType.END_OF_TURN, false, tax, lands);
            assertVault(held, inTime, why);
        }
    }

    @Test
    public void aSearchToTheTopMakesAKnownTop() {
        // spec section 6.3, known tops (Evan, 2026-10-24): a piece of ours that a combo hook puts on top of our
        // library is remembered by the tracker; a search to the hand puts nothing there
        final Card consultation = oracleBoard();
        assertEquals(t.knownTops(), Set.of());
        assertSame(pick(ZoneType.Hand, spell("Demonic Tutor")), consultation);
        assertEquals(t.knownTops(), Set.of());

        final SpellAbility vampiric = spell("Vampiric Tutor");
        assertSame(pick(ZoneType.Library, vampiric), consultation);
        assertEquals(t.knownTops(), Set.of(consultation.getId()));
        // Vampiric Tutor resolving: our search, the shuffle, then Consultation on top, the same card, still known
        AbilityUtils.resolve(vampiric);
        assertSame(ai.getZone(ZoneType.Library).get(0), consultation);
        assertEquals(t.knownTops(), Set.of(consultation.getId()));

        // with no line the stock pick is no combo hook's: nothing is remembered
        oracleBoard();
        t.useLines(List.of());
        assertNotNull(pick(ZoneType.Library, spell("Vampiric Tutor")));
        assertEquals(t.knownTops(), Set.of());
    }

    @Test
    public void aScrySurveilOrDigToTheTopMakesAKnownTop() {
        Card consultation = oracleBoard();
        Card island = addCardToZone("Island", ai, ZoneType.Library);
        assertSame(pc().arrangeForScry(new CardCollection(List.of(island, consultation))).getLeft().get(0),
                consultation);
        assertEquals(t.knownTops(), Set.of(consultation.getId()));

        consultation = oracleBoard();
        island = addCardToZone("Island", ai, ZoneType.Library);
        assertSame(pc().arrangeForSurveil(new CardCollection(List.of(island, consultation))).getLeft().get(0),
                consultation);
        assertEquals(t.knownTops(), Set.of(consultation.getId()));

        // Impulse puts its card in our hand; Thassa's Oracle's own look puts one on top (LibraryPosition$ 0)
        consultation = oracleBoard();
        final List<Card> shown = List.of(t.find(WURM, ZoneType.Library), consultation);
        assertSame(ComboFetch.digPick(ai, spell("Impulse"), shown, ai), consultation);
        assertEquals(t.knownTops(), Set.of());
        final SpellAbility look = addCardToZone(ORACLE, ai, ZoneType.Hand).getTriggers().get(0).ensureAbility();
        look.setActivatingPlayer(ai);
        assertEquals(look.getParam("LibraryPosition"), "0");
        assertSame(ComboFetch.digPick(ai, look, shown, ai), consultation);
        assertEquals(t.knownTops(), Set.of(consultation.getId()));

        // with no line nothing is kept on top, and nothing remembered
        consultation = oracleBoard();
        t.useLines(List.of());
        island = addCardToZone("Island", ai, ZoneType.Library);
        pc().arrangeForScry(new CardCollection(List.of(island, consultation)));
        assertEquals(t.knownTops(), Set.of());
    }

    @Test
    public void anOrderingToTheTopMakesAKnownTopAndOneToTheBottomNone() {
        // the Vault's stack (its last move, LibraryPosition$ 0): moved last, Consultation ends on top
        Card consultation = oracleBoard();
        Card wurm = t.find(WURM, ZoneType.Library);
        Card island = addCardToZone("Island", ai, ZoneType.Library);
        final List<Card> top = list(pc().orderMoveToZoneList(new CardCollection(List.of(wurm, consultation, island)),
                ZoneType.Library, vaultReorder()));
        assertSame(top.get(2), consultation);
        assertEquals(t.knownTops(), Set.of(consultation.getId()));

        // the Vault's GoToBottom (LibraryPosition$ -1): the piece still comes first, but the five go to the bottom
        consultation = oracleBoard();
        wurm = t.find(WURM, ZoneType.Library);
        island = addCardToZone("Island", ai, ZoneType.Library);
        final SpellAbility bottom = vaultGoToBottom();
        final List<Card> order = list(pc().orderMoveToZoneList(new CardCollection(List.of(wurm, consultation, island)),
                ZoneType.Library, bottom));
        assertSame(order.get(0), consultation);
        assertEquals(t.knownTops(), Set.of());
    }

    // Lim-Dul's Vault's dig past the five: the five put on the bottom of our library
    private SpellAbility vaultGoToBottom() {
        final Card vault = addCardToZone(SpecialCardAi.LimDulsVault.NAME, ai, ZoneType.Hand);
        final SpellAbility spell = vault.getFirstSpellAbility();
        spell.setActivatingPlayer(ai);
        final SpellAbility bottom = spell.getSubAbility().getAdditionalAbility("RepeatSubAbility").getSubAbility()
                .getSubAbility();
        assertEquals(bottom.getApi(), ApiType.Dig);
        assertEquals(bottom.getParam("LibraryPosition"), "-1");
        return bottom;
    }

    // Lim-Dul's Vault's last move: the remembered five put back on top, in the order the AI gives
    private SpellAbility vaultReorder() {
        final Card vault = addCardToZone(SpecialCardAi.LimDulsVault.NAME, ai, ZoneType.Hand);
        final SpellAbility spell = vault.getFirstSpellAbility();
        spell.setActivatingPlayer(ai);
        final SpellAbility reorder = spell.getSubAbility().getSubAbility();
        assertTrue(reorder.hasParam("Reorder"));
        assertEquals(reorder.getParam("LibraryPosition"), "0");
        return reorder;
    }

    /** The Vault's look: line 742-1295, Oracle in hand, {@code library} top first (one dig needs 13 cards), its top
     *  five remembered by a Vault cast from our hand; returns the repeated "pay 1 life" StoreSVar willDig is asked
     *  for. */
    private SpellAbility vaultLook(final List<String> library) {
        start(ORACLE_CONSULTATION);
        addCardToZone(ORACLE, ai, ZoneType.Hand);
        for (final String name : library) {
            addCardToZone(name, ai, ZoneType.Library);
        }
        final Card vault = addCardToZone(SpecialCardAi.LimDulsVault.NAME, ai, ZoneType.Hand);
        vault.setCastFrom(ai.getZone(ZoneType.Hand));
        final SpellAbility spell = vault.getFirstSpellAbility();
        spell.setActivatingPlayer(ai);
        final List<Card> lib = list(ai.getCardsIn(ZoneType.Library));
        for (int i = 0; i < 5; i++) {
            vault.addRemembered(lib.get(i));
        }
        final SpellAbility look = spell.getSubAbility().getAdditionalAbility("RepeatSubAbility");
        assertEquals(look.getParam("UnlessAI"), SpecialCardAi.LimDulsVault.UNLESS_AI);
        assertSame(look.getRootAbility(), spell);
        return look;
    }

    private boolean willDig(final SpellAbility look) {
        return SpecialCardAi.LimDulsVault.willDig(ai, look, new Cost("PayLife<1>", false));
    }

    /** A Vault library of {@code size} cards, top first, Demonic Consultation at {@code pieceAt}: the top five hold
     *  two Islands while the piece is below them, which the land-light stock rule keeps (its answer: no dig). */
    private static List<String> vaultLibrary(final int size, final int pieceAt) {
        final List<String> filler = List.of("Island", "Grizzly Bears", "Island", "Hill Giant", "Serra Angel",
                "Shivan Dragon", WURM, "Runeclaw Bear", "Llanowar Elves", "Counterspell", "Divination", "Opt",
                "Cancel", "Mulldrifter", "Air Elemental", "Forest", "Forest", "Forest", "Forest", "Forest",
                "Mountain", "Mountain", "Mountain", "Mountain", "Mountain");
        final List<String> lib = new ArrayList<>(filler.subList(0, size - 1));
        lib.add(pieceAt, CONSULTATION);
        return lib;
    }

    /** {@code top}, top first, over Mountains to 40 cards: long enough that the library margin never stops a dig
     *  (LOOK x (digs + 2) + 3 is 28 once three fives are bottomed), so that only MAX_DIGS does. */
    private static List<String> longLibrary(final List<String> top) {
        final List<String> lib = new ArrayList<>(top);
        while (lib.size() < 40) {
            lib.add("Mountain");
        }
        return lib;
    }

    /** Records our library's bottom {@code fives} fives as the ones this resolution bottomed (LIM_DULS_VAULT_DUG), so
     *  that the Vault's digsSoFar reads {@code fives}. */
    private void rememberBottomed(final int fives) {
        final List<Card> lib = list(ai.getCardsIn(ZoneType.Library));
        for (int i = lib.size() - 5 * fives; i < lib.size(); i++) {
            AiCardMemory.rememberCard(ai, lib.get(i), AiCardMemory.MemorySet.LIM_DULS_VAULT_DUG);
        }
    }

    // ---- follow-up 3, H3: the follow-up draw's own cast bar, and its window under danger ----

    /** The Laboratory Maniac danger board ({@link #labDangerBoard}) at the end step of the opponent whose turn comes
     *  right before ours, under the opponent's {@code hoser} (none for null; a planeswalker with four loyalty): DANGER
     *  holds, with no surer kill and no line one short. Returns the library's Consultation, the top card. */
    private Card labDangerAtTheEndStepBeforeOurTurn(final String hoser) {
        final Card consultation = labDangerBoard();
        game.getPhaseHandler().devModeSet(PhaseType.END_OF_TURN, opp);
        assertSame(game.getPhaseHandler().getNextTurn(), ai);
        if (hoser != null) {
            opposing(hoser);
        }
        assertEquals(game.getPhaseHandler().getPhase(), PhaseType.END_OF_TURN, String.valueOf(hoser));
        assertTrue(t.view().danger(), String.valueOf(hoser));
        assertFalse(ComboSignals.surerKillNow(ai), String.valueOf(hoser));
        assertEquals(t.oneShort(), List.of(), String.valueOf(hoser));
        assertSame(ai.getZone(ZoneType.Library).get(0), consultation, String.valueOf(hoser));
        return consultation;
    }

    // the opponent's hoser put on their battlefield (a planeswalker with four loyalty), the state checked
    private void opposing(final String hoser) {
        final Card c = addCard(hoser, opp);
        if (c.isPlaneswalker()) {
            c.setCounters(CounterEnumType.LOYALTY, 4);
        }
        game.getAction().checkStateEffects(true);
        assertTrue(c.isInZone(ZoneType.Battlefield), hoser);
    }

    /** Review Focus 3, under danger: the draw spell after the emptier must itself be castable in the main phase the
     *  line wins in. On the Laboratory Maniac danger board under the opponent's Rule of Law ("each player can't cast
     *  more than one spell each turn") Opt would be that main phase's second spell, after Consultation, so no win in
     *  time comes of the fetch:
     *  <ul>
     *  <li>Vampiric Tutor at the end step before our turn (our next turn's Main 1, Consultation its only earlier
     *  spell): the line's own window, that end step, is open (DANGER its only signal), yet the tutor takes the stock
     *  pick with the stock draws and leaves no known top, and the Vault keeps the stock holds and stack;
     *  <li>Demonic Tutor in our Main 1, no spell cast yet (Consultation alone could be cast now): the stock pick.
     *  </ul>
     *  With no hoser each takes Consultation. */
    @Test
    public void labDangerUnderRuleOfLawKeepsTheStockPick() {
        final List<ComboLines.Line> lab = lines(LAB_CONSULTATION);
        assertEndStepFetch(labDangerAtTheEndStepBeforeOurTurn(null), lab, "no hoser");
        Card consultation = labDangerAtTheEndStepBeforeOurTurn("Rule of Law");
        assertEquals(ComboSignals.abandon(ai, t.deckLines().get(0), t), ComboSignals.Signal.DANGER);
        assertFalse(ComboSignals.deniedAtMainNextTurn(t.view(), t.deckLines().get(0)), "Consultation is castable");
        final SpellAbility vampiric = spell("Vampiric Tutor");
        assertNotSame(assertWarmStockParity(() -> pick(ZoneType.Library, vampiric), lab), consultation);
        assertEquals(t.knownTops(), Set.of());
        labDangerAtTheEndStepBeforeOurTurn("Rule of Law");
        assertVault(lab, false, "Rule of Law");

        // this turn's Main 1
        consultation = labDangerBoard();
        assertSame(pick(ZoneType.Hand, spell("Demonic Tutor")), consultation, "no hoser, our Main 1");
        consultation = labDangerBoard();
        addCard("Rule of Law", opp);
        final ComboLines.Line l = t.deckLines().get(0);
        assertEquals(game.getStack().getSpellCardsCastThisTurn(), List.of());
        assertFalse(ComboSignals.deniedNow(t.view(), l), "Consultation alone can be cast now");
        assertEquals(ComboSignals.abandon(ai, l, t), ComboSignals.Signal.DANGER);
        final SpellAbility tutor = spell("Demonic Tutor");
        assertNotSame(assertWarmStockParity(() -> pick(ZoneType.Hand, tutor), lab), consultation);
    }

    /** The other side (follow-up 3, H3): a Laboratory Maniac line's win next turn is Consultation and then Opt in our
     *  Main 1, so its denial there is read at that main phase, not at the end step before our turn, the line's own
     *  window. Under the opponent's Grand Abolisher ("during your turn, your opponents can't cast spells") or Teferi,
     *  Time Raveler ("each opponent can cast spells only any time they could cast a sorcery") that end step is barred,
     *  so the line is DENIED; but in our Main 1 neither bars Consultation nor Opt. On the Laboratory Maniac danger
     *  board at that end step, Vampiric Tutor puts Consultation on top, a known top ({@link #assertEndStepFetch}), and
     *  the Vault keeps a five holding it, digs past one missing it and stacks it on top ({@link #assertVault}). With
     *  Laboratory Maniac in our hand instead, and Night's Whisper the draw spell
     *  ({@link #labInHandAtTheEndStepBeforeOurTurn}), neither bars Lab, Consultation nor Night's Whisper in our Main 1
     *  either, and Vampiric Tutor puts Consultation on top. */
    @Test
    public void labDangerUnderAbolisherFetches() {
        final List<ComboLines.Line> lab = lines(LAB_CONSULTATION);
        for (final String hoser : List.of("Grand Abolisher", "Teferi, Time Raveler")) {
            final Card consultation = labDangerAtTheEndStepBeforeOurTurn(hoser);
            final ComboLines.Line l = t.deckLines().get(0);
            assertEquals(ComboSignals.abandon(ai, l, t), ComboSignals.Signal.DENIED, hoser);
            assertFalse(ComboSignals.deniedAtMainNextTurn(t.view(), l), hoser);
            assertEquals(ComboSignals.abandonAtMainNextTurn(t.view(), l), ComboSignals.Signal.DANGER, hoser);
            assertEndStepFetch(consultation, lab, hoser);
            labDangerAtTheEndStepBeforeOurTurn(hoser);
            assertVault(lab, true, hoser);

            final String why = hoser + ", Lab in hand";
            final Card inHand = labInHandAtTheEndStepBeforeOurTurn();
            opposing(hoser);
            final ComboLines.Line cast = t.deckLines().get(0);
            assertEquals(ComboSignals.abandon(ai, cast, t), ComboSignals.Signal.DENIED, why);
            assertEquals(ComboSignals.abandonAtMainNextTurn(t.view(), cast), ComboSignals.Signal.DANGER, why);
            assertEndStepFetch(inHand, lab, why);
        }
    }

    /** Beyond the plan's tests (follow-up 3, H3): only a Laboratory Maniac or Jace line's win next turn is read at the
     *  main-phase window. A Thassa's Oracle line's is its go-off, the emptier in response to Oracle's trigger, so under
     *  the opponent's Teferi, Time Raveler it is DENIED there, as at the line's own window: on the danger board
     *  ({@link #dangerBoardAt}) at the end step before our turn Vampiric Tutor takes the stock pick with the stock
     *  draws and leaves no known top, though in our Main 1, a sorcery's moment, Oracle and then Consultation could
     *  each be cast. */
    @Test
    public void anOracleLineNextTurnIsStillReadAtItsTrigger() {
        final List<ComboLines.Line> held = lines(ORACLE_CONSULTATION);
        final Card consultation = dangerBoardAt(PhaseType.END_OF_TURN, false);
        final Card teferi = addCard("Teferi, Time Raveler", opp);
        teferi.setCounters(CounterEnumType.LOYALTY, 4);
        game.getAction().checkStateEffects(true);
        final ComboLines.Line l = t.deckLines().get(0);
        assertTrue(teferi.isInZone(ZoneType.Battlefield));
        assertEquals(ComboSignals.abandon(ai, l, t), ComboSignals.Signal.DENIED);
        assertEquals(ComboSignals.abandonAtMainNextTurn(t.view(), l), ComboSignals.Signal.DANGER,
                "in our Main 1 both could be cast");
        final SpellAbility vampiric = spell("Vampiric Tutor");
        assertNotSame(assertWarmStockParity(() -> pick(ZoneType.Library, vampiric), held), consultation);
        assertEquals(t.knownTops(), Set.of());
    }

    /** Beyond the plan's tests (follow-up 3, H3): the main-phase window next turn still reads every denial the line's
     *  own window reads, each piece's bar at its moment there among them. At the end step before our turn, DANGER
     *  holding, the line is DENIED at that main-phase window as at the end step, and Vampiric Tutor takes the stock
     *  pick with the stock draws and leaves no known top ({@link #assertDeniedAtMainNextTurn}), on the Laboratory
     *  Maniac danger board under:
     *  <ul>
     *  <li>the opponent's Iona, Shield of Emeria naming black ("your opponents can't cast spells of the chosen color"):
     *  Consultation can be cast at no moment, though Opt can;
     *  <li>Iona naming blue, with Laboratory Maniac in our hand and Night's Whisper the draw spell, Island, Island,
     *  Island, Island, Swamp and Swamp the mana for all three: Lab can be cast at no moment, though Consultation and
     *  Night's Whisper can (with no Iona the tutor takes Consultation);
     *  <li>the opponent's Lich's Mastery ("you can't lose the game"): every opponent cannot lose, though we can win;
     *  <li>three seats, a Platinum Angel of the opponent after us: we cannot win, though the opponent before us, whose
     *  Dreadmaw is the danger, can lose (with no Angel the tutor takes Consultation);
     *  <li>Lignify on our Laboratory Maniac, at 2 life (the 0/4 Treefolk blocks, and the Dreadmaw's trample puts 2
     *  through): Lab has lost its win.
     *  </ul> */
    @Test
    public void theMainPhaseWindowNextTurnStillReadsEachDenial() {
        final List<ComboLines.Line> lab = lines(LAB_CONSULTATION);
        Card consultation = labDangerAtTheEndStepBeforeOurTurn(null);
        final Card iona = addCard("Iona, Shield of Emeria", opp);
        iona.setChosenColors(new ArrayList<>(List.of("black")));
        game.getAction().checkStateEffects(true);
        assertDeniedAtMainNextTurn(consultation, lab, "Iona naming black");

        assertEndStepFetch(labInHandAtTheEndStepBeforeOurTurn(), lab, "Lab in hand, no Iona");
        consultation = labInHandAtTheEndStepBeforeOurTurn();
        final Card blue = addCard("Iona, Shield of Emeria", opp);
        blue.setChosenColors(new ArrayList<>(List.of("blue")));
        game.getAction().checkStateEffects(true);
        assertDeniedAtMainNextTurn(consultation, lab, "Iona naming blue");

        consultation = labDangerAtTheEndStepBeforeOurTurn("Lich's Mastery");
        assertFalse(ai.cantWin());
        assertTrue(opp.cantLose());
        assertDeniedAtMainNextTurn(consultation, lab, "Lich's Mastery");

        assertEndStepFetch(threeSeatLabDangerAtTheEndStepBeforeOurTurn(), lab, "three seats, no Angel");
        consultation = threeSeatLabDangerAtTheEndStepBeforeOurTurn();
        addCard("Platinum Angel", game.getPlayers().get(2));
        assertTrue(ai.cantWin());
        assertFalse(opp.cantLose());
        assertDeniedAtMainNextTurn(consultation, lab, "Platinum Angel");

        consultation = labDangerAtTheEndStepBeforeOurTurn(null);
        final Card maniac = t.find(LAB, ZoneType.Battlefield);
        addCard("Lignify", opp).attachToEntity(maniac, null);
        game.getAction().checkStateEffects(true);
        ai.setLife(2, null);
        assertFalse(ComboSignals.winsOnDraw(maniac));
        assertDeniedAtMainNextTurn(consultation, lab, "Lignify");
    }

    // the line DENIED at the end step before our turn and at the main-phase window next turn, DANGER holding, and
    // Vampiric Tutor the stock pick, with the stock draws, leaving no known top
    private void assertDeniedAtMainNextTurn(final Card consultation, final List<ComboLines.Line> lab,
            final String why) {
        final ComboLines.Line l = t.deckLines().get(0);
        assertEquals(game.getPhaseHandler().getPhase(), PhaseType.END_OF_TURN, why);
        assertEquals(ComboSignals.abandon(ai, l, t), ComboSignals.Signal.DENIED, why);
        assertTrue(ComboSignals.deniedAtMainNextTurn(t.view(), l), why);
        assertEquals(ComboSignals.abandonAtMainNextTurn(t.view(), l), ComboSignals.Signal.DENIED, why);
        assertTrue(t.view().danger(), why);
        assertSame(ai.getZone(ZoneType.Library).get(0), consultation, why);
        final SpellAbility vampiric = spell("Vampiric Tutor");
        assertNotSame(assertWarmStockParity(() -> pick(ZoneType.Library, vampiric), lab), consultation, why);
        assertEquals(t.knownTops(), Set.of(), why);
    }

    /** Line 742-2836 with Laboratory Maniac and Night's Whisper in our hand, the ten-name library, Island, Island,
     *  Island, Island, Swamp and Swamp, at 3 life against an opposing Colossal Dreadmaw, at the end step of the
     *  opponent, our turn next: DANGER is the line's only signal (Lab's {2}{U} and Consultation's {B} within next
     *  turn's six), and Consultation, the top card, its one piece missing. Returns Consultation. */
    private Card labInHandAtTheEndStepBeforeOurTurn() {
        start(LAB_CONSULTATION);
        addCardToZone(LAB, ai, ZoneType.Hand);
        addCardToZone("Night's Whisper", ai, ZoneType.Hand);
        for (final String name : LIBRARY) {
            addCardToZone(name, ai, ZoneType.Library);
        }
        for (final String land : List.of("Island", "Island", "Swamp")) {
            addCard(land, ai);
        }
        ai.setLife(3, null);
        addCard("Colossal Dreadmaw", opp).setSickness(false);
        game.getPhaseHandler().devModeSet(PhaseType.END_OF_TURN, opp);
        assertSame(game.getPhaseHandler().getNextTurn(), ai);
        assertEquals(t.where(LAB), ComboTracker.Where.HAND);
        assertEquals(t.missing(t.deckLines().get(0)), List.of(CONSULTATION));
        assertEquals(ComboSignals.abandon(ai, t.deckLines().get(0), t), ComboSignals.Signal.DANGER);
        assertEquals(t.oneShort(), List.of());
        final Card consultation = t.find(CONSULTATION, ZoneType.Library);
        assertSame(ai.getZone(ZoneType.Library).get(0), consultation);
        return consultation;
    }

    /** The Laboratory Maniac danger board in three seats: Laboratory Maniac on our battlefield, Opt in hand, the
     *  ten-name library, Island, Island and Swamp, at 3 life against a Colossal Dreadmaw of the opponent whose turn
     *  comes right before ours, at that opponent's end step. Returns the library's Consultation, the top card. */
    private Card threeSeatLabDangerAtTheEndStepBeforeOurTurn() {
        game = initAndCreateThreePlayerGame();
        ai = game.getPlayers().get(1);
        opp = game.getPlayers().get(0);
        ((LobbyPlayerAi) ai.getLobbyPlayer()).setAiProfile("Default");
        t = aiOf(ai).getComboTracker();
        t.useLines(lines(LAB_CONSULTATION));
        addCard(LAB, ai);
        addCardToZone(OPT, ai, ZoneType.Hand);
        for (final String name : LIBRARY) {
            addCardToZone(name, ai, ZoneType.Library);
        }
        for (final String land : List.of("Island", "Island", "Swamp")) {
            addCard(land, ai);
        }
        ai.setLife(3, null);
        addCard("Colossal Dreadmaw", opp).setSickness(false);
        game.getPhaseHandler().devModeSet(PhaseType.END_OF_TURN, opp);
        assertSame(game.getPhaseHandler().getNextTurn(), ai);
        assertEquals(ComboSignals.abandon(ai, t.deckLines().get(0), t), ComboSignals.Signal.DANGER);
        assertEquals(t.oneShort(), List.of());
        final Card consultation = t.find(CONSULTATION, ZoneType.Library);
        assertSame(ai.getZone(ZoneType.Library).get(0), consultation);
        return consultation;
    }

    /** Beyond the plan's tests (follow-up 3, H3): a cap counts every spell cast before the draw in that main phase,
     *  this turn's and the winner still to be cast among them. Our own Fires of Invention ("you can cast no more than
     *  two spells each turn"), at 3 life against the opponent's Colossal Dreadmaw in our Main 1. With Laboratory
     *  Maniac on our battlefield ({@link #labDangerBoard}) and no spell cast yet, Consultation and then Opt are the
     *  turn's two spells, and Demonic Tutor takes Consultation; with an Opt cast this turn already, Consultation could
     *  still be cast now, as the second, but the Opt in hand would be the third: the stock pick, with the stock draws.
     *  With Laboratory Maniac in our hand instead, no spell cast yet, and Island, Island, Island, Island and Swamp to
     *  cast all three, Opt would be the turn's third, after Lab and Consultation: the stock pick; without Fires the
     *  tutor takes Consultation. */
    @Test
    public void aCapCountsTheWinnerCastBeforeTheDraw() {
        final List<ComboLines.Line> lab = lines(LAB_CONSULTATION);
        Card consultation = labDangerBoard();
        addCard("Fires of Invention", ai);
        assertEquals(ComboSignals.abandon(ai, t.deckLines().get(0), t), ComboSignals.Signal.DANGER);
        assertSame(pick(ZoneType.Hand, spell("Demonic Tutor")), consultation, "Lab on our battlefield");

        consultation = labDangerBoard();
        addCard("Fires of Invention", ai);
        castThisTurn(OPT);
        assertEquals(game.getStack().getSpellCardsCastThisTurn().size(), 1);
        assertNotNull(t.find(OPT, ZoneType.Hand));
        final ComboLines.Line cast = t.deckLines().get(0);
        assertFalse(ComboSignals.deniedNow(t.view(), cast), "Consultation would be this turn's second");
        assertFalse(t.view().deniedThisTurn(cast));
        assertEquals(ComboSignals.abandon(ai, cast, t), ComboSignals.Signal.DANGER);
        final SpellAbility third = spell("Demonic Tutor");
        assertNotSame(assertWarmStockParity(() -> pick(ZoneType.Hand, third), lab), consultation, "an Opt cast");

        for (final boolean fires : List.of(false, true)) {
            final String why = fires ? "Fires of Invention" : "no cap";
            start(LAB_CONSULTATION);
            addCardToZone(LAB, ai, ZoneType.Hand);
            addCardToZone(OPT, ai, ZoneType.Hand);
            for (final String name : LIBRARY) {
                addCardToZone(name, ai, ZoneType.Library);
            }
            addCard("Island", ai);
            addCard("Island", ai);
            ai.setLife(3, null);
            addCard("Colossal Dreadmaw", opp).setSickness(false);
            if (fires) {
                addCard("Fires of Invention", ai);
            }
            final ComboLines.Line l = t.deckLines().get(0);
            assertEquals(t.where(LAB), ComboTracker.Where.HAND, why);
            assertEquals(t.missing(l), List.of(CONSULTATION), why);
            assertEquals(ComboSignals.abandon(ai, l, t), ComboSignals.Signal.DANGER, why);
            assertFalse(t.view().deniedThisTurn(l), why);
            assertEquals(t.oneShort(), List.of(), why);
            consultation = t.find(CONSULTATION, ZoneType.Library);
            final SpellAbility tutor = spell("Demonic Tutor");
            if (fires) {
                assertNotSame(assertWarmStockParity(() -> pick(ZoneType.Hand, tutor), lab), consultation, why);
            } else {
                assertSame(pick(ZoneType.Hand, tutor), consultation, why);
            }
        }
    }

    // ---- follow-up 3, H3 review, round 1 ----

    /** A barred draw spell is passed over, not the end of danger's search for one. On the Laboratory Maniac danger
     *  board with a second Swamp, under the opponent's Iona, Shield of Emeria naming blue ("your opponents can't cast
     *  spells of the chosen color", {@link #ionaNamingBlueOnTheLabDangerBoard}): Opt, the board's draw spell, can be
     *  cast at no moment, so no win in time comes of a fetch, and Demonic Tutor in our Main 1 and Vampiric Tutor at the
     *  end step before our turn each take the stock pick with the stock draws, leaving no known top. Night's Whisper
     *  put in our hand after Opt, the next draw spell in hand order, can follow Consultation, its {1}{B} and
     *  Consultation's {B} within the four lands: Demonic Tutor takes Consultation, and Vampiric Tutor puts it on top
     *  ({@link #assertEndStepFetch}). */
    @Test
    public void aBarredDrawSpellPassesDangersWinInTimeToTheNext() {
        final List<ComboLines.Line> lab = lines(LAB_CONSULTATION);
        for (final boolean whisper : List.of(false, true)) {
            final String why = whisper ? "Night's Whisper after Opt" : "Opt alone";
            Card consultation = ionaNamingBlueOnTheLabDangerBoard(whisper);
            final SpellAbility tutor = spell("Demonic Tutor");
            if (whisper) {
                assertSame(pick(ZoneType.Hand, tutor), consultation, why + ", our Main 1");
            } else {
                assertNotSame(assertWarmStockParity(() -> pick(ZoneType.Hand, tutor), lab), consultation,
                        why + ", our Main 1");
            }

            consultation = ionaNamingBlueOnTheLabDangerBoard(whisper);
            game.getPhaseHandler().devModeSet(PhaseType.END_OF_TURN, opp);
            assertSame(game.getPhaseHandler().getNextTurn(), ai, why);
            final ComboLines.Line l = t.deckLines().get(0);
            assertFalse(ComboSignals.deniedAtMainNextTurn(t.view(), l), why);
            assertEquals(ComboSignals.abandonAtMainNextTurn(t.view(), l), ComboSignals.Signal.DANGER, why);
            if (whisper) {
                assertEndStepFetch(consultation, lab, why + ", the end step");
            } else {
                final SpellAbility vampiric = spell("Vampiric Tutor");
                assertNotSame(assertWarmStockParity(() -> pick(ZoneType.Library, vampiric), lab), consultation,
                        why + ", the end step");
                assertEquals(t.knownTops(), Set.of(), why + ", the end step");
            }
        }
    }

    /** The Laboratory Maniac danger board ({@link #labDangerBoard}) in our Main 1 with a second Swamp, Night's Whisper
     *  put in our hand after Opt when {@code whisper}, and the opponent's Iona, Shield of Emeria naming blue: DANGER
     *  holds, Consultation can be cast now, and Opt after it cannot, in our Main 1 or at any other moment. Returns the
     *  library's Consultation. */
    private Card ionaNamingBlueOnTheLabDangerBoard(final boolean whisper) {
        final Card consultation = labDangerBoard();
        addCard("Swamp", ai);
        if (whisper) {
            addCardToZone("Night's Whisper", ai, ZoneType.Hand);
        }
        final Card iona = addCard("Iona, Shield of Emeria", opp);
        iona.setChosenColors(new ArrayList<>(List.of("blue")));
        game.getAction().checkStateEffects(true);
        final ComboLines.Line l = t.deckLines().get(0);
        assertEquals(ComboSignals.abandon(ai, l, t), ComboSignals.Signal.DANGER);
        assertFalse(ComboSignals.deniedNow(t.view(), l), "Consultation is castable");
        final Card opt = t.find(OPT, ZoneType.Hand);
        final SpellAbility optSpell = opt.getFirstSpellAbility();
        optSpell.setActivatingPlayer(ai);
        assertTrue(ComboSignals.drawBarredAt(t.view(), optSpell, ai, PhaseType.MAIN1, true, List.of(consultation)),
                "Iona bars Opt");
        if (whisper) {
            final Card nightsWhisper = t.find("Night's Whisper", ZoneType.Hand);
            assertTrue(ai.getCardsIn(ZoneType.Hand).indexOf(opt) < ai.getCardsIn(ZoneType.Hand).indexOf(nightsWhisper),
                    "Opt is read first");
        }
        return consultation;
    }

    /** The main-phase window next turn is read in our Main 1, not in a combat. Under the opponent's Basandra, Battle
     *  Seraph ("players can't cast spells during combat") or Marisi, Breaker of the Coil ("your opponents can't cast
     *  spells during combat") Opt after Consultation cannot be cast in our combat damage step, though in our Main 1 it
     *  can, and neither bars Laboratory Maniac, Consultation or the draw spell there. At the end step before our turn,
     *  DANGER holding, the line is not denied at that window, and Vampiric Tutor puts Consultation on top
     *  ({@link #assertEndStepFetch}), with Laboratory Maniac on our battlefield
     *  ({@link #labDangerAtTheEndStepBeforeOurTurn}) and with Laboratory Maniac in our hand and Night's Whisper the
     *  draw spell ({@link #labInHandAtTheEndStepBeforeOurTurn}). */
    @Test
    public void aCombatCastBarLeavesTheMainPhaseWindowNextTurn() {
        final List<ComboLines.Line> lab = lines(LAB_CONSULTATION);
        for (final String hoser : List.of("Basandra, Battle Seraph", "Marisi, Breaker of the Coil")) {
            final Card consultation = labDangerAtTheEndStepBeforeOurTurn(hoser);
            final ComboLines.Line l = t.deckLines().get(0);
            final SpellAbility opt = t.find(OPT, ZoneType.Hand).getFirstSpellAbility();
            opt.setActivatingPlayer(ai);
            assertTrue(ComboSignals.drawBarredAt(t.view(), opt, ai, PhaseType.COMBAT_DAMAGE, false,
                    List.of(consultation)), hoser + ": in our combat damage step");
            assertFalse(ComboSignals.drawBarredAt(t.view(), opt, ai, PhaseType.MAIN1, true, List.of(consultation)),
                    hoser + ": in our Main 1");
            assertEquals(ComboSignals.abandon(ai, l, t), ComboSignals.Signal.DANGER, hoser);
            assertFalse(ComboSignals.deniedAtMainNextTurn(t.view(), l), hoser);
            assertEquals(ComboSignals.abandonAtMainNextTurn(t.view(), l), ComboSignals.Signal.DANGER, hoser);
            assertEndStepFetch(consultation, lab, hoser);

            final String why = hoser + ", Lab in hand";
            final Card inHand = labInHandAtTheEndStepBeforeOurTurn();
            opposing(hoser);
            final ComboLines.Line cast = t.deckLines().get(0);
            assertFalse(ComboSignals.deniedAtMainNextTurn(t.view(), cast), why);
            assertEquals(ComboSignals.abandonAtMainNextTurn(t.view(), cast), ComboSignals.Signal.DANGER, why);
            assertEndStepFetch(inHand, lab, why);
        }
    }

    /** A Jace, Wielder of Mysteries line's win next turn is read at the main-phase window as a Laboratory Maniac line's
     *  is ({@link #labDangerUnderAbolisherFetches}): Consultation and then Opt in our Main 1. On the Jace danger board
     *  ({@link #jaceDangerAtTheEndStepBeforeOurTurn}) with no hoser, Vampiric Tutor puts Consultation on top
     *  ({@link #assertEndStepFetch}); under the opponent's Grand Abolisher ("during your turn, your opponents can't
     *  cast spells") or Teferi, Time Raveler ("each opponent can cast spells only any time they could cast a sorcery")
     *  the line is DENIED at that end step, its own window, but not in our Main 1, and Vampiric Tutor still puts
     *  Consultation on top. */
    @Test
    public void jaceDangerUnderAbolisherFetches() {
        final List<ComboLines.Line> jace = lines(JACE_CONSULTATION);
        assertEndStepFetch(jaceDangerAtTheEndStepBeforeOurTurn(), jace, "no hoser");
        for (final String hoser : List.of("Grand Abolisher", "Teferi, Time Raveler")) {
            final Card consultation = jaceDangerAtTheEndStepBeforeOurTurn();
            opposing(hoser);
            final ComboLines.Line l = t.deckLines().get(0);
            assertEquals(ComboSignals.abandon(ai, l, t), ComboSignals.Signal.DENIED, hoser);
            assertFalse(ComboSignals.deniedAtMainNextTurn(t.view(), l), hoser);
            assertEquals(ComboSignals.abandonAtMainNextTurn(t.view(), l), ComboSignals.Signal.DANGER, hoser);
            assertEndStepFetch(consultation, jace, hoser);
        }
    }

    /** Line 742-3096 with Jace, Wielder of Mysteries on our battlefield (four loyalty), Opt in hand, the ten-name
     *  library, Island, Island and Swamp, at 3 life against an opposing Colossal Dreadmaw, at the end step of the
     *  opponent, our turn next: DANGER is the line's only signal, with no surer kill and no line one short, and
     *  Consultation, the top card, its one piece missing. Returns Consultation. */
    private Card jaceDangerAtTheEndStepBeforeOurTurn() {
        start(JACE_CONSULTATION);
        addCard(JACE, ai).setCounters(CounterEnumType.LOYALTY, 4);
        addCardToZone(OPT, ai, ZoneType.Hand);
        for (final String name : LIBRARY) {
            addCardToZone(name, ai, ZoneType.Library);
        }
        ai.setLife(3, null);
        addCard("Colossal Dreadmaw", opp).setSickness(false);
        game.getPhaseHandler().devModeSet(PhaseType.END_OF_TURN, opp);
        assertSame(game.getPhaseHandler().getNextTurn(), ai);
        final ComboLines.Line l = t.deckLines().get(0);
        assertEquals(t.where(JACE), ComboTracker.Where.BATTLEFIELD);
        assertEquals(t.missing(l), List.of(CONSULTATION));
        assertEquals(ComboSignals.abandon(ai, l, t), ComboSignals.Signal.DANGER);
        assertTrue(t.view().danger());
        assertFalse(ComboSignals.surerKillNow(ai));
        assertEquals(t.oneShort(), List.of());
        final Card consultation = t.find(CONSULTATION, ZoneType.Library);
        assertSame(ai.getZone(ZoneType.Library).get(0), consultation);
        return consultation;
    }
}
