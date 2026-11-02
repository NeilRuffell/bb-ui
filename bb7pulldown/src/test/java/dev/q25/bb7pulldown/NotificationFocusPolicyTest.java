package dev.q25.bb7pulldown;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import java.util.Arrays;
import java.util.Collections;
import org.junit.Test;

public class NotificationFocusPolicyTest {
    private NotificationItem item(String key) {
        return new NotificationItem(key, "pkg", "title", "preview", 1);
    }
    @Test public void initialFocusSelectsFirstCard() {
        assertEquals("a", NotificationFocusPolicy.retainedKey(
                Arrays.asList(item("a"), item("b")), null, -1));
    }
    @Test public void liveReorderPreservesKey() {
        assertEquals("b", NotificationFocusPolicy.retainedKey(
                Arrays.asList(item("c"), item("b"), item("a")), "b", 0));
    }
    @Test public void removalSelectsClosestRemainingCard() {
        assertEquals("c", NotificationFocusPolicy.retainedKey(
                Arrays.asList(item("a"), item("c")), "b", 1));
        assertEquals("a", NotificationFocusPolicy.retainedKey(
                Collections.singletonList(item("a")), "b", 4));
    }
    @Test public void noCardsClearsFocus() {
        assertNull(NotificationFocusPolicy.retainedKey(Collections.emptyList(), "a", 0));
        assertEquals(-1, NotificationFocusPolicy.indexOf(Collections.emptyList(), "a"));
    }
}
