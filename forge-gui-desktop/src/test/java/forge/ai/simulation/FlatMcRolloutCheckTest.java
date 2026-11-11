package forge.ai.simulation;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNotEquals;
import static org.testng.Assert.assertSame;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.expectThrows;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import forge.ai.simulation.FlatMcGame.Tally;
import forge.ai.simulation.FlatMonteCarloPicker.Config;
import forge.ai.simulation.FlatMonteCarloPicker.Result;
import forge.card.MagicColor;
import forge.game.card.Card;
import forge.game.mana.Mana;
import forge.game.phase.PhaseType;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import forge.util.GameAbandoned;
import forge.util.SimScope;

/**
 * What runs around every rollout (MTG_DeckMaker's tools/combo-ai/flat-mc/SPEC.md, sections 4.5 to 4.7): the
 * real-game check, the search hash and the wall-clock figures. Under a bound scope {@link FlatMcGame#bracket}
 * snapshots the real game before each rollout and checks it on every return, a catch arm's null included, so a rollout
 * that writes the real game ends the game as an Error naming the field. The picker folds each rollout, with a last
 * draw from the rollout's own private stream, into its seat's hash, which the game's search lines carry into its
 * digest. The machine's clock times each rollout for the wall-clock diagnostic alone. The decisions compare
 * {@link #twoBears()}'s two lines at {@link #twoLineConfig()}, as FlatMcClockTest does, so "the 450 ms decision" makes
 * eight rollouts.
 */
public class FlatMcRolloutCheckTest extends FlatMcTestBase {
    /** What every message of the check starts with. */
    private static final String WROTE = "a rollout wrote the real game: ";
    /** SHA-256 of no bytes: a seat's hash before any fold. */
    private static final String SHA256_OF_NOTHING = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";

    private List<SpellAbility> two;
    private Config cfg;

    @BeforeMethod
    public void twoLines() {
        setUpGame();
        two = twoBears();
        assertEquals(two.size(), 2, String.valueOf(two));
        cfg = twoLineConfig();
    }

    /**
     * Each field the check reads, written after a fresh snapshot, is the field its Error names first. A card or a mana
     * that a write needs is made before the snapshot, so that the write alone is checked.
     */
    @Test
    public void theCheckNamesTheFieldARolloutWrote() {
        final long t = game.getTimestamp();
        assertEquals(wrote(game::getNextTimestamp), WROTE + "timestamp " + t + " -> " + (t + 1));
        assertNames(wrote(game::nextCardId), "next card id");
        final Card bear = createCard("Grizzly Bears", ai);
        assertNames(wrote(() -> ai.getZone(ZoneType.Hand).add(bear)), ai + "'s Hand zone");
        final Mana green = new Mana(MagicColor.GREEN, ai.getCardsIn(ZoneType.Battlefield).getFirst(), null, ai);
        assertNames(wrote(() -> ai.getManaPool().addMana(green)), ai + "'s floating mana");
        final Card shock = createCard("Shock", ai);
        assertNames(wrote(() -> game.getStackZone().add(shock)), "stack zone");
        at(PhaseType.MAIN1, ai);
        assertEquals(wrote(() -> game.getPhaseHandler().devModeSet(PhaseType.MAIN2, ai)),
                WROTE + "phase MAIN1 -> MAIN2");
        assertNames(wrote(() -> setTurn(6)), "turn");
    }

    /** A game that is only read passes: here a board with cards in a hand and on the battlefield. */
    @Test
    public void anUntouchedGamePassesTheCheck() {
        final RealGameSnapshot s = RealGameSnapshot.of(game);
        assertTrue(gameStateToString(game).contains("Grizzly Bears"));
        s.requireSame(game);
    }

    /** A rollout that writes the real game and then fails into a catch arm, which returns no value, is caught: the
     *  check runs on every return from the rollout, a catch arm's null included. */
    @Test
    public void aRolloutThatFailsAfterWritingTheRealGameIsCaught() {
        final Throwable thrown = Scoped.thrownBy(new SimScope(12L), () -> FlatMcGame.bracket(ai, 0, () -> {
            game.getNextTimestamp();
            return null;
        }));
        assertTrue(thrown instanceof RolloutWroteRealGame, String.valueOf(thrown));
        assertNames(thrown.getMessage(), "timestamp");
    }

