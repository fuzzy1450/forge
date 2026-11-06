package forge.ai.simulation;

import static org.testng.AssertJUnit.assertEquals;

import org.testng.annotations.Test;

/**
 * The smart mulligan (OpeningHand) runs in every game of the sim harness, and a seed must play one game on any machine:
 * on x86 and on the aarch64 workers alike. Math.exp and Math.log are specified to one ulp and take a different intrinsic
 * on each, and a quarter of the hypergeometric odds below differ in their last bits between Math and StrictMath on the
 * desktop, odds that are compared with a keep bar and with each other. So the odds are fdlibm's, the same bits everywhere,
 * pinned here as bits. On the first six inputs this desktop's Math gives other bits; on the fifth and sixth, with either
 * term of logChoose or the exp alone (Math.log differs from StrictMath at 3, 48 and 74 of the integers below 100).
 */
public class OpeningHandStrictMathTest {
    private static void assertBits(final long expected, final int k, final int draws, final int size, final int good) {
        final double odds = OpeningHand.atLeast(k, draws, size, good);
        assertEquals("atLeast(" + k + "," + draws + "," + size + "," + good + ") = " + odds + " 0x"
                + Long.toHexString(Double.doubleToLongBits(odds)), expected, Double.doubleToLongBits(odds));
    }

    @Test
    public void theOddsAreTheSameBitsOnEveryMachine() {
        assertBits(0x3fc3333333333320L, 1, 3, 20, 1);
        assertBits(0x3fcfffffffffffb4L, 1, 5, 20, 1);
        assertBits(0x3fb435e50d7943b0L, 2, 6, 20, 2);
        assertBits(0x3fd9d31674c59d28L, 1, 3, 20, 3);
        assertBits(0x3fdd742ca1c893c4L, 2, 7, 99, 21);
        assertBits(0x3fe5f7a0c62c6ba1L, 1, 5, 60, 12);
        // the kind of input a Commander hand asks: 37 lands in 99 cards, two in seven draws
        assertBits(0x3fea0dbcdac31484L, 2, 7, 99, 37);
        assertBits(0x3fea357eee7b4743L, 3, 10, 99, 38);
        assertBits(0x3fea439361a1e437L, 1, 3, 40, 17);
        assertBits(0x3fd9b2dd4e02974eL, 4, 8, 60, 24);
    }

    @Test
    public void theEdgesAreExact() {
        assertBits(Double.doubleToLongBits(1.0), 0, 7, 99, 37);
        assertBits(Double.doubleToLongBits(0.0), 3, 2, 99, 37);
        assertBits(Double.doubleToLongBits(0.0), 3, 7, 99, 2);
    }
}
