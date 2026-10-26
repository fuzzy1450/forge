package forge.ai.combo;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.google.common.collect.ImmutableSet;

import forge.ai.PlayerControllerAi;
import forge.deck.CardPool;
import forge.deck.Deck;
import forge.deck.DeckSection;
import forge.game.card.Card;
import forge.game.player.Player;
import forge.game.spellability.SpellAbilityStackInstance;
import forge.game.staticability.StaticAbility;
import forge.game.zone.Zone;
import forge.game.zone.ZoneType;
import forge.item.IPaperCard;
import forge.item.PaperCard;

/**
 * One AI player's combo lines and where their pieces are (spec 2026-10-22-combo-aware-ai-core-design.md section
 * 6.2), held on its AiController, so per game and per seat: nothing here is static, and a serve JVM's concurrent games
 * share nothing through it.
 *
 * <p>Lazy: the constructor reads nothing, because it runs before the player is registered with its game, and
 * GameCopier copies and mind-slave controllers get fresh trackers with no game-start hook (a mind-slave controller's is
 * never asked: {@link #of} answers null while another player controls ours). The first query matches the deck against
 * the lines and keeps the result, published as one immutable object; every other query reads our zones at that
 * moment, since tests' {@code addCardToZone} and the engine's moves change zones without any event this could follow.
 * A decision reads through one {@link View}, which reads each thing once for all its questions and is dropped with the
 * decision. The two other states kept are Demonic Consultation's tutor intent, from its cast decision to its
 * resolution's namer, and the known tops, the cards of ours one of our combo hooks put on top of our library, until
 * each leaves it (section 6.3). Nothing here draws a random number or reads the library's order: a known top is only
 * where we put a card ourselves.
 */
public final class ComboTracker {
    /** Where a piece is, read in this order: the first place that holds it wins. GONE is anywhere else, the graveyard
     *  and exile included (piece 1 counts no recursion, section 6.2). */
    public enum Where { BATTLEFIELD, HAND, COMMAND, STACK, LIBRARY, GONE }

    private final Player ai;
    private List<ComboLines.Line> source;
    // the match, null until the first query: the lines the deck holds and their names, made together and published
    // in this one field as one immutable object, so no reader (an AI evaluation thread running on past its timeout
    // included) sees the lines without their names; each reader reads the field once
    private volatile Match match;
    // Demonic Consultation's tutor intents (section 6.3), by the id of the Consultation card cast: the piece its cast
    // was approved to fetch, recorded at the cast decision and taken by its resolution's namer; read by lookups only
    private final Map<Integer, String> consultIntents = new HashMap<>();
    // the known tops (section 6.3, Evan, 2026-10-24; follow-up F4): the cards of ours that one of our combo hooks put
    // on top of our library, by id, each with the card object it was then. A card that leaves the library and comes
    // back is a new object of the same id (GameAction.changeZone copies it; a move within the library and a shuffle
    // keep it), so the memory ends when the card leaves, whatever comes back. In the order they were put there, and
    // pruned at each read (knownTops, knownTop)
    private final Map<Integer, Card> knownTops = new LinkedHashMap<>();

    /** The tracker of {@code ai}'s lines among {@code source}; nothing is read until a query. */
    public ComboTracker(final Player ai, final List<ComboLines.Line> source) {
        this.ai = ai;
        this.source = List.copyOf(source);
    }

    /** The tracker of the AI deciding for {@code p}, or null when {@code p}'s controller is not a PlayerControllerAi, or
     *  while another player controls {@code p} ({@link Player#isControlled}: Opposition Agent while we search, a
     *  mind-slave turn), whose controller is a fresh one made for that master: its tracker would read our deck's lines
     *  and fetch our missing piece for the player who controls us. */
    public static ComboTracker of(final Player p) {
        if (p == null || p.isControlled() || !(p.getController() instanceof PlayerControllerAi)) {
            return null;
        }
        return ((PlayerControllerAi) p.getController()).getAi().getComboTracker();
    }

    /** The name the table knows {@code c} by: its printed card's (the front face, or {@code A // B} for a split
     *  card), else its current name. */
    public static String nameOf(final Card c) {
        final IPaperCard paper = c.getPaperCard();
        return paper != null ? paper.getName() : c.getName();
    }

