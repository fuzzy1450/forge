package forge.ai.combo;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertSame;
import static org.testng.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import org.testng.annotations.Test;

import forge.StaticData;
import forge.ai.AITest;
import forge.ai.AiPlayDecision;
import forge.ai.ComputerUtil;
import forge.ai.ComputerUtilAbility;
import forge.ai.LobbyPlayerAi;
import forge.ai.PlayerControllerAi;
import forge.ai.SpecialCardAi;
import forge.ai.ability.ChangeZoneAi;
import forge.ai.ability.DigAi;
import forge.game.Game;
import forge.game.ability.AbilityUtils;
import forge.game.ability.ApiType;
import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.card.CardLists;
import forge.game.cost.CostExile;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import forge.item.PaperCard;

/**
 * Fetching and holding for piece 2's loop lines (spec 2026-10-29-combo-aware-ai-loops-design.md section 6.7; the
 * plan's Task B3, R1 and R13). The generic hooks see a loop line as they see piece 1's lines: a search or a dig takes
 * the missing piece of a loop one short, a scry keeps a loop's piece on top, a pitch spares it, and its cast is never
 * held. The hooks written for the empty-library family stay with it: Demonic Consultation as a tutor, Intuition's and
 * Gifts Ungiven's piles and danger's win in time read piece 1's lines only, so a loop line neither switches them on nor
 * stops them. A deck holding no line fetches as stock. Unless a test says otherwise the board is Kinnan's list
 * registered (every piece of the 19 loop rows, the two sinks and lands), the tracker reading the shipped lines, Kinnan,
 * Bonder Prodigy ready on our battlefield, the AI on the Default profile in its own Main 1 with no AI deadline, and the
 * opponent with nothing.
 */
public class ComboLoopFetchAiTest extends AITest {
    private static final String KINNAN = "Kinnan, Bonder Prodigy";
    private static final String BASALT = "Basalt Monolith";
    private static final String GRIM = "Grim Monolith";
    private static final String NYXBLOOM = "Nyxbloom Ancient";
    private static final String SAKASHIMA = "Sakashima of a Thousand Faces";
    private static final String HULLBREAKER = "Hullbreaker Horror";
    private static final String SOL_RING = "Sol Ring";
    private static final String MANA_VAULT = "Mana Vault";
    private static final String BALLISTA = "Walking Ballista";
    private static final String THRASIOS = "Thrasios, Triton Hero";
    private static final String ORACLE = "Thassa's Oracle";
    private static final String CONSULTATION = "Demonic Consultation";
    private static final String PACT = "Tainted Pact";
    private static final String INTUITION = SpecialCardAi.IntuitionPile.NAME;
    private static final String GIFTS = SpecialCardAi.GiftsUngiven.NAME;
    private static final String FORCE = "Force of Will";
    private static final String ENLIGHTENED = "Enlightened Tutor";
    private static final String LOTUS = "Gilded Lotus";
    private static final String ULAMOG = "Ulamog, the Ceaseless Hunger";
    private static final String DREADMAW = "Colossal Dreadmaw";
    private static final String BEARS = "Grizzly Bears";

    /** The library Demonic Consultation tutors from: 80 fillers, piece 1's FILLERS, past CONSULT_TUTOR_MIN_LIBRARY. */
    private static final int FILLERS = 80;
    /** Piece 1's line of Tainted Pact and Thassa's Oracle, beside the loop lines in R1's mixed tracker. */
    private static final String ORACLE_PACT = "1295-3093";
    /** Six cards no line names and that make no mana, below the pieces of the completers' board. */
    private static final List<String> OTHERS = List.of("Craw Wurm", "Runeclaw Bear", BEARS, "Counterspell",
            "Divination", "Opt");

    private Game game;
    private Player ai;
    private Player opp;
    private ComboTracker t;
    private Card kinnan;

    /** Kinnan's list as the tracker reads it beside its commander: every piece of the 19 loop rows but Kinnan, the two
     *  sinks (no row names them) and lands. */
    private static List<String> kinnansList() {
        final Set<String> names = new LinkedHashSet<>();
        for (final ComboLines.Line l : ComboLoopBoards.shippedLoopLines(LoopRows.ids().toArray(new String[0]))) {
            names.addAll(l.names());
        }
        names.remove(KINNAN);
        names.addAll(List.of(BALLISTA, THRASIOS, "Forest", "Island"));
        return List.copyOf(names);
    }

