package forge.game.card;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import org.apache.commons.lang3.StringUtils;
import org.testng.Assert;
import org.testng.annotations.Test;

import forge.StaticData;
import forge.ai.AITest;
import forge.card.CardDb;
import forge.card.CardDb.CardArtPreference;
import forge.card.CardRarity;
import forge.card.CardRules;
import forge.card.GamePieceType;
import forge.game.Game;
import forge.game.player.Player;
import forge.item.IPaperCard;
import forge.item.PaperCard;

/**
 * A card built without a paper card (a token the game makes from its own characteristics, as every token of a Flat MC
 * rollout's game copy is, an effect, an LKI copy of either) looks its paper card up by its name and set. Card keeps that
 * lookup's answers; these tests pin that every answer is the one the lookup itself gives, the same object, for each
 * kind of card, after the same card's name or set changes, and after the card database changes what it answers.
 */
public class CardPaperCardLookupTest extends AITest {

    /** The lookup as Card.getPaperCard ran it before it kept answers: five database lookups, nothing kept. */
    static IPaperCard uncached(final Card c) {
        final String name = c.getName();
        final String set = c.getSetCode();
        if (name.isEmpty()) {
            return null;
        }
        IPaperCard cp;
        if (StringUtils.isNotBlank(set)) {
            cp = StaticData.instance().getVariantCards().getCard(name, set);
            if (cp != null) {
                return cp;
            }
            cp = StaticData.instance().getCommonCards().getCard(name, set);
            if (cp != null) {
                return cp;
            }
        }
        cp = StaticData.instance().getVariantCards().getCard(name);
        if (cp != null) {
            return cp;
        }
        CardArtPreference pref = StaticData.instance().getCardArtPreference();
        if (pref == null) {
            pref = CardArtPreference.ORIGINAL_ART_CORE_EXPANSIONS_REPRINT_ONLY;
        }
        cp = StaticData.instance().getCommonCards().getCardFromEditions(name, pref);
        if (cp != null) {
            return cp;
        }
        return StaticData.instance().getCommonCards().getCard(name);
    }

    /** Asserts the card's answer is the lookup's, the same object, on a first and on a later call. */
    private static IPaperCard assertSameAsLookup(final Card c, final String what) {
        final IPaperCard expected = uncached(c);
        Assert.assertSame(c.getPaperCard(), expected, what + ": the first answer is the lookup's");
        Assert.assertSame(c.getPaperCard(), expected, what + ": a kept answer is the lookup's");
        Assert.assertSame(c.getRules(), expected == null ? null : expected.getRules(), what + ": and so are its rules");
        return expected;
    }

    private static Card noPaperCard(final Game game, final String name, final String set) {
        final Card c = new Card(game.nextCardId(), game);
        c.setName(name);
        c.setSetCode(set);
        return c;
    }

    /** Two editions that print {@code name}, whose print by name and set differs. */
    private static String[] twoEditionsOf(final String name) {
        final Map<String, PaperCard> byEdition = new TreeMap<>();
        for (final PaperCard pc : StaticData.instance().getCommonCards().getAllCards(name)) {
            byEdition.putIfAbsent(pc.getEdition(), pc);
        }
        final List<String> editions = new ArrayList<>();
        final Set<IPaperCard> seen = new HashSet<>();
        for (final String edition : byEdition.keySet()) {
            final IPaperCard pc = StaticData.instance().getCommonCards().getCard(name, edition);
            if (pc != null && seen.add(pc)) {
                editions.add(edition);
            }
            if (editions.size() == 2) {
                return editions.toArray(new String[0]);
            }
        }
        throw new AssertionError(name + " has fewer than two editions: " + byEdition.keySet());
    }

    @Test
    public void aTokenNamedLikeNoCardHasNoPaperCard() {
        final Game game = initAndCreateGame();
        final Card token = noPaperCard(game, "Soldier Token", "TC21");
        token.setGamePieceType(GamePieceType.TOKEN);
        Assert.assertNull(assertSameAsLookup(token, "a token named like no card"));
        final Card unset = noPaperCard(game, "Zombie Token", null);
        Assert.assertNull(assertSameAsLookup(unset, "the same with no set"));
        final Card blank = noPaperCard(game, "Zombie Token", " ");
        Assert.assertNull(assertSameAsLookup(blank, "and a blank set"));
    }

