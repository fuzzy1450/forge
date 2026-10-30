package forge.ai.combo;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertSame;
import static org.testng.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import org.testng.annotations.Test;

import forge.ai.AITest;
import forge.ai.combo.ComboSignals.Signal;
import forge.game.Game;
import forge.game.ability.ApiType;
import forge.game.card.Card;
import forge.game.card.CardFactory;
import forge.game.card.CounterEnumType;
import forge.game.keyword.Keyword;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.trigger.Trigger;
import forge.game.trigger.TriggerType;
import forge.game.zone.ZoneType;

/**
 * Signal 2 for piece 2's loop lines (spec 2026-10-29-combo-aware-ai-loops-design.md sections 6.2 and 6.7; the plan's
 * Calls and R17, R24, R26): a loop line is tracked, live and executable as piece 1's lines are, reads no window, and is
 * denied while we cannot win (unless Walking Ballista can kill what stops the win) or while the engine bars a step of
 * its cycle now. Each test starts from Kinnan's deck registered, Kinnan (ready) and Basalt Monolith on our battlefield,
 * our Main 1, the opponent with nothing.
 */
public class ComboLoopSignalsAiTest extends AITest {
    private static final String KINNAN = "Kinnan, Bonder Prodigy";
    private static final String BASALT = "Basalt Monolith";
    private static final String GRIM = "Grim Monolith";
    private static final String NYXBLOOM = "Nyxbloom Ancient";
    private static final String SAKASHIMA = "Sakashima of a Thousand Faces";
    private static final String PALINCHRON = "Palinchron";
    private static final String DEADEYE = "Deadeye Navigator";
    private static final String HULLBREAKER = "Hullbreaker Horror";
    private static final String BALLISTA = "Walking Ballista";
    private static final String ANGEL = "Platinum Angel";

    /** Every piece of the 19 loop rows but Kinnan, the commander, and Basalt Monolith, which each board puts down. */
    private static final List<String> OTHER_PIECES = List.of(GRIM, NYXBLOOM, SAKASHIMA, PALINCHRON, DEADEYE,
            HULLBREAKER, "Sol Ring", "Mana Vault", "Mox Opal", "Sensei's Divining Top", "Mox Amber", "Arcane Signet",
            "Simic Signet", "Talisman of Curiosity", "Scroll Rack");
    /** Kinnan's list as the tracker reads it: Basalt and the other pieces, the two sinks, and lands. */
    private static final List<String> KINNANS_LIST = kinnansList();
    /** The 19 loop rows in the table's file order, which sorts ids as strings (R12). */
    private static final List<String> LOOPS_IN_FILE_ORDER = List.of("129-2585-2719", "129-4131", "1409-3542",
            "2585-3430", "3430-3542", "3430-4131", "x-10", "x-11", "x-12", "x-13", "x-14", "x-2", "x-3", "x-4", "x-5",
            "x-6", "x-7", "x-8", "x-9");

    private Game game;
    private Player ai;
    private Player opp;
    private ComboTracker t;
    private Card kinnan;
    private Card basalt;

    private static List<String> kinnansList() {
        final List<String> list = new ArrayList<>();
        list.add(BASALT);
        list.addAll(OTHER_PIECES);
        list.addAll(List.of(BALLISTA, "Thrasios, Triton Hero", "Forest", "Island"));
        return List.copyOf(list);
    }

    private static List<String> ids(final List<ComboLines.Line> lines) {
        return lines.stream().map(l -> l.variant().id()).collect(Collectors.toList());
    }

    /** The default board: Kinnan's deck registered, Kinnan (ready) and Basalt Monolith on our battlefield. */
    private void start() {
        start(ZoneType.Battlefield);
    }

    /** The default board with Basalt Monolith in {@code basaltZone}. */
    private void start(final ZoneType basaltZone) {
        game = initAndCreateGame();
        ComboLoopBoards.noDeadline(game);
        ai = game.getPlayers().get(1);
        opp = game.getPlayers().get(0);
        ComboLoopBoards.deck(ai, KINNAN, KINNANS_LIST.toArray(new String[0]));
        t = ComboLoopBoards.aiOf(ai).getComboTracker();
        kinnan = ComboLoopBoards.ready(addCard(KINNAN, ai));
        basalt = addCardToZone(BASALT, ai, basaltZone);
    }

    /** The deck line of that id. */
    private ComboLines.Line line(final String id) {
        for (final ComboLines.Line l : t.deckLines()) {
            if (l.variant().id().equals(id)) {
                return l;
            }
        }
        throw new AssertionError("the deck holds no line " + id + ": " + ids(t.deckLines()));
    }

