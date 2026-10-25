package forge.ai.combo;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertTrue;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.stream.Collectors;

import org.testng.annotations.Test;

import forge.ai.AITest;
import forge.ai.AiController;
import forge.ai.LobbyPlayerAi;
import forge.ai.PlayerControllerAi;
import forge.game.Game;
import forge.game.GameEndReason;
import forge.game.card.Card;
import forge.game.phase.PhaseHandler;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.spellability.SpellAbilityStackInstance;
import forge.game.zone.ZoneType;
import forge.util.MyRandom;

/**
 * Every empty-library line of piece 1 played to a win (follow-up F6, spec 2026-10-22-combo-aware-ai-core-design.md
 * section 6.4), through the real cast path: the game loop gives the AI priority, and the AI casts from its hand,
 * activates and answers its own triggers as it would in a served game. Nothing is put on the stack by hand, and the
 * AI's tracker holds the shipped table's lines, matched against its own cards.
 *
 * <p>Each game starts in the AI's own Main 1 with a full seven-card hand: the line's pieces and playable junk (cards
 * its lands cast, competing for the line's mana), the lands the line needs on its battlefield, and a library of
 * off-colour creatures the AI draws and cannot cast. The opponent, also an AI, has Commander's 40 life and a 40-card
 * library, so neither the junk's attacks nor the opponent's draws end the game first. Play is bounded
 * (LoopProgressTest's bounded loop, no timeOut): at most {@link #MAX_TURNS} turns and {@link #MAX_STEPS} steps. Each
 * test asserts that the AI won, which card won, and the moments the line's spells and abilities went on the stack.
 */
public class ComboLinesEndToEndAiTest extends AITest {
    private static final String ORACLE = "Thassa's Oracle";
    private static final String CONSULTATION = "Demonic Consultation";
    private static final String PACT = "Tainted Pact";
    private static final String LAB = "Laboratory Maniac";
    private static final String JACE = "Jace, Wielder of Mysteries";
    private static final String DOOM = "Doctor Doom, Unrivaled";
    private static final String FBLTHP = "Fblthp, Impossibly Lost";

    /** Five junk cards in blue and black, each castable off Islands and Swamps, none a draw spell (a draw spell in
     *  hand would open Laboratory Maniac's and Jace's main-phase window, not their end-step one). */
    private static final List<String> JUNK = List.of("Coral Merfolk", "Wind Drake", "Walking Corpse", "Typhoid Rats",
            "Merfolk of the Pearl Trident");

    /** Twenty distinct off-colour creatures: a library Tainted Pact can empty, whose draws the AI cannot cast. */
    private static final List<String> SINGLETON = List.of("Runeclaw Bear", "Grizzly Bears", "Hill Giant", "Craw Wurm",
            "Serra Angel", "Shivan Dragon", "Llanowar Elves", "Gray Ogre", "Giant Spider", "Savannah Lions",
            "Raging Goblin", "Centaur Courser", "Trained Armodon", "Goblin Piker", "Elvish Warrior", "Colossal Dreadmaw",
            "Earth Elemental", "Fire Elemental", "Pearled Unicorn", "Wild Griffin");

    /** The turns a game may take after its first: a bound, not a measure (the slowest line here wins in its ninth). */
    private static final int MAX_TURNS = 20;
    /** The main-loop steps a game may take (LoopProgressTest's playOut: bounded, no timeOut). */
    private static final int MAX_STEPS = 5_000;
    /** The seed every game starts from, so each plays the same way on every run. */
    private static final long SEED = 7;

    private Game game;
    private Player ai;
    private Player opp;
    private ComboTracker t;

    /** One of our spells, abilities or triggers going on the stack: what it was (a spell is its card's name, an
     *  ability its card's name and " ability", a trigger its card's name and " trigger"), whose turn and which phase it
     *  was, the turn's number, everything on the stack then (the top first, this item among it), our library's size
     *  then, and the names of the creatures attacking then (none outside combat). */
    private record Event(String what, Player turn, PhaseType phase, int turnNumber, List<String> stack, int library,
            List<String> attackers) {
    }

    private static AiController aiOf(final Player p) {
        return ((PlayerControllerAi) p.getController()).getAi();
    }

    /** A two-player game in the AI's own Main 1 (the AI is player 1; both play the Default profile), the random
     *  stream seeded, the opponent at 40 life with a 40-card library; the AI's tracker holds the shipped lines. */
    private void start() {
        game = initAndCreateGame();
        ai = game.getPlayers().get(1);
        opp = game.getPlayers().get(0);
        ((LobbyPlayerAi) ai.getLobbyPlayer()).setAiProfile("Default");
        ((LobbyPlayerAi) opp.getLobbyPlayer()).setAiProfile("Default");
        t = aiOf(ai).getComboTracker();
        opp.setLife(40, null);
        fillLibrary(opp, 40);
        MyRandom.setRandom(new Random(SEED));
    }

