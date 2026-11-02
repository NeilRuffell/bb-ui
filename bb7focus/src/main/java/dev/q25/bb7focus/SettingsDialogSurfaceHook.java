package dev.q25.bb7focus;

import android.app.Dialog;
import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.util.Log;
import android.view.Window;

import dev.q25.trackpad.theme.BB7Theme;

import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/**
 * Settings-only appearance layer for the shared native/AppCompat AlertDialog
 * window. The original Android dialog still owns all buttons, list items,
 * text, focus, keyboard dispatch, accessibility and dismissal.
 *
 * LineageOS 24 Settings uses Theme.AlertDialog
 * (Theme.AppCompat.DayNight.Dialog.Alert), and expressive Settings uses its
 * SettingsLib expressive alert theme. Both use Dialog.show() to present a
 * platform Window, so this hook is a single presentation boundary.
 */
final class SettingsDialogSurfaceHook {
    private static final String TAG = "BB7-Dialogs";
    private static final String SETTINGS = "com.android.settings";
    private static final Set<Dialog> APPLIED =
            Collections.newSetFromMap(new WeakHashMap<>());

    private SettingsDialogSurfaceHook() {}

    static void install() {
        XposedBridge.hookAllMethods(Dialog.class, "show", new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                if (!(param.thisObject instanceof Dialog)) return;
                Dialog dialog = (Dialog) param.thisObject;
                if (!SETTINGS.equals(dialog.getContext().getPackageName())
                        || !isAlertDialog(dialog.getClass())) return;
                if (!APPLIED.add(dialog)) return;
                Window window = dialog.getWindow();
                if (window == null) return;
                try {
                    // Styling only: the existing dialog theme still supplies
                    // size, margins, typography, action buttons and animations.
                    window.setBackgroundDrawable(surface(dialog.getContext()));
                    Log.i(TAG, "Styled native Settings alert-dialog window: "
                            + dialog.getClass().getName());
                } catch (Throwable error) {
                    Log.w(TAG, "Dialog retains its original window background", error);
                }
            }
        });
        Log.i(TAG, "Shared Settings alert-dialog surface hook registered");
    }

    private static boolean isAlertDialog(Class<?> type) {
        for (Class<?> current = type; current != null;
                current = current.getSuperclass()) {
            String name = current.getName();
            if ("android.app.AlertDialog".equals(name)
                    || "androidx.appcompat.app.AlertDialog".equals(name)) return true;
        }
        return false;
    }

    private static GradientDrawable surface(Context context) {
        BB7Theme.Palette p = BB7Theme.palette(context);
        float density = context.getResources().getDisplayMetrics().density;
        boolean dark = p == BB7Theme.Palette.DARK;
        GradientDrawable drawable = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                dark
                        ? new int[]{0xff374657, 0xff273544, 0xff192633}
                        : new int[]{0xfff9fafc, 0xffedf1f5, 0xffe5eaf0});
        drawable.setCornerRadius(8f * density);
        drawable.setStroke(Math.max(1, Math.round(density)), p.divider);
        return drawable;
    }
}
