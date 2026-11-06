package forge.ai.combo;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import org.testng.annotations.Test;

import com.google.common.collect.Lists;

import forge.ai.AITest;
import forge.ai.AiCardMemory;
import forge.ai.ComputerUtilCost;
import forge.ai.LobbyPlayerAi;
import forge.ai.ManaSacrifice;
import forge.ai.PlayerControllerAi;
import forge.card.mana.ManaCost;
import forge.card.mana.ManaCostParser;
import forge.deck.Deck;
import forge.deck.DeckSection;
import forge.game.Game;
import forge.game.GameRules;
import forge.game.GameStage;
import forge.game.GameType;
import forge.game.Match;
import forge.game.card.Card;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.player.RegisteredPlayer;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import forge.util.MyRandom;

/**
 * The mana of a go-off against upstream's sacrifice-for-mana rule: ComputerUtilMana pays with a mana ability that
 * sacrifices a permanent only when the spell is worth more than what it gives up (ManaSacrifice.worthIt), so a source
 * the payer would refuse is no mana of ComboMana's reading, and Thassa's Oracle waits for mana that will really pay
 * Demonic Consultation after it. Phyrexian Altar's {B} is what the go-off is one short of on these boards. The AI plays
 * the Default profile in its own Main 2, with Oracle and Consultation in hand and line 742-1295 tracked.
 */
public class ComboManaSacrificeAiTest extends AITest {
    private static final String WIN = "F|2|S|Win the game";
    private static final String EXILE = "F|88|H|Exile your library";
    private static final String ORACLE_CONSULTATION =
            "V|742-1295|3|{U}{U}{B}|2,88|H:Demonic Consultation|H:Thassa's Oracle";
    private static final String ORACLE = "Thassa's Oracle";
    private static final String CONSULTATION = "Demonic Consultation";
    private static final String ALTAR = "Phyrexian Altar";

    private Game game;
    private Player ai;
    private SpellAbility oracleCast;
    private SpellAbility consultCast;

    private static List<ComboLines.Line> lines(final String... rows) {
        final List<String> all = new ArrayList<>(List.of(WIN, EXILE));
        all.addAll(Arrays.asList(rows));
        return ComboLines.claimed(ComboTable.parse(all));
    }

    private Card ready(final String name) {
        final Card c = addCard(name, ai);
        c.setSickness(false);
        return c;
    }

    private void board() {
        ((PlayerControllerAi) ai.getController()).getAi().getComboTracker().useLines(lines(ORACLE_CONSULTATION));
        final Card oracle = addCardToZone(ORACLE, ai, ZoneType.Hand);
        final Card consultation = addCardToZone(CONSULTATION, ai, ZoneType.Hand);
        fillLibrary(ai, 20);
        oracleCast = oracle.getFirstSpellAbility();
        oracleCast.setActivatingPlayer(ai);
        consultCast = consultation.getFirstSpellAbility();
        consultCast.setActivatingPlayer(ai);
        ready("Island");
        ready("Island");
    }

    private void constructedBoard() {
        game = initAndCreateGame();
        ai = game.getPlayers().get(1);
        ((LobbyPlayerAi) ai.getLobbyPlayer()).setAiProfile("Default");
        moveToMain2(game, ai);
        board();
    }

