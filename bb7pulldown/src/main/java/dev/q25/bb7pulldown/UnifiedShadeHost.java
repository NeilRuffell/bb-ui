package dev.q25.bb7pulldown;

import android.content.Context;
import android.view.View;
import android.view.MotionEvent;
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
    private final ShadeUiController navigation;
    private final Runnable closeShade;
    private int page;
    private float headerTouchDownY;
    private boolean headerSwipeClosed;

    UnifiedShadeHost(Context context, TwoColumnPanel notifications,
            BB6ConnectionsPanel connections, ShadeUiController navigation,
            int initialPage, Runnable closeShade) {
        super(context);
        this.notifications = notifications;
        this.connections = connections;
        this.navigation = navigation;
        this.closeShade = closeShade;

        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        addView(layout, new FrameLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        dateHeader = new ShadeDateHeader(context);
        // Previously the header lived inside each page and inherited its
        // swipe-up dismissal. Preserve that gesture at the shared host level.
        dateHeader.setOnTouchListener((view, event) -> {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    headerTouchDownY = event.getRawY();
                    headerSwipeClosed = false;
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if (!headerSwipeClosed
                            && headerTouchDownY - event.getRawY() >= dp(65)) {
                        headerSwipeClosed = true;
                        closeShade.run();
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    headerSwipeClosed = false;
                    return true;
                default:
                    return true;
            }
        });
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
        if (page != next) navigation.focusContent();
        page = next;
        notifications.setVisibility(next == NOTIFICATIONS ? View.VISIBLE : View.GONE);
        connections.setVisibility(next == QUICK_SETTINGS ? View.VISIBLE : View.GONE);
    }

    int page() {
        return page;
    }

    void focusContent() {
        navigation.focusContent();
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
        navigation.focusContent();
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }
}
