package forge.ai;

import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.Lists;
import com.google.common.collect.Multimap;
import com.google.common.collect.Multimaps;

import forge.util.SimScope;

import java.util.Arrays;
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
                if (!checker.apply(args[i], cached.get(i + 1))) {
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
        cached.addAll(Arrays.asList(args));
        cache.put(key, cached);
        return result;
    }

    // TODO add different scopes + staleness indicator
    public static void clear() {
        Multimap<String, List<Object>> cache = cache();
        if (cache != null) {
            cache.clear();
        }
    }

}
