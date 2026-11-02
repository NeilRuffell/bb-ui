package dev.q25.bb7focus;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.util.Log;

import dev.q25.trackpad.theme.BB7Theme;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * Appearance-only styling at the actual menu-window construction boundary.
 *
 * LineageOS 24 Settings uses android.widget.Toolbar. Its native
 * ActionMenuPresenter creates MenuPopupHelper, which constructs a framework
 * MenuPopupWindow (standard or cascading). A MenuPopupWindow constructor is
 * shared by these menu types; unlike generic PopupWindow display hooks, no
 * content-view or child-class inference is necessary.
 *
 * AppCompat has an analogous MenuPopupWindow. Neither path changes input,
 * menu adapters, focus search, accessibility or action handling.
 */
final class SettingsPopupThemeHook {
    private static final String TAG = "BB7-PopupMenu";
    private static final String SETTINGS = "com.android.settings";

    private SettingsPopupThemeHook() {}

    static void install(ClassLoader loader) {
        Class<?> frameworkMenu = XposedHelpers.findClassIfExists(
                "android.widget.MenuPopupWindow", loader);
        Class<?> appCompatMenu = XposedHelpers.findClassIfExists(
                "androidx.appcompat.widget.MenuPopupWindow", loader);
        installMenuConstructor(frameworkMenu, "framework");
        installMenuConstructor(appCompatMenu, "AppCompat");
        installNativeMenuTrace(loader);
    }

    private static void installMenuConstructor(Class<?> type, String source) {
        if (type == null) {
            Log.i(TAG, source + " menu window class unavailable");
            return;
        }
        try {
            XposedBridge.hookAllConstructors(type, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    if (param.args.length == 0 || !(param.args[0] instanceof Context))
                        return;
                    Context context = (Context) param.args[0];
                    if (!SETTINGS.equals(context.getPackageName())) {
                        Log.i(TAG, source + " menu window context="
                                + context.getPackageName() + " (untouched)");
                        return;
                    }
                    try {
                        float density = context.getResources()
                                .getDisplayMetrics().density;
                        int stroke = Math.max(1, Math.round(density));
                        BB7Theme.Palette palette = BB7Theme.palette(context);
                        boolean dark = palette == BB7Theme.Palette.DARK;
                        GradientDrawable background = new GradientDrawable(
                                GradientDrawable.Orientation.TOP_BOTTOM,
                                dark ? new int[]{0xff374657, 0xff273544, 0xff192633}
                                        : new int[]{0xfff9fafc, 0xffedf1f5, 0xffe5eaf0});
                        background.setCornerRadius(8f * density);
                        background.setStroke(stroke, palette.divider);
                        XposedHelpers.callMethod(param.thisObject,
                                "setBackgroundDrawable", background);
                        XposedHelpers.callMethod(param.thisObject,
                                "setListSelector", BB7Theme.selected(0f, stroke));
                        Log.i(TAG, source + " menu window styled on construction");
                    } catch (Throwable error) {
                        Log.e(TAG, source + " menu appearance failure", error);
                    }
                }
            });
            Log.i(TAG, source + " menu-window constructor hook registered");
        } catch (Throwable error) {
            Log.e(TAG, source + " menu-window registration failed", error);
        }
    }

    /** Diagnose the native overflow route even when a menu window is absent. */
    private static void installNativeMenuTrace(ClassLoader loader) {
        Class<?> helper = XposedHelpers.findClassIfExists(
                "com.android.internal.view.menu.MenuPopupHelper", loader);
        if (helper == null) {
            Log.w(TAG, "Native MenuPopupHelper unavailable");
            return;
        }
        try {
            XposedBridge.hookAllMethods(helper, "tryShow", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    Log.i(TAG, "Native MenuPopupHelper.tryShow invoked; args="
                            + param.args.length
                            + " implementation=" + param.thisObject.getClass().getName());
                }
            });
            Log.i(TAG, "Native menu invocation trace registered");
        } catch (Throwable error) {
            Log.e(TAG, "Native menu invocation trace registration failed", error);
        }
    }
}