    /** Tests only: these lines instead, matched afresh at the next query. */
    public void useLines(final List<ComboLines.Line> source) {
        this.source = List.copyOf(source);
        match = null;
    }

    /** The lines whose both names the deck holds, in the source's order: matched at the first query, then kept. The
     *  deck is the registered deck's main section plus its commander; when that is empty, or the player is not
     *  registered, the names of every card the player has (AiDeckStatistics.fromPlayer's fallback). */
    public List<ComboLines.Line> deckLines() {
        return matched().lines();
    }

    /** Whether {@code name} is a card of one of the deck's lines. */
    public boolean isPiece(final String name) {
        return matched().pieces().contains(name);
    }

    /** Where our card {@code name} is: our battlefield (what we control, whoever owns it), our hand, our command zone,
     *  a spell or ability we put on the stack whose host has that name, our library; else GONE. */
    public Where where(final String name) {
        if (find(name, ZoneType.Battlefield) != null) {
            return Where.BATTLEFIELD;
        }
        if (find(name, ZoneType.Hand) != null) {
            return Where.HAND;
        }
        if (find(name, ZoneType.Command) != null) {
            return Where.COMMAND;
        }
        if (onOurStack(name)) {
            return Where.STACK;
        }
        if (find(name, ZoneType.Library) != null) {
            return Where.LIBRARY;
        }
        return Where.GONE;
    }

    /** The pieces of {@code l} whose only place is our library, in {@link ComboLines.Line#names} order. */
    public List<String> missing(final ComboLines.Line l) {
        return view().missing(l);
    }

    /** Whether every piece of {@code l} is somewhere we can use it from: none is GONE. */
    public boolean reachable(final ComboLines.Line l) {
        return view().reachable(l);
    }

    /** Whether no two cards of our library share a name (by {@link #nameOf}): Tainted Pact's prerequisite. */
    public boolean singletonLibrary() {
        final Set<String> seen = new HashSet<>();
        for (final Card c : ai.getCardsIn(ZoneType.Library)) {
            if (!seen.add(nameOf(c))) {
                return false;
            }
        }
        return true;
    }

    /** The deck's lines the AI chases now (section 6.2): every piece reachable, a Thassa's Oracle in play only while
     *  its entering trigger is on our stack, a singleton library where the emptier is Tainted Pact, and no abandon
     *  signal ({@link ComboSignals#abandon}, its denial read at the line's own window: section 6.5); in
     *  {@link #deckLines} order. */
    public List<ComboLines.Line> live() {
        return view().live();
    }

    /** The deck's lines that may be executed: every piece reachable, an Oracle in play only with its trigger on our
     *  stack, Pact's singleton library, and not {@link ComboSignals#denied}, read at the line's own window (each piece
     *  still to be cast at its moment there, on the turn the line would go off: Oracle's go-off, still to come when
     *  Oracle's cast is decided, reads its trigger's moment). No mana, danger and a surer kill never stop a win now.
     *  The go-off itself also reads its window this turn (ComboExecute.goOffNow). In deckLines order. */
    public List<ComboLines.Line> executable() {
        return view().executable();
    }

    /**
     * The live lines exactly one piece short ({@link #missing} names one), the ones a fetch may complete (section
     * 6.3), ranked nearest first:
     * <ol>
     * <li>the mana their pieces still to be cast cost now ({@link #costNow}). The table's mana value counts only the
     * pieces Spellbook has cast in the combo, so it rates a line whose winner starts on the battlefield (Laboratory
     * Maniac, Doctor Doom, Fblthp at one) as if that winner were in play already, below Thassa's Oracle's three;
     * <li>a line Thassa's Oracle wins: it wins as its two casts resolve, where the others wait on a draw, an untapped
     * turn or combat damage after them;
     * <li>the table's mana value ascending, a negative one last;
     * <li>the variant id.
     * </ol>
     */
    public List<ComboLines.Line> oneShort() {
        return view().oneShort();
    }

    /** The mana the pieces of {@code l} still to be cast cost now: the mana values of those in our hand, library or
     *  command zone (one on our battlefield or our stack is cast already). */
    public int costNow(final ComboLines.Line l) {
        return view().costNow(l);
    }

