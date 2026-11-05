package dev.q25.bb7pulldown;

import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.service.notification.StatusBarNotification;
import android.util.Log;
import android.widget.ImageView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;

/**
 * Full-colour application artwork in SystemUI's existing notification status bar.
 *
 * Verified against Q25 SystemUI APK SHA-256:
 * 65003bbe5b00146a0e20d8612660426be05e571cce22d244fdcd656a2b3be8c0
 *
 * The Q25 R8 build has NotificationEntry.mIcons, IconPack.mStatusBarIcon,
 * and StatusBarIconView.mNotification. Upstream IconManager accessors and
 * StatusBarIconView.getNotification()/setIconColor() are NOT available here.
 * No status-bar view, layout, notification or icon container is replaced.
 */
final class NativeStatusBarColorIcons {
    private static final String TAG = "BB7-StatusIcons";
    private static final String PROP = "debug.bb7pulldown.color_icons.enabled";
    private static final String ICON_MANAGER =
            "com.android.systemui.statusbar.notification.icon.IconManager";
    private static final String NOTIFICATION_ENTRY =
            "com.android.systemui.statusbar.notification.collection.NotificationEntry";
    private static final String ICON_VIEW =
            "com.android.systemui.statusbar.StatusBarIconView";

    // Only the exact IconPack.mStatusBarIcon views are registered. Shelf, AOD,
    // bundle icons and system status indicators are deliberately excluded.
    private static final Map<ImageView, String> TRACKED =
            Collections.synchronizedMap(new WeakHashMap<>());
    private static boolean installed;
    private static boolean firstRenderLogged;

    private NativeStatusBarColorIcons() {}

    static void install(ClassLoader loader) {
        if (installed) return;
        if (!enabled()) {
            Log.i(TAG, "Full-colour status-bar icons disabled by " + PROP);
            return;
        }
        Class<?> manager = XposedHelpers.findClassIfExists(ICON_MANAGER, loader);
        Class<?> entry = XposedHelpers.findClassIfExists(NOTIFICATION_ENTRY, loader);
        Class<?> iconView = XposedHelpers.findClassIfExists(ICON_VIEW, loader);
        if (manager == null || entry == null || iconView == null) {
            Log.w(TAG, "Q25 native notification icon classes missing; stock rendering retained");
            return;
        }

        // Preflight exact compiled Q25 signatures before installing any hooks.
        try {
            manager.getDeclaredMethod("createIcons", entry);
            manager.getDeclaredMethod("updateIcons", entry, boolean.class);
            iconView.getDeclaredMethod("setStaticDrawableColor", int.class);
            iconView.getDeclaredMethod("updateDrawable", boolean.class);
            iconView.getDeclaredMethod("onDarkChanged",
                    ArrayList.class, float.class, int.class);
        } catch (NoSuchMethodException error) {
            Log.w(TAG, "Q25 notification icon signatures differ; stock rendering retained",
                    error);
            return;
        }

        try {
            XC_MethodHook afterIconPackChange = new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    if (param.hasThrowable() || param.args.length == 0) return;
                    registerStatusIcon(param.args[0]);
                }
            };
            XposedHelpers.findAndHookMethod(manager, "createIcons", entry,
                    afterIconPackChange);
            XposedHelpers.findAndHookMethod(manager, "updateIcons", entry, boolean.class,
                    afterIconPackChange);

