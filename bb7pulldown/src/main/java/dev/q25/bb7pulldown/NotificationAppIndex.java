package dev.q25.bb7pulldown;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;

/** UI-only ordered package index: one icon per app, in native shade order. */
final class NotificationAppIndex {
    private NotificationAppIndex() {}

    static List<String> uniquePackages(List<String> packages) {
        if (packages == null || packages.isEmpty()) return Collections.emptyList();
        LinkedHashSet<String> distinct = new LinkedHashSet<>();
        for (String name : packages) {
            if (name != null && !name.isEmpty()) distinct.add(name);
        }
        return Collections.unmodifiableList(new ArrayList<>(distinct));
    }
}
