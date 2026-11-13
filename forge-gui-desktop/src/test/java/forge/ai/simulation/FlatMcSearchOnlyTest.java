package forge.ai.simulation;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertSame;
import static org.testng.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import forge.ai.AiController;
import forge.ai.AiProps;
import forge.ai.simulation.FlatMonteCarloPicker.Config;
import forge.ai.simulation.FlatMonteCarloPicker.Result;
import forge.game.card.Card;
import forge.game.phase.PhaseType;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import forge.util.SimScope;

/**
 * What a flat_mc seat's search-only mark turns off inside the search path, the hold at the loop driver's hand-over, and
 * the zero-rollout seat (MTG_DeckMaker's tools/combo-ai/flat-mc/SPEC.md, sections 4.2 and 4.7). ComboGuard and the fuel
 * order can each move line 0 away from the default seat's own play, so the mark turns both off; the main-phase search
 * forbids the hold while the loop driver has handed the decision to the walk, where a pass reads as the walk choosing
 * nothing; and a zero-rollout seat takes line 0 where the picker would roll out. A full seat, the default word's, keeps
 * the guard and the fuel order, as plain Flat MC does (SHIP.md beside that spec, design 2). Private controller methods
 * are reached through {@link Reflect}, and every board runs with the AI deadline off.
 */
public class FlatMcSearchOnlyTest extends FlatMcTestBase {
    /** FlatMonteCarloGraveyardLandsTest's dredge library: the deck plan reads DREDGE from it. */
    private static final Object[] DREDGE_DECK = { "Stinkweed Imp", 4, "Golgari Grave-Troll", 4, "Life from the Loam", 3,
            "Bridge from Below", 4, "Narcomoeba", 4, "Prized Amalgam", 4, "Ichorid", 4, "Faithless Looting", 4,
            "Cathartic Reunion", 4, "Swamp", 12 };

    @BeforeMethod
    public void searchProfilesAndABoard() {
        FlatMcProfiles.install();
        setUpGame();
        noDeadline();
    }

    @AfterClass
    public void shippedProfiles() {
        FlatMcProfiles.restore();
    }

    /** Opt is a cantrip, which a default seat's guard keeps while a win looks near; a search-only seat has no guard. */
    @Test
    public void theGuardPassesEveryPlayOfASearchOnlySeat() {
        final SpellAbility opt = put("Opt", ai, ZoneType.Hand).getSpellAbilities().get(0);
        assertEquals(Reflect.call(brain(), "guardProblem", opt, null, true), "cantrip-saved");
        brain().setFlatMcSeat(FlatMcSeat.parse("flat_mc"));
        assertNull(Reflect.call(brain(), "guardProblem", opt, null, true));
    }

    /** A full seat is no search-only seat: its guard still keeps Opt while a win looks near. */
    @Test
    public void theGuardStandsForAFullSeat() {
        final SpellAbility opt = put("Opt", ai, ZoneType.Hand).getSpellAbilities().get(0);
        brain().setFlatMcSeat(FlatMcSeat.parse("default"));
        assertEquals(Reflect.call(brain(), "guardProblem", opt, null, true), "cantrip-saved");
    }

    /**
     * A dredge deck with a dredger in hand puts its fuel first, Faithless Looting ahead of Grizzly Bears; a search-only
     * seat gets its approved plays back as they came, the same list.
     */
    @Test
    public void fuelOrderIsLeftAloneForASearchOnlySeat() {
        for (int i = 0; i < DREDGE_DECK.length; i += 2) {
            put((String) DREDGE_DECK[i], (Integer) DREDGE_DECK[i + 1], ai, ZoneType.Library);
        }
        put("Stinkweed Imp", ai, ZoneType.Hand);
        final List<SpellAbility> approved = new ArrayList<>();
        approved.add(spell(put("Grizzly Bears", ai, ZoneType.Hand)));
        approved.add(spell(put("Faithless Looting", ai, ZoneType.Hand)));
        assertEquals(firstName(Reflect.call(brain(), "fuelPlaysFirst", approved, new ArrayList<>())),
                "Faithless Looting");
        brain().setFlatMcSeat(FlatMcSeat.parse("flat_mc"));
        assertSame(Reflect.call(brain(), "fuelPlaysFirst", approved, new ArrayList<>()), approved);
    }

    /** A full seat orders a dredge deck's plays as plain Flat MC does: Faithless Looting ahead of Grizzly Bears. */
    @Test
    public void aFullSeatPutsItsFuelFirst() {
        for (int i = 0; i < DREDGE_DECK.length; i += 2) {
            put((String) DREDGE_DECK[i], (Integer) DREDGE_DECK[i + 1], ai, ZoneType.Library);
        }
        put("Stinkweed Imp", ai, ZoneType.Hand);
        final List<SpellAbility> approved = new ArrayList<>();
        approved.add(spell(put("Grizzly Bears", ai, ZoneType.Hand)));
        approved.add(spell(put("Faithless Looting", ai, ZoneType.Hand)));
        brain().setFlatMcSeat(FlatMcSeat.parse("default"));
        assertEquals(firstName(Reflect.call(brain(), "fuelPlaysFirst", approved, new ArrayList<>())),
                "Faithless Looting");
    }

