package forge.ai;

import static org.testng.AssertJUnit.assertEquals;
import static org.testng.AssertJUnit.assertFalse;
import static org.testng.AssertJUnit.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import org.testng.annotations.Test;

import forge.ai.combo.ComboHold;
import forge.ai.combo.ComboLines;
import forge.ai.combo.ComboTable;
import forge.game.Game;
import forge.game.card.Card;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import forge.util.MyRandom;

/**
 * Upstream's Doomsday draw-through (79f6fc2ec91, #12701: DoomsdayPile.drawsThroughPile, asked by DrawAi) lets a draw
 * take cards out of a library of five or fewer while Thassa's Oracle, Laboratory Maniac or Jace, Wielder of Mysteries
 * is in hand or in play: DrawAi's two phase holds are lifted and its two "don't deck yourself" guards skipped, in any
 * phase, with no Doomsday cast and no check that the winner can be cast before the next draw. In this fork its context
 * is Flat MC's alone: Doomsday is AI:RemoveDeck:All, which no fork row readmits, and its follow-through casts
 * (DoomsdayPile.sureDraw, ConsultationWin) run only where Flat MC does. DrawAi asks it only for a player whose Flat MC
 * is active, so a game with Flat MC off (every game of the sim harness, GameRunner pins it) keeps the stock holds and
 * guards: the interplay review's boards (F2: S2, S3, S1a) draw nothing, as at G, while with Flat MC on the
 * draw-through still says yes. Each of the four places DrawAi asks it has a board of its own below.
 */
public class MergeDoomsdayDrawThroughGateTest extends AITest {
    private static final String WIN = "F|2|S|Win the game";
    private static final String EXILE = "F|88|H|Exile your library";
    /** The fork's Oracle line (piece 1): Demonic Consultation empties the library, Thassa's Oracle wins, {U}{U}{B}. */
    private static final String ORACLE_CONSULTATION =
            "V|742-1295|3|{U}{U}{B}|2,88|H:Demonic Consultation|H:Thassa's Oracle";
    private static final String ORACLE = "Thassa's Oracle";
    private static final String BEAR = "Runeclaw Bear";

    private Game game;
    private Player ai;
    private Player opp;

    private static AiController aiOf(final Player p) {
        return ((PlayerControllerAi) p.getController()).getAi();
    }

    /**
     * A two-player game, the Default profile on both seats, no AI deadline, Flat MC on or off as {@code flatMc} says
     * (off is what GameRunner pins); the AI (the second player) tracks the combo table's {@code rows} and no other, and
     * the opponent has twenty bears in its library.
     */
    private void start(final boolean flatMc, final String... rows) {
        game = initAndCreateGame();
        game.AI_FLAT_MONTE_CARLO = flatMc;
        game.AI_TIMEOUT = Integer.MAX_VALUE;
        game.AI_CAN_USE_TIMEOUT = false;
        ai = game.getPlayers().get(1);
        opp = game.getPlayers().get(0);
        ((LobbyPlayerAi) ai.getLobbyPlayer()).setAiProfile("Default");
        ((LobbyPlayerAi) opp.getLobbyPlayer()).setAiProfile("Default");
        final List<String> table = new ArrayList<>(List.of(WIN, EXILE));
        table.addAll(Arrays.asList(rows));
        aiOf(ai).getComboTracker().useLines(ComboLines.claimed(ComboTable.parse(table)));
        for (int i = 0; i < 20; i++) {
            addCardToZone(BEAR, opp, ZoneType.Library);
        }
        assertEquals("the harness: Flat MC " + (flatMc ? "on" : "off"), flatMc, aiOf(ai).flatMonteCarloActive());
    }

    private Card hand(final String name) {
        return addCardToZone(name, ai, ZoneType.Hand);
    }

    private void library(final int n) {
        for (int i = 0; i < n; i++) {
            addCardToZone(BEAR, ai, ZoneType.Library);
        }
    }

    private void islands(final int n) {
        for (int i = 0; i < n; i++) {
            addCard("Island", ai);
        }
    }

