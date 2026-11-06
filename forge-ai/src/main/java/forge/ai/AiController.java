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
import com.google.common.collect.Sets;

import forge.ai.AiCardMemory.MemorySet;
import forge.ai.ability.ChangeZoneAi;
import forge.ai.ability.ConniveAi;
import forge.ai.ability.LearnAi;
import forge.ai.combo.ComboExecute;
import forge.ai.combo.ComboFetch;
import forge.ai.combo.ComboHold;
import forge.ai.combo.ComboLines;
import forge.ai.combo.ComboTracker;
import forge.ai.combo.LoopDriver;
import forge.ai.simulation.*;
import forge.card.CardStateName;
import forge.card.CardType;
import forge.card.ColorSet;
import forge.card.MagicColor;
import forge.card.mana.ManaAtom;
import forge.card.mana.ManaCost;
import forge.deck.Deck;
import forge.deck.DeckSection;
import forge.game.*;
import forge.game.ability.AbilityKey;
import forge.game.ability.AbilityUtils;
import forge.game.ability.ApiType;
import forge.game.ability.SpellApiBased;
import forge.game.card.*;
import forge.game.combat.Combat;
import forge.game.combat.CombatUtil;
import forge.game.cost.*;
import forge.game.keyword.Keyword;
import forge.game.mana.ManaCostBeingPaid;
import forge.game.phase.PhaseHandler;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.player.PlayerActionConfirmMode;
import forge.game.player.PlayerCollection;
import forge.game.replacement.ReplaceMoved;
import forge.game.replacement.ReplacementEffect;
import forge.game.replacement.ReplacementLayer;
import forge.game.replacement.ReplacementType;
import forge.game.spellability.*;
import forge.game.staticability.StaticAbility;
import forge.game.staticability.StaticAbilityDisableTriggers;
import forge.game.staticability.StaticAbilityMode;
import forge.game.staticability.StaticAbilityMustTarget;
import forge.game.trigger.Trigger;
import forge.game.trigger.TriggerType;
import forge.game.trigger.WrappedAbility;
import forge.game.zone.ZoneType;
import forge.item.PaperCard;
import forge.util.*;

import io.sentry.Breadcrumb;
import io.sentry.Sentry;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static forge.ai.ComputerUtilMana.getAvailableManaEstimate;
import static java.lang.Math.max;

/**
 * <p>
 * AiController class.
 * </p>
 *
 * @author Forge
 * @version $Id$
 */
public class AiController {
    private final Player player;
    private final Game game;
    private final AiCardMemory memory;
    private final LoopTracker loops;
    private Combat predictedCombat;
    private Combat predictedCombatNextTurn;
    private AIOption simMode;
    private SpellAbilityPicker simPicker;
    private int lastAttackAggression;
    private List<SpellAbility> skipped;
    // Dead-card batch 4, row 33 (Dramatic Reversal): what canPlaySa approved and canPayCost then
    // refused, each entry with its host's zone and the turn and phase it was refused in. Cleared at
    // the start of every chooseSpellAbilityToPlayFromList and added to only after canPayCost drew, so
    // no draw of A moves. The stamp bounds an entry by turn and phase, not by pass: a direct canPlaySa
    // scanner in the same phase can read the previous pass's entries. An instance field: a static
    // would be shared by every concurrent game of a serve JVM. Read only by
    // SpecialCardAi.DramaticReversal.
    private final List<SpecialCardAi.DramaticReversal.Refused> approvedUnaffordable = new ArrayList<>();
    // Combo-aware AI, piece 1 (spec 2026-10-22-combo-aware-ai-core-design.md section 6.2): which combo lines (piece
    // 1's empty-library lines, piece 2's loop lines) this AI's own deck holds, and where each of their pieces is. It
    // reads nothing until its first query, because this constructor runs before the player is registered with its game.
    // An instance field: a static would be shared by every concurrent game of a serve JVM. Read by forge.ai.combo,
    // through ComboTracker.of.
    private final ComboTracker combos;
    // Combo-aware AI, piece 2 (spec 2026-10-29-combo-aware-ai-loops-design.md sections 6.2-6.5): this AI's loop
    // driver, which runs the loop lines its deck holds through getSpellAbilityToPlay's slots. Like the tracker it reads
    // nothing until its first decision. An instance field, for the same reason. Read by forge.ai.combo, through
    // LoopDriver.of.
    private final LoopDriver loopDriver;
    // flat Monte Carlo bookkeeping: time spent on rollouts during one turn, to keep slow devices responsive
    private int flatMcTurn = -1;
    private long flatMcTurnSpentNanos;
    private FlatMonteCarloPicker.Result lastFlatMcResult;
    /** Circuit breaker: Monte Carlo switches itself off for this player after repeated errors or hopeless slowness. */
    private int flatMcErrors;
    private int flatMcSlowStreak;
    /** Errors switch Monte Carlo off for the rest of the game (a bug will not go away); slowness only pauses it. */
    private boolean flatMcTripped;
    /** Slowness: Monte Carlo sits out a few turns, then comes back with a smaller search (see {@link #flatMonteCarloConfig}). */
    private int flatMcSlowTrips;
    private int flatMcPausedUntilTurn = -1;
    private int flatMcGoodStreak;
    private static final int FLAT_MC_PAUSE_TURNS = 3;
    /** The search shrinks and the pause grows for this many slow spells in a row; further ones change nothing. */
    private static final int FLAT_MC_MAX_SLOW_TRIPS = 3;
    /** Good decisions in a row that give back one step of the search a slow spell took away. */
    private static final int FLAT_MC_RECOVER_DECISIONS = 25;
    /** Per player switch for comparisons and tests: null follows the game-wide setting. */
    private Boolean flatMcOverride;
    private Boolean flatMcMaxOverride;

    /**
     * Casts and activations that could not be put on the stack this turn (illegal targets), by card and ability, to
     * the turn number. It lives here and not in the game on purpose: a rollback restores the game and would forget it.
     */
    private final Map<String, Integer> failedCasts = new HashMap<>();

    public static String castKey(final SpellAbility sa) {
        final Card host = sa.getHostCard();
        return (host == null ? "?" : String.valueOf(host.getId())) + "|" + sa.getDescription();
    }

    /** Remember that this cast failed: it is not tried again this turn. */
    public void noteFailedCast(final String key) {
        failedCasts.put(key, game.getPhaseHandler().getTurn());
    }

    public boolean hasFailedToCast(final SpellAbility sa) {
        if (failedCasts.isEmpty()) {
            return false;
        }
        final Integer turn = failedCasts.get(castKey(sa));
        if (turn == null) {
            return false;
        }
        if (turn != game.getPhaseHandler().getTurn()) {
            failedCasts.remove(castKey(sa));
            return false;
        }
        return true;
    }

    public AiController(final Player computerPlayer, final Game game0) {
        player = computerPlayer;
        game = game0;
        memory = new AiCardMemory();
        loops = new LoopTracker();
        combos = new ComboTracker(player, ComboLines.shipped());
        loopDriver = new LoopDriver(player);
        simPicker = new SpellAbilityPicker(player);
    }

    public boolean usesHybridSimulation() {
        return simMode == AIOption.USE_HYBRID_SIMULATION;
    }
    /** Whether any simulation search (full or hybrid) takes the decisions of this AI. */
    public boolean usesSimulation() {
        return simMode != null;
    }
    public boolean usesFullSimulation() {
        return simMode == AIOption.USE_FULL_SIMULATION;
    }
    public void setUseSimulation(AIOption mode) {
        simMode = mode;
    }

    public int getAttackAggression() {
        return lastAttackAggression;
    }

    public SpellAbilityPicker getSimulationPicker() {
        return simPicker;
    }

    public Game getGame() {
        return game;
    }

    public Player getPlayer() {
        return player;
    }

    public AiCardMemory getCardMemory() {
        return memory;
    }

    // Dead-card batch 4, row 33: the record above, read only by SpecialCardAi.DramaticReversal
    public List<SpecialCardAi.DramaticReversal.Refused> getApprovedUnaffordable() {
        return approvedUnaffordable;
    }

    /** How this AI judges the loops it controls: spent or still paying, and which names a spent loop avoids. */
    public LoopTracker getLoopTracker() {
        return loops;
    }

    /** Which combo lines this AI's deck holds, and where their pieces are: read by forge.ai.combo. */
    public ComboTracker getComboTracker() {
        return combos;
    }

    /** This AI's loop driver (combo-aware AI, piece 2): read by forge.ai.combo, through LoopDriver.of. */
    public LoopDriver getLoopDriver() {
        return loopDriver;
    }

    private StormSolver stormSolver;

    /** Whether Flat Monte Carlo is switched on for this player. */
    public boolean flatMonteCarloActive() {
        return (flatMcOverride != null ? flatMcOverride : game.getAIFlatMonteCarlo()) && !flatMcOff()
                && simMode == null && getBoolProperty(AiProps.FLAT_MC_ENABLED);
    }

    public Combat getPredictedCombat() {
        if (predictedCombat == null) {
            AiAttackController aiAtk = new AiAttackController(player);
            predictedCombat = new Combat(player);
            boolean predicted = false;
            try {
                aiAtk.declareAttackers(predictedCombat);
                predicted = true;
            } finally {
                if (!predicted) {
                    predictedCombat = null; // cut short (AITimeoutTracker.Expired): do not serve a partial combat to trigger decisions
                }
            }
            // ignoring attack costs for performance
        }
        return predictedCombat;
    }

    public Combat getPredictedCombatNextTurn() {
        if (predictedCombatNextTurn == null) {
            AiAttackController aiAtk = new AiAttackController(player, true);
            predictedCombatNextTurn = new Combat(player);
            boolean predicted = false;
            try {
                aiAtk.declareAttackers(predictedCombatNextTurn);
                predicted = true;
            } finally {
                if (!predicted) {
                    predictedCombatNextTurn = null; // cut short: do not keep a partial combat
                }
            }
        }
        return predictedCombatNextTurn;
    }

    private List<SpellAbility> getPossibleETBCounters() {
        CardCollection all = new CardCollection(player.getCardsIn(ZoneType.Hand));
        CardCollectionView ccvPlayerLibrary = player.getCardsIn(ZoneType.Library);

        all.addAll(player.getCardsIn(ZoneType.Exile));
        all.addAll(player.getCardsIn(ZoneType.Graveyard));
        if (!ccvPlayerLibrary.isEmpty()) {
            all.add(ccvPlayerLibrary.get(0));
        }

        all.addAll(player.getOpponents().getCardsIn(ZoneType.Exile));

        final List<SpellAbility> spellAbilities = Lists.newArrayList();
        for (final Card c : all) {
            for (final SpellAbility sa : c.getNonManaAbilities()) {
                if (sa instanceof SpellPermanent) {
                    sa.setActivatingPlayer(player);
                    if (checkETBEffects(c, sa, ApiType.Counter)) {
                        spellAbilities.add(sa);
                    }
                }
            }
        }
        return spellAbilities;
    }

    // look for cards on the battlefield that should prevent the AI from using that spellability
    private boolean checkCurseEffects(final SpellAbility sa) {
        CardCollectionView ccvGameBattlefield = CardLists.filter(game.getCardsIn(ZoneType.Battlefield), CardPredicates.hasSVar("AICurseEffect"));
        for (final Card c : ccvGameBattlefield) {
            final String curse = c.getSVar("AICurseEffect");
            final Card host = sa.getHostCard();
            if ("NonActive".equals(curse) && !player.equals(game.getPhaseHandler().getPlayerTurn())) {
                return true;
            } else if ("DestroyCreature".equals(curse) && sa.isSpell() && host.isCreature()
                    && !host.hasKeyword(Keyword.INDESTRUCTIBLE)) {
                return true;
            } else if ("CounterEnchantment".equals(curse) && sa.isSpell() && host.isEnchantment() && sa.isCounterableBy(null)) {
                return true;
            } else if ("ChaliceOfTheVoid".equals(curse) && sa.isSpell() && sa.isCounterableBy(null)
                    && host.getCMC() == c.getCounters(CounterEnumType.CHARGE)) {
                return true;
            } else if ("BazaarOfWonders".equals(curse) && sa.isSpell() && sa.isCounterableBy(null)) {
                String hostName = host.getName();
                for (Card card : ccvGameBattlefield) {
                    if (!card.isToken() && card.sharesNameWith(host)) {
                        return true;
                    }
                }
                if (game.getCardsIn(ZoneType.Graveyard).anyMatch(CardPredicates.nameEquals(hostName))) {
                    return true;
                }
            }
        }
        return false;
    }

    public boolean checkETBEffects(final Card card, final SpellAbility sa, final ApiType api) {
        boolean reset = false;
        if (card.getCastSA() == null) {
            // for xPaid stuff
            card.setCastSA(sa);
            reset = true;
        }
        try {
            return checkETBEffectsPreparedCard(card, sa, api);
        } finally {
            // also when AITimeoutTracker.Expired is thrown from a nested check(): a real card must never keep a hypothetical castSA
            if (reset) {
                card.setCastSA(null);
            }
        }
    }

    private boolean checkETBEffectsPreparedCard(final Card card, final SpellAbility sa, final ApiType api) {
        final Player activator = sa.getActivatingPlayer();

        // Replacement effects
        for (final ReplacementEffect re : card.getReplacementEffects()) {
            // These Replacements all care for ETB effects
            if (!(re instanceof ReplaceMoved)) {
                continue;
            }

            if (!ZoneType.Battlefield.toString().equals(re.getParam("Destination"))) {
                continue;
            }

            if (re.hasParam("ValidCard")) {
                String validCard = re.getParam("ValidCard");
                if (!validCard.contains("Self")) {
                    continue;
                }
                if (validCard.contains("!kicked")) {
                    if (sa.isKicked()) {
                        continue;
                    }
                } else if (validCard.contains("kicked")) {
                    if (validCard.contains("kicked ")) { // want a specific kicker
                        String s = validCard.split("kicked ")[1];
                        if ("1".equals(s) && !sa.isOptionalCostPaid(OptionalCost.Kicker1)) continue;
                        if ("2".equals(s) && !sa.isOptionalCostPaid(OptionalCost.Kicker2)) continue;
                    } else if (!sa.isKicked()) { // otherwise just any must be present
                        continue;
                    }
                }
            }

            if (!re.requirementsCheck(game)) {
                continue;
            }
            SpellAbility exSA = re.getOverridingAbility();

            if (exSA != null) {
                exSA = exSA.copy(activator);

                // These checks only work if the Executing SpellAbility is an Ability_Sub.
                if (exSA instanceof AbilitySub && !doTrigger(exSA, false)) {
                    return false;
                }
            }
        }

        boolean rightapi = api == null;

        // Trigger play improvements
        for (final Trigger tr : card.getTriggers()) {
            // These triggers all care for ETB effects

            if (tr.getMode() != TriggerType.ChangesZone) {
                continue;
            }

            // can't fetch partner isn't problematic
            if (tr.isKeyword(Keyword.PARTNER_WITH)) {
                continue;
            }

            if (!ZoneType.Battlefield.toString().equals(tr.getParam("Destination"))) {
                continue;
            }

            final Map<AbilityKey, Object> runParams = AbilityKey.mapFromCard(tr.getHostCard());
            runParams.put(AbilityKey.Destination, ZoneType.Battlefield.name());
            if (StaticAbilityDisableTriggers.disabled(game, tr, runParams)) {
                return api == null;
            }

            if (tr.hasParam("ValidCard")) {
                String validCard = tr.getParam("ValidCard");
                if (!validCard.contains("Self")) {
                    continue;
                }
                if (validCard.contains("!kicked")) {
                    if (sa.isKicked()) {
                        continue;
                    }
                } else if (validCard.contains("kicked")) {
                    if (validCard.contains("kicked ")) { // want a specific kicker
                        String s = validCard.split("kicked ")[1];
                        if ("1".equals(s) && !sa.isOptionalCostPaid(OptionalCost.Kicker1)) continue;
                        if ("2".equals(s) && !sa.isOptionalCostPaid(OptionalCost.Kicker2)) continue;
                    } else if (!sa.isKicked()) {
                        continue;
                    }
                }
            }

            if (!tr.requirementsCheck(game)) {
                continue;
            }

            // if trigger is not mandatory - no problem
            if (tr.hasParam("OptionalDecider") && api == null) {
                continue;
            }

            SpellAbility exSA = tr.ensureAbility();
            if (exSA.getApi() == api) {
                rightapi = true;
            } else if (api != null) {
                continue;
            }
            exSA = exSA.copy(activator);

            if (api != null && !(exSA instanceof AbilitySub) && !ComputerUtilCost.canPayCost(exSA, player, true)) {
                return false;
            }

            exSA.setTrigger(tr);
            // need to set TriggeredObject
            exSA.setTriggeringObject(AbilityKey.Card, card);

            // for trigger test, need to ignore the conditions
            SpellAbilityCondition cons = exSA.getConditions();
            if (cons != null) {
                String pres = cons.getIsPresent();
                if (pres != null && pres.matches("Card\\.(Strictly)?Self")) {
                    cons.setIsPresent(null);
                }
            }

            // Run non-mandatory trigger.
            // These checks only work if the Executing SpellAbility is an Ability_Sub.
            if (exSA instanceof AbilitySub && !doTrigger(exSA, false)) {
                // AI would not run this trigger if given the chance
                if (api == null && card.isCreature() && !ComputerUtilAbility.isFullyTargetable(exSA) &&
                        (ComputerUtil.aiLifeInDanger(activator, true, 0) || "BadETB".equals(tr.getParam("AILogic")))) {
                    // trigger will not run due to lack of targets and we 1. desperately need a creature or 2. are happy about that
                    continue;
                }
                return false;
            }
        }

        if (card.isSaga()) {
            for (final Trigger tr : card.getTriggers()) {
                if (!tr.isChapter()) {
                    continue;
                }

                SpellAbility exSA = tr.ensureAbility().copy(activator);

                if (exSA.getApi() == api) {
                    rightapi = true;
                }

                if (exSA instanceof AbilitySub && !doTrigger(exSA, false)) {
                    // AI would not run this chapter if given the chance
                    // TODO eventually we'll want to consider playing it anyway, especially if Read ahead would still allow an immediate benefit
                    return false;
                }

                // usually later chapters make use of an earlier one
                break;
            }
        }

        return rightapi;
    }

    private static List<SpellAbility> getPlayableCounters(final CardCollection all) {
        final List<SpellAbility> spellAbility = Lists.newArrayList();
        for (final Card c : all) {
            CardState state = c.isForetold() && c.getAlternateState() != null ? c.getAlternateState() : c.getCurrentState();
            for (final SpellAbility sa : state.getNonManaAbilities()) {
                if (sa.getApi() == ApiType.Counter) {
                    spellAbility.add(sa);
                }
            }
        }
        return spellAbility;
    }

    private CardCollection filterLandsToPlay(CardCollection landList) {
        final CardCollectionView hand = player.getCardsIn(ZoneType.Hand);
        CardCollection nonLandList = CardLists.filter(hand, CardPredicates.NON_LANDS);
        if (landList.size() == 1 && nonLandList.size() < 3) {
            CardCollectionView cardsInPlay = player.getCardsIn(ZoneType.Battlefield);
            CardCollection landsInPlay = CardLists.filter(cardsInPlay, CardPredicates.LANDS);
            CardCollection allCards = new CardCollection(player.getCardsIn(ZoneType.Graveyard));
            allCards.addAll(player.getCardsIn(ZoneType.Command));
            allCards.addAll(cardsInPlay);
            int maxCmcInHand = Aggregates.max(hand, Card::getCMC);
            int max = Math.max(maxCmcInHand, 6);
            // consider not playing lands if there are enough already and an ability with a discard cost is present
            if (landsInPlay.size() + landList.size() > max) {
                for (Card c : allCards) {
                    for (SpellAbility sa : c.getSpellAbilities()) {
                        Cost payCosts = sa.getPayCosts();
                        if (payCosts != null) {
                            for (CostPart part : payCosts.getCostParts()) {
                                if (part instanceof CostDiscard) {
                                    return null;
                                }
                            }
                        }
                    }
                }
            }
        }

        landList = ComputerUtilCard.dedupeCards(landList);

        landList = CardLists.filter(landList, c -> {
            String name = c.getName();
            CardCollectionView battlefield = player.getCardsIn(ZoneType.Battlefield);
            if (c.getType().isLegendary() && !name.equals("Flagstones of Trokair")) {
                if (battlefield.anyMatch(CardPredicates.nameEquals(name))) {
                    return false;
                }
            }

            final CardCollectionView hand1 = player.getCardsIn(ZoneType.Hand);
            CardCollection lands = new CardCollection(battlefield);
            lands.addAll(hand1);
            lands = CardLists.filter(lands, CardPredicates.LANDS);
            int maxCmcInHand = Aggregates.max(hand1, Card::getCMC);

            if (lands.size() >= Math.max(maxCmcInHand, 6)) {
                // don't play MDFC land if other side is spell and enough lands are available
                if (!c.isLand() || (c.isModal() && !c.getState(CardStateName.Backside).getType().isLand())) {
                    return false;
                }

                // don't play the land if it has cycling and enough lands are available
                if (c.hasKeyword(Keyword.CYCLING)) {
                    return false;
                }
            }
            return c.getAllPossibleAbilities(player, true).stream().anyMatch(
                    la -> la.isLandAbility() && saSideEffects(c, la).willingToPlay()
            );
        });
        return landList;
    }

    /** Whether the land would enter the battlefield tapped right now (a replacement effect that taps it, with its condition met). */
    private boolean landEntersTapped(final Card land) {
        // check replacement effects if land would enter tapped or not
        final Map<AbilityKey, Object> repParams = AbilityKey.mapFromAffected(land);
        repParams.put(AbilityKey.Origin, land.getZone().getZoneType());
        repParams.put(AbilityKey.Destination, ZoneType.Battlefield);

        // add Params for AddCounter Replacements
        GameEntityCounterTable table = new GameEntityCounterTable();
        repParams.put(AbilityKey.EffectOnly, true);
        repParams.put(AbilityKey.CounterTable, table);
        repParams.put(AbilityKey.CounterMap, table.column(land));

        for (ReplacementEffect re : player.getGame().getReplacementHandler().getReplacementList(ReplacementType.Moved, repParams, ReplacementLayer.Other)) {
            SpellAbility reSA = re.ensureAbility();
            if (reSA == null || !ApiType.Tap.equals(reSA.getApi())) {
                continue;
            }
            reSA.setActivatingPlayer(reSA.getHostCard().getController());
            if (reSA.metConditions()) {
                return true;
            }
        }
        return false;
    }

