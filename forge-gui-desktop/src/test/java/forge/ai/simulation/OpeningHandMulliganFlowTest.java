package forge.ai.simulation;

import static org.testng.AssertJUnit.assertEquals;
import static org.testng.AssertJUnit.assertFalse;
import static org.testng.AssertJUnit.assertSame;
import static org.testng.AssertJUnit.assertTrue;

import java.lang.reflect.Field;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import java.util.Map;

import org.testng.annotations.Test;

import forge.MulliganDefs;
import forge.StaticData;
import forge.ai.AITest;
import forge.ai.AiProfileUtil;
import forge.ai.AiProps;
import forge.ai.LobbyPlayerAi;
import forge.ai.PlayerControllerAi;
import forge.game.Game;
import forge.game.GameStage;
import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.mulligan.HoustonMulligan;
import forge.game.mulligan.MulliganService;
import forge.game.player.Player;
import forge.game.zone.Zone;
import forge.game.zone.ZoneType;

/**
 * The keep question as MulliganService asks it, not a direct call of OpeningHand.keep. London tucks as it draws and the
 * service then asks with the same count, so the cards it names have already left the hand. PlayerControllerAi passes on
 * what is still to go back: a hand is judged on what it holds, at its own size. Upstream's keep is right as a function;
 * it was told a wrong number.
 * <p>
 * Each question records what the service said and the size it left the hand at, then the test deals the hand of that
 * question and restocks the library, so the odds do not depend on a shuffle: 40 cards, 17 lands, none green, on the
 * play. OpeningHand's formula gives, for a plan reading MIDRANGE (three lands by turn four, on the play three draws):
 * DEAR_SIX 40.9, BEARS 49.1, ONE_LAND_FIVE 41.1, DEAR_FIVE 13.2. The bars are 55 at seven, 45 at six and 30 at five.
 */
public class OpeningHandMulliganFlowTest extends AITest {
    private static final String ISLAND = "Island";
    private static final String MOUNTAIN = "Mountain";
    private static final String GIANT = "Hill Giant";
    /** Cheap enough for nothing: mana value 6, past the plan's four, so no early play and no key card. */
    private static final String DREADMAW = "Colossal Dreadmaw";
    /** A key card, and no early play: nothing in the hand or the library makes green. */
    private static final String BEARS = "Grizzly Bears";

    private static final String STOCK_PROFILE = "FlowTestStockMulligan";
    private static final String HIGH_BAR_PROFILE = "FlowTestThresholdNine";

    private static String[] cards(final Object... namesAndCounts) {
        final List<String> out = new ArrayList<>();
        for (int i = 0; i < namesAndCounts.length; i += 2) {
            for (int n = 0; n < (Integer) namesAndCounts[i + 1]; n++) {
                out.add((String) namesAndCounts[i]);
            }
        }
        return out.toArray(new String[0]);
    }

    /** No land at all: every rule sends this back at any size it is judged at. */
    private static String[] landless(final int size) {
        return cards(GIANT, size);
    }

    /** Three lands and three plays, every part of the score full: well over six's bar. */
    private static final String[] GOOD_SIX = cards(ISLAND, 2, MOUNTAIN, 1, GIANT, 3);
    /** Two lands and nothing to cast: 40.9, under six's bar of 45 and over five's of 30. */
    private static final String[] DEAR_SIX = cards(ISLAND, 1, MOUNTAIN, 1, DREADMAW, 4);
    /** A land and four spells: 41.1, over five's bar of 30. */
    private static final String[] ONE_LAND_FIVE = cards(ISLAND, 1, GIANT, 4);
    /** A land and nothing to cast: 13.2, under five's bar of 30. */
    private static final String[] DEAR_FIVE = cards(ISLAND, 1, DREADMAW, 4);
    /** Two lands and one key card: 49.1, under seven's bar of 55 and over six's of 45. */
    private static final String[] BEARS_SEVEN = cards(ISLAND, 1, MOUNTAIN, 1, BEARS, 1, DREADMAW, 4);
    private static final String[] LAND_FOUR = cards(ISLAND, 1, GIANT, 3);

    private final class Seat extends PlayerControllerAi {
        private final Deque<String[]> deals;
        /** The hand size and the count at each question, as MulliganService left and passed them. */
        final List<Integer> sizes = new ArrayList<>();
        final List<Integer> counts = new ArrayList<>();
        final List<Boolean> answers = new ArrayList<>();

        Seat(final Game game, final Player p, final String[]... deals) {
            super(game, p, p.getLobbyPlayer());
            this.deals = new ArrayDeque<>(Arrays.asList(deals));
        }

        @Override
        public boolean mulliganKeepHand(final Player firstPlayer, final int cardsToReturn) {
            final Player me = getPlayer();
            sizes.add(me.getCardsIn(ZoneType.Hand).size());
            counts.add(cardsToReturn);
            final String[] deal = deals.poll();
            assertTrue("a deal for each question: " + sizes, deal != null);
            assertEquals("the question's hand size is the flow's, not the test's", deal.length,
                    me.getCardsIn(ZoneType.Hand).size());
            deal(me, deal);
            final boolean keep = super.mulliganKeepHand(firstPlayer, cardsToReturn);
            answers.add(keep);
            return keep;
        }
    }

