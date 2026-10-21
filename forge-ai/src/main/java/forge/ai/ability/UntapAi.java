package forge.ai.ability;

import java.util.List;
import java.util.Map;

import forge.ai.AiAbilityDecision;
import forge.ai.AiPlayDecision;
import forge.ai.ComputerUtil;
import forge.ai.ComputerUtilAbility;
import forge.ai.ComputerUtilCard;
import forge.ai.ComputerUtilCombat;
import forge.ai.ComputerUtilCost;
import forge.ai.ComputerUtilMana;
import forge.ai.SpecialCardAi;
import forge.ai.SpellAbilityAi;
import forge.card.mana.ManaCostShard;
import forge.game.Game;
import forge.game.ability.AbilityUtils;
import forge.game.ability.ApiType;
import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.card.CardLists;
import forge.game.card.CardPredicates;
import forge.game.combat.Combat;
import forge.game.cost.Cost;
import forge.game.cost.CostPartMana;
import forge.game.cost.CostTap;
import forge.game.mana.ManaCostBeingPaid;
import forge.game.phase.PhaseHandler;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.player.PlayerCollection;
import forge.game.spellability.SpellAbility;
import forge.game.spellability.TargetRestrictions;
import forge.game.zone.ZoneType;
import forge.util.collect.FCollectionView;

public class UntapAi extends SpellAbilityAi {
    @Override
    protected boolean checkAiLogic(final Player ai, final SpellAbility sa, final String aiLogic) {
        if ("PoolExtraMana".equals(aiLogic)) {
            return doPoolExtraManaLogic(ai, sa);
        }
        if ("PreventCombatDamage".equals(aiLogic)) {
            return doPreventCombatDamageLogic(ai, sa);
            // In the future if you want to give Pseudo vigilance to a creature you attacked with
            // activate during your own during the end of combat step
        }

        return super.checkAiLogic(ai, sa, aiLogic);
    }

    @Override
    protected boolean willPayCosts(final Player payer, final SpellAbility sa, final Cost cost, final Card source) {
        if (!ComputerUtilCost.checkAddM1M1CounterCost(cost, source)) {
            return false;
        }

        return ComputerUtilCost.checkDiscardCost(payer, cost, source, sa);
    }

