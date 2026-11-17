package forge.ai.simulation;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.testng.annotations.Test;

import forge.game.ability.AbilityUtils;
import forge.game.ability.ApiType;
import forge.game.card.Card;
import forge.game.card.CardFactory;
import forge.game.card.CounterEnumType;
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
}
