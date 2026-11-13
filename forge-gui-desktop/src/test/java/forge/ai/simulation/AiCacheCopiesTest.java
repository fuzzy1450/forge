package forge.ai.simulation;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;

import java.lang.ref.Reference;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;

import org.testng.annotations.Test;

import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.Multimap;

import forge.ai.AITest;
import forge.ai.AiCache;
import forge.ai.ComputerUtil;
import forge.ai.ComputerUtilCombat;
import forge.game.Game;
import forge.game.card.Card;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.zone.ZoneType;
import forge.util.SimScope;

/**
 * The AI's cache and the game copies a search makes (MTG_DeckMaker's tools/combo-ai/flat-mc/SHIP-PLAN.md, Task 7b:
 * the census on 2.0.15+23fd6633b91 ran out of heap). AiCache keeps a row for every lookup until the next AI decision
 * clears it, and a copy asks lookups of its own, with its game or its players among the arguments. While a row held
 * them, every copy that asked one stayed alive until that decision was over, and a storm search makes hundreds of
 * copies inside one decision: about a thousand whole games at once in the census's Glint-Eye games, where the search
 * itself keeps about a dozen. A row now keeps a game object weakly and compares it by identity, so a copy goes once its
 * search drops it, and the row answers as it did for as long as its object lives.
 *
 * <p>Every game here is made on {@link AITest}, two AI seats with no AI option, as a served game's default seats are,
 * so the copies take the heuristic AI from their lobby players, as StormSolverScopeTest's do. Each body runs on a
 * fresh game thread bound to a fresh {@link SimScope}, as {@link Scoped} runs one, and the cache it fills is that
 * scope's own.
 */
public class AiCacheCopiesTest extends AITest {
    /** The seed of every scope here. */
    private static final long SEED = 1L;
    /** StormGoldfishTest's Beseech Storm main deck, the library of StormSolverScopeTest's Beseech line. */
    private static final Object[] BESEECH_STORM = {
        "Dark Ritual", 4, "Lion's Eye Diamond", 4, "Lotus Petal", 4, "Chrome Mox", 4, "Mox Opal", 4,
        "Giant's Boulder", 2, "Infernal Tutor", 4, "Beseech the Mirror", 2, "Burning Wish", 3, "Thoughtseize", 4,
        "Duress", 3, "Hexing Squelcher", 2, "Gaea's Will", 1, "Tendrils of Agony", 1, "Wishclaw Talisman", 1,
        "Urza's Saga", 4, "Vault of Whispers", 4, "Spire of Industry", 3, "City of Traitors", 2, "Swamp", 4 };
    /** A payment outside the boxes that Integer.valueOf shares, so that each lookup passes an object of its own. */
    private static final int PAYMENT = 1_000_003;
    /** The state cap of the storm search here: a search that finds no line expands this many states. */
    private static final int STATES = 100;

    /**
     * A storm search whose copies ask the cache, as the census's Glint-Eye games' did, and which finds no line, so that
     * it runs to its state cap. Summoner's Pact costs nothing, so every state that holds it casts it, and in each such
     * copy the Pact's fetch, all of it creatures, has ChangeZoneAi.chooseCreature ask whether the AI's life is in
     * danger (aiLifeInDanger: a row naming the copy's player), whose prediction of the opponent's Grizzly Bears
     * attacking asks whether combat damage is prevented this turn (a row naming the copy's game). Once the search has
     * returned, and before any decision clears the cache, a collection takes every copy those rows name.
     */
    @Test
    public void aStormSearchKeepsNoCopyAliveOnceItReturns() {
        final Player p = beseechLineWithPact();
        final int[] seen = Scoped.under(new SimScope(SEED), () -> {
            StormSolver.solve(p, tutorStorm(p), STATES, Long.MAX_VALUE);
            final Named named = namedCopies(p.getGame());
            return new int[] { named.rows, named.copies.size(), alive(named.copies) };
        });
        assertTrue(seen[0] > 0, "the search's copies asked the cache: no row names one");
        assertEquals(seen[2], 0, "a collection leaves " + seen[2] + " of the " + seen[1] + " copies that " + seen[0]
                + " of the cache's rows name alive");
    }

