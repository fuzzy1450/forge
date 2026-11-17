package forge.ai.simulation;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;

import org.testng.annotations.Test;

import forge.game.GameActionUtil;
import forge.game.ability.AbilityUtils;
import forge.game.ability.ApiType;
import forge.game.card.Card;
import forge.game.card.CardFactory;
import forge.game.card.CounterEnumType;
import forge.game.cost.CostPart;
import forge.game.cost.CostReturn;
import forge.game.keyword.KeywordInterface;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import forge.util.MyRandom;
import forge.util.SimScope;

/**
 * {@link DelayedLoss}: the recognizer of the eleven delayed-loss cards, the debt reader and the count of the mana the
 * AI will certainly have when a Pact's debt is paid (MTG_DeckMaker's self-kill fix, DESIGN.md section 4, with the
 * round-3 rulings' tests for Riverpyre Verge and a double striker). A Pact of Negation board is the opponent's first
 * main phase unless a test says otherwise; the plan is the empty one.
 */
public class DelayedLossTest extends FlatMcTestBase {

    private static final List<String> PACTS = List.of("Pact of Negation", "Summoner's Pact", "Slaughter Pact",
            "Intervention Pact", "Pact of the Titan");
    private static final List<String> EXTRA_TURNS = List.of("Final Fortune", "Last Chance", "Warrior's Oath",
            "Chance for Glory");
    /** Cards near the eleven in shape: extra turns with no loss, other losses (class (d)), and plain spells. */
    private static final List<String> CONTROLS = List.of("Lightning Bolt", "Time Walk", "Temporal Manipulation",
            "Karn's Temporal Sundering", "Sundial of the Infinite", "Demonic Pact", "Lich's Mastery",
            "Phage the Untouchable", "One with Death", "Door to Nothingness", "Celestial Convergence",
            "Archfiend of the Dross", "Counterspell", "Force of Will", "Demonic Tutor", "Dark Ritual", "Brainstorm",
            "Sol Ring", "Island", "Grapeshot");

    // --------------------------------------------------------------------------------------------- helpers

    /** The first spell ability of a card put into the AI's hand, the AI its activator. */
    private SpellAbility spell(final String name) {
        final SpellAbility sa = put(name, ai, ZoneType.Hand).getFirstSpellAbility();
        sa.setActivatingPlayer(ai);
        return sa;
    }

    /** Alchemist's Gambit's normal cast, or its cleave cast (the one whose extra turn has no loss). */
    private SpellAbility gambit(final boolean cleave) {
        final Card c = put("Alchemist's Gambit", ai, ZoneType.Hand);
        for (final SpellAbility sa : c.getSpellAbilities()) {
            if ("TrigEffect2".equals(sa.getParam("ExtraTurnDelayedTriggerExecute")) == cleave
                    && sa.hasParam("ExtraTurnDelayedTriggerExecute")) {
                sa.setActivatingPlayer(ai);
                return sa;
            }
        }
        throw new AssertionError("Alchemist's Gambit has no " + (cleave ? "cleave" : "normal") + " cast");
    }

    private String problemOf(final SpellAbility sa) {
        return DelayedLoss.problem(ai, sa, () -> ComboPlan.EMPTY);
    }

    private String pactOfNegation() {
        return problemOf(spell("Pact of Negation"));
    }

    private List<Card> counted(final DelayedLoss.Due due) {
        return DelayedLoss.count(ai, due, DelayedLoss.Removal.NONE).cards();
    }

    /** The opponent's first main phase, the board every Pact of Negation test here starts from. */
    private void opponentsTurn() {
        setUpGame();
        at(PhaseType.MAIN1, opp);
    }

    /** A card cast as far as the stack, by {@code p}, aimed at {@code target} when one is given. */
    private SpellAbility onStack(final String name, final Player p, final SpellAbility target) {
        final Card c = createCard(name, p);
        c.setGameTimestamp(game.getNextTimestamp());
        game.getStackZone().add(c);
        final SpellAbility sa = c.getFirstSpellAbility();
        sa.setActivatingPlayer(p);
        if (target != null) {
            sa.getTargets().add(target);
        }
        game.getStack().add(sa);
        return sa;
    }

    /** A random stream that counts what is drawn from it. */
    private static final class CountingRandom extends Random {
        private static final long serialVersionUID = 1L;
        int draws;

        CountingRandom(final long seed) {
            super(seed);
        }

        @Override
        protected int next(final int bits) {
            draws++;
            return super.next(bits);
        }
    }

    private static int[] counters(final SimScope s) {
        final SimScope.Counter[] kinds = SimScope.Counter.values();
        final int[] out = new int[kinds.length];
        for (int i = 0; i < kinds.length; i++) {
            out[i] = s.peek(kinds[i]);
        }
        return out;
    }

