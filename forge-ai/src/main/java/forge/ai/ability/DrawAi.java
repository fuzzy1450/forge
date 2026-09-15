/*

* Forge: Play Magic: the Gathering.
 * Copyright (C) 2011  Forge Team
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package forge.ai.ability;

import java.util.Map;

import forge.ai.*;
import forge.game.Game;
import forge.game.ability.AbilityUtils;
import forge.game.ability.ApiType;
import forge.game.card.Card;
import forge.game.card.CounterEnumType;
import forge.game.cost.*;
import forge.game.phase.PhaseHandler;
import forge.game.phase.PhaseType;
import forge.game.player.*;
import forge.game.spellability.AbilitySub;
import forge.game.spellability.SpellAbility;
import forge.game.trigger.TriggerType;
import forge.game.zone.ZoneType;
import forge.util.MyRandom;
import forge.util.collect.FCollectionView;
import org.apache.commons.lang3.StringUtils;

public class DrawAi extends SpellAbilityAi {

    /* (non-Javadoc)
     * @see forge.ai.SpellAbilityAi#checkApiLogic(forge.game.player.Player, forge.game.spellability.SpellAbility)
     */
    @Override
    protected AiAbilityDecision checkApiLogic(Player ai, SpellAbility sa) {
        if ("Biomantic Mastery".equals(ComputerUtilAbility.getAbilitySourceName(sa)) && !(sa instanceof AbilitySub)) {
            // Both targets only MEASURE the draw (Defined$ You draws both halves). targetAI
            // computes X before any target exists (always 0) and never targets an opponent
            // for a non-curse draw, so the spell could never be cast.
            return SpecialCardAi.BiomanticMastery.consider(ai, sa);
        }
        if (!targetAI(ai, sa, false)) {
            return new AiAbilityDecision(0, AiPlayDecision.TargetingFailed);
        }
        if ("Witch's Mark".equals(ComputerUtilAbility.getAbilitySourceName(sa))
                && !SpecialCardAi.WitchsMark.doesSomething(ai, sa)) {
            // never a blank: a card we will rummage away, or a creature to wear the Role
            return new AiAbilityDecision(0, AiPlayDecision.CantPlayAi);
        }

        if (sa.usesTargeting()) {
            final Player player = sa.getTargets().getFirstTargetedPlayer();
            if (player != null && player.isOpponentOf(ai)) {
                return new AiAbilityDecision(0, AiPlayDecision.CantPlayAi);
            }
        }

        if (ComputerUtil.playImmediately(ai, sa)) {
            return new AiAbilityDecision(100, AiPlayDecision.WillPlay);
        }

        // Don't tap creatures that may be able to block
        if (ComputerUtil.waitForBlocking(sa)) {
            return new AiAbilityDecision(0, AiPlayDecision.WaitForCombat);
        }

        if (!canLoot(ai, sa)) {
            return new AiAbilityDecision(0, AiPlayDecision.CantPlayAi);
        }

        if (ComputerUtilCost.isSacrificeSelfCost(sa.getPayCosts())) {
            // Canopy lands and other cards that sacrifice themselves to draw cards
            if (ai.getCardsIn(ZoneType.Hand).isEmpty()
                    || (sa.getHostCard().isLand() && ai.getLandsInPlay().size() >= 5)) {
                // TODO: make this configurable in the AI profile
                return new AiAbilityDecision(100, AiPlayDecision.WillPlay);
            }
            return new AiAbilityDecision(0, AiPlayDecision.CostNotAcceptable);
        }

        return new AiAbilityDecision(100, AiPlayDecision.WillPlay);
    }

    /*
     * (non-Javadoc)
     * 
     * @see forge.ai.SpellAbilityAi#willPayCosts(forge.game.player.Player,
     * forge.game.spellability.SpellAbility, forge.game.cost.Cost,
     * forge.game.card.Card)
     */
    @Override
    protected boolean willPayCosts(Player payer, SpellAbility sa, Cost cost, Card source) {
        if (!ComputerUtilCost.checkCreatureSacrificeCost(payer, cost, source, sa)) {
            return false;
        }

        if (!ComputerUtilCost.checkLifeCost(payer, cost, source, 4, sa)) {
            return false;
        }

        if (!ComputerUtilCost.checkDiscardCost(payer, cost, source, sa)) {
            AiCostDecision aiDecisions = new AiCostDecision(payer, sa, false);
            for (final CostPart part : cost.getCostParts()) {
                if (part instanceof CostDiscard) {
                    PaymentDecision decision = part.accept(aiDecisions);
                    if (null == decision)
                        return false;
                    for (Card discard : decision.cards) {
                        if (!ComputerUtil.isWorseThanDraw(payer, discard)) {
                            return false;
                        }
                    }
                }
            }
        }

        if (!ComputerUtilCost.checkRemoveCounterCost(cost, source, sa)) {
            return false;
        }

        return true;
    }

    /*
     * (non-Javadoc)
     * 
     * @see
     * forge.ai.SpellAbilityAi#checkPhaseRestrictions(forge.game.player.Player,
     * forge.game.spellability.SpellAbility, forge.game.phase.PhaseHandler)
     */
    @Override
    protected boolean checkPhaseRestrictions(Player ai, SpellAbility sa, PhaseHandler ph) {
        // Sacrificing a creature in response to something dangerous is generally good in any phase
        boolean isSacCost = false;
        if (sa.getPayCosts() != null && sa.getPayCosts().hasSpecificCostType(CostSacrifice.class)) {
            isSacCost = true;
        }

        // Don't use draw abilities before main 2 if possible
        if (ph.getPhase().isBefore(PhaseType.MAIN2) && !sa.hasParam("ActivationPhases")
                && !ComputerUtil.castSpellInMain1(ai, sa) && !isSacCost) {
            return false;
        }

        return super.checkPhaseRestrictions(ai, sa, ph);
    }

    /*
     * (non-Javadoc)
     * 
     * @see
     * forge.ai.SpellAbilityAi#checkPhaseRestrictions(forge.game.player.Player,
     * forge.game.spellability.SpellAbility, forge.game.phase.PhaseHandler,
     * java.lang.String)
     */
    @Override
    protected boolean checkPhaseRestrictions(Player ai, SpellAbility sa, PhaseHandler ph, String logic) {
        if (logic.equals("VeilOfSummer")) {
            return SpecialCardAi.VeilOfSummer.consider(ai, sa); // this is more of a counterspell than a true draw card, so it's timed by the card-specific logic
        } else if (logic.startsWith("LifeLessThan.")) {
            // LifeLessThan logic presupposes activation as soon as possible in an
            // attempt to save the AI from dying
            return true;
        } else if (logic.equals("RespondToOwnActivation")) {
            return !ai.getGame().getStack().isEmpty() && ai.getGame().getStack().peekAbility().getHostCard().equals(sa.getHostCard());
        } else if ((!ph.getNextTurn().equals(ai) || ph.getPhase().isBefore(PhaseType.END_OF_TURN))
                && !sa.hasParam("PlayerTurn") && !isSorcerySpeed(sa, ai)
                && ai.getCardsIn(ZoneType.Hand).size() > 1 && !ComputerUtil.activateForCost(sa, ai)
                && !"YawgmothsBargain".equals(logic)) {
            return false;
        }
        return super.checkPhaseRestrictions(ai, sa, ph, logic);
    }

    @Override
    public AiAbilityDecision chkDrawback(Player ai, SpellAbility sa) {
        if ("Biomantic Mastery".equals(ComputerUtilAbility.getAbilitySourceName(sa)) && sa.usesTargeting()) {
            // "Another target player" was already chosen by SpecialCardAi.BiomanticMastery.consider
            // together with the root's target; keep it instead of re-targeting generically
            // (TargetUnique forbids the player the root took, and an opponent is never picked).
            final SpellAbility parent = sa.getParent();
            if (parent != null && !parent.getTargets().isEmpty() && sa.isTargetNumberValid()) {
                return new AiAbilityDecision(100, AiPlayDecision.WillPlay);
            }
            return new AiAbilityDecision(0, AiPlayDecision.TargetingFailed);
        }
        if (targetAI(ai, sa, sa.isTrigger() && sa.getHostCard().isInPlay())) {
            return new AiAbilityDecision(100, AiPlayDecision.WillPlay);
        }
        return new AiAbilityDecision(0, AiPlayDecision.TargetingFailed);
    }

    /**
     * Check if looter (draw + discard) effects are worthwhile
     */
    private boolean canLoot(Player ai, SpellAbility sa) {
        final SpellAbility sub = sa.findSubAbilityByType(ApiType.Discard);
        if (sub != null) {
            final Card source = sa.getHostCard();
            final String sourceName = ComputerUtilAbility.getAbilitySourceName(sa);

            int numHand = ai.getCardsIn(ZoneType.Hand).size();
            if ("Jace, Vryn's Prodigy".equals(sourceName) && ai.getCardsIn(ZoneType.Graveyard).size() > 3) {
                return !ai.isCardInPlay("Jace, Telepath Unbound");
            }
            if (source.isSpell() && ai.getCardsIn(ZoneType.Hand).contains(source)) {
                numHand--; // remember to count looter card if it is a spell in hand
            }
            int numDraw = 1;

            if (sa.hasParam("NumCards")) {
                String numDrawStr = sa.getParam("NumCards");
                if (numDrawStr.equals("X") && sa.getSVar(numDrawStr).equals("Count$Converge")) {
                    numDraw = ComputerUtilMana.getConvergeCount(sa, ai);
                } else {
                    numDraw = AbilityUtils.calculateAmount(source, numDrawStr, sa);
                }
            }
            int numDiscard = 1;
            if (sub.hasParam("NumCards")) {
                numDiscard = AbilityUtils.calculateAmount(source, sub.getParam("NumCards"), sub);
            }
            if (numHand == 0 && numDraw == numDiscard) {
                return false; // no looting since everything is dumped
            }
            if (numHand + numDraw < numDiscard) {
                return false; // net loss of cards
            }
        }
        return true;
    }

    private boolean targetAI(final Player ai, final SpellAbility sa, final boolean mandatory) {
        final Card source = sa.getHostCard();
        final Game game = ai.getGame();
        final String logic = sa.getParamOrDefault("AILogic", "");
        final boolean considerPrimary = logic.equals("ConsiderPrimary");
        final boolean drawback = sa.getParent() != null && !considerPrimary;
        boolean assumeSafeX = false; // if true, the AI will assume that the X value has been set to a value that is safe to draw

        int computerHandSize = ai.getCardsIn(ZoneType.Hand).size();
        final int computerLibrarySize = ai.getCardsIn(ZoneType.Library).size();
        final int computerMaxHandSize = ai.getMaxHandSize();

        final SpellAbility root = sa.getRootAbility();

        final SpellAbility gainLife = sa.findSubAbilityByType(ApiType.GainLife);
        final SpellAbility loseLife = sa.findSubAbilityByType(ApiType.LoseLife);
        final SpellAbility getPoison = sa.findSubAbilityByType(ApiType.Poison);

        //if a spell is used don't count the card
        if (sa.isSpell() && source.isInZone(ZoneType.Hand)) {
            computerHandSize -= 1;
        }

        int numCards = 1;
        if (sa.hasParam("NumCards")) {
            numCards = AbilityUtils.calculateAmount(source, sa.getParam("NumCards"), sa);
        }

        boolean xPaid = false;
        final String num = sa.getParam("NumCards");
        if (num != null && num.equals("X")) {
            if (sa.getSVar(num).equals("Count$xPaid")) {
                // Set PayX here to maximum value.
                if (drawback && root.getXManaCostPaid() != null) {
                    numCards = root.getXManaCostPaid();
                } else {
                    numCards = ComputerUtilCost.setMaxXValue(sa, ai, sa.isTrigger());
                    // try not to overdraw
                    int safeDraw = Math.abs(Math.min(computerMaxHandSize - computerHandSize, computerLibrarySize - 3));
                    if (source.isInstant() || source.isSorcery()) { safeDraw++; } // card will be spent
                    numCards = Math.min(numCards, safeDraw);

                    // assuming CostPayLife is the one with X
                    if (sa.getPayCosts().hasSpecificCostType(CostPayLife.class)) {
                        // [Necrologia, Pay X Life : Draw X Cards]
                        // Don't draw more than what's "safe" and don't risk a near death experience
                        boolean aggroAI = AiProfileUtil.getBoolProperty(ai, AiProps.PLAY_AGGRO);
                        while (ComputerUtil.aiLifeInDanger(ai, aggroAI, numCards) && numCards > 0) {
                            numCards--;
                        }
                    }

                    root.setXManaCostPaid(numCards);
                    assumeSafeX = true;
                }
                xPaid = true;
            } else if (sa.getSVar(num).equals("Count$Converge")) {
                numCards = ComputerUtilMana.getConvergeCount(sa, ai);
            }
        }

        // Logic for cards that require special handling
        if ("YawgmothsBargain".equals(logic)) {
            return SpecialCardAi.YawgmothsBargain.consider(ai, sa);
        }
        if ("CommandersInsight".equals(logic)) {
            // NumCards$ Z (commander casts Plus X): the xPaid branch above is keyed on a
            // literal NumCards$ X, so X was never announced and numCards read 0.
            return SpecialCardAi.CommandersInsight.consider(ai, sa, mandatory);
        }

        // Generic logic for all cards that do not need any special handling

        // TODO: if xPaid and one of the below reasons would fail, instead of
        // bailing reduce toPay amount to acceptable level
        if (sa.usesTargeting()) {
            sa.resetTargets();

            // if it wouldn't draw anything and its not mandatory, skip it
            if (numCards == 0 && !mandatory && !drawback) {
                return false;
            }

            PlayerCollection players = game.getPlayers().filter(PlayerPredicates.isTargetableBy(sa));

            if (players.isEmpty()) {
                return false;
            }

            PlayerCollection opps = players.filter(PlayerPredicates.isOpponentOf(ai));

            for (Player oppA : opps) {
                if (sa.isCurse() && ai.canDraw() && oppA.canLoseLife()) { // Risk Factor
                    if (numCards >= computerLibrarySize - 3) {
                        if (ai.isCardInPlay("Laboratory Maniac")) {
                            sa.getTargets().add(oppA);
                            return true;
                        }
                    } else if (computerHandSize + numCards <= computerMaxHandSize) {
                        sa.getTargets().add(oppA);
                        return true;
                    }
                }

                // try to kill opponent
                if (oppA.cantLoseCheck(GameLossReason.Milled) || !oppA.canDraw()) {
                    continue;
                }

                // try to mill opponent
                if (numCards >= oppA.getCardsIn(ZoneType.Library).size()) {
                    // but only it he doesn't have Laboratory Maniac
                    // also disable it for other checks later too
                    if (oppA.isCardInPlay("Laboratory Maniac")) {
                        continue;
                    }

                    sa.getTargets().add(oppA);
                    return true;
                }

                // try to make opponent pay to death
                if (loseLife != null && oppA.canLoseLife()) {
                    // loseLife for Target
                    if (loseLife.hasParam("Defined") && "Targeted".equals(loseLife.getParam("Defined"))) {
                        // currently all Draw / Lose cards use the same value
                        // for drawing and losing life
                        if (numCards >= oppA.getLife()) {
                            if (xPaid) {
                                root.setXManaCostPaid(oppA.getLife());
                            }
                            sa.getTargets().add(oppA);
                            return true;
                        }
                    }
                }

                // that opponent can gain life and also lose life and that life gain is negative
                if (gainLife != null && oppA.canGainLife() && oppA.canLoseLife() && ComputerUtil.lifegainNegative(oppA, source)) {
                    if (gainLife.hasParam("Defined") && "Targeted".equals(gainLife.getParam("Defined"))) {
                        if (numCards >= oppA.getLife()) {
                            if (xPaid) {
                                root.setXManaCostPaid(oppA.getLife());
                            }
                            sa.getTargets().add(oppA);
                            return true;
                        }
                    }
                }

                // try to make opponent lose to poison
                // currently only Caress of Phyrexia
                if (getPoison != null && oppA.canReceiveCounters(CounterEnumType.POISON)) {
                    if (oppA.getPoisonCounters() + numCards > 9) {
                        sa.getTargets().add(oppA);
                        return true;
                    }
                }
                // we're trying to save ourselves from death
                // (e.g. Bargain), so target the opp anyway
                if (logic.startsWith("LifeLessThan.")) {
                    int threshold = Integer.parseInt(logic.substring(logic.indexOf(".") + 1));
                    sa.getTargets().add(oppA);
                    return ai.getLife() < threshold;
                }
            }
            
            boolean aiTarget = sa.canTarget(ai) && (mandatory || ai.canDraw());
            // checks what the ai prevent from casting it on itself
            // if spell is not mandatory
            if (aiTarget && !ai.cantLose()) {
                if (numCards >= computerLibrarySize - 3) {
                    if (xPaid) {
                        numCards = computerLibrarySize - 1;
                        if (numCards <= 0 && !mandatory) {
                            // not drawing anything, so don't do it
                            return false;
                        }
                    } else if (!ai.isCardInPlay("Laboratory Maniac")) {
                        aiTarget = false;
                    }
                }

                if (loseLife != null && ai.canLoseLife()) {
                    if (numCards >= ai.getLife() + 5) {
                        if (xPaid) {
                            numCards = Math.min(numCards, ai.getLife() - 5);
                            if (numCards <= 0) {
                                aiTarget = false;
                            }
                        } else {
                            aiTarget = false;
                        }
                    }
                }

                if (getPoison != null && ai.canReceiveCounters(CounterEnumType.POISON)) {
                    if (numCards + ai.getPoisonCounters() >= 8) {
                        aiTarget = false;
                    }
                }

                if (xPaid) {
                    root.setXManaCostPaid(numCards);
                }
            }

            if (aiTarget) {
                if (!ai.isCardInPlay("Laboratory Maniac") && computerHandSize + numCards > computerMaxHandSize && game.getPhaseHandler().isPlayerTurn(ai)) {
                    if (xPaid) {
                        numCards = computerMaxHandSize - computerHandSize;
                        if (source.isInZone(ZoneType.Hand)) {
                            numCards++; // the card will be spent
                        }
                        root.setXManaCostPaid(numCards);
                    } else {
                        // Don't draw too many cards and then risk discarding cards at EOT
                        if (!drawback && !mandatory) {
                            return false;
                        }
                    }
                }

                sa.getTargets().add(ai);
                return true;
            }

            // try to benefit ally
            for (Player ally : ai.getAllies()) {
                // try to select ally to help
                if (!sa.canTarget(ally) || !ally.canDraw()) {
                    continue;
                }

                // use xPaid abilities only for itself
                if (xPaid) {
                    continue;
                }

                // ally would draw more than it can
                if (numCards >= ally.getCardsIn(ZoneType.Library).size()) {
                    if (!ally.isCardInPlay("Laboratory Maniac")) {
                        continue;
                    }
                }

                // ally would lose because of life lost
                if (loseLife != null && ally.canLoseLife()) {
                    if (numCards < ai.getLife() - 5) {
                        continue;
                    }
                }

                // ally would lose because of poison
                if (getPoison != null && ally.canReceiveCounters(CounterEnumType.POISON) && ally.getPoisonCounters() + numCards > 9) {
                        continue;
                }

                sa.getTargets().add(ally);
                return true;
            }

            // no nice targets, don't do it
            if (!mandatory) {
                // Symmetric draw: the same resolution draws us at least as many cards as it
                // hands the targeted opponent. The untargeted equivalents (Vision Skeins,
                // Words of Wisdom) already pass the non-targeted branch below; an
                // opponent-only target must not veto the whole spell, chapter or trigger.
                final Player symOpp = chooseSymmetricDrawOpponent(ai, sa, opps, numCards, xPaid,
                        computerLibrarySize, computerMaxHandSize, loseLife, gainLife, getPoison);
                if (symOpp != null) {
                    sa.getTargets().add(symOpp);
                    return true;
                }
                return false;
            }

            // still try to target opponent first
            Player oppMin = opps.min(PlayerPredicates.compareByLife());
            if (oppMin != null) {
                sa.getTargets().add(oppMin);
                return true;
            }

            // final solution for a possible target
            Player result = players.min(PlayerPredicates.compareByLife());
            if (result != null) {
                sa.getTargets().add(result);
                return true;
            }
        } else if (!mandatory) {
            // ability is not targeted

            // TODO: consider if human is the defined player

            if ((numCards == 0 || !ai.canDraw()) && !drawback) {
                return false;
            }

            if (numCards >= computerLibrarySize - 3) {
                if (ai.isCardInPlay("Laboratory Maniac") && !ai.cantWin()) {
                    return true;
                }
                // Don't deck yourself
                return false;
            }

            if ((computerHandSize + numCards > computerMaxHandSize)) {
                // Don't draw too many cards and then risk discarding cards at EOT
                 if (game.getPhaseHandler().isPlayerTurn(ai)
                        && !sa.isTrigger()
                        && !assumeSafeX
                        && !drawback) {
                     return false;
                 }

                if (computerHandSize > computerMaxHandSize) {
                    // Don't make my hand size get too big if already at max
                    return false;
                }
            }
        }
        return true;
    }

    private static boolean hasConditionParam(final SpellAbility s) {
        for (final String key : s.getMapParams().keySet()) {
            if (key.startsWith("Condition")) {
                return true;
            }
        }
        return false;
    }

    /**
     * The opponent to target with a non-mandatory, opponent-only draw that is symmetric: the
     * same resolution draws the AI at least as many cards ("you and target opponent each draw",
     * or a fixed Draw for us elsewhere in the chain, as in The Wedding of River Song). Returns
     * null to keep the stock refusal. Draws no random numbers, like the return false it replaces.
     */
    private static Player chooseSymmetricDrawOpponent(final Player ai, final SpellAbility sa, final PlayerCollection opps,
            final int numCards, final boolean xPaid, final int librarySize, final int maxHandSize,
            final SpellAbility loseLife, final SpellAbility gainLife, final SpellAbility poison) {
        // SHAPE: a fixed, unconditional, single-target draw that cannot target us, and whose target is a drawer
        if (xPaid || numCards <= 0 || !StringUtils.isNumeric(sa.getParamOrDefault("NumCards", "1"))
                || sa.isCurse() || sa.canTarget(ai)
                || loseLife != null || gainLife != null || poison != null
                || sa.hasParam("UnlessCost") || sa.hasParam("Upto") || hasConditionParam(sa)
                || sa.getMaxTargets() != 1) {
            return null;
        }
        // a root spell (Secret Rendezvous) keeps the stock refusal on every route, effect casts included
        if (sa.isSpell() && sa.getParent() == null) {
            return null;
        }
        final String defined = sa.getParam("Defined");
        if (defined != null && !"TargetedAndYou".equals(defined)) {
            return null; // Defined$ You and the like: the target is not the drawer
        }
        int selfDraw = "TargetedAndYou".equals(defined) ? numCards : 0;
        for (SpellAbility s = sa.getRootAbility(); s != null; s = s.getSubAbility()) {
            if (s == sa || s.getApi() != ApiType.Draw || s.usesTargeting()) {
                continue;
            }
            if (!"You".equals(s.getParamOrDefault("Defined", "You"))) {
                continue;
            }
            if (s.hasParam("UnlessCost") || s.hasParam("Upto") || s.hasParam("OptionalDecider") || hasConditionParam(s)) {
                continue;
            }
            final String n = s.getParamOrDefault("NumCards", "1");
            if (!StringUtils.isNumeric(n)) {
                continue; // only a fixed, unconditional draw for us counts
            }
            selfDraw = Math.max(selfDraw, Integer.parseInt(n));
        }
        // FLOOR 1, parity: never give an opponent more cards than we draw in the same resolution
        if (selfDraw < numCards) {
            return null;
        }
        // FLOOR 2, our own draw is safe: no deck-out, no discard to hand size on our turn
        if (!ai.canDraw() || selfDraw >= librarySize - 3) {
            return null;
        }
        final Card host = sa.getHostCard();
        final boolean hostInHand = host != null && host.isInZone(ZoneType.Hand);
        final int handExcl = ai.getCardsIn(ZoneType.Hand).size() - (hostInHand ? 1 : 0);
        if (handExcl + selfDraw > maxHandSize && ai.getGame().getPhaseHandler().isPlayerTurn(ai)) {
            return null;
        }
        // FLOOR 3, refuel guard: when the resolution nets us fewer cards than the opponent gets
        // (an instant or sorcery from hand is spent), never refill an opponent holding fewer cards than we do
        final boolean spent = hostInHand && (host.isInstant() || host.isSorcery());
        final boolean behindOnCards = selfDraw - (spent ? 1 : 0) < numCards;
        Player best = null;
        for (final Player opp : opps) {
            // FLOOR 5: never feed an opponent's draw triggers (Psychosis Crawler, Niv-Mizzet, Sheoldred)
            if (opp.getCardsIn(ZoneType.Battlefield).anyMatch(c -> c.getTriggers().anyMatch(t -> t.getMode() == TriggerType.Drawn))) {
                continue;
            }
            if (!opp.canDraw()) {
                return opp; // pure gain
            }
            // FLOOR 4: never draw an opponent into an empty library (the mill-kill branch above handled the good case)
            if (numCards >= opp.getCardsIn(ZoneType.Library).size()) {
                continue;
            }
            final int oppHand = opp.getCardsIn(ZoneType.Hand).size();
            if (behindOnCards && handExcl > oppHand) {
                continue;
            }
            // cards are worth least to the fullest hand
            if (best == null || oppHand > best.getCardsIn(ZoneType.Hand).size()) {
                best = opp;
            }
        }
        return best;
    }

    @Override
    protected AiAbilityDecision doTriggerNoCost(Player ai, SpellAbility sa, boolean mandatory) {
        if (targetAI(ai, sa, mandatory)) {
            return new AiAbilityDecision(100, AiPlayDecision.WillPlay);
        }
        return new AiAbilityDecision(0, AiPlayDecision.TargetingFailed);
    }

    /* (non-Javadoc)
     * @see forge.card.ability.SpellAbilityAi#confirmAction(forge.game.player.Player, forge.card.spellability.SpellAbility, forge.game.player.PlayerActionConfirmMode, java.lang.String)
     */
    @Override
    public boolean confirmAction(Player player, SpellAbility sa, PlayerActionConfirmMode mode, String message, Map<String, Object> params) {
        int numCards = sa.hasParam("NumCards") ? AbilityUtils.calculateAmount(sa.getHostCard(), sa.getParam("NumCards"), sa) : 1;
        // AI shouldn't mill itself
        if (numCards < player.getZone(ZoneType.Library).size())
            return true;
        // except it has Laboratory Maniac
        return player.isCardInPlay("Laboratory Maniac");
    }

    @Override
    public boolean willPayUnlessCost(Player payer, SpellAbility sa, Cost cost, boolean alreadyPaid, FCollectionView<Player> payers) {
        if ("Witch's Mark".equals(ComputerUtilAbility.getAbilitySourceName(sa))
                && !SpecialCardAi.WitchsMark.willRummage(payer, sa)) {
            // every Zada, Hedron Grinder copy asks again: stop before decking us or feeding punishers
            return false;
        }
        final Card host = sa.getHostCard();
        final String aiLogic = sa.getParam("UnlessAI");

        if ("LowPriority".equals(aiLogic) && MyRandom.getRandom().nextInt(100) < 67) {
            return false;
        }

        // Risk Factor Effects
        for (Player p : AbilityUtils.getDefinedPlayers(host, sa.getParam("Defined"), sa)) {
            if (p.isOpponentOf(payer)) {
                if (!p.canDraw()) {
                    return false;
                }
                if (cost.hasSpecificCostType(CostDamage.class)) {
                    if (!payer.canLoseLife()) {
                        continue;
                    }
                    final CostDamage pay = cost.getCostPartByType(CostDamage.class);
                    int realDamage = ComputerUtilCombat.predictDamageTo(payer, pay.getAbilityAmount(sa), host, false);
                    if (payer.getLife() < realDamage * 2) {
                        return false;
                    }
                }
            }
        }
        // TODO add logic for Discard + Draw Effects

        return super.willPayUnlessCost(payer, sa, cost, alreadyPaid, payers);
    }
}
