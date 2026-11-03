package dev.q25.bb7pulldown;

import android.content.Context;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

/**
 * One system-owned overlay window, one date header and two retained content
 * pages. Page switches change visibility, never WindowManager ownership or
 * the root's translation/entrance animation.
 */
final class UnifiedShadeHost extends FrameLayout {
    static final int NOTIFICATIONS = ShadeStatusNavigation.NOTIFICATIONS;
    static final int QUICK_SETTINGS = ShadeStatusNavigation.QUICK_SETTINGS;

    private final ShadeDateHeader dateHeader;
    private final TwoColumnPanel notifications;
    private final BB6ConnectionsPanel connections;
    private int page;

    UnifiedShadeHost(Context context, TwoColumnPanel notifications,
            BB6ConnectionsPanel connections, int initialPage) {
        super(context);
        this.notifications = notifications;
        this.connections = connections;

        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        addView(layout, new FrameLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        dateHeader = new ShadeDateHeader(context);
        layout.addView(dateHeader, ShadeDateHeader.layoutParams(context));

        FrameLayout pages = new FrameLayout(context);
        layout.addView(pages, new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, 0, 1f));
        pages.addView(notifications, new FrameLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        pages.addView(connections, new FrameLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        // Do not request focus until the one-time entrance animation finishes.
        setPage(initialPage);
    }

    void setPage(int next) {
        if (next != NOTIFICATIONS && next != QUICK_SETTINGS) {
            throw new IllegalArgumentException("Unknown shade page");
        }
        if (page == next && notifications.getVisibility() != connections.getVisibility()) {
            return;
        }
        notifications.clearStatusZoneFocus();
        connections.clearStatusZoneFocus();
        page = next;
        notifications.setVisibility(next == NOTIFICATIONS ? View.VISIBLE : View.GONE);
        connections.setVisibility(next == QUICK_SETTINGS ? View.VISIBLE : View.GONE);
    }

    int page() {
        return page;
    }

    void focusContent() {
        if (page == QUICK_SETTINGS) {
            connections.focusFirst();
        } else {
            notifications.focusSelectedAppIcon();
        }
    }

    void setStatusBarFocused(boolean focused) {
        dateHeader.setShadeContentFocused(!focused);
    }

    void clearStatusBarFocus() {
        notifications.clearStatusZoneFocus();
        connections.clearStatusZoneFocus();
    }
}
