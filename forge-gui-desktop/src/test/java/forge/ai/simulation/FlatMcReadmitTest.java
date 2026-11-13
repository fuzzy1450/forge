package forge.ai.simulation;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.testng.annotations.Test;

import forge.ai.ComputerUtilAbility;
import forge.ai.SpecialCardAi;
import forge.ai.simulation.FlatMonteCarloPicker.Result;
import forge.game.card.Card;
import forge.game.phase.PhaseType;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import forge.util.SimScope;

/**
 * The fork's RemoveDeck readmit rows under full Flat MC (MTG_DeckMaker's tools/combo-ai/flat-mc/SHIP-PLAN.md, Task 4's
 * fix round 1, the ledger's option b). A full seat, the default word's, keeps upstream's routing of a card marked
 * AI:RemoveDeck:All, the filter keeping it and discovery offering it to the rollouts while the heuristics never approve
 * it, except where the card's own row readmits the ability now (SpecialCardAi.RemoveDeckFilter.readmit): the walk then
 * judges it as it did with Flat MC off, and every copy the walk makes of it with another cost as the ability itself;
 * and the filter asks the row first all the same, so the memo a row keeps for the walk's sort is set. Every board is
 * the seat's own Default profile with the AI deadline off.
 */
public class FlatMcReadmitTest extends FlatMcTestBase {
    private static final String BAUBLE = "Mishra's Bauble";

    /** The seat under test as the sim runner seats a default word: Flat MC switched on for it, and its full seat. */
    private void fullSeat() {
        setUpGame();
        profile("Default");
        brain().setFlatMonteCarloOverride(true);
        brain().setFlatMcSeat(FlatMcSeat.parse("default"));
        noDeadline();
        put("Island", 10, ai, ZoneType.Library); // DrawAi's slowtrip guard reads a library of 5 or more
        put("Island", 10, opp, ZoneType.Library);
    }

    /** The seat's own first main phase, the board's statics applied. */
    private void ownMainPhase() {
        at(PhaseType.MAIN1, ai);
        game.getAction().checkStateEffects(true);
        assertTrue(brain().flatMonteCarloActive(), "a full seat plays Flat MC");
    }

    /** The spell that casts {@code c}, its activator its controller, as the walk's expansion stamps every ability it
     *  judges (getOriginalAndAltCostAbilities) and a row's mana check needs (calculateManaCost). */
    private static SpellAbility castOf(final Card c) {
        for (final SpellAbility sa : c.getSpellAbilities()) {
            if (sa.isSpell()) {
                sa.setActivatingPlayer(c.getController());
                return sa;
            }
        }
        throw new AssertionError(c + " has no spell");
    }

    /** What the ability's own row answers now, asked on a thread of its own: a row that keeps a memo for the sort
     *  (Cruel Entertainment's) keeps it on that thread, never on one a test reads the memo on. */
    private boolean rowReadmits(final SpellAbility sa) {
        return Scoped.under(new SimScope(1L), () -> SpecialCardAi.RemoveDeckFilter.readmit(ai, sa));
    }

    /** The walk of AiController (chooseSpellAbilitiesToPlayFromList) over {@code plays}, as the main-phase search calls
     *  it with discovery on: the plays the heuristics approve, the discovered ones added to {@code extras}. */
    @SuppressWarnings("unchecked")
    private List<SpellAbility> walk(final List<SpellAbility> plays, final List<SpellAbility> extras) {
        return (List<SpellAbility>) Reflect.call(brain(), "chooseSpellAbilitiesToPlayFromList", new ArrayList<>(plays),
                true, 4, extras);
    }

    /**
     * The probe's case (Task 4's report: FlatMonteCarloVintageSmokeTest's landless Red Prison hand, whose one play was
     * the Bauble): Mishra's Bauble in hand in the seat's own first main phase, its row's window open (row 20A: our own
     * hand cast at {0}), nothing else to play. The walk approves the cast as it did with Flat MC off and offers it to
     * no discovery as well, and the game loop sees the seat cast it in that main phase. At 96220078349 the walk kept
     * the heuristics off it (isFlatMcOnlyPlay) and the probe's seat kept it in hand 3 times of 3.
     */
    @Test(timeOut = 300_000)
    public void aFullSeatsWalkApprovesTheReadmittedBaubleAndCastsIt() {
        fullSeat();
        final SpellAbility cast = castOf(put(BAUBLE, ai, ZoneType.Hand));
        ownMainPhase();
        assertTrue(rowReadmits(cast), "row 20's window is open: our own {0} hand cast in our own main phase");

        final List<SpellAbility> extras = new ArrayList<>();
        final List<SpellAbility> approved = walk(List.of(cast), extras);
        assertTrue(approved.contains(cast), "the walk approves the readmitted cast; approved " + approved
                + ", discovered " + extras);
        assertFalse(extras.contains(cast), "an approved line is not a discovered one as well: " + extras);

        game.getPhaseHandler().onStackResolved();
        Scoped.under(new SimScope(11L), () -> {
            for (int i = 0; i < 60 && !game.isGameOver() && countCardsWithName(game, BAUBLE) == 0
                    && game.getPhaseHandler().is(PhaseType.MAIN1); i++) {
                game.getPhaseHandler().mainLoopStep();
            }
            return null;
        });
        assertEquals(countCardsWithName(game, BAUBLE), 1, "the seat casts it in its own main phase:\n"
                + gameStateToString(game));
    }

