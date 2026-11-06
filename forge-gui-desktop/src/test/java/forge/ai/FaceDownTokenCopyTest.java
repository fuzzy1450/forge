package forge.ai;

import static org.testng.AssertJUnit.assertEquals;
import static org.testng.AssertJUnit.assertFalse;
import static org.testng.AssertJUnit.assertNotNull;
import static org.testng.AssertJUnit.assertSame;
import static org.testng.AssertJUnit.assertTrue;

import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import forge.ai.simulation.GameCopier;
import forge.card.CardStateName;
import forge.game.Game;
import forge.game.GameObject;
import forge.game.ability.AbilityFactory;
import forge.game.ability.AbilityKey;
import forge.game.ability.AbilityUtils;
import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;

/**
 * A token copy of a face-down permanent takes the face-down state alone (CardFactory.getCloneStates), and
 * CopyPermanentEffect.getProtoType built it on the original's paper card, whose rules still said transforming (modal cards
 * read so too) or split. Those two reads go by the rules, not by the states, so the game asked the token for faces it does
 * not have: CardCopyService.getLKICopy for a back face (NullPointerException, "source" is null) and CardView.updateState
 * for a half (NullPointerException, the return value of Card.getState is null), and the game ended as an Error. The first
 * was seen with Three Steps Ahead on a Treasure Map under Cryptic Coat (w10-s0005-a1-o06-c, the Nathan Drake list's cloaked
 * card) and in the store under Clone Legion, Irenicus's Vile Duplication and Twinflame; the second under Cackling
 * Counterpart. Such a token is a nameless 2/2 of one face, like any other copy of a face-down permanent, and now reads as
 * that: it has no paper card, so no rules to read, and Card.getPaperCard has no name to look up.
 */
public class FaceDownTokenCopyTest extends AITest {
    private static final String TREASURE_MAP = "Treasure Map";

    private Game game;
    private Player ai;

    private void board() {
        game = initAndCreateGame();
        ai = game.getPlayers().get(1);
        fillLibrary(ai, 10);
        fillLibrary(game.getPlayers().get(0), 10);
    }

    /** Cryptic Coat's trigger on the top of the library, by the game's own cloak. */
    private Card cloak(final String name) {
        final Card top = addCardToZone(name, ai, ZoneType.Library);
        final SpellAbility coat = AbilityFactory.getAbility(createCard("Cryptic Coat", ai), "TrigCloak");
        coat.setActivatingPlayer(ai);
        final Card cloaked = top.cloak(ai, coat, AbilityKey.newMap());
        assertTrue(cloaked.isFaceDown());
        assertTrue(cloaked.isInPlay());
        return cloaked;
    }

    private Card newToken(final CardCollection before) {
        Card token = null;
        for (final Card c : ai.getCardsIn(ZoneType.Battlefield)) {
            if (!before.contains(c)) {
                assertTrue("one new permanent: " + c, token == null);
                token = c;
            }
        }
        assertNotNull("a token entered", token);
        return token;
    }

    /** A copy ability resolved as the stack resolves it. */
    private Card resolve(final SpellAbility copy, final GameObject target) {
        final CardCollection before = new CardCollection(ai.getCardsIn(ZoneType.Battlefield));
        copy.setActivatingPlayer(ai);
        copy.getTargets().add(target);
        AbilityUtils.resolve(copy);
        game.getAction().checkStateEffects(true);
        return newToken(before);
    }

    /** Three Steps Ahead's second mode, the one that crashed. */
    private Card threeStepsAhead(final Card target) {
        return resolve(AbilityFactory.getAbility(addCardToZone("Three Steps Ahead", ai, ZoneType.Hand), "DBCopy"), target);
    }

    private Card spell(final String name, final GameObject target) {
        return resolve(addCardToZone(name, ai, ZoneType.Hand).getFirstSpellAbility(), target);
    }

    private static void assertAFaceDownsCopy(final Card token) {
        assertTrue(token.isToken());
        assertTrue(token.isCreature());
        assertFalse(token.isFaceDown());
        assertEquals("", token.getName());
        assertEquals(2, token.getNetPower());
        assertEquals(2, token.getNetToughness());
        assertFalse(token.isTransformable());
        assertFalse(token.isSplitCard());
        assertFalse(token.hasState(CardStateName.Backside));
    }

