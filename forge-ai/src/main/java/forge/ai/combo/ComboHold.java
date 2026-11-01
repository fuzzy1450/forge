package forge.ai.combo;

import java.util.ArrayList;
import java.util.List;

import forge.game.ability.AbilityUtils;
import forge.game.ability.ApiType;
import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.card.CardCollectionView;
import forge.game.card.CardLists;
import forge.game.cost.Cost;
import forge.game.cost.CostExile;
import forge.game.cost.CostPart;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;

/**
 * Holding and protecting (spec 2026-10-22-combo-aware-ai-core-design.md sections 6.2 and 6.4): while a live line needs
 * Thassa's Oracle, our hand cast of it waits until the go-off is ready ({@link ComboExecute#goOffNow}), and a piece of a
 * live line ({@link ComboTracker#protects}) is neither discarded nor exiled to pay a cost while another card can go
 * instead, nor put into the graveyard or exile with our whole hand by a wheel of ours (follow-up F5), nor shuffled away
 * from the top of our library where our ordering has just put it. The emptiers keep their own gates (their executor),
 * and a winner that wins from the battlefield is cast freely: casting it is progress. A piece of a live loop line
 * (piece 2, spec 2026-10-29-combo-aware-ai-loops-design.md section 6.7) is protected as piece 1's pieces are, and its
 * own cast is never held: the loop needs it on the battlefield. The one exception is a cast its own cycle makes: while
 * a cast and bounce cycle runs, the cycle casts its pieces itself.
 *
 * <p>Each method answers for one hook beside the stock code, and answers the stock decision (false, or the list it was
 * given) at once for a deck holding no combo line. Nothing here draws a random number or keeps any state, and
 * nothing reads the library's order but the one known top card our own ordering has just put there; a hold ends when
 * its line is dead or a signal abandons it (section 6.5), never by a turn count.
 */
public final class ComboHold {
    private ComboHold() {
    }

    /**
     * Whether our cast of {@code sa} waits (AiController.canPlayAndPayForFace, after every stock veto:
     * AiPlayDecision.AnotherTime). Only our own cast of a spell, not a copy and not a Play-effect cast, waits:
     * <ul>
     * <li>Thassa's Oracle from our hand, while a {@link ComboTracker#live} line has Oracle as its winner and the go-off
     * is not ready ({@link ComboExecute#goOffNow});
     * <li>Thassa's Oracle from our hand, while the loop driver's dig toward it with Thrasios, Triton Hero could run
     * (piece 2, spec 2026-10-29-combo-aware-ai-loops-design.md section 6.4 step 3: {@link LoopDriver#holdsOracle},
     * which yields to piece 1's own Oracle go-off);
     * <li>a piece of the cast and bounce line the loop driver's go-off runs in this step, from our hand (piece 2,
     * section 6.8: {@link LoopDriver#holdsCycleCast}): the cycle casts it itself, and the stock walk, which plays at
     * the cycle's boundary, would otherwise cast it there, round after round;
     * <li>a wheel (follow-up F5): a spell one of whose parts puts our hand into the graveyard or exile, read from its
     * parts, never its name (a Discard of {@code Mode$ Hand} whose players include us: Wheel of Fortune, Windfall, One
     * with Nothing; a ChangeZoneAll of our hand's cards to the graveyard or exile: Hex Magic), while a card of our hand
     * that it would take is one {@link ComboTracker#protects} protects; except in the go-off itself, when the spell
     * draws for us and that draw wins ({@link ComboExecute#nextDrawWins}: our library emptied with Laboratory Maniac or
     * Jace in play). A spell that puts our hand into our library (Timetwister) leaves the piece reachable, and never
     * waits.
     * </ul>
     */
    public static boolean vetoesCast(final Player ai, final SpellAbility sa) {
        if (sa == null || !sa.isSpell() || sa.isCopied() || sa.isCastFromPlayEffect()) {
            return false;
        }
        final Card host = sa.getHostCard();
        final Player activator = sa.getActivatingPlayer();
        if (host == null || (activator != null && !ai.equals(activator))) {
            return false;
        }
        if (ComboLines.Winner.ORACLE.card.equals(ComboTracker.nameOf(host)) && host.isInZone(ZoneType.Hand)
                && ai.equals(host.getOwner())) {
            return oracleWaits(ai, sa) || loopHoldsOracle(ai, sa);
        }
        return loopHoldsCycleCast(ai, sa) || wheelWaits(ai, sa, host);
    }

    // our hand cast of a piece of the cast and bounce the loop driver's go-off runs waits for its cycle, which casts it
    // itself (piece 2, LoopDriver.holdsCycleCast)
    private static boolean loopHoldsCycleCast(final Player ai, final SpellAbility sa) {
        final LoopDriver d = LoopDriver.of(ai);
        return d != null && d.holdsCycleCast(sa);
    }

    // our hand cast of Thassa's Oracle waits for the loop driver's dig toward it (piece 2, LoopDriver.holdsOracle)
    private static boolean loopHoldsOracle(final Player ai, final SpellAbility sa) {
        final LoopDriver d = LoopDriver.of(ai);
        return d != null && d.holdsOracle(sa);
    }

