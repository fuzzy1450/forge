package forge.sim;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import org.testng.Assert;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import com.google.common.collect.Multimap;

import forge.StaticData;
import forge.card.CardDb;
import forge.card.CardEdition;
import forge.game.card.Card;
import forge.game.card.CounterCustomType;
import forge.game.card.CounterType;
import forge.game.keyword.Keyword;
import forge.item.PaperCard;
import forge.item.PaperToken;
import forge.token.TokenDb;
import forge.trackable.TrackableTypes;
import forge.util.SimScope;

/** The lazily filled static caches a shared JVM fills from several game threads at once.
 *
 *  <p>{@code customCounterTypesSurviveConcurrentFirstUse} is the race detector: the threads
 *  register the same keys at the same time, and against the unsynchronized HashMap it failed
 *  nondeterministically (two objects for one key, a lost entry); after the fix it never fails.
 *
 *  <p>{@code sortableCollectorNumbersAgreeAcrossThreads} is a read-path smoke test only: its
 *  cache is filled before the pool starts, so the threads never write and it also passes
 *  against a plain HashMap.
 *
 *  <p>{@code lookupCachesAreConcurrentMaps} pins the intent for the four lookup caches (Card,
 *  Keyword, TrackableTypes, CardEdition), which have no red-then-green test of their own.
 *
 *  <p>StaticData is not read-only after boot by itself: TokenDb loads tokens and CardDb answers
 *  legend-rule names lazily, from whichever game thread asks first. {@code bootPreloadsEveryEditionToken}
 *  pins that boot loads every token an edition lists; {@code tokensAndLegendRuleNamesAgreeAcrossThreads}
 *  has eight threads ask for the same tokens and names at once. */
public class CacheConcurrencyTest {

    @BeforeClass
    public void boot() {
        GameRunner.boot();                    // the card and token databases
    }

    @AfterClass
    public void relaxStrict() {
        SimScope.setStrict(false);            // boot() arms strict mode
    }

    /** Every token an edition lists and Forge has a script for is loaded by boot. An entry naming a token with no
     *  script (FRC lists the card Gingerbrute among its tokens) cannot be loaded by anyone and is left as it was. */
    @Test
    public void bootPreloadsEveryEditionToken() throws Exception {
        TokenDb tokens = StaticData.instance().getAllTokens();
        Assert.assertFalse(tokens.getAllTokens().isEmpty(), "boot preloaded the tokens");
        Field f = TokenDb.class.getDeclaredField("allTokenByName");
        f.setAccessible(true);
        Multimap<?, ?> loaded = (Multimap<?, ?>) f.get(tokens);
        int listed = 0;
        List<String> missing = new ArrayList<>();
        for (CardEdition e : StaticData.instance().getEditions()) {
            for (String name : e.getTokens().keySet()) {
                listed++;
                if (tokens.containsRule(name) && !loaded.containsKey(String.format("%s_%s", name, e.getCode().toLowerCase()))) {
                    missing.add(e.getCode() + "/" + name);
                }
            }
        }
        Assert.assertTrue(listed > 0, "editions list tokens");
        Assert.assertEquals(missing, List.of(), "of " + listed + " edition tokens, these were left for a game thread to load");
    }

