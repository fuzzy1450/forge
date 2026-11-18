package forge.ai.simulation;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import forge.game.Game;
import forge.game.cost.Cost;
import forge.game.phase.PhaseHandler;
import forge.game.player.Player;
import forge.game.player.RegisteredPlayer;
import forge.game.spellability.SpellAbility;
import forge.game.spellability.SpellAbilityStackInstance;
import forge.util.AITimeoutTracker;
import forge.util.SimScope;

/**
 * The self-kill fix's print-only trace (MTG_DeckMaker's self-kill fix, DESIGN.md 6.4, RULINGS-R3 sections C and F).
 * With {@code -Dselfkill.trace=<file>} it appends one line to that file for each judgment at the fix's sites that reads
 * a delayed-loss play or finds a pay-or-lose debt, allowed or refused (S1, S3, S3b, S4, S5, S5b, S6b and RULINGS-R3
 * M19's solver step), for each delayed-loss move a solver search leaves out (S2skip), for each payment of the AI's own
 * Pact debt (pay, S6) and for each veto (S4's refusal). A game whose decks list none of the eleven cards writes no
 * line.
 * <p>
 * <b>The gate.</b> {@link #ON} is read once, when the class loads, from the property: unset, every site's one test of
 * it fails and no trace code runs, so the game is the shipped engine's. Set, the trace is built the first time a site
 * reaches it, and the file is opened, in append mode, on the first line written; each line is written whole under one
 * lock, UTF-8, and flushed; nothing goes to stdout or stderr, and a file that cannot be written is written no more.
 * Whatever the properties, the trace only prints: it takes no SimScope id, draws no number, writes no game field and
 * changes no order. Its own reads (the stack scan for a winner's trigger, the threat-free judgment, a marker's turn and
 * phase, the seats' deck names) are pure (DESIGN.md 1.10); the held sets' sizes are AI memory, read by the payment's
 * site. Each site asks the same judgment with the same reads either way: the trace hands DelayedLoss a {@code Seen},
 * which notes what the judgment read and changes none of it.
 * <p>
 * <b>A line</b> is tab-separated {@code key=value} fields, in this order (RULINGS-R3 F):
 * <ul>
 *   <li>{@code seed}: the bound SimScope's seed, {@code -} with none bound; {@code players}: each seat's deck name in
 *       seat order, joined by {@code |} (a seat whose deck cannot be read, or has no name, gives its player's name);
 *       {@code turn} and {@code phase}: the game's, {@code phase=copy} for a line from a simulation copy;</li>
 *   <li>{@code site}: {@code S1}, {@code S2skip}, {@code S3}, {@code S3b}, {@code S4} (the final veto letting the play
 *       through), {@code veto} (the final veto refusing it), {@code S5}, {@code S5b}, {@code M19}, {@code pay} (S6, the
 *       AI paying its own Pact debt) or {@code S6b};</li>
 *   <li>{@code verdict}: {@code ok}, or the refusal's code; a free cast S5 or S5b declines says {@code declined}, a
 *       solver skip {@code skipped}; a judgment that threw says the code the site's catch answered;</li>
 *   <li>{@code card}: the judged play's host card;</li>
 *   <li>{@code debts}: the debts the judgment's reader found, {@code -} for none, else each as
 *       {@code DUE:TOTAL[:PIP+PIP...]} ({@code NEXT:5:U+U}), the next upkeep's first, joined by {@code ,};</li>
 *   <li>{@code units}: each count the judgment made, {@code -} for none, else each as
 *       {@code DUE(life=L,threat=T):N{SOURCE;...}}, joined by a space: N units, each counted source as
 *       {@code name=unit+unit} with {@code ~pain} for a painful one ({@code Ancient Tomb=C+C~2}), the land drop as
 *       {@code drop=unit}, a unit's colors as letters ({@code WUBRG}, {@code C});</li>
 *   <li>then, only where they apply, in this order: {@code unread=yes} (the judgment threw and the site's catch
 *       answered), {@code held=NEXT_SPELL,MAIN2,DECLBLK,ENEMY_DECLBLK} and {@code paid=yes|no} (a pay line: the held
 *       sets' sizes before S6 cleared them, and whether the debt was paid), {@code heard=stock|tally|none} (a veto
 *       line: whether the loop driver's noteStockPlay or Flat MC's tally of the decision had heard the play already),
 *       {@code winner=<card>} (a refusal made while a trigger of the AI's whose host DreadReturnWin.isWinner names is
 *       on the stack), {@code threat=threat-only} (a refusal the same judgment with no threat term would allow);</li>
 *   <li>last, {@code copy_judged} and {@code copy_refused}: the game's running counts of the judgments made inside its
 *       simulation copies, kept in its SimScope's scratch (per thread when no scope is bound), never per JVM.</li>
 * </ul>
 * A judgment inside a simulation copy is counted and printed only with {@code -Dselfkill.trace.copies=true}. With
 * {@code -Dselfkill.trace.markers=true} each real-game priority decision also opens with a marker,
 * {@code decision<TAB>turn<TAB>phase}, followed by its seed and players fields.
 * <p>
 * <b>The seam.</b> The sites reach the trace only through the gate, by the static methods here; those forward to the
 * one trace the property names. DelayedLossTraceTest builds its own instances, with a file and flags of its own, and
 * drives them through the package-private methods the static ones forward to.
 */
