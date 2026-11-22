package forge.ai;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.testng.AssertJUnit;
import org.testng.annotations.Test;

import forge.ai.simulation.ComboPlan;
import forge.ai.simulation.DelayedLoss;
import forge.game.Game;
import forge.game.card.Card;
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
        playThroughTheNextUpkeep(game, ai, game.getPhaseHandler().getTurn());
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
     * the count allows is paid, and every board the payer pays is allowed. Paid with one filter: Forest, Mountain x3
     * and Prism x2; Forest x2, Mountain and Boros Signet x2; the same with one Boros Signet; Forest x2, Mountain, Boros
     * Signet and a Prism. Paid with two, a land chosen between them: Forest, Mountain x2 and Selesnya and Boros
     * Signets; Forest, Mountain x2 and Selesnya Signet x2 (one Signet's G pays a pip, the other pays the generic part
     * after a Mountain). Not paid, and refused now (each allowed before RU-29): Mountain x3, Selesnya Signet and a
     * Prism (two filters for the two pips); and the boards where a second filter must pay the generic part, Forest,
     * Mountain and Selesnya and Boros Signets (its payment fails with one Signet tapped), Forest x2 and Boros Signet x2, Forest, Mountain and Selesnya Signet
     * x2, Forest, Mountain, Selesnya and Gruul Signets, Forest, Mountain and Gruul Signet x2, and Mountain x2 and Gruul
     * Signet x2.
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
        final List<List<Object>> expected = List.of(paid, paid, paid, paid,
                Arrays.asList(null, false, 2), Arrays.asList(null, false, 2),
                refused, Arrays.asList(DelayedLoss.PACT_UNPAYABLE, true, 1), refused, refused, refused, refused,
                refused);
        final List<List<Object>> seen = new ArrayList<>();
        for (int i = 0; i < lands.size(); i++) {
            seen.add(castAndPay(lands.get(i), filters.get(i)));
        }
        AssertJUnit.assertEquals("[the count's verdict, the AI lost, filters tapped] per board", expected, seen);
    }
}
