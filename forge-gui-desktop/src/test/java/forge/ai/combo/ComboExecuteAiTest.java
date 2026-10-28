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
import forge.ai.AiCardMemory;
import forge.ai.AiController;
import forge.ai.AiPlayDecision;
import forge.ai.ComputerUtilCard;
import forge.ai.ComputerUtilMana;
import forge.ai.LobbyPlayerAi;
import forge.ai.PlayerControllerAi;
import forge.ai.SpecialCardAi;
import forge.ai.SpellAbilityAi;
import forge.card.MagicColor;
import forge.card.mana.ManaAtom;
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
import forge.game.keyword.Keyword;
import forge.game.mana.Mana;
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
    private static final String MEETING = "Meeting of Minds";
    private static final String REVERSE_ENGINEER = "Reverse Engineer";
    private static final String TREASURE_CRUISE = "Treasure Cruise";
    private static final String DIVINATION = "Divination";
    private static final String GREAT_HALL = "Great Hall of the Biblioplex";
    private static final String ARCANIST = "Vodalian Arcanist";
    private static final String SWORDS = "Swords to Plowshares";
    private static final String VISIONS = "Serum Visions";

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

        // a hand Pact, read once the resolving one is off the stack (follow-up 4, I1: a cast's Laboratory Maniac window
        // opens only on an empty stack): Island + Swamp pay Pact (the line is live) but not Pact and Opt together, so
        // it is held
        game.getStack().remove(game.getStack().peek());
        assertTrue(game.getStack().isEmpty());
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

        // Tainted Pact resolving, its own mana paid: a Swamp pays one mana, never Opt's {U}, so Pact keeps the card;
        // beside three Swamps an Island pays Opt, and the resolving Pact declines
        start(LAB_PACT);
        addCard(LAB, ai);
        hand(OPT);
        lands("Swamp");
        singletonLibrary();
        final Card resolving = castOnStack(PACT);
        assertEquals(ids(t.executable()), List.of("2836-3093"));
        assertFalse(ComboExecute.pactDeclines(ai, returnToHand(resolving)));
        lands("Swamp", "Swamp", "Island");
        assertTrue(ComboExecute.pactDeclines(ai, returnToHand(resolving)));
        // a hand Pact, on a board of its own (follow-up 4, I1: a cast's Laboratory Maniac window opens only on an
        // empty stack, never beside the resolving Pact): three Swamps pay {1}{B} and one more, never Opt's {U}; the
        // line is live, so Pact is held. An Island pays Opt: the hand Pact is cast into the window
        start(LAB_PACT);
        addCard(LAB, ai);
        hand(OPT);
        final Card handPact = hand(PACT);
        lands("Swamp", "Swamp", "Swamp");
        singletonLibrary();
        assertTrue(game.getStack().isEmpty());
        assertEquals(ids(t.live()), List.of("2836-3093"));
        assertEquals(ComboExecute.pactCast(ai, castOf(handPact)), Boolean.FALSE);
        lands("Island");
        assertEquals(ComboExecute.pactCast(ai, castOf(handPact)), Boolean.TRUE);
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

    /** An opponent's draw that {@code amount} would stop, "draw a card unless that player pays {amount}", the opponent
     *  its activator, built on their Rhystic Study as its trigger's draw is (standing in for Mystic Remora's {4} and
     *  Esper Sentinel's {X}), and a 20-card library for them to draw from. */
    private SpellAbility opponentsDrawUnless(final int amount) {
        fillLibrary(opp, 20);
        final Card host = addCard(RHYSTIC, opp);
        final SpellAbility draw = AbilityFactory.getAbility(
                "DB$ Draw | Defined$ You | UnlessCost$ " + amount + " | UnlessPayer$ TriggeredActivator", host);
        draw.setActivatingPlayer(opp);
        return draw;
    }

    /** Beyond the plan's tests (follow-up 2, G3 review, round 1): the unless cost's own amount decides the decline. Our
     *  Thassa's Oracle cast and on our stack, Demonic Consultation in hand, the Swamp and two Islands untapped: an
     *  opponent's draw that {1} would stop is paid as stock, the {1} and Consultation's {B} three mana; one that {3}
     *  would stop is declined where stock pays, the {3} and the {B} four mana; and with a third Island untapped the
     *  {3} is paid as stock, the four lands paying both. */
    @Test
    public void theUnlessCostsOwnAmountDecidesTheDecline() {
        assertTrue(assertPaysAsStock(() -> {
            oracleCast("Island", "Island");
            return opponentsDrawUnless(1);
        }), "{1} beside the {B}");
        assertDeclined(() -> {
            oracleCast("Island", "Island");
            return opponentsDrawUnless(3);
        });
        assertTrue(assertPaysAsStock(() -> {
            oracleCast("Island", "Island", "Island");
            return opponentsDrawUnless(3);
        }), "{3} beside the {B}");
    }

    /** Line 742-1295 in our Main 1, a go-off not begun: Thassa's Oracle and Demonic Consultation in hand, our two
     *  Islands tapped, only the Swamp untapped, a 20-card library. */
    private void notBegun() {
        start(ORACLE_CONSULTATION);
        hand(ORACLE);
        hand(CONSULTATION);
        tapped("Island", "Island");
        lands("Swamp");
        fillLibrary(ai, 20);
    }

    /** Line 742-1295 mid go-off as {@link #oracleCast} has it, only the Swamp untapped, after Opt, a noncreature spell
     *  of ours, was cast and resolved this turn; under the opponent's Deafening Silence when {@code silence}, which
     *  then bars Consultation this turn: this turn's go-off is lost, and the line waits for a later turn. */
    private void oracleCastAfterOpt(final boolean silence) {
        start(ORACLE_CONSULTATION);
        hand(CONSULTATION);
        tapped("Island", "Island");
        lands("Swamp");
        fillLibrary(ai, 20);
        if (silence) {
            addCard("Deafening Silence", opp);
        }
        final Card opt = castOnStack(OPT);
        game.getStack().clear();
        game.getStackZone().remove(opt);
        ai.getZone(ZoneType.Graveyard).add(opt);
        assertEquals(game.getStack().getSpellCardsCastThisTurn(), List.of(opt));
        castOnStack(ORACLE);
        assertEquals(ids(t.executable()), List.of("742-1295"));
        assertEquals(t.view().deniedThisTurn(t.executable().get(0)), silence, "barred this turn");
    }

    /** Rhystic Study's {1} is paid as stock, with the stock draws, outside a go-off's window, where the go-off is not
     *  starved by it, and where it is lost already: the Oracle and Consultation in hand, nothing on our stack, only the
     *  Swamp untapped (a go-off not begun); the Oracle cast with an Island beside the Swamp, which pays the {1} and
     *  leaves the {B}; Consultation cast already, in response to the Oracle's trigger, a Swamp untapped; and the Oracle
     *  cast with only an Island untapped, Consultation's {B} gone whether the {1} is paid or not. And (G3 review, round
     *  1) where no window is open though a line's pieces are near: over the go-off not begun, another spell of ours on
     *  our stack (Opt), and the opponent's own Thassa's Oracle on the stack, they its activator; our Oracle cast after
     *  Opt this turn under the opponent's Deafening Silence, which bars Consultation this turn (without Silence the same
     *  board declines); and our Oracle cast with Tainted Pact in hand and the Swamp and an Island untapped, over a
     *  library of twenty equal cards that Pact cannot empty, its line the deck's but not executable. */
    @Test
    public void rhysticStudyPaidAsStockOutsideAGoOff() {
        assertTrue(assertPaysAsStock(() -> {
            notBegun();
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
        assertTrue(assertPaysAsStock(() -> {
            notBegun();
            castOnStack(OPT);
            assertEquals(ids(t.executable()), List.of("742-1295"));
            return rhysticDraw();
        }), "Opt on our stack");
        assertTrue(assertPaysAsStock(() -> {
            notBegun();
            final Card theirs = createCard(ORACLE, opp);
            theirs.setGameTimestamp(game.getNextTimestamp());
            game.getStackZone().add(theirs);
            final SpellAbility cast = theirs.getFirstSpellAbility();
            cast.setActivatingPlayer(opp);
            game.getStack().add(cast);
            assertEquals(ids(t.executable()), List.of("742-1295"));
            return rhysticDraw();
        }), "their Oracle on the stack");
        assertDeclined(() -> {
            oracleCastAfterOpt(false);
            return rhysticDraw();
        });
        assertTrue(assertPaysAsStock(() -> {
            oracleCastAfterOpt(true);
            return rhysticDraw();
        }), "Silence after Opt");
        assertTrue(assertPaysAsStock(() -> {
            start(ORACLE_PACT);
            hand(PACT);
            tapped("Island", "Island");
            lands("Swamp", "Island");
            fillLibrary(ai, 20);
            castOnStack(ORACLE);
            assertEquals(ids(t.deckLines()), List.of("1295-3093"));
            assertEquals(ids(t.executable()), List.of(), "Pact cannot empty the library");
            return rhysticDraw();
        }), "Pact over a plain library");
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

    // ---- follow-up 3, H1: the pricer draws no random number ----

    /** Review Focus 1: a draw spell whose price the engine works out by asking the AI's controller is no follow-up,
     *  since pricing it (ComboExecute.priced, CostAdjustment.adjust) would ask: Meeting of Minds' convoke reaches
     *  PlayerControllerAi.chooseCardsForConvokeOrImprovise, which draws MyRandom where it holds likely blockers back.
     *  Line 742-2836 in our Main 1: Laboratory Maniac on our battlefield, Consultation and Meeting of Minds in hand, a
     *  Swamp and four Islands, the mana for both. The window does not open on Meeting of Minds: Consultation is not
     *  cast, nor exempt from the own-library floor. Its convoke gone, the window opens on it. */
    @Test
    public void aConvokeDrawIsNotAFollowUp() {
        final Card consultation = followUpBoard(LAB_CONSULTATION, LAB, MEETING, "Swamp", "Island", "Island", "Island",
                "Island");
        final Card meeting = t.find(MEETING, ZoneType.Hand);
        assertTrue(meeting.hasKeyword(Keyword.CONVOKE));
        assertEquals(consider(consultation), AiPlayDecision.CantPlayAi);
        assertNull(ComboExecute.floorExemption(ai, castOf(consultation)));
        assertOpensWithout(consultation, meeting, Keyword.CONVOKE);
    }

    /** The same deck in the opponent's Main 1 (Review Focus 1): Laboratory Maniac, an untapped creature of ours that
     *  Meeting of Minds' convoke could tap, and the opponent's Hill Giant, which can attack. There the engine's own
     *  price of Meeting of Minds draws: the convoke chooser holds likely blockers back, and
     *  ComputerUtilCard.getLikelyBlockers reaches ComputerUtilCombat.lifeInDanger's MyRandom. Evaluating Consultation
     *  (canPlaySa, on the test thread, inside the seeded block) gives the no-combo control's answer and leaves the next
     *  MyRandom values the control's, on a Swamp and two Islands (short of Consultation and Meeting of Minds together
     *  even with Laboratory Maniac convoked, so only the draws could move) and on a Swamp and four Islands (the mana for
     *  both, unconvoked). */
    @Test
    public void aConvokeDrawInHandDrawsNothing() {
        for (final int islands : List.of(2, 4)) {
            final String why = islands + " Islands";
            start(LAB_CONSULTATION);
            addCard(LAB, ai);
            final Card consultation = hand(CONSULTATION);
            final Card meeting = hand(MEETING);
            lands("Swamp");
            for (int i = 0; i < islands; i++) {
                lands("Island");
            }
            fillLibrary(ai, 20);
            addCard("Hill Giant", opp).setSickness(false);
            game.getPhaseHandler().devModeSet(PhaseType.MAIN1, opp);
            assertEquals(ids(t.executable()), List.of("742-2836"), why);

            final SpellAbility price = castOf(meeting);
            MyRandom.setRandom(new Random(7));
            final long before = MyRandom.callCount();
            ComputerUtilMana.calculateManaCost(price.getPayCosts(), price, ai, true, 0, false);
            assertTrue(MyRandom.callCount() > before, why + ": pricing Meeting of Minds draws nothing on this board");

            t.useLines(List.of());
            final List<Object> stock = nextValuesAfter(() -> aiOf(ai).canPlaySa(castOf(consultation)));
            t.useLines(lines(LAB_CONSULTATION));
            assertEquals(nextValuesAfter(() -> aiOf(ai).canPlaySa(castOf(consultation))), stock, why);
        }
    }

    /** Reverse Engineer's improvise (Review Focus 1), Millstone an untapped artifact of ours with no mana ability that
     *  it could tap, so its price asks the improvise chooser: line 742-2836 in our Main 1, a Swamp and five Islands, the
     *  mana for Consultation and Reverse Engineer together even unimprovised. The window does not open on it; its
     *  improvise gone, it does. */
    @Test
    public void anImproviseDrawIsNotAFollowUp() {
        final Card consultation = followUpBoard(LAB_CONSULTATION, LAB, REVERSE_ENGINEER, "Swamp", "Island", "Island",
                "Island", "Island", "Island");
        final Card millstone = addCard("Millstone", ai);
        assertTrue(millstone.isArtifact() && !millstone.isCreature() && millstone.isUntapped()
                && millstone.getManaAbilities().isEmpty());
        final Card engineer = t.find(REVERSE_ENGINEER, ZoneType.Hand);
        assertTrue(engineer.hasKeyword(Keyword.IMPROVISE));
        assertEquals(consider(consultation), AiPlayDecision.CantPlayAi);
        assertNull(ComboExecute.floorExemption(ai, castOf(consultation)));
        assertOpensWithout(consultation, engineer, Keyword.IMPROVISE);
    }

    /** Beyond the plan's tests: Treasure Cruise's delve asks the AI's controller too (chooseCardsToDelve, whose test
     *  mode also clears the host's delved cards), with a card in our graveyard to delve: line 742-2836 in our Main 1, a
     *  Swamp and eight Islands, the mana for both undelved. The window does not open on it; its delve gone, it does. */
    @Test
    public void aDelveDrawIsNotAFollowUp() {
        final Card consultation = followUpBoard(LAB_CONSULTATION, LAB, TREASURE_CRUISE, "Swamp", "Island", "Island",
                "Island", "Island", "Island", "Island", "Island", "Island");
        addCardToZone("Grizzly Bears", ai, ZoneType.Graveyard);
        final Card cruise = t.find(TREASURE_CRUISE, ZoneType.Hand);
        assertTrue(cruise.hasKeyword(Keyword.DELVE));
        assertEquals(consider(consultation), AiPlayDecision.CantPlayAi);
        assertNull(ComboExecute.floorExemption(ai, castOf(consultation)));
        assertOpensWithout(consultation, cruise, Keyword.DELVE);
    }

    /** Beyond the plan's tests: every other step of a spell's price that asks the AI's controller, or test-pays, refuses
     *  the follow-up too, though no card of the follow-up shape carries one today. Line 742-2836 in our Main 1, a Swamp
     *  and four Islands: Divination is the follow-up the window opens on, until it is given Assist
     *  (choosePlayerToAssistPayment, asked for its {2}), TapCreaturesForMana (the convoke chooser), an Announce$
     *  NumTimes (calculateManaCost test-pays it, canPayManaCost), a RaiseCost static of its own adding waterbend {1}
     *  as an additional cost (Water Whip's shape: the convoke chooser, waterbend {1} on top of its {2}{U}), or a
     *  Waterbend part printed in its own cost, which followsUp's mana-only read lets through (a CostWaterbend is a mana
     *  part), so only pricingAsks's read of the spell's own cost refuses it: waterbend {1} merged into its {2}{U}
     *  (Cost.add), and a Cost$ of Waterbend<3> alone, the one shape a script's parse keeps (it drops a mana string
     *  written beside a Waterbend part). */
    @Test
    public void aFollowUpWhosePriceAsksTheControllerIsRefused() {
        for (final String step : List.of("Assist", "TapCreaturesForMana", "NumTimes", "Waterbend", "PrintedWaterbend",
                "WaterbendOnly")) {
            final Card consultation = followUpBoard(LAB_CONSULTATION, LAB, DIVINATION, "Swamp", "Island", "Island",
                    "Island", "Island");
            final Card divination = t.find(DIVINATION, ZoneType.Hand);
            assertEquals(consider(consultation), AiPlayDecision.WillPlay, step);
            final SpellAbility spell = castOf(divination);
            if ("Assist".equals(step)) {
                divination.addChangedCardKeywords(List.of("Assist"), null, false, game.getNextTimestamp(), null);
                assertTrue(divination.hasKeyword(Keyword.ASSIST), step);
            } else if ("TapCreaturesForMana".equals(step)) {
                spell.putParam("TapCreaturesForMana", "True");
            } else if ("NumTimes".equals(step)) {
                spell.putParam("Announce", "NumTimes");
            } else if ("Waterbend".equals(step)) {
                divination.addStaticAbility("Mode$ RaiseCost | ValidCard$ Card.Self | Activator$ You | Type$ Spell"
                        + " | Cost$ Waterbend<1> | EffectZone$ All | Description$ As an additional cost to cast this"
                        + " spell, waterbend {1}.");
            } else {
                if ("PrintedWaterbend".equals(step)) {
                    spell.getPayCosts().add(new Cost("Waterbend<1>", false));
                    assertEquals(spell.getPayCosts().getMaxWaterbend(), "1", step);
                } else {
                    spell.setPayCosts(new Cost("Waterbend<3>", false));
                    assertEquals(spell.getPayCosts().getMaxWaterbend(), "3", step);
                }
                assertTrue(spell.getPayCosts().isOnlyManaCost(), step);
                assertTrue(divination.getStaticAbilities().isEmpty(), step);
            }
            assertEquals(consider(consultation), AiPlayDecision.CantPlayAi, step);
        }
    }

    /** Stock parity (Review Focus 1): a deck holding no line (Laboratory Maniac's, with no Laboratory Maniac) with
     *  Meeting of Minds in hand, Grizzly Bears of ours to convoke, four Islands and the opponent's Hill Giant, which
     *  can attack, makes the stock decision with the stock draws: at the opponent's end step it plays Meeting of Minds,
     *  and in their Main 1, where its price draws, nothing. */
    @Test
    public void aDeckWithNoLinePlaysMeetingOfMindsAsStock() {
        for (final PhaseType phase : List.of(PhaseType.END_OF_TURN, PhaseType.MAIN1)) {
            start(LAB_CONSULTATION);
            ready("Grizzly Bears");
            hand(MEETING);
            lands("Island", "Island", "Island", "Island");
            fillLibrary(ai, 20);
            addCard("Hill Giant", opp).setSickness(false);
            game.getPhaseHandler().devModeSet(phase, opp);
            assertEquals(t.deckLines(), List.of(), phase.name());
            assertEquals(assertStockParity(this::decision, lines(LAB_CONSULTATION)),
                    phase == PhaseType.END_OF_TURN ? MEETING : null, phase.name());
        }
    }

    // spell's keyword taken away, the window opens on it: Consultation is cast, exempt from the own-library floor
    private void assertOpensWithout(final Card consultation, final Card spell, final Keyword keyword) {
        spell.addChangedCardKeywords(null, List.of(keyword.toString()), false, game.getNextTimestamp(), null);
        assertFalse(spell.hasKeyword(keyword), spell + " keeps " + keyword);
        assertEquals(consider(consultation), AiPlayDecision.WillPlay, spell + " without " + keyword);
        assertEquals(ComboExecute.floorExemption(ai, castOf(consultation)), "combo", spell + " without " + keyword);
    }

    /** The decision's answer and the next three MyRandom values after it, the stream seeded alike (7). */
    private static List<Object> nextValuesAfter(final Supplier<?> decision) {
        MyRandom.setRandom(new Random(7));
        final Object answer = decision.get();
        final Random r = MyRandom.getRandom();
        return List.of(answer, r.nextInt(), r.nextInt(), r.nextInt());
    }

    // ---- follow-up 3, H3: the follow-up draw's own cast bar ----

    /** Review Focus 3: the draw spell that follows the emptier is cast right after it, so it must be castable there.
     *  Under the opponent's Rule of Law ("each player can't cast more than one spell each turn") Opt would be our
     *  turn's second spell, after Demonic Consultation: line 742-2836 in our Main 1 with no spell cast yet, Laboratory
     *  Maniac on our battlefield, Consultation and Opt in hand, Island and Swamp, a 20-card library. Consultation alone
     *  could be cast now (the line is not denied now, and it is executable), yet it is not cast, nor exempt from the
     *  own-library floor, and played through our turn the library is left whole. Without Rule of Law (the control) the
     *  window opens on Opt and the line wins in our Main 1. */
    @Test
    public void ruleOfLawBarsTheFollowUpDraw() {
        assertTheFollowUpDrawIsBarred("Rule of Law");
    }

    /** The same under the opponent's Deafening Silence ("each player can't cast more than one noncreature spell each
     *  turn"): the Laboratory Maniac on our battlefield was no spell of this turn, and Opt would be the turn's second
     *  noncreature spell, after Consultation. */
    @Test
    public void deafeningSilenceBarsTheFollowUpDraw() {
        assertTheFollowUpDrawIsBarred("Deafening Silence");
    }

    // ruleOfLawBarsTheFollowUpDraw's board: with no hoser the line wins in our Main 1; under the opponent's hoser
    // Consultation, castable now, waits, and our turn reaches its end step with the library whole
    private void assertTheFollowUpDrawIsBarred(final String hoser) {
        Card consultation = followUpBoard(LAB_CONSULTATION, LAB, OPT, "Island", "Swamp");
        assertEquals(consider(consultation), AiPlayDecision.WillPlay, "no hoser");
        assertEquals(ComboExecute.floorExemption(ai, castOf(consultation)), "combo", "no hoser");
        playUntilPhase(PhaseType.END_OF_TURN);
        assertWonThisTurn();

        consultation = followUpBoard(LAB_CONSULTATION, LAB, OPT, "Island", "Swamp");
        addCard(hoser, opp);
        assertEquals(game.getStack().getSpellCardsCastThisTurn(), List.of(), hoser);
        assertFalse(ComboSignals.deniedNow(t.view(), t.deckLines().get(0)), hoser + ": Consultation alone is castable");
        assertEquals(ids(t.executable()), List.of("742-2836"), hoser);
        assertEquals(consider(consultation), AiPlayDecision.CantPlayAi, hoser);
        assertNull(ComboExecute.floorExemption(ai, castOf(consultation)), hoser);
        playUntilPhase(PhaseType.END_OF_TURN);
        assertFalse(game.isGameOver(), hoser);
        assertEquals(game.getPhaseHandler().getPlayerTurn(), ai, hoser);
        assertNotNull(t.find(CONSULTATION, ZoneType.Hand), hoser + ": Consultation was cast");
        assertFalse(ai.getCardsIn(ZoneType.Library).isEmpty(), hoser);
    }

    /** Beyond the plan's tests (follow-up 3, H3): the draw's bar counts this turn's spells before it, the emptier
     *  among them once, still to be cast or cast already. Our own Fires of Invention ("you can cast no more than two
     *  spells each turn"), line 742-2836 in our Main 1: Laboratory Maniac on our battlefield, Consultation and Opt in
     *  hand, Island and Swamp. With no spell cast yet Consultation and then Opt are the turn's two, and the window
     *  opens; with an Opt cast this turn, Consultation could still be cast now, as the second, but the Opt in hand
     *  would be the third, and Consultation waits. So for Tainted Pact resolving (line 2836-3093, Pact cast this turn
     *  and on our stack): the Opt after it is the turn's second, and Pact declines the card it exiled; with an Opt cast
     *  before Pact, the third, and Pact keeps the card. */
    @Test
    public void aCapCountsThisTurnsSpellsBeforeTheDraw() {
        Card consultation = followUpBoard(LAB_CONSULTATION, LAB, OPT, "Island", "Swamp");
        addCard("Fires of Invention", ai);
        assertEquals(consider(consultation), AiPlayDecision.WillPlay, "no spell cast yet");

        consultation = followUpBoard(LAB_CONSULTATION, LAB, OPT, "Island", "Swamp");
        addCard("Fires of Invention", ai);
        castThisTurn(OPT);
        assertFalse(ComboSignals.deniedNow(t.view(), t.deckLines().get(0)), "Consultation would be the second");
        assertEquals(consider(consultation), AiPlayDecision.CantPlayAi, "an Opt cast");

        for (final boolean optFirst : List.of(false, true)) {
            final String why = optFirst ? "an Opt cast before Pact" : "Pact the turn's first";
            start(LAB_PACT);
            addCard(LAB, ai);
            hand(OPT);
            lands("Island", "Swamp", "Swamp");
            singletonLibrary();
            addCard("Fires of Invention", ai);
            if (optFirst) {
                castThisTurn(OPT);
            }
            final Card pact = castOnStack(PACT);
            assertEquals(game.getStack().getSpellCardsCastThisTurn().size(), optFirst ? 2 : 1, why);
            assertEquals(ComboExecute.pactDeclines(ai, returnToHand(pact)), !optFirst, why);
        }
    }

    /** Follow-up 3, H3 review, round 1: a barred draw spell is passed over, not the end of the window's search. Line
     *  742-2836 in our Main 1, Laboratory Maniac on our battlefield, Consultation and Opt in hand, Island, Swamp and
     *  Swamp, under the opponent's Iona, Shield of Emeria naming blue ("your opponents can't cast spells of the chosen
     *  color"): Consultation can be cast now, Opt can be cast at no moment, so with Opt the only draw spell
     *  Consultation is not cast. Night's Whisper put in our hand after Opt, the next draw spell in hand order, can
     *  follow, its {1}{B} and Consultation's {B} within the three lands: the window opens on it, and Consultation is
     *  cast, exempt from the own-library floor. */
    @Test
    public void aBarredDrawSpellPassesTheWindowToTheNext() {
        final Card consultation = followUpBoard(LAB_CONSULTATION, LAB, OPT, "Island", "Swamp", "Swamp");
        final Card iona = addCard("Iona, Shield of Emeria", opp);
        iona.setChosenColors(new ArrayList<>(List.of("blue")));
        game.getAction().checkStateEffects(true);
        assertFalse(ComboSignals.deniedNow(t.view(), t.deckLines().get(0)), "Consultation is castable");
        final Card opt = t.find(OPT, ZoneType.Hand);
        assertTrue(ComboSignals.drawBarredAt(t.view(), castOf(opt), ai, PhaseType.MAIN1, true, List.of(consultation)),
                "Iona bars Opt");
        assertEquals(consider(consultation), AiPlayDecision.CantPlayAi, "Opt alone");
        assertNull(ComboExecute.floorExemption(ai, castOf(consultation)), "Opt alone");

        final Card whisper = hand("Night's Whisper");
        assertFalse(ComboSignals.drawBarredAt(t.view(), castOf(whisper), ai, PhaseType.MAIN1, true,
                List.of(consultation)), "Night's Whisper can follow");
        assertTrue(ai.getCardsIn(ZoneType.Hand).indexOf(opt) < ai.getCardsIn(ZoneType.Hand).indexOf(whisper),
                "Opt is read first");
        assertEquals(consider(consultation), AiPlayDecision.WillPlay, "Night's Whisper after Opt");
        assertEquals(ComboExecute.floorExemption(ai, castOf(consultation)), "combo", "Night's Whisper after Opt");
    }

    /** Our {@code name} cast this turn and gone from the stack to our graveyard: the turn's record of its casts holds
     *  it (ComboFetchAiTest's castThisTurn). */
    private void castThisTurn(final String name) {
        final Card card = castOnStack(name);
        game.getStack().clear();
        game.getStackZone().remove(card);
        ai.getZone(ZoneType.Graveyard).add(card);
        assertTrue(game.getStack().isEmpty());
    }

    // ---- follow-up 3, H4: Rhystic Study's payment leaves the emptier's mana ----

    /** Green cards in {@code p}'s hand. The stock payer of a generic pip keeps, among sources of equal score, those of
     *  the colour the hand of the payment's activator needs most (ComputerUtilMana's sort, "keep access to more colors
     *  longer"); for Rhystic Study's draw that activator is the opponent, so a green hand of theirs keeps our Forest
     *  and spends our Swamp. */
    private void greenHand(final Player p) {
        for (final String name : List.of("Grizzly Bears", "Llanowar Elves", "Giant Growth")) {
            addCardToZone(name, p, ZoneType.Hand);
        }
    }

    private boolean held(final Card c) {
        return AiCardMemory.isRememberedCard(ai, c, AiCardMemory.MemorySet.HELD_MANA_SOURCES_FOR_NEXT_SPELL);
    }

    /** Review Focus 4 at the payment itself (follow-up 3, H4; beyond the plan's tests). Our Thassa's Oracle cast and on
     *  our stack, its {U}{U} paid, Demonic Consultation in hand, the Swamp and a Forest untapped, the opponent's Rhystic
     *  Study and a hand of green cards. The {1} leaves Consultation's {B} payable, so it is paid. With no line the stock
     *  payer pays it with the Swamp, keeping the Forest for the green hand it reads, and Consultation's {B} is gone.
     *  With the line it is paid from the Forest: the Swamp is held for Consultation while the {1} is paid. */
    @Test
    public void rhysticsOneIsPaidFromTheSpareLand() {
        oracleCast("Forest");
        greenHand(opp);
        SpellAbility rhystic = rhysticDraw();
        t.useLines(List.of());
        assertTrue(paysUnless(rhystic), "stock does not pay: this board tells nothing");
        assertTrue(t.find("Swamp", ZoneType.Battlefield).isTapped(),
                "stock kept the Swamp: this board tells nothing");
        assertFalse(t.find("Forest", ZoneType.Battlefield).isTapped());

        oracleCast("Forest");
        greenHand(opp);
        rhystic = rhysticDraw();
        assertTrue(paysUnless(rhystic), "the {1} was declined");
        assertFalse(t.find("Swamp", ZoneType.Battlefield).isTapped(), "the {1} took Consultation's Swamp");
        assertTrue(t.find("Forest", ZoneType.Battlefield).isTapped());
    }

    /** Review Focus 4 (follow-up 3, H4): the decline reads every line the go-off could take, and declines only when none
     *  keeps its emptier payable beside the unless cost. Lines 1295-3093 and 742-1295, in either order: our Thassa's
     *  Oracle's trigger on our stack, Demonic Consultation and Tainted Pact in hand, our Islands tapped, two Swamps
     *  untapped, a singleton library. Rhystic Study's {1} leaves Pact's {1}{B} unpayable but not Consultation's {B}: it
     *  is paid, as stock pays it (the answer and the draws), from one Swamp; and played on, Consultation answers the
     *  trigger from the other and wins the turn, Pact still in hand. */
    @Test
    public void aSurvivingLineIsPaidAsStock() {
        for (final List<String> rows : List.of(List.of(ORACLE_PACT, ORACLE_CONSULTATION),
                List.of(ORACLE_CONSULTATION, ORACLE_PACT))) {
            final String why = rows.get(0).equals(ORACLE_PACT) ? "Pact's line first" : "Consultation's line first";
            assertTrue(assertPaysAsStock(() -> {
                start(rows.toArray(new String[0]));
                final Card oracle = hand(ORACLE);
                hand(CONSULTATION);
                hand(PACT);
                tapped("Island", "Island");
                lands("Swamp", "Swamp");
                singletonLibrary();
                enters(oracle);
                assertEquals(ids(t.executable()).size(), 2, why);
                return rhysticDraw();
            }), why + ": the {1} was declined");
            assertEquals(untapped("Swamp"), 1L, why);
            playUntilPhase(PhaseType.END_OF_TURN);
            assertWonThisTurn();
            assertTrue(ai.getCardsIn(ZoneType.Library).isEmpty(), why);
            assertNotNull(t.find(PACT, ZoneType.Hand), why + ": Pact was cast");
        }
    }

    /** Follow-up 3, H4: the sources held for the emptier are held for the unless payment alone. On
     *  rhysticsOneIsPaidFromTheSpareLand's board, once the {1} is paid no card of ours is held for the next spell
     *  (AiCardMemory's HELD_MANA_SOURCES_FOR_NEXT_SPELL), the Swamp the payment left untapped among them. And (beyond the
     *  plan's tests) a source held before the payment, as Ride the Avalanche holds the sources of the spell it rides in
     *  front of, is held still after it, untapped: the payment releases only what it held. */
    @Test
    public void heldSourcesAreReleased() {
        oracleCast("Forest");
        greenHand(opp);
        assertTrue(paysUnless(rhysticDraw()));
        final Card swamp = t.find("Swamp", ZoneType.Battlefield);
        assertFalse(swamp.isTapped(), "the {1} took Consultation's Swamp");
        for (final Card c : ai.getCardsIn(ZoneType.Battlefield)) {
            assertFalse(held(c), c + " is held after the payment");
        }
        assertTrue(AiCardMemory.isMemorySetEmpty(ai, AiCardMemory.MemorySet.HELD_MANA_SOURCES_FOR_NEXT_SPELL));

        oracleCast("Forest", "Plains");
        greenHand(opp);
        final Card plains = t.find("Plains", ZoneType.Battlefield);
        AiCardMemory.rememberCard(ai, plains, AiCardMemory.MemorySet.HELD_MANA_SOURCES_FOR_NEXT_SPELL);
        assertTrue(paysUnless(rhysticDraw()));
        assertTrue(held(plains), "the payment released a hold it did not make");
        assertFalse(plains.isTapped(), "the held Plains paid");
        assertFalse(t.find("Swamp", ZoneType.Battlefield).isTapped(), "the {1} took Consultation's Swamp");
        assertFalse(held(t.find("Swamp", ZoneType.Battlefield)), "the Swamp is held after the payment");
    }

    /** Line 742-1295 mid go-off as {@link #oracleCast} has it, but with an Island untapped in place of the Swamp and
     *  {B} floating in our pool: Consultation's {B} and the {1} together come to the pool and the Island. */
    private Card floatingBlack() {
        return floating(ManaAtom.BLACK, "Swamp", "Island");
    }

    /** Line 742-1295 mid go-off: our Thassa's Oracle cast and on our stack, our two Islands tapped, Demonic Consultation
     *  in hand, {@code land} untapped, one mana of {@code colour} floating in our pool (made by a {@code maker} of
     *  ours), a 20-card library. Returns Consultation. */
    private Card floating(final int colour, final String maker, final String land) {
        start(ORACLE_CONSULTATION);
        final Card consultation = hand(CONSULTATION);
        tapped("Island", "Island");
        lands(land);
        fillLibrary(ai, 20);
        ai.getManaPool().addMana(new Mana((byte) colour, createCard(maker, ai), null, ai));
        castOnStack(ORACLE);
        assertEquals(ids(t.executable()), List.of("742-1295"));
        return consultation;
    }

    /** Beyond the plan's tests (follow-up 3, H4): the floating pool goes to the unless cost, as the AI's payer spends it
     *  first (ComputerUtilMana.payManaCost), so held lands cannot keep its mana for the emptier. On floatingBlack's board
     *  some assignment pays Consultation's {B} and Rhystic Study's {1} together (the pool's {B} and the Island), and
     *  with no line the {1} is paid from the pool: the Island stays untapped and the {B} is gone. With the line the {1}
     *  is declined, the {B} kept. */
    @Test
    public void floatingManaPaysTheUnlessCostFirst() {
        final Card consultation = floatingBlack();
        final SpellAbility rhystic = rhysticDraw();
        assertTrue(ComboMana.of(ai, castOf(consultation), true).pays(consultation.getManaCost(),
                ComputerUtilMana.calculateManaCost(AbilityUtils.calculateUnlessCost(rhystic,
                        rhystic.getParam("UnlessCost"), true), rhystic, ai, true, 0, true).toManaCost()),
                "no assignment pays both: this board tells nothing");
        t.useLines(List.of());
        assertTrue(paysUnless(rhystic), "stock does not pay: this board tells nothing");
        assertEquals(ai.getManaPool().getAmountOfColor(MagicColor.BLACK), 0,
                "stock kept the floating {B}: this board tells nothing");
        assertEquals(untapped("Island"), 1L, "stock tapped the Island");

        floatingBlack();
        assertFalse(paysUnless(rhysticDraw()), "the {1} took the floating {B}");
        assertEquals(ai.getManaPool().getAmountOfColor(MagicColor.BLACK), 1);
        assertEquals(untapped("Island"), 1L);
    }

    /** Beyond the plan's tests (follow-up 3, H4 review of its own mutants: the pool not counted for the unless cost
     *  survived the tests above): the floating pool pays the unless cost while the emptier's land is held. Our Thassa's
     *  Oracle cast and on our stack, Demonic Consultation in hand, the Swamp untapped and {U} floating: the Swamp is
     *  held for Consultation and the pool's {U} pays Rhystic Study's {1}, as stock pays it (the answer and the draws),
     *  the Swamp left untapped and the pool emptied. */
    @Test
    public void floatingManaPaysTheUnlessCostBesideAHeldLand() {
        assertTrue(assertPaysAsStock(() -> {
            floating(ManaAtom.BLUE, "Island", "Swamp");
            return rhysticDraw();
        }), "the {1} was declined");
        assertEquals(ai.getManaPool().totalMana(), 0, "the pool did not pay the {1}");
        assertEquals(untapped("Swamp"), 1L, "the {1} took Consultation's Swamp");
    }

    /** Our untapped lands, by name, in battlefield order (ComboMana reads its sources in that order). */
    private List<String> untappedLands() {
        return ai.getCardsIn(ZoneType.Battlefield).stream().filter(c -> c.isLand() && !c.isTapped())
                .map(Card::getName).collect(Collectors.toList());
    }

    /** Our first untapped card named {@code name} on our battlefield. */
    private Card untappedCard(final String name) {
        return ai.getCardsIn(ZoneType.Battlefield).stream().filter(c -> c.getName().equals(name) && !c.isTapped())
                .findFirst().orElseThrow(() -> new AssertionError("no untapped " + name));
    }

    /** Line 742-1295 mid go-off as {@link #oracleCast} has it, but with {@code before} untapped on our battlefield ahead
     *  of the Swamp. */
    private void oracleCastBehind(final String... before) {
        start(ORACLE_CONSULTATION);
        hand(CONSULTATION);
        tapped("Island", "Island");
        lands(before);
        lands("Swamp");
        fillLibrary(ai, 20);
        castOnStack(ORACLE);
        assertEquals(ids(t.executable()), List.of("742-1295"));
    }

    /** Beyond the plan's tests (follow-up 3, H4 review, round 1: with every source one kind, so that a hold could only
     *  be a prefix of the battlefield, every test above still passed, each listing the emptier's source first). The
     *  hold is searched by kind, not by battlefield order: rhysticsOneIsPaidFromTheSpareLand's board with the Forest on
     *  our battlefield before the Swamp. With no line the stock payer still pays Rhystic Study's {1} with the Swamp,
     *  keeping the Forest for the opponent's green hand. With the line the {1} is paid from the Forest, the Swamp is
     *  left untapped for Consultation, and after the payment no card of ours is held. */
    @Test
    public void rhysticsOneIsPaidFromASpareLandListedFirst() {
        oracleCastBehind("Forest");
        greenHand(opp);
        final SpellAbility rhystic = rhysticDraw();
        assertEquals(untappedLands(), List.of("Forest", "Swamp"), "not the Forest first");
        t.useLines(List.of());
        assertTrue(paysUnless(rhystic), "stock does not pay: this board tells nothing");
        assertEquals(untappedLands(), List.of("Forest"), "stock kept the Swamp: this board tells nothing");

        oracleCastBehind("Forest");
        greenHand(opp);
        assertTrue(paysUnless(rhysticDraw()), "the {1} was declined");
        assertEquals(untappedLands(), List.of("Swamp"), "the {1} took Consultation's Swamp");
        assertTrue(AiCardMemory.isMemorySetEmpty(ai, AiCardMemory.MemorySet.HELD_MANA_SOURCES_FOR_NEXT_SPELL),
                "a card is held after the payment");
    }

    /** Line 1295-3093 alone, mid go-off: our Thassa's Oracle's trigger on our stack (its {U}{U} paid, our two Islands
     *  tapped), Tainted Pact in hand, {@code open} untapped on our battlefield in that order, a singleton library, and
     *  the opponent's Rhystic Study and hand of green cards. Returns Rhystic's draw. */
    private SpellAbility pactAtTheTrigger(final String... open) {
        start(ORACLE_PACT);
        final Card oracle = hand(ORACLE);
        hand(PACT);
        tapped("Island", "Island");
        lands(open);
        singletonLibrary();
        greenHand(opp);
        enters(oracle);
        assertEquals(ids(t.executable()), List.of("1295-3093"));
        return rhysticDraw();
    }

    /** Beyond the plan's tests (follow-up 3, H4 review, round 1: no kept line held more than one source, so a hold
     *  searched among one source at most, and a payment holding only a hold's first source, passed every test above).
     *  Line 1295-3093 alone, Tainted Pact's {1}{B} beside Rhystic Study's {1}, three lands open, the opponent's hand
     *  green (pactAtTheTrigger):
     *  - an Island, a Swamp and a Forest open in that order: with only the Island held, as a payment holding a hold's
     *    first source alone would leave it, the payer keeps the Forest for the green hand and spends the Swamp, and
     *    Pact's {B} is gone. With the line the {1} is paid, as stock pays it (the answer and the draws), from the
     *    Forest, the Island and the Swamp untapped; no card of ours is held after the payment; and played on, Pact
     *    answers Oracle's trigger, empties the library and Oracle wins the turn;
     *  - a Swamp, an Island and a Forest: with no line the stock payer spends the Swamp, and Pact's {B} is gone. With
     *    the line the Swamp and the Island are held for Pact and the {1} is paid from the Forest. */
    @Test
    public void aPactLineHoldsBothOfItsSources() {
        SpellAbility rhystic = pactAtTheTrigger("Island", "Swamp", "Forest");
        t.useLines(List.of());
        AiCardMemory.rememberCard(ai, untappedCard("Island"), AiCardMemory.MemorySet.HELD_MANA_SOURCES_FOR_NEXT_SPELL);
        assertTrue(paysUnless(rhystic), "stock does not pay: this board tells nothing");
        assertEquals(untappedLands(), List.of("Island", "Forest"),
                "with the Island alone held, the {1} kept the Swamp: this board tells nothing");

        assertTrue(assertPaysAsStock(() -> pactAtTheTrigger("Island", "Swamp", "Forest")), "the {1} was declined");
        assertEquals(untappedLands(), List.of("Island", "Swamp"), "the {1} took a source of Pact's");
        assertTrue(AiCardMemory.isMemorySetEmpty(ai, AiCardMemory.MemorySet.HELD_MANA_SOURCES_FOR_NEXT_SPELL),
                "a card is held after the payment");
        playUntilPhase(PhaseType.END_OF_TURN);
        assertWonThisTurn();
        assertTrue(ai.getCardsIn(ZoneType.Library).isEmpty());
        assertNotNull(t.find(PACT, ZoneType.Graveyard), "Pact was not cast");

        rhystic = pactAtTheTrigger("Swamp", "Island", "Forest");
        t.useLines(List.of());
        assertTrue(paysUnless(rhystic), "stock does not pay: this board tells nothing");
        assertEquals(untappedLands(), List.of("Island", "Forest"), "stock kept the Swamp: this board tells nothing");
        rhystic = pactAtTheTrigger("Swamp", "Island", "Forest");
        assertTrue(paysUnless(rhystic), "the {1} was declined");
        assertEquals(untappedLands(), List.of("Swamp", "Island"), "the {1} took a source of Pact's");
    }

    /** Beyond the plan's tests (follow-up 3, H4 review, round 1: a hold taking at most one source of a kind passed every
     *  test above). Line 1295-3093 alone, our Thassa's Oracle cast and on our stack (its {U}{U} paid, our two Islands
     *  tapped), Tainted Pact in hand, three Swamps open, a singleton library: Rhystic Study's {1} leaves Pact's {1}{B}
     *  payable from two of the Swamps, so it is paid, as stock pays it (the answer and the draws), from the third; two
     *  Swamps are left untapped, and no card of ours is held after the payment. */
    @Test
    public void aPactLineHoldsTwoSourcesOfOneKind() {
        assertTrue(assertPaysAsStock(() -> {
            start(ORACLE_PACT);
            hand(PACT);
            tapped("Island", "Island");
            lands("Swamp", "Swamp", "Swamp");
            singletonLibrary();
            castOnStack(ORACLE);
            assertEquals(ids(t.executable()), List.of("1295-3093"));
            return rhysticDraw();
        }), "the {1} was declined");
        assertEquals(untapped("Swamp"), 2L, "the {1} took one of Pact's Swamps");
        assertTrue(AiCardMemory.isMemorySetEmpty(ai, AiCardMemory.MemorySet.HELD_MANA_SOURCES_FOR_NEXT_SPELL),
                "a card is held after the payment");
    }

    /** Beyond the plan's tests (follow-up 3, H4 review, round 1: a kind read from only one of its two readings passed
     *  every test above). Sources are one kind only when they make the same mana under both readings, the emptier's and
     *  the unless payment's, and a restricted source's mana differs between them. Our Thassa's Oracle cast and on our
     *  stack (its {U}{U} paid, our two Islands tapped):
     *  - line 742-1295, Demonic Consultation in hand, Wastes, Great Hall of the Biblioplex and a Forest open in that
     *    order: for Rhystic Study's draw both of the first two make {C} alone, but for Consultation the Great Hall also
     *    makes {B} (paying 1 life, its mana spent only on an instant or sorcery). The Great Hall is held for
     *    Consultation, and Rhystic's {1} is paid as stock pays it (the answer and the draws);
     *  - line 1295-3093, Tainted Pact in hand, a Swamp, Wastes and a Vodalian Arcanist free of summoning sickness open
     *    in that order, a singleton library: for Pact the Wastes and the Arcanist both make {C}, but the Arcanist's mana
     *    pays no draw of Rhystic's (instants and sorceries only). The Swamp and the Arcanist are held for Pact, and the
     *    {1} is paid as stock pays it (the answer and the draws), from the Wastes. */
    @Test
    public void aSourcesKindIsReadUnderBothReadings() {
        assertTrue(assertPaysAsStock(() -> {
            start(ORACLE_CONSULTATION);
            hand(CONSULTATION);
            tapped("Island", "Island");
            lands("Wastes", GREAT_HALL, "Forest");
            fillLibrary(ai, 20);
            castOnStack(ORACLE);
            assertEquals(ids(t.executable()), List.of("742-1295"));
            return rhysticDraw();
        }), "the {1} was declined");
        assertEquals(untapped(GREAT_HALL), 1L, "the {1} took Consultation's Great Hall");

        assertTrue(assertPaysAsStock(() -> {
            start(ORACLE_PACT);
            hand(PACT);
            tapped("Island", "Island");
            lands("Swamp", "Wastes");
            ready(ARCANIST);
            singletonLibrary();
            castOnStack(ORACLE);
            assertEquals(ids(t.executable()), List.of("1295-3093"));
            return rhysticDraw();
        }), "the {1} was declined");
        assertEquals(untappedLands(), List.of("Swamp"), "the {1} took Pact's Swamp");
        assertEquals(untapped(ARCANIST), 1L, "the {1} took Pact's Arcanist");
    }

    /** How many cards of ours named {@code name} are untapped on our battlefield. */
    private long untapped(final String name) {
        return ai.getCardsIn(ZoneType.Battlefield).stream().filter(c -> c.getName().equals(name) && !c.isTapped())
                .count();
    }

    // ---- follow-up 4, I1: the Lab/Jace windows wait for the stack ----

    /** The opponent's {@code name}, cast and on the stack, nothing paid or resolved, aimed at {@code target} (none for
     *  null), the opponent its activator: the stack's top, which it returns. */
    private SpellAbilityStackInstance theirSpell(final String name, final Card target) {
        final Card card = createCard(name, opp);
        card.setGameTimestamp(game.getNextTimestamp());
        game.getStackZone().add(card);
        final SpellAbility sa = card.getFirstSpellAbility();
        sa.setActivatingPlayer(opp);
        if (target != null) {
            sa.getTargets().add(target);
        }
        game.getStack().add(sa);
        final SpellAbilityStackInstance top = game.getStack().peek();
        assertEquals(top.getSourceCard(), card, name + " is not on the stack");
        assertEquals(top.getActivatingPlayer(), opp);
        return top;
    }

    /** Review Focus 1 (follow-up 4, I1; FOLLOWUP3.md's final review, ComboExecute.java:563), the reviewer's board: line
     *  742-2836 in our Main 1, Laboratory Maniac on our battlefield, Demonic Consultation and Serum Visions in hand,
     *  Island and Swamp, a 20-card library. On an empty stack the window opens (the control). With the opponent's
     *  Swords to Plowshares on Lab on the stack, the line still executable, Consultation is not cast as the emptier:
     *  Visions, a sorcery, could be cast only once the stack is empty, by when Swords has exiled Lab, and the emptied
     *  library would deck us. No window's WillPlay, nor a tutor's (the stock refusal), and no floor exemption. Swords
     *  gone, the stack empty, the window opens again. */
    @Test
    public void aSorceryFollowUpWaitsForTheStack() {
        assertTheFollowUpWaitsForTheStack(VISIONS, true);
    }

    /** The same with Opt, an instant, in place of Serum Visions: an instant could follow above Swords, yet the window
     *  waits for the stack all the same. */
    @Test
    public void anInstantFollowUpWaitsForTheStack() {
        assertTheFollowUpWaitsForTheStack(OPT, false);
    }

    // aSorceryFollowUpWaitsForTheStack's board, with spell (a sorcery or not) as the draw that follows
    private void assertTheFollowUpWaitsForTheStack(final String spell, final boolean sorcery) {
        final Card consultation = followUpBoard(LAB_CONSULTATION, LAB, spell, "Island", "Swamp");
        final Card lab = t.find(LAB, ZoneType.Battlefield);
        assertEquals(SpellAbilityAi.isSorcerySpeed(castOf(t.find(spell, ZoneType.Hand)), ai), sorcery, spell);
        assertEquals(game.getPhaseHandler().getPhase(), PhaseType.MAIN1);
        assertEquals(game.getPhaseHandler().getPlayerTurn(), ai);
        assertTrue(game.getStack().isEmpty());
        assertEquals(ComboExecute.consultation(ai, castOf(consultation)).decision(), AiPlayDecision.WillPlay,
                spell + ": no window on the empty stack");
        assertEquals(ComboExecute.floorExemption(ai, castOf(consultation)), "combo", spell + ", the empty stack");

        final SpellAbilityStackInstance swords = theirSpell(SWORDS, lab);
        assertEquals(ids(t.executable()), List.of("742-2836"), spell + ": the line is not executable above Swords");
        assertNull(ComboExecute.consultation(ai, castOf(consultation)), spell + ": Consultation approved above Swords");
        assertEquals(consider(consultation), AiPlayDecision.CantPlayAi, spell + " above Swords");
        assertNull(ComboExecute.floorExemption(ai, castOf(consultation)), spell + " above Swords");

        game.getStack().remove(swords);
        assertTrue(game.getStack().isEmpty());
        assertEquals(consider(consultation), AiPlayDecision.WillPlay, spell + ": the stack empty again");
        assertEquals(ComboExecute.floorExemption(ai, castOf(consultation)), "combo", spell + ", the stack empty again");
    }

    /** Review Focus 2 (follow-up 4, I1): at the opponent's end step, our turn next, the window needs no draw spell
     *  (our draw step follows), yet it waits for the stack. Laboratory Maniac (line 742-2836) on our battlefield with
     *  the opponent's Swords to Plowshares on it on the stack, and Jace, Wielder of Mysteries (line 742-3096) with the
     *  opponent's Opt on the stack, aimed at nothing of ours (any spell will do): Demonic Consultation in hand, a
     *  Swamp, a 20-card library, no draw spell. The line executable, Consultation is not cast, nor exempt from the
     *  floor; on the empty stack, before and after, the window opens. */
    @Test
    public void theEndStepWindowWaitsForTheStack() {
        assertTheEndStepWaits(LAB_CONSULTATION, LAB, "742-2836", SWORDS, true);
        assertTheEndStepWaits(JACE_CONSULTATION, JACE, "742-3096", OPT, false);
    }

    // theEndStepWindowWaitsForTheStack's board for one winner, with the opponent's spell on the stack, aimed at that
    // winner or at nothing
    private void assertTheEndStepWaits(final String row, final String winner, final String id, final String theirs,
            final boolean aimed) {
        start(row);
        final Card w = addCard(winner, ai);
        final Card consultation = hand(CONSULTATION);
        lands("Swamp");
        fillLibrary(ai, 20);
        opponentsEndStep();
        assertEquals(consider(consultation), AiPlayDecision.WillPlay, winner + ": no window on the empty stack");
        assertEquals(ComboExecute.floorExemption(ai, castOf(consultation)), "combo", winner + ", the empty stack");

        final SpellAbilityStackInstance spell = theirSpell(theirs, aimed ? w : null);
        assertEquals(ids(t.executable()), List.of(id), winner + ": the line is not executable above " + theirs);
        assertNull(ComboExecute.consultation(ai, castOf(consultation)), winner + ": Consultation approved above "
                + theirs);
        assertEquals(consider(consultation), AiPlayDecision.CantPlayAi, winner + " above " + theirs);
        assertNull(ComboExecute.floorExemption(ai, castOf(consultation)), winner + " above " + theirs);

        game.getStack().remove(spell);
        assertTrue(game.getStack().isEmpty());
        assertEquals(consider(consultation), AiPlayDecision.WillPlay, winner + ": the stack empty again");
    }

    /** Review Focus 1 for Tainted Pact (follow-up 4, I1): line 2836-3093 in our Main 1, Laboratory Maniac on our
     *  battlefield, Pact and Opt in hand, Island, Swamp and Swamp (Pact's {1}{B} and Opt's {U}), a singleton library.
     *  On the empty stack Pact is cast into the window and is exempt from the floor (the control). With the
     *  opponent's Swords to Plowshares on Lab on the stack, the line still executable and live, Pact is held, not
     *  cast, and not exempt. On the empty stack again it is cast. */
    @Test
    public void pactWaitsForTheStack() {
        start(LAB_PACT);
        final Card lab = addCard(LAB, ai);
        final Card pact = hand(PACT);
        hand(OPT);
        lands("Island", "Swamp", "Swamp");
        singletonLibrary();
        assertEquals(ids(t.executable()), List.of("2836-3093"));
        assertEquals(ComboExecute.pactCast(ai, castOf(pact)), Boolean.TRUE, "no window on the empty stack");
        assertEquals(ComboExecute.floorExemption(ai, castOf(pact)), "combo", "the empty stack");

        final SpellAbilityStackInstance swords = theirSpell(SWORDS, lab);
        assertEquals(ids(t.executable()), List.of("2836-3093"), "the line is not executable above Swords");
        assertEquals(ids(t.live()), List.of("2836-3093"));
        assertEquals(ComboExecute.pactCast(ai, castOf(pact)), Boolean.FALSE, "Pact cast above Swords");
        assertNull(ComboExecute.floorExemption(ai, castOf(pact)), "above Swords");

        game.getStack().remove(swords);
        assertTrue(game.getStack().isEmpty());
        assertEquals(ComboExecute.pactCast(ai, castOf(pact)), Boolean.TRUE, "the stack empty again");
    }

    /** Review Focus 3 (follow-up 4, I1): Tainted Pact resolving is still the stack's top (MagicStack.resolveStack
     *  removes it only when it finishes), and its own instance does not close the window. Line 2836-3093 in our Main
     *  1, Laboratory Maniac on our battlefield, Opt in hand, an Island for it, a singleton library. Pact the stack's
     *  only instance: it declines the card it exiled, through the stock route too. With the opponent's Swords to
     *  Plowshares on Lab beneath Pact: it keeps the card, as stock does. Swords gone, Pact alone again: it declines. */
    @Test
    public void resolvingPactStillDeclines() {
        start(LAB_PACT);
        addCard(LAB, ai);
        hand(OPT);
        lands("Island");
        singletonLibrary();
        Card pact = castOnStack(PACT);
        assertEquals(game.getStack().size(), 1);
        assertTrue(ComboExecute.pactDeclines(ai, returnToHand(pact)), "Pact alone on the stack");
        assertFalse(aiOf(ai).confirmAction(returnToHand(pact), null, "", null), "Pact alone on the stack");

        start(LAB_PACT);
        final Card lab = addCard(LAB, ai);
        hand(OPT);
        lands("Island");
        singletonLibrary();
        final SpellAbilityStackInstance swords = theirSpell(SWORDS, lab);
        pact = castOnStack(PACT);
        assertEquals(game.getStack().size(), 2);
        assertEquals(game.getStack().peek().getSourceCard(), pact, "Pact is not the stack's top");
        assertEquals(ids(t.executable()), List.of("2836-3093"), "the line is not executable above Swords");
        assertFalse(ComboExecute.pactDeclines(ai, returnToHand(pact)), "Swords beneath Pact");
        assertTrue(aiOf(ai).confirmAction(returnToHand(pact), null, "", null), "Swords beneath Pact");

        game.getStack().remove(swords);
        assertEquals(game.getStack().size(), 1);
        assertTrue(ComboExecute.pactDeclines(ai, returnToHand(pact)), "Pact alone again");
    }

    /** Stock parity (follow-up 4, I1): the reviewer's board with Opt (Laboratory Maniac on our battlefield, Demonic
     *  Consultation and Opt in hand, Island and Swamp, our Main 1) and the opponent's Swords to Plowshares on Lab on
     *  the stack. A deck holding no line (line 1295-3093's Oracle and Pact are not ours) decides as stock does, answer
     *  and draws; and so, beyond the plan, does the deck holding Lab's line, whose window the stack keeps shut. */
    @Test
    public void anOpposingSpellOnTheStackKeepsTheStockDecision() {
        final Card consultation = followUpBoard(LAB_CONSULTATION, LAB, OPT, "Island", "Swamp");
        theirSpell(SWORDS, t.find(LAB, ZoneType.Battlefield));
        t.useLines(lines(ORACLE_PACT));
        assertEquals(t.deckLines(), List.of());
        assertEquals(assertStockParity(() -> consider(consultation), lines(ORACLE_PACT)), AiPlayDecision.CantPlayAi);
        assertEquals(assertStockParity(() -> consider(consultation), lines(LAB_CONSULTATION)),
                AiPlayDecision.CantPlayAi);
    }
}