public final class DelayedLossTrace {
    /** The file the trace appends to. */
    public static final String PROPERTY = "selfkill.trace";
    /** "true": the judgments inside simulation copies are printed too, with phase=copy. */
    public static final String COPIES_PROPERTY = "selfkill.trace.copies";
    /** "true": every real-game priority decision opens with a marker line. */
    public static final String MARKERS_PROPERTY = "selfkill.trace.markers";

    /** The property's value, read once when the class loads. */
    private static final String FILE = System.getProperty(PROPERTY, "");
    /** The gate: whether the property names a file. Every site tests it before it reaches the trace. */
    public static final boolean ON = !FILE.isEmpty();

    /** Site words (RULINGS-R3 F, amended 06:28). */
    public static final String S1 = "S1";
    public static final String S2SKIP = "S2skip";
    public static final String S3 = "S3";
    public static final String S3B = "S3b";
    public static final String S4 = "S4";
    public static final String VETO = "veto";
    public static final String S5 = "S5";
    public static final String S5B = "S5b";
    public static final String M19 = "M19";
    public static final String PAY = "pay";
    public static final String S6B = "S6b";

    /** Whether a veto's play was heard before it (DESIGN.md S4, finding I7). */
    public static final String HEARD_STOCK = "stock";
    public static final String HEARD_TALLY = "tally";
    public static final String HEARD_NONE = "none";

    static final String OK = "ok";
    static final String DECLINED = "declined";
    static final String SKIPPED = "skipped";

    /** Whether the trace the property names was built (with the property unset, never). */
    private static volatile boolean started;

    /** The trace the property names, built the first time a site reaches it. */
    private static final class Live {
        static final DelayedLossTrace TRACE = start();

        private static DelayedLossTrace start() {
            started = true;
            Path file;
            try {
                file = Paths.get(FILE);
            } catch (final RuntimeException e) {
                file = null; // a name no path can be: nothing is written
            }
            return new DelayedLossTrace(file, Boolean.getBoolean(COPIES_PROPERTY),
                    Boolean.getBoolean(MARKERS_PROPERTY));
        }
    }

    static boolean started() {
        return started;
    }

    // ------------------------------------------------------------------------------- the sites' entry points (gated)

