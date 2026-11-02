package dev.q25.bb7pulldown;

import android.app.KeyguardManager;
import android.content.Context;
import android.content.BroadcastReceiver;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.PixelFormat;
import android.os.SystemClock;
import android.util.Log;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Fresh BBOS7-inspired top-edge gesture prototype; independent of the
 * previous experimental notification shade module.
 *
 * The panel presents live, app-specific notification summaries, not native QS.
 * Touches starting on the phone's native status bar are exclusively owned
 * by this gesture when debug.bb7pulldown.enabled is true. Otherwise stock
 * Android remains untouched.
 *
 * Emergency rollback:
 *   adb shell setprop debug.bb7pulldown.enabled false
 *   adb shell am crash com.android.systemui
 */
public final class PullDownHook implements IXposedHookLoadPackage {
    private static final String TAG = "BB7-PullDown";
    private static final String PACKAGE = "com.android.systemui";
    private static final String STATUS_BAR =
            "com.android.systemui.statusbar.phone.PhoneStatusBarView";
    private static final String ENABLE_PROP = "debug.bb7pulldown.enabled";
    // Android internal WindowManager.LayoutParams.TYPE_STATUS_BAR_SUB_PANEL
    // is hidden from public SDK stubs; framework value is FIRST_SYSTEM_WINDOW+17.
    private static final int STATUS_BAR_SUB_PANEL_WINDOW_TYPE = 2017;
    private static final Map<View, SwipeState> SWIPES =
            Collections.synchronizedMap(new WeakHashMap<>());
    private static boolean shown;
    private static WindowManager windowManager;
    private static TwoColumnPanel panel;
    private static boolean openingOrClosing;
    private static BroadcastReceiver screenOffReceiver;
    private static Context receiverContext;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lp) {
        if (!PACKAGE.equals(lp.packageName) || !PACKAGE.equals(lp.processName)) return;

        if (!enabled()) {
            Log.i(TAG, "Disabled by " + ENABLE_PROP + "; stock status bar retained");
            return;
        }
        SystemUiNotificationObserver.install(lp.classLoader);
        Class<?> viewClass = XposedHelpers.findClassIfExists(STATUS_BAR, lp.classLoader);
        if (viewClass == null) {
            Log.w(TAG, "Native status-bar gesture source not present");
            return;
        }
        try {
            XposedBridge.hookAllMethods(viewClass, "dispatchTouchEvent", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (!(param.thisObject instanceof View) || param.args.length != 1
                            || !(param.args[0] instanceof MotionEvent)) return;
                    View view = (View) param.thisObject;
                    MotionEvent event = (MotionEvent) param.args[0];
                    try {
                        if (handleTopEdgeTouch(view, event)) param.setResult(true);
                    } catch (Throwable e) {
                        Log.e(TAG, "Top-edge gesture failed; native dispatch retained", e);
                    }
                }
            });
            Log.i(TAG, "Full-width pull-down edge gesture registered; ratio=15/85");
        } catch (Throwable error) {
            Log.e(TAG, "Unable to register global status-bar gesture", error);
        }
    }

    private static boolean enabled() {
        try {
            Class<?> props = Class.forName("android.os.SystemProperties");
            return (Boolean) XposedHelpers.callStaticMethod(
                    props, "getBoolean", ENABLE_PROP, false);
        } catch (Throwable error) {
            Log.e(TAG, "Cannot read rollback property; leaving stock shade", error);
            return false;
        }
    }

    private static boolean locked(Context context) {
        KeyguardManager manager = (KeyguardManager)
                context.getSystemService(Context.KEYGUARD_SERVICE);
        return manager == null || manager.isKeyguardLocked();
    }

    private static boolean handleTopEdgeTouch(View view, MotionEvent event) {
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            if (!enabled() || shown || openingOrClosing || locked(view.getContext())
                    || event.getPointerCount() != 1
                    || event.getToolType(0) != MotionEvent.TOOL_TYPE_FINGER) return false;
            SwipeState state = new SwipeState(event.getRawY(), event.getRawX());
            SWIPES.put(view, state);
            return true;
        }
        SwipeState state = SWIPES.get(view);
        if (state == null) return false;
        switch (action) {
            case MotionEvent.ACTION_MOVE:
                float deltaY = event.getRawY() - state.startY;
                float deltaX = Math.abs(event.getRawX() - state.startX);
                int thresholdPx = dp(view.getContext(), 48);
                if (!state.opened && deltaY >= thresholdPx && deltaY > deltaX) {
                    state.opened = true;
                    showPanel(view.getContext());
                }
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
            case MotionEvent.ACTION_POINTER_DOWN:
                SWIPES.remove(view);
                return true;
            default:
                return true;
        }
    }

    static void onNotificationItemsUpdated() {
        TwoColumnPanel current = panel;
        if (current == null || !shown) return;
        current.post(() -> {
            if (current == panel && shown) {
                if (locked(current.getContext())) {
                    dismissPanelImmediately();
                } else {
                    current.setNotifications(SystemUiNotificationObserver.itemsSnapshot());
                }
            }
        });
    }

    private static void openNotification(String key) {
        TwoColumnPanel current = panel;
        if (current == null || !shown || openingOrClosing) return;
        if (locked(current.getContext())) {
            dismissPanelImmediately();
            return;
        }
        if (SystemUiNotificationObserver.openNotification(key)) {
            dismissPanelImmediately();
        }
    }

    private static void showPanel(Context context) {
        if (shown || openingOrClosing || !enabled() || locked(context)) return;
        openingOrClosing = true;
        WindowManager manager = (WindowManager)
                context.getSystemService(Context.WINDOW_SERVICE);
        if (manager == null) {
            openingOrClosing = false;
            Log.w(TAG, "WindowManager unavailable");
            return;
        }
        // Query the live, system-owned notification listener before drawing.
        // The gesture and 15/85 geometry are unchanged.
        SystemUiNotificationObserver.refreshBeforeOpen();
        TwoColumnPanel root = new TwoColumnPanel(
                context, PullDownHook::dismissPanel, PullDownHook::openNotification);
        root.setNotifications(SystemUiNotificationObserver.itemsSnapshot());
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                STATUS_BAR_SUB_PANEL_WINDOW_TYPE,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.OPAQUE);
        params.setTitle("BB7 Pull-down");
        params.gravity = Gravity.TOP | Gravity.START;
        params.setFitInsetsTypes(0);
        params.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        int height = context.getResources().getDisplayMetrics().heightPixels;
        root.setTranslationY(-height);
        try {
            manager.addView(root, params);
            // A full-screen SystemUI sub-panel must close when the device
            // sleeps. Don't expose notification previews over the keyguard.
            try {
                registerScreenOffReceiver(context);
            } catch (Throwable registrationError) {
                manager.removeViewImmediate(root);
                throw registrationError;
            }
            panel = root;
            windowManager = manager;
            shown = true;
            Log.i(TAG, "Two-column window attached; fullWidth=true left=15 right=85"
                    + " heightPx=" + height);
            root.post(() -> {
                root.animate().translationY(0f).setDuration(220).withEndAction(() -> {
                    openingOrClosing = false;
                    root.focusSelectedAppIcon();
                }).start();
            });
        } catch (Throwable error) {
            openingOrClosing = false;
            shown = false;
            panel = null;
            windowManager = null;
            Log.e(TAG, "Unable to display full-width pull-down; stock system unaffected", error);
        }
    }

    private static void dismissPanel() {
        if (!shown || openingOrClosing || panel == null) return;
        openingOrClosing = true;
        TwoColumnPanel root = panel;
        int height = root.getHeight();
        if (height <= 0) height = root.getResources().getDisplayMetrics().heightPixels;
        root.animate().translationY(-height).setDuration(180)
                .withEndAction(() -> {
                    try {
                        if (windowManager != null) windowManager.removeViewImmediate(root);
                    } catch (Throwable e) {
                        Log.w(TAG, "Unable to remove pull-down cleanly", e);
                    } finally {
                        unregisterScreenOffReceiver();
                        panel = null;
                        windowManager = null;
                        shown = false;
                        openingOrClosing = false;
                        Log.i(TAG, "Two-column pull-down dismissed");
                    }
                }).start();
    }

    private static void registerScreenOffReceiver(Context context) {
        if (screenOffReceiver != null) return;
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent intent) {
                if (Intent.ACTION_SCREEN_OFF.equals(intent.getAction())) {
                    dismissPanelImmediately();
                }
            }
        };
        context.registerReceiver(receiver, new IntentFilter(Intent.ACTION_SCREEN_OFF));
        screenOffReceiver = receiver;
        receiverContext = context;
    }

    private static void unregisterScreenOffReceiver() {
        BroadcastReceiver receiver = screenOffReceiver;
        Context context = receiverContext;
        screenOffReceiver = null;
        receiverContext = null;
        if (receiver != null && context != null) {
            try {
                context.unregisterReceiver(receiver);
            } catch (IllegalArgumentException ignored) { }
        }
    }

    private static void dismissPanelImmediately() {
        TwoColumnPanel current = panel;
        if (current == null) return;
        try {
            current.animate().cancel();
            if (windowManager != null) windowManager.removeViewImmediate(current);
        } catch (Throwable error) {
            Log.w(TAG, "Unable to immediately hide notification panel", error);
        } finally {
            unregisterScreenOffReceiver();
            panel = null;
            windowManager = null;
            shown = false;
            openingOrClosing = false;
            Log.i(TAG, "Two-column pull-down hidden on lock/screen off");
        }
    }

    private static int dp(Context context, int value) {
        return (int) (value * context.getResources().getDisplayMetrics().density + 0.5f);
    }

    private static final class SwipeState {
        final float startY;
        final float startX;
        boolean opened;
        SwipeState(float y, float x) { startY = y; startX = x; }
    }
}
