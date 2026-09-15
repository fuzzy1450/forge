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
import forge.ai.ability.AnimateAi;
import forge.ai.ability.FightAi;
import forge.card.ColorSet;
import forge.card.MagicColor;
import forge.card.mana.ManaCost;
import forge.game.Game;
import forge.game.GameEntity;
import forge.game.GameType;
import forge.game.ability.AbilityUtils;
import forge.game.ability.ApiType;
import forge.game.card.*;
import forge.game.combat.Combat;
import forge.game.combat.CombatUtil;
import forge.game.cost.CostExile;
import forge.game.cost.CostPart;
import forge.game.cost.CostSacrifice;
import forge.game.keyword.Keyword;
import forge.game.mana.ManaCostBeingPaid;
import forge.game.phase.PhaseHandler;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.player.PlayerCollection;
import forge.game.player.PlayerPredicates;
import forge.game.replacement.ReplacementEffect;
import forge.game.replacement.ReplacementType;
import forge.game.spellability.AbilitySub;
import forge.game.spellability.SpellAbility;
import forge.game.spellability.SpellAbilityPredicates;
import forge.game.spellability.SpellAbilityStackInstance;
import forge.game.spellability.SpellPermanent;
import forge.game.staticability.StaticAbility;
import forge.game.staticability.StaticAbilityCantDraw;
import forge.game.trigger.Trigger;
import forge.game.trigger.TriggerType;
import forge.game.zone.ZoneType;
import forge.util.Aggregates;
import forge.util.FileSection;
import forge.util.IterableUtil;
import forge.util.MyRandom;
import forge.util.TextUtil;
import org.apache.commons.lang3.tuple.Pair;

import java.util.*;
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