    private void opponentsEndStep() {
        game.getPhaseHandler().devModeSet(PhaseType.END_OF_TURN, opp);
        game.getAction().checkStateEffects(true);
    }

    private void ourPhase(final PhaseType phase) {
        game.getPhaseHandler().devModeSet(phase, ai);
        game.getAction().checkStateEffects(true);
    }

    /** The AI's play now: the first of AiController.chooseSpellAbilityToPlay's list, or null. */
    private SpellAbility choice() {
        MyRandom.setRandom(new Random(7));
        final List<SpellAbility> list = aiOf(ai).chooseSpellAbilityToPlay();
        return list == null || list.isEmpty() ? null : list.get(0);
    }

    private static String nameOf(final SpellAbility sa) {
        return sa == null ? null : sa.getHostCard().getName() + " [" + sa.getApi() + "]";
    }

    /** DrawAi's own decision on the first ability of {@code card}, the AI activating it, as upstream's
     *  FlatMonteCarloVintageDoomsdayTest asks it of the cut pile's Street Wraith. */
    private AiPlayDecision drawDecision(final Card card) {
        final SpellAbility sa = card.getFirstSpellAbility();
        sa.setActivatingPlayer(ai);
        return SpellApiToAi.Converter.get(sa).canPlayWithSubs(ai, sa).decision();
    }

    // ------------------------------------------------------------------------ the interplay review's boards (F2)

    /**
     * S2: no combo line, Thassa's Oracle and Think Twice in hand, two Islands, a library of one, the opponent's end
     * step. At G the AI drew nothing here, took its last card in its own draw step and won with the Oracle on turn 2;
     * with the draw-through it cast Think Twice here and lost at that draw.
     */
    @Test(timeOut = 120_000)
    public void withNoLineAndALibraryOfOneNothingIsDrawnAtTheOpponentsEndStep() {
        start(false);
        hand(ORACLE);
        hand("Think Twice");
        islands(2);
        library(1);
        opponentsEndStep();

        assertEquals("the Oracle cannot be cast before our draw step, so the last card waits for it", null,
                nameOf(choice()));
    }

    /**
     * S3, the shape of the Arcanis list 87715b77 (Jace, Wielder of Mysteries and no emptier): Arcanis the Omnipotent
     * untapped, Jace in hand, three Islands, a library of three, the opponent's end step. At G Arcanis stayed untapped;
     * with the draw-through it drew all three and the AI lost at its draw.
     */
    @Test(timeOut = 120_000)
    public void arcanisIsNotTappedToDrawALibraryOfThree() {
        start(false);
        hand("Jace, Wielder of Mysteries");
        addCard("Arcanis the Omnipotent", ai).setSickness(false);
        islands(3);
        library(3);
        opponentsEndStep();

        assertEquals("Arcanis's draw would take the whole library", null, nameOf(choice()));
    }

    /**
     * S1a: the fork's Oracle line live (742-1295) but its go-off unpayable (three Islands, no {B} for Consultation),
     * Thassa's Oracle, Demonic Consultation and Divination in hand, a library of four, our Main 2. ComboHold keeps the
     * Oracle while the line waits. At G Divination stayed in hand and the library lasted to turn 10; with the
     * draw-through the AI cast it down to two cards and was decked on turn 7.
     */
    @Test(timeOut = 120_000)
    public void aLiveOracleLineKeepsDivinationInHandOnALibraryOfFour() {
        start(false, ORACLE_CONSULTATION);
        final Card oracle = hand(ORACLE);
        hand("Demonic Consultation");
        hand("Divination");
        islands(3);
        library(4);
        ourPhase(PhaseType.MAIN2);
        assertEquals("the harness: the Oracle line is live", 1, aiOf(ai).getComboTracker().live().size());
        final SpellAbility oracleCast = oracle.getFirstSpellAbility();
        oracleCast.setActivatingPlayer(ai);
        assertTrue("the harness: ComboHold keeps the Oracle while Consultation cannot be paid",
                ComboHold.vetoesCast(ai, oracleCast));

        final SpellAbility play = choice();
        assertFalse("Divination would draw the library down to two, the AI chose " + nameOf(play),
                play != null && "Divination".equals(play.getHostCard().getName()));
    }

