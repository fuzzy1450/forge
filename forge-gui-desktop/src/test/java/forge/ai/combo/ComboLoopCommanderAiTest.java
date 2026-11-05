package forge.ai.combo;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.testng.annotations.Test;

import com.google.common.collect.Lists;

import forge.ai.AITest;
import forge.ai.AiController;
import forge.ai.AiPlayDecision;
import forge.ai.ComputerUtilCost;
import forge.ai.LobbyPlayerAi;
import forge.ai.SpecialCardAi;
import forge.ai.SpellApiToAi;
import forge.deck.Deck;
import forge.deck.DeckSection;
import forge.game.Game;
import forge.game.GameRules;
import forge.game.GameStage;
import forge.game.GameType;
import forge.game.Match;
import forge.game.card.Card;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.player.RegisteredPlayer;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import forge.util.MyRandom;

/**
 * Our commander's cast in our Main 1 (spec 2026-10-29-combo-aware-ai-loops-design.md section 6.7, amended 2026-11-04
 * (the commander cast in Main 1); the Kinnan cast diagnosis's option A, tools/combo-ai/sdd/p2-kinnan-cast-diagnosis.md,
 * Evan's choice): for a deck holding a loop line, our own commander's cast, from our command zone or our hand, is not
 * held for Main 2 in our Main 1 while the commander is a piece of a loop line the deck holds and real mana pays its
 * cast (LoopDriver.commanderInMainOne, through ComboHold.liftsMainTwoWait, from
 * PermanentCreatureAi.checkPhaseRestrictions), so the walk casts it there. Every other cast keeps PermanentAi's wait
 * for Main 2, the diagnosis's gate 1, and a deck holding no loop line, a commander that is no piece of a line the deck
 * holds and a window G1 (SpecialCardAi.CommanderCastCeiling.affordable) refuses play as stock, decision for decision
 * and draw for draw. G1 is a ceiling, not a payability test: a window it over-counts is lifted, and the payment then
 * refuses the cast after drawing (spec section 9). The boards are the diagnosis's probe boards: a two-player Commander
 * game, the AI (player 1) in its own Main 1 of game turn 3 with no AI deadline, both seats on the Default profile, our
 * commander in our command zone (initVariantsZones, whose effect casts it from there) and the registered deck the
 * tracker reads, each library holding 30 lands; the opponent's commander is Azami, Lady of Scrolls, unless a board
 * names another. The smoke's whole game is ComboLoopSmokeGameTest's.
 */
public class ComboLoopCommanderAiTest extends AITest {
    private static final String KINNAN = "Kinnan, Bonder Prodigy";
    private static final String THRASIOS = "Thrasios, Triton Hero";
    private static final String BASALT = "Basalt Monolith";
    private static final String GRIM = "Grim Monolith";
    private static final String NYXBLOOM = "Nyxbloom Ancient";
    private static final String ORACLE = "Thassa's Oracle";
    private static final String CONSULTATION = "Demonic Consultation";
    private static final String FOREST = "Forest";
    private static final String ISLAND = "Island";
    private static final String AZAMI = "Azami, Lady of Scrolls";
    private static final String MULDROTHA = "Muldrotha, the Gravetide";
    private static final String FBLTHP = "Fblthp, Impossibly Lost";
    private static final String COMMAND_TOWER = "Command Tower";
    private static final String SOL_RING = "Sol Ring";
    /** The rock untap of Kinnan and Basalt Monolith. */
    private static final String KINNAN_BASALT = "129-4131";
    /** The rock untap of Grim Monolith and Nyxbloom Ancient, which names no Kinnan. */
    private static final String GRIM_NYXBLOOM = "2585-3430";
    /** Piece 1's line of Demonic Consultation and Fblthp, Impossibly Lost: no loop line. */
    private static final String CONSULTATION_FBLTHP = "742-8266";

    private Game game;
    private Player ai;

    /** The board (the class comment): {@code commander}, unless null, our commander in our command zone, and
     *  {@code main}, one copy each, the rest of our registered deck; nothing placed yet. */
    private void start(final String commander, final String... main) {
        startAgainst(AZAMI, commander, main);
    }

