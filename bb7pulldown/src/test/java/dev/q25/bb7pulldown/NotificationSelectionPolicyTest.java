package dev.q25.bb7pulldown;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Test;

public class NotificationSelectionPolicyTest {
    private final NotificationItem a1 = new NotificationItem("1", "a", "First", "hello", 1);
    private final NotificationItem a2 = new NotificationItem("2", "a", "Second", "world", 2);
    private final NotificationItem b1 = new NotificationItem("3", "b", "Third", "other", 3);

    @Test public void filtersOnlySelectedAppInOriginalOrder() {
        List<NotificationItem> all = Arrays.asList(a1, b1, a2);
        assertEquals(Arrays.asList(a1, a2), NotificationSelectionPolicy.forPackage(all, "a"));
        assertEquals(Collections.singletonList(b1),
                NotificationSelectionPolicy.forPackage(all, "b"));
        assertEquals(Arrays.asList(a1, b1, a2), all);
    }

    @Test public void nullOrAbsentAppShowsNoCards() {
        assertTrue(NotificationSelectionPolicy.forPackage(Arrays.asList(a1, b1), null).isEmpty());
        assertTrue(NotificationSelectionPolicy.forPackage(Arrays.asList(a1, b1), "c").isEmpty());
        assertTrue(NotificationSelectionPolicy.forPackage(Collections.emptyList(), "a").isEmpty());
    }

    @Test public void contentUpdatesAreDetectableWithoutNewPackage() {
        NotificationItem changed = new NotificationItem("1", "a", "First", "updated", 1);
        assertFalse(a1.equals(changed));
        assertEquals(a1, new NotificationItem("1", "a", "First", "hello", 1));
    }
}