    /**
     * isFlatMcOnlyPlay as a full seat reads it: false for an ability its own row readmits now (the Bauble's cast at {0}
     * in our own main phase); true for a hinted card no row names (Urza's Bauble) and for a row's card outside its
     * window (the Bauble taxed {1} by the opponent's Thorn of Amethyst); false for every card with Flat MC off.
     */
    @Test(timeOut = 120_000)
    public void isFlatMcOnlyPlayAnswersAsTheCardsOwnRowDoes() {
        fullSeat();
        final SpellAbility readmitted = castOf(put(BAUBLE, ai, ZoneType.Hand));
        final SpellAbility noRow = castOf(put("Urza's Bauble", ai, ZoneType.Hand));
        ownMainPhase();
        assertTrue(rowReadmits(readmitted));
        assertFalse(rowReadmits(noRow), "Urza's Bauble has no readmit row");
        assertFalse(brain().isFlatMcOnlyPlay(readmitted), "its own row readmits it now");
        assertTrue(brain().isFlatMcOnlyPlay(noRow), "no row readmits it: a play for the rollouts alone");

        put("Thorn of Amethyst", opp, ZoneType.Battlefield);
        game.getAction().checkStateEffects(true);
        assertFalse(rowReadmits(readmitted), "taxed {1}: outside row 20's window");
        assertTrue(brain().isFlatMcOnlyPlay(readmitted), "outside its row's window: a play for the rollouts alone");

        brain().setFlatMonteCarloOverride(false);
        assertFalse(brain().flatMonteCarloActive());
        assertFalse(brain().isFlatMcOnlyPlay(noRow), "with Flat MC off the filter alone keeps a hinted card off");
    }

    /**
     * Every other hinted card keeps upstream's routing in the walk: the heuristics never approve it, and discovery
     * offers it to the rollouts. Urza's Bauble has no row; the Mishra's Bauble beside it is outside its row's window,
     * taxed {1} by the opponent's Thorn of Amethyst, with two Islands of ours to pay either.
     */
    @Test(timeOut = 120_000)
    public void everyOtherHintedCardIsOnlyAPlayForTheRolloutsInTheWalk() {
        fullSeat();
        final SpellAbility noRow = castOf(put("Urza's Bauble", ai, ZoneType.Hand));
        final SpellAbility outside = castOf(put(BAUBLE, ai, ZoneType.Hand));
        put("Island", 2, ai, ZoneType.Battlefield);
        put("Thorn of Amethyst", opp, ZoneType.Battlefield);
        ownMainPhase();
        assertFalse(rowReadmits(noRow), "Urza's Bauble has no readmit row");
        assertFalse(rowReadmits(outside), "taxed {1}: outside row 20's window");

        final List<SpellAbility> extras = new ArrayList<>();
        final List<SpellAbility> approved = walk(List.of(noRow, outside), extras);
        assertTrue(approved.isEmpty(), "the heuristics approve neither: " + approved);
        assertTrue(extras.contains(noRow) && extras.contains(outside),
                "both are discovered for the rollouts: " + extras);
    }

    /**
     * Under Flat MC the filter keeps a hinted card whatever its row answers, as upstream's RemoveDeck change does:
     * the seat's main-phase search, counted under a scope as in a served game, compares Urza's Bauble (no row) with
     * passing.
     */
    @Test(timeOut = 300_000)
    public void theFilterLeavesAHintedCardItsRowDoesNotReadmitToTheSearch() {
        fullSeat();
        final SpellAbility noRow = castOf(put("Urza's Bauble", ai, ZoneType.Hand));
        ownMainPhase();
        assertFalse(rowReadmits(noRow), "Urza's Bauble has no readmit row");

        Scoped.under(new SimScope(11L), brain()::chooseSpellAbilityToPlay);
        final Result result = brain().getLastFlatMonteCarloResult();
        assertNotNull(result, "no search: the filter dropped the hinted card, or discovery did not offer it");
        assertTrue(result.candidatesEvaluated >= 2, "passing and Urza's Bauble compared: " + result);
    }

