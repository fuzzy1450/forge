package forge.ai.combo;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNotSame;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertSame;
import static org.testng.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.testng.annotations.Test;

import com.google.common.collect.Lists;

import forge.ai.AITest;
import forge.ai.AiController;
import forge.ai.LobbyPlayerAi;
import forge.ai.PlayerControllerAi;
import forge.ai.ability.ChangeZoneAi;
import forge.ai.simulation.ComboCatalog;
import forge.ai.simulation.CommanderPlan;
import forge.deck.Deck;
import forge.deck.DeckSection;
import forge.game.Game;
import forge.game.GameRules;
import forge.game.GameStage;
import forge.game.GameType;
import forge.game.Match;
import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.player.RegisteredPlayer;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;

/**
 * Evan's ruling of 2026-11-14 on the merge of upstream e5e2f900616 ("go with a - upstream decides everywhere", the
 * review's question 1, answer (a)): a deliberate refusal of the fork's combo program, ComboFetch's (danger with no win
 * in time, a line denied at its window, a hoser such as Rule of Law or Platinum Angel, a search another player
 * controls), is no veto on upstream's deck plan. Where ComboFetch.searchPick makes no pick, ChangeZoneAi's stock code
 * asks the deck plan (DeckPlan.tutorPick), whose catalogue step (upstream #12858: ComboCatalog.missingPieces,
 * ai-combos.txt) and, in a Commander game, the commander plan's key cards fill the refusal; ComboFetch still decides
 * first whenever it fetches (the merge's rule R5). The gate of f602a0c1949 (CatalogOverlap), which left out of the
 * catalogue step every combination the fork's combo table holds a line of, is reverted.
 * <p>
 * Every board here runs with the catalogue as shipped. ComboFetchAiTest's and ComboTrackerTest's run without it
 * ({@link NoComboCatalog}), where a refusal is the pick of a game without the catalogue; these boards are theirs.
 */
public class MergeRefusalFillTest extends AITest {
    private static final String WIN = "F|2|S|Win the game";
    private static final String EXILE = "F|88|H|Exile your library";
    private static final String ORACLE_CONSULTATION = "V|742-1295|3|{U}{U}{B}|2,88|H:Demonic Consultation|H:Thassa's Oracle";
    private static final String LAB_CONSULTATION = "V|742-2836|1|{B}|2,88|H:Demonic Consultation|B:Laboratory Maniac";

    private static final String ORACLE = "Thassa's Oracle";
    private static final String CONSULTATION = "Demonic Consultation";
    private static final String LAB = "Laboratory Maniac";
    private static final String JACE = "Jace, Wielder of Mysteries";

    // ComboFetchAiTest's ten distinct names, Consultation on top
    private static final List<String> LIBRARY = Arrays.asList(CONSULTATION, "Craw Wurm", "Runeclaw Bear",
            "Grizzly Bears", "Hill Giant", "Serra Angel", "Shivan Dragon", "Llanowar Elves", "Counterspell", "Divination");

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

    /** ComboFetchAiTest's start: a two-player game whose AI (player 1, on the Default profile, in its own MAIN1)
     *  tracks {@code rows}, with Island, Island and Swamp in play. */
    private void start(final String... rows) {
        game = initAndCreateGame();
        ai = game.getPlayers().get(1);
        opp = game.getPlayers().get(0);
        ((LobbyPlayerAi) ai.getLobbyPlayer()).setAiProfile("Default");
        t = aiOf(ai).getComboTracker();
        t.useLines(lines(rows));
        for (final String land : List.of("Island", "Island", "Swamp")) {
            addCard(land, ai);
        }
    }

    /** ComboFetchAiTest's danger board: line 742-1295, Oracle in hand, the ten-name library, a second Swamp, at 3 life
     *  against an opposing Colossal Dreadmaw that nothing of ours can block; DANGER is the line's only signal. */
    private void dangerBoard() {
        start(ORACLE_CONSULTATION);
        addCardToZone(ORACLE, ai, ZoneType.Hand);
        for (final String name : LIBRARY) {
            addCardToZone(name, ai, ZoneType.Library);
        }
        addCard("Swamp", ai);
        ai.setLife(3, null);
        addCard("Colossal Dreadmaw", opp).setSickness(false);
        assertEquals(ComboSignals.abandon(ai, t.deckLines().get(0), t), ComboSignals.Signal.DANGER);
    }