    /** Whether {@code c} is a card we own, in our hand, library or command zone or on our battlefield, that is a piece
     *  of a live line (by {@link #nameOf}): held, never spent for value. An opponent's card of the same name, ours in
     *  their control, and ours on the stack, in the graveyard or in exile are not. */
    public boolean protects(final Card c) {
        return view().protects(c);
    }

    /**
     * The nearest live combo (spec section 6.2, the seam of section 6.6): the {@link #live} line with the fewest
     * pieces {@link #missing}, ties going by {@link #oneShort}'s ranking (the mana its pieces still to be cast cost
     * now, a Thassa's Oracle line, the table's mana value, the variant id), then the first in {@link #live} order;
     * null when no line is live.
     */
    public ComboLines.Line nearest() {
        return view().nearest();
    }

    /** Records that one of our combo hooks put our card {@code c}, a card of our library, on top of it (ComboFetch: a
     *  search or a dig to the top, a scry, surveil or ordering that keeps the piece first, the Vault's stack): a known
     *  top (section 6.3, Evan, 2026-10-24) until that card leaves our library. */
    void placedOnTop(final Card c) {
        knownTops.put(c.getId(), c);
    }

    /** The ids of our cards that one of our combo hooks put on top of our library and that are still in it (the very
     *  card: not one that left and came back), in the order they were put there. A shuffle is not followed, so a
     *  known top outlives one. */
    public Set<Integer> knownTops() {
        prune();
        return ImmutableSet.copyOf(knownTops.keySet());
    }

    /** Whether a card of ours named {@code name} (by {@link #nameOf}) is a known top ({@link #knownTops}): Demonic
     *  Consultation never tutors for it ({@link ComboFetch#consultTutor}), since the six cards it exiles first would
     *  take it, and the whole library with it. The memory only ever refuses a tutor, never chooses one. */
    public boolean knownTop(final String name) {
        prune();
        for (final Card c : knownTops.values()) {
            if (nameOf(c).equals(name)) {
                return true;
            }
        }
        return false;
    }

    // drops each known top that is no longer in our library: no card of our library is that very object
    private void prune() {
        if (knownTops.isEmpty()) {
            return;
        }
        final List<Card> library = new ArrayList<>(ai.getCardsIn(ZoneType.Library));
        knownTops.values().removeIf(c -> !holdsItself(library, c));
    }

    // whether the cards hold that very object (not a card of the same id: Card.equals compares ids)
    private static boolean holdsItself(final List<Card> cards, final Card c) {
        for (final Card in : cards) {
            if (in == c) {
                return true;
            }
        }
        return false;
    }

    /** A reading of this tracker for one decision ({@link View}). */
    public View view() {
        return new View();
    }

    /** Whether a triggered ability we control whose host is named {@code name} is on the stack: a fired trigger,
     *  which nothing that arrives after it can stop from triggering (Thassa's Oracle's, waiting for its emptier). */
    boolean triggerOnOurStack(final String name) {
        for (final SpellAbilityStackInstance si : ai.getGame().getStack()) {
            final Card host = si.getSourceCard();
            if (si.isTrigger() && host != null && ai.equals(si.getActivatingPlayer()) && nameOf(host).equals(name)) {
                return true;
            }
        }
        return false;
    }

    /** Records that the Demonic Consultation card {@code hostId} is cast as a tutor for {@code name}
     *  ({@link ComboFetch#consultTutor}), replacing any earlier intent of that card. */
    public void setConsultIntent(final int hostId, final String name) {
        consultIntents.put(hostId, name);
    }

    /** The piece the Demonic Consultation card {@code hostId} was last approved to fetch, removed; null when none is
     *  recorded ({@link ComboFetch#consultNamed}). */
    public String takeConsultIntent(final int hostId) {
        return consultIntents.remove(hostId);
    }

    // oneShort's rank: the mana still to cast, then a Thassa's Oracle line first, then byTable
    private static int rank(final ComboLines.Line a, final int costA, final ComboLines.Line b, final int costB) {
        if (costA != costB) {
            return Integer.compare(costA, costB);
        }
        final boolean oracleA = a.winner() == ComboLines.Winner.ORACLE;
        final boolean oracleB = b.winner() == ComboLines.Winner.ORACLE;
        if (oracleA != oracleB) {
            return oracleA ? -1 : 1;
        }
        return byTable(a, b);
    }

