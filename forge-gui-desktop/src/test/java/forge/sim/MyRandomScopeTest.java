package forge.sim;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.testng.Assert;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.Test;

import forge.util.MyRandom;
import forge.util.SimScope;

public class MyRandomScopeTest {

    @AfterMethod
    public void unbindAndRelax() {
        if (SimScope.current() != null) {
            SimScope.exit();
        }
        SimScope.setStrict(false);
    }

    private static List<Integer> next(int n) {
        List<Integer> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            out.add(MyRandom.getRandom().nextInt(1000));
        }
        return out;
    }

    @Test
    public void boundScopeAnswersGetRandom() {
        List<Integer> expected = new ArrayList<>();
        Random ref = new Random(1234L);
        for (int i = 0; i < 10; i++) {
            expected.add(ref.nextInt(1000));
        }
        SimScope.enter(new SimScope(1234L));
        Assert.assertEquals(next(10), expected);
    }

    @Test
    public void setRandomWhileBoundReseedsTheScopeNotTheStatic() {
        Random before = MyRandom.getRandom();        // unbound, non-strict: the static
        try {
            Random marker = new Random(99L);
            MyRandom.setRandom(marker);
            Assert.assertSame(MyRandom.getRandom(), marker);

            SimScope s = new SimScope(1L);
            SimScope.enter(s);
            Random reseed = new Random(5L);
            MyRandom.setRandom(reseed);
            Assert.assertSame(MyRandom.getRandom(), reseed, "bound: setRandom replaced the scope's stream");
            Assert.assertSame(s.random(), reseed);
            SimScope.exit();
            Assert.assertSame(MyRandom.getRandom(), marker, "the static was never touched while bound");
        } finally {
            MyRandom.setRandom(before);
        }
    }

    @Test
    public void percentTrueAndGroupsDrawFromTheScope() {
        Random ref = new Random(77L);
        boolean expectedPercent = 50 > ref.nextInt(100);
        int[] expectedGroups = new int[3];
        for (int i = 0; i < 12; i++) {
            expectedGroups[ref.nextInt(3)]++;
        }
        SimScope.enter(new SimScope(77L));
        Assert.assertEquals(MyRandom.percentTrue(50), expectedPercent);
        Assert.assertEquals(MyRandom.splitIntoRandomGroups(12, 3), expectedGroups);
    }

    @Test
    public void strictModeRefusesUnboundUse() {
        SimScope.setStrict(true);
        Assert.assertThrows(IllegalStateException.class, MyRandom::getRandom);
        Assert.assertThrows(IllegalStateException.class, () -> MyRandom.setRandom(new Random(1L)));
        Assert.assertThrows(IllegalStateException.class, () -> MyRandom.percentTrue(50));
        Assert.assertThrows(IllegalStateException.class, () -> MyRandom.splitIntoRandomGroups(3, 2));
    }
}
