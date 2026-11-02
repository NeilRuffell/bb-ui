package dev.q25.trackpad.theme;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.util.Log;

import java.util.HashSet;
import java.util.Set;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Shared OS-owned UI appearance entry point for Vector.
 *
 * IMPORTANT: Vector invokes legacy module entry points in each APPROVED
 * process after its Zygisk scope check, including IXposedHookZygoteInit.
 * An initZygote callback here is NOT a global Zygote injection in Vector.
 *
 * Install directly at the earliest reliable per-process entry point,
 * handleLoadPackage, so Android View/theme and AndroidX Compose/Material
 * are already hooked before Settings/SystemUI creates their first screen.
 *
 * No Settings submenu names or layouts appear in this class. Nothing is
 * injected into third-party app processes. Native and Compose renderers
 * require separate framework endpoints; there is no universal UI root.
 */
public final class SystemBb7VisualHook implements IXposedHookLoadPackage {
    private static final String TAG = "BB7-SystemSkin";
    private static final Set<String> BOOTSTRAPPED = new HashSet<>();

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam load) {
        // Always report that the module entry ran, even when the policy
        // rejects the process. This distinguishes scope from hook failures.
        Log.i(TAG, "Module entry: package=" + load.packageName
                + " process=" + load.processName
                + " appInfo=" + (load.appInfo != null));

        if (!isOsOwned(load.packageName, load.appInfo)) {
            Log.i(TAG, "Appearance skipped: not an OS-owned system app");
            return;
        }
        // Vector identifies the platform server as system_server; never
        // mutate system_server UI resources or install Activity hooks there.
        if ("system_server".equals(load.processName)
                || "system".equals(load.processName)) {
            Log.i(TAG, "Appearance skipped: system_server");
            return;
        }
        if (!enabled()) {
            Log.i(TAG, "Appearance disabled: debug.bb7.systemskin.enabled=false");
            return;
        }
        String identity = load.processName + "|" + load.packageName;
        synchronized (BOOTSTRAPPED) {
            if (!BOOTSTRAPPED.add(identity)) return;
        }

        Log.i(TAG, "Installing shared visual endpoints: "
                + identity);
        run("native View backgrounds", () -> SystemBb7RootViewHook.install());
        run("framework theme colors", () -> SystemBb7NativeTheme.install());
        run("native shared components", () ->
                SystemBb7NativeVisuals.install(load.packageName, load.classLoader));
        run("Compose Material2/3", () ->
                SystemBb7ComposeVisuals.install(load.packageName, load.classLoader));
        run("native list focus", () ->
                SystemBb7RowFocus.install(load.packageName, load.classLoader));
        Log.i(TAG, "Shared system visual hook registration finished: "
                + identity);
    }

    private interface Installer {
        void install();
    }

    private static void run(String description, Installer installer) {
        try {
            installer.install();
            Log.i(TAG, "Hook installation attempted: " + description);
        } catch (Throwable error) {
            Log.e(TAG, "Hook installation failed: " + description, error);
        }
    }

    /** Read dynamically so the ADB kill switch affects View rendering. */
    static boolean enabled() {
        try {
            Class<?> properties = de.robv.android.xposed.XposedHelpers.findClass(
                    "android.os.SystemProperties", null);
            Object state = de.robv.android.xposed.XposedHelpers.callStaticMethod(
                    properties, "getBoolean", "debug.bb7.systemskin.enabled", true);
            return !Boolean.FALSE.equals(state);
        } catch (Throwable error) {
            return true;
        }
    }

    static boolean isOsOwnedContext(Context context) {
        if (context == null) return false;
        try {
            return isOsOwned(context.getPackageName(), context.getApplicationInfo());
        } catch (Throwable ignored) {
            return false;
        }
    }

    static boolean isOsOwned(String packageName, ApplicationInfo info) {
        if (packageName == null || info == null) return false;
        // OS package AND installed-system status: never skin Google Messages,
        // WhatsApp, or other independently authored third-party UI, even
        // when a vendor preinstalls them as /system applications.
        boolean osPackage = "android".equals(packageName)
                || packageName.startsWith("com.android.")
                || packageName.startsWith("org.lineageos.")
                || "com.google.android.permissioncontroller".equals(packageName)
                || "com.google.android.packageinstaller".equals(packageName);
        if (!osPackage) return false;
        return (info.flags & (ApplicationInfo.FLAG_SYSTEM
                | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0;
    }
}