    @Override
    protected AiAbilityDecision checkApiLogic(Player ai, SpellAbility sa) {
        if (SpecialCardAi.ManifoldKey.NAME.equals(ComputerUtilAbility.getAbilitySourceName(sa))) {
            // Manifold Key's "{1}, {T}: Untap another target artifact." The card carried
            // AI:RemoveDeck:All, so AiController.getSpellAbilityToPlay stripped this ability for
            // every controller and the stock engine never evaluated it; behind the hint,
            // untapPrefTargeting below refuses a mana-costed untap of anything without UntapMe or
            // "doesn't untap", which would have left it dead. Every decline in the evaluator draws
            // no RNG. Only manifold_key.txt carries the name; every other Untap card takes exactly
            // the path it took before this branch.
            return SpecialCardAi.ManifoldKey.considerUntap(ai, sa);
        }
        if (SpecialCardAi.VizierOfTumblingSands.isTapUntap(sa)) {
            // Dead-card batch 3, row 42. Vizier of Tumbling Sands' "{T}: Untap another target
            // permanent." Its AI:RemoveDeck:All hint (dropped) kept A from ever evaluating it; behind
            // the hint, untapPrefTargeting below untaps our most expensive tapped permanent at the
            // first priority in any phase, so the Vizier would tap every opponent's upkeep and never
            // block. Keyed on the host's paper name (rule B3-3): a Mairsil grant or a copy keeps this
            // stock path. Every decline draws no RNG. The cycle trigger goes through doTriggerNoCost
            // and stays stock.
            return SpecialCardAi.VizierOfTumblingSands.considerUntap(ai, sa);
        }
        if (SpecialCardAi.CleverConjurer.isTapUntap(sa)) {
            // Dead-card batch 4, row 72 (with the Vizier's amendment above), part 72C-engine. Clever Conjurer's
            // sorcery-speed "{T}: Untap target permanent not named Clever Conjurer". Stock untapPrefTargeting
            // below, with a tapped Arcanis the Omnipotent, still took a pricier permanent (Nezahal) or, with only
            // creatures tapped, the best creature (a Kraken token). First our tapped draw engine
            // (SpecialCardAi.CleverConjurer.engine); else exactly the stock targeted path below. Keyed on the
            // host's paper name (rule B3-3). Draw-free: the engine scan reads state, and untapPrefTargeting
            // draws nothing; with no engine this is A's path and answer.
            final Card engine = SpecialCardAi.CleverConjurer.engine(ai, sa);
            if (engine != null) {
                sa.resetTargets();
                sa.getTargets().add(engine);
                return new AiAbilityDecision(100, AiPlayDecision.WillPlay);
            }
            if (untapPrefTargeting(ai, sa, false)) {
                // Part 72C-hold: the stock pick fires at the first main-phase priority anything of ours is
                // tapped, usually a land in Main 1, before Arcanis draws in Main 2 (DrawAi holds every draw back
                // to Main 2). While an untapped engine of ours will draw in our Main 2 with the hand we hold now
                // (SpecialCardAi.CleverConjurer.holdForDraw), the pick waits, so the Conjurer is spent on that
                // engine after its draw (72C-engine above). A veto after the draw-free stock pick; targets reset.
                if (SpecialCardAi.CleverConjurer.holdForDraw(ai, sa)) {
                    sa.resetTargets();
                    return new AiAbilityDecision(0, AiPlayDecision.AnotherTime);
                }
                return new AiAbilityDecision(100, AiPlayDecision.WillPlay);
            }
            return new AiAbilityDecision(0, AiPlayDecision.TargetingFailed);
        }
        if (SpecialCardAi.AphettoAlchemist.isOwnUntap(ai, sa)) {
            // Dead-card batch 4, row 74, part 74A. Aphetto Alchemist's "{T}: Untap target artifact or creature"
            // carries AI:RemoveDeck:All; G3's readmit puts it back only at the opponent's end step right before our
            // turn, on an empty stack, while a tapped creature or artifact of ours has a {T} draw that DrawAi takes
            // at once there (SpecialCardAi.AphettoAlchemist.tappedHolder). untapPrefTargeting below would untap our
            // most expensive tapped permanent at the first priority in any phase. Keyed on the paper name, owner
            // and controller (rule B3-3): a thief's Alchemist, a Clone, a Mairsil grant and a copy of the ability
            // keep this stock path. Draws nothing.
            return SpecialCardAi.AphettoAlchemist.considerUntap(ai, sa);
        }
        if (SpecialCardAi.Earthcraft.isOwnActivation(ai, sa)) {
            // Dead-card batch 4, row 41, part 41B. Earthcraft's "Tap an untapped creature you control: Untap target
            // basic land." keeps AI:RemoveDeck:All; G3's readmit puts it back only for the player who owns and
            // controls it, in our own main phase on an empty stack, and it is judged here under the readmit's own key
            // (rule B3-2): a thief's Earthcraft, a Mairsil grant, a copy and every other Untap card keep the stock
            // path below. untapPrefTargeting below would untap any tapped basic at every priority, tapping the
            // lowest-power body (a live mana dork: net 0) for mana nobody spends. Every decline before the payer
            // tests draws nothing.
            return SpecialCardAi.Earthcraft.considerUntap(ai, sa);
        }
        if (SpecialCardAi.ClockOfOmens.isOwnUntap(ai, sa)) {
            // Dead-card batch 4, row 45, part 45B1. Clock of Omens' "Tap two untapped artifacts you control: Untap
            // target artifact." keeps AI:RemoveDeck:All; G3's readmit puts it back only for the player who owns and
            // controls it, and it is judged here under the readmit's own key (rule B3-2): a thief's Clock, a Sculpting
            // Steel copy, a Mairsil grant and every other Untap card keep the stock path below. untapPrefTargeting
            // below churns it (tap two artifacts, untap the most expensive one: the hint-drop probe's eight
            // activations for nothing, Treasures and a copied Arcane Signet among them). It untaps only a Treasure
            // engine of ours (Magda, Brazen Outlaw's Dwarves), in our own main phase on an empty stack, at most nine
            // times a turn and below thirty Treasures. Every decline draws nothing.
            return SpecialCardAi.ClockOfOmens.considerUntap(ai, sa);
        }
        final Card source = sa.getHostCard();

        if (sa.usesTargeting()) {
            if (untapPrefTargeting(ai, sa, false)) {
                return new AiAbilityDecision(100, AiPlayDecision.WillPlay);
            }
            return new AiAbilityDecision(0, AiPlayDecision.TargetingFailed);
        }

        final List<Card> pDefined = AbilityUtils.getDefinedCards(source, sa.getParam("Defined"), sa);
        if (pDefined.isEmpty() || (pDefined.get(0).isTapped() && pDefined.get(0).getController() == ai)) {
            // If the defined card is tapped, or if there are no defined cards, we can play this ability
            return new AiAbilityDecision(100, AiPlayDecision.WillPlay);
        }
        return new AiAbilityDecision(0, AiPlayDecision.MissingNeededCards);
    }