    /**
     * The three lookups that name a copy's objects, asked in copies as a search's children ask them: the copy's
     * player in aiLifeInDanger and evaluateBoardPosition, and its game in predictDamageTo's question whether combat
     * damage is prevented. Once the copies are dropped, a collection takes every one, before any decision clears the
     * cache. A copy still in use keeps its row: after the collection its lookup is answered from the cache, with a
     * payment equal to the one the row holds but not the same object, so a value is kept and compared by its checker
     * as before; and a clear still empties the cache.
     */
    @Test
    public void aCopysLookupsLetItGoWhileALiveCopysRowStillAnswers() {
        final Game game = initAndCreateGame();
        noDeadline(game);
        final Player p = game.getPlayers().get(1);
        addCard("Grizzly Bears", game.getPlayers().get(0));
        final String key = "AiCacheCopiesTest.live";
        final int[] seen = Scoped.under(new SimScope(SEED), () -> {
            final Player live = new GameSimulator(game, p).getSimulatedAiPlayer(); // its search still uses this copy
            final List<BiFunction<Object, Object, Boolean>> checks = List.of(AiCache::identity, Objects::equals);
            final AtomicInteger computed = new AtomicInteger();
            AiCache.getCached(key, computed::incrementAndGet, checks, live, Integer.valueOf(PAYMENT));
            final int left = alive(copiesThatAsked(game, p, 5));
            AiCache.getCached(key, computed::incrementAndGet, checks, live, Integer.valueOf(PAYMENT));
            final int afterCollection = computed.get();
            AiCache.clear();
            AiCache.getCached(key, computed::incrementAndGet, checks, live, Integer.valueOf(PAYMENT));
            return new int[] { left, afterCollection, computed.get() };
        });
        assertEquals(seen[0], 0, "a collection leaves " + seen[0] + " of the 5 dropped copies alive");
        assertEquals(seen[1], 1, "the live copy's lookup is answered from its row after the collection");
        assertEquals(seen[2], 2, "and a clear still empties the cache: the same lookup computes again");
    }

    /**
     * A row compares a game object by identity even when its lookup's checker asks for equality. A copy's player
     * equals the real one by id (GameEntity.equals), and neither may be answered from the other's row: the real game
     * from a rollout's, or a rollout from the real game's. It is also what makes keeping the object weakly exact: an
     * equal object could otherwise arrive after the collector took the row's own, and whether the lookup hit would
     * depend on when the collector had run.
     */
    @Test
    public void aGameObjectIsComparedByIdentityWhateverItsChecker() {
        final Game game = initAndCreateGame();
        noDeadline(game);
        final Player p = game.getPlayers().get(1);
        final String key = "AiCacheCopiesTest.equal";
        final int[] seen = Scoped.under(new SimScope(SEED), () -> {
            final Player copy = new GameSimulator(game, p).getSimulatedAiPlayer();
            assertTrue(copy.equals(p) && copy != p, "the copy's player equals the real one by id, another object");
            final List<BiFunction<Object, Object, Boolean>> equal = List.of(Objects::equals);
            final AtomicInteger computed = new AtomicInteger();
            AiCache.getCached(key, computed::incrementAndGet, equal, p);
            AiCache.getCached(key, computed::incrementAndGet, equal, copy);
            final int afterCopy = computed.get();
            AiCache.getCached(key, computed::incrementAndGet, equal, p);
            return new int[] { afterCopy, computed.get() };
        });
        assertEquals(seen[0], 2, "the copy's player is not answered from the real player's row");
        assertEquals(seen[1], 2, "and the real player is still answered from its own");
    }

    /** The AI deadline off, as served games have it. */
    private static void noDeadline(final Game game) {
        game.AI_TIMEOUT = Integer.MAX_VALUE;
        game.AI_CAN_USE_TIMEOUT = false;
    }

