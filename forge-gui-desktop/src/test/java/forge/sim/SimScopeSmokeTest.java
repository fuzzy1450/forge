package forge.sim;

import java.util.List;

import org.testng.Assert;
import org.testng.annotations.AfterClass;
import org.testng.annotations.Test;

import forge.util.SimScope;

/** The spec's 7.6 smoke subset: two pairings at two seeds through all three arms. Needs the
 *  display and a few minutes. */
public class SimScopeSmokeTest {

    @AfterClass
    public void relaxStrict() {
        SimScope.setStrict(false);
    }

    @Test(timeOut = 1_800_000)
    public void threeArmsAgreeOnTheSmokeList() {
        List<DeterminismBattery.Entry> list = DeterminismBattery.smokeList();
        Assert.assertEquals(list.size(), 4);
        DeterminismBattery.Report r = DeterminismBattery.run(list, List.of("A", "B", "C"), 6, null);
        Assert.assertEquals(r.mismatches(), List.of(), "digests differ across arms");
        Assert.assertEquals(r.problems(), List.of(), "a game ended Error, or timed out in a concurrent arm, or strict mode fired");
        Assert.assertEquals(r.untestable(), List.of(), "a game timed out in the solo arm");
        Assert.assertFalse(r.poisoned());
        Assert.assertEquals(r.played().size(), 12);
        Assert.assertTrue(r.passed());
    }
}
