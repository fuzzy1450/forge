package forge.util;

/**
 * Thrown by {@link SimScope#requireUnboundAllowed} under strict mode: Forge code reached a
 * static that a simulated game must never reach unscoped -- a random draw, an id, a reset --
 * on a thread with no {@link SimScope} bound, which means a thread hop the simulation did
 * not adopt. An Error, not an Exception, for the same reason {@link GameAbandoned} is one:
 * Forge's game and AI code catches Exception in many places and would carry on with a
 * silently crippled game (AiController's eval thread rethrows only an Error, and reads any
 * other failure as "nothing to play"), while an Error propagates and ends the game as an
 * Error the determinism battery can see.
 */
public final class UnscopedAccessError extends Error {
    private static final long serialVersionUID = 1L;

    public UnscopedAccessError(String message) {
        super(message);
    }
}
