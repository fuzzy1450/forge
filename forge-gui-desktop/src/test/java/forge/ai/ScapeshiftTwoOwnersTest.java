package forge.ai;

import java.util.List;

import org.testng.AssertJUnit;
import org.testng.annotations.Test;

import forge.ai.ability.ChangeZoneAi;
import forge.ai.simulation.RampPlan;
import forge.game.Game;
import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;

/**
 * Scapeshift has two owners (merge 2026-11-22, ruling RU-2): upstream's Valakut line (RampPlan, 852e7a4f741 and the
 * commits before it) decides a deck's own Scapeshift while RampPlan.valakutAccessible says a Valakut is in its hand, its
 * library or in play; the fork's Field of the Dead row (SpecialCardAi.Scapeshift, dead-card batch 4, row 31) decides it
 * otherwise. The two meet at the cast (the readmit), the search pick and the sacrifice chooser; each test pins one of
 * them in a Valakut deck, where the fork's row decided before the ruling, and in a deck without a Valakut, where it
 * still does.
 */
public class ScapeshiftTwoOwnersTest extends AITest {

    private static final String VALAKUT = "Valakut, the Molten Pinnacle";

    private Game newGame() {
        final Game game = initAndCreateGame();
        game.AI_FLAT_MONTE_CARLO = false;
        return game;
    }

    /** Scapeshift in {@code p}'s hand, its Sacrifice root activated by {@code p}. */
    private SpellAbility scapeshiftIn(final Player p) {
        final SpellAbility root = addCardToZone("Scapeshift", p, ZoneType.Hand).getFirstSpellAbility();
        root.setActivatingPlayer(p);
        root.getSubAbility().setActivatingPlayer(p);
        return root;
    }

    private static CardCollection landsIn(final Player p, final ZoneType zone) {
        final CardCollection lands = new CardCollection();
        for (final Card c : p.getCardsIn(zone)) {
            if (c.isLand()) {
                lands.add(c);
            }
        }
        return lands;
    }

    /** The cast: the fork's readmit (its main-2 window) takes a Field of the Dead deck's Scapeshift, not a Valakut deck's. */
    @Test
    public void theReadmitIsTheFieldDecksAlone() {
        final Game game = newGame();
        final Player p = game.getPlayers().get(1);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN2, p);
        final SpellAbility field = scapeshiftIn(p);
        AssertJUnit.assertTrue("no Valakut: the fork's row readmits its Scapeshift", SpecialCardAi.Scapeshift.readmit(p, field));

