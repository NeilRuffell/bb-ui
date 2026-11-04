package dev.q25.bb7pulldown;

/**
 * BBOS6 locked connection-row presentation categories only.
 *
 * Native tile specs and state are resolved from SystemUI's actual QSHost
 * by BB6NativeQsPanel. This policy does not control platform radios.
 */
final class ConnectionControlPolicy {
    static final int MOBILE = 0;
    static final int WIFI = 1;
    static final int BLUETOOTH = 2;
    static final int COUNT = 3;

    static String label(int kind) {
        switch (kind) {
            case MOBILE: return "Mobile Network";
            case WIFI: return "Wi-Fi";
            case BLUETOOTH: return "Bluetooth";
            default: throw new IllegalArgumentException("Unknown connection control");
        }
    }

    private ConnectionControlPolicy() {}
}
