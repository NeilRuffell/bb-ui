package dev.q25.trackpad.theme;

import android.content.Context;
import android.content.res.Resources;
import android.util.Log;
import android.util.TypedValue;

import java.util.concurrent.atomic.AtomicBoolean;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/**
 * Legacy Android framework-theme colors for OS-owned process scopes.
 * This deliberately hooks color attributes only, not dimensions, icons,
 * selectors, text appearance resources or arbitrary third-party apps.
 */
final class SystemBb7NativeTheme {
    private static final String TAG = "BB7-SystemSkin";
    private static final AtomicBoolean APPLIED = new AtomicBoolean();

    private SystemBb7NativeTheme() {}

    static void install() {
        try {
            XposedBridge.hookAllMethods(Resources.Theme.class, "resolveAttribute",
                    new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    if (!Boolean.TRUE.equals(param.getResult())
                            || param.args.length != 3
                            || !(param.args[0] instanceof Integer)
                            || !(param.args[1] instanceof TypedValue)) return;
                    int attribute = (Integer) param.args[0];
                    if (!matchesColorAttribute(attribute)) return;
                    try {
                        Resources.Theme theme = (Resources.Theme) param.thisObject;
                        Context context = currentApplication();
                        if (context == null) return;
                        BB7Theme.Palette p = BB7Theme.palette(context);
                        int rgb = resolveColor(attribute, p);
                        TypedValue value = (TypedValue) param.args[1];
                        value.type = TypedValue.TYPE_INT_COLOR_ARGB8;
                        value.data = rgb;
                        value.resourceId = 0;
                        value.string = null;
                        if (APPLIED.compareAndSet(false, true))
                            Log.i(TAG, "Android framework system colour theme applied");
                    } catch (Throwable error) {
                        // Original resolved attribute remains usable.
                    }
                }
            });
            Log.i(TAG, "Shared Android framework theme-colour hook registered");
        } catch (Throwable error) {
            Log.w(TAG, "Android framework theme-colour hook unavailable", error);
        }
    }

    private static boolean matchesColorAttribute(int attr) {
        return attr == android.R.attr.colorBackground
                || attr == android.R.attr.colorBackgroundFloating
                || attr == android.R.attr.colorAccent
                || attr == android.R.attr.colorControlActivated
                || attr == android.R.attr.colorControlHighlight
                || attr == android.R.attr.colorForeground;
    }

    private static int resolveColor(int attr, BB7Theme.Palette p) {
        if (attr == android.R.attr.colorBackground) return p.canvas;
        if (attr == android.R.attr.colorBackgroundFloating) return p.card;
        if (attr == android.R.attr.colorForeground) return p.title;
        if (attr == android.R.attr.colorControlHighlight) return 0x66459fd9;
        return BB7Theme.BLUE_MIDDLE;
    }

    private static Context currentApplication() {
        try {
            Object running = de.robv.android.xposed.XposedHelpers.callStaticMethod(
                    de.robv.android.xposed.XposedHelpers.findClass(
                            "android.app.ActivityThread", null),
                    "currentApplication");
            return running instanceof Context ? (Context) running : null;
        } catch (Throwable ignored) {
            return null;
        }
    }
}