    /**
     * Pact of Negation resolved in the opponent's first main phase over their Grizzly Bears, with no AI deadline: a debt
     * due at the AI's next upkeep (3UU). Run it inside {@code Scoped.under}. The AI controls nothing yet.
     */
    private void resolveAPactOfNegation() {
        setUpGame();
        noDeadline();
        fillLibrary(ai, 10);
        fillLibrary(opp, 10);
        at(PhaseType.MAIN1, opp);
        final SpellAbility bears = onStack("Grizzly Bears", opp, null);
        onStack("Pact of Negation", ai, bears);
        game.getPhaseHandler().onStackResolved();
        playUntilStackClear(game);
        assertTrue(DelayedLoss.owed(ai, null).hasNext(), "the resolved Pact's debt is due at the next upkeep");
    }

    /**
     * Summoner's Pact resolved in the AI's own first main phase, with no AI deadline: a debt due at the AI's next upkeep
     * (2GG). Run it inside {@code Scoped.under}. The AI controls nothing yet.
     */
    private void resolveASummonersPact() {
        setUpGame();
        noDeadline();
        fillLibrary(ai, 10);
        fillLibrary(opp, 10);
        at(PhaseType.MAIN1, ai);
        onStack("Summoner's Pact", ai, null);
        game.getPhaseHandler().onStackResolved();
        playUntilStackClear(game);
        assertTrue(DelayedLoss.owed(ai, null).hasNext(), "the resolved Pact's debt is due at the next upkeep");
    }

    /**
     * The AI's upkeep with its Pact of Negation's trigger on the stack, a debt due now (3UU): the Pact resolved as in
     * {@link #resolveAPactOfNegation}, then the game played into the AI's upkeep. Run it inside {@code Scoped.under}.
     * The AI controls nothing yet; the trigger (a WrappedAbility) is returned.
     */
    private SpellAbility upkeepWithThePactsTrigger() {
        resolveAPactOfNegation();
        for (int i = 0; i < 500 && !game.isGameOver()
                && !(game.getPhaseHandler().is(PhaseType.UPKEEP, ai) && !game.getStack().isEmpty()); i++) {
            game.getPhaseHandler().mainLoopStep();
        }
        assertTrue(game.getPhaseHandler().is(PhaseType.UPKEEP, ai) && !game.getStack().isEmpty(),
                "the AI's upkeep with the Pact's trigger on the stack:\n" + gameStateToString(game));
        final SpellAbility trigger = game.getStack().peekAbility();
        assertEquals(trigger.getApi(), ApiType.LosesGame, "the Pact's pay-or-lose trigger is on top: " + trigger);
        assertTrue(DelayedLoss.owed(ai, null).hasNow(), "a debt due now");
        return trigger;
    }

    /** A spell's alternative-cost version with a part of this kind (Daze's Island return), the AI its activator. */
    private SpellAbility alternative(final SpellAbility sa, final Class<? extends CostPart> part) {
        for (final SpellAbility alt : GameActionUtil.getAlternativeCosts(sa, ai, true)) {
            if (alt.getPayCosts() != null && alt.getPayCosts().hasSpecificCostType(part)) {
                alt.setActivatingPlayer(ai);
                return alt;
            }
        }
        throw new AssertionError(sa.getHostCard() + " has no alternative cost with a " + part.getSimpleName());
    }

    /** The cards put onto the AI's battlefield in a seeded shuffle of this order, so their ids follow the shuffle. */
    private List<String> board(final List<String> names, final long seed) {
        final List<String> order = new ArrayList<>(names);
        Collections.shuffle(order, new Random(seed));
        for (final String name : order) {
            put(name, ai, ZoneType.Battlefield);
        }
        return order;
    }

    // --------------------------------------------------------------------------------------------- recognition

    @Test
    public void kindReadsTheElevenShapes() {
        setUpGame();
        for (final String name : PACTS) {
            assertEquals(DelayedLoss.kind(spell(name)), DelayedLoss.Kind.PACT, name);
        }
        for (final String name : EXTRA_TURNS) {
            assertEquals(DelayedLoss.kind(spell(name)), DelayedLoss.Kind.EXTRA_TURN, name);
        }
        assertEquals(DelayedLoss.kind(gambit(false)), DelayedLoss.Kind.EXTRA_TURN,
                "Alchemist's Gambit's normal cast reaches its loss through its effect's end-step trigger");
        assertEquals(DelayedLoss.kind(spell("Glorious End")), DelayedLoss.Kind.END_STEP, "Glorious End");
    }