    private void lands(final String... names) {
        for (final String name : names) {
            addCard(name, ai);
        }
    }

    /** Our hand: {@code pieces}, then the five junk cards, seven cards in all. */
    private void hand(final String... pieces) {
        for (final String name : pieces) {
            addCardToZone(name, ai, ZoneType.Hand);
        }
        for (final String name : JUNK) {
            addCardToZone(name, ai, ZoneType.Hand);
        }
        assertEquals(ai.getCardsIn(ZoneType.Hand).size(), 7, "not a full hand");
    }

    private void library(final List<String> names) {
        for (final String name : names) {
            addCardToZone(name, ai, ZoneType.Library);
        }
    }

    private static String label(final SpellAbilityStackInstance si) {
        final String name = ComboTracker.nameOf(si.getSourceCard());
        return si.isSpell() ? name : si.isTrigger() ? name + " trigger" : name + " ability";
    }

    /**
     * Plays the game, bounded, until it is over, {@link #MAX_TURNS} turns have passed or {@link #MAX_STEPS} steps
     * were taken. Returns, in order, each of our spells, abilities and triggers as it was first seen on the stack after
     * a step: a step ends with every item a player put there still on it, since the stack resolves only once every
     * player has passed. Items first seen after the same step come in the order they went on the stack, the bottom
     * one first (a trigger before the spell cast in response to it).
     */
    private List<Event> play() {
        final List<Event> events = new ArrayList<>();
        final Map<SpellAbilityStackInstance, Boolean> seen = new IdentityHashMap<>();
        final int lastTurn = game.getPhaseHandler().getTurn() + MAX_TURNS;
        int steps = 0;
        do {
            game.getPhaseHandler().mainLoopStep();
            final List<SpellAbilityStackInstance> items = new ArrayList<>();
            final List<String> stack = new ArrayList<>();
            for (final SpellAbilityStackInstance si : game.getStack()) {
                items.add(si);
                stack.add(label(si));
            }
            for (int i = items.size() - 1; i >= 0; i--) {
                final SpellAbilityStackInstance si = items.get(i);
                if (seen.put(si, Boolean.TRUE) == null && ai.equals(si.getActivatingPlayer())) {
                    final PhaseHandler ph = game.getPhaseHandler();
                    final List<String> attackers = new ArrayList<>();
                    if (game.getCombat() != null) {
                        for (final Card c : game.getCombat().getAttackers()) {
                            attackers.add(ComboTracker.nameOf(c));
                        }
                    }
                    events.add(new Event(label(si), ph.getPlayerTurn(), ph.getPhase(), ph.getTurn(), stack,
                            ai.getCardsIn(ZoneType.Library).size(), attackers));
                }
            }
        } while (!game.isGameOver() && game.getPhaseHandler().getTurn() <= lastTurn && ++steps < MAX_STEPS);
        return events;
    }

    /** The first event that is {@code what}. */
    private static Event first(final List<Event> events, final String what) {
        for (final Event e : events) {
            if (e.what().equals(what)) {
                return e;
            }
        }
        throw new AssertionError("never on the stack: " + what + "; our stack items: " + whats(events));
    }

    private static List<String> whats(final List<Event> events) {
        return events.stream().map(Event::what).collect(Collectors.toList());
    }

    /** The AI won by {@code card}'s own effect: the game is over, the AI is its winner, and the win names that card. */
    private void assertWonBy(final String card, final List<Event> events) {
        assertTrue(game.isGameOver(), "the game went on; our stack items: " + whats(events) + "\n"
                + gameStateToString(game));
        assertTrue(ai.hasWon(), "the AI lost: " + game.getOutcome().getWinCondition() + "; our stack items: "
                + whats(events));
        assertEquals(game.getOutcome().getWinCondition(), GameEndReason.WinsGameSpellEffect);
        assertEquals(ai.getOutcome().altWinSourceName, card, "the card that won");
    }

    private void assertLibraryEmpty() {
        assertTrue(ai.getCardsIn(ZoneType.Library).isEmpty(), "our library is not empty: "
                + ai.getCardsIn(ZoneType.Library));
    }

    /** The AI's own turn is the game's now, in the phase the game ended in. */
    private void assertEndedInOurTurn(final PhaseType phase, final int turnNumber) {
        final PhaseHandler ph = game.getPhaseHandler();
        assertEquals(ph.getPlayerTurn(), ai, "the game ended in the opponent's turn");
        assertEquals(ph.getPhase(), phase, "the phase the game ended in");
        assertEquals(ph.getTurn(), turnNumber, "the turn the game ended in");
    }

