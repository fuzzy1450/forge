package forge.ai.combo;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNotEquals;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertSame;
import static org.testng.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import org.apache.commons.lang3.tuple.ImmutablePair;
import org.testng.annotations.Test;

import forge.ai.AITest;
import forge.ai.AiCardMemory;
import forge.ai.AiController;
import forge.ai.ComputerUtil;
import forge.ai.LobbyPlayerAi;
import forge.ai.PlayerControllerAi;
import forge.ai.SpecialCardAi;
import forge.ai.ability.ChangeZoneAi;
import forge.ai.ability.DigAi;
import forge.game.Game;
import forge.game.ability.AbilityKey;
import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.cost.Cost;
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
    // 742-1295 at mana value 1: it ties 742-2836 and ranks ahead of it on the lower id
    private static final String CHEAP_ORACLE_CONSULTATION =
            "V|742-1295|1|{U}{U}{B}|2,88|H:Demonic Consultation|H:Thassa's Oracle";

    private static final String ORACLE = "Thassa's Oracle";
    private static final String CONSULTATION = "Demonic Consultation";
    private static final String LAB = "Laboratory Maniac";
    private static final String WURM = "Craw Wurm";

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
        // two lines one piece short: Laboratory Maniac's (mana value 1) ranks ahead of Oracle's (3)
        assertEquals(ids(t.oneShort()), List.of("742-2836", "742-1295"));
        final SpellAbility tutor = spell("Demonic Tutor");
        final SpellAbility impulse = spell("Impulse");

        // both missing pieces offered, Oracle first: the first line's piece is the pick, not the first card
        assertSame(ComboFetch.searchPick(tutor, ZoneType.Hand, new CardCollection(List.of(oracle, lab, wurm)), ai, ai),
                lab);
        assertSame(ComboFetch.digPick(ai, impulse, List.of(oracle, lab, wurm), ai), lab);
        // the first line's piece not offered: the next line is served
        assertSame(ComboFetch.searchPick(tutor, ZoneType.Hand, new CardCollection(List.of(wurm, oracle)), ai, ai),
                oracle);
        assertSame(ComboFetch.digPick(ai, impulse, List.of(wurm, oracle), ai), oracle);
    }

    @Test
    public void aBattlefieldFetchSkipsALineWhosePieceCannotStartThereForTheNextLine() {
        start(CHEAP_ORACLE_CONSULTATION, LAB_CONSULTATION);
        addCard("Swamp", ai);   // Laboratory Maniac (3) and Consultation (1) cost 4
        addCardToZone(CONSULTATION, ai, ZoneType.Hand);
        final Card oracle = addCardToZone(ORACLE, ai, ZoneType.Library);
        final Card lab = addCardToZone(LAB, ai, ZoneType.Library);
        // Oracle's line ranks first (both at 1, the lower id first), and its missing piece starts in hand only (H)
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
}
