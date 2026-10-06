package forge.sim;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

import com.google.common.eventbus.Subscribe;

import forge.GuiDesktop;
import forge.ai.AIOption;
import forge.ai.AiProfileUtil;
import forge.deck.Deck;
import forge.deck.io.DeckSerializer;
import forge.game.Game;
import forge.game.GameEndReason;
import forge.game.GameLogEntry;
import forge.game.GameLogEntryType;
import forge.game.GameOutcome;
import forge.game.GameRules;
import forge.game.GameType;
import forge.game.Match;
import forge.game.card.Card;
import forge.game.event.GameEventGameStarted;
import forge.game.event.GameEventTurnBegan;
import forge.game.player.Player;
import forge.game.player.PlayerView;
import forge.game.player.RegisteredPlayer;
import forge.game.zone.ZoneType;
import forge.gui.GuiBase;
import forge.localinstance.properties.ForgeConstants;
import forge.localinstance.properties.ForgePreferences.FPref;
import forge.model.FModel;
import forge.player.GamePlayerUtil;
import forge.util.GameAbandoned;
import forge.util.Localizer;
import forge.util.MyRandom;
import forge.util.SimScope;

/**
 * Plays Commander games in THIS JVM, several at once, each on its own thread under its own
 * {@link SimScope}. {@link #boot()} once, then {@link #play} as often as wanted; at most
 * {@code slots} games run at a time. Design: MTG_DeckMaker's
 * docs/superpowers/specs/2026-10-05-sim-scope-design.md, sections 5 and 6.
 */
public final class GameRunner {

    public record SeatSpec(Path deckFile, String profile, String ai) { }

    public record GameSpec(long seed, int timeoutSeconds, List<SeatSpec> seats) { }

    public record GameResult(long seed, Integer winnerSeat, String endReason, int turns, Integer firstSeat,
                             boolean timedOut, String error, long ms, String digest, List<String> digestLines) { }

    /** Thrown by {@link #play} once a game thread has outlived its cancellation. */
    public static final class PoisonedException extends IllegalStateException {
        private static final long serialVersionUID = 1L;

        PoisonedException() {
            super("this JVM is poisoned: a game thread outlived its cancellation; drain and exit " + EXIT_POISONED);
        }
    }

    public static final int EXIT_POISONED = 5;
    public static final int EXIT_UNCAUGHT = 4;
    public static final long GRACE_MS = 15_000L;
    static final List<ZoneType> DIGEST_ZONES = List.of(ZoneType.Library, ZoneType.Hand, ZoneType.Battlefield,
            ZoneType.Graveyard, ZoneType.Exile, ZoneType.Command);

    private static volatile boolean booted = false;

    private final int slotCount;
    private final BlockingQueue<Integer> freeSlots;
    private volatile boolean poisoned = false;

    public GameRunner(int slots) {
        if (slots < 1) {
            throw new IllegalArgumentException("slots must be >= 1, got " + slots);
        }
        this.slotCount = slots;
        this.freeSlots = new ArrayBlockingQueue<>(slots);
        for (int i = 0; i < slots; i++) {
            freeSlots.add(i);
        }
    }

    public int slots() {
        return slotCount;
    }

    public boolean isPoisoned() {
        return poisoned;
    }

    /** The process exit code once every game in flight has finished: 5 if poisoned, else 0. */
    public int exitCodeAfterDrain() {
        return poisoned ? EXIT_POISONED : 0;
    }

    // ------------------------------------------------------------------ boot

    /** Today's harness boot, once per JVM: the GUI interface Forge's model needs, the card
     *  database, a check that the Default AI profile exists, then strict mode on. */
    public static synchronized void boot() {
        if (!booted) {
            System.setProperty("java.util.Arrays.useLegacyMergeSort", "true");
            System.setProperty("sun.java2d.d3d", "false");
            // GuiDesktop's static initializer probes the screen; headless=true would make it throw.
            System.setProperty("java.awt.headless", "false");
            GuiBase.setInterface(new GuiDesktop());
            FModel.initialize(null, preferences -> {
                preferences.setPref(FPref.LOAD_CARD_SCRIPTS_LAZILY, false);
                preferences.setPref(FPref.UI_LANGUAGE, "en-US");
                return null;
            });
            if (!AiProfileUtil.getProfilesDisplayList().contains("Default")) {
                throw new IllegalStateException("AI profile 'Default' is missing from " + ForgeConstants.AI_PROFILE_DIR
                        + ": this Forge tree cannot run the simulation");
            }
            booted = true;
        }
        SimScope.setStrict(true);   // on every call: a test class may have relaxed it after itself
    }

