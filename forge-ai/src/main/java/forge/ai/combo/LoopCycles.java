package forge.ai.combo;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import com.google.common.collect.Iterables;

import forge.card.MagicColor;
import forge.card.mana.ManaAtom;
import forge.card.mana.ManaCost;
import forge.card.mana.ManaCostShard;
import forge.game.ability.ApiType;
import forge.game.card.Card;
import forge.game.card.CardUtil;
import forge.game.mana.ManaPool;
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
 * card object it is now, never as the first card of a name. All four kinds run here.
 *
 * <p>The rock untap: each rock of the line on our battlefield (a piece whose card taps for mana and untaps itself,
 * Basalt or Grim Monolith) is tapped for mana, its mana ability, and then pays its own untap from the pool. A boundary
 * is a board on which a rock is untapped, so the next step is a tap: a refill ends only there, the rock back where a
 * new cycle starts. With every rock tapped the next step pays an untap, the rock's whose untap has run the fewest
 * times this turn first, so two copies take turns. An untap may be paid by our untapped lands beside the pool: the
 * first of a go-off whose rock starts tapped, or a taxed one, whose cycle R21's measurement then fails.
 *
 * <p>The flicker (Palinchron paired with Deadeye Navigator, 1409-3542) and the return and recast (Palinchron beside
 * Nyxbloom Ancient, 3430-3542): each untapped land of ours is tapped for mana, one a decision, those that make no blue
 * first, so that the blue the cycle pays with is tapped last; then Palinchron moves, once the pool pays it: for the
 * flicker the {1}{U} Deadeye grants it while they are paired, for the recast its own {2}{U}{U} return and then, from
 * our hand, its {5}{U}{U} cast. Palinchron's entering untap ({@link LoopDriver#untapChoice}: our lands that make blue
 * first) and Deadeye's re-pair resolve as stock. A boundary is a board on which Palinchron is back (paired, for the
 * flicker) and every land its untap puts back (seven of our lands at most, those that make blue first) is untapped, so
 * the next step is a tap: once in each cycle, after the untap, and at a go-off's start with those lands untapped. Each
 * cycle makes Palinchron a new object, found afresh at every decision with its abilities; a token Palinchron is never
 * moved, since a flicker would exile it and a return leave it nowhere. A cycle starts or goes on only while the pool
 * and our lands to tap pay the rest of it, so the recast does not return a Palinchron that the pool and our lands
 * cannot cast again.
 *
 * <p>The cast and bounce (Hullbreaker Horror with a rock and a fill, x-2 to x-14): the row's two pieces besides
 * Hullbreaker and Kinnan, one on our battlefield and the other in our hand, trade places each half of a cycle. The one
 * on our battlefield is tapped for mana when it makes any, then the other is cast; Hullbreaker's cast trigger, which
 * the driver chooses ({@link LoopDriver#charmModes}), returns the first to our hand while the second is still on the
 * stack, and the second resolves untapped. With both in our hand (x-14's start) the line's first piece is cast first,
 * its trigger choosing nothing. A boundary is a board on which the line's first piece is untapped on our battlefield
 * and its second in our hand, once in each cycle. Each cast and each return makes a piece a new object, found afresh
 * at every decision; a token piece is none, since returned to our hand it would cease to exist.
 * Nothing here draws a random number or writes a card but setActivatingPlayer on the abilities it reads, and Mox
 * Amber's colour, set as the express choice of the tap it returns ({@link #reflectedColour}).
 */
final class LoopCycles {
    /** Palinchron, the piece the flicker and the recast move each cycle, as the table names it. */
    static final String PALINCHRON = "Palinchron";
    /** Deadeye Navigator, whose soulbond pair is granted the flicker, as the table names it. */
    static final String DEADEYE = "Deadeye Navigator";
    /** Hullbreaker Horror, whose cast trigger returns the cast and bounce's other piece to our hand, as the table names
     *  it. */
    static final String HULLBREAKER = "Hullbreaker Horror";
    // Kinnan, Bonder Prodigy: a piece of x-4, x-12 and x-14 for his bonus alone, never one the cast and bounce casts
    private static final String KINNAN = "Kinnan, Bonder Prodigy";
    // Palinchron's entering untap: up to seven lands
    private static final int UNTAPS = 7;
    // metalcraft (Mox Opal's tap): three artifacts of ours
    private static final int METALCRAFT = 3;

    private LoopCycles() {
    }

    /**
     * The net mana one cycle of loop line {@code l} is expected to make, from what is on our battlefield now; 0 or less
     * means it cannot run. A rock untap: the rock's mana times 3 for each Nyxbloom Ancient of ours (each multiplier of
     * what a permanent we tap makes, read by its effect: Mana Reflection's is 2), plus one for each Kinnan of ours (an
     * active TapsForMana trigger that the rock's tap fires: Sakashima as a copy of Kinnan counts, a Kinnan that lost
     * its abilities does not), minus the untap's printed cost (Basalt 3, Grim 4). The flicker: what the lands
     * Palinchron's untap puts back make (seven of our lands at most, each one's mana times our multipliers: 3 for each
     * Nyxbloom; Kinnan's bonus is for nonland permanents), minus the flicker's printed {1}{U}; 0 while our
     * Palinchron has no flicker (Deadeye Navigator unpaired, or paired with another creature) and with no land of ours
     * that makes blue. The recast: what those lands make, minus Palinchron's printed return and cast, {2}{U}{U} and
     * {5}{U}{U} (+10 with seven lands and one Nyxbloom); 0 with no Palinchron of ours on our battlefield or in our
     * hand. The cast and bounce (I6 section 2.3's r + K + m + K x [m > 0] - cost(A) - cost(B)): what one tap of each of
     * its two pieces makes ({@link #tapOf}, times our multipliers, one for each Kinnan of ours its tap fires, less the
     * tap's own cost: Simic Signet's {1}; none for a piece that makes no mana in the cycle: Sensei's Divining Top,
     * Scroll Rack, Mox Amber without a legend of ours, Mox Opal short of metalcraft), less both pieces' printed casts;
     * read from each piece's card on our battlefield or in our hand, so the same at every point of the cycle; 0 with a
     * piece neither there nor there. A cost tax is not read here (R21: the driver measures each cycle).
     */
    static int net(final Player ai, final ComboLines.Line l) {
        if (l.loop() == LoopKind.BOUNCE) {
            final List<Card> cards = bounceCards(ai, l);
            if (cards == null) {
                return 0;
            }
            int net = 0;
            for (final Card c : cards) {
                net += tapYield(ai, l, c) - printed(castOf(c)).getCMC();
            }
            return net;
        }
        if (l.loop() == LoopKind.FLICKER) {
            final SpellAbility flicker = flicker(ai);
            if (flicker == null) {
                return 0;
            }
            final List<Card> back = putBack(ai);
            return blueMade(ai, back) == 0 ? 0 : made(ai, back) - printed(flicker).getCMC();
        }
        if (l.loop() == LoopKind.RECAST) {
            final Card p = recastPalinchron(ai);
            final SpellAbility back = p == null ? null : returnOf(p);
            final SpellAbility cast = p == null ? null : castOf(p);
            if (back == null || cast == null) {
                return 0;
            }
            return made(ai, putBack(ai)) - printed(back).getCMC() - printed(cast).getCMC();
        }
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

    /**
     * Whether loop line {@code l} can run a cycle now. A rock untap: every piece of it on our battlefield, its
     * {@link #net} above 0, and a rock of it untapped, or tapped with its untap payable from the pool and our untapped
     * lands. The flicker: every piece on our battlefield, its net above 0, and the flicker, as the engine prices it
     * now, paid by the pool and our lands to tap (each making its mana times our multipliers). The recast: Nyxbloom
     * Ancient, every piece but Palinchron, on our battlefield, its net above 0, the lands Palinchron's untap puts back
     * making the blue of its return and cast (four a cycle: two Islands with one Nyxbloom), and the rest of the cycle
     * paid by the pool and our lands to tap: with a Palinchron of ours in our hand and no land of ours left to tap, the
     * cycle past its return, its cast as priced now; else, with Palinchron on our battlefield, its return as priced now
     * and its cast as printed. Palinchron in our hand beside lands to tap, none on our battlefield, is no start (spec
     * 6.2: the pieces on the battlefield). The cast and bounce: Hullbreaker Horror, and Kinnan where the row names him,
     * on our battlefield; one of its two pieces on our battlefield (a card, not a token) and the other in our hand, or
     * both in our hand with neither on our battlefield (R19: x-14's start, the first to be cast first); its net above
     * 0; and the next cast, as the engine prices it now, paid by the pool, our untapped lands and the tap of the piece
     * on our battlefield when the cycle taps it first.
     */
    static boolean ready(final Player ai, final ComboLines.Line l) {
        if (l.loop() == LoopKind.BOUNCE) {
            return bounceReady(ai, l);
        }
        if (l.loop() == LoopKind.FLICKER) {
            for (final String name : l.names()) {
                if (onOurBattlefield(ai, name) == null) {
                    return false;
                }
            }
            if (net(ai, l) <= 0) {
                return false;
            }
            final ManaCost flicker = ComboExecute.priced(ai, flicker(ai));
            return completes(ai, flicker.getCMC(), blue(flicker));
        }
        if (l.loop() == LoopKind.RECAST) {
            return recastReady(ai, l);
        }
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

    /** The next action of loop line {@code l}'s cycle, its activator us and any target set; null when it has none. A
     *  rock untap: at a boundary the mana ability of the first untapped rock, in our battlefield's order; with every
     *  rock tapped the untap of the rock untapped the fewest times this turn (the first of those), whose untap the pool
     *  and our untapped lands pay. The flicker: the mana ability of our next land to tap, those that make no blue
     *  first; with none, the flicker of our Palinchron when the pool pays it as the engine prices it now. The recast:
     *  with a Palinchron of ours in our hand and no land of ours left to tap, its cast, when the pool pays it so; else
     *  the next land's tap, then the return of our Palinchron on our battlefield, when the pool pays it so. The cast
     *  and bounce: the tap of the piece on our battlefield ({@link #tapOf}) while it is untapped and makes mana in the
     *  cycle, the driver may make it now and the pool and our untapped lands pay its cost, Mox Amber's set first to the
     *  colour the next cast needs ({@link #reflectedColour}), so that no colour is left over from the last payment
     *  (section 6.3); else the cast of the piece in our hand (with both there, the line's first), when the pool and our
     *  untapped lands pay it as the engine prices it now. */
    static SpellAbility next(final Player ai, final ComboLines.Line l) {
        if (l.loop() == LoopKind.BOUNCE) {
            final Bounce s = bounce(ai, l);
            if (s == null) {
                return null;
            }
            final SpellAbility tap = s.here() == null ? null : tappable(ai, l, s.here());
            if (tap != null) {
                if (tap.getApi() == ApiType.ManaReflected) {
                    final byte colour = reflectedColour(ai, tap, ComboExecute.priced(ai, castOf(s.there())));
                    tap.getManaPart().setExpressChoice(MagicColor.toShortString(colour));
                }
                return tap;
            }
            return paid(ai, castOf(s.there()));
        }
        if (l.loop() == LoopKind.FLICKER) {
            final SpellAbility flicker = flicker(ai);
            return flicker == null ? null : tapOrMove(ai, flicker);
        }
        if (l.loop() == LoopKind.RECAST) {
            final Card inHand = firstInHand(ai);
            if (inHand != null && landTap(ai) == null) {
                final SpellAbility cast = castOf(inHand);
                return cast == null ? null : paid(ai, cast);
            }
            final Card p = palinchron(ai);
            final SpellAbility back = p == null ? null : returnOf(p);
            return back == null ? null : tapOrMove(ai, back);
        }
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
     *  untap's, a rock of it untapped; the flicker's, our Palinchron with its flicker, and the recast's, our Palinchron
     *  on our battlefield, each with every land Palinchron's untap puts back untapped; the cast and bounce's, the
     *  line's first piece untapped on our battlefield (a card, not a token) and its second in our hand, which comes
     *  once a cycle (the pieces trade places each half of it). */
    static boolean boundary(final Player ai, final ComboLines.Line l) {
        if (l.loop() == LoopKind.BOUNCE) {
            final List<String> pieces = bouncePieces(l);
            return pieces.size() == 2 && untappedPiece(ai, pieces.get(0)) != null
                    && inOurHand(ai, pieces.get(1)) != null;
        }
        if (l.loop() == LoopKind.FLICKER) {
            return flicker(ai) != null && putBackUntapped(ai);
        }
        if (l.loop() == LoopKind.RECAST) {
            return palinchron(ai) != null && putBackUntapped(ai);
        }
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
     *  or its untap; for the flicker and the recast alike, which each move our Palinchron, a mana ability of a land of
     *  ours, the flicker Deadeye Navigator grants (on Palinchron, or on Deadeye itself), Palinchron's own return and
     *  its cast. ChangeZoneAi makes those moves only to save a creature from removal on the stack, so none of them is
     *  ever something the stock AI spends the pool on. For the cast and bounce, a mana ability of a card of ours of one
     *  of its two pieces, and the cast of one from our hand (which the cycle makes itself: LoopDriver.holdsCycleCast).
     */
    static boolean isStep(final Player ai, final ComboLines.Line l, final SpellAbility sa) {
        if (l.loop() == LoopKind.BOUNCE) {
            final Card host = sa.getHostCard();
            if (host == null || !ai.equals(host.getController())
                    || !bouncePieces(l).contains(ComboTracker.nameOf(host))) {
                return false;
            }
            return sa.isManaAbility() || (sa.isSpell() && host.isInZone(ZoneType.Hand));
        }
        if (l.loop() == LoopKind.FLICKER || l.loop() == LoopKind.RECAST) {
            return palinchronStep(ai, sa);
        }
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
     *  rocks' (Kinnan's bonus is of the kind the rock made, and Nyxbloom triples what is made); the flicker's and the
     *  recast's, those of the lands Palinchron's untap puts back, blue only while they make more of it than the cycle
     *  pays (the flicker's {U}, the recast's four), none without the cycle's abilities; the cast and bounce's, those of
     *  its pieces' taps that make mana in the cycle ({@link #tapOf}), Mox Amber's the colour its tap is set to before
     *  the other piece's cast ({@link #reflectedColour}). */
    static int colours(final Player ai, final ComboLines.Line l) {
        if (l.loop() == LoopKind.BOUNCE) {
            final List<Card> cards = bounceCards(ai, l);
            if (cards == null) {
                return 0;
            }
            int kinds = 0;
            for (int i = 0; i < cards.size(); i++) {
                final Card c = cards.get(i);
                final SpellAbility ma = tapOf(c);
                if (ma == null || !makesMana(ai, l, ma)) {
                    continue;
                }
                kinds |= ma.getApi() == ApiType.ManaReflected
                        ? reflectedColour(ai, ma, ComboExecute.priced(ai, castOf(cards.get(1 - i))))
                        : LoopPool.made(ma);
            }
            return kinds;
        }
        if (l.loop() == LoopKind.FLICKER || l.loop() == LoopKind.RECAST) {
            final int pays = cycleBlue(ai, l);
            if (pays < 0) {
                return 0;
            }
            final List<Card> back = putBack(ai);
            int kinds = 0;
            for (final Card c : back) {
                kinds |= LoopPool.made(landMana(c));
            }
            return blueMade(ai, back) > pays ? kinds : kinds & ~ManaAtom.BLUE;
        }
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

    /** The mana ability of land {@code c} a Palinchron cycle taps it with: its first that makes blue, else its first;
     *  null with none. */
    static SpellAbility landMana(final Card c) {
        SpellAbility first = null;
        for (final SpellAbility ma : c.getManaAbilities()) {
            if (blueMana(ma)) {
                return ma;
            }
            if (first == null) {
                first = ma;
            }
        }
        return first;
    }

    /** Whether land {@code c} makes blue: one of its mana abilities does ({@link LoopPool#made}), tapped or not. */
    static boolean makesBlue(final Card c) {
        final SpellAbility ma = landMana(c);
        return ma != null && blueMana(ma);
    }

    // whether the mana ability makes blue
    private static boolean blueMana(final SpellAbility ma) {
        return (LoopPool.made(ma) & ManaAtom.BLUE) != 0;
    }

    // our first nontoken Palinchron on our battlefield (by ComboTracker.nameOf, in its order), or null: a token is
    // never moved, since a flicker would exile it and a return leave it nowhere
    private static Card palinchron(final Player ai) {
        for (final Card c : ai.getCardsIn(ZoneType.Battlefield)) {
            if (PALINCHRON.equals(ComboTracker.nameOf(c)) && !c.isToken()) {
                return c;
            }
        }
        return null;
    }

    // our first Palinchron in our hand, or null
    private static Card firstInHand(final Player ai) {
        for (final Card c : ai.getCardsIn(ZoneType.Hand)) {
            if (PALINCHRON.equals(ComboTracker.nameOf(c))) {
                return c;
            }
        }
        return null;
    }

    // the recast's Palinchron: ours on our battlefield (palinchron), else our first in our hand, the cycle past its
    // return; null with neither
    private static Card recastPalinchron(final Player ai) {
        final Card here = palinchron(ai);
        return here != null ? here : firstInHand(ai);
    }

    // the flicker of our Palinchron, which Deadeye Navigator grants it while they are paired: of our nontoken
    // Palinchrons on our battlefield, in its order, the first one's that has it (of two copies, the paired one's); null
    // with none
    private static SpellAbility flicker(final Player ai) {
        for (final Card c : ai.getCardsIn(ZoneType.Battlefield)) {
            if (!PALINCHRON.equals(ComboTracker.nameOf(c)) || c.isToken()) {
                continue;
            }
            for (final SpellAbility sa : c.getNonManaAbilities()) {
                if (isFlicker(sa)) {
                    return sa;
                }
            }
        }
        return null;
    }

    // the flicker: an activated ChangeZone to exile that a static grants (Deadeye Navigator's, to each of its pair)
    private static boolean isFlicker(final SpellAbility sa) {
        return sa.isActivatedAbility() && sa.getApi() == ApiType.ChangeZone && sa.getGrantorStatic() != null
                && "Exile".equals(sa.getParam("Destination"));
    }

    // Palinchron's own return: an activated ChangeZone to its owner's hand that no static grants
    private static boolean isReturn(final SpellAbility sa) {
        return sa.isActivatedAbility() && sa.getApi() == ApiType.ChangeZone && sa.getGrantorStatic() == null
                && "Hand".equals(sa.getParam("Destination"));
    }

    // the card's own return (isReturn), or null
    private static SpellAbility returnOf(final Card c) {
        for (final SpellAbility sa : c.getNonManaAbilities()) {
            if (isReturn(sa)) {
                return sa;
            }
        }
        return null;
    }

    // the card's cast: the first of its spells, or null
    private static SpellAbility castOf(final Card c) {
        return Iterables.getFirst(c.getSpells(), null);
    }

    // a step of the Palinchron cycles (see isStep): a mana ability of a land of ours; the flicker of our Palinchron or
    // of our Deadeye Navigator; our Palinchron's own return, or its cast
    private static boolean palinchronStep(final Player ai, final SpellAbility sa) {
        final Card host = sa.getHostCard();
        if (host == null || !ai.equals(host.getController())) {
            return false;
        }
        if (sa.isManaAbility()) {
            return host.isLand();
        }
        final String name = ComboTracker.nameOf(host);
        if (PALINCHRON.equals(name)) {
            return isFlicker(sa) || isReturn(sa) || sa.isSpell();
        }
        return DEADEYE.equals(name) && isFlicker(sa);
    }

    // our lands on our battlefield that tap for mana (landMana), in its order: the lands the Palinchron cycles tap
    private static List<Card> lands(final Player ai) {
        final List<Card> out = new ArrayList<>();
        for (final Card c : ai.getCardsIn(ZoneType.Battlefield)) {
            if (c.isLand() && landMana(c) != null) {
                out.add(c);
            }
        }
        return out;
    }

    // the lands Palinchron's entering untap puts back each cycle, in LoopDriver.untapChoice's order: of our lands
    // (lands) those that make blue, then the rest, each in our battlefield's order, the first seven of them
    private static List<Card> putBack(final Player ai) {
        final List<Card> back = new ArrayList<>();
        final List<Card> rest = new ArrayList<>();
        for (final Card c : lands(ai)) {
            if (makesBlue(c)) {
                back.add(c);
            } else {
                rest.add(c);
            }
        }
        back.addAll(rest);
        return back.size() > UNTAPS ? back.subList(0, UNTAPS) : back;
    }

    // the mana a cycle taps land c for: its mana ability's (landMana), times our multipliers
    private static int yieldOf(final Player ai, final Card c) {
        return multiplied(ai, c, landMana(c).amountOfManaGenerated(true));
    }

    // what the lands make together, each its yield
    private static int made(final Player ai, final List<Card> lands) {
        int n = 0;
        for (final Card c : lands) {
            n += yieldOf(ai, c);
        }
        return n;
    }

    // what those of the lands that make blue make together, each its yield
    private static int blueMade(final Player ai, final List<Card> lands) {
        int n = 0;
        for (final Card c : lands) {
            if (makesBlue(c)) {
                n += yieldOf(ai, c);
            }
        }
        return n;
    }

    // land c's mana ability the cycle taps now (landMana): c a land of ours, untapped, its ability one the driver may
    // make now (LoopDriver.allowed, which makes us its activator); null otherwise
    private static SpellAbility toTap(final Player ai, final Card c) {
        if (!c.isLand() || c.isTapped()) {
            return null;
        }
        final SpellAbility ma = landMana(c);
        return ma != null && LoopDriver.allowed(ai, ma) ? ma : null;
    }

    // the cycle's next tap: of our lands to tap (toTap), in our battlefield's order, the first that makes no blue, else
    // the first that does, so that the blue the cycle pays with stays untapped the longest; null with none
    private static SpellAbility landTap(final Player ai) {
        SpellAbility blue = null;
        for (final Card c : ai.getCardsIn(ZoneType.Battlefield)) {
            final SpellAbility ma = toTap(ai, c);
            if (ma == null) {
                continue;
            }
            if (!blueMana(ma)) {
                return ma;
            }
            if (blue == null) {
                blue = ma;
            }
        }
        return blue;
    }

    // the step before Palinchron's move: a land to tap (landTap), else the move when the pool pays it (paid)
    private static SpellAbility tapOrMove(final Player ai, final SpellAbility move) {
        final SpellAbility tap = landTap(ai);
        return tap != null ? tap : paid(ai, move);
    }

    // sa, us its activator, when the pool and our untapped lands pay it as the engine prices it now; else null
    private static SpellAbility paid(final Player ai, final SpellAbility sa) {
        return LoopPool.pays(ai, ComboExecute.priced(ai, sa), null) ? sa : null;
    }

    // whether every land Palinchron's untap puts back (putBack) is untapped: the cycle at its start, its next step a
    // tap. A land the cycle cannot tap (a summoning-sick Dryad Arbor) stays untapped, so it keeps no boundary away
    private static boolean putBackUntapped(final Player ai) {
        for (final Card c : putBack(ai)) {
            if (c.isTapped()) {
                return false;
            }
        }
        return true;
    }

    // whether the pool and our lands to tap (toTap, each making its yield) pay mana of mana value mana, blue of it in
    // blue: the pool's mana and the lands' together at least mana, and the blue of both at least blue. The Palinchron
    // cycles' costs ask for blue and generic mana alone, for which these two sums are Hall's condition
    private static boolean completes(final Player ai, final int mana, final int blue) {
        final ManaPool pool = ai.getManaPool();
        int all = pool.totalMana();
        int blues = pool.getAmountOfColor(MagicColor.BLUE);
        for (final Card c : ai.getCardsIn(ZoneType.Battlefield)) {
            final SpellAbility ma = toTap(ai, c);
            if (ma != null) {
                final int y = yieldOf(ai, c);
                all += y;
                if (blueMana(ma)) {
                    blues += y;
                }
            }
        }
        return all >= mana && blues >= blue;
    }

    // the recast's readiness (see ready)
    private static boolean recastReady(final Player ai, final ComboLines.Line l) {
        for (final String name : l.names()) {
            if (!PALINCHRON.equals(name) && onOurBattlefield(ai, name) == null) {
                return false;
            }
        }
        if (net(ai, l) <= 0) {
            return false;
        }
        final Card p = recastPalinchron(ai);
        final ManaCost cast = printed(castOf(p));
        if (blueMade(ai, putBack(ai)) < blue(printed(returnOf(p))) + blue(cast)) {
            return false;
        }
        final Card inHand = firstInHand(ai);
        if (inHand != null && landTap(ai) == null) {
            // past its return: no land of ours left to tap, so the pool pays its cast
            final ManaCost castNow = ComboExecute.priced(ai, castOf(inHand));
            return completes(ai, castNow.getCMC(), blue(castNow));
        }
        final Card here = palinchron(ai);
        if (here == null) {
            // in our hand beside lands to tap: no start
            return false;
        }
        final ManaCost back = ComboExecute.priced(ai, returnOf(here));
        return completes(ai, back.getCMC() + cast.getCMC(), blue(back) + blue(cast));
    }

    // the blue the cycle of a Palinchron line pays, as printed: the flicker's, or the recast's return's and cast's
    // together; -1 when the cycle's abilities are missing (no flicker; no Palinchron of ours with both)
    private static int cycleBlue(final Player ai, final ComboLines.Line l) {
        if (l.loop() == LoopKind.FLICKER) {
            final SpellAbility flicker = flicker(ai);
            return flicker == null ? -1 : blue(printed(flicker));
        }
        final Card p = recastPalinchron(ai);
        final SpellAbility back = p == null ? null : returnOf(p);
        final SpellAbility cast = p == null ? null : castOf(p);
        return back == null || cast == null ? -1 : blue(printed(back)) + blue(printed(cast));
    }

    // ---- the cast and bounce ----

    /** The cast and bounce line {@code l}'s two pieces, the cards its cycle taps and casts, in the row's order: every
     *  piece it names but Hullbreaker Horror and Kinnan (ComboSignals' reading of the cycle's casts); immutable. */
    static List<String> bouncePieces(final ComboLines.Line l) {
        final List<String> out = new ArrayList<>(2);
        for (final String name : l.names()) {
            if (!HULLBREAKER.equals(name) && !KINNAN.equals(name)) {
                out.add(name);
            }
        }
        return List.copyOf(out);
    }

    /** The mana ability the cast and bounce taps our card {@code c} with: of its mana abilities whose cost taps it, the
     *  first that makes colourless mana alone (Talisman of Curiosity's {C}, where its {G} or {U} would deal 1 damage to
     *  us), else the first; null with none (Sensei's Divining Top, Scroll Rack). */
    static SpellAbility tapOf(final Card c) {
        SpellAbility first = null;
        for (final SpellAbility ma : c.getManaAbilities()) {
            if (!ma.getPayCosts().hasTapCost()) {
                continue;
            }
            if (LoopPool.made(ma) == ManaAtom.COLORLESS) {
                return ma;
            }
            if (first == null) {
                first = ma;
            }
        }
        return first;
    }

    /** Our card of the cast and bounce's piece {@code name} that Hullbreaker Horror's trigger returns to our hand: of
     *  our cards of it on our battlefield, tokens aside, in its order, the first that is tapped (the one the cycle has
     *  tapped), else the first; null with none. */
    static Card bounceTarget(final Player ai, final String name) {
        Card first = null;
        for (final Card c : ai.getCardsIn(ZoneType.Battlefield)) {
            if (!name.equals(ComboTracker.nameOf(c)) || c.isToken()) {
                continue;
            }
            if (c.isTapped()) {
                return c;
            }
            if (first == null) {
                first = c;
            }
        }
        return first;
    }

    /** The colour our reflected mana ability {@code ma} (Mox Amber's) makes at the cast and bounce's tap, which the
     *  cycle sets as its express choice (section 6.3; a reflected-mana choice is otherwise left over from the last
     *  payment): of the colours it can reflect now ({@link CardUtil#getReflectableManaColors}: Amber's, those of our
     *  legendary creatures and planeswalkers), in WUBRG order, the first that a pip of {@code cost}, the cast it pays
     *  for next, needs, else the first; 0 with none, so never a colour no legend of ours has. */
    static byte reflectedColour(final Player ai, final SpellAbility ma, final ManaCost cost) {
        ma.setActivatingPlayer(ai);
        final Set<String> can = CardUtil.getReflectableManaColors(ma);
        byte first = 0;
        for (final byte colour : MagicColor.WUBRG) {
            if (!can.contains(MagicColor.toLongString(colour))) {
                continue;
            }
            if (needs(cost, colour)) {
                return colour;
            }
            if (first == 0) {
                first = colour;
            }
        }
        return first;
    }

    // whether a pip of the cost is of that colour
    private static boolean needs(final ManaCost cost, final byte colour) {
        for (final ManaCostShard s : cost) {
            if ((s.getColorMask() & colour) != 0) {
                return true;
            }
        }
        return false;
    }

    // the cast and bounce's state now: its piece on our battlefield (here; null when both are in our hand) and its
    // piece in our hand to cast next (there)
    private record Bounce(Card here, Card there) {
    }

    // the cast and bounce's state now (see Bounce), its pieces read in the row's order (bounceCards: two, each with a
    // cast): the first on our battlefield (onBattlefieldPiece) and the second in our hand, or the second there and the
    // first in our hand; else both in our hand, so neither on our battlefield, the first to be cast first (R19: x-14's
    // start); null otherwise (both on our battlefield; a piece gone)
    private static Bounce bounce(final Player ai, final ComboLines.Line l) {
        if (bounceCards(ai, l) == null) {
            return null;
        }
        final List<String> pieces = bouncePieces(l);
        final Card aHere = onBattlefieldPiece(ai, pieces.get(0));
        final Card bHere = onBattlefieldPiece(ai, pieces.get(1));
        final Card aHand = inOurHand(ai, pieces.get(0));
        final Card bHand = inOurHand(ai, pieces.get(1));
        if (aHere != null && bHand != null) {
            return new Bounce(aHere, bHand);
        }
        if (bHere != null && aHand != null) {
            return new Bounce(bHere, aHand);
        }
        // with both in our hand, neither can be on our battlefield here: either would have made a state above
        return aHand != null && bHand != null ? new Bounce(null, aHand) : null;
    }

    // the cast and bounce's two pieces as cards, in the row's order: each one's card on our battlefield
    // (onBattlefieldPiece), else in our hand; null unless the row names exactly two pieces (bouncePieces) and each has
    // such a card with a cast
    private static List<Card> bounceCards(final Player ai, final ComboLines.Line l) {
        final List<String> pieces = bouncePieces(l);
        if (pieces.size() != 2) {
            return null;
        }
        final List<Card> out = new ArrayList<>(2);
        for (final String name : pieces) {
            Card c = onBattlefieldPiece(ai, name);
            if (c == null) {
                c = inOurHand(ai, name);
            }
            if (c == null || castOf(c) == null) {
                return null;
            }
            out.add(c);
        }
        return out;
    }

    // our card of the piece on our battlefield, tokens aside: the first untapped one (untappedPiece), else the first;
    // null with none
    private static Card onBattlefieldPiece(final Player ai, final String name) {
        final Card untapped = untappedPiece(ai, name);
        if (untapped != null) {
            return untapped;
        }
        for (final Card c : ai.getCardsIn(ZoneType.Battlefield)) {
            if (name.equals(ComboTracker.nameOf(c)) && !c.isToken()) {
                return c;
            }
        }
        return null;
    }

    // our first untapped card of the piece on our battlefield, tokens aside, in its order; null with none
    private static Card untappedPiece(final Player ai, final String name) {
        for (final Card c : ai.getCardsIn(ZoneType.Battlefield)) {
            if (name.equals(ComboTracker.nameOf(c)) && !c.isToken() && c.isUntapped()) {
                return c;
            }
        }
        return null;
    }

    // our first card named name in our hand (by ComboTracker.nameOf), or null
    private static Card inOurHand(final Player ai, final String name) {
        for (final Card c : ai.getCardsIn(ZoneType.Hand)) {
            if (name.equals(ComboTracker.nameOf(c))) {
                return c;
            }
        }
        return null;
    }

    // what one tap of our card c makes in the cast and bounce, net of the tap's own cost (Simic Signet's {1}): the mana
    // of the ability the cycle taps it with (tapOf) times our multipliers, and one for each Kinnan of ours its tap
    // fires; 0 when that ability makes nothing in the cycle (makesMana), where the cycle does not tap it
    private static int tapYield(final Player ai, final ComboLines.Line l, final Card c) {
        final SpellAbility ma = tapOf(c);
        if (ma == null || !makesMana(ai, l, ma)) {
            return 0;
        }
        ma.setActivatingPlayer(ai);
        return multiplied(ai, c, ma.amountOfManaGenerated(true)) + kinnans(ai, c) - printed(ma).getCMC();
    }

    // whether the ability ma of a piece of ours makes mana when the cast and bounce taps it, that piece on our
    // battlefield and the line's other piece in our hand: a reflected one only with a colour to reflect (Mox Amber with
    // a legend of ours); any other only when it makes some kind (not Arcane Signet in a game with no commander), and
    // one that needs metalcraft (Mox Opal) only with three artifacts of ours then (artifactsWith)
    private static boolean makesMana(final Player ai, final ComboLines.Line l, final SpellAbility ma) {
        if (ma.getApi() == ApiType.ManaReflected) {
            ma.setActivatingPlayer(ai);
            return !CardUtil.getReflectableManaColors(ma).isEmpty();
        }
        if (LoopPool.made(ma) == 0) {
            return false;
        }
        return ma.getRestrictions() == null || !ma.getRestrictions().isMetalcraft()
                || artifactsWith(ai, l) >= METALCRAFT;
    }

    // the artifacts of ours with a piece of the cast and bounce on our battlefield and the other in our hand: that
    // piece, an artifact (Mox Opal), and ours on our battlefield that are no piece of the line
    private static int artifactsWith(final Player ai, final ComboLines.Line l) {
        final List<String> pieces = bouncePieces(l);
        int n = 1;
        for (final Card a : ai.getCardsIn(ZoneType.Battlefield)) {
            if (a.isArtifact() && !pieces.contains(ComboTracker.nameOf(a))) {
                n++;
            }
        }
        return n;
    }

    // the tap of the piece here, us its activator, when the cycle taps it now: here untapped, the ability it is tapped
    // with (tapOf) one that makes mana in the cycle (makesMana) and that the driver may make now (LoopDriver.allowed),
    // its cost paid by the pool and our untapped lands (paid); else null. It writes no colour (next sets Mox Amber's)
    private static SpellAbility tappable(final Player ai, final ComboLines.Line l, final Card here) {
        if (here.isTapped()) {
            return null;
        }
        final SpellAbility ma = tapOf(here);
        if (ma == null || !makesMana(ai, l, ma) || !LoopDriver.allowed(ai, ma)) {
            return null;
        }
        return paid(ai, ma);
    }

    // the cast and bounce's readiness (see ready)
    private static boolean bounceReady(final Player ai, final ComboLines.Line l) {
        final List<String> pieces = bouncePieces(l);
        for (final String name : l.names()) {
            if (!pieces.contains(name) && onOurBattlefield(ai, name) == null) {
                return false;
            }
        }
        final Bounce s = bounce(ai, l);
        if (s == null || net(ai, l) <= 0) {
            return false;
        }
        final SpellAbility tap = s.here() == null ? null : tappable(ai, l, s.here());
        final int extra = tap == null ? 0 : tapYield(ai, l, s.here());
        final ManaCost cast = ComboExecute.priced(ai, castOf(s.there()));
        // the tap's mana pays the generic part; a coloured pip is paid by the pool and our lands alone
        return LoopPool.pays(ai, cast.isPureGeneric() ? ManaCost.get(Math.max(0, cast.getCMC() - extra)) : cast, null);
    }

    // the printed mana of the ability's or spell's cost (none for a cost with no mana part)
    private static ManaCost printed(final SpellAbility sa) {
        return sa.getPayCosts() == null ? ManaCost.ZERO : sa.getPayCosts().getTotalMana();
    }

    // the cost's blue pips
    private static int blue(final ManaCost cost) {
        return cost.getShardCount(ManaCostShard.BLUE);
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

    // what the tap of our permanent tapped (a rock, a land) makes, made, after our multipliers: each ProduceMana
    // replacement on our battlefield, not suppressed, that multiplies what a permanent we tap makes (ReplaceMana:
    // Nyxbloom Ancient's ReplaceAmount$ 3) and whose ValidCard takes the permanent, made times its amount
    private static int multiplied(final Player ai, final Card tapped, final int made) {
        int out = made;
        for (final Card c : ai.getCardsIn(ZoneType.Battlefield)) {
            for (final ReplacementEffect re : c.getReplacementEffects()) {
                final SpellAbility with = re.getOverridingAbility();
                if (re.getMode() == ReplacementType.ProduceMana && !re.isSuppressed() && with != null
                        && with.getApi() == ApiType.ReplaceMana && re.matchesValidParam("ValidCard", tapped)
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