    /** The deck lines of that kind, in their order. */
    private List<ComboLines.Line> kind(final LoopKind k) {
        return t.deckLines().stream().filter(l -> l.loop() == k).collect(Collectors.toList());
    }

    /** Every piece but Kinnan's and Basalt's in our library: every loop line's pieces are then reachable. */
    private void otherPiecesInLibrary() {
        for (final String name : OTHER_PIECES) {
            addCardToZone(name, ai, ZoneType.Library);
        }
    }

    private void lands(final int n) {
        for (int i = 0; i < n; i++) {
            addCard("Island", ai);
        }
    }

    private Signal abandon(final ComboLines.Line l) {
        return ComboSignals.abandon(ai, l, t);
    }

    private boolean denied(final ComboLines.Line l) {
        return t.view().denied(l);
    }

    /** Palinchron and Deadeye Navigator, ready on our battlefield and paired: Deadeye's {1}{U} flicker granted to
     *  both. Returns Palinchron. */
    private Card pairedPalinchron() {
        final Card palinchron = ComboLoopBoards.ready(addCard(PALINCHRON, ai));
        final Card deadeye = ComboLoopBoards.ready(addCard(DEADEYE, ai));
        palinchron.setPairedWith(deadeye);
        deadeye.setPairedWith(palinchron);
        game.getAction().checkStaticAbilities();
        assertNotNull(flickerOf(palinchron), "Deadeye's flicker was not granted to Palinchron");
        return palinchron;
    }

    // Palinchron's granted flicker: an activated ChangeZone to exile that a static grants, or null
    private static SpellAbility flickerOf(final Card c) {
        for (final SpellAbility sa : c.getNonManaAbilities()) {
            if (sa.isActivatedAbility() && sa.getApi() == ApiType.ChangeZone && sa.getGrantorStatic() != null
                    && "Exile".equals(sa.getParam("Destination"))) {
                return sa;
            }
        }
        return null;
    }

    // Palinchron's own return to our hand, or null
    private static SpellAbility returnOf(final Card c) {
        for (final SpellAbility sa : c.getNonManaAbilities()) {
            if (sa.isActivatedAbility() && sa.getApi() == ApiType.ChangeZone
                    && "Hand".equals(sa.getParam("Destination"))) {
                return sa;
            }
        }
        return null;
    }

    // whether the card has a TapsForMana trigger that is not suppressed: Kinnan's bonus
    private static boolean amplifies(final Card c) {
        for (final Trigger tr : c.getTriggers()) {
            if (tr.getMode() == TriggerType.TapsForMana && !tr.isSuppressed()) {
                return true;
            }
        }
        return false;
    }

    /** The opponent's {@code name} cast at {@code target} (none when null) and resolved at once: put on the stack by
     *  hand, nothing paid (ComboLinesEndToEndAiTest.theirResponse's idiom), then resolved, no one given priority. */
    private void theirSpellResolvesOn(final String name, final Card target) {
        final Card card = createCard(name, opp);
        card.setGameTimestamp(game.getNextTimestamp());
        game.getStackZone().add(card);
        final SpellAbility sa = card.getFirstSpellAbility();
        sa.setActivatingPlayer(opp);
        if (target != null) {
            sa.getTargets().add(target);
        }
        game.getStack().add(sa);
        game.getStack().resolveStack();
        assertTrue(game.getStack().isEmpty(), name + " is still on the stack");
    }

    @Test
    public void liveExecutableAndNowNeverThrowForALoopLine() {
        start();
        // the shipped lines the deck holds are the 19 loop lines, in the table's order (R12)
        assertEquals(ids(t.deckLines()), LOOPS_IN_FILE_ORDER);
        final ComboLines.Line rock = line("129-4131");
        final ComboTracker.View v = t.view();
        assertTrue(v.live().contains(rock));
        assertTrue(v.executable().contains(rock));
        assertTrue(v.executableNow().contains(rock));
        // and only it: every other loop line has a piece in no zone of ours
        assertEquals(v.live(), List.of(rock));
        assertEquals(v.executable(), List.of(rock));
        assertEquals(v.executableNow(), List.of(rock));
        // the readers over live() answer for it as for any line: nothing is missing, so it is the nearest line and no
        // one-short line, and both its pieces are held
        assertSame(v.nearest(), rock);
        assertEquals(v.oneShort(), List.of());
        assertTrue(v.protects(kinnan));
        assertTrue(v.protects(basalt));
        assertNull(abandon(rock));
    }

