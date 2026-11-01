package forge.ai.combo;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotNull;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Predicate;
import java.util.function.Supplier;

import forge.ai.AiController;
import forge.ai.PlayerControllerAi;
import forge.deck.Deck;
import forge.deck.DeckSection;
import forge.game.Game;
import forge.game.GameLogEntry;
import forge.game.GameObject;
import forge.game.card.Card;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.model.FModel;
import forge.util.MyRandom;

/**
 * The piece-2 test classes' shared boards (plan 2026-10-29-combo-aware-ai-loops.md, Part B), so that they do not copy
 * each other: loop lines from rows or from the shipped table, the registered deck the tracker reads, a ready card, the
 * AI deadline off, the pool, the AI of a seat, piece 1's stock-parity idiom, a bounded run of the game loop, and an
 * opponent's spell put on the stack by hand (Task B9). Every method is static and keeps nothing between calls.
 */
final class ComboLoopBoards {
    private ComboLoopBoards() {
    }

    /** The lines {@code rows} make, V lines in the table's syntax: before them, each F line they name, as the shipped
     *  combos.txt writes it (F|3,4,5,11,17,28,87,173 for the loop rows), so the table parses; then the claim
     *  ({@link ComboLines#claimed}), in the rows' order. */
    static List<ComboLines.Line> loopLines(final String... rows) {
        final Set<Integer> named = new TreeSet<>();
        for (final String row : rows) {
            final String[] fields = row.split("\\|", -1);
            if (fields.length > 4 && !fields[4].isEmpty()) {
                for (final String id : fields[4].split(",")) {
                    named.add(Integer.parseInt(id.trim()));
                }
            }
        }
        final List<String> all = new ArrayList<>();
        for (final int id : named) {
            final ComboTable.Feature f = ComboTable.shipped().features().get(id);
            assertNotNull(f, "the shipped table has no feature " + id);
            all.add("F|" + f.id() + "|" + f.status() + "|" + f.name());
        }
        all.addAll(Arrays.asList(rows));
        return ComboLines.claimed(ComboTable.parse(all));
    }

    /** The shipped table's lines ({@link ComboLines#shipped}) whose ids are {@code ids}, in that order; an id no
     *  shipped line has fails the test. */
    static List<ComboLines.Line> shippedLoopLines(final String... ids) {
        final List<ComboLines.Line> out = new ArrayList<>();
        for (final String id : ids) {
            ComboLines.Line found = null;
            for (final ComboLines.Line l : ComboLines.shipped()) {
                if (l.variant().id().equals(id)) {
                    found = l;
                    break;
                }
            }
            assertNotNull(found, "no shipped line " + id);
            out.add(found);
        }
        return List.copyOf(out);
    }

    /** Registers {@code main} as {@code p}'s deck's main section, one copy of each, and {@code commander}, unless null,
     *  as its commander: the registered deck the tracker reads (ComboTracker.deckNames). The tracker keeps the match it
     *  makes at its first query, so a board calls this before anything asks the tracker. */
    static void deck(final Player p, final String commander, final String... main) {
        final Deck deck = p.getRegisteredPlayer().getDeck();
        if (commander != null) {
            deck.getOrCreate(DeckSection.Commander).add(commander, 1);
        }
        for (final String name : main) {
            deck.getMain().add(name, 1);
        }
    }

    /** {@code c} untapped and free of summoning sickness; returns it. */
    static Card ready(final Card c) {
        c.setSickness(false);
        c.setTapped(false);
        return c;
    }

    /** The AI deadline off, as served games set it (GameRunner): no answer depends on AITest's 5 s deadline. */
    static void noDeadline(final Game game) {
        game.AI_TIMEOUT = Integer.MAX_VALUE;
        game.AI_CAN_USE_TIMEOUT = false;
    }

    /** The mana in {@code p}'s pool. */
    static int pool(final Player p) {
        return p.getManaPool().totalMana();
    }

    /** The AiController deciding for {@code p}. */
    static AiController aiOf(final Player p) {
        return ((PlayerControllerAi) p.getController()).getAi();
    }

    /** Piece 1's stock parity (ComboExecuteAiTest.assertStockParity): {@code decision}, reseeded alike, once with
     *  {@code ai}'s tracker holding no line and once holding {@code others}, gives the same answer and makes the same
     *  number of random draws; returns the stock answer. The tracker keeps {@code others}. */
    static <T> T assertStockParity(final Supplier<T> decision, final Player ai, final List<ComboLines.Line> others) {
        final ComboTracker t = aiOf(ai).getComboTracker();
        t.useLines(List.of());
        MyRandom.setRandom(new Random(7));
        long before = MyRandom.callCount();
        final T stock = decision.get();
        final long stockDraws = MyRandom.callCount() - before;
        t.useLines(others);
        MyRandom.setRandom(new Random(7));
        before = MyRandom.callCount();
        final T ours = decision.get();
        assertEquals(ours, stock, "the answer moved");
        assertEquals(MyRandom.callCount() - before, stockDraws, "the draws moved");
        return stock;
    }

    /** Steps {@code game}'s main loop ({@code PhaseHandler.mainLoopStep}) while the game is not over and {@code done}
     *  does not hold, at most {@code maxSteps} steps (a bound, no timeOut: LoopProgressTest's idiom); returns the
     *  messages the game log gained meanwhile, in their order. Whether {@code done} came is the caller's to assert. */
    static List<String> stepUntil(final Game game, final Predicate<Game> done, final int maxSteps) {
        final int from = game.getGameLog().size();
        for (int step = 0; step < maxSteps && !game.isGameOver() && !done.test(game); step++) {
            game.getPhaseHandler().mainLoopStep();
        }
        final List<GameLogEntry> all = game.getGameLog().getAllEntries();
        final List<String> out = new ArrayList<>();
        for (int i = from; i < all.size(); i++) {
            out.add(all.get(i).message());
        }
        return out;
    }

    /** {@code opp}'s {@code name}, cast at {@code targets} and alone on {@code game}'s stack: put there by hand,
     *  nothing paid ({@code opp}'s AI is not asked to cast it), {@code opp} its activator and, as its caster, holding
     *  priority (ComboLinesEndToEndAiTest.theirSpellOn's idiom). */
    static Card theirSpellOn(final Game game, final Player opp, final String name, final GameObject... targets) {
        final Card card = theirResponse(game, opp, name, targets);
        assertEquals(game.getStack().size(), 1, name + " is not alone on the stack");
        return card;
    }

    /** {@link #theirSpellOn} above whatever {@code game}'s stack holds (ComboLinesEndToEndAiTest.theirResponse's
     *  idiom): the card built as AITest builds one, {@code targets} set on the first of its spell's parts that targets
     *  (none for a spell that targets nothing), the spell put on the stack, its top. */
    static Card theirResponse(final Game game, final Player opp, final String name, final GameObject... targets) {
        final Card card = Card.fromPaperCard(FModel.getMagicDb().getCommonCards().getCard(name), opp);
        card.setGameTimestamp(game.getNextTimestamp());
        game.getStackZone().add(card);
        final SpellAbility sa = card.getFirstSpellAbility();
        sa.setActivatingPlayer(opp);
        for (SpellAbility part = sa; part != null; part = part.getSubAbility()) {
            if (part.usesTargeting()) {
                for (final GameObject target : targets) {
                    part.getTargets().add(target);
                }
                break;
            }
        }
        game.getStack().add(sa);
        assertEquals(game.getStack().peek().getSourceCard(), card, name + " is not the stack's top");
        assertEquals(game.getPhaseHandler().getPriorityPlayer(), opp, "its caster does not hold priority");
        return card;
    }
}
