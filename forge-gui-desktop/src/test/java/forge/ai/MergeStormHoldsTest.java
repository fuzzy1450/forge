package forge.ai;

import static org.testng.AssertJUnit.assertEquals;
import static org.testng.AssertJUnit.assertFalse;
import static org.testng.AssertJUnit.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import org.testng.annotations.Test;

import com.google.common.collect.Lists;

import forge.ai.simulation.ComboPlan;
import forge.deck.Deck;
import forge.deck.DeckSection;
import forge.game.Game;
import forge.game.GameRules;
import forge.game.GameStage;
import forge.game.GameType;
import forge.game.Match;
import forge.game.ability.ApiType;
import forge.game.card.Card;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.player.RegisteredPlayer;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import forge.util.MyRandom;

/**
 * Upstream's tutor storm holds in the walk of AiController: a tutor storm deck keeps its fast mana, its cheap fodder,
 * Infernal Tutor and a take-life storm finisher for StormSolver, their only release, which runs under Flat Monte Carlo
 * alone. The holds stand where Flat MC is on, in a rollout of its game too, and a game with Flat MC off (every game of
 * the sim harness, GameRunner pins it) treats those cards as stock: the walk judges them, and the Storm block falls to
 * the stock minimum storm count. A deck that reads as no tutor storm is not touched either way.
 */
public class MergeStormHoldsTest extends AITest {
    /** FlatMonteCarloStormTest's main deck of "[Legacy] Beseech Storm", which the deck plan reads as a tutor storm. */
    private static final Object[] BESEECH_STORM = {
            "Ad Nauseam", 1, "Beseech the Mirror", 2, "Burning Wish", 2, "Chrome Mox", 4, "City of Traitors", 1,
            "Dark Ritual", 4, "Duress", 2, "Gaea's Will", 1, "Giant's Boulder", 4, "Infernal Tutor", 4,
            "Lion's Eye Diamond", 4, "Lotus Petal", 4, "Mox Opal", 4, "Skateboard", 1, "Song of Creation", 1,
            "Spire of Industry", 3, "Tendrils of Agony", 1, "Thoughtseize", 4, "Urza's Saga", 4, "Vault of Whispers", 4,
            "Veil of Summer", 2, "Wishclaw Talisman", 3 };
    private static final String TENDRILS = "Tendrils of Agony";

    private Game game;
    private Player ai;

    private static AiController aiOf(final Player p) {
        return ((PlayerControllerAi) p.getController()).getAi();
    }

    /** The interplay review's P5: {@code Flusterstorm} makes a storm finisher of its Storm keyword, and with three
     *  tutors and eight cheap mana cards the list reads as a tutor storm; with Counterspell in its place, as no combo.
     *  Sensei's Divining Top, Dark Ritual and Lotus Petal in hand, a Swamp and an Island on the battlefield, in the
     *  AI's own Main 2 of a Commander game. */
    private void commanderBoard(final boolean storm) {
        final Map<String, Integer> main = new LinkedHashMap<>();
        for (final String n : List.of("Demonic Tutor", "Diabolic Tutor", "Grim Tutor", "Sol Ring", "Mana Vault",
                "Mana Crypt", "Arcane Signet", "Fellwar Stone", "Mind Stone", "Lotus Petal", "Dark Ritual", "Chrome Mox",
                "Mox Diamond", "Sensei's Divining Top", "Thassa's Oracle", "Demonic Consultation")) {
            main.put(n, 1);
        }
        main.put(storm ? "Flusterstorm" : "Counterspell", 1);
        main.put("Island", 20);
        main.put("Swamp", 20);
        final Deck ours = new Deck("ours");
        ours.getOrCreate(DeckSection.Commander).add("Kinnan, Bonder Prodigy", 1);
        main.forEach((n, count) -> ours.getMain().add(n, count));
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
        main.forEach((n, count) -> {
            for (int i = 0; i < count; i++) {
                addCardToZone(n, ai, ZoneType.Library);
            }
        });
        move("Sensei's Divining Top", ZoneType.Hand);
        move("Dark Ritual", ZoneType.Hand);
        move("Lotus Petal", ZoneType.Hand);
        move("Swamp", ZoneType.Battlefield).setSickness(false);
        move("Island", ZoneType.Battlefield).setSickness(false);
        assertEquals(storm, aiOf(ai).deckPlan(ai).combos.has(ComboPlan.Kind.TUTOR_STORM));
    }

