package forge.ai;

import java.util.List;

import org.testng.AssertJUnit;
import org.testng.annotations.Test;

import forge.ai.ability.ChangeZoneAi;
import forge.ai.simulation.ComboPlan;
import forge.game.Game;
import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.trigger.Trigger;
import forge.game.zone.ZoneType;

/**
 * Three dead-card picks of ChangeZoneAi.chooseCardToHiddenOriginChangeZone answer searches that upstream's deck plan
 * pick (ComboPlan.choose, which AiController.chooseCardToHiddenOriginChangeZone asks first) answers too: Isochron
 * Scepter's imprint and Praetor's Grasp's exile in a tutor storm deck, and our own Scapeshift's land search in a Dark
 * Depths deck. Since the merge of upstream 626aa9f0735 the fork's pick decides them first, as it did before the merge
 * (the merge's rule R5: the fork's hook decides first at a meeting point; ChangeZoneAi.deadCardPickDecides). These pin
 * that on upstream's own tutor storm and Dark Depths lists (FlatMonteCarloStormTest's and
 * FlatMonteCarloGraveyardLandsTest's), each on a board where the deck plan's pick and the fork's differ, and the key
 * for the three searches and no other.
 */
public class DeadCardPicksBeforeDeckPlanTest extends AITest {

    /** FlatMonteCarloStormTest's main deck of "[Legacy] Beseech Storm", which the deck plan reads as a tutor storm. */
    private static final Object[] BESEECH_STORM = {
            "Ad Nauseam", 1, "Beseech the Mirror", 2, "Burning Wish", 2, "Chrome Mox", 4, "City of Traitors", 1,
            "Dark Ritual", 4, "Duress", 2, "Gaea's Will", 1, "Giant's Boulder", 4, "Infernal Tutor", 4,
            "Lion's Eye Diamond", 4, "Lotus Petal", 4, "Mox Opal", 4, "Skateboard", 1, "Song of Creation", 1,
            "Spire of Industry", 3, "Tendrils of Agony", 1, "Thoughtseize", 4, "Urza's Saga", 4, "Vault of Whispers", 4,
            "Veil of Summer", 2, "Wishclaw Talisman", 3 };

    /** FlatMonteCarloGraveyardLandsTest's Dark Depths deck. */
    private static final Object[] DEPTHS_DECK = { "Vampire Hexmage", 4, "Dark Depths", 4, "Thespian's Stage", 4,
            "Lightning Bolt", 4, "Thoughtseize", 4, "Inquisition of Kozilek", 4, "Lotus Petal", 4, "Swamp", 14,
            "Mountain", 4, "Dark Ritual", 4 };

    /** A game as FlatMonteCarloStormTest starts one: the heuristics and the deck plan only, no Flat MC. */
    private Game newGame() {
        final Game game = initAndCreateGame();
        game.AI_FLAT_MONTE_CARLO = false;
        game.getPlayers().get(1).setTeam(0);
        game.getPlayers().get(0).setTeam(1);
        return game;
    }

    /** The list in player 1's library, as upstream's deck plan tests deal it. */
    private Player deal(final Game game, final Object... list) {
        final Player p = game.getPlayers().get(1);
        for (int i = 0; i < list.length; i += 2) {
            for (int n = 0; n < (Integer) list[i + 1]; n++) {
                addCardToZone((String) list[i], p, ZoneType.Library);
            }
        }
        return p;
    }

    private static AiController aiOf(final Player p) {
        return ((PlayerControllerAi) p.getController()).getAi();
    }

    /** Isochron Scepter's enters-trigger exile, the imprint. */
    private static SpellAbility imprintOf(final Card scepter) {
        for (final Trigger t : scepter.getTriggers()) {
            final SpellAbility sa = t.ensureAbility();
            if (SpecialCardAi.IsochronScepter.isImprint(sa)) {
                return sa;
            }
        }
        throw new AssertionError("no imprint trigger on " + scepter);
    }

    /** Scapeshift's land search, the Sacrifice root's sub, both activated by {@code p}. */
    private static SpellAbility shiftOf(final Card scapeshift, final Player p) {
        final SpellAbility root = scapeshift.getFirstSpellAbility();
        root.setActivatingPlayer(p);
        final SpellAbility shift = root.getSubAbility();
        shift.setActivatingPlayer(p);
        return shift;
    }

