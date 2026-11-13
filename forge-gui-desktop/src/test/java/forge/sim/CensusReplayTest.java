package forge.sim;

import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.testng.Assert;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import forge.util.SimScope;

/**
 * Games that the Flat MC ship engine's census ended as an Error on 2.0.15+23fd6633b91 (MTG_DeckMaker's
 * tools/combo-ai/flat-mc/SHIP-PLAN.md, Tasks 7a and 7c; the census is H:/mtg-combo-lanes/flatmc-ship/diag/census.md), replayed
 * as the census played them: a one-game job of the game's census job file, at the game's seed, with its two seats'
 * deck lists, Default profiles and default AI words and the census's 1200-s limit, read by JobFile and played by
 * JobRunner on a one-slot GameRunner. Each deck list is the job's deck_file, copied byte for byte into this class's
 * resources as census/&lt;hash prefix&gt;.dck. Each game must end with no Error.
 */
public class CensusReplayTest {
    /** The census's limit on a game, from its job files. */
    private static final int TIMEOUT_S = 1200;

    @BeforeClass
    public void boot() {
        GameRunner.boot();
    }

    @AfterClass
    public void relaxStrict() {
        SimScope.setStrict(false);
    }

    /**
     * job-3 game 1, seed 990000003001: sauron-s-pile-o-nuts-old-v1 (005a83fb) in seat 0 against
     * nathan-drake-treasure-hunter+32 (006fb3f8) in seat 1. Seat 1's Siphon Insight exiled seat 0's Exotic Orchard
     * face down on turn 5. On turn 6 seat 1's land choice rolled that land out beside a Mountain from its hand, the
     * rollout asked the real land ability's canPlay(), which took a real timestamp, and the census ended the game: "a
     * rollout wrote the real game: timestamp 40 -> 41".
     */
    @Test(timeOut = 1_300_000)
    public void aLandChoiceOverAFaceDownExiledLandPlaysOn() throws Exception {
        assertNoError(play(censusJob(990_000_003_001L,
                "005a83fb", "005a83fbf50b95ed36dde518dbebebf9cb0e49bf2708d3deb7b7114531bfefeb",
                "006fb3f8", "006fb3f8a8380441132109a5fd13c4fd4f84091d12523ba0c76ac1f1f8cfd01f")));
    }

    /**
     * job-162 game 0, seed 990000162000: Rabbits, Rabbits, Rabbits (331ad298) in seat 0 against Jeskai Artifacts and
     * Energy (33431700) in seat 1. On turn 24 a rollout of seat 1's main-phase search could not pay a Vanish into
     * Eternity in the copy and rolled the cast back, undoing a mana ability paid from seat 1's floating mana, and the
     * refund put that {C} in the real pool: the census ended the game, "a rollout wrote the real game: Seat1's
     * floating mana 5 -> 6".
     */
    @Test(timeOut = 1_300_000)
    public void aCastRolledBackInARolloutPlaysOn() throws Exception {
        assertNoError(play(censusJob(990_000_162_000L,
                "331ad298", "331ad2984e14cf7e5c45a4a02120058eac0091d2c77fd87063627bcb642977b6",
                "33431700", "33431700855b00ac1320219bff4272329f663ee13aa1a72496900e9f6dcea389")));
    }

    /**
     * job-260 game 0, seed 990000260000: Esper Dungeon Reanimator (507d2ea4) in seat 0 against Neera - Wild Magic
     * (510f65b5) in seat 1. On turn 17, with Melek, Izzet Paragon in seat 1's hand, the AI read Melek's SpellCast list
     * (Instant.wasCastFromYourLibrary,Sorcery.wasCastFromYourLibrary) as one restriction, which threw, and the census
     * ended the game: "No element named Library,Sorcery.wasCastFromYourLibrary in enum Zone" (Task 7c).
     */
    @Test(timeOut = 1_300_000)
    public void aCastPayoffWithACommaListPlaysOn() throws Exception {
        assertNoError(play(censusJob(990_000_260_000L,
                "507d2ea4", "507d2ea41b77394771d8e6687b621eecd355ba6a2c13a09a5bfe8a4436e39e68",
                "510f65b5", "510f65b5164dbd7823d8eb0aab85105f87bb300fc5adca614a5ef0fc3ccd885b")));
    }

    /** The census's job for the game of {@code seed}, as its job file reads with games=1 and that seed: seat 0 plays
     *  the resource census/{@code deck0}.dck and seat 1 census/{@code deck1}.dck, both Default and default. */
    static JobFile censusJob(final long seed, final String deck0, final String hash0, final String deck1,
                             final String hash1) {
        return JobFile.parse(List.of("games=1", "seed=" + seed, "timeout_s=" + TIMEOUT_S, "format=Commander",
                "raw_dir=",
                "seat.0.deck_file=" + deck(deck0), "seat.0.deck_hash=" + hash0, "seat.0.profile=Default",
                "seat.0.ai=default",
                "seat.1.deck_file=" + deck(deck1), "seat.1.deck_hash=" + hash1, "seat.1.profile=Default",
                "seat.1.ai=default"));
    }

    /** The resource copy of the census deck list {@code prefix}.dck. */
    static Path deck(final String prefix) {
        final URL url = CensusReplayTest.class.getResource("census/" + prefix + ".dck");
        Assert.assertNotNull(url, "census/" + prefix + ".dck is missing from the test resources");
        try {
            return Paths.get(url.toURI());
        } catch (final URISyntaxException e) {
            throw new AssertionError(String.valueOf(url), e);
        }
    }

    /** The record of the job's one game, played as a served job is; the outcome goes to stdout for the test log. */
    static Map<String, Object> play(final JobFile job) throws InterruptedException {
        final List<Map<String, Object>> out = new ArrayList<>();
        new JobRunner(new GameRunner(1)).run(job, out::add);
        Assert.assertEquals(out.size(), 2, "one record and the summary: " + out);
        final Map<String, Object> rec = out.get(0);
        System.out.println("census replay seed=" + rec.get("seed") + " end_reason=" + rec.get("end_reason") + " turns="
                + rec.get("turns") + " winner_seat=" + rec.get("winner_seat") + " ms=" + rec.get("ms") + " error="
                + rec.get("error"));
        return rec;
    }

    static void assertNoError(final Map<String, Object> rec) {
        Assert.assertNotEquals(rec.get("end_reason"), "Error", "seed " + rec.get("seed") + " ended as an Error on turn "
                + rec.get("turns") + ": " + rec.get("error"));
    }
}
