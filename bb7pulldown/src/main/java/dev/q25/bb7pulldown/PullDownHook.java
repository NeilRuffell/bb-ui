package dev.q25.bb7pulldown;

import dev.q25.trackpad.theme.BB7Theme;

import android.app.KeyguardManager;
import android.content.Context;
import android.media.session.MediaSession;
import android.content.BroadcastReceiver;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.PixelFormat;
import android.bluetooth.BluetoothAdapter;
import android.net.wifi.WifiManager;
import android.telephony.SubscriptionManager;
import android.os.Handler;
import android.os.Looper;
import android.database.ContentObserver;
import android.view.KeyEvent;
import android.widget.FrameLayout;
import android.widget.Toast;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.ColorDrawable;
import java.lang.ref.WeakReference;
import android.os.SystemClock;
import android.util.Log;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
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
    private static boolean nativeShadeExpanded;
    // Intercept SystemUI's native Recents command before Quickstep changes activities.
    private static final String COMMAND_QUEUE = "com.android.systemui.statusbar.CommandQueue";
    // Android internal WindowManager.LayoutParams.TYPE_STATUS_BAR_SUB_PANEL
    // is hidden from public SDK stubs; framework value is FIRST_SYSTEM_WINDOW+17.
    private static final int STATUS_BAR_SUB_PANEL_WINDOW_TYPE = 2017;
    private static final Map<View, SwipeState> SWIPES =
            Collections.synchronizedMap(new WeakHashMap<>());
    // Non-null ONLY while creating the native shade from an active finger
    // gesture on the real Android status bar. Never stored in BB view state.
    private static SwipeState pendingNativeTouchOpen;
    private static WindowManager windowManager;
    private static UnifiedShadeHost shadeHost;
    private static TwoColumnPanel panel;
    private static NativeMediaSessions mediaSessions;
    private static BB6NativeQsPanel quickPanel;
    private static FavoritesRules activeFavorites = new FavoritesRules();
    private static ContentObserver favoritesObserver;
    private static Context favoritesObserverContext;
    private static boolean openingOrClosing;
    private static BroadcastReceiver screenOffReceiver;
    private static Context receiverContext;
    private static WeakReference<View> nativeStatusBar = new WeakReference<>(null);
    private static Drawable originalStatusBackground;
    private static View decoratedStatusBar;
    private static WindowManager focusManager;
    private static View focusWindow;
    // Same presentation class used by the fully opened shade; no page content
    // is attached until the user activates a header selection.
    private static ShadeDateHeader standaloneHeader;
    private static boolean selectorTouchInProgress;
    private static BroadcastReceiver focusReceiver;
    private static Context focusReceiverContext;
    // The system task selected before the popup grabs keyboard focus.
    private static ForegroundTaskActions.Target popupTaskTarget;
    // The original CommandQueue instance continues to own native Quickstep.
    private static WeakReference<Object> nativeRecentsQueue = new WeakReference<>(null);
    private static boolean bypassRecentsMenuInterception;

    /**
     * One navigation/session owner for the native status bar and both shade
     * pages. These callbacks are the current LSPosed integration adapter;
     * ShadeUiController, the host, and the pages depend on no Xposed types.
     */
    private static final ShadeUiController NAVIGATION = new ShadeUiController(
            new ShadeUiController.Listener() {
                @Override public void onStatusFocusChanged(int zone) {
                    TwoColumnPanel currentPanel = panel;
                    if (currentPanel != null) currentPanel.refreshAppIconFocus();
                    // Popup visibility is controlled solely by the BB key hook.
                    // Content navigation must never create a popup window.
                    ShadeDateHeader popup = standaloneHeader;
                    if (popup != null && zone != ShadeStatusNavigation.NONE) {
                        popup.setFocusedZone(zone);
                    }
                    updateStatusAppearance();
                }

                @Override public void onShowPage(int page) {
                    UnifiedShadeHost current = shadeHost;
                    if (current != null) {
                        switchShadePage(page);
                        if (shadeHost == current) dismissStatusFocus();
                        return;
                    }
                    View anchor = focusWindow;
                    if (anchor == null) anchor = nativeStatusBar.get();
                    if (anchor != null) showShade(anchor.getContext(), page);
                }

                @Override public void onCloseApplication() {
                    closeForegroundApplication();
                }

                @Override public void onSwitchApplication() {
                    showNativeRecents();
                }

                @Override public void onExit() {
                    if (focusWindow != null) {
                        dismissStatusFocus();
                        if (shadeHost != null) shadeHost.focusContent();
                    } else if (shadeHost != null) {
                        dismissShade();
                    }
                }
            });

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lp) {
        if (!PACKAGE.equals(lp.packageName) || !PACKAGE.equals(lp.processName)) return;
        // Capture the native status-bar visibility interactor as early as
        // possible during SystemUI startup. Installing this only after the
        // other BB hooks missed Q25's already constructed flow instance.
        if (NativeShadeHostBridge.requested()) {
            NativeShadeStatusBarVisibility.install(lp.classLoader);
        }
        // Colour icons have their own independent rollback property, even when
        // the custom BB7 shade is disabled.
        NativeStatusBarColorIcons.install(lp.classLoader);
        // The Hub status indicators belong to the existing native icon area.
        // Independent rollback and exact Q25 signature checks retain stock on failure.
        NativeStatusBarHubIcons.install(lp.classLoader);
        // Popup presentation only; BB shade and its notification actions are untouched.
        NativeSimpleHeadsUp.install(lp.classLoader);
        NativeSquareHeadsUpCorners.install(lp.classLoader);
        // Shared SystemUI notification width policy: remove Android side gutters globally.
        NativeFullWidthNotifications.install(lp.classLoader);

        if (!enabled()) {
            Log.i(TAG, "Disabled by " + ENABLE_PROP + "; stock status bar retained");
            return;
        }
        installRecentsInterception(lp.classLoader);
        installNativeShadeState(lp.classLoader);
        SystemUiNotificationObserver.install(lp.classLoader);
        NativeQuickSettingsTiles.install(lp.classLoader);
        NativeNotificationClicks.install(lp.classLoader);
        // Optional single-shade integration: SystemUI owns the BB UI shade window.
        NativeShadeHostBridge.install(lp.classLoader);
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
                        View bar = (View) param.thisObject;
                        nativeStatusBar = new WeakReference<>(bar);
                        bar.post(PullDownHook::updateStatusAppearance);
                    }
                }
            });
            Log.i(TAG, "Status-bar gesture and two-zone navigation registered");
        } catch (Throwable error) {
            Log.e(TAG, "Unable to register global status-bar gesture", error);
        }
    }

    private static void installRecentsInterception(ClassLoader loader) {
        Class<?> queue = XposedHelpers.findClassIfExists(COMMAND_QUEUE, loader);
        if (queue == null) {
            Log.w(TAG, "Native Recents command queue unavailable; preserving stock behavior");
            return;
        }
        try {
            XposedBridge.hookAllMethods(queue, "toggleRecentApps", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    if (!enabled()) return;
                    // Menu's Switch Application uses this same queue without
                    // recursively invoking the BB-key interception path.
                    if (bypassRecentsMenuInterception) return;
                    nativeRecentsQueue = new WeakReference<>(param.thisObject);
                    View bar = nativeStatusBar.get();
                    if (bar == null || locked(bar.getContext())) {
                        Log.w(TAG, "BB menu: native bar unavailable/locked; stock retained");
                        return;
                    }
                    param.setResult(null);
                    new Handler(Looper.getMainLooper()).post(() -> {
                        if (focusWindow != null) {
                            dismissStatusFocus();
                            if (shadeHost != null) shadeHost.focusContent();
                        } else if (shadeHost != null) {
                            showStatusFocus(shadeHost.getContext());
                            NAVIGATION.openPopupFromBlackBerryKey(shadeHost.page());
                        } else {
                            showStatusFocus(bar.getContext());
                        }
                    });
                    Log.i(TAG, "BlackBerry key requested bottom-left page popup");
                }
            });
            Log.i(TAG, "BB page menu interception registered");
        } catch (Throwable error) {
            Log.e(TAG, "Unable to register BB menu interception", error);
        }
    }

    /** Close only the exact foreground application task captured at popup open. */
    private static void closeForegroundApplication() {
        ForegroundTaskActions.Target target = popupTaskTarget;
        View popupWindow = focusWindow;
        Context context = popupWindow != null ? popupWindow.getContext() : null;
        dismissStatusFocus();
        if (context == null || !ForegroundTaskActions.closeCapturedApplication(context, target)) {
            if (context != null) {
                Toast.makeText(context, "Unable to close application", Toast.LENGTH_SHORT).show();
            }
        }
    }

    /** Reuse Quickstep's native Recents rather than creating another switcher. */
    private static void showNativeRecents() {
        Object queue = nativeRecentsQueue.get();
        View source = focusWindow;
        Context context = source != null ? source.getContext() : null;
        dismissStatusFocus();
        if (shadeHost != null) dismissShadeImmediately();
        if (queue == null) {
            Log.w(TAG, "Native Recents command queue no longer available");
            if (context != null) {
                Toast.makeText(context, "Unable to open applications",
                        Toast.LENGTH_SHORT).show();
            }
            return;
        }
        try {
            // Unlike toggleRecentApps, showRecentApps is not our BB-key hook.
            XposedHelpers.callMethod(queue, "showRecentApps", false);
        } catch (Throwable showError) {
            Log.w(TAG, "showRecentApps unavailable; trying native toggle", showError);
            bypassRecentsMenuInterception = true;
            try {
                XposedHelpers.callMethod(queue, "toggleRecentApps");
            } catch (Throwable toggleError) {
                Log.e(TAG, "Quickstep activation unavailable", toggleError);
                if (context != null) {
                    Toast.makeText(context, "Unable to open applications",
                            Toast.LENGTH_SHORT).show();
                }
            } finally {
                bypassRecentsMenuInterception = false;
            }
        }
    }

    private static void dismissStatusFocus() {
        selectorTouchInProgress = false;
        popupTaskTarget = null;
        NAVIGATION.leaveStandaloneStatusBar();
        unregisterSelectorScreenOffReceiver();
        View root = focusWindow;
        WindowManager wm = focusManager;
        focusWindow = null;
        focusManager = null;
        standaloneHeader = null;
        updateStatusAppearance();
        if (root != null && wm != null) {
            try { wm.removeViewImmediate(root); }
            catch (Throwable error) { Log.w(TAG, "Status focus removal failed", error); }
        }
    }

    private static void registerSelectorScreenOffReceiver(Context context) {
        if (focusReceiver != null) return;
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent intent) {
                if (Intent.ACTION_SCREEN_OFF.equals(intent.getAction())) {
                    if (shadeHost != null) dismissShadeImmediately();
                    else dismissStatusFocus();
                }
            }
        };
        try {
            context.registerReceiver(receiver, new IntentFilter(Intent.ACTION_SCREEN_OFF),
                    Context.RECEIVER_NOT_EXPORTED);
            focusReceiver = receiver;
            focusReceiverContext = context;
        } catch (RuntimeException error) {
            // Retain the keyboard selector if a ROM restricts registration.
            Log.w(TAG, "Selector screen-off cleanup receiver unavailable", error);
        }
    }

    private static void unregisterSelectorScreenOffReceiver() {
        BroadcastReceiver receiver = focusReceiver;
        Context context = focusReceiverContext;
        focusReceiver = null;
        focusReceiverContext = null;
        if (receiver != null && context != null) {
            try { context.unregisterReceiver(receiver); }
            catch (IllegalArgumentException ignored) { }
        }
    }

    /** Forward a touch to the original popup if the shade window covers it. */
    static boolean routeExternalHeaderTouch(MotionEvent event) {
        ShadeDateHeader popup = standaloneHeader;
        if (popup == null || shadeHost == null || !popup.isShown()) {
            selectorTouchInProgress = false;
            return false;
        }
        int action = event.getActionMasked();
        int[] origin = new int[2];
        popup.getLocationOnScreen(origin);
        if (action == MotionEvent.ACTION_DOWN) {
            float x = event.getRawX();
            float y = event.getRawY();
            selectorTouchInProgress = x >= origin[0] && x < origin[0] + popup.getWidth()
                    && y >= origin[1] && y < origin[1] + popup.getHeight();
        }
        if (!selectorTouchInProgress) return false;
        MotionEvent translated = MotionEvent.obtain(event);
        try {
            translated.setLocation(event.getRawX() - origin[0],
                    event.getRawY() - origin[1]);
            popup.dispatchTouchEvent(translated);
        } finally {
            translated.recycle();
        }
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL
                || action == MotionEvent.ACTION_POINTER_DOWN) {
            selectorTouchInProgress = false;
        }
        return true;
    }

    private static void showStatusFocus(Context context) {
        if (openingOrClosing || focusWindow != null || locked(context)) return;
        WindowManager wm = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        if (wm == null) return;
        ForegroundTaskActions.Target target = shadeHost == null
                ? ForegroundTaskActions.currentApplication(context) : null;
        boolean closeAvailable = target != null;
        NAVIGATION.setCloseApplicationAvailable(closeAvailable);
        FrameLayout root = new FrameLayout(context) {
            @Override public boolean dispatchKeyEvent(KeyEvent event) {
                if (NAVIGATION.handleKey(event)) return true;
                return super.dispatchKeyEvent(event);
            }
        };
        root.setFocusable(true);
        root.setFocusableInTouchMode(true);
        root.setBackgroundColor(Color.TRANSPARENT);
        ShadeDateHeader popup = new ShadeDateHeader(context, closeAvailable);
        int popupWidth = ShadeDateHeader.widthPx(context);
        int popupHeight = ShadeDateHeader.heightPx(context, closeAvailable);
        root.addView(popup, new FrameLayout.LayoutParams(popupWidth, popupHeight));
        final float[] touchDown = new float[2];
        final boolean[] moved = new boolean[1];
        popup.setOnTouchListener((view, event) -> {
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) {
                touchDown[0] = event.getRawX();
                touchDown[1] = event.getRawY();
                moved[0] = false;
                return true;
            }
            if (action == MotionEvent.ACTION_MOVE) {
                if (Math.abs(event.getRawX() - touchDown[0]) >= dp(context, 12)
                        || Math.abs(event.getRawY() - touchDown[1]) >= dp(context, 12)) {
                    moved[0] = true;
                }
                return true;
            }
            if (action == MotionEvent.ACTION_UP && focusWindow == root
                    && !locked(context) && !moved[0]) {
                int zone = ShadeHeaderSegmentPolicy.zoneAt(
                        event.getY(), view.getHeight(), closeAvailable);
                if (zone != ShadeStatusNavigation.NONE) {
                    NAVIGATION.selectPopupZone(zone);
                    NAVIGATION.handleKey(KeyEvent.KEYCODE_ENTER, KeyEvent.ACTION_UP);
                }
            }
            return true;
        });
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                popupWidth, popupHeight, STATUS_BAR_SUB_PANEL_WINDOW_TYPE,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT);
        params.setTitle("BB UI application menu");
        params.gravity = Gravity.BOTTOM | Gravity.START;
        params.x = dp(context, 4);
        params.y = dp(context, 8);
        params.setFitInsetsTypes(android.view.WindowInsets.Type.navigationBars());
        try {
            wm.addView(root, params);
            focusManager = wm;
            focusWindow = root;
            standaloneHeader = popup;
            popupTaskTarget = target;
            registerSelectorScreenOffReceiver(context);
            if (shadeHost == null) NAVIGATION.enterStandaloneStatusBar();
            popup.setFocusedZone(NAVIGATION.focusedZone());
            root.requestFocus();
            Log.i(TAG, "BB page popup displayed at bottom-left");
        } catch (Throwable error) {
            unregisterSelectorScreenOffReceiver();
            if (root.isAttachedToWindow()) {
                try { wm.removeViewImmediate(root); }
                catch (Throwable cleanupError) {
                    Log.w(TAG, "Unable to remove failed page popup", cleanupError);
                }
            }
            focusManager = null;
            focusWindow = null;
            standaloneHeader = null;
            popupTaskTarget = null;
            if (shadeHost == null) NAVIGATION.leaveStandaloneStatusBar();
            Log.e(TAG, "Unable to present BB page popup", error);
        }
    }

    private static boolean enabled() {
        try {
            Class<?> props = Class.forName("android.os.SystemProperties");
            return (Boolean) XposedHelpers.callStaticMethod(
                    props, "getBoolean", ENABLE_PROP, true);
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
            // If native integration is enabled but SystemUI's controller is
            // not ready, leave the touch to Android instead of consuming it.
            if (NativeShadeHostBridge.requested() && !NativeShadeHostBridge.ready()) {
                return false;
            }
            if (!enabled() || shadeHost != null || openingOrClosing || locked(view.getContext())
                    || event.getPointerCount() != 1
                    || event.getToolType(0) != MotionEvent.TOOL_TYPE_FINGER) return false;
            SwipeState state = new SwipeState(
                    event.getRawY(), event.getRawX(), event.getEventTime());
            SWIPES.put(view, state);
            return true;
        }
        SwipeState state = SWIPES.get(view);
        if (state == null) return false;
        switch (action) {
            case MotionEvent.ACTION_MOVE:
                float deltaY = event.getRawY() - state.startY;
                float deltaX = Math.abs(event.getRawX() - state.startX);
                int thresholdPx = dp(view.getContext(), 20);
                if (!state.opened && deltaY >= thresholdPx && deltaY > deltaX
                        && event.getPointerCount() == 1) {
                    state.opened = true;
                    if (NativeShadeHostBridge.requested()
                            && NativeShadeSlideAnimator.touchEnabled()) {
                        pendingNativeTouchOpen = state;
                        try {
                            showPanel(view.getContext());
                        } finally {
                            pendingNativeTouchOpen = null;
                        }
                    } else {
                        showPanel(view.getContext());
                    }
                }
                if (state.opened && NativeShadeSlideAnimator.isTouchOpening()) {
                    NativeShadeSlideAnimator.updateTouchOpen(deltaY);
                }
                return true;
            case MotionEvent.ACTION_UP:
                if (state.opened && NativeShadeSlideAnimator.isTouchOpening()) {
                    finishStatusBarDrag(event, state, false);
                } else if (!state.opened && Math.abs(event.getRawY() - state.startY)
                        < dp(view.getContext(), 12)
                        && Math.abs(event.getRawX() - state.startX)
                        < dp(view.getContext(), 12)) {
                    // A tap retains the ordinary animated shade opening.
                    showPanel(view.getContext());
                }
                SWIPES.remove(view);
                return true;
            case MotionEvent.ACTION_CANCEL:
            case MotionEvent.ACTION_POINTER_DOWN:
                if (state.opened && NativeShadeSlideAnimator.isTouchOpening()) {
                    finishStatusBarDrag(event, state, true);
                }
                SWIPES.remove(view);
                return true;
            default:
                return true;
        }
    }

    private static void finishStatusBarDrag(MotionEvent event, SwipeState state,
            boolean canceled) {
        UnifiedShadeHost root = shadeHost;
        if (root == null) {
            NativeShadeSlideAnimator.restore();
            return;
        }
        long elapsed = Math.max(1L, event.getEventTime() - state.downTime);
        float velocityY = (event.getRawY() - state.startY) * 1000f / elapsed;
        boolean started = NativeShadeSlideAnimator.finishTouchOpen(velocityY, canceled,
                () -> {
                    if (shadeHost == root) {
                        openingOrClosing = false;
                        root.focusContent();
                    }
                },
                () -> {
                    if (shadeHost == root) dismissShadeImmediately();
                });
        if (!started && shadeHost == root) dismissShadeImmediately();
    }

    static void onNotificationItemsUpdated() {
        TwoColumnPanel current = panel;
        if (current == null || !notificationPageActive()) return;
        current.post(() -> {
            if (current == panel && notificationPageActive()) {
                if (locked(current.getContext())) {
                    dismissPanelImmediately();
                } else {
                    current.setNotifications(SystemUiNotificationObserver.itemsSnapshot());
                    FavoritesClient.reportObserved(current.getContext(), activeFavorites,
                            SystemUiNotificationObserver.itemsSnapshot());
                    NativeMediaSessions media = mediaSessions;
                    if (media != null) media.refresh();
                }
            }
        });
    }

    private static void openNotification(String key) {
        TwoColumnPanel current = panel;
        if (current == null || !notificationPageActive() || openingOrClosing) return;
        if (locked(current.getContext())) {
            dismissPanelImmediately();
            return;
        }
        // Reuse the system-owned notification row click, or the system-owned
        // ActivityStarter when there is no native row. This callback is async
        // for ActivityStarter; only close the same panel after dispatch.
        NativeNotificationClicks.open(key, () -> current.post(() -> {
            if (panel == current && notificationPageActive()) dismissPanelImmediately();
        }));
    }

    /** Native notification actions; keep the existing card-open route untouched. */
    private static void activateNotificationAction(String key, int index) {
        TwoColumnPanel current = panel;
        if (current == null || !notificationPageActive() || openingOrClosing) return;
        if (locked(current.getContext())) {
            dismissPanelImmediately();
            return;
        }
        NativeNotificationActions.activate(key, index, current.getContext(),
                () -> current.post(() -> {
                    if (panel == current && notificationPageActive()) dismissPanelImmediately();
                }));
    }

    private static void dismissNotification(String key) {
        TwoColumnPanel current = panel;
        if (current == null || !notificationPageActive() || openingOrClosing) return;
        if (locked(current.getContext())) {
            dismissPanelImmediately();
            return;
        }
        SystemUiNotificationObserver.dismiss(key);
    }

    private static boolean sendNotificationReply(String key, int index, String reply) {
        TwoColumnPanel current = panel;
        if (current == null || !notificationPageActive() || openingOrClosing) return false;
        if (locked(current.getContext())) {
            dismissPanelImmediately();
            return false;
        }
        return NativeNotificationActions.reply(key, index, reply, current.getContext());
    }

    /** Page changes keep the same WindowManager window and translation. */
    private static void switchShadePage(int next) {
        UnifiedShadeHost root = shadeHost;
        if (root == null || openingOrClosing) return;
        Context context = root.getContext();
        if (locked(context)) {
            dismissShadeImmediately();
            return;
        }
        if (root.page() == next) {
            root.focusContent();
            return;
        }
        // Refresh native data for the page we are entering. Neither page is
        // detached or reconstructed, and the root entrance animation is untouched.
        if (next == UnifiedShadeHost.NOTIFICATIONS) {
            SystemUiNotificationObserver.refreshBeforeOpen();
            if (panel != null) {
                panel.setOrganizationMode(NotificationShadeModeClient.read(context));
                panel.setFavorites(activeFavorites);
                panel.setNotifications(SystemUiNotificationObserver.itemsSnapshot());
            }
            FavoritesClient.reportObserved(context, activeFavorites,
                    SystemUiNotificationObserver.itemsSnapshot());
            if (mediaSessions != null) mediaSessions.refresh();
        }
        NAVIGATION.onPageChanged(next);
        root.setPage(next);
        updateStatusAppearance();
        root.focusContent();
        Log.i(TAG, "Unified shade switched page=" + next + " without animation");
    }

    /** Invoke ONLY the selected installed native SystemUI QS tile. */
    private static void activateQuickTile(String spec, int ignoredKind,
            NativeQuickSettingsTiles.Action action) {
        BB6NativeQsPanel root = quickPanel;
        if (root == null || !quickSettingsPageActive()) return;
        if (locked(root.getContext())) {
            dismissShadeImmediately();
            return;
        }
        // QS activation is not a request to close the shade. Keep its native
        // listening session, selected list row, and SystemUI window mounted
        // while the real QSTile handles the click (including native dialogs).
        // Explicit Back, swipe dismissal, Home and screen-off retain their
        // existing independent close paths.
        Log.i(TAG, "Native QS action with shade retained: " + spec
                + " (" + action + ")");
        boolean accepted = NativeQuickSettingsTiles.activate(spec, action);
        if (!accepted) {
            Log.w(TAG, "Installed native QS tile refused action: " + spec
                    + " (" + action + ")");
        }
    }

    /** Native status bar stays untouched outside the requested UI states. */
    private static GradientDrawable graphiteBackground(Context context) {
        GradientDrawable background = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xff40536a, 0xff2a3c52, 0xff172638});
        background.setStroke(dp(context, 1), 0xff57758f);
        return background;
    }

    private static void updateStatusAppearance() {
        View bar = nativeStatusBar.get();
        if (bar == null) bar = decoratedStatusBar;
        if (bar == null) return;
        final View target = bar;
        target.post(() -> {
            if (decoratedStatusBar != null && decoratedStatusBar != target) {
                decoratedStatusBar.setBackground(originalStatusBackground);
                decoratedStatusBar = null;
                originalStatusBackground = null;
            }
            // Popup focus never paints the native status bar.
            boolean decorated = shadeHost != null || openingOrClosing
                    || nativeShadeExpanded;
            if (!decorated) {
                if (decoratedStatusBar == target) {
                    target.setBackground(originalStatusBackground);
                    decoratedStatusBar = null;
                    originalStatusBackground = null;
                }
                return;
            }
            if (decoratedStatusBar != target) {
                originalStatusBackground = target.getBackground();
                decoratedStatusBar = target;
            }
            Drawable base = graphiteBackground(target.getContext());
            // The real status bar retains its native clock and icons.
            target.setBackground(base);
        });
    }

    /** Native Android shade state, including Quick Settings collapse. */
    private static void installNativeShadeState(ClassLoader loader) {
        Class<?> type = XposedHelpers.findClassIfExists(
                "com.android.systemui.shade.ShadeExpansionStateManager", loader);
        if (type == null) {
            Log.w(TAG, "Native shade state manager unavailable");
            return;
        }
        try {
            XposedBridge.hookAllMethods(type, "onPanelExpansionChanged",
                    new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    if (p.args.length < 2 || !(p.args[1] instanceof Boolean)) return;
                    boolean expanded = (Boolean) p.args[1];
                    if (nativeShadeExpanded == expanded) return;
                    nativeShadeExpanded = expanded;
                    updateStatusAppearance();
                }
            });
            Log.i(TAG, "Native shade expansion appearance listener registered");
        } catch (Throwable error) {
            Log.w(TAG, "Native shade expansion appearance listener unavailable", error);
        }
    }

    private static int nativeStatusBarHeight(Context context) {
        View bar = nativeStatusBar.get();
        if (bar != null && bar.getHeight() > 0) return bar.getHeight();
        int id = context.getResources().getIdentifier("status_bar_height", "dimen", "android");
        if (id != 0) return context.getResources().getDimensionPixelSize(id);
        return dp(context, 24);
    }

    // Visibility is derived from the single navigation session and window.
    // No independent per-page flags can drift out of sync with focus.
    private static boolean notificationPageActive() {
        return shadeHost != null
                && NAVIGATION.activePage() == ShadeStatusNavigation.NOTIFICATIONS;
    }

    private static boolean quickSettingsPageActive() {
        return shadeHost != null
                && NAVIGATION.activePage() == ShadeStatusNavigation.QUICK_SETTINGS;
    }

    private static void showPanel(Context context) {
        if (shadeHost != null) {
            switchShadePage(UnifiedShadeHost.NOTIFICATIONS);
        } else {
            showShade(context, UnifiedShadeHost.NOTIFICATIONS);
        }
    }

    /**
     * Native SystemUI expanded/collapsed, including a stock heads-up
     * pull-down gesture. Enter BB presentation in the same native window.
     */
    static void onNativeShadeHostUnavailable() {
        if (shadeHost != null && NativeShadeHostBridge.requested()) {
            Log.w(TAG, "Native shade host lost; releasing BB UI session");
            dismissShadeImmediately();
        }
    }

    /**
     * Called by Q25 BackActionInteractor.onBackRequested() only while BB UI
     * is attached to the actual native notification-shade window.
     * Back keeps the existing notification/reply/media navigation depth.
     */
    static boolean onNativeSystemBackRequested() {
        UnifiedShadeHost current = shadeHost;
        if (current == null || !NativeShadeHostBridge.isMounted(current)) return false;
        if (openingOrClosing) {
            // A second native Back must NOT start Android's competing collapse.
            // Back during the opening slide reverses it immediately.
            if (NativeShadeSlideAnimator.isOpening()) dismissShade();
            return true;
        }
        return current.handleNativeBack();
    }

    /** A SystemUI root reinflation interrupted entrance motion. Remount without animation. */
    static void onNativeShadeSlideInterrupted() {
        UnifiedShadeHost current = shadeHost;
        if (current == null) return;
        openingOrClosing = false;
        current.post(() -> {
            if (shadeHost == current) current.focusContent();
        });
    }

    /** Existing native shade root, not a second touch or notification system. */
    static void onNativeShadeTouchCloseStarted() {
        if (shadeHost != null) openingOrClosing = true;
    }

    static void onNativeShadeTouchCloseSettled(boolean closed) {
        UnifiedShadeHost root = shadeHost;
        if (root == null) return;
        if (closed) {
            dismissShadeImmediately();
        } else {
            openingOrClosing = false;
            root.focusContent();
        }
    }

    static void onNativeShadeExpansionChanged(boolean expanded) {
        if (!NativeShadeHostBridge.active()) return;
        nativeShadeExpanded = expanded;
        updateStatusAppearance();
        UnifiedShadeHost current = shadeHost;
        if (NativeShadeModePolicy.detachOnCollapse(true, expanded,
                current != null && NativeShadeHostBridge.isMounted(current))) {
            dismissShadeImmediately();
            return;
        }
        FrameLayout nativePanel = NativeShadeHostBridge.panel();
        if (nativePanel != null && NativeShadeModePolicy.openFromNative(true,
                expanded, current != null || openingOrClosing,
                locked(nativePanel.getContext()))) {
            showShade(nativePanel.getContext(), UnifiedShadeHost.NOTIFICATIONS);
        }
    }

    /**
     * Native mode uses SystemUI's NotificationPanelView as the only shade
     * window. Legacy mode is retained solely as an independent rollback.
     */
    private static void showShade(Context context, int initialPage) {
        if (shadeHost != null || openingOrClosing || !enabled() || locked(context)) return;
        final boolean nativeMode = NativeShadeHostBridge.requested();
        if (nativeMode && !NativeShadeModePolicy.mayMount(
                true, NativeShadeHostBridge.ready(), locked(context))) {
            Log.w(TAG, "Native shade requested but unavailable; refusing duplicate window");
            return;
        }
        openingOrClosing = true;
        final WindowManager manager = NativeShadeModePolicy.useLegacyWindow(nativeMode)
                ? (WindowManager) context.getSystemService(Context.WINDOW_SERVICE) : null;
        if (!nativeMode && manager == null) {
            openingOrClosing = false;
            Log.w(TAG, "Shade WindowManager unavailable");
            return;
        }

        // The popup is dismissed only after the selected shade mounts.
        final boolean invokedFromPopup = focusWindow != null;

        try {
        SystemUiNotificationObserver.refreshBeforeOpen();
        activeFavorites = FavoritesClient.read(context);
        FavoritesClient.reportObserved(context, activeFavorites,
                SystemUiNotificationObserver.itemsSnapshot());

        NativeMediaSessions nativeMedia = new NativeMediaSessions(context, entries -> {
            TwoColumnPanel current = panel;
            if (shadeHost != null && current != null) current.setMediaSessions(entries);
        });
        TwoColumnPanel notifications = new TwoColumnPanel(
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
                }, NAVIGATION, new TwoColumnPanel.MediaControlListener() {
                    @Override public boolean onControl(MediaSession.Token token, int action) {
                        return nativeMedia.send(token, action);
                    }
                    @Override public boolean onSeek(MediaSession.Token token, long positionMs) {
                        return nativeMedia.seekTo(token, positionMs);
                    }
                    @Override public boolean onSetMode(
                            MediaSession.Token token, int kind, int value) {
                        return nativeMedia.setMode(token, kind, value);
                    }
                });
        BB6NativeQsPanel connections = new BB6NativeQsPanel(context,
                new BB6NativeQsPanel.Listener() {
                    @Override public void onClose() {
                        dismissShade();
                    }
                    @Override public void onAction(String spec, int kind,
                            NativeQuickSettingsTiles.Action action) {
                        activateQuickTile(spec, kind, action);
                    }
                }, NAVIGATION, false);
        notifications.setFavorites(activeFavorites);
        notifications.setOrganizationMode(NotificationShadeModeClient.read(context));
        notifications.setNotifications(SystemUiNotificationObserver.itemsSnapshot());

        UnifiedShadeHost root = new UnifiedShadeHost(
                context, notifications, connections, NAVIGATION, initialPage,
                PullDownHook::dismissShade);
        // Construct legacy window parameters only in rollback mode.
        // Native mode must not allocate a second shade window at all.
        WindowManager.LayoutParams params = null;
        int height = 0;
        if (!nativeMode) {
            params = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    Math.max(1, context.getResources().getDisplayMetrics().heightPixels
                            - nativeStatusBarHeight(context)),
                    STATUS_BAR_SUB_PANEL_WINDOW_TYPE,
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.OPAQUE);
            params.setTitle("BB UI Unified Shade");
            params.gravity = Gravity.TOP | Gravity.START;
            params.y = nativeStatusBarHeight(context);
            params.setFitInsetsTypes(0);
            params.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
            height = params.height;
        }
        root.setTranslationY(nativeMode ? 0f : -height);
        boolean attached = false;
        boolean nativeSlidePrepared = false;
        try {
            if (nativeMode) {
                if (!NativeShadeHostBridge.attach(root, nativeStatusBarHeight(context))) {
                    throw new IllegalStateException("Cannot mount BB UI in Q25 native shade");
                }
                // Both BB confirmation and status-bar touch open the native window.
                nativeSlidePrepared = NativeShadeSlideAnimator.prepareOpen(
                        NativeShadeHostBridge.shadeWindow(), pendingNativeTouchOpen != null);
            } else {
                manager.addView(root, params);
            }
            attached = true;
            shadeHost = root;
            windowManager = manager; // deliberately null for native shade mode
            panel = notifications;
            quickPanel = connections;
            NAVIGATION.onShadeOpened(initialPage);
            registerScreenOffReceiver(context);
            mediaSessions = nativeMedia;
            nativeMedia.start();
            registerFavoritesObserver(context, notifications);
            updateStatusAppearance();

            if (nativeMode) {
                Log.i(TAG, "BB UI session attached inside native shade: page=" + initialPage);
                if (!NativeShadeHostBridge.requestNativeOpen()) {
                    Log.e(TAG, "Native shade controller refused expansion; releasing BB UI host");
                    dismissShadeImmediately();
                    return;
                }
                if (focusWindow != null) dismissStatusFocus();
                if (nativeSlidePrepared) {
                    if (NativeShadeSlideAnimator.isTouchOpening()) {
                        Log.i(TAG, "Native shade opening under finger control");
                    } else {
                        root.post(() -> {
                            if (shadeHost != root || !NativeShadeSlideAnimator.isOpening()) return;
                            if (!NativeShadeSlideAnimator.animateOpen(() -> {
                                if (shadeHost == root && !NativeShadeSlideAnimator.isClosing()) {
                                    openingOrClosing = false;
                                    root.focusContent();
                                }
                            }) && shadeHost == root) {
                                openingOrClosing = false;
                                root.focusContent();
                            }
                        });
                    }
                } else {
                    openingOrClosing = false;
                    root.post(() -> {
                        if (shadeHost == root) root.focusContent();
                    });
                }
            } else {
                Log.i(TAG, "Unified shade window attached: initialPage=" + initialPage
                        + " heightPx=" + height);
                if (focusWindow != null) dismissStatusFocus();
                root.post(() -> {
                    if (shadeHost != root) return;
                    root.animate().translationY(0f).setDuration(220).withEndAction(() -> {
                        if (shadeHost == root) {
                            openingOrClosing = false;
                            root.focusContent();
                        }
                    }).start();
                });
            }
        } catch (Throwable error) {
            Log.e(TAG, "Unable to attach unified shade; rolling back", error);
            if (shadeHost == root) {
                dismissShadeImmediately();
            } else if (attached) {
                try {
                    if (nativeMode) NativeShadeHostBridge.detach(root);
                    else manager.removeViewImmediate(root);
                } catch (Throwable rollbackError) {
                    Log.w(TAG, "Unable to remove failed shade host", rollbackError);
                }
                openingOrClosing = false;
            } else {
                openingOrClosing = false;
            }
            restorePopupAfterFailedMount(invokedFromPopup, initialPage);
        }
        } catch (Throwable initializationError) {
            openingOrClosing = false;
            restorePopupAfterFailedMount(invokedFromPopup, initialPage);
            Log.e(TAG, "Unable to initialize unified shade pages", initializationError);
        }
    }

    /** Failed mount restores keyboard focus without replacing the popup View. */
    private static void restorePopupAfterFailedMount(boolean fromPopup, int zone) {
        if (!fromPopup || focusWindow == null || shadeHost != null) return;
        NAVIGATION.enterStandaloneStatusBar();
        NAVIGATION.selectPopupZone(zone);
        focusWindow.requestFocus();
    }

    private static void dismissPanel() {
        dismissShade();
    }

    private static void dismissShade() {
        UnifiedShadeHost root = shadeHost;
        if (root == null || (openingOrClosing && !NativeShadeSlideAnimator.isOpening())) {
            return;
        }
        if (NativeShadeHostBridge.isMounted(root)) {
            openingOrClosing = true;
            root.clearStatusBarFocus();
            // The native WINDOW itself slides up, keeping the BB child and
            // native backdrop together. Only after the slide do we collapse
            // the SystemUI panel and release its child.
            if (NativeShadeSlideAnimator.animateClose(() -> {
                if (shadeHost == root) dismissShadeImmediately();
            })) return;
            dismissShadeImmediately();
            return;
        }
        openingOrClosing = true;
        int height = root.getHeight();
        if (height <= 0) height = root.getResources().getDisplayMetrics().heightPixels;
        root.clearStatusBarFocus();
        root.animate().translationY(-height).setDuration(180)
                .withEndAction(() -> {
                    if (shadeHost == root) dismissShadeImmediately();
                }).start();
    }

    private static void registerScreenOffReceiver(Context context) {
        if (screenOffReceiver != null) return;
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent intent) {
                String action = intent.getAction();
                if (Intent.ACTION_CLOSE_SYSTEM_DIALOGS.equals(action)) {
                    Log.i(TAG, "Native close-system-dialogs event received");
                    dismissShadeImmediately();
                } else if (Intent.ACTION_SCREEN_OFF.equals(action)) {
                    Log.i(TAG, "Native screen-off event received");
                    dismissShadeImmediately();
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
        dismissShadeImmediately();
    }

    /** One cleanup path for QS, notifications, Home, and screen-off. */
    private static void dismissShadeImmediately() {
        UnifiedShadeHost current = shadeHost;
        if (current == null) return;
        shadeHost = null;
        NAVIGATION.onShadeClosed();
        boolean nativeMount = NativeShadeHostBridge.isMounted(current);
        try {
            current.clearStatusBarFocus();
            current.animate().cancel();
            if (nativeMount) {
                // Collapse BEFORE resetting the native window translation, so
                // no full shade flashes back onscreen during animated dismissal.
                NativeShadeHostBridge.collapseNative();
                NativeShadeSlideAnimator.restore();
                NativeShadeHostBridge.detach(current);
            } else if (windowManager != null) {
                windowManager.removeViewImmediate(current);
            }
        } catch (Throwable error) {
            Log.w(TAG, "Unable to remove unified shade host", error);
        } finally {
            // Also restore after an exception during native collapse/detach.
            if (nativeMount) NativeShadeSlideAnimator.restore();
            stopFavoritesObserver();
            stopMediaSessions();
            unregisterScreenOffReceiver();
            panel = null;
            quickPanel = null;
            windowManager = null;
            openingOrClosing = false;
            // A transient popup can be open over an expanded shade.
            if (focusWindow != null) dismissStatusFocus();
            updateStatusAppearance();
            Log.i(TAG, "Unified shade window removed");
        }
    }

    /** Listen only while our panel is visible; settings updates are live. */
    private static void registerFavoritesObserver(Context context, TwoColumnPanel root) {
        stopFavoritesObserver();
        ContentObserver observer = new ContentObserver(new Handler(Looper.getMainLooper())) {
            @Override public void onChange(boolean selfChange) {
                // Settings remain synchronized even when the retained QS page
                // is visible, so returning to notifications uses fresh rules.
                if (panel != root || shadeHost == null) return;
                activeFavorites = FavoritesClient.read(context);
                if (notificationPageActive()) {
                    // Do not steal focus while the retained QS page is visible.
                    root.setOrganizationMode(NotificationShadeModeClient.read(context));
                    root.setFavorites(activeFavorites);
                }
                FavoritesClient.reportObserved(context, activeFavorites,
                        SystemUiNotificationObserver.itemsSnapshot());
            }
        };
        try {
            context.getContentResolver().registerContentObserver(
                    FavoritesSettingsProvider.RULES_URI, false, observer);
            favoritesObserver = observer;
            favoritesObserverContext = context;
        } catch (RuntimeException error) {
            Log.w(TAG, "Favorites live settings observer unavailable", error);
        }
    }

    private static void stopFavoritesObserver() {
        ContentObserver observer = favoritesObserver;
        Context context = favoritesObserverContext;
        favoritesObserver = null;
        favoritesObserverContext = null;
        if (observer != null && context != null) {
            try {
                context.getContentResolver().unregisterContentObserver(observer);
            } catch (RuntimeException error) {
                Log.w(TAG, "Unable to release Favorites observer", error);
            }
        }
    }

    private static void stopMediaSessions() {
        NativeMediaSessions running = mediaSessions;
        mediaSessions = null;
        if (running != null) running.stop();
    }

    private static int dp(Context context, int value) {
        return (int) (value * context.getResources().getDisplayMetrics().density + 0.5f);
    }

    private static final class SwipeState {
        final float startY;
        final float startX;
        final long downTime;
        boolean opened;
        SwipeState(float y, float x, long time) {
            startY = y;
            startX = x;
            downTime = time;
        }
    }
}
