package forge.sim;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;

import forge.sim.GameRunner.GameResult;
import forge.sim.GameRunner.GameSpec;
import forge.sim.GameRunner.SeatSpec;

/**
 * The determinism battery (spec section 7): a fixed list of games played through three arms --
 * A: one slot in list order, B: {@code slots} slots in list order, C: {@code slots} slots in
 * reversed order -- whose digests must be identical game for game.
 *
 * <pre>
 * java ... forge.sim.DeterminismBattery [--arms A,B,C] [--slots 6] [--list games.tsv | --smoke] [--out dir]
 * </pre>
 *
 * A {@code --list} file has one game per line: {@code seed TAB timeout_s TAB seat TAB seat...},
 * each seat {@code deckfile;profile;ai}. Output (stdout, tab-separated): {@code GAME} lines per
 * game and arm, {@code ARM} timings, {@code MISMATCH} and {@code PROBLEM} findings, one
 * {@code RESULT} line. Exit 0 on PASS, 1 on FAIL, 5 if the runner was poisoned.
 */
public final class DeterminismBattery {

    public record Entry(String label, GameSpec spec) { }

    public record Played(Entry entry, String arm, GameResult result) { }

    public record ArmTiming(String arm, int slots, long wallMs, int games) { }

    public record Report(List<Entry> list, List<Played> played, List<ArmTiming> timings,
                         List<String> mismatches, List<String> problems, boolean poisoned) {
        public boolean passed() {
            return mismatches.isEmpty() && problems.isEmpty() && !poisoned;
        }
    }

    static final long SEED_BASE = 7_000_000L;
    // Per game. The concurrent arms run a game a median 1.4x slower than solo (p90 1.7x, max 2.1x on the
    // 2026-10-05 desktop), so a game that takes 150-270 s alone would time out only in arms B and C at the
    // farm's 300 s: a wall-clock artifact, not a determinism signal. A real hang still ends in a Timeout.
    static final int TIMEOUT_S = 1200;
    static final Set<String> DECIDED = Set.of("AllOpponentsLost", "WinsGameSpellEffect", "Draw");

    private DeterminismBattery() { }

    // ------------------------------------------------------------------ the lists (spec 7.1)

    /** 180 games: seven 1v1 pairings at 20 seeds, two pods at 10, two hybrid_sim pairings at 10. */
    public static List<Entry> fullList() {
        // Precons.commander() reads ForgeConstants, whose initializer needs Forge booted.
        GameRunner.boot();
        List<Path> p = Precons.commander();
        if (p.size() < 14) {
            throw new IllegalStateException("the battery needs 14 Commander precons, found " + p.size() + " in " + p);
        }
        List<Entry> out = new ArrayList<>();
        for (int i = 0; i + 1 < 14; i += 2) {
            add(out, "1v1-default", List.of(p.get(i), p.get(i + 1)), List.of("default", "default"), 20);
        }
        add(out, "pod-default", p.subList(0, 4), List.of("default", "default", "default", "default"), 10);
        add(out, "pod-default", p.subList(4, 8), List.of("default", "default", "default", "default"), 10);
        add(out, "1v1-hybrid", List.of(p.get(8), p.get(9)), List.of("hybrid_sim", "default"), 10);
        add(out, "1v1-hybrid", List.of(p.get(10), p.get(11)), List.of("hybrid_sim", "default"), 10);
        return out;
    }

    /** Spec 7.6: (P1,P2) and (P3,P4) at two seeds, default AI. */
    public static List<Entry> smokeList() {
        // Precons.commander() reads ForgeConstants, whose initializer needs Forge booted.
        GameRunner.boot();
        List<Path> p = Precons.commander();
        if (p.size() < 4) {
            throw new IllegalStateException("the smoke list needs 4 Commander precons, found " + p.size());
        }
        List<Entry> out = new ArrayList<>();
        add(out, "smoke", List.of(p.get(0), p.get(1)), List.of("default", "default"), 2);
        add(out, "smoke", List.of(p.get(2), p.get(3)), List.of("default", "default"), 2);
        return out;
    }

    private static void add(List<Entry> out, String slice, List<Path> decks, List<String> ais, int seeds) {
        String names = decks.stream().map(d -> d.getFileName().toString().replace(".dck", "")).collect(Collectors.joining("+"));
        for (int i = 0; i < seeds; i++) {
            List<SeatSpec> seats = new ArrayList<>();
            for (int s = 0; s < decks.size(); s++) {
                seats.add(new SeatSpec(decks.get(s), "Default", ais.get(s)));
            }
            out.add(new Entry(slice + "/" + names + "/" + (SEED_BASE + i), new GameSpec(SEED_BASE + i, TIMEOUT_S, seats)));
        }
    }

