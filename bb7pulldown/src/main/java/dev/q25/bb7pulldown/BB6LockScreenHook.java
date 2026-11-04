package dev.q25.bb7pulldown;

import android.app.KeyguardManager;
import android.bluetooth.BluetoothAdapter;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.PowerManager;
import android.net.wifi.WifiManager;
import android.telephony.SubscriptionManager;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.ViewTreeObserver;
import android.view.ViewParent;
import android.widget.FrameLayout;

import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Map;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * BBOS6-style lock-screen information banner.
 *
 * Appends one selectable, nonactivating header INSIDE the native SystemUI window.
 * It never creates a new window, changes key dispatch, handles passwords, or modifies
 * native lock-screen credentials. This can later be adapted to a ROM's
 * SystemUI without changing the banner/notification-count model.
 */
public final class BB6LockScreenHook implements IXposedHookLoadPackage {
    private static final String TAG = "BB6-LockScreen";
    private static final String PACKAGE = "com.android.systemui";
    private static final String ROOT =
            "com.android.systemui.shade.NotificationShadeWindowView";
    private static final String PRIMARY =
            "com.android.systemui.bouncer.domain.interactor.PrimaryBouncerInteractor";
    private static final String KEYGUARD_STATE =
            "com.android.systemui.statusbar.policy.KeyguardStateControllerImpl";
    // Verified in the Q25 SystemUI APK: setState(int, boolean), getState().
    private static final String STATUS_BAR_STATE =
            "com.android.systemui.statusbar.StatusBarStateControllerImpl";
    private static final String NATIVE_STACK_ID = "shared_notification_container";
    private static final String ENABLE_PROP = "debug.bb6lockscreen.enabled";

    private static WeakReference<FrameLayout> rootRef = new WeakReference<>(null);
    private static WeakReference<BB6LockScreenHeader> headerRef =
            new WeakReference<>(null);
    private static WeakReference<Object> primaryRef = new WeakReference<>(null);
    private static boolean bouncerRequested;
    private static boolean lastVisible;
    private static Map<String, Integer> lastCounts = Collections.emptyMap();
    private static BroadcastReceiver screenReceiver;
    private static WeakReference<Object> stateControllerRef = new WeakReference<>(null);
    private static ViewTreeObserver.OnGlobalLayoutListener stackLayoutObserver;
    private static WeakReference<FrameLayout> observerRootRef = new WeakReference<>(null);
    private static ViewGroup detachedStackParent;
    private static View detachedStack;
    private static ViewGroup.LayoutParams detachedStackLayoutParams;
    private static int detachedStackIndex = -1;
    private static boolean missingNativeStackLogged;
    private static boolean missingKeyguardStatusBarLogged;

