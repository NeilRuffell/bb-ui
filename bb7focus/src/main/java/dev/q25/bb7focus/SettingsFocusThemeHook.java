package dev.q25.bb7focus;

import dev.q25.trackpad.theme.BB7Theme;

import android.app.Activity;
import android.content.res.ColorStateList;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.ViewTreeObserver;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.TextView;

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.WeakHashMap;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Visual-only BlackBerry OS 7-style focus highlight for existing Settings
 * preference rows. Does NOT request focus, handle keys or adjust navigation.
 *
 * Vector/LSPosed scope: com.android.settings only.
 */
public final class SettingsFocusThemeHook implements IXposedHookLoadPackage {
    private static final String TARGET = "com.android.settings";
    private static final String TAG = "BB7-Focus";
    private static final int BLUE_TOP = BB7Theme.BLUE_TOP;
    private static final int BLUE_MIDDLE = BB7Theme.BLUE_MIDDLE;
    private static final int BLUE_BOTTOM = BB7Theme.BLUE_BOTTOM;
    private static final int TITLE_COLOR = BB7Theme.TITLE;
    private static final int SUMMARY_COLOR = BB7Theme.SUMMARY;

    // Entries are explicitly detached at onDestroy; the weak keys are a
    // fallback for abnormal activity lifecycle termination.
    private static final Map<Activity, FocusObserver> OBSERVERS = new WeakHashMap<>();

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lp) {
        if (!TARGET.equals(lp.packageName)) return;
        try {
            SettingsDialogSurfaceHook.install();
            SettingsPopupThemeHook.install(lp.classLoader);
            XposedBridge.hookAllMethods(Activity.class, "onResume", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    Activity activity = (Activity) param.thisObject;
                    if (!TARGET.equals(activity.getPackageName())) return;
                    try {
                        attach(activity);
                    } catch (Throwable error) {
                        Log.e(TAG, "Cannot attach focus observer", error);
                    }
                }
            });
            XposedBridge.hookAllMethods(Activity.class, "onPause", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    Activity activity = (Activity) param.thisObject;
                    if (!TARGET.equals(activity.getPackageName())) return;
                    FocusObserver observer = OBSERVERS.get(activity);
                    if (observer != null) observer.clearHighlight();
                }
            });
            XposedBridge.hookAllMethods(Activity.class, "onDestroy", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    Activity activity = (Activity) param.thisObject;
                    if (!TARGET.equals(activity.getPackageName())) return;
                    FocusObserver observer = OBSERVERS.remove(activity);
                    if (observer != null) observer.detach();
                }
            });
            Log.i(TAG, "Settings focus appearance hooks registered");
        } catch (Throwable error) {
            XposedBridge.log(TAG + ": registration failed: " + error);
        }
    }

    private static void attach(Activity activity) {
        if (OBSERVERS.containsKey(activity)) {
            OBSERVERS.get(activity).update(activity.getWindow().getDecorView().findFocus());
            return;
        }
        if (activity.getWindow() == null) return;
        View root = activity.getWindow().getDecorView();
        if (root == null) return;
        FocusObserver observer = new FocusObserver(root);
        if (observer.attach()) {
            OBSERVERS.put(activity, observer);
            observer.update(root.findFocus());
        }
    }

    private static boolean isClassOrSubclass(Class<?> cls, String qualifiedName) {
        for (Class<?> current = cls; current != null; current = current.getSuperclass()) {
            if (qualifiedName.equals(current.getName())) return true;
        }
        return false;
    }

    private static boolean isPreferenceAdapter(Object adapter) {
        if (adapter == null) return false;
        Class<?> type = adapter.getClass();
        // LineageOS 24 SettingsPreferenceFragment creates
        // com.android.settings.widget.HighlightablePreferenceGroupAdapter,
        // which extends SettingsPreferenceGroupAdapter. Match the actual
        // shared Settings adapter hierarchy before considering fallback types.
        return isClassOrSubclass(type,
                    "com.android.settingslib.widget.SettingsPreferenceGroupAdapter")
                || isClassOrSubclass(type,
                    "com.android.settings.widget.HighlightablePreferenceGroupAdapter")
                || isClassOrSubclass(type, "androidx.preference.PreferenceGroupAdapter")
                || isClassOrSubclass(type,
                    "android.support.v7.preference.PreferenceGroupAdapter");
    }

    /**
     * Locate the existing RecyclerView row, only for PreferenceGroupAdapter.
     * Unknown/custom lists are not modified. Focusable nested controls remain
     * untouched.
     */
    private static View preferenceRowFor(View focused) {
        // A SwitchPreferenceCompat can focus its nested switch control.
        // Highlight its enclosing native preference row, while leaving
        // actual switch focus, checked state and keyboard behavior alone.
        // Editable inputs remain excluded to preserve the caret/readability.
        if (focused == null || focused instanceof EditText) return null;
        for (View node = focused; node != null; ) {
            ViewParent parent = node.getParent();
            if (!(parent instanceof View)) return null;
            View parentView = (View) parent;
            if (isClassOrSubclass(parentView.getClass(),
                    "androidx.recyclerview.widget.RecyclerView")) {
                try {
                    Object adapter = XposedHelpers.callMethod(parentView, "getAdapter");
                    return isPreferenceAdapter(adapter) && node.isShown() && node.isEnabled()
                            ? node : null;
                } catch (Throwable ignored) {
                    return null;
                }
            }
            node = parentView;
        }
        return null;
    }

    private static GradientDrawable blueBackground() {
        GradientDrawable gradient = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                new int[] { BLUE_TOP, BLUE_MIDDLE, BLUE_BOTTOM });
        gradient.setCornerRadius(0);
        return gradient;
    }

    private static boolean isSummaryText(TextView textView) {
        int id = textView.getId();
        if (id == View.NO_ID) return false;
        try {
            return "summary".equals(textView.getResources().getResourceEntryName(id));
        } catch (android.content.res.Resources.NotFoundException ignored) {
            return false;
        }
    }

    private static void recolorText(View view, Map<TextView, ColorStateList> originals) {
        if (view instanceof TextView && !(view instanceof EditText)
                && !(view instanceof CompoundButton)) {
            TextView textView = (TextView) view;
            originals.put(textView, textView.getTextColors());
            textView.setTextColor(isSummaryText(textView) ? SUMMARY_COLOR : TITLE_COLOR);
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                recolorText(group.getChildAt(i), originals);
            }
        }
    }

    private static final class FocusObserver
            implements ViewTreeObserver.OnGlobalFocusChangeListener {
        private final View root;
        private View selected;
        private Drawable originalBackground;
        private int paddingStart, paddingTop, paddingEnd, paddingBottom;
        private final Map<TextView, ColorStateList> originalText = new IdentityHashMap<>();

        FocusObserver(View root) {
            this.root = root;
        }

        boolean attach() {
            ViewTreeObserver observer = root.getViewTreeObserver();
            if (!observer.isAlive()) return false;
            observer.addOnGlobalFocusChangeListener(this);
            return true;
        }

        void detach() {
            clearHighlight();
            ViewTreeObserver observer = root.getViewTreeObserver();
            if (observer.isAlive()) observer.removeOnGlobalFocusChangeListener(this);
        }

        @Override
        public void onGlobalFocusChanged(View oldFocus, View newFocus) {
            update(newFocus);
        }

        void update(View focused) {
            View newRow = preferenceRowFor(focused);
            if (selected == newRow) return;
            clearHighlight();
            if (newRow == null) return;
            try {
                selected = newRow;
                originalBackground = newRow.getBackground();
                paddingStart = newRow.getPaddingStart();
                paddingTop = newRow.getPaddingTop();
                paddingEnd = newRow.getPaddingEnd();
                paddingBottom = newRow.getPaddingBottom();
                newRow.setBackground(blueBackground());
                newRow.setPaddingRelative(paddingStart, paddingTop, paddingEnd, paddingBottom);
                recolorText(newRow, originalText);
            } catch (Throwable error) {
                Log.e(TAG, "Cannot style focused preference row", error);
                clearHighlight();
            }
        }

        void clearHighlight() {
            if (selected == null) return;
            View row = selected;
            try {
                row.setBackground(originalBackground);
                row.setPaddingRelative(paddingStart, paddingTop, paddingEnd, paddingBottom);
                for (Map.Entry<TextView, ColorStateList> text : originalText.entrySet()) {
                    text.getKey().setTextColor(text.getValue());
                }
            } catch (Throwable error) {
                Log.w(TAG, "Cannot fully restore preference row", error);
            } finally {
                selected = null;
                originalBackground = null;
                originalText.clear();
            }
        }
    }
}