    private Card chooseBestLandToPlay(CardCollection landList) {
        if (landList.isEmpty()) {
            return null;
        }

        CardCollection nonLandsInHand = CardLists.filter(player.getCardsIn(ZoneType.Hand), CardPredicates.NON_LANDS);

        // Some considerations for Momir/MoJhoSto
        boolean hasMomir = player.isCardInCommand("Momir Vig, Simic Visionary Avatar");
        if (hasMomir && nonLandsInHand.isEmpty()) {
            // Only do this if we have an all-basic land hand, which covers both stock Momir and MoJhoSto modes
            // and also a custom Vanguard setup with a customized basic land deck and Momir as the avatar.
            String landStrategy = getProperty(AiProps.MOMIR_BASIC_LAND_STRATEGY);
            if (landStrategy.equalsIgnoreCase("random")) {
                // Pick a completely random basic land
                return Aggregates.random(landList);
            } else if (landStrategy.toLowerCase().startsWith("preforder:")) {
                // Pick a basic land in order of preference, or play a random one if nothing is preferred
                String order = landStrategy.substring(10);
                for (char c : order.toCharArray()) {
                    byte color = MagicColor.fromName(c);
                    for (Card land : landList) {
                        for (final SpellAbility m : ComputerUtilMana.getAIPlayableMana(land)) {
                            if (m.canProduce(MagicColor.toShortString(color))) {
                                return land;
                            }
                        }
                    }
                }
                return Aggregates.random(landList);
            }
            // If nothing is done here, proceeds to the default land picking strategy
        }

        //Skip reflected lands.
        CardCollection unreflectedLands = new CardCollection(landList);
        for (Card l : landList) {
            if (l.isReflectedLand()) {
                unreflectedLands.remove(l);
            }
        }
        if (!unreflectedLands.isEmpty()) {
            landList = unreflectedLands;
        }

        // a deck built around a land (Dark Depths) plays its lands in its own order, see ComboPlan
        final Card forCombo = deckPlan(player).combos.chooseLand(player, landList);
        if (forCombo != null) {
            return forCombo;
        }

        // Plan the next land drops against the spells in hand (and the commander): which colours are needed when, and
        // whether a land that enters tapped costs anything this turn. It answers only when one land is clearly better.
        if (landList.size() > 1 && getBoolProperty(AiProps.LAND_PLANNER)) {
            final Card planned = LandPlanner.choose(player, landList, this::landEntersTapped);
            if (planned != null) {
                return planned;
            }
        }

        // try to skip lands that enter the battlefield tapped if we might want to play something this turn
        if (!nonLandsInHand.isEmpty()) {
            CardCollection nonTappedLands = new CardCollection();
            for (Card land : landList) {
                // TODO if this is the only source for a color we need badly prioritize it instead
                if (landEntersTapped(land)) {
                    continue;
                }

                nonTappedLands.add(land);
            }

            // if we have the choice, see if we can play an untapped land
            if (!nonTappedLands.isEmpty()) {
                // If we have a lot of mana, prefer untapped lands.
                // We're either topdecking or have drawn enough the tempo no longer matters.
                int mana_available = getAvailableManaEstimate(player);
                if (mana_available > 6) {
                    landList = nonTappedLands;
                } else {
                    // get the costs of the nonland cards in hand and the mana we have available.
                    // If adding one won't make something new castable, then pick a tapland.
                    int max_inc = 0;
                    for (Card c : nonTappedLands) {
                        max_inc = max(max_inc, c.getMaxManaProduced());
                    }
                    // check for lands with no mana abilities
                    if (max_inc > 0) {
                        boolean found = false;
                        for (Card c : nonLandsInHand) {
                            // TODO make this work better with split cards and Monocolored Hybrid
                            ManaCost cost = c.getManaCost();
                            // check for incremental cmc
                            // check for X cost spells
                            if ((cost.getCMC() - mana_available) * (cost.getCMC() - mana_available - max_inc - 1) < 0 ||
                                    (cost.countX() > 0 && cost.getCMC() >= mana_available)) {
                                found = true;
                                break;
                            }
                        }

                        if (found) {
                            landList = nonTappedLands;
                        }
                    }
                }
            }
        }

        // Early out if we only have one card left
        if (landList.size() == 1) {
            return landList.get(0);
        }

        // Choose first land to be able to play a one drop
        if (player.getLandsInPlay().isEmpty()) {
            CardCollection oneDrops = CardLists.filter(nonLandsInHand, CardPredicates.hasCMC(1));
            for (MagicColor.Color color : ColorSet.WUBRG) {
                if (oneDrops.anyMatch(CardPredicates.isColor(color.getColorMask()))) {
                    for (Card land : landList) {
                        if (land.getType().hasSubtype(color.getBasicLandType())) {
                            return land;
                        }
                        for (final SpellAbility m : ComputerUtilMana.getAIPlayableMana(land)) {
                            // before the first land drop there is usually nothing to pay for a filter
                            if (!m.getPayCosts().hasManaCost() && m.canProduce(color.getShortName())) {
                                return land;
                            }
                        }
                    }
                }
            }
        }

        // play lands with a basic type and/or color that is needed the most
        final CardCollectionView landsInBattlefield = player.getLandsInPlay();
        final Set<String> basics = Sets.newHashSet();

        // what colors are available?
        int[] counts = new int[6]; // in WUBRGC order

        for (Card c : player.getCardsIn(ZoneType.Battlefield)) {
            for (SpellAbility m: c.getManaAbilities()) {
                m.setActivatingPlayer(c.getController());
                for (AbilityManaPart mp : m.getAllManaParts()) {
                    for (String part : mp.mana(m).split(" ")) {
                        // TODO handle any
                        int index = ManaAtom.getIndexFromName(part);
                        if (index != -1) {
                            counts[index] += 1;
                        }
                    }
                }
            }
        }

        // what types can I go get?
        for (final String name : MagicColor.Constant.BASIC_LANDS) {
            if (landList.stream().anyMatch(c -> c.getType().hasSubtype(name)) &&
                    landsInBattlefield.stream().anyMatch(c -> c.getType().hasSubtype(name))) {
                basics.add(name);
            }
        }

        // pick the land with the best score.
        // use the evaluation plus a modifier for each new color pip and basic type
        Card toReturn = Aggregates.itemWithMax(IterableUtil.filter(landList, Card::hasPlayableLandFace),
                (card -> {
                    // base score is for the evaluation score
                    int score = GameStateEvaluator.evaluateLand(card);
                    // add for new basic type
                    for (String cardType: card.getType().getLandTypes()) {
                        if (CardType.isABasicLandType(cardType) && !basics.contains(cardType)) {
                            score += 25;
                        }
                    }

                    // TODO handle fetchlands and what they can fetch for
                    // determine new color pips
                    int[] card_counts = new int[6]; // in WUBRGC order
                    for (SpellAbility m: card.getManaAbilities()) {
                        m.setActivatingPlayer(card.getController());
                        for (AbilityManaPart mp : m.getAllManaParts()) {
                            for (String part : mp.mana(m).split(" ")) {
                                // TODO handle any
                                int index = ManaAtom.getIndexFromName(part);
                                if (index != -1) {
                                    card_counts[index] += 1;
                                }
                            }
                        }
                    }

                    // use 1 / x+1 for diminishing returns
                    // TODO use max pips of each color in the deck from deck statistics to weight this
                    for (int i = 0; i < card_counts.length; i++) {
                        int diff = (card_counts[i] * 50) / (counts[i] + 1);
                        score += diff;
                    }

                    // TODO utility lands only if we have enough to pay their costs
                    // TODO Tron lands and other lands that care about land counts

                    return score;
                }));
        return toReturn;
    }

    // if return true, go to next phase
    private SpellAbility chooseCounterSpell(final List<SpellAbility> possibleCounters) {
        if (possibleCounters == null || possibleCounters.isEmpty()) {
            return null;
        }
        SpellAbility bestSA = null;
        int bestRestriction = Integer.MIN_VALUE;

        for (final SpellAbility sa : ComputerUtilAbility.getOriginalAndAltCostAbilities(possibleCounters, player)) {
            sa.setActivatingPlayer(player);
            if (StifleCombo.keepsForDreadnought(player, sa)) {
                continue; // the Stifle is for the Dreadnought's own trigger, not for whatever the opponent does first
            }
            // check everything necessary

            AiPlayDecision opinion = canPlayAndPayFor(sa);
            //PhaseHandler ph = game.getPhaseHandler();
            // System.out.printf("Ai thinks '%s' of %s @ %s %s >>> \n", opinion, sa, Lang.getPossesive(ph.getPlayerTurn().getName()), ph.getPhase());
            if (opinion == AiPlayDecision.WillPlay) {
                final int restrictionLevel = ComputerUtil.counterSpellRestriction(player, sa);
                if (bestSA == null || restrictionLevel > bestRestriction) {
                    bestRestriction = restrictionLevel;
                    bestSA = sa;
                }
            }
        }

        // TODO - "Look" at Targeted SA and "calculate" the threshold
        // if (bestRestriction < targetedThreshold) return false;
        return bestSA;
    }

    public SpellAbility predictSpellToCastInMain2(ApiType exceptSA) {
        return predictSpellToCastInMain2(exceptSA, true);
    }
    private SpellAbility predictSpellToCastInMain2(ApiType exceptSA, boolean handOnly) {
        if (!getBoolProperty(AiProps.PREDICT_SPELLS_FOR_MAIN2)) {
            return null;
        }

        final CardCollectionView cards = handOnly ? player.getCardsIn(ZoneType.Hand) :
                ComputerUtilAbility.getAvailableCards(game, player);

        List<SpellAbility> all = ComputerUtilAbility.getSpellAbilities(cards, player);

        if (ComputerUtilAbility.sortSpellAbilities(all)) { // put best spells first
            reportSortFallback(all);
        }

        for (final SpellAbility sa : ComputerUtilAbility.getOriginalAndAltCostAbilities(all, player)) {
            ApiType saApi = sa.getApi();

            if (saApi == ApiType.Counter || saApi == exceptSA) {
                continue;
            }
            sa.setActivatingPlayer(player);
            // TODO: this currently only works as a limited prediction of permanent spells.
            // Ideally this should cast canPlaySa to determine that the AI is truly able/willing to cast a spell,
            // but that is currently difficult to implement due to various side effects leading to stack overflow.
            Card host = sa.getHostCard();
            if (sa instanceof SpellPermanent && host != null && !host.isLand() && !ComputerUtil.castPermanentInMain1(player, sa) && ComputerUtilCost.canPayCost(sa, player, false)) {
                return sa;
            }
        }
        return null;
    }

    public boolean reserveManaSourcesForNextSpell(SpellAbility sa, SpellAbility exceptForSa) {
        return reserveManaSources(sa, null, false, true, exceptForSa);
    }
    public boolean reserveManaSources(SpellAbility sa) {
        return reserveManaSources(sa, PhaseType.MAIN2, false, false, null);
    }
    public boolean reserveManaSources(SpellAbility sa, PhaseType phaseType, boolean enemy) {
        return reserveManaSources(sa, phaseType, enemy, true, null);
    }
    public boolean reserveManaSources(SpellAbility sa, PhaseType phaseType, boolean enemy, boolean forNextSpell, SpellAbility exceptForThisSa) {
        AiCardMemory.MemorySet memSet = null;
        if (phaseType == null && forNextSpell) {
            memSet = AiCardMemory.MemorySet.HELD_MANA_SOURCES_FOR_NEXT_SPELL;
        } else if (phaseType != null) {
            switch (phaseType) {
                case MAIN2:
                    // TODO bail if profile chance 0 anyway
                    memSet = AiCardMemory.MemorySet.HELD_MANA_SOURCES_FOR_MAIN2;
                    break;
                case COMBAT_DECLARE_BLOCKERS:
                    memSet = enemy ? AiCardMemory.MemorySet.HELD_MANA_SOURCES_FOR_ENEMY_DECLBLK
                            : AiCardMemory.MemorySet.HELD_MANA_SOURCES_FOR_DECLBLK;
                    break;
                default:
                    System.out.println("Warning: unsupported mana reservation phase specified for reserveManaSources: "
                            + phaseType.name() + ", reserving until Main 2 instead. Consider adding support for the phase if needed.");
                    memSet = AiCardMemory.MemorySet.HELD_MANA_SOURCES_FOR_MAIN2;
                    break;
            }
        }

        ManaCostBeingPaid cost = ComputerUtilMana.calculateManaCost(sa.getPayCosts(), sa, player, true, 0, false);
        CardCollection manaSources = ComputerUtilMana.getManaSourcesToPayCost(cost, sa, player, false);

        if (manaSources == null || manaSources.isEmpty()) {
            return false;
        }

        // used for chained spells where two spells need to be cast in succession
        if (exceptForThisSa != null) {
            CardCollection exceptSources = ComputerUtilMana.getManaSourcesToPayCost(
                    ComputerUtilMana.calculateManaCost(exceptForThisSa.getPayCosts(), exceptForThisSa, player, true, 0, false),
                    exceptForThisSa, player, false);
            // the other spell needs these too, so they usually can't be promised to both
            if (exceptSources != null && !exceptSources.isEmpty() && manaSources.removeAll(exceptSources) && manaSources.isEmpty()) {
                return false;
            }
        }

        for (Card c : manaSources) {
            memory.rememberCard(c, memSet);
        }
        return true;
    }

    // Casts whose payment failed this phase (ComputerUtil.handlePlayingSpellAbility rolled them back). The rollback
    // puts the card back where it was cast from with its mana untapped, so the next choice sees the board it saw
    // before. A failure that came from a random draw (the convoke prediction) often succeeds at the next try, so a card
    // gets one retry; when the payability check and the payment disagree for good it would otherwise be picked again
    // at once, again and again until PhaseHandler's 999-actions cap (a skip lasts one choice, see
    // getSpellAbilityToPlay, and an alternative-cost copy never carries it). Two failures in a phase end its tries:
    // every spell and ability of the card is refused for the rest of the phase, as the stock failure branch skips
    // all of a card's spells. The map stays empty until a payment fails or a cast is given up before its payment
    // (rememberAbandonedCast), so until then the check at each cast is one isEmpty.
    private static final int FAILED_CAST_TRIES = 2;
    // (by card, for the phase; upstream's failedCasts above keeps its own by cast, for the turn)
    private final java.util.Map<Card, Integer> failedPayments = new java.util.HashMap<>();
    private int failedPaymentsTurn = -1;
    private forge.game.phase.PhaseType failedPaymentsPhase;
    // every failed payment counted here so far in the game: PlayerControllerAi.playWithRollback reads it to leave
    // such a cast to this rule, never also to upstream's failedCasts, whose one failure would end a card's tries for
    // the turn and take away the retry above
    private int rolledBackPayments;

    /** How many failed payments {@link #rememberFailedCast} has counted so far in the game. */
    public int getRolledBackPaymentCount() {
        return rolledBackPayments;
    }

    public void rememberFailedCast(final Card c) {
        rolledBackPayments++;
        countFailedCast(c);
    }

    /**
     * A cast ComputerUtil gave up before its payment (no Charm mode, too few deferred targets) counts toward the card's
     * tries as a failed payment does, whoever made the cast. It is no rolled-back payment: the game loop's
     * PlayerControllerAi.playWithRollback still keeps it in upstream's failedCasts as well, so that cast is refused for
     * the turn at its first failure, as upstream has it, and every play of the card for the phase at its second.
     */
    public void rememberAbandonedCast(final Card c) {
        countFailedCast(c);
    }

    private void countFailedCast(final Card c) {
        final forge.game.phase.PhaseHandler ph = game.getPhaseHandler();
        if (ph.getTurn() != failedPaymentsTurn || ph.getPhase() != failedPaymentsPhase) {
            failedPayments.clear();
            failedPaymentsTurn = ph.getTurn();
            failedPaymentsPhase = ph.getPhase();
        }
        failedPayments.merge(c, 1, Integer::sum);
    }

    private boolean castFailedThisPhase(final Card c) {
        if (failedPayments.isEmpty()) {
            return false;
        }
        final forge.game.phase.PhaseHandler ph = game.getPhaseHandler();
        if (ph.getTurn() != failedPaymentsTurn || ph.getPhase() != failedPaymentsPhase) {
            failedPayments.clear();
            return false;
        }
        return failedPayments.getOrDefault(c, 0) >= FAILED_CAST_TRIES;
    }

    private AiPlayDecision canPlayAndPayFor(final SpellAbility sa) {
        AITimeoutTracker.check();
        final Card host = sa.getHostCard();

        if (castFailedThisPhase(host)) {
            return AiPlayDecision.CantPlaySa;
        }

        if (sa.isSpell() && host != null && (SpecialCardAi.HierophantBioTitan.NAME.equals(host.getName())
                || SpecialCardAi.SkeletalScrying.NAME.equals(host.getName())
                || SpecialCardAi.ToxicDeluge.NAME.equals(host.getName())
                || SpecialCardAi.SickeningShoal.NAME.equals(host.getName()))) {
            // drop a counter X (Hierophant), a graveyard-exile X (Skeletal Scrying), a life X
            // (Toxic Deluge) or a hand-exile X (Sickening Shoal's pitch copy, which inherits the hard
            // cast's) left by an evaluation that declined after choosing it: canPlayFromHost checks
            // the additional cost against it before chooseX runs again
            sa.setXManaCostPaid(null);
        }

        if (sa instanceof Spell sp) {
            Card altHost = sp.canPlayFromHost();
            if (altHost == null) {
                return AiPlayDecision.CantPlaySa;
            }
            // state needs to be switched here so API checks evaluate the right face
            if (host != altHost) {
                sa.setHostCard(altHost);
            }
            altHost.setCastSA(sa);
        } else if (!sa.canPlay()) {
            return AiPlayDecision.CantPlaySa;
        }

        try {
            return canPlayAndPayForFace(sa);
        } finally {
            // in addition to engine some AI api can also switch host
            if (sa.getHostCard() != host) {
                sa.setHostCard(host);
            }
            if (sa.isSpell()) {
                host.setCastSA(null);
            }
        }
    }

