package forge.ai.combo;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertSame;
import static org.testng.Assert.assertTrue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

import org.testng.annotations.Test;

import forge.ai.AITest;
import forge.ai.AiController;
import forge.ai.LobbyPlayerAi;
import forge.game.Game;
import forge.game.GameObject;
import forge.game.GameStage;
import forge.game.ability.AbilityFactory;
import forge.game.ability.ApiType;
import forge.game.card.Card;
import forge.game.card.CounterEnumType;
import forge.game.phase.PhaseType;
import forge.game.player.GameLossReason;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.spellability.SpellAbilityStackInstance;
import forge.game.trigger.Trigger;
import forge.game.trigger.WrappedAbility;
import forge.game.zone.MagicStack;
import forge.game.zone.ZoneType;
import forge.util.MyRandom;

/**
 * Going off in response to removal (spec 2026-10-29-combo-aware-ai-loops-design.md section 6.6; the plan's Task B9,
 * R5, R16, R27 and R28): with an opponent's spell on the stack that would take a piece of a loop line of an
 * instant-speed kind, or Walking Ballista, off our battlefield, the loop goes off above it and Ballista shoots to exact
 * lethal before it resolves, ahead of any counterspell, on either player's turn; a go-off of our main phase that the
 * removal meets mid-cycle goes on above it, and with no Ballista takes no step a gone piece needs. No response comes
 * without a Ballista on our battlefield, for a sorcery-speed kind, for an item that threatens nothing of the loop's, or
 * for a volley the caps cannot hold. Unless a test says otherwise the board is Kinnan's list registered, the tracker
 * reading the shipped lines, both seats on the Default profile, no AI deadline, twenty cards in each library, the
 * opponent at 20, and the moment the opponent's Main 1 of the game's second turn, their removal put on the stack by
 * hand ({@link ComboLoopBoards#theirSpellOn}). The plan's kills run through the whole game loop ({@link #play}), where
 * every seat decides; {@link #respond} plays the driver's own responses.
 */
public class ComboLoopResponseAiTest extends AITest {
    private static final String KINNAN = "Kinnan, Bonder Prodigy";
    private static final String BASALT = "Basalt Monolith";
    private static final String NYXBLOOM = "Nyxbloom Ancient";
    private static final String PALINCHRON = "Palinchron";
    private static final String DEADEYE = "Deadeye Navigator";
    private static final String HULLBREAKER = "Hullbreaker Horror";
    private static final String BALLISTA = LoopSinks.BALLISTA;
    private static final String BEAR = "Runeclaw Bear";
    private static final String DREADMAW = "Colossal Dreadmaw";
    private static final String COUNTERSPELL = "Counterspell";
    private static final String MURDER = "Murder";
    private static final String ISLAND = "Island";
    private static final String FOREST = "Forest";
    /** The rock untap of Kinnan and Basalt Monolith. */
    private static final String KINNAN_BASALT = "129-4131";
    /** The rock untap of Basalt Monolith and Nyxbloom Ancient. */
    private static final String BASALT_NYXBLOOM = "3430-4131";
    /** The flicker of Palinchron and Deadeye Navigator. */
    private static final String FLICKER = "1409-3542";
    /** The return and recast of Palinchron beside Nyxbloom Ancient. */
    private static final String RECAST = "3430-3542";
    /** The game-loop steps a stepping test may take: a volley of some two hundred actions at up to three steps each,
     *  and slack. */
    private static final int MAX_STEPS = 2_000;
    /** The driver's responses a driving test may take. */
    private static final int MAX_ACTIONS = 1_000;
    /** Kinnan's list beside its commander: every piece of the 19 loop rows, the sinks, lands and Counterspell. */
    private static final List<String> KINNANS_LIST = kinnansList();

    private Game game;
    private Player ai;
    private Player opp;
    private ComboTracker t;
    private LoopDriver driver;
    private Card basalt;
    /** The random draws made inside the driver's decisions while {@link #respond} ran, since the board was set. */
    private long decisionDraws;

    /** One of our stack items as the game loop first put it there: the ability, its host, and whether the removal was
     *  beneath it then. */
    private record Item(SpellAbility sa, Card host, boolean above) {
        boolean pump() {
            return ballista() && sa.getApi() == ApiType.PutCounter;
        }

        boolean ping() {
            return ballista() && sa.getApi() == ApiType.DealDamage;
        }

        boolean flicker() {
            return PALINCHRON.equals(ComboTracker.nameOf(host)) && sa.getApi() == ApiType.ChangeZone;
        }

        private boolean ballista() {
            return BALLISTA.equals(ComboTracker.nameOf(host));
        }
    }

    private static List<String> kinnansList() {
        final Set<String> names = new LinkedHashSet<>();
        for (final ComboLines.Line l : ComboLoopBoards.shippedLoopLines(LoopRows.ids().toArray(new String[0]))) {
            names.addAll(l.names());
        }
        names.remove(KINNAN);
        names.addAll(List.of(BALLISTA, "Thrasios, Triton Hero", FOREST, ISLAND, COUNTERSPELL));
        return List.copyOf(names);
    }

    /** A two-player game whose AI (player 1, in its own Main 1, no AI deadline) has Kinnan's list registered, both
     *  seats on the Default profile, twenty Runeclaw Bears in each library; nothing else placed yet. */
    private void start() {
        game = initAndCreateGame();
        ComboLoopBoards.noDeadline(game);
        ai = game.getPlayers().get(1);
        opp = game.getPlayers().get(0);
        ((LobbyPlayerAi) ai.getLobbyPlayer()).setAiProfile("Default");
        ((LobbyPlayerAi) opp.getLobbyPlayer()).setAiProfile("Default");
        ComboLoopBoards.deck(ai, KINNAN, KINNANS_LIST.toArray(new String[0]));
        t = ComboLoopBoards.aiOf(ai).getComboTracker();
        driver = ComboLoopBoards.aiOf(ai).getLoopDriver();
        decisionDraws = 0;
        basalt = null;
        fillLibrary(ai, 20);
        fillLibrary(opp, 20);
    }

    /** Kinnan (ready) and Basalt Monolith on our battlefield: the rock untap 129-4131. Returns Kinnan. */
    private Card rock() {
        final Card kinnan = ComboLoopBoards.ready(addCard(KINNAN, ai));
        basalt = addCard(BASALT, ai);
        return kinnan;
    }

    /** A Walking Ballista of ours on our battlefield, ready, with {@code counters} +1/+1 counters. */
    private Card ballista(final int counters) {
        final Card b = ComboLoopBoards.ready(addCard(BALLISTA, ai));
        b.setCounters(CounterEnumType.P1P1, counters);
        return b;
    }

    /** Palinchron and Deadeye Navigator, ready on our battlefield and paired: Deadeye's {1}{U} flicker granted to both.
     *  Returns Palinchron. */
    private Card paired() {
        final Card palinchron = ComboLoopBoards.ready(addCard(PALINCHRON, ai));
        final Card deadeye = ComboLoopBoards.ready(addCard(DEADEYE, ai));
        palinchron.setPairedWith(deadeye);
        deadeye.setPairedWith(palinchron);
        game.getAction().checkStaticAbilities();
        return palinchron;
    }

