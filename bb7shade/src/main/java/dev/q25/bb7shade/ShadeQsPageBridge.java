package dev.q25.bb7shade;

import android.util.Log;
import android.view.View;
import android.view.ViewGroup;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

import de.robv.android.xposed.XposedHelpers;

/**
 * Partial Notifications page integration. The Q25 may be displaying the
 * fully expanded native Quick Settings page (which moves notifications
 * offscreen) when the BB7 app rail is mounted.
 *
 * Use SystemUI's own QuickSettingsControllerImpl.closeQs() rather than
 * attempting to reposition, duplicate or force visibility of native rows.
 * This only runs when the experimental rail is active and QS is expanded.
 * It intentionally does not implement the eventual separate QS page.
 */
final class ShadeQsPageBridge {
    private static final String TAG = "BB7-Shade";
    private static final Map<Object, Boolean> PENDING =
            Collections.synchronizedMap(new WeakHashMap<>());
    private static final Map<Object, Boolean> ERROR_LOGGED =
            Collections.synchronizedMap(new WeakHashMap<>());
    private static boolean enabled = true;

    static void setEnabled(boolean value) {
        enabled = value;
    }

    static void onShadeExpansion(Object panel, boolean keyguardShowing) {
        if (!enabled || keyguardShowing) return;

        Object qs;
        try {
            qs = XposedHelpers.getObjectField(panel, "mQsController");
        } catch (Throwable e) {
            reportOnce(panel, "Cannot access native QS controller", e);
            return;
        }
        if (qs == null) return;

        boolean fullyExpanded;
        try {
            fullyExpanded = XposedHelpers.getBooleanField(qs, "mFullyExpanded");
        } catch (Throwable e) {
            reportOnce(panel, "Cannot read native QS expansion state", e);
            return;
        }
        if (!fullyExpanded) return;
        if (!ShadeRailHost.isRailActive()) return;

        View panelView;
        try {
            panelView = (View) XposedHelpers.getObjectField(panel, "mView");
        } catch (Throwable e) {
            reportOnce(panel, "Cannot access native shade panel view", e);
            return;
        }
        if (panelView == null) return;
        synchronized (PENDING) {
            if (PENDING.containsKey(panel)) return;
            PENDING.put(panel, Boolean.TRUE);
        }
        boolean posted = panelView.post(() -> {
            synchronized (PENDING) {
                PENDING.remove(panel);
            }
            if (!enabled || !ShadeRailHost.isRailActive()) return;
            try {
                Object currentQs = XposedHelpers.getObjectField(panel, "mQsController");
                if (currentQs == null
                        || !XposedHelpers.getBooleanField(currentQs, "mFullyExpanded")) {
                    return;
                }
                XposedHelpers.callMethod(currentQs, "closeQs");
                Log.i(TAG, "Native Quick Settings collapsed for experimental notifications page");
            } catch (Throwable e) {
                reportOnce(panel, "Native Quick Settings closeQs() failed", e);
            }
        });
        if (!posted) {
            synchronized (PENDING) {
                PENDING.remove(panel);
            }
        }
    }

    private static void reportOnce(Object controller, String message, Throwable error) {
        synchronized (ERROR_LOGGED) {
            if (ERROR_LOGGED.containsKey(controller)) return;
            ERROR_LOGGED.put(controller, Boolean.TRUE);
        }
        // Deliberately log only the exception type, not notification content.
        Log.w(TAG, message + " (" + error.getClass().getSimpleName() + ")");
    }

    private ShadeQsPageBridge() {}
}
