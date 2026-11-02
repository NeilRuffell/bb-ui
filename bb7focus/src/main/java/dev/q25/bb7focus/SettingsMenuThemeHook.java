package dev.q25.bb7focus;

import android.app.AlertDialog;
import android.app.Dialog;
import android.graphics.drawable.GradientDrawable;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ListView;
import android.widget.PopupWindow;

import dev.q25.trackpad.theme.BB7Theme;

import java.util.WeakHashMap;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Appearance-only styling for native Settings AlertDialog lists and popup
 * menus. Keeps Android's original list adapters, click listeners, keyboard
 * dispatch, focus search, accessibility and window lifecycles.
 */
public final class SettingsMenuThemeHook implements IXposedHookLoadPackage {
    private static final String TARGET = "com.android.settings";
    private static final String TAG = "BB7-Menus";
    private static final WeakHashMap<ListView, Boolean> STYLED = new WeakHashMap<>();

    @Override public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lp) {
        if (!TARGET.equals(lp.packageName)) return;
        try {
            XposedBridge.hookAllMethods(Dialog.class, "show", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    if (!(p.thisObject instanceof AlertDialog)) return;
                    AlertDialog dialog = (AlertDialog) p.thisObject;
                    if (!TARGET.equals(dialog.getContext().getPackageName())) return;
                    styleList(dialog.getListView());
                }
            });

            XC_MethodHook popupAppearance = new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    PopupWindow popup = (PopupWindow) p.thisObject;
                    View content = popup.getContentView();
                    if (content == null
                            || !TARGET.equals(content.getContext().getPackageName())) return;
                    styleContainedLists(content);
                }
            };
            XposedBridge.hookAllMethods(PopupWindow.class, "showAsDropDown", popupAppearance);
            XposedBridge.hookAllMethods(PopupWindow.class, "showAtLocation", popupAppearance);
            Log.i(TAG, "Native Settings menu appearance hooks registered");
        } catch (Throwable error) {
            Log.e(TAG, "Cannot register native Settings menu styling", error);
        }
    }

    private static void styleContainedLists(View view) {
        if (view instanceof ListView) styleList((ListView) view);
        if (!(view instanceof ViewGroup)) return;
        ViewGroup parent = (ViewGroup) view;
        for (int i = 0; i < parent.getChildCount(); i++) {
            styleContainedLists(parent.getChildAt(i));
        }
    }

    private static void styleList(ListView list) {
        if (list == null || STYLED.containsKey(list)) return;
        try {
            // Native selected/activated item remains controlled by ListView.
            // This changes only its visual selector, never selection itself.
            int densityBorder = Math.max(1, Math.round(
                    list.getResources().getDisplayMetrics().density));
            GradientDrawable selector = BB7Theme.selected(0f, densityBorder);
            list.setSelector(selector);
            list.setDrawSelectorOnTop(false);
            // Preserve Settings' active light/dark text colors. Do not
            // recolor content or replace any original item views.
            STYLED.put(list, Boolean.TRUE);
            Log.i(TAG, "Native Settings list selector styled: "
                    + list.getClass().getName());
        } catch (Throwable error) {
            Log.w(TAG, "Native menu list left unchanged", error);
        }
    }
}
