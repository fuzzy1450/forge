package forge.ai;

import static org.testng.AssertJUnit.assertEquals;
import static org.testng.AssertJUnit.assertFalse;
import static org.testng.AssertJUnit.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.testng.annotations.Test;

import forge.ai.simulation.DoomsdayPile;
import forge.game.Game;
import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.player.Player;
import forge.game.zone.ZoneType;
import forge.util.SimScope;

/**
 * Upstream's Doomsday planner (DoomsdayPile, #12701 and #12606) plans the whole pile at Doomsday's first pick and serves
 * the next four picks out of that plan, which it kept in two JVM-wide maps keyed by Player. A Player's equals is its id
 * within its game, so in one serve JVM the same seat of two games is one key, and one game could be served the picks
 * another game planned. The top-up (b30a2f1fad9) keeps the two maps in the bound SimScope: two games played side by side
 * under scopes of their own, the same seat in each, keep their own plans.
 */
public class MergeDoomsdayPileScopeTest extends AITest {
    private static final int PICKS = 5;

    /** The AI seat (the second player) of a new game: the real Doomsday list as its library, and the reported hand. */
    private Player doomsdaySeat() {
        final Game game = initAndCreateGame();
        final Player ai = game.getPlayers().get(1);
        for (final String line : FlatMonteCarloVintageMetaTest.DOOMSDAY.split("\n")) {
            final int space = line.indexOf(' ');
            if (space > 0) {
                for (int n = Integer.parseInt(line.substring(0, space)); n > 0; n--) {
                    addCardToZone(line.substring(space + 1).trim(), ai, ZoneType.Library);
                }
            }
        }
        // FlatMonteCarloVintageDoomsdayTest's reported hand: a pile of Ancestral Recall, Black Lotus and the Oracle wins
        addCardToZone("Underground Sea", ai, ZoneType.Battlefield);
        addCardToZone("Underground Sea", ai, ZoneType.Battlefield);
        addCardToZone("Island", ai, ZoneType.Battlefield);
        addCardToZone("Lotus Petal", ai, ZoneType.Battlefield);
        addCardToZone("Doomsday", ai, ZoneType.Hand);
        addCardToZone("Misdirection", ai, ZoneType.Hand);
        addCardToZone("Dark Ritual", ai, ZoneType.Hand);
        addCardToZone("Street Wraith", ai, ZoneType.Hand);
        return ai;
    }

    /** One pick of the pile, taken out of the options before the next, as ComboPlan.choose asks them. */
    private static String pickOnce(final Player ai, final CardCollection options) {
        final Card pick = DoomsdayPile.pick(ai, options);
        if (pick == null) {
            return null;
        }
        options.remove(pick);
        return pick.getName();
    }

    /** The five picks of a game played alone, on a game thread under its own scope, as the harness plays it. */
    private static List<String> alone(final Player ai, final SimScope scope) throws Exception {
        final List<String> picks = new ArrayList<>();
        final AtomicReference<Throwable> failed = new AtomicReference<>();
        final Thread t = new Thread(() -> {
            SimScope.enter(scope);
            try {
                final CardCollection options = new CardCollection(ai.getCardsIn(ZoneType.Library));
                for (int i = 0; i < PICKS; i++) {
                    picks.add(pickOnce(ai, options));
                }
            } catch (Throwable e) {
                failed.set(e);
            } finally {
                SimScope.exit();
            }
        }, "Game-sim-doomsday-alone");
        t.setDaemon(true);
        t.start();
        t.join(120_000);
        if (t.isAlive()) {
            throw new AssertionError("the picks did not finish within 120 s");
        }
        if (failed.get() != null) {
            throw new AssertionError(failed.get());
        }
        return picks;
    }

    /**
     * The picks of two games played at once, each on its own thread, bound to its own scope for the whole game as a
     * serve JVM's slots are, taking turns pick by pick: game 0 picks, then game 1, then game 0 again.
     */
    private static List<List<String>> sideBySide(final Player[] seats, final SimScope[] scopes) throws Exception {
        final List<List<String>> picks = List.of(new ArrayList<>(), new ArrayList<>());
        final Semaphore[] turn = { new Semaphore(1), new Semaphore(0) };
        final AtomicReference<Throwable> failed = new AtomicReference<>();
        final Thread[] games = new Thread[2];
        for (int g = 0; g < 2; g++) {
            final int me = g;
            games[g] = new Thread(() -> {
                SimScope.enter(scopes[me]);
                try {
                    final CardCollection options = new CardCollection(seats[me].getCardsIn(ZoneType.Library));
                    for (int i = 0; i < PICKS; i++) {
                        if (!turn[me].tryAcquire(120, TimeUnit.SECONDS)) {
                            throw new AssertionError("game " + me + " waited 120 s for its turn");
                        }
                        try {
                            picks.get(me).add(pickOnce(seats[me], options));
                        } finally {
                            turn[1 - me].release();
                        }
                    }
                } catch (Throwable e) {
                    failed.compareAndSet(null, e);
                    turn[1 - me].release(PICKS); // the other game never waits on one that failed
                } finally {
                    SimScope.exit();
                }
            }, "Game-sim-doomsday-" + me);
            games[g].setDaemon(true);
        }
        for (final Thread t : games) {
            t.start();
        }
        for (final Thread t : games) {
            t.join(240_000);
            if (t.isAlive()) {
                throw new AssertionError("a game's picks did not finish within 240 s");
            }
        }
        if (failed.get() != null) {
            throw new AssertionError(failed.get());
        }
        return picks;
    }

    @Test(timeOut = 600_000)
    public void twoGamesOfOneServeJvmKeepTheirOwnPilePlans() throws Exception {
        final List<String> planned = alone(doomsdaySeat(), new SimScope(1L));
        assertEquals("five picks, the pile: " + planned, PICKS, planned.size());
        assertFalse("every pick exists: " + planned, planned.contains(null));
        assertTrue("the picks came out of a plan: the Lotus that pays for the Oracle is kept, which pick()'s scores"
                + " alone never keep among five, the picks were " + planned, planned.contains("Black Lotus"));

        final Player[] seats = { doomsdaySeat(), doomsdaySeat() };
        assertEquals("the premise: the same seat of two games is one key of a map keyed by Player", seats[0], seats[1]);
        final List<List<String>> got = sideBySide(seats, new SimScope[] { new SimScope(2L), new SimScope(3L) });
        assertEquals("game 0 is served out of its own plan while game 1 plans beside it", planned, got.get(0));
        assertEquals("game 1 makes its own plan and is not served out of game 0's", planned, got.get(1));
    }
}