    /** A throwable escaping the rollout skips the check and the timing: what ends the game is that throwable, not the
     *  check's Error, and the rollout, which took 5 ms, is not timed. */
    @Test
    public void anEscapingThrowableSkipsTheCheck() {
        final IllegalStateException escaping = new IllegalStateException("escapes the rollout");
        final SimScope scope = new SimScope(13L);
        final Throwable thrown = Scoped.thrownBy(scope, () -> FlatMcGame.bracket(ai, 0, () -> {
            game.getNextTimestamp();
            sleep(5);
            throw escaping;
        }));
        assertSame(thrown, escaping);
        assertEquals(FlatMcGame.peek(scope).slowestRolloutNanos(), 0L, "nor timed");
    }

    /** The 450 ms decision's eight rollouts each pass the check: the decision returns, the real game's zones read as
     *  they did before it, and the game has had a rollout. */
    @Test
    public void aSearchedDecisionLeavesTheRealGameAsItFoundIt() {
        final String before = gameStateToString(game);
        final SimScope scope = new SimScope(15L);
        final Result r = Scoped.under(scope, () -> FlatMonteCarloPicker.choose(ai, two, cfg, null));
        assertEquals(r.rollouts, 8, String.valueOf(r));
        assertEquals(gameStateToString(game), before);
        assertTrue(FlatMcGame.peek(scope).anyRollout());
    }

    /**
     * At one resolution per rollout every rollout ends in the cap's catch arm with no value. Each is still checked and
     * timed (the timing follows the check), and folded, so the game has one search line, its seat's.
     */
    @Test
    public void aCappedRolloutIsCheckedTimedAndFolded() {
        cfg.resolutionsPerRollout = 1;
        final int seat = game.getPlayers().indexOf(ai);
        final SimScope scope = new SimScope(16L);
        final Result r = Scoped.under(scope, () -> {
            FlatMcGame.current().registerSeat(seat, ai.getId());
            return FlatMonteCarloPicker.choose(ai, two, cfg, null);
        });
        assertEquals(r.reason, "heuristic-unsimulatable", String.valueOf(r));
        final FlatMcGame st = FlatMcGame.peek(scope);
        final Tally t = st.tally(ai.getId());
        assertTrue(t.rollouts >= 2, String.valueOf(t.toRecord()));
        assertEquals(t.resolutionCapStops, t.rollouts, "every rollout reached the cap");
        assertEquals(t.invalidLines, t.rollouts, "and every line is invalid");
        assertTrue(st.slowestRolloutNanos() > 0, "timed");
        assertTrue(st.anyRollout());
        final String hash = (String) t.toRecord().get("hash");
        assertNotEquals(hash, SHA256_OF_NOTHING, "folded");
        assertEquals(st.searchLines(), List.of("search\t" + seat + "\t" + t.rollouts + "\t" + t.playouts + "\t"
                + t.plays + "\t" + t.resolutions + "\t" + hash));
    }

    /**
     * A seat's hash folds what each rollout saw, the last draw of its own private stream included: two fresh games fed
     * the same five rollouts hash alike, and one whose third rollout's stream ended on another draw does not. A game
     * with no rollout has no search line, even for a registered seat, and its seat's hash is that of nothing.
     */
    @Test
    public void theHashSeesTheRolloutsOwnStream() {
        assertEquals(fiveFolds(102L), fiveFolds(102L), "the same five rollouts, the same hash");
        assertNotEquals(fiveFolds(7L), fiveFolds(102L), "one final draw apart, another hash");
        final int seat = game.getPlayers().indexOf(ai);
        final FlatMcGame none = Scoped.under(new SimScope(20L), () -> {
            final FlatMcGame st = FlatMcGame.current();
            st.registerSeat(seat, ai.getId());
            return st;
        });
        assertFalse(none.anyRollout());
        assertEquals(none.searchLines(), List.of());
        assertEquals(none.tally(ai.getId()).toRecord().get("hash"), SHA256_OF_NOTHING);
    }

