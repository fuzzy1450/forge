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
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import org.testng.annotations.Test;

import forge.ai.AITest;
import forge.ai.AiController;
import forge.ai.AiPlayDecision;
import forge.ai.ComputerUtil;
import forge.ai.ComputerUtilAbility;
import forge.ai.LobbyPlayerAi;
import forge.ai.PlayerControllerAi;
import forge.ai.SpecialCardAi;
import forge.ai.SpellApiToAi;
import forge.ai.combo.ComboTracker.Where;
import forge.card.MagicColor;
import forge.game.Game;
import forge.game.ability.AbilityFactory;
import forge.game.ability.AbilityKey;
import forge.game.ability.AbilityUtils;
import forge.game.ability.ApiType;
import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.card.CounterEnumType;
import forge.game.cost.Cost;
import forge.game.cost.CostExile;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import forge.util.MyRandom;

/**
 * Holding and protecting (spec 2026-10-22-combo-aware-ai-core-design.md sections 6.2, 6.4 and 6.5): our own hand cast
 * of Thassa's Oracle waits while a live line needs it, until the go-off is ready (its emptier in hand and the mana for
 * both); a live line's piece is not discarded or pitched while another card can go instead, and a pitch whose only
 * fodder is a piece is refused. A dead or abandoned line releases the hold, and a deck holding no line keeps the stock
 * decisions and draws. Unless a test says otherwise the AI plays the Default profile in its own MAIN2, with no land in
 * hand.
 */
public class ComboHoldAiTest extends AITest {
    private static final String WIN = "F|2|S|Win the game";
    private static final String EXILE = "F|88|H|Exile your library";
    private static final String ORACLE_CONSULTATION = "V|742-1295|3|{U}{U}{B}|2,88|H:Demonic Consultation|H:Thassa's Oracle";
    private static final String PACT_ORACLE = "V|1295-3093|4|{1}{U}{U}{B}|2|H:Tainted Pact|H:Thassa's Oracle";
    private static final String LAB_CONSULTATION = "V|742-2836|1|{B}|2,88|H:Demonic Consultation|B:Laboratory Maniac";

    private static final String ORACLE = "Thassa's Oracle";
    private static final String CONSULTATION = "Demonic Consultation";
    private static final String PACT = "Tainted Pact";
    private static final String LAB = "Laboratory Maniac";
    private static final String WURM = "Craw Wurm";
    private static final String BRAINSTORM = "Brainstorm";
    private static final String FORCE = "Force of Will";
    private static final String TEFERI = "Teferi, Time Raveler";

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

    private static List<String> names(final Iterable<Card> cards) {
        final List<String> out = new ArrayList<>();
        for (final Card c : cards) {
            out.add(ComboTracker.nameOf(c));
        }
        return out;
    }

