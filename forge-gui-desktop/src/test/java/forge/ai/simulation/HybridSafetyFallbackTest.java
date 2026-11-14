package forge.ai.simulation;

import java.util.function.Supplier;

import org.mockito.MockedConstruction;
import org.mockito.Mockito;
import org.testng.Assert;
import org.testng.annotations.Test;

import forge.ai.AiController;
import forge.ai.AiPlayDecision;
import forge.ai.PlayerControllerAi;
import forge.game.Game;
import forge.game.card.Card;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import forge.util.AITimeoutTracker;
import forge.util.SimScope;

/**
 * A hybrid_sim seat weighs each play on a copy of the game (OnePlaySafetyChecker, from the hybrid branch of
 * AiController.saSideEffects), on its game thread as well as on its eval thread: its land choice, its counterspell
 * check, its cast-from-effect check and dozens of other callers reach that branch. A copy that failed (one of the
 * copier's gaps: a token copy of Thief of Blood, in the determinism battery's game 1v1-hybrid/Plunder the Graves+Seize
 * Control/7000000) threw out of saSideEffects, and on the game thread nothing caught it, so the game ended as an Error
 * in every arm. Now the seat judges that play as a default seat does, as a Flat MC rollout that fails falls back to the
 * heuristic choice. The AI's deadline still goes through, and so, under a simulation, does an Error in the failure's
 * chain, which FlatMonteCarloPicker.reportFailure rethrows for the same reason: whether a stack overflows depends on
 * the machine, so read as a failed check it would make a game that differs by machine.
 * <p>
 * The failure is made here by a copier that throws whatever it is asked, because the copier's known gaps are closed
 * (GameCopierTokenCopyTest): the branch must not depend on the copier having none.
 */
public class HybridSafetyFallbackTest extends SimulationTest {

    /** Every game copy made while it is open fails with {@code failure}'s throwable, as the copier's gaps made one fail. */
    private static MockedConstruction<GameCopier> everyCopyFails(final Supplier<? extends RuntimeException> failure) {
        return Mockito.mockConstructionWithAnswer(GameCopier.class, invocation -> {
            throw failure.get();
        });
    }

    private static AiController ai(final Player player) {
        return ((PlayerControllerAi) player.getController()).getAi();
    }

    /**
     * A hybrid seat at 2 life with a Forest in hand, against Zo-Zu the Punisher (2 damage to a land's controller as the
     * land enters): playing the land loses the game. A default seat's checks refuse it as BadEtbEffects
     * (ComputerUtil.getDamageFromETB); the hybrid seat's own simulation refuses it as HybridSimRejected.
     */
    private SpellAbility lethalLandPlay(final Game game) {
        final Player ai = game.getPlayers().get(1);
        final Player opponent = game.getPlayers().get(0);
        ai.setLife(2, null);
        final Card forest = addCardToZone("Forest", ai, ZoneType.Hand);
        addCard("Zo-Zu the Punisher", opponent);
        moveToMain2(game, ai);
        final SpellAbility land = forest.getAllPossibleAbilities(ai, true).stream()
                .filter(SpellAbility::isLandAbility).findFirst().orElseThrow();
        land.setActivatingPlayer(ai);
        return land;
    }

    @Test
    public void aHybridSeatWhoseGameCannotBeCopiedJudgesThePlayAsADefaultSeatDoes() {
        final Game game = initAndCreateGame(true);
        final Player ai = game.getPlayers().get(1);
        final SpellAbility land = lethalLandPlay(game);

        final AiPlayDecision decision;
        final int copiers;
        try (MockedConstruction<GameCopier> copies =
                     everyCopyFails(() -> new IllegalStateException("this game cannot be copied"))) {
            decision = ai(ai).canPlaySa(land);
            copiers = copies.constructed().size();
        }

        Assert.assertTrue(copiers > 0, "the hybrid seat tried its safety check on a copy of the game");
        Assert.assertEquals(decision, AiPlayDecision.BadEtbEffects,
                "with no copy to weigh the play on, the hybrid seat refuses the lethal land as a default seat does");
    }

    @Test
    public void theAiDeadlineStillGoesThrough() {
        final Game game = initAndCreateGame(true);
        final Player ai = game.getPlayers().get(1);
        final SpellAbility land = lethalLandPlay(game);

        try (MockedConstruction<GameCopier> copies = everyCopyFails(() -> {
            // the deadline passed while the copy was made: AITimeoutTracker.check() throws it as the AI's loops do
            final Object token = AITimeoutTracker.arm(System.nanoTime() - 1, false);
            try {
                AITimeoutTracker.check();
            } finally {
                AITimeoutTracker.disarm(token);
            }
            return new IllegalStateException("check() did not throw: the test's deadline was not armed");
        })) {
            Assert.expectThrows(AITimeoutTracker.Expired.class, () -> ai(ai).canPlaySa(land));
            Assert.assertTrue(copies.constructed().size() > 0, "the deadline came from the copy");
        }
    }

    @Test
    public void underASimulationAnErrorInTheFailureGoesThrough() {
        final StackOverflowError overflow = new StackOverflowError("as deep as this machine's stack");
        final Throwable thrown = Scoped.thrownBy(new SimScope(19L), () -> {
            // made on the game's thread: a construction mock is only seen on the thread that opened it
            final Game game = initAndCreateGame(true);
            final Player ai = game.getPlayers().get(1);
            final SpellAbility land = lethalLandPlay(game);
            try (MockedConstruction<GameCopier> copies =
                         everyCopyFails(() -> new RuntimeException("an ability could not be built", overflow))) {
                return ai(ai).canPlaySa(land);
            }
        });
        Assert.assertSame(thrown, overflow, "the overflow in the failure's chain is rethrown, not read as a failed check");
    }
}
