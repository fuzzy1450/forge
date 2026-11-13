package forge.ai.simulation;

/**
 * The seat word of a Flat Monte Carlo seat in a game the sim runner plays. {@code default}, the word every farm and
 * local seat names (JobFile reads a blank word as it), is a full seat: plain Flat MC, the search and every behaviour
 * {@code AiController.flatMonteCarloActive()} gates, at its profile's settings, with the default caps below and its
 * game's tally (MTG_DeckMaker's tools/combo-ai/flat-mc/SHIP.md, design 2). The flat_mc test's words keep their
 * search-only meaning (its SPEC.md, section 4.1): {@code flat_mc:<rollouts per game>:<resolutions per rollout>}, plain
 * {@code flat_mc} for {@code flat_mc:4000:500}, and {@code flat_mc_zero}, the search path with the rollouts taken out,
 * which takes no caps. Set on a seat's AiController, a seat that is not full is also that seat's search-only mark.
 *
 * @param rolloutsPerGame       the most rollouts the seat makes in one game
 * @param resolutionsPerRollout the most stack resolutions one rollout makes
 * @param zeroRollout           the search path with no rollouts
 * @param full                  plain Flat MC, the default word's seat; false for a flat_mc test word's search-only seat
 */
public record FlatMcSeat(int rolloutsPerGame, int resolutionsPerRollout, boolean zeroRollout, boolean full) {

    public static final int DEFAULT_ROLLOUTS_PER_GAME = 4000;
    public static final int DEFAULT_RESOLUTIONS_PER_ROLLOUT = 500;
    /** Every AI word a seat may name, as the refusal of an unknown one lists them. */
    public static final String AI_MODES =
            "(default|hybrid_sim|full_sim|flat_mc[:<rollouts per game>:<resolutions per rollout>]|flat_mc_zero)";

    private static final String FULL = "default";
    private static final String WORD = "flat_mc";
    private static final String ZERO = WORD + "_zero";
    private static final String CAPPED = WORD + ":";

    /**
     * The seat a word names: the full seat, with the default caps, for {@code default}; null for any other word that
     * does not begin {@code flat_mc}, which is no Flat MC seat ({@code hybrid_sim} and {@code full_sim} do their own
     * lookahead); a search-only seat for {@code flat_mc}, {@code flat_mc_zero} and {@code flat_mc:R:S}, R and S written
     * in decimal digits alone and each 1 or more. Any other word that begins {@code flat_mc} is refused, so a malformed
     * word is never played as some other seat.
     *
     * @throws IllegalArgumentException {@code "unknown ai mode " + word + " " + AI_MODES}
     */
    public static FlatMcSeat parse(final String word) {
        if (word.equals(FULL)) {
            return new FlatMcSeat(DEFAULT_ROLLOUTS_PER_GAME, DEFAULT_RESOLUTIONS_PER_ROLLOUT, false, true);
        }
        if (!word.startsWith(WORD)) {
            return null;
        }
        if (word.equals(WORD)) {
            return new FlatMcSeat(DEFAULT_ROLLOUTS_PER_GAME, DEFAULT_RESOLUTIONS_PER_ROLLOUT, false, false);
        }
        if (word.equals(ZERO)) {
            return new FlatMcSeat(DEFAULT_ROLLOUTS_PER_GAME, DEFAULT_RESOLUTIONS_PER_ROLLOUT, true, false);
        }
        if (word.startsWith(CAPPED)) {
            final String[] caps = word.substring(CAPPED.length()).split(":", -1);
            if (caps.length == 2) {
                final int rollouts = count(caps[0]);
                final int resolutions = count(caps[1]);
                if (rollouts > 0 && resolutions > 0) {
                    return new FlatMcSeat(rollouts, resolutions, false, false);
                }
            }
        }
        throw new IllegalArgumentException("unknown ai mode " + word + " " + AI_MODES);
    }

    /** The count written in {@code digits}, or 0 when it is not decimal digits alone (Integer.parseInt would also take a
     *  sign), is empty or does not fit an int. */
    private static int count(final String digits) {
        if (!digits.chars().allMatch(c -> c >= '0' && c <= '9')) {
            return 0;
        }
        try {
            return Integer.parseInt(digits);
        } catch (final NumberFormatException emptyOrTooLarge) {
            return 0;
        }
    }
}