    private static List<String> ids(final List<ComboLines.Line> lines) {
        return lines.stream().map(l -> l.variant().id()).collect(Collectors.toList());
    }

    private static List<String> names(final Iterable<Card> cards) {
        final List<String> out = new ArrayList<>();
        for (final Card c : cards) {
            out.add(ComboTracker.nameOf(c));
        }
        return out;
    }

    /** A two-player game whose AI (player 1, on the Default profile, in its own Main 1, with no AI deadline) has
     *  {@code deck} registered with Kinnan as its commander, and Kinnan ready on its battlefield. */
    private void start(final List<String> deck) {
        game = initAndCreateGame();
        ComboLoopBoards.noDeadline(game);
        ai = game.getPlayers().get(1);
        opp = game.getPlayers().get(0);
        ((LobbyPlayerAi) ai.getLobbyPlayer()).setAiProfile("Default");
        ComboLoopBoards.deck(ai, KINNAN, deck.toArray(new String[0]));
        t = ComboLoopBoards.aiOf(ai).getComboTracker();
        kinnan = ComboLoopBoards.ready(addCard(KINNAN, ai));
    }

    /** The default board, with {@code extra} registered beside Kinnan's list. */
    private void kinnansBoard(final String... extra) {
        final List<String> deck = new ArrayList<>(kinnansList());
        deck.addAll(Arrays.asList(extra));
        start(deck);
    }

    private void lands(final String... names) {
        for (final String name : names) {
            addCard(name, ai);
        }
    }

    private Card hand(final String name) {
        return addCardToZone(name, ai, ZoneType.Hand);
    }

    /** Our card {@code name} put under our library: the first card put there is its top. */
    private Card library(final String name) {
        return addCardToZone(name, ai, ZoneType.Library);
    }

    /** The first {@code n} unique card names (StaticData's getUniqueCards, in its order) that are no land, no piece of
     *  a shipped line and no sink. */
    private static List<String> fillers(final int n) {
        final Set<String> pieces = new HashSet<>(List.of(BALLISTA, THRASIOS));
        for (final ComboLines.Line l : ComboLines.shipped()) {
            pieces.addAll(l.names());
        }
        final List<String> out = new ArrayList<>(n);
        for (final PaperCard pc : StaticData.instance().getCommonCards().getUniqueCards()) {
            if (out.size() == n) {
                break;
            }
            if (!pc.getRules().getType().isLand() && !pieces.contains(pc.getName())) {
                out.add(pc.getName());
            }
        }
        assertEquals(out.size(), n);
        return out;
    }

    /** Our library: the FILLERS fillers, with {@code pieces} after the first half of them; a singleton library. */
    private void deepLibrary(final String... pieces) {
        final List<String> names = fillers(FILLERS);
        for (int i = 0; i < FILLERS; i++) {
            if (i == FILLERS / 2) {
                for (final String piece : pieces) {
                    library(piece);
                }
            }
            library(names.get(i));
        }
        assertEquals(ai.getCardsIn(ZoneType.Library).size(), FILLERS + pieces.length);
    }

    /** The deck line of that id. */
    private ComboLines.Line line(final String id) {
        for (final ComboLines.Line l : t.deckLines()) {
            if (l.variant().id().equals(id)) {
                return l;
            }
        }
        throw new AssertionError("the deck holds no line " + id + ": " + ids(t.deckLines()));
    }

    /** Piece 1's line 1295-3093 and the 19 loop lines, as the shipped table holds them: R1's mixed tracker, which
     *  leaves out 742-1295 (Consultation and Oracle, assembled on its boards, so piece 1's own rule would stop the
     *  tutor: ComboConsultTutorAiTest.anAssembledLineSpendsNoConsultation). */
    private static List<ComboLines.Line> mixed() {
        final List<String> ids = new ArrayList<>(List.of(ORACLE_PACT));
        ids.addAll(LoopRows.ids());
        return ComboLoopBoards.shippedLoopLines(ids.toArray(new String[0]));
    }