    @Test
    public void kindIgnoresEveryOtherShape() {
        setUpGame();
        for (final String name : CONTROLS.subList(0, 12)) {
            final Card c = put(name, ai, ZoneType.Hand);
            for (final SpellAbility sa : c.getSpellAbilities()) {
                sa.setActivatingPlayer(ai);
                assertNull(DelayedLoss.kind(sa), name + ": " + sa);
            }
        }
        assertNull(DelayedLoss.kind(gambit(true)), "Alchemist's Gambit cast for cleave loses nothing");
    }

    @Test(timeOut = 120_000)
    public void theRecognizerTakesNoIdAndDrawsNothing() {
        Scoped.under(new SimScope(7L), () -> {
            setUpGame();
            final List<SpellAbility> all = new ArrayList<>();
            final List<String> names = new ArrayList<>(PACTS);
            names.addAll(EXTRA_TURNS);
            names.addAll(List.of("Alchemist's Gambit", "Glorious End"));
            names.addAll(CONTROLS);
            for (final String name : names) {
                // every card's abilities are built first, so what follows can only read them
                for (final SpellAbility sa : put(name, ai, ZoneType.Hand).getSpellAbilities()) {
                    sa.setActivatingPlayer(ai);
                    all.add(sa);
                }
            }
            final SimScope scope = SimScope.current();
            final CountingRandom random = new CountingRandom(7L);
            MyRandom.setRandom(random);
            final int[] before = counters(scope);
            int flagged = 0;
            for (final SpellAbility sa : all) {
                flagged += DelayedLoss.kind(sa) == null ? 0 : 1;
            }
            assertEquals(flagged, 11, "the eleven cards' delayed-loss spell abilities, Alchemist's cleave not among them");
            assertEquals(counters(scope), before, "no SimScope counter moved: " + List.of(SimScope.Counter.values()));
            assertEquals(random.draws, 0, "and nothing was drawn");
            return null;
        });
    }

    // --------------------------------------------------------------------------------------------- will it untap

    @Test
    public void aGlobalUntapRestrictionCountsNothing() {
        for (final String restrictor : new String[] { null, "Back to Basics", "Stasis", "Winter Orb" }) {
            opponentsTurn();
            put("Underground Sea", 3, ai, ZoneType.Battlefield);
            put("Island", 2, ai, ZoneType.Battlefield);
            if (restrictor != null) {
                put(restrictor, opp, ZoneType.Battlefield);
            }
            game.getAction().checkStateEffects(true);
            if (restrictor == null) {
                assertNull(pactOfNegation(), "with no restriction the five lands pay 3UU");
                continue;
            }
            if ("Winter Orb".equals(restrictor)) {
                boolean adjusted = false;
                for (final KeywordInterface ki : ai.getKeywords()) {
                    adjusted |= ki.getOriginal().startsWith("UntapAdjust");
                }
                assertTrue(adjusted, "Winter Orb gives the AI its UntapAdjust keyword");
            }
            assertEquals(pactOfNegation(), DelayedLoss.PACT_UNPAYABLE,
                    "the opponent's " + restrictor + ": no source is certain to untap");
            assertTrue(counted(DelayedLoss.Due.NEXT).isEmpty(), restrictor + " counts nothing");
        }
    }

    @Test
    public void anAuraStopsOnlyWhatItEnchants() {
        opponentsTurn();
        final Card birds = put("Birds of Paradise", ai, ZoneType.Battlefield);
        final List<Card> islands = put("Island", 2, ai, ZoneType.Battlefield);
        assertTrue(counted(DelayedLoss.Due.NEXT).contains(birds), "Birds of Paradise counts without the aura");
        final Card aura = put("Claustrophobia", opp, ZoneType.Battlefield);
        aura.attachToEntity(birds, null);
        assertTrue(aura.getAttachedTo() == birds, "Claustrophobia enchants Birds of Paradise");
        final List<Card> counted = counted(DelayedLoss.Due.NEXT);
        assertFalse(counted.contains(birds), "the enchanted Birds of Paradise does not untap");
        assertTrue(counted.containsAll(islands), "the Islands still count: " + counted);
    }

