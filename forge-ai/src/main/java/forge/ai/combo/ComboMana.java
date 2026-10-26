package forge.ai.combo;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import forge.ai.AiCardMemory;
import forge.card.MagicColor;
import forge.card.mana.ManaAtom;
import forge.card.mana.ManaCost;
import forge.card.mana.ManaCostShard;
import forge.game.ability.AbilityUtils;
import forge.game.ability.ApiType;
import forge.game.card.Card;
import forge.game.mana.ManaPool;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.spellability.AbilityManaPart;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;

/**
 * Whether the mana a player can make right now pays several costs together (spec
 * 2026-10-22-combo-aware-ai-core-design.md section 6.4, "the mana for both"): Thassa's Oracle's {U}{U} and then its
 * emptier, or an emptier and the draw spell that follows it. Each source is assigned to one pip, as the payment taps
 * it: an Underground Sea is a {U} or a {B}, never both, and Sol Ring's two pay generic mana only.
 * SpecialCardAi.HonestMana counts one multicolour source toward each of its colours, so Island, Underground Sea and
 * Sol Ring read as two blue and one black, which Oracle and Demonic Consultation cannot both be paid from (final
 * review, round 1).
 *
 * <p>The sources are HonestMana's (a private copy of its reading, which is frozen): the floating pool, and each
 * battlefield source we control whose mana ability canPlay() (untapped, not summoning-sick, its restrictions met), has
 * no mana in its own cost, meets its conditions, may be spent on {@code sa} and is no "activate only as an instant"
 * ability (Lion's Eye Diamond: the AI's payer never taps one), less the sources isManaSourceReserved holds when
 * {@code skipHeld}. A source makes one activation's mana: a fixed production its symbols (Karoo's "U B" a
 * {U} and a {B}), a choice production its amount of one kind among its letters (Command Tower one of its colours,
 * Gilded Lotus three of one colour), "AnyDifferent" one coloured and the rest generic only (an under-count), a
 * reflected, special or letterless production generic only. A source with several abilities of one mana each is one
 * mana of any of their kinds (Talisman's {C}, {U} or {B}); one whose abilities make more is tried ability by ability.
 *
 * <p>A cost's coloured, colourless and hybrid pips must each meet a source of a kind that pays it (Hall's condition
 * over the kinds demanded, which is exact for one mana a source), and the sources must number its whole mana value.
 * A Phyrexian or a {2/C} pip is read as its colour alone and a snow pip as generic; X is none. Under-counts, the safe
 * side for a go-off: those Phyrexian and {2/C} readings, hand sources, mana multipliers and the sources past
 * {@link #MAX_CHOICES}. The costs are read as handed: ComboExecute hands each spell's as the engine prices it, taxes
 * and reductions included (follow-up 2, G3), ComboFetch and ComboSignals a card's printed cost. Not read: a restriction
 * that lets a source pay one of the costs but not the other (each source is read against {@code sa}). Draws nothing and
 * keeps nothing; its only write is setActivatingPlayer on the mana abilities it reads, as HonestMana does.
 */
final class ComboMana {
    /** The kinds of mana, as ManaAtom's low bits: W, U, B, R, G, and colourless. */
    private static final int KINDS = 6;
    /** A colourless mana's kind bit (ManaAtom.COLORLESS). */
    private static final int COLOURLESS = 1 << 5;
    /** The most combinations of the sources tried ability by ability; each source past it pays generic mana only. */
    private static final int MAX_CHOICES = 64;

    // how many sources make one mana of each kind mask (0: generic only); index = the mask
    private final int[] units = new int[1 << KINDS];
    // the sources that make more than one mana by some ability, each its options: the masks of the mana it makes
    private final List<List<int[]>> flexible = new ArrayList<>();

    private ComboMana() {
    }

