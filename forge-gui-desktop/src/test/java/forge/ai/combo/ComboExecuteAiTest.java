package forge.ai.combo;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNotEquals;
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
import forge.ai.ComputerUtilMana;
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
import forge.game.ability.AbilityUtils;
import forge.game.ability.ApiType;
import forge.game.card.Card;
import forge.game.card.CounterEnumType;
import forge.game.cost.Cost;
import forge.game.phase.PhaseHandler;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.player.PlayerCollection;
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
    private static final String TEFERI = "Teferi, Time Raveler";

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

    /** Spec 6.4: Consultation goes for Laboratory Maniac or Jace "when one of them is on our battlefield and a draw we
     *  control follows". With the winner still in our {@code zone}, hand or library, the opponent's end step, our turn
     *  next, opens no window, though the line is executable (both zones are reachable): Consultation would take the
     *  library, the winner with it when it is there, and our draw would lose. The library is the likelier zone while
     *  Consultation is in hand. The same card moved onto our battlefield opens it. */
    private void aWinnerOffTheBattlefieldOpensNoWindow(final String row, final String winner, final String id,
            final ZoneType zone, final ComboTracker.Where where) {
        start(row);
        final Card held = addCardToZone(winner, ai, zone);
        final Card consultation = hand(CONSULTATION);
        lands("Swamp");
        fillLibrary(ai, 20);
        opponentsEndStep();
        assertEquals(ids(t.executable()), List.of(id));
        assertEquals(t.where(winner), where);
        assertEquals(consider(consultation), AiPlayDecision.CantPlayAi, winner + " in our " + where + " opened a window");

        game.getAction().moveTo(ZoneType.Battlefield, held, null, AbilityKey.newMap());
        assertEquals(t.where(winner), ComboTracker.Where.BATTLEFIELD);
        assertEquals(consider(consultation), AiPlayDecision.WillPlay);
    }

    @Test
    public void labInHandIsNoWindow() {
        aWinnerOffTheBattlefieldOpensNoWindow(LAB_CONSULTATION, LAB, "742-2836", ZoneType.Hand,
                ComboTracker.Where.HAND);
    }

    @Test
    public void jaceInHandIsNoWindow() {
        aWinnerOffTheBattlefieldOpensNoWindow(JACE_CONSULTATION, JACE, "742-3096", ZoneType.Hand,
                ComboTracker.Where.HAND);
    }

    @Test
    public void labInTheLibraryIsNoWindow() {
        aWinnerOffTheBattlefieldOpensNoWindow(LAB_CONSULTATION, LAB, "742-2836", ZoneType.Library,
                ComboTracker.Where.LIBRARY);
    }

    @Test
    public void jaceInTheLibraryIsNoWindow() {
        aWinnerOffTheBattlefieldOpensNoWindow(JACE_CONSULTATION, JACE, "742-3096", ZoneType.Library,
                ComboTracker.Where.LIBRARY);
    }

    /** A window is its own line's (the ComboExecute header: each window is gated by a line executable now pairing that
     *  emptier with that winner). A deck with two Consultation lines, in our Main 1 with no draw spell: Laboratory
     *  Maniac on our battlefield stages its line, but no draw of ours follows; Doctor Doom in our library, whose line
     *  wants no draw after, does not stage its own. Lab's staging opens no window for Doom's line. Doom moved onto our
     *  battlefield, ready, opens its own. */
    @Test
    public void twoLinesOpenOnlyTheirOwnWindow() {
        start(LAB_CONSULTATION, DOOM_CONSULTATION);
        ready(LAB);
        final Card doom = addCardToZone(DOOM, ai, ZoneType.Library);
        final Card consultation = hand(CONSULTATION);
        lands("Swamp");
        fillLibrary(ai, 20);
        assertEquals(game.getPhaseHandler().getPhase(), PhaseType.MAIN1);
        assertEquals(game.getPhaseHandler().getPlayerTurn(), ai);
        assertEquals(ids(t.executable()).stream().sorted().collect(Collectors.toList()), List.of("742-2836", "x-1"));
        assertEquals(t.where(DOOM), ComboTracker.Where.LIBRARY);
        assertEquals(consider(consultation), AiPlayDecision.CantPlayAi, "Lab's staging opened Doom's window");

        final Card inPlay = game.getAction().moveTo(ZoneType.Battlefield, doom, null, AbilityKey.newMap());
        inPlay.setSickness(false);
        assertEquals(t.where(DOOM), ComboTracker.Where.BATTLEFIELD);
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

        // review round 3: the window holds against Visions' own cast on the library Consultation emptied, in our Main 1
        // and our Main 2
        emptied(consultation);
        for (final PhaseType phase : List.of(PhaseType.MAIN1, PhaseType.MAIN2)) {
            ph.devModeSet(phase, ai);
            assertEquals(aiOf(ai).canPlaySa(castOf(visions)), AiPlayDecision.WillPlay, "Serum Visions at " + phase);
        }

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
        final Card opt = hand(OPT);
        for (final PhaseType phase : elsewhere) {
            ph.devModeSet(phase, phase == PhaseType.MAIN1 ? opp : ai);
            assertEquals(consider(consultation), AiPlayDecision.WillPlay, "Opt at " + phase);
        }

        // review round 3: the window holds against Opt's own cast on the library Consultation emptied, in each phase
        emptied(consultation);
        for (final PhaseType phase : elsewhere) {
            ph.devModeSet(phase, phase == PhaseType.MAIN1 ? opp : ai);
            assertEquals(aiOf(ai).canPlaySa(castOf(opt)), AiPlayDecision.WillPlay, "Opt at " + phase);
        }
    }

    /** A draw spell the AI is never offered is no follow-up: Gitaxian Probe carries AI:RemoveDeck:All and no readmit
     *  row, so getSpellAbilityToPlay drops it and the emptied library would wait for our draw step. Two Swamps make the
     *  two mana Consultation and Probe cost together, and Probe's {U/P} is no blue pip, yet Consultation waits, with an
     *  Island beside them too; Opt, which the AI casts, opens the window. (Since review round 3 Probe's targeted
     *  RevealHand root is no follow-up shape either; See Beyond, of that shape, pins the hint alone below.) */
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

    /** A looter is no follow-up, even on its readmit row (review round 3; round 2 had Faithless Looting follow there):
     *  a Discard part is no follow-up shape, since a loot with no hand left to discard is never cast (Careful Study
     *  below). Faithless Looting (AI:RemoveDeck:All) is readmitted in our Main 2 with two cards worth looting away and
     *  its red, yet Consultation waits, as it does with one such card or in our Main 1. The decision draws no random
     *  number. */
    @Test
    public void aLooterIsNoFollowUpEvenOnItsReadmitRow() {
        start(LAB_CONSULTATION);
        addCard(LAB, ai);
        final Card consultation = hand(CONSULTATION);
        final Card looting = hand("Faithless Looting");
        hand("Craw Wurm");
        lands("Swamp", "Mountain");
        fillLibrary(ai, 20);
        assertTrue(ComputerUtilCard.isCardRemAIDeck(looting));
        assertNotNull(castOf(looting).findSubAbilityByType(ApiType.Discard));
        final PhaseHandler ph = game.getPhaseHandler();
        ph.devModeSet(PhaseType.MAIN2, ai);
        assertFalse(SpecialCardAi.RemoveDeckFilter.readmit(ai, castOf(looting)));
        assertEquals(consider(consultation), AiPlayDecision.CantPlayAi);

        hand("Craw Wurm");
        assertTrue(SpecialCardAi.RemoveDeckFilter.readmit(ai, castOf(looting)));
        final long before = MyRandom.callCount();
        assertEquals(consider(consultation), AiPlayDecision.CantPlayAi);
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
     *  spell in mana value wait for both of Harmonize's green. (Round 2 had Desperate Ravings' red and Revitalize's
     *  white too; since review round 3 neither is a follow-up shape, a Discard part and a GainLife root, and no front
     *  face of that shape in the cardsfolder carries a red or a white pip, a hybrid one aside.) */
    @Test
    public void everyColourOfTheDrawSpellIsChecked() {
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

    // ---- review round 3 ----

    /** Review Focus 4 with the finding's Consider, end to end: Laboratory Maniac in play, Consultation and Consider in
     *  hand, Island + Swamp, a 20-card library, our Main 1. Consider is no follow-up (on the emptied library SurveilAi
     *  refuses it), so Consultation is not cast this turn and our end step finds the library whole. */
    @Test
    public void considerLeavesTheLibraryWholeThroughOurTurn() {
        start(LAB_CONSULTATION);
        addCard(LAB, ai);
        hand(CONSULTATION);
        hand("Consider");
        lands("Island", "Swamp");
        fillLibrary(ai, 20);
        playUntilPhase(PhaseType.END_OF_TURN);
        assertFalse(game.isGameOver());
        assertEquals(game.getPhaseHandler().getPlayerTurn(), ai);
        assertNotNull(t.find(CONSULTATION, ZoneType.Hand), "Consultation was cast");
        assertFalse(ai.getCardsIn(ZoneType.Library).isEmpty());
    }

    /** End to end, a follow-up wins: Laboratory Maniac in play, Consultation and Brainstorm in hand, Island + Swamp, a
     *  20-card library, our Main 1. Consultation empties the library and Brainstorm's draw wins the same turn. */
    @Test
    public void labThenConsultationThenBrainstormWins() {
        start(LAB_CONSULTATION);
        addCard(LAB, ai);
        hand(CONSULTATION);
        hand("Brainstorm");
        lands("Island", "Swamp");
        fillLibrary(ai, 20);
        playUntilPhase(PhaseType.END_OF_TURN);
        assertWonThisTurn();
        assertTrue(ai.getCardsIn(ZoneType.Library).isEmpty());
    }

    /** The finding's Consider: a Surveil root is no follow-up. Island + Swamp pay Consultation and Consider together,
     *  yet Consultation waits in our Main 1, since on the emptied library the AI does not cast Consider (SurveilAi:
     *  an instant only at the opponent's end step, and nothing to surveil). */
    @Test
    public void considerIsNoFollowUp() {
        final Card consultation = followUpBoard(LAB_CONSULTATION, LAB, "Consider", "Island", "Swamp");
        final Card surveil = t.find("Consider", ZoneType.Hand);
        assertEquals(castOf(surveil).getApi(), ApiType.Surveil);
        assertEquals(consider(consultation), AiPlayDecision.CantPlayAi);
        emptied(consultation);
        assertNotEquals(aiOf(ai).canPlaySa(castOf(surveil)), AiPlayDecision.WillPlay);
    }

    /** The finding's Thrill of Possibility: a cost with a part other than mana is no follow-up. Thrill the only card
     *  beside Consultation, Swamp + Mountain + Mountain paying both, Consultation waits: once it is cast nothing is
     *  left to discard, and the AI does not cast Thrill. */
    @Test
    public void aDrawSpellWithADiscardCostIsNoFollowUp() {
        final Card consultation = followUpBoard(LAB_CONSULTATION, LAB, "Thrill of Possibility", "Swamp", "Mountain",
                "Mountain");
        final Card thrill = t.find("Thrill of Possibility", ZoneType.Hand);
        assertFalse(castOf(thrill).getPayCosts().isOnlyManaCost());
        assertEquals(ai.getCardsIn(ZoneType.Hand).size(), 2);
        assertEquals(consider(consultation), AiPlayDecision.CantPlayAi);
        emptied(consultation);
        assertEquals(ai.getCardsIn(ZoneType.Hand).size(), 1);
        assertNotEquals(aiOf(ai).canPlaySa(castOf(thrill)), AiPlayDecision.WillPlay);
    }

    /** Every other way out of the follow-up shape, one spell each, the only one its board's spell takes (followsUp's
     *  list): Consultation waits in our Main 1 on a board whose lands pay it and the spell together, in mana value and
     *  in each colour's pips. Where the AI's own cast on the emptied library is why, it is shown: the AI does not cast
     *  that spell there. Abandon Attachments and Index would be cast and draw nothing; Demand Answers' costs and
     *  Inspiring Refrain's suspend come with the cast. */
    @Test
    public void aSpellOfAnotherShapeIsNoFollowUp() {
        Card s = assertNoFollowUp(LAB_CONSULTATION, LAB, "Crushing Disappointment", true, "Swamp", "Swamp", "Island",
                "Island", "Island");
        assertEquals(castOf(s).getApi(), ApiType.LoseLife); // a root that waits for Main 2
        s = assertNoFollowUp(LAB_CONSULTATION, LAB, "Careful Study", true, "Island", "Swamp");
        assertNotNull(castOf(s).findSubAbilityByType(ApiType.Discard)); // a loot with no hand left
        s = assertNoFollowUp(LAB_CONSULTATION, LAB, "Whispers of the Muse", true, "Island", "Swamp");
        assertEquals(castOf(s).getParam("AILogic"), "AtOppEOT"); // the card's own timing
        s = assertNoFollowUp(JACE_CONSULTATION, JACE, "Vision Skeins", true, "Island", "Island", "Swamp");
        assertEquals(castOf(s).getParam("Defined"), "Player"); // each player draws: not lifted with Jace
        s = assertNoFollowUp(JACE_CONSULTATION, JACE, "Collective Unconscious", true, "Swamp", "Forest", "Forest",
                "Forest", "Forest", "Forest", "Forest");
        assertEquals(castOf(s).getParam("NumCards"), "X"); // a creature count, none with Jace alone
        s = assertNoFollowUp(LAB_CONSULTATION, LAB, "Abandon Attachments", false, "Island", "Island", "Swamp");
        assertTrue(castOf(s).hasParam("UnlessCost")); // draws only off a discard
        s = assertNoFollowUp(LAB_CONSULTATION, LAB, "Demand Answers", false, "Swamp", "Mountain", "Mountain");
        assertTrue(castOf(s).getPayCosts().isOnlyManaCost()); // the keyword's costs come with the cast
        assertTrue(s.hasStartOfKeyword("AlternateAdditionalCost"));
        s = assertNoFollowUp(LAB_CONSULTATION, LAB, "Inspiring Refrain", false, "Swamp", "Island", "Island", "Island",
                "Island", "Island", "Island");
        assertEquals(castOf(s).getSubAbility().getParam("Origin"), "Stack"); // a ChangeZone not from our hand
        s = assertNoFollowUp(LAB_CONSULTATION, LAB, "Index", false, "Island", "Swamp");
        assertEquals(castOf(s).getApi(), ApiType.RearrangeTopOfLibrary); // no draw at all
        assertNull(castOf(s).getSubAbility());
    }

    /** Every follow-up shape is cast on the emptied library: Laboratory Maniac, Consultation and the spell, lands
     *  paying both; the window opens in our Main 1, and once Consultation has resolved off a Swamp the AI casts the
     *  spell. Opt (a Scry root), Serum Visions (a Draw root, a Scry part), Ponder (a RearrangeTopOfLibrary root),
     *  Brainstorm (a ChangeZone from our hand), Divination (a Draw alone) and Night's Whisper at 4 life (a LoseLife
     *  part, whose drawback floor refuses 2 + 3 > 4 but on the finishing draw). */
    @Test
    public void everyFollowUpShapeIsCastOnTheEmptiedLibrary() {
        assertFollowsUp("Opt", 20, "Island", "Swamp");
        assertFollowsUp("Serum Visions", 20, "Island", "Swamp");
        assertFollowsUp("Ponder", 20, "Island", "Swamp");
        assertFollowsUp("Brainstorm", 20, "Island", "Swamp");
        assertFollowsUp("Divination", 20, "Island", "Island", "Island", "Swamp");
        assertFollowsUp("Night's Whisper", 4, "Swamp", "Swamp", "Island");
    }

    /** A hinted spell of the follow-up shape is no follow-up without its readmit row: See Beyond (AI:RemoveDeck:All,
     *  no row), a Draw with a ChangeZone from our hand, with Island + Swamp + Island paying it and Consultation, is
     *  never offered to the AI, so Consultation waits; Opt opens the window on the same board. */
    @Test
    public void aHintedSpellOfTheFollowUpShapeIsNoFollowUp() {
        final Card consultation = followUpBoard(LAB_CONSULTATION, LAB, "See Beyond", "Island", "Swamp", "Island");
        final Card seeBeyond = t.find("See Beyond", ZoneType.Hand);
        assertTrue(ComputerUtilCard.isCardRemAIDeck(seeBeyond));
        assertFalse(SpecialCardAi.RemoveDeckFilter.readmit(ai, castOf(seeBeyond)));
        assertEquals(castOf(seeBeyond).getSubAbility().getParam("Origin"), "Hand");
        assertEquals(consider(consultation), AiPlayDecision.CantPlayAi);
        hand(OPT);
        assertEquals(consider(consultation), AiPlayDecision.WillPlay);
    }

    // ---- final review, round 1 ----

    @Test
    public void theLabWindowsTwoCostsTakeOneSourceAPip() {
        // Laboratory Maniac in play, Consultation and Opt in hand, our Main 1. Underground Sea and Sol Ring make three
        // mana, a blue and a black among them when counted source by source, but the Sea pays Opt's {U} or
        // Consultation's {B}, never both: no window, so Consultation does not exile the library with no draw to follow
        start(LAB_CONSULTATION);
        addCard(LAB, ai);
        final Card consultation = hand(CONSULTATION);
        hand(OPT);
        lands("Underground Sea", "Sol Ring");
        fillLibrary(ai, 20);
        assertEquals(ids(t.executable()), List.of("742-2836"));
        assertEquals(consider(consultation), AiPlayDecision.CantPlayAi);

        // an Island beside them pays Opt
        lands("Island");
        assertEquals(consider(consultation), AiPlayDecision.WillPlay);
    }

    @Test
    public void imprisonedInTheMoonOnDoomClosesItsWindow() {
        // the opponent's Imprisoned in the Moon (Doom's mana value 4 meets its AttachAITgts cmcGE3) makes our untapped
        // Doctor Doom a colorless land with "{T}: Add {C}" alone, never summoning sick: no draw wins, so Consultation is
        // not cast into a library it would only exile
        start(DOOM_CONSULTATION);
        final Card doom = ready(DOOM);
        final Card consultation = hand(CONSULTATION);
        lands("Swamp");
        fillLibrary(ai, 20);
        assertEquals(consider(consultation), AiPlayDecision.WillPlay);
        assertEquals(ComboExecute.floorExemption(ai, castOf(consultation)), "combo");

        addCard("Imprisoned in the Moon", opp).attachToEntity(doom, null);
        game.getAction().checkStateEffects(true);
        assertTrue(doom.isLand() && !doom.isCreature() && !doom.isTapped() && !doom.isSick());
        assertEquals(ComboTracker.nameOf(doom), DOOM);
        assertEquals(t.executable(), List.of());
        assertEquals(consider(consultation), AiPlayDecision.CantPlayAi);
        assertNull(ComboExecute.floorExemption(ai, castOf(consultation)));
    }

    @Test
    public void lignifyOnLabClosesItsWindowsAndItsFinishingDraw() {
        // Lignify: our Laboratory Maniac is a 0/4 Treefolk with no abilities. Neither the opponent's end step nor a
        // draw spell in hand opens a window, and on an emptied library a draw is no finishing draw
        start(LAB_CONSULTATION);
        final Card lab = addCard(LAB, ai);
        final Card consultation = hand(CONSULTATION);
        hand(OPT);
        lands("Island", "Swamp");
        fillLibrary(ai, 20);
        assertEquals(consider(consultation), AiPlayDecision.WillPlay);
        opponentsEndStep();
        assertEquals(consider(consultation), AiPlayDecision.WillPlay);

        addCard("Lignify", opp).attachToEntity(lab, null);
        game.getAction().checkStateEffects(true);
        assertTrue(lab.getReplacementEffects().isEmpty());
        assertEquals(t.executable(), List.of());
        assertEquals(consider(consultation), AiPlayDecision.CantPlayAi);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, ai);
        assertEquals(consider(consultation), AiPlayDecision.CantPlayAi);

        // the library emptied, Consultation spent: Opt draws into a loss, so the deck guard stands
        start(LAB_CONSULTATION);
        final Card spentLab = addCard(LAB, ai);
        addCardToZone(CONSULTATION, ai, ZoneType.Graveyard);
        final Card opt = hand(OPT);
        lands("Island");
        assertTrue(ai.getCardsIn(ZoneType.Library).isEmpty());
        assertTrue(ComboExecute.finishesLine(ai, castOf(opt)));
        addCard("Lignify", opp).attachToEntity(spentLab, null);
        game.getAction().checkStateEffects(true);
        assertFalse(ComboExecute.finishesLine(ai, castOf(opt)));
        assertEquals(aiOf(ai).canPlaySa(castOf(opt)), AiPlayDecision.CantPlayAi);
    }

    /** {@code winner} (line {@code row}) on our battlefield, Consultation and {@code spell} in our hand, {@code lands},
     *  a 20-card library, our Main 1: the line executable, and the lands paying Consultation and the spell together in
     *  mana value and in each colour's pips, as the window's own mana rule reads them. Returns Consultation. */
    private Card followUpBoard(final String row, final String winner, final String spell, final String... lands) {
        start(row);
        addCard(winner, ai);
        final Card consultation = hand(CONSULTATION);
        final Card s = hand(spell);
        lands(lands);
        fillLibrary(ai, 20);
        assertEquals(t.executable().size(), 1, spell);
        final SpecialCardAi.HonestMana mana = SpecialCardAi.HonestMana.of(ai, castOf(consultation), true);
        assertTrue(mana.total() >= consultation.getCMC() + s.getCMC(), spell + ": " + mana.total() + " mana");
        for (final byte colour : MagicColor.WUBRG) {
            final ManaCostShard pip = ManaCostShard.valueOf(colour);
            assertTrue(mana.colour(colour) >= consultation.getManaCost().getShardCount(pip)
                    + s.getManaCost().getShardCount(pip), spell + ": colour " + colour);
        }
        return consultation;
    }

    /** Consultation resolved off a Swamp: every card of our library exiled, Consultation in our graveyard, one
     *  untapped Swamp tapped for its {B}; the phase and the rest of the hand stay. */
    private void emptied(final Card consultation) {
        while (!ai.getCardsIn(ZoneType.Library).isEmpty()) {
            game.getAction().moveTo(ZoneType.Exile, ai.getCardsIn(ZoneType.Library).getFirst(), null,
                    AbilityKey.newMap());
        }
        game.getAction().moveTo(ZoneType.Graveyard, consultation, null, AbilityKey.newMap());
        for (final Card c : ai.getCardsIn(ZoneType.Battlefield)) {
            if ("Swamp".equals(c.getName()) && c.isUntapped()) {
                c.setTapped(true);
                return;
            }
        }
        throw new AssertionError("no untapped Swamp paid Consultation");
    }

    // followUpBoard with spell, then Consultation waits in our Main 1; with uncast, the AI does not cast the spell on
    // the library Consultation emptied either. Returns the spell's card.
    private Card assertNoFollowUp(final String row, final String winner, final String spell, final boolean uncast,
            final String... lands) {
        final Card consultation = followUpBoard(row, winner, spell, lands);
        final Card s = t.find(spell, ZoneType.Hand);
        assertEquals(consider(consultation), AiPlayDecision.CantPlayAi, spell);
        if (uncast) {
            emptied(consultation);
            assertNotEquals(aiOf(ai).canPlaySa(castOf(s)), AiPlayDecision.WillPlay, spell + " on the emptied library");
        }
        return s;
    }

    // followUpBoard with spell at life, then the window opens in our Main 1 and, on the library Consultation emptied,
    // the AI casts the spell
    private void assertFollowsUp(final String spell, final int life, final String... lands) {
        final Card consultation = followUpBoard(LAB_CONSULTATION, LAB, spell, lands);
        ai.setLife(life, null);
        final Card s = t.find(spell, ZoneType.Hand);
        assertEquals(consider(consultation), AiPlayDecision.WillPlay, spell);
        emptied(consultation);
        assertEquals(aiOf(ai).canPlaySa(castOf(s)), AiPlayDecision.WillPlay, spell + " on the emptied library");
    }

    // ---- follow-up F3: denial at the moment the line needs it ----

    /** Review Focus 2: under an opposing Teferi, Time Raveler, Oracle's trigger would sit on a stack where Consultation
     *  cannot be cast, so the go-off is not ready, though Oracle, Consultation and their mana are all here. Rule of
     *  Law, Archon of Emeria and Ethersworn Canonist bar the go-off's second spell; the opponent's Grand Abolisher bars
     *  nothing in our turn, nor Deafening Silence a creature and one noncreature spell. */
    @Test
    public void teferiStopsTheGoOff() {
        Card oracle = goOffBoard();
        assertTrue(ComboExecute.goOffNow(ai, castOf(oracle)));
        teferi();
        assertFalse(ComboExecute.goOffNow(ai, castOf(oracle)));
        assertEquals(t.executable(), List.of());

        for (final String hoser : List.of("Rule of Law", "Archon of Emeria", "Ethersworn Canonist")) {
            oracle = goOffBoard();
            addCard(hoser, opp);
            assertFalse(ComboExecute.goOffNow(ai, castOf(oracle)), hoser);
        }
        for (final String hoser : List.of("Grand Abolisher", "Deafening Silence")) {
            oracle = goOffBoard();
            addCard(hoser, opp);
            assertTrue(ComboExecute.goOffNow(ai, castOf(oracle)), hoser);
        }
    }

    /** The Laboratory Maniac window of a draw spell is now. Under an opposing Teferi, Time Raveler, or the opponent's
     *  Grand Abolisher, the line's own window, the opponent's end step, is barred, so the line is denied; yet in our
     *  Main 1 Consultation and then Opt are cast while we could cast a sorcery, in our own turn, so Consultation goes,
     *  and Tainted Pact alike, cast and resolving. At the opponent's end step neither goes. */
    @Test
    public void theDrawSpellWindowIsReadNow() {
        for (final String hoser : List.of(TEFERI, "Grand Abolisher")) {
            start(LAB_CONSULTATION, LAB_PACT);
            addCard(LAB, ai);
            final Card consultation = hand(CONSULTATION);
            final Card pact = hand(PACT);
            hand(OPT);
            lands("Island", "Swamp", "Swamp");
            singletonLibrary();
            assertEquals(ids(t.executable()), List.of("742-2836", "2836-3093"), hoser);
            if (TEFERI.equals(hoser)) {
                teferi();
            } else {
                addCard(hoser, opp);
            }
            assertEquals(t.executable(), List.of(), hoser);
            assertEquals(t.live(), List.of(), hoser);
            assertEquals(consider(consultation), AiPlayDecision.WillPlay, hoser);
            assertEquals(ComboExecute.pactCast(ai, castOf(pact)), Boolean.TRUE, hoser);
            assertTrue(ComboExecute.pactDeclines(ai, returnToHand(pact)), hoser);

            opponentsEndStep();
            assertEquals(consider(consultation), AiPlayDecision.CantPlayAi, hoser);
            assertNull(ComboExecute.pactCast(ai, castOf(pact)), hoser);
        }
    }

    /** End to end under an opposing Teferi, Time Raveler: Laboratory Maniac in play, Consultation and Brainstorm in
     *  hand, Island + Swamp, a 20-card library, our Main 1. Both are cast while we could cast a sorcery: Consultation
     *  empties the library and Brainstorm's draw wins the same turn. */
    @Test
    public void labThenConsultationThenBrainstormWinsUnderTeferi() {
        start(LAB_CONSULTATION);
        addCard(LAB, ai);
        hand(CONSULTATION);
        hand("Brainstorm");
        lands("Island", "Swamp");
        fillLibrary(ai, 20);
        teferi();
        assertEquals(t.live(), List.of());
        playUntilPhase(PhaseType.END_OF_TURN);
        assertWonThisTurn();
        assertTrue(ai.getCardsIn(ZoneType.Library).isEmpty());
    }

    /** Line 742-1295 in our Main 1: Oracle and Consultation in hand, Island, Island and Swamp, a 20-card library.
     *  Returns the Oracle. */
    private Card goOffBoard() {
        start(ORACLE_CONSULTATION);
        final Card oracle = hand(ORACLE);
        hand(CONSULTATION);
        lands("Island", "Island", "Swamp");
        fillLibrary(ai, 20);
        assertEquals(ids(t.executable()), List.of("742-1295"));
        return oracle;
    }

    /** The opponent's Teferi, Time Raveler with its four loyalty: "Each opponent can cast spells only any time they
     *  could cast a sorcery." */
    private Card teferi() {
        final Card c = addCard(TEFERI, opp);
        c.setCounters(CounterEnumType.LOYALTY, 4);
        return c;
    }

    // ---- follow-up F5: the go-off goes first ----

    /** Line 742-1295: {@code other} in hand ahead of Thassa's Oracle and Demonic Consultation, Island, Island and Swamp
     *  (the go-off's whole mana), a 20-card library; our Main 1, or our Main 2 when {@code main2}. */
    private void sequencerBoard(final String other, final boolean main2) {
        start(ORACLE_CONSULTATION);
        if (main2) {
            moveToMain2(game, ai);
        }
        hand(other);
        hand(ORACLE);
        hand(CONSULTATION);
        lands("Island", "Island", "Swamp");
        fillLibrary(ai, 20);
    }

    /** The name of the host of the spell the AI chooses to play now, or null when it plays nothing. */
    private String decision() {
        final List<SpellAbility> choice = aiOf(ai).chooseSpellAbilityToPlay();
        return choice == null || choice.isEmpty() ? null : ComboTracker.nameOf(choice.get(0).getHostCard());
    }

    /** Our Main 2, the go-off ready: Divination ({2}{U}) sorts with Thassa's Oracle ({U}{U}, and a creature's one) and
     *  comes first in hand, and the creature sort puts Wind Drake ahead of the Oracle whatever the hand's order. Stock
     *  casts either, spending the go-off's mana, and the Oracle is held again; the go-off goes first. From our Main 1
     *  the go-off goes first there (follow-up 2, G1: the Oracle's Main-2 wait is lifted), and Oracle and Consultation
     *  win the turn in Main 1, past the Divination. */
    @Test
    public void goOffCastsOracleBeforeAnotherSpell() {
        for (final String other : List.of("Divination", "Wind Drake")) {
            sequencerBoard(other, true);
            assertEquals(ids(t.executable()), List.of("742-1295"), other);
            assertTrue(ComboExecute.goOffNow(ai, castOf(t.find(ORACLE, ZoneType.Hand))), other);
            t.useLines(List.of());
            assertEquals(decision(), other, "with no line the other spell is not first");
            t.useLines(lines(ORACLE_CONSULTATION));
            assertEquals(decision(), ORACLE, other + " took the go-off's mana");
        }

        // our Main 1: the go-off is ready, so the creature's Main-2 wait (the stock rule) is lifted for the Oracle
        // (follow-up 2, G1), and the go-off is chosen there
        sequencerBoard("Divination", false);
        assertTrue(ComboExecute.goOffNow(ai, castOf(t.find(ORACLE, ZoneType.Hand))));
        assertEquals(decision(), ORACLE, "the go-off waited for Main 2");
        playUntilPhase(PhaseType.END_OF_TURN);
        assertWonThisTurn();
        assertEquals(game.getPhaseHandler().getPhase(), PhaseType.MAIN1, "the go-off waited past Main 1");
        assertTrue(ai.getCardsIn(ZoneType.Library).isEmpty());
        assertNotNull(t.find("Divination", ZoneType.Hand), "Divination was cast");
    }

    /** While the go-off is not ready (Consultation still in the library), the sequencer asks nothing that draws: the
     *  held Oracle's decision makes the stock decision's draws, those of the walk's one canPlayAndPayFor of the Oracle
     *  (the hold vetoes it after canPayCost), with no second evaluation of it ahead of the walk. */
    @Test
    public void aGoOffNotReadyCostsNoExtraDraws() {
        start(ORACLE_CONSULTATION);
        moveToMain2(game, ai);
        hand(ORACLE);
        library(CONSULTATION, "Island");
        lands("Island", "Island", "Swamp");
        assertEquals(ids(t.live()), List.of("742-1295"));
        assertFalse(ComboExecute.goOffNow(ai, castOf(t.find(ORACLE, ZoneType.Hand))));

        t.useLines(List.of());
        MyRandom.setRandom(new Random(7));
        long before = MyRandom.callCount();
        assertEquals(decision(), ORACLE);
        final long stock = MyRandom.callCount() - before;
        assertTrue(stock > 0, "the Oracle's evaluation draws nothing: no draw count to compare");
        t.useLines(lines(ORACLE_CONSULTATION));
        MyRandom.setRandom(new Random(7));
        before = MyRandom.callCount();
        assertNull(decision(), "the held Oracle was cast");
        assertEquals(MyRandom.callCount() - before, stock, "the draws moved");
    }

    /** The go-off is the Oracle's own cast, not whichever spell the go-off's mana would also pay: Ponder ({U}), first
     *  in hand, fits beside Demonic Consultation's {B} on Island, Island and Swamp as the Oracle's {U}{U} does, and the
     *  Oracle is still the spell chosen. */
    @Test
    public void goOffCastsTheOracleNotACheaperSpell() {
        sequencerBoard("Ponder", true);
        final Card ponder = t.find("Ponder", ZoneType.Hand);
        final Card consultation = t.find(CONSULTATION, ZoneType.Hand);
        assertEquals(ids(t.executable()), List.of("742-1295"));
        assertTrue(ComboMana.of(ai, castOf(ponder), true).pays(ponder.getManaCost(), consultation.getManaCost()),
                "the go-off's mana does not pay Ponder beside the Consultation");
        assertEquals(decision(), ORACLE, "a cheaper spell stood in for the Oracle");
    }

    /** The go-off goes ahead of the pre-casts after the walk too. Our Main 2: Air Elemental, High Tide, Thassa's Oracle
     *  and Demonic Consultation in hand, Island, Island, Island and Swamp. With no line the stock play is a pre-cast,
     *  High Tide in front of the four-drop its extra {U} newly pays (SpecialCardAi.HighTide.findPreCast); with the line
     *  the go-off is ready, and the Oracle is cast first. */
    @Test
    public void goOffBeatsAPreCast() {
        start(ORACLE_CONSULTATION);
        moveToMain2(game, ai);
        hand("Air Elemental");
        hand("High Tide");
        hand(ORACLE);
        hand(CONSULTATION);
        lands("Island", "Island", "Island", "Swamp");
        fillLibrary(ai, 20);
        assertEquals(ids(t.executable()), List.of("742-1295"));
        t.useLines(List.of());
        assertEquals(decision(), "High Tide", "without a line the stock pre-cast is not High Tide");
        t.useLines(lines(ORACLE_CONSULTATION));
        assertEquals(decision(), ORACLE, "a pre-cast spent the go-off's mana");
    }

    /** The go-off asks nothing of the walk first: with the line, the decision makes the same draws whether Divination
     *  stands in hand ahead of Thassa's Oracle and Demonic Consultation or not, those of the Oracle's own
     *  canPlayAndPayFor. A go-off asked after the walk would come after the walk's evaluation of the Divination. */
    @Test
    public void goOffAsksNothingBeforeItsOwnCast() {
        final long alone = goOffDraws(false);
        assertTrue(alone > 0, "the Oracle's cast draws nothing: no draw count to compare");
        assertEquals(goOffDraws(true), alone, "the walk ran before the go-off");
    }

    // the draws of the decision, the Oracle's cast, on line 742-1295's go-off board in our Main 2 (Island, Island and
    // Swamp, a 20-card library), with Divination in hand ahead of the Oracle and the Consultation when withDivination
    private long goOffDraws(final boolean withDivination) {
        start(ORACLE_CONSULTATION);
        moveToMain2(game, ai);
        if (withDivination) {
            hand("Divination");
        }
        hand(ORACLE);
        hand(CONSULTATION);
        lands("Island", "Island", "Swamp");
        fillLibrary(ai, 20);
        MyRandom.setRandom(new Random(7));
        final long before = MyRandom.callCount();
        assertEquals(decision(), ORACLE);
        return MyRandom.callCount() - before;
    }

    /** A deck holding no line (here, Laboratory Maniac's with no Laboratory Maniac) keeps the stock decision and its
     *  draws: the other spell first. */
    @Test
    public void goOffSequencerIsStockWithoutALine() {
        for (final String other : List.of("Divination", "Wind Drake")) {
            sequencerBoard(other, true);
            t.useLines(lines(LAB_CONSULTATION));
            assertEquals(t.deckLines(), List.of(), other);
            assertEquals(assertStockParity(this::decision, lines(LAB_CONSULTATION)), other);
        }
    }

    /** A mind-slave turn (Mindslaver; Opposition Agent's search runs the same way): a fresh PlayerControllerAi decides
     *  for us, and ComboTracker.of(ai) is null, so the go-off has no tracker to read. On the board whose go-off is ready
     *  the decision throws nothing and is the stock one, with the stock draws, whatever lines the master's fresh
     *  tracker holds; once control ends, the go-off goes first again. */
    @Test
    public void aControlledPlayerHasNoGoOff() {
        sequencerBoard("Divination", true);
        assertEquals(decision(), ORACLE);

        final long timestamp = game.getNextTimestamp();
        ai.addController(timestamp, opp);
        try {
            assertTrue(ai.isControlled());
            assertNull(ComboTracker.of(ai));
            final ComboTracker fresh = aiOf(ai).getComboTracker();
            fresh.useLines(List.of());
            MyRandom.setRandom(new Random(7));
            long before = MyRandom.callCount();
            final String stock = decision();
            final long stockDraws = MyRandom.callCount() - before;
            assertNotEquals(stock, ORACLE, "the stock decision is the go-off: this board tells nothing");
            fresh.useLines(lines(ORACLE_CONSULTATION));
            MyRandom.setRandom(new Random(7));
            before = MyRandom.callCount();
            assertEquals(decision(), stock, "the answer moved");
            assertEquals(MyRandom.callCount() - before, stockDraws, "the draws moved");
        } finally {
            ai.removeController(timestamp);
        }
        assertFalse(ai.isControlled());
        assertEquals(decision(), ORACLE);
    }

    // ---- follow-up final review, round 1: mana the payer never taps ----

    /** Line 742-1295 in our Main 1: Oracle and Consultation in hand, Island, Island and Lion's Eye Diamond, a 20-card
     *  library. The Diamond's "activate only as an instant" ability is playable now (canPlay), but the AI's payer
     *  never taps an instant-speed mana ability (ComputerUtilMana's getAIPlayableMana and canPayShardWithSpellAbility
     *  skip it), so Consultation's {B} cannot be paid. Returns the Oracle. */
    private Card diamondBoard() {
        start(ORACLE_CONSULTATION);
        final Card oracle = hand(ORACLE);
        final Card consultation = hand(CONSULTATION);
        lands("Island", "Island", "Lion's Eye Diamond");
        fillLibrary(ai, 20);
        assertEquals(ids(t.executable()), List.of("742-1295"));
        assertEquals(ids(t.live()), List.of("742-1295"));
        final SpellAbility diamond = t.find("Lion's Eye Diamond", ZoneType.Battlefield).getManaAbilities().get(0);
        diamond.setActivatingPlayer(ai);
        assertTrue(diamond.getRestrictions().isInstantSpeed());
        assertTrue(diamond.canPlay(), "the Diamond's mana ability is not playable now: this board tells nothing");
        assertFalse(ComputerUtilMana.canPayManaCost(castOf(consultation), ai, 0, false),
                "the payer taps the Diamond for Consultation's {B}");
        return oracle;
    }

    /** ComboMana counts no instant-speed-only mana: on the Diamond's board the go-off is not ready, so the Oracle is
     *  held, not cast first and spent as a 1/3 when Consultation's {B} finds no source at its trigger. With a Swamp
     *  added the go-off is ready. End to end, the held Oracle is still in hand at our end step, the library whole. */
    @Test
    public void instantSpeedManaPaysNoGoOff() {
        Card oracle = diamondBoard();
        assertFalse(ComboExecute.goOffNow(ai, castOf(oracle)), "the Diamond paid the go-off's {B}");
        assertTrue(ComboHold.vetoesCast(ai, castOf(oracle)), "the Oracle was not held");
        assertNull(ComboExecute.goOffFirst(ai, List.of(castOf(oracle))));
        playUntilPhase(PhaseType.END_OF_TURN);
        assertFalse(game.isGameOver());
        assertNotNull(t.find(ORACLE, ZoneType.Hand), "the Oracle was cast");
        assertNotNull(t.find(CONSULTATION, ZoneType.Hand));
        assertEquals(ai.getCardsIn(ZoneType.Library).size(), 20);

        oracle = diamondBoard();
        lands("Swamp");
        assertTrue(ComboExecute.goOffNow(ai, castOf(oracle)));
        assertFalse(ComboHold.vetoesCast(ai, castOf(oracle)));
        playUntilPhase(PhaseType.END_OF_TURN);
        assertWonThisTurn();
    }

    // ---- follow-up 2, G1: the go-off in Main 1 ----

    /** Line 742-1295 in our Main 1: {@code other} in hand ahead of Thassa's Oracle and Demonic Consultation, Island,
     *  Island, Swamp and Forest (the go-off's whole mana, and a Forest), a library of a Forest, an Island and eighteen
     *  Runeclaw Bears (basics a ramp spell can find); the go-off ready. */
    private void mainOneBoard(final String other) {
        start(ORACLE_CONSULTATION);
        hand(other);
        hand(ORACLE);
        hand(CONSULTATION);
        lands("Island", "Island", "Swamp", "Forest");
        library("Forest", "Island");
        fillLibrary(ai, 18);
        assertTrue(game.getPhaseHandler().is(PhaseType.MAIN1, ai));
        assertEquals(ids(t.executable()), List.of("742-1295"));
        assertTrue(ComboExecute.goOffNow(ai, castOf(t.find(ORACLE, ZoneType.Hand))));
    }

    /** Played out to our end step from mainOneBoard: Oracle and Consultation won the turn in our Main 1, the library
     *  emptied, {@code other} never cast. */
    private void assertWonInMainOne(final String other) {
        playUntilPhase(PhaseType.END_OF_TURN);
        assertWonThisTurn();
        assertEquals(game.getPhaseHandler().getPhase(), PhaseType.MAIN1, "the go-off waited past Main 1");
        assertTrue(ai.getCardsIn(ZoneType.Library).isEmpty());
        assertNotNull(t.find(other, ZoneType.Hand), other + " was cast");
    }

    /** Our Main 1, the go-off ready, a ramp spell in hand. Stock holds the Oracle for Main 2 (PermanentAi's wait) and
     *  casts Rampant Growth in Main 1: its {1}{G} takes the Forest and a go-off land, its basic enters tapped, and the
     *  go-off is gone for the turn. Cultivate's stock Main 1 is nothing (ChangeZoneAi keeps a search whose first
     *  destination is the library for Main 2), the Oracle held all the same. With the go-off ready the Oracle's wait is
     *  lifted: it is cast first, in Main 1, and Oracle and Consultation win the turn there. */
    @Test
    public void goOffCastsOracleInMainOneBeforeARampSpell() {
        mainOneBoard("Rampant Growth");
        t.useLines(List.of());
        assertEquals(decision(), "Rampant Growth", "the stock Main 1 is not the ramp spell: this board tells nothing");
        t.useLines(lines(ORACLE_CONSULTATION));
        assertEquals(decision(), ORACLE, "the ramp spell took the go-off's mana");
        assertWonInMainOne("Rampant Growth");

        mainOneBoard("Cultivate");
        assertEquals(decision(), ORACLE, "the go-off waited for Main 2");
        assertWonInMainOne("Cultivate");
    }

    /** The same with Carpet of Flowers, the opponent holding an Island (Carpet's own cast floor,
     *  SpecialCardAi.CarpetOfFlowers: it is worth the Islands of the opponent its trigger targets). Its PlayMain1 casts
     *  it in our Main 1 while the Oracle is held for Main 2; with the go-off ready the Oracle goes first, in Main 1. */
    @Test
    public void goOffInMainOneBeforeCarpetOfFlowers() {
        mainOneBoard("Carpet of Flowers");
        addCard("Island", opp);
        t.useLines(List.of());
        assertEquals(decision(), "Carpet of Flowers", "the stock Main 1 is not Carpet: this board tells nothing");
        t.useLines(lines(ORACLE_CONSULTATION));
        assertEquals(decision(), ORACLE, "Carpet of Flowers went first");
        assertWonInMainOne("Carpet of Flowers");
    }

    /** Stock parity for the Main-2 wait. Thassa's Oracle in hand, Demonic Consultation still in the library: the line
     *  is live, the go-off not ready, and in our Main 1 the Oracle's decision (canPlaySa: the creature's phase rule)
     *  and the AI's choice are the stock wait's, with the stock draws. On the go-off's ready board the wait stands for
     *  every other creature (Wind Drake, whose {2}{U} the go-off's mana pays beside Consultation's {B}), and for the
     *  Oracle of a deck holding no line (Laboratory Maniac's, with no Lab). */
    @Test
    public void mainTwoWaitStaysWithoutAGoOff() {
        start(ORACLE_CONSULTATION);
        final Card oracle = hand(ORACLE);
        library(CONSULTATION, "Island");
        lands("Island", "Island", "Swamp");
        fillLibrary(ai, 18);
        assertTrue(game.getPhaseHandler().is(PhaseType.MAIN1, ai));
        assertEquals(ids(t.live()), List.of("742-1295"));
        assertFalse(ComboExecute.goOffNow(ai, castOf(oracle)));
        assertEquals(assertStockParity(() -> aiOf(ai).canPlaySa(castOf(oracle)), lines(ORACLE_CONSULTATION)),
                AiPlayDecision.CantPlayAi);
        assertNull(assertStockParity(this::decision, lines(ORACLE_CONSULTATION)));

        mainOneBoard("Wind Drake");
        final Card drake = t.find("Wind Drake", ZoneType.Hand);
        assertTrue(ComboMana.of(ai, castOf(drake), true).pays(drake.getManaCost(),
                t.find(CONSULTATION, ZoneType.Hand).getManaCost()), "the go-off's mana does not pay the Drake");
        assertEquals(assertStockParity(() -> aiOf(ai).canPlaySa(castOf(drake)), lines(ORACLE_CONSULTATION)),
                AiPlayDecision.CantPlayAi);

        final Card held = t.find(ORACLE, ZoneType.Hand);
        assertEquals(assertStockParity(() -> aiOf(ai).canPlaySa(castOf(held)), lines(LAB_CONSULTATION)),
                AiPlayDecision.CantPlayAi);
        assertEquals(t.deckLines(), List.of());
    }

    // ---- follow-up 2, G1 review, round 1 ----

    /** Line 1295-3093 (Thassa's Oracle and Tainted Pact) in our Main 1: Carpet of Flowers, Oracle and Pact in hand,
     *  Island, Island, Swamp and Forest (Oracle's {U}{U} and Pact's {1}{B}, the go-off's whole mana), the opponent
     *  holding an Island (Carpet's cast floor), a library of fifteen distinct names (Pact's own condition). With no line
     *  the stock Main 1 is Tainted Pact, the stock cantrip (pactCantripsWithoutALine), on two of the go-off's lands.
     *  With the line Pact is held for its window, and Carpet, whose {G} takes the Forest that Pact's {1} needs, is the
     *  Main-1 play the Oracle's wait would leave to go first. The go-off is ready, and the Oracle's wait is lifted for a
     *  Pact line as for a Consultation one: Oracle is cast first, and Oracle and Pact win the turn in Main 1, the
     *  library exiled whole. */
    @Test
    public void goOffInMainOneOnAPactLine() {
        start(ORACLE_PACT);
        final Card carpet = hand("Carpet of Flowers");
        hand(ORACLE);
        final Card pact = hand(PACT);
        lands("Island", "Island", "Swamp", "Forest");
        singletonLibrary();
        addCard("Island", opp);
        assertTrue(game.getPhaseHandler().is(PhaseType.MAIN1, ai));
        assertEquals(ids(t.deckLines()), List.of("1295-3093"));
        assertEquals(ids(t.executable()), List.of("1295-3093"));
        assertTrue(ComboExecute.goOffNow(ai, castOf(t.find(ORACLE, ZoneType.Hand))));
        t.useLines(List.of());
        assertEquals(decision(), PACT, "the stock Main 1 is not Pact's cantrip: this board tells nothing");
        t.useLines(lines(ORACLE_PACT));
        assertEquals(ComboExecute.pactCast(ai, castOf(pact)), Boolean.FALSE, "Pact is not held for its window");
        assertEquals(aiOf(ai).canPlaySa(castOf(carpet)), AiPlayDecision.WillPlay,
                "Carpet of Flowers is no Main-1 play here: this board tells nothing");
        assertEquals(decision(), ORACLE, "Carpet of Flowers went first on the Pact line");
        assertWonInMainOne("Carpet of Flowers");
        assertEquals(ai.getCardsIn(ZoneType.Exile).size(), SINGLETON.size(), "Pact did not exile the library whole");
    }

    /** aControlledPlayerHasNoGoOff in our Main 1, where the lift reads the tracker: under a mind-slave turn
     *  ComboTracker.of(ai) is null, so the go-off has no tracker to read, and on the board whose go-off is ready the
     *  Oracle keeps the stock wait. Its decision (canPlaySa: the creature's phase rule) throws nothing and is CantPlayAi,
     *  and the AI's choice is the stock one, not the Oracle, with the stock draws, whatever lines the master's fresh
     *  tracker holds. canPlaySa is asked on the test's own thread: the AI's eval thread prints an exception and reads it
     *  as nothing to play. Once control ends, the go-off goes first in Main 1 again. */
    @Test
    public void aControlledPlayerHasNoGoOffInMainOne() {
        sequencerBoard("Divination", false);
        final Card oracle = t.find(ORACLE, ZoneType.Hand);
        assertTrue(game.getPhaseHandler().is(PhaseType.MAIN1, ai));
        assertEquals(decision(), ORACLE);

        final long timestamp = game.getNextTimestamp();
        ai.addController(timestamp, opp);
        try {
            assertTrue(ai.isControlled());
            assertNull(ComboTracker.of(ai));
            final ComboTracker fresh = aiOf(ai).getComboTracker();
            fresh.useLines(List.of());
            MyRandom.setRandom(new Random(7));
            long before = MyRandom.callCount();
            final AiPlayDecision stockWait = aiOf(ai).canPlaySa(castOf(oracle));
            final String stock = decision();
            final long stockDraws = MyRandom.callCount() - before;
            assertEquals(stockWait, AiPlayDecision.CantPlayAi, "a controlled player's Oracle was not held for Main 2");
            assertNotEquals(stock, ORACLE, "a controlled player's Oracle was cast in Main 1");
            fresh.useLines(lines(ORACLE_CONSULTATION));
            MyRandom.setRandom(new Random(7));
            before = MyRandom.callCount();
            assertEquals(aiOf(ai).canPlaySa(castOf(oracle)), stockWait, "the Oracle's wait moved");
            assertEquals(decision(), stock, "the answer moved");
            assertEquals(MyRandom.callCount() - before, stockDraws, "the draws moved");
        } finally {
            ai.removeController(timestamp);
        }
        assertFalse(ai.isControlled());
        assertEquals(decision(), ORACLE);
    }

    /** The lift's mana gate. Thassa's Oracle and Demonic Consultation both in hand and line 742-1295 executable, but
     *  Island, Island and Plains make no {B} beside the Oracle's {U}{U}: the go-off is not ready. In our Main 1 the
     *  Oracle's decision (canPlaySa: the creature's phase rule) is the stock wait, with the stock draws, and so is the
     *  AI's choice. */
    @Test
    public void mainTwoWaitStaysWithTheManaShort() {
        start(ORACLE_CONSULTATION);
        final Card oracle = hand(ORACLE);
        hand(CONSULTATION);
        lands("Island", "Island", "Plains");
        fillLibrary(ai, 20);
        assertTrue(game.getPhaseHandler().is(PhaseType.MAIN1, ai));
        assertEquals(ids(t.executable()), List.of("742-1295"));
        assertFalse(ComboExecute.goOffNow(ai, castOf(oracle)), "Island, Island and Plains paid Consultation's {B}");
        assertEquals(assertStockParity(() -> aiOf(ai).canPlaySa(castOf(oracle)), lines(ORACLE_CONSULTATION)),
                AiPlayDecision.CantPlayAi);
        assertNull(assertStockParity(this::decision, lines(ORACLE_CONSULTATION)));
    }

    // ---- follow-up 2, G3: taxes and Rhystic Study at the go-off ----

    private static final String THALIA = "Thalia, Guardian of Thraben";
    private static final String RHYSTIC = "Rhystic Study";

    /** Beyond the plan's tests: the Laboratory Maniac window of a draw spell reads both costs as the engine prices
     *  them. Under the opponent's Thalia, Guardian of Thraben, Demonic Consultation costs {1}{B} and Opt {1}{U}: Island
     *  and Swamp, which pay the printed {B} and {U}, open no window; a third land pays one taxed cost beside the other
     *  printed, not both; a fourth opens it. Tainted Pact resolving, its own mana paid: Opt's taxed {1}{U} needs two
     *  lands, where an Island paid its printed {U}. */
    @Test
    public void thaliaRaisesTheDrawSpellWindowsCosts() {
        start(LAB_CONSULTATION);
        addCard(LAB, ai);
        final Card consultation = hand(CONSULTATION);
        hand(OPT);
        lands("Island", "Swamp");
        fillLibrary(ai, 20);
        assertEquals(consider(consultation), AiPlayDecision.WillPlay);
        addCard(THALIA, opp);
        assertEquals(ids(t.executable()), List.of("742-2836"));
        assertEquals(consider(consultation), AiPlayDecision.CantPlayAi, "the window read the printed costs");
        lands("Island");
        assertEquals(consider(consultation), AiPlayDecision.CantPlayAi, "the window read a printed cost");
        lands("Swamp");
        assertEquals(consider(consultation), AiPlayDecision.WillPlay);

        start(LAB_PACT);
        addCard(LAB, ai);
        hand(OPT);
        lands("Island");
        singletonLibrary();
        final Card resolving = castOnStack(PACT);
        assertTrue(ComboExecute.pactDeclines(ai, returnToHand(resolving)));
        addCard(THALIA, opp);
        assertEquals(ids(t.executable()), List.of("2836-3093"));
        assertFalse(ComboExecute.pactDeclines(ai, returnToHand(resolving)), "the window read Opt's printed cost");
        lands("Swamp");
        assertTrue(ComboExecute.pactDeclines(ai, returnToHand(resolving)));
    }

    /** Lands of ours on our battlefield, tapped: mana spent already. */
    private void tapped(final String... names) {
        for (final String name : names) {
            addCard(name, ai).setTapped(true);
        }
    }

    /** Line 742-1295 in our Main 1, mid go-off: Thassa's Oracle cast and on our stack, its {U}{U} paid (our two
     *  Islands tapped), Demonic Consultation in hand, the Swamp and {@code more} untapped, a 20-card library. Returns the
     *  Oracle's spell. */
    private SpellAbility oracleCast(final String... more) {
        start(ORACLE_CONSULTATION);
        hand(CONSULTATION);
        tapped("Island", "Island");
        lands("Swamp");
        lands(more);
        fillLibrary(ai, 20);
        final SpellAbility oracle = castOnStack(ORACLE).getFirstSpellAbility();
        assertEquals(ids(t.executable()), List.of("742-1295"));
        return oracle;
    }

    /** The opponent's Rhystic Study on their battlefield, a 20-card library for them to draw from, and Rhystic's draw
     *  as its trigger resolves: "you may draw a card unless that player pays {1}", the opponent its activator. */
    private SpellAbility rhysticDraw() {
        fillLibrary(opp, 20);
        final Card rhystic = addCard(RHYSTIC, opp);
        for (final Trigger tr : rhystic.getTriggers()) {
            if (tr.getMode() == TriggerType.SpellCast) {
                final SpellAbility sa = tr.ensureAbility().copy(opp);
                sa.setTrigger(tr);
                assertEquals(sa.getApi(), ApiType.Draw);
                return sa;
            }
        }
        throw new AssertionError("no cast trigger on " + rhystic);
    }

    /** Whether we pay the unless cost of {@code sa} as it resolves, we its one payer
     *  (PlayerControllerAi.payCostToPreventEffect, AbilityUtils.handleUnlessCost's call). */
    private boolean paysUnless(final SpellAbility sa) {
        final Cost cost = AbilityUtils.calculateUnlessCost(sa, sa.getParam("UnlessCost"), true);
        return ((PlayerControllerAi) ai.getController()).payCostToPreventEffect(cost, sa, false,
                new PlayerCollection(ai));
    }

    /** On a board {@code board} builds, stock pays the unless cost it returns (no line), and on a second one built
     *  alike our own decision (the board's lines) declines it. */
    private void assertDeclined(final Supplier<SpellAbility> board) {
        SpellAbility sa = board.get();
        t.useLines(List.of());
        assertTrue(paysUnless(sa), "stock does not pay: this board tells nothing");
        sa = board.get();
        assertFalse(t.deckLines().isEmpty());
        assertFalse(paysUnless(sa), "the unless cost was paid");
    }

    /** Stock parity for an unless payment, on two boards {@code board} builds alike, the random stream seeded alike:
     *  the unless cost it returns is paid or not with no line and with the board's own lines, the same answer with the
     *  same draws. Returns the stock answer. */
    private boolean assertPaysAsStock(final Supplier<SpellAbility> board) {
        SpellAbility sa = board.get();
        t.useLines(List.of());
        MyRandom.setRandom(new Random(7));
        long before = MyRandom.callCount();
        final boolean stock = paysUnless(sa);
        final long stockDraws = MyRandom.callCount() - before;
        sa = board.get();
        assertFalse(t.deckLines().isEmpty());
        MyRandom.setRandom(new Random(7));
        before = MyRandom.callCount();
        assertEquals(paysUnless(sa), stock, "the answer moved");
        assertEquals(MyRandom.callCount() - before, stockDraws, "the draws moved");
        return stock;
    }

    /** Beyond the plan's tests, the decline itself, in both of a go-off's windows. The opponent's Rhystic Study asks
     *  {1} of us while our Thassa's Oracle is cast and on our stack, or while its entering trigger waits there, with
     *  Demonic Consultation in hand and only its {B} untapped: paid, Consultation could not follow. The AI declines,
     *  where stock pays. Tainted Pact's {1}{B} alike, with the Swamp and an Island untapped; and Consultation's {1}{B}
     *  under the opponent's Thalia, Guardian of Thraben, the engine's price, on that same Swamp and Island. */
    @Test
    public void rhysticStudyIsDeclinedInBothWindowsOfTheGoOff() {
        assertDeclined(() -> {
            oracleCast();
            return rhysticDraw();
        });
        assertDeclined(() -> {
            start(ORACLE_CONSULTATION);
            final Card oracle = hand(ORACLE);
            hand(CONSULTATION);
            tapped("Island", "Island");
            lands("Swamp");
            fillLibrary(ai, 20);
            enters(oracle);
            assertEquals(ids(t.executable()), List.of("742-1295"));
            return rhysticDraw();
        });
        assertDeclined(() -> {
            start(ORACLE_PACT);
            hand(PACT);
            tapped("Island", "Island");
            lands("Swamp", "Island");
            singletonLibrary();
            castOnStack(ORACLE);
            assertEquals(ids(t.executable()), List.of("1295-3093"));
            return rhysticDraw();
        });
        assertDeclined(() -> {
            oracleCast("Island");
            addCard(THALIA, opp);
            return rhysticDraw();
        });
    }

    /** Rhystic Study's {1} is paid as stock, with the stock draws, outside a go-off's window, where the go-off is not
     *  starved by it, and where it is lost already: the Oracle and Consultation in hand, nothing on our stack, only the
     *  Swamp untapped (a go-off not begun); the Oracle cast with an Island beside the Swamp, which pays the {1} and
     *  leaves the {B}; Consultation cast already, in response to the Oracle's trigger, a Swamp untapped; and the Oracle
     *  cast with only an Island untapped, Consultation's {B} gone whether the {1} is paid or not. */
    @Test
    public void rhysticStudyPaidAsStockOutsideAGoOff() {
        assertTrue(assertPaysAsStock(() -> {
            start(ORACLE_CONSULTATION);
            hand(ORACLE);
            hand(CONSULTATION);
            tapped("Island", "Island");
            lands("Swamp");
            fillLibrary(ai, 20);
            assertEquals(ids(t.executable()), List.of("742-1295"));
            return rhysticDraw();
        }), "not begun");
        assertTrue(assertPaysAsStock(() -> {
            oracleCast("Island");
            return rhysticDraw();
        }), "a mana to spare");
        assertTrue(assertPaysAsStock(() -> {
            start(ORACLE_CONSULTATION);
            final Card oracle = hand(ORACLE);
            tapped("Island", "Island", "Swamp");
            lands("Swamp");
            fillLibrary(ai, 20);
            enters(oracle);
            castOnStack(CONSULTATION);
            assertEquals(ids(t.executable()), List.of("742-1295"));
            return rhysticDraw();
        }), "Consultation cast");
        assertTrue(assertPaysAsStock(() -> {
            start(ORACLE_CONSULTATION);
            hand(CONSULTATION);
            tapped("Island", "Island", "Swamp");
            lands("Island");
            fillLibrary(ai, 20);
            castOnStack(ORACLE);
            assertEquals(ids(t.executable()), List.of("742-1295"));
            return rhysticDraw();
        }), "the go-off lost");
    }

    /** Beyond the plan's tests: inside the go-off's window, an unless cost that stops anything but an opponent's draw
     *  keeps the stock decision, with the stock draws. The opponent's Mana Leak on our Thassa's Oracle asks {3}: the
     *  Swamp and two Islands untapped pay it but leave no {B} for Demonic Consultation, and declined, the Oracle would be
     *  countered, the go-off lost all the same. A draw for us that {1} would stop (Rhystic Study's draw made ours,
     *  standing in for one), only the Swamp untapped: declined, we would draw, and on an empty library lose. And an
     *  opponent's untargeted effect that is no draw (a life gain of theirs that {1} would stop, built on their Rhystic
     *  Study, standing in for any other), only the Swamp untapped: the decline reads a draw alone. */
    @Test
    public void unlessCostsBeyondAnOpponentsDrawArePaidAsStock() {
        assertTrue(assertPaysAsStock(() -> {
            oracleCast();
            final Card host = addCard(RHYSTIC, opp);
            final SpellAbility gain = AbilityFactory.getAbility(
                    "DB$ GainLife | LifeAmount$ 1 | UnlessCost$ 1 | UnlessPayer$ TriggeredActivator", host);
            gain.setActivatingPlayer(opp);
            return gain;
        }), "an opponent's life gain");
        assertTrue(assertPaysAsStock(() -> {
            final SpellAbility oracle = oracleCast("Island", "Island");
            final Card leak = createCard("Mana Leak", opp);
            final SpellAbility counter = leak.getFirstSpellAbility();
            counter.setActivatingPlayer(opp);
            counter.getTargets().add(oracle);
            return counter;
        }), "Mana Leak");
        assertTrue(assertPaysAsStock(() -> {
            oracleCast();
            final Card rhystic = addCard(RHYSTIC, ai);
            for (final Trigger tr : rhystic.getTriggers()) {
                if (tr.getMode() == TriggerType.SpellCast) {
                    final SpellAbility sa = tr.ensureAbility().copy(ai);
                    sa.setTrigger(tr);
                    return sa;
                }
            }
            throw new AssertionError("no cast trigger on " + rhystic);
        }), "a draw for us");
    }
}