    /** A scope's id counters and the game's card id counter are read without being stepped, and peek, which has no
     *  checkpoint, answers on a cancelled scope where nextId throws. */
    @Test
    public void countersAndCardIdsAreReadWithoutBeingStepped() {
        final SimScope s = new SimScope(9L);
        final int v = s.peek(SimScope.Counter.SPELL_ABILITY);
        assertEquals(s.peek(SimScope.Counter.SPELL_ABILITY), v, "a read steps nothing");
        s.nextId(SimScope.Counter.SPELL_ABILITY);
        assertEquals(s.peek(SimScope.Counter.SPELL_ABILITY), v + 1);
        assertEquals(s.peek(SimScope.Counter.COMBAT_VIEW), -2, "COMBAT_VIEW's next id, as a fresh scope starts it");
        s.cancel();
        assertEquals(s.peek(SimScope.Counter.SPELL_ABILITY), v + 1, "no checkpoint: a cancelled scope still answers");
        expectThrows(GameAbandoned.class, () -> s.nextId(SimScope.Counter.SPELL_ABILITY));
        final int id = game.getCardIdCounter();
        assertEquals(game.getCardIdCounter(), id);
        game.nextCardId();
        assertEquals(game.getCardIdCounter(), id + 1);
    }

    /**
     * The machine's clock times every rollout, and its two figures reach nothing else: the same decision on a fresh
     * scope of the same seed gives the same record, key for key and value for value, its hash included, while each
     * run's slowest rollout took some real time and none took 5 s.
     */
    @Test
    public void wallClockFiguresStayOutOfTheTally() {
        final SimScope first = new SimScope(17L);
        final SimScope second = new SimScope(17L);
        final Map<String, Object> a = searched(first);
        final Map<String, Object> b = searched(second);
        assertEquals(new ArrayList<>(a.keySet()), FlatMcGame.TALLY_KEYS);
        assertNotEquals(a.get("hash"), SHA256_OF_NOTHING, "the decision's rollouts are folded");
        assertEquals(b, a, "the same decision, the same record");
        for (final SimScope s : List.of(first, second)) {
            assertTrue(FlatMcGame.peek(s).slowestRolloutNanos() > 0);
            assertEquals(FlatMcGame.peek(s).rolloutsOver5s(), 0);
        }
    }

    /** The check's message after {@code write} on a fresh snapshot of the game. */
    private String wrote(final Runnable write) {
        final RealGameSnapshot s = RealGameSnapshot.of(game);
        write.run();
        return expectThrows(RolloutWroteRealGame.class, () -> s.requireSame(game)).getMessage();
    }

    /** {@code message} names {@code field} as the first difference. */
    private static void assertNames(final String message, final String field) {
        assertTrue(message.startsWith(WROTE + field + " "), message);
    }

    /**
     * The seat's hash after five rollouts on a fresh scope, each bracketed and then folded as the picker folds them:
     * the third rollout's private stream ends on {@code thirdDraw}, the others' on draws of their own, and the fourth
     * has no value.
     */
    private String fiveFolds(final long thirdDraw) {
        return Scoped.under(new SimScope(19L), () -> {
            final FlatMcGame st = FlatMcGame.current();
            for (int i = 0; i < 5; i++) {
                final long draw = i == 2 ? thirdDraw : 100L + i;
                final Long value = i == 3 ? null : Long.valueOf(10L * i);
                FlatMcGame.bracket(ai, 0, () -> {
                    st.noteFinalDraw(draw);
                    return value;
                });
                st.fold(ai.getId(), i % 2, i / 2, value);
            }
            return (String) st.tally(ai.getId()).toRecord().get("hash");
        });
    }

    /** The seat's record after the 450 ms decision under {@code scope}, noted as the controller notes a decision. */
    private Map<String, Object> searched(final SimScope scope) {
        return Scoped.under(scope, () -> {
            final FlatMcGame st = FlatMcGame.current();
            st.noteDecision(ai.getId(), FlatMonteCarloPicker.choose(ai, two, cfg, null));
            return st.tally(ai.getId()).toRecord();
        });
    }

    private static void sleep(final long ms) {
        try {
            Thread.sleep(ms);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted", e);
        }
    }
}
