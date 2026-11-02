package dev.q25.bb7pulldown;

import android.app.KeyguardManager;
import android.content.Context;
import android.content.BroadcastReceiver;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.PixelFormat;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.widget.FrameLayout;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.Drawable;
import java.lang.ref.WeakReference;
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
    // A Q25-specific code must be established from hardware before enabling.
    private static final String BB_KEYCODE_PROP = "debug.bb7pulldown.bb_keycode";
    private static final String FOCUS_ACTION = "dev.q25.bb7pulldown.FOCUS_STATUS";
    private static final String FOCUS_PERMISSION = "android.permission.STATUS_BAR_SERVICE";
    private static final String SYSTEM_SERVER = "android";
    private static final String INPUT_POLICY = "com.android.server.policy.PhoneWindowManager";
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
    private static WeakReference<View> nativeStatusBar = new WeakReference<>(null);
    private static Drawable focusOutline;
    private static WindowManager focusManager;
    private static View focusWindow;
    private static int focusZone;
    private static BroadcastReceiver focusReceiver;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lp) {
        if (SYSTEM_SERVER.equals(lp.packageName) && SYSTEM_SERVER.equals(lp.processName)) {
            installInputPolicy(lp.classLoader);
            return;
        }
        if (!PACKAGE.equals(lp.packageName) || !PACKAGE.equals(lp.processName)) return;

        if (!enabled()) {
            Log.i(TAG, "Disabled by " + ENABLE_PROP + "; stock status bar retained");
            return;
        }
        installFocusReceiver();
        SystemUiNotificationObserver.install(lp.classLoader);
        NativeNotificationClicks.install(lp.classLoader);
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
                    nativeStatusBar = new WeakReference<>(view);
                    MotionEvent event = (MotionEvent) param.args[0];
                    try {
                        if (handleTopEdgeTouch(view, event)) param.setResult(true);
                    } catch (Throwable e) {
                        Log.e(TAG, "Top-edge gesture failed; native dispatch retained", e);
                    }
                }
            });
            // Native status bar remains the owner of clock, icons and status.
            XposedBridge.hookAllConstructors(viewClass, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    if (param.thisObject instanceof View) {
                        nativeStatusBar = new WeakReference<>((View) param.thisObject);
                        registerFocusReceiver(((View) param.thisObject).getContext());
                    }
                }
            });
            Log.i(TAG, "Status-bar gesture and two-zone navigation registered");
        } catch (Throwable error) {
            Log.e(TAG, "Unable to register global status-bar gesture", error);
        }
    }

    private static int configuredKeycode() {
        try {
            Class<?> props = Class.forName("android.os.SystemProperties");
            String value = (String) XposedHelpers.callStaticMethod(
                    props, "get", BB_KEYCODE_PROP, "");
            int code = Integer.parseInt(value);
            return code > 0 && code <= KeyEvent.getMaxKeyCode() ? code : -1;
        } catch (Throwable error) {
            return -1;
        }
    }

    private static void installInputPolicy(ClassLoader loader) {
        Class<?> policy = XposedHelpers.findClassIfExists(INPUT_POLICY, loader);
        if (policy == null) {
            Log.w(TAG, "Global BlackBerry-key hook: PhoneWindowManager unavailable");
            return;
        }
        try {
            XposedBridge.hookAllMethods(policy, "interceptKeyBeforeDispatching",
                    new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    if (!enabled() || configuredKeycode() < 0) return;
                    KeyEvent event = null;
                    for (Object arg : param.args) {
                        if (arg instanceof KeyEvent) { event = (KeyEvent) arg; break; }
                    }
                    if (event == null || event.getKeyCode() != configuredKeycode()) return;
                    // Do not steal long-press repeats or device wake/power behavior.
                    if (event.getRepeatCount() > 0) {
                        param.setResult(-1L);
                        return;
                    }
                    if (event.getAction() == KeyEvent.ACTION_UP) {
                        try {
                            Context context = (Context) XposedHelpers.getObjectField(
                                    param.thisObject, "mContext");
                            Intent intent = new Intent(FOCUS_ACTION);
                            intent.setPackage(PACKAGE);
                            context.sendBroadcast(intent);
                            Log.i(TAG, "BlackBerry key status-bar focus requested");
                        } catch (Throwable error) {
                            Log.e(TAG, "BlackBerry key focus broadcast failed", error);
                        }
                    }
                    param.setResult(-1L);
                }
            });
            Log.i(TAG, "BlackBerry key policy observer installed (inactive until keycode set)");
        } catch (Throwable error) {
            Log.e(TAG, "Unable to install BlackBerry key policy hook", error);
        }
    }

    private static void installFocusReceiver() {
        // This receiver only accepts broadcasts from callers holding the
        // platform STATUS_BAR_SERVICE permission (system_server).
        focusReceiver = new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) {
                if (!FOCUS_ACTION.equals(intent.getAction()) || !enabled()
                        || configuredKeycode() < 0 || locked(context)) return;
                if (focusWindow != null) dismissStatusFocus();
                else showStatusFocus(context);
            }
        };
        // Registration needs a SystemUI context; PhoneStatusBarView provides it.
    }

    private static void registerFocusReceiver(Context context) {
        if (focusReceiver == null) installFocusReceiver();
        if (focusReceiverRegistered) return;
        try {
            context.registerReceiver(focusReceiver, new IntentFilter(FOCUS_ACTION),
                    FOCUS_PERMISSION, new Handler(Looper.getMainLooper()),
                    Context.RECEIVER_EXPORTED);
            focusReceiverRegistered = true;
            Log.i(TAG, "Protected BlackBerry key focus receiver registered");
        } catch (Throwable error) {
            Log.e(TAG, "BlackBerry key focus receiver registration failed", error);
        }
    }

    private static boolean focusReceiverRegistered;

    private static void dismissStatusFocus() {
        updateStatusFocus(-1);
        View root = focusWindow;
        WindowManager wm = focusManager;
        focusWindow = null;
        focusManager = null;
        if (root != null && wm != null) {
            try { wm.removeViewImmediate(root); }
            catch (Throwable error) { Log.w(TAG, "Status focus removal failed", error); }
        }
    }

    private static void showStatusFocus(Context context) {
        if (shown || openingOrClosing || focusWindow != null || locked(context)) return;
        WindowManager wm = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        if (wm == null) return;
        FrameLayout root = new FrameLayout(context) {
            @Override public boolean dispatchKeyEvent(KeyEvent event) {
                int code = event.getKeyCode();
                int mapped = configuredKeycode();
                if (mapped > 0 && code == mapped) {
                    if (event.getAction() == KeyEvent.ACTION_UP) dismissStatusFocus();
                    return true;
                }
                if (code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_ESCAPE) {
                    if (event.getAction() == KeyEvent.ACTION_UP) dismissStatusFocus();
                    return true;
                }
                if (code == KeyEvent.KEYCODE_DPAD_LEFT
                        || code == KeyEvent.KEYCODE_DPAD_RIGHT) {
                    if (event.getAction() == KeyEvent.ACTION_DOWN) {
                        focusZone = code == KeyEvent.KEYCODE_DPAD_LEFT ? 0 : 1;
                        updateStatusFocus(focusZone);
                    }
                    return true;
                }
                if (code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN) {
                    return true;
                }
                if (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER
                        || code == KeyEvent.KEYCODE_NUMPAD_ENTER || code == KeyEvent.KEYCODE_SPACE) {
                    if (event.getAction() == KeyEvent.ACTION_UP) {
                        int selected = focusZone;
                        dismissStatusFocus();
                        if (selected == 0) showPanel(getContext());
                        else showSystemControls(getContext());
                    }
                    return true;
                }
                return super.dispatchKeyEvent(event);
            }
        };
        root.setFocusable(true);
        root.setFocusableInTouchMode(true);
        root.setBackgroundColor(Color.TRANSPARENT);
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, nativeStatusBarHeight(context),
                STATUS_BAR_SUB_PANEL_WINDOW_TYPE,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT);
        params.setTitle("BB7 status bar keyboard focus");
        params.gravity = Gravity.TOP | Gravity.START;
        params.setFitInsetsTypes(0);
        try {
            wm.addView(root, params);
            focusManager = wm;
            focusWindow = root;
            focusZone = 0;
            root.requestFocus();
            updateStatusFocus(focusZone);
            Log.i(TAG, "BlackBerry key entered native status-bar navigation");
        } catch (Throwable error) {
            Log.e(TAG, "Unable to acquire status-bar focus; native input retained", error);
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
                if (!state.opened && Math.abs(event.getRawY() - state.startY)
                        < dp(view.getContext(), 12)
                        && Math.abs(event.getRawX() - state.startX)
                        < dp(view.getContext(), 12)) {
                    showPanel(view.getContext());
                }
                SWIPES.remove(view);
                return true;
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
        // Reuse the system-owned notification row click, or the system-owned
        // ActivityStarter when there is no native row. This callback is async
        // for ActivityStarter; only close the same panel after dispatch.
        NativeNotificationClicks.open(key, () -> current.post(() -> {
            if (panel == current && shown) dismissPanelImmediately();
        }));
    }

    /** Native notification actions; keep the existing card-open route untouched. */
    private static void activateNotificationAction(String key, int index) {
        TwoColumnPanel current = panel;
        if (current == null || !shown || openingOrClosing) return;
        if (locked(current.getContext())) {
            dismissPanelImmediately();
            return;
        }
        NativeNotificationActions.activate(key, index, current.getContext(),
                () -> current.post(() -> {
                    if (panel == current && shown) dismissPanelImmediately();
                }));
    }

    private static void dismissNotification(String key) {
        TwoColumnPanel current = panel;
        if (current == null || !shown || openingOrClosing) return;
        if (locked(current.getContext())) {
            dismissPanelImmediately();
            return;
        }
        SystemUiNotificationObserver.dismiss(key);
    }

    private static boolean sendNotificationReply(String key, int index, String reply) {
        TwoColumnPanel current = panel;
        if (current == null || !shown || openingOrClosing) return false;
        if (locked(current.getContext())) {
            dismissPanelImmediately();
            return false;
        }
        return NativeNotificationActions.reply(key, index, reply, current.getContext());
    }

    private static void showSystemControls(Context context) {
        // Stock SystemUI owns Quick Settings; do not fabricate toggle states.
        dismissPanelImmediately();
        try {
            Object service = context.getSystemService("statusbar");
            if (service == null) throw new IllegalStateException("statusbar service absent");
            XposedHelpers.callMethod(service, "expandSettingsPanel");
            Log.i(TAG, "Native Quick Settings requested from status-bar system zone");
        } catch (Throwable error) {
            Log.e(TAG, "Unable to expand stock Quick Settings", error);
        }
    }

    private static void updateStatusFocus(int zone) {
        View bar = nativeStatusBar.get();
        if (bar == null) return;
        bar.post(() -> {
            if (focusOutline != null) {
                bar.getOverlay().remove(focusOutline);
                focusOutline = null;
            }
            if (zone < 0 || bar.getWidth() <= 0 || bar.getHeight() <= 0) return;
            GradientDrawable outline = new GradientDrawable();
            outline.setColor(Color.argb(45, 91, 180, 231));
            outline.setStroke(dp(bar.getContext(), 2), Color.rgb(91, 180, 231));
            outline.setCornerRadius(dp(bar.getContext(), 3));
            int mid = bar.getWidth() / 2;
            outline.setBounds(zone == 0 ? 0 : mid, 0,
                    zone == 0 ? mid : bar.getWidth(), bar.getHeight());
            focusOutline = outline;
            bar.getOverlay().add(outline);
        });
    }

    private static int nativeStatusBarHeight(Context context) {
        View bar = nativeStatusBar.get();
        if (bar != null && bar.getHeight() > 0) return bar.getHeight();
        int id = context.getResources().getIdentifier("status_bar_height", "dimen", "android");
        if (id != 0) return context.getResources().getDimensionPixelSize(id);
        return dp(context, 24);
    }

    private static void showPanel(Context context) {
        if (focusWindow != null) dismissStatusFocus();
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
                context, PullDownHook::dismissPanel, PullDownHook::openNotification,
                new TwoColumnPanel.NotificationActionListener() {
                    @Override public void onAction(String key, int index) {
                        activateNotificationAction(key, index);
                    }
                    @Override public void onDismiss(String key) {
                        dismissNotification(key);
                    }
                    @Override public boolean onReply(String key, int index, String reply) {
                        return sendNotificationReply(key, index, reply);
                    }
                }, new TwoColumnPanel.StatusZoneListener() {
                    @Override public void onZoneFocus(int zone) {
                        updateStatusFocus(zone);
                    }
                    @Override public void onSystemControls() {
                        showSystemControls(context);
                    }
                });
        root.setNotifications(SystemUiNotificationObserver.itemsSnapshot());
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                Math.max(1, context.getResources().getDisplayMetrics().heightPixels
                        - nativeStatusBarHeight(context)),
                STATUS_BAR_SUB_PANEL_WINDOW_TYPE,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.OPAQUE);
        params.setTitle("BB7 Pull-down");
        params.gravity = Gravity.TOP | Gravity.START;
        params.y = nativeStatusBarHeight(context);
        params.setFitInsetsTypes(0);
        params.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        int height = params.height;
        root.setTranslationY(-height);
        try {
            manager.addView(root, params);
            // Match CentralSurfacesImpl: observe Android's own system-dialog
            // close broadcast for Home, as well as screen-off for privacy.
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
        root.clearStatusZoneFocus();
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
                String action = intent.getAction();
                if (Intent.ACTION_CLOSE_SYSTEM_DIALOGS.equals(action)) {
                    Log.i(TAG, "Native close-system-dialogs event received");
                    dismissPanelImmediately();
                } else if (Intent.ACTION_SCREEN_OFF.equals(action)) {
                    Log.i(TAG, "Native screen-off event received");
                    dismissPanelImmediately();
                }
            }
        };
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_CLOSE_SYSTEM_DIALOGS);
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        try {
            // Android 13+ requires an explicit receiver export flag when
            // a filter contains non-exempt system broadcasts.
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } catch (SecurityException registrationError) {
            // Never regress panel opening solely because Home-collapse
            // broadcasts cannot be registered on this system image.
            // Retain the essential screen-off privacy receiver.
            Log.w(TAG, "System-dialog close receiver rejected; using screen-off only",
                    registrationError);
            context.registerReceiver(receiver, new IntentFilter(Intent.ACTION_SCREEN_OFF),
                    Context.RECEIVER_NOT_EXPORTED);
        }
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
            current.clearStatusZoneFocus();
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
            Log.i(TAG, "Two-column pull-down immediately hidden");
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