    @Test
    public void aTokenCopyOfARealCardHasThatCardsPrint() {
        final Game game = initAndCreateGame();
        final String[] editions = twoEditionsOf("Sol Ring");
        for (final String edition : editions) {
            final Card copy = noPaperCard(game, "Sol Ring", edition);
            copy.setGamePieceType(GamePieceType.TOKEN);
            final IPaperCard pc = assertSameAsLookup(copy, "a token copy of Sol Ring from " + edition);
            Assert.assertNotNull(pc);
            Assert.assertEquals(pc.getEdition(), edition, "the print of the copy's set");
        }
        // a set that does not print it, and no set at all: the art preference's print
        Assert.assertNotNull(assertSameAsLookup(noPaperCard(game, "Sol Ring", "TC21"), "Sol Ring from a set without it"));
        Assert.assertNotNull(assertSameAsLookup(noPaperCard(game, "Sol Ring", null), "Sol Ring with no set"));
        Assert.assertNotNull(assertSameAsLookup(noPaperCard(game, "sol ring", null), "a name in another case"));
    }

    @Test
    public void anEffectAndAnEmptyNameHaveTheLookupsAnswer() {
        final Game game = initAndCreateGame();
        final Card effect = noPaperCard(game, "Sol Ring's Effect", "C21");
        effect.setGamePieceType(GamePieceType.EFFECT);
        Assert.assertNull(assertSameAsLookup(effect, "an effect"));
        final Card emblem = noPaperCard(game, "Emblem - Teferi, Hero of Dominaria", "DOM");
        emblem.setGamePieceType(GamePieceType.EFFECT);
        Assert.assertNull(assertSameAsLookup(emblem, "an emblem"));
        Assert.assertNull(noPaperCard(game, "", "C21").getPaperCard(), "no name, no lookup");
    }

    @Test
    public void anLkiCopyTakesTheLookupsAnswerAsItsPaperCard() {
        final Game game = initAndCreateGame();
        final Player p = game.getPlayers().get(0);
        final String edition = twoEditionsOf("Llanowar Elves")[1];
        final Card token = noPaperCard(game, "Llanowar Elves", edition);
        token.setOwner(p);
        token.setGamePieceType(GamePieceType.TOKEN);
        final IPaperCard pc = assertSameAsLookup(token, "a token copy of Llanowar Elves");
        final Card lki = CardCopyService.getLKICopy(token);
        Assert.assertSame(lki.getPaperCard(), pc, "the LKI copy is built on the token's answer");

        final Card nothing = noPaperCard(game, "Soldier Token", "TC21");
        nothing.setOwner(p);
        final Card lkiOfNothing = CardCopyService.getLKICopy(nothing);
        Assert.assertNull(assertSameAsLookup(lkiOfNothing, "the LKI copy of a token named like no card"));
    }

    @Test
    public void theSameCardsAnswerFollowsItsNameAndItsSet() {
        final Game game = initAndCreateGame();
        final String[] editions = twoEditionsOf("Sol Ring");
        final Card c = noPaperCard(game, "Sol Ring", editions[0]);
        final IPaperCard first = assertSameAsLookup(c, "Sol Ring from " + editions[0]);

        c.setSetCode(editions[1]);
        final IPaperCard second = assertSameAsLookup(c, "the same card from " + editions[1]);
        Assert.assertNotSame(second, first, "another set, another print");

        c.setName("Llanowar Elves");
        final IPaperCard elves = assertSameAsLookup(c, "the same card renamed");
        Assert.assertNotNull(elves);
        Assert.assertEquals(elves.getName(), "Llanowar Elves");

        c.setName("Soldier Token");
        Assert.assertNull(assertSameAsLookup(c, "renamed like no card"));

        c.setName("Sol Ring");
        c.setSetCode(editions[0]);
        Assert.assertSame(assertSameAsLookup(c, "back to the first name and set"), first);
    }

