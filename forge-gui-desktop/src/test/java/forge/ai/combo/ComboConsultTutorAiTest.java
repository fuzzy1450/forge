package forge.ai.combo;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotEquals;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertTrue;

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
 * {@link ComboFetch#CONSULT_TUTOR_MIN_LIBRARY} cards, its resolution names that piece; the namer re-checks the intent
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
        assertEquals(ids(t.oneShort()), List.of("742-2836", "1295-3093"));

        // a tutor for the Pact line (the cheaper Lab line holds Consultation itself)
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
        // two Pact lines one short, Oracle's (mana value 4) listed first, Laboratory Maniac's (2) ranked first; a fifth
        // land, so Lab's {2}{U} and Pact's {1}{B} leave its line no NO_MANA
        start(ORACLE_PACT, LAB_PACT);
        hand(PACT);
        final Card consultation = hand(CONSULTATION);
        lands("Island", "Island", "Swamp", "Swamp", "Swamp");
        library(FILLERS, ORACLE);
        addCardToZone(LAB, ai, ZoneType.Library);
        assertEquals(ids(t.deckLines()), List.of("1295-3093", "2836-3093"));
        assertEquals(ids(t.oneShort()), List.of("2836-3093", "1295-3093"));

        final SpellAbility sa = castOf(consultation);
        assertEquals(consider(sa), AiPlayDecision.WillPlay);
        assertEquals(named(sa), LAB);
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
}
