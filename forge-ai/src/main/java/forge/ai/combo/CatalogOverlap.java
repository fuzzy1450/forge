package forge.ai.combo;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import forge.ai.simulation.AiCardLists;
import forge.ai.simulation.ComboCatalog;

/**
 * Which combos of upstream's catalog ({@link ComboCatalog}, ai-combos.txt) the fork's combo table holds as a line of
 * exactly those cards. The fork's combo program owns those: {@link ComboFetch} decides, ahead of the deck plan in
 * ChangeZoneAi, when a piece of one is worth fetching, and when it is not (danger, a denied line, a hoser, a search
 * another player controls) it wants the stock pick with the stock draws. Upstream's #12858 gave the deck plan's tutor
 * pick a catalog step of its own (DeckPlan.tutorPicks over ComboCatalog.missingPieces) that fetched such a piece
 * whatever the fork's lines said; the deck plan now leaves each combo {@link #held} answers for to the fork's program,
 * and the catalog keeps serving the combos the table holds no line of (Devoted Druid and Quillspike).
 */
public final class CatalogOverlap {
    private CatalogOverlap() {
    }

    /** The catalog {@link #heldCombinations} was read for, by identity: ComboCatalog.reload() makes a new one. */
    private static List<ComboCatalog.Entry> readFor;
    /** The catalog's slot combinations the table holds a line of exactly, each a set of normalized names. */
    private static Set<Set<String>> heldCombinations = Set.of();

    /**
     * Whether the shipped table holds a line of exactly {@code piece} and one card named in each set of
     * {@code inView} (the names in view that fill a combo's other slots), every name normalized as the catalog
     * normalizes it ({@link AiCardLists#normalize}).
     */
    public static boolean held(final List<Set<String>> inView, final String piece) {
        final Set<Set<String>> held = heldCombinations();
        if (held.isEmpty()) {
            return false;
        }
        final List<String> chosen = new ArrayList<>();
        chosen.add(piece);
        return anyChoice(inView, 0, chosen, held);
    }

    private static boolean anyChoice(final List<Set<String>> inView, final int slot, final List<String> chosen,
            final Set<Set<String>> held) {
        if (slot == inView.size()) {
            return held.contains(new HashSet<>(chosen));
        }
        for (final String name : inView.get(slot)) {
            chosen.add(name);
            final boolean found = anyChoice(inView, slot + 1, chosen, held);
            chosen.remove(chosen.size() - 1);
            if (found) {
                return true;
            }
        }
        return false;
    }

    /** The catalog's slot combinations that the table holds, read in one pass over the table for each catalog. */
    private static synchronized Set<Set<String>> heldCombinations() {
        final List<ComboCatalog.Entry> entries = ComboCatalog.entries();
        if (entries != readFor) {
            final Set<Set<String>> wanted = new HashSet<>();
            for (final ComboCatalog.Entry e : entries) {
                combinations(e.slots(), 0, new ArrayList<>(), wanted);
            }
            final Set<Set<String>> held = new HashSet<>();
            for (final ComboTable.Variant v : ComboTable.shipped().variants()) {
                final Set<String> names = new HashSet<>();
                for (final ComboTable.Piece p : v.pieces()) {
                    names.add(AiCardLists.normalize(p.name()));
                }
                if (wanted.contains(names)) {
                    held.add(Set.copyOf(names));
                }
            }
            heldCombinations = Set.copyOf(held);
            readFor = entries;
        }
        return heldCombinations;
    }

    private static void combinations(final List<Set<String>> slots, final int slot, final List<String> chosen,
            final Set<Set<String>> out) {
        if (slot == slots.size()) {
            out.add(new HashSet<>(chosen));
            return;
        }
        for (final String name : slots.get(slot)) {
            chosen.add(name);
            combinations(slots, slot + 1, chosen, out);
            chosen.remove(chosen.size() - 1);
        }
    }
}
