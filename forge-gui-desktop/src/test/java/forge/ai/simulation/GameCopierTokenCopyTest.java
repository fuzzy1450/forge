package forge.ai.simulation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.testng.Assert;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import forge.game.Game;
import forge.game.ability.AbilityUtils;
import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.keyword.Keyword;
import forge.game.keyword.KeywordInterface;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;

/**
 * A token copy of a card whose keyword names one of the card's SVars could not be copied, and with it no game it was
 * in: GameCopier rebuilt every token through TokenInfo.makeOneToken, which builds each of the token's keywords on a
 * bare card before copyCopiableCharacteristics brings the SVars over, so the ability such a keyword names was not
 * there ("Error in Keyword ETBReplacement:Other:DBRemoveCounterAll for card Thief of Blood", caused by
 * "AbilityFactory : getAbility -- no API"). A default seat's Flat MC rollout read it as a failed line (Master Biomancer
 * in the Flat MC ship's determinism battery, Titan of Littjara on claw), and a hybrid_sim seat's safety check threw
 * (Thief of Blood, the battery's game 1v1-hybrid/Plunder the Graves+Seize Control/7000000, an Error in every arm).
 * copyCopiableCharacteristics copies the keywords with the SVars they name, so the token is now built without them.
 */
public class GameCopierTokenCopyTest extends SimulationTest {

    /** Each card, its keyword naming an SVar, and that SVar. */
    @DataProvider(name = "cards")
    public static Object[][] cards() {
        return new Object[][] {
                {"Thief of Blood", "DBRemoveCounterAll"},
                {"Titan of Littjara", "ChooseCT"},
                {"Master Biomancer", "AddExtraCounter"} };
    }

    /** A token copy of {@code original}, made by Cackling Counterpart's ability as the stack resolves it. */
    private Card tokenCopyOf(final Card original) {
        final Player p = original.getController();
        final Game game = p.getGame();
        final CardCollection before = new CardCollection(p.getCardsIn(ZoneType.Battlefield));
        final SpellAbility copy = addCardToZone("Cackling Counterpart", p, ZoneType.Hand).getFirstSpellAbility();
        copy.setActivatingPlayer(p);
        copy.getTargets().add(original);
        AbilityUtils.resolve(copy);
        game.getAction().checkStateEffects(true);
        Card token = null;
        for (final Card c : p.getCardsIn(ZoneType.Battlefield)) {
            if (!before.contains(c)) {
                Assert.assertNull(token, "one new permanent, but also " + c);
                token = c;
            }
        }
        Assert.assertNotNull(token, "a token entered");
        Assert.assertTrue(token.isToken());
        Assert.assertEquals(token.getName(), original.getName());
        return token;
    }

    @Test(dataProvider = "cards")
    public void aTokenCopyOfACardWhoseKeywordNamesAnSVarIsCopiedWithIt(final String name, final String svar) {
        final Game game = initAndCreateGame();
        final Player p = game.getPlayers().get(1);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, p);
        final Card token = tokenCopyOf(addCard(name, p));
        Assert.assertTrue(token.hasSVar(svar), "the token copy has the SVar its keyword names");

        final GameCopier copier = new GameCopier(game);
        final Game copy = copier.makeCopy();

