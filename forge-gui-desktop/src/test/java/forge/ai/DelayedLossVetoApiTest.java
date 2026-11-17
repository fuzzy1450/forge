package forge.ai;

import java.util.Arrays;
import java.util.List;

import org.testng.AssertJUnit;
import org.testng.annotations.Test;

import forge.ai.simulation.FlatMonteCarloStats;
import forge.game.Game;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;

/**
 * The final veto (MTG_DeckMaker's self-kill fix, DESIGN.md S4, RULINGS-R3 M23), asked through AiController's own
 * {@code vetoDelayedLoss}. These rows call what the fix added, so they do not compile at the parent 65940c9d323; the
 * rows that drive the AI and compile there are DelayedLossVetoTest's.
 */
public class DelayedLossVetoApiTest extends AITest {

    private static AiController brainOf(final Player p) {
        return ((PlayerControllerAi) p.getController()).getAi();
    }

    private static Player opponentOf(final Game game, final Player ai) {
        return game.getPlayers().get(0) == ai ? game.getPlayers().get(1) : game.getPlayers().get(0);
    }

    /** The AI deadline off, as served games have it. */
    private static void noDeadline(final Game game) {
        game.AI_TIMEOUT = Integer.MAX_VALUE;
        game.AI_CAN_USE_TIMEOUT = false;
    }

    /** A card put into the AI's hand, its first spell ability with the AI as activator. */
    private SpellAbility fromHand(final Player ai, final String name) {
        final SpellAbility sa = addCardToZone(name, ai, ZoneType.Hand).getFirstSpellAbility();
        sa.setActivatingPlayer(ai);
        return sa;
    }

    /** How many times FlatMonteCarloStats has noted this rejection, read from its summary's FLATMC-REJECTED line. */
    private static int rejected(final String key) {
        for (final String line : FlatMonteCarloStats.summary().split("\n")) {
            if (!line.startsWith("FLATMC-REJECTED ")) {
                continue;
            }
            for (final String entry : line.substring("FLATMC-REJECTED ".length()).split("; ")) {
                final int eq = entry.lastIndexOf('=');
                if (eq > 0 && entry.substring(0, eq).equals(key)) {
                    return Integer.parseInt(entry.substring(eq + 1).trim());
                }
            }
        }
        return 0;
    }

    /** The play a decision returns, as the host's name, " (land)" for a land play; null for a pass. */
    private static String describe(final List<SpellAbility> chosen) {
        if (chosen == null || chosen.isEmpty()) {
            return null;
        }
        final SpellAbility sa = chosen.get(0);
        return sa.getHostCard().getName() + (sa.isLandAbility() ? " (land)" : "");
    }

