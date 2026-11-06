package dev.q25.bb7pulldown;

import android.app.Notification;
import android.content.Context;
import android.content.res.ColorStateList;
import android.database.ContentObserver;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.service.notification.StatusBarNotification;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.ViewTreeObserver;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * Presentation-only BB Hub notification indicators in SystemUI's EXISTING
 * status-bar notification icon area.
 *
 * SystemUI keeps its original NotificationIconContainer, status-bar icon
 * views, eligible-entry filtering and notification lifecycle. When Hub mode
 * is selected, a compact row replaces only that container's visible area.
 * App mode restores the original native container without recreating it.
 *
 * A container/field mismatch retains stock icons instead of risking SystemUI.
 */
final class NativeStatusBarHubIcons {
    private static final String TAG = "BB7-StatusHub";
    private static final String PROP = "debug.bb7pulldown.hub_status_icons.enabled";
    private static final String CONTAINER =
            "com.android.systemui.statusbar.phone.NotificationIconContainer";
    private static final String ICON_VIEW =
            "com.android.systemui.statusbar.StatusBarIconView";

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final Runnable UPDATE = NativeStatusBarHubIcons::update;
    private static boolean installed;
    private static Class<?> iconViewClass;

    // These are real views in the SystemUI status-bar window, never a new window.
    // Mutated only on the main thread; the observer and native hooks schedule here.
    private static ViewGroup nativeContainer;
    private static FrameLayout host;
    // The full-width native status-bar root is the positioning anchor.
    // The notification-only host remains the source of native icon eligibility.
    private static FrameLayout centeredHost;
    private static LinearLayout strip;
    private static ContentObserver modeObserver;
    private static String currentMode = NotificationShadeModePolicy.APPS;
    // SystemUI can reapply visibility during icon-container/view-model updates.
    // Guard the complete *notification-only* host immediately before drawing,
    // rather than hiding one container only on the first Hub render.
    private static final Map<View, Integer> hiddenNativeChildren = new IdentityHashMap<>();
    private static ViewTreeObserver trackedTree;
    private static ViewTreeObserver.OnPreDrawListener visibilityGuard;
    private static Map<String, Integer> renderedCounts = Collections.emptyMap();
    private static boolean attachmentLogged;
    private static boolean eligibilityWarningLogged;

    private NativeStatusBarHubIcons() {}