    /** S2c, the control: S2 with no winner anywhere, where the draw-through has nothing to draw to on any tree. */
    @Test(timeOut = 120_000)
    public void withNoWinnerAnywhereNothingIsDrawnEither() {
        start(false);
        hand("Think Twice");
        islands(2);
        library(1);
        opponentsEndStep();

        assertEquals(null, nameOf(choice()));
    }

    /**
     * S2's board with Flat MC on: upstream's draw-through is kept where Flat MC runs, so DrawAi lets Think Twice take
     * the last card (the untargeted deck guard skipped). Under Flat MC the play itself is the rollouts' to choose; this
     * is DrawAi's permission alone.
     */
    @Test
    public void withFlatMcOnTheDrawThroughStillLetsThinkTwiceTakeTheLastCard() {
        start(true);
        hand(ORACLE);
        final Card thinkTwice = hand("Think Twice");
        islands(2);
        library(1);
        opponentsEndStep();

        assertEquals(AiPlayDecision.WillPlay, drawDecision(thinkTwice));
    }

    // ------------------------------------------------------------- each place DrawAi asks it, Flat MC off and on

    /** Thassa's Oracle and {@code card} in hand, two Islands, a library of five, no combo line, our {@code phase}: the
     *  draw-through reads the library as a pile, while a draw of one leaves the deck guards alone. */
    private Card libraryOfFive(final boolean flatMc, final String card, final PhaseType phase) {
        start(flatMc);
        hand(ORACLE);
        final Card c = hand(card);
        islands(2);
        library(5);
        ourPhase(phase);
        return c;
    }

    /** The Main 2 hold of a draw with no AI logic (checkPhaseRestrictions): Think Twice in our Main 1 waits for Main 2,
     *  unless the draw-through lifts the hold, which it does with Flat MC on alone. */
    @Test
    public void theMainTwoHoldStandsWithFlatMcOffAndIsLiftedWithFlatMcOn() {
        assertEquals("Flat MC off", AiPlayDecision.MissingPhaseRestrictions,
                drawDecision(libraryOfFive(false, "Think Twice", PhaseType.MAIN1)));
        assertEquals("Flat MC on", AiPlayDecision.WillPlay,
                drawDecision(libraryOfFive(true, "Think Twice", PhaseType.MAIN1)));
    }

    /** The hold of a draw with an AI logic (checkPhaseRestrictions with logic): Whispers of the Muse, AtOppEOT, waits
     *  in our Main 2 for the opponent's end step, unless the draw-through lifts the hold, with Flat MC on alone. */
    @Test
    public void theEndStepHoldStandsWithFlatMcOffAndIsLiftedWithFlatMcOn() {
        assertEquals("Flat MC off", AiPlayDecision.MissingPhaseRestrictions,
                drawDecision(libraryOfFive(false, "Whispers of the Muse", PhaseType.MAIN2)));
        assertEquals("Flat MC on", AiPlayDecision.WillPlay,
                drawDecision(libraryOfFive(true, "Whispers of the Muse", PhaseType.MAIN2)));
    }

    /** The targeted deck guard: Ancestral Recall, three of the five cards, in our Main 2, is aimed at the AI itself
     *  only where Flat MC runs. (The untargeted guard is S2's, S3's and S1a's, and its Flat MC twin S2's above.) */
    @Test
    public void theTargetedDeckGuardStandsWithFlatMcOffAndIsSkippedWithFlatMcOn() {
        assertEquals("Flat MC off", AiPlayDecision.TargetingFailed,
                drawDecision(libraryOfFive(false, "Ancestral Recall", PhaseType.MAIN2)));
        assertEquals("Flat MC on", AiPlayDecision.WillPlay,
                drawDecision(libraryOfFive(true, "Ancestral Recall", PhaseType.MAIN2)));
    }
}
