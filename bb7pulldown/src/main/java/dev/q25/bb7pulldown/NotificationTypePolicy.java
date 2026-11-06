package dev.q25.bb7pulldown;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Stable BB UI shade categories based on Android notification metadata.
 * Neither package names nor message text are used to guess classification.
 * Categories are presentation-only; the native notification is unchanged.
 */
final class NotificationTypePolicy {
    static final String MESSAGES = "@bbui:type:messages";
    static final String EMAIL = "@bbui:type:email";
    static final String CALLS = "@bbui:type:calls";
    static final String CALENDAR = "@bbui:type:calendar";
    static final String REMINDERS = "@bbui:type:reminders";
    static final String ALARMS = "@bbui:type:alarms";
    static final String OTHER = "@bbui:type:other";

    private static final List<String> FIXED = Collections.unmodifiableList(Arrays.asList(
            MESSAGES, EMAIL, CALLS, CALENDAR, REMINDERS, ALARMS, OTHER));

    private NotificationTypePolicy() {}

    static boolean isType(String category) {
        return FIXED.contains(category);
    }

    static List<String> categories(boolean favoritesEnabled) {
        if (!favoritesEnabled) return FIXED;
        ArrayList<String> result = new ArrayList<>(FIXED.size() + 1);
        result.add(NotificationCategoryPolicy.FAVORITES_ID);
        result.addAll(FIXED);
        return Collections.unmodifiableList(result);
    }

    static String label(String category) {
        if (MESSAGES.equals(category)) return "Messages";
        if (EMAIL.equals(category)) return "Email";
        if (CALLS.equals(category)) return "Calls";
        if (CALENDAR.equals(category)) return "Calendar";
        if (REMINDERS.equals(category)) return "Reminders";
        if (ALARMS.equals(category)) return "Alarms";
        return "Other";
    }

    static String typeOf(NotificationItem item) {
        if (item == null) return OTHER;
        switch (item.nativeCategory) {
            case "msg": return MESSAGES; // Notification.CATEGORY_MESSAGE
            case "email": return EMAIL;
            case "call":
            case "missed_call":
            case "voicemail": return CALLS;
            case "event": return CALENDAR;
            case "reminder": return REMINDERS;
            case "alarm": return ALARMS;
            default:
                // Standard Notification.CallStyle / MessagingStyle metadata.
                if (item.notificationStyle.endsWith("$CallStyle")
                        || item.notificationStyle.endsWith(".CallStyle")) return CALLS;
                if (item.notificationStyle.endsWith("$MessagingStyle")
                        || item.notificationStyle.endsWith(".MessagingStyle")
                        || !item.conversationIds.isEmpty()) return MESSAGES;
                return OTHER;
        }
    }

    static List<NotificationItem> filter(List<NotificationItem> items, String category) {
        if (!isType(category) || items == null || items.isEmpty())
            return Collections.emptyList();
        List<NotificationItem> matches = new ArrayList<>();
        for (NotificationItem item : items) {
            if (item != null && category.equals(typeOf(item))) matches.add(item);
        }
        return NotificationSelectionPolicy.withoutRedundantGroupSummaries(matches);
    }
}
