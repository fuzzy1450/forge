package forge.ai.ability;

import com.google.common.collect.Lists;
import forge.ai.*;
import forge.game.GameActionUtil;
import forge.game.ability.AbilityUtils;
import forge.game.ability.effects.CharmEffect;
import forge.game.card.Card;
import forge.game.keyword.Keyword;
import forge.game.player.Player;
import forge.game.spellability.AbilitySub;
import forge.game.spellability.OptionalCost;
import forge.game.spellability.OptionalCostValue;
import forge.game.spellability.SpellAbility;
import forge.util.Aggregates;
import forge.util.MyRandom;
import forge.util.collect.FCollection;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class CharmAi extends SpellAbilityAi {
    @Override
    protected AiAbilityDecision checkApiLogic(Player ai, SpellAbility sa) {
        final Card source = sa.getHostCard();
        List<AbilitySub> choices = CharmEffect.makePossibleOptions(sa);

        final int num;
        final int min;
        if (sa.isEntwine()) {
            num = min = choices.size();
        } else {
            num = AbilityUtils.calculateAmount(source, sa.getParamOrDefault("CharmNum", "1"), sa);
            min = sa.hasParam("MinCharmNum") ? AbilityUtils.calculateAmount(source, sa.getParam("MinCharmNum"), sa) : num;
        }

        boolean timingRight = sa.isTrigger(); //is there a reason to play the charm now?
        boolean choiceForOpp = !ai.equals(sa.getActivatingPlayer());

        // Reset the chosen list otherwise it will be locked in forever by earlier calls
        sa.setChosenList(null);
        sa.setSubAbility(null);
        List<AbilitySub> chosenList;

        if (choiceForOpp) {
            // This branch is for "An Opponent chooses" Charm spells from Alliances
            // Current just choose the first available spell, which seem generally less disastrous for the AI.
            chosenList = choices.subList(1, choices.size());
        } else if ("Triskaidekaphobia".equals(ComputerUtilAbility.getAbilitySourceName(sa))) {
            chosenList = chooseTriskaidekaphobia(choices, ai);
        } else if ("Brokers Confluence".equals(ComputerUtilAbility.getAbilitySourceName(sa))) {
            chosenList = SpecialCardAi.BrokersConfluence.chooseModes(ai, sa, choices, num);
        } else if ("Promise of Power".equals(ComputerUtilAbility.getAbilitySourceName(sa))) {
            chosenList = SpecialCardAi.PromiseOfPower.chooseModes(ai, sa, choices);
        } else if (SpecialCardAi.DoomsdayConfluence.handles(sa)) {
            // AI:RemoveDeck:All kept the owner's cast off the playable list, so A never reached the
            // shuffle below for it: this gate sits before that draw, and the evaluator draws nothing
            // until its mana floor passes. A Play-effect cast (Nathan Drake, Jeleva, Silent-Blade
            // Oni) and a copy never match, and keep the stock path the hint never covered.
            chosenList = SpecialCardAi.DoomsdayConfluence.chooseModes(ai, sa, choices);
        } else if (SpecialCardAi.ProfaneCommand.routes(ai, sa)) {
            // AI:RemoveDeck:All kept every path routes() matches off the playable list, so A never
            // reached the shuffle below on them: the owner's cast from hand (planned by the
            // evaluator, which draws nothing until its plan passes on the board) and every MayPlay
            // copy (declined first, RNG-free). A Play-effect cast (Nathan Drake) and a no-mana test
            // copy (ManaAi's ManaRitual hand scan) never match, and keep the stock path they took in A.
            chosenList = SpecialCardAi.ProfaneCommand.chooseModes(ai, sa);
        } else if (SpecialCardAi.MysticConfluence.handles(sa)) {
            // Every evaluation AI:RemoveDeck:All used to drop (getSpellAbilityToPlay's list, any zone,
            // any player), so A never reached the shuffle below for them: this gate sits before that
            // draw, and the evaluator draws nothing on any decline. It judges only the owner's cast
            // from hand and returns empty for the rest. Play-effect casts (Jeleva, Silent-Blade Oni,
            // Nathan Drake, Epic Experiment, Mizzix's Mastery), copies and triggers never read the
            // hint and keep the stock chooser.
            chosenList = SpecialCardAi.MysticConfluence.chooseModes(ai, sa, choices, num);
        } else if ("Unite the Coalition".equals(ComputerUtilAbility.getAbilitySourceName(sa))) {
            // Unhinted, so A took the stock else below at every held evaluation: its shuffle never
            // fired (num 5 is never below the five or four offered modes), and
            // chooseMultipleOptionsAi ran every mode's handler without reaching five passes. The
            // evaluator replays that pass first, so a decline draws exactly what A drew, and only
            // then builds its own five slots. Play-effect and cascade casts come here too, on purpose.
            chosenList = SpecialCardAi.UniteTheCoalition.chooseModes(ai, sa, choices, num);
        } else if (SpecialCardAi.GrabTheReins.handles(sa)) {
            // AI:RemoveDeck:All kept the owner's hand cast off the playable list, but hand scans that
            // ignore the hint (ManaAi's ritual scan, TapAi.willPayUnlessCost through
            // hasReasonToPlayCardThisTurn, the Nykthos and Yawgmoth's Will scanners) reached the stock
            // else below for the plain spell and drew its shuffle. So this branch draws that same
            // shuffle first (handles() only reads), then asks the card's own chooser, which finds the
            // modes by API and draws nothing (the entwined copy, num == choices, never shuffled).
            // A Play-effect cast (Sunforger, Nathan Drake) never matches and keeps the stock chooser.
            if (num < choices.size()) {
                Collections.shuffle(choices, MyRandom.getRandom());
            }
            chosenList = SpecialCardAi.GrabTheReins.chooseModes(ai, sa, choices);
        } else {
            // only randomize if not all possible together
            if (num < choices.size()) {
                Collections.shuffle(choices, MyRandom.getRandom());
            }

            /*
             * The generic chooseOptionsAi uses canPlayAi() to determine good choices
             * which means most "bonus" effects like life-gain and random pumps will
             * usually not be chosen. This is designed to force the AI to only select
             * the best choice(s) since it does not actually know if it can pay for
             * "bonus" choices (eg. Entwine/Escalate).
             * chooseMultipleOptionsAi() uses "AILogic$Good" tags to manually identify
             * bonus choice(s) for the AI otherwise it might be too hard to ever fulfil
             * minimum choice requirements with canPlayAi() alone.
             */
            chosenList = min > 1 ? chooseMultipleOptionsAi(sa, choices, ai, min)
                    : chooseOptionsAi(sa, choices, ai, timingRight, num, min);
        }

        // Maestros Confluence: the stock chooser above can never fill the card's three slots
        // (its goad mode targets a player and GoadAi.checkApiLogic looks only for card targets),
        // so it always returns empty here. It still runs first, exactly as before, so every
        // random draw it makes stays in step with the stock engine; only then is the card's own
        // chooser asked. Every other charm takes exactly the path it took before. This assumes
        // the stock chooser never fills this card: a stock refill of chooseMultipleOptionsAi
        // under CanRepeatModes would bypass this gate and its floor.
        if (chosenList.isEmpty() && SpecialCardAi.MaestrosConfluence.handles(ai, sa)) {
            chosenList = SpecialCardAi.MaestrosConfluence.chooseModes(ai, sa, choices, num);
        }

        if (chosenList.isEmpty()) {
            if (timingRight) {
                // Set minimum choices for triggers where chooseMultipleOptionsAi() returns null
                chosenList = chooseOptionsAi(sa, choices, ai, true, num, min);
                if (chosenList.isEmpty() && min != 0) {
                    return new AiAbilityDecision(0, AiPlayDecision.CantPlayAi);
                }
            } else {
                return new AiAbilityDecision(0, AiPlayDecision.CantPlayAi);
            }
        }

        // store the choices so they'll get reused
        sa.setChosenList(chosenList);

        if (choiceForOpp) {
            return new AiAbilityDecision(0, AiPlayDecision.CantPlayAi);
        }

        if (sa.isSpell()) {
            // prebuild chain to improve cost calculation accuracy
            CharmEffect.chainAbilities(sa, chosenList);
        }

        if (chosenList.size() < num) {
            if (sa.isEntwine()) {
                return new AiAbilityDecision(0, AiPlayDecision.CostNotAcceptable);
            }
            // TODO return lower score since the SA wouldn't be used to its full effectiveness
        }

        return super.checkApiLogic(ai, sa);
    }

    private List<AbilitySub> chooseOptionsAi(SpellAbility sa, List<AbilitySub> choices, final Player ai, boolean isTrigger, int num, int min) {
        List<AbilitySub> chosen = Lists.newArrayList();
        AiController aic = ((PlayerControllerAi) ai.getController()).getAi();

        // TODO the AI doesn't know how to effectively handle repeated choices from CanRepeatModes yet.
        final int pawprintLimit = sa.hasParam("Pawprint") ? AbilityUtils.calculateAmount(sa.getHostCard(), sa.getParam("Pawprint"), sa) : 0;
        if (pawprintLimit > 0) {
            // try to pay for the more expensive subs first
            Collections.reverse(choices);
        }
        int pawprintAmount = 0;

        // First pass using standard canPlayAi() for good choices
        for (AbilitySub sub : choices) {
            handleDependentModes(sa, chosen, sub);
            sub.setActivatingPlayer(ai);
            // TODO instead of shuffling check all to sort by AiAbilityDecision rating
            if (SpellApiToAi.Converter.get(sub).canPlayWithSubs(ai, sub).willingToPlay() && canPayForAdditionalMode(sa, chosen, sub, ai)) {
                if (pawprintLimit > 0) {
                    int curPawprintAmount = AbilityUtils.calculateAmount(sub.getHostCard(), sub.getParamOrDefault("Pawprint", "0"), sub);
                    if (pawprintAmount + curPawprintAmount > pawprintLimit) {
                        continue;
                    }
                    pawprintAmount += curPawprintAmount;
                }
                chosen.add(sub);
                if (chosen.size() == num) {
                    // maximum choices reached
                    break;
                }
            }
        }
        if (isTrigger && chosen.size() < min) {
            // Second pass using doTrigger(false) to fulfill minimum choice
            choices.removeAll(chosen);
            for (AbilitySub sub : choices) {
                handleDependentModes(sa, chosen, sub);
                if (aic.doTrigger(sub, false)) {
                    chosen.add(sub);
                    if (chosen.size() == min) {
                        break;
                    }
                }
            }
            // Third pass using doTrigger(true) to force fill minimum choices
            if (chosen.size() < min) {
                choices.removeAll(chosen);
                for (AbilitySub sub : choices) {
                    handleDependentModes(sa, chosen, sub);
                    if (aic.doTrigger(sub, true)) {
                        chosen.add(sub);
                        if (chosen.size() == min) {
                            break;
                        }
                    }
                }
            }
        }
        if (!isTrigger && !chosen.isEmpty() && chosen.size() < num && min < num) {
            // Optional extra modes can be worth adding even when canPlaySa() is too strict for a standalone mode.
            choices.removeAll(chosen);
            for (AbilitySub sub : choices) {
                handleDependentModes(sa, chosen, sub);
                sub.setActivatingPlayer(ai);
                if (SpellApiToAi.Converter.get(sub).chkDrawbackWithSubs(ai, sub).willingToPlay()
                        && canPayForAdditionalMode(sa, chosen, sub, ai)) {
                    chosen.add(sub);
                    if (chosen.size() == num) {
                        break;
                    }
                }
            }
        }
        if (chosen.size() < min) {
            // not enough choices
            chosen.clear();
        }
        sa.setSubAbility(null);
        return chosen;
    }

    private List<AbilitySub> chooseTriskaidekaphobia(List<AbilitySub> choices, final Player ai) {
        List<AbilitySub> chosenList = Lists.newArrayList();
        if (choices == null || choices.isEmpty()) { return chosenList; }

        AbilitySub gain = choices.get(0);
        AbilitySub lose = choices.get(1);
        FCollection<Player> opponents = ai.getOpponents();

        boolean oppTainted = false;
        boolean allyTainted = ai.isCardInPlay("Tainted Remedy");
        final int aiLife = ai.getLife(); 

        //Check if Opponent controls Tainted Remedy
        for (Player p : opponents) {
            if (p.isCardInPlay("Tainted Remedy")) {
                oppTainted = true;
                break;
            }
        }
        // if ai or ally of ai does control Tainted Remedy, prefer gain life instead of lose
        if (!allyTainted) {
            for (Player p : ai.getAllies()) {
                if (p.isCardInPlay("Tainted Remedy")) {
                    allyTainted = true;
                    break;
                }
            }
        }
        
        if (!ai.canLoseLife() || ai.cantLose()) {
            // ai can't lose life, or can't lose the game, don't think about others
            chosenList.add(allyTainted ? gain : lose);
        } else if (oppTainted || ai.getGame().isCardInPlay("Rain of Gore")) {
            // Rain of Gore does negate lifegain, so don't benefit the others
            // same for if a opponent does control Tainted Remedy
            // but if ai can't gain life, the effects are negated
            chosenList.add(ai.canGainLife() ? lose : gain);
        } else if (ai.getGame().isCardInPlay("Sulfuric Vortex")) {
            // no life gain, but extra life loss.
            if (aiLife >= 17)
                chosenList.add(lose);
            // try to prevent to get to 13 with extra lose
            else if (aiLife < 13 || ((aiLife - 13) % 2) == 1) {
                chosenList.add(gain);
            } else {
                chosenList.add(lose);
            }
        } else if (ai.canGainLife() && aiLife <= 5) {
            // critical Life try to gain more
            chosenList.add(gain);
        } else if (!ai.canGainLife() && aiLife == 14) {
            // ai can't gain life, but try to avoid falling to 13
            // but if a opponent does control Tainted Remedy its irrelevant
            chosenList.add(oppTainted ? lose : gain);
        } else if (allyTainted) {
            // Tainted Remedy negation logic, try gain instead of lose
            // because negation does turn it into lose for opponents
            boolean oppCritical = false;
            // an opponent is Critical = 14, and can't gain life, try to lose life instead
            // but only if ai doesn't kill itself with that.
            if (aiLife != 14) {
                for (Player p : opponents) {
                    if (p.getLife() == 14 && !p.canGainLife() && p.canLoseLife()) {
                        oppCritical = true;
                        break;
                    }
                }
            }
            chosenList.add(aiLife == 12 || oppCritical ? lose : gain);
        } else {
            // normal logic, try to gain life if its critical
            boolean oppCritical = false;
            // an opponent is Critical = 12, and can gain life, try to gain life instead
            // but only if ai doesn't kill itself with that.
            if (aiLife != 12) {
                for (Player p : opponents) {
                    if (p.getLife() == 12 && p.canGainLife()) {
                        oppCritical = true;
                        break;
                    }
                }
            }
            chosenList.add(aiLife == 14 || aiLife <= 10 || oppCritical ? gain : lose);
        }
        return chosenList;
    }

    // Choice selection for charms that require multiple choices (e.g. Cryptic Command)
    private List<AbilitySub> chooseMultipleOptionsAi(SpellAbility sa, List<AbilitySub> choices, final Player ai, int min) {
        AbilitySub goodChoice = null;
        List<AbilitySub> chosen = Lists.newArrayList();
        AiController aic = ((PlayerControllerAi) ai.getController()).getAi();
        for (AbilitySub sub : choices) {
            handleDependentModes(sa, chosen, sub);
            sub.setActivatingPlayer(ai);
            // Assign generic good choice to fill up choices if necessary 
            if ("Good".equals(sub.getParam("AILogic")) && aic.doTrigger(sub, false)) {
                goodChoice = sub;
            } else if (SpellApiToAi.Converter.get(sub).canPlayWithSubs(ai, sub).willingToPlay()) {
                chosen.add(sub);
                if (chosen.size() == min) {
                    break; // enough choices
                }
            }
        }
        // Add generic good choice if one more choice is needed
        if (chosen.size() == min - 1 && goodChoice != null) {
            chosen.add(0, goodChoice);  // hack to make Dromoka's Command fight targets work
        }
        if (chosen.size() != min) {
            chosen.clear();
        }
        sa.setSubAbility(null);
        return chosen;
    }

    private void handleDependentModes(SpellAbility sa, List<AbilitySub> chosen, AbilitySub sub) {
        if (sub.hasParam("TargetUnique") && !chosen.isEmpty()) {
            // support "Each mode must target a different..."
            sa.setSubAbility(null);
            CharmEffect.chainAbilities(sa, chosen);
            sa.appendSubAbility(sub);
        }
    }

    private boolean canPayForAdditionalMode(SpellAbility sa, List<AbilitySub> chosen, AbilitySub sub, Player ai) {
        Card source = sa.getHostCard();
        if (!source.hasKeyword(Keyword.ESCALATE) && !source.hasKeyword(Keyword.SPREE) && !source.hasKeyword(Keyword.TIERED)) {
            return true;
        }
        try {
            List<AbilitySub> testModes = Lists.newArrayList(chosen);
            testModes.add(sub);
            sa.setSubAbility(null);
            CharmEffect.chainAbilities(sa, testModes);
            return ComputerUtilCost.canPayCost(sa, ai, false);
        } finally {
            sa.setSubAbility(null);
        }
    }

    @Override
    public Player chooseSinglePlayer(Player ai, SpellAbility sa, Iterable<Player> opponents, Map<String, Object> params) {
        return Aggregates.random(opponents);
    }

    @Override
    public List<OptionalCostValue> chooseOptionalCosts(Player payer, SpellAbility chosen, List<OptionalCostValue> optionalCostValues) {
        List<OptionalCostValue> chosenCosts = super.chooseOptionalCosts(payer, chosen, optionalCostValues);

        Optional<OptionalCostValue> entwine = chosenCosts.stream().filter(c -> c.getType() == OptionalCost.Entwine).findFirst();
        if (entwine.isPresent()) {
            SpellAbility entwined = GameActionUtil.addOptionalCosts(chosen, chosenCosts);
            if (!checkApiLogic(payer, entwined).willingToPlay()) {
                chosenCosts.remove(entwine.get());
            }
        }

        return chosenCosts;
    }

    @Override
    public AiAbilityDecision chkDrawbackWithSubs(Player aiPlayer, AbilitySub ab) {
        // choices were already targeted
        if (ab.getRootAbility().getChosenList() != null) {
            return new AiAbilityDecision(100, AiPlayDecision.WillPlay);
        }
        return super.chkDrawbackWithSubs(aiPlayer, ab);
    }

}
