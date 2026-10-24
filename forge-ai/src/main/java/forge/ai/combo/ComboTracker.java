package forge.ai.combo;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import forge.ai.PlayerControllerAi;
import forge.deck.CardPool;
import forge.deck.Deck;
import forge.deck.DeckSection;
import forge.game.card.Card;
import forge.game.player.Player;
import forge.game.spellability.SpellAbilityStackInstance;
import forge.game.zone.ZoneType;
import forge.item.IPaperCard;
import forge.item.PaperCard;

/**
 * One AI player's combo lines and where their pieces are (spec 2026-10-22-combo-aware-ai-core-design.md section
 * 6.2), held on its AiController, so per game and per seat: nothing here is static, and a serve JVM's concurrent games
 * share nothing through it.
 *
 * <p>Lazy: the constructor reads nothing, because it runs before the player is registered with its game, and
 * GameCopier copies and mind-slave controllers get fresh trackers with no game-start hook. The first query matches the
 * deck against the lines and keeps the result; every other query reads our zones at that moment, since tests'
 * {@code addCardToZone} and the engine's moves change zones without any event this could follow. A decision reads
 * through one {@link View}, which reads each thing once for all its questions and is dropped with the decision. The
 * one other state kept is Demonic Consultation's tutor intent, from its cast decision to its resolution's namer
 * (section 6.3). Nothing here draws a random number or reads the library's order.
 */
public final class ComboTracker {
    /** Where a piece is, read in this order: the first place that holds it wins. GONE is anywhere else, the graveyard
     *  and exile included (piece 1 counts no recursion, section 6.2). */
    public enum Where { BATTLEFIELD, HAND, COMMAND, STACK, LIBRARY, GONE }

    private final Player ai;
    private List<ComboLines.Line> source;
    // the match, null until the first query: the lines the deck holds, and their names, the set read by lookups only
    private List<ComboLines.Line> deckLines;
    private Set<String> pieces;
    // Demonic Consultation's tutor intents (section 6.3), by the id of the Consultation card cast: the piece its cast
    // was approved to fetch, recorded at the cast decision and taken by its resolution's namer; read by lookups only
    private final Map<Integer, String> consultIntents = new HashMap<>();

    /** The tracker of {@code ai}'s lines among {@code source}; nothing is read until a query. */
    public ComboTracker(final Player ai, final List<ComboLines.Line> source) {
        this.ai = ai;
        this.source = List.copyOf(source);
    }

    /** The tracker of the AI deciding for {@code p}, or null when {@code p}'s controller is not a PlayerControllerAi. */
    public static ComboTracker of(final Player p) {
        if (p == null || !(p.getController() instanceof PlayerControllerAi)) {
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
        deckLines = null;
        pieces = null;
    }

    /** The lines whose both names the deck holds, in the source's order: matched at the first query, then kept. The
     *  deck is the registered deck's main section plus its commander; when that is empty, or the player is not
     *  registered, the names of every card the player has (AiDeckStatistics.fromPlayer's fallback). */
    public List<ComboLines.Line> deckLines() {
        if (deckLines == null) {
            match();
        }
        return deckLines;
    }

    /** Whether {@code name} is a card of one of the deck's lines. */
    public boolean isPiece(final String name) {
        deckLines();
        return pieces.contains(name);
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
     *  signal ({@link ComboSignals#abandon}); in {@link #deckLines} order. */
    public List<ComboLines.Line> live() {
        return view().live();
    }

    /** The deck's lines that may be executed now: every piece reachable, an Oracle in play only with its trigger on
     *  our stack, Pact's singleton library, and not {@link ComboSignals#denied}. No mana, danger and a surer kill never
     *  stop a win now. In deckLines order. */
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

    /** Whether {@code c} is a piece of a live line (by {@link #nameOf}): held, never spent for value. */
    public boolean protects(final Card c) {
        return view().protects(c);
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

    private void match() {
        final List<ComboLines.Line> held = new ArrayList<>();
        final Set<String> names = new HashSet<>();
        if (!source.isEmpty()) {
            final Set<String> deck = deckNames();
            for (final ComboLines.Line l : source) {
                if (deck.containsAll(l.names())) {
                    held.add(l);
                    names.addAll(l.names());
                }
            }
        }
        deckLines = List.copyOf(held);
        pieces = names;
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
     * every piece name at once), whether our library is singleton, each deck line's standing prerequisites and denial,
     * the live, executable and one-short lines, and the abandon signals' inputs that no line changes (whether we can
     * win, whether every opponent cannot lose, the danger, the surer kill, next turn's mana, whether a CantBeCast
     * static is in force) with the cast bar by piece and each winner's own reading. So a decision asking for the
     * executable lines, then the live ones, then the one-short ones reads the board once, where each query alone
     * read it again for every line. A view is the local of the decision that makes it and is dropped with it, never
     * kept across a game action; nothing in it is static.
     */
    public final class View {
        private final List<ComboLines.Line> lines = deckLines();
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
        private final byte[] denied = new byte[lines.size()];
        private List<ComboLines.Line> live;
        private List<ComboLines.Line> executable;
        private List<ComboLines.Line> oneShort;
        // the abandon signals' inputs (ComboSignals reads them through the accessors below)
        private Boolean cantWin;
        private Boolean everyOpponentCantLose;
        private Boolean danger;
        private Boolean surerKill;
        private Boolean castBarsInForce;
        private Boolean oracleTriggerDisabled;
        private int nextTurnMana = -1;
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

        /** {@link ComboTracker#oneShort}, read once. */
        public List<ComboLines.Line> oneShort() {
            if (oneShort == null) {
                final List<ComboLines.Line> out = new ArrayList<>();
                for (final ComboLines.Line l : live()) {
                    if (missing(l).size() == 1) {
                        out.add(l);
                    }
                }
                if (out.size() > 1) {
                    final Map<ComboLines.Line, Integer> cost = new IdentityHashMap<>();
                    for (final ComboLines.Line l : out) {
                        cost.put(l, costNow(l));
                    }
                    out.sort((a, b) -> rank(a, cost.get(a), b, cost.get(b)));
                }
                oneShort = List.copyOf(out);
            }
            return oneShort;
        }

        /** {@link ComboTracker#protects}. */
        public boolean protects(final Card c) {
            final String name = nameOf(c);
            if (!pieces.contains(name)) {
                return false;
            }
            for (final ComboLines.Line l : live()) {
                if (l.names().contains(name)) {
                    return true;
                }
            }
            return false;
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

        /** {@link ComboSignals#denied} (kept for a deck line). */
        boolean denied(final ComboLines.Line l) {
            final int i = indexOf(l);
            if (i < 0) {
                return ComboSignals.deniedNow(this, l);
            }
            if (denied[i] == 0) {
                denied[i] = (byte) (ComboSignals.deniedNow(this, l) ? 2 : 1);
            }
            return denied[i] == 2;
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

        boolean danger() {
            if (danger == null) {
                danger = ComboSignals.dangerNow(ai);
            }
            return danger;
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

        boolean castBarsInForce() {
            if (castBarsInForce == null) {
                castBarsInForce = ComboSignals.castBarsInForceNow(ai.getGame());
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