    /** {@code c}'s first spell, with its owner as its activator. */
    private static SpellAbility castOf(final Card c) {
        final SpellAbility sa = c.getFirstSpellAbility();
        sa.setActivatingPlayer(c.getOwner());
        return sa;
    }

    /** {@code name}'s first spell, the card put in our hand, with us as its activator. */
    private SpellAbility spell(final String name) {
        return castOf(hand(name));
    }

    /** The cards of our library that {@code sa}'s search may take (its ChangeType, as ChangeZoneEffect reads it), in
     *  the library's order: a fresh list each call, since the stock pick shuffles the list it is given. */
    private CardCollection searchable(final SpellAbility sa) {
        return CardLists.getValidCards(ai.getCardsIn(ZoneType.Library), sa.getParam("ChangeType"), ai,
                sa.getHostCard(), sa);
    }

    /** The chooser as ChangeZoneEffect calls it: our own search of our library, decided by us. */
    private Card pick(final ZoneType destination, final SpellAbility sa, final CardCollection fetchList) {
        return ChangeZoneAi.chooseCardToHiddenOriginChangeZone(destination, Arrays.asList(ZoneType.Library), sa,
                fetchList, ai, ai);
    }

    /** Kinnan's {5}{G}{U} dig, with us as its activator. */
    private SpellAbility digOf(final Card c) {
        for (final SpellAbility sa : c.getSpellAbilities()) {
            if (sa.getApi() == ApiType.Dig) {
                sa.setActivatingPlayer(ai);
                return sa;
            }
        }
        throw new AssertionError("no dig on " + c);
    }

    /** Force of Will's spell whose cost has a CostExile: its alternative cost, read as the AI's own list reads it
     *  (ComboHoldAiTest's forceAlt). */
    private SpellAbility forceAlt(final Card force) {
        for (final SpellAbility sa : ComputerUtilAbility.getOriginalAndAltCostAbilities(
                new ArrayList<>(force.getAllSpellAbilities()), ai)) {
            if (sa.getPayCosts().hasSpecificCostType(CostExile.class)) {
                return sa;
            }
        }
        throw new AssertionError("no spell of Force of Will exiles a card");
    }

    /** Demonic Consultation's cast decision (SpecialCardAi.DemonicConsultation, ChooseCardNameAi's route). */
    private AiPlayDecision consider(final SpellAbility sa) {
        return SpecialCardAi.DemonicConsultation.consider(ai, sa).decision();
    }

    /** The name the AI chooses at {@code sa}'s resolution (ChooseCardNameEffect's call). */
    private String named(final SpellAbility sa) {
        return ((PlayerControllerAi) ai.getController()).chooseCardName(sa, null, null, "");
    }

    /** The stock namer's answer (PlayerControllerAi.chooseCardName's Consultation branch): the first unique card name
     *  that no card of our library bears. */
    private String stockName() {
        final Set<String> library = new HashSet<>();
        for (final Card c : ai.getCardsIn(ZoneType.Library)) {
            library.add(c.getName());
        }
        for (final PaperCard pc : StaticData.instance().getCommonCards().getUniqueCards()) {
            if (!library.contains(pc.getName())) {
                return pc.getName();
            }
        }
        throw new AssertionError("every card name is in the library");
    }

    /** {@link ComboLoopBoards#assertStockParity} against the shipped lines, after one stock call with no line: on some
     *  boards the stock pick's first call draws what its later calls do not (ComboFetchAiTest.assertWarmStockParity),
     *  so two warm calls are compared. The tracker keeps the shipped lines. */
    private <T> T assertWarmStockParity(final Supplier<T> decision) {
        t.useLines(List.of());
        decision.get();
        return ComboLoopBoards.assertStockParity(decision, ai, ComboLines.shipped());
    }

