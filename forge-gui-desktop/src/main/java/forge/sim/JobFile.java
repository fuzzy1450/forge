package forge.sim;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;

/**
 * The farm's {@code key=value} job, read as MTG_DeckMaker's sim/java/mtgsim/Harness.java reads it in
 * {@code Job.load}: {@code games}, {@code seed}, {@code timeout_s}, {@code format} and {@code raw_dir}, and per seat
 * {@code seat.<N>.deck_file}, {@code seat.<N>.deck_hash}, {@code seat.<N>.profile} (empty: Default) and
 * {@code seat.<N>.ai} (empty: default). Blank lines and lines without {@code =} are skipped, the last value of a key
 * wins, the seats are taken in index order, and a job needs at least two of them and format Commander. A job that
 * cannot be loaded is a {@link BadJob} naming the key or the rule. {@link #specs()} yields one
 * {@link GameRunner.GameSpec} per game; the players are built from those by {@link GameRunner#registerPlayers},
 * which also maps the ai string to AI options.
 */
public final class JobFile {
    public record Seat(String deckFile, String deckHash, String profile, String ai) { }
    /** A job that cannot be loaded; the message names the key or the rule. */
    public static final class BadJob extends RuntimeException { public BadJob(String message) { super(message); } }

    public final int games;          // default 1
    public final long seed;          // default 0
    public final int timeoutS;       // default 600
    public final String format;      // default "Commander"; anything else is BadJob (case ignored, as the harness)
    public final String rawDir;      // default ""
    public final List<Seat> seats;   // in seat-index order; fewer than two is BadJob

    /** One seat while its lines are read, with Harness.SeatSpec's defaults. */
    private static final class SeatFields {
        String deckFile = "";
        String deckHash = "";
        String profile = "Default";
        String ai = "default";
    }

    private JobFile(int games, long seed, int timeoutS, String format, String rawDir, List<Seat> seats) {
        this.games = games;
        this.seed = seed;
        this.timeoutS = timeoutS;
        this.format = format;
        this.rawDir = rawDir;
        this.seats = seats;
    }

    /** Reads a job file (UTF-8) and parses it: Harness.Job.load, with {@link BadJob} in place of its Fatal. */
    public static JobFile load(Path path) throws IOException {
        return parse(Files.readAllLines(path, StandardCharsets.UTF_8));
    }

    /** The lines of a job file, parsed as {@link #load} parses the file's. */
    public static JobFile parse(List<String> lines) {
        int games = 1;
        int timeoutS = 600;
        long seed = 0;
        String format = "Commander";
        String rawDir = "";
        TreeMap<Integer, SeatFields> seats = new TreeMap<>();
        for (String line : lines) {
            int eq = line.indexOf('=');
            if (line.isBlank() || eq < 0) {
                continue;
            }
            String key = line.substring(0, eq).trim();
            String val = line.substring(eq + 1).trim();
            if (key.startsWith("seat.")) {
                String[] parts = key.split("\\.", 3);
                SeatFields s = seats.computeIfAbsent(seatIndex(key, parts[1]), k -> new SeatFields());
                switch (parts[2]) {
                    case "deck_file": s.deckFile = val; break;
                    case "deck_hash": s.deckHash = val; break;
                    case "profile":   s.profile = val.isEmpty() ? "Default" : val; break;
                    case "ai":        s.ai = val.isEmpty() ? "default" : val; break;
                    default: throw new BadJob("unknown job key " + key);
                }
            } else {
                try {
                    switch (key) {
                        case "games":     games = Integer.parseInt(val); break;
                        case "seed":      seed = Long.parseLong(val); break;
                        case "timeout_s": timeoutS = Integer.parseInt(val); break;
                        case "format":    format = val; break;
                        case "raw_dir":   rawDir = val; break;
                        default: throw new BadJob("unknown job key " + key);
                    }
                } catch (NumberFormatException e) {
                    throw new BadJob("bad value for " + key + ": " + val);
                }
            }
        }
        if (seats.size() < 2) {
            throw new BadJob("a job needs at least two seats");
        }
        if (!format.equalsIgnoreCase("Commander")) {
            throw new BadJob("only format=Commander is supported");
        }
        List<Seat> inOrder = new ArrayList<>();
        for (SeatFields s : seats.values()) {
            inOrder.add(new Seat(s.deckFile, s.deckHash, s.profile, s.ai));
        }
        return new JobFile(games, seed, timeoutS, format, rawDir, List.copyOf(inOrder));
    }

    /** The {@code N} of a {@code seat.<N>.<field>} key; one that is not a number is a BadJob naming the key. */
    private static int seatIndex(String key, String index) {
        try {
            return Integer.parseInt(index);
        } catch (NumberFormatException e) {
            throw new BadJob("bad seat index in job key " + key);
        }
    }

    /** One {@link GameRunner.GameSpec} per game, at seed + i, each with the job's timeout and its seats in order. */
    public List<GameRunner.GameSpec> specs() {
        List<GameRunner.SeatSpec> seatSpecs = new ArrayList<>();
        for (Seat s : seats) {
            seatSpecs.add(new GameRunner.SeatSpec(Paths.get(s.deckFile()), s.profile(), s.ai()));
        }
        List<GameRunner.GameSpec> out = new ArrayList<>();
        for (int g = 0; g < games; g++) {
            out.add(new GameRunner.GameSpec(seed + g, timeoutS, seatSpecs));
        }
        return out;
    }
}