    /** Today's HALT_ON_UNCAUGHT for a process that only plays games: anything a game thread did
     *  not catch (an OutOfMemoryError above all) is reported and the JVM halts with 4, because a
     *  shared heap's state is unknowable after one. The battery tool installs this; tests do not. */
    public static void installHaltOnUncaught() {
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            System.err.println("uncaught on " + t.getName() + ": " + e);
            e.printStackTrace(System.err);
            System.err.flush();
            Runtime.getRuntime().halt(EXIT_UNCAUGHT);
        });
    }

    // ------------------------------------------------------------------ play

    public GameResult play(GameSpec spec) throws InterruptedException {
        if (!booted) {
            throw new IllegalStateException("GameRunner.boot() first");
        }
        if (poisoned) {
            throw new PoisonedException();
        }
        if (spec.seats().size() < 2) {
            throw new IllegalArgumentException("a game needs at least two seats");
        }
        if (spec.timeoutSeconds() < 1) {
            throw new IllegalArgumentException("timeoutSeconds must be >= 1, got " + spec.timeoutSeconds());
        }
        List<RegisteredPlayer> players = registerPlayers(spec);     // refuses a bad deck or profile before a slot is taken
        int slot = freeSlots.take();
        try {
            SimScope scope = new SimScope(spec.seed());
            Body body = new Body(spec, players, scope);
            Thread t = new Thread(body, "Game-sim-" + slot);       // the "Game" prefix: ThreadUtil.isGameThread()
            t.setDaemon(true);
            long t0 = System.currentTimeMillis();
            t.start();
            t.join(TimeUnit.SECONDS.toMillis(spec.timeoutSeconds()));
            boolean timedOut = t.isAlive();
            if (timedOut) {
                scope.cancel();                                      // every draw and id request now throws GameAbandoned
                Game g = body.game;
                if (g != null) {
                    g.setGameOver(GameEndReason.Draw);               // the phase loop exits at its next check
                }
                t.join(GRACE_MS);
                if (t.isAlive()) {
                    poisoned = true;
                }
            }
            return body.result(timedOut, System.currentTimeMillis() - t0);
        } finally {
            freeSlots.put(slot);
        }
    }

    static Set<AIOption> aiOptions(String ai) {
        switch (ai) {
            case "default":    return null;
            case "hybrid_sim": return EnumSet.of(AIOption.USE_HYBRID_SIMULATION);
            case "full_sim":   return EnumSet.of(AIOption.USE_FULL_SIMULATION);
            default: throw new IllegalArgumentException("unknown ai mode " + ai + " (default|hybrid_sim|full_sim)");
        }
    }

    /** This game's own players, built from its deck files: nothing is shared across games. */
    static List<RegisteredPlayer> registerPlayers(GameSpec spec) {
        List<String> profiles = AiProfileUtil.getProfilesDisplayList();
        List<RegisteredPlayer> players = new ArrayList<>();
        for (int i = 0; i < spec.seats().size(); i++) {
            SeatSpec s = spec.seats().get(i);
            File f = s.deckFile().toFile();
            Deck d = f.exists() ? DeckSerializer.fromFile(f) : null;
            if (d == null || d.getMain().isEmpty()) {
                throw new IllegalArgumentException("could not load deck " + s.deckFile());
            }
            if (!profiles.contains(s.profile())) {
                throw new IllegalArgumentException("unknown AI profile " + s.profile() + " (available: "
                        + String.join(", ", profiles) + ")");
            }
            RegisteredPlayer rp = RegisteredPlayer.forCommander(d);
            rp.setPlayer(GamePlayerUtil.createAiPlayer("Seat" + i, 0, 0, aiOptions(s.ai()), s.profile()));
            players.add(rp);
        }
        return players;
    }

    static int seatOf(String playerName) {
        return playerName != null && playerName.startsWith("Seat") ? Integer.parseInt(playerName.substring(4)) : -1;
    }

    // ------------------------------------------------------------------ one game

    /** First seat and turn count from the game's own events, delivered on the game's own thread
     *  (the event bus is synchronous). Public because Guava's EventBus needs to see the methods. */
    public static final class Stats {
        volatile Integer firstSeat;
        volatile int turn;

        @Subscribe
        public void onStarted(GameEventGameStarted ev) {
            PlayerView first = ev.firstTurn();
            firstSeat = first == null ? null : seatOf(first.getName());
        }

        @Subscribe
        public void onTurn(GameEventTurnBegan ev) {
            turn = ev.turnNumber();
        }
    }

    private static final class Body implements Runnable {
        final GameSpec spec;
        final List<RegisteredPlayer> players;
        final SimScope scope;
        final Stats stats = new Stats();
        volatile Game game;
        volatile String error;
        volatile boolean finished;

        Body(GameSpec spec, List<RegisteredPlayer> players, SimScope scope) {
            this.spec = spec;
            this.players = players;
            this.scope = scope;
        }

        @Override
        public void run() {
            SimScope.enter(scope);
            try {
                try {
                    MyRandom.setRandom(new Random(spec.seed()));          // as the harness: the first draw is identical
                    GameRules rules = new GameRules(GameType.Commander);
                    rules.setSimTimeout(spec.timeoutSeconds());
                    Match match = new Match(rules, players, "mtgsim");
                    Game g = match.createGame();
                    // No per-decision AI time budget, as the harness: an abandoned evaluation has already
                    // drawn from the seeded stream, so the game would follow the clock instead of its seed.
                    g.AI_TIMEOUT = Integer.MAX_VALUE;
                    g.AI_CAN_USE_TIMEOUT = false;
                    g.subscribeToEvents(stats);
                    game = g;
                    match.startGame(g);
                } catch (GameAbandoned abandoned) {
                    return;                                           // cancelled from outside: the verdict is already Timeout
                } catch (OutOfMemoryError oom) {
                    throw oom;                                        // not a game result: the uncaught handler halts
                } catch (Throwable t) {
                    Throwable root = t;
                    while (root.getCause() != null && root.getCause() != root) {
                        root = root.getCause();
                    }
                    String msg = t.getClass().getName() + ": " + t.getMessage();
                    if (root != t) {
                        msg += " | cause: " + root.getClass().getName() + ": " + root.getMessage();
                    }
                    error = msg.length() > 600 ? msg.substring(0, 600) : msg;
                }
                Game g = game;
                if (g != null && !g.isGameOver()) {
                    g.setGameOver(GameEndReason.Draw);                // stops the engine after an error
                }
                finished = true;
            } finally {
                SimScope.exit();
            }
        }

        GameResult result(boolean timedOut, long ms) {
            Game g = game;
            if (timedOut || !finished || g == null) {
                // Live state may still be moving (a thread past its grace) or may never have existed:
                // read nothing live, as the harness does after a timeout.
                String reason = timedOut ? "Timeout" : "Error";
                String err = timedOut ? null : (error != null ? error : "game ended without finishing");
                List<String> lines = List.of(outcomeLine(null, reason, stats.turn, stats.firstSeat, err));
                return new GameResult(spec.seed(), null, reason, stats.turn, stats.firstSeat, timedOut, err, ms,
                        reason.toUpperCase(), lines);
            }
            GameOutcome out = g.getOutcome();
            Integer winner = null;
            String reason;
            String err = error;
            if (err != null) {
                reason = "Error";
            } else if (out == null) {
                reason = "Error";
                err = "game ended without an outcome";
            } else if (out.isDraw()) {
                reason = "Draw";
            } else {
                int w = players.indexOf(out.getWinningPlayer());
                if (w < 0) {
                    reason = "Error";
                    err = "winner is not one of the seats";
                } else {
                    winner = w;
                    reason = out.getWinCondition() == GameEndReason.WinsGameSpellEffect ? "WinsGameSpellEffect" : "AllOpponentsLost";
                }
            }
            int turns = out != null ? out.getLastTurnNumber() : stats.turn;
            List<String> lines = digestLines(g, players.size(), winner, reason, turns, stats.firstSeat, err);
            return new GameResult(spec.seed(), winner, reason, turns, stats.firstSeat, false, err, ms, sha256(lines), lines);
        }
    }

    // ------------------------------------------------------------------ digest (spec 7.3)

    static String outcomeLine(Integer winner, String reason, int turns, Integer firstSeat, String error) {
        return "outcome\t" + (winner == null ? -1 : winner) + "\t" + reason + "\t" + turns + "\t"
                + (firstSeat == null ? -1 : firstSeat) + "\t" + (error == null ? "" : error);
    }

    static List<String> digestLines(Game g, int nSeats, Integer winner, String reason, int turns, Integer firstSeat,
                                    String error) {
        List<String> lines = new ArrayList<>();
        lines.add(outcomeLine(winner, reason, turns, firstSeat, error));
        for (GameLogEntry e : g.getGameLog().getAllEntries()) {
            if (isElapsedTime(e)) {
                continue;
            }
            lines.add("log\t" + e.type().name() + "\t" + e.message());
        }
        Player[] bySeat = new Player[nSeats];
        for (Player p : g.getRegisteredPlayers()) {
            int seat = seatOf(p.getName());
            if (seat >= 0 && seat < nSeats) {
                bySeat[seat] = p;
            }
        }
        for (int seat = 0; seat < nSeats; seat++) {
            Player p = bySeat[seat];
            for (ZoneType zt : DIGEST_ZONES) {
                StringBuilder sb = new StringBuilder("zone\t").append(seat).append('\t').append(zt.name()).append('\t');
                if (p != null) {
                    boolean first = true;
                    for (Card c : p.getCardsIn(zt)) {
                        if (!first) {
                            sb.append('|');
                        }
                        sb.append(c.getName());
                        first = false;
                    }
                }
                lines.add(sb.toString());
            }
        }
        return lines;
    }

    /** GameLogFormatter writes the match's wall-clock duration into the log at game end; spec 7.3 excludes elapsed time. */
    static boolean isElapsedTime(GameLogEntry e) {
        return e.type() == GameLogEntryType.GAME_OUTCOME
                && e.message().startsWith(Localizer.getInstance().getMessage("lblMatchDuration"));
    }

    static String sha256(List<String> lines) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            for (String line : lines) {
                md.update(line.getBytes(StandardCharsets.UTF_8));
                md.update((byte) '\n');
            }
            StringBuilder hex = new StringBuilder();
            for (byte b : md.digest()) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
