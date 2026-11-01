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
import forge.card.MagicColor;
import forge.card.mana.ManaAtom;
import forge.card.mana.ManaCost;
import forge.game.Game;
import forge.game.GameLogEntry;
import forge.game.ability.AbilityKey;
import forge.game.ability.effects.CharmEffect;
import forge.game.card.Card;
import forge.game.mana.Mana;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.spellability.AbilitySub;
import forge.game.spellability.SpellAbility;
import forge.game.trigger.Trigger;
import forge.game.trigger.TriggerType;
import forge.game.zone.MagicStack;
import forge.game.zone.ZoneType;
import forge.util.MyRandom;

/**
 * Hullbreaker Horror's cast and bounce (spec 2026-10-29-combo-aware-ai-loops-design.md sections 6.1, 6.3 and 6.8; the
 * plan's Task B8, R19): with one piece of a row on our battlefield and the other in our hand, the cycle taps the one
 * there when it makes mana, casts the other, whose cast trigger the driver chooses to return the first to our hand,
 * taps the new one and casts the first again. Its net is r + K + m + K x [m > 0] - cost(A) - cost(B) (I6 section 2.3),
 * each tap tripled per Nyxbloom Ancient. Each cycle makes both pieces new objects, read afresh at every decision. With
 * both of x-14's pieces in our hand the cycle casts the first, whose trigger chooses nothing. Mox Amber's colour is set
 * before each tap; Talisman of Curiosity taps its {C}; Mox Opal taps only with metalcraft. While the cycle runs, our
 * hand cast of its piece in hand waits for the cycle, so the stock walk, playing at the cycle's boundary, never casts
 * it. Unless a test says otherwise the board is Kinnan's list registered (every piece of the 19 loop rows, the two
 * sinks, lands, Endless One and Counterspell), the tracker reading the shipped lines, both seats on the Default
 * profile, the AI in its own Main 1 with no AI deadline, Kinnan and Hullbreaker ready on our battlefield, and the
 * opponent at 20 with nothing. {@link #oneCycle} runs the whole game loop; {@link #drive} plays the driver's own
 * decisions through the AI's play path, every trigger resolved.
 */
