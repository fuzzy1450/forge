package forge.ai.combo;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertSame;
import static org.testng.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import org.testng.annotations.Test;

import forge.ai.AITest;
import forge.ai.AiController;
import forge.ai.LobbyPlayerAi;
import forge.ai.PlayerControllerAi;
import forge.ai.SpecialCardAi;
import forge.ai.ability.ChangeZoneAi;
import forge.game.Game;
import forge.game.ability.AbilityKey;
import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.card.CardLists;
import forge.game.card.CardPredicates;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import forge.util.MyRandom;

/**
 * Intuition's and Gifts Ungiven's piles (spec 2026-10-22-combo-aware-ai-core-design.md section 6.3): the cards they
 * search for are chosen so that the AI ends up with a one-short combo line's missing piece. Intuition reveals three
 * completers (any card the opponent hands over completes a line), else the creature piece and the two cheapest spells
 * that are no piece, since the opponent's stock pick prefers a creature; Gifts Ungiven takes the completers first, but
 * only with three or more, so any two left after the opponent bins two still hold one. A deck holding no line keeps
 * the stock pile, picks and draws. Unless a test says otherwise the AI plays the Default profile (whose
 * INTUITION_ALTERNATIVE_LOGIC is on) in its own MAIN1, with five lands in play.
 */
public class ComboPilesAiTest extends AITest {
    private static final String WIN = "F|2|S|Win the game";
    private static final String EXILE = "F|88|H|Exile your library";
    private static final String ORACLE_CONSULTATION = "V|742-1295|3|{U}{U}{B}|2,88|H:Demonic Consultation|H:Thassa's Oracle";
    private static final String LAB_CONSULTATION = "V|742-2836|1|{B}|2,88|H:Demonic Consultation|B:Laboratory Maniac";
    private static final String JACE_CONSULTATION =
            "V|742-3096|1|{B}|2,88|H:Demonic Consultation|B:Jace, Wielder of Mysteries";
    private static final String DOOM_CONSULTATION = "V|x-1|1|{B}|2,88|H:Demonic Consultation|B:Doctor Doom, Unrivaled";
    private static final String PACT_ORACLE = "V|1295-3093|4|{1}{U}{U}{B}|2|H:Tainted Pact|H:Thassa's Oracle";

    private static final String ORACLE = "Thassa's Oracle";
    private static final String LAB = "Laboratory Maniac";
    private static final String DOOM = "Doctor Doom, Unrivaled";
    private static final String JACE = "Jace, Wielder of Mysteries";
    private static final String CONSULTATION = "Demonic Consultation";
    private static final String PACT = "Tainted Pact";
    private static final String INTUITION = SpecialCardAi.IntuitionPile.NAME;
    private static final String GIFTS = SpecialCardAi.GiftsUngiven.NAME;

    // seven distinct names, none a piece: five creatures and two spells (Counterspell at two, Divination at three)
    private static final List<String> OTHERS = List.of("Craw Wurm", "Runeclaw Bear", "Grizzly Bears", "Hill Giant",
            "Serra Angel", "Counterspell", "Divination");
    // big creatures, for the singleton libraries beside Tainted Pact
    private static final List<String> BIG = List.of("Craw Wurm", "Shivan Dragon", "Serra Angel", "Hill Giant",
            "Air Elemental");

    // two seeds, two shuffles of the pile the opponent's stock pick shuffles
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

    private static List<String> names(final List<Card> cards) {
        return cards.stream().map(ComboTracker::nameOf).collect(Collectors.toList());
    }

    /** A two-player game whose AI (player 1, on the Default profile, in its own MAIN1) tracks {@code rows}, with
     *  Island, Island, Swamp, Swamp and Swamp in play: the whole cost of every line here (Consultation and Doctor
     *  Doom's five the dearest), so NO_MANA is quiet; nothing in hand or library yet. */
    private void start(final String... rows) {
        game = initAndCreateGame();
        ai = game.getPlayers().get(1);
        opp = game.getPlayers().get(0);
        ((LobbyPlayerAi) ai.getLobbyPlayer()).setAiProfile("Default");
        t = aiOf(ai).getComboTracker();
        t.useLines(lines(rows));
        for (final String land : List.of("Island", "Island", "Swamp", "Swamp", "Swamp")) {
            addCard(land, ai);
        }
    }

    private void library(final List<String> names) {
        for (final String name : names) {
            addCardToZone(name, ai, ZoneType.Library);
        }
    }

    private void library(final String... names) {
        library(Arrays.asList(names));
    }