        final Card copied = copier.find(token);
        Assert.assertNotSame(copied, token);
        Assert.assertSame(copied.getGame(), copy);
        Assert.assertTrue(copied.isToken(), "still a token");
        Assert.assertEquals(copied.getName(), name);
        Assert.assertTrue(copied.hasSVar(svar), "the copy has the SVar its keyword names");
        Assert.assertEquals(copied.getReplacementEffects().size(), token.getReplacementEffects().size(),
                "the copy has the token's replacement effects, its keyword's ETB replacement among them");
        Assert.assertEquals(copied.getTriggers().size(), token.getTriggers().size(), "and its triggers");
        Assert.assertEquals(copied.getStaticAbilities().size(), token.getStaticAbilities().size(),
                "and its static abilities");
    }

    /**
     * The battery's case: a hybrid_sim seat with a token copy of Thief of Blood in play weighs a play on a copy of the
     * game again. Here the play is a lethal land (2 life, against Zo-Zu the Punisher), which its simulation refuses.
     */
    @Test
    public void aHybridSeatWithATokenCopyOfThiefOfBloodInPlayStillWeighsItsPlays() {
        final Game game = initAndCreateGame(true);
        final Player ai = game.getPlayers().get(1);
        final Player opponent = game.getPlayers().get(0);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, ai);
        tokenCopyOf(addCard("Thief of Blood", ai));
        ai.setLife(2, null);
        final Card forest = addCardToZone("Forest", ai, ZoneType.Hand);
        addCard("Zo-Zu the Punisher", opponent);
        moveToMain2(game, ai);
        final SpellAbility land = forest.getAllPossibleAbilities(ai, true).stream()
                .filter(SpellAbility::isLandAbility).findFirst().orElseThrow();
        land.setActivatingPlayer(ai);

        Assert.assertFalse(OnePlaySafetyChecker.isAcceptable(ai, land),
                "the copy of the game shows the land's 2 damage killing the seat");
    }

    /**
     * Since the copier builds a token without its keywords, a token copy's own keywords come only from
     * copyCopiableCharacteristics (CardState.copyFrom): a 1/1 white Spirit token with flying keeps its flying in the
     * copy, and the static ability its flying is. A copy without it would let every rollout block the Spirit with a
     * ground creature, silently.
     */
    @Test
    public void aSpiritTokenWithFlyingIsCopiedWithItsFlying() {
        final Game game = initAndCreateGame();
        final Player p = game.getPlayers().get(1);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, p);
        final Card spirit = addToken("w_1_1_spirit_flying", p);
        game.getAction().checkStateEffects(true);
        Assert.assertEquals(namesOf(spirit.getCurrentState().getIntrinsicKeywords()), List.of("Flying"),
                "the Spirit's own keyword is flying");

        assertCopiedWithItsKeywords(game, spirit);
    }

    /**
     * A token whose keywords an anthem grants: a 1/1 white Soldier token under Eldrazi Monument has flying and
     * indestructible from it and no keyword of its own. TokenInfo reads every keyword a token shows, but the copy has
     * these from the copied Monument's static ability, applied again in the copy once the original's effects are
     * undone, and not as its own, so a rollout that removes the Monument takes them away.
     */
    @Test
    public void aTokenWhoseKeywordsAnAnthemGrantsIsCopiedWithThem() {
        final Game game = initAndCreateGame();
        final Player p = game.getPlayers().get(1);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, p);
        addCard("Eldrazi Monument", p);
        final Card soldier = addToken("w_1_1_soldier", p);
        game.getAction().checkStateEffects(true);
        Assert.assertTrue(soldier.getCurrentState().getIntrinsicKeywords().isEmpty(),
                "the Soldier has no keyword of its own");
        Assert.assertEquals(namesOf(soldier.getKeywords()), List.of("Flying", "Indestructible"),
                "the Monument gives it flying and indestructible");

        assertCopiedWithItsKeywords(game, soldier);
    }

    /**
     * Copies the game and checks the token's copy: the token's keywords, flying among them, the same of them its own,
     * and as many static abilities.
     */
    private void assertCopiedWithItsKeywords(final Game game, final Card token) {
        Assert.assertTrue(token.isToken());
        Assert.assertFalse(token.getStaticAbilities().isEmpty(), "the token's flying is a static ability");

        final GameCopier copier = new GameCopier(game);
        final Game copy = copier.makeCopy();

        final Card copied = copier.find(token);
        Assert.assertNotSame(copied, token);
        Assert.assertSame(copied.getGame(), copy);
        Assert.assertTrue(copied.isToken(), "still a token");
        Assert.assertEquals(namesOf(copied.getKeywords()), namesOf(token.getKeywords()),
                "the copy has the token's keywords");
        Assert.assertTrue(copied.hasKeyword(Keyword.FLYING), "flying among them");
        Assert.assertEquals(namesOf(copied.getCurrentState().getIntrinsicKeywords()),
                namesOf(token.getCurrentState().getIntrinsicKeywords()), "the same of them its own");
        Assert.assertEquals(copied.getStaticAbilities().size(), token.getStaticAbilities().size(),
                "and as many static abilities");
    }

    /** Each keyword's text, sorted: which keywords a card has, whatever order its keyword collection lists them in. */
    private static List<String> namesOf(final Iterable<KeywordInterface> keywords) {
        final List<String> names = new ArrayList<>();
        for (final KeywordInterface k : keywords) {
            names.add(k.getOriginal());
        }
        Collections.sort(names);
        return names;
    }
}