    @Override
    protected AiAbilityDecision doTriggerNoCost(Player ai, SpellAbility sa, boolean mandatory) {
        if (!sa.usesTargeting()) {
            if (mandatory) {
                return new AiAbilityDecision(100, AiPlayDecision.WillPlay);
            }
            if ("Never".equals(sa.getParam("AILogic"))) {
                return new AiAbilityDecision(0, AiPlayDecision.CantPlayAi);
            }

            final List<Card> pDefined = AbilityUtils.getDefinedCards(sa.getHostCard(), sa.getParam("Defined"), sa);
            if (pDefined.isEmpty() || (pDefined.get(0).isTapped() && pDefined.get(0).getController() == ai)) {
                return new AiAbilityDecision(100, AiPlayDecision.WillPlay);
            }
            return new AiAbilityDecision(0, AiPlayDecision.MissingNeededCards);
        } else if (untapPrefTargeting(ai, sa, mandatory)) {
            return new AiAbilityDecision(100, AiPlayDecision.WillPlay);
        } else if (mandatory) {
            // not enough preferred targets, but mandatory so keep going:
            if (untapUnpreferredTargeting(sa, mandatory)) {
                return new AiAbilityDecision(50, AiPlayDecision.MandatoryPlay);
            }
            return new AiAbilityDecision(0, AiPlayDecision.CantPlayAi);
        }

        return new AiAbilityDecision(0, AiPlayDecision.CantPlayAi);
    }

    @Override
    public AiAbilityDecision chkDrawback(Player ai, SpellAbility sa) {
        if (!sa.usesTargeting()) {
            // who cares if its already untapped, it's only a subability?
        } else {
            if (!untapPrefTargeting(ai, sa, false)) {
                return new AiAbilityDecision(0, AiPlayDecision.CantPlayAi);
            }
        }

        return new AiAbilityDecision(100, AiPlayDecision.WillPlay);
    }

