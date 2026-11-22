package forge.ai;

import org.testng.Assert;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import forge.ai.ability.ChooseGenericAi;
import forge.ai.ability.PumpAi;
import forge.ai.ability.PumpAllAi;
import forge.game.Game;
import forge.game.ability.AbilityFactory;
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
 * <p>
 * The merge of upstream ca782d4e6ff (#12876, "Cleanup of Pump abilities", which moved Pump and PumpAll lines that pump
 * nothing to its new Bookkeeping, Nothing and EndOfTurn, all three given to NothingAi, and more target-only lines to
 * TargetOnly) kept the fork's line where the move conflicted (Flickerform, Infinite Reflection, Into the Time Vortex,
 * Throes of Chaos, Refuse // Cooperate, Filigree Vector's charge-counter sub) and put back five lines that merged
 * without a conflict (Aetherspouts, Finale of Promise, Harsh Mercy, Sinister Waltz, Sudden Substitution); the rows
 * below the first nineteen are theirs, with the four hooks REVIEW-2 R1 named. {@code theLineAForkHintSitsOn} pins the
 * lines whose API is not Pump but whose hint a hook reads: the fork's GenericChoice hints, beside whose lines
 * upstream moved the choices, and the two lines kept that a Pump row cannot find (a second ChangesZone trigger, a
 * PumpAll inside a RepeatEach).
 */
public class ForkHeldPumpScriptsTest extends AITest {