    @Test
    public void anEffectStopsOnlyWhatItRemembers() {
        opponentsTurn();
        final Card marsh = put("Cinder Marsh", ai, ZoneType.Battlefield);
        final List<Card> islands = put("Island", 2, ai, ZoneType.Battlefield);
        assertTrue(counted(DelayedLoss.Due.NEXT).contains(marsh), "Cinder Marsh counts before it is tapped for color");
        SpellAbility colored = null;
        for (final SpellAbility ma : marsh.getManaAbilities()) {
            if (ma.getSubAbility() != null && ma.getSubAbility().getApi() == ApiType.Effect) {
                colored = ma;
            }
        }
        assertNotNull(colored, "Cinder Marsh's {B} or {R} ability");
        colored.setActivatingPlayer(ai);
        marsh.setTapped(true);
        AbilityUtils.resolve(colored.getSubAbility()); // tapped for {B}: its effect remembers it
        assertEquals(game.getCardsIn(ZoneType.Command).size(), 1, "the effect that keeps Cinder Marsh tapped");
        List<Card> counted = counted(DelayedLoss.Due.NEXT);
        assertFalse(counted.contains(marsh), "the effect stops Cinder Marsh");
        assertTrue(counted.containsAll(islands), "and only it: " + counted);

        opponentsTurn();
        put("Island", 5, ai, ZoneType.Battlefield);
        final SpellAbility bontu = spell("Bontu's Last Reckoning");
        AbilityUtils.resolve(bontu.getSubAbility()); // "lands you control don't untap during your next untap step"
        counted = counted(DelayedLoss.Due.NEXT);
        assertTrue(counted.isEmpty(), "Bontu's Last Reckoning's effect stops every source: " + counted);
        assertEquals(pactOfNegation(), DelayedLoss.PACT_UNPAYABLE);
    }

    /**
     * Ethereal Grasp's Animate perpetually adds "doesn't untap during your untap step" to its target, a creature: Birds
     * of Paradise here, where DESIGN.md's row names an Island (Ethereal Grasp cannot target one).
     */
    @Test
    public void anAddedRestrictionIsSeen() {
        opponentsTurn();
        final Card birds = put("Birds of Paradise", ai, ZoneType.Battlefield);
        put("Island", 2, ai, ZoneType.Battlefield);
        assertTrue(counted(DelayedLoss.Due.NEXT).contains(birds), "Birds of Paradise counts before Ethereal Grasp");
        final SpellAbility grasp = put("Ethereal Grasp", opp, ZoneType.Hand).getFirstSpellAbility();
        grasp.setActivatingPlayer(opp);
        grasp.getTargets().add(birds);
        AbilityUtils.resolve(grasp);
        assertTrue(birds.isTapped(), "Ethereal Grasp tapped it");
        assertFalse(counted(DelayedLoss.Due.NEXT).contains(birds), "the added restriction is read");
    }

    @Test
    public void aCopyIsNotCounted() {
        opponentsTurn();
        final Card ring = put("Sol Ring", ai, ZoneType.Battlefield);
        final Card metamorph = put("Phyrexian Metamorph", ai, ZoneType.Battlefield);
        metamorph.addCloneState(CardFactory.getCloneStates(ring, metamorph, ring.getFirstSpellAbility()),
                game.getNextTimestamp());
        assertTrue(metamorph.isCloned(), "Phyrexian Metamorph is a copy of Sol Ring");
        final List<Card> counted = counted(DelayedLoss.Due.NEXT);
        assertTrue(counted.contains(ring), "Sol Ring counts");
        assertFalse(counted.contains(metamorph), "its copy does not: its printed card is another card");
    }

    @Test
    public void aStunnedSourceIsNotCounted() {
        opponentsTurn();
        put("Island", 4, ai, ZoneType.Battlefield);
        final Card fifth = put("Island", ai, ZoneType.Battlefield);
        fifth.setTapped(true);
        fifth.setCounters(CounterEnumType.STUN, 1);
        assertEquals(pactOfNegation(), DelayedLoss.PACT_UNPAYABLE, "a tapped Island with a stun counter stays tapped");
        fifth.setCounters(CounterEnumType.STUN, 0);
        assertNull(pactOfNegation(), "without the counter it untaps and pays");
        fifth.setTapped(false);
        fifth.setCounters(CounterEnumType.STUN, 1);
        assertFalse(counted(DelayedLoss.Due.NEXT).contains(fifth),
                "an untapped stunned Island tapped before the upkeep would stay tapped");
        assertTrue(counted(DelayedLoss.Due.NOW).contains(fifth), "but it can tap for a debt due now");
    }

    @Test
    public void aPhasingSourceIsNotCounted() {
        opponentsTurn();
        put("Island", 3, ai, ZoneType.Battlefield);
        final Card isle = put("Teferi's Isle", ai, ZoneType.Battlefield);
        assertFalse(isle.isTapped(), "the Isle is untapped (UU)");
        assertEquals(pactOfNegation(), DelayedLoss.PACT_UNPAYABLE, "Teferi's Isle phases out at the untap step");
        opponentsTurn();
        put("Island", 5, ai, ZoneType.Battlefield);
        assertNull(pactOfNegation(), "five Islands pay");
    }

    @Test
    public void aManaAbilityTheUpkeepCannotActivateIsNotCounted() {
        opponentsTurn();
        put("Island", 3, ai, ZoneType.Battlefield);
        put("Lavinia, Foil to Conspiracy", ai, ZoneType.Battlefield).setSickness(false);
        assertEquals(pactOfNegation(), DelayedLoss.PACT_UNPAYABLE,
                "Lavinia's {C}{C} is only for an opponent's turn, and the upkeep is the AI's (3 < 5)");
        opponentsTurn();
        put("Island", 3, ai, ZoneType.Battlefield);
        put("Sol Ring", ai, ZoneType.Battlefield);
        assertNull(pactOfNegation(), "Sol Ring's {C}{C} in its place pays");
    }