    @Test
    public void deniedThisTurnNeverThrowsForALoopLine() {
        start();
        final ComboLines.Line rock = line("129-4131");
        final ComboTracker.View v = t.view();
        assertFalse(v.deniedThisTurn(rock));
        assertFalse(v.windowBarred(rock));
        // a loop line has no window: none is read on the opponent's turn either, nor under a CantBeCast static
        game.getPhaseHandler().devModeSet(PhaseType.END_OF_TURN, opp);
        final ComboTracker.View theirs = t.view();
        assertFalse(theirs.deniedThisTurn(rock));
        assertFalse(theirs.windowBarred(rock));
        addCard("Rule of Law", opp);
        final ComboTracker.View barred = t.view();
        assertFalse(barred.deniedThisTurn(rock));
        assertFalse(barred.windowBarred(rock));
    }

    @Test
    public void nullRodDeniesTheRockLines() {
        start();
        otherPiecesInLibrary();
        final ComboLines.Line rock = line("129-4131");
        final List<ComboLines.Line> rocks = kind(LoopKind.ROCK_UNTAP);
        assertEquals(ids(rocks), List.of("129-2585-2719", "129-4131", "2585-3430", "3430-4131"));
        assertTrue(t.live().contains(rock));
        for (final ComboLines.Line l : rocks) {
            assertFalse(denied(l), l.variant().id());
        }

        addCard("Null Rod", opp);
        assertFalse(t.live().contains(rock));
        assertFalse(t.executable().contains(rock));
        assertEquals(abandon(rock), Signal.DENIED);
        // every rock line, its Monolith on our battlefield or in our library
        for (final ComboLines.Line l : rocks) {
            assertEquals(abandon(l), Signal.DENIED, l.variant().id());
        }
        // and every bounce row: each has a rock to tap, an artifact (its mana abilities are what Null Rod refuses)
        for (final ComboLines.Line l : kind(LoopKind.BOUNCE)) {
            assertEquals(abandon(l), Signal.DENIED, l.variant().id());
        }
        // Null Rod stops artifacts only: the flicker's and the recast's pieces are creatures
        assertFalse(denied(line("1409-3542")));
        assertFalse(denied(line("3430-3542")));
    }

    @Test
    public void nullRodDeniesABasaltInHand() {
        start(ZoneType.Hand);
        lands(3);   // Basalt's cast: no NO_MANA
        final ComboLines.Line rock = line("129-4131");
        assertNull(abandon(rock));
        assertTrue(t.live().contains(rock));

        addCard("Null Rod", opp);
        assertEquals(abandon(rock), Signal.DENIED);
        assertFalse(t.live().contains(rock));
        // read where it is, our hand, Basalt's abilities pass: Null Rod's AffectedZone$ Battlefield skips a card in
        // hand, so the line is judged on Basalt as it would be on our battlefield (R17)
        assertFalse(basalt.getManaAbilities().isEmpty());
        for (final SpellAbility sa : basalt.getManaAbilities()) {
            assertTrue(sa.checkRestrictions(ai), sa.toString());
        }
    }

    @Test
    public void aKinnanWithoutItsAbilitiesIsNoAmplifier() {
        start();
        addCardToZone(NYXBLOOM, ai, ZoneType.Library);
        final ComboLines.Line rock = line("129-4131");
        final ComboLines.Line nyx = line("3430-4131");
        assertTrue(amplifies(kinnan));
        assertFalse(denied(rock));
        assertFalse(denied(nyx));

        // the opponent's Turn to Frog on Kinnan: until end of turn a 1/1 Frog with no abilities, no TapsForMana
        // trigger among them (R26)
        theirSpellResolvesOn("Turn to Frog", kinnan);
        assertFalse(amplifies(kinnan));
        assertEquals(abandon(rock), Signal.DENIED);
        assertFalse(t.live().contains(rock));
        assertFalse(t.executable().contains(rock));
        // Basalt and Nyxbloom need no Kinnan
        assertFalse(denied(nyx));
    }