    /**
     * <p>
     * untapPrefTargeting.
     * </p>
     * 
     * @param sa
     *            a {@link forge.game.spellability.SpellAbility} object.
     * @param mandatory
     *            a boolean.
     * @return a boolean.
     */
    private static boolean untapPrefTargeting(final Player ai, final SpellAbility sa, final boolean mandatory) {
        final Card source = sa.getHostCard();

        if (alreadyAssignedTarget(sa)) {
            if (sa.getTargets().size() > 0) {
                // If we selected something lets assume its valid
                return true;
            }
        }
        sa.resetTargets();

        final PlayerCollection targetController;
        if (sa.isCurse() || (sa.getSubAbility() != null && sa.getSubAbility().getApi() == ApiType.GainControl)) {
            targetController = ai.getOpponents();
        } else {
            targetController = ai.getYourTeam();
        }

        CardCollection list = CardLists.getTargetableCards(targetController.getCardsIn(ZoneType.Battlefield), sa);

        if (!sa.isCurse()) {
            list = ComputerUtil.getSafeTargets(ai, sa, list);
        }

        if (list.isEmpty()) {
            return false;
        }

        // For some abilities, it may be worth to target even an untapped card if we're targeting mostly for the subability
        boolean targetUntapped = false;
        if (sa.getSubAbility() != null) {
            SpellAbility subSa = sa.getSubAbility();
            if (subSa.getApi() == ApiType.RemoveFromCombat && "RemoveBestAttacker".equals(subSa.getParam("AILogic"))) {
                targetUntapped = true;
                Combat combat = ai.getGame().getCombat();
                if (combat == null) {
                    return false;
                }
                list = CardLists.filter(list, c -> combat.isAttacking(c, ai));
                if (list.isEmpty()) {
                    return false;
                }
            }
        }

        CardCollection untapList = targetUntapped ? list : CardLists.filter(list, CardPredicates.TAPPED);
        // filter out enchantments and planeswalkers, their tapped state doesn't matter.
        final String[] tappablePermanents = {"Creature", "Land", "Artifact"};
        untapList = CardLists.getValidCards(untapList, tappablePermanents, source.getController(), source, sa);

        // Try to avoid potential infinite recursion,
        // e.g. Kiora's Follower untapping another Kiora's Follower and repeating infinitely
        if (sa.getPayCosts().hasOnlySpecificCostType(CostTap.class)) {
            CardCollection toRemove = new CardCollection();
            for (Card c : untapList) {
                for (SpellAbility ab : c.getAllSpellAbilities()) {
                    if (ab.getApi() == ApiType.Untap
                            && ab.getPayCosts().hasOnlySpecificCostType(CostTap.class)
                            && ab.canTarget(source)) {
                        toRemove.add(c);
                        break;
                    }
                }
            }
            untapList.removeAll(toRemove);
        }

        //try to exclude things that will already be untapped due to something on stack or because something is
        //already targeted in a parent or sub SA
        if (!sa.isTrigger() || mandatory) { // but if just confirming trigger no need to look for other targets and might still help anyway
            CardCollection toExclude = ComputerUtilAbility.getCardsTargetedWithApi(ai, untapList, sa, ApiType.Untap);
            untapList.removeAll(toExclude);
        }

        while (sa.canAddMoreTarget()) {
            Card choice = null;

            if (untapList.isEmpty()) {
                // Animate untapped lands (Koth of the Hammer)
                if (sa.getSubAbility() != null && sa.getSubAbility().getApi() == ApiType.Animate && !list.isEmpty()
                        && ai.getGame().getPhaseHandler().getPhase().isBefore(PhaseType.COMBAT_DECLARE_ATTACKERS)) {
                    choice = ComputerUtilCard.getWorstPermanentAI(list, false, false, false, false);
                } else if (!sa.isMinTargetChosen() || sa.isZeroTargets()) {
                    // check if the cost is acceptable anyway (e.g. Planeswalker +Loyalty)
                    if (ComputerUtil.activateForCost(sa, ai)) {
                        return true;
                    }
                    sa.resetTargets();
                    return false;
                } else {
                    // TODO is this good enough? for up to amounts?
                    break;
                }
            } else {
                choice = detectPriorityUntapTargets(untapList);

                if (choice == null) {
                    if (CardLists.getNotType(untapList, "Creature").isEmpty()) {
                        choice = ComputerUtilCard.getBestCreatureAI(untapList); // if only creatures take the best
                    } else if (!sa.getPayCosts().hasManaCost() || sa.isTrigger()
                            || "Always".equals(sa.getParam("AILogic"))) {
                        choice = ComputerUtilCard.getMostExpensivePermanentAI(untapList);
                    }
                }
            }

            if (choice == null) { // can't find anything left
                if (!sa.isMinTargetChosen() || sa.isZeroTargets()) {
                    sa.resetTargets();
                    return false;
                } else {
                    // TODO is this good enough? for up to amounts?
                    break;
                }
            }

            untapList.remove(choice);
            list.remove(choice);
            // TODO ComputerUtilCard.willUntap(ai, choice)
            sa.getTargets().add(choice);
        }
        return true;
    }

    /**
     * <p>
     * untapUnpreferredTargeting.
     * </p>
     *
     * @param sa
     *            a {@link forge.game.spellability.SpellAbility} object.
     * @param mandatory
     *            a boolean.
     * @return a boolean.
     */
    private boolean untapUnpreferredTargeting(final SpellAbility sa, final boolean mandatory) {
        final Card source = sa.getHostCard();
        final TargetRestrictions tgt = sa.getTargetRestrictions();

        CardCollection list = CardLists.getTargetableCards(source.getGame().getCardsIn(ZoneType.Battlefield), sa);

        // filter by enchantments and planeswalkers, their tapped state doesn't matter.
        final String[] tappablePermanents = { "Enchantment", "Planeswalker" };
        CardCollection tapList = CardLists.getValidCards(list, tappablePermanents, source.getController(), source, sa);

        if (untapTargetList(source, sa, mandatory, tapList)) {
            return true;
        }

        // try to just tap already tapped things
        tapList = CardLists.filter(list, CardPredicates.UNTAPPED);

        if (untapTargetList(source, sa, mandatory, tapList)) {
            return true;
        }

        // just tap whatever we can
        tapList = list;

        return untapTargetList(source, sa, mandatory, tapList);
    }