    // This is for playing spells regularly (no Cascade/Ripple etc.)
    private AiPlayDecision canPlayAndPayForFace(final SpellAbility sa) {
        final Card host = sa.getHostCard();

        if (sa.hasParam("AICheckSVar") && !aiShouldRun(sa, sa, host, null)) {
            return AiPlayDecision.NeedsToPlayCriteriaNotMet;
        }

        // this is the "heaviest" check, which also sets up targets, defines X, etc.
        AiPlayDecision canPlay = canPlaySa(sa);

        if (canPlay != AiPlayDecision.WillPlay) {
            return canPlay;
        }

        // Dead-card batch 3, row 95: canPlaySa approved Ambitious Dragonborn (its renamed script
        // floor, NeedsToPlayVar:X GE3, is met); refuse an unpayable window here, before canPayCost's
        // isManaSourceReserved rolls. Placed after canPlaySa so B has done exactly A's evaluation
        // (its RNG draws and checkETBEffectsPreparedCard's SpellAbility copy) before the decline.
        // G1 under G4's contract, called not edited. A name compare: inert for every other SA. See
        // the class comment.
        if (sa.isSpell() && host != null && SpecialCardAi.AmbitiousDragonborn.NAME.equals(host.getName())
                && !sa.isCastFromPlayEffect() && !SpecialCardAi.CommanderCastCeiling.affordable(player, sa)) {
            return AiPlayDecision.CantAfford;
        }

        // Dead-card batch 4, row 17: Assassin Gauntlet. TapAllAi.chkDrawback lifted its enters-trigger
        // veto, so canPlaySa now approves the cast where A returned BadEtbEffects at saSideEffects, and
        // canPayCost below would run its test payment (isManaSourceReserved draws MyRandom per source)
        // in windows that cannot pay: a held game would re-roll with no visible cast. G1 refuses those
        // first, drawing nothing, as A's decline drew nothing after canPlaySa. Placed after canPlaySa so
        // B has made exactly A's evaluation (checkETBEffectsPreparedCard's copy and attach preference)
        // before the decline. Own paid casts (from hand, or a MayPlay caster's); a Play-effect cast never
        // comes here. G1 under G4's contract, called not edited. A name compare: inert for every other SA.
        if (sa.isSpell() && host != null && SpecialCardAi.AssassinGauntlet.NAME.equals(host.getName())
                && !sa.isCastFromPlayEffect() && !SpecialCardAi.CommanderCastCeiling.affordable(player, sa)) {
            return AiPlayDecision.CantAfford;
        }

        // Dead-card batch 4, row 26: Disruptor Flute. canPlaySa approved it (its window and chooser,
        // SpecialCardAi.DisruptorFlute.consider, answered at A's own decline point in
        // ChooseCardNameAi.doTriggerNoCost); refuse an unpayable window here, before canPayCost's
        // isManaSourceReserved rolls, as row 95 does. A flash card is consulted at every priority, so
        // without this every unpayable in-window consult would draw with no visible cast. Own paid
        // casts (from hand, or a MayPlay caster's); a Play-effect cast never comes here. G1 under G4's
        // contract, called not edited. A name compare: inert for every other SA.
        if (sa.isSpell() && host != null && SpecialCardAi.DisruptorFlute.NAME.equals(host.getName())
                && !sa.isCastFromPlayEffect() && !SpecialCardAi.CommanderCastCeiling.affordable(player, sa)) {
            return AiPlayDecision.CantAfford;
        }

        // Dead-card batch 4, row 32, part 32B2: Gifts Ungiven. In A its sub-ability's targeting
        // (ChangeZoneAi.hiddenOriginPlayDrawbackAI, a non-curse that could only target opponents) refused
        // every consult inside canPlaySa, so canPayCost never ran for it; with IsCurse$ on that sub (32A)
        // canPlaySa approves from Main 2 on, and an unpayable window would now draw canPayCost's
        // isManaSourceReserved rolls (the IsCurse-only prototype left A's stream 1-4 turns before its
        // first cast in all three logged games). G1 refuses those windows first, drawing nothing, as A's
        // decline drew nothing after canPlaySa. Placed after canPlaySa, so B has made exactly A's
        // evaluation first. Own paid casts (from hand, or a thief's or graveyard caster's regular cast);
        // a Play-effect cast never comes here. The paper name is compared here, not 32B1's
        // GiftsUngiven.NAME, so this block stands if 32B1 is reverted alone. G1 under G4's contract,
        // called not edited. A name compare: inert for every other SA.
        // Retry 1: the AI's own item on top of the stack (logged case 1716826 T10, Tymna's trigger).
        // There getSpellAbilityToPlay keeps a chosen SA only if it is the list's first copy SA (its
        // topOwnedByAI && !mustRespond test, copied here), so a Gifts approval is never a cast: it only
        // ran canPayCost's isManaSourceReserved rolls and shadowed a later copy SA (Twincast, Flare of
        // Duplication) that A's list walk reached. A refused every such consult inside canPlaySa and no B
        // canPlaySa consult draws, so declining right after it is A's draws and A's walk. Stack reads
        // only, before G1; the mustRespond windows (Sensei's Divining Top's AIRespondsToOwnAbility, an
        // Evoke sacrifice trigger) go on to G1 as before.
        if (sa.isSpell() && host != null && "Gifts Ungiven".equals(host.getName())
                && !sa.isCastFromPlayEffect()) {
            if (!game.getStack().isEmpty()) {
                final SpellAbility stackTop = game.getStack().peekAbility();
                if (stackTop.getActivatingPlayer().equals(player)
                        && !stackTop.hasParam("AIRespondsToOwnAbility")
                        && !(stackTop.isTrigger() && stackTop.getTrigger().isKeyword(Keyword.EVOKE))) {
                    return AiPlayDecision.AnotherTime;
                }
            }
            if (!SpecialCardAi.CommanderCastCeiling.affordable(player, sa)) {
                return AiPlayDecision.CantAfford;
            }
        }

        // Dead-card batch 4, row 9, part 9B2: Intuition. In A its sub-ability's targeting
        // (ChangeZoneAi.hiddenOriginPlayDrawbackAI, a non-curse that could only target opponents) refused
        // every consult inside canPlaySa, so canPayCost never ran for it; with IsCurse$ on that sub (9A)
        // canPlaySa approves from Main 2 on, and an unpayable window would now draw canPayCost's
        // isManaSourceReserved rolls (the IsCurse-only prototype left A's stream before its first cast in all
        // three logged games). G1 refuses those windows first, drawing nothing, as A's decline drew nothing
        // after canPlaySa (its stock considerMultiple fill, inside canPlaySa, draws alike in A and B). Placed
        // after canPlaySa, so B has made exactly A's evaluation first, and after W5's Gifts Ungiven block: its
        // own block, as rows 9 and 32 land in different builds. Own paid casts (from hand, or a thief's or
        // graveyard caster's regular cast); a Play-effect cast never comes here. The paper name is compared
        // here, not 9B1's IntuitionPile.NAME, so this block stands if 9B1 is reverted alone. G1 under G4's
        // contract, called not edited. A name compare: inert for every other SA.
        if (sa.isSpell() && host != null && "Intuition".equals(host.getName())
                && !sa.isCastFromPlayEffect() && !SpecialCardAi.CommanderCastCeiling.affordable(player, sa)) {
            return AiPlayDecision.CantAfford;
        }

        // Dead-card batch 4, row 59, part 59B: Abigale, Eloquent First-Year. canPlaySa approved the cast now that
        // its enters trigger may take no target (59A); A declined it inside canPlaySa (BadEtbEffects at
        // saSideEffects, after the same draws), so canPayCost never ran for it, and an unpayable window would now
        // draw canPayCost's isManaSourceReserved rolls with no visible cast. Refuse a window real mana cannot pay
        // ({W/B}{W/B}: G1 plus the hybrid pips, AbigaleEloquentFirstYear.affordable) here, drawing nothing. Placed
        // after canPlaySa, so B has made exactly A's evaluation first, and after the Intuition block (W7's
        // order: 9B2, then 59B). Own paid casts (from hand, or a thief's or graveyard caster's regular cast); a
        // Play-effect cast never comes here. G1 under G4's contract, called not edited. A name compare: inert for
        // every other SA. See the class comment.
        if (sa.isSpell() && host != null && SpecialCardAi.AbigaleEloquentFirstYear.NAME.equals(host.getName())
                && !sa.isCastFromPlayEffect() && !SpecialCardAi.AbigaleEloquentFirstYear.affordable(player, sa)) {
            return AiPlayDecision.CantAfford;
        }

        if (!ComputerUtilCost.canPayCost(sa, player, sa.isTrigger())) {
            // Dead-card batch 4, row 33's record: the handler wanted it and the mana refused it, written
            // after canPayCost drew. Passive: nothing but SpecialCardAi.DramaticReversal reads it.
            approvedUnaffordable.add(SpecialCardAi.DramaticReversal.Refused.of(sa, game));
            // for dependent costs with X, e.g. Repeal, which require a valid target to be specified before a decision can be made
            // on whether the cost can be paid, this can only be checked late after canPlaySa has been run (or the AI will misplay)
            return AiPlayDecision.CantAfford;
        }

        // check if enough left (pass memory indirectly because we don't want to include those)
        Set<Card> tappedForMana = AiCardMemory.getMemorySet(player, MemorySet.PAYS_TAP_COST);
        if (tappedForMana != null && !tappedForMana.isEmpty() &&
                !ComputerUtilCost.checkTapTypeCost(player, sa.getPayCosts(), host, sa, new CardCollection(tappedForMana))) {
            return AiPlayDecision.CantAfford;
        }

        // Dead-card batch 3, row 89: Signature Slam's chooser found no victim worth the card and
        // kept A's untargeted WillPlay, so canPlaySa and canPayCost above ran A's path, draws
        // included. A cast it here and MagicStack.add refused the paid chain for its untargeted
        // EachDamage sub, stranding the card on the stack. A name compare: inert for every other SA.
        if (SpecialCardAi.SignatureSlam.untargetedRegularCast(sa)) {
            return AiPlayDecision.TargetingFailed;
        }

        // Dead-card batch 4, row 5: The Water Crystal's mill. MillAi approved it from MAIN2 on and
        // canPayCost above ran A's test payment, draws included; TheWaterCrystal.holdMill keeps it
        // for the end step before our turn unless it empties a library now (and never mills nothing).
        // A name compare: inert for every other SA. See the class comment.
        if (SpecialCardAi.TheWaterCrystal.holdMill(player, sa)) {
            return AiPlayDecision.WaitForEndOfTurn;
        }

        // Dead-card batch 4, row 6 (Engulf the Shore, Evacuation). ChangeZoneAllAi prices a mass bounce
        // to hand by evaluateCreatureList, where our own commander is an ordinary body. canPlaySa and
        // canPayCost above ran A's path, draws included, so this veto changes only the cast A was about
        // to make; a decline in an unpayable window would have skipped canPayCost's draws (row 159).
        // A name compare inside declines: inert for every other SA. Draws nothing. See the class comment.
        if (SpecialCardAi.OwnCommanderBounce.declines(player, sa)) {
            return AiPlayDecision.CantPlayAi;
        }

        // Dead-card batch 4, row 76, part 76A: Flow of Knowledge. DrawAi approved it whatever the Island count, and
        // canPayCost above ran A's test payment, draws included; FlowOfKnowledge.holds keeps it until it draws five
        // (net +2 after the spell and its two discards). A name compare: inert for every other SA. Draws nothing.
        // See the class comment.
        if (SpecialCardAi.FlowOfKnowledge.holds(player, sa)) {
            return AiPlayDecision.AnotherTime;
        }

        // Dead-card batch 4, row 63, part 63F: Mnemonic Deluge. ChangeZoneAi's chooser (63A) found no pick and kept
        // the stock target, a Death by Dragons-shaped card (a targeted Token part with AITgts$ Opponent): its three free
        // copies would be aimed at us by TokenAi.doTriggerNoCost, so every other player would get the tokens. canPlaySa
        // and canPayCost above ran A's path, draws included, so this veto changes only the cast A was about to make
        // (B4-3). A name compare inside declines: inert for every other SA. Draws nothing. See the class comment.
        if (SpecialCardAi.MnemonicDeluge.declines(player, sa)) {
            return AiPlayDecision.CantPlayAi;
        }

        // Dead-card batch 4, row 77, part 77C: our own cast of an instant or sorcery (hand, flashback, Aminatou's
        // Augury's exile, Melek's library top) is refused when it takes our library under OwnLibraryFloor's margin.
        // canPlaySa and canPayCost above ran A's path, draws included, so this veto changes only the cast A was about
        // to make (B4-3). Placed last, after W4's 76A and W6's 63F and 68A. Inert unless OwnLibraryFloor is engaged.
        // Draws nothing. See the class comment.
        if (SpecialCardAi.OwnLibraryFloor.breachesCast(player, sa)) {
            return AiPlayDecision.CantPlayAi;
        }

        // Combo-aware AI, piece 1 (spec 2026-10-22-combo-aware-ai-core-design.md sections 6.2 and 6.4): our own
        // hand cast of Thassa's Oracle waits while a live line needs it and the go-off is not ready, and our own cast
        // of a wheel waits while it would put a live line's piece from our hand into the graveyard or exile (follow-up
        // F5) (ComboHold.vetoesCast). canPlaySa and canPayCost above ran A's path, draws included, so this veto changes
        // only the cast A was about to make. Inert for a deck holding no combo line. Draws nothing.
        if (ComboHold.vetoesCast(player, sa)) {
            return AiPlayDecision.AnotherTime;
        }

        // convoking would tap a creature the AI means to attack or block with, so it keeps it for combat
        final CardCollection convoked = new CardCollection(sa.getTappedForConvoke());
        if (!convoked.isEmpty() && game.getPhaseHandler().isPlayerTurn(player)
                && game.getPhaseHandler().getPhase().isBefore(PhaseType.COMBAT_DECLARE_ATTACKERS)
                && (convoked.anyMatch(c -> getPredictedCombat().isAttacking(c))
                || ComputerUtil.protectRecursion(sa, () -> ComputerUtilCard.getLikelyBlockers(player, null), CardCollection.EMPTY).anyMatch(convoked::contains))) {
            return AiPlayDecision.AnotherTime;
        }

        return AiPlayDecision.WillPlay;
    }

    public AiPlayDecision canPlaySa(SpellAbility sa) {
        if (hasFailedToCast(sa)) {
            return AiPlayDecision.CantPlayAi; // it could not be put on the stack earlier this turn
        }
        if (deckPlan(player).combos.outletMustWait(player, sa)) {
            return AiPlayDecision.AnotherTime; // a madness card would be thrown away at the wrong moment
        }
        if (deckPlan(player).combos.payoffMustWait(player, sa)) {
            return AiPlayDecision.AnotherTime; // Replenish on a graveyard without its enchantments is a wasted card
        }
        if (DredgeLine.mustWait(player, deckPlan(player).combos, sa)) {
            return AiPlayDecision.AnotherTime; // Breakthrough without the Diamond and a dredger only empties the hand
        }
        if (!checkAiSpecificRestrictions(sa)) {
            return AiPlayDecision.CantPlayAi;
        }
        // The non-mana half of Grell Philosopher's grant veto (the mana half is in
        // ComputerUtilMana.getAIPlayableMana): an ability its effect copied onto our Horrors keeps
        // the artifact's own cost, so "Sacrifice/Exile CARDNAME" now names the Horror. Nothing but
        // a Grell grant carries that origin, so this is inert for every other ability.
        if (SpecialCardAi.GrellPhilosopher.vetoGrantedAbility(sa)) {
            return AiPlayDecision.CantPlayAi;
        }
        if (sa instanceof WrappedAbility) {
            return canPlaySa(((WrappedAbility) sa).getWrappedAbility());
        }

        if (!sa.canCastTiming(player)) {
            return AiPlayDecision.TimingRestrictions;
        }

        final Card card = sa.getHostCard();

        // Trying to play a card that has Buyback without a Buyback cost, look for possible additional considerations
        if (getBoolProperty(AiProps.TRY_TO_PRESERVE_BUYBACK_SPELLS) && card.hasKeyword(Keyword.BUYBACK)
                && !sa.isBuyback() && !canPlaySpellWithoutBuyback(sa)) {
            return AiPlayDecision.NeedsToPlayCriteriaNotMet;
        }

        if (sa.getApi() != null) {
            String msg = "AiController:canPlaySa: AI checks for if can PlaySa";
            Breadcrumb bread = new Breadcrumb(msg);
            bread.setData("Api", sa.getApi().toString());
            bread.setData("Card", card.getName());
            bread.setData("SA", sa.toString());
            Sentry.addBreadcrumb(bread);

            // add Extra for debugging
            Sentry.setExtra("Card", card.getName());
            Sentry.setExtra("SA", sa.toString());

            boolean canPlay = SpellApiToAi.Converter.get(sa).canPlayWithSubs(player, sa).willingToPlay();

            // remove added extra
            Sentry.removeExtra("Card");
            Sentry.removeExtra("SA");

            if (!canPlay) {
                return AiPlayDecision.CantPlayAi;
            }
        } else {
            Cost payCosts = sa.getPayCosts();
            if (payCosts != null) {
                ManaCost mana = payCosts.getTotalMana();
                if (mana.countX() > 0) {
                    // Set PayX here to maximum value.
                    final int xPay = ComputerUtilCost.setMaxXValue(sa, player, sa.isTrigger());
                    if (xPay <= 0) {
                        return AiPlayDecision.CantAffordX;
                    }
                } else if (mana.isZero()) {
                    // if mana is zero, but card mana cost does have X, then something is wrong
                    ManaCost cardCost = card.getManaCost();
                    if (cardCost != null && cardCost.countX() > 0) {
                        return AiPlayDecision.CantPlayAi;
                    }
                }
            }
            // TODO before suspending some spells try to predict if relevant targets can be expected
            if ((sa.isPlotting() || sa.isForetelling() || sa.isKeyword(Keyword.SUSPEND)) && game.getPhaseHandler().getPhase().isBefore(PhaseType.MAIN2)) {
                // don't compete with more important SA AI wants to pay before main2
                return AiPlayDecision.WaitForMain2;
            }
        }
        // TODO maybe other location for this?
        if (!sa.isLegalAfterStack()) {
            return AiPlayDecision.AnotherTime;
        }
        Card spellHost = card;
        if (sa.isSpell()) {
            spellHost = CardCopyService.getLKICopy(spellHost);
            spellHost.setLKICMC(-1); // to reset the cmc
            spellHost.setLastKnownZone(game.getStackZone()); // need to add to stack to make check Restrictions respect stack cmc
            spellHost.setCastFrom(card.getZone());
        }
        if (!sa.checkRestrictions(spellHost, player)) {
            return AiPlayDecision.AnotherTime;
        }
        if (sa.usesTargeting()) {
            if (!sa.isTargetNumberValid() && sa.getTargetRestrictions().getNumCandidates(sa) == 0) {
                return AiPlayDecision.TargetingFailed;
            }
            if (!StaticAbilityMustTarget.meetsMustTargetRestriction(sa)) {
                return AiPlayDecision.TargetingFailed;
            }
        }

        return saSideEffects(spellHost, sa);
    }

    private AiPlayDecision saSideEffects(final Card card, final SpellAbility sa) {
        if (usesFullSimulation()) {
            return AiPlayDecision.WillPlay;
        }
        if (usesHybridSimulation()) {
            return OnePlaySafetyChecker.isAcceptable(player, sa) ? AiPlayDecision.WillPlay : AiPlayDecision.HybridSimRejected;
        }

        int damage = 0;
        Supplier<Boolean> loseCheck = () -> !player.cantLoseForZeroOrLessLife() && player.canLoseLife();
        if (!sa.isLandAbility() && loseCheck.get()) {
            loseCheck = () -> true;
            damage = ComputerUtil.getDamageForPlaying(player, sa);
            if (damage >= player.getLife()) {
                // TODO even if it doesn't kill AI lower the score
                return AiPlayDecision.CurseEffects;
            }
        }

        if (!sa.isLandAbility() && !sa.isSpell()) {
            return AiPlayDecision.WillPlay;
        }

        if (card.isPermanent() && !sa.isMutate()) {
            if (loseCheck.get()) {
                damage += ComputerUtil.getDamageFromETB(player, card);
                if (damage >= player.getLife()) {
                    return AiPlayDecision.BadEtbEffects;
                }
            }
            if (!sa.isLandAbility() && !checkETBEffects(card, sa, null)) {
                return AiPlayDecision.BadEtbEffects;
            }
        }

        if ("True".equals(card.getSVar("NonStackingEffect")) && ComputerUtilCard.isNonDisabledCardInPlay(player, card.getName())) {
            return AiPlayDecision.DoesntImpactGame;
        }

        if (checkCurseEffects(sa)) {
            return AiPlayDecision.CurseEffects;
        }

        return ComputerUtilCard.checkNeedsToPlayReqs(card, sa);
    }

    private boolean canPlaySpellWithoutBuyback(SpellAbility sa) {
        int copies = CardLists.count(player.getCardsIn(ZoneType.Hand), CardPredicates.nameEquals(sa.getHostCard().getName()));
        // Have two copies : allow
        if (copies >= 2) {
            return true;
        }

        // About to lose game : allow
        if (ComputerUtil.aiLifeInDanger(player, true, 0)) {
            return true;
        }

        Cost costWithBuyback = sa.getPayCosts().copy();
        for (OptionalCostValue opt : GameActionUtil.getOptionalCostValues(sa)) {
            if (opt.getType() == OptionalCost.Buyback) {
                costWithBuyback.add(opt.getCost());
            }
        }
        costWithBuyback = CostAdjustment.adjust(costWithBuyback, sa, false);
        if (costWithBuyback.hasSpecificCostType(CostPayLife.class)
                || costWithBuyback.hasSpecificCostType(CostDiscard.class)
                || costWithBuyback.hasSpecificCostType(CostSacrifice.class)) {
            // won't be able to afford buyback any time soon
            // if Buyback cost includes sacrifice, life, discard
            return true;
        }

        int neededMana = 0;
        if (costWithBuyback.getCostMana() != null) {
            neededMana = costWithBuyback.getCostMana().getMana().getCMC();
        }
        // Memory Crystal-like effects need special handling
        for (Card c : game.getCardsIn(ZoneType.Battlefield)) {
            for (StaticAbility s : c.getStaticAbilities()) {
                if (s.checkMode(StaticAbilityMode.ReduceCost)
                        && "Spell.Buyback".equals(s.getParam("ValidSpell"))) {
                    neededMana -= AbilityUtils.calculateAmount(c, s.getParam("Amount"), s);
                }
            }
        }
        if (neededMana < 0) {
            neededMana = 0;
        }

        int hasMana = getAvailableManaEstimate(player, false);
        if (hasMana < neededMana - 1) {
            return true;
        }

        return false;
    }

    public CardCollection getCardsToDiscard(final int numDiscard, final String[] uTypes, final SpellAbility sa) {
        return getCardsToDiscard(numDiscard, uTypes, sa, CardCollection.EMPTY);
    }
    public CardCollection getCardsToDiscard(final int numDiscard, final String[] uTypes, final SpellAbility sa, final CardCollectionView exclude) {
        boolean noFiltering = sa != null && "DiscardCMCX".equals(sa.getParam("AILogic")); // list AI logic for which filtering is taken care of elsewhere
        CardCollection hand = new CardCollection(player.getCardsIn(ZoneType.Hand));
        hand.removeAll(exclude);
        if (uTypes != null && sa != null && !noFiltering) {
            hand = CardLists.getValidCards(hand, uTypes, sa.getActivatingPlayer(), sa.getHostCard(), sa);
        }
        return getCardsToDiscard(numDiscard, numDiscard, hand, sa);
    }

    public CardCollection getCardsToDiscard(final int min, final int max, final CardCollection validCards, final SpellAbility sa) {
        if (validCards.size() > min) {
            final ComboPlan combos = deckPlan(player).combos;
            // Combo-aware AI, piece 1 (spec 2026-10-22-combo-aware-ai-core-design.md sections 6.2 and 6.4): both of
            // upstream's deciders below read the cards ComboHold.spare leaves, as chooseCardsToDiscard's own pick does,
            // so neither throws away a live combo line's piece, and its fall-through below spares again. Inert (the
            // list itself) for a deck holding no combo line. Draws nothing.
            final CardCollection spared = ComboHold.spare(player, validCards, min);
            // a deck that wants creatures in the graveyard throws away the right ones
            final CardCollection forCombo = combos.preferredDiscards(player, spared, min);
            if (forCombo != null) {
                return forCombo;
            }
            // and no deck throws away its win card while something else will do. When too few cards are left without
            // the spared ones, a pair's piece goes before a line's: chooseCardsToDiscard returns a list of min cards
            // unread, so it is handed this one only when it holds no spared piece
            final CardCollection safe = deckPlan(player).commander.withoutProtected(player,
                    combos.withoutProtected(spared));
            if (safe.size() >= min && safe.size() < spared.size()) {
                return chooseCardsToDiscard(min, max, safe, sa);
            }
        }
        return chooseCardsToDiscard(min, max, validCards, sa);
    }