            // The native colour controller can run after icon inflation and
            // on wallpaper/contrast changes. Zero is this class's NO_COLOR.
            XposedHelpers.findAndHookMethod(iconView, "setStaticDrawableColor",
                    int.class, new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam param) {
                            if (TRACKED.containsKey(param.thisObject)) param.args[0] = 0;
                        }
                    });

            // A source-icon update can replace our image without replacing the
            // native view. Restore artwork only after a successful native load.
            XposedHelpers.findAndHookMethod(iconView, "updateDrawable",
                    boolean.class, new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam param) {
                            if (!param.hasThrowable() && Boolean.TRUE.equals(param.getResult())) {
                                refreshTrackedView(param.thisObject);
                            }
                        }
                    });

            // This compiled Q25 class still uses ImageView's tint list here.
            XposedHelpers.findAndHookMethod(iconView, "onDarkChanged",
                    ArrayList.class, float.class, int.class, new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam param) {
                            if (param.hasThrowable() || !(param.thisObject instanceof ImageView)
                                    || !TRACKED.containsKey(param.thisObject)) return;
                            ImageView view = (ImageView) param.thisObject;
                            view.setImageTintList(null);
                            view.clearColorFilter();
                        }
                    });

            installed = true;
            Log.i(TAG, "Q25 native full-colour notification icon hooks registered");
        } catch (Throwable error) {
            Log.e(TAG, "Could not install Q25 native notification icon hooks", error);
        }
    }

    private static void registerStatusIcon(Object entry) {
        if (entry == null) return;
        try {
            // Actual fields from the uploaded Q25 APK; neither getIcons()
            // nor getNotification() is assumed to exist at runtime.
            Object pack = XposedHelpers.getObjectField(entry, "mIcons");
            if (pack == null) return;
            Object candidate = XposedHelpers.getObjectField(pack, "mStatusBarIcon");
            if (!(candidate instanceof ImageView)) return;
            ImageView view = (ImageView) candidate;

            Object source = XposedHelpers.getObjectField(entry, "mSbn");
            Object bound = XposedHelpers.getObjectField(view, "mNotification");
            if (!(source instanceof StatusBarNotification)
                    || !(bound instanceof StatusBarNotification)) return;
            StatusBarNotification sbn = (StatusBarNotification) source;
            StatusBarNotification attached = (StatusBarNotification) bound;
            boolean conversation = XposedHelpers.getBooleanField(view, "mShowsConversation");
            if (!StatusBarColorIconPolicy.eligible(sbn.getKey(), attached.getKey(),
                    sbn.getPackageName(), conversation)) {
                TRACKED.remove(view);
                return;
            }

            TRACKED.put(view, sbn.getPackageName());
            applyColorArtwork(view, sbn);
        } catch (Throwable error) {
            Log.w(TAG, "Could not resolve Q25 notification status-bar icon", error);
        }
    }

    private static void refreshTrackedView(Object candidate) {
        if (!(candidate instanceof ImageView)) return;
        ImageView view = (ImageView) candidate;
        String pkg = TRACKED.get(view);
        if (pkg == null) return;
        try {
            Object value = XposedHelpers.getObjectField(view, "mNotification");
            if (!(value instanceof StatusBarNotification)) {
                TRACKED.remove(view);
                return;
            }
            StatusBarNotification sbn = (StatusBarNotification) value;
            if (!pkg.equals(sbn.getPackageName())
                    || XposedHelpers.getBooleanField(view, "mShowsConversation")) {
                TRACKED.remove(view);
                return;
            }
            applyColorArtwork(view, sbn);
        } catch (Throwable error) {
            Log.w(TAG, "Could not refresh native colour notification icon", error);
        }
    }

    private static void applyColorArtwork(ImageView view, StatusBarNotification sbn) {
        Drawable appIcon = loadApplicationIcon(view.getContext(), sbn);
        if (appIcon == null) {
            TRACKED.remove(view);
            return; // The native small icon has already been drawn.
        }
        try {
            // Supplying a plain drawable directly bypasses SystemUI's small-
            // icon AdaptiveIconDrawable monochrome extraction.
            view.setImageDrawable(appIcon.mutate());
            view.setImageTintList(null);
            XposedHelpers.callMethod(view, "setStaticDrawableColor", 0);
            view.clearColorFilter();
            if (!firstRenderLogged) {
                firstRenderLogged = true;
                Log.i(TAG, "Rendered first full-colour native status-bar app icon");
            }
        } catch (Throwable error) {
            TRACKED.remove(view);
            Log.w(TAG, "Could not apply full-colour notification artwork", error);
        }
    }

    private static Drawable loadApplicationIcon(Context context, StatusBarNotification sbn) {
        try {
            // Prefer the notification's own package/user context (work profiles).
            Context appContext = (Context) XposedHelpers.callMethod(
                    sbn, "getPackageContext", context);
            if (appContext != null) {
                Drawable icon = appContext.getApplicationInfo()
                        .loadIcon(appContext.getPackageManager());
                if (icon != null) return icon;
            }
        } catch (Throwable ignored) {
            // Continue with the native PackageManager fallback.
        }
        try {
            return context.getPackageManager().getApplicationIcon(sbn.getPackageName());
        } catch (PackageManager.NameNotFoundException | RuntimeException ignored) {
            return null;
        }
    }

    private static boolean enabled() {
        try {
            Class<?> props = Class.forName("android.os.SystemProperties");
            return (Boolean) XposedHelpers.callStaticMethod(
                    props, "getBoolean", PROP, true);
        } catch (Throwable error) {
            Log.w(TAG, "Could not read colour icon rollback property; keeping stock icons",
                    error);
            return false;
        }
    }
}
