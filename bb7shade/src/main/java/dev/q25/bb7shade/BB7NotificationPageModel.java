package dev.q25.bb7shade;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Selection and filtered *view* of the indexed notification references.
 * Neither this model nor the rail alters the authoritative notifications.
 */
final class BB7NotificationPageModel {
    private final ShadeNavigationState navigation = new ShadeNavigationState();
    private ShadeNotificationIndex.Snapshot snapshot =
            new ShadeNotificationIndex.Snapshot(new java.util.LinkedHashMap<>());

    ShadeNavigationState navigation() { return navigation; }

    void acceptSnapshot(ShadeNotificationIndex.Snapshot next) {
        if (next == null) throw new IllegalArgumentException("Snapshot required");
        snapshot = next;
        navigation.updatePackages(snapshot.appCounts.keySet());
    }

    List<ShadeNotificationIndex.NotificationRef> selectedNotifications() {
        String selectedPackage = navigation.selectedPackage();
        if (selectedPackage != null) {
            List<ShadeNotificationIndex.NotificationRef> refs =
                    snapshot.notificationsByApp.get(selectedPackage);
            return refs == null ? Collections.emptyList() : refs;
        }
        List<ShadeNotificationIndex.NotificationRef> all = new ArrayList<>();
        for (List<ShadeNotificationIndex.NotificationRef> refs
                : snapshot.notificationsByApp.values()) {
            all.addAll(refs);
        }
        return Collections.unmodifiableList(all);
    }

    void selectApplication(String packageName) {
        navigation.selectPackage(packageName);
    }
}
