package dev.q25.bb7focus;

import android.app.Application;
import android.content.Context;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;

import java.lang.reflect.Method;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import dev.q25.trackpad.theme.BB7Theme;

/**
 * Settings SPA overflow is Compose, NOT android.widget.MenuPopupWindow.
 *
 * LineageOS 24 Settings: ManageApplicationsUtil routes the all-apps page to
 * SpaActivity when SETTINGS_ENABLE_SPA is enabled. SettingsLib's AppListPage
 * calls MoreOptionsAction, which uses Material3 DropdownMenu.
 *
 * MenuDefaults.containerColor is the shared Material3 menu-specific theme
 * token. Change only that token in the Settings process; do not alter the
 * whole ColorScheme, window layout, menu actions, or keyboard handling.
 *
 * The subwindow trace is diagnostic only and is never modified.
 */
final class SettingsPopupThemeHook {
    private static final String TAG = "BB7-PopupMenu";
    private static final String SETTINGS = "com.android.settings";
    private static boolean firstColorLog;

    private SettingsPopupThemeHook() {}

    static void install(ClassLoader loader) {
        installComposeMenuToken(loader);
        installWindowTrace(loader);
    }

    private static void installComposeMenuToken(ClassLoader loader) {
        Class<?> defaults = XposedHelpers.findClassIfExists(
                "androidx.compose.material3.MenuDefaults", loader);
        if (defaults == null) {
            Log.w(TAG, "Material3 MenuDefaults class unavailable; SPA menu unchanged");
            return;
        }
        int installed = 0;
        for (Method method : defaults.getDeclaredMethods()) {
            // A Compose Color is represented at the JVM boundary as a
            // packed long. Kotlin generates mangled getter names.
            if (!method.getName().startsWith("getContainerColor")
                    || method.getReturnType() != long.class) continue;
            try {
                XposedBridge.hookMethod(method, new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam param) {
                        // Xposed API 82 compile stubs do not include AndroidAppHelper.
                        // Resolve Android's running Application through the
                        // framework ActivityThread at runtime instead.
                        Object runningApp = XposedHelpers.callStaticMethod(
                                XposedHelpers.findClass("android.app.ActivityThread", loader),
                                "currentApplication");
                        if (!(runningApp instanceof Application)) return;
                        Application app = (Application) runningApp;
                        if (app == null || !SETTINGS.equals(app.getPackageName())) return;
                        BB7Theme.Palette palette = BB7Theme.palette(app);
                        // Compose Color for sRGB: ARGB in the high 32 bits,
                        // with the standard sRGB colorspace discriminator.
                        int color = palette == BB7Theme.Palette.DARK
                                ? 0xff273544 : 0xffedf1f5;
                        long packedColor = ((long) color & 0xffffffffL) << 32;
                        param.setResult(packedColor);
                        if (!firstColorLog) {
                            firstColorLog = true;
                            Log.i(TAG, "Compose Material3 menu background applied: "
                                    + method.getName() + " dark="
                                    + (palette == BB7Theme.Palette.DARK));
                        }
                    }
                });
                installed++;
                Log.i(TAG, "Compose menu color getter hooked: " + method.getName());
            } catch (Throwable error) {
                Log.e(TAG, "Compose menu getter hook failed: " + method.getName(), error);
            }
        }
        if (installed == 0) {
            Log.w(TAG, "Material3 MenuDefaults found, but no compatible "
                    + "long-returning containerColor getter; no appearance changes");
            for (Method method : defaults.getDeclaredMethods()) {
                if (method.getName().contains("ContainerColor"))
                    Log.w(TAG, "Available menu color method: " + method.getName()
                            + " return=" + method.getReturnType().getName());
            }
        }
    }

    /**
     * AOSP SPA Compose Popup and framework menu windows both ultimately
     * reach WindowManagerGlobal.addView. This confirms the real popup route
     * without modifying any window, focus target or menu content.
     */
    private static void installWindowTrace(ClassLoader loader) {
        Class<?> manager = XposedHelpers.findClassIfExists(
                "android.view.WindowManagerGlobal", loader);
        if (manager == null) {
            Log.w(TAG, "WindowManagerGlobal unavailable for popup diagnostics");
            return;
        }
        try {
            XposedBridge.hookAllMethods(manager, "addView", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    if (param.args.length < 2 || !(param.args[0] instanceof View)
                            || !(param.args[1] instanceof WindowManager.LayoutParams))
                        return;
                    View root = (View) param.args[0];
                    Context context = root.getContext();
                    if (context == null || !SETTINGS.equals(context.getPackageName())) return;
                    WindowManager.LayoutParams attributes =
                            (WindowManager.LayoutParams) param.args[1];
                    if (attributes.type < WindowManager.LayoutParams.FIRST_SUB_WINDOW
                            || attributes.type > WindowManager.LayoutParams.LAST_SUB_WINDOW)
                        return;
                    Log.i(TAG, "Settings subwindow created: type=" + attributes.type
                            + " root=" + root.getClass().getName()
                            + " title=" + attributes.getTitle()
                            + " child=" + firstChildClass(root));
                }
            });
            Log.i(TAG, "Shared Settings popup-window diagnostic registered");
        } catch (Throwable error) {
            Log.e(TAG, "Popup-window diagnostic registration failed", error);
        }
    }

    private static String firstChildClass(View root) {
        if (!(root instanceof ViewGroup)) return "none";
        ViewGroup parent = (ViewGroup) root;
        if (parent.getChildCount() == 0) return "none";
        return parent.getChildAt(0).getClass().getName();
    }
}
