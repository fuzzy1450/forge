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
import forge.ai.AiPlayDecision;
import forge.ai.ComputerUtil;
import forge.ai.ComputerUtilAbility;
import forge.ai.LobbyPlayerAi;
import forge.ai.PlayerControllerAi;
import forge.ai.SpellApiToAi;
import forge.ai.combo.ComboTracker.Where;
import forge.game.Game;
import forge.game.ability.AbilityKey;
import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.cost.Cost;
import forge.game.cost.CostExile;
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
}