    /** One of each kind of card a face-down permanent can be, by AlternateMode: transforming, split, room, adventure, flip,
     *  modal, meld, one-faced, and the omen, prepare, specialize and vengeance kinds. */
    @DataProvider(name = "faces")
    public static Object[][] faces() {
        return new Object[][] { {TREASURE_MAP}, {"Delver of Secrets"}, {"Fire // Ice"}, {"Bottomless Pool // Locker Room"},
                {"Bonecrusher Giant"}, {"Akki Lavarunner"}, {"Valki, God of Lies"}, {"Bruna, the Fading Light"},
                {"Grizzly Bears"}, {"Bloomvine Regent"}, {"Abigale, Poet Laureate"}, {"Alora, Rogue Companion"}, {"Hela, Mistress of Darkness"} };
    }

    @Test(dataProvider = "faces")
    public void aTokenCopyOfAFaceDownPermanentIsANamelessTwoTwo(final String name) {
        board();
        assertAFaceDownsCopy(threeStepsAhead(cloak(name)));
    }

    @Test(dataProvider = "faces")
    public void theTokenCanBeCopiedInTurn(final String name) {
        board();
        assertAFaceDownsCopy(threeStepsAhead(threeStepsAhead(cloak(name))));
    }

    @Test
    public void twinflameCopiesTheFaceDownCard() {
        board();
        assertAFaceDownsCopy(spell("Twinflame", cloak(TREASURE_MAP)));
    }

    /** The store's other message: a split card under the token, CardView asking for its halves. */
    @Test
    public void cacklingCounterpartCopiesAFaceDownSplitCard() {
        board();
        assertAFaceDownsCopy(spell("Cackling Counterpart", cloak("Fire // Ice")));
    }

    /** Clone Legion copies each creature of the player it targets, the cloaked card among them. */
    @Test
    public void cloneLegionCopiesTheFaceDownCardAmongTheCreatures() {
        board();
        cloak(TREASURE_MAP);
        assertAFaceDownsCopy(spell("Clone Legion", ai));
    }

    /** Irenicus's Vile Duplication adds flying to the same two-two, its one change. */
    @Test
    public void theCopysOwnChangesApply() {
        board();
        final Card token = spell("Irenicus's Vile Duplication", cloak(TREASURE_MAP));
        assertAFaceDownsCopy(token);
        assertTrue(token.hasKeyword("Flying"));
    }

    /** Stock, untouched: a copy of a face-down card of one face keeps its paper card. */
    @Test
    public void aPlainFaceDownCardsTokenStaysOnItsPaperCard() {
        board();
        final Card bears = cloak("Grizzly Bears");
        assertSame(bears.getPaperCard().getRules(), threeStepsAhead(bears).getRules());
    }

    /** Stock, untouched: CR 707.8a, a copy of a transforming permanent face up has both faces. */
    @Test
    public void aFaceUpTransformingPermanentsTokenHasBothFaces() {
        board();
        final Card map = addCard(TREASURE_MAP, ai);
        final Card token = threeStepsAhead(map);
        assertTrue(token.isTransformable());
        assertTrue(token.hasState(CardStateName.Backside));
        assertEquals(TREASURE_MAP, token.getName());
    }

    /** The token lives on: attacks by the AI, a hybrid seat's copy of the game, and leaving play. */
    @Test(timeOut = 120_000)
    public void theGameGoesOnWithTheToken() {
        board();
        final Card token = threeStepsAhead(cloak(TREASURE_MAP));
        token.setSickness(false);
        assertNotNull(new GameCopier(game).makeCopy());
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, ai);
        playUntilPhase(game, PhaseType.END_OF_TURN);
        assertFalse(game.isGameOver());
        game.getAction().moveToGraveyard(token, null);
        game.getAction().checkStateEffects(true);
        assertFalse("a token leaves no card behind", ai.getCardsIn(ZoneType.Graveyard).contains(token));
    }
}
