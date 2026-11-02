package dev.q25.bb7focus;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.Drawable;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ListView;
import android.widget.PopupWindow;

import dev.q25.trackpad.theme.BB7Theme;

import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/**
 * Appearance-only styling at the final framework PopupWindow display point.
 *
 * On LineageOS 24, SettingsBaseActivity installs android.widget.Toolbar,
 * whose ActionMenuPresenter uses framework MenuPopupWindow. Its internal
 * ListPopupWindow delegates actual display to PopupWindow.showAsDropDown.
 * Recognize the framework MenuDropDownListView content, not the toolbar
 * class or an assumed AppCompat presenter.
 *
 * No changes to menu adapters, actions, focus routing or key events.
 */
final class SettingsPopupThemeHook {
    private static final String TAG = "BB7-PopupMenu";
    private static final String SETTINGS = "com.android.settings";
    private static final String FRAMEWORK_MENU_LIST =
            "android.widget.MenuPopupWindow$MenuDropDownListView";
    private static final String APPCOMPAT_MENU_LIST =
            "androidx.appcompat.widget.MenuPopupWindow$MenuDropDownListView";
    private static final Set<PopupWindow> STYLED =
            Collections.newSetFromMap(new WeakHashMap<>());

    private SettingsPopupThemeHook() {}

    static void install(ClassLoader loader) {
        try {
            XC_MethodHook beforeShow = new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    PopupWindow popup = (PopupWindow) param.thisObject;
                    View content = popup.getContentView();
                    if (content == null) return;
                    ListView menuList = findMenuList(content);
                    if (menuList == null) return;
                    Context context = menuList.getContext();
                    if (!SETTINGS.equals(context.getPackageName())) return;
                    if (!STYLED.add(popup)) return;
                    try {
                        float density = context.getResources()
                                .getDisplayMetrics().density;
                        int stroke = Math.max(1, Math.round(density));
                        BB7Theme.Palette palette = BB7Theme.palette(context);
                        boolean dark = palette == BB7Theme.Palette.DARK;
                        GradientDrawable surface = new GradientDrawable(
                                GradientDrawable.Orientation.TOP_BOTTOM,
                                dark ? new int[]{0xff374657, 0xff273544, 0xff192633}
                                        : new int[]{0xfff9fafc, 0xffedf1f5, 0xffe5eaf0});
                        surface.setCornerRadius(8f * density);
                        surface.setStroke(stroke, palette.divider);
                        popup.setBackgroundDrawable(surface);
                        Drawable selector = BB7Theme.selected(0f, stroke);
                        menuList.setSelector(selector);
                        menuList.setDrawSelectorOnTop(false);
                        Log.i(TAG, "Native popup menu styled via PopupWindow: "
                                + menuList.getClass().getName());
                    } catch (Throwable error) {
                        STYLED.remove(popup);
                        Log.w(TAG, "Native popup styling failed; input unchanged", error);
                    }
                }
            };
            XposedBridge.hookAllMethods(PopupWindow.class, "showAsDropDown", beforeShow);
            XposedBridge.hookAllMethods(PopupWindow.class, "showAtLocation", beforeShow);
            Log.i(TAG, "Framework PopupWindow menu display hook registered");
        } catch (Throwable error) {
            Log.e(TAG, "Framework PopupWindow registration failed", error);
        }
    }

    private static ListView findMenuList(View root) {
        if (root instanceof ListView) {
            Class<?> type = root.getClass();
            while (type != null) {
                String name = type.getName();
                if (FRAMEWORK_MENU_LIST.equals(name)
                        || APPCOMPAT_MENU_LIST.equals(name)) return (ListView) root;
                type = type.getSuperclass();
            }
        }
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                ListView found = findMenuList(group.getChildAt(i));
                if (found != null) return found;
            }
        }
        return null;
    }
}
