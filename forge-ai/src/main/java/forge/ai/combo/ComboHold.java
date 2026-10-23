package forge.ai.combo;

import forge.game.card.Card;
import forge.game.card.CardCollection;
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
 * instead. The emptiers keep their own gates (their executor), and a winner that wins from the battlefield is cast
 * freely: casting it is progress.
 *
 * <p>Each method answers for one hook beside the stock code, and answers the stock decision (false, or the list it was
 * given) at once for a deck holding none of piece 1's lines. Nothing here draws a random number, keeps any state or
 * reads the library's order; a hold ends when its line is dead or a signal abandons it (section 6.5), never by a turn
 * count.
 */
public final class ComboHold {
    private ComboHold() {
    }

    /**
     * Whether our cast of {@code sa} waits (AiController.canPlayAndPayForFace, after every stock veto:
     * AiPlayDecision.AnotherTime): our own spell from our hand, not a copy and not a Play-effect cast, whose host is
     * Thassa's Oracle, while a {@link ComboTracker#live} line has Oracle as its winner and the go-off is not ready.
     */
    public static boolean vetoesCast(final Player ai, final SpellAbility sa) {
        if (sa == null || !sa.isSpell() || sa.isCopied() || sa.isCastFromPlayEffect()) {
            return false;
        }
        final Card host = sa.getHostCard();
        if (host == null || !ComboLines.Winner.ORACLE.card.equals(ComboTracker.nameOf(host))
                || !host.isInZone(ZoneType.Hand) || !ai.equals(host.getOwner())) {
            return false;
        }
        final Player activator = sa.getActivatingPlayer();
        if (activator != null && !ai.equals(activator)) {
            return false;
        }
        final ComboTracker t = ComboTracker.of(ai);
        if (t == null || t.deckLines().isEmpty()) {
            return false;
        }
        for (final ComboLines.Line l : t.live()) {
            if (l.winner() == ComboLines.Winner.ORACLE) {
                return !ComboExecute.goOffNow(ai, sa);
            }
        }
        return false;
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
        CardCollection out = null;
        int left = cards.size();
        for (final Card c : cards) {
            if (left <= keep) {
                break;
            }
            if (ai.equals(c.getController()) && t.protects(c)) {
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
                if (!t.protects(c)) {
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
