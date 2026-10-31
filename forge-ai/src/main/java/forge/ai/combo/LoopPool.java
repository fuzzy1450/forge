package forge.ai.combo;

import java.util.ArrayList;
import java.util.List;

import forge.card.MagicColor;
import forge.card.mana.ManaAtom;
import forge.card.mana.ManaCost;
import forge.card.mana.ManaCostShard;
import forge.game.card.Card;
import forge.game.cost.Cost;
import forge.game.mana.ManaPool;
import forge.game.player.Player;
import forge.game.spellability.AbilityManaPart;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;

/**
 * The pool a mana loop builds and what it pays for (spec 2026-10-29-combo-aware-ai-loops-design.md section 6.3): the
 * driver runs cycles until the pool holds {@link #TARGET}, or what the next spend needs when that is less, and then
 * lets the stock AI play, paying from it.
 *
 * <p>Whether a cost can be paid is read here without the stock payer, which draws random numbers when it scans its
 * sources (ComputerUtilMana.isManaSourceReserved): the floating pool's mana by colour, one mana from each untapped land
 * of ours of a kind it makes, and the kinds the loop makes ({@link LoopCycles#colours}) as unlimited, since the loop
 * makes more of them on demand. With Chromatic Orrery on our battlefield any mana pays a coloured pip. Each source is
 * assigned to one pip (Hall's condition over the kinds the pips demand, ComboMana's reading). A cost is read as
 * printed, its X as none: taxes and reductions are the stock payer's (R21's measurement catches what a tax does to a
 * cycle).
 * Under-counts, the safe side: a Phyrexian pip read as its colour, a {2/C} pip as two generic mana when the loop does
 * not pay it, a land's mana as one of one kind. Nothing here draws a random number; its one write is
 * setActivatingPlayer on our battlefield's abilities, as the stock AI's readings do.
 */
final class LoopPool {
    /** The pool a loop builds to: Evan's 200 (section 6.3). */
    static final int TARGET = 200;

    private static final String ORRERY = "Chromatic Orrery";
    // the kinds of mana, as ManaAtom's low bits: W, U, B, R, G, and colourless
    private static final int KINDS = 6;
    private static final int ALL_KINDS = (1 << KINDS) - 1;

    private LoopPool() {
    }

    /** The mana floating in {@code ai}'s pool. */
    static int floating(final Player ai) {
        return ai.getManaPool().totalMana();
    }

    /**
     * What the pool is topped up to before the stock AI decides (section 6.3, "the stock AI's view"), while loop line
     * {@code l} runs: {@link #TARGET} while a card in our hand has an X spell we can pay ({@link #payable}); else the
     * largest mana value we can pay among the spells of the cards in our hand and the non-mana activated abilities on
     * our battlefield that can be activated now, are no step of {@code l}'s cycle and no sink's (Kinnan's dig,
     * {5}{G}{U}, is one such); else 0, nothing to spend on.
     */
    static int stockTarget(final Player ai, final ComboLines.Line l) {
        int most = 0;
        for (final Card c : ai.getCardsIn(ZoneType.Hand)) {
            for (final SpellAbility spell : c.getSpells()) {
                final ManaCost cost = printed(spell);
                if (!pays(ai, cost, l)) {
                    continue;
                }
                if (cost.countX() > 0) {
                    return TARGET;
                }
                most = Math.max(most, cost.getCMC());
            }
        }
        for (final Card c : ai.getCardsIn(ZoneType.Battlefield)) {
            if (isSink(c)) {
                continue;
            }
            for (final SpellAbility sa : c.getNonManaAbilities()) {
                if (!sa.isActivatedAbility() || LoopCycles.isStep(ai, l, sa)) {
                    continue;
                }
                sa.setActivatingPlayer(ai);
                if (!sa.canPlay() || !sa.checkRestrictions(ai)) {
                    continue;
                }
                final ManaCost cost = printed(sa);
                if (pays(ai, cost, l)) {
                    most = Math.max(most, cost.getCMC());
                }
            }
        }
        return most;
    }