    /**
     * A library-out winner that wins off a draw (Laboratory Maniac; Jace, Wielder of Mysteries) in hand beside Demonic
     * Consultation and the junk, the given lands, a 20-card library. The AI casts the winner in its own turn, casts
     * Consultation at an opponent's end step, its turn next, and wins at its own draw step.
     */
    private void assertWinsAtTheDraw(final String winner, final String line, final String... lands) {
        start();
        hand(winner, CONSULTATION);
        lands(lands);
        fillLibrary(ai, 20);
        assertEquals(ids(t.deckLines()), List.of(line));

        final List<Event> events = play();
        assertWonBy(winner, events);
        assertLibraryEmpty();
        final Event cast = first(events, winner);
        assertEquals(cast.turn(), ai, winner + " was cast in the opponent's turn");
        final Event consultation = first(events, CONSULTATION);
        assertTrue(events.indexOf(consultation) > events.indexOf(cast), "Consultation went first: " + whats(events));
        assertEquals(consultation.turn(), opp, "Consultation's turn");
        assertEquals(consultation.phase(), PhaseType.END_OF_TURN, "Consultation's phase");
        assertEndedInOurTurn(PhaseType.DRAW, consultation.turnNumber() + 1);
    }

    private static List<String> ids(final List<ComboLines.Line> lines) {
        return lines.stream().map(l -> l.variant().id()).collect(Collectors.toList());
    }

    /** Laboratory Maniac in hand: the AI casts it, casts Demonic Consultation at the opponent's end step, and wins at
     *  its own draw. */
    @Test
    public void labManiacLineWins() {
        assertWinsAtTheDraw(LAB, "742-2836", "Island", "Island", "Island", "Swamp", "Swamp");
    }

    /** The same with Jace, Wielder of Mysteries ({1}{U}{U}{U}). */
    @Test
    public void jaceLineWins() {
        assertWinsAtTheDraw(JACE, "742-3096", "Island", "Island", "Island", "Island", "Swamp", "Swamp");
    }

    /**
     * Doctor Doom, Unrivaled in hand (line x-1, the table's own extra): the AI casts Doom; on a later turn of its own,
     * Doom untapped and free of summoning sickness, it casts Demonic Consultation and then activates Doom's {T}, whose
     * draw on the emptied library wins that turn.
     */
    @Test
    public void doctorDoomLineWins() {
        start();
        hand(DOOM, CONSULTATION);
        lands("Swamp", "Swamp", "Swamp", "Island", "Island");
        fillLibrary(ai, 20);
        assertEquals(ids(t.deckLines()), List.of("x-1"));

        final List<Event> events = play();
        assertWonBy(DOOM, events);
        assertLibraryEmpty();
        final Event cast = first(events, DOOM);
        assertEquals(cast.turn(), ai, "Doom was cast in the opponent's turn");
        final Event consultation = first(events, CONSULTATION);
        assertEquals(consultation.turn(), ai, "Consultation's turn");
        assertTrue(consultation.turnNumber() > cast.turnNumber(), "Consultation came in Doom's own turn");
        final Event draw = first(events, DOOM + " ability");
        assertTrue(events.indexOf(draw) > events.indexOf(consultation), "Doom drew first: " + whats(events));
        assertEquals(draw.turnNumber(), consultation.turnNumber(), "Doom's draw came in another turn");
        assertEquals(draw.library(), 0, "Doom drew on a library Consultation had not emptied");
        final PhaseHandler ph = game.getPhaseHandler();
        assertEquals(ph.getPlayerTurn(), ai);
        assertEquals(ph.getTurn(), consultation.turnNumber());
    }