    /** One game per line: {@code seed TAB timeout_s TAB deck;profile;ai TAB deck;profile;ai ...}. */
    public static List<Entry> readList(Path tsv) throws IOException {
        List<Entry> out = new ArrayList<>();
        int n = 0;
        for (String line : Files.readAllLines(tsv, StandardCharsets.UTF_8)) {
            n++;
            if (line.isBlank()) {
                continue;
            }
            String[] f = line.split("\t");
            if (f.length < 4) {
                throw new IllegalArgumentException(tsv + ":" + n + ": need seed, timeout and at least two seats");
            }
            List<SeatSpec> seats = new ArrayList<>();
            for (int i = 2; i < f.length; i++) {
                String[] seat = f[i].split(";", -1);
                if (seat.length != 3) {
                    throw new IllegalArgumentException(tsv + ":" + n + ": seat " + (i - 2) + " must be deck;profile;ai, got " + f[i]);
                }
                seats.add(new SeatSpec(Paths.get(seat[0]), seat[1], seat[2]));
            }
            out.add(new Entry("line-" + n, new GameSpec(Long.parseLong(f[0].trim()), Integer.parseInt(f[1].trim()), seats)));
        }
        return out;
    }

    // ------------------------------------------------------------------ the arms (spec 7.2)

    public static Report run(List<Entry> list, List<String> arms, int slots, PrintStream progress) {
        GameRunner.boot();
        List<Played> played = new ArrayList<>();
        List<ArmTiming> timings = new ArrayList<>();
        boolean poisoned = false;
        for (String arm : arms) {
            int armSlots = arm.equals("A") ? 1 : slots;
            List<Entry> order = new ArrayList<>(list);
            if (arm.equals("C")) {
                Collections.reverse(order);
            }
            GameRunner runner = new GameRunner(armSlots);
            ExecutorService pool = Executors.newFixedThreadPool(armSlots);
            long t0 = System.currentTimeMillis();
            List<Future<Played>> futures = new ArrayList<>();
            for (Entry e : order) {
                futures.add(pool.submit(() -> new Played(e, arm, runner.play(e.spec()))));
            }
            for (Future<Played> f : futures) {
                try {
                    Played p = f.get();
                    played.add(p);
                    if (progress != null) {
                        progress.println(gameLine(p));
                        progress.flush();
                    }
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("interrupted while arm " + arm + " was playing", ie);
                } catch (ExecutionException ee) {
                    throw new IllegalStateException("arm " + arm + " could not play a game: " + ee.getCause(), ee.getCause());
                }
            }
            pool.shutdown();
            timings.add(new ArmTiming(arm, armSlots, System.currentTimeMillis() - t0, order.size()));
            poisoned |= runner.isPoisoned();
        }
        return new Report(list, played, timings, mismatches(list, arms, played), problems(played), poisoned);
    }

    public static String gameLine(Played p) {
        GameResult r = p.result();
        return "GAME\t" + p.entry().label() + "\t" + r.seed() + "\t" + p.arm() + "\t"
                + (r.winnerSeat() == null ? "" : r.winnerSeat()) + "\t" + r.endReason() + "\t" + r.turns() + "\t"
                + (r.firstSeat() == null ? "" : r.firstSeat()) + "\t" + r.ms() + "\t" + r.digest();
    }

    // ------------------------------------------------------------------ the comparison (spec 7.4, 7.5)

    static List<String> mismatches(List<Entry> list, List<String> arms, List<Played> played) {
        Map<String, Map<String, Played>> byLabel = new LinkedHashMap<>();
        for (Played p : played) {
            byLabel.computeIfAbsent(p.entry().label(), k -> new LinkedHashMap<>()).put(p.arm(), p);
        }
        List<String> out = new ArrayList<>();
        for (Entry e : list) {
            Map<String, Played> arm = byLabel.getOrDefault(e.label(), Map.of());
            Set<String> digests = arm.values().stream().map(p -> p.result().digest()).collect(Collectors.toSet());
            if (digests.size() <= 1 && arm.size() == arms.size()) {
                continue;
            }
            StringBuilder sb = new StringBuilder("MISMATCH\t").append(e.label());
            for (String a : arms) {
                Played p = arm.get(a);
                sb.append('\t').append(a).append('=').append(p == null ? "missing" : p.result().digest());
            }
            List<Played> differing = new ArrayList<>(arm.values());
            for (int i = 1; i < differing.size(); i++) {
                Played x = differing.get(0), y = differing.get(i);
                if (!x.result().digest().equals(y.result().digest())) {
                    sb.append("\n  ").append(firstDivergence(x, y));
                    break;
                }
            }
            out.add(sb.toString());
        }
        return out;
    }

