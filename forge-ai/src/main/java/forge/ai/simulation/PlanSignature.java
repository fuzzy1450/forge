package forge.ai.simulation;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import forge.game.card.Card;
import forge.game.player.Player;

/**
 * What a list's deck plan says, as data: two lists read one plan when the texts of their signatures are equal
 * (MTG_DeckMaker's tools/combo-ai/f5-guard/SPEC.md, section 3.2, which says why each field is in or out, and which
 * PlanSignatureFieldsTest keeps honest). {@link DeckPlan#describe()} cannot be it: it prints name sets in the order the
 * scan met them, so one list prints two ways. Here every name set is sorted, so the text follows the list and not a
 * shuffle, and the order of the combos stays, because it is the plan's own: {@link ComboPlan#primary()} reads it.
 * <p>
 * In this package because a combo's three sets and {@link ComboPlan#deckCards} are package-private. It only reads. Its one
 * caller is the plan verb's read, at the first turn of a game that is thrown away, never a game that is played on.
 */
public final class PlanSignature {
    /** {@link CommanderPlan#scan} stops here, walking a set in scan order, so at the cap which pairs it kept can follow the
     *  library, and a list that holds so many has no answer. */
    public static final int PAIR_CAP = CommanderPlan.MAX_KNOWN_COMBOS;

    private static final String LIVING_END = "Living End";

    private PlanSignature() { }

    /**
     * The signature of {@code plan}, whose player is {@code player}: {@code archetype}, {@code combos} in plan order, each
     * with its {@code kind}, {@code strength} and sorted {@code payoffs}, {@code enablers} and {@code cascaders}, then
     * sorted {@code commanders} and {@code known_pairs}, and {@code living_end}, whether the deck holds the card
     * {@link ComboPlan#isMassReanimationDeck} finds by name with no combo read. The keys keep this order.
     */
    public static Map<String, Object> of(final DeckPlan plan, final Player player) {
        final Map<String, Object> signature = new LinkedHashMap<>();
        signature.put("archetype", plan.archetype.name());
        final List<Object> combos = new ArrayList<>();
        for (final ComboPlan.Combo c : plan.combos.getCombos()) {
            final Map<String, Object> combo = new LinkedHashMap<>();
            combo.put("kind", c.kind.name());
            combo.put("strength", c.strength);
            combo.put("payoffs", sorted(c.payoffs));
            combo.put("enablers", sorted(c.enablers));
            combo.put("cascaders", sorted(c.cascaders));
            combos.add(combo);
        }
        signature.put("combos", combos);
        signature.put("commanders", sorted(plan.commander.getCommanderNames()));
        final List<String> pairs = new ArrayList<>();
        for (final CommanderPlan.KnownCombo known : plan.commander.getCombos()) {
            pairs.add(known.name);
        }
        signature.put("known_pairs", sorted(pairs));
        signature.put("living_end", holdsLivingEnd(player));
        return signature;
    }

    private static List<String> sorted(final Collection<String> names) {
        final List<String> out = new ArrayList<>(names);
        Collections.sort(out);
        return out;
    }

    private static boolean holdsLivingEnd(final Player player) {
        for (final Card c : ComboPlan.deckCards(player)) {
            if (LIVING_END.equals(c.getName())) {
                return true;
            }
        }
        return false;
    }
}
