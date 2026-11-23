package forge.game.ability.effects;

import static org.testng.AssertJUnit.assertEquals;
import static org.testng.AssertJUnit.assertFalse;
import static org.testng.AssertJUnit.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.testng.annotations.Test;

import forge.ai.AITest;
import forge.ai.PlayerControllerAi;
import forge.card.CardStateName;
import forge.card.ICardFace;
import forge.game.Game;
import forge.game.ability.AbilityFactory;
import forge.game.ability.AbilityUtils;
import forge.game.ability.ApiType;
import forge.game.card.Card;
import forge.game.card.CardCollectionView;
import forge.game.card.CounterEnumType;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;

/**
 * "Sacrifice an artifact, a creature, and a land": a Sacrifice with SacEachValid asks for one choice for each type of its
 * SacValid ("Artifact & Creature & Land"), each choice with a prompt of its own. With no SacMessage the prompts were split
 * off the description of the whole SacValid at " & ". Since "Update Stack Descriptions" (5a7f95b72a3, #12834) that
 * description reads "artifact, creature and land" and has no " & " left, so only the first type had a prompt, and the
 * game ended with an ArrayIndexOutOfBoundsException ("Index 1 out of bounds for length 1") as soon as a later type had
 * something to sacrifice. Five scripts use SacEachValid, none with a SacMessage: the Oubliette room of Tomb of
 * Annihilation, Release (of Catch // Release), Perilous Predicament, Structural Collapse and To the Slaughter.
 * Each choice is now asked for by the description of its own type.
 */
public class SacrificeEachValidTest extends AITest {
    /** The sacrifice of the Oubliette room (tokenscripts/tomb_of_annihilation.txt, DBSacArtifact), word for word. */
    private static final String ONE_OF_EACH = "DB$ Sacrifice | Defined$ You | SacValid$ Artifact & Creature & Land | SacEachValid$ True";

    private Game game;
    private Player p;
    private Player opp;
    private Asked mine;
    private Asked theirs;

    /** A player's own AI, which notes the prompt of every sacrifice it is asked for and, in a dungeon, takes the Tomb of
     *  Annihilation and its Oubliette (the stock AI picks a room at random among those it accepts). */
    private static final class Asked extends PlayerControllerAi {
        private final List<String> prompts = new ArrayList<>();

        private Asked(final Player player) {
            super(player.getGame(), player, player.getLobbyPlayer());
        }

        @Override
        public CardCollectionView choosePermanentsToSacrifice(final SpellAbility sa, final int min, final int max,
                final CardCollectionView validTargets, final String message) {
            prompts.add(message);
            return super.choosePermanentsToSacrifice(sa, min, max, validTargets, message);
        }

        @Override
        public ICardFace chooseSingleCardFace(final SpellAbility sa, final List<ICardFace> faces, final String message) {
            for (final ICardFace face : faces) {
                if ("Tomb of Annihilation".equals(face.getName())) {
                    return face;
                }
            }
            throw new AssertionError("Tomb of Annihilation is not among the dungeons: " + faces);
        }

        @Override
        public SpellAbility chooseSingleSpellForEffect(final List<SpellAbility> spells, final SpellAbility sa, final String title,
                final Map<String, Object> params) {
            for (final SpellAbility room : spells) {
                if ("Oubliette".equals(room.getParam("RoomName"))) {
                    return room;
                }
            }
            throw new AssertionError("the Oubliette is not among the next rooms: " + spells);
        }
    }

    private void board() {
        game = initAndCreateGame();
        p = game.getPlayers().get(1);
        opp = game.getPlayers().get(0);
        mine = new Asked(p);
        theirs = new Asked(opp);
        p.dangerouslySetController(mine);
        opp.dangerouslySetController(theirs);
    }

    /** {@code sa} resolved for p as the stack resolves it, its cost aside. */
    private void resolve(final SpellAbility sa) {
        game.getAction().checkStateEffects(true);
        sa.setActivatingPlayer(p);
        AbilityUtils.resolve(sa);
        game.getAction().checkStateEffects(true);
    }

    /** A Sacrifice script resolved for p from a card that is in no zone, so that it is not itself a choice. */
    private void sacrifice(final String script) {
        resolve(AbilityFactory.getAbility(script, createCard("Structural Collapse", p)));
    }

    /** The spell of p's card {@code name} whose chain holds the one-of-each sacrifice, on whichever face it is. */
    private SpellAbility spellWithTheSacrifice(final String name) {
        final Card card = addCardToZone(name, p, ZoneType.Hand);
        for (final CardStateName state : card.getStates()) {
            for (final SpellAbility root : card.getState(state).getSpellAbilities()) {
                for (SpellAbility sa = root; sa != null; sa = sa.getSubAbility()) {
                    if (sa.hasParam("SacEachValid")) {
                        return root;
                    }
                }
            }
        }
        throw new AssertionError(name + " has no SacEachValid sacrifice");
    }

