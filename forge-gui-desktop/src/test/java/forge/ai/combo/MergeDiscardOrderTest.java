package forge.ai.combo;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNotNull;
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
import forge.ai.simulation.ComboPlan;
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
import forge.game.zone.ZoneType;

/**
 * The order of the discard's three deciders in AiController.getCardsToDiscard, since the merge of upstream 626aa9f0735
 * (its rule R5: the fork's hook decides first at a meeting point): ComboHold.spare takes a live line's pieces out of
 * the choice while more than the discard's number of cards stay, then upstream's deck plan (ComboPlan.preferredDiscards)
 * picks among what is left, then upstream's protection (ComboPlan and CommanderPlan's withoutProtected) keeps its cards
 * among what is left. Upstream's protection never gives a piece back: when it cannot hold, it yields. A deck holding no
 * line gets upstream's answers unchanged. On the Commander boards Kinnan, Bonder Prodigy sits in the command zone, which
 * makes Basalt Monolith in hand a protected piece of upstream's pair of the two, and our test line 742-1295 makes
 * Thassa's Oracle a protected piece of ours.
 */
public class MergeDiscardOrderTest extends AITest {
    private static final String WIN = "F|2|S|Win the game";
    private static final String EXILE = "F|88|H|Exile your library";
    private static final String ORACLE_CONSULTATION =
            "V|742-1295|3|{U}{U}{B}|2,88|H:Demonic Consultation|H:Thassa's Oracle";

    private static final String KINNAN = "Kinnan, Bonder Prodigy";
    private static final String ORACLE = "Thassa's Oracle";
    private static final String CONSULTATION = "Demonic Consultation";
    private static final String BASALT = "Basalt Monolith";
    private static final String PALINCHRON = "Palinchron";
    private static final String DEADEYE = "Deadeye Navigator";
    private static final String WURM = "Craw Wurm";

    /** FlatMonteCarloComboTest's reanimator list: Griselbrand is the big target its plan throws away. */
    private static final Object[] REANIMATOR = { "Reanimate", 4, "Animate Dead", 4, "Exhume", 2, "Entomb", 4,
            "Griselbrand", 4, "Archon of Cruelty", 3, "Faithless Looting", 4, "Careful Study", 2, "Thoughtseize", 4,
            "Swamp", 12, "Polluted Delta", 4, "Cabal Therapy", 2, "Dark Ritual", 3 };
    /** FlatMonteCarloComboTest's Living End list: any creature in hand is fuel for its plan. */
    private static final Object[] LIVING_END = { "Living End", 4, "Violent Outburst", 4, "Demonic Dread", 4,
            "Shardless Agent", 4, "Street Wraith", 4, "Architects of Will", 4, "Curator of Mysteries", 4,
            "Waker of Waves", 4, "Fury", 3, "Grief", 3, "Swamp", 8, "Mountain", 4, "Forest", 4 };

    private Game game;
    private Player ai;
    private ComboTracker t;

    private static AiController aiOf(final Player p) {
        return ((PlayerControllerAi) p.getController()).getAi();
    }

    private static List<ComboLines.Line> lines(final String... rows) {
        final List<String> all = new ArrayList<>(List.of(WIN, EXILE));
        all.addAll(Arrays.asList(rows));
        return ComboLines.claimed(ComboTable.parse(all));
    }

    private static List<String> names(final Iterable<Card> cards) {
        final List<String> out = new ArrayList<>();
        for (final Card c : cards) {
            out.add(ComboTracker.nameOf(c));
        }
        return out;
    }

    /** {@code n} of {@code cards} discarded (a fresh list each call: the chooser removes what it picks). */
    private List<String> discard(final int n, final Card... cards) {
        return names(aiOf(ai).getCardsToDiscard(n, n, new CardCollection(Arrays.asList(cards)), null));
    }

    private Card hand(final String name) {
        return addCardToZone(name, ai, ZoneType.Hand);
    }

    private void library(final Object... list) {
        for (int i = 0; i < list.length; i += 2) {
            for (int n = 0; n < (Integer) list[i + 1]; n++) {
                addCardToZone((String) list[i], ai, ZoneType.Library);
            }
        }
    }

