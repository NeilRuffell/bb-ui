package dev.q25.bb7pulldown;

import android.view.KeyEvent;

/**
 * Platform-independent shade/navigation state owner.
 *
 * The SystemUI integration (currently PullDownHook, later native SystemUI)
 * implements Listener. Both shade pages and the standalone status-bar focus
 * surface receive THIS controller, not their own navigation state machines.
 *
 * Back from the status bar always exits the current surface; Down returns to
 * shade content, or opens native Recents when the shade has not been opened.
 */
final class ShadeUiController {
    interface Listener {
        void onStatusFocusChanged(int zone);
        void onShowPage(int page);
        void onFocusShadeContent();
        void onExit();
        void onNativeRecents();
    }

    private final ShadeStatusNavigation status;
    private int openPage = ShadeStatusNavigation.NONE;

    ShadeUiController(Listener listener) {
        status = new ShadeStatusNavigation(new ShadeStatusNavigation.Listener() {
            @Override public void onZoneFocus(int zone) {
                listener.onStatusFocusChanged(zone);
            }

            @Override public void onDown() {
                if (openPage == ShadeStatusNavigation.NONE) {
                    listener.onNativeRecents();
                } else {
                    listener.onFocusShadeContent();
                }
            }

            @Override public void onBack() {
                listener.onExit();
            }

            @Override public void onNotificationsSelected() {
                listener.onShowPage(ShadeStatusNavigation.NOTIFICATIONS);
            }

            @Override public void onQuickSettingsSelected() {
                listener.onShowPage(ShadeStatusNavigation.QUICK_SETTINGS);
            }
        });
    }

    /** Status bar focused without any shade open (native Recents shortcut). */
    void enterStandaloneStatusBar() {
        openPage = ShadeStatusNavigation.NONE;
        status.enter(ShadeStatusNavigation.NOTIFICATIONS);
    }

    void leaveStandaloneStatusBar() {
        if (openPage == ShadeStatusNavigation.NONE) status.leave();
    }

    /** Call only after the shade is attached successfully. */
    void onShadeOpened(int page) {
        setPage(page);
    }

    /** Both retained pages belong to the same shade session. */
    void onPageChanged(int page) {
        setPage(page);
    }

    void onShadeClosed() {
        openPage = ShadeStatusNavigation.NONE;
        status.leave();
    }

    private void setPage(int page) {
        if (page != ShadeStatusNavigation.NOTIFICATIONS
                && page != ShadeStatusNavigation.QUICK_SETTINGS) {
            throw new IllegalArgumentException("Invalid shade page");
        }
        openPage = page;
        status.leave();
    }

    /** Called only from the current page's topmost focusable content. */
    void focusStatusBarFromPage(int page) {
        if (openPage == page) status.enter(page);
    }

    /** Called by content focus, page switch, or explicit focus return. */
    void focusContent() {
        status.leave();
    }

    boolean isStatusBarFocused() {
        return status.isFocused();
    }

    int focusedZone() {
        return status.zone();
    }

    int activePage() {
        return openPage;
    }

    boolean handleKey(KeyEvent event) {
        return status.handleKey(event.getKeyCode(), event.getAction());
    }
}
