package forge.ai.simulation;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import forge.game.Game;
import forge.game.player.Player;
import forge.game.zone.PlayerZone;
import forge.game.zone.ZoneType;

/**
 * What a rollout must leave as it found it in the real game it copies (MTG_DeckMaker's tools/combo-ai/flat-mc/SPEC.md,
 * section 4.6): the timestamp, the next card id, each registered player's zone sizes over {@link Player#ALL_ZONES}
 * and floating mana, the stack's size, the stack zone's size, the phase and the turn. {@link FlatMcGame#bracket} takes
 * one before every rollout and checks the real game against it on every return, so a rollout that wrote one of these
 * ends the game as an Error naming it, instead of the game playing on from a state the search changed.
 *
 * <p>It reads getters only: a few hundred calls, against the copy of the whole game that every rollout makes.
 */
public final class RealGameSnapshot {
    /** Each field's name, in the order the check compares them. */
    private final List<String> fields = new ArrayList<>();
    /** Each field's value, in the same order. */
    private final List<Object> values = new ArrayList<>();

    private RealGameSnapshot(final Game real) {
        read("timestamp", real.getTimestamp());
        read("next card id", real.getCardIdCounter());
        // the zones and the mana come per player, so the count of players comes first: a different count is named
        // before the fields after it could fall out of step
        read("registered players", real.getRegisteredPlayers().size());
        for (final Player p : real.getRegisteredPlayers()) {
            for (final ZoneType z : Player.ALL_ZONES) {
                final PlayerZone zone = p.getZone(z);
                read(p + "'s " + z.name() + " zone", zone == null ? 0 : zone.size());
            }
            read(p + "'s floating mana", p.getManaPool().totalMana());
        }
        read("stack", real.getStack().size());
        read("stack zone", real.getStackZone().size());
        read("phase", real.getPhaseHandler().getPhase());
        read("turn", real.getPhaseHandler().getTurn());
    }

    /** {@code real}'s fields as they stand. */
    public static RealGameSnapshot of(final Game real) {
        return new RealGameSnapshot(real);
    }

    /**
     * Reads {@code real}'s fields again and throws {@link RolloutWroteRealGame} at the first, in this snapshot's
     * order, whose value has changed: {@code a rollout wrote the real game: <field> <before> -> <after>}.
     */
    public void requireSame(final Game real) {
        final RealGameSnapshot now = new RealGameSnapshot(real);
        for (int i = 0; i < values.size(); i++) {
            if (!Objects.equals(values.get(i), now.values.get(i))) {
                throw new RolloutWroteRealGame("a rollout wrote the real game: " + fields.get(i) + " " + values.get(i)
                        + " -> " + now.values.get(i));
            }
        }
    }

    private void read(final String field, final Object value) {
        fields.add(field);
        values.add(value);
    }
}
