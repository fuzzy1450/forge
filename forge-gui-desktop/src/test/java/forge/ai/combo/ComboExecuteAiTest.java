package forge.ai.combo;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertNull;
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
import forge.ai.AiPlayDecision;
import forge.ai.ComputerUtilCard;
import forge.ai.LobbyPlayerAi;
import forge.ai.PlayerControllerAi;
import forge.ai.SpecialCardAi;
import forge.ai.SpellAbilityAi;
import forge.card.MagicColor;
import forge.card.mana.ManaCostShard;
import forge.game.Game;
import forge.game.GameEndReason;
import forge.game.ability.AbilityFactory;
import forge.game.ability.AbilityKey;
import forge.game.ability.ApiType;
import forge.game.card.Card;
import forge.game.phase.PhaseHandler;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.spellability.AbilitySub;
import forge.game.spellability.SpellAbility;
import forge.game.spellability.SpellAbilityStackInstance;
import forge.game.trigger.Trigger;
import forge.game.trigger.TriggerType;
import forge.game.zone.ZoneType;
import forge.util.MyRandom;

/**
 * Executing the empty-library family (spec 2026-10-22-combo-aware-ai-core-design.md sections 6.4 and 6.5): Thassa's
 * Oracle goes off with Demonic Consultation or Tainted Pact cast in response to its trigger; Tainted Pact empties only
 * inside a window of its line and declines every card there, and stays the stock cantrip outside its lines; Demonic
 * Consultation empties for Laboratory Maniac and Jace when a draw we control follows, for Doctor Doom when its {T} can
 * be used right after, and for Fblthp in response to Fblthp's own trigger; the draw that finishes a line is played on
 * the emptied library; and the own-library floor exempts an emptier cast inside a window. None of it while we cannot
 * win. Unless a test says otherwise the AI plays the Default profile in its own MAIN1, with no land in hand.
 */
public class ComboExecuteAiTest extends AITest {
    private static final String WIN = "F|2|S|Win the game";
    private static final String EXILE = "F|88|H|Exile your library";
    private static final String ORACLE_CONSULTATION = "V|742-1295|3|{U}{U}{B}|2,88|H:Demonic Consultation|H:Thassa's Oracle";
    private static final String ORACLE_PACT = "V|1295-3093|4|{1}{U}{U}{B}|2|H:Tainted Pact|H:Thassa's Oracle";
    private static final String LAB_PACT = "V|2836-3093|2|{1}{B}|2|H:Tainted Pact|B:Laboratory Maniac";
    private static final String LAB_CONSULTATION = "V|742-2836|1|{B}|2,88|H:Demonic Consultation|B:Laboratory Maniac";
    private static final String JACE_CONSULTATION = "V|742-3096|1|{B}|2,88|H:Demonic Consultation|B:Jace, Wielder of Mysteries";
    private static final String FBLTHP_CONSULTATION = "V|742-8266|1|{B}|2,88|B:Fblthp, Impossibly Lost|H:Demonic Consultation";
    private static final String DOOM_CONSULTATION = "V|x-1|1|{B}|2,88|H:Demonic Consultation|B:Doctor Doom, Unrivaled";

    private static final String ORACLE = "Thassa's Oracle";
    private static final String CONSULTATION = "Demonic Consultation";
    private static final String PACT = "Tainted Pact";
    private static final String LAB = "Laboratory Maniac";
    private static final String JACE = "Jace, Wielder of Mysteries";
    private static final String DOOM = "Doctor Doom, Unrivaled";
    private static final String FBLTHP = "Fblthp, Impossibly Lost";
    private static final String OPT = "Opt";
    private static final String EMERITUS = "Archmage Emeritus";
    private static final String ANGEL = "Platinum Angel";
    private static final String LINVALA = "Linvala, Keeper of Silence";

    /** Fifteen distinct names, none a land and none a piece of piece 1's lines: a library Tainted Pact can empty. */
    private static final List<String> SINGLETON = List.of("Runeclaw Bear", "Grizzly Bears", "Hill Giant", "Craw Wurm",
            "Serra Angel", "Shivan Dragon", "Llanowar Elves", "Counterspell", "Divination", "Coral Merfolk", "Gray Ogre",
            "Wind Drake", "Air Elemental", "Giant Spider", "Merfolk of the Pearl Trident");

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

    private void library(final String... names) {
        for (final String name : names) {
            addCardToZone(name, ai, ZoneType.Library);
        }
    }

    private void singletonLibrary() {
        library(SINGLETON.toArray(new String[0]));
    }

    /** Our card {@code name} on our battlefield, free of summoning sickness. */
    private Card ready(final String name) {
        final Card c = addCard(name, ai);
        c.setSickness(false);
        return c;
    }

    /** {@code c}'s first spell, with its owner as its activator. */
    private static SpellAbility castOf(final Card c) {
        final SpellAbility sa = c.getFirstSpellAbility();
        sa.setActivatingPlayer(c.getOwner());
        return sa;
    }

