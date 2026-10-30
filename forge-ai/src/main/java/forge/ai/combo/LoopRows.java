package forge.ai.combo;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Piece 2's loop rows: the 19 rows of the combo table that are Kinnan, Bonder Prodigy's unlimited-mana loops, each
 * mapped by hand to the kind of cycle that runs it, since the table keeps no steps (spec
 * 2026-10-29-combo-aware-ai-loops-design.md section 6.1). They are the main repo's PIECE2_IDS
 * (mtglib/sim/combotable.py), which the table build refuses to lose: six Spellbook rows, then the extras file's x-2 to
 * x-14 (sim/combos/extras.txt), Hullbreaker Horror's cast and bounce with each fill in Kinnan's list.
 *
 * <p>No other row is a loop row, so the driver never runs one: 513-3682 (Hullbreaker Horror and Mox Amber) needs a
 * third permanent it does not name, whose fills are rows of their own (x-5, x-13 and x-14).
 */
public final class LoopRows {
    /** Each loop row's id and kind, in {@link #ids}'s order; immutable. */
    private static final Map<String, LoopKind> KINDS = kinds();
    /** The ids of {@link #KINDS}, in its order; immutable. */
    private static final List<String> IDS = List.copyOf(KINDS.keySet());

    private LoopRows() {
    }

    /** The kind of cycle that runs the row whose id is {@code variantId}, or null when it is no loop row. */
    public static LoopKind kindOf(final String variantId) {
        return KINDS.get(variantId);
    }

    /** The 19 loop rows' ids, in this order: the rock untaps 129-4131, 3430-4131, 2585-3430 and 129-2585-2719, the
     *  flicker 1409-3542, the return and recast 3430-3542, then the cast and bounce rows x-2 to x-14 by number;
     *  immutable, and the same list on every call. */
    public static List<String> ids() {
        return IDS;
    }

    private static Map<String, LoopKind> kinds() {
        final Map<String, LoopKind> kinds = new LinkedHashMap<>();
        kinds.put("129-4131", LoopKind.ROCK_UNTAP);        // Kinnan + Basalt Monolith
        kinds.put("3430-4131", LoopKind.ROCK_UNTAP);       // Basalt Monolith + Nyxbloom Ancient
        kinds.put("2585-3430", LoopKind.ROCK_UNTAP);       // Grim Monolith + Nyxbloom Ancient
        kinds.put("129-2585-2719", LoopKind.ROCK_UNTAP);   // Kinnan + Grim Monolith + Sakashima as a copy of Kinnan
        kinds.put("1409-3542", LoopKind.FLICKER);          // Palinchron + Deadeye Navigator
        kinds.put("3430-3542", LoopKind.RECAST);           // Palinchron + Nyxbloom Ancient
        // Hullbreaker Horror's cast and bounce, as the extras file writes each row: the rock on the battlefield beside
        // Hullbreaker; the fill in hand; Kinnan, on the battlefield, where the row names him
        kinds.put("x-2", LoopKind.BOUNCE);                 // Sol Ring; Mana Vault
        kinds.put("x-3", LoopKind.BOUNCE);                 // Sol Ring; Mox Opal
        kinds.put("x-4", LoopKind.BOUNCE);                 // Sol Ring; Sensei's Divining Top; Kinnan
        kinds.put("x-5", LoopKind.BOUNCE);                 // Sol Ring; Mox Amber
        kinds.put("x-6", LoopKind.BOUNCE);                 // Mana Vault; Sensei's Divining Top
        kinds.put("x-7", LoopKind.BOUNCE);                 // Mana Vault; Mox Opal
        kinds.put("x-8", LoopKind.BOUNCE);                 // Mana Vault; Grim Monolith
        kinds.put("x-9", LoopKind.BOUNCE);                 // Mana Vault; Arcane Signet
        kinds.put("x-10", LoopKind.BOUNCE);                // Mana Vault; Simic Signet
        kinds.put("x-11", LoopKind.BOUNCE);                // Mana Vault; Talisman of Curiosity
        kinds.put("x-12", LoopKind.BOUNCE);                // Mana Vault; Scroll Rack; Kinnan
        kinds.put("x-13", LoopKind.BOUNCE);                // Mana Vault; Mox Amber
        kinds.put("x-14", LoopKind.BOUNCE);                // no rock; Mox Amber and Mox Opal; Kinnan
        return Collections.unmodifiableMap(kinds);
    }
}
