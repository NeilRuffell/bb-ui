package dev.q25.bb7focus;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.util.Log;
import android.view.View;

import dev.q25.trackpad.theme.BB7Theme;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * Presentation-only hook at the native contextual-menu popup presenter.
 *
 * LineageOS SettingsBaseActivity uses android.widget.Toolbar and its native
 * ActionMenuPresenter. The framework MenuPopupWindow inherits
 * android.widget.ListPopupWindow.show(). Some Settings widgets may instead
 * use AppCompat's analogous class; both routes are supported.
 *
 * No popup content, menu actions, focus, or keyboard dispatch is replaced.
 */
final class SettingsPopupThemeHook {
    private static final String TAG = "BB7-PopupMenu";
    private static final String SETTINGS = "com.android.settings";

    private SettingsPopupThemeHook() {}

    static void install(ClassLoader loader) {
        // Framework ListPopupWindow is hidden from the public SDK stubs.
        // Resolve it at runtime in the Settings process.
        installPresenter(XposedHelpers.findClassIfExists(
                        "android.widget.ListPopupWindow", loader),
                XposedHelpers.findClassIfExists(
                        "android.widget.MenuPopupWindow", loader),
                "framework");
        Class<?> appCompatList = XposedHelpers.findClassIfExists(
                "androidx.appcompat.widget.ListPopupWindow", loader);
        Class<?> appCompatMenu = XposedHelpers.findClassIfExists(
                "androidx.appcompat.widget.MenuPopupWindow", loader);
        installPresenter(appCompatList, appCompatMenu, "AppCompat");
    }

    private static void installPresenter(Class<?> listClass, Class<?> menuClass,
            String source) {
        if (listClass == null || menuClass == null) {
            Log.i(TAG, source + " contextual popup classes unavailable");
            return;
        }
        try {
            XposedBridge.hookAllMethods(listClass, "show", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    if (!menuClass.isInstance(param.thisObject)) return;
                    try {
                        Object anchor = XposedHelpers.callMethod(param.thisObject,
                                "getAnchorView");
                        if (!(anchor instanceof View)) return;
                        Context context = ((View) anchor).getContext();
                        if (!SETTINGS.equals(context.getPackageName())) return;
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
                        Log.i(TAG, source + " native contextual menu styled");
                    } catch (Throwable error) {
                        Log.w(TAG, source + " menu remains native", error);
                    }
                }
            });
            Log.i(TAG, source + " MenuPopupWindow presenter hook registered");
        } catch (Throwable error) {
            Log.e(TAG, "Cannot register " + source + " menu presenter", error);
        }
    }
}
