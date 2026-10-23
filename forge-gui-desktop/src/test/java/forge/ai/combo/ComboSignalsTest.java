package forge.ai.combo;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertSame;
import static org.testng.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import org.testng.annotations.Test;

import forge.ai.AITest;
import forge.ai.AiController;
import forge.ai.PlayerControllerAi;
import forge.ai.combo.ComboSignals.Signal;
import forge.game.Game;
import forge.game.ability.AbilityKey;
import forge.game.card.Card;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.spellability.SpellAbilityStackInstance;
import forge.game.zone.ZoneType;
import forge.util.MyRandom;

/** Evan's four abandon signals, and the tracker's live, executable and one-short lines (spec
 *  2026-10-22-combo-aware-ai-core-design.md sections 6.2 and 6.5). Unless a test says otherwise it starts from line
 *  742-1295, with Thassa's Oracle in hand and Demonic Consultation in the library. */
public class ComboSignalsTest extends AITest {
    private static final String WIN = "F|2|S|Win the game";
    private static final String EXILE = "F|88|H|Exile your library";
    private static final String ORACLE_CONSULTATION = "V|742-1295|3|{U}{U}{B}|2,88|H:Demonic Consultation|H:Thassa's Oracle";
    private static final String ORACLE_PACT = "V|1295-3093|4|{1}{U}{U}{B}|2|H:Tainted Pact|H:Thassa's Oracle";
    private static final String LAB_CONSULTATION = "V|742-2836|1|{B}|2,88|H:Demonic Consultation|B:Laboratory Maniac";
    private static final String LAB_PACT = "V|2836-3093|2|{1}{B}|2|H:Tainted Pact|B:Laboratory Maniac";
    private static final String DOOM_CONSULTATION = "V|x-1|1|{B}|2,88|H:Demonic Consultation|B:Doctor Doom, Unrivaled";

    private static final String ORACLE = "Thassa's Oracle";
    private static final String CONSULTATION = "Demonic Consultation";
    private static final String PACT = "Tainted Pact";
    private static final String LAB = "Laboratory Maniac";
    private static final String DOOM = "Doctor Doom, Unrivaled";
    private static final String BEARS = "Grizzly Bears";
    private static final String DREADMAW = "Colossal Dreadmaw";

    // ten distinct nonland names (GiftsUngivenAiTest's): fillLibrary makes ten of one name
    private static final List<String> NAMES = Arrays.asList("Runeclaw Bear", "Grizzly Bears", "Hill Giant",
            "Craw Wurm", "Serra Angel", "Shivan Dragon", "Llanowar Elves", "Counterspell", "Divination", "Opt");

    private Game game;
    private Player ai;
    private Player opp;
    private ComboTracker t;

    private static AiController aiOf(final Player p) {
        return ((PlayerControllerAi) p.getController()).getAi();
    }

    private static List<ComboLines.Line> lines(final String... rows) {
        final List<String> all = new ArrayList<>(List.of(WIN, EXILE));
        all.addAll(Arrays.asList(rows));
        return ComboLines.claimed(ComboTable.parse(all));
    }

    private static ComboLines.Line line(final String row) {
        return lines(row).get(0);
    }

    private static List<String> ids(final List<ComboLines.Line> lines) {
        return lines.stream().map(l -> l.variant().id()).collect(Collectors.toList());
    }

    /** A two-player game whose AI (player 1, in its own MAIN1) tracks {@code rows}; no card placed yet. */
    private void start(final String... rows) {
        game = initAndCreateGame();
        ai = game.getPlayers().get(1);
        opp = game.getPlayers().get(0);
        t = aiOf(ai).getComboTracker();
        t.useLines(lines(rows));
    }

    /** The tests' default board: line 742-1295, Oracle in our hand, Consultation in our library. */
    private ComboLines.Line oracleLine() {
        start(ORACLE_CONSULTATION);
        addCardToZone(ORACLE, ai, ZoneType.Hand);
        addCardToZone(CONSULTATION, ai, ZoneType.Library);
        assertEquals(ids(t.deckLines()), List.of("742-1295"));
        return t.deckLines().get(0);
    }

    /** Oracle's {U}{U} and Consultation's {B}: the default line's whole cost, so NO_MANA is quiet. */
    private void manaForTheLine() {
        lands("Island", "Island", "Swamp");
    }

    private void lands(final String... names) {
        for (final String name : names) {
            addCard(name, ai);
        }
    }

