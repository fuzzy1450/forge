package forge.ai;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.testng.AssertJUnit;
import org.testng.annotations.Test;

import forge.ai.simulation.ComboGuard;
import forge.ai.simulation.ComboPlan;
import forge.game.Game;
import forge.game.GameActionUtil;
import forge.game.ability.ApiType;
import forge.game.card.Card;
import forge.game.cost.Cost;
import forge.game.cost.CostPart;
import forge.game.cost.CostReturn;
import forge.game.phase.PhaseHandler;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import forge.util.collect.FCollection;

/**
 * The self-kill fix's priority-decision sites, asked through the AI's own decision (MTG_DeckMaker's self-kill fix,
 * DESIGN.md section 2): S3, the heuristic counter; S3b, the heuristic walk; S4, the final veto behind both. Every
 * row plays with Flat Monte Carlo off, as the GUI's and N's seats do, and as every seat's heuristics do on the paths
 * the rows name, and asks what {@code chooseSpellAbilityToPlay} returns. The class names nothing the fix added (no
 * DelayedLoss member, no vetoDelayedLoss), so it compiles at the parent 65940c9d323, where each row fails with the
 * play its site is there to stop; where a row needs a verdict it asks ComboGuard.problem, which asks DelayedLoss
 * first. Rows that must call what the fix added are DelayedLossVetoApiTest's. The resolution sites' rows (S5, S5b,
 * S6, S6b) ask PlayerControllerAi and DiscoverAi, as the effects that resolve ask them, under the same rule.
 */
public class DelayedLossVetoTest extends AITest {

    // --------------------------------------------------------------------------------------------- helpers

    private static Player opponentOf(final Game game, final Player ai) {
        return game.getPlayers().get(0) == ai ? game.getPlayers().get(1) : game.getPlayers().get(0);
    }

    /** The AI deadline off, as served games have it: no answer here depends on the test game's deadline. */
    private static void noDeadline(final Game game) {
        game.AI_TIMEOUT = Integer.MAX_VALUE;
        game.AI_CAN_USE_TIMEOUT = false;
    }

    /** The name of the card the AI's decision plays, or null when it passes. */
    private static String nextPlay(final Player ai) {
        final List<SpellAbility> chosen = ((PlayerControllerAi) ai.getController()).chooseSpellAbilityToPlay();
        return chosen == null || chosen.isEmpty() ? null : chosen.get(0).getHostCard().getName();
    }

    private static String verdict(final Player ai, final SpellAbility sa) {
        return ComboGuard.problem(ai, sa, null, ComboPlan.EMPTY);
    }

    /** A card cast as far as the stack by {@code p}, with nothing paid, aimed at {@code target} when one is given. */
    private SpellAbility onStack(final Game game, final String name, final Player p, final SpellAbility target) {
        final Card c = createCard(name, p);
        c.setGameTimestamp(game.getNextTimestamp());
        game.getStackZone().add(c);
        final SpellAbility sa = c.getFirstSpellAbility();
        sa.setActivatingPlayer(p);
        if (target != null) {
            sa.getTargets().add(target);
        }
        game.getStack().add(sa);
        return sa;
    }

    /** A spell's alternative-cost version with a part of this kind (Daze's Island return), the AI its activator. */
    private static SpellAbility alternative(final Player ai, final SpellAbility sa,
                                            final Class<? extends CostPart> part) {
        for (final SpellAbility alt : GameActionUtil.getAlternativeCosts(sa, ai, true)) {
            if (alt.getPayCosts() != null && alt.getPayCosts().hasSpecificCostType(part)) {
                alt.setActivatingPlayer(ai);
                return alt;
            }
        }
        throw new AssertionError(sa.getHostCard() + " has no alternative cost with a " + part.getSimpleName());
    }

    /** The spell ability with the AI its activator, aimed at {@code target} alone. */
    private static SpellAbility aimed(final SpellAbility sa, final Player ai, final SpellAbility target) {
        sa.setActivatingPlayer(ai);
        sa.resetTargets();
        sa.getTargets().add(target);
        return sa;
    }

