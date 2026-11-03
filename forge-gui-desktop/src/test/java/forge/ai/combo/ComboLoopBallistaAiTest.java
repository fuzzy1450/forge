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
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import org.testng.annotations.Test;

import forge.ai.AITest;
import forge.ai.LobbyPlayerAi;
import forge.ai.LoopTracker;
import forge.card.mana.ManaAtom;
import forge.game.Game;
import forge.game.GameLogEntry;
import forge.game.GameObject;
import forge.game.ability.ApiType;
import forge.game.card.Card;
import forge.game.card.CounterEnumType;
import forge.game.keyword.Keyword;
import forge.game.mana.Mana;
import forge.game.phase.PhaseType;
import forge.game.player.GameLossReason;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.spellability.SpellAbilityStackInstance;
import forge.game.zone.ZoneType;
import forge.util.MyRandom;

/**
 * Walking Ballista, the loop's first sink (spec 2026-10-29-combo-aware-ai-loops-design.md section 6.4 step 1; the
 * plan's Task B5, R4, R6 and R25): in our hand it is cast with X from the pool, the pool built to twice what a kill
 * needs; on our battlefield it is pumped with {4} to exact lethal; then it shoots, every loss blocker first, then each
 * opponent, one ping a decision, and the driver owns every decision while it builds. A hexproof player means a pump
 * (or a cast) to five times lethal and no ping; Pithing Needle on Ballista means no Ballista step; a loss blocker the
 * pings cannot kill denies the line; a ping that changes nothing ends the volley for the phase. Unless a test says
 * otherwise the board is Kinnan's list registered, the tracker reading the shipped lines, every seat on the Default
 * profile, the AI in its own Main 1 with no AI deadline, Kinnan (ready) and Basalt Monolith on our battlefield, and
 * the opponent at 20 with nothing. The plan's kills run through the whole game loop ({@link #play}), where the stock
 * AI decides too; the other boards play the driver's own decisions ({@link #drive}).
 */
public class ComboLoopBallistaAiTest extends AITest {
    private static final String KINNAN = "Kinnan, Bonder Prodigy";
    private static final String BASALT = "Basalt Monolith";
    private static final String BALLISTA = LoopSinks.BALLISTA;
    private static final String ANGEL = "Platinum Angel";
    private static final String DREADMAW = "Colossal Dreadmaw";
    private static final String HARSH_MENTOR = "Harsh Mentor";
    private static final String SPELLSHOCK = "Spellshock";
    /** The rock untap of Kinnan and Basalt Monolith. */
    private static final String KINNAN_BASALT = "129-4131";
    /** The game-loop steps a stepping test may take: a hundred cycles at two or three steps each, and slack. */
    private static final int MAX_STEPS = 2_000;
    /** The driver's actions a driving test may take. */
    private static final int MAX_ACTIONS = 1_000;
    /** Kinnan's list beside its commander: every piece of the 19 loop rows, the sinks and lands. */
    private static final List<String> KINNANS_LIST = kinnansList();

    private Game game;
    private Player ai;
    private Player opp;
    private ComboTracker t;
    private LoopDriver driver;
    private Card basalt;
    /** The random draws made inside the driver's decisions while {@link #drive} ran, since the board was set. */
    private long decisionDraws;

    /**
     * A Walking Ballista action of ours, or another action the driver returned: the ability, its host, its X and its
     * target as it was taken, its host's +1/+1 counters then, and our pool just before it.
     */
    private record Act(SpellAbility sa, Card host, Integer x, GameObject target, int counters, int poolBefore) {
        boolean ballista() {
            return BALLISTA.equals(ComboTracker.nameOf(host));
        }

        boolean cast() {
            return ballista() && sa.isSpell();
        }

        boolean pump() {
            return ballista() && !sa.isSpell() && sa.getApi() == ApiType.PutCounter;
        }

        boolean ping() {
            return ballista() && !sa.isSpell() && sa.getApi() == ApiType.DealDamage;
        }
    }

    private static List<String> kinnansList() {
        final Set<String> names = new LinkedHashSet<>();
        for (final ComboLines.Line l : ComboLoopBoards.shippedLoopLines(LoopRows.ids().toArray(new String[0]))) {
            names.addAll(l.names());
        }
        names.remove(KINNAN);
        names.addAll(List.of(BALLISTA, "Thrasios, Triton Hero", "Forest", "Island"));
        return List.copyOf(names);
    }

    /** The default board in a two-player game: the AI is player 1, the opponent player 0. */
    private void start() {
        board(initAndCreateGame());
    }

    /** The default board in a three-player game: the AI is player 1, its opponents players 0 and 2. */
    private void startPod() {
        board(initAndCreateThreePlayerGame());
    }

    private void board(final Game g) {
        game = g;
        ComboLoopBoards.noDeadline(game);
        ai = game.getPlayers().get(1);
        opp = game.getPlayers().get(0);
        for (final Player p : game.getPlayers()) {
            ((LobbyPlayerAi) p.getLobbyPlayer()).setAiProfile("Default");
        }
        ComboLoopBoards.deck(ai, KINNAN, KINNANS_LIST.toArray(new String[0]));
        t = ComboLoopBoards.aiOf(ai).getComboTracker();
        driver = ComboLoopBoards.aiOf(ai).getLoopDriver();
        decisionDraws = 0;
        ComboLoopBoards.ready(addCard(KINNAN, ai));
        basalt = addCard(BASALT, ai);
    }

    /** A Walking Ballista of ours on our battlefield, ready, with {@code counters} +1/+1 counters. */
    private Card ballistaOnOurs(final int counters) {
        final Card b = ComboLoopBoards.ready(addCard(BALLISTA, ai));
        b.setCounters(CounterEnumType.P1P1, counters);
        return b;
    }

    private Card ballistaInHand() {
        return addCardToZone(BALLISTA, ai, ZoneType.Hand);
    }

    /** Two Counterspells in our hand and two untapped Islands of ours: a stock target of 2 the stock AI never
     *  spends. */
    private void counterspells() {
        addCardToZone("Counterspell", ai, ZoneType.Hand);
        addCardToZone("Counterspell", ai, ZoneType.Hand);
        addCard("Island", ai);
        addCard("Island", ai);
    }

