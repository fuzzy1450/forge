package forge.sim;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import forge.sim.GameRunner.GameResult;
import forge.sim.GameRunner.GameSpec;

/** Plays one job on a GameRunner and hands each game's record, then the summary, to a sink (harness-in-engine
 *  spec 3.1). */
public final class JobRunner {
    private final GameRunner runner;

    public JobRunner(GameRunner runner) {
        this.runner = runner;
    }

    /** Plays every game of {@code job} in order; hands each record and finally the summary to {@code sink} as
     *  Maps (the caller serialises). Returns the summary's counts. A game that times out or errors is recorded
     *  and the job continues. A PoisonedException from the runner propagates after the games played so far
     *  were handed over; no summary is written then. An IllegalArgumentException from the runner -- registerPlayers
     *  refusing a deck it cannot load, an unknown profile or an unknown AI, or a timeout_s below 1 -- comes out at
     *  the first game, after no record and with no summary. An InterruptedException propagates with no record for
     *  the interrupted game and no summary. A poison caused by the job's own last game is not reported here: the
     *  summary is written and run returns normally, so a caller that must know asks the runner
     *  ({@link GameRunner#isPoisoned()}). */
    public Map<String, Integer> run(JobFile job, Consumer<Map<String, Object>> sink) throws InterruptedException {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String r : GameRecords.END_REASONS) {
            counts.put(r, 0);
        }
        long totalMs = 0;
        List<GameSpec> specs = job.specs();
        for (int g = 0; g < specs.size(); g++) {
            GameSpec spec = specs.get(g);
            AtomicReference<GameRecords.Stats> stats = new AtomicReference<>();
            GameResult r = runner.play(spec, game -> {
                stats.set(new GameRecords.Stats(game, spec.seats().size()));
                game.subscribeToEvents(stats.get());
            });
            // Read once, so that record and fallbackRecord see the same Stats. Null when the observer never ran (the
            // game was never created, or was given up on first): then a Stats with no game, read with no live state.
            GameRecords.Stats collected = stats.get();
            if (collected == null) {
                collected = new GameRecords.Stats(spec.seats().size());
            }
            Map<String, Object> rec;
            try {
                rec = GameRecords.record(collected, g, spec.seed(), job.seats, r.timedOut(), r.error(), r.ms());
            } catch (OutOfMemoryError oom) {
                throw oom;                                            // as the harness: the uncaught handler halts
            } catch (Throwable t) {
                rec = GameRecords.fallbackRecord(collected, g, spec.seed(), job.seats, r.timedOut(), r.error(), t, r.ms());
            }
            if (r.violations() > 0) {
                rec.put("violations", r.violations());               // harness-in-engine spec 3.3: present only when non-zero
            }
            counts.merge((String) rec.get("end_reason"), 1, Integer::sum);
            totalMs += (Long) rec.get("ms");
            sink.accept(rec);
        }
        sink.accept(GameRecords.summary(job.games, counts, totalMs));
        return counts;
    }
}
