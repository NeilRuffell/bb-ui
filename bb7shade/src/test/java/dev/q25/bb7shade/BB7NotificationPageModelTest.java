package dev.q25.bb7shade;

import static org.junit.Assert.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import org.junit.Test;

public final class BB7NotificationPageModelTest {
    private static ShadeNotificationIndex.Snapshot snapshot() {
        LinkedHashMap<String, List<ShadeNotificationIndex.NotificationRef>> items =
                new LinkedHashMap<>();
        items.put("app.a", new ArrayList<>(Arrays.asList(
                new ShadeNotificationIndex.NotificationRef("a-1", "app.a"),
                new ShadeNotificationIndex.NotificationRef("a-2", "app.a"))));
        items.put("app.b", new ArrayList<>(Arrays.asList(
                new ShadeNotificationIndex.NotificationRef("b-1", "app.b"))));
        return new ShadeNotificationIndex.Snapshot(items);
    }

    @Test public void selectionFiltersPresentationOnly() {
        BB7NotificationPageModel model = new BB7NotificationPageModel();
        ShadeNotificationIndex.Snapshot original = snapshot();
        model.acceptSnapshot(original);
        assertEquals(3, model.selectedNotifications().size());
        model.selectApplication("app.b");
        assertEquals(1, model.selectedNotifications().size());
        assertEquals("b-1", model.selectedNotifications().get(0).key);
        assertEquals(2, original.appCounts.get("app.a").intValue());
        assertEquals(1, original.appCounts.get("app.b").intValue());
        model.selectApplication(null);
        assertEquals(3, model.selectedNotifications().size());
    }

    @Test public void disappearingAppResetsSelection() {
        BB7NotificationPageModel model = new BB7NotificationPageModel();
        model.acceptSnapshot(snapshot());
        model.selectApplication("app.b");
        LinkedHashMap<String, List<ShadeNotificationIndex.NotificationRef>> reduced =
                new LinkedHashMap<>();
        reduced.put("app.a", new ArrayList<>(Arrays.asList(
                new ShadeNotificationIndex.NotificationRef("a-3", "app.a"))));
        model.acceptSnapshot(new ShadeNotificationIndex.Snapshot(reduced));
        assertNull(model.navigation().selectedPackage());
        assertEquals(1, model.selectedNotifications().size());
    }
}
