package forge.ai.combo;

import java.util.ArrayList;
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
 * follow. Nothing here draws a random number or reads the library's order.
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
