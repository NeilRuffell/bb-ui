package dev.q25.bb7pulldown;

/** Reuse BB's existing three Back-navigation scopes, never stock shade UI. */
final class NativeShadeBackRoute {
    static final int STATUS_BAR = 0;
    static final int NOTIFICATIONS = 1;
    static final int QUICK_SETTINGS = 2;

    private NativeShadeBackRoute() {}

    static int route(boolean statusFocused, int page) {
        if (statusFocused) return STATUS_BAR;
        return page == ShadeStatusNavigation.QUICK_SETTINGS
                ? QUICK_SETTINGS : NOTIFICATIONS;
    }
}