    /** {@code n} of {@code name} on {@code p}'s battlefield, free of summoning sickness. */
    private List<Card> ready(final String name, final int n, final Player p) {
        final List<Card> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            final Card c = addCard(name, p);
            c.setSickness(false);
            out.add(c);
        }
        return out;
    }

    private Signal abandon(final ComboLines.Line l) {
        return ComboSignals.abandon(ai, l, t);
    }

    @Test
    public void liveWithTheMana() {
        final ComboLines.Line l = oracleLine();
        lands("Island", "Island", "Swamp");

        assertNull(abandon(l));
        assertEquals(ids(t.live()), List.of("742-1295"));
        assertEquals(ids(t.executable()), List.of("742-1295"));
    }

    @Test
    public void noManaWhenTheLineOutrunsNextTurn() {
        final ComboLines.Line l = oracleLine();
        // Oracle (2) and Consultation (1) cost 3; one Island makes 1 and no land is left to make more
        lands("Island");
        assertEquals(abandon(l), Signal.NO_MANA);
        assertEquals(t.live(), List.of());

        // a land to draw adds one to next turn's mana: one Island and one to draw make 2, still short of 3 (the
        // plan's "adding an Island to the library gives null" needs a second land in play: see the Task 7 report)
        addCardToZone("Island", ai, ZoneType.Library);
        assertEquals(abandon(l), Signal.NO_MANA);
        // two lands in play and one to draw make next turn's 3
        lands("Swamp");
        assertNull(abandon(l));
        assertEquals(ids(t.live()), List.of("742-1295"));
    }

    @Test
    public void deniedByPlatinumAngel() {
        final ComboLines.Line l = oracleLine();
        manaForTheLine();
        assertNull(abandon(l));

        addCard("Platinum Angel", opp);
        // an opposing Angel reads as ai.cantWin(), and the opponent cannot lose either
        assertTrue(ai.cantWin());
        assertTrue(opp.cantLose());
        assertEquals(abandon(l), Signal.DENIED);
        assertTrue(ComboSignals.denied(ai, l, t));
        assertEquals(t.live(), List.of());
        assertEquals(t.executable(), List.of());
        assertEquals(t.oneShort(), List.of());
    }

    @Test
    public void deniedByVoidWinnower() {
        final ComboLines.Line l = oracleLine();
        manaForTheLine();
        assertNull(abandon(l));

        // Oracle's mana value 2 is even; Consultation's 1 is not
        addCard("Void Winnower", opp);
        assertEquals(abandon(l), Signal.DENIED);
        assertEquals(t.executable(), List.of());
    }

    @Test
    public void deniedByTorporOrb() {
        final ComboLines.Line l = oracleLine();
        manaForTheLine();
        assertNull(abandon(l));

        addCard("Torpor Orb", opp);
        assertEquals(abandon(l), Signal.DENIED);
        assertTrue(ComboSignals.denied(ai, l, t));

        // Laboratory Maniac wins by a replacement, not an entering trigger: the orb does not stop 742-2836
        addCard(LAB, ai);
        addCardToZone(CONSULTATION, ai, ZoneType.Hand);
        assertFalse(ComboSignals.denied(ai, line(LAB_CONSULTATION), t));
    }

    @Test
    public void deniedByLinvalaForDoom() {
        start(DOOM_CONSULTATION);
        addCard(DOOM, ai);
        addCardToZone(CONSULTATION, ai, ZoneType.Library);
        manaForTheLine();
        final ComboLines.Line l = t.deckLines().get(0);
        assertEquals(l.variant().id(), "x-1");
        assertNull(abandon(l));

        addCard("Linvala, Keeper of Silence", opp);
        assertEquals(abandon(l), Signal.DENIED);
    }

    @Test
    public void danger() {
        final ComboLines.Line l = oracleLine();
        manaForTheLine();
        ai.setLife(5, null);
        ready(DREADMAW, 1, opp);
        assertEquals(abandon(l), Signal.DANGER);

        // one creature of ours chumps the Dreadmaw
        addCard(BEARS, ai);
        assertNull(abandon(l));
    }

    @Test
    public void surerKillByCombat() {
        final ComboLines.Line l = oracleLine();
        manaForTheLine();
        opp.setLife(5, null);
        ready(BEARS, 3, ai);
        assertEquals(abandon(l), Signal.SURER_KILL);

        // the same in the opponent's turn
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, opp);
        assertNull(abandon(l));

        // back in our MAIN1, an untapped opposing Wall of Wood can block each of them
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, ai);
        assertEquals(abandon(l), Signal.SURER_KILL);
        addCard("Wall of Wood", opp);
        assertNull(abandon(l));
    }

    @Test
    public void surerKillByBurn() {
        final ComboLines.Line l = oracleLine();
        manaForTheLine();
        lands("Mountain");
        opp.setLife(3, null);
        assertNull(abandon(l));

        addCardToZone("Lightning Bolt", ai, ZoneType.Hand);
        assertEquals(abandon(l), Signal.SURER_KILL);
    }

    @Test
    public void deadInTheGraveyardOrExile() {
        start(ORACLE_CONSULTATION);
        final Card oracle = addCardToZone(ORACLE, ai, ZoneType.Hand);
        final Card consultation = addCardToZone(CONSULTATION, ai, ZoneType.Library);
        manaForTheLine();
        assertEquals(ids(t.live()), List.of("742-1295"));
        assertTrue(t.protects(oracle));

        final Card inGraveyard = game.getAction().moveTo(ZoneType.Graveyard, consultation, null, AbilityKey.newMap());
        assertEquals(t.live(), List.of());
        assertFalse(t.protects(oracle));

        game.getAction().moveTo(ZoneType.Exile, inGraveyard, null, AbilityKey.newMap());
        assertEquals(t.live(), List.of());
        assertFalse(t.protects(oracle));
        assertEquals(t.executable(), List.of());
        assertEquals(t.oneShort(), List.of());
    }

    @Test
    public void pactNeedsASingletonLibrary() {
        start(ORACLE_PACT);
        addCardToZone(ORACLE, ai, ZoneType.Hand);
        addCardToZone(PACT, ai, ZoneType.Hand);
        for (final String name : NAMES) {
            addCardToZone(name, ai, ZoneType.Library);
        }
        // Oracle's {U}{U} and Pact's {1}{B}
        lands("Island", "Island", "Swamp", "Swamp");
        assertEquals(ids(t.live()), List.of("1295-3093"));
        assertEquals(ids(t.executable()), List.of("1295-3093"));

        addCardToZone("Island", ai, ZoneType.Library);
        addCardToZone("Island", ai, ZoneType.Library);
        assertEquals(t.live(), List.of());
        assertEquals(t.executable(), List.of());
    }

    @Test
    public void signalsDrawNothing() {
        final ComboLines.Line l = oracleLine();
        manaForTheLine();
        ai.setLife(5, null);
        ready(DREADMAW, 1, opp);

        long draws = MyRandom.callCount();
        assertEquals(t.live(), List.of());
        assertEquals(MyRandom.callCount(), draws, "live() drew");

        // a board that reads every signal to its end: a chump blocker quiets DANGER, our ready Bears are blocked,
        // and a Bolt in hand falls short of 20
        ready(BEARS, 1, ai);
        addCardToZone("Lightning Bolt", ai, ZoneType.Hand);
        draws = MyRandom.callCount();
        assertNull(abandon(l));
        assertEquals(ids(t.live()), List.of("742-1295"));
        assertEquals(ids(t.executable()), List.of("742-1295"));
        assertEquals(ids(t.oneShort()), List.of("742-1295"));
        assertTrue(t.protects(t.find(ORACLE, ZoneType.Hand)));
        assertEquals(MyRandom.callCount(), draws, "a signal or a line query drew");
    }

    @Test
    public void oneShortRanksByManaValue() {
        start(ORACLE_CONSULTATION, LAB_CONSULTATION);
        addCardToZone(CONSULTATION, ai, ZoneType.Hand);
        addCardToZone(ORACLE, ai, ZoneType.Library);
        addCardToZone(LAB, ai, ZoneType.Library);
        // Consultation's {B} and Laboratory Maniac's {2}{U}: the dearer line's 4
        lands("Island", "Island", "Swamp", "Island");

        assertEquals(ids(t.deckLines()), List.of("742-1295", "742-2836"));
        assertEquals(ids(t.oneShort()), List.of("742-2836", "742-1295"));
    }

    // ---- beyond the plan's thirteen ----

    @Test
    public void executableIgnoresNoManaDangerAndASurerKill() {
        // NO_MANA: one Island
        ComboLines.Line l = oracleLine();
        lands("Island");
        final Card oracle = t.find(ORACLE, ZoneType.Hand);
        assertEquals(abandon(l), Signal.NO_MANA);
        assertFalse(ComboSignals.denied(ai, l, t));
        assertEquals(t.live(), List.of());
        assertEquals(ids(t.executable()), List.of("742-1295"));
        assertEquals(t.oneShort(), List.of());
        assertFalse(t.protects(oracle), "a line no signal leaves alone protects nothing");

        // DANGER
        l = oracleLine();
        manaForTheLine();
        ai.setLife(5, null);
        ready(DREADMAW, 1, opp);
        assertEquals(abandon(l), Signal.DANGER);
        assertEquals(t.live(), List.of());
        assertEquals(ids(t.executable()), List.of("742-1295"));

        // SURER_KILL
        l = oracleLine();
        manaForTheLine();
        opp.setLife(5, null);
        ready(BEARS, 3, ai);
        assertEquals(abandon(l), Signal.SURER_KILL);
        assertEquals(t.live(), List.of());
        assertEquals(ids(t.executable()), List.of("742-1295"));
    }

    @Test
    public void everyOpponentMustBeUnableToLose() {
        final ComboLines.Line l = oracleLine();
        manaForTheLine();
        // the opponent cannot lose, yet nothing stops us winning: denied by the every-opponent clause alone
        addCard("Lich's Mastery", opp);
        assertFalse(ai.cantWin());
        assertTrue(opp.cantLose());
        assertEquals(abandon(l), Signal.DENIED);

        // three seats: one opponent who cannot lose and one who can is not a denial
        game = initAndCreateThreePlayerGame();
        ai = game.getPlayers().get(1);
        t = aiOf(ai).getComboTracker();
        t.useLines(lines(ORACLE_CONSULTATION));
        addCardToZone(ORACLE, ai, ZoneType.Hand);
        addCardToZone(CONSULTATION, ai, ZoneType.Library);
        manaForTheLine();
        final ComboLines.Line three = t.deckLines().get(0);
        addCard("Lich's Mastery", game.getPlayers().get(0));
        assertFalse(ComboSignals.denied(ai, three, t));
        addCard("Lich's Mastery", game.getPlayers().get(2));
        assertTrue(ComboSignals.denied(ai, three, t));
    }

    @Test
    public void theCastCheckPutsTheCardsCastSABack() {
        final ComboLines.Line l = oracleLine();
        manaForTheLine();
        final Card oracle = t.find(ORACLE, ZoneType.Hand);
        assertNull(oracle.getCastSA());
        addCard("Void Winnower", opp);
        assertTrue(ComboSignals.denied(ai, l, t));
        // StaticAbilityCantBeCast.cantBeCastAbility writes the card's castSA; the check restores it
        assertNull(oracle.getCastSA());
    }

    @Test
    public void anOracleTriggerAlreadyOnOurStackIsNotDenied() {
        final ComboLines.Line l = oracleLine();
        manaForTheLine();
        final Card oracle = t.find(ORACLE, ZoneType.Hand);
        game.getAction().moveTo(ZoneType.Battlefield, oracle, null, AbilityKey.newMap());
        // the move holds its trigger as waiting; the state check runs it, then it goes on the stack
        game.getAction().checkStateEffects(true);
        game.getStack().addAllTriggeredAbilitiesToStack();
        final SpellAbilityStackInstance top = game.getStack().peek();
        assertTrue(top.isTrigger());
        assertEquals(ComboTracker.nameOf(top.getSourceCard()), ORACLE);
        assertSame(top.getActivatingPlayer(), ai);

        // a Torpor Orb that arrived after the trigger cannot stop it: Consultation in response still wins
        addCard("Torpor Orb", opp);
        assertFalse(ComboSignals.denied(ai, l, t));
        assertEquals(ids(t.executable()), List.of("742-1295"));

        // with nothing on the stack, the orb stops Oracle's next entry
        game.getStack().clear();
        assertTrue(ComboSignals.denied(ai, l, t));
    }

    @Test
    public void noManaCountsOnlyPiecesInHandLibraryOrCommand() {
        // Laboratory Maniac in play costs nothing: Consultation's 1 against one Swamp
        start(LAB_CONSULTATION);
        addCard(LAB, ai);
        addCardToZone(CONSULTATION, ai, ZoneType.Hand);
        lands("Swamp");
        final ComboLines.Line lab = t.deckLines().get(0);
        assertNull(abandon(lab));

        // in hand it costs its 3 too: 4 against one Swamp
        start(LAB_CONSULTATION);
        addCardToZone(LAB, ai, ZoneType.Hand);
        addCardToZone(CONSULTATION, ai, ZoneType.Hand);
        lands("Swamp");
        assertEquals(abandon(t.deckLines().get(0)), Signal.NO_MANA);

        // a piece in the command zone costs its mana value, and a land in hand is a land to play next turn
        start(ORACLE_CONSULTATION);
        addCardToZone(ORACLE, ai, ZoneType.Command);
        addCardToZone(CONSULTATION, ai, ZoneType.Library);
        lands("Island");
        final ComboLines.Line oracle = t.deckLines().get(0);
        assertEquals(abandon(oracle), Signal.NO_MANA);
        lands("Swamp");
        assertEquals(abandon(oracle), Signal.NO_MANA);
        addCardToZone("Island", ai, ZoneType.Hand);
        assertNull(abandon(oracle));
    }

    @Test
    public void dangerChumpsTheLargestHitsAndNeverFiresWhenWeCannotLose() {
        final ComboLines.Line l = oracleLine();
        manaForTheLine();
        ready(DREADMAW, 1, opp);
        ready(BEARS, 1, opp);
        // our one creature chumps the Dreadmaw's 6, leaving the Bears' 2
        addCard("Runeclaw Bear", ai);
        ai.setLife(3, null);
        assertNull(abandon(l));
        ai.setLife(2, null);
        assertEquals(abandon(l), Signal.DANGER);

        // our own Platinum Angel: we cannot lose, so no hit is a danger (the Angel chumps one of two Dreadmaws, the
        // other's 6 reaches our 5)
        final ComboLines.Line second = oracleLine();
        manaForTheLine();
        ready(DREADMAW, 2, opp);
        ai.setLife(5, null);
        addCard("Platinum Angel", ai);
        assertTrue(ai.cantLose());
        assertFalse(ai.cantWin());
        assertNull(abandon(second));
    }

    @Test
    public void surerKillCountsOnlyReadyAttackersBeforeAttacks() {
        final ComboLines.Line l = oracleLine();
        manaForTheLine();
        opp.setLife(5, null);
        final List<Card> bears = ready(BEARS, 3, ai);
        assertEquals(abandon(l), Signal.SURER_KILL);

        // from combat's start up to the declaration only
        game.getPhaseHandler().devModeSet(PhaseType.COMBAT_BEGIN, ai);
        assertEquals(abandon(l), Signal.SURER_KILL);
        game.getPhaseHandler().devModeSet(PhaseType.COMBAT_DECLARE_ATTACKERS, ai);
        assertNull(abandon(l));
        game.getPhaseHandler().devModeSet(PhaseType.MAIN2, ai);
        assertNull(abandon(l));

        // a tapped Bears and a sick one do not attack: 2 of 6 against 5
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, ai);
        bears.get(0).setTapped(true);
        assertNull(abandon(l));
        opp.setLife(4, null);
        assertEquals(abandon(l), Signal.SURER_KILL);
        bears.get(1).setSickness(true);
        assertNull(abandon(l));
        opp.setLife(2, null);
        assertEquals(abandon(l), Signal.SURER_KILL);
    }

    @Test
    public void surerKillNeedsEveryOpponent() {
        game = initAndCreateThreePlayerGame();
        ai = game.getPlayers().get(1);
        t = aiOf(ai).getComboTracker();
        t.useLines(lines(ORACLE_CONSULTATION));
        addCardToZone(ORACLE, ai, ZoneType.Hand);
        addCardToZone(CONSULTATION, ai, ZoneType.Library);
        manaForTheLine();
        final ComboLines.Line l = t.deckLines().get(0);
        ready(BEARS, 3, ai);

        game.getPlayers().get(0).setLife(5, null);
        assertNull(abandon(l), "one opponent in reach and one not");
        game.getPlayers().get(2).setLife(5, null);
        assertEquals(abandon(l), Signal.SURER_KILL);

        // an opponent who cannot lose is not killable, while the other can still lose (so the line is not denied)
        addCard("Lich's Mastery", game.getPlayers().get(0));
        assertFalse(ComboSignals.denied(ai, l, t));
        assertNull(abandon(l));
    }

    @Test
    public void surerKillByBurnNeedsTheDamageATargetAndTheMana() {
        final ComboLines.Line l = oracleLine();
        manaForTheLine();
        lands("Mountain");
        addCardToZone("Lightning Bolt", ai, ZoneType.Hand);
        opp.setLife(4, null);
        assertNull(abandon(l), "3 damage against 4 life");

        // a burn spell that cannot aim at a player
        opp.setLife(3, null);
        assertEquals(abandon(l), Signal.SURER_KILL);
        ai.getZone(ZoneType.Hand).remove(t.find("Lightning Bolt", ZoneType.Hand));
        addCardToZone("Flame Slash", ai, ZoneType.Hand);
        assertNull(abandon(l), "Flame Slash targets creatures only");

        // the Bolt with every land tapped: next turn's mana counts tapped lands, the mana to cast it now does not
        ai.getZone(ZoneType.Hand).remove(t.find("Flame Slash", ZoneType.Hand));
        addCardToZone("Lightning Bolt", ai, ZoneType.Hand);
        assertEquals(abandon(l), Signal.SURER_KILL);
        for (final Card land : ai.getCardsIn(ZoneType.Battlefield)) {
            land.setTapped(true);
        }
        assertNull(abandon(l));
    }

    @Test
    public void protectsReadsOnlyLiveLines() {
        final ComboLines.Line l = oracleLine();
        manaForTheLine();
        final Card oracle = t.find(ORACLE, ZoneType.Hand);
        final Card consultation = t.find(CONSULTATION, ZoneType.Library);
        final Card island = t.find("Island", ZoneType.Battlefield);
        assertNull(abandon(l));
        assertTrue(t.protects(oracle));
        assertTrue(t.protects(consultation));
        assertFalse(t.protects(island));

        // no lines: nothing is live, executable, one short or protected
        t.useLines(List.of());
        assertEquals(t.live(), List.of());
        assertEquals(t.executable(), List.of());
        assertEquals(t.oneShort(), List.of());
        assertFalse(t.protects(oracle));
    }

    @Test
    public void pactsHoldIsDroppedAndOraclesRestsOnConsultation() {
        // Review Focus 1: a pair in the library ends Tainted Pact's line, not Consultation's
        start(ORACLE_CONSULTATION, ORACLE_PACT);
        final Card oracle = addCardToZone(ORACLE, ai, ZoneType.Hand);
        final Card pact = addCardToZone(PACT, ai, ZoneType.Hand);
        addCardToZone(CONSULTATION, ai, ZoneType.Library);
        for (final String name : NAMES) {
            addCardToZone(name, ai, ZoneType.Library);
        }
        lands("Island", "Island", "Swamp", "Swamp");
        assertEquals(ids(t.live()), List.of("742-1295", "1295-3093"));
        assertTrue(t.protects(pact));

        addCardToZone("Island", ai, ZoneType.Library);
        addCardToZone("Island", ai, ZoneType.Library);
        assertEquals(ids(t.live()), List.of("742-1295"));
        assertEquals(ids(t.executable()), List.of("742-1295"));
        assertTrue(t.protects(oracle), "Oracle's hold rests on Consultation alone");
        assertFalse(t.protects(pact), "Pact is a cantrip again");
    }

    @Test
    public void oneShortPutsAMissingManaValueLastThenOrdersById() {
        start();
        addCardToZone(CONSULTATION, ai, ZoneType.Hand);
        addCardToZone(ORACLE, ai, ZoneType.Library);
        addCardToZone(PACT, ai, ZoneType.Library);
        addCardToZone(LAB, ai, ZoneType.Library);
        // Pact's {1}{B} and Laboratory Maniac's {2}{U}: the dearest line's 5
        lands("Island", "Island", "Island", "Swamp", "Swamp");
        final ComboLines.Line twoShort = line(LAB_PACT);
        t.useLines(List.of(made("b", 3), made("c", -1), twoShort, made("a", 3), made("d", 1)));

        assertEquals(ids(t.live()), List.of("b", "c", "2836-3093", "a", "d"));
        assertEquals(t.missing(twoShort), List.of(PACT, LAB));
        assertEquals(ids(t.oneShort()), List.of("d", "a", "b", "c"));
    }

    // ---- review round 1 ----

    @Test
    public void aSpentOracleInPlayIsNoLine() {
        // our Oracle in play with no entering trigger of its own on the stack (it resolved, was countered, or never
        // fired) wins nothing: its line is neither chased nor executed, and Consultation is an ordinary card again
        start(ORACLE_CONSULTATION);
        final Card oracle = addCard(ORACLE, ai);
        final Card consultation = addCardToZone(CONSULTATION, ai, ZoneType.Library);
        manaForTheLine();
        final ComboLines.Line l = t.deckLines().get(0);
        assertTrue(game.getStack().isEmpty());
        assertEquals(t.where(ORACLE), ComboTracker.Where.BATTLEFIELD);
        assertTrue(t.reachable(l));
        assertNull(abandon(l), "no signal holds: the line is dead for want of the trigger alone");
        assertEquals(t.live(), List.of());
        assertEquals(t.executable(), List.of());
        assertEquals(t.oneShort(), List.of());
        assertFalse(t.protects(consultation));
        assertFalse(t.protects(oracle));

        // an opponent's Oracle trigger on the stack is not ours
        final SpellAbilityStackInstance theirs = enters(addCardToZone(ORACLE, opp, ZoneType.Hand));
        assertSame(theirs.getActivatingPlayer(), opp);
        assertEquals(t.live(), List.of());
        assertEquals(t.executable(), List.of());
        assertFalse(t.protects(consultation));
    }

    @Test
    public void anOracleInPlayIsALineWhileItsTriggerIsOnOurStack() {
        final ComboLines.Line l = oracleLine();
        manaForTheLine();
        final Card consultation = t.find(CONSULTATION, ZoneType.Library);
        final SpellAbilityStackInstance trigger = enters(t.find(ORACLE, ZoneType.Hand));
        assertSame(trigger.getActivatingPlayer(), ai);
        assertEquals(t.where(ORACLE), ComboTracker.Where.BATTLEFIELD);
        assertNull(abandon(l));
        assertEquals(ids(t.live()), List.of("742-1295"));
        assertEquals(ids(t.executable()), List.of("742-1295"));
        assertEquals(ids(t.oneShort()), List.of("742-1295"));
        assertTrue(t.protects(consultation));

        // the trigger gone, resolved or countered: the Oracle in play is spent
        game.getStack().clear();
        assertEquals(t.live(), List.of());
        assertEquals(t.executable(), List.of());
        assertEquals(t.oneShort(), List.of());
        assertFalse(t.protects(consultation));
    }

    @Test
    public void anOracleSpellOnOurStackIsNotItsTrigger() {
        // Oracle cast into a Torpor Orb will enter with no trigger: our spell on the stack is not a fired trigger
        final ComboLines.Line l = oracleLine();
        manaForTheLine();
        ai.getZone(ZoneType.Hand).remove(t.find(ORACLE, ZoneType.Hand));
        castOnStack(ORACLE);
        assertEquals(t.where(ORACLE), ComboTracker.Where.STACK);
        assertFalse(game.getStack().peek().isTrigger());
        assertNull(abandon(l));
        assertEquals(ids(t.executable()), List.of("742-1295"));

        addCard("Torpor Orb", opp);
        assertTrue(ComboSignals.denied(ai, l, t));
        assertEquals(t.executable(), List.of());
    }

    @Test
    public void deniedWhenOnlyWeCannotWin() {
        // three seats: one opponent's Platinum Angel stops our winning while the other opponent can still lose, so
        // the cannot-win clause denies alone
        game = initAndCreateThreePlayerGame();
        ai = game.getPlayers().get(1);
        t = aiOf(ai).getComboTracker();
        t.useLines(lines(ORACLE_CONSULTATION));
        addCardToZone(ORACLE, ai, ZoneType.Hand);
        addCardToZone(CONSULTATION, ai, ZoneType.Library);
        manaForTheLine();
        final ComboLines.Line l = t.deckLines().get(0);
        assertNull(abandon(l));

        addCard("Platinum Angel", game.getPlayers().get(0));
        assertTrue(ai.cantWin());
        assertFalse(game.getPlayers().get(2).cantLose());
        assertEquals(abandon(l), Signal.DENIED);
        assertEquals(t.live(), List.of());
        assertEquals(t.executable(), List.of());
    }

    @Test
    public void abandonReadsTheSignalsInEnumOrder() {
        // DENIED before NO_MANA: one Island, then an opposing Platinum Angel
        ComboLines.Line l = oracleLine();
        lands("Island");
        assertEquals(abandon(l), Signal.NO_MANA);
        addCard("Platinum Angel", opp);
        assertEquals(abandon(l), Signal.DENIED);

        // NO_MANA before DANGER: one Island, 5 life, an opposing Dreadmaw
        l = oracleLine();
        lands("Island");
        ai.setLife(5, null);
        ready(DREADMAW, 1, opp);
        assertEquals(abandon(l), Signal.NO_MANA);

        // DANGER before SURER_KILL: a Bolt and the opponent at 3, but our 5 life against a Dreadmaw
        l = oracleLine();
        manaForTheLine();
        lands("Mountain");
        ai.setLife(5, null);
        ready(DREADMAW, 1, opp);
        opp.setLife(3, null);
        addCardToZone("Lightning Bolt", ai, ZoneType.Hand);
        assertEquals(abandon(l), Signal.DANGER);
        ai.setLife(20, null);
        assertEquals(abandon(l), Signal.SURER_KILL);
    }

    @Test
    public void castBarReadsLibraryCommandAndNotTheBattlefield() {
        // Oracle (an even mana value) in the library, Consultation in hand
        start(ORACLE_CONSULTATION);
        addCardToZone(CONSULTATION, ai, ZoneType.Hand);
        addCardToZone(ORACLE, ai, ZoneType.Library);
        manaForTheLine();
        ComboLines.Line l = t.deckLines().get(0);
        assertNull(abandon(l));
        addCard("Void Winnower", opp);
        assertEquals(abandon(l), Signal.DENIED);

        // Oracle in the command zone
        start(ORACLE_CONSULTATION);
        addCardToZone(CONSULTATION, ai, ZoneType.Hand);
        addCardToZone(ORACLE, ai, ZoneType.Command);
        manaForTheLine();
        l = t.deckLines().get(0);
        assertNull(abandon(l));
        addCard("Void Winnower", opp);
        assertEquals(abandon(l), Signal.DENIED);

        // Oracle already in play with its trigger on our stack: cast already, so never barred, and Consultation (odd)
        // in response still wins
        start(ORACLE_CONSULTATION);
        addCardToZone(CONSULTATION, ai, ZoneType.Hand);
        enters(addCardToZone(ORACLE, ai, ZoneType.Hand));
        manaForTheLine();
        l = t.deckLines().get(0);
        addCard("Void Winnower", opp);
        assertFalse(ComboSignals.denied(ai, l, t));
        assertEquals(ids(t.executable()), List.of("742-1295"));
    }

    @Test
    public void castBarReadsTheFirstPieceToo() {
        // Tainted Pact (2, even) is the line's first piece, Laboratory Maniac (3, odd) its second
        start(LAB_PACT);
        addCardToZone(PACT, ai, ZoneType.Hand);
        addCardToZone(LAB, ai, ZoneType.Library);
        lands("Island", "Island", "Island", "Swamp", "Swamp");
        final ComboLines.Line l = t.deckLines().get(0);
        assertEquals(l.names(), List.of(PACT, LAB));
        assertNull(abandon(l));
        addCard("Void Winnower", opp);
        assertEquals(abandon(l), Signal.DENIED);
    }

    @Test
    public void dangerIgnoresCreaturesThatCannotAttack() {
        // a defender's 10 power never reaches us
        final ComboLines.Line l = oracleLine();
        manaForTheLine();
        ai.setLife(5, null);
        ready("Colossus of Akros", 1, opp);
        assertNull(abandon(l));
    }

    @Test
    public void surerKillIgnoresAttackersThatCannotAttack() {
        // nor does our own defender's reach the opponent
        final ComboLines.Line l = oracleLine();
        manaForTheLine();
        opp.setLife(5, null);
        ready("Colossus of Akros", 1, ai);
        assertNull(abandon(l));
    }

    @Test
    public void surerKillCountsAFlyerNoGroundBlockerCanBlock() {
        // an untapped Wall of Wood (no reach) cannot block a Serra Angel
        final ComboLines.Line l = oracleLine();
        manaForTheLine();
        opp.setLife(4, null);
        ready("Serra Angel", 1, ai);
        addCard("Wall of Wood", opp);
        assertEquals(abandon(l), Signal.SURER_KILL);
    }

    @Test
    public void surerKillByBurnInASubAbility() {
        // Call In a Professional: an Effect root whose sub-ability deals the 3 damage
        final ComboLines.Line l = oracleLine();
        manaForTheLine();
        lands("Mountain");
        opp.setLife(3, null);
        assertNull(abandon(l));
        addCardToZone("Call In a Professional", ai, ZoneType.Hand);
        assertEquals(abandon(l), Signal.SURER_KILL);
    }

    @Test
    public void surerKillByBurnThatTargetsAPlayerOrAnOpponent() {
        // Flame Jet's ValidTgts names Player (and Planeswalker), never Any
        ComboLines.Line l = oracleLine();
        manaForTheLine();
        lands("Mountain");
        opp.setLife(3, null);
        assertNull(abandon(l));
        addCardToZone("Flame Jet", ai, ZoneType.Hand);
        assertEquals(abandon(l), Signal.SURER_KILL);

        // Stinging Vitriol's names Opponent
        l = oracleLine();
        manaForTheLine();
        lands("Mountain");
        opp.setLife(2, null);
        assertNull(abandon(l));
        addCardToZone("Stinging Vitriol", ai, ZoneType.Hand);
        assertEquals(abandon(l), Signal.SURER_KILL);
    }

    /** {@code c} enters the battlefield and its entering trigger goes on the stack (the move holds the trigger as
     *  waiting; the state check runs it): the stack's top, that trigger. */
    private SpellAbilityStackInstance enters(final Card c) {
        game.getAction().moveTo(ZoneType.Battlefield, c, null, AbilityKey.newMap());
        game.getAction().checkStateEffects(true);
        game.getStack().addAllTriggeredAbilitiesToStack();
        final SpellAbilityStackInstance top = game.getStack().peek();
        assertTrue(top.isTrigger());
        assertEquals(ComboTracker.nameOf(top.getSourceCard()), ComboTracker.nameOf(c));
        return top;
    }

    /** Our {@code name} cast: on our stack, nothing paid or resolved (ComboTrackerTest's castOnStack). */
    private Card castOnStack(final String name) {
        final Card card = createCard(name, ai);
        card.setGameTimestamp(game.getNextTimestamp());
        game.getStackZone().add(card);
        final SpellAbility sa = card.getFirstSpellAbility();
        sa.setActivatingPlayer(ai);
        game.getStack().add(sa);
        return card;
    }

    /** An Oracle + Consultation line with this id and mana value, built in code: the table's parser refuses a
     *  negative mana value. */
    private static ComboLines.Line made(final String id, final int manaValue) {
        final ComboTable.Variant v = new ComboTable.Variant(id, manaValue, "", List.of(2),
                List.of(new ComboTable.Piece(CONSULTATION, "H", false), new ComboTable.Piece(ORACLE, "H", false)));
        return new ComboLines.Line(v, ComboLines.Emptier.CONSULTATION, ComboLines.Winner.ORACLE);
    }
}