    /** Lines 742-1295, 742-2836 and x-1, Demonic Consultation in hand; in the library Thassa's Oracle, Laboratory
     *  Maniac, Doctor Doom with {@code doom}, then the first {@code others} of OTHERS. With {@code doom} the three lines
     *  are one short; without it the deck holds no Doctor Doom, so x-1 is no deck line. */
    private void completerBoard(final boolean doom, final int others) {
        start(ORACLE_CONSULTATION, LAB_CONSULTATION, DOOM_CONSULTATION);
        addCardToZone(CONSULTATION, ai, ZoneType.Hand);
        library(ORACLE, LAB);
        if (doom) {
            library(DOOM);
        }
        library(OTHERS.subList(0, others));
    }

    /** Five Islands in hand beside Consultation: six cards besides Gifts Ungiven, so the two it brings would take the
     *  hand over seven before cleanup, and the stock floor (GiftsUngiven.worthSearching's F4) refuses. */
    private void fullHand() {
        for (int i = 0; i < 5; i++) {
            addCardToZone("Island", ai, ZoneType.Hand);
        }
    }

    /** Line 1295-3093, Tainted Pact in hand, over a singleton library: Thassa's Oracle, Ponder, Brainstorm and BIG.
     *  Returns the library's Oracle. */
    private Card pactBoard() {
        start(PACT_ORACLE);
        addCardToZone(PACT, ai, ZoneType.Hand);
        library(ORACLE, "Ponder", "Brainstorm");
        library(BIG);
        assertTrue(t.singletonLibrary());
        assertEquals(ids(t.oneShort()), List.of("1295-3093"));
        return t.find(ORACLE, ZoneType.Library);
    }

    /** {@code name}'s first spell, the card put in our hand, with us as its activator (its sub-abilities too). */
    private SpellAbility spell(final String name) {
        final SpellAbility sa = addCardToZone(name, ai, ZoneType.Hand).getFirstSpellAbility();
        sa.setActivatingPlayer(ai);
        return sa;
    }

    /** The stock evaluator's list for {@code sa}, Intuition.considerMultiple, the hooked one. */
    private List<Card> pile(final SpellAbility sa) {
        return new ArrayList<>(SpecialCardAi.Intuition.considerMultiple(ai, sa));
    }

    /** The chooser as IntuitionPickAiTest's pick calls it: our own search of our library, decided by us. */
    private Card pick(final SpellAbility sa, final CardCollection fetchList) {
        return ChangeZoneAi.chooseCardToHiddenOriginChangeZone(ZoneType.Library, Arrays.asList(ZoneType.Library), sa,
                fetchList, ai, ai);
    }

    /** Gifts Ungiven's four picks as ChangeZoneEffect's one-at-a-time loop makes them: DifferentNames drops every name
     *  chosen so far from the list, and each pick leaves the list (the cards stay in the library until the search
     *  ends). */
    private List<Card> giftsPicks(final SpellAbility gifts) {
        CardCollection fetchList = new CardCollection(ai.getCardsIn(ZoneType.Library));
        final List<Card> chosen = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            for (final Card c : chosen) {
                fetchList = CardLists.filter(fetchList, CardPredicates.sharesNameWith(c).negate());
            }
            final Card c = pick(gifts, fetchList);
            assertNotNull(c, "pick " + (i + 1));
            fetchList.remove(c);
            chosen.add(c);
        }
        return chosen;
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
    public void intuitionAllCompleters() {
        completerBoard(true, OTHERS.size());
        // ranked by the mana still to cast beside Consultation's 1: Oracle 2, Laboratory Maniac 3, Doctor Doom 4
        assertEquals(ids(t.oneShort()), List.of("742-1295", "742-2836", "x-1"));
        final SpellAbility intuition = spell(INTUITION);

        final List<Card> pile = pile(intuition);
        assertEquals(names(pile), List.of(ORACLE, LAB, DOOM));
        for (final Card c : pile) {
            assertTrue(c.isInZone(ZoneType.Library), c + " is in the library");
            assertEquals(c.getOwner(), ai);
        }
        assertEquals(ComboFetch.completers(ai), List.of(ORACLE, LAB, DOOM));
    }

    @Test
    public void intuitionCreaturePiece() {
        final Card oracle = pactBoard();

        final List<Card> pile = pile(spell(INTUITION));
        assertEquals(pile.size(), 3);
        assertSame(pile.get(0), oracle);
        // the library's only noncreature nonland cards, both at one: ties go by name
        assertEquals(names(pile.subList(1, 3)), List.of("Brainstorm", "Ponder"));
        assertEquals(ComboFetch.completers(ai), List.of(ORACLE));
    }