    /** The mana {@code ai} can make now for {@code sa}, held sources left out when {@code skipHeld}. */
    static ComboMana of(final Player ai, final SpellAbility sa, final boolean skipHeld) {
        final ComboMana m = new ComboMana();
        final ManaPool pool = ai.getManaPool();
        int coloured = 0;
        for (int i = 0; i < MagicColor.WUBRG.length; i++) {
            final int n = pool.getAmountOfColor(MagicColor.WUBRG[i]);
            m.units[MagicColor.WUBRG[i]] += n;
            coloured += n;
        }
        m.units[COLOURLESS] += Math.max(0, pool.totalMana() - coloured);
        for (final Card src : ai.getCardsIn(ZoneType.Battlefield)) {
            if (skipHeld && isHeld(ai, src)) {
                continue;
            }
            final List<int[]> options = new ArrayList<>();
            for (final SpellAbility ma : src.getManaAbilities()) {
                final AbilityManaPart mp = ma.getManaPart();
                if (mp == null) {
                    continue;
                }
                ma.setActivatingPlayer(ai);
                if (!ma.canPlay() || ma.getPayCosts().hasManaCost() || !ma.metConditions()
                        || !mp.meetsManaRestrictions(sa) || instantSpeed(ma)) {
                    continue;
                }
                final int amount = AbilityUtils.calculateAmount(src, ma.getParamOrDefault("Amount", "1"), ma);
                if (amount > 0) {
                    options.addAll(shapes(ma, mp, amount));
                }
            }
            m.add(options);
        }
        return m;
    }

    // an "activate only as an instant" mana ability (Lion's Eye Diamond, Diamond Lion, Charmed Pendant): playable at
    // priority (canPlay), but the AI's payer never taps one (ComputerUtilMana's getAIPlayableMana and
    // canPayShardWithSpellAbility skip it, as SpellAbility.isManaAbilityFor does), so it pays none of these costs
    // (follow-up final review, round 1)
    private static boolean instantSpeed(final SpellAbility ma) {
        return ma.getRestrictions() != null && ma.getRestrictions().isInstantSpeed();
    }

    /** Whether these costs can all be paid together from this mana, each source making one activation's mana. */
    boolean pays(final ManaCost... costs) {
        final int[] need = new int[1 << KINDS];
        int generic = 0;
        for (final ManaCost cost : costs) {
            generic += cost.getGenericCost();
            for (final ManaCostShard s : cost) {
                if (s.isOfKind(ManaAtom.IS_X)) {
                    continue;
                }
                final int kinds = s.getShard() & ((1 << KINDS) - 1);
                if (kinds == 0) {
                    generic++;
                } else {
                    need[kinds]++;
                }
            }
        }
        // the sources tried ability by ability, while their combinations stay within MAX_CHOICES; the rest pay generic
        // mana only, their largest activation
        final int[] base = units.clone();
        final List<List<int[]>> tried = new ArrayList<>();
        int choices = 1;
        for (final List<int[]> options : flexible) {
            if (choices * options.size() <= MAX_CHOICES) {
                choices *= options.size();
                tried.add(options);
            } else {
                int most = 0;
                for (final int[] o : options) {
                    most = Math.max(most, o.length);
                }
                base[0] += most;
            }
        }
        return assign(base, tried, 0, need, generic);
    }

    // some choice of an option for each tried source, from the i-th on, pays the need
    private static boolean assign(final int[] units, final List<List<int[]>> tried, final int i, final int[] need,
            final int generic) {
        if (i == tried.size()) {
            return feasible(units, need, generic);
        }
        for (final int[] option : tried.get(i)) {
            for (final int mask : option) {
                units[mask]++;
            }
            final boolean pays = assign(units, tried, i + 1, need, generic);
            for (final int mask : option) {
                units[mask]--;
            }
            if (pays) {
                return true;
            }
        }
        return false;
    }

    // Hall's condition: for every set of the demanded kind masks, the sources able to pay one of them are at least as
    // many as the pips they demand; and the sources are at least as many as every pip and generic mana together
    private static boolean feasible(final int[] units, final int[] need, final int generic) {
        int have = 0;
        int want = generic;
        final List<Integer> demanded = new ArrayList<>();
        for (int mask = 0; mask < units.length; mask++) {
            have += units[mask];
            if (need[mask] > 0) {
                want += need[mask];
                demanded.add(mask);
            }
        }
        if (have < want) {
            return false;
        }
        for (int set = 1; set < (1 << demanded.size()); set++) {
            int pips = 0;
            int cover = 0;
            for (int j = 0; j < demanded.size(); j++) {
                if ((set & (1 << j)) != 0) {
                    pips += need[demanded.get(j)];
                    cover |= demanded.get(j);
                }
            }
            int able = 0;
            for (int mask = 1; mask < units.length; mask++) {
                if ((mask & cover) != 0) {
                    able += units[mask];
                }
            }
            if (able < pips) {
                return false;
            }
        }
        return true;
    }

