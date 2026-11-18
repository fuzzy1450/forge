package forge.ai.simulation;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Function;

import forge.game.Game;
import forge.game.card.Card;
import forge.game.zone.ZoneType;
import forge.util.SimScope;

/**
 * Print-only, off unless {@code -Dflatmc.sicknessprobe=<file>} is set: the copier's summoning-sickness probe, for the
 * checks of MTG_DeckMaker's copier-sickness fix (docs/superpowers/specs/2026-11-18-flatmc-copier-sickness-design.md,
 * section 5 item 3). Nothing in a game reads anything it writes, and it only reads: it draws no random value and takes
 * no id, and a failure inside it is swallowed, never thrown into the copy.
 *
 * <p>An <em>affected</em> card is a battlefield card that is sick and hasted when it is copied: the one place where the
 * copier, which now copies the raw flag ({@link Card#isFirstTurnControlled()}), gives the copy another value than the
 * old copier did ({@link Card#hasSickness()}). After each copy's own state check, {@link #afterCopy} appends, keyed by the
 * game scope's seed, at most:
 * <ul>
 * <li>{@code D* seed=S turn=T phase=P cards=ID:NAME,...}, once a game, at its first copy holding an affected card;</li>
 * <li>{@code AFFECTED seed=S turn=T phase=P card=ID:NAME}, once a card a game, the first time it is affected;</li>
 * <li>{@code LOST seed=S turn=T phase=P card=ID:NAME copies=N}, once a card a game, the first time the copy reads it
 * sick although the original has the haste that keeps it from being so: the copier lost that haste, which the old
 * copier's masked flag hid (the design's anti-goal). {@code N} counts that game's copies with such a card so far.</li>
 * </ul>
 * With no scope bound (the GUI) it prints nothing.
 */
final class SicknessProbe {
    private static final String PROPERTY = "flatmc.sicknessprobe";

    private static volatile Consumer<String> sink = fileSink(System.getProperty(PROPERTY));

    private static final Set<Long> D_STAR = ConcurrentHashMap.newKeySet();
    private static final Set<String> AFFECTED_SEEN = ConcurrentHashMap.newKeySet();
    private static final Set<String> LOST_SEEN = ConcurrentHashMap.newKeySet();
    private static final ConcurrentHashMap<Long, AtomicInteger> LOST_COPIES = new ConcurrentHashMap<>();

    private SicknessProbe() {
    }

    /** Sick and hasted on the battlefield: the old and the new copier give its copy different flags. */
    static boolean affected(final Card original) {
        return original.isInZone(ZoneType.Battlefield) && original.isFirstTurnControlled() && !original.hasSickness();
    }

    /** The copy, still on its battlefield after its state check, reads sick: it lost the haste the original has. */
    static boolean lostHaste(final Card original, final Card copy) {
        return affected(original) && copy != null && copy.isInZone(ZoneType.Battlefield) && copy.hasSickness();
    }

    /** Called by the copier after the copy's own state check; does nothing while the probe is off or no scope is bound. */
    static void afterCopy(final Game original, final Function<Card, Card> toCopy) {
        final Consumer<String> out = sink;
        if (out == null) {
            return;
        }
        try {
            final SimScope scope = SimScope.current();
            if (scope == null) {
                return;
            }
            final List<Card> affected = new ArrayList<>();
            for (final Card c : original.getCardsIn(ZoneType.Battlefield)) {
                if (affected(c)) {
                    affected.add(c);
                }
            }
            if (affected.isEmpty()) {
                return;
            }
            final long seed = scope.seed();
            final String where = "seed=" + seed + " turn=" + original.getPhaseHandler().getTurn()
                    + " phase=" + original.getPhaseHandler().getPhase();
            if (D_STAR.add(seed)) {
                final StringBuilder cards = new StringBuilder();
                for (final Card c : affected) {
                    cards.append(cards.length() == 0 ? "" : ",").append(c.getId()).append(':').append(c.getName());
                }
                out.accept("D* " + where + " cards=" + cards);
            }
            final List<Card> lost = new ArrayList<>();
            for (final Card c : affected) {
                if (AFFECTED_SEEN.add(seed + ":" + c.getId())) {
                    out.accept("AFFECTED " + where + " card=" + c.getId() + ":" + c.getName());
                }
                if (lostHaste(c, toCopy.apply(c))) {
                    lost.add(c);
                }
            }
            if (!lost.isEmpty()) {
                final int copies = LOST_COPIES.computeIfAbsent(seed, k -> new AtomicInteger()).incrementAndGet();
                for (final Card c : lost) {
                    if (LOST_SEEN.add(seed + ":" + c.getId())) {
                        out.accept("LOST " + where + " card=" + c.getId() + ":" + c.getName() + " copies=" + copies);
                    }
                }
            }
        } catch (final RuntimeException e) {
            // print-only: a probe must never break a copy
        }
    }

    /** Tests only: puts {@code next} in the sink's place (null turns the probe off) and returns what was there. */
    static Consumer<String> swapSink(final Consumer<String> next) {
        final Consumer<String> before = sink;
        sink = next;
        return before;
    }

    private static Consumer<String> fileSink(final String path) {
        if (path == null || path.isEmpty()) {
            return null;
        }
        return line -> {
            synchronized (SicknessProbe.class) {
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
