package forge.ai;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import org.testng.AssertJUnit;
import org.testng.annotations.Test;

import com.google.common.eventbus.Subscribe;

import forge.ai.simulation.ComboPlan;
import forge.ai.simulation.DelayedLoss;
import forge.game.Game;
import forge.game.card.Card;
import forge.game.event.GameEventCardTapped;
import forge.game.phase.PhaseHandler;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;

/**
 * RU-29 (review selfkill-mana F1): the self-kill count never passes a debt the AI's own upkeep payment cannot pay.
 * Each row asks both halves on one board: DelayedLoss's verdict on Summoner's Pact cast in the AI's first main phase,
 * then the Pact resolved and the game played through the AI's next upkeep, where its trigger asks for {2}{G}{G} and the
 * AI's payer answers. A board the count allows is one the payer pays: the AI is still in the game.
 */
public class DelayedLossUpkeepPaymentTest extends AITest {

    private static Player opponentOf(final Game game, final Player ai) {
        return game.getPlayers().get(0) == ai ? game.getPlayers().get(1) : game.getPlayers().get(0);
    }

    /** The AI deadline off, as served games have it. */
    private static void noDeadline(final Game game) {
        game.AI_TIMEOUT = Integer.MAX_VALUE;
        game.AI_CAN_USE_TIMEOUT = false;
    }

    /** A card cast as far as the stack by {@code p}, with nothing paid. */
    private SpellAbility onStack(final Game game, final String name, final Player p) {
        final Card c = createCard(name, p);
        c.setGameTimestamp(game.getNextTimestamp());
        game.getStackZone().add(c);
        final SpellAbility sa = c.getFirstSpellAbility();
        sa.setActivatingPlayer(p);
        game.getStack().add(sa);
        return sa;
    }

    /** How many of these cards are tapped. */
    private static int tapped(final List<Card> cards) {
        int n = 0;
        for (final Card c : cards) {
            n += c.isTapped() ? 1 : 0;
        }
        return n;
    }

    /** Plays on from turn {@code turn} until the AI's next upkeep is over, or the game is. */
    private static void playThroughTheNextUpkeep(final Game game, final Player ai, final int turn) {
        final PhaseHandler ph = game.getPhaseHandler();
        for (int i = 0; i < 2000 && !game.isGameOver() && !(ph.isPlayerTurn(ai) && ph.getTurn() > turn
                && ph.getPhase() != null && ph.getPhase().isAfter(PhaseType.UPKEEP)); i++) {
            ph.mainLoopStep();
        }
    }

    /**
     * The AI's first main phase with {@code lands} and {@code filters} on its battlefield and green creatures in its
     * library: [the count's verdict on Summoner's Pact from hand, whether the AI lost by the end of its next upkeep,
     * how many of the filters were tapped then]. Flat Monte Carlo off, as the test game has it.
     */
    private List<Object> castAndPay(final List<String> lands, final List<String> filters) {
        return castAndPay(lands, filters, new ArrayList<>());
    }

    /** The cards of the AI's that were tapped during its upkeep, in the order they were tapped. */
    public static final class UpkeepTaps {
        private final Game game;
        private final Player ai;
        final List<String> names = new ArrayList<>();

        UpkeepTaps(final Game game, final Player ai) {
            this.game = game;
            this.ai = ai;
        }

        @Subscribe
        public void onTapped(final GameEventCardTapped e) {
            final PhaseHandler ph = game.getPhaseHandler();
            if (e.tapped() && ph.isPlayerTurn(ai) && ph.is(PhaseType.UPKEEP) && e.card() != null) {
                names.add(e.card().getName());
            }
        }
    }

    /** {@link #castAndPay(List, List)}, the names of the cards the AI tapped in its upkeep added to
     *  {@code tapOrder}. */
    private List<Object> castAndPay(final List<String> lands, final List<String> filters,
                                    final List<String> tapOrder) {
        final Game game = initAndCreateGame();
        noDeadline(game);
        final Player ai = game.getPlayers().get(1);
        fillLibrary(ai, 10); // Runeclaw Bears: the green creature the Pact finds
        fillLibrary(opponentOf(game, ai), 10);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, ai);
        for (final String land : lands) {
            addCard(land, ai);
        }
        final List<Card> filterCards = new ArrayList<>();
        for (final String filter : filters) {
            filterCards.add(addCard(filter, ai));
        }
        game.getAction().checkStateEffects(true);
        final SpellAbility fromHand = addCardToZone("Summoner's Pact", ai, ZoneType.Hand).getFirstSpellAbility();
        fromHand.setActivatingPlayer(ai);
        final String verdict = DelayedLoss.problem(ai, fromHand, () -> ComboPlan.EMPTY);

