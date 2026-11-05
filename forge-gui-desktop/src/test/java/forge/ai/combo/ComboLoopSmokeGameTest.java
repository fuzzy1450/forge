package forge.ai.combo;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;

import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import com.google.common.eventbus.Subscribe;

import forge.game.Game;
import forge.game.card.Card;
import forge.game.event.GameEventLandPlayed;
import forge.game.event.GameEventSpellAbilityCast;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.zone.ZoneType;
import forge.localinstance.properties.ForgeConstants;
import forge.sim.GameRunner;
import forge.util.SimScope;

/**
 * One whole game of the Kinnan smoke (MTG_DeckMaker's ailab.kinnan_smoke): its fixture, combo-smoke-kinnan.dck, in
 * seat 0 against the combo smoke's precon, Arcane Wizardry, both on the Default profile and the default AI, played by
 * the engine's own game body (GameRunner, as the served harness plays a job). Kinnan, Bonder Prodigy, our commander and
 * a piece of the rock untap with Basalt Monolith, comes down in our Main 1 of the first turn whose land drop gives us a
 * Forest and an Island (spec 2026-10-29-combo-aware-ai-loops-design.md section 6.7, amended 2026-11-04 (the commander
 * cast in Main 1)). The stock AI held it for Main 2, where Walking Ballista's {X}{X} or Basalt Monolith took the mana
 * first: in the smoke's second game (seed 778000000001), before the merge of upstream's 626aa9f0735, it came on turn 11
 * while our lands made {G}{U} from turn 3 (the Kinnan cast diagnosis's replay,
 * tools/combo-ai/sdd/p2-kinnan-cast-diagnosis.md section 2.2). Since that merge both seats mulligan the sevens they
 * kept in that game (upstream's smart mulligan, on by default since e5a0f8156da) and our lands there are Forests
 * alone, so this test plays the smoke's third game (seed 778000000002): our lands make {G}{U} on turn 3, and with
 * LoopDriver.commanderInMainOne taken out of ComboHold.liftsMainTwoWait Kinnan came on turn 15, in Main 2 (measured on
 * the merge). GameRunner.boot leaves strict mode on, so it is relaxed after the class (GameRunnerTest's idiom) for the
 * AITest classes that share the JVM.
 */
public class ComboLoopSmokeGameTest {
    private static final String KINNAN = "Kinnan, Bonder Prodigy";
    private static final String FOREST = "Forest";
    private static final String ISLAND = "Island";
    private static final String SEAT0 = "Seat0";
    /** The smoke's fixture, sim/fixtures/combo-smoke-kinnan.dck in MTG_DeckMaker, byte for byte. */
    private static final String FIXTURE = "[metadata]\r\nName=combo-smoke-kinnan\r\n[Commander]\r\n"
            + "1 Kinnan, Bonder Prodigy\r\n[Main]\r\n20 Basalt Monolith\r\n20 Walking Ballista\r\n30 Island\r\n"
            + "29 Forest\r\n";
    /** The fixture's sha256, as the smoke's game records carry it (the plan's Part C,
     *  tools/combo-ai/sdd/p2-c1-kinnan). */
    private static final String FIXTURE_SHA256 = "9fd410b3894513f0b9866a9e8de7bd9fe80c220eb7b47dda25d233e7081d38f4";
    /** The combo smoke's precon (ailab.COMBO_SMOKE_OPPONENT), from the engine's own res/quest/precons. */
    private static final String OPPONENT = "Arcane Wizardry.dck";
    /** The smoke's third game: its seeds are 778000000000 and up, one a game. */
    private static final long SEED = 778_000_000_002L;

    @BeforeClass
    public void boot() {
        GameRunner.boot();
    }

    @AfterClass
    public void relaxStrict() {
        SimScope.setStrict(false);
    }

    /** Seat 0's game, read from its events alone (state reads: no draw, no id, nothing changed): the turn and phase of
     *  the first land drop of ours after which we control a Forest and an Island, and those of our first cast of
     *  Kinnan. Each runs on the game thread, which the result's return follows. */
    public static final class Watch {
        private Game game;
        private int greenBlueTurn = -1;
        private PhaseType greenBluePhase;
        private int castTurn = -1;
        private PhaseType castPhase;

        @Subscribe
        public void onLand(final GameEventLandPlayed ev) {
            if (greenBlueTurn > 0 || !SEAT0.equals(ev.player().getName())) {
                return;
            }
            boolean forest = FOREST.equals(ev.land().getName());
            boolean island = ISLAND.equals(ev.land().getName());
            for (final Player p : game.getRegisteredPlayers()) {
                if (!SEAT0.equals(p.getName())) {
                    continue;
                }
                for (final Card c : p.getCardsIn(ZoneType.Battlefield)) {
                    forest |= c.isLand() && FOREST.equals(c.getName());
                    island |= c.isLand() && ISLAND.equals(c.getName());
                }
            }
            if (forest && island) {
                greenBlueTurn = game.getPhaseHandler().getTurn();
                greenBluePhase = game.getPhaseHandler().getPhase();
            }
        }

        @Subscribe
        public void onCast(final GameEventSpellAbilityCast ev) {
            if (castTurn > 0 || !ev.sa().isSpell() || ev.si().getActivatingPlayer() == null
                    || !SEAT0.equals(ev.si().getActivatingPlayer().getName()) || ev.sa().getHostCard() == null
                    || !KINNAN.equals(ev.sa().getHostCard().getName())) {
                return;
            }
            castTurn = game.getPhaseHandler().getTurn();
            castPhase = game.getPhaseHandler().getPhase();
        }
    }

    /** The game's outcome in a line: GameResult's own toString carries the whole digest. */
    private static String outcome(final GameRunner.GameResult r) {
        return "seed " + r.seed() + ", winner seat " + r.winnerSeat() + ", " + r.endReason() + ", " + r.turns()
                + " turns, first seat " + r.firstSeat() + (r.error() == null ? "" : ", error " + r.error());
    }

    @Test(timeOut = 600_000)
    public void kinnanComesDownOnItsFirstGreenBlueTurn() throws Exception {
        final byte[] bytes = FIXTURE.getBytes(StandardCharsets.UTF_8);
        assertEquals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)), FIXTURE_SHA256,
                "the fixture is not the smoke's");
        final Path precon = Paths.get(ForgeConstants.QUEST_PRECON_DIR, OPPONENT);
        assertTrue(Files.isRegularFile(precon), precon + " is missing");
        final Path fixture = Files.createTempFile("combo-smoke-kinnan", ".dck");
        try {
            Files.write(fixture, bytes);
            final GameRunner.GameSpec spec = new GameRunner.GameSpec(SEED, 300, List.of(
                    new GameRunner.SeatSpec(fixture, "Default", "default"),
                    new GameRunner.SeatSpec(precon, "Default", "default")));
            final Watch watch = new Watch();
            final GameRunner.GameResult r = new GameRunner(1).play(spec, game -> {
                watch.game = game;
                game.subscribeToEvents(watch);
            });
            assertNull(r.error(), outcome(r));
            assertTrue(watch.greenBlueTurn > 0, "our lands never made {G}{U}: " + outcome(r));
            assertEquals(watch.castTurn + " " + watch.castPhase, watch.greenBlueTurn + " " + PhaseType.MAIN1,
                    "Kinnan's first cast (turn and phase), beside our first land drop that made {G}{U}, turn "
                            + watch.greenBlueTurn + " in " + watch.greenBluePhase + "; " + outcome(r));
        } finally {
            Files.deleteIfExists(fixture);
        }
    }
}
