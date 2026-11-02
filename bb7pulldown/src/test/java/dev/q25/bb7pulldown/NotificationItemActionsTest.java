package dev.q25.bb7pulldown;

import static org.junit.Assert.*;
import java.util.Arrays;
import java.util.Collections;
import org.junit.Test;

public class NotificationItemActionsTest {
    @Test public void immutableNativeActionDescriptorsAreRetained() {
        NotificationItem item = new NotificationItem("a", "pkg", "title", "text", 1,
                true, Arrays.asList(
                        new NotificationItem.ActionItem(0, "Reply", true),
                        new NotificationItem.ActionItem(2, "Mark read", false)));
        assertTrue(item.clearable);
        assertEquals(2, item.actions.size());
        assertEquals(0, item.actions.get(0).index);
        assertTrue(item.actions.get(0).freeFormReply);
        assertEquals(2, item.actions.get(1).index);
        assertEquals("Mark read", item.actions.get(1).title);
    }

    @Test public void equalityObservesChangesToButtonsAndClearability() {
        NotificationItem old = new NotificationItem("a", "pkg", "title", "text", 1,
                false, Collections.emptyList());
        NotificationItem withAction = new NotificationItem("a", "pkg", "title", "text", 1,
                false, Collections.singletonList(new NotificationItem.ActionItem(0, "Reply", true)));
        NotificationItem clearable = new NotificationItem("a", "pkg", "title", "text", 1,
                true, Collections.emptyList());
        assertNotEquals(old, withAction);
        assertNotEquals(old, clearable);
        assertEquals(withAction, new NotificationItem("a", "pkg", "title", "text", 1,
                false, Collections.singletonList(new NotificationItem.ActionItem(0, "Reply", true))));
    }

    @Test public void actionListDoesNotAllowExternalMutation() {
        NotificationItem item = new NotificationItem("a", "pkg", "", "", 0,
                true, Collections.singletonList(new NotificationItem.ActionItem(0, "Archive", false)));
        try {
            item.actions.clear();
            fail("list must be immutable");
        } catch (UnsupportedOperationException expected) { }
    }
}