    // the table's mana value ascending, a negative one (none known) last, then the variant id
    private static int byTable(final ComboLines.Line a, final ComboLines.Line b) {
        final int ma = a.variant().manaValue() < 0 ? Integer.MAX_VALUE : a.variant().manaValue();
        final int mb = b.variant().manaValue() < 0 ? Integer.MAX_VALUE : b.variant().manaValue();
        if (ma != mb) {
            return Integer.compare(ma, mb);
        }
        return a.variant().id().compareTo(b.variant().id());
    }

    /** Our first card named {@code name} in {@code zone} (by {@link #nameOf}, in the zone's order), or null. */
    public Card find(final String name, final ZoneType zone) {
        for (final Card c : ai.getCardsIn(zone)) {
            if (nameOf(c).equals(name)) {
                return c;
            }
        }
        return null;
    }

    private boolean onOurStack(final String name) {
        return ourStackHost(name) != null;
    }

    // the host of the first spell or ability we put on the stack whose host has that name, or null
    private Card ourStackHost(final String name) {
        for (final SpellAbilityStackInstance si : ai.getGame().getStack()) {
            final Card host = si.getSourceCard();
            if (host != null && ai.equals(si.getActivatingPlayer()) && nameOf(host).equals(name)) {
                return host;
            }
        }
        return null;
    }

    // the match, made at the first query and kept: read into a local once, so a reader never mixes two matches
    private Match matched() {
        Match m = match;
        if (m == null) {
            m = matchNow();
            match = m;
        }
        return m;
    }

    private Match matchNow() {
        final List<ComboLines.Line> held = new ArrayList<>();
        final Set<String> names = new LinkedHashSet<>();
        final List<ComboLines.Line> from = source;
        if (!from.isEmpty()) {
            final Set<String> deck = deckNames();
            for (final ComboLines.Line l : from) {
                if (deck.containsAll(l.names())) {
                    held.add(l);
                    names.addAll(l.names());
                }
            }
        }
        return new Match(List.copyOf(held), ImmutableSet.copyOf(names));
    }

    /** The deck's match: the lines it holds ({@link #deckLines}) and their pieces' names (read by lookups only), made
     *  together and immutable, so one reference publishes both. */
    private record Match(List<ComboLines.Line> lines, Set<String> pieces) {
    }

    private Set<String> deckNames() {
        final Set<String> names = new HashSet<>();
        Deck deck;
        try {
            deck = ai.getRegisteredPlayer().getDeck();
        } catch (final RuntimeException e) {
            // not registered with its game (getRegisteredPlayer's indexOf is -1), as inside the AiController
            // constructor: read the cards the player has instead
            deck = null;
        }
        if (deck != null) {
            addNames(names, deck.getMain());
            addNames(names, deck.get(DeckSection.Commander));
        }
        if (names.isEmpty()) {
            // a test, or some weird match: the cards the player has
            for (final Card c : ai.getAllCards()) {
                names.add(nameOf(c));
            }
        }
        return names;
    }

    private static void addNames(final Set<String> names, final CardPool pool) {
        if (pool == null) {
            return;
        }
        for (final Map.Entry<PaperCard, Integer> e : pool) {
            names.add(e.getKey().getName());
        }
    }

    // the zone a Where reads (an if chain, not an enum switch: javac would add a synthetic switch-map class)
    private static ZoneType zoneOf(final Where w) {
        if (w == Where.BATTLEFIELD) {
            return ZoneType.Battlefield;
        }
        if (w == Where.HAND) {
            return ZoneType.Hand;
        }
        return w == Where.COMMAND ? ZoneType.Command : ZoneType.Library;
    }

