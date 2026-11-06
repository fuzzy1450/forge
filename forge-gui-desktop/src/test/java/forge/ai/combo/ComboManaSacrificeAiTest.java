package forge.ai.combo;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertSame;
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
import forge.game.ability.AbilityKey;
import forge.game.ability.AbilityUtils;
import forge.game.ability.ApiType;
import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.cost.Cost;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.player.PlayerCollection;
import forge.game.player.RegisteredPlayer;
import forge.game.spellability.SpellAbility;
import forge.game.trigger.Trigger;
import forge.game.trigger.TriggerType;
import forge.game.zone.ZoneType;
import forge.util.MyRandom;

/**
 * The mana of a go-off against upstream's sacrifice-for-mana rule: ComputerUtilMana pays with a mana ability that
 * sacrifices a permanent only when the spell is worth more than what it gives up (ManaSacrifice.worthIt), so a source
 * the payer would refuse is no mana of ComboMana's reading, and Thassa's Oracle waits for mana that will really pay
 * Demonic Consultation after it. By the emptier's moment Oracle is on the battlefield with its trigger on the stack, so
 * the payer can give up Oracle itself, which loses nothing, and in a Commander game it does: the pair's key card bonus
 * makes Consultation worth 202 and Pact 214, against Oracle's 125 and the margin's 60. In a Constructed game, the
 * bonus gone, Consultation is worth 52 and not even Oracle is given up for it. Phyrexian Altar's {B} is what the go-off
 * is one short of on these boards. The AI plays the Default profile in its own Main 2, with Oracle and Consultation in
 * hand and line 742-1295 tracked.
 */
public class ComboManaSacrificeAiTest extends AITest {
    private static final String WIN = "F|2|S|Win the game";
    private static final String EXILE = "F|88|H|Exile your library";
    private static final String ORACLE_CONSULTATION =
            "V|742-1295|3|{U}{U}{B}|2,88|H:Demonic Consultation|H:Thassa's Oracle";
    private static final String ORACLE_PACT = "V|1295-3093|4|{1}{U}{U}{B}|2|H:Tainted Pact|H:Thassa's Oracle";
    private static final String ORACLE = "Thassa's Oracle";
    private static final String CONSULTATION = "Demonic Consultation";
    private static final String PACT = "Tainted Pact";
    private static final String ALTAR = "Phyrexian Altar";
    private static final String TOWER = "Phyrexian Tower";
    private static final String WURM = "Craw Wurm";
    private static final String BEARS = "Grizzly Bears";

    /** Fifteen distinct names, none a land and none a piece of a line: a library Tainted Pact can empty. */
    private static final List<String> SINGLETON = List.of("Runeclaw Bear", "Hill Giant", "Serra Angel", "Shivan Dragon",
            "Llanowar Elves", "Counterspell", "Divination", "Coral Merfolk", "Gray Ogre", "Wind Drake", "Air Elemental",
            "Giant Spider", "Merfolk of the Pearl Trident", "Goblin Piker", "Elvish Mystic");

    /** The main-loop steps a played-out turn may take (LoopProgressTest's playOut: bounded, no timeOut). */
    private static final int MAX_STEPS = 400;

    private Game game;
    private Player ai;
    private Player opp;
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

    private ComboTracker tracker() {
        return ((PlayerControllerAi) ai.getController()).getAi().getComboTracker();
    }

    private void board() {
        board("Island", "Island");
    }

    private void board(final String... lands) {
        tracker().useLines(lines(ORACLE_CONSULTATION));
        final Card oracle = addCardToZone(ORACLE, ai, ZoneType.Hand);
        final Card consultation = addCardToZone(CONSULTATION, ai, ZoneType.Hand);
        fillLibrary(ai, 20);
        oracleCast = oracle.getFirstSpellAbility();
        oracleCast.setActivatingPlayer(ai);
        consultCast = consultation.getFirstSpellAbility();
        consultCast.setActivatingPlayer(ai);
        for (final String land : lands) {
            ready(land);
        }
    }

    private void constructedBoard() {
        game = initAndCreateGame();
        ai = game.getPlayers().get(1);
        ((LobbyPlayerAi) ai.getLobbyPlayer()).setAiProfile("Default");
        moveToMain2(game, ai);
        board();
    }

