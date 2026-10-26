package forge.ai.combo;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.BooleanSupplier;

import com.google.common.collect.Iterables;

import forge.ai.AiAbilityDecision;
import forge.ai.AiPlayDecision;
import forge.ai.SpecialCardAi;
import forge.card.mana.ManaCost;
import forge.game.ability.ApiType;
import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.card.CardCollectionView;
import forge.game.phase.PhaseHandler;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.Zone;
import forge.game.zone.ZoneType;

/**
 * The fetch rules (spec 2026-10-22-combo-aware-ai-core-design.md section 6.3): a decision that picks cards from our
 * library, or orders its top, takes the missing piece of a combo line exactly one piece short when that is a legal
 * choice, and keeps every piece of a live line on top; otherwise it is today's decision, unchanged. A search or a dig
 * to our hand (or, for a piece that may start there, the battlefield) also fetches for a line whose only signal is
 * DANGER when its win comes this turn, read from its winner's own window ({@link #fetchable}: section 6.5.3, a combo
 * that wins this turn beats surviving). Intuition's and Gifts Ungiven's piles are built from the
 * completers, the missing pieces of the lines one piece short. Demonic Consultation is cast as a tutor from a deep
 * library, and its resolution names the piece it was cast for; it never tutors for a known top, a piece one of these
 * hooks put on top of our library (Evan, 2026-10-24).
 *
 * <p>Each method answers for one hook beside the stock code, and answers "the stock decision" (null, false, an empty
 * list, or a list left as it was) at once for a deck holding none of piece 1's lines. Nothing here draws a random
 * number, keeps any state of its own or reads the library's order: a piece is fetched for by name, from the cards the
 * decision is already allowed to choose among (the search's list or library, the dig's or the Vault's cards in view,
 * the library Consultation reveals), and the tracker reads our library as contents only. Consultation's tutor intent
 * and the known tops, the things carried from a decision to a later one, live on the AI's own tracker.
 */