    @Test
    public void ruleOfLawDeniesTheRecastAndTheBounce() {
        start();
        otherPiecesInLibrary();
        final List<ComboLines.Line> casting = new ArrayList<>(kind(LoopKind.RECAST));
        casting.addAll(kind(LoopKind.BOUNCE));
        assertEquals(ids(casting), List.of("3430-3542", "x-10", "x-11", "x-12", "x-13", "x-14", "x-2", "x-3", "x-4",
                "x-5", "x-6", "x-7", "x-8", "x-9"));
        for (final ComboLines.Line l : casting) {
            assertFalse(denied(l), l.variant().id());
        }

        // a cycle casts again and again: Rule of Law lets one spell a turn through, so it bars the cycle (R24)
        addCard("Rule of Law", opp);
        for (final ComboLines.Line l : casting) {
            assertEquals(abandon(l), Signal.DENIED, l.variant().id());
        }
        // a rock untap and the flicker cast nothing
        assertFalse(denied(line("129-4131")));
        assertTrue(t.live().contains(line("129-4131")));
        assertFalse(denied(line("1409-3542")));

        // the recast's Palinchron on our battlefield already: the cycle casts that one again
        start();
        ComboLoopBoards.ready(addCard(PALINCHRON, ai));
        addCard(NYXBLOOM, ai);
        final ComboLines.Line recast = line("3430-3542");
        assertFalse(denied(recast));
        addCard("Rule of Law", opp);
        assertEquals(abandon(recast), Signal.DENIED);
    }

    @Test
    public void torporOrbDeniesThePalinchronLines() {
        start();
        pairedPalinchron();
        addCard(NYXBLOOM, ai);
        final ComboLines.Line flicker = line("1409-3542");
        final ComboLines.Line recast = line("3430-3542");
        assertFalse(denied(flicker));
        assertFalse(denied(recast));

        addCard("Torpor Orb", opp);
        assertEquals(abandon(flicker), Signal.DENIED);
        assertEquals(abandon(recast), Signal.DENIED);
        // Kinnan's bonus is a static trigger, which no DisableTriggers static reaches, and Basalt and Nyxbloom's loop
        // has no entering trigger in it
        assertTrue(t.live().contains(line("129-4131")));
        assertFalse(denied(line("3430-4131")));
    }

    @Test
    public void cursedTotemDeniesTheFlicker() {
        start();
        pairedPalinchron();
        addCardToZone(NYXBLOOM, ai, ZoneType.Library);
        final ComboLines.Line flicker = line("1409-3542");
        final ComboLines.Line recast = line("3430-3542");
        final ComboLines.Line rock = line("129-4131");
        assertFalse(denied(flicker));
        assertFalse(denied(recast));

        addCard("Cursed Totem", opp);
        assertEquals(abandon(flicker), Signal.DENIED);
        // Palinchron's own return is a creature's activated ability as well
        assertEquals(abandon(recast), Signal.DENIED);
        // Kinnan's bonus is a trigger, and Basalt is no creature
        assertTrue(t.live().contains(rock));
        assertNull(abandon(rock));
    }

    @Test
    public void platinumAngelDeniesWithoutABallista() {
        start();
        otherPiecesInLibrary();
        addCard(ANGEL, opp);
        assertTrue(ai.cantWin());
        assertEquals(t.deckLines().size(), 19);
        for (final ComboLines.Line l : t.deckLines()) {
            assertEquals(abandon(l), Signal.DENIED, l.variant().id());
        }
        assertEquals(t.live(), List.of());
        assertEquals(t.executable(), List.of());
        assertNull(LoopSinks.lossBlocker(ai));
        // a Ballista in our library is none: it must be on our battlefield or in our hand
        addCardToZone(BALLISTA, ai, ZoneType.Library);
        assertEquals(abandon(line("129-4131")), Signal.DENIED);
        assertNull(LoopSinks.lossBlocker(ai));
    }

    @Test
    public void platinumAngelWithABallistaIsNotDenied() {
        start();
        final Card angel = addCard(ANGEL, opp);
        addCardToZone(BALLISTA, ai, ZoneType.Hand);
        final ComboLines.Line rock = line("129-4131");
        assertTrue(ai.cantWin());
        assertTrue(t.executable().contains(rock));
        assertTrue(t.live().contains(rock));
        assertSame(LoopSinks.lossBlocker(ai), angel);
        assertEquals(LoopSinks.lossBlockers(ai), List.of(angel));

        // a Ballista on our battlefield frees the win alike
        start();
        final Card again = addCard(ANGEL, opp);
        addCard(BALLISTA, ai).setCounters(CounterEnumType.P1P1, 1);
        assertTrue(t.executable().contains(line("129-4131")));
        assertSame(LoopSinks.lossBlocker(ai), again);
    }

    @Test
    public void aHexproofAngelDeniesEvenWithABallista() {
        start();
        final Card angel = addCard(ANGEL, opp);
        addCard("Swiftfoot Boots", opp).attachToEntity(angel, null);
        game.getAction().checkStateEffects(true);
        assertTrue(angel.hasKeyword(Keyword.HEXPROOF));
        addCardToZone(BALLISTA, ai, ZoneType.Hand);
        final ComboLines.Line rock = line("129-4131");
        assertEquals(abandon(rock), Signal.DENIED);
        assertFalse(t.executable().contains(rock));
        assertNull(LoopSinks.lossBlocker(ai));
        assertEquals(LoopSinks.lossBlockers(ai), List.of());
    }