    // one source's options: a single option is its mana; several of one mana each are one mana of any of their kinds;
    // anything else is tried ability by ability
    private void add(final List<int[]> options) {
        if (options.isEmpty()) {
            return;
        }
        if (options.size() == 1) {
            for (final int mask : options.get(0)) {
                units[mask]++;
            }
            return;
        }
        int any = 0;
        for (final int[] o : options) {
            if (o.length != 1) {
                flexible.add(options);
                return;
            }
            any |= o[0];
        }
        units[any]++;
    }

    // what one activation of the ability can make, as one option per way to make it (HonestMana's reading of its
    // production)
    private static List<int[]> shapes(final SpellAbility ma, final AbilityManaPart mp, final int amount) {
        final String produced = mp.getOrigProduced().trim();
        final boolean reflectedOrSpecial = ma.getApi() == ApiType.ManaReflected || mp.isSpecialMana();
        final boolean choice = reflectedOrSpecial || produced.isEmpty() || mp.isComboMana() || mp.isAnyMana()
                || produced.contains("Chosen");
        final List<int[]> out = new ArrayList<>();
        if (!choice) {
            // fixed symbols, each made amount times: "U B" an {U} and a {B}, Sol Ring's "C" at Amount 2 two {C}
            final String[] symbols = produced.split(" ");
            final int[] made = new int[symbols.length * amount];
            int n = 0;
            for (final String symbol : symbols) {
                for (int k = 0; k < amount; k++) {
                    made[n++] = kind(symbol);
                }
            }
            out.add(made);
            return out;
        }
        final String letters;
        if (reflectedOrSpecial) {
            letters = "";
        } else if (mp.isComboMana()) {
            letters = mp.getComboColors(ma);
        } else if (mp.isAnyMana()) {
            letters = "W U B R G";
        } else if (produced.contains("Chosen")) {
            letters = produced.replace("Chosen", mp.getChosenColor(ma));
        } else {
            letters = produced;
        }
        int mask = 0;
        for (final String symbol : letters.split(" ")) {
            mask |= kind(symbol);
        }
        if (produced.contains("AnyDifferent")) {
            // amount of different colours: one of them, the rest generic only
            final int[] made = new int[amount];
            made[0] = mask;
            out.add(made);
            return out;
        }
        if (amount == 1 || Integer.bitCount(mask) <= 1) {
            final int[] made = new int[amount];
            Arrays.fill(made, mask);
            out.add(made);
            return out;
        }
        // amount of one kind, chosen among the letters
        for (int bit = 0; bit < KINDS; bit++) {
            if ((mask & (1 << bit)) != 0) {
                final int[] made = new int[amount];
                Arrays.fill(made, 1 << bit);
                out.add(made);
            }
        }
        return out;
    }

    // the kind bit of one mana symbol (W, U, B, R, G or C), 0 for anything else
    private static int kind(final String symbol) {
        return symbol.length() == 1 ? ManaAtom.fromName(symbol.charAt(0)) & ((1 << KINDS) - 1) : 0;
    }

    // the sources ComputerUtilMana.isManaSourceReserved refuses, in the phases it refuses them (HonestMana's isHeld)
    private static boolean isHeld(final Player ai, final Card src) {
        if (AiCardMemory.isRememberedCard(ai, src, AiCardMemory.MemorySet.HELD_MANA_SOURCES_FOR_NEXT_SPELL)) {
            return true;
        }
        final PhaseType phase = ai.getGame().getPhaseHandler().getPhase();
        if (phase != PhaseType.COMBAT_DECLARE_BLOCKERS && phase != PhaseType.CLEANUP
                && (AiCardMemory.isRememberedCard(ai, src, AiCardMemory.MemorySet.HELD_MANA_SOURCES_FOR_DECLBLK)
                || AiCardMemory.isRememberedCard(ai, src, AiCardMemory.MemorySet.HELD_MANA_SOURCES_FOR_ENEMY_DECLBLK))) {
            return true;
        }
        return phase != PhaseType.MAIN2 && phase != PhaseType.CLEANUP
                && AiCardMemory.isRememberedCard(ai, src, AiCardMemory.MemorySet.HELD_MANA_SOURCES_FOR_MAIN2);
    }
}