    /**
     * One reading of this tracker, for one decision (spec sections 6.2 and 7: a decision's cost stays O(1) amortised;
     * final review, round 1). It reads what the tracker's own queries read, at the moment of each first use, and keeps
     * it for the decision's later questions: where each piece is (one pass over each of our zones and our stack, for
     * every piece name at once), whether our library is singleton, each deck line's standing prerequisites, its denial
     * now and at its window and its window's bars on the turn the board opens it, the live, executable, executable-now
     * and one-short lines, and the abandon signals'
     * inputs that no line changes (whether we can win, whether every opponent cannot lose, the danger, the surer kill,
     * next turn's mana, the CantBeCast statics on the board and whether one is in force) with the cast bar by piece and
     * each winner's own reading. So a decision asking for the executable lines, then the live ones, then the one-short
     * ones reads the board once, where each query alone read it again for every line. A view is the local of the
     * decision that makes it and is dropped with it, never kept across a game action; nothing in it is static.
     */
    public final class View {
        // the match, read once: this view's lines and piece names come from the one published object
        private final Match deck = matched();
        private final List<ComboLines.Line> lines = deck.lines();
        private final Set<String> pieces = deck.pieces();
        // the first card of each piece name (by nameOf, in the zone's order) in each of our zones and among the hosts
        // of our stack items, and the piece names with a trigger of ours on the stack; read together, at the first
        // place lookup
        private Map<String, Card> battlefield;
        private Map<String, Card> hand;
        private Map<String, Card> command;
        private Map<String, Card> library;
        private Map<String, Card> stack;
        private Set<String> stackTriggers;
        private Boolean singleton;
        // per deck line, by its index in lines: 0 unread, 1 no, 2 yes
        private final byte[] ready = new byte[lines.size()];
        private final byte[] deniedNow = new byte[lines.size()];
        private final byte[] denied = new byte[lines.size()];
        private final byte[] windowBarred = new byte[lines.size()];
        private List<ComboLines.Line> live;
        private List<ComboLines.Line> executable;
        private List<ComboLines.Line> executableNow;
        private List<ComboLines.Line> oneShort;
        // the abandon signals' inputs (ComboSignals reads them through the accessors below)
        private Boolean cantWin;
        private Boolean everyOpponentCantLose;
        private Boolean danger;
        private Boolean surerKill;
        private List<StaticAbility> castBarStatics;
        private Boolean castBarsInForce;
        private Boolean oracleTriggerDisabled;
        private int nextTurnMana = -1;
        // the attackers-by-blockers matrices the danger reading built in this view: one for each opponent whose whole
        // attack, unblocked, reaches our life (follow-up 2, G5), read by the view's tests
        private int blockMatrixBuilds;
        private final Map<String, Boolean> castBarred = new HashMap<>(4);
        // per winner, by its ordinal: 0 unread, 1 armed, 2 disarmed
        private final byte[] disarmed = new byte[ComboLines.Winner.values().length];

        private View() {
        }

        /** The tracker this view reads. */
        public ComboTracker tracker() {
            return ComboTracker.this;
        }

        /** The deck's lines ({@link ComboTracker#deckLines}). */
        public List<ComboLines.Line> lines() {
            return lines;
        }

        /** {@link ComboTracker#where}, read once with every other piece. */
        public Where where(final String name) {
            if (!pieces.contains(name)) {
                return ComboTracker.this.where(name);
            }
            index();
            if (battlefield.containsKey(name)) {
                return Where.BATTLEFIELD;
            }
            if (hand.containsKey(name)) {
                return Where.HAND;
            }
            if (command.containsKey(name)) {
                return Where.COMMAND;
            }
            if (stack.containsKey(name)) {
                return Where.STACK;
            }
            return library.containsKey(name) ? Where.LIBRARY : Where.GONE;
        }

        /** Our card named {@code name} where {@link #where} places it: that zone's first one, or the host of our first
         *  stack item of that name; null when it is GONE. */
        public Card card(final String name) {
            final Where w = where(name);
            if (w == Where.GONE) {
                return null;
            }
            if (!pieces.contains(name)) {
                return w == Where.STACK ? ourStackHost(name) : find(name, zoneOf(w));
            }
            if (w == Where.BATTLEFIELD) {
                return battlefield.get(name);
            }
            if (w == Where.HAND) {
                return hand.get(name);
            }
            if (w == Where.COMMAND) {
                return command.get(name);
            }
            return w == Where.STACK ? stack.get(name) : library.get(name);
        }

        /** {@link ComboTracker#triggerOnOurStack}, read once with every other piece. */
        boolean triggerOnOurStack(final String name) {
            if (!pieces.contains(name)) {
                return ComboTracker.this.triggerOnOurStack(name);
            }
            index();
            return stackTriggers.contains(name);
        }

