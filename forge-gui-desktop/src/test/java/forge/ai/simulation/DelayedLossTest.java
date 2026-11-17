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
import forge.game.cost.Cost;
import forge.game.cost.CostPart;
import forge.game.cost.CostPayLife;
import forge.game.cost.CostReturn;
import forge.game.keyword.KeywordInterface;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.trigger.Trigger;
import forge.game.trigger.WrappedAbility;
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

    /** Every card's spell abilities (mana abilities among them), in every zone a read can reach, asked once. */
    private void askEveryCardsAbilities() {
        for (final ZoneType zone : new ZoneType[] { ZoneType.Battlefield, ZoneType.Hand, ZoneType.Command,
                ZoneType.Graveyard, ZoneType.Exile, ZoneType.Library, ZoneType.Stack }) {
            for (final Card c : game.getCardsIn(zone)) {
                c.getSpellAbilities();
            }
        }
    }

    /**
     * DESIGN.md section 4 (findings I2, round 2's B1 and B4), now that every predicate exists: the count, debt
     * protection, copies and the resolution sites' predicates take no SimScope id and draw nothing. Every card's spell
     * abilities are asked first; then, with no read in between, states whose replacement effects were never built:
     * an opposing creature's first shield counter, a Saga, and the first stun counters of the AI's tapped Birds of
     * Paradise and untapped Sol Ring (a {@code Card.canUntap} would build them all). Then each call twice, with the
     * seven SimScope counters and the draws of a counting random compared: on the first board (every source kind, the
     * opponent's first main phase) {@code problem} of Pact of Negation and {@code declinesFreeCast} of it with no debt,
     * then, a Pact of the Titan's debt pending, {@code problem} and {@code declinesFreeCast} of Crop Rotation; on the
     * second (the AI's upkeep, Pact of Negation's trigger on the stack under an opponent's spell) {@code problem} of
     * Daze's alternative cost and of Counterspell, {@code declinesUnlessPayment} of a {1} the opponent asks, and
     * {@code problem} of Ad Nauseam and Austere Command, whose parts hang under additional abilities (T2 review
     * finding 2: the walk that reads them).
     */
    @Test(timeOut = 300_000)
    public void theCoverageReadTakesNoIdAndDrawsNothing() {
        Scoped.under(new SimScope(7L), () -> {
            setUpGame();
            noDeadline();
            fillLibrary(ai, 10);
            fillLibrary(opp, 10);
            at(PhaseType.MAIN1, ai);
            onStack("Pact of the Titan", ai, null);
            game.getPhaseHandler().onStackResolved();
            playUntilStackClear(game);
            final List<Trigger> titanDebt = new ArrayList<>(game.getTriggerHandler().getDelayedTriggersView());
            assertEquals(titanDebt.size(), 1, "Pact of the Titan's upkeep trigger");
            at(PhaseType.MAIN1, opp);
            for (final String name : List.of("Island", "Ancient Tomb", "Mana Confluence", "City of Brass",
                    "Tarnished Citadel", "Gemstone Caverns", "City of Traitors", "Mox Opal", "Mox Amber", "Chrome Mox",
                    "Mana Vault", "Dimir Signet", "Talisman of Creativity", "Exotic Orchard", "Bloom Tender",
                    "Vivi Ornitier", "Springleaf Drum", "The Cabbage Merchant", "Lotus Petal", "Teferi's Isle",
                    "Lavinia, Foil to Conspiracy")) {
                put(name, ai, ZoneType.Battlefield);
            }
            final Card birds = put("Birds of Paradise", ai, ZoneType.Battlefield);
            birds.setTapped(true);
            final Card ring = put("Sol Ring", ai, ZoneType.Battlefield);
            final Card elves = put("Llanowar Elves", ai, ZoneType.Battlefield);
            put("Claustrophobia", opp, ZoneType.Battlefield).attachToEntity(elves, null);
            final Card marsh = put("Cinder Marsh", ai, ZoneType.Battlefield);
            SpellAbility colored = null;
            for (final SpellAbility ma : marsh.getManaAbilities()) {
                if (ma.getSubAbility() != null && ma.getSubAbility().getApi() == ApiType.Effect) {
                    colored = ma;
                }
            }
            assertNotNull(colored, "Cinder Marsh's {B} or {R} ability");
            colored.setActivatingPlayer(ai);
            marsh.setTapped(true);
            AbilityUtils.resolve(colored.getSubAbility()); // its effect keeps the Marsh tapped
            assertEquals(game.getCardsIn(ZoneType.Command).size(), 1, "the effect that remembers Cinder Marsh");
            put("Mountain", opp, ZoneType.Battlefield);
            final Card bears = put("Grizzly Bears", opp, ZoneType.Battlefield);
            put("Island", ai, ZoneType.Hand);
            final SpellAbility pon = spell("Pact of Negation");
            final SpellAbility crop = spell("Crop Rotation");
            game.getTriggerHandler().clearDelayedTrigger(); // the Titan's debt waits outside the handler for now
            askEveryCardsAbilities();
            bears.setCounters(CounterEnumType.SHIELD, 1);
            put("History of Benalia", opp, ZoneType.Battlefield);
            birds.setCounters(CounterEnumType.STUN, 1);
            ring.setCounters(CounterEnumType.STUN, 1);

            final SimScope scope = SimScope.current();
            final CountingRandom random = new CountingRandom(7L);
            MyRandom.setRandom(random);
            int[] before = counters(scope);
            final List<String> first = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                first.add(problemOf(pon));
                first.add(String.valueOf(DelayedLoss.declinesFreeCast(ai, pon)));
            }
            game.getTriggerHandler().registerDelayedTrigger(titanDebt.get(0)); // a list's add: the debt is pending
            for (int i = 0; i < 2; i++) {
                first.add(problemOf(crop));
                first.add(String.valueOf(DelayedLoss.declinesFreeCast(ai, crop)));
            }
            assertEquals(counters(scope), before, "the first board: no SimScope counter moved "
                    + List.of(SimScope.Counter.values()) + "; verdicts " + first);
            assertEquals(random.draws, 0, "and nothing was drawn");
            assertEquals(first, Arrays.asList(null, "true", null, "true", null, "true", null, "true"),
                    "every source kind counted, Pact of Negation covered; Crop Rotation counted with each land taken in"
                            + " turn, the Titan's 4R covered each time; both free casts declined");

            final SpellAbility trigger = upkeepWithThePactsTrigger();
            final SpellAbility angel = onStack("Serra Angel", opp, null);
            final List<Card> islands = put("Island", 5, ai, ZoneType.Battlefield);
            put("Mana Confluence", ai, ZoneType.Battlefield);
            final Card theirs = put("Grizzly Bears", opp, ZoneType.Battlefield);
            final SpellAbility daze = alternative(spell("Daze"), CostReturn.class);
            daze.getTargets().add(angel);
            final SpellAbility counterspell = spell("Counterspell");
            counterspell.getTargets().add(angel);
            final SpellAbility leak = put("Mana Leak", opp, ZoneType.Hand).getFirstSpellAbility();
            leak.setActivatingPlayer(opp);
            final Cost one = new Cost("1", true);
            final SpellAbility nauseam = spell("Ad Nauseam"); // its loss hangs under its Repeat's RepeatSubAbility
            final SpellAbility austere = spell("Austere Command"); // its DestroyAll modes are its Choices
            askEveryCardsAbilities();
            theirs.setCounters(CounterEnumType.SHIELD, 1);
            islands.get(0).setCounters(CounterEnumType.STUN, 1);

            final CountingRandom random2 = new CountingRandom(8L);
            MyRandom.setRandom(random2);
            before = counters(scope);
            final List<String> second = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                second.add(problemOf(daze));
                second.add(problemOf(counterspell));
                second.add(String.valueOf(DelayedLoss.declinesUnlessPayment(ai, leak, one)));
                second.add(String.valueOf(DelayedLoss.isOwnPactDebt(ai, trigger)));
                second.add(problemOf(nauseam));
                second.add(problemOf(austere));
            }
            assertEquals(counters(scope), before, "the second board: no SimScope counter moved "
                    + List.of(SimScope.Counter.values()) + "; verdicts " + second);
            assertEquals(random2.draws, 0, "and nothing was drawn");
            assertEquals(second, Arrays.asList(null, DelayedLoss.DEBT_DUE_NOW, "true", "true", DelayedLoss.DEBT_DUE_NOW,
                    DelayedLoss.DEBT_DUE_NOW, null, DelayedLoss.DEBT_DUE_NOW, "true", "true", DelayedLoss.DEBT_DUE_NOW,
                    DelayedLoss.DEBT_DUE_NOW), "Daze's return leaves four Islands and Mana Confluence; Counterspell and"
                    + " the {1} would tap Islands the trigger needs; the trigger is the AI's own debt; Ad Nauseam's 3BB"
                    + " and Austere Command's 4WW would too");
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
     * DESIGN.md 1.7 (round 2's B1), T2 review finding 3: while a debt is due now, a play's mana payment must leave the
     * debt covered whichever sources it taps, its colors included, not only its total. Pact of Negation's trigger (3UU)
     * on the stack, Sol Ring x3 and Island x2 untapped: Brainstorm's {U} can only be paid by an Island, and one Island
     * is then left for UU, though the payment that takes off the source with the most units (a Sol Ring) leaves six
     * mana and both Islands. With a third Island the payment can spare one.
     */
    @Test(timeOut = 300_000)
    public void aDebtDueNowKeepsTheColorsItNeedsWhicheverSourceThePaymentTaps() {
        for (final int islands : new int[] { 2, 3 }) {
            Scoped.under(new SimScope(97L), () -> {
                upkeepWithThePactsTrigger();
                put("Sol Ring", 3, ai, ZoneType.Battlefield);
                put("Island", islands, ai, ZoneType.Battlefield);
                final String v = problemOf(spell("Brainstorm"));
                if (islands == 2) {
                    assertEquals(v, DelayedLoss.DEBT_DUE_NOW,
                            "Brainstorm's {U} taps one of the two Islands: one U is left for UU");
                } else {
                    assertNull(v, "a third Island: two are left for UU whichever Island pays the {U}");
                }
                return null;
            });
        }
    }

    /**
     * DESIGN.md 1.7, T2 review finding 3: a filter the payment may use takes off a second source, the one that feeds
     * it, so a payment of mana value t may tap t + min(t, the counted filters) sources. Pact of Negation's trigger
     * (3UU) on the stack, Dimir Signet and Island x5 untapped: an opponent's {1} unless cost may be paid with the
     * Signet's mana, fed by an Island, which leaves four Islands; with a sixth Island five are left.
     */
    @Test(timeOut = 300_000)
    public void aFilterThePaymentMayUseTakesTheSourceThatFeedsIt() {
        for (final int islands : new int[] { 5, 6 }) {
            Scoped.under(new SimScope(101L), () -> {
                upkeepWithThePactsTrigger();
                put("Dimir Signet", ai, ZoneType.Battlefield);
                put("Island", islands, ai, ZoneType.Battlefield);
                final SpellAbility leak = put("Mana Leak", opp, ZoneType.Hand).getFirstSpellAbility();
                leak.setActivatingPlayer(opp);
                final boolean declined = DelayedLoss.declinesUnlessPayment(ai, leak, new Cost("1", true));
                if (islands == 5) {
                    assertTrue(declined,
                            "the Signet and the Island that feeds it may pay the {1}: four Islands for 3UU");
                } else {
                    assertFalse(declined, "a sixth Island: five are left whichever two pay");
                }
                return null;
            });
        }
    }

    /**
     * DESIGN.md 1.7, T2 review finding 5: a mana part with X is refused while a debt is due now, however much is
     * untapped, since X is chosen after the decision and may tap every source the debt needs. Pact of Negation's
     * trigger (3UU) under an opponent's Serra Angel, Island x10 untapped: Counterspell leaves eight Islands, allowed;
     * Condescend's {X}{U} is refused.
     */
    @Test(timeOut = 300_000)
    public void anXManaCostIsRefusedWhileADebtIsDueNow() {
        Scoped.under(new SimScope(103L), () -> {
            upkeepWithThePactsTrigger();
            final SpellAbility angel = onStack("Serra Angel", opp, null);
            put("Island", 10, ai, ZoneType.Battlefield);
            final SpellAbility counterspell = spell("Counterspell");
            counterspell.getTargets().add(angel);
            assertNull(problemOf(counterspell), "Counterspell's {U}{U}: eight Islands are left for 3UU");
            final SpellAbility condescend = spell("Condescend");
            condescend.getTargets().add(angel);
            assertEquals(problemOf(condescend), DelayedLoss.DEBT_DUE_NOW,
                    "Condescend's X is chosen after the decision: it may tap the Islands the trigger needs");
            return null;
        });
    }

    /**
     * problem's answers for three plays whose life cannot be read as a number (RULINGS-R3 M17, T2 review finding 4):
     * Toxic Deluge (its additional cost, {@code PayLife<X>}), Stroke of Genius ({X}{2}{U}: X mana, any of it perhaps
     * tapped from a painful source) and Reanimate (its own LoseLife of X, the creature's mana value). A throw is
     * answered by its text: ComboGuard.problem would answer it with refusalWhenUnread, no refusal for a play nothing
     * flags.
     */
    private List<String> unreadableLifeVerdicts() {
        final List<String> out = new ArrayList<>();
        for (final String name : List.of("Toxic Deluge", "Stroke of Genius", "Reanimate")) {
            try {
                out.add(problemOf(spell(name)));
            } catch (final RuntimeException e) {
                out.add("threw " + e);
            }
        }
        return out;
    }

    /**
     * RULINGS-R3 M17: while a debt's cover needs a painful unit, the life the play's own effects cost and the pain its
     * mana may cost come off the life budget. A pending Pact of Negation debt (3UU), Island x3 and Ancient Tomb, the
     * opponent's end step: at 6 life the Tomb fits (2 + 3 < 6), and Vampiric Tutor's 2 life, with its {B} perhaps
     * tapped from the untapped Tomb (1 x 2), leaves it none. Each half alone, too; and nothing is asked of the play
     * while the painless sources cover the debt. An amount that cannot be read as a number refuses the play while the
     * cover needs pain, at any life (T2 review finding 4): a {@code PayLife<X>}, an X mana cost beside an untapped
     * painful source, an own LoseLife of X.
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
            ai.setLife(20, null);
            List<String> unread = unreadableLifeVerdicts();
            assertEquals(unread, Arrays.asList(DelayedLoss.DEBT_UNPROTECTED, DelayedLoss.DEBT_UNPROTECTED,
                    DelayedLoss.DEBT_UNPROTECTED), "at 20 life the Tomb fits, but no number can be budgeted: Toxic"
                    + " Deluge's PayLife<X>, Stroke of Genius's {X} beside the untapped Tomb, Reanimate's own LoseLife"
                    + " X; verdicts " + unread);

            put("Island", 2, ai, ZoneType.Battlefield);
            ai.setLife(3, null);
            assertNull(problemOf(spell("Vampiric Tutor")), "five Islands cover 3UU without the Tomb: nothing to budget");
            ai.setLife(20, null);
            unread = unreadableLifeVerdicts();
            assertEquals(unread, Arrays.asList(null, null, null), "nor for the three plays whose life cannot be read"
                    + "; verdicts " + unread);
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
     * RULINGS-R3 M17, T2 review finding 2: a play's own life loss is read where it hangs under an additional ability,
     * not only along its sub-abilities. A pending Pact of Negation debt (3UU), Island x3 and Ancient Tomb, tapped now
     * (no mana of the play's can cost life), the opponent's end step: the cover needs the Tomb. Ad Nauseam's loss (X,
     * each card's mana value) sits under its Repeat's RepeatSubAbility: not a number. Hoarder's Greed's 2 life sits
     * under a Repeat that runs while it wins its clashes: no number of times. Abzan Charm's 2 life is under one of its
     * Choices, a number: refused at 6 life (6 - 2 = 4, and the Tomb's 2 + 3 < 4 fails), allowed at 40. With Island x5
     * the painless sources cover 3UU and nothing is budgeted.
     */
    @Test(timeOut = 300_000)
    public void anOwnLifeLossUnderAnAdditionalAbilityIsRead() {
        Scoped.under(new SimScope(83L), () -> {
            resolveAPactOfNegation();
            put("Island", 3, ai, ZoneType.Battlefield);
            put("Ancient Tomb", ai, ZoneType.Battlefield).setTapped(true);
            at(PhaseType.END_OF_TURN, opp);
            ai.setLife(20, null);
            assertEquals(DelayedLoss.count(ai, DelayedLoss.Due.NEXT, DelayedLoss.Removal.NONE).units().size(), 5,
                    "the Tomb untaps for the upkeep and fits at 20 life: 3 + 2 for 3UU");
            final List<String> verdicts = new ArrayList<>();
            verdicts.add(problemOf(spell("Ad Nauseam")));
            verdicts.add(problemOf(spell("Hoarder's Greed")));
            ai.setLife(6, null);
            verdicts.add(problemOf(spell("Abzan Charm")));
            ai.setLife(40, null);
            verdicts.add(problemOf(spell("Abzan Charm")));
            put("Island", 2, ai, ZoneType.Battlefield);
            ai.setLife(20, null);
            verdicts.add(problemOf(spell("Ad Nauseam")));
            verdicts.add(problemOf(spell("Hoarder's Greed")));
            assertEquals(verdicts, Arrays.asList(DelayedLoss.DEBT_UNPROTECTED, DelayedLoss.DEBT_UNPROTECTED,
                    DelayedLoss.DEBT_UNPROTECTED, null, null, null), "Island x3 and the Tomb: Ad Nauseam's X under its"
                    + " Repeat, Hoarder's Greed's 2 life repeated while it wins its clashes, Abzan Charm's mode's 2 life"
                    + " at 6 life and at 40; Island x5: Ad Nauseam, Hoarder's Greed; verdicts " + verdicts);
            return null;
        });
    }

    /**
     * RULINGS-R3 M18, T2 review finding 2: a board-wide removal under an additional ability is read too. A pending
     * Summoner's Pact debt (2GG): Austere Command's modes are its Choices, and one destroys every artifact, Sol Ring
     * among them; every mode is read, whichever two the AI would choose.
     */
    @Test(timeOut = 300_000)
    public void aBoardWideRemovalUnderAnAdditionalAbilityTakesTheSourcesItMatches() {
        for (final int forests : new int[] { 2, 4 }) {
            Scoped.under(new SimScope(89L), () -> {
                resolveASummonersPact();
                put("Forest", forests, ai, ZoneType.Battlefield);
                put("Sol Ring", ai, ZoneType.Battlefield);
                final String v = problemOf(spell("Austere Command"));
                if (forests == 2) {
                    assertEquals(v, DelayedLoss.DEBT_UNPROTECTED,
                            "its artifact mode takes Sol Ring, whose CC the debt needs: two Forests are left");
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

    // --------------------------------------------------------------------------------------------- resolution sites

    /**
     * S5 and S5b's predicate (DESIGN.md D13): an optional free cast of a flagged card is declined, covered or not, and
     * so is a copy of one; while a debt is pending, so is a free cast with a cost part beyond its waived mana. No mana
     * is counted.
     */
    @Test
    public void aFreeCastIsDeclinedForAFlaggedCardACopyOrACostBeyondManaWhileADebtIsPending() {
        opponentsTurn();
        put("Island", 10, ai, ZoneType.Battlefield);
        assertTrue(DelayedLoss.declinesFreeCast(ai, spell("Pact of Negation")), "a Pact, though ten Islands cover it");
        assertTrue(DelayedLoss.declinesFreeCast(ai, spell("Final Fortune")), "an extra turn that loses");
        assertTrue(DelayedLoss.declinesFreeCast(ai, spell("Glorious End")), "a loss at an end step");
        assertFalse(DelayedLoss.declinesFreeCast(ai, spell("Lightning Bolt")), "a play nothing flags");
        assertFalse(DelayedLoss.declinesFreeCast(ai, spell("Crop Rotation")), "a sacrifice, while no debt is pending");
        final SpellAbility fortune = onStack("Final Fortune", ai, null);
        final SpellAbility twincast = spell("Twincast");
        twincast.getTargets().add(fortune);
        assertTrue(DelayedLoss.declinesFreeCast(ai, twincast), "a copy of Final Fortune");

        opponentsTurn();
        put("Island", 10, ai, ZoneType.Battlefield);
        onStack("Pact of Negation", ai, onStack("Grizzly Bears", opp, null)); // a debt due at the next upkeep
        assertTrue(DelayedLoss.declinesFreeCast(ai, spell("Crop Rotation")), "a sacrifice while a debt is pending");
        assertTrue(DelayedLoss.declinesFreeCast(ai, alternative(spell("Force of Will"), CostPayLife.class)),
                "life and a card while a debt is pending");
        assertFalse(DelayedLoss.declinesFreeCast(ai, spell("Lightning Bolt")), "nothing beyond the waived mana");
    }

    /**
     * S6's predicate (DESIGN.md D28): the AI's own pay-or-lose debt by its shape, an own LosesGame with an UnlessCost$,
     * read through the trigger's wrapper or on the ability it wraps; S6b never declines it. At the payment the ability
     * asked is the LosesGame the trigger wraps (WrappedAbility.resolve plays it, AbilityUtils.handleUnlessCost passes it
     * to payCostToPreventEffect), while the trigger itself, still on the stack until it resolves, is a debt due now:
     * only the exemption keeps that debt from refusing its own payment (T2 review finding 1).
     */
    @Test(timeOut = 300_000)
    public void theAIsOwnPactDebtIsReadByItsShape() {
        Scoped.under(new SimScope(67L), () -> {
            final SpellAbility trigger = upkeepWithThePactsTrigger();
            put("Island", 3, ai, ZoneType.Battlefield);
            assertTrue(DelayedLoss.isOwnPactDebt(ai, trigger), "the trigger on the stack, through its wrapper");
            assertTrue(DelayedLoss.isOwnPactDebt(ai, ((WrappedAbility) trigger).getWrappedAbility()),
                    "the LosesGame the unless payment is made for");
            assertFalse(DelayedLoss.isOwnPactDebt(opp, trigger), "the AI's debt, not the opponent's");
            assertFalse(DelayedLoss.isOwnPactDebt(ai, spell("Lightning Bolt")), "a play nothing flags");
            assertFalse(DelayedLoss.isOwnPactDebt(ai, null), "nothing");
            assertFalse(DelayedLoss.declinesUnlessPayment(ai, trigger, new Cost("3 U U", true)),
                    "S6b never declines the AI's own debt, though three Islands cannot pay it");
            assertFalse(DelayedLoss.declinesUnlessPayment(ai, ((WrappedAbility) trigger).getWrappedAbility(),
                    new Cost("3 U U", true)), "nor at the payment, asked of the LosesGame the trigger wraps: the trigger"
                    + " left on the stack is the debt due now, and three Islands do not cover it");
            return null;
        });
    }

    /**
     * S6b's predicate (DESIGN.md D32, round 2's B1): an unless cost the AI is about to pay is declined when paying it
     * would spend a debt due now, whichever sources the payment taps, or uncover a pending debt; its mana is free for a
     * debt due next upkeep; and the asking ability's own effects are not read, as a play's are.
     */
    @Test(timeOut = 300_000)
    public void anUnlessPaymentTheDebtCannotSpareIsDeclined() {
        for (final int islands : new int[] { 5, 6 }) {
            Scoped.under(new SimScope(71L), () -> {
                upkeepWithThePactsTrigger();
                put("Island", islands, ai, ZoneType.Battlefield);
                final SpellAbility leak = put("Mana Leak", opp, ZoneType.Hand).getFirstSpellAbility();
                leak.setActivatingPlayer(opp);
                final boolean declined = DelayedLoss.declinesUnlessPayment(ai, leak, new Cost("1", true));
                if (islands == 5) {
                    assertTrue(declined, "the trigger due now needs all five Islands: 5 - 1 < 5");
                } else {
                    assertFalse(declined, "a sixth Island to spare");
                }
                return null;
            });
        }
        Scoped.under(new SimScope(73L), () -> {
            resolveAPactOfNegation();
            put("Island", 5, ai, ZoneType.Battlefield);
            final SpellAbility leak = put("Mana Leak", opp, ZoneType.Hand).getFirstSpellAbility();
            leak.setActivatingPlayer(opp);
            assertFalse(DelayedLoss.declinesUnlessPayment(ai, leak, new Cost("1", true)),
                    "due at the next upkeep, the {1} costs nothing: the Island untaps");
            assertTrue(DelayedLoss.declinesUnlessPayment(ai, leak, new Cost("Sac<1/Land>", true)),
                    "a sacrificed land would uncover 3UU");
            return null;
        });
        Scoped.under(new SimScope(79L), () -> {
            resolveAPactOfNegation();
            put("Island", 3, ai, ZoneType.Battlefield);
            put("Ancient Tomb", ai, ZoneType.Battlefield);
            ai.setLife(6, null);
            final SpellAbility tutor = spell("Vampiric Tutor");
            assertEquals(problemOf(tutor), DelayedLoss.DEBT_UNPROTECTED, "a play's own 2 life is budgeted (M17)");
            assertFalse(DelayedLoss.declinesUnlessPayment(ai, tutor, new Cost("0", true)),
                    "the asking ability's effects are not read: an unless cost of {0} takes nothing");
            return null;
        });
        opponentsTurn();
        put("Island", 5, ai, ZoneType.Battlefield);
        final SpellAbility leak = put("Mana Leak", opp, ZoneType.Hand).getFirstSpellAbility();
        leak.setActivatingPlayer(opp);
        assertFalse(DelayedLoss.declinesUnlessPayment(ai, leak, new Cost("Sac<1/Land>", true)),
                "no debt: nothing to protect");
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