    /** The opponent as the target of the first ability of the chain that has one. */
    private void targetTheOpponent(final SpellAbility root) {
        for (SpellAbility sa = root; sa != null; sa = sa.getSubAbility()) {
            if (sa.usesTargeting()) {
                sa.getTargets().add(opp);
                return;
            }
        }
        throw new AssertionError("no ability of the chain has a target: " + root);
    }

    /** A planeswalker with loyalty: put onto the battlefield as the others are, it would have none and die at once. */
    private Card planeswalker(final String name, final Player controller) {
        final Card walker = addCard(name, controller);
        walker.setCounters(CounterEnumType.LOYALTY, 3);
        return walker;
    }

    /** Sacrificed, by the game's own record of the turn: a card that reached the graveyard another way does not count. */
    private static void assertSacrificed(final Card... cards) {
        for (final Card c : cards) {
            assertTrue(c + " was sacrificed", c.getController().getSacrificedThisTurn().contains(c));
            assertTrue(c + " is in its owner's graveyard", c.getOwner().getZone(ZoneType.Graveyard).contains(c));
        }
    }

    private static void assertKept(final Card... cards) {
        for (final Card c : cards) {
            assertTrue(c + " stays on the battlefield", c.getOwner().getZone(ZoneType.Battlefield).contains(c));
        }
    }

    // --- the room's sacrifice: Artifact & Creature & Land ---

    @Test
    public void anArtifactACreatureAndALandAreSacrificed() {
        board();
        final Card ring = addCard("Sol Ring", p);
        final Card bears = addCard("Grizzly Bears", p);
        final Card forest = addCard("Forest", p);
        final Card anthem = addCard("Glorious Anthem", p);
        final Card theirRing = addCard("Sol Ring", opp);
        final Card theirBears = addCard("Grizzly Bears", opp);
        final Card theirForest = addCard("Forest", opp);

        sacrifice(ONE_OF_EACH);

        assertSacrificed(ring, bears, forest);
        assertKept(anthem, theirRing, theirBears, theirForest);
    }

    @Test
    public void withNoArtifactTheCreatureAndTheLandAreSacrificed() {
        board();
        final Card bears = addCard("Grizzly Bears", p);
        final Card forest = addCard("Forest", p);

        sacrifice(ONE_OF_EACH);

        assertSacrificed(bears, forest);
    }

    @Test
    public void aLandAloneIsSacrificed() {
        board();
        final Card forest = addCard("Forest", p);

        sacrifice(ONE_OF_EACH);

        assertSacrificed(forest);
    }

    @Test
    public void eachChoiceIsAskedForByItsOwnType() {
        board();
        addCard("Sol Ring", p);
        addCard("Grizzly Bears", p);
        addCard("Forest", p);

        sacrifice(ONE_OF_EACH);

        assertEquals(List.of("artifact", "creature", "land"), mine.prompts);
    }

    @Test
    public void aTypeWithNothingToSacrificeIsNotAskedForAndTheOthersKeepTheirOwnPrompts() {
        board();
        addCard("Grizzly Bears", p);
        addCard("Forest", p);

        sacrifice(ONE_OF_EACH);

        assertEquals(List.of("creature", "land"), mine.prompts);
    }

    @Test
    public void aTypeOfSeveralKindsIsDescribedAsTheWholeSacValidIs() {
        board();
        final Card anthem = addCard("Glorious Anthem", p);
        final Card forest = addCard("Forest", p);

        sacrifice("DB$ Sacrifice | Defined$ You | SacValid$ Artifact,Enchantment & Land | SacEachValid$ True");

        assertSacrificed(anthem, forest);
        assertEquals(List.of("artifact or enchantment", "land"), mine.prompts);
    }

    // --- a SacMessage beside SacEachValid: its parts are the prompts ---

    /** A guard: this passed before the per-type descriptions too. */
    @Test
    public void aSacMessageWithAPartForEachTypeGivesThePrompts() {
        board();
        final Card ring = addCard("Sol Ring", p);
        final Card bears = addCard("Grizzly Bears", p);
        final Card forest = addCard("Forest", p);

        sacrifice(ONE_OF_EACH + " | SacMessage$ relic & beast & plot");

        assertSacrificed(ring, bears, forest);
        assertEquals(List.of("relic", "beast", "plot"), mine.prompts);
    }