    @Test
    public void angelsGraceDeniesEvenWithABallista() {
        // the opponent's Angel's Grace resolved: until end of turn we cannot win, through an effect in their command
        // zone, which no ping can kill (every card of the game is read for the sources of that, not the battlefield's)
        start();
        addCardToZone(BALLISTA, ai, ZoneType.Hand);
        assertNull(abandon(line("129-4131")));
        theirSpellResolvesOn("Angel's Grace", null);
        assertTrue(ai.cantWin());
        assertEquals(abandon(line("129-4131")), Signal.DENIED);
        assertNull(LoopSinks.lossBlocker(ai));
    }

    @Test
    public void lichsMasteryDeniesNothing() {
        start();
        addCard("Lich's Mastery", opp);
        addCardToZone(BALLISTA, ai, ZoneType.Hand);
        assertTrue(opp.cantLose());
        assertFalse(ai.cantWin());
        final ComboLines.Line rock = line("129-4131");
        assertTrue(t.live().contains(rock));
        assertNull(abandon(rock));
        // an enchantment: Ballista cannot kill it, so it is no loss blocker
        assertNull(LoopSinks.lossBlocker(ai));
    }

    @Test
    public void noManaReadsCastCostsOnly() {
        start();
        addCardToZone(PALINCHRON, ai, ZoneType.Hand);
        addCardToZone(NYXBLOOM, ai, ZoneType.Hand);
        final ComboLines.Line recast = line("3430-3542");
        // seven and seven to cast, against next turn's mana: the lands, and Basalt's three
        lands(6);
        assertEquals(abandon(recast), Signal.NO_MANA);
        lands(4);
        assertEquals(abandon(recast), Signal.NO_MANA, "ten lands and Basalt make 13");
        lands(1);
        assertNull(abandon(recast), "eleven lands and Basalt make 14: Palinchron's {2}{U}{U} return is not counted");
        lands(3);
        assertNull(abandon(recast));
    }

    // ---- beyond the plan's thirteen ----

    @Test
    public void aLoopLineReadsOneDenialAtEveryMoment() {
        // no window: now, at its window and at our next main phase read alike, denied or not
        start();
        final ComboLines.Line rock = line("129-4131");
        ComboTracker.View v = t.view();
        assertFalse(v.denied(rock));
        assertFalse(v.deniedNow(rock));
        assertFalse(ComboSignals.deniedAtMainNextTurn(v, rock));
        assertNull(ComboSignals.abandonAtMainNextTurn(v, rock));

        addCard("Null Rod", opp);
        v = t.view();
        assertTrue(v.denied(rock));
        assertTrue(v.deniedNow(rock));
        assertTrue(v.deniedThisTurn(rock));
        assertTrue(ComboSignals.deniedAtMainNextTurn(v, rock));
        assertEquals(ComboSignals.abandonAtMainNextTurn(v, rock), Signal.DENIED);
        assertFalse(v.executableNow().contains(rock));
    }

    @Test
    public void everyDenialReadingFreesTheWinWithABallista() {
        // the Ballista's exception to "we cannot win" holds at every moment a loop line is read, not at its window
        // alone: an opposing Platinum Angel, and a Walking Ballista in our hand
        start();
        addCard(ANGEL, opp);
        addCardToZone(BALLISTA, ai, ZoneType.Hand);
        final ComboLines.Line rock = line("129-4131");
        final ComboTracker.View v = t.view();
        assertFalse(v.denied(rock));
        assertFalse(v.deniedNow(rock));
        assertFalse(v.deniedThisTurn(rock));
        assertFalse(ComboSignals.deniedAtMainNextTurn(v, rock));
        assertTrue(v.executable().contains(rock));
        assertTrue(v.executableNow().contains(rock));
        assertNull(ComboSignals.abandonAtMainNextTurn(v, rock));
    }

    @Test
    public void everyDenialReadingDeniesAnUnfreedAngel() {
        // and with no Ballista of ours, the Angel denies the line at every moment
        start();
        addCard(ANGEL, opp);
        final ComboLines.Line rock = line("129-4131");
        final ComboTracker.View v = t.view();
        assertTrue(v.denied(rock));
        assertTrue(v.deniedNow(rock));
        assertTrue(v.deniedThisTurn(rock));
        assertTrue(ComboSignals.deniedAtMainNextTurn(v, rock));
        assertFalse(v.executable().contains(rock));
        assertFalse(v.executableNow().contains(rock));
        assertEquals(ComboSignals.abandonAtMainNextTurn(v, rock), Signal.DENIED);
    }