    /** {@code n} untapped lands of ours named {@code name}, in their order on our battlefield. */
    private List<Card> lands(final String name, final int n) {
        final List<Card> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            out.add(addCard(name, ai));
        }
        return out;
    }

    /** The opponent's Main 1 of the game's second turn. */
    private void theirTurn() {
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, opp, 2);
    }

    /** The deck line of that id. */
    private ComboLines.Line line(final String id) {
        for (final ComboLines.Line l : t.deckLines()) {
            if (l.variant().id().equals(id)) {
                return l;
            }
        }
        throw new AssertionError("the deck holds no line " + id);
    }

    /** The card's own {T} mana ability, found here and not through the driver. */
    private static SpellAbility manaOf(final Card c) {
        for (final SpellAbility sa : c.getManaAbilities()) {
            if (sa.getApi() == ApiType.Mana) {
                return sa;
            }
        }
        throw new AssertionError(c + " has no mana ability");
    }

    /** The card's own activated Untap ability. */
    private static SpellAbility untapOf(final Card c) {
        for (final SpellAbility sa : c.getNonManaAbilities()) {
            if (sa.isActivatedAbility() && sa.getApi() == ApiType.Untap) {
                return sa;
            }
        }
        throw new AssertionError(c + " has no untap");
    }

    /** Our first card on our battlefield by its printed name (ComboTracker.nameOf), or null. */
    private Card onOurBattlefield(final String name) {
        for (final Card c : ai.getCardsIn(ZoneType.Battlefield)) {
            if (name.equals(ComboTracker.nameOf(c))) {
                return c;
            }
        }
        return null;
    }

    /** Whether our battlefield holds that very object. */
    private boolean stillThere(final Card c) {
        for (final Card in : ai.getCardsIn(ZoneType.Battlefield)) {
            if (in == c) {
                return true;
            }
        }
        return false;
    }

    /** Whether an item of {@code c}'s is on the stack. */
    private boolean onTheStack(final Card c) {
        for (final SpellAbilityStackInstance si : game.getStack()) {
            if (c.equals(si.getSourceCard())) {
                return true;
            }
        }
        return false;
    }

    private static int count(final List<Item> items, final Predicate<Item> kind) {
        int n = 0;
        for (final Item i : items) {
            if (kind.test(i)) {
                n++;
            }
        }
        return n;
    }

    /** The opponent's {@code name} on the stack alone, cast at {@code targets}; whether it threatens {@code cards}
     *  (LoopResponse.threatens), a reading that must draw no random number; the stack cleared after. */
    private boolean threatens(final String name, final List<Card> cards, final GameObject... targets) {
        ComboLoopBoards.theirSpellOn(game, opp, name, targets);
        final long before = MyRandom.callCount();
        final boolean out = LoopResponse.threatens(ai, game.getStack().peek(), cards);
        assertEquals(MyRandom.callCount() - before, 0L, name + ": the reading drew");
        game.getStack().clear();
        return out;
    }

    /** Steps the whole game loop (PhaseHandler.mainLoopStep: every seat decides, the stock AI included) while the game
     *  is on and {@code removal} is on the stack, at most {@link #MAX_STEPS} steps; returns our stack items the loop
     *  put there meanwhile (those already there aside), in the order it put them there, each as first seen, with
     *  whether {@code removal} was beneath it then. */
    private List<Item> play(final Card removal) {
        final List<Item> items = new ArrayList<>();
        final Set<Integer> seen = new HashSet<>();
        for (final SpellAbilityStackInstance si : game.getStack()) {
            seen.add(si.getId());
        }
        for (int step = 0; step < MAX_STEPS && !game.isGameOver() && onTheStack(removal); step++) {
            game.getPhaseHandler().mainLoopStep();
            final List<SpellAbilityStackInstance> stack = new ArrayList<>();
            game.getStack().forEach(stack::add);
            int at = -1;
            for (int i = 0; i < stack.size(); i++) {
                if (removal.equals(stack.get(i).getSourceCard())) {
                    at = i;
                }
            }
            for (int i = stack.size() - 1; i >= 0; i--) {
                final SpellAbilityStackInstance si = stack.get(i);
                if (seen.add(si.getId()) && ai.equals(si.getActivatingPlayer())) {
                    items.add(new Item(si.getSpellAbility(), si.getSourceCard(), i < at));
                }
            }
        }
        return items;
    }

    /** Plays the driver's responses, one decision at a time, each through the AI's own play path
     *  (PlayerControllerAi.playChosenSpellAbility: paid, then resolved at once or put on the stack), the stack then
     *  resolved down to {@code removal}'s item, which stays, until the driver returns null, the game is over or
     *  {@code max} actions; returns them in order. The state-based actions are checked before each decision, as the
     *  game loop checks them before every priority; the random draws made inside the decisions are added to
     *  {@link #decisionDraws}. */
    private List<SpellAbility> respond(final Card removal, final int max) {
        final List<SpellAbility> acts = new ArrayList<>();
        while (acts.size() < max) {
            game.getAction().checkStateEffects(true);
            if (game.isGameOver()) {
                break;
            }
            final long before = MyRandom.callCount();
            final SpellAbility sa = driver.responseAction();
            decisionDraws += MyRandom.callCount() - before;
            if (sa == null) {
                break;
            }
            acts.add(sa);
            ai.getController().playChosenSpellAbility(sa);
            resolveDownTo(removal);
        }
        return acts;
    }

    /** Resolves the stack, and every trigger it adds, down to {@code removal}'s item, which stays; bounded. */
    private void resolveDownTo(final Card removal) {
        final MagicStack stack = game.getStack();
        for (int i = 0; i < 100; i++) {
            stack.addAllTriggeredAbilitiesToStack();
            if (stack.isEmpty() || removal.equals(stack.peek().getSourceCard())) {
                return;
            }
            stack.resolveStack();
        }
        throw new AssertionError("the stack did not resolve down to " + removal);
    }

    /** {@code rounds} after {@code n} actions counted this turn, as the driver counts each one it returns
     *  (LoopRounds.act), none of them refused. */
    private LoopRounds afterActions(final LoopRounds rounds, final int n) {
        for (int i = 0; i < n; i++) {
            assertTrue(rounds.act(ai), "an action refused");
        }
        return rounds;
    }

    /** Our Main 1 through the game loop until the go-off has put Basalt's untap on the stack, alone there. */
    private void ourUntapOnTheStack() {
        ComboLoopBoards.stepUntil(game, g -> !g.getStack().isEmpty(), MAX_STEPS);
        assertEquals(game.getStack().size(), 1, "one item on the stack");
        final SpellAbilityStackInstance si = game.getStack().peek();
        assertEquals(si.getSourceCard(), basalt, "Basalt's item");
        assertEquals(si.getSpellAbility().getApi(), ApiType.Untap, "Basalt's untap");
        assertTrue(game.getPhaseHandler().is(PhaseType.MAIN1, ai), "our Main 1: " + game.getPhaseHandler().getPhase());
    }

    private void opponentLostByLife() {
        assertTrue(game.isGameOver(), "the game went on: " + game.getPhaseHandler().getPlayerTurn() + "'s "
                + game.getPhaseHandler().getPhase() + ", the stack " + game.getStack());
        assertNotNull(opp.getOutcome(), "the opponent is still in the game");
        assertEquals(opp.getOutcome().lossState, GameLossReason.LifeReachedZero, "the opponent's loss");
    }

    // ---- the plan's tests ----

    // On the opponent's turn their Murder on Kinnan goes on the stack, Ballista on ours with one counter and the
    // opponent at 20: the rock loop goes off above it, every untap, pump and ping of ours put on the stack over Murder,
    // Ballista pumped to 20 counters and shooting 20 times, and the opponent loses for having 0 life before Murder
    // resolves, in their own Main 1: Kinnan never leaves our battlefield.
    @Test
    public void goesOffInResponseToMurderOnKinnan() {
        start();
        final Card kinnan = rock();
        ballista(1);
        theirTurn();
        final Card murder = ComboLoopBoards.theirSpellOn(game, opp, MURDER, kinnan);
        final List<Item> items = play(murder);
        opponentLostByLife();
        assertSame(onOurBattlefield(KINNAN), kinnan, "Murder resolved: Kinnan left our battlefield");
        assertEquals(count(items, Item::pump), 19, "the pumps: " + items);
        assertEquals(count(items, Item::ping), 20, "the pings: " + items);
        for (final Item i : items) {
            assertTrue(i.above(), "an item of ours not above Murder: " + i);
        }
        assertSame(game.getPhaseHandler().getPlayerTurn(), opp, "the game ended on the opponent's turn");
        assertTrue(game.getPhaseHandler().is(PhaseType.MAIN1), "in their Main 1");
    }

    // The flicker in response: Palinchron and Deadeye Navigator paired, seven Islands, Ballista with one counter, the
    // opponent's Murder on Palinchron on their turn. Its volley takes 167 actions (16 cycles of seven taps and a
    // flicker make the 19 pumps' 76, then 20 pings); the first flicker makes Palinchron a new object, so Murder's
    // target is gone and it reads as a threat no more, yet the go-off goes on above it, and the opponent loses before
    // it resolves.
    @Test
    public void goesOffInResponseWithTheFlicker() {
        start();
        final Card palinchron = paired();
        lands(ISLAND, 7);
        ballista(1);
        theirTurn();
        final Card murder = ComboLoopBoards.theirSpellOn(game, opp, MURDER, palinchron);
        assertEquals(LoopResponse.volleyActions(ai, line(FLICKER)), 167L, "the volley's actions");
        final List<Item> items = play(murder);
        opponentLostByLife();
        assertEquals(count(items, Item::pump), 19, "the pumps: " + items);
        assertEquals(count(items, Item::ping), 20, "the pings: " + items);
        assertTrue(count(items, Item::flicker) >= 16, "the flickers: " + items);
        for (final Item i : items) {
            assertTrue(i.above(), "an item of ours not above Murder: " + i);
        }
        assertFalse(stillThere(palinchron), "the first flicker made Palinchron a new object");
        assertNotNull(onOurBattlefield(PALINCHRON), "Palinchron left our battlefield");
        assertNotNull(onOurBattlefield(DEADEYE));
    }

    // DANGER (the opponent's ready Colossal Dreadmaw against our 3 life) stops holding and fetching, never a win now:
    // the response to Murder on Kinnan still comes, and the opponent loses before Murder resolves.
    @Test
    public void anOpposingLethalBoardDoesNotStopTheResponse() {
        start();
        final Card kinnan = rock();
        ballista(1);
        ai.setLife(3, null);
        ComboLoopBoards.ready(addCard(DREADMAW, opp));
        theirTurn();
        assertEquals(ComboSignals.abandon(ai, line(KINNAN_BASALT), t), ComboSignals.Signal.DANGER);
        final Card murder = ComboLoopBoards.theirSpellOn(game, opp, MURDER, kinnan);
        final List<Item> items = play(murder);
        opponentLostByLife();
        assertSame(onOurBattlefield(KINNAN), kinnan, "Murder resolved");
        assertEquals(count(items, Item::ping), 20, "the pings: " + items);
        assertEquals(ai.getLife(), 3, "we were attacked");
    }

    // Each removal aimed at a loop piece threatens it: Murder (destroy), Swords to Plowshares (exile), Unsummon (to
    // hand), Spin into Myth (to the library), Dismember (-5/-5), Act of Treason (control), Lightning Bolt (3 damage to
    // a 2/2) and Grim Affliction (a -1/-1 counter and a proliferate: two counters on a 2/2), each on Kinnan.
    // Divination, Lightning Bolt aimed at the opponent's own creature and a single -1/-1 counter on Hullbreaker Horror
    // (Scar, no proliferate: a 7/8) threaten nothing. Each reading draws nothing.
    @Test
    public void threatensReadsEachRemoval() {
        start();
        final Card kinnan = rock();
        final Card ballista = ballista(1);
        final Card hullbreaker = addCard(HULLBREAKER, ai);
        final Card theirs = addCard(BEAR, opp);
        final List<Card> pieces = List.of(kinnan, basalt, ballista);
        theirTurn();
        for (final String removal : List.of(MURDER, "Swords to Plowshares", "Unsummon", "Spin into Myth", "Dismember",
                "Act of Treason", "Lightning Bolt", "Grim Affliction")) {
            assertTrue(threatens(removal, pieces, kinnan), removal + " on Kinnan");
        }
        assertFalse(threatens("Divination", pieces), "Divination");
        assertFalse(threatens("Lightning Bolt", pieces, theirs), "Lightning Bolt on the opponent's own creature");
        assertFalse(threatens("Scar", List.of(hullbreaker), hullbreaker), "one -1/-1 counter on a 7/8");
    }

    // R27: Diabolic Edict targets a player and that player chooses the sacrifice, so aimed at us it threatens a piece
    // only when every creature we control is a loop piece or Ballista: true with Kinnan and Ballista our only
    // creatures, false with a Runeclaw Bear of ours beside them. Aimed at its caster it threatens nothing of ours.
    @Test
    public void diabolicEdictThreatensOnlyWithoutASpareCreature() {
        start();
        final Card kinnan = rock();
        final Card ballista = ballista(1);
        final List<Card> pieces = List.of(kinnan, basalt, ballista);
        theirTurn();
        assertTrue(threatens("Diabolic Edict", pieces, ai), "Kinnan and Ballista our only creatures");
        assertFalse(threatens("Diabolic Edict", pieces, opp), "aimed at its caster");
        addCard(BEAR, ai);
        assertFalse(threatens("Diabolic Edict", pieces, ai), "a Runeclaw Bear to sacrifice instead");
    }

    // R28: the opponent at 100,000: Murder on Kinnan draws no response, since the volley (the cycles that make its
    // pumps' mana, the pumps and the pings) cannot fit in what the turn's cap has left. The control: at 20 it does.
    @Test
    public void noResponseWhenTheVolleyCannotFinish() {
        start();
        final Card kinnan = rock();
        ballista(1);
        opp.setLife(100_000, null);
        theirTurn();
        ComboLoopBoards.theirSpellOn(game, opp, MURDER, kinnan);
        assertTrue(LoopResponse.volleyActions(ai, line(KINNAN_BASALT)) > LoopRounds.TURN_CAP, "a volley past the cap");
        assertNull(driver.responseAction(), "no response");
        opp.setLife(20, null);
        assertSame(driver.responseAction(), manaOf(basalt), "the control: the opponent at 20");
    }

    // Murder on Ballista itself: the volley happens first, every step above Murder, and the opponent loses before
    // Murder resolves.
    @Test
    public void respondsToRemovalOfBallista() {
        start();
        final Card kinnan = rock();
        final Card ballista = ballista(1);
        theirTurn();
        final Card murder = ComboLoopBoards.theirSpellOn(game, opp, MURDER, ballista);
        final List<Item> items = play(murder);
        opponentLostByLife();
        assertEquals(count(items, Item::pump), 19, "the pumps: " + items);
        assertEquals(count(items, Item::ping), 20, "the pings: " + items);
        for (final Item i : items) {
            assertTrue(i.above(), "an item of ours not above Murder: " + i);
        }
        assertSame(onOurBattlefield(KINNAN), kinnan);
    }

    // No Ballista on our battlefield: Murder on Kinnan draws no response, nor with a Ballista in our hand, which cannot
    // be cast above Murder, nor once our Leyline of Anticipation would let it be (section 6.6: Ballista on the
    // battlefield). The control: with one on our battlefield the response is Basalt's mana ability.
    @Test
    public void noResponseWithoutBallista() {
        start();
        final Card kinnan = rock();
        theirTurn();
        ComboLoopBoards.theirSpellOn(game, opp, MURDER, kinnan);
        assertTrue(LoopCycles.ready(ai, line(KINNAN_BASALT)), "the line can run");
        assertNull(driver.responseAction(), "no Ballista");
        addCardToZone(BALLISTA, ai, ZoneType.Hand);
        assertNull(driver.responseAction(), "a Ballista in our hand");
        addCard("Leyline of Anticipation", ai);
        assertTrue(LoopSinks.ballistaCanShoot(ai), "with flash, the Ballista in our hand could be cast");
        assertNull(driver.responseAction(), "a Ballista in our hand, though it has flash");
        ballista(1);
        assertSame(driver.responseAction(), manaOf(basalt), "the control: a Ballista on our battlefield");
    }

    // R5: Palinchron, Nyxbloom Ancient, seven lands (four Islands, three Forests) and Ballista, no Basalt Monolith, so
    // no rock line runs: the return and recast (3430-3542) can run, is not denied, Ballista can shoot and Murder on
    // Nyxbloom threatens it, but the recast needs a sorcery-speed cast: no response. With Basalt beside Nyxbloom the
    // rock untap 3430-4131 runs at instant speed, yet Murder on Palinchron, a piece of the recast alone, still draws
    // none; the control: Murder on Nyxbloom, a piece of the rock line too, draws the response, and the response stays
    // on the rock line though the recast nets more (10 a cycle to the rock's 6): Basalt's tap, then its untap, never
    // the pump of Ballista that the recast's boundary (our Palinchron on our battlefield, the lands its untap puts back
    // untapped) would take with the tap's 9 floating.
    @Test
    public void noResponseForASorcerySpeedKind() {
        start();
        final Card nyxbloom = ComboLoopBoards.ready(addCard(NYXBLOOM, ai));
        final Card palinchron = ComboLoopBoards.ready(addCard(PALINCHRON, ai));
        lands(ISLAND, 4);
        lands(FOREST, 3);
        final Card ballista = ballista(1);
        theirTurn();
        ComboLoopBoards.theirSpellOn(game, opp, MURDER, nyxbloom);
        assertTrue(LoopCycles.ready(ai, line(RECAST)), "the recast can run");
        assertTrue(t.executable().contains(line(RECAST)), "the recast is executable");
        assertTrue(LoopSinks.ballistaCanShoot(ai), "Ballista can shoot");
        assertTrue(LoopResponse.threatens(ai, game.getStack().peek(), List.of(nyxbloom, ballista)), "Murder threatens");
        assertNull(driver.responseAction(), "the recast is sorcery speed");
        game.getStack().clear();
        basalt = addCard(BASALT, ai);
        ComboLoopBoards.theirSpellOn(game, opp, MURDER, palinchron);
        assertNull(driver.responseAction(), "Murder on a piece of the recast alone");
        game.getStack().clear();
        final Card murder = ComboLoopBoards.theirSpellOn(game, opp, MURDER, nyxbloom);
        assertEquals(LoopCycles.net(ai, line(RECAST)), 10, "the recast's net a cycle");
        assertEquals(LoopCycles.net(ai, line(BASALT_NYXBLOOM)), 6, "the rock's net a cycle");
        assertEquals(respond(murder, 2), List.of(manaOf(basalt), untapOf(basalt)),
                "the control: Murder on Nyxbloom, beside Basalt, draws the rock line's tap and untap");
    }

    // Review Focus 1: our turn, a go-off running, three untapped lands of ours (R16: the response's first step is a
    // second untap, Basalt's first one beneath Murder). With Ballista: the opponent's Murder on Kinnan, put above our
    // Basalt untap, draws the response, and the opponent loses before Murder resolves. With no Ballista (the go-off
    // runs for Kinnan's dig): Murder resolves, Kinnan is gone, and the driver takes no step after it, neither a tap nor
    // an untap of Basalt, to the end of our Main 1; nothing throws.
    @Test
    public void removalOfKinnanMidLoopStopsCleanly() {
        start();
        final Card kinnan = rock();
        lands(FOREST, 1);
        lands(ISLAND, 2);
        ballista(1);
        ourUntapOnTheStack();
        final Card murder = ComboLoopBoards.theirResponse(game, opp, MURDER, kinnan);
        final List<Item> items = play(murder);
        opponentLostByLife();
        assertSame(onOurBattlefield(KINNAN), kinnan, "Murder resolved");
        assertEquals(count(items, Item::ping), 20, "the pings: " + items);
        for (final Item i : items) {
            assertTrue(i.above(), "an item of ours not above Murder: " + i);
        }
        assertSame(game.getPhaseHandler().getPlayerTurn(), ai, "the game ended on our turn");

        start();
        final Card kinnan2 = rock();
        lands(FOREST, 1);
        lands(ISLAND, 2);
        ourUntapOnTheStack();
        final Card murder2 = ComboLoopBoards.theirResponse(game, opp, MURDER, kinnan2);
        ComboLoopBoards.stepUntil(game, g -> !onTheStack(murder2), MAX_STEPS);
        assertFalse(game.isGameOver(), "the game ended");
        assertFalse(stillThere(kinnan2), "Murder did not resolve");
        assertNull(onOurBattlefield(KINNAN), "Kinnan on our battlefield");
        final int taps = manaOf(basalt).getActivationsThisTurn();
        final int untaps = untapOf(basalt).getActivationsThisTurn();
        ComboLoopBoards.stepUntil(game, g -> !g.getPhaseHandler().is(PhaseType.MAIN1, ai), MAX_STEPS);
        assertFalse(game.getPhaseHandler().is(PhaseType.MAIN1, ai), "our Main 1 went on");
        assertEquals(manaOf(basalt).getActivationsThisTurn(), taps, "a tap of Basalt after Murder resolved");
        assertEquals(untapOf(basalt).getActivationsThisTurn(), untaps, "an untap of Basalt after Murder resolved");
    }

    // ---- beyond the plan's tests ----

    // R28's sum, exact from a boundary: Ballista with one counter, the opponent at 3, Basalt untapped and the pool
    // empty make a volley of 21 actions (eight cycles of two make the two pumps' 8, then 3 pings). Under a turn's cap
    // of 20, or a game's cap of 20, no response comes; under a turn's cap of 21 the volley is played in exactly 21
    // actions, and the opponent loses before Murder resolves. Mid-cycle the sum errs high (after the first tap it
    // reads 23 for the 20 left: the pool and the cycle in progress are not counted toward the mana), so it is read at
    // the start alone. The driver's decisions draw nothing.
    @Test
    public void theVolleyMustFitInWhatTheCapsHaveLeft() {
        start();
        final Card kinnan = rock();
        ballista(1);
        opp.setLife(3, null);
        theirTurn();
        final Card murder = ComboLoopBoards.theirSpellOn(game, opp, MURDER, kinnan);
        assertEquals(LoopResponse.volleyActions(ai, line(KINNAN_BASALT)), 21L, "the volley's actions");
        driver.useRounds(new LoopRounds(20, 100));
        assertNull(driver.responseAction(), "21 actions under a turn's cap of 20");
        driver.useRounds(new LoopRounds(100, 20));
        assertNull(driver.responseAction(), "21 actions under a game's cap of 20");
        driver.useRounds(new LoopRounds(21, 100));
        assertEquals(respond(murder, 1), List.of(manaOf(basalt)), "the first tap");
        assertEquals(LoopResponse.volleyActions(ai, line(KINNAN_BASALT)), 23L, "mid-cycle, the sum errs high");
        final List<SpellAbility> acts = respond(murder, MAX_ACTIONS);
        assertEquals(acts.size(), 20, "the volley's other actions: " + acts);
        opponentLostByLife();
        assertSame(onOurBattlefield(KINNAN), kinnan, "Murder resolved");
        assertEquals(decisionDraws, 0, "the driver's decisions drew");
    }

    // R28's "what the caps have left" counts the actions the driver has already returned: the same volley of 21
    // actions draws no response from a turn's cap of 30 with 10 of its actions spent this turn, nor from a game's cap
    // of 30 with 10 spent, 20 left either way. With 9 of a turn's cap of 30 spent, 21 left, the response starts with
    // Basalt's mana ability, and the volley is played to the kill, its last ping the 30th action counted this turn.
    @Test
    public void whatTheCapsHaveLeftCountsTheActionsAlreadySpent() {
        start();
        final Card kinnan = rock();
        ballista(1);
        opp.setLife(3, null);
        theirTurn();
        final Card murder = ComboLoopBoards.theirSpellOn(game, opp, MURDER, kinnan);
        assertEquals(LoopResponse.volleyActions(ai, line(KINNAN_BASALT)), 21L, "the volley's actions");
        driver.useRounds(afterActions(new LoopRounds(30, 100), 10));
        assertNull(driver.responseAction(), "21 actions, 20 of a turn's cap of 30 left");
        driver.useRounds(afterActions(new LoopRounds(100, 30), 10));
        assertNull(driver.responseAction(), "21 actions, 20 of a game's cap of 30 left");
        driver.useRounds(afterActions(new LoopRounds(30, 100), 9));
        assertEquals(respond(murder, 1), List.of(manaOf(basalt)), "21 actions, 21 left: the first tap");
        final List<SpellAbility> acts = respond(murder, MAX_ACTIONS);
        assertEquals(acts.size(), 20, "the volley's other actions: " + acts);
        opponentLostByLife();
    }

    // R25's judge waits for our ping to leave the stack: Ballista with three counters, the opponent at 3, Murder on
    // Kinnan. The driver's first response is a ping (the counters cover the kill); the opponent answers it with
    // Lightning Bolt on Ballista, so the next decision comes with our ping still on the stack, beneath Bolt: the
    // driver acts again, not reading the unresolved ping as one that changed nothing, the volley goes on above Bolt,
    // and the opponent loses before Bolt resolves.
    @Test
    public void anItemAboveOurPingLeavesTheVolleyOn() {
        start();
        final Card kinnan = rock();
        final Card ballista = ballista(3);
        opp.setLife(3, null);
        theirTurn();
        ComboLoopBoards.theirSpellOn(game, opp, MURDER, kinnan);
        final SpellAbility first = driver.responseAction();
        assertNotNull(first, "a response");
        assertEquals(first.getApi(), ApiType.DealDamage, "a ping first");
        ai.getController().playChosenSpellAbility(first);
        final Card bolt = ComboLoopBoards.theirResponse(game, opp, "Lightning Bolt", ballista);
        assertEquals(game.getStack().size(), 3, "Bolt above our ping above Murder");
        final List<SpellAbility> rest = respond(bolt, MAX_ACTIONS);
        assertFalse(rest.isEmpty(), "the volley ended with our ping on the stack");
        opponentLostByLife();
        assertEquals(decisionDraws, 0, "the driver's decisions drew");
    }

    // Going on, Ballista must still be able to shoot: the response to Murder on Kinnan starts (Basalt's first tap),
    // then the opponent's Leyline of Sanctity gives them hexproof, so no kill is in reach any more, though Ballista
    // still has a step to take (a pump toward five times lethal): the driver goes off no further.
    @Test
    public void aShotOutOfReachMidResponseEndsIt() {
        start();
        final Card kinnan = rock();
        ballista(1);
        theirTurn();
        final Card murder = ComboLoopBoards.theirSpellOn(game, opp, MURDER, kinnan);
        assertEquals(respond(murder, 1), List.of(manaOf(basalt)), "the response starts");
        addCard("Leyline of Sanctity", opp);
        game.getAction().checkStaticAbilities();
        assertFalse(LoopSinks.ballistaCanShoot(ai), "a hexproof opponent");
        assertTrue(LoopSinks.ballistaPool(ai) >= 0, "Ballista still has a step");
        assertNull(driver.responseAction(), "the response went on");
    }

    // R25 in response: Ballista with five counters, the opponent at 3, Murder on Kinnan. The first ping lands (3 to
    // 2); then the opponent's Energy Field prevents the damage sources they do not control would deal them, the next
    // ping changes nothing, and the volley is over for the phase: the driver goes off no further, though Ballista can
    // still shoot and its counters, three, would cover the 2 left with a free ping.
    @Test
    public void aPingThatChangesNothingEndsTheResponse() {
        start();
        final Card kinnan = rock();
        ballista(5);
        opp.setLife(3, null);
        theirTurn();
        final Card murder = ComboLoopBoards.theirSpellOn(game, opp, MURDER, kinnan);
        final List<SpellAbility> first = respond(murder, 1);
        assertEquals(first.size(), 1, "the response starts");
        assertEquals(first.get(0).getApi(), ApiType.DealDamage, "a ping first");
        assertEquals(opp.getLife(), 2, "it landed");
        addCard("Energy Field", opp);
        final List<SpellAbility> rest = respond(murder, MAX_ACTIONS);
        assertEquals(rest.size(), 1, "one more ping, then nothing: " + rest);
        assertEquals(rest.get(0).getApi(), ApiType.DealDamage, "a ping");
        assertEquals(opp.getLife(), 2, "the second ping was prevented");
        assertTrue(LoopSinks.ballistaCanShoot(ai), "Ballista can still shoot");
        assertNull(driver.responseAction(), "the volley is over for the phase");
        assertEquals(decisionDraws, 0, "the driver's decisions drew");
    }

    // Our own item on top is the stock own-top pass's (AiController passes there before its stack-response block):
    // Ballista with three counters, the opponent at 3, Murder on Kinnan. The response's first ping on the stack above
    // Murder, the driver answers nothing above it; once the ping has resolved, Murder on top again, the volley goes on.
    @Test
    public void ourOwnItemOnTopDrawsNoResponse() {
        start();
        final Card kinnan = rock();
        ballista(3);
        opp.setLife(3, null);
        theirTurn();
        final Card murder = ComboLoopBoards.theirSpellOn(game, opp, MURDER, kinnan);
        final SpellAbility ping = driver.responseAction();
        assertNotNull(ping, "a response");
        assertEquals(ping.getApi(), ApiType.DealDamage, "a ping first");
        ai.getController().playChosenSpellAbility(ping);
        assertSame(game.getStack().peek().getActivatingPlayer(), ai, "our ping on top");
        assertNull(driver.responseAction(), "an action above our own ping");
        resolveDownTo(murder);
        assertEquals(opp.getLife(), 2, "the ping resolved");
        assertNotNull(driver.responseAction(), "the volley went no further");
    }

    // An empty stack holds nothing to answer (AiController asks the driver only above a stack, and MagicStack.peek is
    // null on an empty one): with the rock line and Ballista ready on the opponent's turn and nothing on the stack, the
    // driver answers nothing and throws nothing. The control: Murder on Kinnan draws Basalt's mana ability.
    @Test
    public void noResponseOnAnEmptyStack() {
        start();
        final Card kinnan = rock();
        ballista(1);
        theirTurn();
        assertTrue(game.getStack().isEmpty(), "the stack holds an item");
        assertNull(driver.responseAction(), "an empty stack");
        ComboLoopBoards.theirSpellOn(game, opp, MURDER, kinnan);
        assertSame(driver.responseAction(), manaOf(basalt), "the control: Murder on Kinnan");
    }

    // The driver checks that the game is still on before it returns an action (section 7: a game that times out is
    // marked over while its engine may still be deciding): Murder on Kinnan, Ballista ready, draws Basalt's mana
    // ability while the game is on, and nothing once it is marked over. Over is the game's age alone, which the
    // timeout's Game.setGameOver sets before it gives every player an outcome (that takes them out of the game, and
    // no kill would read as in reach), so the null is the driver's own check.
    @Test
    public void noResponseOnceTheGameIsOver() {
        for (final boolean over : new boolean[] {false, true}) {
            start();
            final Card kinnan = rock();
            ballista(1);
            theirTurn();
            ComboLoopBoards.theirSpellOn(game, opp, MURDER, kinnan);
            if (over) {
                game.setAge(GameStage.GameOver);
                assertNull(driver.responseAction(), "the game is over");
            } else {
                assertSame(driver.responseAction(), manaOf(basalt), "the game is on");
            }
        }
    }

    // R21 holds in response: the opponent's Suppression Field ({2} more for each activated ability that is no mana
    // ability) makes Basalt's untap cost 5, so a cycle loses mana. With two Islands of ours to pay the first untap, the
    // response to Murder on Kinnan (its count, 347 actions, read at the printed net and the priced pump and ping)
    // takes the tap and the untap, and the cycle that ended at the next boundary is measured short: the line is failed
    // for the phase, and the driver acts no more.
    @Test
    public void aCycleThatLosesManaFailsItsLineInResponse() {
        start();
        final Card kinnan = rock();
        ballista(1);
        lands(ISLAND, 2);
        addCard("Suppression Field", opp);
        theirTurn();
        final Card murder = ComboLoopBoards.theirSpellOn(game, opp, MURDER, kinnan);
        assertEquals(LoopResponse.volleyActions(ai, line(KINNAN_BASALT)), 347L, "the volley's actions");
        final List<SpellAbility> acts = respond(murder, MAX_ACTIONS);
        assertEquals(acts, List.of(manaOf(basalt), untapOf(basalt)), "the tap and the untap");
        assertTrue(basalt.isUntapped(), "the untap resolved");
        assertNull(driver.responseAction(), "the line is failed for the phase");
    }

    // R21's judge of the action returned last holds in response, as in a main phase: Basalt tapped, three untapped
    // Forests of ours to pay its untap, Ballista with one counter and Murder on Kinnan. The response's first step is
    // Basalt's untap; asked again with that untap never played, nothing changed among the pool, our hand, our
    // battlefield and the stack, so the rock line is failed for the phase and the driver returns nothing, never the
    // same step again. The control, on the same board: with the untap played, the next step is Basalt's tap.
    @Test
    public void aStepThatChangesNothingFailsItsLineInResponse() {
        start();
        final Card kinnan = rock();
        lands(FOREST, 3);
        ballista(1);
        basalt.setTapped(true);
        theirTurn();
        ComboLoopBoards.theirSpellOn(game, opp, MURDER, kinnan);
        assertSame(driver.responseAction(), untapOf(basalt), "the first step: the untap");
        assertNull(driver.responseAction(), "the same untap again, nothing changed: its line is failed");

        start();
        final Card kinnan2 = rock();
        lands(FOREST, 3);
        ballista(1);
        basalt.setTapped(true);
        theirTurn();
        final Card murder = ComboLoopBoards.theirSpellOn(game, opp, MURDER, kinnan2);
        assertEquals(respond(murder, 2), List.of(untapOf(basalt), manaOf(basalt)), "the control: the untap played");
    }

    // A win now beats countering the removal: Counterspell in our hand and two untapped Islands. With no loop line
    // the AI answers Murder on Kinnan with Counterspell; with the shipped lines its choice is the go-off's first step,
    // Basalt's mana ability, ahead of the counterspell pick.
    @Test
    public void goingOffComesBeforeTheCounterspell() {
        start();
        final Card kinnan = rock();
        ballista(1);
        addCardToZone(COUNTERSPELL, ai, ZoneType.Hand);
        lands(ISLAND, 2);
        theirTurn();
        ComboLoopBoards.theirSpellOn(game, opp, MURDER, kinnan);
        final AiController brains = ComboLoopBoards.aiOf(ai);
        t.useLines(List.of());
        final List<SpellAbility> stock = brains.chooseSpellAbilityToPlay();
        assertNotNull(stock, "the stock AI passes");
        assertEquals(ComboTracker.nameOf(stock.get(0).getHostCard()), COUNTERSPELL, "the stock answer");
        t.useLines(ComboLines.shipped());
        final List<SpellAbility> ours = brains.chooseSpellAbilityToPlay();
        assertNotNull(ours, "the AI passes");
        assertSame(ours.get(0), manaOf(basalt), "the go-off's first step");
    }

    // A deck holding no live loop line answers removal as stock: the same board, its tracker holding a loop line the
    // board cannot run (the flicker's), gives the stock answer to Murder on Kinnan with the same random draws.
    @Test
    public void aDeckWithNoLiveLoopAnswersRemovalAsStock() {
        start();
        final Card kinnan = rock();
        ballista(1);
        addCardToZone(COUNTERSPELL, ai, ZoneType.Hand);
        lands(ISLAND, 2);
        theirTurn();
        ComboLoopBoards.theirSpellOn(game, opp, MURDER, kinnan);
        final AiController brains = ComboLoopBoards.aiOf(ai);
        t.useLines(List.of());
        brains.chooseSpellAbilityToPlay(); // warm: a stock pick's first call can draw what later calls do not
        ComboLoopBoards.assertStockParity(brains::chooseSpellAbilityToPlay, ai,
                ComboLoopBoards.shippedLoopLines(FLICKER));
        assertFalse(driver.liftsThrottle(), "the driver acted");
    }

    // Only a loop piece or Ballista is the response's to save: Murder on our Runeclaw Bear, beside a rock line that can
    // run and Ballista, draws no response. The control: Murder on Kinnan does.
    @Test
    public void noResponseToRemovalOfACardThatIsNoPiece() {
        start();
        final Card kinnan = rock();
        ballista(1);
        final Card bear = ComboLoopBoards.ready(addCard(BEAR, ai));
        theirTurn();
        ComboLoopBoards.theirSpellOn(game, opp, MURDER, bear);
        assertNull(driver.responseAction(), "Murder on a Runeclaw Bear");
        game.getStack().clear();
        ComboLoopBoards.theirSpellOn(game, opp, MURDER, kinnan);
        assertSame(driver.responseAction(), manaOf(basalt), "the control: Murder on Kinnan");
    }

    // A piece of any instant-speed line that can run is the response's to save, not only of the line it runs: Kinnan
    // and Basalt (129-4131, 1 a cycle) beside Palinchron and Deadeye Navigator paired with seven Islands (the flicker,
    // 1409-3542, 5 a cycle), and Murder on Kinnan, a piece of the rock line alone. The response runs the best line, the
    // flicker, which needs no Kinnan: its first step is the first Island's mana ability.
    @Test
    public void aPieceOfAnyLiveLineIsSaved() {
        start();
        final Card kinnan = rock();
        paired();
        final List<Card> islands = lands(ISLAND, 7);
        ballista(1);
        theirTurn();
        assertEquals(LoopCycles.net(ai, line(FLICKER)), 5);
        assertEquals(LoopCycles.net(ai, line(KINNAN_BASALT)), 1);
        assertFalse(line(FLICKER).names().contains(KINNAN));
        ComboLoopBoards.theirSpellOn(game, opp, MURDER, kinnan);
        assertSame(driver.responseAction(), manaOf(islands.get(0)), "the flicker goes off");
    }

    // Removal aimed at the Monolith, the case section 6.6 put to Evan beside Kinnan's: the opponent's Shatter on Basalt
    // Monolith, the rock line's piece that is no creature, on their turn. The volley happens first, every step above
    // Shatter, Ballista pumped to 20 counters and shooting 20 times, and the opponent loses before Shatter resolves:
    // Basalt never leaves our battlefield.
    @Test
    public void respondsToRemovalOfTheMonolith() {
        start();
        rock();
        ballista(1);
        theirTurn();
        final Card shatter = ComboLoopBoards.theirSpellOn(game, opp, "Shatter", basalt);
        final List<Item> items = play(shatter);
        opponentLostByLife();
        assertSame(onOurBattlefield(BASALT), basalt, "Shatter resolved: Basalt left our battlefield");
        assertEquals(count(items, Item::pump), 19, "the pumps: " + items);
        assertEquals(count(items, Item::ping), 20, "the pings: " + items);
        for (final Item i : items) {
            assertTrue(i.above(), "an item of ours not above Shatter: " + i);
        }
    }

    // The flicker line's other piece is saved as Palinchron is: Palinchron and Deadeye Navigator paired, seven Islands,
    // Ballista with one counter, and the opponent's Murder on Deadeye Navigator draws the response, the flicker's first
    // step, the first Island's mana ability.
    @Test
    public void respondsToRemovalOfDeadeyeNavigator() {
        start();
        paired();
        final List<Card> islands = lands(ISLAND, 7);
        ballista(1);
        theirTurn();
        ComboLoopBoards.theirSpellOn(game, opp, MURDER, onOurBattlefield(DEADEYE));
        assertSame(driver.responseAction(), manaOf(islands.get(0)), "Murder on Deadeye Navigator");
    }

    // Section 6.6 reads "a spell or ability on the stack": an opponent's ability draws the response as a spell does. An
    // activated one, Qasali Pridemage's destroy ({1}, sacrifice it: destroy target artifact or enchantment) aimed at
    // Basalt Monolith and put on the stack by hand (nothing paid), draws Basalt's mana ability; so does a triggered
    // one, Flametongue Kavu's 4 damage aimed at Kinnan, wrapped as the trigger handler wraps a mandatory trigger.
    @Test
    public void goesOffInResponseToAnAbility() {
        start();
        rock();
        ballista(1);
        theirTurn();
        final Card pridemage = addCard("Qasali Pridemage", opp);
        SpellAbility destroy = null;
        for (final SpellAbility sa : pridemage.getNonManaAbilities()) {
            if (sa.isActivatedAbility() && sa.getApi() == ApiType.Destroy) {
                destroy = sa;
            }
        }
        assertNotNull(destroy, "Pridemage's ability");
        destroy.setActivatingPlayer(opp);
        destroy.getTargets().add(basalt);
        game.getStack().add(destroy);
        assertFalse(game.getStack().peek().getSpellAbility().isSpell(), "an ability");
        assertSame(driver.responseAction(), manaOf(basalt), "an activated ability on Basalt");

        start();
        final Card kinnan = rock();
        ballista(1);
        theirTurn();
        final Card ftk = addCard("Flametongue Kavu", opp);
        final Trigger trigger = ftk.getTriggers().get(0);
        final SpellAbility inner = AbilityFactory.getAbility(ftk, trigger.getParam("Execute"));
        inner.setActivatingPlayer(opp);
        inner.setTrigger(trigger);
        inner.getTargets().add(kinnan);
        game.getStack().add(new WrappedAbility(trigger, inner, null));
        assertTrue(game.getStack().peek().getSpellAbility().isWrapper(), "a triggered ability");
        assertSame(driver.responseAction(), manaOf(basalt), "a trigger on Kinnan");
    }

    // The "all" forms and a removal on a part after the first: Wrath of God (destroy all creatures), Pyroclasm (2
    // damage to each creature, lethal to Kinnan's 2/2), Evacuation (every creature to its owner's hand), Infest (-2/-2
    // to all), Darkness Descends (two -1/-1 counters on each creature) and All Is Dust (each player sacrifices its
    // coloured permanents) threaten Kinnan, each read for Kinnan alone, and Severed Strands's destroy, its second part,
    // aimed at Kinnan does too. Each reach is read: Pyroclasm, Infest and Darkness Descends spare a Hullbreaker Horror
    // (7/8), All Is Dust a colourless Basalt Monolith, Arms of Hadar (-2/-2 to the creatures of the player it targets)
    // our creatures when it targets its caster, and Calamity's Wake (every graveyard exiled) our battlefield.
    @Test
    public void threatensReadsTheAllFormsAndEveryPart() {
        start();
        final Card kinnan = rock();
        final Card ballista = ballista(1);
        final Card hullbreaker = addCard(HULLBREAKER, ai);
        final List<Card> pieces = List.of(kinnan, basalt, ballista);
        theirTurn();
        for (final String all : List.of("Wrath of God", "Pyroclasm", "Evacuation", "Infest", "Darkness Descends",
                "All Is Dust")) {
            assertTrue(threatens(all, List.of(kinnan)), all);
        }
        assertTrue(threatens("Severed Strands", pieces, kinnan), "Severed Strands's destroy");
        for (final String sparing : List.of("Pyroclasm", "Infest", "Darkness Descends")) {
            assertFalse(threatens(sparing, List.of(hullbreaker)), sparing + " on a 7/8");
        }
        assertFalse(threatens("All Is Dust", List.of(basalt)), "All Is Dust on a colourless rock");
        assertTrue(threatens("Arms of Hadar", pieces, ai), "Arms of Hadar aimed at us");
        assertFalse(threatens("Arms of Hadar", pieces, opp), "Arms of Hadar aimed at its caster");
        assertFalse(threatens("Calamity's Wake", pieces), "Calamity's Wake exiles graveyards");
    }

    // A piece that is no creature is read as a creature is: Shatter (destroy) and Return to Dust (exile) aimed at
    // Basalt Monolith, and Shatterstorm (destroy all artifacts), threaten it, each read for Basalt alone (Walking
    // Ballista, an artifact creature, would be Shatterstorm's in its own right), while Shatterstorm spares Kinnan, no
    // artifact. Each reading draws nothing.
    @Test
    public void threatensReadsAPieceThatIsNoCreature() {
        start();
        final Card kinnan = rock();
        ballista(1);
        theirTurn();
        assertTrue(threatens("Shatter", List.of(basalt), basalt), "Shatter (destroy) on Basalt");
        assertTrue(threatens("Return to Dust", List.of(basalt), basalt), "Return to Dust (exile) on Basalt");
        assertTrue(threatens("Shatterstorm", List.of(basalt)), "Shatterstorm (destroy all artifacts) on Basalt");
        assertFalse(threatens("Shatterstorm", List.of(kinnan)), "Shatterstorm on Kinnan, no artifact");
    }

    // The target as the engine reads it at resolution, and whose item it is: Lightning Bolt on Hullbreaker Horror (3
    // damage to a 7/8) and our own Murder on our Kinnan threaten nothing; Scar's one -1/-1 counter kills a Ballista
    // with one +1/+1 counter; Control Magic, and Switcheroo trading Kinnan for their Runeclaw Bear, take Kinnan. Murder
    // on Kinnan threatens nothing once Kinnan has left our battlefield and come back (another object: the item's target
    // is gone, as MagicStack.hasFizzled reads it), while a Murder on the Kinnan that came back does; nor once
    // Asceticism gives our creatures hexproof, so that Murder can target Kinnan no more.
    @Test
    public void threatensReadsTheTargetAsItIsNow() {
        start();
        final Card kinnan = rock();
        final Card ballista = ballista(1);
        final Card hullbreaker = addCard(HULLBREAKER, ai);
        final Card theirs = addCard(BEAR, opp);
        final List<Card> pieces = List.of(kinnan, basalt, ballista);
        theirTurn();
        assertFalse(threatens("Lightning Bolt", List.of(hullbreaker), hullbreaker), "3 damage to a 7/8");
        assertTrue(threatens("Scar", pieces, ballista), "one -1/-1 counter on a 1/1");
        assertTrue(threatens("Control Magic", pieces, kinnan), "Control Magic");
        assertTrue(threatens("Switcheroo", pieces, kinnan, theirs), "Switcheroo");
        ComboLoopBoards.theirSpellOn(game, ai, MURDER, kinnan);
        assertFalse(LoopResponse.threatens(ai, game.getStack().peek(), pieces), "our own Murder");
        game.getStack().clear();

        ComboLoopBoards.theirSpellOn(game, opp, MURDER, kinnan);
        final Card back = game.getAction().moveToPlay(game.getAction().exile(kinnan, null, null), null, null);
        final List<Card> now = List.of(back, basalt, ballista);
        assertFalse(LoopResponse.threatens(ai, game.getStack().peek(), now), "Murder on the Kinnan that left");
        game.getStack().clear();
        assertTrue(threatens(MURDER, now, back), "the control: Murder on the Kinnan that came back");
        addCard("Asceticism", ai);
        game.getAction().checkStaticAbilities();
        assertFalse(threatens(MURDER, now, back), "Murder on a hexproof Kinnan");
    }

    // Each part is read for what it would do, its condition not read: Dark Temper's second part destroys the creature
    // its first part deals 2 damage to (Defined$ Targeted), so a 5/5 Ballista that the damage spares is threatened,
    // where Lightning Bolt's 3 is not. Indestructibility is read where the engine reads it: with Avacyn, Angel of Hope
    // on ours (our other permanents indestructible), Murder, Wrath of God and Lightning Bolt threaten Kinnan no more,
    // while Swords to Plowshares (exile) and Dismember (-5/-5: no toughness left kills an indestructible creature)
    // still do.
    @Test
    public void threatensReadsWhatEachPartWouldDo() {
        start();
        final Card kinnan = rock();
        final Card ballista = ballista(5);
        final List<Card> pieces = List.of(kinnan, basalt, ballista);
        theirTurn();
        assertTrue(threatens("Dark Temper", List.of(ballista), ballista), "Dark Temper's destroy");
        assertFalse(threatens("Lightning Bolt", List.of(ballista), ballista), "the control: 3 damage to a 5/5");
        addCard("Avacyn, Angel of Hope", ai);
        game.getAction().checkStaticAbilities();
        assertFalse(threatens(MURDER, pieces, kinnan), "Murder on an indestructible Kinnan");
        assertFalse(threatens("Wrath of God", pieces), "Wrath of God");
        assertFalse(threatens("Lightning Bolt", pieces, kinnan), "Lightning Bolt");
        assertTrue(threatens("Swords to Plowshares", pieces, kinnan), "Swords exiles");
        assertTrue(threatens("Dismember", pieces, kinnan), "Dismember's -5/-5");
    }

    // A part whose amount the engine draws at random (a Count$Random, as the Alchemy cards write theirs) is not read,
    // so the reading draws nothing: Lightning Bolt on Kinnan, its damage made Count$Random.2.4 on the stack by hand,
    // threatens nothing, and its reading makes no random draw. The control: Bolt as printed threatens Kinnan.
    @Test
    public void aRandomAmountIsNotRead() {
        start();
        final Card kinnan = rock();
        ballista(1);
        theirTurn();
        ComboLoopBoards.theirSpellOn(game, opp, "Lightning Bolt", kinnan);
        game.getStack().peek().getSpellAbility().putParam("NumDmg", "Count$Random.2.4");
        final long before = MyRandom.callCount();
        assertFalse(LoopResponse.threatens(ai, game.getStack().peek(), List.of(kinnan)), "a random amount read");
        assertEquals(MyRandom.callCount() - before, 0L, "the reading drew");
        game.getStack().clear();
        assertTrue(threatens("Lightning Bolt", List.of(kinnan), kinnan), "the control: Bolt as printed");
    }
}
