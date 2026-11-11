package forge.ai.simulation;

/**
 * A rollout wrote the real game it was copied from (MTG_DeckMaker's tools/combo-ai/flat-mc/SPEC.md, sections 4.5 and
 * 4.6): {@link RealGameSnapshot#requireSame} found one of the fields it reads changed across the rollout, and its
 * message names the first. An Error, so that no {@code catch (RuntimeException)} on its way up reads it as a failed
 * line: the game ends as an Error, loud and counted.
 */
public final class RolloutWroteRealGame extends Error {
    private static final long serialVersionUID = 1L;

    RolloutWroteRealGame(final String message) {
        super(message);
    }
}