    @Test
    public void aChangedNameIsLookedUpByTheNameTheCardHasNow() {
        final Game game = initAndCreateGame();
        final Card c = noPaperCard(game, "Soldier Token", null);
        Assert.assertNull(assertSameAsLookup(c, "before the change"));
        c.addChangedName("Sol Ring", false, game.getNextTimestamp(), 0);
        Assert.assertEquals(c.getName(), "Sol Ring");
        Assert.assertNotNull(assertSameAsLookup(c, "named Sol Ring by an effect"));
    }

    @Test
    public void anArtPreferenceChangeIsSeenAtOnce() {
        final Game game = initAndCreateGame();
        final StaticData data = StaticData.instance();
        final CardArtPreference before = data.getCardArtPreference();
        final Card c = noPaperCard(game, "Sol Ring", null);
        try {
            data.setCardArtPreference(false, false); // original art, all editions
            final IPaperCard original = assertSameAsLookup(c, "original art");
            data.setCardArtPreference(true, false); // latest art, all editions
            final IPaperCard latest = assertSameAsLookup(c, "latest art");
            Assert.assertNotSame(latest, original, "the preference decides the print of a card with no set");
        } finally {
            data.setCardArtPreference(before.latestFirst, before.filterSets, before.blackBorderOnly);
        }
        Assert.assertEquals(data.getCardArtPreference(), before);
        assertSameAsLookup(c, "the preference restored");
    }

    /**
     * A preferred art names the art index a card with no set is looked up at. A Forest has several arts, so some
     * preferred art moves its print; the test sets each in turn until one does, and takes every one back.
     */
    @SuppressWarnings("unchecked")
    @Test
    public void aPreferredArtIsSeenAtOnce() throws Exception {
        final Game game = initAndCreateGame();
        final String name = "Forest";
        final CardDb common = StaticData.instance().getCommonCards();
        final Card c = noPaperCard(game, name, null);
        final IPaperCard before = assertSameAsLookup(c, "no preferred art");

        final Field artPrefsField = CardDb.class.getDeclaredField("artPrefs");
        artPrefsField.setAccessible(true);
        final Map<String, String> artPrefs = (Map<String, String>) artPrefsField.get(null);
        final Field uniqueField = CardDb.class.getDeclaredField("uniqueCardsByRules");
        uniqueField.setAccessible(true);
        final Map<CardRules, PaperCard> unique = (Map<CardRules, PaperCard>) uniqueField.get(common);
        final CardRules rules = before.getRules();
        final PaperCard uniqueBefore = unique.get(rules);
        Assert.assertFalse(artPrefs.containsKey(name), "no test left a preferred art for " + name);

        int moved = 0;
        int tried = 0;
        for (final PaperCard pc : common.getAllCards(name)) {
            if (pc.getArtIndex() < 2 || tried >= 40) {
                continue;
            }
            tried++;
            try {
                if (!common.setPreferredArt(name, pc.getEdition(), pc.getArtIndex())) {
                    continue;
                }
                final IPaperCard after = assertSameAsLookup(c, "a preferred art " + pc.getEdition() + " " + pc.getArtIndex());
                if (after != before) {
                    moved++;
                }
            } finally {
                artPrefs.remove(name);
                unique.put(rules, uniqueBefore);
                CardDb.noteChange(); // the test's own writes, counted as a writer counts them
            }
            Assert.assertSame(assertSameAsLookup(c, "the preferred art taken back"), before);
            if (moved > 0) {
                break;
            }
        }
        Assert.assertTrue(moved > 0, "some preferred art of " + tried + " moved the print of a Forest with no set");
    }

