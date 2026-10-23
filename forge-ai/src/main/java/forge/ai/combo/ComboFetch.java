package forge.ai.combo;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import forge.ai.AiAbilityDecision;
import forge.ai.AiPlayDecision;
import forge.ai.SpecialCardAi;
import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.card.CardCollectionView;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.Zone;
import forge.game.zone.ZoneType;

/**
 * The fetch rules (spec 2026-10-22-combo-aware-ai-core-design.md section 6.3): a decision that picks cards from our
 * library, or orders its top, takes the missing piece of a combo line exactly one piece short when that is a legal
 * choice, and keeps every piece of a live line on top; otherwise it is today's decision, unchanged. Intuition's and
 * Gifts Ungiven's piles are built from the completers, the missing pieces of the lines one piece short. Demonic
 * Consultation is cast as a tutor from a deep library, and its resolution names the piece it was cast for.
 *
 * <p>Each method answers for one hook beside the stock code, and answers "the stock decision" (null, false, an empty
 * list, or a list left as it was) at once for a deck holding none of piece 1's lines. Nothing here draws a random
 * number, keeps any state of its own or reads the library's order: a piece is fetched for by name, from the cards the
 * decision is already allowed to choose among (the search's list or library, the dig's or the Vault's cards in view,
 * the library Consultation reveals), and the tracker reads our library as contents only. Consultation's tutor intent,
 * the one thing carried from a decision to a later one, lives on the AI's own tracker.
 */
public final class ComboFetch {
    /** Which pieces a fetch to a destination may take: any, or only a piece that may start on the battlefield. */
    private enum Fit { ANY, BATTLEFIELD }

    /** Intuition's three cards ("search your library for three cards"): the piece and two fillers, or three
     *  completers. */
    private static final int INTUITION_PILE = 3;
    /** Gifts Ungiven reveals four and the opponent bins two: with three completers among them, two left still hold
     *  one. */
    private static final int GIFTS_COMPLETERS = 3;
    /** The smallest library Demonic Consultation tutors from: 6/75 = 8%, the chance the named piece is among the six
     *  exiled first, which turns the tutor into an exile of the whole library. */
    public static final int CONSULT_TUTOR_MIN_LIBRARY = 75;

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

    /**
     * The completers the piles are built from (section 6.3): the distinct missing pieces of the
     * {@link ComboTracker#oneShort} lines, in their rank order. Each is in our library, a missing piece's only place,
     * so a search can take it. Empty for a deck holding none of piece 1's lines.
     */
    public static List<String> completers(final Player ai) {
        final ComboTracker t = ComboTracker.of(ai);
        if (t == null || t.deckLines().isEmpty()) {
            return List.of();
        }
        final List<String> out = new ArrayList<>();
        for (final ComboLines.Line l : t.oneShort()) {
            final String name = t.missing(l).get(0);
            if (!out.contains(name)) {
                out.add(name);
            }
        }
        return List.copyOf(out);
    }

    /**
     * Intuition's pile (Intuition.considerMultiple, ahead of its Trix branch), or null for the stock pile. With three
     * or more completers, the first three's library cards: whichever the opponent hands us completes a line. With
     * fewer, when the first is a creature, that creature and the two cheapest ({@code getCMC}, ties by name) of our
     * library's noncreature, nonland cards that are no piece of the deck's lines: the opponent's stock pick prefers a
     * creature (ChangeZoneAi.chooseCardToHiddenOriginChangeZone, "Prefer to pull a creature"), so it hands over the
     * piece and the two spells go to the graveyard. Otherwise null.
     */
    public static CardCollection intuitionPile(final Player ai) {
        final List<String> names = completers(ai);
        if (names.isEmpty()) {
            return null;
        }
        final ComboTracker t = ComboTracker.of(ai);
        final CardCollection pile = new CardCollection();
        if (names.size() >= INTUITION_PILE) {
            for (final String name : names.subList(0, INTUITION_PILE)) {
                pile.add(t.find(name, ZoneType.Library));
            }
            return pile;
        }
        final Card piece = t.find(names.get(0), ZoneType.Library);
        if (!piece.isCreature()) {
            return null;
        }
        final List<Card> fillers = new ArrayList<>();
        for (final Card c : ai.getCardsIn(ZoneType.Library)) {
            if (!c.isCreature() && !c.isLand() && !t.isPiece(ComboTracker.nameOf(c))) {
                fillers.add(c);
            }
        }
        if (fillers.size() < INTUITION_PILE - 1) {
            return null;
        }
        fillers.sort(Comparator.comparingInt((Card c) -> c.getCMC()).thenComparing(ComboTracker::nameOf));
        pile.add(piece);
        pile.addAll(fillers.subList(0, INTUITION_PILE - 1));
        return pile;
    }