    /**
     * The AI's own second main phase, a land played this turn, Forest x2 and Grizzly Bears in hand, Flat Monte Carlo
     * off (the test game's default): a decision whose pick is a play nothing flags.
     */
    private Player bearsInTheSecondMainPhase() {
        final Game game = initAndCreateGame();
        noDeadline(game);
        final Player ai = game.getPlayers().get(1);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN2, ai);
        ai.setLandsPlayedThisTurn(1);
        addCards("Forest", 2, ai);
        addCardToZone("Grizzly Bears", ai, ZoneType.Hand);
        fillLibrary(ai, 10);
        game.getAction().checkStateEffects(true);
        return ai;
    }

    /**
     * An AiController for {@code ai} whose final veto refuses every play of the card {@code name} and asks the real
     * veto of every other play: a stand-in for a refusal at one of chooseSpellAbilityToPlayImpl's spell returns.
     */
    private static AiController vetoing(final Player ai, final String name) {
        return new AiController(ai, ai.getGame()) {
            @Override
            SpellAbility vetoDelayedLoss(final SpellAbility chosen) {
                return chosen != null && name.equals(chosen.getHostCard().getName()) ? null
                        : super.vetoDelayedLoss(chosen);
            }
        };
    }

    /**
     * S4 (DESIGN.md D12): a play DelayedLoss refuses becomes a pass, is noted in FlatMonteCarloStats as
     * "DelayedLoss:" and its code, and is a failed cast for the rest of the turn; every other play is returned itself,
     * and nothing is noted for it. The opponent's first main phase: with Island x3 the AI's upkeep cannot pay Pact of
     * Negation's {3}{U}{U}; with Island x5 it can, and Lightning Bolt is no delayed-loss play at all.
     */
    @Test(timeOut = 120_000)
    public void theVetoPassesInsteadOfARefusedPlay() {
        final Game game = initAndCreateGame();
        noDeadline(game);
        final Player ai = game.getPlayers().get(1);
        final AiController brain = brainOf(ai);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, opponentOf(game, ai));
        addCards("Island", 3, ai);
        final SpellAbility uncovered = fromHand(ai, "Pact of Negation");
        final int before = rejected("DelayedLoss:pact-unpayable");
        AssertJUnit.assertNull("an uncovered Pact of Negation is vetoed: a pass", brain.vetoDelayedLoss(uncovered));
        AssertJUnit.assertTrue("the vetoed play is a failed cast for the rest of the turn",
                brain.hasFailedToCast(uncovered));
        AssertJUnit.assertEquals("the veto is noted as DelayedLoss:pact-unpayable", before + 1,
                rejected("DelayedLoss:pact-unpayable"));

        addCards("Island", 2, ai);
        final SpellAbility covered = fromHand(ai, "Pact of Negation");
        final SpellAbility bolt = fromHand(ai, "Lightning Bolt");
        AssertJUnit.assertSame("a covered Pact of Negation is the play itself", covered,
                brain.vetoDelayedLoss(covered));
        AssertJUnit.assertSame("so is any play nothing flags", bolt, brain.vetoDelayedLoss(bolt));
        AssertJUnit.assertNull("no play stays no play", brain.vetoDelayedLoss(null));
        AssertJUnit.assertFalse("a play the veto lets through is no failed cast", brain.hasFailedToCast(covered));
        AssertJUnit.assertFalse(brain.hasFailedToCast(bolt));
        AssertJUnit.assertEquals("nothing more is noted", before + 1, rejected("DelayedLoss:pact-unpayable"));
    }

    /**
     * RULINGS-R3 M23: the veto at the play-before-land return sits inside its condition, so a vetoed pick falls through
     * to the land play and the walk instead of passing. No board reaches that return with a play DelayedLoss refuses:
     * the pass picks through the walk, where S3b has asked DelayedLoss already, and none of the eleven cards carries
     * PlayBeforeLandDrop (DelayedLossVetoTest's note). So the veto here refuses the pass's own pick: an AiController
     * whose vetoDelayedLoss refuses Mox Diamond and asks the real veto of every other play. The AI's own second main
     * phase (where a land is played, not held for later), Mox Diamond and an Island in hand: the stock AI's pick is Mox
     * Diamond, before the land; with Mox Diamond vetoed the AI plays the Island.
     */
    @Test(timeOut = 120_000)
    public void aVetoedPlayBeforeLandPickFallsThroughToTheLandPlay() {
        final Game game = initAndCreateGame();
        noDeadline(game);
        final Player ai = game.getPlayers().get(1);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN2, ai);
        addCardToZone("Mox Diamond", ai, ZoneType.Hand);
        addCardToZone("Island", ai, ZoneType.Hand);
        final String stock = describe(brainOf(ai).chooseSpellAbilityToPlay());
        final AiController vetoing = new AiController(ai, game) {
            @Override
            SpellAbility vetoDelayedLoss(final SpellAbility chosen) {
                return chosen != null && "Mox Diamond".equals(chosen.getHostCard().getName()) ? null
                        : super.vetoDelayedLoss(chosen);
            }
        };
        final String vetoed = describe(vetoing.chooseSpellAbilityToPlay());
        AssertJUnit.assertEquals("[the stock pick, the pick with Mox Diamond vetoed]",
                Arrays.asList("Mox Diamond", "Island (land)"), Arrays.asList(stock, vetoed));
    }

    /**
     * DESIGN.md S4, D12 (review TA, finding 1): the veto at the last return, getSpellAbilityToPlay()'s. On target it is
     * the only DelayedLoss check of the paths that ask no other site: the combo-table loops (LoopDriver's response and
     * main-phase action, LoopRunner), ComboExecute.goOffFirst, the sure plays, and a search-only seat's own Flat MC
     * choice, whose ComboGuard is off (DESIGN.md S4, "What can still reach the veto"). As in M23's row, an AiController
     * whose vetoDelayedLoss refuses Grizzly Bears (and asks the real veto of every other play) stands in for a refusal
     * there: the row pins the veto's place at this return, theVetoPassesInsteadOfARefusedPlay its answers. On
     * {@link #bearsInTheSecondMainPhase}'s board the stock pick is Grizzly Bears, which getSpellAbilityToPlay returns;
     * with Grizzly Bears vetoed the decision is a pass.
     */
    @Test(timeOut = 120_000)
    public void theLastReturnPassesTheVeto() {
        final Player ai = bearsInTheSecondMainPhase();
        final String stock = describe(brainOf(ai).chooseSpellAbilityToPlay());
        final String vetoed = describe(vetoing(ai, "Grizzly Bears").chooseSpellAbilityToPlay());
        AssertJUnit.assertEquals("[the stock pick, the pick with Grizzly Bears vetoed at the last return]",
                Arrays.asList("Grizzly Bears", null), Arrays.asList(stock, vetoed));
    }
}
