package forge.sim;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import org.testng.Assert;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import forge.game.Game;
import forge.sim.GameRunner.GameResult;
import forge.sim.GameRunner.GameSpec;
import forge.util.SimScope;

public class HarnessFacadeTest {
    /** Every temp file the tests made: deleted after the class. */
    static final List<Path> TEMP = Collections.synchronizedList(new ArrayList<>());

    @BeforeClass
    public void boot() { GameRunner.boot(); }

    @AfterClass
    public void relaxStrict() { SimScope.setStrict(false); }

    @AfterClass
    public void deleteTempFiles() throws IOException {
        for (Path p : TEMP) {
            Files.deleteIfExists(p);
        }
        TEMP.clear();
    }

    static int dispatch(String stdin, String... args) {
        ByteArrayOutputStream outBytes = new ByteArrayOutputStream();
        PrintStream out = new PrintStream(outBytes, true, StandardCharsets.UTF_8);
        ByteArrayOutputStream errBytes = new ByteArrayOutputStream();
        PrintStream err = new PrintStream(errBytes, true, StandardCharsets.UTF_8);
        int code = mtgsim.Harness.dispatch(args, new BufferedReader(new StringReader(stdin)), out, err);
        lastOut = outBytes.toString(StandardCharsets.UTF_8);
        lastErr = errBytes.toString(StandardCharsets.UTF_8);
        return code;
    }

    static String lastOut = "";
    static String lastErr = "";

    /** A temp file of exactly these lines, deleted after the class. */
    static Path tempFile(List<String> lines) throws IOException {
        Path f = Files.createTempFile("facade", ".txt");
        TEMP.add(f);
        Files.write(f, lines, StandardCharsets.UTF_8);
        return f;
    }

    /** The one-shot run of {@code job} on {@code runner}: {exit code, stdout lines, stderr text}. */
    static Object[] runOn(GameRunner runner, JobFile job) throws InterruptedException {
        ByteArrayOutputStream outBytes = new ByteArrayOutputStream(), errBytes = new ByteArrayOutputStream();
        PrintStream out = new PrintStream(outBytes, true, StandardCharsets.UTF_8);
        PrintStream err = new PrintStream(errBytes, true, StandardCharsets.UTF_8);
        int code = mtgsim.Harness.run(job, runner, out, err);
        String text = outBytes.toString(StandardCharsets.UTF_8);
        List<String> lines = text.isEmpty() ? List.of() : List.of(text.split("\\R"));
        return new Object[] {code, lines, errBytes.toString(StandardCharsets.UTF_8)};
    }

    @Test
    public void usageIsExitTwo() {
        Assert.assertEquals(dispatch(""), 2);
        Assert.assertEquals(dispatch("", "dance"), 2);
        Assert.assertEquals(dispatch("", "run"), 2);
        Assert.assertEquals(dispatch("", "serve", "--slots", "0"), 2);
        Assert.assertEquals(dispatch("", "serve", "--slots", "many"), 2);
        Assert.assertTrue(lastErr.contains("usage"), lastErr);
    }

    @Test
    public void aMissingJobFileIsFatal() {
        Assert.assertEquals(dispatch("", "run", "C:/no/such/file.job"), 2);
        Assert.assertTrue(lastErr.contains("fatal"), lastErr);
    }

    @Test
    public void serveWithNoInputExitsZero() {
        Assert.assertEquals(dispatch("", "serve", "--slots", "1"), 0, "EOF is quit; nothing ran");
    }

    /** Each refusal is the usage line, not a fatal: GameRunner refuses a count below 1 with an IllegalArgumentException,
     *  which would exit 2 as well. */
    @Test
    public void serveTakesOnlyAPositiveSlotCount() {
        String[][] refused = {{"serve", "--slots"}, {"serve", "--slots", "0"}, {"serve", "--slots", "-1"},
                {"serve", "--threads", "2"}, {"serve", "--slots", "2", "extra"}};
        for (String[] args : refused) {
            String line = String.join(" ", args);
            Assert.assertEquals(dispatch("", args), 2, line);
            Assert.assertTrue(lastErr.startsWith("usage: mtgsim.Harness "), line + ": " + lastErr);
            Assert.assertEquals(lastOut, "", line + ": usage goes to err; nothing on the JSON stream");
        }
    }