    static String firstDivergence(Played x, Played y) {
        List<String> lx = x.result().digestLines(), ly = y.result().digestLines();
        int n = Math.min(lx.size(), ly.size());
        for (int i = 0; i < n; i++) {
            if (!lx.get(i).equals(ly.get(i))) {
                return "first divergence at line " + i + "\n    " + x.arm() + ": " + lx.get(i) + "\n    " + y.arm() + ": " + ly.get(i);
            }
        }
        return "the first " + n + " lines agree; " + x.arm() + " has " + lx.size() + " lines, " + y.arm() + " has " + ly.size();
    }

    /** A game that did not end decided, or that strict mode stopped, is a finding whatever its digests say. */
    static List<String> problems(List<Played> played) {
        List<String> out = new ArrayList<>();
        for (Played p : played) {
            GameResult r = p.result();
            if (!DECIDED.contains(r.endReason())) {
                out.add("PROBLEM\t" + p.entry().label() + "\t" + p.arm() + "\t" + r.endReason() + "\t" + (r.error() == null ? "" : r.error()));
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ the CLI

    public static void main(String[] args) throws Exception {
        GameRunner.installHaltOnUncaught();
        // Our lines on fd 1; Forge's own chatter (card loading, the AI) goes to stderr.
        PrintStream out = new PrintStream(new FileOutputStream(FileDescriptor.out), true, StandardCharsets.UTF_8);
        System.setOut(new PrintStream(new FileOutputStream(FileDescriptor.err), true, StandardCharsets.UTF_8));

        List<String> arms = List.of("A", "B", "C");
        int slots = 6;
        Path listFile = null, outDir = null;
        boolean smoke = false;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--arms":  arms = Arrays.asList(args[++i].split(",")); break;
                case "--slots": slots = Integer.parseInt(args[++i]); break;
                case "--list":  listFile = Paths.get(args[++i]); break;
                case "--out":   outDir = Paths.get(args[++i]); break;
                case "--smoke": smoke = true; break;
                default:
                    System.err.println("usage: forge.sim.DeterminismBattery [--arms A,B,C] [--slots 6] [--list games.tsv | --smoke] [--out dir]");
                    System.exit(2);
            }
        }
        List<Entry> list = listFile != null ? readList(listFile) : smoke ? smokeList() : fullList();
        out.println("LIST\t" + list.size() + "\tgames\t" + String.join(",", arms) + "\tslots=" + slots);

        Report r = run(list, arms, slots, out);

        for (ArmTiming t : r.timings()) {
            double hours = t.wallMs() / 3_600_000.0;
            out.println("ARM\t" + t.arm() + "\tslots=" + t.slots() + "\tgames=" + t.games() + "\twall_s="
                    + String.format("%.1f", t.wallMs() / 1000.0) + "\tgames_per_hour=" + String.format("%.0f", t.games() / hours)
                    + "\theap_committed_mb=" + Runtime.getRuntime().totalMemory() / (1024 * 1024));
        }
        for (String m : r.mismatches()) {
            out.println(m);
        }
        for (String p : r.problems()) {
            out.println(p);
        }
        if (outDir != null) {
            Files.createDirectories(outDir);
            List<String> lines = new ArrayList<>();
            for (Played p : r.played()) {
                lines.add(gameLine(p));
            }
            Files.write(outDir.resolve("results.tsv"), lines, StandardCharsets.UTF_8);
            Set<String> badLabels = r.mismatches().stream().map(m -> m.split("\t")[1]).collect(Collectors.toSet());
            for (Played p : r.played()) {
                if (badLabels.contains(p.entry().label())) {
                    String name = p.entry().label().replaceAll("[^A-Za-z0-9._+-]", "_") + "-" + p.arm() + ".log";
                    Files.write(outDir.resolve(name), p.result().digestLines(), StandardCharsets.UTF_8);
                }
            }
        }
        out.println("RESULT\t" + (r.passed() ? "PASS" : "FAIL") + "\t" + list.size() + " games\t" + r.mismatches().size()
                + " mismatches\t" + r.problems().size() + " problems" + (r.poisoned() ? "\tPOISONED" : ""));
        out.flush();
        System.exit(r.poisoned() ? GameRunner.EXIT_POISONED : r.passed() ? 0 : 1);
    }
}