        /** {@link ComboTracker#singletonLibrary}, read once. */
        public boolean singletonLibrary() {
            if (singleton == null) {
                singleton = ComboTracker.this.singletonLibrary();
            }
            return singleton;
        }

        /** {@link ComboTracker#missing}. */
        public List<String> missing(final ComboLines.Line l) {
            final List<String> out = new ArrayList<>(2);
            for (final String name : l.names()) {
                if (where(name) == Where.LIBRARY) {
                    out.add(name);
                }
            }
            return List.copyOf(out);
        }

        /** {@link ComboTracker#reachable}. */
        public boolean reachable(final ComboLines.Line l) {
            for (final String name : l.names()) {
                if (where(name) == Where.GONE) {
                    return false;
                }
            }
            return true;
        }

        /** {@link ComboTracker#costNow}. */
        public int costNow(final ComboLines.Line l) {
            int cost = 0;
            for (final String name : l.names()) {
                final Where w = where(name);
                if (w == Where.HAND || w == Where.LIBRARY || w == Where.COMMAND) {
                    cost += card(name).getCMC();
                }
            }
            return cost;
        }

        /** {@link ComboTracker#live}, read once. */
        public List<ComboLines.Line> live() {
            if (live == null) {
                final List<ComboLines.Line> out = new ArrayList<>();
                for (final ComboLines.Line l : lines) {
                    if (ready(l) && ComboSignals.abandon(this, l) == null) {
                        out.add(l);
                    }
                }
                live = List.copyOf(out);
            }
            return live;
        }

        /** {@link ComboTracker#executable}, read once. */
        public List<ComboLines.Line> executable() {
            if (executable == null) {
                final List<ComboLines.Line> out = new ArrayList<>();
                for (final ComboLines.Line l : lines) {
                    if (ready(l) && !denied(l)) {
                        out.add(l);
                    }
                }
                executable = List.copyOf(out);
            }
            return executable;
        }

        /** The deck's lines that may be executed at a window open now, read once: as {@link #executable}, but denied
         *  as read now ({@link ComboSignals#deniedNow}), the moment of every window ComboExecute opens. So the window
         *  of a draw spell after the emptier in our main phase, Laboratory Maniac's or Jace's, reads its own moment:
         *  under Teferi, Time Raveler it is open, though the line, whose own window is the opponent's end step, is
         *  denied. In deckLines order. */
        List<ComboLines.Line> executableNow() {
            if (executableNow == null) {
                final List<ComboLines.Line> out = new ArrayList<>();
                for (final ComboLines.Line l : lines) {
                    if (ready(l) && !deniedNow(l)) {
                        out.add(l);
                    }
                }
                executableNow = List.copyOf(out);
            }
            return executableNow;
        }

        /** {@link ComboTracker#oneShort}, read once. */
        public List<ComboLines.Line> oneShort() {
            if (oneShort == null) {
                final List<ComboLines.Line> out = new ArrayList<>();
                for (final ComboLines.Line l : live()) {
                    if (missing(l).size() == 1) {
                        out.add(l);
                    }
                }
                oneShort = ranked(out);
            }
            return oneShort;
        }

        /** {@code unranked} in {@link #oneShort}'s rank order (the mana still to cast now, a Thassa's Oracle line, the
         *  table's mana value, the variant id; a stable sort), as an immutable list. */
        List<ComboLines.Line> ranked(final List<ComboLines.Line> unranked) {
            final List<ComboLines.Line> out = new ArrayList<>(unranked);
            if (out.size() > 1) {
                final Map<ComboLines.Line, Integer> cost = new IdentityHashMap<>();
                for (final ComboLines.Line l : out) {
                    cost.put(l, costNow(l));
                }
                out.sort((a, b) -> rank(a, cost.get(a), b, cost.get(b)));
            }
            return List.copyOf(out);
        }

        /** {@link ComboTracker#nearest}: the first live line with the fewest missing pieces, ties by
         *  {@link #oneShort}'s rank. */
        public ComboLines.Line nearest() {
            ComboLines.Line best = null;
            int bestMissing = 0;
            int bestCost = 0;
            for (final ComboLines.Line l : live()) {
                final int missing = missing(l).size();
                final int cost = costNow(l);
                if (best == null || missing < bestMissing
                        || (missing == bestMissing && rank(l, cost, best, bestCost) < 0)) {
                    best = l;
                    bestMissing = missing;
                    bestCost = cost;
                }
            }
            return best;
        }