    /**
     * S1, S3, S3b and M19: {@link DelayedLoss#problem}, with the sites' catches (DESIGN.md 1.11:
     * AITimeoutTracker.Expired rethrown, an unreadable play answered by {@link DelayedLoss#refusalWhenUnread}), traced.
     * Only behind {@link #ON}.
     */
    public static String problem(final String site, final Player ai, final SpellAbility sa,
                                 final Supplier<ComboPlan> plan) {
        return Live.TRACE.judgePlay(site, ai, sa, plan, null);
    }

    /** S4: the final veto's judgment, as {@link #problem}; a refusal is traced as a veto with {@code heard}. */
    public static String veto(final Player ai, final SpellAbility sa, final Supplier<ComboPlan> plan,
                              final String heard) {
        return Live.TRACE.judgePlay(S4, ai, sa, plan, heard);
    }

    /** S5 and S5b: {@link DelayedLoss#declinesFreeCast}, with the sites' catches, traced. Only behind {@link #ON}. */
    public static boolean declinesFreeCast(final String site, final Player ai, final SpellAbility sa) {
        return Live.TRACE.judgeFreeCast(site, ai, sa);
    }

    /**
     * S6b: {@link DelayedLoss#declinesUnlessPayment}, traced; a judgment that throws lets the payment go on, as S6b's
     * catch does. Only behind {@link #ON}.
     */
    public static boolean declinesUnlessPayment(final Player ai, final SpellAbility sa, final Cost cost) {
        return Live.TRACE.judgeUnlessPayment(ai, sa, cost);
    }

    /** S6: the AI paid, or did not, its own Pact debt; {@code held}: the held sets' sizes before S6 cleared them. */
    public static void paid(final Player ai, final SpellAbility sa, final int[] held, final boolean paid) {
        Live.TRACE.pay(ai, sa, held, paid);
    }

    /** S2: a solver search left this delayed-loss move out; {@code ai} is the real game's player, the search's root. */
    public static void skipped(final Player ai, final SpellAbility move) {
        Live.TRACE.skip(ai, move);
    }

    /** The top of a priority decision: a marker, for a real game, with the markers on. */
    public static void decision(final Player ai) {
        Live.TRACE.marker(ai);
    }

    // ------------------------------------------------------------------------------------------------- an instance

    private final Path file;
    private final boolean copies;
    private final boolean markers;
    private final Object lock = new Object();
    private Writer out;
    private boolean broken;
    /** The copy counts of a thread with no SimScope bound (the GUI's): one per thread, never one per JVM. */
    private final ThreadLocal<Counts> unbound = ThreadLocal.withInitial(Counts::new);

    /** A trace appending to {@code file}: DelayedLossTraceTest's seam. The sites reach only the one the gate names. */
    DelayedLossTrace(final Path file, final boolean copies, final boolean markers) {
        this.file = file;
        this.copies = copies;
        this.markers = markers;
    }

    String judgePlay(final String site, final Player ai, final SpellAbility sa, final Supplier<ComboPlan> plan,
                     final String heard) {
        final DelayedLoss.Seen seen = new DelayedLoss.Seen(false);
        String verdict;
        boolean unread = false;
        try {
            verdict = DelayedLoss.problem(ai, sa, plan, seen);
        } catch (final AITimeoutTracker.Expired e) {
            throw e;
        } catch (final RuntimeException e) {
            verdict = DelayedLoss.refusalWhenUnread(sa);
            unread = true;
        }
        final boolean veto = S4.equals(site) && verdict != null;
        judged(veto ? VETO : site, ai, sa, verdict == null ? OK : verdict, verdict != null, unread, seen,
                veto ? heard : null, () -> DelayedLoss.problem(ai, sa, plan, new DelayedLoss.Seen(true)) == null);
        return verdict;
    }

