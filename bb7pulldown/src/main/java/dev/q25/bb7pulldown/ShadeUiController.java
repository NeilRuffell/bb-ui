package dev.q25.bb7pulldown;

import android.view.KeyEvent;

/**
 * LSPosed-independent Android shade/navigation state owner.
 *
 * The SystemUI integration (currently PullDownHook, later native SystemUI)
 * implements Listener. Both shade pages and the standalone status-bar focus
 * header selector receive this same controller, not separate navigation instances.
 *
 * The BB button first enters standalone selector focus without opening a
 * page. The user chooses Notifications or Quick Settings; only the selected
 * page is attached. Back exits the selector, Down preserves native Recents
 * behavior, and keyboard navigation is shared with the opened shade.
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

    /** Compact page selector focused without opening either shade. */
    void enterStandaloneStatusBar() {
        openPage = ShadeStatusNavigation.NONE;
        status.enter(ShadeStatusNavigation.NOTIFICATIONS);
    }

    /** Touch-select one page in the same shared keyboard/navigation state. */
    void selectStandaloneHeaderZone(int zone) {
        if (openPage == ShadeStatusNavigation.NONE && status.isFocused()) {
            status.enter(zone);
        }
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
        return handleKey(event.getKeyCode(), event.getAction());
    }

    /** Integer variant permits JVM state-machine tests without an Android runtime. */
    boolean handleKey(int code, int action) {
        return status.handleKey(code, action);
    }
}