    /** A two-player game whose AI (player 1, on the Default profile, in its own MAIN2) tracks {@code rows}; no card
     *  placed yet. */
    private void start(final String... rows) {
        game = initAndCreateGame();
        ai = game.getPlayers().get(1);
        opp = game.getPlayers().get(0);
        ((LobbyPlayerAi) ai.getLobbyPlayer()).setAiProfile("Default");
        t = aiOf(ai).getComboTracker();
        t.useLines(lines(rows));
        moveToMain2(game, ai);
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

    /** Line 742-1295: Thassa's Oracle in hand, Demonic Consultation and an Island in the library, Island, Island and
     *  Swamp in play (the line's whole cost). Returns the Oracle. */
    private Card heldBoard() {
        start(ORACLE_CONSULTATION);
        final Card oracle = hand(ORACLE);
        library(CONSULTATION, "Island");
        lands("Island", "Island", "Swamp");
        return oracle;
    }

    /** {@code c}'s first spell, with its owner as its activator. */
    private static SpellAbility castOf(final Card c) {
        final SpellAbility sa = c.getFirstSpellAbility();
        sa.setActivatingPlayer(c.getOwner());
        return sa;
    }

    /** The host of the spell the AI chooses to play now, or null when it plays nothing. */
    private Card decision() {
        final List<SpellAbility> choice = aiOf(ai).chooseSpellAbilityToPlay();
        return choice == null || choice.isEmpty() ? null : choice.get(0).getHostCard();
    }

    /** The stock discard of one card from {@code cards} (a fresh list each call: the chooser removes what it picks). */
    private List<String> discard(final Card... cards) {
        return names(aiOf(ai).getCardsToDiscard(1, 1, new CardCollection(Arrays.asList(cards)), null));
    }

    /** Force of Will's spell whose cost has a CostExile: its alternative cost, read as the AI's own list reads it
     *  (ComputerUtilAbility.getOriginalAndAltCostAbilities over card.getAllSpellAbilities(), which alone holds only
     *  the mana-cost spell). */
    private SpellAbility forceAlt(final Card force) {
        for (final SpellAbility sa : ComputerUtilAbility.getOriginalAndAltCostAbilities(
                new ArrayList<>(force.getAllSpellAbilities()), ai)) {
            if (sa.getPayCosts().hasSpecificCostType(CostExile.class)) {
                return sa;
            }
        }
        throw new AssertionError("no spell of Force of Will exiles a card");
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
    public void oracleHeldWhileConsultationIsInTheLibrary() {
        final Card oracle = heldBoard();
        // stock casts the 1/3 in Main 2
        t.useLines(List.of());
        assertSame(decision(), oracle);

        t.useLines(lines(ORACLE_CONSULTATION));
        assertEquals(ids(t.live()), List.of("742-1295"));
        assertTrue(ComboHold.vetoesCast(ai, castOf(oracle)));
        assertNull(aiOf(ai).chooseSpellAbilityToPlay());
    }

    @Test
    public void oracleCastWhenTheGoOffIsReady() {
        start(ORACLE_CONSULTATION);
        final Card oracle = hand(ORACLE);
        hand(CONSULTATION);
        library("Runeclaw Bear", "Grizzly Bears", "Hill Giant");
        lands("Island", "Island", "Swamp");
        assertEquals(ids(t.executable()), List.of("742-1295"));
        assertTrue(ComboExecute.goOffNow(ai, castOf(oracle)));

        assertFalse(ComboHold.vetoesCast(ai, castOf(oracle)));
        final List<SpellAbility> choice = aiOf(ai).chooseSpellAbilityToPlay();
        assertNotNull(choice);
        assertEquals(ComboTracker.nameOf(choice.get(0).getHostCard()), ORACLE);
    }

    /** Line 742-1295 with Oracle and Consultation in hand and an Island to draw, {@code inPlay} on the battlefield.
     *  Returns the Oracle. */
    private Card goOffBoard(final String... inPlay) {
        start(ORACLE_CONSULTATION);
        final Card oracle = hand(ORACLE);
        hand(CONSULTATION);
        library("Island");
        lands(inPlay);
        return oracle;
    }

    @Test
    public void manaOneShortHolds() {
        // Review Focus 3. Island + Island: two mana for Oracle's two and Consultation's one. The line stays live (the
        // Island to draw makes next turn's three), so Oracle waits rather than come down as a 1/3.
        Card oracle = goOffBoard("Island", "Island");
        assertEquals(ids(t.live()), List.of("742-1295"));
        assertFalse(ComboExecute.goOffNow(ai, castOf(oracle)));
        assertTrue(ComboHold.vetoesCast(ai, castOf(oracle)));
        assertNull(aiOf(ai).chooseSpellAbilityToPlay());

        // Island + Swamp + Swamp: three mana, one of it blue
        oracle = goOffBoard("Island", "Swamp", "Swamp");
        assertEquals(ids(t.live()), List.of("742-1295"));
        assertFalse(ComboExecute.goOffNow(ai, castOf(oracle)));
        assertTrue(ComboHold.vetoesCast(ai, castOf(oracle)));
        assertNull(aiOf(ai).chooseSpellAbilityToPlay());
    }

    @Test
    public void deadLineReleasesTheHold() {
        // Review Focus 5
        final Card oracle = heldBoard();
        assertTrue(ComboHold.vetoesCast(ai, castOf(oracle)));

        game.getAction().moveTo(ZoneType.Exile, t.find(CONSULTATION, ZoneType.Library), null, AbilityKey.newMap());
        assertEquals(t.where(CONSULTATION), Where.GONE);
        assertEquals(t.live(), List.of());
        assertFalse(ComboHold.vetoesCast(ai, castOf(oracle)));
        // Oracle is an ordinary creature again
        assertSame(decision(), oracle);
    }

    @Test
    public void abandonReleasesTheHold() {
        final Card oracle = heldBoard();
        assertTrue(ComboHold.vetoesCast(ai, castOf(oracle)));

        addCard("Platinum Angel", opp);
        assertTrue(ai.cantWin());
        assertEquals(t.live(), List.of());
        assertFalse(ComboHold.vetoesCast(ai, castOf(oracle)));
    }

    @Test
    public void discardSparesThePiece() {
        start(ORACLE_CONSULTATION);
        final Card oracle = hand(ORACLE);
        final Card wurm = hand(WURM);
        library(CONSULTATION);
        // six lands: Craw Wurm is castable within reach, so stock discards the worse card, the Oracle
        lands("Island", "Island", "Swamp", "Swamp", "Swamp", "Swamp");
        t.useLines(List.of());
        assertEquals(discard(oracle, wurm), List.of(ORACLE));

        t.useLines(lines(ORACLE_CONSULTATION));
        assertTrue(t.protects(oracle));
        assertEquals(discard(oracle, wurm), List.of(WURM));
    }

    @Test
    public void pitchSparesThePiece() {
        start(ORACLE_CONSULTATION);
        final Card oracle = hand(ORACLE);
        final Card brainstorm = hand(BRAINSTORM);
        final Card force = hand(FORCE);
        library(CONSULTATION);
        lands("Island", "Island", "Swamp");
        final SpellAbility fowAltSa = forceAlt(force);
        assertTrue(t.protects(oracle));

        assertEquals(names(ComputerUtil.chooseExileFromList(ai, new CardCollection(List.of(oracle, brainstorm)), force,
                1, fowAltSa, false)), List.of(BRAINSTORM));
    }

    @Test
    public void pitchVetoedWhenThePieceIsTheOnlyFodder() {
        start(ORACLE_CONSULTATION);
        hand(ORACLE);
        final Card force = hand(FORCE);
        library(CONSULTATION);
        lands("Island", "Island", "Swamp");
        final SpellAbility fowAltSa = forceAlt(force);
        assertEquals(ids(t.live()), List.of("742-1295"));

        assertTrue(ComboHold.vetoesPitch(ai, fowAltSa, fowAltSa.getPayCosts()));
        hand(BRAINSTORM);
        assertFalse(ComboHold.vetoesPitch(ai, fowAltSa, fowAltSa.getPayCosts()));
    }

    @Test
    public void noLineIsStock() {
        // the deck holds Oracle and Consultation but no Laboratory Maniac: line 742-2836 is none of its lines
        start(LAB_CONSULTATION);
        final Card oracle = hand(ORACLE);
        final Card wurm = hand(WURM);
        library(CONSULTATION, "Island");
        lands("Island", "Island", "Swamp", "Swamp", "Swamp", "Swamp");
        assertEquals(t.deckLines(), List.of());
        assertFalse(ComboHold.vetoesCast(ai, castOf(oracle)));

        assertEquals(assertStockParity(() -> discard(oracle, wurm), lines(LAB_CONSULTATION)), List.of(ORACLE));
        // and the cast: stock casts the creature in Main 2, as it would with no line at all
        assertEquals(assertStockParity(() -> {
            final Card host = decision();
            return host == null ? null : ComboTracker.nameOf(host);
        }, lines(LAB_CONSULTATION)), ORACLE);
    }

    // ---- beyond the plan's nine ----

    @Test
    public void discardStillTakesThePieceWhenItIsAllThereIs() {
        // a forced discard of every valid card is stock's own early return, before the spare
        final Card oracle = heldBoard();
        assertTrue(t.protects(oracle));
        assertEquals(discard(oracle), List.of(ORACLE));
    }

    @Test
    public void pitchSparesThePieceWhereStockWouldPitchIt() {
        start(ORACLE_CONSULTATION);
        final Card oracle = hand(ORACLE);
        final Card elemental = hand("Air Elemental");
        final Card force = hand(FORCE);
        library(CONSULTATION);
        lands("Island", "Island", "Swamp");
        final SpellAbility fowAltSa = forceAlt(force);
        final Supplier<List<String>> pitch = () -> names(ComputerUtil.chooseExileFromList(ai,
                new CardCollection(List.of(oracle, elemental)), force, 1, fowAltSa, false));

        // stock pitches the lower power first: the 1/3 before the 4/4
        t.useLines(List.of());
        assertEquals(pitch.get(), List.of(ORACLE));
        t.useLines(lines(ORACLE_CONSULTATION));
        assertEquals(pitch.get(), List.of("Air Elemental"));
    }

    @Test
    public void willPayCostsRefusesAPitchOfTheOnlyFodder() {
        start(ORACLE_CONSULTATION);
        hand(ORACLE);
        final Card force = hand(FORCE);
        library(CONSULTATION);
        lands("Island", "Island", "Swamp");
        // the opponent's Craw Wurm on the stack: mana value 6, past Force of Will's MinCMC.3 and every counter roll
        final Card cast = createCard(WURM, opp);
        cast.setGameTimestamp(game.getNextTimestamp());
        game.getStackZone().add(cast);
        final SpellAbility wurmSpell = cast.getFirstSpellAbility();
        wurmSpell.setActivatingPlayer(opp);
        game.getStack().add(wurmSpell);
        final SpellAbility fowAltSa = forceAlt(force);

        t.useLines(List.of());
        assertEquals(SpellApiToAi.Converter.get(fowAltSa).canPlayWithSubs(ai, fowAltSa).decision(),
                AiPlayDecision.WillPlay);
        t.useLines(lines(ORACLE_CONSULTATION));
        assertEquals(SpellApiToAi.Converter.get(fowAltSa).canPlayWithSubs(ai, fowAltSa).decision(),
                AiPlayDecision.CostNotAcceptable);
    }

    @Test
    public void spareDropsPiecesInOrderDownToKeep() {
        start(ORACLE_CONSULTATION);
        final Card oracle = hand(ORACLE);
        final Card consultation = hand(CONSULTATION);
        final Card bears = hand("Grizzly Bears");
        lands("Island", "Island", "Swamp");
        final Card theirs = addCardToZone(ORACLE, opp, ZoneType.Hand);
        final CardCollection three = new CardCollection(List.of(oracle, consultation, bears));

        assertEquals(names(ComboHold.spare(ai, three, 2)), List.of(CONSULTATION, "Grizzly Bears"));
        assertEquals(names(ComboHold.spare(ai, three, 1)), List.of("Grizzly Bears"));
        assertEquals(names(ComboHold.spare(ai, three, 0)), List.of("Grizzly Bears"));
        // nothing to drop: the list itself, and the caller's list is never changed
        assertSame(ComboHold.spare(ai, three, 3), three);
        assertEquals(names(three), List.of(ORACLE, CONSULTATION, "Grizzly Bears"));
        // an opponent's card of a piece's name is no piece of ours
        final CardCollection mixed = new CardCollection(List.of(theirs, bears));
        assertSame(ComboHold.spare(ai, mixed, 1), mixed);
        // no line: the list itself
        t.useLines(List.of());
        assertSame(ComboHold.spare(ai, three, 0), three);
    }

    @Test
    public void onlyOurOwnHandCastOfTheOracleIsHeld() {
        // B-zone winners are cast freely: Laboratory Maniac's live line holds nothing
        start(LAB_CONSULTATION);
        final Card lab = hand(LAB);
        library(CONSULTATION, "Island");
        lands("Island", "Island", "Swamp");
        assertEquals(ids(t.live()), List.of("742-2836"));
        assertFalse(ComboHold.vetoesCast(ai, castOf(lab)));
        // nor does it hold an Oracle that is no piece of the deck's lines: only a live line won by Oracle holds Oracle
        final Card loose = hand(ORACLE);
        assertFalse(t.isPiece(ORACLE));
        assertFalse(ComboHold.vetoesCast(ai, castOf(loose)));

        // the opponent's own Oracle, cast from their hand, is not ours to hold
        final Card held = heldBoard();
        assertTrue(ComboHold.vetoesCast(ai, castOf(held)));
        final Card theirs = addCardToZone(ORACLE, opp, ZoneType.Hand);
        assertFalse(ComboHold.vetoesCast(ai, castOf(theirs)));

        // nor an Oracle cast from the command zone: the hold is for the hand cast alone
        start(ORACLE_CONSULTATION);
        final Card commander = addCardToZone(ORACLE, ai, ZoneType.Command);
        library(CONSULTATION, "Island");
        lands("Island", "Island", "Swamp");
        assertEquals(t.where(ORACLE), Where.COMMAND);
        assertEquals(ids(t.live()), List.of("742-1295"));
        assertFalse(ComboHold.vetoesCast(ai, castOf(commander)));
    }

    @Test
    public void goOffNowNeedsTwoBlueAndOneBlack() {
        // three Islands make the three, both blue, but no black for Consultation
        Card oracle = goOffBoard("Island", "Island", "Island");
        assertEquals(ids(t.executable()), List.of("742-1295"));
        assertFalse(ComboExecute.goOffNow(ai, castOf(oracle)));
        assertTrue(ComboHold.vetoesCast(ai, castOf(oracle)));
        // Island, Island and Swamp: the line's exact cost
        oracle = goOffBoard("Island", "Island", "Swamp");
        assertTrue(ComboExecute.goOffNow(ai, castOf(oracle)));
        assertFalse(ComboHold.vetoesCast(ai, castOf(oracle)));
    }

    @Test
    public void noPitchVetoWithNothingToPitch() {
        // Force of Will alone in hand: no blue card to exile at all, which the stock checks answer, not the hold
        start(ORACLE_CONSULTATION);
        final Card force = hand(FORCE);
        library(ORACLE, CONSULTATION);
        lands("Island", "Island", "Swamp");
        assertEquals(ids(t.live()), List.of("742-1295"));
        final SpellAbility fowAltSa = forceAlt(force);
        assertFalse(ComboHold.vetoesPitch(ai, fowAltSa, fowAltSa.getPayCosts()));
    }

    @Test
    public void anExileFromTheGraveyardIsNoPitch() {
        // Stonerise Spirit's {4}, exile a card from your graveyard: only an exile from our hand can take the Oracle
        start(ORACLE_CONSULTATION);
        hand(ORACLE);
        library(CONSULTATION);
        lands("Island", "Island", "Swamp");
        final Card spirit = addCard("Stonerise Spirit", ai);
        addCardToZone("Grizzly Bears", ai, ZoneType.Graveyard);
        assertEquals(ids(t.live()), List.of("742-1295"));
        SpellAbility pump = null;
        for (final SpellAbility sa : spirit.getSpellAbilities()) {
            if (sa.isActivatedAbility() && sa.getPayCosts().hasSpecificCostType(CostExile.class)) {
                pump = sa;
            }
        }
        assertNotNull(pump);
        pump.setActivatingPlayer(ai);
        assertFalse(ComboHold.vetoesPitch(ai, pump, pump.getPayCosts()));
    }

    @Test
    public void goOffNowReadsTheEmptierInHandAndTheManaForBoth() {
        // Tainted Pact's line: Oracle's two and Pact's two, over a singleton library
        start(PACT_ORACLE);
        final Card oracle = hand(ORACLE);
        hand(PACT);
        library("Island", "Ponder", BRAINSTORM);
        lands("Island", "Island", "Swamp");
        assertTrue(t.singletonLibrary());
        assertEquals(ids(t.live()), List.of("1295-3093"));
        assertFalse(ComboExecute.goOffNow(ai, castOf(oracle)));
        assertTrue(ComboHold.vetoesCast(ai, castOf(oracle)));
        lands("Swamp");
        assertTrue(ComboExecute.goOffNow(ai, castOf(oracle)));
        assertFalse(ComboHold.vetoesCast(ai, castOf(oracle)));

        // Review Focus 1: two cards of one name in the library, so Pact empties nothing and its line holds nothing
        library("Ponder");
        assertFalse(t.singletonLibrary());
        assertEquals(t.live(), List.of());
        assertFalse(ComboExecute.goOffNow(ai, castOf(oracle)));
        assertFalse(ComboHold.vetoesCast(ai, castOf(oracle)));
    }

    @Test
    public void dangerReleasesTheHold() {
        final Card oracle = heldBoard();
        assertTrue(ComboHold.vetoesCast(ai, castOf(oracle)));

        // four ready 6/6s and nothing of ours to chump: 24 coming at our 20
        for (int i = 0; i < 4; i++) {
            addCard("Colossal Dreadmaw", opp).setSickness(false);
        }
        assertEquals(ComboSignals.abandon(ai, t.deckLines().get(0), t), ComboSignals.Signal.DANGER);
        assertFalse(ComboHold.vetoesCast(ai, castOf(oracle)));
    }

    // ---- review round 1 ----

    /** {@code getCardsToDiscard(min, max, cards, null)}'s names (a fresh list each call). */
    private List<String> discard(final int min, final int max, final Card... cards) {
        return names(aiOf(ai).getCardsToDiscard(min, max, new CardCollection(Arrays.asList(cards)), null));
    }

    /** ComputerUtil.chooseExileFromList's names for Force of Will's pitch of one card among {@code cards}. */
    private List<String> pitchOne(final Card force, final SpellAbility fowAltSa, final Card... cards) {
        return names(ComputerUtil.chooseExileFromList(ai, new CardCollection(Arrays.asList(cards)), force, 1,
                fowAltSa, false));
    }

    @Test
    public void otherSpellsOfOursAreNotHeld() {
        // the hold is for Thassa's Oracle alone: no other spell of ours in hand waits for the go-off
        final Card oracle = heldBoard();
        final Card brainstorm = hand(BRAINSTORM);
        final Card wurm = hand(WURM);
        assertEquals(ids(t.live()), List.of("742-1295"));
        assertFalse(ComboExecute.goOffNow(ai, castOf(oracle)));
        assertTrue(ComboHold.vetoesCast(ai, castOf(oracle)));
        assertFalse(ComboHold.vetoesCast(ai, castOf(brainstorm)), "Brainstorm held");
        assertFalse(ComboHold.vetoesCast(ai, castOf(wurm)), "Craw Wurm held");
    }

    @Test
    public void theHeldOracleLeavesTheOtherPlays() {
        // the decision passes over the held Oracle to the next play: a one-drop, which stock casts after the Oracle
        // (the higher mana value first)
        final Card oracle = heldBoard();
        final Card merfolk = hand("Merfolk of the Pearl Trident");
        t.useLines(List.of());
        assertSame(decision(), oracle);

        t.useLines(lines(ORACLE_CONSULTATION));
        assertTrue(ComboHold.vetoesCast(ai, castOf(oracle)));
        assertSame(decision(), merfolk, "the hold stopped the other plays too");
        assertFalse(ComboHold.vetoesCast(ai, castOf(merfolk)));
    }

    @Test
    public void aCopiedCastIsNotHeld() {
        final Card oracle = heldBoard();
        final SpellAbility cast = castOf(oracle);
        assertTrue(ComboHold.vetoesCast(ai, cast));
        cast.setCopied(true);
        assertFalse(ComboHold.vetoesCast(ai, cast));
        cast.setCopied(false);
        assertTrue(ComboHold.vetoesCast(ai, cast));
    }

    @Test
    public void aPlayEffectCastIsNotHeld() {
        final Card oracle = heldBoard();
        final SpellAbility cast = castOf(oracle);
        assertTrue(ComboHold.vetoesCast(ai, cast));
        cast.setCastFromPlayEffect(true);
        assertFalse(ComboHold.vetoesCast(ai, cast));
        cast.setCastFromPlayEffect(false);
        assertTrue(ComboHold.vetoesCast(ai, cast));
    }

    @Test
    public void goOffNowReadsOnlyTheLinesOracleWins() {
        // Laboratory Maniac's line has its emptier, Consultation, in hand with the mana; the Oracle's own line has its
        // emptier, Tainted Pact, in the library: no go-off, and the Oracle waits for Pact's line
        start(PACT_ORACLE, LAB_CONSULTATION);
        final Card oracle = hand(ORACLE);
        hand(CONSULTATION);
        library(PACT, LAB, "Island");
        lands("Island", "Island", "Swamp");
        assertEquals(ids(t.live()), List.of("1295-3093", "742-2836"));
        assertEquals(ids(t.executable()), List.of("1295-3093", "742-2836"));
        assertFalse(ComboExecute.goOffNow(ai, castOf(oracle)), "went off on a Laboratory Maniac line's emptier");
        assertTrue(ComboHold.vetoesCast(ai, castOf(oracle)));
    }

    @Test
    public void goOffNowIgnoresTheAbandonSignals() {
        // a win now beats surviving: the danger that ends the hold does not stop the go-off
        final Card oracle = goOffBoard("Island", "Island", "Swamp");
        assertTrue(ComboExecute.goOffNow(ai, castOf(oracle)));
        for (int i = 0; i < 4; i++) {
            addCard("Colossal Dreadmaw", opp).setSickness(false);
        }
        assertEquals(ComboSignals.abandon(ai, t.deckLines().get(0), t), ComboSignals.Signal.DANGER);
        assertEquals(t.live(), List.of());
        assertEquals(ids(t.executable()), List.of("742-1295"));
        assertTrue(ComboExecute.goOffNow(ai, castOf(oracle)), "danger stopped the go-off");
        assertFalse(ComboHold.vetoesCast(ai, castOf(oracle)));
    }

    @Test
    public void thePitchsHostIsNoFodder() {
        // an exile from hand of any card, "Other" not in its type: the spell paying it is no fodder for its own cost,
        // so the Oracle is the only card left to pay with
        start(ORACLE_CONSULTATION);
        hand(ORACLE);
        library(CONSULTATION);
        lands("Island", "Island", "Swamp");
        final SpellAbility sa = castOf(hand(BRAINSTORM));
        assertEquals(ids(t.live()), List.of("742-1295"));
        final Cost anyCard = new Cost("ExileFromHand<1/Card>", false);
        assertTrue(ComboHold.vetoesPitch(ai, sa, anyCard));
        // a second card to pay with lifts the veto
        hand(WURM);
        assertFalse(ComboHold.vetoesPitch(ai, sa, anyCard));
    }

    @Test
    public void thePitchCountsTheCostsAmount() {
        start(ORACLE_CONSULTATION);
        hand(ORACLE);
        library(CONSULTATION);
        lands("Island", "Island", "Swamp");
        final SpellAbility sa = castOf(hand(BRAINSTORM));
        hand(WURM);
        assertEquals(ids(t.live()), List.of("742-1295"));
        // one card: Craw Wurm pays it; two: the Oracle would be the second
        assertFalse(ComboHold.vetoesPitch(ai, sa, new Cost("ExileFromHand<1/Card.Other>", false)));
        assertTrue(ComboHold.vetoesPitch(ai, sa, new Cost("ExileFromHand<2/Card.Other>", false)));
        // three: too few cards at all, which the stock checks answer, not the hold
        assertFalse(ComboHold.vetoesPitch(ai, sa, new Cost("ExileFromHand<3/Card.Other>", false)));
    }

    @Test
    public void aForcedDiscardOfTwoPiecesTakesOne() {
        // both cards are the line's pieces and one must go: the spare stops at the one card the discard needs,
        // dropping the first piece in the list's order
        start(ORACLE_CONSULTATION);
        final Card oracle = hand(ORACLE);
        final Card consultation = hand(CONSULTATION);
        lands("Island", "Island", "Swamp");
        assertEquals(ids(t.live()), List.of("742-1295"));
        assertEquals(discard(1, 1, oracle, consultation), List.of(CONSULTATION));
        assertEquals(discard(1, 1, consultation, oracle), List.of(ORACLE));
    }

    @Test
    public void aDiscardOfOneOrTwoSparesEveryPiece() {
        // min 1, max 2: the spare keeps min cards, not max, so both pieces leave the choice
        start(ORACLE_CONSULTATION);
        final Card oracle = hand(ORACLE);
        final Card consultation = hand(CONSULTATION);
        final Card wurm = hand(WURM);
        lands("Island", "Island", "Swamp", "Swamp", "Swamp", "Swamp");
        assertEquals(ids(t.live()), List.of("742-1295"));
        assertEquals(discard(1, 2, oracle, consultation, wurm), List.of(WURM));
        assertEquals(discard(1, 2, wurm, consultation, oracle), List.of(WURM));
    }

    @Test
    public void anExileOfTheOnlyPieceStillPays() {
        // the piece is all the cost can take: the spare stops at the amount, so the payment still finds a card (an
        // empty list would throw in chooseExileFromList's subList, mid-game)
        start(ORACLE_CONSULTATION);
        final Card oracle = hand(ORACLE);
        final Card force = hand(FORCE);
        library(CONSULTATION);
        lands("Island", "Island", "Swamp");
        final SpellAbility fowAltSa = forceAlt(force);
        assertTrue(t.protects(oracle));
        assertEquals(pitchOne(force, fowAltSa, oracle), List.of(ORACLE));
    }

    @Test
    public void anExileOfTwoPiecesTakesOne() {
        start(ORACLE_CONSULTATION);
        final Card oracle = hand(ORACLE);
        final Card consultation = hand(CONSULTATION);
        final Card force = hand(FORCE);
        lands("Island", "Island", "Swamp");
        final SpellAbility fowAltSa = forceAlt(force);
        assertEquals(ids(t.live()), List.of("742-1295"));
        assertEquals(pitchOne(force, fowAltSa, oracle, consultation), List.of(CONSULTATION));
        assertEquals(pitchOne(force, fowAltSa, consultation, oracle), List.of(ORACLE));
    }

    // ---- final review, round 1 ----

    @Test
    public void aDualLandPaysOnePipOfTheGoOff() {
        // Island, Underground Sea and Sol Ring make four mana, two blue and one black when counted source by source
        // (HonestMana); but Oracle's {U}{U} takes the Island and the Sea, and nothing is left for Consultation's {B}.
        // Oracle waits, rather than come down into a trigger nothing answers
        Card oracle = goOffBoard("Island", "Underground Sea", "Sol Ring");
        assertEquals(ids(t.live()), List.of("742-1295"));
        final SpecialCardAi.HonestMana honest = SpecialCardAi.HonestMana.of(ai, castOf(oracle), true);
        assertEquals(honest.total(), 4);
        assertEquals(honest.colour(MagicColor.BLUE), 2);
        assertEquals(honest.colour(MagicColor.BLACK), 1);
        assertFalse(ComboExecute.goOffNow(ai, castOf(oracle)));
        assertTrue(ComboHold.vetoesCast(ai, castOf(oracle)));
        assertNull(aiOf(ai).chooseSpellAbilityToPlay());

        // Island, Underground Sea and Plains: the Sea is the second blue or the black, never both
        oracle = goOffBoard("Island", "Underground Sea", "Plains");
        assertFalse(ComboExecute.goOffNow(ai, castOf(oracle)));
        assertTrue(ComboHold.vetoesCast(ai, castOf(oracle)));

        // two Islands and the Sea: the Islands pay the {U}{U}, the Sea the {B}
        oracle = goOffBoard("Island", "Island", "Underground Sea");
        assertTrue(ComboExecute.goOffNow(ai, castOf(oracle)));
        assertFalse(ComboHold.vetoesCast(ai, castOf(oracle)));
    }

    @Test
    public void pactsGoOffNeedsItsBlackAfterOraclesBlue() {
        // Oracle and Tainted Pact, {U}{U} and {1}{B}: Island, Underground Sea and Sol Ring make the four mana, with no
        // black left once the Sea pays a {U}; a Swamp beside them pays the {B}, Sol Ring the {1}
        start(PACT_ORACLE);
        final Card oracle = hand(ORACLE);
        hand(PACT);
        library("Island");
        lands("Island", "Underground Sea", "Sol Ring");
        assertEquals(ids(t.live()), List.of("1295-3093"));
        assertFalse(ComboExecute.goOffNow(ai, castOf(oracle)));
        assertTrue(ComboHold.vetoesCast(ai, castOf(oracle)));

        lands("Swamp");
        assertTrue(ComboExecute.goOffNow(ai, castOf(oracle)));
        assertFalse(ComboHold.vetoesCast(ai, castOf(oracle)));
    }

    // ---- follow-up F3: denial at the moment the line needs it ----

    @Test
    public void teferiReleasesTheOracleHold() {
        // an opposing Teferi, Time Raveler: we cast spells only when we could cast a sorcery, so Consultation can never
        // answer Oracle's trigger. The Oracle line is denied, and Oracle is an ordinary creature again: the stock cast
        // of the 1/3 in Main 2
        final Card oracle = heldBoard();
        assertTrue(ComboHold.vetoesCast(ai, castOf(oracle)));
        final Card teferi = addCard(TEFERI, opp);
        teferi.setCounters(CounterEnumType.LOYALTY, 4);
        assertEquals(t.live(), List.of());
        assertFalse(ComboHold.vetoesCast(ai, castOf(oracle)));
        assertSame(decision(), oracle);

        // the opponent's Grand Abolisher bars us only in its controller's turn, and our go-off is in ours: still held
        final Card held = heldBoard();
        addCard("Grand Abolisher", opp);
        assertEquals(ids(t.live()), List.of("742-1295"));
        assertTrue(ComboHold.vetoesCast(ai, castOf(held)));
        assertNull(aiOf(ai).chooseSpellAbilityToPlay());
    }

    // ---- follow-up 2, G2: denial at each line's own window ----

    /** Review Focus 2: the opponent's Grand Abolisher, in its owner's turn, bars every spell of ours now, but our
     *  Oracle line's window is our own turn: the line stays live and its pieces protected, as in our turn. Oracle in
     *  hand is spared a discard beside a Craw Wurm, and Consultation is kept on top of our library. With no line the
     *  stock choice stands. */
    @Test
    public void abolisherInItsOwnersTurnKeepsOurPiecesProtected() {
        final Card oracle = heldBoard();
        hand(WURM);
        final Card consultation = t.find(CONSULTATION, ZoneType.Library);
        addCard("Grand Abolisher", opp);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, opp);
        final ComboLines.Line l = t.deckLines().get(0);
        assertTrue(ComboSignals.deniedNow(t.view(), l), "read now, the Abolisher bars our spells");
        assertFalse(ComboSignals.barredAtWindowNow(t.view(), l), "the window is our turn");
        assertEquals(ids(t.live()), List.of("742-1295"));
        assertTrue(t.protects(oracle));
        assertTrue(t.protects(consultation));
        final CardCollection hand = new CardCollection(ai.getCardsIn(ZoneType.Hand));
        assertEquals(names(hand), List.of(ORACLE, WURM));
        assertEquals(names(ComboHold.spare(ai, hand, 1)), List.of(WURM));
        assertTrue(ComboFetch.keepsOnTop(ai, consultation));

        // a deck with no line: nothing is protected, and the cards to discard are the stock list
        t.useLines(List.of());
        assertFalse(t.protects(oracle));
        assertSame(ComboHold.spare(ai, hand, 1), hand);
        assertFalse(ComboFetch.keepsOnTop(ai, consultation));
    }

    /** Beyond the plan's tests: our own Fires of Invention, two spells a turn, after Opt this turn. Read now, Oracle
     *  and Consultation could each be cast, the turn's second spell; but the go-off makes Consultation the third, so
     *  Oracle is not cast into it. Next turn's go-off makes it the second: the line is live, and Oracle waits. */
    @Test
    public void firesOfInventionHoldsOracleForNextTurnsGoOff() {
        start(ORACLE_CONSULTATION);
        final Card oracle = hand(ORACLE);
        hand(CONSULTATION);
        library("Island");
        lands("Island", "Island", "Swamp");
        addCard("Fires of Invention", ai);
        final Card opt = createCard("Opt", ai);
        opt.setGameTimestamp(game.getNextTimestamp());
        game.getStackZone().add(opt);
        final SpellAbility optCast = opt.getFirstSpellAbility();
        optCast.setActivatingPlayer(ai);
        game.getStack().add(optCast);
        game.getStack().clear();
        game.getStackZone().remove(opt);
        ai.getZone(ZoneType.Graveyard).add(opt);
        assertEquals(game.getStack().getSpellCardsCastThisTurn(), List.of(opt));
        final ComboLines.Line l = t.deckLines().get(0);
        assertFalse(ComboSignals.deniedNow(t.view(), l), "read now, each would be the turn's second spell");
        assertTrue(ComboSignals.barredAtWindowNow(t.view(), l), "this turn's go-off: Consultation the third spell");
        assertEquals(ids(t.live()), List.of("742-1295"));
        assertFalse(ComboExecute.goOffNow(ai, castOf(oracle)));
        assertTrue(ComboHold.vetoesCast(ai, castOf(oracle)));
        assertNull(decision(), "nothing is cast: Oracle waits for next turn's go-off");
    }

    // ---- follow-up F5: protect the hand; a stacked piece stays on top ----

    private static final String WHEEL = "Wheel of Fortune";
    private static final String LAB_PACT = "V|2836-3093|2|{1}{B}|2|H:Tainted Pact|B:Laboratory Maniac";
    /** Fifteen distinct names, none a land and none a piece: a library Tainted Pact can empty, too deep for a wheel. */
    private static final List<String> SINGLETON = List.of("Runeclaw Bear", "Grizzly Bears", "Hill Giant", "Craw Wurm",
            "Serra Angel", "Shivan Dragon", "Llanowar Elves", "Counterspell", "Divination", "Coral Merfolk",
            "Gray Ogre", "Wind Drake", "Air Elemental", "Giant Spider", "Merfolk of the Pearl Trident");

    /** heldBoard (line 742-1295: Thassa's Oracle held while Demonic Consultation is in the library) with {@code spells}
     *  in hand after the Oracle, a Mountain beside the Island, Island and Swamp, and twenty more cards to draw. Returns
     *  the Oracle. */
    private Card wheelBoard(final String... spells) {
        final Card oracle = heldBoard();
        for (final String spell : spells) {
            hand(spell);
        }
        lands("Mountain");
        fillLibrary(ai, 20);
        return oracle;
    }

    /** Review Focus 4: Terra's own Wheel of Fortune with a held Oracle. The decision passes over the held Oracle, and
     *  the wheel would discard it with the rest of our hand; the wheel waits, so nothing is cast. With no line nothing
     *  is held, and the Oracle's 1/3, which sorts with the wheel and is first in hand, is the stock cast. */
    @Test
    public void wheelOfFortuneWaitsWhileOracleIsProtected() {
        final Card oracle = wheelBoard(WHEEL);
        final Card wheel = t.find(WHEEL, ZoneType.Hand);
        assertEquals(ids(t.live()), List.of("742-1295"));
        assertTrue(t.protects(oracle));
        assertTrue(ComboHold.vetoesCast(ai, castOf(oracle)));
        assertNull(decision(), "a spell took the held Oracle's hand");
        assertTrue(ComboHold.vetoesCast(ai, castOf(wheel)), "the wheel would discard the held Oracle");

        t.useLines(List.of());
        assertFalse(ComboHold.vetoesCast(ai, castOf(wheel)));
        assertSame(decision(), oracle);
    }

    /** A spell that puts our hand into our library leaves the piece reachable (section 6.2): Timetwister is never held
     *  for it, while Wheel of Fortune beside it is. */
    @Test
    public void timetwisterStillCastable() {
        final Card oracle = wheelBoard("Timetwister", WHEEL);
        assertTrue(t.protects(oracle));
        assertFalse(ComboHold.vetoesCast(ai, castOf(t.find("Timetwister", ZoneType.Hand))));
        assertTrue(ComboHold.vetoesCast(ai, castOf(t.find(WHEEL, ZoneType.Hand))));
    }

    /** A dead line protects nothing (Review Focus 5): once Demonic Consultation is exiled, the wheel is an ordinary
     *  spell again, and the decision is the stock one, with the stock draws. */
    @Test
    public void wheelCastableWhenTheLineIsDead() {
        final Card oracle = wheelBoard(WHEEL);
        final Card wheel = t.find(WHEEL, ZoneType.Hand);
        assertTrue(ComboHold.vetoesCast(ai, castOf(wheel)));

        game.getAction().moveTo(ZoneType.Exile, t.find(CONSULTATION, ZoneType.Library), null, AbilityKey.newMap());
        assertEquals(t.live(), List.of());
        assertFalse(t.protects(oracle));
        assertFalse(ComboHold.vetoesCast(ai, castOf(wheel)));
        assertEquals(assertStockParity(() -> {
            final Card host = decision();
            return host == null ? null : ComboTracker.nameOf(host);
        }, lines(ORACLE_CONSULTATION)), ORACLE);
    }

    /** Every spell of ours one of whose parts puts our hand into the graveyard or exile waits, read from its parts:
     *  Windfall's discard of each player's hand, One with Nothing's of ours (no Defined: you), Hex Magic's exile of the
     *  cards we own in hand (a ChangeZoneAll). Wheel and Deal discards only its target opponents' hands, and
     *  Timetwister shuffles ours into the library: neither waits. */
    @Test
    public void everySpellThatTakesOurHandWaits() {
        final Card oracle = wheelBoard("Windfall", "One with Nothing", "Hex Magic", "Wheel and Deal", "Timetwister");
        assertTrue(t.protects(oracle));
        for (final String name : List.of("Windfall", "One with Nothing", "Hex Magic")) {
            assertTrue(ComboHold.vetoesCast(ai, castOf(t.find(name, ZoneType.Hand))), name);
        }
        for (final String name : List.of("Wheel and Deal", "Timetwister")) {
            assertFalse(ComboHold.vetoesCast(ai, castOf(t.find(name, ZoneType.Hand))), name);
        }
    }

    /** A ChangeZoneAll from the hand is read as ChangeZoneAllEffect reads it: only the cards its ChangeType fits go (an
     *  exile of the lands in our hand leaves the Oracle; one of the creatures takes it), and one using all its origin
     *  zones takes every player's cards, ours among them, whatever its Defined. Spells written for the test. */
    @Test
    public void aHandExileReadsItsChangeTypeAndItsPlayers() {
        final Card oracle = wheelBoard();
        final Card host = hand("Hex Magic");
        assertTrue(t.protects(oracle));
        assertFalse(ComboHold.vetoesCast(ai, spell(host, "Land.YouOwn", "")), "the Oracle is no land");
        assertTrue(ComboHold.vetoesCast(ai, spell(host, "Creature.YouOwn", "")), "the Oracle is a creature");
        assertFalse(ComboHold.vetoesCast(ai, spell(host, "Card", " | Defined$ Opponent")), "only the opponent's hand");
        assertTrue(ComboHold.vetoesCast(ai, spell(host, "Card", " | Defined$ Opponent | UseAllOriginZones$ True")),
                "every player's hand");
    }

    // a spell of host's exiling the cards of the ChangeType from the hand, with the extra parameters, cast by us
    private SpellAbility spell(final Card host, final String changeType, final String extra) {
        final SpellAbility sa = AbilityFactory.getAbility("SP$ ChangeZoneAll | Origin$ Hand | Destination$ Exile"
                + " | ChangeType$ " + changeType + extra, host);
        sa.setActivatingPlayer(ai);
        assertTrue(sa.isSpell());
        return sa;
    }

    /** A discard of a counted number of cards is no wheel: Faithless Looting's and Careful Study's "draw two, then
     *  discard two" (a sub Discard of Mode$ TgtChoose) leave the choice to our own discard, which spares a protected
     *  piece while another card can go (ComboHold.spare). Neither waits, while Wheel of Fortune beside them, a Discard
     *  of Mode$ Hand, does. */
    @Test
    public void aCountedDiscardIsNoWheel() {
        final Card oracle = wheelBoard("Faithless Looting", "Careful Study", WHEEL);
        assertTrue(t.protects(oracle));
        assertTrue(ComboHold.vetoesCast(ai, castOf(t.find(WHEEL, ZoneType.Hand))));
        for (final String name : List.of("Faithless Looting", "Careful Study")) {
            assertFalse(ComboHold.vetoesCast(ai, castOf(t.find(name, ZoneType.Hand))),
                    name + " waited for a discard of two");
        }
    }

    /** A counted exile from the hand is no wheel, as a counted discard is none: Mind Swords' "each player exiles two
     *  cards from their hand" (a ChangeZone of ChangeNum$ 2, not a ChangeZoneAll) and an exile of one card of our hand
     *  written for the test leave the choice to our own exile, which spares a protected piece. Neither waits, while
     *  Wheel of Fortune beside them, a Discard of Mode$ Hand, does. */
    @Test
    public void aCountedExileIsNoWheel() {
        final Card oracle = wheelBoard("Mind Swords", WHEEL);
        final Card host = hand("Hex Magic");
        assertTrue(t.protects(oracle));
        assertTrue(ComboHold.vetoesCast(ai, castOf(t.find(WHEEL, ZoneType.Hand))));

        final SpellAbility swords = castOf(t.find("Mind Swords", ZoneType.Hand));
        assertEquals(swords.getApi(), ApiType.ChangeZone);
        assertEquals(swords.getParam("Origin"), "Hand");
        assertEquals(swords.getParam("Destination"), "Exile");
        assertFalse(ComboHold.vetoesCast(ai, swords), "Mind Swords waited for an exile of two");

        final SpellAbility one = AbilityFactory.getAbility("SP$ ChangeZone | Origin$ Hand | Destination$ Exile"
                + " | ChangeType$ Card | ChangeNum$ 1 | Hidden$ True | Mandatory$ True | DefinedPlayer$ You"
                + " | Chooser$ You", host);
        one.setActivatingPlayer(ai);
        assertTrue(one.isSpell());
        assertFalse(ComboHold.vetoesCast(ai, one), "an exile of one card waited");
    }

    /** A part that takes our hand need not be the spell's root: Apocalypse's root exiles every permanent, and a sub of
     *  it discards our hand (a Discard of Mode$ Hand naming no player: you). It waits while the Oracle is protected,
     *  and with no line it is an ordinary spell again. */
    @Test
    public void aSubThatDiscardsTheHandWaits() {
        final Card oracle = wheelBoard("Apocalypse");
        final Card apocalypse = t.find("Apocalypse", ZoneType.Hand);
        assertTrue(t.protects(oracle));
        assertTrue(ComboHold.vetoesCast(ai, castOf(apocalypse)), "Apocalypse discarded the held Oracle");

        t.useLines(List.of());
        assertFalse(ComboHold.vetoesCast(ai, castOf(apocalypse)));
    }

    /** A hand put into the graveyard is a wheel as much as a hand put into exile: Harness Infinity ("Exchange your hand
     *  and graveyard": a ChangeZoneAll of our cards from the hand to the graveyard, then the graveyard's others back to
     *  the hand) waits, and so does a bare hand-to-graveyard ChangeZoneAll written for the test. */
    @Test
    public void aHandToTheGraveyardWaits() {
        final Card oracle = wheelBoard("Harness Infinity");
        final Card host = hand("Hex Magic");
        assertTrue(t.protects(oracle));
        assertTrue(ComboHold.vetoesCast(ai, castOf(t.find("Harness Infinity", ZoneType.Hand))),
                "Harness Infinity put the held Oracle into the graveyard");
        assertTrue(ComboHold.vetoesCast(ai, zoneAll(host, "Hand", "Graveyard", "Card.YouCtrl")),
                "a hand to the graveyard was cast into the held Oracle");
    }

    /** A ChangeZoneAll from another zone takes nothing from our hand, whatever its ChangeType would fit there: Final
     *  Judgment's exile of the battlefield's creatures and Calamity's Wake's exile of every graveyard never wait, nor do
     *  a battlefield-to-exile, a battlefield-to-graveyard and a graveyard-to-exile written for the test, while each of
     *  those from the hand does. */
    @Test
    public void otherOriginsAreNoWheel() {
        final Card oracle = wheelBoard("Final Judgment", "Calamity's Wake");
        final Card host = hand("Hex Magic");
        assertTrue(t.protects(oracle));
        for (final String name : List.of("Final Judgment", "Calamity's Wake")) {
            assertFalse(ComboHold.vetoesCast(ai, castOf(t.find(name, ZoneType.Hand))), name);
        }
        for (final List<String> move : List.of(List.of("Battlefield", "Exile", "Creature"),
                List.of("Battlefield", "Graveyard", "Creature"), List.of("Graveyard", "Exile", "Card"))) {
            assertFalse(ComboHold.vetoesCast(ai, zoneAll(host, move.get(0), move.get(1), move.get(2))),
                    move.toString());
            assertTrue(ComboHold.vetoesCast(ai, zoneAll(host, "Hand", move.get(1), move.get(2))),
                    "from the hand: " + move);
        }
    }

    // a spell of host's moving every card of the type from the origin to the destination, cast by us
    private SpellAbility zoneAll(final Card host, final String origin, final String destination, final String type) {
        final SpellAbility sa = AbilityFactory.getAbility("SP$ ChangeZoneAll | Origin$ " + origin + " | Destination$ "
                + destination + " | ChangeType$ " + type, host);
        sa.setActivatingPlayer(ai);
        assertTrue(sa.isSpell());
        return sa;
    }

    /** The go-off's own last draw: Laboratory Maniac in play and our library empty, a wheel's draw wins, so it is cast
     *  though Tainted Pact, the piece of the Pact line still live on the empty library, goes with our hand. With a
     *  library too deep for its seven draws it waits. */
    @Test
    public void aWheelWhoseDrawWinsIsCastInTheGoOff() {
        start(LAB_PACT);
        addCard(LAB, ai);
        final Card pact = hand(PACT);
        final Card wheel = hand(WHEEL);
        lands("Swamp", "Swamp", "Mountain");
        assertTrue(ai.getCardsIn(ZoneType.Library).isEmpty());
        assertEquals(ids(t.live()), List.of("2836-3093"));
        assertTrue(t.protects(pact));
        assertFalse(ComboHold.vetoesCast(ai, castOf(wheel)), "the wheel's winning draw waited");
        // a spell that takes our hand and draws nothing does not win: One with Nothing waits
        assertTrue(ComboHold.vetoesCast(ai, castOf(hand("One with Nothing"))), "One with Nothing discarded the Pact");

        library(SINGLETON.toArray(new String[0]));
        assertEquals(ids(t.live()), List.of("2836-3093"));
        assertTrue(t.protects(pact));
        assertTrue(ComboHold.vetoesCast(ai, castOf(wheel)), "the wheel discarded the live Pact");
    }

    /** Ponder's "you may shuffle" after our ordering stacked a live piece (spec section 6.3, the known tops).
     *  Laboratory Maniac in play, Demonic Consultation second of our library's top three and no land in play: the line
     *  is live on next turn's land, and the stock confirm shuffles away a top card it cannot cast yet. Ponder puts
     *  Consultation on top, keeps it there, and draws it. */
    @Test
    public void ponderKeepsThePieceOnTop() {
        final SpellAbility ponder = ponderBoard();
        final Card consultation = t.find(CONSULTATION, ZoneType.Library);
        assertSame(ai.getCardsIn(ZoneType.Library).get(1), consultation);
        assertTrue(t.protects(consultation));

        MyRandom.setRandom(new Random(7));
        AbilityUtils.resolve(ponder);
        assertNotNull(t.find(CONSULTATION, ZoneType.Hand), "Ponder shuffled the stacked Consultation away: drew "
                + names(ai.getCardsIn(ZoneType.Hand)));
        assertEquals(t.where(CONSULTATION), Where.HAND);
        assertEquals(t.knownTops(), Set.of());
    }

    /** The confirm itself, asked as RearrangeTopOfLibraryEffect asks it after its moves: no shuffle once our ordering
     *  has stacked the live piece, a known top. A piece that is on top though no hook of ours put it there (nothing the
     *  AI knows), a deck holding no line, and another player's library all get the stock answer: shuffle the card it
     *  cannot cast yet. */
    @Test
    public void ponderDeclinesTheShuffleOnlyOverAKnownTop() {
        final SpellAbility ponder = ponderBoard();
        final Card consultation = t.find(CONSULTATION, ZoneType.Library);
        // on top before any ordering of ours: no known top, the stock shuffle
        game.getAction().moveToLibrary(consultation, 0, ponder);
        assertSame(ai.getCardsIn(ZoneType.Library).get(0), consultation);
        assertEquals(t.knownTops(), Set.of());
        assertTrue(ai.getController().confirmAction(ponder, null, "", null),
                "a top card no hook of ours put there was kept");

        final SpellAbility stacked = ponderBoard();
        final PlayerControllerAi pc = (PlayerControllerAi) ai.getController();
        for (final Card c : pc.orderMoveToZoneList(ai.getTopXCardsFromLibrary(3), ZoneType.Library, stacked)) {
            game.getAction().moveToLibrary(c, 0, stacked);
        }
        final Card top = ai.getCardsIn(ZoneType.Library).get(0);
        assertEquals(ComboTracker.nameOf(top), CONSULTATION);
        assertEquals(t.knownTops(), Set.of(top.getId()));
        assertTrue(ComboHold.keepsTheStackedTop(ai, ai));
        assertFalse(pc.confirmAction(stacked, null, "", null), "the stacked Consultation was shuffled away");
        assertFalse(ComboHold.keepsTheStackedTop(ai, opp), "another player's library");
        assertFalse(ComboHold.keepsTheStackedTop(null, ai));
        // a known top whose line is dead (an opposing Platinum Angel: we cannot win) protects nothing: stock shuffles
        final Card angel = addCard("Platinum Angel", opp);
        assertEquals(t.live(), List.of());
        assertEquals(t.knownTops(), Set.of(top.getId()));
        assertFalse(ComboHold.keepsTheStackedTop(ai, ai), "a dead line's piece was kept on top");
        assertTrue(pc.confirmAction(stacked, null, "", null));
        game.getAction().moveTo(ZoneType.Graveyard, angel, null, AbilityKey.newMap());
        assertTrue(ComboHold.keepsTheStackedTop(ai, ai));
        // the same top with no line is the stock confirm's
        t.useLines(List.of());
        assertFalse(ComboHold.keepsTheStackedTop(ai, ai));
        assertTrue(pc.confirmAction(stacked, null, "", null));
    }

    /** Laboratory Maniac in play, no land in play, line 742-2836 live on next turn's land; Grizzly Bears, Demonic
     *  Consultation and an Island our library's top three, twenty cards below. Returns Ponder's spell, ours. */
    private SpellAbility ponderBoard() {
        start(LAB_CONSULTATION);
        addCard(LAB, ai);
        library("Grizzly Bears", CONSULTATION, "Island");
        fillLibrary(ai, 20);
        assertEquals(ids(t.live()), List.of("742-2836"));
        return castOf(hand("Ponder"));
    }

    // ---- follow-up 2, G3: the go-off's costs as the engine prices them ----

    /** Review Focus 3: the opponent's Thalia, Guardian of Thraben taxes Demonic Consultation, a noncreature spell, to
     *  {1}{B}, and not Thassa's Oracle. Island, Island and Swamp pay the printed {U}{U} and {B} but not the engine's
     *  price: no go-off, so the Oracle is held, not cast into a trigger Consultation could not answer. One more land
     *  pays it: the go-off is ready, and the Oracle is the spell chosen. */
    @Test
    public void thaliaRaisesTheGoOffCost() {
        final Card oracle = goOffBoard("Island", "Island", "Swamp");
        addCard("Thalia, Guardian of Thraben", opp);
        assertEquals(ids(t.live()), List.of("742-1295"));
        assertFalse(ComboExecute.goOffNow(ai, castOf(oracle)), "the go-off read Consultation's printed cost");
        assertTrue(ComboHold.vetoesCast(ai, castOf(oracle)), "the Oracle was not held");
        assertNull(aiOf(ai).chooseSpellAbilityToPlay());

        lands("Swamp");
        assertTrue(ComboExecute.goOffNow(ai, castOf(oracle)));
        assertFalse(ComboHold.vetoesCast(ai, castOf(oracle)));
        assertSame(decision(), oracle);
    }

    /** Sphere of Resistance ("Spells cost {1} more to cast") raises both of the go-off's costs, the Oracle's to
     *  {1}{U}{U} and Consultation's to {1}{B}. Island, Island, Swamp and Island pay either raised cost beside the other
     *  one printed, but not both raised: no go-off, and the Oracle is held. A fifth land pays both. The opponent's
     *  Grand Arbiter Augustin IV ("Spells your opponents cast cost {1} more to cast") alike. */
    @Test
    public void sphereOfResistanceRaisesBothCosts() {
        for (final String tax : List.of("Sphere of Resistance", "Grand Arbiter Augustin IV")) {
            final Card oracle = goOffBoard("Island", "Island", "Swamp", "Island");
            addCard(tax, opp);
            assertEquals(ids(t.live()), List.of("742-1295"), tax);
            assertFalse(ComboExecute.goOffNow(ai, castOf(oracle)), tax + ": a cost was read as printed");
            assertTrue(ComboHold.vetoesCast(ai, castOf(oracle)), tax);

            lands("Swamp");
            assertTrue(ComboExecute.goOffNow(ai, castOf(oracle)), tax);
            assertFalse(ComboHold.vetoesCast(ai, castOf(oracle)), tax);
        }
    }

    /** Beyond the plan's tests: the engine's price lowers a cost as well. Helm of Awakening ("Spells cost {1} less to
     *  cast") takes Tainted Pact's {1}, so Island, Island and Swamp pay the Oracle's {U}{U} and Pact's {B}, where the
     *  printed costs need a fourth mana: the go-off is ready, and the Oracle is not held. */
    @Test
    public void aCostReductionLowersTheGoOff() {
        start(PACT_ORACLE);
        final Card oracle = hand(ORACLE);
        hand(PACT);
        library("Island");
        lands("Island", "Island", "Swamp");
        assertEquals(ids(t.live()), List.of("1295-3093"));
        assertFalse(ComboExecute.goOffNow(ai, castOf(oracle)));
        assertTrue(ComboHold.vetoesCast(ai, castOf(oracle)));

        addCard("Helm of Awakening", opp);
        assertEquals(ids(t.live()), List.of("1295-3093"));
        assertTrue(ComboExecute.goOffNow(ai, castOf(oracle)), "the go-off read Pact's printed cost");
        assertFalse(ComboHold.vetoesCast(ai, castOf(oracle)));
    }
}
