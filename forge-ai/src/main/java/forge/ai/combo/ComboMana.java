package forge.ai.combo;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import forge.ai.AiCardMemory;
import forge.ai.ManaSacrifice;
import forge.ai.PlayerControllerAi;
import forge.ai.simulation.ComboPlan;
import forge.card.MagicColor;
import forge.card.mana.ManaAtom;
import forge.card.mana.ManaCost;
import forge.card.mana.ManaCostShard;
import forge.game.ability.AbilityUtils;
import forge.game.ability.ApiType;
import forge.game.card.Card;
import forge.game.cost.CostDiscard;
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
 * no mana in its own cost, meets its conditions, may be spent on {@code sa}, is no "activate only as an instant"
 * ability (Lion's Eye Diamond: the AI's payer never taps one), is no discard the payer refuses while the hand holds a
 * kill piece of a storm deck (Skirge Familiar with Tendrils of Agony in hand) and, when it sacrifices another permanent,
 * gives up one the AI's payer would give up for what it pays (ManaSacrifice.worthItBeforePaying: for {@code sa},
 * Phyrexian Altar's only creature, a Craw Wurm, buying no Constructed game's Demonic Consultation; for a go-off, what the
 * payer gives up at the emptier's moment, {@link #of(Player, SpellAbility, SpellAbility, Card, boolean)}), less the
 * sources isManaSourceReserved holds when {@code skipHeld}. A source makes one activation's mana: a fixed production
 * its symbols (Karoo's "U B" a {U} and a {B}), a choice production its amount of one kind among its letters (Command
 * Tower one of its colours, Gilded Lotus three of one colour), "AnyDifferent" one coloured and the rest generic only
 * (an under-count), a reflected, special or letterless production generic only. A source with several abilities of one
 * mana each is one mana of any of their kinds (Talisman's {C}, {U} or {B}); one whose abilities make more is tried
 * ability by ability. {@link #nextTurn} reads our next turn's mana instead (follow-up 2, G4): the same sources, whether
 * or not they can be activated now, and no pool.
 *
 * <p>A cost's coloured, colourless and hybrid pips must each meet a source of a kind that pays it (Hall's condition
 * over the kinds demanded, which is exact for one mana a source), and the sources must number its whole mana value.
 * A Phyrexian or a {2/C} pip is read as its colour alone and a snow pip as generic; X is none. Under-counts, the safe
 * side for a go-off: those Phyrexian and {2/C} readings, hand sources, mana multipliers and the sources past
 * {@link #MAX_CHOICES}. The costs are read as handed: ComboExecute hands each spell's as the engine prices it, taxes
 * and reductions included (follow-up 2, G3), and so does ComboFetch's win in time under danger (follow-up final review,
 * round 1); ComboSignals' burn reading hands a card's printed cost. Not read: a restriction
 * that lets a source pay one of the costs but not the other (each source is read against {@code sa}). A null
 * {@code sa} is a payment that is no spell or ability, ComboSignals' attack taxes (follow-up 3, H2): a source whose mana
 * carries any spending restriction pays none of it. {@link #hold} names the sources that pay one cost alone while the
 * rest of the mana pays another (follow-up 3, H4: an opponent's Rhystic Study paid during a go-off, the emptier's
 * sources held from its payer). Draws nothing and keeps nothing past a reading; its only write is
 * setActivatingPlayer on the mana abilities it reads, as HonestMana does (and as ComputerUtilMana's estimate does on
 * an opponent's, for the opponent's mana that ComboSignals reads).
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
    // the floating pool's share of units (hold gives it to the cost it does not hold for)
    private final int[] pool = new int[1 << KINDS];
    // each battlefield source read, in battlefield order, and its options (hold reads them one source at a time)
    private final Map<Card, List<int[]>> sources = new LinkedHashMap<>();

    private ComboMana() {
    }

    /** The mana {@code ai} can make now for {@code sa}, held sources left out when {@code skipHeld}. */
    static ComboMana of(final Player ai, final SpellAbility sa, final boolean skipHeld) {
        return read(ai, sa, sa, null, skipHeld, false);
    }

    /**
     * {@link #of}, where a sacrifice pays {@code paidFor}, the last spell of the costs read, once {@code entering} is
     * on the battlefield: a source that sacrifices another permanent counts when the AI's payer would give one up for
     * {@code paidFor} at that moment, {@code entering} among them (null: the battlefield as it is). The go-off's own
     * read: Thassa's Oracle's emptier is paid with Oracle in play and its trigger on the stack, so the payer can give
     * up Oracle, which loses nothing, and keep the creature a read of today's board would have asked it for. In a
     * Commander game on a plain board it does (Consultation's worth of 202, the key card bonus in it, against Oracle's
     * 125 and the margin of 60); in a Constructed game, at 52, it does not. The read takes Oracle's value off the
     * battlefield, the 125 of ManaSacrifice.worthItBeforePaying, so under our own anthem or haste it reads ready where
     * the payer refuses: at the payment Oracle is 145 under Glorious Anthem, past Consultation's 142, and 156 under Fervor
     * or Rising of the Day, past Tainted Pact's 154 too, and Oracle's trigger resolves on a full library. That false
     * "ready" is not the safe side. A false "ready" on a source only Oracle's own cost needs, the fodder too dear for
     * Oracle (Island, Swamp, Sol Ring, Phyrexian Altar and a Craw Wurm), is: the payer refuses the cast, and nothing is
     * spent.
     */
    static ComboMana of(final Player ai, final SpellAbility sa, final SpellAbility paidFor, final Card entering,
            final boolean skipHeld) {
        return read(ai, sa, paidFor, entering, skipHeld, false);
    }

    /** The mana {@code ai} can make for {@code sa} in its own next turn, untapped (follow-up 2, G4: a piece put on top
     *  of our library at the end step before our turn is cast in that turn's Main 1): each battlefield source we
     *  control, read as {@link #of} reads it but whether or not its mana ability can be activated now (tapped,
     *  summoning-sick, restricted to our turn), as Forge's own estimate of next turn's mana skips canPlay
     *  (ComputerUtilMana.getAvailableManaEstimate with checkPlayable false: signal 1's next turn's mana); no floating
     *  mana, which empties before that turn, and no held source, the AI's card memory resetting at each end step
     *  (PlayerControllerAi.resetAtEndOfTurn). A land still to be played is not counted (the hand sources' under-count). */
    static ComboMana nextTurn(final Player ai, final SpellAbility sa) {
        return nextTurn(ai, sa, null);
    }

    /** {@link #nextTurn}, a sacrifice for {@code sa} read with {@code entering} on the battlefield by then (the
     *  go-off's read, {@link #of(Player, SpellAbility, SpellAbility, Card, boolean)}). */
    static ComboMana nextTurn(final Player ai, final SpellAbility sa, final Card entering) {
        return read(ai, sa, sa, entering, false, true);
    }

    // the mana of the sources, now (the pool, then each source whose mana ability canPlay()) or in our next turn; a
    // source is read against sa, its sacrifice against paidFor with entering on the battlefield
    private static ComboMana read(final Player ai, final SpellAbility sa, final SpellAbility paidFor,
            final Card entering, final boolean skipHeld, final boolean nextTurn) {
        final ComboMana m = new ComboMana();
        if (!nextTurn) {
            final ManaPool pool = ai.getManaPool();
            int coloured = 0;
            for (int i = 0; i < MagicColor.WUBRG.length; i++) {
                final int n = pool.getAmountOfColor(MagicColor.WUBRG[i]);
                m.units[MagicColor.WUBRG[i]] += n;
                m.pool[MagicColor.WUBRG[i]] += n;
                coloured += n;
            }
            m.units[COLOURLESS] += Math.max(0, pool.totalMana() - coloured);
            m.pool[COLOURLESS] += Math.max(0, pool.totalMana() - coloured);
        }
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
                if ((!nextTurn && !ma.canPlay()) || ma.getPayCosts().hasManaCost() || !ma.metConditions()
                        || !spendable(mp, sa) || instantSpeed(ma) || discardRefused(ai, ma)
                        || !ManaSacrifice.worthItBeforePaying(ai, ma, paidFor, entering)) {
                    continue;
                }
                final int amount = AbilityUtils.calculateAmount(src, ma.getParamOrDefault("Amount", "1"), ma);
                if (amount > 0) {
                    options.addAll(shapes(ma, mp, amount));
                }
            }
            if (!options.isEmpty()) {
                m.sources.put(src, options);
            }
            m.add(options);
        }
        return m;
    }

    // whether the mana may be spent on sa (AbilityManaPart.meetsManaRestrictions); with sa null, a payment that is no
    // spell or ability (an attack tax, ComboSignals' attack: follow-up 3, H2), whether the mana carries no spending
    // restriction at all, so a restricted source pays none of it (an under-count: a "nonSpell" source could)
    private static boolean spendable(final AbilityManaPart mp, final SpellAbility sa) {
        if (sa != null) {
            return mp.meetsManaRestrictions(sa);
        }
        return mp.getManaRestrictions().isEmpty() && mp.getExtraManaRestriction().isEmpty();
    }

    // an "activate only as an instant" mana ability (Lion's Eye Diamond, Diamond Lion, Charmed Pendant): playable at
    // priority (canPlay), but the AI's payer never taps one (ComputerUtilMana's getAIPlayableMana and
    // canPayShardWithSpellAbility skip it, as SpellAbility.isManaAbilityFor does), so it pays none of these costs
    // (follow-up final review, round 1)
    private static boolean instantSpeed(final SpellAbility ma) {
        return ma.getRestrictions() != null && ma.getRestrictions().isInstantSpeed();
    }

    // a discard for mana while the hand holds the finisher or the engine of a storm deck: the AI's payer refuses it
    // (ComputerUtilMana.canPayShardWithSpellAbility, the same three tests) for any spell, as it refuses Lion's Eye Diamond,
    // so it pays none of these costs. Asked after instantSpeed, so only Skirge Familiar and Overeager Apprentice get here
    private static boolean discardRefused(final Player ai, final SpellAbility ma) {
        return ma.getPayCosts().hasSpecificCostType(CostDiscard.class) && ai.getController() instanceof PlayerControllerAi
                && ComboPlan.holdsKillPiece(ai);
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

    /**
     * The sources to hold so that they alone pay {@code held} while the rest of the mana pays {@code rest} (follow-up
     * 3, H4: an opponent's Rhystic Study paid during a go-off, the emptier's sources held from its payer), or null when
     * no such split exists. This reading is the held cost's spell's, {@code others} a reading of the same moment for
     * {@code rest}'s payment, the two matched source by source. The floating pool goes to {@code rest} alone, since the
     * AI's payer spends it first (ComputerUtilMana.payManaCost's payManaCostFromPool), and a held source pays
     * {@code held} alone, any mana it makes past that cost not counted for {@code rest} (an under-count: Sol Ring's
     * second {C}, which the payer would leave floating). The fewest sources are held (each pays a pip at least, so
     * never more than {@code held}'s mana value); sources making the same mana under both readings are one kind (two
     * Islands), and the earliest kinds, then the earliest cards of each, in battlefield order, are held first: one
     * answer for one board, and nothing drawn.
     */
    List<Card> hold(final ManaCost held, final ComboMana others, final ManaCost rest) {
        final Map<String, List<Card>> byKind = new LinkedHashMap<>();
        for (final Map.Entry<Card, List<int[]>> e : sources.entrySet()) {
            final String kind = signature(e.getValue()) + "|" + signature(others.sources.get(e.getKey()));
            byKind.computeIfAbsent(kind, k -> new ArrayList<>()).add(e.getKey());
        }
        final List<List<Card>> kinds = new ArrayList<>(byKind.values());
        final int[] take = new int[kinds.size()];
        for (int total = 0; total <= held.getCMC(); total++) {
            if (choose(kinds, take, 0, total, held, others, rest)) {
                final List<Card> out = new ArrayList<>(total);
                for (int k = 0; k < kinds.size(); k++) {
                    out.addAll(kinds.get(k).subList(0, take[k]));
                }
                return out;
            }
        }
        return null;
    }

    // whether some count of each kind from the k-th on, left cards in all, splits the mana (splits), the earliest
    // kinds taking the most first; take holds the counts found
    private boolean choose(final List<List<Card>> kinds, final int[] take, final int k, final int left,
            final ManaCost held, final ComboMana others, final ManaCost rest) {
        if (k == kinds.size()) {
            return left == 0 && splits(kinds, take, held, others, rest);
        }
        for (int n = Math.min(left, kinds.get(k).size()); n >= 0; n--) {
            take[k] = n;
            if (choose(kinds, take, k + 1, left - n, held, others, rest)) {
                return true;
            }
        }
        take[k] = 0;
        return false;
    }

    // whether the first take[k] cards of each kind pay held alone, as this reading reads them, while every other source
    // of others' reading and its floating pool pay rest
    private boolean splits(final List<List<Card>> kinds, final int[] take, final ManaCost held,
            final ComboMana others, final ManaCost rest) {
        final ComboMana mine = new ComboMana();
        final List<Card> chosen = new ArrayList<>();
        for (int k = 0; k < kinds.size(); k++) {
            for (final Card c : kinds.get(k).subList(0, take[k])) {
                chosen.add(c);
                mine.add(sources.get(c));
            }
        }
        if (!mine.pays(held)) {
            return false;
        }
        final ComboMana theirs = new ComboMana();
        System.arraycopy(others.pool, 0, theirs.units, 0, theirs.units.length);
        for (final Map.Entry<Card, List<int[]>> e : others.sources.entrySet()) {
            if (!chosen.contains(e.getKey())) {
                theirs.add(e.getValue());
            }
        }
        return theirs.pays(rest);
    }

    // a source's options as text, the same for two sources making the same mana; empty for no source
    private static String signature(final List<int[]> options) {
        if (options == null) {
            return "";
        }
        final StringBuilder out = new StringBuilder();
        for (final int[] option : options) {
            out.append(Arrays.toString(option)).append(';');
        }
        return out.toString();
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