    /** A tutor storm deck's Isochron Scepter, Lightning Bolt and Opt in hand: the deck plan's put-back rule (Chrome
     *  Mox's: the card the kill needs least, the first of two equal ones) takes Lightning Bolt, the Scepter's ranked
     *  pick Opt (a draw before a burn), and the imprint takes the Scepter's. */
    @Test
    public void aTutorStormDecksScepterImprintIsTheScepterPick() {
        final Game game = newGame();
        final Player p = deal(game, BESEECH_STORM);
        final AiController aic = aiOf(p);
        final ComboPlan combos = aic.deckPlan(p).combos;
        AssertJUnit.assertTrue(combos.summary(), combos.has(ComboPlan.Kind.TUTOR_STORM));
        final Card scepter = addCard("Isochron Scepter", p);
        final Card bolt = addCardToZone("Lightning Bolt", p, ZoneType.Hand);
        final Card opt = addCardToZone("Opt", p, ZoneType.Hand);
        final SpellAbility imprint = imprintOf(scepter);
        imprint.setActivatingPlayer(p);
        final List<ZoneType> fromHand = List.of(ZoneType.Hand);

        final Card planPick = combos.choose(p, ZoneType.Exile, fromHand,
                new CardCollection(List.of(bolt, opt)));
        final Card scepterPick = SpecialCardAi.IsochronScepter.imprintPick(p, scepter, List.of(bolt, opt));
        AssertJUnit.assertSame(bolt, planPick);
        AssertJUnit.assertSame(opt, scepterPick);
        AssertJUnit.assertSame(opt, aic.chooseCardToHiddenOriginChangeZone(ZoneType.Exile, fromHand, imprint,
                new CardCollection(List.of(bolt, opt)), p, p));
    }

    /** A Dark Depths deck's own Scapeshift, its library's lands and a Field of the Dead to search among: the deck
     *  plan's land rule (Crop Rotation's: the land the deck is built around) takes Dark Depths, the Scapeshift pick
     *  Field of the Dead, and the search takes the Scapeshift pick. */
    @Test
    public void aDarkDepthsDecksScapeshiftSearchIsTheScapeshiftPick() {
        final Game game = newGame();
        final Player p = deal(game, DEPTHS_DECK);
        final AiController aic = aiOf(p);
        final ComboPlan combos = aic.deckPlan(p).combos;
        AssertJUnit.assertTrue(combos.summary(), combos.has(ComboPlan.Kind.DARK_DEPTHS));
        final Card field = addCardToZone("Field of the Dead", p, ZoneType.Library);
        final SpellAbility shift = shiftOf(addCardToZone("Scapeshift", p, ZoneType.Hand), p);
        final CardCollection lands = new CardCollection();
        for (final Card c : p.getCardsIn(ZoneType.Library)) {
            if (c.isLand()) {
                lands.add(c);
            }
        }
        final List<ZoneType> fromLibrary = List.of(ZoneType.Library);

        final Card planPick = combos.choose(p, ZoneType.Battlefield, fromLibrary,
                new CardCollection(lands));
        AssertJUnit.assertNotNull(planPick);
        AssertJUnit.assertEquals("Dark Depths", planPick.getName());
        AssertJUnit.assertSame(field, SpecialCardAi.Scapeshift.choosePick(p, lands));
        AssertJUnit.assertSame(field, aic.chooseCardToHiddenOriginChangeZone(ZoneType.Battlefield, fromLibrary, shift,
                new CardCollection(lands), p, p));
    }

    /** The key: the three searches, Praetor's Grasp's among them, and not a plain tutor, an imprint's or the Grasp's
     *  ability sent to another zone, or a Scapeshift search another player decides. */
    @Test
    public void theKeyTakesTheThreeSearchesAndNoOther() {
        final Game game = newGame();
        final Player p = game.getPlayers().get(1);
        final Player opp = game.getPlayers().get(0);
        final SpellAbility grasp = addCardToZone("Praetor's Grasp", p, ZoneType.Hand).getFirstSpellAbility();
        grasp.setActivatingPlayer(p);
        final SpellAbility imprint = imprintOf(addCard("Isochron Scepter", p));
        imprint.setActivatingPlayer(p);
        final SpellAbility shift = shiftOf(addCardToZone("Scapeshift", p, ZoneType.Hand), p);
        final SpellAbility tutor = addCardToZone("Demonic Tutor", p, ZoneType.Hand).getFirstSpellAbility();
        tutor.setActivatingPlayer(p);

        AssertJUnit.assertTrue(ChangeZoneAi.deadCardPickDecides(ZoneType.Exile, grasp, opp, p));
        AssertJUnit.assertTrue(ChangeZoneAi.deadCardPickDecides(ZoneType.Exile, imprint, p, p));
        AssertJUnit.assertTrue(ChangeZoneAi.deadCardPickDecides(ZoneType.Battlefield, shift, p, p));

        AssertJUnit.assertFalse(ChangeZoneAi.deadCardPickDecides(ZoneType.Hand, tutor, p, p));
        AssertJUnit.assertFalse(ChangeZoneAi.deadCardPickDecides(ZoneType.Hand, grasp, opp, p));
        AssertJUnit.assertFalse(ChangeZoneAi.deadCardPickDecides(ZoneType.Hand, imprint, p, p));
        AssertJUnit.assertFalse(ChangeZoneAi.deadCardPickDecides(ZoneType.Battlefield, shift, p, opp));
        AssertJUnit.assertFalse(ChangeZoneAi.deadCardPickDecides(ZoneType.Battlefield, shift, opp, p));
    }
}