    private CardCollection chooseCardsToDiscard(int min, final int max, CardCollection validCards, final SpellAbility sa) {
        if (validCards.size() <= min) {
            return validCards; //return all valid cards since they will be discarded without filtering needed
        }
        // Combo-aware AI, piece 1 (spec 2026-10-22-combo-aware-ai-core-design.md sections 6.2 and 6.4): a live
        // combo line's pieces leave the choice while more than min cards stay (ComboHold.spare: a new list when it
        // drops any, so the caller's is never changed). Inert for a deck holding no combo line. Draws
        // nothing.
        validCards = ComboHold.spare(player, validCards, min);

        Card sourceCard = null;
        final CardCollection discardList = new CardCollection();
        int count = 0;
        if (sa != null) {
            String logic = sa.getParamOrDefault("AILogic", "");
            sourceCard = sa.getHostCard();
            if ("Always".equals(logic) && !validCards.isEmpty()) {
                min = 1;
            } else if (logic.startsWith("UnlessAtLife.")) {
                int threshold = AbilityUtils.calculateAmount(sourceCard, logic.substring(logic.indexOf(".") + 1), sa);
                if (player.getLife() <= threshold) {
                    min = 1;
                }
            } else if ("VolrathsShapeshifter".equals(logic)) {
                return SpecialCardAi.VolrathsShapeshifter.targetBestCreature(player, sa);
            } else if ("DiscardCMCX".equals(logic)) {
                final int cmc = sa.getXManaCostPaid();
                CardCollection discards = CardLists.filter(player.getCardsIn(ZoneType.Hand), CardPredicates.hasCMC(cmc));
                if (discards.isEmpty()) {
                    return null;
                }
                return new CardCollection(ComputerUtilCard.getWorstAI(discards));
            } else if ("NantukoCultivator".equals(logic)) {
                // Optional land discard (min 0): the choose loops below run min times and discard
                // nothing. Discard the spare lands instead; see SpecialCardAi.NantukoCultivator.
                return SpecialCardAi.NantukoCultivator.chooseLands(player, validCards, max, sa);
            }
            // Firestorm (dead-card batch 4, row 34): our own hand cast discards what its plan priced: the
            // spare cards first, then (the lethal line only) the rest by lowest mana value in hand order.
            // Draws nothing (the stock picker's replay-card Aggregates.random is never reached). A name
            // compare with the owner's own cast from hand: inert for every other discard.
            if (SpecialCardAi.Firestorm.paysOwnDiscard(player, sa)) {
                return SpecialCardAi.Firestorm.chooseDiscards(player, validCards, min);
            }

            if (sa.hasParam("AnyNumber")) {
                if ("DiscardUncastableAndExcess".equals(sa.getParam("AILogic"))) {
                    CardCollection discards = new CardCollection();
                    final CardCollectionView inHand = player.getCardsIn(ZoneType.Hand);
                    final int numLandsOTB = CardLists.count(inHand, CardPredicates.LANDS);
                    int numOppInHand = 0;
                    for (Player p : player.getGame().getPlayers()) {
                        if (p.getCardsIn(ZoneType.Hand).size() > numOppInHand) {
                            numOppInHand = p.getCardsIn(ZoneType.Hand).size();
                        }
                    }
                    for (Card c : inHand) {
                        if (c.hasSVar("DoNotDiscardIfAble") || c.hasSVar("IsReanimatorCard")) {
                            continue;
                        }
                        if (c.isCreature() && !ComputerUtilMana.hasEnoughManaSourcesToCast(c.getSpellPermanent(), player)) {
                            discards.add(c);
                        }
                        if ((c.isLand() && numLandsOTB >= 5) || (c.getFirstSpellAbility() != null && !ComputerUtilMana.hasEnoughManaSourcesToCast(c.getFirstSpellAbility(), player))) {
                            if (discards.size() + 1 <= numOppInHand) {
                                discards.add(c);
                            }
                        }
                    }
                    return discards;
                }
            }
        }

        // look for good discards
        while (count < min) {
            Card prefCard = null;
            if (sa != null && sa.getActivatingPlayer() != null && sa.getActivatingPlayer().isOpponentOf(player)) {
                for (Card c : validCards) {
                    if (c.hasSVar("DiscardMeByOpp")) {
                        prefCard = c;
                        break;
                    }
                }
            }
            if (prefCard == null) {
                prefCard = ComputerUtil.getCardPreference(player, sourceCard, "DiscardCost", validCards);
                // TODO use DiscardMe:0 instead so each card without is just treated as 1
                if (prefCard != null && prefCard.hasSVar("DoNotDiscardIfAble")) {
                    prefCard = null;
                }
            }
            if (prefCard != null) {
                discardList.add(prefCard);
                validCards.remove(prefCard);
                count++;
            } else {
                break;
            }
        }

        final int discardsLeft = min - count;

        // choose rest
        for (int i = 0; i < discardsLeft; i++) {
            if (validCards.isEmpty()) {
                continue;
            }
            final int numLandsInPlay = CardLists.count(player.getCardsIn(ZoneType.Battlefield), CardPredicates.LANDS_PRODUCING_MANA);
            final CardCollection landsInHand = CardLists.filter(validCards, CardPredicates.LANDS);
            final int numLandsInHand = landsInHand.size();

            // Discard a land
            boolean canDiscardLands = numLandsInHand > 3 || (numLandsInHand > 2 && numLandsInPlay > 0)
                    || (numLandsInHand > 1 && numLandsInPlay > 2) || (numLandsInHand > 0 && numLandsInPlay > 5);

            if (canDiscardLands) {
                discardList.add(landsInHand.get(0));
                validCards.remove(landsInHand.get(0));
            } else { // Discard other stuff
                CardLists.sortByCmcDesc(validCards);
                // Uncastable means out of reach beyond the near term: the permanents that make mana
                // (manaPermanentsInPlay: lands as before, and mana rocks and creatures now too), a land drop
                // from the hand, and one more mana -- the window ComputerUtil.getBestNearTermDiscardThreat
                // gives an opponent's hand. A card kept inside it goes to the worst-card fallback, which keeps
                // the mana sources while the AI is short (keepManaSourcesWhileShort).
                int nearTermMana = manaPermanentsInPlay() + 1;
                if (numLandsInHand > 0) {
                    nearTermMana++;
                }

                // Discard unplayable card (checks by CMC)
                // But check if there is a card in play that allows casting spells for free!
                // if yes, nothing is unplayable based on CMC alone
                boolean discardedUnplayable = false;
                boolean freeCastAllowed = ComputerUtilCost.isFreeCastAllowedByPermanent(player, null);

                for (int j = 0; j < validCards.size(); j++) {
                    if ((validCards.get(j).getCMC() > nearTermMana || freeCastAllowed) && !validCards.get(j).hasSVar("DoNotDiscardIfAble")) {
                        discardList.add(validCards.get(j));
                        validCards.remove(validCards.get(j));
                        discardedUnplayable = true;
                        break;
                    } else if (validCards.get(j).getCMC() <= nearTermMana) {
                        // cut short to avoid looping over cards which are guaranteed not to fit the criteria
                        break;
                    }
                }

                if (!discardedUnplayable) {
                    // discard worst card; a connive keeps its engines (ConniveAi.chooseDiscard), and the AI keeps
                    // its mana rocks and mana creatures while it is short of mana (keepManaSourcesWhileShort)
                    final CardCollection worstOf = keepManaSourcesWhileShort(validCards);
                    Card worst = sa != null && sa.getApi() == ApiType.Connive
                            ? ConniveAi.chooseDiscard(worstOf) : ComputerUtilCard.getWorstAI(worstOf);
                    if (worst == null) {
                        // there were only instants and sorceries, and maybe cards that are not good to discard, so look
                        // for more discard options
                        worst = ComputerUtilCard.getCheapestSpellAI(validCards);
                    }
                    if (worst == null && !validCards.isEmpty()) {
                        // still nothing chosen, so choose the first thing that works, trying not to make DoNotDiscardIfAble
                        // discards
                        for (Card c : validCards) {
                            if (!c.hasSVar("DoNotDiscardIfAble")) {
                                worst = c;
                                break;
                            }
                        }
                        // Only DoNotDiscardIfAble cards? If we have a duplicate for something, discard it
                        if (worst == null) {
                            for (Card c : validCards) {
                                if (CardLists.count(player.getCardsIn(ZoneType.Hand), CardPredicates.nameEquals(c.getName())) > 1) {
                                    worst = c;
                                    break;
                                }
                            }
                            if (worst == null) {
                                // Otherwise just grab a random card and discard it
                                worst = Aggregates.random(validCards);
                            }
                        }
                    }
                    discardList.add(worst);
                    validCards.remove(worst);
                }
            }
        }
        return discardList;
    }

    /**
     * The cards getCardsToDiscard's worst-card fallback picks from: validCards itself or, while the AI is
     * short of mana, validCards without its nonland mana sources (a mana rock or a mana creature,
     * isNonlandManaSource), as long as a nonland card is left to discard in their place.
     * <p>
     * The fallback is ComputerUtilCard.getWorstAI, which discards the cheapest artifact or enchantment
     * before anything else, so a hand's Sol Ring, Talisman or Signet went first whenever no card counted
     * as uncastable; a connive's ConniveAi.chooseDiscard takes the cheapest nonland card, as often a mana
     * rock. Replays of 2.0.15+7a241d90134 show both (Arcane Signet, Mind Stone, a Talisman), and a wider
     * uncastable window made it the price of every expensive card kept: Sol Ring for Time Wipe, a
     * Talisman for Kang Prime, Everflowing Chalice for Memory Plunder (2.0.15+db062768f34).
     * <p>
     * Short of mana: the permanents the AI controls that make mana (manaPermanentsInPlay) are fewer than
     * the mana value of the most expensive other nonland card in its hand, or than the profile's
     * SCRY_NUM_LANDS_TO_NOT_NEED_MORE, the count of mana-producing lands at which it stops wanting more
     * (Default 7). Past both, a mana source is one more cheap card and the stock choice stands.
     * <p>
     * With no mana source among validCards, with only lands beside them, or with mana enough, this returns
     * validCards itself, and the fallback runs exactly as before. Draws no RNG and writes nothing. The later
     * fallbacks (getCheapestSpellAI, which never takes a permanent; the first card; a duplicate;
     * Aggregates.random) keep validCards.
     */
    private CardCollection keepManaSourcesWhileShort(final CardCollection validCards) {
        final CardCollection others = CardLists.filter(validCards, c -> !isNonlandManaSource(c));
        if (others.size() == validCards.size() || !others.anyMatch(c -> !c.isLand())) {
            return validCards;
        }
        int need = getIntProperty(AiProps.SCRY_NUM_LANDS_TO_NOT_NEED_MORE);
        for (final Card c : player.getCardsIn(ZoneType.Hand)) {
            if (!c.isLand() && !isNonlandManaSource(c)) {
                need = Math.max(need, c.getCMC());
            }
        }
        return manaPermanentsInPlay() < need ? others : validCards;
    }

    // A nonland card that makes mana once it is in play: a mana rock or a mana creature (Sol Ring, a
    // Talisman, Llanowar Elves). A spell that adds mana as it resolves (Dark Ritual) has no mana ability.
    private static boolean isNonlandManaSource(final Card c) {
        return !c.isLand() && !c.getManaAbilities().isEmpty();
    }

    // The permanents the AI controls that make mana: the lands that do (LANDS_PRODUCING_MANA) and every
    // other permanent with a mana ability, each counted once, as a land is.
    private int manaPermanentsInPlay() {
        final CardCollectionView inPlay = player.getCardsIn(ZoneType.Battlefield);
        return CardLists.count(inPlay, CardPredicates.LANDS_PRODUCING_MANA)
                + CardLists.count(inPlay, AiController::isNonlandManaSource);
    }

    public boolean confirmAction(SpellAbility sa, PlayerActionConfirmMode mode, String message, Map<String, Object> params) {
        ApiType api = sa == null ? null : sa.getApi();

        // Abilities without api may also use this routine, However they should provide a unique mode value ?? How could this work?
        if (sa == null || api == null) {
            String exMsg = String.format("AI confirmAction does not know what to decide about %s mode (%s is null).",
                    mode, sa == null ? "SA" : "API");
            throw new IllegalArgumentException(exMsg);
        }
        return SpellApiToAi.Converter.get(api).confirmAction(player, sa, mode, message, params);
    }

    public boolean confirmBidAction(SpellAbility sa, PlayerActionConfirmMode mode, String message, int bid, Player winner) {
        if (mode != null) switch (mode) {
            case BidLife:
                if (sa.hasParam("AIBidMax")) {
                    return !player.equals(winner) && bid < Integer.parseInt(sa.getParam("AIBidMax")) && player.getLife() > bid + 5;
                }
                return false;
            default:
                return false;
        }
        return false;
    }

    public boolean confirmStaticApplication(Card hostCard, String logic) {
        return true;
    }

    public String getProperty(AiProps propName) {
        return AiProfileUtil.getProperty(getPlayer(), propName);
    }
    public int getIntProperty(AiProps propName) {
        return AiProfileUtil.getIntProperty(getPlayer(), propName);
    }
    public boolean getBoolProperty(AiProps propName) {
        return AiProfileUtil.getBoolProperty(getPlayer(), propName);
    }

    public AiPlayDecision canPlayFromEffectAI(Spell spell, boolean mandatory, boolean withoutPayingManaCost) {
        if (spell instanceof SpellApiBased) {
            boolean chance;
            if (withoutPayingManaCost) {
                chance = SpellApiToAi.Converter.get(spell).doTriggerNoCostWithSubs(player, spell, mandatory).willingToPlay();
            } else {
                chance = SpellApiToAi.Converter.get(spell).doTrigger(player, spell, mandatory);
            }
            if (!chance) {
                return AiPlayDecision.TargetingFailed;
            }
        }

        if (mandatory) {
            return AiPlayDecision.WillPlay;
        }

        SpellAbility abilityToCheck = spell;
        if (usesHybridSimulation() && withoutPayingManaCost && !spell.hasParam("WithoutManaCost")) {
            abilityToCheck = spell.copyWithNoManaCost(player);
        }

        return saSideEffects(spell.getHostCard(), abilityToCheck);
    }

    // declares blockers for given defender in a given combat
    public void declareBlockersFor(Player defender, Combat combat) {
        final long id = AITimeoutTracker.key(game.getId(), player.getId());
        final long timeoutNanos = TimeUnit.SECONDS.toNanos(game.getAITimeout());
        AITimeoutTracker.begin(id, timeoutNanos);
        Object limit = null;
        try {
            // constructed before arming and inside the try: a throwing constructor can no longer leak begin()/arm()
            final AiBlockController block = new AiBlockController(defender, defender != player);
            limit = AITimeoutTracker.arm(AITimeoutTracker.deadlineNanos(id, timeoutNanos), false);
            try {
                // When player != defender, AI should declare blockers for its benefit.
                block.assignBlockersForCombat(combat);
            } catch (AITimeoutTracker.Expired e) {
                // out of time: keep the blocks decided so far (each block is added atomically, so combat stays consistent)
                AITimeoutTracker.disarm(limit); // the fallback below must not be cut short again
                limit = null;
                block.completeRequiredBlocks(combat); // creatures that must block still do
            }
        } finally {
            if (limit != null) {
                AITimeoutTracker.disarm(limit);
            }
            AITimeoutTracker.end(id);
        }
    }

    /** In a simulated game: the attack to declare instead of asking the heuristics (set by a rollout, used once). */
    private java.util.Map<Card, GameEntity> attackOverride;

    public void setAttackOverride(final java.util.Map<Card, GameEntity> attackers) {
        attackOverride = attackers;
    }

    private void declareOverriddenAttackers(final Combat combat) {
        final java.util.Map<Card, GameEntity> attackers = attackOverride;
        attackOverride = null;
        for (final java.util.Map.Entry<Card, GameEntity> e : attackers.entrySet()) {
            if (CombatUtil.canAttack(e.getKey(), e.getValue())) {
                combat.addAttacker(e.getKey(), e.getValue());
            }
        }
    }

    /**
     * Monte Carlo for the attack: only on the AI's own declaration in the real game, with the heuristics' attack
     * already in {@code combat}. Replaces it by another attack set when rollouts clearly prefer one.
     */
    private void refineAttackWithFlatMonteCarlo(final Player attacker, final Combat combat) {
        if (!(flatMcOverride != null ? flatMcOverride : game.getAIFlatMonteCarlo()) || flatMcOff() || simMode != null
                || !getBoolProperty(AiProps.FLAT_MC_ENABLED)
                || !(flatMcAttackOverride != null ? flatMcAttackOverride : getBoolProperty(AiProps.FLAT_MC_ATTACK) || isFlatMonteCarloMax() || isFlatMonteCarloLite())
                || attacker != player || !game.getPhaseHandler().is(PhaseType.COMBAT_DECLARE_ATTACKERS)) {
            return;
        }
        final FlatMonteCarloPicker.Config cfg = flatMonteCarloConfig();
        if (cfg == null) {
            return;
        }
        final java.util.Map<Card, GameEntity> before = new java.util.LinkedHashMap<>();
        for (final Card c : combat.getAttackers()) {
            before.put(c, combat.getDefenderByAttacker(c));
        }
        final FlatMonteCarloPicker.Result result = FlatMonteCarloPicker.chooseAttack(player, combat, cfg);
        lastFlatMcResult = result;
        FlatMonteCarloStats.record(result);
        flatMcTurnSpentNanos += result.elapsedNanos;
        noteFlatMcOutcome(result.reason);
        final boolean changed = result.decidedByRollouts && result.attackers != null && !result.attackers.equals(before);
        FlatMonteCarloStats.noteAttackResult(result, changed);
        if (!changed) {
            return;
        }
        combat.clearAttackers();
        for (final java.util.Map.Entry<Card, GameEntity> e : result.attackers.entrySet()) {
            if (CombatUtil.canAttack(e.getKey(), e.getValue())) {
                combat.addAttacker(e.getKey(), e.getValue());
            }
        }
    }

    public void declareAttackers(Player attacker, Combat combat) {
        if (attackOverride != null) {
            declareOverriddenAttackers(combat);
            return;
        }
        // 12/2/10(sol) the decision making here has moved to getAttackers()
        final AiAttackController aiAtk = new AiAttackController(attacker);
        final long id = AITimeoutTracker.key(game.getId(), player.getId());
        final long timeoutNanos = TimeUnit.SECONDS.toNanos(game.getAITimeout());
        AITimeoutTracker.begin(id, timeoutNanos);
        Object limit = null;
        try {
            limit = AITimeoutTracker.arm(AITimeoutTracker.deadlineNanos(id, timeoutNanos), false);
            lastAttackAggression = aiAtk.declareAttackers(combat);

            aiAtk.reinforceWithBanding(combat);
            refineAttackWithFlatMonteCarlo(attacker, combat);
        } catch (AITimeoutTracker.Expired e) {
            // out of time: attack with what was decided so far; the validity check below repairs an illegal declaration
        } finally {
            if (limit != null) {
                AITimeoutTracker.disarm(limit);
            }
            AITimeoutTracker.end(id);
        }

        // Breath of Fury on our battlefield (SpecialCardAi.BreathOfFury.steerAttack): its host attacks only when the
        // chain from it kills or pays, with every body that chain counted; otherwise it is held home. Our own
        // declaration only: under "you choose which creatures attack" the other player's Breath keeps the stock
        // attack. Inert without the Aura (one isCardInPlay scan). Every change is validated and undone if it breaks
        // the declaration; no attacker with an attack cost is ever added (removeUnpayableAttackers stays the judge).
        if (attacker.equals(player)) {
            SpecialCardAi.BreathOfFury.steerAttack(attacker, combat);
        }

        // Per CR 508.1d, the decision to pay attack costs (e.g. Propaganda)
        // is made at declaration time. Remove attackers the AI can't pay for.
        // Correctness-critical and cheap, so it also runs when the deadline fired (outside the armed region).
        removeUnpayableAttackers(combat);

        // if invalid: just try an attack declaration that we know to be legal
        if (!CombatUtil.validateAttackers(combat)) {
            combat.clearAttackers();
            final Map<Card, GameEntity> legal = combat.getAttackConstraints().getLegalAttackers().getLeft();
            System.err.println("AI Attack declaration invalid, defaulting to: " + legal);
            for (final Map.Entry<Card, GameEntity> mandatoryAttacker : legal.entrySet()) {
                combat.addAttacker(mandatoryAttacker.getKey(), mandatoryAttacker.getValue());
            }
            if (!CombatUtil.validateAttackers(combat)) {
                aiAtk.declareAttackers(combat);
            }
        }
    }

    private void removeUnpayableAttackers(Combat combat) {
        for (Card attacker : combat.getAttackers().threadSafeIterable()) {
            Cost attackCost = CombatUtil.getAttackCost(game, attacker, combat.getDefenderByAttacker(attacker));
            if (attackCost == null) {
                continue;
            }
            SpellAbility fakeSA = new SpellAbility.EmptySa(attacker, attacker.getController());
            fakeSA.setCardState(attacker.getCurrentState());
            fakeSA.setPayCosts(attackCost);
            fakeSA.setSVar("X", "0");
            if (!ComputerUtilCost.canPayCost(attackCost, fakeSA, player, true)) {
                combat.removeFromCombat(attacker);
            }
        }
    }

    private List<SpellAbility> singleSpellAbilityList(SpellAbility sa) {
        if (sa == null) {
            return null;
        }
        return Lists.newArrayList(sa);
    }

    /**
     * One "thinking session" per priority: every nested AI step shares the same deadline and gives up
     * (= the AI passes priority) the moment the timer runs out.
     */
    public List<SpellAbility> chooseSpellAbilityToPlay() {
        final long id = AITimeoutTracker.key(game.getId(), player.getId());
        final long timeoutNanos = TimeUnit.SECONDS.toNanos(game.getAITimeout());
        AITimeoutTracker.begin(id, timeoutNanos);
        final Object limit = AITimeoutTracker.arm(AITimeoutTracker.deadlineNanos(id, timeoutNanos), false);
        try {
            if (SimAudit.ENABLED) {
                SimAudit.checkpoint(game, player);
            }
            final List<SpellAbility> chosen = chooseSpellAbilityToPlayImpl();
            if (SimAudit.ENABLED) {
                SimAudit.afterChoice(game, player, chosen);
            }
            return chosen;
        } catch (AITimeoutTracker.Expired e) {
            return null; // out of time: pass priority
        } finally {
            AITimeoutTracker.disarm(limit);
            AITimeoutTracker.end(id);
        }
    }

    /**
     * Which land to play, decided by rollouts when there is a real choice (two or more different lands in hand that
     * can be played): an untapped land when something can be cast with it this turn, a tapped one when the turn has
     * no use for the mana, the colour the hand needs. The heuristic land stays the default and needs a clear margin to
     * be replaced.
     */
    private List<SpellAbility> refineLandChoice(final CardCollection playable, final List<SpellAbility> heuristicAbilities) {
        if (!usesFlatMonteCarlo(false) || playable.size() < 2) {
            return heuristicAbilities;
        }
        final FlatMonteCarloPicker.Config cfg = flatMonteCarloConfig();
        if (cfg == null || !cfg.landChoice) {
            return heuristicAbilities;
        }
        final Set<String> names = new HashSet<>();
        final List<SpellAbility> candidates = new ArrayList<>();
        final Card heuristicLand = heuristicAbilities.get(0).getHostCard();
        names.add(heuristicLand.getName());
        candidates.add(heuristicAbilities.get(0));
        for (final Card land : playable) {
            if (candidates.size() >= 3 || !names.add(land.getName())) {
                continue;
            }
            for (final SpellAbility sa : land.getAllPossibleAbilities(player, true)) {
                if (sa.isLandAbility()) {
                    candidates.add(sa);
                    break;
                }
            }
        }
        if (candidates.size() < 2) {
            return heuristicAbilities; // all the same land
        }
        cfg.holdForbidden = true;
        cfg.considerHolding = false;
        cfg.targetVariants = 0;
        cfg.extraCandidates = 0;
        try {
            final FlatMonteCarloPicker.Result result = FlatMonteCarloPicker.choose(player, candidates, Collections.emptyList(), cfg, null,
                    FlatMonteCarloPicker.Mode.MAIN);
            lastFlatMcResult = result;
            FlatMonteCarloStats.record(result);
            FlatMonteCarloStats.noteLandChoice(result.decidedByRollouts, result.choice != candidates.get(0));
            flatMcTurnSpentNanos += result.elapsedNanos;
            noteFlatMcOutcome(result.reason);
            if (result.choice != null && result.choice != candidates.get(0)) {
                final List<SpellAbility> chosen = new ArrayList<>();
                chosen.add(result.choice);
                return chosen;
            }
        } catch (final AITimeoutTracker.Expired e) {
            throw e;
        } catch (final RuntimeException e) {
            noteFlatMcOutcome("error");
            FlatMonteCarloStats.noteFailure("land: " + e);
        }
        return heuristicAbilities;
    }

    private List<SpellAbility> chooseSpellAbilityToPlayImpl() {
        AiCache.clear();
        // Reset cached predicted combat, as it may be stale. It will be
        // re-created if needed and used for any AI logic that needs it.
        predictedCombat = null;
        // Also reset predicted combat for next turn here
        predictedCombatNextTurn = null;

        // Reset priority mana reservation that's meant to work for one spell only
        memory.clearMemorySet(AiCardMemory.MemorySet.HELD_MANA_SOURCES_FOR_NEXT_SPELL);

        if (usesFullSimulation()) {
            return singleSpellAbilityList(simPicker.chooseSpellAbilityToPlay(null));
        }

        CardCollection playBeforeLand = CardLists.filter(
                player.getCardsIn(ZoneType.Hand), CardPredicates.hasSVar("PlayBeforeLandDrop")
        );
        if (!playBeforeLand.isEmpty()) {
            SpellAbility wantToPlayBeforeLand = chooseSpellAbilityToPlayFromList(
                    ComputerUtilAbility.getSpellAbilities(playBeforeLand, player), false
            );
            if (wantToPlayBeforeLand != null) {
                return singleSpellAbilityList(wantToPlayBeforeLand);
            }
        }

        CardCollection landsWannaPlay = ComputerUtilAbility.getAvailableLandsToPlay(game, player);
        if (landsWannaPlay != null) {
            landsWannaPlay = filterLandsToPlay(landsWannaPlay);
            if (landsWannaPlay != null && !landsWannaPlay.isEmpty()) {
                // TODO search for other land it might want to play?
                Card land = chooseBestLandToPlay(landsWannaPlay);
                if (land != null && (!game.getPhaseHandler().is(PhaseType.MAIN1) || !isSafeToHoldLandDropForMain2(land))) {
                    final List<SpellAbility> abilities = land.getAllPossibleAbilities(player, true);
                    // skip non Land Abilities
                    abilities.removeIf(sa -> !sa.isLandAbility());

                    if (!abilities.isEmpty()) {
                        // TODO extend this logic to evaluate MDFC with both sides land
                        return refineLandChoice(landsWannaPlay, abilities);
                    }
                }
            }
        }

        return singleSpellAbilityList(getSpellAbilityToPlay());
    }