    private boolean untapTargetList(final Card source, final SpellAbility sa, final boolean mandatory,
            final CardCollection tapList) {
        tapList.removeAll(sa.getTargets().getTargetCards());

        if (tapList.isEmpty()) {
            return false;
        }

        while (sa.canAddMoreTarget()) {
            Card choice = null;

            if (tapList.isEmpty()) {
                if (sa.getTargets().size() < sa.getMinTargets() || sa.getTargets().size() == 0) {
                    if (!mandatory) {
                        sa.resetTargets();
                    }
                    return false;
                } else {
                    // TODO is this good enough? for up to amounts?
                    break;
                }
            }

            choice = ComputerUtilCard.getBestAI(tapList);

            if (choice == null) { // can't find anything left
                if (sa.getTargets().size() < sa.getMinTargets() || sa.getTargets().size() == 0) {
                    if (!mandatory) {
                        sa.resetTargets();
                    }
                    return false;
                } else {
                    // TODO is this good enough? for up to amounts?
                    break;
                }
            }

            tapList.remove(choice);
            sa.getTargets().add(choice);
        }

        return true;
    }

    @Override
    public Card chooseSingleCard(Player ai, SpellAbility sa, Iterable<Card> list, boolean isOptional, Player targetedPlayer, Map<String, Object> params) {
        CardCollection filteredList = CardLists.filterControlledBy(list, ai.getYourTeam());
        if (!filteredList.isEmpty()) {
            return ComputerUtilCard.getBestAI(filteredList);
        }
        if (isOptional) {
            return null;
        }
        return ComputerUtilCard.getWorstAI(list);
    }

    private static Card detectPriorityUntapTargets(final List<Card> untapList) {
        // See if there are cards that are *especially* worth untapping, like Time Vault
        for (Card c : untapList) {
            if ("True".equals(c.getSVar("UntapMe"))) {
                return c;
            }
        }

        // See if there's anything to untap that is tapped and that doesn't untap during the next untap step by itself
        CardCollection noAutoUntap = CardLists.filter(untapList, c -> !c.canUntap(c.getController(), true));
        if (!noAutoUntap.isEmpty()) {
            return ComputerUtilCard.getBestAI(noAutoUntap);
        }

        return null;
    }

    private boolean doPreventCombatDamageLogic(final Player ai, final SpellAbility sa) {
        // Only Maze of Ith and Maze of Shadows uses this. Feel free to use it aggressively.
        Game game = ai.getGame();
        sa.resetTargets();

        if (!game.getPhaseHandler().getPlayerTurn().isOpponentOf(ai)) {
            return false;
        }

        Combat activeCombat = game.getCombat();
        if (activeCombat == null) {
            return false;
        }

        CardCollection list = CardLists.getTargetableCards(activeCombat.getAttackers(), sa);
        list = CardLists.filter(list, c -> activeCombat.isAttacking(c, ai));

        if (list.isEmpty()) {
            return false;
        }

        if (game.getPhaseHandler().is(PhaseType.COMBAT_DECLARE_BLOCKERS)) {
            // Blockers already set. Are there any dangerous unblocked creatures? Sort by creature that will deal the most damage?
            Card card = ComputerUtilCombat.mostDangerousAttacker(list, ai, activeCombat, true);

            if (card == null) { return false; }

            sa.getTargets().add(card);
            return true;
        }

        return false;
    }

    private static boolean alreadyAssignedTarget(final SpellAbility sa) {
        if (sa.hasParam("AILogic")) {
            String aiLogic = sa.getParam("AILogic");
            return "PreventCombatDamage".equals(aiLogic);
        }
        return false;
    }