    boolean judgeFreeCast(final String site, final Player ai, final SpellAbility sa) {
        final DelayedLoss.Seen seen = new DelayedLoss.Seen(false);
        boolean declined;
        String verdict;
        boolean unread = false;
        try {
            declined = DelayedLoss.declinesFreeCast(ai, sa, seen);
            verdict = declined ? DECLINED : OK;
        } catch (final AITimeoutTracker.Expired e) {
            throw e;
        } catch (final RuntimeException e) {
            final String code = DelayedLoss.refusalWhenUnread(sa);
            declined = code != null;
            verdict = declined ? code : OK;
            unread = true;
        }
        judged(site, ai, sa, verdict, declined, unread, seen, null, null);
        return declined;
    }

    boolean judgeUnlessPayment(final Player ai, final SpellAbility sa, final Cost cost) {
        final DelayedLoss.Seen seen = new DelayedLoss.Seen(false);
        String code;
        boolean unread = false;
        try {
            code = DelayedLoss.unlessPaymentProblem(ai, sa, cost, seen);
        } catch (final AITimeoutTracker.Expired e) {
            throw e;
        } catch (final RuntimeException e) {
            code = null; // the payment goes on as today
            unread = true;
        }
        judged(S6B, ai, sa, code == null ? OK : code, code != null, unread, seen, null,
                () -> DelayedLoss.unlessPaymentProblem(ai, sa, cost, new DelayedLoss.Seen(true)) == null);
        return code != null;
    }

    void pay(final Player ai, final SpellAbility sa, final int[] held, final boolean paid) {
        try {
            final Game game = ai.getGame();
            final boolean copy = game.isSimulationCopy();
            final long[] counts = gameCounts().note(copy, false);
            if (copy && !copies) {
                return;
            }
            final Line line = head(game, copy, PAY, OK, sa,
                    debts(List.of(DelayedLoss.Debt.of(sa.getParam("UnlessCost"))), "NOW"), "-");
            line.add("held", held == null ? "-" : held[0] + "," + held[1] + "," + held[2] + "," + held[3]);
            line.add("paid", paid ? "yes" : "no");
            write(line.counts(counts));
        } catch (final AITimeoutTracker.Expired e) {
            throw e;
        } catch (final RuntimeException e) {
            // the trace only prints: whatever goes wrong here, the game goes on as it would without it
        }
    }

    void skip(final Player ai, final SpellAbility move) {
        try {
            final Game game = ai.getGame();
            final boolean copy = game.isSimulationCopy();
            final long[] counts = gameCounts().note(copy, true);
            if (copy && !copies) {
                return;
            }
            final Line line = head(game, copy, S2SKIP, SKIPPED, move, "-", "-");
            final String winner = winner(ai, game);
            if (winner != null) {
                line.add("winner", winner);
            }
            write(line.counts(counts));
        } catch (final AITimeoutTracker.Expired e) {
            throw e;
        } catch (final RuntimeException e) {
            // the trace only prints
        }
    }

    void marker(final Player ai) {
        try {
            if (!markers) {
                return;
            }
            final Game game = ai.getGame();
            if (game.isSimulationCopy()) {
                return;
            }
            final PhaseHandler ph = game.getPhaseHandler();
            write("decision\t" + ph.getTurn() + "\t" + phase(ph) + "\tseed=" + seed() + "\tplayers="
                    + clean(players(game)));
        } catch (final AITimeoutTracker.Expired e) {
            throw e;
        } catch (final RuntimeException e) {
            // the trace only prints
        }
    }

    /** Closes the file: DelayedLossTraceTest's instances. The live trace's file closes with the JVM. */
    void close() {
        synchronized (lock) {
            try {
                if (out != null) {
                    out.close();
                }
            } catch (final IOException e) {
                broken = true;
            }
            out = null;
        }
    }

    // -------------------------------------------------------------------------------------------------- the lines