    /** Pact of the Titan resolved in the AI's own first main phase: a debt due at its next upkeep, {4}{R}. */
    private void resolvePactOfTheTitan(final Game game, final Player ai) {
        fillLibrary(ai, 10);
        fillLibrary(opponentOf(game, ai), 10);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, ai);
        onStack(game, "Pact of the Titan", ai, null);
        playUntilStackClear(game);
        AssertJUnit.assertTrue("Pact of the Titan resolved: its Giant is on the battlefield",
                ai.getCreaturesInPlay().anyMatch(Card::isToken));
    }

    /**
     * The AI's upkeep with its Pact of Negation's trigger on the stack, a debt due now ({3}{U}{U}): the Pact resolved
     * in the opponent's first main phase over their Grizzly Bears, then the game played into the AI's upkeep. The AI
     * controls nothing yet, and neither does the opponent.
     */
    private SpellAbility upkeepWithThePactsTrigger(final Game game, final Player ai) {
        final Player opp = opponentOf(game, ai);
        noDeadline(game);
        fillLibrary(ai, 10);
        fillLibrary(opp, 10);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, opp);
        final SpellAbility bears = onStack(game, "Grizzly Bears", opp, null);
        onStack(game, "Pact of Negation", ai, bears);
        game.getPhaseHandler().onStackResolved();
        playUntilStackClear(game);
        AssertJUnit.assertEquals("Pact of Negation resolved: the Bears were countered", 1,
                countCardsWithName(game, "Grizzly Bears", ZoneType.Graveyard));
        for (int i = 0; i < 500 && !game.isGameOver()
                && !(game.getPhaseHandler().is(PhaseType.UPKEEP, ai) && !game.getStack().isEmpty()); i++) {
            game.getPhaseHandler().mainLoopStep();
        }
        AssertJUnit.assertTrue("the AI's upkeep with the Pact's trigger on the stack:\n" + gameStateToString(game),
                game.getPhaseHandler().is(PhaseType.UPKEEP, ai) && !game.getStack().isEmpty());
        final SpellAbility trigger = game.getStack().peekAbility();
        AssertJUnit.assertEquals("the Pact's pay-or-lose trigger is on top", ApiType.LosesGame, trigger.getApi());
        return trigger;
    }

    // --------------------------------------------------------------------------------------------- S3

    /**
     * The opponent's first main phase with their Serra Angel on the stack (mana value 5, above CounterAi's random
     * refusals of cheap spells), and the AI with Island and four Mountains: five lands (Pact of Negation's NumLands
     * GE5) and one blue source for its {3}{U}{U}. Pact of Negation in hand; with {@code manaLeak}, Mana Leak beside
     * it, and the opponent with Island x2 and Gaea's Cradle untapped and no creature: three mana sources, by which
     * ComputerUtil.counterSpellRestriction ranks Mana Leak at -9 against the Pact's -5, but two mana, which cannot
     * pay Mana Leak's {3}, so CounterAi still casts it. Flat MC off: the heuristic counter is returned directly
     * (AiController.java:2587).
     */
    private Game counterBoard(final boolean manaLeak) {
        final Game game = initAndCreateGame();
        noDeadline(game);
        final Player ai = game.getPlayers().get(1);
        final Player opp = opponentOf(game, ai);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, opp);
        addCard("Island", ai);
        addCards("Mountain", 4, ai);
        addCardToZone("Pact of Negation", ai, ZoneType.Hand);
        if (manaLeak) {
            addCardToZone("Mana Leak", ai, ZoneType.Hand);
            addCards("Island", 2, opp);
            addCard("Gaea's Cradle", opp);
            AssertJUnit.assertEquals("the opponent's mana sources, as Mana Leak's rank counts them", 3,
                    ComputerUtilMana.getAvailableManaSources(opp, true).size());
            AssertJUnit.assertEquals("the opponent's mana, as CounterAi weighs it against Mana Leak's {3}", 2,
                    ComputerUtilMana.getAvailableManaEstimate(opp));
        }
        onStack(game, "Serra Angel", opp, null);
        return game;
    }

    /**
     * S3 (DESIGN.md D11, RULINGS-R3 M24): the heuristic counter skips a counterspell DelayedLoss refuses and picks the
     * next. With Pact of Negation alone the AI does not cast it: one blue source cannot pay {3}{U}{U} at its upkeep.
     * With Mana Leak beside it, on the board {@link #counterBoard} sets up, the AI picks Mana Leak. Today
     * chooseCounterSpell picks the Pact both times (NumLands 5).
     */
    @Test(timeOut = 120_000)
    public void theHeuristicCounterSkipsAnUncoveredPact() {
        final String alone = nextPlay(counterBoard(false).getPlayers().get(1));
        final String withManaLeak = nextPlay(counterBoard(true).getPlayers().get(1));
        AssertJUnit.assertEquals("[Pact of Negation alone, beside Mana Leak]: an uncovered Pact is never the counter",
                Arrays.asList(null, "Mana Leak"), Arrays.asList(alone, withManaLeak));
    }

    // --------------------------------------------------------------------------------------------- S3b

    /**
     * S3b (DESIGN.md D27, round 2's B3): the heuristic walk skips a play DelayedLoss refuses and returns its next
     * approved play. Flat MC off, the AI's own second main phase, a land played this turn and none in hand. Pact of
     * the Titan resolved earlier this turn: {4}{R} is due at the next upkeep, and Mountain and Forest x4, untapped,
     * are exactly that. Harrow (its additional cost sacrifices a land; SacCost$Land.Basic, and a land to the
     * battlefield the heuristics approve in a main phase) sorts before Jungle Lion, a one-drop. Harrow's sacrifice
     * would leave four lands for the debt ("debt-unprotected"): the walk skips it and returns Jungle Lion. Today the
     * walk returns Harrow (and with S3b removed, the final veto alone would make this decision a pass).
     */
    @Test(timeOut = 300_000)
    public void theWalkSkipsARefusedPlayAndPlaysOn() {
        final Game game = initAndCreateGame();
        noDeadline(game);
        final Player ai = game.getPlayers().get(1);
        for (int i = 0; i < 6; i++) {
            addCardToZone("Forest", ai, ZoneType.Library); // the basic lands Harrow finds
        }
        resolvePactOfTheTitan(game, ai);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN2, ai);
        ai.setLandsPlayedThisTurn(1);
        addCard("Mountain", ai);
        addCards("Forest", 4, ai);
        addCardToZone("Harrow", ai, ZoneType.Hand);
        addCardToZone("Jungle Lion", ai, ZoneType.Hand);
        game.getAction().checkStateEffects(true);
        AssertJUnit.assertEquals("the walk plays on past the refused Harrow", "Jungle Lion", nextPlay(ai));
    }

    // --------------------------------------------------------------------------------------------- S3 at the upkeep

    /**
     * Round 2's B1, at S3: the AI's upkeep, its Pact of Negation's trigger on the stack ({3}{U}{U}, a debt due now)
     * and the opponent's Serra Angel above it; Island x5 untapped, an Island in hand (no land is played before the
     * trigger resolves); Daze and Counterspell in hand, and the opponent with no mana for Daze's {1}. Flat MC off and
     * an ability on the stack: the heuristic counter answers. Counterspell's {U}{U} and Daze's {1}{U} may tap two of
     * the five Islands the trigger needs (5 - 2 < 5), and Daze's alternative cost returns one (4 left): the AI casts
     * neither. With a sixth untapped Island, Daze's alternative cost leaves five and is allowed, while Counterspell is
     * still refused (6 - 2 < 5); with a seventh, both are allowed. Today chooseCounterSpell returns Daze, and nothing
     * is refused.
     */
    @Test(timeOut = 300_000)
    public void aResponseAtTheUpkeepCannotSpendThePactsMana() {
        final Game game = initAndCreateGame();
        final Player ai = game.getPlayers().get(1);
        upkeepWithThePactsTrigger(game, ai);
        final SpellAbility angel = onStack(game, "Serra Angel", opponentOf(game, ai), null);
        addCards("Island", 5, ai);
        addCardToZone("Island", ai, ZoneType.Hand);
        final Card daze = addCardToZone("Daze", ai, ZoneType.Hand);
        final Card counterspell = addCardToZone("Counterspell", ai, ZoneType.Hand);
        final String chosen = nextPlay(ai);

        addCard("Island", ai);
        final SpellAbility dazeReturn =
                aimed(alternative(ai, daze.getFirstSpellAbility(), CostReturn.class), ai, angel);
        final SpellAbility hardCast = aimed(counterspell.getFirstSpellAbility(), ai, angel);
        final String dazeAtSix = verdict(ai, dazeReturn);
        final String counterspellAtSix = verdict(ai, hardCast);
        addCard("Island", ai);
        final String dazeAtSeven = verdict(ai, dazeReturn);
        final String counterspellAtSeven = verdict(ai, hardCast);
        AssertJUnit.assertEquals("[the choice with Island x5; Daze's return and Counterspell with Island x6; the"
                        + " same with Island x7]",
                Arrays.asList(null, null, "debt-due-now", null, null),
                Arrays.asList(chosen, dazeAtSix, counterspellAtSix, dazeAtSeven, counterspellAtSeven));
    }

    // --------------------------------------------------------------------------------------------- S5

    /**
     * Round 2's M11 board for the free casts at resolution (S5, S5b): the opponent's first main phase, their Serra
     * Angel on the stack (mana value 5, above CounterAi's random refusals of cheap spells), and the AI with Island x5:
     * five lands, Pact of Negation's NumLands GE5, which would also pay its {3}{U}{U} at the next upkeep. Flat MC off.
     */
    private Game freeCastBoard() {
        final Game game = initAndCreateGame();
        noDeadline(game);
        final Player ai = game.getPlayers().get(1);
        final Player opp = opponentOf(game, ai);
        fillLibrary(ai, 10);
        fillLibrary(opp, 10);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, opp);
        addCards("Island", 5, ai);
        onStack(game, "Serra Angel", opp, null);
        return game;
    }

    /**
     * S5 (DESIGN.md D13): an optional free cast of a delayed-loss card at resolution is declined, covered or not.
     * Pact of Negation in the AI's exile, as cascade or Beseech the Mirror leaves it, cast through
     * playSaFromPlayEffect as PlayEffect asks it: a copy without its mana cost, its cost not mandatory. The board
     * covers the Pact's debt, and the free Pact is still declined: nothing is added to the stack. Today
     * canPlayFromEffectAI approves it on this board, and the Pact is cast.
     */
    @Test(timeOut = 120_000)
    public void anOptionalFreeCastOfAFlaggedCardIsDeclined() {
        final Game game = freeCastBoard();
        final Player ai = game.getPlayers().get(1);
        final SpellAbility pact = addCardToZone("Pact of Negation", ai, ZoneType.Exile).getFirstSpellAbility();
        pact.setActivatingPlayer(ai);
        final SpellAbility free = pact.copyWithNoManaCost();
        AssertJUnit.assertFalse("the free cast is optional", free.getPayCosts().isMandatory());
        final boolean cast = ((PlayerControllerAi) ai.getController()).playSaFromPlayEffect(free);
        AssertJUnit.assertEquals("[whether the free Pact of Negation was cast, the stack's size]: declined, and the"
                        + " stack holds the Serra Angel alone",
                Arrays.asList(false, 1), Arrays.asList(cast, game.getStack().size()));
    }

    /**
     * Review TB-1's row: S5 leaves a mandatory free cast alone (DESIGN.md S5, D13: "a mandatory cast is still cast:
     * the rules force it"). S5's board; Pact of Negation's free copy, its cost and its targeting made mandatory as
     * PlayEffect makes them when its effect is not optional (PlayEffect.java:392-405, 432-434): the Pact is cast, on
     * top of the Serra Angel. A guards row: the parent, which has no S5, casts it too.
     */
    @Test(timeOut = 120_000)
    public void reviewAMandatoryFreeCastOfAFlaggedCardIsStillCast() {
        final Game game = freeCastBoard();
        final Player ai = game.getPlayers().get(1);
        final SpellAbility pact = addCardToZone("Pact of Negation", ai, ZoneType.Exile).getFirstSpellAbility();
        pact.setActivatingPlayer(ai);
        final SpellAbility forced = pact.copyWithNoManaCost();
        AssertJUnit.assertTrue("Pact of Negation's free copy targets", forced.usesTargeting());
        forced.getPayCosts().setMandatory(true);
        forced.getTargetRestrictions().setMandatory(true);
        final boolean cast = ((PlayerControllerAi) ai.getController()).playSaFromPlayEffect(forced);
        AssertJUnit.assertEquals("[whether the mandatory free Pact of Negation was cast, the stack's size, the card on"
                        + " top]: cast, above the Serra Angel", Arrays.asList(true, 2, "Pact of Negation"),
                Arrays.asList(cast, game.getStack().size(), game.getStack().peekAbility().getHostCard().getName()));
    }

    // --------------------------------------------------------------------------------------------- S5b

    /**
     * Whether the AI casts the card a Discover found, asked as DiscoverEffect asks it: the controller's confirmAction
     * for the Discover ability, the found card (already exiled) in its params.
     */
    private static boolean castsTheDiscoveredCard(final Player ai, final SpellAbility discover, final Card found) {
        final Map<String, Object> params = new HashMap<>();
        params.put("Card", found);
        return ai.getController().confirmAction(discover, null, "", Arrays.asList("Cast", "Hand"), found, params);
    }

    /**
     * S5b (DESIGN.md D13, round 1's C5): a discovered delayed-loss card is put into the hand, not cast. Discover marks
     * its free cast mandatory, so the choice is DiscoverAi.confirmAction's: on S5's board the AI's Hit the Mother Lode
     * (Discover 10) has exiled Pact of Negation, then, asked again, Lightning Bolt. The Pact goes to the hand; the
     * Bolt, which nothing flags, is still cast. Today the Pact is cast too.
     */
    @Test(timeOut = 120_000)
    public void aDiscoveredPactGoesToTheHand() {
        final Game game = freeCastBoard();
        final Player ai = game.getPlayers().get(1);
        final SpellAbility discover = addCardToZone("Hit the Mother Lode", ai, ZoneType.Hand).getFirstSpellAbility();
        discover.setActivatingPlayer(ai);
        final boolean pact = castsTheDiscoveredCard(ai, discover, addCardToZone("Pact of Negation", ai, ZoneType.Exile));
        final boolean bolt = castsTheDiscoveredCard(ai, discover, addCardToZone("Lightning Bolt", ai, ZoneType.Exile));
        AssertJUnit.assertEquals("[whether the discovered Pact of Negation is cast, whether the discovered Lightning"
                        + " Bolt is]", Arrays.asList(false, true), Arrays.asList(pact, bolt));
    }

    // --------------------------------------------------------------------------------------------- S6

    /** How many of these cards are tapped. */
    private static int tapped(final List<Card> cards) {
        int n = 0;
        for (final Card c : cards) {
            n += c.isTapped() ? 1 : 0;
        }
        return n;
    }

    /**
     * Plays the game on from turn {@code turn} until the AI's next upkeep is over: its untap step and upkeep, where
     * Pact of the Titan's trigger asks for {4}{R}. Stops in the draw step that follows, or when the game is over.
     */
    private static void playThroughTheNextUpkeep(final Game game, final Player ai, final int turn) {
        final PhaseHandler ph = game.getPhaseHandler();
        for (int i = 0; i < 2000 && !game.isGameOver() && !(ph.isPlayerTurn(ai) && ph.getTurn() > turn
                && ph.getPhase() != null && ph.getPhase().isAfter(PhaseType.UPKEEP)); i++) {
            ph.mainLoopStep();
        }
    }

    /**
     * T-UPK (DESIGN.md D15): the Pact's pay-or-lose trigger resolves before Mana Vault's optional untap. Pact of the
     * Titan resolved in the AI's first main phase ({4}{R} due at its next upkeep), Mountain x5 and a tapped Mana Vault,
     * then the game played on through the AI's next upkeep: the AI is still in the game, and the five Mountains paid
     * the debt. Had Mana Vault's trigger resolved first, paying its {4} would have left one Mountain and the Vault's
     * {C}{C}{C} for {4}{R}. Green today: it pins D15.
     */
    @Test(timeOut = 300_000)
    public void thePactIsPaidBeforeManaVaultsUntap() {
        final Game game = initAndCreateGame();
        noDeadline(game);
        final Player ai = game.getPlayers().get(1);
        final List<Card> mountains = addCards("Mountain", 5, ai);
        addCard("Mana Vault", ai).setTapped(true);
        resolvePactOfTheTitan(game, ai);
        playThroughTheNextUpkeep(game, ai, game.getPhaseHandler().getTurn());
        AssertJUnit.assertEquals("[whether the AI lost, its Mountains tapped once its upkeep is over]",
                Arrays.asList(false, 5), Arrays.asList(ai.hasLost(), tapped(mountains)));
    }

    /**
     * RU-31 (review selfkill-mana F3 b): the Pact's unless payment at its trigger's resolution takes Victory Chimes'
     * ChoosePlayer-rooted mana ability, which upstream's getAIPlayableMana now lists. Pact of the Titan resolved in the
     * AI's first main phase ({4}{R} due at its next upkeep), Mountain x4 and Victory Chimes, then the game played on
     * through the AI's next upkeep: the AI is still in the game, and the Chimes and the four Mountains paid the debt.
     */
    @Test(timeOut = 300_000)
    public void victoryChimesPaysThePactAtTheUpkeep() {
        final Game game = initAndCreateGame();
        noDeadline(game);
        final Player ai = game.getPlayers().get(1);
        final List<Card> mountains = addCards("Mountain", 4, ai);
        final Card chimes = addCard("Victory Chimes", ai);
        resolvePactOfTheTitan(game, ai);
        playThroughTheNextUpkeep(game, ai, game.getPhaseHandler().getTurn());
        AssertJUnit.assertEquals("[whether the AI lost, its Mountains tapped, the Chimes tapped, once its upkeep is over]",
                Arrays.asList(false, 4, true), Arrays.asList(ai.hasLost(), tapped(mountains), chimes.isTapped()));
    }

    /**
     * T-UPK-HELD (DESIGN.md S6, D28, round 1's L4): a mana source the AI holds for a later spell still pays its Pact.
     * Pact of the Titan resolved in the AI's first main phase ({4}{R} due at its next upkeep), Mountain x5, exactly the
     * debt; then, in the opponent's turn, one Mountain held for the opponent's declare-blockers step and one for the
     * AI's own second main phase: holds its memory keeps until its own end step, and which its payments refuse. Played
     * on through the AI's upkeep: the AI is still in the game, and its payment of the Pact cleared both holds. Today the
     * two held Mountains are refused at the upkeep, three Mountains do not pay {4}{R}, and the AI loses.
     */
    @Test(timeOut = 300_000)
    public void aHeldSourceStillPaysThePact() {
        final Game game = initAndCreateGame();
        noDeadline(game);
        final Player ai = game.getPlayers().get(1);
        final List<Card> mountains = addCards("Mountain", 5, ai);
        resolvePactOfTheTitan(game, ai);
        final int turn = game.getPhaseHandler().getTurn();
        game.getPhaseHandler().devModeSet(PhaseType.MAIN2, opponentOf(game, ai));
        AiCardMemory.rememberCard(ai, mountains.get(0), AiCardMemory.MemorySet.HELD_MANA_SOURCES_FOR_ENEMY_DECLBLK);
        AiCardMemory.rememberCard(ai, mountains.get(1), AiCardMemory.MemorySet.HELD_MANA_SOURCES_FOR_MAIN2);
        playThroughTheNextUpkeep(game, ai, turn);
        AssertJUnit.assertEquals("[whether the AI lost, its hold for the opponent's blockers empty, its hold for its"
                        + " second main phase empty]", Arrays.asList(false, true, true),
                Arrays.asList(ai.hasLost(),
                        AiCardMemory.isMemorySetEmpty(ai, AiCardMemory.MemorySet.HELD_MANA_SOURCES_FOR_ENEMY_DECLBLK),
                        AiCardMemory.isMemorySetEmpty(ai, AiCardMemory.MemorySet.HELD_MANA_SOURCES_FOR_MAIN2)));
    }

    // --------------------------------------------------------------------------------------------- S6b

    /** Rhystic Study on {@code opp}'s battlefield, and the draw its trigger offers them unless the AI pays {1}. */
    private SpellAbility rhysticStudysDraw(final Player opp) {
        final SpellAbility draw = addCard("Rhystic Study", opp).getTriggers().get(0).ensureAbility();
        draw.setActivatingPlayer(opp);
        return draw;
    }

    /** Whether the AI pays the {1} the ability asks of it, asked as an unless cost asks it: payCostToPreventEffect. */
    private static boolean paysOne(final Player ai, final SpellAbility asking) {
        return ai.getController().payCostToPreventEffect(new Cost("1", true), asking, false, new FCollection<>(ai));
    }

    /**
     * S6b (DESIGN.md D32, round 2's B1): an unless payment is declined when it would spend the mana of the AI's own
     * debt due now. The AI's upkeep with its Pact of Negation's trigger on the stack ({3}{U}{U}), Island x5 untapped:
     * exactly the debt. The opponent's Rhystic Study asks the AI for {1}, as after a free Force of Will in that window:
     * declined, and no Island is tapped. With a sixth untapped Island the {1} is paid (6 - 1 >= 5); and with no debt at
     * all (another game, the AI's upkeep, Island x5) it is paid. Today the stock path pays the {1} at five Islands too,
     * and the trigger then finds four.
     */
    @Test(timeOut = 300_000)
    public void anUnlessPaymentDoesNotSpendThePactsMana() {
        final Game game = initAndCreateGame();
        final Player ai = game.getPlayers().get(1);
        upkeepWithThePactsTrigger(game, ai);
        final List<Card> islands = addCards("Island", 5, ai);
        final SpellAbility draw = rhysticStudysDraw(opponentOf(game, ai));
        final boolean paidAtFive = paysOne(ai, draw);
        final int tappedAtFive = tapped(islands);
        addCard("Island", ai);
        final boolean paidAtSix = paysOne(ai, draw);

        final Game noDebt = initAndCreateGame();
        noDeadline(noDebt);
        final Player ai2 = noDebt.getPlayers().get(1);
        noDebt.getPhaseHandler().devModeSet(PhaseType.UPKEEP, ai2);
        addCards("Island", 5, ai2);
        final boolean paidWithNoDebt = paysOne(ai2, rhysticStudysDraw(opponentOf(noDebt, ai2)));
        AssertJUnit.assertEquals("[the {1} paid at Island x5, the Islands tapped then, the {1} paid at Island x6, the"
                        + " {1} paid with no debt]", Arrays.asList(false, 0, true, true),
                Arrays.asList(paidAtFive, tappedAtFive, paidAtSix, paidWithNoDebt));
    }

    // The play-before-land return's veto (RULINGS-R3 M23) has no row here: no board reaches that return with a play
    // DelayedLoss refuses. The pass chooses through the walk, where S3b has asked DelayedLoss already, and none of
    // the eleven cards carries PlayBeforeLandDrop (Mox Diamond, Scorched Earth and Thundering Wurm do). M23's
    // fall-through is pinned in DelayedLossVetoApiTest, through a veto that refuses the pass's own pick.
}
