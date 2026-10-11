package forge.card;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;

import org.testng.annotations.Test;

/**
 * CardType keeps its "Saga", "Adventure" and "Omen" lookups, which isSaga, isAdventure and isOmen answer from,
 * until its subtypes change. These pin that the three answers are hasSubtype's for types built every way, and
 * that every method changing the subtypes after the answers were asked drops them, so that the next answers see
 * the change. Every expectation holds whether or not the type lists are loaded: the types are incomplete (which
 * sanisfySubtypes leaves alone) wherever a change would otherwise run it with a core type.
 */
public class CardTypeRuleSubtypesTest {

    private static final String[] NAMES = {"Saga", "Adventure", "Omen"};

    private static boolean[] answers(final CardType t) {
        return new boolean[] {t.isSaga(), t.isAdventure(), t.isOmen()};
    }

    /** The kept answers are hasSubtype's, which reads the sets every time. */
    private static void assertAnswersAreHasSubtypes(final CardType t) {
        final boolean[] a = answers(t);
        for (int i = 0; i < NAMES.length; i++) {
            assertEquals(a[i], t.hasSubtype(NAMES[i]), NAMES[i] + " of " + t);
        }
    }

    /** The answers are yes for the names given and no for the others, and hasSubtype agrees. */
    private static void assertPresent(final CardType t, final String... present) {
        final List<String> expected = Arrays.asList(present);
        final boolean[] a = answers(t);
        for (int i = 0; i < NAMES.length; i++) {
            assertEquals(a[i], expected.contains(NAMES[i]), NAMES[i] + " of " + t);
        }
        assertAnswersAreHasSubtypes(t);
    }

    private static String[] allBut(final String name) {
        final List<String> rest = new ArrayList<>(Arrays.asList(NAMES));
        rest.remove(name);
        return rest.toArray(new String[0]);
    }

    /** An incomplete enchantment with all three names among its subtypes, its answers asked. */
    private static CardType withAllThree() {
        final CardType t = new CardType(true);
        t.add("Enchantment");
        for (final String name : NAMES) {
            t.add(name);
        }
        assertPresent(t, NAMES);
        return t;
    }

    private static CardType changed(final CardType base, final ICardChangedType change) {
        return (CardType) base.getTypeWithChanges(List.of(change));
    }

    @Test
    public void theAnswersAreHasSubtypesForTypesBuiltEveryWay() {
        final List<CardType> types = new ArrayList<>();
        types.add(new CardType(false));
        types.add(new CardType(true));
        types.add(CardType.parse("Enchantment Saga", false));
        types.add(CardType.parse("Instant Adventure", false));
        types.add(CardType.parse("Sorcery Omen", false));
        types.add(CardType.parse("Legendary Creature Giant", false));
        types.add(CardType.parse("Kindred Instant Elf", false));
        types.add(new CardType(Arrays.asList("Enchantment", "Saga", "Adventure", "Omen"), true));
        types.add(new CardType(Arrays.asList("Enchantment", "Saga"), false));
        types.add((CardType) CardType.EMPTY);
        final List<CardType> derived = new ArrayList<>();
        for (final CardType t : types) {
            derived.add(new CardType(t));
            derived.add(new CardType((CardTypeView) t));
            derived.add(CardType.combine(t, CardType.parse("Instant Omen", false)));
            // a creature with all creature types: the answers then come from hasSubtype itself
            derived.add(changed(t, new CardChangedType(CardType.parse("Creature", false), null, true,
                    EnumSet.noneOf(RemoveType.class))));
            derived.add(changed(t, new CardChangedType(null, null, false, EnumSet.of(RemoveType.SubTypes))));
            derived.add(changed(t, new WordChangedType("Saga", "Adventure")));
        }
        types.addAll(derived);
        for (final CardType t : types) {
            assertAnswersAreHasSubtypes(t);
            // asked again, from the kept lookups
            assertAnswersAreHasSubtypes(t);
        }
    }

    @Test
    public void aChangelingStillAnswersThroughHasSubtype() {
        final CardType changeling = changed(CardType.parse("Creature Shapeshifter", false),
                new CardChangedType(null, null, true, EnumSet.noneOf(RemoveType.class)));
        assertTrue(changeling.hasAllCreatureTypes());
        assertAnswersAreHasSubtypes(changeling);
        changeling.add("Saga");
        assertTrue(changeling.isSaga());
        assertAnswersAreHasSubtypes(changeling);
    }