    /** A Commander game, nothing placed: Oracle, Consultation and Pact are key cards of upstream's two pairs, worth
     *  ManaSacrifice's bonus. */
    private void commanderGame() {
        final Deck ours = new Deck("ours");
        ours.getOrCreate(DeckSection.Commander).add("Kinnan, Bonder Prodigy", 1);
        for (final String n : List.of(ORACLE, CONSULTATION, PACT, "Island", "Swamp")) {
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
        opp = game.getPlayers().get(0);
        game.setAge(GameStage.Play);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN2, ai, 3);
        game.getPhaseHandler().onStackResolved();
        game.AI_TIMEOUT = Integer.MAX_VALUE;
        game.AI_CAN_USE_TIMEOUT = false;
    }

    private void commanderBoard() {
        commanderGame();
        board();
    }

    /** The rest of our turn, played by the game loop from the board as it stands. */
    private void playOurTurn() {
        MyRandom.setRandom(new Random(7));
        int steps = 0;
        while (steps++ < MAX_STEPS && !game.isGameOver() && game.getPhaseHandler().isPlayerTurn(ai)) {
            game.getPhaseHandler().mainLoopStep();
        }
    }

    private static SpellAbility manaOf(final Card altar, final Player ai) {
        final SpellAbility ma = altar.getManaAbilities().get(0);
        ma.setActivatingPlayer(ai);
        return ma;
    }

    /** Whether the payer pays the emptier at its moment: the Islands tapped, and Oracle on the battlefield with its
     *  trigger on the stack, where it can be given up like any other creature. */
    private boolean payerPaysTheEmptierAfterOracle() {
        for (final Card c : ai.getCardsIn(ZoneType.Battlefield)) {
            if (c.getName().equals("Island")) {
                c.setTapped(true);
            }
        }
        game.getAction().moveTo(ZoneType.Battlefield, oracleCast.getHostCard(), null, AbilityKey.newMap());
        game.getAction().checkStateEffects(true);
        game.getStack().addAllTriggeredAbilitiesToStack();
        assertEquals(game.getStack().peek().getSourceCard().getName(), ORACLE, "the harness: Oracle's trigger");
        return ComputerUtilCost.canPayCost(consultCast, ai, false);
    }

    /** A Constructed game, no key card bonus: Consultation is worth 52, so nothing dearer than fodder is given up for
     *  it, Oracle in play included, and the fork must not read ready. */
    @Test(timeOut = 120_000)
    public void aCreatureTheSpellIsNotWorthPaysNoGoOff() {
        constructedBoard();
        final Card altar = ready(ALTAR);
        ready("Craw Wurm");
        assertFalse(ManaSacrifice.worthIt(ai, manaOf(altar, ai), consultCast), "the harness: the payer's rule");

        assertFalse(ComboExecute.goOffNow(ai, oracleCast), "Oracle waits: the Altar's {B} will not be made");
        assertFalse(payerPaysTheEmptierAfterOracle(), "the payer's own word for it");
    }

    /** A 3/3 is more than Consultation buys off today's board, but today's board is not the emptier's: Oracle is there
     *  by then, cheaper, and the payer gives it up, its trigger being on the stack already. Ready, then, as the payer
     *  says at that moment. */
    @Test(timeOut = 120_000)
    public void theEmptierIsPaidWithOracleOnTheBattlefield() {
        commanderBoard();
        final Card altar = ready(ALTAR);
        ready("Centaur Courser");   // a 3/3: more than a 2/2 and less than Oracle's worth with its key card bonus
        assertTrue(ManaSacrifice.worthIt(ai, manaOf(altar, ai), oracleCast), "the harness: worth it for Oracle");
        assertFalse(ManaSacrifice.worthIt(ai, manaOf(altar, ai), consultCast), "the harness: not for Consultation yet");

        assertTrue(ComboExecute.goOffNow(ai, oracleCast));
        assertTrue(payerPaysTheEmptierAfterOracle(), "the payer's own word for it");
    }

    /** The boards the first reading of this rule held in hand, Oracle and Consultation, played by the game loop. The
     *  go-off's {B} is a sacrifice's alone, whatever the creature is worth: Oracle is cast, its trigger goes on the
     *  stack, Oracle is given up for Consultation, and the trigger wins, the creature kept. On the last two boards
     *  upstream's mana estimate has no line live, and the go-off still goes first, ahead of Kinnan. */
    @Test
    public void goOffsPlayedOutGiveUpOracleForTheEmptier() {
        for (final List<String> sources : List.of(
                List.of("Sol Ring", ALTAR, WURM),
                List.of("Sol Ring", ALTAR, BEARS),
                List.of(TOWER, WURM),
                List.of(TOWER, BEARS),
                List.of(ALTAR, WURM),
                List.of(ALTAR, BEARS))) {
            commanderBoard();
            sources.forEach(this::ready);
            final String fodder = sources.get(sources.size() - 1);
            playOurTurn();
            assertTrue(ai.hasWon(), sources + " held " + ai.getCardsIn(ZoneType.Hand));
            assertEquals(countCardsWithName(game, ORACLE, ZoneType.Graveyard), 1, sources + ": Oracle given up");
            assertEquals(countCardsWithName(game, fodder, ZoneType.Battlefield), 1, sources + ": the creature kept");
        }
    }

