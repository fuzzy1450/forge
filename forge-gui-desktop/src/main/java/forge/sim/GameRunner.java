package forge.sim;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import com.google.common.eventbus.Subscribe;

import forge.GuiDesktop;
import forge.ai.AIOption;
import forge.ai.AiProfileUtil;
import forge.card.CardEdition;
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
import forge.token.TokenDb;
import forge.util.GameAbandoned;
import forge.util.Localizer;
import forge.util.MyRandom;
import forge.util.SimScope;

/**
 * Plays Commander games in THIS JVM, several at once, each on its own thread under its own
 * {@link SimScope}. {@link #boot()} once, then {@link #play} as often as wanted; at most
 * {@code slots} games run at a time. Design: MTG_DeckMaker's
 * docs/superpowers/specs/2026-10-05-sim-scope-design.md, sections 5 and 6. Not final only so that
 * DeterminismBatteryTest can poison a runner on a chosen play.
 */
public class GameRunner {

    public record SeatSpec(Path deckFile, String profile, String ai) { }

    public record GameSpec(long seed, int timeoutSeconds, List<SeatSpec> seats) { }

    /** {@code violations}: the unbound accesses strict mode refused anywhere in this process while the game played
     *  ({@link SimScope#VIOLATIONS}), 0 normally. A refusal happens on a thread bound to no game, so a game that played
     *  beside another can carry that one's count too. Never part of the digest. */
    public record GameResult(long seed, Integer winnerSeat, String endReason, int turns, Integer firstSeat,
                             boolean timedOut, String error, long ms, String digest, List<String> digestLines,
                             long violations) { }

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

    // Encoded at class load: halt() can run after an OutOfMemoryError, and a line built at that moment can be the
    // allocation that fails again (as sim/java/mtgsim/Harness.java).
    private static final FileOutputStream RAW_ERR = new FileOutputStream(FileDescriptor.err);
    private static final byte[] UNCAUGHT_LINE =
            ("GameRunner: uncaught throwable; halting with exit " + EXIT_UNCAUGHT + "\n").getBytes(StandardCharsets.UTF_8);
    private static final byte[] OOM_LINE =
            ("GameRunner: java.lang.OutOfMemoryError; halting with exit " + EXIT_UNCAUGHT + "\n").getBytes(StandardCharsets.UTF_8);

    /** What {@link #installHaltOnUncaught} installs: report the throwable and halt with {@link #EXIT_UNCAUGHT}. */
    public static final Thread.UncaughtExceptionHandler HALT_ON_UNCAUGHT = GameRunner::halt;

    /** True once {@link #installHaltOnUncaught} has run, so that {@link #boot} re-asserts the handler. Package-private for the test. */
    static volatile boolean haltInstalled;

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

    /** Test seam (DeterminismBatteryTest): marks this runner poisoned, as a game thread that outlived its grace
     *  period would, so that {@link #play} refuses every game from now on. Never called outside the tests. */
    void poisonForTest() {
        poisoned = true;
    }

    /** The process exit code once every game in flight has finished: 5 if poisoned, else 0. */
    public int exitCodeAfterDrain() {
        return poisoned ? EXIT_POISONED : 0;
    }

    // ------------------------------------------------------------------ boot

