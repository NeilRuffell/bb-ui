package dev.q25.bb7focus;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.util.Log;

import dev.q25.trackpad.theme.BB7Theme;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * Shared appearance hook at AppCompat's native contextual-menu presenter.
 *
 * AppCompat StandardMenuPopup and CascadingMenuPopup use MenuPopupWindow,
 * a subclass of ListPopupWindow. ListPopupWindow.show() creates the actual
 * DropDownListView, honouring the list selector and background configured
 * here. No menu inflation, focus, keyboard or action dispatch is replaced.
 */
final class SettingsPopupThemeHook {
    private static final String TAG = "BB7-PopupMenu";
    private static final String SETTINGS = "com.android.settings";

    private SettingsPopupThemeHook() {}

    static void install(ClassLoader loader) {
        Class<?> listPopup = XposedHelpers.findClassIfExists(
                "androidx.appcompat.widget.ListPopupWindow", loader);
        Class<?> menuPopup = XposedHelpers.findClassIfExists(
                "androidx.appcompat.widget.MenuPopupWindow", loader);
        if (listPopup == null || menuPopup == null) {
            Log.w(TAG, "AppCompat contextual menu presenter unavailable; no changes");
            return;
        }
        try {
            XposedBridge.hookAllMethods(listPopup, "show", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    if (!menuPopup.isInstance(param.thisObject)) return;
                    try {
                        Object anchor = XposedHelpers.callMethod(param.thisObject,
                                "getAnchorView");
                        if (!(anchor instanceof android.view.View)) return;
                        Context context = ((android.view.View) anchor).getContext();
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
                        Log.i(TAG, "AppCompat native contextual menu styled");
                    } catch (Throwable error) {
                        Log.w(TAG, "Menu appearance unchanged; native show retained", error);
                    }
                }
            });
            Log.i(TAG, "AppCompat MenuPopupWindow presenter hook registered");
        } catch (Throwable error) {
            Log.e(TAG, "Cannot register AppCompat popup presenter", error);
        }
    }
}
