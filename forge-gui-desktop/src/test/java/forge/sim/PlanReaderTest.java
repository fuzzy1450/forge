package forge.sim;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.testng.Assert;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import forge.StaticData;
import forge.ai.AiController;
import forge.ai.AiProfileUtil;
import forge.ai.AiProps;
import forge.ai.PlayerControllerAi;
import forge.ai.simulation.CommanderPlan;
import forge.ai.simulation.DeckPlan;
import forge.ai.simulation.FlatMonteCarloStats;
import forge.ai.simulation.PlanSignature;
import forge.game.player.Player;
import forge.localinstance.properties.ForgeConstants;
import forge.player.GamePlayerUtil;
import forge.sim.GameRunner.GameResult;
import forge.sim.GameRunner.GameSpec;
import forge.sim.GameRunner.SeatSpec;
import forge.util.SimScope;

/** The plan verb (MTG_DeckMaker tools/combo-ai/f5-guard/SPEC.md, sections 3.1 and 3.2), read through real mirrors. */
public class PlanReaderTest {
    private static final String COMMANDER = "Isamaru, Hound of Konda";
    /** Seven rocks at mana value 3 or less. */
    private static final List<String> ROCKS = List.of("Arcane Signet", "Mind Stone", "Fellwar Stone", "Commander's Sphere",
            "Izzet Signet", "Dimir Signet", "Talisman of Dominance");
    /** Twenty cheap creatures, GO_WIDE's bodies. */
    private static final List<String> BODIES = List.of("Llanowar Elves", "Elvish Mystic", "Fyndhorn Elves", "Birds of Paradise",
            "Elvish Visionary", "Wall of Roots", "Eternal Witness", "Sakura-Tribe Elder", "Wood Elves", "Elvish Archdruid",
            "Priest of Titania", "Joraga Treespeaker", "Arbor Elf", "Boreal Druid", "Avacyn's Pilgrim", "Noble Hierarch",
            "Bloom Tender", "Incubation Druid", "Elves of Deep Shadow", "Elvish Spirit Guide");

    /** Every temp file the tests made: deleted after the class. */
    private static final List<Path> TEMP = Collections.synchronizedList(new ArrayList<>());

    @BeforeClass
    public void boot() {
        GameRunner.boot();
    }

    @AfterClass
    public void relaxStrict() {
        SimScope.setStrict(false);
    }

    @AfterClass
    public void deleteTempFiles() throws IOException {
        for (Path p : TEMP) {
            Files.deleteIfExists(p);
        }
        TEMP.clear();
    }

    private static Path deck(List<String> commanders, List<String> main) throws IOException {
        Path f = Files.createTempFile("plan", ".dck");
        TEMP.add(f);
        List<String> lines = new ArrayList<>(List.of("[metadata]", "Name=" + f.getFileName(), "[Commander]"));
        for (String c : commanders) {
            lines.add("1 " + c);
        }
        lines.add("[Main]");
        lines.addAll(main);
        Files.write(f, lines, StandardCharsets.UTF_8);
        return f;
    }

    /** {@code main}, one of each, filled to 99 with Islands and Hill Giants, about forty lands whatever {@code main} holds. */
    private static Path listOf(List<String> main) throws IOException {
        List<String> lines = main.stream().map(n -> "1 " + n).collect(Collectors.toList());
        lines.add("38 Island");
        lines.add((61 - main.size()) + " Hill Giant");
        return deck(List.of(COMMANDER), lines);
    }

    /** RAMP by DeckPlan.classify's counters: the rocks, five spells of mana value 5 or more, and two top-level counters. */
    private static Path rampList(String slot) throws IOException {
        List<String> main = new ArrayList<>(ROCKS);
        main.addAll(List.of("Shivan Dragon", "Craw Wurm", "Mahamoti Djinn", "Serra Angel", "Sengir Vampire"));
        main.addAll(List.of("Counterspell", "Negate", slot));
        return listOf(main);
    }