    private void stockLibrary(final Player p) {
        final Zone library = p.getZone(ZoneType.Library);
        for (final Card c : new CardCollection(library.getCards())) {
            library.remove(c);
        }
        for (final String name : cards(ISLAND, 9, MOUNTAIN, 8, GIANT, 23)) {
            addCardToZone(name, p, ZoneType.Library);
        }
    }

    private void deal(final Player p, final String... names) {
        final Zone hand = p.getZone(ZoneType.Hand);
        for (final Card c : new CardCollection(hand.getCards())) {
            hand.remove(c);
        }
        for (final String name : names) {
            addCardToZone(name, p, ZoneType.Hand);
        }
        stockLibrary(p);
    }

    private static void profile(final Player p, final String name) {
        ((LobbyPlayerAi) p.getLobbyPlayer()).setAiProfile(name);
    }

    /** Every seat of the sim harness plays the Default profile, whose threshold is 4 (AiProps' own default is 5). */
    private Player seatOf(final Game game) {
        final Player ai = game.getPlayers().get(1);
        profile(ai, "Default");
        return ai;
    }

    /** The real flow: a seven-card hand, MulliganService under London, the seat on the play. */
    private Seat run(final Game game, final Player ai, final String[]... deals) {
        assertSame("the sim harness plays London", MulliganDefs.MulliganRule.London,
                StaticData.instance().getMulliganRule());
        final Seat seat = new Seat(game, ai, deals);
        ai.dangerouslySetController(seat);
        deal(ai, landless(7));
        game.setAge(GameStage.Mulligan);
        new MulliganService(ai).perform();
        return seat;
    }

    private static void assertFlow(final Seat seat, final List<Integer> sizes, final List<Integer> counts,
            final List<Boolean> answers) {
        assertEquals("hand sizes at the questions", sizes, seat.sizes);
        assertEquals("the service's counts, which the fix leaves as they are", counts, seat.counts);
        assertEquals("the answers", answers, seat.answers);
    }

    @Test
    public void afterOneMulliganASixIsJudgedAtSixesBar() {
        final Game game = initAndCreateGame();
        final Player ai = seatOf(game);
        // judged as five with a card bottomed, as the count used to make it, 40.9 clears 30 and this was kept
        final Seat seat = run(game, ai, landless(7), DEAR_SIX, ONE_LAND_FIVE);
        assertFlow(seat, List.of(7, 6, 5), List.of(0, 1, 2), List.of(false, false, true));
        assertEquals(5, ai.getCardsIn(ZoneType.Hand).size());
        assertEquals(2, ai.getStats().getMulliganCount());
    }

    @Test
    public void aSixOverItsBarIsKept() {
        final Game game = initAndCreateGame();
        final Player ai = seatOf(game);
        final Seat seat = run(game, ai, landless(7), GOOD_SIX);
        assertFlow(seat, List.of(7, 6), List.of(0, 1), List.of(false, true));
        assertEquals(6, ai.getCardsIn(ZoneType.Hand).size());
    }

    /** OpeningHand.playable's rules for a hand of six apply to a hand of six: judged as five, a one-lander passed. */
    @Test
    public void aSixHeldToSixesPlayableRule() {
        final Game game = initAndCreateGame();
        final Player ai = seatOf(game);
        final Seat seat = run(game, ai, landless(7), cards(ISLAND, 1, GIANT, 5), ONE_LAND_FIVE);
        assertFlow(seat, List.of(7, 6, 5), List.of(0, 1, 2), List.of(false, false, true));
    }

    @Test
    public void afterTwoAFiveIsJudgedAtThirtyAndCanGoBack() {
        final Game game = initAndCreateGame();
        final Player ai = seatOf(game);
        // the count used to make this hand three cards, under the threshold, so it was always kept
        final Seat seat = run(game, ai, landless(7), landless(6), DEAR_FIVE, LAND_FOUR);
        assertFlow(seat, List.of(7, 6, 5, 4), List.of(0, 1, 2, 3), List.of(false, false, false, true));
        assertEquals(4, ai.getCardsIn(ZoneType.Hand).size());
        assertEquals(3, ai.getStats().getMulliganCount());
    }

    @Test
    public void aFiveOverItsBarIsKept() {
        final Game game = initAndCreateGame();
        final Player ai = seatOf(game);
        final Seat seat = run(game, ai, landless(7), landless(6), ONE_LAND_FIVE);
        assertFlow(seat, List.of(7, 6, 5), List.of(0, 1, 2), List.of(false, false, true));
    }

