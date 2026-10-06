package forge.sim;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.testng.Assert;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import forge.util.SimScope;

public class ServeTest {
    @BeforeClass
    public static void boot() { GameRunner.boot(); }

    @AfterClass
    public static void relaxStrict() { SimScope.setStrict(false); }

    static Path jobFile(int games, long seed, int timeoutS, int seats) throws Exception {
        List<String> lines = new ArrayList<>(List.of("games=" + games, "seed=" + seed, "timeout_s=" + timeoutS));
        List<Path> precons = Precons.commander();
        for (int i = 0; i < seats; i++) {
            lines.add("seat." + i + ".deck_file=" + precons.get(i));
            lines.add("seat." + i + ".deck_hash=h" + i);
        }
        Path f = Files.createTempFile("serve", ".job");
        Files.write(f, lines);
        return f;
    }

    /** Runs a Serve over the given stdin text; returns {exit code, stdout lines}. */
    static Object[] serve(GameRunner runner, String stdin) throws Exception {
        ByteArrayOutputStream outBytes = new ByteArrayOutputStream(), errBytes = new ByteArrayOutputStream();
        PrintStream out = new PrintStream(outBytes, true, StandardCharsets.UTF_8);
        PrintStream err = new PrintStream(errBytes, true, StandardCharsets.UTF_8);
        int code = new Serve(runner, new BufferedReader(new StringReader(stdin)), out, err).run();
        List<String> lines = List.of(outBytes.toString(StandardCharsets.UTF_8).split("\\R"));
        return new Object[] {code, lines};
    }

    static List<String> linesOf(List<String> all, String id) {
        List<String> mine = new ArrayList<>();
        for (String l : all) if (l.startsWith("{\"job\":\"" + id + "\",")) mine.add(l);
        return mine;
    }

    @Test(timeOut = 600_000)
    public void twoJobsInterleaveOnTwoSlotsAndEveryLineIsAttributed() throws Exception {
        Path a = jobFile(2, 7_000_000L, 300, 2), b = jobFile(1, 7_000_010L, 300, 2);
        Object[] r = serve(new GameRunner(2), "run a " + a + "\nrun b " + b + "\nquit\n");
        Assert.assertEquals(r[0], 0);
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
        Object[] r = serve(new GameRunner(1), "hello\nrun onlytwo\nrun a " + a + "\nrun a " + a + "\nrun c C:/no/such.job\nquit\n");
        Assert.assertEquals(r[0], 0);
        @SuppressWarnings("unchecked") List<String> lines = (List<String>) r[1];
        Assert.assertTrue(lines.get(0).startsWith("{\"job\":null,\"error\":\"unknown command"), lines.get(0));
        Assert.assertTrue(lines.get(1).startsWith("{\"job\":null,\"error\":\"usage: run"), lines.get(1));
        Assert.assertTrue(lines.stream().anyMatch(l -> l.startsWith("{\"job\":null,\"error\":\"job id already used: a")));
        List<String> lc = linesOf(lines, "c");
        Assert.assertEquals(lc.size(), 1, lc.toString());
        Assert.assertTrue(lc.get(0).contains("\"error\":"), "a job that cannot start answers one error line");
        Assert.assertEquals(linesOf(lines, "a").size(), 3, "a ran: accepted, record, summary");
    }

    @Test(timeOut = 300_000)
    public void eofIsQuit() throws Exception {
        Path a = jobFile(1, 7_000_000L, 300, 2);
        Object[] r = serve(new GameRunner(1), "run a " + a + "\n");
        Assert.assertEquals(r[0], 0);
        @SuppressWarnings("unchecked") List<String> lines = (List<String>) r[1];
        Assert.assertEquals(linesOf(lines, "a").size(), 3);
    }

    @Test(timeOut = 600_000)
    public void aPoisonedRunnerRefusesTheRestAndExitsFive() throws Exception {
        GameRunner runner = new GameRunner(1) {
            @Override
            public GameResult play(GameSpec spec, java.util.function.Consumer<forge.game.Game> observer) throws InterruptedException {
                GameResult r = super.play(spec, observer);
                poisonForTest();                                     // the first game played; the runner is poisoned after it
                return r;
            }
        };
        Path a = jobFile(1, 7_000_000L, 300, 2), b = jobFile(1, 7_000_010L, 300, 2);
        Object[] r = serve(runner, "run a " + a + "\nrun b " + b + "\nquit\n");
        Assert.assertEquals(r[0], 5, "poisoned: exit 5");
        @SuppressWarnings("unchecked") List<String> lines = (List<String>) r[1];
        List<String> la = linesOf(lines, "a"), lb = linesOf(lines, "b");
        Assert.assertEquals(la.size(), 3, "a: accepted, its record, its summary (the poison came after its game)");
        Assert.assertEquals(lb.size(), 2, "b: accepted, then error poisoned: " + lb);
        Assert.assertTrue(lb.get(1).contains("\"error\":\"poisoned\""), lb.get(1));
    }