    private boolean isSafeToHoldLandDropForMain2(Card landToPlay) {
        boolean hasMomir = player.isCardInCommand("Momir Vig, Simic Visionary Avatar");
        if (hasMomir) {
            // Don't do this in Momir variants since it messes with the AI decision making for the avatar.
            return false;
        }

        if (game.getPhaseHandler().getTurn() <= 2) {
            // too obvious when doing it on the very first turn of the game
            return false;
        }

        if (!MyRandom.percentTrue(getIntProperty(AiProps.HOLD_LAND_DROP_FOR_MAIN2_IF_UNUSED))) {
            // check against the chance specified in the profile
            return false;
        }

        CardCollection inHand = CardLists.filter(player.getCardsIn(ZoneType.Hand), CardPredicates.NON_LANDS);
        CardCollectionView otb = player.getCardsIn(ZoneType.Battlefield);

        if (getBoolProperty(AiProps.HOLD_LAND_DROP_ONLY_IF_HAVE_OTHER_PERMS) && !otb.anyMatch(CardPredicates.NON_LANDS)) {
            return false;
        }

        // TODO: improve the detection of taplands
        boolean isTapLand = false;
        for (ReplacementEffect repl : landToPlay.getReplacementEffects()) {
            if (repl.getParamOrDefault("Description", "").equals("CARDNAME enters tapped.")) {
                isTapLand = true;
            }
        }

        int totalCMCInHand = Aggregates.sum(inHand, Card::getCMC);
        int minCMCInHand = Aggregates.min(inHand, Card::getCMC);
        if (minCMCInHand == Integer.MAX_VALUE)
            minCMCInHand = 0;
        int predictedMana = getAvailableManaEstimate(player, true);

        boolean canCastWithLandDrop = (predictedMana + 1 >= minCMCInHand) && minCMCInHand > 0 && !isTapLand;
        boolean cantCastAnythingNow = predictedMana < minCMCInHand;

        boolean hasRelevantAbsOTB = otb.anyMatch(card -> {
            boolean isTapLand1 = false;
            for (ReplacementEffect repl : card.getReplacementEffects()) {
                // TODO: improve the detection of taplands
                if (repl.getParamOrDefault("Description", "").equals("CARDNAME enters tapped.")) {
                    isTapLand1 = true;
                }
            }

            for (SpellAbility sa : card.getSpellAbilities()) {
                if (sa.isAbility()
                        && sa.getPayCosts().getCostMana() != null
                        && sa.getPayCosts().getCostMana().getMana().getCMC() > 0
                        && (!sa.getPayCosts().hasTapCost() || !isTapLand1)
                        && (!sa.hasParam("ActivationZone") || sa.getParam("ActivationZone").contains("Battlefield"))) {
                    return true;
                }
            }
            return false;
        });

        boolean hasLandBasedEffect = otb.anyMatch(card -> {
            for (Trigger t : card.getTriggers()) {
                Map<String, String> params = t.getMapParams();
                if ("ChangesZone".equals(params.get("Mode"))
                        && params.containsKey("ValidCard")
                        && (!params.containsKey("AILogic") || !params.get("AILogic").equals("SafeToHold"))
                        && !params.get("ValidCard").contains("nonLand")
                        && ((params.get("ValidCard").contains("Land")) || (params.get("ValidCard").contains("Permanent")))
                        && "Battlefield".equals(params.get("Destination"))) {
                    // Landfall and other similar triggers
                    return true;
                }
            }
            for (String sv : card.getSVars().keySet()) {
                String varValue = card.getSVar(sv);
                if (varValue.equals("Count$Domain")) {
                    for (String type : landToPlay.getType().getLandTypes()) {
                        if (CardType.isABasicLandType(type) && CardLists.getType(otb, type).isEmpty()) {
                            return true;
                        }
                    }
                }
                if (varValue.startsWith("Count$Valid") || sv.equals("BuffedBy")) {
                    if (varValue.contains("Land") || varValue.contains("Plains") || varValue.contains("Forest")
                            || varValue.contains("Mountain") || varValue.contains("Island") || varValue.contains("Swamp")
                            || varValue.contains("Wastes")) {
                        // In presence of various cards that get buffs like "equal to the number of lands you control",
                        // safer for our AI model to just play the land earlier rather than make a blunder
                        return true;
                    }
                }
            }
            return false;
        });

        // TODO: add prediction for effects that will untap a tapland as it enters the battlefield
        if (!canCastWithLandDrop && cantCastAnythingNow && !hasLandBasedEffect && (!hasRelevantAbsOTB || isTapLand)) {
            // Hopefully there's not much to do with the extra mana immediately, can wait for Main 2
            return true;
        }
        if ((predictedMana <= totalCMCInHand && canCastWithLandDrop) || (hasRelevantAbsOTB && !isTapLand) || hasLandBasedEffect) {
            // Might need an extra land to cast something, or for some kind of an ETB ability with a cost or an
            // alternative cost (if we cast it in Main 1), or to use an activated ability on the battlefield
            return false;
        }

        // TODO needed to pay propaganda

        return true;
    }

    private SpellAbility getSpellAbilityToPlay() {
        if (skipped != null) {
            //FIXME: this is for failed SA to skip temporarily, don't know why AI computation for mana fails, maybe due to auto mana compute?
            for (SpellAbility sa : skipped) {
                //System.out.println("Unskip: " + sa.toString() + " (" +  sa.getHostCard().getName() + ").");
                sa.setSkip(false);
            }
        }
        CardCollection cards = ComputerUtilAbility.getAvailableCards(game, player);
        cards = ComputerUtilCard.dedupeCards(cards);
        List<SpellAbility> saList = Lists.newArrayList();

        SpellAbility top = null;
        if (!game.getStack().isEmpty()) {
            top = game.getStack().peekAbility();
        }
        final boolean topOwnedByAI = top != null && top.getActivatingPlayer().equals(player);

        if (topOwnedByAI) {
            // Stiflenought: the AI's own "sacrifice it unless" trigger is answered with Stifle, which nothing else would do
            final SpellAbility stifle = StifleCombo.stifleOwnTrigger(player, top);
            if (stifle != null && !castFailedThisPhase(stifle.getHostCard()) && !hasFailedToCast(stifle)) {
                return stifle;
            }
        }

        // Must respond: cases where the AI should respond to its own triggers or other abilities (need to add negative stuff to be countered here)
        boolean mustRespond = false;
        if (top != null) {
            mustRespond = top.hasParam("AIRespondsToOwnAbility"); // Forced combos (currently defined for Sensei's Divining Top)
            mustRespond |= top.isTrigger() && top.getTrigger().isKeyword(Keyword.EVOKE); // Evoke sacrifice trigger
            // Combo-aware AI (spec 2026-10-22-combo-aware-ai-core-design.md section 6.4): our Fblthp trigger, answered
            // with Demonic Consultation (its script carries no AIRespondsToOwnAbility)
            mustRespond |= ComboExecute.respondsToOwn(player, top);
        }

        if (topOwnedByAI && stormSolver != null && flatMonteCarloActive() && stormSolver.expectsResponse(player)) {
            // a line of the tutor storm solver answers its own spell (Lion's Eye Diamond with Infernal Tutor on the stack)
            final SpellAbility response = stormSolver.next(player, deckPlan(player).combos);
            if (response != null) {
                return response;
            }
        }

        if (topOwnedByAI && flatMonteCarloActive()) {
            // Breakthrough on the stack: Lion's Eye Diamond puts the dredgers into the graveyard before the four draws
            final SpellAbility diamond = DredgeLine.response(player, deckPlan(player).combos);
            if (diamond != null) {
                return diamond;
            }
        }

        if (topOwnedByAI) {
            // AI's own spell: should probably let my stuff resolve first, but may want to copy the SA or respond to it
            // in a scripted timed fashion.

            if (!mustRespond) {
                saList = ComputerUtilAbility.getSpellAbilities(cards, player); // get the SA list early to check for copy SAs
                if (ComputerUtilAbility.getFirstCopySASpell(saList) == null) {
                    // Nothing to copy the spell with, so do nothing.
                    return null;
                }
            }
        }

        // Monte Carlo on the opponent's turn (or in response to their spell): then the heuristic counter is only one of
        // the candidates, to be compared with passing and with what else the AI could do with the mana.
        final FlatMonteCarloPicker.Mode reactiveMode = reactiveMonteCarloMode(mustRespond);
        SpellAbility heuristicCounter = null;
        if (!game.getStack().isEmpty()) {
            // Combo-aware AI, piece 2 (spec 2026-10-29-combo-aware-ai-loops-design.md section 6.6, amended): above an
            // opponent's removal of a loop piece or of Walking Ballista, a loop of an instant-speed kind goes off and
            // Ballista shoots to exact lethal before the removal resolves (LoopDriver.respond), ahead of the
            // counterspell pick: a win now beats countering the removal. Its answer is its next step, or a pass while a
            // step of its own waits beneath a punisher's trigger for it, the stock AI playing nothing then either. Null
            // at once for a deck holding no loop line and with no opponent's item on top; draws nothing.
            final LoopDriver.Answer loopAnswer = loopDriver.respond();
            if (loopAnswer != null) {
                return loopAnswer.action();
            }

            SpellAbility counter = chooseCounterSpell(getPlayableCounters(cards));
            if (counter != null && reactiveMode == null) return counter;
            heuristicCounter = counter;

            SpellAbility counterETB = chooseSpellAbilityToPlayFromList(getPossibleETBCounters(), false);
            if (counterETB != null)
                return counterETB;
        }

        if (saList.isEmpty()) {
            saList = ComputerUtilAbility.getSpellAbilities(cards, player);
        }

        // pieces of the deck's combos stay in the list even when the script says AI:RemoveDeck:All:
        // Flat MC compares them with rollouts, the heuristics still decide for every other card
        // Every other card marked AI:RemoveDeck:All stays in the list too, for Flat MC alone: see isFlatMcOnlyPlay
        final boolean flatMc = flatMonteCarloActive();
        saList.removeIf(spellAbility -> {
            // don't include removedAI cards if somehow the AI can play the ability or gain control of unsupported card,
            // unless Flat MC is on: it weighs them with rollouts (isFlatMcOnlyPlay keeps the heuristics off them)
            // TODO allow when experimental profile?
            // The RemoveDeck dispatcher (SpecialCardAi.RemoveDeckFilter): a hinted card's own row may
            // re-admit one of its abilities, an unhinted card's own row may keep one off outside its
            // cast window. Both answer false for every card no row names: the filter as it was.
            if (spellAbility.isLandAbility()) {
                return true;
            }
            final Card h = spellAbility.getHostCard();
            return h != null && (ComputerUtilCard.isCardRemAIDeck(h)
                    ? !flatMc && !SpecialCardAi.RemoveDeckFilter.readmit(player, spellAbility)
                    : SpecialCardAi.RemoveDeckFilter.keepOff(player, spellAbility));
        });
        //removed skipped SA
        skipped = saList.stream().filter(SpellAbility::isSkip).collect(Collectors.toList());
        if (!skipped.isEmpty())
            saList.removeAll(skipped);

        // Combo-aware AI (follow-up F5, spec 2026-10-22-combo-aware-ai-core-design.md section 6.4): once Thassa's
        // Oracle's go-off is ready on an empty stack (its emptier in hand and the mana for both:
        // ComboExecute.goOffFirst), its cast is chosen at this pass when canPlayAndPayFor approves it, ahead of the
        // walk below and the pre-casts after it, so no other spell spends the go-off's mana first. Inert (null once
        // the tracker's match is read) for a deck holding no Oracle line; draws nothing but canPlayAndPayFor's own,
        // the walk's own check of that cast. Ahead of upstream's own sure plays below (the Stiflenought cast, Flat MC's
        // madness and cut plays and its storm solver), which decide only when no combo line of ours does.
        final SpellAbility goOff = ComboExecute.goOffFirst(player, saList);
        if (goOff != null && canPlayAndPayFor(goOff) == AiPlayDecision.WillPlay) {
            return goOff;
        }

        // Combo-aware AI, piece 2 (spec 2026-10-29-combo-aware-ai-loops-design.md sections 6.2-6.5): a loop line's
        // go-off, after piece 1's and ahead of the walk. While one runs, each pass returns its cycle's next step,
        // played as any pick is, until the pool holds what the walk could spend; the driver's own check stands in for
        // canPlayAndPayFor, whose throttle and stock judges would refuse the steps. Null at once for a deck holding no
        // loop line, outside our main phase and above a stack; draws nothing.
        final SpellAbility loopPick = loopDriver.mainPhaseAction(saList);
        if (loopPick != null) {
            return loopPick;
        }

        // Stiflenought: the Dreadnought is cast as soon as its Stifle can be paid for as well, without rollouts
        final SpellAbility sureStifle = StifleCombo.sureCast(player, saList);
        if (sureStifle != null && !castFailedThisPhase(sureStifle.getHostCard()) && !hasFailedToCast(sureStifle)) {
            return sureStifle;
        }
        if (flatMonteCarloActive() && game.getStack().isEmpty()) {
            final SpellAbility sure = deckPlan(player).combos.sureMadnessPlay(player, saList);
            if (sure != null) {
                return sure; // safe and sure: no rollouts needed
            }
        }
        if (flatMonteCarloActive() && game.getStack().isEmpty()) {
            final SpellAbility cut = deckPlan(player).combos.sureCutPlay(player, saList);
            if (cut != null) {
                return cut; // the library cut with a finisher in place: no rollouts needed
            }
        }

        if (flatMonteCarloActive() && game.getStack().isEmpty() && deckPlan(player).combos.has(ComboPlan.Kind.TUTOR_STORM)) {
            if (stormSolver == null) {
                stormSolver = new StormSolver();
            }
            final SpellAbility solved = stormSolver.next(player, deckPlan(player).combos);
            if (solved != null) {
                return solved; // a line that wins this turn was found: follow it instead of guessing
            }
        }

        SpellAbility chosenSa;
        lastFlatMcResult = null;
        if (reactiveMode != null) {
            chosenSa = chooseReactively(reactiveMode, saList, heuristicCounter, cards, top);
        } else if (usesFlatMonteCarlo(mustRespond)) {
            // Same heuristics as always, but keep the first few plays they approve of and let rollouts compare them.
            final FlatMonteCarloPicker.Config mcCfg = flatMonteCarloConfig();
            final boolean discover = mcCfg != null && mcCfg.discovery && mcCfg.extraCandidates > 0;
            final List<SpellAbility> extras = new ArrayList<>();
            List<SpellAbility> approved = chooseSpellAbilitiesToPlayFromList(saList, true,
                    mcCfg == null ? 1 : discover ? mcCfg.maxCandidates * 2 : mcCfg.maxCandidates, discover ? extras : null);
            approved = fuelPlaysFirst(approved, extras);
            if (discover) {
                approved = FlatMonteCarloPicker.diversify(approved, mcCfg.maxCandidates);
            }
            FlatMonteCarloStats.noteDecisionPoint(approved.size(), mcCfg == null);
            try {
                chosenSa = refineWithFlatMonteCarlo(approved, extras, mcCfg, FlatMonteCarloPicker.Mode.MAIN);
            } catch (final AITimeoutTracker.Expired e) {
                throw e;
            } catch (final RuntimeException e) {
                // Monte Carlo is a refinement: never let it break a turn, fall back to the heuristic choice.
                noteFlatMcOutcome("error");
                FlatMonteCarloStats.noteFailure("controller: " + e);
                chosenSa = approved.isEmpty() ? null : approved.get(0);
            }
        } else {
            chosenSa = chooseSpellAbilityToPlayFromList(saList, true);
        }

        if (topOwnedByAI && !mustRespond && chosenSa != ComputerUtilAbility.getFirstCopySASpell(saList)) {
            return null; // not planning to copy the spell and not marked as something the AI would respond to
        }

        // Ride the Avalanche: a "next spell you cast this turn" pre-cast is only worth
        // its card in front of a spell we are casting anyway, so it is offered here,
        // with the choice already made, rather than predicted out of the hand. Inert
        // and RNG-free unless an SA in saList carries that AILogic.
        if (top == null) {
            SpellAbility ride = SpecialCardAi.RideTheAvalanche.findPreCast(player, chosenSa, saList);
            if (ride != null) {
                SpecialCardAi.RideTheAvalanche.PENDING.set(ride);
                try {
                    // hold the follow-up's own sources back first, so paying for the
                    // pre-cast can never starve the spell it rides in front of
                    if (reserveManaSourcesForNextSpell(chosenSa, ride)) {
                        if (canPlayAndPayFor(ride) == AiPlayDecision.WillPlay) {
                            return ride;
                        }
                        memory.clearMemorySet(AiCardMemory.MemorySet.HELD_MANA_SOURCES_FOR_NEXT_SPELL);
                    }
                } finally {
                    SpecialCardAi.RideTheAvalanche.PENDING.remove();
                }
            }
            // Rite of Harmony (dead-card batch 3, row 74): the same pre-cast idiom for a "draw per
            // creature or enchantment entering this turn" instant, with one difference. The chosen
            // spell's own sources are held with no exception (null, not the pre-cast): Rite's {G}{W}
            // and a one- or two-drop follow-up pick the same lowest-scored basics, so Ride's
            // except-set would empty the follow-up's holds and refuse after its test payments had
            // drawn. Rite then pays from what is left. Inert and RNG-free unless a Rite of Harmony
            // SA is in saList and the chosen spell puts a creature or enchantment onto our battlefield.
            SpellAbility rite = SpecialCardAi.RiteOfHarmony.findPreCast(player, chosenSa, saList);
            if (rite != null) {
                SpecialCardAi.RiteOfHarmony.PENDING.set(rite);
                try {
                    if (reserveManaSourcesForNextSpell(chosenSa, null)) {
                        if (canPlayAndPayFor(rite) == AiPlayDecision.WillPlay) {
                            return rite;
                        }
                        memory.clearMemorySet(AiCardMemory.MemorySet.HELD_MANA_SOURCES_FOR_NEXT_SPELL);
                    }
                } finally {
                    SpecialCardAi.RiteOfHarmony.PENDING.remove();
                }
            }
            // Benevolent Offering (dead-card batch 3, row 11, W11): in our own Main 2 the Offering may take
            // the place of the play the AI just chose (SpecialCardAi.BenevolentOffering.findHoist): H2 when
            // its three Spirit tokens make that creature spell cheap enough for both this turn, H1 when its
            // value beats that creature's by half a card. Inert (one hand scan by paper name) unless an
            // owner's Offering is in hand in our Main 2 on an empty stack; a decline draws nothing, holds
            // nothing and returns chosenSa unchanged. On H2 the chosen spell's off-white sources are held
            // for the next spell (Ride the Avalanche's idiom, chosen deterministically) so paying the
            // Offering cannot strand it; canPlayAndPayFor is the cast's own check and draw.
            SpecialCardAi.BenevolentOffering.Hoist hoist = SpecialCardAi.BenevolentOffering.findHoist(player, chosenSa, saList);
            if (hoist != null && hoist.pick) {
                for (Card c : hoist.hold) {
                    memory.rememberCard(c, AiCardMemory.MemorySet.HELD_MANA_SOURCES_FOR_NEXT_SPELL);
                }
                if (canPlayAndPayFor(hoist.offering) == AiPlayDecision.WillPlay) {
                    return hoist.offering;
                }
                memory.clearMemorySet(AiCardMemory.MemorySet.HELD_MANA_SOURCES_FOR_NEXT_SPELL);
            }
            // High Tide (dead-card batch 4, row 8, part 8A): our own hand High Tide may take the place of the
            // play the AI just chose, or of no play, in our own Main 2, when its extra {U} per Island makes the
            // first permanent spell of ours in saList's own order, ahead of that play, newly affordable this
            // turn, and that spell is our commander or of mana value 4 or more (SpecialCardAi.HighTide.findPreCast).
            // The card keeps AI:RemoveDeck:All and has no G3 readmit, so saList never holds it and A's loop, sort
            // and handler path are untouched. Inert (a phase test, then a hand scan by paper name) unless our own
            // tide is in hand in our Main 2 on an empty stack; a decline draws nothing, holds nothing, allocates
            // no SpellAbility and returns chosenSa unchanged; only an approval's own canPlayAndPayFor draws
            // (canPayCost's test payment), at a cast A was not about to make.
            SpellAbility tide = SpecialCardAi.HighTide.findPreCast(player, chosenSa, saList);
            if (tide != null) {
                SpecialCardAi.HighTide.PENDING.set(tide);
                try {
                    if (canPlayAndPayFor(tide) == AiPlayDecision.WillPlay) {
                        return tide;
                    }
                } finally {
                    SpecialCardAi.HighTide.PENDING.remove();
                }
            }
        }

        // Combo-aware AI, piece 2: when the loop driver passed this decision to the walk at its pool's target, it hears
        // what the walk returned (a round of its go-off), or, the walk having chosen nothing, ends the go-off
        // (LoopDriver.afterStockWalk, where the driver's own sinks are to play). Inert otherwise; draws nothing.
        if (chosenSa == null) {
            chosenSa = loopDriver.afterStockWalk();
        } else {
            loopDriver.noteStockPlay(chosenSa);
        }

        return chosenSa;
    }

    private SpellAbility chooseSpellAbilityToPlayFromList(final List<SpellAbility> all, boolean skipCounter) {
        final List<SpellAbility> chosen = chooseSpellAbilitiesToPlayFromList(all, skipCounter, 1);
        return chosen.isEmpty() ? null : chosen.get(0);
    }

    /** Result of the last flat Monte Carlo decision (null if the last choice was made by the heuristics alone). */
    public FlatMonteCarloPicker.Result getLastFlatMonteCarloResult() {
        return lastFlatMcResult;
    }

    /**
     * Whether this priority is one Monte Carlo decides on the opponent's turn: with a spell of theirs on the stack
     * (also on the AI's own turn), when they attack, or in their end step. Null when it is not, or when Monte Carlo is
     * off. Only plain spells on the stack can be simulated, so a stack with abilities on it is left to the heuristics.
     */
    private FlatMonteCarloPicker.Mode reactiveMonteCarloMode(final boolean mustRespond) {
        if (!(flatMcOverride != null ? flatMcOverride : game.getAIFlatMonteCarlo()) || flatMcOff() || mustRespond
                || simMode != null || !getBoolProperty(AiProps.FLAT_MC_ENABLED) || !getBoolProperty(AiProps.FLAT_MC_REACTIVE)) {
            return null;
        }
        final PhaseHandler ph = game.getPhaseHandler();
        if (!game.getStack().isEmpty()) {
            boolean onlySpells = true;
            for (final SpellAbilityStackInstance si : game.getStack()) {
                onlySpells &= si.getSpellAbility().isSpell();
            }
            final SpellAbility top = game.getStack().peekAbility();
            return onlySpells && top != null && top.getActivatingPlayer() != null && top.getActivatingPlayer().isOpponentOf(player)
                    ? FlatMonteCarloPicker.Mode.RESPOND : null;
        }
        if (ph.isPlayerTurn(player)) {
            return null;
        }
        if (ph.is(PhaseType.END_OF_TURN) && game.getPlayers().size() == 2) {
            return FlatMonteCarloPicker.Mode.END_STEP;
        }
        if (ph.is(PhaseType.COMBAT_DECLARE_ATTACKERS) && ph.getCombat() != null && !ph.getCombat().getAttackersOf(player).isEmpty()) {
            return FlatMonteCarloPicker.Mode.RESPOND;
        }
        return null;
    }