    /** ComboFetchAiTest's Laboratory Maniac danger board: line 742-2836, Lab in play, Opt in hand, the ten-name library,
     *  Island, Island and Swamp, at 3 life against an opposing Colossal Dreadmaw. */
    private void labDangerBoard() {
        start(LAB_CONSULTATION);
        addCard(LAB, ai);
        addCardToZone("Opt", ai, ZoneType.Hand);
        for (final String name : LIBRARY) {
            addCardToZone(name, ai, ZoneType.Library);
        }
        ai.setLife(3, null);
        addCard("Colossal Dreadmaw", opp).setSickness(false);
        assertEquals(ComboSignals.abandon(ai, t.deckLines().get(0), t), ComboSignals.Signal.DANGER);
    }

    private SpellAbility spell(final String name) {
        final SpellAbility sa = addCardToZone(name, ai, ZoneType.Hand).getFirstSpellAbility();
        sa.setActivatingPlayer(ai);
        return sa;
    }

    private CardCollection library() {
        return new CardCollection(ai.getCardsIn(ZoneType.Library));
    }

    /** ChangeZoneAi's chooser over our whole library, decided by us, as ComboFetchAiTest's pick calls it. */
    private Card pick(final ZoneType destination, final SpellAbility sa) {
        return ChangeZoneAi.chooseCardToHiddenOriginChangeZone(destination, List.of(ZoneType.Library), sa, library(),
                ai, ai);
    }

    /** ComboFetch refuses {@code sa}'s search, and the stock pick fills the refusal with the catalogue's piece,
     *  Consultation; without the catalogue it is another card, as ComboFetchAiTest's guards pin. */
    private void assertTheCatalogueFills(final SpellAbility sa, final ZoneType destination, final String why) {
        final Card consultation = t.find(CONSULTATION, ZoneType.Library);
        assertNull(ComboFetch.searchPick(sa, destination, library(), ai, ai), why + ": ComboFetch refuses");
        assertEquals(ComboCatalog.missingPieces(ai, library()), List.of(consultation), why + ": the catalogue's piece");
        assertSame(pick(destination, sa), consultation, why);
        final NoComboCatalog off = NoComboCatalog.begin();
        try {
            assertNotSame(pick(destination, sa), consultation, why + ", without the catalogue");
        } finally {
            off.end();
        }
    }

    /**
     * The review's test for answer (a) on Constructed boards (AITest's game), at three refusals of ComboFetch's that
     * ComboFetchAiTest's guards hold: Imperial Seal in our Main 1 on the danger board (the piece would reach our hand
     * only in our next draw step, after the opponent's attack: no win in time), Demonic Tutor there under an opposing
     * Platinum Angel (we cannot win: the line is DENIED), and Demonic Tutor in our Main 1 on the Laboratory Maniac board
     * under an opposing Rule of Law (Opt after Consultation would be the turn's second spell: no win in time). Each time
     * the pick is Demonic Consultation, the piece of a combo of ai-combos.txt one card short (Thassa's Oracle +
     * Demonic Consultation; Demonic Consultation + Laboratory Maniac). The gate of f602a0c1949 skipped it, as a
     * combination the fork's table holds (742-1295, 742-2836), and left the stock pick.
     */
    @Test
    public void aRefusalOfOurOwnSearchTakesTheCataloguesPiece() {
        dangerBoard();
        assertTheCatalogueFills(spell("Imperial Seal"), ZoneType.Library, "Imperial Seal under danger");

        dangerBoard();
        addCard("Platinum Angel", opp);
        game.getAction().checkStateEffects(true);
        assertEquals(ComboSignals.abandon(ai, t.deckLines().get(0), t), ComboSignals.Signal.DENIED);
        assertTheCatalogueFills(spell("Demonic Tutor"), ZoneType.Hand, "Platinum Angel");

        labDangerBoard();
        addCard("Rule of Law", opp);
        assertEquals(ComboSignals.abandon(ai, t.deckLines().get(0), t), ComboSignals.Signal.DANGER);
        assertTheCatalogueFills(spell("Demonic Tutor"), ZoneType.Hand, "Rule of Law");
    }

