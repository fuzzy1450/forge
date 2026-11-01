package forge.ai.combo;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertNotSame;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertSame;
import static org.testng.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.testng.annotations.Test;

import forge.ai.AITest;
import forge.ai.LobbyPlayerAi;
import forge.card.GamePieceType;
import forge.card.mana.ManaAtom;
import forge.game.Game;
import forge.game.ability.ApiType;
import forge.game.card.Card;
import forge.game.mana.Mana;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.trigger.Trigger;
import forge.game.trigger.TriggerType;
import forge.game.zone.MagicStack;
import forge.game.zone.ZoneType;
import forge.util.MyRandom;

/**
 * Palinchron's two loops (spec 2026-10-29-combo-aware-ai-loops-design.md sections 6.1 and 6.8; the plan's Task B7, R5
 * and R24). The flicker, Palinchron and Deadeye Navigator paired (1409-3542): every land of ours is tapped, one a
 * decision, then Palinchron is flickered with the {1}{U} Deadeye grants it, and Palinchron's entering untap and
 * Deadeye's re-pair resolve as stock. The return and recast, Palinchron and Nyxbloom Ancient (3430-3542): our lands are
 * tapped, each making three times its mana, Palinchron is returned with its own {2}{U}{U} and cast again for
 * {5}{U}{U}. Each cycle makes Palinchron a new object, read afresh at every decision. During such a cycle Palinchron's
 * untap takes our lands that make blue first, and Deadeye Navigator's own soulbond pairs it with Palinchron whenever
 * the deck holds the flicker line. Unless a test says otherwise the board is Kinnan's list registered (every piece of
 * the 19 loop rows, the two sinks, lands, Endless One and Counterspell), the tracker reading the shipped lines, both
 * seats on the Default profile, the AI in its own Main 1 with no AI deadline, and the opponent at 20 with nothing; the
 * flicker boards hold Palinchron and Deadeye paired, the recast boards Palinchron and Nyxbloom and no Deadeye (R5).
 * {@link #oneCycle} runs the whole game loop; {@link #drive} plays the driver's own decisions through the AI's play
 * path, every trigger resolved.
 */
public class ComboLoopPalinchronAiTest extends AITest {
    private static final String KINNAN = "Kinnan, Bonder Prodigy";
    private static final String PALINCHRON = "Palinchron";
    private static final String DEADEYE = "Deadeye Navigator";
    private static final String NYXBLOOM = "Nyxbloom Ancient";
    private static final String HULLBREAKER = "Hullbreaker Horror";
    private static final String BASALT = "Basalt Monolith";
    private static final String ENDLESS_ONE = "Endless One";
    private static final String COUNTERSPELL = "Counterspell";
    private static final String ISLAND = "Island";
    private static final String FOREST = "Forest";
    /** The flicker: Palinchron and Deadeye Navigator. */
    private static final String FLICKER = "1409-3542";
    /** The return and recast: Palinchron and Nyxbloom Ancient. */
    private static final String RECAST = "3430-3542";
    /** The rock untap of Kinnan and Basalt Monolith. */
    private static final String KINNAN_BASALT = "129-4131";
    /** The game-loop steps a stepping test may take for one cycle: nine taps and three stack items, and slack. */
    private static final int MAX_STEPS = 300;
    /** Kinnan's list beside its commander: every piece of the 19 loop rows, the sinks, lands and the boards' spells. */
    private static final List<String> KINNANS_LIST = kinnansList();

    private Game game;
    private Player ai;
    private Player opp;
    private ComboTracker t;
    private LoopDriver driver;
    /** The random draws made inside the driver's decisions while {@link #drive} ran, since {@link #start}. */
    private long decisionDraws;

    private static List<String> kinnansList() {
        final Set<String> names = new LinkedHashSet<>();
        for (final ComboLines.Line l : ComboLoopBoards.shippedLoopLines(LoopRows.ids().toArray(new String[0]))) {
            names.addAll(l.names());
        }
        names.remove(KINNAN);
        names.addAll(List.of(LoopSinks.BALLISTA, "Thrasios, Triton Hero", FOREST, ISLAND, ENDLESS_ONE, COUNTERSPELL));
        return List.copyOf(names);
    }