    private int pool() {
        return ComboLoopBoards.pool(ai);
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

    /** Our card on our battlefield by its printed name (ComboTracker.nameOf), or null. */
    private Card onOurBattlefield(final String name) {
        for (final Card c : ai.getCardsIn(ZoneType.Battlefield)) {
            if (name.equals(ComboTracker.nameOf(c))) {
                return c;
            }
        }
        return null;
    }

    private static GameObject firstTarget(final SpellAbility sa) {
        return sa.usesTargeting() && !sa.getTargets().isEmpty() ? sa.getTargets().get(0) : null;
    }

    private static List<Act> only(final List<Act> acts, final Predicate<Act> kind) {
        return acts.stream().filter(kind).collect(Collectors.toList());
    }

    /** The index of the first act of that kind, or -1. */
    private static int firstIndex(final List<Act> acts, final Predicate<Act> kind) {
        for (int i = 0; i < acts.size(); i++) {
            if (kind.test(acts.get(i))) {
                return i;
            }
        }
        return -1;
    }

    /** The index of the last act of that kind, or -1. */
    private static int lastIndex(final List<Act> acts, final Predicate<Act> kind) {
        for (int i = acts.size() - 1; i >= 0; i--) {
            if (kind.test(acts.get(i))) {
                return i;
            }
        }
        return -1;
    }

    /** Plays the driver's go-off actions, one decision at a time, each through the AI's own play path
     *  (PlayerControllerAi.playChosenSpellAbility) with the stack then resolved, until it returns null, the game is
     *  over or {@code max} actions; returns them in order, each as it was when returned (its host's counters before its
     *  cost). The state-based actions are checked before each decision, as the game loop checks them before every
     *  priority (a 0/0 Ballista dies, an opponent at 0 life loses). The random draws made inside the driver's decisions
     *  are added to {@link #decisionDraws}. */
    private List<Act> drive(final int max) {
        final List<Act> acts = new ArrayList<>();
        while (acts.size() < max) {
            game.getAction().checkStateEffects(true);
            final long before = MyRandom.callCount();
            final SpellAbility sa = driver.mainPhaseAction(List.of());
            decisionDraws += MyRandom.callCount() - before;
            if (sa == null) {
                break;
            }
            final Card host = sa.getHostCard();
            acts.add(new Act(sa, host, sa.getXManaCostPaid(), firstTarget(sa), host.getCounters(CounterEnumType.P1P1),
                    pool()));
            ai.getController().playChosenSpellAbility(sa);
            while (!game.getStack().isEmpty()) {
                game.getStack().resolveStack();
            }
        }
        return acts;
    }

    /** Steps the whole game loop (PhaseHandler.mainLoopStep: every seat decides, the stock AI included) until the game
     *  is over, at most {@link #MAX_STEPS} steps, calling {@code afterEachStep} with the Ballista actions so far after
     *  each step; returns our Walking Ballista items as the loop put them on the stack, in order, each as first seen
     *  there (a ping's host's counters after its cost) with our pool after the step before. */
    private List<Act> play(final Consumer<List<Act>> afterEachStep) {
        final List<Act> acts = new ArrayList<>();
        final Set<Integer> seen = new HashSet<>();
        int poolBefore = pool();
        for (int step = 0; step < MAX_STEPS && !game.isGameOver(); step++) {
            game.getPhaseHandler().mainLoopStep();
            for (final SpellAbilityStackInstance si : game.getStack()) {
                final Card host = si.getSourceCard();
                if (seen.add(si.getId()) && host != null && ai.equals(si.getActivatingPlayer())
                        && BALLISTA.equals(ComboTracker.nameOf(host))) {
                    final SpellAbility sa = si.getSpellAbility();
                    acts.add(new Act(sa, host, sa.getXManaCostPaid(), firstTarget(sa),
                            host.getCounters(CounterEnumType.P1P1), poolBefore));
                }
            }
            poolBefore = pool();
            afterEachStep.accept(acts);
        }
        return acts;
    }

    /** {@code n} colourless mana into our pool, as a rock's tap makes it. */
    private void floatColourless(final int n) {
        for (int i = 0; i < n; i++) {
            ai.getManaPool().addMana(new Mana((byte) ManaAtom.COLORLESS, basalt, null, ai));
        }
    }

    /** How many entries of the game log read exactly {@code message}. */
    private int logged(final String message) {
        int n = 0;
        for (final GameLogEntry e : game.getGameLog().getAllEntries()) {
            if (message.equals(e.message())) {
                n++;
            }
        }
        return n;
    }

    private void opponentLostByLife(final Player p) {
        assertNotNull(p.getOutcome(), p + " is still in the game");
        assertEquals(p.getOutcome().lossState, GameLossReason.LifeReachedZero, p + "'s loss");
    }

    // ---- the plan's tests ----

    // Ballista in our hand, the opponent at 20: the pool is built to 40, Ballista is cast with X = 20 and pings 20
    // times, every one at the opponent, who loses for having 0 life.
    @Test
    public void ballistaInHandIsCastAtExactLethalAndShoots() {
        start();
        ballistaInHand();
        assertEquals(LoopSinks.lethalNeeded(ai), 20);
        assertTrue(LoopSinks.ballistaCanShoot(ai));
        final List<Act> acts = play(a -> { });
        assertTrue(game.isGameOver(), "the game ended");
        opponentLostByLife(opp);
        assertEquals(acts.size(), 21, "one cast and twenty pings: " + acts);
        final Act cast = acts.get(0);
        assertTrue(cast.cast(), "the cast first: " + cast);
        assertEquals(cast.x(), Integer.valueOf(20), "X at exact lethal");
        assertEquals(cast.poolBefore(), 40, "the pool built to twice lethal");
        for (final Act a : acts.subList(1, acts.size())) {
            assertTrue(a.ping(), "a ping: " + a);
            assertEquals(a.target(), opp);
        }
    }

    // Ballista on our battlefield with 2 counters, the opponent at 10: 8 pumps, then 10 pings at the opponent.
    @Test
    public void ballistaOnTheBattlefieldPumpsThenShoots() {
        start();
        opp.setLife(10, null);
        final Card ballista = ballistaOnOurs(2);
        final List<Act> acts = play(a -> { });
        assertTrue(game.isGameOver(), "the game ended");
        opponentLostByLife(opp);
        assertEquals(acts.size(), 18, "8 pumps and 10 pings: " + acts);
        for (int i = 0; i < 8; i++) {
            assertTrue(acts.get(i).pump(), "a pump: " + acts.get(i));
            assertEquals(acts.get(i).host(), ballista);
        }
        for (int i = 8; i < 18; i++) {
            assertTrue(acts.get(i).ping(), "a ping: " + acts.get(i));
            assertEquals(acts.get(i).target(), opp);
        }
    }

    // The same run, step by step through the game loop, where the stock AI's own ping (DamageDealAi's Triskelion goes
    // face once the counters reach the opponent's life less 4) would fire if it were given a decision: the opponent's
    // life is 10 at every step before the first ping, which comes only once Ballista holds 10 counters.
    @Test
    public void noPingBeforeTheVolley() {
        start();
        opp.setLife(10, null);
        ballistaOnOurs(2);
        final List<Act> acts = play(a -> {
            if (a.stream().noneMatch(Act::ping)) {
                assertEquals(opp.getLife(), 10, "a step before the first ping");
            }
        });
        final int first = firstIndex(acts, Act::ping);
        assertTrue(first >= 0, "no ping: " + acts);
        assertEquals(acts.get(first).counters() + 1, 10, "the counters Ballista held when it first pinged");
        assertEquals(first, 8, "after the eight pumps");
        opponentLostByLife(opp);
    }

    // The opponent's Leyline of Sanctity (player hexproof), the opponent at 4, Ballista on ours with one counter: no
    // ping can kill, so Ballista is pumped until our board is five times lethal (Ballista's 18 and Kinnan's 2), no ping
    // is made, and the driver stops.
    @Test
    public void aHexproofPlayerMeansPumpToFiveTimes() {
        start();
        opp.setLife(4, null);
        addCard("Leyline of Sanctity", opp);
        final Card ballista = ballistaOnOurs(1);
        game.getAction().checkStateEffects(true);
        assertTrue(opp.hasKeyword(Keyword.HEXPROOF), "the opponent has hexproof");
        assertEquals(LoopSinks.lethalNeeded(ai), 0, "no opponent a ping can target");
        assertFalse(LoopSinks.ballistaCanShoot(ai));
        final List<Act> acts = drive(MAX_ACTIONS);
        assertTrue(acts.stream().noneMatch(Act::ping), "no ping: " + acts);
        assertEquals(only(acts, Act::pump).size(), 17, "pumped from 1 to 18");
        assertEquals(ballista.getCounters(CounterEnumType.P1P1), 18);
        final LoopTracker.Snapshot now = LoopTracker.Snapshot.of(ai);
        assertEquals(now.ownPower(), 20, "Ballista's 18 and Kinnan's 2");
        assertTrue(now.isDecisive(), "five times the opponent's 4");
        assertNull(driver.mainPhaseAction(List.of()), "the driver stops");
        assertEquals(opp.getLife(), 4);
        assertEquals(decisionDraws, 0, "the driver's decisions drew");
    }

    // The same with Ballista in our hand (R4): the pool is built to twice the counters that make the board five times
    // lethal, 36, and Ballista is cast with X = 18, never 0; no pump and no ping follow.
    @Test
    public void aHexproofPlayerWithBallistaInHand() {
        start();
        opp.setLife(4, null);
        addCard("Leyline of Sanctity", opp);
        ballistaInHand();
        final List<Act> acts = drive(MAX_ACTIONS);
        final List<Act> casts = only(acts, Act::cast);
        assertEquals(casts.size(), 1, "one cast: " + acts);
        assertEquals(casts.get(0).x(), Integer.valueOf(18), "X at what makes the board five times lethal");
        assertEquals(casts.get(0).poolBefore(), 36, "the pool built to twice that");
        assertTrue(acts.stream().noneMatch(a -> a.ping() || a.pump()), "no ping and no pump: " + acts);
        final Card ballista = onOurBattlefield(BALLISTA);
        assertNotNull(ballista, "Ballista entered");
        assertEquals(ballista.getCounters(CounterEnumType.P1P1), 18);
        assertEquals(LoopTracker.Snapshot.of(ai).ownPower(), 20);
        assertNull(driver.mainPhaseAction(List.of()), "the driver stops");
        assertEquals(opp.getLife(), 4);
        assertEquals(decisionDraws, 0, "the driver's decisions drew");
    }

    // The opponent's Platinum Angel (they cannot lose, we cannot win), the opponent at 10, Ballista in our hand:
    // Ballista enters with 14 counters, pings the Angel four times, then the opponent ten times; the game ends.
    @Test
    public void platinumAngelIsShotFirst() {
        start();
        opp.setLife(10, null);
        final Card angel = addCard(ANGEL, opp);
        ballistaInHand();
        assertSame(LoopSinks.lossBlocker(ai), angel);
        assertEquals(LoopSinks.lethalNeeded(ai), 14, "the opponent's 10 and the Angel's 4");
        final List<Act> acts = play(a -> { });
        assertTrue(game.isGameOver(), "the game ended");
        opponentLostByLife(opp);
        assertEquals(acts.size(), 15, "a cast, four pings at the Angel, ten at the opponent: " + acts);
        assertTrue(acts.get(0).cast());
        assertEquals(acts.get(0).x(), Integer.valueOf(14));
        assertEquals(acts.get(0).poolBefore(), 28);
        for (int i = 1; i < 5; i++) {
            assertTrue(acts.get(i).ping(), "a ping: " + acts.get(i));
            assertEquals(acts.get(i).target(), angel, "the Angel first");
        }
        for (int i = 5; i < 15; i++) {
            assertTrue(acts.get(i).ping(), "a ping: " + acts.get(i));
            assertEquals(acts.get(i).target(), opp);
        }
        assertEquals(countCardsWithName(game, ANGEL), 0, "the Angel died");
    }

    // Two Ballistas on ours, with 3 and 4 counters, the opponent at 10: their counters together count toward the kill,
    // the one with 4 is pumped to 7 (the other never), and the ten pings come from both.
    @Test
    public void twoBallistasShareTheVolley() {
        start();
        opp.setLife(10, null);
        final Card three = ballistaOnOurs(3);
        final Card four = ballistaOnOurs(4);
        assertEquals(LoopSinks.lethalNeeded(ai), 10);
        final List<Act> acts = drive(MAX_ACTIONS);
        assertTrue(game.isGameOver(), "the game ended");
        opponentLostByLife(opp);
        final List<Act> pumps = only(acts, Act::pump);
        assertEquals(pumps.size(), 3, "pumped to 7: " + acts);
        for (final Act p : pumps) {
            assertEquals(p.host(), four, "the one with the most counters");
        }
        final List<Act> pings = only(acts, Act::ping);
        assertEquals(pings.size(), 10);
        assertTrue(pings.stream().anyMatch(a -> a.host().equals(three)), "pings from the one with 3");
        assertTrue(pings.stream().anyMatch(a -> a.host().equals(four)), "pings from the one with 7");
        assertTrue(lastIndex(acts, Act::pump) < firstIndex(acts, Act::ping), "every pump before the volley");
    }

    // R6: Ballista on our battlefield with 2 counters, nothing else to spend on. Without a hoser the go-off starts for
    // Ballista's pump (a refill first: Basalt's tap); under the opponent's Pithing Needle naming Walking Ballista its
    // pump and its ping are refused, so Ballista takes no step and no go-off starts on its account, though the line
    // itself is not denied.
    @Test
    public void pithingNeedleOnBallistaMeansNoBallistaStep() {
        start();
        ballistaOnOurs(2);
        assertEquals(LoopPool.stockTarget(ai, line(KINNAN_BASALT)), 0, "nothing else to spend on");
        assertSame(driver.mainPhaseAction(List.of()), manaOf(basalt), "the control: a refill toward the pump");

        start();
        final Card ballista = ballistaOnOurs(2);
        addCard("Pithing Needle", opp).setNamedCards(List.of(BALLISTA));
        assertFalse(LoopSinks.ballistaCanShoot(ai));
        assertNull(LoopSinks.ballista(ai), "no Ballista step");
        assertFalse(ComboSignals.denied(ai, line(KINNAN_BASALT), t), "the line itself is not denied");
        assertNull(driver.mainPhaseAction(List.of()), "no go-off on Ballista's account");
        assertEquals(ballista.getCounters(CounterEnumType.P1P1), 2);
    }

    // R25: the opponent's Platinum Angel wearing Darksteel Plate is indestructible, so no ping can kill it: it is no
    // loss blocker, and with it on the battlefield we cannot win, so every loop line reads DENIED though a Ballista is
    // in our hand. Without the Plate the Angel is a loss blocker and the line stands.
    @Test
    public void anIndestructibleAngelDenies() {
        start();
        final Card angel = addCard(ANGEL, opp);
        ballistaInHand();
        assertSame(LoopSinks.lossBlocker(ai), angel, "the control: a killable Angel");
        assertNull(ComboSignals.abandon(ai, line(KINNAN_BASALT), t));
        addCard("Darksteel Plate", opp).attachToEntity(angel, null);
        game.getAction().checkStateEffects(true);
        assertTrue(angel.hasKeyword(Keyword.INDESTRUCTIBLE));
        assertTrue(ai.cantWin());
        assertEquals(LoopSinks.lossBlockers(ai), List.of());
        assertEquals(LoopSinks.lethalNeeded(ai), 0, "nothing the volley can kill");
        assertEquals(t.deckLines().size(), 19);
        for (final ComboLines.Line l : t.deckLines()) {
            assertEquals(ComboSignals.abandon(ai, l, t), ComboSignals.Signal.DENIED, l.variant().id());
        }
        assertNull(driver.mainPhaseAction(List.of()));
    }

    // R25: the opponent at 3, Ballista on ours with 3 counters. The first ping lands (3 to 2); then the opponent's
    // Energy Field (all damage that sources they do not control would deal them is prevented) comes, and the next
    // ping changes nothing: the volley ends for the phase, and the driver pumps no more, though 2 is now short of what
    // the kill needs. A new phase reads Ballista afresh.
    @Test
    public void aPingThatChangesNothingEndsTheVolley() {
        start();
        opp.setLife(3, null);
        final Card ballista = ballistaOnOurs(3);
        final List<Act> first = drive(1);
        assertEquals(first.size(), 1);
        assertTrue(first.get(0).ping(), "the first ping: " + first);
        assertEquals(opp.getLife(), 2, "it landed");
        addCard("Energy Field", opp);
        final List<Act> rest = drive(MAX_ACTIONS);
        assertEquals(rest.size(), 1, "one more ping, then nothing: " + rest);
        assertTrue(rest.get(0).ping());
        assertEquals(opp.getLife(), 2, "the second ping was prevented");
        assertEquals(ballista.getCounters(CounterEnumType.P1P1), 1);
        assertTrue(LoopSinks.lethalNeeded(ai) > 1, "the counters are short of the kill");
        assertNull(driver.mainPhaseAction(List.of()), "no pump: the volley is over for the phase");
        assertNull(driver.mainPhaseAction(List.of()));

        moveToMain2(game, ai);
        assertSame(driver.mainPhaseAction(List.of()), manaOf(basalt), "a new phase: a refill toward a pump");
    }

    // The opponent's Lich's Mastery and nothing else: no opponent can lose, and an enchantment is no loss blocker, so
    // with Ballista in our hand no go-off starts, though the line can run and is not denied.
    @Test
    public void lichsMasteryMeansNoStart() {
        start();
        ballistaInHand();
        addCard("Lich's Mastery", opp);
        assertTrue(opp.cantLose());
        assertNull(LoopSinks.lossBlocker(ai));
        assertEquals(LoopSinks.lethalNeeded(ai), 0);
        assertTrue(LoopCycles.ready(ai, line(KINNAN_BASALT)));
        assertFalse(ComboSignals.denied(ai, line(KINNAN_BASALT), t));
        assertNull(driver.mainPhaseAction(List.of()));
    }

    // A three-player game, the opponents at 7 and 9, Ballista in our hand: the kill needs 16 counters, Ballista is cast
    // with them, then pings each opponent its life's worth: both lose, and the game ends.
    @Test
    public void aThreePlayerPodShootsEachOpponent() {
        startPod();
        final Player first = game.getPlayers().get(0);
        final Player second = game.getPlayers().get(2);
        first.setLife(7, null);
        second.setLife(9, null);
        ballistaInHand();
        assertEquals(ai.getOpponents().size(), 2, "two opponents");
        assertEquals(LoopSinks.lethalNeeded(ai), 16);
        final List<Act> acts = play(a -> { });
        assertTrue(game.isGameOver(), "the game ended");
        opponentLostByLife(first);
        opponentLostByLife(second);
        assertEquals(acts.size(), 17, "a cast and sixteen pings: " + acts);
        assertTrue(acts.get(0).cast());
        assertEquals(acts.get(0).x(), Integer.valueOf(16));
        final List<Act> pings = only(acts, Act::ping);
        assertEquals(pings.size(), 16);
        assertEquals(pings.stream().filter(a -> first.equals(a.target())).count(), 7L);
        assertEquals(pings.stream().filter(a -> second.equals(a.target())).count(), 9L);
    }

    // Our board already five times lethal with summoning-sick creatures (two Colossal Dreadmaws and a Runeclaw Bear
    // beside Kinnan: 16 power, the opponent at 3): nothing of theirs can attack this turn, and Kinnan's 2 is short of
    // the opponent's 3, so the kill this turn is Ballista's. It still goes off, in Main 1.
    @Test
    public void aDecisiveBoardStillShoots() {
        start();
        opp.setLife(3, null);
        addCard(DREADMAW, ai);
        addCard(DREADMAW, ai);
        addCard("Runeclaw Bear", ai);
        ballistaInHand();
        assertTrue(LoopTracker.Snapshot.of(ai).isDecisive(), "five times the opponent's 3");
        final List<Act> acts = play(a -> { });
        assertTrue(game.isGameOver(), "the game ended");
        opponentLostByLife(opp);
        assertTrue(game.getPhaseHandler().is(PhaseType.MAIN1, ai), "in our Main 1");
        assertEquals(acts.size(), 4, "a cast and three pings: " + acts);
        assertEquals(acts.get(0).x(), Integer.valueOf(3));
        assertEquals(only(acts, Act::ping).size(), 3);
    }

    // ---- beyond the plan's tests ----

    // R6's Needle with Ballista in our hand: its ping and its pump, read on it as they would be on our
    // battlefield, are refused, so Ballista takes no step, its cast included, whether the opponent can be shot or
    // not (a hexproof one: their Leyline of Sanctity).
    @Test
    public void pithingNeedleOnBallistaInHandMeansNoBallistaStepEither() {
        start();
        ballistaInHand();
        assertTrue(LoopSinks.ballistaCanShoot(ai), "the control");
        assertEquals(LoopSinks.ballistaPool(ai), 40, "the control: a cast at twice 20");
        addCard("Pithing Needle", opp).setNamedCards(List.of(BALLISTA));
        assertFalse(LoopSinks.ballistaCanShoot(ai));
        assertNull(LoopSinks.ballista(ai));
        assertEquals(LoopSinks.ballistaPool(ai), -1);
        addCard("Leyline of Sanctity", opp);
        game.getAction().checkStateEffects(true);
        assertNull(LoopSinks.ballista(ai), "no cast toward five times lethal either");
        assertEquals(LoopSinks.ballistaPool(ai), -1);
    }

    // R25, the other half: a Platinum Angel whose damage is prevented (the opponent's Inviolability on it) cannot be
    // killed by the pings either: no loss blocker, and the line reads DENIED.
    @Test
    public void anAngelWhoseDamageIsPreventedDenies() {
        start();
        final Card angel = addCard(ANGEL, opp);
        ballistaInHand();
        assertSame(LoopSinks.lossBlocker(ai), angel, "the control: a killable Angel");
        addCard("Inviolability", opp).attachToEntity(angel, null);
        game.getAction().checkStateEffects(true);
        assertTrue(angel.isEnchanted(), "Inviolability is on the Angel");
        assertEquals(LoopSinks.lossBlockers(ai), List.of());
        assertEquals(ComboSignals.abandon(ai, line(KINNAN_BASALT), t), ComboSignals.Signal.DENIED);
        assertNull(driver.mainPhaseAction(List.of()));
    }

    // R25's judge for a loss blocker: the opponent's Platinum Angel, the opponent at 3, Ballista on ours with 10
    // counters (the kill needs 7). The first ping lands on the Angel (its toughness left 4 to 3); then its damage is
    // gone, as a heal's or a regeneration's would be, so the ping changed nothing on it. Ballista would ping it again,
    // but the volley is over for the phase: the driver pings and pumps no more, and the opponent stays at 3. A new
    // phase reads Ballista afresh: a ping at the Angel.
    @Test
    public void aPingThatChangesNothingOnALossBlockerEndsTheVolley() {
        start();
        opp.setLife(3, null);
        final Card angel = addCard(ANGEL, opp);
        final Card ballista = ballistaOnOurs(10);
        assertSame(LoopSinks.lossBlocker(ai), angel);
        assertEquals(LoopSinks.lethalNeeded(ai), 7, "the opponent's 3 and the Angel's 4");
        final List<Act> first = drive(1);
        assertEquals(first.size(), 1);
        assertTrue(first.get(0).ping(), "the first ping: " + first);
        assertEquals(first.get(0).target(), angel, "the Angel first");
        assertEquals(LoopSinks.toughnessLeft(angel), 3, "it landed");
        angel.setDamage(0);
        final SpellAbility again = LoopSinks.ballista(ai);
        assertNotNull(again, "the control: Ballista has a ping to make");
        assertEquals(firstTarget(again), angel, "the control: at the Angel");
        assertNull(driver.mainPhaseAction(List.of()), "the volley is over for the phase");
        final List<Act> rest = drive(MAX_ACTIONS);
        assertTrue(rest.stream().noneMatch(a -> a.pump() || a.ping()), "no pump and no ping: " + rest);
        assertEquals(opp.getLife(), 3);
        assertEquals(ballista.getCounters(CounterEnumType.P1P1), 9);

        moveToMain2(game, ai);
        final SpellAbility afresh = driver.mainPhaseAction(List.of());
        assertNotNull(afresh, "a new phase: Ballista read afresh");
        assertEquals(afresh.getApi(), ApiType.DealDamage, "a ping: " + afresh);
        assertEquals(firstTarget(afresh), angel, "at the Angel");
        assertEquals(decisionDraws, 0, "the driver's decisions drew");
    }

    // No margin (spec 6.4: "if an opponent gains life in response, the loop just goes round again"): the opponent at
    // 5, Ballista on ours with 8 counters (the kill needs 5). The first ping lands (5 to 4); then the opponent gains 5,
    // to 9, more than the 5 they had when the ping was returned. That is no ping that changed nothing: the next
    // decision counts again, the kill needs 9 and Ballista holds 7, so it pumps twice and pings nine times, and the
    // opponent loses for having 0 life.
    @Test
    public void lifeGainedInResponseGoesAroundAgain() {
        start();
        opp.setLife(5, null);
        ballistaOnOurs(8);
        final List<Act> first = drive(1);
        assertEquals(first.size(), 1);
        assertTrue(first.get(0).ping(), "the first ping: " + first);
        assertEquals(opp.getLife(), 4, "it landed");
        opp.setLife(9, null);
        assertEquals(LoopSinks.lethalNeeded(ai), 9, "counted again");
        final List<Act> rest = drive(MAX_ACTIONS);
        assertTrue(game.isGameOver(), "the game ended: " + rest);
        opponentLostByLife(opp);
        assertEquals(only(rest, Act::pump).size(), 2, "from 7 counters to 9: " + rest);
        assertEquals(only(rest, Act::ping).size(), 9, "nine pings: " + rest);
    }

    // The plan's rule "a Cursed Totem that arrives mid-go-off": after one refill cycle toward Ballista's pump, the
    // opponent's Cursed Totem refuses every activated ability of a creature, so Ballista takes no step: no pump, no
    // ping, and with nothing else to spend on, nothing more.
    @Test
    public void aCursedTotemArrivingMidGoOffStopsBallista() {
        start();
        opp.setLife(10, null);
        final Card ballista = ballistaOnOurs(2);
        final List<Act> refill = drive(2);
        assertEquals(refill.size(), 2, "one cycle: " + refill);
        for (final Act a : refill) {
            assertEquals(a.host(), basalt, "a refill: " + a);
        }
        addCard("Cursed Totem", opp);
        assertFalse(LoopSinks.ballistaCanShoot(ai));
        assertNull(LoopSinks.ballista(ai));
        final List<Act> after = drive(MAX_ACTIONS);
        assertTrue(after.stream().noneMatch(Act::ballista), "no Ballista step: " + after);
        assertEquals(ballista.getCounters(CounterEnumType.P1P1), 2);
        assertEquals(opp.getLife(), 10);
    }

    // "One is cast from hand only when none is on our battlefield": a Ballista on ours with 2 counters and another in
    // our hand, the opponent at 10: the one on the battlefield is pumped and shoots, and the other is never cast.
    @Test
    public void aBallistaInHandWaitsWhileOneIsOnOurBattlefield() {
        start();
        opp.setLife(10, null);
        final Card ballista = ballistaOnOurs(2);
        final Card inHand = ballistaInHand();
        final List<Act> acts = drive(MAX_ACTIONS);
        assertTrue(game.isGameOver(), "the game ended");
        opponentLostByLife(opp);
        assertTrue(acts.stream().noneMatch(Act::cast), "no cast: " + acts);
        assertEquals(only(acts, Act::pump).size(), 8);
        for (final Act a : only(acts, a -> a.pump() || a.ping())) {
            assertEquals(a.host(), ballista);
        }
        assertTrue(ai.getCardsIn(ZoneType.Hand).contains(inHand), "still in our hand");
    }

    // Only a Ballista of ours is one: the opponent's Walking Ballista on their battlefield (with 3 counters) and
    // another in their hand, the opponent at 10. With none of ours there is no volley: nothing to kill, no shot and no
    // step. With ours in our hand the kill is the opponent's 10, ours can be cast, and its cast is for twice that,
    // whatever theirs hold.
    @Test
    public void anOpponentsBallistaIsNoBallistaOfOurs() {
        start();
        opp.setLife(10, null);
        addCard(BALLISTA, opp).setCounters(CounterEnumType.P1P1, 3);
        addCardToZone(BALLISTA, opp, ZoneType.Hand);
        assertEquals(LoopSinks.lethalNeeded(ai), 0, "none of ours: no volley");
        assertFalse(LoopSinks.ballistaCanShoot(ai));
        assertEquals(LoopSinks.ballistaPool(ai), -1);
        ballistaInHand();
        assertEquals(LoopSinks.lethalNeeded(ai), 10);
        assertTrue(LoopSinks.ballistaCanShoot(ai), "ours can be cast");
        assertEquals(LoopSinks.ballistaPool(ai), 20, "a cast at twice 10, whatever theirs hold");
    }

    // What the kill needs, read on several boards: nothing with no Ballista of ours; the opponent's life with one in
    // our hand; with the opponent's Platinum Angel the Angel's toughness besides, less the damage marked on it; and
    // with a hexproof opponent, only the Angel.
    @Test
    public void lethalNeededIsTheLifeAndTheBlockersToughnessLeft() {
        start();
        assertEquals(LoopSinks.lethalNeeded(ai), 0, "no Ballista: no volley");
        assertFalse(LoopSinks.ballistaCanShoot(ai));
        ballistaInHand();
        assertEquals(LoopSinks.lethalNeeded(ai), 20);
        assertTrue(LoopSinks.ballistaCanShoot(ai));
        final Card angel = addCard(ANGEL, opp);
        assertEquals(LoopSinks.lethalNeeded(ai), 24, "and the Angel's 4");
        angel.setDamage(1);
        assertEquals(LoopSinks.lethalNeeded(ai), 23, "the Angel's toughness left");
        addCard("Leyline of Sanctity", opp);
        game.getAction().checkStateEffects(true);
        assertEquals(LoopSinks.lethalNeeded(ai), 3, "a hexproof opponent: the Angel alone");
        assertTrue(LoopSinks.ballistaCanShoot(ai), "the Angel can still be shot");
    }

    // Every loss blocker's toughness left counts toward the kill: two Platinum Angels of the opponent's between two of
    // their Runeclaw Bears (so that neither Angel is the first or the last of their permanents, and the volley's judge
    // must find the Angel it pinged), the opponent at 20, Ballista in our hand. The kill needs 28 counters: the pool is
    // built to 56, Ballista is cast with X = 28, and it pings four times at each Angel, in the battlefield's order,
    // then twenty times at the opponent, with no pump: both Angels die, the opponent loses for having 0 life, and the
    // driver's decisions draw nothing.
    @Test
    public void twoPlatinumAngelsAreBothShotFirst() {
        start();
        addCard("Runeclaw Bear", opp);
        final Card first = addCard(ANGEL, opp);
        final Card second = addCard(ANGEL, opp);
        addCard("Runeclaw Bear", opp);
        ballistaInHand();
        assertEquals(LoopSinks.lossBlockers(ai), List.of(first, second), "both Angels, in the battlefield's order");
        assertEquals(LoopSinks.lethalNeeded(ai), 28, "the opponent's 20 and each Angel's 4");
        final List<Act> acts = drive(MAX_ACTIONS);
        assertTrue(game.isGameOver(), "the game ended: " + acts);
        opponentLostByLife(opp);
        final List<Act> casts = only(acts, Act::cast);
        assertEquals(casts.size(), 1, "one cast: " + acts);
        assertEquals(casts.get(0).x(), Integer.valueOf(28), "X at the whole kill");
        assertEquals(casts.get(0).poolBefore(), 56, "the pool built to twice 28");
        assertTrue(acts.stream().noneMatch(Act::pump), "no pump: " + acts);
        final List<Act> pings = only(acts, Act::ping);
        assertEquals(pings.size(), 28, "four, four and twenty: " + acts);
        for (int i = 0; i < 4; i++) {
            assertEquals(pings.get(i).target(), first, "the first Angel first");
        }
        for (int i = 4; i < 8; i++) {
            assertEquals(pings.get(i).target(), second, "then the second Angel");
        }
        for (int i = 8; i < 28; i++) {
            assertEquals(pings.get(i).target(), opp, "then the opponent");
        }
        assertEquals(countCardsWithName(game, ANGEL), 0, "both Angels died");
        assertEquals(decisionDraws, 0, "the driver's decisions drew");
    }

    // The Angel's whole point: an opponent at 0 life or less stays in the game while it stands. The opponent at -3
    // behind their Platinum Angel, Ballista in our hand: the kill is the Angel's toughness alone (their life counts as
    // 0, not -3 and not 1), so Ballista is cast with X = 4 and pings the Angel four times; the Angel dies, and the
    // opponent then loses for having -3 life.
    @Test
    public void anOpponentBelowZeroLifeBehindAnAngelNeedsOnlyTheAngelKilled() {
        start();
        final Card angel = addCard(ANGEL, opp);
        opp.setLife(-3, null);
        ballistaInHand();
        game.getAction().checkStateEffects(true);
        assertFalse(opp.hasLost(), "the Angel keeps them in the game");
        assertSame(LoopSinks.lossBlocker(ai), angel);
        assertEquals(LoopSinks.lethalNeeded(ai), 4, "the Angel's toughness alone");
        final List<Act> acts = drive(MAX_ACTIONS);
        assertTrue(game.isGameOver(), "the game ended: " + acts);
        opponentLostByLife(opp);
        final List<Act> casts = only(acts, Act::cast);
        assertEquals(casts.size(), 1, "one cast: " + acts);
        assertEquals(casts.get(0).x(), Integer.valueOf(4), "X at the Angel's toughness");
        final List<Act> pings = only(acts, Act::ping);
        assertEquals(pings.size(), 4, "four pings: " + acts);
        assertTrue(pings.stream().allMatch(a -> angel.equals(a.target())), "all at the Angel: " + pings);
        assertEquals(countCardsWithName(game, ANGEL), 0, "the Angel died");
    }

    // A tax on the cast (the opponent's Sphere of Resistance: spells cost {1} more) is paid beside {X}{X}: the pool is
    // built to 41, Ballista is cast with X = 20, and the twenty pings kill.
    @Test
    public void aTaxOnTheCastIsPaidBesideX() {
        start();
        addCard("Sphere of Resistance", opp);
        ballistaInHand();
        final List<Act> acts = drive(MAX_ACTIONS);
        assertTrue(game.isGameOver(), "the game ended");
        opponentLostByLife(opp);
        final List<Act> casts = only(acts, Act::cast);
        assertEquals(casts.size(), 1);
        assertEquals(casts.get(0).x(), Integer.valueOf(20));
        assertEquals(casts.get(0).poolBefore(), 41, "twice 20 and the tax");
        assertEquals(only(acts, Act::ping).size(), 20);
        assertTrue(acts.stream().noneMatch(Act::pump));
        assertEquals(decisionDraws, 0, "the driver's decisions drew");
    }

    // The driver's decisions draw no random number over a whole Ballista go-off: pumps, refills and the volley.
    @Test
    public void theBallistaStepDrawsNothing() {
        start();
        opp.setLife(10, null);
        ballistaOnOurs(2);
        final List<Act> acts = drive(MAX_ACTIONS);
        assertTrue(game.isGameOver(), "the game ended");
        assertEquals(only(acts, Act::ping).size(), 10);
        assertEquals(decisionDraws, 0, "the driver's decisions drew");
    }

    // "Refill until the pool holds 2 x lethalNeeded or 200, whichever is less, then cast with X = half the pool, ...
    // Pumps make up any rest": the opponent at 150, Ballista in our hand, the pool already at 200. Ballista is cast at
    // once with X = 100, then pumped fifty times and shoots 150 times.
    @Test
    public void aCastIsBuiltToTwoHundredAtMost() {
        start();
        opp.setLife(150, null);
        ballistaInHand();
        assertEquals(LoopSinks.lethalNeeded(ai), 150);
        assertEquals(LoopSinks.ballistaPool(ai), LoopPool.TARGET, "twice 150, at most 200");
        floatColourless(LoopPool.TARGET);
        final List<Act> acts = drive(MAX_ACTIONS);
        assertTrue(game.isGameOver(), "the game ended");
        opponentLostByLife(opp);
        assertTrue(acts.get(0).cast(), "cast at once: " + acts.get(0));
        assertEquals(acts.get(0).x(), Integer.valueOf(100), "half the pool");
        assertEquals(only(acts, Act::cast).size(), 1);
        assertEquals(only(acts, Act::pump).size(), 50, "pumps make up the rest");
        assertEquals(only(acts, Act::ping).size(), 150);
        assertEquals(decisionDraws, 0, "the driver's decisions drew");
    }

    // An opponent whose life total cannot change (their Platinum Emperion) can be shot at, but no ping moves them: the
    // volley counts no such opponent, so Ballista cannot shoot, and the Emperion is no loss blocker.
    @Test
    public void anOpponentWhoseLifeCannotChangeIsNoKill() {
        start();
        ballistaInHand();
        assertEquals(LoopSinks.lethalNeeded(ai), 20, "the control");
        addCard("Platinum Emperion", opp);
        game.getAction().checkStateEffects(true);
        assertFalse(opp.canLoseLife());
        assertEquals(LoopSinks.lethalNeeded(ai), 0);
        assertFalse(LoopSinks.ballistaCanShoot(ai));
        assertNull(LoopSinks.lossBlocker(ai));
    }

    // Every Ballista action counts toward the caps and stamps the turn for the runaway throttle's lift: with room
    // for two actions a turn, Ballista on ours with 3 counters and the opponent at 3, two pings, then the turn's cap
    // and its one log line.
    @Test
    public void theCapsCountBallistasActions() {
        start();
        opp.setLife(3, null);
        ballistaOnOurs(3);
        driver.useRounds(new LoopRounds(2, 100));
        final List<Act> acts = drive(MAX_ACTIONS);
        assertEquals(acts.size(), 2, "two actions: " + acts);
        assertTrue(acts.stream().allMatch(Act::ping), "two pings: " + acts);
        assertEquals(opp.getLife(), 1);
        assertTrue(driver.liftsThrottle(), "a Ballista action lifts the throttle for the turn");
        assertEquals(logged("Loop driver: stopped for the turn at 2 actions."), 1);
    }

    // R21 for a Ballista action: a pump returned and never played leaves the board as it was, so the next decision
    // fails its line for the phase. The pool is already at the pump's 4, so the pump is the first action.
    @Test
    public void anUnplayedPumpFailsItsLine() {
        start();
        opp.setLife(10, null);
        final Card ballista = ballistaOnOurs(2);
        floatColourless(4);
        final SpellAbility pump = driver.mainPhaseAction(List.of());
        assertNotNull(pump, "a go-off starts");
        assertEquals(pump.getApi(), ApiType.PutCounter, "the pump first: " + pump);
        assertEquals(pump.getHostCard(), ballista);
        assertNull(driver.mainPhaseAction(List.of()), "nothing changed: the line is failed");
    }

    // The futility gate holds back a go-off whose only spender is the stock AI. Ballista on ours with 2 counters under
    // the opponent's Pithing Needle (no Ballista step), two Counterspells and two Islands: Main 1 builds the pool to 2
    // and the stock AI casts nothing, a futile go-off. In Main 2 the same board starts nothing; with the Needle gone
    // (our hand and battlefield as they were) Ballista has a step again, which the gate does not hold back.
    @Test
    public void aBallistaStepIsNoFutileGoOff() {
        start();
        final Card ballista = ballistaOnOurs(2);
        final Card needle = addCard("Pithing Needle", opp);
        needle.setNamedCards(List.of(BALLISTA));
        addCardToZone("Counterspell", ai, ZoneType.Hand);
        addCardToZone("Counterspell", ai, ZoneType.Hand);
        addCard("Island", ai);
        addCard("Island", ai);
        ComboLoopBoards.stepUntil(game, g -> !g.getPhaseHandler().is(PhaseType.MAIN1), 200);
        assertFalse(game.getPhaseHandler().is(PhaseType.MAIN1), "Main 1 ended");
        assertEquals(manaOf(basalt).getActivationsThisTurn(), 2, "built to 2");
        assertEquals(ai.getCardsIn(ZoneType.Hand).size(), 2, "neither counterspell cast");
        assertEquals(ballista.getCounters(CounterEnumType.P1P1), 2, "no Ballista step");
        moveToMain2(game, ai);
        assertNull(driver.mainPhaseAction(List.of()), "the futile build is not repeated");
        game.getAction().moveToGraveyard(needle, null);
        assertSame(driver.mainPhaseAction(List.of()), manaOf(basalt), "Ballista's step: a refill toward its pump");
    }

    // With no opponent able to lose, the driver goes on only while Ballista can kill the loss blocker that stops one
    // (spec 6.2), not merely while one its ping could target stands: the opponent's Platinum Angel, and our Ballista
    // under their Pithing Needle naming it. Signal 2 does not deny the line (it reads the target, not the activation),
    // and Endless One in our hand is a stock target of 200, yet no go-off starts; with the Needle gone, one does.
    @Test
    public void aLossBlockerBallistaCannotShootStartsNothing() {
        start();
        final Card angel = addCard(ANGEL, opp);
        ballistaOnOurs(2);
        final Card needle = addCard("Pithing Needle", opp);
        needle.setNamedCards(List.of(BALLISTA));
        addCardToZone("Endless One", ai, ZoneType.Hand);
        assertSame(LoopSinks.lossBlocker(ai), angel);
        assertFalse(ComboSignals.denied(ai, line(KINNAN_BASALT), t), "signal 2 reads the target, not the activation");
        assertEquals(LoopPool.stockTarget(ai, line(KINNAN_BASALT)), LoopPool.TARGET);
        assertFalse(LoopSinks.ballistaCanShoot(ai));
        assertNull(driver.mainPhaseAction(List.of()), "no go-off: Ballista cannot shoot the Angel");
        game.getAction().moveToGraveyard(needle, null);
        assertSame(driver.mainPhaseAction(List.of()), manaOf(basalt), "Ballista can: a refill toward its pump");
    }

    // A cast is for what the kill needs and never more, and never with X = 0: the opponent at 20, Ballista in our hand.
    // With 1 mana floating there is no cast to take (the driver builds the pool to 40 first); with 50 the driver casts
    // Ballista with X = 20, not half the pool.
    @Test
    public void aCastTakesNoMoreThanTheKillNeedsAndNeverXZero() {
        start();
        ballistaInHand();
        floatColourless(1);
        assertNull(LoopSinks.ballista(ai), "no X of 0");
        assertEquals(LoopSinks.ballistaPool(ai), 40);
        floatColourless(49);
        final SpellAbility cast = driver.mainPhaseAction(List.of());
        assertNotNull(cast, "a go-off starts");
        assertTrue(cast.isSpell(), "the cast at once: " + cast);
        assertEquals(cast.getXManaCostPaid(), Integer.valueOf(20), "what the kill needs, not half the pool");
    }

    // The smallest cast: the opponent at 1, Ballista in our hand. The kill needs one counter and the pool 2, so the
    // driver builds 2 with Basalt Monolith, casts Ballista with X = 1 and pings once; the opponent loses for having 0
    // life.
    @Test
    public void theSmallestCastIsXOneAtAPoolOfTwo() {
        start();
        opp.setLife(1, null);
        ballistaInHand();
        assertEquals(LoopSinks.lethalNeeded(ai), 1);
        assertEquals(LoopSinks.ballistaPool(ai), 2, "twice 1");
        final List<Act> acts = drive(MAX_ACTIONS);
        assertTrue(game.isGameOver(), "the game ended: " + acts);
        opponentLostByLife(opp);
        final List<Act> casts = only(acts, Act::cast);
        assertEquals(casts.size(), 1, "one cast: " + acts);
        assertEquals(casts.get(0).x(), Integer.valueOf(1), "X = 1");
        assertEquals(casts.get(0).poolBefore(), 2, "the pool built to 2");
        final List<Act> pings = only(acts, Act::ping);
        assertEquals(pings.size(), 1, "one ping: " + acts);
        assertEquals(pings.get(0).target(), opp);
        assertTrue(acts.stream().noneMatch(Act::pump), "no pump: " + acts);
        for (final Act a : only(acts, a -> !a.ballista())) {
            assertEquals(a.host(), basalt, "a refill: " + a);
        }
    }

    // The counters that make the board five times lethal count only the opponents who can lose: in a three-player
    // game, one opponent under their Lich's Mastery (at 20, unable to lose) and the other hexproof (their Leyline of
    // Sanctity, at 4). No ping can kill, and the cast from our hand is for the 18 that make five times that hexproof
    // opponent's 4 with Kinnan's 2: a pool of 36.
    @Test
    public void aFiveTimesCastCountsOnlyTheOpponentsWhoCanLose() {
        startPod();
        final Player locked = game.getPlayers().get(0);
        final Player shielded = game.getPlayers().get(2);
        addCard("Lich's Mastery", locked);
        shielded.setLife(4, null);
        addCard("Leyline of Sanctity", shielded);
        ballistaInHand();
        game.getAction().checkStateEffects(true);
        assertTrue(locked.cantLose());
        assertTrue(shielded.hasKeyword(Keyword.HEXPROOF));
        assertEquals(LoopSinks.lethalNeeded(ai), 0, "no opponent the volley can kill");
        assertEquals(LoopSinks.ballistaPool(ai), 36, "twice the 18 that make five times 4");
    }

    // A tax on Ballista's activations is paid in full. Under the opponent's Suppression Field (abilities that are no
    // mana abilities cost {2} more), with Nyxbloom Ancient beside Kinnan and Basalt Monolith (a cycle still nets 5:
    // Basalt's 3 tripled and Kinnan's 1, less the untap's 5), Ballista on ours with 2 counters and the opponent at 4:
    // each pump waits for its 6 and each ping for its 2, and the opponent dies.
    @Test
    public void aTaxedPumpAndPingArePaidInFull() {
        start();
        opp.setLife(4, null);
        ComboLoopBoards.ready(addCard("Nyxbloom Ancient", ai));
        ballistaOnOurs(2);
        addCard("Suppression Field", opp);
        assertEquals(LoopSinks.ballistaPool(ai), 6, "the pump's 4 and the tax");
        final List<Act> acts = drive(MAX_ACTIONS);
        assertTrue(game.isGameOver(), "the game ended: " + acts);
        opponentLostByLife(opp);
        assertEquals(only(acts, Act::pump).size(), 2);
        assertEquals(only(acts, Act::ping).size(), 4);
        for (final Act a : only(acts, Act::pump)) {
            assertTrue(a.poolBefore() >= 6, "a pump paid in full: " + a);
        }
        for (final Act a : only(acts, Act::ping)) {
            assertTrue(a.poolBefore() >= 2, "a ping paid in full: " + a);
        }
    }

    // A go-off in which Ballista spent is no futile one, whatever the stock AI does after it. The opponent at 2 with
    // their Energy Field, Ballista on ours with 2 counters, two Counterspells and two Islands: Ballista's first ping
    // changes nothing, which ends its volley; the go-off then builds to the Counterspells' 2 and the walk plays
    // nothing. In Main 2, under the opponent's Pithing Needle naming Walking Ballista (so that a go-off would be the
    // stock AI's alone, which is what the futility gate reads), the same hand and battlefield start a go-off again.
    @Test
    public void aGoOffBallistaSpentInIsNotFutile() {
        start();
        opp.setLife(2, null);
        addCard("Energy Field", opp);
        ballistaOnOurs(2);
        counterspells();
        final List<Act> acts = drive(MAX_ACTIONS);
        assertTrue(acts.get(0).ping(), "Ballista first: " + acts);
        assertEquals(opp.getLife(), 2, "the ping was prevented");
        assertEquals(pool(), 2, "then built to the Counterspells' 2: " + acts);
        assertNull(driver.afterStockWalk(), "the walk plays nothing");
        ai.getManaPool().clearPool(false);
        moveToMain2(game, ai);
        addCard("Pithing Needle", opp).setNamedCards(List.of(BALLISTA));
        assertSame(driver.mainPhaseAction(List.of()), manaOf(basalt), "not futile: Ballista spent in it");
    }

    // A round of Ballista's breaks a run of idle stock rounds, as any round the idle rule does not judge does (spec
    // 6.5: a round is one spend, a Ballista counter among them). Ballista on ours with 1 counter under the opponent's
    // Pithing Needle naming it, two Counterspells and two Islands: the go-off builds to 2 for the walk, whose play buys
    // nothing (an idle judged round). The Needle gone, Ballista takes a pump; a second Needle comes, the go-off builds
    // to 2 again and the walk's play buys nothing again: one idle round since Ballista's, not two, so the driver goes
    // on.
    @Test
    public void aBallistaRoundBreaksARunOfIdleRounds() {
        start();
        ballistaOnOurs(1);
        final Card needle = addCard("Pithing Needle", opp);
        needle.setNamedCards(List.of(BALLISTA));
        counterspells();
        final SpellAbility counter = ai.getCardsIn(ZoneType.Hand).get(0).getFirstSpellAbility();
        assertEquals(drive(MAX_ACTIONS).size(), 4, "built to 2, then the walk's turn");
        driver.noteStockPlay(counter);
        ai.getManaPool().clearPool(false);
        game.getAction().moveToGraveyard(needle, null);
        final List<Act> pumped = drive(9);
        assertEquals(pumped.size(), 9);
        assertTrue(pumped.get(8).pump(), "a pump after four cycles: " + pumped);
        addCard("Pithing Needle", opp).setNamedCards(List.of(BALLISTA));
        assertEquals(drive(MAX_ACTIONS).size(), 4, "built to 2 again, then the walk's turn");
        driver.noteStockPlay(counter);
        ai.getManaPool().clearPool(false);
        assertNotNull(driver.mainPhaseAction(List.of()), "one idle round since Ballista's, not two");
    }

    // A ping spends no mana, so it needs no boundary: Basalt Monolith tapped (its untap payable by our three Islands)
    // and Ballista on ours with 3 counters, the opponent at 3: the go-off's first action is a ping, not the untap.
    @Test
    public void aPingNeedsNoBoundary() {
        start();
        basalt.setTapped(true);
        addCard("Island", ai);
        addCard("Island", ai);
        addCard("Island", ai);
        opp.setLife(3, null);
        ballistaOnOurs(3);
        assertTrue(LoopCycles.ready(ai, line(KINNAN_BASALT)), "the line can run: its untap is payable");
        assertFalse(LoopCycles.boundary(ai, line(KINNAN_BASALT)), "mid-cycle");
        final SpellAbility first = driver.mainPhaseAction(List.of());
        assertNotNull(first, "a go-off starts");
        assertEquals(first.getApi(), ApiType.DealDamage, "a ping first: " + first);
    }

    // ---- the final review's round 1: what its mutation pass found untested ----

    // R30's budget (LoopSinks.volleyLife) priced part by part, on a board where the opponent's Harsh Mentor makes each
    // pump and each ping cost 2: Ballista on ours with one counter and the opponent at 10, so nine pumps ({4} each) and
    // ten pings, 38 life. The cycles of the line that makes the mana, 2 life each at a net of 3: as many as the 36 mana
    // takes, and one more, 13; each mana floating counts toward the 36; and cycles that net nothing never make it.
    @Test
    public void theVolleysPriceInLifeIsTheSumOfItsParts() {
        start();
        opp.setLife(10, null);
        ballistaOnOurs(1);
        addCard(HARSH_MENTOR, opp);
        assertEquals(LoopSinks.volleyLife(ai, 3, 0), 38L, "nine pumps and ten pings at 2 each");
        assertEquals(LoopSinks.volleyLife(ai, 3, 2), 38L + 26L, "and 13 cycles for the 36 mana");
        floatColourless(35);
        assertEquals(LoopSinks.volleyLife(ai, 3, 2), 38L + 4L, "one mana short: two cycles");
        floatColourless(1);
        assertEquals(LoopSinks.volleyLife(ai, 3, 2), 38L, "the pool pays it: no cycle");
        assertEquals(LoopSinks.volleyLife(ai, 0, 2), 38L, "no cycle is needed, whatever it nets");
        ai.getManaPool().clearPool(false);
        assertEquals(LoopSinks.volleyLife(ai, 0, 2), Long.MAX_VALUE, "cycles that net nothing never make the mana");
        assertEquals(LoopSinks.volleyLife(ai, -1, 2), Long.MAX_VALUE, "nor do cycles that net less");
    }

    // The mana is what the engine charges now, a tax included: under the opponent's Suppression Field ({2} more for an
    // activated ability that is no mana ability) a ping costs {2} and a pump {6}. Four pings and three pumps against
    // the opponent's 4 are 8 + 18 = 26 mana, 10 cycles at 2 life, and 3 x 2 + 4 x 2 = 14 life for the volley: 34.
    @Test
    public void theVolleysManaIsWhatTheEngineChargesNow() {
        start();
        opp.setLife(4, null);
        ballistaOnOurs(1);
        addCard(HARSH_MENTOR, opp);
        addCard("Suppression Field", opp);
        assertEquals(LoopSinks.volleyLife(ai, 3, 2), 34L, "26 mana: 10 cycles at 2, then 3 pumps and 4 pings at 2");
    }

    // Ballista in our hand, its cast at most X = 100 from the loop's pool of 200: the opponent's 20 needs the cast at
    // 20, twenty pings and no pump, the cast's 40 mana 15 cycles at a net of 3; the opponent's 150 needs fifty pumps
    // beside the cast at 100.
    @Test
    public void theVolleysPriceFromOurHandIsTheCastsPumpsAndPings() {
        start();
        opp.setLife(20, null);
        ballistaInHand();
        addCard(HARSH_MENTOR, opp);
        assertEquals(LoopSinks.volleyLife(ai, 3, 0), 40L, "twenty pings at 2, no pump");
        assertEquals(LoopSinks.volleyLife(ai, 3, 2), 40L + 30L, "and the 40 mana of the cast takes 15 cycles at 2");
        opp.setLife(150, null);
        assertEquals(LoopSinks.volleyLife(ai, 3, 0), 400L, "fifty pumps and 150 pings at 2 each");
    }

    // The cast is priced too: under the opponent's Spellshock (2 damage to a player for each spell they cast)
    // Ballista's cast from our hand costs 2, and its pump and ping, abilities, nothing; with a Ballista on our
    // battlefield too, the volley has no cast.
    @Test
    public void theVolleysPriceCountsTheCast() {
        start();
        opp.setLife(20, null);
        ballistaInHand();
        addCard(SPELLSHOCK, opp);
        assertEquals(LoopSinks.volleyLife(ai, 3, 0), 2L, "the cast alone");
        ballistaOnOurs(1);
        assertEquals(LoopSinks.volleyLife(ai, 3, 0), 0L, "a Ballista on our battlefield: no cast, nothing priced");
    }

    // Only a kill this turn is worth our life: the opponent's Leyline of Sanctity (player hexproof) leaves no ping to
    // aim, so the pumps toward five times lethal are never worth a price in life (Harsh Mentor's 2 for each), and the
    // driver takes no action.
    @Test
    public void aBoardNoPingCanKillIsNoKillWorthOurLife() {
        start();
        opp.setLife(4, null);
        addCard("Leyline of Sanctity", opp);
        addCard(HARSH_MENTOR, opp);
        ballistaOnOurs(1);
        game.getAction().checkStateEffects(true);
        assertEquals(LoopSinks.lethalNeeded(ai), 0, "no opponent a ping can target");
        assertEquals(LoopSinks.volleyLife(ai, 3, 0), Long.MAX_VALUE, "priced, and no kill to pay for");
        final List<Act> acts = drive(MAX_ACTIONS);
        assertTrue(acts.isEmpty(), "a pump or an untap that costs life: " + acts);
    }

    // ---- the final review's round 1: what its mutation pass's round 2 found untested ----

    // The cast's tax is in the volley's price beside its {X}{X}: under the opponent's Sphere of Resistance (each spell
    // costs {1} more) and Harsh Mentor (2 for each ping), Ballista in our hand against the opponent's 20 is cast at
    // X = 20 for 41 mana, which cycles of net 1 make in 42 cycles at 2 life each, beside twenty pings: 124.
    @Test
    public void theVolleysPriceFromOurHandCountsTheCastsTax() {
        start();
        opp.setLife(20, null);
        ballistaInHand();
        addCard(HARSH_MENTOR, opp);
        addCard("Sphere of Resistance", opp);
        assertEquals(LoopSinks.volleyLife(ai, 1, 2), 124L, "41 mana: 42 cycles at 2, and twenty pings at 2");
    }

    // The counters the cast leaves are the loop's pool of 200 less the tax, halved, and the cast's mana is at most that
    // pool: the opponent at 150 under the same Sphere of Resistance and Harsh Mentor, the cast at X = 99 for all 200
    // mana and 51 pumps ({4} each) beside it, 404 mana, so 405 cycles at a net of 1, and 2 life for each cycle, pump
    // and ping: 810 + 102 + 300.
    @Test
    public void theVolleysPriceFromOurHandCapsTheCastAtTheLoopsPool() {
        start();
        opp.setLife(150, null);
        ballistaInHand();
        addCard(HARSH_MENTOR, opp);
        addCard("Sphere of Resistance", opp);
        assertEquals(LoopSinks.volleyLife(ai, 1, 2), 1212L, "51 pumps, 150 pings and 405 cycles at 2 each");
    }

    // Cycles that cost nothing are never in the price, whatever they net: Ballista in our hand against the opponent's
    // 20 under Harsh Mentor, twenty pings at 2 and no cycle.
    @Test
    public void cyclesThatCostNothingAreNeverInTheVolleysPrice() {
        start();
        opp.setLife(20, null);
        ballistaInHand();
        addCard(HARSH_MENTOR, opp);
        assertEquals(LoopSinks.volleyLife(ai, 0, 0), 40L, "cycles that net nothing and cost nothing");
        assertEquals(LoopSinks.volleyLife(ai, -1, 0), 40L, "cycles that net less and cost nothing");
    }

    // The pumps the volley still needs are the counters all our Ballistas lack together: two on our battlefield with
    // one counter each, the opponent at 10 under Harsh Mentor (2 for each pump and ping): eight pumps and ten pings.
    @Test
    public void theVolleysPumpsAreWhatAllOurBallistasLack() {
        start();
        opp.setLife(10, null);
        ballistaOnOurs(1);
        ballistaOnOurs(1);
        addCard(HARSH_MENTOR, opp);
        assertEquals(LoopSinks.volleyLife(ai, 3, 0), 36L, "eight pumps and ten pings at 2 each");
    }
}
