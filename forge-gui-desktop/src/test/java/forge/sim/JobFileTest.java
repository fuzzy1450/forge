package forge.sim;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.testng.Assert;
import org.testng.annotations.Test;

import forge.sim.GameRunner.GameSpec;

public class JobFileTest {
    static final List<String> SAMPLE = List.of(
            "games=3", "seed=42000000000", "timeout_s=300", "format=Commander", "raw_dir=",
            "seat.0.deck_file=C:/decks/a.dck", "seat.0.deck_hash=aaaa", "seat.0.profile=Reckless", "seat.0.ai=hybrid_sim",
            "seat.1.deck_file=C:/decks/b.dck", "seat.1.deck_hash=bbbb", "seat.1.profile=", "seat.1.ai=",
            "", "seed=7000000");                               // blank lines skipped; the LAST value wins

    @Test
    public void parsesEveryKeyAndTheLastValueWins() {
        JobFile job = JobFile.parse(SAMPLE);
        Assert.assertEquals(job.games, 3);
        Assert.assertEquals(job.seed, 7_000_000L);
        Assert.assertEquals(job.timeoutS, 300);
        Assert.assertEquals(job.format, "Commander");
        Assert.assertEquals(job.rawDir, "");
        Assert.assertEquals(job.seats.size(), 2);
        Assert.assertEquals(job.seats.get(0), new JobFile.Seat("C:/decks/a.dck", "aaaa", "Reckless", "hybrid_sim"));
        Assert.assertEquals(job.seats.get(1), new JobFile.Seat("C:/decks/b.dck", "bbbb", "Default", "default"),
                "an empty profile is Default and an empty ai is default, as Harness.Job.load reads them");
    }

    @Test
    public void defaultsMatchTheHarness() {
        JobFile job = JobFile.parse(List.of("seat.0.deck_file=a.dck", "seat.0.deck_hash=a",
                                            "seat.1.deck_file=b.dck", "seat.1.deck_hash=b"));
        Assert.assertEquals(job.games, 1);
        Assert.assertEquals(job.seed, 0L);
        Assert.assertEquals(job.timeoutS, 600);
        Assert.assertEquals(job.format, "Commander");
    }

    @Test
    public void seatsAreInIndexOrderWithTheHarnessDefaults() {
        JobFile job = JobFile.parse(List.of("seat.1.deck_file=b.dck", "seat.1.deck_hash=b",
                                            "seat.0.deck_file=a.dck", "seat.0.deck_hash=a"));
        Assert.assertEquals(job.rawDir, "");
        Assert.assertEquals(job.seats, List.of(new JobFile.Seat("a.dck", "a", "Default", "default"),
                                               new JobFile.Seat("b.dck", "b", "Default", "default")),
                "seats in index order, not file order; an absent profile is Default and an absent ai default");
    }

    @Test
    public void specsAreOnePerGameWithConsecutiveSeeds() {
        List<GameSpec> specs = JobFile.parse(SAMPLE).specs();
        Assert.assertEquals(specs.size(), 3);
        for (int i = 0; i < 3; i++) {
            Assert.assertEquals(specs.get(i).seed(), 7_000_000L + i);
            Assert.assertEquals(specs.get(i).timeoutSeconds(), 300);
            Assert.assertEquals(specs.get(i).seats().get(0).deckFile(), Path.of("C:/decks/a.dck"));
            Assert.assertEquals(specs.get(i).seats().get(0).profile(), "Reckless");
            Assert.assertEquals(specs.get(i).seats().get(0).ai(), "hybrid_sim");
            Assert.assertEquals(specs.get(i).seats().get(1).profile(), "Default");
            Assert.assertEquals(specs.get(i).seats().get(1).ai(), "default");
        }
    }

    @Test
    public void refusalsNameTheRule() {
        expectBad(List.of("seat.0.deck_file=a", "seat.0.deck_hash=a"), "at least two seats");
        expectBad(List.of("format=Standard", "seat.0.deck_file=a", "seat.0.deck_hash=a",
                          "seat.1.deck_file=b", "seat.1.deck_hash=b"), "format=Commander");
        expectBad(List.of("colour=blue", "seat.0.deck_file=a", "seat.0.deck_hash=a",
                          "seat.1.deck_file=b", "seat.1.deck_hash=b"), "colour");
        expectBad(List.of("seat.0.colour=blue", "seat.0.deck_file=a", "seat.0.deck_hash=a",
                          "seat.1.deck_file=b", "seat.1.deck_hash=b"), "seat.0.colour");
        expectBad(List.of("games=three", "seat.0.deck_file=a", "seat.0.deck_hash=a",
                          "seat.1.deck_file=b", "seat.1.deck_hash=b"), "games");
        expectBad(List.of("seat.0=x", "seat.0.deck_file=a", "seat.0.deck_hash=a",
                          "seat.1.deck_file=b", "seat.1.deck_hash=b"), "seat.0");
        expectBad(List.of("seat.x.deck_file=a", "seat.0.deck_file=a", "seat.0.deck_hash=a",
                          "seat.1.deck_file=b", "seat.1.deck_hash=b"), "seat.x.deck_file");
    }

    private static void expectBad(List<String> lines, String fragment) {
        try {
            JobFile.parse(lines);
            Assert.fail("expected BadJob mentioning " + fragment);
        } catch (JobFile.BadJob e) {
            Assert.assertTrue(e.getMessage().contains(fragment), e.getMessage());
        }
    }

    @Test
    public void loadReadsAFile() throws Exception {
        Path f = Files.createTempFile("jobfile", ".job");
        try {
            Files.write(f, SAMPLE);
            Assert.assertEquals(JobFile.load(f).games, 3);
        } finally {
            Files.deleteIfExists(f);
        }
    }
}
