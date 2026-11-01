package dev.q25.bb7pulldown;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import org.junit.Test;

public class NotificationAppIndexTest {
    @Test public void keepsOnlyOneIconPerAppInNativeOrder() {
        assertEquals(Arrays.asList("a", "b", "c"),
                NotificationAppIndex.uniquePackages(
                        Arrays.asList("a", "b", "a", "c", "b")));
    }

    @Test public void skipsEmptyPackages() {
        assertEquals(Collections.singletonList("a"),
                NotificationAppIndex.uniquePackages(Arrays.asList(null, "", "a", "")));
    }

    @Test public void noNotificationsMeansEmptyColumn() {
        assertTrue(NotificationAppIndex.uniquePackages(null).isEmpty());
        assertTrue(NotificationAppIndex.uniquePackages(Collections.emptyList()).isEmpty());
    }
}
