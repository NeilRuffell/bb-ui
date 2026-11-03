package dev.q25.bb7pulldown;

/**
 * BBOS6-style presentation categories mapped to Android's existing QS controls.
 * Android 12+ manages Wi-Fi and mobile data through the shared Internet tile.
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

    static String tileSpec(int kind) {
        switch (kind) {
            case MOBILE:
            case WIFI:
                return "internet";
            case BLUETOOTH:
                return "bt";
            default:
                throw new IllegalArgumentException("Unknown connection control");
        }
    }

    static String stateLabel(int kind, Boolean enabled) {
        if (enabled == null) return "Unknown";
        if (kind == MOBILE) return enabled ? "Data on" : "Data off";
        return enabled ? "On" : "Off";
    }

    static boolean checked(Boolean enabled) {
        return Boolean.TRUE.equals(enabled);
    }

    private ConnectionControlPolicy() {}
}