    /** Demonic Consultation's cast decision (SpecialCardAi.DemonicConsultation, ChooseCardNameAi's route). */
    private AiPlayDecision consider(final Card consultation) {
        return SpecialCardAi.DemonicConsultation.consider(ai, castOf(consultation)).decision();
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

    /** Tainted Pact's DBReturnToHand: the ChangeZone AbilitySub of its RepeatSubAbility chain. */
    private static SpellAbility returnToHand(final Card pact) {
        final SpellAbility spell = castOf(pact);
        for (SpellAbility s = spell.getAdditionalAbility("RepeatSubAbility"); s != null; s = s.getSubAbility()) {
            if (s.getApi() == ApiType.ChangeZone) {
                return s;
            }
        }
        throw new AssertionError("no ChangeZone in Tainted Pact's repeat");
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

    /** Doctor Doom's {T}: draw, lose 1 life, win on an empty library; we activate it. */
    private SpellAbility doomDraw(final Card doom) {
        for (final SpellAbility sa : doom.getSpellAbilities()) {
            if (sa.isActivatedAbility() && sa.getApi() == ApiType.Draw) {
                sa.setActivatingPlayer(ai);
                return sa;
            }
        }
        throw new AssertionError("no draw ability on " + doom);
    }

    /** AITest's playUntilPhase, bounded: at most MAX_STEPS steps, stopping at the game's end or at {@code phase}. */
    private void playUntilPhase(final PhaseType phase) {
        int steps = 0;
        do {
            game.getPhaseHandler().mainLoopStep();
        } while (!game.isGameOver() && !game.getPhaseHandler().is(phase) && ++steps < MAX_STEPS);
    }

    /** The AI won by its own spell or ability, in its own turn, before its end step. */
    private void assertWonThisTurn() {
        assertTrue(game.isGameOver(), "the game went on: " + gameStateToString(game));
        assertTrue(ai.hasWon());
        assertEquals(game.getOutcome().getWinCondition(), GameEndReason.WinsGameSpellEffect);
        final PhaseHandler ph = game.getPhaseHandler();
        assertEquals(ph.getPlayerTurn(), ai);
        assertTrue(ph.getPhase().isBefore(PhaseType.END_OF_TURN), "won at " + ph.getPhase());
    }

    /** The opponent's end step, our turn next. */
    private void opponentsEndStep() {
        game.getPhaseHandler().devModeSet(PhaseType.END_OF_TURN, opp);
        assertEquals(game.getPhaseHandler().getNextTurn(), ai);
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

    // ---- end to end ----

    @Test
    public void oracleThenConsultationWins() {
        start(ORACLE_CONSULTATION);
        hand(ORACLE);
        hand(CONSULTATION);
        lands("Island", "Island", "Swamp");
        fillLibrary(ai, 20);
        assertEquals(ids(t.deckLines()), List.of("742-1295"));

        playUntilPhase(PhaseType.END_OF_TURN);
        assertWonThisTurn();
        assertTrue(ai.getCardsIn(ZoneType.Library).isEmpty());
    }

    @Test
    public void oracleThenPactWins() {
        start(ORACLE_PACT);
        hand(ORACLE);
        hand(PACT);
        lands("Island", "Island", "Swamp", "Swamp");
        singletonLibrary();
        assertEquals(ids(t.live()), List.of("1295-3093"));

        playUntilPhase(PhaseType.END_OF_TURN);
        assertWonThisTurn();
        // every card Pact exiled was declined: none came to our hand, and the library went to exile whole
        assertTrue(ai.getCardsIn(ZoneType.Library).isEmpty());
        assertTrue(ai.getCardsIn(ZoneType.Hand).isEmpty(), "Pact kept a card: " + ai.getCardsIn(ZoneType.Hand));
        assertEquals(ai.getCardsIn(ZoneType.Exile).size(), SINGLETON.size());
    }

    // ---- Tainted Pact ----

    @Test
    public void pactCantripsWithoutALine() {
        // Review Focus 1. No Oracle in the deck: no Pact line, so Pact is the stock cantrip
        start(ORACLE_PACT);
        final Card pact = hand(PACT);
        singletonLibrary();
        lands("Island", "Island", "Swamp", "Swamp");
        assertEquals(t.deckLines(), List.of());
        assertNull(ComboExecute.pactCast(ai, castOf(pact)));
        assertEquals(aiOf(ai).canPlaySa(castOf(pact)), AiPlayDecision.WillPlay);

        // a singleton library and a live Pact line with no window: held
        hand(ORACLE);
        t.useLines(lines(ORACLE_PACT));
        assertEquals(ids(t.live()), List.of("1295-3093"));
        assertEquals(ComboExecute.pactCast(ai, castOf(pact)), Boolean.FALSE);
        assertEquals(aiOf(ai).canPlaySa(castOf(pact)), AiPlayDecision.CantPlayAi);

        // two Islands in the library, line 1295-3093 only: no singleton library, so no live Pact line, and Pact is
        // the stock cantrip again
        library("Island", "Island");
        assertFalse(t.singletonLibrary());
        assertEquals(t.live(), List.of());
        assertNull(ComboExecute.pactCast(ai, castOf(pact)));
        assertEquals(aiOf(ai).canPlaySa(castOf(pact)), AiPlayDecision.WillPlay);
    }

    @Test
    public void pactDeclinesInTheWindow() {
        start(ORACLE_PACT);
        final Card oracle = hand(ORACLE);
        final Card pact = hand(PACT);
        singletonLibrary();
        lands("Island", "Island", "Swamp", "Swamp");
        final SpellAbility back = returnToHand(pact);
        assertTrue(back instanceof AbilitySub);
        assertEquals(back.getParam("Origin"), "Exile");
        assertEquals(back.getParam("Destination"), "Hand");
        assertFalse(ComboExecute.pactDeclines(ai, back));

        // our Oracle's trigger on the stack
        enters(oracle);
        assertEquals(ids(t.executable()), List.of("1295-3093"));
        assertTrue(ComboExecute.pactDeclines(ai, back));
        assertEquals(ComboExecute.pactCast(ai, castOf(pact)), Boolean.TRUE);
        // through the stock route: ChangeZoneAi.confirmAction
        assertFalse(aiOf(ai).confirmAction(back, null, "", null));

        // with the stack empty it is false
        game.getStack().remove(game.getStack().peek());
        assertTrue(game.getStack().isEmpty());
        assertFalse(ComboExecute.pactDeclines(ai, back));
        assertTrue(aiOf(ai).confirmAction(back, null, "", null));
    }

    // ---- Laboratory Maniac ----

    @Test
    public void labAtTheOpponentsEndStep() {
        // Review Focus 4
        start(LAB_CONSULTATION);
        addCard(LAB, ai);
        final Card consultation = hand(CONSULTATION);
        lands("Swamp");
        fillLibrary(ai, 20);
        assertEquals(ids(t.executable()), List.of("742-2836"));

        // our MAIN1, no draw spell: Consultation would leave the library empty for a whole opposing turn
        assertEquals(consider(consultation), AiPlayDecision.CantPlayAi);

        // the opponent's end step, our turn next: our draw step follows
        opponentsEndStep();
        assertEquals(consider(consultation), AiPlayDecision.WillPlay);

        // our MAIN1 again, with Opt in hand: the Swamp alone pays one of the two
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, ai);
        hand(OPT);
        assertEquals(consider(consultation), AiPlayDecision.CantPlayAi);
        // Island + Swamp pay both
        lands("Island");
        assertEquals(consider(consultation), AiPlayDecision.WillPlay);
    }

    // ---- Doctor Doom ----

    @Test
    public void doomWindow() {
        start(DOOM_CONSULTATION);
        final Card doom = ready(DOOM);
        final Card consultation = hand(CONSULTATION);
        lands("Swamp");
        fillLibrary(ai, 20);
        assertEquals(ids(t.executable()), List.of("x-1"));
        assertEquals(consider(consultation), AiPlayDecision.WillPlay);

        doom.setSickness(true);
        assertTrue(doom.isSick());
        assertEquals(consider(consultation), AiPlayDecision.CantPlayAi);
        doom.setSickness(false);

        doom.setTapped(true);
        assertEquals(consider(consultation), AiPlayDecision.CantPlayAi);
        doom.setTapped(false);
        assertEquals(consider(consultation), AiPlayDecision.WillPlay);

        addCard(LINVALA, opp);
        assertEquals(t.executable(), List.of());
        assertEquals(consider(consultation), AiPlayDecision.CantPlayAi);
    }

    @Test
    public void doomDrawsToWinOnAnEmptyLibrary() {
        start(DOOM_CONSULTATION);
        final Card doom = ready(DOOM);
        // the spent emptier: the deck holds line x-1
        addCardToZone(CONSULTATION, ai, ZoneType.Graveyard);
        ai.setLife(1, null);
        assertTrue(ai.getCardsIn(ZoneType.Library).isEmpty());
        assertEquals(ids(t.deckLines()), List.of("x-1"));

        assertTrue(ComboExecute.finishesLine(ai, doomDraw(doom)));
        assertEquals(aiOf(ai).canPlaySa(doomDraw(doom)), AiPlayDecision.WillPlay);

        // without line x-1: stock parity
        final AiPlayDecision stock = assertStockParity(() -> aiOf(ai).canPlaySa(doomDraw(doom)), lines(LAB_CONSULTATION));
        assertEquals(stock, AiPlayDecision.CantPlayAi);
    }

    // ---- Jace, Wielder of Mysteries ----

    @Test
    public void jaceLetsADrawSpellWin() {
        start(JACE_CONSULTATION);
        addCard(JACE, ai);
        addCardToZone(CONSULTATION, ai, ZoneType.Graveyard);
        final Card opt = hand(OPT);
        lands("Island");
        assertTrue(ai.getCardsIn(ZoneType.Library).isEmpty());
        assertEquals(ids(t.deckLines()), List.of("742-3096"));

        assertEquals(aiOf(ai).canPlaySa(castOf(opt)), AiPlayDecision.WillPlay);

        // without the line: stock parity
        final AiPlayDecision stock = assertStockParity(() -> aiOf(ai).canPlaySa(castOf(opt)), lines(LAB_CONSULTATION));
        assertEquals(stock, AiPlayDecision.CantPlayAi);
    }

    // ---- Fblthp, Impossibly Lost ----

    @Test
    public void fblthpTriggerWindow() {
        start(FBLTHP_CONSULTATION);
        final Card fblthp = ready(FBLTHP);
        final Card consultation = hand(CONSULTATION);
        lands("Swamp");
        fillLibrary(ai, 20);
        game.getStack().add(fblthpTrigger(fblthp));
        final SpellAbility top = game.getStack().peekAbility();
        assertTrue(top.isTrigger());
        assertEquals(ids(t.executable()), List.of("742-8266"));

        assertTrue(ComboExecute.respondsToOwn(ai, top));
        assertEquals(consider(consultation), AiPlayDecision.WillPlay);
    }

    // ---- Platinum Angel ----

    @Test
    public void platinumAngelBlocksEveryWindow() {
        // Review Focus 2
        start(LAB_CONSULTATION, LAB_PACT);
        addCard(LAB, ai);
        final Card consultation = hand(CONSULTATION);
        final Card pact = hand(PACT);
        lands("Swamp", "Swamp");
        singletonLibrary();
        opponentsEndStep();
        // the end-step Lab window, open for both emptiers
        assertEquals(consider(consultation), AiPlayDecision.WillPlay);
        assertEquals(ComboExecute.pactCast(ai, castOf(pact)), Boolean.TRUE);

        addCard(ANGEL, opp);
        assertTrue(ai.cantWin());
        assertEquals(consider(consultation), AiPlayDecision.CantPlayAi);
        assertNull(ComboExecute.pactCast(ai, castOf(pact)));
    }

    // ---- the own-library floor ----

    @Test
    public void floorExemptsAGoOffConsultation() {
        start(DOOM_CONSULTATION);
        addCard(EMERITUS, ai);
        ready(DOOM);
        final Card consultation = hand(CONSULTATION);
        lands("Swamp");
        fillLibrary(ai, 4);

        t.useLines(List.of());
        assertTrue(SpecialCardAi.OwnLibraryFloor.breachesCast(ai, castOf(consultation)));
        t.useLines(lines(DOOM_CONSULTATION));
        assertEquals(ComboExecute.floorExemption(ai, castOf(consultation)), "combo");
        assertFalse(SpecialCardAi.OwnLibraryFloor.breachesCast(ai, castOf(consultation)));
    }

    // ---- beyond the plan's eleven ----

    /** Tainted Pact resolving in a Laboratory Maniac window it was cast into: its own {1}{B} is paid, so the draw that
     *  follows needs only Opt's {U}, and every card is declined while that {U} is there. A hand cast of Pact needs the
     *  two together, black among it. */
    @Test
    public void pactDeclinesInTheLabWindowOnceItsOwnManaIsPaid() {
        start(LAB_PACT);
        addCard(LAB, ai);
        hand(OPT);
        final Card island = addCard("Island", ai);
        singletonLibrary();
        final Card pact = castOnStack(PACT);
        assertEquals(t.where(PACT), ComboTracker.Where.STACK);
        assertEquals(ids(t.executable()), List.of("2836-3093"));
        assertTrue(ComboExecute.pactDeclines(ai, returnToHand(pact)));
        // nothing left to cast Opt with: no draw follows, and Pact keeps the card
        island.setTapped(true);
        assertFalse(ComboExecute.pactDeclines(ai, returnToHand(pact)));
        island.setTapped(false);

        // a hand Pact: Island + Swamp pay Pact (the line is live) but not Pact and Opt together, so it is held
        final Card handPact = hand(PACT);
        lands("Swamp");
        assertEquals(ids(t.live()), List.of("2836-3093"));
        assertEquals(ComboExecute.pactCast(ai, castOf(handPact)), Boolean.FALSE);
        // a second Island pays both: the window opens
        lands("Island");
        assertEquals(ComboExecute.pactCast(ai, castOf(handPact)), Boolean.TRUE);
    }

    /** Fblthp's trigger asks for a response only with its line executable, Consultation in our hand and black to cast
     *  it; with no line the AI passes its own trigger, as stock does, and with one it answers it with Consultation. */
    @Test
    public void fblthpRespondsOnlyWithItsLineConsultationAndBlack() {
        start(FBLTHP_CONSULTATION);
        final Card fblthp = ready(FBLTHP);
        final Card consultation = hand(CONSULTATION);
        final Card swamp = addCard("Swamp", ai);
        fillLibrary(ai, 20);
        game.getStack().add(fblthpTrigger(fblthp));
        final SpellAbility top = game.getStack().peekAbility();

        t.useLines(List.of());
        assertFalse(ComboExecute.respondsToOwn(ai, top));
        assertNull(aiOf(ai).chooseSpellAbilityToPlay());

        t.useLines(lines(FBLTHP_CONSULTATION));
        final List<SpellAbility> choice = aiOf(ai).chooseSpellAbilityToPlay();
        assertNotNull(choice);
        assertEquals(ComboTracker.nameOf(choice.get(0).getHostCard()), CONSULTATION);

        // no black
        swamp.setTapped(true);
        assertFalse(ComboExecute.respondsToOwn(ai, top));
        swamp.setTapped(false);
        assertTrue(ComboExecute.respondsToOwn(ai, top));

        // Consultation back in the library: nothing in hand to respond with
        game.getAction().moveTo(ZoneType.Library, consultation, null, AbilityKey.newMap());
        assertFalse(ComboExecute.respondsToOwn(ai, top));
    }

    /** The finishing draw is the line's only on an empty library, while we can win; DrawAi.confirmAction then draws
     *  from it too. */
    @Test
    public void theFinishingDrawNeedsAnEmptyLibraryAndAWin() {
        start(JACE_CONSULTATION);
        addCard(JACE, ai);
        addCardToZone(CONSULTATION, ai, ZoneType.Graveyard);
        final Card opt = hand(OPT);
        lands("Island");
        final SpellAbility draw = castOf(opt).getSubAbility();
        assertEquals(draw.getApi(), ApiType.Draw);
        assertTrue(ComboExecute.finishesLine(ai, draw));
        assertTrue(aiOf(ai).confirmAction(draw, null, "", null));

        library("Grizzly Bears");
        assertFalse(ComboExecute.finishesLine(ai, draw));
        game.getAction().moveTo(ZoneType.Exile, t.find("Grizzly Bears", ZoneType.Library), null, AbilityKey.newMap());
        assertTrue(ComboExecute.finishesLine(ai, draw));

        addCard(ANGEL, opp);
        assertFalse(ComboExecute.finishesLine(ai, draw));
        assertFalse(aiOf(ai).confirmAction(draw, null, "", null));
    }

    /** With no line, Consultation and Pact keep their stock decisions and draws on a board where a line would open a
     *  window. */
    @Test
    public void noLineIsStock() {
        start(DOOM_CONSULTATION);
        ready(DOOM);
        final Card consultation = hand(CONSULTATION);
        lands("Swamp");
        fillLibrary(ai, 20);
        assertEquals(assertStockParity(() -> consider(consultation), lines(LAB_CONSULTATION)), AiPlayDecision.CantPlayAi);

        start(LAB_PACT);
        final Card pact = hand(PACT);
        lands("Swamp", "Swamp");
        singletonLibrary();
        opponentsEndStep();
        assertEquals(assertStockParity(() -> aiOf(ai).canPlaySa(castOf(pact)), lines(ORACLE_CONSULTATION)),
                AiPlayDecision.WillPlay);
    }

    /** The Laboratory Maniac window's own rules: the end step is the opponent's with our turn next, never ours; the
     *  spell that follows must draw; and the mana pays the emptier and that spell together, black among it. */
    @Test
    public void theLabWindowIsTheOpponentsEndStepOrADrawSpellWithTheManaForBoth() {
        start(LAB_CONSULTATION);
        addCard(LAB, ai);
        final Card consultation = hand(CONSULTATION);
        fillLibrary(ai, 20);
        lands("Island", "Island");
        final Card swamp = addCard("Swamp", ai);

        // our own end step: the opponent's turn comes before our draw
        game.getPhaseHandler().devModeSet(PhaseType.END_OF_TURN, ai);
        assertEquals(game.getPhaseHandler().getNextTurn(), opp);
        assertEquals(consider(consultation), AiPlayDecision.CantPlayAi);

        // our MAIN1: a spell that draws nothing is no follow-up, though the mana pays it and Consultation
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, ai);
        hand("Grizzly Bears");
        assertEquals(consider(consultation), AiPlayDecision.CantPlayAi);
        // Opt is one
        hand(OPT);
        assertEquals(consider(consultation), AiPlayDecision.WillPlay);
        // the Swamp tapped: two Islands pay Opt and one more, but not Consultation's black
        swamp.setTapped(true);
        assertEquals(consider(consultation), AiPlayDecision.CantPlayAi);
    }

    /** The finishing draw is its own winner's: Doctor Doom's {T} on an empty library finishes nothing for a deck whose
     *  line is Laboratory Maniac's, nor does a draw with Jace in play for a deck whose line is Doom's. */
    @Test
    public void theFinishingDrawNeedsItsOwnWinnersLine() {
        start(LAB_CONSULTATION);
        final Card doom = ready(DOOM);
        addCardToZone(LAB, ai, ZoneType.Graveyard);
        addCardToZone(CONSULTATION, ai, ZoneType.Graveyard);
        assertEquals(ids(t.deckLines()), List.of("742-2836"));
        assertFalse(ComboExecute.finishesLine(ai, doomDraw(doom)));
        t.useLines(lines(DOOM_CONSULTATION));
        assertTrue(ComboExecute.finishesLine(ai, doomDraw(doom)));

        start(DOOM_CONSULTATION);
        addCard(JACE, ai);
        addCardToZone(DOOM, ai, ZoneType.Graveyard);
        addCardToZone(CONSULTATION, ai, ZoneType.Graveyard);
        final SpellAbility draw = castOf(hand(OPT)).getSubAbility();
        assertEquals(ids(t.deckLines()), List.of("x-1"));
        assertFalse(ComboExecute.finishesLine(ai, draw));
        t.useLines(lines(JACE_CONSULTATION));
        assertTrue(ComboExecute.finishesLine(ai, draw));
    }

    // ---- review round 1 ----

    /** Review Focus 4, the colours: the Laboratory Maniac window's draw spell must be payable in its own colours beside
     *  the emptier's. Two Swamps make the two mana Consultation and Opt cost together, black among it, but no {U}: Opt
     *  cannot follow, so Consultation waits; an Island opens the window. Tainted Pact alike, resolving and cast. */
    @Test
    public void theLabWindowsDrawSpellMustBePayableInItsOwnColours() {
        start(LAB_CONSULTATION);
        addCard(LAB, ai);
        final Card consultation = hand(CONSULTATION);
        hand(OPT);
        lands("Swamp", "Swamp");
        fillLibrary(ai, 20);
        assertEquals(ids(t.executable()), List.of("742-2836"));
        final SpecialCardAi.HonestMana mana = SpecialCardAi.HonestMana.of(ai, castOf(consultation), true);
        assertEquals(mana.total(), 2);
        assertEquals(mana.colour(MagicColor.BLACK), 2);
        assertEquals(mana.colour(MagicColor.BLUE), 0);
        assertEquals(consider(consultation), AiPlayDecision.CantPlayAi);
        lands("Island");
        assertEquals(consider(consultation), AiPlayDecision.WillPlay);

        // Tainted Pact resolving, its own mana paid: a Swamp pays one mana, never Opt's {U}, so Pact keeps the card
        start(LAB_PACT);
        addCard(LAB, ai);
        hand(OPT);
        lands("Swamp");
        singletonLibrary();
        final Card resolving = castOnStack(PACT);
        assertEquals(ids(t.executable()), List.of("2836-3093"));
        assertFalse(ComboExecute.pactDeclines(ai, returnToHand(resolving)));
        // a hand Pact: three Swamps pay {1}{B} and one more, never Opt's {U}; the line is live, so Pact is held
        final Card handPact = hand(PACT);
        lands("Swamp", "Swamp");
        assertEquals(ids(t.live()), List.of("2836-3093"));
        assertEquals(ComboExecute.pactCast(ai, castOf(handPact)), Boolean.FALSE);
        // an Island pays Opt: the hand Pact is cast into the window, and the resolving one declines
        lands("Island");
        assertEquals(ComboExecute.pactCast(ai, castOf(handPact)), Boolean.TRUE);
        assertTrue(ComboExecute.pactDeclines(ai, returnToHand(resolving)));
    }

    /** Fblthp on our battlefield is no window by itself, in our Main 1 or at the opponent's end step: Consultation
     *  waits for Fblthp's own trigger on our stack (spec section 6.4: only when that trigger resolves this turn). */
    @Test
    public void fblthpInPlayIsNoWindowWithoutItsTrigger() {
        start(FBLTHP_CONSULTATION);
        final Card fblthp = ready(FBLTHP);
        final Card consultation = hand(CONSULTATION);
        lands("Swamp");
        fillLibrary(ai, 20);
        assertEquals(ids(t.executable()), List.of("742-8266"));
        assertTrue(game.getStack().isEmpty());
        assertEquals(consider(consultation), AiPlayDecision.CantPlayAi);
        opponentsEndStep();
        assertEquals(consider(consultation), AiPlayDecision.CantPlayAi);

        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, ai);
        game.getStack().add(fblthpTrigger(fblthp));
        assertEquals(consider(consultation), AiPlayDecision.WillPlay);
    }

