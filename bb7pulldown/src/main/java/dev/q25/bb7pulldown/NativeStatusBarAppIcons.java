package dev.q25.bb7pulldown;

import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.drawable.Drawable;
import android.service.notification.StatusBarNotification;
import android.util.Log;
import android.widget.ImageView;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * Color app icons in the native status bar without creating an overlay or
 * replacing SystemUI's notification pipeline.
 *
 * LineageOS 24 IconManager owns separate status-bar, shelf, and AOD icon views.
 * Only its status-bar view is registered here. SystemUI retains the underlying
 * StatusBarIcon, ordering, visibility, overflow dots, and content description.
 */
final class NativeStatusBarAppIcons {
    private static final String TAG = "BB7-StatusIcons";
    private static final String ICON_MANAGER =
            "com.android.systemui.statusbar.notification.icon.IconManager";
    private static final String ICON_VIEW =
            "com.android.systemui.statusbar.StatusBarIconView";

    // Weak keys allow removed native notification views to be collected.
    private static final Map<ImageView, String> COLORED =
            Collections.synchronizedMap(new WeakHashMap<>());
    private static boolean installed;

    private NativeStatusBarAppIcons() {}

    static void install(ClassLoader loader) {
        if (installed) return;
        Class<?> manager = XposedHelpers.findClassIfExists(ICON_MANAGER, loader);
        Class<?> iconView = XposedHelpers.findClassIfExists(ICON_VIEW, loader);
        if (manager == null || iconView == null) {
            Log.w(TAG, "Native status-bar icon pipeline unavailable; stock icons retained");
            return;
        }
        try {
            XC_MethodHook created = new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    if (param.hasThrowable()) return;
                    // IconManager also creates BundleEntry icons. These have
                    // no StatusBarNotification; they are left entirely native.
                    for (Object arg : param.args) {
                        if (arg == null || !arg.getClass().getName().equals(
                                "com.android.systemui.statusbar.notification.collection.NotificationEntry")) {
                            continue;
                        }
                        try {
                            Object pack = XposedHelpers.callMethod(arg, "getIcons");
                            if (pack != null) colorView(
                                    XposedHelpers.callMethod(pack, "getStatusBarIcon"));
                        } catch (Throwable error) {
                            Log.w(TAG, "Unable to resolve status-bar notification icon", error);
                        }
                        break;
                    }
                }
            };
            XposedBridge.hookAllMethods(manager, "createIcons", created);
            XposedBridge.hookAllMethods(manager, "updateIcons", created);

            // Connected displays can create or update an individual status-bar
            // icon independently of the default display's IconPack.
            XposedBridge.hookAllMethods(manager, "createSbIconView", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    if (!param.hasThrowable()) colorView(param.getResult());
                }
            });
            XposedBridge.hookAllMethods(manager, "updateSbIcon", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    if (!param.hasThrowable() && param.args.length > 1) {
                        colorView(param.args[1]);
                    }
                }
            });

            // The existing status-bar color binder calls these methods when
            // changing wallpaper contrast, theme, or notification animations.
            // NO_COLOR is SystemUI's native "do not tint the artwork" value.
            XC_MethodHook preserveFullColor = new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    if (param.thisObject instanceof ImageView
                            && COLORED.containsKey(param.thisObject)
                            && param.args.length > 0) {
                        param.args[0] = 0;
                    }
                }
            };
            XposedBridge.hookAllMethods(iconView, "setStaticDrawableColor", preserveFullColor);
            XposedBridge.hookAllMethods(iconView, "setIconColor", preserveFullColor);

            // SystemUI reloads notification artwork for RTL/configuration
            // changes. Reapply the package icon, not a stale small-icon glyph.
            XposedBridge.hookAllMethods(iconView, "updateDrawable", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    if (!param.hasThrowable() && COLORED.containsKey(param.thisObject)) {
                        colorView(param.thisObject);
                    }
                }
            });

            // Legacy dark-mode paths directly set ImageView tint lists rather
            // than using the newer static drawable color binder.
            XposedBridge.hookAllMethods(iconView, "onDarkChanged", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    if (COLORED.containsKey(param.thisObject)) {
                        ((ImageView) param.thisObject).setImageTintList(null);
                    }
                }
            });
            installed = true;
            Log.i(TAG, "Full-color native notification status-bar icons registered");
        } catch (Throwable error) {
            Log.e(TAG, "Could not install status-bar app icon hooks", error);
        }
    }

    private static void colorView(Object candidate) {
        if (!(candidate instanceof ImageView)) return;
        ImageView view = (ImageView) candidate;
        try {
            Object notification = XposedHelpers.callMethod(view, "getNotification");
            if (!(notification instanceof StatusBarNotification)) return;
            StatusBarNotification sbn = (StatusBarNotification) notification;
            String packageName = sbn.getPackageName();
            if (!StatusBarAppIconPolicy.shouldReplace(true, packageName)) return;

            Drawable drawable = loadAppIcon(view.getContext(), sbn);
            if (drawable == null) return; // Keep the native icon if lookup fails.

            COLORED.put(view, packageName);
            // Preserve the native status-bar dimensions and container: the
            // drawable is the only thing replaced.
            view.setImageDrawable(drawable);
            // A tint list can survive drawable replacement. The native status
            // bar still owns overflow-dot tint via setDecorColor().
            view.setImageTintList((ColorStateList) null);
            view.setColorFilter(null);
            XposedHelpers.callMethod(view, "setStaticDrawableColor", 0);
        } catch (Throwable error) {
            Log.w(TAG, "Keeping native notification icon after app-icon error", error);
        }
    }

    private static Drawable loadAppIcon(Context context, StatusBarNotification sbn) {
        // SystemUI's own notification icon loader uses the notification's
        // package context, which also handles notifications from other users.
        try {
            Context app = (Context) XposedHelpers.callMethod(
                    sbn, "getPackageContext", context);
            if (app != null) {
                return app.getApplicationInfo().loadIcon(app.getPackageManager());
            }
        } catch (Throwable ignored) {
            // A missing package context is not a fatal SystemUI error.
        }
        try {
            return context.getPackageManager().getApplicationIcon(sbn.getPackageName());
        } catch (PackageManager.NameNotFoundException | RuntimeException ignored) {
            return null;
        }
    }
}