    @Test
    public void addIsSeen() {
        for (final String name : NAMES) {
            final CardType t = CardType.parse("Enchantment", false);
            assertPresent(t);
            t.add(name);
            assertPresent(t, name);
        }
    }

    @Test
    public void addAllOfNamesIsSeen() {
        for (final String name : NAMES) {
            final CardType t = new CardType(true);
            assertPresent(t);
            t.addAll(Arrays.asList("Sorcery", name));
            assertPresent(t, name);
        }
    }

    @Test
    public void addAllOfATypeIsSeen() {
        for (final String name : NAMES) {
            final CardType t = new CardType(true);
            assertPresent(t);
            t.addAll(CardType.parse("Sorcery " + name, false));
            assertPresent(t, name);
        }
    }

    @Test
    public void addAllOfATypeViewIsSeen() {
        for (final String name : NAMES) {
            final CardType t = new CardType(true);
            assertPresent(t);
            t.addAll((CardTypeView) CardType.parse("Sorcery " + name, false));
            assertPresent(t, name);
        }
    }

    @Test
    public void removeAllIsSeen() {
        for (final String name : NAMES) {
            final CardType t = withAllThree();
            t.removeAll(CardType.parse(name, false));
            assertPresent(t, allBut(name));
        }
    }

    @Test
    public void removeIsSeen() {
        for (final String name : NAMES) {
            final CardType t = withAllThree();
            t.remove(name);
            assertPresent(t, allBut(name));
        }
    }

    @Test
    public void clearIsSeen() {
        final CardType t = withAllThree();
        t.clear();
        assertPresent(t);
    }

    @Test
    public void setCreatureTypesIsSeen() {
        for (final String name : NAMES) {
            final CardType t = new CardType(true);
            t.add("Creature");
            assertPresent(t);
            t.setCreatureTypes(List.of(name));
            assertPresent(t, name);
        }
    }

    @Test
    public void sanisfySubtypesIsSeen() {
        for (final String name : NAMES) {
            // no core type allows any subtype, whatever the type lists say
            final CardType t = new CardType(false);
            t.add(name);
            assertPresent(t, name);
            t.sanisfySubtypes();
            assertPresent(t);
        }
    }

    @Test
    public void aChangedTypeAnswersForItselfAndLeavesItsBaseAlone() {
        final CardType base = CardType.parse("Enchantment Saga", false);
        assertPresent(base, "Saga");
        assertPresent(changed(base, new CardChangedType(null, null, false, EnumSet.of(RemoveType.SubTypes))));
        assertPresent(base, "Saga");
    }

    @Test
    public void aChangelingHasTheNamesThatAreCreatureTypes() {
        // None of the three names is a creature type in the shipped lists, but an edition file can add creature types
        // when it loads. Once the names are creature types, a creature or kindred with all creature types has each
        // of them through hasCreatureType alone, which only the live lookup behind the kept answers can see
        final List<String> registered = new ArrayList<>();
        try {
            for (final String name : NAMES) {
                if (!CardType.isACreatureType(name)) {
                    CardType.Helper.parseTypes("CreatureTypes", List.of(name));
                    registered.add(name);
                }
            }
            final CardType changeling = changed(CardType.parse("Creature Shapeshifter", false),
                    new CardChangedType(null, null, true, EnumSet.noneOf(RemoveType.class)));
            assertTrue(changeling.hasAllCreatureTypes());
            assertPresent(changeling, NAMES);
            // asked again, from the kept lookups
            assertPresent(changeling, NAMES);

            final CardType kindred = changed(CardType.parse("Kindred Enchantment", false),
                    new CardChangedType(null, null, true, EnumSet.noneOf(RemoveType.class)));
            assertTrue(kindred.hasAllCreatureTypes());
            assertPresent(kindred, NAMES);

            // a creature type the change takes out of all creature types is not had
            final CardType allButOmen = changed(CardType.parse("Creature Shapeshifter", false),
                    new CardChangedType(null, CardType.parse("Omen", true), true, EnumSet.noneOf(RemoveType.class)));
            assertTrue(allButOmen.hasAllCreatureTypes());
            assertPresent(allButOmen, "Saga", "Adventure");
        } finally {
            CardType.Constant.CREATURE_TYPES.removeAll(registered);
        }
    }
}