    /** Review Focus 1 with both Oracle rows: in a library holding two Islands, Pact's line is dead and Consultation's
     *  live, so Oracle's hold rests on Consultation alone. Pact is never held for that line nor cast as its emptier,
     *  before Oracle's trigger or with it on our stack; it declines nothing and the floor does not exempt it. */
    @Test
    public void pactIsNoEmptierWhenOnlyConsultationsOracleLineIsLive() {
        start(ORACLE_CONSULTATION, ORACLE_PACT);
        final Card oracle = hand(ORACLE);
        final Card pact = hand(PACT);
        library(CONSULTATION, "Island", "Island");
        lands("Island", "Island", "Swamp", "Swamp");
        assertEquals(ids(t.deckLines()), List.of("742-1295", "1295-3093"));
        assertFalse(t.singletonLibrary());
        assertEquals(ids(t.live()), List.of("742-1295"));
        assertNull(ComboExecute.pactCast(ai, castOf(pact)));
        assertEquals(aiOf(ai).canPlaySa(castOf(pact)), AiPlayDecision.WillPlay);

        // Oracle's trigger on our stack: Consultation's window, not Pact's
        enters(oracle);
        assertEquals(ids(t.executable()), List.of("742-1295"));
        assertNull(ComboExecute.pactCast(ai, castOf(pact)));
        assertFalse(ComboExecute.pactDeclines(ai, returnToHand(pact)));
        assertTrue(aiOf(ai).confirmAction(returnToHand(pact), null, "", null));
        assertNull(ComboExecute.floorExemption(ai, castOf(pact)));
    }

