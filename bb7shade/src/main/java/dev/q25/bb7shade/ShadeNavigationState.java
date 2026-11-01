package dev.q25.bb7shade;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

/**
 * Pure layout/navigation state for the future two-pane shade.
 *
 * No key or trackpad events are intercepted in this stage. The state model
 * makes panel boundaries explicit so notification action focus does not
 * inadvertently trigger a page transition.
 */
public final class ShadeNavigationState {
    public enum Page { NOTIFICATIONS, QUICK_SETTINGS }
    public enum Region { APP_RAIL, NOTIFICATION_PANE, QUICK_SETTINGS }

    private Page page = Page.NOTIFICATIONS;
    private Region region = Region.APP_RAIL;
    private final ArrayList<String> packages = new ArrayList<>();
    private String selectedPackage; // null = UI-only "All" item.

    public Page page() { return page; }
    public Region region() { return region; }
    public String selectedPackage() { return selectedPackage; }
    public List<String> packages() {
        return Collections.unmodifiableList(packages);
    }

    public void updatePackages(Collection<String> orderedPackages) {
        packages.clear();
        for (String packageName : orderedPackages) {
            if (packageName != null && !packageName.isEmpty()
                    && !packages.contains(packageName)) {
                packages.add(packageName);
            }
        }
        if (selectedPackage != null && !packages.contains(selectedPackage)) {
            selectedPackage = null;
            if (region == Region.NOTIFICATION_PANE) region = Region.APP_RAIL;
        }
    }

    /** Select an app from the rail, without changing Android's notification list. */
    public void selectPackage(String packageName) {
        if (packageName != null && !packages.contains(packageName)) {
            throw new IllegalArgumentException("Package not in visible notification index");
        }
        selectedPackage = packageName;
    }

    /** Applies to the application rail only; no wraparound or implicit page switch. */
    public boolean moveAppSelection(int delta) {
        if (page != Page.NOTIFICATIONS || region != Region.APP_RAIL || delta == 0) {
            return false;
        }
        int index = selectedPackage == null ? 0 : packages.indexOf(selectedPackage) + 1;
        int nextIndex = Math.max(0, Math.min(packages.size(), index + delta));
        if (nextIndex == index) return false;
        selectedPackage = nextIndex == 0 ? null : packages.get(nextIndex - 1);
        return true;
    }

    public void enterNotificationPane() {
        if (page == Page.NOTIFICATIONS) region = Region.NOTIFICATION_PANE;
    }

    public void returnToAppRail() {
        if (page == Page.NOTIFICATIONS) region = Region.APP_RAIL;
    }

    /** Explicit page navigation. Never derived from arbitrary row key events. */
    public void showQuickSettings() {
        page = Page.QUICK_SETTINGS;
        region = Region.QUICK_SETTINGS;
    }

    public void showNotifications() {
        page = Page.NOTIFICATIONS;
        region = Region.APP_RAIL;
    }
}