    @Test
    public void cursedTotemDeniesTheFlickerBeforeThePair() {
        // Palinchron in our hand and Deadeye unpaired on our battlefield: no flicker is granted yet, and Palinchron's
        // own return stands in for it, read on Palinchron as it would be on our battlefield (R17)
        start();
        final Card palinchron = addCardToZone(PALINCHRON, ai, ZoneType.Hand);
        ComboLoopBoards.ready(addCard(DEADEYE, ai));
        final ComboLines.Line flicker = line("1409-3542");
        assertNull(flickerOf(palinchron));
        assertFalse(denied(flicker));

        addCard("Cursed Totem", opp);
        assertEquals(abandon(flicker), Signal.DENIED);
        // in our hand its return passes: Cursed Totem's AffectedZone$ Battlefield skips it there
        assertNotNull(returnOf(palinchron));
        assertTrue(returnOf(palinchron).checkRestrictions(ai));
    }

    @Test
    public void pithingNeedleOnBasaltDeniesItsUntap() {
        // the opponent's Pithing Needle naming Basalt Monolith: its {3} untap cannot be activated, its {T} mana ability
        // can (the Needle spares mana abilities), and the rock's cycle needs both
        start();
        otherPiecesInLibrary();
        final Card needle = addCard("Pithing Needle", opp);
        needle.setNamedCards(List.of(BASALT));
        for (final SpellAbility sa : basalt.getManaAbilities()) {
            assertTrue(sa.checkRestrictions(ai), sa.toString());
        }
        assertEquals(abandon(line("129-4131")), Signal.DENIED);
        assertEquals(abandon(line("3430-4131")), Signal.DENIED);
        // Grim Monolith's lines are another rock's
        assertFalse(denied(line("2585-3430")));
        assertFalse(denied(line("129-2585-2719")));
    }

    @Test
    public void anActivationHoserThatTouchesNoPieceDeniesNoBounceRow() {
        // Cursed Totem (creatures only) and a Pithing Needle naming a card that is no piece: activation statics are on
        // the board, and no bounce row (artifacts, cast again and again) has an activation they refuse
        start();
        otherPiecesInLibrary();
        addCard("Cursed Totem", opp);
        addCard("Pithing Needle", opp).setNamedCards(List.of("Island"));
        for (final ComboLines.Line l : kind(LoopKind.BOUNCE)) {
            assertFalse(denied(l), l.variant().id());
        }
        assertFalse(denied(line("129-4131")));
        assertFalse(denied(line("2585-3430")));
    }

    @Test
    public void ionaNamingBlueDeniesTheRecastAlone() {
        // the opponent's Iona naming blue: Palinchron, blue, cannot be cast again; the bounce rows cast colourless
        // artifacts and never Hullbreaker, blue too, which stays on our battlefield; nor Kinnan, green and blue
        start();
        otherPiecesInLibrary();
        addCard("Iona, Shield of Emeria", opp).setChosenColors(List.of("blue"));
        assertEquals(abandon(line("3430-3542")), Signal.DENIED);
        for (final ComboLines.Line l : kind(LoopKind.BOUNCE)) {
            assertFalse(denied(l), l.variant().id());
        }
        assertFalse(denied(line("1409-3542")));
        assertFalse(denied(line("129-4131")));
        // Sakashima, blue, in our library: assembling the Grim line is no step of its cycle
        assertFalse(denied(line("129-2585-2719")));
    }

    @Test
    public void ionaNamingGreenLeavesTheRecastAlone() {
        // the recast casts Palinchron (blue) again, never Nyxbloom Ancient (green), which only has to be on the
        // battlefield
        start();
        otherPiecesInLibrary();
        addCard("Iona, Shield of Emeria", opp).setChosenColors(List.of("green"));
        assertFalse(denied(line("3430-3542")));
        assertFalse(denied(line("1409-3542")));
    }

    @Test
    public void teferiDeniesNoLoopOnOurTurnOrTheirs() {
        // the opponent's Teferi, Time Raveler: we cast only when we could cast a sorcery. The recast and the bounce
        // cast in our main phase on an empty stack, a sorcery's moment, whatever the turn the reading is made in
        start();
        otherPiecesInLibrary();
        addCard("Teferi, Time Raveler", opp);
        for (final ComboLines.Line l : t.deckLines()) {
            assertFalse(denied(l), l.variant().id());
        }
        game.getPhaseHandler().devModeSet(PhaseType.END_OF_TURN, opp);
        for (final ComboLines.Line l : t.deckLines()) {
            assertFalse(denied(l), l.variant().id());
        }
    }