    /** Pact's {1}{B} the same way, the third Island its {1} and the Altar's {B} Oracle's life. */
    @Test
    public void aPactGoOffPlayedOutGivesUpOracleToo() {
        commanderGame();
        tracker().useLines(lines(ORACLE_PACT));
        addCardToZone(ORACLE, ai, ZoneType.Hand);
        addCardToZone(PACT, ai, ZoneType.Hand);
        SINGLETON.forEach(n -> addCardToZone(n, ai, ZoneType.Library));
        List.of("Island", "Island", "Island", ALTAR, WURM).forEach(this::ready);
        playOurTurn();
        assertTrue(ai.hasWon(), "held " + ai.getCardsIn(ZoneType.Hand));
        assertEquals(countCardsWithName(game, WURM, ZoneType.Battlefield), 1, "the Wurm kept");
    }

    /** The Altar on Oracle's own {U}, the Swamp paying the emptier: Bears are given up for Oracle, worth far more. */
    @Test
    public void theAltarPaysOraclesOwnPipWhenOracleIsWorthTheFodder() {
        commanderGame();
        board("Island", "Swamp");
        List.of("Sol Ring", ALTAR, BEARS).forEach(this::ready);
        assertTrue(ComboExecute.goOffNow(ai, oracleCast));
        playOurTurn();
        assertTrue(ai.hasWon(), "held " + ai.getCardsIn(ZoneType.Hand));
        assertEquals(countCardsWithName(game, BEARS, ZoneType.Graveyard), 1, "Bears given up for Oracle");
    }

    /** The read asks only of the emptier, so on Oracle's own pip it may read ready for a Wurm the payer will not give
     *  up (306 against 261 and the margin). That spends nothing: the payer refuses the cast and Oracle stays in
     *  hand. */
    @Test
    public void aFalseReadyOnOraclesOwnPipSpendsNoOracle() {
        commanderGame();
        board("Island", "Swamp");
        List.of("Sol Ring", ALTAR, WURM).forEach(this::ready);
        assertTrue(ComboExecute.goOffNow(ai, oracleCast));
        playOurTurn();
        assertFalse(ai.hasWon());
        assertEquals(countCardsWithName(game, ORACLE, ZoneType.Hand), 1, "Oracle was spent");
        assertEquals(countCardsWithName(game, CONSULTATION, ZoneType.Hand), 1, "Consultation was spent");
    }

    /** The sacrifice is asked of the emptier, the spell it pays for: a 1/1 token is given up for Oracle and not for a
     *  Consultation worth 52, in a Constructed game, so the go-off is not ready. */
    @Test(timeOut = 120_000)
    public void theSacrificeIsAskedOfTheEmptierAndNotOfOracle() {
        constructedBoard();
        final Card altar = ready(ALTAR);
        addToken("g_1_1_elf_warrior", ai).setSickness(false);
        assertTrue(ManaSacrifice.worthIt(ai, manaOf(altar, ai), oracleCast), "the harness: worth it for Oracle");
        assertFalse(ManaSacrifice.worthIt(ai, manaOf(altar, ai), consultCast), "the harness: not for Consultation");

        assertFalse(ComboExecute.goOffNow(ai, oracleCast));
    }

    // ---- the go-off's other two reads of the emptier's mana ----

    /** Our {@code name} cast: on our stack, nothing paid or resolved (ComboSignalsTest's castOnStack). */
    private void castOnStack(final String name) {
        final Card card = createCard(name, ai);
        card.setGameTimestamp(game.getNextTimestamp());
        game.getStackZone().add(card);
        final SpellAbility sa = card.getFirstSpellAbility();
        sa.setActivatingPlayer(ai);
        game.getStack().add(sa);
    }

    /** The opponent's Rhystic Study, a 20-card library for them to draw from, and its draw as its trigger resolves. */
    private SpellAbility rhysticDraw() {
        fillLibrary(opp, 20);
        for (final Trigger tr : addCard("Rhystic Study", opp).getTriggers()) {
            if (tr.getMode() == TriggerType.SpellCast) {
                final SpellAbility sa = tr.ensureAbility().copy(opp);
                sa.setTrigger(tr);
                assertEquals(sa.getApi(), ApiType.Draw);
                return sa;
            }
        }
        throw new AssertionError("no cast trigger on Rhystic Study");
    }