    @Test
    public void gemstoneCavernsWithoutALuckCounterMakesOnlyC() {
        opponentsTurn();
        final Card caverns = put("Gemstone Caverns", ai, ZoneType.Battlefield);
        put("Island", 2, ai, ZoneType.Battlefield);
        assertEquals(problemOf(spell("Slaughter Pact")), DelayedLoss.PACT_UNPAYABLE,
                "without a luck counter Gemstone Caverns makes {C}: no B for 2B");
        caverns.setCounters(CounterEnumType.LUCK, 1);
        assertNull(problemOf(spell("Slaughter Pact")), "with one it makes any color");
    }

    /** RULINGS-R3 M22: an activation condition on a mana ability is judged on today's board. */
    @Test
    public void riverpyreVergesBlueIsNotCountedWhenItsConditionFails() {
        opponentsTurn();
        put("Riverpyre Verge", ai, ZoneType.Battlefield);
        put("Halimar Depths", ai, ZoneType.Battlefield);
        put("Swamp", 3, ai, ZoneType.Battlefield);
        assertEquals(pactOfNegation(), DelayedLoss.PACT_UNPAYABLE,
                "no Island or Mountain: the Verge makes only {R}, one U for UU");
        opponentsTurn();
        put("Riverpyre Verge", ai, ZoneType.Battlefield);
        put("Halimar Depths", ai, ZoneType.Battlefield);
        put("Swamp", 2, ai, ZoneType.Battlefield);
        put("Mountain", ai, ZoneType.Battlefield);
        assertNull(pactOfNegation(), "with a Mountain the Verge's {U} counts: UU from it and Halimar Depths");
    }

    /** RULINGS-R3 M27: in the threat term a double striker counts twice its power. */
    @Test
    public void aDoubleStrikersPowerCountsTwiceInTheThreat() {
        for (final String attacker : new String[] { "Memnite", "Fencing Ace" }) {
            opponentsTurn();
            ai.setLife(3, null);
            put(attacker, opp, ZoneType.Battlefield);
            put("Island", 4, ai, ZoneType.Battlefield);
            put("Mana Confluence", ai, ZoneType.Battlefield);
            game.getAction().checkStateEffects(true);
            final boolean doubles = "Fencing Ace".equals(attacker);
            assertEquals(DelayedLoss.threat(ai), doubles ? 2 : 1, attacker + "'s power in the threat");
            if (doubles) {
                assertEquals(pactOfNegation(), DelayedLoss.PACT_UNPAYABLE,
                        "3 life - 2 threat leaves no margin for Mana Confluence's 1 life");
            } else {
                assertNull(pactOfNegation(), "3 life - 1 threat: Mana Confluence's 1 life fits (2 - 1 >= 1)");
            }
        }
    }

    /**
     * A debt due now is counted from what is untapped now (DESIGN.md 1.4, round 2's B1), and Mana Vault and its kind
     * still count nothing there: the rule keeps one reading of the "doesn't untap" row, untapped or not.
     */
    @Test
    public void aDebtDueNowCountsWhatIsUntappedButNeverAManaVault() {
        opponentsTurn();
        final Card island = put("Island", ai, ZoneType.Battlefield);
        final Card tapped = put("Island", ai, ZoneType.Battlefield);
        tapped.setTapped(true);
        final Card vault = put("Mana Vault", ai, ZoneType.Battlefield);
        final Card monolith = put("Grim Monolith", ai, ZoneType.Battlefield);
        final List<Card> now = counted(DelayedLoss.Due.NOW);
        assertTrue(now.contains(island), "an untapped Island pays a debt due now: " + now);
        assertFalse(now.contains(tapped), "a tapped one does not untap before it resolves: " + now);
        assertFalse(now.contains(vault), "an untapped Mana Vault counts nothing, as for the next upkeep: " + now);
        assertFalse(now.contains(monolith), "nor an untapped Grim Monolith: " + now);
        final List<Card> next = counted(DelayedLoss.Due.NEXT);
        assertTrue(next.contains(island) && next.contains(tapped), "both Islands untap for the next upkeep: " + next);
        assertFalse(next.contains(vault) || next.contains(monolith), "the Vault and the Monolith do not: " + next);
    }

    // --------------------------------------------------------------------------------------------- debts

