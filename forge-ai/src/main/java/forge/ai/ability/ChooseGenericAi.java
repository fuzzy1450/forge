package forge.ai.ability;

import com.google.common.collect.Lists;
import forge.ai.*;
import forge.card.MagicColor;
import forge.game.Game;
import forge.game.card.Card;
import forge.game.card.CardCollectionView;
import forge.game.cost.Cost;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.spellability.AbilitySub;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import forge.util.Aggregates;
import forge.util.collect.FCollection;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class ChooseGenericAi extends SpellAbilityAi {

    @Override
    protected boolean checkAiLogic(final Player ai, final SpellAbility sa, final String aiLogic) {
        if ("Khans".equals(aiLogic) || "Dragons".equals(aiLogic)) {
            return true;
        } else if ("Pump".equals(aiLogic) || "BestOption".equals(aiLogic)) {
            for (AbilitySub sb : sa.getAdditionalAbilityList("Choices")) {
                if (SpellApiToAi.Converter.get(sb).canPlayWithSubs(ai, sb).willingToPlay()) {
                    return true;
                }
            }
        } else if ("Always".equals(aiLogic)) {
            return true;
        } else if ("ArchangelOfStrife".equals(aiLogic)) {
            // Archangel of Strife, judged whole in SpecialCardAi. Only the pre-cast ETB dry
            // run asks (AiController.checkETBEffects); the replacement is mandatory, so
            // resolution never does, and chooseSingleSpellAbility keeps spells.get(0) (War).
            return SpecialCardAi.ArchangelOfStrife.consider(ai, sa);
        } else if ("BorderlandExplorer".equals(aiLogic)) {
            // Borderland Explorer: "each player may discard a card". Every chooser, us included,
            // may answer No at resolution (chooseSingleSpellAbility -> SpecialCardAi), so the ETB
            // never costs us a card; refusing it here vetoed the whole 3/1 at
            // AiController.checkETBEffects (BadEtbEffects). Only the pre-cast dry run asks: the
            // real trigger is mandatory. SpecialCardAi keeps an unaffordable window refused.
            return SpecialCardAi.BorderlandExplorer.considerEtb(ai, sa);
        } else if (namedChoice(sa.getAdditionalAbilityList("Choices"), aiLogic) != null) {
            // "As this enters, choose A or B" with AILogic naming the preferred mode
            // (Battle of Hoover Dam: Legion). The choice itself is free; refusing it
            // vetoes the whole permanent at AiController.checkETBEffects. Same contract
            // the Khans/Dragons sieges already get; chooseSingleSpellAbility picks it.
            return true;
        }
        return false;
    }

    /** The choice whose description is exactly the AILogic string, or null. */
    private static <T extends SpellAbility> T namedChoice(final List<T> choices, final String logic) {
        for (final T choice : choices) {
            if (logic.equals(choice.getDescription())) {
                return choice;
            }
        }
        return null;
    }

    @Override
    protected AiAbilityDecision checkApiLogic(final Player ai, final SpellAbility sa) {
        if ("Seize the Spotlight".equals(ComputerUtilAbility.getAbilitySourceName(sa))) {
            // No AILogic, so the fallthrough below refused it outright. Judged
            // whole in SpecialCardAi.SeizeTheSpotlight; every other GenericChoice
            // card takes exactly the path it took before.
            return SpecialCardAi.SeizeTheSpotlight.consider(ai, sa);
        }
        if ("Imposing Grandeur".equals(ComputerUtilAbility.getAbilitySourceName(sa)) && !(sa instanceof AbilitySub)) {
            // Symmetric "each player may wheel into their commander's MV", no AILogic,
            // so the fallthrough below refused it outright. Judged whole in
            // SpecialCardAi.ImposingGrandeur (our hand, library and the opponents' refill);
            // every other GenericChoice card takes exactly the path it took before.
            return SpecialCardAi.ImposingGrandeur.consider(ai, sa);
        }
        if ("Prisoner's Dilemma".equals(ComputerUtilAbility.getAbilitySourceName(sa))) {
            // Its damage sits below a GenericChoice this handler cannot see past, and it
            // carries no AILogic (the opponents' chooser reads AILogic too), so the
            // fallthrough below refused it outright. Judged whole in
            // SpecialCardAi.PrisonersDilemma; every other GenericChoice card takes
            // exactly the path it took before.
            return SpecialCardAi.PrisonersDilemma.consider(ai, sa);
        }
        if (sa.hasParam("AILogic")) {
            // This is equivalent to what was here before but feels bad
            return new AiAbilityDecision(100, AiPlayDecision.WillPlay);
        }

        return new AiAbilityDecision(0, AiPlayDecision.CantPlayAi);
    }

    /* (non-Javadoc)
     * @see forge.card.abilityfactory.SpellAiLogic#chkAIDrawback(java.util.Map, forge.card.spellability.SpellAbility, forge.game.player.Player)
     */
    @Override
    public AiAbilityDecision chkDrawback(Player aiPlayer, SpellAbility sa) {
        AiAbilityDecision decision;
        if (sa.isTrigger()) {
            decision = doTriggerNoCost(aiPlayer, sa, sa.isMandatory());
        } else {
            decision = checkApiLogic(aiPlayer, sa);
        }

        return decision;
    }

    @Override
    protected AiAbilityDecision doTriggerNoCost(final Player aiPlayer, final SpellAbility sa, final boolean mandatory) {
        if ("CombustibleGearhulk".equals(sa.getParam("AILogic")) || "SoulEcho".equals(sa.getParam("AILogic"))) {
            for (final Player p : aiPlayer.getOpponents()) {
                if (p.canBeTargetedBy(sa)) {
                    sa.resetTargets();
                    sa.getTargets().add(p);
                    return new AiAbilityDecision(100, AiPlayDecision.WillPlay);
                }
            }
            return new AiAbilityDecision(100, AiPlayDecision.WillPlay); // perhaps the opponent(s) had Sigarda, Heron's Grace or another effect giving hexproof in play, still play the creature as 6/6
        }
        if (ComputerUtilAbility.getAbilitySourceName(sa).equals("Deathmist Raptor")) {
            return new AiAbilityDecision(100, AiPlayDecision.WillPlay);
        }
        return super.doTriggerNoCost(aiPlayer, sa, mandatory);
    }

    @Override
    public SpellAbility chooseSingleSpellAbility(Player player, SpellAbility sa, List<SpellAbility> spells, Map<String, Object> params) {
        Card host = sa.getHostCard();
        if ("Imposing Grandeur".equals(host.getName())) {
            // Each player's own "discard my hand?" answer: yes only for a real refill
            // (the logic == null fallthrough below answers "Discard" for every player, always).
            return SpecialCardAi.ImposingGrandeur.chooseWheel(player, host, spells);
        }
        final Game game = host.getGame();
        final String logic = sa.getParam("AILogic");
        if (logic == null) {
            return spells.get(0);
        } else if ("Random".equals(logic)) {
            return Aggregates.random(spells);
        } else if ("Phasing".equals(logic)) { // Teferi's Realm : keep aggressive
            List<SpellAbility> filtered = spells.stream()
                    .filter(sp -> !sp.getDescription().contains("Creature") && !sp.getDescription().contains("Land"))
                    .collect(Collectors.toList());
            return Aggregates.random(filtered);
        } else if ("PayUnlessCost".equals(logic)) {
            for (final SpellAbility sp : spells) {
                String unlessCost = sp.getParam("UnlessCost");
                sp.setActivatingPlayer(sa.getActivatingPlayer());
                Cost unless = new Cost(unlessCost, false);
                if (SpellApiToAi.Converter.get(sp).willPayUnlessCost(player, sp, unless, false, new FCollection<>(player))
                        && ComputerUtilCost.canPayCost(unless, sp, player, true)) {
                    return sp;
                }
            }
            return spells.get(0);
        } else if ("Khans".equals(logic) || "Dragons".equals(logic)) { // Fate Reforged sieges
            for (final SpellAbility sp : spells) {
                if (sp.getDescription().equals(logic)) {
                    return sp;
                }
            }
        } else if ("SelfOthers".equals(logic)) {
            SpellAbility self = null, others = null;
            for (final SpellAbility sp : spells) {
                if (sp.getDescription().equals("Self")) {
                    self = sp;
                } else {
                    others = sp;
                }
            }
            String hostname = host.getName();
            if (hostname.equals("May Civilization Collapse")) {
                if (player.getLandsInPlay().isEmpty()) {
                    return self;
                }
            } else if (hostname.equals("Feed the Machine")) {
                if (player.getCreaturesInPlay().isEmpty()) {
                    return self;
                }
            } else if (hostname.equals("Surrender Your Thoughts")) {
                if (player.getCardsIn(ZoneType.Hand).isEmpty()) {
                    return self;
                }
            } else if (hostname.equals("The Fate of the Flammable")) {
                if (!player.canLoseLife()) {
                    return self;
                }
            }
            return others;
        } else if ("Counters".equals(logic)) {
            // TODO: this code will need generalization if this logic is used for cards other
            // than Elspeth Conquers Death with different choice parameters
            SpellAbility p1p1 = null, loyalty = null;
            for (final SpellAbility sp : spells) {
                if (("P1P1").equals(sp.getParam("CounterType"))) {
                    p1p1 = sp;
                } else {
                    loyalty = sp;
                }
            }
            if (sa.getParent().getTargetCard() != null && sa.getParent().getTargetCard().isPlaneswalker()) {
                return loyalty;
            } else {
                return p1p1;
            }
        } else if ("Fatespinner".equals(logic)) {
            SpellAbility skipDraw = null, /*skipMain = null,*/ skipCombat = null;
            for (final SpellAbility sp : spells) {
                if (sp.getDescription().equals("FatespinnerSkipDraw")) {
                    skipDraw = sp;
                } else if (sp.getDescription().equals("FatespinnerSkipMain")) {
                    //skipMain = sp;
                } else {
                    skipCombat = sp;
                }
            }
            // FatespinnerSkipDraw,FatespinnerSkipMain,FatespinnerSkipCombat
            if (game.getReplacementHandler().wouldPhaseBeSkipped(player, PhaseType.DRAW)) {
                return skipDraw;
            }
            if (game.getReplacementHandler().wouldPhaseBeSkipped(player, PhaseType.COMBAT_BEGIN)) {
                return skipCombat;
            }

            // TODO If combat is poor, Skip Combat
            // Todo if hand is empty or mostly empty, skip main phase
            // Todo if hand has gas, skip draw
            return Aggregates.random(spells);
        } else if ("SinProdder".equals(logic)) {
            SpellAbility allow = null, deny = null;
            for (final SpellAbility sp : spells) {
                if (sp.getDescription().equals("No")) {
                    allow = sp;
                } else {
                    deny = sp;
                }
            }

            Card imprinted = host.getImprintedCards().getFirst();
            int dmg = imprinted.getCMC();
            Player owner = imprinted.getOwner();

            //useless cards in hand
            if (imprinted.getName().equals("Bridge from Below") ||
                    imprinted.getName().equals("Haakon, Stromgald Scourge")) {
                return allow;
            }

            //bad cards when are thrown from the library to the graveyard, but Yixlid can prevent that
            if (!player.getGame().isCardInPlay("Yixlid Jailer") && (
                    imprinted.getName().equals("Gaea's Blessing") ||
                    imprinted.getName().equals("Narcomoeba"))) {
                return allow;
            }

            // milling against Tamiyo is pointless
            if (owner.isCardInCommand("Emblem — Tamiyo, the Moon Sage")) {
                return allow;
            }

            // milling a land against Gitrog result in card draw
            if (imprinted.isLand() && owner.isCardInPlay("The Gitrog Monster")) {
                // try to mill owner
                if (owner.getCardsIn(ZoneType.Library).size() < 5) {
                    return deny;
                }
                return allow;
            }

            // milling a creature against Sidisi result in more creatures
            if (imprinted.isCreature() && owner.isCardInPlay("Sidisi, Brood Tyrant")) {
                return allow;
            }

            //if Iona does prevent from casting, allow it to draw
            for (final Card io : player.getCardsIn(ZoneType.Battlefield, "Iona, Shield of Emeria")) {
                if (imprinted.getColor().hasAnyColor(MagicColor.fromName(io.getChosenColor()))) {
                    return allow;
                }
            }

            if (dmg == 0) {
                // If CMC = 0, mill it!
                return deny;
            } else if (dmg + 3 > player.getLife()) {
                // if low on life, do nothing.
                return allow;
            } else if (player.getLife() - dmg > 15) {
                // TODO Check "danger" level of card
                // If lots of life, and card might be dangerous? Mill it!
                return deny;
            }
            // if unsure, random?
            return Aggregates.random(spells);
        } else if ("CombustibleGearhulk".equals(logic)) {
            Player controller = sa.getActivatingPlayer();
            List<ZoneType> zones = ZoneType.listValueOf("Graveyard, Battlefield, Exile");
            int life = player.getLife();
            CardCollectionView revealedCards = controller.getCardsIn(zones);

            if (revealedCards.size() < 5) {
                // Not very many revealed cards, just guess based on lifetotal
                return life < 7 ? spells.get(0) : spells.get(1);
            }

            int totalCMC = 0;
            for (Card c : revealedCards) {
                totalCMC += c.getCMC();
            }

            int bestGuessDamage = totalCMC * 3 / revealedCards.size();
            return life <= bestGuessDamage ? spells.get(0) : spells.get(1);
        }  else if ("SoulEcho".equals(logic)) {
            return sa.getHostCard().getController().getLife() < 10 ? spells.get(0) : Aggregates.random(spells);
        } else if ("Pump".equals(logic) || "BestOption".equals(logic)) {
            List<SpellAbility> filtered = Lists.newArrayList();
            // filter first for the spells which can be done
            for (SpellAbility sp : spells) {
                if (SpellApiToAi.Converter.get(sp).canPlayWithSubs(player, sp).willingToPlay()) {
                    filtered.add(sp);
                }
            }

            // TODO find better way to check
            if (!filtered.isEmpty()) {
                return filtered.get(0);
            }
        } else if ("FoodOrTreasure".equals(logic)) {
            // Tireless Provisioner
            // TODO: implement a better way to check for possible benefits in each case. If made generic, replace
            // fixed spells.get(N) with a way to detect which SA creates which token
            return ComputerUtil.aiLifeInDanger(player, false, 0) ? spells.get(0) : spells.get(1);
        } else if ("BorderlandExplorer".equals(logic)) {
            // each chooser's own answer; the fallback below would answer Discard for everyone, always
            return SpecialCardAi.BorderlandExplorer.chooseDiscardOrNo(player, sa, spells);
        }
        // AILogic naming one of the choices: take that mode (see checkAiLogic)
        final SpellAbility named = namedChoice(spells, logic);
        if (named != null) {
            return named;
        }
        return spells.get(0);   // return first choice if no logic found
    }
}