    @Test
    public void intuitionOpponentHandsUsTheCreature() {
        final Card oracle = pactBoard();
        // four lands in play: with no land in hand, fewer than four in play and nothing castable, the opponent's stock
        // pick fixes basics instead (ChangeZoneAi.basicManaFixing, which takes the shuffled pile's first card)
        for (int i = 0; i < 4; i++) {
            addCard("Plains", opp);
        }
        final SpellAbility intuition = spell(INTUITION);
        final List<Card> pile = pile(intuition);
        assertSame(pile.get(0), oracle);
        final SpellAbility sub = intuition.getSubAbility();
        assertEquals(sub.getParam("Destination"), "Hand");

        for (final long seed : SHUFFLE_SEEDS) {
            MyRandom.setRandom(new Random(seed));
            assertSame(ChangeZoneAi.chooseCardToHiddenOriginChangeZone(ZoneType.Hand, Arrays.asList(ZoneType.Library),
                    sub, new CardCollection(pile), ai, opp), oracle, "seed " + seed);
        }
    }

    @Test
    public void giftsNeedsThreeCompleters() {
        final List<ComboLines.Line> held = lines(ORACLE_CONSULTATION, LAB_CONSULTATION, DOOM_CONSULTATION);

        // two completers: no Doctor Doom in the deck
        completerBoard(false, OTHERS.size());
        fullHand();
        final SpellAbility gifts = spell(GIFTS);
        assertEquals(ids(t.oneShort()), List.of("742-1295", "742-2836"));
        // the stock floor refuses (no room in hand), and so does the combo
        assertFalse(assertStockParity(() -> SpecialCardAi.GiftsUngiven.worthSearching(ai, gifts), held));
        assertStockParity(() -> pick(gifts, new CardCollection(ai.getCardsIn(ZoneType.Library))), held);
        assertEquals(ComboFetch.completers(ai), List.of(ORACLE, LAB));
        assertFalse(ComboFetch.giftsReady(ai));

        // three: Doctor Doom in the library too
        completerBoard(true, OTHERS.size());
        fullHand();
        final SpellAbility gifts3 = spell(GIFTS);
        assertEquals(ids(t.oneShort()), List.of("742-1295", "742-2836", "x-1"));
        t.useLines(List.of());
        assertFalse(SpecialCardAi.GiftsUngiven.worthSearching(ai, gifts3), "the stock floor");
        t.useLines(held);
        assertTrue(SpecialCardAi.GiftsUngiven.worthSearching(ai, gifts3));
        final List<Card> picks = giftsPicks(gifts3);
        assertEquals(names(picks.subList(0, 3)), List.of(ORACLE, LAB, DOOM));
        assertFalse(List.of(LAB, DOOM, ORACLE).contains(ComboTracker.nameOf(picks.get(3))));
        assertEquals(ComboFetch.completers(ai), List.of(ORACLE, LAB, DOOM));
        assertTrue(ComboFetch.giftsReady(ai));
    }

    @Test
    public void noLineKeepsTheStockPile() {
        start(ORACLE_CONSULTATION);
        addCardToZone(CONSULTATION, ai, ZoneType.Hand);
        library(ORACLE);
        library(OTHERS);
        final SpellAbility intuition = spell(INTUITION);

        // 742-2836 needs a Laboratory Maniac the deck lacks
        final List<Card> stock = assertStockParity(() -> pile(intuition), lines(LAB_CONSULTATION));
        assertEquals(t.deckLines(), List.of());
        assertFalse(stock.isEmpty());
    }

    // ---- beyond the plan's five ----

