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
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.testng.Assert;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import forge.util.SimScope;

public class ServeTest {
    /** Every temp file and directory the tests made, in the order made: deleted after the class, newest first. */
    static final List<Path> TEMP = Collections.synchronizedList(new ArrayList<>());

    @BeforeClass
    public void boot() { GameRunner.boot(); }

    @AfterClass
    public void relaxStrict() { SimScope.setStrict(false); }

    @AfterClass
    public void deleteTempFiles() throws IOException {
        for (int i = TEMP.size() - 1; i >= 0; i--) {
            Files.deleteIfExists(TEMP.get(i));
        }
        TEMP.clear();
    }

    /** {@code p}, to be deleted after the class. */
    static Path temp(Path p) {
        TEMP.add(p);
        return p;
    }

    static Path jobFile(int games, long seed, int timeoutS, int seats) throws Exception {
        List<String> lines = new ArrayList<>(List.of("games=" + games, "seed=" + seed, "timeout_s=" + timeoutS));
        List<Path> precons = Precons.commander();
        for (int i = 0; i < seats; i++) {
            lines.add("seat." + i + ".deck_file=" + precons.get(i));
            lines.add("seat." + i + ".deck_hash=h" + i);
        }
        return jobFile(lines);
    }

    /** A job file of exactly these lines. */
    static Path jobFile(List<String> lines) throws IOException {
        Path f = temp(Files.createTempFile("serve", ".job"));
        Files.write(f, lines);
        return f;
    }

    /** Runs a Serve over the given stdin text; returns {exit code, stdout lines, stderr text}. */
    static Object[] serve(GameRunner runner, String stdin) throws Exception {
        return serve(runner, new BufferedReader(new StringReader(stdin)));
    }

    /** Runs a Serve over the given stdin; returns {exit code, stdout lines, stderr text}. */
    static Object[] serve(GameRunner runner, BufferedReader stdin) throws Exception {
        ByteArrayOutputStream outBytes = new ByteArrayOutputStream(), errBytes = new ByteArrayOutputStream();
        PrintStream out = new PrintStream(outBytes, true, StandardCharsets.UTF_8);
        PrintStream err = new PrintStream(errBytes, true, StandardCharsets.UTF_8);
        int code = new Serve(runner, stdin, out, err).run();
        List<String> lines = List.of(outBytes.toString(StandardCharsets.UTF_8).split("\\R"));
        return new Object[] {code, lines, errBytes.toString(StandardCharsets.UTF_8)};
    }

    static List<String> linesOf(List<String> all, String id) {
        List<String> mine = new ArrayList<>();
        for (String l : all) if (l.startsWith("{\"job\":\"" + id + "\",")) mine.add(l);
        return mine;
    }

    /** The job's only line is its error, which starts with {@code error}: it was refused, never accepted. */
    static void assertRefused(List<String> lines, String id, String error) {
        List<String> mine = linesOf(lines, id);
        Assert.assertEquals(mine.size(), 1, "one error line and no accepted line: " + mine);
        Assert.assertTrue(mine.get(0).startsWith("{\"job\":\"" + id + "\",\"error\":\"" + error), mine.get(0));
    }

    /** A one-slot runner poisoned after its first game, as a game thread that outlived its grace would leave it: every
     *  later play throws PoisonedException. */
    static GameRunner poisonedAfterItsFirstGame() {
        return poisonedAfterItsFirstGame(new CountDownLatch(0));
    }

    /** The same runner, each play held until {@code gate} opens. */
    static GameRunner poisonedAfterItsFirstGame(CountDownLatch gate) {
        return new GameRunner(1) {
            @Override
            public GameResult play(GameSpec spec, java.util.function.Consumer<forge.game.Game> observer) throws InterruptedException {
                gate.await();
                GameResult r = super.play(spec, observer);
                poisonForTest();
                return r;
            }
        };
    }

