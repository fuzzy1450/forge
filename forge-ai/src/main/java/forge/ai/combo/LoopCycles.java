package forge.ai.combo;

import java.util.ArrayList;
import java.util.List;

import forge.game.ability.ApiType;
import forge.game.card.Card;
import forge.game.player.Player;
import forge.game.replacement.ReplacementEffect;
import forge.game.replacement.ReplacementType;
import forge.game.spellability.SpellAbility;
import forge.game.trigger.Trigger;
import forge.game.trigger.TriggerType;
import forge.game.zone.ZoneType;

/**
 * One cycle of each loop kind (spec 2026-10-29-combo-aware-ai-loops-design.md section 6.1): what it nets, whether its
 * pieces are where it needs them, and its next action, read from the board alone at each decision, so a piece that
 * changes identity (a recast or flickered card is a new object) or a copy of one (two Basalt Monoliths) is read as the
 * card object it is now, never as the first card of a name. The rock untaps run here; the flicker, the recast and the
 * cast and bounce are no cycle yet (Tasks B7 and B8), so their lines net 0 and are never ready.
 *
 * <p>The rock untap: each rock of the line on our battlefield (a piece whose card taps for mana and untaps itself,
 * Basalt or Grim Monolith) is tapped for mana, its mana ability, and then pays its own untap from the pool. A boundary
 * is a board on which a rock is untapped, so the next step is a tap: a refill ends only there, the rock back where a
 * new cycle starts. With every rock tapped the next step pays an untap, the rock's whose untap has run the fewest
 * times this turn first, so two copies take turns. An untap may be paid by our untapped lands beside the pool: the
 * first of a go-off whose rock starts tapped, or a taxed one, whose cycle R21's measurement then fails.
 * Nothing here draws a random number or writes a card but setActivatingPlayer on the abilities it reads.
 */
final class LoopCycles {
    private LoopCycles() {
    }

    /**
     * The net mana one cycle of loop line {@code l} is expected to make, from what is on our battlefield now; 0 or less
     * means it cannot run. A rock untap: the rock's mana times 3 for each Nyxbloom Ancient of ours (each multiplier of
     * what a permanent we tap makes, read by its effect: Mana Reflection's is 2), plus one for each Kinnan of ours (an
     * active TapsForMana trigger that the rock's tap fires: Sakashima as a copy of Kinnan counts, a Kinnan that lost
     * its abilities does not), minus the untap's printed cost (Basalt 3, Grim 4). A cost tax is not read here (R21:
     * the driver measures each cycle).
     */
    static int net(final Player ai, final ComboLines.Line l) {
        if (l.loop() != LoopKind.ROCK_UNTAP) {
            return 0;
        }
        final List<Card> rocks = rocks(ai, l);
        if (rocks.isEmpty()) {
            return 0;
        }
        final Card rock = rocks.get(0);
        final int made = multiplied(ai, rock, manaOf(rock).amountOfManaGenerated(true));
        return made + kinnans(ai, rock) - untapOf(rock).getPayCosts().getTotalMana().getCMC();
    }

    /** Whether loop line {@code l} can run a cycle now: every piece of it on our battlefield, its {@link #net} above 0,
     *  and a rock of it untapped, or tapped with its untap payable from the pool and our untapped lands. */
    static boolean ready(final Player ai, final ComboLines.Line l) {
        if (l.loop() != LoopKind.ROCK_UNTAP) {
            return false;
        }
        for (final String name : l.names()) {
            if (onOurBattlefield(ai, name) == null) {
                return false;
            }
        }
        if (net(ai, l) <= 0) {
            return false;
        }
        for (final Card rock : rocks(ai, l)) {
            if (rock.isUntapped() || untapPayable(ai, rock)) {
                return true;
            }
        }
        return false;
    }

    /** The next action of loop line {@code l}'s cycle, its activator us and any target set; null when it has none: at
     *  a boundary the mana ability of the first untapped rock, in our battlefield's order; with every rock tapped the
     *  untap of the rock untapped the fewest times this turn (the first of those), whose untap the pool and our
     *  untapped lands pay. */
    static SpellAbility next(final Player ai, final ComboLines.Line l) {
        if (l.loop() != LoopKind.ROCK_UNTAP) {
            return null;
        }
        final List<Card> rocks = rocks(ai, l);
        for (final Card rock : rocks) {
            if (rock.isUntapped()) {
                final SpellAbility mana = manaOf(rock);
                mana.setActivatingPlayer(ai);
                return mana;
            }
        }
        SpellAbility best = null;
        int fewest = Integer.MAX_VALUE;
        for (final Card rock : rocks) {
            final SpellAbility untap = untapOf(rock);
            if (!untapPayable(ai, rock)) {
                continue;
            }
            final int untaps = untap.getActivationsThisTurn();
            if (untaps < fewest) {
                best = untap;
                fewest = untaps;
            }
        }
        if (best != null) {
            best.setActivatingPlayer(ai);
        }
        return best;
    }

    /** Whether loop line {@code l}'s cycle is at a boundary, where a refill may end and the stock AI play: a rock
     *  untap's, a rock of it untapped. */
    static boolean boundary(final Player ai, final ComboLines.Line l) {
        if (l.loop() != LoopKind.ROCK_UNTAP) {
            return false;
        }
        for (final Card rock : rocks(ai, l)) {
            if (rock.isUntapped()) {
                return true;
            }
        }
        return false;
    }