    /** The same tokens and legend-rule names from eight threads at once: every thread gets the same answers, and a
     *  token no edition lists, which getToken creates on first use, is created once, so every thread gets one object. */
    @Test(timeOut = 120_000)
    public void tokensAndLegendRuleNamesAgreeAcrossThreads() throws Exception {
        TokenDb tokens = StaticData.instance().getAllTokens();
        CardDb cards = StaticData.instance().getCommonCards();
        Set<String> inSomeEdition = new HashSet<>();
        for (CardEdition e : StaticData.instance().getEditions()) {
            inSomeEdition.addAll(e.getTokens().keySet());
        }
        List<String> tokenNames = new ArrayList<>(List.of("w_1_1_soldier", "c_a_treasure_sac", "r_1_1_goblin", "g_1_1_saproling", "b_2_2_zombie"));
        final int listed = tokenNames.size();
        Assert.assertTrue(inSomeEdition.containsAll(tokenNames), "the handful of common tokens is listed by an edition");
        List<String> unlisted = tokens.getRules().keySet().stream().filter(n -> !inSomeEdition.contains(n)).sorted()
                .collect(Collectors.toList());
        Assert.assertFalse(unlisted.isEmpty(), "Forge has token scripts that no edition lists");
        tokenNames.addAll(unlisted);
        List<String> cardNames = cards.getUniqueCards().stream().map(PaperCard::getName).sorted().limit(3_000)
                .collect(Collectors.toList());

        final int threads = 8;
        CyclicBarrier go = new CyclicBarrier(threads);                  // every thread asks at the same moment
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<List<Object>>> done = new ArrayList<>();
        List<List<Object>> answers = new ArrayList<>();
        try {
            for (int t = 0; t < threads; t++) {
                done.add(pool.submit(() -> {
                    go.await();
                    List<Object> seen = new ArrayList<>();
                    for (String name : tokenNames) {
                        seen.add(tokens.getToken(name));
                    }
                    for (String name : cardNames) {
                        seen.add(cards.isNonLegendaryCreatureName(name));
                    }
                    return seen;
                }));
            }
            for (Future<List<Object>> f : done) {
                answers.add(f.get(100, TimeUnit.SECONDS));
            }
        } finally {
            pool.shutdownNow();
        }
        List<Object> first = answers.get(0);
        for (List<Object> other : answers) {
            for (int i = 0; i < tokenNames.size(); i++) {
                PaperToken a = (PaperToken) first.get(i), b = (PaperToken) other.get(i);
                Assert.assertNotNull(b, tokenNames.get(i));
                Assert.assertEquals(b.getEdition(), a.getEdition(), tokenNames.get(i));
                Assert.assertSame(b.getRules(), a.getRules(), tokenNames.get(i));
                if (i >= listed) {
                    Assert.assertSame(b, a, "a token no edition lists is created once: " + tokenNames.get(i));
                }
            }
            Assert.assertEquals(other.subList(tokenNames.size(), other.size()), first.subList(tokenNames.size(), first.size()),
                    "the legend-rule answers");
        }
        Field f = CardDb.class.getDeclaredField("nonLegendaryCreatureNames");
        f.setAccessible(true);
        Object map = f.get(cards);
        Assert.assertTrue(map instanceof ConcurrentMap, "CardDb#nonLegendaryCreatureNames is written from every game's"
                + " legend-rule check, so it must be a ConcurrentMap, but is " + map.getClass().getName());
    }

    @Test(timeOut = 120_000)
    public void customCounterTypesSurviveConcurrentFirstUse() throws Exception {
        final int threads = 8, keys = 5_000;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<?>> done = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            done.add(pool.submit(() -> {
                for (int i = 0; i < keys; i++) {
                    CounterCustomType a = CounterCustomType.get("CacheTest-" + i);
                    CounterCustomType b = CounterCustomType.get("CacheTest-" + i);
                    Assert.assertSame(a, b, "one object per key, whoever registered it first");
                }
            }));
        }
        for (Future<?> f : done) {
            f.get(100, TimeUnit.SECONDS);
        }
        pool.shutdown();
        Set<CounterType> all = CounterCustomType.getValues();
        long ours = all.stream().filter(c -> c.toString().startsWith("CacheTest-")).count();
        Assert.assertEquals(ours, keys, "every key registered exactly once");
    }

    @Test(timeOut = 120_000)
    public void sortableCollectorNumbersAgreeAcrossThreads() throws Exception {
        final int threads = 8;
        String[] inputs = { "1", "10", "2a", "100b", "7", "007", "WS6", "S1", "12", "3" };
        String[] expected = new String[inputs.length];
        for (int i = 0; i < inputs.length; i++) {
            expected[i] = CardEdition.getSortableCollectorNumber(inputs[i]);
        }
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<?>> done = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            done.add(pool.submit(() -> {
                for (int round = 0; round < 2_000; round++) {
                    for (int i = 0; i < inputs.length; i++) {
                        Assert.assertEquals(CardEdition.getSortableCollectorNumber(inputs[i]), expected[i]);
                    }
                }
            }));
        }
        for (Future<?> f : done) {
            f.get(100, TimeUnit.SECONDS);
        }
        pool.shutdown();
    }

    /** The four lookup caches (a card's keyword set, a trackable enum type, a sortable collector
     *  number, the UI card cache) are pure caches whose only hazard is the unsynchronized write,
     *  so each must be a ConcurrentMap. Nothing but a race tells the map types apart, so this
     *  reads the private static fields. */
    @Test
    public void lookupCachesAreConcurrentMaps() throws Exception {
        assertConcurrentMap(Card.class, "cp2card");
        assertConcurrentMap(Keyword.class, "cardKeywordSetLookup");
        assertConcurrentMap(TrackableTypes.class, "enumTypes");
        assertConcurrentMap(CardEdition.class, "sortableCollNumberLookup");
    }

    private static void assertConcurrentMap(Class<?> owner, String fieldName) throws Exception {
        Field field = owner.getDeclaredField(fieldName);
        field.setAccessible(true);
        Object map = field.get(null);
        Assert.assertTrue(map instanceof ConcurrentMap, owner.getSimpleName() + "#" + fieldName
                + " must be a ConcurrentMap, but is " + (map == null ? "null" : map.getClass().getName()));
    }
}