    /**
     * A search another player controls: Opposition Agent's, or a Mindslaver turn's (ComboTrackerTest's
     * ofIsNullForAControlledPlayer board). ComboTracker.of answers nothing for a controlled player, so ComboFetch makes
     * no pick, and the fresh AI deciding our search for the controlling player reads our deck: its catalogue step takes
     * Demonic Consultation, as our own search does through ComboFetch. Control ended, ComboFetch decides again.
     */
    @Test
    public void aSearchAnotherPlayerControlsTakesTheCataloguesPiece() {
        start(ORACLE_CONSULTATION);
        addCardToZone(ORACLE, ai, ZoneType.Hand);
        for (final String name : LIBRARY) {
            addCardToZone(name, ai, ZoneType.Library);
        }
        final Card consultation = t.find(CONSULTATION, ZoneType.Library);
        final SpellAbility tutor = spell("Demonic Tutor");
        assertSame(ComboFetch.searchPick(tutor, ZoneType.Hand, library(), ai, ai), consultation);

        final long timestamp = game.getNextTimestamp();
        ai.addController(timestamp, opp);
        assertTrue(ai.isControlled());
        assertNull(ComboTracker.of(ai));
        assertNull(ComboFetch.searchPick(tutor, ZoneType.Hand, library(), ai, ai));
        assertSame(pick(ZoneType.Hand, tutor), consultation);

        ai.removeController(timestamp);
        assertFalse(ai.isControlled());
        assertSame(ComboFetch.searchPick(tutor, ZoneType.Hand, library(), ai, ai), consultation);
    }

    /**
     * The review's test (e), F8 as the merge of 2026-11-05 recorded it: in a Commander game, which every harness game is
     * (forge.sim.GameRunner), the catalogue step comes before the commander plan's key cards. Our commander Lazav,
     * Dimir Mastermind in our command zone, Thassa's Oracle in hand, Jace, Wielder of Mysteries above Demonic
     * Consultation in our library (Consultation with Oracle and Consultation with Jace are pairs of the Commander lists,
     * so all three are the commander plan's key cards), Island, Island and Swamp, and an opposing Platinum Angel: we
     * cannot win, line 742-1295 is DENIED and ComboFetch refuses. Demonic Tutor takes Consultation, the catalogue's piece
     * for Oracle. Without the catalogue the key-card step takes Jace, the first key card of the library, as
     * 1785d1982ed's deck plan, which had no catalogue step, and the gate of f602a0c1949 did.
     */
    @Test
    public void inACommanderGameTheCatalogueFillsARefusalBeforeTheKeyCards() {
        final Deck ours = new Deck("ours");
        ours.getOrCreate(DeckSection.Commander).add("Lazav, Dimir Mastermind", 1);
        for (final String name : List.of(ORACLE, CONSULTATION, JACE, "Demonic Tutor")) {
            ours.getMain().add(name, 1);
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
        opp = game.getPlayers().get(0);
        ai = game.getPlayers().get(1);
        game.setAge(GameStage.Play);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, ai, 3);
        game.getPhaseHandler().onStackResolved();
        ComboLoopBoards.noDeadline(game);
        t = aiOf(ai).getComboTracker();
        t.useLines(lines(ORACLE_CONSULTATION));
        for (final String land : List.of("Island", "Island", "Swamp")) {
            addCard(land, ai);
        }
        addCardToZone(ORACLE, ai, ZoneType.Hand);
        final Card jace = addCardToZone(JACE, ai, ZoneType.Library);
        for (final String name : LIBRARY) {
            addCardToZone(name, ai, ZoneType.Library);
        }
        final Card consultation = t.find(CONSULTATION, ZoneType.Library);
        addCard("Platinum Angel", opp);
        game.getAction().checkStateEffects(true);
        final SpellAbility tutor = spell("Demonic Tutor");

        assertEquals(ai.getCommanders().size(), 1);
        assertSame(ai.getZone(ZoneType.Library).get(0), jace);
        assertSame(ai.getZone(ZoneType.Library).get(1), consultation);
        assertEquals(ComboSignals.abandon(ai, t.deckLines().get(0), t), ComboSignals.Signal.DENIED);
        assertNull(ComboFetch.searchPick(tutor, ZoneType.Hand, library(), ai, ai), "ComboFetch refuses");
        final CommanderPlan plan = aiOf(ai).deckPlan(ai).commander;
        assertTrue(plan.isActive());
        assertTrue(plan.isKeyCard(jace), "Jace is a key card");
        assertTrue(plan.isKeyCard(consultation), "Consultation is a key card");
        assertEquals(ComboCatalog.missingPieces(ai, library()), List.of(consultation), "the catalogue's piece");

        assertSame(pick(ZoneType.Hand, tutor), consultation);
        final NoComboCatalog off = NoComboCatalog.begin();
        try {
            assertSame(pick(ZoneType.Hand, tutor), jace, "without the catalogue, the first key card");
        } finally {
            off.end();
        }
    }
}
