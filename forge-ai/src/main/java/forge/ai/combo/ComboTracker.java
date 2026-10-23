package forge.ai.combo;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
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
 * deck against the lines and keeps the result; every other query scans our zones at that moment for the names it is
 * asked about, since tests' {@code addCardToZone} and the engine's moves change zones without any event this could
 * follow. The one other state kept is Demonic Consultation's tutor intent, from its cast decision to its resolution's
 * namer (section 6.3). Nothing here draws a random number or reads the library's order.
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
        final List<String> out = new ArrayList<>(2);
        for (final String name : l.names()) {
            if (where(name) == Where.LIBRARY) {
                out.add(name);
            }
        }
        return List.copyOf(out);
    }

    /** Whether every piece of {@code l} is somewhere we can use it from: none is GONE. */
    public boolean reachable(final ComboLines.Line l) {
        for (final String name : l.names()) {
            if (where(name) == Where.GONE) {
                return false;
            }
        }
        return true;
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
        final List<ComboLines.Line> out = new ArrayList<>();
        for (final ComboLines.Line l : deckLines()) {
            if (ready(l) && ComboSignals.abandon(ai, l, this) == null) {
                out.add(l);
            }
        }
        return List.copyOf(out);
    }

    /** The deck's lines that may be executed now: every piece reachable, an Oracle in play only with its trigger on
     *  our stack, Pact's singleton library, and not {@link ComboSignals#denied}. No mana, danger and a surer kill never
     *  stop a win now. In deckLines order. */
    public List<ComboLines.Line> executable() {
        final List<ComboLines.Line> out = new ArrayList<>();
        for (final ComboLines.Line l : deckLines()) {
            if (ready(l) && !ComboSignals.denied(ai, l, this)) {
                out.add(l);
            }
        }
        return List.copyOf(out);
    }

    /** The live lines exactly one piece short ({@link #missing} names one), the ones a fetch may complete (section
     *  6.3), ranked: the table's mana value ascending, a negative one last, then the variant id. */
    public List<ComboLines.Line> oneShort() {
        final List<ComboLines.Line> out = new ArrayList<>();
        for (final ComboLines.Line l : live()) {
            if (missing(l).size() == 1) {
                out.add(l);
            }
        }
        out.sort(ComboTracker::byCost);
        return List.copyOf(out);
    }

    /** Whether {@code c} is a piece of a live line (by {@link #nameOf}): held, never spent for value. */
    public boolean protects(final Card c) {
        final String name = nameOf(c);
        if (!isPiece(name)) {
            return false;
        }
        for (final ComboLines.Line l : live()) {
            if (l.names().contains(name)) {
                return true;
            }
        }
        return false;
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

    // a line's standing prerequisites: no piece gone; Thassa's Oracle in play wins only through its entering trigger,
    // so a spent one (that trigger resolved, countered or never fired) is no line until such a trigger of ours is on
    // the stack; and Tainted Pact empties only a library with no two cards of one name (Spellbook's stated
    // prerequisite: basics break it)
    private boolean ready(final ComboLines.Line l) {
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

    // the table's mana value ascending, a negative one (none known) last, then the variant id
    private static int byCost(final ComboLines.Line a, final ComboLines.Line b) {
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
        for (final SpellAbilityStackInstance si : ai.getGame().getStack()) {
            final Card host = si.getSourceCard();
            if (host != null && ai.equals(si.getActivatingPlayer()) && nameOf(host).equals(name)) {
                return true;
            }
        }
        return false;
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
}