    @Test
    public void aSacMessageWithTooFewPartsLeavesTheRestToTheTypesOwnDescription() {
        board();
        final Card ring = addCard("Sol Ring", p);
        final Card bears = addCard("Grizzly Bears", p);
        final Card forest = addCard("Forest", p);

        sacrifice(ONE_OF_EACH + " | SacMessage$ relic & beast");

        assertSacrificed(ring, bears, forest);
        assertEquals(List.of("relic", "beast", "land"), mine.prompts);
    }

    // --- the room itself ---

    private Card dungeon() {
        for (final Card c : p.getCardsIn(ZoneType.Command)) {
            if (c.getType().isDungeon()) {
                return c;
            }
        }
        throw new AssertionError("p has no dungeon in the command zone");
    }

    /** p ventures into the dungeon with {@code source}'s own ability, and the room's ability resolves off the stack. */
    private void venture(final Card source) {
        SpellAbility venture = null;
        for (final SpellAbility sa : source.getSpellAbilities()) {
            if (sa.getApi() == ApiType.Venture) {
                venture = sa;
            }
        }
        assertTrue(source + " ventures into the dungeon", venture != null);
        resolve(venture);
        game.getStack().addAllTriggeredAbilitiesToStack();
        assertFalse("the room's ability is on the stack", game.getStack().isEmpty());
        game.getStack().resolveStack();
        assertTrue("the room's ability has resolved", game.getStack().isEmpty());
    }

    @Test
    public void theOublietteRoomDiscardsACardAndSacrificesAnArtifactACreatureAndALand() {
        board();
        final Card map = addCard("Dungeon Map", p);
        final Card bears = addCard("Grizzly Bears", p);
        final Card forest = addCard("Forest", p);
        final Card inHand = addCardToZone("Divination", p, ZoneType.Hand);

        venture(map);
        assertEquals("Trapped Entry", dungeon().getCurrentRoom());
        venture(map);
        assertEquals("Oubliette", dungeon().getCurrentRoom());

        assertTrue("the room's discard", p.getZone(ZoneType.Graveyard).contains(inHand));
        assertSacrificed(map, bears, forest);
        assertEquals(List.of("artifact", "creature", "land"), mine.prompts);
    }

    // --- the four cards ---

    @Test
    public void structuralCollapseTakesAnArtifactAndALandAndDealsItsDamage() {
        board();
        final Card ring = addCard("Sol Ring", opp);
        final Card forest = addCard("Forest", opp);
        final Card bears = addCard("Grizzly Bears", opp);
        final SpellAbility collapse = spellWithTheSacrifice("Structural Collapse");
        targetTheOpponent(collapse);

        resolve(collapse);

        assertSacrificed(ring, forest);
        assertKept(bears);
        assertEquals("2 damage to the player after the sacrifice", 18, opp.getLife());
    }

    @Test
    public void perilousPredicamentTakesAnArtifactCreatureAndANonartifactCreature() {
        board();
        final Card thopter = addCard("Ornithopter", opp);
        final Card bears = addCard("Grizzly Bears", opp);
        final Card myBears = addCard("Grizzly Bears", p);

        resolve(spellWithTheSacrifice("Perilous Predicament"));

        assertSacrificed(thopter, bears);
        assertKept(myBears);
        assertEquals(List.of("artifact creature", "nonartifact creature"), theirs.prompts);
    }

    @Test
    public void toTheSlaughterWithDeliriumTakesACreatureAndAPlaneswalker() {
        board();
        final Card bears = addCard("Grizzly Bears", opp);
        final Card jace = planeswalker("Jace Beleren", opp);
        // four card types in our graveyard: delirium
        addCardToZone("Forest", p, ZoneType.Graveyard);
        addCardToZone("Grizzly Bears", p, ZoneType.Graveyard);
        addCardToZone("Lightning Bolt", p, ZoneType.Graveyard);
        addCardToZone("Divination", p, ZoneType.Graveyard);
        final SpellAbility slaughter = spellWithTheSacrifice("To the Slaughter");
        targetTheOpponent(slaughter);

        resolve(slaughter);

        assertSacrificed(bears, jace);
    }

    @Test
    public void releaseTakesOneOfEachOfItsFiveTypesFromEachPlayer() {
        board();
        final List<Card> all = new ArrayList<>();
        for (final Player each : List.of(p, opp)) {
            for (final String name : List.of("Sol Ring", "Grizzly Bears", "Glorious Anthem", "Forest")) {
                all.add(addCard(name, each));
            }
            all.add(planeswalker("Jace Beleren", each));
        }

        resolve(spellWithTheSacrifice("Catch // Release"));

        assertSacrificed(all.toArray(new Card[0]));
        final List<String> five = List.of("artifact", "creature", "enchantment", "land", "planeswalker");
        assertEquals(five, mine.prompts);
        assertEquals(five, theirs.prompts);
    }
}
