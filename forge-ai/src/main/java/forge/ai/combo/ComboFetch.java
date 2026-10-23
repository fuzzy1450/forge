package forge.ai.combo;

import java.util.ArrayList;
import java.util.List;

import forge.ai.SpecialCardAi;
import forge.game.card.Card;
import forge.game.card.CardCollectionView;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.Zone;
import forge.game.zone.ZoneType;

/**
 * The fetch rules (spec 2026-10-22-combo-aware-ai-core-design.md section 6.3): a decision that picks cards from our
 * library, or orders its top, takes the missing piece of a combo line exactly one piece short when that is a legal
 * choice, and keeps every piece of a live line on top; otherwise it is today's decision, unchanged.
 *
 * <p>Each method answers for one hook beside the stock code, and answers "the stock decision" (null, false, or a list
 * left as it was) at once for a deck holding none of piece 1's lines. Nothing here draws a random number, keeps any
 * state or reads the library's order: a piece is fetched for by name, from the cards the decision is already allowed
 * to choose among (the search's list, the dig's or the Vault's cards in view), and the tracker reads our library as
 * contents only.
 */
public final class ComboFetch {
    /** Which pieces a fetch to a destination may take: any, or only a piece that may start on the battlefield. */
    private enum Fit { ANY, BATTLEFIELD }

    private ComboFetch() {
    }

    /**
     * The library search's combo pick (ChangeZoneAi.chooseCardToHiddenOriginChangeZone, ahead of KeyCards), or null
     * for the stock pick: our own search ({@code player} is the decider), never Gifts Ungiven's or Intuition's piles
     * (their own rules), to our hand, the top of our library ({@code LibraryPosition$ 0}) or, for a piece that may
     * start there, the battlefield. The pick is the missing piece of the first {@link ComboTracker#oneShort} line,
     * by rank, that {@code fetchList} holds a card of ours named for: the first such card in its order.
     */
    public static Card searchPick(final SpellAbility sa, final ZoneType destination,
            final CardCollectionView fetchList, final Player player, final Player decider) {
        if (decider == null || !decider.equals(player)) {
            return null;
        }
        final ComboTracker t = ComboTracker.of(decider);
        if (t == null || t.deckLines().isEmpty()) {
            return null;
        }
        if (SpecialCardAi.GiftsUngiven.handles(sa) || "Intuition".equals(sa.getParam("AILogic"))) {
            return null;
        }
        final Fit fit = fit(destination, sa.getParam("LibraryPosition"));
        return fit == null ? null : pick(t, decider, fetchList, fit);
    }

    /**
     * The dig's combo pick (DigAi.chooseSingleCard, after Cream of the Crop's), or null for the stock pick: a dig of
     * our own library ({@code relatedPlayer} null or us), whose chosen card goes to our hand (DigEffect's default
     * {@code DestinationZone}), the top of our library ({@code LibraryPosition$ 0}) or, for a piece that may start
     * there, the battlefield; ranked as {@link #searchPick} is, among {@code valid}.
     */
    public static Card digPick(final Player ai, final SpellAbility sa, final Iterable<Card> valid,
            final Player relatedPlayer) {
        if (relatedPlayer != null && !ai.equals(relatedPlayer)) {
            return null;
        }
        final ComboTracker t = ComboTracker.of(ai);
        if (t == null || t.deckLines().isEmpty()) {
            return null;
        }
        final String zone = sa.getParam("DestinationZone");
        final ZoneType destination = zone == null ? ZoneType.Hand : ZoneType.smartValueOf(zone);
        final Fit fit = fit(destination, sa.getParam("LibraryPosition"));
        return fit == null ? null : pick(t, ai, valid, fit);
    }

