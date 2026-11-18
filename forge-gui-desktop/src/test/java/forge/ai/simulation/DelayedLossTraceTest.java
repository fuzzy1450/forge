package forge.ai.simulation;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.function.Supplier;

import org.testng.SkipException;
import org.testng.annotations.Test;

import forge.ai.PlayerControllerAi;
import forge.game.Game;
import forge.game.ability.ApiType;
import forge.game.card.Card;
import forge.game.cost.Cost;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.trigger.WrappedAbility;
import forge.game.zone.ZoneType;
import forge.util.MyRandom;
import forge.util.SimScope;

/**
 * DelayedLossTrace, the self-kill fix's print-only trace (MTG_DeckMaker's self-kill fix, DESIGN.md 6.4, RULINGS-R3
 * sections C and F). With its property unset it is inert: no trace is built, so no file is opened, and the sites'
 * judgments take no id and draw nothing, as the same judgments asked of DelayedLoss directly. With its printing path
 * on, for each combination of the copies and markers flags, its lines carry their fields in RULINGS-R3 F's order,
 * name the bound scope's seed (and "-" with none bound) and the seats' decks (their players, for unnamed decks), and
 * its judgments leave the seven SimScope counters and the draws of a counting random where the same judgments asked
 * of DelayedLoss directly leave them.
 * <p>
 * The gate, {@link DelayedLossTrace#ON}, is read once when the class loads, and the test JVM does not set the property,
 * so the printing path is driven through the class's package-private seam: an instance built here with a file and flags
 * of its own ({@code new DelayedLossTrace(file, copies, markers)}), asked through the methods the sites' static entry
 * points forward to (judgePlay, judgeFreeCast, judgeUnlessPayment, pay, skip, marker). Production code reaches only the
 * one instance the property names, and only behind the gate.
 */
public class DelayedLossTraceTest extends FlatMcTestBase {

    private static final Supplier<ComboPlan> PLAN = () -> ComboPlan.EMPTY;
    /** A plan a Pact's judgment cannot read: the judgment throws, and the site's catch answers. */
    private static final Supplier<ComboPlan> UNREADABLE = () -> {
        throw new IllegalStateException("a plan that cannot be read");
    };

    private static final List<String> HEAD = List.of("seed", "players", "turn", "phase", "site", "verdict", "card",
            "debts", "units");
    private static final List<String> OPTIONAL = List.of("unread", "held", "paid", "heard", "winner", "threat");
    private static final List<String> TAIL = List.of("copy_judged", "copy_refused");

    /**
     * The seats' deck names on the boards, a label and a hash as the harness's .dck files are named. RULINGS-R3 F
     * (amended 05:08) makes the players field each seat's deck name: the harness names every player Seat0, Seat1, ...,
     * and the deck names are what tell apart two games of one serve JVM that share a seed.
     */
    private static final String OPP_DECK = "Opponent Deck 0123abcd";
    private static final String AI_DECK = "AI Deck 89abcdef";
    /** The players field of every line made on the boards: seat 0's deck name, then seat 1's. */
    private static final String PLAYERS = OPP_DECK + "|" + AI_DECK;

    // --------------------------------------------------------------------------------------------- helpers

    private SpellAbility spell(final Player p, final String name) {
        final SpellAbility sa = put(name, p, ZoneType.Hand).getFirstSpellAbility();
        sa.setActivatingPlayer(p);
        return sa;
    }

