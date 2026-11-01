package dev.q25.trackpad;

import android.content.Context;
import android.provider.Settings;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;

import java.util.WeakHashMap;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Experimental visual grid for Quickstep's RecentsView.
 *
 * Off by default: adb shell settings put global bb_ui_recents_grid 1
 * Scope to the system package providing Quickstep, NOT the user's home launcher.
 * This keeps Quickstep's real TaskView thumbnails and task click listeners.
 *
 * Native animation/gesture paths may still assume a horizontally paged RecentsView.
 */
public final class RecentsGridHook implements IXposedHookLoadPackage {
    private static final String RECENTS =
            "com.android.quickstep.views.RecentsView";
    private static final String TASK = "com.android.quickstep.views.TaskView";
    private static final String TOGGLE = "bb_ui_recents_grid";
    private static final int COLUMNS = 2;
    private static final WeakHashMap<View, GridState> STATES = new WeakHashMap<>();

    private static class GridState {
        int offset;
        int contentHeight;
        int selected;
        boolean applying;
    }

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lp) {
        // Only hook packages actually bundling the Quickstep RecentsView class.
        // This can be the system Launcher3/Trebuchet package or a standalone recents provider.
        if (lp.packageName.equals("android") || lp.packageName.equals("com.android.systemui")
                || lp.packageName.equals("dev.q25.trackpad")) return;
        Class<?> recents = XposedHelpers.findClassIfExists(RECENTS, lp.classLoader);
        Class<?> task = XposedHelpers.findClassIfExists(TASK, lp.classLoader);
        if (recents == null || task == null) return;
        try {
            XposedBridge.hookAllMethods(recents, "onLayout", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    ViewGroup view = (ViewGroup) p.thisObject;
                    if (enabled(view)) layoutGrid(view, task);
                }
            });
            XposedBridge.hookAllMethods(recents, "onGenericMotionEvent", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    ViewGroup view = (ViewGroup) p.thisObject;
                    if (!enabled(view)) return;
                    MotionEvent e = (MotionEvent) p.args[0];
                    if (e.getActionMasked() != MotionEvent.ACTION_SCROLL) return;
                    float axis = e.getAxisValue(MotionEvent.AXIS_VSCROLL);
                    if (axis == 0) return;
                    GridState s = state(view);
                    int step = dp(view, 64);
                    s.offset += Math.round(-axis * step);
                    layoutGrid(view, task);
                    p.setResult(true);
                }
            });
            XposedBridge.hookAllMethods(recents, "dispatchKeyEvent", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    ViewGroup view = (ViewGroup) p.thisObject;
                    if (!enabled(view)) return;
                    KeyEvent e = (KeyEvent) p.args[0];
                    if (e.getAction() != KeyEvent.ACTION_DOWN) return;
                    int delta;
                    switch (e.getKeyCode()) {
                        case KeyEvent.KEYCODE_DPAD_DOWN: delta = COLUMNS; break;
                        case KeyEvent.KEYCODE_DPAD_UP: delta = -COLUMNS; break;
                        case KeyEvent.KEYCODE_DPAD_RIGHT: delta = 1; break;
                        case KeyEvent.KEYCODE_DPAD_LEFT: delta = -1; break;
                        default: return;
                    }
                    GridState s = state(view);
                    int count = taskCount(view, task);
                    if (count == 0) return;
                    s.selected = Math.max(0, Math.min(count - 1, s.selected + delta));
                    View target = taskAt(view, task, s.selected);
                    if (target != null) {
                        target.setFocusable(true);
                        target.requestFocus();
                        scrollToVisible(view, target, s);
                        layoutGrid(view, task);
                        p.setResult(true);
                    }
                }
            });
            XposedBridge.log("BB UI: experimental Recents grid hook installed for " + lp.packageName);
        } catch (Throwable t) {
            XposedBridge.log("BB UI: Recents grid hook unavailable: " + t);
        }
    }

    private static synchronized GridState state(View v) {
        GridState s = STATES.get(v);
        if (s == null) {
            s = new GridState();
            STATES.put(v, s);
        }
        return s;
    }

    private static boolean enabled(View v) {
        try {
            return Settings.Global.getInt(v.getContext().getContentResolver(), TOGGLE, 0) == 1;
        } catch (Throwable ignored) { return false; }
    }

    private static int dp(View v, int n) {
        return Math.round(n * v.getResources().getDisplayMetrics().density);
    }

    private static int taskCount(ViewGroup root, Class<?> cls) {
        int n = 0;
        for (int i = 0; i < root.getChildCount(); i++) {
            if (cls.isInstance(root.getChildAt(i))) n++;
        }
        return n;
    }

    private static View taskAt(ViewGroup root, Class<?> cls, int index) {
        for (int i = 0; i < root.getChildCount(); i++) {
            View child = root.getChildAt(i);
            if (!cls.isInstance(child)) continue;
            if (index-- == 0) return child;
        }
        return null;
    }

    private static void scrollToVisible(ViewGroup parent, View target, GridState s) {
        int row = s.selected / COLUMNS;
        int slotH = parent.getTag(0x7f0c0001) instanceof Integer
                ? (Integer) parent.getTag(0x7f0c0001) : dp(parent, 200);
        int top = dp(parent, 12) + row * slotH;
        if (top < s.offset) s.offset = top;
        else if (top + slotH > s.offset + parent.getHeight())
            s.offset = top + slotH - parent.getHeight();
    }

    private static void layoutGrid(ViewGroup root, Class<?> taskType) {
        GridState s = state(root);
        if (s.applying || root.getWidth() <= 0 || root.getHeight() <= 0) return;
        s.applying = true;
        try {
            final int gap = dp(root, 10);
            final int inset = dp(root, 12);
            final int cellWidth = Math.max(1, (root.getWidth() - inset * 2 - gap) / COLUMNS);
            View first = taskAt(root, taskType, 0);
            if (first == null) return;
            final int rawWidth = Math.max(1, first.getWidth());
            final int rawHeight = Math.max(1, first.getHeight());
            final float scale = Math.min(1f, (float) cellWidth / rawWidth);
            final int cellHeight = Math.round(rawHeight * scale);
            final int rowStep = cellHeight + gap;
            final int count = taskCount(root, taskType);
            root.setTag(0x7f0c0001, rowStep);
            s.contentHeight = inset * 2 + (count + 1) / 2 * rowStep;
            s.offset = Math.max(0, Math.min(s.offset, Math.max(0, s.contentHeight - root.getHeight())));
            root.setClipChildren(false);
            for (int i = 0; i < count; i++) {
                View tile = taskAt(root, taskType, i);
                if (tile == null) continue;
                float actualScale = Math.min(1f, (float) cellWidth / Math.max(1, tile.getWidth()));
                float x = inset + (i % COLUMNS) * (cellWidth + gap);
                float y = inset + (i / COLUMNS) * rowStep - s.offset;
                // View scaling defaults to center pivot; translation aligns the visual top-left.
                tile.setScaleX(actualScale);
                tile.setScaleY(actualScale);
                tile.setTranslationX(x - tile.getLeft() - (1f - actualScale) * tile.getWidth() / 2f);
                tile.setTranslationY(y - tile.getTop() - (1f - actualScale) * tile.getHeight() / 2f);
                tile.setFocusable(true);
            }
        } catch (Throwable t) {
            XposedBridge.log("BB UI: Recents grid layout failure: " + t);
        } finally {
            s.applying = false;
        }
    }
}