    /** AiProps' threshold of 5, a seat with no profile: four is under it, so any four is kept. */
    @Test
    public void aSeatWithNoProfileKeepsAnyFour() {
        final Game game = initAndCreateGame();
        final Player ai = game.getPlayers().get(1);
        final Seat seat = run(game, ai, landless(7), landless(6), landless(5), landless(4));
        assertFlow(seat, List.of(7, 6, 5, 4), List.of(0, 1, 2, 3), List.of(false, false, false, true));
    }

    /**
     * The Default profile's threshold of 4, every seat of the sim harness: four is not under it, so a four with a land is
     * kept (keepBar has no bar for it) and a landless four fails OpeningHand.playable and goes back. Three is under it.
     */
    @Test
    public void aDefaultSeatSendsALandlessFourBackAndKeepsThree() {
        final Game game = initAndCreateGame();
        final Player ai = seatOf(game);
        final Seat seat = run(game, ai, landless(7), landless(6), landless(5), landless(4), landless(3));
        assertFlow(seat, List.of(7, 6, 5, 4, 3), List.of(0, 1, 2, 3, 4), List.of(false, false, false, false, true));
        assertEquals(3, ai.getCardsIn(ZoneType.Hand).size());
        assertEquals(4, ai.getStats().getMulliganCount());
    }

    /** The pods' free first mulligan tucks none, so its seven is judged at seven; the next tuck is the first. */
    @Test
    public void aPodsFreeMulliganIsJudgedAtSeven() {
        final Game game = initAndCreateThreePlayerGame();
        final Player ai = seatOf(game);
        final Seat seat = run(game, ai, landless(7), BEARS_SEVEN, DEAR_SIX, ONE_LAND_FIVE);
        // 49.1 clears six's bar and not seven's; 40.9 clears five's bar and not six's
        assertFlow(seat, List.of(7, 7, 6, 5), List.of(0, 0, 1, 2), List.of(false, false, false, true));
        assertEquals(3, ai.getStats().getMulliganCount());
    }

    private static Map<String, Map<AiProps, String>> loadedProfiles() throws ReflectiveOperationException {
        final Field f = AiProfileUtil.class.getDeclaredField("loadedProfiles");
        f.setAccessible(true);
        @SuppressWarnings("unchecked")
        final Map<String, Map<AiProps, String>> profiles = (Map<String, Map<AiProps, String>>) f.get(null);
        return profiles;
    }

    /**
     * The stock rule behind the smart one takes the same number. No shipped profile turns the smart mulligan off, so the
     * test loads one that does. The stock rule reads the final size only against the threshold: at the old count a
     * landless five was three cards, under it, and kept.
     */
    @Test
    public void theStockFallbackJudgesTheHandAsItIs() throws ReflectiveOperationException {
        final Game game = initAndCreateGame();
        final Player ai = game.getPlayers().get(1);
        loadedProfiles().put(STOCK_PROFILE, Map.of(AiProps.FLAT_MC_MULLIGAN, "false", AiProps.MULLIGAN_THRESHOLD, "4"));
        try {
            profile(ai, STOCK_PROFILE);
            final Seat seat = run(game, ai, landless(7), landless(6), landless(5), LAND_FOUR);
            assertFalse("this test is about the stock rule", seat.getAi().useSmartMulligan());
            assertFlow(seat, List.of(7, 6, 5, 4), List.of(0, 1, 2, 3), List.of(false, false, false, true));
        } finally {
            loadedProfiles().remove(STOCK_PROFILE);
        }
    }

    /**
     * Houston draws three past the hand and tucks them after the keep, so its count is still to go back. The rule never
     * lets MulliganService ask (canMulligan is false), so the controller is called as the service would call it. A
     * threshold of nine tells the two readings apart: seven cards kept is under it, a landless ten is judged and refused.
     */
    @Test
    public void aHoustonHandStillSubtracts() throws ReflectiveOperationException {
        final Game game = initAndCreateGame();
        final Player ai = game.getPlayers().get(1);
        loadedProfiles().put(HIGH_BAR_PROFILE, Map.of(AiProps.MULLIGAN_THRESHOLD, "9"));
        try {
            profile(ai, HIGH_BAR_PROFILE);
            final Seat seat = new Seat(game, ai, new String[0][]);
            ai.dangerouslySetController(seat);
            deal(ai, landless(7));
            game.setAge(GameStage.Mulligan);
            final HoustonMulligan rule = new HoustonMulligan(ai, false);
            rule.beforeFirstMulligan();
            deal(ai, landless(10));
            assertFalse("the service cannot ask under Houston", rule.canMulligan());
            assertEquals(3, rule.tuckCardsDuringMulligan());
            final PlayerControllerAi stock = new PlayerControllerAi(game, ai, ai.getLobbyPlayer());
            assertTrue("seven cards will be left, under the threshold", stock.mulliganKeepHand(ai,
                    rule.tuckCardsDuringMulligan()));
            assertFalse("the control: nothing to return, and a landless ten is judged", stock.mulliganKeepHand(ai, 0));
            rule.keep();
            assertEquals(7, ai.getCardsIn(ZoneType.Hand).size());
        } finally {
            loadedProfiles().remove(HIGH_BAR_PROFILE);
        }
    }
}