    // This is one compact SystemUI child, not a second overlay window.
    // Only the lock screen owns this instance. The unlocked shade retains
    // its existing BB6ConnectionsPanel and controller unchanged.
    private static BB6ConnectionsPanel lockQuickPanel;
    private static BroadcastReceiver lockQuickReceiver;
    private static Context lockQuickReceiverContext;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lp) {
        if (!PACKAGE.equals(lp.packageName) || !PACKAGE.equals(lp.processName)) return;
        if (!enabled()) {
            Log.i(TAG, "Disabled by " + ENABLE_PROP);
            return;
        }

        // Idempotent; the pull-down component uses this same live listener.
        SystemUiNotificationObserver.install(lp.classLoader);
        NativeQuickSettingsTiles.install(lp.classLoader);
        // Leave native clock/date/carrier and AOD-managed views intact.

        Class<?> rootClass = XposedHelpers.findClassIfExists(ROOT, lp.classLoader);
        if (rootClass == null) {
            Log.e(TAG, "Native notification shade root unavailable");
            return;
        }
        try {
            XposedBridge.hookAllMethods(rootClass, "onAttachedToWindow",
                    new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    if (param.thisObject instanceof FrameLayout) {
                        FrameLayout root = (FrameLayout) param.thisObject;
                        root.post(() -> attach(root));
                    }
                }
            });
            Log.i(TAG, "BBOS6 lock-screen banner hook registered");
        } catch (Throwable error) {
            Log.e(TAG, "Cannot hook native keyguard root: " + brief(error));
            return;
        }

        Class<?> primary = XposedHelpers.findClassIfExists(PRIMARY, lp.classLoader);
        if (primary != null) {
            try {
                XposedBridge.hookAllConstructors(primary, new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam param) {
                        primaryRef = new WeakReference<>(param.thisObject);
                        requestRefresh();
                    }
                });
                XposedBridge.hookAllMethods(primary, "show", new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam param) {
                        if (!Boolean.FALSE.equals(param.getResult())) {
                            bouncerRequested = true;
                            BB6BlueprintHook.scheduleSync();
                            requestRefresh();
                        }
                    }
                });
                XposedBridge.hookAllMethods(primary, "hide", new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam param) {
                        bouncerRequested = false;
                        BB6BlueprintHook.scheduleSync();
                        requestRefresh();
                    }
                });
            } catch (Throwable error) {
                Log.w(TAG, "Could not observe native bouncer: " + brief(error));
            }
        }
        installStateObserver(lp.classLoader);
        Class<?> keyguard = XposedHelpers.findClassIfExists(KEYGUARD_STATE, lp.classLoader);
        if (keyguard != null) {
            try {
                XposedBridge.hookAllMethods(keyguard, "notifyKeyguardState",
                        new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam param) {
                        requestRefresh();
                    }
                });
            } catch (Throwable error) {
                Log.w(TAG, "Could not observe native keyguard state: " + brief(error));
            }
        }
    }

    /** Recheck when native keyguard/shade mode changes; restore unlocked shade. */
    private static void installStateObserver(ClassLoader loader) {
        Class<?> state = XposedHelpers.findClassIfExists(STATUS_BAR_STATE, loader);
        if (state == null) {
            Log.w(TAG, "Native status bar state controller unavailable");
            return;
        }
        try {
            XposedBridge.hookAllConstructors(state, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    stateControllerRef = new WeakReference<>(param.thisObject);
                    BB6BlueprintHook.scheduleSync();
                    requestRefresh();
                }
            });
            XposedBridge.hookAllMethods(state, "setState", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    stateControllerRef = new WeakReference<>(param.thisObject);
                    requestRefresh();
                }
            });
            Log.i(TAG, "Native status state transitions observed");
        } catch (Throwable error) {
            Log.e(TAG, "Native state observer registration failed: " + brief(error));
        }
    }

    static void onNotificationsUpdated() {
        requestRefresh();
    }

    private static void attach(FrameLayout root) {
        if (!enabled() || !root.isAttachedToWindow()) return;
        BB6LockScreenHeader previous = headerRef.get();
        if (rootRef.get() == root && previous != null && previous.getParent() == root) {
            requestRefresh();
            return;
        }
        closeLockscreenQuickSettings(false);
        removeNativeStackWatcher();
        restoreNativeStack();
        // The previous header may still own the native status-bar highlight
        // if SystemUI replaced its window root during a transition.
        if (previous != null) {
            previous.setBb6CompositionActive(false);
            previous.bindNativeStatusBar(null);
        }
        Context context = root.getContext();
        BB6LockScreenHeader header = new BB6LockScreenHeader(context);
        header.setOnStatusActivation(BB6LockScreenHook::openLockscreenQuickSettings);
        header.setVisibility(View.GONE);
        // The header is a single native focus target for trackpad selection.
        // Its own key policy never handles Space: the existing
        // WindowRootView hook still owns Space-to-credential.
        header.setElevation(dp(context, 8));

        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        params.topMargin = nativeStatusBarHeight(root);
        try {
            root.addView(header, params);
        } catch (RuntimeException error) {
            Log.e(TAG, "Cannot attach BB6 lock-screen banner: " + brief(error));
            return;
        }
        rootRef = new WeakReference<>(root);
        headerRef = new WeakReference<>(header);
        BB6BlueprintHook.onRootAttached(root);
        registerNativeStackWatcher(root);
        lastCounts = Collections.emptyMap();
        lastVisible = false;
        registerScreenEvents(context);
        Log.i(TAG, "Banner attached to native SystemUI keyguard root");
        requestRefresh();
    }

    private static void requestRefresh() {
        BB6LockScreenHeader header = headerRef.get();
        if (header != null) header.post(BB6LockScreenHook::refresh);
    }

    private static void refresh() {
        FrameLayout root = rootRef.get();
        BB6LockScreenHeader header = headerRef.get();
        if (root == null || header == null || header.getParent() != root) return;
        if (!enabled()) {
            closeLockscreenQuickSettings(false);
            header.setBb6CompositionActive(false);
            header.bindNativeStatusBar(null);
            header.setVisibility(View.GONE);
            restoreNativeStack();
            return;
        }

        Context context = root.getContext();
        KeyguardManager keyguard = (KeyguardManager)
                context.getSystemService(Context.KEYGUARD_SERVICE);
        PowerManager power = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        boolean locked = keyguard != null && keyguard.isKeyguardLocked();
        if (!locked) bouncerRequested = false;
        boolean shown = BB6NativeChromePolicy.useBb6Composition(
                locked, power != null && power.isInteractive(),
                nativeStatusState(), bouncerRequested || nativeBouncerShowing());
        // The native blueprint owns stock clock/date construction.
        // Our single status/date-time focus target leaves icons passive.
        updateNativeStack(root, shown);


        if (shown != lastVisible) {
            lastVisible = shown;
            Log.i(TAG, "BBOS6 notification strip " + (shown ? "eligible" : "inactive"));
        }
        if (!shown) {
            closeLockscreenQuickSettings(false);
            header.setBb6CompositionActive(false);
            header.bindNativeStatusBar(null);
            header.setVisibility(View.GONE);
            return;
        }
        boolean ownsKeyguard = BB6BlueprintHook.isApplied();
        if (!ownsKeyguard) closeLockscreenQuickSettings(false);
        View statusBar = null;
        if (ownsKeyguard) {
            int statusBarId = root.getResources().getIdentifier(
                    "keyguard_header", "id", PACKAGE);
            if (statusBarId != 0) {
                statusBar = root.findViewById(statusBarId);
            }
            if (statusBar == null && !missingKeyguardStatusBarLogged) {
                missingKeyguardStatusBarLogged = true;
                Log.w(TAG, "Native keyguard_header unavailable for combined "
                        + "status/date-time focus; native views left unchanged");
            } else if (statusBar != null) {
                missingKeyguardStatusBarLogged = false;
            }
        }
        // A single focus target is clockRow. Its focus state applies to the
        // native status bar as well; the notification strip is not focusable.
        header.bindNativeStatusBar(statusBar);
        header.setBb6CompositionActive(ownsKeyguard);
        Map<String, Integer> counts =
                SystemUiNotificationObserver.lockscreenCountsSnapshot(context);
        if (!counts.equals(lastCounts)) {
            lastCounts = counts;
            header.showCounts(counts);
            Log.i(TAG, "Lock-screen notification indicators: apps=" + counts.size());
        }
        // In native BB6 mode, the header also owns time/date. In fallback,
        // the stock clock/date remain the only clock/date on screen.
        header.setVisibility(ownsKeyguard || !counts.isEmpty()
                ? View.VISIBLE : View.GONE);
        // The limited QS panel cannot survive a keyguard/bouncer/doze
        // transition. Re-check the live native state on each refresh.
        if (lockQuickPanel != null && !mayShowLockscreenQuickSettings(root)) {
            closeLockscreenQuickSettings(false);
        }
    }

    /**
     * Lock-screen QS runs inside the existing SystemUI window/blueprint.
     * Do not open the unlocked shade or create a new focusable window.
     *
     * A native tile owns its own security/authentication requirements.
     * There is deliberately NO fallback to Android Settings while locked.
     */
    private static boolean mayShowLockscreenQuickSettings(FrameLayout root) {
        if (root == null || !root.isAttachedToWindow() || !enabled()) return false;
        Context context = root.getContext();
        KeyguardManager keyguard = (KeyguardManager)
                context.getSystemService(Context.KEYGUARD_SERVICE);
        PowerManager power = (PowerManager)
                context.getSystemService(Context.POWER_SERVICE);
        return LockscreenQuickSettingsPolicy.mayShow(
                keyguard != null && keyguard.isKeyguardLocked(),
                power != null && power.isInteractive(),
                isNativeDozing(), isBouncerActive(), BB6BlueprintHook.isApplied(),
                nativeStatusState());
    }

    private static void openLockscreenQuickSettings() {
        FrameLayout root = rootRef.get();
        BB6LockScreenHeader header = headerRef.get();
        if (root == null || header == null || header.getParent() != root
                || !header.isShown() || !mayShowLockscreenQuickSettings(root)) {
            return;
        }
        if (lockQuickPanel != null) {
            lockQuickPanel.focusFirst();
            return;
        }

        Context context = root.getContext();
        BB6ConnectionsPanel panel = new BB6ConnectionsPanel(context,
                new BB6ConnectionsPanel.Listener() {
                    @Override public void onClose() {
                        closeLockscreenQuickSettings(true);
                    }
                    @Override public void onActivate(int kind) {
                        activateLockscreenQuickSetting(kind);
                    }
                }, null, true);

        // Full-size QS remains available ONLY in the existing unlocked shade.
        // The lock-screen panel is compact, occupying the notification area
        // below the status + date/time selection.
        int top = nativeStatusBarHeight(root) + dp(context, 54);
        int remaining = root.getHeight() - top;
        if (remaining < dp(context, 198)) {
            Log.w(TAG, "Not enough lock-screen space for connection controls");
            return;
        }
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                Math.min(dp(context, 220), remaining),
                Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        params.topMargin = top;
        panel.setElevation(dp(context, 12));
        try {
            root.addView(panel, params);
            lockQuickPanel = panel;
            registerLockQuickUpdates(context);
            panel.focusFirst();
            Log.i(TAG, "Limited lockscreen QS opened: mobile data, Wi-Fi, Bluetooth");
        } catch (RuntimeException error) {
            if (panel.getParent() == root) root.removeView(panel);
            Log.e(TAG, "Cannot attach limited lockscreen QS", error);
        }
    }

    private static void activateLockscreenQuickSetting(int kind) {
        FrameLayout root = rootRef.get();
        if (lockQuickPanel == null || !LockscreenQuickSettingsPolicy.mayActivate(
                kind, mayShowLockscreenQuickSettings(root))) {
            Log.w(TAG, "Blocked connection activation outside native keyguard");
            closeLockscreenQuickSettings(false);
            return;
        }

        // QSTileHost and individual tiles are shared with the unlocked QS.
        // The tile's own SystemUI backend enforces any required unlock.
        // No Settings.Panel ACTION_*, direct radio API, or activity fallback.
        if (!NativeQuickSettingsTiles.activate(kind)) {
            Log.w(TAG, "Native lockscreen QS tile missing/unavailable: "
                    + ConnectionControlPolicy.label(kind)
                    + "; no external Settings fallback permitted");
        }
        BB6ConnectionsPanel current = lockQuickPanel;
        if (current != null) {
            current.post(() -> {
                if (lockQuickPanel == current
                        && mayShowLockscreenQuickSettings(rootRef.get())) {
                    current.refresh(NativeConnectionControls.read(current.getContext()));
                }
            });
        }
    }

    private static void closeLockscreenQuickSettings(boolean restoreHeaderFocus) {
        BB6ConnectionsPanel panel = lockQuickPanel;
        lockQuickPanel = null;
        stopLockQuickUpdates();
        if (panel != null && panel.getParent() instanceof ViewGroup) {
            ((ViewGroup) panel.getParent()).removeView(panel);
        }
        if (panel != null) {
            Log.i(TAG, "Limited lockscreen QS closed");
            if (restoreHeaderFocus) {
                FrameLayout root = rootRef.get();
                BB6LockScreenHeader header = headerRef.get();
                if (root != null && header != null
                        && mayShowLockscreenQuickSettings(root)) {
                    header.focusStatusDate();
                }
            }
        }
    }

    private static void registerLockQuickUpdates(Context context) {
        stopLockQuickUpdates();
        IntentFilter filter = new IntentFilter();
        filter.addAction(WifiManager.WIFI_STATE_CHANGED_ACTION);
        filter.addAction(BluetoothAdapter.ACTION_STATE_CHANGED);
        filter.addAction(Intent.ACTION_AIRPLANE_MODE_CHANGED);
        filter.addAction(SubscriptionManager.ACTION_DEFAULT_SUBSCRIPTION_CHANGED);
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override public void onReceive(Context ctx, Intent intent) {
                BB6ConnectionsPanel panel = lockQuickPanel;
                if (panel != null && mayShowLockscreenQuickSettings(rootRef.get())) {
                    panel.refresh(NativeConnectionControls.read(panel.getContext()));
                }
            }
        };
        try {
            context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED);
            lockQuickReceiver = receiver;
            lockQuickReceiverContext = context;
        } catch (RuntimeException error) {
            Log.w(TAG, "Locked QS state receiver unavailable", error);
        }
    }

    private static void stopLockQuickUpdates() {
        BroadcastReceiver receiver = lockQuickReceiver;
        Context context = lockQuickReceiverContext;
        lockQuickReceiver = null;
        lockQuickReceiverContext = null;
        if (receiver != null && context != null) {
            try {
                context.unregisterReceiver(receiver);
            } catch (RuntimeException error) {
                Log.w(TAG, "Locked QS state receiver cleanup unavailable", error);
            }
        }
    }

    /**
     * The whole native notification-list subtree is a child of the SystemUI
     * window root (SharedNotificationContainer in super_notification_shade).
     * While the BB6 lockscreen owns the screen, DETACH this subtree rather
     * than setting INVISIBLE/GONE or leaving an empty constraint-frame.
     *
     * Reattach the same original instance, at its original child index,
     * before the normal SHADE, SHADE_LOCKED, bouncer or AOD presentation.
     * SystemUI keeps its notification listener/pipeline and scroll state.
     */
    private static void updateNativeStack(FrameLayout root, boolean bb6Active) {
        if (!bb6Active) {
            restoreNativeStack();
            return;
        }
        if (detachedStack != null && detachedStack.getParent() == null) return;

        int resourceId = root.getResources().getIdentifier(
                NATIVE_STACK_ID, "id", PACKAGE);
        View stack = resourceId == 0 ? null : root.findViewById(resourceId);
        if (stack == null) {
            if (!missingNativeStackLogged) {
                missingNativeStackLogged = true;
                Log.w(TAG, "Native notification container not found in keyguard root");
            }
            return;
        }
        ViewParent parent = stack.getParent();
        if (!(parent instanceof ViewGroup)) {
            Log.w(TAG, "Native shared notification container has no ViewGroup parent");
            return;
        }
        ViewGroup group = (ViewGroup) parent;
        int index = group.indexOfChild(stack);
        if (index < 0) return;
        ViewGroup.LayoutParams params = stack.getLayoutParams();
        try {
            group.removeView(stack);
            detachedStackParent = group;
            detachedStack = stack;
            detachedStackIndex = index;
            detachedStackLayoutParams = params;
            Log.i(TAG, "Removed complete stock notification subtree from "
                    + "BB6 lock-screen composition; no reserved frame");
        } catch (RuntimeException error) {
            Log.e(TAG, "Native notification subtree detach failed: " + brief(error));
        }
    }

    private static int nativeStatusState() {
        Object controller = stateControllerRef.get();
        if (controller == null) return LockscreenNativeStackPolicy.UNKNOWN;
        try {
            Object value = XposedHelpers.callMethod(controller, "getState");
            return value instanceof Integer
                    ? (Integer) value : LockscreenNativeStackPolicy.UNKNOWN;
        } catch (Throwable error) {
            Log.w(TAG, "Cannot read native shade state: " + brief(error));
            return LockscreenNativeStackPolicy.UNKNOWN;
        }
    }

    private static void restoreNativeStack() {
        if (detachedStack == null) return;
        if (detachedStack.getParent() == null && detachedStackParent != null) {
            try {
                int index = Math.min(detachedStackIndex,
                        detachedStackParent.getChildCount());
                detachedStackParent.addView(detachedStack, index,
                        detachedStackLayoutParams);
                Log.i(TAG, "Reattached original native notification subtree "
                        + "for shade, bouncer or AOD");
            } catch (RuntimeException error) {
                Log.e(TAG, "Cannot restore native notification subtree: "
                        + brief(error));
                return; // Keep handles so a later state update can retry.
            }
        }
        detachedStackParent = null;
        detachedStack = null;
        detachedStackLayoutParams = null;
        detachedStackIndex = -1;
    }

    /**
     * If SystemUI reconstructs its native views or changes their owner,
     * reconcile actual parentage on a layout pass. No global View hook.
     */
    private static void registerNativeStackWatcher(FrameLayout root) {
        removeNativeStackWatcher();
        stackLayoutObserver = () -> {
            if (!enabled() || rootRef.get() != root || !root.isAttachedToWindow()) return;
            // Never detach/reattach View children during an active layout
            // traversal. Reconcile after traversal on the SystemUI UI thread.
            root.post(() -> {
                if (!enabled() || rootRef.get() != root
                        || !root.isAttachedToWindow()) return;
                KeyguardManager km = (KeyguardManager)
                        root.getContext().getSystemService(Context.KEYGUARD_SERVICE);
                boolean locked = km != null && km.isKeyguardLocked();
                PowerManager pm = (PowerManager)
                        root.getContext().getSystemService(Context.POWER_SERVICE);
                boolean compose = BB6NativeChromePolicy.useBb6Composition(
                        locked, pm != null && pm.isInteractive(), nativeStatusState(),
                        bouncerRequested || nativeBouncerShowing());
                updateNativeStack(root, compose);
    
            });
        };
        observerRootRef = new WeakReference<>(root);
        root.getViewTreeObserver().addOnGlobalLayoutListener(stackLayoutObserver);
    }

    private static void removeNativeStackWatcher() {
        FrameLayout previous = observerRootRef.get();
        if (previous != null && stackLayoutObserver != null
                && previous.getViewTreeObserver().isAlive()) {
            previous.getViewTreeObserver().removeOnGlobalLayoutListener(stackLayoutObserver);
        }
        stackLayoutObserver = null;
        observerRootRef = new WeakReference<>(null);
    }

    static void onBlueprintChanged() {
        requestRefresh();
    }

    static boolean isBouncerActive() {
        return bouncerRequested || nativeBouncerShowing();
    }

    static int statusStateForBlueprint() {
        return nativeStatusState();
    }

    static boolean isNativeDozing() {
        Object state = stateControllerRef.get();
        if (state == null) return true;
        try {
            return Boolean.TRUE.equals(XposedHelpers.callMethod(state, "isDozing"));
        } catch (Throwable error) {
            Log.w(TAG, "Native doze state unknown: " + brief(error));
            return true;
        }
    }

    private static boolean nativeBouncerShowing() {
        Object primary = primaryRef.get();
        if (primary == null) return false;
        for (String name : new String[] {"isBouncerShowing", "isFullyShowing", "isInTransit"}) {
            try {
                Method m = primary.getClass().getMethod(name);
                Object value = m.invoke(primary);
                if (Boolean.TRUE.equals(value)) return true;
            } catch (ReflectiveOperationException ignored) {
                // Optional method differs by SystemUI variant.
            }
        }
        return false;
    }

    private static void registerScreenEvents(Context context) {
        if (screenReceiver != null) return;
        screenReceiver = new BroadcastReceiver() {
            @Override public void onReceive(Context ctx, Intent intent) {
                if (Intent.ACTION_SCREEN_OFF.equals(intent.getAction())) {
                    bouncerRequested = false;
                }
                BB6BlueprintHook.scheduleSync();
                requestRefresh();
            }
        };
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_SCREEN_ON);
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        try {
            context.registerReceiver(screenReceiver, filter);
        } catch (RuntimeException error) {
            Log.w(TAG, "Could not observe screen state: " + brief(error));
            screenReceiver = null;
        }
    }

    private static int nativeStatusBarHeight(FrameLayout root) {
        int inset = 0;
        WindowInsets insets = root.getRootWindowInsets();
        if (insets != null) {
            inset = insets.getInsets(WindowInsets.Type.statusBars()).top;
        }
        int id = root.getResources().getIdentifier(
                "status_bar_height", "dimen", "android");
        int fallback = id == 0 ? dp(root.getContext(), 24)
                : root.getResources().getDimensionPixelSize(id);
        return Math.max(inset, fallback);
    }

    private static int dp(Context context, int value) {
        return (int) (context.getResources().getDisplayMetrics().density * value + 0.5f);
    }

    private static boolean enabled() {
        try {
            Class<?> properties = XposedHelpers.findClass("android.os.SystemProperties", null);
            return !"false".equals(XposedHelpers.callStaticMethod(
                    properties, "get", ENABLE_PROP, "true"));
        } catch (Throwable error) {
            Log.w(TAG, "Cannot read feature property: " + brief(error));
            return false;
        }
    }

    private static String brief(Throwable error) {
        return error.getClass().getSimpleName() + ": " + error.getMessage();
    }
}