    @Test(timeOut = 600_000)
    public void twoJobsInterleaveOnTwoSlotsAndEveryLineIsAttributed() throws Exception {
        AtomicInteger inFlight = new AtomicInteger();
        AtomicInteger mostInFlight = new AtomicInteger();
        Set<String> slotThreads = ConcurrentHashMap.newKeySet();
        GameRunner runner = new GameRunner(2) {
            @Override
            public GameResult play(GameSpec spec, java.util.function.Consumer<forge.game.Game> observer) throws InterruptedException {
                slotThreads.add(Thread.currentThread().getName());
                mostInFlight.accumulateAndGet(inFlight.incrementAndGet(), Math::max);
                try {
                    return super.play(spec, observer);
                } finally {
                    inFlight.decrementAndGet();
                }
            }
        };
        Path a = jobFile(2, 7_000_000L, 300, 2), b = jobFile(1, 7_000_010L, 300, 2);
        Object[] r = serve(runner, "run a " + a + "\nrun b " + b + "\nquit\n");
        Assert.assertEquals(r[0], 0);
        Assert.assertEquals(mostInFlight.get(), 2, "the two jobs played at once, a game on each slot");
        Assert.assertEquals(slotThreads, Set.of("serve-slot-0", "serve-slot-1"), "each job on a slot thread of its own, numbered");
        @SuppressWarnings("unchecked") List<String> lines = (List<String>) r[1];
        for (String l : lines) Assert.assertTrue(l.startsWith("{\"job\":"), "every line is attributed: " + l);
        List<String> la = linesOf(lines, "a"), lb = linesOf(lines, "b");
        Assert.assertEquals(la.size(), 4, "accepted, two records, summary: " + la);
        Assert.assertEquals(lb.size(), 3, "accepted, one record, summary: " + lb);
        Assert.assertTrue(la.get(0).contains("\"accepted\":true,\"games\":2"));
        Assert.assertTrue(la.get(3).contains("\"summary\":{"), "the summary ends the job");
        Assert.assertTrue(lb.get(2).contains("\"summary\":{"));
    }

    @Test(timeOut = 300_000)
    public void badLinesAnswerAnErrorAndTheServerLivesOn() throws Exception {
        Path a = jobFile(1, 7_000_000L, 300, 2);
        Path badJob = jobFile(List.of("seat.0=x"));                    // a seat key with no field: JobFile's BadJob
        Path noGames = jobFile(0, 7_000_000L, 300, 2);                 // games=0: accepted, then its summary
        Object[] r = serve(new GameRunner(1), "hello\nrun onlytwo\nrun a " + a + "\nrun a " + a + "\nrun c C:/no/such.job\n"
                + "run bj " + badJob + "\nafter-the-bad-job\nrun z " + noGames + "\nafter-no-games\nquit\nrun q " + a + "\n");
        Assert.assertEquals(r[0], 0);
        @SuppressWarnings("unchecked") List<String> lines = (List<String>) r[1];
        Assert.assertTrue(lines.get(0).startsWith("{\"job\":null,\"error\":\"unknown command"), lines.get(0));
        Assert.assertTrue(lines.get(1).startsWith("{\"job\":null,\"error\":\"usage: run"), lines.get(1));
        Assert.assertTrue(lines.stream().anyMatch(l -> l.startsWith("{\"job\":null,\"error\":\"job id already used: a")));
        List<String> lc = linesOf(lines, "c");
        Assert.assertEquals(lc.size(), 1, lc.toString());
        Assert.assertTrue(lc.get(0).contains("\"error\":"), "a job that cannot start answers one error line");
        Assert.assertEquals(linesOf(lines, "a").size(), 3, "a ran: accepted, record, summary");
        assertRefused(lines, "bj", "BadJob: unknown job key seat.0");
        Assert.assertTrue(lines.contains("{\"job\":null,\"error\":\"unknown command: after-the-bad-job\"}"),
                "the line after a refused job file is read: " + lines);
        Assert.assertEquals(linesOf(lines, "z").size(), 2, "a job of no games: accepted, summary: " + lines);
        Assert.assertTrue(lines.contains("{\"job\":null,\"error\":\"unknown command: after-no-games\"}"),
                "the line after a job of no games is read: " + lines);
        Assert.assertEquals(linesOf(lines, "q"), List.of(), "quit accepts nothing more: the run after it is never read");
    }