    /**
     * Whether {@code c} stays on top of {@code p}'s library (the scry, surveil and ordering rules): it is in
     * {@code p}'s library, {@code p} owns it, and it is a piece of a line {@code p} chases now
     * ({@link ComboTracker#protects}: any live line, not only a one-short one).
     */
    public static boolean keepsOnTop(final Player p, final Card c) {
        final ComboTracker t = ComboTracker.of(p);
        if (t == null || t.deckLines().isEmpty()) {
            return false;
        }
        final Zone zone = c.getZone();
        return zone != null && zone.is(ZoneType.Library, p) && p.equals(c.getOwner()) && t.protects(c);
    }

    /** Moves the cards {@link #keepsOnTop} keeps for {@code p} to the front of {@code cards}, in place and stable:
     *  both groups keep their order, and a list holding none is left as it was. */
    public static void pieceFirst(final Player p, final List<Card> cards) {
        final ComboTracker t = ComboTracker.of(p);
        if (t == null || t.deckLines().isEmpty()) {
            return;
        }
        final List<Card> pieces = new ArrayList<>();
        final List<Card> rest = new ArrayList<>();
        for (final Card c : cards) {
            (keepsOnTop(p, c) ? pieces : rest).add(c);
        }
        if (pieces.isEmpty()) {
            return;
        }
        cards.clear();
        cards.addAll(pieces);
        cards.addAll(rest);
    }

    /**
     * Lim-Dul's Vault's dig (LimDulsVault.dig, behind its own MAX_DIGS, library, life-floor and look tests): FALSE
     * keeps these five when a {@link ComboTracker#oneShort} line's missing piece is among {@code shown}, TRUE digs
     * again when one is still in our library below them, null leaves the stock rule.
     */
    public static Boolean vaultDig(final Player ai, final List<Card> shown) {
        final ComboTracker t = ComboTracker.of(ai);
        if (t == null || t.deckLines().isEmpty()) {
            return null;
        }
        final List<ComboLines.Line> oneShort = t.oneShort();
        for (final ComboLines.Line l : oneShort) {
            if (ours(ai, shown, t.missing(l).get(0)) != null) {
                return Boolean.FALSE;
            }
        }
        for (final ComboLines.Line l : oneShort) {
            if (t.where(t.missing(l).get(0)) == ComboTracker.Where.LIBRARY) {
                return Boolean.TRUE;
            }
        }
        return null;
    }

    // what a fetch to the destination may take: hand and the top of the library any piece, the battlefield a piece
    // that may start there; anything else (the graveyard, exile, the library's bottom or another position) none
    private static Fit fit(final ZoneType destination, final String libraryPosition) {
        if (destination == ZoneType.Hand || (destination == ZoneType.Library && "0".equals(libraryPosition))) {
            return Fit.ANY;
        }
        return destination == ZoneType.Battlefield ? Fit.BATTLEFIELD : null;
    }

    // the missing piece of the first one-short line, by the tracker's rank, that the fetch may take and that the
    // cards hold one of ours of: the first such card in their order
    private static Card pick(final ComboTracker t, final Player owner, final Iterable<Card> cards, final Fit fit) {
        for (final ComboLines.Line l : t.oneShort()) {
            final String name = t.missing(l).get(0);
            if (fit == Fit.BATTLEFIELD && !startsOnBattlefield(l, name)) {
                continue;
            }
            final Card c = ours(owner, cards, name);
            if (c != null) {
                return c;
            }
        }
        return null;
    }

    // whether the line's piece of that name may start on the battlefield (its zones hold B)
    private static boolean startsOnBattlefield(final ComboLines.Line l, final String name) {
        for (final ComboTable.Piece piece : l.variant().pieces()) {
            if (piece.name().equals(name) && piece.needs('B')) {
                return true;
            }
        }
        return false;
    }

    // the first of the cards that the owner owns and that bears that name (by ComboTracker.nameOf), or null
    private static Card ours(final Player owner, final Iterable<Card> cards, final String name) {
        for (final Card c : cards) {
            if (owner.equals(c.getOwner()) && ComboTracker.nameOf(c).equals(name)) {
                return c;
            }
        }
        return null;
    }
}