        addCardToZone(VALAKUT, p, ZoneType.Library);
        AssertJUnit.assertTrue(RampPlan.valakutAccessible(p));
        AssertJUnit.assertFalse("a Valakut in the library: upstream's line owns the cast",
                SpecialCardAi.Scapeshift.readmit(p, field));
    }

    /**
     * The search: with Valakut and Field of the Dead both in the library, the fork's pick takes Field of the Dead, and
     * upstream's RampPlan.nextPick takes Valakut, the land its line needs while none is in play. A Valakut deck's search
     * is upstream's pick.
     */
    @Test
    public void aValakutDecksScapeshiftSearchIsTheValakutPick() {
        final Game game = newGame();
        final Player p = game.getPlayers().get(1);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, p);
        final Card valakut = addCardToZone(VALAKUT, p, ZoneType.Library);
        final Card field = addCardToZone("Field of the Dead", p, ZoneType.Library);
        for (int i = 0; i < 7; i++) {
            addCardToZone("Mountain", p, ZoneType.Library);
        }
        final SpellAbility shift = scapeshiftIn(p).getSubAbility();
        final CardCollection lands = landsIn(p, ZoneType.Library);
        AssertJUnit.assertSame("the fork's pick, for the record", field, SpecialCardAi.Scapeshift.choosePick(p, lands));

        AssertJUnit.assertFalse("a Valakut deck's search is not the fork's dead-card pick",
                ChangeZoneAi.deadCardPickDecides(ZoneType.Battlefield, shift, p, p));
        final AiController aic = ((PlayerControllerAi) p.getController()).getAi();
        AssertJUnit.assertSame(valakut, aic.chooseCardToHiddenOriginChangeZone(ZoneType.Battlefield,
                List.of(ZoneType.Library), shift, new CardCollection(lands), p, p));
    }

    /** The search of a deck with no Valakut stays the fork's pick: Field of the Dead. */
    @Test
    public void aFieldDecksScapeshiftSearchIsStillTheFieldPick() {
        final Game game = newGame();
        final Player p = game.getPlayers().get(1);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, p);
        final Card field = addCardToZone("Field of the Dead", p, ZoneType.Library);
        for (int i = 0; i < 7; i++) {
            addCardToZone("Mountain", p, ZoneType.Library);
        }
        final SpellAbility shift = scapeshiftIn(p).getSubAbility();
        final CardCollection lands = landsIn(p, ZoneType.Library);
        AssertJUnit.assertTrue(ChangeZoneAi.deadCardPickDecides(ZoneType.Battlefield, shift, p, p));
        final AiController aic = ((PlayerControllerAi) p.getController()).getAi();
        AssertJUnit.assertSame(field, aic.chooseCardToHiddenOriginChangeZone(ZoneType.Battlefield,
                List.of(ZoneType.Library), shift, new CardCollection(lands), p, p));
    }

    /**
     * The sacrifice: Valakut, three Mountains and five Forests in play, all untapped, seven Mountains in the library.
     * The fork's chooser sacrifices only tapped lands (none here); upstream's keeps Valakut and the Mountains its line
     * needs and sacrifices up to RampPlan.scapeshiftLandsToSacrifice lands.
     */
    @Test
    public void aValakutDecksScapeshiftSacrificeIsTheValakutLine() {
        final Game game = newGame();
        final Player p = game.getPlayers().get(1);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, p);
        final Card valakut = addCard(VALAKUT, p);
        addCards("Mountain", 3, p);
        addCards("Forest", 5, p);
        for (int i = 0; i < 7; i++) {
            addCardToZone("Mountain", p, ZoneType.Library);
        }
        final SpellAbility root = scapeshiftIn(p);
        final CardCollection mine = landsIn(p, ZoneType.Battlefield);
        AssertJUnit.assertTrue("the fork's chooser, for the record: no tapped land, nothing sacrificed",
                SpecialCardAi.Scapeshift.chooseSacrifice(p, mine).isEmpty());

        final CardCollection sacrificed = ComputerUtil.choosePermanentsToSacrifice(p, mine, mine.size(), root, false, true);
        AssertJUnit.assertFalse("Valakut is kept: " + sacrificed, sacrificed.contains(valakut));
        AssertJUnit.assertFalse("upstream's line sacrifices lands for its Mountains: " + sacrificed, sacrificed.isEmpty());
        AssertJUnit.assertTrue("at most what the line wants: " + sacrificed,
                sacrificed.size() <= RampPlan.scapeshiftLandsToSacrifice(p));
    }

    /** The sacrifice of a deck with no Valakut stays the fork's row: only tapped lands, none here. */
    @Test
    public void aFieldDecksScapeshiftSacrificeIsStillTheForksRow() {
        final Game game = newGame();
        final Player p = game.getPlayers().get(1);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, p);
        addCards("Mountain", 3, p);
        addCards("Forest", 5, p);
        for (int i = 0; i < 7; i++) {
            addCardToZone("Mountain", p, ZoneType.Library);
        }
        final SpellAbility root = scapeshiftIn(p);
        final CardCollection mine = landsIn(p, ZoneType.Battlefield);
        AssertJUnit.assertTrue(ComputerUtil.choosePermanentsToSacrifice(p, mine, mine.size(), root, false, true).isEmpty());
    }
}
