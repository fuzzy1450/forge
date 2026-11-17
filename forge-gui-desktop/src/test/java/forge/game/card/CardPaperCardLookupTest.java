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
import forge.card.CardEdition;
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
        final CardRules bolt = data.getCommonCards().getRules("Lightning Bolt", false);
        final CardRules fireIce = data.getCommonCards().getRules("Fire // Ice", false);
        Assert.assertNotNull(fireIce);
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

        // a split card's print is filed under each half's name too, and counted after them
        db.addCard(new PaperCard(fireIce, "LEA", CardRarity.Uncommon, 1, false, "", "", null));
        count = assertCounted(count, "addCard of a split card");
        Assert.assertNotNull(db.getCard("Fire // Ice"));
        db.addUnassignedCardPrints(false);
        count = assertCounted(count, "addUnassignedCardPrints");

        // new rules loaded from an edition that does not print them: no print is added, only the rules and their faces
        String noCounterspell = null;
        for (final CardEdition e : data.getEditions()) {
            if (e.getCardInSet("Counterspell").isEmpty()) {
                noCounterspell = e.getCode();
                break;
            }
        }
        Assert.assertNotNull(noCounterspell);
        db.loadCard("Counterspell", noCounterspell, counterspell);
        count = assertCounted(count, "loading new rules with no print");
        Assert.assertTrue(db.getAllCards("Counterspell").isEmpty(), "no print of Counterspell was added");
        Assert.assertSame(db.getRules("Counterspell", false), counterspell, "but its rules were");

        db.getEditor().setImmediateReindex(false); // this small database's full reindex is not what is tested
        db.getEditor().putCard(bolt);
        count = assertCounted(count, "the editor adding new rules");
        Assert.assertNotNull(db.getCard("Lightning Bolt"));
        db.getEditor().putCard(counterspell); // rules it has by that name: their properties are taken again
        count = assertCounted(count, "the editor replacing rules' properties");
        Assert.assertSame(db.getRules("Counterspell", false), counterspell);

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

    // ---- prints added to the shared database for one test, and taken out again

    /** An edition that prints no Sol Ring, so the prints a test adds there are the only candidates by name and set. */
    private static String editionWithoutSolRing() {
        for (final String code : List.of("DOM", "WAR", "ELD", "KHM")) {
            final CardEdition e = StaticData.instance().getEditions().get(code);
            if (e != null && e.getCardInSet("Sol Ring").isEmpty()) {
                return e.getCode();
            }
        }
        throw new AssertionError("no edition without Sol Ring among the candidates");
    }

    private static void addPrints(final PaperCard... prints) {
        for (final PaperCard pc : prints) {
            StaticData.instance().getCommonCards().addCard(pc);
        }
    }

    @SuppressWarnings("unchecked")
    private static void removePrints(final PaperCard... prints) throws Exception {
        final CardDb common = StaticData.instance().getCommonCards();
        for (final String field : List.of("allCardsByName", "allCardsByRules")) {
            final Field f = CardDb.class.getDeclaredField(field);
            f.setAccessible(true);
            final com.google.common.collect.ListMultimap<Object, PaperCard> map =
                    (com.google.common.collect.ListMultimap<Object, PaperCard>) f.get(common);
            for (final PaperCard pc : prints) {
                final Object key = field.equals("allCardsByName") ? pc.getName() : pc.getRules();
                map.get(key).removeIf(x -> x == pc);
            }
        }
        CardDb.noteChange(); // the test's own write, counted as a writer counts it
    }

    private static void setHasImage(final PaperCard pc, final Boolean value) throws Exception {
        final Field f = PaperCard.class.getDeclaredField("hasImage");
        f.setAccessible(true);
        f.set(pc, value);
    }

    /** A print whose image check runs a write the first time it is asked, and answers what it held before the write. */
    static final class WritingPrint extends PaperCard {
        boolean image;
        Runnable onFirstAsk;

        WritingPrint(final CardRules rules, final String edition, final String collectorNumber, final boolean image) {
            super(rules, edition, CardRarity.Special, 1, false, collectorNumber, "", null);
            this.image = image;
        }

        @Override
        public boolean hasImage() {
            final Runnable write = onFirstAsk;
            if (write != null) {
                onFirstAsk = null;
                final boolean before = image;
                write.run();
                return before;
            }
            return image;
        }
    }

    /**
     * The count is read before the lookup: a write that lands while a lookup runs leaves that lookup's answer under the
     * count it started at, so the next call looks up again. Here the write is a print's image being found (as
     * ImageFetcher's hasImage(true) does), made from inside the lookup itself by the first print's image check.
     */
    @Test
    public void anAnswerFoundAcrossAWriteIsNotKeptAsCurrent() throws Exception {
        final Game game = initAndCreateGame();
        final CardRules solRing = StaticData.instance().getCommonCards().getRules("Sol Ring", false);
        final String edition = editionWithoutSolRing();
        final WritingPrint first = new WritingPrint(solRing, edition, "9001", false);
        final WritingPrint second = new WritingPrint(solRing, edition, "9002", true);
        first.onFirstAsk = () -> {
            first.image = true;
            CardDb.noteChange();
        };
        addPrints(first, second);
        try {
            final Card c = noPaperCard(game, "Sol Ring", edition);
            Assert.assertSame(c.getPaperCard(), second, "the lookup the write landed in found the print with an image then");
            Assert.assertNull(first.onFirstAsk, "the write ran inside the lookup");
            Assert.assertSame(c.getPaperCard(), first, "the next call looks up again and finds the first print's image");
            Assert.assertSame(uncached(c), first);
            Assert.assertSame(c.getPaperCard(), first, "and keeps that answer");
        } finally {
            removePrints(first, second);
        }
    }

    /** Another StaticData (as tests that build their own database make) is looked up in, never served the old answers. */
    @Test
    public void anotherStaticDataIsLookedUpInItself() throws Exception {
        final Game game = initAndCreateGame();
        final StaticData original = StaticData.instance();
        final Field last = StaticData.class.getDeclaredField("lastInstance");
        last.setAccessible(true);
        final StaticData other;
        try {
            other = forge.gamesimulationtests.util.CardDatabaseHelper.createStaticData("CardPaperCardLookupTest", true);
        } finally {
            last.set(null, original);
        }
        Assert.assertNotSame(other, original);
        final Card c = noPaperCard(game, "Sol Ring", "C21");
        final IPaperCard ours = assertSameAsLookup(c, "Sol Ring from C21 in the shared database");
        final long count = CardDb.getChangeCount();
        try {
            last.set(null, other);
            final IPaperCard theirs = c.getPaperCard();
            Assert.assertNotNull(theirs);
            Assert.assertNotSame(theirs, ours, "the other database's own print, not the one kept from the shared one");
            Assert.assertEquals(theirs.getEdition(), "C21");
            Assert.assertSame(uncached(c), theirs);
        } finally {
            last.set(null, original);
        }
        Assert.assertSame(c.getPaperCard(), ours, "back on the shared database, its print again");
        Assert.assertTrue(count <= CardDb.getChangeCount());
    }

    /**
     * A lookup among the prints of one set takes the first print with an image. When ImageFetcher downloads one, it
     * asks hasImage(true), and the answer moves to that print at once.
     */
    @SuppressWarnings("unchecked")
    @Test
    public void anImageFoundAgainMovesTheAnswer() throws Exception {
        final Game game = initAndCreateGame();
        final CardRules solRing = StaticData.instance().getCommonCards().getRules("Sol Ring", false);
        final String edition = editionWithoutSolRing();
        final PaperCard first = new PaperCard(solRing, edition, CardRarity.Special, 1, false, "9003", "", null);
        final PaperCard second = new PaperCard(solRing, edition, CardRarity.Special, 1, false, "9004", "", null);
        setHasImage(first, false);
        setHasImage(second, true);

        final Field lookupField = forge.ImageKeys.class.getDeclaredField("editionImageLookup");
        lookupField.setAccessible(true);
        final Map<String, Boolean> editionImageLookup = (Map<String, Boolean>) lookupField.get(null);
        final Field contentField = forge.ImageKeys.class.getDeclaredField("cachedContent");
        contentField.setAccessible(true);
        final Map<String, HashSet<String>> cachedContent = (Map<String, HashSet<String>>) contentField.get(null);
        final String[] keyParts = StringUtils.split(first.getCardImageKey(), "//");
        final String folder = forge.ImageKeys.getSetFolder(edition);
        final boolean hadLookup = editionImageLookup.containsKey(edition);
        final Boolean lookupBefore = editionImageLookup.get(edition);
        final Map<String, HashSet<String>> contentBefore = new HashMap<>();
        for (final String k : List.of(keyParts[0], folder)) {
            contentBefore.put(k, cachedContent.get(k));
        }

        addPrints(first, second);
        try {
            final Card c = noPaperCard(game, "Sol Ring", edition);
            Assert.assertSame(assertSameAsLookup(c, "only the second print has an image"), second);

            // the image of the first print arrives in the cache folder
            editionImageLookup.put(edition, true);
            final HashSet<String> content = new HashSet<>();
            content.add(keyParts[1].split("\\.")[0]);
            cachedContent.put(keyParts[0], content);
            cachedContent.put(folder, content);
            Assert.assertSame(c.getPaperCard(), second, "until it is asked again, the first print still has none");
            Assert.assertTrue(first.hasImage(true), "the image is found again");
            Assert.assertSame(assertSameAsLookup(c, "the first print now has an image"), first);
        } finally {
            removePrints(first, second);
            if (hadLookup) {
                editionImageLookup.put(edition, lookupBefore);
            } else {
                editionImageLookup.remove(edition);
            }
            for (final Map.Entry<String, HashSet<String>> e : contentBefore.entrySet()) {
                if (e.getValue() == null) {
                    cachedContent.remove(e.getKey());
                } else {
                    cachedContent.put(e.getKey(), e.getValue());
                }
            }
        }
    }

    /**
     * A name that is a request string with flags is looked up every call, and each call gets a new flagged copy: the
     * variant database (planes, schemes...) answers such a request with copyWithFlags, a new object each time.
     */
    @Test
    public void aRequestStringWithFlagsGetsANewCopyEachCall() {
        final Game game = initAndCreateGame();
        final PaperCard plane = StaticData.instance().getVariantCards().getAllCards().iterator().next();
        final String request = plane.getName() + CardDb.NameSetSeparator + plane.getEdition() + CardDb.NameSetSeparator
                + plane.getArtIndex() + CardDb.NameSetSeparator + "#{noSellValue=true}";
        final Card c = noPaperCard(game, request, null);
        final IPaperCard a = c.getPaperCard();
        final IPaperCard b = c.getPaperCard();
        final IPaperCard fresh = uncached(c);
        Assert.assertNotNull(a, request);
        Assert.assertEquals(a.getName(), plane.getName());
        Assert.assertEquals(a.getEdition(), plane.getEdition());
        Assert.assertTrue(((PaperCard) a).hasNoSellValue(), "the flag is on the copy");
        Assert.assertNotSame(b, a, "a new copy each call, as the lookup makes one");
        Assert.assertNotSame(fresh, a);
        Assert.assertEquals(b, a);
        Assert.assertEquals(fresh, a);
    }

    /** Past 20,000 kept answers an answer is looked up and not kept; every answer is still the lookup's. */
    @Test(timeOut = 300_000)
    public void pastTheCapAnswersAreLookedUpAndNotKept() throws Exception {
        final Game game = initAndCreateGame();
        final Class<?> holderClass = Class.forName("forge.game.card.Card$PaperCardLookups");
        final Field current = holderClass.getDeclaredField("current");
        current.setAccessible(true);
        final Field answers = holderClass.getDeclaredField("answers");
        answers.setAccessible(true);
        final Card c = noPaperCard(game, "Probe Token 0", "C21");
        final long count = CardDb.getChangeCount();
        for (int i = 0; i < 20_050; i++) {
            c.setName("Probe Token " + i);
            Assert.assertNull(c.getPaperCard());
        }
        c.setName("Sol Ring");
        Assert.assertSame(c.getPaperCard(), uncached(c), "an answer past the cap is still the lookup's");
        Assert.assertEquals(CardDb.getChangeCount(), count, "nothing changed the database meanwhile");
        final Map<?, ?> kept = (Map<?, ?>) answers.get(current.get(null));
        Assert.assertEquals(kept.size(), 20_000, "no more answers are kept than the cap");
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