        /** {@link ComboTracker#protects}. */
        public boolean protects(final Card c) {
            final String name = nameOf(c);
            if (!pieces.contains(name) || !ownedInOurZone(c)) {
                return false;
            }
            for (final ComboLines.Line l : live()) {
                if (l.names().contains(name)) {
                    return true;
                }
            }
            return false;
        }

        // c is a card we own, in our hand, library or command zone or on our battlefield (so one we control)
        private boolean ownedInOurZone(final Card c) {
            final Zone zone = c.getZone();
            return ai.equals(c.getOwner()) && zone != null && (zone.is(ZoneType.Hand, ai)
                    || zone.is(ZoneType.Library, ai) || zone.is(ZoneType.Command, ai)
                    || zone.is(ZoneType.Battlefield, ai));
        }

        /** A line's standing prerequisites (kept for a deck line): no piece gone; Thassa's Oracle in play wins only
         *  through its entering trigger, so a spent one (that trigger resolved, countered or never fired) is no line
         *  until such a trigger of ours is on the stack; and Tainted Pact empties only a library with no two cards of
         *  one name (Spellbook's stated prerequisite: basics break it). */
        boolean ready(final ComboLines.Line l) {
            final int i = indexOf(l);
            if (i < 0) {
                return readyNow(l);
            }
            if (ready[i] == 0) {
                ready[i] = (byte) (readyNow(l) ? 2 : 1);
            }
            return ready[i] == 2;
        }

        /** {@link ComboSignals#denied} (kept for a deck line): denied at the line's own window
         *  ({@link ComboSignals#deniedAtWindow}, follow-up 2, G2), the denial that live and executable read. */
        boolean denied(final ComboLines.Line l) {
            final int i = indexOf(l);
            if (i < 0) {
                return ComboSignals.deniedAtWindow(this, l);
            }
            if (denied[i] == 0) {
                denied[i] = (byte) (ComboSignals.deniedAtWindow(this, l) ? 2 : 1);
            }
            return denied[i] == 2;
        }

        /** {@link ComboSignals#barredAtWindowNow} (kept for a deck line): a piece still to be cast barred at the
         *  line's window on the turn the board opens it, this turn while it can still open this turn. */
        boolean windowBarred(final ComboLines.Line l) {
            final int i = indexOf(l);
            if (i < 0) {
                return ComboSignals.barredAtWindowNow(this, l);
            }
            if (windowBarred[i] == 0) {
                windowBarred[i] = (byte) (ComboSignals.barredAtWindowNow(this, l) ? 2 : 1);
            }
            return windowBarred[i] == 2;
        }

        /** The denial of a line executed this turn (G2; follow-up F3's denial): denied now
         *  ({@link ComboSignals#deniedNow}), or a piece barred at its window this turn while the board can still open
         *  it this turn ({@link #windowBarred}). The go-off reads it (ComboExecute.goOffNow), and the win this turn
         *  under danger (ComboFetch): a line whose window this turn is barred waits for a later turn, and is held for
         *  it, but goes off in none this turn. */
        boolean deniedThisTurn(final ComboLines.Line l) {
            return deniedNow(l) || windowBarred(l);
        }

        /** {@link ComboSignals#deniedNow} (kept for a deck line): the denial at a window open now. */
        boolean deniedNow(final ComboLines.Line l) {
            final int i = indexOf(l);
            if (i < 0) {
                return ComboSignals.deniedNow(this, l);
            }
            if (deniedNow[i] == 0) {
                deniedNow[i] = (byte) (ComboSignals.deniedNow(this, l) ? 2 : 1);
            }
            return deniedNow[i] == 2;
        }

        // ---- the abandon signals' inputs, each read at its first use ----

        boolean cantWin() {
            if (cantWin == null) {
                cantWin = ai.cantWin();
            }
            return cantWin;
        }

        boolean everyOpponentCantLose() {
            if (everyOpponentCantLose == null) {
                everyOpponentCantLose = ComboSignals.everyOpponentCantLose(ai);
            }
            return everyOpponentCantLose;
        }

