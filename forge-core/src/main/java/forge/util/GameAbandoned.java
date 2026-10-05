package forge.util;

/**
 * Thrown by a cancelled {@link SimScope}'s {@code random()} and {@code nextId()}: the game
 * this scope belongs to was abandoned (it timed out) and its thread must unwind. An Error,
 * not an Exception, so that a {@code catch (Exception)} in game code cannot swallow it.
 */
public final class GameAbandoned extends Error {
    private static final long serialVersionUID = 1L;

    public GameAbandoned() {
        super("game abandoned: its SimScope was cancelled");
    }
}
