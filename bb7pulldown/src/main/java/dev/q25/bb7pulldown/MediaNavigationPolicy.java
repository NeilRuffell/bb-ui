package dev.q25.bb7pulldown;

/**
 * Bounded hardware navigation within the expanded BB UI media player.
 * Progress is the last selectable row; artwork is display-only.
 * Media-session browsing is bounded independently of the player's rows.
 */
final class MediaNavigationPolicy {
    static final int TRANSPORT_CONTROLS = 0;
    static final int SEEK_BAR = 1;

    private MediaNavigationPolicy() {}

    static int moveRow(int currentRow, int direction) {
        return AppSelectionPolicy.move(currentRow, direction, SEEK_BAR + 1);
    }

    static int moveSession(int currentIndex, int direction, int count) {
        return AppSelectionPolicy.move(currentIndex, direction, count);
    }
}