    static void install(ClassLoader loader) {
        if (installed) return;
        if (!enabled()) {
            Log.i(TAG, "Category status indicators disabled by " + PROP);
            return;
        }
        Class<?> groupClass = XposedHelpers.findClassIfExists(CONTAINER, loader);
        Class<?> iconClass = XposedHelpers.findClassIfExists(ICON_VIEW, loader);
        if (groupClass == null || iconClass == null) {
            Log.w(TAG, "Native status-bar icon classes missing; keeping app icons");
            return;
        }
        try {
            // Unlike upstream source APIs, the notification field is confirmed
            // in the Q25 compiled SystemUI (see NATIVE_COLOR_STATUS_ICONS.md).
            groupClass.getDeclaredMethod("onViewAdded", View.class);
            groupClass.getDeclaredMethod("onViewRemoved", View.class);
            iconClass.getDeclaredMethod("updateDrawable", boolean.class);
            iconClass.getDeclaredField("mNotification");
        } catch (ReflectiveOperationException error) {
            Log.w(TAG, "Q25 native status icon signatures differ; keeping app icons", error);
            return;
        }
        try {
            iconViewClass = iconClass;
            XposedBridge.hookAllConstructors(groupClass, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    if (param.hasThrowable() || !(param.thisObject instanceof ViewGroup)) return;
                    ViewGroup container = (ViewGroup) param.thisObject;
                    container.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
                        @Override public void onViewAttachedToWindow(View v) {
                            container.post(() -> onAttached(container));
                        }

                        @Override public void onViewDetachedFromWindow(View v) {
                            MAIN.post(() -> onDetached(container));
                        }
                    });
                    if (container.isAttachedToWindow()) container.post(
                            () -> onAttached(container));
                }
            });
            XC_MethodHook hierarchyChanged = new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    if (!param.hasThrowable() && param.thisObject == nativeContainer) {
                        schedule();
                    }
                }
            };
            XposedBridge.hookAllMethods(groupClass, "onViewAdded", hierarchyChanged);
            XposedBridge.hookAllMethods(groupClass, "onViewRemoved", hierarchyChanged);

            // Native icon contents can change without a child being added.
            XposedBridge.hookAllMethods(iconClass, "updateDrawable", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    if (param.hasThrowable() || !(param.thisObject instanceof View)) return;
                    if (isChildOf((View) param.thisObject, nativeContainer)) schedule();
                }
            });
            // Refresh contrast when Android updates its own status bar icons.
            XposedBridge.hookAllMethods(iconClass, "onDarkChanged", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    if (!param.hasThrowable() && param.thisObject instanceof View
                            && isChildOf((View) param.thisObject, nativeContainer)) {
                        MAIN.post(NativeStatusBarHubIcons::refreshTint);
                    }
                }
            });
            installed = true;
            Log.i(TAG, "Native Q25 status-bar category hooks registered");
        } catch (Throwable error) {
            Log.e(TAG, "Cannot install status category hooks; native icons retained", error);
        }
    }

    /** SystemUI's existing notification listener may update classification metadata. */
    static void onNotificationsUpdated() {
        schedule();
    }

    private static void onAttached(ViewGroup candidate) {
        if (!candidate.isAttachedToWindow()) return;
        FrameLayout resolved = findNotificationIconArea(candidate);
        FrameLayout centerRoot = findStatusBarRoot(candidate);
        if (resolved == null || centerRoot == null) return;
        // Shelf, AOD and keyguard containers have no matching status-bar path.
        if (nativeContainer == candidate && host == resolved
                && centeredHost == centerRoot && strip != null) {
            schedule();
            return;
        }

        release();
        nativeContainer = candidate;
        host = resolved;
        centeredHost = centerRoot;
        strip = new LinearLayout(candidate.getContext());
        strip.setOrientation(LinearLayout.HORIZONTAL);
        strip.setGravity(Gravity.CENTER_VERTICAL);
        strip.setFocusable(false);
        strip.setClickable(false);
        strip.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        strip.setVisibility(View.GONE);
        // Center the group against the *entire* actual status bar, not the
        // left-side wrap-content notification slot. No new window/overlay.
        centerRoot.addView(strip, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT,
                Gravity.CENTER_HORIZONTAL | Gravity.CENTER_VERTICAL));
        installVisibilityGuard();

        modeObserver = new ContentObserver(MAIN) {
            @Override public void onChange(boolean selfChange) {
                refreshMode();
            }
        };
        try {
            candidate.getContext().getContentResolver().registerContentObserver(
                    FavoritesSettingsProvider.MODE_URI, false, modeObserver);
        } catch (RuntimeException error) {
            Log.w(TAG, "Live status bar mode setting unavailable", error);
            modeObserver = null;
        }
        if (!attachmentLogged) {
            attachmentLogged = true;
            Log.i(TAG, "Category indicators attached to native notification_icon_area");
        }
        refreshMode();
    }

    private static FrameLayout findNotificationIconArea(ViewGroup candidate) {
        Context ctx = candidate.getContext();
        int expected = ctx.getResources().getIdentifier(
                "notification_icon_area", "id", "com.android.systemui");
        if (expected == 0) return null;
        ViewParent ancestor = candidate.getParent();
        while (ancestor instanceof View) {
            View view = (View) ancestor;
            if (view.getId() == expected && view instanceof FrameLayout) {
                return (FrameLayout) view;
            }
            ancestor = view.getParent();
        }
        return null;
    }

    /** Only the actual PhoneStatusBarView, never an AOD or shelf parent. */
    private static FrameLayout findStatusBarRoot(ViewGroup candidate) {
        Context ctx = candidate.getContext();
        int expected = ctx.getResources().getIdentifier(
                "status_bar", "id", "com.android.systemui");
        if (expected == 0) return null;
        ViewParent ancestor = candidate.getParent();
        while (ancestor instanceof View) {
            View view = (View) ancestor;
            if (view.getId() == expected && view instanceof FrameLayout) {
                return (FrameLayout) view;
            }
            ancestor = view.getParent();
        }
        return null;
    }

    private static void onDetached(ViewGroup candidate) {
        if (nativeContainer == candidate && !candidate.isAttachedToWindow()) release();
    }

    private static void refreshMode() {
        ViewGroup target = nativeContainer;
        if (target == null) return;
        currentMode = NotificationShadeModeClient.read(target.getContext());
        schedule();
    }

    private static void schedule() {
        if (!installed) return;
        MAIN.removeCallbacks(UPDATE);
        MAIN.post(UPDATE);
    }

    private static void update() {
        if (nativeContainer == null || strip == null
                || host == null || centeredHost == null
                || !nativeContainer.isAttachedToWindow()
                || strip.getParent() != centeredHost) return;
        if (!NotificationShadeModePolicy.TYPES.equals(currentMode)) {
            restoreNative();
            strip.setVisibility(View.GONE);
            return;
        }

        // Only records included by the real native status-bar icon binder.
        // If its layout or Q25 notification references cannot be read safely,
        // show the untouched native icons rather than an empty/broken Hub.
        List<NotificationItem> eligible = nativeStatusBarItems();
        if (eligible == null) {
            restoreNative();
            strip.setVisibility(View.GONE);
            if (!eligibilityWarningLogged) {
                eligibilityWarningLogged = true;
                Log.w(TAG, "Cannot resolve native eligible icons; keeping stock status icons");
            }
            return;
        }

        Map<String, Integer> counts = StatusBarCategoryCounts.count(eligible);
        if (!counts.equals(renderedCounts)) {
            render(counts);
            renderedCounts = counts;
        }
        // The icon binder can turn original app icons back on after a change.
        // Hide every original child of notification_icon_area, not only the
        // container seen during our initial attach; keep original visibility
        // values for exact App-mode restoration.
        strip.setVisibility(View.VISIBLE);
        suppressNativeChildren();
        refreshTint();
    }

    /** Return null on an unrecognized native view to trigger the stock fallback. */
    private static List<NotificationItem> nativeStatusBarItems() {
        Map<String, NotificationItem> observed = new HashMap<>();
        for (NotificationItem item : SystemUiNotificationObserver.itemsSnapshot()) {
            if (item != null) observed.put(item.key, item);
        }
        Map<String, NotificationItem> included = new LinkedHashMap<>();
        ViewGroup container = nativeContainer;
        if (container == null) return null;
        for (int index = 0; index < container.getChildCount(); index++) {
            View child = container.getChildAt(index);
            if (child.getVisibility() != View.VISIBLE) continue;
            if (!iconViewClass.isInstance(child)) return null;
            try {
                Object raw = XposedHelpers.getObjectField(child, "mNotification");
                if (!(raw instanceof StatusBarNotification)) return null;
                StatusBarNotification sbn = (StatusBarNotification) raw;
                Notification notification = sbn.getNotification();
                if (notification == null) return null;
                NotificationItem matched = observed.get(sbn.getKey());
                if (matched != null && Objects.equals(matched.packageName, sbn.getPackageName())
                        && Objects.equals(matched.groupKey, sbn.getGroupKey())
                        && matched.postedAt == sbn.getPostTime()
                        && Objects.equals(matched.nativeCategory, notification.category)) {
                    included.put(sbn.getKey(), matched);
                } else {
                    included.put(sbn.getKey(), fromNativeIcon(sbn));
                }
            } catch (RuntimeException error) {
                Log.w(TAG, "Cannot inspect real native status icon", error);
                return null;
            }
        }
        return new ArrayList<>(included.values());
    }

    /** Native metadata fallback when the observer has not refreshed yet. */
    private static NotificationItem fromNativeIcon(StatusBarNotification sbn) {
        Notification n = sbn.getNotification();
        Bundle extras = n.extras;
        String style = extras == null ? "" : extras.getString(Notification.EXTRA_TEMPLATE, "");
        boolean video = extras != null
                && extras.getBoolean(Notification.EXTRA_CALL_IS_VIDEO, false);
        List<String> conversations = new ArrayList<>(1);
        String shortcut = n.getShortcutId();
        if (shortcut != null && !shortcut.isEmpty()) conversations.add(shortcut);
        return new NotificationItem(sbn.getKey(), sbn.getPackageName(), "", "",
                sbn.getPostTime(), sbn.isClearable(), Collections.emptyList(),
                sbn.getGroupKey(), (n.flags & Notification.FLAG_GROUP_SUMMARY) != 0,
                false, conversations, "", n.category, style, video);
    }

    private static void render(Map<String, Integer> counts) {
        LinearLayout row = strip;
        if (row == null) return;
        row.removeAllViews();
        Context context = row.getContext();
        int spacing = dp(context, 4);
        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
            String category = entry.getKey();
            int count = entry.getValue();
            LinearLayout pair = new LinearLayout(context);
            pair.setOrientation(LinearLayout.HORIZONTAL);
            pair.setGravity(Gravity.CENTER_VERTICAL);
            pair.setFocusable(false);
            pair.setClickable(false);
            pair.setContentDescription(count + " " + NotificationTypePolicy.label(category)
                    + (count == 1 ? " notification" : " notifications"));

            TextView number = new TextView(context);
            number.setText(Integer.toString(count));
            number.setTextSize(11);
            number.setSingleLine(true);
            number.setIncludeFontPadding(false);
            number.setGravity(Gravity.CENTER);
            number.setTypeface(null, android.graphics.Typeface.BOLD);
            number.setFocusable(false);
            number.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            pair.addView(number, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));

            ImageView image = new ImageView(context);
            image.setImageDrawable(new NotificationTypeIconDrawable(category));
            image.setScaleType(ImageView.ScaleType.FIT_CENTER);
            image.setPadding(dp(context, 2), dp(context, 2),
                    dp(context, 1), dp(context, 2));
            image.setFocusable(false);
            image.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            pair.addView(image, new LinearLayout.LayoutParams(dp(context, 21),
                    dp(context, 23)));
            LinearLayout.LayoutParams placement = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.MATCH_PARENT);
            placement.setMarginEnd(spacing);
            row.addView(pair, placement);
        }
    }

    private static void refreshTint() {
        if (strip == null || host == null || strip.getVisibility() != View.VISIBLE) return;
        int color = Color.WHITE;
        Context ctx = host.getContext();
        int clockId = ctx.getResources().getIdentifier("clock", "id", "com.android.systemui");
        if (clockId != 0) {
            View clock = host.getRootView().findViewById(clockId);
            if (clock instanceof TextView) color = ((TextView) clock).getCurrentTextColor();
        }
        for (int i = 0; i < strip.getChildCount(); i++) {
            View child = strip.getChildAt(i);
            if (!(child instanceof LinearLayout)) continue;
            LinearLayout pair = (LinearLayout) child;
            for (int j = 0; j < pair.getChildCount(); j++) {
                View element = pair.getChildAt(j);
                if (element instanceof TextView) ((TextView) element).setTextColor(color);
                if (element instanceof ImageView) {
                    ((ImageView) element).setImageTintList(ColorStateList.valueOf(color));
                }
            }
        }
    }

    /** Keep only Hub indicators visible without detaching any native icons. */
    private static void suppressNativeChildren() {
        FrameLayout area = host;
        if (area == null || strip == null || centeredHost == null
                || strip.getParent() != centeredHost) return;
        for (int i = 0; i < area.getChildCount(); i++) {
            View nativeChild = area.getChildAt(i);
            if (nativeChild == strip) continue;
            if (!hiddenNativeChildren.containsKey(nativeChild)) {
                hiddenNativeChildren.put(nativeChild, nativeChild.getVisibility());
            }
            if (nativeChild.getVisibility() != View.GONE) {
                nativeChild.setVisibility(View.GONE);
            }
        }
        // Centered strip lives outside the original notification-only host.
        // Native group suppression must never remove or move it.
    }

    /**
     * Native SystemUI can change a container's visibility after our update.
     * Pre-draw reasserts Hub-only presentation in the same status-bar host.
     * Does not change the native notification collection or remove its views.
     */
    private static void installVisibilityGuard() {
        FrameLayout area = host;
        if (area == null) return;
        ViewTreeObserver tree = area.getViewTreeObserver();
        if (!tree.isAlive()) return;
        visibilityGuard = () -> {
            if (NotificationShadeModePolicy.TYPES.equals(currentMode)
                    && strip != null && strip.getVisibility() == View.VISIBLE) {
                suppressNativeChildren();
            }
            return true;
        };
        tree.addOnPreDrawListener(visibilityGuard);
        trackedTree = tree;
    }

    private static void restoreNative() {
        // Never blindly make all icons visible: restore each native child's
        // captured visibility so hidden native items stay hidden.
        for (Map.Entry<View, Integer> entry : hiddenNativeChildren.entrySet()) {
            View nativeChild = entry.getKey();
            if (nativeChild.getParent() == host
                    && nativeChild.getVisibility() == View.GONE) {
                nativeChild.setVisibility(entry.getValue());
            }
        }
        hiddenNativeChildren.clear();
        renderedCounts = Collections.emptyMap();
    }

    private static void release() {
        MAIN.removeCallbacks(UPDATE);
        if (trackedTree != null && visibilityGuard != null && trackedTree.isAlive()) {
            trackedTree.removeOnPreDrawListener(visibilityGuard);
        }
        trackedTree = null;
        visibilityGuard = null;
        if (nativeContainer != null && modeObserver != null) {
            try {
                nativeContainer.getContext().getContentResolver()
                        .unregisterContentObserver(modeObserver);
            } catch (RuntimeException error) {
                Log.w(TAG, "Cannot release Hub status mode observer", error);
            }
        }
        restoreNative();
        if (strip != null && strip.getParent() instanceof ViewGroup) {
            ((ViewGroup) strip.getParent()).removeView(strip);
        }
        nativeContainer = null;
        strip = null;
        host = null;
        centeredHost = null;
        modeObserver = null;
        currentMode = NotificationShadeModePolicy.APPS;
    }

    private static boolean isChildOf(View view, ViewGroup ancestor) {
        if (view == null || ancestor == null) return false;
        ViewParent parent = view.getParent();
        while (parent instanceof View) {
            if (parent == ancestor) return true;
            parent = parent.getParent();
        }
        return false;
    }

    private static int dp(Context context, int dip) {
        return Math.round(dip * context.getResources().getDisplayMetrics().density);
    }

    private static boolean enabled() {
        try {
            Class<?> props = Class.forName("android.os.SystemProperties");
            return (Boolean) XposedHelpers.callStaticMethod(
                    props, "getBoolean", PROP, true);
        } catch (Throwable error) {
            Log.w(TAG, "Cannot read category icon rollback property; using stock icons", error);
            return false;
        }
    }
}
