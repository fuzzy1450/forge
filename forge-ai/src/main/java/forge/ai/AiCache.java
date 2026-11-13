package forge.ai;

import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.Lists;
import com.google.common.collect.Multimap;
import com.google.common.collect.Multimaps;

import forge.game.Game;
import forge.game.GameObject;
import forge.util.SimScope;

import java.lang.ref.WeakReference;
import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Supplier;

public class AiCache {

    // stores result + args as vector; the GUI's cache: a simulated game keeps its own (see cache())
    private final static Multimap<String, List<Object>> dataMap = Multimaps.synchronizedMultimap(ArrayListMultimap.create());

    public static boolean identity(Object a, Object b) {
        return a == b;
    }

    /**
     * The cache this thread reads and fills. Every AI decision clears it (AiController.chooseSpellAbilityToPlay), and
     * whether a lookup hits or recomputes is part of how a game plays: recomputing evaluateBoardPosition or
     * aiLifeInDanger runs predictNextCombatsRemainingLife, whose Combat takes a CombatView id and whose
     * AiBlockController draws from the game's random stream for its trade blocks. With one process-global map, every
     * concurrent game's decisions also cleared this game's entries, at moments set by thread timing, so a lookup that
     * hits when the game plays alone recomputed instead: the same value, but the stream moved on and every later random
     * choice shifted (one seed tapped a different Island, or skipped a block, from run to run). A simulated game
     * therefore keeps its own map in its SimScope, cleared only by its own decisions, as the global map is when one
     * game runs alone; thread-confined like the rest of the scope (the game thread, and the AI eval thread while the
     * game thread waits on it; a simulated game runs AiAttackController's must-attack checks on its own thread too,
     * never on the common pool). An unbound thread under strict mode is a thread no game adopted and belongs to no
     * game it could cache for: null, so it computes without caching. Unbound otherwise is the GUI, the must-attack
     * checks it runs on the common pool included: the global map, as before.
     */
    private static Multimap<String, List<Object>> cache() {
        SimScope s = SimScope.current();
        if (s != null) {
            return s.scratch(AiCache.class, ArrayListMultimap::create);
        }
        return SimScope.isStrict() ? null : dataMap;
    }

    // the GUI's cache is global for calculations that can be shared between games (a simulated game's is its own)
    // but that also means unwanted collisions need to be considered:
    // for that you can pass Functions that compare the args
    /**
     * The result of {@code func} for these arguments: from a row of {@code key} whose arguments {@code argsCheck} finds
     * the same (each by {@code equals} when it is null), else computed and kept in a new row until the next
     * {@link #clear()}.
     *
     * <p>A game object among the arguments, a {@link Game} or any {@link GameObject} (a player, a card, an ability),
     * each of which holds its whole game, is kept weakly and compared by identity, whatever its checker says. A game
     * copy asks lookups of its own, and its rows used to keep it alive until the next AI decision cleared them: a storm
     * search makes hundreds of copies inside one decision, and every copy whose play asked a lookup stayed, about a
     * thousand whole games where the search itself keeps a dozen, which ran the census on 2.0.15+23fd6633b91 out of
     * heap (MTG_DeckMaker's tools/combo-ai/flat-mc/SHIP-PLAN.md, Task 7b). Held weakly, a copy goes once its search
     * drops it, and no lookup is answered differently: an object the collector has taken can never be passed again, so
     * a row naming it could never be hit again, and while the object lives its row answers as it always did, whenever
     * the collector runs and on every machine. Identity is what makes that exact: under equality an equal object could
     * arrive after the collector took the row's own, and whether the lookup hit would depend on when the collector had
     * run. It is also the only comparison that is right for a game object, since a copy's players and cards equal the
     * real ones by id (GameEntity.equals) and a row must not answer for another game; every caller compares them so.
     * A row whose object is gone stays until the next clear, as every row does, holding nothing of its game.
     */
    public static <T> T getCached(String key, Supplier<T> func, List<BiFunction<Object, Object, Boolean>> argsCheck, Object... args) {
        Multimap<String, List<Object>> cache = cache();
        if (cache == null) {
            return func.get();
        }
        // TODO would like a good strategy to derive default key, but there's no clean way to obtain the method name
        for (List<Object> cached : Lists.newArrayList(cache.get(key))) {
            boolean hit = true;
            for (int i = 0; i < args.length; i++) {
                BiFunction<Object, Object, Boolean> checker = argsCheck == null ? Object::equals : argsCheck.get(i);
                if (!matches(checker, args[i], cached.get(i + 1))) {
                    hit = false;
                    break;
                }
            }
            if (hit) {
                return (T) cached.get(0);
            }
        }
        T result = func.get();
        List<Object> cached = Lists.newArrayList(result);
        for (Object arg : args) {
            cached.add(keep(arg));
        }
        cache.put(key, cached);
        return result;
    }

    /** What a row keeps of an argument: a game object weakly, anything else as it is (see {@link #getCached}). */
    private static Object keep(Object arg) {
        return arg instanceof Game || arg instanceof GameObject ? new Held(arg) : arg;
    }

    /** Whether a row's {@code kept} argument answers for {@code arg}: a game object only when it is that very object
     *  (see {@link #getCached}), anything else when {@code checker} says so. */
    private static boolean matches(BiFunction<Object, Object, Boolean> checker, Object arg, Object kept) {
        if (kept instanceof Held held) {
            return held.is(arg);
        }
        return checker.apply(arg, kept);
    }

    /** A game object that a row names, held weakly (see {@link #getCached}). */
    private static final class Held extends WeakReference<Object> {
        Held(Object o) {
            super(o);
        }

        /** Whether {@code arg} is the very object held, which it never is once the collector has taken that. */
        boolean is(Object arg) {
            Object o = get();
            return o != null && o == arg;
        }
    }

    // TODO add different scopes + staleness indicator
    public static void clear() {
        Multimap<String, List<Object>> cache = cache();
        if (cache != null) {
            cache.clear();
        }
    }

}
