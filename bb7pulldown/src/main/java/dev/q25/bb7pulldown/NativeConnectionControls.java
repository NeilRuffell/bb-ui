package dev.q25.bb7pulldown;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.content.Context;
import android.content.Intent;
import android.net.wifi.WifiManager;
import android.provider.Settings;
import android.telephony.TelephonyManager;
import android.util.Log;

/** Read real Android radio/data settings; never infer state from stale UI. */
final class NativeConnectionControls {
    private static final String TAG = "BB6-Connections";

    static final class Status {
        final Boolean mobileData;
        final Boolean wifi;
        final Boolean bluetooth;

        Status(Boolean mobileData, Boolean wifi, Boolean bluetooth) {
            this.mobileData = mobileData;
            this.wifi = wifi;
            this.bluetooth = bluetooth;
        }

        Boolean forKind(int kind) {
            switch (kind) {
                case ConnectionControlPolicy.MOBILE: return mobileData;
                case ConnectionControlPolicy.WIFI: return wifi;
                case ConnectionControlPolicy.BLUETOOTH: return bluetooth;
                default: return null;
            }
        }
    }

    static Status read(Context context) {
        Boolean mobile = null, wifi = null, bluetooth = null;
        try {
            TelephonyManager telephony = (TelephonyManager)
                    context.getSystemService(Context.TELEPHONY_SERVICE);
            if (telephony != null && telephony.getSimState() !=
                    TelephonyManager.SIM_STATE_ABSENT) {
                // This is the mobile-DATA setting, not modem power or signal service.
                mobile = telephony.isDataEnabled();
            }
        } catch (RuntimeException error) {
            Log.w(TAG, "Mobile data status unavailable", error);
        }
        try {
            WifiManager manager = (WifiManager)
                    context.getSystemService(Context.WIFI_SERVICE);
            if (manager != null) wifi = manager.isWifiEnabled();
        } catch (RuntimeException error) {
            Log.w(TAG, "Wi-Fi status unavailable", error);
        }
        try {
            BluetoothManager manager = (BluetoothManager)
                    context.getSystemService(Context.BLUETOOTH_SERVICE);
            BluetoothAdapter adapter = manager == null ? null : manager.getAdapter();
            if (adapter != null) bluetooth = adapter.isEnabled();
        } catch (RuntimeException error) {
            Log.w(TAG, "Bluetooth status unavailable", error);
        }
        return new Status(mobile, wifi, bluetooth);
    }

    /**
     * Public Android panels are a safety fallback when the corresponding
     * SystemUI QS tile is not installed or has changed implementation.
     */
    static boolean openNativeSettings(Context context, int kind) {
        final String action;
        if (kind == ConnectionControlPolicy.MOBILE) {
            action = Settings.Panel.ACTION_INTERNET_CONNECTIVITY;
        } else if (kind == ConnectionControlPolicy.WIFI) {
            action = Settings.Panel.ACTION_WIFI;
        } else if (kind == ConnectionControlPolicy.BLUETOOTH) {
            action = Settings.ACTION_BLUETOOTH_SETTINGS;
        } else {
            return false;
        }
        try {
            Intent intent = new Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
            Log.i(TAG, "Opened Android native connectivity UI for kind=" + kind);
            return true;
        } catch (RuntimeException error) {
            Log.e(TAG, "Unable to open native connection settings kind=" + kind, error);
            return false;
        }
    }

    private NativeConnectionControls() {}
}