    /** Every writer of a card database counts its change; a load that adds nothing counts none. */
    @Test
    public void everyCardDatabaseWriterCountsItsChange() {
        final StaticData data = StaticData.instance();
        final CardRules solRing = data.getCommonCards().getRules("Sol Ring", false);
        final CardRules elves = data.getCommonCards().getRules("Llanowar Elves", false);
        final CardRules counterspell = data.getCommonCards().getRules("Counterspell", false);
        final Map<String, CardRules> rules = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        rules.put(solRing.getName(), solRing);

        long count = CardDb.getChangeCount();
        final CardDb db = new CardDb(rules, data.getEditions(), new HashSet<>());
        count = assertCounted(count, "the constructor");
        db.setCardArtPreference(true, false, false);
        count = assertCounted(count, "a new art preference");
        db.setCardArtPreference(true, false, false);
        Assert.assertEquals(CardDb.getChangeCount(), count, "the same art preference again changes nothing");
        db.setPreferredLanguageAvailability(null);
        count = assertCounted(count, "a preferred language");
        db.initialize(false, false, false);
        count = assertCounted(count, "initialize");
        Assert.assertNotNull(db.getCard("Sol Ring"));

        db.addCard(new PaperCard(solRing, "LEA", CardRarity.Uncommon, 7, false, "", "", null));
        count = assertCounted(count, "addCard");
        db.loadCard("Llanowar Elves", "LEA", elves);
        count = assertCounted(count, "loading a card");
        Assert.assertNotNull(db.getCard("Llanowar Elves", "LEA"));
        db.loadCard("Llanowar Elves", "LEA", elves);
        Assert.assertEquals(CardDb.getChangeCount(), count, "loading a loaded card again changes nothing");

        db.getEditor().putCard(counterspell);
        count = assertCounted(count, "the editor");
        Assert.assertNotNull(db.getCard("Counterspell"));

        db.clearLoadedCards();
        count = assertCounted(count, "clearing the loaded cards");
        Assert.assertNull(db.getCard("Sol Ring"));

        // a print of an edition with no image folder, so looking again adds nothing to the image caches
        final PaperCard print = new PaperCard(solRing, "ZZPROBE", CardRarity.Special, 1, false, "", "", null);
        print.hasImage(false);
        Assert.assertEquals(CardDb.getChangeCount(), count, "asking for a print's image changes nothing");
        final boolean hadImage = print.hasImage(true);
        assertCounted(count, "a print's image looked for again");
        Assert.assertEquals(print.hasImage(false), hadImage);
    }

    private static long assertCounted(final long before, final String what) {
        final long now = CardDb.getChangeCount();
        Assert.assertTrue(now > before, what + " counts a change (" + before + " -> " + now + ")");
        return now;
    }

    /** Games on many threads at once share the kept answers: each thread gets the lookup's answer. */
    @Test(timeOut = 120_000)
    public void manyThreadsGetTheLookupsAnswers() throws Exception {
        final Game game = initAndCreateGame();
        final List<Card> cards = new ArrayList<>();
        final Set<String> names = new LinkedHashSet<>(List.of("Sol Ring", "Llanowar Elves", "Soldier Token",
                "Sol Ring's Effect", "Counterspell", "Treasure"));
        for (final String name : names) {
            cards.add(noPaperCard(game, name, null));
            cards.add(noPaperCard(game, name, "C21"));
            cards.add(noPaperCard(game, name, "LEA"));
        }
        final Map<Card, IPaperCard> expected = new HashMap<>();
        for (final Card c : cards) {
            expected.put(c, uncached(c));
        }
        final List<Thread> threads = new ArrayList<>();
        final List<String> wrong = java.util.Collections.synchronizedList(new ArrayList<>());
        for (int t = 0; t < 8; t++) {
            final int offset = t;
            final Thread thread = new Thread(() -> {
                for (int i = 0; i < 2000; i++) {
                    final Card c = cards.get((i + offset) % cards.size());
                    if (c.getPaperCard() != expected.get(c)) {
                        wrong.add(c.getName() + "|" + c.getSetCode());
                    }
                }
            });
            threads.add(thread);
            thread.start();
        }
        for (final Thread thread : threads) {
            thread.join();
        }
        Assert.assertTrue(wrong.isEmpty(), "answers other than the lookup's: " + wrong);
    }
}