    @Test
    public void aMissingLoopPieceIsFetchedOneShort() {
        kinnansBoard(ENLIGHTENED);
        lands("Forest", "Forest", "Island", "Island");
        final Card basalt = library(BASALT);
        final Card lotus = library(LOTUS);
        library(BEARS);
        // Basalt Monolith only in our library beside Kinnan: 129-4131 is one short, its {3} in next turn's four
        assertEquals(ids(t.oneShort()), List.of("129-4131"));
        assertEquals(t.missing(line("129-4131")), List.of(BASALT));
        // Enlightened Tutor finds an artifact or an enchantment and puts it on top (Worldly Tutor finds a creature)
        final SpellAbility tutor = spell(ENLIGHTENED);
        assertEquals(tutor.getParam("LibraryPosition"), "0");
        assertEquals(names(searchable(tutor)), List.of(BASALT, LOTUS));

        assertSame(ComboFetch.searchPick(tutor, ZoneType.Library, searchable(tutor), ai, ai), basalt);
        // with no line the stock pick takes the dearest artifact (getBestAI's getMostExpensivePermanentAI)
        t.useLines(List.of());
        assertSame(pick(ZoneType.Library, tutor, searchable(tutor)), lotus);
        t.useLines(ComboLines.shipped());
        assertSame(pick(ZoneType.Library, tutor, searchable(tutor)), basalt);
        // Enlightened Tutor resolving: the search, the shuffle, then Basalt on top, the same card, a known top
        AbilityUtils.resolve(tutor);
        assertSame(ai.getZone(ZoneType.Library).get(0), basalt);
        assertEquals(t.knownTops(), Set.of(basalt.getId()));
    }

    @Test
    public void kinnansDigTakesAMissingCreaturePiece() {
        kinnansBoard();
        addCard(BASALT, ai);
        addCard(GRIM, ai);
        lands("Forest");
        final Card nyxbloom = library(NYXBLOOM);
        final Card ulamog = library(ULAMOG);
        library("Forest");
        library("Brainstorm");
        library("Opt");
        // Basalt's 3, Grim's 3 and the spare Forest, and one for the land left in our library: next turn's 8 covers
        // Nyxbloom's 7, so both of its lines are one short (129-4131 is assembled)
        assertEquals(ComboSignals.nextTurnManaNow(ai), 8);
        assertEquals(ids(t.oneShort()), List.of("2585-3430", "3430-4131"));
        final SpellAbility dig = digOf(kinnan);
        assertEquals(dig.getParam("DestinationZone"), "Battlefield");
        // the dig's five, and among them the non-Human creatures DigEffect's ChangeValid lets it take
        final List<Card> five = new ArrayList<>(ai.getCardsIn(ZoneType.Library)).subList(0, 5);
        final CardCollection valid = CardLists.getValidCards(five, dig.getParam("ChangeValid"), ai, kinnan, dig);
        assertEquals(names(valid), List.of(NYXBLOOM, ULAMOG));

        assertSame(ComboFetch.digPick(ai, dig, valid, ai), nyxbloom);
        // with no line the stock pick (getBestCreatureAI) takes the bigger creature
        t.useLines(List.of());
        assertSame(new DigAi().chooseSingleCard(ai, dig, valid, true, ai, Map.of()), ulamog);
        t.useLines(ComboLines.shipped());
        assertSame(new DigAi().chooseSingleCard(ai, dig, valid, true, ai, Map.of()), nyxbloom);
        // the dig resolving, whose DigEffect asks this chooser: Nyxbloom onto our battlefield, Ulamog to the bottom of
        // our library
        AbilityUtils.resolve(dig);
        assertEquals(t.where(NYXBLOOM), ComboTracker.Where.BATTLEFIELD);
        assertNotNull(t.find(ULAMOG, ZoneType.Library));
    }

