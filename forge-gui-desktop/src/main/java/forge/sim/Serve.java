package forge.sim;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The harness's server loop (harness-in-engine spec 4): reads commands from {@code in}, one per line, and runs the
 * jobs they name on one shared {@link GameRunner}, one job per slot and each job's games in order, so never more games
 * at once than the runner has slots. It takes streams rather than System.in and System.out, so that tests drive it in
 * memory.
 * <ul>
 * <li>{@code run <id> <job file>}: the id is any token not used before; the job file is the rest of the line. Once the
 * runner is poisoned, a run is answered with one {@code {"job": id, "error": "poisoned"}} and nothing further, its job
 * file unread. Before that, a job whose file loads and whose first game passes the checks {@link GameRunner#play}
 * makes before it takes a slot (two seats or more, a timeout of a second or more, every deck, profile and AI loaded)
 * is answered {@code {"job": id, "accepted": true, "games": n}} and waits FIFO for a free slot; any other is answered
 * with one {@code {"job": id, "error": "..."}} and nothing further.</li>
 * <li>{@code quit}, or the end of {@code in}: accept nothing more, finish the queue and the jobs in flight.</li>
 * <li>Anything else, a {@code run} without an id and a file, or one whose id was used before: one
 * {@code {"job": null, "error": "..."}}.</li>
 * </ul>
 * Every line on {@code out} is one JSON object whose first key is {@code job}. An accepted job's lines are its accepted
 * line, then its records in game order, then its summary or one error line, either of which ends it. Lines of
 * different jobs interleave. A poisoned runner plays no further game: each accepted job with games still to play, or
 * still waiting for its slot, ends with {@code "error": "poisoned"} instead of a summary; a run read after the poison
 * is refused as above; and once the input has ended and the drain is done, {@link #run} returns
 * {@link #EXIT_POISONED}. {@code err} gets a line when an accepted job starts playing and one when it ends:
 * {@code done} with its counts, or its error; a run refused as poisoned gets that end line too.
 */
public final class Serve {
    public static final int EXIT_OK = 0;
    /** GameRunner's halt handler's code: a throwable escaped a job. */
    public static final int EXIT_UNCAUGHT = GameRunner.EXIT_UNCAUGHT;
    public static final int EXIT_POISONED = GameRunner.EXIT_POISONED;
    /** How many characters of an unknown command its error line quotes. */
    private static final int CLIP = 120;

    private final GameRunner runner;
    private final BufferedReader in;
    private final PrintStream out;
    private final PrintStream err;
    private final Set<String> ids = new HashSet<>();           // the reading thread's alone: every id that has had a line

    public Serve(GameRunner runner, BufferedReader in, PrintStream out, PrintStream err) {
        this.runner = runner;
        this.in = in;
        this.out = out;
        this.err = err;
    }

    /** Runs the command loop to its end (quit or EOF, then every accepted job finished or refused) and returns the
     *  exit code: {@link #EXIT_UNCAUGHT} (4) when a throwable escaped a job, else the runner's own
     *  {@link GameRunner#exitCodeAfterDrain()}: {@link #EXIT_POISONED} (5) when it was poisoned, else
     *  {@link #EXIT_OK} (0). A throwable out of a job still goes on to its slot thread's uncaught handler, which the
     *  facade makes GameRunner's halt handler, so that exit code only matters when the drain ends before the halt. An
     *  Error on the reading thread itself leaves at once: nothing more is accepted, and the queue is not waited for. */
    public int run() throws InterruptedException {
        AtomicInteger threads = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(runner.slots(), r -> {
            Thread t = new Thread(r, "serve-slot-" + threads.getAndIncrement());
            t.setDaemon(true);
            return t;
        });
        AtomicReference<Throwable> fatal = new AtomicReference<>();
        try {
            String line;
            while ((line = readLine()) != null) {
                String trimmed = line.strip();
                if (trimmed.isEmpty()) {
                    continue;
                }
                if (trimmed.equals("quit")) {
                    break;
                }
                String[] parts = trimmed.split("\\s+", 3);           // the job file is the rest of the line: its path may hold spaces
                if (!parts[0].equals("run")) {
                    emitError(null, "unknown command: " + clip(trimmed));
                    continue;
                }
                if (parts.length != 3) {
                    emitError(null, "usage: run <id> <job file>");
                    continue;
                }
                String id = parts[1];
                if (!ids.add(id)) {
                    emitError(null, "job id already used: " + id);
                    continue;
                }
                if (runner.isPoisoned()) {                            // no game will start: refused here, its id spent, its file unread
                    fail(id, "poisoned");
                    continue;
                }
                JobFile job;
                try {
                    job = JobFile.load(Paths.get(parts[2]));
                    GameRunner.validate(job.firstSpec());             // a deck, profile or AI the runner cannot load: the job cannot start
                } catch (IOException | RuntimeException e) {          // BadJob is a RuntimeException, as is a path Paths.get refuses
                    emitError(id, describe(e));
                    continue;
                }
                Map<String, Object> accepted = new LinkedHashMap<>();
                accepted.put("accepted", true);
                accepted.put("games", job.games);
                emit(id, accepted);                                   // written before the job can write a line of its own
                // execute, not submit: a throwable out of a job must reach the slot thread's uncaught handler, which the
                // facade makes GameRunner's halt handler (exit 4); submit would keep it in a Future nobody reads. It is
                // noted first, so that run answers 4 too if the drain ends before the halt.
                pool.execute(() -> {
                    try {
                        playJob(id, job);
                    } catch (Throwable t) {
                        fatal.compareAndSet(null, t);
                        throw t;
                    }
                });
            }
        } finally {
            pool.shutdown();                                          // accept nothing more, however the loop ended
        }
        pool.awaitTermination(Long.MAX_VALUE, TimeUnit.MILLISECONDS);   // only after a quit or EOF: the queue and the jobs in flight finish
        if (fatal.get() != null) {
            return EXIT_UNCAUGHT;
        }
        return runner.exitCodeAfterDrain();
    }

    /** One job on a slot thread: its records, then its summary, or else one error line that ends it. */
    private void playJob(String id, JobFile job) {
        if (runner.isPoisoned()) {
            fail(id, "poisoned");
            return;
        }
        err.println("job " + id + ": " + job.games + " games");
        try {
            Map<String, Integer> counts = new JobRunner(runner).run(job, rec -> emit(id, rec));
            err.println("job " + id + ": done " + counts);
        } catch (GameRunner.PoisonedException poisoned) {
            fail(id, "poisoned");                                     // the games played so far were written; no summary
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            fail(id, "interrupted");
        } catch (RuntimeException e) {
            // The runner's own refusals were checked when the job was accepted (GameRunner.validate), so one here means a
            // deck file changed or went away since, or a bug: either way this error line ends the job, with no summary.
            fail(id, describe(e));
        }
    }

    /** Ends a job with an error, an accepted one or a run refused because the runner is poisoned: its error line on
     *  {@code out}, and its end line on {@code err}. */
    private void fail(String id, String message) {
        emitError(id, message);
        err.println("job " + id + ": " + message);
    }

    /** Writes one line: {@code job} first, then {@code body}'s entries in their order, under one lock so that lines
     *  written from several slots never mix. */
    private void emit(String id, Map<String, Object> body) {
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("job", id);
        line.putAll(body);
        String json = GameRecords.Json.write(line);
        synchronized (out) {
            out.println(json);
            out.flush();
        }
    }

    /** One {@code {"job": id, "error": message}} line; the id is null for a line that names no job. */
    private void emitError(String id, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", message);
        emit(id, body);
    }

    /** An exception as an error line gives it: its class's simple name, then its message. */
    private static String describe(Exception e) {
        return e.getClass().getSimpleName() + ": " + e.getMessage();
    }

    /** The next line of {@code in}, or null at its end; a read that fails is its end too, so it drains as quit does. */
    private String readLine() {
        try {
            return in.readLine();
        } catch (IOException e) {
            return null;
        }
    }

    private static String clip(String command) {
        return command.length() <= CLIP ? command : command.substring(0, CLIP);
    }
}