public final class ComboFetch {
    /** Where a fetch puts its card, and so which pieces it may take: our hand or the top of our library any piece,
     *  the battlefield only a piece that may start there. A piece put on top of our library in our main phase is not
     *  in our hand this turn, so only a fetch to our hand or the battlefield wins this turn under danger
     *  ({@link #fetchable}). */
    private enum Fit { HAND, TOP, BATTLEFIELD }

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
     * start there, the battlefield. The pick is the missing piece of the first {@link #fetchable} line, by rank, that
     * {@code fetchList} holds a card of ours named for: the first such card in its order. A pick to the top of our
     * library is remembered as a known top ({@link ComboTracker#knownTops}).
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
        return fit == null ? null : placed(t, destination, pick(t.view(), decider, fetchList, fit));
    }

    /**
     * The dig's combo pick (DigAi.chooseSingleCard, after Cream of the Crop's), or null for the stock pick: a dig of
     * our own library ({@code relatedPlayer} null or us), whose chosen card goes to our hand (DigEffect's default
     * {@code DestinationZone}), the top of our library ({@code LibraryPosition$ 0}) or, for a piece that may start
     * there, the battlefield; ranked as {@link #searchPick} is, among {@code valid}, and remembered as a known top as
     * it is.
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
        return fit == null ? null : placed(t, destination, pick(t.view(), ai, valid, fit));
    }

    // the pick, remembered as a known top (section 6.3) when it goes to the top of our library: a fit's only Library
    // destination is LibraryPosition$ 0. A pick from our library keeps its object there (a move within the zone); one
    // from anywhere else arrives as a new object, which the tracker's next read drops as no longer in our library
    private static Card placed(final ComboTracker t, final ZoneType destination, final Card pick) {
        if (pick != null && destination == ZoneType.Library) {
            t.placedOnTop(pick);
        }
        return pick;
    }

    /**
     * Whether {@code c} stays on top of {@code p}'s library (the scry, surveil and ordering rules): it is in
     * {@code p}'s library, {@code p} owns it, and it is a piece of a line {@code p} chases now
     * ({@link ComboTracker#protects}: any live line, not only a one-short one).
     */
    public static boolean keepsOnTop(final Player p, final Card c) {
        final ComboTracker t = ComboTracker.of(p);
        if (t == null || t.deckLines().isEmpty() || !inOwnLibrary(p, c) || !t.isPiece(ComboTracker.nameOf(c))) {
            return false;
        }
        return t.view().protects(c);
    }

    // c is in p's library and p owns it
    private static boolean inOwnLibrary(final Player p, final Card c) {
        final Zone zone = c.getZone();
        return zone != null && zone.is(ZoneType.Library, p) && p.equals(c.getOwner());
    }

    /** Moves the cards {@link #keepsOnTop} keeps for {@code p} to the front of {@code cards}, in place and stable:
     *  both groups keep their order, and a list holding none is left as it was. The cards go on top of our library (a
     *  scry's or a surveil's kept cards), so the pieces moved first are known tops ({@link ComboTracker#knownTops}). */
    public static void pieceFirst(final Player p, final List<Card> cards) {
        pieceFirst(p, cards, ComboFetch::always);
    }

    /** {@link #pieceFirst}, for a move whose destination {@code toTop} tells (orderMoveToZoneList: the top of the
     *  library or its bottom): the pieces moved first are known tops only when it answers true, and it is asked only
     *  when some piece was moved first. */
    public static void pieceFirst(final Player p, final List<Card> cards, final BooleanSupplier toTop) {
        final ComboTracker t = ComboTracker.of(p);
        if (t == null || t.deckLines().isEmpty()) {
            return;
        }
        ComboTracker.View v = null;
        final List<Card> pieces = new ArrayList<>();
        final List<Card> rest = new ArrayList<>();
        for (final Card c : cards) {
            boolean keep = false;
            if (inOwnLibrary(p, c) && t.isPiece(ComboTracker.nameOf(c))) {
                if (v == null) {
                    v = t.view();
                }
                keep = v.protects(c);
            }
            (keep ? pieces : rest).add(c);
        }
        if (pieces.isEmpty()) {
            return;
        }
        cards.clear();
        cards.addAll(pieces);
        cards.addAll(rest);
        if (toTop.getAsBoolean()) {
            for (final Card c : pieces) {
                t.placedOnTop(c);
            }
        }
    }

    // pieceFirst's destination for a scry's or a surveil's kept cards: always the top
    private static boolean always() {
        return true;
    }

    /**
     * Lim-Dul's Vault's dig (LimDulsVault.dig, behind its own MAX_DIGS, library, life-floor and look tests): FALSE
     * keeps these five when a {@link #fetchable} line's missing piece is among {@code shown}, TRUE digs again when one
     * is still in our library below them, null leaves the stock rule. The Vault stacks the piece on top of our library,
     * so its lines are the one-short ones: under danger no win this turn comes of it (follow-up final review, round 1).
     */
    public static Boolean vaultDig(final Player ai, final List<Card> shown) {
        final ComboTracker t = ComboTracker.of(ai);
        if (t == null || t.deckLines().isEmpty()) {
            return null;
        }
        final ComboTracker.View v = t.view();
        final List<ComboLines.Line> fetchable = fetchable(v, Fit.TOP);
        for (final ComboLines.Line l : fetchable) {
            if (ours(ai, shown, v.missing(l).get(0)) != null) {
                return Boolean.FALSE;
            }
        }
        for (final ComboLines.Line l : fetchable) {
            if (v.where(v.missing(l).get(0)) == ComboTracker.Where.LIBRARY) {
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
        return completers(t.view());
    }

    // completers, read through the decision's view
    private static List<String> completers(final ComboTracker.View v) {
        final List<String> out = new ArrayList<>();
        for (final ComboLines.Line l : v.oneShort()) {
            final String name = v.missing(l).get(0);
            if (!out.contains(name)) {
                out.add(name);
            }
        }
        return List.copyOf(out);
    }

    /**
     * Intuition's pile (Intuition.considerMultiple, ahead of its Trix branch), or null for the stock pile. With three
     * or more completers, the first three's library cards: whichever the opponent hands us completes a line. With
     * fewer, when the first is a creature, that creature and two fillers: the opponent's stock pick prefers a creature
     * (ChangeZoneAi.chooseCardToHiddenOriginChangeZone, "Prefer to pull a creature"), so it hands over the piece and
     * the two fillers go to the graveyard. The fillers are the two cheapest ({@code getCMC}, ties by name) of our
     * library's noncreature, nonland cards that are no piece of the deck's lines and no mana source
     * ({@link #manaSource}: Evan, 2026-10-24, so Chrome Mox, Lotus Petal and Dark Ritual are not binned); only when
     * fewer than two such cards remain, the two cheapest of those cards with the mana sources among them. Otherwise
     * null.
     */
    public static CardCollection intuitionPile(final Player ai) {
        final ComboTracker t = ComboTracker.of(ai);
        if (t == null || t.deckLines().isEmpty()) {
            return null;
        }
        final ComboTracker.View v = t.view();
        final List<String> names = completers(v);
        if (names.isEmpty()) {
            return null;
        }
        final CardCollection pile = new CardCollection();
        if (names.size() >= INTUITION_PILE) {
            for (final String name : names.subList(0, INTUITION_PILE)) {
                pile.add(v.card(name));
            }
            return pile;
        }
        final Card piece = v.card(names.get(0));
        if (!piece.isCreature()) {
            return null;
        }
        final List<Card> spells = new ArrayList<>();
        final List<Card> spare = new ArrayList<>();
        for (final Card c : ai.getCardsIn(ZoneType.Library)) {
            if (!c.isCreature() && !c.isLand() && !t.isPiece(ComboTracker.nameOf(c))) {
                spells.add(c);
                if (!manaSource(c)) {
                    spare.add(c);
                }
            }
        }
        final List<Card> fillers = spare.size() >= INTUITION_PILE - 1 ? spare : spells;
        if (fillers.size() < INTUITION_PILE - 1) {
            return null;
        }
        fillers.sort(Comparator.comparingInt((Card c) -> c.getCMC()).thenComparing(ComboTracker::nameOf));
        pile.add(piece);
        pile.addAll(fillers.subList(0, INTUITION_PILE - 1));
        return pile;
    }

    // a mana source, which Intuition's fillers spare (Evan, 2026-10-24): a card with a mana ability (Chrome Mox, Lotus
    // Petal, Lion's Eye Diamond, Mana Vault), or with a spell one of whose parts, the root or a sub, adds mana (Dark
    // Ritual, Cabal Ritual: ApiType.Mana)
    private static boolean manaSource(final Card c) {
        if (!c.getManaAbilities().isEmpty()) {
            return true;
        }
        for (final SpellAbility spell : c.getSpells()) {
            for (SpellAbility s = spell; s != null; s = s.getSubAbility()) {
                if (s.getApi() == ApiType.Mana) {
                    return true;
                }
            }
        }
        return false;
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
     * the stock refusal: our own hand cast, a library of at least {@link #CONSULT_TUTOR_MIN_LIBRARY} cards, no
     * {@link ComboTracker#live} line already assembled (none of its pieces left in our library: holding and execution
     * run it, and a tutor would spend a piece of it, Consultation itself, or the mana of its go-off, as in our upkeep
     * ahead of a Main 1 Oracle), a {@link ComboTracker#oneShort} line, by rank, that does not name Consultation (cast
     * as a tutor, it is no emptier of that line) and whose missing piece is in our library and no known top
     * ({@link ComboTracker#knownTop}: one of our hooks put it on top, where the six cards Consultation exiles first
     * would take it, and the whole library with it; the next line by rank is served instead), and Consultation's own
     * cast paid now (SpecialCardAi.CommanderCastCeiling.affordable, G1: the test-mode cost against HonestMana, in total
     * and in each colour). The 75-card floor is the whole threshold (Evan, 2026-10-24): no condition is read on the
     * other live pieces the exile may take. An approval the mana cannot pay would go on to canPayCost's test payment,
     * whose isManaSourceReserved rolls draw MyRandom at every priority with no cast, where the stock refusal draws
     * nothing. WillPlay(100), with that piece recorded as the cast's intent for its resolution's namer
     * ({@link #consultNamed}).
     */
    public static AiAbilityDecision consultTutor(final Player ai, final SpellAbility sa) {
        final ComboTracker t = ComboTracker.of(ai);
        return t == null ? null : consultTutor(ai, sa, t.view());
    }

    /** {@link #consultTutor}, read through the decision's view (ComboExecute.consultation's, which read its
     *  executable lines). The board's gates come first: our own hand cast, the library's size, and a deck line that
     *  does not name Consultation (Sauron's three all do), before any line's signals. */
    static AiAbilityDecision consultTutor(final Player ai, final SpellAbility sa, final ComboTracker.View v) {
        if (!ComboExecute.ownHandCast(ai, sa, ComboLines.Emptier.CONSULTATION) || v.lines().isEmpty()
                || ai.getCardsIn(ZoneType.Library).size() < CONSULT_TUTOR_MIN_LIBRARY || !tutorsFor(v)) {
            return null;
        }
        final ComboTracker t = v.tracker();
        for (final ComboLines.Line l : v.live()) {
            if (v.missing(l).isEmpty()) {
                return null;
            }
        }
        for (final ComboLines.Line l : v.oneShort()) {
            if (l.names().contains(ComboLines.Emptier.CONSULTATION.card)) {
                continue;
            }
            final String name = v.missing(l).get(0);
            if (v.where(name) == ComboTracker.Where.LIBRARY && !t.knownTop(name)) {
                if (!SpecialCardAi.CommanderCastCeiling.affordable(ai, sa)) {
                    return null;
                }
                t.setConsultIntent(sa.getHostCard().getId(), name);
                return new AiAbilityDecision(100, AiPlayDecision.WillPlay);
            }
        }
        return null;
    }

    // whether a deck line does not name Demonic Consultation: one Consultation can tutor for
    private static boolean tutorsFor(final ComboTracker.View v) {
        for (final ComboLines.Line l : v.lines()) {
            if (!l.names().contains(ComboLines.Emptier.CONSULTATION.card)) {
                return true;
            }
        }
        return false;
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
        if (destination == ZoneType.Hand) {
            return Fit.HAND;
        }
        if (destination == ZoneType.Library && "0".equals(libraryPosition)) {
            return Fit.TOP;
        }
        return destination == ZoneType.Battlefield ? Fit.BATTLEFIELD : null;
    }

    /**
     * The lines a fetch putting its card {@code fit} fetches for, in {@link ComboTracker#oneShort}'s rank order: the
     * one-short lines; and, while DANGER holds (follow-up F4, spec section 6.5.3: a combo that wins this turn beats
     * surviving, so no line is one short), for a search or a dig to our hand or the battlefield, the deck's lines
     * exactly one piece short whose only signal DANGER is (neither denied nor NO_MANA, and no surer kill) when the win
     * comes this turn ({@link #winsThisTurn}): our main phase, the winner's own window open this turn and not denied
     * this turn (the view's deniedThisTurn, follow-up 2, G2: a line denied only this turn waits for a later one, too
     * late under danger), and the mana we can make now paying the line with the fetched piece in our hand or on the
     * battlefield. A fetch to the top of our
     * library (Vampiric Tutor, Imperial Seal, the Vault's stack) puts nothing in our hand this turn and keeps the
     * one-short lines, as the piles, Gifts Ungiven and Consultation's tutor do (follow-up final review, round 1).
     */
    private static List<ComboLines.Line> fetchable(final ComboTracker.View v, final Fit fit) {
        final List<ComboLines.Line> oneShort = v.oneShort();
        // a one-short line has no signal, so DANGER does not hold: these are the lines
        if (!oneShort.isEmpty() || fit == Fit.TOP || !ourMainPhase(v.ai()) || !v.danger() || v.surerKill()) {
            return oneShort;
        }
        final List<ComboLines.Line> out = new ArrayList<>();
        for (final ComboLines.Line l : v.lines()) {
            if (v.ready(l) && ComboSignals.abandon(v, l) == ComboSignals.Signal.DANGER && !v.deniedThisTurn(l)
                    && v.missing(l).size() == 1 && winsThisTurn(v, l, fit)) {
                out.add(l);
            }
        }
        return v.ranked(out);
    }

    // our own main phase, whoever is to act: a win this turn casts its pieces at sorcery speed
    private static boolean ourMainPhase(final Player ai) {
        final PhaseHandler ph = ai.getGame().getPhaseHandler();
        return ph.isPlayerTurn(ai) && ph.getPhase() != null && ph.getPhase().isMain();
    }

    // whether l, its one missing piece fetched now (to our hand, or onto our battlefield for fit BATTLEFIELD), wins
    // this turn, read from its winner's own window as ComboExecute opens it (follow-up final review, round 1: the mana
    // alone let a Laboratory Maniac line with no draw to follow, a Doctor Doom cast summoning sick and a Fblthp past
    // combat take the fetch from the stock pick under danger, and the AI stopped playing to live):
    // - Thassa's Oracle: its go-off (ComboExecute.goOffFirst, then the emptier at Oracle's trigger) needs the mana
    //   alone;
    // - Laboratory Maniac and Jace win off a draw of ours after the emptier, and this turn only a spell of our hand
    //   that the AI casts right after it on the emptied library can be that draw (ComboExecute.followUpDraws, the draw
    //   spell's window; the opponent's end step comes after their attack): one of them, its mana paid with the line's;
    // - Doctor Doom wins off its own {T}: it is on our battlefield already, untapped and free of summoning sickness
    //   (ComboExecute.staged), since one cast or put there now cannot tap this turn;
    // - Fblthp, Impossibly Lost wins off its trigger on combat damage to an opponent, which no fetch in a main phase
    //   can count on (none comes after combat; before it, an attack may not be made, or be blocked): never.
    // The mana we can make now (ComboMana, each source assigned to one pip, the sources isManaSourceReserved holds left
    // out) pays together the mana costs of every piece of l still to be cast, ours in our hand, library or command zone
    // (the fetched one, the library's, counted as in our hand, unless the fetch puts it onto our battlefield), the
    // commander tax on one in our command zone (ComboSignals.commanderTax) and, for Laboratory Maniac or Jace, the
    // follow-up draw's. The sources are read against the first such piece's first spell (in Line.names order, the
    // emptier first), else the draw's. A piece on our battlefield or our stack is cast already
    private static boolean winsThisTurn(final ComboTracker.View v, final ComboLines.Line l, final Fit fit) {
        final ComboLines.Winner w = l.winner();
        if (w == ComboLines.Winner.FBLTHP || (w == ComboLines.Winner.DOOM && !ComboExecute.staged(v, w))) {
            return false;
        }
        final List<ManaCost> costs = new ArrayList<>(4);
        SpellAbility spell = null;
        for (final String name : l.names()) {
            final ComboTracker.Where where = v.where(name);
            if (where != ComboTracker.Where.HAND && where != ComboTracker.Where.LIBRARY
                    && where != ComboTracker.Where.COMMAND) {
                continue;
            }
            if (where == ComboTracker.Where.LIBRARY && fit == Fit.BATTLEFIELD) {
                continue;
            }
            final Card c = v.card(name);
            costs.add(c.getManaCost());
            if (spell == null) {
                spell = Iterables.getFirst(c.getSpells(), null);
            }
        }
        final int tax = ComboSignals.commanderTax(v, l);
        if (tax > 0) {
            costs.add(ManaCost.get(tax));
        }
        final Player ai = v.ai();
        if (!ComboExecute.drawsAfter(w)) {
            return spell != null && ComboMana.of(ai, spell, true).pays(costs.toArray(new ManaCost[0]));
        }
        final ComboMana mana = spell == null ? null : ComboMana.of(ai, spell, true);
        for (final Card draw : ComboExecute.followUpDraws(ai)) {
            final ComboMana m = mana != null ? mana
                    : ComboMana.of(ai, Iterables.getFirst(draw.getSpells(), null), true);
            final List<ManaCost> withDraw = new ArrayList<>(costs);
            withDraw.add(draw.getManaCost());
            if (m.pays(withDraw.toArray(new ManaCost[0]))) {
                return true;
            }
        }
        return false;
    }

    // the missing piece of the first fetchable line, by the tracker's rank, that the fetch may take and that the cards
    // hold one of ours of: the first such card in their order
    private static Card pick(final ComboTracker.View v, final Player owner, final Iterable<Card> cards,
            final Fit fit) {
        for (final ComboLines.Line l : fetchable(v, fit)) {
            final String name = v.missing(l).get(0);
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
