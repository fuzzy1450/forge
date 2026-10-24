package forge.ai.combo;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotEquals;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertNotSame;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertSame;
import static org.testng.Assert.assertTrue;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import org.testng.annotations.Test;

import forge.StaticData;
import forge.ai.AITest;
import forge.ai.AiController;
import forge.ai.AiPlayDecision;
import forge.ai.LobbyPlayerAi;
import forge.ai.PlayerControllerAi;
import forge.ai.SpecialCardAi;
import forge.game.Game;
import forge.game.GameEndReason;
import forge.game.ability.AbilityKey;
import forge.game.ability.AbilityUtils;
import forge.game.card.Card;
import forge.game.phase.PhaseHandler;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.spellability.SpellAbilityStackInstance;
import forge.game.trigger.Trigger;
import forge.game.trigger.TriggerType;
import forge.game.zone.ZoneType;
import forge.item.PaperCard;
import forge.util.MyRandom;

/**
 * Demonic Consultation as a tutor (spec 2026-10-22-combo-aware-ai-core-design.md section 6.3): cast from our hand for
 * a line one piece short that does not hold Consultation itself, whose missing piece is in a library of at least
 * {@link ComboFetch#CONSULT_TUTOR_MIN_LIBRARY} cards, while no live line is assembled already and our mana pays
 * Consultation now, its resolution names that piece; the namer re-checks the intent
 * there, and names a card absent from our library (the stock namer, the emptier's) once our Thassa's Oracle or Fblthp
 * trigger is on the stack or the piece has left the library. Unless a test says otherwise the AI plays the Default
 * profile in its own MAIN1, with Tainted Pact and Demonic Consultation in hand, Island, Island, Swamp and Swamp in
 * play, and the plan's library: the first 80 unique card names that are neither lands nor piece-1 cards, plus
 * Thassa's Oracle after the first forty.
 */
public class ComboConsultTutorAiTest extends AITest {
    private static final String WIN = "F|2|S|Win the game";
    private static final String EXILE = "F|88|H|Exile your library";
    private static final String ORACLE_PACT = "V|1295-3093|4|{1}{U}{U}{B}|2|H:Tainted Pact|H:Thassa's Oracle";
    private static final String ORACLE_CONSULTATION = "V|742-1295|3|{U}{U}{B}|2,88|H:Demonic Consultation|H:Thassa's Oracle";
    private static final String LAB_CONSULTATION = "V|742-2836|1|{B}|2,88|H:Demonic Consultation|B:Laboratory Maniac";
    private static final String LAB_PACT = "V|2836-3093|2|{1}{B}|2|H:Tainted Pact|B:Laboratory Maniac";

    private static final String ORACLE = "Thassa's Oracle";
    private static final String CONSULTATION = "Demonic Consultation";
    private static final String PACT = "Tainted Pact";
    private static final String LAB = "Laboratory Maniac";
    private static final String JACE = "Jace, Wielder of Mysteries";
    private static final String DOOM = "Doctor Doom, Unrivaled";
    private static final String FBLTHP = "Fblthp, Impossibly Lost";

    /** Piece 1's cards, never a filler. */
    private static final List<String> PIECE_ONE = List.of(ORACLE, CONSULTATION, PACT, LAB, JACE, DOOM, FBLTHP);
    /** The plan's library: 80 fillers and Thassa's Oracle, 81 cards. */
    private static final int FILLERS = 80;
    /** The main-loop steps a bounded play may take (LoopProgressTest's playOut: bounded, no timeOut). */
    private static final int MAX_STEPS = 2_000;

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

    /** A two-player game whose AI (player 1, on the Default profile, in its own MAIN1) tracks {@code rows}; no card
     *  placed yet. */
    private void start(final String... rows) {
        game = initAndCreateGame();
        ai = game.getPlayers().get(1);
        opp = game.getPlayers().get(0);
        ((LobbyPlayerAi) ai.getLobbyPlayer()).setAiProfile("Default");
        t = aiOf(ai).getComboTracker();
        t.useLines(lines(rows));
    }

    private void lands(final String... names) {
        for (final String name : names) {
            addCard(name, ai);
        }
    }

    private Card hand(final String name) {
        return addCardToZone(name, ai, ZoneType.Hand);
    }

    /** The first {@code n} unique card names (StaticData's getUniqueCards, in its name order) that are neither lands
     *  nor piece-1 cards. */
    private static List<String> fillers(final int n) {
        final List<String> out = new ArrayList<>(n);
        for (final PaperCard pc : StaticData.instance().getCommonCards().getUniqueCards()) {
            if (out.size() == n) {
                break;
            }
            if (!pc.getRules().getType().isLand() && !PIECE_ONE.contains(pc.getName())) {
                out.add(pc.getName());
            }
        }
        assertEquals(out.size(), n);
        return out;
    }

