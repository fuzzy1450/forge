package forge.ai.ability;

import com.google.common.collect.Iterables;

import forge.ai.AiAbilityDecision;
import forge.ai.AiPlayDecision;
import forge.ai.AiPlayerPredicates;
import forge.ai.ComputerUtilAbility;
import forge.ai.SpecialCardAi;
import forge.ai.SpellAbilityAi;
import forge.game.player.Player;
import forge.game.player.PlayerCollection;
import forge.game.player.PlayerPredicates;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;

import java.util.Map;

public class ChoosePlayerAi extends SpellAbilityAi {
    @Override
    protected AiAbilityDecision canPlay(Player ai, SpellAbility sa) {
        if ("Spectral Searchlight".equals(ComputerUtilAbility.getAbilitySourceName(sa))
                && sa.getParent() == null && sa.isManaAbility()) {
            // The AI mana payer never taps this (ComputerUtilMana.getAIPlayableMana
            // skips non-Mana roots), so the only useful activation is ritual-style:
            // tap only when the mana lets us cast something now. Otherwise the stock
            // unconditional WillPlay below idle-taps it into an emptying pool.
            return SpecialCardAi.SpectralSearchlight.consider(ai, sa)
                    ? new AiAbilityDecision(100, AiPlayDecision.WillPlay)
                    : new AiAbilityDecision(0, AiPlayDecision.CantPlayAi);
        }
        if (SpecialCardAi.SylvanOffering.isOwnHandCast(ai, sa)) {
            // Dead-card batch 3, row 6: the owner's own paid hand cast only, the G3 readmit's own key
            // (B3-2). The DBChoose sub (parent set), copies, effect casts and thefts keep the stock answer.
            return SpecialCardAi.SylvanOffering.consider(ai, sa);
        }
        if (SpecialCardAi.InfernalOffering.isOwnPaidRoot(ai, sa)) {
            // Dead-card batch 3, row 8: the owner's own paid hand cast only, the G3 readmit's own key
            // (B3-2). The stock WillPlay below reads neither graveyard, and the reanimate halves
            // approve unconditionally in chkDrawback (AI:RemoveDeck:All hid that). The DBChoose sub
            // (parent set), ManaAi's no-cost ritual probe, copies, Play-effect casts and thefts keep
            // the stock answer.
            return SpecialCardAi.InfernalOffering.consider(ai, sa);
        }
        if (SpecialCardAi.IntellectualOffering.isRootCast(sa)) {
            // Dead-card batch 3, row 12: the card's own root cast (its hint is dropped, so there is no
            // readmit key: a hand cast, an AI thief's MayPlay cast from exile, a canPlaySa probe's
            // copy). The stock WillPlay below fires the 5-mana symmetric draw-and-untap at the first
            // window it can pay for (AI:RemoveDeck:All hid that). The DBChoose sub (parent set) and
            // effect casts (doTriggerNoCost below) keep the stock answer.
            return SpecialCardAi.IntellectualOffering.consider(ai, sa);
        }
        if (SpecialCardAi.CruelEntertainment.isOwnHandCast(ai, sa)) {
            // Dead-card batch 4, row 21 (W7): the owner's own hand cast only, the G3 readmit's key. Cast only
            // as a Vial Smasher kill, both targets set here (DBControlPlayer1 is CannotPlayAi's otherwise).
            // Cascade and other effect casts (doTriggerNoCost below), copies, flashback and thefts keep the stock
            // WillPlay, which the ControlPlayer sub still refuses.
            return SpecialCardAi.CruelEntertainment.consider(ai, sa);
        }
        return new AiAbilityDecision(100, AiPlayDecision.WillPlay);
    }

    @Override
    public AiAbilityDecision chkDrawback(Player ai, SpellAbility sa) {
        return canPlay(ai, sa);
    }

    @Override
    protected AiAbilityDecision doTriggerNoCost(Player ai, SpellAbility sa, boolean mandatory) {
        if (SpecialCardAi.InfernalOffering.NAME.equals(ComputerUtilAbility.getAbilitySourceName(sa))) {
            // Dead-card batch 3, row 8: effect casts (Nathan Drake's thefts, Prosper, cascade) keep the
            // stock answer, never the paid-cast evaluator
            return new AiAbilityDecision(100, AiPlayDecision.WillPlay);
        }
        if (SpecialCardAi.IntellectualOffering.NAME.equals(ComputerUtilAbility.getAbilitySourceName(sa))) {
            // Dead-card batch 3, row 12: effect casts (Nathan Drake's thefts, Jeleva) keep the stock
            // answer, never the root-cast evaluator
            return new AiAbilityDecision(100, AiPlayDecision.WillPlay);
        }
        return canPlay(ai, sa);
    }

    @Override
    public Player chooseSinglePlayer(Player ai, SpellAbility sa, Iterable<Player> choices, Map<String, Object> params) {
        Player chosen = null;
        if (sa.hasParam("Protect")) {
            chosen = new PlayerCollection(choices).min(PlayerPredicates.compareByLife());
        }
        else if ("Curse".equals(sa.getParam("AILogic"))) {
            PlayerCollection curseChoices = new PlayerCollection(choices).filter(PlayerPredicates.isOpponentOf(ai));
            if (!curseChoices.isEmpty()) {
                chosen = curseChoices.max(AiPlayerPredicates.compareByBoardPosition);
            }
            if (chosen == null) {
                chosen = Iterables.getFirst(choices, null);
                System.out.println("No good curse choices. Picking first available: " + chosen);
            }
        }
        else if ("Pump".equals(sa.getParam("AILogic"))) {
            chosen = Iterables.contains(choices, ai) ? ai : Iterables.getFirst(choices, null);
        }
        else if ("BestAllyBoardPosition".equals(sa.getParam("AILogic"))) {
            PlayerCollection prefChoices = new PlayerCollection(choices);
            prefChoices.removeAll(ai.getOpponents());
            if (!prefChoices.isEmpty()) {
                chosen = prefChoices.max(AiPlayerPredicates.compareByBoardPosition);
            }
            if (chosen == null) {
                chosen = Iterables.getFirst(choices, null);
                System.out.println("No good curse choices. Picking first available: " + chosen);
            }
        } else if ("MostCardsInHand".equals(sa.getParam("AILogic"))) {
            int cardsInHand = 0;
            for (final Player p : choices) {
                int hand = p.getCardsIn(ZoneType.Hand).size();
                if (hand >= cardsInHand) {
                    chosen = p;
                    cardsInHand = hand;
                }
            }
        } else if ("LeastCreatures".equals(sa.getParam("AILogic"))) {
            int creats = 50;
            for (final Player p : choices) {
                int curr = p.getCreaturesInPlay().size();
                if (curr <= creats) {
                    chosen = p;
                    creats = curr;
                }
            }
        } else {
            System.out.println("Default player choice logic.");
            chosen = Iterables.contains(choices, ai) ? ai : Iterables.getFirst(choices, null);
        }
        return chosen;
    }
}
