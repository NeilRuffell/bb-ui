package dev.q25.bb7shade;

import android.app.KeyguardManager;
import android.content.Context;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * An early, visibly incomplete layout built INSIDE the stock SystemUI shade.
 * There is never an additional window or a second set of notification cards.
 *
 * We insert only the narrow application rail and constrain the native NSSL
 * alongside it. Android continues to own, render and operate every row.
 * Quick Settings stays stock in this stage.
 *
 * ADB rollback, without a reboot or uninstall:
 *   adb shell setprop debug.bb7shade.layout false
 *   adb shell am crash com.android.systemui
 *
 * The switch is checked during the next SystemUI process initialization.
 */
final class ShadeRailHost {
    private static final String TAG = "BB7-Shade";
    private static final String CONTAINER =
            "com.android.systemui.shade.NotificationsQuickSettingsContainer";
    private static final String ENABLE_PROP = "debug.bb7shade.layout";
    private static final int NO_CONSTRAINT = -1;
    private static final int PARENT_ID = 0;

    private static final Map<ViewGroup, WeakReference<ShadeRailHost>> HOSTS =
            Collections.synchronizedMap(new WeakHashMap<>());

    private final ViewGroup parent;
    private final View nativeStack;
    private final BB7AppRailView rail;
    private final BB7NotificationPageModel model = new BB7NotificationPageModel();
    private final int originalStartToStart;
    private final int originalStartToEnd;
    private final int originalWidth;
    private final boolean originalVisibility;
    private boolean railVisible;
    private boolean pendingRefresh;

    private ShadeRailHost(ViewGroup container, View stack, BB7AppRailView appRail) {
        parent = container;
        nativeStack = stack;
        rail = appRail;
        ViewGroup.LayoutParams originalParams = stack.getLayoutParams();
        originalStartToStart = XposedHelpers.getIntField(originalParams, "startToStart");
        originalStartToEnd = XposedHelpers.getIntField(originalParams, "startToEnd");
        originalWidth = originalParams.width;
        originalVisibility = appRail.getVisibility() == View.VISIBLE;
    }