    @Test
    public void aLoopPieceIsNotPitched() {
        kinnansBoard();
        addCard(GRIM, ai);
        lands("Island", "Island", "Forest", "Forest");
        final Card sakashima = hand(SAKASHIMA);
        final Card force = hand(FORCE);
        // Kinnan and Grim Monolith on our battlefield, Sakashima (blue) in our hand: 129-2585-2719 is live and holds it
        final ComboLines.Line grim = line("129-2585-2719");
        assertTrue(t.live().contains(grim));
        assertEquals(t.missing(grim), List.of());
        assertTrue(t.protects(sakashima));
        final SpellAbility fowAlt = forceAlt(force);

        // Sakashima is the only blue card Force of Will could exile
        assertTrue(ComboHold.vetoesPitch(ai, fowAlt, fowAlt.getPayCosts()));
        t.useLines(List.of());
        assertFalse(ComboHold.vetoesPitch(ai, fowAlt, fowAlt.getPayCosts()));
        t.useLines(ComboLines.shipped());
        // a second blue card that no line names can go instead, and the exile's choice spares Sakashima
        final Card brainstorm = hand("Brainstorm");
        assertFalse(ComboHold.vetoesPitch(ai, fowAlt, fowAlt.getPayCosts()));
        assertEquals(names(ComboHold.spare(ai, new CardCollection(List.of(sakashima, brainstorm)), 1)),
                List.of("Brainstorm"));
    }

    @Test
    public void aLoopPieceIsNotScriedToTheBottom() {
        kinnansBoard();
        // three lands and none in hand: the stock scry bottoms every nonland (SCRY_NUM_LANDS_TO_STILL_NEED_MORE is 4)
        lands("Forest", "Island", "Island");
        final Card basalt = library(BASALT);
        library(BEARS);
        assertSame(ai.getZone(ZoneType.Library).get(0), basalt);
        assertEquals(ids(t.oneShort()), List.of("129-4131"));

        assertTrue(ComboFetch.keepsOnTop(ai, basalt));
        assertFalse(ComputerUtil.scryWillMoveCardToBottomOfLibrary(ai, basalt));
        // with no line it is the stock scry's
        t.useLines(List.of());
        assertFalse(ComboFetch.keepsOnTop(ai, basalt));
        assertTrue(ComputerUtil.scryWillMoveCardToBottomOfLibrary(ai, basalt));
    }

    @Test
    public void castingALoopPieceIsNeverVetoed() {
        kinnansBoard();
        lands("Forest", "Island", "Island");
        final Card basalt = hand(BASALT);
        // Basalt Monolith in our hand beside Kinnan: 129-4131 is live, and Basalt is held, never spent for value
        assertTrue(t.live().contains(line("129-4131")));
        assertTrue(t.protects(basalt));

        assertFalse(ComboHold.vetoesCast(ai, castOf(basalt)));
        // and in our Main 2 the AI casts it, as stock does, with the stock draws
        moveToMain2(game, ai);
        assertEquals(assertWarmStockParity(() -> {
            final List<SpellAbility> choice = ComboLoopBoards.aiOf(ai).chooseSpellAbilityToPlay();
            return choice == null || choice.isEmpty() ? null : ComboTracker.nameOf(choice.get(0).getHostCard());
        }), BASALT);
    }

    @Test
    public void consultationNeverTutorsForALoopPiece() {
        kinnansBoard(CONSULTATION);
        lands("Swamp", "Swamp", "Forest", "Island");
        final Card consultation = hand(CONSULTATION);
        deepLibrary(BASALT);
        // the deck's lines are the 19 loops alone (no winner beside Consultation), and 129-4131 is one short of Basalt
        // Monolith, in a library deep enough for the tutor
        assertEquals(t.deckLines().size(), 19);
        assertTrue(t.deckLines().stream().allMatch(ComboLines.Line::isLoop));
        assertEquals(ids(t.oneShort()), List.of("129-4131"));
        assertTrue(ai.getCardsIn(ZoneType.Library).size() >= ComboFetch.CONSULT_TUTOR_MIN_LIBRARY);
        final SpellAbility sa = castOf(consultation);

        assertNull(ComboFetch.consultTutor(ai, sa));
        assertNull(t.takeConsultIntent(consultation.getId()), "an intent was recorded");
        // the cast decision is the stock refusal, with the stock draws, and its resolution names the stock card
        assertEquals(assertWarmStockParity(() -> consider(castOf(consultation))), AiPlayDecision.CantPlayAi);
        assertEquals(named(sa), stockName());
    }