    @Test(timeOut = 300_000)
    public void aDebtOnTheStackIsReadThroughTheWrapper() {
        Scoped.under(new SimScope(31L), () -> {
            setUpGame();
            noDeadline();
            fillLibrary(ai, 10);
            fillLibrary(opp, 10);
            at(PhaseType.MAIN1, opp);
            final SpellAbility bears = onStack("Grizzly Bears", opp, null);
            onStack("Pact of Negation", ai, bears);
            game.getPhaseHandler().onStackResolved();
            playUntilStackClear(game);
            assertEquals(game.getTriggerHandler().getDelayedTriggersView().size(), 1,
                    "the resolved Pact registered its upkeep trigger");
            final DelayedLoss.Debts pending = DelayedLoss.owed(ai, null);
            assertTrue(pending.hasNext() && !pending.hasNow(), "a debt due at the next upkeep: " + pending);

            for (int i = 0; i < 500 && !game.isGameOver()
                    && !(game.getPhaseHandler().is(PhaseType.UPKEEP, ai) && !game.getStack().isEmpty()); i++) {
                game.getPhaseHandler().mainLoopStep();
            }
            assertTrue(game.getPhaseHandler().is(PhaseType.UPKEEP, ai) && !game.getStack().isEmpty(),
                    "the AI's upkeep with the Pact's trigger on the stack:\n" + gameStateToString(game));
            final SpellAbility top = game.getStack().peekAbility();
            assertTrue(top.isWrapper(), "the trigger on the stack is a WrappedAbility: " + top);
            assertEquals(top.getApi(), ApiType.LosesGame, "it delegates its API to the LosesGame it wraps");

            final DelayedLoss.Debts owed = DelayedLoss.owed(ai, null);
            assertTrue(owed.hasNow(), "one debt due now: " + owed);
            assertFalse(owed.hasNext(), "and none at the next upkeep: " + owed);
            assertEquals(owed.nowDebts().size(), 1);
            assertEquals(owed.now().total(), 5, "3 U U is five mana");
            assertEquals(owed.now().pips(), List.of(DelayedLoss.U, DelayedLoss.U), "with two blue pips");
            assertTrue(game.getTriggerHandler().getDelayedTriggersView().isEmpty(),
                    "the delayed trigger left the handler's list when it fired");
            return null;
        });
    }

    // --------------------------------------------------------------------------------------------- debt protection

    /**
     * DESIGN.md section 4 (round 2's B1): a debt due now is counted from what is untapped now. Daze's alternative cost
     * returns an Island: one of the five untapped Islands, in the worst case.
     */
    @Test(timeOut = 300_000)
    public void aDebtDueNowCountsOnlyWhatIsUntappedNow() {
        Scoped.under(new SimScope(41L), () -> {
            upkeepWithThePactsTrigger();
            final List<Card> untapped = put("Island", 5, ai, ZoneType.Battlefield);
            put("Island", ai, ZoneType.Battlefield).setTapped(true);
            put("Island", ai, ZoneType.Hand);
            final SpellAbility daze = alternative(spell("Daze"), CostReturn.class);
            assertEquals(problemOf(daze), DelayedLoss.DEBT_DUE_NOW,
                    "Daze returns an untapped Island: four untapped Islands are left for 3UU");
            final DelayedLoss.Removal anIsland = new DelayedLoss.Removal(List.of(untapped.get(0)), 0);
            assertEquals(DelayedLoss.count(ai, DelayedLoss.Due.NOW, anIsland).units().size(), 4,
                    "what pays a debt due now: the untapped Islands left");
            assertEquals(DelayedLoss.count(ai, DelayedLoss.Due.NEXT, anIsland).units().size(), 6,
                    "the next-upkeep count would have said 6: the tapped Island untaps, the hand's Island is the drop");
            return null;
        });
    }

    /**
     * RULINGS-R3 M20: for a debt due now a summoning-sick creature gives no tap unit. Five untapped Islands and Birds of
     * Paradise: Daze's alternative cost returns an Island, which the Birds replace only when they can tap.
     */
    @Test(timeOut = 300_000)
    public void aSummoningSickCreatureDoesNotPayADebtDueNow() {
        for (final boolean sick : new boolean[] { true, false }) {
            Scoped.under(new SimScope(43L), () -> {
                upkeepWithThePactsTrigger();
                put("Island", 5, ai, ZoneType.Battlefield);
                final Card birds = put("Birds of Paradise", ai, ZoneType.Battlefield);
                birds.setSickness(sick);
                assertEquals(birds.isSick(), sick, "Birds of Paradise is summoning-sick: " + sick);
                final String v = problemOf(alternative(spell("Daze"), CostReturn.class));
                if (sick) {
                    assertEquals(v, DelayedLoss.DEBT_DUE_NOW, "a sick Birds cannot tap: four Islands for 3UU");
                } else {
                    assertNull(v, "four Islands and the Birds pay 3UU after Daze's return");
                }
                return null;
            });
        }
    }