    /**
     * StormSolverScopeTest's Beseech line, with Summoner's Pact beside it in the hand, two Grizzly Bears in the
     * library for the Pact to fetch, and a Grizzly Bears on the opponent's side to attack next turn; the opponent is at
     * 40 life, out of the line's reach.
     */
    private Player beseechLineWithPact() {
        final Game game = initAndCreateGame();
        noDeadline(game);
        final Player p = game.getPlayers().get(1);
        for (int i = 0; i < BESEECH_STORM.length; i += 2) {
            for (int n = 0; n < (Integer) BESEECH_STORM[i + 1]; n++) {
                addCardToZone((String) BESEECH_STORM[i], p, ZoneType.Library);
            }
        }
        addCardToZone("Grizzly Bears", p, ZoneType.Library);
        addCardToZone("Grizzly Bears", p, ZoneType.Library);
        for (final String name : List.of("Beseech the Mirror", "Dark Ritual", "Tendrils of Agony")) {
            final Card c = fromLibrary(p, name);
            p.getZone(ZoneType.Library).remove(c);
            p.getZone(ZoneType.Hand).add(c);
        }
        addCardToZone("Summoner's Pact", p, ZoneType.Hand);
        addCards("Swamp", 6, p);
        addCard("Wishclaw Talisman", p);
        final Player opp = game.getPlayers().get(0);
        opp.setLife(40, null);
        addCard("Grizzly Bears", opp);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, p);
        game.getAction().checkStateEffects(true);
        game.getStack().getSpellsCastThisTurn().clear();
        return p;
    }

    private static Card fromLibrary(final Player p, final String name) {
        for (final Card c : p.getCardsIn(ZoneType.Library)) {
            if (c.getName().equals(name)) {
                return c;
            }
        }
        throw new AssertionError(name + " is not in the library");
    }

    /** The seat's combos, read in the scope bound here, as the AI reads them. */
    private static ComboPlan tutorStorm(final Player p) {
        final ComboPlan combos = DeckPlan.forSetting("auto", p).combos;
        assertTrue(combos.has(ComboPlan.Kind.TUTOR_STORM), "the deck reads as tutor storm: " + combos.summary());
        return combos;
    }

    /**
     * {@code n} copies of {@code game} as {@code p} sees it, each of which asks the three lookups that name a copy's
     * objects; nothing here keeps a copy once this returns.
     */
    private static List<WeakReference<Game>> copiesThatAsked(final Game game, final Player p, final int n) {
        final List<WeakReference<Game>> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            final GameSimulator sim = new GameSimulator(game, p);
            sim.releaseCopier();
            final Player ai = sim.getSimulatedAiPlayer();
            final Player opp = ai.getOpponents().getFirst();
            final Card bears = opp.getCreaturesInPlay().getFirst();
            ComputerUtil.aiLifeInDanger(ai, false, 0);
            ComputerUtil.evaluateBoardPosition(ai, opp);
            ComputerUtilCombat.predictDamageTo(ai, 2, bears, true);
            out.add(new WeakReference<>(sim.getSimulatedGameState()));
        }
        return out;
    }

    /** How many of {@code refs} still reach their object once the collector has been asked to run, up to 20 times. */
    private static int alive(final List<? extends Reference<?>> refs) {
        for (int i = 0; i < 20 && reached(refs) > 0; i++) {
            System.gc();
            try {
                Thread.sleep(50);
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return reached(refs);
    }

    private static int reached(final List<? extends Reference<?>> refs) {
        int n = 0;
        for (final Reference<?> r : refs) {
            if (r.get() != null) {
                n++;
            }
        }
        return n;
    }

    /**
     * How many of a cache's rows name a copy, by holding an object of a game other than the real one or a reference
     * whose object the collector has taken (the real game's objects all live), and a weak reference to each such game
     * still alive.
     */
    private static final class Named {
        int rows;
        final List<WeakReference<Game>> copies = new ArrayList<>();
    }

    /** {@link Named} for the cache of the scope bound here, as it stands. */
    private static Named namedCopies(final Game real) {
        final Multimap<String, List<Object>> cache =
                SimScope.current().scratch(AiCache.class, ArrayListMultimap::create);
        final Named out = new Named();
        final Set<Game> games = Collections.newSetFromMap(new IdentityHashMap<>());
        for (final List<Object> row : cache.values()) {
            boolean namesCopy = false;
            for (final Object element : row) {
                Object o = element;
                if (o instanceof Reference<?> r) {
                    o = r.get();
                    if (o == null) {
                        namesCopy = true;
                        continue;
                    }
                }
                final Game g = gameOf(o);
                if (g != null && g != real) {
                    namesCopy = true;
                    games.add(g);
                }
            }
            if (namesCopy) {
                out.rows++;
            }
        }
        for (final Game g : games) {
            out.copies.add(new WeakReference<>(g));
        }
        return out;
    }

    /** The game {@code o} belongs to when it is a game, a player or a card; null otherwise. */
    private static Game gameOf(final Object o) {
        if (o instanceof Game g) {
            return g;
        }
        if (o instanceof Player pl) {
            return pl.getGame();
        }
        return o instanceof Card c ? c.getGame() : null;
    }
}
