package dev.q25.trackpad.theme;

import android.app.Activity;
import android.content.res.ColorStateList;
import android.graphics.drawable.Drawable;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.ViewTreeObserver;
import android.widget.AdapterView;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.TextView;

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * Appearance-only OS list-row focus for native RecyclerView/ListView screens.
 *
 * This handles actual Android keyboard/trackpad focus, never requests focus,
 * translates a key, or inserts a new focusable widget. It excludes Settings'
 * PreferenceGroupAdapter rows already handled by SettingsFocusThemeHook and
 * avoids SystemUI / Quickstep special focus implementations entirely.
 */
final class SystemBb7RowFocus {
    private static final String TAG = "BB7-SystemSkin";
    private static final Map<Activity, Observer> OBSERVERS = new WeakHashMap<>();
    private static final AtomicBoolean LOGGED = new AtomicBoolean();

    private SystemBb7RowFocus() {}

    static void install(String processPackage, ClassLoader loader) {
        if ("com.android.systemui".equals(processPackage)
                || "com.android.launcher3".equals(processPackage)) {
            Log.i(TAG, "Keeping existing SystemUI / Quickstep focus implementation");
            return;
        }
        try {
            XposedBridge.hookAllMethods(Activity.class, "onResume",
                    new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        Activity activity = (Activity) param.thisObject;
                        if (activity.getWindow() == null) return;
                        View root = activity.getWindow().getDecorView();
                        if (root == null) return;
                        Observer observer;
                        synchronized (OBSERVERS) {
                            observer = OBSERVERS.get(activity);
                            if (observer == null) {
                                observer = new Observer(root);
                                if (!observer.attach()) return;
                                OBSERVERS.put(activity, observer);
                            }
                        }
                        observer.update(root.findFocus());
                    } catch (Throwable error) {
                        Log.w(TAG, "Shared focus observer unavailable", error);
                    }
                }
            });
            XposedBridge.hookAllMethods(Activity.class, "onPause",
                    new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    synchronized (OBSERVERS) {
                        Observer observer = OBSERVERS.get(param.thisObject);
                        if (observer != null) observer.clear();
                    }
                }
            });
            XposedBridge.hookAllMethods(Activity.class, "onDestroy",
                    new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    Observer observer;
                    synchronized (OBSERVERS) {
                        observer = OBSERVERS.remove(param.thisObject);
                    }
                    if (observer != null) observer.detach();
                }
            });
            Log.i(TAG, "Shared native system list focus observers registered");
        } catch (Throwable error) {
            Log.e(TAG, "Shared native system list focus registration failed", error);
        }
    }

    private static boolean inherits(Class<?> cls, String name) {
        for (Class<?> type = cls; type != null; type = type.getSuperclass()) {
            if (name.equals(type.getName())) return true;
        }
        return false;
    }

    private static boolean isPreferenceAdapter(Object adapter) {
        if (adapter == null) return false;
        Class<?> type = adapter.getClass();
        return inherits(type, "androidx.preference.PreferenceGroupAdapter")
                || inherits(type, "com.android.settingslib.widget.SettingsPreferenceGroupAdapter");
    }

    private static View nativeRowFor(View focused) {
        if (focused == null || focused instanceof EditText) return null;
        for (View child = focused; child != null; ) {
            ViewParent parent = child.getParent();
            if (!(parent instanceof View)) return null;
            View outer = (View) parent;
            try {
                if (inherits(outer.getClass(),
                        "androidx.recyclerview.widget.RecyclerView")) {
                    Object adapter = XposedHelpers.callMethod(outer, "getAdapter");
                    if (adapter == null || isPreferenceAdapter(adapter)) return null;
                    Object position = XposedHelpers.callMethod(outer,
                            "getChildAdapterPosition", child);
                    if (position instanceof Integer && (Integer) position >= 0
                            && child.isShown() && child.isEnabled()) return child;
                    return null;
                }
                if (outer instanceof AdapterView) {
                    Object adapter = ((AdapterView<?>) outer).getAdapter();
                    if (adapter == null) return null;
                    Object position = XposedHelpers.callMethod(outer,
                            "getPositionForView", child);
                    if (position instanceof Integer && (Integer) position >= 0
                            && child.isShown() && child.isEnabled()) return child;
                    return null;
                }
            } catch (Throwable ignored) {
                return null;
            }
            child = outer;
        }
        return null;
    }

    private static void textColors(View view,
            Map<TextView, ColorStateList> original) {
        if (view instanceof EditText) return;
        if (view instanceof TextView && !(view instanceof CompoundButton)) {
            TextView label = (TextView) view;
            original.put(label, label.getTextColors());
            label.setTextColor(BB7Theme.TITLE);
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++)
                textColors(group.getChildAt(i), original);
        }
    }

    private static final class Observer
            implements ViewTreeObserver.OnGlobalFocusChangeListener {
        final View root;
        View highlighted;
        Drawable previous;
        int left, top, right, bottom;
        final Map<TextView, ColorStateList> text =
                new IdentityHashMap<>();

        Observer(View root) { this.root = root; }

        boolean attach() {
            ViewTreeObserver tree = root.getViewTreeObserver();
            if (!tree.isAlive()) return false;
            tree.addOnGlobalFocusChangeListener(this);
            return true;
        }

        void detach() {
            clear();
            ViewTreeObserver tree = root.getViewTreeObserver();
            if (tree.isAlive()) tree.removeOnGlobalFocusChangeListener(this);
        }

        @Override public void onGlobalFocusChanged(View oldFocus, View newFocus) {
            update(newFocus);
        }

        void update(View focused) {
            View row = nativeRowFor(focused);
            if (row == highlighted) return;
            clear();
            if (row == null) return;
            try {
                highlighted = row;
                previous = row.getBackground();
                left = row.getPaddingStart();
                top = row.getPaddingTop();
                right = row.getPaddingEnd();
                bottom = row.getPaddingBottom();
                int line = Math.max(1, Math.round(
                        row.getResources().getDisplayMetrics().density));
                row.setBackground(BB7Theme.selected(0f, line));
                row.setPaddingRelative(left, top, right, bottom);
                textColors(row, text);
                if (LOGGED.compareAndSet(false, true))
                    Log.i(TAG, "BB7 selection follows native OS list focus");
            } catch (Throwable error) {
                Log.w(TAG, "OS list focus appearance unchanged", error);
                clear();
            }
        }

        void clear() {
            if (highlighted == null) return;
            try {
                highlighted.setBackground(previous);
                highlighted.setPaddingRelative(left, top, right, bottom);
                for (Map.Entry<TextView, ColorStateList> original : text.entrySet())
                    original.getKey().setTextColor(original.getValue());
            } catch (Throwable error) {
                Log.w(TAG, "Could not fully restore native row", error);
            } finally {
                highlighted = null;
                previous = null;
                text.clear();
            }
        }
    }
}
