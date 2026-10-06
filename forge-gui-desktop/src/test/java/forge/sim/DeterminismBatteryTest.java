package forge.sim;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.testng.Assert;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import forge.sim.DeterminismBattery.Entry;
import forge.sim.DeterminismBattery.Played;
import forge.sim.DeterminismBattery.Report;
import forge.sim.GameRunner.GameResult;
import forge.sim.GameRunner.GameSpec;
import forge.sim.GameRunner.SeatSpec;
import forge.util.SimScope;

/** The battery tool's solo reference (spec 7.1 and 7.4, Task 7d): a game whose solo play ends in Timeout is
 *  untestable and is not played in the later arms, a game the solo arm decided gets a budget scaled from its solo
 *  wall, and with no leading arm A nothing changes. The game tests need the display and play a few seconds
 *  of Forge each. */
public class DeterminismBatteryTest {
    static final long SEED = 7_000_000L;

    @BeforeClass
    public void boot() {
        GameRunner.boot();                    // Precons.commander() reads ForgeConstants, which needs Forge booted
    }

    @AfterClass
    public void relaxStrict() {
        SimScope.setStrict(false);            // run() boots the runner, which arms strict mode
    }

    /** One game the way DeterminismBattery.fullList() builds it (label slice/names/seed, profile Default, default AI),
     *  with the timeout the test wants. */
    static Entry entry(String slice, List<Path> decks, int timeoutSeconds) {
        return entry(slice, decks, SEED, timeoutSeconds);
    }

    static Entry entry(String slice, List<Path> decks, long seed, int timeoutSeconds) {
        String names = decks.stream().map(d -> d.getFileName().toString().replace(".dck", "")).collect(Collectors.joining("+"));
        List<SeatSpec> seats = new ArrayList<>();
        for (Path deck : decks) {
            seats.add(new SeatSpec(deck, "Default", "default"));
        }
        return new Entry(slice + "/" + names + "/" + seed, new GameSpec(seed, timeoutSeconds, seats));
    }

    /** A pod that cannot finish inside its 1 s timeout (GameRunnerTest's pod timeout test relies on the same fact),
     *  then a 1v1 that finishes in seconds. */
    static List<Entry> podThenOneVsOne() {
        List<Path> precons = Precons.commander();
        return List.of(entry("pod-default", precons.subList(0, 4), 1), entry("1v1-default", precons.subList(0, 2), 300));
    }

    // Fabricated solo results, built with the record's constructor: the fields GameRunner.Body.result fills for a Timeout,
    // a decided game and an Error. Digests and lines are placeholders; only the end reason and the wall matter to the rule.
    static GameResult soloTimeout(long ms) {
        return new GameResult(SEED, null, "Timeout", 0, 0, true, null, ms, "TIMEOUT", List.of(), 0L);
    }

    static GameResult soloDecided(long ms) {
        return new GameResult(SEED, 0, "AllOpponentsLost", 24, 0, false, null, ms, "placeholder", List.of(), 0L);
    }

    static GameResult soloError(long ms) {
        return new GameResult(SEED, null, "Error", 0, 0, false, "game ended without finishing", ms, "ERROR", List.of(), 0L);
    }

    // For the comparator probe: a decided play with a digest the test chooses, a Timeout play, and a play of an entry in an arm.
    private static GameResult decided(String digest) {
        return new GameResult(SEED, 0, "AllOpponentsLost", 24, 0, false, null, 1_000L, digest, List.of(), 0L);
    }

    private static GameResult timeout() {
        return soloTimeout(1_000L);
    }

    private static Played played(Entry entry, String arm, GameResult result) {
        return new Played(entry, arm, result);
    }

    @Test
    public void budgetIsTheListTimeoutOrThreeTimesTheSoloWall() {
        Assert.assertEquals(DeterminismBattery.budgetSeconds(1200, 100_000L), 1200);
        Assert.assertEquals(DeterminismBattery.budgetSeconds(1200, 884_927L), 2655);   // ceil(2654.781)
        Assert.assertEquals(DeterminismBattery.budgetSeconds(1, 500L), 2);             // ceil(1.5)
        Assert.assertEquals(DeterminismBattery.budgetSeconds(300, 100_000L), 300);
    }

    @Test
    public void budgetedKeepsSeedAndSeatsAndRaisesOnlyTheTimeout() {
        GameSpec spec = entry("1v1-default", Precons.commander().subList(0, 2), 300).spec();
        GameSpec b = DeterminismBattery.budgeted(spec, 884_927L);
        Assert.assertEquals(b.seed(), spec.seed());
        Assert.assertEquals(b.seats(), spec.seats());
        Assert.assertEquals(b.timeoutSeconds(), 2655);
        Assert.assertEquals(DeterminismBattery.budgeted(spec, 1_000L).timeoutSeconds(), 300, "a fast solo game keeps the list timeout");
    }