    /** Whether some spell of our card {@code c} has a printed cost, its X as none, that the pool, the kinds loop line
     *  {@code l} makes (none for a null line), our untapped lands and Chromatic Orrery can pay. */
    static boolean payable(final Player ai, final Card c, final ComboLines.Line l) {
        for (final SpellAbility spell : c.getSpells()) {
            if (pays(ai, printed(spell), l)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether {@code cost} can be paid from {@code ai}'s floating pool and one mana of each untapped land of ours, with
     * the kinds loop line {@code l} makes as unlimited (a null line makes none: the driver's own check of a step it
     * pays now) and, with Chromatic Orrery on our battlefield, any mana spent as a coloured one. X is none.
     */
    static boolean pays(final Player ai, final ManaCost cost, final ComboLines.Line l) {
        final int loop = l == null ? 0 : LoopCycles.colours(ai, l);
        final boolean orrery = orrery(ai);
        final int[] need = new int[1 << KINDS];
        int generic = cost.getGenericCost();
        for (final ManaCostShard s : cost) {
            if (s.isOfKind(ManaAtom.IS_X)) {
                continue;
            }
            final int kinds = s.getShard() & ALL_KINDS;
            if (kinds == 0) {
                generic++;
            } else if ((kinds & loop) != 0
                    || (loop != 0 && (s.isOr2Generic() || (orrery && (kinds & ManaAtom.ALL_MANA_COLORS) != 0)))) {
                // the loop pays it: a kind it makes, two of its generic mana, or any coloured pip through Orrery
                continue;
            } else if (s.isOr2Generic()) {
                generic += 2;
            } else {
                need[kinds]++;
            }
        }
        if (loop != 0) {
            generic = 0;
        }
        return feasible(units(ai, orrery), need, generic);
    }

    // the mana of the pool and our untapped lands, as how many sources make one mana of each kind mask (index = the
    // mask; with Orrery every source's mask takes in every colour)
    private static int[] units(final Player ai, final boolean orrery) {
        final int[] units = new int[1 << KINDS];
        final ManaPool pool = ai.getManaPool();
        for (final byte colour : MagicColor.WUBRG) {
            units[widened(colour, orrery)] += pool.getAmountOfColor(colour);
        }
        units[widened(ManaAtom.COLORLESS, orrery)] += pool.getAmountOfColor((byte) ManaAtom.COLORLESS);
        for (final Card c : ai.getCardsIn(ZoneType.Battlefield)) {
            if (!c.isLand() || c.isTapped()) {
                continue;
            }
            final int kinds = landKinds(ai, c);
            if (kinds != 0) {
                units[widened(kinds, orrery)]++;
            }
        }
        return units;
    }

    // a source's kind mask, with Orrery able to pay any colour too
    private static int widened(final int kinds, final boolean orrery) {
        return orrery ? kinds | ManaAtom.ALL_MANA_COLORS : kinds;
    }

    // the kinds one of the land's mana abilities that can be activated now makes
    private static int landKinds(final Player ai, final Card land) {
        int kinds = 0;
        for (final SpellAbility ma : land.getManaAbilities()) {
            ma.setActivatingPlayer(ai);
            if (ma.canPlay()) {
                kinds |= made(ma);
            }
        }
        return kinds;
    }

    /** The kinds of mana {@code ma} makes, as ManaAtom's low bits: its fixed symbols, its combination's colours, or
     *  every colour for "any"; 0 for what it names otherwise (a reflected or chosen colour: an under-count). */
    static int made(final SpellAbility ma) {
        final AbilityManaPart mp = ma.getManaPart();
        if (mp == null) {
            return 0;
        }
        if (mp.isAnyMana()) {
            return ManaAtom.ALL_MANA_COLORS;
        }
        final String symbols = mp.isComboMana() ? mp.getComboColors(ma) : mp.getOrigProduced();
        int kinds = 0;
        for (final String symbol : symbols.trim().split(" ")) {
            if (symbol.length() == 1) {
                kinds |= ManaAtom.fromName(symbol.charAt(0)) & ALL_KINDS;
            }
        }
        return kinds;
    }

    // Hall's condition: for every set of the demanded kind masks, the sources able to pay one of them are at least as
    // many as the pips demanding them; and the sources are at least as many as every pip and generic mana together
    // (ComboMana's feasible, which this reading shares)
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

    // the printed mana of the spell's or ability's cost (none for a cost with no mana part)
    private static ManaCost printed(final SpellAbility sa) {
        final Cost cost = sa.getPayCosts();
        return cost == null ? ManaCost.ZERO : cost.getTotalMana();
    }

    // a sink's card (LoopSinks.BALLISTA, LoopSinks.THRASIOS), whose abilities are the driver's own steps (Walking
    // Ballista's, Thrasios's), not the stock AI's spend
    private static boolean isSink(final Card c) {
        final String name = ComboTracker.nameOf(c);
        return LoopSinks.BALLISTA.equals(name) || LoopSinks.THRASIOS.equals(name);
    }

    // whether Chromatic Orrery is on our battlefield: we may spend mana as though it were mana of any colour
    private static boolean orrery(final Player ai) {
        for (final Card c : ai.getCardsIn(ZoneType.Battlefield)) {
            if (ORRERY.equals(ComboTracker.nameOf(c))) {
                return true;
            }
        }
        return false;
    }
}