    // SPELL_WITH_LOGIC: the card's spell whose AILogic is the row's, among every state's abilities (a split card)
    private enum Where { SPELL, ACTIVATED, TRIGGER, SPELL_WITH_LOGIC }

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
                // the merge of ca782d4e6ff: kept as the fork's where #12876 conflicted
                {"Flickerform", Where.ACTIVATED, null, 0, "Flickerform",
                        "PumpAi.checkApiLogic (Flickerform.LOGIC, isOwnActivation)"},
                {"Into the Time Vortex", Where.SPELL, null, 0, "CascadeShell",
                        "PumpAi.checkApiLogic and doTriggerNoCost (CascadeShell)"},
                {"Throes of Chaos", Where.SPELL, null, 0, "CascadeShell",
                        "PumpAi.checkApiLogic and doTriggerNoCost (CascadeShell)"},
                {"Refuse // Cooperate", Where.SPELL_WITH_LOGIC, null, 0, "RefuseBurn",
                        "PumpAi.checkApiLogic (RefuseBurn)"},
                // the merge of ca782d4e6ff: put back where #12876 merged without a conflict (Finale of Promise,
                // Sinister Waltz, Sudden Substitution and Refuse // Cooperate are REVIEW-2 R1's four)
                {"Aetherspouts", Where.SPELL, null, 0, null, "PumpAi.checkApiLogic (Aetherspouts.NAME)"},
                {"Finale of Promise", Where.SPELL, null, 0, null,
                        "PumpAi.checkApiLogic (Finale of Promise)"},
                {"Finale of Promise", Where.SPELL, null, 1, null,
                        "PumpAi.chkDrawback (Finale of Promise) and FinaleOfPromise.consider's sorcery-sub shape"},
                {"Sinister Waltz", Where.SPELL, null, 0, null, "PumpAi.checkApiLogic (Sinister Waltz)"},
                {"Sudden Substitution", Where.SPELL, null, 0, null,
                        "PumpAi.checkApiLogic (Sudden Substitution)"},
                {"Sudden Substitution", Where.SPELL, null, 1, null, "PumpAi.chkDrawback (Sudden Substitution)"},
                {"Sudden Substitution", Where.SPELL, null, 2, null,
                        "PumpAi.chkDrawback (Sudden Substitution, the untargeted remember sub)"},
        };
    }

    @DataProvider(name = "hints")
    public Object[][] hints() {
        // card, the SVar of the line (null: the card's first spell), its API, the parameter the hook reads and its
        // value, the AI class SpellApiToAi gives the line to, the hook
        return new Object[][] {
                {"Archangel of Strife", "ChooseEach", ApiType.GenericChoice, "AILogic", "ArchangelOfStrife",
                        ChooseGenericAi.class, "ChooseGenericAi.checkAiLogic (ArchangelOfStrife)"},
                {"Borderland Explorer", "TrigChoose", ApiType.GenericChoice, "AILogic", "BorderlandExplorer",
                        ChooseGenericAi.class,
                        "ChooseGenericAi.checkAiLogic and chooseSingleSpellAbility (BorderlandExplorer)"},
                {"Phenomenon Investigators", "BelieveOrDoubt", ApiType.GenericChoice, "AILogic", "Always",
                        ChooseGenericAi.class, "ChooseGenericAi.checkAiLogic (Always)"},
                {"Struggle for Project Purity", "SiegeChoice", ApiType.GenericChoice, "AILogic", "Enclave",
                        ChooseGenericAi.class,
                        "ChooseGenericAi.checkAiLogic and chooseSingleSpellAbility (namedChoice)"},
                {"Prisoner's Dilemma", null, ApiType.GenericChoice, "AIActivateLast", "True",
                        ChooseGenericAi.class,
                        "ComputerUtilAbility's AIActivateLast sort (SpecialCardAi.PrisonersDilemma)"},
                {"Infinite Reflection", "RememberInitialAttach", ApiType.Pump, "AILogic", "Always", PumpAi.class,
                        "SpellAbilityAi.doTriggerNoCostWithSubs (AILogic Always)"},
                {"Harsh Mercy", "DBRemember", ApiType.PumpAll, "RememberPumped", "True", PumpAllAi.class,
                        "SpecialCardAi.HarshMercy.consider's shape (PumpAll, Creature.ChosenType, RememberPumped)"},
        };
    }

    @Test(dataProvider = "hints")
    public void theLineAForkHintSitsOn(final String name, final String svar, final ApiType api, final String param,
            final String value, final Class<?> aiClass, final String hook) {
        final Game game = initAndCreateGame();
        final Player p = game.getPlayers().get(1);
        final Card card = createCard(name, p);
        final String line = name + " (" + (svar == null ? "spell" : "SVar " + svar) + ", read by " + hook + ")";
        final SpellAbility sa;
        if (svar == null) {
            sa = card.getFirstSpellAbility();
        } else {
            Assert.assertTrue(card.hasSVar(svar), line + ": no such SVar");
            sa = AbilityFactory.getAbility(card, svar);
        }
        Assert.assertNotNull(sa, line + ": no ability");
        Assert.assertEquals(sa.getApi(), api, line + ": the hook reads a " + api + " line");
        Assert.assertEquals(sa.getParam(param), value, line + ": not the line carrying the hook's " + param);
        Assert.assertEquals(SpellApiToAi.Converter.get(sa).getClass(), aiClass,
                line + ": SpellApiToAi gives the line to " + SpellApiToAi.Converter.get(sa).getClass().getSimpleName());
    }

    // a split card offers both halves' spells (Refuse // Cooperate): the spell carrying the row's AILogic
    private static SpellAbility spellWithLogic(final Card card, final String aiLogic, final String line) {
        Assert.assertNotNull(aiLogic, line + ": a SPELL_WITH_LOGIC row names the AILogic");
        for (final SpellAbility sa : card.getAllSpellAbilities()) {
            if (sa.isSpell() && aiLogic.equals(sa.getParam("AILogic"))) {
                return sa;
            }
        }
        Assert.fail(line + ": no spell carrying AILogic " + aiLogic);
        return null;
    }

    @Test(dataProvider = "lines")
    public void theLineAForkHookReadsIsPump(final String name, final Where where, final TriggerType mode,
            final int down, final String aiLogic, final String hook) {
        final Game game = initAndCreateGame();
        final Player p = game.getPlayers().get(1);
        final Card card = createCard(name, p);
        final String line = name + " (" + where + (mode == null ? "" : " " + mode) + (down == 0 ? "" : ", sub " + down)
                + ", read by " + hook + ")";
        SpellAbility sa = where == Where.SPELL_WITH_LOGIC ? spellWithLogic(card, aiLogic, line)
                : find(card, where, mode, line);
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