    @Test
    public void intuitionCasterPicksThePile() {
        // the 9C route (IntuitionPile.choosePick: the evaluator's first card still in the search) takes the pile, in
        // its order, over ChangeZoneEffect's three asks
        completerBoard(true, OTHERS.size());
        final SpellAbility intuition = spell(INTUITION);
        final CardCollection fetchList = new CardCollection(ai.getCardsIn(ZoneType.Library));
        final List<Card> picks = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            final Card c = pick(intuition, fetchList);
            picks.add(c);
            fetchList.remove(c);
        }
        assertEquals(names(picks), List.of(ORACLE, LAB, DOOM));
    }

    @Test
    public void intuitionFillersAreTheCheapestSpellsThatAreNoPiece() {
        // a land, a creature and the deck's other piece are never fillers; the cheapest spells are, ties by name,
        // never by the library's order (Ponder lies above Brainstorm)
        start(PACT_ORACLE, ORACLE_CONSULTATION);
        addCardToZone(PACT, ai, ZoneType.Hand);
        library(ORACLE, CONSULTATION, "Island", "Memnite", "Ponder", "Divination", "Counterspell", "Brainstorm",
                "Craw Wurm");
        assertTrue(t.singletonLibrary());
        // 742-1295 is two short (Consultation and Oracle both in the library): Consultation is a piece, no completer
        assertEquals(ids(t.oneShort()), List.of("1295-3093"));
        assertTrue(t.isPiece(CONSULTATION));
        // the two-short line is live all the same, and its first missing piece is still no completer (review round 2)
        assertEquals(ids(t.live()), List.of("1295-3093", "742-1295"));
        assertEquals(t.missing(t.live().get(1)), List.of(CONSULTATION, ORACLE));
        assertEquals(ComboFetch.completers(ai), List.of(ORACLE));

        assertEquals(names(pile(spell(INTUITION))), List.of(ORACLE, "Brainstorm", "Ponder"));
    }

    @Test
    public void intuitionKeepsTheStockPileWithoutACreaturePieceOrTwoFillers() {
        // Jace, Wielder of Mysteries is a planeswalker, which the opponent's creature pick would pass over
        start(JACE_CONSULTATION);
        addCardToZone(CONSULTATION, ai, ZoneType.Hand);
        library(JACE);
        library(OTHERS);
        final SpellAbility jaceIntuition = spell(INTUITION);
        assertEquals(ComboFetch.completers(ai), List.of(JACE));
        assertNull(ComboFetch.intuitionPile(ai));
        assertStockParity(() -> pile(jaceIntuition), lines(JACE_CONSULTATION));

        // one spell beside the creatures: no second filler
        start(PACT_ORACLE);
        addCardToZone(PACT, ai, ZoneType.Hand);
        library(ORACLE, "Brainstorm");
        library(BIG);
        final SpellAbility pactIntuition = spell(INTUITION);
        assertEquals(ComboFetch.completers(ai), List.of(ORACLE));
        assertNull(ComboFetch.intuitionPile(ai));
        assertStockParity(() -> pile(pactIntuition), lines(PACT_ORACLE));
    }

    @Test
    public void burningRuneDemonKeepsItsStockList() {
        // the pile is three cards, Intuition's: the other AILogic$ Intuition card searches for exactly two
        final Card oracle = pactBoard();
        assertSame(pile(spell(INTUITION)).get(0), oracle);
        final Card demon = addCard("Burning-Rune Demon", ai);
        final SpellAbility trig = demon.getTriggers().get(0).ensureAbility();
        trig.setActivatingPlayer(ai);
        assertEquals(trig.getParam("AILogic"), "Intuition");

        assertStockParity(() -> pile(trig), lines(PACT_ORACLE));
    }

    @Test
    public void completersAreDistinctMissingNamesInRankOrder() {
        // two lines missing Thassa's Oracle give one completer; ranked by the mana still to cast, 742-1295 (Consultation
        // 1 and Oracle 2), then at 4 Pact's Oracle line ahead of Laboratory Maniac's (an Oracle line wins on its casts)
        start(PACT_ORACLE, ORACLE_CONSULTATION, LAB_CONSULTATION);
        addCardToZone(CONSULTATION, ai, ZoneType.Hand);
        addCardToZone(PACT, ai, ZoneType.Hand);
        library(ORACLE, LAB);
        library(OTHERS);
        assertEquals(ids(t.oneShort()), List.of("742-1295", "1295-3093", "742-2836"));

        assertEquals(ComboFetch.completers(ai), List.of(ORACLE, LAB));
        assertFalse(ComboFetch.giftsReady(ai));
    }

    @Test
    public void giftsTakesOnlyOurCompleters() {
        completerBoard(true, OTHERS.size());
        final SpellAbility gifts = spell(GIFTS);
        // an opposing Thassa's Oracle first in the list is not ours to take
        final CardCollection fetchList = new CardCollection();
        fetchList.add(addCardToZone(ORACLE, opp, ZoneType.Library));
        fetchList.addAll(ai.getCardsIn(ZoneType.Library));

        assertSame(pick(gifts, fetchList), t.find(ORACLE, ZoneType.Library));
    }

    @Test
    public void giftsKeepsTheRefusalsThatEndTheSearch() {
        final List<ComboLines.Line> held = lines(ORACLE_CONSULTATION, LAB_CONSULTATION, DOOM_CONSULTATION);

        // a punished search (an opposing Archivist of Oghma) is never forced by the combo floor (follow-up F4; it
        // was lifted before, a soft refusal): the stock floor and the combo refuse alike
        completerBoard(true, OTHERS.size());
        addCard("Archivist of Oghma", opp).setSickness(false);
        final SpellAbility punished = spell(GIFTS);
        t.useLines(List.of());
        assertFalse(SpecialCardAi.GiftsUngiven.worthSearching(ai, punished), "the stock floor");
        t.useLines(held);
        assertTrue(ComboFetch.giftsReady(ai));
        assertFalse(SpecialCardAi.GiftsUngiven.worthSearching(ai, punished));

        // seven cards: four leave, and three would stay
        completerBoard(true, 4);
        assertEquals(ai.getCardsIn(ZoneType.Library).size(), 7);
        assertTrue(ComboFetch.giftsReady(ai));
        assertFalse(SpecialCardAi.GiftsUngiven.worthSearching(ai, spell(GIFTS)));

        // an opponent controls the search (Opposition Agent's record on us)
        completerBoard(true, OTHERS.size());
        ai.addControlledWhileSearching(game.getNextTimestamp(), opp);
        assertTrue(ComboFetch.giftsReady(ai));
        assertFalse(SpecialCardAi.GiftsUngiven.worthSearching(ai, spell(GIFTS)));

        // an Opposition Agent in play: its static writes that record at every state check, so F3 refuses ahead of the
        // combo, although the floor's later check of an opposing ControlOpponentsSearchingLibrary static is skipped
        completerBoard(true, OTHERS.size());
        addCard("Opposition Agent", opp).setSickness(false);
        game.getAction().checkStateEffects(true);
        assertTrue(ComboFetch.giftsReady(ai));
        assertFalse(SpecialCardAi.GiftsUngiven.worthSearching(ai, spell(GIFTS)));

        // Aven Mindcensor: the search sees only the top four, and which four is the library's order
        completerBoard(true, OTHERS.size());
        addCard("Aven Mindcensor", opp).setSickness(false);
        game.getAction().checkStateEffects(true);
        assertTrue(ai.hasKeyword("LimitSearchLibrary"));
        assertTrue(ComboFetch.giftsReady(ai));
        assertFalse(SpecialCardAi.GiftsUngiven.worthSearching(ai, spell(GIFTS)));
    }

    // ---- review round 1 ----

    @Test
    public void intuitionWithTwoCompletersPilesTheCreaturePieceAndTwoSpells() {
        // two completers make no all-completer pile, whose third card the opponent could hand back: the first is a
        // creature, so the pile is it and the two cheapest spells that are no piece
        completerBoard(false, OTHERS.size());
        assertEquals(ids(t.oneShort()), List.of("742-1295", "742-2836"));
        assertEquals(ComboFetch.completers(ai), List.of(ORACLE, LAB));

        final List<Card> pile = pile(spell(INTUITION));
        assertEquals(names(pile), List.of(ORACLE, "Counterspell", "Divination"));
        assertSame(pile.get(0), t.find(ORACLE, ZoneType.Library));
    }

    @Test
    public void intuitionWithFourCompletersTakesTheFirstThreeInRankOrder() {
        // ranked by the mana still to cast beside Consultation's 1: 742-1295 (Oracle 2), 742-2836 (Laboratory Maniac
        // 3), then 742-3096 and x-1 by id (Jace and Doctor Doom, 4 each): Doctor Doom, the last, stays out of the pile
        start(ORACLE_CONSULTATION, LAB_CONSULTATION, JACE_CONSULTATION, DOOM_CONSULTATION);
        addCardToZone(CONSULTATION, ai, ZoneType.Hand);
        library(ORACLE, LAB, JACE, DOOM);
        library(OTHERS);
        assertEquals(ids(t.oneShort()), List.of("742-1295", "742-2836", "742-3096", "x-1"));
        assertEquals(ComboFetch.completers(ai), List.of(ORACLE, LAB, JACE, DOOM));

        assertEquals(names(pile(spell(INTUITION))), List.of(ORACLE, LAB, JACE));
    }

    @Test
    public void theGiftsRouteServesGiftsUngivenAlone() {
        // three completers in the library, and another search (Entomb's, to the graveyard): it keeps its stock pick and
        // draws, where Gifts Ungiven's completer pick would bin a piece
        final List<ComboLines.Line> held = lines(ORACLE_CONSULTATION, LAB_CONSULTATION, DOOM_CONSULTATION);
        completerBoard(true, OTHERS.size());
        assertTrue(ComboFetch.giftsReady(ai));
        final SpellAbility entomb = spell("Entomb");
        assertEquals(entomb.getParam("Destination"), "Graveyard");

        final Card stock = assertStockParity(() -> ChangeZoneAi.chooseCardToHiddenOriginChangeZone(ZoneType.Graveyard,
                Arrays.asList(ZoneType.Library), entomb, new CardCollection(ai.getCardsIn(ZoneType.Library)), ai, ai),
                held);
        assertNotNull(stock);
        assertFalse(List.of(LAB, DOOM, ORACLE).contains(ComboTracker.nameOf(stock)), "the stock pick " + stock);
    }

    @Test
    public void deadLinesHaveNoCompleters() {
        // Demonic Consultation in the graveyard is gone (Review Focus 5): 742-1295 is dead, although Thassa's Oracle is
        // its only piece in the library, and nothing is piled for it
        start(ORACLE_CONSULTATION);
        final Card consultation = addCardToZone(CONSULTATION, ai, ZoneType.Hand);
        library(ORACLE);
        library(OTHERS);
        // Consultation in hand: the line is one short, and the pile is its creature piece
        assertEquals(ComboFetch.completers(ai), List.of(ORACLE));
        assertNotNull(ComboFetch.intuitionPile(ai));
        game.getAction().moveTo(ZoneType.Graveyard, consultation, null, AbilityKey.newMap());
        assertEquals(t.where(CONSULTATION), ComboTracker.Where.GONE);
        assertEquals(ids(t.deckLines()), List.of("742-1295"));
        assertEquals(t.missing(t.deckLines().get(0)), List.of(ORACLE));
        assertEquals(t.live(), List.of());
        assertEquals(ComboFetch.completers(ai), List.of());
        assertNull(ComboFetch.intuitionPile(ai));
        final SpellAbility consultationGone = spell(INTUITION);
        assertStockParity(() -> pile(consultationGone), lines(ORACLE_CONSULTATION));

        // Tainted Pact over a library holding two Islands stops at the second (Review Focus 1): 1295-3093 is dead too
        start(PACT_ORACLE);
        addCardToZone(PACT, ai, ZoneType.Hand);
        library(ORACLE, "Island", "Ponder", "Brainstorm");
        library(BIG);
        // one Island: the library is singleton, and the pile is the line's creature piece
        assertTrue(t.singletonLibrary());
        assertEquals(ComboFetch.completers(ai), List.of(ORACLE));
        assertNotNull(ComboFetch.intuitionPile(ai));
        library("Island");
        assertFalse(t.singletonLibrary());
        assertEquals(ids(t.deckLines()), List.of("1295-3093"));
        assertEquals(t.missing(t.deckLines().get(0)), List.of(ORACLE));
        assertEquals(t.live(), List.of());
        assertEquals(ComboFetch.completers(ai), List.of());
        assertNull(ComboFetch.intuitionPile(ai));
        final SpellAbility twoIslands = spell(INTUITION);
        assertStockParity(() -> pile(twoIslands), lines(PACT_ORACLE));
    }

    @Test
    public void keyCardsDoNotBeatTheGiftsCompleters() {
        // the Gifts route sits ahead of KeyCards, as the search pick does
        // (ComboFetchAiTest.comboPickGoesAheadOfKeyCards), and KeyCards stays the fallback after it: the fourth pick
        final List<ComboLines.Line> held = lines(ORACLE_CONSULTATION, LAB_CONSULTATION, DOOM_CONSULTATION);
        completerBoard(true, OTHERS.size());
        ai.getRegisteredPlayer().getDeck().addKeyCard("Craw Wurm");
        final SpellAbility gifts = spell(GIFTS);
        // with no line the deck's KeyCards decide: Craw Wurm
        t.useLines(List.of());
        assertEquals(pick(gifts, new CardCollection(ai.getCardsIn(ZoneType.Library))).getName(), "Craw Wurm");

        t.useLines(held);
        assertEquals(names(giftsPicks(gifts)), List.of(ORACLE, LAB, DOOM, "Craw Wurm"));
    }

    // ---- review round 2 ----

    /** Line 742-1295 one short and live: Demonic Consultation in hand, Thassa's Oracle and OTHERS in the library, so
     *  Oracle is the completer and Intuition piles it. Returns the line. */
    private ComboLines.Line liveOracleLine() {
        start(ORACLE_CONSULTATION);
        addCardToZone(CONSULTATION, ai, ZoneType.Hand);
        library(ORACLE);
        library(OTHERS);
        assertEquals(ids(t.deckLines()), List.of("742-1295"));
        final ComboLines.Line l = t.deckLines().get(0);
        assertPiledAgain(l);
        return l;
    }

    /** {@code l} is chased again: no signal, one short, Thassa's Oracle its completer, and Intuition piles it. */
    private void assertPiledAgain(final ComboLines.Line l) {
        assertNull(ComboSignals.abandon(ai, l, t));
        assertEquals(t.oneShort(), List.of(l));
        assertEquals(ComboFetch.completers(ai), List.of(ORACLE));
        assertNotNull(ComboFetch.intuitionPile(ai));
    }

    /** {@code signal} holds for {@code l}, which is still one short and reachable: it has no completer, and Intuition
     *  keeps the stock pile. */
    private void assertNotFetchedFor(final ComboLines.Line l, final ComboSignals.Signal signal) {
        assertEquals(ComboSignals.abandon(ai, l, t), signal);
        assertTrue(t.reachable(l));
        assertEquals(t.missing(l), List.of(ORACLE));
        assertEquals(t.live(), List.of());
        assertEquals(t.oneShort(), List.of());
        assertEquals(ComboFetch.completers(ai), List.of());
        assertNull(ComboFetch.intuitionPile(ai));
        final SpellAbility intuition = spell(INTUITION);
        assertStockParity(() -> pile(intuition), lines(ORACLE_CONSULTATION));
    }

    @Test
    public void theSignalsThatStopFetchingLeaveNoCompleters() {
        // signals 1, 3 and 4 stop fetching (spec section 6.5) while the line stays executable: no completer and the
        // stock pile, until the signal clears

        // NO_MANA: one land left, and the line's pieces cost three
        ComboLines.Line l = liveOracleLine();
        final List<Card> lands = new ArrayList<>(ai.getLandsInPlay());
        for (final Card land : lands.subList(1, lands.size())) {
            game.getAction().moveTo(ZoneType.Graveyard, land, null, AbilityKey.newMap());
        }
        assertEquals(t.executable(), List.of(l));
        assertNotFetchedFor(l, ComboSignals.Signal.NO_MANA);
        addCard("Island", ai);
        addCard("Swamp", ai);
        assertPiledAgain(l);

        // DANGER: 5 life against an opposing Colossal Dreadmaw, and no creature of ours to chump it
        l = liveOracleLine();
        ai.setLife(5, null);
        addCard("Colossal Dreadmaw", opp).setSickness(false);
        assertEquals(t.executable(), List.of(l));
        assertNotFetchedFor(l, ComboSignals.Signal.DANGER);
        addCard("Grizzly Bears", ai);
        assertPiledAgain(l);

        // SURER_KILL: in our MAIN1 our ready Grizzly Bears reach the opponent's 2 life, and nothing of theirs blocks
        l = liveOracleLine();
        opp.setLife(2, null);
        addCard("Grizzly Bears", ai).setSickness(false);
        assertEquals(t.executable(), List.of(l));
        assertNotFetchedFor(l, ComboSignals.Signal.SURER_KILL);
        opp.setLife(20, null);
        assertPiledAgain(l);
    }

    @Test
    public void aDeniedLineHasNoCompleters() {
        // signal 2 stops everything (spec section 6.5): with an opposing Platinum Angel we cannot win, and the line,
        // one short and reachable, is not even executable
        final ComboLines.Line l = liveOracleLine();
        final Card angel = addCard("Platinum Angel", opp);
        game.getAction().checkStateEffects(true);
        assertTrue(ai.cantWin());
        assertTrue(ComboSignals.denied(ai, l, t));
        assertEquals(t.executable(), List.of());
        assertNotFetchedFor(l, ComboSignals.Signal.DENIED);

        game.getAction().moveTo(ZoneType.Graveyard, angel, null, AbilityKey.newMap());
        game.getAction().checkStateEffects(true);
        assertFalse(ai.cantWin());
        assertPiledAgain(l);
    }

    @Test
    public void lichsMasteryLeavesTheCompleters() {
        // follow-up 7, L1 (Review Focus 5), aDeniedLineHasNoCompleters' twin: the opponent's Lich's Mastery ("you can't
        // lose the game") denies nothing (Evan's decision of 2026-10-28: a library-out win ignores it), so the line,
        // one short, is executable and chased, and Intuition still piles Oracle. At d2c86593647 signal 2's "every
        // opponent cannot lose" denied it, and the stock pile stood
        final ComboLines.Line l = liveOracleLine();
        addCard("Lich's Mastery", opp);
        game.getAction().checkStateEffects(true);
        assertFalse(ai.cantWin());
        assertTrue(opp.cantLose());
        assertFalse(ComboSignals.denied(ai, l, t));
        assertEquals(t.executable(), List.of(l));
        assertPiledAgain(l);
    }

    // ---- follow-up F4: fillers spare mana sources; Gifts respects punishers ----

    /** Line 1295-3093, Tainted Pact in hand, over a singleton library of {@code names} and Thassa's Oracle, one short
     *  with Oracle its completer, a creature: Intuition piles Oracle and two fillers. */
    private void pactLibrary(final String... names) {
        start(PACT_ORACLE);
        addCardToZone(PACT, ai, ZoneType.Hand);
        library(names);
        library(ORACLE);
        assertTrue(t.singletonLibrary());
        assertEquals(ids(t.oneShort()), List.of("1295-3093"));
        assertEquals(ComboFetch.completers(ai), List.of(ORACLE));
    }

    @Test
    public void fillersSpareFastMana() {
        // Evan, 2026-10-24: the two cards the opponent bins are the cheapest noncreature, nonland non-pieces that are
        // no mana source. Chrome Mox and Lotus Petal (0, mana abilities) and Dark Ritual (1, a Mana spell) are passed
        // over for Brainstorm and Ponder (1 each, ties by name); the old rule piled Chrome Mox and Lotus Petal
        pactLibrary("Chrome Mox", "Lotus Petal", "Dark Ritual", "Brainstorm", "Ponder");

        final List<Card> pile = pile(spell(INTUITION));
        assertEquals(names(pile), List.of(ORACLE, "Brainstorm", "Ponder"));
        assertSame(pile.get(0), t.find(ORACLE, ZoneType.Library));
    }

    @Test
    public void fillersFallBackWhenOnlyManaRemains() {
        // fewer than two that are no mana source: the old rule, the two cheapest noncreature, nonland non-pieces,
        // mana sources included (Chrome Mox and Lotus Petal, 0 each)
        pactLibrary("Chrome Mox", "Lotus Petal", "Dark Ritual");
        assertEquals(names(pile(spell(INTUITION))), List.of(ORACLE, "Chrome Mox", "Lotus Petal"));

        // one that is no mana source is still fewer than two: the old rule whole, not Brainstorm and a mana source
        pactLibrary("Chrome Mox", "Lotus Petal", "Dark Ritual", "Brainstorm");
        assertEquals(names(pile(spell(INTUITION))), List.of(ORACLE, "Chrome Mox", "Lotus Petal"));
    }

    @Test
    public void fillersSpareEveryNamedKindOfManaSource() {
        // beyond the plan's tests: Lion's Eye Diamond (0) and Mana Vault (1) have mana abilities, Cabal Ritual (2) is
        // a Mana spell, and Energy Tap (1) a Tap spell whose sub-ability adds the mana; the old rule piled Lion's Eye
        // Diamond and Brainstorm
        pactLibrary("Lion's Eye Diamond", "Mana Vault", "Cabal Ritual", "Energy Tap", "Ponder", "Brainstorm");
        assertEquals(names(pile(spell(INTUITION))), List.of(ORACLE, "Brainstorm", "Ponder"));
    }

    @Test
    public void giftsFloorRespectsSearchPunishers() {
        // follow-up F4: three completers no longer force Gifts Ungiven into a punished search. Ob Nixilis, Unshackled
        // (a creature and 10 life), River Song (damage for each search) and Psychogenic Probe (2 at the shuffle) each
        // make the stock floor refuse (searchPunished), and the combo floor now yields to it
        final List<ComboLines.Line> held = lines(ORACLE_CONSULTATION, LAB_CONSULTATION, DOOM_CONSULTATION);
        for (final String punisher : List.of("Ob Nixilis, Unshackled", "River Song", "Psychogenic Probe")) {
            completerBoard(true, OTHERS.size());
            final SpellAbility gifts = spell(GIFTS);
            assertTrue(SpecialCardAi.GiftsUngiven.worthSearching(ai, gifts), "before " + punisher);
            addCard(punisher, opp).setSickness(false);
            assertTrue(ComboFetch.giftsReady(ai), punisher);
            assertFalse(SpecialCardAi.GiftsUngiven.worthSearching(ai, gifts), punisher);
            assertFalse(assertStockParity(() -> SpecialCardAi.GiftsUngiven.worthSearching(ai, gifts), held),
                    punisher);
        }
    }
}
