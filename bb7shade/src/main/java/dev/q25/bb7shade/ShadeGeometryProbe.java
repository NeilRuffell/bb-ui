package dev.q25.bb7shade;

import android.os.SystemClock;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

import de.robv.android.xposed.XposedHelpers;

/**
 * Bounded instrumentation for a real native notification stack.
 * If notifications are offscreen or transparent in an expanded QS state,
 * width alone is insufficient to diagnose the problem.
 */
final class ShadeGeometryProbe {
    private static final String TAG = "BB7-Shade";
    private static final Map<View, Long> LAST_SAMPLE =
            Collections.synchronizedMap(new WeakHashMap<>());
    private static final long SAMPLE_MS = 2000L;

    static void onStackLayout(View stack) {
        long now = SystemClock.uptimeMillis();
        Long last = LAST_SAMPLE.get(stack);
        if (last != null && now - last < SAMPLE_MS) return;
        LAST_SAMPLE.put(stack, now);
        try {
            String qsFull = "unknown";
            try {
                qsFull = String.valueOf(XposedHelpers.getBooleanField(stack, "mQsFullScreen"));
            } catch (Throwable ignored) { }
            String qsFraction = "unknown";
            try {
                qsFraction = String.valueOf(
                        XposedHelpers.getFloatField(stack, "mQsExpansionFraction"));
            } catch (Throwable ignored) { }
            ViewGroup group = (ViewGroup) stack;
            View firstRow = null;
            int children = 0;
            for (int i = 0; i < group.getChildCount(); i++) {
                View child = group.getChildAt(i);
                if (!isNotificationRow(child)) continue;
                children++;
                if (firstRow == null) firstRow = child;
            }
            View qsFrame = null;
            if (stack.getParent() instanceof ViewGroup) {
                ViewGroup parent = (ViewGroup) stack.getParent();
                int qsId = parent.getResources().getIdentifier(
                        "qs_frame", "id", "com.android.systemui");
                if (qsId != 0) qsFrame = parent.findViewById(qsId);
            }
            Log.i(TAG, "Shade presentation: qsFullScreen=" + qsFull
                    + " qsFraction=" + qsFraction
                    + " qsFrameShown=" + (qsFrame != null && qsFrame.isShown())
                    + " stackShown=" + stack.isShown()
                    + " stackVisibility=" + stack.getVisibility()
                    + " stackAlpha=" + stack.getAlpha()
                    + " stackTranslationY=" + stack.getTranslationY()
                    + " rowViews=" + children
                    + (firstRow == null ? "" :
                        " firstRowShown=" + firstRow.isShown()
                        + " firstRowAlpha=" + firstRow.getAlpha()
                        + " firstRowY=" + firstRow.getY()
                        + " firstRowHeight=" + firstRow.getHeight()));
        } catch (Throwable e) {
            Log.w(TAG, "Native stack geometry probe unavailable ("
                    + e.getClass().getSimpleName() + ")");
        }
    }

    private static final Map<Object, Float> LAST_ALPHA =
            Collections.synchronizedMap(new WeakHashMap<>());
    private static final Map<Object, Long> LAST_ALPHA_TIME =
            Collections.synchronizedMap(new WeakHashMap<>());
    private static final long ALPHA_INTERVAL_MS = 2000L;
    private static final Map<Object, Boolean> LAST_PANEL_QS =
            Collections.synchronizedMap(new WeakHashMap<>());
    private static final Map<Object, Long> LAST_PANEL_SAMPLE =
            Collections.synchronizedMap(new WeakHashMap<>());

    static void onControllerAlpha(Object controller) {
        Object viewObject;
        try {
            viewObject = XposedHelpers.getObjectField(controller, "mView");
        } catch (Throwable ignored) {
            return;
        }
        if (!(viewObject instanceof View)) return;
        View view = (View) viewObject;
        float alpha = view.getAlpha();
        long now = SystemClock.uptimeMillis();
        Float previous = LAST_ALPHA.get(controller);
        Long lastTime = LAST_ALPHA_TIME.get(controller);
        boolean changed = previous == null
                || (previous <= 0.01f) != (alpha <= 0.01f);
        if (!changed && lastTime != null && now - lastTime < ALPHA_INTERVAL_MS) return;
        LAST_ALPHA.put(controller, alpha);
        LAST_ALPHA_TIME.put(controller, now);
        Log.i(TAG, "Notification alpha sources: result=" + alpha
                + " view=" + alphaField(controller, "mMaxAlphaFromView")
                + " unhide=" + alphaField(controller, "mMaxAlphaForUnhide")
                + " rebind=" + alphaField(controller, "mMaxAlphaForRebind")
                + " hub=" + alphaField(controller, "mMaxAlphaForGlanceableHub")
                + " keyguard=" + alphaField(controller, "mMaxAlphaForKeyguard")
                + " stackShown=" + view.isShown()
                + " appRailActive=" + ShadeRailHost.isRailActive());
    }

    static void onPanelExpansion(Object panel, boolean showingKeyguard) {
        // Called on existing SystemUI expansion lifecycle, not on a
        // separately scheduled timer. No personal notification data is read.
        try {
            Object qs = XposedHelpers.getObjectField(panel, "mQsController");
            boolean qsExpanded = qs != null &&
                    XposedHelpers.getBooleanField(qs, "mFullyExpanded");
            long now = SystemClock.uptimeMillis();
            Boolean previous = LAST_PANEL_QS.get(panel);
            Long lastTime = LAST_PANEL_SAMPLE.get(panel);
            if (previous != null && previous == qsExpanded
                    && lastTime != null && now - lastTime < 2500L) return;
            LAST_PANEL_QS.put(panel, qsExpanded);
            LAST_PANEL_SAMPLE.put(panel, now);
            Log.i(TAG, "Native shade expansion: showingKeyguard=" + showingKeyguard
                    + " fullyExpandedQS=" + qsExpanded
                    + " appRailActive=" + ShadeRailHost.isRailActive());
        } catch (Throwable ignored) {
            // Optimized builds may not retain the legacy controller fields.
        }
    }

    private static String alphaField(Object owner, String field) {
        try {
            return String.valueOf(XposedHelpers.getFloatField(owner, field));
        } catch (Throwable error) {
            return "n/a";
        }
    }

    private static boolean isNotificationRow(View child) {
        for (Class<?> type = child.getClass(); type != null; type = type.getSuperclass()) {
            if ("com.android.systemui.statusbar.notification.row.ExpandableNotificationRow"
                    .equals(type.getName())) return true;
        }
        return false;
    }

    private ShadeGeometryProbe() {}
}