    // our hand cast of Thassa's Oracle waits while a live line has Oracle as its winner and the go-off is not ready
    private static boolean oracleWaits(final Player ai, final SpellAbility sa) {
        final ComboTracker t = ComboTracker.of(ai);
        if (t == null || t.deckLines().isEmpty()) {
            return false;
        }
        final ComboTracker.View v = t.view();
        for (final ComboLines.Line l : v.live()) {
            if (l.winner() == ComboLines.Winner.ORACLE) {
                return !ComboExecute.goOffNow(ai, sa, v);
            }
        }
        return false;
    }

    // a wheel waits (follow-up F5): a part of the spell would put a protected card of our hand into the graveyard or
    // exile, and the spell's own draw does not win now. Its parts are read first, from their parameters alone (an
    // ordinary spell takes no hand, and costs nothing more), then the tracker; the spell's own card is no card of the
    // hand it takes, since it is on the stack by then
    private static boolean wheelWaits(final Player ai, final SpellAbility sa, final Card host) {
        if (!takesAHand(sa)) {
            return false;
        }
        final ComboTracker t = ComboTracker.of(ai);
        if (t == null || t.deckLines().isEmpty()) {
            return false;
        }
        ComboTracker.View v = null;
        for (final Card c : ai.getCardsIn(ZoneType.Hand)) {
            if (c.equals(host) || !t.isPiece(ComboTracker.nameOf(c))) {
                continue;
            }
            if (v == null) {
                v = t.view();
            }
            if (v.protects(c) && takes(ai, sa, c)) {
                return !(drawsForUs(ai, sa) && ComboExecute.nextDrawWins(ai, t));
            }
        }
        return false;
    }

    // a part of the spell (its root or a sub) moves a hand to the graveyard or exile, by its parameters alone
    private static boolean takesAHand(final SpellAbility sa) {
        for (SpellAbility s = sa; s != null; s = s.getSubAbility()) {
            if (discardsHands(s) || binsHands(s)) {
                return true;
            }
        }
        return false;
    }

    // a Discard of whole hands (Mode$ Hand: DiscardEffect discards each of its players' whole hand)
    private static boolean discardsHands(final SpellAbility s) {
        return s.getApi() == ApiType.Discard && "Hand".equals(s.getParam("Mode"));
    }

    // a ChangeZoneAll whose origins hold the hand and whose destination is the graveyard or exile
    private static boolean binsHands(final SpellAbility s) {
        if (s.getApi() != ApiType.ChangeZoneAll || !s.hasParam("Origin") || !s.hasParam("Destination")) {
            return false;
        }
        final ZoneType to = ZoneType.smartValueOf(s.getParam("Destination"));
        return (to == ZoneType.Graveyard || to == ZoneType.Exile)
                && ZoneType.listValueOf(s.getParam("Origin")).contains(ZoneType.Hand);
    }

    // a part of the spell would move c, a card of our hand, to the graveyard or exile, as its effect reads its players:
    // a discard of the hands of its players, its targets or else its Defined ones (DiscardEffect), us among them; or a
    // ChangeZoneAll of the hands of every player when it has neither targets nor Defined or uses all its origin zones,
    // else of its players, us among them, whose ChangeType c fits (ChangeZoneAllEffect)
    private static boolean takes(final Player ai, final SpellAbility sa, final Card c) {
        for (SpellAbility s = sa; s != null; s = s.getSubAbility()) {
            if (discardsHands(s) && players(s).contains(ai)) {
                return true;
            }
            if (binsHands(s) && (everyPlayer(s) || players(s).contains(ai))
                    && AbilityUtils.filterListByType(new CardCollection(c), s.getParam("ChangeType"), s).contains(c)) {
                return true;
            }
        }
        return false;
    }

    // the spell, or a sub of it, draws for us: a Draw whose players (its targets, or else its Defined ones) include us
    private static boolean drawsForUs(final Player ai, final SpellAbility sa) {
        for (SpellAbility s = sa; s != null; s = s.getSubAbility()) {
            if (s.getApi() == ApiType.Draw && players(s).contains(ai)) {
                return true;
            }
        }
        return false;
    }

    // the players a part acts on, as SpellAbilityEffect.getTargetPlayers reads them: its targets when it targets, else
    // its Defined players (You when it names none), each " & " term in turn
    private static List<Player> players(final SpellAbility s) {
        final List<Player> out = new ArrayList<>();
        if (s.usesTargeting()) {
            for (final Player p : s.getTargets().getTargetPlayers()) {
                out.add(p);
            }
            return out;
        }
        for (final String d : s.getParamOrDefault("Defined", "You").split(" & ")) {
            out.addAll(AbilityUtils.getDefinedPlayers(s.getHostCard(), d, s));
        }
        return out;
    }