        onStack(game, "Summoner's Pact", ai);
        playUntilStackClear(game);
        AssertJUnit.assertTrue("Summoner's Pact resolved: its debt is due at the next upkeep",
                DelayedLoss.owed(ai, null).hasNext());
        final UpkeepTaps taps = new UpkeepTaps(game, ai);
        game.subscribeToEvents(taps);
        playThroughTheNextUpkeep(game, ai, game.getPhaseHandler().getTurn());
        tapOrder.addAll(taps.names);
        return Arrays.asList(verdict, ai.hasLost(), tapped(filterCards));
    }

    /**
     * F1's board: Mountain x4 and Prophetic Prism x2 ({1},{T}: any colour). Before RU-29 the count allowed it: four R
     * units and two any-colour units, six for the debt's four and the filters' two, the Prisms matching the two G pips.
     * The payment pays the pips first, one after the other: the first Prism takes one, and its test refuses the second
     * Prism, a filter chosen right after a filter, so no source is left for the second G and the AI loses. The count
     * now keeps at most one filter, and five units cannot pay four and a Prism's {1}: refused.
     */
    @Test(timeOut = 300_000)
    public void twoPropheticPrismsForBothGreenPipsAreRefused() {
        AssertJUnit.assertEquals("[the count's verdict, the AI lost, Prisms tapped after its upkeep]",
                Arrays.asList(DelayedLoss.PACT_UNPAYABLE, true, 0),
                castAndPay(List.of("Mountain", "Mountain", "Mountain", "Mountain"),
                        List.of("Prophetic Prism", "Prophetic Prism")));
    }

    /**
     * F1's pre-merge form: Mountain x2 and Selesnya Signet x2 ({1},{T}: {G}{W}). Each Signet has one G, so each would
     * pay one of the two G pips; before RU-29 the count allowed it (G W G W R R, six for four and two inputs), and the
     * payment's test refuses the second Signet as it refuses the second Prism: the AI loses. The count with one Signet,
     * G W R R, cannot pay four and its {1}: refused.
     */
    @Test(timeOut = 300_000)
    public void twoSignetsForBothGreenPipsAreRefused() {
        AssertJUnit.assertEquals("[the count's verdict, the AI lost, Signets tapped after its upkeep]",
                Arrays.asList(DelayedLoss.PACT_UNPAYABLE, true, 0),
                castAndPay(List.of("Mountain", "Mountain"), List.of("Selesnya Signet", "Selesnya Signet")));
    }

    /**
     * RU-33: two DIFFERENT filters, Forest, Mountain x2, a Gruul Signet ({1},{T}: {R}{G}, nets one) and a Prophetic
     * Prism ({1},{T}: any colour, nets none). The Signet kept alone covers the debt (Forest and the Signet's G for the
     * pips, the Mountains for {2}, the Signet's R for its own {1}: five units for four and one input); the Prism kept
     * alone does not (Forest, Mountain x2 and the Prism are four units for four and its {1}). RU-29's rule, some one
     * filter kept, allowed it; the payer may take the Prism first and then refuse the Signet as a filter chained after
     * it, so the count must not lean on which filter it takes: a debt is payable only when it is with each counted
     * filter alone. Refused. The payment on this board is informational, recorded as it was seen: the cards the AI
     * tapped in its upkeep were, in order, the Forest, the Gruul Signet (the better filter, taken first), a Mountain,
     * the Prism and a Mountain; it paid with both filters and is not lost. Refused though paid: the rule's accepted
     * cost on this board.
     */
    @Test(timeOut = 300_000)
    public void twoDifferentFiltersPayOnlyWhenEachAlonePays() {
        final List<String> tapOrder = new ArrayList<>();
        final List<Object> seen = castAndPay(List.of("Forest", "Mountain", "Mountain"),
                List.of("Gruul Signet", "Prophetic Prism"), tapOrder);
        AssertJUnit.assertEquals("the count's verdict: the Prism alone does not cover the debt",
                DelayedLoss.PACT_UNPAYABLE, seen.get(0));
        // informational (RU-33): what the payer did on this board at the AI's upkeep, recorded as it was seen
        AssertJUnit.assertEquals("[the AI lost, filters tapped after its upkeep; what it tapped there, in order]",
                Arrays.asList(false, 2, List.of("Forest", "Gruul Signet", "Mountain", "Prophetic Prism", "Mountain")),
                Arrays.asList(seen.get(1), seen.get(2), tapOrder));
    }

    /**
     * The control: Forest, Mountain x3 and one Prophetic Prism. One G pip from the Forest, one from the Prism, the
     * Mountains for {2} and the Prism's {1}: five units for four and one input. Allowed, and paid.
     */
    @Test(timeOut = 300_000)
    public void oneFilterBesideALandOfTheColourPaysTheDebtTheCountAllowed() {
        AssertJUnit.assertEquals("[the count's verdict, the AI lost, Prisms tapped after its upkeep]",
                Arrays.asList(null, false, 1),
                castAndPay(List.of("Forest", "Mountain", "Mountain", "Mountain"), List.of("Prophetic Prism")));
    }

    /**
     * RU-29's payment boards beside the three above, each [the count's verdict, the AI lost, filters tapped]: a board
     * the count allows is paid, and every board the payer pays is allowed but the two RU-33 marks (below). Paid with
     * one filter: Forest, Mountain x3 and Prism x2; Forest x2, Mountain and Boros Signet x2; the same with one Boros
     * Signet; Forest x2, Mountain, Boros Signet and a Prism. Paid with two, a land chosen between them: Forest,
     * Mountain x2 and Selesnya and Boros
     * Signets; Forest, Mountain x2 and Selesnya Signet x2 (one Signet's G pays a pip, the other pays the generic part
     * after a Mountain). Not paid, and refused now (each allowed before RU-29): Mountain x3, Selesnya Signet and a
     * Prism (two filters for the two pips); and the boards where a second filter must pay the generic part, Forest,
     * Mountain and Selesnya and Boros Signets (its payment fails with one Signet tapped), Forest x2 and Boros Signet x2, Forest, Mountain and Selesnya Signet
     * x2, Forest, Mountain, Selesnya and Gruul Signets, Forest, Mountain and Gruul Signet x2, and Mountain x2 and Gruul
     * Signet x2.
     * <p>
     * RU-33 makes the claim "allowed implies paid" on every row, and the rule (payable with EACH counted filter taken
     * alone) costs two rows the payer pays: Forest x2, Mountain, Boros Signet and a Prism (the Prism alone is four
     * units for four and its {1}), and Forest, Mountain x2 and Selesnya and Boros Signets (the Boros Signet alone
     * leaves one G pip without a G). Both keep their true values, refused though the payer pays, marked in
     * {@code refusedThoughPaid}: the rule's accepted cost.
     */
    @Test(timeOut = 900_000)
    public void aBoardTheCountAllowsIsOneThePayerPays() {
        final List<List<String>> lands = List.of(
                List.of("Forest", "Mountain", "Mountain", "Mountain"),
                List.of("Forest", "Forest", "Mountain"),
                List.of("Forest", "Forest", "Mountain"),
                List.of("Forest", "Forest", "Mountain"),
                List.of("Forest", "Mountain", "Mountain"),
                List.of("Forest", "Mountain", "Mountain"),
                List.of("Mountain", "Mountain", "Mountain"),
                List.of("Forest", "Mountain"),
                List.of("Forest", "Forest"),
                List.of("Forest", "Mountain"),
                List.of("Forest", "Mountain"),
                List.of("Forest", "Mountain"),
                List.of("Mountain", "Mountain"));
        final List<List<String>> filters = List.of(
                List.of("Prophetic Prism", "Prophetic Prism"),
                List.of("Boros Signet", "Boros Signet"),
                List.of("Boros Signet"),
                List.of("Boros Signet", "Prophetic Prism"),
                List.of("Selesnya Signet", "Boros Signet"),
                List.of("Selesnya Signet", "Selesnya Signet"),
                List.of("Selesnya Signet", "Prophetic Prism"),
                List.of("Selesnya Signet", "Boros Signet"),
                List.of("Boros Signet", "Boros Signet"),
                List.of("Selesnya Signet", "Selesnya Signet"),
                List.of("Selesnya Signet", "Gruul Signet"),
                List.of("Gruul Signet", "Gruul Signet"),
                List.of("Gruul Signet", "Gruul Signet"));
        final List<Object> paid = Arrays.asList(null, false, 1);
        final List<Object> refused = Arrays.asList(DelayedLoss.PACT_UNPAYABLE, true, 0);
        // RU-33's accepted cost: the rows the count refuses though the payer pays them, by index
        final Set<Integer> refusedThoughPaid = Set.of(3, 4);
        final List<List<Object>> expected = List.of(paid, paid, paid,
                Arrays.asList(DelayedLoss.PACT_UNPAYABLE, false, 1),
                Arrays.asList(DelayedLoss.PACT_UNPAYABLE, false, 2),
                Arrays.asList(null, false, 2),
                refused, Arrays.asList(DelayedLoss.PACT_UNPAYABLE, true, 1), refused, refused, refused, refused,
                refused);
        final List<List<Object>> seen = new ArrayList<>();
        for (int i = 0; i < lands.size(); i++) {
            seen.add(castAndPay(lands.get(i), filters.get(i)));
        }
        AssertJUnit.assertEquals("[the count's verdict, the AI lost, filters tapped] per board", expected, seen);
        for (int i = 0; i < seen.size(); i++) {
            final boolean allowed = seen.get(i).get(0) == null;
            final boolean lost = (Boolean) seen.get(i).get(1);
            AssertJUnit.assertFalse("row " + i + ": allowed and lost", allowed && lost);
            AssertJUnit.assertEquals("row " + i + ": refused though paid only where marked",
                    refusedThoughPaid.contains(i), !allowed && !lost);
        }
    }

    /**
     * RU-35's form of {@link #castAndPay}: {@code pact} judged from the AI's hand in its first main phase, with
     * {@code battlefield} put onto its battlefield in that order and {@code hand} in its hand in that order (the hand
     * copy of the Pact the count judged is taken out again, so the hand is exactly {@code hand}); then the Pact
     * resolved (Pact of Negation over an opponent's Grizzly Bears) and the game played through the AI's next upkeep.
     * [the count's verdict, whether the AI lost by the end of that upkeep]; the cards the AI tapped in that upkeep are
     * added to {@code tapOrder}, in order.
     */
    private List<Object> payBoard(final String pact, final List<String> battlefield, final List<String> hand,
                                  final List<String> tapOrder) {
        final Game game = initAndCreateGame();
        noDeadline(game);
        final Player ai = game.getPlayers().get(1);
        final Player opp = opponentOf(game, ai);
        fillLibrary(ai, 10); // Runeclaw Bears: the green creature Summoner's Pact finds
        fillLibrary(opp, 10);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, ai);
        for (final String name : battlefield) {
            addCard(name, ai);
        }
        for (final String name : hand) {
            addCardToZone(name, ai, ZoneType.Hand);
        }
        game.getAction().checkStateEffects(true);
        final boolean counter = "Pact of Negation".equals(pact);
        final SpellAbility bears = counter ? onStack(game, "Grizzly Bears", opp) : null;
        final Card inHand = addCardToZone(pact, ai, ZoneType.Hand);
        final SpellAbility fromHand = inHand.getFirstSpellAbility();
        fromHand.setActivatingPlayer(ai);
        if (counter) {
            fromHand.getTargets().add(bears);
        }
        final String verdict = DelayedLoss.problem(ai, fromHand, () -> ComboPlan.EMPTY);
        ai.getZone(ZoneType.Hand).remove(inHand);

        final Card pactCard = createCard(pact, ai);
        pactCard.setGameTimestamp(game.getNextTimestamp());
        game.getStackZone().add(pactCard);
        final SpellAbility cast = pactCard.getFirstSpellAbility();
        cast.setActivatingPlayer(ai);
        if (counter) {
            cast.getTargets().add(bears);
        }
        game.getStack().add(cast);
        if (counter) {
            game.getPhaseHandler().onStackResolved();
        }
        playUntilStackClear(game);
        AssertJUnit.assertTrue(pact + " resolved: its debt is due at the next upkeep",
                DelayedLoss.owed(ai, null).hasNext());
        final UpkeepTaps taps = new UpkeepTaps(game, ai);
        game.subscribeToEvents(taps);
        playThroughTheNextUpkeep(game, ai, game.getPhaseHandler().getTurn());
        tapOrder.addAll(taps.names);
        return Arrays.asList(verdict, ai.hasLost());
    }

    /** {@link #payBoard} for one row: {pact, battlefield names comma-separated in order, hand names likewise}. */
    private List<Object> payRow(final List<String> row, final List<String> tapOrder) {
        final List<String> hand = row.get(2).isEmpty() ? List.of() : Arrays.asList(row.get(2).split(","));
        return payBoard(row.get(0), Arrays.asList(row.get(1).split(",")), hand, tapOrder);
    }

    /**
     * RU-35 (review-close M-1): the payer's chain clause (ComputerUtilMana.payManaCost, "chaining filters risks
     * misplay") applies to every mana ability that costs mana to activate, also those the count gives no filter
     * option: a filter land's hybrid-input ability (Mystic Gate's {W/U},{T}, Wooded Bastion's {G/W},{T}), counted only
     * as its {T}: {C}. Each board below has one such land beside one counted filter, and before RU-35 the count allowed
     * it, the counted filter's mana needed (Pact of Negation, {3}{U}{U}: Mystic Gate as {C}, Dimir Signet's {U}{B}
     * for its {1}, an Island, Mountains; Summoner's Pact, {2}{G}{G}: Wooded Bastion as {C}, a Gruul or Selesnya
     * Signet's G for one pip, a Forest for the other, a Mountain), and the AI lost at its upkeep, with nothing of the
     * board's colours in its hand, in every battlefield order tried, with Worldfire in hand, and with a Mountain more
     * than the debt needs. Its payment tapped the Island (the Forest) for the Signet's {1}, then the Signet, and
     * stopped there. With a
     * black card in hand (Griselbrand) the Gate board is paid, and with a green one (Craterhoof Behemoth) the Bastion
     * board is: the payer's order follows the hand's colours, which the count does not read. So when the AI controls
     * a filter the count does not count, the count leans on no counted filter: refused, each row, and the AI loses
     * as it did. [the count's verdict, the AI lost by the end of its upkeep] per row.
     */
    @Test(timeOut = 900_000)
    public void aFilterTheCountDoesNotCountLeavesItNoFilterToLeanOn() {
        final List<List<String>> rows = List.of(
                List.of("Pact of Negation", "Mystic Gate,Dimir Signet,Island,Mountain,Mountain", ""),
                List.of("Pact of Negation", "Dimir Signet,Mystic Gate,Island,Mountain,Mountain", ""),
                List.of("Pact of Negation", "Island,Mountain,Mountain,Dimir Signet,Mystic Gate", ""),
                List.of("Pact of Negation", "Mystic Gate,Dimir Signet,Island,Mountain,Mountain,Mountain", ""),
                List.of("Summoner's Pact", "Wooded Bastion,Gruul Signet,Forest,Mountain", ""),
                List.of("Summoner's Pact", "Gruul Signet,Wooded Bastion,Forest,Mountain", ""),
                List.of("Summoner's Pact", "Forest,Mountain,Gruul Signet,Wooded Bastion", "Worldfire"),
                List.of("Summoner's Pact", "Wooded Bastion,Selesnya Signet,Forest,Mountain", ""),
                List.of("Summoner's Pact", "Wooded Bastion,Gruul Signet,Forest,Mountain,Mountain", ""));
        final List<List<Object>> seen = new ArrayList<>();
        for (final List<String> row : rows) {
            seen.add(payRow(row, new ArrayList<>()));
        }
        final List<List<Object>> expected = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            expected.add(Arrays.asList(DelayedLoss.PACT_UNPAYABLE, true));
        }
        AssertJUnit.assertEquals("[the count's verdict, the AI lost] per row", expected, seen);
        for (int i = 0; i < seen.size(); i++) {
            AssertJUnit.assertFalse("row " + i + " " + rows.get(i) + ": allowed and lost",
                    seen.get(i).get(0) == null && (Boolean) seen.get(i).get(1));
        }
    }
}
