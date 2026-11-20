package forge.game;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

import forge.game.card.Card;
import forge.game.player.Player;
import forge.util.SimScope;

/**
 * Print-only, off unless {@code -Dflatmc.arrivalprobe=<file>} is set: the game copier's arrival probe, for the checks
 * of MTG_DeckMaker's copier-turninzone fix (docs/superpowers/specs/2026-11-20-flatmc-copier-turninzone-design.md,
 * section 6). Nothing in a game reads anything it writes, and it only reads: it draws no random value and takes no id,
 * and a failure inside it is swallowed, never thrown into the game.
 *
 * <p>The old copier added every copied card to its zone with {@code Zone.add}, which stamps the copy's turn, and so
 * counted a descend for each permanent card it put in a graveyard. The fixed copier writes the turn each card really
 * entered its zone, which is never above the copy's first turn, and gives each copy player the original's count back.
 * Every other value in a copy is one both copiers stamp alike: a card the copy moves is stamped with the copy's
 * current turn either way. So a card of a copy whose {@code turnInZone} is below the copy's first turn carries a value
 * the old copier would have written as that turn, and only such a card does (its last-known copies and a copy of the
 * copy inherit the value, and the rule holds for each in its own copy). This probe marks the reads of such a value
 * whose outcome can differ, and the reads of a copy player's descend count while the old copier's would differ:
 * <ul>
 * <li>{@code enteredThisTurn()} reads differently only while the copy is in its first turn;</li>
 * <li>a direct {@code getTurnInZone()} read is named from the stack: a last-known copy ({@code CardCopyService}) and
 * the copier only pass the value on and are skipped, the upkeep's reset ({@code PhaseHandler}) reads differently only
 * in the copy's first turn, and every other reader is marked;</li>
 * <li>{@code getDescended()} while the copy player's difference stands, but the copier's own reads.</li>
 * </ul>
 * Once per game (its scope: a JVM can play one seed twice), keyed in the lines by the scope's seed, it appends at most:
 * <ul>
 * <li>{@code D* seed=S turn=T phase=P reader=R card=ID:NAME} (or {@code player=ID}), once a game, at its first marked
 * read;</li>
 * <li>{@code READ seed=S turn=T phase=P reader=R card=ID:NAME}, once per game, reader and card;</li>
 * <li>{@code DESCEND seed=S turn=T phase=P reader=R player=ID offset=N}, once per game and player.</li>
 * </ul>
 * A reader is the first stack frame outside the probe and the card (or player): {@code class.method:line}. With no
 * scope bound (the GUI) it prints nothing.
 *
 * <p>It lives in forge-game, not beside the copier in forge-ai: {@code Card} and {@code Player} call it. A copy is
 * known by its {@code Game} (identity), and a copy player by its game and id: {@code GameEntity.equals} takes a copy
 * player for its original.
 */
public final class ArrivalProbe {
    private static final String PROPERTY = "flatmc.arrivalprobe";

    /** Set once from the property; the hooks read it plainly. Tests swap it on their own thread. */
    private static Consumer<String> sink = fileSink(System.getProperty(PROPERTY));

    private static final class Copy {
        final int firstTurn;
        /** Player id: what the old copier would have counted, less this copy's count. */
        final Map<Integer, Integer> descend = new ConcurrentHashMap<>();

        Copy(final int firstTurn) {
            this.firstTurn = firstTurn;
        }
    }

    /** One game's lines so far, keyed by the game's scope: a JVM can play one seed twice (a walk pair, a replay). */
    private static final class Lines {
        boolean dstar;
        final Set<String> reads = new HashSet<>();
        final Set<Integer> descends = new HashSet<>();
    }

    private static final Map<Game, Copy> COPIES = Collections.synchronizedMap(new WeakHashMap<>());
    private static final Map<SimScope, Lines> LINES = Collections.synchronizedMap(new WeakHashMap<>());
    private static final StackWalker WALKER = StackWalker.getInstance();

    private static final String PROBE = ArrivalProbe.class.getName();
    private static final String CARD = "forge.game.card.Card";
    private static final String PLAYER = "forge.game.player.Player";
    private static final String CARD_COPY_SERVICE = "forge.game.card.CardCopyService";
    private static final String GAME_COPIER = "forge.ai.simulation.GameCopier";
    private static final String PHASE_HANDLER = "forge.game.phase.PhaseHandler";

    private ArrivalProbe() {
    }

    /** The hooks' one check: whether the probe is on. */
    public static boolean watching() {
        return sink != null;
    }

    /** The copier made {@code copy}, whose turn it set to {@code firstTurn}: called before any card is added. */
    public static void copyMade(final Game copy, final int firstTurn) {
        if (sink == null) {
            return;
        }
        try {
            COPIES.put(copy, new Copy(firstTurn));
        } catch (final RuntimeException e) {
            // print-only: a probe must never break a copy
        }
    }

    /**
     * The copier gave {@code copyPlayer} the original's count back: {@code oldCopierCount} is what it held just
     * before, the old copier's count; {@code origCount} the original's. A copy of a copy adds its parent's standing
     * difference, which the old copier's parent had counted already.
     */
    public static void descendCopied(final Player copyPlayer, final Player origPlayer, final int oldCopierCount,
                                     final int origCount) {
        if (sink == null) {
            return;
        }
        try {
            final Copy copy = COPIES.get(copyPlayer.getGame());
            if (copy == null) {
                return;
            }
            int difference = oldCopierCount - origCount;
            final Copy parent = COPIES.get(origPlayer.getGame());
            if (parent != null) {
                difference += parent.descend.getOrDefault(origPlayer.getId(), 0);
            }
            if (difference != 0) {
                copy.descend.put(copyPlayer.getId(), difference);
            } else {
                copy.descend.remove(copyPlayer.getId());
            }
        } catch (final RuntimeException e) {
            // print-only
        }
    }

