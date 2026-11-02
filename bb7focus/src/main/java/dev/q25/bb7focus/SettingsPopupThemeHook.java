package dev.q25.bb7focus;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.util.Log;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.concurrent.atomic.AtomicBoolean;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import dev.q25.trackpad.theme.BB7Theme;

/**
 * Settings native + Compose menu appearance, scoped to com.android.settings.
 *
 * LineageOS 24's Apps > All apps can route through SPA. Its menu is
 * SettingsLib MoreOptionsAction -> Material3 DropdownMenu, not PopupWindow.
 * Legacy Settings pages can also use the framework MenuPopupWindow.
 *
 * Styling is applied at the corresponding shared theme/presentation APIs,
 * without menu replacement or any synthetic input processing.
 */
final class SettingsPopupThemeHook {
    private static final String TAG = "BB7-PopupMenu";
    private static final String SETTINGS = "com.android.settings";
    private static final AtomicBoolean COMPOSE_COLOR_SEEN = new AtomicBoolean();
    private static final AtomicBoolean COMPACT_SEEN = new AtomicBoolean();

    private SettingsPopupThemeHook() {}

    static void install(ClassLoader loader) {
        installComposeMenuColor(loader);
        SettingsComposeMenuFocusHook.install(loader);
        installComposeMenuPadding(loader);
        installNativeMenuAppearance(loader);
        SettingsMenuGeometryHook.install(loader);
    }

    private static void installComposeMenuColor(ClassLoader loader) {
        Class<?> defaults = XposedHelpers.findClassIfExists(
                "androidx.compose.material3.MenuDefaults", loader);
        if (defaults == null) {
            Log.i(TAG, "Material3 MenuDefaults absent; Compose colour unchanged");
            return;
        }
        int hooked = 0;
        for (Method method : defaults.getDeclaredMethods()) {
            if (!method.getName().startsWith("getContainerColor")
                    || method.getReturnType() != long.class) continue;
            try {
                XposedBridge.hookMethod(method, new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam param) {
                        try {
                            Context context = settingsApplication(loader);
                            if (context == null) return;
                            boolean dark = BB7Theme.palette(context) == BB7Theme.Palette.DARK;
                            // Compose's packed sRGB Color: ARGB upper 32 bits.
                            int color = dark ? 0xff273544 : 0xffedf1f5;
                            param.setResult((((long) color) & 0xffffffffL) << 32);
                            if (COMPOSE_COLOR_SEEN.compareAndSet(false, true))
                                Log.i(TAG, "Compose menu background applied: "
                                        + method.getName() + " dark=" + dark);
                        } catch (Throwable error) {
                            Log.w(TAG, "Compose menu colour unchanged", error);
                        }
                    }
                });
                hooked++;
            } catch (Throwable error) {
                Log.e(TAG, "Compose container colour registration failed", error);
            }
        }
        Log.i(TAG, "Compose menu background getters hooked=" + hooked);
    }

    /**
     * Save horizontal space on the Q25 without touching row height or keyboard
     * behaviour. This changes Material3's menu-item padding token from its
     * default to 10dp horizontal, 0dp vertical where available.
     */
    private static void installComposeMenuPadding(ClassLoader loader) {
        Class<?> defaults = XposedHelpers.findClassIfExists(
                "androidx.compose.material3.MenuDefaults", loader);
        Class<?> paddingKt = XposedHelpers.findClassIfExists(
                "androidx.compose.foundation.layout.PaddingKt", loader);
        if (defaults == null || paddingKt == null) {
            Log.i(TAG, "Compose menu padding API unavailable; original density retained");
            return;
        }
        Object compact = null;
        for (Method method : paddingKt.getDeclaredMethods()) {
            if (!method.getName().startsWith("PaddingValues")
                    || !Modifier.isStatic(method.getModifiers())
                    || method.getParameterTypes().length != 2
                    || method.getParameterTypes()[0] != float.class
                    || method.getParameterTypes()[1] != float.class) continue;
            try {
                method.setAccessible(true);
                compact = method.invoke(null, 10f, 0f);
                if (compact != null) break;
            } catch (Throwable ignored) {
                // Runtime Compose version may not expose this overload.
            }
        }
        if (compact == null) {
            Log.i(TAG, "Compose PaddingValues factory unavailable; original padding retained");
            return;
        }
        final Object replacement = compact;
        int hooked = 0;
        for (Method method : defaults.getDeclaredMethods()) {
            if (!method.getName().startsWith("getDropdownMenuItemContentPadding")
                    && !method.getName().startsWith(
                            "getDropdownMenuSelectableItemContentPadding")) continue;
            if (method.getParameterTypes().length != 0
                    || !method.getReturnType().isInstance(replacement)) continue;
            try {
                XposedBridge.hookMethod(method, new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam param) {
                        param.setResult(replacement);
                        if (COMPACT_SEEN.compareAndSet(false, true))
                            Log.i(TAG, "Compact Compose menu horizontal padding applied");
                    }
                });
                hooked++;
            } catch (Throwable error) {
                Log.w(TAG, "Compose padding getter unavailable", error);
            }
        }
        Log.i(TAG, "Compose menu padding getters hooked=" + hooked);
    }

    /** Native overflow menus on Settings screens outside SPA. */
    private static void installNativeMenuAppearance(ClassLoader loader) {
        installNativePresenter(
                XposedHelpers.findClassIfExists("android.widget.MenuPopupWindow", loader),
                "framework");
        installNativePresenter(
                XposedHelpers.findClassIfExists(
                        "androidx.appcompat.widget.MenuPopupWindow", loader),
                "AppCompat");
    }

    private static void installNativePresenter(Class<?> type, String label) {
        if (type == null) return;
        try {
            XposedBridge.hookAllConstructors(type, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    if (param.args.length == 0 || !(param.args[0] instanceof Context))
                        return;
                    Context context = (Context) param.args[0];
                    if (!SETTINGS.equals(context.getPackageName())) return;
                    try {
                        float density = context.getResources()
                                .getDisplayMetrics().density;
                        int lineWidth = Math.max(1, Math.round(density));
                        BB7Theme.Palette palette = BB7Theme.palette(context);
                        boolean dark = palette == BB7Theme.Palette.DARK;
                        GradientDrawable surface = new GradientDrawable(
                                GradientDrawable.Orientation.TOP_BOTTOM,
                                dark ? new int[]{0xff374657, 0xff273544, 0xff192633}
                                        : new int[]{0xfff9fafc, 0xffedf1f5, 0xffe5eaf0});
                        surface.setCornerRadius(0f);
                        surface.setStroke(lineWidth, palette.divider);
                        XposedHelpers.callMethod(
                                param.thisObject, "setBackgroundDrawable", surface);
                        XposedHelpers.callMethod(param.thisObject,
                                "setListSelector", BB7Theme.selected(0f, lineWidth));
                        Log.i(TAG, label + " native menu styled");
                    } catch (Throwable error) {
                        Log.w(TAG, label + " native menu keeps original appearance", error);
                    }
                }
            });
            Log.i(TAG, label + " native menu constructor hook installed");
        } catch (Throwable error) {
            Log.e(TAG, label + " native menu hook registration failed", error);
        }
    }

    private static Context settingsApplication(ClassLoader loader) {
        Object application = XposedHelpers.callStaticMethod(
                XposedHelpers.findClass("android.app.ActivityThread", loader),
                "currentApplication");
        if (!(application instanceof Context)) return null;
        Context context = (Context) application;
        return SETTINGS.equals(context.getPackageName()) ? context : null;
    }
}
