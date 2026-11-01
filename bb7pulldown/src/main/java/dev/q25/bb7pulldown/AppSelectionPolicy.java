package dev.q25.bb7pulldown;

import java.util.List;

/**
 * Pure state transitions for the app icon selector.
 * Selection is a presentation detail; notification state is never changed.
 */
final class AppSelectionPolicy {
    private AppSelectionPolicy() {}

    static int afterUpdate(String selectedPackage, int oldIndex, List<String> packages) {
        if (packages == null || packages.isEmpty()) return -1;
        if (selectedPackage != null) {
            int retained = packages.indexOf(selectedPackage);
            if (retained >= 0) return retained;
        }
        return Math.min(Math.max(oldIndex, 0), packages.size() - 1);
    }

    static int move(int currentIndex, int step, int count) {
        if (count <= 0) return -1;
        int current = currentIndex < 0 ? 0 : currentIndex;
        return Math.max(0, Math.min(count - 1, current + Integer.signum(step)));
    }
}