    static void installHooks(ClassLoader loader) {
        if (!isEnabledByProperty()) {
            Log.i(TAG, "Experimental app rail disabled by " + ENABLE_PROP
                    + "; stock shade unchanged");
            return;
        }
        Class<?> container = XposedHelpers.findClassIfExists(CONTAINER, loader);
        if (container == null) {
            Log.w(TAG, "Notification/QS container missing in this classloader");
            return;
        }
        try {
            XposedBridge.hookAllMethods(container, "onFinishInflate", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (!(param.thisObject instanceof ViewGroup)) return;
                    try {
                        install((ViewGroup) param.thisObject);
                    } catch (Throwable error) {
                        // Failure must not prevent the native shade from inflating.
                        Log.e(TAG, "Unable to attach BB7 app rail; using stock shade", error);
                    }
                }
            });
            Log.i(TAG, "Experimental in-shade app rail hook registered");
        } catch (Throwable error) {
            Log.e(TAG, "Unable to register in-shade app rail", error);
        }
    }

    private static boolean isEnabledByProperty() {
        try {
            Class<?> props = Class.forName("android.os.SystemProperties");
            return (Boolean) XposedHelpers.callStaticMethod(
                    props, "getBoolean", ENABLE_PROP, true);
        } catch (Throwable error) {
            // No verified rollback switch -> don't modify the shade.
            Log.e(TAG, "Cannot read experimental layout safety switch", error);
            return false;
        }
    }

    private static void install(ViewGroup parent) {
        synchronized (HOSTS) {
            WeakReference<ShadeRailHost> existing = HOSTS.get(parent);
            if (existing != null && existing.get() != null) return;
        }

        Context context = parent.getContext();
        int stackId = context.getResources().getIdentifier(
                "notification_stack_scroller", "id", "com.android.systemui");
        if (stackId == 0) {
            Log.w(TAG, "Native notification stack id unavailable; stock layout retained");
            return;
        }
        View nativeStack = parent.findViewById(stackId);
        if (nativeStack == null || nativeStack.getParent() != parent) {
            Log.w(TAG, "Native stack is not a direct shade child; stock layout retained");
            return;
        }
        ViewGroup.LayoutParams originalParams = nativeStack.getLayoutParams();
        if (XposedHelpers.findFieldIfExists(originalParams.getClass(), "startToStart") == null
                || XposedHelpers.findFieldIfExists(originalParams.getClass(), "startToEnd") == null
                || XposedHelpers.findFieldIfExists(originalParams.getClass(), "topToTop") == null
                || XposedHelpers.findFieldIfExists(originalParams.getClass(), "bottomToBottom") == null) {
            Log.w(TAG, "Native stack has unexpected layout constraints; stock layout retained");
            return;
        }

        BB7NotificationPageModel pageModel = new BB7NotificationPageModel();
        BB7AppRailView rail = new BB7AppRailView(context, pageModel.navigation());
        int screenWidthPx = context.getResources().getDisplayMetrics().widthPixels;
        if (screenWidthPx < 240) {
            Log.w(TAG, "Shade display too narrow for experimental rail; stock retained");
            return;
        }
        int railWidth = Math.min(rail.preferredWidthPx(), screenWidthPx / 4);
        rail.setId(View.generateViewId());
        rail.setVisibility(View.GONE);
        rail.setContentDescription("Notification application selector");

        // Native ConstraintLayout.LayoutParams(int width, int height).
        // No AndroidX class is bundled into our Vector APK; use the
        // exact LayoutParams class loaded by the running SystemUI.
        Object railLayout = XposedHelpers.newInstance(
                originalParams.getClass(), railWidth, 0);
        XposedHelpers.setIntField(railLayout, "startToStart", PARENT_ID);
        XposedHelpers.setIntField(railLayout, "topToTop", PARENT_ID);
        XposedHelpers.setIntField(railLayout, "bottomToBottom", PARENT_ID);

        ShadeRailHost host = new ShadeRailHost(parent, nativeStack, rail);
        // The rail is inserted before the stack so notification cards retain
        // their normal drawing order. There is no extra window or duplicate
        // card list.
        int stackIndex = parent.indexOfChild(nativeStack);
        parent.addView(rail, stackIndex, (ViewGroup.LayoutParams) railLayout);
        synchronized (HOSTS) {
            HOSTS.put(parent, new WeakReference<>(host));
        }
        rail.setTag(host);
        // This stage intentionally does not hide or filter native rows:
        // replacing their visibility here would interfere with NSSL's
        // sensitive/heads-up/grouped-notification layout calculations.
        rail.setOnApplicationSelectedListener(packageName ->
                Log.i(TAG, "Application rail selection changed; native rows remain unfiltered"));
        host.railVisible = false;
        host.model.acceptSnapshot(ShadeNotificationIndex.current());
        rail.update(ShadeNotificationIndex.current());
        host.refreshKeyguardState();
        Log.i(TAG, "BB7 app rail mounted in native shade container"
                + " widthPx=" + railWidth
                + " stackId=" + nativeStack.getId()
                + " originalStackWidth=" + originalParams.width);
    }

    /** Called from the existing native notification stack onLayout hook. */
    static void onNativeStackLayout(View stack) {
        if (!(stack.getParent() instanceof ViewGroup)) return;
        ViewGroup container = (ViewGroup) stack.getParent();
        WeakReference<ShadeRailHost> ref;
        synchronized (HOSTS) {
            ref = HOSTS.get(container);
        }
        ShadeRailHost host = ref == null ? null : ref.get();
        if (host == null || host.nativeStack != stack) return;
        host.refreshKeyguardState();
    }

    private void refreshKeyguardState() {
        KeyguardManager keyguard = (KeyguardManager)
                parent.getContext().getSystemService(Context.KEYGUARD_SERVICE);
        // Keep both the icon rail and the notification stack's stock width
        // unchanged on the lock screen. Never override the platform's
        // notification privacy or keyguard rendering.
        boolean wantVisible = keyguard != null && !keyguard.isKeyguardLocked();
        if (wantVisible == railVisible) return;

        ViewGroup.LayoutParams params = nativeStack.getLayoutParams();
        try {
            if (wantVisible) {
                XposedHelpers.setIntField(params, "startToStart", NO_CONSTRAINT);
                XposedHelpers.setIntField(params, "startToEnd", rail.getId());
                params.width = 0;  // Match remaining ConstraintLayout width.
                rail.setVisibility(View.VISIBLE);
            } else {
                XposedHelpers.setIntField(params, "startToStart", originalStartToStart);
                XposedHelpers.setIntField(params, "startToEnd", originalStartToEnd);
                params.width = originalWidth;
                rail.setVisibility(View.GONE);
            }
            nativeStack.setLayoutParams(params);
            railVisible = wantVisible;
            Log.i(TAG, wantVisible ? "BB7 two-column notification layout activated"
                    : "BB7 rail hidden; stock keyguard notification layout restored");
        } catch (Throwable error) {
            // Ensure the native notification view can keep drawing even if
            // constraints don't match this device's SystemUI revision.
            try {
                XposedHelpers.setIntField(params, "startToStart", originalStartToStart);
                XposedHelpers.setIntField(params, "startToEnd", originalStartToEnd);
                params.width = originalWidth;
                nativeStack.setLayoutParams(params);
                rail.setVisibility(View.GONE);
                railVisible = false;
            } catch (Throwable ignored) {
                Log.e(TAG, "Unable to restore original stack constraints", ignored);
            }
            Log.e(TAG, "BB7 app rail layout update failed; reverted to native", error);
        }
    }

    static void onSnapshotChanged(ShadeNotificationIndex.Snapshot snapshot) {
        // Both pipeline and native-stack callbacks occur on SystemUI's main
        // thread on this device. Post reconciliation to avoid mutating the
        // shade child hierarchy inside Android's layout/render callbacks.
        List<ShadeRailHost> current = new ArrayList<>();
        synchronized (HOSTS) {
            for (WeakReference<ShadeRailHost> ref : HOSTS.values()) {
                ShadeRailHost host = ref.get();
                if (host != null) current.add(host);
            }
        }
        for (ShadeRailHost host : current) host.scheduleRefresh();
    }

    private void scheduleRefresh() {
        if (pendingRefresh) return;
        pendingRefresh = true;
        rail.post(() -> {
            pendingRefresh = false;
            try {
                ShadeNotificationIndex.Snapshot next = ShadeNotificationIndex.current();
                model.acceptSnapshot(next);
                rail.update(next);
            } catch (Throwable error) {
                Log.e(TAG, "Application rail update failed", error);
            }
        });
    }
}
