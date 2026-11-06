package dev.q25.bb7pulldown;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Test;

public class NotificationTypePolicyTest {
    private static NotificationItem item(String key, String pkg, String nativeType,
            String style, String group, boolean summary, boolean silent,
            List<String> conversationIds) {
        return new NotificationItem(key, pkg, key, "", 1L, true,
                Collections.emptyList(), group, summary, silent, conversationIds,
                "", nativeType, style, false);
    }

    private static NotificationItem item(String key, String pkg, String nativeType) {
        return item(key, pkg, nativeType, "", null, false, false,
                Collections.emptyList());
    }

    @Test public void everyCategoryIsPresentWithoutNotifications() {
        List<String> types = NotificationTypePolicy.categories(false);
        assertEquals(Arrays.asList(NotificationTypePolicy.MESSAGES,
                NotificationTypePolicy.EMAIL, NotificationTypePolicy.CALLS,
                NotificationTypePolicy.CALENDAR, NotificationTypePolicy.REMINDERS,
                NotificationTypePolicy.ALARMS, NotificationTypePolicy.OTHER), types);
        assertEquals(types, NotificationShadeModePolicy.categoriesFor(
                Collections.emptyList(), false, NotificationShadeModePolicy.TYPES));
    }

    @Test public void nativeTypesAreIndependentOfTheSendingApp() {
        List<NotificationItem> all = Arrays.asList(
                item("sms", "sms.app", "msg"),
                item("wa", "whatsapp.app", "msg"),
                item("email", "mail.app", "email"),
                item("call", "whatsapp.app", "call"),
                item("missed", "phone.app", "missed_call"),
                item("voicemail", "phone.app", "voicemail"),
                item("event", "calendar.app", "event"),
                item("reminder", "tasks.app", "reminder"),
                item("alarm", "clock.app", "alarm"));
        assertEquals(2, NotificationTypePolicy.filter(
                all, NotificationTypePolicy.MESSAGES).size());
        assertEquals(1, NotificationTypePolicy.filter(
                all, NotificationTypePolicy.EMAIL).size());
        assertEquals(3, NotificationTypePolicy.filter(
                all, NotificationTypePolicy.CALLS).size());
        assertEquals(1, NotificationTypePolicy.filter(
                all, NotificationTypePolicy.CALENDAR).size());
        assertEquals(1, NotificationTypePolicy.filter(
                all, NotificationTypePolicy.REMINDERS).size());
        assertEquals(1, NotificationTypePolicy.filter(
                all, NotificationTypePolicy.ALARMS).size());
    }

    @Test public void styleAndConversationMetadataAreFallbacks() {
        List<NotificationItem> all = Arrays.asList(
                item("style", "messenger", "", "android.app.Notification$MessagingStyle",
                        null, false, false, Collections.emptyList()),
                item("shortcut", "chat", "", "", null, false, false,
                        Collections.singletonList("conversation42")),
                item("call", "video", "", "android.app.Notification$CallStyle",
                        null, false, false, Collections.emptyList()),
                item("uncat", "weird.app", "", "", null, false, false,
                        Collections.emptyList()),
                item("progress", "download.app", "progress"));
        assertEquals(2, NotificationTypePolicy.filter(
                all, NotificationTypePolicy.MESSAGES).size());
        assertEquals(1, NotificationTypePolicy.filter(
                all, NotificationTypePolicy.CALLS).size());
        assertEquals(2, NotificationTypePolicy.filter(
                all, NotificationTypePolicy.OTHER).size());
    }

    @Test public void groupSummariesDoNotInflateCounts() {
        List<NotificationItem> all = Arrays.asList(
                item("sum", "app", "msg", "", "thread", true, false, Collections.emptyList()),
                item("one", "app", "msg", "", "thread", false, false, Collections.emptyList()),
                item("two", "app", "msg", "", "thread", false, true, Collections.emptyList()));
        FavoritesRules favorites = new FavoritesRules();
        assertEquals(2, NotificationCategoryCounts.countFor(
                all, favorites, NotificationTypePolicy.MESSAGES));
        assertEquals(2, NotificationShadeModePolicy.select(
                all, favorites, NotificationTypePolicy.MESSAGES).size());
        assertEquals(1, NotificationTypePolicy.filter(
                Collections.singletonList(all.get(0)), NotificationTypePolicy.MESSAGES).size());
    }

    @Test public void crossTypeSummaryDoesNotLeakIntoOther() {
        List<NotificationItem> all = Arrays.asList(
                item("summary", "chat.app", "", "", "group", true, false,
                        Collections.emptyList()),
                item("child", "chat.app", "msg", "", "group", false, false,
                        Collections.emptyList()));
        assertEquals(0, NotificationTypePolicy.filter(
                all, NotificationTypePolicy.OTHER).size());
        assertEquals(1, NotificationTypePolicy.filter(
                all, NotificationTypePolicy.MESSAGES).size());
    }

    @Test public void silentNotificationsRetainTheirContentType() {
        NotificationItem silentMessage = item("s", "chat.app", "msg", "",
                null, false, true, Collections.emptyList());
        assertEquals(1, NotificationTypePolicy.filter(Collections.singletonList(silentMessage),
                NotificationTypePolicy.MESSAGES).size());
        assertEquals(0, NotificationTypePolicy.filter(Collections.singletonList(silentMessage),
                NotificationTypePolicy.OTHER).size());
    }

    @Test public void favoritesCategoryIsOptionalAndSharedBetweenModes() {
        FavoritesRules fav = new FavoritesRules();
        fav.enabled = true;
        FavoritesRules.AppRule app = new FavoritesRules.AppRule("chat.app");
        fav.apps.add(app);
        List<NotificationItem> items = Arrays.asList(
                item("sms", "chat.app", "msg"), item("mail", "mail.app", "email"));
        assertEquals(NotificationCategoryPolicy.FAVORITES_ID,
                NotificationTypePolicy.categories(true).get(0));
        assertEquals(1, NotificationShadeModePolicy.select(
                items, fav, NotificationCategoryPolicy.FAVORITES_ID).size());
        assertEquals(1, NotificationShadeModePolicy.select(
                items, fav, NotificationTypePolicy.MESSAGES).size());
        assertFalse(NotificationTypePolicy.categories(false).contains(
                NotificationCategoryPolicy.FAVORITES_ID));
    }

    @Test public void switchingModesKeepsExistingAppFiltering() {
        List<NotificationItem> items = Arrays.asList(
                item("one", "app", "msg"), item("two", "app", "email"));
        assertEquals(Collections.singletonList("app"),
                NotificationShadeModePolicy.categoriesFor(items, false,
                        NotificationShadeModePolicy.APPS));
        assertEquals(2, NotificationShadeModePolicy.select(
                items, new FavoritesRules(), "app").size());
        assertEquals(1, NotificationShadeModePolicy.select(
                items, new FavoritesRules(), NotificationTypePolicy.EMAIL).size());
        assertEquals(NotificationShadeModePolicy.APPS, NotificationShadeModePolicy.normalized(null));
        assertEquals(NotificationShadeModePolicy.APPS,
                NotificationShadeModePolicy.normalized("unknown"));
        assertEquals(NotificationShadeModePolicy.TYPES,
                NotificationShadeModePolicy.normalized(NotificationShadeModePolicy.TYPES));
    }

    @Test public void metadataIsPartOfNotificationEquality() {
        NotificationItem a = item("one", "app", "email");
        NotificationItem b = item("one", "app", "msg");
        assertFalse(a.equals(b));
        assertTrue(a.equals(item("one", "app", "email")));
    }
}