    /**
     * The hold is forbidden at the hand-over, as land choice forbids it, and allowed again once the driver has heard
     * the walk. With no decision budget there is no search, so nothing to forbid.
     */
    @Test
    public void theHoldIsForbiddenOnlyWhileTheLoopDriverHasHandedOver() {
        profile("FlatSearch");
        brain().setFlatMcSeat(FlatMcSeat.parse("flat_mc"));
        brain().setFlatMonteCarloOverride(true);
        at(PhaseType.MAIN1, ai);
        put("Counterspell", ai, ZoneType.Hand);
        put("Island", 2, ai, ZoneType.Hand);
        Reflect.set(brain().getLoopDriver(), "handedToWalk", true);
        final Config at = (Config) Reflect.call(brain(), "mainPhaseConfig");
        assertTrue(at.holdForbidden);
        assertFalse(at.considerHolding);
        Reflect.set(brain().getLoopDriver(), "handedToWalk", false);
        final Config away = (Config) Reflect.call(brain(), "mainPhaseConfig");
        assertFalse(away.holdForbidden);
        assertTrue(away.considerHolding);
        profile("FlatSearchOff");
        Reflect.set(brain().getLoopDriver(), "handedToWalk", true);
        assertNull(Reflect.call(brain(), "mainPhaseConfig"), "no decision budget: no search");
    }

    /**
     * The same two bears three times, each decided under a scope of seed 11: first a default seat's play; then a
     * zero-rollout seat's, which gets to the picker's call site (two approved plays), takes line 0 there and rolls
     * nothing out; last a full-count seat's, which does roll out there, so the zero-rollout result is that mark's
     * alone.
     */
    @Test
    public void aZeroRolloutSeatPlaysTheDefaultSeatsChoiceWithoutARollout() {
        profile("Default");
        assertEquals(twoBears().size(), 2);
        final AiController defaultSeat = brain();
        final List<SpellAbility> defaultChoice = Scoped.under(new SimScope(11L), defaultSeat::chooseSpellAbilityToPlay);
        assertNotNull(defaultChoice, "the default seat plays a bear");

        setUpGame();
        profile("FlatSearch");
        assertEquals(twoBears().size(), 2);
        brain().setFlatMonteCarloOverride(true);
        brain().setFlatMcSeat(FlatMcSeat.parse("flat_mc_zero"));
        final AiController zeroSeat = brain();
        final int rolloutsBefore = FlatMonteCarloPicker.getTotalRollouts();
        final List<SpellAbility> zeroChoice = Scoped.under(new SimScope(11L), zeroSeat::chooseSpellAbilityToPlay);

        assertEquals(zeroChoice.get(0).getHostCard().getName(), defaultChoice.get(0).getHostCard().getName());
        final Result result = zeroSeat.getLastFlatMonteCarloResult();
        assertEquals(result.reason, "zero-rollout", String.valueOf(result));
        assertEquals(FlatMonteCarloPicker.getTotalRollouts(), rolloutsBefore);

        setUpGame();
        profile("FlatSearch");
        assertEquals(twoBears().size(), 2);
        brain().setFlatMonteCarloOverride(true);
        brain().setFlatMcSeat(FlatMcSeat.parse("flat_mc"));
        final AiController fullCountSeat = brain();
        Scoped.under(new SimScope(11L), fullCountSeat::chooseSpellAbilityToPlay);
        final Result searched = fullCountSeat.getLastFlatMonteCarloResult();
        assertTrue(searched.rollouts > 0, String.valueOf(searched));
    }

    /**
     * Each of FlatMcProfiles' four profiles reads as Default with its lines: every property a seat reads under it is
     * the one it reads under Default, the appended keys excepted, which read as appended; and its file is LF only and
     * ends with exactly its lines. A trailing space or a lost base would otherwise play AiProps' defaults silently.
     */
    @Test
    public void eachPrivateProfileIsDefaultWithItsLines() throws IOException {
        final List<String> search = List.of("FLAT_MC_DISCOVERY=false", "FLAT_MC_LAND_CHOICE=false",
                "FLAT_MC_REACTIVE=false");
        assertProfile("FlatSearch", search);
        assertProfile("FlatSearchBudget", search, "FLAT_MC_DECISION_BUDGET_MS=18", "FLAT_MC_TURN_BUDGET_MS=80");
        assertProfile("FlatSearchOff", search, "FLAT_MC_DECISION_BUDGET_MS=0");
        assertProfile("FlatSearchTight", search, "FLAT_MC_DECISION_BUDGET_MS=2", "FLAT_MC_TURN_BUDGET_MS=6");
    }

    private void assertProfile(final String name, final List<String> search, final String... budget)
            throws IOException {
        final List<String> lines = new ArrayList<>(search);
        lines.addAll(List.of(budget));
        final Map<AiProps, String> expected = properties("Default");
        for (final String line : lines) {
            final String[] keyValue = line.split("=");
            expected.put(AiProps.valueOf(keyValue[0]), keyValue[1]);
        }
        assertEquals(properties(name), expected, name);
        final String text = Files.readString(new File(FlatMcProfiles.directory(), name + ".ai").toPath(),
                StandardCharsets.UTF_8);
        assertFalse(text.contains("\r"), name + " is LF only");
        assertTrue(text.endsWith("\n" + String.join("\n", lines) + "\n"), name + " ends with exactly its lines");
    }

    /** Every property the seat under test reads under the profile {@code name}. */
    private Map<AiProps, String> properties(final String name) {
        profile(name);
        final Map<AiProps, String> read = new EnumMap<>(AiProps.class);
        for (final AiProps p : AiProps.values()) {
            read.put(p, brain().getProperty(p));
        }
        return read;
    }

    /** The card's first spell, activated by the seat under test, as the AI's own lists hold it. */
    private SpellAbility spell(final Card c) {
        final SpellAbility sa = c.getSpellAbilities().get(0);
        sa.setActivatingPlayer(ai);
        return sa;
    }

    private static String firstName(final Object plays) {
        return ((SpellAbility) ((List<?>) plays).get(0)).getHostCard().getName();
    }
}
