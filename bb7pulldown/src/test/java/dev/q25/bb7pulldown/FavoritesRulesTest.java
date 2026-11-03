package dev.q25.bb7pulldown;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Test;

public class FavoritesRulesTest {
    private static NotificationItem item(String key, String pkg, String title,
            String conversation, boolean silent) {
        return new NotificationItem(key, pkg, title, "", 1L, true,
                Collections.emptyList(), null, false, silent,
                Collections.singletonList(conversation), "Some renamed chat");
    }

    private static FavoritesRules enabledWith(FavoritesRules.AppRule app) {
        FavoritesRules config = new FavoritesRules();
        config.enabled = true;
        config.apps.add(app);
        return config;
    }

    @Test public void entireAppIsIncludedWhenUnfiltered() {
        FavoritesRules.AppRule app = new FavoritesRules.AppRule("com.chat");
        FavoritesRules favorites = enabledWith(app);
        assertTrue(favorites.matches(item("1", "com.chat", "Other chat", "id-2", false)));
        assertFalse(favorites.matches(item("2", "com.mail", "Other chat", "id-2", false)));
        favorites.enabled = false;
        assertFalse(favorites.matches(item("1", "com.chat", "Other chat", "id-2", false)));
    }

    @Test public void nativeConversationIdentitySurvivesTitleChanges() {
        FavoritesRules.AppRule app = new FavoritesRules.AppRule("com.chat");
        app.onlyMatching = true;
        app.rules.add(new FavoritesRules.Rule(FavoritesRules.TYPE_CONVERSATION, "stable-7"));
        FavoritesRules favorites = enabledWith(app);
        assertTrue(favorites.matches(item("1", "com.chat", "Weekend", "stable-7", false)));
        assertTrue(favorites.matches(item("2", "com.chat", "Renamed chat", "stable-7", true)));
        assertFalse(favorites.matches(item("3", "com.chat", "Weekend", "other-id", false)));
    }

    @Test public void titleContainsIgnoresCaseButNotMessageBody() {
        FavoritesRules.AppRule app = new FavoritesRules.AppRule("com.chat");
        app.onlyMatching = true;
        app.rules.add(new FavoritesRules.Rule(
                FavoritesRules.TYPE_TITLE_CONTAINS, "weekEND Group"));
        FavoritesRules favorites = enabledWith(app);
        assertTrue(favorites.matches(item("1", "com.chat",
                "John · Weekend Group Chat", "id-1", false)));
        assertFalse(favorites.matches(item("2", "com.chat",
                "Family room", "id-1", false)));
    }

    @Test public void anyAndAllRulesAreSupported() {
        FavoritesRules.AppRule app = new FavoritesRules.AppRule("com.chat");
        app.onlyMatching = true;
        app.rules.add(new FavoritesRules.Rule(FavoritesRules.TYPE_TITLE_CONTAINS, "Weekend"));
        app.rules.add(new FavoritesRules.Rule(FavoritesRules.TYPE_CONVERSATION, "cid"));
        FavoritesRules favorites = enabledWith(app);
        NotificationItem titleOnly = item("1", "com.chat", "Weekend", "other", false);
        NotificationItem both = item("2", "com.chat", "Weekend", "cid", false);
        assertTrue(favorites.matches(titleOnly));
        app.matchAll = true;
        assertFalse(favorites.matches(titleOnly));
        assertTrue(favorites.matches(both));
    }

    @Test public void emptyRulesNeverMatchWhenFilteringIsRequired() {
        FavoritesRules.AppRule app = new FavoritesRules.AppRule("com.chat");
        app.onlyMatching = true;
        assertFalse(enabledWith(app).matches(item(
                "1", "com.chat", "Weekend", "cid", false)));
    }

    @Test public void favoritesContainBothSilentAndAlertingNotifications() {
        FavoritesRules.AppRule app = new FavoritesRules.AppRule("com.chat");
        FavoritesRules favorites = enabledWith(app);
        NotificationItem alert = item("1", "com.chat", "Weekend", "cid", false);
        NotificationItem silent = item("2", "com.chat", "Another", "cid2", true);
        assertEquals(Arrays.asList(alert, silent),
                favorites.filter(Arrays.asList(alert, silent)));
    }

    @Test public void favoriteCategoryIsFirstEvenWhenEmpty() {
        List<String> categories = NotificationCategoryPolicy.categoriesFor(
                Collections.emptyList(), true);
        assertEquals(Collections.singletonList(NotificationCategoryPolicy.FAVORITES_ID),
                categories);
        assertTrue(NotificationCategoryPolicy.categoriesFor(
                Collections.emptyList(), false).isEmpty());
    }

    @Test public void favoriteCategoryPrecedesAppsAndSilent() {
        NotificationItem alert = item("1", "com.chat", "Weekend", "cid", false);
        NotificationItem silent = item("2", "com.chat", "Another", "cid2", true);
        assertEquals(Arrays.asList(NotificationCategoryPolicy.FAVORITES_ID,
                        "com.chat", NotificationCategoryPolicy.SILENT_ID),
                NotificationCategoryPolicy.categoriesFor(Arrays.asList(alert, silent), true));
    }
}