    /**
     * Under Flat MC the filter asks a hinted card's row as it does with Flat MC off, so the memo the row keeps for the
     * walk's sort is set (ComputerUtilAbility.getSpellAbilityPriority's bonuses): Cruel Entertainment in its kill
     * window (row 21: our own main phase, no spell cast yet this turn, Vial Smasher the Fierce in play, the one
     * opponent at 6, {6}{B} in Swamps). The decision runs on a thread of its own and the memo is read there after it:
     * the window's bonus and the kill's. Nothing else asks the row on that thread: the walk asks it on its evaluation
     * thread, and with nothing to compare no rollout runs.
     */
    @Test(timeOut = 300_000)
    public void underFlatMcTheFilterSetsTheMemoOfTheRow() {
        fullSeat();
        final SpellAbility cruel = castOf(put("Cruel Entertainment", ai, ZoneType.Hand));
        put("Vial Smasher the Fierce", ai, ZoneType.Battlefield);
        put("Swamp", 7, ai, ZoneType.Battlefield);
        opp.setLife(6, null);
        ownMainPhase();
        assertTrue(rowReadmits(cruel), "row 21's kill window is open");

        final int bonus = Scoped.under(new SimScope(11L), () -> {
            brain().chooseSpellAbilityToPlay();
            return SpecialCardAi.CruelEntertainment.sortBonus(ai, cruel);
        });
        assertEquals(bonus, SpecialCardAi.CruelEntertainment.SORT_WINDOW + SpecialCardAi.CruelEntertainment.SORT_LETHAL,
                "the filter set the row's memo for the sort");
    }

    /**
     * A copy the walk makes of a readmitted ability with another cost is judged as that ability: Misdirection's row
     * readmits its mana spell, the one ability its filter sees, and answers false for the pitch copy that
     * getOriginalAndAltCostAbilities makes from it; N's walk judged the copy with the spell. The opponent's Lightning
     * Bolt waits at us, at 3 life; Misdirection and Opt are in our hand and no land: the mana spell cannot be paid, the
     * pitch (Opt) can, and the walk approves the pitch copy.
     */
    @Test(timeOut = 120_000)
    public void aCopyOfAReadmittedAbilityIsJudgedAsTheAbilityItself() {
        fullSeat();
        final SpellAbility spell = castOf(put("Misdirection", ai, ZoneType.Hand));
        put("Opt", ai, ZoneType.Hand);
        ai.setLife(3, null);
        at(PhaseType.MAIN1, opp);
        boltAtUs();
        assertTrue(rowReadmits(spell),
                "row 40's window is open: the opponent's lethal Bolt at us, a blue card to pitch");
        assertFalse(rowReadmits(pitchCopyOf(spell)), "the row answers for the mana spell alone");

        final List<SpellAbility> extras = new ArrayList<>();
        final List<SpellAbility> approved = walk(List.of(spell), extras);
        assertEquals(approved.size(), 1, "the pitch copy alone, the mana spell unpaid; approved " + approved
                + ", discovered " + extras);
        assertEquals(approved.get(0).getHostCard().getName(), "Misdirection");
        assertTrue(approved.get(0).getPayCosts().getTotalMana().isZero(), "the pitch copy: " + approved.get(0));
    }

    /** The opponent has cast Lightning Bolt at us, and it waits on the stack (FlatMonteCarloSavingTest's boltAt). */
    private void boltAtUs() {
        final Card bolt = put("Lightning Bolt", opp, ZoneType.Hand);
        final SpellAbility sa = castOf(bolt);
        sa.setActivatingPlayer(opp);
        final Card onStack = game.getAction().moveToStack(bolt, sa);
        sa.setHostCard(onStack);
        sa.getTargets().add(ai);
        game.getStack().add(sa);
    }

    /** The pitch copy getOriginalAndAltCostAbilities makes of Misdirection's mana spell (a fresh one, for its row). */
    private SpellAbility pitchCopyOf(final SpellAbility spell) {
        for (final SpellAbility sa : ComputerUtilAbility.getOriginalAndAltCostAbilities(new ArrayList<>(List.of(spell)),
                ai)) {
            if (sa != spell && sa.getPayCosts().getTotalMana().isZero()) {
                return sa;
            }
        }
        throw new AssertionError("no pitch copy of " + spell);
    }
}
