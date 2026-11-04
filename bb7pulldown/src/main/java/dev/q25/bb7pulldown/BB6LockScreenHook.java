package dev.q25.bb7pulldown;

import android.app.KeyguardManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.PowerManager;
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
 * Appends a noninteractive view INSIDE the native SystemUI window. It never
 * creates a new window, changes key dispatch, handles passwords, or modifies
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

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lp) {
        if (!PACKAGE.equals(lp.packageName) || !PACKAGE.equals(lp.processName)) return;
        if (!enabled()) {
            Log.i(TAG, "Disabled by " + ENABLE_PROP);
            return;
        }

        // Idempotent; the pull-down component uses this same live listener.
        SystemUiNotificationObserver.install(lp.classLoader);
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
        removeNativeStackWatcher();
        restoreNativeStack();
        Context context = root.getContext();
        BB6LockScreenHeader header = new BB6LockScreenHeader(context);
        header.setVisibility(View.GONE);
        // Remain outside all trackpad/keyboard focus traversal.
        header.setFocusable(false);
        header.setClickable(false);
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
        // Stock clock/date/carrier views are left native and unduplicated.
        // Only the notification-list subtree is temporarily removed.
        updateNativeStack(root, shown);


        if (shown != lastVisible) {
            lastVisible = shown;
            Log.i(TAG, "BBOS6 notification strip " + (shown ? "eligible" : "inactive"));
        }
        if (!shown) {
            header.setVisibility(View.GONE);
            return;
        }
        boolean ownsKeyguard = BB6BlueprintHook.isApplied();
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
