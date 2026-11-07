package forge.sim;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import forge.ai.AiController;
import forge.ai.PlayerControllerAi;
import forge.ai.simulation.DeckPlan;
import forge.ai.simulation.PlanSignature;
import forge.game.Game;
import forge.game.player.Player;
import forge.sim.GameRunner.GameResult;
import forge.sim.GameRunner.GameSpec;
import forge.sim.GameRunner.SeatSpec;

/**
 * The deck plan a list reads as, by the game's own read (MTG_DeckMaker's tools/combo-ai/f5-guard/SPEC.md, section 3.1).
 * Python cannot answer this, because the classifier reads spell abilities and costs from card scripts. So a list plays
 * itself, on one runner, under a fresh {@link forge.util.SimScope}, and {@link GameRunner#play(GameSpec,
 * java.util.function.Consumer, java.util.function.Consumer)} stops the game before turn 1's first priority, where the
 * first keep question has filled the field a real game holds. It is read there, and again, fresh, in the other seat's
 * shuffle: a stored answer stands for good, so this is the one check a list gets.
 * <p>
 * Only the plan verb and {@code --selftest} call this, each in a JVM of its own, and neither game is played on.
 */
public final class PlanReader {
    static final long SEED = 1L;
    static final int TIMEOUT_S = 120;

    private PlanReader() { }

    /** The two reads, at the hook. {@code held} is {@code first}'s field. {@code fresh} is {@code forSetting("true",
     *  other)}: the table of plans is one per game and both seats share it, so the other seat's brain would only find the
     *  first seat's plan, and two equal texts would prove nothing, while "true" reads as "auto" under a key the keep
     *  question left no entry at, so this one walks the other seat's cards through the code a game runs. It takes ids, in
     *  a game that is being thrown away. */
    record Reads(DeckPlan held, DeckPlan fresh, Map<String, Object> asRead, Map<String, Object> again) {
        static Reads at(Game g) {
            Player first = g.getStartingPlayer();               // asked first, so the plan both seats share walked its cards
            Player other = null;
            for (Player p : g.getRegisteredPlayers()) {
                if (p != first) {
                    other = p;
                    break;
                }
            }
            AiController brain = ((PlayerControllerAi) first.getController()).getAi();
            DeckPlan held = brain.deckPlan(first);
            DeckPlan fresh = DeckPlan.forSetting("true", other);
            return new Reads(held, fresh, PlanSignature.of(held, first), PlanSignature.of(fresh, other));
        }
    }

    /** One line of a lists file, {@code <id> <deck file>}, the path being the rest of the line, as Serve splits a run
     *  command: that list's answer, as {@link #read}. The line is not blank. */
    public static Map<String, Object> readLine(String line, GameRunner runner) throws InterruptedException {
        String[] parts = line.trim().split("\\s+", 2);
        if (parts.length < 2) {
            return line(parts[0], "error", "no deck file");
        }
        Path deck;
        try {
            deck = Paths.get(parts[1]);
        } catch (InvalidPathException e) {
            return line(parts[0], "error", describe(e));
        }
        return read(parts[0], deck, runner);
    }

    /** The answer for one list: {@code {"id", "plan"}}, or {@code {"id", "error"}} when it has none, and a list that
     *  cannot load is an answer too. A PoisonedException from the runner comes out, for the caller to stop on. A read that
     *  poisons the runner is answered as any other: the caller asks {@link GameRunner#isPoisoned()}, as {@code run} does. */
    public static Map<String, Object> read(String id, Path deck, GameRunner runner) throws InterruptedException {
        SeatSpec seat = new SeatSpec(deck, "Default", "default");
        AtomicReference<Reads> reads = new AtomicReference<>();
        GameResult result;
        try {
            result = runner.play(new GameSpec(SEED, TIMEOUT_S, List.of(seat, seat)), g -> { }, g -> reads.set(Reads.at(g)));
        } catch (GameRunner.PoisonedException poisoned) {
            throw poisoned;
        } catch (RuntimeException refused) {                    // what play checks before it takes a slot: the deck above all
            return line(id, "error", describe(refused));
        }
        Reads got = reads.get();
        return answer(id, result, got == null ? null : got.asRead(), got == null ? null : got.again());
    }

    /** The step that gives a list its answer. A line carries {@code plan} only when the game reached the hook inside its
     *  deadline, the two reads gave one text, strict mode refused nothing and the list is under the pair cap. The result
     *  says Error when both reads landed, because the game did not finish; that means nothing here. A Timeout does: the
     *  reads landed in the grace, in a game the runner had given up on, and no answer is the safe side. */
    static Map<String, Object> answer(String id, GameResult result, Map<String, Object> asRead, Map<String, Object> again) {
        if (asRead == null || again == null) {
            return line(id, "error", "no read: " + result.endReason() + ": " + result.error());
        }
        if (result.timedOut()) {
            return line(id, "error", "the read ran past its deadline of " + TIMEOUT_S + " s");
        }
        if (result.violations() != 0) {
            return line(id, "error", "strict mode refused " + result.violations() + " access(es) during the read");
        }
        String one = GameRecords.Json.write(asRead), other = GameRecords.Json.write(again);
        if (!one.equals(other)) {
            return line(id, "error", "the two reads differ: " + one + " against " + other);
        }
        int pairs = ((List<?>) asRead.get("known_pairs")).size();
        if (pairs >= PlanSignature.PAIR_CAP) {
            return line(id, "error", pairs + " known pairs, at the cap of " + PlanSignature.PAIR_CAP);
        }
        return line(id, "plan", asRead);
    }

    private static Map<String, Object> line(String id, String key, Object value) {
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("id", id);
        line.put(key, value);
        return line;
    }

    private static String describe(RuntimeException e) {
        return e.getClass().getSimpleName() + ": " + e.getMessage();
    }
}