    /**
     * A judgment's line, when the judgment read a delayed-loss play or found a debt (or threw on a play the site's
     * catch then refused); counted instead when it was made inside a simulation copy, and printed then only with the
     * copies on.
     */
    private void judged(final String site, final Player ai, final SpellAbility sa, final String verdict,
                        final boolean refused, final boolean unread, final DelayedLoss.Seen seen, final String heard,
                        final BooleanSupplier allowedWithoutThreat) {
        try {
            if (!seen.flagged && seen.debts.isEmpty() && !(unread && refused)) {
                return; // nothing flagged read, no debt found: no line, and nothing to count
            }
            final Game game = ai.getGame();
            final boolean copy = game.isSimulationCopy();
            final long[] counts = gameCounts().note(copy, refused);
            if (copy && !copies) {
                return;
            }
            final Line line = head(game, copy, site, verdict, sa, debts(seen.debts), units(seen.counts));
            if (unread) {
                line.add("unread", "yes");
            }
            if (heard != null) {
                line.add("heard", heard);
            }
            if (refused) {
                final String winner = winner(ai, game);
                if (winner != null) {
                    line.add("winner", winner);
                }
                if (allowedWithoutThreat != null && seen.countedNext() && allowedWithoutThreat(allowedWithoutThreat)) {
                    line.add("threat", "threat-only");
                }
            }
            write(line.counts(counts));
        } catch (final AITimeoutTracker.Expired e) {
            throw e;
        } catch (final RuntimeException e) {
            // the trace only prints: whatever goes wrong here, the game goes on as it would without it
        }
    }

    /** The same judgment with no threat term in its counts: whether it allows the play (round 2's M4). */
    private static boolean allowedWithoutThreat(final BooleanSupplier judgment) {
        try {
            return judgment.getAsBoolean();
        } catch (final AITimeoutTracker.Expired e) {
            throw e;
        } catch (final RuntimeException e) {
            return false;
        }
    }

    /** seed, players, turn, phase, site, verdict, card, debts and units: every line's first fields, in this order. */
    private static Line head(final Game game, final boolean copy, final String site, final String verdict,
                             final SpellAbility sa, final String debts, final String units) {
        final PhaseHandler ph = game.getPhaseHandler();
        return new Line().add("seed", seed()).add("players", players(game)).add("turn", ph.getTurn())
                .add("phase", copy ? "copy" : phase(ph)).add("site", site).add("verdict", verdict)
                .add("card", sa == null || sa.getHostCard() == null ? "-" : sa.getHostCard().getName())
                .add("debts", debts).add("units", units);
    }

    private static String seed() {
        final SimScope scope = SimScope.current();
        return scope == null ? "-" : Long.toString(scope.seed());
    }

    private static String phase(final PhaseHandler ph) {
        return ph.getPhase() == null ? "-" : ph.getPhase().name();
    }

    /** Each seat's deck name in seat order, joined by "|"; a seat whose deck cannot be read gives its player's name. */
    static String players(final Game game) {
        final StringBuilder sb = new StringBuilder();
        for (final Player p : game.getRegisteredPlayers()) {
            sb.append(sb.length() == 0 ? "" : "|").append(deckName(p));
        }
        return sb.toString();
    }

    private static String deckName(final Player p) {
        try {
            final RegisteredPlayer registered = p.getRegisteredPlayer();
            final String name = registered == null || registered.getDeck() == null ? null
                    : registered.getDeck().getName();
            if (name != null && !name.isEmpty()) {
                return name;
            }
        } catch (final RuntimeException e) {
            // a copy's player, or a seat the match does not list: its player's name instead
        }
        return p.getName();
    }

    /** The debts, the next upkeep's first: {@code NEXT:5:U+U,NOW:5:U+U}, or {@code -}. */
    static String debts(final DelayedLoss.Debts debts) {
        if (debts == null || debts.isEmpty()) {
            return "-";
        }
        final String next = debts(debts.nextDebts(), "NEXT");
        final String now = debts(debts.nowDebts(), "NOW");
        return next.isEmpty() ? now : now.isEmpty() ? next : next + "," + now;
    }