    /** Only Doctor Doom's own {T} draw finishes a Doom line: another creature's {T} draw (Arcanis the Omnipotent's) on
     *  the emptied library would lose us the game, so it finishes nothing. */
    @Test
    public void anotherTapDrawIsNotDoomsFinishingDraw() {
        start(DOOM_CONSULTATION);
        final Card doom = ready(DOOM);
        addCardToZone(CONSULTATION, ai, ZoneType.Graveyard);
        final Card arcanis = ready("Arcanis the Omnipotent");
        ai.setLife(1, null);
        assertTrue(ai.getCardsIn(ZoneType.Library).isEmpty());
        assertEquals(ids(t.deckLines()), List.of("x-1"));
        assertTrue(ComboExecute.finishesLine(ai, doomDraw(doom)));

        // doomDraw finds a card's activated Draw: Arcanis's {T}: draw three cards
        final SpellAbility draw = doomDraw(arcanis);
        assertEquals(draw.getHostCard(), arcanis);
        assertTrue(draw.getPayCosts().hasTapCost());
        assertFalse(ComboExecute.finishesLine(ai, draw));
    }

    /** Jace, Wielder of Mysteries follows Laboratory Maniac's rules (the plan's calls): Consultation waits in our
     *  Main 1 with no draw spell, and goes at the opponent's end step with our turn next, or with Opt and the mana for
     *  both. */
    @Test
    public void jacesWindowIsLabsWindow() {
        start(JACE_CONSULTATION);
        addCard(JACE, ai);
        final Card consultation = hand(CONSULTATION);
        lands("Swamp");
        fillLibrary(ai, 20);
        assertEquals(ids(t.executable()), List.of("742-3096"));
        assertEquals(consider(consultation), AiPlayDecision.CantPlayAi);
        opponentsEndStep();
        assertEquals(consider(consultation), AiPlayDecision.WillPlay);

        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, ai);
        hand(OPT);
        assertEquals(consider(consultation), AiPlayDecision.CantPlayAi);
        lands("Island");
        assertEquals(consider(consultation), AiPlayDecision.WillPlay);
    }

    /** The end-step rule is the end step's: the opponent's Main 2, with our turn next as well, is no window. */
    @Test
    public void theLabWindowIsTheOpponentsEndStepNotItsMainTwo() {
        start(LAB_CONSULTATION);
        addCard(LAB, ai);
        final Card consultation = hand(CONSULTATION);
        lands("Swamp");
        fillLibrary(ai, 20);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN2, opp);
        assertEquals(game.getPhaseHandler().getNextTurn(), ai);
        assertEquals(consider(consultation), AiPlayDecision.CantPlayAi);
        game.getPhaseHandler().devModeSet(PhaseType.END_OF_TURN, opp);
        assertEquals(consider(consultation), AiPlayDecision.WillPlay);
    }

    /** Only Fblthp's own trigger is answered: Oracle's entering trigger on top of our stack asks for no response, with
     *  Fblthp's line, Consultation and black all there; Fblthp's trigger on top does. */
    @Test
    public void onlyFblthpsOwnTriggerIsAnswered() {
        start(FBLTHP_CONSULTATION);
        final Card fblthp = ready(FBLTHP);
        hand(CONSULTATION);
        final Card oracle = hand(ORACLE);
        lands("Swamp");
        fillLibrary(ai, 20);
        enters(oracle);
        final SpellAbility oracles = game.getStack().peekAbility();
        assertEquals(ComboTracker.nameOf(oracles.getHostCard()), ORACLE);
        assertEquals(ids(t.executable()), List.of("742-8266"));
        assertFalse(ComboExecute.respondsToOwn(ai, oracles));

        game.getStack().add(fblthpTrigger(fblthp));
        final SpellAbility fblthps = game.getStack().peekAbility();
        assertEquals(ComboTracker.nameOf(fblthps.getHostCard()), FBLTHP);
        assertTrue(ComboExecute.respondsToOwn(ai, fblthps));
    }

    /** Fblthp's trigger asks for no response while its line is denied: an opposing Platinum Angel (we cannot win)
     *  leaves no executable line, though the deck holds it and Consultation and black are there. */
    @Test
    public void fblthpAsksNoResponseWhileItsLineIsDenied() {
        start(FBLTHP_CONSULTATION);
        final Card fblthp = ready(FBLTHP);
        hand(CONSULTATION);
        lands("Swamp");
        fillLibrary(ai, 20);
        game.getStack().add(fblthpTrigger(fblthp));
        final SpellAbility top = game.getStack().peekAbility();
        assertTrue(ComboExecute.respondsToOwn(ai, top));

        addCard(ANGEL, opp);
        assertTrue(ai.cantWin());
        assertEquals(ids(t.deckLines()), List.of("742-8266"));
        assertEquals(t.executable(), List.of());
        assertFalse(ComboExecute.respondsToOwn(ai, top));
    }

    /** The floor's exemption needs an open window: with Doctor Doom sick the same board's Consultation keeps the
     *  floor's veto, its line executable all the same. */
    @Test
    public void theFloorKeepsItsVetoOutsideAWindow() {
        start(DOOM_CONSULTATION);
        addCard(EMERITUS, ai);
        final Card doom = ready(DOOM);
        final Card consultation = hand(CONSULTATION);
        lands("Swamp");
        fillLibrary(ai, 4);
        assertEquals(ComboExecute.floorExemption(ai, castOf(consultation)), "combo");
        assertFalse(SpecialCardAi.OwnLibraryFloor.breachesCast(ai, castOf(consultation)));

        doom.setSickness(true);
        assertEquals(ids(t.executable()), List.of("x-1"));
        assertNull(ComboExecute.floorExemption(ai, castOf(consultation)));
        assertTrue(SpecialCardAi.OwnLibraryFloor.breachesCast(ai, castOf(consultation)));
    }

    /** The floor reads the window as the emptier's cast does, the emptier's own mana counted: in a Laboratory Maniac
     *  window an Island pays Opt but not Opt and Consultation together, so no exemption; a Swamp beside it pays
     *  both. */
    @Test
    public void theFloorsWindowCountsTheEmptiersOwnMana() {
        start(LAB_CONSULTATION);
        addCard(EMERITUS, ai);
        addCard(LAB, ai);
        final Card consultation = hand(CONSULTATION);
        hand(OPT);
        lands("Island");
        fillLibrary(ai, 4);
        assertEquals(ids(t.executable()), List.of("742-2836"));
        assertNull(ComboExecute.floorExemption(ai, castOf(consultation)));
        lands("Swamp");
        assertEquals(ComboExecute.floorExemption(ai, castOf(consultation)), "combo");
    }

    /** The floor exempts Tainted Pact cast inside its window as it does Consultation: Pact in the opponent's end-step
     *  Laboratory Maniac window, Archmage Emeritus in play; in our Main 1 with no draw spell, no window, no
     *  exemption. */
    @Test
    public void theFloorExemptsAPactInItsWindow() {
        start(LAB_PACT);
        addCard(EMERITUS, ai);
        addCard(LAB, ai);
        final Card pact = hand(PACT);
        lands("Swamp", "Swamp");
        singletonLibrary();
        opponentsEndStep();
        assertEquals(ComboExecute.pactCast(ai, castOf(pact)), Boolean.TRUE);
        assertEquals(ComboExecute.floorExemption(ai, castOf(pact)), "combo");

        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, ai);
        assertEquals(ComboExecute.pactCast(ai, castOf(pact)), Boolean.FALSE);
        assertNull(ComboExecute.floorExemption(ai, castOf(pact)));
    }

    /** Only Tainted Pact's own exile-to-hand step is declined: Distant Memories' DBToHand, the same Exile-to-Hand
     *  ChangeZone sub, is not, inside the Oracle window where Pact's is. */
    @Test
    public void onlyPactsReturnToHandIsDeclined() {
        start(ORACLE_PACT);
        final Card oracle = hand(ORACLE);
        final Card pact = hand(PACT);
        singletonLibrary();
        lands("Island", "Island", "Swamp", "Swamp");
        enters(oracle);
        assertTrue(ComboExecute.pactDeclines(ai, returnToHand(pact)));

        final Card memories = hand("Distant Memories");
        final SpellAbility toHand = AbilityFactory.getAbility(memories, "DBToHand");
        toHand.setActivatingPlayer(ai);
        assertTrue(toHand instanceof AbilitySub);
        assertEquals(toHand.getApi(), ApiType.ChangeZone);
        assertEquals(toHand.getParam("Origin"), "Exile");
        assertEquals(toHand.getParam("Destination"), "Hand");
        assertFalse(ComboExecute.pactDeclines(ai, toHand));
    }

    /** The follow-up draw is ours alone: Prosperity (each player draws) opens no window though the mana pays it and
     *  Consultation together; Opt does. */
    @Test
    public void anEachPlayerDrawIsNoFollowUp() {
        start(LAB_CONSULTATION);
        addCard(LAB, ai);
        final Card consultation = hand(CONSULTATION);
        lands("Island", "Island", "Swamp");
        fillLibrary(ai, 20);
        hand("Prosperity");
        assertEquals(consider(consultation), AiPlayDecision.CantPlayAi);
        hand(OPT);
        assertEquals(consider(consultation), AiPlayDecision.WillPlay);
    }

    /** The follow-up draw is untargeted, as the finishing draw is: with Jace in play stock DrawAi aims a targeted draw
     *  at our emptied library only with Laboratory Maniac, and finishesLine lifts only an untargeted draw of ours, so
     *  Ancestral Recall opens no window though the mana pays it and Consultation together; Opt does, and on the emptied
     *  library Opt's draw finishes the line where Ancestral Recall's does not. */
    @Test
    public void aTargetedDrawIsNoFollowUp() {
        start(JACE_CONSULTATION);
        addCard(JACE, ai);
        final Card consultation = hand(CONSULTATION);
        final Card recall = hand("Ancestral Recall");
        lands("Island", "Swamp");
        fillLibrary(ai, 20);
        assertTrue(castOf(recall).usesTargeting());
        assertEquals(consider(consultation), AiPlayDecision.CantPlayAi);
        final Card opt = hand(OPT);
        assertEquals(consider(consultation), AiPlayDecision.WillPlay);

        while (!ai.getCardsIn(ZoneType.Library).isEmpty()) {
            game.getAction().moveTo(ZoneType.Exile, ai.getCardsIn(ZoneType.Library).getFirst(), null,
                    AbilityKey.newMap());
        }
        assertTrue(ComboExecute.finishesLine(ai, castOf(opt).getSubAbility()));
        assertFalse(ComboExecute.finishesLine(ai, castOf(recall)));
    }

    // ---- review round 2 ----

    /** Review Focus 4, the timing: a sorcery-speed draw spell follows the emptier only in our own main phase. With
     *  Serum Visions beside Consultation and Laboratory Maniac, Island + Swamp paying both, the window opens in our
     *  Main 1 and Main 2; in the opponent's Main 1, in our combat and at our own end step Visions cannot be cast before
     *  our draw step, where the emptied library would deck us, so Consultation waits (the opponent's end step with our
     *  turn next stays the window it is). Tainted Pact resolving declines alike: in our Main 1, not in the
     *  opponent's. */
    @Test
    public void aSorceryDrawFollowsOnlyInOurOwnMainPhase() {
        start(LAB_CONSULTATION);
        addCard(LAB, ai);
        final Card consultation = hand(CONSULTATION);
        final Card visions = hand("Serum Visions");
        lands("Island", "Swamp");
        fillLibrary(ai, 20);
        assertEquals(ids(t.executable()), List.of("742-2836"));
        assertTrue(SpellAbilityAi.isSorcerySpeed(castOf(visions), ai));
        final PhaseHandler ph = game.getPhaseHandler();

        assertEquals(consider(consultation), AiPlayDecision.WillPlay);
        ph.devModeSet(PhaseType.MAIN2, ai);
        assertEquals(consider(consultation), AiPlayDecision.WillPlay);

        ph.devModeSet(PhaseType.MAIN1, opp);
        assertEquals(ph.getNextTurn(), ai);
        assertEquals(consider(consultation), AiPlayDecision.CantPlayAi);
        ph.devModeSet(PhaseType.COMBAT_DECLARE_ATTACKERS, ai);
        assertEquals(consider(consultation), AiPlayDecision.CantPlayAi);
        ph.devModeSet(PhaseType.END_OF_TURN, ai);
        assertEquals(ph.getNextTurn(), opp);
        assertEquals(consider(consultation), AiPlayDecision.CantPlayAi);
        opponentsEndStep();
        assertEquals(consider(consultation), AiPlayDecision.WillPlay);

        // Tainted Pact resolving, its own mana paid: Visions follows in our Main 1, never in the opponent's
        start(LAB_PACT);
        addCard(LAB, ai);
        hand("Serum Visions");
        lands("Island");
        singletonLibrary();
        final Card resolving = castOnStack(PACT);
        assertEquals(ids(t.executable()), List.of("2836-3093"));
        assertTrue(ComboExecute.pactDeclines(ai, returnToHand(resolving)));
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, opp);
        assertFalse(ComboExecute.pactDeclines(ai, returnToHand(resolving)));
    }

    /** An instant, or a sorcery with flash, follows the emptier in any phase: Opt beside Consultation and Laboratory
     *  Maniac opens the window in the opponent's Main 1, in our combat and at our own end step, and so does Serum
     *  Visions while Leyline of Anticipation lets us cast it as though it had flash. */
    @Test
    public void anInstantOrAFlashedSorceryFollowsInAnyPhase() {
        start(LAB_CONSULTATION);
        addCard(LAB, ai);
        final Card consultation = hand(CONSULTATION);
        final Card visions = hand("Serum Visions");
        lands("Island", "Swamp");
        fillLibrary(ai, 20);
        final PhaseHandler ph = game.getPhaseHandler();
        // the opponent's Main 1, then our combat and our own end step
        final List<PhaseType> elsewhere = List.of(PhaseType.MAIN1, PhaseType.COMBAT_DECLARE_ATTACKERS,
                PhaseType.END_OF_TURN);
        ph.devModeSet(PhaseType.MAIN1, opp);
        assertEquals(consider(consultation), AiPlayDecision.CantPlayAi);

        final Card leyline = addCard("Leyline of Anticipation", ai);
        assertFalse(SpellAbilityAi.isSorcerySpeed(castOf(visions), ai));
        for (final PhaseType phase : elsewhere) {
            ph.devModeSet(phase, phase == PhaseType.MAIN1 ? opp : ai);
            assertEquals(consider(consultation), AiPlayDecision.WillPlay, "Serum Visions with flash at " + phase);
        }
        game.getAction().moveTo(ZoneType.Graveyard, leyline, null, AbilityKey.newMap());
        assertTrue(SpellAbilityAi.isSorcerySpeed(castOf(visions), ai));
        assertEquals(consider(consultation), AiPlayDecision.CantPlayAi);

        game.getAction().moveTo(ZoneType.Graveyard, visions, null, AbilityKey.newMap());
        hand(OPT);
        for (final PhaseType phase : elsewhere) {
            ph.devModeSet(phase, phase == PhaseType.MAIN1 ? opp : ai);
            assertEquals(consider(consultation), AiPlayDecision.WillPlay, "Opt at " + phase);
        }
    }

    /** A draw spell the AI is never offered is no follow-up: Gitaxian Probe carries AI:RemoveDeck:All and no readmit
     *  row, so getSpellAbilityToPlay drops it and the emptied library would wait for our draw step. Two Swamps make the
     *  two mana Consultation and Probe cost together, and Probe's {U/P} is no blue pip, yet Consultation waits, with an
     *  Island beside them too; Opt, which the AI casts, opens the window. */
    @Test
    public void aDrawSpellTheAiNeverCastsIsNoFollowUp() {
        start(LAB_CONSULTATION);
        addCard(LAB, ai);
        final Card consultation = hand(CONSULTATION);
        final Card probe = hand("Gitaxian Probe");
        lands("Swamp", "Swamp");
        fillLibrary(ai, 20);
        assertEquals(ids(t.executable()), List.of("742-2836"));
        assertTrue(ComputerUtilCard.isCardRemAIDeck(probe));
        assertFalse(SpecialCardAi.RemoveDeckFilter.readmit(ai, castOf(probe)));
        assertEquals(probe.getManaCost().getShardCount(ManaCostShard.BLUE), 0);
        assertEquals(probe.getCMC(), 1);
        assertEquals(SpecialCardAi.HonestMana.of(ai, castOf(consultation), true).total(), 2);
        assertEquals(consider(consultation), AiPlayDecision.CantPlayAi);
        lands("Island");
        assertEquals(consider(consultation), AiPlayDecision.CantPlayAi);
        hand(OPT);
        assertEquals(consider(consultation), AiPlayDecision.WillPlay);
    }

    /** A hinted draw spell follows when its own readmit row admits it, as getSpellAbilityToPlay asks the row: Faithless
     *  Looting (AI:RemoveDeck:All) is readmitted in our Main 2 with two cards worth looting away and its red, so
     *  Consultation goes; with one such card, or in our Main 1, the row keeps Looting off and Consultation waits. The
     *  decision draws no random number. */
    @Test
    public void aHintedDrawSpellFollowsOnItsReadmitRow() {
        start(LAB_CONSULTATION);
        addCard(LAB, ai);
        final Card consultation = hand(CONSULTATION);
        final Card looting = hand("Faithless Looting");
        hand("Craw Wurm");
        lands("Swamp", "Mountain");
        fillLibrary(ai, 20);
        assertTrue(ComputerUtilCard.isCardRemAIDeck(looting));
        final PhaseHandler ph = game.getPhaseHandler();
        ph.devModeSet(PhaseType.MAIN2, ai);
        assertFalse(SpecialCardAi.RemoveDeckFilter.readmit(ai, castOf(looting)));
        assertEquals(consider(consultation), AiPlayDecision.CantPlayAi);

        hand("Craw Wurm");
        assertTrue(SpecialCardAi.RemoveDeckFilter.readmit(ai, castOf(looting)));
        final long before = MyRandom.callCount();
        assertEquals(consider(consultation), AiPlayDecision.WillPlay);
        assertEquals(MyRandom.callCount(), before, "the decision drew");

        ph.devModeSet(PhaseType.MAIN1, ai);
        assertFalse(SpecialCardAi.RemoveDeckFilter.readmit(ai, castOf(looting)));
        assertEquals(consider(consultation), AiPlayDecision.CantPlayAi);
    }

    /** The colours add per colour over the two costs: Consultation's {B} and Night's Whisper's {1}{B} need two black.
     *  Swamp + Island + Island make the three mana they cost together with one black among it, so Night's Whisper
     *  could not follow and Consultation waits; a second Swamp opens the window. */
    @Test
    public void sameColourPipsOfTheTwoCostsAdd() {
        start(LAB_CONSULTATION);
        addCard(LAB, ai);
        final Card consultation = hand(CONSULTATION);
        hand("Night's Whisper");
        lands("Swamp", "Island", "Island");
        fillLibrary(ai, 20);
        final SpecialCardAi.HonestMana mana = SpecialCardAi.HonestMana.of(ai, castOf(consultation), true);
        assertEquals(mana.total(), 3);
        assertEquals(mana.colour(MagicColor.BLACK), 1);
        assertEquals(consider(consultation), AiPlayDecision.CantPlayAi);
        lands("Swamp");
        assertEquals(consider(consultation), AiPlayDecision.WillPlay);
    }

    /** Every colour of the draw spell is checked, not only blue and black: Swamps that pay Consultation and the draw
     *  spell in mana value wait for Desperate Ravings' red, Revitalize's white and both of Harmonize's green. */
    @Test
    public void everyColourOfTheDrawSpellIsChecked() {
        assertTheDrawSpellWaitsForItsColour("Desperate Ravings", 3, "Mountain", 1);
        assertTheDrawSpellWaitsForItsColour("Revitalize", 3, "Plains", 1);
        assertTheDrawSpellWaitsForItsColour("Harmonize", 5, "Forest", 2);
    }

    // Laboratory Maniac, Consultation and drawSpell in hand, our Main 1, a 20-card library: swamps Swamps pay the two
    // in mana value, black among it, and Consultation waits until the pips-th land of the draw spell's colour
    private void assertTheDrawSpellWaitsForItsColour(final String drawSpell, final int swamps, final String land,
            final int pips) {
        start(LAB_CONSULTATION);
        addCard(LAB, ai);
        final Card consultation = hand(CONSULTATION);
        final Card spell = hand(drawSpell);
        fillLibrary(ai, 20);
        for (int i = 0; i < swamps; i++) {
            lands("Swamp");
        }
        assertEquals(ids(t.executable()), List.of("742-2836"));
        assertEquals(consultation.getCMC() + spell.getCMC(), swamps, drawSpell);
        for (int i = 0; i < pips; i++) {
            assertEquals(consider(consultation), AiPlayDecision.CantPlayAi, drawSpell + " with " + i + " " + land);
            lands(land);
        }
        assertEquals(consider(consultation), AiPlayDecision.WillPlay, drawSpell + " with " + pips + " " + land);
    }
}