    /**
     * RULINGS-R3 M17: while a debt's cover needs a painful unit, the life the play's own effects cost and the pain its
     * mana may cost come off the life budget. A pending Pact of Negation debt (3UU), Island x3 and Ancient Tomb, the
     * opponent's end step: at 6 life the Tomb fits (2 + 3 < 6), and Vampiric Tutor's 2 life, with its {B} perhaps
     * tapped from the untapped Tomb (1 x 2), leaves it none. Each half alone, too; and nothing is asked of the play
     * while the painless sources cover the debt.
     */
    @Test(timeOut = 300_000)
    public void thePlaysOwnLifeLossIsBudgetedWhileTheCoverNeedsPain() {
        Scoped.under(new SimScope(47L), () -> {
            resolveAPactOfNegation();
            put("Island", 3, ai, ZoneType.Battlefield);
            final Card tomb = put("Ancient Tomb", ai, ZoneType.Battlefield);
            at(PhaseType.END_OF_TURN, opp);
            ai.setLife(6, null);
            assertEquals(DelayedLoss.count(ai, DelayedLoss.Due.NEXT, DelayedLoss.Removal.NONE).units().size(), 5,
                    "at 6 life the Tomb is counted: 3 + 2 for 3UU");
            assertEquals(problemOf(spell("Vampiric Tutor")), DelayedLoss.DEBT_UNPROTECTED,
                    "6 - 2 (its own loss) - 1 x 2 (its {B} from the Tomb) = 2: the Tomb no longer fits");
            ai.setLife(40, null);
            assertNull(problemOf(spell("Vampiric Tutor")), "at 40 life the Tomb fits after it");

            ai.setLife(6, null);
            tomb.setTapped(true); // it untaps for the upkeep; no untapped painful source can pay the Tutor's {B}
            assertEquals(problemOf(spell("Vampiric Tutor")), DelayedLoss.DEBT_UNPROTECTED,
                    "its own 2 life alone: 6 - 2 = 4, and 2 + 3 < 4 fails");
            tomb.setTapped(false);
            ai.setLife(7, null);
            assertEquals(problemOf(spell("Brainstorm")), DelayedLoss.DEBT_UNPROTECTED,
                    "no loss of its own, but its {U} may be tapped from the Tomb: 7 - 1 x 2 = 5, and 2 + 3 < 5 fails");
            tomb.setTapped(true);
            assertNull(problemOf(spell("Brainstorm")), "with the Tomb tapped its mana costs no life");

            tomb.setTapped(false);
            put("Island", 2, ai, ZoneType.Battlefield);
            ai.setLife(3, null);
            assertNull(problemOf(spell("Vampiric Tutor")), "five Islands cover 3UU without the Tomb: nothing to budget");
            return null;
        });
    }

    /**
     * RULINGS-R3 M18: a board-wide removal of the AI's own play takes the counted sources its filter matches. A pending
     * Summoner's Pact debt (2GG): Culling Ritual destroys each nonland permanent with mana value 2 or less, Sol Ring
     * among them.
     */
    @Test(timeOut = 300_000)
    public void anOwnBoardWideRemovalTakesTheSourcesItMatches() {
        for (final int forests : new int[] { 2, 4 }) {
            Scoped.under(new SimScope(53L), () -> {
                resolveASummonersPact();
                put("Forest", forests, ai, ZoneType.Battlefield);
                put("Sol Ring", ai, ZoneType.Battlefield);
                final String v = problemOf(spell("Culling Ritual"));
                if (forests == 2) {
                    assertEquals(v, DelayedLoss.DEBT_UNPROTECTED, "the debt needs Sol Ring's CC: two Forests are left");
                } else {
                    assertNull(v, "four Forests cover 2GG without Sol Ring");
                }
                return null;
            });
        }
    }

    /**
     * RULINGS-R3 M28: a cost that puts -1/-1 counters on a counted creature whose toughness is at most the counters
     * removes it. A pending Summoner's Pact debt (2GG), Forest x3 and Devoted Druid: the Druid's untap puts a -1/-1
     * counter on it, which kills it once it has one already (toughness 1).
     */
    @Test(timeOut = 300_000)
    public void aMinusOneCounterCostThatKillsACountedCreatureTakesIt() {
        for (final int already : new int[] { 1, 0 }) {
            Scoped.under(new SimScope(59L), () -> {
                resolveASummonersPact();
                put("Forest", 3, ai, ZoneType.Battlefield);
                final Card druid = put("Devoted Druid", ai, ZoneType.Battlefield);
                druid.setCounters(CounterEnumType.M1M1, already);
                assertEquals(druid.getNetToughness(), 2 - already, "Devoted Druid's toughness");
                assertTrue(counted(DelayedLoss.Due.NEXT).contains(druid), "the Druid's G is counted: 4 for 2GG");
                SpellAbility untap = null;
                for (final SpellAbility sa : druid.getSpellAbilities()) {
                    if (sa.getApi() == ApiType.Untap) {
                        untap = sa;
                    }
                }
                assertNotNull(untap, "Devoted Druid's untap ability");
                untap.setActivatingPlayer(ai);
                final String v = problemOf(untap);
                if (already == 1) {
                    assertEquals(v, DelayedLoss.DEBT_UNPROTECTED, "a second counter kills the Druid: 3 G for 2GG");
                } else {
                    assertNull(v, "the first counter leaves it at toughness 1");
                }
                return null;
            });
        }
    }

