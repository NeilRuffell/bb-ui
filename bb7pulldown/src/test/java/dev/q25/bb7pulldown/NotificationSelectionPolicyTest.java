package dev.q25.bb7pulldown;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
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

    private static NotificationItem flagged(String key, String pkg, boolean silent,
            String group, boolean summary) {
        return new NotificationItem(key, pkg, key, "body", 1, true,
                Collections.emptyList(), group, summary, silent);
    }

    @Test public void silentItemsAreOnlyInSharedSilentPane() {
        NotificationItem alertA = flagged("a1", "a", false, null, false);
        NotificationItem silentA = flagged("a2", "a", true, null, false);
        NotificationItem silentB = flagged("b1", "b", true, null, false);
        List<NotificationItem> all = Arrays.asList(alertA, silentA, silentB);
        assertEquals(Collections.singletonList(alertA),
                NotificationSelectionPolicy.forPackage(all, "a"));
        assertTrue(NotificationSelectionPolicy.forPackage(all, "b").isEmpty());
        assertEquals(Arrays.asList(silentA, silentB),
                NotificationSelectionPolicy.forSilent(all));
        assertTrue(NotificationSelectionPolicy.forPackage(
                all, NotificationCategoryPolicy.SILENT_ID).isEmpty());
    }

    @Test public void silentGroupSummaryIsHiddenWhenSilentChildrenArePresent() {
        NotificationItem summary = flagged("s", "a", true, "group", true);
        NotificationItem child = flagged("c", "a", true, "group", false);
        assertEquals(Collections.singletonList(child),
                NotificationSelectionPolicy.forSilent(Arrays.asList(summary, child)));
    }

    @Test public void silentSummaryRemainsWhenOnlyAlertingChildrenExist() {
        NotificationItem summary = flagged("s", "a", true, "group", true);
        NotificationItem child = flagged("c", "a", false, "group", false);
        List<NotificationItem> all = Arrays.asList(summary, child);
        assertEquals(Collections.singletonList(summary),
                NotificationSelectionPolicy.forSilent(all));
        assertEquals(Collections.singletonList(child),
                NotificationSelectionPolicy.forPackage(all, "a"));
    }

    @Test public void representedMediaRemovedOnlyFromSilentCategory() {
        NotificationItem media = flagged("media", "player", true, null, false);
        NotificationItem unrelated = flagged("other", "chat", true, null, false);
        NotificationItem alerting = flagged("alert", "player", false, null, false);
        List<NotificationItem> original = Arrays.asList(media, unrelated, alerting);
        List<NotificationItem> visible = NotificationSelectionPolicy.withoutRepresentedSilentMedia(
                original, new HashSet<>(Collections.singletonList("media")));

        assertEquals(Collections.singletonList(unrelated),
                NotificationSelectionPolicy.forSilent(visible));
        assertEquals(Collections.singletonList(alerting),
                NotificationSelectionPolicy.forPackage(visible, "player"));
        assertEquals(Collections.singletonList(media),
                NotificationSelectionPolicy.forSilent(original));
        assertEquals(Arrays.asList("player", NotificationCategoryPolicy.SILENT_ID),
                NotificationCategoryPolicy.categoriesFor(visible));
    }

    @Test public void mediaOnlyLeavesNoSilentIconWhenRepresented() {
        NotificationItem media = flagged("media", "player", true, null, false);
        List<NotificationItem> visible = NotificationSelectionPolicy.withoutRepresentedSilentMedia(
                Collections.singletonList(media),
                Collections.singleton("media"));
        assertTrue(NotificationCategoryPolicy.categoriesFor(visible).isEmpty());
    }

    @Test public void unrepresentedMediaRemainsAvailableForFallback() {
        NotificationItem media = flagged("media", "player", true, null, false);
        assertEquals(Collections.singletonList(media),
                NotificationSelectionPolicy.withoutRepresentedSilentMedia(
                        Collections.singletonList(media), Collections.emptySet()));
        assertEquals(Collections.singletonList(media),
                NotificationSelectionPolicy.withoutRepresentedSilentMedia(
                        Collections.singletonList(media), Collections.singleton("unknown")));
    }

    @Test public void contentUpdatesAreDetectableWithoutNewPackage() {
        NotificationItem changed = new NotificationItem("1", "a", "First", "updated", 1);
        assertFalse(a1.equals(changed));
        assertEquals(a1, new NotificationItem("1", "a", "First", "hello", 1));
    }
}
