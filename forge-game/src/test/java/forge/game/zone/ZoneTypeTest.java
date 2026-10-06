package forge.game.zone;

import java.util.List;

import org.testng.AssertJUnit;
import org.testng.annotations.Test;

/**
 * Pins what {@link ZoneType#listValueOf} answers for the shapes card scripts use, so that a change to how the
 * string is split (it was {@code values.split("[, ]+")}, a regex compiled on every call) cannot change a game.
 */
public class ZoneTypeTest {

    @Test
    public void singleZone() {
        AssertJUnit.assertEquals(List.of(ZoneType.Battlefield), ZoneType.listValueOf("Battlefield"));
    }

    @Test
    public void commaSeparated() {
        AssertJUnit.assertEquals(List.of(ZoneType.Graveyard, ZoneType.Library), ZoneType.listValueOf("Graveyard,Library"));
    }

    @Test
    public void commaAndSpaceSeparated() {
        AssertJUnit.assertEquals(List.of(ZoneType.Graveyard, ZoneType.Library, ZoneType.Exile),
                ZoneType.listValueOf("Graveyard, Library,  Exile"));
    }

    @Test
    public void spaceSeparated() {
        AssertJUnit.assertEquals(List.of(ZoneType.Hand, ZoneType.Library), ZoneType.listValueOf("Hand Library"));
    }

    @Test
    public void trailingSeparatorIsDropped() {
        AssertJUnit.assertEquals(List.of(ZoneType.Graveyard), ZoneType.listValueOf("Graveyard,"));
        AssertJUnit.assertEquals(List.of(ZoneType.Graveyard), ZoneType.listValueOf("Graveyard, "));
    }

    @Test(expectedExceptions = IllegalArgumentException.class)
    public void leadingSeparatorRejectsTheEmptyName() {
        // String.split keeps an empty leading substring before a positive-width match, and smartValueOf("") throws.
        ZoneType.listValueOf(",Graveyard");
    }

    @Test(expectedExceptions = IllegalArgumentException.class)
    public void unknownNameThrows() {
        ZoneType.listValueOf("Graveyard,Nowhere");
    }

    @Test
    public void namesAreCaseInsensitive() {
        AssertJUnit.assertEquals(List.of(ZoneType.Battlefield, ZoneType.Graveyard), ZoneType.listValueOf("battlefield,GRAVEYARD"));
    }

    @Test
    public void allExpandsToTheSevenZonesInOrder() {
        AssertJUnit.assertEquals(List.of(ZoneType.Battlefield, ZoneType.Hand, ZoneType.Graveyard, ZoneType.Exile,
                ZoneType.Stack, ZoneType.Library, ZoneType.Command), ZoneType.listValueOf("All"));
    }

    @Test
    public void resultIsAFreshMutableListForAnOrdinaryValue() {
        // Callers such as ChangeZoneAi build on the returned list; a fresh ArrayList is what they get today.
        List<ZoneType> a = ZoneType.listValueOf("Graveyard");
        List<ZoneType> b = ZoneType.listValueOf("Graveyard");
        AssertJUnit.assertNotSame(a, b);
        a.add(ZoneType.Exile);
        AssertJUnit.assertEquals(List.of(ZoneType.Graveyard, ZoneType.Exile), a);
    }

    @Test
    public void isHiddenFollowsTheListedZones() {
        AssertJUnit.assertTrue(ZoneType.isHidden("Graveyard,Library"));
        AssertJUnit.assertFalse(ZoneType.isHidden("Graveyard, Battlefield"));
        AssertJUnit.assertTrue(ZoneType.isHidden("Hand"));
    }
}