    // --------------------------------------------------------------------------------------------- copies

    /**
     * DESIGN.md 1.8 (D26): a copy of a flagged spell is judged as that spell cast by the AI. A copy of the opponent's
     * Pact of Negation is a debt of the AI's, covered or not by the AI's count; a copy of a spell nothing flags is
     * nothing; a copy with no target yet copies any item on the stack.
     */
    @Test
    public void aCopyIsJudgedAsWhatItCopies() {
        opponentsTurn();
        put("Island", 3, ai, ZoneType.Battlefield);
        final SpellAbility theirs = onStack("Pact of Negation", opp, onStack("Grizzly Bears", ai, null));
        SpellAbility twincast = spell("Twincast");
        twincast.getTargets().add(theirs);
        assertEquals(problemOf(twincast), DelayedLoss.PACT_UNPAYABLE, "the copy's 3UU is the AI's: three Islands");
        put("Island", 2, ai, ZoneType.Battlefield);
        assertNull(problemOf(twincast), "five Islands pay it");

        opponentsTurn();
        final SpellAbility counter = onStack("Counterspell", opp, onStack("Grizzly Bears", ai, null));
        twincast = spell("Twincast");
        twincast.getTargets().add(counter);
        assertNull(problemOf(twincast), "a copy of Counterspell is nothing to judge");
        onStack("Final Fortune", ai, null);
        assertEquals(problemOf(spell("Twincast")), DelayedLoss.EXTRA_TURN_LOSES,
                "with no target yet, a copy may copy Final Fortune on the stack");
    }

    /**
     * DESIGN.md section 4 (finding I5), with RULINGS-R3 M29(d): the same two boards built in three orders (seeded
     * shuffles of the order the AI's permanents are made in, so of their ids) give the same verdicts. Neither board
     * holds two painful sources of different colors with the same pain, so (pain, card id) order never chooses
     * between colors. The first board sits at the edge of the pain budget: Mana Confluence and City of Brass (pain 1,
     * any color), Ancient Tomb (2), Tarnished Citadel (3; its {C} is painless); at life 3 both pain-1 sources fit, at
     * life 2 only one. The second sits at the edge of a matching: Underground Sea and Volcanic Island are its only blue,
     * so with a Pact of Negation of the AI's on the stack (a debt due next upkeep) Crop Rotation's sacrifice of either
     * leaves one U.
     */
    @Test
    public void theVerdictDoesNotDependOnBoardOrder() {
        final List<String> painEdge = List.of("Island", "Island", "Mana Confluence", "City of Brass", "Ancient Tomb",
                "Tarnished Citadel");
        final List<String> matchingEdge = List.of("Underground Sea", "Volcanic Island", "Badlands", "Swamp", "Swamp",
                "Mountain");
        final List<List<String>> orders = new ArrayList<>();
        for (final long seed : new long[] { 1L, 2L, 3L }) {
            final List<String> verdicts = new ArrayList<>();
            for (final int life : new int[] { 3, 2 }) {
                opponentsTurn();
                final List<String> order = board(painEdge, seed);
                if (life == 3) {
                    orders.add(order);
                }
                ai.setLife(life, null);
                verdicts.add(pactOfNegation());
            }
            opponentsTurn();
            orders.add(board(matchingEdge, seed));
            verdicts.add(pactOfNegation());
            onStack("Pact of Negation", ai, onStack("Grizzly Bears", opp, null));
            verdicts.add(problemOf(spell("Crop Rotation")));
            assertEquals(verdicts, Arrays.asList(null, DelayedLoss.PACT_UNPAYABLE, null, DelayedLoss.DEBT_UNPROTECTED),
                    "seed " + seed + ": pain edge at life 3 and 2, the duals, Crop Rotation with the debt pending; orders "
                            + orders);
        }
        assertFalse(orders.get(0).equals(orders.get(2)) && orders.get(2).equals(orders.get(4)),
                "the seeds put the pain board down in different orders: " + orders);
        assertFalse(orders.get(1).equals(orders.get(3)) && orders.get(3).equals(orders.get(5)),
                "and the duals: " + orders);
    }
}