    /** A job's accepted line goes out before the job can write one. This out lets go of the lock Serve holds around a
     *  write while it writes an accepted line, for up to two seconds or until another line is written: were the job
     *  started first, its summary (a job of no games writes nothing else) would get in ahead. */
    @Test(timeOut = 120_000)
    public void theAcceptedLineIsWrittenBeforeTheJobCanWriteOne() throws Exception {
        ByteArrayOutputStream outBytes = new ByteArrayOutputStream();
        PrintStream out = new PrintStream(outBytes, true, StandardCharsets.UTF_8) {
            private int written;

            @Override
            public void println(String line) {
                synchronized (this) {
                    if (line.contains("\"accepted\":true")) {
                        int before = written;
                        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
                        try {
                            while (written == before && System.nanoTime() < deadline) {
                                wait(Math.max(1, TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime())));
                            }
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    }
                    super.println(line);
                    written++;
                    notifyAll();
                }
            }
        };
        PrintStream err = new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8);
        String stdin = "run z " + jobFile(0, 7_000_000L, 300, 2) + "\nquit\n";
        int code = new Serve(new GameRunner(1), new BufferedReader(new StringReader(stdin)), out, err).run();
        Assert.assertEquals(code, 0);
        List<String> lines = List.of(outBytes.toString(StandardCharsets.UTF_8).split("\\R"));
        Assert.assertEquals(lines.size(), 2, lines.toString());
        Assert.assertTrue(lines.get(0).startsWith("{\"job\":\"z\",\"accepted\":true"), "accepted, then the summary: " + lines);
        Assert.assertTrue(lines.get(1).startsWith("{\"job\":\"z\",\"summary\":{"), "accepted, then the summary: " + lines);
    }

    @Test(timeOut = 300_000)
    public void eofIsQuit() throws Exception {
        Path a = jobFile(1, 7_000_000L, 300, 2);
        Object[] r = serve(new GameRunner(1), "run a " + a + "\n");
        Assert.assertEquals(r[0], 0);
        @SuppressWarnings("unchecked") List<String> lines = (List<String>) r[1];
        Assert.assertEquals(linesOf(lines, "a").size(), 3);
    }

    /** b is accepted before the poison and refused when it reaches its slot. a's game waits until the reading thread
     *  asks for the line after b's, so b has had its answer by then: a run read after the poison is refused where it
     *  is read (aRunReadAfterThePoisonIsRefusedWithOneErrorLineAndNoAcceptedLine). */
    @Test(timeOut = 600_000)
    public void aPoisonedRunnerRefusesTheRestAndExitsFive() throws Exception {
        Path a = jobFile(1, 7_000_000L, 300, 2), b = jobFile(1, 7_000_010L, 300, 2);
        CountDownLatch bRead = new CountDownLatch(1);
        BufferedReader stdin = new BufferedReader(new StringReader("run a " + a + "\nrun b " + b + "\nquit\n")) {
            private int read;

            @Override
            public String readLine() throws IOException {
                if (read++ == 2) {
                    bRead.countDown();                                // the line after b's: b has been answered
                }
                return super.readLine();
            }
        };
        Object[] r = serve(poisonedAfterItsFirstGame(bRead), stdin);
        Assert.assertEquals(r[0], 5, "poisoned: exit 5");
        @SuppressWarnings("unchecked") List<String> lines = (List<String>) r[1];
        List<String> la = linesOf(lines, "a"), lb = linesOf(lines, "b");
        Assert.assertEquals(la.size(), 3, "a: accepted, its record, its summary (the poison came after its game)");
        Assert.assertTrue(la.get(2).contains("\"summary\":{"),
                "a had no game left when its own last game poisoned the runner, so its summary still ends it: " + la.get(2));
        Assert.assertEquals(lb.size(), 2, "b: accepted, then error poisoned: " + lb);
        Assert.assertTrue(lb.get(1).contains("\"error\":\"poisoned\""), lb.get(1));
        String err = (String) r[2];
        Assert.assertTrue(err.contains("job b: poisoned"), "a job refused at its start ends on err too: " + err);
        Assert.assertFalse(err.contains("job b: 1 games"), "it never started: " + err);
    }

    @Test(timeOut = 600_000)
    public void aJobWithGamesLeftOnAPoisonedRunnerEndsPoisonedAfterTheRecordsItPlayed() throws Exception {
        Object[] r = serve(poisonedAfterItsFirstGame(), "run a " + jobFile(2, 7_000_000L, 300, 2) + "\nquit\n");
        Assert.assertEquals(r[0], 5, "poisoned: exit 5");
        @SuppressWarnings("unchecked") List<String> lines = (List<String>) r[1];
        List<String> la = linesOf(lines, "a");
        Assert.assertEquals(la.size(), 3, "accepted, the first game's record, then poisoned instead of a summary: " + la);
        Assert.assertTrue(la.get(1).contains("\"game\":0,"), la.get(1));
        Assert.assertEquals(la.get(2), "{\"job\":\"a\",\"error\":\"poisoned\"}");
        String err = (String) r[2];
        Assert.assertTrue(err.contains("job a: 2 games") && err.contains("job a: poisoned"),
                "the job's start line, then its error as its end line: " + err);
    }

    /** A run read once the runner is poisoned is refused where it is read: one poisoned error line, no accepted line.
     *  The input is a client's that sends run b only after it has read job a's end, the poisoned error a's second game
     *  got. b's id is spent as any other's (its repeat is answered as a repeat, so b's error stays its last line), and c
     *  is refused before its job file, which does not exist, is read. Then the input ends, with no quit, and the
     *  process exits 5. */
    @Test(timeOut = 600_000)
    public void aRunReadAfterThePoisonIsRefusedWithOneErrorLineAndNoAcceptedLine() throws Exception {
        String aPoisoned = "{\"job\":\"a\",\"error\":\"poisoned\"}", bPoisoned = "{\"job\":\"b\",\"error\":\"poisoned\"}";
        CountDownLatch aEnded = new CountDownLatch(1);
        ByteArrayOutputStream outBytes = new ByteArrayOutputStream(), errBytes = new ByteArrayOutputStream();
        PrintStream out = new PrintStream(outBytes, true, StandardCharsets.UTF_8) {
            @Override
            public void println(String line) {
                super.println(line);
                if (line.equals(aPoisoned)) {
                    aEnded.countDown();
                }
            }
        };
        PrintStream err = new PrintStream(errBytes, true, StandardCharsets.UTF_8);
        String runB = "run b " + jobFile(1, 7_000_010L, 300, 2);
        List<String> sent = List.of("run a " + jobFile(2, 7_000_000L, 300, 2), runB, runB, "run c C:/no/such.job");
        BufferedReader in = new BufferedReader(new StringReader("")) {
            private int read;

            @Override
            public String readLine() {
                if (read == 1) {
                    try {
                        Assert.assertTrue(aEnded.await(300, TimeUnit.SECONDS), "job a ended, poisoned, before run b was sent");
                    } catch (InterruptedException e) {
                        throw new AssertionError("interrupted while waiting for job a's end", e);
                    }
                }
                return read < sent.size() ? sent.get(read++) : null;      // then the end of input
            }
        };
        int code = new Serve(poisonedAfterItsFirstGame(), in, out, err).run();
        Assert.assertEquals(code, 5, "poisoned: exit 5 once the input has ended");
        List<String> lines = List.of(outBytes.toString(StandardCharsets.UTF_8).split("\\R"));
        List<String> la = linesOf(lines, "a");
        Assert.assertEquals(la.size(), 3, "a: accepted, game 0's record, then poisoned instead of a summary: " + la);
        Assert.assertEquals(la.get(2), aPoisoned);
        Assert.assertEquals(linesOf(lines, "b"), List.of(bPoisoned), "b: one error line and no accepted line: " + lines);
        Assert.assertTrue(lines.contains("{\"job\":null,\"error\":\"job id already used: b\"}"), "b's repeat: " + lines);
        Assert.assertEquals(linesOf(lines, "c"), List.of("{\"job\":\"c\",\"error\":\"poisoned\"}"),
                "c: poisoned, not its missing file: " + lines);
        Assert.assertEquals(lines.size(), 6, "nothing else was written: " + lines);
        String errText = errBytes.toString(StandardCharsets.UTF_8);
        Assert.assertTrue(errText.contains("job b: poisoned"), "the refusal ends b on err too: " + errText);
        Assert.assertFalse(errText.contains("job b: 1 games"), "b never started: " + errText);
    }

    /** Spec 8.5: a 1 s pod through serve is recorded as a Timeout, gives its slot back, and its job goes on: the job's
     *  second game needs the runner's one slot, and the summary still ends the job. */
    @Test(timeOut = 600_000)
    public void aOneSecondPodTimesOutAndItsJobGoesOnToItsSummary() throws Exception {
        Object[] r = serve(new GameRunner(1), "run p " + jobFile(2, 7_000_000L, 1, 4) + "\nquit\n");
        Assert.assertEquals(r[0], 0, "a timed-out game that unwinds within its grace leaves the runner unpoisoned");
        @SuppressWarnings("unchecked") List<String> lines = (List<String>) r[1];
        List<String> lp = linesOf(lines, "p");
        Assert.assertEquals(lp.size(), 4, "accepted, two records, summary: " + lp);
        Assert.assertEquals(lp.get(0), "{\"job\":\"p\",\"accepted\":true,\"games\":2}");
        for (int g = 0; g < 2; g++) {
            String rec = lp.get(1 + g);
            Assert.assertTrue(rec.startsWith("{\"job\":\"p\",\"game\":" + g + ",") && rec.contains("\"end_reason\":\"Timeout\""), rec);
        }
        Assert.assertTrue(lp.get(3).startsWith("{\"job\":\"p\",\"summary\":{\"games\":2,")
                && lp.get(3).contains("\"Timeout\":2,\"Error\":0,"), "the summary ends the job: " + lp.get(3));
    }

    @Test(timeOut = 300_000)
    public void aJobWhoseDeckProfileOrAiCannotBeLoadedIsRefusedBeforeItIsAccepted() throws Exception {
        String seat0 = "seat.0.deck_file=" + Precons.commander().get(0), seat1 = "seat.1.deck_file=" + Precons.commander().get(1);
        Path deck = jobFile(List.of("games=2", seat0, "seat.1.deck_file=C:/no/such.dck"));
        Path deckNoGames = jobFile(List.of("games=0", seat0, "seat.1.deck_file=C:/no/such.dck"));
        Path profile = jobFile(List.of("games=2", seat0, seat1, "seat.1.profile=NoSuchProfile"));
        Path ai = jobFile(List.of("games=2", seat0, seat1, "seat.1.ai=no_such_ai"));
        Path next = jobFile(0, 7_000_000L, 300, 2);                    // games=0: accepted, then its summary; nothing is played
        Object[] r = serve(new GameRunner(1), "run deck " + deck + "\nrun nogames " + deckNoGames + "\nrun profile " + profile
                + "\nrun ai " + ai + "\nrun next " + next + "\nquit\n");
        Assert.assertEquals(r[0], 0);
        @SuppressWarnings("unchecked") List<String> lines = (List<String>) r[1];
        assertRefused(lines, "deck", "IllegalArgumentException: could not load deck");
        assertRefused(lines, "nogames", "IllegalArgumentException: could not load deck");
        assertRefused(lines, "profile", "IllegalArgumentException: unknown AI profile NoSuchProfile");
        assertRefused(lines, "ai", "IllegalArgumentException: unknown ai mode no_such_ai");
        Assert.assertEquals(linesOf(lines, "next").size(), 2, "the server plays on: accepted, summary");
    }

    @Test(timeOut = 120_000)
    public void aBareRunIsMalformedAndTheJobFileIsTheRestOfTheLine() throws Exception {
        Path dir = temp(Files.createTempDirectory("serve dir"));
        Path spaced = temp(Files.copy(jobFile(0, 7_000_000L, 300, 2), dir.resolve("a job.job")));
        Path tabbed = jobFile(0, 7_000_000L, 300, 2);                  // games=0 (both): accepted, then the summary
        Object[] r = serve(new GameRunner(1), "run\nrun spaced " + spaced + "\nrun\ttabbed\t" + tabbed + "\nquit\n");
        Assert.assertEquals(r[0], 0);
        @SuppressWarnings("unchecked") List<String> lines = (List<String>) r[1];
        Assert.assertTrue(lines.get(0).startsWith("{\"job\":null,\"error\":\"usage: run"), "a run with no id and no file: " + lines.get(0));
        Assert.assertEquals(linesOf(lines, "spaced").size(), 2, "a job file whose path holds spaces: " + lines);
        Assert.assertEquals(linesOf(lines, "tabbed").size(), 2, "tabs separate the tokens as spaces do: " + lines);
    }

    /** The facade makes GameRunner's halt handler the default handler (exit 4, spec section 6), so an Error out of a
     *  job has to reach that handler; a Future nobody reads (pool.submit) would keep it. run answers the handler's exit
     *  code itself, so that the process exits 4 whichever of the halt and the end of the drain comes first. */
    @Test(timeOut = 120_000)
    public void anErrorOutOfAJobReachesTheUncaughtHandler() throws Exception {
        Error boom = new Error("boom");
        GameRunner runner = new GameRunner(1) {
            @Override
            public GameResult play(GameSpec spec, java.util.function.Consumer<forge.game.Game> observer) {
                throw boom;
            }
        };
        CountDownLatch reached = new CountDownLatch(1);
        Thread.UncaughtExceptionHandler saved = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            if (e == boom) {
                reached.countDown();
            } else if (saved != null) {
                saved.uncaughtException(t, e);
            }
        });
        try {
            Object[] r = serve(runner, "run a " + jobFile(1, 7_000_000L, 300, 2) + "\nquit\n");
            Assert.assertTrue(reached.await(30, TimeUnit.SECONDS), "the Error reached the default uncaught handler");
            Assert.assertEquals(r[0], GameRunner.EXIT_UNCAUGHT, "run answers the halt handler's exit code");
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(saved);
        }
    }

    /** An Error on the reading thread (an OutOfMemoryError in JobFile.load, say) goes on to that thread's uncaught
     *  handler at once: run does not first wait for the jobs it accepted. */
    @Test(timeOut = 120_000)
    public void anErrorOnTheReadingThreadLeavesWithoutWaitingForTheJobs() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        GameRunner runner = new GameRunner(1) {
            @Override
            public GameResult play(GameSpec spec, java.util.function.Consumer<forge.game.Game> observer) throws InterruptedException {
                release.await();                                      // job a holds its slot until the test lets it go
                throw new IllegalStateException("let go");
            }
        };
        Error boom = new Error("read failed");
        String first = "run a " + jobFile(1, 7_000_000L, 300, 2);
        BufferedReader in = new BufferedReader(new StringReader("")) {
            private boolean sent;

            @Override
            public String readLine() {
                if (sent) {
                    throw boom;
                }
                sent = true;
                return first;
            }
        };
        PrintStream out = new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8);
        PrintStream err = new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8);
        AtomicReference<Throwable> thrown = new AtomicReference<>();
        Thread reading = new Thread(() -> {
            try {
                new Serve(runner, in, out, err).run();
            } catch (Throwable t) {
                thrown.set(t);
            }
        }, "serve-reading");
        reading.start();
        reading.join(10_000);
        boolean left = !reading.isAlive();
        release.countDown();
        reading.join(60_000);
        Assert.assertTrue(left, "run left on the reading thread's Error while job a still held its slot");
        Assert.assertSame(thrown.get(), boom);
    }
}
