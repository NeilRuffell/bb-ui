package dev.q25.trackpad.theme;

import android.app.Application;
import android.app.Instrumentation;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.util.Log;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.IXposedHookZygoteInit;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Single framework-level BBOS7 visual bootstrap.
 *
 * In Zygote, hook the shared native View background endpoint and Android's
 * common Instrumentation.callApplicationOnCreate(Application). After Android
 * creates an OS-owned Application, install shared framework/Material2/3
 * appearance and native list focus hooks against THAT process ClassLoader.
 *
 * Unlike Settings-only resource-ID hooks, this requires no knowledge of
 * particular screens, submenus, activities or view layouts. Only UI inside
 * verified OS-owned packages is modified; third-party apps continue to use
 * their own styles.
 *
 * The legacy handleLoadPackage callback is retained as a scoped fallback
 * when a Vector/LSPosed runtime does not propagate the Zygote entry point.
 * Both entry points call the same idempotent bootstrap.
 */
public final class SystemBb7VisualHook
        implements IXposedHookLoadPackage, IXposedHookZygoteInit {
    private static final String TAG = "BB7-SystemSkin";
    private static final AtomicBoolean LIFECYCLE_HOOKED = new AtomicBoolean();
    private static final Map<ClassLoader, Set<String>> STYLED =
            new WeakHashMap<>();

    @Override
    public void initZygote(StartupParam startupParam) {
        installSharedFrameworkEndpoints("zygote");
    }

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam load) {
        if (!isOsOwned(load.packageName, load.appInfo)) return;
        // Never install application presentation hooks in system_server.
        if ("android".equals(load.packageName)
                && "system".equals(load.processName)) return;
        installSharedFrameworkEndpoints("scoped:" + load.packageName);
    }

    /**
     * Both hooks are owned by Android's boot class loader, not Settings or
     * a specific AndroidX library. The process/Context is selected later.
     */
    private static void installSharedFrameworkEndpoints(String origin) {
        try {
            SystemBb7RootViewHook.install();
        } catch (Throwable error) {
            Log.e(TAG, "Shared root View endpoint unavailable", error);
        }
        if (!LIFECYCLE_HOOKED.compareAndSet(false, true)) return;
        try {
            XposedBridge.hookAllMethods(Instrumentation.class,
                    "callApplicationOnCreate", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    if (param.args.length != 1
                            || !(param.args[0] instanceof Application)) return;
                    Application app = (Application) param.args[0];
                    if (!isOsOwnedContext(app) || !enabled()) return;
                    installForSystemApplication(app);
                }
            });
            Log.i(TAG, "Framework Application-onCreate skin bootstrap active: "
                    + origin);
        } catch (Throwable error) {
            LIFECYCLE_HOOKED.set(false);
            Log.e(TAG, "Framework application bootstrap unavailable", error);
        }
    }

    /** One installation per package and application ClassLoader. */
    private static void installForSystemApplication(Application app) {
        String name = app.getPackageName();
        ClassLoader loader = app.getClassLoader();
        synchronized (STYLED) {
            Set<String> installed = STYLED.get(loader);
            if (installed == null) {
                installed = new HashSet<>();
                STYLED.put(loader, installed);
            }
            if (!installed.add(name)) return;
        }
        Log.i(TAG, "Applying root-based BB7 theme to OS application: "
                + name);

        try {
            SystemBb7NativeTheme.install();
        } catch (Throwable error) {
            Log.e(TAG, "Framework colour theme unavailable", error);
        }
        try {
            SystemBb7NativeVisuals.install(name, loader);
        } catch (Throwable error) {
            Log.e(TAG, "Native UI appearance unavailable", error);
        }
        try {
            SystemBb7ComposeVisuals.install(name, loader);
        } catch (Throwable error) {
            Log.e(TAG, "Compose UI appearance unavailable", error);
        }
        try {
            SystemBb7RowFocus.install(name, loader);
        } catch (Throwable error) {
            Log.e(TAG, "Shared native-list focus appearance unavailable", error);
        }
    }

    /** Read at each relevant View update to allow the ADB kill switch. */
    static boolean enabled() {
        try {
            Class<?> properties = XposedHelpers.findClass(
                    "android.os.SystemProperties", null);
            Object state = XposedHelpers.callStaticMethod(
                    properties, "getBoolean", "debug.bb7.systemskin.enabled", true);
            return !Boolean.FALSE.equals(state);
        } catch (Throwable error) {
            // Keep the existing appearance when the optional flag is absent.
            return true;
        }
    }

    /** Same strict third-party exclusion in the View and Application paths. */
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
