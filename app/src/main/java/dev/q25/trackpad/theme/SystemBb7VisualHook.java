package dev.q25.trackpad.theme;

import android.content.pm.ApplicationInfo;
import android.util.Log;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Shared BBOS7 visual skin for OS-owned Android application processes.
 *
 * This is a real process-scope boundary: Vector/LSPosed MUST scope the module
 * to each OS package to be styled. Do not scope this to "android" (system
 * server) or to third-party apps. Framework resource overlays would change
 * third-party UI too, which is explicitly not desired here.
 *
 * All hooks affect visual presentation only: no key interception, synthetic
 * input, preference changes, task-stack changes, or package-specific screens.
 */
public final class SystemBb7VisualHook implements IXposedHookLoadPackage {
    private static final String TAG = "BB7-SystemSkin";

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam load) {
        if (!isOsOwned(load.packageName, load.appInfo)) return;

        Log.i(TAG, "Installing shared BB7 appearance in " + load.packageName
                + " process=" + load.processName);
        try {
            SystemBb7NativeVisuals.install(load.packageName, load.classLoader);
        } catch (Throwable error) {
            Log.e(TAG, "Native visuals unavailable", error);
        }
        try {
            SystemBb7ComposeVisuals.install(load.packageName, load.classLoader);
        } catch (Throwable error) {
            Log.e(TAG, "Compose visuals unavailable", error);
        }
        try {
            SystemBb7RowFocus.install(load.packageName, load.classLoader);
        } catch (Throwable error) {
            Log.e(TAG, "System list focus visuals unavailable", error);
        }
    }

    private static boolean isOsOwned(String packageName, ApplicationInfo info) {
        if (packageName == null || info == null) return false;
        // Include installed Android/Lineage OS packages only. A prefixed,
        // user-installed package does not qualify by name alone.
        boolean osName = packageName.startsWith("com.android.")
                || packageName.startsWith("org.lineageos.")
                || "com.google.android.permissioncontroller".equals(packageName);
        if (!osName) return false;
        int systemFlags = ApplicationInfo.FLAG_SYSTEM
                | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP;
        return (info.flags & systemFlags) != 0;
    }
}