    @Test
    public void grandAbolisherOnOurTurnDeniesNoRow() {
        // the opponent's Grand Abolisher bars the casts and the activations of our cards in its controller's turn
        // only: on ours it bars no step of any cycle
        start();
        otherPiecesInLibrary();
        addCard("Grand Abolisher", opp);
        for (final ComboLines.Line l : t.deckLines()) {
            assertFalse(denied(l), l.variant().id());
        }
    }

    @Test
    public void deafeningSilenceDeniesTheBounceButNotTheRecast() {
        // one noncreature spell a turn: the bounce casts artifacts again and again, the recast a creature
        start();
        otherPiecesInLibrary();
        addCard("Deafening Silence", opp);
        final List<ComboLines.Line> bounces = kind(LoopKind.BOUNCE);
        assertEquals(bounces.size(), 13);
        for (final ComboLines.Line l : bounces) {
            assertEquals(abandon(l), Signal.DENIED, l.variant().id());
        }
        assertFalse(denied(line("3430-3542")));
        assertFalse(denied(line("129-4131")));
    }

    @Test
    public void voidWinnowerDeniesEachBounceRowWithAnEvenCast() {
        // the opponent's Void Winnower: we cannot cast a spell of even mana value. A bounce row is barred when a piece
        // its cycle casts is even (Mox Opal and Mox Amber 0, Grim Monolith, the Signets, the Talisman and Scroll Rack
        // 2), never for Hullbreaker or Kinnan, which it does not cast (Kinnan's 2 is even)
        start();
        otherPiecesInLibrary();
        addCard("Void Winnower", opp);
        final List<String> barred = new ArrayList<>();
        for (final ComboLines.Line l : kind(LoopKind.BOUNCE)) {
            if (denied(l)) {
                barred.add(l.variant().id());
            }
        }
        assertEquals(barred, List.of("x-10", "x-11", "x-12", "x-13", "x-14", "x-3", "x-5", "x-7", "x-8", "x-9"));
        // Sol Ring, Mana Vault and Sensei's Divining Top are 1: x-2, x-4 and x-6 run; Palinchron is 7
        assertFalse(denied(line("x-4")));
        assertFalse(denied(line("3430-3542")));
        // assembling a line is no step of its cycle: Grim Monolith, 2, still in our library, bars no rock untap
        assertFalse(denied(line("2585-3430")));
    }

    @Test
    public void aSakashimaThatCopiedNoKinnanDeniesTheGrimLine() {
        // row 129-2585-2719 needs two Kinnans: Kinnan, and Sakashima entered as a copy of him
        start();
        addCard(GRIM, ai);
        final Card inHand = addCardToZone(SAKASHIMA, ai, ZoneType.Hand);
        final ComboLines.Line two = line("129-2585-2719");
        // in our hand it is read as nothing yet: it becomes a Kinnan, or not, as it enters
        assertFalse(amplifies(inHand));
        assertFalse(denied(two));

        // on our battlefield as itself: no TapsForMana trigger, so the Grim cycle nets nothing
        start();
        addCard(GRIM, ai);
        final Card plain = addCard(SAKASHIMA, ai);
        assertEquals(abandon(line("129-2585-2719")), Signal.DENIED);
        assertFalse(denied(line("129-4131")));

        // and as a copy of Kinnan (a clone state, as its entering replacement makes one): a second bonus
        plain.addCloneState(CardFactory.getCloneStates(kinnan, plain, kinnan.getFirstSpellAbility()),
                game.getNextTimestamp());
        assertTrue(amplifies(plain));
        assertEquals(ComboTracker.nameOf(plain), SAKASHIMA);
        assertFalse(denied(line("129-2585-2719")));
    }

    @Test
    public void aKinnanWithoutHisBonusDeniesTheBounceRowsThatNameHim() {
        // x-4, x-12 and x-14 name Kinnan: without his bonus they deny as the rock rows do (R26)
        start();
        addCard(HULLBREAKER, ai);
        addCard("Sol Ring", ai);
        addCard("Mana Vault", ai);
        for (final String name : List.of("Sensei's Divining Top", "Scroll Rack", "Mox Amber", "Mox Opal")) {
            addCardToZone(name, ai, ZoneType.Hand);
        }
        final List<ComboLines.Line> named = List.of(line("x-4"), line("x-12"), line("x-14"));
        for (final ComboLines.Line l : named) {
            assertFalse(denied(l), l.variant().id());
        }
        theirSpellResolvesOn("Turn to Frog", kinnan);
        for (final ComboLines.Line l : named) {
            assertEquals(abandon(l), Signal.DENIED, l.variant().id());
        }
    }

