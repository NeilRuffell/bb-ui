package dev.q25.bb7pulldown;

import dev.q25.trackpad.theme.BB7Theme;

import android.app.KeyguardManager;
import android.content.Context;
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
import android.content.pm.PackageManager;
import android.widget.FrameLayout;
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
    static final String RECENTS_VISIBILITY_ACTION =
            "dev.q25.trackpad.action.RECENTS_VISIBILITY";
    static final String RECENTS_VISIBILITY_EXTRA = "visible";
    private static boolean recentsVisible;
    private static boolean nativeShadeExpanded;
    private static BroadcastReceiver recentsVisibilityReceiver;
    // Intercept SystemUI's native Recents command before Quickstep changes activities.
    private static final String COMMAND_QUEUE = "com.android.systemui.statusbar.CommandQueue";
    private static Object nativeRecentsQueue;
    private static boolean allowOneNativeRecents;
    // Android internal WindowManager.LayoutParams.TYPE_STATUS_BAR_SUB_PANEL
    // is hidden from public SDK stubs; framework value is FIRST_SYSTEM_WINDOW+17.
    private static final int STATUS_BAR_SUB_PANEL_WINDOW_TYPE = 2017;
    private static final Map<View, SwipeState> SWIPES =
            Collections.synchronizedMap(new WeakHashMap<>());
    private static boolean shown;
    private static WindowManager windowManager;
    private static UnifiedShadeHost shadeHost;
    private static TwoColumnPanel panel;
    private static NativeMediaSessions mediaSessions;
    private static boolean quickShown;
    private static BB6ConnectionsPanel quickPanel;
    private static BroadcastReceiver quickStateReceiver;
    private static Context quickReceiverContext;
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
    private static BroadcastReceiver focusReceiver;

    /**
     * One navigation/session owner for the native status bar and both shade
     * pages. These callbacks are the current LSPosed integration adapter;
     * ShadeUiController, the host, and the pages depend on no Xposed types.
     */
    private static final ShadeUiController NAVIGATION = new ShadeUiController(
            new ShadeUiController.Listener() {
                @Override public void onStatusFocusChanged(int zone) {
                    updateStatusAppearance();
                }

                @Override public void onShowPage(int page) {
                    UnifiedShadeHost current = shadeHost;
                    if (current != null) {
                        switchShadePage(page);
                        return;
                    }
                    View anchor = focusWindow;
                    if (anchor == null) anchor = nativeStatusBar.get();
                    if (anchor == null) return;
                    Context context = anchor.getContext();
                    if (focusWindow != null) dismissStatusFocus();
                    showShade(context, page);
                }

                @Override public void onFocusShadeContent() {
                    UnifiedShadeHost current = shadeHost;
                    if (current != null) current.focusContent();
                }

                @Override public void onExit() {
                    if (shadeHost != null) dismissShade();
                    else dismissStatusFocus();
                }

                @Override public void onNativeRecents() {
                    dismissStatusFocus();
                    openNativeRecents();
                }
            });

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lp) {
        if (!PACKAGE.equals(lp.packageName) || !PACKAGE.equals(lp.processName)) return;

        if (!enabled()) {
            Log.i(TAG, "Disabled by " + ENABLE_PROP + "; stock status bar retained");
            return;
        }
        installRecentsInterception(lp.classLoader);
        installNativeShadeState(lp.classLoader);
        SystemUiNotificationObserver.install(lp.classLoader);
        NativeQuickSettingsTiles.install(lp.classLoader);
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
                        View bar = (View) param.thisObject;
                        nativeStatusBar = new WeakReference<>(bar);
                        registerRecentsVisibilityReceiver(bar.getContext());
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
                    if (allowOneNativeRecents) {
                        allowOneNativeRecents = false;
                        Log.i(TAG, "Native Recents requested explicitly by Down");
                        return;
                    }
                    View bar = nativeStatusBar.get();
                    if (bar == null || locked(bar.getContext())) {
                        Log.w(TAG, "Recents toggle: native bar unavailable/locked; stock retained");
                        return;
                    }
                    nativeRecentsQueue = param.thisObject;
                    // CommandQueue entry may be on a Binder thread. Window operations
                    // must run on SystemUI's main thread; consume the command now.
                    param.setResult(null); // void command: prevent Overview transition
                    new Handler(Looper.getMainLooper()).post(() -> {
                        if (focusWindow != null) dismissStatusFocus();
                        else showStatusFocus(bar.getContext());
                    });
                    Log.i(TAG, "Native Recents command consumed; foreground app preserved");
                }
            });
            Log.i(TAG, "Native Recents command interception registered");
        } catch (Throwable error) {
            Log.e(TAG, "Unable to register native Recents interception", error);
        }
    }

    private static void openNativeRecents() {
        Object queue = nativeRecentsQueue;
        if (queue == null) {
            Log.w(TAG, "Native Recents command unavailable");
            return;
        }
        allowOneNativeRecents = true;
        try {
            XposedHelpers.callMethod(queue, "toggleRecentApps");
        } catch (Throwable error) {
            allowOneNativeRecents = false;
            Log.e(TAG, "Unable to open native Recents", error);
        }
    }

    private static void dismissStatusFocus() {
        NAVIGATION.leaveStandaloneStatusBar();
        View root = focusWindow;
        WindowManager wm = focusManager;
        focusWindow = null;
        focusManager = null;
        updateStatusAppearance();
        if (root != null && wm != null) {
            try { wm.removeViewImmediate(root); }
            catch (Throwable error) { Log.w(TAG, "Status focus removal failed", error); }
        }
    }

    private static void showStatusFocus(Context context) {
        if (shown || openingOrClosing || quickShown
                || focusWindow != null || locked(context)) return;
        WindowManager wm = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        if (wm == null) return;
        // Standalone focus is only an input surface. It uses the exact same
        // navigation state as the fully opened shade; it owns no controller.
        FrameLayout root = new FrameLayout(context) {
            @Override public boolean dispatchKeyEvent(KeyEvent event) {
                if (NAVIGATION.handleKey(event)) return true;
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
            root.requestFocus();
            NAVIGATION.enterStandaloneStatusBar();
            Log.i(TAG, "BlackBerry key entered native status-bar navigation");
        } catch (Throwable error) {
            Log.e(TAG, "Unable to acquire status-bar focus; native input retained", error);
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
            if (!enabled() || shadeHost != null || openingOrClosing || locked(view.getContext())
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
        if (shadeHost == null) showShade(context, UnifiedShadeHost.QUICK_SETTINGS);
        else switchShadePage(UnifiedShadeHost.QUICK_SETTINGS);
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
                panel.setFavorites(activeFavorites);
                panel.setNotifications(SystemUiNotificationObserver.itemsSnapshot());
            }
            FavoritesClient.reportObserved(context, activeFavorites,
                    SystemUiNotificationObserver.itemsSnapshot());
            if (mediaSessions != null) mediaSessions.refresh();
        } else if (quickPanel != null) {
            quickPanel.refresh(NativeConnectionControls.read(context));
        }
        root.setPage(next);
        shown = next == UnifiedShadeHost.NOTIFICATIONS;
        quickShown = next == UnifiedShadeHost.QUICK_SETTINGS;
        updateStatusAppearance();
        root.focusContent();
        Log.i(TAG, "Unified shade switched page=" + next + " without animation");
    }

    private static void activateQuickConnection(int kind) {
        BB6ConnectionsPanel root = quickPanel;
        if (root == null || !quickShown) return;
        Context context = root.getContext();
        if (locked(context)) {
            dismissShadeImmediately();
            return;
        }
        // Remove our overlay before native SystemUI displays its Internet/BT UI.
        dismissShadeImmediately();
        if (!NativeQuickSettingsTiles.activate(kind)
                && !NativeConnectionControls.openNativeSettings(context, kind)) {
            // Final compatibility path: never strand the user without working QS.
            try {
                Object statusbar = context.getSystemService("statusbar");
                if (statusbar != null) {
                    XposedHelpers.callMethod(statusbar, "expandSettingsPanel");
                }
            } catch (Throwable error) {
                Log.e(TAG, "Unable to open any native connectivity controls", error);
            }
        }
    }

    private static void registerQuickConnectionStateReceiver(Context context) {
        stopQuickConnectionStateReceiver();
        IntentFilter filter = new IntentFilter();
        filter.addAction(WifiManager.WIFI_STATE_CHANGED_ACTION);
        filter.addAction(BluetoothAdapter.ACTION_STATE_CHANGED);
        filter.addAction(Intent.ACTION_AIRPLANE_MODE_CHANGED);
        filter.addAction(SubscriptionManager.ACTION_DEFAULT_SUBSCRIPTION_CHANGED);
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override public void onReceive(Context caller, Intent intent) {
                BB6ConnectionsPanel root = quickPanel;
                if (root != null && quickShown) {
                    root.refresh(NativeConnectionControls.read(root.getContext()));
                }
            }
        };
        try {
            // Source extras are not trusted; every refresh re-reads system managers.
            context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED);
            quickStateReceiver = receiver;
            quickReceiverContext = context;
        } catch (RuntimeException error) {
            Log.w(TAG, "Quick Settings state listener unavailable", error);
        }
    }

    private static void stopQuickConnectionStateReceiver() {
        BroadcastReceiver receiver = quickStateReceiver;
        Context context = quickReceiverContext;
        quickStateReceiver = null;
        quickReceiverContext = null;
        if (receiver != null && context != null) {
            try { context.unregisterReceiver(receiver); }
            catch (IllegalArgumentException ignored) { }
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

    private static void updateStatusFocus(int zone) {
        updateStatusAppearance();
    }

    private static void updateStatusAppearance() {
        UnifiedShadeHost currentShade = shadeHost;
        if (currentShade != null) {
            currentShade.setStatusBarFocused(shown && panelStatusFocused()
                    || quickShown && quickPanelStatusFocused());
        }
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
            boolean focused = focusWindow != null
                    || shown && panelStatusFocused()
                    || quickShown && quickPanelStatusFocused();
            boolean decorated = focused || shown || openingOrClosing
                    || quickShown
                    || recentsVisible || nativeShadeExpanded;
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
            int selectedZone = focusWindow != null ? focusZone
                    : quickShown && quickPanelStatusFocused() ? quickPanelFocusedZone()
                    : shown && panelStatusFocused() ? panelFocusedZone() : -1;
            if (selectedZone < 0 || target.getWidth() <= 0) {
                target.setBackground(base);
                return;
            }
            GradientDrawable selection = BB7Theme.selected(
                    dp(target.getContext(), 3), dp(target.getContext(), 1));
            LayerDrawable background = new LayerDrawable(new Drawable[]{base, selection});
            int mid = target.getWidth() / 2;
            background.setLayerInset(1, selectedZone == 0 ? 0 : mid, 0,
                    selectedZone == 0 ? target.getWidth() - mid : 0, 0);
            target.setBackground(background);
        });
    }

    private static boolean panelStatusFocused() {
        return panel != null && panel.statusFocusZone() >= 0;
    }

    private static int panelFocusedZone() {
        return panel == null ? -1 : panel.statusFocusZone();
    }

    private static boolean quickPanelStatusFocused() {
        return quickPanel != null && quickPanel.statusFocusZone() >= 0;
    }

    private static int quickPanelFocusedZone() {
        return quickPanel == null ? -1 : quickPanel.statusFocusZone();
    }

    /** Quickstep sends only the native overview visibility transition. */
    private static void registerRecentsVisibilityReceiver(Context context) {
        if (recentsVisibilityReceiver != null) return;
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override public void onReceive(Context receiverContext, Intent intent) {
                if (!RECENTS_VISIBILITY_ACTION.equals(intent.getAction())) return;
                // Do not accept untrusted apps spoofing launcher state.
                int senderUid = getSentFromUid();
                String[] packages = receiverContext.getPackageManager()
                        .getPackagesForUid(senderUid);
                boolean fromLauncher = false;
                if (packages != null) {
                    for (String name : packages) {
                        if ("com.android.launcher3".equals(name)) {
                            fromLauncher = true;
                            break;
                        }
                    }
                }
                if (!fromLauncher) {
                    Log.w(TAG, "Ignored overview visibility from non-launcher UID");
                    return;
                }
                recentsVisible = intent.getBooleanExtra(RECENTS_VISIBILITY_EXTRA, false);
                Log.i(TAG, "Native Quickstep overview visible=" + recentsVisible);
                updateStatusAppearance();
            }
        };
        try {
            context.registerReceiver(receiver,
                    new IntentFilter(RECENTS_VISIBILITY_ACTION),
                    Context.RECEIVER_EXPORTED);
            recentsVisibilityReceiver = receiver;
        } catch (Throwable error) {
            Log.e(TAG, "Cannot observe native Quickstep visibility", error);
        }
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

    private static void showPanel(Context context) {
        if (shadeHost != null) {
            switchShadePage(UnifiedShadeHost.NOTIFICATIONS);
        } else {
            showShade(context, UnifiedShadeHost.NOTIFICATIONS);
        }
    }

    /**
     * One window per shade session. Both pages remain attached until the shade
     * really closes; internal page changes only toggle view visibility.
     */
    private static void showShade(Context context, int initialPage) {
        if (focusWindow != null) dismissStatusFocus();
        if (shadeHost != null || openingOrClosing || !enabled() || locked(context)) return;
        openingOrClosing = true;
        WindowManager manager = (WindowManager)
                context.getSystemService(Context.WINDOW_SERVICE);
        if (manager == null) {
            openingOrClosing = false;
            Log.w(TAG, "Shade WindowManager unavailable");
            return;
        }

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
                }, new TwoColumnPanel.StatusZoneListener() {
                    @Override public void onZoneFocus(int zone) {
                        updateStatusFocus(zone);
                    }
                    @Override public void onSystemControls() {
                        showSystemControls(context);
                    }
                }, nativeMedia::send);
        BB6ConnectionsPanel connections = new BB6ConnectionsPanel(context,
                new BB6ConnectionsPanel.Listener() {
                    @Override public void onClose() {
                        dismissShade();
                    }
                    @Override public void onActivate(int kind) {
                        activateQuickConnection(kind);
                    }
                    @Override public void onStatusZoneFocus(int zone) {
                        updateStatusFocus(zone);
                    }
                    @Override public void onShowNotifications() {
                        switchShadePage(UnifiedShadeHost.NOTIFICATIONS);
                    }
                });
        notifications.setFavorites(activeFavorites);
        notifications.setNotifications(SystemUiNotificationObserver.itemsSnapshot());

        UnifiedShadeHost root = new UnifiedShadeHost(
                context, notifications, connections, initialPage, PullDownHook::dismissShade);
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
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
        int height = params.height;
        root.setTranslationY(-height);
        boolean attached = false;
        try {
            manager.addView(root, params);
            attached = true;
            shadeHost = root;
            windowManager = manager;
            panel = notifications;
            quickPanel = connections;
            shown = initialPage == UnifiedShadeHost.NOTIFICATIONS;
            quickShown = initialPage == UnifiedShadeHost.QUICK_SETTINGS;
            registerScreenOffReceiver(context);
            registerQuickConnectionStateReceiver(context);
            mediaSessions = nativeMedia;
            nativeMedia.start();
            registerFavoritesObserver(context, notifications);
            updateStatusAppearance();

            Log.i(TAG, "Unified shade window attached: initialPage=" + initialPage
                    + " heightPx=" + height);
            root.post(() -> {
                if (shadeHost != root) return;
                root.animate().translationY(0f).setDuration(220).withEndAction(() -> {
                    if (shadeHost == root) {
                        openingOrClosing = false;
                        root.focusContent();
                    }
                }).start();
            });
        } catch (Throwable error) {
            Log.e(TAG, "Unable to attach unified shade; rolling back", error);
            if (shadeHost == root) {
                dismissShadeImmediately();
            } else if (attached) {
                try { manager.removeViewImmediate(root); }
                catch (Throwable rollbackError) {
                    Log.w(TAG, "Unable to remove failed unified shade window", rollbackError);
                }
                openingOrClosing = false;
            } else {
                openingOrClosing = false;
            }
        }
        } catch (Throwable initializationError) {
            openingOrClosing = false;
            Log.e(TAG, "Unable to initialize unified shade pages", initializationError);
        }
    }

    private static void dismissPanel() {
        dismissShade();
    }

    private static void dismissShade() {
        UnifiedShadeHost root = shadeHost;
        if (root == null || openingOrClosing) return;
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
        shown = false;
        quickShown = false;
        try {
            current.clearStatusBarFocus();
            current.animate().cancel();
            if (windowManager != null) windowManager.removeViewImmediate(current);
        } catch (Throwable error) {
            Log.w(TAG, "Unable to remove unified shade window", error);
        } finally {
            stopFavoritesObserver();
            stopQuickConnectionStateReceiver();
            stopMediaSessions();
            unregisterScreenOffReceiver();
            panel = null;
            quickPanel = null;
            windowManager = null;
            openingOrClosing = false;
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
                if (shown) {
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
        boolean opened;
        SwipeState(float y, float x) { startY = y; startX = x; }
    }
}