    /** A Commander game: Kinnan in our command zone, {@code main} our registered main deck, our test line tracked, 30
     *  Islands and Demonic Consultation in our library, and the AI (player 1, Default profile) in its own Main 2. */
    private void kinnansGame(final String... main) {
        final Deck ours = new Deck("ours");
        ours.getOrCreate(DeckSection.Commander).add(KINNAN, 1);
        for (final String n : main) {
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
        t = aiOf(ai).getComboTracker();
        t.useLines(lines(ORACLE_CONSULTATION));
        library(CONSULTATION, 1, "Island", 30);
        for (final String land : List.of("Island", "Island", "Swamp")) {
            addCard(land, ai);
        }
    }

    /** A two-player Constructed game, the AI (player 1, Default profile) in its own Main 2. */
    private void constructedGame() {
        game = initAndCreateGame();
        ai = game.getPlayers().get(1);
        ((LobbyPlayerAi) ai.getLobbyPlayer()).setAiProfile("Default");
        game.AI_TIMEOUT = Integer.MAX_VALUE;
        game.AI_CAN_USE_TIMEOUT = false;
        moveToMain2(game, ai);
        t = aiOf(ai).getComboTracker();
    }

    /** The interplay review's P1: upstream's protection leaves exactly the discard's number of cards, our piece. */
    @Test(timeOut = 120_000)
    public void aLivePieceIsNotThrownAwayToKeepAPairPiece() {
        kinnansGame(ORACLE, CONSULTATION, BASALT, "Island", "Swamp");
        final Card oracle = hand(ORACLE);
        final Card basalt = hand(BASALT);
        assertTrue(t.protects(oracle), "the harness: Oracle is a live line's piece");

        assertEquals(discard(1, oracle, basalt), List.of(BASALT));
    }

    @Test(timeOut = 120_000)
    public void aDiscardOfTwoGivesUpThePairPieceBeforeTheLivePiece() {
        kinnansGame(ORACLE, CONSULTATION, BASALT, WURM, "Island", "Swamp");
        final Card oracle = hand(ORACLE);
        final Card basalt = hand(BASALT);
        final Card wurm = hand(WURM);

        final List<String> got = discard(2, oracle, basalt, wurm);

        assertEquals(got.size(), 2, got.toString());
        assertFalse(got.contains(ORACLE), got.toString());
    }

    /** Upstream's protection still decides among the cards the spare leaves. */
    @Test(timeOut = 120_000)
    public void aPairPieceIsKeptAmongWhatTheSpareLeaves() {
        kinnansGame(ORACLE, CONSULTATION, PALINCHRON, DEADEYE, WURM, "Island", "Swamp");
        addCard(DEADEYE, ai);
        final Card oracle = hand(ORACLE);
        final Card palinchron = hand(PALINCHRON);
        final Card wurm = hand(WURM);

        assertEquals(discard(1, oracle, palinchron, wurm), List.of(WURM));
    }

    /** No line: upstream's answer. Stock throws away the dearest card that is out of reach, Palinchron, which upstream's
     *  pair with Deadeye Navigator keeps. */
    @Test(timeOut = 120_000)
    public void aDeckHoldingNoLineKeepsUpstreamsProtection() {
        kinnansGame(ORACLE, CONSULTATION, PALINCHRON, DEADEYE, WURM, "Island", "Swamp");
        t.useLines(List.of());
        addCard(DEADEYE, ai);
        final Card palinchron = hand(PALINCHRON);
        final Card wurm = hand(WURM);

        assertEquals(discard(1, palinchron, wurm), List.of(WURM));
    }

    /** No line: upstream's deck plan. Six lands in play and one in hand: stock throws away the land. */
    @Test(timeOut = 120_000)
    public void aDeckHoldingNoLineKeepsUpstreamsDeckPlan() {
        constructedGame();
        library(REANIMATOR);
        for (int i = 0; i < 6; i++) {
            addCard("Swamp", ai);
        }
        final Card griselbrand = hand("Griselbrand");
        final Card swamp = hand("Swamp");
        assertTrue(aiOf(ai).deckPlan(ai).combos.has(ComboPlan.Kind.REANIMATOR));

        assertEquals(discard(1, griselbrand, swamp), List.of("Griselbrand"));
    }

    /** Upstream's deck plan picks among the cards the spare leaves: it would throw away our Oracle, a creature. */
    @Test(timeOut = 120_000)
    public void theDeckPlanPicksAmongWhatTheSpareLeaves() {
        constructedGame();
        t.useLines(lines(ORACLE_CONSULTATION));
        library(LIVING_END);
        library(CONSULTATION, 1);
        for (final String land : List.of("Island", "Island", "Swamp")) {
            addCard(land, ai);
        }
        final Card oracle = hand(ORACLE);
        final Card swamp = hand("Swamp");
        final ComboPlan combos = aiOf(ai).deckPlan(ai).combos;
        assertTrue(combos.has(ComboPlan.Kind.MASS_REANIMATION), combos.summary());
        assertTrue(t.protects(oracle), "the harness: Oracle is a live line's piece");
        final CardCollection plan = combos.preferredDiscards(ai, new CardCollection(List.of(oracle, swamp)), 1);
        assertNotNull(plan);
        assertEquals(names(plan), List.of(ORACLE), "the harness: the plan alone would throw the Oracle away");

        assertEquals(discard(1, oracle, swamp), List.of("Swamp"));
    }
}