    @Test
    public void aJobFileThatCannotBeParsedIsFatal() throws IOException {
        Path job = tempFile(List.of("seat.0=x"));                         // a seat key with no field: JobFile's BadJob
        Assert.assertEquals(dispatch("", "run", job.toString()), 2);
        Assert.assertTrue(lastErr.contains("fatal") && lastErr.contains("unknown job key seat.0"), lastErr);
        Assert.assertEquals(lastOut, "");
    }

    /** JobRunner's contract: a deck the runner cannot load is an IllegalArgumentException at the first game, before
     *  any record; the one-shot reads it as a job that cannot be built. */
    @Test
    public void aDeckTheRunnerCannotLoadIsFatal() throws IOException {
        Path job = tempFile(List.of("games=2", "seed=9", "timeout_s=300", "seat.0.deck_file=C:/no/such.dck",
                "seat.1.deck_file=" + Precons.commander().get(1)));
        Assert.assertEquals(dispatch("", "run", job.toString()), 2, "an IllegalArgumentException from the runner: exit 2");
        Assert.assertTrue(lastErr.contains("fatal") && lastErr.contains("could not load deck"), lastErr);
        Assert.assertEquals(lastOut, "", "no record and no summary");
    }

    @Test
    public void aJobThatEndsWithItsSummaryExitsZero() throws Exception {
        Object[] r = runOn(JobRunnerTest.neverObserving(0), JobRunnerTest.job(2, 9L, 300, 2));
        Assert.assertEquals(r[0], 0);
        @SuppressWarnings("unchecked") List<String> lines = (List<String>) r[1];
        Assert.assertEquals(lines.size(), 3, "two records, then the summary: " + lines);
        Assert.assertTrue(lines.get(0).startsWith("{\"game\":0,\"seed\":9,"), lines.get(0));
        Assert.assertTrue(lines.get(1).startsWith("{\"game\":1,\"seed\":10,"), lines.get(1));
        Assert.assertTrue(lines.get(2).startsWith("{\"summary\":{\"games\":2,"), lines.get(2));
        String err = (String) r[2];
        Assert.assertTrue(err.contains("game 1/2 Error 5ms") && err.contains("game 2/2 Error 5ms"), err);
        Assert.assertFalse(err.contains("poisoned"), err);
    }

    /** Spec 6: a poisoned one-shot exits 3. JobRunner writes the summary and returns normally when the job's own last
     *  game poisoned the runner, so only the runner can say so. */
    @Test
    public void aPoisonCausedByTheJobsLastGameExitsThreeAfterItsSummary() throws Exception {
        GameRunner runner = new GameRunner(1) {
            private final AtomicInteger plays = new AtomicInteger();

            @Override
            public GameResult play(GameSpec spec, Consumer<Game> observer) {
                if (plays.incrementAndGet() < 2) {
                    return JobRunnerTest.createFailed(spec, 0);
                }
                poisonForTest();                    // as play() does when this game's thread outlives its grace period
                return new GameResult(spec.seed(), null, "Timeout", 0, null, true, null, 5L, "TIMEOUT", List.of(), 0L);
            }
        };
        Object[] r = runOn(runner, JobRunnerTest.job(2, 9L, 300, 2));
        Assert.assertEquals(r[0], 3, "JobRunner returned normally, and the runner is poisoned");
        @SuppressWarnings("unchecked") List<String> lines = (List<String>) r[1];
        Assert.assertEquals(lines.size(), 3, "both records and the summary went out first: " + lines);
        Assert.assertTrue(lines.get(1).contains("\"end_reason\":\"Timeout\""), lines.get(1));
        Assert.assertTrue(lines.get(2).startsWith("{\"summary\":{"), lines.get(2));
        Assert.assertTrue(((String) r[2]).contains("poisoned"), (String) r[2]);
    }

