package dev.q25.bb7pulldown;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.Test;

public class StatusBarCategoryCountsTest {
    private static NotificationItem icon(String key, String app, String type,
            String group, boolean summary) {
        return new NotificationItem(key, app, key, "", 1, true,
                Collections.emptyList(), group, summary, false,
                Collections.emptyList(), "", type, "", false);
    }

    @Test public void countsMultipleAppsAsOneType() {
        Map<String, Integer> counts = StatusBarCategoryCounts.count(Arrays.asList(
                icon("sms", "sms", "msg", null, false),
                icon("whatsapp", "wa", "msg", null, false),
                icon("mail", "gmail", "email", null, false)));
        assertEquals(Integer.valueOf(2), counts.get(NotificationTypePolicy.MESSAGES));
        assertEquals(Integer.valueOf(1), counts.get(NotificationTypePolicy.EMAIL));
        assertEquals(2, counts.size());
    }

    @Test public void countsNativeEligibilitySubsetOnly() {
        // The native icon binder supplies only included/eligible entries.
        List<NotificationItem> posted = Arrays.asList(
                icon("eligible", "chat", "msg", null, false),
                icon("filtered", "chat", "msg", null, false));
        assertEquals(1, StatusBarCategoryCounts.count(
                Collections.singletonList(posted.get(0))).get(
                NotificationTypePolicy.MESSAGES).intValue());
    }

    @Test public void groupedSummaryWithNoCategoryDoesNotLeakIntoOther() {
        Map<String, Integer> counts = StatusBarCategoryCounts.count(Arrays.asList(
                icon("summary", "chat", "", "g", true),
                icon("child1", "chat", "msg", "g", false),
                icon("child2", "chat", "msg", "g", false)));
        assertEquals(Integer.valueOf(2), counts.get(NotificationTypePolicy.MESSAGES));
        assertFalse(counts.containsKey(NotificationTypePolicy.OTHER));
    }

    @Test public void unclassifiedNotificationFallsBackToOther() {
        Map<String, Integer> counts = StatusBarCategoryCounts.count(
                Collections.singletonList(icon("notification", "app", "", null, false)));
        assertEquals(Integer.valueOf(1), counts.get(NotificationTypePolicy.OTHER));
    }

    @Test public void emptyGroupsRemainHiddenAndOrderIsStable() {
        Map<String, Integer> counts = StatusBarCategoryCounts.count(Arrays.asList(
                icon("call", "dialer", "missed_call", null, false),
                icon("email", "mail", "email", null, false),
                icon("msg", "chat", "msg", null, false)));
        assertEquals(Arrays.asList(NotificationTypePolicy.MESSAGES,
                NotificationTypePolicy.EMAIL, NotificationTypePolicy.CALLS),
                Arrays.asList(counts.keySet().toArray(new String[0])));
        assertFalse(counts.containsKey(NotificationTypePolicy.CALENDAR));
    }

    @Test public void removalAndNullInputDoNotLeaveStaleCounts() {
        assertTrue(StatusBarCategoryCounts.count(null).isEmpty());
        assertTrue(StatusBarCategoryCounts.count(Collections.emptyList()).isEmpty());
        assertEquals(1, StatusBarCategoryCounts.count(
                Collections.singletonList(icon("one", "chat", "msg", null, false)))
                .size());
    }

    @Test public void loneGroupSummaryCountsAsOne() {
        Map<String, Integer> counts = StatusBarCategoryCounts.count(
                Collections.singletonList(icon("summary", "app", "email", "g", true)));
        assertEquals(Integer.valueOf(1), counts.get(NotificationTypePolicy.EMAIL));
    }
}