    /** Today's harness boot: the GUI interface Forge's model needs, the card database, every edition's tokens and a
     *  check that the Default AI profile exists, once per JVM. On every call it re-arms strict mode and, if
     *  {@link #installHaltOnUncaught} was asked for, the halt handler. */
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
            preloadTokens(FModel.getMagicDb().getAllTokens(), FModel.getMagicDb().getEditions());
            if (!AiProfileUtil.getProfilesDisplayList().contains("Default")) {
                throw new IllegalStateException("AI profile 'Default' is missing from " + ForgeConstants.AI_PROFILE_DIR
                        + ": this Forge tree cannot run the simulation");
            }
            booted = true;
        }
        SimScope.setStrict(true);   // on every call: a test class may have relaxed it after itself
        if (haltInstalled) {
            // Forge's boot may install its own default handler (a modal Bug Report dialog nobody answers):
            // the runner's wins whenever it was asked for.
            Thread.setDefaultUncaughtExceptionHandler(HALT_ON_UNCAUGHT);
        }
    }

    /** Loads every token an edition lists, once, at boot: TokenDb otherwise fills its multimap from whichever thread
     *  asks first, and several game threads ask at once. TokenDb.preloadTokens() (the GUI token viewer's) would do it
     *  but aborts on the first entry naming a token that has no script (FRC lists the card Gingerbrute among its
     *  tokens), so each listed token is asked for instead, which loads every art of it: TokenDb rejects such an entry
     *  before it writes anything, and a game that asks for it fails as it always did. Returns the entries skipped. */
    static int preloadTokens(TokenDb tokens, Iterable<CardEdition> editions) {
        int skipped = 0;
        for (CardEdition edition : editions) {
            for (String token : edition.getTokens().keySet()) {
                try {
                    tokens.getToken(token, edition.getCode());
                } catch (RuntimeException noScript) {
                    skipped++;
                }
            }
        }
        return skipped;
    }

    /** Today's HALT_ON_UNCAUGHT for a process that only plays games: anything a game thread did
     *  not catch (an OutOfMemoryError above all) is reported and the JVM halts with 4, because a
     *  shared heap's state is unknowable after one. The battery tool installs this, before or after
     *  {@link #boot}: boot re-asserts it. */
    public static void installHaltOnUncaught() {
        try {
            // Forge's forge.error.ExceptionHandler installs itself as the JVM's default handler from its static
            // initializer. Loading it first uses that up, so nothing later can install Forge's handler over ours.
            Class.forName("forge.error.ExceptionHandler", true, GameRunner.class.getClassLoader());
        } catch (ClassNotFoundException | LinkageError absent) {
            // no Forge handler to pre-empt
        }
        Thread.setDefaultUncaughtExceptionHandler(HALT_ON_UNCAUGHT);
        Thread.currentThread().setUncaughtExceptionHandler(HALT_ON_UNCAUGHT);
        haltInstalled = true;
    }

    /** Reports a throwable that reached no catch and halts with {@link #EXIT_UNCAUGHT}. The first write is a line
     *  encoded at class load, so it still goes out when the heap is gone; the thread and the stack trace come after
     *  it, as best effort. Runtime.halt rather than System.exit, so no shutdown hook can hold the JVM open. */
    static void halt(Thread t, Throwable e) {
        try {
            RAW_ERR.write(e instanceof OutOfMemoryError ? OOM_LINE : UNCAUGHT_LINE);
            System.err.println("in thread \"" + t.getName() + "\":");
            e.printStackTrace(System.err);
            System.err.flush();
        } catch (Throwable reportFailed) {
            // the fixed line is the part that has to get out
        } finally {
            Runtime.getRuntime().halt(EXIT_UNCAUGHT);
        }
    }

    // ------------------------------------------------------------------ play

    /** Plays one game and returns its result. {@code observer} runs on the game thread, under the game's scope, after
     *  the game is created and before it starts -- where a stats subscriber attaches. It must only listen: it runs
     *  under that scope, so a draw or an id request it made would become part of the seeded game and its digest. It is
     *  never called when {@code createGame()} fails or a timeout's cancellation ends the game during setup, so a
     *  subscriber may never have attached. A throwable from it ends the game as an Error like any other. An interrupt
     *  of the calling thread while it waits is a cancellation request: the game is cancelled as on a timeout, its slot
     *  is returned, and InterruptedException is thrown with the interrupt status left set. */
    public GameResult play(GameSpec spec, Consumer<Game> observer) throws InterruptedException {
        if (!booted) {
            throw new IllegalStateException("GameRunner.boot() first");
        }
        if (poisoned) {
            throw new PoisonedException();
        }
        Objects.requireNonNull(observer, "observer");
        List<RegisteredPlayer> players = validate(spec);            // refuses a bad spec, deck, profile or AI before a slot is taken
        int slot = freeSlots.take();
        try {
            if (poisoned) {                                          // another game can poison the runner while this one waits for a slot
                throw new PoisonedException();
            }
            SimScope scope = new SimScope(spec.seed());
            Body body = new Body(spec, players, scope, observer);
            Thread t = new Thread(body, "Game-sim-" + slot);       // the "Game" prefix: ThreadUtil.isGameThread()
            t.setDaemon(true);
            long violationsAtStart = SimScope.VIOLATIONS.get();
            long t0 = System.currentTimeMillis();
            t.start();
            boolean interrupted = false;
            try {
                t.join(TimeUnit.SECONDS.toMillis(spec.timeoutSeconds()));
            } catch (InterruptedException ie) {
                interrupted = true;                                  // a cancellation request: cancel the game below, then report it
            }
            boolean timedOut = t.isAlive();
            if (timedOut) {
                scope.cancel();                                      // every draw and id request now throws GameAbandoned
                Game g = body.game;
                if (g != null) {
                    try {
                        g.setGameOver(GameEndReason.Draw);           // the phase loop exits at its next check
                    } catch (RuntimeException ignored) {
                        // the engine is still moving under us: the grace join and the poison check below must run regardless
                    }
                }
                joinUninterruptibly(t, GRACE_MS);
                if (t.isAlive()) {
                    poisoned = true;
                }
            }
            if (interrupted) {
                Thread.currentThread().interrupt();
                throw new InterruptedException("interrupted while waiting for the game with seed " + spec.seed());
            }
            return body.result(timedOut, System.currentTimeMillis() - t0, SimScope.VIOLATIONS.get() - violationsAtStart);
        } finally {
            freeSlots.add(slot);                                     // capacity is guaranteed; unlike put, add cannot throw on a pending interrupt
        }
    }

    /** Plays one game and returns its result: {@link #play(GameSpec, Consumer)} with no observer. */
    public GameResult play(GameSpec spec) throws InterruptedException {
        return play(spec, g -> { });
    }

    /** Joins for at most {@code ms} whatever the caller's interrupt status: an interrupt is remembered, not acted on,
     *  and the caller's interrupt flag is set again before returning. */
    private static void joinUninterruptibly(Thread t, long ms) {
        boolean interrupted = false;
        try {
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(ms);
            while (t.isAlive()) {
                long remaining = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
                if (remaining <= 0) {
                    return;
                }
                try {
                    t.join(remaining);
                } catch (InterruptedException ie) {
                    interrupted = true;
                }
            }
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
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

    /** The checks {@link #play} makes on a spec before it takes a slot, returning the game's players: at least two seats,
     *  a timeout of at least one second, and every seat's deck, AI profile and AI mode loaded by
     *  {@link #registerPlayers}. Serve makes the same checks on a job before it accepts it. Static, so it reads and
     *  changes no runner's state; an IllegalArgumentException names what is wrong. Before boot it refuses every spec
     *  as play does: registerPlayers would fail on Forge's unloaded profiles with a bare NullPointerException. */
    static List<RegisteredPlayer> validate(GameSpec spec) {
        if (!booted) {
            throw new IllegalStateException("GameRunner.boot() first");
        }
        if (spec.seats().size() < 2) {
            throw new IllegalArgumentException("a game needs at least two seats");
        }
        if (spec.timeoutSeconds() < 1) {
            throw new IllegalArgumentException("timeoutSeconds must be >= 1, got " + spec.timeoutSeconds());
        }
        return registerPlayers(spec);
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
        final Consumer<Game> observer;
        final Stats stats = new Stats();
        volatile Game game;
        volatile String error;
        volatile boolean finished;

        Body(GameSpec spec, List<RegisteredPlayer> players, SimScope scope, Consumer<Game> observer) {
            this.spec = spec;
            this.players = players;
            this.scope = scope;
            this.observer = observer;
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
                    observer.accept(g);                               // the records' subscriber attaches here (harness-in-engine spec 3.2)
                    match.startGame(g);
                } catch (GameAbandoned abandoned) {
                    return;                                           // cancelled from outside: the verdict is already Timeout
                } catch (OutOfMemoryError oom) {
                    throw oom;                                        // not a game result: the uncaught handler halts
                } catch (Throwable t) {
                    Throwable root = t;
                    for (int hops = 0; hops < 16 && root.getCause() != null && root.getCause() != root; hops++) {
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

        GameResult result(boolean timedOut, long ms, long violations) {
            Game g = game;
            if (timedOut || !finished || g == null) {
                // Live state may still be moving (a thread past its grace) or may never have existed:
                // read nothing live, as the harness does after a timeout.
                String reason = timedOut ? "Timeout" : "Error";
                String err = timedOut ? null : (error != null ? error : "game ended without finishing");
                List<String> lines = List.of(outcomeLine(null, reason, stats.turn, stats.firstSeat, err));
                return new GameResult(spec.seed(), null, reason, stats.turn, stats.firstSeat, timedOut, err, ms,
                        timedOut ? "TIMEOUT" : "ERROR", lines, violations);
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
            return new GameResult(spec.seed(), winner, reason, turns, stats.firstSeat, false, err, ms, sha256(lines), lines,
                    violations);
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
                    for (Card c : p.getCardsIn(zt, false)) {          // false: phased-out permanents are in the zone too (spec 7.3)
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