    /**
     * Monte Carlo on the opponent's turn. The candidates are the response the heuristics would make (a counterspell,
     * removal, a trick), the other plays they approve, and discovered plays: a counterspell they rejected, card draw
     * in response to a big spell or at the end step, flash plays, bounce. Passing is always compared as well. With
     * nothing castable there is nothing to decide and no rollout is spent.
     */
    private SpellAbility chooseReactively(final FlatMonteCarloPicker.Mode mode, final List<SpellAbility> saList,
                                          final SpellAbility heuristicCounter, final CardCollection cards, final SpellAbility top) {
        final FlatMonteCarloPicker.Config cfg = flatMonteCarloConfig();
        if (cfg == null || !cfg.reactive) {
            return heuristicCounter != null ? heuristicCounter : chooseSpellAbilityToPlayFromList(saList, true);
        }
        if (mode == FlatMonteCarloPicker.Mode.END_STEP) {
            // each sample plays a whole turn on (untap, draw, main phase, combat), several times a main phase rollout
            cfg.budgetNanos *= 2;
        }
        final boolean discover = cfg.discovery && cfg.extraCandidates > 0;
        final List<SpellAbility> extras = new ArrayList<>();
        final List<SpellAbility> approved = new ArrayList<>();
        if (heuristicCounter != null) {
            approved.add(heuristicCounter);
        }
        approved.addAll(chooseSpellAbilitiesToPlayFromList(saList, true,
                discover ? cfg.maxCandidates * 2 : cfg.maxCandidates, discover ? extras : null));
        // counterspells the heuristics did not want to use are still worth a look against a big spell
        if (top != null && mode == FlatMonteCarloPicker.Mode.RESPOND) {
            // Counterspells with an alternative cost (Force of Will, Daze, Force of Negation...) are separate plays that
            // only exist once the alternative costs are expanded: the printed ones are not castable without the mana.
            final List<SpellAbility> counterBases = new ArrayList<>(getPlayableCounters(cards));
            for (final SpellAbility sa : saList) {
                if (sa.getApi() == ApiType.Counter && sa.isSpell() && !counterBases.contains(sa)) {
                    counterBases.add(sa);
                }
            }
            final List<SpellAbility> taken = new ArrayList<>(approved);
            if (heuristicCounter != null) {
                taken.add(heuristicCounter);
            }
            taken.addAll(extras);
            for (final SpellAbility counter : ComputerUtilAbility.getOriginalAndAltCostAbilities(counterBases, player)) {
                if (taken.stream().anyMatch(t -> t == counter || (t.getHostCard() == counter.getHostCard()
                        && t.getPayCosts().toString().equals(counter.getPayCosts().toString())
                        && String.valueOf(t.getDescription()).equals(String.valueOf(counter.getDescription()))))) {
                    continue;
                }
                counter.setActivatingPlayer(player);
                // AILogic$ Never: out, unless it is one of the kinds that can be judged and discovery or a ramp deck's
                // key card asks for it (Kozilek, the Great Distortion's counter)
                final boolean guarded = GuardedAbilities.isNeverLogic(counter);
                if (guarded ? !GuardedAbilities.mayConsider(player, counter, discover) : !discover) {
                    continue; // without discovery only a ramp deck's key card gets this far
                }
                try {
                    if (counter.canPlay() && counter.canTargetSpellAbility(top)) {
                        // aim first: a cost that depends on the target (a discard of its mana value) cannot be checked before
                        counter.resetTargets();
                        counter.getTargets().add(top);
                        if (ComputerUtilCost.canPayCost(counter, player, false)
                                && (!guarded || GuardedAbilities.worthIt(player, counter, top))) {
                            extras.add(counter);
                        } else {
                            counter.resetTargets();
                        }
                    }
                } catch (final RuntimeException e) {
                    counter.resetTargets(); // not castable after all
                }
            }
        }
        // Card draw and selection only make sense in response to a spell or at the end step; during combat only
        // interaction is worth an instant.
        if (mode == FlatMonteCarloPicker.Mode.RESPOND && game.getStack().isEmpty()) {
            extras.removeIf(sa -> !FlatMonteCarloPicker.isInteraction(sa));
        }
        final List<SpellAbility> shown = FlatMonteCarloPicker.diversify(approved, cfg.maxCandidates);
        FlatMonteCarloStats.noteReactivePoint(mode, shown.size() + extras.size());
        if (shown.isEmpty() && extras.isEmpty()) {
            return null; // nothing to do with this priority: pass, at no cost
        }
        try {
            final SpellAbility chosen = refineWithFlatMonteCarlo(shown, extras, cfg, mode);
            if (lastFlatMcResult != null) {
                FlatMonteCarloStats.noteReactiveResult(mode, lastFlatMcResult, shown.isEmpty() ? null : shown.get(0));
            }
            for (final SpellAbility extra : extras) {
                if (extra != chosen && extra.getApi() == ApiType.Counter) {
                    extra.resetTargets(); // aimed at the stack for the rollouts only
                }
            }
            return chosen;
        } catch (final AITimeoutTracker.Expired e) {
            throw e;
        } catch (final RuntimeException e) {
            noteFlatMcOutcome("error");
            FlatMonteCarloStats.noteFailure("reactive: " + e);
            return shown.isEmpty() ? null : shown.get(0);
        }
    }

    private boolean usesFlatMonteCarlo(final boolean mustRespond) {
        if (!(flatMcOverride != null ? flatMcOverride : game.getAIFlatMonteCarlo()) || flatMcOff() || mustRespond || simMode != null
                || !getBoolProperty(AiProps.FLAT_MC_ENABLED)) {
            return false; // off, forced responses, or one of the simulation modes which do their own lookahead
        }
        final PhaseHandler ph = game.getPhaseHandler();
        // Only sorcery-timing decisions on the AI's own turn: that is where the lines to compare are, and the
        // rollout ends at the second main phase. Everything reactive stays with the heuristics (and is cheap).
        return ph.isPlayerTurn(player) && game.getStack().isEmpty()
                && (ph.is(PhaseType.MAIN1) || ph.is(PhaseType.MAIN2));
    }

    private FlatMonteCarloPicker.Config flatMonteCarloConfig() {
        final int turn = game.getPhaseHandler().getTurn();
        if (turn != flatMcTurn) {
            flatMcTurn = turn;
            flatMcTurnSpentNanos = 0;
        }
        final FlatMonteCarloPicker.Config cfg = FlatMonteCarloPicker.Config.fromProfile(this, player);
        if (flatMcMaxOverride != null ? flatMcMaxOverride : game.getAIFlatMonteCarloMax()) {
            cfg.maximized();
        } else if (game.getAIFlatMonteCarloLite()) {
            cfg.mobileLite();
        }
        if (flatMcSlowTrips > 0) {
            // Back after a pause for slowness: a smaller search, and no look at the opponent's turn, which costs most.
            cfg.maxCandidates = Math.max(3, cfg.maxCandidates - 2 * flatMcSlowTrips);
            cfg.rolloutsPerCandidate = Math.max(2, cfg.rolloutsPerCandidate - flatMcSlowTrips);
            cfg.extraRounds = Math.min(cfg.extraRounds, 1);
            cfg.opponentLookaheadPercent = 0;
        }
        final long left = cfg.turnBudgetNanos - flatMcTurnSpentNanos;
        if (left <= 0 || cfg.budgetNanos <= 0) {
            return null;
        }
        cfg.budgetNanos = Math.min(cfg.budgetNanos, left);
        return cfg;
    }

    /**
     * Plays that Flat Monte Carlo never spends a card on (see {@link forge.ai.simulation.ComboGuard}): bringing back
     * a legend that is already in play and working, a second Omniscience, Show and Tell with nothing worth putting in,
     * and a cantrip while the AI is confident to win this turn or the next. Returns the reason, or null for a fine play.
     */
    private String guardProblem(final SpellAbility sa, final GameEntity aim, final boolean saveCantrips) {
        final ComboPlan plan = deckPlan(player).combos;
        final String problem = ComboGuard.problem(player, sa, aim, plan);
        if (problem != null) {
            return problem;
        }
        return saveCantrips && ComboGuard.isCantrip(sa) ? "cantrip-saved" : null;
    }

    private SpellAbility refineWithFlatMonteCarlo(final List<SpellAbility> approvedIn, final List<SpellAbility> extras,
                                                  final FlatMonteCarloPicker.Config cfg, final FlatMonteCarloPicker.Mode mode) {
        if (approvedIn.isEmpty() && extras.isEmpty()) {
            return null;
        }
        // What the heuristics aimed each approved play at, noted before the rollouts: asking the heuristics again
        // (as the rollouts do to check a line is still approved) clears the targets of a play they turn down on the
        // dice, and the aim they gave it a moment ago is then the only record of where the play was meant to go.
        final Map<SpellAbility, GameEntity> approvedAim = new HashMap<>();
        for (final SpellAbility sa : approvedIn) {
            if (sa.usesTargeting() && sa.getTargets() != null) {
                final List<GameEntity> aimed = new ArrayList<>();
                for (final GameEntity e : sa.getTargets().getTargetEntities()) {
                    aimed.add(e);
                }
                if (aimed.size() == 1) {
                    approvedAim.put(sa, aimed.get(0));
                }
            }
        }
        // Plays that waste a card are taken out before anything is compared, the heuristics' favourite included: the
        // next play in line then stands for "what the heuristics would do". Nothing left means passing.
        final boolean saveCantrips = ComboGuard.winLikelySoon(player, deckPlan(player).combos);
        final List<SpellAbility> approved = new ArrayList<>(approvedIn.size());
        for (final SpellAbility sa : approvedIn) {
            final String problem = guardProblem(sa, approvedAim.get(sa), saveCantrips);
            if (problem == null) {
                approved.add(sa);
            } else {
                FlatMonteCarloStats.noteRejected("ComboGuard:" + problem);
            }
        }
        for (final SpellAbility sa : new ArrayList<>(extras)) {
            final String problem = guardProblem(sa, null, saveCantrips);
            if (problem != null) {
                extras.remove(sa);
                FlatMonteCarloStats.noteRejected("ComboGuard:" + problem);
            }
        }
        if (approved.isEmpty() && extras.isEmpty()) {
            return null;
        }
        if (cfg == null) { // out of budget
            return approved.isEmpty() ? null : approved.get(0); // out of budget
        }
        if (mode == FlatMonteCarloPicker.Mode.MAIN && extras.isEmpty() && approved.size() < 2
                && !FlatMonteCarloPicker.isHoldPossible(player)) {
            return approved.get(0); // nothing to compare
        }
        // The heuristics are asked again before a line is rolled out, and that can aim the play somewhere else (at the
        // legend that is already in play): such a line is not simulated, and never played either (see below).
        final FlatMonteCarloPicker.Result result = FlatMonteCarloPicker.choose(player, approved, extras, cfg,
                sa -> playDecisionStable(sa) == AiPlayDecision.WillPlay && guardProblem(sa, null, saveCantrips) == null, mode);
        lastFlatMcResult = result;
        FlatMonteCarloStats.record(result);
        flatMcTurnSpentNanos += result.elapsedNanos;
        noteFlatMcOutcome(result.reason);
        if (result.hold) {
            return null; // pass priority with the mana still available
        }
        if (extras.contains(result.choice)) {
            // A discovered play: the heuristics never approved it, so there is nothing for them to re-check. Make sure
            // it is still aimed where the rollouts liked it, and that it can still be paid for.
            if (result.target != null && !FlatMonteCarloPicker.applyTarget(result.choice, result.target)) {
                return approved.isEmpty() ? null : approved.get(0);
            }
            if (guardProblem(result.choice, null, saveCantrips) != null) {
                return approved.isEmpty() ? null : approved.get(0);
            }
            result.choice.setActivatingPlayer(player);
            return result.choice;
        }
        // Whatever was evaluated afterwards may have disturbed the targets of the chosen play: set them up again, and
        // fall back to the other approved plays in heuristic order if the heuristics no longer like it.
        final List<SpellAbility> order = new ArrayList<>();
        order.add(result.choice);
        for (final SpellAbility sa : approved) {
            if (sa != result.choice) {
                order.add(sa);
            }
        }
        for (final SpellAbility sa : order) {
            // What the heuristics aimed at when they approved the play: a refusal below clears the targets again.
            GameEntity approvedTarget = null;
            int approvedTargets = 0;
            if (sa == result.choice && sa.usesTargeting() && approvedAim.containsKey(sa)) {
                approvedTarget = approvedAim.get(sa);
                approvedTargets = 1;
            } else if (sa == result.choice && sa.usesTargeting() && sa.getTargets() != null) {
                for (final GameEntity e : sa.getTargets().getTargetEntities()) {
                    approvedTarget = e;
                    approvedTargets++;
                }
            }
            final boolean willPlay = playDecisionStable(sa) == AiPlayDecision.WillPlay; // asked once: the answer can roll dice
            if (!willPlay && sa == result.choice) {
                // The heuristics are not deterministic (several of them roll dice, some remember what they planned) and
                // said yes a moment ago when they approved this play, and the rollouts confirmed it: Monte Carlo's
                // verdict stands, aimed where it was (a single target, still legal) if the play aims at all.
                final boolean aimOk = !sa.usesTargeting()
                        || (result.target == null && approvedTargets == 1 && FlatMonteCarloPicker.applyTarget(sa, approvedTarget));
                if (aimOk && isStillCastable(sa) && guardProblem(sa, null, saveCantrips) == null) {
                    return sa;
                }
            }
            if (willPlay) {
                if (sa == result.choice && result.target != null && !FlatMonteCarloPicker.applyTarget(sa, result.target)
                        && playDecisionStable(sa) != AiPlayDecision.WillPlay) {
                    continue; // the other target is no longer legal and the heuristics gave the play up as well
                }
                if (guardProblem(sa, null, saveCantrips) != null && !reaimedWithoutProblem(sa, saveCantrips)) {
                    FlatMonteCarloStats.noteRejected("ComboGuard:late");
                    continue; // the heuristics aimed it at the legend that is already in play, and nothing else will do
                }
                return sa;
            }
        }
        return null;
    }

    /** Another target for a play the heuristics aimed at a legend the AI already has: the first one the guard accepts. */
    private boolean reaimedWithoutProblem(final SpellAbility sa, final boolean saveCantrips) {
        for (final GameObject target : FlatMonteCarloPicker.alternativeTargets(player, sa, 3)) {
            if (FlatMonteCarloPicker.applyTarget(sa, target) && guardProblem(sa, null, saveCantrips) == null) {
                return true;
            }
        }
        return false;
    }

    /** Forces Monte Carlo on or off for this player only (null: follow the game setting). Used for A/B comparisons. */
    public void setFlatMonteCarloOverride(final Boolean enabled) {
        flatMcOverride = enabled;
    }

    private Boolean flatMcAttackOverride;

    /** Mulligans and bottoms cards by the deck plan: asked for in the profile, or part of maximum strength. */
    public boolean useSmartMulligan() {
        return getBoolProperty(AiProps.FLAT_MC_MULLIGAN)
                || isFlatMonteCarloMax() && (flatMcOverride != null ? flatMcOverride : game.getAIFlatMonteCarlo());
    }

    /** Maximum strength (desktop matches): the attack search and broad discovery are part of it. */
    private boolean isFlatMonteCarloMax() {
        return flatMcMaxOverride != null ? flatMcMaxOverride : game.getAIFlatMonteCarloMax();
    }

    /** Phone and tablet matches: the attack search is part of it too, within the small time limits. */
    private boolean isFlatMonteCarloLite() {
        return game.getAIFlatMonteCarloLite();
    }

    /** Forces the attack search on or off for this player; null follows the profile (FLAT_MC_ATTACK). */
    public void setFlatMonteCarloAttackOverride(final Boolean enabled) {
        flatMcAttackOverride = enabled;
    }

    /** Forces maximum strength on or off for this player; null follows the game (on for desktop matches). */
    public void setFlatMonteCarloMaxOverride(final Boolean enabled) {
        flatMcMaxOverride = enabled;
    }

    void noteFlatMcOutcome(final String reason) {
        if ("error".equals(reason)) {
            if (++flatMcErrors >= 3) {
                flatMcTripped = true;
            }
        } else if ("too-slow".equals(reason) || "no-time".equals(reason)) {
            if (++flatMcSlowStreak >= 4) {
                // This device could not afford two rollouts per decision just now. A phone has hiccups (a garbage
                // collection, another app), so sit out a few turns and come back with a smaller search; only a device
                // that keeps failing stays off for the game.
                flatMcSlowStreak = 0;
                flatMcGoodStreak = 0;
                // Slowness never switches Monte Carlo off for good: each further failure pauses it a little longer
                // (3, 6, then 9 turns) and shrinks the search further, and a run of good decisions earns the size back.
                flatMcSlowTrips = Math.min(flatMcSlowTrips + 1, FLAT_MC_MAX_SLOW_TRIPS);
                flatMcPausedUntilTurn = game.getPhaseHandler().getTurn() + FLAT_MC_PAUSE_TURNS * flatMcSlowTrips;
            }
        } else {
            flatMcSlowStreak = 0;
            if (flatMcSlowTrips > 0 && ++flatMcGoodStreak >= FLAT_MC_RECOVER_DECISIONS) {
                flatMcGoodStreak = 0;
                flatMcSlowTrips--;
            }
        }
    }

    /** Monte Carlo is off for this player: tripped by errors, or paused after slowness. */
    private boolean flatMcOff() {
        return flatMcTripped || game.getPhaseHandler().getTurn() < flatMcPausedUntilTurn;
    }

    private DeckPlan flatMcDeckPlan;

    /** What the deck is for, worked out once from the whole list and kept for the game; see {@link forge.ai.simulation.DeckPlan}. */
    public DeckPlan deckPlan(final Player p) {
        if (flatMcDeckPlan == null) {
            flatMcDeckPlan = DeckPlan.forSetting(getProperty(AiProps.FLAT_MC_DECK_PLAN), p);
            // the benchmark report's counters are JVM-wide statics: a game played under a SimScope (the sim harness,
            // beside the other games of its serve JVM) keeps out of them; they decide nothing
            if (SimScope.current() == null) {
                if (flatMcDeckPlan.archetype != DeckPlan.Archetype.MIDRANGE || !flatMcDeckPlan.combos.isEmpty()) {
                    FlatMonteCarloStats.notePlan(flatMcDeckPlan.toString());
                }
                for (final ComboPlan.Combo combo : flatMcDeckPlan.combos.getCombos()) {
                    FlatMonteCarloStats.notePlan("combo:" + combo.kind);
                }
            }
        }
        return flatMcDeckPlan;
    }

    /**
     * For a deck built around the graveyard (Living End and its kin, reanimator) the plays that put cards there on
     * purpose (cycling, discard and looting) come first: they are what the combo needs before anything else, and a
     * creature-first ordering would bury them. Fuel plays the heuristics turned down are brought in from the extras.
     * Other decks, and decks whose payoff is gone, get the list back unchanged.
     */
    private List<SpellAbility> fuelPlaysFirst(final List<SpellAbility> approved, final List<SpellAbility> extras) {
        final ComboPlan combos = deckPlan(player).combos;
        if (combos.isEmpty() || !combos.fuelFirst(player)) {
            return approved;
        }
        final List<SpellAbility> fuel = new ArrayList<>();
        final List<SpellAbility> rest = new ArrayList<>();
        for (final SpellAbility sa : approved) {
            (combos.isFuelPlay(sa) ? fuel : rest).add(sa);
        }
        for (final SpellAbility sa : new ArrayList<>(extras)) {
            if (combos.isFuelPlay(sa) && !fuel.contains(sa)) {
                fuel.add(sa);
                extras.remove(sa);
            }
        }
        if (fuel.isEmpty()) {
            return approved;
        }
        fuel.addAll(rest);
        return fuel;
    }

    /** True once Monte Carlo has switched itself off for this player (repeated errors or slowness). */
    public boolean isFlatMonteCarloTripped() {
        return flatMcOff();
    }

    /**
     * A play the heuristics turned down that Monte Carlo may still compare: it is of a strategic kind and can really
     * be cast or activated right now (timing, restrictions and cost), so the only thing against it is the heuristics'
     * rule of thumb. Nothing is decided here; rollouts judge it against the alternatives.
     */
    /**
     * A play of a card marked AI:RemoveDeck:All that is not a piece of the deck's combos or a tutor with a find: the
     * heuristics cannot play it well, so they never approve it, but with rollouts to check what it really does it may
     * be offered as a discovered play (Parallax Wave's exile ability, the Wave itself). Only while Flat MC is on.
     */
    public boolean isFlatMcOnlyPlay(final SpellAbility sa) {
        final Card h = sa == null ? null : sa.getHostCard();
        return h != null && ComputerUtilCard.isCardRemAIDeck(h) && flatMonteCarloActive()
                && !deckPlan(player).combos.isKeyCard(h) && !TutorPlan.hasPlanTarget(player, sa);
    }

    /**
     * A mana rock or mana creature marked AI:RemoveDeck:All (Springleaf Drum): the heuristics never cast it, and
     * without broad discovery nothing else would either, so it is always offered to the rollouts, which decide.
     */
    private boolean isRemoveDeckManaPermanent(final SpellAbility sa) {
        final Card h = sa.getHostCard();
        return h != null && sa.isSpell() && h.isPermanent() && !h.isLand() && ComputerUtilCard.isCardRemAIDeck(h)
                && !h.getManaAbilities().isEmpty();
    }

    private boolean isLegalDiscovery(final SpellAbility sa, final AiPlayDecision opinion) {
        if (sa == null) {
            return false;
        }

        sa.setActivatingPlayer(player);

        if (!sa.checkRestrictions(player)) {
            return false;
        }

        if (GuardedAbilities.isNeverLogic(sa) && !GuardedAbilities.mayConsider(player, sa, true)) {
            return false; // the script says the AI never plays it, and nothing makes an exception
        }
        switch (opinion) {
            case CantPlaySa, CantAfford, CantAffordX, TimingRestrictions, WouldDestroyLegend, WouldBecomeZeroToughnessCreature:
                return false;
            default:
                break;
        }
        final Card host = sa.getHostCard();
        final ComboPlan combos = deckPlan(player).combos;
        if (!FlatMonteCarloPicker.isDiscoverable(sa, getBoolProperty(AiProps.FLAT_MC_BROAD_DISCOVERY) || isFlatMonteCarloMax())
                && !isRemoveDeckManaPermanent(sa)
                && !combos.isFuelPlay(sa)
                && !(host != null && ComputerUtilCard.isCardRemAIDeck(host) && combos.isKeyCard(host))
                && !TutorPlan.hasUse(player, sa)) {
            return false; // a tutor with something to find is a play for the rollouts to weigh, in every mode
        }
        try {
            return sa.canPlay() && ComputerUtilCost.canPayCost(sa, player, false);
        } catch (final RuntimeException e) {
            return false;
        }
    }

