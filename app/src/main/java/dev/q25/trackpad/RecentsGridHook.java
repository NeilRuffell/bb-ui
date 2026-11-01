package dev.q25.trackpad;

import android.provider.Settings;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;

import java.util.WeakHashMap;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Experimental Recents grid. Intentionally restricted to Quickstep's RecentsView.
 *
 * The original version applied child translations while allowing horizontal PagedView
 * scrolling. Those coordinate spaces disagreed, producing off-screen task cards.
 *
 * This version places actual TaskView bounds in a grid, resets the horizontal scroll
 * and consumes overview background drag/wheel input for vertical scrolling.
 * Other Quickstep animations can still change TaskView transforms at runtime.
 */
public final class RecentsGridHook implements IXposedHookLoadPackage {
    private static final String RECENTS = "com.android.quickstep.views.RecentsView";
    private static final String TASK = "com.android.quickstep.views.TaskView";
    private static final String TOGGLE = "bb_ui_recents_grid";
    private static final int COLUMNS = 2;
    private static final WeakHashMap<View, State> STATES = new WeakHashMap<>();

    private static class State {
        int offset;
        int contentHeight;
        int rowHeight;
        int selected;
        boolean layingOut;
        boolean dragging;
        float downX;
        float downY;
        float lastY;
    }

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lp) {
        if (lp.packageName.equals("android")
                || lp.packageName.equals("com.android.systemui")
                || lp.packageName.equals("dev.q25.trackpad")) return;

        final Class<?> recents = XposedHelpers.findClassIfExists(RECENTS, lp.classLoader);
        final Class<?> task = XposedHelpers.findClassIfExists(TASK, lp.classLoader);
        final Class<?> paged = XposedHelpers.findClassIfExists(
                "com.android.launcher3.PagedView", lp.classLoader);
        if (recents == null || task == null || paged == null) return;

        try {
            XposedBridge.hookAllMethods(recents, "onLayout", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    ViewGroup root = (ViewGroup) p.thisObject;
                    if (enabled(root)) placeGrid(root, task);
                }
            });


            // Block horizontal movement of the Quickstep pager in grid mode.
            XposedBridge.hookAllMethods(View.class, "scrollTo", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    View view = (View) p.thisObject;
                    if (recents.isInstance(view) && enabled(view)) {
                        p.args[0] = 0;
                        p.args[1] = 0;
                    }
                }
            });
            XposedBridge.hookAllMethods(View.class, "scrollBy", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    View view = (View) p.thisObject;
                    if (recents.isInstance(view) && enabled(view)) p.setResult(null);
                }
            });

            // Quickstep's carousel animation must not translate grid tasks horizontally.
            XposedBridge.hookAllMethods(View.class, "setTranslationX", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    View view = (View) p.thisObject;
                    if (task.isInstance(view) && isGridTask(view, recents)) p.args[0] = 0f;
                }
            });
            XposedBridge.hookAllMethods(View.class, "setTranslationY", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    View view = (View) p.thisObject;
                    if (task.isInstance(view) && isGridTask(view, recents)) p.args[0] = 0f;
                }
            });

            // The original PagedView can change scrollX without running onLayout again.
            // Restore the grid's coordinate space before it draws, but only if needed.
            XposedBridge.hookAllMethods(ViewGroup.class, "dispatchDraw", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    ViewGroup root = (ViewGroup) p.thisObject;
                    if (recents.isInstance(root) && enabled(root)) placeGrid(root, task);
                }
            });

            XposedBridge.hookAllMethods(recents, "onTouchEvent", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    ViewGroup root = (ViewGroup) p.thisObject;
                    if (enabled(root)) p.setResult(true); // disable PagedView's horizontal swipe
                }
            });

            XposedBridge.hookAllMethods(paged, "onGenericMotionEvent", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    ViewGroup root = (ViewGroup) p.thisObject;
                    if (!recents.isInstance(root) || !enabled(root)) return;
                    MotionEvent e = (MotionEvent) p.args[0];
                    if (e.getActionMasked() != MotionEvent.ACTION_SCROLL) return;
                    float wheel = e.getAxisValue(MotionEvent.AXIS_VSCROLL);
                    if (wheel == 0) return;
                    State state = getState(root);
                    state.offset -= Math.round(wheel * dp(root, 64));
                    placeGrid(root, task);
                    p.setResult(true);
                }
            });

            // ViewGroup's inherited dispatch methods run before hit-testing children.
            XposedBridge.hookAllMethods(ViewGroup.class, "dispatchTouchEvent", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    ViewGroup root = (ViewGroup) p.thisObject;
                    if (!recents.isInstance(root) || !enabled(root)) return;
                    MotionEvent e = (MotionEvent) p.args[0];
                    State state = getState(root);
                    switch (e.getActionMasked()) {
                        case MotionEvent.ACTION_DOWN:
                            state.downX = e.getX();
                            state.downY = e.getY();
                            state.lastY = state.downY;
                            state.dragging = false;
                            break;
                        case MotionEvent.ACTION_MOVE:
                            float dy = e.getY() - state.lastY;
                            if (!state.dragging &&
                                    Math.abs(e.getY() - state.downY) > dp(root, 16) &&
                                    Math.abs(e.getY() - state.downY) >
                                            Math.abs(e.getX() - state.downX)) {
                                state.dragging = true;
                                // Cancel touch handling for the child that received ACTION_DOWN
                                // by consuming subsequent movements.
                            }
                            state.lastY = e.getY();
                            if (state.dragging) {
                                state.offset -= Math.round(dy);
                                placeGrid(root, task);
                                p.setResult(true);
                            }
                            break;
                        case MotionEvent.ACTION_UP:
                        case MotionEvent.ACTION_CANCEL:
                            if (state.dragging) {
                                state.dragging = false;
                                p.setResult(true);
                            }
                            break;
                        default:
                            break;
                    }
                }
            });

            XposedBridge.hookAllMethods(ViewGroup.class, "dispatchKeyEvent", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    ViewGroup root = (ViewGroup) p.thisObject;
                    if (!recents.isInstance(root) || !enabled(root)) return;
                    KeyEvent e = (KeyEvent) p.args[0];
                    if (e.getAction() != KeyEvent.ACTION_DOWN) return;
                    int delta;
                    switch (e.getKeyCode()) {
                        case KeyEvent.KEYCODE_DPAD_UP: delta = -2; break;
                        case KeyEvent.KEYCODE_DPAD_DOWN: delta = 2; break;
                        case KeyEvent.KEYCODE_DPAD_LEFT: delta = -1; break;
                        case KeyEvent.KEYCODE_DPAD_RIGHT: delta = 1; break;
                        default: return;
                    }
                    State state = getState(root);
                    int n = countTasks(root, task);
                    if (n == 0) return;
                    state.selected = Math.max(0, Math.min(n - 1, state.selected + delta));
                    View selected = getTask(root, task, state.selected);
                    if (selected == null) return;
                    int rowTop = dp(root, 12) + (state.selected / 2) * state.rowHeight;
                    if (rowTop < state.offset) state.offset = rowTop;
                    if (rowTop + state.rowHeight > state.offset + root.getHeight())
                        state.offset = rowTop + state.rowHeight - root.getHeight();
                    placeGrid(root, task);
                    selected.setFocusable(true);
                    selected.requestFocus();
                    p.setResult(true);
                }
            });
            XposedBridge.log("BB UI: RecentsGridHook v4 hooked " + lp.packageName);
        } catch (Throwable error) {
            XposedBridge.log("BB UI: RecentsGridHook v4 unavailable: " + error);
        }
    }

    private static boolean isGridTask(View tile, Class<?> recents) {
        ViewParent parent = tile.getParent();
        return parent instanceof View && recents.isInstance(parent) && enabled((View) parent);
    }

    private static boolean enabled(View view) {
        try {
            return Settings.Global.getInt(view.getContext().getContentResolver(),
                    TOGGLE, 0) == 1;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static synchronized State getState(View view) {
        State state = STATES.get(view);
        if (state == null) {
            state = new State();
            STATES.put(view, state);
        }
        return state;
    }

    private static int dp(View view, int pixels) {
        return Math.round(view.getResources().getDisplayMetrics().density * pixels);
    }

    private static int countTasks(ViewGroup root, Class<?> task) {
        int count = 0;
        for (int i = 0; i < root.getChildCount(); ++i)
            if (task.isInstance(root.getChildAt(i))) count++;
        return count;
    }

    private static View getTask(ViewGroup root, Class<?> task, int index) {
        for (int i = 0; i < root.getChildCount(); ++i) {
            View v = root.getChildAt(i);
            if (task.isInstance(v) && index-- == 0) return v;
        }
        return null;
    }

    private static void placeGrid(ViewGroup root, Class<?> task) {
        State state = getState(root);
        if (state.layingOut || root.getWidth() <= 0 || root.getHeight() <= 0) return;
        state.layingOut = true;
        try {
            int margin = dp(root, 2);
            int gap = dp(root, 6);
            int width = Math.max(1, (root.getWidth() - 2 * margin - gap) / COLUMNS);
            int count = countTasks(root, task);
            if (count == 0) return;
            View first = getTask(root, task, 0);
            if (first == null || first.getWidth() <= 0 || first.getHeight() <= 0) return;
            float nominalScale = Math.min(1f, (float) width / first.getWidth());
            int cellHeight = Math.max(1, Math.round(first.getHeight() * nominalScale));
            state.rowHeight = cellHeight + gap;
            state.contentHeight = 2 * margin + (count + 1) / 2 * state.rowHeight - gap;
            state.offset = Math.max(0, Math.min(state.offset,
                    Math.max(0, state.contentHeight - root.getHeight())));

            // Reset PagedView's horizontal origin rather than compensate only one frame.
            if (root.getScrollX() != 0) root.scrollTo(0, 0);
            root.setClipChildren(true);
            root.setClipToPadding(false);
            // Remove the horizontally positioned Clear All affordance from this mode.
            for (int childIndex = 0; childIndex < root.getChildCount(); childIndex++) {
                View child = root.getChildAt(childIndex);
                if (child.getClass().getName().endsWith(".ClearAllButton")) {
                    child.setVisibility(View.INVISIBLE);
                }
            }

            for (int i = 0; i < count; ++i) {
                View tile = getTask(root, task, i);
                if (tile == null || tile.getWidth() <= 0 || tile.getHeight() <= 0) continue;

                // Fit the complete source card inside its column; do not crop the edges.
                float scale = Math.min(1f, (float) width / tile.getWidth());
                int x = margin + (i % COLUMNS) * (width + gap)
                        + Math.max(0, Math.round((width - tile.getWidth() * scale) / 2f));
                int y = margin + (i / COLUMNS) * state.rowHeight - state.offset;

                // Actual child coordinates, not translations, are used by hit-testing.
                int originalW = tile.getWidth();
                int originalH = tile.getHeight();
                if (tile.getTranslationX() != 0f) tile.setTranslationX(0f);
                if (tile.getTranslationY() != 0f) tile.setTranslationY(0f);
                tile.setPivotX(0f);
                tile.setPivotY(0f);
                // Task thumbnails are nested inside Quickstep containers which may
                // retain edge clipping from the original full-size carousel.
                if (tile instanceof ViewGroup) {
                    ((ViewGroup) tile).setClipChildren(false);
                    ((ViewGroup) tile).setClipToPadding(false);
                }
                if (tile.getLeft() != x || tile.getTop() != y) {
                    tile.layout(x, y, x + originalW, y + originalH);
                }
                if (tile.getScaleX() != scale) tile.setScaleX(scale);
                if (tile.getScaleY() != scale) tile.setScaleY(scale);
                tile.setFocusable(true);
            }
        } catch (Throwable error) {
            XposedBridge.log("BB UI: RecentsGridHook v4 layout error: " + error);
        } finally {
            state.layingOut = false;
        }
    }
}