    // a ChangeZoneAll with neither targets nor Defined, or one using all its origin zones, moves every player's cards
    private static boolean everyPlayer(final SpellAbility s) {
        return (!s.usesTargeting() && !s.hasParam("Defined")) || s.hasParam("UseAllOriginZones");
    }

    /**
     * Whether we decline an optional shuffle of {@code owner}'s library (RearrangeTopOfLibraryAi.confirmAction, the
     * "you may shuffle" of Ponder, Portent and the like; follow-up F5): {@code owner} is us, and the top card of our
     * library is a known top ({@link ComboTracker#knownTops}: the card our ordering has just put there,
     * ComboFetch.pieceFirst) that is a piece {@link ComboTracker#protects} protects. A shuffle would lose the piece we
     * stacked. The top card is read only to see that it is the card we put there, as the stock confirm reads that same
     * card; false at once for a deck holding no combo line, or another player's library.
     */
    public static boolean keepsTheStackedTop(final Player ai, final Player owner) {
        if (ai == null || !ai.equals(owner)) {
            return false;
        }
        final ComboTracker t = ComboTracker.of(ai);
        if (t == null || t.deckLines().isEmpty()) {
            return false;
        }
        final CardCollectionView library = ai.getCardsIn(ZoneType.Library);
        if (library.isEmpty()) {
            return false;
        }
        final Card top = library.get(0);
        return t.knownTops().contains(top.getId()) && t.view().protects(top);
    }

    /**
     * The cards a discard or an exile cost chooses among, spared our live lines' pieces (AiController.getCardsToDiscard
     * and ComputerUtil.chooseExileFromList, ahead of their stock picks): the cards of ours {@link ComboTracker#protects}
     * protects are dropped, in their order, while more than {@code keep} cards are left, so the payment still finds the
     * cards it needs. The list itself when nothing is dropped; otherwise a new list, so the caller's is never changed.
     */
    public static CardCollection spare(final Player ai, final CardCollection cards, final int keep) {
        if (cards.size() <= keep) {
            return cards;
        }
        final ComboTracker t = ComboTracker.of(ai);
        if (t == null || t.deckLines().isEmpty()) {
            return cards;
        }
        ComboTracker.View v = null;
        CardCollection out = null;
        int left = cards.size();
        for (final Card c : cards) {
            if (left <= keep) {
                break;
            }
            if (!ai.equals(c.getController()) || !t.isPiece(ComboTracker.nameOf(c))) {
                continue;
            }
            if (v == null) {
                v = t.view();
            }
            if (v.protects(c)) {
                if (out == null) {
                    out = new CardCollection(cards);
                }
                out.remove(c);
                left--;
            }
        }
        return out == null ? cards : out;
    }

    /**
     * Whether we refuse to pay {@code cost} for {@code sa} (SpellAbilityAi.willPayCosts, its fifth check): a CostExile
     * part that exiles from our hand, as AiCostDecision pays it through ComputerUtil.chooseExileFromList (not the
     * card itself, not "All", nor a total-mana-value or same-zone exile), finds enough valid cards in our hand besides
     * {@code sa}'s host, but too few once the pieces {@link ComboTracker#protects} protects are set aside: the payment
     * would have to exile a live line's piece.
     */
    public static boolean vetoesPitch(final Player ai, final SpellAbility sa, final Cost cost) {
        if (sa == null || cost == null) {
            return false;
        }
        ComboTracker t = null;
        ComboTracker.View v = null;
        final Card host = sa.getHostCard();
        for (final CostPart part : cost.getCostParts()) {
            if (!(part instanceof CostExile ex) || !paysFromHand(ex)) {
                continue;
            }
            if (t == null) {
                t = ComboTracker.of(ai);
                if (t == null || t.deckLines().isEmpty()) {
                    return false;
                }
            }
            final int amount = ex.getAbilityAmount(sa);
            final CardCollection valid = CardLists.getValidCards(ai.getCardsIn(ZoneType.Hand), ex.getType().split(";"),
                    ai, host, sa);
            valid.remove(host);
            if (valid.size() < amount) {
                continue;
            }
            int unprotected = 0;
            for (final Card c : valid) {
                if (!t.isPiece(ComboTracker.nameOf(c))) {
                    unprotected++;
                    continue;
                }
                if (v == null) {
                    v = t.view();
                }
                if (!v.protects(c)) {
                    unprotected++;
                }
            }
            if (unprotected < amount) {
                return true;
            }
        }
        return false;
    }

    // an exile from our hand alone that AiCostDecision.visit(CostExile) pays through ComputerUtil.chooseExileFrom:
    // not the card itself, not "All", not a total-mana-value or top-of-graveyard type, not a same-zone exile
    private static boolean paysFromHand(final CostExile ex) {
        if (ex.getFrom().size() != 1 || ex.getFrom().get(0) != ZoneType.Hand || ex.payCostFromSource()
                || ex.zoneRestriction == 0) {
            return false;
        }
        final String type = ex.getType();
        return !"All".equals(type) && !type.contains("FromTopGrave") && !type.contains("+withTotalCMCGE");
    }
}