    private boolean isStillCastable(final SpellAbility sa) {
        sa.setActivatingPlayer(player);
        try {
            return sa.checkRestrictions(player) && sa.canPlay() && ComputerUtilCost.canPayCost(sa, player, false);
        } catch (final RuntimeException e) {
            return false;
        }
    }

    /**
     * Like {@link #playDecision} for a play the heuristics approved a moment ago: several of them roll dice, so one
     * "no" proves little. WillPlay if any of a few asks says so (which also re-aims the play's targets).
     */
    private AiPlayDecision playDecisionStable(final SpellAbility sa) {
        AiPlayDecision decision = playDecision(sa);
        for (int i = 0; i < 3 && decision != AiPlayDecision.WillPlay; i++) {
            decision = playDecision(sa);
        }
        return decision;
    }

    /** The heuristic verdict on one play, the way the spell choice loop asks for it. */
    private AiPlayDecision playDecision(final SpellAbility sa) {
        sa.setActivatingPlayer(player);
        final SpellAbility root = sa.getRootAbility();
        if (root.isSpell() || root.isTrigger() || root.isReplacementAbility()) {
            sa.setLastStateBattlefield(game.getLastStateBattlefield());
            sa.setLastStateGraveyard(game.getLastStateGraveyard());
        }
        try {
            return canPlayAndPayFor(sa);
        } finally {
            sa.clearLastState();
        }
    }

    private static final AtomicBoolean sortFallbackReported = new AtomicBoolean();

    /** The spell ability evaluator contradicted itself and a stable fallback sort was used instead; report it once per run. */
    private static void reportSortFallback(final List<SpellAbility> all) {
        if (!sortFallbackReported.compareAndSet(false, true)) {
            return; // the O(n^3) check below is only worth running once
        }
        final String assertex = ComparatorUtil.verifyTransitivity(ComputerUtilAbility.saEvaluator, all);
        Sentry.captureMessage("saEvaluator broke the Comparator contract, used stable fallback sort\nverifyTransitivity: " + assertex);
    }

    /** Like {@link #chooseSpellAbilityToPlayFromList} but keeps looking until {@code maxResults} plays are approved. */
    private List<SpellAbility> chooseSpellAbilitiesToPlayFromList(final List<SpellAbility> all, boolean skipCounter, final int maxResults) {
        return chooseSpellAbilitiesToPlayFromList(all, skipCounter, maxResults, null);
    }

