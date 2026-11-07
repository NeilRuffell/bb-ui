package dev.q25.bb7pulldown;

import android.view.KeyEvent;

/**
 * SystemUI-independent shade session and BB menu navigation owner.
 * The menu is transient; Notifications and Quick Settings remain separate.
 */
final class ShadeUiController {
    interface Listener {
        void onStatusFocusChanged(int zone);
        void onShowPage(int page);
        void onCloseApplication();
        void onSwitchApplication();
        void onExit();
    }

    private final ShadeStatusNavigation popup;
    private int openPage = ShadeStatusNavigation.NONE;

    ShadeUiController(Listener listener) {
        popup = new ShadeStatusNavigation(new ShadeStatusNavigation.Listener() {
            @Override public void onZoneFocus(int zone) {
                listener.onStatusFocusChanged(zone);
            }

            @Override public void onBack() { listener.onExit(); }

            @Override public void onNotificationsSelected() {
                listener.onShowPage(ShadeStatusNavigation.NOTIFICATIONS);
            }

            @Override public void onQuickSettingsSelected() {
                listener.onShowPage(ShadeStatusNavigation.QUICK_SETTINGS);
            }

            @Override public void onCloseApplicationSelected() {
                listener.onCloseApplication();
            }

            @Override public void onSwitchApplicationSelected() {
                listener.onSwitchApplication();
            }
        });
    }

    void setCloseApplicationAvailable(boolean available) {
        popup.setCloseApplicationAvailable(available);
    }

    /** BB key opens the menu without expanding either shade. */
    void enterStandaloneStatusBar() {
        openPage = ShadeStatusNavigation.NONE;
        popup.enter(ShadeStatusNavigation.NOTIFICATIONS);
    }

    /** Touch selects a visible row of the already-open menu. */
    void selectPopupZone(int zone) {
        if (popup.isFocused()) popup.enter(zone);
    }

    void leaveStandaloneStatusBar() {
        if (openPage == ShadeStatusNavigation.NONE) popup.leave();
    }

    void onShadeOpened(int page) { setPage(page); }
    void onPageChanged(int page) { setPage(page); }

    void onShadeClosed() {
        openPage = ShadeStatusNavigation.NONE;
        popup.leave();
    }

    private void setPage(int page) {
        if (page != ShadeStatusNavigation.NOTIFICATIONS
                && page != ShadeStatusNavigation.QUICK_SETTINGS) {
            throw new IllegalArgumentException("Invalid shade page");
        }
        openPage = page;
        popup.leave();
    }

    /** Reopen only from a deliberate hardware BlackBerry-key press. */
    void openPopupFromBlackBerryKey(int page) {
        if (openPage == page) popup.enter(page);
    }

    void focusContent() { popup.leave(); }
    boolean isStatusBarFocused() { return popup.isFocused(); }
    int focusedZone() { return popup.zone(); }
    int activePage() { return openPage; }

    boolean handleKey(KeyEvent event) {
        return handleKey(event.getKeyCode(), event.getAction());
    }

    boolean handleKey(int code, int action) {
        return popup.handleKey(code, action);
    }
}