    @Test(timeOut = 600_000)
    public void aJobWithGamesLeftOnAPoisonedRunnerEndsPoisonedAfterTheRecordsItPlayed() throws Exception {
        GameRunner runner = new GameRunner(1) {
            @Override
            public GameResult play(GameSpec spec, java.util.function.Consumer<forge.game.Game> observer) throws InterruptedException {
                GameResult r = super.play(spec, observer);
                poisonForTest();                                     // after the first game: the second finds the runner poisoned
                return r;
            }
        };
        Object[] r = serve(runner, "run a " + jobFile(2, 7_000_000L, 300, 2) + "\nquit\n");
        Assert.assertEquals(r[0], 5, "poisoned: exit 5");
        @SuppressWarnings("unchecked") List<String> lines = (List<String>) r[1];
        List<String> la = linesOf(lines, "a");
        Assert.assertEquals(la.size(), 3, "accepted, the first game's record, then poisoned instead of a summary: " + la);
        Assert.assertTrue(la.get(1).contains("\"game\":0,"), la.get(1));
        Assert.assertEquals(la.get(2), "{\"job\":\"a\",\"error\":\"poisoned\"}");
    }

    @Test(timeOut = 300_000)
    public void aDeckTheRunnerCannotLoadEndsAnAcceptedJobWithOneErrorLine() throws Exception {
        Path bad = Files.createTempFile("serve", ".job");
        Files.write(bad, List.of("games=2", "seat.0.deck_file=" + Precons.commander().get(0), "seat.1.deck_file=C:/no/such.dck"));
        Path next = jobFile(0, 7_000_000L, 300, 2);                    // games=0: accepted, then its summary; nothing is played
        Object[] r = serve(new GameRunner(1), "run bad " + bad + "\nrun next " + next + "\nquit\n");
        Assert.assertEquals(r[0], 0);
        @SuppressWarnings("unchecked") List<String> lines = (List<String>) r[1];
        List<String> lb = linesOf(lines, "bad");
        Assert.assertEquals(lb.size(), 2, "the file loads, so accepted; the first game refuses the deck: one error, no summary: " + lb);
        Assert.assertTrue(lb.get(1).startsWith("{\"job\":\"bad\",\"error\":\"IllegalArgumentException: could not load deck"), lb.get(1));
        Assert.assertEquals(linesOf(lines, "next").size(), 2, "the slot plays on: accepted, summary");
    }

    @Test(timeOut = 120_000)
    public void aBareRunIsMalformedAndTheJobFileIsTheRestOfTheLine() throws Exception {
        Path spaced = Files.copy(jobFile(0, 7_000_000L, 300, 2), Files.createTempDirectory("serve dir").resolve("a job.job"));
        Path tabbed = jobFile(0, 7_000_000L, 300, 2);                  // games=0 (both): accepted, then the summary
        Object[] r = serve(new GameRunner(1), "run\nrun spaced " + spaced + "\nrun\ttabbed\t" + tabbed + "\nquit\n");
        Assert.assertEquals(r[0], 0);
        @SuppressWarnings("unchecked") List<String> lines = (List<String>) r[1];
        Assert.assertTrue(lines.get(0).startsWith("{\"job\":null,\"error\":\"usage: run"), "a run with no id and no file: " + lines.get(0));
        Assert.assertEquals(linesOf(lines, "spaced").size(), 2, "a job file whose path holds spaces: " + lines);
        Assert.assertEquals(linesOf(lines, "tabbed").size(), 2, "tabs separate the tokens as spaces do: " + lines);
    }

    /** The facade makes GameRunner's halt handler the default handler (exit 4, spec section 6), so an Error out of a
     *  job has to reach that handler; a Future nobody reads (pool.submit) would keep it. */
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
            serve(runner, "run a " + jobFile(1, 7_000_000L, 300, 2) + "\nquit\n");
            Assert.assertTrue(reached.await(30, TimeUnit.SECONDS), "the Error reached the default uncaught handler");
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(saved);
        }
    }
}