    private boolean doPoolExtraManaLogic(final Player ai, final SpellAbility sa) {
        final Card source = sa.getHostCard();
        final PhaseHandler ph = source.getGame().getPhaseHandler();
        final Game game = ai.getGame();

        if (source.isTapped()) {
            return true;
        }

        // Check if something is playable if we untap for an additional mana with this, then proceed
        CardCollection inHand = CardLists.filter(ai.getCardsIn(ZoneType.Hand), CardPredicates.NON_LANDS);
        // The AI is not very good at timing non-permanent spells this way, so filter them out
        // (it may actually be possible to enable this for sorceries, but that'll need some canPlay shenanigans)
        CardCollection playable = CardLists.filter(inHand, CardPredicates.PERMANENTS);

        CardCollection untappingCards = CardLists.filter(ai.getCardsIn(ZoneType.Battlefield), card -> {
            boolean hasUntapLandLogic = false;
            for (SpellAbility sa1 : card.getSpellAbilities()) {
                if ("PoolExtraMana".equals(sa1.getParam("AILogic"))) {
                    hasUntapLandLogic = true;
                    break;
                }
            }
            return hasUntapLandLogic && card.isUntapped();
        });

        // TODO: currently limited to Main 2, somehow improve to let the AI use this SA at other time?
        if (ph.is(PhaseType.MAIN2, ai)) {
            for (Card c : playable) {
                for (SpellAbility ab : c.getAllPossibleAbilities(ai, true)) {
                    if (!ab.isBasicSpell()) {
                        continue;
                    }
                    if (!ComputerUtilMana.hasEnoughManaSourcesToCast(ab, ai)) {
                        // TODO: Currently limited to predicting something that can be paid with any color,
                        // can ideally be improved to work by color.
                        ManaCostBeingPaid reduced = new ManaCostBeingPaid(ab.getPayCosts().getCostMana().getManaCostFor(ab));
                        reduced.decreaseShard(ManaCostShard.GENERIC, untappingCards.size());
                        if (ComputerUtilMana.canPayManaCost(reduced, ab, ai, false)) {
                            CardCollection manaLandsTapped = CardLists.filter(ai.getCardsIn(ZoneType.Battlefield),
                                    CardPredicates.LANDS_PRODUCING_MANA, CardPredicates.TAPPED);
                            manaLandsTapped = CardLists.getValidCards(manaLandsTapped, sa.getParam("ValidTgts"), ai, source, null);

                            if (!manaLandsTapped.isEmpty()) {
                                // already have a tapped land, so agree to proceed with untapping it
                                return true;
                            }

                            // pool one additional mana by tapping a land to try to ramp to something
                            CardCollection manaLands = CardLists.filter(ai.getCardsIn(ZoneType.Battlefield),
                                    CardPredicates.LANDS_PRODUCING_MANA, CardPredicates.CAN_TAP);
                            manaLands = CardLists.getValidCards(manaLands, sa.getParam("ValidTgts"), ai, source, null);

                            if (manaLands.isEmpty()) {
                                // nothing to untap
                                return false;
                            }

                            Card landToPool = manaLands.getFirst();
                            SpellAbility manaAb = landToPool.getManaAbilities().getFirst();

                            ComputerUtil.playNoStack(ai, manaAb, game, false);

                            return true;
                        }
                    }
                }
            }
        }

        // no harm in doing this past declare blockers during the opponent's turn and right before our turn,
        // maybe we'll serendipitously untap into something like a removal spell or burn spell that'll help
        return ph.getNextTurn() == ai
                && (ph.is(PhaseType.COMBAT_DECLARE_BLOCKERS) || ph.getPhase().isAfter(PhaseType.COMBAT_DECLARE_BLOCKERS));
    }

    @Override
    public boolean willPayUnlessCost(Player payer, SpellAbility sa, Cost cost, boolean alreadyPaid, FCollectionView<Player> payers) {
        // Paralyze effects
        if (sa.hasParam("UnlessSwitched")) {
            final Card host = sa.getHostCard();
            final Game game = host.getGame();
            for (Card card : AbilityUtils.getDefinedCards(host, null, sa)) {
                final Card gameCard = game.getCardState(card, null);
                if (gameCard == null
                        || !gameCard.isInPlay() // not in play
                        || gameCard.isUntapped() // already untapped
                        ) {
                    return false;
                }

                // if the ManaCost would cost more than the creatures CMC, it is not worth it
                CostPartMana mana = cost.getCostMana();
                if (mana != null && mana.getManaCostFor(sa).getCMC() > card.getCMC()) {
                    return false;
                }
            }
        }

        return super.willPayUnlessCost(payer, sa, cost, alreadyPaid, payers);
    }
}
