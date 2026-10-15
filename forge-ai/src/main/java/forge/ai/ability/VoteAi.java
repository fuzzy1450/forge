package forge.ai.ability;

import forge.ai.AiAbilityDecision;
import forge.ai.AiPlayDecision;
import forge.ai.SpecialCardAi;
import forge.ai.SpellAbilityAi;
import forge.game.card.Card;
import forge.game.card.CardLists;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;

import java.util.Map;

public class VoteAi extends SpellAbilityAi {
    /* (non-Javadoc)
     * @see forge.card.abilityfactory.SpellAiLogic#canPlayAI(forge.game.player.Player, java.util.Map, forge.card.spellability.SpellAbility)
     */
    @Override
    protected AiAbilityDecision canPlay(Player aiPlayer, SpellAbility sa) {
        // Mob Verdict has no AILogic, so the fallthrough below declined it at every priority.
        // Name-gated to its own evaluator, whose declines draw no random number (nor did the
        // fallthrough's); every other Vote spell, and every Vote sub (chkDrawback), stays stock.
        if (SpecialCardAi.MobVerdict.handles(sa)) {
            return SpecialCardAi.MobVerdict.consider(aiPlayer, sa);
        }
        // Truth or Consequences has no AILogic either (held 968 times, never cast by its owner).
        // Its own evaluator draws no random number before any decline; vote choice stays stock
        // (ComputerUtil.vote with no AILogic), and Play-effect casts keep doTriggerNoCost.
        if (SpecialCardAi.TruthOrConsequences.handles(sa)) {
            return SpecialCardAi.TruthOrConsequences.consider(aiPlayer, sa);
        }
        // Fateful Tempest has no AILogic either (held 344 times on 2.0.15+55e709fa6ba, never cast by
        // its owner on any engine). Its evaluator draws no random number before any decline; vote
        // choice stays stock (ComputerUtil.vote with no AILogic), and Play-effect casts keep
        // doTriggerNoCost.
        if (SpecialCardAi.FatefulTempest.handles(sa)) {
            return SpecialCardAi.FatefulTempest.consider(aiPlayer, sa);
        }
        // Travel Through Caradhras has no AILogic either (held 274 times on 2.0.15+55e709fa6ba, never
        // cast by its owner on any engine). Its evaluator draws no random number before any decline;
        // vote choice stays stock (ComputerUtil.vote with no AILogic; the script's AIActivateLast$ True
        // is read only by the sort), and Play-effect casts keep doTriggerNoCost.
        if (SpecialCardAi.TravelThroughCaradhras.handles(sa)) {
            return SpecialCardAi.TravelThroughCaradhras.consider(aiPlayer, sa);
        }
        // Sail into the West has no AILogic either (held 205 times on 2.0.15+55e709fa6ba, never
        // cast by its owner on any engine). Its evaluator draws no random number before any
        // decline; vote choice stays stock (ComputerUtil.vote with no AILogic), each player's wheel
        // stays DiscardAi.confirmAction's, and Play-effect casts keep doTriggerNoCost.
        if (SpecialCardAi.SailIntoTheWest.handles(sa)) {
            return SpecialCardAi.SailIntoTheWest.consider(aiPlayer, sa);
        }
        // Selvala's Stampede has no AILogic either (held 180 times on 2.0.15+55e709fa6ba by
        // precon:Temur Roar (TDC), never cast from hand by its owner on any engine). Its evaluator
        // draws no random number before any decline; vote choice stays stock (ComputerUtil.vote
        // with no AILogic), the free vote's pick stays ChangeZoneAi's, and Play-effect casts
        // (Breaching Dragonstorm, a thief's Dazzling Sphinx) keep doTriggerNoCost.
        if (SpecialCardAi.SelvalasStampede.handles(sa)) {
            return SpecialCardAi.SelvalasStampede.consider(aiPlayer, sa);
        }
        // TODO: add ailogic
        String logic = sa.getParam("AILogic");
        final Card host = sa.getHostCard();
        if ("Always".equals(logic)) {
            return new AiAbilityDecision(100, AiPlayDecision.WillPlay);
        } else if ("Judgment".equals(logic)) {
            if (!CardLists.getValidCards(host.getGame().getCardsIn(ZoneType.Battlefield),
                    sa.getParam("VoteCard"), host.getController(), host, sa).isEmpty()) {
                return new AiAbilityDecision(100, AiPlayDecision.WillPlay);
            }
            return new AiAbilityDecision(0, AiPlayDecision.MissingNeededCards);
        } else if ("Torture".equals(logic)) {
            if (aiPlayer.getGame().getPhaseHandler().getPhase().isAfter(PhaseType.MAIN1)) {
                return new AiAbilityDecision(100, AiPlayDecision.WillPlay);
            }
            return new AiAbilityDecision(0, AiPlayDecision.WaitForMain2);
        }
        return new AiAbilityDecision(0, AiPlayDecision.CantPlayAi);
    }

    /* (non-Javadoc)
     * @see forge.card.abilityfactory.SpellAiLogic#chkAIDrawback(java.util.Map, forge.card.spellability.SpellAbility, forge.game.player.Player)
     */
    @Override
    public AiAbilityDecision chkDrawback(Player aiPlayer, SpellAbility sa) {
        return canPlay(aiPlayer, sa);
    }

    @Override
    protected AiAbilityDecision doTriggerNoCost(Player ai, SpellAbility sa, boolean mandatory) {
        return new AiAbilityDecision(100, AiPlayDecision.WillPlay);
    }

    @Override
    public int chooseNumber(Player player, SpellAbility sa, int min, int max, Map<String, Object> params) {
        if (params.containsKey("Voter")) {
            Player p = (Player)params.get("Voter");
            if (p.isOpponentOf(player)) {
                return min;
            }
        }
        if (sa.getActivatingPlayer().isOpponentOf(player)) {
            return min;
        }
        return max;
    }
}