    /** A Commander game: Oracle and Consultation are both key cards of upstream's pair, worth ManaSacrifice's bonus. */
    private void commanderBoard() {
        final Deck ours = new Deck("ours");
        ours.getOrCreate(DeckSection.Commander).add("Kinnan, Bonder Prodigy", 1);
        for (final String n : List.of(ORACLE, CONSULTATION, "Island", "Swamp")) {
            ours.getMain().add(n, 1);
        }
        final Deck theirs = new Deck("theirs");
        theirs.getOrCreate(DeckSection.Commander).add("Azami, Lady of Scrolls", 1);
        theirs.getMain().add("Island", 40);
        final List<RegisteredPlayer> players = Lists.newArrayList(
                RegisteredPlayer.forCommander(theirs).setPlayer(new LobbyPlayerAi("p2", null)),
                RegisteredPlayer.forCommander(ours).setPlayer(new LobbyPlayerAi("p1", null)));
        final GameRules rules = new GameRules(GameType.Commander);
        game = new Game(players, rules, new Match(rules, players, "Test"));
        for (final Player p : game.getPlayers()) {
            p.initVariantsZones(p.getRegisteredPlayer());
            ((LobbyPlayerAi) p.getLobbyPlayer()).setAiProfile("Default");
        }
        ai = game.getPlayers().get(1);
        game.setAge(GameStage.Play);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN2, ai, 3);
        game.getPhaseHandler().onStackResolved();
        game.AI_TIMEOUT = Integer.MAX_VALUE;
        game.AI_CAN_USE_TIMEOUT = false;
        board();
    }

    private static SpellAbility manaOf(final Card altar, final Player ai) {
        final SpellAbility ma = altar.getManaAbilities().get(0);
        ma.setActivatingPlayer(ai);
        return ma;
    }

    /** Whether the payer pays the emptier with the Islands gone, as they are once Oracle is cast. */
    private boolean payerPaysTheEmptierAfterOracle() {
        for (final Card c : ai.getCardsIn(ZoneType.Battlefield)) {
            if (c.getName().equals("Island")) {
                c.setTapped(true);
            }
        }
        return ComputerUtilCost.canPayCost(consultCast, ai, false);
    }

    /** The red test the review asked for: the fork reads ready, and the payer refuses. */
    @Test(timeOut = 120_000)
    public void aCreatureTheSpellIsNotWorthPaysNoGoOff() {
        constructedBoard();
        final Card altar = ready(ALTAR);
        ready("Craw Wurm");
        assertFalse(ManaSacrifice.worthIt(ai, manaOf(altar, ai), consultCast), "the harness: the payer's rule");

        assertFalse(ComboExecute.goOffNow(ai, oracleCast), "Oracle waits: the Altar's {B} will not be made");
        assertFalse(payerPaysTheEmptierAfterOracle(), "the payer's own word for it");
    }

    /** Fodder the payer gives up for Oracle's cast but not for Consultation's, a key card of less worth: the go-off
     *  needs the mana for both, so it needs the source for both. */
    @Test(timeOut = 120_000)
    public void aSacrificeWorthItForOracleAloneDoesNotPayTheEmptier() {
        commanderBoard();
        final Card altar = ready(ALTAR);
        ready("Centaur Courser");   // a 3/3: more than a 2/2 and less than Oracle's worth with its key card bonus
        assertTrue(ManaSacrifice.worthIt(ai, manaOf(altar, ai), oracleCast), "the harness: worth it for Oracle");
        assertFalse(ManaSacrifice.worthIt(ai, manaOf(altar, ai), consultCast), "the harness: not for Consultation");

        assertFalse(ComboExecute.goOffNow(ai, oracleCast));
        assertFalse(payerPaysTheEmptierAfterOracle(), "the payer's own word for it");
    }

    /** Every other reader reads for one spell: the source is asked of that spell. */
    @Test(timeOut = 120_000)
    public void aReadForOneSpellSkipsTheSourceThatSpellDoesNotBuy() {
        constructedBoard();
        ready(ALTAR);
        final Card wurm = ready("Craw Wurm");
        final ManaCost black = new ManaCost(new ManaCostParser("B"));

        assertFalse(ComboMana.of(ai, consultCast, true).pays(black));
        assertFalse(ComboMana.nextTurn(ai, consultCast).pays(black));

        wurm.setSVar("SacMe", "1");
        assertTrue(ComboMana.of(ai, consultCast, true).pays(black));
        assertTrue(ComboMana.nextTurn(ai, consultCast).pays(black));
    }

    /** A real creature the spell is worth more than: a key card pays with a summoning-sick 2/2. */
    @Test(timeOut = 120_000)
    public void aCreatureWorthLessThanBothCastsPaysTheGoOff() {
        commanderBoard();
        final Card altar = ready(ALTAR);
        final Card bears = addCard("Grizzly Bears", ai);
        bears.setSickness(true);
        assertTrue(ManaSacrifice.worthIt(ai, manaOf(altar, ai), consultCast), "the harness: the payer's rule");

        assertTrue(ComboExecute.goOffNow(ai, oracleCast));
        assertTrue(payerPaysTheEmptierAfterOracle(), "the payer's own word for it");
    }

    /** A real {B} beside the refused source: the go-off is ready, on the source it does not need. */
    @Test(timeOut = 120_000)
    public void aRefusedSourceTakesNothingFromTheOtherMana() {
        constructedBoard();
        ready(ALTAR);
        ready("Craw Wurm");
        ready("Swamp");

        assertTrue(ComboExecute.goOffNow(ai, oracleCast));
    }

    /** Fodder the payer always takes: a marked creature costs nothing. */
    @Test(timeOut = 120_000)
    public void markedFodderPaysTheGoOff() {
        constructedBoard();
        final Card altar = ready(ALTAR);
        ready("Grizzly Bears").setSVar("SacMe", "1");
        assertTrue(ManaSacrifice.worthIt(ai, manaOf(altar, ai), consultCast), "the harness: the payer's rule");

        assertTrue(ComboExecute.goOffNow(ai, oracleCast));
        assertTrue(payerPaysTheEmptierAfterOracle(), "the payer's own word for it");
    }

    /** What is given up is what the AI would miss least, wherever it lies on the battlefield. */
    @Test(timeOut = 120_000)
    public void theCheapestCreatureIsTheOneGivenUp() {
        constructedBoard();
        ready(ALTAR);
        ready("Craw Wurm");
        final Card bears = ready("Grizzly Bears");
        bears.setSVar("SacMe", "1");

        assertTrue(ComboExecute.goOffNow(ai, oracleCast));

        // set aside for another cost of this payment, it is not there to give up
        AiCardMemory.rememberCard(ai, bears, AiCardMemory.MemorySet.PAYS_SAC_COST);
        assertFalse(ComboExecute.goOffNow(ai, oracleCast));
    }

    /** Two marked creatures: the payment's own choice shuffles them, and the reading must not draw. */
    @Test(timeOut = 120_000)
    public void theReadingDrawsNothing() {
        constructedBoard();
        final Card altar = ready(ALTAR);
        ready("Grizzly Bears").setSVar("SacMe", "1");
        ready("Runeclaw Bear").setSVar("SacMe", "1");
        MyRandom.setRandom(new Random(7));
        long before = MyRandom.callCount();
        ManaSacrifice.worthIt(ai, manaOf(altar, ai), consultCast);
        assertTrue(MyRandom.callCount() > before, "the harness: the payer's rule draws on this board");

        before = MyRandom.callCount();
        assertTrue(ComboExecute.goOffNow(ai, oracleCast));
        assertEquals(MyRandom.callCount(), before, "the reading drew");
    }

    /** A source that sacrifices itself is no ManaSacrifice source: Lotus Petal stays one mana of any colour. */
    @Test(timeOut = 120_000)
    public void aSourceThatSacrificesItselfIsNotAsked() {
        constructedBoard();
        ready("Lotus Petal");

        assertTrue(ComboExecute.goOffNow(ai, oracleCast));
    }
}