        // signal 3, read once a view and only when asked: the block matrices it builds are built once a view
        boolean danger() {
            if (danger == null) {
                danger = ComboSignals.dangerNow(this);
            }
            return danger;
        }

        /** How many attackers-by-blockers matrices this view's danger reading built ({@link ComboSignals#dangerNow}):
         *  none before {@link #danger} is first asked, then at most one for each opponent, and none for an opponent
         *  whose whole attack, unblocked, falls short of our life. */
        int blockMatrixBuilds() {
            return blockMatrixBuilds;
        }

        // the danger reading built one more block matrix
        void blockMatrixBuilt() {
            blockMatrixBuilds++;
        }

        boolean surerKill() {
            if (surerKill == null) {
                surerKill = ComboSignals.surerKillNow(ai);
            }
            return surerKill;
        }

        int nextTurnMana() {
            if (nextTurnMana < 0) {
                nextTurnMana = ComboSignals.nextTurnManaNow(ai);
            }
            return nextTurnMana;
        }

        // the CantBeCast statics of the cards in a static-ability source zone, whatever their conditions now
        List<StaticAbility> castBarStatics() {
            if (castBarStatics == null) {
                castBarStatics = ComboSignals.castBarStaticsNow(ai.getGame());
            }
            return castBarStatics;
        }

        boolean castBarsInForce() {
            if (castBarsInForce == null) {
                castBarsInForce = ComboSignals.castBarsInForceNow(castBarStatics());
            }
            return castBarsInForce;
        }

        boolean castBarred(final String name) {
            Boolean barred = castBarred.get(name);
            if (barred == null) {
                barred = ComboSignals.castBarredNow(this, name);
                castBarred.put(name, barred);
            }
            return barred;
        }

        boolean oracleTriggerDisabled() {
            if (oracleTriggerDisabled == null) {
                oracleTriggerDisabled = ComboSignals.oracleTriggerDisabledNow(this);
            }
            return oracleTriggerDisabled;
        }

        boolean disarmed(final ComboLines.Winner w) {
            if (disarmed[w.ordinal()] == 0) {
                disarmed[w.ordinal()] = (byte) (ComboSignals.disarmedNow(this, w) ? 2 : 1);
            }
            return disarmed[w.ordinal()] == 2;
        }

        Player ai() {
            return ai;
        }

        private boolean readyNow(final ComboLines.Line l) {
            if (!reachable(l)) {
                return false;
            }
            final String oracle = ComboLines.Winner.ORACLE.card;
            if (l.winner() == ComboLines.Winner.ORACLE && where(oracle) == Where.BATTLEFIELD
                    && !triggerOnOurStack(oracle)) {
                return false;
            }
            return l.emptier() != ComboLines.Emptier.PACT || singletonLibrary();
        }

        // the deck line's index, by identity; -1 for a line that is not one of them
        private int indexOf(final ComboLines.Line l) {
            for (int i = 0; i < lines.size(); i++) {
                if (lines.get(i) == l) {
                    return i;
                }
            }
            return -1;
        }

        // every piece's first card in each of our zones and among our stack's hosts, and the pieces with a trigger of
        // ours on the stack: one pass each
        private void index() {
            if (battlefield != null) {
                return;
            }
            battlefield = firsts(ZoneType.Battlefield);
            hand = firsts(ZoneType.Hand);
            command = firsts(ZoneType.Command);
            library = firsts(ZoneType.Library);
            stack = new HashMap<>(4);
            stackTriggers = new HashSet<>(4);
            for (final SpellAbilityStackInstance si : ai.getGame().getStack()) {
                final Card host = si.getSourceCard();
                if (host == null || !ai.equals(si.getActivatingPlayer())) {
                    continue;
                }
                final String name = nameOf(host);
                if (pieces.contains(name)) {
                    stack.putIfAbsent(name, host);
                    if (si.isTrigger()) {
                        stackTriggers.add(name);
                    }
                }
            }
        }

        private Map<String, Card> firsts(final ZoneType zone) {
            final Map<String, Card> out = new HashMap<>(8);
            for (final Card c : ai.getCardsIn(zone)) {
                final String name = nameOf(c);
                if (pieces.contains(name)) {
                    out.putIfAbsent(name, c);
                }
            }
            return out;
        }
    }
}
