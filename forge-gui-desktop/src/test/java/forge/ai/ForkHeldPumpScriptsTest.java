package forge.ai;

import org.testng.Assert;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import forge.ai.ability.PumpAi;
import forge.game.Game;
import forge.game.ability.ApiType;
import forge.game.card.Card;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.trigger.Trigger;
import forge.game.trigger.TriggerType;

/**
 * The card scripts whose Pump line a fork hook reads. Upstream's #12858 moved abilities that only choose a target from
 * Pump to its new TargetOnly (TargetOnlyEffect, TargetOnlyAi), and TargetOnlyAi reads none of the fork's hooks: the
 * AILogic values the dead-card batches added, PumpAi's hooks keyed on a card's name, ControlGainAi's Domineering Will
 * mark. The merge of upstream e5e2f900616 (2d339921360) kept the fork's line in the twelve scripts where the move
 * conflicted; five more (Legions to Ashes, Breaking of the Fellowship, Maelstrom Pulse, Domineering Will, Carpet of
 * Flowers) merged without a conflict as TargetOnly, so their hooks never ran, until their lines were put back as
 * 05477b77cfb had them. One row per line a hook reads, found by where it sits on the card (its spell, its activated
 * ability or the ability of its trigger of a mode, then so many sub-abilities down) and, where the hook keys on an
 * AILogic, by that AILogic: its API is Pump and SpellApiToAi gives it to PumpAi. The next upstream conversion of any
 * of these lines fails here, by name, instead of turning a hook off without a word.
 */
public class ForkHeldPumpScriptsTest extends AITest {

    private enum Where { SPELL, ACTIVATED, TRIGGER }

    @DataProvider(name = "lines")
    public Object[][] lines() {
        // card, where, trigger mode (TRIGGER only), sub-abilities down, the AILogic the hook keys on (null: the hook
        // keys on the card's name), the hook
        return new Object[][] {
                // the twelve 2d339921360 kept as the fork's
                {"Brass Squire", Where.ACTIVATED, null, 0, "BrassSquire", "PumpAi.checkApiLogic (BrassSquire.LOGIC)"},
                {"Chromeshell Crab", Where.TRIGGER, TriggerType.TurnFaceUp, 0, "ChromeshellCrab",
                        "PumpAi.doTriggerNoCost (ChromeshellCrab.LOGIC)"},
                {"Curious Herd", Where.SPELL, null, 0, "CuriousHerd", "PumpAi.checkApiLogic (CuriousHerd)"},
                {"Decoy Gambit", Where.SPELL, null, 0, "DecoyGambit", "PumpAi.checkApiLogic (DecoyGambit)"},
                {"Fiery Gambit", Where.SPELL, null, 0, null,
                        "PumpAi.checkApiLogic and doTriggerNoCost (FieryGambit.NAME)"},
                {"Filigree Vector", Where.TRIGGER, TriggerType.ChangesZone, 0, "Always",
                        "SpellAbilityAi.doTriggerNoCostWithSubs (AILogic Always)"},
                {"Filigree Vector", Where.TRIGGER, TriggerType.ChangesZone, 1, null,
                        "PumpAi.chkDrawback (Filigree Vector, ImprintCards)"},
                {"Friendly Rivalry", Where.SPELL, null, 0, "FriendlyRivalry",
                        "PumpAi.checkApiLogic (FriendlyRivalry.LOGIC)"},
                {"Friendly Rivalry", Where.SPELL, null, 1, null, "PumpAi.chkDrawback (FriendlyRivalry.takeJudged)"},
                {"Recurring Insight", Where.SPELL, null, 0, "RecurringInsight",
                        "PumpAi.checkApiLogic and doTriggerNoCost (RecurringInsight)"},
                {"Shapesharer", Where.ACTIVATED, null, 0, "Never", "SpellAbilityAi.checkAiLogic (Never)"},
                {"Song of Inspiration", Where.SPELL, null, 0, "SongOfInspiration",
                        "PumpAi.checkApiLogic (SongOfInspiration)"},
                {"Suffer the Past", Where.SPELL, null, 0, "SufferThePast", "PumpAi.checkApiLogic (SufferThePast)"},
                {"Vedalken Plotter", Where.TRIGGER, TriggerType.ChangesZone, 0, "VedalkenPlotter",
                        "PumpAi.doTriggerNoCost (VedalkenPlotter.LOGIC)"},
                // the five the merge moved to TargetOnly without a conflict
                {"Legions to Ashes", Where.SPELL, null, 0, null, "PumpAi.checkApiLogic (Legions to Ashes)"},
                {"Breaking of the Fellowship", Where.SPELL, null, 0, null,
                        "PumpAi.checkApiLogic (BreakingOfTheFellowship.NAME)"},
                {"Maelstrom Pulse", Where.SPELL, null, 0, null, "PumpAi.checkApiLogic (MaelstromPulse.NAME)"},
                {"Domineering Will", Where.SPELL, null, 0, null,
                        "PumpAi.checkApiLogic (DomineeringWill.handles), which sets ControlGainAi's mark"},
                {"Carpet of Flowers", Where.TRIGGER, TriggerType.Phase, 0, null,
                        "PumpAi.doTriggerNoCost (CarpetOfFlowers.NAME)"},
        };
    }

    @Test(dataProvider = "lines")
    public void theLineAForkHookReadsIsPump(final String name, final Where where, final TriggerType mode,
            final int down, final String aiLogic, final String hook) {
        final Game game = initAndCreateGame();
        final Player p = game.getPlayers().get(1);
        final Card card = createCard(name, p);
        final String line = name + " (" + where + (mode == null ? "" : " " + mode) + (down == 0 ? "" : ", sub " + down)
                + ", read by " + hook + ")";
        SpellAbility sa = find(card, where, mode, line);
        for (int i = 0; i < down; i++) {
            sa = sa.getSubAbility();
            Assert.assertNotNull(sa, line + ": no sub-ability that far down");
        }
        if (aiLogic != null) {
            Assert.assertEquals(sa.getParam("AILogic"), aiLogic, line + ": not the line carrying the hook's AILogic");
        }
        Assert.assertEquals(sa.getApi(), ApiType.Pump, line + ": the hook reads a Pump line");
        Assert.assertTrue(SpellApiToAi.Converter.get(sa) instanceof PumpAi,
                line + ": SpellApiToAi gives the line to " + SpellApiToAi.Converter.get(sa).getClass().getSimpleName()
                        + ", not to PumpAi");
    }

    private static SpellAbility find(final Card card, final Where where, final TriggerType mode, final String line) {
        SpellAbility found = null;
        switch (where) {
            case SPELL:
                found = card.getFirstSpellAbility();
                Assert.assertNotNull(found, line + ": no spell");
                Assert.assertTrue(found.isSpell(), line + ": the first ability is no spell");
                return found;
            case ACTIVATED:
                for (final SpellAbility sa : card.getSpellAbilities()) {
                    if (sa.isActivatedAbility() && !sa.isManaAbility()) {
                        Assert.assertNull(found, line + ": more than one activated ability");
                        found = sa;
                    }
                }
                Assert.assertNotNull(found, line + ": no activated ability");
                return found;
            case TRIGGER:
                for (final Trigger t : card.getTriggers()) {
                    if (t.getMode() == mode) {
                        Assert.assertNull(found, line + ": more than one " + mode + " trigger");
                        found = t.ensureAbility();
                    }
                }
                Assert.assertNotNull(found, line + ": no " + mode + " trigger with an ability");
                return found;
            default:
                throw new IllegalArgumentException(where.toString());
        }
    }
}