    /** GO_WIDE with all three of its name sets full: finishers, bodies and finisher tutors, in that order of the signature. */
    private static List<String> goWide(String body) {
        List<String> main = new ArrayList<>(List.of("Craterhoof Behemoth", "Overrun", "Triumph of the Hordes"));
        main.addAll(List.of("Green Sun's Zenith", "Natural Order", "Chord of Calling", "Eldritch Evolution"));
        main.addAll(BODIES);
        main.add(body);
        return main;
    }

    private static Map<String, Object> read(GameRunner runner, Path deck) throws Exception {
        return PlanReader.read("a-list", deck, runner);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> planOf(GameRunner runner, Path deck) throws Exception {
        Map<String, Object> line = read(runner, deck);
        Assert.assertNull(line.get("error"), String.valueOf(line.get("error")));
        return (Map<String, Object>) line.get("plan");
    }

    private static String text(Object plan) {
        return GameRecords.Json.write(plan);
    }

    private static AiController brainOf(Player p) {
        return ((PlayerControllerAi) p.getController()).getAi();
    }

    /** The plan a game holds in seat {@code seat}, as the signature's text, at that game's first-turn hook. */
    private static String heldBy(GameRunner runner, GameSpec spec, int seat) throws Exception {
        AtomicReference<String> held = new AtomicReference<>();
        GameResult r = runner.play(spec, g -> { }, g -> {
            for (Player p : g.getRegisteredPlayers()) {
                if (GameRunner.seatOf(p.getName()) == seat) {
                    held.set(text(PlanSignature.of(brainOf(p).deckPlan(p), p)));
                }
            }
        });
        Assert.assertNotNull(held.get(), "seat " + seat + " at seed " + spec.seed() + ": " + r.error());
        return held.get();
    }

    /** The field a keep question fills: nothing reads a deck plan before the first keep question of a game. */
    private static Object planHeldBy(Player p) throws ReflectiveOperationException {
        Field f = AiController.class.getDeclaredField("flatMcDeckPlan");
        f.setAccessible(true);
        return f.get(brainOf(p));
    }

    /**
     * What section 3.1 hangs on, written before anything reads a plan: no mirror has ever been played, in the store or in
     * a fork test, and the verb plays one per list. Each of the first four precons, on both seats: the game reaches the
     * hook, and each seat was asked to keep, which is what filled its plan. Failing this takes 3.1's fallback.
     */
    @Test(timeOut = 300_000)
    public void aMirrorOfARealListReachesTheHook() throws Exception {
        GameRunner runner = new GameRunner(1);
        List<Path> precons = Precons.commander();
        Assert.assertTrue(precons.size() >= 4, "Forge ships Commander precons; found " + precons);
        for (Path deck : precons.subList(0, 4)) {
            SeatSpec seat = new SeatSpec(deck, "Default", "default");
            AtomicInteger hooks = new AtomicInteger();
            AtomicReference<String> failed = new AtomicReference<>();
            List<Object> plans = new ArrayList<>();
            GameResult r = runner.play(new GameSpec(1L, 120, List.of(seat, seat)), g -> { }, g -> {
                hooks.incrementAndGet();
                try {
                    for (Player p : g.getRegisteredPlayers()) {
                        plans.add(planHeldBy(p));
                    }
                } catch (ReflectiveOperationException e) {
                    failed.set(e.toString());
                }
            });
            String say = deck.getFileName() + ": " + r;
            Assert.assertNull(failed.get(), say);
            Assert.assertEquals(hooks.get(), 1, "the hook ran once: " + say);
            Assert.assertEquals(plans.size(), 2, "two seats: " + say);
            Assert.assertNotNull(plans.get(0), "seat 0 was asked to keep: " + say);
            Assert.assertNotNull(plans.get(1), "seat 1 was asked to keep: " + say);
            Assert.assertEquals(r.error(), "game ended without finishing", "nothing was thrown on the way: " + say);
            Assert.assertEquals(r.violations(), 0L, say);
            Assert.assertFalse(runner.isPoisoned(), say);
        }
    }

    /** The verb itself, through dispatch: a line per list in input order, blank lines skipped, the path the rest of the
     *  line, and a list that cannot load is an error line that stops nothing. */
    @Test(timeOut = 300_000)
    public void oneLinePerListInOrder() throws Exception {
        List<Path> precons = Precons.commander();
        Path lists = Files.createTempFile("lists", ".txt");
        TEMP.add(lists);
        Path missing = Paths.get("C:/no/such list.dck");
        Files.write(lists, List.of("first " + precons.get(0), "", "   ", "missing " + missing, "alone",
                "last   " + precons.get(1)), StandardCharsets.UTF_8);
        Assert.assertEquals(HarnessFacadeTest.dispatch("", "plan", lists.toString()), 0, HarnessFacadeTest.lastErr);
        List<String> out = List.of(HarnessFacadeTest.lastOut.split("\\R"));
        Assert.assertEquals(out.size(), 4, HarnessFacadeTest.lastOut);
        Assert.assertTrue(out.get(0).startsWith("{\"id\":\"first\",\"plan\":{\"archetype\":\""), out.get(0));
        Assert.assertEquals(out.get(1), "{\"id\":\"missing\",\"error\":"
                + GameRecords.Json.write("IllegalArgumentException: could not load deck " + missing) + "}");
        Assert.assertTrue(out.get(2).startsWith("{\"id\":\"alone\",\"error\":\""), out.get(2));
        Assert.assertTrue(out.get(3).startsWith("{\"id\":\"last\",\"plan\":{\"archetype\":\""), out.get(3));
    }

    /** The two seats' shuffles differ, the reads' games differ, and the text is the plan a real game holds, at any seed, in
     *  either seat, in a mirror or not. The list has a combo with all three name sets full, so order would show. */
    @Test(timeOut = 300_000)
    public void theVerbAnswersAsAGameReads() throws Exception {
        GameRunner runner = new GameRunner(1);
        Path deck = listOf(goWide("Grizzly Bears"));
        Map<String, Object> plan = planOf(runner, deck);
        @SuppressWarnings("unchecked")
        Map<String, Object> combo = ((List<Map<String, Object>>) plan.get("combos")).get(0);
        Assert.assertEquals(combo.get("kind"), "GO_WIDE", text(plan));
        for (String set : List.of("payoffs", "enablers", "cascaders")) {
            Assert.assertTrue(((List<?>) combo.get(set)).size() >= 2, set + " in " + text(plan));
        }
        SeatSpec list = new SeatSpec(deck, "Default", "default");
        SeatSpec precon = new SeatSpec(Precons.commander().get(0), "Default", "default");
        for (long seed = 1; seed <= 5; seed++) {
            Assert.assertEquals(heldBy(runner, new GameSpec(seed, 120, List.of(list, list)), 0), text(plan), "mirror, seat 0");
            Assert.assertEquals(heldBy(runner, new GameSpec(seed, 120, List.of(list, list)), 1), text(plan), "mirror, seat 1");
            Assert.assertEquals(heldBy(runner, new GameSpec(seed, 120, List.of(list, precon)), 0), text(plan), "seat 0");
            Assert.assertEquals(heldBy(runner, new GameSpec(seed, 120, List.of(precon, list)), 1), text(plan), "seat 1");
        }
    }

    /** The cache is one per game and shared by both seats, so a read through the other seat's brain would find the first
     *  seat's plan and two equal texts would prove nothing. {@code forSetting("true", other)} misses that entry. */
    @Test(timeOut = 120_000)
    public void theSecondReadIsFresh() throws Exception {
        GameRunner runner = new GameRunner(1);
        SeatSpec s = new SeatSpec(Precons.commander().get(0), "Default", "default");
        AtomicReference<PlanReader.Reads> reads = new AtomicReference<>();
        AtomicInteger entries = new AtomicInteger();
        runner.play(new GameSpec(1L, 120, List.of(s, s)), g -> { }, g -> {
            reads.set(PlanReader.Reads.at(g));
            Map<String, DeckPlan> table = SimScope.current().scratch(DeckPlan.class, HashMap::new);
            entries.set(table.size());
        });
        Assert.assertNotNull(reads.get());
        Assert.assertNotSame(reads.get().fresh(), reads.get().held(), "the second read is a read, not the cache's answer");
        Assert.assertEquals(entries.get(), 2, "the scope's table holds the keep question's entry and the fresh one");
    }

    private static GameResult stopped(long violations) {
        return new GameResult(1L, null, "Error", 1, 0, false, "game ended without finishing", 5L, "ERROR", List.of(), violations);
    }

    private static Map<String, Object> plan(int pairs) {
        Map<String, Object> plan = new LinkedHashMap<>();
        plan.put("archetype", "RAMP");
        plan.put("known_pairs", IntStream.range(0, pairs).mapToObj(i -> "A" + i + " + B" + i).collect(Collectors.toList()));
        return plan;
    }

    @Test
    public void readsThatDisagreeAreAnError() {
        Map<String, Object> ramp = plan(0), control = plan(0);
        control.put("archetype", "CONTROL");
        Map<String, Object> line = PlanReader.answer("x", stopped(0), ramp, control);
        Assert.assertFalse(line.containsKey("plan"), text(line));
        Assert.assertTrue(String.valueOf(line.get("error")).contains("CONTROL"), "the line says what differed: " + text(line));
        Assert.assertTrue(PlanReader.answer("x", stopped(0), ramp, plan(0)).containsKey("plan"), "two equal texts are the answer");
    }

    @Test
    public void violationsAreAnError() {
        Map<String, Object> line = PlanReader.answer("x", stopped(2), plan(0), plan(0));
        Assert.assertFalse(line.containsKey("plan"), text(line));
        Assert.assertTrue(String.valueOf(line.get("error")).contains("2"), text(line));
    }

    /** Past the cap CommanderPlan.scan keeps the first forty it walks, in an order that follows the library, so a list at
     *  the cap has no answer: at the step that gives one, and through a real list that holds more than forty pairs. */
    @Test(timeOut = 120_000)
    public void aListAtThePairCapIsAnError() throws Exception {
        int cap = PlanSignature.PAIR_CAP;
        Assert.assertTrue(PlanReader.answer("x", stopped(0), plan(cap - 1), plan(cap - 1)).containsKey("plan"));
        Map<String, Object> at = PlanReader.answer("x", stopped(0), plan(cap), plan(cap));
        Assert.assertFalse(at.containsKey("plan"), text(at));
        Assert.assertTrue(String.valueOf(at.get("error")).contains("cap"), text(at));

        Set<String> names = new LinkedHashSet<>();
        for (String line : Files.readAllLines(Paths.get(ForgeConstants.COMMANDER_BRACKET_COMBOS_FILE), StandardCharsets.UTF_8)) {
            String[] parts = line.split("\\s*\\|\\s*");
            if (names.size() < 90 && parts.length >= 3 && isCard(parts[1]) && isCard(parts[2])) {
                names.add(parts[1]);
                names.add(parts[2]);
            }
        }
        Map<String, Object> real = read(new GameRunner(1), listOf(new ArrayList<>(names)));
        Assert.assertFalse(real.containsKey("plan"), text(real));
        Assert.assertTrue(String.valueOf(real.get("error")).contains("cap"), text(real));
    }

    private static boolean isCard(String name) {
        return !name.contains("//") && StaticData.instance().getCommonCards().getCard(name) != null;
    }

    @Test(timeOut = 120_000)
    public void anArchetypeChangeMovesThePlan() throws Exception {
        GameRunner runner = new GameRunner(1);
        Map<String, Object> ramp = planOf(runner, rampList("Siphon Insight"));
        Map<String, Object> control = planOf(runner, rampList("An Offer You Can't Refuse"));
        Assert.assertEquals(ramp.get("archetype"), "RAMP", text(ramp));
        Assert.assertEquals(control.get("archetype"), "CONTROL", "a third top-level counter: " + text(control));
        ramp.remove("archetype");
        control.remove("archetype");
        Assert.assertEquals(text(ramp), text(control), "and nothing else moved");
    }

    @Test(timeOut = 120_000)
    public void aPairPieceMovesThePlan() throws Exception {
        GameRunner runner = new GameRunner(1);
        Map<String, Object> whole = planOf(runner, listOf(List.of("Demonic Consultation", "Thassa's Oracle")));
        Map<String, Object> cut = planOf(runner, listOf(List.of("Thassa's Oracle", "Grizzly Bears")));
        Assert.assertEquals(whole.get("known_pairs"), List.of("Demonic Consultation + Thassa's Oracle"), text(whole));
        Assert.assertEquals(cut.get("known_pairs"), List.of(), text(cut));
        Assert.assertNotEquals(text(whole), text(cut));
    }

    @Test(timeOut = 120_000)
    public void aComboPieceMovesThePlan() throws Exception {
        GameRunner runner = new GameRunner(1);
        Map<String, Object> before = planOf(runner, listOf(goWide("Grizzly Bears")));
        Map<String, Object> after = planOf(runner, listOf(goWide("Runeclaw Bear")));
        Assert.assertEquals(before.get("archetype"), after.get("archetype"));
        @SuppressWarnings("unchecked") Map<String, Object> a = ((List<Map<String, Object>>) before.get("combos")).get(0);
        @SuppressWarnings("unchecked") Map<String, Object> b = ((List<Map<String, Object>>) after.get("combos")).get(0);
        Assert.assertEquals(a.get("strength"), b.get("strength"), "a body for a body: only the name moved");
        Assert.assertTrue(((List<?>) a.get("enablers")).contains("Grizzly Bears"), text(a));
        Assert.assertTrue(((List<?>) b.get("enablers")).contains("Runeclaw Bear"), text(b));
        Assert.assertNotEquals(text(before), text(after));
    }

    /** ComboPlan finds Living End by name in the library with no MASS_REANIMATION read: this list has no cycler and no
     *  creature count, so the swap moves no combo, and only the bit shows it. */
    @Test(timeOut = 120_000)
    public void livingEndMovesThePlan() throws Exception {
        GameRunner runner = new GameRunner(1);
        Map<String, Object> with = planOf(runner, rampList("Living End"));
        Map<String, Object> without = planOf(runner, rampList("Siphon Insight"));
        Assert.assertEquals(with.get("combos"), List.of(), text(with));
        Assert.assertEquals(with.get("living_end"), Boolean.TRUE, text(with));
        Assert.assertEquals(without.get("living_end"), Boolean.FALSE, text(without));
        with.remove("living_end");
        without.remove("living_end");
        Assert.assertEquals(text(with), text(without), "and nothing else moved");
    }

    /** Eleven artifacts are five points of the ARTIFACTS strategy, twelve are six and it counts: Sol Ring in moves
     *  strategies alone, as walk 14 step 3 did, and no decision reads them in a harness game. */
    @Test(timeOut = 120_000)
    public void aStrategyOnlyChangeLeavesThePlanAlone() throws Exception {
        GameRunner runner = new GameRunner(1);
        List<String> artifacts = new ArrayList<>(ROCKS);
        artifacts.addAll(List.of("Golgari Signet", "Boros Signet", "Selesnya Signet", "Simic Signet"));
        artifacts.addAll(List.of("Shivan Dragon", "Craw Wurm", "Mahamoti Djinn", "Serra Angel", "Sengir Vampire"));
        List<String> withSolRing = new ArrayList<>(artifacts);
        withSolRing.add("Sol Ring");
        artifacts.add("Grizzly Bears");
        Path before = listOf(artifacts), after = listOf(withSolRing);
        Assert.assertEquals(strategies(runner, before), List.of());
        Assert.assertEquals(strategies(runner, after), List.of(CommanderPlan.Strategy.ARTIFACTS));
        Assert.assertEquals(text(planOf(runner, before)), text(planOf(runner, after)));
    }

    private static List<CommanderPlan.Strategy> strategies(GameRunner runner, Path deck) throws Exception {
        SeatSpec s = new SeatSpec(deck, "Default", "default");
        AtomicReference<List<CommanderPlan.Strategy>> found = new AtomicReference<>();
        runner.play(new GameSpec(1L, 120, List.of(s, s)), g -> { }, g -> {
            Player p = g.getRegisteredPlayers().get(0);
            found.set(brainOf(p).deckPlan(p).commander.getStrategies());
        });
        return found.get();
    }

    /** Every list has a fresh scope, game and cards, so a list's answer depends on no other in the JVM. */
    @Test(timeOut = 120_000)
    public void manyListsInOneJvmAnswerAsOne() throws Exception {
        Path a = rampList("Siphon Insight"), b = rampList("An Offer You Can't Refuse"), c = listOf(goWide("Grizzly Bears"));
        GameRunner runner = new GameRunner(1);
        String first = text(planOf(runner, a)), second = text(planOf(runner, b)), third = text(planOf(runner, c));
        Assert.assertEquals(text(planOf(runner, c)), third, "the last again");
        Assert.assertEquals(text(planOf(runner, b)), second, "reversed");
        Assert.assertEquals(text(planOf(runner, a)), first, "reversed");
        Assert.assertEquals(text(planOf(runner, b)), second, "a repeated list");
        Assert.assertEquals(text(planOf(new GameRunner(1), a)), first, "a new runner");
        Assert.assertEquals(new LinkedHashSet<>(List.of(first, second, third)).size(), 3, "three lists, three plans");
    }

    /** A read plays under a scope, so the plan stays in the scope's scratch: the JVM's table and Flat Monte Carlo's statistics
     *  do not see it. */
    @Test(timeOut = 120_000)
    public void aReadTouchesNoJvmTable() throws Exception {
        Field f = DeckPlan.class.getDeclaredField("KNOWN");
        f.setAccessible(true);
        Map<?, ?> known = (Map<?, ?>) f.get(null);
        List<Object> before = new ArrayList<>(known.keySet());
        FlatMonteCarloStats.reset();
        Path deck = rampList("Siphon Insight");
        Assert.assertEquals(planOf(new GameRunner(1), deck).get("archetype"), "RAMP", "a plan worth counting");
        Assert.assertEquals(new ArrayList<>(known.keySet()), before, "DeckPlan.KNOWN is unchanged");
        Assert.assertEquals(plansCounted(), 0, "as MergeDeckPlanScopeTest counts them");
    }

    @SuppressWarnings("unchecked")
    private static int plansCounted() throws Exception {
        Field f = FlatMonteCarloStats.class.getDeclaredField("PLANS");
        f.setAccessible(true);
        int n = 0;
        synchronized (FlatMonteCarloStats.class) {
            for (int[] c : ((Map<String, int[]>) f.get(null)).values()) {
                n += c[0];
            }
        }
        return n;
    }

    /** The store's key has no profile, because every shipped profile reads the defaults. A profile that names either
     *  setting makes that key too coarse, and this says so. */
    @Test
    public void everyShippedProfileReadsAuto() {
        List<String> profiles = AiProfileUtil.getAvailableProfiles();
        Assert.assertFalse(profiles.isEmpty());
        for (String profile : profiles) {
            forge.LobbyPlayer ai = GamePlayerUtil.createAiPlayer("x", 0, 0, null, profile);
            Assert.assertEquals(AiProfileUtil.getAIProp(ai, AiProps.FLAT_MC_DECK_PLAN), "auto", profile);
            Assert.assertEquals(AiProfileUtil.getAIProp(ai, AiProps.FLAT_MC_MULLIGAN), "true", profile);
        }
    }
}