    /** A card cast as far as the stack, by {@code p}, aimed at {@code target} when one is given. */
    private SpellAbility onStack(final String name, final Player p, final SpellAbility target) {
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

    /** A random stream that counts what is drawn from it. */
    private static final class CountingRandom extends Random {
        private static final long serialVersionUID = 1L;
        int draws;

        CountingRandom(final long seed) {
            super(seed);
        }

        @Override
        protected int next(final int bits) {
            draws++;
            return super.next(bits);
        }
    }

    private static int[] counters(final SimScope s) {
        final SimScope.Counter[] kinds = SimScope.Counter.values();
        final int[] out = new int[kinds.length];
        for (int i = 0; i < kinds.length; i++) {
            out[i] = s.peek(kinds[i]);
        }
        return out;
    }

    private static int[] minus(final int[] a, final int[] b) {
        final int[] out = new int[a.length];
        for (int i = 0; i < a.length; i++) {
            out[i] = a[i] - b[i];
        }
        return out;
    }

    /** Every card's spell abilities, in every zone a judgment reads, asked once: what follows can only read them. */
    private static void askEveryCardsAbilities(final Game g) {
        for (final ZoneType zone : new ZoneType[] { ZoneType.Battlefield, ZoneType.Hand, ZoneType.Command,
                ZoneType.Graveyard, ZoneType.Exile, ZoneType.Library, ZoneType.Stack }) {
            for (final Card c : g.getCardsIn(zone)) {
                c.getSpellAbilities();
            }
        }
    }

    /** The boards the judgments are asked on, each its own game in the one scope. */
    private static final class Boards {
        Player threatAi;
        SpellAbility threatPact;
        Player copyAi;
        SpellAbility copyPact;
        Player debtAi;
        SpellAbility crop;
        SpellAbility bolt;
        SpellAbility fortune;
        SpellAbility fortuneFree;
        Player nowAi;
        SpellAbility leak;
        SpellAbility debtPaid;
        Player winnerAi;
        SpellAbility winnerPact;
        SpellAbility winnerSlaughter;
        Player coveredAi;
        SpellAbility coveredPact;
        Player coveredCopyAi;
        SpellAbility coveredCopyPact;
        final List<Game> games = new ArrayList<>();
    }

    /**
     * A fresh game ({@link #setUpGame}) whose seats' decks carry names, as the harness's decks do: the opponent's, seat
     * 0, {@link #OPP_DECK}, and the AI's, seat 1, {@link #AI_DECK}. Each seat's deck is its own copy
     * (RegisteredPlayer.restoreDeck), so each is named apart, and a simulation copy's seats copy them, names and all.
     */
    private void setUpNamedGame() {
        setUpGame();
        opp.getRegisteredPlayer().getDeck().setName(OPP_DECK);
        ai.getRegisteredPlayer().getDeck().setName(AI_DECK);
    }

    /**
     * The AI's own second main phase, a land played, life 6, Ancient Tomb and {@code forests} Forests, and the
     * opponent's Memnite: with Memnite's 1 power to come, the Tomb's 2 damage leaves no margin (2 + 3 &lt; 5 fails),
     * so a Summoner's Pact ({2}{G}{G}) is counted from the Forests alone.
     */
    private void tombAndForests(final int forests) {
        setUpNamedGame();
        noDeadline();
        fillLibrary(ai, 10);
        fillLibrary(opp, 10);
        at(PhaseType.MAIN2, ai);
        ai.setLandsPlayedThisTurn(1);
        ai.setLife(6, null);
        put("Ancient Tomb", ai, ZoneType.Battlefield);
        put("Forest", forests, ai, ZoneType.Battlefield);
        put("Memnite", opp, ZoneType.Battlefield);
    }

    /**
     * A simulation copy of the game, as Flat MC's rollouts make one, and the copy's own Summoner's Pact, the copy's AI
     * its activator. The game and its copy join the boards.
     */
    private SpellAbility copysSummonersPact(final Boards b) {
        final GameSimulator copy = new GameSimulator(game, ai);
        final Player copyAi = copy.getSimulatedAiPlayer();
        SpellAbility pact = null;
        for (final Card c : copyAi.getCardsIn(ZoneType.Hand)) {
            if ("Summoner's Pact".equals(c.getName())) {
                pact = c.getFirstSpellAbility();
                pact.setActivatingPlayer(copyAi);
            }
        }
        assertTrue(copy.getSimulatedGameState().isSimulationCopy() && pact != null,
                "a simulation copy of the board, with its Summoner's Pact");
        b.games.add(game);
        b.games.add(copy.getSimulatedGameState());
        return pact;
    }

    /**
     * Five boards, each a game whose seats' decks are named ({@link #setUpNamedGame}), with every card's abilities
     * asked afterwards:
     * <ul>
     *   <li>the threat board: {@link #tombAndForests} with Forest x2, Summoner's Pact in hand: refused, two units for
     *       {2}{G}{G}; with no threat term the Tomb's two count too and it is allowed
     *       (FlatMonteCarloPactGuardTest.reviewThreatOnOwnTurnAfterCombat). A simulation copy of it too;</li>
     *   <li>the debt board: Summoner's Pact resolved in the AI's first main phase ({2}{G}{G} due), Forest x2 and
     *       Mountain x2, exactly the debt; Crop Rotation, Lightning Bolt and Final Fortune in hand, and a free copy of
     *       Final Fortune as cascade casts it;</li>
     *   <li>the upkeep board: the AI's upkeep with its Pact of Negation's trigger on the stack ({3}{U}{U} due now),
     *       Island x5, and the opponent's Mana Leak;</li>
     *   <li>the winner board: the AI's first main phase, its Thassa's Oracle's trigger on the stack, Island x2 and a
     *       Swamp; Pact of Negation in hand, refused (three units for {3}{U}{U}), and Slaughter Pact, covered
     *       ({2}{B});</li>
     *   <li>the covered board: {@link #tombAndForests} with Forest x4, Summoner's Pact in hand: the threat term still
     *       leaves the Tomb out, and the four Forests cover {2}{G}{G}: allowed, the threat costing the count the Tomb's
     *       two units and the verdict nothing. A simulation copy of it too.</li>
     * </ul>
     */
    private Boards boards() {
        final Boards b = new Boards();

        tombAndForests(2);
        b.threatAi = ai;
        b.threatPact = spell(ai, "Summoner's Pact");
        b.copyPact = copysSummonersPact(b);
        b.copyAi = b.copyPact.getActivatingPlayer();

        setUpNamedGame();
        noDeadline();
        fillLibrary(ai, 10);
        fillLibrary(opp, 10);
        at(PhaseType.MAIN1, ai);
        onStack("Summoner's Pact", ai, null);
        game.getPhaseHandler().onStackResolved();
        playUntilStackClear(game);
        assertTrue(DelayedLoss.owed(ai, null).hasNext(), "the resolved Summoner's Pact's debt: due at the next upkeep");
        put("Forest", 2, ai, ZoneType.Battlefield);
        put("Mountain", 2, ai, ZoneType.Battlefield);
        b.debtAi = ai;
        b.crop = spell(ai, "Crop Rotation");
        b.bolt = spell(ai, "Lightning Bolt");
        b.fortune = spell(ai, "Final Fortune");
        b.fortuneFree = b.fortune.copyWithNoManaCost();
        b.games.add(game);

        setUpNamedGame();
        noDeadline();
        fillLibrary(ai, 10);
        fillLibrary(opp, 10);
        at(PhaseType.MAIN1, opp);
        final SpellAbility bears = onStack("Grizzly Bears", opp, null);
        onStack("Pact of Negation", ai, bears);
        game.getPhaseHandler().onStackResolved();
        playUntilStackClear(game);
        for (int i = 0; i < 500 && !game.isGameOver()
                && !(game.getPhaseHandler().is(PhaseType.UPKEEP, ai) && !game.getStack().isEmpty()); i++) {
            game.getPhaseHandler().mainLoopStep();
        }
        final SpellAbility trigger = game.getStack().peekAbility();
        assertEquals(trigger.getApi(), ApiType.LosesGame, "the Pact's pay-or-lose trigger on the stack at the upkeep");
        put("Island", 5, ai, ZoneType.Battlefield);
        b.nowAi = ai;
        b.leak = put("Mana Leak", opp, ZoneType.Hand).getFirstSpellAbility();
        b.leak.setActivatingPlayer(opp);
        b.debtPaid = ((WrappedAbility) trigger).getWrappedAbility();
        b.games.add(game);

        setUpNamedGame();
        noDeadline();
        fillLibrary(ai, 10);
        fillLibrary(opp, 10);
        at(PhaseType.MAIN1, ai);
        put("Island", 2, ai, ZoneType.Battlefield);
        put("Swamp", ai, ZoneType.Battlefield);
        onStack("Thassa's Oracle", ai, null);
        game.getPhaseHandler().onStackResolved();
        for (int i = 0; i < 50 && !game.isGameOver() && !oraclesTriggerOnTop(); i++) {
            game.getPhaseHandler().mainLoopStep();
        }
        assertTrue(oraclesTriggerOnTop(), "Thassa's Oracle's trigger on the stack:\n" + gameStateToString(game));
        b.winnerAi = ai;
        b.winnerPact = spell(ai, "Pact of Negation");
        b.winnerSlaughter = spell(ai, "Slaughter Pact");
        b.games.add(game);

        tombAndForests(4);
        b.coveredAi = ai;
        b.coveredPact = spell(ai, "Summoner's Pact");
        b.coveredCopyPact = copysSummonersPact(b);
        b.coveredCopyAi = b.coveredCopyPact.getActivatingPlayer();

        for (final Game g : b.games) {
            askEveryCardsAbilities(g);
        }
        return b;
    }

    private boolean oraclesTriggerOnTop() {
        if (game.getStack().isEmpty()) {
            return false;
        }
        final SpellAbility top = game.getStack().peekAbility();
        return top.isTrigger() && top.getHostCard() != null && "Thassa's Oracle".equals(top.getHostCard().getName());
    }

    /** The judgments asked of DelayedLoss directly, as every site asks them with the trace off; their answers. */
    private static List<Object> askedDirectly(final Boards b) {
        final List<Object> out = new ArrayList<>();
        out.add(DelayedLoss.problem(b.threatAi, b.threatPact, PLAN));
        out.add(DelayedLoss.problem(b.threatAi, b.threatPact, PLAN));
        String unread;
        try {
            unread = DelayedLoss.problem(b.threatAi, b.threatPact, UNREADABLE);
        } catch (final RuntimeException e) {
            unread = DelayedLoss.refusalWhenUnread(b.threatPact);
        }
        out.add(unread);
        out.add(DelayedLoss.problem(b.copyAi, b.copyPact, PLAN));
        out.add(DelayedLoss.problem(b.debtAi, b.crop, PLAN));
        out.add(DelayedLoss.problem(b.debtAi, b.bolt, PLAN));
        out.add(DelayedLoss.declinesFreeCast(b.debtAi, b.fortune));
        out.add(DelayedLoss.declinesFreeCast(b.debtAi, b.crop));
        out.add(DelayedLoss.kind(b.fortune) != null);
        out.add(DelayedLoss.declinesUnlessPayment(b.nowAi, b.leak, new Cost("1", true)));
        out.add(DelayedLoss.problem(b.winnerAi, b.winnerPact, PLAN));
        out.add(DelayedLoss.problem(b.coveredAi, b.coveredPact, PLAN));
        out.add(DelayedLoss.problem(b.coveredAi, b.coveredPact, PLAN));
        out.add(DelayedLoss.problem(b.winnerAi, b.winnerSlaughter, PLAN));
        out.add(DelayedLoss.problem(b.coveredCopyAi, b.coveredCopyPact, PLAN));
        return out;
    }

    /** The same judgments through the trace's printing path, with a marker and a payment beside them; their answers. */
    private static List<Object> askedThroughTheTrace(final DelayedLossTrace trace, final Boards b) {
        final List<Object> out = new ArrayList<>();
        trace.marker(b.threatAi);
        out.add(trace.judgePlay(DelayedLossTrace.S1, b.threatAi, b.threatPact, PLAN, null));
        out.add(trace.judgePlay(DelayedLossTrace.S4, b.threatAi, b.threatPact, PLAN, DelayedLossTrace.HEARD_TALLY));
        out.add(trace.judgePlay(DelayedLossTrace.M19, b.threatAi, b.threatPact, UNREADABLE, null));
        trace.marker(b.copyAi);
        out.add(trace.judgePlay(DelayedLossTrace.S1, b.copyAi, b.copyPact, PLAN, null));
        out.add(trace.judgePlay(DelayedLossTrace.S3, b.debtAi, b.crop, PLAN, null));
        out.add(trace.judgePlay(DelayedLossTrace.S1, b.debtAi, b.bolt, PLAN, null));
        out.add(trace.judgeFreeCast(DelayedLossTrace.S5, b.debtAi, b.fortune));
        out.add(trace.judgeFreeCast(DelayedLossTrace.S5B, b.debtAi, b.crop));
        trace.skip(b.debtAi, b.fortune);
        out.add(true);
        out.add(trace.judgeUnlessPayment(b.nowAi, b.leak, new Cost("1", true)));
        trace.pay(b.nowAi, b.debtPaid, new int[] { 0, 1, 0, 1 }, true);
        out.add(trace.judgePlay(DelayedLossTrace.S1, b.winnerAi, b.winnerPact, PLAN, null));
        // review TB-2: allowed judgments (at S4 with a heard word, at S3b, with a winner's trigger on the stack), a
        // copy's allowed judgment, and a payment that did not pay
        out.add(trace.judgePlay(DelayedLossTrace.S4, b.coveredAi, b.coveredPact, PLAN, DelayedLossTrace.HEARD_STOCK));
        out.add(trace.judgePlay(DelayedLossTrace.S3B, b.coveredAi, b.coveredPact, PLAN, null));
        out.add(trace.judgePlay(DelayedLossTrace.S1, b.winnerAi, b.winnerSlaughter, PLAN, null));
        out.add(trace.judgePlay(DelayedLossTrace.S1, b.coveredCopyAi, b.coveredCopyPact, PLAN, null));
        trace.pay(b.nowAi, b.debtPaid, new int[] { 1, 2, 3, 4 }, false);
        return out;
    }

    /** The lines the trace wrote for {@link #askedThroughTheTrace}, field for field, with these flags. */
    private static List<String> expectedLines(final long seed, final boolean copies, final boolean markers) {
        final String head = "seed=" + seed + "\tplayers=" + PLAYERS + "\t";
        final String threat = head + "turn=1\tphase=MAIN2\t";
        final String debt = head + "turn=1\tphase=MAIN1\t";
        final String upkeep = head + "turn=2\tphase=UPKEEP\t";
        final String twoForests = "NEXT(life=6,threat=1):2{Forest=G;Forest=G}";
        final String fourForests = "NEXT(life=6,threat=1):4{Forest=G;Forest=G;Forest=G;Forest=G}";
        final String islandsAndSwamp = "NEXT(life=20,threat=0):3{Island=U;Island=U;Swamp=B}";
        final String none = "\tcopy_judged=0\tcopy_refused=0";
        final String one = "\tcopy_judged=1\tcopy_refused=1"; // the copy's judgment is counted, printed or not
        final String two = "\tcopy_judged=2\tcopy_refused=1"; // and the copy's allowed one: judged, not refused
        final List<String> lines = new ArrayList<>();
        if (markers) {
            lines.add("decision\t1\tMAIN2\tseed=" + seed + "\tplayers=" + PLAYERS);
        }
        lines.add(threat + "site=S1\tverdict=pact-unpayable\tcard=Summoner's Pact\tdebts=-\tunits=" + twoForests
                + "\tthreat=threat-only" + none);
        lines.add(threat + "site=veto\tverdict=pact-unpayable\tcard=Summoner's Pact\tdebts=-\tunits=" + twoForests
                + "\theard=tally\tthreat=threat-only" + none);
        lines.add(threat + "site=M19\tverdict=pact-unpayable\tcard=Summoner's Pact\tdebts=-\tunits=-\tunread=yes"
                + none);
        if (copies) {
            lines.add(head + "turn=1\tphase=copy\tsite=S1\tverdict=pact-unpayable\tcard=Summoner's Pact\tdebts=-"
                    + "\tunits=" + twoForests + "\tthreat=threat-only" + one);
        }
        lines.add(debt + "site=S3\tverdict=debt-unprotected\tcard=Crop Rotation\tdebts=NEXT:4:G+G\tunits="
                + "NEXT(life=20,threat=0):4{Forest=G;Forest=G;Mountain=R;Mountain=R}" + one);
        lines.add(debt + "site=S1\tverdict=ok\tcard=Lightning Bolt\tdebts=NEXT:4:G+G\tunits=-" + one);
        lines.add(debt + "site=S5\tverdict=declined\tcard=Final Fortune\tdebts=-\tunits=-" + one);
        lines.add(debt + "site=S5b\tverdict=declined\tcard=Crop Rotation\tdebts=NEXT:4:G+G\tunits=-" + one);
        lines.add(debt + "site=S2skip\tverdict=skipped\tcard=Final Fortune\tdebts=-\tunits=-" + one);
        lines.add(upkeep + "site=S6b\tverdict=debt-due-now\tcard=Mana Leak\tdebts=NOW:5:U+U\tunits="
                + "NOW(life=20,threat=0):5{Island=U;Island=U;Island=U;Island=U;Island=U}" + one);
        lines.add(upkeep + "site=pay\tverdict=ok\tcard=Pact of Negation\tdebts=NOW:5:U+U\tunits=-\theld=0,1,0,1"
                + "\tpaid=yes" + one);
        lines.add(debt + "site=S1\tverdict=pact-unpayable\tcard=Pact of Negation\tdebts=-\tunits=" + islandsAndSwamp
                + "\twinner=Thassa's Oracle" + one);
        // review TB-2: an allowed S4 judgment is written as S4, not as a veto, and with no heard; an allowed judgment
        // carries no refusal tag, though its count had a threat term (no threat) or a winner's trigger is on the stack
        // (no winner); a copy's allowed judgment counts as judged, not as refused; a payment that failed says paid=no
        lines.add(threat + "site=S4\tverdict=ok\tcard=Summoner's Pact\tdebts=-\tunits=" + fourForests + one);
        lines.add(threat + "site=S3b\tverdict=ok\tcard=Summoner's Pact\tdebts=-\tunits=" + fourForests + one);
        lines.add(debt + "site=S1\tverdict=ok\tcard=Slaughter Pact\tdebts=-\tunits=" + islandsAndSwamp + one);
        if (copies) {
            lines.add(head + "turn=1\tphase=copy\tsite=S1\tverdict=ok\tcard=Summoner's Pact\tdebts=-\tunits="
                    + fourForests + two);
        }
        lines.add(upkeep + "site=pay\tverdict=ok\tcard=Pact of Negation\tdebts=NOW:5:U+U\tunits=-\theld=1,2,3,4"
                + "\tpaid=no" + two);
        return lines;
    }

    /** A line's keys: RULINGS-R3 F's nine first, the ones that apply in their order, the two counts last. */
    private static void assertFieldsInOrder(final String line) {
        final List<String> keys = new ArrayList<>();
        for (final String field : line.split("\t", -1)) {
            final int eq = field.indexOf('=');
            assertTrue(eq > 0, "a key=value field, not \"" + field + "\": " + line);
            keys.add(field.substring(0, eq));
        }
        assertTrue(keys.size() >= HEAD.size() + TAIL.size(), "every field there is: " + line);
        assertEquals(keys.subList(0, HEAD.size()), HEAD, "the first fields, in order: " + line);
        assertEquals(keys.subList(keys.size() - TAIL.size(), keys.size()), TAIL, "the counts last: " + line);
        int at = -1;
        for (final String k : keys.subList(HEAD.size(), keys.size() - TAIL.size())) {
            final int i = OPTIONAL.indexOf(k);
            assertTrue(i > at, "\"" + k + "\" among the fields that apply, in their order: " + line);
            at = i;
        }
    }

    // --------------------------------------------------------------------------------------------- the gate

    /**
     * RULINGS-R3 C and F: with the property unset the trace is inert. The gate is off; the sites' judgments (S1 through
     * ComboGuard.problem, S5 through playSaFromPlayEffect, which declines the free Final Fortune before any stock
     * judgment) and DelayedLoss's own, on boards with delayed-loss plays and pending debts, leave the seven SimScope
     * counters and the draws of a counting random where they were, as the same judgments asked of DelayedLoss alone;
     * and no trace was ever built, so no file was opened or written.
     */
    @Test(timeOut = 300_000)
    public void withThePropertyUnsetTheTraceIsInert() {
        if (!System.getProperty(DelayedLossTrace.PROPERTY, "").isEmpty()) {
            throw new SkipException("-D" + DelayedLossTrace.PROPERTY + " is set in this JVM: no unset gate to test");
        }
        assertFalse(DelayedLossTrace.ON, "the gate is off with the property unset");
        Scoped.under(new SimScope(4401L), () -> {
            final Boards b = boards();
            final SimScope scope = SimScope.current();
            final CountingRandom random = new CountingRandom(4401L);
            MyRandom.setRandom(random);
            final int[] before = counters(scope);
            final List<Object> direct = askedDirectly(b);
            final int[] afterDirect = counters(scope);
            final int drawsDirect = random.draws;
            final List<Object> sites = new ArrayList<>();
            sites.add(ComboGuard.problem(b.threatAi, b.threatPact, null, ComboPlan.EMPTY));
            sites.add(ComboGuard.problem(b.debtAi, b.crop, null, ComboPlan.EMPTY));
            sites.add(ComboGuard.problem(b.winnerAi, b.winnerPact, null, ComboPlan.EMPTY));
            sites.add(((PlayerControllerAi) b.debtAi.getController()).playSaFromPlayEffect(b.fortuneFree));
            final int[] afterSites = counters(scope);
            assertEquals(minus(afterDirect, before), new int[7], "DelayedLoss's own judgments take no id");
            assertEquals(drawsDirect, 0, "and draw nothing");
            assertEquals(minus(afterSites, afterDirect), new int[7], "nor do the sites', the trace's gate among them "
                    + List.of(SimScope.Counter.values()));
            assertEquals(random.draws - drawsDirect, 0, "and the sites draw nothing either");
            assertEquals(direct, Arrays.asList(DelayedLoss.PACT_UNPAYABLE, DelayedLoss.PACT_UNPAYABLE,
                    DelayedLoss.PACT_UNPAYABLE, DelayedLoss.PACT_UNPAYABLE, DelayedLoss.DEBT_UNPROTECTED, null, true,
                    true, true, true, DelayedLoss.PACT_UNPAYABLE, null, null, null, null), "the judgments' answers");
            assertEquals(sites, Arrays.asList(DelayedLoss.PACT_UNPAYABLE, DelayedLoss.DEBT_UNPROTECTED,
                    DelayedLoss.PACT_UNPAYABLE, false), "the sites' answers: refused, the free Final Fortune declined");
            return null;
        });
        assertFalse(DelayedLossTrace.started(), "no trace was built: no file was opened or written");
    }

    // --------------------------------------------------------------------------------------------- the printing path

    /**
     * RULINGS-R3 C and F, the printing path through the seam, for each combination of the copies and markers flags: on
     * {@link #boards()}, every kind of line (S1, a veto, an M19 judgment that threw, a copy's judgment, S3, S5, S5b, an
     * S2 skip, S6b, a payment, a refusal with a winner's trigger on the stack; and, review TB-2's, allowed judgments at
     * S4, S3b and S1 with no veto, heard, threat or winner field, a copy's allowed judgment and a payment that failed),
     * each field in its order, the bound scope's seed and the seats' deck names (review TB-3) in each, a simulation
     * copy's lines and the markers included, the copies' judgments counted, judged and refused apart, and printed only
     * with the copies on, the marker only with the markers on and only for a real game. The trace's judgments answer as
     * DelayedLoss's own, and leave the seven SimScope counters and the draws of a counting random where the same
     * judgments asked of DelayedLoss directly leave them: unchanged.
     */
    @Test(timeOut = 600_000)
    public void theLinesCarryTheirFieldsInOrderAndTheTraceOnlyPrints() throws IOException {
        long seed = 4410L;
        for (final boolean copies : new boolean[] { false, true }) {
            for (final boolean markers : new boolean[] { false, true }) {
                final long s = seed++;
                final Path file = Files.createTempFile("selfkill-trace-", ".txt");
                Files.delete(file); // the trace opens the file on its first line
                final DelayedLossTrace trace = new DelayedLossTrace(file, copies, markers);
                try {
                    Scoped.under(new SimScope(s), () -> {
                        final Boards b = boards();
                        final SimScope scope = SimScope.current();
                        final CountingRandom random = new CountingRandom(s);
                        MyRandom.setRandom(random);
                        final int[] before = counters(scope);
                        final List<Object> direct = askedDirectly(b);
                        final int[] afterDirect = counters(scope);
                        final int drawsDirect = random.draws;
                        final List<Object> traced = askedThroughTheTrace(trace, b);
                        final int[] afterTraced = counters(scope);
                        final String flags = "[copies " + copies + ", markers " + markers + "] ";
                        assertEquals(minus(afterTraced, afterDirect), minus(afterDirect, before), flags
                                + "the traced judgments move the counters as the same judgments asked directly "
                                + List.of(SimScope.Counter.values()));
                        assertEquals(minus(afterDirect, before), new int[7], flags + "which is not at all");
                        assertEquals(random.draws - drawsDirect, drawsDirect, flags + "and draw as they draw: nothing");
                        assertEquals(drawsDirect, 0, flags + "nothing");
                        assertEquals(traced, direct, flags + "the trace answers as DelayedLoss");
                        return null;
                    });
                } finally {
                    trace.close();
                }
                final List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
                Files.delete(file);
                assertEquals(lines, expectedLines(s, copies, markers), "the lines with copies " + copies + ", markers "
                        + markers);
                for (final String line : lines) {
                    if (!line.startsWith("decision\t")) {
                        assertFieldsInOrder(line);
                    }
                }
            }
        }
    }

    /**
     * RULINGS-R3 C: a line made on a thread with no SimScope bound names no seed ("seed=-"), and the copy counts it
     * gives are its thread's. The opponent's first main phase, Island x3, Pact of Negation: refused. The game's decks
     * are left unnamed, as a test game's are, and each seat's player's name stands in for its deck's (RULINGS-R3 F,
     * amended 05:08: a seat whose deck cannot be read prints its player's name).
     */
    @Test(timeOut = 120_000)
    public void aLineMadeWithNoScopeBoundSaysSeedDash() throws IOException {
        final Path file = Files.createTempFile("selfkill-trace-", ".txt");
        final DelayedLossTrace trace = new DelayedLossTrace(file, false, false);
        try {
            assertEquals(SimScope.current(), null, "no scope is bound on the test's own thread");
            setUpGame();
            at(PhaseType.MAIN1, opp);
            put("Island", 3, ai, ZoneType.Battlefield);
            final SpellAbility pon = spell(ai, "Pact of Negation");
            assertEquals(trace.judgePlay(DelayedLossTrace.S1, ai, pon, PLAN, null), DelayedLoss.PACT_UNPAYABLE);
        } finally {
            trace.close();
        }
        final List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        Files.delete(file);
        assertEquals(lines, List.of("seed=-\tplayers=p2|p1\tturn=1\tphase=MAIN1\tsite=S1\tverdict=pact-unpayable\tcard="
                + "Pact of Negation\tdebts=-\tunits=NEXT(life=20,threat=0):3{Island=U;Island=U;Island=U}\tcopy_judged=0"
                + "\tcopy_refused=0"), "one line, with no seed, and the players' names for the unnamed decks");
        assertFieldsInOrder(lines.get(0));
    }
}