    @Test
    public void specAfterReferenceBudgetsDecidedGamesSkipsSoloTimeoutsAndKeepsErrorsPlayable() {
        Entry e = entry("1v1-default", Precons.commander().subList(0, 2), 300);
        Assert.assertSame(DeterminismBattery.specAfterReference(e, null), e.spec(), "no reference: the list spec");
        Assert.assertNull(DeterminismBattery.specAfterReference(e, soloTimeout(1_200_009L)), "a solo Timeout: untestable");
        Assert.assertEquals(DeterminismBattery.specAfterReference(e, soloDecided(884_927L)).timeoutSeconds(), 2655, "a decided game: the budget");
        GameSpec err = DeterminismBattery.specAfterReference(e, soloError(1_000L));
        Assert.assertNotNull(err, "a solo Error is played again and stays a problem; it is never untestable");
        Assert.assertEquals(err.timeoutSeconds(), 300);
        Assert.assertEquals(DeterminismBattery.specAfterReference(e, soloDecided(1_000L)).seats(), e.spec().seats());
    }

    @Test
    public void mismatchesReportsDivergenceAndMissingPlaysButSkipsUntestableGames() {
        List<Path> precons = Precons.commander();
        Entry g1 = entry("1v1-default", precons.subList(0, 2), SEED, 300);
        Entry g2 = entry("pod-default", precons.subList(0, 4), SEED, 1);
        Entry g3 = entry("1v1-default", precons.subList(0, 2), SEED + 1, 300);
        List<Entry> list = List.of(g1, g2, g3);
        List<String> arms = List.of("A", "B");
        List<Played> played = List.of(
                played(g1, "A", decided("d1")), played(g1, "B", decided("d2")),      // divergence
                played(g2, "A", decided("same")),                                     // missing in B
                played(g3, "A", timeout()),                                           // untestable: skipped in B by design
                played(g3, "B", timeout()));                                          // (if it were ever played, it is still skipped)
        Set<String> untestable = Set.of(g3.label());
        List<String> m = DeterminismBattery.mismatches(list, arms, played, untestable);
        Assert.assertEquals(m.size(), 2, "the divergence and the missing play, nothing for the untestable game");
        Assert.assertTrue(m.get(0).startsWith("MISMATCH\t" + g1.label()), m.get(0));
        Assert.assertTrue(m.get(0).contains("\tA=d1\tB=d2"), m.get(0));
        Assert.assertTrue(m.get(1).startsWith("MISMATCH\t" + g2.label()), m.get(1));
        Assert.assertTrue(m.get(1).contains("\tA=same\tB=missing"), m.get(1));
        Assert.assertEquals(DeterminismBattery.mismatches(list, arms, played, Set.of()).size(), 2,
                "unmarked, g3 still agrees: both arms played it and its two Timeouts digest alike, so the count stays 2, not 3");
        // The production shape: the later arm never played the untestable game, so only the skip keeps it out of the report.
        List<Played> asRun = played.subList(0, 4);
        Assert.assertEquals(DeterminismBattery.mismatches(list, arms, asRun, untestable).size(), 2, "g3 is skipped, not missing in B");
        Assert.assertEquals(DeterminismBattery.mismatches(list, arms, asRun, Set.of()).size(), 3, "without the skip g3 reads as missing in B");
        List<String> p = DeterminismBattery.problems(played, untestable);
        Assert.assertEquals(p.size(), 0, "the solo Timeout of the untestable game is not a problem");
        Assert.assertEquals(DeterminismBattery.problems(played, Set.of()).size(), 2, "without the untestable set both Timeout plays are problems");
    }

    @Test
    public void aPlayDuringWhichStrictModeRefusedAnAccessIsAProblemEvenWhenDecided() {
        Entry e = entry("1v1-default", Precons.commander().subList(0, 2), 300);
        GameResult swallowed = new GameResult(SEED, 0, "AllOpponentsLost", 24, 0, false, null, 1_000L, "placeholder", List.of(), 2L);
        String expected = "PROBLEM\t" + e.label() + "\tA\tStrictViolations\t2";
        Assert.assertEquals(DeterminismBattery.problems(List.of(played(e, "A", swallowed)), Set.of()), List.of(expected),
                "a decided game is a problem when strict mode refused an access while it played: the refusal may have been swallowed");
        Assert.assertEquals(DeterminismBattery.problems(List.of(played(e, "A", swallowed)), Set.of(e.label())), List.of(expected),
                "untestable or not: a refusal is a finding wherever it happens");
        Assert.assertEquals(DeterminismBattery.problems(List.of(played(e, "A", decided("d"))), Set.of()), List.of(), "no refusal, no problem");
        Assert.assertTrue(DeterminismBattery.gameLine(played(e, "A", swallowed)).endsWith("\tplaceholder\tviolations=2"),
                DeterminismBattery.gameLine(played(e, "A", swallowed)));
        Assert.assertTrue(DeterminismBattery.gameLine(played(e, "A", decided("d"))).endsWith("\td"), "no count, no column");
    }

