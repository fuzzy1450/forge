package forge.ai.simulation;

import static org.testng.Assert.assertEquals;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import org.testng.annotations.Test;

import forge.StaticData;
import forge.ai.AITest;
import forge.card.CardStateName;
import forge.game.Game;
import forge.game.card.Card;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import forge.item.PaperCard;

/**
 * DelayedLoss.kind, the recognizer of the eleven delayed-loss cards, against every unique card the real card factory
 * builds (MTG_DeckMaker's self-kill fix, DESIGN.md section 4, finding I1). recognizer_scan.py mirrors the recognizer in
 * text; this asks the Java recognizer itself. Every unique card is built, and kind() runs over every spell ability of
 * every state the card has (each face), the card turned to that state first, as it is when that face is cast (a spell
 * ability's host reads its SVars from the card's current state). The (card, face) pairs kind() flags must be exactly
 * the eleven cards' faces, and Alchemist's Gambit's cleave cast must read null.
 * <p>
 * It builds some thirty thousand cards, so it runs only on request, as StackDescriptionSweepTest does:
 * {@code mvn test -Dtest=DelayedLossSweepTest -Dforge.sweep=true}. It writes target/delayed-loss-sweep.txt: the counts,
 * the flagged faces, and every card or face the test game could not build or ask.
 */
public class DelayedLossSweepTest extends AITest {
    private static final int BATCH = 400;

    /** The eleven cards' faces, as "card | face | kind" (DESIGN.md 1.2). */
    private static final Set<String> ELEVEN = new TreeSet<>(List.of(
            "Pact of Negation | Original | PACT",
            "Summoner's Pact | Original | PACT",
            "Slaughter Pact | Original | PACT",
            "Intervention Pact | Original | PACT",
            "Pact of the Titan | Original | PACT",
            "Final Fortune | Original | EXTRA_TURN",
            "Last Chance | Original | EXTRA_TURN",
            "Warrior's Oath | Original | EXTRA_TURN",
            "Chance for Glory | Original | EXTRA_TURN",
            "Alchemist's Gambit | Original | EXTRA_TURN",
            "Glorious End | Original | END_STEP"));

    @Test
    public void everyFlaggedFaceIsOneOfTheEleven() throws Exception {
        if (!Boolean.getBoolean("forge.sweep")) {
            System.out.println("DelayedLossSweepTest skipped (-Dforge.sweep=true to run it)");
            return;
        }
        final List<String> names = new ArrayList<>();
        for (final PaperCard pc : StaticData.instance().getCommonCards().getUniqueCards()) {
            names.add(pc.getName());
        }
        final Set<String> flagged = new TreeSet<>();
        final List<String> gambit = new ArrayList<>();
        final List<String> skipped = new ArrayList<>();
        int built = 0;
        int faces = 0;
        int abilities = 0;
        Player ai = null;
        for (int i = 0; i < names.size(); i++) {
            if (i % BATCH == 0) {
                final Game game = initAndCreateGame();
                ai = game.getPlayers().get(1);
            }
            final String name = names.get(i);
            final Card card;
            try {
                card = addCardToZone(name, ai, ZoneType.Hand);
            } catch (final RuntimeException e) {
                skipped.add(name + " | not built | " + e);
                continue;
            }
            built++;
            final CardStateName current = card.getCurrentStateName();
            for (final CardStateName face : new ArrayList<>(card.getStates())) {
                faces++;
                try {
                    card.setState(face, false);
                    for (final SpellAbility sa : card.getState(face).getSpellAbilities()) {
                        abilities++;
                        sa.setActivatingPlayer(ai);
                        final DelayedLoss.Kind kind = DelayedLoss.kind(sa);
                        if (kind != null) {
                            flagged.add(name + " | " + face + " | " + kind);
                        }
                        if ("Alchemist's Gambit".equals(name) && sa.hasParam("ExtraTurnDelayedTriggerExecute")) {
                            gambit.add(sa.getParam("ExtraTurnDelayedTriggerExecute") + " | " + kind);
                        }
                    }
                } catch (final RuntimeException e) {
                    skipped.add(name + " | " + face + " | " + e);
                } finally {
                    card.setState(current, false);
                }
            }
        }
        try (PrintWriter w = new PrintWriter("target/delayed-loss-sweep.txt", "UTF-8")) {
            w.println("unique cards " + names.size() + ", built " + built + ", faces " + faces + ", spell abilities "
                    + abilities);
            w.println("flagged faces (" + flagged.size() + "):");
            for (final String f : flagged) {
                w.println("  " + f);
            }
            w.println("Alchemist's Gambit's casts (ExtraTurnDelayedTriggerExecute | kind): " + gambit);
            w.println("not built or not asked (" + skipped.size() + "):");
            for (final String s : skipped) {
                w.println("  " + s);
            }
        }
        System.out.println("SWEEP delayed-loss: unique cards " + names.size() + ", built " + built + ", faces " + faces
                + ", spell abilities " + abilities + ", not built or not asked " + skipped.size());
        System.out.println("SWEEP delayed-loss flagged: " + flagged);
        System.out.println("SWEEP delayed-loss Alchemist's Gambit: " + gambit);
        assertEquals(flagged, ELEVEN, "the faces kind() flags among every unique card's");
        assertEquals(new TreeSet<>(gambit), new TreeSet<>(List.of("TrigEffect | EXTRA_TURN", "TrigEffect2 | null")),
                "Alchemist's Gambit: its normal cast an extra turn that loses, its cleave cast nothing");
    }
}