    @Test
    public void aRunnerPoisonedBeforeTheJobExitsThreeWithNothingWritten() throws Exception {
        GameRunner runner = new GameRunner(1);
        runner.poisonForTest();
        Object[] r = runOn(runner, JobRunnerTest.job(2, 9L, 300, 2));
        Assert.assertEquals(r[0], 3, "the PoisonedException at the first game");
        Assert.assertEquals(r[1], List.of(), "nothing played, nothing written");
        Assert.assertTrue(((String) r[2]).contains("poisoned"), (String) r[2]);
    }

    @Test
    public void checkAnswersEveryNameWithForgesOwn() throws IOException {
        Path names = tempFile(List.of("Sol Ring", "", "  Treasure Map // Treasure Cove  ", "Not A Real Card Xyzzy"));
        Assert.assertEquals(dispatch("", "check", names.toString()), 0);
        Assert.assertEquals(List.of(lastOut.split("\\R")), List.of(
                "{\"name\":\"Sol Ring\",\"forge\":\"Sol Ring\"}",
                "{\"name\":\"Treasure Map // Treasure Cove\",\"forge\":\"Treasure Map\"}",
                "{\"name\":\"Not A Real Card Xyzzy\",\"forge\":null}"));
        Assert.assertEquals(dispatch("", "check"), 2, "check without its names file");
        Assert.assertTrue(lastErr.contains("usage"), lastErr);
    }