    /** {@link #start}'s board with {@code theirCommander} the opponent's commander, in their command zone. */
    private void startAgainst(final String theirCommander, final String commander, final String... main) {
        final Deck ours = new Deck("ours");
        if (commander != null) {
            ours.getOrCreate(DeckSection.Commander).add(commander, 1);
        }
        for (final String name : main) {
            ours.getMain().add(name, 1);
        }
        final Deck theirs = new Deck("theirs");
        theirs.getOrCreate(DeckSection.Commander).add(theirCommander, 1);
        theirs.getMain().add(ISLAND, 40);
        final List<RegisteredPlayer> players = Lists.newArrayList(
                RegisteredPlayer.forCommander(theirs).setPlayer(new LobbyPlayerAi("p2", null)),
                RegisteredPlayer.forCommander(ours).setPlayer(new LobbyPlayerAi("p1", null)));
        final GameRules rules = new GameRules(GameType.Commander);
        game = new Game(players, rules, new Match(rules, players, "Test"));
        for (final Player p : game.getPlayers()) {
            p.initVariantsZones(p.getRegisteredPlayer());
            ((LobbyPlayerAi) p.getLobbyPlayer()).setAiProfile("Default");
        }
        final Player opp = game.getPlayers().get(0);
        ai = game.getPlayers().get(1);
        game.setAge(GameStage.Play);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, ai, 3);
        game.getPhaseHandler().onStackResolved();
        ComboLoopBoards.noDeadline(game);
        for (int i = 0; i < 30; i++) {
            addCardToZone(i % 2 == 0 ? ISLAND : FOREST, ai, ZoneType.Library);
            addCardToZone(ISLAND, opp, ZoneType.Library);
        }
    }

    private AiController aic() {
        return ComboLoopBoards.aiOf(ai);
    }

    /** {@code names} on our battlefield, untapped and ready. */
    private void lands(final String... names) {
        for (final String n : names) {
            ComboLoopBoards.ready(addCard(n, ai));
        }
    }

    /** Our commander, in our command zone. */
    private Card commander() {
        final Card c = ai.getCommanders().get(0);
        assertTrue(c.isInZone(ZoneType.Command), c + " is not in our command zone");
        return c;
    }

    /** Our commander moved from our command zone to our {@code zone} by writing the two zones (the Commander rules' own
     *  move to our hand offers the command zone instead, a choice the AI answers); it stays our commander. */
    private Card moved(final Card commander, final ZoneType zone) {
        ai.getZone(ZoneType.Command).remove(commander);
        ai.getZone(zone).add(commander);
        assertTrue(commander.isCommander() && commander.isInZone(zone), commander + " is not our commander in " + zone);
        return commander;
    }

    /** The statics applied, the command zone's own among them (the Commander rules' effect, whose MayPlay casts our
     *  commander from there), and the state-based actions checked, as the game loop does before a priority. */
    private void settle() {
        game.getAction().checkStaticAbilities();
        game.getAction().checkStateEffects(true);
    }

    /** Our cast of {@code c} the AI may weigh now (Card.getAllPossibleAbilities, the walk's own source), we its
     *  activator. */
    private SpellAbility castOf(final Card c) {
        for (final SpellAbility sa : c.getAllPossibleAbilities(ai, false)) {
            if (sa.isSpell()) {
                sa.setActivatingPlayer(ai);
                return sa;
            }
        }
        throw new AssertionError(c + " has no cast from " + c.getZone());
    }

    /** The API AI's answer for our cast {@code sa} (PermanentCreatureAi's, or PermanentNoncreatureAi's, template): its
     *  phase rule refuses first, MissingPhaseRestrictions, the diagnosis's gate 1. */
    private AiPlayDecision apiAnswer(final SpellAbility sa) {
        return SpellApiToAi.Converter.get(sa).canPlayWithSubs(ai, sa).decision();
    }

    /** What the AI plays at this priority (AiController.chooseSpellAbilityToPlay: the land drop, then the walk): its
     *  first spell ability, or null for a pass. */
    private SpellAbility pick() {
        final List<SpellAbility> choice = aic().chooseSpellAbilityToPlay();
        return choice == null || choice.isEmpty() ? null : choice.get(0);
    }

    /** Whether {@code pick} is a cast of {@code c}. */
    private static boolean casts(final SpellAbility pick, final Card c) {
        return pick != null && pick.isSpell() && c.equals(pick.getHostCard());
    }

    /** The ids of the loop lines our deck holds (the tracker's deck lines) that name {@code name} among their
     *  pieces. */
    private List<String> heldLoopsNaming(final String name) {
        final List<String> ids = new ArrayList<>();
        for (final ComboLines.Line l : aic().getComboTracker().deckLines()) {
            if (l.isLoop() && l.names().contains(name)) {
                ids.add(l.variant().id());
            }
        }
        return ids;
    }

    /** The ids of the loop lines our deck holds. */
    private List<String> heldLoops() {
        final List<String> ids = new ArrayList<>();
        for (final ComboLines.Line l : aic().getComboTracker().deckLines()) {
            if (l.isLoop()) {
                ids.add(l.variant().id());
            }
        }
        return ids;
    }

    /** {@code sa}'s answer at canPlaySa (the walk's own judgement, which reaches the phase rule through the API AI),
     *  asserted to draw no random number. */
    private AiPlayDecision drawlessCanPlaySa(final SpellAbility sa) {
        final long before = MyRandom.callCount();
        final AiPlayDecision answer = aic().canPlaySa(sa);
        assertEquals(MyRandom.callCount() - before, 0L, "the decision on " + sa.getHostCard() + " drew");
        return answer;
    }

    // ------------------------------------------------------------------ the commander piece is cast in our Main 1

    // The smoke's own board (the diagnosis's A1): a Forest and an Island, Walking Ballista and Basalt Monolith in our
    // hand, Kinnan, our commander, in our command zone, the deck holding the rock untap of Kinnan and Basalt. The
    // stock AI holds every permanent spell for Main 2 there and passes; Kinnan is now cast, and Ballista and Basalt,
    // which are not our commander, still wait for Main 2.
    @Test
    public void kinnanOurCommanderIsCastInOurMainOneFromTheCommandZone() {
        start(KINNAN, BASALT, LoopSinks.BALLISTA, ISLAND, FOREST);
        lands(FOREST, ISLAND);
        final Card ballista = addCardToZone(LoopSinks.BALLISTA, ai, ZoneType.Hand);
        final Card basalt = addCardToZone(BASALT, ai, ZoneType.Hand);
        settle();
        final Card kinnan = commander();
        assertEquals(heldLoopsNaming(KINNAN), List.of(KINNAN_BASALT),
                "Kinnan is a piece of a loop line the deck holds");
        final SpellAbility cast = castOf(kinnan);
        assertEquals(apiAnswer(cast), AiPlayDecision.WillPlay,
                "our commander's cast, a piece of a held loop line, is held for Main 2 in our Main 1 with {G}{U} open");
        assertEquals(aic().canPlaySa(cast), AiPlayDecision.WillPlay);
        assertEquals(apiAnswer(castOf(ballista)), AiPlayDecision.MissingPhaseRestrictions, "Ballista waits for Main 2");
        assertEquals(apiAnswer(castOf(basalt)), AiPlayDecision.MissingPhaseRestrictions,
                "Basalt Monolith, a piece that is not our commander, waits for Main 2");
        final SpellAbility pick = pick();
        assertTrue(casts(pick, kinnan), "the AI's pick in our Main 1 is " + pick + ", not Kinnan's cast");
    }

    // The same board with Kinnan, still our commander, in our hand: its cast from there is not held either.
    @Test
    public void kinnanOurCommanderIsCastInOurMainOneFromOurHand() {
        start(KINNAN, BASALT, LoopSinks.BALLISTA, ISLAND, FOREST);
        lands(FOREST, ISLAND);
        addCardToZone(LoopSinks.BALLISTA, ai, ZoneType.Hand);
        addCardToZone(BASALT, ai, ZoneType.Hand);
        final Card kinnan = moved(commander(), ZoneType.Hand);
        settle();
        final SpellAbility cast = castOf(kinnan);
        assertEquals(apiAnswer(cast), AiPlayDecision.WillPlay,
                "our commander's cast from our hand is held for Main 2 in our Main 1 with {G}{U} open");
        final SpellAbility pick = pick();
        assertTrue(casts(pick, kinnan), "the AI's pick in our Main 1 is " + pick + ", not Kinnan's cast from our hand");
    }

    // Kinnan cast once already (a recast after a death): {2}{G}{U} with the commander tax. A Forest and an Island do
    // not pay it, so the stock wait answers, drawing nothing; two more lands pay it, and Kinnan is cast.
    @Test
    public void aTaxedKinnanIsCastInOurMainOneOnceRealManaPaysTheTax() {
        start(KINNAN, BASALT, ISLAND, FOREST);
        lands(FOREST, ISLAND);
        final Card kinnan = commander();
        ai.incCommanderCast(kinnan);
        settle();
        final SpellAbility cast = castOf(kinnan);
        assertFalse(SpecialCardAi.CommanderCastCeiling.affordable(ai, cast), "two lands pay the taxed {2}{G}{U}");
        assertEquals(drawlessCanPlaySa(cast), AiPlayDecision.CantPlayAi);
        assertEquals(apiAnswer(cast), AiPlayDecision.MissingPhaseRestrictions);
        assertNull(pick(), "an unpaid tax: the stock pass");
        lands(FOREST, ISLAND);
        settle();
        final SpellAbility paid = castOf(kinnan);
        assertTrue(SpecialCardAi.CommanderCastCeiling.affordable(ai, paid), "four lands do not pay {2}{G}{U}");
        assertEquals(apiAnswer(paid), AiPlayDecision.WillPlay, "the taxed cast, paid by real mana, waits for Main 2");
        final SpellAbility pick = pick();
        assertTrue(casts(pick, kinnan), "the AI's pick in our Main 1 is " + pick + ", not Kinnan's taxed cast");
    }

    // ------------------------------------------------------------------ the stock wait, decision and draw alike

    // Two Forests: no blue, so Kinnan's cast is not affordable now (CommanderCastCeiling.affordable, read before
    // anything that could draw). The stock path answers, drawing nothing, and the AI's whole pick is stock's.
    @Test
    public void anUnpayableKinnanTakesTheStockPathAndDrawsNothing() {
        start(KINNAN, BASALT, ISLAND, FOREST);
        lands(FOREST, FOREST);
        settle();
        final SpellAbility cast = castOf(commander());
        assertFalse(SpecialCardAi.CommanderCastCeiling.affordable(ai, cast), "two Forests pay {G}{U}");
        assertEquals(drawlessCanPlaySa(cast), AiPlayDecision.CantPlayAi);
        assertEquals(apiAnswer(cast), AiPlayDecision.MissingPhaseRestrictions);
        assertNull(ComboLoopBoards.assertStockParity(this::pick, ai, ComboLines.shipped()));
        assertEquals(heldLoopsNaming(KINNAN), List.of(KINNAN_BASALT), "the parity ran with Kinnan's loop line held");
    }

    // THE RULE's inertness (the hook rule's parity, spec section 7): a deck holding no loop line, here piece 1's
    // Thassa's Oracle line alone, beside Kinnan, our commander, a piece of loop rows the deck does not hold. Kinnan
    // waits for Main 2, and the AI decides and draws as it does with the tracker holding no line.
    @Test
    public void aDeckHoldingNoLoopLinePlaysAsStock() {
        start(KINNAN, ORACLE, CONSULTATION, ISLAND, FOREST);
        lands(FOREST, ISLAND);
        settle();
        final SpellAbility cast = castOf(commander());
        assertEquals(apiAnswer(cast), AiPlayDecision.MissingPhaseRestrictions);
        assertNull(ComboLoopBoards.assertStockParity(this::pick, ai, ComboLines.shipped()));
        assertEquals(ComboLoopBoards.assertStockParity(() -> aic().canPlaySa(cast), ai, ComboLines.shipped()),
                AiPlayDecision.CantPlayAi);
        assertTrue(heldLoops().isEmpty(), "the deck holds loop lines " + heldLoops());
        assertFalse(aic().getComboTracker().deckLines().isEmpty(), "the deck holds piece 1's line");
    }

    // Thrasios, Triton Hero, a {G}{U} commander as Kinnan is, beside the rock untap of Kinnan and Basalt Monolith,
    // which the deck holds: Thrasios is no piece of a loop line, so it waits for Main 2, decision and draw as stock.
    @Test
    public void aCommanderThatIsNoPieceWaitsForMainTwo() {
        start(THRASIOS, KINNAN, BASALT, ISLAND, FOREST);
        lands(FOREST, ISLAND);
        settle();
        final SpellAbility cast = castOf(commander());
        assertEquals(apiAnswer(cast), AiPlayDecision.MissingPhaseRestrictions);
        assertNull(ComboLoopBoards.assertStockParity(this::pick, ai, ComboLines.shipped()));
        assertEquals(heldLoops(), List.of(KINNAN_BASALT), "the deck holds Kinnan's loop line");
        assertTrue(heldLoopsNaming(THRASIOS).isEmpty());
    }

    // Kinnan, our commander, beside the rock untap of Grim Monolith and Nyxbloom Ancient, the one loop line the deck
    // holds: Kinnan is a piece of loop rows the deck does not hold (with Basalt, with Sakashima), of none it holds, so
    // it waits for Main 2, decision and draw as stock.
    @Test
    public void aCommanderNoPieceOfALineTheDeckHoldsWaitsForMainTwo() {
        start(KINNAN, GRIM, NYXBLOOM, ISLAND, FOREST);
        lands(FOREST, ISLAND);
        settle();
        final SpellAbility cast = castOf(commander());
        assertEquals(apiAnswer(cast), AiPlayDecision.MissingPhaseRestrictions);
        assertNull(ComboLoopBoards.assertStockParity(this::pick, ai, ComboLines.shipped()));
        assertEquals(heldLoops(), List.of(GRIM_NYXBLOOM), "the deck holds the Grim and Nyxbloom line alone");
        assertTrue(heldLoopsNaming(KINNAN).isEmpty());
    }

    // Kinnan in our hand, a piece of the loop line the deck holds but not our commander (that is Thrasios): it waits
    // for Main 2, and so does Thrasios.
    @Test
    public void kinnanThatIsNotOurCommanderWaitsForMainTwo() {
        start(THRASIOS, KINNAN, BASALT, ISLAND, FOREST);
        lands(FOREST, ISLAND);
        final Card kinnan = addCardToZone(KINNAN, ai, ZoneType.Hand);
        settle();
        assertFalse(kinnan.isCommander());
        assertEquals(apiAnswer(castOf(kinnan)), AiPlayDecision.MissingPhaseRestrictions);
        assertEquals(apiAnswer(castOf(commander())), AiPlayDecision.MissingPhaseRestrictions);
        assertNull(pick(), "the stock pass");
    }

    // Basalt Monolith in our hand, a piece of the loop line the deck holds beside Kinnan, our commander, on our
    // battlefield: Basalt is not our commander, so it waits for Main 2.
    @Test
    public void basaltMonolithAPieceThatIsNotOurCommanderWaitsForMainTwo() {
        start(KINNAN, BASALT, ISLAND, FOREST);
        lands(FOREST, ISLAND, FOREST);
        ComboLoopBoards.ready(moved(commander(), ZoneType.Battlefield));
        final Card basalt = addCardToZone(BASALT, ai, ZoneType.Hand);
        settle();
        final SpellAbility cast = castOf(basalt);
        assertEquals(drawlessCanPlaySa(cast), AiPlayDecision.CantPlayAi);
        assertEquals(apiAnswer(cast), AiPlayDecision.MissingPhaseRestrictions);
        assertNull(pick(), "the stock pass");
        assertEquals(heldLoopsNaming(BASALT), List.of(KINNAN_BASALT),
                "Basalt is a piece of the loop line the deck holds");
    }

    // Kinnan, our commander, in our graveyard beside Muldrotha, the Gravetide, whose static lets us cast a creature
    // spell from our graveyard in our turn: a cast from a zone that is neither our command zone nor our hand keeps
    // the stock wait. Only the statics are applied here: the state-based actions would return our commander to our
    // command zone.
    @Test
    public void ourCommanderCastFromAnotherZoneWaitsForMainTwo() {
        start(KINNAN, BASALT, ISLAND, FOREST);
        lands(FOREST, ISLAND);
        ComboLoopBoards.ready(addCard(MULDROTHA, ai));
        final Card kinnan = moved(commander(), ZoneType.Graveyard);
        game.getAction().checkStaticAbilities();
        final SpellAbility cast = castOf(kinnan);
        assertTrue(cast.canPlay(), "Muldrotha does not let us cast Kinnan from our graveyard");
        assertTrue(SpecialCardAi.CommanderCastCeiling.affordable(ai, cast), "a Forest and an Island do not pay {G}{U}");
        assertEquals(heldLoopsNaming(KINNAN), List.of(KINNAN_BASALT),
                "Kinnan is a piece of a loop line the deck holds");
        assertEquals(apiAnswer(cast), AiPlayDecision.MissingPhaseRestrictions);
        assertNull(pick(), "the stock pass");
    }

    // ------------------------------------------------------------------ the reader's own conditions, one at a time

    /** The smoke's board with Kinnan, our commander, in our command zone and {G}{U} open: his cast, which the lift
     *  passes (the control of the tests below, each of which changes one thing about it). */
    private SpellAbility liftedKinnanCast() {
        start(KINNAN, BASALT, LoopSinks.BALLISTA, ISLAND, FOREST);
        lands(FOREST, ISLAND);
        settle();
        final SpellAbility cast = castOf(commander());
        assertTrue(LoopDriver.commanderInMainOne(ai, cast), "the lift does not pass our commander's cast");
        assertTrue(ComboHold.liftsMainTwoWait(ai, cast));
        return cast;
    }

    // The lift is for our Main 1 alone: not our Main 2, nor the opponent's Main 1. Through PermanentCreatureAi both are
    // moot (PermanentAi's wait passes a cast outside our Main 1, and outside our main phases the timing refuses a
    // sorcery-speed cast earlier still), so the reader's own answer is pinned.
    @Test
    public void ourCommanderIsLiftedInOurMainOneAlone() {
        final SpellAbility cast = liftedKinnanCast();
        game.getPhaseHandler().devModeSet(PhaseType.MAIN2, ai, 3);
        assertFalse(LoopDriver.commanderInMainOne(ai, cast), "lifted in our Main 2");
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, game.getPlayers().get(0), 3);
        assertFalse(LoopDriver.commanderInMainOne(ai, cast), "lifted in the opponent's Main 1");
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, ai, 3);
        assertTrue(LoopDriver.commanderInMainOne(ai, cast), "not lifted in our Main 1 again");
    }

    // Only on an empty stack: with the opponent's spell on it the lift is not passed (through PermanentCreatureAi moot
    // as well: above a stack the template refuses a sorcery-speed cast before the phase rule).
    @Test
    public void ourCommanderIsLiftedOnAnEmptyStackAlone() {
        final SpellAbility cast = liftedKinnanCast();
        ComboLoopBoards.theirSpellOn(game, game.getPlayers().get(0), "Lightning Bolt", ai);
        assertFalse(LoopDriver.commanderInMainOne(ai, cast), "lifted with a spell on the stack");
    }

    // Only the commander's own spell: not a copy of it, not a cast a Play effect makes, not one another player
    // activates, no spell at all (null), and not an ability of the commander's (Kinnan's {5}{G}{U} dig, which seven
    // lands pay, so that only the spell test refuses it).
    @Test
    public void onlyOurCommandersOwnSpellIsLifted() {
        final SpellAbility cast = liftedKinnanCast();
        cast.setCopied(true);
        assertFalse(LoopDriver.commanderInMainOne(ai, cast), "lifted for a copy");
        cast.setCopied(false);
        cast.setCastFromPlayEffect(true);
        assertFalse(LoopDriver.commanderInMainOne(ai, cast), "lifted for a Play effect's cast");
        cast.setCastFromPlayEffect(false);
        cast.setActivatingPlayer(game.getPlayers().get(0));
        assertFalse(LoopDriver.commanderInMainOne(ai, cast), "lifted for the opponent's activation");
        cast.setActivatingPlayer(ai);
        assertTrue(LoopDriver.commanderInMainOne(ai, cast), "not lifted once the cast is ours again");
        assertFalse(LoopDriver.commanderInMainOne(ai, null), "lifted for no spell");
        lands(FOREST, FOREST, FOREST, ISLAND, ISLAND);
        settle();
        SpellAbility dig = null;
        for (final SpellAbility sa : cast.getHostCard().getSpellAbilities()) {
            if (sa.isActivatedAbility()) {
                dig = sa;
            }
        }
        assertTrue(dig != null && !dig.isSpell(), "Kinnan has no activated ability");
        dig.setActivatingPlayer(ai);
        assertTrue(SpecialCardAi.CommanderCastCeiling.affordable(ai, dig), "seven lands do not pay {5}{G}{U}");
        assertFalse(LoopDriver.commanderInMainOne(ai, dig), "lifted for an ability of our commander's");
        assertTrue(LoopDriver.commanderInMainOne(ai, cast), "the cast is not lifted beside seven lands");
    }

    // The opponent's Kinnan, their commander, in our hand (a steal): our deck holds the rock untap line he is a piece
    // of and {G}{U} is open, but he is not our commander (we do not own him), so his cast waits for Main 2.
    @Test
    public void anotherPlayersCommanderInOurHandIsNotLifted() {
        startAgainst(KINNAN, THRASIOS, KINNAN, BASALT, ISLAND, FOREST);
        lands(FOREST, ISLAND);
        final Player opp = game.getPlayers().get(0);
        final Card stolen = opp.getCommanders().get(0);
        assertEquals(stolen.getName(), KINNAN);
        opp.getZone(ZoneType.Command).remove(stolen);
        ai.getZone(ZoneType.Hand).add(stolen);
        settle();
        assertTrue(stolen.isCommander() && stolen.isInZone(ZoneType.Hand), stolen + " is not a commander in hand");
        assertEquals(stolen.getOwner(), opp, "we own the opponent's commander");
        final SpellAbility cast = stolen.getFirstSpellAbility();
        cast.setActivatingPlayer(ai);
        assertTrue(SpecialCardAi.CommanderCastCeiling.affordable(ai, cast), "{G}{U} does not pay Kinnan");
        assertEquals(heldLoopsNaming(KINNAN), List.of(KINNAN_BASALT), "the deck holds Kinnan's loop line");
        assertFalse(LoopDriver.commanderInMainOne(ai, cast), "lifted for the opponent's commander");
        assertFalse(ComboHold.liftsMainTwoWait(ai, cast));
    }

    // While another player controls us (a mind-slave turn; Opposition Agent while we search), we have no tracker:
    // ComboTracker.of(ai) is null, so the lift has no lines to read and answers false, throwing nothing, as piece 1's
    // Oracle lift does (ComboExecuteAiTest.aControlledPlayerHasNoGoOffInMainOne). Our commander's cast keeps the
    // stock wait: the API AI's phase rule refuses it, and canPlaySa, asked of the controller made for our master,
    // answers CantPlayAi, drawing nothing. Once control ends, the lift passes again.
    @Test
    public void aControlledPlayerHasNoLift() {
        final SpellAbility cast = liftedKinnanCast();
        assertEquals(apiAnswer(cast), AiPlayDecision.WillPlay, "the control: our commander's cast is held for Main 2");
        final long timestamp = game.getNextTimestamp();
        ai.addController(timestamp, game.getPlayers().get(0));
        try {
            assertTrue(ai.isControlled());
            assertNull(ComboTracker.of(ai));
            assertFalse(LoopDriver.commanderInMainOne(ai, cast), "a controlled player's commander was lifted");
            assertFalse(ComboHold.liftsMainTwoWait(ai, cast));
            assertEquals(apiAnswer(cast), AiPlayDecision.MissingPhaseRestrictions,
                    "a controlled player's commander is not held for Main 2");
            assertEquals(drawlessCanPlaySa(cast), AiPlayDecision.CantPlayAi);
        } finally {
            ai.removeController(timestamp);
        }
        assertFalse(ai.isControlled());
        assertTrue(LoopDriver.commanderInMainOne(ai, cast), "not lifted once control ended");
    }

    // Our commander a piece of a line the deck holds that is no LOOP line: Fblthp, Impossibly Lost, a legendary
    // creature, with Demonic Consultation, piece 1's line. {1}{U} is open, but only a loop line lifts the wait (and
    // piece 1's own lift is for Thassa's Oracle's hand cast).
    @Test
    public void aCommanderThatIsAPieceOfANonLoopLineIsNotLifted() {
        start(FBLTHP, CONSULTATION, ISLAND, FOREST);
        lands(FOREST, ISLAND);
        settle();
        final List<String> named = new ArrayList<>();
        for (final ComboLines.Line l : aic().getComboTracker().deckLines()) {
            if (!l.isLoop() && l.names().contains(FBLTHP)) {
                named.add(l.variant().id());
            }
        }
        assertEquals(named, List.of(CONSULTATION_FBLTHP), "the deck holds piece 1's line of Fblthp");
        assertTrue(heldLoops().isEmpty(), "the deck holds loop lines " + heldLoops());
        final SpellAbility cast = castOf(commander());
        assertTrue(SpecialCardAi.CommanderCastCeiling.affordable(ai, cast), "a Forest and an Island do not pay {1}{U}");
        assertFalse(LoopDriver.commanderInMainOne(ai, cast), "lifted for a piece of a line that is no loop line");
        assertFalse(ComboHold.liftsMainTwoWait(ai, cast));
    }

    // ------------------------------------------------------------------ G1 is a ceiling (spec section 9)

    // G1 (SpecialCardAi.CommanderCastCeiling.affordable) is a ceiling, not a payability test: it counts a multicolour
    // source toward each colour it can make. Command Tower beside Sol Ring: {G}{U} passes it (three mana, a G and a
    // U), though real mana cannot pay it (the Tower makes one colour, Sol Ring {C}{C}). So the lift passes the phase
    // rule and canPlaySa approves the cast, drawing nothing, and canPayCost's test payment draws (isManaSourceReserved)
    // before it refuses it, where the stock wait refuses the cast at the phase rule, drawing nothing. Kinnan is not
    // cast either way; the walk's pick is a pass both ways, the lifted one drawing.
    @Test
    public void aWindowG1OverCountsIsLiftedAndDrawsAtThePayment() {
        start(KINNAN, BASALT, COMMAND_TOWER, SOL_RING, ISLAND, FOREST);
        lands(COMMAND_TOWER);
        ComboLoopBoards.ready(addCard(SOL_RING, ai));
        settle();
        assertEquals(heldLoops(), List.of(KINNAN_BASALT), "the deck holds Kinnan's loop line alone");
        final SpellAbility cast = castOf(commander());
        assertTrue(SpecialCardAi.CommanderCastCeiling.affordable(ai, cast),
                "G1 refuses {G}{U} on Command Tower and Sol Ring");
        assertTrue(LoopDriver.commanderInMainOne(ai, cast), "the lift does not pass the window G1 over-counts");
        assertEquals(drawlessCanPlaySa(cast), AiPlayDecision.WillPlay);
        long before = MyRandom.callCount();
        assertFalse(ComputerUtilCost.canPayCost(cast, ai, false), "real mana pays {G}{U} on the Tower and Sol Ring");
        assertTrue(MyRandom.callCount() - before > 0, "canPayCost's test payment drew nothing");
        final ComboTracker t = aic().getComboTracker();
        t.useLines(List.of());
        assertEquals(drawlessCanPlaySa(cast), AiPlayDecision.CantPlayAi);
        MyRandom.setRandom(new Random(7));
        before = MyRandom.callCount();
        assertNull(pick(), "the stock pick is not a pass");
        assertEquals(MyRandom.callCount() - before, 0L, "the stock pick drew");
        t.useLines(ComboLines.shipped());
        MyRandom.setRandom(new Random(7));
        before = MyRandom.callCount();
        assertNull(pick(), "the lifted pick is not a pass");
        assertTrue(MyRandom.callCount() - before > 0, "the lifted pick drew nothing");
    }
}