    /**
     * Fblthp, Impossibly Lost in hand (line 742-8266), the opponent's board empty: the AI casts Fblthp, attacks with it
     * on a later turn, and casts Demonic Consultation in response to Fblthp's combat-damage trigger, whose draw on the
     * emptied library wins that turn.
     */
    @Test
    public void fblthpLineWins() {
        start();
        hand(FBLTHP, CONSULTATION);
        lands("Island", "Island", "Island", "Swamp", "Swamp");
        fillLibrary(ai, 20);
        assertEquals(ids(t.deckLines()), List.of("742-8266"));
        assertTrue(opp.getCreaturesInPlay().isEmpty());

        final List<Event> events = play();
        assertWonBy(FBLTHP, events);
        // the trigger's last part, after its win, shuffles Fblthp into our library: it is all the library holds
        assertEquals(ai.getCardsIn(ZoneType.Library).stream().map(ComboTracker::nameOf).collect(Collectors.toList()),
                List.of(FBLTHP));
        final Event cast = first(events, FBLTHP);
        assertEquals(cast.turn(), ai, "Fblthp was cast in the opponent's turn");
        final Event trigger = first(events, FBLTHP + " trigger");
        assertEquals(trigger.phase(), PhaseType.COMBAT_DAMAGE, "Fblthp's trigger's phase");
        assertTrue(trigger.attackers().contains(FBLTHP), "Fblthp did not attack: " + trigger.attackers());
        assertTrue(trigger.turnNumber() > cast.turnNumber(), "Fblthp's trigger came in the turn it was cast");
        final Event consultation = first(events, CONSULTATION);
        assertTrue(events.indexOf(consultation) > events.indexOf(trigger), "Consultation went first: "
                + whats(events));
        assertEquals(consultation.turnNumber(), trigger.turnNumber(), "Consultation's turn");
        assertTrue(consultation.stack().contains(FBLTHP + " trigger"), "Consultation was not cast in response to "
                + "Fblthp's trigger: " + consultation.stack());
        final PhaseHandler ph = game.getPhaseHandler();
        assertEquals(ph.getPlayerTurn(), ai);
        assertEquals(ph.getTurn(), trigger.turnNumber());
    }

    /**
     * Laboratory Maniac and Tainted Pact in hand (line 2836-3093), a singleton library of twenty off-colour creatures:
     * the AI casts Lab, casts Pact at the opponent's end step, declines every card Pact exiles, so the whole library
     * goes to exile, and wins at its own draw.
     */
    @Test
    public void pactLabLineWins() {
        start();
        hand(LAB, PACT);
        lands("Island", "Island", "Island", "Swamp", "Swamp");
        library(SINGLETON);
        assertTrue(t.singletonLibrary());
        assertEquals(ids(t.deckLines()), List.of("2836-3093"));

        final List<Event> events = play();
        assertWonBy(LAB, events);
        assertLibraryEmpty();
        final Event cast = first(events, LAB);
        assertEquals(cast.turn(), ai, "Lab was cast in the opponent's turn");
        final Event pact = first(events, PACT);
        assertTrue(events.indexOf(pact) > events.indexOf(cast), "Pact went first: " + whats(events));
        assertEquals(pact.turn(), opp, "Pact's turn");
        assertEquals(pact.phase(), PhaseType.END_OF_TURN, "Pact's phase");
        assertTrue(pact.library() > 0);
        // every card Pact exiled was declined: the library it found went to exile whole
        assertEquals(ai.getCardsIn(ZoneType.Exile).size(), pact.library(), "Pact kept a card");
        assertEndedInOurTurn(PhaseType.DRAW, pact.turnNumber() + 1);
    }

    /**
     * The sequencer case on a real board (follow-up F5's goOffFirst): Thassa's Oracle and Demonic Consultation in hand
     * behind the five junk cards, Island, Island and Swamp (the go-off's whole mana), our Grizzly Bears facing the
     * opponent's Hill Giant and Grizzly Bears, the opponent's Mountains in play. No junk card takes the go-off's mana:
     * Oracle is the first spell the AI casts, Consultation answers its trigger, and the AI wins the turn it began in.
     */
    @Test
    public void oracleLineWinsWithAFullHand() {
        start();
        for (final String name : JUNK) {
            addCardToZone(name, ai, ZoneType.Hand);
        }
        addCardToZone(ORACLE, ai, ZoneType.Hand);
        addCardToZone(CONSULTATION, ai, ZoneType.Hand);
        assertEquals(ai.getCardsIn(ZoneType.Hand).size(), 7, "not a full hand");
        lands("Island", "Island", "Swamp");
        addCard("Grizzly Bears", ai).setSickness(false);
        fillLibrary(ai, 20);
        for (final String name : List.of("Hill Giant", "Grizzly Bears", "Mountain", "Mountain", "Mountain")) {
            addCard(name, opp).setSickness(false);
        }
        assertEquals(ids(t.deckLines()), List.of("742-1295"));
        final int turn = game.getPhaseHandler().getTurn();

        final List<Event> events = play();
        assertWonBy(ORACLE, events);
        assertLibraryEmpty();
        assertFalse(events.isEmpty());
        assertEquals(events.get(0).what(), ORACLE, "a junk card went first: " + whats(events));
        final Event consultation = first(events, CONSULTATION);
        assertTrue(consultation.stack().contains(ORACLE + " trigger"), "Consultation was not cast in response to "
                + "Oracle's trigger: " + consultation.stack());
        assertEquals(game.getPhaseHandler().getPlayerTurn(), ai);
        assertEquals(game.getPhaseHandler().getTurn(), turn, "the go-off waited a turn");
        for (final String name : JUNK) {
            assertNotNull(t.find(name, ZoneType.Hand), name + " was cast");
        }
    }
}
