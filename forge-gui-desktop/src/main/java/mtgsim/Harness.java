package mtgsim;

import java.io.BufferedReader;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import forge.StaticData;
import forge.card.CardDb;
import forge.item.PaperCard;
import forge.localinstance.properties.ForgeConstants;
import forge.sim.GameRecords;
import forge.sim.GameRunner;
import forge.sim.JobFile;
import forge.sim.JobRunner;
import forge.sim.Precons;
import forge.sim.Serve;

/**
 * The farm harness, inside the engine since the shared-JVM program's sub-project 2
 * (MTG_DeckMaker docs/superpowers/specs/2026-10-06-harness-in-engine-design.md).
 *
 *   mtgsim.Harness check {@code <names.txt>}   resolve card names against Forge's database
 *   mtgsim.Harness run {@code <job.job>}       play the job, one game at a time, records on stdout
 *   mtgsim.Harness serve [--slots N]           play jobs from stdin on N slots (default 1), attributed records on stdout
 *   mtgsim.Harness --selftest                  name checks + two precon games
 *
 * Exit codes: 0 ok, 1 selftest failed, 2 fatal (usage, a boot failure, a job, deck or profile that cannot be loaded,
 * or any other exception out of a command), 3 one-shot run poisoned (a timed-out game's thread outlived its grace),
 * 4 uncaught throwable (GameRunner's halt handler: an Error, an OutOfMemoryError above all), 5 serve poisoned. The
 * class keeps its name so every launcher's classpath finds it first in the jar. {@link #main} is {@link #launch} and
 * an exit; the work is forge.sim's (JobFile, JobRunner, Serve).
 */
public final class Harness {
    static final int EXIT_FATAL = 2;
    static final int EXIT_TIMEOUT_RESTART = 3;
    static final PrintStream OUT = new PrintStream(new FileOutputStream(FileDescriptor.out), true, StandardCharsets.UTF_8);
    static final PrintStream ERR = new PrintStream(new FileOutputStream(FileDescriptor.err), true, StandardCharsets.UTF_8);
    /** A poisoned runner, said in this class's words: GameRunner's own message names the server's exit code. */
    static final String POISONED = "poisoned: a game thread outlived its cancellation";

    private Harness() { }