    /** Whether {@code sa} is a step of loop line {@code l}'s cycle, never the stock AI's spend: a rock's mana ability
     *  or its untap. */
    static boolean isStep(final Player ai, final ComboLines.Line l, final SpellAbility sa) {
        if (l.loop() != LoopKind.ROCK_UNTAP) {
            return false;
        }
        for (final Card rock : rocks(ai, l)) {
            if (rock == sa.getHostCard() && (sa.equals(manaOf(rock)) || sa.equals(untapOf(rock)))) {
                return true;
            }
        }
        return false;
    }

    /** The kinds of mana loop line {@code l}'s cycle makes without limit, as ManaAtom's low bits: a rock untap's, its
     *  rocks' (Kinnan's bonus is of the kind the rock made, and Nyxbloom triples what is made). */
    static int colours(final Player ai, final ComboLines.Line l) {
        if (l.loop() != LoopKind.ROCK_UNTAP) {
            return 0;
        }
        int kinds = 0;
        for (final Card rock : rocks(ai, l)) {
            kinds |= LoopPool.made(manaOf(rock));
        }
        return kinds;
    }

    // the rocks of the line on our battlefield, in its order: our cards of a piece the line names (by
    // ComboTracker.nameOf) that tap for mana and untap themselves
    static List<Card> rocks(final Player ai, final ComboLines.Line l) {
        final List<Card> out = new ArrayList<>(2);
        for (final Card c : ai.getCardsIn(ZoneType.Battlefield)) {
            if (l.names().contains(ComboTracker.nameOf(c)) && manaOf(c) != null && untapOf(c) != null) {
                out.add(c);
            }
        }
        return out;
    }

    // the card's {T} mana ability that adds mana of its own (an AB$ Mana), or null
    static SpellAbility manaOf(final Card c) {
        for (final SpellAbility sa : c.getManaAbilities()) {
            if (sa.getApi() == ApiType.Mana && sa.getPayCosts() != null && sa.getPayCosts().hasTapCost()) {
                return sa;
            }
        }
        return null;
    }

    // the card's activated untap of itself (no target, Defined absent or Self), or null
    static SpellAbility untapOf(final Card c) {
        for (final SpellAbility sa : c.getNonManaAbilities()) {
            if (sa.isActivatedAbility() && sa.getApi() == ApiType.Untap && !sa.usesTargeting()
                    && "Self".equals(sa.getParamOrDefault("Defined", "Self"))) {
                return sa;
            }
        }
        return null;
    }

    // whether the rock's untap, as the engine prices it now (a tax included), is paid by the pool and our untapped
    // lands: what the driver itself pays with now
    private static boolean untapPayable(final Player ai, final Card rock) {
        return LoopPool.pays(ai, ComboExecute.priced(ai, untapOf(rock)), null);
    }

    // our first card on our battlefield named name (by ComboTracker.nameOf, in its order), or null
    private static Card onOurBattlefield(final Player ai, final String name) {
        for (final Card c : ai.getCardsIn(ZoneType.Battlefield)) {
            if (name.equals(ComboTracker.nameOf(c))) {
                return c;
            }
        }
        return null;
    }

    // our Kinnans for the rock's tap: each TapsForMana trigger on our battlefield, not suppressed, that the rock tapped
    // by us fires (its ValidCard and Activator; Kinnan's own and a copy's)
    private static int kinnans(final Player ai, final Card rock) {
        int n = 0;
        for (final Card c : ai.getCardsIn(ZoneType.Battlefield)) {
            for (final Trigger tr : c.getTriggers()) {
                if (tr.getMode() == TriggerType.TapsForMana && !tr.isSuppressed()
                        && tr.matchesValidParam("ValidCard", rock) && tr.matchesValidParam("Activator", ai)) {
                    n++;
                }
            }
        }
        return n;
    }

    // what the rock's tap makes, made, after our multipliers: each ProduceMana replacement on our battlefield, not
    // suppressed, that multiplies what a permanent we tap makes (ReplaceMana: Nyxbloom Ancient's ReplaceAmount$ 3) and
    // whose ValidCard takes the rock, made times its amount
    private static int multiplied(final Player ai, final Card rock, final int made) {
        int out = made;
        for (final Card c : ai.getCardsIn(ZoneType.Battlefield)) {
            for (final ReplacementEffect re : c.getReplacementEffects()) {
                final SpellAbility with = re.getOverridingAbility();
                if (re.getMode() == ReplacementType.ProduceMana && !re.isSuppressed() && with != null
                        && with.getApi() == ApiType.ReplaceMana && re.matchesValidParam("ValidCard", rock)
                        && re.matchesValidParam("ValidActivator", ai)) {
                    out *= amount(with.getParam("ReplaceAmount"));
                }
            }
        }
        return out;
    }

    // a ReplaceAmount written as a whole number of one to three digits, else 1: no change of the amount is read (a
    // ReplaceMana that changes the mana's type, or an amount given by a count, an under-count)
    private static int amount(final String n) {
        if (n == null || n.isEmpty() || n.length() > 3) {
            return 1;
        }
        int v = 0;
        for (int i = 0; i < n.length(); i++) {
            final char ch = n.charAt(i);
            if (ch < '0' || ch > '9') {
                return 1;
            }
            v = v * 10 + (ch - '0');
        }
        return Math.max(1, v);
    }
}