    /** Our library: the first {@code n} fillers, with {@code piece} after the first half of them. */
    private void library(final int n, final String piece) {
        final List<String> names = fillers(n);
        for (int i = 0; i < n; i++) {
            if (i == n / 2) {
                addCardToZone(piece, ai, ZoneType.Library);
            }
            addCardToZone(names.get(i), ai, ZoneType.Library);
        }
    }

    /** The plan's board for {@code rows}, with {@code n} fillers: Tainted Pact and Demonic Consultation in hand, Island,
     *  Island, Swamp and Swamp, and Thassa's Oracle among the fillers; returns Consultation. */
    private Card board(final int n, final String... rows) {
        start(rows);
        hand(PACT);
        final Card consultation = hand(CONSULTATION);
        lands("Island", "Island", "Swamp", "Swamp");
        library(n, ORACLE);
        assertEquals(ai.getCardsIn(ZoneType.Library).size(), n + 1);
        return consultation;
    }

    /** {@code c}'s first spell, with its owner as its activator. */
    private static SpellAbility castOf(final Card c) {
        final SpellAbility sa = c.getFirstSpellAbility();
        sa.setActivatingPlayer(c.getOwner());
        return sa;
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

    /** No card of our library bears {@code name}, as Forge or the table names it. */
    private void assertNotInLibrary(final String name) {
        for (final Card c : ai.getCardsIn(ZoneType.Library)) {
            assertNotEquals(c.getName(), name);
            assertNotEquals(ComboTracker.nameOf(c), name);
        }
    }

    /** {@code c} enters the battlefield and its entering trigger goes on the stack (the move holds the trigger as
     *  waiting; the state check runs it): the stack's top, that trigger. */
    private SpellAbilityStackInstance enters(final Card c) {
        game.getAction().moveTo(ZoneType.Battlefield, c, null, AbilityKey.newMap());
        game.getAction().checkStateEffects(true);
        game.getStack().addAllTriggeredAbilitiesToStack();
        final SpellAbilityStackInstance top = game.getStack().peek();
        assertTrue(top.isTrigger());
        assertEquals(ComboTracker.nameOf(top.getSourceCard()), ComboTracker.nameOf(c));
        return top;
    }

    /** Fblthp's combat-damage trigger as WeddingRingFloorAiTest builds an ETB: the ability copied for us, its trigger
     *  set. */
    private SpellAbility fblthpTrigger(final Card fblthp) {
        for (final Trigger tr : fblthp.getTriggers()) {
            if (tr.getMode() == TriggerType.DamageAll) {
                final SpellAbility sa = tr.ensureAbility().copy(ai);
                sa.setTrigger(tr);
                return sa;
            }
        }
        throw new AssertionError("no combat-damage trigger on " + fblthp);
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

    /** AITest's playUntilPhase, bounded: at most MAX_STEPS steps, stopping at the game's end or at {@code phase}. */
    private void playUntilPhase(final PhaseType phase) {
        int steps = 0;
        do {
            game.getPhaseHandler().mainLoopStep();
        } while (!game.isGameOver() && !game.getPhaseHandler().is(phase) && ++steps < MAX_STEPS);
    }

    @Test
    public void tutorsOracleForAPactLine() {
        final Card consultation = board(FILLERS, ORACLE_PACT);
        assertTrue(t.singletonLibrary());
        assertEquals(ids(t.oneShort()), List.of("1295-3093"));
        assertEquals(t.missing(t.oneShort().get(0)), List.of(ORACLE));

        final SpellAbility sa = castOf(consultation);
        assertEquals(SpecialCardAi.DemonicConsultation.consider(ai, sa).decision(), AiPlayDecision.WillPlay);
        assertEquals(named(sa), ORACLE);
    }

    @Test
    public void smallLibraryNoTutor() {
        final Card consultation = board(73, ORACLE_PACT);
        assertEquals(ai.getCardsIn(ZoneType.Library).size(), 74);
        assertEquals(ids(t.oneShort()), List.of("1295-3093"));

        final SpellAbility sa = castOf(consultation);
        assertEquals(consider(sa), AiPlayDecision.CantPlayAi);
        // and nothing recorded: the stock name
        assertEquals(named(sa), stockName());
    }

    @Test
    public void consultationsOwnLineIsNoTutor() {
        final Card consultation = board(FILLERS, ORACLE_CONSULTATION);
        assertNotNull(t.find(ORACLE, ZoneType.Library));
        assertEquals(ids(t.oneShort()), List.of("742-1295"));

        assertEquals(consider(castOf(consultation)), AiPlayDecision.CantPlayAi);
    }

    @Test
    public void staleIntentNamesAnAbsentCard() {
        final Card consultation = board(FILLERS, ORACLE_PACT);
        final SpellAbility sa = castOf(consultation);
        // the intent recorded at the cast decision
        assertEquals(consider(sa), AiPlayDecision.WillPlay);

        // then a second Oracle of ours enters, its trigger on our stack: Consultation now empties for it
        enters(hand(ORACLE));
        assertNotNull(t.find(ORACLE, ZoneType.Library), "the library's Oracle stays");
        final String name = named(sa);
        assertNotEquals(name, ORACLE);
        assertNotInLibrary(name);
        assertEquals(name, stockName());
    }

    @Test
    public void noLineNamerUnchanged() {
        final Card consultation = board(FILLERS);
        assertEquals(t.deckLines(), List.of());
        final SpellAbility sa = castOf(consultation);
        assertEquals(consider(sa), AiPlayDecision.CantPlayAi);

        final String name = named(sa);
        assertEquals(name, stockName());
        assertNotInLibrary(name);
        // lines the deck does not hold: the same name, the same draws
        assertEquals(assertStockParity(() -> named(castOf(consultation)), lines(LAB_CONSULTATION)), name);
    }

    // ---- beyond the plan's five ----

    @Test
    public void seventyFiveCardsAreDeepEnough() {
        assertEquals(ComboFetch.CONSULT_TUTOR_MIN_LIBRARY, 75);
        final Card consultation = board(74, ORACLE_PACT);
        assertEquals(ai.getCardsIn(ZoneType.Library).size(), ComboFetch.CONSULT_TUTOR_MIN_LIBRARY);

        final SpellAbility sa = castOf(consultation);
        assertNotNull(ComboFetch.consultTutor(ai, sa));
        assertEquals(ComboFetch.consultTutor(ai, sa).decision(), AiPlayDecision.WillPlay);
        assertEquals(ComboFetch.consultTutor(ai, sa).rating(), 100);
        assertEquals(named(sa), ORACLE);
    }

    @Test
    public void theCastDecisionPassesTheLibraryFloor() {
        // the whole cast decision, AiController's vetoes and the own-library floor (77C) included: a tutor's six and
        // one leave a deep library well above the floor
        final Card consultation = board(FILLERS, ORACLE_PACT);
        final SpellAbility sa = castOf(consultation);
        assertEquals(aiOf(ai).canPlaySa(sa), AiPlayDecision.WillPlay);
        assertEquals(named(sa), ORACLE);
    }

    @Test
    public void theIntentIsTakenOnce() {
        final Card consultation = board(FILLERS, ORACLE_PACT);
        final SpellAbility sa = castOf(consultation);
        assertEquals(consider(sa), AiPlayDecision.WillPlay);
        assertEquals(named(sa), ORACLE);
        // its resolution took it: a second naming is the stock one
        assertEquals(named(sa), stockName());
        assertNull(t.takeConsultIntent(consultation.getId()));
    }

    @Test
    public void theIntentIsPerConsultation() {
        final Card consultation = board(FILLERS, ORACLE_PACT);
        final Card other = hand(CONSULTATION);
        final SpellAbility sa = castOf(consultation);
        assertEquals(consider(sa), AiPlayDecision.WillPlay);
        // the other Consultation's cast recorded nothing
        assertEquals(named(castOf(other)), stockName());
        assertEquals(named(sa), ORACLE);
    }

    @Test
    public void theTrackerKeepsOneIntentPerHost() {
        start(ORACLE_PACT);
        assertNull(t.takeConsultIntent(7));
        t.setConsultIntent(7, ORACLE);
        t.setConsultIntent(8, PACT);
        t.setConsultIntent(7, LAB);
        assertEquals(t.takeConsultIntent(7), LAB);
        assertNull(t.takeConsultIntent(7));
        assertEquals(t.takeConsultIntent(8), PACT);
        assertNull(t.takeConsultIntent(8));
    }

    @Test
    public void aPieceThatLeftTheLibraryIsNotNamed() {
        final Card consultation = board(FILLERS, ORACLE_PACT);
        final SpellAbility sa = castOf(consultation);
        assertEquals(consider(sa), AiPlayDecision.WillPlay);

        // the Oracle drawn before the resolution
        game.getAction().moveTo(ZoneType.Hand, t.find(ORACLE, ZoneType.Library), null, AbilityKey.newMap());
        final String name = named(sa);
        assertNotEquals(name, ORACLE);
        assertNotInLibrary(name);
        assertEquals(name, stockName());
    }

    @Test
    public void anFblthpTriggerOnOurStackIsTheEmptiersWindow() {
        final Card consultation = board(FILLERS, ORACLE_PACT);
        final SpellAbility sa = castOf(consultation);
        assertEquals(consider(sa), AiPlayDecision.WillPlay);

        final Card fblthp = addCard(FBLTHP, ai);
        game.getStack().add(fblthpTrigger(fblthp));
        assertTrue(game.getStack().peekAbility().isTrigger());
        final String name = named(sa);
        assertNotEquals(name, ORACLE);
        assertNotInLibrary(name);
        assertEquals(name, stockName());
    }

    @Test
    public void anOpponentsOracleTriggerKeepsTheIntent() {
        // only a trigger of ours makes Consultation the emptier
        final Card consultation = board(FILLERS, ORACLE_PACT);
        final SpellAbility sa = castOf(consultation);
        assertEquals(consider(sa), AiPlayDecision.WillPlay);

        final Card theirs = addCardToZone(ORACLE, opp, ZoneType.Hand);
        game.getAction().moveTo(ZoneType.Battlefield, theirs, null, AbilityKey.newMap());
        game.getAction().checkStateEffects(true);
        game.getStack().addAllTriggeredAbilitiesToStack();
        assertTrue(game.getStack().peek().isTrigger());
        assertEquals(game.getStack().peek().getActivatingPlayer(), opp);
        assertEquals(named(sa), ORACLE);
    }

    @Test
    public void aWindowDropsAnEarlierIntent() {
        // the deck also holds Laboratory Maniac's Consultation line, Lab in the library
        start(ORACLE_PACT, LAB_CONSULTATION);
        hand(PACT);
        final Card consultation = hand(CONSULTATION);
        lands("Island", "Island", "Swamp", "Swamp");
        library(FILLERS, ORACLE);
        final Card lab = addCardToZone(LAB, ai, ZoneType.Library);
        // both cost 4 still to cast (Pact 2 and Oracle 2, Consultation 1 and Lab 3): the Oracle line first
        assertEquals(ids(t.oneShort()), List.of("1295-3093", "742-2836"));

        // a tutor for the Pact line (the Lab line holds Consultation itself)
        final SpellAbility sa = castOf(consultation);
        assertEquals(consider(sa), AiPlayDecision.WillPlay);
        assertEquals(t.takeConsultIntent(consultation.getId()), ORACLE);
        t.setConsultIntent(consultation.getId(), ORACLE);

        // later Lab is in play and it is the opponent's end step, our turn next: Consultation is approved again, as
        // Lab's emptier, and its resolution must exile the whole library
        game.getAction().moveTo(ZoneType.Battlefield, lab, null, AbilityKey.newMap());
        game.getPhaseHandler().devModeSet(PhaseType.END_OF_TURN, opp);
        assertEquals(game.getPhaseHandler().getNextTurn(), ai);
        assertEquals(ids(t.executable()), List.of("1295-3093", "742-2836"));
        assertEquals(consider(sa), AiPlayDecision.WillPlay);
        final String name = named(sa);
        assertNotInLibrary(name);
        assertEquals(name, stockName());
    }

    @Test
    public void theMissingEmptierIsNamedToo() {
        // Oracle in hand, Tainted Pact the piece in the library
        start(ORACLE_PACT);
        hand(ORACLE);
        final Card consultation = hand(CONSULTATION);
        lands("Island", "Island", "Swamp", "Swamp");
        library(FILLERS, PACT);
        assertEquals(ids(t.oneShort()), List.of("1295-3093"));
        assertEquals(t.missing(t.oneShort().get(0)), List.of(PACT));

        final SpellAbility sa = castOf(consultation);
        assertEquals(consider(sa), AiPlayDecision.WillPlay);
        assertEquals(named(sa), PACT);
    }

    @Test
    public void theTutorTakesTheFirstLineByRank() {
        // two Pact lines one short, Laboratory Maniac's listed first (and first by the table's mana value, 2 against
        // 4), Oracle's ranked first by the mana still to cast (Pact 2 and Oracle 2, against Pact 2 and Lab 3: final
        // review, round 1); a fifth land, so Lab's {2}{U} and Pact's {1}{B} leave its line no NO_MANA
        start(LAB_PACT, ORACLE_PACT);
        hand(PACT);
        final Card consultation = hand(CONSULTATION);
        lands("Island", "Island", "Swamp", "Swamp", "Swamp");
        library(FILLERS, ORACLE);
        addCardToZone(LAB, ai, ZoneType.Library);
        assertEquals(ids(t.deckLines()), List.of("2836-3093", "1295-3093"));
        assertEquals(ids(t.oneShort()), List.of("1295-3093", "2836-3093"));

        final SpellAbility sa = castOf(consultation);
        assertEquals(consider(sa), AiPlayDecision.WillPlay);
        assertEquals(named(sa), ORACLE);
    }

    @Test
    public void aLineTwoShortIsNoTutor() {
        // Oracle and Tainted Pact both in the library
        start(ORACLE_PACT);
        final Card consultation = hand(CONSULTATION);
        lands("Island", "Island", "Swamp", "Swamp");
        library(FILLERS, ORACLE);
        addCardToZone(PACT, ai, ZoneType.Library);
        assertEquals(ids(t.live()), List.of("1295-3093"));
        assertEquals(t.oneShort(), List.of());

        assertEquals(consider(castOf(consultation)), AiPlayDecision.CantPlayAi);
    }

    @Test
    public void noManaForTheLineIsNoTutor() {
        // the plan's own board, a Swamp alone: Oracle's two and Pact's two outrun one land, so the line is not live
        start(ORACLE_PACT);
        hand(PACT);
        final Card consultation = hand(CONSULTATION);
        lands("Swamp");
        library(FILLERS, ORACLE);
        assertEquals(ComboSignals.abandon(ai, t.deckLines().get(0), t), ComboSignals.Signal.NO_MANA);
        final SpellAbility sa = castOf(consultation);
        assertEquals(consider(sa), AiPlayDecision.CantPlayAi);

        // three more lands: the line is live, one short, and Consultation tutors for it
        lands("Island", "Island", "Swamp");
        assertEquals(ids(t.oneShort()), List.of("1295-3093"));
        assertEquals(consider(sa), AiPlayDecision.WillPlay);
    }

    @Test
    public void dangerIsNoTutor() {
        final Card consultation = board(FILLERS, ORACLE_PACT);
        ai.setLife(2, null);
        addCard("Hill Giant", opp).setSickness(false);
        assertEquals(ComboSignals.abandon(ai, t.deckLines().get(0), t), ComboSignals.Signal.DANGER);
        assertEquals(t.oneShort(), List.of());

        assertEquals(consider(castOf(consultation)), AiPlayDecision.CantPlayAi);
    }

    @Test
    public void onlyOurOwnHandCastTutors() {
        final Card consultation = board(FILLERS, ORACLE_PACT);
        // a Consultation of ours in the graveyard, cast by some effect
        final Card buried = addCardToZone(CONSULTATION, ai, ZoneType.Graveyard);
        assertNull(ComboFetch.consultTutor(ai, castOf(buried)));
        assertEquals(named(castOf(buried)), stockName());
        // the opponent's Consultation in their hand
        final Card theirs = addCardToZone(CONSULTATION, opp, ZoneType.Hand);
        assertNull(ComboFetch.consultTutor(ai, castOf(theirs)));
        // ours in hand
        assertNotNull(ComboFetch.consultTutor(ai, castOf(consultation)));
    }

    @Test
    public void theTutorThenOracleThenPactWins() {
        // a fifth land: Consultation's {B}, then Oracle's {U}{U} and Pact's {1}{B}
        start(ORACLE_PACT);
        hand(PACT);
        hand(CONSULTATION);
        lands("Island", "Island", "Swamp", "Swamp", "Swamp");
        library(FILLERS, ORACLE);
        assertEquals(ids(t.oneShort()), List.of("1295-3093"));

        playUntilPhase(PhaseType.END_OF_TURN);
        assertTrue(game.isGameOver(), "the game went on: " + gameStateToString(game));
        assertTrue(ai.hasWon());
        assertEquals(game.getOutcome().getWinCondition(), GameEndReason.WinsGameSpellEffect);
        final PhaseHandler ph = game.getPhaseHandler();
        assertEquals(ph.getPlayerTurn(), ai);
        assertTrue(ph.getPhase().isBefore(PhaseType.END_OF_TURN), "won at " + ph.getPhase());
        // Consultation fetched the Oracle (its six and its reveals exiled), Pact exiled the rest, declining each
        assertNotNull(t.find(ORACLE, ZoneType.Battlefield));
        assertTrue(ai.getCardsIn(ZoneType.Library).isEmpty());
        assertTrue(ai.getCardsIn(ZoneType.Hand).isEmpty(), "a card was kept: " + ai.getCardsIn(ZoneType.Hand));
        assertEquals(ai.getCardsIn(ZoneType.Exile).size(), FILLERS);
    }

    // ---- review round 1 ----

    /** The cast entry the playable list uses (AiController.canPlayAndPayFor, private): canPlayFromHost, then
     *  canPlayAndPayForFace, whose canPayCost test payment draws MyRandom per source it tries. */
    private AiPlayDecision canPlayAndPayFor(final SpellAbility sa) {
        try {
            final Method m = AiController.class.getDeclaredMethod("canPlayAndPayFor", SpellAbility.class);
            m.setAccessible(true);
            return (AiPlayDecision) m.invoke(aiOf(ai), sa);
        } catch (final ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    /** Lines 1295-3093 and 742-1295, Thassa's Oracle and Demonic Consultation in hand, Island, Island and Swamp in
     *  play, Tainted Pact among the 80 fillers and a Swamp below them (a land left to play, as a deck's library holds:
     *  Oracle's two and Pact's two fit next turn's four); returns Consultation. */
    private Card assembledBoard() {
        start(ORACLE_PACT, ORACLE_CONSULTATION);
        hand(ORACLE);
        final Card consultation = hand(CONSULTATION);
        lands("Island", "Island", "Swamp");
        library(FILLERS, PACT);
        addCardToZone("Swamp", ai, ZoneType.Library);
        assertTrue(t.singletonLibrary());
        assertEquals(ids(t.live()), List.of("1295-3093", "742-1295"));
        assertEquals(t.missing(t.live().get(1)), List.of(), "Oracle and Consultation are both in hand");
        assertEquals(ids(t.oneShort()), List.of("1295-3093"));
        assertEquals(t.missing(t.oneShort().get(0)), List.of(PACT));
        return consultation;
    }

    @Test
    public void anAssembledLineSpendsNoConsultation() {
        // in our upkeep, long before the Main 1 go-off: Consultation tutoring Pact would spend 742-1295's emptier
        final Card consultation = assembledBoard();
        game.getPhaseHandler().devModeSet(PhaseType.UPKEEP, ai);
        final SpellAbility sa = castOf(consultation);
        assertEquals(consider(sa), AiPlayDecision.CantPlayAi);
        assertEquals(named(sa), stockName(), "an intent was recorded");

        // the same board without the assembled line: Consultation tutors Pact (theMissingEmptierIsNamedToo)
        t.useLines(lines(ORACLE_PACT));
        assertEquals(consider(sa), AiPlayDecision.WillPlay);
        assertEquals(named(sa), PACT);
    }

    @Test
    public void anAssembledLineOfTheOtherEmptierSpendsNoConsultation() {
        // Oracle and Pact in hand (1295-3093 assembled, its four on four lands), Laboratory Maniac in the library
        // (2836-3093 one short): the {B} a tutor spends in our upkeep is the Main 1 go-off's
        start(ORACLE_PACT, LAB_PACT);
        hand(ORACLE);
        hand(PACT);
        final Card consultation = hand(CONSULTATION);
        lands("Island", "Island", "Swamp", "Swamp");
        library(FILLERS, LAB);
        addCardToZone("Swamp", ai, ZoneType.Library);
        assertTrue(t.singletonLibrary());
        assertEquals(ids(t.live()), List.of("1295-3093", "2836-3093"));
        assertEquals(t.missing(t.live().get(0)), List.of());
        assertEquals(ids(t.oneShort()), List.of("2836-3093"));
        assertEquals(t.missing(t.oneShort().get(0)), List.of(LAB));

        game.getPhaseHandler().devModeSet(PhaseType.UPKEEP, ai);
        final SpellAbility sa = castOf(consultation);
        assertEquals(consider(sa), AiPlayDecision.CantPlayAi);
        assertEquals(named(sa), stockName(), "an intent was recorded");

        // without the assembled line: Consultation tutors Laboratory Maniac
        t.useLines(lines(LAB_PACT));
        assertEquals(consider(sa), AiPlayDecision.WillPlay);
        assertEquals(named(sa), LAB);
    }

    @Test
    public void aDeniedAssembledLineLeavesTheTutor() {
        // the assembled line must be live: Torpor Orb stops Oracle's trigger, so 742-1295 is dead (section 6.5: its
        // pieces are ordinary cards) and Consultation may tutor Laboratory Maniac for 2836-3093
        start(ORACLE_CONSULTATION, LAB_PACT);
        hand(ORACLE);
        hand(PACT);
        final Card consultation = hand(CONSULTATION);
        lands("Island", "Island", "Swamp", "Swamp", "Swamp");
        library(FILLERS, LAB);
        addCard("Torpor Orb", opp);
        final ComboLines.Line assembled = t.deckLines().get(0);
        assertEquals(assembled.variant().id(), "742-1295");
        assertEquals(t.missing(assembled), List.of());
        assertEquals(ComboSignals.abandon(ai, assembled, t), ComboSignals.Signal.DENIED);
        assertEquals(ids(t.live()), List.of("2836-3093"));
        assertEquals(ids(t.oneShort()), List.of("2836-3093"));

        final SpellAbility sa = castOf(consultation);
        assertEquals(consider(sa), AiPlayDecision.WillPlay);
        assertEquals(named(sa), LAB);
    }

    @Test
    public void anAssembledLineGoesOffFromUpkeep() {
        // end to end from our upkeep: Consultation waits for Oracle's trigger, and Oracle and Consultation win on
        // Island, Island and Swamp this turn (a tutor in the upkeep would leave two Islands for Oracle and Pact's four)
        assembledBoard();
        game.getPhaseHandler().devModeSet(PhaseType.UPKEEP, ai);
        game.getPhaseHandler().onStackResolved();
        playUntilPhase(PhaseType.END_OF_TURN);
        assertTrue(game.isGameOver(), "the game went on: " + gameStateToString(game));
        assertTrue(ai.hasWon());
        assertEquals(game.getOutcome().getWinCondition(), GameEndReason.WinsGameSpellEffect);
        final PhaseHandler ph = game.getPhaseHandler();
        assertEquals(ph.getPlayerTurn(), ai);
        assertTrue(ph.getPhase().isBefore(PhaseType.END_OF_TURN), "won at " + ph.getPhase());
        // Consultation exiled the whole library for the trigger: Pact was never fetched
        assertNotNull(t.find(ORACLE, ZoneType.Battlefield));
        assertTrue(ai.getCardsIn(ZoneType.Library).isEmpty());
        assertEquals(ai.getCardsIn(ZoneType.Exile).size(), FILLERS + 2);
        assertNotNull(t.find(PACT, ZoneType.Exile));
    }

    @Test
    public void noBlackForConsultationIsNoTutor() {
        // four Islands: the line is live (Oracle's two and Pact's two on four lands) but nothing makes Consultation's
        // {B}, so no tutor is approved, and the cast's canPayCost test payment never runs
        start(ORACLE_PACT);
        hand(PACT);
        final Card consultation = hand(CONSULTATION);
        lands("Island", "Island", "Island", "Island");
        library(FILLERS, ORACLE);
        assertEquals(ids(t.oneShort()), List.of("1295-3093"));

        final SpellAbility sa = castOf(consultation);
        assertEquals(consider(sa), AiPlayDecision.CantPlayAi);
        assertEquals(named(sa), stockName(), "an intent was recorded");
        // the whole cast: the stock answer and the stock draws (none of canPayCost's isManaSourceReserved rolls)
        assertEquals(assertStockParity(() -> canPlayAndPayFor(castOf(consultation)), lines(ORACLE_PACT)),
                AiPlayDecision.CantPlayAi);
    }

    @Test
    public void tappedSwampsAreNoBlack() {
        // Island, Island, Swamp and Swamp, the Swamps tapped: next turn's mana counts them, so the line is live, but
        // their black is spent now
        start(ORACLE_PACT);
        hand(PACT);
        final Card consultation = hand(CONSULTATION);
        lands("Island", "Island");
        final Card swamp = addCard("Swamp", ai);
        addCard("Swamp", ai).setTapped(true);
        swamp.setTapped(true);
        library(FILLERS, ORACLE);
        assertEquals(ids(t.oneShort()), List.of("1295-3093"));

        final SpellAbility sa = castOf(consultation);
        assertEquals(consider(sa), AiPlayDecision.CantPlayAi);
        assertEquals(named(sa), stockName(), "an intent was recorded");
        assertEquals(assertStockParity(() -> canPlayAndPayFor(castOf(consultation)), lines(ORACLE_PACT)),
                AiPlayDecision.CantPlayAi);

        // one Swamp untapped pays it: the tutor is approved, and the cast goes on to be paid
        swamp.setTapped(false);
        assertEquals(consider(sa), AiPlayDecision.WillPlay);
        assertEquals(named(sa), ORACLE);
        assertEquals(canPlayAndPayFor(castOf(consultation)), AiPlayDecision.WillPlay);
    }

    // ---- follow-up F4: known tops ----

    /** Vampiric Tutor resolved from our hand (AbilityUtils.resolve, its {B} unpaid): our own search of our library,
     *  decided by us, the shuffle, the card on top, and 2 life. */
    private void vampiricTutor() {
        AbilityUtils.resolve(castOf(hand("Vampiric Tutor")));
    }

    @Test
    public void knownTopBlocksTheConsultationTutor() {
        // the plan's Pact board, from which Consultation tutors Oracle (tutorsOracleForAPactLine). Vampiric Tutor's
        // resolution takes Oracle and puts it on top (spec section 6.3, known tops: Evan, 2026-10-24), where
        // Consultation's first six would exile it, and the whole library with it
        final Card consultation = board(FILLERS, ORACLE_PACT);
        vampiricTutor();
        final Card oracle = t.find(ORACLE, ZoneType.Library);
        assertSame(ai.getZone(ZoneType.Library).get(0), oracle);
        assertEquals(ids(t.oneShort()), List.of("1295-3093"));

        final SpellAbility sa = castOf(consultation);
        assertEquals(consider(sa), AiPlayDecision.CantPlayAi);
        assertEquals(t.knownTops(), Set.of(oracle.getId()));
        assertEquals(named(sa), stockName(), "an intent was recorded");
    }

    @Test
    public void knownTopEndsWhenTheCardLeavesTheLibrary() {
        board(FILLERS, ORACLE_PACT);
        vampiricTutor();
        final Card oracle = t.find(ORACLE, ZoneType.Library);
        assertEquals(t.knownTops(), Set.of(oracle.getId()));

        // Oracle drawn: it is no longer in our library, so no longer a known top
        ai.drawCard();
        assertEquals(t.where(ORACLE), ComboTracker.Where.HAND);
        assertEquals(t.knownTops(), Set.of());
    }

    // ---- beyond the plan's tests ----

    @Test
    public void aKnownTopThatLeftAndCameBackIsForgotten() {
        // the memory ends when the card leaves the library, even when it is back before anything reads the memory:
        // the card that comes back is a new object of the same id (GameAction.changeZone's copy), here on the bottom
        final Card consultation = board(FILLERS, ORACLE_PACT);
        vampiricTutor();
        final Card oracle = t.find(ORACLE, ZoneType.Library);
        final Card drawn = game.getAction().moveTo(ZoneType.Hand, oracle, null, AbilityKey.newMap());
        game.getAction().moveToBottomOfLibrary(drawn, null);
        final Card back = t.find(ORACLE, ZoneType.Library);
        assertEquals(back.getId(), oracle.getId());
        assertNotSame(back, oracle);

        // the tutor's own read is the first to see it gone, and tutors Oracle again
        final SpellAbility sa = castOf(consultation);
        assertEquals(consider(sa), AiPlayDecision.WillPlay);
        assertEquals(named(sa), ORACLE);
        assertEquals(t.knownTops(), Set.of());
    }

    @Test
    public void aShuffleKeepsTheKnownTop() {
        // a shuffle is not tracked (Evan, 2026-10-24): the memory outlives it, and a rare missed tutor is the cost
        final Card consultation = board(FILLERS, ORACLE_PACT);
        vampiricTutor();
        final Card oracle = t.find(ORACLE, ZoneType.Library);
        ai.getZone(ZoneType.Library).shuffle();
        assertSame(t.find(ORACLE, ZoneType.Library), oracle);
        assertEquals(t.knownTops(), Set.of(oracle.getId()));
        assertEquals(consider(castOf(consultation)), AiPlayDecision.CantPlayAi);
    }

    @Test
    public void theTutorPassesOverAKnownTopForTheNextLine() {
        // theTutorTakesTheFirstLineByRank's board: two Pact lines one short, Oracle's ranked first. Vampiric Tutor
        // puts Oracle on top, so Consultation names the next line's piece, Laboratory Maniac (the 75-card floor is the
        // whole threshold: no condition on the other live pieces, Evan, 2026-10-24)
        start(LAB_PACT, ORACLE_PACT);
        hand(PACT);
        final Card consultation = hand(CONSULTATION);
        lands("Island", "Island", "Swamp", "Swamp", "Swamp");
        library(FILLERS, ORACLE);
        addCardToZone(LAB, ai, ZoneType.Library);
        assertEquals(ids(t.oneShort()), List.of("1295-3093", "2836-3093"));
        vampiricTutor();
        assertSame(ai.getZone(ZoneType.Library).get(0), t.find(ORACLE, ZoneType.Library));
        assertEquals(ids(t.oneShort()), List.of("1295-3093", "2836-3093"));

        final SpellAbility sa = castOf(consultation);
        assertEquals(consider(sa), AiPlayDecision.WillPlay);
        assertEquals(named(sa), LAB);
    }
}
