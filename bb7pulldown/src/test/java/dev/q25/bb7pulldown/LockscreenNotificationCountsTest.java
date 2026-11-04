package dev.q25.bb7pulldown;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.Map;

import org.junit.Test;

public class LockscreenNotificationCountsTest {
    private static LockscreenNotificationCounts.Record record(
            String app, String group, boolean summary, boolean visible) {
        return new LockscreenNotificationCounts.Record(app, group, summary, visible);
    }

    @Test public void countsEachActiveNotificationPerApp() {
        Map<String, Integer> result = LockscreenNotificationCounts.count(Arrays.asList(
                record("sms.app", "threadA", false, true),
                record("email.app", null, false, true),
                record("sms.app", "threadB", false, true),
                record("sms.app", "threadC", false, true)));
        assertEquals(Integer.valueOf(3), result.get("sms.app"));
        assertEquals(Integer.valueOf(1), result.get("email.app"));
        assertEquals(Arrays.asList("sms.app", "email.app"),
                Arrays.asList(result.keySet().toArray(new String[0])));
    }

    @Test public void groupSummaryDoesNotDoubleCountExistingChildren() {
        Map<String, Integer> result = LockscreenNotificationCounts.count(Arrays.asList(
                record("email.app", "mail", true, true),
                record("email.app", "mail", false, true),
                record("email.app", "mail", false, true)));
        assertEquals(Integer.valueOf(2), result.get("email.app"));
    }

    @Test public void loneGroupSummaryIsStillCounted() {
        Map<String, Integer> result = LockscreenNotificationCounts.count(
                Collections.singletonList(record("email.app", "mail", true, true)));
        assertEquals(Integer.valueOf(1), result.get("email.app"));
    }

    @Test public void secretAndExcludedRecordsDoNotAppear() {
        Map<String, Integer> result = LockscreenNotificationCounts.count(Arrays.asList(
                record("private.app", "p", false, false),
                record("public.app", null, false, true)));
        assertFalse(result.containsKey("private.app"));
        assertEquals(Integer.valueOf(1), result.get("public.app"));
    }

    @Test public void emptyListHasNoIndicators() {
        assertTrue(LockscreenNotificationCounts.count(Collections.emptyList()).isEmpty());
    }
}