    /** From {@code Card.getTurnInZone()}. */
    public static void read(final Card c, final int turnInZone) {
        if (sink == null) {
            return;
        }
        try {
            final Game game = c.getGame();
            final Copy copy = game == null ? null : COPIES.get(game);
            if (copy == null || turnInZone >= copy.firstTurn) {
                return;
            }
            final StackWalker.StackFrame frame = callerOutside(CARD);
            if (frame == null) {
                return;
            }
            final String cls = frame.getClassName();
            if (CARD_COPY_SERVICE.equals(cls) || GAME_COPIER.equals(cls)) {
                return; // passes the value on: checked again when it is read from the new card
            }
            if (PHASE_HANDLER.equals(cls) && game.getPhaseHandler().getTurn() != copy.firstTurn) {
                return; // turnInZone < turn: the same either way after the copy's first turn
            }
            markCard(c, game, reader(frame));
        } catch (final RuntimeException e) {
            // print-only
        }
    }

    /** From {@code Card.enteredThisTurn()}: {@code turnInZone == now} reads differently only in the copy's first turn. */
    public static void entered(final Card c, final int turnInZone, final int now) {
        if (sink == null) {
            return;
        }
        try {
            final Game game = c.getGame();
            final Copy copy = game == null ? null : COPIES.get(game);
            if (copy == null || turnInZone >= copy.firstTurn || now != copy.firstTurn) {
                return;
            }
            final StackWalker.StackFrame frame = callerOutside(CARD);
            markCard(c, game, frame == null ? "?" : reader(frame));
        } catch (final RuntimeException e) {
            // print-only
        }
    }

    /** From {@code Player.getDescended()}. */
    public static void descendRead(final Player p) {
        if (sink == null) {
            return;
        }
        try {
            final Game game = p.getGame();
            final Copy copy = game == null ? null : COPIES.get(game);
            if (copy == null) {
                return;
            }
            final Integer difference = copy.descend.get(p.getId());
            if (difference == null || difference == 0) {
                return;
            }
            final StackWalker.StackFrame frame = callerOutside(PLAYER);
            if (frame == null || GAME_COPIER.equals(frame.getClassName())) {
                return; // the copier's own reads pass the count on
            }
            final SimScope scope = SimScope.current();
            final Consumer<String> out = sink;
            if (scope == null || out == null) {
                return;
            }
            final String where = where(scope.seed(), game);
            final String reader = reader(frame);
            final Lines seen = LINES.computeIfAbsent(scope, k -> new Lines());
            synchronized (seen) {
                if (!seen.dstar) {
                    seen.dstar = true;
                    out.accept("D* " + where + " reader=" + reader + " player=" + p.getId());
                }
                if (seen.descends.add(p.getId())) {
                    out.accept("DESCEND " + where + " reader=" + reader + " player=" + p.getId() + " offset="
                            + difference);
                }
            }
        } catch (final RuntimeException e) {
            // print-only
        }
    }

    /** From {@code Player.setDescended()}: the copy's count is set (its cleanup's reset); the difference is gone. */
    public static void descendSet(final Player p) {
        if (sink == null) {
            return;
        }
        try {
            final Game game = p.getGame();
            final Copy copy = game == null ? null : COPIES.get(game);
            if (copy != null) {
                copy.descend.remove(p.getId());
            }
        } catch (final RuntimeException e) {
            // print-only
        }
    }

    /** Tests only: puts {@code next} in the sink's place (null turns the probe off) and returns what was there. */
    public static Consumer<String> swapSink(final Consumer<String> next) {
        final Consumer<String> before = sink;
        sink = next;
        return before;
    }

    private static void markCard(final Card c, final Game game, final String reader) {
        final SimScope scope = SimScope.current();
        if (scope == null) {
            return;
        }
        final String where = where(scope.seed(), game);
        final String card = c.getId() + ":" + c.getName();
        final Consumer<String> out = sink;
        if (out == null) {
            return;
        }
        final Lines seen = LINES.computeIfAbsent(scope, k -> new Lines());
        synchronized (seen) {
            if (!seen.dstar) {
                seen.dstar = true;
                out.accept("D* " + where + " reader=" + reader + " card=" + card);
            }
            if (seen.reads.add(reader + "|" + c.getId())) {
                out.accept("READ " + where + " reader=" + reader + " card=" + card);
            }
        }
    }

    private static String where(final long seed, final Game game) {
        return "seed=" + seed + " turn=" + game.getPhaseHandler().getTurn() + " phase="
                + game.getPhaseHandler().getPhase();
    }

    private static StackWalker.StackFrame callerOutside(final String owner) {
        return WALKER.walk(frames -> frames
                .filter(f -> !PROBE.equals(f.getClassName()) && !owner.equals(f.getClassName()))
                .findFirst()
                .orElse(null));
    }

    private static String reader(final StackWalker.StackFrame frame) {
        return frame.getClassName() + "." + frame.getMethodName() + ":" + frame.getLineNumber();
    }

    private static Consumer<String> fileSink(final String path) {
        if (path == null || path.isEmpty()) {
            return null;
        }
        return line -> {
            synchronized (ArrivalProbe.class) {
                try (Writer w = Files.newBufferedWriter(Paths.get(path), StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
                    w.write(line);
                    w.write('\n');
                } catch (final IOException e) {
                    // print-only: a probe must never break a copy
                }
            }
        };
    }
}