    /** Beseech Storm in the library of the AI (Default profile), Flat MC off, in its own Main 2, five Swamps in play and
     *  the opponent at 20; {@code hand} comes out of the library. */
    private void beseechBoard(final String... hand) {
        game = initAndCreateGame();
        game.AI_FLAT_MONTE_CARLO = false;
        ai = game.getPlayers().get(1);
        ((LobbyPlayerAi) ai.getLobbyPlayer()).setAiProfile("Default");
        game.getPlayers().get(1).setTeam(0);
        game.getPlayers().get(0).setTeam(1);
        game.AI_TIMEOUT = Integer.MAX_VALUE;
        game.AI_CAN_USE_TIMEOUT = false;
        for (int i = 0; i < BESEECH_STORM.length; i += 2) {
            for (int n = 0; n < (Integer) BESEECH_STORM[i + 1]; n++) {
                addCardToZone((String) BESEECH_STORM[i], ai, ZoneType.Library);
            }
        }
        for (final String name : hand) {
            move(name, ZoneType.Hand);
        }
        addCards("Swamp", 5, ai);
        game.getPlayers().get(0).setLife(20, null);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN2, ai);
        game.getAction().checkStateEffects(true);
        assertTrue(aiOf(ai).deckPlan(ai).combos.has(ComboPlan.Kind.TUTOR_STORM));
    }

    private Card move(final String name, final ZoneType to) {
        for (final Card c : ai.getCardsIn(ZoneType.Library)) {
            if (c.getName().equals(name)) {
                ai.getZone(ZoneType.Library).remove(c);
                ai.getZone(to).add(c);
                return c;
            }
        }
        throw new AssertionError(name + " is not in the library");
    }

    private void storm(final int count) {
        final Card any = ai.getCardsIn(ZoneType.Library).get(0);
        game.getStack().getSpellsCastThisTurn().clear();
        for (int i = 0; i < count; i++) {
            game.getStack().getSpellsCastThisTurn().add(new SpellAbility.EmptySa(ApiType.Draw, any));
        }
        game.updateStackForView();   // the stock minimum reads the count the view holds
    }

    private String decide() {
        MyRandom.setRandom(new Random(7));
        final List<SpellAbility> choice = aiOf(ai).chooseSpellAbilityToPlay();
        return choice == null || choice.isEmpty() ? null : choice.get(0).getHostCard().getName();
    }

    /** P5 with Flat MC off, what GameRunner pins: nothing can release a hold, so there is none. */
    @Test(timeOut = 120_000)
    public void withFlatMcOffATutorStormDeckPlaysItsFastManaAsAnyDeckDoes() {
        commanderBoard(false);
        final String plain = decide();
        assertEquals("the harness: a plain deck plays it", "Lotus Petal", plain);

        commanderBoard(true);
        assertFalse(aiOf(ai).flatMonteCarloActive());
        assertEquals(plain, decide());
    }

    /** What the three of P5's hand are held by; Flat MC on for this player, as A/B tests switch it on. */
    @Test(timeOut = 300_000)
    public void withFlatMcOnTheHoldsStand() {
        commanderBoard(true);
        aiOf(ai).setFlatMonteCarloOverride(true);
        assertTrue(aiOf(ai).flatMonteCarloActive());

        assertEquals(null, decide());
    }

    /** The game of a rollout has the flag off, by design, and holds as the game it copies does. */
    @Test(timeOut = 120_000)
    public void aRolloutHolds() {
        commanderBoard(true);
        game.markAsSimulationCopy();
        assertFalse(aiOf(ai).flatMonteCarloActive());

        assertEquals(null, decide());
    }

    @Test(timeOut = 120_000)
    public void flatMcDoesNotChangeADeckThatIsNoTutorStorm() {
        commanderBoard(false);
        game.markAsSimulationCopy();

        assertEquals("Lotus Petal", decide());
    }

    /** The Storm block's tutor storm arm: Tendrils at storm 3 of 20 life, Duress beside it. */
    @Test(timeOut = 120_000)
    public void withFlatMcOffATakeLifeStormCardMeetsOnlyTheStockMinimum() {
        beseechBoard(TENDRILS, "Duress");
        storm(0);
        assertFalse("below the stock minimum storm count", TENDRILS.equals(decide()));

        storm(3);
        assertEquals("from the minimum on", TENDRILS, decide());
    }

    /** The same board in a rollout: the Storm block's tutor storm arm holds it from the minimum on. */
    @Test(timeOut = 120_000)
    public void inARolloutATakeLifeStormCardWaitsForLethal() {
        beseechBoard(TENDRILS, "Duress");
        game.markAsSimulationCopy();
        storm(3);
        assertFalse(TENDRILS.equals(decide()));

        storm(9);
        assertEquals("lethal needs no solver", TENDRILS, decide());
    }

    /** The take-life finisher hold: Tendrils alone in hand, which the Storm block does not read. */
    @Test(timeOut = 120_000)
    public void withFlatMcOffATakeLifeFinisherAloneIsCastBelowLethal() {
        beseechBoard(TENDRILS);
        storm(3);

        assertEquals(TENDRILS, decide());
    }

    @Test(timeOut = 120_000)
    public void inARolloutATakeLifeFinisherAloneWaitsForLethal() {
        beseechBoard(TENDRILS);
        game.markAsSimulationCopy();
        storm(3);
        assertEquals(null, decide());

        storm(9);
        assertEquals("lethal needs no solver", TENDRILS, decide());
    }

    /** The cheap fodder follows the same gate as the fast mana. (Infernal Tutor's hold has no board here: its script's
     *  AI:RemoveDeck:All keeps it off the walk of every game but one with Flat MC on, where rollouts decide.) */
    @Test(timeOut = 120_000)
    public void theCheapFodderFollowsTheSameGate() {
        beseechBoard("Giant's Boulder");
        final SpellAbility cast = ai.getCardsIn(ZoneType.Hand).get(0).getFirstSpellAbility();
        cast.setActivatingPlayer(ai);
        assertTrue("the harness", aiOf(ai).deckPlan(ai).combos.wastesFodder(cast));
        assertEquals("Giant's Boulder", decide());

        beseechBoard("Giant's Boulder");
        game.markAsSimulationCopy();
        assertEquals(null, decide());
    }
}
