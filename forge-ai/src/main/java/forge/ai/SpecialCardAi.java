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
package forge.ai;

import com.google.common.collect.Lists;
import com.google.common.collect.Table;
import forge.StaticData;
import forge.ai.ability.AnimateAi;
import forge.ai.ability.FightAi;
import forge.ai.ability.TokenAi;
import forge.card.CardFacePredicates;
import forge.card.ColorSet;
import forge.card.ICardFace;
import forge.card.MagicColor;
import forge.card.mana.ManaCost;
import forge.game.Game;
import forge.game.GameEntity;
import forge.game.GameEntityCounterTable;
import forge.game.GameObject;
import forge.game.GameType;
import forge.game.ability.AbilityUtils;
import forge.game.ability.ApiType;
import forge.game.card.*;
import forge.game.combat.Combat;
import forge.game.combat.CombatUtil;
import forge.game.combat.GlobalAttackRestrictions;
import forge.game.cost.CostDiscard;
import forge.game.cost.CostExile;
import forge.game.cost.CostPart;
import forge.game.cost.CostPartMana;
import forge.game.cost.CostRemoveAnyCounter;
import forge.game.cost.CostReveal;
import forge.game.cost.CostSacrifice;
import forge.game.cost.CostTap;
import forge.game.cost.PaymentDecision;
import forge.game.keyword.Keyword;
import forge.game.mana.ManaCostBeingPaid;
import forge.game.phase.PhaseHandler;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.player.PlayerCollection;
import forge.game.player.PlayerPredicates;
import forge.game.replacement.ReplacementEffect;
import forge.game.replacement.ReplacementLayer;
import forge.game.replacement.ReplacementType;
import forge.game.spellability.AbilitySub;
import forge.game.spellability.SpellAbility;
import forge.game.spellability.SpellAbilityPredicates;
import forge.game.spellability.SpellAbilityStackInstance;
import forge.game.spellability.SpellPermanent;
import forge.game.staticability.StaticAbility;
import forge.game.staticability.StaticAbilityCantDraw;
import forge.game.staticability.StaticAbilityFlipCoinMod;
import forge.game.staticability.StaticAbilityMode;
import forge.game.trigger.Trigger;
import forge.game.trigger.TriggerType;
import forge.game.zone.ZoneType;
import forge.item.PaperCard;
import forge.util.Aggregates;
import forge.util.FileSection;
import forge.util.IterableUtil;
import forge.util.MyRandom;
import forge.util.TextUtil;
import org.apache.commons.lang3.tuple.Pair;

import java.util.*;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Special logic for individual cards
 * <p>
 * Specific methods for each card that requires special handling are stored in inner classes
 * Each class should have a name based on the name of the card and ideally preceded with a
 * single-line comment with the full English card name to make searching for them easier.
 * <p>
 * Class methods should return "true" if they are successful and have completed their task in full,
 * otherwise should return "false" to signal that the AI should not use the card under current
 * circumstances. A good convention to follow is to call the method "consider" if it's the only
 * method necessary, or considerXXXX if several methods do different tasks, and use at least two
 * mandatory parameters (Player ai, SpellAbility sa, in this order) and, if necessary, additional
 * parameters later. Methods that perform utility tasks and return a certain value for further
 * processing should be called getXXXX. If they take Player and SpellAbility parameters, it is
 * good practice to put them in the same order as for considerXXXX methods (Player ai, SpellAbility
 * sa, followed by any additional parameters necessary).
 * <p>
 * If this class ends up being busy, consider splitting it into individual classes, each in its
 * own file, inside its own package, for example, forge.ai.cards.
 */
public class SpecialCardAi {

    // Archangel of Strife
    // "As this enters, each player chooses war or peace." Every AI chooser takes the first choice
    // (ChooseGenericAi.chooseSingleSpellAbility -> War), and a human opponent is modelled the same
    // way, so the cast hands every opponent's creatures +3/+0 along with ours. The body is always
    // worth its seven mana on the unchanged creature path; the one self-harming moment is when that
    // symmetric +3 turns the opponents' next swing into a lethal one. Blockers are only our creatures
    // that will be untapped on their turn (ours do not untap first; before our own attack, any that
    // can attack is assumed to), matched greedily per attacker with the engine's own block legality
    // (flying, reach, menace, can't-be-blocked), the Archangel itself as one more flier. Decline iff
    // that swing reaches our life and exceeds the swing we face without the cast. Reads game state
    // only: no random draw, no targets, no card mutation (also asked from getPossibleETBCounters).
    public static class ArchangelOfStrife {
        public static final int WAR_POWER = 3;

        public static boolean consider(final Player ai, final SpellAbility sa) {
            if (ai.cantLose() || ai.cantLoseForZeroOrLessLife() || !ai.canLoseLife()) {
                return true;
            }
            final int life = ai.getLife();
            final List<List<Card>> attackers = new ArrayList<>();
            final Map<Card, Integer> nowDmg = new HashMap<>();
            final Map<Card, Integer> warDmg = new HashMap<>();
            int warTotal = 0;
            for (final Player opp : ai.getOpponents()) {
                final List<Card> atts = new ArrayList<>();
                for (final Card att : opp.getCreaturesInPlay()) {
                    if (!ComputerUtilCombat.canAttackNextTurn(att, ai)) {
                        continue;
                    }
                    final int dmg = ComputerUtilCombat.damageIfUnblocked(att, ai, null, false);
                    final int war = dmg + WAR_POWER * (att.hasDoubleStrike() ? 2 : 1);
                    atts.add(att);
                    nowDmg.put(att, dmg);
                    warDmg.put(att, war);
                    warTotal += war;
                }
                attackers.add(atts);
            }
            // cheap exit: even with no block at all the War swing is not lethal
            if (warTotal < life) {
                return true;
            }

            final PhaseHandler ph = ai.getGame().getPhaseHandler();
            final boolean beforeOurAttack = ph.isPlayerTurn(ai) && ph.getPhase() != null
                    && ph.getPhase().isBefore(PhaseType.COMBAT_DECLARE_ATTACKERS);
            final List<Card> blockers = CardLists.filter(ai.getCreaturesInPlay(), c -> CombatUtil.canBlock(c)
                    && (!beforeOurAttack || c.hasKeyword(Keyword.VIGILANCE) || !CombatUtil.canAttack(c)));

            int before = 0, after = 0;
            for (final List<Card> atts : attackers) {
                before += unblocked(atts, nowDmg, blockers, null, ai);
                after += unblocked(atts, warDmg, blockers, sa.getHostCard(), ai);
            }
            // floor: the War buff must not create (or deepen) a lethal board
            return !(after >= life && after > before);
        }

        // damage that gets through when the biggest hitters are blocked first; the least capable
        // blockers are spent first, so a flier or reach blocker is kept for evasive attackers
        private static int unblocked(final List<Card> atts, final Map<Card, Integer> dmg,
                final List<Card> blockers, final Card extraBlocker, final Player ai) {
            final List<Card> order = new ArrayList<>(atts);
            order.sort((a, b) -> dmg.get(b) - dmg.get(a));
            final List<Card> free = new ArrayList<>(blockers);
            free.sort(Comparator.comparingInt(c -> c.hasKeyword(Keyword.FLYING) || c.hasKeyword(Keyword.REACH) ? 1 : 0));
            if (extraBlocker != null) {
                free.add(extraBlocker);
            }
            int sum = 0;
            for (final Card att : order) {
                final int need = Math.max(1, CombatUtil.getMinNumBlockersForAttacker(att, ai));
                final List<Card> pick = new ArrayList<>();
                for (final Card b : free) {
                    if (pick.size() < need && CombatUtil.canBlock(att, b, true)) {
                        pick.add(b);
                    }
                }
                if (pick.size() >= need) {
                    free.removeAll(pick);
                } else {
                    sum += dmg.get(att);
                }
            }
            return sum;
        }
    }

    // Arena and Magus of the Arena
    public static class Arena {
        public static AiAbilityDecision consider(final Player ai, final SpellAbility sa) {
            final Game game = ai.getGame();

            // TODO This is basically removal, so we may want to play this at other times
            if (!game.getPhaseHandler().is(PhaseType.END_OF_TURN) || game.getPhaseHandler().getNextTurn() != ai) {
                return new AiAbilityDecision(0, AiPlayDecision.WaitForEndOfTurn);
            }

            CardCollection aiCreatures = ai.getCreaturesInPlay();
            if (aiCreatures.isEmpty()) {
                return new AiAbilityDecision(0, AiPlayDecision.MissingNeededCards);
            }

            for (Player opp : ai.getOpponents()) {
                CardCollection oppCreatures = opp.getCreaturesInPlay();
                if (oppCreatures.isEmpty()) {
                    continue;
                }

                for (Card aiCreature : aiCreatures) {
                    boolean canKillAll = true;
                    for (Card oppCreature : oppCreatures) {
                        if (FightAi.canKill(oppCreature, aiCreature, 0)) {
                            canKillAll = false;
                            break;
                        }
                        if (!FightAi.canKill(aiCreature, oppCreature, 0)) {
                            canKillAll = false;
                            break;
                        }
                    }
                    if (canKillAll) {
                        sa.getTargets().clear();
                        sa.getTargets().add(aiCreature);
                        return new AiAbilityDecision(100, AiPlayDecision.Removal);
                    }
                }
            }
            return new AiAbilityDecision(0, AiPlayDecision.CantPlayAi);
        }
    }

    // Biomantic Mastery
    // Draw a card for each creature target player controls, then draw a card for each creature
    // ANOTHER target player controls. Both draws go to us (Defined$ You); the two targets only
    // measure the count, so target the two players with the most creatures. Cast only when seven
    // mana buys real, keepable cards: no opposing draw thief or draw punisher sees the draws, the
    // draw that actually arrives (after draw-limit statics) is at least MIN_TOTAL_DRAW, at least
    // MIN_KEPT of it survives cleanup's discard to hand size, and LIBRARY_MARGIN cards stay behind.
    public static class BiomanticMastery {
        public static final int MIN_TOTAL_DRAW = 4;  // seven mana plus the card itself: net +3 or better
        public static final int MIN_KEPT = 3;        // drawn cards that fit under the maximum hand size
        public static final int LIBRARY_MARGIN = 10; // cards left in the library after the draw

        public static AiAbilityDecision consider(final Player ai, final SpellAbility sa) {
            final AbilitySub second = sa.getSubAbility();
            if (!sa.usesTargeting() || second == null || second.getApi() != ApiType.Draw || !second.usesTargeting()) {
                return new AiAbilityDecision(0, AiPlayDecision.CantPlayAi);
            }
            sa.resetTargets();
            second.resetTargets();

            final Card host = sa.getHostCard();
            if (drawIsStolenOrPunished(ai, host)) {
                return new AiAbilityDecision(0, AiPlayDecision.CantPlayAi);
            }

            final List<Player> ranked = Lists.newArrayList(ai.getGame().getPlayers().filter(PlayerPredicates.isTargetableBy(sa)));
            if (ranked.size() < 2) {
                return new AiAbilityDecision(0, AiPlayDecision.TargetingFailed); // e.g. a hexproof opponent
            }
            ranked.sort((p1, p2) -> Integer.compare(p2.getCreaturesInPlay().size(), p1.getCreaturesInPlay().size()));
            sa.getTargets().add(ranked.get(0));
            if (!second.canTarget(ranked.get(1))) {
                sa.resetTargets();
                return new AiAbilityDecision(0, AiPlayDecision.TargetingFailed);
            }
            second.getTargets().add(ranked.get(1));

            // the engine's own X for each half, now that each half has its target
            final int total = AbilityUtils.calculateAmount(host, sa.getParam("NumCards"), sa)
                    + AbilityUtils.calculateAmount(host, second.getParam("NumCards"), second);
            // draw-limit statics (Narset, Parter of Veils; Leovold) cap what actually arrives
            final int effective = StaticAbilityCantDraw.canDrawAmount(ai, total);
            final int handAfterCast = ai.getCardsIn(ZoneType.Hand).size() - (host.isInZone(ZoneType.Hand) ? 1 : 0);
            final int kept = ai.isUnlimitedHandSize() ? effective
                    : Math.min(effective, ai.getMaxHandSize() - handAfterCast);
            final int library = ai.getCardsIn(ZoneType.Library).size();

            if (effective < MIN_TOTAL_DRAW || kept < MIN_KEPT || library - effective < LIBRARY_MARGIN) {
                sa.resetTargets();
                second.resetTargets();
                return new AiAbilityDecision(0, AiPlayDecision.CantPlayAi);
            }
            return new AiAbilityDecision(100, AiPlayDecision.WillPlay);
        }

        // An opposing draw thief (Notion Thief, Hullbreacher, Alms Collector) or draw punisher
        // (Sheoldred, the Apocalypse; Consecrated Sphinx; Orcish Bowmasters; Spiteful Visions) that
        // would see our draws, tested the way ReplaceDraw/ReplaceDrawCards.canReplace and
        // TriggerDrawn.performTest test (an absent param matches). The host stands in for the drawn
        // cards: it is owned and controlled by us, so Card.OppOwn matches and an opponent's own
        // Card.YouCtrl draw trigger (Chasm Skulker) does not.
        private static boolean drawIsStolenOrPunished(final Player ai, final Card host) {
            for (final Card c : ai.getGame().getCardsIn(Arrays.asList(ZoneType.Battlefield, ZoneType.Command))) {
                final Player controller = c.getController();
                if (controller == null || controller.equals(ai)) {
                    continue;
                }
                for (final ReplacementEffect re : c.getReplacementEffects()) {
                    if ((re.getMode() == ReplacementType.Draw || re.getMode() == ReplacementType.DrawCards)
                            && re.matchesValidParam("ValidPlayer", ai)) {
                        return true;
                    }
                }
                for (final Trigger t : c.getTriggers()) {
                    if (t.getMode() == TriggerType.Drawn && t.matchesValidParam("ValidPlayer", ai)
                            && t.matchesValidParam("ValidCard", host)) {
                        return true;
                    }
                }
            }
            return false;
        }
    }

    // Black Lotus and Lotus Bloom
    public static class BlackLotus {
        public static boolean consider(final Player ai, final SpellAbility sa, final ManaCostBeingPaid cost) {
            CardCollection manaSources = ComputerUtilMana.getAvailableManaSources(ai, true);
            int numManaSrcs = manaSources.size();

            CardCollection allCards = CardLists.filter(ai.getAllCards(), Arrays.asList(CardPredicates.NON_TOKEN,
                    CardPredicates.NON_LANDS, CardPredicates.isOwner(ai)));

            int numHighCMC = CardLists.count(allCards, CardPredicates.greaterCMC(5));
            int numLowCMC = CardLists.count(allCards, CardPredicates.lessCMC(3));

            boolean isLowCMCDeck = numHighCMC <= 6 && numLowCMC >= 25;

            int minCMC = isLowCMCDeck ? 3 : 4; // probably not worth wasting a lotus on a low-CMC spell (<4 CMC), except in low-CMC decks, where 3 CMC may be fine
            int paidCMC = cost.getConvertedManaCost();
            if (paidCMC < minCMC) {
                // if it's a CMC 3 spell and we're more than one mana source short for it, might be worth it anyway
                return paidCMC == 3 && numManaSrcs < 3;
            }

            return true;
        }
    }

    // Borderland Explorer
    // "When this enters, each player may discard a card. Each player who discarded a card this
    // way may search their library for a basic land card, reveal it, put it into their hand."
    // ChooseGenericAi asks once per AI chooser at resolution (the caster and every AI opponent).
    // Discard only when the card the resolution's own picker takes is card-neutral to lose: a
    // land traded for a basic (a nonbasic only while another land stays in hand), or a card that
    // wants the graveyard (DiscardMe; DiscardMeByOpp when an opponent's Explorer asks). A castable
    // spell is never traded for a land, and with no basic sure to be fetched the answer is No.
    public static class BorderlandExplorer {
        // Pre-cast ETB dry run (ChooseGenericAi.checkAiLogic; the real trigger is mandatory and
        // never asks). The choice costs its controller nothing, but approving it lets the cast go
        // on to ComputerUtilCost.canPayCost, whose shard payment rolls MyRandom in
        // ComputerUtilMana.isManaSourceReserved - a roll the stock BadEtbEffects refusal never
        // reached. So approve only when untapped mana covers the mana value and a green source is
        // there for the pip: an unaffordable window stays refused exactly as before, drawing nothing.
        public static boolean considerEtb(final Player ai, final SpellAbility sa) {
            final Card host = sa.getHostCard();
            return host != null
                    && ComputerUtilMana.getAvailableManaEstimate(ai, true) >= host.getCMC() // untapped only, RNG-free
                    && hasGreenSources(ai, host);
        }

        // Floating green plus untapped sources whose printed production could be green (the
        // Crackling Spellslinger red check, for G). Reads only the Produced text, never mana(sa).
        private static boolean hasGreenSources(final Player ai, final Card host) {
            final ManaCost cost = host.getManaCost();
            final int needed = cost == null ? 0 : cost.getShardCount(forge.card.mana.ManaCostShard.GREEN);
            int green = ai.getManaPool().getAmountOfColor(MagicColor.GREEN);
            for (final Card src : ai.getCardsIn(ZoneType.Battlefield)) {
                if (green >= needed) {
                    break;
                }
                for (final SpellAbility ma : src.getManaAbilities()) {
                    ma.setActivatingPlayer(ai);
                    if (ma.getManaPart() == null || !ma.canPlay()) {
                        continue;
                    }
                    final String produced = ma.getManaPart().getOrigProduced();
                    if (produced.contains("G") || produced.contains("Any") || produced.contains("Chosen")
                            || produced.startsWith("Combo")) {
                        green++;
                        break;
                    }
                }
            }
            return green >= needed;
        }

        public static SpellAbility chooseDiscardOrNo(final Player chooser, final SpellAbility sa,
                                                     final List<SpellAbility> spells) {
            SpellAbility discard = null, no = null;
            for (final SpellAbility sp : spells) {
                if (sp.hasParam("NoteCardsFor")) {
                    discard = sp;
                } else {
                    no = sp;
                }
            }
            if (discard == null || no == null) {
                return spells.get(0); // unexpected script shape: stock behaviour
            }
            return wantsToDiscard(chooser, sa) ? discard : no;
        }

        static boolean wantsToDiscard(final Player chooser, final SpellAbility sa) {
            final CardCollectionView hand = chooser.getCardsIn(ZoneType.Hand);
            if (hand.isEmpty() || !(chooser.getController() instanceof PlayerControllerAi)) {
                return false;
            }
            final SpellAbility discardSa = sa.getSubAbility();                                  // DBDiscard
            final SpellAbility searchSa = discardSa == null ? null : discardSa.getSubAbility(); // DBSearch
            if (discardSa == null || searchSa == null
                    || !chooser.canDiscardBy(discardSa, true)
                    || !chooser.canSearchLibraryWith(searchSa, chooser)
                    || chooser.hasKeyword("LimitSearchLibrary") // Aven Mindcensor: the top cards may hold no basic
                    || !chooser.getCardsIn(ZoneType.Library).anyMatch(Card::isBasicLand)) {
                return false; // no basic sure to be fetched: the discard would be pure card loss
            }
            // The picker below draws RNG in two places, and each would hand this floor a card it
            // refuses, so answer No before calling it (the prediction stays RNG-free and equal to
            // the real pick): a hand of nothing but DoNotDiscardIfAble cards (its last-resort
            // Aggregates.random), and two or more flashback/escape/disturb cards (the DiscardCost
            // roll among them in ComputerUtil.getCardPreference). The second also declines the
            // rare such hand where a DiscardMe card or a land-rich land pick would have come first.
            if (hand.allMatch(c -> c.hasSVar("DoNotDiscardIfAble"))
                    || CardLists.count(hand, c -> c.hasKeyword(Keyword.FLASHBACK) || c.hasKeyword(Keyword.ESCAPE)
                            || c.hasKeyword(Keyword.DISTURB)) >= 2) {
                return false;
            }
            // Predict the exact card the resolution's picker takes: DiscardEffect (Mode$ TgtChoose,
            // the chooser discards) -> PlayerControllerAi.chooseCardsToDiscardFrom -> this same
            // method with this same SpellAbility, on the chooser's hand in hand order. A copy, since
            // the picker removes from its list; no hand changes between this choice and the discard.
            final AiController aic = ((PlayerControllerAi) chooser.getController()).getAi();
            final CardCollection pick = aic.getCardsToDiscard(1, 1, new CardCollection(hand), discardSa);
            if (pick == null || pick.isEmpty()) {
                return false;
            }
            final Card c = pick.getFirst();
            if (c.hasSVar("DoNotDiscardIfAble")) {
                return false;
            }
            if (c.isLand()) {
                // a basic for a basic; a nonbasic (Command Tower, a bounce land) only while another land stays in hand
                return c.isBasicLand() || CardLists.count(hand, CardPredicates.LANDS) >= 2;
            }
            final Player activator = sa.getActivatingPlayer();
            return c.hasSVar("DiscardMe")
                    || (c.hasSVar("DiscardMeByOpp") && activator != null && activator.isOpponentOf(chooser));
        }
    }

    // Borrowing 100,000 Arrows (and Theft of Dreams, the identical script)
    // Draw a card for each tapped creature target opponent controls. We draw (Defined$ You); the
    // target only sizes X, so target the opponent with the most tapped creatures, sizing each one
    // through the engine's own X. Cast only when the draw leaves LIBRARY_MARGIN cards behind, at
    // least MIN_KEPT of it survives cleanup's discard to hand size, and no draw thief or draw
    // punisher at the table (ours included: an Ob Nixilis emblem can be ours) sees the draws.
    public static class BorrowingArrows {
        public static final int MIN_KEPT = 2;        // the spell itself is a card: 2 kept = +1 card
        public static final int LIBRARY_MARGIN = 3;  // DrawAi's own margin

        public static AiAbilityDecision consider(final Player ai, final SpellAbility sa) {
            final Game game = ai.getGame();
            final Card source = sa.getHostCard();
            sa.resetTargets();
            // draw-limit statics (Narset, Parter of Veils; Spirit of the Labyrinth) after our draw step
            if (!ai.canDrawAmount(MIN_KEPT)) {
                return new AiAbilityDecision(0, AiPlayDecision.CantPlayAi);
            }

            // size the draw per opponent exactly as resolution will: set the target, evaluate
            // NumCards (Count$Valid Creature.tapped+TargetedPlayerCtrl), clear
            Player best = null;
            int bestN = 0;
            for (final Player opp : ai.getOpponents()) {
                if (!sa.canTarget(opp)) {
                    continue; // hexproof / shroud players
                }
                sa.getTargets().add(opp);
                final int n = AbilityUtils.calculateAmount(source, sa.getParam("NumCards"), sa);
                sa.resetTargets();
                if (n > bestN) {
                    best = opp;
                    bestN = n;
                }
            }
            if (best == null) {
                return new AiAbilityDecision(0, AiPlayDecision.TargetingFailed);
            }

            // don't deck ourselves (DrawAi's library-3 margin)
            final CardCollectionView library = ai.getCardsIn(ZoneType.Library);
            if (bestN >= library.size() - LIBRARY_MARGIN && !ai.isCardInPlay("Laboratory Maniac")) {
                return new AiAbilityDecision(0, AiPlayDecision.CantPlayAi);
            }

            // count only cards we keep past our own cleanup (the sorcery leaves the hand when cast)
            int kept = bestN;
            if (!ai.isUnlimitedHandSize()) {
                final int handAfterCast = ai.getCardsIn(ZoneType.Hand).size() - (source.isInZone(ZoneType.Hand) ? 1 : 0);
                kept = Math.min(bestN, Math.max(0, ai.getMaxHandSize() - handAfterCast));
            }
            if (kept < MIN_KEPT) {
                return new AiAbilityDecision(0, AiPlayDecision.CantPlayAi);
            }

            // the top card stands in for the drawn cards; an empty library gets here only with Laboratory Maniac
            final Card probe = library.isEmpty() ? source : library.getFirst();
            if (drawIsStolenOrPunished(ai, game, probe)) {
                return new AiAbilityDecision(0, AiPlayDecision.CantPlayAi);
            }

            sa.getTargets().add(best);
            return new AiAbilityDecision(100, AiPlayDecision.WillPlay);
        }

        // A draw thief (Notion Thief, Hullbreacher, Chains of Mephistopheles; Alms Collector is
        // Event$ DrawCards) or draw punisher (Fate Unraveler, Nekusar, Spiteful Visions, Orcish
        // Bowmasters, an Ob Nixilis Reignited emblem) on the battlefield or in the Command zone, under
        // ANY controller, that works where it sits (zonesCheck: a commander waiting in the Command zone
        // does not count) and whose ValidPlayer/ValidCard match us and our drawn card, tested the way
        // ReplaceDraw/ReplaceDrawCards.canReplace and TriggerDrawn.performTest test (an absent param
        // matches). An opponent's own Card.YouOwn draw trigger (Psychosis Crawler) does not match.
        private static boolean drawIsStolenOrPunished(final Player ai, final Game game, final Card probe) {
            for (final Card c : game.getCardsIn(Arrays.asList(ZoneType.Battlefield, ZoneType.Command))) {
                for (final Trigger t : c.getTriggers()) {
                    if (t.getMode() == TriggerType.Drawn && t.zonesCheck(game.getZoneOf(c)) && t.requirementsCheck(game)
                            && t.matchesValidParam("ValidCard", probe) && t.matchesValidParam("ValidPlayer", ai)) {
                        return true;
                    }
                }
                for (final ReplacementEffect re : c.getReplacementEffects()) {
                    if ((re.getMode() == ReplacementType.Draw || re.getMode() == ReplacementType.DrawCards)
                            && re.zonesCheck(game.getZoneOf(c)) && re.requirementsCheck(game)
                            && re.matchesValidParam("ValidPlayer", ai)) {
                        return true;
                    }
                }
            }
            return false;
        }
    }

    // Brain in a Jar
    public static class BrainInAJar {
        public static boolean consider(final Player ai, final SpellAbility sa) {
            final Card source = sa.getHostCard();

            int counterNum = source.getCounters(CounterEnumType.CHARGE);
            // no need for logic
            if (counterNum == 0) {
                return false;
            }
            int libsize = ai.getCardsIn(ZoneType.Library).size();

            final CardCollection hand = CardLists.filter(ai.getCardsIn(ZoneType.Hand),
                    CardPredicates.INSTANTS_AND_SORCERIES);
            if (!hand.isEmpty()) {
                // has spell that can be cast in hand with put ability
                if (hand.anyMatch(CardPredicates.hasCMC(counterNum + 1))) {
                    return false;
                }
                // has spell that can be cast if one counter is removed
                if (hand.anyMatch(CardPredicates.hasCMC(counterNum))) {
                    sa.setXManaCostPaid(1);
                    return true;
                }
            }
            final CardCollection library = CardLists.filter(ai.getCardsIn(ZoneType.Library),
                    CardPredicates.INSTANTS_AND_SORCERIES);
            if (!library.isEmpty()) {
                // get max cmc of instant or sorceries in the library
                int maxCMC = 0;
                for (final Card c : library) {
                    int v = c.getCMC();
                    if (c.isSplitCard()) {
                        v = Math.max(c.getCMC(Card.SplitCMCMode.LeftSplitCMC), c.getCMC(Card.SplitCMCMode.RightSplitCMC));
                    }
                    if (v > maxCMC) {
                        maxCMC = v;
                    }
                }
                // there is a spell with more CMC, no need to remove counter
                if (counterNum + 1 < maxCMC) {
                    return false;
                }
                int maxToRemove = counterNum - maxCMC + 1;
                // no Scry 0, even if its caught from later stuff
                if (maxToRemove <= 0) {
                    return false;
                }
                sa.setXManaCostPaid(maxToRemove);
            } else {
                // no Instant or Sorceries anymore, just scry
                sa.setXManaCostPaid(Math.min(counterNum, libsize));
            }
            return true;
        }
    }

    // Brokers Confluence
    // "Choose three. You may choose the same mode more than once." CharmAi's multi-mode picker
    // (chooseMultipleOptionsAi) ignores CanRepeatModes and needs three distinct modes that each pass
    // canPlaySa on their own, and PhasesAi never targets (phasesPrefTargeting is a stub), so it could
    // never fill three slots and the card was never cast. Build the list here instead: at most one
    // save (counter or phase out what an opponent's object on top of the stack threatens), then fill
    // the remaining slots with Proliferate (CharmEffect.chainAbilities clones a repeated entry), and
    // cast only for that save or when those proliferates buy something real - a poison kill, or
    // counters worth growing on our side of the table in a window where the mana is not wanted for
    // anything else.
    public static class BrokersConfluence {
        // per proliferate, on CountersProliferateAi's own scale: three of them (>= 6) cover the 5 mana
        public static final int MIN_PROLIFERATE_VALUE = 2;
        // a threatened creature worth the card (unless it is our commander): ~ a non-token 3/3 three-drop
        public static final int MIN_SAVE_EVAL = 180;
        private static final int VALUE_OWN_PLANESWALKER = 3;
        private static final int VALUE_OWN_POSITIVE_COUNTERS = 1;
        private static final int VALUE_OPP_NEGATIVE_COUNTERS = 1;
        private static final int VALUE_OPP_POISON = 2;

        public static List<AbilitySub> chooseModes(final Player ai, final SpellAbility sa,
                                                   final List<AbilitySub> choices, final int num) {
            final List<AbilitySub> chosen = Lists.newArrayList();
            final Game game = ai.getGame();
            final AiController aic = ((PlayerControllerAi) ai.getController()).getAi();

            // RNG parity: the stock picker ran canPlaySa on every offered mode, in this order, and
            // CounterAi can draw there (MyRandom.percentTrue against a 1-3 mana ability). Replay that
            // pass with its verdicts ignored, so a held Confluence consumes exactly the draws it did.
            AbilitySub prolif = null;
            AbilitySub phase = null;
            AbilitySub counter = null;
            for (final AbilitySub sub : choices) {
                sub.setActivatingPlayer(ai);
                aic.canPlaySa(sub);
                if (sub.usesTargeting()) {
                    sub.resetTargets(); // no stale targets from that pass or an earlier priority
                }
                if (sub.getApi() == ApiType.Proliferate) {
                    prolif = sub;
                } else if (sub.getApi() == ApiType.Phases) {
                    phase = sub;
                } else if (sub.getApi() == ApiType.Counter) {
                    counter = sub; // offered only while an activated or triggered ability is on the stack
                }
            }
            if (prolif == null || num <= 0) {
                return chosen; // script drifted: stay out
            }

            // 1. a save: an opponent's object on top of the stack threatens a permanent of ours worth the card
            boolean saves = false;
            final SpellAbility top = ComputerUtilAbility.getTopSpellAbilityOnStack(game, sa);
            if (top != null && top.getActivatingPlayer() != null && top.getActivatingPlayer().isOpponentOf(ai)) {
                final CardCollection worth = new CardCollection();
                for (final Object o : ComputerUtil.predictThreatenedObjects(ai, null, true)) {
                    if (o instanceof Card c && c.isInPlay() && ai.equals(c.getController()) && isWorthSaving(c)) {
                        worth.add(c);
                    }
                }
                if (!worth.isEmpty()) {
                    if (counter != null && !top.isSpell() && counter.canTargetSpellAbility(top)) {
                        counter.getTargets().add(top); // counters the whole threatening ability
                        chosen.add(counter);
                        saves = true;
                    } else if (phase != null) {
                        final CardCollection savable = new CardCollection();
                        for (final Card c : worth) {
                            if (c.isCreature() && phase.canTarget(c)) {
                                savable.add(c);
                            }
                        }
                        if (!savable.isEmpty()) {
                            phase.getTargets().add(ComputerUtilCard.getBestCreatureAI(savable));
                            chosen.add(phase);
                            saves = true;
                        }
                    }
                }
            }

            // 2. fill the remaining slots with Proliferate
            final int proliferates = num - chosen.size();
            for (int i = 0; i < proliferates; i++) {
                chosen.add(prolif);
            }

            // 3. the floor: a save or a poison kill at any time ...
            if (saves || proliferateKills(ai, proliferates)) {
                return chosen;
            }
            // ... otherwise real value, on an empty stack, at the opponent's end step before our turn
            // or in our own main 2 (the script's AIActivateLast$ True lets every other play go first)
            final PhaseHandler ph = game.getPhaseHandler();
            final boolean window = game.getStack().isEmpty()
                    && ((ph.is(PhaseType.END_OF_TURN) && !ph.isPlayerTurn(ai) && ai.equals(ph.getNextTurn()))
                        || ph.is(PhaseType.MAIN2, ai));
            if (window && proliferateValue(ai) >= MIN_PROLIFERATE_VALUE) {
                return chosen;
            }
            chosen.clear();
            return chosen;
        }

        // our commander, a planeswalker, or a creature that evaluates at MIN_SAVE_EVAL or better
        static boolean isWorthSaving(final Card c) {
            return c.isCommander() || c.isPlaneswalker()
                    || (c.isCreature() && ComputerUtilCard.evaluateCreature(c) >= MIN_SAVE_EVAL);
        }

        // What one proliferate gives the resolution-time chooser (CountersProliferateAi.chooseSingleEntity),
        // on CountersProliferateAi's weights, counting only what that chooser really improves: never a
        // land (charge counters on a Vivid land, a graft counter on Llanowar Reborn) or a battle, and on
        // our side only a Positive counter (ComputerUtil.getCounterCategory) with no Negative one beside
        // it, so Neutral keyword counters count for nothing.
        static int proliferateValue(final Player ai) {
            int value = 0;
            for (final Player p : ai.getYourTeam()) {
                for (final Card c : p.getCardsIn(ZoneType.Battlefield)) {
                    if (c.isLand() || c.isBattle() || !c.hasCounters()) {
                        continue;
                    }
                    if (c.isPlaneswalker()) {
                        value += VALUE_OWN_PLANESWALKER;
                        continue;
                    }
                    boolean positive = false;
                    boolean negative = false;
                    for (final CounterType ct : c.getCounters().elementSet()) {
                        if (c.getCounters(ct) < 1) {
                            continue;
                        }
                        final CounterAiCategory category = ComputerUtil.getCounterCategory(ct, c);
                        positive |= category == CounterAiCategory.Positive;
                        negative |= category == CounterAiCategory.Negative;
                    }
                    if (positive && !negative) {
                        value += VALUE_OWN_POSITIVE_COUNTERS;
                    }
                }
            }
            boolean opponentPoison = false;
            for (final Player o : ai.getOpponents()) {
                opponentPoison |= o.getPoisonCounters() > 0 && o.canReceiveCounters(CounterEnumType.POISON);
                for (final Card c : o.getCardsIn(ZoneType.Battlefield)) {
                    if (!c.isCreature() || c.isLand() || c.isPlaneswalker() || !c.hasCounters()) {
                        continue;
                    }
                    for (final CounterType ct : c.getCounters().elementSet()) {
                        if (c.getCounters(ct) >= 1 && ComputerUtil.isNegativeCounter(ct, c)) {
                            value += VALUE_OPP_NEGATIVE_COUNTERS;
                            break;
                        }
                    }
                }
            }
            return value + (opponentPoison ? VALUE_OPP_POISON : 0);
        }

        // an opponent who already has poison, can get more and can lose to it reaches 10 within n proliferates
        static boolean proliferateKills(final Player ai, final int n) {
            for (final Player o : ai.getOpponents()) {
                final int poison = o.getPoisonCounters();
                if (poison > 0 && poison + n >= 10 && o.canReceiveCounters(CounterEnumType.POISON)
                        && !o.cantLoseCheck(forge.game.player.GameLossReason.Poisoned)) {
                    return true;
                }
            }
            return false;
        }
    }

    // Calamity of the Titans
    // "As an additional cost to cast this spell, reveal a colorless creature card from your hand. Exile each
    // creature and planeswalker with mana value less than the revealed card's mana value."
    // X (Revealed$CardManaCost) reads the reveal cost's paid list, which stays empty until the cost is paid,
    // so ChangeZoneAllAi's generic ChangeType filter always saw X = 0, an empty sweep, and declined; and the
    // stock reveal payment is the discard heuristic, blind to the sweep. Judge every revealable mana value
    // here, and pay the cost with the same chooser (AiCostDecision.visit(CostReveal)), so the sweep judged
    // is the sweep cast. Floor: something of an opponent's is exiled, and the net exiled value (theirs
    // minus ours) clears the profile's mass-exile margin. Reads game state only: no random draw.
    public static class CalamityOfTheTitans {
        private static boolean isSwept(final Card c, final int mv) {
            return (c.isCreature() || c.isPlaneswalker()) && c.getCMC() < mv;
        }

        private static int value(final Card c) {
            if (c.isCreature()) {
                return ComputerUtilCard.evaluateCreature(c);
            }
            return 100 + 25 * c.getCMC(); // noncreature planeswalker
        }

        /** Net value exiled by a reveal of mana value mv: the opponents' minus ours. */
        public static int netExile(final Player ai, final int mv) {
            int net = 0;
            for (final Card c : ai.getOpponents().getCardsIn(ZoneType.Battlefield)) {
                if (isSwept(c, mv)) {
                    net += value(c);
                }
            }
            for (final Card c : ai.getCardsIn(ZoneType.Battlefield)) {
                if (isSwept(c, mv)) {
                    net -= value(c);
                }
            }
            return net;
        }

        /** The card to reveal: the best net exile, ties keeping the smaller mana value. Null if none. */
        public static Card chooseReveal(final Player ai, final SpellAbility sa, final CostReveal reveal) {
            final Card host = sa.getHostCard();
            if (reveal == null || host == null) {
                return null;
            }
            final CardCollection hand = new CardCollection(ai.getCardsIn(reveal.getRevealFrom()));
            hand.remove(host); // can't pay for itself (CostReveal.getMaxAmountX)
            final CardCollection valid = CardLists.getValidCards(hand, reveal.getType().split(";"), ai, host, sa);
            if (valid.isEmpty()) {
                return null;
            }
            final SortedSet<Integer> mvs = new TreeSet<>();
            for (final Card c : valid) {
                mvs.add(c.getCMC());
            }
            int bestMv = mvs.first();
            int bestNet = Integer.MIN_VALUE;
            for (final int mv : mvs) { // ascending, strict '>': a tie keeps the smaller reveal
                final int net = netExile(ai, mv);
                if (net > bestNet) {
                    bestNet = net;
                    bestMv = mv;
                }
            }
            final int mv = bestMv;
            return ComputerUtilCard.getWorstCreatureAI(CardLists.filter(valid, c -> c.getCMC() == mv));
        }

        public static AiAbilityDecision consider(final Player ai, final SpellAbility sa, final boolean ignoreTiming) {
            final CostReveal reveal = sa.getPayCosts() == null ? null
                    : sa.getPayCosts().getCostPartByType(CostReveal.class);
            final Card revealed = chooseReveal(ai, sa, reveal);
            if (revealed == null || revealed.getCMC() <= 0) {
                return new AiAbilityDecision(0, AiPlayDecision.CantPlayAi);
            }
            final int mv = revealed.getCMC();
            // Never cast it to exile nothing of an opponent's.
            if (!ai.getOpponents().getCardsIn(ZoneType.Battlefield).anyMatch(c -> isSwept(c, mv))) {
                return new AiAbilityDecision(0, AiPlayDecision.CantPlayAi);
            }
            // The stock mass-exile margin (ChangeZoneAllAi.canPlay), on the real sweep.
            if (netExile(ai, mv) <= AiProfileUtil.getIntProperty(ai, AiProps.BOUNCE_ALL_ELSEWHERE_CREAT_EVAL_DIFF)) {
                return new AiAbilityDecision(0, AiPlayDecision.CantPlayAi);
            }
            // The stock mass-exile timing; a cascade cast is now or never, so it skips this.
            if (!ignoreTiming && ai.getGame().getPhaseHandler().is(PhaseType.MAIN1, ai)) {
                return new AiAbilityDecision(0, AiPlayDecision.TimingRestrictions);
            }
            return new AiAbilityDecision(100, AiPlayDecision.WillPlay);
        }
    }

    // Cascade shells: Throes of Chaos, Into the Time Vortex (AILogic$ CascadeShell)
    // Spells whose only effect is the Cascade cast trigger (plus Retrace / Rebound), scripted as a
    // no-op SP$ Pump. All the value is the free cascade hit, and that hit is still judged, optionally,
    // by its own handler when the trigger resolves (PlayAi.chooseSingleCard). So this only asks whether
    // the library's composition (never its order) holds a hit the AI would actually cast.
    public static class CascadeShell {
        public static AiAbilityDecision consider(final Player ai, final SpellAbility sa, final boolean free) {
            final Card source = sa.getHostCard();
            if (source == null || !source.hasKeyword(Keyword.CASCADE)) {
                return new AiAbilityDecision(0, AiPlayDecision.CantPlayAi);
            }
            final CardCollectionView library = ai.getCardsIn(ZoneType.Library);
            // the accepted hit leaves the library like a draw (a Rebound cast comes right before the draw step)
            if (library.size() <= 2) {
                return new AiAbilityDecision(0, AiPlayDecision.CantPlayAi);
            }
            final int mv = source.getCMC(); // Cascade's X is the host's mana value
            int hits = 0;      // cards the cascade can exile and offer
            int proactive = 0; // hits the AI casts on its own, with nothing to respond to
            for (final Card c : library) {
                if (c.isLand() || c.getCMC() >= mv) {
                    continue; // not a cascade hit
                }
                final ManaCost cost = c.getManaCost();
                if (cost != null && cost.countX() > 0) {
                    continue; // refused when free: PlayAi.chooseSingleCard (instants/sorceries), PermanentAi (permanents)
                }
                final SpellAbility first = c.getFirstSpellAbility();
                if (first != null && first.getApi() == ApiType.Counter) {
                    continue; // the only spell under the cascade trigger is our own shell
                }
                hits++;
                if (c.isPermanent() || c.isSorcery()) {
                    proactive++;
                }
            }
            if (hits == 0) {
                return new AiAbilityDecision(0, AiPlayDecision.MissingNeededCards);
            }
            // Paying for it (mana, or a land via Retrace): most of the pool must be hits the AI casts,
            // not counters/tricks/protection it declines at sorcery speed with an empty stack.
            if (!free && proactive * 2 < hits) {
                return new AiAbilityDecision(0, AiPlayDecision.CantPlayAi);
            }
            return new AiAbilityDecision(100, AiPlayDecision.WillPlay);
        }
    }

    // Chain of Acid
    public static class ChainOfAcid {
        public static AiAbilityDecision consider(final Player ai, final SpellAbility sa) {
            List<Card> AiLandsOnly = CardLists.filter(ai.getCardsIn(ZoneType.Battlefield),
                    CardPredicates.LANDS);
            List<Card> OppPerms = CardLists.filter(ai.getOpponents().getCardsIn(ZoneType.Battlefield),
                    CardPredicates.NON_CREATURES);

            // TODO: improve this logic (currently the AI has difficulty evaluating non-creature permanents,
            // which it can only distinguish by their CMC, considering >CMC higher value).
            // Currently ensures that the AI will still have lands provided that the human player goes to
            // destroy all the AI's lands in order (to avoid manalock).
            if (!OppPerms.isEmpty() && AiLandsOnly.size() > OppPerms.size() + 2) {
                // If there are enough lands, target the worst non-creature permanent of the opponent
                Card worstOppPerm = ComputerUtilCard.getWorstAI(OppPerms);
                if (worstOppPerm != null) {
                    sa.resetTargets();
                    sa.getTargets().add(worstOppPerm);
                    return new AiAbilityDecision(100, AiPlayDecision.WillPlay);
                }
            }
            return new AiAbilityDecision(0, AiPlayDecision.TargetingFailed);
        }
    }

    // Chain of Smog
    public static class ChainOfSmog {
        public static AiAbilityDecision consider(final Player ai, final SpellAbility sa) {
            if (ai.getCardsIn(ZoneType.Hand).isEmpty()) {
                // to avoid failure to add to stack, provide a legal target opponent first (choosing random at this point)
                // TODO: this makes the AI target opponents with 0 cards in hand, but bailing from here causes a
                // "failed to add to stack" error, needs investigation and improvement.
                Player targOpp = Aggregates.random(ai.getOpponents());

                for (Player opp : ai.getOpponents()) {
                    if (!opp.getCardsIn(ZoneType.Hand).isEmpty()) {
                        targOpp = opp;
                        break;
                    }
                }

                sa.getParent().resetTargets();
                sa.getParent().getTargets().add(targOpp);
                return new AiAbilityDecision(100, AiPlayDecision.WillPlay);
            }

            return new AiAbilityDecision(0, AiPlayDecision.TargetingFailed);
        }
    }

    // Clone Legion
    // "For each creature target player controls, create a token that's a copy
    // of that creature." CopyPermanentAi's DuplicatePerms count reads
    // Defined$ Valid Creature.TargetedPlayerCtrl before any player is targeted,
    // so it is always empty and the card was never cast; the player is chosen
    // here instead. Every creature the target controls is copied and the copies
    // enter under our control, ETBs included, so a player is vetoed outright
    // when any of their creatures has a harmful self-ETB (lose the game, skip
    // turns, a mass sacrifice/destroy, exiling our library or board, a
    // sacrifice-unless). Nine mana and a card buy copies, so the floor is value:
    // a player is eligible with at least two copies worth having whose summed
    // value reaches MIN_COPY_VALUE, and the richest eligible board wins, ours
    // first on ties.
    public static class CloneLegion {
        public static final int MIN_COPIES = 2;
        public static final int MIN_COPY_VALUE = 400;

        // A token copy is never evoked, kicked or cast, so a self-ETB that
        // requires one of those never triggers for it ("!wasCast..." does).
        private static final Pattern CAST_ONLY_ETB = Pattern.compile("(?<!!)(evoked|kicked|wasCast)");

        public static AiAbilityDecision consider(final Player ai, final SpellAbility sa, final boolean mandatory) {
            sa.resetTargets();
            if (!sa.usesTargeting() || !sa.getTargetRestrictions().canTgtPlayer()) {
                return new AiAbilityDecision(0, AiPlayDecision.CantPlayAi);
            }
            final List<Player> candidates = Lists.newArrayList(ai);
            candidates.addAll(ai.getOpponents());

            Player best = null;
            int bestValue = -1;
            Player bestSafe = null; // highest sum among players not vetoed
            int bestSafeValue = -1;
            Player bestAny = null; // highest sum among all targetable players
            int bestAnyValue = -1;
            for (final Player p : candidates) {
                if (!sa.canTarget(p)) {
                    continue;
                }
                boolean vetoed = false;
                int value = 0;
                int count = 0;
                for (final Card c : p.getCreaturesInPlay()) {
                    if (hasHarmfulSelfETB(c)) {
                        vetoed = true;
                    }
                    if (!worthCopying(ai, c)) {
                        continue;
                    }
                    value += copyValue(c);
                    count++;
                }
                if (value > bestAnyValue) {
                    bestAny = p;
                    bestAnyValue = value;
                }
                if (vetoed) {
                    continue;
                }
                if (value > bestSafeValue) {
                    bestSafe = p;
                    bestSafeValue = value;
                }
                if (count >= MIN_COPIES && value >= MIN_COPY_VALUE && value > bestValue) {
                    best = p;
                    bestValue = value;
                }
            }

            Player choice = best;
            if (choice == null) {
                if (!mandatory) {
                    return new AiAbilityDecision(0, AiPlayDecision.MissingNeededCards);
                }
                choice = bestSafe != null ? bestSafe : bestAny;
                if (choice == null) {
                    return new AiAbilityDecision(0, AiPlayDecision.TargetingFailed);
                }
            }
            sa.getTargets().add(choice);
            return new AiAbilityDecision(100, AiPlayDecision.WillPlay);
        }

        // Left out of both count and value (still copied, just not worth
        // paying for): a 0-toughness body that gets its size from counters
        // (the copy enters as a 0/0 and dies), a card the AI cannot play, and
        // a legend the legend rule would take straight back (ours, or theirs
        // when we already control one with that name).
        private static boolean worthCopying(final Player ai, final Card c) {
            if (c.getBaseToughness() <= 0 || ComputerUtilCard.isCardRemAIDeck(c)) {
                return false;
            }
            if (c.getType().isLegendary()) {
                if (ai.equals(c.getController())) {
                    return false;
                }
                if (!CardLists.filter(ai.getCardsIn(ZoneType.Battlefield),
                        CardPredicates.nameEquals(c.getName())).isEmpty()) {
                    return false;
                }
            }
            return true;
        }

        // A copy has the printed P/T, not the counters, auras, equipment or
        // anthems on the original, so the P/T terms are taken from base values.
        private static int copyValue(final Card c) {
            return ComputerUtilCard.evaluateCreature(c, false, true)
                    + 15 * Math.max(0, c.getBasePower()) + 10 * Math.max(0, c.getBaseToughness());
        }

        private static boolean hasHarmfulSelfETB(final Card c) {
            for (final Trigger t : c.getTriggers()) {
                if (t.getMode() != TriggerType.ChangesZone || t.hasParam("OptionalDecider")) {
                    continue;
                }
                if (!t.hasParam("Destination") || !t.getParam("Destination").contains("Battlefield")) {
                    continue;
                }
                final String valid = t.hasParam("ValidCard") ? t.getParam("ValidCard") : "";
                if (!valid.contains("Self") || CAST_ONLY_ETB.matcher(valid).find()) {
                    continue;
                }
                if (ComputerUtilCard.isCardRemAIDeck(c) || isHarmfulChain(t)) {
                    return true;
                }
            }
            return false;
        }

        // Reads the trigger's ability chain without building it: an ability
        // not built yet is read from its Execute SVar text, because building
        // one allocates a SpellAbility id (ids feed SpellAbility.hashCode), and
        // this runs on every look at the card, cast or not.
        private static boolean isHarmfulChain(final Trigger t) {
            final SpellAbility built = t.getOverridingAbility();
            if (built != null) {
                for (SpellAbility part = built; part != null; part = part.getSubAbility()) {
                    if (isHarmfulPart(part.getApi() == null ? "" : part.getApi().name(), part.usesTargeting(),
                            part.getParam("Origin"), part.getParam("ChangeType"), part.getParam("UnlessCost"))) {
                        return true;
                    }
                }
                return false;
            }
            final Set<String> seen = new HashSet<>();
            String svar = t.hasParam("Execute") ? t.getParam("Execute") : null;
            while (svar != null && seen.add(svar)) {
                final String text = t.getSVar(svar);
                if (text.isEmpty()) {
                    break;
                }
                final Map<String, String> params = FileSection.parseToMap(text, FileSection.DOLLAR_SIGN_KV_SEPARATOR);
                String api = params.get("DB");
                if (api == null) {
                    api = params.containsKey("AB") ? params.get("AB") : params.get("SP");
                }
                if (isHarmfulPart(api == null ? "" : api, params.containsKey("ValidTgts"),
                        params.get("Origin"), params.get("ChangeType"), params.get("UnlessCost"))) {
                    return true;
                }
                svar = params.get("SubAbility");
            }
            return false;
        }

        private static boolean isHarmfulPart(final String api, final boolean targeted, final String origin,
                final String changeType, final String unlessCost) {
            if (ApiType.LosesGame.name().equals(api) || ApiType.SkipTurn.name().equals(api)
                    || ApiType.SacrificeAll.name().equals(api) || ApiType.DestroyAll.name().equals(api)) {
                return true;
            }
            if (ApiType.ChangeZoneAll.name().equals(api) && !targeted && origin != null
                    && (origin.contains("Library") || origin.contains("Battlefield"))
                    && (changeType == null || !(changeType.contains("OppCtrl") || changeType.contains("OppOwn")))) {
                return true;
            }
            return unlessCost != null && unlessCost.contains("Sac<");
        }
    }

    // Commandeer
    // Cast only in response to an opponent's noncreature spell worth taking.
    // Two hard limits shape the window: the AI cannot choose new targets for
    // the stolen spell (PlayerControllerAi.chooseNewTargetsFor is a stub), so
    // a spell with chosen targets resolves against its original targets and is
    // never worth stealing; and mass "...All" effects mostly resolve the same
    // (or worse - controller-relative wordings flip onto us) whoever controls
    // them. So: opponent's spell, no chosen targets anywhere in its chain, no
    // *All api, and a CMC floor so seven mana / three cards buys a haymaker.
    public static class Commandeer {
        public static final int MIN_STOLEN_CMC = 5;

        public static AiAbilityDecision consider(final Player ai, final SpellAbility sa) {
            final Game game = ai.getGame();

            // Routing through ControlSpellAi.canPlay's name gate bypasses the
            // base class's restriction check, so mirror it here.
            if (sa.getRestrictions() != null && !sa.getRestrictions().canPlay(sa.getHostCard(), sa)) {
                return new AiAbilityDecision(0, AiPlayDecision.CantPlaySa);
            }

            if (game.getStack().isEmpty()) {
                return new AiAbilityDecision(0, AiPlayDecision.TargetingFailed);
            }
            final SpellAbility topSA = ComputerUtilAbility.getTopSpellAbilityOnStack(game, sa);
            if (topSA == null || !topSA.isSpell()) {
                return new AiAbilityDecision(0, AiPlayDecision.TargetingFailed);
            }

            final Player caster = topSA.getActivatingPlayer();
            if (caster == null || !caster.isOpponentOf(ai) || ai.getYourTeam().contains(caster)) {
                return new AiAbilityDecision(0, AiPlayDecision.CantPlayAi);
            }

            for (SpellAbility part = topSA; part != null; part = part.getSubAbility()) {
                if (part.usesTargeting() && !part.getTargets().isEmpty()) {
                    return new AiAbilityDecision(0, AiPlayDecision.CantPlayAi);
                }
                if (part.getApi() != null && part.getApi().name().endsWith("All")) {
                    return new AiAbilityDecision(0, AiPlayDecision.CantPlayAi);
                }
            }

            // Value floor: whichever cost is being weighed (seven mana, or
            // exiling two other blue cards), only a haymaker pays it back.
            int tgtCMC = 0;
            if (topSA.getPayCosts() != null && topSA.getPayCosts().getTotalMana() != null) {
                tgtCMC = topSA.getPayCosts().getTotalMana().getCMC();
                if (topSA.getPayCosts().getTotalMana().countX() > 0) {
                    tgtCMC += topSA.getXManaCostPaid() != null ? topSA.getXManaCostPaid() : 3;
                }
            }
            if (tgtCMC < MIN_STOLEN_CMC) {
                return new AiAbilityDecision(0, AiPlayDecision.CantPlayAi);
            }

            // Alternative-cost variant (exile two other blue cards from hand):
            // require Commandeer plus two other blue cards, otherwise the cast
            // fails at payment (same guard as ForceOfWill.consider below).
            for (CostPart c : sa.getPayCosts().getCostParts()) {
                if (c instanceof CostExile) {
                    CardCollection blueCards = CardLists.filter(ai.getCardsIn(ZoneType.Hand), CardPredicates.isColor(MagicColor.BLUE));
                    if (blueCards.size() < 3) {
                        return new AiAbilityDecision(0, AiPlayDecision.CantAfford);
                    }
                    break;
                }
            }

            sa.resetTargets();
            if (!sa.canTargetSpellAbility(topSA)) {
                return new AiAbilityDecision(0, AiPlayDecision.TargetingFailed);
            }
            sa.getTargets().add(topSA);
            return new AiAbilityDecision(100, AiPlayDecision.WillPlay);
        }
    }

    // Commander's Insight
    // "Target player draws X cards plus an additional card for each time they've cast a
    // commander from the command zone this game." The script says NumCards$ Z (SVar$Y/Plus.X),
    // and DrawAi.targetAI announces X only for a literal NumCards$ X, so X was never chosen,
    // Z read 0 (no target yet, X unpaid) and the draw of nothing was declined. Announce X here,
    // target only ourselves, and draw only what we keep. Floor: at least MIN_DRAW cards in
    // total; at most half of the library above LIBRARY_MARGIN (an X-spell copy such as Unbound
    // Flourishing's resolves the same draw again); no cleanup discard on our own turn (one card
    // of slack on an opponent's turn, ahead of our untap and land drop); the whole draw
    // survives draw-limit statics (Narset, Parter of Veils; Spirit of the Labyrinth; Leovold);
    // no opposing draw punisher or draw thief. Every RNG-free check runs before setMaxXValue,
    // whose test payments draw MyRandom (ComputerUtilMana.isManaSourceReserved).
    public static class CommandersInsight {
        public static final int MIN_DRAW = 3;
        public static final int LIBRARY_MARGIN = 3;

        public static boolean consider(final Player ai, final SpellAbility sa, final boolean mandatory) {
            final Game game = ai.getGame();
            final Card source = sa.getHostCard();
            final SpellAbility root = sa.getRootAbility();
            final boolean xFixed = sa.isCopied(); // a copy keeps the original's X
            sa.resetTargets();

            final int bonus = ai.getTotalCommanderCast(); // Y as it will read with us targeted
            int hand = ai.getCardsIn(ZoneType.Hand).size();
            if (source.isInZone(ZoneType.Hand)) {
                hand--; // the Insight itself is spent
            }
            final int library = ai.getCardsIn(ZoneType.Library).size();

            int capTotal = (library - LIBRARY_MARGIN) / 2;
            if (!ai.isUnlimitedHandSize()) {
                // our turn: nothing over max hand size at cleanup; an opponent's turn: +1 for
                // the untap and land drop before our own cleanup
                final int room = ai.getMaxHandSize() - hand + (game.getPhaseHandler().isPlayerTurn(ai) ? 0 : 1);
                capTotal = Math.min(capTotal, room);
            }

            // Necessary conditions of the accept test below, checked RNG-free first:
            // total >= MIN_DRAW, total >= bonus and total <= capTotal, canDrawAmount(total).
            if (sa.canTarget(ai) && ai.canDraw() && capTotal >= Math.max(MIN_DRAW, bonus)
                    && !opposingDrawPunisher(ai)) {
                int x;
                if (xFixed) {
                    x = root.getXManaCostPaid() == null ? 0 : root.getXManaCostPaid();
                } else {
                    root.setXManaCostPaid(null); // drop a stale value from an earlier window
                    x = ComputerUtilCost.setMaxXValue(sa, ai, sa.isTrigger()); // leftover after U U U
                    x = Math.max(0, Math.min(x, capTotal - bonus));
                }
                final int total = x + bonus;
                if (total >= MIN_DRAW && total <= capTotal && ai.canDrawAmount(total)) {
                    if (!xFixed) {
                        root.setXManaCostPaid(x);
                    }
                    sa.getTargets().add(ai);
                    return true;
                }
            }
            if (!mandatory) {
                if (!xFixed) {
                    root.setXManaCostPaid(null);
                }
                return false;
            }

            // forced (a cast or copy that must complete): the cheapest X, the least harmful target
            int forcedX = 0;
            if (xFixed) {
                forcedX = root.getXManaCostPaid() == null ? 0 : root.getXManaCostPaid();
            } else {
                root.setXManaCostPaid(0);
            }
            if (sa.canTarget(ai) && forcedX + bonus < library) {
                sa.getTargets().add(ai);
                return true;
            }
            for (final Player opp : ai.getOpponents()) {
                if (sa.canTarget(opp)) {
                    sa.getTargets().add(opp);
                    return true;
                }
            }
            if (sa.canTarget(ai)) {
                sa.getTargets().add(ai); // last resort: the forced draw must go somewhere
                return true;
            }
            return false;
        }

        // An opposing "whenever an opponent draws a card" trigger (Nekusar, the Mindrazer;
        // Orcish Bowmasters; Kederekt Parasite; Fate Unraveler): Mode$ Drawn on an opponent's
        // permanent whose ValidCard names opponents (Card.OppOwn, Card.OppCtrl), an owner, or
        // nothing at all. Or an opposing draw replacement that applies to us (Notion Thief,
        // Hullbreacher, Alms Collector, Chains of Mephistopheles).
        private static boolean opposingDrawPunisher(final Player ai) {
            for (final Player opp : ai.getOpponents()) {
                for (final Card c : opp.getCardsIn(ZoneType.Battlefield)) {
                    for (final Trigger t : c.getTriggers()) {
                        if (t.getMode() != TriggerType.Drawn) {
                            continue;
                        }
                        final String valid = t.getParamOrDefault("ValidCard", "Card");
                        if (valid.contains("Opp") || valid.equals("Card") || valid.contains("OwnedBy")) {
                            return true;
                        }
                    }
                }
            }
            for (final Card c : ai.getGame().getCardsIn(ZoneType.Battlefield)) {
                if (ai.equals(c.getController())) {
                    continue;
                }
                for (final ReplacementEffect re : c.getReplacementEffects()) {
                    if ((re.getMode() == ReplacementType.Draw || re.getMode() == ReplacementType.DrawCards)
                            && re.matchesValidParam("ValidPlayer", ai)) {
                        return true;
                    }
                }
            }
            return false;
        }
    }

    // Cosmic Intervention
    // "If a permanent you control would be put into a graveyard from the battlefield this
    // turn, exile it instead. Return it to the battlefield under its owner's control at the
    // beginning of the next end step." Reached from EffectAi.checkApiLogic's AILogic$
    // CosmicIntervention branch after the randomReturn roll, for the hand cast and the
    // foretold cast alike, and EffectAi.doTriggerNoCost skips its AILogic pre-call for this
    // logic, so every consult draws the one roll the stock no-AILogic refusal drew. Draws no
    // RNG itself. One window, safe by construction: an OPPONENT's spell or ability on top of
    // the stack whose effect chain puts our own permanents into the graveyard (destroy, lethal
    // damage, -X/-X, sacrifice-all). Resolving then can only turn those deaths into a
    // temporary exile. Declined when any link of that chain exiles, bounces, steals or
    // attaches instead (the shared predictor counts those; this card saves from none of them).
    // Counted: permanents we control AND own, non-token (tokens cease to exist in exile), not a
    // commander (903.9a offers the command zone from exile too). Floor: two or more nonland
    // permanents saved, three or more lands, or one premium nonland (a creature at
    // CreatureEvaluator 200+, a planeswalker, or another nonland at CMC 4+). Divided damage is
    // re-checked per target against its own allocation (the predictor applies the whole NumDmg
    // to every target), and stolen permanents this would hand back to their owners are netted
    // out of the count and veto the single-card branch.
    public static class CosmicIntervention {
        public static final int MIN_SAVED = 2;               // two cards saved: card advantage vs. the removal
        public static final int SINGLE_CREATURE_VALUE = 200; // ~ a non-token 3/3 with an ability
        public static final int SINGLE_NONCREATURE_CMC = 4;  // real artifacts and enchantments
        public static final int MIN_LANDS = 3;               // land wipes

        public static AiAbilityDecision consider(final Player ai, final SpellAbility sa) {
            final Game game = ai.getGame();
            if (game.getStack().isEmpty() || alreadyActive(ai)) {
                return new AiAbilityDecision(0, AiPlayDecision.CantPlayAi);
            }
            final SpellAbility top = game.getStack().peekAbility();
            if (top == null || top.getActivatingPlayer() == null || !top.getActivatingPlayer().isOpponentOf(ai)) {
                return new AiAbilityDecision(0, AiPlayDecision.CantPlayAi);
            }
            final SpellAbility threat = top instanceof forge.game.trigger.WrappedAbility w ? w.getWrappedAbility() : top;
            if (!routesToGraveyard(threat)) {
                return new AiAbilityDecision(0, AiPlayDecision.CantPlayAi);
            }

            final Set<Card> threatened = new LinkedHashSet<>();
            for (final Object o : ComputerUtil.predictThreatenedObjects(ai, null, true)) {
                if (o instanceof Card c) {
                    threatened.add(c);
                }
            }
            threatened.removeAll(survivesDividedDamage(threat));
            threatened.addAll(sacrificeAllVictims(ai, threat));

            final CardCollection saved = new CardCollection();
            final CardCollection givenBack = new CardCollection();
            for (final Card c : threatened) {
                if (!c.isInPlay() || !ai.equals(c.getController()) || c.isToken()) {
                    continue;
                }
                if (!ai.equals(c.getOwner())) {
                    givenBack.add(c); // returns under its owner's control: to an opponent
                } else if (!c.isRealCommander()) {
                    saved.add(c);
                }
            }
            return passesFloor(saved, givenBack)
                    ? new AiAbilityDecision(100, AiPlayDecision.WillPlay)
                    : new AiAbilityDecision(0, AiPlayDecision.CantPlayAi);
        }

        private static boolean passesFloor(final CardCollection saved, final CardCollection givenBack) {
            final CardCollection nonland = CardLists.filter(saved, c -> !c.isLand());
            final int lands = saved.size() - nonland.size();
            final int back = givenBack.size();
            if (nonland.size() - back >= MIN_SAVED || lands - back >= MIN_LANDS) {
                return true;
            }
            if (nonland.size() == 1 && back == 0) {
                final Card c = nonland.getFirst();
                return c.isCreature() ? ComputerUtilCard.evaluateCreature(c) >= SINGLE_CREATURE_VALUE
                        : c.isPlaneswalker() || c.getCMC() >= SINGLE_NONCREATURE_CMC;
            }
            return false;
        }

        // A second copy this turn adds nothing. The effect card's name embeds the host's view
        // string, so match on the effect source rather than isCardInCommand(name).
        private static boolean alreadyActive(final Player ai) {
            return ai.getCardsIn(ZoneType.Command).anyMatch(c -> c.getEffectSource() != null
                    && "Cosmic Intervention".equals(c.getEffectSource().getName()));
        }

        // False when any link of the threat chain removes battlefield permanents by a route this
        // card does not replace (exile, bounce, library, control change, auras).
        private static boolean routesToGraveyard(final SpellAbility threat) {
            for (SpellAbility cur = threat; cur != null; cur = cur.getSubAbility()) {
                final ApiType api = cur.getApi();
                if (api == ApiType.ChangeZone || api == ApiType.ChangeZoneAll) {
                    final String origin = cur.getParamOrDefault("Origin", "");
                    if ((origin.isEmpty() || origin.contains("Battlefield"))
                            && !"Graveyard".equals(cur.getParam("Destination"))) {
                        return false;
                    }
                } else if (api == ApiType.GainControl || api == ApiType.ExchangeControl || api == ApiType.Attach) {
                    return false;
                }
            }
            return true;
        }

        // Targets of a DividedAsYouChoose DealDamage link whose own allocation does not kill them
        // (a missing allocation counts as no damage). Removing them can only lower the count.
        private static CardCollection survivesDividedDamage(final SpellAbility threat) {
            final CardCollection out = new CardCollection();
            for (SpellAbility cur = threat; cur != null; cur = cur.getSubAbility()) {
                if (cur.getApi() != ApiType.DealDamage || !cur.isDividedAsYouChoose()) {
                    continue;
                }
                for (final Card c : cur.getTargets().getTargetCards()) {
                    final Integer dmg = cur.getDividedValue(c);
                    if (dmg == null || ComputerUtilCombat.predictDamageTo(c, dmg, cur.getHostCard(), false)
                            < ComputerUtilCombat.getDamageToKill(c, false)) {
                        out.add(c);
                    }
                }
            }
            return out;
        }

        // The predictor has no SacrificeAll branch. Mirror SacrificeAllEffect's own list (the
        // battlefield filtered by ValidCards, then canBeSacrificedBy) for an unconditional sweep;
        // Defined/Controller variants are left out, which can only under-count.
        private static CardCollection sacrificeAllVictims(final Player ai, final SpellAbility threat) {
            final CardCollection out = new CardCollection();
            for (SpellAbility cur = threat; cur != null; cur = cur.getSubAbility()) {
                if (cur.getApi() != ApiType.SacrificeAll || cur.hasParam("Defined") || cur.hasParam("Controller")) {
                    continue;
                }
                CardCollectionView list = ai.getGame().getCardsIn(ZoneType.Battlefield);
                if (cur.hasParam("ValidCards")) {
                    list = AbilityUtils.filterListByType(list, cur.getParam("ValidCards"), cur);
                }
                for (final Card c : list) {
                    if (c.canBeSacrificedBy(cur, true)) {
                        out.add(c);
                    }
                }
            }
            return out;
        }
    }

    // Crackling Spellslinger
    // "When Crackling Spellslinger enters, if you cast it, the next instant or sorcery
    // spell you cast this turn has storm." Reached from EffectAi.checkApiLogic's
    // AILogic$ CracklingSpellslinger branch (the ETB Effect sub that
    // AiController.checkETBEffects consults before the cast) after the randomReturn
    // roll, and EffectAi.doTriggerNoCost skips its AILogic pre-call for this logic, so
    // each consult draws the one roll the stock no-AILogic refusal drew. The real
    // trigger is mandatory at resolution, so this only decides the cast. Approves:
    // a storm cast in our own main phase with an empty stack and a payable instant or
    // sorcery follow-up; a body-only cast at the end step before our turn when no
    // follow-up is in hand (the storm could never matter); a cast that saves the card
    // from cleanup discard; an emergency blocker when the attack would kill us.
    // Never at the opponent's upkeep, draw or main phase, and never in our own main
    // phase without a follow-up. Draws no RNG: lifeInDanger rolls MyRandom, so
    // lifeInSeriousDanger is used; and every approval first passes an RNG-free estimate
    // of Spellslinger's own cost (mana count, red sources), because an approval goes on
    // to canPayCost, whose mana-reservation roll (ComputerUtilMana.isManaSourceReserved)
    // the stock veto never reached.
    public static class CracklingSpellslinger {
        public static boolean consider(final Player ai, final SpellAbility sa) {
            final Card host = sa.getHostCard();
            if (host == null) {
                return false;
            }
            final Game game = ai.getGame();
            final PhaseHandler ph = game.getPhaseHandler();
            if (ph.getPhase() == null) {
                return false;
            }
            final int avail = ComputerUtilMana.getAvailableManaEstimate(ai, true); // untapped only, RNG-free
            final int own = host.getCMC();
            if (avail < own || !hasRedSources(ai, host)) {
                return false;
            }

            // 1. Storm window: the follow-up fits in the untapped mana on top of this
            //    creature, and gets at least one copy (this creature is on the storm count).
            if (ph.isPlayerTurn(ai) && ph.getPhase().isMain() && game.getStack().isEmpty()
                    && hasFollowUp(ai, avail - own)) {
                return true;
            }
            // 2. Body-only windows with nothing else to spend the mana on.
            if (!ph.isPlayerTurn(ai) && ph.is(PhaseType.END_OF_TURN) && ai.equals(ph.getNextTurn())
                    && !hasFollowUp(ai, -1)) {
                return true; // no follow-up to storm, and the mana untaps next step anyway
            }
            if (ph.is(PhaseType.END_OF_TURN, ai) && !ai.isUnlimitedHandSize()
                    && ai.getCardsIn(ZoneType.Hand).size() > ai.getMaxHandSize()) {
                return true; // would be discarded in cleanup otherwise
            }
            // 3. Emergency blocker, only when the attack would kill us.
            final Combat combat = game.getCombat();
            return combat != null && !ph.isPlayerTurn(ai) && ph.is(PhaseType.COMBAT_DECLARE_ATTACKERS)
                    && !combat.getAttackersOf(ai).isEmpty() && ComputerUtilCombat.lifeInSeriousDanger(ai, combat);
        }

        // An instant or sorcery in hand that the storm grant could copy: a real mana cost, no X
        // (it can't be sized here), and not a counterspell (never cast proactively).
        // budget < 0 accepts any cost.
        private static boolean hasFollowUp(final Player ai, final int budget) {
            for (final Card c : ai.getCardsIn(ZoneType.Hand)) {
                if (!c.isInstant() && !c.isSorcery()) {
                    continue; // Spellslinger itself is a creature and never counts
                }
                final ManaCost follow = c.getManaCost();
                final SpellAbility first = c.getFirstSpellAbility();
                if (follow == null || follow.isNoCost() || follow.countX() > 0
                        || first == null || first.getApi() == ApiType.Counter) {
                    continue;
                }
                if (budget < 0 || follow.getCMC() <= budget) {
                    return true;
                }
            }
            return false;
        }

        // Enough red for Spellslinger's own red pips: floating red plus untapped sources whose
        // printed production could be red. Reads only the Produced text (never mana(sa), which
        // can run special-mana handling), so it errs optimistic.
        private static boolean hasRedSources(final Player ai, final Card host) {
            final ManaCost cost = host.getManaCost();
            final int needed = cost == null ? 0 : cost.getShardCount(forge.card.mana.ManaCostShard.RED);
            int red = ai.getManaPool().getAmountOfColor(MagicColor.RED);
            for (final Card src : ai.getCardsIn(ZoneType.Battlefield)) {
                if (red >= needed) {
                    break;
                }
                for (final SpellAbility ma : src.getManaAbilities()) {
                    ma.setActivatingPlayer(ai);
                    if (ma.getManaPart() == null || !ma.canPlay()) {
                        continue;
                    }
                    final String produced = ma.getManaPart().getOrigProduced();
                    if (produced.contains("R") || produced.contains("Any") || produced.contains("Chosen")
                            || produced.startsWith("Combo")) {
                        red++;
                        break;
                    }
                }
            }
            return red >= needed;
        }
    }

    // Crafty Cutpurse
    // "When Crafty Cutpurse enters, each token that would be created under an
    // opponent's control this turn is created under your control instead."
    // Reached from EffectAi.checkApiLogic's AILogic$ CraftyCutpurse branch (the
    // ETB Effect sub that AiController.checkETBEffects consults before the cast)
    // after the randomReturn roll, and EffectAi.doTriggerNoCost skips its AILogic
    // pre-call for this logic, so each consult draws the one roll the stock
    // no-AILogic refusal drew. The flash 2/2 is only worth four mana in response
    // to an opponent's spell or ability already on the stack that will create a
    // token under an opponent's control, and never before our own end step (an
    // opponent's token trigger in our combat would tap us out of main 2 for one
    // Treasure). Not counted: optional triggers and Optional, OptionalDecider or
    // UnlessCost parts (we would pay an unless-cost ourselves and stop the very
    // token), unmet conditions, a Token amount of 0. Creators follow each effect's
    // own player rule. No RNG of its own; a part that throws on an unresolved
    // stack item counts as no token, so the Game AI Eval task never fails here.
    public static class CraftyCutpurse {
        public static AiAbilityDecision consider(final Player ai, final SpellAbility sa) {
            final Game game = ai.getGame();
            final PhaseHandler ph = game.getPhaseHandler();
            if (ph.isPlayerTurn(ai) && (ph.getPhase() == null || ph.getPhase().isBefore(PhaseType.END_OF_TURN))) {
                return new AiAbilityDecision(0, AiPlayDecision.CantPlayAi);
            }
            for (final SpellAbilityStackInstance si : game.getStack()) {
                final SpellAbility item = si.getSpellAbility();
                if (item == null || item.isOptionalTrigger()) {
                    continue;
                }
                final Player activator = item.getActivatingPlayer();
                if (activator == null || !activator.isOpponentOf(ai)) {
                    continue;
                }
                // A trigger on the stack is a WrappedAbility, which does not delegate
                // its conditions: judge the wrapped ability instead.
                final SpellAbility root = item instanceof forge.game.trigger.WrappedAbility
                        ? ((forge.game.trigger.WrappedAbility) item).getWrappedAbility() : item;
                for (SpellAbility part = root; part != null; part = part.getSubAbility()) {
                    if (createsTokensForOpponent(ai, part)) {
                        return new AiAbilityDecision(100, AiPlayDecision.WillPlay);
                    }
                }
            }
            return new AiAbilityDecision(0, AiPlayDecision.CantPlayAi);
        }

        private static boolean createsTokensForOpponent(final Player ai, final SpellAbility part) {
            final ApiType api = part.getApi();
            if (api != ApiType.Token && api != ApiType.CopyPermanent
                    && api != ApiType.Investigate && api != ApiType.Incubate) {
                return false; // Amass excluded: its counters can land on an Army already in play
            }
            if (part.hasParam("Optional") || part.hasParam("OptionalDecider") || part.hasParam("UnlessCost")) {
                return false;
            }
            try {
                final Card host = part.getHostCard();
                if (host == null || !part.metConditions()) {
                    return false;
                }
                if (api == ApiType.Token
                        && AbilityUtils.calculateAmount(host, part.getParamOrDefault("TokenAmount", "1"), part) <= 0) {
                    return false;
                }
                final List<Player> creators = Lists.newArrayList();
                if (api == ApiType.CopyPermanent) {
                    // CopyPermanentEffect.resolve: Controller, else a ChosenMap's players, else the activator
                    if (part.hasParam("Controller")) {
                        creators.addAll(AbilityUtils.getDefinedPlayers(host, part.getParam("Controller"), part));
                    } else if ("ChosenMap".equals(part.getParam("Defined"))) {
                        creators.addAll(host.getChosenMap().keySet());
                    }
                    if (creators.isEmpty()) {
                        creators.add(part.getActivatingPlayer());
                    }
                } else {
                    // SpellAbilityEffect.getPlayers: Token reads TokenOwner defined-first;
                    // Investigate and Incubate read Defined, and targets win whenever they target
                    final boolean definedFirst = api == ApiType.Token;
                    final String param = definedFirst ? "TokenOwner" : "Defined";
                    if (part.usesTargeting() && (!definedFirst || !part.hasParam(param))) {
                        part.getTargets().getTargetPlayers().forEach(creators::add);
                    } else {
                        for (final String d : part.getParamOrDefault(param, "You").split(" & ")) {
                            creators.addAll(AbilityUtils.getDefinedPlayers(host, d, part));
                        }
                    }
                }
                for (final Player p : creators) {
                    if (p != null && p.isOpponentOf(ai)) {
                        return true;
                    }
                }
                return false;
            } catch (final RuntimeException e) {
                return false;
            }
        }
    }

    // Crawling Barrens
    public static class CrawlingBarrens {
        public static boolean consider(final Player ai, final SpellAbility sa) {
            final PhaseHandler ph = ai.getGame().getPhaseHandler();
            final Combat combat = ai.getGame().getCombat();

            Card animated = AnimateAi.becomeAnimated(sa.getHostCard(), sa.getSubAbility());
            if (sa.getHostCard().canReceiveCounters(CounterEnumType.P1P1)) {
                animated.addCounterInternal(CounterEnumType.P1P1, 2, ai, false, null, null);
            }
            boolean isOppEOT = ph.is(PhaseType.END_OF_TURN) && ph.getNextTurn() == ai;
            boolean isValuableAttacker = ph.is(PhaseType.MAIN1, ai) && ComputerUtilCard.doesSpecifiedCreatureAttackAI(ai, animated);
            boolean isValuableBlocker = combat != null && combat.getDefendingPlayers().contains(ai) && ComputerUtilCard.doesSpecifiedCreatureBlock(ai, animated);

            return isOppEOT || isValuableAttacker || isValuableBlocker;
        }
    }

    // Curious Herd
    // "Choose target opponent. You create X 3/3 green Beast creature tokens, where X is the
    // number of artifacts that player controls." Routed from PumpAi.checkApiLogic by
    // AILogic$ CuriousHerd: the generic non-curse Pump branch cannot target an opponent player,
    // and the Token sub's inherited chkDrawback never checks X. Pick the opponent whose artifacts
    // give the most tokens - measured with the script's own X, with that target set - and cast
    // only for at least two 3/3s, at instant speed into an empty stack: an opponent's end step,
    // an opponent's declare-attackers step when we are attacked (surprise blockers), or our own
    // main 2 as the fallback. Never main 1 (tokens without haste gain nothing there).
    public static class CuriousHerd {
        public static final int MIN_TOKENS = 2;

        public static AiAbilityDecision consider(final Player ai, final SpellAbility sa) {
            final Game game = ai.getGame();
            final PhaseHandler ph = game.getPhaseHandler();
            final Card source = sa.getHostCard();
            final AbilitySub tokenSa = sa.getSubAbility();
            if (source == null || tokenSa == null || tokenSa.getApi() != ApiType.Token
                    || !tokenSa.hasParam("TokenScript")) {
                return new AiAbilityDecision(0, AiPlayDecision.CantPlayAi);
            }
            if (!game.getStack().isEmpty()) {
                return new AiAbilityDecision(0, AiPlayDecision.AnotherTime);
            }

            final boolean oppTurn = ph.getPlayerTurn().isOpponentOf(ai);
            final Combat combat = game.getCombat();
            final boolean window = (oppTurn && ph.is(PhaseType.END_OF_TURN))
                    || (oppTurn && ph.is(PhaseType.COMBAT_DECLARE_ATTACKERS)
                        && combat != null && combat.isPlayerAttacked(ai))
                    || ph.is(PhaseType.MAIN2, ai);
            if (!window) {
                return new AiAbilityDecision(0, AiPlayDecision.AnotherTime);
            }

            // Turn order; strict > keeps the first opponent on a tie.
            Player best = null;
            int bestX = 0;
            for (final Player opp : ai.getOpponents()) {
                if (!sa.canTarget(opp)) {
                    continue;
                }
                sa.resetTargets();
                sa.getTargets().add(opp);
                // TokenAmount$ X reads TargetedPlayer$ through the sub's parent: exactly what
                // resolution will count against this opponent.
                final int x = AbilityUtils.calculateAmount(source,
                        tokenSa.getParamOrDefault("TokenAmount", "1"), tokenSa);
                if (x > bestX) {
                    best = opp;
                    bestX = x;
                }
            }
            sa.resetTargets();
            if (best == null || bestX < MIN_TOKENS) {
                return new AiAbilityDecision(0, AiPlayDecision.CantPlayAi);
            }

            // A static toughness debuff that would kill the tokens on arrival makes it a blank.
            final Card token = TokenAi.spawnToken(ai, tokenSa);
            if (token == null || !token.isCreature() || token.getNetToughness() < 1) {
                return new AiAbilityDecision(0, AiPlayDecision.CantPlayAi);
            }

            sa.getTargets().add(best);
            if (!sa.isTargetNumberValid()) {
                sa.resetTargets();
                return new AiAbilityDecision(0, AiPlayDecision.TargetingFailed);
            }
            return new AiAbilityDecision(100, AiPlayDecision.WillPlay);
        }
    }

    // Cursed Scroll
    public static class CursedScroll {
        public static boolean consider(final Player ai, final SpellAbility sa) {
            CardCollectionView hand = ai.getCardsIn(ZoneType.Hand);
            if (hand.isEmpty()) {
                return false;
            }

            // For now, see if all cards in hand have the same name, and then proceed if true
            return CardLists.filter(hand, CardPredicates.nameEquals(hand.getFirst().getName())).size() == hand.size();
        }

        public static String chooseCard(final Player ai, final SpellAbility sa) {
            int maxCount = 0;
            Card best = null;
            CardCollectionView hand = ai.getCardsIn(ZoneType.Hand);

            for (Card c : ai.getCardsIn(ZoneType.Hand)) {
                int count = CardLists.filter(hand, CardPredicates.nameEquals(c.getName())).size();
                if (count > maxCount) {
                    maxCount = count;
                    best = c;
                }
            }

            return best != null ? best.getName() : "";
        }
    }

    public static class PithingNeedle {
        // TODO Build out exclusion list based off cards in my deck and cards that other needles have chosen
        public static String chooseCard(final Player ai, final SpellAbility sa) {
            String keyCardChoice = chooseCardViaKeyCard(ai, sa);
            if (keyCardChoice != null) {
                return keyCardChoice;
            }

            String choice = chooseCardViaScoring(ai, sa);
            if (choice != null) {
                return choice;
            }
            return chooseNonBattlefieldName();
        }

        // Helper method to score a card's abilities and static effects
        // Used by both chooseCardViaKeyCard and chooseCardViaScoring
        private static int scoreCardAbilities(final Card c, boolean skipManaAbilities) {
            int score = 0;

            for (SpellAbility ab : c.getSpellAbilities()) {
                if (!ab.isActivatedAbility()) {
                    continue;
                }
                if (skipManaAbilities && ab.isManaAbility()) {
                    continue;
                }

                // Alter this score based off the ApiType
                switch (ab.getApi()) {
                    case Destroy:
                        score += 20;
                        break;
                    case DamageAll:
                    case DestroyAll:
                        score += 30;
                        break;
                    case WinsGame:
                    case LosesGame:
                        score += 50;
                        break;
                    case Draw:
                        score += 10;
                        break;
                    case GainControl:
                    case Play:
                    case DealDamage:
                        score += 15;
                        break;
                    case ChangeZone:
                        if (ab.getParam("Destination") != null && ab.getParam("Destination").equals("Battlefield")) {
                            score += 15;
                        } else {
                            score += 5;
                        }
                        break;
                    default:
                        score += 5;
                }

                score += 10;

                // Give higher score to cheaper abilities, as they are more likely to be used and thus worth naming
                if (ab.getPayCosts().getCostMana() != null) {
                    if (ab.getPayCosts().hasXInAnyCostPart()) {
                        score += 15;
                    } else {
                        Integer convertedAmount = ab.getPayCosts().getCostMana().convertAmount();
                        if (convertedAmount != null) {
                            score += Math.max(0, 20 - Math.pow(convertedAmount, 2));
                        }
                    }
                }
                if (ab.getPayCosts().hasSpecificCostType(CostSacrifice.class)) {
                    score += 10;
                }
            }

            for (StaticAbility st : c.getStaticAbilities()) {
                if (st.hasParam("GainsAbilitiesOf") && st.getParamOrDefault("Affected", "Self").contains("Self")) {
                    score += 10;
                }

                if (st.hasParam("AddAbility") && st.getParamOrDefault("Affected", "Self").contains("Self")) {
                    score += 10;
                }
            }

            return score;
        }

        public static String chooseCardViaKeyCard(final Player ai, final SpellAbility sa) {
            boolean skipManaAbilities = sa.getParam("AILogic").equals("PithingNeedle");
            boolean skipLands = sa.getParam("AILogic").equals("PhyrexianRevoker");
            boolean knowHand = sa.getParam("AILogic").equals("SorcerousSpyglass");

            String bestKeyCard = null;
            int bestScore = Integer.MIN_VALUE;

            for (Player opp : ai.getOpponents()) {
                List<String> keyCards = opp.getRegisteredPlayer().getDeck().getKeyCards();

                for (Card c : opp.getAllCards()) {
                    String name = c.getName();
                    if (!keyCards.contains(name)) {
                        continue;
                    }

                    // Skip lands if required
                    if (skipLands && c.isLand()) {
                        continue;
                    }

                    // Base score for key cards
                    int score = 100;

                    // Add ability-based scoring
                    score += scoreCardAbilities(c, skipManaAbilities);

                    if (score == 100) {
                        // No activated abilities found, skip this key card
                        continue;
                    }

                    // Bonus for cards on battlefield (more likely to be a key card in play)
                    if (c.isInZone(ZoneType.Battlefield)) {
                        score += 20;
                    }

                    if (knowHand && c.isInZone(ZoneType.Hand)) {
                        score += 8;
                    }

                    if (score > bestScore) {
                        bestScore = score;
                        bestKeyCard = name;
                    }
                }
            }

            return bestKeyCard;
        }

        public static String chooseNonBattlefieldName() {
            return "Liliana of the Veil";
        }


        public static String chooseCardViaScoring(final Player ai, final SpellAbility sa) {
            // Look through opponents' known zones (library, hand, graveyard, exile) for dangerous
            // cards to name with Pithing Needle. Prefer planeswalkers, otherwise any card that
            // has a non-trigger, non-mana SpellAbility (activated/static abilities that are relevant).
            final Map<String, Integer> nameToScore = new HashMap<>();
            boolean skipManaAbilities = sa.getParam("AILogic").equals("PithingNeedle");
            boolean skipLands = sa.getParam("AILogic").equals("PhyrexianRevoker");
            boolean knowHand = sa.getParam("AILogic").equals("SorcerousSpyglass");

            for (Player opp : ai.getOpponents()) {
                for (Card c : opp.getAllCards()) {
                    if (skipLands && c.isLand()) {
                        continue;
                    }

                    String name = c.getName();
                    int score = scoreCardAbilities(c, skipManaAbilities);

                    if (score == 0) {
                        continue;
                    }

                    score += c.isInZone(ZoneType.Battlefield) ? 10 : 0;
                    if (knowHand && c.isInZone(ZoneType.Hand)) {
                        score += 5;
                    }

                    if (nameToScore.containsKey(name)) {
                        nameToScore.put(name, nameToScore.get(name) + score);
                    } else {
                        nameToScore.put(name, score);
                    }
                }
            }

            for (Card n : CardLists.filter(ai.getGame().getCardsIn(ZoneType.Battlefield), CardPredicates.nameEquals("Pithing Needle"))) {
                String named = n.getNamedCard();
                if (named != null && !named.isEmpty()) {
                    if (nameToScore.containsKey(named)) {
                        nameToScore.put(named, nameToScore.get(named) - 10);
                    } else {
                        nameToScore.put(named, -10);
                    }
                }
            }

            for (Card c : ai.getAllCards()) {
                String name = c.getName();
                int score = c.isInZone(ZoneType.Battlefield) ? -10 : -4;

                if (nameToScore.containsKey(name)) {
                    nameToScore.put(name, nameToScore.get(name) + score);
                } else {
                    nameToScore.put(name, score);
                }
            }

            if (nameToScore.isEmpty()) {
                return null;
            }

            return nameToScore.entrySet().stream().max(Map.Entry.comparingByValue()).get().getKey();
        }
    }

    // Day of the Dragons - judged from its ETB exile trigger (ChangeZoneAllAi.doTriggerNoCost,
    // non-mandatory only): trade our creatures for the same number of 5/5 flying Dragons
    // only when that is a clear upgrade. Reads game state only: no token prototype (TokenDb
    // draws from the seeded RNG and consumes a card id) and no random draw.
    public static class DayOfTheDragons {
        // CreatureEvaluator on the r_5_5_dragon_flying token: 80 base + 0 (token) + 5*15 power
        // + 5*10 toughness + 0 cmc + 5*10 flying + 1 untapped = 256.
        private static final int DRAGON_TOKEN_VALUE = 256;

        public static AiAbilityDecision consider(final Player ai, final SpellAbility sa) {
            final PhaseHandler ph = ai.getGame().getPhaseHandler();
            // The Dragons are summoning sick: never convert creatures that could still attack this turn.
            if (ph.isPlayerTurn(ai) && ph.getPhase().isBefore(PhaseType.MAIN2)) {
                return new AiAbilityDecision(0, AiPlayDecision.TimingRestrictions);
            }
            // Exactly what the trigger will exile; never fire for nothing.
            final CardCollectionView ours = AbilityUtils.filterListByType(
                    ai.getCardsIn(ZoneType.Battlefield), sa.getParam("ChangeType"), sa);
            final int n = ours.size();
            if (n < 2) {
                return new AiAbilityDecision(0, AiPlayDecision.CantPlayAi);
            }
            // A creature holding cards it exiled (Angel of Serenity, Banisher Priest) would hand them back.
            if (ours.anyMatch(c -> c.hasExiledCard())) {
                return new AiAbilityDecision(0, AiPlayDecision.CantPlayAi);
            }
            final int gain = n * DRAGON_TOKEN_VALUE - ComputerUtilCard.evaluateCreatureList(ours);
            if (gain < AiProfileUtil.getIntProperty(ai, AiProps.BOUNCE_ALL_ELSEWHERE_CREAT_EVAL_DIFF)) {
                return new AiAbilityDecision(0, AiPlayDecision.CantPlayAi);
            }
            // Affordability pre-filter (an overestimate: tapped sources count). A WillPlay here sends
            // the cast on to ComputerUtilCost.canPayCost, whose mana-source reservation check draws
            // from the seeded RNG; without this, every unaffordable pass would draw where the
            // stock veto drew nothing.
            if (ComputerUtilMana.getAvailableManaEstimate(ai, false) < sa.getHostCard().getCMC()) {
                return new AiAbilityDecision(0, AiPlayDecision.CantAfford);
            }
            return new AiAbilityDecision(100, AiPlayDecision.WillPlay);
        }
    }

    // Day of the Moon
    // "I, II, III - Choose a creature card name, then goad all creatures with a name
    // chosen for this Saga." AiController.checkETBEffects asks chapter I's NameCard
    // non-mandatorily before the cast (its Saga branch) and ChooseCardNameAi answered
    // CantPlayAi to every such ask; the stock resolution chooser (no AILogic) ignored
    // ValidCards and named any opposing nonland card in any zone. One chooser serves
    // the cast decision and every chapter. A name qualifies when it is a legal
    // creature card name on an opposing creature, not yet chosen for this Saga, and on
    // no card the AI owns or controls in any zone (names accumulate, so a later chapter
    // would goad our own copy). Opposing copies that cannot attack, or are already
    // goaded by us, are ignored; every other copy must fit a tier or the name is
    // dropped. Tier A: in multiplayer it can attack another opponent; in 1v1 one of our
    // untapped creatures can block it alone, kill it and survive. Tier B (1v1 only): no
    // vigilance, infect, annihilator or attack trigger, untapped now, we have an
    // attacker for our next turn, the opposing board's unblocked damage leaves us above
    // the danger threshold, and the name's own unblocked damage is below our life.
    // Highest summed evaluateCreature wins, tier A before tier B; ties go to the first
    // name alphabetically. RNG-free, like the stock refusal it replaces:
    // ComputerUtilCard.canBeBlockedProfitably and ComputerUtil.aiLifeInDanger both run
    // an AiBlockController (lifeInDanger's threshold roll, random trade blocks), and
    // abilities-on combat predictions reach canPayCost's mana-reservation roll, so
    // single-blocker canDestroyAttacker/canDestroyBlocker and damageIfUnblocked run
    // withoutAbilities, and an unaffordable pass is refused before any of it.
    public static class DayOfTheMoon {
        public static AiAbilityDecision consider(final Player ai, final SpellAbility sa) {
            // An approval goes on to ComputerUtilCost.canPayCost, whose mana-source
            // reservation roll the stock veto never reached (as DayOfTheDragons).
            if (ComputerUtilMana.getAvailableManaEstimate(ai, false) < sa.getHostCard().getCMC()) {
                return new AiAbilityDecision(0, AiPlayDecision.CantAfford);
            }
            final Predicate<ICardFace> legal = CardFacePredicates.valid(sa.getParamOrDefault("ValidCards", "Creature"));
            return chooseName(ai, sa, legal) != null
                    ? new AiAbilityDecision(100, AiPlayDecision.WillPlay)
                    : new AiAbilityDecision(0, AiPlayDecision.CantPlayAi);
        }

        // null = no name clears the floor
        public static String chooseName(final Player ai, final SpellAbility sa, final Predicate<ICardFace> legal) {
            final Card host = sa.getHostCard();
            final Game game = ai.getGame();
            final PlayerCollection opps = ai.getOpponents();
            final boolean multiplayer = game.getPlayers().size() > 2;
            final Set<String> ownNames = ownNames(ai);
            final CardCollectionView ours = ai.getCreaturesInPlay();

            boolean safeToTapBlockers = false;
            if (!multiplayer) {
                boolean weCanAttack = false;
                for (final Card o : ours) {
                    if (ComputerUtilCombat.canAttackNextTurn(o)) {
                        weCanAttack = true;
                        break;
                    }
                }
                int incoming = 0;
                for (final Player opp : opps) {
                    for (final Card att : opp.getCreaturesInPlay()) {
                        if (ComputerUtilCombat.canAttackNextTurn(att, ai)) {
                            incoming += ComputerUtilCombat.damageIfUnblocked(att, ai, null, true);
                        }
                    }
                }
                safeToTapBlockers = weCanAttack
                        && incoming + AiProfileUtil.getIntProperty(ai, AiProps.AI_IN_DANGER_MAX_THRESHOLD) < ai.getLife();
            }

            final Map<String, Integer> tierA = new TreeMap<>();
            final Map<String, Integer> tierB = new TreeMap<>();
            final Map<String, Integer> damageB = new HashMap<>(); // looked up by name only
            final Set<String> dropped = new HashSet<>();          // looked up by name only
            for (final Card c : CardLists.filterControlledBy(game.getCardsIn(ZoneType.Battlefield), opps)) {
                if (!c.isCreature()) {
                    continue;
                }
                final String name = c.getName();
                if (name.isEmpty() || host.getNamedCards().contains(name) || ownNames.contains(name)
                        || ours.anyMatch(o -> o.sharesNameWith(name))) {
                    continue;
                }
                final ICardFace face = StaticData.instance().getCommonCards().getFaceByName(name);
                if (face == null || !legal.test(face)) {
                    continue; // not a creature card name (e.g. a plain "Soldier" token)
                }
                if (ComputerUtilCard.isUselessCreature(ai, c) || c.isGoadedBy(ai)) {
                    continue; // goading this copy forces nothing new
                }
                if (multiplayer) {
                    if (canAttackAnotherOpponent(ai, c)) {
                        tierA.merge(name, ComputerUtilCard.evaluateCreature(c), Integer::sum);
                    } else if (ComputerUtilCombat.canAttackNextTurn(c)) {
                        dropped.add(name); // it could only be forced into us
                    }
                    continue;
                }
                if (!ComputerUtilCombat.canAttackNextTurn(c)) {
                    continue;
                }
                if (hasSafeKillingBlock(ai, c)) {
                    tierA.merge(name, ComputerUtilCard.evaluateCreature(c), Integer::sum);
                } else if (safeToTapBlockers && c.isUntapped() && !c.hasKeyword(Keyword.VIGILANCE)
                        && !c.hasKeyword(Keyword.INFECT) && !hasAttackTrigger(c)) {
                    tierB.merge(name, ComputerUtilCard.evaluateCreature(c), Integer::sum);
                    damageB.merge(name, ComputerUtilCombat.damageIfUnblocked(c, ai, null, true), Integer::sum);
                } else {
                    dropped.add(name);
                }
            }
            for (final Map.Entry<String, Integer> e : damageB.entrySet()) {
                if (e.getValue() >= ai.getLife()) {
                    dropped.add(e.getKey());
                }
            }
            tierA.keySet().removeAll(dropped);
            tierB.keySet().removeAll(dropped);
            tierB.keySet().removeAll(tierA.keySet());
            final String best = best(tierA);
            return best != null ? best : best(tierB);
        }

        // A mandatory chapter with nothing worth goading: a name that adds no creature.
        public static String harmlessName(final Player ai, final SpellAbility sa, final Predicate<ICardFace> legal) {
            final List<String> named = sa.getHostCard().getNamedCards();
            if (!named.isEmpty()) {
                return named.get(0); // already chosen for this Saga: goads nothing new
            }
            final Set<String> ownNames = ownNames(ai);
            final CardCollectionView field = ai.getGame().getCardsIn(ZoneType.Battlefield);
            for (final PaperCard pc : StaticData.instance().getCommonCards().getUniqueCards()) {
                final ICardFace face = pc.getRules().getMainPart();
                final String name = face.getName();
                if (legal.test(face) && !ownNames.contains(name) && !field.anyMatch(c -> c.sharesNameWith(name))) {
                    return name;
                }
            }
            return "Morphling";
        }

        private static Set<String> ownNames(final Player ai) {
            final Set<String> names = new HashSet<>();
            for (final Card c : ai.getAllCards()) {
                names.add(c.getName());
            }
            return names;
        }

        private static boolean canAttackAnotherOpponent(final Player ai, final Card c) {
            for (final Player o : ai.getOpponents()) {
                if (!o.equals(c.getController()) && ComputerUtilCombat.canAttackNextTurn(c, o)) {
                    return true;
                }
            }
            return false;
        }

        // One of our creatures, untapped now (it stays tapped through the opponent's
        // turn), can block it alone, kills it and survives. Abilities off: RNG-free.
        private static boolean hasSafeKillingBlock(final Player ai, final Card attacker) {
            if (!CombatUtil.canBeBlocked(attacker, null, ai) || !CombatUtil.canAttackerBeBlockedWithAmount(attacker, 1, ai)) {
                return false;
            }
            for (final Card b : ai.getCreaturesInPlay()) {
                if (CombatUtil.canBlock(attacker, b)
                        && ComputerUtilCombat.canDestroyAttacker(ai, attacker, b, null, true)
                        && !ComputerUtilCombat.canDestroyBlocker(ai, b, attacker, null, true)) {
                    return true;
                }
            }
            return false;
        }

        private static boolean hasAttackTrigger(final Card c) {
            if (c.hasKeyword(Keyword.ANNIHILATOR)) {
                return true;
            }
            for (final Trigger t : c.getTriggers()) {
                if (TriggerType.Attacks.equals(t.getMode())) {
                    return true;
                }
            }
            return false;
        }

        // TreeMap iteration: strict > keeps the alphabetically first name on a tie.
        private static String best(final Map<String, Integer> scores) {
            String best = null;
            int bestScore = Integer.MIN_VALUE;
            for (final Map.Entry<String, Integer> e : scores.entrySet()) {
                if (e.getValue() > bestScore) {
                    best = e.getKey();
                    bestScore = e.getValue();
                }
            }
            return best;
        }
    }

    // Deathgorge Scavenger
    public static class DeathgorgeScavenger {
        public static boolean consider(final Player ai, final SpellAbility sa) {
            Card worstCreat = ComputerUtilCard.getWorstAI(CardLists.filter(ai.getOpponents().getCardsIn(ZoneType.Graveyard), CardPredicates.CREATURES));
            Card worstNonCreat = ComputerUtilCard.getWorstAI(CardLists.filter(ai.getOpponents().getCardsIn(ZoneType.Graveyard), CardPredicates.NON_CREATURES));
            if (worstCreat == null) {
                worstCreat = ComputerUtilCard.getWorstAI(CardLists.filter(ai.getCardsIn(ZoneType.Graveyard), CardPredicates.CREATURES));
            }
            if (worstNonCreat == null) {
                worstNonCreat = ComputerUtilCard.getWorstAI(CardLists.filter(ai.getCardsIn(ZoneType.Graveyard), CardPredicates.NON_CREATURES));
            }

            sa.resetTargets();
            if (worstCreat != null && ai.getLife() <= ai.getStartingLife() / 4) {
                sa.getTargets().add(worstCreat);
            } else if (worstNonCreat != null && ai.getGame().getCombat() != null
                    && ai.getGame().getCombat().isAttacking(sa.getHostCard())) {
                sa.getTargets().add(worstNonCreat);
            } else if (worstCreat != null) {
                sa.getTargets().add(worstCreat);
            }

            return sa.getTargets().size() > 0;
        }
    }

    // Demonic Consultation - cast only in response to our own Thassa's Oracle
    // ETB trigger: the trigger then resolves against an empty library and its
    // WinsGame sub fires (X = devotion >= Y = 0). Casting in any other window
    // exiles the library with no win attached, so the guard is the whole point.
    public static class DemonicConsultation {
        public static AiAbilityDecision consider(final Player ai, final SpellAbility sa) {
            if (ai.cantWin() || ai.getCardsIn(ZoneType.Library).isEmpty()) {
                return new AiAbilityDecision(0, AiPlayDecision.CantPlayAi);
            }
            for (SpellAbilityStackInstance si : ai.getGame().getStack()) {
                SpellAbility stackSa = si.getSpellAbility();
                if (stackSa != null && stackSa.isTrigger()
                        && ai.equals(stackSa.getActivatingPlayer())
                        && stackSa.getHostCard() != null
                        && "Thassa's Oracle".equals(stackSa.getHostCard().getName())) {
                    return new AiAbilityDecision(100, AiPlayDecision.WillPlay);
                }
            }
            return new AiAbilityDecision(0, AiPlayDecision.CantPlayAi);
        }
    }

    // Desecration Demon
    public static class DesecrationDemon {
        private static final int demonSacThreshold = Integer.MAX_VALUE; // if we're in dire conditions, sac everything from worst to best hoping to find an answer

        public static boolean considerSacrificingCreature(final Player ai, final SpellAbility sa) {
            Card c = sa.getHostCard();

            // Only check for sacrifice if it's the owner's turn, and it can attack.
            // TODO: Maybe check if sacrificing a creature allows AI to kill the opponent with the rest on their turn?
            if (!CombatUtil.canAttack(c) ||
                    !ai.getGame().getPhaseHandler().isPlayerTurn(sa.getActivatingPlayer())) {
                return false;
            }

            CardCollection flyingCreatures = CardLists.filter(ai.getCardsIn(ZoneType.Battlefield),
                    CardPredicates.UNTAPPED.and(
                            CardPredicates.hasKeyword(Keyword.FLYING).or(CardPredicates.hasKeyword(Keyword.REACH))));
            boolean hasUsefulBlocker = false;

            for (Card fc : flyingCreatures) {
                if (!ComputerUtilCard.isUselessCreature(ai, fc)) {
                    hasUsefulBlocker = true;
                    break;
                }
            }

            return ai.getLife() <= c.getNetPower() && !hasUsefulBlocker;
        }

        public static int getSacThreshold() {
            return demonSacThreshold;
        }
    }

    // Donate
    public static class Donate {
        public static AiAbilityDecision considerTargetingOpponent(final Player ai, final SpellAbility sa) {
            final Card donateTarget = ComputerUtil.getCardPreference(ai, sa.getHostCard(), "DonateMe", CardLists.filter(
                    ai.getCardsIn(ZoneType.Battlefield).threadSafeIterable(), CardPredicates.hasSVar("DonateMe")));
            if (donateTarget != null) {
                // first filter for opponents which can be targeted by SA
                PlayerCollection oppList = ai.getOpponents().filter(PlayerPredicates.isTargetableBy(sa));

                // All opponents have hexproof or something like that
                if (oppList.isEmpty()) {
                    return new AiAbilityDecision(0, AiPlayDecision.TargetingFailed);
                }

                // filter for player who does not have donate target already
                PlayerCollection oppTarget = oppList.filter(PlayerPredicates.isNotCardInPlay(donateTarget.getName()));
                // fall back to previous list
                if (oppTarget.isEmpty()) {
                    oppTarget = oppList;
                }

                // select player with less lands on the field (helpful for Illusions of Grandeur and probably Pacts too)
                Player opp = Collections.min(oppTarget,
                        PlayerPredicates.compareByZoneSize(ZoneType.Battlefield, CardPredicates.LANDS));

                if (opp != null) {
                    sa.resetTargets();
                    sa.getTargets().add(opp);
                }
                return new AiAbilityDecision(100, AiPlayDecision.WillPlay);
            }
            // No targets found to donate, so do nothing.
            return new AiAbilityDecision(0, AiPlayDecision.TargetingFailed);
        }

        public static AiAbilityDecision considerDonatingPermanent(final Player ai, final SpellAbility sa) {
            Card donateTarget = ComputerUtil.getCardPreference(ai, sa.getHostCard(), "DonateMe", CardLists.filter(ai.getCardsIn(ZoneType.Battlefield).threadSafeIterable(), CardPredicates.hasSVar("DonateMe")));
            if (donateTarget != null) {
                sa.resetTargets();
                sa.getTargets().add(donateTarget);
                return new AiAbilityDecision(100, AiPlayDecision.WillPlay);
            }

            // Should never get here because targetOpponent, called before targetPermanentToDonate, should already have made the AI bail
            System.err.println("Warning: Donate AI failed at SpecialCardAi.Donate#targetPermanentToDonate despite successfully targeting an opponent first.");
            return new AiAbilityDecision(0, AiPlayDecision.TargetingFailed);
        }
    }

    // Electric Seaweed
    // "When this creature enters, until end of turn, whenever another creature
    // dies, this creature deals 1 damage to each non-Wall creature."
    // Reached from EffectAi.checkApiLogic's AILogic$ ElectricSeaweed branch (the
    // ETB Effect sub that AiController.checkETBEffects consults before the cast)
    // after the randomReturn roll, and EffectAi.doTriggerNoCost runs that consult
    // once for this logic, so each consult draws the one roll the stock no-AILogic
    // refusal drew. The chain is symmetric and feeds itself: every death it causes
    // deals another point to each non-Wall creature on both sides, and its likeliest
    // first death is our own hasty ping on an X/1 in main 2. Never before combat
    // damage is over (combat seeds and widens the chain beyond any snapshot, and the
    // pinger loses nothing by waiting). Then run the chain to its fixpoint on the
    // current battlefield, walls and indestructible creatures excluded, under two
    // seeds: a death from outside the pool (1 + deaths so far) and a pool creature's
    // own death (deaths so far, at least 1). The outside seed kills a superset of the
    // pool seed: WillPlay when it kills none of our creatures; otherwise WillPlay only
    // when both chains kill strictly more opposing creature value than ours, else
    // CantPlayAi (never ping their lone 1/1 and lose our tokens). No RNG of its own.
    public static class ElectricSeaweed {
        public static AiAbilityDecision consider(final Player ai, final SpellAbility sa) {
            final Card host = sa.getHostCard();
            if (host == null) {
                return new AiAbilityDecision(0, AiPlayDecision.CantPlayAi);
            }
            final Game game = ai.getGame();
            final PhaseHandler ph = game.getPhaseHandler();
            if (ph.getPhase() == null || ph.getPhase().isBefore(PhaseType.COMBAT_END)) {
                return new AiAbilityDecision(0, AiPlayDecision.AnotherTime);
            }
            // An approval goes on to canPlayAndPayForFace's canPayCost, whose test payment draws
            // MyRandom (ComputerUtilMana.isManaSourceReserved); the stock BadEtbEffects veto never
            // reached it, so an unaffordable WillPlay desynced B from A (games 450813, 477566).
            // RNG-free first: untapped mana for the mana value, and red sources this spell may spend.
            if (ComputerUtilMana.getAvailableManaEstimate(ai, true) < host.getCMC() || !hasRedSources(ai, host)) {
                return new AiAbilityDecision(0, AiPlayDecision.CantAfford);
            }
            CardCollection pool = CardLists.getValidCards(game.getCardsIn(ZoneType.Battlefield),
                    "Creature.nonWall", ai, host, sa);
            pool = CardLists.getNotKeyword(pool, Keyword.INDESTRUCTIBLE);

            final CardCollection deadExternal = chain(pool, host, true);
            final CardCollection oursExternal = CardLists.filterControlledBy(deadExternal, ai);
            if (oursExternal.isEmpty()) {
                return new AiAbilityDecision(100, AiPlayDecision.WillPlay);
            }
            if (ComputerUtilCard.evaluateCreatureList(CardLists.filterControlledBy(deadExternal, ai.getOpponents()))
                    <= ComputerUtilCard.evaluateCreatureList(oursExternal)) {
                return new AiAbilityDecision(0, AiPlayDecision.CantPlayAi);
            }
            final CardCollection deadInPool = chain(pool, host, false);
            if (ComputerUtilCard.evaluateCreatureList(CardLists.filterControlledBy(deadInPool, ai.getOpponents()))
                    <= ComputerUtilCard.evaluateCreatureList(CardLists.filterControlledBy(deadInPool, ai))) {
                return new AiAbilityDecision(0, AiPlayDecision.CantPlayAi);
            }
            return new AiAbilityDecision(100, AiPlayDecision.WillPlay);
        }

        // Enough untapped red for RR: floating red plus sources whose printed production could be
        // red and whose mana this spell may spend (Turtle Lair's Ninja/Turtle-only mana is not).
        private static boolean hasRedSources(final Player ai, final Card host) {
            final ManaCost cost = host.getManaCost();
            final int needed = cost == null ? 0 : cost.getShardCount(forge.card.mana.ManaCostShard.RED);
            final SpellAbility spell = host.getFirstSpellAbility();
            int red = ai.getManaPool().getAmountOfColor(MagicColor.RED);
            for (final Card src : ai.getCardsIn(ZoneType.Battlefield)) {
                if (red >= needed) {
                    break;
                }
                for (final SpellAbility ma : src.getManaAbilities()) {
                    ma.setActivatingPlayer(ai);
                    if (ma.getManaPart() == null || !ma.canPlay()) {
                        continue;
                    }
                    if (spell != null && !ma.getManaPart().meetsManaRestrictions(spell)) {
                        continue;
                    }
                    final String produced = ma.getManaPart().getOrigProduced();
                    if (produced.contains("R") || produced.contains("Any") || produced.contains("Chosen")
                            || produced.startsWith("Combo")) {
                        red++;
                        break;
                    }
                }
            }
            return red >= needed;
        }

        // After k deaths every surviving pool creature has taken k damage, plus one
        // for the seed's death when the seed came from outside the pool.
        private static CardCollection chain(final CardCollection pool, final Card host, final boolean externalSeed) {
            final CardCollection dead = new CardCollection();
            boolean grew = true;
            while (grew) {
                grew = false;
                final int dmg = Math.max(1, dead.size() + (externalSeed ? 1 : 0));
                for (final Card c : pool) {
                    if (!dead.contains(c) && ComputerUtilCombat.predictDamageTo(c, dmg, host, false)
                            >= ComputerUtilCombat.getDamageToKill(c, false)) {
                        dead.add(c);
                        grew = true;
                    }
                }
            }
            return dead;
        }
    }

    // Electrostatic Pummeler
    public static class ElectrostaticPummeler {
        public static AiAbilityDecision consider(final Player ai, final SpellAbility sa) {
            final Card source = sa.getHostCard();
            Game game = ai.getGame();
            Combat combat = game.getCombat();
            Pair<Integer, Integer> predictedPT = getPumpedPT(ai, source.getNetCombatDamage(), source.getNetToughness());

            // Try to save the Pummeler from death by pumping it if it's threatened with a damage spell
            if (ComputerUtil.predictThreatenedObjects(ai, null, true).contains(source)) {
                SpellAbility saTop = game.getStack().peekAbility();

                if (saTop.getApi() == ApiType.DealDamage || saTop.getApi() == ApiType.DamageAll) {
                    int dmg = AbilityUtils.calculateAmount(saTop.getHostCard(), saTop.getParam("NumDmg"), saTop);
                    if (source.getNetToughness() - source.getDamage() <= dmg && predictedPT.getRight() - source.getDamage() > dmg)
                        return new AiAbilityDecision(100, AiPlayDecision.WillPlay);
                }
            }

            // Do not activate if damage will be prevented
            if (source.staticDamagePrevention(predictedPT.getLeft(), 0, source, true) == 0) {
                return new AiAbilityDecision(0, AiPlayDecision.DoesntImpactGame);
            }

            // Activate Electrostatic Pummeler's pump only as a combat trick
            if (game.getPhaseHandler().is(PhaseType.COMBAT_BEGIN)) {
                if (predictOverwhelmingDamage(ai, sa)) {
                    // We'll try to deal lethal trample/unblocked damage, so remember the card for attack
                    // and wait until declare blockers step.
                    AiCardMemory.rememberCard(ai, source, AiCardMemory.MemorySet.MANDATORY_ATTACKERS);
                    return new AiAbilityDecision(0, AiPlayDecision.CantPlayAi);
                }
            } else if (!game.getPhaseHandler().is(PhaseType.COMBAT_DECLARE_BLOCKERS)) {
                return new AiAbilityDecision(0, AiPlayDecision.WaitForCombat);
            }

            if (combat == null || !(combat.isAttacking(source) || combat.isBlocking(source))) {
                return new AiAbilityDecision(0, AiPlayDecision.CantPlayAi);
            }

            boolean isBlocking = combat.isBlocking(source);
            boolean cantDie = ComputerUtilCombat.combatantCantBeDestroyed(ai, source);

            CardCollection opposition = isBlocking ? combat.getAttackersBlockedBy(source) : combat.getBlockers(source);
            int oppP = Aggregates.sum(opposition, Card::getNetCombatDamage);
            int oppT = Aggregates.sum(opposition, Card::getNetToughness);

            boolean oppHasFirstStrike = false;
            boolean oppCantDie = true;
            boolean unblocked = opposition.isEmpty();
            boolean canTrample = source.hasKeyword(Keyword.TRAMPLE);

            if (!isBlocking && combat.getDefenderByAttacker(source) instanceof Card) {
                int loyalty = combat.getDefenderByAttacker(source).getCounters(CounterEnumType.LOYALTY);
                int totalDamageToPW = 0;
                for (Card atk : combat.getAttackersOf(combat.getDefenderByAttacker(source))) {
                    if (combat.isUnblocked(atk)) {
                        totalDamageToPW += atk.getNetCombatDamage();
                    }
                }
                if (totalDamageToPW >= oppT + loyalty) {
                    // Already enough damage to take care of the planeswalker
                    return new AiAbilityDecision(0, AiPlayDecision.DoesntImpactCombat);
                }
                if ((unblocked || canTrample) && predictedPT.getLeft() >= oppT + loyalty) {
                    // Can pump to kill the planeswalker, go for it
                    return new AiAbilityDecision(100, AiPlayDecision.ImpactCombat);
                }

            }

            for (Card c : opposition) {
                if (c.hasKeyword(Keyword.FIRST_STRIKE) || c.hasKeyword(Keyword.DOUBLE_STRIKE)) {
                    oppHasFirstStrike = true;
                }
                if (!ComputerUtilCombat.combatantCantBeDestroyed(c.getController(), c)) {
                    oppCantDie = false;
                }
            }

            if (!isBlocking) {
                int oppLife = combat.getDefendingPlayerRelatedTo(source).getLife();
                if (((unblocked || canTrample) && (predictedPT.getLeft() - oppT > oppLife / 2))
                        || (canTrample && predictedPT.getLeft() - oppT > 0 && predictedPT.getRight() > oppP)) {
                    // We can deal a lot of damage (either a lot of damage directly to the opponent,
                    // or kill the blocker(s) and damage the opponent at the same time, so go for it
                    AiCardMemory.rememberCard(ai, source, AiCardMemory.MemorySet.MANDATORY_ATTACKERS);
                    return new AiAbilityDecision(100, AiPlayDecision.ImpactCombat);
                }
            }

            if (predictedPT.getRight() - source.getDamage() <= oppP && oppHasFirstStrike && !cantDie) {
                // Can't survive first strike or double strike, don't pump
                return new AiAbilityDecision(0, AiPlayDecision.DoesntImpactCombat);
            }
            if (predictedPT.getLeft() < oppT && (!cantDie || predictedPT.getRight() - source.getDamage() <= oppP)) {
                // Can't pump enough to kill the blockers and survive, don't pump
                return new AiAbilityDecision(0, AiPlayDecision.DoesntImpactCombat);
            }
            if (source.getNetCombatDamage() > oppT && source.getNetToughness() > oppP) {
                // Already enough to kill the blockers and survive, don't overpump
                return new AiAbilityDecision(0, AiPlayDecision.DoesntImpactCombat);
            }
            if (oppCantDie && !source.hasKeyword(Keyword.TRAMPLE) && !source.isWitherDamage()
                    && predictedPT.getLeft() <= oppT) {
                // Can't kill or cripple anyone, as well as can't Trample over, so don't pump
                return new AiAbilityDecision(0, AiPlayDecision.DoesntImpactCombat);
            }

            // If we got here, it should be a favorable combat pump, resulting in at least one
            // opposing creature dying, and hopefully with the Pummeler surviving combat.
            return new AiAbilityDecision(100, AiPlayDecision.ImpactCombat);
        }

        public static boolean predictOverwhelmingDamage(final Player ai, final SpellAbility sa) {
            final Card source = sa.getHostCard();
            int oppLife = ai.getWeakestOpponent().getLife();
            CardCollection oppInPlay = ai.getWeakestOpponent().getCreaturesInPlay();
            CardCollection potentialBlockers = new CardCollection();

            for (Card b : oppInPlay) {
                if (CombatUtil.canBlock(source, b)) {
                    potentialBlockers.add(b);
                }
            }

            Pair<Integer, Integer> predictedPT = getPumpedPT(ai, source.getNetCombatDamage(), source.getNetToughness());
            int oppT = Aggregates.sum(potentialBlockers, Card::getNetToughness);

            return potentialBlockers.isEmpty() || (source.hasKeyword(Keyword.TRAMPLE) && predictedPT.getLeft() - oppT >= oppLife);
        }

        public static Pair<Integer, Integer> getPumpedPT(Player ai, int power, int toughness) {
            int energy = ai.getCounters(CounterEnumType.ENERGY);
            if (energy > 0) {
                int numActivations = energy / 3;
                for (int i = 0; i < numActivations; i++) {
                    power *= 2;
                    toughness *= 2;
                }
            }

            return Pair.of(power, toughness);
        }
    }

    // Entrancing Melody
    // "X U U sorcery: gain control of target creature with mana value X" -
    // ControlGainAi never announces this X, so ValidTgts$ Creature.cmcEQX reads
    // X=0 and only mana-value-0 creatures are ever legal. Announce X ourselves
    // as the chosen creature's mana value, checking canTarget at that X per
    // candidate. Floor: an opponent's creature the stock handler would already
    // take (controllable by us, not leaving at end of turn, not AI-unsupported,
    // deals combat damage, can attack an opponent next turn) and worth at least
    // a vanilla non-token 2/2 two-drop (evaluateCreature >= 160; a 3/3 token is
    // 156). Ward only when its whole cost is mana, reserved on top of X.
    public static class EntrancingMelody {
        public static final int MIN_EVAL = 160;

        public static AiAbilityDecision consider(final Player ai, final SpellAbility sa) {
            // Routing from ControlGainAi.canPlay bypasses the base class's
            // restriction check, so mirror it here.
            if (sa.getRestrictions() != null && !sa.getRestrictions().canPlay(sa.getHostCard(), sa)) {
                return new AiAbilityDecision(0, AiPlayDecision.CantPlaySa);
            }
            sa.resetTargets();

            // Cheap pass first: no mana simulation (which can draw RNG) until a
            // candidate exists.
            final PlayerCollection opponents = ai.getOpponents();
            final CardCollection candidates = new CardCollection();
            for (Card c : opponents.getCreaturesInPlay()) {
                if (!c.canBeControlledBy(ai) || c.hasSVar("EndOfTurnLeavePlay")
                        || ComputerUtilCard.isCardRemAIDeck(c) || c.getNetCombatDamage() <= 0
                        || wardMana(c) < 0) {
                    continue;
                }
                boolean canAttack = false;
                for (Player opp : opponents) {
                    if (ComputerUtilCombat.canAttackNextTurn(c, opp)) {
                        canAttack = true;
                        break;
                    }
                }
                if (canAttack && ComputerUtilCard.evaluateCreature(c) >= MIN_EVAL) {
                    candidates.add(c);
                }
            }
            if (candidates.isEmpty()) {
                return new AiAbilityDecision(0, AiPlayDecision.CantPlayAi);
            }

            sa.setXManaCostPaid(null);
            final int maxX = ComputerUtilCost.setMaxXValue(sa, ai, false); // X = 0 is legal (tokens)
            Card best = null;
            int bestEval = Integer.MIN_VALUE;
            for (Card c : candidates) {
                final int mv = c.getCMC();
                if (mv + wardMana(c) > maxX) {
                    continue; // can't pay X and still answer a mana Ward
                }
                sa.setXManaCostPaid(mv);
                if (!sa.canTarget(c)) {
                    continue;
                }
                final int eval = ComputerUtilCard.evaluateCreature(c);
                if (best == null || eval > bestEval || (eval == bestEval && mv < best.getCMC())) {
                    best = c;
                    bestEval = eval;
                }
            }
            if (best == null) {
                sa.setXManaCostPaid(null);
                return new AiAbilityDecision(0, AiPlayDecision.TargetingFailed);
            }

            sa.setXManaCostPaid(best.getCMC());
            sa.getTargets().add(best);
            if (!sa.isTargetNumberValid()) {
                sa.resetTargets();
                sa.setXManaCostPaid(null);
                return new AiAbilityDecision(0, AiPlayDecision.TargetingFailed);
            }
            return new AiAbilityDecision(100, AiPlayDecision.WillPlay);
        }

        // Ward mana to reserve on top of X: 0 without Ward, -1 when any part of
        // the Ward cost is not mana (pay life, discard), which canPayCost judges
        // only after targeting and may refuse, leaving the card stuck on the
        // same best target at every priority.
        private static int wardMana(final Card c) {
            if (!c.hasKeyword(Keyword.WARD)) {
                return 0;
            }
            final forge.game.cost.Cost ward = ComputerUtilCard.getTotalWardCost(c);
            for (CostPart part : ward.getCostParts()) {
                if (!(part instanceof CostPartMana)) {
                    return -1;
                }
            }
            return ward.getTotalMana().getCMC();
        }
    }

    // Espers to Magicite
    // "Exile each opponent's graveyard. When you do, choose up to one target creature card exiled
    // this way. Create a token that's a copy of that card, except it's an artifact and it loses all
    // other card types." The token is a NONCREATURE artifact under our control, so two kinds of
    // printed text reliably pay off on it: its own enters trigger, and a lord static for our side.
    // The cast is judged here (ChangeZoneAllAi.canPlay, after both of that path's random draws; the
    // ImmediateTrigger sub is AILogic$ Always and trusts this), and the reflexive copy picks with the
    // same screen (CopyPermanentAi.doTriggerNoCost), so it never falls back to an unscreened pick.
    public static class EspersToMagicite {
        private static final Pattern CAST_ONLY_ETB = Pattern.compile("(?<!!)(evoked|kicked|wasCast)");
        private static final Pattern SELF_ONLY = Pattern.compile("(Card|Creature|Permanent)\\.Self(\\+[^,]*)?");
        private static final String[] SELECTORS = {"Affected", "ValidCard", "ValidCreature", "ValidAttacker", "ValidTarget"};
        private static final String[] BRANCHES = {"SubAbility", "Execute", "TrueSubAbility", "FalseSubAbility",
                "WinSubAbility", "LoseSubAbility", "RepeatSubAbility"};

        public static AiAbilityDecision consider(final Player ai, final SpellAbility sa, final CardCollectionView oppGraveyards) {
            final Game game = ai.getGame();
            final PhaseHandler ph = game.getPhaseHandler();
            if (!game.getStack().isEmpty()) {
                return new AiAbilityDecision(0, AiPlayDecision.AnotherTime);
            }
            // after our own development (main 2) or at an opponent's end step
            if (!(ph.is(PhaseType.MAIN2, ai)
                    || (ph.is(PhaseType.END_OF_TURN) && ph.getPlayerTurn().isOpponentOf(ai)))) {
                return new AiAbilityDecision(0, AiPlayDecision.AnotherTime);
            }
            if (bestPick(ai, oppGraveyards) == null) {
                return new AiAbilityDecision(0, AiPlayDecision.MissingNeededCards);
            }
            // canPlayAndPayForFace runs canPayCost only after a WillPlay, and its test payment draws
            // MyRandom (ComputerUtilMana.isManaSourceReserved). The stock path never reached it for
            // this card (its ImmediateTrigger sub vetoed first), so an unaffordable WillPlay desynced
            // B from A (game 412278). This estimate draws nothing.
            if (ComputerUtilMana.getAvailableManaEstimate(ai, false) < sa.getHostCard().getCMC()) {
                return new AiAbilityDecision(0, AiPlayDecision.CantAfford);
            }
            return new AiAbilityDecision(100, AiPlayDecision.WillPlay);
        }

        // Highest mana value (the proxy for what an enters trigger is worth) among the screened
        // creature cards; null when none passes. Judged against ai's current board, so the
        // reflexive copy re-checks what the cast saw.
        public static Card bestPick(final Player ai, final Iterable<Card> cards) {
            final CardCollection ok = new CardCollection();
            for (final Card c : cards) {
                if (c.isCreature() && !c.isCommander() && !ComputerUtilCard.isCardRemAIDeck(c)
                        && hasValueAsArtifact(ai, c) && !hasHarm(ai, c)) {
                    ok.add(c);
                }
            }
            return ok.isEmpty() ? null : ComputerUtilCard.getMostExpensivePermanentAI(ok);
        }

        // Its own non-keyword enters trigger (not one that needs the card to have been cast, and not
        // a fight, which a noncreature cannot do) that would do something on this board, or a lord
        // static naming our side. Battlefield activations and phase/cast triggers do not count: they
        // admit self-pumps, regeneration and drawback upkeeps, which are blank or worse on a
        // noncreature artifact.
        private static boolean hasValueAsArtifact(final Player ai, final Card c) {
            for (final Trigger t : c.getTriggers()) {
                if (t.getKeyword() != null || t.isSecondary() || t.getMode() != TriggerType.ChangesZone
                        || !"Battlefield".equals(t.getParam("Destination"))) {
                    continue;
                }
                final String valid = t.getParamOrDefault("ValidCard", "");
                if (valid.contains("Self") && !CAST_ONLY_ETB.matcher(valid).find()
                        && !ApiType.Fight.name().equalsIgnoreCase(rootApi(t)) && etbLive(ai, c, t)) {
                    return true;
                }
            }
            for (final StaticAbility st : c.getStaticAbilities()) {
                if (st.getKeyword() != null || st.isSecondary() || st.isCharacteristicDefining()) {
                    continue;
                }
                final String affected = st.getParam("Affected");
                if (affected != null && affected.contains("YouCtrl") && !isSelfOnly(affected)) {
                    return true;
                }
            }
            return false;
        }

        // Anything the token's text would do against its new controller. Every trigger chain is read
        // (script and keyword triggers, except Evoke's, which only fires for an evoked cast; Echo and
        // cumulative upkeep stay in), and a static that is neither characteristic-defining nor
        // self-only must name our side (YouCtrl), which refuses symmetric hosers such as Thalia,
        // Collector Ouphe, Magus of the Moon and Hushbringer. A mandatory enters target with no
        // opposing candidate is harm too: the forced target lands on our side or on the token.
        private static boolean hasHarm(final Player ai, final Card c) {
            for (final Trigger t : c.getTriggers()) {
                if (t.getKeyword() != null || t.isSecondary() || t.getMode() != TriggerType.ChangesZone
                        || !"Battlefield".equals(t.getParam("Destination"))
                        || !t.getParamOrDefault("ValidCard", "").contains("Self")
                        || t.hasParam("OptionalDecider")) {
                    continue;
                }
                final Map<String, String> p = rootParams(t);
                if (p == null || !p.containsKey("ValidTgts") || "0".equals(p.get("TargetMin"))
                        || p.get("ValidTgts").contains("YouCtrl")) {
                    continue;
                }
                if (!hasLiveTarget(ai, c, p)) {
                    return true; // Shriekmaw with no nonblack, nonartifact opposing creature (game 412131)
                }
            }
            for (final Trigger t : c.getTriggers()) {
                if (!t.isKeyword(Keyword.EVOKE) && chainHasHarm(t)) {
                    return true;
                }
            }
            for (final StaticAbility st : c.getStaticAbilities()) {
                if (st.getKeyword() != null || st.isSecondary() || st.isCharacteristicDefining()) {
                    continue;
                }
                String selector = null;
                for (final String key : SELECTORS) {
                    if (st.hasParam(key)) {
                        selector = st.getParam(key);
                        break;
                    }
                }
                if (selector == null || !(isSelfOnly(selector) || selector.contains("YouCtrl"))) {
                    return true;
                }
            }
            return false;
        }

        private static boolean isSelfOnly(final String selector) {
            for (final String part : selector.split(",")) {
                if (!SELF_ONLY.matcher(part.trim()).matches()) {
                    return false;
                }
            }
            return true;
        }

        // The params of a trigger's root ability, read the same way as rootApi (never built here).
        private static Map<String, String> rootParams(final Trigger t) {
            final SpellAbility built = t.getOverridingAbility();
            if (built != null) {
                return built.getMapParams();
            }
            final String text = t.hasParam("Execute") ? t.getSVar(t.getParam("Execute")) : "";
            return text.isEmpty() ? null : FileSection.parseToMap(text, FileSection.DOLLAR_SIGN_KV_SEPARATOR);
        }

        // A target the token's new controller would actually want on this board: an opposing player,
        // or a candidate on the side the restriction names (YouCtrl = ours, else an opponent's).
        // TgtZone is read as a list, so a multi-zone script cannot throw here.
        private static boolean hasLiveTarget(final Player ai, final Card c, final Map<String, String> p) {
            final String vt = p.get("ValidTgts");
            for (final String part : vt.split(",")) {
                final String s = part.trim();
                if (s.equals("Any") || s.startsWith("Player") || s.startsWith("Opponent")) {
                    return true;
                }
            }
            final List<ZoneType> zones = p.containsKey("TgtZone")
                    ? ZoneType.listValueOf(p.get("TgtZone")) : List.of(ZoneType.Battlefield);
            final boolean ownSide = vt.contains("YouCtrl");
            for (final Card k : CardLists.getValidCards(ai.getGame().getCardsIn(zones), vt.split(","), ai, c, null)) {
                if (ownSide ? ai.equals(k.getController()) : k.getController().isOpponentOf(ai)) {
                    return true;
                }
            }
            return false;
        }

        // An enters trigger counts as value only if it would do something now: no intervening "if"
        // (Knight of the White Orchid), a live target (Angel of the Ruins with no opposing artifact
        // or enchantment is blank), and a library search that can find something (Wood Elves in a
        // deck with no Forest card is blank).
        private static boolean etbLive(final Player ai, final Card c, final Trigger t) {
            if (t.hasParam("CheckSVar")) {
                return false;
            }
            final Map<String, String> p = rootParams(t);
            if (p == null) {
                return false;
            }
            if (p.containsKey("ValidTgts")) {
                return hasLiveTarget(ai, c, p);
            }
            if (ApiType.ChangeZone.name().equalsIgnoreCase(apiOf(p)) && p.getOrDefault("Origin", "").contains("Library")) {
                return !CardLists.getValidCards(ai.getCardsIn(ZoneType.Library),
                        p.getOrDefault("ChangeType", "Card").split(","), ai, c, null).isEmpty();
            }
            return true;
        }

        // A trigger's chain is read without building it: an ability not built yet is read from its
        // SVar text, because building one allocates a SpellAbility id (ids feed
        // SpellAbility.hashCode) and this runs on every look at an opponent's graveyard, cast or not.
        private static String rootApi(final Trigger t) {
            final SpellAbility built = t.getOverridingAbility();
            if (built != null) {
                return built.getApi() == null ? null : built.getApi().name();
            }
            final String text = t.hasParam("Execute") ? t.getSVar(t.getParam("Execute")) : "";
            return text.isEmpty() ? null : apiOf(FileSection.parseToMap(text, FileSection.DOLLAR_SIGN_KV_SEPARATOR));
        }

        private static boolean chainHasHarm(final Trigger t) {
            final SpellAbility built = t.getOverridingAbility();
            if (built != null) {
                return builtHasHarm(built, 0);
            }
            final Deque<String> todo = new ArrayDeque<>();
            final Set<String> seen = new HashSet<>();
            if (t.hasParam("Execute")) {
                todo.add(t.getParam("Execute"));
            }
            while (!todo.isEmpty()) {
                final String svar = todo.poll();
                if (!seen.add(svar)) {
                    continue;
                }
                final String text = t.getSVar(svar);
                if (text.isEmpty()) {
                    continue;
                }
                final Map<String, String> params = FileSection.parseToMap(text, FileSection.DOLLAR_SIGN_KV_SEPARATOR);
                if (isHarmfulPart(apiOf(params), params)) {
                    return true;
                }
                for (final String key : BRANCHES) {
                    if (params.containsKey(key)) {
                        todo.add(params.get(key));
                    }
                }
                if (params.containsKey("Choices")) {
                    for (final String choice : params.get("Choices").split(",")) {
                        todo.add(choice.trim());
                    }
                }
            }
            return false;
        }

        private static boolean builtHasHarm(final SpellAbility sa, final int depth) {
            if (sa == null || depth > 12) {
                return false;
            }
            if (isHarmfulPart(sa.getApi() == null ? null : sa.getApi().name(), sa.getMapParams())
                    || builtHasHarm(sa.getSubAbility(), depth + 1)) {
                return true;
            }
            for (final SpellAbility extra : sa.getAdditionalAbilities().values()) {
                if (builtHasHarm(extra, depth + 1)) {
                    return true;
                }
            }
            for (final List<AbilitySub> choices : sa.getAdditionalAbilityLists().values()) {
                for (final AbilitySub choice : choices) {
                    if (builtHasHarm(choice, depth + 1)) {
                        return true;
                    }
                }
            }
            return false;
        }

        private static String apiOf(final Map<String, String> params) {
            final String api = params.get("DB");
            if (api != null) {
                return api;
            }
            return params.containsKey("AB") ? params.get("AB") : params.get("SP");
        }

        private static boolean isHarmfulPart(final String api, final Map<String, String> params) {
            if (api == null) {
                return false;
            }
            if (api.endsWith("All") || ApiType.Sacrifice.name().equalsIgnoreCase(api)
                    || ApiType.LosesGame.name().equalsIgnoreCase(api) || ApiType.SkipTurn.name().equalsIgnoreCase(api)) {
                return true;
            }
            final String defined = params.get("Defined");
            final String validTgts = params.get("ValidTgts");
            if (ApiType.Token.name().equalsIgnoreCase(api)) {
                // tokens for anyone but us (Hunted Dragon, Goblin Spymaster, Skyclave Apparition)
                final String owner = params.get("TokenOwner");
                return (owner != null && !"You".equals(owner)) || validTgts != null;
            }
            if (ApiType.GainControl.name().equalsIgnoreCase(api)) {
                // handing a permanent to anyone but us (Akroan Horse)
                final String newController = params.get("NewController");
                return newController != null && !"You".equals(newController);
            }
            if (ApiType.Draw.name().equalsIgnoreCase(api) || ApiType.GainLife.name().equalsIgnoreCase(api)
                    || ApiType.PutCounter.name().equalsIgnoreCase(api)) {
                return (defined != null && (defined.contains("Opponent") || defined.contains("Player") || defined.startsWith("Triggered")))
                        || (validTgts != null && validTgts.contains("Opponent"));
            }
            if (ApiType.LoseLife.name().equalsIgnoreCase(api) || ApiType.DealDamage.name().equalsIgnoreCase(api)
                    || ApiType.Discard.name().equalsIgnoreCase(api) || ApiType.Mill.name().equalsIgnoreCase(api)
                    || ApiType.Poison.name().equalsIgnoreCase(api)) {
                if (defined == null) {
                    // untargeted life loss, discard, mill and poison fall on the controller
                    return validTgts == null && !ApiType.DealDamage.name().equalsIgnoreCase(api);
                }
                return defined.contains("You") || "Player".equals(defined);
            }
            return false;
        }
    }

    // Excise the Imperfect
    // "Exile target nonland permanent. Its controller incubates X, where X is its mana value."
    // The exile half is Utter End's (ChangeZoneAi picks and sets the target with the stock removal
    // floor); this prices only what the card hands back: an Incubator with X +1/+1 counters that
    // becomes an X/X for {2}. Consulted as the Incubate sub's chkDrawback, after the exile target is
    // chosen, so it draws no random number and a decline leaves the game exactly as it was.
    public static class ExciseTheImperfect {
        // never hand a noncreature's controller a body bigger than Pongify's / Generous Gift's 3/3
        public static final int MAX_NONCREATURE_GIFT = 3;

        public static AiAbilityDecision considerIncubate(final Player ai, final SpellAbility sub) {
            final SpellAbility exile = sub.getParentTargetingCard();
            final Card tgt = exile == null ? null : exile.getTargets().getFirstTargetedCard();
            if (tgt == null || !tgt.getController().isOpponentOf(ai)) {
                return new AiAbilityDecision(0, AiPlayDecision.CantPlayAi);
            }
            // SVar X = Targeted$CardManaCost, which resolves to getCMC()
            final int x = tgt.getCMC();
            if (x <= 0) {
                // tokens, face-down, 0-drops: the Incubator gets no counters and dies as a 0/0 if
                // transformed, so this is Utter End
                return new AiAbilityDecision(100, AiPlayDecision.WillPlay);
            }
            final boolean worth;
            if (tgt.isCreature()) {
                // CreatureEvaluator's value of the transformed Incubator: base 80, token (no +20),
                // X/X from counters (15X + 10X), cmc 0, untapped +1. Accept when the creature we
                // exile is worth at least the body we hand back (factor 1.0: the gift grows with X,
                // is vanilla, and needs {2} to wake; Pongify's 1.5 was set against a fixed 3/3).
                final int gift = 81 + 25 * x;
                worth = ComputerUtilCard.evaluateCreature(tgt) >= gift;
            } else if (tgt.isPlaneswalker()) {
                // a walker's repeated loyalty value beats a vanilla body that needs {2}: Utter End
                worth = true;
            } else {
                // artifacts, enchantments, battles: their value is already on the board, so cap the gift
                worth = x <= MAX_NONCREATURE_GIFT;
            }
            return worth ? new AiAbilityDecision(100, AiPlayDecision.WillPlay)
                    : new AiAbilityDecision(0, AiPlayDecision.CantPlayAi);
        }
    }

    // Extraplanar Lens
    public static class ExtraplanarLens {
        public static boolean consider(final Player ai, final SpellAbility sa) {
            Card bestBasic = null;
            Card bestBasicSelfOnly = null;

            CardCollection aiLands = CardLists.filter(ai.getCardsIn(ZoneType.Battlefield), CardPredicates.LANDS_PRODUCING_MANA);
            CardCollection oppLands = CardLists.filter(ai.getOpponents().getCardsIn(ZoneType.Battlefield),
                    CardPredicates.LANDS_PRODUCING_MANA);

            int bestCount = 0;
            int bestSelfOnlyCount = 0;
            for (String landType : MagicColor.Constant.BASIC_LANDS) {
                CardCollection landsOfType = CardLists.filter(aiLands, CardPredicates.nameEquals(landType));
                CardCollection oppLandsOfType = CardLists.filter(oppLands, CardPredicates.nameEquals(landType));

                int numCtrl = CardLists.filter(aiLands, CardPredicates.nameEquals(landType)).size();
                if (numCtrl > bestCount) {
                    bestCount = numCtrl;
                    bestBasic = ComputerUtilCard.getWorstLand(landsOfType);
                }
                if (numCtrl > bestSelfOnlyCount && numCtrl > 1 && oppLandsOfType.isEmpty() && bestBasicSelfOnly == null) {
                    bestSelfOnlyCount = numCtrl;
                    bestBasicSelfOnly = ComputerUtilCard.getWorstLand(landsOfType);
                }
            }

            sa.resetTargets();
            if (bestBasicSelfOnly != null) {
                sa.getTargets().add(bestBasicSelfOnly);
                return true;
            } else if (bestBasic != null) {
                sa.getTargets().add(bestBasic);
                return true;
            }

            return false;
        }
    }

    // Fell the Mighty
    public static class FellTheMighty {
        public static AiAbilityDecision consider(final Player ai, final SpellAbility sa) {
            CardCollection aiList = ai.getCreaturesInPlay();
            if (aiList.isEmpty()) {
                return new AiAbilityDecision(0, AiPlayDecision.MissingNeededCards);
            }
            CardLists.sortByPowerAsc(aiList);
            Card lowest = aiList.get(0);
            if (!sa.canTarget(lowest)) {
                return new AiAbilityDecision(0, AiPlayDecision.TargetingFailed);
            }

            CardCollection oppList = CardLists.filter(ai.getGame().getCardsIn(ZoneType.Battlefield),
                    CardPredicates.CREATURES, CardPredicates.isControlledByAnyOf(ai.getOpponents()));

            oppList = CardLists.filterPower(oppList, lowest.getNetPower() + 1);
            if (ComputerUtilCard.evaluateCreatureList(oppList) > 200) {
                sa.resetTargets();
                sa.getTargets().add(lowest);
                return new AiAbilityDecision(100, AiPlayDecision.WillPlay);
            }
            return new AiAbilityDecision(0, AiPlayDecision.TargetingFailed);
        }
    }

    // Force of Will
    public static class ForceOfWill {
        public static boolean consider(final Player ai, final SpellAbility sa) {
            CardCollection blueCards = CardLists.filter(ai.getCardsIn(ZoneType.Hand), CardPredicates.isColor(MagicColor.BLUE));

            boolean isExileMode = false;
            for (CostPart c : sa.getPayCosts().getCostParts()) {
                if (c.toString().contains("Exile")) {
                    isExileMode = true; // the AI is trying to go for the "exile and pay life" alt cost
                    break;
                }
            }

            if (isExileMode) {
                if (blueCards.size() < 2) {
                    // Need to have something else in hand that is blue in addition to Force of Will itself,
                    // otherwise the AI will fail to play the card and the card will disappear from the pool
                    return false;
                } else if (!blueCards.anyMatch(CardPredicates.lessCMC(3))) {
                    // We probably need a low-CMC card to exile to it, exiling a higher CMC spell may be suboptimal
                    // since the AI does not prioritize/value cards vs. permission at the moment.
                    return false;
                }
            }

            return true;
        }
    }

    // Gaze of Granite
    // "X B B G: Destroy each nonland permanent with mana value X or less."
    // DestroyAllAi.doMassRemovalLogic evaluates and pays only the MAX affordable
    // X, so it either sweeps our own commander/rocks along with theirs or
    // declines a one-sided wipe that a smaller X would give. Choose X here: the
    // ceiling with the best net swing, smallest on ties, under a floor that the
    // swing clears DestroyAllAi's own margin, never leaves us a card down, and
    // never trades down on the creature board.
    // The board is scanned first with no mana probes: ComputerUtilMana's test
    // payments draw MyRandom (isManaSourceReserved), so they run only once some
    // X is admissible on the board.
    public static class GazeOfGranite {
        private static final int MIN_NET = 4;       // DestroyAllAi's own margin: opp > ai + 3
        private static final int MIN_OPP_CARDS = 2; // below LONE_NET: at least a 2-for-1 (counting Gaze)
        private static final int LONE_NET = 6;      // ...or a swing this big (a bomb, a token swarm)

        private static boolean destroyable(final Card c) {
            return !(c.hasKeyword(Keyword.INDESTRUCTIBLE) || c.getCounters(CounterEnumType.SHIELD) > 0 || c.hasSVar("SacMe"));
        }

        private static int pieces(final Card c) { // a mutated pile dies as every card in it
            return c.hasMergedCard() ? Math.max(1, c.getMergedCards().size()) : 1;
        }

        private static int value(final Iterable<Card> list) {
            int v = 0;
            for (Card c : list) {
                if (CardPredicates.TOKEN.test(c) && !c.isCreature() && c.getCMC() == 0) {
                    continue; // Treasure/Clue/Food: destroyed, but not a reason to cast
                }
                v += (c.getCMC() + 1) * pieces(c) + (c.isCommander() ? 2 : 0); // commander: + recast tax
            }
            return v;
        }

        private static int cards(final Iterable<Card> list) { // nontoken cards lost, piles counted whole
            int n = 0;
            for (Card c : list) {
                if (!CardPredicates.TOKEN.test(c)) {
                    n += pieces(c);
                }
            }
            return n;
        }

        public static AiAbilityDecision consider(final Player ai, final SpellAbility sa) {
            final Card source = sa.getHostCard();
            final String valid = sa.getParamOrDefault("ValidCards", "");

            int cap = 0; // the lists only change at mana values present on the battlefield
            for (Card c : ai.getGame().getCardsIn(ZoneType.Battlefield)) {
                if (!c.isLand()) {
                    cap = Math.max(cap, c.getCMC());
                }
            }

            final int[] nets = new int[cap + 1];
            final boolean[] ok = new boolean[cap + 1];
            boolean any = false;
            for (int x = 0; x <= cap; x++) { // board first: no mana probes (they draw MyRandom)
                sa.setXManaCostPaid(x); // cmcLEX reads Count$xPaid from the root SA
                CardCollection opp = CardLists.filter(CardLists.getValidCards(
                        ai.getOpponents().getCardsIn(ZoneType.Battlefield), valid, source.getController(), source, sa), GazeOfGranite::destroyable);
                CardCollection mine = CardLists.filter(CardLists.getValidCards(
                        ai.getCardsIn(ZoneType.Battlefield), valid, source.getController(), source, sa), GazeOfGranite::destroyable);
                if (opp.isEmpty()) {
                    continue;
                }
                int net = value(opp) - value(mine);
                if (net < MIN_NET) {
                    continue;
                }
                // never a card down: opposing creature tokens count as half a card
                int oppCards = cards(opp) + CardLists.count(opp, CardPredicates.TOKEN.and(CardPredicates.CREATURES)) / 2;
                int myCards = cards(mine);
                if (oppCards < Math.max(net >= LONE_NET ? 1 : MIN_OPP_CARDS, myCards + 1)) {
                    continue;
                }
                // never lose a better creature board than we remove
                if (ComputerUtilCard.evaluateCreatureList(CardLists.filter(mine, CardPredicates.CREATURES))
                        > ComputerUtilCard.evaluateCreatureList(CardLists.filter(opp, CardPredicates.CREATURES))) {
                    continue;
                }
                ok[x] = true;
                nets[x] = net;
                any = true;
            }

            if (!any) {
                sa.setXManaCostPaid(null);
                return new AiAbilityDecision(0, AiPlayDecision.CantPlayAi);
            }

            final int maxX = ComputerUtilCost.setMaxXValue(sa, ai, false); // only now: RNG-consuming test payments
            int bestX = -1;
            int bestNet = Integer.MIN_VALUE;
            for (int x = 0; x <= Math.min(cap, maxX); x++) {
                if (ok[x] && nets[x] > bestNet) { // strict: ties keep the smaller X
                    bestNet = nets[x];
                    bestX = x;
                }
            }

            if (bestX < 0) {
                sa.setXManaCostPaid(null);
                return new AiAbilityDecision(0, AiPlayDecision.CantAffordX);
            }

            sa.setXManaCostPaid(bestX);
            return new AiAbilityDecision(100, AiPlayDecision.WillPlay);
        }
    }

    // Gideon Blackblade
    public static class GideonBlackblade {
        public static AiAbilityDecision consider(final Player ai, final SpellAbility sa) {
            sa.resetTargets();
            CardCollectionView otb = CardLists.filter(ai.getCardsIn(ZoneType.Battlefield), CardPredicates.isTargetableBy(sa));
            if (!otb.isEmpty()) {
                sa.getTargets().add(ComputerUtilCard.getBestAI(otb));
            }
            return new AiAbilityDecision(100, AiPlayDecision.WillPlay);
        }
    }

    // Goblin Polka Band
    public static class GoblinPolkaBand {
        public static AiAbilityDecision consider(final Player ai, final SpellAbility sa) {
            int maxPotentialTgts = ai.getOpponents().getCreaturesInPlay().filter(CardPredicates.UNTAPPED).size();
            int maxPotentialPayment = ComputerUtilMana.determineLeftoverMana(sa, ai, "R", false);

            int numTgts = Math.min(maxPotentialPayment, maxPotentialTgts);
            if (numTgts == 0) {
                return new AiAbilityDecision(0, AiPlayDecision.TargetingFailed);
            }

            // Set Announce
            sa.getHostCard().setSVar("TgtNum", String.valueOf(numTgts));

            // Simulate random targeting
            List<GameEntity> validTgts = sa.getTargetRestrictions().getAllCandidates(sa);
            sa.resetTargets();
            sa.getTargets().addAll(Aggregates.random(validTgts, numTgts));
            return new AiAbilityDecision(100, AiPlayDecision.WillPlay);
        }
    }

    // Goblin Recruiter
    // "Search your library for any number of Goblin cards ... put those cards on top in any order."
    // The stock hidden-origin chooser never returns null, so the AI stacked EVERY Goblin and drew
    // nothing else - no land, removal or non-Goblin engine - for the rest of the game. Stack only
    // the next few draws: the best Goblin creatures the hand is about to run out of, each one a
    // card the AI's own scry heuristic would keep on top. A null pick ends the search.
    public static class GoblinRecruiter {
        public static final int MAX_STACKED = 3;

        public static Card considerCardToStack(final Player ai, final SpellAbility sa, final CardCollection fetchList) {
            final Card host = sa.getHostCard();
            if (fetchList.isEmpty() || host == null || !ai.equals(sa.getActivatingPlayer())) {
                return null; // someone else deciding our search: stack nothing
            }
            // Picks are removed from fetchList but stay in the library until the move,
            // so (eligible in library) - (still offered) = already stacked.
            final int eligible = AbilityUtils.filterListByType(ai.getCardsIn(ZoneType.Library),
                    sa.getParam("ChangeType"), sa).size();
            final int already = eligible - fetchList.size();
            // Creatures already in hand compete for the same mana: stack only what the hand runs out of.
            final int budget = MAX_STACKED - CardLists.count(ai.getCardsIn(ZoneType.Hand), CardPredicates.CREATURES);
            if (already >= budget) {
                return null;
            }
            // scryWillMoveCardToBottomOfLibrary: castable soon (lands in hand counted, no free land
            // drop assumed), not a below-average creature on a wide board, not a spell on a mana-light board.
            final CardCollection pool = CardLists.filter(fetchList, c -> c.isCreature()
                    && !c.getName().equals(host.getName())
                    && !(c.getType().isLegendary() && ai.isCardInPlay(c.getName()))
                    && !ComputerUtil.scryWillMoveCardToBottomOfLibrary(ai, c));
            if (pool.isEmpty()) {
                return null; // nothing worth a guaranteed draw: leave the rest random
            }
            return ComputerUtilCard.getBestCreatureAI(pool);
        }
    }

    // Grisly Sigil
    public static class GrislySigil {
        public static boolean consider(final Player ai, final SpellAbility sa) {
            // TODO: improve targeting support for Casualty 1
            CardCollection potentialTgts = CardLists.filterControlledBy(CardUtil.getValidCardsToTarget(sa), ai.getOpponents());

            for (Card c : potentialTgts) {
                int potentialDamage = c.getAssignedDamage(false, null) > 0 ? 3 : 1; // TODO: account for damage reduction
                if (c.canBeDestroyed()) {
                    int damageToDeal = c.isCreature() ? c.getNetToughness() : c.getCurrentLoyalty();
                    if (damageToDeal <= c.getAssignedDamage() + potentialDamage) {
                        potentialTgts.add(c);
                    }
                }
            }

            if (!potentialTgts.isEmpty()) {
                sa.resetTargets();
                sa.getTargets().add(ComputerUtilCard.getBestAI(potentialTgts));
                return true;
            }

            return false;
        }
    }

    // Grothama, All-Devouring
    public static class GrothamaAllDevouring {
        public static boolean consider(final Player ai, final SpellAbility sa) {
            final Card fighter = sa.getHostCard();
            final Card devourer = sa.getOriginalHost();
            if (ai.getTeamMates(true).contains(devourer.getController())) {
                return false; // TODO: Currently, the AI doesn't ever fight its own (or allied) Grothama for card draw. This can be improved.
            }
            boolean goodTradeOrNoTrade = devourer.canBeDestroyed() && (devourer.getNetPower() < fighter.getNetToughness() || !fighter.canBeDestroyed()
                    || ComputerUtilCard.evaluateCreature(devourer) > ComputerUtilCard.evaluateCreature(fighter));
            return goodTradeOrNoTrade && fighter.getNetPower() >= devourer.getNetToughness();
        }
    }

    // Guff Rewrites History
    // For each player, target a nonland nonenchantment permanent they control;
    // owners shuffle them into their libraries, then each affected player casts
    // a free nonland permanent dug off the top. The swap is symmetric, so both
    // halves must favour us: each opponent loses a permanent worth more than an
    // average refill, and we lose only fodder that our own refill (a
    // planeswalker-heavy deck) beats. Guards: empty stack only; never on our
    // own turn before MAIN1 (upkeep/draw would spend the mana our walkers want
    // at sorcery speed); never a ward threat (ward counters the whole spell);
    // never a combatant already dying in this combat, nor our own attacker or
    // blocker; no creature fodder when the opponents' untapped-next-turn
    // attackers could kill us unblocked. Everything here is RNG-free, like the
    // generic targeting it replaces.
    public static class GuffRewritesHistory {
        public static final int MIN_THREAT_VALUE = 220; // a vanilla 4/4 four-drop evaluates to 221
        public static final int MAX_FODDER_VALUE = 130; // tokens, Sol Ring, Signets; keeps CMC 3+ artifacts and every walker

        public static AiAbilityDecision consider(final Player ai, final SpellAbility sa) {
            sa.resetTargets();
            final Game game = ai.getGame();
            if (!game.getStack().isEmpty()) {
                return new AiAbilityDecision(0, AiPlayDecision.AnotherTime);
            }
            final PhaseHandler ph = game.getPhaseHandler();
            if (ph.isPlayerTurn(ai) && ph.getPhase().isBefore(PhaseType.MAIN1)) {
                return new AiAbilityDecision(0, AiPlayDecision.AnotherTime);
            }
            final Combat combat = game.getCombat();

            // Opponents: each one's biggest targetable permanent, above the floor
            final CardCollection threats = new CardCollection();
            for (final Player p : game.getPlayers()) { // the same set OneEach counts
                if (!p.isOpponentOf(ai)) {
                    continue;
                }
                final CardCollection candidates = CardLists.filter(
                        CardLists.getTargetableCards(p.getCardsIn(ZoneType.Battlefield), sa),
                        c -> !c.hasKeyword(Keyword.WARD)
                                && (combat == null || !ComputerUtilCombat.combatantWouldBeDestroyed(ai, c, combat)));
                final Card threat = Aggregates.itemWithMax(candidates, GuffRewritesHistory::value);
                if (threat == null || value(threat) < MIN_THREAT_VALUE) {
                    return new AiAbilityDecision(0, AiPlayDecision.CantPlayAi);
                }
                threats.add(threat);
            }
            if (threats.isEmpty()) {
                return new AiAbilityDecision(0, AiPlayDecision.CantPlayAi);
            }

            // Keep our creatures when the attackers left after the shuffle are lethal unblocked
            int incoming = 0;
            for (final Player p : ai.getOpponents()) {
                final CardCollection attackers = CardLists.filter(p.getCreaturesInPlay(),
                        c -> !threats.contains(c) && ComputerUtilCombat.canAttackNextTurn(c, ai));
                incoming += ComputerUtilCombat.sumDamageIfUnblocked(attackers, ai);
            }
            final boolean lastBlockersMatter = ai.canLoseLife() && incoming >= ai.getLife();

            // Us (and teammates): the cheapest fodder, never the commander
            final CardCollection picks = new CardCollection(threats);
            for (final Player p : game.getPlayers()) {
                if (p.isOpponentOf(ai)) {
                    continue;
                }
                Card fodder = null;
                for (final Card c : CardLists.getTargetableCards(p.getCardsIn(ZoneType.Battlefield), sa)) {
                    if (c.isCommander() || value(c) > MAX_FODDER_VALUE
                            || (lastBlockersMatter && c.isCreature())
                            || (combat != null && (combat.isAttacking(c) || combat.isBlocking(c)))) {
                        continue;
                    }
                    if (fodder == null || fodderBefore(c, fodder)) {
                        fodder = c;
                    }
                }
                if (fodder == null) {
                    return new AiAbilityDecision(0, AiPlayDecision.CantPlayAi);
                }
                picks.add(fodder);
            }

            for (final Card c : picks) {
                if (!sa.canTarget(c)) {
                    sa.resetTargets();
                    return new AiAbilityDecision(0, AiPlayDecision.TargetingFailed);
                }
                sa.getTargets().add(c);
            }
            if (!sa.isTargetNumberValid()) {
                sa.resetTargets();
                return new AiAbilityDecision(0, AiPlayDecision.TargetingFailed);
            }
            return new AiAbilityDecision(100, AiPlayDecision.WillPlay);
        }

        // Tokens first (lowest value first), then nontoken permanents without
        // mana abilities (lowest value first), then mana sources - highest
        // value first, since at this end of the CMC scale a Signet (110) is a
        // worse rock than Sol Ring (80).
        private static boolean fodderBefore(final Card c, final Card best) {
            final int rc = rank(c);
            final int rb = rank(best);
            if (rc != rb) {
                return rc < rb;
            }
            return rc == 2 ? value(c) > value(best) : value(c) < value(best);
        }

        private static int rank(final Card c) {
            if (c.isToken()) {
                return 0;
            }
            return c.getManaAbilities().isEmpty() ? 1 : 2;
        }

        // The scale of ComputerUtilCard.evaluateRemovalTargetPriority (private
        // there), minus its token bonus and board-position term: a shuffled
        // token is not "gone for good" here, its controller still gets a card.
        private static int value(final Card c) {
            if (c.isCreature()) {
                return ComputerUtilCard.evaluateCreature(c);
            }
            int v = 50 + 30 * c.getCMC();
            if (c.isPlaneswalker()) {
                v += 10 * c.getCounters(CounterEnumType.LOYALTY);
            }
            return v;
        }
    }

    // Guilty Conscience
    public static class GuiltyConscience {
        public static Card getBestAttachTarget(final Player ai, final SpellAbility sa, final List<Card> list) {
            Card chosen = null;

            List<Card> aiStuffies = CardLists.filter(list, c -> {
                // Don't enchant creatures that can survive
                if (!c.getController().equals(ai)) {
                    return false;
                }
                final String name = c.getName();
                return name.equals("Stuffy Doll") || name.equals("Boros Reckoner") || name.equals("Spitemare");
            });
            if (!aiStuffies.isEmpty()) {
                chosen = aiStuffies.get(0);
            } else {
                List<Card> creatures = CardLists.filterControlledBy(list, ai.getOpponents());
                // Don't enchant creatures that can survive
                creatures = CardLists.filter(creatures, c -> c.canBeDestroyed()
                        && c.getNetCombatDamage() >= c.getNetToughness()
                        && !c.isEnchantedBy("Guilty Conscience")
                );
                chosen = ComputerUtilCard.getBestCreatureAI(creatures);
            }

            return chosen;
        }
    }

    // Here Comes a New Hero!
    // "Target player draws X cards. Then create a token that's a copy of up to
    // one target creature with mana value X or less." DrawAi approves the Draw X
    // root (main 2, self target) and sets X on it; the DBCopy sub then reached
    // the inherited SpellAbilityAi.chkDrawback, which refuses every targeted sub,
    // so the whole spell was vetoed. Reached from CopyPermanentAi.chkDrawback's
    // name gate, after DrawAi.targetAI set the root's X. Draws no random numbers,
    // like the base chkDrawback it replaces.
    public static class HereComesANewHero {
        // Draw alone must be at least this many cards (5 mana, Stroke of Genius at X=2).
        private static final int MIN_X_WITHOUT_COPY = 2;
        // A vanilla non-token 1-mana 1/1 scores 130 tapped; below that a copy is chaff.
        private static final int MIN_COPY_VALUE = 130;

        public static AiAbilityDecision considerCopy(final Player ai, final SpellAbility sa) {
            sa.resetTargets();
            final Integer xPaid = sa.getRootAbility().getXManaCostPaid();
            final int x = xPaid == null ? 0 : xPaid;
            // DrawAi's hand-size clamp can leave X at 0 or below: never pay 3 to draw nothing.
            if (x < 1) {
                return new AiAbilityDecision(0, AiPlayDecision.CantPlayAi);
            }
            // For X draws DrawAi.targetAI raises X toward library - 1 once it reaches library - 3.
            if (x > ai.getCardsIn(ZoneType.Library).size() - 3) {
                return new AiAbilityDecision(0, AiPlayDecision.CantPlayAi);
            }

            // Frost Titan, Unsettled Mariner, Kira-granted triggers counter the whole spell
            // (draw included) when an opponent's creature is targeted, and
            // ComputerUtilCost.canPayCost cannot see them: then copy only our own.
            final boolean onlyOwn = opponentPunishesTargeting(ai);

            Card best = null;
            int bestValue = MIN_COPY_VALUE - 1;
            // Creature.cmcLEX reads X through the root's getXManaCostPaid.
            for (final Card c : CardUtil.getValidCardsToTarget(sa)) {
                if (!sa.canTarget(c)) {
                    continue;
                }
                final boolean ours = ai.equals(c.getController());
                if (!ours && onlyOwn) {
                    continue;
                }
                // canPayCost adds an opponent's ward cost after DrawAi spent every mana
                // on X: CantAfford, then the same X and the same target every pass.
                if (!ours && c.hasKeyword(Keyword.WARD)) {
                    continue;
                }
                // The legend rule would bin the token or the original.
                if (ours && c.getType().isLegendary()) {
                    continue;
                }
                // The token copies base P/T and no counters: Steelbane Hydra (0/0) dies on
                // arrival, Voracious Hydra (0/1) fights for nothing.
                if (c.getBasePower() <= 0 || c.getBaseToughness() <= 0) {
                    continue;
                }
                // Leveler, Phyrexian Dreadnought: copies that hurt their new controller.
                if (ComputerUtilCard.isCardRemAIDeck(c) || ComputerUtilCard.isCardRemRandomDeck(c)) {
                    continue;
                }
                final int v = copyValue(c);
                if (v > bestValue) {
                    best = c;
                    bestValue = v;
                }
            }

            if (best != null) {
                sa.getTargets().add(best);
                return new AiAbilityDecision(100, AiPlayDecision.WillPlay);
            }
            // "Up to one": zero targets, the draw alone, once it is worth the mana.
            if (x >= MIN_X_WITHOUT_COPY) {
                return new AiAbilityDecision(100, AiPlayDecision.WillPlay);
            }
            return new AiAbilityDecision(0, AiPlayDecision.CantPlayAi);
        }

        private static boolean opponentPunishesTargeting(final Player ai) {
            for (final Player opp : ai.getOpponents()) {
                for (final Card c : opp.getCardsIn(ZoneType.Battlefield)) {
                    for (final Trigger t : c.getTriggers()) {
                        if (t.getMode() == TriggerType.BecomesTarget || t.getMode() == TriggerType.BecomesTargetOnce) {
                            return true;
                        }
                    }
                }
            }
            return false;
        }

        // What the TOKEN will be: copiable base P/T, not counters or pumps on the original.
        private static int copyValue(final Card c) {
            return ComputerUtilCard.evaluateCreature(c, false, true)
                    + 15 * c.getBasePower() + 10 * c.getBaseToughness();
        }
    }

    // Heroic Sacrifice
    // "Choose target creature you control. The next time a source would deal
    // combat damage to you or another creature you control this turn, that
    // damage is dealt to the chosen creature instead. When it dies this turn,
    // draw a card and put its counters on a creature you control."
    // EffectAi's no-AILogic fallthrough refused it every time, and its Fog
    // branch only knows how to target an attacker (Turn the Tables). Reached
    // from EffectAi.checkApiLogic's name gate after the randomReturn roll.
    // Its own fog window, with no FogAi route: FogAi's memory check would
    // reserve mana and remember a CHOSEN_FOG_EFFECT, and AiAttackController
    // then stops holding blockers back for a fog that chooseMagnet may still
    // refuse. The window is the opponent's declare-blockers step with an empty
    // stack, combat damage not already prevented, attackers on us, and
    // ComputerUtilCombat.lifeInDanger true (the only RNG draw, checked last).
    public static class HeroicSacrifice {
        public static AiAbilityDecision consider(final Player ai, final SpellAbility sa) {
            final Game game = ai.getGame();
            final PhaseHandler ph = game.getPhaseHandler();
            final Combat combat = game.getCombat();
            if (!ph.getPlayerTurn().isOpponentOf(ai) || !ph.is(PhaseType.COMBAT_DECLARE_BLOCKERS)
                    || !game.getStack().isEmpty() || combat == null
                    || combat.getAttackersOf(ai).isEmpty()
                    || game.getReplacementHandler().isPreventCombatDamageThisTurn()) {
                return new AiAbilityDecision(0, AiPlayDecision.CantPlayAi);
            }
            if (!ComputerUtilCombat.lifeInDanger(ai, combat)) {
                return new AiAbilityDecision(0, AiPlayDecision.CantPlayAi);
            }
            final Card magnet = chooseMagnet(ai, sa, combat);
            if (magnet == null) {
                return new AiAbilityDecision(0, AiPlayDecision.CantPlayAi);
            }
            sa.resetTargets();
            sa.getTargets().add(magnet);
            return new AiAbilityDecision(100, AiPlayDecision.WillPlay);
        }

        // The creature that soaks every point of this combat's damage aimed at
        // us and our creatures, or null when no choice makes the redirect work.
        public static Card chooseMagnet(final Player ai, final SpellAbility sa, final Combat combat) {
            final CardCollection candidates = CardLists.getTargetableCards(ai.getCreaturesInPlay(), sa);
            if (candidates.isEmpty()) {
                return null;
            }
            // One entry per combat hit headed at us or at a creature we control.
            // Attackers of our planeswalkers are counted too - an overcount,
            // which only makes survival harder.
            final List<Pair<Card, Integer>> allHits = new ArrayList<>();
            final List<Pair<Card, Integer>> firstStrikeHits = new ArrayList<>();
            for (final Card att : combat.getAttackers()) {
                if (!ai.equals(combat.getDefenderPlayerByAttacker(att))) {
                    continue;
                }
                final int hit = att.getNetCombatDamage()
                        + ComputerUtilCombat.predictPowerBonusOfAttacker(att, null, combat, false);
                if (hit <= 0) {
                    continue;
                }
                allHits.add(Pair.of(att, hit));
                if (att.hasFirstStrike() || att.hasDoubleStrike()) {
                    firstStrikeHits.add(Pair.of(att, hit));
                }
                if (att.hasDoubleStrike()) {
                    allHits.add(Pair.of(att, hit));
                }
            }
            if (allHits.isEmpty()) {
                return null;
            }

            // 1) A creature that absorbs all of it and lives: a pure fog. Most
            //    headroom wins, so a response pump is least likely to flip it.
            Card best = null;
            int bestRoom = 0;
            for (final Card c : candidates) {
                final int room = headroom(c, allHits);
                if (room > bestRoom) {
                    best = c;
                    bestRoom = room;
                }
            }
            if (best != null) {
                return best;
            }

            // 2) A sacrifice (replaced by the spell's draw). Never the commander,
            //    and it must outlive the first-strike step - a magnet that dies
            //    there stops redirecting and the regular-damage step lands in full.
            final CardCollection sac = CardLists.filter(candidates,
                    c -> !c.isCommander() && headroom(c, firstStrikeHits) > 0);
            if (!sac.isEmpty()) {
                // Prefer a blocker that is dying in this combat anyway.
                final CardCollection doomed = CardLists.filter(sac,
                        c -> combat.isBlocking(c) && ComputerUtilCombat.blockerWouldBeDestroyed(ai, c, combat));
                return ComputerUtilCard.getWorstCreatureAI(doomed.isEmpty() ? sac : doomed);
            }

            // 3) Only when this combat kills us: the commander, under the same
            //    first-strike rule. It goes to the command zone (no draw), and we live.
            if (ComputerUtilCombat.lifeInSeriousDanger(ai, combat)) {
                final CardCollection commanders = CardLists.filter(candidates,
                        c -> c.isCommander() && headroom(c, firstStrikeHits) > 0);
                if (!commanders.isEmpty()) {
                    return ComputerUtilCard.getWorstCreatureAI(commanders);
                }
            }
            return null;
        }

        // > 0: c survives these hits. Integer.MAX_VALUE when nothing gets
        // through or c is indestructible against plain damage.
        private static int headroom(final Card c, final List<Pair<Card, Integer>> hits) {
            int dmg = 0;
            boolean deathtouch = false;
            boolean counters = false; // wither/infect: -1/-1 counters, indestructible doesn't help
            for (final Pair<Card, Integer> h : hits) {
                final int d = ComputerUtilCombat.predictDamageTo(c, h.getRight(), h.getLeft(), true);
                if (d <= 0) {
                    continue;
                }
                dmg += d;
                deathtouch |= h.getLeft().hasKeyword(Keyword.DEATHTOUCH);
                counters |= h.getLeft().isWitherDamage();
            }
            if (dmg == 0) {
                return Integer.MAX_VALUE;
            }
            if (c.hasKeyword(Keyword.INDESTRUCTIBLE) && !counters) {
                return Integer.MAX_VALUE;
            }
            if (deathtouch) {
                return 0;
            }
            return ComputerUtilCombat.getDamageToKill(c, false) - dmg;
        }
    }

    // Hierophant Bio-Titan
    // "As an additional cost to cast this spell, you may remove any number of +1/+1 counters
    // from among creatures you control. This spell costs {2} less to cast for each counter
    // removed this way." Scripted as Cost$ 10 G G RemoveAnyCounter<X/P1P1/Creature> with a
    // Relative ReduceCost of 2*X. No generic code chooses that non-mana X (PermanentAi only does
    // for SacToReduceCost), so X read 0 and AiCostDecision.visit(CostRemoveAnyCounter) returned
    // null for 0: willPayCosts declined every cast, and payment would have failed even with
    // twelve lands. chooseX (PermanentAi.checkApiLogic) picks the SMALLEST affordable X and
    // chooseCounters (AiCostDecision) pays it.
    // Floor: a counter comes off a creature only if it survives with every positive P/T boost
    // gone (Clamavus's counter-scaled static included) and its marked damage still on it; every
    // donor keeps its last counter unless X cannot be met otherwise (The Swarmlord, Winged Hive
    // Tyrant, Bred for the Hunt and Tyrant Guard key off creatures with counters); X never buys
    // more reduction than the generic cost has; and an X >= 1 cast waits for our main 2, so it
    // never shrinks this turn's attackers. The mana probes draw MyRandom
    // (ComputerUtilMana.isManaSourceReserved) and the stock path never probed this card, so an
    // RNG-free mana estimate rules out the hopeless boards first.
    public static class HierophantBioTitan {
        public static final String NAME = "Hierophant Bio-Titan";

        public static boolean isOwnReduceCounterCost(final SpellAbility sa, final CostRemoveAnyCounter cost) {
            return sa != null && sa.isSpell() && sa.getHostCard() != null
                    && NAME.equals(sa.getHostCard().getName())
                    && "X".equals(cost.getAmount()) && !cost.payCostFromSource()
                    && cost.counter != null && cost.counter.is(CounterEnumType.P1P1);
        }

        // +1/+1 counters c can lose and still survive with every positive boost gone
        static int safeCounters(final Card c) {
            if (!c.canRemoveCounters(CounterEnumType.P1P1)) {
                return 0;
            }
            int positiveBoost = 0;
            for (Pair<Integer, Integer> boost : c.getPTBoostTable().values()) {
                positiveBoost += Math.max(0, boost.getRight());
            }
            return Math.max(0, Math.min(c.getCounters(CounterEnumType.P1P1), c.getLethalDamage() - 1 - positiveBoost));
        }

        static List<Card> donors(final Player ai, final SpellAbility sa, final CostRemoveAnyCounter cost) {
            final List<Card> list = Lists.newArrayList(CardLists.filter(CardLists.getValidCards(ai.getCardsIn(ZoneType.Battlefield),
                    cost.getType().split(";"), ai, sa.getHostCard(), sa), c -> safeCounters(c) > 0));
            // deterministic: undying first (losing its counters re-arms it), most removable, card id
            list.sort(Comparator.comparing((Card c) -> !c.hasKeyword(Keyword.UNDYING))
                    .thenComparingInt(c -> -safeCounters(c))
                    .thenComparingInt(Card::getId));
            return list;
        }

        public static AiAbilityDecision chooseX(final Player ai, final SpellAbility sa) {
            final CostRemoveAnyCounter part = sa.getPayCosts().getCostPartByType(CostRemoveAnyCounter.class);
            if (part == null || !isOwnReduceCounterCost(sa, part)) {
                return new AiAbilityDecision(100, AiPlayDecision.WillPlay); // nothing to choose
            }
            int safe = 0;
            for (Card c : donors(ai, sa, part)) {
                safe += safeCounters(c);
            }
            sa.setXManaCostPaid(0);
            final ManaCostBeingPaid full = ComputerUtilMana.calculateManaCost(sa.getPayCosts(), sa, ai, true, 0, false);
            final int generic = full.getGenericManaAmount();
            // our own main 1: only a cast that removes nothing
            final boolean main1 = ai.getGame().getPhaseHandler().is(PhaseType.MAIN1, ai);
            final int maxX = main1 ? 0 : Math.min(safe, (generic + 1) / 2);
            final AiPlayDecision no = main1 && safe > 0 ? AiPlayDecision.WaitForMain2 : AiPlayDecision.CantAfford;
            // RNG-free ceiling (it counts tapped sources too) before the probes, which draw MyRandom
            if (ComputerUtilMana.getAvailableManaEstimate(ai, false) < full.getConvertedManaCost() - Math.min(generic, 2 * maxX)) {
                sa.setXManaCostPaid(null);
                return new AiAbilityDecision(0, no);
            }
            for (int x = 0; x <= maxX; x++) {
                sa.setXManaCostPaid(x);
                if (ComputerUtilCost.canPayCost(sa, ai, sa.isTrigger())) {
                    return new AiAbilityDecision(100, AiPlayDecision.WillPlay);
                }
            }
            sa.setXManaCostPaid(null);
            return new AiAbilityDecision(0, no);
        }

        // decision (ComputerUtilCost.checkRemoveCounterCost) and payment (payComputerCosts) alike
        public static PaymentDecision chooseCounters(final Player ai, final SpellAbility sa,
                final CostRemoveAnyCounter cost, final int amount) {
            final GameEntityCounterTable table = new GameEntityCounterTable();
            if (amount <= 0) {
                return PaymentDecision.counters(table); // "any number" includes none
            }
            final List<Card> donors = donors(ai, sa, cost);
            final int[] take = new int[donors.size()];
            int left = amount;
            // pass 1 leaves every donor one counter; pass 2 takes last counters only if still short
            for (int pass = 1; pass <= 2 && left > 0; pass++) {
                for (int i = 0; i < donors.size() && left > 0; i++) {
                    final Card c = donors.get(i);
                    int room = safeCounters(c);
                    if (pass == 1) {
                        room = Math.min(room, c.getCounters(CounterEnumType.P1P1) - 1);
                    }
                    final int t = Math.min(room - take[i], left);
                    if (t > 0) {
                        take[i] += t;
                        left -= t;
                    }
                }
            }
            if (left > 0) {
                return null; // never overdraw into a kill; the cast fails through setSkip instead
            }
            for (int i = 0; i < donors.size(); i++) {
                if (take[i] > 0) {
                    table.put(null, donors.get(i), CounterEnumType.P1P1, take[i]);
                }
            }
            return PaymentDecision.counters(table);
        }
    }

    // Hua Tuo, Honored Physician
    // "{T}: Put target creature card from your graveyard on top of your
    // library. Activate only during your turn, before attackers are declared."
    // Without an AILogic the whole activation window lies before MAIN2, where
    // ChangeZoneAi's Graveyard->Library phase veto always refuses. Act only in
    // our own upkeep, with an empty stack and a draw step that will happen, so
    // the pick is drawn this same turn; the script's AIActivateLast$ True lets
    // every other upkeep activation (a library shuffle included) go first.
    // Floors: a creature card clearly better than Hua Tuo itself (the
    // BetterThanSource idiom), one the AI would cast (no RemoveDeck hint) and
    // can cast this turn, and never while the random draw is a land we need.
    public static class HuaTuo {
        static final int BETTER_THAN_SOURCE = 30;   // a vanilla 3/3 three-drop scores exactly Hua Tuo + 30
        static final int MIN_LANDS_WITHOUT_LAND_IN_HAND = 4;

        public static AiAbilityDecision consider(final Player ai, final SpellAbility sa) {
            final Game game = ai.getGame();
            final PhaseHandler ph = game.getPhaseHandler();
            final Card source = sa.getHostCard();

            // The pick must replace THIS turn's draw.
            if (!ph.is(PhaseType.UPKEEP, ai) || !game.getStack().isEmpty()
                    || game.getReplacementHandler().wouldPhaseBeSkipped(ai, PhaseType.DRAW)
                    || !ai.canDraw()) {
                return new AiAbilityDecision(0, AiPlayDecision.AnotherTime);
            }

            final boolean landInHand = ai.getCardsIn(ZoneType.Hand).anyMatch(CardPredicates.LANDS_PRODUCING_MANA);
            // A random draw is often a land; don't trade it away while short on lands.
            if (!landInHand && ai.getLandsInPlay().size() < MIN_LANDS_WITHOUT_LAND_IN_HAND) {
                return new AiAbilityDecision(0, AiPlayDecision.AnotherTime);
            }

            // Value floor: clearly better than Hua Tuo, not hinted away, and
            // castable this turn off current sources (or by mana value with the
            // land drop), so the replaced draw is never dead.
            final int floor = ComputerUtilCard.evaluateCreature(source) + BETTER_THAN_SOURCE;
            final int mana = ComputerUtilMana.getAvailableManaEstimate(ai, false) + (landInHand ? 1 : 0);
            final CardCollection picks = CardLists.filter(
                    CardLists.getTargetableCards(ai.getCardsIn(ZoneType.Graveyard), sa),
                    c -> c.isCreature() && !ComputerUtilCard.isCardRemAIDeck(c)
                            && ComputerUtilCard.evaluateCreature(c) > floor
                            && (ComputerUtilMana.hasEnoughManaSourcesToCast(c.getFirstSpellAbility(), ai)
                                || (landInHand && c.getCMC() <= mana)));
            if (picks.isEmpty()) {
                return new AiAbilityDecision(0, AiPlayDecision.TargetingFailed);
            }
            sa.resetTargets();
            sa.getTargets().add(ComputerUtilCard.getBestCreatureAI(picks));
            return new AiAbilityDecision(100, AiPlayDecision.WillPlay);
        }
    }

    // Hunted by The Family
    // "Choose up to four target creatures you don't control. For each of them,
    // that creature's controller faces a villainous choice: it becomes a 1/1
    // white Human with no abilities, or you create a token copy of it."
    // ChooseCardAi's generic branch only targets players, so this card was
    // never cast. Both outcomes are pure gain for the caster (an AI victim
    // always shrinks: VillainousChoice -> AlwaysPlayAi -> spells.get(0)), so
    // pick the opponents' creatures that lose the most by the shrink.
    // What is lost is measured against the body the creature really keeps:
    // Animate only sets base P/T (layer 7b), so counters, anthems and pumps
    // stay on top of the 1/1. Floor: a target must lose at least MIN_GAIN
    // (strictly above the ability bonus, so an ability alone never qualifies
    // one); cast only for a real threat (it loses >= THREAT_GAIN, or it is an
    // opposing commander that still has an ability to lose) or for two or
    // more targets that lose >= MIN_TOTAL_GAIN combined.
    public static class HuntedByTheFamily {
        static final int MIN_GAIN = 50;         // a vanilla 3/3 three-drop loses 50
        static final int THREAT_GAIN = 150;     // a clean 5/5 flyer loses exactly 150
        static final int MIN_TOTAL_GAIN = 150;
        static final int ABILITY_BONUS = 40;
        static final int COMMANDER_BONUS = 100;

        public static AiAbilityDecision consider(final Player ai, final SpellAbility sa) {
            if (!sa.usesTargeting()) {
                return new AiAbilityDecision(0, AiPlayDecision.CantPlayAi);
            }
            sa.resetTargets();

            final List<Pair<Card, Integer>> cands = new ArrayList<>();
            for (final Card c : ai.getOpponents().getCreaturesInPlay()) {
                if (!sa.canTarget(c)) {
                    continue; // hexproof, shroud, protection
                }
                if (c.hasKeyword(Keyword.WARD)) {
                    continue; // ward is priced after targeting and can counter the whole spell
                }
                if (ai.equals(c.getOwner())) {
                    continue; // our own card: control will likely come back to us
                }
                if (c.getType().isLegendary() && ai.isCardInPlay(c.getName())) {
                    continue; // the copy branch would put us to the legend rule
                }
                if (c.getAmountOfKeyword("CARDNAME's power and toughness are switched") % 2 != 0) {
                    continue; // the body it keeps cannot be modelled
                }
                final int gain = gain(c);
                if (gain < MIN_GAIN && !isLiveCommander(c)) {
                    continue;
                }
                cands.add(Pair.of(c, gain));
            }
            cands.sort((a, b) -> Integer.compare(b.getRight(), a.getRight()));

            final int max = Math.min(sa.getMaxTargets(), cands.size());
            if (max <= 0) {
                return new AiAbilityDecision(0, AiPlayDecision.CantPlayAi);
            }
            final List<Pair<Card, Integer>> chosen = cands.subList(0, max);
            boolean threat = false;
            int total = 0;
            for (final Pair<Card, Integer> p : chosen) {
                total += p.getRight();
                if (p.getRight() >= THREAT_GAIN || isLiveCommander(p.getLeft())) {
                    threat = true;
                }
            }
            if (!threat && (chosen.size() < 2 || total < MIN_TOTAL_GAIN)) {
                return new AiAbilityDecision(0, AiPlayDecision.CantPlayAi);
            }

            for (final Pair<Card, Integer> p : chosen) {
                sa.getTargets().add(p.getLeft());
            }
            if (sa.getTargets().isEmpty() || !sa.isTargetNumberValid()) {
                sa.resetTargets();
                return new AiAbilityDecision(0, AiPlayDecision.TargetingFailed);
            }
            return new AiAbilityDecision(100, AiPlayDecision.WillPlay);
        }

        // What the creature loses: its CreatureEvaluator score minus the score
        // of the body it keeps - a 1/1 plus whatever counters, anthems and
        // pumps still apply (15 per power, 10 per toughness), non-token +20,
        // cmc*5 and untapped +1 unchanged - plus a flat bonus for abilities the
        // evaluator does not price, and for a commander that still has any.
        static int gain(final Card c) {
            final int keptPower = Math.max(0, 1 + c.getNetPower() - c.getCurrentPower());
            final int keptToughness = Math.max(0, 1 + c.getNetToughness() - c.getCurrentToughness());
            final int kept = 80 + (c.isToken() ? 0 : 20) + 15 * keptPower + 10 * keptToughness
                    + c.getCMC() * 5 + (c.isUntapped() ? 1 : 0);
            int g = ComputerUtilCard.evaluateCreature(c) - kept;
            if (hasUnpricedAbility(c)) {
                g += ABILITY_BONUS;
            }
            if (isLiveCommander(c)) {
                g += COMMANDER_BONUS;
            }
            return g;
        }

        // Static abilities and triggers the evaluator does not already price.
        // Keyword-generated ones are excluded (Card.updateStaticAbilities and
        // updateTriggers append them: Flying and Fear are CantBlockBy statics,
        // ward/echo/cumulative upkeep are triggers), as are a spent self-ETB
        // and upkeep triggers, which are often drawbacks (sac-unless, damage).
        static boolean hasUnpricedAbility(final Card c) {
            for (final StaticAbility st : c.getStaticAbilities()) {
                if (st.getKeyword() == null) {
                    return true;
                }
            }
            for (final Trigger t : c.getTriggers()) {
                if (t.getKeyword() != null) {
                    continue;
                }
                if (forge.game.trigger.TriggerType.ChangesZone.equals(t.getMode())
                        && "Battlefield".equals(t.getParam("Destination"))
                        && "Card.Self".equals(t.getParam("ValidCard"))) {
                    continue;
                }
                if (forge.game.trigger.TriggerType.Phase.equals(t.getMode())
                        && "Upkeep".equals(t.getParam("Phase"))) {
                    continue;
                }
                return true;
            }
            return false;
        }

        // An opposing commander that is not already neutered: any keyword,
        // static, trigger or activated ability left to lose.
        static boolean isLiveCommander(final Card c) {
            if (!c.isCommander()) {
                return false;
            }
            if (!c.getKeywords().isEmpty() || !c.getStaticAbilities().isEmpty() || !c.getTriggers().isEmpty()) {
                return true;
            }
            for (final SpellAbility ab : c.getSpellAbilities()) {
                if (ab.isAbility()) {
                    return true;
                }
            }
            return false;
        }
    }

    // Hunter's Insight
    // "Choose target creature you control. Whenever that creature deals combat
    // damage to a player or planeswalker this turn, draw that many cards."
    // Cast only once blocks are locked in on our own turn (declare-blockers
    // step, empty stack), on the unblocked attacker we control that is
    // predicted to deal the most combat damage to the player or planeswalker
    // it attacks (prevention and double strike counted, activated pumps not
    // assumed, infect = 0), and only for at least MIN_CARDS cards.
    // The draw is mandatory, so the library floor bounds every draw the cast
    // can cause. Each copy of the spell is another whole draw trigger, and an
    // AI copy is re-aimed at our WORST targetable creature (setupTargets ->
    // EffectAi.doTriggerNoCost), not at the chosen attacker, so the floor uses
    // the most damage ANY attacker of ours is predicted to deal, blocked or
    // not, plus one per effect for copy-grown attackers (Kalamax's SpellCopy
    // counter): library >= effects * (maxDamage + effects) + LIBRARY_MARGIN.
    // Vetoes: an opponent's draw punisher (a Draw/DrawCards replacement or a
    // Drawn trigger that can see our draws: Notion Thief, Hullbreacher, Alms
    // Collector, Orcish Bowmasters, Sheoldred), and a static that grants our
    // spells conspire, replicate or casualty (the AI pays those whenever it
    // can: Wort, the Raidmother would tap our untapped blockers for a copy
    // that draws nothing).
    public static class HuntersInsight {
        public static final int MIN_CARDS = 2;
        public static final int LIBRARY_MARGIN = 5;

        public static AiAbilityDecision consider(final Player ai, final SpellAbility sa) {
            final Game game = ai.getGame();
            final Combat combat = game.getCombat();
            if (!game.getPhaseHandler().is(PhaseType.COMBAT_DECLARE_BLOCKERS, ai)
                    || !game.getStack().isEmpty() || combat == null) {
                return new AiAbilityDecision(0, AiPlayDecision.AnotherTime);
            }
            if (!ai.canDraw()) {
                return new AiAbilityDecision(0, AiPlayDecision.CantPlayAi);
            }
            if (opponentPunishesDraws(ai) || grantsCopyKeyword(ai)) {
                return new AiAbilityDecision(0, AiPlayDecision.CantPlayAi);
            }
            final int effects = effectsPerCast(ai);

            Card best = null;
            int bestDmg = 0;
            int maxDmgAll = 0;
            for (final Card attacker : combat.getAttackers()) {
                if (!ai.equals(attacker.getController())) {
                    continue;
                }
                final GameEntity defender = combat.getDefenderByAttacker(attacker);
                final int dmg = ComputerUtilCombat.damageIfUnblocked(attacker, defender, combat, true);
                maxDmgAll = Math.max(maxDmgAll, dmg); // a copy can land on any of our attackers
                if (combat.isBlocked(attacker) || !sa.canTarget(attacker)) {
                    continue;
                }
                if (!(defender instanceof Player) && !(defender instanceof Card && ((Card) defender).isPlaneswalker())) {
                    continue; // a battle is not "a player or planeswalker"
                }
                if (dmg >= MIN_CARDS && dmg > bestDmg) {
                    best = attacker;
                    bestDmg = dmg;
                }
            }
            if (best == null) {
                return new AiAbilityDecision(0, AiPlayDecision.CantPlayAi);
            }
            final int library = ai.getCardsIn(ZoneType.Library).size();
            if (library < effects * (maxDmgAll + effects) + LIBRARY_MARGIN) {
                return new AiAbilityDecision(0, AiPlayDecision.CantPlayAi);
            }

            sa.resetTargets();
            sa.getTargets().add(best);
            return new AiAbilityDecision(100, AiPlayDecision.WillPlay);
        }

        // An opponent's card that steals or punishes our draws: a Draw/DrawCards
        // replacement not limited to its own controller (ValidPlayer other than
        // exactly You), or a Drawn trigger whose ValidCard is not limited to its
        // own controller's cards (no YouCtrl/YouOwn).
        static boolean opponentPunishesDraws(final Player ai) {
            for (final Player opp : ai.getOpponents()) {
                for (final Card c : opp.getCardsIn(ZoneType.Battlefield, ZoneType.Command)) {
                    for (final ReplacementEffect re : c.getReplacementEffects()) {
                        if ((re.getMode() == ReplacementType.Draw || re.getMode() == ReplacementType.DrawCards)
                                && !"You".equals(re.getParam("ValidPlayer"))) {
                            return true;
                        }
                    }
                    for (final Trigger t : c.getTriggers()) {
                        if (t.getMode() != TriggerType.Drawn) {
                            continue;
                        }
                        final String valid = t.hasParam("ValidCard") ? t.getParam("ValidCard") : "";
                        if (!valid.contains("YouCtrl") && !valid.contains("YouOwn")) {
                            return true;
                        }
                    }
                }
            }
            return false;
        }

        // A static on our battlefield granting our spells a copy keyword the AI
        // pays whenever it can afford it.
        static boolean grantsCopyKeyword(final Player ai) {
            for (final Card c : ai.getCardsIn(ZoneType.Battlefield)) {
                for (final StaticAbility st : c.getStaticAbilities()) {
                    if (!st.hasParam("AddKeyword")) {
                        continue;
                    }
                    final String kw = st.getParam("AddKeyword");
                    if (kw.contains("Conspire") || kw.contains("Replicate") || kw.contains("Casualty")) {
                        return true;
                    }
                }
            }
            return false;
        }

        // How many copies of the draw trigger one cast can create: the spell,
        // plus one per cast trigger of ours that copies it (Kalamax, Swarm
        // Intelligence, Melek; over-counts conditional ones, which is
        // conservative), doubled by a CopySpell replacement (Twinning Staff).
        // At least 2, which covers a single copy from any other source.
        static int effectsPerCast(final Player ai) {
            int copyTriggers = 0;
            boolean copyAddsOne = false;
            for (final Card c : ai.getCardsIn(ZoneType.Battlefield)) {
                for (final Trigger t : c.getTriggers()) {
                    if (t.getMode() != TriggerType.SpellCast && t.getMode() != TriggerType.SpellCastOrCopy) {
                        continue;
                    }
                    final SpellAbility tsa = t.ensureAbility();
                    if (tsa != null && tsa.getApi() == ApiType.CopySpellAbility) {
                        copyTriggers++;
                    }
                }
                for (final ReplacementEffect re : c.getReplacementEffects()) {
                    if (re.getMode() == ReplacementType.CopySpell) {
                        copyAddsOne = true;
                    }
                }
            }
            return Math.max(2, 1 + copyTriggers * (copyAddsOne ? 2 : 1));
        }
    }

    // Imposing Grandeur
    // Each player may discard their hand and draw cards equal to the greatest
    // mana value of a commander they own on the battlefield or in the command
    // zone. Symmetric, so: cast only when OUR refill is large (net +3 cards
    // after discarding everything else in hand), we can draw all of it
    // without decking, and no opponent who would sensibly accept nets as many
    // cards as we do (our gain already excludes Grandeur itself, so a tie is
    // a relative card loss). A vetoed cast is always CantPlayAi, never
    // WaitForMain2. Each chooser (us included, at resolution) takes the wheel
    // only for a net gain. Card-local SVar:AIPriorityModifier:-4 sorts it at
    // 5 - 4 = 1, behind every creature and every spell of mana value 2 or
    // more, so main 2 spends the mana first and the wheel is re-judged on
    // the smaller hand.
    public static class ImposingGrandeur {
        public static final int MIN_CAST_GAIN = 3;
        public static final int MIN_TAKE_GAIN = 1;

        public static AiAbilityDecision consider(final Player ai, final SpellAbility sa) {
            final Card host = sa.getHostCard();
            if (host == null) {
                return new AiAbilityDecision(0, AiPlayDecision.CantPlayAi);
            }
            final int ourDraw = commanderDraw(ai);
            final int ourHand = handExcluding(ai, host);
            final int ourGain = ourDraw - ourHand;
            if (ourGain < MIN_CAST_GAIN || !canRefill(ai, ourDraw)) {
                return new AiAbilityDecision(0, AiPlayDecision.CantPlayAi);
            }
            for (final Player opp : ai.getOpponents()) {
                if (wouldTake(opp, host) && commanderDraw(opp) - handExcluding(opp, host) >= ourGain) {
                    return new AiAbilityDecision(0, AiPlayDecision.CantPlayAi);
                }
            }
            // Something else still in hand: give main 1 to it first (DrawAi's
            // main-2 idiom), so the wheel does not throw away a spell we could cast.
            if (ourHand > 0 && ai.getGame().getPhaseHandler().getPhase().isBefore(PhaseType.MAIN2)) {
                return new AiAbilityDecision(0, AiPlayDecision.WaitForMain2);
            }
            return new AiAbilityDecision(100, AiPlayDecision.WillPlay);
        }

        public static SpellAbility chooseWheel(final Player chooser, final Card host, final List<SpellAbility> spells) {
            SpellAbility yes = null, no = null;
            for (final SpellAbility sp : spells) {
                if (sp.hasParam("NoteCardsFor")) {
                    yes = sp;
                } else {
                    no = sp;
                }
            }
            if (yes == null || no == null) {
                return spells.get(0); // unexpected script shape: stock behaviour
            }
            return wouldTake(chooser, host) ? yes : no;
        }

        static boolean wouldTake(final Player p, final Card host) {
            final int draw = commanderDraw(p);
            return draw - handExcluding(p, host) >= MIN_TAKE_GAIN && canRefill(p, draw);
        }

        // mirrors SVar:X (Count$ValidBattlefield,Command Card.IsCommander+RememberedPlayerOwn$GreatestCardManaCost)
        static int commanderDraw(final Player p) {
            int best = 0;
            for (final Card c : p.getGame().getCardsIn(ZoneType.listValueOf("Battlefield,Command"))) {
                if (c.isCommander() && p.equals(c.getOwner())) {
                    best = Math.max(best, c.getCMC());
                }
            }
            return best;
        }

        static int handExcluding(final Player p, final Card host) {
            return CardLists.count(p.getCardsIn(ZoneType.Hand), c -> !c.equals(host));
        }

        // draws the whole amount (no Narset/Spirit-style cap) and survives the next draw step
        static boolean canRefill(final Player p, final int draw) {
            return draw > 0 && p.canDrawAmount(draw) && p.getCardsIn(ZoneType.Library).size() > draw;
        }
    }

    // Invert Polarity
    // Cast only in response to an opponent's spell in Commandeer's window
    // (untargeted anywhere in the chain, no "...All" api, CMC floor): winning
    // the flip steals the haymaker exactly like Commandeer, losing it counters
    // the same haymaker - both halves are pure gain in that window, so the
    // coin cannot hurt us. Two extra guards beyond Commandeer's: the spell
    // must be counterable (the lose-the-flip half is a Counter effect and
    // would fizzle on an uncounterable spell, turning the flip into a real
    // 50/50 gamble), and the floor is lower (three mana answers a four-drop).
    public static class InvertPolarity {
        public static final int MIN_SPELL_CMC = 4;

        public static AiAbilityDecision consider(final Player ai, final SpellAbility sa) {
            final Game game = ai.getGame();

            if (game.getStack().isEmpty()) {
                return new AiAbilityDecision(0, AiPlayDecision.TargetingFailed);
            }
            final SpellAbility topSA = ComputerUtilAbility.getTopSpellAbilityOnStack(game, sa);
            if (topSA == null || !topSA.isSpell()) {
                return new AiAbilityDecision(0, AiPlayDecision.TargetingFailed);
            }

            final Player caster = topSA.getActivatingPlayer();
            if (caster == null || !caster.isOpponentOf(ai) || ai.getYourTeam().contains(caster)) {
                return new AiAbilityDecision(0, AiPlayDecision.CantPlayAi);
            }

            // The lose-the-flip half counters the spell; if it can't be
            // countered, that half buys nothing - stay out of the coin flip.
            if (!topSA.isCounterableBy(null)) {
                return new AiAbilityDecision(0, AiPlayDecision.CantPlayAi);
            }

            for (SpellAbility part = topSA; part != null; part = part.getSubAbility()) {
                if (part.usesTargeting() && !part.getTargets().isEmpty()) {
                    return new AiAbilityDecision(0, AiPlayDecision.CantPlayAi);
                }
                if (part.getApi() != null && part.getApi().name().endsWith("All")) {
                    return new AiAbilityDecision(0, AiPlayDecision.CantPlayAi);
                }
            }

            int tgtCMC = 0;
            if (topSA.getPayCosts() != null && topSA.getPayCosts().getTotalMana() != null) {
                tgtCMC = topSA.getPayCosts().getTotalMana().getCMC();
                if (topSA.getPayCosts().getTotalMana().countX() > 0) {
                    tgtCMC += topSA.getXManaCostPaid() != null ? topSA.getXManaCostPaid() : 3;
                }
            }
            if (tgtCMC < MIN_SPELL_CMC) {
                return new AiAbilityDecision(0, AiPlayDecision.CantPlayAi);
            }

            sa.resetTargets();
            if (!sa.canTargetSpellAbility(topSA)) {
                return new AiAbilityDecision(0, AiPlayDecision.TargetingFailed);
            }
            sa.getTargets().add(topSA);
            return new AiAbilityDecision(100, AiPlayDecision.WillPlay);
        }
    }

    // Intuition (and any other card that might potentially let you pick N cards from the library,
    // one of which will then be picked for you by the opponent)
    public static class Intuition {
        public static CardCollection considerMultiple(final Player ai, final SpellAbility sa) {
            if (ai.getController().isAI()) {
                if (!((PlayerControllerAi) ai.getController()).getAi().getBoolProperty(AiProps.INTUITION_ALTERNATIVE_LOGIC)) {
                    return new CardCollection(); // fall back to standard ChangeZoneAi considerations
                }
            }

            int changeNum = AbilityUtils.calculateAmount(sa.getHostCard(),
                    sa.getParamOrDefault("ChangeNum", "1"), sa);
            CardCollection lib = CardLists.filter(ai.getCardsIn(ZoneType.Library),
                    CardPredicates.nameNotEquals(sa.getHostCard().getName()));
            lib.sort(CardLists.CmcComparatorInv);

            // Additional cards which are difficult to auto-classify but which are generally good to Intuition for
            List<String> highPriorityNamedCards = Lists.newArrayList("Accumulated Knowledge", "Take Inventory");

            // figure out how many of each card we have in deck
            Map<String, Long> cardAmount = lib.stream().collect(Collectors.groupingBy(Card::getName, Collectors.counting()));

            // Trix: see if we can complete the combo (if it looks like we might win shortly or if we need to get a Donate stat)
            boolean donateComboMightWin = false;
            int numIllusionsOTB = CardLists.filter(ai.getCardsIn(ZoneType.Battlefield), CardPredicates.nameEquals("Illusions of Grandeur")).size();
            if (ai.getOpponentsSmallestLifeTotal() < 20 || numIllusionsOTB > 0) {
                donateComboMightWin = true;
                int numIllusionsInHand = CardLists.filter(ai.getCardsIn(ZoneType.Hand), CardPredicates.nameEquals("Illusions of Grandeur")).size();
                int numDonateInHand = CardLists.filter(ai.getCardsIn(ZoneType.Hand), CardPredicates.nameEquals("Donate")).size();
                int numIllusionsInLib = CardLists.filter(ai.getCardsIn(ZoneType.Library), CardPredicates.nameEquals("Illusions of Grandeur")).size();
                int numDonateInLib = CardLists.filter(ai.getCardsIn(ZoneType.Library), CardPredicates.nameEquals("Donate")).size();
                CardCollection comboList = new CardCollection();
                if ((numIllusionsInHand > 0 || numIllusionsOTB > 0) && numDonateInHand == 0 && numDonateInLib >= 3) {
                    for (Card c : lib) {
                        if (c.getName().equals("Donate")) {
                            comboList.add(c);
                        }
                    }
                    return comboList;
                } else if (numDonateInHand > 0 && numIllusionsInHand == 0 && numIllusionsInLib >= 3) {
                    for (Card c : lib) {
                        if (c.getName().equals("Illusions of Grandeur")) {
                            comboList.add(c);
                        }
                    }
                    return comboList;
                }
            }

            // Create a priority list for cards that we have no more than 4 of and that are not lands
            CardCollection libPriorityList = new CardCollection();
            CardCollection libHighPriorityList = new CardCollection();
            CardCollection libLowPriorityList = new CardCollection();
            List<String> processed = Lists.newArrayList();
            for (int i = 4; i > 0; i--) {
                for (Card c : lib) {
                    if (!donateComboMightWin && (c.getName().equals("Illusions of Grandeur") || c.getName().equals("Donate"))) {
                        // Probably not worth putting two of the combo pieces into the graveyard
                        // since one Illusions-Donate is likely to not be enough
                        continue;
                    }
                    if (cardAmount.get(c.getName()) == i && !c.isLand() && !processed.contains(c.getName())) {
                        // if it's a card that is generally good to place in the graveyard, also add it
                        // to the mix
                        boolean canRetFromGrave = false;
                        String name = c.getName().replace(',', ';');
                        for (Trigger t : c.getTriggers()) {
                            SpellAbility ab = t.ensureAbility();
                            if (ab == null) {
                                continue;
                            }

                            if (ab.getApi() == ApiType.ChangeZone
                                    && "Self".equals(ab.getParam("Defined"))
                                    && "Graveyard".equals(ab.getParam("Origin"))
                                    && "Battlefield".equals(ab.getParam("Destination"))) {
                                canRetFromGrave = true;
                            }
                            if (ab.getApi() == ApiType.ChangeZoneAll
                                    && TextUtil.concatNoSpace("Creature.named", name).equals(ab.getParam("ChangeType"))
                                    && "Graveyard".equals(ab.getParam("Origin"))
                                    && "Battlefield".equals(ab.getParam("Destination"))) {
                                canRetFromGrave = true;
                            }
                        }
                        boolean isGoodToPutInGrave = c.hasSVar("DiscardMe") || canRetFromGrave
                                || (ComputerUtil.isPlayingReanimator(ai) && c.isCreature());

                        for (Card c1 : lib) {
                            if (c1.getName().equals(c.getName())) {
                                if (!ai.getCardsIn(ZoneType.Hand).anyMatch(CardPredicates.nameEquals(c1.getName()))
                                        && ComputerUtilMana.hasEnoughManaSourcesToCast(c1.getFirstSpellAbility(), ai)) {
                                    // Try not to search for things we already have in hand or that we can't cast
                                    libPriorityList.add(c1);
                                } else {
                                    libLowPriorityList.add(c1);
                                }
                                if (isGoodToPutInGrave || highPriorityNamedCards.contains(c.getName())) {
                                    libHighPriorityList.add(c1);
                                }
                            }
                        }
                        processed.add(c.getName());
                    }
                }
            }

            // If we're playing Reanimator, we're really interested just in the highest CMC spells, not the
            // ones we necessarily have multiples of
            if (ComputerUtil.isPlayingReanimator(ai)) {
                libHighPriorityList.sort(CardLists.CmcComparatorInv);
            }

            // Otherwise, try to grab something that is hopefully decent to grab, in priority order
            CardCollection chosen = new CardCollection();
            if (libHighPriorityList.size() >= changeNum) {
                for (int i = 0; i < changeNum; i++) {
                    chosen.add(libHighPriorityList.get(i));
                }
            } else if (libPriorityList.size() >= changeNum) {
                for (int i = 0; i < changeNum; i++) {
                    chosen.add(libPriorityList.get(i));
                }
            } else if (libLowPriorityList.size() >= changeNum) {
                for (int i = 0; i < changeNum; i++) {
                    chosen.add(libLowPriorityList.get(i));
                }
            }

            return chosen;
        }
    }

    // Karplusan Minotaur
    // Its cumulative upkeep is paid in coin flips, and every lost flip deals 1
    // damage to a target of the opponent's choice. Stock SpellAbilityAi pays
    // any FlipCoin cost forever, so bound the worst case (every flip lost,
    // every ping at our face): keep a buffer that grows with the flips, capped
    // at the danger threshold. Age 1 pays at life >= 3, age 2 at >= 5, age 3 at
    // >= 7, age n >= 4 at >= n + 5; life after the worst case is always >= 1.
    public static class KarplusanMinotaur {
        public static boolean willPayUpkeep(final Player ai, final SpellAbility sa) {
            if (!ai.canLoseLife() || ai.cantLoseForZeroOrLessLife()) {
                return true;
            }
            AiController aic = ((PlayerControllerAi) ai.getController()).getAi();
            int lifeInDanger = aic.getIntProperty(AiProps.AI_IN_DANGER_THRESHOLD);
            // SacrificeEffect adds this upkeep's AGE counter before asking, so counters == flips about to be paid
            int flips = sa.getHostCard().getCounters(CounterEnumType.AGE);
            return ai.getLife() > flips + Math.min(flips, lifeInDanger);
        }
    }

    // Knollspine Dragon
    // "When it enters, you may discard your hand and draw cards equal to the damage dealt to target
    // opponent this turn." The target lives on the Draw sub; the "may" is asked at resolution
    // (PlayerControllerAi.confirmTrigger -> doTrigger(sa, false)), where the stock chain
    // (DiscardAi.doTriggerNoCost's unconditional WillPlay, then DrawAi's mandatory targetAI) accepted
    // every time, even at X = 0. Both the stack-time call (mandatory) and the resolution call pick the
    // same target here; only the resolution call decides, with the floor below. RNG-free.
    public static class KnollspineDragon {
        public static AiAbilityDecision consider(final Player ai, final SpellAbility sa, final boolean mandatory) {
            final AbilitySub draw = sa.getSubAbility();
            if (draw == null || draw.getApi() != ApiType.Draw || !draw.usesTargeting()) {
                return new AiAbilityDecision(0, AiPlayDecision.CantPlayAi);
            }

            // Target: the targetable opponent dealt the most damage this turn, the same
            // getAssignedDamage sum X's TargetedPlayer$DamageThisTurn reads; game order on ties.
            draw.resetTargets();
            Player best = null;
            int bestDamage = -1;
            for (final Player opp : ai.getOpponents()) {
                if (!draw.canTarget(opp)) {
                    continue;
                }
                final int damage = opp.getAssignedDamage();
                if (damage > bestDamage) {
                    best = opp;
                    bestDamage = damage;
                }
            }
            if (best == null) {
                return new AiAbilityDecision(0, AiPlayDecision.TargetingFailed);
            }
            draw.getTargets().add(best);

            if (mandatory) {
                // Putting the trigger on the stack: only the target is decided here.
                return new AiAbilityDecision(100, AiPlayDecision.WillPlay);
            }

            // Draws actually allowed (CantDraw statics), and never within DrawAi's decking margin.
            final int drawn = StaticAbilityCantDraw.canDrawAmount(ai, bestDamage);
            if (drawn <= 0 || drawn >= ai.getCardsIn(ZoneType.Library).size() - 3) {
                return new AiAbilityDecision(0, AiPlayDecision.CantPlayAi);
            }
            if (drawOrDiscardIsPunished(ai, sa.getHostCard())) {
                return new AiAbilityDecision(0, AiPlayDecision.CantPlayAi);
            }
            // Floor: cards kept (draws capped at max hand size) against the whole hand thrown away;
            // strictly ahead on an empty hand, at least two cards ahead otherwise.
            final int hand = ai.getCardsIn(ZoneType.Hand).size();
            final int kept = ai.isUnlimitedHandSize() ? drawn : Math.min(drawn, ai.getMaxHandSize());
            if (kept < hand + (hand == 0 ? 1 : 2)) {
                return new AiAbilityDecision(0, AiPlayDecision.CantPlayAi);
            }
            return new AiAbilityDecision(100, AiPlayDecision.WillPlay);
        }

        // An opponent's draw thief (Notion Thief, Alms Collector) or draw punisher (Orcish Bowmasters,
        // Consecrated Sphinx, Spiteful Visions, Fate Unraveler, Nekusar) that would see these draws, or
        // discard punisher (Waste Not, Megrim) that would see a card of this hand, on the battlefield or
        // in the Command zone and working where it sits (zonesCheck), tested the way
        // ReplaceDraw/ReplaceDrawCards.canReplace, TriggerDrawn and TriggerDiscarded test (an absent
        // param matches). The host stands in for the drawn cards: it is ours, so Card.OppOwn matches.
        // Over-declines (a symmetric beneficial Drawn trigger) are fine: declining keeps the 7/5 flyer.
        private static boolean drawOrDiscardIsPunished(final Player ai, final Card host) {
            final Game game = ai.getGame();
            for (final Card c : game.getCardsIn(Arrays.asList(ZoneType.Battlefield, ZoneType.Command))) {
                final Player controller = c.getController();
                if (controller == null || !controller.isOpponentOf(ai)) {
                    continue;
                }
                for (final ReplacementEffect re : c.getReplacementEffects()) {
                    if ((re.getMode() == ReplacementType.Draw || re.getMode() == ReplacementType.DrawCards)
                            && re.zonesCheck(game.getZoneOf(c)) && re.matchesValidParam("ValidPlayer", ai)) {
                        return true;
                    }
                }
                for (final Trigger t : c.getTriggers()) {
                    if (!t.zonesCheck(game.getZoneOf(c)) || !t.matchesValidParam("ValidPlayer", ai)) {
                        continue;
                    }
                    if (t.getMode() == TriggerType.Drawn && t.matchesValidParam("ValidCard", host)) {
                        return true;
                    }
                    if (t.getMode() == TriggerType.Discarded) {
                        for (final Card h : ai.getCardsIn(ZoneType.Hand)) {
                            if (t.matchesValidParam("ValidCard", h)) {
                                return true;
                            }
                        }
                    }
                }
            }
            return false;
        }
    }

    // Last Night Together
    // "Choose two target creatures. Untap them, put two +1/+1 counters on each,
    // they gain vigilance, indestructible and haste until end of turn. After this
    // main phase, an additional combat phase in which only they can attack."
    // ChooseCardAi's targeting only ever searches for an opponent PLAYER, so this
    // creature-targeted spell was CantPlayAi on every look. Pick the targets here:
    // two creatures we own and control, at least one of them able to attack an
    // opponent once untapped and hasted, and 5+ total post-counter power among the
    // attack-capable picks. RNG-free, like the stock decline it replaces: attack
    // capability is checked against every opponent player instead of
    // AiAttackController.choosePreferredDefenderPlayer, which draws
    // Aggregates.random with two or more opponents (identical in 1v1).
    public static class LastNightTogether {
        static final int COUNTERS = 2;
        static final int MIN_SWING_POWER = 5;

        public static AiAbilityDecision consider(final Player ai, final SpellAbility sa) {
            final Game game = ai.getGame();
            final PhaseHandler ph = game.getPhaseHandler();
            sa.resetTargets();

            // "After this main phase" must be our own main phase, with nothing on the stack.
            if (!ph.isPlayerTurn(ai) || !ph.getPhase().isMain() || !game.getStack().isEmpty()) {
                return new AiAbilityDecision(0, AiPlayDecision.CantPlayAi);
            }
            if (ai.getOpponents().isEmpty()) {
                return new AiAbilityDecision(0, AiPlayDecision.CantPlayAi);
            }

            final CardCollection attackers = new CardCollection();
            final CardCollection others = new CardCollection();
            for (final Card c : ai.getCreaturesInPlay()) {
                if (!ai.equals(c.getOwner())) {
                    continue; // borrowed (e.g. Become the Pilot): the counters would go back with it
                }
                if (!sa.canTarget(c) || ComputerUtilCard.isUselessCreature(ai, c)) {
                    continue;
                }
                if (c.getNetPower() + COUNTERS > 0 && canSwing(ai, c)) {
                    attackers.add(c);
                } else {
                    others.add(c);
                }
            }
            if (attackers.isEmpty() || attackers.size() + others.size() < 2) {
                return new AiAbilityDecision(0, AiPlayDecision.CantPlayAi);
            }
            ComputerUtilCard.sortByEvaluateCreature(attackers);
            ComputerUtilCard.sortByEvaluateCreature(others);
            final CardCollection picks = new CardCollection();
            for (final Card c : attackers) {
                if (picks.size() < 2) {
                    picks.add(c);
                }
            }
            for (final Card c : others) {
                if (picks.size() < 2) {
                    picks.add(c);
                }
            }

            int swing = 0;
            for (final Card c : picks) {
                if (attackers.contains(c)) {
                    swing += c.getNetPower() + COUNTERS;
                }
            }
            if (swing < MIN_SWING_POWER) {
                return new AiAbilityDecision(0, AiPlayDecision.CantPlayAi);
            }

            for (final Card c : picks) {
                sa.getTargets().add(c);
            }
            if (!sa.isTargetNumberValid()) {
                sa.resetTargets();
                return new AiAbilityDecision(0, AiPlayDecision.TargetingFailed);
            }
            return new AiAbilityDecision(100, AiPlayDecision.WillPlay);
        }

        // The AI wrapper skips the tapped and summoning-sick checks the spell
        // itself solves (untap, haste) but keeps Defender, CantAttack statics,
        // goad, a skipped combat and a must-attack-another-entity requirement.
        private static boolean canSwing(final Player ai, final Card c) {
            for (final Player opp : ai.getOpponents()) {
                if (ComputerUtilCombat.canAttackNextTurn(c, opp)) {
                    return true;
                }
            }
            return false;
        }
    }

    // Launch the Fleet
    // "Strive {1}: until end of turn, any number of target creatures each gain
    // 'Whenever this creature attacks, create a 1/1 Soldier token tapped and
    // attacking.'" Target only our own creatures that the AI's own attack plan
    // (AiController.getPredictedCombat, already built by AnimateAi.checkAiLogic)
    // sends in, so no creature attacks because of this spell. Stop adding targets
    // at the first one Strive makes unaffordable, and cast only for at least
    // MIN_TOKENS tokens. Decline while any predicted attacker faces an attack tax
    // (Propaganda, Ghostly Prison): the prediction budgets taxes against main-1
    // mana this spell would spend, and the real declaration drops attackers it
    // can no longer pay for. Skip creatures with mana abilities, which the payment
    // could tap. Every board-only exit runs before the first canPayCost, whose
    // test payment draws MyRandom; the stock refusal drew nothing.
    public static class LaunchTheFleet {
        public static final int MIN_TOKENS = 2;

        public static AiAbilityDecision consider(final Player ai, final SpellAbility sa) {
            final Game game = ai.getGame();
            final PhaseHandler ph = game.getPhaseHandler();
            // checkAiLogic added every predicted attacker; start from none.
            sa.resetTargets();

            if (!ph.isPlayerTurn(ai) || !ph.getPhase().isBefore(PhaseType.COMBAT_DECLARE_ATTACKERS)) {
                return new AiAbilityDecision(0, AiPlayDecision.AnotherTime);
            }
            final Combat predicted = ((PlayerControllerAi) ai.getController()).getAi().getPredictedCombat();
            for (final Card a : predicted.getAttackers()) {
                if (CombatUtil.getAttackCost(game, a, predicted.getDefenderByAttacker(a)) != null) {
                    return new AiAbilityDecision(0, AiPlayDecision.AnotherTime);
                }
            }
            final List<Card> candidates = Lists.newArrayList();
            for (final Card c : CardLists.getTargetableCards(ai.getCreaturesInPlay(), sa)) {
                if (predicted.isAttacking(c) && c.getManaAbilities().isEmpty()) {
                    candidates.add(c);
                }
            }
            if (candidates.size() < MIN_TOKENS) {
                return new AiAbilityDecision(0, AiPlayDecision.TargetingFailed);
            }

            for (final Card c : candidates) {
                if (!sa.canAddMoreTarget()) {
                    break;
                }
                sa.getTargets().add(c);
                // Strive: each target beyond the first costs {1} more
                if (!ComputerUtilCost.canPayCost(sa, ai, false)) {
                    sa.getTargets().remove(c);
                    break;
                }
            }
            if (sa.getTargets().size() < MIN_TOKENS) {
                sa.resetTargets();
                return new AiAbilityDecision(0, AiPlayDecision.TargetingFailed);
            }
            return new AiAbilityDecision(100, AiPlayDecision.WillPlay);
        }
    }

    // Legions to Ashes
    // Exile target nonland permanent an opponent controls and every token that
    // player controls with the same name (a Pump targeting shell; the exile is
    // the ChangeZoneAll sub). Candidates: targetable opposing permanents, never
    // a card we own, never an opponent's card carrying an Aura we control
    // (ChangeZoneAi's battlefield-exile filter), minus creatures already dying.
    // Fire, in order:
    //  1. the highest-valued same-name group, when it is a real multi-for-one
    //     (MIN_GROUP_SIZE+ permanents worth >= MIN_GROUP_VALUE);
    //  2. otherwise the stock single-target pick (getBestRemovalTargetAI over the
    //     candidates minus noncreature tokens), when the stock removal floor
    //     useRemovalNow accepts it - at most one call per consult;
    //  3. otherwise the best group that clears the multi-for-one bar, if any.
    public static class LegionsToAshes {
        public static final int MIN_GROUP_SIZE = 3;
        public static final int MIN_GROUP_VALUE = 300;

        public static AiAbilityDecision consider(final Player ai, final SpellAbility sa) {
            CardCollection candidates = CardLists.getTargetableCards(
                    ai.getOpponents().getCardsIn(ZoneType.Battlefield), sa);
            candidates = CardLists.filter(candidates, c -> {
                if (ai.equals(c.getOwner())) {
                    return false;
                }
                for (Card aura : c.getEnchantedBy()) {
                    if (c.getOwner().isOpponentOf(ai) && aura.getController().equals(ai)) {
                        return false;
                    }
                }
                return true;
            });
            candidates = ComputerUtil.filterCreaturesThatWillDieThisTurn(ai, candidates, sa);
            if (candidates.isEmpty()) {
                return new AiAbilityDecision(0, AiPlayDecision.TargetingFailed);
            }

            // deterministic battlefield order; the first wins ties
            Card bestGroup = null;
            int bestGroupValue = Integer.MIN_VALUE;
            int bestGroupSize = 0;
            Card bestMulti = null;
            int bestMultiValue = Integer.MIN_VALUE;
            for (final Card c : candidates) {
                final CardCollection group = exiledWith(c);
                int value = 0;
                for (final Card m : group) {
                    value += removalValue(m);
                }
                if (value > bestGroupValue) {
                    bestGroup = c;
                    bestGroupValue = value;
                    bestGroupSize = group.size();
                }
                if (isMultiForOne(group.size(), value) && value > bestMultiValue) {
                    bestMulti = c;
                    bestMultiValue = value;
                }
            }

            if (isMultiForOne(bestGroupSize, bestGroupValue)) {
                return target(sa, bestGroup);
            }

            final CardCollection singles = CardLists.filter(candidates,
                    c -> c.isCreature() || !(c.isToken() || c.isTokenCard()));
            final Card pick = ComputerUtilCard.getBestRemovalTargetAI(ai, singles);
            if (pick != null && ComputerUtilCard.useRemovalNow(sa, pick, 0, ZoneType.Exile)) {
                return target(sa, pick);
            }

            if (bestMulti != null) {
                return target(sa, bestMulti);
            }
            return new AiAbilityDecision(0, AiPlayDecision.CantPlayAi);
        }

        private static boolean isMultiForOne(final int size, final int value) {
            return size >= MIN_GROUP_SIZE && value >= MIN_GROUP_VALUE;
        }

        private static AiAbilityDecision target(final SpellAbility sa, final Card t) {
            sa.resetTargets();
            sa.getTargets().add(t);
            return new AiAbilityDecision(100, AiPlayDecision.WillPlay);
        }

        // Mirrors the ChangeZoneAll sub: the target plus every token its
        // controller controls that shares its name
        // (TargetedCard.Self,Card.NotDefinedTargeted+token+sharesNameWith Targeted+ControlledBy TargetedController).
        private static CardCollection exiledWith(final Card t) {
            final CardCollection group = new CardCollection(t);
            for (final Card m : t.getController().getCardsIn(ZoneType.Battlefield)) {
                if (m != t && (m.isToken() || m.isTokenCard()) && m.sharesNameWith(t)) {
                    group.add(m);
                }
            }
            return group;
        }

        // ComputerUtilCard.evaluateRemovalTargetPriority's per-card term for a
        // nonland permanent (that method is private and adds a per-controller
        // board term that must not be summed once per group member).
        private static int removalValue(final Card c) {
            if (c.isCreature()) {
                return ComputerUtilCard.evaluateCreature(c);
            }
            int v = 50 + 30 * c.getCMC();
            if (c.isPlaneswalker()) {
                v += c.getCounters(CounterEnumType.LOYALTY) * 10;
            }
            return v;
        }
    }

    // Life's Legacy
    // "As an additional cost to cast this spell, sacrifice a creature. Draw cards equal to the
    // sacrificed creature's power." Three generic gates keep it dead once AI:RemoveDeck is gone:
    // NumCards Sacrificed$CardPower reads 0 before payment (DrawAi.targetAI's "draws nothing" veto),
    // the SacCost preference finds no creature under the Default profile (willPayCosts), and the
    // payment's fallback is getWorstAI - the lowest-power body, which draws nothing. One chooser
    // answers all three: DrawAi.checkApiLogic routes the decision here and
    // ComputerUtil.getCardPreference("SacCost") asks chooseSacrifice for this card's own cost, so the
    // creature the decision priced is the creature the payment sacrifices.
    // Only bodies that are leaving or free anyway are cashed in: blitzed creatures and tokens with an
    // end-of-turn leave, SacMe creatures, active undying/persist, useless creatures (2+ useful draws),
    // and other tokens (3+). A real nontoken body is never sacrificed. Own main 2 only, after combat.
    // The chooser draws no random numbers: its blocker-safety test sums unblocked damage instead of
    // asking AiBlockController, whose random trade and gang blocks could flip the verdict between the
    // decision and the payment and sacrifice the 0/1 the decision meant to avoid.
    public static class LifesLegacy {
        static final int FREE_FLOOR = 2;  // useful draws for a body that is leaving or free anyway
        static final int TOKEN_FLOOR = 3; // useful draws for any other token

        public static boolean handles(final Card source) {
            return source != null && "Life's Legacy".equals(source.getName());
        }

        public static AiAbilityDecision consider(final Player ai, final SpellAbility sa) {
            final Card source = sa.getHostCard();
            if (!ai.canDraw()) {
                return new AiAbilityDecision(0, AiPlayDecision.CantPlayAi);
            }
            // attack first: blitzed bodies swing with haste, then become cards before the end-step sacrifice
            if (!ai.getGame().getPhaseHandler().is(PhaseType.MAIN2, ai)) {
                return new AiAbilityDecision(0, AiPlayDecision.WaitForMain2);
            }
            final CostSacrifice sac = sa.getPayCosts() == null ? null
                    : sa.getPayCosts().getCostPartByType(CostSacrifice.class);
            if (sac == null) {
                return new AiAbilityDecision(0, AiPlayDecision.CantPlayAi);
            }
            final CardCollection options = CardLists.filter(
                    CardLists.getValidCards(ai.getCardsIn(ZoneType.Battlefield), sac.getType().split(";"), ai, source, sa),
                    CardPredicates.canBeSacrificedBy(sa, false));
            return chooseSacrifice(ai, source, options) == null
                    ? new AiAbilityDecision(0, AiPlayDecision.CostNotAcceptable)
                    : new AiAbilityDecision(100, AiPlayDecision.WillPlay);
        }

        // Called at the decision (consider, then willPayCosts) and again at the payment
        // (chooseSacrificeType), both through getCardPreference after the first: a function of the board
        // alone, so it returns the same creature each time. Every cost decision is taken before any mana
        // is tapped (AiCostDecision.paysRightAfterDecision is false); the only move in between is this
        // spell going from hand to stack, which the hand count accounts for.
        public static Card chooseSacrifice(final Player ai, final Card source, final Iterable<Card> options) {
            int hand = ai.getCardsIn(ZoneType.Hand).size();
            if (source.isInZone(ZoneType.Hand)) {
                hand--; // decision time: the spell itself leaves the hand (at payment it is on the stack)
            }
            final int library = ai.getCardsIn(ZoneType.Library).size();
            final int room = ai.isUnlimitedHandSize() ? Integer.MAX_VALUE : Math.max(0, ai.getMaxHandSize() - hand);
            // "can't draw more than N cards each turn" caps what the spell really draws
            final int drawCap = StaticAbilityCantDraw.canDrawAmount(ai, Integer.MAX_VALUE);
            Boolean safe = null; // survivesUnblocked, computed at most once per call

            Card best = null;
            int bestTier = 0, bestUseful = 0, bestEval = 0;
            for (final Card c : options) {
                if (!c.isCreature()) {
                    continue;
                }
                final int power = c.getNetPower();
                if (power <= 0 || power > library - 3) {
                    continue; // draws nothing / would deck us
                }
                // cards past max hand size are discarded at cleanup
                final int useful = Math.min(power, Math.min(room, drawCap));
                // "leaving anyway": blitz sacrifices it at the end step (and draws a card when it dies);
                // a token with any end-of-turn leave ceases to exist. Nontoken Dash/Warp/AtEOT are NOT
                // free - those can return to hand or be recast from exile.
                final boolean leaving = "Blitz".equals(c.getSVar("EndOfTurnLeavePlay"))
                        || (c.isToken() && c.hasSVar("EndOfTurnLeavePlay"));
                final int tier, floor;
                if (leaving || c.hasSVar("SacMe") || ComputerUtilCard.hasActiveUndyingOrPersist(c)
                        || ComputerUtilCard.isUselessCreature(ai, c)) {
                    tier = 0;
                    floor = FREE_FLOOR;
                } else if (c.isToken()) {
                    tier = 1;
                    floor = TOKEN_FLOOR;
                } else {
                    continue; // a real nontoken body is never cashed in
                }
                if (useful < floor) {
                    continue;
                }
                if (!leaving) {
                    if (c.isCommander()) {
                        continue; // it would come back only for commander tax
                    }
                    if (safe == null) {
                        safe = survivesUnblocked(ai);
                    }
                    if (!safe) {
                        continue; // it may be a blocker we need
                    }
                }
                // lowest tier, then most useful draws, then the least valuable body, then card id
                final int eval = ComputerUtilCard.evaluateCreature(c);
                if (best == null || tier < bestTier
                        || (tier == bestTier && (useful > bestUseful
                        || (useful == bestUseful && (eval < bestEval
                        || (eval == bestEval && c.getId() < best.getId())))))) {
                    best = c;
                    bestTier = tier;
                    bestUseful = useful;
                    bestEval = eval;
                }
            }
            return best;
        }

        // Deterministic next-turn safety: the AI survives every opponent's next attack even if nothing
        // blocks, so no creature it cashes in was a needed blocker. Independent of the candidate.
        private static boolean survivesUnblocked(final Player ai) {
            if (ai.cantLose()) {
                return true;
            }
            int damage = 0, poison = 0;
            for (final Player opp : ai.getOpponents()) {
                final CardCollection attackers = CardLists.filter(opp.getCreaturesInPlay(),
                        c -> ComputerUtilCombat.canAttackNextTurn(c, ai));
                damage += ComputerUtilCombat.sumDamageIfUnblocked(attackers, ai);
                poison += ComputerUtilCombat.sumPoisonIfUnblocked(attackers, ai);
            }
            final boolean lifeSafe = ai.cantLoseForZeroOrLessLife()
                    || ai.getLife() - damage >= AiProfileUtil.getIntProperty(ai, AiProps.AI_IN_DANGER_THRESHOLD);
            return lifeSafe && ai.getPoisonCounters() + poison < 7;
        }
    }

    // Lifestream's Blessing
    // "Draw X cards, where X is the greatest power among creatures you controlled as you cast
    // this spell. If this spell was cast from exile, you gain twice X life." X is not ours to
    // choose, so decline unless it is a real draw that cannot deck us, does not just discard at
    // our own cleanup, and does not feed an opponent's draw replacement or draw punisher.
    public static class LifestreamsBlessing {
        public static final int MIN_DRAW = 3;      // six mana (five foretold) for at least three cards
        public static final int LIBRARY_KEEP = 7;  // library left after the forced X

        public static boolean consider(final Player ai, final SpellAbility sa) {
            final Game game = ai.getGame();
            // The cast locks X from game.getLastStateBattlefield() as MagicStack.addAndUnfreeze
            // stores it, and nothing between this decision and that push refreshes it. The live
            // board can differ (a creature killed by combat damage stays in the snapshot until
            // the next play or resolution), so read the snapshot, with AbilityUtils' own fallback.
            CardCollectionView lki = game.getLastStateBattlefield();
            if (lki == null || lki.isEmpty()) {
                lki = game.getCardsIn(ZoneType.Battlefield);
            }
            int x = 0;
            for (final Card c : lki) {
                if (c.isCreature() && ai.equals(c.getController())) {
                    x = Math.max(x, c.getNetPower());
                }
            }
            if (x < MIN_DRAW || !ai.canDrawAmount(x)) {
                return false;
            }
            if (ai.getCardsIn(ZoneType.Library).size() - x < LIBRARY_KEEP) {
                return false; // never mill ourselves toward a loss on a forced X
            }
            int hand = ai.getCardsIn(ZoneType.Hand).size();
            final Card source = sa.getHostCard();
            if (source != null && source.isInZone(ZoneType.Hand)) {
                hand--; // the spell itself is spent
            }
            if (!ai.isUnlimitedHandSize()) {
                if (game.getPhaseHandler().isPlayerTurn(ai) && hand + x > ai.getMaxHandSize()) {
                    return false; // the surplus would go at our own cleanup
                }
                if (hand > ai.getMaxHandSize()) {
                    return false; // mirror DrawAi: already over max
                }
            }
            for (final Card c : ai.getOpponents().getCardsIn(ZoneType.Battlefield)) {
                for (final ReplacementEffect re : c.getReplacementEffects()) {
                    if (re.getMode() == ReplacementType.Draw) {
                        return false; // Notion Thief, Hullbreacher
                    }
                }
                for (final Trigger t : c.getTriggers()) {
                    // Orcish Bowmasters, Underworld Dreams, Fate Unraveler (Card.OppOwn), Spiteful
                    // Visions (Card), Sheoldred's punisher (Card.OppCtrl). An opponent's own-draw
                    // trigger (Card.YouOwn, Card.YouCtrl) is not about our draws.
                    if (t.getMode() == TriggerType.Drawn && !t.getParamOrDefault("ValidCard", "").contains("You")) {
                        return false;
                    }
                }
            }
            return true;
        }
    }

    // Living Death (and other similar cards using AILogic LivingDeath or AILogic ReanimateAll)
    public static class LivingDeath {
        public static AiAbilityDecision consider(final Player ai, final SpellAbility sa) {
            // if there's another reanimator card currently suspended, don't cast a new one until the previous
            // one resolves, otherwise the reanimation attempt will be ruined (e.g. Living End)
            for (Card ex : ai.getCardsIn(ZoneType.Exile)) {
                if (ex.hasSVar("IsReanimatorCard") && ex.getCounters(CounterEnumType.TIME) > 0) {
                    return new AiAbilityDecision(0, AiPlayDecision.CantPlayAi);
                }
            }

            int aiBattlefieldPower = 0, aiGraveyardPower = 0;
            int threshold = 320; // approximately a 4/4 Flying creature worth of extra value

            CardCollection aiCreaturesInGY = CardLists.filter(ai.getZone(ZoneType.Graveyard).getCards(), CardPredicates.CREATURES);

            if (aiCreaturesInGY.isEmpty()) {
                // nothing in graveyard, so cut short
                return new AiAbilityDecision(0, AiPlayDecision.CantPlayAi);
            }

            for (Card c : ai.getCreaturesInPlay()) {
                if (!ComputerUtilCard.isUselessCreature(ai, c)) {
                    aiBattlefieldPower += ComputerUtilCard.evaluateCreature(c);
                }
            }
            for (Card c : aiCreaturesInGY) {
                aiGraveyardPower += ComputerUtilCard.evaluateCreature(c);
            }

            int oppBattlefieldPower = 0, oppGraveyardPower = 0;
            List<Player> opponents = ai.getOpponents();
            for (Player p : opponents) {
                int playerPower = 0;
                int tempGraveyardPower = 0;
                for (Card c : p.getCreaturesInPlay()) {
                    playerPower += ComputerUtilCard.evaluateCreature(c);
                }
                for (Card c : CardLists.filter(p.getZone(ZoneType.Graveyard).getCards(), CardPredicates.CREATURES)) {
                    tempGraveyardPower += ComputerUtilCard.evaluateCreature(c);
                }
                if (playerPower > oppBattlefieldPower) {
                    oppBattlefieldPower = playerPower;
                }
                if (tempGraveyardPower > oppGraveyardPower) {
                    oppGraveyardPower = tempGraveyardPower;
                }
            }

            // if we get more value out of this than our opponent does (hopefully), go for it
            if ((aiGraveyardPower - aiBattlefieldPower) > (oppGraveyardPower - oppBattlefieldPower + threshold)) {
                return new AiAbilityDecision(100, AiPlayDecision.WillPlay);
            } else {
                return new AiAbilityDecision(0, AiPlayDecision.CantPlayAi);
            }
        }
    }

    // Mandate of Abaddon
    // "Choose target creature you control. Destroy all creatures with power less
    // than that creature's power." ChooseCardAi's generic branch only targets
    // opponent players, which ValidTgts$ Creature.YouCtrl never matches, so the
    // card was never cast. The target only sets X (Targeted$CardPower) for the
    // DestroyAll sub and always survives it. A threshold qualifies only if it
    // clears the sub's own Main-1 margin against the FIRST opponent, the only one
    // DestroyAllAi.doMassRemovalLogic judges (200 / opponents): that opponent's
    // destroyable creatures below it must be worth more than ours below it plus
    // the margin. The sub then approves on its Main-1 check, so its survival
    // branch - built for a full wrath, blind to the big attacker surviving a
    // threshold wrath, and tripped by any commander near 21 damage - is never the
    // reason for a cast. Among qualifying thresholds, keep the best net value
    // over all opponents.
    public static class MandateOfAbaddon {
        public static AiAbilityDecision consider(final Player ai, final SpellAbility sa) {
            sa.resetTargets();
            final AbilitySub sub = sa.getSubAbility();
            if (!sa.usesTargeting() || sub == null || sub.getApi() != ApiType.DestroyAll) {
                return new AiAbilityDecision(0, AiPlayDecision.CantPlayAi);
            }
            final PlayerCollection opps = ai.getOpponents();
            final Player first = opps.getFirst();
            if (first == null) {
                return new AiAbilityDecision(0, AiPlayDecision.CantPlayAi);
            }
            final int margin = 200 / opps.size(); // DestroyAllAi's CREATURE_EVAL_THRESHOLD for an untargeted sub

            final CardCollectionView ours = ai.getCreaturesInPlay();
            final CardCollectionView firstCreatures = first.getCreaturesInPlay();
            final CardCollectionView theirs = opps.getCreaturesInPlay();
            Card best = null;
            int bestNet = Integer.MIN_VALUE;
            for (final Card c : ours) {
                if (!sa.canTarget(c)) {
                    continue;
                }
                final int power = c.getNetPower();
                final CardCollection firstLoses = CardLists.filter(firstCreatures, x -> x.getNetPower() < power && destroyable(x));
                if (firstLoses.isEmpty()) {
                    continue;
                }
                final int weLose = ComputerUtilCard.evaluateCreatureList(
                        CardLists.filter(ours, x -> x.getNetPower() < power && destroyable(x)));
                if (weLose + margin >= ComputerUtilCard.evaluateCreatureList(firstLoses)) {
                    continue; // the sub's Main-1 margin fails for this threshold
                }
                final int net = ComputerUtilCard.evaluateCreatureList(
                        CardLists.filter(theirs, x -> x.getNetPower() < power && destroyable(x))) - weLose;
                if (net > bestNet) {
                    bestNet = net;
                    best = c;
                }
            }
            if (best == null) {
                return new AiAbilityDecision(0, AiPlayDecision.TargetingFailed);
            }
            sa.getTargets().add(best);
            return new AiAbilityDecision(100, AiPlayDecision.WillPlay);
        }

        // DestroyAllAi's private predicate (DestroyAllAi.java:20), mirrored.
        private static boolean destroyable(final Card c) {
            return !(c.hasKeyword(Keyword.INDESTRUCTIBLE) || c.getCounters(CounterEnumType.SHIELD) > 0 || c.hasSVar("SacMe"));
        }
    }

    // Mass Diminish
    //
    // Until our next turn, creatures target player controls have base power and
    // toughness 1/1. AnimateAllAi.canPlay has no logic for it and never picks the
    // required player target (AILogic$ Always would pay for the spell and then
    // have MagicStack.add refuse it for failing to target). Target only an
    // opponent, only on our own turn, and only when the shrink is worth a card:
    // - Defensive: the creatures that can attack us next turn lose at least
    //   MIN_POWER_REMOVED base power in total, and at least one of them loses
    //   MIN_SINGLE_CUT or more (a board of 2/2 tokens is not worth it). The
    //   effect lasts through their whole turn.
    // - Offensive: before attackers, we have an attacker with power 2 or more,
    //   and the opponent's non-deathtouch creatures that can block one of those
    //   attackers (flying, reach, shadow and CantBlockBy statics included) in a
    //   block that matters today lose at least MIN_TOUGHNESS_REMOVED toughness.
    // Base P/T is read with getCurrentPower/getCurrentToughness, so counters and
    // pumps, which the spell does not touch, are not counted. A creature already
    // at 1/1 counts nothing, so a second cast or the flashback right after the
    // first never fires.
    public static class MassDiminish {
        public static final int MIN_POWER_REMOVED = 4;
        public static final int MIN_SINGLE_CUT = 2;
        public static final int MIN_TOUGHNESS_REMOVED = 4;

        public static AiAbilityDecision consider(final Player ai, final SpellAbility sa) {
            final PhaseHandler ph = ai.getGame().getPhaseHandler();

            // Routing through AnimateAllAi.canPlay's name gate bypasses the base
            // class's restriction check, so mirror it here.
            if (sa.getRestrictions() != null && !sa.getRestrictions().canPlay(sa.getHostCard(), sa)) {
                return new AiAbilityDecision(0, AiPlayDecision.CantPlaySa);
            }

            // Elsha of the Infinite lets this be cast from the top of the library
            // as though it had flash; "until your next turn" cast on an opponent's
            // turn expires before that turn's remaining combat, so only ever cast
            // it on our own turn.
            if (!ph.isPlayerTurn(ai)) {
                return new AiAbilityDecision(0, AiPlayDecision.CantPlayAi);
            }

            final CardCollection attackers = new CardCollection();
            if (ph.getPhase().isBefore(PhaseType.COMBAT_DECLARE_ATTACKERS)) {
                for (Card c : ai.getCreaturesInPlay()) {
                    if (c.getNetPower() >= 2 && CombatUtil.canAttack(c)) {
                        attackers.add(c);
                    }
                }
            }

            Player best = null;
            int bestScore = 0;
            for (Player opp : ai.getOpponents()) {
                if (!sa.canTarget(opp)) {
                    continue;
                }
                int powerRemoved = 0, biggestCut = 0, toughnessRemoved = 0;
                for (Card c : opp.getCreaturesInPlay()) {
                    if (ComputerUtilCombat.canAttackNextTurn(c, ai)) {
                        // may be -1 for a 0-power body: it grows
                        int cut = c.getCurrentPower() - 1;
                        powerRemoved += cut;
                        biggestCut = Math.max(biggestCut, cut);
                    }
                    if (!attackers.isEmpty() && !c.hasKeyword(Keyword.DEATHTOUCH) && blocksAnAttacker(c, attackers)) {
                        toughnessRemoved += Math.max(0, c.getCurrentToughness() - 1);
                    }
                }
                final boolean defensive = powerRemoved >= MIN_POWER_REMOVED && biggestCut >= MIN_SINGLE_CUT;
                final boolean offensive = toughnessRemoved >= MIN_TOUGHNESS_REMOVED;
                if (!defensive && !offensive) {
                    continue;
                }
                final int score = Math.max(0, powerRemoved) + toughnessRemoved;
                if (score > bestScore) {
                    bestScore = score;
                    best = opp;
                }
            }
            if (best == null) {
                return new AiAbilityDecision(0, AiPlayDecision.CantPlayAi);
            }
            sa.resetTargets();
            sa.getTargets().add(best);
            return new AiAbilityDecision(100, AiPlayDecision.WillPlay);
        }

        // A block counts only if the blocker can legally block one of our
        // attackers (CombatUtil.canBlock(attacker, blocker) also excludes tapped
        // blockers) and the block matters today: it survives that attacker or
        // kills it. Chump blockers that are already dead count nothing.
        private static boolean blocksAnAttacker(final Card blocker, final CardCollection attackers) {
            for (Card a : attackers) {
                if (CombatUtil.canBlock(a, blocker)
                        && (blocker.getNetToughness() > a.getNetPower() || blocker.getNetPower() >= a.getNetToughness())) {
                    return true;
                }
            }
            return false;
        }
    }

    // Maze's End
    public static class MazesEnd {
        public static AiAbilityDecision consider(final Player ai, final SpellAbility sa) {
            PhaseHandler ph = ai.getGame().getPhaseHandler();
            CardCollection availableGates = CardLists.filter(ai.getCardsIn(ZoneType.Library), CardPredicates.isType("Gate"));

            if (ph.is(PhaseType.END_OF_TURN) && ph.getNextTurn() == ai && !availableGates.isEmpty()) {
                return new AiAbilityDecision(100, AiPlayDecision.WillPlay);
            }

            if (availableGates.isEmpty()) {
                // No gates available, so don't activate Maze's End
                return new AiAbilityDecision(0, AiPlayDecision.MissingNeededCards);
            }

            return new AiAbilityDecision(0, AiPlayDecision.AnotherTime);
        }

        public static Card considerCardToGet(final Player ai, final SpellAbility sa) {
            CardCollection currentGates = CardLists.filter(ai.getCardsIn(ZoneType.Battlefield), CardPredicates.isType("Gate"));
            CardCollection availableGates = CardLists.filter(ai.getCardsIn(ZoneType.Library), CardPredicates.isType("Gate"));

            if (availableGates.isEmpty())
                return null; // shouldn't get here

            for (Card gate : availableGates) {
                if (!currentGates.anyMatch(CardPredicates.nameEquals(gate.getName()))) {
                    // Diversify our mana base
                    return gate;
                }
            }

            // Fetch a random gate if we already have all types
            return Aggregates.random(availableGates);
        }
    }

    // Mairsil, the Pretender
    public static class MairsilThePretender {
        // Scan the fetch list for a card with at least one activated ability.
        // TODO: can be improved to a full consider(sa, ai) logic which would scan the graveyard first and hand last
        public static Card considerCardFromList(final CardCollection fetchList, SpellAbility sa) {
            CardCollectionView caged = CardLists.filter(sa.getActivatingPlayer().getCardsIn(ZoneType.Exile),
                    CardPredicates.hasCounter(CounterType.getType("CAGE")));
            return fetchList.stream().filter(CardPredicates.ARTIFACTS.or(CardPredicates.CREATURES))
                .filter(c -> c.getSpellAbilities().stream().anyMatch(SpellAbility::isActivatedAbility))
                .filter(c -> caged.stream().noneMatch(CardPredicates.sharesNameWith(c)))
                .findFirst().orElse(null);
        }
    }

    // Meteor Blast
    // "X R R R: 4 damage to each of X targets" - the generic DamageDealAi
    // never announces this X (its X handling is keyed on NumDmg$ X, and here
    // NumDmg is the fixed 4), so TargetMin/Max$ X read X=0 and the spell can
    // never choose a target. Announce X ourselves and pick only targets that
    // are pure gain: opponents' creatures that 4 damage actually kills (worth
    // at least a real card), the opponent's face when 4 damage is lethal, and
    // face padding only on top of at least one kill. Floor: two such targets,
    // or lethal face.
    public static class MeteorBlast {
        public static final int DMG = 4;
        public static final int MIN_TARGETS = 2;

        public static AiAbilityDecision consider(final Player ai, final SpellAbility sa) {
            // Routing from DamageDealAi.canPlay bypasses the base class's
            // restriction check, so mirror it here.
            if (sa.getRestrictions() != null && !sa.getRestrictions().canPlay(sa.getHostCard(), sa)) {
                return new AiAbilityDecision(0, AiPlayDecision.CantPlaySa);
            }

            final Card source = sa.getHostCard();
            sa.setXManaCostPaid(null);
            final int maxX = ComputerUtilCost.setMaxXValue(sa, ai, false);
            if (maxX <= 0) {
                return new AiAbilityDecision(0, AiPlayDecision.CantAffordX);
            }

            // Creatures 4 damage kills, best first; only bodies worth a card.
            CardCollection kills = new CardCollection();
            for (Card c : ai.getOpponents().getCreaturesInPlay()) {
                if (!sa.canTarget(c)) {
                    continue;
                }
                if (ComputerUtilCombat.getEnoughDamageToKill(c, DMG, source, false, true) > DMG) {
                    continue;
                }
                if (c.getCMC() >= 2 || c.getNetPower() >= 3) {
                    kills.add(c);
                }
            }
            ComputerUtilCard.sortByEvaluateCreature(kills);

            Player lethalFace = null;
            Player anyFace = null;
            for (Player opp : ai.getOpponents()) {
                if (!sa.canTarget(opp) || !opp.canLoseLife()) {
                    continue;
                }
                if (anyFace == null) {
                    anyFace = opp;
                }
                if (opp.getLife() <= DMG && !opp.cantLoseForZeroOrLessLife()) {
                    lethalFace = opp;
                    break;
                }
            }

            int x = Math.min(maxX, kills.size() + (lethalFace != null || (anyFace != null && !kills.isEmpty()) ? 1 : 0));
            if (x < MIN_TARGETS && !(lethalFace != null && x >= 1)) {
                return new AiAbilityDecision(0, AiPlayDecision.CantPlayAi);
            }

            sa.setXManaCostPaid(x);
            sa.resetTargets();
            int added = 0;
            if (lethalFace != null) {
                sa.getTargets().add(lethalFace);
                added++;
            }
            for (Card c : kills) {
                if (added >= x) {
                    break;
                }
                sa.getTargets().add(c);
                added++;
            }
            if (added < x && lethalFace == null && anyFace != null) {
                sa.getTargets().add(anyFace);
                added++;
            }
            if (added != x || !sa.isTargetNumberValid()) {
                sa.resetTargets();
                sa.setXManaCostPaid(null);
                return new AiAbilityDecision(0, AiPlayDecision.TargetingFailed);
            }
            return new AiAbilityDecision(100, AiPlayDecision.WillPlay);
        }
    }

    // Mimic Vat
    public static class MimicVat {
        public static boolean considerExile(final Player ai, final SpellAbility sa) {
            final Card source = sa.getHostCard();
            final Card exiledWith = source.getImprintedCards().isEmpty() ? null : source.getImprintedCards().getFirst();
            final List<Card> defined = AbilityUtils.getDefinedCards(sa.getHostCard(), sa.getParam("Defined"), sa);
            final Card tgt = defined.isEmpty() ? null : defined.get(0);

            return exiledWith == null || (tgt != null && ComputerUtilCard.evaluateCreature(tgt) > ComputerUtilCard.evaluateCreature(exiledWith));
        }

        public static AiAbilityDecision considerCopy(final Player ai, final SpellAbility sa) {
            final Card source = sa.getHostCard();
            final Card exiledWith = source.getImprintedCards().isEmpty() ? null : source.getImprintedCards().getFirst();

            if (exiledWith == null) {
                return new AiAbilityDecision(0, AiPlayDecision.MissingNeededCards);
            }

            // We want to either be able to attack with the creature, or keep it until our opponent's end of turn as a
            // potential blocker
            if (ComputerUtilCard.doesSpecifiedCreatureAttackAI(ai, exiledWith)
                    || (ai.getGame().getPhaseHandler().getPlayerTurn().isOpponentOf(ai) && ai.getGame().getCombat() != null
                    && !ai.getGame().getCombat().getAttackers().isEmpty())) {
                return new AiAbilityDecision(100, AiPlayDecision.WillPlay);
            } else {
                return new AiAbilityDecision(0, AiPlayDecision.CantPlayAi);
            }
        }
    }

    // Momentous Fall
    // "As an additional cost to cast this spell, sacrifice a creature. You draw cards equal to the
    // sacrificed creature's power, then you gain life equal to its toughness." Once AI:RemoveDeck is
    // gone it hits Life's Legacy's gates (NumCards reads 0 before payment; the SacCost preference
    // can't see a Destroy/Exile threat with a Draw saviour; the payment falls back to getWorstAI), but
    // it is an instant, so its window is its own: cash in only a creature of ours that is leaving
    // anyway - one a spell or ability on the stack will destroy, exile, steal for good, or burn or
    // shrink to death, or one that dies in the current combat without taking anything with it. The
    // board loses nothing it would have kept; four mana and the card buy MIN_POWER+ cards and the life.
    // Draws no random numbers, so consider(), DrawAi.willPayCosts and the payment
    // (ComputerUtil.chooseSacrificeType) land on the same creature.
    public static class MomentousFall {
        public static final int MIN_POWER = 2;

        public static boolean handles(final SpellAbility sa) {
            return sa != null && "Momentous Fall".equals(ComputerUtilAbility.getAbilitySourceName(sa));
        }

        public static AiAbilityDecision consider(final Player ai, final SpellAbility sa) {
            if (!ai.canDraw()) {
                return new AiAbilityDecision(0, AiPlayDecision.CantPlayAi);
            }
            final Card pick = chooseSacrifice(ai, sa);
            if (pick == null) {
                return new AiAbilityDecision(0, AiPlayDecision.CantPlayAi);
            }
            // don't deck ourselves (mirrors DrawAi.targetAI's library guard)
            if (pick.getNetPower() >= ai.getCardsIn(ZoneType.Library).size() - 3) {
                return new AiAbilityDecision(0, AiPlayDecision.CantPlayAi);
            }
            return new AiAbilityDecision(100, AiPlayDecision.WillPlay);
        }

        public static Card chooseSacrifice(final Player ai, final SpellAbility sa) {
            return chooseSacrifice(ai, CardLists.filter(ai.getCreaturesInPlay(),
                    CardPredicates.canBeSacrificedBy(sa, false)));
        }

        public static Card chooseSacrifice(final Player ai, final Iterable<Card> candidates) {
            final Game game = ai.getGame();
            final PhaseHandler ph = game.getPhaseHandler();
            final Combat combat = game.getCombat();
            final boolean combatWindow = combat != null
                    && (ph.is(PhaseType.COMBAT_DECLARE_BLOCKERS) || ph.is(PhaseType.COMBAT_FIRST_STRIKE_DAMAGE));
            if (game.getStack().isEmpty() && !combatWindow) {
                return null; // nothing can be leaving
            }
            final Set<Card> stackVerdictIgnored = stackThreatsToIgnore(game);

            Card best = null;
            for (final Card c : candidates) {
                if (!c.isCreature() || !ai.equals(c.getController()) || c.getNetPower() < MIN_POWER) {
                    continue;
                }
                // Saviour null keeps predictThreatenedObjects' Destroy / Exile / GainControl branches live
                // (with this Draw spell as saviour it skips them); nonCombatOnly returns the stack verdict
                // alone, under the profile's DONT_EVAL_KILLSPELLS_ON_STACK_WITH_PERMISSION guard, so a
                // removal spell or wrath that a counterspell above it will stop is not "leaving".
                boolean leaving = !stackVerdictIgnored.contains(c)
                        && ComputerUtil.predictCreatureWillDieThisTurn(ai, c, null, true);
                if (!leaving && combatWindow
                        && ComputerUtilCombat.combatantWouldBeDestroyed(ai, c, combat)
                        && !ComputerUtilCombat.willOpposingCreatureDieInCombat(ai, c, combat)
                        && !ComputerUtilCombat.isDangerousToSacInCombat(ai, c, combat)) {
                    leaving = true; // dies in this combat without trading, and no trampler behind it
                }
                if (!leaving) {
                    continue;
                }
                // highest power, then highest toughness, then lowest card id
                if (best == null || c.getNetPower() > best.getNetPower()
                        || (c.getNetPower() == best.getNetPower() && (c.getNetToughness() > best.getNetToughness()
                        || (c.getNetToughness() == best.getNetToughness() && c.getId() < best.getId())))) {
                    best = c;
                }
            }
            return best;
        }

        // Creatures whose stack verdict does not count as leaving: the targets of an until-end-of-turn
        // steal (a GainControl part with LoseControl, e.g. Threaten or Act of Treason - they come back,
        // so sacrificing them is a loss, not a save), and the targets of divided damage
        // (predictThreatenedObjects charges the full NumDmg to every target of a DividedAsYouChoose
        // DealDamage, so an Arc Lightning split three ways reads as lethal on a 3/3).
        private static Set<Card> stackThreatsToIgnore(final Game game) {
            final Set<Card> out = new HashSet<>();
            for (final SpellAbilityStackInstance si : game.getStack()) {
                for (SpellAbility part = si.getSpellAbility(); part != null; part = part.getSubAbility()) {
                    if (part.getApi() == ApiType.GainControl && part.hasParam("LoseControl")) {
                        part.getTargets().getTargetCards().forEach(out::add);
                        if (part.hasParam("Defined")) {
                            out.addAll(AbilityUtils.getDefinedCards(part.getHostCard(), part.getParam("Defined"), part));
                        }
                    } else if (part.getApi() == ApiType.DealDamage && part.hasParam("DividedAsYouChoose")) {
                        part.getTargets().getTargetCards().forEach(out::add);
                    }
                }
            }
            return out;
        }
    }

    // Momir Vig, Simic Visionary Avatar
    public static class MomirVigAvatar {
        public static AiAbilityDecision consider(final Player ai, final SpellAbility sa) {
            Card source = sa.getHostCard();

            if (source.getGame().getPhaseHandler().getPhase().isBefore(PhaseType.MAIN1)) {
                return new AiAbilityDecision(0, AiPlayDecision.AnotherTime);
            }

            // In MoJhoSto, prefer Jhoira sorcery ability from time to time
            if (source.getGame().getRules().hasAppliedVariant(GameType.MoJhoSto)
                    && CardLists.filter(ai.getLandsInPlay(), CardPredicates.UNTAPPED).size() >= 3) {
                AiController aic = ((PlayerControllerAi) ai.getController()).getAi();
                int chanceToPrefJhoira = aic.getIntProperty(AiProps.MOJHOSTO_CHANCE_TO_PREFER_JHOIRA_OVER_MOMIR);
                int numLandsForJhoira = aic.getIntProperty(AiProps.MOJHOSTO_NUM_LANDS_TO_ACTIVATE_JHOIRA);

                if (ai.getLandsInPlay().size() >= numLandsForJhoira && MyRandom.percentTrue(chanceToPrefJhoira)) {
                    return new AiAbilityDecision(0, AiPlayDecision.AnotherTime);
                }
            }

            // Set PayX here to maximum value.
            int tokenSize = ComputerUtilCost.setMaxXValue(sa, ai, false);

            // Some basic strategy for Momir
            if (tokenSize < 2) {
                return new AiAbilityDecision(0, AiPlayDecision.AnotherTime);
            }

            if (tokenSize > 11) {
                tokenSize = 11;
            }

            sa.setXManaCostPaid(tokenSize);

            return new AiAbilityDecision(100, AiPlayDecision.WillPlay);
        }
    }

    // Multiple Choice
    public static class MultipleChoice {
        public static boolean consider(final Player ai, final SpellAbility sa) {
            int maxX = ComputerUtilCost.setMaxXValue(sa, ai, false);

            if (maxX == 0) {
                return false;
            }

            boolean canScryDraw = maxX >= 1 && ai.getCardsIn(ZoneType.Library).size() >= 3; // TODO: generalize / use profile values
            boolean canBounce = maxX >= 2 && !ai.getOpponents().getCreaturesInPlay().isEmpty();
            boolean shouldBounce = canBounce && ComputerUtilCard.evaluateCreature(ComputerUtilCard.getWorstCreatureAI(ai.getOpponents().getCreaturesInPlay())) > 210; // 180 is the level of a 4/4 token creature
            boolean canMakeToken = maxX >= 3;
            boolean canDoAll = maxX >= 4 && canScryDraw && shouldBounce;

            if (canDoAll) {
                sa.setXManaCostPaid(4);
                return true;
            } else if (canMakeToken) {
                sa.setXManaCostPaid(3);
                return true;
            } else if (shouldBounce) {
                sa.setXManaCostPaid(2);
                return true;
            } else if (canScryDraw) {
                sa.setXManaCostPaid(1);
                return true;
            }

            return false;
        }
    }

    // Nanogene Conversion
    // One window: our precombat main phase with the stack empty. Every other
    // creature (both sides) becomes a nonlegendary copy of the target until end of
    // turn, so the spell converts creature COUNT into damage. Fire only when that
    // turns an attack that cannot already kill some opponent (generous upper bound)
    // into one that does (strict lower bound under AiAttackController.doAssault's
    // own blocking model, so the attack AI then swings all in), with a template that
    // hands nothing multiplicative to either side (triggers, activations,
    // replacement effects, non-trivial statics, lifelink/infect/defender) and kills
    // none of our creatures. Draw-free: nothing below reaches MyRandom or
    // ComputerUtilCost.canPayCost (whose mana test can roll the main-2 reservation
    // chance), so a declined evaluation leaves the game exactly where the stock
    // path (CloneAi.checkPhaseRestrictions -> MissingPhaseRestrictions) left it.
    public static class NanogeneConversion {
        private static final Set<String> STATIC_KEYS = Set.of("Mode", "Affected", "AddPower",
                "AddToughness", "AddKeyword", "Description", "EffectZone", "Secondary");

        public static AiAbilityDecision consider(final Player ai, final SpellAbility sa) {
            final AiAbilityDecision no = new AiAbilityDecision(0, AiPlayDecision.CantPlayAi);
            final Game game = ai.getGame();
            final PhaseHandler ph = game.getPhaseHandler();

            // Routing through CloneAi.canPlay's name gate bypasses the base
            // class's restriction check, so mirror it here.
            if (sa.getRestrictions() != null && !sa.getRestrictions().canPlay(sa.getHostCard(), sa)) {
                return new AiAbilityDecision(0, AiPlayDecision.CantPlaySa);
            }
            if (!ph.is(PhaseType.MAIN1, ai) || !game.getStack().isEmpty() || ai.cantWin()) {
                return no;
            }
            // Any attack cap on the board breaks the count model.
            final GlobalAttackRestrictions restrict =
                    GlobalAttackRestrictions.getGlobalRestrictions(ai, CombatUtil.getAllPossibleDefenders(ai));
            if (restrict.getMax() != null || !restrict.getDefenderMax().isEmpty()) {
                return no;
            }

            final CardCollection templates = CardLists.filter(CardUtil.getValidCardsToTarget(sa),
                    t -> inertTemplate(t) && !killsOneOfOurs(ai, t));
            if (templates.isEmpty()) {
                return no;
            }

            Card best = null;
            int bestMargin = -1;
            for (Player opp : ai.getOpponents()) {
                if (opp.getLife() <= 0 || opp.cantLose() || opp.cantLoseForZeroOrLessLife()
                        || !opp.canLoseLife() || visibleFog(opp, ai) || hasMultiBlocker(opp)) {
                    continue;
                }
                final int life = opp.getLife();
                if (attackUpperBoundNow(ai, opp) >= life) {
                    continue; // may already be lethal without spending the card
                }
                final int blockers = blockersAfterCopy(opp);
                for (Card t : templates) {
                    final int margin = attackLowerBoundAfterCopy(ai, opp, t, blockers) - life;
                    if (margin >= 0 && margin > bestMargin) {
                        best = t;
                        bestMargin = margin;
                    }
                }
            }
            if (best == null) {
                return no;
            }
            sa.resetTargets();
            sa.getTargets().add(best);
            return new AiAbilityDecision(100, AiPlayDecision.WillPlay);
        }

        // Nothing on the template may scale with the number of copies, arm the
        // opponent's copies, or dodge the life-damage math.
        private static boolean inertTemplate(final Card t) {
            if (t.isFaceDown() || !t.getCurrentState().getType().isCreature()
                    || t.getPTIterable().iterator().hasNext()          // no set-P/T layer on the template
                    || t.getBasePower() < 1 || t.getBaseToughness() < 1
                    || t.hasSVar("EndOfTurnLeavePlay")
                    || t.hasKeyword(Keyword.DEFENDER) || t.hasKeyword(Keyword.LIFELINK)
                    || t.hasKeyword(Keyword.INFECT) || t.hasKeyword(Keyword.TOXIC)
                    || t.hasKeyword(Keyword.POISONOUS)
                    || !ComputerUtilCombat.canAttackNextTurn(t)) {      // "can't attack" riders
                return false;
            }
            // Opposing copies inherit damage prevention/redirection (Guardian Seraph,
            // Palisade Giant) that the pre-copy damage prediction cannot see.
            if (!t.getReplacementEffects().isEmpty()) {
                return false;
            }
            if (!t.getManaAbilities().isEmpty()) {
                return false;                                           // mana dorks for the opponent
            }
            for (SpellAbility ab : t.getSpellAbilities()) {
                // Battlefield activations only (restriction zone defaults to Battlefield):
                // cycling/ninjutsu/channel live in the hand and never reach a copy;
                // Suspend is an AbilityStatic (isActivatedAbility() == false).
                if (ab.isActivatedAbility() && ab.getRestrictions() != null
                        && ab.getRestrictions().getZone() == ZoneType.Battlefield) {
                    return false;                                       // instant-speed tools for the opponent
                }
            }
            for (Trigger tr : t.getTriggers()) {                        // includes keyword triggers
                final Set<ZoneType> zones = tr.getActiveZone();
                if (zones != null && !zones.isEmpty() && !zones.contains(ZoneType.Battlefield)) {
                    continue;                                           // e.g. Suspend's exile-only triggers
                }
                if (TriggerType.ChangesZone.equals(tr.getMode())
                        && "Battlefield".equals(tr.getParam("Destination"))
                        && "Card.Self".equals(tr.getParam("ValidCard"))) {
                    continue;                                           // own ETB: copies never enter
                }
                return false;
            }
            for (StaticAbility st : t.getStaticAbilities()) {
                if (!harmlessStatic(st)) {
                    return false;
                }
            }
            return true;
        }

        // Allowlist: a Continuous buff/keyword grant to itself or to its controller's
        // team, numerically non-negative, granting nothing that changes who can attack
        // or what damage does. Everything else (AttackRestrict, CantBlockBy,
        // CanBlockAny/CanBlockAmount, lords with SVar X, ...) is out.
        private static boolean harmlessStatic(final StaticAbility st) {
            final Set<ZoneType> zones = st.getActiveZone();
            if (zones != null && !zones.isEmpty() && !zones.contains(ZoneType.Battlefield)) {
                return true;
            }
            if (!st.checkMode(StaticAbilityMode.Continuous)) {
                return false;
            }
            final String affected = st.getParamOrDefault("Affected", "");
            final boolean self = "Card.Self".equals(affected) || "Creature.Self".equals(affected);
            final boolean team = affected.contains("YouCtrl") && !affected.contains("Opp");
            if (!self && !team) {
                return false;
            }
            if (!STATIC_KEYS.containsAll(st.getMapParams().keySet())) {
                return false;
            }
            for (String key : List.of("AddPower", "AddToughness")) {
                if (st.hasParam(key) && !st.getParam(key).matches("\\+?\\d+")) {
                    return false;
                }
            }
            final String kw = st.getParamOrDefault("AddKeyword", "");
            return !(kw.contains("Defender") || kw.contains("can't") || kw.contains("Lifelink")
                    || kw.contains("Infect") || kw.contains("Toxic") || kw.contains("Poisonous"));
        }

        // ComputerUtil.hasAFogEffect's card set (battlefield, external activatable
        // zones, revealed hand cards) without its canPayCost test, which can draw:
        // any Fog there declines, payable or not.
        private static boolean visibleFog(final Player opp, final Player ai) {
            final CardCollection all = new CardCollection(opp.getCardsIn(ZoneType.Battlefield));
            all.addAll(opp.getCardsActivatableInExternalZones(true));
            final Set<Card> revealed = AiCardMemory.getMemorySet(ai, AiCardMemory.MemorySet.REVEALED_CARDS);
            if (revealed != null) {
                for (Card c : revealed) {
                    if (c.isInZone(ZoneType.Hand) && c.getOwner() == opp) {
                        all.add(c);
                    }
                }
            }
            for (Card c : all) {
                for (SpellAbility ab : c.getSpellAbilities()) {
                    if (ab.getApi() == ApiType.Fog) {
                        return true;
                    }
                }
            }
            return false;
        }

        // doAssault lets a blocker that can block any number of (or additional)
        // creatures stop several attackers; the count model cannot, so decline.
        private static boolean hasMultiBlocker(final Player opp) {
            for (Card b : opp.getCreaturesInPlay()) {
                if (b.canBlockAny() || b.canBlockAdditional() > 0) {
                    return true;
                }
            }
            return false;
        }

        // Every untapped opposing creature (a copy too: evasion shared, no death
        // credit), plus every untapped noncreature permanent that
        // AiAttackController.getOpponentCreatures may add as a blocker (a self-Animate
        // incl. Crew, or a SetState), counted without its cost test and without
        // AnimateAi.becomeAnimated (which takes a game timestamp), plus Peacewalker
        // Colossus's extra vehicles.
        private static int blockersAfterCopy(final Player opp) {
            int blockers = CardLists.count(opp.getCreaturesInPlay(), b -> b.isUntapped() && !b.isPhasedOut());
            for (Card c : opp.getCardsIn(ZoneType.Battlefield)) {
                if (c.isTapped() || c.isCreature() || c.isPlaneswalker()) {
                    continue;
                }
                for (SpellAbility ab : c.getSpellAbilities()) {
                    if (ab.getApi() == ApiType.SetState
                            || (ab.getApi() == ApiType.Animate && !ab.usesTargeting()
                                && "Self".equals(ab.getParamOrDefault("Defined", "Self")))) {
                        blockers++;
                        break;
                    }
                }
            }
            if (opp.isCardInPlay("Peacewalker Colossus")
                    && CardLists.count(opp.getLandsInPlay(), CardPredicates.UNTAPPED) > 0) {
                blockers += CardLists.count(CardLists.getNotType(opp.getCardsIn(ZoneType.Battlefield), "Creature"),
                        CardPredicates.isType("Vehicle").and(CardPredicates.UNTAPPED));
            }
            return blockers;
        }

        // Until-end-of-turn pumps (static id 0) survive a copy in full; a static
        // boost may come from a creature that is about to lose its abilities, so only
        // its negative part is kept.
        private static int persistingBoost(final Card c, final boolean power) {
            int sum = 0;
            for (Table.Cell<Long, Long, Pair<Integer, Integer>> cell : c.getPTBoostTable().cellSet()) {
                final int v = power ? cell.getValue().getLeft() : cell.getValue().getRight();
                sum += cell.getColumnKey() == 0L ? v : Math.min(0, v);
            }
            return sum;
        }

        // No creature of ours may drop to 0 toughness / lethal marked damage as a copy.
        private static boolean killsOneOfOurs(final Player ai, final Card t) {
            for (Card c : ai.getCreaturesInPlay()) {
                if (c.equals(t)) {
                    continue;
                }
                final int tough = (c.getPTIterable().iterator().hasNext()
                        ? Math.min(t.getBaseToughness(), c.getCurrentToughness()) : t.getBaseToughness())
                        + persistingBoost(c, false) + c.getToughnessBonusFromCounters();
                if (tough - c.getDamage() < 1) {
                    return true;
                }
            }
            return false;
        }

        // Generous: every creature that can attack now; tramplers, menace and creatures
        // the defender cannot block count in full; each opposing creature that can
        // block at least one of the rest stops only the SMALLEST remaining one.
        // withoutAbilities: the pump-ability scan pays through canPayCost, which can draw.
        private static int attackUpperBoundNow(final Player ai, final Player opp) {
            final CardCollection attackers = CardLists.filter(ai.getCreaturesInPlay(), c -> CombatUtil.canAttack(c, opp));
            final CardCollection oppCreatures = opp.getCreaturesInPlay();
            int sum = 0;
            final List<Integer> rest = new ArrayList<>();
            for (Card c : attackers) {
                final int d = ComputerUtilCombat.damageIfUnblocked(c, opp, null, true);
                if (c.hasKeyword(Keyword.TRAMPLE) || c.hasKeyword(Keyword.MENACE)
                        || !CombatUtil.canBeBlocked(c, oppCreatures, null)) {
                    sum += d;
                } else {
                    rest.add(d);
                }
            }
            Collections.sort(rest);
            final int blockers = CardLists.count(oppCreatures,
                    b -> CombatUtil.canBlock(b) && CombatUtil.canBlockAtLeastOne(b, attackers));
            for (int i = Math.min(blockers, rest.size()); i < rest.size(); i++) {
                sum += rest.get(i);
            }
            return sum;
        }

        // Strict: the template itself if it can attack now, plus every other creature
        // of ours that is untapped, under our control since the turn began (haste not
        // credited), free of attack taxes and of "can't attack" riders, hitting for the
        // template's base power + counters + persisting boosts (the template itself:
        // no more than that either, since a lord's anthem on it may be about to vanish).
        // Each counted blocker stops the BIGGEST remaining attacker. Trample, double
        // strike, menace and positive static anthems are not credited.
        private static int attackLowerBoundAfterCopy(final Player ai, final Player opp, final Card t, final int blockers) {
            final Game game = ai.getGame();
            final List<Integer> hits = new ArrayList<>();
            for (Card c : ai.getCreaturesInPlay()) {
                if (CombatUtil.getAttackCost(game, c, opp) != null) {
                    continue;
                }
                int pow;
                if (c.equals(t)) {
                    if (!CombatUtil.canAttack(c, opp)) {
                        continue;
                    }
                    pow = Math.min(t.getNetCombatDamage(),
                            t.getCurrentPower() + persistingBoost(t, true) + t.getPowerBonusFromCounters());
                } else {
                    if (c.isTapped() || c.isPhasedOut() || c.isFirstTurnControlled()
                            || !CombatUtil.canAttackNextTurn(c, opp)) {
                        continue;
                    }
                    pow = (c.getPTIterable().iterator().hasNext()
                            ? Math.min(t.getBasePower(), c.getCurrentPower()) : t.getBasePower())
                            + persistingBoost(c, true) + c.getPowerBonusFromCounters();
                }
                if (pow > 0) {
                    hits.add(ComputerUtilCombat.predictDamageTo(opp, pow, t, true));
                }
            }
            hits.sort(Collections.reverseOrder());
            int sum = 0;
            for (int i = blockers; i < hits.size(); i++) {
                sum += hits.get(i);
            }
            return sum;
        }
    }

    // Necropotence
    public static class Necropotence {
        public static AiAbilityDecision consider(final Player ai, final SpellAbility sa) {
            Game game = ai.getGame();
            int computerHandSize = ai.getZone(ZoneType.Hand).size();
            int maxHandSize = ai.getMaxHandSize();

            if (ai.getCardsIn(ZoneType.Library).isEmpty()) {
                return new AiAbilityDecision(0, AiPlayDecision.CantPlayAi);
            }

            if (ai.getCardsIn(ZoneType.Battlefield).anyMatch(CardPredicates.nameEquals("Yawgmoth's Bargain"))) {
                // Prefer Yawgmoth's Bargain because AI is generally better with it

                // TODO: in presence of bad effects which deal damage when a card is drawn, probably better to prefer Necropotence instead?
                // (not sure how to detect the presence of such effects yet)
                return new AiAbilityDecision(0, AiPlayDecision.CantPlayAi);
            }

            PhaseHandler ph = game.getPhaseHandler();

            int exiledWithNecro = 1; // start with 1 because if this succeeds, one extra card will be exiled with Necro
            for (Card c : ai.getCardsIn(ZoneType.Exile)) {
                if (c.getExiledWith() != null && "Necropotence".equals(c.getExiledWith().getName()) && c.isFaceDown()) {
                    exiledWithNecro++;
                }
            }

            // TODO: Any other bad effects like that?
            boolean blackViseOTB = game.getCardsIn(ZoneType.Battlefield).anyMatch(CardPredicates.nameEquals("Black Vise"));

            if (ph.getNextTurn().equals(ai) && ph.is(PhaseType.MAIN2)
                    && ai.getSpellsCastLastTurn() == 0
                    && ai.getSpellsCastThisTurn() == 0
                    && ai.getLandsPlayedLastTurn() == 0) {
                // We're in a situation when we have nothing castable in hand, something needs to be done
                if (!blackViseOTB) {
                    // exile-loot +1 card when at max hand size, hoping to get a workable spell or land
                    if (computerHandSize + exiledWithNecro - 1 == maxHandSize) {
                        return new AiAbilityDecision(100, AiPlayDecision.WillPlay);
                    } else {
                        return new AiAbilityDecision(0, AiPlayDecision.CantPlayAi);
                    }
                } else {
                    // Loot to 7 in presence of Black Vise, hoping to find what to do
                    // NOTE: can still currently get theoretically locked with 7 uncastable spells. Loot to 8 instead?
                    if (computerHandSize + exiledWithNecro <= maxHandSize) {
                        // Loot to 7, hoping to find something playable
                        return new AiAbilityDecision(100, AiPlayDecision.WillPlay);
                    } else {
                        // Loot to 8, hoping to find something playable
                        return new AiAbilityDecision(0, AiPlayDecision.CantPlayAi);
                    }
                }
            } else if (blackViseOTB && computerHandSize + exiledWithNecro - 1 >= 4) {
                // try not to overdraw in presence of Black Vise
                return new AiAbilityDecision(0, AiPlayDecision.CantPlayAi);
            } else if (computerHandSize + exiledWithNecro - 1 >= maxHandSize) {
                // Only draw until we reach max hand size
                return new AiAbilityDecision(0, AiPlayDecision.CantPlayAi);
            } else if (!ph.isPlayerTurn(ai) || !ph.is(PhaseType.MAIN2)) {
                // Only activate in AI's own turn (sans the exception above)
                return new AiAbilityDecision(0, AiPlayDecision.WaitForMain2);
            }
            return new AiAbilityDecision(100, AiPlayDecision.WillPlay);
        }
    }

    // New Blood
    // The spell is judged by the stock ControlGainAi.canPlay, which targets the
    // best opposing creature that has combat damage > 0 and can attack, can be
    // controlled by us, has no EndOfTurnLeavePlay and is not RemAIDeck. Its
    // "replace one creature type with Vampire" rider (DB$ ChangeText) had no AI
    // of its own and vetoed every cast. Accept the rider only when the parent
    // really targets an opponent's creature worth a card: at least a vanilla
    // 2/2 (evaluateCreature 161; a vanilla 1/1 card and a 2/2 token score 131,
    // a 1/1 token 106), so four mana and a tapped Vampire never buy a chump
    // token. getBestCreatureAI already picked the highest-value candidate, so
    // the floor on the pick is the floor on the whole candidate list.
    public static class NewBlood {
        private static final int MIN_STOLEN_VALUE = 150;

        public static AiAbilityDecision considerTextChange(final Player ai, final SpellAbility sa) {
            final SpellAbility root = sa.getRootAbility();
            if (root == null || root.getApi() != ApiType.GainControl) {
                return new AiAbilityDecision(0, AiPlayDecision.CantPlayAi);
            }
            final Card stolen = root.getTargetCard();
            if (stolen == null || !stolen.isCreature() || !stolen.isInPlay()
                    || stolen.getController() == null || !ai.isOpponentOf(stolen.getController())) {
                return new AiAbilityDecision(0, AiPlayDecision.TargetingFailed);
            }
            if (ComputerUtilCard.evaluateCreature(stolen) < MIN_STOLEN_VALUE) {
                return new AiAbilityDecision(0, AiPlayDecision.CantPlayAi);
            }
            return new AiAbilityDecision(100, AiPlayDecision.WillPlay);
        }
    }

    // Null Brooch
    public static class NullBrooch {
        public static boolean consider(final Player ai, final SpellAbility sa) {
            // TODO: improve the detection of Ensnaring Bridge type effects ("GTX", "X" need generalization)
            boolean hasEnsnaringBridgeEffect = false;
            for (Card otb : ai.getCardsIn(ZoneType.Battlefield)) {
                for (StaticAbility stab : otb.getStaticAbilities()) {
                    if ("CARDNAME can't attack.".equals(stab.getParam("AddHiddenKeyword"))
                            && "Creature.powerGTX".equals(stab.getParam("Affected"))
                            && "Count$InYourHand".equals(otb.getSVar("X"))) {
                        hasEnsnaringBridgeEffect = true;
                        break;
                    }
                }

            }
            // Maybe use it for some important high-impact spells even if there are more cards in hand?
            return ai.getCardsIn(ZoneType.Hand).size() <= 1 || hasEnsnaringBridgeEffect;
        }
    }

    // Nykthos, Shrine to Nyx
    public static class NykthosShrineToNyx {
        public static boolean consider(final Player ai, final SpellAbility sa) {
            Game game = ai.getGame();
            PhaseHandler ph = game.getPhaseHandler();
            if (!ph.isPlayerTurn(ai) || ph.getPhase().isBefore(PhaseType.MAIN2)) {
                // TODO: currently limited to Main 2, somehow improve to let the AI use this SA at other time?
                return false;
            }
            String prominentColor = ComputerUtilCard.getMostProminentColor(ai.getCardsIn(ZoneType.Battlefield));
            int devotion = AbilityUtils.calculateAmount(sa.getHostCard(), "Count$Devotion." + prominentColor, sa);
            int activationCost = sa.getPayCosts().getTotalMana().getCMC() + (sa.getPayCosts().hasTapCost() ? 1 : 0);

            // do not use this SA if devotion to most prominent color is less than its own activation cost + 1 (to actually get advantage)
            if (devotion < activationCost + 1) {
                return false;
            }

            final CardCollectionView cards = ai.getCardsIn(ZoneType.Hand, ZoneType.Battlefield, ZoneType.Command);
            List<SpellAbility> all = ComputerUtilAbility.getSpellAbilities(cards, ai);

            int numManaSrcs = CardLists.filter(ComputerUtilMana.getAvailableManaSources(ai, true), CardPredicates.UNTAPPED).size();

            for (final SpellAbility testSa : ComputerUtilAbility.getOriginalAndAltCostAbilities(all, ai)) {
                ManaCost cost = testSa.getPayCosts().getTotalMana();
                boolean canPayWithAvailableColors = cost.canBePaidWithAvailable(ColorSet.fromNames(
                        ComputerUtilCost.getAvailableManaColors(ai, sa.getHostCard())).getColor());

                byte colorProfile = cost.getColorProfile();

                if (cost.getCMC() == 0 && cost.countX() == 0) {
                    // no mana cost, no need to activate this SA then (additional mana not needed)
                    continue;
                } else if (colorProfile != 0 && !canPayWithAvailableColors
                        && (cost.getColorProfile() & MagicColor.fromName(prominentColor)) == 0) {
                    // don't have at least one of each shard required to pay, so most likely won't be able to pay
                    continue;
                } else if ((testSa.getPayCosts().getTotalMana().getCMC() > devotion + numManaSrcs - activationCost)) {
                    // the cost may be too high even if we activate this SA
                    continue;
                }

                if (ComputerUtilAbility.getAbilitySourceName(testSa).equals(ComputerUtilAbility.getAbilitySourceName(sa))
                        || testSa.hasParam("AINoRecursiveCheck")) {
                    // prevent infinitely recursing abilities that are susceptible to reentry
                    continue;
                }

                testSa.setActivatingPlayer(ai);
                if (((PlayerControllerAi) ai.getController()).getAi().canPlaySa(testSa) == AiPlayDecision.WillPlay) {
                    // the AI is willing to play the spell
                    return true;
                }
            }

            return false; // haven't found anything to play with the excess generated mana
        }
    }

    // Order of Succession
    // Resolution is predictable under AI choosers: ControlGainVariantAi.chooseSingleCard
    // is getBestCreatureAI over the next player's creatures, so we gain the best creature
    // of the next player in the chosen direction, and the player whose "next" we are takes
    // our best creature. The direction mirrors AiController.chooseDirection's GainControl
    // branch (in 1v1 both directions reach the one opponent). Cast only when the steal is
    // worth a card, a swap is clearly up, and nothing that changes sides with our creature
    // (an anthem, our commander) or stays behind (our own Aura on theirs) spoils the trade.
    public static class OrderOfSuccession {
        // ~a 4/4 for 4 (220) or a 3/3 flyer; never a 3/3 for 3 (190), a 2/2 (160) or a 1/1 token (105)
        public static final int MIN_GAIN_VALUE = 200;
        // stock ControlExchangeAi asks +40 for a best-for-WORST swap; this one is best-for-best
        public static final int MIN_SWAP_MARGIN = 60;
        private static final Pattern SELF_ONLY_AFFECTED = Pattern.compile("[A-Za-z]+\\.Self(\\+.*)?");

        public static AiAbilityDecision consider(final Player ai, final SpellAbility sa) {
            final Game game = ai.getGame();

            // ChooseDirectionAi overrides canPlay wholesale, so mirror the base
            // class's restriction check.
            if (sa.getRestrictions() != null && !sa.getRestrictions().canPlay(sa.getHostCard(), sa)) {
                return new AiAbilityDecision(0, AiPlayDecision.CantPlaySa);
            }

            final CardCollection creats = CardLists.filter(game.getCardsIn(ZoneType.Battlefield), CardPredicates.CREATURES);
            final Player left = game.getNextPlayerAfter(ai, forge.game.Direction.Left);
            final Player right = game.getNextPlayerAfter(ai, forge.game.Direction.Right);
            final CardCollection neighbours = CardLists.filterControlledBy(creats, left);
            if (right != null && !right.equals(left)) {
                neighbours.addAll(CardLists.filterControlledBy(creats, right));
            }
            if (neighbours.isEmpty()) {
                return new AiAbilityDecision(0, AiPlayDecision.TargetingFailed);
            }
            final Card gain = ComputerUtilCard.getBestCreatureAI(neighbours);
            if (gain == null || !gain.getController().isOpponentOf(ai) || !gain.canBeControlledBy(ai)) {
                return new AiAbilityDecision(0, AiPlayDecision.TargetingFailed);
            }
            // A creature neutralised by our own Aura (Darksteel Mutation keeps its mana
            // value, so it can outscore a real body) is no steal.
            if (gain.getNetPower() <= 0 || gain.getEnchantedBy().anyMatch(CardPredicates.isController(ai))) {
                return new AiAbilityDecision(0, AiPlayDecision.CantPlayAi);
            }
            // A creature whose power or toughness is characteristic-defined by a count relative
            // to its controller (Bronze Guardian / Master of Etherium: artifacts you control,
            // Psychosis Crawler: cards in your hand, Rubblehulk: lands you control) is scored at
            // the opponent's count and shrinks when it changes sides; the evaluator cannot see that.
            if (hasControllerRelativePT(gain)) {
                return new AiAbilityDecision(0, AiPlayDecision.CantPlayAi);
            }
            final int gainValue = ComputerUtilCard.evaluateCreature(gain);
            if (gainValue < MIN_GAIN_VALUE) {
                return new AiAbilityDecision(0, AiPlayDecision.CantPlayAi);
            }

            final CardCollection ours = ai.getCreaturesInPlay();
            if (!ours.isEmpty()) {
                // The hand-over happens on resolution: pre-combat it takes an attacker
                // from us and gives them a blocker, and what we gain is summoning sick.
                if (!game.getPhaseHandler().is(PhaseType.MAIN2, ai)) {
                    return new AiAbilityDecision(0, AiPlayDecision.WaitForMain2);
                }
                final Card loss = ComputerUtilCard.getBestCreatureAI(ours); // what their chooser takes
                if (loss != null) {
                    if (loss.isCommander() && ai.equals(loss.getOwner())) {
                        return new AiAbilityDecision(0, AiPlayDecision.CantPlayAi);
                    }
                    if (affectsBeyondItself(loss)) {
                        return new AiAbilityDecision(0, AiPlayDecision.CantPlayAi);
                    }
                    if (gainValue < ComputerUtilCard.evaluateCreature(loss) + MIN_SWAP_MARGIN) {
                        return new AiAbilityDecision(0, AiPlayDecision.CantPlayAi);
                    }
                }
            }
            return new AiAbilityDecision(100, AiPlayDecision.WillPlay);
        }

        // An intrinsic continuous static that reaches past its own card (an anthem or
        // lord such as Kongming's "Creature.Other+YouCtrl") changes sides with it, and the
        // creature evaluator does not price it.
        private static boolean affectsBeyondItself(final Card c) {
            for (final StaticAbility stAb : c.getStaticAbilities()) {
                if (!stAb.isIntrinsic() || !stAb.checkMode(forge.game.staticability.StaticAbilityMode.Continuous)) {
                    continue;
                }
                final String affected = stAb.getParam("Affected");
                if (affected == null) {
                    continue;
                }
                for (final String part : affected.split(",")) {
                    if (!SELF_ONLY_AFFECTED.matcher(part.trim()).matches()) {
                        return true;
                    }
                }
            }
            return false;
        }

        // A power or toughness characteristic-defined by a count relative to the controller
        // ("You"/"Opp" in the SetPower/SetToughness expression or its SVar) is read at the
        // current controller's count, so the creature evaluator overprices it on the side
        // that is about to lose it.
        private static boolean hasControllerRelativePT(final Card c) {
            for (final StaticAbility stAb : c.getStaticAbilities()) {
                if (!stAb.isIntrinsic() || !stAb.isCharacteristicDefining()
                        || !stAb.checkMode(forge.game.staticability.StaticAbilityMode.Continuous)) {
                    continue;
                }
                for (final String param : new String[] {"SetPower", "SetToughness"}) {
                    if (!stAb.hasParam(param)) {
                        continue;
                    }
                    final String name = stAb.getParam(param);
                    final String svar = c.getSVar(name);
                    final String expr = svar == null || svar.isEmpty() ? name : svar;
                    if (expr.contains("You") || expr.contains("Opp")) {
                        return true;
                    }
                }
            }
            return false;
        }
    }

    // Path of the Pyromancer
    // "Discard all the cards in your hand. Add {R} for each card discarded this
    // way, then draw that many cards plus one." Card-neutral by construction
    // (Path + N cards in, N+1 random cards out), so the floor only decides which
    // hand is worth trading: an empty one, or one where every card is worse than
    // a random draw. A castable keeper is never pitched, whatever order the main-2
    // spell list is evaluated in (Path's CMC 5 sorts ahead of most keepers).
    // The Will of the Planeswalkers rider does nothing outside Planechase.
    public static class PathOfThePyromancer {
        public static AiAbilityDecision consider(final Player ai, final SpellAbility sa, final boolean fromEffect) {
            // Our own main 2 (DiscardAi's stock timing): the {R} floats through the
            // phase and pays for what we draw. A free cast from an effect has no
            // choice of phase.
            if (!fromEffect && !ai.getGame().getPhaseHandler().is(PhaseType.MAIN2, ai)) {
                return new AiAbilityDecision(0, AiPlayDecision.WaitForMain2);
            }
            // Discarding the hand and drawing nothing is pure loss.
            if (!ai.canDraw()) {
                return new AiAbilityDecision(0, AiPlayDecision.CantPlayAi);
            }
            final CardCollection hand = new CardCollection(ai.getCardsIn(ZoneType.Hand));
            hand.remove(sa.getHostCard());
            // Never deck ourselves: DrawAi's own margin, on the real draw count
            // (nothing is remembered yet, so the Draw sub's own check sees one card).
            if (hand.size() + 1 >= ai.getCardsIn(ZoneType.Library).size() - 3) {
                return new AiAbilityDecision(0, AiPlayDecision.CantPlayAi);
            }
            for (final Card c : hand) {
                // Dead = worse than a random draw: excess lands, or spells not
                // castable for some time. isWorseThanDraw's late-game "small stuff"
                // clause (CMC <= 1) is ignored for spells, so cheap held interaction
                // stays a keeper. One keeper vetoes the whole trade.
                final boolean dead = c.hasSVar("DiscardMe")
                        || (ComputerUtil.isWorseThanDraw(ai, c) && (c.isLand() || c.getCMC() > 1));
                if (!dead) {
                    return new AiAbilityDecision(0, AiPlayDecision.CantPlayAi);
                }
            }
            return new AiAbilityDecision(100, AiPlayDecision.WillPlay);
        }
    }

    // Path of the Schemer
    // Each player mills two, then we put a creature card from ANY graveyard
    // onto the battlefield under our control (the Will of the Planeswalkers
    // vote does nothing outside Planechase). The hidden reanimate sub approves
    // unconditionally in ChangeZoneAi.hiddenOriginPlayDrawbackAI, so without a
    // floor five mana buys "each player mills two". Floor: the creature the
    // resolution will take from the graveyards as they stand
    // (ChangeZoneAi.chooseCardToHiddenOriginChangeZone: the best-evaluated
    // creature, skipping legends we already control) must be a real body,
    // must not be a card the AI is told never to play (AI:RemoveDeck:All -
    // Cataclysmic Gearhulk would sacrifice our own go-wide board, Worldgorger
    // Dragon exile it), and must not make us lose the game on entering (Phage).
    public static class PathOfTheSchemer {
        public static final int MIN_PICK_VALUE = 200; // the "real creature" line of ChangeZoneAi / DestroyAi

        public static AiAbilityDecision consider(final Player ai, final SpellAbility sa) {
            // RNG parity: the stock hiddenOriginPlayDrawbackAI this replaces
            // calls choosePreferredDefenderPlayer first, which draws from
            // MyRandom (Aggregates.random) with two or more opponents.
            AiAttackController.choosePreferredDefenderPlayer(ai);

            CardCollection pool = CardLists.getValidCards(ai.getGame().getCardsIn(ZoneType.Graveyard),
                    sa.getParamOrDefault("ChangeType", "Creature"), ai, sa.getHostCard(), sa);
            pool = CardLists.filter(pool, c -> !c.getType().isLegendary() || !ai.isCardInPlay(c.getName()));
            if (pool.isEmpty()) {
                return new AiAbilityDecision(0, AiPlayDecision.CantPlayAi);
            }
            final Card pick = ComputerUtilCard.getBestCreatureAI(pool);
            if (pick == null || ComputerUtilCard.isCardRemAIDeck(pick)
                    || ComputerUtilCard.evaluateCreature(pick) < MIN_PICK_VALUE || losesOnEntering(pick)) {
                return new AiAbilityDecision(0, AiPlayDecision.CantPlayAi);
            }
            return new AiAbilityDecision(100, AiPlayDecision.WillPlay);
        }

        // Phage the Untouchable shape: a self-ETB trigger whose chain makes its controller lose.
        private static boolean losesOnEntering(final Card c) {
            for (final Trigger t : c.getTriggers()) {
                if (t.getMode() != TriggerType.ChangesZone || !"Battlefield".equals(t.getParam("Destination"))) {
                    continue;
                }
                for (SpellAbility part = t.ensureAbility(); part != null; part = part.getSubAbility()) {
                    if (part.getApi() == ApiType.LosesGame && "You".equals(part.getParamOrDefault("Defined", "You"))) {
                        return true;
                    }
                }
            }
            return false;
        }
    }

    // Phyrexian Dreadnought
    public static class PhyrexianDreadnought {
        public static CardCollection reviseCreatureSacList(final Player ai, final SpellAbility sa, final CardCollection choices) {
            choices.sort(ComputerUtilCard.getCachedCreatureComparator());
            int power = 0;
            List<Card> toKeep = Lists.newArrayList();
            for (Card c : choices) {
                if (c.getName().equals(ComputerUtilAbility.getAbilitySourceName(sa))) {
                    continue; // not worth it sac'ing another Dreadnaught
                }
                if (c.getNetPower() < 1) {
                    continue; // contributes nothing to Dreadnought requirements
                }
                if (power >= 12) {
                    break;
                }
                toKeep.add(c);
                power += c.getNetPower();
            }

            return new CardCollection(toKeep);
        }
    }

    // Phyrexian Revoker
    // As it enters, the AI names a card; activated abilities of sources with that
    // name can't be activated. The lock is symmetric and, unlike Pithing Needle's,
    // stops mana abilities too. So the name is only ever an opponent's real nonland
    // card with an activated ability of its own: never a name the AI holds with an
    // activated ability (any zone), never a token or face-down name (not a legal
    // card name, and it would lock every token of that name), never a name a
    // permanent already stops. consider() and chooseCard() share one chooser, so
    // the cast decision and the name picked at resolution agree. No random draw.
    public static class PhyrexianRevoker {
        public static AiAbilityDecision consider(final Player ai, final SpellAbility sa) {
            return chooseCard(ai, sa) != null
                    ? new AiAbilityDecision(100, AiPlayDecision.WillPlay)
                    : new AiAbilityDecision(0, AiPlayDecision.CantPlayAi);
        }

        // null = nothing worth naming
        public static String chooseCard(final Player ai, final SpellAbility sa) {
            final Set<String> excluded = new HashSet<>();
            for (final Card c : ai.getAllCards()) {
                if (hasActivatedAbility(c)) {
                    excluded.add(c.getName());
                }
            }
            for (final Card c : ai.getGame().getCardsIn(ZoneType.Battlefield)) {
                if (!c.hasNamedCard()) {
                    continue;
                }
                for (final StaticAbility st : c.getStaticAbilities()) {
                    if (st.checkMode(StaticAbilityMode.CantBeActivated)) {
                        excluded.addAll(c.getNamedCards());
                        break;
                    }
                }
            }

            final Map<String, Integer> nameToScore = new LinkedHashMap<>();
            for (final Player opp : ai.getOpponents()) {
                final List<String> keyCards = opp.getRegisteredPlayer() == null
                        ? List.of() : opp.getRegisteredPlayer().getDeck().getKeyCards();
                for (final Card c : opp.getAllCards()) {
                    if (c.isLand() || c.isToken() || c.isFaceDown() || !hasActivatedAbility(c)) {
                        continue;
                    }
                    final String name = c.getName();
                    if (excluded.contains(name) || StaticData.instance().getCommonCards().getFaceByName(name) == null) {
                        continue;
                    }
                    int score = PithingNeedle.scoreCardAbilities(c, false);
                    if (c.isInZone(ZoneType.Battlefield) || c.isInZone(ZoneType.Command)) {
                        score += 10;
                    }
                    if (keyCards.contains(name)) {
                        score += 100;
                    }
                    nameToScore.merge(name, score, Integer::sum);
                }
            }

            // Every candidate scores > 0 (each activated ability adds at least 15);
            // strict > keeps the first-seen name on a tie.
            String best = null;
            int bestScore = 0;
            for (final Map.Entry<String, Integer> e : nameToScore.entrySet()) {
                if (e.getValue() > bestScore) {
                    best = e.getKey();
                    bestScore = e.getValue();
                }
            }
            return best;
        }

        private static boolean hasActivatedAbility(final Card c) {
            return c.getSpellAbilities().anyMatch(SpellAbility::isActivatedAbility);
        }
    }

    // Power Struggle
    public static class PowerStruggle {
        public static boolean considerFirstTarget(final Player ai, final SpellAbility sa) {
            Card firstTgt = (Card) Aggregates.random(sa.getTargetRestrictions().getAllCandidates(sa));
            if (firstTgt != null) {
                sa.getTargets().add(firstTgt);
                return true;
            } else {
                return false;
            }
        }

        public static AiAbilityDecision considerSecondTarget(final Player ai, final SpellAbility sa) {
            Card firstTgt = sa.getParent().getTargetCard();
            CardCollection candidates = ai.getOpponents().getCardsIn(ZoneType.Battlefield).filter(
                    CardPredicates.sharesCardTypeWith(firstTgt).and(CardPredicates.isTargetableBy(sa)));
            Card secondTgt = Aggregates.random(candidates);
            if (secondTgt != null) {
                sa.resetTargets();
                sa.getTargets().add(secondTgt);
                return new AiAbilityDecision(100, AiPlayDecision.WillPlay);
            } else {
                return new AiAbilityDecision(0, AiPlayDecision.CantPlayAi);
            }
        }
    }

    // Predators' Hour: until end of turn our creatures gain menace and
    // "whenever this creature deals combat damage to a player, exile the top
    // card of that player's library face down; you may look at and play it".
    // All of its value is this turn's combat damage to players, so it is cast
    // only before our own attack, and only when the AI's own attack plan has
    // an untaxed attacker that connects even if the defender double-blocks.
    public static class PredatorsHour {
        public static AiAbilityDecision consider(final Player ai, final SpellAbility sa) {
            final Game game = ai.getGame();
            final PhaseHandler ph = game.getPhaseHandler();

            // Routing through AnimateAllAi.canPlay's name gate bypasses the base
            // class's restriction check, so mirror it here.
            if (sa.getRestrictions() != null && !sa.getRestrictions().canPlay(sa.getHostCard(), sa)) {
                return new AiAbilityDecision(0, AiPlayDecision.CantPlaySa);
            }
            // Our turn, before attackers are declared, nothing on the stack.
            if (!ph.isPlayerTurn(ai) || !ph.getPhase().isBefore(PhaseType.COMBAT_DECLARE_ATTACKERS)
                    || !game.getStack().isEmpty()) {
                return new AiAbilityDecision(0, AiPlayDecision.CantPlayAi);
            }
            // No creatures, no attack. Decline before building the predicted
            // combat, which can draw MyRandom; the stock refusal drew nothing.
            if (ai.getCreaturesInPlay().isEmpty()) {
                return new AiAbilityDecision(0, AiPlayDecision.CantPlayAi);
            }

            // The AI's own attack plan (cached per priority pass, and empty after
            // declare-attackers because CombatUtil.canAttack refuses then).
            final Combat predicted = ((PlayerControllerAi) ai.getController()).getAi().getPredictedCombat();
            final Map<Player, Integer> swingsAt = new HashMap<>();
            for (Card c : predicted.getAttackers()) {
                GameEntity def = predicted.getDefenderByAttacker(c);
                // The trigger needs combat damage to a PLAYER. Attackers facing an
                // attack tax (Ghostly Prison, Propaganda, ...) are not counted: the
                // prediction ignores attack costs, and our own 1B may be exactly
                // the mana that would have paid the tax.
                if (def instanceof Player p && c.getNetCombatDamage() > 0
                        && CombatUtil.getAttackCost(game, c, p) == null) {
                    swingsAt.merge(p, 1, Integer::sum);
                }
            }
            for (Map.Entry<Player, Integer> e : swingsAt.entrySet()) {
                int blockers = CardLists.count(e.getKey().getCreaturesInPlay(), c -> CombatUtil.canBlock(c));
                // Menace: each attacker they stop costs them two blockers, so at
                // least (attackers - blockers/2) connect whatever they do.
                if (e.getValue() - blockers / 2 >= 1) {
                    return new AiAbilityDecision(100, AiPlayDecision.WillPlay);
                }
            }
            return new AiAbilityDecision(0, AiPlayDecision.CantPlayAi);
        }
    }

    // Predict
    // "Choose a card name, then target player mills a card. If a card with the
    // chosen name was milled this way, you draw two cards. Otherwise, you draw
    // a card." A cantrip at worst, so it only spends mana nothing else wants:
    // the end step right before our own turn, or our main 2 when it is the last
    // card in hand. The name comes from the script's MostProminentInHumanDeck
    // logic (the opponent's library), so the mill must be able to hit one.
    public static class Predict {
        public static AiAbilityDecision consider(final Player ai, final SpellAbility sa) {
            final Game game = ai.getGame();
            final PhaseHandler ph = game.getPhaseHandler();
            // It draws one or two (and MillAi may mill ourselves): never into a
            // thin library.
            if (!ai.canDraw() || ai.getCardsIn(ZoneType.Library).size() <= 4) {
                return new AiAbilityDecision(0, AiPlayDecision.CantPlayAi);
            }
            // No response value; leave the stack alone.
            if (!game.getStack().isEmpty()) {
                return new AiAbilityDecision(0, AiPlayDecision.StackNotEmpty);
            }
            // With no opponent a legal mill target with cards left, MillAi's
            // mandatory fallback targets us: a name guessed from the opponent's
            // library then mills our own top card (often one we set up) for a
            // flat draw one.
            final SpellAbility mill = sa.findSubAbilityByType(ApiType.Mill);
            if (mill == null || !ai.getOpponents().anyMatch(o -> mill.canTarget(o) && !o.getCardsIn(ZoneType.Library).isEmpty())) {
                return new AiAbilityDecision(0, AiPlayDecision.CantPlayAi);
            }
            // The end step right before our own turn: the mana is unused this
            // turn cycle.
            if (ph.is(PhaseType.END_OF_TURN) && ai.equals(ph.getNextTurn())) {
                return new AiAbilityDecision(100, AiPlayDecision.WillPlay);
            }
            // Nothing else to spend the mana on: our main 2, last card in hand.
            if (ph.is(PhaseType.MAIN2, ai) && ai.getCardsIn(ZoneType.Hand).size() <= 1) {
                return new AiAbilityDecision(100, AiPlayDecision.WillPlay);
            }
            return new AiAbilityDecision(0, AiPlayDecision.WaitForEndOfTurn);
        }
    }

    // Price of Progress
    public static class PriceOfProgress {
        public static AiAbilityDecision consider(final Player ai, final SpellAbility sa) {
            // Don't play in early game - opponent likely still has lands to play
            if (ai.getGame().getPhaseHandler().getTurn() < 10) {
                return new AiAbilityDecision(0, AiPlayDecision.AnotherTime);
            }

            int aiLands = CardLists.filter(ai.getCardsIn(ZoneType.Battlefield), CardPredicates.NONBASIC_LANDS).size();
            // TODO Better if we actually calculate the true damage
            boolean willDieToPCasting = (ai.getLife() <= aiLands * 2);
            if (!willDieToPCasting) {
                boolean hasBridge = false;
                for (Card c : ai.getCardsIn(ZoneType.Battlefield)) {
                    // Do we have a card in play that makes us want to empty out hand?
                    if (c.hasSVar("PreferredHandSize") && ai.getCardsIn(ZoneType.Hand).size() > Integer.parseInt(c.getSVar("PreferredHandSize"))) {
                        hasBridge = true;
                        break;
                    }
                }

                // Do if we need to lose cards to activate Ensnaring Bridge or Cursed Scroll
                // even if suboptimal play, but don't waste the card too early even then!
                if (hasBridge) {
                    return new AiAbilityDecision(100, AiPlayDecision.PlayToEmptyHand);
                }
            }

            boolean willPlay = true;
            for (Player opp : ai.getOpponents()) {
                int oppLands = CardLists.filter(opp.getCardsIn(ZoneType.Battlefield), CardPredicates.NONBASIC_LANDS).size();
                // Don't if no enemy nonbasic lands
                if (oppLands == 0) {
                    willPlay = false;
                    continue;
                }

                // Always if enemy would die and we don't!
                // TODO : predict actual damage instead of assuming it'll be 2*lands
                // Don't if we lose, unless we lose anyway to unblocked creatures next turn
                if (willDieToPCasting &&
                        (!(ComputerUtil.aiLifeInDanger(ai, true, 0)) && ((ai.getOpponentsSmallestLifeTotal()) <= oppLands * 2))) {
                    willPlay = false;
                }
                // Do if we can win
                if (opp.getLife() <= oppLands * 2) {
                    return new AiAbilityDecision(1000, AiPlayDecision.WillPlay);
                }
                // Don't if we'd lose a larger percentage of our remaining life than enemy
                if ((aiLands / ((double) ai.getLife())) >
                        (oppLands / ((double) ai.getOpponentsSmallestLifeTotal()))) {
                    willPlay = false;
                }

                // Don't if loss is equal in percentage but we lose more points
                if (((aiLands / ((double) ai.getLife())) == (oppLands / ((double) ai.getOpponentsSmallestLifeTotal())))
                        && (aiLands > oppLands)) {
                    willPlay = false;
                }

            }
            if (willPlay) {
                return new AiAbilityDecision(100, AiPlayDecision.WillPlay);
            }
            return new AiAbilityDecision(0, AiPlayDecision.CantPlayAi);
        }
    }

    // Prisoner's Dilemma
    // "Each opponent secretly chooses silence or snitch..." - Forge's chooser
    // (ChooseGenericAi.chooseSingleSpellAbility, no AILogic) always answers
    // Silence, and a lone opponent's best reply is Silence too (4 < 8), so the
    // spell is modelled as 4 damage to each opponent (exact in pods of Forge AIs
    // as well: all-silence). Tiers, in order, once some opponent can take damage:
    // a cast without paying its mana cost (Etali, Primal Storm) - nothing spent;
    // the burn leaves a killable opponent below 5 life - any main phase;
    // otherwise only in our own main 2, and only when it is the flashback (no
    // card spent), the card would be discarded to hand size anyway, or 4 is at
    // least a fifth of the weakest killable opponent's life. AIActivateLast on
    // the script keeps it behind every other play the AI is willing to make.
    // Reads state only and draws no RNG, like the stock refusal it replaces.
    public static class PrisonersDilemma {
        public static final int DMG = 4;

        public static AiAbilityDecision consider(final Player ai, final SpellAbility sa) {
            final Card source = sa.getHostCard();
            boolean anyDamageable = false;
            boolean nearLethal = false;
            Player weakest = null;
            int weakestLeft = Integer.MAX_VALUE;
            int weakestDmg = 0;
            for (final Player opp : ai.getOpponents()) {
                if (!opp.canLoseLife()) {
                    continue;
                }
                final int dmg = ComputerUtilCombat.predictDamageTo(opp, DMG, source, false);
                if (dmg <= 0) {
                    continue;
                }
                anyDamageable = true;
                if (opp.cantLoseForZeroOrLessLife()) {
                    continue; // takes the damage, but can't be burned out (Platinum Angel)
                }
                final int left = opp.getLife() - dmg;
                if (left < 5) {
                    nearLethal = true;
                }
                if (left < weakestLeft) {
                    weakest = opp;
                    weakestLeft = left;
                    weakestDmg = dmg;
                }
            }
            if (!anyDamageable) {
                return new AiAbilityDecision(0, AiPlayDecision.CantPlayAi); // nobody can take it
            }
            if (sa.hasParam("WithoutManaCost")) {
                return new AiAbilityDecision(100, AiPlayDecision.WillPlay); // free: no mana, and an exiled card is lost anyway
            }
            if (nearLethal) {
                return new AiAbilityDecision(100, AiPlayDecision.WillPlay);
            }
            final PhaseHandler ph = ai.getGame().getPhaseHandler();
            if (!ph.is(PhaseType.MAIN2, ai)) {
                return new AiAbilityDecision(0, AiPlayDecision.WaitForMain2);
            }
            if (sa.isFlashback()) {
                return new AiAbilityDecision(100, AiPlayDecision.WillPlay); // no card spent
            }
            if (!ai.isUnlimitedHandSize() && ai.getCardsIn(ZoneType.Hand).size() > ai.getMaxHandSize()) {
                return new AiAbilityDecision(100, AiPlayDecision.WillPlay); // cast it rather than discard it
            }
            if (weakest != null && weakestDmg * 5 >= weakest.getLife()) {
                return new AiAbilityDecision(100, AiPlayDecision.WillPlay);
            }
            return new AiAbilityDecision(0, AiPlayDecision.CantPlayAi);
        }
    }

    // Promise of Power
    // "Choose one - You draw five cards and you lose 5 life; or create an X/X black Demon creature
    // token with flying, where X is the number of cards in your hand. Entwine {4}"
    // The script carried AI:RemoveDeck:All, and behind it the stock charm chooser shuffles the modes
    // and trusts DrawAi/TokenAi, neither of which floors this card: TokenAi keys X on TokenAmount,
    // not the hand-sized TokenPower (a 0/0 Demon), DrawAi skips its overdraw guard for a charm mode
    // (drawback), and LifeLoseAi lets 8 life pay down to 3. Reached from CharmAi's name gate for the
    // plain spell and for the entwined copy (CharmAi.chooseOptionalCosts). Only in our own main 2.
    // Plain spell: the Demon when X at resolution (this card gone from hand) is at least 4, else the
    // draw when its floor holds and at most one card is discarded at cleanup. Entwine: only when the
    // draw floor holds (draw resolves first, so the Demon is at least 5/5). The draw floor: we can
    // draw, the library holds more than 8, no opponent's permanent triggers on or replaces draws
    // (Orcish Bowmasters, Spiteful Visions, Notion Thief, Alms Collector), and - unless we can't lose
    // life - at least 10 life is left after the 5, and more than the profile's danger threshold after
    // the opponents' unblocked next-turn attack. Reads state only and draws no RNG (aiLifeInDanger's
    // block simulation would). An empty list is CantPlayAi in CharmAi; the list is mutable because
    // CharmEffect.chainAbilities sorts it in place.
    public static class PromiseOfPower {
        public static final int DRAW = 5;
        public static final int LIFE = 5;
        public static final int MIN_LIFE_AFTER = 10;
        public static final int MIN_DEMON = 4;
        public static final int LIBRARY_MARGIN = 3; // DrawAi.targetAI's own deck-out margin

        public static List<AbilitySub> chooseModes(final Player ai, final SpellAbility sa, final List<AbilitySub> choices) {
            final List<AbilitySub> chosen = Lists.newArrayList();
            AbilitySub draw = null;
            AbilitySub demon = null;
            for (final AbilitySub sub : choices) {
                if (sub.getApi() == ApiType.Draw) {
                    draw = sub;
                } else if (sub.getApi() == ApiType.Token) {
                    demon = sub;
                }
            }
            final Card host = sa.getHostCard();
            // an exact MAIN2 test: chooseOptionalCosts runs this before the timing check, so a
            // "not before main 2" test would also choose at our end step
            if (draw == null || demon == null || host == null || !demon.hasParam("TokenPower")
                    || !ai.getGame().getPhaseHandler().is(PhaseType.MAIN2, ai)) {
                return chosen; // script drifted, or not the window: stay out
            }

            // X is counted at resolution, when this card has left the hand
            int handAfter = AbilityUtils.calculateAmount(host, demon.getParam("TokenPower"), demon);
            if (host.isInZone(ZoneType.Hand)) {
                handAfter--;
            }

            if (sa.isEntwine()) {
                // five cards, then a (handAfter + 5)/(handAfter + 5) flier
                if (drawSafe(ai)) {
                    chosen.add(draw);
                    chosen.add(demon);
                }
                return chosen;
            }

            if (handAfter >= MIN_DEMON) {
                chosen.add(demon);
            } else if (drawSafe(ai)
                    && (ai.isUnlimitedHandSize() || handAfter + DRAW <= ai.getMaxHandSize() + 1)) {
                chosen.add(draw);
            }
            return chosen;
        }

        // the draw mode's floor: never into a deck-out, a draw punisher, or low life
        static boolean drawSafe(final Player ai) {
            if (!ai.canDraw() || ai.getCardsIn(ZoneType.Library).size() <= DRAW + LIBRARY_MARGIN
                    || drawPunished(ai)) {
                return false;
            }
            if (!ai.canLoseLife()) {
                return true;
            }
            final int lifeAfter = ai.getLife() - LIFE;
            if (lifeAfter < MIN_LIFE_AFTER) {
                return false;
            }
            // unblocked damage ignores our blockers, so it only ever holds the draw back
            int unblocked = 0;
            for (final Player opp : ai.getOpponents()) {
                unblocked += ComputerUtilCombat.sumDamageIfUnblocked(CardLists.filter(opp.getCreaturesInPlay(),
                        c -> ComputerUtilCombat.canAttackNextTurn(c, ai)), ai);
            }
            return lifeAfter - unblocked > AiProfileUtil.getIntProperty(ai, AiProps.AI_IN_DANGER_MAX_THRESHOLD);
        }

        // an opponent's permanent that triggers on draws (Orcish Bowmasters, Spiteful Visions) or
        // replaces them (Notion Thief, Alms Collector): five draws would feed it, or not reach us
        static boolean drawPunished(final Player ai) {
            for (final Card c : ai.getGame().getCardsIn(ZoneType.Battlefield)) {
                if (ai.equals(c.getController())) {
                    continue;
                }
                for (final Trigger t : c.getTriggers()) {
                    if (t.getMode() == TriggerType.Drawn) {
                        return true;
                    }
                }
                for (final ReplacementEffect re : c.getReplacementEffects()) {
                    if (re.getMode() == ReplacementType.Draw || re.getMode() == ReplacementType.DrawCards) {
                        return true;
                    }
                }
            }
            return false;
        }
    }

    // Recurring Insight
    // "Draw cards equal to the number of cards in target opponent's hand. Rebound."
    // Reached from PumpAi's AILogic$ RecurringInsight branches: checkApiLogic for the cast from
    // hand, doTriggerNoCost for Rebound's upkeep cast. The root Pump only picks the opponent, and
    // PumpAi's non-curse targeting cannot target an opponent player, so the card was dead even
    // without AI:RemoveDeck; DrawAi's drawback check then approves any X short of decking. Pick
    // the targetable opponent whose hand gives the most draws - measured with the script's own
    // NumCards, that target set - and cast only when it is worth it: the hard cast in our main 2
    // when at least two of the drawn cards fit under the maximum hand size (it still draws all X;
    // the rest is a cleanup discard of our choice), the free rebound cast for at least one card.
    // Never into a deck-out, and never while an opponent's permanent replaces our draws (Notion
    // Thief, Hullbreacher, Alms Collector would take the cards instead). No random draws.
    public static class RecurringInsight {
        public static final int MIN_DRAW_HARD_CAST = 2;
        public static final int MIN_DRAW_FREE_CAST = 1;
        public static final int LIBRARY_MARGIN = 3; // DrawAi.targetAI's own deck-out margin

        public static AiAbilityDecision consider(final Player ai, final SpellAbility sa, final boolean fromEffect) {
            final Game game = ai.getGame();
            final Card host = sa.getHostCard();
            final AbilitySub drawSa = sa.getSubAbility();
            if (host == null || drawSa == null || drawSa.getApi() != ApiType.Draw || !ai.canDraw()) {
                return new AiAbilityDecision(0, AiPlayDecision.CantPlayAi);
            }

            // checkApiLogic is only reached for a cast that pays its cost; every effect-driven cast
            // (Rebound) comes through doTriggerNoCost. An effect that still charges mana keeps the
            // hard-cast floor, without the main 2 wait (a resolving effect has no priority window).
            final boolean free = fromEffect && (sa.hasParam("WithoutManaCost")
                    || sa.getPayCosts() == null || sa.getPayCosts().getTotalMana().isZero());

            if (!fromEffect) {
                if (!game.getStack().isEmpty()) {
                    return new AiAbilityDecision(0, AiPlayDecision.AnotherTime);
                }
                // A draw spell waits for our main 2 (DrawAi.checkPhaseRestrictions).
                if (!game.getPhaseHandler().is(PhaseType.MAIN2, ai)) {
                    return new AiAbilityDecision(0, AiPlayDecision.WaitForMain2);
                }
            }

            // Deliberately coarse: any active draw replacement an opponent controls declines the cast.
            for (final Player opp : ai.getOpponents()) {
                for (final Card c : opp.getCardsIn(ZoneType.Battlefield)) {
                    for (final ReplacementEffect re : c.getReplacementEffects()) {
                        if ((re.getMode() == ReplacementType.Draw || re.getMode() == ReplacementType.DrawCards)
                                && re.zonesCheck(game.getZoneOf(c))) {
                            return new AiAbilityDecision(0, AiPlayDecision.CantPlayAi);
                        }
                    }
                }
            }

            // Turn order; strict > keeps the first opponent on a tie.
            boolean anyTargetable = false;
            Player best = null;
            int bestX = 0;
            for (final Player opp : ai.getOpponents()) {
                if (!sa.canTarget(opp)) {
                    continue;
                }
                anyTargetable = true;
                sa.resetTargets();
                sa.getTargets().add(opp);
                // NumCards$ X reads TargetedPlayer$CardsInHand through the sub's parent: exactly
                // what resolution will count for this opponent.
                final int x = AbilityUtils.calculateAmount(host, drawSa.getParamOrDefault("NumCards", "1"), drawSa);
                if (x > bestX) {
                    best = opp;
                    bestX = x;
                }
            }
            sa.resetTargets();
            if (!anyTargetable) {
                return new AiAbilityDecision(0, AiPlayDecision.TargetingFailed);
            }
            if (best == null) {
                return new AiAbilityDecision(0, AiPlayDecision.CantPlayAi);
            }

            // Never deck ourselves (the margin DrawAi.chkDrawback vetoes on anyway).
            if (bestX >= ai.getCardsIn(ZoneType.Library).size() - LIBRARY_MARGIN) {
                return new AiAbilityDecision(0, AiPlayDecision.CantPlayAi);
            }

            int useful = bestX;
            if (!free && !ai.isUnlimitedHandSize()) {
                int handAfter = ai.getCardsIn(ZoneType.Hand).size();
                if (host.isInZone(ZoneType.Hand)) {
                    handAfter--; // the card itself is spent
                }
                useful = Math.min(bestX, Math.max(0, ai.getMaxHandSize() - handAfter));
            }
            if (useful < (free ? MIN_DRAW_FREE_CAST : MIN_DRAW_HARD_CAST)) {
                return new AiAbilityDecision(0, AiPlayDecision.CantPlayAi);
            }

            sa.getTargets().add(best);
            if (!sa.isTargetNumberValid()) {
                sa.resetTargets();
                return new AiAbilityDecision(0, AiPlayDecision.TargetingFailed);
            }
            return new AiAbilityDecision(100, AiPlayDecision.WillPlay);
        }
    }

    // Reins of Power
    // Two windows, both safe by construction. Offensive: our own turn before
    // attackers are declared, stack empty, and the targeted opponent's army
    // out-powers ours by a clear margin - swap, then swing their own creatures
    // at them (the spell untaps and hastes everything it moves); the creatures
    // we hand over can do nothing on our turn but block a combat that isn't
    // happening, and control reverts at end of turn. Defensive: the opponent's
    // declare-blockers step with lethal-ish damage incoming - gaining control
    // of the attackers removes them from combat (GameAction.
    // controllerChangeZoneCorrection -> Combat.removeFromCombat), a Fog that
    // borrows their army for the rest of the turn.
    public static class ReinsOfPower {
        public static final int MIN_POWER_MARGIN = 4;

        public static AiAbilityDecision consider(final Player ai, final SpellAbility sa) {
            final Game game = ai.getGame();
            final PhaseHandler ph = game.getPhaseHandler();

            // Routing through UntapAllAi.canPlay's name gate bypasses the base
            // class's restriction check, so mirror it here.
            if (sa.getRestrictions() != null && !sa.getRestrictions().canPlay(sa.getHostCard(), sa)) {
                return new AiAbilityDecision(0, AiPlayDecision.CantPlaySa);
            }

            // Defensive fog: their combat, our life on the line.
            final Combat combat = game.getCombat();
            if (!ph.isPlayerTurn(ai) && ph.is(PhaseType.COMBAT_DECLARE_BLOCKERS)
                    && combat != null && !combat.getAttackersOf(ai).isEmpty()
                    && ComputerUtilCombat.lifeInDanger(ai, combat)) {
                Player attacker = null;
                for (Card c : combat.getAttackersOf(ai)) {
                    if (c.getController().isOpponentOf(ai) && sa.canTarget(c.getController())) {
                        attacker = c.getController();
                        break;
                    }
                }
                if (attacker != null) {
                    sa.resetTargets();
                    sa.getTargets().add(attacker);
                    return new AiAbilityDecision(100, AiPlayDecision.WillPlay);
                }
            }

            // Offensive swap: only on our turn, only before combat, only with
            // the stack empty, and only when their board clearly beats ours.
            if (!ph.isPlayerTurn(ai) || !ph.getPhase().isBefore(PhaseType.COMBAT_DECLARE_ATTACKERS)
                    || !game.getStack().isEmpty()) {
                return new AiAbilityDecision(0, AiPlayDecision.CantPlayAi);
            }

            final int ourPower = attackPower(ai.getCreaturesInPlay());
            Player bestOpp = null;
            int bestPower = 0;
            for (Player opp : ai.getOpponents()) {
                if (!sa.canTarget(opp)) {
                    continue;
                }
                CardCollection theirs = opp.getCreaturesInPlay();
                int power = attackPower(theirs);
                if (theirs.size() >= 2 && power > bestPower) {
                    bestPower = power;
                    bestOpp = opp;
                }
            }
            if (bestOpp == null || bestPower < ourPower + MIN_POWER_MARGIN) {
                return new AiAbilityDecision(0, AiPlayDecision.CantPlayAi);
            }

            sa.resetTargets();
            sa.getTargets().add(bestOpp);
            return new AiAbilityDecision(100, AiPlayDecision.WillPlay);
        }

        // Power that would actually swing: positive-power non-defenders.
        private static int attackPower(final CardCollection creatures) {
            int total = 0;
            for (Card c : creatures) {
                if (c.getNetPower() > 0 && !c.hasKeyword(Keyword.DEFENDER)) {
                    total += c.getNetPower();
                }
            }
            return total;
        }
    }

    // Reverse the Sands
    //
    // LifeSetAi refuses every Redistribute SetLife outright; this is the cast
    // decision for this one card, reached only in our own main 2 (LifeSetAi's
    // phase gate runs first). Resolution is already right for the caster:
    // AiController.chooseNumber (SetLife) takes the highest total for us and
    // the lowest for opponents, and a total that became illegal is filtered
    // out, so the worst resolution is keeping our own life.
    public static class ReverseTheSands {
        // An 8-mana sorcery: the swap must be worth a turn's development.
        public static final int MIN_GAIN_ANY = 20;      // opponent far ahead: always worth it
        public static final int MIN_GAIN = 10;          // with our life at or below half of starting life
        public static final int MIN_GAIN_IN_DANGER = 5; // the swap outruns the next combat's life threat

        public static AiAbilityDecision consider(final Player ai, final SpellAbility sa) {
            // Gaining must actually be gaining (canGainLife, Tainted Remedy-style
            // LoseLife and Lich-style NoLife/LichDraw replacements); otherwise
            // the redistribution strips our higher total and the spell is a
            // no-op or worse.
            if (!ComputerUtil.lifegainPositive(ai, sa.getHostCard())) {
                return new AiAbilityDecision(0, AiPlayDecision.CantPlayAi);
            }

            // Swap with the highest-life opponent whose life can go down (a
            // Platinum Emperion-style "life total can't change" makes it illegal).
            Player best = null;
            for (Player opp : ai.getOpponents()) {
                if (!opp.canLoseLife()) {
                    continue;
                }
                if (best == null || opp.getLife() > best.getLife()) {
                    best = opp;
                }
            }
            if (best == null) {
                return new AiAbilityDecision(0, AiPlayDecision.CantPlayAi);
            }

            final int myLife = ai.getLife();
            final int gain = best.getLife() - myLife;
            if (gain >= MIN_GAIN_ANY) {
                return new AiAbilityDecision(100, AiPlayDecision.WillPlay);
            }
            if (gain >= MIN_GAIN && myLife <= ai.getStartingLife() / 2) {
                return new AiAbilityDecision(100, AiPlayDecision.WillPlay);
            }
            // The deterministic branches come first: the danger check builds a
            // predicted combat and draws RNG, so it runs only when the spell is
            // affordable. aiLifeInDanger also fires on commander damage, poison
            // and MustBeBlocked attackers, which a life swap does not answer, so
            // the swapped total (modelled as a payment of -gain) must clear it.
            if (gain >= MIN_GAIN_IN_DANGER
                    && ComputerUtilCost.canPayCost(sa, ai, false)
                    && ComputerUtil.aiLifeInDanger(ai, false, 0)
                    && !ComputerUtil.aiLifeInDanger(ai, false, -gain)) {
                return new AiAbilityDecision(100, AiPlayDecision.WillPlay);
            }
            return new AiAbilityDecision(0, AiPlayDecision.CantPlayAi);
        }
    }

    // Rile
    // "1 damage to target creature you control; it gains trample until end of
    // turn. Draw a card." DamageDealAi only targets objects an opponent
    // controls outside mandatory SAs, so Rile was never cast. It is a cantrip
    // whose cost is a ping on one of our own creatures. Floor: only in our own
    // main 2 (the ping can no longer change a combat and is gone at cleanup;
    // trample is forfeited on purpose), never with a library of 4 or fewer,
    // and only on a creature that survives the ping, that no damage
    // replacement anywhere would touch (amplifiers such as Angrath's
    // Marauders, which getEnoughDamageToKill does not model; prevention and
    // shield counters; redirection), and whose becomes-target or damage-dealt
    // triggers are not a cost to us. Prefer a body with an upside damage
    // trigger (Enrage, or an optional one we decide), e.g. Vrondiss, Rage of
    // Ancients' Dragon Spirit; otherwise the widest toughness margin. Draws no
    // RNG, like the stock DamageDealAi path it replaces for this card.
    public static class Rile {
        public static AiAbilityDecision consider(final Player ai, final SpellAbility sa) {
            // Routing from DamageDealAi.canPlay bypasses the base class's
            // restriction check, so mirror it here.
            if (sa.getRestrictions() != null && !sa.getRestrictions().canPlay(sa.getHostCard(), sa)) {
                return new AiAbilityDecision(0, AiPlayDecision.CantPlaySa);
            }
            sa.resetTargets();

            final Game game = ai.getGame();
            if (!game.getPhaseHandler().is(PhaseType.MAIN2, ai)) {
                return new AiAbilityDecision(0, AiPlayDecision.WaitForMain2);
            }
            if (!ai.canDraw() || ai.getCardsIn(ZoneType.Library).size() <= 4) {
                return new AiAbilityDecision(0, AiPlayDecision.CantPlayAi);
            }

            final Card source = sa.getHostCard();
            Card best = null;
            boolean bestUpside = false;
            int bestMargin = Integer.MIN_VALUE;
            int bestValue = Integer.MAX_VALUE;
            for (final Card c : ai.getCreaturesInPlay()) {
                if (!sa.canTarget(c)) {
                    continue;
                }
                // survives: marked damage, indestructible, shields and
                // DestroyWhenDamaged are all in getEnoughDamageToKill
                if (ComputerUtilCombat.getEnoughDamageToKill(c, 1, source, false) <= 1) {
                    continue;
                }
                if (damageWouldBeReplaced(game, c, source, sa)) {
                    continue;
                }
                if (c.hasSVar("Targeting") || c.hasSVar("SacMe")) {
                    continue;
                }
                if (hasCostlySelfTrigger(c, source)) {
                    continue;
                }
                final boolean upside = hasUpsideDamageTrigger(c, source)
                        && ComputerUtilCombat.predictDamageTo(c, 1, source, false) > 0;
                final int margin = c.getNetToughness() - c.getDamage();
                final int value = ComputerUtilCard.evaluateCreature(c);
                final boolean better;
                if (best == null) {
                    better = true;
                } else if (upside != bestUpside) {
                    better = upside;
                } else if (upside) {
                    better = value > bestValue;         // the best enrage body
                } else {
                    better = margin > bestMargin || (margin == bestMargin && value < bestValue);
                }
                if (better) {
                    best = c;
                    bestUpside = upside;
                    bestMargin = margin;
                    bestValue = value;
                }
            }
            if (best == null) {
                return new AiAbilityDecision(0, AiPlayDecision.TargetingFailed);
            }
            sa.getTargets().add(best);
            return new AiAbilityDecision(100, AiPlayDecision.WillPlay);
        }

        // Any DamageDone replacement on any card or effect that would modify
        // Rile's 1 damage to c: amplifiers (Angrath's Marauders, Fiery
        // Emancipation), prevention, shield counters, redirection. Read-only;
        // over-excluding only costs a cast.
        private static boolean damageWouldBeReplaced(final Game game, final Card c, final Card source, final SpellAbility sa) {
            final Map<forge.game.ability.AbilityKey, Object> repParams = forge.game.ability.AbilityKey.mapFromAffected(c);
            repParams.put(forge.game.ability.AbilityKey.DamageSource, source);
            repParams.put(forge.game.ability.AbilityKey.DamageAmount, 1);
            repParams.put(forge.game.ability.AbilityKey.IsCombat, false);  // ReplaceDamage unboxes it under IsCombat$
            repParams.put(forge.game.ability.AbilityKey.Cause, sa);        // CauseIsSource dereferences it
            return !game.getReplacementHandler().getReplacementList(ReplacementType.DamageDone, repParams, null).isEmpty();
        }

        // Becomes-target triggers (Illusion-style sacrifice; conservatively
        // also Valiant, ward and Thunderbreak Regent-style bodies) and
        // damage-dealt triggers that are not provably upside (Jackal Pup's
        // damage to us, Phyrexian Obliterator's sacrifice, Tephraderm).
        private static boolean hasCostlySelfTrigger(final Card c, final Card source) {
            for (final Trigger t : c.getTriggers()) {
                final TriggerType mode = t.getMode();
                if ((mode == TriggerType.BecomesTarget || mode == TriggerType.BecomesTargetOnce)
                        && t.matchesValidParam("ValidTarget", c)) {
                    return true;
                }
                if (isDamageDealtToTrigger(t, c, source) && !isUpside(t)) {
                    return true;
                }
            }
            return false;
        }

        private static boolean hasUpsideDamageTrigger(final Card c, final Card source) {
            for (final Trigger t : c.getTriggers()) {
                if (isDamageDealtToTrigger(t, c, source) && isUpside(t)) {
                    return true;
                }
            }
            return false;
        }

        private static boolean isDamageDealtToTrigger(final Trigger t, final Card c, final Card source) {
            final TriggerType mode = t.getMode();
            return (mode == TriggerType.DamageDone || mode == TriggerType.DamageDoneOnce)
                    && t.hasParam("ValidTarget") && t.matchesValidParam("ValidTarget", c)
                    && t.matchesValidParam("ValidSource", source);
        }

        // an optional trigger we decide ourselves, or the Enrage ability word
        // (upside by design: dinosaurs, AFC dragons)
        private static boolean isUpside(final Trigger t) {
            return "You".equals(t.getParam("OptionalDecider"))
                    || t.getParamOrDefault("TriggerDescription", "").startsWith("Enrage");
        }
    }

    public static class SarkhanTheMad {
        public static AiAbilityDecision considerDig(final Player ai, final SpellAbility sa) {
            if (sa.getHostCard().getCounters(CounterEnumType.LOYALTY) == 1) {
                return new AiAbilityDecision(100, AiPlayDecision.WillPlay);
            } else {
                return new AiAbilityDecision(0, AiPlayDecision.CantPlayAi);
            }
        }

        public static AiAbilityDecision considerMakeDragon(final Player ai, final SpellAbility sa) {
            // TODO: expand this logic to make the AI force the opponent to sacrifice a big threat bigger than a 5/5 flier?
            CardCollection creatures = ai.getCreaturesInPlay();
            boolean hasValidTgt = !CardLists.filter(creatures, t -> t.getNetPower() < 5 && t.getNetToughness() < 5).isEmpty();
            if (hasValidTgt) {
                Card worstCreature = ComputerUtilCard.getWorstCreatureAI(creatures);
                sa.getTargets().add(worstCreature);
                return new AiAbilityDecision(100, AiPlayDecision.AddBoardPresence);
            }
            return new AiAbilityDecision(0, AiPlayDecision.CantPlayAi);
        }


        public static boolean considerUltimate(final Player ai, final SpellAbility sa, final Player weakestOpp) {
            int minLife = weakestOpp.getLife();

            int dragonPower = 0;
            CardCollection dragons = CardLists.filter(ai.getCreaturesInPlay(), CardPredicates.isType("Dragon"));
            for (Card c : dragons) {
                dragonPower += c.getNetPower();
            }

            return dragonPower >= minLife;
        }
    }

    // Savior of Ollenbock
    public static class SaviorOfOllenbock {
        public static boolean consider(final Player ai, final SpellAbility sa) {
            CardCollection oppTargetables = CardLists.getTargetableCards(ai.getOpponents().getCreaturesInPlay(), sa);
            CardCollection threats = CardLists.filter(oppTargetables, card -> !ComputerUtilCard.isUselessCreature(card.getController(), card));
            CardCollection ownTgts = CardLists.filter(ai.getCardsIn(ZoneType.Graveyard), CardPredicates.CREATURES);

            // TODO: improve the conditions for when the AI is considered threatened (check the possibility of being attacked?)
            int lifeInDanger = (((PlayerControllerAi) ai.getController()).getAi().getIntProperty(AiProps.AI_IN_DANGER_THRESHOLD));
            boolean threatened = !threats.isEmpty() && ((ai.getLife() <= lifeInDanger && !ai.cantLoseForZeroOrLessLife()) || ai.getLifeLostLastTurn() + ai.getLifeLostThisTurn() > 0);

            if (threatened) {
                sa.getTargets().add(ComputerUtilCard.getBestCreatureAI(threats));
            } else if (!ownTgts.isEmpty()) {
                Card target = ComputerUtilCard.getBestCreatureAI(ownTgts);
                sa.getTargets().add(target);

                int ownExiledValue = ComputerUtilCard.evaluateCreature(target), oppExiledValue = 0;
                for (Card c : ai.getGame().getCardsIn(ZoneType.Exile)) {
                    if (c.getExiledWith() == sa.getHostCard()) {
                        if (c.getOwner() == ai) {
                            ownExiledValue += ComputerUtilCard.evaluateCreature(c);
                        } else {
                            oppExiledValue += ComputerUtilCard.evaluateCreature(c);
                        }
                    }
                }
                if (ownExiledValue > oppExiledValue + 150) {
                    sa.getHostCard().setSVar("SacMe", "5");
                } else {
                    sa.getHostCard().removeSVar("SacMe");
                }
            } else if (!threats.isEmpty()) {
                sa.getTargets().add(ComputerUtilCard.getBestCreatureAI(threats));
            }

            return sa.isTargetNumberValid();
        }
    }

    // Seize the Spotlight
    // "Each opponent chooses fame or fortune. For each opponent who chose fame,
    // gain control of a creature that player controls until end of turn, untap
    // it, it gains haste. For each who chose fortune, draw a card and create a
    // Treasure." A Forge AI opponent always picks Fame (ChooseGenericAi.
    // chooseSingleSpellAbility, no AILogic -> the first choice), so the line
    // that happens is a Threaten whose creature we choose; Fortune (a human
    // opponent) is a card and a Treasure, never harmful. Cast only on our own
    // turn before attackers, with an empty stack and combat not skipped, and
    // only for a creature worth taking, judged on the one the chooser takes
    // (the best by evaluation that can attack and wears no triggered Equipment
    // or Aura of an opponent's): lethal on its controller, or at least
    // MIN_STOLEN_DAMAGE combat damage or MIN_STOLEN_VALUE of creature. A
    // non-lethal steal is a few damage at sorcery speed and does nothing
    // defensively (the creature untaps back home), so it is also held while the
    // opponents' next attack puts us in danger, and while the mana it spends is
    // what a creature or planeswalker in hand, or our commander, needs this turn.
    // Draws no RNG, like the stock refusal it replaces: the danger check is
    // ComputerUtilCombat.lifeInDanger's threshold rule without its MyRandom
    // draws, and not ComputerUtil.aiLifeInDanger, whose AiBlockController draws
    // MyRandom on every pass while the card is held.
    public static class SeizeTheSpotlight {
        public static final int MIN_STOLEN_DAMAGE = 3;
        public static final int MIN_STOLEN_VALUE = 200;

        public static AiAbilityDecision consider(final Player ai, final SpellAbility sa) {
            final Game game = ai.getGame();
            final PhaseHandler ph = game.getPhaseHandler();
            if (!ph.isPlayerTurn(ai) || !ph.getPhase().isBefore(PhaseType.COMBAT_DECLARE_ATTACKERS)
                    || !game.getStack().isEmpty()
                    || game.getReplacementHandler().wouldPhaseBeSkipped(ai, PhaseType.COMBAT_BEGIN)) {
                return new AiAbilityDecision(0, AiPlayDecision.AnotherTime);
            }

            boolean worthTaking = false;
            for (final Player opp : ai.getOpponents()) {
                final Card best = bestSteal(ai, opp.getCreaturesInPlay());
                if (best == null) {
                    continue;
                }
                final int dmg = best.getNetCombatDamage();
                if (dmg >= opp.getLife()) {
                    return new AiAbilityDecision(100, AiPlayDecision.WillPlay);
                }
                if (dmg >= MIN_STOLEN_DAMAGE || ComputerUtilCard.evaluateCreature(best) >= MIN_STOLEN_VALUE) {
                    worthTaking = true;
                }
            }
            if (worthTaking && !inDangerNextCombat(ai) && !displacesPermanent(ai, sa)) {
                return new AiAbilityDecision(100, AiPlayDecision.WillPlay);
            }
            return new AiAbilityDecision(0, AiPlayDecision.CantPlayAi);
        }

        // Resolution: the same filter the cast was judged on, so the creature we
        // judged is the creature we take (unless the board changed in response).
        public static Card chooseCreature(final Player ai, final Iterable<Card> options) {
            final Card best = bestSteal(ai, options);
            return best != null ? best : ComputerUtilCard.getBestCreatureAI(options);
        }

        // Act of Treason's filter (ControlGainAi.canPlay): we can control it, it
        // has positive combat damage, it can attack one of our opponents, and it
        // is not a card the AI is told it cannot use. Plus: it wears no triggered
        // attachment of an opponent's.
        private static Card bestSteal(final Player ai, final Iterable<Card> creatures) {
            final CardCollection able = new CardCollection();
            for (final Card c : creatures) {
                if (!c.isCreature() || c.isPhasedOut() || !c.canBeControlledBy(ai)
                        || c.getNetCombatDamage() <= 0 || ComputerUtilCard.isCardRemAIDeck(c)
                        || hasOpposingAttachmentTrigger(ai, c)) {
                    continue;
                }
                for (final Player opp : ai.getOpponents()) {
                    if (ComputerUtilCombat.canAttackNextTurn(c, opp)) {
                        able.add(c);
                        break;
                    }
                }
            }
            return able.isEmpty() ? null : ComputerUtilCard.getBestCreatureAI(able);
        }

        // An Equipment or Aura stays under its controller when we take the
        // creature, so its triggers (The Key to the Vault, the Swords, Mask of
        // Memory, Skullclamp...) fire for the opponent off our attack.
        private static boolean hasOpposingAttachmentTrigger(final Player ai, final Card c) {
            for (final Card a : c.getAttachedCards()) {
                if (!a.getController().equals(ai) && !a.getTriggers().isEmpty()) {
                    return true;
                }
            }
            return false;
        }

        // Each opponent attacks us with every creature that can attack next
        // turn and nothing blocks. Danger is lifeInSeriousDanger (lethal damage,
        // commander damage, poison, a MustBeBlocked attacker), or
        // ComputerUtilCombat.lifeInDanger's poison and threshold rules without
        // its MyRandom draws: AI_IN_DANGER_THRESHOLD itself, never raised by a
        // roll. With no blocks it holds the card in some states where our
        // blockers would have kept us safe - more cautious, and deterministic.
        private static boolean inDangerNextCombat(final Player ai) {
            final int threshold = AiProfileUtil.getIntProperty(ai, AiProps.AI_IN_DANGER_THRESHOLD);
            for (final Player opp : ai.getOpponents()) {
                final Combat combat = new Combat(opp);
                boolean containsAttacker = false;
                for (final Card att : opp.getCreaturesInPlay()) {
                    if (ComputerUtilCombat.canAttackNextTurn(att, ai)) {
                        combat.addAttacker(att, ai);
                        containsAttacker = true;
                    }
                }
                if (!containsAttacker) {
                    continue;
                }
                if (ComputerUtilCombat.lifeInSeriousDanger(ai, combat)
                        || ComputerUtilCombat.resultingPoison(ai, combat) > Math.max(7, ai.getPoisonCounters())
                        || (!ai.cantLoseForZeroOrLessLife()
                            && ComputerUtilCombat.lifeThatWouldRemain(ai, combat) < Math.min(threshold, ai.getLife()))) {
                    return true;
                }
            }
            return false;
        }

        // A non-lethal steal is a few damage at sorcery speed. Hold it while the
        // mana it spends is what a creature or planeswalker in hand, or our
        // commander in the command zone (with its tax), needs this turn: one we
        // could cast with the mana we have, but not with what the steal leaves.
        // Colours are ignored, so it holds a little more often than strictly
        // needed, never less.
        private static boolean displacesPermanent(final Player ai, final SpellAbility sa) {
            final int avail = ComputerUtilMana.getAvailableManaEstimate(ai, true);
            final int left = avail - sa.getPayCosts().getTotalMana().getCMC();
            final CardCollection cands = CardLists.filter(ai.getCardsIn(ZoneType.Hand),
                    card -> card.isCreature() || card.isPlaneswalker());
            for (final Card cmdr : ai.getCommanders()) {
                if (cmdr.isInZone(ZoneType.Command)) {
                    cands.add(cmdr);
                }
            }
            for (final Card c : cands) {
                final int cost = c.getCMC() + (c.isCommander() ? 2 * ai.getCommanderCast(c) : 0);
                if (cost <= avail && cost > left) {
                    return true;
                }
            }
            return false;
        }
    }

    // Song of Inspiration
    // Both d20 results return the targets, so this is a five-mana instant Regrowth for up to two
    // permanent cards (15+ also gains life equal to their total mana value). The script uses Pump as
    // a targeting shell, and PumpAi's generic targeting only enumerates creatures on the battlefield
    // (TargetRestrictions.canTgtCreature() is true for "Permanent..." whatever TgtZone says), so the
    // graveyard was never looked at. This picks and targets the cards itself. No random draws: the
    // stock path it replaces drew none either.
    public static class SongOfInspiration {
        public static final int MIN_TOTAL_CMC = 4;

        public static AiAbilityDecision consider(final Player ai, final SpellAbility sa) {
            final Game game = ai.getGame();
            final PhaseHandler ph = game.getPhaseHandler();
            sa.resetTargets();

            // Not a response, never on our own turn: the opponent's end step right before our turn,
            // when the mana would otherwise go unused and the returned cards are castable next turn.
            if (!game.getStack().isEmpty() || !ph.is(PhaseType.END_OF_TURN) || !ai.equals(ph.getNextTurn())) {
                return new AiAbilityDecision(0, AiPlayDecision.WaitForEndOfTurn);
            }

            CardCollection pool = CardLists.getTargetableCards(ai.getCardsIn(ZoneType.Graveyard), sa);
            pool = CardLists.filter(pool, c -> !c.isLand()
                    && !(c.getType().isLegendary() && !c.ignoreLegendRule() && ai.isCardInPlay(c.getName())));

            // Highest mana value first, to match the floor below (getBestAI ranks an all-creature pool
            // by evaluateCreature and could miss a pair that clears it).
            final CardCollection picks = new CardCollection();
            while (picks.size() < sa.getMaxTargets() && !pool.isEmpty()) {
                final Card best = ComputerUtilCard.getMostExpensivePermanentAI(pool);
                pool.remove(best);
                picks.add(best);
            }
            if (picks.isEmpty() || Aggregates.sum(picks, Card::getCMC) < MIN_TOTAL_CMC) {
                return new AiAbilityDecision(0, AiPlayDecision.CantPlayAi);
            }

            // Don't pull back cards we would only discard.
            final int handAfter = ai.getCardsIn(ZoneType.Hand).size()
                    - (sa.getHostCard().isInZone(ZoneType.Hand) ? 1 : 0) + picks.size();
            if (!ai.isUnlimitedHandSize() && handAfter > ai.getMaxHandSize()) {
                return new AiAbilityDecision(0, AiPlayDecision.CantPlayAi);
            }

            for (final Card c : picks) {
                if (!sa.canTarget(c)) {
                    sa.resetTargets();
                    return new AiAbilityDecision(0, AiPlayDecision.TargetingFailed);
                }
                sa.getTargets().add(c);
            }
            return new AiAbilityDecision(100, AiPlayDecision.WillPlay);
        }
    }

    // Sorin, Vengeful Bloodlord
    public static class SorinVengefulBloodlord {
        public static AiAbilityDecision consider(final Player ai, final SpellAbility sa) {
            int loyalty = sa.getHostCard().getCounters(CounterEnumType.LOYALTY);
            CardCollection creaturesToGet = CardLists.filter(ai.getCardsIn(ZoneType.Graveyard),
                    CardPredicates.CREATURES
                            .and(CardPredicates.lessCMC(loyalty - 1))
                            .and(card -> {
                                final Card copy = CardCopyService.getLKICopy(card);
                                ComputerUtilCard.applyStaticContPT(ai.getGame(), copy, null);
                                return copy.getNetToughness() > 0;
                            })
            );

            if (creaturesToGet.isEmpty()) {
                return new AiAbilityDecision(0, AiPlayDecision.CantPlayAi);
            }

            CardLists.sortByCmcDesc(creaturesToGet);

            // pick the best creature that will stay on the battlefield
            Card best = creaturesToGet.getFirst();
            for (Card c : creaturesToGet) {
                if (best != c && ComputerUtilCard.evaluateCreature(c, true, false) >
                        ComputerUtilCard.evaluateCreature(best, true, false)) {
                    best = c;
                }
            }

            if (best != null) {
                sa.resetTargets();
                sa.getTargets().add(best);
                sa.setXManaCostPaid(best.getCMC());
                return new AiAbilityDecision(100, AiPlayDecision.WillPlay);
            }

            return new AiAbilityDecision(0, AiPlayDecision.TargetingFailed);
        }
    }

    // Surge to Victory
    // "Exile target instant or sorcery card from your graveyard. Creatures you
    // control get +X/+0 until end of turn, where X is that card's mana value.
    // Whenever a creature you control deals combat damage to a player this
    // turn, you may cast a copy of the exiled card." ChangeZoneAi's generic
    // graveyard-exile targeting keeps only opponents' cards, so the card was
    // never cast. The script's copy has no Optional$: every creature of ours
    // that connects FORCE-casts a copy of the pick in our own combat damage
    // step, while our creatures are attacking. So the pick must be safe under
    // compulsion - a whitelisted, untargeted, self-only effect chain: no
    // forced cast inside it, nothing leaving the battlefield, no Effect static
    // other than "may play", no trigger or replacement effect, no cost beyond
    // mana or a discard (a sacrifice or life cost would be paid again for
    // every copy), no Demonstrate (each copy could hand an opponent one).
    // Floor: our own main 1 (a main-2 cast pumps nothing and never triggers),
    // combat damage not prevented this turn, a pick of mana value at least
    // MIN_PICK_CMC, pick mana value times the attackers of the AI's own attack
    // plan that face no attack tax (Propaganda, Ghostly Prison: the prediction
    // ignores taxes, and Surge's six mana may be what would have paid one) at
    // least MIN_TOTAL_PUMP, and a library deeper than LIBRARY_PER_COPY cards
    // per such attacker. Every board-only check runs before the predicted
    // combat is built, which can draw MyRandom; the stock refusal drew nothing.
    public static class SurgeToVictory {
        public static final int MIN_PICK_CMC = 2;
        public static final int MIN_TOTAL_PUMP = 4;
        public static final int LIBRARY_PER_COPY = 7; // Apex of Power exiles 7, Dig Through Time digs 7

        private static final EnumSet<ApiType> SAFE_COPY_APIS = EnumSet.of(
                ApiType.Draw, ApiType.Dig, ApiType.DigUntil, ApiType.Token, ApiType.Mana,
                ApiType.Scry, ApiType.Surveil, ApiType.Shuffle, ApiType.RearrangeTopOfLibrary,
                ApiType.PeekAndReveal, ApiType.Effect, ApiType.Cleanup, ApiType.ChangeZone,
                ApiType.Discard, ApiType.Play);

        public static AiAbilityDecision consider(final Player ai, final SpellAbility sa) {
            final Game game = ai.getGame();
            // The stock targeting reset the targets before it refused; a decline
            // here leaves them the same way.
            sa.resetTargets();

            if (!game.getPhaseHandler().is(PhaseType.MAIN1, ai)
                    || game.getReplacementHandler().isPreventCombatDamageThisTurn()) {
                return new AiAbilityDecision(0, AiPlayDecision.WaitForCombat);
            }
            final int creatures = ai.getCreaturesInPlay().size();
            final int library = ai.getCardsIn(ZoneType.Library).size();
            if (creatures == 0 || library <= LIBRARY_PER_COPY) {
                return new AiAbilityDecision(0, AiPlayDecision.CantPlayAi);
            }

            Card pick = null;
            for (Card c : ai.getCardsIn(ZoneType.Graveyard)) {
                if (!(c.isInstant() || c.isSorcery()) || c.getManaCost() == null
                        || c.getCMC() < MIN_PICK_CMC || c.getManaCost().countX() > 0
                        || !sa.canTarget(c) || !safeAsForcedCopy(c)) {
                    continue;
                }
                if (pick == null || c.getCMC() > pick.getCMC()) {
                    pick = c;
                }
            }
            // The attackers are a subset of our creatures: when even all of them
            // fall short of the floor, decline before predicting combat.
            if (pick == null || pick.getCMC() * creatures < MIN_TOTAL_PUMP) {
                return new AiAbilityDecision(0, AiPlayDecision.CantPlayAi);
            }

            final Combat predicted = ((PlayerControllerAi) ai.getController()).getAi().getPredictedCombat();
            int attackers = 0;
            for (Card c : ai.getCreaturesInPlay()) {
                if (predicted.isAttacking(c)
                        && CombatUtil.getAttackCost(game, c, predicted.getDefenderByAttacker(c)) == null) {
                    attackers++;
                }
            }
            if (attackers == 0 || pick.getCMC() * attackers < MIN_TOTAL_PUMP
                    || library <= LIBRARY_PER_COPY * attackers) {
                return new AiAbilityDecision(0, AiPlayDecision.CantPlayAi);
            }

            sa.getTargets().add(pick);
            return new AiAbilityDecision(100, AiPlayDecision.WillPlay);
        }

        // Whitelist, not blacklist: every part of every basic spell must be an
        // untargeted self-only effect that cannot touch the battlefield we are
        // attacking with. Unlisted apis (Charm, RepeatEach, GenericChoice,
        // ImmediateTrigger, TwoPiles, any ...All) hide their effects outside
        // the getSubAbility chain or hit every creature.
        static boolean safeAsForcedCopy(final Card c) {
            if (c.getBasicSpells().isEmpty() || c.hasKeyword(Keyword.DEMONSTRATE)) {
                return false;
            }
            for (SpellAbility csa : c.getBasicSpells()) {
                if (csa.getPayCosts() != null) {
                    for (CostPart cp : csa.getPayCosts().getCostParts()) {
                        if (!(cp instanceof CostPartMana) && !(cp instanceof CostDiscard)) {
                            return false; // a sacrifice / life / exile cost on every connecting attacker
                        }
                    }
                }
                for (SpellAbility part = csa; part != null; part = part.getSubAbility()) {
                    final ApiType api = part.getApi();
                    if (api == null || part.usesTargeting() || !SAFE_COPY_APIS.contains(api)) {
                        return false;
                    }
                    if (api == ApiType.Play && !part.hasParam("Optional")) {
                        return false; // a forced cast inside the forced cast
                    }
                    if (api == ApiType.ChangeZone && part.getParamOrDefault("Origin", "").contains("Battlefield")) {
                        return false; // could move our attackers
                    }
                    if (api == ApiType.Effect) {
                        if (part.hasParam("Triggers") || part.hasParam("ReplacementEffects")) {
                            return false;
                        }
                        if (part.hasParam("StaticAbilities")) {
                            for (String st : part.getParam("StaticAbilities").split(",")) {
                                if (!part.getSVar(st.trim()).contains("MayPlay$")) {
                                    return false; // Hunter's Ambush, Inspired Idea, Peace Talks ...
                                }
                            }
                        }
                    }
                    final String who = part.getParamOrDefault(api == ApiType.Token ? "TokenOwner" : "Defined", "You");
                    if (api != ApiType.ChangeZone && api != ApiType.Play && api != ApiType.Effect
                            && api != ApiType.Cleanup && !"You".equals(who)) {
                        return false; // a draw, dig, discard or token aimed at someone else
                    }
                }
            }
            return true;
        }
    }

    // Survival of the Fittest
    public static class SurvivalOfTheFittest {
        public static Card considerDiscardTarget(final Player ai) {
            // The AI here only checks the number of available creatures of various CMC, which is equivalent to knowing
            // your deck composition and checking (and counting) the cards in other zones so you know what you have left
            // in the library. As such, this does not cause unfair advantage, at least unless there are cards that are
            // face down (on the battlefield or in exile). Might need some kind of an update to consider hidden information
            // like that properly (probably by adding all those cards to the evaluation mix so the AI doesn't "know" which
            // ones are already face down in play and which are still in the library)
            CardCollectionView creatsInLib = CardLists.filter(ai.getCardsIn(ZoneType.Library), CardPredicates.CREATURES);
            CardCollectionView creatsInHand = CardLists.filter(ai.getCardsIn(ZoneType.Hand), CardPredicates.CREATURES);
            CardCollectionView manaSrcsInHand = CardLists.filter(ai.getCardsIn(ZoneType.Hand), CardPredicates.LANDS_PRODUCING_MANA);

            if (creatsInHand.isEmpty() || creatsInLib.isEmpty()) {
                return null;
            }

            int numManaSrcs = ComputerUtilMana.getAvailableManaEstimate(ai, false)
                    + Math.min(1, manaSrcsInHand.size());

            // Cards in library that are either below/at (preferred) or above the max CMC affordable by the AI
            // (the latter might happen if we're playing a Reanimator deck with lots of fatties)
            CardCollection atTargetCMCInLib = CardLists.filter(creatsInLib,
                    card -> ComputerUtilMana.hasEnoughManaSourcesToCast(card.getSpellPermanent(), ai)
            );
            if (atTargetCMCInLib.isEmpty()) {
                atTargetCMCInLib = CardLists.filter(creatsInLib, CardPredicates.greaterCMC(numManaSrcs));
            }
            atTargetCMCInLib.sort(CardLists.CmcComparatorInv);
            if (atTargetCMCInLib.isEmpty()) {
                // Nothing to aim for?
                return null;
            }

            // Cards in hand that are below the max CMC affordable by the AI
            CardCollection belowMaxCMC = CardLists.filter(creatsInHand, CardPredicates.lessCMC(numManaSrcs - 1));
            belowMaxCMC.sort(CardLists.CmcComparator);

            // Cards in hand that are above the max CMC affordable by the AI
            CardCollection aboveMaxCMC = CardLists.filter(creatsInHand, CardPredicates.greaterCMC(numManaSrcs + 1));
            aboveMaxCMC.sort(CardLists.CmcComparatorInv);

            Card maxCMC = !aboveMaxCMC.isEmpty() ? aboveMaxCMC.getFirst() : null;
            Card minCMC = !belowMaxCMC.isEmpty() ? belowMaxCMC.getFirst() : null;
            Card bestInLib = !atTargetCMCInLib.isEmpty() ? atTargetCMCInLib.getFirst() : null;

            int maxCMCdiff = 0;
            if (maxCMC != null) {
                maxCMCdiff = maxCMC.getCMC() - numManaSrcs; // how far are we from viably casting it?
            }

            // We have something too fat to viably cast in the nearest future, discard it hoping to
            // grab something more immediately valuable (or maybe we're playing Reanimator and we want
            // it to be in the graveyard anyway)
            if (maxCMCdiff >= 3) {
                return maxCMC;
            }
            // We have a card in hand that is worse than the one in library, so discard the worst card
            if (maxCMCdiff <= 0 && minCMC != null
                    && ComputerUtilCard.evaluateCreature(bestInLib) > ComputerUtilCard.evaluateCreature(minCMC)) {
                return minCMC;
            }
            // We have a card in the library that is closer to being castable than the one in hand, and
            // no options with smaller CMC, so discard the one that is harder to cast for the one that is
            // easier to cast right now, but only if the best card in the library is at least CMC 3
            // (probably not worth it to grab low mana cost cards this way)
            if (maxCMC != null && bestInLib != null && maxCMC.getCMC() < bestInLib.getCMC() && bestInLib.getCMC() >= 3) {
                return maxCMC;
            }
            // We appear to be playing Reanimator (or we have a reanimator card in hand already), so it's
            // worth to fill the graveyard now
            if (ComputerUtil.isPlayingReanimator(ai) && !creatsInLib.isEmpty()) {
                CardCollection creatsInHandByCMC = new CardCollection(creatsInHand);
                creatsInHandByCMC.sort(CardLists.CmcComparatorInv);
                return creatsInHandByCMC.getFirst();
            }

            // probably nothing that is worth changing, so bail
            return null;
        }

        public static Card considerCardToGet(final Player ai, final SpellAbility sa) {
            CardCollection creatsInLib = CardLists.filter(ai.getCardsIn(ZoneType.Library), CardPredicates.CREATURES);
            if (creatsInLib.isEmpty()) {
                return null;
            }

            CardCollectionView manaSrcsInHand = CardLists.filter(ai.getCardsIn(ZoneType.Hand), CardPredicates.LANDS_PRODUCING_MANA);
            int numManaSrcs = ComputerUtilMana.getAvailableManaEstimate(ai, false)
                    + Math.min(1, manaSrcsInHand.size());

            CardCollection atTargetCMCInLib = CardLists.filter(creatsInLib,
                    card -> ComputerUtilMana.hasEnoughManaSourcesToCast(card.getSpellPermanent(), ai)
            );
            if (atTargetCMCInLib.isEmpty()) {
                atTargetCMCInLib = CardLists.filter(creatsInLib, CardPredicates.greaterCMC(numManaSrcs));
            }
            atTargetCMCInLib.sort(CardLists.CmcComparatorInv);

            Card bestInLib = atTargetCMCInLib.getFirst();

            if (bestInLib == null && ComputerUtil.isPlayingReanimator(ai)) {
                // For Reanimator, we don't mind grabbing the biggest thing possible to recycle it again with SotF later.
                creatsInLib.sort(CardLists.CmcComparatorInv);
                return creatsInLib.getFirst();
            }

            return bestInLib;
        }
    }

    // Spelltwine
    // Exile our best instant/sorcery and an opponent's, copy both, cast the
    // copies free. The copies are cast "if able" - a forced cast the AI does
    // not get to decline at resolution - so both picks are restricted to
    // spells that are safe under compulsion: no targeting anywhere in the
    // chain (a mandatory cast with only our own permanents as legal targets
    // would be aimed at us) and no "...All" api (a copied wrath nukes our own
    // board). A CMC floor makes six mana buy real cards.
    public static class Spelltwine {
        public static final int MIN_PICK_CMC = 2;
        public static final int MIN_COMBINED_CMC = 5;

        public static AiAbilityDecision consider(final Player ai, final SpellAbility sa) {
            final AbilitySub tgtOpp = sa.getSubAbility();
            if (tgtOpp == null || tgtOpp.getApi() != ApiType.ChangeZone || !tgtOpp.usesTargeting()) {
                return new AiAbilityDecision(0, AiPlayDecision.CantPlayAi);
            }

            Card mine = bestSafePick(CardLists.getValidCards(ai.getCardsIn(ZoneType.Graveyard),
                    "Instant.YouCtrl,Sorcery.YouCtrl", ai, sa.getHostCard(), sa), sa);
            Card theirs = bestSafePick(CardLists.getValidCards(ai.getOpponents().getCardsIn(ZoneType.Graveyard),
                    "Instant.OppOwn,Sorcery.OppOwn", ai, sa.getHostCard(), tgtOpp), tgtOpp);

            if (mine == null || theirs == null
                    || mine.getManaCost().getCMC() + theirs.getManaCost().getCMC() < MIN_COMBINED_CMC) {
                return new AiAbilityDecision(0, AiPlayDecision.CantPlayAi);
            }

            sa.resetTargets();
            if (!sa.canTarget(mine)) {
                return new AiAbilityDecision(0, AiPlayDecision.TargetingFailed);
            }
            sa.getTargets().add(mine);

            tgtOpp.resetTargets();
            if (!tgtOpp.canTarget(theirs)) {
                sa.resetTargets();
                return new AiAbilityDecision(0, AiPlayDecision.TargetingFailed);
            }
            tgtOpp.getTargets().add(theirs);
            return new AiAbilityDecision(100, AiPlayDecision.WillPlay);
        }

        // Highest-CMC card (>= the floor) that is safe to cast under
        // compulsion: no targeting and no mass effect anywhere in the chain.
        private static Card bestSafePick(final CardCollection pool, final SpellAbility sa) {
            Card best = null;
            for (Card c : pool) {
                if (c.getManaCost() == null || c.getManaCost().getCMC() < MIN_PICK_CMC) {
                    continue;
                }
                boolean safe = true;
                for (SpellAbility csa : c.getBasicSpells()) {
                    for (SpellAbility part = csa; part != null; part = part.getSubAbility()) {
                        if (part.usesTargeting()
                                || (part.getApi() != null && part.getApi().name().endsWith("All"))) {
                            safe = false;
                            break;
                        }
                    }
                    if (!safe) {
                        break;
                    }
                }
                if (safe && (best == null || c.getManaCost().getCMC() > best.getManaCost().getCMC())) {
                    best = c;
                }
            }
            return best;
        }
    }

    // Squee's Revenge
    // "Choose a number. Flip a coin that many times or until you lose a flip. If
    // you win all the flips, draw two cards for each flip." ChooseNumberAi refuses
    // any ChooseNumber without AILogic, and the stock chooseNumber answers Max (99)
    // - a streak nobody wins, so a cast would draw nothing. Pick the count
    // ourselves: the n maximising expected cards, 2n * P(win n in a row), plus one
    // card per won flip for each "wins a coin flip -> draw" trigger we control
    // (Zndrsplt). The worst case (every flip won) never decks us, and the spell's
    // own draws never overflow the hand. Floor: n >= 1 and expected cards >= 1
    // (it replaces itself on average), cast in main 2 unless PlayMain1.
    public static class SqueesRevenge {
        public static final int LIBRARY_MARGIN = 4;
        public static final int MAX_FLIPS = 20;

        public static AiAbilityDecision consider(final Player ai, final SpellAbility sa) {
            final PhaseHandler ph = ai.getGame().getPhaseHandler();
            if (ph.getPhase().isBefore(PhaseType.MAIN2) && !ComputerUtil.castSpellInMain1(ai, sa)) {
                return new AiAbilityDecision(0, AiPlayDecision.WaitForMain2);
            }
            final Card host = sa.getHostCard();
            final int handAfter = ai.getCardsIn(ZoneType.Hand).size()
                    - (host != null && host.isInZone(ZoneType.Hand) ? 1 : 0);
            if (bestCount(ai, handAfter) < 1) {
                return new AiAbilityDecision(0, AiPlayDecision.CantPlayAi);
            }
            return new AiAbilityDecision(100, AiPlayDecision.WillPlay);
        }

        // At resolution the card is on the stack: the hand is already net of it.
        public static int chooseNumber(final Player ai, final SpellAbility sa, final int min, final int max) {
            int n = bestCount(ai, ai.getCardsIn(ZoneType.Hand).size());
            if (n < 1) {
                // Already paid for (state moved since the cast decision): one flip
                // if the library can take its worst case, otherwise nothing.
                n = (ai.canDraw() && libraryCap(ai) >= 1 && firstFlipChance(ai) > 0) ? 1 : 0;
            }
            return Math.max(min, Math.min(max, n));
        }

        static int bestCount(final Player ai, final int handAfter) {
            if (!ai.canDraw()) {
                return 0;
            }
            final double p1 = firstFlipChance(ai);
            final double p = laterFlipChance(ai);
            if (p1 <= 0) {
                return 0;
            }
            final int d = drawOnWinTriggers(ai);
            int cap = Math.min(MAX_FLIPS, libraryCap(ai));
            if (!ai.isUnlimitedHandSize()) {
                cap = Math.min(cap, (ai.getMaxHandSize() - handAfter) / 2);
            }
            int best = 0;
            double bestEv = 0, pAll = 1, wonFlips = 0;
            for (int n = 1; n <= cap; n++) {
                pAll *= (n == 1 ? p1 : p);          // P(win the first n flips)
                wonFlips += pAll;                   // E[# won flips] for a streak of n
                final double ev = 2.0 * n * pAll + d * wonFlips;
                if (ev > bestEv + 1e-9) {           // ties keep the smaller n
                    bestEv = ev;
                    best = n;
                }
            }
            return bestEv >= 1.0 - 1e-9 ? best : 0;
        }

        // Worst case (win every flip): 2n from the spell + d per flip from triggers.
        static int libraryCap(final Player ai) {
            return (ai.getCardsIn(ZoneType.Library).size() - LIBRARY_MARGIN) / (2 + drawOnWinTriggers(ai));
        }

        static double laterFlipChance(final Player ai) {
            return 1.0 - Math.pow(0.5, StaticAbilityFlipCoinMod.getFlipMultiplier(ai));
        }

        static double firstFlipChance(final Player ai) {
            final Boolean fixed = StaticAbilityFlipCoinMod.fixedResult(ai);
            return fixed == null ? laterFlipChance(ai) : (fixed ? 1.0 : 0.0);
        }

        static int drawOnWinTriggers(final Player ai) {
            int d = 0;
            for (final Card c : ai.getCardsIn(ZoneType.Battlefield)) {
                for (final Trigger t : c.getTriggers()) {
                    if (t.getMode() != TriggerType.FlippedCoin || !"Win".equals(t.getParam("ValidResult"))) {
                        continue;
                    }
                    final SpellAbility ab = t.ensureAbility();
                    if (ab != null && ab.getApi() == ApiType.Draw) {
                        d++;
                    }
                }
            }
            return d;
        }
    }

    // Dominate
    // "X 1 U U: gain control of target creature with mana value X or less" -
    // ControlGainAi never announces this X, so ValidTgts$ Creature.cmcLEX reads
    // X=0 and only mana-value-0 creatures are ever legal. Announce X ourselves
    // as the chosen creature's mana value (the cheapest legal X), checking
    // canTarget at that X per candidate. Windows: our own main phase, an
    // opponent's end step with our turn next, or an opponent's declare-attackers
    // or declare-blockers step against us (stealing an attacker removes it from
    // combat). Never in response. Floor: a creature worth a card and a half
    // (evaluateCreature >= 200), never one we own on the opponent's turn (it may
    // only be borrowed); with lethal damage incoming after blocks, only an
    // unblocked attacker whose steal leaves us alive.
    public static class StealCreatureForX {
        public static final int MIN_EVAL = 200;

        public static AiAbilityDecision consider(final Player ai, final SpellAbility sa) {
            // Routing from ControlGainAi.canPlay bypasses the base class's
            // restriction check, so mirror it here.
            if (sa.getRestrictions() != null && !sa.getRestrictions().canPlay(sa.getHostCard(), sa)) {
                return new AiAbilityDecision(0, AiPlayDecision.CantPlaySa);
            }
            sa.resetTargets();

            final Game game = ai.getGame();
            if (!game.getStack().isEmpty()) {
                return new AiAbilityDecision(0, AiPlayDecision.AnotherTime);
            }
            final PhaseHandler ph = game.getPhaseHandler();
            final Combat combat = game.getCombat();
            final boolean ownTurn = ph.isPlayerTurn(ai);
            final boolean defensive = !ownTurn && combat != null
                    && (ph.is(PhaseType.COMBAT_DECLARE_ATTACKERS) || ph.is(PhaseType.COMBAT_DECLARE_BLOCKERS))
                    && !combat.getAttackersOf(ai).isEmpty();
            final boolean oppEndStep = !ownTurn && ph.is(PhaseType.END_OF_TURN) && ai.equals(ph.getNextTurn());
            final boolean ownMain = ownTurn && ph.getPhase().isMain();
            if (!defensive && !oppEndStep && !ownMain) {
                return new AiAbilityDecision(0, AiPlayDecision.AnotherTime);
            }

            // Cheap pass first: no mana simulation and no RNG draw until a
            // candidate exists (lifeInDanger would draw on every call).
            final int remain = defensive && ph.is(PhaseType.COMBAT_DECLARE_BLOCKERS) && !ai.cantLoseForZeroOrLessLife()
                    ? ComputerUtilCombat.lifeThatWouldRemain(ai, combat) : Integer.MAX_VALUE;
            final boolean lethal = remain < 1;
            final CardCollection pool = defensive ? combat.getAttackersOf(ai) : ai.getOpponents().getCreaturesInPlay();
            final CardCollection candidates = new CardCollection();
            for (Card c : pool) {
                if (!c.canBeControlledBy(ai) || c.hasSVar("EndOfTurnLeavePlay")
                        || ComputerUtilCard.isCardRemAIDeck(c) || c.hasKeyword(Keyword.WARD)) {
                    continue;
                }
                if (lethal) {
                    // Only a steal that turns lethal into survivable; when none
                    // exists the steal cannot change the outcome, keep the card.
                    if (!combat.isBlocked(c)
                            && remain + ComputerUtilCombat.damageIfUnblocked(c, ai, combat, false) >= 1) {
                        candidates.add(c);
                    }
                    continue;
                }
                // Threaten / Act of Aggression on our own creature: it comes
                // back at cleanup anyway.
                if (!ownTurn && ai.equals(c.getOwner())) {
                    continue;
                }
                if (ComputerUtilCard.evaluateCreature(c) >= MIN_EVAL) {
                    candidates.add(c);
                }
            }
            if (candidates.isEmpty()) {
                return new AiAbilityDecision(0, AiPlayDecision.CantPlayAi);
            }

            sa.setXManaCostPaid(null);
            final int maxX = ComputerUtilCost.setMaxXValue(sa, ai, false); // X = 0 is legal (tokens)
            Card best = null;
            int bestEval = Integer.MIN_VALUE;
            for (Card c : candidates) {
                final int mv = c.getCMC();
                if (mv > maxX) {
                    continue;
                }
                sa.setXManaCostPaid(mv);
                if (!sa.canTarget(c)) {
                    continue;
                }
                final int eval = ComputerUtilCard.evaluateCreature(c);
                if (eval > bestEval) {
                    best = c;
                    bestEval = eval;
                }
            }
            if (best == null) {
                sa.setXManaCostPaid(null);
                return new AiAbilityDecision(0, AiPlayDecision.CantPlayAi);
            }

            sa.setXManaCostPaid(best.getCMC());
            sa.getTargets().add(best);
            if (!sa.isTargetNumberValid()) {
                sa.resetTargets();
                sa.setXManaCostPaid(null);
                return new AiAbilityDecision(0, AiPlayDecision.TargetingFailed);
            }
            return new AiAbilityDecision(100, AiPlayDecision.WillPlay);
        }
    }

    // Steward of the Harvest
    // Its ETB exiles up to three land cards from our own graveyard, and our
    // creatures gain every activated ability of the exiled lands. The generic
    // graveyard-exile targeting aims only at opponents' cards
    // (ChangeZoneAi.isPreferredTarget without AITgtOwnCards), so the trigger
    // read as unrunnable and checkETBEffects vetoed every cast.
    // Floor: exile only lands whose every battlefield activated ability is a
    // mana ability paid with nothing but {T} and mana, and that have at least
    // one mana ability the AI's payment code can use. A granted "{T}, Sacrifice
    // this: search" fetch (ChangeZoneAi.willPayCosts skips checkSacrificeCost
    // for Destination$ Battlefield) or "Sacrifice a creature: Add {B}{B}" would
    // spend our own creatures; a {1},{T} filter land is skipped by
    // ComputerUtilMana.getAIPlayableMana and grants nothing. Greedy color
    // cover: after the first pick a land is added only for a color the chosen
    // lands do not make yet, so graveyard lands the deck spends (Loam, Multani,
    // delve) are not exiled for nothing. No RNG: stable sort, graveyard order.
    public static class StewardOfTheHarvest {
        public static boolean chooseTargets(final Player ai, final SpellAbility sa, final boolean mandatory) {
            sa.resetTargets();
            final Map<Card, Integer> colors = new HashMap<>();
            final List<Card> lands = new ArrayList<>();
            for (Card c : CardLists.getTargetableCards(ai.getCardsIn(ZoneType.Graveyard), sa)) {
                if (c.isLand() && grantsOnlySafeManaAbilities(c)) {
                    colors.put(c, colorMask(c));
                    lands.add(c);
                }
            }
            // most colors first; List.sort is stable, ties keep graveyard order
            lands.sort((a, b) -> Integer.compare(Integer.bitCount(colors.get(b)), Integer.bitCount(colors.get(a))));

            final Set<String> names = new HashSet<>();
            int covered = 0;
            for (Card c : lands) {
                if (!sa.canAddMoreTarget()) {
                    break;
                }
                final int mask = colors.get(c);
                if (names.contains(c.getName()) || (!sa.getTargets().isEmpty() && (mask & ~covered) == 0)) {
                    continue;
                }
                if (!sa.canTarget(c)) {
                    continue;
                }
                sa.getTargets().add(c);
                names.add(c.getName());
                covered |= mask;
            }
            // mandatory (the real trigger): "up to three", zero targets is legal
            return mandatory || !sa.getTargets().isEmpty();
        }

        private static boolean grantsOnlySafeManaAbilities(final Card land) {
            for (SpellAbility ab : land.getSpellAbilities()) {
                if (!ab.isActivatedAbility()) {
                    continue; // play-land; GainsAbilitiesOf copies activated abilities only
                }
                final ZoneType zone = ab.getRestrictions() == null ? null : ab.getRestrictions().getZone();
                if (zone != null && zone != ZoneType.Battlefield) {
                    continue; // cycling, graveyard abilities: a creature never uses them
                }
                if (!ab.isManaAbility() || ab.getPayCosts() == null) {
                    return false; // fetch, animate, draw/discard, sacrifice-for-value
                }
                for (CostPart part : ab.getPayCosts().getCostParts()) {
                    if (!(part instanceof CostTap) && !(part instanceof CostPartMana)) {
                        return false; // any sacrifice, life, exile, discard or return cost
                    }
                }
            }
            return !ComputerUtilMana.getAIPlayableMana(land).isEmpty();
        }

        private static int colorMask(final Card land) {
            int mask = 0;
            for (SpellAbility ma : ComputerUtilMana.getAIPlayableMana(land)) {
                for (byte color : MagicColor.WUBRG) {
                    if (ma.canProduce(MagicColor.toShortString(color))) {
                        mask |= color;
                    }
                }
            }
            return mask;
        }
    }

    // The Mimeoplasm
    // Its optional "as it enters" copy effect is a ChooseCard chain whose copy
    // chooser picks from the two cards the first chooser exiles mid-resolution.
    // Before the cast (and at the Optional replacement's accept-or-decline) no
    // card is remembered yet, so the stock "Clone" scan finds nothing and the
    // permanent is vetoed at AiController.checkETBEffects (BadEtbEffects). One
    // plan drives the cast floor, both ETB answers and the real picks: copy the
    // graveyard creature card worth the most as a copy, fed by the highest-power
    // other card's +1/+1 counters. Draws no random numbers; its own trigger scan
    // reads Execute text rather than calling Trigger.ensureAbility (the stock
    // evaluateCreature still builds a graveyard card's upkeep trigger).
    public static class TheMimeoplasm {
        public static final String NAME = "The Mimeoplasm";
        // CreatureEvaluator prices +1 power at 15 and +1 toughness at 10.
        private static final int COUNTER_VALUE = 25;
        // Pre-cast floor, evaluateCreature units: about a vanilla 4/4 for five
        // (2/2 cmc2 copy + 2 counters = 210; 1/1 cmc1 + 1 = 155; 3/3 cmc3 + 1 = 215).
        public static final int CAST_FLOOR = 200;

        private static CardCollection pool(final Player ai) {
            return CardLists.filter(ai.getGame().getCardsIn(ZoneType.Graveyard), CardPredicates.CREATURES);
        }

        // Can this card be the copy at all?
        private static boolean copyable(final Player ai, final Card c) {
            if (c.getType().isLegendary() && ai.isCardInPlay(c.getName())) {
                return false; // legend rule would bin one of them
            }
            if (c.hasSVar("EndOfTurnLeavePlay")) {
                return false; // cast after combat (PermanentAi waits for Main 2), sacrificed unused
            }
            for (ReplacementEffect re : c.getReplacementEffects()) {
                if (re.getLayer() == ReplacementLayer.Copy) {
                    return false; // clone-of-a-clone: see CloneAi's runaway guard
                }
            }
            for (Trigger t : c.getTriggers()) {
                // A copied enters trigger sees OUR cast (from hand, mana spent):
                // Deathbringer Regent's wrath would hit our own board.
                if (t.getMode() == TriggerType.ChangesZone && "Battlefield".equals(t.getParam("Destination"))) {
                    final String api = rootApi(t);
                    if ("DestroyAll".equals(api) || "SacrificeAll".equals(api)) {
                        return false;
                    }
                }
            }
            return true;
        }

        // The trigger's root api, read from its Execute text when it is not built yet.
        private static String rootApi(final Trigger t) {
            final SpellAbility built = t.getOverridingAbility();
            if (built != null) {
                return built.getApi() == null ? null : built.getApi().name();
            }
            final String text = t.hasParam("Execute") ? t.getSVar(t.getParam("Execute")) : "";
            if (text.isEmpty()) {
                return null;
            }
            final Map<String, String> params = FileSection.parseToMap(text, FileSection.DOLLAR_SIGN_KV_SEPARATOR);
            final String api = params.get("DB");
            if (api != null) {
                return api;
            }
            return params.containsKey("AB") ? params.get("AB") : params.get("SP");
        }

        // Value of entering as `copy` with `donor`'s power in +1/+1 counters; MIN_VALUE = unsafe.
        public static int outcome(final Player ai, final Card copy, final Card donor) {
            if (copy == null || donor == null || copy.equals(donor) || !copyable(ai, copy)) {
                return Integer.MIN_VALUE;
            }
            final int counters = Math.max(0, donor.getNetPower());
            if (copy.getNetToughness() + counters <= 0) {
                return Integer.MIN_VALUE; // dies to state-based actions
            }
            return ComputerUtilCard.evaluateCreature(copy) + counters * COUNTER_VALUE;
        }

        // Best plan over every graveyard: {copy, donor}, or null when no copy survives.
        // The best donor for a copy is the highest-power other card (it maximises both
        // value and survival), so this is O(n). Ties keep graveyard iteration order.
        public static Pair<Card, Card> plan(final Player ai) {
            final CardCollection pool = pool(ai);
            if (pool.size() < 2) {
                return null;
            }
            Card p1 = null, p2 = null; // the two highest powers
            for (Card c : pool) {
                if (p1 == null || c.getNetPower() > p1.getNetPower()) {
                    p2 = p1;
                    p1 = c;
                } else if (p2 == null || c.getNetPower() > p2.getNetPower()) {
                    p2 = c;
                }
            }
            Pair<Card, Card> best = null;
            int bestVal = Integer.MIN_VALUE;
            for (Card c : pool) {
                final Card donor = c.equals(p1) ? p2 : p1;
                final int v = outcome(ai, c, donor);
                if (v > bestVal) {
                    bestVal = v;
                    best = Pair.of(c, donor);
                }
            }
            return best;
        }

        public static int planValue(final Player ai) {
            final Pair<Card, Card> p = plan(ai);
            return p == null ? Integer.MIN_VALUE : outcome(ai, p.getLeft(), p.getRight());
        }

        // ChooseCardAi.checkAiLogic: the pre-cast ETB check AND the Optional-replacement confirm.
        public static boolean considerChoice(final Player ai, final SpellAbility sa) {
            if ("Exile".equals(sa.getParam("ChoiceZone"))) {
                return true; // copy chooser: its choices are the parent's exiled pair, which do not exist yet
            }
            return plan(ai) != null; // any surviving copy beats declining (a dead 0/0)
        }

        // PermanentCreatureAi.checkApiLogic: the value floor, pre-cast only.
        public static boolean worthCasting(final Player ai) {
            return planValue(ai) >= CAST_FLOOR;
        }

        // ChooseCardAi.chooseSingleCard: make resolution execute the judged plan.
        public static Card chooseCard(final Player ai, final SpellAbility sa, final Iterable<Card> options) {
            final CardCollection opts = new CardCollection(options);
            if (opts.isEmpty()) {
                return null;
            }
            if ("Exile".equals(sa.getParam("ChoiceZone"))) {
                if (opts.size() == 2) {
                    final Card a = opts.get(0), b = opts.get(1);
                    return outcome(ai, a, b) >= outcome(ai, b, a) ? a : b;
                }
                return ComputerUtilCard.getBestCreatureAI(opts);
            }
            // Graveyard picks: nothing moves between the two picks (the exile happens
            // later), so the plan recomputes identically; take whichever planned card
            // is still offered.
            final Pair<Card, Card> p = plan(ai);
            if (p != null) {
                if (opts.contains(p.getLeft())) {
                    return p.getLeft();
                }
                if (opts.contains(p.getRight())) {
                    return p.getRight();
                }
            }
            return ComputerUtilCard.getBestCreatureAI(opts);
        }
    }

    // The One Ring
    public static class TheOneRing {
        public static AiAbilityDecision consider(final Player ai, final SpellAbility sa) {
            if (!ai.canLoseLife() || ai.cantLoseForZeroOrLessLife()) {
                return new AiAbilityDecision(100, AiPlayDecision.WillPlay);
            }

            AiController aic = ((PlayerControllerAi) ai.getController()).getAi();
            int lifeInDanger = aic.getIntProperty(AiProps.AI_IN_DANGER_THRESHOLD);
            int numCtrs = sa.getHostCard().getCounters(CounterType.getType("BURDEN"));

            if (ai.getLife() > numCtrs + 1 && ai.getLife() > lifeInDanger
                    && ai.getMaxHandSize() >= ai.getCardsIn(ZoneType.Hand).size() + numCtrs + 1) {
                return new AiAbilityDecision(100, AiPlayDecision.WillPlay);
            }

            return new AiAbilityDecision(0, AiPlayDecision.LifeInDanger);
        }
    }

    // The Scarab God
    public static class TheScarabGod {
        public static AiAbilityDecision consider(final Player ai, final SpellAbility sa) {
            Card bestOppCreat = ComputerUtilCard.getBestAI(CardLists.filter(ai.getOpponents().getCardsIn(ZoneType.Graveyard), CardPredicates.CREATURES));
            Card worstOwnCreat = ComputerUtilCard.getWorstAI(CardLists.filter(ai.getCardsIn(ZoneType.Graveyard), CardPredicates.CREATURES));

            sa.resetTargets();
            if (bestOppCreat != null) {
                sa.getTargets().add(bestOppCreat);
            } else if (worstOwnCreat != null) {
                sa.getTargets().add(worstOwnCreat);
            }

            if (!sa.getTargets().isEmpty()) {
                // If we have a target, we can play this ability
                return new AiAbilityDecision(100, AiPlayDecision.WillPlay);
            } else {
                // No valid targets, can't play this ability
                return new AiAbilityDecision(0, AiPlayDecision.TargetingFailed);
            }
        }
    }

    // Thief of Blood
    // "As it enters, remove all counters from all permanents; it enters with a
    // +1/+1 counter for each counter removed." Symmetric and mandatory, so the
    // floor is a ledger over every permanent's removable counters: an
    // opponent's useful counters and our own harmful ones are gain; our own
    // useful counters and an opponent's harmful ones are loss. Hard vetoes: the
    // strip would kill one of our permanents (a planeswalker's loyalty, a
    // creature whose toughness - or survival of damage already marked - is its
    // counters), unlock an opponent's Dark Depths, or defeat an opponent's
    // battle (they cast its back face for free). Otherwise only when the gain
    // clears a floor and beats the loss. Reads the board only: no random draws.
    public static class ThiefOfBlood {
        public static final int MIN_GAIN = 3;    // at least a 4/4 flier for six, bought by stripping the opponent
        public static final int KILL_BONUS = 3;  // an opposing permanent that dies to the strip

        public static AiAbilityDecision consider(final Player ai, final SpellAbility sa) {
            final Card host = sa.getHostCard();
            int gain = 0;
            int loss = 0;
            for (final Card c : ai.getGame().getCardsIn(ZoneType.Battlefield)) {
                if (c.equals(host) || !c.hasCounters()) {
                    continue;
                }
                final boolean ours = !c.getController().isOpponentOf(ai);
                boolean strips = false;
                for (final CounterType ct : c.getCounters().elementSet()) {
                    if (!c.canRemoveCounters(ct)) {
                        continue;
                    }
                    strips = true;
                    if (ct.is(CounterEnumType.DEFENSE) && c.isBattle()) {
                        continue; // a battle's defense is judged as a defeat below, never counted
                    }
                    final CounterAiCategory cat = ComputerUtil.getCounterCategory(ct, c);
                    if (cat == CounterAiCategory.Neutral || ct.is(CounterEnumType.LORE)) {
                        continue; // no effect / a stripped saga just replays its chapters
                    }
                    final boolean harmful = cat == CounterAiCategory.Negative;
                    if (ours == harmful) {
                        gain += c.getCounters(ct);
                    } else {
                        loss += c.getCounters(ct);
                    }
                }
                if (!ours && c.isBattle() && c.getCounters(CounterEnumType.DEFENSE) > 0
                        && c.canRemoveCounters(CounterEnumType.DEFENSE)) {
                    // stripping every defense counter defeats it for them: its back face, cast free
                    // (our own battle's defeat benefits us, but stays out of the ledger)
                    return new AiAbilityDecision(0, AiPlayDecision.CantPlayAi);
                }
                if (!ours && "Dark Depths".equals(c.getName()) && c.getCounters(CounterEnumType.ICE) > 0
                        && c.canRemoveCounters(CounterEnumType.ICE)) {
                    return new AiAbilityDecision(0, AiPlayDecision.CantPlayAi); // hands them Marit Lage
                }
                if (!strips) {
                    continue;
                }
                final int toughAfter = c.getNetToughness() - c.getToughnessBonusFromCounters();
                final boolean dies = (c.isPlaneswalker() && c.getCurrentLoyalty() > 0
                            && c.canRemoveCounters(CounterEnumType.LOYALTY))
                        || (c.isCreature() && (toughAfter <= 0
                            || (c.getDamage() > 0 && c.getDamage() >= toughAfter && !c.hasKeyword(Keyword.INDESTRUCTIBLE))));
                if (dies) {
                    if (ours) {
                        return new AiAbilityDecision(0, AiPlayDecision.CantPlayAi);
                    }
                    gain += KILL_BONUS;
                }
            }
            if (gain < MIN_GAIN || gain <= loss) {
                return new AiAbilityDecision(0, AiPlayDecision.CantPlayAi);
            }
            return new AiAbilityDecision(100, AiPlayDecision.WillPlay);
        }
    }

    // Time Lord Regeneration
    // "Until end of turn, target Time Lord you control gains 'When this creature
    // dies, reveal cards until you reveal a Time Lord creature card and put it onto
    // the battlefield.'" The grant is worth something only on a Time Lord that is
    // about to die, so the card is never cast proactively - only in two windows
    // where the AI's own death predictors (the ones RegenerateAi relies on) say one
    // of our Time Lords dies:
    // - in response to an opponent's stack top that kills it (destroy, lethal
    //   damage or -X/-X, per predictThreatenedObjects with no saviour), when
    //   nothing in that chain would exile, bounce, steal or attach to it, or
    //   replace the death (the trigger needs a real "dies");
    // - declare blockers with an empty stack, when combatantWouldBeDestroyed says
    //   one of our Time Lords in combat dies and combatantCantBeDestroyed does not
    //   save it (a chump block or a losing trade becomes a free Time Lord).
    // A Time Lord whose own replacement effects send it anywhere but the graveyard
    // (The Eighth Doctor's grant on graveyard-cast permanents) never "dies" and is
    // never a candidate. Floor: a Time Lord creature card must remain in our
    // library (the AI knows its own decklist), checked last because it scans the
    // whole library. Worst case: {U} and the card, never board loss.
    public static class TimeLordRegeneration {
        public static AiAbilityDecision consider(final Player ai, final SpellAbility sa) {
            final Game game = ai.getGame();

            // Routing through AnimateAi.canPlay's name gate bypasses the base
            // class's restriction check, so mirror it here.
            if (sa.getRestrictions() != null && !sa.getRestrictions().canPlay(sa.getHostCard(), sa)) {
                return new AiAbilityDecision(0, AiPlayDecision.CantPlaySa);
            }

            sa.resetTargets();
            final CardCollection ours = CardLists.filter(
                    CardLists.getTargetableCards(ai.getCardsIn(ZoneType.Battlefield), sa),
                    CardPredicates.CREATURES.and(c -> !deathIsReplaced(c)));
            if (ours.isEmpty()) {
                return new AiAbilityDecision(0, AiPlayDecision.TargetingFailed);
            }

            final CardCollection dying = new CardCollection();
            if (!game.getStack().isEmpty()) {
                final SpellAbility top = game.getStack().peekAbility();
                if (top == null || top.getActivatingPlayer() == null
                        || ai.equals(top.getActivatingPlayer()) || !killsWithoutReplacing(top)) {
                    return new AiAbilityDecision(0, AiPlayDecision.CantPlayAi);
                }
                // A null saviour opens every threat branch; an Animate-typed saviour
                // would skip the destroy and -X/-X branches.
                final List<GameObject> threatened = ComputerUtil.predictThreatenedObjects(ai, null, true);
                for (final Card c : ours) {
                    if (threatened.contains(c)) {
                        dying.add(c);
                    }
                }
            } else if (game.getPhaseHandler().is(PhaseType.COMBAT_DECLARE_BLOCKERS) && game.getCombat() != null) {
                final Combat combat = game.getCombat();
                for (final Card c : ours) {
                    // combatantWouldBeDestroyed first: it returns at once for a creature
                    // outside combat, so the regeneration scan in combatantCantBeDestroyed
                    // only runs for our combatants. combatantCantBeDestroyed covers what
                    // the damage totals ignore (indestructible, shield counters,
                    // regeneration shields and abilities).
                    if (ComputerUtilCombat.combatantWouldBeDestroyed(ai, c, combat)
                            && !ComputerUtilCombat.combatantCantBeDestroyed(ai, c)) {
                        dying.add(c);
                    }
                }
            }
            if (dying.isEmpty()) {
                return new AiAbilityDecision(0, AiPlayDecision.CantPlayAi);
            }

            if (!IterableUtil.any(ai.getCardsIn(ZoneType.Library),
                    c -> c.isCreature() && c.getType().hasCreatureType("Time Lord"))) {
                return new AiAbilityDecision(0, AiPlayDecision.MissingNeededCards);
            }

            sa.getTargets().add(ComputerUtilCard.getBestCreatureAI(dying));
            return new AiAbilityDecision(100, AiPlayDecision.WillPlay);
        }

        // predictThreatenedObjects(ai, null, ...) unions every threat branch of the
        // chain. Reject any chain that could remove our Time Lord WITHOUT it dying
        // (exile / bounce / steal / attach) or that replaces the death, so whatever
        // it still flags came from a destroy, lethal-damage or -X/-X branch.
        private static boolean killsWithoutReplacing(final SpellAbility top) {
            for (SpellAbility part = top; part != null; part = part.getSubAbility()) {
                if (part.hasParam("ReplaceDyingDefined") || part.hasParam("ReplaceDyingValid")) {
                    return false;
                }
                final ApiType api = part.getApi();
                if (api == ApiType.GainControl || api == ApiType.Attach) {
                    return false;
                }
                if ((api == ApiType.ChangeZone || api == ApiType.ChangeZoneAll)
                        && ("Exile".equals(part.getParam("Destination"))
                            || part.getParamOrDefault("Origin", "").contains("Battlefield"))) {
                    return false;
                }
                if (api == ApiType.Effect && part.hasParam("ReplacementEffects")) {
                    return false;
                }
            }
            return true;
        }

        // A card-local "moved from the battlefield (or from anywhere) -> somewhere
        // other than the graveyard" replacement means the creature never "dies".
        private static boolean deathIsReplaced(final Card c) {
            for (final ReplacementEffect re : c.getReplacementEffects()) {
                if (re.getMode() == ReplacementType.Moved
                        && (!re.hasParam("Origin") || re.getParam("Origin").contains("Battlefield"))
                        && (!re.hasParam("Destination") || re.getParam("Destination").contains("Graveyard"))) {
                    return true;
                }
            }
            return false;
        }
    }

    // Timetwister
    public static class Timetwister {
        public static AiAbilityDecision consider(final Player ai, final SpellAbility sa) {
            final int aiHandSize = ai.getCardsIn(ZoneType.Hand).size();
            int maxOppHandSize = 0;

            final int HAND_SIZE_THRESHOLD = 3;

            for (Player p : ai.getOpponents()) {
                int handSize = p.getCardsIn(ZoneType.Hand).size();
                if (handSize > maxOppHandSize) {
                    maxOppHandSize = handSize;
                }
            }

            // use in case we're getting low on cards or if we're significantly behind our opponent in cards in hand
            if (aiHandSize < HAND_SIZE_THRESHOLD || maxOppHandSize - aiHandSize > HAND_SIZE_THRESHOLD) {
                // if the AI has less than 3 cards in hand or the opponent has more than 3 cards in hand than the AI
                // then the AI is willing to play this ability
                return new AiAbilityDecision(100, AiPlayDecision.WillPlay);
            } else {
                // otherwise, don't play this ability
                return new AiAbilityDecision(0, AiPlayDecision.CantPlayAi);
            }
        }
    }

    // Timmerian Fiends
    public static class TimmerianFiends {
        public static boolean consider(final Player ai, final SpellAbility sa) {
            final Card targeted = sa.getParentTargetingCard().getTargetCard();
            if (targeted == null) {
                return false;
            }

            if (targeted.isCreature()) {
                if (ComputerUtil.aiLifeInDanger(ai, true, 0)) {
                    return true; // do it, hoping to save a valuable potential blocker etc.
                }
                return ComputerUtilCard.evaluateCreature(targeted) >= 200; // might need tweaking
            } else {
                // TODO: this currently compares purely by CMC. To be somehow improved, especially for stuff like the Power Nine etc.
                return ComputerUtilCard.evaluatePermanentList(new CardCollection(targeted)) >= 3;
            }
        }
    }

    // Trade Secrets
    // Target opponent draws two, we draw up to four, and the OPPONENT chooses how often to repeat.
    // Forge's chooser for that (AiController "RepeatDraw") picks (maxHand - hand + rand{0..2}) / 2
    // from the hand the opponent holds AFTER the root's two cards; ours ("OptionalDraw") stops at
    // maxHand + 2. So it is a refill: cast only when the first four fit under our maximum hand size
    // and the fullest-handed targetable opponent already holds at least HAND_GAP more cards than we
    // do (their refill is small, ours is the full four: never behind on cards in any branch of
    // their roll), our library survives the most we could draw across every repeat they pick, and
    // no draw thief or draw punisher at the table sees the draws. An opponent the first two cards
    // deck is worth it whatever the hands hold.
    public static class TradeSecrets {
        public static final int DRAW = 4;           // our draw per pass
        public static final int OPP_DRAW = 2;       // the target's draw per pass
        public static final int HAND_GAP = 3;       // target's hand minus ours (the spell excluded)
        public static final int LIBRARY_MARGIN = 3; // cards left after the most we can draw

        public static AiAbilityDecision consider(final Player ai, final SpellAbility sa) {
            final Game game = ai.getGame();
            final Card host = sa.getHostCard();
            sa.resetTargets();

            // draw-limit statics (Narset, Parter of Veils) count the draws already made this turn
            if (!ai.canDraw() || StaticAbilityCantDraw.canDrawAmount(ai, DRAW) < DRAW) {
                return new AiAbilityDecision(0, AiPlayDecision.CantPlayAi);
            }

            int hand = ai.getCardsIn(ZoneType.Hand).size();
            if (host != null && host.isInZone(ZoneType.Hand)) {
                hand--; // the spell itself is spent
            }
            final int maxHand = ai.getMaxHandSize();

            // OptionalDraw caps our hand at maxHand + 2 (and at the library size) over every repeat
            // the opponent picks; never let that empty our library
            final int mostWeDraw = Math.max(DRAW, maxHand + 2 - hand);
            if (ai.getCardsIn(ZoneType.Library).size() < mostWeDraw + LIBRARY_MARGIN) {
                return new AiAbilityDecision(0, AiPlayDecision.CantPlayAi);
            }

            if (tableStealsOrPunishesDraws(ai, game)) {
                return new AiAbilityDecision(0, AiPlayDecision.CantPlayAi);
            }

            Player best = null;
            boolean decksThem = false;
            for (final Player opp : ai.getOpponents()) {
                if (!sa.canTarget(opp) || opp.isCardInPlay("Laboratory Maniac")) {
                    continue;
                }
                if (opp.canDraw() && !opp.cantLoseCheck(forge.game.player.GameLossReason.Milled)
                        && opp.getCardsIn(ZoneType.Library).size() < OPP_DRAW) {
                    best = opp; // the first pass decks them
                    decksThem = true;
                    break;
                }
                // fullest hand = fewest repeats = smallest refill; game order on ties
                if (best == null || opp.getCardsIn(ZoneType.Hand).size() > best.getCardsIn(ZoneType.Hand).size()) {
                    best = opp;
                }
            }
            if (best == null) {
                return new AiAbilityDecision(0, AiPlayDecision.TargetingFailed);
            }

            if (!decksThem) {
                // the full first four without a cleanup discard (the stock own-turn draw floor)
                if (!ai.isUnlimitedHandSize() && hand + DRAW > maxHand) {
                    return new AiAbilityDecision(0, AiPlayDecision.CantPlayAi);
                }
                // we are low and they are near full: their refill is smaller than ours
                if (best.getCardsIn(ZoneType.Hand).size() < hand + HAND_GAP) {
                    return new AiAbilityDecision(0, AiPlayDecision.CantPlayAi);
                }
            }

            sa.getTargets().add(best);
            return new AiAbilityDecision(100, AiPlayDecision.WillPlay);
        }

        // Draw thieves and draw punishers on the battlefield or in the Command zone (an emblem),
        // counted only where they work (zonesCheck: a commander waiting in the Command zone does
        // not count). Any opponent's Drawn trigger or Draw replacement is a veto: every card either
        // of us draws can feed it. One of ours (or an emblem we own) is a veto when it is symmetric:
        // a Drawn trigger whose ValidCard and ValidPlayer name no side (Spiteful Visions, Phyrexian
        // Tyranny, Ob Nixilis Reignited's emblem), or a Draw replacement not limited to opponents
        // (Chains of Mephistopheles, Uba Mask). Our own payoffs still pass: Psychosis Crawler
        // (Card.YouOwn), Consecrated Sphinx (Card.OppOwn), Notion Thief (ValidPlayer$ Opponent).
        private static boolean tableStealsOrPunishesDraws(final Player ai, final Game game) {
            for (final Card c : game.getCardsIn(Arrays.asList(ZoneType.Battlefield, ZoneType.Command))) {
                final Player controller = c.getController();
                final boolean theirs = controller != null && controller.isOpponentOf(ai);
                for (final Trigger t : c.getTriggers()) {
                    if (t.getMode() == TriggerType.Drawn && t.zonesCheck(game.getZoneOf(c))
                            && (theirs || (!namesASide(t.getParam("ValidCard")) && !namesASide(t.getParam("ValidPlayer"))))) {
                        return true;
                    }
                }
                for (final ReplacementEffect re : c.getReplacementEffects()) {
                    if ((re.getMode() == ReplacementType.Draw || re.getMode() == ReplacementType.DrawCards)
                            && re.zonesCheck(game.getZoneOf(c))) {
                        final String validPlayer = re.getParam("ValidPlayer");
                        if (theirs || validPlayer == null || !validPlayer.contains("Opp")) {
                            return true;
                        }
                    }
                }
            }
            return false;
        }

        private static boolean namesASide(final String valid) {
            return valid != null && (valid.contains("You") || valid.contains("Opp"));
        }
    }

    // Unfinished Business
    // Return a creature card from our graveyard, then up to two Aura and/or
    // Equipment cards from our graveyard attached to it. The generic ChangeZone
    // targeting cannot read the sub's AttachedTo$ ParentTarget (it tests the
    // Defined word as a card type, so every attachment is filtered out) and
    // refuses "up to two" with fewer than two picks, so the whole package is
    // chosen here. Floor: a real body (evaluateCreature >= 140 after static
    // P/T, not ETB-prevented, no legend-rule collision); attachments only if
    // they help it (Equipment, or an Aura with Pump attach logic), can legally
    // attach to it once it is on the battlefield, and do not cut its toughness
    // to zero (Skullclamp on an X/1); and at least five mana of returned
    // permanents, the spell's own mana value. Zero attachments is legal
    // (TargetMin$ 0), so a big enough body goes back alone.
    public static class UnfinishedBusiness {
        public static final int MIN_BODY_EVAL = 140; // above a vanilla 1/1
        public static final int MIN_RETURNED_MV = 5;
        public static final int MAX_ATTACHMENTS = 2;

        public static AiAbilityDecision consider(final Player ai, final SpellAbility sa) {
            final AbilitySub attach = sa.getSubAbility();
            if (!sa.usesTargeting() || attach == null || attach.getApi() != ApiType.ChangeZone
                    || !attach.usesTargeting()) {
                return new AiAbilityDecision(0, AiPlayDecision.CantPlayAi);
            }
            final Card source = sa.getHostCard();
            final CardCollectionView graveyard = ai.getCardsIn(ZoneType.Graveyard);

            // Which attachments help does not depend on the creature: collect
            // them once, highest mana value first (a stable sort).
            final List<Card> helpful = new ArrayList<>();
            for (Card a : CardLists.getTargetableCards(graveyard, attach)) {
                if (a.getOwner().equals(ai) && helps(a)) {
                    helpful.add(a);
                }
            }
            helpful.sort((x, y) -> Integer.compare(y.getCMC(), x.getCMC()));
            int maxAttachMV = 0;
            for (int i = 0; i < Math.min(MAX_ATTACHMENTS, helpful.size()); i++) {
                maxAttachMV += helpful.get(i).getCMC();
            }

            Card bestCreature = null;
            List<Card> bestPicks = null;
            int bestScore = Integer.MIN_VALUE;
            for (Card c : CardLists.getTargetableCards(graveyard, sa)) {
                if (c.equals(source) || !c.getOwner().equals(ai)) {
                    continue;
                }
                // cheap exits before any LKI copy is built
                if (c.getCMC() + maxAttachMV < MIN_RETURNED_MV) {
                    continue;
                }
                if (ComputerUtil.isETBprevented(c) || (!c.ignoreLegendRule() && ai.isCardInPlay(c.getName()))) {
                    continue;
                }

                // judge it as it would be on the battlefield: canBeAttached
                // refuses any creature that is not in play
                final Card lki = CardCopyService.getLKICopy(c);
                lki.setLastKnownZone(ai.getZone(ZoneType.Battlefield));
                ComputerUtilCard.applyStaticContPT(c.getGame(), lki, null);
                if (lki.getNetToughness() <= 0) {
                    continue;
                }
                final int body = ComputerUtilCard.evaluateCreature(lki);
                if (body < MIN_BODY_EVAL) {
                    continue;
                }

                final List<Card> picks = new ArrayList<>();
                int mv = c.getCMC();
                for (Card a : helpful) {
                    if (picks.size() >= MAX_ATTACHMENTS) {
                        break;
                    }
                    if (!killsOnArrival(a, lki) && lki.canBeAttached(a, attach)) {
                        picks.add(a);
                        mv += a.getCMC();
                    }
                }
                if (mv < MIN_RETURNED_MV) {
                    continue;
                }
                final int score = body + 40 * picks.size() + 10 * mv;
                if (score > bestScore) {
                    bestScore = score;
                    bestCreature = c;
                    bestPicks = picks;
                }
            }

            if (bestCreature == null) {
                attach.resetTargets();
                return new AiAbilityDecision(0, AiPlayDecision.CantPlayAi);
            }

            sa.resetTargets();
            attach.resetTargets();
            if (!sa.canTarget(bestCreature)) {
                return new AiAbilityDecision(0, AiPlayDecision.TargetingFailed);
            }
            sa.getTargets().add(bestCreature);
            for (Card a : bestPicks) {
                if (attach.canAddMoreTarget() && attach.canTarget(a)) {
                    attach.getTargets().add(a);
                }
            }
            return new AiAbilityDecision(100, AiPlayDecision.WillPlay);
        }

        // Equipment always helps the creature it is attached to; an Aura only
        // when its attach logic is Pump (the stock "good aura" signal in
        // ChangeZoneAi.isPreferredTarget), so curses stay in the graveyard.
        private static boolean helps(final Card a) {
            if (a.isEquipment()) {
                return true;
            }
            if (!a.isAura()) {
                return false;
            }
            if ("Pump".equals(a.getSVar("AttachAILogic"))) {
                return true;
            }
            for (SpellAbility s : a.getSpellAbilities()) {
                if (s.getApi() == ApiType.Attach && "Pump".equals(s.getParam("AILogic"))) {
                    return true;
                }
            }
            return false;
        }

        // A literal toughness cut (AddToughness$ -N) the creature cannot
        // survive. This reads the LKI's toughness without ETB counters, so it
        // can skip a survivable pick but never admits a lethal one.
        private static boolean killsOnArrival(final Card a, final Card lki) {
            for (StaticAbility st : a.getStaticAbilities()) {
                if (!st.checkMode(forge.game.staticability.StaticAbilityMode.Continuous)) {
                    continue;
                }
                final String t = st.getParam("AddToughness");
                if (t != null && t.matches("-\\d{1,4}") && lki.getNetToughness() <= Integer.parseInt(t.substring(1))) {
                    return true;
                }
            }
            return false;
        }
    }

    // Veil of Summer
    public static class VeilOfSummer {
        public static boolean consider(final Player ai, final SpellAbility sa) {
            // check the top ability on stack if it's (a) an opponent's counterspell targeting the AI's spell;
            // (b) a black or a blue spell targeting something that belongs to the AI
            Game game = ai.getGame();
            if (game.getStack().isEmpty()) {
                return false;
            }

            SpellAbility topSA = game.getStack().peekAbility();
            if (topSA.usesTargeting() && topSA.getActivatingPlayer().isOpponentOf(ai)) {
                if (topSA.getApi() == ApiType.Counter) {
                    SpellAbility tgtSpell = topSA.getTargets().getFirstTargetedSpell();
                    if (tgtSpell != null && tgtSpell.getActivatingPlayer().equals(ai)) {
                        return true;
                    }
                } else if (topSA.getHostCard().isBlack() || topSA.getHostCard().isBlue()) {
                    for (Player tgtP : topSA.getTargets().getTargetPlayers()) {
                        if (tgtP.equals(ai)) {
                            return true;
                        }
                    }
                    for (Card tgtC : topSA.getTargets().getTargetCards()) {
                        if (tgtC.getController().equals(ai)) {
                            return true;
                        }
                    }
                }
            }
            return false;
        }
    }


    // Volrath's Shapeshifter
    public static class VolrathsShapeshifter {
        public static AiAbilityDecision consider(final Player ai, final SpellAbility sa) {
            PhaseHandler ph = ai.getGame().getPhaseHandler();
            if (ph.getPhase().isBefore(PhaseType.COMBAT_BEGIN)) {
                // try not to do this too early to at least attempt to avoid situations where the AI
                // would cast a spell which would ruin the shapeshifting
                return new AiAbilityDecision(0, AiPlayDecision.WaitForMain2);
            }

            CardCollectionView aiGY = ai.getCardsIn(ZoneType.Graveyard);
            Card topGY = null;
            Card creatHand = ComputerUtilCard.getBestCreatureAI(ai.getCardsIn(ZoneType.Hand));
            int numCreatsInHand = CardLists.filter(ai.getCardsIn(ZoneType.Hand), CardPredicates.CREATURES).size();

            if (!aiGY.isEmpty()) {
                topGY = ai.getCardsIn(ZoneType.Graveyard).get(0);
            }

            if (creatHand != null) {
                if (topGY == null
                        || !topGY.isCreature()
                        || ComputerUtilCard.evaluateCreature(creatHand) > ComputerUtilCard.evaluateCreature(topGY) + 80) {
                    if (numCreatsInHand > 1 || !ComputerUtilMana.canPayManaCost(creatHand.getSpellPermanent(), ai, 0, false)) {
                        return new AiAbilityDecision(100, AiPlayDecision.WillPlay);
                    } else {
                        return new AiAbilityDecision(0, AiPlayDecision.CantPlayAi);
                    }
                }
            }

            return new AiAbilityDecision(0, AiPlayDecision.CantPlayAi);
        }

        public static CardCollection targetBestCreature(final Player ai, final SpellAbility sa) {
            Card creatHand = ComputerUtilCard.getBestCreatureAI(ai.getCardsIn(ZoneType.Hand));
            if (creatHand != null) {
                CardCollection cc = new CardCollection();
                cc.add(creatHand);
                return cc;
            }

            // Should ideally never get here
            System.err.println("Volrath's Shapeshifter AI: Could not find a discard target despite the previous confirmation to proceed!");
            return null;
        }
    }

    // Ugin, the Spirit Dragon
    public static class UginTheSpiritDragon {
        public static boolean considerPWAbilityPriority(final Player ai, final SpellAbility sa, final ZoneType origin, CardCollectionView oppType, CardCollectionView computerType) {
            Card source = sa.getHostCard();
            Game game = source.getGame();

            final int loyalty = source.getCounters(CounterEnumType.LOYALTY);
            int x = -1, best = 0;
            Card single = null;
            for (int i = 0; i < loyalty; i++) {
                sa.setXManaCostPaid(i);
                oppType = CardLists.filterControlledBy(game.getCardsIn(origin), ai.getOpponents());
                oppType = AbilityUtils.filterListByType(oppType, sa.getParam("ChangeType"), sa);
                computerType = AbilityUtils.filterListByType(ai.getCardsIn(origin), sa.getParam("ChangeType"), sa);
                int net = ComputerUtilCard.evaluatePermanentList(oppType) - ComputerUtilCard.evaluatePermanentList(computerType) - i;
                if (net > best) {
                    x = i;
                    best = net;
                    if (oppType.size() == 1) {
                        single = oppType.getFirst();
                    } else {
                        single = null;
                    }
                }
            }
            // check if +1 would be sufficient
            if (single != null) {
                // TODO use better logic to find the right Deal Damage Effect?
                SpellAbility ugin_burn = IterableUtil.find(source.getSpellAbilities(), SpellAbilityPredicates.isApi(ApiType.DealDamage), null);
                if (ugin_burn != null) {
                    // basic logic copied from DamageDealAi::dealDamageChooseTgtC
                    if (ugin_burn.canTarget(single)) {
                        final boolean can_kill = single.getSVar("Targeting").equals("Dies")
                                || (ComputerUtilCombat.getEnoughDamageToKill(single, 3, source, false, false) <= 3)
                                && !ComputerUtil.canRegenerate(ai, single)
                                && !(single.getSVar("SacMe").length() > 0);
                        if (can_kill) {
                            return false;
                        }
                        // simple check to burn player instead of exiling planeswalker
                        if (single.isPlaneswalker() && single.getCurrentLoyalty() <= 3) {
                            return false;
                        }
                    }
                }
            }
            if (x == -1) {
                return false;
            }
            sa.setXManaCostPaid(x);
            return true;
        }
    }

    // Warbriar Blessing
    // An Aura whose ETB makes the enchanted creature fight up to one target creature
    // we don't control. Stock AI never cast it: AiController.checkETBEffects judges
    // that fight before the Aura is attached, Defined$ Enchanted is empty there,
    // FightAi.checkApiLogic answers MissingNeededCards and the whole cast is
    // BadEtbEffects. Cast it only as removal - on the creature that kills a real
    // opposing creature and survives - and at resolution take a no-loss fight or
    // none ("up to one": zero targets is always legal and fights nothing).
    public static class WarbriarBlessing {
        // AttachAi.acceptableChoice's "good enough creature" line: a 2/2 token or
        // any real 1-drop clears it, 1/1 and 0/x tokens do not.
        public static final int MIN_VICTIM_EVAL = 130;

        // AttachAi.checkApiLogic name gate: choose the creature to enchant, or don't cast.
        public static AiAbilityDecision consider(final Player ai, final SpellAbility sa) {
            final Card aura = sa.getHostCard();
            final SpellAbility fight = getEtbFight(aura);
            if (fight == null) {
                return new AiAbilityDecision(0, AiPlayDecision.CantPlayAi);
            }
            final SpellAbility fightSa = fight.copy(ai); // the copy AiController.checkETBEffects makes
            final int pow = getEnchantBonus(aura, "AddPower");
            final int tgh = getEnchantBonus(aura, "AddToughness");

            Card bestFighter = null;
            Card bestVictim = null;
            int bestVictimEval = 0;
            for (final Card f : ai.getCreaturesInPlay()) {
                // AttachAi's own exclusions (getSafeTargets, EndOfTurnLeavePlay)
                if (!sa.canTarget(f) || !f.canBeAttached(aura, sa) || f.hasSVar("EndOfTurnLeavePlay")
                        || "Dies".equals(f.getSVar("Targeting")) || "Counter".equals(f.getSVar("Targeting"))) {
                    continue;
                }
                final Card v = getBestVictim(ai, fightSa, f, pow, tgh, MIN_VICTIM_EVAL, true, true);
                if (v == null) {
                    continue;
                }
                final int vEval = ComputerUtilCard.evaluateCreature(v);
                if (bestVictim == null || vEval > bestVictimEval
                        || (v.equals(bestVictim) && ComputerUtilCard.evaluateCreature(f) > ComputerUtilCard.evaluateCreature(bestFighter))) {
                    bestFighter = f;
                    bestVictim = v;
                    bestVictimEval = vEval;
                }
            }
            if (bestFighter == null) {
                return new AiAbilityDecision(0, AiPlayDecision.TargetingFailed);
            }
            sa.resetTargets();
            sa.getTargets().add(bestFighter);
            return new AiAbilityDecision(100, AiPlayDecision.WillPlay);
        }

        // FightAi.doTriggerNoCost name gate: the ETB fight itself.
        public static AiAbilityDecision considerFight(final Player ai, final SpellAbility sa) {
            sa.resetTargets();
            final Card fighter = sa.getHostCard().getEnchantingCard();
            if (fighter == null) {
                // Pre-cast AiController.checkETBEffects (or the Aura fell off): nothing is
                // attached to judge. "Up to one" can always resolve as no fight, and
                // consider() already judged the cast (the handler runs before saSideEffects).
                return new AiAbilityDecision(100, AiPlayDecision.WillPlay);
            }
            // On the stack the Aura's bonuses are already in the fighter's net P/T. The card
            // is spent, so any no-loss kill is pure gain: no evaluation floor. Prefer a victim
            // without ward (an unpaid ward counters the fight); otherwise no fight at all.
            Card victim = getBestVictim(ai, sa, fighter, 0, 0, 0, false, true);
            if (victim == null) {
                victim = getBestVictim(ai, sa, fighter, 0, 0, 0, false, false);
            }
            if (victim != null) {
                sa.getTargets().add(victim);
            }
            return new AiAbilityDecision(100, AiPlayDecision.WillPlay);
        }

        private static Card getBestVictim(final Player ai, final SpellAbility fightSa, final Card fighter, final int pow,
                final int tgh, final int minEval, final boolean castTime, final boolean skipWard) {
            final CardCollection victims = CardLists.filter(
                    CardLists.getTargetableCards(ai.getOpponents().getCreaturesInPlay(), fightSa),
                    v -> (!skipWard || !v.hasKeyword(Keyword.WARD))
                            && ComputerUtilCard.evaluateCreature(v) >= minEval
                            && FightAi.canKill(fighter, v, pow)
                            && survivesFight(fighter, v, tgh, castTime));
            return ComputerUtilCard.getBestCreatureAI(victims);
        }

        // FightAi.shouldFight's survive idiom (the toughness bonus taken off the victim's
        // power), plus the cases that idiom hides.
        private static boolean survivesFight(final Card fighter, final Card victim, final int tgh, final boolean castTime) {
            if (FightAi.canKill(victim, fighter, -tgh)) {
                return false;
            }
            if (victim.getNetPower() > 0) {
                // -tgh hides deathtouch
                if (victim.hasKeyword(Keyword.DEATHTOUCH) && !fighter.hasKeyword(Keyword.INDESTRUCTIBLE)) {
                    return false;
                }
                // any damage destroys it (getDamageToKill's 1), which -tgh hides too
                if (fighter.hasSVar("DestroyWhenDamaged")) {
                    return false;
                }
            }
            if (castTime && ComputerUtil.canRegenerate(fighter.getController(), fighter)) {
                // canKill trusts regeneration, counting mana the Aura itself is about to
                // spend: before the cast, judge survival on toughness alone.
                final int damage = victim.getNetPower() - tgh;
                if (damage > 0 && ComputerUtilCombat.getEnoughDamageToKill(fighter, damage, victim, false) <= damage) {
                    return false;
                }
            }
            return true;
        }

        private static SpellAbility getEtbFight(final Card aura) {
            for (final Trigger t : aura.getTriggers()) {
                if (t.getMode() != TriggerType.ChangesZone || !"Battlefield".equals(t.getParam("Destination"))) {
                    continue;
                }
                final SpellAbility s = t.ensureAbility();
                if (s != null && s.getApi() == ApiType.Fight && "Enchanted".equals(s.getParam("Defined"))) {
                    return s;
                }
            }
            return null;
        }

        private static int getEnchantBonus(final Card aura, final String param) {
            int total = 0;
            for (final StaticAbility st : aura.getStaticAbilities()) {
                if (st.checkMode(forge.game.staticability.StaticAbilityMode.Continuous) && st.hasParam(param)
                        && st.getParamOrDefault("Affected", "").contains("EnchantedBy")) {
                    total += AbilityUtils.calculateAmount(aura, st.getParam(param), st);
                }
            }
            return total;
        }
    }

    // Witch's Mark
    // "You may discard a card. If you do, draw two cards. Create a Wicked Role token attached to
    // up to one target creature you control." Its Role sub targets through TokenAi.chkDrawback;
    // these floors keep the cast from being a blank, and keep Zada, Hedron Grinder's copy fan-out
    // (every copy re-asks the unless cost) from decking us or feeding an opponent's draw or
    // discard punishers once per copy.
    public static class WitchsMark {
        // Cast only when it does something: another card to rummage away while a rummage is safe,
        // or a creature of ours that TokenAi.tgtRoleAura would give the Role (no Role of ours on it).
        public static boolean doesSomething(final Player ai, final SpellAbility sa) {
            final Card host = sa.getHostCard();
            if (ai.getCardsIn(ZoneType.Hand).anyMatch(c -> !c.equals(host)) && willRummage(ai, sa)) {
                return true;
            }
            final SpellAbility sub = sa.getSubAbility();
            if (sub == null || !sub.usesTargeting()) {
                return false;
            }
            final CardCollection homes = CardLists.filterControlledBy(CardUtil.getValidCardsToTarget(sub), ai.getYourTeam());
            return homes.anyMatch(c -> !c.getAttachedCards().anyMatch(att ->
                    att.getController() == ai && att.getType().hasSubtype("Role")));
        }

        // Pay "discard a card: draw N" only while we can draw N (Narset, Parter of Veils), it cannot
        // deck us (DrawAi.targetAI's numCards >= library - 3 floor), and no opponent's permanent
        // steals or punishes the draw or the discard (Notion Thief, Alms Collector, Orcish
        // Bowmasters, Spiteful Visions, Waste Not). The Roles still land when this says no.
        public static boolean willRummage(final Player payer, final SpellAbility sa) {
            final int n = AbilityUtils.calculateAmount(sa.getHostCard(), sa.getParamOrDefault("NumCards", "1"), sa);
            if (!payer.canDrawAmount(n)) {
                return false;
            }
            if (n >= payer.getCardsIn(ZoneType.Library).size() - 3 && !payer.isCardInPlay("Laboratory Maniac")) {
                return false;
            }
            for (final Player opp : payer.getOpponents()) {
                for (final Card c : opp.getCardsIn(ZoneType.Battlefield)) {
                    for (final ReplacementEffect re : c.getReplacementEffects()) {
                        if (re.getMode() == ReplacementType.Draw || re.getMode() == ReplacementType.DrawCards) {
                            return false;
                        }
                    }
                    for (final Trigger t : c.getTriggers()) {
                        if (t.getMode() == TriggerType.Drawn || t.getMode() == TriggerType.Discarded) {
                            return false;
                        }
                    }
                }
            }
            return true;
        }
    }

    // Yawgmoth's Bargain
    public static class YawgmothsBargain {
        public static boolean consider(final Player ai, final SpellAbility sa) {
            Game game = ai.getGame();
            PhaseHandler ph = game.getPhaseHandler();

            if (ai.getCardsIn(ZoneType.Library).isEmpty()) {
                return false; // nothing to draw from the library
            }

            int computerHandSize = ai.getZone(ZoneType.Hand).size();
            int maxHandSize = ai.getMaxHandSize();

            // TODO: Any other bad effects like that?
            boolean blackViseOTB = game.getCardsIn(ZoneType.Battlefield).anyMatch(CardPredicates.nameEquals("Black Vise"));

            // TODO: Consider effects like "whenever a player draws a card, he loses N life" (e.g. Nekusar, the Mindraiser),
            //       and effects that draw an additional card whenever a card is drawn.

            if (ph.getNextTurn().equals(ai) && ph.is(PhaseType.END_OF_TURN)
                    && ai.getSpellsCastLastTurn() == 0
                    && ai.getSpellsCastThisTurn() == 0
                    && ai.getLandsPlayedLastTurn() == 0) {
                // We're in a situation when we have nothing castable in hand, something needs to be done
                if (!blackViseOTB) {
                    // draw +1 card when at max hand size, hoping to draw a workable spell or land
                    return computerHandSize == maxHandSize;
                } else {
                    // draw cards hoping to draw answers even in presence of Black Vise if there's no valid play
                    // TODO: maybe limit to 1 or 2 cards at a time?
                    return computerHandSize + 1 <= maxHandSize; // currently draws to 7 cards
                }
            } else if (blackViseOTB && computerHandSize + 1 > 4) {
                // try not to overdraw in presence of Black Vise
                return false;
            } else if (computerHandSize + 1 > maxHandSize) {
                // Only draw until we reach max hand size
                return false;
            } else if (!ph.isPlayerTurn(ai)) {
                // Only activate in AI's own turn (sans the exception above)
                return false;
            }
            return true;
        }
    }

    // Yawgmoth's Will and other cards with similar effect, e.g. Magus of the Will
    public static class YawgmothsWill {
        public static boolean consider(final Player ai, final SpellAbility sa) {
            CardCollectionView cardsInGY = ai.getCardsIn(ZoneType.Graveyard);
            if (cardsInGY.size() == 0) {
                return false;
            } else if (ai.getGame().getPhaseHandler().getPlayerTurn() != ai) {
                // The AI is not very good at deciding for what to viably do during the opp's turn when this
                // comes from an instant speed effect (e.g. Magus of the Will)
                return false;
            }

            int minManaAdj = 2; // we want the AI to have some spare mana for possible other spells to cast
            float minCastableInGY = 3.0f; // we want the AI to have several castable cards in GY before attempting this effect
            List<SpellAbility> saList = ComputerUtilAbility.getSpellAbilities(cardsInGY, ai);
            int selfCMC = sa.getPayCosts().getCostMana().getMana().getCMC();

            float numCastable = 0.0f;
            for (SpellAbility ab : saList) {
                final Card src = ab.getHostCard();

                if (ab.getApi() == ApiType.Counter) {
                    // cut short considering to play counterspells via Yawgmoth's Will
                    continue;
                }

                if ((ComputerUtilAbility.getAbilitySourceName(ab).equals(ComputerUtilAbility.getAbilitySourceName(sa))
                        && !(ab instanceof SpellPermanent)) || ab.hasParam("AINoRecursiveCheck")) {
                    // prevent infinitely recursing abilities that are susceptible to reentry
                    continue;
                }

                // check to see if the AI is willing to play this card
                final SpellAbility testAb = ab.copy();
                testAb.getRestrictions().setZone(ZoneType.Graveyard);
                testAb.setActivatingPlayer(ai);

                boolean willPlayAb = ((PlayerControllerAi) ai.getController()).getAi().canPlaySa(testAb) == AiPlayDecision.WillPlay;

                // Land drops are generally made by the AI in main 1 before casting spells, so testing for them is iffy.
                if (!src.getType().isLand() && willPlayAb) {
                    int CMC = ab.getPayCosts().getTotalMana() != null ? ab.getPayCosts().getTotalMana().getCMC() : 0;
                    int Xcount = ab.getPayCosts().getTotalMana() != null ? ab.getPayCosts().getTotalMana().countX() : 0;

                    if ((Xcount == 0 && CMC == 0) || ComputerUtilMana.canPayManaCost(ab, ai, selfCMC + minManaAdj, false)) {
                        if (src.isInstant() || src.isSorcery()) {
                            // instants and sorceries are one-shot, so only treat them as 1/2 value for the purpose of meeting minimum 
                            // castable cards in graveyard requirements 
                            numCastable += 0.5f;
                        } else {
                            numCastable += 1.0f;
                        }
                    }
                }
            }

            return numCastable >= minCastableInGY;
        }
    }

}
