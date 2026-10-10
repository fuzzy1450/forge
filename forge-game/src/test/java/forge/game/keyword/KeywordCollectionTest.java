package forge.game.keyword;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertSame;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.Function;

import org.testng.annotations.Test;

import forge.game.replacement.ReplacementEffect;
import forge.game.spellability.SpellAbility;
import forge.game.staticability.StaticAbility;
import forge.game.trigger.Trigger;

/**
 * The trait rebuilds walk a card's keywords through {@link KeywordCollection#applySpellAbility},
 * {@code applyTrigger}, {@code applyReplacementEffect} and {@code applyStaticAbility}, which walk an array of
 * the keywords kept beside the multimap and dropped before every change to it. These pin that every walk
 * visits exactly the keywords {@link KeywordCollection#getValues()} iterates, in its order, whatever was
 * changed since the walk before and by which method.
 */
public class KeywordCollectionTest {

    /** A list that remembers the collection each addAll was given: a keyword hands over its own trait list. */
    private static final class Recorder<T> extends ArrayList<T> {
        private static final long serialVersionUID = 1L;
        private final List<Collection<?>> given = new ArrayList<>();

        @Override
        public boolean addAll(final Collection<? extends T> c) {
            given.add(c);
            return super.addAll(c);
        }
    }

    /** Each of the four walks visits the keywords getValues() iterates, in that order, and no others. */
    private static void assertWalksFollowValues(final KeywordCollection keywords) {
        final List<KeywordInterface> expected = new ArrayList<>(keywords.getValues());
        final Recorder<SpellAbility> spells = new Recorder<>();
        keywords.applySpellAbility(spells);
        assertVisited(spells.given, expected, KeywordInterface::getAbilities);
        final Recorder<Trigger> triggers = new Recorder<>();
        keywords.applyTrigger(triggers);
        assertVisited(triggers.given, expected, KeywordInterface::getTriggers);
        final Recorder<ReplacementEffect> replacements = new Recorder<>();
        keywords.applyReplacementEffect(replacements);
        assertVisited(replacements.given, expected, KeywordInterface::getReplacements);
        final Recorder<StaticAbility> statics = new Recorder<>();
        keywords.applyStaticAbility(statics);
        assertVisited(statics.given, expected, KeywordInterface::getStaticAbilities);
    }

    private static void assertVisited(final List<Collection<?>> given, final List<KeywordInterface> expected,
            final Function<KeywordInterface, Collection<?>> traits) {
        assertEquals(given.size(), expected.size(), "keywords walked");
        for (int i = 0; i < expected.size(); i++) {
            assertSame(given.get(i), traits.apply(expected.get(i)), "keyword " + i + ", " + expected.get(i));
        }
    }

    /** Flying, Bushido 1, Haste, Bushido 2 and Undying twice: four keywords, two of them with two instances. */
    private static KeywordCollection sixKeywords() {
        final KeywordCollection keywords = new KeywordCollection();
        keywords.addAll(List.of("Flying", "Bushido:1", "Haste", "Bushido:2", "Undying", "Undying"));
        assertEquals(keywords.size(), 6);
        return keywords;
    }

    private static KeywordInterface first(final KeywordCollection keywords, final String original) {
        for (final KeywordInterface k : keywords.getValues()) {
            if (k.getOriginal().equals(original)) {
                return k;
            }
        }
        throw new AssertionError("no " + original);
    }

    @Test
    public void walksFollowTheValuesAndRepeat() {
        final KeywordCollection keywords = sixKeywords();
        assertWalksFollowValues(keywords);
        assertWalksFollowValues(keywords);
    }

    @Test
    public void aKeywordInsertedAfterAWalkIsWalked() {
        final KeywordCollection keywords = sixKeywords();
        assertWalksFollowValues(keywords);
        keywords.insert(Keyword.getInstance("Bushido:3"));
        assertEquals(keywords.size(), 7);
        assertWalksFollowValues(keywords);
        keywords.add("Reach");
        assertEquals(keywords.size(), 8);
        assertWalksFollowValues(keywords);
    }

    @Test
    public void keywordsRemovedByNameAfterAWalkAreNotWalked() {
        final KeywordCollection keywords = sixKeywords();
        assertWalksFollowValues(keywords);
        keywords.remove("Bushido");
        assertEquals(keywords.size(), 4);
        assertWalksFollowValues(keywords);
        keywords.removeAll(List.of("Haste"));
        assertEquals(keywords.size(), 3);
        assertWalksFollowValues(keywords);
    }

    @Test
    public void anInstanceRemovedAfterAWalkIsNotWalked() {
        final KeywordCollection keywords = sixKeywords();
        assertWalksFollowValues(keywords);
        keywords.remove(first(keywords, "Undying"));
        assertEquals(keywords.size(), 5);
        assertWalksFollowValues(keywords);
    }

    @Test
    public void aKeywordRemovedAltogetherAfterAWalkIsNotWalked() {
        // removing the first key moves another into its place in the multimap's key order
        final KeywordCollection keywords = sixKeywords();
        assertWalksFollowValues(keywords);
        keywords.removeAll(Keyword.FLYING);
        assertEquals(keywords.size(), 5);
        assertWalksFollowValues(keywords);
    }

    @Test
    public void instancesRemovedAfterAWalkAreNotWalked() {
        final KeywordCollection keywords = sixKeywords();
        assertWalksFollowValues(keywords);
        keywords.removeInstances(List.of(first(keywords, "Bushido:1"), first(keywords, "Haste")));
        assertEquals(keywords.size(), 4);
        assertWalksFollowValues(keywords);
    }

    @Test
    public void aClearedCollectionWalksNothingAndThenWhatIsAdded() {
        final KeywordCollection keywords = sixKeywords();
        assertWalksFollowValues(keywords);
        keywords.clear();
        assertWalksFollowValues(keywords);
        keywords.add("Vigilance");
        assertEquals(keywords.size(), 1);
        assertWalksFollowValues(keywords);
    }

    @Test
    public void aKeywordChangeWalksItsOwnKeywords() {
        final KeywordCollection source = sixKeywords();
        final KeywordsChange change = new KeywordsChange(new ArrayList<>(source.getValues()), List.<String>of(), false);
        final List<KeywordInterface> expected = new ArrayList<>(change.getKeywords());
        assertEquals(expected.size(), 6);
        for (int walk = 0; walk < 2; walk++) {
            final Recorder<StaticAbility> statics = new Recorder<>();
            change.applyStaticAbility(statics);
            assertVisited(statics.given, expected, KeywordInterface::getStaticAbilities);
            final Recorder<Trigger> triggers = new Recorder<>();
            change.applyTrigger(triggers);
            assertVisited(triggers.given, expected, KeywordInterface::getTriggers);
        }
    }
}
