package dev.q25.bb7pulldown;

import static org.junit.Assert.assertEquals;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import org.junit.Test;

public class NotificationCategoryCountsTest {
    private static NotificationItem item(String key, String pkg, String title,
            boolean silent, String group, boolean summary) {
        return new NotificationItem(key, pkg, title, "", 1L, true,
                Collections.emptyList(), group, summary, silent);
    }

    @Test public void countsMatchEachAppPaneInsteadOfAllPostedNotifications() {
        List<NotificationItem> items = Arrays.asList(
                item("a1", "mail.app", "One", false, null, false),
                item("b1", "chat.app", "Message", false, null, false),
                item("a2", "mail.app", "Two", false, null, false),
                item("s1", "mail.app", "Background", true, null, false));
        FavoritesRules favorites = new FavoritesRules();
        assertEquals(2, NotificationCategoryCounts.countFor(items, favorites, "mail.app"));
        assertEquals(1, NotificationCategoryCounts.countFor(items, favorites, "chat.app"));
        assertEquals(1, NotificationCategoryCounts.countFor(
                items, favorites, NotificationCategoryPolicy.SILENT_ID));
    }

    @Test public void groupSummaryDoesNotIncreaseVisibleCount() {
        List<NotificationItem> items = Arrays.asList(
                item("summary", "mail.app", "Summary", false, "thread", true),
                item("child1", "mail.app", "First", false, "thread", false),
                item("child2", "mail.app", "Second", false, "thread", false));
        assertEquals(2, NotificationCategoryCounts.countFor(
                items, new FavoritesRules(), "mail.app"));
        assertEquals(2, NotificationSelectionPolicy.forPackage(items, "mail.app").size());
    }

    @Test public void loneSummaryStillCountsAsOne() {
        List<NotificationItem> items = Collections.singletonList(
                item("summary", "mail.app", "Summary", false, "thread", true));
        assertEquals(1, NotificationCategoryCounts.countFor(
                items, new FavoritesRules(), "mail.app"));
    }

    @Test public void silentCountUsesOnlyVisibleSilentCards() {
        NotificationItem media = item("media", "player.app", "Playing", true, null, false);
        NotificationItem silentSummary =
                item("summary", "mail.app", "Summary", true, "g", true);
        NotificationItem silentChild =
                item("child", "mail.app", "Child", true, "g", false);
        NotificationItem alert =
                item("alert", "mail.app", "Alert", false, null, false);
        List<NotificationItem> visible = NotificationSelectionPolicy.withoutRepresentedSilentMedia(
                Arrays.asList(media, silentSummary, silentChild, alert),
                new HashSet<>(Collections.singletonList("media")));
        assertEquals(1, NotificationCategoryCounts.countFor(
                visible, new FavoritesRules(), NotificationCategoryPolicy.SILENT_ID));
        assertEquals(1, NotificationCategoryCounts.countFor(
                visible, new FavoritesRules(), "mail.app"));
    }

    @Test public void favoritesCountMatchesConfiguredFilters() {
        FavoritesRules favorites = new FavoritesRules();
        favorites.enabled = true;
        FavoritesRules.AppRule mail = new FavoritesRules.AppRule("mail.app");
        mail.onlyMatching = true;
        mail.rules.add(new FavoritesRules.Rule(
                FavoritesRules.TYPE_TITLE_CONTAINS, "urgent"));
        favorites.apps.add(mail);
        List<NotificationItem> items = Arrays.asList(
                item("mail1", "mail.app", "Urgent request", false, null, false),
                item("mail2", "mail.app", "Newsletter", false, null, false),
                item("chat1", "chat.app", "Urgent message", false, null, false));
        assertEquals(1, NotificationCategoryCounts.countFor(
                items, favorites, NotificationCategoryPolicy.FAVORITES_ID));
        assertEquals(favorites.filter(items).size(), NotificationCategoryCounts.countFor(
                items, favorites, NotificationCategoryPolicy.FAVORITES_ID));
        assertEquals(2, NotificationCategoryCounts.countFor(items, favorites, "mail.app"));
    }

    @Test public void countChangesWithoutChangingThePackageList() {
        NotificationItem first = item("one", "mail.app", "One", false, null, false);
        NotificationItem second = item("two", "mail.app", "Two", false, null, false);
        FavoritesRules favorites = new FavoritesRules();
        List<NotificationItem> before = Collections.singletonList(first);
        List<NotificationItem> after = Arrays.asList(first, second);
        assertEquals(NotificationCategoryPolicy.categoriesFor(before),
                NotificationCategoryPolicy.categoriesFor(after));
        assertEquals(1, NotificationCategoryCounts.countFor(before, favorites, "mail.app"));
        assertEquals(2, NotificationCategoryCounts.countFor(after, favorites, "mail.app"));
        assertEquals(1, NotificationCategoryCounts.countFor(
                Collections.singletonList(second), favorites, "mail.app"));
    }

    @Test public void emptyFavoritesAndMissingCategoriesShowZero() {
        FavoritesRules favorites = new FavoritesRules();
        favorites.enabled = true;
        assertEquals(0, NotificationCategoryCounts.countFor(
                Collections.emptyList(), favorites, NotificationCategoryPolicy.FAVORITES_ID));
        assertEquals(0, NotificationCategoryCounts.countFor(
                Collections.emptyList(), favorites, "missing.app"));
        assertEquals(0, NotificationCategoryCounts.countFor(
                Collections.emptyList(), favorites, null));
    }
}