    @Test
    public void lossBlockersAreTheCreaturesThatStopOurKill() {
        // our own Platinum Angel stops no kill of ours: no loss blocker, and we can win
        start();
        addCard(ANGEL, ai);
        addCardToZone(BALLISTA, ai, ZoneType.Hand);
        assertFalse(ai.cantWin());
        assertNull(LoopSinks.lossBlocker(ai));

        // our own Abyssal Persecutor stops our win and its controller's opponents losing: Ballista may kill it, so the
        // line stands
        start();
        final Card persecutor = addCard("Abyssal Persecutor", ai);
        addCardToZone(BALLISTA, ai, ZoneType.Hand);
        assertTrue(ai.cantWin());
        assertEquals(LoopSinks.lossBlockers(ai), List.of(persecutor));
        assertFalse(denied(line("129-4131")));

        // the opponent's Abyssal Persecutor stops neither: theirs is the win it stops, ours the loss
        start();
        addCard("Abyssal Persecutor", opp);
        addCardToZone(BALLISTA, ai, ZoneType.Hand);
        assertFalse(ai.cantWin());
        assertNull(LoopSinks.lossBlocker(ai));
    }

    @Test
    public void aKillableAngelBesideAnUnkillableSourceStillDenies() {
        // every source of "we cannot win" must be a creature the ping can kill: the Angel is, Angel's Grace (an effect
        // in their command zone) is not
        start();
        addCard(ANGEL, opp);
        addCardToZone(BALLISTA, ai, ZoneType.Hand);
        final ComboLines.Line rock = line("129-4131");
        assertNull(abandon(rock));
        theirSpellResolvesOn("Angel's Grace", null);
        assertTrue(ai.cantWin());
        assertEquals(abandon(rock), Signal.DENIED);
        assertFalse(t.executable().contains(rock));
    }

    @Test
    public void aKillableAngelBesideAHexproofOneStillDenies() {
        // two opposing Platinum Angels, one of them hexproof: the ping can kill the other, and the hexproof one still
        // stops our win
        start();
        final Card angel = addCard(ANGEL, opp);
        final Card shielded = addCard(ANGEL, opp);
        addCard("Swiftfoot Boots", opp).attachToEntity(shielded, null);
        game.getAction().checkStateEffects(true);
        assertTrue(shielded.hasKeyword(Keyword.HEXPROOF));
        addCardToZone(BALLISTA, ai, ZoneType.Hand);
        assertEquals(LoopSinks.lossBlockers(ai), List.of(angel));
        assertEquals(abandon(line("129-4131")), Signal.DENIED);
    }

    @Test
    public void anAngelOutsideTheBattlefieldIsNoSourceOfTheLoss() {
        // Platinum Angel's effects are active on the battlefield only: one in a graveyard or a hand stops nothing
        start();
        final Card angel = addCard(ANGEL, opp);
        addCardToZone(ANGEL, opp, ZoneType.Graveyard);
        addCardToZone(ANGEL, opp, ZoneType.Hand);
        addCardToZone(BALLISTA, ai, ZoneType.Hand);
        assertTrue(ai.cantWin());
        assertEquals(LoopSinks.lossBlockers(ai), List.of(angel));
        assertNull(abandon(line("129-4131")));
    }

    @Test
    public void aBallistaOutsideHandAndBattlefieldFreesNothing() {
        // a Walking Ballista of ours in our graveyard or in exile has no ping to aim
        start();
        addCard(ANGEL, opp);
        addCardToZone(BALLISTA, ai, ZoneType.Graveyard);
        addCardToZone(BALLISTA, ai, ZoneType.Exile);
        assertEquals(abandon(line("129-4131")), Signal.DENIED);
        assertNull(LoopSinks.lossBlocker(ai));
        assertEquals(LoopSinks.lossBlockers(ai), List.of());
    }

    @Test
    public void aReplacementIsNoLossBlocker() {
        // only a "can't happen" effect blocks: an opposing Exquisite Archangel replaces the loss (it is exiled
        // instead), so it is no loss blocker and the Ballista has nothing to kill first
        start();
        addCard("Exquisite Archangel", opp);
        addCardToZone(BALLISTA, ai, ZoneType.Hand);
        assertFalse(ai.cantWin());
        assertNull(LoopSinks.lossBlocker(ai));
        assertEquals(LoopSinks.lossBlockers(ai), List.of());
    }
}