    /** Whether we pay the unless cost of {@code sa} as it resolves (AbilityUtils.handleUnlessCost's call). */
    private boolean paysUnless(final SpellAbility sa) {
        final Cost cost = AbilityUtils.calculateUnlessCost(sa, sa.getParam("UnlessCost"), true);
        return ((PlayerControllerAi) ai.getController()).payCostToPreventEffect(cost, sa, false,
                new PlayerCollection(ai));
    }

    /** Oracle cast and on our stack, its Islands tapped, Pact in hand, one Island, the Altar and a Craw Wurm open,
     *  Rhystic Study asking {1}: the {B} the Altar makes for Pact comes off Oracle, in play by then, so paying the {1}
     *  with the Island would leave Pact's {1} unpaid and Oracle spent for nothing. Stock pays. */
    private SpellAbility rhysticOnOracle() {
        commanderGame();
        tracker().useLines(lines(ORACLE_PACT));
        addCardToZone(PACT, ai, ZoneType.Hand);
        SINGLETON.forEach(n -> addCardToZone(n, ai, ZoneType.Library));
        ready("Island").setTapped(true);
        ready("Island").setTapped(true);
        List.of("Island", ALTAR, WURM).forEach(this::ready);
        castOnStack(ORACLE);
        return rhysticDraw();
    }

    @Test(timeOut = 120_000)
    public void rhysticsOneIsDeclinedWhenTheEmptierNeedsOraclesSacrifice() {
        SpellAbility rhystic = rhysticOnOracle();
        tracker().useLines(List.of());
        assertTrue(paysUnless(rhystic), "stock does not pay: this board tells nothing");

        rhystic = rhysticOnOracle();
        assertFalse(paysUnless(rhystic), "the {1} took Pact's mana");
    }

    /** Our Main 2 at 1 life before an opposing Colossal Dreadmaw that tramples over our Craw Wurm: DANGER is line
     *  742-1295's only signal. Oracle in hand, the library holding Consultation; Island, Island, Sol Ring, the Altar
     *  and the Wurm in play. Returns the library's Consultation. */
    private Card dangerBoard() {
        commanderGame();
        tracker().useLines(lines(ORACLE_CONSULTATION));
        addCardToZone(ORACLE, ai, ZoneType.Hand);
        final Card consultation = addCardToZone(CONSULTATION, ai, ZoneType.Library);
        SINGLETON.forEach(n -> addCardToZone(n, ai, ZoneType.Library));
        List.of("Island", "Island", "Sol Ring", ALTAR, WURM).forEach(this::ready);
        ai.setLife(1, null);
        addCard("Colossal Dreadmaw", opp).setSickness(false);
        assertEquals(ComboSignals.abandon(ai, tracker().deckLines().get(0), tracker()), ComboSignals.Signal.DANGER);
        assertFalse(ComboSignals.surerKillNow(ai));
        assertEquals(tracker().oneShort(), List.of());
        return consultation;
    }

    private Card pick(final ZoneType destination, final SpellAbility tutor) {
        return ComboFetch.searchPick(tutor, destination, new CardCollection(ai.getCardsIn(ZoneType.Library)), ai, ai);
    }

    private SpellAbility tutor(final String name) {
        final SpellAbility sa = addCardToZone(name, ai, ZoneType.Hand).getFirstSpellAbility();
        sa.setActivatingPlayer(ai);
        return sa;
    }

    /** Danger's win in time reads the go-off's mana as goOffNow reads it, so the piece a fetch takes is one the go-off
     *  then goes ahead with: in this turn's main phase and, at the end step before our turn, in our next one.
     *  ComboFetch is asked itself: with no line upstream's deck plan takes the pair's piece for a tutor as well, but
     *  not for a dig or a scry. */
    @Test(timeOut = 120_000)
    public void dangersWinInTimeReadsTheEmptierWithOracleInPlay() {
        Card consultation = dangerBoard();
        assertSame(pick(ZoneType.Hand, tutor("Demonic Tutor")), consultation);

        consultation = dangerBoard();
        game.getPhaseHandler().devModeSet(PhaseType.END_OF_TURN, opp);
        assertSame(game.getPhaseHandler().getNextTurn(), ai);
        assertEquals(ComboSignals.abandon(ai, tracker().deckLines().get(0), tracker()), ComboSignals.Signal.DANGER);
        assertSame(pick(ZoneType.Library, tutor("Vampiric Tutor")), consultation);
    }