    @Test(timeOut = 600_000)
    public void aSoloTimeoutMakesTheGameUntestableAndItIsNotPlayedAgain() {
        List<Entry> list = podThenOneVsOne();
        String podLabel = list.get(0).label();
        String oneVsOneLabel = list.get(1).label();
        Report r = DeterminismBattery.run(list, List.of("A", "B"), 2, null);
        Assert.assertEquals(r.untestable(), List.of(podLabel));
        Assert.assertEquals(r.problems(), List.of(), "the solo Timeout is not a problem");
        Assert.assertEquals(r.mismatches(), List.of(), "the untestable game is not compared");
        Assert.assertEquals(r.played().stream().filter(p -> p.arm().equals("A")).count(), 2L);
        Assert.assertEquals(r.played().stream().filter(p -> p.arm().equals("B")).count(), 1L, "the pod is not played in B");
        Assert.assertEquals(r.played().stream().filter(p -> p.arm().equals("B")).findFirst().get().entry().label(), oneVsOneLabel);
        Assert.assertTrue(r.passed());
        Assert.assertFalse(r.poisoned());
    }

    @Test(timeOut = 600_000)
    public void withoutASoloReferenceEveryArmPlaysEverything() {
        // The same two-entry list, arms B,C: no reference, so the pod is played in both arms and its two Timeouts are
        // problems, as before Task 7d.
        Report r = DeterminismBattery.run(podThenOneVsOne(), List.of("B", "C"), 2, null);
        Assert.assertEquals(r.untestable(), List.of());
        Assert.assertEquals(r.played().size(), 4);
        Assert.assertEquals(r.problems().size(), 2);
        Assert.assertFalse(r.passed());
    }

    @Test(timeOut = 600_000)
    public void armAIsTheReferenceOnlyWhenItRunsFirst() {
        // The same two-entry list with A second: B plays first, so there is no reference and nothing is untestable.
        Report r = DeterminismBattery.run(podThenOneVsOne(), List.of("B", "A", "C"), 2, null);
        Assert.assertEquals(r.untestable(), List.of(), "A ran second: no reference");
        Assert.assertEquals(r.played().size(), 6, "every arm played both games");
        Assert.assertEquals(r.problems().size(), 3, "the pod's Timeout in each of the three arms");
        Assert.assertFalse(r.passed());
    }

    @Test(timeOut = 600_000)
    public void theProductionShapeSkipsAnUntestableGameInEveryLaterArm() {
        List<Entry> list = podThenOneVsOne();
        String podLabel = list.get(0).label();
        Report r = DeterminismBattery.run(list, List.of("A", "B", "C"), 2, null);
        Assert.assertEquals(r.untestable(), List.of(podLabel));
        Assert.assertEquals(r.played().stream().filter(p -> p.arm().equals("A")).count(), 2L);
        Assert.assertEquals(r.played().stream().filter(p -> p.arm().equals("B")).count(), 1L, "B skipped the pod");
        Assert.assertEquals(r.played().stream().filter(p -> p.arm().equals("C")).count(), 1L, "C skipped the pod too");
        Assert.assertEquals(r.timings().stream().map(t -> t.games()).toList(), List.of(2, 1, 1));
        Assert.assertTrue(r.passed());
    }

    /** Beyond the brief's four: arm C plays the list reversed, so with arms A,C it meets the two untestable pods
     *  last-first; the report names them in list order all the same, and the arm's timing counts only what it played. */
    @Test(timeOut = 600_000)
    public void untestableGamesAreNamedInListOrderAndTimingsCountOnlyGamesPlayed() {
        List<Path> precons = Precons.commander();
        Entry podOne = entry("pod-default", precons.subList(0, 4), SEED, 1);
        Entry oneVsOne = entry("1v1-default", precons.subList(0, 2), SEED, 300);
        Entry podTwo = entry("pod-default", precons.subList(0, 4), SEED + 1, 1);
        Report r = DeterminismBattery.run(List.of(podOne, oneVsOne, podTwo), List.of("A", "C"), 2, null);
        Assert.assertEquals(r.untestable(), List.of(podOne.label(), podTwo.label()), "list order, not the order arm C met them in");
        Assert.assertEquals(r.timings().get(0).games(), 3, "arm A played the whole list");
        Assert.assertEquals(r.timings().get(1).games(), 1, "arm C played only the 1v1");
        Assert.assertEquals(r.problems(), List.of());
        Assert.assertEquals(r.mismatches(), List.of());
        Assert.assertTrue(r.passed());
        Assert.assertFalse(r.poisoned());
    }
}
