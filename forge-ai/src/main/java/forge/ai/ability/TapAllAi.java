package forge.ai.ability;

import forge.ai.AiAbilityDecision;
import forge.ai.AiPlayDecision;
import forge.ai.ComputerUtilCombat;
import forge.ai.SpellAbilityAi;
import forge.game.Game;
import forge.game.ability.AbilityUtils;
import forge.game.card.Card;
import forge.game.card.CardCollectionView;
import forge.game.card.CardLists;
import forge.game.card.CardPredicates;
import forge.game.combat.CombatUtil;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.player.PlayerCollection;
import forge.game.player.PlayerPredicates;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;

import java.util.List;

public class TapAllAi extends SpellAbilityAi {
    @Override
    protected AiAbilityDecision checkApiLogic(final Player ai, SpellAbility sa) {
        // If tapping all creatures do it either during declare attackers of AIs turn
        // or during upkeep/begin combat?

        final Card source = sa.getHostCard();
        final Player opp = ai.getStrongestOpponent();
        final Game game = ai.getGame();

        if (game.getPhaseHandler().getPhase().isAfter(PhaseType.COMBAT_BEGIN)) {
            return new AiAbilityDecision(0, AiPlayDecision.CantPlayAi);
        }

        final String valid = sa.getParamOrDefault("ValidCards", "");

        CardCollectionView validTappables = game.getCardsIn(ZoneType.Battlefield);

        if (sa.usesTargeting()) {
            sa.resetTargets();
            sa.getTargets().add(opp);
            validTappables = opp.getCardsIn(ZoneType.Battlefield);
        }

        validTappables = CardLists.getValidCards(validTappables, valid, source.getController(), source, sa);
        validTappables = CardLists.filter(validTappables, CardPredicates.UNTAPPED);

        if (sa.hasParam("AILogic")) {
            String logic = sa.getParam("AILogic");
            if (logic.startsWith("AtLeast")) {
                int num = AbilityUtils.calculateAmount(source, logic.substring(7), sa);
                if (validTappables.size() < num) {
                    return new AiAbilityDecision(0, AiPlayDecision.CantPlayAi);
                }
            }
        }

        if (validTappables.isEmpty()) {
            return new AiAbilityDecision(0, AiPlayDecision.CantPlayAi);
        }

        final List<Card> human = CardLists.filterControlledBy(validTappables, opp);
        final List<Card> compy = CardLists.filterControlledBy(validTappables, ai);
        if (human.size() <= compy.size()) {
            return new AiAbilityDecision(0, AiPlayDecision.CantPlayAi);
        }
        // in AI's turn, check if there are possible attackers, before tapping blockers
        if (game.getPhaseHandler().isPlayerTurn(ai)) {
            validTappables = ai.getCardsIn(ZoneType.Battlefield);
            final boolean any = validTappables.anyMatch(c -> CombatUtil.canAttack(c) && ComputerUtilCombat.canAttackNextTurn(c));
            return any ? new AiAbilityDecision(100, AiPlayDecision.WillPlay) : new AiAbilityDecision(0, AiPlayDecision.CantPlayAi);
        }
        return new AiAbilityDecision(100, AiPlayDecision.WillPlay);
    }

    // Dead-card batch 4, row 17 (Assassin Gauntlet). A targeted TapAll sub-ability had no
    // chkDrawback, so SpellAbilityAi's default refused it (CantPlayAi plus its "default
    // implementation" warning) and never chose its target:
    //  - the non-mandatory consult of the Gauntlet's enters trigger (AiController.checkETBEffects)
    //    failed, and saSideEffects vetoed the whole Equipment (BadEtbEffects): 0 owner casts in
    //    1,864 held decided games over 992 libraries on 2.0.15+499e03087c5 but the 4 where an
    //    opponent's Doorkeeper Thrull had switched that consult off;
    //  - the stack-time call (mandatory) left the sub untargeted, so MagicStack.add refused the whole
    //    trigger ("Couldn't add to stack, failed to target") when the Gauntlet entered by another
    //    route (Danitha, Benalia's Hope; Ratonhnhake:ton; an opponent's Chaos Warp): neither the
    //    attach nor the tap happened.
    // The sub taps only cards the targeted player controls, so an opponent target can never cost us:
    // target the targetable opponent with the most untapped matching cards (ties: the first in
    // getOpponents order), never ourselves. No targetable opponent: TargetingFailed, as the default
    // answers with no candidate. Draws nothing and allocates no SpellAbility id, like the CantPlayAi
    // it replaces. The only script with a targeted TapAll sub in this cardsfolder is
    // assassin_gauntlet.txt (Naya Charm's TapAll is a Charm mode: CharmAi approves a chosen mode
    // without asking it; Gulf Squid, Mistbind Clique, Tempest Caller and Kiora Bests the Sea God's
    // chapter II are trigger roots, judged by doTriggerNoCost below); a deck upload can widen that.
    @Override
    public AiAbilityDecision chkDrawback(final Player ai, final SpellAbility sa) {
        if (!sa.usesTargeting()) {
            return super.chkDrawback(ai, sa);
        }
        Player best = null;
        int bestCount = -1;
        for (final Player opp : ai.getOpponents()) {
            if (!sa.canTarget(opp)) {
                continue;
            }
            final int n = CardLists.count(AbilityUtils.filterListByType(opp.getCardsIn(ZoneType.Battlefield),
                    sa.getParam("ValidCards"), sa), CardPredicates.UNTAPPED);
            if (n > bestCount) {
                best = opp;
                bestCount = n;
            }
        }
        sa.resetTargets();
        if (best == null) {
            return new AiAbilityDecision(0, AiPlayDecision.TargetingFailed);
        }
        sa.getTargets().add(best);
        return new AiAbilityDecision(100, AiPlayDecision.WillPlay);
    }

    private CardCollectionView getTapAllTargets(final String valid, final Card source, SpellAbility sa) {
        final Game game = source.getGame();
        CardCollectionView tmpList = game.getCardsIn(ZoneType.Battlefield);
        tmpList = CardLists.getValidCards(tmpList, valid, source.getController(), source, sa);
        tmpList = CardLists.filter(tmpList, CardPredicates.UNTAPPED);
        return tmpList;
    }

    @Override
    protected AiAbilityDecision doTriggerNoCost(final Player ai, SpellAbility sa, boolean mandatory) {
        final Card source = sa.getHostCard();

        final String valid = sa.getParamOrDefault("ValidCards", "");

        CardCollectionView validTappables = getTapAllTargets(valid, source, sa);

        if (sa.usesTargeting()) {
            final PlayerCollection targetableOpps = ai.getOpponents().filter(PlayerPredicates.isTargetableBy(sa));
            Player target = targetableOpps.max(PlayerPredicates.compareByLife());
            if (target == null && mandatory) {
                target = ai;
            }
            sa.resetTargets();
            sa.getTargets().add(target);
            validTappables = target.getCardsIn(ZoneType.Battlefield);
        }

        if (mandatory) {
            return new AiAbilityDecision(100, AiPlayDecision.WillPlay);
        }

        if (validTappables.size() > 0) {
            final int human = CardLists.count(validTappables, CardPredicates.isControlledByAnyOf(ai.getYourTeam()));
            final int compy = CardLists.count(validTappables, CardPredicates.isControlledByAnyOf(ai.getOpponents()));
            if (human > compy) {
                return new AiAbilityDecision(100, AiPlayDecision.WillPlay);
            }
        }
        return new AiAbilityDecision(0, AiPlayDecision.CantPlayAi);
    }
}