    /** A two-player game whose AI (player 1, in its own Main 1, no AI deadline) has Kinnan's list registered, both
     *  seats on the Default profile; nothing placed yet. */
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
    }

    /** Palinchron and Deadeye Navigator, ready on our battlefield and paired: Deadeye's {1}{U} flicker granted to both.
     *  Returns Palinchron. */
    private Card paired() {
        final Card palinchron = ComboLoopBoards.ready(addCard(PALINCHRON, ai));
        final Card deadeye = ComboLoopBoards.ready(addCard(DEADEYE, ai));
        palinchron.setPairedWith(deadeye);
        deadeye.setPairedWith(palinchron);
        game.getAction().checkStaticAbilities();
        assertNotNull(flickerOf(palinchron), "Deadeye's flicker was not granted to Palinchron");
        return palinchron;
    }

    /** Nyxbloom Ancient and Palinchron, ready on our battlefield, no Deadeye Navigator. Returns Palinchron. */
    private Card recast() {
        ComboLoopBoards.ready(addCard(NYXBLOOM, ai));
        return ComboLoopBoards.ready(addCard(PALINCHRON, ai));
    }

    /** {@code n} untapped lands of ours named {@code name}, in their order on our battlefield. */
    private List<Card> lands(final String name, final int n) {
        final List<Card> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            out.add(addCard(name, ai));
        }
        return out;
    }

    /** Endless One in our hand: an X spell the pool pays, so the stock AI's target is 200. */
    private Card endlessOne() {
        return addCardToZone(ENDLESS_ONE, ai, ZoneType.Hand);
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

    /** Our first Palinchron on our battlefield, or null. */
    private Card palinchron() {
        return onOurBattlefield(PALINCHRON);
    }

    /** Our first card named {@code name} on our battlefield (by ComboTracker.nameOf), or null. */
    private Card onOurBattlefield(final String name) {
        for (final Card c : ai.getCardsIn(ZoneType.Battlefield)) {
            if (name.equals(ComboTracker.nameOf(c))) {
                return c;
            }
        }
        return null;
    }

    /** The flicker a static grants the card: an activated ChangeZone to exile, found here and not through the driver;
     *  null when none is granted. */
    private static SpellAbility flickerOf(final Card c) {
        for (final SpellAbility sa : c.getNonManaAbilities()) {
            if (sa.isActivatedAbility() && sa.getApi() == ApiType.ChangeZone && sa.getGrantorStatic() != null
                    && "Exile".equals(sa.getParam("Destination"))) {
                return sa;
            }
        }
        return null;
    }

    /** Palinchron's own return to its owner's hand. */
    private static SpellAbility returnOf(final Card c) {
        for (final SpellAbility sa : c.getNonManaAbilities()) {
            if (sa.isActivatedAbility() && sa.getApi() == ApiType.ChangeZone && sa.getGrantorStatic() == null
                    && "Hand".equals(sa.getParam("Destination"))) {
                return sa;
            }
        }
        throw new AssertionError(c + " has no return");
    }

    /** The card's own activated Untap ability (Basalt Monolith's). */
    private static SpellAbility untapOf(final Card c) {
        for (final SpellAbility sa : c.getNonManaAbilities()) {
            if (sa.isActivatedAbility() && sa.getApi() == ApiType.Untap) {
                return sa;
            }
        }
        throw new AssertionError(c + " has no untap");
    }

    /** A land's own mana ability. */
    private static SpellAbility manaOf(final Card c) {
        for (final SpellAbility sa : c.getManaAbilities()) {
            if (sa.getApi() == ApiType.Mana) {
                return sa;
            }
        }
        throw new AssertionError(c + " has no mana ability");
    }

    /** Palinchron's entering untap: its ChangesZone trigger's Untap ability, the host Palinchron. */
    private static SpellAbility etbOf(final Card palinchron) {
        for (final Trigger tr : palinchron.getTriggers()) {
            final SpellAbility sa = tr.getMode() == TriggerType.ChangesZone ? tr.ensureAbility() : null;
            if (sa != null && sa.getApi() == ApiType.Untap) {
                return sa;
            }
        }
        throw new AssertionError(palinchron + " has no entering untap");
    }

    /** The Bond ability of the soulbond trigger of {@code c}'s own entering (ValidCards another creature of ours). */
    private static SpellAbility bondOf(final Card c) {
        for (final Trigger tr : c.getTriggers()) {
            final SpellAbility sa = tr.getMode() == TriggerType.ChangesZone ? tr.ensureAbility() : null;
            if (sa != null && sa.getApi() == ApiType.Bond && sa.getParam("ValidCards").contains("Other")) {
                return sa;
            }
        }
        throw new AssertionError(c + " has no soulbond of its own entering");
    }

    /** Whether our battlefield holds that very object (Card.equals compares ids, which a card keeps as it moves). */
    private boolean stillThere(final Card c) {
        for (final Card in : ai.getCardsIn(ZoneType.Battlefield)) {
            if (in == c) {
                return true;
            }
        }
        return false;
    }

    /** How many of {@code cards} are untapped. */
    private static int untapped(final List<Card> cards) {
        int n = 0;
        for (final Card c : cards) {
            if (c.isUntapped()) {
                n++;
            }
        }
        return n;
    }

    /** Steps the whole game loop until our Palinchron on our battlefield is a new object and every stack item and
     *  trigger has resolved (one flicker or recast cycle: its entering untap and Deadeye's re-pair done); returns what
     *  the pool gained. */
    private int oneCycle() {
        final int from = pool();
        final Card before = palinchron();
        assertNotNull(before, "Palinchron on our battlefield");
        ComboLoopBoards.stepUntil(game, g -> {
            final Card now = palinchron();
            return now != null && now != before && g.getStack().isEmpty()
                    && !g.getStack().hasSimultaneousStackEntries();
        }, MAX_STEPS);
        final Card after = palinchron();
        assertNotNull(after, "Palinchron came back");
        assertNotSame(after, before, "one cycle made Palinchron a new object");
        assertTrue(game.getStack().isEmpty() && !game.getStack().hasSimultaneousStackEntries(), "the cycle resolved");
        return pool() - from;
    }

    /** Resolves the stack and every trigger it adds, bounded. */
    private void resolveAll() {
        final MagicStack stack = game.getStack();
        for (int i = 0; i < 100 && (!stack.isEmpty() || stack.hasSimultaneousStackEntries()); i++) {
            stack.addAllTriggeredAbilitiesToStack();
            if (!stack.isEmpty()) {
                stack.resolveStack();
            }
        }
        assertTrue(stack.isEmpty() && !stack.hasSimultaneousStackEntries(), "the stack did not resolve");
    }

    /** Plays the driver's go-off actions, one decision at a time, each through the AI's own play path
     *  (PlayerControllerAi.playChosenSpellAbility: paid, then resolved at once or put on the stack) with the stack and
     *  every trigger then resolved, until it returns null or {@code max} actions; returns them in order. Before each
     *  decision the state-based actions are checked, as the game loop checks them before every priority. The random
     *  draws made inside the driver's decisions are added to {@link #decisionDraws}. */
    private List<SpellAbility> drive(final int max) {
        final List<SpellAbility> acts = new ArrayList<>();
        while (acts.size() < max) {
            game.getAction().checkStateEffects(true);
            final long before = MyRandom.callCount();
            final SpellAbility sa = driver.mainPhaseAction(List.of());
            decisionDraws += MyRandom.callCount() - before;
            if (sa == null) {
                break;
            }
            acts.add(sa);
            ai.getController().playChosenSpellAbility(sa);
            resolveAll();
        }
        return acts;
    }

    /** Our card {@code name}, cast: put on the stack as our spell and resolved (its payment aside), its entering
     *  triggers too; returns it on our battlefield (the last of that name there). */
    private Card entersOurs(final String name) {
        final Card card = createCard(name, ai);
        card.setGameTimestamp(game.getNextTimestamp());
        game.getStackZone().add(card);
        final SpellAbility sa = card.getFirstSpellAbility();
        sa.setActivatingPlayer(ai);
        game.getStack().add(sa);
        resolveAll();
        Card in = null;
        for (final Card c : ai.getCardsIn(ZoneType.Battlefield)) {
            if (name.equals(ComboTracker.nameOf(c))) {
                in = c;
            }
        }
        assertNotNull(in, name + " did not enter");
        return in;
    }

    /** {@code n} of our mana in our pool, of that kind. */
    private void floating(final int n, final byte kind, final Card source) {
        for (int i = 0; i < n; i++) {
            ai.getManaPool().addMana(new Mana(kind, source, null, ai));
        }
    }

    // ---- the plan's tests ----

    // Seven Islands: each cycle taps them for seven, pays the flicker's {1}{U}, and Palinchron's untap puts all seven
    // back: the pool grows by 5, the net the line reads.
    @Test
    public void oneFlickerCycleNetsTheLandsMinusTwo() {
        start();
        paired();
        final List<Card> islands = lands(ISLAND, 7);
        endlessOne();
        assertEquals(LoopCycles.net(ai, line(FLICKER)), 5);
        assertTrue(LoopCycles.ready(ai, line(FLICKER)));
        assertEquals(oneCycle(), 5);
        assertEquals(untapped(islands), 7, "every Island back untapped");
    }

    // Seven Forests pay no {U}: the flicker line nets 0, is not ready, and no go-off starts. An eighth land, an Island,
    // makes the cycle's blue: Palinchron untaps it and six Forests, a net of 5.
    @Test
    public void aFlickerNeedsBlue() {
        start();
        paired();
        lands(FOREST, 7);
        endlessOne();
        assertEquals(LoopCycles.net(ai, line(FLICKER)), 0);
        assertFalse(LoopCycles.ready(ai, line(FLICKER)));
        assertNull(driver.mainPhaseAction(List.of()));
        addCard(ISLAND, ai);
        assertEquals(LoopCycles.net(ai, line(FLICKER)), 5, "the control: one Island");
        assertTrue(LoopCycles.ready(ai, line(FLICKER)));
        assertNotNull(driver.mainPhaseAction(List.of()), "a go-off starts");
    }

    // Review Focus 4: each cycle makes Palinchron a new object, re-found at every decision with its flicker; three
    // cycles run back to back and the pool grows by 15.
    @Test
    public void threeFlickerCyclesInARow() {
        start();
        final Card first = paired();
        lands(ISLAND, 7);
        endlessOne();
        final Set<Card> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        seen.add(first);
        int grew = 0;
        for (int i = 0; i < 3; i++) {
            grew += oneCycle();
            assertTrue(seen.add(palinchron()), "cycle " + i + " made a new Palinchron");
            assertNotNull(flickerOf(palinchron()), "paired again after cycle " + i);
        }
        assertEquals(grew, 15);
        assertEquals(pool(), 15);
    }

    // Palinchron and Nyxbloom Ancient on ours and no Deadeye Navigator (with Deadeye paired the flicker would net 19
    // and be picked first: R5), four Islands and three Forests: the lands make 21, the return's {2}{U}{U} and the
    // recast's {5}{U}{U} cost 11, and one cycle grows the pool by 10.
    @Test
    public void oneRecastCycleNetsTenWithSevenLands() {
        start();
        recast();
        final List<Card> islands = lands(ISLAND, 4);
        final List<Card> forests = lands(FOREST, 3);
        endlessOne();
        assertEquals(LoopCycles.net(ai, line(RECAST)), 10);
        assertTrue(LoopCycles.ready(ai, line(RECAST)));
        assertEquals(oneCycle(), 10);
        assertEquals(untapped(islands) + untapped(forests), 7, "Palinchron's untap put all seven back");
    }

    // Four Islands, tripled, make 12, one more than the cycle's 11: the recast nets 1, and runs. Three make 9: the net
    // is -2, and the line is not ready.
    @Test
    public void fourLandsAreTooFewToRecast() {
        start();
        recast();
        lands(ISLAND, 4);
        endlessOne();
        assertEquals(LoopCycles.net(ai, line(RECAST)), 1);
        assertTrue(LoopCycles.ready(ai, line(RECAST)), "a net of 1 runs");

        start();
        recast();
        lands(ISLAND, 3);
        endlessOne();
        assertEquals(LoopCycles.net(ai, line(RECAST)), -2);
        assertFalse(LoopCycles.ready(ai, line(RECAST)));
        assertNull(driver.mainPhaseAction(List.of()));
    }

    // The recast pays {U}{U} twice a cycle, so the lands Palinchron untaps must make four blue a cycle: seven Forests
    // make none, and the line is not ready though it nets 10; one Island's three are too few, even beside four blue
    // floating from elsewhere, which the next cycle no longer has; two Islands' six are enough.
    @Test
    public void aRecastNeedsBlue() {
        start();
        recast();
        lands(FOREST, 7);
        endlessOne();
        assertEquals(LoopCycles.net(ai, line(RECAST)), 10);
        assertFalse(LoopCycles.ready(ai, line(RECAST)));
        assertNull(driver.mainPhaseAction(List.of()));

        start();
        recast();
        final Card island = lands(ISLAND, 1).get(0);
        lands(FOREST, 6);
        endlessOne();
        assertFalse(LoopCycles.ready(ai, line(RECAST)), "one Island");
        floating(4, (byte) ManaAtom.BLUE, island);
        assertFalse(LoopCycles.ready(ai, line(RECAST)), "one Island, and four blue floating");

        start();
        recast();
        lands(ISLAND, 2);
        lands(FOREST, 5);
        endlessOne();
        assertTrue(LoopCycles.ready(ai, line(RECAST)), "two Islands");
    }

    // Nine lands of ours, five Forests and four Islands, all tapped by the cycle before its flicker: Palinchron's
    // untap takes all four Islands, then three Forests. The stock pick (ComputerUtilCard.getBestLandAI) takes the least
    // represented basic type first, the Islands there too; with five Islands and four Forests it would take the four
    // Forests first and leave two Islands tapped, where the cycle's untap takes all five Islands, the recast's as the
    // flicker's.
    @Test
    public void palinchronUntapsBlueFirst() {
        start();
        paired();
        final List<Card> forests = lands(FOREST, 5);
        final List<Card> islands = lands(ISLAND, 4);
        endlessOne();
        assertEquals(oneCycle(), 7, "nine taps less the flicker's two");
        assertEquals(untapped(islands), 4, "all four Islands");
        assertEquals(untapped(forests), 3);

        start();
        paired();
        final List<Card> forests2 = lands(FOREST, 4);
        final List<Card> islands2 = lands(ISLAND, 5);
        endlessOne();
        assertEquals(oneCycle(), 7);
        assertEquals(untapped(islands2), 5, "all five Islands");
        assertEquals(untapped(forests2), 2);

        // the recast's untap too: beside Nyxbloom, four Forests and five Islands make 27, less the cycle's 11
        start();
        recast();
        final List<Card> forests3 = lands(FOREST, 4);
        final List<Card> islands3 = lands(ISLAND, 5);
        endlessOne();
        assertEquals(oneCycle(), 16);
        assertEquals(untapped(islands3), 5, "all five Islands, the recast's");
        assertEquals(untapped(forests3), 2);
    }

    // Deadeye Navigator enters with Palinchron and Hullbreaker Horror (7/8, which the stock getBestCreatureAI prefers)
    // unpaired on ours: its soulbond, resolved through BondAi, pairs it with Palinchron, which gains the flicker. With
    // no Palinchron line held it pairs with Hullbreaker, and so it does with the recast line alone held: the pairing
    // serves the flicker line.
    @Test
    public void deadeyePairsWithPalinchron() {
        start();
        final Card palinchron = ComboLoopBoards.ready(addCard(PALINCHRON, ai));
        ComboLoopBoards.ready(addCard(HULLBREAKER, ai));
        final Card deadeye = entersOurs(DEADEYE);
        assertSame(deadeye.getPairedWith(), palinchron);
        assertNotNull(flickerOf(palinchron), "the flicker granted");

        start();
        t.useLines(ComboLoopBoards.shippedLoopLines(KINNAN_BASALT));
        ComboLoopBoards.ready(addCard(PALINCHRON, ai));
        final Card hullbreaker = ComboLoopBoards.ready(addCard(HULLBREAKER, ai));
        assertSame(entersOurs(DEADEYE).getPairedWith(), hullbreaker, "no Palinchron line: the stock pick");

        start();
        t.useLines(ComboLoopBoards.shippedLoopLines(RECAST));
        ComboLoopBoards.ready(addCard(PALINCHRON, ai));
        final Card hullbreaker2 = ComboLoopBoards.ready(addCard(HULLBREAKER, ai));
        assertSame(entersOurs(DEADEYE).getPairedWith(), hullbreaker2, "the recast line alone: the stock pick");
    }

    // A deck with no Palinchron line (the rock untap of Kinnan and Basalt Monolith alone held): Palinchron's untap and
    // Deadeye's pairing are the stock ones, null, though a rock go-off runs.
    @Test
    public void outsideALoopTheChoicesAreStock() {
        start();
        t.useLines(ComboLoopBoards.shippedLoopLines(KINNAN_BASALT));
        ComboLoopBoards.ready(addCard(KINNAN, ai));
        addCard(BASALT, ai);
        endlessOne();
        final Card palinchron = ComboLoopBoards.ready(addCard(PALINCHRON, ai));
        final Card deadeye = ComboLoopBoards.ready(addCard(DEADEYE, ai));
        final Card hullbreaker = ComboLoopBoards.ready(addCard(HULLBREAKER, ai));
        final List<Card> islands = lands(ISLAND, 2);
        for (final Card c : islands) {
            c.setTapped(true);
        }
        assertNull(driver.untapChoice(etbOf(palinchron), islands));
        assertNull(driver.partnerChoice(bondOf(deadeye), List.of(hullbreaker, palinchron)));
        assertEquals(drive(1).size(), 1, "a rock go-off runs");
        assertNull(driver.untapChoice(etbOf(palinchron), islands), "a rock go-off is no Palinchron cycle");
        assertNull(driver.partnerChoice(bondOf(deadeye), List.of(hullbreaker, palinchron)));
    }

    // ---- beyond the plan's tests ----

    // One flicker cycle through the driver's own decisions: our seven Islands' mana abilities, one a decision, in their
    // order, then the flicker Deadeye grants Palinchron, which the pool pays. Two Counterspells in hand set a target of
    // 2, which the pool holds at the first boundary after the cycle, where the stock walk's turn comes (null). The
    // walk's spend there, then a fresh refill: the next cycle's flicker is the new Palinchron's, read afresh. The
    // driver's decisions draw nothing.
    @Test
    public void theFlickerTapsEveryLandThenFlickersTheNewPalinchron() {
        start();
        final Card first = paired();
        final SpellAbility firstFlicker = flickerOf(first);
        final List<Card> islands = lands(ISLAND, 7);
        addCardToZone(COUNTERSPELL, ai, ZoneType.Hand);
        final Card counterspell = addCardToZone(COUNTERSPELL, ai, ZoneType.Hand);
        final List<SpellAbility> acts = drive(50);
        assertEquals(acts.size(), 8, "seven taps and the flicker, then the walk's turn");
        for (int i = 0; i < 7; i++) {
            assertSame(acts.get(i), manaOf(islands.get(i)), "Island " + i);
        }
        assertSame(acts.get(7), firstFlicker);
        assertEquals(pool(), 5);
        final Card second = palinchron();
        assertNotSame(second, first, "a new Palinchron");
        final SpellAbility secondFlicker = flickerOf(second);
        assertNotNull(secondFlicker, "paired again");
        assertNotSame(secondFlicker, firstFlicker);

        driver.noteStockPlay(counterspell.getFirstSpellAbility());
        ai.getManaPool().clearPool(false);
        final List<SpellAbility> again = drive(50);
        assertEquals(again.size(), 8, "refilled to 2 with one cycle");
        assertSame(again.get(7), secondFlicker, "the new object's flicker");
        assertEquals(decisionDraws, 0, "the driver's decisions drew");
    }

    // The cycle taps the lands that make no blue first and the blue last, so the blue the flicker is paid with stays
    // among our untapped lands at every boundary: an Island first on our battlefield, then three Forests, then three
    // Islands are tapped Forests first, then the Islands in their order, then the flicker.
    @Test
    public void theLandsThatMakeNoBlueAreTappedFirst() {
        start();
        paired();
        final Card island = addCard(ISLAND, ai);
        final List<Card> forests = lands(FOREST, 3);
        final List<Card> islands = lands(ISLAND, 3);
        endlessOne();
        final List<SpellAbility> acts = drive(8);
        assertEquals(acts.size(), 8);
        for (int i = 0; i < 3; i++) {
            assertSame(acts.get(i), manaOf(forests.get(i)), "Forest " + i);
        }
        assertSame(acts.get(3), manaOf(island));
        for (int i = 0; i < 3; i++) {
            assertSame(acts.get(4 + i), manaOf(islands.get(i)), "Island " + i);
        }
        assertEquals(acts.get(7).getApi(), ApiType.ChangeZone, "the flicker");
    }

    // The cycle taps our lands alone, and only those it may tap now: a summoning-sick Dryad Arbor (a land creature,
    // first on our battlefield, which makes no blue) and a Sol Ring of ours stay untapped while the seven Islands are
    // tapped and Palinchron flickered.
    @Test
    public void theCycleTapsOnlyTheLandsItMayTap() {
        start();
        final Card palinchron = paired();
        final SpellAbility flicker = flickerOf(palinchron);
        final Card arbor = addCard("Dryad Arbor", ai);
        final Card ring = addCard("Sol Ring", ai);
        final List<Card> islands = lands(ISLAND, 7);
        endlessOne();
        assertTrue(arbor.isSick(), "the Arbor cannot tap this turn");
        final List<SpellAbility> acts = drive(8);
        assertEquals(acts.size(), 8);
        for (int i = 0; i < 7; i++) {
            assertSame(acts.get(i), manaOf(islands.get(i)), "Island " + i);
        }
        assertSame(acts.get(7), flicker);
        assertTrue(arbor.isUntapped(), "the Arbor");
        assertTrue(ring.isUntapped(), "Sol Ring");
    }

    // A land is tapped for the mana ability that makes blue, when it has one: Floodfarm Verge, whose first ability
    // makes {W} and its second {U} (we control a Plains), is the one blue land beside five Forests and a Plains, tapped
    // last and for its {U}, which pays the flicker.
    @Test
    public void aDualLandIsTappedForItsBlue() {
        start();
        final Card palinchron = paired();
        final SpellAbility flicker = flickerOf(palinchron);
        lands(FOREST, 5);
        lands("Plains", 1);
        final Card dual = addCard("Floodfarm Verge", ai);
        endlessOne();
        assertEquals(LoopPool.made(dual.getManaAbilities().get(0)), ManaAtom.WHITE, "its first ability makes white");
        assertTrue(LoopCycles.makesBlue(dual));
        assertEquals(LoopCycles.net(ai, line(FLICKER)), 5);
        final List<SpellAbility> acts = drive(8);
        assertEquals(acts.size(), 8);
        assertSame(acts.get(6).getHostCard(), dual, "the blue land last");
        assertEquals(LoopPool.made(acts.get(6)), ManaAtom.BLUE, "for its blue");
        assertSame(acts.get(7), flicker);
        assertEquals(pool(), 5);
    }

    // The flicker is Deadeye's only while it is paired with Palinchron: with Deadeye paired with Hullbreaker Horror
    // instead, and with the two unpaired, the line nets 0, is not ready, and starts nothing; paired with Palinchron,
    // the control, it nets 5 and starts.
    @Test
    public void aFlickerNeedsThePair() {
        start();
        final Card palinchron = ComboLoopBoards.ready(addCard(PALINCHRON, ai));
        final Card deadeye = ComboLoopBoards.ready(addCard(DEADEYE, ai));
        final Card hullbreaker = ComboLoopBoards.ready(addCard(HULLBREAKER, ai));
        lands(ISLAND, 7);
        endlessOne();
        deadeye.setPairedWith(hullbreaker);
        hullbreaker.setPairedWith(deadeye);
        game.getAction().checkStaticAbilities();
        assertNotNull(flickerOf(hullbreaker), "Hullbreaker has the flicker");
        assertNull(flickerOf(palinchron));
        assertEquals(LoopCycles.net(ai, line(FLICKER)), 0, "paired with Hullbreaker");
        assertFalse(LoopCycles.ready(ai, line(FLICKER)));
        assertNull(driver.mainPhaseAction(List.of()));
        deadeye.setPairedWith(null);
        hullbreaker.setPairedWith(null);
        game.getAction().checkStaticAbilities();
        assertEquals(LoopCycles.net(ai, line(FLICKER)), 0, "unpaired");
        assertFalse(LoopCycles.ready(ai, line(FLICKER)));
        assertFalse(LoopCycles.boundary(ai, line(FLICKER)), "no flicker, no boundary");
        assertEquals(LoopCycles.colours(ai, line(FLICKER)), 0, "no cycle, no kinds");
        assertNull(LoopCycles.next(ai, line(FLICKER)), "no step");
        assertNull(driver.mainPhaseAction(List.of()));
        deadeye.setPairedWith(palinchron);
        palinchron.setPairedWith(deadeye);
        game.getAction().checkStaticAbilities();
        assertEquals(LoopCycles.net(ai, line(FLICKER)), 5, "the control: paired with Palinchron");
        assertTrue(LoopCycles.ready(ai, line(FLICKER)));
        assertNotNull(driver.mainPhaseAction(List.of()));
    }

    // The cycle must be able to pay its flicker from where it stands: one untapped Island beside six tapped Forests
    // makes one mana, not the {1}{U}, so the line is not ready though it nets 5 (it would tap the Island and stop); a
    // Forest untapped beside it pays the {1}, and so do two blue floating.
    @Test
    public void aFlickerOurLandsCannotPayFromHereStartsNothing() {
        start();
        final Card palinchron = paired();
        final Card island = addCard(ISLAND, ai);
        final List<Card> forests = lands(FOREST, 6);
        for (final Card c : forests) {
            c.setTapped(true);
        }
        endlessOne();
        assertEquals(LoopCycles.net(ai, line(FLICKER)), 5);
        assertFalse(LoopCycles.ready(ai, line(FLICKER)));
        assertNull(driver.mainPhaseAction(List.of()));
        forests.get(0).setTapped(false);
        assertTrue(LoopCycles.ready(ai, line(FLICKER)), "an Island and a Forest untapped");
        forests.get(0).setTapped(true);
        island.setTapped(true);
        floating(1, (byte) ManaAtom.BLUE, palinchron);
        assertFalse(LoopCycles.ready(ai, line(FLICKER)), "every land tapped, one blue floating");
        assertNull(LoopCycles.next(ai, line(FLICKER)), "nothing to tap, and the pool short of the flicker");
        floating(1, (byte) ManaAtom.BLUE, palinchron);
        assertTrue(LoopCycles.ready(ai, line(FLICKER)), "two blue floating");
        assertSame(LoopCycles.next(ai, line(FLICKER)), flickerOf(palinchron), "the flicker is next");

        // the Island tapped and the six Forests untapped make the flicker's two, but not its {U}
        start();
        paired();
        addCard(ISLAND, ai).setTapped(true);
        lands(FOREST, 6);
        endlessOne();
        assertEquals(LoopCycles.net(ai, line(FLICKER)), 5);
        assertFalse(LoopCycles.ready(ai, line(FLICKER)), "no blue to tap");
        assertNull(driver.mainPhaseAction(List.of()));
    }

    // With Deadeye paired and Nyxbloom out the board holds both Palinchron lines, and the flicker's 7 x 3 - 2 = 19 a
    // cycle beats the recast's 21 - 11 = 10: seven taps, then the flicker, never Palinchron's return.
    @Test
    public void withTheFlickerAndTheRecastTheFlickerRunsFirst() {
        start();
        final Card palinchron = paired();
        ComboLoopBoards.ready(addCard(NYXBLOOM, ai));
        lands(ISLAND, 7);
        endlessOne();
        assertEquals(LoopCycles.net(ai, line(FLICKER)), 19);
        assertEquals(LoopCycles.net(ai, line(RECAST)), 10);
        assertTrue(LoopCycles.ready(ai, line(RECAST)));
        final SpellAbility flicker = flickerOf(palinchron);
        final List<SpellAbility> acts = drive(8);
        assertEquals(acts.size(), 8);
        assertSame(acts.get(7), flicker);
        assertEquals(pool(), 19);
    }

    // Review Focus 3 for Palinchron: of two copies on our battlefield, the first unpaired and the second paired with
    // Deadeye, the flicker is the paired one's, read on that card object, never on the first card of the name; the
    // other copy is never moved.
    @Test
    public void theFlickerIsThePairedOneOfTwoPalinchrons() {
        start();
        final Card unpaired = ComboLoopBoards.ready(addCard(PALINCHRON, ai));
        final Card pairedOne = paired();
        assertNull(flickerOf(unpaired));
        final SpellAbility flicker = flickerOf(pairedOne);
        lands(ISLAND, 7);
        endlessOne();
        assertEquals(LoopCycles.net(ai, line(FLICKER)), 5);
        assertTrue(LoopCycles.ready(ai, line(FLICKER)));
        final List<SpellAbility> acts = drive(8);
        assertEquals(acts.size(), 8);
        assertSame(acts.get(7), flicker, "the paired copy's flicker");
        assertTrue(stillThere(unpaired), "the unpaired copy never moved");
        assertEquals(pool(), 5);
    }

    // One recast cycle through the driver's own decisions: our seven lands' mana abilities (the Forests first), then
    // Palinchron's own return, which the pool pays, then, Palinchron in our hand and every land of ours tapped, its
    // cast from our hand, the line ready there too. Palinchron comes back a new object, its untap puts our lands back,
    // and the pool is 10 up. The driver's decisions draw nothing.
    @Test
    public void theRecastTapsEveryLandThenReturnsAndCastsPalinchron() {
        start();
        final Card first = recast();
        final SpellAbility back = returnOf(first);
        final List<Card> islands = lands(ISLAND, 4);
        final List<Card> forests = lands(FOREST, 3);
        endlessOne();
        final List<SpellAbility> acts = drive(9);
        assertEquals(acts.size(), 9);
        for (int i = 0; i < 3; i++) {
            assertSame(acts.get(i), manaOf(forests.get(i)), "Forest " + i);
        }
        for (int i = 0; i < 4; i++) {
            assertSame(acts.get(3 + i), manaOf(islands.get(i)), "Island " + i);
        }
        assertSame(acts.get(7), back, "Palinchron's return");
        final SpellAbility cast = acts.get(8);
        assertTrue(cast.isSpell(), "the cast: " + cast);
        assertEquals(ComboTracker.nameOf(cast.getHostCard()), PALINCHRON);
        assertNotSame(cast.getHostCard(), first, "the card in our hand is a new object");
        final Card second = palinchron();
        assertNotNull(second, "Palinchron is back on our battlefield");
        assertNotSame(second, first);
        assertEquals(untapped(islands) + untapped(forests), 7);
        assertEquals(pool(), 10);
        assertEquals(decisionDraws, 0, "the driver's decisions drew");
    }

    // Review Focus 4 for the recast: three cycles back to back, a new Palinchron each, and the pool 30 up.
    @Test
    public void threeRecastCyclesInARow() {
        start();
        final Card first = recast();
        lands(ISLAND, 4);
        lands(FOREST, 3);
        endlessOne();
        final Set<Card> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        seen.add(first);
        int grew = 0;
        for (int i = 0; i < 3; i++) {
            grew += oneCycle();
            assertTrue(seen.add(palinchron()), "cycle " + i + " made a new Palinchron");
        }
        assertEquals(grew, 30);
    }

    // With a Palinchron of ours in our hand beside one on our battlefield, the recast casts the one in our hand once
    // every land is tapped, before it would return the other: seven taps, then that cast (21 less 7), and the other
    // copy is never moved.
    @Test
    public void aPalinchronInOurHandIsCastBeforeAnotherIsReturned() {
        start();
        final Card onBattlefield = recast();
        final Card inHand = addCardToZone(PALINCHRON, ai, ZoneType.Hand);
        lands(ISLAND, 4);
        lands(FOREST, 3);
        endlessOne();
        assertTrue(LoopCycles.ready(ai, line(RECAST)));
        final List<SpellAbility> acts = drive(8);
        assertEquals(acts.size(), 8);
        final SpellAbility cast = acts.get(7);
        assertTrue(cast.isSpell(), "a cast, not a return: " + cast);
        assertEquals(cast.getHostCard().getId(), inHand.getId(), "the one from our hand");
        assertTrue(stillThere(onBattlefield), "the other copy never moved");
        assertEquals(pool(), 14);
    }

    // The recast starts only with its pieces on our battlefield (spec 6.2): Palinchron in our hand with our lands
    // untapped is no start. In our hand with every land of ours tapped and the pool paying its {5}{U}{U}, the cycle is
    // past its return, the line is ready, and the driver's step is the cast.
    @Test
    public void noRecastStartsFromOurHand() {
        start();
        ComboLoopBoards.ready(addCard(NYXBLOOM, ai));
        final Card inHand = addCardToZone(PALINCHRON, ai, ZoneType.Hand);
        final List<Card> islands = lands(ISLAND, 2);
        final List<Card> forests = lands(FOREST, 5);
        endlessOne();
        assertEquals(LoopCycles.net(ai, line(RECAST)), 10);
        assertFalse(LoopCycles.ready(ai, line(RECAST)), "our lands untapped: no start from our hand");
        assertFalse(LoopCycles.boundary(ai, line(RECAST)), "no Palinchron on our battlefield, no boundary");
        assertNull(driver.mainPhaseAction(List.of()));
        for (final Card c : islands) {
            c.setTapped(true);
        }
        for (final Card c : forests) {
            c.setTapped(true);
        }
        assertFalse(LoopCycles.boundary(ai, line(RECAST)), "Palinchron in our hand");
        assertFalse(LoopCycles.ready(ai, line(RECAST)), "every land tapped, the pool empty");
        assertNull(LoopCycles.next(ai, line(RECAST)), "the cast unpaid");
        floating(2, (byte) ManaAtom.BLUE, islands.get(0));
        floating(5, (byte) ManaAtom.GREEN, forests.get(0));
        assertTrue(LoopCycles.ready(ai, line(RECAST)), "past the return");
        final SpellAbility next = driver.mainPhaseAction(List.of());
        assertNotNull(next);
        assertTrue(next.isSpell());
        assertSame(next.getHostCard(), inHand);
    }

    // The recast must be able to pay its return and its cast from where it stands, or it would leave Palinchron in our
    // hand: two Islands and seven Forests, seven of them tapped, an Island and a Forest untapped, make 6 of the 11
    // (the net 10 and the cycle's blue are those of the seven lands Palinchron untaps), and the line is not ready.
    @Test
    public void aRecastOurLandsCannotPayFromHereStartsNothing() {
        start();
        recast();
        final List<Card> islands = lands(ISLAND, 2);
        final List<Card> forests = lands(FOREST, 7);
        endlessOne();
        assertTrue(LoopCycles.ready(ai, line(RECAST)), "the control: every land untapped");
        islands.get(1).setTapped(true);
        for (int i = 1; i < 7; i++) {
            forests.get(i).setTapped(true);
        }
        assertEquals(LoopCycles.net(ai, line(RECAST)), 10);
        assertFalse(LoopCycles.ready(ai, line(RECAST)), "6 of the 11");
        assertNull(driver.mainPhaseAction(List.of()));

        // the two Islands tapped, the seven Forests untapped make 21, but none of the cycle's four blue
        start();
        recast();
        for (final Card c : lands(ISLAND, 2)) {
            c.setTapped(true);
        }
        lands(FOREST, 7);
        endlessOne();
        assertEquals(LoopCycles.net(ai, line(RECAST)), 10);
        assertFalse(LoopCycles.ready(ai, line(RECAST)), "no blue to tap");
        assertNull(driver.mainPhaseAction(List.of()));
    }

    // The recast runs only with its pieces but Palinchron on our battlefield: with Nyxbloom Ancient in our library and
    // Mana Reflection doubling our lands instead, the cycle would net 7 x 2 - 11 = 3, but the line is not ready.
    @Test
    public void theRecastNeedsNyxbloomOnOurBattlefield() {
        start();
        ComboLoopBoards.ready(addCard(PALINCHRON, ai));
        addCardToZone(NYXBLOOM, ai, ZoneType.Library);
        addCard("Mana Reflection", ai);
        lands(ISLAND, 7);
        endlessOne();
        assertEquals(LoopCycles.net(ai, line(RECAST)), 3);
        assertFalse(LoopCycles.ready(ai, line(RECAST)));
        assertNull(driver.mainPhaseAction(List.of()));
    }

    // R24: the recast casts Palinchron every cycle, so under the opponent's Rule of Law (one spell a turn) the line is
    // denied and no go-off starts; with the Rule gone the same board starts one in the same phase (a refused start
    // ends nothing).
    @Test
    public void noRecastUnderRuleOfLaw() {
        start();
        recast();
        lands(ISLAND, 4);
        lands(FOREST, 3);
        endlessOne();
        final Card law = addCard("Rule of Law", opp);
        assertTrue(t.view().denied(line(RECAST)));
        assertNull(driver.mainPhaseAction(List.of()));
        game.getAction().moveToGraveyard(law, null);
        game.getAction().checkStaticAbilities();
        assertFalse(t.view().denied(line(RECAST)));
        assertNotNull(driver.mainPhaseAction(List.of()), "the control: no Rule of Law");
    }

    // A boundary of the Palinchron cycles is a board on which the lands Palinchron's untap puts back (seven of our
    // lands at most, those that make blue first) are untapped, so the next step is a tap. With nine lands, five Forests
    // then four Islands, the untap puts back the Islands and the first three Forests: the board is a boundary with
    // every land untapped and with the last two Forests tapped, and none with the first Forest tapped. So the go-off's
    // first cycle is measured from its start (R21) and nets 7; the next nets 5, the line not failed. With five
    // Islands, five untapped is a boundary, four is none.
    @Test
    public void aPalinchronBoundaryHasTheLandsItsUntapPutsBackUntapped() {
        start();
        paired();
        final List<Card> forests = lands(FOREST, 5);
        lands(ISLAND, 4);
        endlessOne();
        assertTrue(LoopCycles.boundary(ai, line(FLICKER)), "every land untapped");
        forests.get(4).setTapped(true);
        forests.get(3).setTapped(true);
        assertTrue(LoopCycles.boundary(ai, line(FLICKER)), "the two Forests the untap leaves tapped");
        forests.get(0).setTapped(true);
        assertFalse(LoopCycles.boundary(ai, line(FLICKER)), "a Forest the untap puts back");
        for (final Card c : forests) {
            c.setTapped(false);
        }
        assertEquals(oneCycle(), 7, "nine taps less the flicker's two");
        assertEquals(oneCycle(), 5, "seven less two: the line runs on");
        assertEquals(oneCycle(), 5);

        start();
        paired();
        final List<Card> five = lands(ISLAND, 5);
        assertTrue(LoopCycles.boundary(ai, line(FLICKER)), "five lands, five untapped");
        five.get(0).setTapped(true);
        assertFalse(LoopCycles.boundary(ai, line(FLICKER)), "five lands, four untapped");
    }

    // A land the cycle cannot tap does not keep the go-off from its boundary: five Islands and a summoning-sick Dryad
    // Arbor, all six of which Palinchron's untap would put back, and two Counterspells in hand, a target of 2. The
    // cycle taps the five Islands and flickers; back at its start, the Arbor never tapped, the board is a boundary
    // with the pool at 3, and the stock walk's turn comes (null), where a count of lands to tap would never reach six.
    @Test
    public void aLandTheCycleCannotTapLeavesItsBoundary() {
        start();
        final Card palinchron = paired();
        final SpellAbility flicker = flickerOf(palinchron);
        lands(ISLAND, 5);
        final Card arbor = addCard("Dryad Arbor", ai);
        addCardToZone(COUNTERSPELL, ai, ZoneType.Hand);
        addCardToZone(COUNTERSPELL, ai, ZoneType.Hand);
        final List<SpellAbility> acts = drive(50);
        assertEquals(acts.size(), 6, "five taps and the flicker, then the walk's turn");
        assertSame(acts.get(5), flicker);
        assertTrue(arbor.isUntapped(), "the Arbor never tapped");
        assertTrue(LoopCycles.boundary(ai, line(FLICKER)));
        assertEquals(pool(), 3);
    }

    // A Palinchron that lost its abilities (the opponent's Turn to Frog: a 1/1 Frog until end of turn) has no return
    // and no flicker, and no cast of its card either: paired with Deadeye and beside Nyxbloom, neither of its lines
    // nets anything or runs, and nothing throws.
    @Test
    public void aPalinchronThatLostItsAbilitiesRunsNoCycle() {
        start();
        final Card palinchron = paired();
        ComboLoopBoards.ready(addCard(NYXBLOOM, ai));
        lands(ISLAND, 7);
        endlessOne();
        assertEquals(LoopCycles.net(ai, line(FLICKER)), 19, "the control");
        assertEquals(LoopCycles.net(ai, line(RECAST)), 10, "the control");
        final Card frog = createCard("Turn to Frog", opp);
        frog.setGameTimestamp(game.getNextTimestamp());
        game.getStackZone().add(frog);
        final SpellAbility spell = frog.getFirstSpellAbility();
        spell.setActivatingPlayer(opp);
        spell.getTargets().add(palinchron);
        game.getStack().add(spell);
        resolveAll();
        assertNull(flickerOf(palinchron), "no flicker");
        assertTrue(palinchron.getSpells().isEmpty(), "no cast of its card");
        assertEquals(LoopCycles.net(ai, line(FLICKER)), 0);
        assertEquals(LoopCycles.net(ai, line(RECAST)), 0);
        assertFalse(LoopCycles.ready(ai, line(FLICKER)));
        assertFalse(LoopCycles.ready(ai, line(RECAST)));
        assertNull(driver.mainPhaseAction(List.of()));
    }

    // The kinds of mana the cycle makes without limit are those of the lands Palinchron untaps, blue only when they
    // make more of it than the cycle pays: seven Islands make blue; one Island beside six Forests pays the flicker's
    // own {U} with its blue, so only green is the loop's; two Islands make both; with seven Islands and two Mountains
    // the untap puts the Islands back, not the Mountains, so no red. The recast pays four blue a cycle: two Islands,
    // tripled, make six, both kinds; one makes three, green alone.
    @Test
    public void theCycleMakesTheKindsOfTheLandsPalinchronUntaps() {
        start();
        paired();
        lands(ISLAND, 7);
        assertEquals(LoopCycles.colours(ai, line(FLICKER)), ManaAtom.BLUE);

        start();
        paired();
        lands(ISLAND, 1);
        lands(FOREST, 6);
        assertEquals(LoopCycles.colours(ai, line(FLICKER)), ManaAtom.GREEN);

        start();
        paired();
        lands(ISLAND, 2);
        lands(FOREST, 5);
        assertEquals(LoopCycles.colours(ai, line(FLICKER)), ManaAtom.BLUE | ManaAtom.GREEN);

        start();
        paired();
        lands("Mountain", 2);
        lands(ISLAND, 7);
        assertEquals(LoopCycles.colours(ai, line(FLICKER)), ManaAtom.BLUE);

        start();
        recast();
        lands(ISLAND, 2);
        lands(FOREST, 5);
        assertEquals(LoopCycles.colours(ai, line(RECAST)), ManaAtom.BLUE | ManaAtom.GREEN);

        start();
        recast();
        lands(ISLAND, 1);
        lands(FOREST, 6);
        assertEquals(LoopCycles.colours(ai, line(RECAST)), ManaAtom.GREEN);
    }

    // Palinchron's moves are the cycles' own steps, never the stock AI's spend (ChangeZoneAi makes them only to save a
    // creature from removal on the stack): the flicker on Palinchron and on Deadeye, Palinchron's return and its cast,
    // and our lands' mana abilities. So on a board with nothing else to spend on, the stock target is 0 and no go-off
    // starts; Mind Stone's draw is still a spend. An opponent's Palinchron's return is no step of ours, and a rock
    // line's steps are its rock's alone.
    @Test
    public void palinchronsMovesAreNoStockSpend() {
        start();
        final Card palinchron = paired();
        final Card deadeye = onOurBattlefield(DEADEYE);
        final Card island = lands(ISLAND, 7).get(0);
        final ComboLines.Line flicker = line(FLICKER);
        final ComboLines.Line recastLine = line(RECAST);
        assertTrue(LoopCycles.isStep(ai, flicker, flickerOf(palinchron)));
        assertTrue(LoopCycles.isStep(ai, flicker, flickerOf(deadeye)));
        assertTrue(LoopCycles.isStep(ai, flicker, returnOf(palinchron)));
        assertTrue(LoopCycles.isStep(ai, flicker, manaOf(island)));
        assertTrue(LoopCycles.isStep(ai, recastLine, returnOf(palinchron)));
        assertTrue(LoopCycles.isStep(ai, recastLine, palinchron.getFirstSpellAbility()));
        assertTrue(LoopCycles.isStep(ai, recastLine, manaOf(island)));
        assertFalse(LoopCycles.isStep(ai, line(KINNAN_BASALT), returnOf(palinchron)), "a rock line's");
        final Card theirs = addCard(PALINCHRON, opp);
        assertFalse(LoopCycles.isStep(ai, flicker, returnOf(theirs)), "an opponent's Palinchron");
        assertFalse(LoopCycles.isStep(ai, flicker, manaOf(addCard(ISLAND, opp))), "an opponent's land");
        assertFalse(LoopCycles.isStep(ai, flicker, deadeye.getFirstSpellAbility()), "Deadeye's cast");
        assertEquals(LoopPool.stockTarget(ai, flicker), 0);
        assertNull(driver.mainPhaseAction(List.of()), "nothing to spend on");
        addCard("Mind Stone", ai);
        assertEquals(LoopPool.stockTarget(ai, flicker), 1, "Mind Stone's {1}, {T}, sacrifice: draw");
        assertFalse(LoopCycles.isStep(ai, flicker, manaOf(addCard(BASALT, ai))), "a rock's mana ability");
    }

    // LoopDriver.untapChoice acts for Palinchron's entering untap during one of the driver's flicker or recast cycles
    // alone: before any go-off it is null; mid-cycle it takes our land that makes blue, then the rest of ours, never an
    // opponent's (null with none of ours left); it is null for another untap and for another of Palinchron's
    // abilities; and once the step the cycle ran in is over, null.
    @Test
    public void palinchronsUntapIsTheDriversOnlyDuringItsCycle() {
        start();
        final Card palinchron = paired();
        lands(ISLAND, 7);
        endlessOne();
        final Card theirIsland = addCard(ISLAND, opp);
        final Card ourForest = addCard(FOREST, ai);
        final Card ourIsland = addCard(ISLAND, ai);
        for (final Card c : List.of(theirIsland, ourForest, ourIsland)) {
            c.setTapped(true);
        }
        final SpellAbility etb = etbOf(palinchron);
        assertNull(driver.untapChoice(etb, List.of(theirIsland, ourForest, ourIsland)), "no go-off yet");
        assertEquals(drive(1).size(), 1, "the cycle's first tap");
        assertSame(driver.untapChoice(etb, List.of(theirIsland, ourForest, ourIsland)), ourIsland);
        assertSame(driver.untapChoice(etb, List.of(theirIsland, ourForest)), ourForest, "the rest of ours");
        assertNull(driver.untapChoice(etb, List.of(theirIsland)), "never an opponent's");
        final SpellAbility basaltUntap = untapOf(addCard(BASALT, ai));
        assertNull(driver.untapChoice(basaltUntap, List.of(ourIsland)), "another untap");
        assertNull(driver.untapChoice(returnOf(palinchron), List.of(ourIsland)), "Palinchron's return");
        game.getPhaseHandler().devModeSet(PhaseType.COMBAT_BEGIN, ai);
        assertNull(driver.untapChoice(etb, List.of(ourIsland)), "the cycle's step is over");
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, ai, 3);
        assertNull(driver.untapChoice(etb, List.of(ourIsland)), "the same step of a later turn");
    }

    // LoopDriver.partnerChoice is Deadeye Navigator's own pairing alone: Palinchron among the options while the deck
    // holds the flicker line; none for another soulbond creature's pairing, for options without Palinchron, or for a
    // token Palinchron, which a flicker would exile for ever.
    @Test
    public void deadeyesPartnerIsPalinchronOnlyForDeadeye() {
        start();
        final Card palinchron = ComboLoopBoards.ready(addCard(PALINCHRON, ai));
        final Card hullbreaker = ComboLoopBoards.ready(addCard(HULLBREAKER, ai));
        final SpellAbility bond = bondOf(ComboLoopBoards.ready(addCard(DEADEYE, ai)));
        assertSame(driver.partnerChoice(bond, List.of(hullbreaker, palinchron)), palinchron);
        assertNull(driver.partnerChoice(bond, List.of(hullbreaker)), "no Palinchron among the options");
        final SpellAbility otherBond = bondOf(addCard("Wolfir Silverheart", ai));
        assertNull(driver.partnerChoice(otherBond, List.of(hullbreaker, palinchron)), "another soulbond creature");
        final Card token = ComboLoopBoards.ready(addCard(PALINCHRON, ai));
        token.setGamePieceType(GamePieceType.TOKEN);
        assertNull(driver.partnerChoice(bond, List.of(hullbreaker, token)), "a token Palinchron");
        assertSame(driver.partnerChoice(bond, List.of(token, palinchron)), palinchron, "the card past the token");
    }

    // A token Palinchron is never moved: a flicker would exile it and a return would leave it nowhere. Paired with
    // Deadeye, it makes the flicker line net 0, not ready, and starts nothing; beside Nyxbloom, neither does the
    // recast. The same card as a card, the control, makes each line run.
    @Test
    public void aTokenPalinchronIsNoCyclePiece() {
        start();
        final Card flickered = paired();
        flickered.setGamePieceType(GamePieceType.TOKEN);
        lands(ISLAND, 7);
        endlessOne();
        assertEquals(LoopCycles.net(ai, line(FLICKER)), 0);
        assertFalse(LoopCycles.ready(ai, line(FLICKER)));
        assertNull(driver.mainPhaseAction(List.of()));
        flickered.setGamePieceType(GamePieceType.CARD);
        assertEquals(LoopCycles.net(ai, line(FLICKER)), 5, "the control: a card");
        assertTrue(LoopCycles.ready(ai, line(FLICKER)));

        start();
        final Card recast = recast();
        recast.setGamePieceType(GamePieceType.TOKEN);
        lands(ISLAND, 7);
        endlessOne();
        assertEquals(LoopCycles.net(ai, line(RECAST)), 0);
        assertFalse(LoopCycles.ready(ai, line(RECAST)));
        assertNull(driver.mainPhaseAction(List.of()));
        recast.setGamePieceType(GamePieceType.CARD);
        assertEquals(LoopCycles.net(ai, line(RECAST)), 10, "the control: a card");
        assertTrue(LoopCycles.ready(ai, line(RECAST)));
    }

    // UntapAi and BondAi ask the driver for every AI deck's choice: neither draws a random number, inert (no cycle
    // runs; another soulbond creature's pairing) or live (mid-cycle; Deadeye's pairing with Palinchron).
    @Test
    public void theChoicesDrawNothing() {
        start();
        final Card palinchron = paired();
        lands(ISLAND, 7);
        endlessOne();
        final Card tapped = addCard(ISLAND, ai);
        tapped.setTapped(true);
        final Card hullbreaker = ComboLoopBoards.ready(addCard(HULLBREAKER, ai));
        final SpellAbility bond = bondOf(onOurBattlefield(DEADEYE));
        long before = MyRandom.callCount();
        assertNull(driver.untapChoice(etbOf(palinchron), List.of(tapped)), "inert");
        assertNull(driver.partnerChoice(bondOf(addCard("Wolfir Silverheart", ai)), List.of(hullbreaker)), "inert");
        assertEquals(MyRandom.callCount() - before, 0, "the inert choices drew");
        assertEquals(drive(1).size(), 1);
        before = MyRandom.callCount();
        assertSame(driver.untapChoice(etbOf(palinchron), List.of(tapped)), tapped, "live");
        assertSame(driver.partnerChoice(bond, List.of(hullbreaker, palinchron)), palinchron, "live");
        assertEquals(MyRandom.callCount() - before, 0, "the live choices drew");
    }

    // The hooks ask LoopDriver.of, null while another player controls the AI: Deadeye Navigator entering for the
    // controlled AI takes the stock pairing (Hullbreaker Horror) and throws nothing, and so does Palinchron's untap.
    @Test
    public void aControlledAisChoicesAreTheStockOnes() {
        start();
        final Card palinchron = ComboLoopBoards.ready(addCard(PALINCHRON, ai));
        final Card hullbreaker = ComboLoopBoards.ready(addCard(HULLBREAKER, ai));
        final List<Card> islands = lands(ISLAND, 2);
        for (final Card c : islands) {
            c.setTapped(true);
        }
        final long timestamp = game.getNextTimestamp();
        ai.addController(timestamp, opp);
        try {
            assertTrue(ai.isControlled());
            assertNull(LoopDriver.of(ai));
            assertSame(entersOurs(DEADEYE).getPairedWith(), hullbreaker, "the stock pairing");
            assertNotSame(entersOurs(PALINCHRON), palinchron, "a second Palinchron entered, its untap the stock one");
            assertEquals(untapped(islands), 2, "the stock untap took our lands");
        } finally {
            ai.removeController(timestamp);
        }
        assertFalse(ai.isControlled());
    }
}