    public static void main(String[] args) {
        int code = launch(args, new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8)), OUT, ERR);
        System.exit(code);                                        // Forge leaves non-daemon threads behind
    }

    /** main's body less its exit, public for the tests. No arguments is the usage line and 2 before anything else, as
     *  the frozen harness had it. Otherwise System.out goes onto {@code err} first, then GameRunner's halt handler is
     *  installed and Forge boots: a boot that throws a RuntimeException or a LinkageError (a NoClassDefFoundError,
     *  ExceptionInInitializerError or UnsatisfiedLinkError from a broken install) is "fatal: boot failed" and 2
     *  (spec 6), while an OutOfMemoryError or any other VirtualMachineError goes on to the halt handler (4). Then
     *  {@link #dispatch}; both streams are flushed and the exit code returned. The halt handler and System.out stay as
     *  it set them. */
    public static int launch(String[] args, BufferedReader stdin, PrintStream out, PrintStream err) {
        if (args.length < 1) {
            return usage(err);                                    // before any Forge class is touched, as the frozen harness
        }
        System.setOut(err);                                       // before anything else: no Forge line, boot's included, prefixes a JSON line
        GameRunner.installHaltOnUncaught();                       // an Error from here on, in boot or in any job, halts with 4
        try {
            GameRunner.boot();                                    // GuiDesktop, FModel, strict mode, the halt handler again
        } catch (RuntimeException | LinkageError bootFailed) {   // spec 6: 2; OutOfMemoryError and other VirtualMachineErrors still halt with 4
            err.println("fatal: boot failed");
            bootFailed.printStackTrace(err);
            err.flush();
            return EXIT_FATAL;
        }
        int code = dispatch(args, stdin, out, err);
        out.flush();
        err.flush();
        return code;
    }

    /** The command and its exit code, without exiting and without touching the halt handler or System.out: the tests'
     *  entry. Boot must have run. Any exception out of a command is a fatal line and 2, as the frozen harness's catch of
     *  Exception made it; only an Error goes on to the halt handler. */
    public static int dispatch(String[] args, BufferedReader stdin, PrintStream out, PrintStream err) {
        if (args.length < 1) {
            return usage(err);
        }
        try {
            switch (args[0]) {
                case "check":      return check(Paths.get(need(args, 1)), out);
                case "run":        return run(Paths.get(need(args, 1)), out, err);
                case "serve":      return serve(args, stdin, out, err);
                case "--selftest": return selftest(out, err);
                default:           return usage(err);
            }
        } catch (Usage u) {
            return usage(err);
        } catch (JobFile.BadJob | IllegalArgumentException | IOException | UncheckedIOException e) {
            err.println("fatal: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            return EXIT_FATAL;
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            err.println("fatal: interrupted");
            return EXIT_FATAL;
        } catch (RuntimeException e) {                          // anything else out of a command: 2, as today
            err.println("fatal: " + e);
            e.printStackTrace(err);
            return EXIT_FATAL;
        }
    }

    // ------------------------------------------------------------------ run

    /** The one-shot: the job file's games in order on a runner of one slot. */
    static int run(Path jobFile, PrintStream out, PrintStream err) throws IOException, InterruptedException {
        return run(JobFile.load(jobFile), new GameRunner(1), out, err);
    }

    /** Plays {@code job} on {@code runner}, public for the tests: each record, then the summary, as one JSON line on
     *  {@code out}, and a progress line per game on {@code err}. Returns 0, or 3 when the runner is poisoned: either its
     *  PoisonedException stopped the job (no summary then) or the job's own last game poisoned it, which JobRunner
     *  does not report (the summary is written). An IllegalArgumentException, a deck, profile or AI the runner cannot
     *  load, comes out before the first game, with no record and no summary, a job of no games included. */
    public static int run(JobFile job, GameRunner runner, PrintStream out, PrintStream err) throws InterruptedException {
        try {
            new JobRunner(runner).run(job, rec -> {
                out.println(GameRecords.Json.write(rec));
                out.flush();
                if (!rec.containsKey("summary")) {
                    err.println("game " + ((Integer) rec.get("game") + 1) + "/" + job.games + " " + rec.get("end_reason")
                            + " " + rec.get("ms") + "ms");
                }
            });
        } catch (GameRunner.PoisonedException poisoned) {
            err.println(POISONED + "; exit " + EXIT_TIMEOUT_RESTART);
            return EXIT_TIMEOUT_RESTART;                           // the v26 client restarts the JVM on 3 and resumes at the next seed
        }
        if (runner.isPoisoned()) {                                 // the last game's thread outlived its grace after it was recorded
            err.println(POISONED + "; exit " + EXIT_TIMEOUT_RESTART);
            return EXIT_TIMEOUT_RESTART;
        }
        return 0;
    }

    // ------------------------------------------------------------------ serve

    static int serve(String[] args, BufferedReader stdin, PrintStream out, PrintStream err) throws InterruptedException {
        int slots = 1;
        for (int i = 1; i < args.length; i++) {
            if (args[i].equals("--slots") && i + 1 < args.length) {
                try {
                    slots = Integer.parseInt(args[++i]);
                } catch (NumberFormatException e) {
                    throw new Usage();
                }
            } else {
                throw new Usage();
            }
        }
        if (slots < 1) {
            throw new Usage();
        }
        return serve(new GameRunner(slots), stdin, out, err);
    }

    /** The server loop on {@code runner}, public for the tests: Serve's exit code, 0, 4 or 5, passed through. */
    public static int serve(GameRunner runner, BufferedReader stdin, PrintStream out, PrintStream err)
            throws InterruptedException {
        return new Serve(runner, stdin, out, err).run();
    }

    // ------------------------------------------------------------------ check

    static PaperCard lookup(String name) {
        CardDb db = StaticData.instance().getCommonCards();
        PaperCard pc = db.getCard(name);
        if (pc == null && name.contains(" // ")) {
            pc = db.getCard(name.split(" // ")[0].trim());
        }
        if (pc == null) {
            pc = StaticData.instance().getVariantCards().getCard(name);
        }
        return pc;
    }

    static int check(Path namesFile, PrintStream out) throws IOException {
        for (String line : Files.readAllLines(namesFile, StandardCharsets.UTF_8)) {
            String name = line.trim();
            if (name.isEmpty()) {
                continue;
            }
            PaperCard pc = lookup(name);
            Map<String, Object> rec = new LinkedHashMap<>();
            rec.put("name", name);
            rec.put("forge", pc == null ? null : pc.getName());
            out.println(GameRecords.Json.write(rec));
        }
        return 0;
    }

    // ------------------------------------------------------------------ selftest

    /** Today's selftest: three lookups and a split card, then the first two Commander precons through a JobRunner on
     *  one slot, two games; any game ending in Error fails it. Exit 1 on a failure. */
    static int selftest(PrintStream out, PrintStream err) throws InterruptedException {
        String[][] expect = {{"Sol Ring", "Sol Ring"}, {"Treasure Map // Treasure Cove", "Treasure Map"},
                             {"Not A Real Card Xyzzy", null}};
        for (String[] e : expect) {
            PaperCard pc = lookup(e[0]);
            String got = pc == null ? null : pc.getName();
            if (!Objects.equals(got, e[1])) {
                err.println("selftest: lookup(" + e[0] + ") = " + got + ", expected " + e[1]);
                return 1;
            }
        }
        if (lookup("Fire // Ice") == null) {
            err.println("selftest: lookup(Fire // Ice) returned null");
            return 1;
        }
        List<Path> precons;
        try {
            precons = Precons.commander();
        } catch (IllegalStateException unreadable) {
            err.println("selftest: " + unreadable.getMessage());
            return 1;
        }
        if (precons.size() < 2) {
            err.println("selftest: fewer than two Commander precons in " + ForgeConstants.QUEST_PRECON_DIR);
            return 1;
        }
        List<String> lines = new ArrayList<>(List.of("games=2", "seed=1", "timeout_s=300"));
        for (int i = 0; i < 2; i++) {
            lines.add("seat." + i + ".deck_file=" + precons.get(i).toAbsolutePath());
            lines.add("seat." + i + ".deck_hash=selftest");
        }
        List<Map<String, Object>> records = new ArrayList<>();
        GameRunner runner = new GameRunner(1);
        try {
            new JobRunner(runner).run(JobFile.parse(lines), rec -> {
                if (rec.containsKey("summary")) {
                    return;
                }
                records.add(rec);
                err.println("selftest game " + ((Integer) rec.get("game") + 1) + ": " + rec.get("end_reason") + " in "
                        + rec.get("ms") + " ms, " + ((List<?>) rec.get("cards")).size() + " card rows");
            });
        } catch (GameRunner.PoisonedException poisoned) {
            err.println("selftest: " + POISONED);
            return 1;
        }
        if (runner.isPoisoned()) {
            err.println("selftest: " + POISONED);
            return 1;
        }
        for (Map<String, Object> rec : records) {
            if ("Error".equals(rec.get("end_reason"))) {
                err.println("selftest: game errored: " + rec.get("error"));
                return 1;
            }
            if (((List<?>) rec.get("seats")).size() != 2) {
                err.println("selftest: expected 2 seats");
                return 1;
            }
        }
        out.println("selftest ok");
        return 0;
    }

    // ------------------------------------------------------------------ usage

    /** A command line dispatch cannot read: answered with the usage line and exit 2. */
    private static final class Usage extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }

    static String need(String[] args, int i) {
        if (args.length <= i) {
            throw new Usage();
        }
        return args[i];
    }

    static int usage(PrintStream err) {
        err.println("usage: mtgsim.Harness check <names.txt> | run <job.job> | serve [--slots N] | --selftest");
        return EXIT_FATAL;
    }
}