    @Test
    public void consultationTutorsForTheOracleLineInAMixedDeck() {
        kinnansBoard(ORACLE, CONSULTATION, PACT);
        // R1: the Oracle line and the loop lines, not the shipped lines, whose 742-1295 would be assembled here
        t.useLines(mixed());
        addCard(BASALT, ai);
        lands("Island", "Island", "Swamp", "Swamp", "Forest");
        hand(ORACLE);
        final Card consultation = hand(CONSULTATION);
        deepLibrary(PACT, NYXBLOOM);
        assertTrue(t.singletonLibrary());
        // Kinnan and Basalt on our battlefield: 129-4131 is live and assembled
        final ComboLines.Line rock = line("129-4131");
        assertTrue(t.live().contains(rock));
        assertEquals(t.missing(rock), List.of());
        // the Oracle line one short of Tainted Pact, 3430-4131 one short of Nyxbloom
        assertEquals(ids(t.oneShort()), List.of(ORACLE_PACT, "3430-4131"));
        assertEquals(t.missing(line(ORACLE_PACT)), List.of(PACT));
        assertEquals(t.missing(line("3430-4131")), List.of(NYXBLOOM));
        final SpellAbility sa = castOf(consultation);

        assertEquals(consider(sa), AiPlayDecision.WillPlay);
        assertEquals(named(sa), PACT);
    }

    @Test
    public void intuitionIgnoresLoopCompleters() {
        completersBoard();
        final SpellAbility intuition = spell(INTUITION);

        assertEquals(ComboFetch.completers(ai), List.of());
        assertNull(ComboFetch.intuitionPile(ai));
        // and the whole pile (Intuition.considerMultiple, on the Default profile's INTUITION_ALTERNATIVE_LOGIC) is the
        // stock one, with the stock draws
        assertWarmStockParity(() -> new ArrayList<>(SpecialCardAi.Intuition.considerMultiple(ai, intuition)));
    }

    @Test
    public void dangerFetchSkipsLoopLines() {
        kinnansBoard();
        lands("Island", "Island", "Swamp");
        final Card basalt = library(BASALT);
        final Card bears = library(BEARS);
        library("Divination");
        ai.setLife(3, null);
        ComboLoopBoards.ready(addCard(DREADMAW, opp));
        // at 3 life against the Dreadmaw (Kinnan chumps it, its trample puts 4 through), 129-4131's only signal is
        // DANGER, so no line is one short; Basalt's {3} is open now and next turn
        final ComboLines.Line rock = line("129-4131");
        assertEquals(ComboSignals.abandon(ai, rock, t), ComboSignals.Signal.DANGER);
        assertEquals(t.oneShort(), List.of());

        // our Main 1: a search to our hand takes nothing for the loop, and the pick is stock's, with the stock draws
        final SpellAbility tutor = spell("Demonic Tutor");
        assertNull(ComboFetch.searchPick(tutor, ZoneType.Hand, searchable(tutor), ai, ai));
        assertWarmStockParity(() -> pick(ZoneType.Hand, tutor, searchable(tutor)));

        // the opponent's end step, our turn next: a search to the top of our library, the Vault's dig and a move to the
        // top take nothing for it either
        game.getPhaseHandler().devModeSet(PhaseType.END_OF_TURN, opp);
        assertSame(game.getPhaseHandler().getNextTurn(), ai);
        assertEquals(ComboSignals.abandon(ai, rock, t), ComboSignals.Signal.DANGER);
        final SpellAbility vampiric = spell("Vampiric Tutor");
        assertNull(ComboFetch.searchPick(vampiric, ZoneType.Library, searchable(vampiric), ai, ai));
        assertNull(ComboFetch.vaultDig(ai, List.of(bears, basalt)));
        final List<Card> order = new ArrayList<>(List.of(bears, basalt));
        ComboFetch.pieceFirst(ai, order, () -> true);
        assertEquals(order, List.of(bears, basalt));
        assertEquals(t.knownTops(), Set.of());
    }