    /**
     * @param extrasOut when not null, legal plays of a strategic kind (card draw, selection, ...) that the heuristics
     *                  turned down are added here, for Monte Carlo to consider anyway
     */
    private List<SpellAbility> chooseSpellAbilitiesToPlayFromList(final List<SpellAbility> all, boolean skipCounter, final int maxResults,
                                                                  final List<SpellAbility> extrasOut) {
        if (all == null || all.isEmpty())
            return Collections.emptyList();

        approvedUnaffordable.clear(); // Dead-card batch 4, row 33: a fresh pass of the record
        if (ComputerUtilAbility.sortSpellAbilities(all)) { // put best spells first
            reportSortFallback(all);
        }

        // Dead-card batch 3, row 6: Sylvan Offering before its "draw for each creature" follower in
        // the same Main 2. Inert (an O(1) phase test) outside our own Main 2 with an empty stack;
        // draws nothing, makes no card; either moves that follower to just after Offering or leaves
        // the list exactly as it is.
        SpecialCardAi.SylvanOffering.sequenceFollower(player, all);
        // Dead-card batch 4, row 1, part 1B (Neera, Wild Mage): a cheap feeder ahead of the first
        // own-hand spell that is timing-legal and payable, while our Neera's once-each-turn trigger is
        // unspent. Inert (an empty-stack test and one isCardInPlay) otherwise; draws nothing, makes no
        // card; either moves that one feeder or leaves the list exactly as it is.
        SpecialCardAi.NeeraWildMage.sequenceFeeder(player, all);
        // Dead-card batch 4, wave 2, row 65, part 65A (Scroll Rack): 1B never moves a Rack (a reusable
        // permanent), so a readmitted Rack moves itself ahead of the first payable non-feeder hand spell while
        // our Neera is unspent. Inert (an empty-stack test, one isCardInPlay and Neera's trigger scan, one list
        // scan) otherwise; draws nothing, makes no card; either moves the Rack or leaves the list exactly as it is.
        SpecialCardAi.ScrollRack.sequenceFeeder(player, all);

        // read on this thread, before the evaluation thread starts: the plan is fixed for the whole game
        final ComboPlan combos = deckPlan(player).combos;
        final boolean massReanimation = combos.isMassReanimationDeck(player);
        final boolean darkDepths = combos.has(ComboPlan.Kind.DARK_DEPTHS);
        // Upstream's tutor storm holds below keep a deck's fast mana, its cheap fodder, Infernal Tutor and its take-life
        // finisher for StormSolver, their only release, which runs under Flat Monte Carlo alone (and which upstream ships
        // switched on). With Flat MC off, every game of the sim harness (GameRunner pins it), nothing could release them
        // and those cards would simply be dead: the walk treats them as stock there, and the Storm block falls to the
        // stock minimum storm count. A rollout's game has the flag off by design (GameCopier) and holds as the game it
        // copies does. False at once for a deck that reads as no tutor storm; draws nothing.
        final boolean stormHolds = combos.has(ComboPlan.Kind.TUTOR_STORM)
                && (flatMonteCarloActive() || game.isSimulationCopy());
        final long aiId = AITimeoutTracker.key(game.getId(), player.getId());
        final long timeoutNanos = TimeUnit.SECONDS.toNanos(game.getAITimeout());
        AITimeoutTracker.begin(aiId, timeoutNanos);
        final long deadlineNanos = AITimeoutTracker.deadlineNanos(aiId, timeoutNanos);
        FutureTask<List<SpellAbility>> future = new FutureTask<>(() -> {
            final List<SpellAbility> approvedPlays = new ArrayList<>();
            // the eval thread aborts itself at the shared deadline (or when cancelled) instead of running on as a zombie
            final Object evalLimit = AITimeoutTracker.arm(deadlineNanos, true);
            try {
                //avoid ComputerUtil.aiLifeInDanger in loops as it slows down a lot.. call this outside loops will generally be fast...
                final boolean isLifeInDanger = massReanimation && ComputerUtil.aiLifeInDanger(player, true, 0);
                for (final SpellAbility sa : ComputerUtilAbility.getOriginalAndAltCostAbilities(all, player)) {
                    AITimeoutTracker.check();

                    // Don't add Counterspells to the "normal" playcard lookups
                    if (skipCounter && sa.getApi() == ApiType.Counter) {
                        continue;
                    }

                    if (sa.getHostCard().hasKeyword(Keyword.STORM)
                            && sa.getApi() != ApiType.Counter // AI would suck at trying to deliberately proc a Storm counterspell
                            && player.getZone(ZoneType.Hand).contains(
                                    Predicate.not(CardPredicates.LANDS.or(CardPredicates.hasKeyword("Storm")))
                        )) {
                        // a storm card that takes the last life of the opponent is always worth casting; a deck built around
                        // finding it with tutors keeps it until then, any other deck casts it from the minimum storm count on
                        if (!ComboPlan.stormIsLethal(player, sa.getHostCard())
                                && ((stormHolds && ComboPlan.takesLifePerCopy(sa.getHostCard()))
                                || game.getView().getStormCount() < this.getIntProperty(AiProps.MIN_COUNT_FOR_STORM_SPELLS))) {
                            // skip evaluating Storm unless we reached the minimum Storm count
                            continue;
                        }
                    }

                    // a tutor storm deck keeps its rituals and moxen for the turn the solver finds the kill
                    if (stormHolds && (combos.holdsForKill(sa) || combos.wastesTutor(sa) || combos.wastesFodder(sa))) {
                        continue;
                    }
                    // a take-life storm finisher is never cast unless it kills, even when it is the only spell left in hand
                    if (stormHolds && sa.isSpell() && sa.getHostCard() != null
                            && ComboPlan.isStormFinisher(sa.getHostCard())
                            && ComboPlan.takesLifePerCopy(sa.getHostCard())
                            && !ComboPlan.stormIsLethal(player, sa.getHostCard())) {
                        continue;
                    }

                    sa.setActivatingPlayer(player);
                    SpellAbility root = sa.getRootAbility();

                    // a Living End style deck plays its cycling, fuel and cascade by its own rules, see ComboPlan
                    ComboPlan.Ruling ruling = ComboPlan.Ruling.NO_OPINION;
                    if (massReanimation) {
                        ruling = combos.massReanimationRuling(player, sa, isLifeInDanger);
                        if (ruling == ComboPlan.Ruling.SKIP) {
                            continue;
                        }
                    }
                    if (darkDepths && ruling == ComboPlan.Ruling.NO_OPINION) {
                        ruling = combos.darkDepthsRuling(player, sa);
                        if (ruling == ComboPlan.Ruling.SKIP) {
                            continue;
                        }
                    }

                    if (root.isSpell() || root.isTrigger() || root.isReplacementAbility()) {
                        sa.setLastStateBattlefield(game.getLastStateBattlefield());
                        sa.setLastStateGraveyard(game.getLastStateGraveyard());
                    }
                    final AiPlayDecision opinion;
                    try {
                        // a ruling plays past the heuristics, not past a cast that has failed: the seed-11 rule's two
                        // tries a phase and upstream's memory of a cast that did not go on the stack both still count
                        opinion = ruling == ComboPlan.Ruling.PLAY && !castFailedThisPhase(sa.getHostCard())
                                && !hasFailedToCast(sa) ? AiPlayDecision.WillPlay : canPlayAndPayFor(sa);
                    } finally {
                        // reset LastStateBattlefield - also when the deadline fired inside canPlayAndPayFor
                        sa.clearLastState();
                    }
                    // PhaseHandler ph = game.getPhaseHandler();
                    // System.out.printf("Ai thinks '%s' of %s -> %s @ %s %s >>> \n", opinion, sa.getHostCard(), sa, Lang.getInstance().getPossesive(ph.getPlayerTurn().getName()), ph.getPhase());

                    if (isFlatMcOnlyPlay(sa)) {
                        // never approved by the heuristics: only a play for the rollouts to weigh, and only with discovery
                        if (extrasOut != null && isLegalDiscovery(sa, opinion)) {
                            extrasOut.add(sa);
                        }
                        continue;
                    }
                    if (opinion != AiPlayDecision.WillPlay) {
                        if (maxResults > 1) {
                            FlatMonteCarloStats.noteRejected(opinion.name());
                        }
                        if (extrasOut != null && isLegalDiscovery(sa, opinion)) {
                            extrasOut.add(sa);
                        }
                        continue;
                    }

                    // TODO could continue to try find another with higher rating (weighted by priority ordering)
                    approvedPlays.add(sa);
                    if (approvedPlays.size() >= maxResults) {
                        break;
                    }
                }

                return approvedPlays;
            } catch (AITimeoutTracker.Expired e) {
                return approvedPlays; // whatever was approved before time ran out
            } finally {
                AITimeoutTracker.disarm(evalLimit); // matters as soon as this runs on a pooled thread
            }
        });
        // The decision runs on its own thread; under a simulation it must draw from THIS game's
        // scope, so the task adopts the scope bound here (explicit, never inherited by a pool).
        Thread t = new Thread(SimScope.adopting(future), "Game AI Eval");
        t.setDaemon(true);
        t.start();
        try {
            // wait only for what is left of the shared deadline (not a fresh full timeout per call)
            return future.get(Math.max(0, deadlineNanos - System.nanoTime()), TimeUnit.NANOSECONDS);
        } catch (InterruptedException | ExecutionException | TimeoutException e) {
            if (e instanceof ExecutionException && e.getCause() instanceof Error err) {
                // An Error thrown by the evaluation -- a StackOverflowError out of an unbounded AI
                // recursion, an OutOfMemoryError -- is not a decision. Read as "nothing to play",
                // it let the game go on from a random-stream position that depended on how deep
                // the eval thread's stack happened to reach, so one seed played different games
                // (walk 7 game 671988). Rethrown, it ends the game as an Error, every run alike.
                throw err;
            }
            Error wrapped = SimScope.simulationError(e);
            if (wrapped != null) {
                // A simulated game rethrows an Error from further down the chain too, such as a
                // strict-mode refusal or an abandoned game that AbilityFactory wrapped in a
                // RuntimeException while it built an ability. Read as "nothing to play", it would leave
                // a silently crippled game that every arm of the determinism battery agrees on.
                throw wrapped;
            }
            e.printStackTrace();
            if (e instanceof TimeoutException) {
                // log where the eval thread currently is - each timeout doubles as a
                // profiler sample for diagnosing remaining AI slowdowns from user logs
                StringBuilder sb = new StringBuilder("AI eval thread at timeout:");
                StackTraceElement[] evalStack = t.getStackTrace();
                for (int i = 0; i < Math.min(30, evalStack.length); i++) {
                    sb.append("\n\tat ").append(evalStack[i]);
                }
                System.out.println(sb);
            }
            // ask the eval thread to exit at the next SpellAbility check first: a brutal
            // Thread.stop() mid-evaluation can leave partially mutated shared state behind
            future.cancel(true);
            try {
                // join returns as soon as the eval thread exits, so the common case costs nothing. The full 2 s only matters
                // for a thread inside code without check() (mana payment, threat prediction, simulation): giving up earlier
                // just starts the stop() fallback sooner, and on Java 20+/Android stop() throws, so the thread would keep
                // running next to the game thread
                t.join(2000); // 2 seconds wait
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
            if (t.isAlive()) {
                // last resort, see #8302: the eval thread may be stuck inside a single
                // evaluation or an infinite loop and never reach the cooperative exit
                try {
                    t.stop();
                } catch (UnsupportedOperationException | NoSuchMethodError ex) {
                    // Stop support: dropped by Android and Java 20 / 26 removed it completely - so sadly thread will keep running
                }
            }
            // TODO mark some as skipped to increase chance to find something playable next priority
            return Collections.emptyList();
        } finally {
            AITimeoutTracker.end(aiId);
        }
    }

    public CardCollection chooseCardsToDelve(int genericCost, CardCollection grave) {
        CardCollection toExile = new CardCollection();
        int numToExile = Math.min(grave.size(), genericCost);

        for (int i = 0; i < numToExile; i++) {
            Card chosen = null;
            for (final Card c : grave) {
                // Exile noncreatures first in case we can revive
                // Might wanna do some additional checking here for Flashback and the like
                if (!c.isCreature()) {
                    chosen = c;
                    break;
                }
            }
            if (chosen == null) {
                chosen = ComputerUtilCard.getWorstCreatureAI(grave);
            }

            if (chosen == null) {
                // Should never get here but... You know how it is.
                chosen = grave.get(0);
            }

            toExile.add(chosen);
            grave.remove(chosen);
        }
        return toExile;
    }

    public boolean doTrigger(SpellAbility sa, boolean mandatory) {
        if (sa instanceof WrappedAbility)
            return doTrigger(((WrappedAbility) sa).getWrappedAbility(), mandatory);
        if (sa.getApi() != null)
            return SpellApiToAi.Converter.get(sa).doTrigger(player, sa, mandatory);
        if (sa.getPayCosts().isFree() && !sa.usesTargeting()) {
            // For non-converted triggers (such as Cumulative Upkeep) that don't have costs or targets to worry about
            return true;
        }
        return false;
    }

    /**
     * Ai should run.
     *
     * @param sa the sa
     * @return true, if successful
     */
    public final boolean aiShouldRun(final CardTraitBase effect, final SpellAbility sa, final Card host, final GameEntity affected) {
        if (effect.hasParam("AILogic") && effect.getParam("AILogic").equalsIgnoreCase("ProtectFriendly")) {
            final Player controller = host.getController();
            if (affected instanceof Player) {
                return !((Player) affected).isOpponentOf(controller);
            }
            if (affected instanceof Card) {
                return !((Card) affected).getController().isOpponentOf(controller);
            }
        }
        if (effect.hasParam("AICheckSVar")) {
            final String svarToCheck = effect.getParam("AICheckSVar");
            String comparator = "GE";
            int compareTo = 1;

            if (effect.hasParam("AISVarCompare")) {
                final String fullCmp = effect.getParam("AISVarCompare");
                comparator = fullCmp.substring(0, 2);
                final String strCmpTo = fullCmp.substring(2);
                try {
                    compareTo = Integer.parseInt(strCmpTo);
                } catch (final Exception ignored) {
                    if (sa == null) {
                        compareTo = AbilityUtils.calculateAmount(host, host.getSVar(strCmpTo), effect);
                    } else {
                        compareTo = AbilityUtils.calculateAmount(host, host.getSVar(strCmpTo), sa);
                    }
                }
            }

            int left;
            if (sa == null) {
                left = AbilityUtils.calculateAmount(host, svarToCheck, effect);
            } else {
                left = AbilityUtils.calculateAmount(host, svarToCheck, sa);
            }
            return Expressions.compare(left, comparator, compareTo);
        } else if (effect.isKeyword(Keyword.DREDGE)) {
            return player.getCardsIn(ZoneType.Library).size() > 8 || player.isCardInPlay("Laboratory Maniac");
        } else return sa != null && doTrigger(sa, false);
    }

    public List<SpellAbility> chooseSaToActivateFromOpeningHand(List<SpellAbility> usableFromOpeningHand) {
        // AI would play everything. But limits to one copy of (Leyline of Singularity) and (Gemstone Caverns)

        List<SpellAbility> result = Lists.newArrayList();
        for (SpellAbility sa : usableFromOpeningHand) {
            // Is there a better way for the AI to decide this?
            if (doTrigger(sa, false)) {
                result.add(sa);
            }
        }

        boolean hasLeyline1 = false;
        SpellAbility saGemstones = null;

        List<SpellAbility> toRemove = Lists.newArrayList();
        for (SpellAbility sa : result) {
            String srcName = sa.getHostCard().getName();
            if ("Gemstone Caverns".equals(srcName)) {
                if (saGemstones == null)
                    saGemstones = sa;
                else
                    toRemove.add(sa);
            } else if ("Leyline of Singularity".equals(srcName)) {
                if (!hasLeyline1)
                    hasLeyline1 = true;
                else
                    toRemove.add(sa);
            }
        }
        result.removeAll(toRemove);

        // Play them last
        if (saGemstones != null) {
            result.remove(saGemstones);
            result.add(saGemstones);
        }

        return result;
    }

    public int chooseNumber(SpellAbility sa, String title, int min, int max) {
        if ("Squee's Revenge".equals(ComputerUtilAbility.getAbilitySourceName(sa))) {
            return SpecialCardAi.SqueesRevenge.chooseNumber(player, sa, min, max);
        }
        if (SpecialCardAi.LocalizedDestruction.NAME.equals(ComputerUtilAbility.getAbilitySourceName(sa))) {
            // Dead-card batch 2, row 113: the stock "Max" below pays every {E} we have and protects
            // only creatures with that power
            return SpecialCardAi.LocalizedDestruction.chooseEnergy(player, sa, min, max);
        }
        if (SpecialCardAi.TalionTheKindlyLord.NAME.equals(ComputerUtilAbility.getAbilitySourceName(sa))) {
            // Dead-card batch 3, row 1: the AILogic only approves the pre-cast ETB probe; the stock
            // fallthrough below answers Max (10), a number almost no opposing spell matches
            return SpecialCardAi.TalionTheKindlyLord.chooseNumber(player, sa, min, max);
        }
        if (SpecialCardAi.SanctumPrelate.NAME.equals(ComputerUtilAbility.getAbilitySourceName(sa))) {
            // Dead-card batch 4, row 47: the stock "Max" below answers 99, a number no spell has
            return SpecialCardAi.SanctumPrelate.chooseNumber(player, sa, min, max);
        }
        if (SpecialCardAi.WrathOfTheSkies.isWrath(sa)) {
            // Dead-card batch 3, row 29: the stock "Max" below pays every {E} we have, sweeping our
            // own commander and rocks with theirs
            return SpecialCardAi.WrathOfTheSkies.chooseEnergy(player, sa, min, max);
        }
        final Card source = sa.getHostCard();
        final String logic = sa.getParamOrDefault("AILogic", "Max");
        if ("GainLife".equals(logic)) {
            if (player.getLife() < 5 || player.getCardsIn(ZoneType.Hand).size() >= player.getMaxHandSize()) {
                return min;
            }
        } else if ("LoseLife".equals(logic)) {
            if (player.getLife() > 5) {
                return min;
            }
        } else if ("Min".equals(logic)) {
            return min;
        } else if ("DigACard".equals(logic)) {
            int random = MyRandom.getRandom().nextInt(Math.min(4, max)) + 1;
            if (player.getLife() < random + 5) {
                return min;
            } else {
                return random;
            }
        } else if ("Damnation".equals(logic)) {
            int chosenMax = player.getLife() - 1;
            int cardsInPlay = player.getCardsIn(ZoneType.Battlefield).size();
            return Math.min(chosenMax, cardsInPlay);
        } else if ("OptionalDraw".equals(logic)) {
            int cardsInLib = player.getCardsIn(ZoneType.Library).size();
            if (cardsInLib >= max && player.isCardInPlay("Laboratory Maniac")) {
                return max;
            }
            int cardsInHand = player.getCardsIn(ZoneType.Hand).size();
            int maxDraw = Math.min(player.getMaxHandSize() + 2 - cardsInHand, max);
            int maxCheckLib = Math.min(maxDraw, cardsInLib);
            return Math.max(min, maxCheckLib);
        } else if ("RepeatDraw".equals(logic)) {
            int remaining = player.getMaxHandSize() - player.getCardsIn(ZoneType.Hand).size()
                    + MyRandom.getRandom().nextInt(3);
            return Math.max(remaining, min) / 2;
        } else if ("LowestLoseLife".equals(logic)) {
            return MyRandom.getRandom().nextInt(Math.min(player.getLife() / 3, player.getWeakestOpponent().getLife())) + 1;
        } else if ("HighestLoseLife".equals(logic)) {
            // nobody to outbid
            if (AbilityUtils.getDefinedPlayers(source, sa.getParam("Defined"), sa).size() < 2) {
                return min;
            }
            int random = MyRandom.getRandom().nextInt(Math.max(player.getLife() / 3, 0) + 1);
            if (player.getLife() < random + 5) {
                return min;
            }
            return random;
        } else if ("HighestGetCounter".equals(logic)) {
            return MyRandom.getRandom().nextInt(3);
        } else if (sa.hasSVar("EnergyToPay")) {
            return AbilityUtils.calculateAmount(source, sa.getSVar("EnergyToPay"), sa);
        } else if ("Vermin".equals(logic)) {
            if (player.getLife() < 5) {
                return min;
            }

            return MyRandom.getRandom().nextInt(Math.max(player.getLife() - 5, 1));
        } else if ("SweepCreatures".equals(logic)) {
            int minAllowedChoice = AbilityUtils.calculateAmount(source, sa.getParam("Min"), sa);
            int choiceLimit = AbilityUtils.calculateAmount(source, sa.getParam("Max"), sa);
            int maxCreatures = 0;
            for (Player opp : player.getOpponents()) {
                maxCreatures = Math.max(maxCreatures, opp.getCreaturesInPlay().size());
            }
            return Math.min(choiceLimit, Math.max(minAllowedChoice, maxCreatures));
        } else if ("Random".equals(logic)) {
            return MyRandom.getRandom().nextInt((max - min) + 1) + min;
        }
        return max;
    }

    public int chooseNumber(SpellAbility sa, String title, List<Integer> options, Player relatedPlayer) {
        switch (sa.getApi()) {
            case SetLife: // Reverse the Sands
                if (relatedPlayer.equals(sa.getHostCard().getController())) {
                    return Collections.max(options);
                } else if (relatedPlayer.isOpponentOf(sa.getHostCard().getController())) {
                    return Collections.min(options);
                } else {
                    return options.get(0);
                }
            case ChooseNumber:
                if (sa.getHostCard().getName().equals("Emissary's Ploy")) {
                    // Count the amount of creatures in each CMC of 1,2,3 and choose that number
                    // If you have multiple ploys, technically AI should choose different numbers
                    // But thats not what happens currently
                    List<Integer> counter = Lists.newArrayList(0,0,0);
                    int max = 0;
                    int slot = 0;
                    for (Card c : relatedPlayer.getZone(ZoneType.Library).getCards()) {
                        if (!c.isCreature()) {
                            continue;
                        }

                        if (c.getCMC() > 0 && c.getCMC() < 4) {
                            counter.set(c.getCMC() - 1, counter.get(c.getCMC() - 1) + 1);
                        }
                    }
                    for(int i = 0; i < counter.size(); i++) {
                        if (counter.get(i) >= max) {
                            max = counter.get(i);
                            slot = i;
                        }
                    }
                    return slot;
                }

                return Aggregates.random(options);
            case RollDice:
                // Dead-card batch 2, row 96: Valiant Endeavor's chosen die sets the destroy threshold
                // and the other die the Knight count; the default below always took the first die as
                // the threshold. Every other dice card (Arcane, Grave and Reckless Endeavor among
                // them) keeps that default.
                if (SpecialCardAi.ValiantEndeavor.NAME.equals(ComputerUtilAbility.getAbilitySourceName(sa))) {
                    return SpecialCardAi.ValiantEndeavor.chooseResult(player, sa, options);
                }
                // Dead-card batch 3, row 34: Wild Endeavor's chosen die is its Beast count and the
                // other die its basic-land count. RollDiceEffect sorts the rolls ascending before
                // asking, so the default below gave the Beasts the lower die.
                if (SpecialCardAi.WildEndeavor.NAME.equals(ComputerUtilAbility.getAbilitySourceName(sa))) {
                    return SpecialCardAi.WildEndeavor.chooseResult(options);
                }
                return options.get(0);
            default:
                return options.get(0);
        }
    }

    public boolean confirmPayment(CostPart costPart) {
        throw new UnsupportedOperationException("AI is not supposed to reach this code at the moment");
    }

    public int attemptToAssist(SpellAbility sa, int max, int request) {
        Player activator = sa.getActivatingPlayer();

        if (game.getPlayers().size() == 2) {
            // Never help your opponent in a 2 player game
            return 0;
        }

        PlayerCollection allies = player.getAllies();

        if (allies.isEmpty()) {
            // AI only has opponents.
            // TODO: Maybe help out someone if it seems good for us, but who knows how you calculate that.
            // Probably needs some specific AI here.
            // If the spell is a creature, probably don't help.
            // If spell is a instant/sorcery, help based on the situation
            return 0;
        } else {
            // AI has allies, don't help out anyone but allies.
            if (!allies.contains(activator)) {
                return 0;
            }
        }

        // AI has decided to help. Now let's figure out how much they can help
        int mana = getAvailableManaEstimate(player, true);

        // TODO We should make a logical guess here, but for now just uh yknow randomly decide?
        // What do I want to play next? Can I still pay for that and have mana left over to help?
        // Is the spell I'm helping cast better for me than the thing I would cast?
        if (MyRandom.percentTrue(80)) {
            return 0;
        }

        int willingToPay = 0;
        if (mana >= request) {
            return request;
        } else {
            return mana;
        }
    }

    public CardCollection chooseCardsForEffect(CardCollectionView pool, SpellAbility sa, int min, int max, boolean isOptional, Map<String, Object> params) {
        if (sa == null || sa.getApi() == null) {
            throw new UnsupportedOperationException();
        }
        CardCollection result = new CardCollection();
        if (sa.hasParam("AIMaxAmount")) {
            max = AbilityUtils.calculateAmount(sa.getHostCard(), sa.getParam("AIMaxAmount"), sa);
        }
        switch (sa.getApi()) {
            case TwoPiles:
                // TODO: improve AI
                Card biggest = null;
                Card smallest = null;
                biggest = pool.get(0);
                smallest = pool.get(0);

                for (Card c : pool) {
                    if (c.getCMC() >= biggest.getCMC()) {
                        biggest = c;
                    } else if (c.getCMC() <= smallest.getCMC()) {
                        smallest = c;
                    }
                }
                result.add(biggest);

                if (max > 3 && !result.contains(smallest)) {
                    result.add(smallest);
                }
                break;
            case MultiplePiles:
                // Whims of the Fates {all, 0, 0}
                result.addAll(pool);
                break;
            case FlipOntoBattlefield:
                if ("DamageCreatures".equals(sa.getParam("AILogic"))) {
                    int maxToughness = Integer.parseInt(sa.getSubAbility().getParam("NumDmg"));
                    CardCollectionView rightToughness = CardLists.filter(pool, card -> card.getController().isOpponentOf(sa.getActivatingPlayer())
                            && card.getNetToughness() <= maxToughness
                            && card.canBeDestroyed());
                    Card bestCreature = ComputerUtilCard.getBestCreatureAI(rightToughness.isEmpty() ? pool : rightToughness);
                    if (bestCreature != null) {
                        result.add(bestCreature);
                        break;
                    }
                } else {
                    CardCollectionView viableOptions = CardLists.filter(pool, CardPredicates.isControlledByAnyOf(sa.getActivatingPlayer().getOpponents()), Card::canBeDestroyed);
                    Card best = ComputerUtilCard.getBestAI(viableOptions);
                    if (best != null) {
                        result.add(best);
                        break;
                    }
                }
                result.add(Aggregates.random(pool)); // should ideally never get here
                break;
            default:
                CardCollection editablePool = new CardCollection(pool);
                for (int i = 0; i < max; i++) {
                    Card c = player.getController().chooseSingleEntityForEffect(editablePool, sa, null, isOptional, params);
                    if (c == null) {
                        break;
                    }
                    result.add(c);
                    editablePool.remove(c);

                    // Special case for Bow to My Command which simulates a complex tap cost via ChooseCard
                    // TODO: consider enhancing support for tapXType<Any/...> in UnlessCost to get rid of this hack
                    if ("BowToMyCommand".equals(sa.getParam("AILogic"))) {
                        if (!sa.getHostCard().isInZone(ZoneType.Command)) {
                            // Make sure that other opponents do not tap for an already abandoned scheme
                            result.clear();
                            break;
                        }

                        int totPower = 0;
                        for (Card p : result) {
                            totPower += p.getNetPower();
                        }
                        if (totPower >= 8) {
                            break;
                        }
                    }
                }
        }

        // TODO: Hack for Phyrexian Dreadnought. Might need generalization (possibly its own AI logic)
        if ("Phyrexian Dreadnought".equals(ComputerUtilAbility.getAbilitySourceName(sa))) {
            result = SpecialCardAi.PhyrexianDreadnought.reviseCreatureSacList(player, sa, result);
        }

        return result;
    }

    public Map<DeckSection, List<? extends PaperCard>> complainCardsCantPlayWell(Deck myDeck) {
        Map<DeckSection, List<? extends PaperCard>> complaints = new HashMap<>();
        // When using simulation, AI should be able to figure out most cards.
        if (!usesFullSimulation()) {
            complaints = myDeck.getUnplayableAICards().unplayable;
        }
        return complaints;
    }

    // this is where the computer cheats
    // changes AllZone.getComputerPlayer().getZone(Zone.Library)

    /**
     * <p>
     * smoothComputerManaCurve.
     * </p>
     *
     * @param in an array of {@link forge.game.card.Card} objects.
     * @return an array of {@link forge.game.card.Card} objects.
     */
    public CardCollectionView cheatShuffle(CardCollectionView in) {
        if (in.size() < 20 || !getBoolProperty(AiProps.CHEAT_WITH_MANA_ON_SHUFFLE) || !getGame().getRules().isAllowCheatShuffle()) {
            return in;
        }

        final CardCollection library = new CardCollection(in);
        CardLists.shuffle(library);

        // remove all land, keep non-basicland in there, shuffled
        CardCollection land = CardLists.filter(library, CardPredicates.LANDS);
        for (Card c : land) {
            if (c.isLand()) {
                library.remove(c);
            }
        }

        try {
            // mana weave, total of 7 land
            // The Following have all been reduced by 1, to account for the
            // computer starting first.
            library.add(5, land.get(0));
            library.add(6, land.get(1));
            library.add(8, land.get(2));
            library.add(9, land.get(3));
            library.add(10, land.get(4));

            library.add(12, land.get(5));
            library.add(15, land.get(6));
        } catch (final IndexOutOfBoundsException e) {
            System.err.println("Error: cannot smooth mana curve, not enough land");
            return in;
        }

        // add the rest of land to the end of the deck
        for (Card card : land) {
            if (!library.contains(card)) {
                library.add(card);
            }
        }

        return library;
    } // smoothComputerManaCurve()

    public boolean chooseDirection(SpellAbility sa) {
        if (sa == null || sa.getApi() == null) {
            throw new UnsupportedOperationException();
        }
        // Left:True; Right:False
        if ("GainControl".equals(sa.getParam("AILogic")) && game.getPlayers().size() > 2) {
            CardCollection creats = CardLists.getType(game.getCardsIn(ZoneType.Battlefield), "Creature");
            CardCollection left = CardLists.filterControlledBy(creats, game.getNextPlayerAfter(player, Direction.Left));
            CardCollection right = CardLists.filterControlledBy(creats, game.getNextPlayerAfter(player, Direction.Right));
            if (!left.isEmpty() || !right.isEmpty()) {
                CardCollection all = new CardCollection(left);
                all.addAll(right);
                return left.contains(ComputerUtilCard.getBestCreatureAI(all));
            }
        }
        if ("Aminatou".equals(sa.getParam("AILogic")) && game.getPlayers().size() > 2) {
            CardCollection all = CardLists.filter(game.getCardsIn(ZoneType.Battlefield), CardPredicates.NONLAND_PERMANENTS);
            CardCollection left = CardLists.filterControlledBy(all, game.getNextPlayerAfter(player, Direction.Left));
            CardCollection right = CardLists.filterControlledBy(all, game.getNextPlayerAfter(player, Direction.Right));
            return Aggregates.sum(left, Card::getCMC) > Aggregates.sum(right, Card::getCMC);
        }
        return MyRandom.getRandom().nextBoolean();
    }

    public boolean chooseEvenOdd(SpellAbility sa) {
        String aiLogic = sa.getParamOrDefault("AILogic", "");

        if (aiLogic.equals("AlwaysEven")) {
            return false;
        } else if (aiLogic.equals("AlwaysOdd")) {
            return true;
        } else if (aiLogic.equals("Random")) {
            return MyRandom.getRandom().nextBoolean();
        } else if (aiLogic.equals("CMCInHand")) {
            CardCollectionView hand = sa.getActivatingPlayer().getCardsIn(ZoneType.Hand);
            int numEven = CardLists.filter(hand, CardPredicates.evenCMC()).size();
            int numOdd = CardLists.filter(hand, CardPredicates.oddCMC()).size();
            return numOdd > numEven;
        } else if (aiLogic.equals("CMCOppControls")) {
            CardCollectionView hand = sa.getActivatingPlayer().getOpponents().getCardsIn(ZoneType.Battlefield);
            int numEven = CardLists.filter(hand, CardPredicates.evenCMC()).size();
            int numOdd = CardLists.filter(hand, CardPredicates.oddCMC()).size();
            return numOdd > numEven;
        } else if (aiLogic.equals("CMCOppControlsByPower")) {
            // TODO: improve this to check for how dangerous those creatures actually are relative to host card
            CardCollectionView hand = sa.getActivatingPlayer().getOpponents().getCardsIn(ZoneType.Battlefield);
            int powerEven = Aggregates.sum(CardLists.filter(hand, CardPredicates.evenCMC()), Card::getNetPower);
            int powerOdd = Aggregates.sum(CardLists.filter(hand, CardPredicates.oddCMC()), Card::getNetPower);
            return powerOdd > powerEven;
        }
        return MyRandom.getRandom().nextBoolean(); // outside of any specific logic, choose randomly
    }

    public Card chooseCardToHiddenOriginChangeZone(ZoneType destination, List<ZoneType> origin, SpellAbility sa,
                                                   CardCollection fetchList, Player player2, Player decider) {
        if (usesFullSimulation()) {
            return simPicker.chooseCardToHiddenOriginChangeZone(destination, origin, sa, fetchList, player2, decider);
        }

        if (decider == player && sa.getApi() != ApiType.Learn && !ChangeZoneAi.filtersReshapeHarm(sa, decider)) {
            // Combo-aware AI, piece 1 (spec 2026-10-22-combo-aware-ai-core-design.md section 6.3): our own search for a
            // combo line of ours takes its missing piece (ComboFetch.searchPick, and Gifts Ungiven's completers) ahead
            // of the deck plan's pick below, which decides only when no line of ours has a pick; null, so upstream's
            // choice, for a deck holding no line. ChangeZoneAi asks the same two first, for the simulation picker that
            // reaches it without this method, and for Reshape's own search, left to it above: its harm filter comes
            // before them (and the deck plan has no pick for an artifact search). Draws nothing.
            final Card forLine = ComboFetch.searchPick(sa, destination, fetchList, player2, decider);
            if (forLine != null) {
                return forLine;
            }
            if (SpecialCardAi.GiftsUngiven.handles(sa)) {
                final Card giftsPick = ComboFetch.giftsPick(decider, fetchList);
                if (giftsPick != null) {
                    return giftsPick;
                }
            }
            // the win card of a cheating spell, the creature to put into the graveyard: what the deck is built for;
            // except for a search a dead-card pick of ChangeZoneAi decides (Praetor's Grasp, Isochron Scepter's
            // imprint, our own Scapeshift), which the fork's pick keeps (ChangeZoneAi.deadCardPickDecides)
            if (!ChangeZoneAi.deadCardPickDecides(destination, sa, player2, decider)) {
                final Card forCombo = deckPlan(player).combos.choose(player, destination, origin, fetchList);
                if (forCombo != null) {
                    return forCombo;
                }
            }
        }

        if (sa.getApi() == ApiType.Learn) {
            return LearnAi.chooseCardToLearn(fetchList, decider, sa);
        } else {
            return ChangeZoneAi.chooseCardToHiddenOriginChangeZone(destination, origin, sa, fetchList, player2, decider);
        }
    }

    public List<SpellAbility> orderPlaySa(List<SpellAbility> activePlayerSAs) {
        // list is only one or empty, no need to filter
        if (activePlayerSAs.size() < 2) {
            return activePlayerSAs;
        }

        // filter list by ApiTypes
        List<SpellAbility> discard = filterListByApi(activePlayerSAs, ApiType.Discard);
        List<SpellAbility> mandatoryDiscard = filterList(discard, SpellAbility::isMandatory);

        List<SpellAbility> draw = filterListByApi(activePlayerSAs, ApiType.Draw);

        List<SpellAbility> putCounter = filterListByApi(activePlayerSAs, ApiType.PutCounter);
        List<SpellAbility> putCounterAll = filterListByApi(activePlayerSAs, ApiType.PutCounterAll);

        List<SpellAbility> evolve = filterList(putCounter, CardTraitPredicates.isKeyword(Keyword.EVOLVE));

        List<SpellAbility> token = filterListByApi(activePlayerSAs, ApiType.Token);
        List<SpellAbility> pump = filterListByApi(activePlayerSAs, ApiType.Pump);
        List<SpellAbility> pumpAll = filterListByApi(activePlayerSAs, ApiType.PumpAll);

        List<SpellAbility> result = Lists.newArrayList(activePlayerSAs);

        // do mandatory discard early if hand is empty or has DiscardMe card
        CardCollectionView playerHand = player.getCardsIn(ZoneType.Hand);
        if (!playerHand.isEmpty() && !playerHand.anyMatch(CardPredicates.hasSVar("DiscardMe"))) {
            result.addAll(mandatoryDiscard);
            mandatoryDiscard.clear();
        }

        // optional Discard, probably combined with Draw
        result.addAll(discard);
        // do Draw before Discard
        result.addAll(draw);

        result.addAll(putCounterAll);
        // do putCounter before Draw/Discard because it can cause a Draw Trigger
        result.addAll(putCounter);
        // do Evolve Trigger before other PutCounter SpellAbilities
        result.addAll(evolve);

        // token should be added first so they might get the pump bonus
        result.addAll(pumpAll);
        result.addAll(pump);
        result.addAll(token);

        result.addAll(mandatoryDiscard);

        return result;
    }

    // TODO move to more common place
    private static <T> List<T> filterList(List<T> input, Predicate<? super T> pred) {
        List<T> filtered = input.stream().filter(pred).collect(Collectors.toList());
        input.removeAll(filtered);
        return filtered;
    }

    // TODO move to more common place
    public static <T extends TriggerReplacementBase> List<T> filterList(List<T> input, Function<SpellAbility, Object> pred, Object value) {
        return filterList(input, trb -> trb.ensureAbility() != null && pred.apply(trb.ensureAbility()) == value);
    }

    public static List<SpellAbility> filterListByApi(List<SpellAbility> input, ApiType type) {
        return filterList(input, SpellAbilityPredicates.isApi(type));
    }

    private <T extends CardTraitBase> List<T> filterListByAiLogic(List<T> list, final String logic) {
        return filterList(list, CardTraitPredicates.hasParam("AILogic", logic));
    }

    public List<AbilitySub> chooseModeForAbility(SpellAbility sa, List<AbilitySub> possible, int min, int num, boolean allowRepeat) {
        // Combo-aware AI, piece 2 (spec 2026-10-29-combo-aware-ai-loops-design.md section 6.8): Hullbreaker Horror's
        // cast trigger during one of the loop driver's cast and bounce cycles returns the cycle's other piece to our
        // hand (LoopDriver.charmModes). Null, and the stock choice below, for every other charm and outside such a
        // cycle; draws nothing.
        final List<AbilitySub> loopModes = loopDriver.charmModes(sa, possible);
        if (loopModes != null) {
            return loopModes;
        }
        if (simPicker != null) {
            return simPicker.chooseModeForAbility(sa, possible, min, num, allowRepeat);
        }
        return null;
    }

    public CardCollectionView chooseSacrificeType(String type, SpellAbility ability, boolean effect, int amount, final CardCollectionView exclude) {
        if (simPicker != null) {
            return simPicker.chooseSacrificeType(type, ability, effect, amount, exclude);
        }
        return ComputerUtil.chooseSacrificeType(player, type, ability, ability.getTargetCard(), effect, amount, exclude);
    }

    private boolean checkAiSpecificRestrictions(final SpellAbility sa) {
        // AI-specific restrictions specified as activation parameters in spell abilities

        if (sa.hasParam("AILifeThreshold")) {
            return player.getLife() > Integer.parseInt(sa.getParam("AILifeThreshold"));
        }

        return true;
    }

    // AI logic for choosing which replacement effect to apply happens here.
    public ReplacementEffect chooseSingleReplacementEffect(List<ReplacementEffect> list) {
        // no need to choose anything
        if (list.size() <= 1) {
            return list.get(0);
        }

        ReplacementType mode = list.get(0).getMode();

        if (mode.equals(ReplacementType.GainLife)) {
            List<ReplacementEffect> noGain = filterListByAiLogic(list, "NoLife");
            List<ReplacementEffect> loseLife = filterListByAiLogic(list, "LoseLife");
            List<ReplacementEffect> doubleLife = filterListByAiLogic(list, "DoubleLife");
            List<ReplacementEffect> lichDraw = filterListByAiLogic(list, "LichDraw");

            if (!noGain.isEmpty()) {
                // no lifegain is better than lose life
                return noGain.get(0);
            } else if (!loseLife.isEmpty()) {
                // lose life before double life to prevent lose double
                return loseLife.get(0);
            } else if (!lichDraw.isEmpty()) {
                // lich draw before double life to prevent to draw to much
                return lichDraw.get(0);
            } else if (!doubleLife.isEmpty()) {
                // other than that, do double life
                return doubleLife.get(0);
            }
        } else if (mode.equals(ReplacementType.DamageDone)) {
            List<ReplacementEffect> prevention = filterList(list, CardTraitPredicates.hasParam("Prevent"));

            // TODO when Protection is done as ReplacementEffect do them
            // before normal prevention
            if (!prevention.isEmpty()) {
                return prevention.get(0);
            }
        } else if (mode.equals(ReplacementType.Destroy)) {
            List<ReplacementEffect> shield = filterList(list, CardTraitPredicates.hasParam("ShieldCounter"));
            List<ReplacementEffect> regeneration = filterList(list, CardTraitPredicates.hasParam("Regeneration"));
            List<ReplacementEffect> umbraArmor = filterList(list, CardTraitPredicates.isKeyword(Keyword.UMBRA_ARMOR));
            List<ReplacementEffect> umbraArmorIndestructible = filterList(umbraArmor, x -> x.getHostCard().hasKeyword(Keyword.INDESTRUCTIBLE));

            // Indestructible umbra armor is the best
            if (!umbraArmorIndestructible.isEmpty()) {
                return umbraArmorIndestructible.get(0);
            }

            // then it might be better to remove shield counter if able?
            if (!shield.isEmpty()) {
                return shield.get(0);
            }

            // TODO get the RunParams for Affected to check if the creature already dealt combat damage for Regeneration effects
            // is using a Regeneration Effect better than using a Umbra Armor?
            if (!regeneration.isEmpty()) {
                return regeneration.get(0);
            }

            if (!umbraArmor.isEmpty()) {
                // sort them by cmc
                umbraArmor.sort(Comparator.comparing(CardTraitBase::getHostCard, Comparator.comparing(Card::getCMC)));
                return umbraArmor.get(0);
            }
        } else if (mode.equals(ReplacementType.Draw)) {
            List<ReplacementEffect> winGame = filterList(list, SpellAbility::getApi, ApiType.WinsGame);
            if (!winGame.isEmpty()) {
                return winGame.get(0);
            }
            // several dredge cards in the graveyard: the one that suits the size of the library, not the first of the list
            final ReplacementEffect dredge = deckPlan(player).combos.chooseDredge(player, list);
            if (dredge != null) {
                return dredge;
            }
        }

        // TODO always lower counters with Vorinclex first, might turn it from 1 to 0 as final

        return list.get(0);
    }

}