    /** Whether Gifts Ungiven goes for the combo (its worthSearching floor and its four picks): three or more
     *  completers, so whichever two of them the opponent bins, one still reaches our hand. */
    public static boolean giftsReady(final Player ai) {
        return completers(ai).size() >= GIFTS_COMPLETERS;
    }

    /** Gifts Ungiven's own pick (ChangeZoneAi's chooser, ahead of KeyCards), or null for the stock pick: while
     *  {@link #giftsReady}, our card of the first completer, in rank order, that {@code fetchList} still holds. */
    public static Card giftsPick(final Player decider, final CardCollectionView fetchList) {
        final List<String> names = completers(decider);
        if (names.size() < GIFTS_COMPLETERS) {
            return null;
        }
        for (final String name : names) {
            final Card c = ours(decider, fetchList, name);
            if (c != null) {
                return c;
            }
        }
        return null;
    }

    /**
     * Demonic Consultation as a tutor (section 6.3; ComboExecute.consultation, after every winner window), or null for
     * the stock refusal: our own hand cast, a library of at least {@link #CONSULT_TUTOR_MIN_LIBRARY} cards, and a
     * {@link ComboTracker#oneShort} line, by rank, that does not name Consultation (cast as a tutor, it is no emptier
     * of that line) and whose missing piece is in our library. WillPlay(100), with that piece recorded as the cast's
     * intent for its resolution's namer ({@link #consultNamed}).
     */
    public static AiAbilityDecision consultTutor(final Player ai, final SpellAbility sa) {
        if (!ComboExecute.ownHandCast(ai, sa, ComboLines.Emptier.CONSULTATION)) {
            return null;
        }
        final ComboTracker t = ComboTracker.of(ai);
        if (t == null || t.deckLines().isEmpty()
                || ai.getCardsIn(ZoneType.Library).size() < CONSULT_TUTOR_MIN_LIBRARY) {
            return null;
        }
        for (final ComboLines.Line l : t.oneShort()) {
            if (l.names().contains(ComboLines.Emptier.CONSULTATION.card)) {
                continue;
            }
            final String name = t.missing(l).get(0);
            if (t.find(name, ZoneType.Library) != null) {
                t.setConsultIntent(sa.getHostCard().getId(), name);
                return new AiAbilityDecision(100, AiPlayDecision.WillPlay);
            }
        }
        return null;
    }

    /**
     * The name Demonic Consultation's resolution chooses (PlayerControllerAi.chooseCardName, ahead of the stock
     * absent-name loop), or null for the stock namer: the intent {@link #consultTutor} recorded for {@code sa}'s host,
     * taken (removed) whatever the answer, while no trigger of ours hosted by Thassa's Oracle or Fblthp, Impossibly
     * Lost is on the stack (Consultation is that trigger's emptier now, and must exile the whole library) and that
     * piece is still in our library.
     */
    public static String consultNamed(final Player ai, final SpellAbility sa) {
        final Card host = sa.getHostCard();
        final ComboTracker t = ComboTracker.of(ai);
        if (host == null || t == null) {
            return null;
        }
        final String name = t.takeConsultIntent(host.getId());
        if (name == null || t.triggerOnOurStack(ComboLines.Winner.ORACLE.card)
                || t.triggerOnOurStack(ComboLines.Winner.FBLTHP.card)) {
            return null;
        }
        return t.find(name, ZoneType.Library) != null ? name : null;
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
