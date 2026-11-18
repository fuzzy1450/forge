package forge.ai.simulation;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import org.testng.AssertJUnit;
import org.testng.annotations.Test;

import forge.game.Game;
import forge.game.card.Card;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.util.SimScope;

/**
 * The copier's sickness probe (MTG_DeckMaker docs/superpowers/specs/2026-11-18-flatmc-copier-sickness-design.md, section
 * 5 item 3, test T6): print-only, it names the cards whose copied flag differs from what the old copier gave them (sick
 * and hasted when copied), and the copies where a haste the original has was lost in the copy.
 */
public class SicknessProbeTest extends SimulationTest {
    private Game game;
    private Player ai;

    private void setUp() {
        game = initAndCreateGame();
        ai = game.getPlayers().get(1);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, ai);
    }

    private static Card arrived(final Card c) {
        c.setSickness(true);
        return c;
    }

    private Card greavesOn(final Card holder) {
        final Card greaves = addCard("Lightning Greaves", ai);
        greaves.attachToEntity(holder, null);
        game.getAction().checkStateEffects(true);
        return greaves;
    }

    private static List<String> withSeed(final List<String> lines, final long seed) {
        final List<String> out = new ArrayList<>();
        for (final String l : lines) {
            if (l.contains(" seed=" + seed + " ")) {
                out.add(l);
            }
        }
        return out;
    }

    private static List<String> startingWith(final List<String> lines, final String prefix) {
        final List<String> out = new ArrayList<>();
        for (final String l : lines) {
            if (l.startsWith(prefix)) {
                out.add(l);
            }
        }
        return out;
    }

    @Test
    public void affectedNamesSickHastedCardsOnly() {
        setUp();
        final Card hastedByGreaves = arrived(addToken("b_5_5_demon_flying", ai));
        greavesOn(hastedByGreaves);
        final Card printedHaste = arrived(addCard("Raging Goblin", ai));
        final Card oldWithGreaves = addCard("Hill Giant", ai);
        oldWithGreaves.setSickness(false);
        greavesOn(oldWithGreaves);
        final Card sickNoHaste = arrived(addCard("Hill Giant", ai));

        AssertJUnit.assertTrue(SicknessProbe.affected(hastedByGreaves));
        AssertJUnit.assertTrue(SicknessProbe.affected(printedHaste));
        AssertJUnit.assertFalse("not sick: both copiers give it false", SicknessProbe.affected(oldWithGreaves));
        AssertJUnit.assertFalse("sick and no haste: both copiers give it true", SicknessProbe.affected(sickNoHaste));
    }

    @Test
    public void lostHasteReadsTheCopy() {
        setUp();
        final Card first = arrived(addToken("b_5_5_demon_flying", ai));
        final Card second = arrived(addToken("b_5_5_demon_flying", ai));
        final Card greaves = greavesOn(first);

        final GameCopier copier = new GameCopier(game);
        final Game copy = copier.makeCopy();
        final Card cFirst = copier.find(first);
        AssertJUnit.assertFalse("the copy carried the Greaves, so the haste with it", SicknessProbe.lostHaste(first, cFirst));
        AssertJUnit.assertFalse(SicknessProbe.lostHaste(first, null));

        copier.find(greaves).attachToEntity(copier.find(second), null);
        copy.getAction().checkStateEffects(true);
        AssertJUnit.assertTrue("the copy no longer gives it the haste the original has", SicknessProbe.lostHaste(first, cFirst));
    }

    @Test
    public void oneLinePerGameAndCard() {
        setUp();
        final Card demon = arrived(addToken("b_5_5_demon_flying", ai));
        greavesOn(demon);
        final List<String> lines = new ArrayList<>();
        final Consumer<String> before = SicknessProbe.swapSink(lines::add);
        try {
            Scoped.under(new SimScope(77L), () -> {
                new GameCopier(game).makeCopy();
                new GameCopier(game).makeCopy();
                return null;
            });
            final List<String> mine = withSeed(lines, 77L);
            AssertJUnit.assertEquals(String.valueOf(mine), 1, startingWith(mine, "D* seed=77 ").size());
            final List<String> affected = startingWith(mine, "AFFECTED seed=77 ");
            AssertJUnit.assertEquals(String.valueOf(mine), 1, affected.size());
            AssertJUnit.assertTrue(affected.get(0), affected.get(0).contains(" card=" + demon.getId() + ":"));
            AssertJUnit.assertEquals("the copy carried the Greaves", 0, startingWith(mine, "LOST ").size());

            final Game plain = initAndCreateGame();
            plain.getPhaseHandler().devModeSet(PhaseType.MAIN1, plain.getPlayers().get(1));
            addCard("Hill Giant", plain.getPlayers().get(1)).setSickness(false);
            Scoped.under(new SimScope(78L), () -> new GameCopier(plain).makeCopy());
            AssertJUnit.assertEquals("no affected card, no line", 0, withSeed(lines, 78L).size());

            final int count = lines.size();
            new GameCopier(game).makeCopy(); // no scope bound: the GUI's case
            AssertJUnit.assertEquals("no scope, no line", count, lines.size());
        } finally {
            SicknessProbe.swapSink(before);
        }
    }

    @Test
    public void offByDefault() {
        setUp();
        final Card demon = arrived(addToken("b_5_5_demon_flying", ai));
        greavesOn(demon);
        final Consumer<String> before = SicknessProbe.swapSink(null);
        try {
            Scoped.under(new SimScope(79L), () -> new GameCopier(game).makeCopy());
            // off means no side effect at all: the game's once-a-game line was not spent while the probe was off
            final List<String> lines = new ArrayList<>();
            SicknessProbe.swapSink(lines::add);
            Scoped.under(new SimScope(79L), () -> new GameCopier(game).makeCopy());
            AssertJUnit.assertEquals(String.valueOf(lines), 1, startingWith(lines, "D* seed=79 ").size());
        } finally {
            SicknessProbe.swapSink(before);
        }
    }
}