    @Test
    public void aDeckWithNoLineFetchesAsStock() {
        // Kinnan as commander, the two sinks and a rock, but no loop row whole: the deck holds no line, piece 1's or
        // piece 2's
        start(List.of(BALLISTA, THRASIOS, SOL_RING, LOTUS, ENLIGHTENED, "Forest", "Island"));
        lands("Forest", "Forest", "Island", "Island");
        library(SOL_RING);
        library(LOTUS);
        library(BALLISTA);
        library(BEARS);
        final SpellAbility tutor = spell(ENLIGHTENED);

        assertNotNull(assertWarmStockParity(() -> pick(ZoneType.Library, tutor, searchable(tutor))));
        assertEquals(t.deckLines(), List.of());
    }

    // ---- beyond the plan's ten ----

    /** Kinnan's list with Intuition and Gifts Ungiven registered; Basalt Monolith, Grim Monolith, Hullbreaker Horror
     *  and Sol Ring on our battlefield beside Kinnan, three Islands; in our library Mana Vault, Sakashima of a Thousand
     *  Faces and Nyxbloom Ancient, then OTHERS. Five loop lines are one short, with three distinct missing pieces among
     *  them: as completers they would pass Gifts Ungiven's floor and fill Intuition's three-card pile. */
    private void completersBoard() {
        kinnansBoard(INTUITION, GIFTS);
        for (final String name : List.of(BASALT, GRIM, HULLBREAKER, SOL_RING)) {
            addCard(name, ai);
        }
        lands("Island", "Island", "Island");
        library(MANA_VAULT);
        library(SAKASHIMA);
        library(NYXBLOOM);
        for (final String name : OTHERS) {
            library(name);
        }
        // ranked by the mana still to cast: Mana Vault 1 (x-2, then x-8 by id), Sakashima 4, Nyxbloom 7
        assertEquals(ids(t.oneShort()), List.of("x-2", "x-8", "129-2585-2719", "2585-3430", "3430-4131"));
    }

    @Test
    public void giftsIgnoresLoopCompleters() {
        completersBoard();
        final SpellAbility gifts = spell(GIFTS);

        assertFalse(ComboFetch.giftsReady(ai));
        assertNull(ComboFetch.giftsPick(ai, new CardCollection(ai.getCardsIn(ZoneType.Library))));
        // its floor and its four picks are stock's, with the stock draws
        assertWarmStockParity(() -> SpecialCardAi.GiftsUngiven.worthSearching(ai, gifts));
        assertWarmStockParity(() -> pick(ZoneType.Library, gifts, new CardCollection(ai.getCardsIn(ZoneType.Library))));
    }

    @Test
    public void consultationPassesOverALoopLineRankedFirst() {
        // the mixed tracker with the loop line first by rank: Basalt Monolith's 3 still to cast against Oracle's 2 and
        // Tainted Pact's 2, so Consultation's tutor passes over a loop line it meets before piece 1's
        kinnansBoard(ORACLE, CONSULTATION, PACT);
        t.useLines(mixed());
        lands("Island", "Island", "Swamp", "Swamp", "Forest");
        hand(ORACLE);
        final Card consultation = hand(CONSULTATION);
        deepLibrary(PACT, BASALT);
        assertTrue(t.singletonLibrary());
        assertEquals(ids(t.oneShort()), List.of("129-4131", ORACLE_PACT));
        final SpellAbility sa = castOf(consultation);

        assertEquals(consider(sa), AiPlayDecision.WillPlay);
        assertEquals(named(sa), PACT);
    }

    @Test
    public void aLoopOnlyDeckRefusesTheTutorBeforeReadingAnySignal() {
        // consultTutor's board gates come before any line's signals: a loop-only deck holds no line of piece 1's that
        // does not name Consultation, so the tutor is refused before DANGER is read (the view counts its block matrix)
        kinnansBoard(CONSULTATION);
        lands("Swamp", "Swamp", "Forest", "Island");
        final Card consultation = hand(CONSULTATION);
        deepLibrary(BASALT);
        ai.setLife(3, null);
        ComboLoopBoards.ready(addCard(DREADMAW, opp));
        final ComboTracker.View v = t.view();

        assertNull(ComboFetch.consultTutor(ai, castOf(consultation), v));
        assertEquals(v.blockMatrixBuilds(), 0, "a line's signals were read");
        // the danger is real: read, it builds the Dreadmaw's matrix
        assertTrue(v.danger());
        assertEquals(v.blockMatrixBuilds(), 1);
    }
}
