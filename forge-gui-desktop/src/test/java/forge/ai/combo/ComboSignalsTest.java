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
import forge.game.card.CounterEnumType;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.spellability.SpellAbilityStackInstance;
import forge.game.trigger.Trigger;
import forge.game.trigger.TriggerType;
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
    private static final String JACE_CONSULTATION = "V|742-3096|1|{B}|2,88|H:Demonic Consultation|B:Jace, Wielder of Mysteries";
    private static final String FBLTHP_CONSULTATION = "V|742-8266|1|{B}|2,88|B:Fblthp, Impossibly Lost|H:Demonic Consultation";

    private static final String ORACLE = "Thassa's Oracle";
    private static final String CONSULTATION = "Demonic Consultation";
    private static final String PACT = "Tainted Pact";
    private static final String LAB = "Laboratory Maniac";
    private static final String DOOM = "Doctor Doom, Unrivaled";
    private static final String JACE = "Jace, Wielder of Mysteries";
    private static final String FBLTHP = "Fblthp, Impossibly Lost";
    private static final String BEARS = "Grizzly Bears";
    private static final String DREADMAW = "Colossal Dreadmaw";
    private static final String GIANT = "Hill Giant";
    private static final String TEFERI = "Teferi, Time Raveler";

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
    public void oneShortRanksByTheManaStillToCast() {
        // final review, round 1: the table's mana values (742-2836 at 1, 742-1295 at 3) count Laboratory Maniac as in
        // play already. From this board Oracle's line costs Consultation's 1 and Oracle's 2, Lab's Consultation's 1
        // and Lab's 3: Oracle's is nearer
        start(LAB_CONSULTATION, ORACLE_CONSULTATION);
        addCardToZone(CONSULTATION, ai, ZoneType.Hand);
        addCardToZone(ORACLE, ai, ZoneType.Library);
        addCardToZone(LAB, ai, ZoneType.Library);
        // Consultation's {B} and Laboratory Maniac's {2}{U}: the dearer line's 4
        lands("Island", "Island", "Swamp", "Island");

        assertEquals(ids(t.deckLines()), List.of("742-2836", "742-1295"));
        assertEquals(t.costNow(t.deckLines().get(0)), 4);
        assertEquals(t.costNow(t.deckLines().get(1)), 3);
        assertEquals(ids(t.oneShort()), List.of("742-1295", "742-2836"));

        // Laboratory Maniac in play instead, Oracle in hand and Consultation the missing piece of both: Lab's line costs
        // only Consultation's 1 now, Oracle's 3, and the cheaper line leads whatever its winner
        start(ORACLE_CONSULTATION, LAB_CONSULTATION);
        addCard(LAB, ai);
        addCardToZone(ORACLE, ai, ZoneType.Hand);
        addCardToZone(CONSULTATION, ai, ZoneType.Library);
        manaForTheLine();
        assertEquals(ids(t.oneShort()), List.of("742-2836", "742-1295"));

        // two battlefield winners, both at 1 in the table: Fblthp ({1}{U}) is cheaper to cast than Laboratory Maniac
        // ({2}{U}), and leads although its id is the later
        start(LAB_CONSULTATION, FBLTHP_CONSULTATION);
        addCardToZone(CONSULTATION, ai, ZoneType.Hand);
        addCardToZone(LAB, ai, ZoneType.Library);
        addCardToZone(FBLTHP, ai, ZoneType.Library);
        lands("Island", "Island", "Swamp", "Island");
        assertEquals(ids(t.oneShort()), List.of("742-8266", "742-2836"));
    }

    @Test
    public void anEqualCostGoesToTheOracleLine() {
        // Yuriko's two lines: Fblthp, Impossibly Lost ({1}{U}) and Thassa's Oracle ({U}{U}) cost the same beside
        // Consultation, and the table's mana value (742-8266 at 1) would put Fblthp first; but Oracle wins as its two
        // casts resolve, where Fblthp's line waits on combat damage
        start(FBLTHP_CONSULTATION, ORACLE_CONSULTATION);
        addCardToZone(CONSULTATION, ai, ZoneType.Hand);
        addCardToZone(ORACLE, ai, ZoneType.Library);
        addCardToZone(FBLTHP, ai, ZoneType.Library);
        manaForTheLine();
        assertEquals(t.costNow(t.deckLines().get(0)), t.costNow(t.deckLines().get(1)));
        assertEquals(ids(t.oneShort()), List.of("742-1295", "742-8266"));
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
        // StaticAbilityCantBeCast.cantBeCastAbility writes the castSA of the card it reads: the check reads an LKI
        // copy (final review, round 1), and the card itself keeps its own
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
        ready("Craw Wurm", 1, opp);
        ready(BEARS, 1, opp);
        // our one creature chumps the Craw Wurm's 6 (no trample: follow-up F2 puts a trampler's excess through),
        // leaving the Bears' 2
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

    // ---- review round 2 ----

    @Test
    public void aSpentOracleIsNotRevivedByAnotherCardsTrigger() {
        // a spent Oracle in play waits for its own entering trigger: an entering trigger of ours that some other card
        // hosts is not it
        start(ORACLE_CONSULTATION);
        addCard(ORACLE, ai);
        final Card consultation = addCardToZone(CONSULTATION, ai, ZoneType.Library);
        manaForTheLine();
        assertEquals(t.live(), List.of());

        final SpellAbilityStackInstance other = enters(addCardToZone("Elvish Visionary", ai, ZoneType.Hand));
        assertSame(other.getActivatingPlayer(), ai);
        assertEquals(t.where(ORACLE), ComboTracker.Where.BATTLEFIELD);
        assertEquals(t.live(), List.of());
        assertEquals(t.executable(), List.of());
        assertEquals(t.oneShort(), List.of());
        assertFalse(t.protects(consultation));
    }

    @Test
    public void aSpentOracleInPlayLeavesTheLabLinesAlone() {
        // the spent-Oracle rule kills only the lines Oracle wins: Laboratory Maniac's needs no entering trigger
        start(ORACLE_CONSULTATION, LAB_CONSULTATION);
        final Card oracle = addCard(ORACLE, ai);
        addCard(LAB, ai);
        final Card consultation = addCardToZone(CONSULTATION, ai, ZoneType.Hand);
        manaForTheLine();
        assertTrue(game.getStack().isEmpty());
        assertEquals(ids(t.deckLines()), List.of("742-1295", "742-2836"));

        assertEquals(ids(t.live()), List.of("742-2836"));
        assertEquals(ids(t.executable()), List.of("742-2836"));
        assertTrue(t.protects(consultation));
        assertFalse(t.protects(oracle), "the spent Oracle's only line is dead");
    }

    @Test
    public void aLineWithNothingMissingIsNotOneShort() {
        // every piece in hand: live, nothing to fetch, so not one short
        start(ORACLE_CONSULTATION);
        addCardToZone(ORACLE, ai, ZoneType.Hand);
        final Card consultation = addCardToZone(CONSULTATION, ai, ZoneType.Hand);
        manaForTheLine();
        final ComboLines.Line l = t.deckLines().get(0);
        assertEquals(ids(t.live()), List.of("742-1295"));
        assertEquals(t.missing(l), List.of());
        assertEquals(t.oneShort(), List.of());

        // the same line with Consultation in the library is one short
        ai.getZone(ZoneType.Hand).remove(consultation);
        addCardToZone(CONSULTATION, ai, ZoneType.Library);
        assertEquals(t.missing(l), List.of(CONSULTATION));
        assertEquals(ids(t.oneShort()), List.of("742-1295"));
    }

    @Test
    public void linvalaDeniesOnlyDoomsLine() {
        // Linvala bars our Doctor Doom's {T} ability, which only the Doom line uses
        start(DOOM_CONSULTATION, ORACLE_CONSULTATION);
        addCard(DOOM, ai);
        addCardToZone(ORACLE, ai, ZoneType.Hand);
        addCardToZone(CONSULTATION, ai, ZoneType.Library);
        manaForTheLine();
        assertEquals(ids(t.deckLines()), List.of("x-1", "742-1295"));
        assertEquals(ids(t.live()), List.of("x-1", "742-1295"));

        addCard("Linvala, Keeper of Silence", opp);
        assertTrue(ComboSignals.denied(ai, t.deckLines().get(0), t));
        assertFalse(ComboSignals.denied(ai, t.deckLines().get(1), t));
        assertEquals(ids(t.live()), List.of("742-1295"), "Doom's line is denied, Oracle's is not");
        assertEquals(ids(t.executable()), List.of("742-1295"));
    }

    @Test
    public void surerKillByBurnWithExactlyTheMana() {
        // a burn spell whose mana value is exactly the mana we can make now: Lightning Bolt and one Mountain
        start(LAB_CONSULTATION);
        addCard(LAB, ai);
        addCardToZone(CONSULTATION, ai, ZoneType.Hand);
        lands("Mountain");
        final ComboLines.Line l = t.deckLines().get(0);
        opp.setLife(3, null);
        assertNull(abandon(l), "Consultation's 1 against one Mountain, and nothing threatens");

        addCardToZone("Lightning Bolt", ai, ZoneType.Hand);
        assertEquals(abandon(l), Signal.SURER_KILL, "Bolt's 1 against the one Mountain");
    }

    // ---- final review, round 1 ----

    @Test
    public void drannithMagistrateBarsOnlyAPieceCastFromOutsideTheHand() {
        // an opposing Drannith Magistrate: "Your opponents can't cast spells from anywhere other than their hands".
        // Oracle in our hand and Consultation in our library (a tutor or a draw brings it to the hand first) are cast
        // from the hand, so the line stays live and executable; the bar is read as the cast reads it
        final ComboLines.Line l = oracleLine();
        manaForTheLine();
        addCard("Drannith Magistrate", opp);
        assertNull(abandon(l));
        assertFalse(ComboSignals.denied(ai, l, t));
        assertEquals(ids(t.live()), List.of("742-1295"));
        assertEquals(ids(t.executable()), List.of("742-1295"));
        // the real card's castSA is never written by the check
        assertNull(t.find(ORACLE, ZoneType.Hand).getCastSA());

        // Oracle in the command zone is cast from there: barred
        start(ORACLE_CONSULTATION);
        addCardToZone(CONSULTATION, ai, ZoneType.Hand);
        addCardToZone(ORACLE, ai, ZoneType.Command);
        manaForTheLine();
        final ComboLines.Line commander = t.deckLines().get(0);
        assertNull(abandon(commander));
        addCard("Drannith Magistrate", opp);
        assertEquals(abandon(commander), Signal.DENIED);
        assertEquals(t.executable(), List.of());
    }

    @Test
    public void aWinnerInPlayThatLostItsWinIsDenied() {
        // an opposing Imprisoned in the Moon on each battlefield winner leaves a colorless land with "{T}: Add {C}"
        // alone, still named for its printed card: Laboratory Maniac and Jace lose the Draw replacement that wins,
        // Doctor Doom its {T} draw, Fblthp its combat-damage trigger. Each line is denied, so it is neither chased nor
        // executed
        final String[][] rows = {{LAB_CONSULTATION, LAB}, {JACE_CONSULTATION, JACE}, {DOOM_CONSULTATION, DOOM},
                {FBLTHP_CONSULTATION, FBLTHP}};
        for (final String[] row : rows) {
            start(row[0]);
            final Card winner = ready(row[1], 1, ai).get(0);
            addCardToZone(CONSULTATION, ai, ZoneType.Hand);
            lands("Swamp");
            final ComboLines.Line l = t.deckLines().get(0);
            assertNull(abandon(l), row[1]);

            imprison(winner);
            assertEquals(t.where(row[1]), ComboTracker.Where.BATTLEFIELD, row[1]);
            assertEquals(abandon(l), Signal.DENIED, row[1]);
            assertEquals(t.live(), List.of(), row[1]);
            assertEquals(t.executable(), List.of(), row[1]);
        }

        // the same winner in our library is not read: it has its win when it enters
        start(LAB_CONSULTATION);
        final Card lab = addCardToZone(LAB, ai, ZoneType.Library);
        addCardToZone(CONSULTATION, ai, ZoneType.Hand);
        lands("Swamp", "Island", "Island", "Island");
        addCard("Imprisoned in the Moon", opp);
        assertTrue(lab.isInZone(ZoneType.Library));
        assertNull(abandon(t.deckLines().get(0)));
    }

    @Test
    public void aViewReadsEachLineListOnceAndAgreesWithTheTracker() {
        // a decision reads through one view (spec 6.2 and 7: a decision costs O(1) amortised): its line lists are read
        // once and kept, they are the tracker's own answers, and the view is one moment of the board
        oracleLine();
        manaForTheLine();
        final ComboTracker.View v = t.view();
        assertSame(v.live(), v.live());
        assertSame(v.executable(), v.executable());
        assertSame(v.oneShort(), v.oneShort());
        assertEquals(v.live(), t.live());
        assertEquals(v.executable(), t.executable());
        assertEquals(v.oneShort(), t.oneShort());
        for (final String name : List.of(ORACLE, CONSULTATION, LAB)) {
            assertEquals(v.where(name), t.where(name), name);
        }
        assertSame(v.card(ORACLE), t.find(ORACLE, ZoneType.Hand));
        assertSame(v.card(CONSULTATION), t.find(CONSULTATION, ZoneType.Library));

        // Consultation exiled after the view was read: the view keeps its moment, a fresh one reads the move
        game.getAction().moveTo(ZoneType.Exile, t.find(CONSULTATION, ZoneType.Library), null, AbilityKey.newMap());
        assertEquals(ids(v.live()), List.of("742-1295"));
        assertEquals(t.live(), List.of());
        assertEquals(t.view().where(CONSULTATION), ComboTracker.Where.GONE);
    }

    @Test
    public void anImprisonedFblthpsFiredTriggerStillWins() {
        // Fblthp's trigger on our stack resolves whatever Fblthp has now: its line stays executable
        start(FBLTHP_CONSULTATION);
        final Card fblthp = ready(FBLTHP, 1, ai).get(0);
        addCardToZone(CONSULTATION, ai, ZoneType.Hand);
        lands("Swamp");
        SpellAbility trigger = null;
        for (final Trigger tr : fblthp.getTriggers()) {
            if (tr.getMode() == TriggerType.DamageAll) {
                trigger = tr.ensureAbility().copy(ai);
                trigger.setTrigger(tr);
            }
        }
        assertTrue(trigger != null);
        game.getStack().add(trigger);
        imprison(fblthp);
        assertTrue(fblthp.getTriggers().isEmpty());
        assertFalse(ComboSignals.denied(ai, t.deckLines().get(0), t));
        assertEquals(ids(t.executable()), List.of("742-8266"));
    }

    // ---- follow-up F2: signals in pods and in combat ----

    @Test
    public void dangerReadsEachOpponentAlone() {
        // three seats, a ready Hill Giant (3/3) for each opponent: each attacks in its own turn, so 3 and 3 come at
        // our 5, never 6 at once
        ComboLines.Line l = threeSeatOracleLine();
        ready(GIANT, 1, game.getPlayers().get(0));
        ready(GIANT, 1, game.getPlayers().get(2));
        ai.setLife(5, null);
        assertNull(abandon(l), "3 and 3 against 5: no one opponent reaches it");
        // one opponent's own 3 reaches our 3
        ai.setLife(3, null);
        assertEquals(abandon(l), Signal.DANGER);

        // one opponent with both 3/3s: 6 against 5
        l = threeSeatOracleLine();
        ready(GIANT, 2, game.getPlayers().get(0));
        ai.setLife(5, null);
        assertEquals(abandon(l), Signal.DANGER);

        // every opponent is read: a ready Colossal Dreadmaw held only by the opponent the reading meets second (turn
        // order from us, seat 2 then seat 0)
        l = threeSeatOracleLine();
        ready(DREADMAW, 1, ai.getOpponents().get(1));
        ai.setLife(5, null);
        assertEquals(abandon(l), Signal.DANGER);
    }

    @Test
    public void aChumpCannotBlockAFlier() {
        // our Grizzly Bears cannot block a Serra Angel (flying): its 4 comes through whole, at our 4
        final ComboLines.Line l = oracleLine();
        manaForTheLine();
        addCard(BEARS, ai);
        ready("Serra Angel", 1, opp);
        ai.setLife(4, null);
        assertEquals(abandon(l), Signal.DANGER);

        // a Suntail Hawk (flying) can chump it
        addCard("Suntail Hawk", ai);
        assertNull(abandon(l));
    }

    @Test
    public void trampleGoesThroughAChump() {
        // our 1/1 Fugitive Wizard chumps a Colossal Dreadmaw (6/6 trample): the 5 past its toughness reaches our 5
        ComboLines.Line l = oracleLine();
        manaForTheLine();
        addCard("Fugitive Wizard", ai);
        ready(DREADMAW, 1, opp);
        ai.setLife(5, null);
        assertEquals(abandon(l), Signal.DANGER);
        ai.setLife(6, null);
        assertNull(abandon(l), "5 past the chump against 6");

        // a blocker tougher than the trampler's power lets nothing through, never less than nothing: our Wall of Stone
        // (0/8) blocks a War Mammoth (3/3 trample), and a Serra Angel the Wall cannot block brings 4 at our 4
        l = oracleLine();
        manaForTheLine();
        addCard("Wall of Stone", ai);
        ready("War Mammoth", 1, opp);
        ready("Serra Angel", 1, opp);
        ai.setLife(4, null);
        assertEquals(abandon(l), Signal.DANGER);
    }

    @Test
    public void trampleExcessReadsTheBlockersToughnessNotItsPower() {
        // a blocked trampler puts through its hit less its blocker's toughness, never less its power: our Wall of Stone
        // (0/8) stops all of a Colossal Dreadmaw's 6 (6/6 trample), so nothing reaches even our 1
        ComboLines.Line l = oracleLine();
        manaForTheLine();
        addCard("Wall of Stone", ai);
        ready(DREADMAW, 1, opp);
        ai.setLife(5, null);
        assertNull(abandon(l), "an 0/8 stops all of a 6/6 trampler");
        ai.setLife(1, null);
        assertNull(abandon(l), "nothing comes through");

        // our Craw Wurm (6/4) blocking the Dreadmaw lets the 2 past its toughness through, where its power would let
        // none
        l = oracleLine();
        manaForTheLine();
        addCard("Craw Wurm", ai);
        ready(DREADMAW, 1, opp);
        ai.setLife(3, null);
        assertNull(abandon(l), "2 past the Wurm against 3");
        ai.setLife(2, null);
        assertEquals(abandon(l), Signal.DANGER, "2 past the Wurm's toughness of 4 reaches our 2");
    }

    @Test
    public void aMenaceAttackerNeedsTwoBlockers() {
        // a Boggart Brute (3/2 menace) at our 3, and one Grizzly Bears of ours: it cannot block the Brute alone
        final ComboLines.Line l = oracleLine();
        manaForTheLine();
        addCard(BEARS, ai);
        ready("Boggart Brute", 1, opp);
        ai.setLife(3, null);
        assertEquals(abandon(l), Signal.DANGER);

        // two spare blockers can
        addCard("Runeclaw Bear", ai);
        assertNull(abandon(l));
    }

    @Test
    public void aChumpGoesWhereNoOtherCanBlock() {
        // our Suntail Hawk (flying) and Grizzly Bears against a Craw Wurm (6/4) and a Serra Angel (4/4 flying), at our
        // 4: the Wurm's larger hit is blocked first, by the Bears, which can block nothing after it, and the Hawk is
        // left for the Angel. The Hawk, first on our battlefield, on the Wurm would let the Angel's 4 through
        final ComboLines.Line l = oracleLine();
        manaForTheLine();
        addCard("Suntail Hawk", ai);
        addCard(BEARS, ai);
        assertEquals(ai.getCreaturesInPlay().get(0).getName(), "Suntail Hawk");
        ready("Craw Wurm", 1, opp);
        ready("Serra Angel", 1, opp);
        ai.setLife(4, null);
        assertNull(abandon(l));
    }

    @Test
    public void aTappedCreatureOfOursCannotChump() {
        // CombatUtil.canBlock: our Grizzly Bears tapped in our turn stays tapped through the opponent's next attack (it
        // untaps in our own untap step), so a Hill Giant's 3 reaches our 3
        final ComboLines.Line l = oracleLine();
        manaForTheLine();
        final Card bears = addCard(BEARS, ai);
        ready(GIANT, 1, opp);
        ai.setLife(3, null);
        assertNull(abandon(l));
        bears.setTapped(true);
        assertEquals(abandon(l), Signal.DANGER);
    }

    @Test
    public void dangerBlocksTheLargestHitFirstWhateverTheBattlefieldOrder() {
        // the opponent's Grizzly Bears (2/2) first on its battlefield and its Craw Wurm (6/4) second, against our one
        // Runeclaw Bear: the chump goes to the Wurm's larger hit, not to the attacker met first, leaving the Bears' 2
        final ComboLines.Line l = oracleLine();
        manaForTheLine();
        ready(BEARS, 1, opp);
        ready("Craw Wurm", 1, opp);
        assertEquals(opp.getCreaturesInPlay().get(0).getName(), BEARS);
        addCard("Runeclaw Bear", ai);
        ai.setLife(3, null);
        assertNull(abandon(l), "the one chump takes the Wurm's 6, and the Bears' 2 is short of 3");
        ai.setLife(2, null);
        assertEquals(abandon(l), Signal.DANGER, "the Bears' 2 reaches our 2");
    }

    @Test
    public void surerKillNeedsASingleOpponent() {
        // three seats, both opponents at 2 life, three ready Grizzly Bears of ours and nothing to block them: each
        // opponent alone is in reach, but one combat cannot spend the same attackers on two players
        final ComboLines.Line l = threeSeatOracleLine();
        final Player first = game.getPlayers().get(0);
        final Player third = game.getPlayers().get(2);
        ready(BEARS, 3, ai);
        first.setLife(2, null);
        third.setLife(2, null);
        assertNull(abandon(l));

        // one opponent concedes: the one left in the game is in reach
        third.concede();
        game.getAction().checkGameOverCondition();
        assertFalse(game.isGameOver());
        assertEquals(ai.getOpponents().size(), 1);
        assertSame(ai.getOpponents().get(0), first);
        assertEquals(abandon(l), Signal.SURER_KILL);

        // an opponent who cannot lose is not killable (read alone: the abandon order denies the line first)
        addCard("Lich's Mastery", first);
        assertEquals(abandon(l), Signal.DENIED);
        assertFalse(ComboSignals.surerKillNow(ai));
    }

    @Test
    public void burnPaysByColour() {
        // Lightning Bolt ({R}) and one Swamp: a mana for Bolt's mana value, but not of its colour
        start(LAB_CONSULTATION);
        addCard(LAB, ai);
        addCardToZone(CONSULTATION, ai, ZoneType.Hand);
        lands("Swamp");
        final ComboLines.Line l = t.deckLines().get(0);
        opp.setLife(3, null);
        addCardToZone("Lightning Bolt", ai, ZoneType.Hand);
        assertNull(abandon(l), "a Swamp cannot pay {R}");

        // a Mountain can
        lands("Mountain");
        assertEquals(abandon(l), Signal.SURER_KILL);
    }

    @Test
    public void noManaCountsCommanderTax() {
        // our Fblthp, Impossibly Lost ({1}{U}) in the command zone and Consultation ({B}) in hand: 3 against three
        // lands
        start(FBLTHP_CONSULTATION);
        final Card fblthp = addCardToZone(FBLTHP, ai, ZoneType.Command);
        addCardToZone(CONSULTATION, ai, ZoneType.Hand);
        lands("Island", "Island", "Swamp");
        final ComboLines.Line l = t.deckLines().get(0);
        assertNull(abandon(l));

        // cast twice before from the command zone: Forge's commander tax (CostAdjustment), 2 for each earlier cast, is
        // 4 more, 7 against 3. Forge taxes only a commander
        ai.incCommanderCast(fblthp);
        ai.incCommanderCast(fblthp);
        assertNull(abandon(l), "a card that is not a commander pays no tax");
        fblthp.setCommander(true);
        assertEquals(abandon(l), Signal.NO_MANA);
        lands("Island", "Island", "Island");
        assertEquals(abandon(l), Signal.NO_MANA, "7 against 6");
        lands("Island");
        assertNull(abandon(l), "7 against 7");

        // the same commander in our hand is cast from there, untaxed
        start(FBLTHP_CONSULTATION);
        final Card inHand = addCardToZone(FBLTHP, ai, ZoneType.Hand);
        inHand.setCommander(true);
        ai.incCommanderCast(inHand);
        ai.incCommanderCast(inHand);
        addCardToZone(CONSULTATION, ai, ZoneType.Hand);
        lands("Island", "Island", "Swamp");
        assertNull(abandon(t.deckLines().get(0)));
    }

    @Test
    public void commanderTaxIsTwoForEachEarlierCast() {
        // our commander Fblthp, Impossibly Lost ({1}{U}) in the command zone and Consultation ({B}) in hand, against
        // three lands: never cast from the command zone, it pays no tax; cast once before, 2
        start(FBLTHP_CONSULTATION);
        final Card fblthp = addCardToZone(FBLTHP, ai, ZoneType.Command);
        fblthp.setCommander(true);
        addCardToZone(CONSULTATION, ai, ZoneType.Hand);
        lands("Island", "Island", "Swamp");
        final ComboLines.Line l = t.deckLines().get(0);
        assertEquals(ai.getCommanderCast(fblthp), 0);
        assertNull(abandon(l), "never cast from the command zone: 3 against 3");
        ai.incCommanderCast(fblthp);
        assertEquals(abandon(l), Signal.NO_MANA, "cast once: 5 against 3");
        lands("Island");
        assertEquals(abandon(l), Signal.NO_MANA, "5 against 4");
        lands("Island");
        assertNull(abandon(l), "5 against 5");
    }

    @Test
    public void burnSkipsARandomAmountAtAnyDepth() {
        // a damage amount that reaches a Count$Random draws when it is calculated, so it is never calculated, however
        // deep the SVars that lead to it: Lightning Bolt made to deal X, X naming Y_2 (an SVar name may hold an
        // underscore and a digit). A Y_2 of 3 reaches the 3 life
        final ComboLines.Line l = oracleLine();
        manaForTheLine();
        lands("Mountain");
        opp.setLife(3, null);
        final SpellAbility bolt = addCardToZone("Lightning Bolt", ai, ZoneType.Hand).getFirstSpellAbility();
        bolt.putParam("NumDmg", "X");
        bolt.setSVar("X", "SVar$Y_2/Plus.0");
        bolt.setSVar("Y_2", "3");
        assertEquals(abandon(l), Signal.SURER_KILL);

        // a Y_2 that is a Random count (always 3), two SVars down
        bolt.setSVar("Y_2", "Count$Random.3.3");
        long draws = MyRandom.callCount();
        assertNull(abandon(l));
        assertEquals(MyRandom.callCount(), draws, "the burn reader drew");

        // the amount itself a Random count
        bolt.putParam("NumDmg", "Count$Random.3.3");
        draws = MyRandom.callCount();
        assertNull(abandon(l));
        assertEquals(MyRandom.callCount(), draws, "the burn reader drew");
    }

    @Test(timeOut = 60_000)
    public void burnRandomGuardReadsEachSVarOnce() {
        // SVars that name each other (X names Y, then X again): each is read once, so the guard ends, and it finds Y's
        // Random count. Calculated, X would recurse without end; the timeOut fails a guard that never ends
        final ComboLines.Line l = oracleLine();
        manaForTheLine();
        lands("Mountain");
        opp.setLife(3, null);
        final SpellAbility bolt = addCardToZone("Lightning Bolt", ai, ZoneType.Hand).getFirstSpellAbility();
        bolt.putParam("NumDmg", "X");
        bolt.setSVar("X", "SVar$Y/Plus.X");
        bolt.setSVar("Y", "Count$Random.3.3");
        final long draws = MyRandom.callCount();
        assertNull(abandon(l));
        assertEquals(MyRandom.callCount(), draws, "the burn reader drew");
    }

    // ---- follow-up F3: denial at the moment the line needs it ----

    @Test
    public void teferiDeniesTheOracleLine() {
        // an opposing Teferi, Time Raveler: we cast spells only when we could cast a sorcery. In our MAIN1, the stack
        // empty, Consultation could be cast now, but the go-off casts it with Oracle's trigger on the stack: the
        // Oracle line is denied, so Oracle is not held or protected and nothing is fetched for it (Consultation is in
        // the library: the line is one short)
        final ComboLines.Line l = oracleLine();
        manaForTheLine();
        assertEquals(ids(t.oneShort()), List.of("742-1295"));
        teferi(opp);
        assertTrue(ai.canCastSorcery());
        assertFalse(ComboSignals.deniedNow(t.view(), l), "read now, nothing bars Consultation");
        assertTrue(ComboSignals.barredAtWindowNow(t.view(), l), "at the go-off Teferi bars it");
        assertEquals(abandon(l), Signal.DENIED);
        assertTrue(ComboSignals.denied(ai, line(ORACLE_CONSULTATION), t), "a line read outside the deck's");
        assertEquals(t.live(), List.of());
        assertEquals(t.executable(), List.of());
        assertEquals(t.oneShort(), List.of());
        assertNull(t.nearest());
        assertFalse(t.protects(t.find(ORACLE, ZoneType.Hand)));

        // Oracle's trigger already on our stack: that window is now, which the reading now covers, and Teferi bars
        // Consultation now as well
        start(ORACLE_CONSULTATION);
        addCardToZone(CONSULTATION, ai, ZoneType.Hand);
        manaForTheLine();
        enters(addCardToZone(ORACLE, ai, ZoneType.Hand));
        final ComboLines.Line fired = t.deckLines().get(0);
        teferi(opp);
        assertFalse(ComboSignals.barredAtWindowNow(t.view(), fired));
        assertTrue(ComboSignals.deniedNow(t.view(), fired));
        assertEquals(abandon(fired), Signal.DENIED);
        assertEquals(t.executable(), List.of());
    }

    @Test
    public void teferiDeniesTheEndStepWindowButNotDoomsWindow() {
        // with no draw spell, Laboratory Maniac's and Jace's window is the opponent's end step, where no sorcery can be
        // cast: under an opposing Teferi, Time Raveler their lines are denied in our MAIN1 too, with Consultation or
        // Tainted Pact as the emptier
        final String[][] rows = {{LAB_CONSULTATION, LAB, CONSULTATION}, {JACE_CONSULTATION, JACE, CONSULTATION},
                {LAB_PACT, LAB, PACT}};
        for (final String[] row : rows) {
            start(row[0]);
            ready(row[1], 1, ai);
            addCardToZone(row[2], ai, ZoneType.Hand);
            lands("Swamp", "Swamp");
            final ComboLines.Line l = t.deckLines().get(0);
            assertNull(abandon(l), row[0]);
            teferi(opp);
            assertFalse(ComboSignals.deniedNow(t.view(), l), row[0]);
            assertTrue(ComboSignals.barredAtWindowNow(t.view(), l), row[0]);
            assertEquals(abandon(l), Signal.DENIED, row[0]);
        }

        // Doctor Doom's window is now: in our MAIN1 Consultation, then Doom's {T}, both while we could cast a sorcery
        start(DOOM_CONSULTATION);
        ready(DOOM, 1, ai);
        addCardToZone(CONSULTATION, ai, ZoneType.Hand);
        lands("Swamp");
        final ComboLines.Line doom = t.deckLines().get(0);
        teferi(opp);
        assertFalse(ComboSignals.barredAtWindowNow(t.view(), doom));
        assertNull(abandon(doom));
        assertEquals(ids(t.executable()), List.of("x-1"));
        // in the opponent's turn it is read now, as at the base: denied
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, opp);
        assertEquals(abandon(doom), Signal.DENIED);
    }

    @Test
    public void ruleOfLawDeniesTheOracleLine() {
        // "Each player can't cast more than one spell each turn": the go-off casts Oracle, then Consultation in
        // response to its trigger, two spells in one turn. Nothing is cast this turn yet, so Consultation alone could
        // be cast now. The Laboratory Maniac line's window is the opponent's end step, in a turn of its own, where
        // Consultation is the first spell
        for (final String hoser : List.of("Rule of Law", "Archon of Emeria")) {
            final ComboLines.Line l = oracleLine();
            manaForTheLine();
            addCard(hoser, opp);
            assertTrue(game.getStack().getSpellsCastThisTurn().isEmpty(), hoser);
            assertFalse(ComboSignals.deniedNow(t.view(), l), hoser);
            assertEquals(abandon(l), Signal.DENIED, hoser);
            assertEquals(t.live(), List.of(), hoser);

            final ComboLines.Line lab = labLine();
            addCard(hoser, opp);
            assertFalse(ComboSignals.barredAtWindowNow(t.view(), lab), hoser);
            assertNull(abandon(lab), hoser);
            // a spell of ours this turn: read now Consultation would be this turn's second, but the end step is in a
            // turn of its own
            castThisTurn("Opt", ai);
            assertTrue(ComboSignals.deniedNow(t.view(), lab), hoser);
            assertFalse(ComboSignals.barredAtWindowNow(t.view(), lab), hoser);
        }
    }

    @Test
    public void canonistDeniesTheOracleLine() {
        // Ethersworn Canonist: a player who has cast a nonartifact spell this turn can't cast another. Oracle and
        // Consultation are both nonartifact, so the go-off's second spell is barred; the Laboratory Maniac line's
        // end-step Consultation is that turn's first
        final ComboLines.Line l = oracleLine();
        manaForTheLine();
        addCard("Ethersworn Canonist", opp);
        assertFalse(ComboSignals.deniedNow(t.view(), l));
        assertEquals(abandon(l), Signal.DENIED);
        assertEquals(t.live(), List.of());

        final ComboLines.Line lab = labLine();
        addCard("Ethersworn Canonist", opp);
        assertNull(abandon(lab));
    }

    @Test
    public void deafeningSilenceAllowsTheOracleLine() {
        // "Each player can't cast more than one noncreature spell each turn": Oracle is a creature, so Consultation is
        // the go-off's first noncreature spell; a creature spell cast this turn counts for nothing either
        final ComboLines.Line l = oracleLine();
        manaForTheLine();
        addCard("Deafening Silence", opp);
        assertFalse(ComboSignals.barredAtWindowNow(t.view(), l));
        assertNull(abandon(l));
        assertEquals(ids(t.live()), List.of("742-1295"));
        castThisTurn(BEARS, ai);
        assertFalse(ComboSignals.barredAtWindowNow(t.view(), l));
        assertNull(abandon(l));
        assertEquals(ids(t.live()), List.of("742-1295"));
    }

    @Test
    public void deafeningSilenceDeniesAfterANoncreatureSpell() {
        // once we cast Opt this turn, Consultation would be the turn's second noncreature spell: the go-off is barred
        // while it can still come this turn, in our MAIN2 as well
        final ComboLines.Line l = oracleLine();
        manaForTheLine();
        addCard("Deafening Silence", opp);
        castThisTurn("Opt", ai);
        assertTrue(ComboSignals.barredAtWindowNow(t.view(), l));
        assertEquals(abandon(l), Signal.DENIED);
        assertEquals(t.live(), List.of());
        moveToMain2(game, ai);
        assertTrue(ComboSignals.barredAtWindowNow(t.view(), l), "our MAIN2");

        // from our end step on, the go-off comes in a later turn of ours, where Consultation is the first noncreature
        // spell: the window bars nothing (read now, Consultation would still be this turn's second)
        game.getPhaseHandler().devModeSet(PhaseType.END_OF_TURN, ai);
        assertFalse(ComboSignals.barredAtWindowNow(t.view(), l), "our end step");
        assertTrue(ComboSignals.deniedNow(t.view(), l), "our end step, read now");
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, opp);
        assertFalse(ComboSignals.barredAtWindowNow(t.view(), l), "the opponent's turn");

        // the opponent's noncreature spell this turn is theirs: it counts for nothing against ours
        final ComboLines.Line fresh = oracleLine();
        manaForTheLine();
        addCard("Deafening Silence", opp);
        castThisTurn("Opt", opp);
        assertFalse(ComboSignals.barredAtWindowNow(t.view(), fresh));
        assertNull(abandon(fresh));
    }

    @Test
    public void grandAbolisherDeniesOnlyItsOwnersTurn() {
        // the opponent's Grand Abolisher: during its controller's turn we cast no spells. The Oracle line goes off in
        // our own turn: not denied
        final ComboLines.Line l = oracleLine();
        manaForTheLine();
        addCard("Grand Abolisher", opp);
        assertFalse(ComboSignals.barredAtWindowNow(t.view(), l));
        assertNull(abandon(l));
        assertEquals(ids(t.live()), List.of("742-1295"));

        // the Laboratory Maniac line's window is the opponent's end step, in their turn: denied in our MAIN1, though
        // nothing bars Consultation now; in their own turn it is barred read now and at the window alike
        ComboLines.Line lab = labLine();
        addCard("Grand Abolisher", opp);
        assertFalse(ComboSignals.deniedNow(t.view(), lab));
        assertTrue(ComboSignals.barredAtWindowNow(t.view(), lab));
        assertEquals(abandon(lab), Signal.DENIED);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, opp);
        assertTrue(ComboSignals.deniedNow(t.view(), lab));
        assertTrue(ComboSignals.barredAtWindowNow(t.view(), lab));

        // our own Abolisher bars only our opponents, and one in its owner's graveyard bars nothing
        lab = labLine();
        addCard("Grand Abolisher", ai);
        assertNull(abandon(lab));
        lab = labLine();
        addCardToZone("Grand Abolisher", opp, ZoneType.Graveyard);
        assertNull(abandon(lab));

        // three seats: our end-step window is in the turn of the player right before ours, never another opponent's
        game = initAndCreateThreePlayerGame();
        ai = game.getPlayers().get(1);
        final Player before = game.getPlayers().get(0);
        final Player after = game.getPlayers().get(2);
        assertSame(game.getNextPlayerAfter(before), ai);
        assertSame(game.getNextPlayerAfter(ai), after);
        t = aiOf(ai).getComboTracker();
        t.useLines(lines(LAB_CONSULTATION));
        ready(LAB, 1, ai);
        addCardToZone(CONSULTATION, ai, ZoneType.Hand);
        lands("Swamp");
        lab = t.deckLines().get(0);
        addCard("Grand Abolisher", after);
        assertNull(abandon(lab), "the player after us");
        addCard("Grand Abolisher", before);
        assertEquals(abandon(lab), Signal.DENIED, "the player before us");
    }

    @Test
    public void noHoserNotDenied() {
        // with no CantBeCast static, nothing is barred at any window, whatever the moment: the Oracle line, with a
        // spell of ours cast this turn and another on the stack (no sorcery could be cast now) as well; the Laboratory
        // Maniac line; and the reading draws nothing
        final ComboLines.Line l = oracleLine();
        manaForTheLine();
        long draws = MyRandom.callCount();
        assertFalse(ComboSignals.barredAtWindowNow(t.view(), l));
        assertNull(abandon(l));
        assertEquals(MyRandom.callCount(), draws, "the window's reading drew");
        castThisTurn("Opt", ai);
        castOnStack("Divination");
        assertFalse(ai.canCastSorcery());
        draws = MyRandom.callCount();
        assertFalse(ComboSignals.barredAtWindowNow(t.view(), l));
        assertFalse(ComboSignals.denied(ai, l, t));
        assertEquals(ids(t.executable()), List.of("742-1295"));
        assertEquals(MyRandom.callCount(), draws, "the window's reading drew");
        final ComboLines.Line lab = labLine();
        draws = MyRandom.callCount();
        assertFalse(ComboSignals.barredAtWindowNow(t.view(), lab));
        assertNull(abandon(lab));
        assertEquals(MyRandom.callCount(), draws, "the window's reading drew");

        // a CantBeCast static that bars nothing of ours (Grafdigger's Cage: casts from graveyards and libraries) is
        // read to its end and denies nothing, drawing nothing
        final ComboLines.Line caged = oracleLine();
        manaForTheLine();
        addCard("Grafdigger's Cage", opp);
        draws = MyRandom.callCount();
        assertFalse(ComboSignals.barredAtWindowNow(t.view(), caged));
        assertNull(abandon(caged));
        assertEquals(MyRandom.callCount(), draws, "the window's reading drew");
        final ComboLines.Line cagedLab = labLine();
        addCard("Grafdigger's Cage", opp);
        draws = MyRandom.callCount();
        assertFalse(ComboSignals.barredAtWindowNow(t.view(), cagedLab));
        assertNull(abandon(cagedLab));
        assertEquals(MyRandom.callCount(), draws, "the window's reading drew");
    }

    @Test
    public void atItsMomentTheWindowReadsAsForgeDoes() {
        // at the opponent's end step, our turn next, the Laboratory Maniac lines' window is now: its reading of each
        // CantBeCast static agrees with Forge's own cast check (StaticAbilityCantBeCast.cantBeCastAbility, through
        // castBarredNow) for Consultation and for Tainted Pact, parameter by parameter
        endStepLabBoard();
        assertReadsAsForge("no static", false, false);

        endStepLabBoard();
        teferi(opp);
        assertReadsAsForge("Teferi: OnlySorcerySpeed", true, true);
        endStepLabBoard();
        teferi(ai);
        assertReadsAsForge("our Teferi: Caster", false, false);

        endStepLabBoard();
        addCard("Rule of Law", opp);
        assertReadsAsForge("Rule of Law, nothing cast", false, false);
        castThisTurn("Opt", opp);
        assertReadsAsForge("Rule of Law, the opponent's spell", false, false);
        castThisTurn("Opt", ai);
        assertReadsAsForge("Rule of Law, our spell", true, true);

        endStepLabBoard();
        addCard("Ethersworn Canonist", opp);
        castThisTurn("Sol Ring", ai);
        assertReadsAsForge("Canonist, an artifact cast", false, false);
        castThisTurn("Opt", ai);
        assertReadsAsForge("Canonist, a nonartifact cast", true, true);

        endStepLabBoard();
        addCard("Deafening Silence", opp);
        castThisTurn(BEARS, ai);
        assertReadsAsForge("Deafening Silence, a creature cast", false, false);
        castThisTurn("Opt", ai);
        assertReadsAsForge("Deafening Silence, a noncreature cast", true, true);

        endStepLabBoard();
        addCard("Grand Abolisher", opp);
        assertReadsAsForge("the opponent's Abolisher, in its turn", true, true);
        endStepLabBoard();
        addCard("Grand Abolisher", ai);
        assertReadsAsForge("our Abolisher", false, false);

        endStepLabBoard();
        final Card flail = addCard("Conqueror's Flail", opp);
        assertReadsAsForge("Conqueror's Flail, unattached", false, false);
        flail.attachToEntity(addCard(GIANT, opp), null);
        assertReadsAsForge("Conqueror's Flail, attached", true, true);

        endStepLabBoard();
        addCard("Grafdigger's Cage", opp);
        assertReadsAsForge("Grafdigger's Cage: Origin", false, false);

        endStepLabBoard();
        addCard("Drannith Magistrate", opp);
        assertReadsAsForge("Drannith Magistrate: cast from the hand", false, false);

        endStepLabBoard();
        addCard("Lavinia, Azorius Renegade", opp);
        assertReadsAsForge("Lavinia, one land", false, true);
        lands("Swamp");
        assertReadsAsForge("Lavinia, two lands", false, false);

        endStepLabBoard();
        addCard("Angel of Eternal Dawn", opp);
        assertEquals(ai.getTurn(), 0);
        assertReadsAsForge("Angel of Eternal Dawn, no turn begun", true, true);
        ai.getStats().nextTurn();
        assertReadsAsForge("Angel of Eternal Dawn, one turn", false, true);
        ai.getStats().nextTurn();
        assertReadsAsForge("Angel of Eternal Dawn, two turns", false, false);

        endStepLabBoard();
        addCard("Void Winnower", opp);
        assertReadsAsForge("Void Winnower", false, true);

        endStepLabBoard();
        addCard("Fires of Invention", ai);
        assertReadsAsForge("our Fires of Invention, not our turn", true, true);
    }

    /** Line 742-2836 in a fresh two-player game in our MAIN1: Laboratory Maniac ready in play, Consultation in hand,
     *  a Swamp. */
    private ComboLines.Line labLine() {
        start(LAB_CONSULTATION);
        ready(LAB, 1, ai);
        addCardToZone(CONSULTATION, ai, ZoneType.Hand);
        lands("Swamp");
        assertEquals(ids(t.deckLines()), List.of("742-2836"));
        return t.deckLines().get(0);
    }

    /** Lines 742-2836 and 2836-3093 at the opponent's end step, our turn next, so now is their window's moment:
     *  Laboratory Maniac ready in play, Consultation and Tainted Pact in hand, a Swamp. */
    private void endStepLabBoard() {
        start(LAB_CONSULTATION, LAB_PACT);
        ready(LAB, 1, ai);
        addCardToZone(CONSULTATION, ai, ZoneType.Hand);
        addCardToZone(PACT, ai, ZoneType.Hand);
        lands("Swamp");
        game.getPhaseHandler().devModeSet(PhaseType.END_OF_TURN, opp);
        assertSame(game.getPhaseHandler().getNextTurn(), ai);
        assertEquals(ids(t.deckLines()), List.of("742-2836", "2836-3093"));
    }

    /** At the opponent's end step each Laboratory Maniac line's window reads as Forge's own cast check reads its
     *  emptier now ({@link ComboSignals#castBarredNow}): Consultation's line barred as {@code consultation}, Tainted
     *  Pact's as {@code pact}. */
    private void assertReadsAsForge(final String why, final boolean consultation, final boolean pact) {
        final ComboTracker.View v = t.view();
        final List<ComboLines.Line> both = t.deckLines();
        final boolean[] barred = {consultation, pact};
        for (int i = 0; i < both.size(); i++) {
            final ComboLines.Line l = both.get(i);
            final String what = why + ", " + l.emptier().card;
            assertEquals(ComboSignals.castBarredNow(v, l.emptier().card), barred[i], what + ", Forge's own reading");
            assertEquals(ComboSignals.barredAtWindowNow(v, l), barred[i], what);
        }
    }

    /** {@code p}'s Teferi, Time Raveler with its four loyalty: "Each opponent can cast spells only any time they could
     *  cast a sorcery." */
    private Card teferi(final Player p) {
        final Card c = addCard(TEFERI, p);
        c.setCounters(CounterEnumType.LOYALTY, 4);
        return c;
    }

    /** {@code p}'s {@code name} cast this turn and gone from the stack: the turn's record of its casts holds it, the
     *  stack is empty again, and the card is in its owner's graveyard. */
    private void castThisTurn(final String name, final Player p) {
        final Card card = createCard(name, p);
        card.setGameTimestamp(game.getNextTimestamp());
        game.getStackZone().add(card);
        final SpellAbility sa = card.getFirstSpellAbility();
        sa.setActivatingPlayer(p);
        game.getStack().add(sa);
        game.getStack().clear();
        game.getStackZone().remove(card);
        p.getZone(ZoneType.Graveyard).add(card);
        assertTrue(game.getStack().isEmpty());
    }

    /** A three-player game whose AI (player 1, in its own MAIN1) holds the default board: line 742-1295, Oracle in
     *  hand, Consultation in the library, and the line's mana. */
    private ComboLines.Line threeSeatOracleLine() {
        game = initAndCreateThreePlayerGame();
        ai = game.getPlayers().get(1);
        opp = null;
        t = aiOf(ai).getComboTracker();
        t.useLines(lines(ORACLE_CONSULTATION));
        addCardToZone(ORACLE, ai, ZoneType.Hand);
        addCardToZone(CONSULTATION, ai, ZoneType.Library);
        manaForTheLine();
        assertEquals(ids(t.deckLines()), List.of("742-1295"));
        return t.deckLines().get(0);
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

    /** The opponent's Imprisoned in the Moon on {@code c}: a colorless land whose one ability is "{T}: Add {C}". */
    private void imprison(final Card c) {
        addCard("Imprisoned in the Moon", opp).attachToEntity(c, null);
        game.getAction().checkStateEffects(true);
        assertTrue(c.isLand() && !c.isCreature() && !c.isPlaneswalker(), c + " imprisoned");
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
