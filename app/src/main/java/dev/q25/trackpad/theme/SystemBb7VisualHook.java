package dev.q25.trackpad.theme;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.util.Log;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.IXposedHookZygoteInit;
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
public final class SystemBb7VisualHook
        implements IXposedHookLoadPackage, IXposedHookZygoteInit {
    private static final String TAG = "BB7-SystemSkin";

    /**
     * Root framework entry: native View rendering is part of the boot class
     * loader and is shared by app processes. The callback checks each View's
     * OS-owned Context, so installed third-party apps remain visually stock.
     * Compose/Material3 classes are not available here; those remain handled
     * by their shared per-process theme hooks in handleLoadPackage().
     */
    @Override
    public void initZygote(StartupParam startupParam) {
        try {
            SystemBb7RootViewHook.install();
            Log.i(TAG, "Zygote native View appearance endpoint installed");
        } catch (Throwable error) {
            Log.e(TAG, "Unable to install zygote View endpoint", error);
        }
    }

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam load) {
        if (!isOsOwned(load.packageName, load.appInfo)) return;
        // The legacy framework entry can also be loaded in system_server.
        // Only actual app/UI processes get appearance hooks.
        if ("android".equals(load.packageName)
                && "system".equals(load.processName)) return;
        if (!enabled()) {
            Log.i(TAG, "Shared BB7 skin disabled by debug.bb7.systemskin.enabled");
            return;
        }

        Log.i(TAG, "Installing shared BB7 appearance in " + load.packageName
                + " process=" + load.processName);
        try {
            SystemBb7RootViewHook.install();
        } catch (Throwable error) {
            Log.e(TAG, "Root View background endpoint unavailable", error);
        }
        try {
            SystemBb7NativeTheme.install();
        } catch (Throwable error) {
            Log.e(TAG, "Framework colour theme unavailable", error);
        }
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

    static boolean enabled() {
        try {
            Class<?> properties = de.robv.android.xposed.XposedHelpers.findClass(
                    "android.os.SystemProperties", null);
            Object state = de.robv.android.xposed.XposedHelpers.callStaticMethod(
                    properties, "getBoolean", "debug.bb7.systemskin.enabled", true);
            return !Boolean.FALSE.equals(state);
        } catch (Throwable error) {
            // A missing optional debug property must never block normal BB UI.
            return true;
        }
    }

    /** Same package boundary for framework Views and the load-package callback. */
    static boolean isOsOwnedContext(Context context) {
        if (context == null) return false;
        try {
            return isOsOwned(context.getPackageName(),
                    context.getApplicationInfo());
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean isOsOwned(String packageName, ApplicationInfo info) {
        if (packageName == null || info == null) return false;
        // Include installed Android/Lineage OS packages only. A prefixed,
        // user-installed package does not qualify by name alone.
        boolean osName = "android".equals(packageName)
                || packageName.startsWith("com.android.")
                || packageName.startsWith("org.lineageos.")
                || "com.google.android.permissioncontroller".equals(packageName)
                || "com.google.android.packageinstaller".equals(packageName);
        if (!osName) return false;
        int systemFlags = ApplicationInfo.FLAG_SYSTEM
                | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP;
        return (info.flags & systemFlags) != 0;
    }
}