public class ComboLoopBounceAiTest extends AITest {
    private static final String KINNAN = "Kinnan, Bonder Prodigy";
    private static final String HULLBREAKER = "Hullbreaker Horror";
    private static final String SOL_RING = "Sol Ring";
    private static final String MANA_VAULT = "Mana Vault";
    private static final String TOP = "Sensei's Divining Top";
    private static final String AMBER = "Mox Amber";
    private static final String OPAL = "Mox Opal";
    private static final String GRIM = "Grim Monolith";
    private static final String ARCANE_SIGNET = "Arcane Signet";
    private static final String SIMIC_SIGNET = "Simic Signet";
    private static final String TALISMAN = "Talisman of Curiosity";
    private static final String SCROLL_RACK = "Scroll Rack";
    private static final String NYXBLOOM = "Nyxbloom Ancient";
    private static final String BASALT = "Basalt Monolith";
    private static final String ENDLESS_ONE = "Endless One";
    private static final String COUNTERSPELL = "Counterspell";
    private static final String ALPHA_MYR = "Alpha Myr";
    private static final String ORNITHOPTER = "Ornithopter";
    private static final String ISLAND = "Island";
    /** Hullbreaker, Sol Ring and Mana Vault. */
    private static final String RING_VAULT = "x-2";
    /** Hullbreaker, Sol Ring, Sensei's Divining Top and Kinnan. */
    private static final String RING_TOP = "x-4";
    /** Hullbreaker, Sol Ring and Mox Amber. */
    private static final String RING_AMBER = "x-5";
    /** Hullbreaker, Mana Vault and Simic Signet. */
    private static final String VAULT_SIMIC = "x-10";
    /** Hullbreaker, Mana Vault and Talisman of Curiosity. */
    private static final String VAULT_TALISMAN = "x-11";
    /** Hullbreaker, Mana Vault and Mox Amber. */
    private static final String VAULT_AMBER = "x-13";
    /** Hullbreaker, Mox Amber, Mox Opal and Kinnan. */
    private static final String AMBER_OPAL = "x-14";
    /** The rock untap of Kinnan and Basalt Monolith. */
    private static final String KINNAN_BASALT = "129-4131";
    /** The game-loop steps a stepping test may take for one cycle: two taps, four stack items, and slack. */
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
        names.addAll(List.of(LoopSinks.BALLISTA, "Thrasios, Triton Hero", "Forest", ISLAND, ENDLESS_ONE, COUNTERSPELL));
        return List.copyOf(names);
    }

    /** A two-player game whose AI (player 1, in its own Main 1, no AI deadline) has Kinnan's list registered, both
     *  seats on the Default profile; nothing placed yet. */
    private void start() {
        startWith(KINNAN, KINNANS_LIST.toArray(new String[0]));
    }

    /** The same game with {@code main} and {@code commander} (none when null) as the AI's registered deck. */
    private void startWith(final String commander, final String... main) {
        game = initAndCreateGame();
        ComboLoopBoards.noDeadline(game);
        ai = game.getPlayers().get(1);
        opp = game.getPlayers().get(0);
        ((LobbyPlayerAi) ai.getLobbyPlayer()).setAiProfile("Default");
        ((LobbyPlayerAi) opp.getLobbyPlayer()).setAiProfile("Default");
        ComboLoopBoards.deck(ai, commander, main);
        t = ComboLoopBoards.aiOf(ai).getComboTracker();
        driver = ComboLoopBoards.aiOf(ai).getLoopDriver();
        decisionDraws = 0;
    }

    /** Kinnan, Bonder Prodigy and Hullbreaker Horror, ready on our battlefield. Returns Hullbreaker. */
    private Card bouncer() {
        ComboLoopBoards.ready(addCard(KINNAN, ai));
        return ComboLoopBoards.ready(addCard(HULLBREAKER, ai));
    }

    /** Our card {@code name}, put in our hand. */
    private Card hand(final String name) {
        return addCardToZone(name, ai, ZoneType.Hand);
    }

    /** Endless One in our hand: an X spell the pool pays, so the stock AI's target is 200. */
    private Card endlessOne() {
        return hand(ENDLESS_ONE);
    }

    /** Two Counterspells in our hand and two untapped Islands of ours: a target of 2 the stock AI never spends.
     *  Returns one Counterspell. */
    private Card counterspells() {
        hand(COUNTERSPELL);
        final Card one = hand(COUNTERSPELL);
        addCard(ISLAND, ai);
        addCard(ISLAND, ai);
        return one;
    }

    private int pool() {
        return ComboLoopBoards.pool(ai);
    }

    /** {@code n} of our mana in our pool, of that kind. */
    private void floating(final int n, final byte kind, final Card source) {
        for (int i = 0; i < n; i++) {
            ai.getManaPool().addMana(new Mana(kind, source, null, ai));
        }
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

    /** Our first card named {@code name} on our battlefield (by ComboTracker.nameOf), or null. */
    private Card onOurBattlefield(final String name) {
        return first(name, ZoneType.Battlefield);
    }

    /** Our first card named {@code name} in our hand, or null. */
    private Card inOurHand(final String name) {
        return first(name, ZoneType.Hand);
    }

    private Card first(final String name, final ZoneType zone) {
        for (final Card c : ai.getCardsIn(zone)) {
            if (name.equals(ComboTracker.nameOf(c))) {
                return c;
            }
        }
        return null;
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

    /** The card's first mana ability. */
    private static SpellAbility manaOf(final Card c) {
        assertFalse(c.getManaAbilities().isEmpty(), c + " has no mana ability");
        return c.getManaAbilities().get(0);
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

    /** Each action as "tap NAME" for a mana ability, "cast NAME" for a spell, else "API NAME", NAME its host's. */
    private static List<String> steps(final List<SpellAbility> acts) {
        final List<String> out = new ArrayList<>();
        for (final SpellAbility sa : acts) {
            final String name = ComboTracker.nameOf(sa.getHostCard());
            if (sa.isManaAbility()) {
                out.add("tap " + name);
            } else if (sa.isSpell()) {
                out.add("cast " + name);
            } else {
                out.add(sa.getApi() + " " + name);
            }
        }
        return out;
    }

    /** The deck lines' ids, in order. */
    private static List<String> ids(final List<ComboLines.Line> lines) {
        final List<String> out = new ArrayList<>();
        for (final ComboLines.Line l : lines) {
            out.add(l.variant().id());
        }
        return out;
    }

    /** A copy of our Hullbreaker Horror's cast trigger's charm, as the engine makes one for each cast: its triggering
     *  card {@code spell}, us its activator. */
    private SpellAbility charmFor(final Card hullbreaker, final Card spell) {
        return charmFor(hullbreaker, hullbreaker, spell);
    }

    /** The same charm with {@code host} as its host. */
    private SpellAbility charmFor(final Card hullbreaker, final Card host, final Card spell) {
        for (final Trigger tr : hullbreaker.getTriggers()) {
            if (tr.getMode() == TriggerType.SpellCast) {
                final SpellAbility charm = tr.ensureAbility().copy(host, ai, false);
                charm.setTriggeringObject(AbilityKey.Card, spell);
                return charm;
            }
        }
        throw new AssertionError(hullbreaker + " has no cast trigger");
    }

    /** Whether the charm's mode is the bounce: a target on the battlefield, returned to its owner's hand. */
    private static boolean isBounce(final AbilitySub mode) {
        return mode.usesTargeting() && mode.getTargetRestrictions().getZone().contains(ZoneType.Battlefield)
                && "Hand".equals(mode.getParam("Destination"));
    }

    /** Steps the whole game loop through one cycle of a cast and bounce line whose first piece is {@code first}: until
     *  our card of it on our battlefield is a new object, untapped, with a card of {@code second} in our hand, and
     *  every stack item and trigger has resolved; returns what the pool gained. */
    private int oneCycle(final String first, final String second) {
        final int from = pool();
        final Card before = onOurBattlefield(first);
        assertNotNull(before, first + " on our battlefield");
        ComboLoopBoards.stepUntil(game, g -> {
            final Card now = onOurBattlefield(first);
            return now != null && now != before && now.isUntapped() && inOurHand(second) != null
                    && g.getStack().isEmpty() && !g.getStack().hasSimultaneousStackEntries();
        }, MAX_STEPS);
        final Card after = onOurBattlefield(first);
        assertNotNull(after, first + " came back");
        assertNotSame(after, before, "one cycle made " + first + " a new object");
        assertTrue(after.isUntapped(), first + " back untapped");
        assertNotNull(inOurHand(second), second + " back in our hand");
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
     *  every trigger then resolved (Hullbreaker's chosen by the AI's own charm choice), until it returns null or
     *  {@code max} actions; returns them in order. Before each decision the state-based actions are checked, as the
     *  game loop checks them before every priority. The random draws made inside the driver's decisions are added to
     *  {@link #decisionDraws}. */
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

    // ---- the plan's tests ----

    // Row x-2: Sol Ring on ours, Mana Vault in hand. One cycle taps Sol Ring (2 and Kinnan's 1), casts Mana Vault (1),
    // whose trigger returns Sol Ring to our hand, taps Mana Vault (3 and 1) and casts Sol Ring again (1), whose trigger
    // returns Mana Vault: the pool grows by 5, the net the line reads, and the pieces are back where they started.
    @Test
    public void oneBounceCycleWithSolRingAndManaVaultNetsFive() {
        start();
        bouncer();
        addCard(SOL_RING, ai);
        hand(MANA_VAULT);
        endlessOne();
        assertEquals(LoopCycles.net(ai, line(RING_VAULT)), 5);
        assertTrue(LoopCycles.ready(ai, line(RING_VAULT)));
        assertEquals(oneCycle(SOL_RING, MANA_VAULT), 5);
    }

    // As Mana Vault is cast, Hullbreaker Horror's trigger is chosen for the cycle: its one chosen mode is the bounce of
    // a nonland permanent (its target on the battlefield), aimed at our Sol Ring, which it returns to our hand while
    // Mana Vault resolves onto our battlefield.
    @Test
    public void hullbreakerBouncesOurOtherPiece() {
        start();
        final Card hullbreaker = bouncer();
        final Card ring = addCard(SOL_RING, ai);
        hand(MANA_VAULT);
        endlessOne();
        assertEquals(steps(drive(1)), List.of("tap Sol Ring"));
        final SpellAbility cast = driver.mainPhaseAction(List.of());
        assertNotNull(cast, "the cycle's cast");
        assertEquals(steps(List.of(cast)), List.of("cast Mana Vault"));
        ai.getController().playChosenSpellAbility(cast);
        game.getStack().addAllTriggeredAbilitiesToStack();
        final SpellAbility trigger = game.getStack().peekAbility();
        assertSame(trigger.getHostCard(), hullbreaker, "Hullbreaker's trigger, above the cast");
        final AbilitySub chosen = trigger.getSubAbility();
        assertNotNull(chosen, "a mode chosen");
        assertNull(chosen.getSubAbility(), "one mode");
        assertTrue(isBounce(chosen), "the bounce of a nonland permanent");
        assertSame(chosen.getTargets().getFirstTargetedCard(), ring, "our Sol Ring");
        resolveAll();
        assertFalse(stillThere(ring), "Sol Ring left our battlefield");
        assertNotNull(inOurHand(SOL_RING), "Sol Ring returned to our hand");
        assertNotNull(onOurBattlefield(MANA_VAULT), "Mana Vault resolved");
    }

    // Row x-4: Sol Ring on ours, Sensei's Divining Top in hand, Kinnan's bonus the whole net. One cycle taps Sol Ring
    // (3), casts Top (1), which returns Sol Ring, and casts Sol Ring (1), which returns Top: the pool grows by 1. Top
    // makes no mana, so it is never tapped: it is returned untapped.
    @Test
    public void oneBounceCycleWithSolRingAndTopNetsOne() {
        start();
        bouncer();
        addCard(SOL_RING, ai);
        hand(TOP);
        endlessOne();
        assertEquals(LoopCycles.net(ai, line(RING_TOP)), 1);
        assertTrue(LoopCycles.ready(ai, line(RING_TOP)));
        assertEquals(oneCycle(SOL_RING, TOP), 1);

        start();
        bouncer();
        addCard(SOL_RING, ai);
        hand(TOP);
        endlessOne();
        final List<SpellAbility> acts = drive(2);
        assertEquals(steps(acts), List.of("tap Sol Ring", "cast Sensei's Divining Top"));
        final Card top = onOurBattlefield(TOP);
        assertNotNull(top, "Top resolved");
        assertTrue(top.isUntapped());
        acts.addAll(drive(1));
        assertEquals(steps(acts), List.of("tap Sol Ring", "cast Sensei's Divining Top", "cast Sol Ring"), "no Top tap");
        assertFalse(stillThere(top), "the untapped Top was returned");
        assertNotNull(inOurHand(TOP));
        assertEquals(pool(), 1);
    }

    // Row x-14 without metalcraft: Mox Amber on ours, Mox Opal in hand. One cycle taps Amber (one blue, Kinnan being a
    // legend of ours, and Kinnan's one), casts Opal, which returns Amber, and casts Amber, which returns Opal: Opal,
    // the one artifact of ours then, cannot tap. The pool grows by 2.
    @Test
    public void aMoxOpalCycleRuns() {
        start();
        bouncer();
        addCard(AMBER, ai);
        hand(OPAL);
        endlessOne();
        assertEquals(LoopCycles.net(ai, line(AMBER_OPAL)), 2);
        assertTrue(LoopCycles.ready(ai, line(AMBER_OPAL)));
        assertEquals(oneCycle(AMBER, OPAL), 2);
    }

    // Review Focus 4: each cycle makes both pieces new objects, each found again at every decision: three cycles of row
    // x-2 run back to back, and the pool grows by 15.
    @Test
    public void threeBounceCyclesInARow() {
        start();
        bouncer();
        final Card ring = addCard(SOL_RING, ai);
        final Card vault = hand(MANA_VAULT);
        endlessOne();
        final Set<Card> rings = Collections.newSetFromMap(new IdentityHashMap<>());
        final Set<Card> vaults = Collections.newSetFromMap(new IdentityHashMap<>());
        rings.add(ring);
        vaults.add(vault);
        int grew = 0;
        for (int i = 0; i < 3; i++) {
            grew += oneCycle(SOL_RING, MANA_VAULT);
            assertTrue(rings.add(onOurBattlefield(SOL_RING)), "cycle " + i + " made a new Sol Ring");
            assertTrue(vaults.add(inOurHand(MANA_VAULT)), "cycle " + i + " made a new Mana Vault");
        }
        assertEquals(grew, 15);
        assertEquals(pool(), 15);
    }

    // Row x-14 from its start, Mox Amber and Mox Opal both in our hand, Kinnan (green and blue) out: every tap of Amber
    // makes blue, the first in WUBRG order of the colours of our legends, since the next cast, Opal's, needs none, and
    // Kinnan's bonus copies it, so the pool holds blue alone, though a payment had left black as Amber's choice: never
    // a colour no legend of ours has. A cast that needs green would have green; one that needs only a colour no legend
    // of ours has gets the blue.
    @Test
    public void moxAmberTapsTheColourTheNextCastNeeds() {
        start();
        bouncer();
        hand(AMBER);
        hand(OPAL);
        endlessOne();
        assertTrue(LoopCycles.ready(ai, line(AMBER_OPAL)), "both in our hand: the cycle's start");
        assertEquals(steps(drive(1)), List.of("cast Mox Amber"));
        final Card amber = onOurBattlefield(AMBER);
        assertNotNull(amber, "Amber resolved");
        final SpellAbility reflect = manaOf(amber);
        reflect.getManaPart().setExpressChoice("B");
        final List<SpellAbility> acts = drive(5);
        assertEquals(steps(acts), List.of("tap Mox Amber", "cast Mox Opal", "cast Mox Amber", "tap Mox Amber",
                "cast Mox Opal"));
        assertEquals(pool(), 4);
        assertEquals(ai.getManaPool().getAmountOfColor(MagicColor.BLUE), 4, "blue alone");
        assertEquals(LoopCycles.reflectedColour(ai, reflect, new ManaCost("G")), MagicColor.GREEN,
                "a cast that needs green");
        assertEquals(LoopCycles.reflectedColour(ai, reflect, new ManaCost("B")), MagicColor.BLUE,
                "a cast that needs a colour no legend of ours has");
        assertEquals(LoopCycles.reflectedColour(ai, reflect, ManaCost.ZERO), MagicColor.BLUE, "a cast that needs none");
    }

    // A deck without Kinnan holding Hullbreaker Horror, Mox Amber and Mox Opal: x-14 names Kinnan, whose being a legend
    // of ours is what lets Amber make mana, so the deck holds no loop line, and with the three on the board the driver
    // does nothing. The same deck with Kinnan as its commander holds x-14.
    @Test
    public void x14IsNotHeldWithoutKinnan() {
        startWith(null, HULLBREAKER, AMBER, OPAL, ENDLESS_ONE, ISLAND);
        assertFalse(ids(t.deckLines()).contains(AMBER_OPAL));
        for (final ComboLines.Line l : t.deckLines()) {
            assertFalse(l.isLoop(), l.variant().id());
        }
        ComboLoopBoards.ready(addCard(HULLBREAKER, ai));
        addCard(AMBER, ai);
        hand(OPAL);
        endlessOne();
        assertNull(driver.mainPhaseAction(List.of()));

        startWith(KINNAN, HULLBREAKER, AMBER, OPAL, ENDLESS_ONE, ISLAND);
        assertTrue(ids(t.deckLines()).contains(AMBER_OPAL), "the control: with Kinnan");
    }

    // R19: our own Hullbreaker's trigger from a spell we cast with no cycle running (an opponent's spell never triggers
    // it) is the stock one: the driver's charm choice is null for it, and the stock choice, which returns an opponent's
    // permanent and never ours, leaves our Sol Ring on our battlefield beside the Mana Vault we cast.
    @Test
    public void ourTriggerOutsideACycleIsStock() {
        start();
        final Card hullbreaker = bouncer();
        final Card ring = addCard(SOL_RING, ai);
        final Card vault = hand(MANA_VAULT);
        final Card bears = createCard("Grizzly Bears", opp);
        game.getStackZone().add(bears);
        final SpellAbility theirs = bears.getFirstSpellAbility();
        theirs.setActivatingPlayer(opp);
        game.getStack().add(theirs);
        game.getTriggerHandler().runWaitingTriggers();
        assertFalse(game.getStack().hasSimultaneousStackEntries(), "an opponent's spell triggers nothing of ours");
        resolveAll();
        final SpellAbility charm = charmFor(hullbreaker, vault);
        assertNull(driver.charmModes(charm, CharmEffect.makePossibleOptions(charm)), "no cycle runs");
        final SpellAbility cast = vault.getFirstSpellAbility();
        cast.setActivatingPlayer(ai);
        ai.getController().playChosenSpellAbility(cast);
        assertTrue(game.getStack().hasSimultaneousStackEntries(), "our cast triggers Hullbreaker");
        resolveAll();
        assertTrue(stillThere(ring), "the stock choice never returns our Sol Ring");
        assertNotNull(onOurBattlefield(MANA_VAULT), "Mana Vault resolved");
    }

    // ---- beyond the plan's tests ----

    // One cycle and a half of row x-2 through the driver's own decisions, one action a decision: Sol Ring's tap, Mana
    // Vault's cast, Mana Vault's tap, Sol Ring's cast, then the new Sol Ring's tap and the new Mana Vault's cast: each
    // a new object's ability, read afresh. The driver's decisions draw nothing.
    @Test
    public void theBounceTapsThenCastsEachPieceOneADecision() {
        start();
        bouncer();
        final Card ring = addCard(SOL_RING, ai);
        hand(MANA_VAULT);
        endlessOne();
        final List<SpellAbility> acts = drive(6);
        assertEquals(steps(acts), List.of("tap Sol Ring", "cast Mana Vault", "tap Mana Vault", "cast Sol Ring",
                "tap Sol Ring", "cast Mana Vault"));
        assertSame(acts.get(0), manaOf(ring), "the first Sol Ring's own ability");
        assertNotSame(acts.get(4), acts.get(0), "the new Sol Ring's");
        assertEquals(pool(), 5 + 2, "a cycle, then Sol Ring's 3 less Mana Vault's 1");
        assertEquals(decisionDraws, 0, "the driver's decisions drew");
    }

    // The board with Sol Ring untapped and Mana Vault in our hand is the boundary, the go-off's start: two
    // Counterspells in hand set a target of 2, so one cycle builds 5 and the walk's turn comes (null) at the boundary.
    // The walk's spend there, then a fresh refill of one cycle.
    @Test
    public void theBounceHandsThePoolToTheStockWalkAtItsBoundary() {
        start();
        bouncer();
        addCard(SOL_RING, ai);
        hand(MANA_VAULT);
        final Card counterspell = counterspells();
        assertTrue(LoopCycles.boundary(ai, line(RING_VAULT)), "Sol Ring untapped, Mana Vault in our hand");
        assertEquals(LoopPool.stockTarget(ai, line(RING_VAULT)), 2);
        assertEquals(drive(20).size(), 4, "one cycle, then the walk's turn");
        assertEquals(pool(), 5);
        assertTrue(LoopCycles.boundary(ai, line(RING_VAULT)));
        driver.noteStockPlay(counterspell.getFirstSpellAbility());
        ai.getManaPool().clearPool(false);
        assertEquals(drive(20).size(), 4, "refilled with one cycle");
    }

    // R19: with both of x-14's pieces in our hand the line is ready, though no boundary; the cycle casts the first,
    // Mox Amber, whose trigger chooses nothing (it is never put on the stack, and nothing is returned), and then taps
    // it. Row x-2 from both in our hand, with an Island to pay for it, casts Sol Ring first; with no mana for it, it is
    // not ready.
    @Test
    public void bothPiecesInOurHandCastTheFirstWhoseTriggerChoosesNothing() {
        start();
        final Card hullbreaker = bouncer();
        hand(AMBER);
        hand(OPAL);
        endlessOne();
        final ComboLines.Line both = line(AMBER_OPAL);
        assertTrue(LoopCycles.ready(ai, both));
        assertFalse(LoopCycles.boundary(ai, both));
        final SpellAbility first = driver.mainPhaseAction(List.of());
        assertNotNull(first);
        assertEquals(steps(List.of(first)), List.of("cast Mox Amber"));
        ai.getController().playChosenSpellAbility(first);
        game.getStack().addAllTriggeredAbilitiesToStack();
        assertEquals(game.getStack().size(), 1, "the trigger chose nothing: only Amber's cast is on the stack");
        resolveAll();
        assertNotNull(onOurBattlefield(AMBER));
        assertNotNull(inOurHand(OPAL));
        assertTrue(stillThere(hullbreaker), "nothing returned");
        assertNotNull(onOurBattlefield(KINNAN), "nothing returned");
        assertTrue(LoopCycles.boundary(ai, both), "Amber untapped, Opal in our hand");
        final SpellAbility tap = driver.mainPhaseAction(List.of());
        assertSame(tap, manaOf(onOurBattlefield(AMBER)), "Amber's tap");

        start();
        bouncer();
        hand(SOL_RING);
        hand(MANA_VAULT);
        endlessOne();
        assertFalse(LoopCycles.ready(ai, line(RING_VAULT)), "no mana for Sol Ring");
        assertNull(driver.mainPhaseAction(List.of()));
        addCard(ISLAND, ai);
        assertTrue(LoopCycles.ready(ai, line(RING_VAULT)), "an Island pays it");
        assertEquals(steps(drive(1)), List.of("cast Sol Ring"));
    }

    // The cycle runs only with its pieces where it needs them: Hullbreaker Horror on our battlefield, and Kinnan where
    // the row names him (x-14 with another legend of ours, Isamaru, whose white lets Amber make mana: it nets 1 but is
    // not ready without Kinnan); one piece on our battlefield and the other in our hand, or both in our hand, never
    // both on our battlefield; no piece gone; and a net above 0.
    @Test
    public void aBounceNeedsItsPiecesWhereItsCycleNeedsThem() {
        start();
        ComboLoopBoards.ready(addCard(KINNAN, ai));
        addCard(SOL_RING, ai);
        hand(MANA_VAULT);
        endlessOne();
        assertEquals(LoopCycles.net(ai, line(RING_VAULT)), 5);
        assertFalse(LoopCycles.ready(ai, line(RING_VAULT)), "no Hullbreaker");
        assertNull(driver.mainPhaseAction(List.of()));
        ComboLoopBoards.ready(addCard(HULLBREAKER, ai));
        assertTrue(LoopCycles.ready(ai, line(RING_VAULT)), "the control: Hullbreaker");

        start();
        ComboLoopBoards.ready(addCard(HULLBREAKER, ai));
        ComboLoopBoards.ready(addCard("Isamaru, Hound of Konda", ai));
        addCard(AMBER, ai);
        hand(OPAL);
        endlessOne();
        assertEquals(LoopCycles.net(ai, line(AMBER_OPAL)), 1, "Amber's white");
        assertFalse(LoopCycles.ready(ai, line(AMBER_OPAL)), "no Kinnan");
        ComboLoopBoards.ready(addCard(KINNAN, ai));
        assertTrue(LoopCycles.ready(ai, line(AMBER_OPAL)), "the control: Kinnan");

        start();
        bouncer();
        addCard(SOL_RING, ai);
        addCard(MANA_VAULT, ai);
        endlessOne();
        assertFalse(LoopCycles.ready(ai, line(RING_VAULT)), "both on our battlefield");
        assertNull(LoopCycles.next(ai, line(RING_VAULT)), "no step");

        start();
        bouncer();
        addCard(SOL_RING, ai);
        endlessOne();
        assertEquals(LoopCycles.net(ai, line(RING_VAULT)), 0, "Mana Vault gone");
        assertFalse(LoopCycles.ready(ai, line(RING_VAULT)));

        // every piece in place, but a net of 0: row x-9 with no Kinnan, Arcane Signet making nothing in this
        // Constructed game (no commander), Mana Vault's 3 less the two casts
        start();
        ComboLoopBoards.ready(addCard(HULLBREAKER, ai));
        addCard(MANA_VAULT, ai);
        hand(ARCANE_SIGNET);
        endlessOne();
        assertEquals(LoopCycles.net(ai, line("x-9")), 0);
        assertFalse(LoopCycles.ready(ai, line("x-9")), "a net of 0");
        assertNull(driver.mainPhaseAction(List.of()));
    }

    // The cycle must pay its next cast from where it stands: Sensei's Divining Top on ours and Sol Ring in our hand,
    // with nothing floating and no land, is no start, one colourless floating is; Sol Ring tapped with Top in our hand
    // likewise, until an Island pays Top; Sol Ring untapped pays Top itself. Simic Signet's tap costs {1}: untapped on
    // ours with Mana Vault in our hand and nothing to pay that {1}, row x-10 is no start; one floating pays it.
    @Test
    public void aBounceItsPoolAndLandsCannotPayStartsNothing() {
        start();
        final Card hullbreaker = bouncer();
        addCard(TOP, ai);
        hand(SOL_RING);
        endlessOne();
        assertEquals(LoopCycles.net(ai, line(RING_TOP)), 1);
        assertFalse(LoopCycles.ready(ai, line(RING_TOP)), "nothing pays Sol Ring");
        assertNull(driver.mainPhaseAction(List.of()));
        floating(1, (byte) ManaAtom.COLORLESS, hullbreaker);
        assertTrue(LoopCycles.ready(ai, line(RING_TOP)), "one floating");

        start();
        bouncer();
        addCard(SOL_RING, ai).setTapped(true);
        hand(TOP);
        endlessOne();
        assertFalse(LoopCycles.ready(ai, line(RING_TOP)), "Sol Ring tapped, nothing pays Top");
        addCard(ISLAND, ai);
        assertTrue(LoopCycles.ready(ai, line(RING_TOP)), "an Island pays Top");

        start();
        bouncer();
        addCard(SOL_RING, ai);
        hand(TOP);
        endlessOne();
        assertTrue(LoopCycles.ready(ai, line(RING_TOP)), "Sol Ring's own tap pays Top");

        start();
        final Card hb = bouncer();
        addCard(SIMIC_SIGNET, ai);
        hand(MANA_VAULT);
        endlessOne();
        assertEquals(LoopCycles.net(ai, line(VAULT_SIMIC)), 3);
        assertFalse(LoopCycles.ready(ai, line(VAULT_SIMIC)), "nothing pays the Signet's {1}");
        floating(1, (byte) ManaAtom.COLORLESS, hb);
        assertTrue(LoopCycles.ready(ai, line(VAULT_SIMIC)), "one floating pays it");
    }

    // A cast and bounce line's boundary, where a refill may end and the stock AI play, comes once a cycle: its first
    // piece untapped on our battlefield and its second in our hand. Not with the first tapped, mid-cycle (the second on
    // our battlefield, the first in our hand), with both in our hand, nor with both on our battlefield.
    @Test
    public void theBounceBoundaryIsItsFirstPieceUntappedWithTheSecondInHand() {
        start();
        bouncer();
        final Card ring = addCard(SOL_RING, ai);
        hand(MANA_VAULT);
        final ComboLines.Line l = line(RING_VAULT);
        assertTrue(LoopCycles.boundary(ai, l));
        ring.setTapped(true);
        assertFalse(LoopCycles.boundary(ai, l), "Sol Ring tapped");

        start();
        bouncer();
        addCard(MANA_VAULT, ai);
        hand(SOL_RING);
        assertFalse(LoopCycles.boundary(ai, line(RING_VAULT)), "mid-cycle");

        start();
        bouncer();
        hand(SOL_RING);
        hand(MANA_VAULT);
        assertFalse(LoopCycles.boundary(ai, line(RING_VAULT)), "both in our hand");

        start();
        bouncer();
        addCard(SOL_RING, ai);
        addCard(MANA_VAULT, ai);
        assertFalse(LoopCycles.boundary(ai, line(RING_VAULT)), "both on our battlefield");

        start();
        bouncer();
        addCard(AMBER, ai);
        hand(OPAL);
        assertTrue(LoopCycles.boundary(ai, line(AMBER_OPAL)));

        start();
        bouncer();
        addCard(OPAL, ai);
        hand(AMBER);
        assertFalse(LoopCycles.boundary(ai, line(AMBER_OPAL)), "Opal on ours, Amber in our hand: mid-cycle");
    }

    // The net of each fill in Kinnan's list (I6 section 2.3), with the rock on our battlefield and the fill in our
    // hand: Mox Opal without metalcraft and Sensei's Divining Top and Scroll Rack make nothing; Simic Signet's tap pays
    // its own {1}; Talisman of Curiosity taps its {C}; Arcane Signet makes nothing in this Constructed game, which has
    // no commander. Without Kinnan, x-2 nets 3 and x-5 nets 1 (Amber, with no legend of ours, makes nothing); beside
    // Nyxbloom Ancient, x-2's taps are tripled (Kinnan's bonus is not): 15.
    @Test
    public void theNetsOfTheBounceRows() {
        final String[][] rows = {
            {"x-3", SOL_RING, OPAL, "2"}, {"x-5", SOL_RING, AMBER, "4"}, {"x-6", MANA_VAULT, TOP, "2"},
            {"x-7", MANA_VAULT, OPAL, "3"}, {"x-8", MANA_VAULT, GRIM, "5"}, {"x-9", MANA_VAULT, ARCANE_SIGNET, "1"},
            {"x-10", MANA_VAULT, SIMIC_SIGNET, "3"}, {"x-11", MANA_VAULT, TALISMAN, "3"},
            {"x-12", MANA_VAULT, SCROLL_RACK, "1"}, {"x-13", MANA_VAULT, AMBER, "5"},
        };
        for (final String[] row : rows) {
            start();
            bouncer();
            addCard(row[1], ai);
            hand(row[2]);
            assertEquals(LoopCycles.net(ai, line(row[0])), Integer.parseInt(row[3]), row[0]);
        }

        start();
        ComboLoopBoards.ready(addCard(HULLBREAKER, ai));
        addCard(SOL_RING, ai);
        hand(MANA_VAULT);
        assertEquals(LoopCycles.net(ai, line(RING_VAULT)), 3, "x-2 without Kinnan");
        hand(AMBER);
        assertEquals(LoopCycles.net(ai, line(RING_AMBER)), 1, "x-5 without a legend of ours");

        start();
        bouncer();
        ComboLoopBoards.ready(addCard(NYXBLOOM, ai));
        addCard(SOL_RING, ai);
        hand(MANA_VAULT);
        assertEquals(LoopCycles.net(ai, line(RING_VAULT)), 15, "beside Nyxbloom Ancient");
    }

    // Talisman of Curiosity taps its {C} (its {G} or {U} would deal 1 damage to us): row x-11's cycle taps Mana Vault,
    // casts the Talisman and taps it for colourless, and our life stays 20.
    @Test
    public void talismanTapsItsColourlessMode() {
        start();
        bouncer();
        addCard(MANA_VAULT, ai);
        final Card talisman = hand(TALISMAN);
        endlessOne();
        assertEquals(LoopPool.made(LoopCycles.tapOf(talisman)), ManaAtom.COLORLESS);
        final List<SpellAbility> acts = drive(4);
        assertEquals(steps(acts), List.of("tap Mana Vault", "cast Talisman of Curiosity", "tap Talisman of Curiosity",
                "cast Mana Vault"));
        assertEquals(LoopPool.made(acts.get(2)), ManaAtom.COLORLESS, "its {C}");
        assertEquals(ai.getLife(), 20, "no damage to us");
        assertEquals(pool(), 3);
    }

    // Simic Signet's tap is {1} and {T}: row x-10's cycle taps Mana Vault (4), casts the Signet (2), taps it, paying
    // its {1} from the pool (G, U and Kinnan's one), and casts Mana Vault (1): the pool grows by 3.
    @Test
    public void simicSignetsTapPaysItsOwnCost() {
        start();
        bouncer();
        addCard(MANA_VAULT, ai);
        hand(SIMIC_SIGNET);
        endlessOne();
        final List<SpellAbility> acts = drive(4);
        assertEquals(steps(acts),
                List.of("tap Mana Vault", "cast Simic Signet", "tap Simic Signet", "cast Mana Vault"));
        assertEquals(acts.get(2).getPayCosts().getTotalMana().getCMC(), 1, "the Signet's {1}");
        assertEquals(pool(), 3);
    }

    // Mox Opal taps only with metalcraft: beside two Ornithopters of ours, Opal on our battlefield (Amber in our hand)
    // makes three artifacts, so row x-14 nets 4 and its cycle taps Opal; beside one, it nets 2 and Opal is not tapped.
    @Test
    public void metalcraftLetsMoxOpalTap() {
        start();
        bouncer();
        addCard(AMBER, ai);
        hand(OPAL);
        addCard(ORNITHOPTER, ai);
        addCard(ORNITHOPTER, ai);
        endlessOne();
        assertEquals(LoopCycles.net(ai, line(AMBER_OPAL)), 4);
        final List<SpellAbility> acts = drive(4);
        assertEquals(steps(acts), List.of("tap Mox Amber", "cast Mox Opal", "tap Mox Opal", "cast Mox Amber"));
        assertEquals(pool(), 4);

        start();
        bouncer();
        addCard(AMBER, ai);
        hand(OPAL);
        addCard(ORNITHOPTER, ai);
        endlessOne();
        assertEquals(LoopCycles.net(ai, line(AMBER_OPAL)), 2, "one Ornithopter: no metalcraft");
        assertEquals(steps(drive(3)), List.of("tap Mox Amber", "cast Mox Opal", "cast Mox Amber"));
    }

    // Mox Amber makes nothing without a legend of ours: with Hullbreaker alone beside Sol Ring (row x-5, which names no
    // Kinnan), the cycle taps Sol Ring, casts Amber and casts Sol Ring, never tapping Amber, and nets 1. With Kinnan
    // out Amber is tapped, for 4.
    @Test
    public void aMoxAmberWithNoLegendOfOursIsNeverTapped() {
        start();
        ComboLoopBoards.ready(addCard(HULLBREAKER, ai));
        addCard(SOL_RING, ai);
        final Card amber = hand(AMBER);
        endlessOne();
        assertEquals(LoopCycles.reflectedColour(ai, LoopCycles.tapOf(amber), ManaCost.ZERO), 0, "no colour");
        assertEquals(LoopCycles.net(ai, line(RING_AMBER)), 1);
        assertEquals(steps(drive(3)), List.of("tap Sol Ring", "cast Mox Amber", "cast Sol Ring"));
        assertEquals(pool(), 1);

        start();
        bouncer();
        addCard(SOL_RING, ai);
        hand(AMBER);
        endlessOne();
        assertEquals(LoopCycles.net(ai, line(RING_AMBER)), 4);
        assertEquals(steps(drive(4)), List.of("tap Sol Ring", "cast Mox Amber", "tap Mox Amber", "cast Sol Ring"));
        assertEquals(pool(), 4);
    }

    // A cast and bounce line's steps are its pieces' own: the tap of a piece of ours, the cast of one from our hand.
    // Not another spell, Hullbreaker's or Kinnan's abilities, an opponent's Sol Ring, nor the cast of a piece on our
    // battlefield; and a rock line's steps are its rock's alone.
    @Test
    public void theBounceStepsAreItsPiecesTapsAndCasts() {
        start();
        final Card hullbreaker = bouncer();
        final Card ring = addCard(SOL_RING, ai);
        final Card vault = hand(MANA_VAULT);
        final Card endless = endlessOne();
        final ComboLines.Line l = line(RING_VAULT);
        assertTrue(LoopCycles.isStep(ai, l, manaOf(ring)));
        assertTrue(LoopCycles.isStep(ai, l, vault.getFirstSpellAbility()));
        assertFalse(LoopCycles.isStep(ai, l, endless.getFirstSpellAbility()), "Endless One's cast");
        assertFalse(LoopCycles.isStep(ai, l, hullbreaker.getFirstSpellAbility()), "Hullbreaker's");
        final Card kinnan = onOurBattlefield(KINNAN);
        assertFalse(LoopCycles.isStep(ai, l, kinnan.getNonManaAbilities().get(0)), "Kinnan's dig");
        assertFalse(LoopCycles.isStep(ai, l, manaOf(addCard(SOL_RING, opp))), "an opponent's Sol Ring");
        final Card vaultHere = addCard(MANA_VAULT, ai);
        assertFalse(LoopCycles.isStep(ai, l, vaultHere.getFirstSpellAbility()), "the cast of one on our battlefield");
        assertTrue(LoopCycles.isStep(ai, l, manaOf(vaultHere)), "its tap");
        assertFalse(LoopCycles.isStep(ai, line(KINNAN_BASALT), manaOf(ring)), "a rock line's");
    }

    // The kinds a cast and bounce cycle makes without limit: its pieces' taps (Kinnan's bonus is of the kind tapped),
    // Mox Amber's the colour its tap is set to; a piece that makes nothing adds none.
    @Test
    public void theBounceMakesItsPiecesKinds() {
        start();
        bouncer();
        addCard(SOL_RING, ai);
        hand(MANA_VAULT);
        assertEquals(LoopCycles.colours(ai, line(RING_VAULT)), ManaAtom.COLORLESS);
        hand(TOP);
        assertEquals(LoopCycles.colours(ai, line(RING_TOP)), ManaAtom.COLORLESS, "Top makes none");

        start();
        bouncer();
        addCard(MANA_VAULT, ai);
        hand(SIMIC_SIGNET);
        hand(AMBER);
        assertEquals(LoopCycles.colours(ai, line(VAULT_SIMIC)), ManaAtom.COLORLESS | ManaAtom.GREEN | ManaAtom.BLUE);
        assertEquals(LoopCycles.colours(ai, line(VAULT_AMBER)), ManaAtom.COLORLESS | ManaAtom.BLUE);

        start();
        bouncer();
        addCard(AMBER, ai);
        hand(OPAL);
        assertEquals(LoopCycles.colours(ai, line(AMBER_OPAL)), ManaAtom.BLUE, "Opal, without metalcraft, none");
    }

    // Review Focus 3: two Sol Rings, the first tapped and the second untapped, beside Mana Vault in our hand: the cycle
    // taps the untapped copy, not the first card of the name; both tapped then, Hullbreaker returns the first of them,
    // and the other stays on our battlefield. The bounce's target is our tapped copy first, else our first, never a
    // token.
    @Test
    public void twoSolRingsTheCycleTapsTheUntappedOne() {
        start();
        bouncer();
        final Card tapped = addCard(SOL_RING, ai);
        tapped.setTapped(true);
        final Card untapped = addCard(SOL_RING, ai);
        hand(MANA_VAULT);
        endlessOne();
        assertTrue(LoopCycles.boundary(ai, line(RING_VAULT)), "an untapped Sol Ring");
        final List<SpellAbility> acts = drive(1);
        assertSame(acts.get(0), manaOf(untapped), "the untapped copy's tap");
        assertSame(LoopCycles.bounceTarget(ai, SOL_RING), tapped, "both tapped: the first");
        assertEquals(steps(drive(1)), List.of("cast Mana Vault"));
        assertFalse(stillThere(tapped), "the first returned");
        assertTrue(stillThere(untapped), "the other stays");

        start();
        final Card a = addCard(SOL_RING, ai);
        final Card b = addCard(SOL_RING, ai);
        assertSame(LoopCycles.bounceTarget(ai, SOL_RING), a, "both untapped: the first");
        b.setTapped(true);
        assertSame(LoopCycles.bounceTarget(ai, SOL_RING), b, "a tapped one first");
        b.setGamePieceType(GamePieceType.TOKEN);
        assertSame(LoopCycles.bounceTarget(ai, SOL_RING), a, "never a token");
        assertNull(LoopCycles.bounceTarget(ai, MANA_VAULT));
    }

    // A token piece is no piece: returned to our hand it would cease to exist. A token Sol Ring beside Mana Vault in
    // our hand makes row x-2 net 0, not ready, and starts nothing; the same card as a card, the control, runs.
    @Test
    public void aTokenPieceIsNoPiece() {
        start();
        bouncer();
        final Card ring = addCard(SOL_RING, ai);
        ring.setGamePieceType(GamePieceType.TOKEN);
        hand(MANA_VAULT);
        endlessOne();
        assertEquals(LoopCycles.net(ai, line(RING_VAULT)), 0);
        assertFalse(LoopCycles.ready(ai, line(RING_VAULT)));
        assertNull(driver.mainPhaseAction(List.of()));
        ring.setGamePieceType(GamePieceType.CARD);
        assertEquals(LoopCycles.net(ai, line(RING_VAULT)), 5, "the control: a card");
        assertTrue(LoopCycles.ready(ai, line(RING_VAULT)));
    }

    // R21: under an opponent's Sphere of Resistance each cast costs {1} more, so row x-4's printed net of 1 becomes -1
    // a cycle: the cycle runs once (Sol Ring's tap, Top's cast, Sol Ring's cast, paid by the pool and an Island), and
    // back at its boundary with the pool no higher, its line fails for the phase and the go-off ends.
    @Test
    public void aTaxThatEatsTheBounceFailsItsLine() {
        start();
        bouncer();
        addCard(SOL_RING, ai);
        hand(TOP);
        endlessOne();
        for (int i = 0; i < 5; i++) {
            addCard(ISLAND, ai);
        }
        addCard("Sphere of Resistance", opp);
        assertEquals(LoopCycles.net(ai, line(RING_TOP)), 1, "the printed net");
        assertTrue(LoopCycles.ready(ai, line(RING_TOP)));
        assertEquals(steps(drive(10)), List.of("tap Sol Ring", "cast Sensei's Divining Top", "cast Sol Ring"));
        assertTrue(LoopCycles.boundary(ai, line(RING_TOP)), "back at the boundary");
        assertEquals(pool(), 0, "no higher than at the cycle's start");
        assertNull(driver.mainPhaseAction(List.of()), "the line failed for the phase");
    }

    // LoopDriver.charmModes, Hullbreaker's trigger during a cycle: before any go-off it is null; mid-cycle, for the
    // cast of Mana Vault, a mutable list (CharmEffect.chainAbilities sorts it and chains it) holding a copy of the
    // bounce aimed at our Sol Ring, the card's own mode left with no target, the bounce and not the return of a spell
    // though an opponent's spell is on the stack; for the cast of Sol Ring, Mana Vault in our hand, an empty list
    // (nothing); for another spell's trigger and another card's charm, null; a target left on the trigger's own mode is
    // not carried; with shroud on Sol Ring, nothing; and once the cycle's step is over, null. It draws nothing.
    @Test
    public void theCharmModesOfOurCycle() {
        start();
        final Card hullbreaker = bouncer();
        final Card ring = addCard(SOL_RING, ai);
        final Card vault = hand(MANA_VAULT);
        final Card endless = endlessOne();
        final SpellAbility before = charmFor(hullbreaker, vault);
        assertNull(driver.charmModes(before, CharmEffect.makePossibleOptions(before)), "no go-off yet");
        assertEquals(steps(drive(1)), List.of("tap Sol Ring"));
        final Card bears = createCard("Grizzly Bears", opp);
        game.getStackZone().add(bears);
        final SpellAbility theirs = bears.getFirstSpellAbility();
        theirs.setActivatingPlayer(opp);
        game.getStack().add(theirs);
        game.getTriggerHandler().runWaitingTriggers();
        assertFalse(game.getStack().hasSimultaneousStackEntries(), "an opponent's spell triggers nothing of ours");
        final long draws = MyRandom.callCount();
        final SpellAbility charm = charmFor(hullbreaker, vault);
        final List<AbilitySub> possible = CharmEffect.makePossibleOptions(charm);
        assertEquals(possible.size(), 2, "the return of a spell, and the bounce");
        final List<AbilitySub> modes = driver.charmModes(charm, possible);
        assertNotNull(modes);
        assertEquals(modes.size(), 1);
        final AbilitySub bounce = modes.get(0);
        assertTrue(isBounce(bounce), "the bounce, not the return");
        assertSame(bounce.getTargets().getFirstTargetedCard(), ring);
        for (final AbilitySub mode : possible) {
            assertNotSame(mode, bounce, "a copy");
            assertTrue(mode.getTargets().isEmpty(), "the card's own mode keeps no target");
        }
        CharmEffect.chainAbilities(charm, modes);
        assertSame(charm.getSubAbility().getTargets().getFirstTargetedCard(), ring, "chained");
        final SpellAbility ringCharm = charmFor(hullbreaker, ring);
        assertEquals(driver.charmModes(ringCharm, CharmEffect.makePossibleOptions(ringCharm)), List.of(),
                "Sol Ring's cast, Mana Vault in our hand: nothing");
        final SpellAbility other = charmFor(hullbreaker, endless);
        assertNull(driver.charmModes(other, CharmEffect.makePossibleOptions(other)), "another spell's trigger");
        final SpellAbility abzan = createCard("Abzan Charm", ai).getFirstSpellAbility();
        abzan.setActivatingPlayer(ai);
        assertNull(driver.charmModes(abzan, CharmEffect.makePossibleOptions(abzan)), "another card's charm");
        final SpellAbility foreign = charmFor(hullbreaker, onOurBattlefield(KINNAN), vault);
        assertNull(driver.charmModes(foreign, CharmEffect.makePossibleOptions(foreign)),
                "the same trigger on another host, for Mana Vault's cast");
        // a target left on the trigger's own mode (a stock choice leaves one) is not carried into the copy
        final SpellAbility stale = charmFor(hullbreaker, vault);
        final List<AbilitySub> stalePossible = CharmEffect.makePossibleOptions(stale);
        assertTrue(isBounce(stalePossible.get(1)));
        stalePossible.get(1).getTargets().add(hullbreaker);
        assertEquals(List.copyOf(driver.charmModes(stale, stalePossible).get(0).getTargets()), List.of(ring),
                "our Sol Ring alone");
        // with shroud, Sol Ring cannot be the bounce's target: the trigger chooses nothing
        ring.addChangedCardKeywords(List.of("Shroud"), null, false, game.getNextTimestamp(), null);
        final SpellAbility shrouded = charmFor(hullbreaker, vault);
        assertEquals(driver.charmModes(shrouded, CharmEffect.makePossibleOptions(shrouded)), List.of(),
                "Sol Ring with shroud: nothing");
        assertEquals(MyRandom.callCount() - draws, 0, "the charm choice drew");
        game.getPhaseHandler().devModeSet(PhaseType.COMBAT_BEGIN, ai);
        final SpellAbility later = charmFor(hullbreaker, vault);
        assertNull(driver.charmModes(later, CharmEffect.makePossibleOptions(later)), "the cycle's step is over");
    }

    // While the cycle runs our hand cast of its piece in hand waits for the cycle (ComboHold.vetoesCast): the stock
    // walk plays at the cycle's boundary, where it would cast that piece, which the next cycle returns and casts again,
    // each a round the idle rule reads as progress. Row x-14 with Alpha Myr in hand (a target of 2) through the whole
    // game loop, the turn's cap at 40 actions: the walk casts Alpha Myr from the pool, never Mox Opal, and with nothing
    // left to spend it passes; the go-off ends with Opal in our hand and the cap never reached.
    @Test
    public void theStockWalkNeverCastsTheCyclesPieceInHand() {
        start();
        bouncer();
        addCard(AMBER, ai);
        hand(OPAL);
        hand(ALPHA_MYR);
        driver.useRounds(new LoopRounds(40, 5_000));
        ComboLoopBoards.stepUntil(game, g -> !g.getPhaseHandler().is(PhaseType.MAIN1), 2_000);
        assertFalse(game.getPhaseHandler().is(PhaseType.MAIN1), "Main 1 ended");
        assertEquals(logged("Loop driver: stopped for the turn at 40 actions."), 0, "the cap was never reached");
        assertNotNull(onOurBattlefield(ALPHA_MYR), "the walk spent the pool on its own play");
        assertNotNull(inOurHand(OPAL), "the walk never cast the cycle's piece in our hand");
        assertNotNull(onOurBattlefield(AMBER));
    }

    // The hold reads the running cycle alone: Mox Opal's cast from our hand waits only while row x-14's go-off runs in
    // this step, never Alpha Myr's; not before the go-off, and not once the step is over. While a rock line's go-off
    // runs (Kinnan and Basalt Monolith, the one line the tracker reads), the cast of a second Basalt from our hand
    // waits for nothing, and Hullbreaker's trigger for it is the stock one. It draws nothing.
    @Test
    public void theCycleHoldsItsPieceInHandOnlyWhileItRuns() {
        start();
        bouncer();
        addCard(AMBER, ai);
        final SpellAbility opal = hand(OPAL).getFirstSpellAbility();
        final SpellAbility myr = hand(ALPHA_MYR).getFirstSpellAbility();
        endlessOne();
        assertFalse(ComboHold.vetoesCast(ai, opal), "no go-off yet");
        assertEquals(steps(drive(1)), List.of("tap Mox Amber"));
        final long draws = MyRandom.callCount();
        assertTrue(ComboHold.vetoesCast(ai, opal), "the cycle's piece in our hand");
        assertTrue(driver.holdsCycleCast(opal));
        assertFalse(ComboHold.vetoesCast(ai, myr), "our own play");
        assertEquals(MyRandom.callCount() - draws, 0, "the hold drew");
        game.getPhaseHandler().devModeSet(PhaseType.MAIN2, ai);
        assertFalse(ComboHold.vetoesCast(ai, opal), "the cycle's step is over");

        start();
        t.useLines(ComboLoopBoards.shippedLoopLines(KINNAN_BASALT));
        final Card hullbreaker = bouncer();
        addCard(BASALT, ai);
        final Card second = hand(BASALT);
        endlessOne();
        assertEquals(steps(drive(1)), List.of("tap Basalt Monolith"), "a rock line's go-off");
        assertFalse(ComboHold.vetoesCast(ai, second.getFirstSpellAbility()), "a rock line's go-off holds nothing");
        final SpellAbility charm = charmFor(hullbreaker, second);
        assertNull(driver.charmModes(charm, CharmEffect.makePossibleOptions(charm)), "nor steers a charm");
    }

    // A loop line of a cast and bounce row that names one piece besides Hullbreaker (a hand-built x-2 of Hullbreaker
    // and Sol Ring) is no cycle: it nets 0, is not ready, has no step or boundary, makes no kind, and nothing throws.
    // Nor is one whose second piece has no cast (an Island in our hand).
    @Test
    public void aBounceLineWithOneCyclePieceIsNoCycle() {
        start();
        t.useLines(ComboLoopBoards.loopLines("V|x-2|0||11,17|B:Hullbreaker Horror|B:Sol Ring"));
        bouncer();
        addCard(SOL_RING, ai);
        hand(MANA_VAULT);
        endlessOne();
        final ComboLines.Line l = t.deckLines().get(0);
        assertEquals(l.loop(), LoopKind.BOUNCE);
        assertEquals(LoopCycles.net(ai, l), 0);
        assertFalse(LoopCycles.ready(ai, l));
        assertNull(LoopCycles.next(ai, l));
        assertFalse(LoopCycles.boundary(ai, l));
        assertEquals(LoopCycles.colours(ai, l), 0);
        assertNull(driver.mainPhaseAction(List.of()));

        start();
        t.useLines(ComboLoopBoards.loopLines("V|x-2|0||11,17|B:Hullbreaker Horror|B:Sol Ring|H:Island"));
        bouncer();
        addCard(SOL_RING, ai);
        hand(ISLAND);
        endlessOne();
        final ComboLines.Line land = t.deckLines().get(0);
        assertEquals(LoopCycles.net(ai, land), 0, "an Island has no cast");
        assertFalse(LoopCycles.ready(ai, land));
        assertNull(LoopCycles.next(ai, land));
        assertEquals(LoopCycles.colours(ai, land), 0);
        assertNull(driver.mainPhaseAction(List.of()));

        // the control: the shipped x-2 on a board of the same pieces, Mana Vault in our hand, runs
        start();
        t.useLines(ComboLoopBoards.shippedLoopLines(RING_VAULT));
        bouncer();
        addCard(SOL_RING, ai);
        hand(MANA_VAULT);
        endlessOne();
        assertEquals(LoopCycles.net(ai, line(RING_VAULT)), 5);
        assertNotNull(driver.mainPhaseAction(List.of()), "the control: a go-off starts");
    }

    // With Sol Ring on ours and Mana Vault and Sensei's Divining Top in our hand, rows x-2 (net 5) and x-4 (net 1) are
    // both ready: the higher net runs, so Sol Ring's tap is followed by Mana Vault's cast, not Top's.
    @Test
    public void theHigherNetBounceRuns() {
        start();
        bouncer();
        addCard(SOL_RING, ai);
        hand(MANA_VAULT);
        hand(TOP);
        endlessOne();
        assertTrue(LoopCycles.ready(ai, line(RING_VAULT)));
        assertTrue(LoopCycles.ready(ai, line(RING_TOP)));
        assertEquals(steps(drive(2)), List.of("tap Sol Ring", "cast Mana Vault"));
    }
}