    private static String debts(final List<DelayedLoss.Debt> debts, final String due) {
        final StringBuilder sb = new StringBuilder();
        for (final DelayedLoss.Debt d : debts) {
            sb.append(sb.length() == 0 ? "" : ",").append(due).append(':').append(d.total());
            for (int i = 0; i < d.pips().size(); i++) {
                sb.append(i == 0 ? ":" : "+").append(DelayedLoss.colors(d.pips().get(i)));
            }
        }
        return sb.toString();
    }

    /** Each count made: {@code NEXT(life=20,threat=0):5{Island=U;Sol Ring=C+C}}, joined by a space, or {@code -}. */
    static String units(final List<DelayedLoss.Count> counts) {
        if (counts.isEmpty()) {
            return "-";
        }
        final StringBuilder sb = new StringBuilder();
        for (final DelayedLoss.Count c : counts) {
            sb.append(sb.length() == 0 ? "" : " ").append(c.due()).append("(life=").append(c.life()).append(",threat=")
                    .append(c.threat()).append("):").append(c.mana().units().size()).append('{')
                    .append(c.mana().describe()).append('}');
        }
        return sb.toString();
    }

    /**
     * The host of a trigger of the AI's on the stack that DreadReturnWin.isWinner names (Thassa's Oracle, Laboratory
     * Maniac: a library-out winner), top first, or null: the waiver DESIGN.md 1.9 leaves unbuilt (round 2's M12).
     * Field reads and a name-list read; ConsultationWin.isEnterWinner, which builds replacement effects, is not asked.
     */
    static String winner(final Player ai, final Game game) {
        for (final SpellAbilityStackInstance si : game.getStack()) {
            final SpellAbility s = si.getSpellAbility();
            if (s != null && s.isTrigger() && ai.equals(s.getActivatingPlayer())
                    && DreadReturnWin.isWinner(s.getHostCard())) {
                return s.getHostCard().getName();
            }
        }
        return null;
    }

    private void write(final String line) {
        synchronized (lock) {
            if (broken || file == null) {
                return;
            }
            try {
                if (out == null) {
                    out = Files.newBufferedWriter(file, StandardCharsets.UTF_8, StandardOpenOption.CREATE,
                            StandardOpenOption.APPEND, StandardOpenOption.WRITE);
                }
                out.write(line);
                out.write('\n');
                out.flush();
            } catch (final IOException e) {
                broken = true; // never stdout or stderr: a file that cannot be written is written no more
            }
        }
    }

    /** A field's value on one line: a tab or a line break inside it would split the line. */
    private static String clean(final Object value) {
        return String.valueOf(value).replace('\t', ' ').replace('\r', ' ').replace('\n', ' ');
    }

    /** The game's copy counts: in its SimScope's scratch, which it shares with its own rollouts and no other game. */
    private Counts gameCounts() {
        final SimScope scope = SimScope.current();
        return scope == null ? unbound.get() : scope.scratch(DelayedLossTrace.class, Counts::new);
    }

    /** A game's running counts of the judgments made inside its simulation copies (RULINGS-R3 F). */
    static final class Counts {
        private long judged;
        private long refused;

        /** Counts a judgment made inside a copy; the counts after it. */
        synchronized long[] note(final boolean copy, final boolean refusedNow) {
            if (copy) {
                judged++;
                if (refusedNow) {
                    refused++;
                }
            }
            return new long[] { judged, refused };
        }
    }

    /** One line's tab-separated fields. */
    private static final class Line {
        private final StringBuilder sb = new StringBuilder(200);

        Line add(final String key, final Object value) {
            sb.append(sb.length() == 0 ? "" : "\t").append(key).append('=').append(clean(value));
            return this;
        }

        /** The two counts, the last fields, and the line. */
        String counts(final long[] counts) {
            return add("copy_judged", counts[0]).add("copy_refused", counts[1]).sb.toString();
        }
    }
}
