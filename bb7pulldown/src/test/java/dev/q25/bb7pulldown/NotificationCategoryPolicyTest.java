package dev.q25.bb7pulldown;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import org.junit.Test;

public class NotificationCategoryPolicyTest {
    private static NotificationItem item(String key, String pkg, boolean silent) {
        return new NotificationItem(key, pkg, key, "body", 1, true,
                Collections.emptyList(), null, false, silent);
    }

    @Test public void silentAppearsExactlyOnceAndLast() {
        assertEquals(Arrays.asList("a", "b", NotificationCategoryPolicy.SILENT_ID),
                NotificationCategoryPolicy.categoriesFor(Arrays.asList(
                        item("1", "a", false), item("2", "a", true),
                        item("3", "b", true), item("4", "b", false),
                        item("5", "a", false))));
    }

    @Test public void silentOnlyDoesNotCreateAppIcons() {
        assertEquals(Collections.singletonList(NotificationCategoryPolicy.SILENT_ID),
                NotificationCategoryPolicy.categoriesFor(Arrays.asList(
                        item("1", "a", true), item("2", "b", true))));
    }

    @Test public void noSilentMeansNoSilentCategory() {
        assertEquals(Collections.singletonList("a"),
                NotificationCategoryPolicy.categoriesFor(Collections.singletonList(
                        item("1", "a", false))));
        assertTrue(NotificationCategoryPolicy.categoriesFor(Collections.emptyList()).isEmpty());
        assertFalse(NotificationCategoryPolicy.isSilent("a"));
        assertTrue(NotificationCategoryPolicy.isSilent(NotificationCategoryPolicy.SILENT_ID));
    }
}
