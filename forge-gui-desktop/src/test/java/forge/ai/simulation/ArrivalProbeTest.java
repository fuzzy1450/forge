package forge.ai.simulation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;

import org.testng.AssertJUnit;
import org.testng.annotations.Test;

import forge.game.ArrivalProbe;
import forge.game.Game;
import forge.game.card.Card;
import forge.game.card.CardCopyService;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.zone.ZoneType;
import forge.util.SimScope;

/**
 * The copier's arrival probe (MTG_DeckMaker docs/superpowers/specs/2026-11-20-flatmc-copier-turninzone-design.md,
 * section 6, test T8): print-only, it marks the reads in a rollout copy whose outcome the old copier, which stamped
 * every copied card with the copy's turn and counted a descend for each permanent card it put in a graveyard, could
 * have changed.
 */
public class ArrivalProbeTest extends SimulationTest {
    private static final int NOW = 5;
    private static final int EARLIER = 2;

    private Game game;
    private Player ai;

    private void setUp() {
        game = initAndCreateGame();
        ai = game.getPlayers().get(1);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, ai, NOW);
    }

    private static Card old(final Card c) {
        c.setTurnInZone(EARLIER);
        return c;
    }

    /** Two permanent cards in the AI's graveyard since turn 2, and no descend this turn. */
    private void oldGraveyard() {
        for (int i = 0; i < 2; i++) {
            old(addCardToZone("Hill Giant", ai, ZoneType.Graveyard));
        }
        ai.setDescended(0);
    }

    private static List<String> withSeed(final List<String> lines, final long seed) {
        final List<String> out = new ArrayList<>();
        synchronized (lines) {
            for (final String l : lines) {
                if (l.contains(" seed=" + seed + " ")) {
                    out.add(l);
                }
            }
        }
        return out;
    }

    private static List<String> startingWith(final List<String> lines, final String prefix) {
        final List<String> out = new ArrayList<>();
        for (final String l : lines) {
            if (l.startsWith(prefix)) {
                out.add(l);
            }
        }
        return out;
    }

    /** Runs {@code body} with a list as the probe's sink, and puts the previous sink back. */
    private static List<String> probed(final Runnable body) {
        final List<String> lines = Collections.synchronizedList(new ArrayList<>());
        final Consumer<String> before = ArrivalProbe.swapSink(lines::add);
        try {
            body.run();
        } finally {
            ArrivalProbe.swapSink(before);
        }
        return lines;
    }

    private static void under(final long seed, final Runnable body) {
        Scoped.under(new SimScope(seed), () -> {
            body.run();
            return null;
        });
    }

    @Test
    public void aFirstTurnReadOfAnOldCardIsMarkedOnce() {
        setUp();
        final Card giant = old(addCard("Hill Giant", ai));
        final List<String> lines = probed(() -> under(77L, () -> {
            final GameCopier copier = new GameCopier(game);
            copier.makeCopy();
            final Card cGiant = copier.find(giant);
            for (int i = 0; i < 2; i++) { // two reads from one reader: a reader is class.method:line
                AssertJUnit.assertFalse(cGiant.enteredThisTurn());
            }
        }));
        final List<String> mine = withSeed(lines, 77L);
        AssertJUnit.assertEquals(String.valueOf(mine), 1, startingWith(mine, "D* seed=77 ").size());
        final List<String> reads = new ArrayList<>();
        for (final String l : startingWith(mine, "READ seed=77 ")) {
            if (l.contains("reader=forge.ai.simulation.ArrivalProbeTest")) {
                reads.add(l);
            }
        }
        AssertJUnit.assertEquals(String.valueOf(mine), 1, reads.size());
        AssertJUnit.assertTrue(reads.get(0), reads.get(0).contains(" card=" + giant.getId() + ":Hill Giant"));
    }

    /** Deathleaper's static reads ThisTurnEntered in the copier's own state check: that read is watched too. */
    @Test
    public void theCopiersOwnStateCheckIsWatched() {
        setUp();
        addCard("Deathleaper, Terror Weapon", ai);
        old(addCard("Hill Giant", ai));
        game.getAction().checkStateEffects(true);
        final List<String> lines = probed(() -> under(78L, () -> new GameCopier(game).makeCopy()));
        final List<String> dstar = startingWith(withSeed(lines, 78L), "D* seed=78 ");
        AssertJUnit.assertEquals(String.valueOf(lines), 1, dstar.size());
        AssertJUnit.assertTrue(dstar.get(0), dstar.get(0).contains(" reader=forge.game.card.CardProperty."));
    }

    @Test
    public void laterTurnsAndFreshCardsAreNotMarked() {
        setUp();
        final Card oldGiant = old(addCard("Hill Giant", ai));
        final Card freshGiant = addCard("Hill Giant", ai);
        final List<String> lines = probed(() -> under(79L, () -> {
            final GameCopier copier = new GameCopier(game);
            final Game copy = copier.makeCopy();
            AssertJUnit.assertTrue(copier.find(freshGiant).enteredThisTurn());
            copy.getPhaseHandler().devModeSet(PhaseType.MAIN1, copier.find(ai), NOW + 1);
            AssertJUnit.assertFalse(copier.find(oldGiant).enteredThisTurn());
        }));
        AssertJUnit.assertEquals(String.valueOf(lines), 0, withSeed(lines, 79L).size());
    }

    /** A card the copy moves is stamped by both copiers alike: its reads are not marked. */
    @Test
    public void aCardTheCopyMovesIsNotMarked() {
        setUp();
        final Card giant = old(addCard("Hill Giant", ai));
        final List<String> lines = probed(() -> under(80L, () -> {
            final GameCopier copier = new GameCopier(game);
            final Game copy = copier.makeCopy();
            final Card moved = copy.getAction().moveToGraveyard(copier.find(giant), null);
            AssertJUnit.assertTrue(moved.enteredThisTurn());
            AssertJUnit.assertEquals(NOW, moved.getTurnInZone());
        }));
        AssertJUnit.assertEquals(String.valueOf(lines), 0, withSeed(lines, 80L).size());
    }

    /** A last-known copy and a copy of a copy only pass the value on; the last-known copy's own read is marked. */
    @Test
    public void passOnReadsAreNotMarked() {
        setUp();
        final Card giant = old(addCard("Hill Giant", ai));
        final List<String> lines = probed(() -> {
            under(81L, () -> {
                final GameCopier copier = new GameCopier(game);
                final Game copy = copier.makeCopy();
                CardCopyService.getLKICopy(copier.find(giant));
                new GameCopier(copy).makeCopy();
            });
            under(89L, () -> {
                final GameCopier copier = new GameCopier(game);
                copier.makeCopy();
                AssertJUnit.assertFalse(CardCopyService.getLKICopy(copier.find(giant)).enteredThisTurn());
            });
        });
        AssertJUnit.assertEquals("they pass the value on: " + lines, 0, withSeed(lines, 81L).size());
        final List<String> dstar = startingWith(withSeed(lines, 89L), "D* seed=89 ");
        AssertJUnit.assertEquals(String.valueOf(lines), 1, dstar.size());
        AssertJUnit.assertTrue(dstar.get(0), dstar.get(0).contains(" reader=forge.ai.simulation.ArrivalProbeTest"));
    }

    @Test
    public void aDirectReadIsMarked() {
        setUp();
        final Card giant = old(addCard("Hill Giant", ai));
        final List<String> lines = probed(() -> under(82L, () -> {
            final GameCopier copier = new GameCopier(game);
            copier.makeCopy();
            AssertJUnit.assertEquals(EARLIER, copier.find(giant).getTurnInZone());
        }));
        final List<String> dstar = startingWith(withSeed(lines, 82L), "D* seed=82 ");
        AssertJUnit.assertEquals(String.valueOf(lines), 1, dstar.size());
        AssertJUnit.assertTrue(dstar.get(0), dstar.get(0).contains(" reader=forge.ai.simulation.ArrivalProbeTest"));
    }

    @Test
    public void aDescendReadIsMarkedWhileItsDifferenceStands() {
        setUp();
        oldGraveyard();
        final List<String> lines = probed(() -> {
            under(83L, () -> {
                final GameCopier copier = new GameCopier(game);
                copier.makeCopy();
                AssertJUnit.assertEquals(0, copier.find(ai).getDescended());
            });
            under(84L, () -> {
                final GameCopier copier = new GameCopier(game);
                copier.makeCopy();
                final Player cAi = copier.find(ai);
                cAi.setDescended(0); // as the copy's cleanup does: the old copier's count is gone too
                AssertJUnit.assertEquals(0, cAi.getDescended());
            });
        });
        final List<String> descend = startingWith(withSeed(lines, 83L), "DESCEND seed=83 ");
        AssertJUnit.assertEquals(String.valueOf(lines), 1, descend.size());
        AssertJUnit.assertTrue(descend.get(0), descend.get(0).endsWith(" player=" + ai.getId() + " offset=2"));
        AssertJUnit.assertEquals(String.valueOf(lines), 1, startingWith(withSeed(lines, 83L), "D* seed=83 ").size());
        AssertJUnit.assertEquals(String.valueOf(lines), 0, withSeed(lines, 84L).size());
    }

    /** The old copier's parent copy had already counted its graveyard: a copy of it counts that twice. */
    @Test
    public void aCopyOfACopyCarriesItsParentsDifference() {
        setUp();
        oldGraveyard();
        final List<String> lines = probed(() -> under(85L, () -> {
            final GameCopier copier = new GameCopier(game);
            final Game copy = copier.makeCopy();
            final GameCopier copier2 = new GameCopier(copy);
            copier2.makeCopy();
            AssertJUnit.assertEquals(0, copier2.find(copier.find(ai)).getDescended());
        }));
        final List<String> mine = withSeed(lines, 85L);
        final List<String> descend = startingWith(mine, "DESCEND seed=85 ");
        AssertJUnit.assertEquals(String.valueOf(mine), 1, descend.size());
        AssertJUnit.assertTrue(descend.get(0), descend.get(0).endsWith(" offset=4"));
        for (final String l : mine) {
            AssertJUnit.assertFalse(l, l.contains("reader=forge.ai.simulation.GameCopier"));
        }
    }

    @Test
    public void twoGamesEachGetTheirOwnDStar() {
        setUp();
        final Card giant = old(addCard("Hill Giant", ai));
        final List<String> lines = probed(() -> {
            for (final long seed : new long[] {86L, 87L}) {
                under(seed, () -> {
                    final GameCopier copier = new GameCopier(game);
                    copier.makeCopy();
                    copier.find(giant).enteredThisTurn();
                });
            }
        });
        AssertJUnit.assertEquals(String.valueOf(lines), 1, startingWith(withSeed(lines, 86L), "D* seed=86 ").size());
        AssertJUnit.assertEquals(String.valueOf(lines), 1, startingWith(withSeed(lines, 87L), "D* seed=87 ").size());
    }

    /** One JVM plays a seed twice (a walk pair, a replay): each game gets its own D*, its own READ lines. */
    @Test
    public void twoGamesWithOneSeedEachGetTheirOwnDStar() {
        setUp();
        final Card giant = old(addCard("Hill Giant", ai));
        final List<String> lines = probed(() -> {
            for (int i = 0; i < 2; i++) {
                under(90L, () -> {
                    final GameCopier copier = new GameCopier(game);
                    copier.makeCopy();
                    copier.find(giant).enteredThisTurn();
                });
            }
        });
        AssertJUnit.assertEquals(String.valueOf(lines), 2, startingWith(withSeed(lines, 90L), "D* seed=90 ").size());
        AssertJUnit.assertEquals(String.valueOf(lines), 2, startingWith(withSeed(lines, 90L), "READ seed=90 ").size());
    }

    /**
     * The upkeep's reset of cameUnderControlSinceLastUpkeep reads turnInZone < turn at the upkeep's end
     * (PhaseHandler.onPhaseEnd): a copy made before it ends, in its first turn, reads it differently.
     */
    @Test
    public void aFirstTurnUpkeepReadIsMarked() {
        game = initAndCreateGame();
        ai = game.getPlayers().get(1);
        game.getPhaseHandler().devModeSet(PhaseType.UNTAP, ai, NOW);
        old(addCard("Hill Giant", ai));
        final List<String> lines = probed(() -> under(91L, () -> {
            final GameCopier copier = new GameCopier(game);
            final Game copy = copier.makeCopy();
            copy.getPhaseHandler().devAdvanceToPhase(PhaseType.DRAW, null); // through the upkeep's end
        }));
        final List<String> dstar = startingWith(withSeed(lines, 91L), "D* seed=91 ");
        AssertJUnit.assertEquals(String.valueOf(lines), 1, dstar.size());
        AssertJUnit.assertTrue(dstar.get(0), dstar.get(0).contains(" reader=forge.game.phase.PhaseHandler."));
    }

    /** Past the copy's first turn the next upkeep's reset reads alike, and the copy's cleanup cleared the descend count. */
    @Test
    public void theNextTurnsUpkeepAndAClearedDescendAreNotMarked() {
        setUp();
        old(addCard("Hill Giant", game.getPlayers().get(0))); // the opponent's: its upkeep comes next
        oldGraveyard();
        final List<String> lines = probed(() -> under(92L, () -> {
            final GameCopier copier = new GameCopier(game);
            final Game copy = copier.makeCopy();
            AssertJUnit.assertTrue(copy.getPhaseHandler().devAdvanceThroughTurnEnd(PhaseType.UPKEEP, null));
            AssertJUnit.assertEquals(NOW + 1, copy.getPhaseHandler().getTurn());
            AssertJUnit.assertEquals(0, copier.find(ai).getDescended());
        }));
        AssertJUnit.assertEquals(String.valueOf(lines), 0, withSeed(lines, 92L).size());
    }

    @Test
    public void offByDefault() {
        setUp();
        final Card giant = old(addCard("Hill Giant", ai));
        oldGraveyard();
        final Consumer<String> before = ArrivalProbe.swapSink(null);
        try {
            AssertJUnit.assertFalse(ArrivalProbe.watching());
            under(88L, () -> {
                final GameCopier copier = new GameCopier(game);
                copier.makeCopy();
                AssertJUnit.assertFalse(copier.find(giant).enteredThisTurn());
                AssertJUnit.assertEquals(EARLIER, copier.find(giant).getTurnInZone());
                AssertJUnit.assertEquals(0, copier.find(ai).getDescended());
            });
        } finally {
            ArrivalProbe.swapSink(before);
        }
    }

    @Test
    public void noScopeNoLines() {
        setUp();
        final Card giant = old(addCard("Hill Giant", ai));
        final List<String> lines = probed(() -> {
            final GameCopier copier = new GameCopier(game);
            copier.makeCopy();
            copier.find(giant).enteredThisTurn();
            copier.find(giant).getTurnInZone();
        });
        AssertJUnit.assertEquals(String.valueOf(lines), 0, lines.size());
    }
}