    /** With no Altar to read the fetch takes nothing: the mana the test above reads is the sacrifice's. */
    @Test(timeOut = 120_000)
    public void dangersWinInTimeNeedsTheSacrificesMana() {
        dangerBoard();
        final Card altar = tracker().find(ALTAR, ZoneType.Battlefield);
        game.getAction().moveTo(ZoneType.Graveyard, altar, null, AbilityKey.newMap());
        assertNull(pick(ZoneType.Hand, tutor("Demonic Tutor")));
    }

    /** Oracle is no fodder of its own cast: with the emptier cast already, danger's read is Oracle's alone, and its
     *  second {U} comes from the Altar. The Bears pay it (306 against 161 and the margin), the Wurm does not (261). */
    @Test(timeOut = 120_000)
    public void oracleIsNoFodderOfItsOwnCast() {
        for (final String fodder : List.of(BEARS, WURM)) {
            commanderGame();
            tracker().useLines(lines(ORACLE_CONSULTATION));
            final Card oracle = addCardToZone(ORACLE, ai, ZoneType.Library);
            SINGLETON.forEach(n -> addCardToZone(n, ai, ZoneType.Library));
            castOnStack(CONSULTATION);
            List.of("Island", "Sol Ring", ALTAR, fodder).forEach(this::ready);
            ai.setLife(1, null);
            addCard("Colossal Dreadmaw", opp).setSickness(false);
            assertEquals(ComboSignals.abandon(ai, tracker().deckLines().get(0), tracker()), ComboSignals.Signal.DANGER);
            assertEquals(pick(ZoneType.Hand, tutor("Demonic Tutor")), fodder.equals(BEARS) ? oracle : null, fodder);
        }
    }

    /** What enters is fodder only of its own type: a Sol Ring on its way is no creature for the Altar, and the Wurm
     *  stays the sacrifice. Oracle is one, and a card already on the battlefield counts once. */
    @Test(timeOut = 120_000)
    public void whatEntersIsFodderOnlyOfItsOwnType() {
        commanderBoard();
        final SpellAbility ma = manaOf(ready(ALTAR), ai);
        final Card wurm = ready(WURM);
        final Card ring = addCardToZone("Sol Ring", ai, ZoneType.Hand);
        assertFalse(ManaSacrifice.worthItBeforePaying(ai, ma, consultCast, null), "the harness: the Wurm is too dear");
        assertFalse(ManaSacrifice.worthItBeforePaying(ai, ma, consultCast, ring));
        assertFalse(ManaSacrifice.worthItBeforePaying(ai, ma, consultCast, wurm));
        assertTrue(ManaSacrifice.worthItBeforePaying(ai, ma, consultCast, oracleCast.getHostCard()));
    }

    /** A Commander game's Oracle is no fodder for a spell worth less than it and the margin: the bonus decides it, not
     *  Oracle's being there. Divination, no key card, is worth 76. */
    @Test(timeOut = 120_000)
    public void whatEntersIsGivenUpOnlyForASpellWorthIt() {
        commanderBoard();
        final SpellAbility ma = manaOf(ready(ALTAR), ai);
        ready(WURM);
        final SpellAbility divination = addCardToZone("Divination", ai, ZoneType.Hand).getFirstSpellAbility();
        divination.setActivatingPlayer(ai);
        assertFalse(ManaSacrifice.worthItBeforePaying(ai, ma, divination, oracleCast.getHostCard()));
    }

    /** What enters joins what is on the battlefield: marked fodder there is still what is given up. */
    @Test(timeOut = 120_000)
    public void whatEntersIsReadBesideTheCheapestOnTheBattlefield() {
        constructedBoard();
        final SpellAbility ma = manaOf(ready(ALTAR), ai);
        ready(BEARS).setSVar("SacMe", "1");
        ready(WURM);
        assertTrue(ManaSacrifice.worthItBeforePaying(ai, ma, consultCast, oracleCast.getHostCard()));
    }

    /** Next turn's read asks the same question of the same board. */
    @Test(timeOut = 120_000)
    public void nextTurnsReadCountsWhatEnters() {
        commanderBoard();
        ready(ALTAR);
        ready(WURM);
        final ManaCost black = new ManaCost(new ManaCostParser("B"));
        assertFalse(ComboMana.nextTurn(ai, consultCast).pays(black));
        assertTrue(ComboMana.nextTurn(ai, consultCast, oracleCast.getHostCard()).pays(black));
    }

    /** A read for one spell, nothing entering: the source is asked of that spell, on today's board. */
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