    /** main less its exit. By the time Serve reads its first command, GameRunner's halt handler is the default handler
     *  and the reading thread's own (an Error out of a job on a slot thread, or on the reading thread, exits 4), and
     *  System.out is the err stream (no Forge line reaches the JSON stream). Both are process-wide, so the test puts
     *  back what it found before it asserts anything. */
    @Test
    public void launchInstallsTheHaltHandlerAndMovesSystemOutBeforeServeReads() {
        PrintStream savedOut = System.out;
        Thread.UncaughtExceptionHandler savedDefault = Thread.getDefaultUncaughtExceptionHandler();
        Thread.UncaughtExceptionHandler savedThread = Thread.currentThread().getUncaughtExceptionHandler();
        boolean savedInstalled = GameRunner.haltInstalled;
        AtomicReference<Thread.UncaughtExceptionHandler> defaultWhileReading = new AtomicReference<>();
        AtomicReference<Thread.UncaughtExceptionHandler> readerWhileReading = new AtomicReference<>();
        AtomicReference<PrintStream> systemOutWhileReading = new AtomicReference<>();
        BufferedReader stdin = new BufferedReader(new StringReader("")) {
            @Override
            public String readLine() {
                defaultWhileReading.set(Thread.getDefaultUncaughtExceptionHandler());
                readerWhileReading.set(Thread.currentThread().getUncaughtExceptionHandler());
                systemOutWhileReading.set(System.out);
                return null;                                              // EOF: quit
            }
        };
        PrintStream out = new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8);
        PrintStream err = new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8);
        int code;
        try {
            code = mtgsim.Harness.launch(new String[] {"serve", "--slots", "1"}, stdin, out, err);
        } finally {
            System.setOut(savedOut);                                      // no other test runs under a halting handler
            Thread.setDefaultUncaughtExceptionHandler(savedDefault);
            Thread.currentThread().setUncaughtExceptionHandler(savedThread);
            GameRunner.haltInstalled = savedInstalled;
        }
        Assert.assertEquals(code, 0, "EOF is quit; nothing ran");
        Assert.assertSame(defaultWhileReading.get(), GameRunner.HALT_ON_UNCAUGHT,
                "the halt handler was every thread's default before Serve read a command");
        Assert.assertSame(readerWhileReading.get(), GameRunner.HALT_ON_UNCAUGHT, "and the reading thread's own");
        Assert.assertSame(systemOutWhileReading.get(), err, "System.out was the err stream before Serve read a command");
    }

    /** Spec 6: anything else out of a command is exit 2, as the frozen harness's catch (Exception) made it. A name of
     *  just "|" sends CardRequest.fromString past the end of an empty split. */
    @Test
    public void anyOtherExceptionOutOfACommandIsFatal() throws IOException {
        Path names = tempFile(List.of("Sol Ring", "|", "Sol Ring"));
        Assert.assertEquals(dispatch("", "check", names.toString()), 2);
        Assert.assertTrue(lastErr.startsWith("fatal: java.lang."), lastErr);
        Assert.assertTrue(lastErr.contains("\tat "), "and its stack trace: " + lastErr);
        Assert.assertEquals(List.of(lastOut.split("\\R")), List.of("{\"name\":\"Sol Ring\",\"forge\":\"Sol Ring\"}"),
                "the names before it were answered");
    }

    /** serve's exit code is Serve's: a runner already poisoned answers 5, though no job is read and no game runs. */
    @Test
    public void aPoisonedServerExitsFiveThroughTheFacade() throws Exception {
        GameRunner runner = new GameRunner(1);
        runner.poisonForTest();
        ByteArrayOutputStream outBytes = new ByteArrayOutputStream();
        PrintStream out = new PrintStream(outBytes, true, StandardCharsets.UTF_8);
        PrintStream err = new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8);
        int code = mtgsim.Harness.serve(runner, new BufferedReader(new StringReader("")), out, err);
        Assert.assertEquals(code, 5, "Serve.run's poisoned exit code, passed through");
        Assert.assertEquals(outBytes.toString(StandardCharsets.UTF_8), "", "no job, no game, no line");
    }

    /** A job of no games is refused like any other when a deck cannot load (JobRunner validates the job's first spec
     *  before its loop), as the frozen harness built its players before it played anything. */
    @Test
    public void aJobOfNoGamesWhoseDeckCannotLoadIsFatal() throws IOException {
        Path job = tempFile(List.of("games=0", "seed=9", "timeout_s=300", "seat.0.deck_file=C:/no/such.dck",
                "seat.1.deck_file=" + Precons.commander().get(1)));
        Assert.assertEquals(dispatch("", "run", job.toString()), 2, "refused before its summary");
        Assert.assertTrue(lastErr.contains("fatal") && lastErr.contains("could not load deck"), lastErr);
        Assert.assertEquals(lastOut, "", "no summary");
    }

    /** No arguments: the usage line and exit 2 before anything else, as the frozen harness printed it before boot.
     *  launch returns before it moves System.out or installs the halt handler, so before it boots. */
    @Test
    public void noArgumentsIsUsageBeforeAnythingElse() {
        PrintStream savedOut = System.out;
        Thread.UncaughtExceptionHandler savedDefault = Thread.getDefaultUncaughtExceptionHandler();
        Thread.UncaughtExceptionHandler savedThread = Thread.currentThread().getUncaughtExceptionHandler();
        boolean savedInstalled = GameRunner.haltInstalled;
        ByteArrayOutputStream errBytes = new ByteArrayOutputStream();
        PrintStream out = new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8);
        PrintStream err = new PrintStream(errBytes, true, StandardCharsets.UTF_8);
        int code;
        PrintStream systemOutAfter;
        Thread.UncaughtExceptionHandler defaultAfter;
        try {
            code = mtgsim.Harness.launch(new String[0], new BufferedReader(new StringReader("")), out, err);
            systemOutAfter = System.out;
            defaultAfter = Thread.getDefaultUncaughtExceptionHandler();
        } finally {
            System.setOut(savedOut);                                      // put back whatever launch did, before asserting
            Thread.setDefaultUncaughtExceptionHandler(savedDefault);
            Thread.currentThread().setUncaughtExceptionHandler(savedThread);
            GameRunner.haltInstalled = savedInstalled;
        }
        Assert.assertEquals(code, 2);
        String text = errBytes.toString(StandardCharsets.UTF_8);
        Assert.assertTrue(text.startsWith("usage: mtgsim.Harness "), text);
        Assert.assertSame(systemOutAfter, savedOut, "System.out was never moved");
        Assert.assertSame(defaultAfter, savedDefault, "the halt handler was never installed");
    }
}
