package dev.q25.trackpad;

import android.graphics.Rect;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;

import java.util.ArrayList;
import java.util.List;
import java.util.WeakHashMap;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Single-renderer BBOS6-inspired task icon grid inside Android Quickstep.
 *
 * The existing RecentsView remains the ONLY Overview host. The actual native
 * TaskViews (and their task objects and click handlers) remain the ONLY task
 * views. Its native TaskViews paint the existing Quickstep app Drawables
 * directly, not the chip/title/thumbnail child UI. No overlays, second
 * activity, screenshot cards or replicated task models are introduced.
 *
 * Initial Q25 compatibility build is opt-in until physically verified.
 * It is a Vector integration adapter, not the future ROM implementation.
 * Property: debug.bbui.recents.icons (default false).
 */
public final class QuickstepIconGridHook implements IXposedHookLoadPackage {
    private static final String TAG = "BBUI-RecentsIcons";
    private static final String PACKAGE = "com.android.launcher3";
    private static final String FLAG = "debug.bbui.recents.icons";
    private static final String RECENTS = "com.android.quickstep.views.RecentsView";
    private static final String TASK = "com.android.quickstep.views.TaskView";

    private static final WeakHashMap<View, Integer> MAX_SCROLL = new WeakHashMap<>();
    private static boolean firstLayoutLogged;

    @Override public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lp) {
        if (!PACKAGE.equals(lp.packageName) || !PACKAGE.equals(lp.processName)) return;
        Class<?> recents = XposedHelpers.findClassIfExists(RECENTS, lp.classLoader);
        Class<?> task = XposedHelpers.findClassIfExists(TASK, lp.classLoader);
        if (recents == null || task == null) {
            Log.e(TAG, "Native Quickstep RecentsView/TaskView unavailable; unchanged");
            return;
        }
        try {
            // Native Quickstep layout hooks: no second task renderer.
            XposedBridge.hookAllMethods(recents, "getTaskSize", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    if (!requested() || !(p.thisObject instanceof View)
                            || p.args.length < 1 || !(p.args[0] instanceof Rect)) return;
                    View root = (View) p.thisObject;
                    RecentsIconGridLayout grid = geometry(root, 1);
                    Rect out = (Rect) p.args[0];
                    if (grid == null || out.isEmpty()) return;
                    // Quickstep uses this size for its existing TaskView objects.
                    out.set(grid.gap, grid.top,
                            grid.gap + grid.cellWidth, grid.top + grid.cellHeight);
                }
            });

            // TaskView owns native input, task IDs and launch behavior. Replace
            // its *actual draw pass*, rather than hiding a giant tile beneath
            // a pill-shaped header. Render NOTHING except real task icons
            // and a compact focus indicator.
            XposedBridge.hookAllMethods(task, "draw", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    if (!requested() || p.args.length != 1
                            || !(p.args[0] instanceof Canvas)) return;
                    View nativeTask = (View) p.thisObject;
                    drawIconsOnly(nativeTask, (Canvas) p.args[0]);
                    // Skip stock TaskView.draw completely: no thumbnail,
                    // no preview card, no chip background, no title strip.
                    p.setResult(null);
                }
            });

            // Quickstep loads application icons asynchronously. The existing
            // chip owns the Drawable; when its icon changes, invalidate the
            // *same* native TaskView so its icon-only paint is refreshed.
            Class<?> iconChip = XposedHelpers.findClassIfExists(
                    "com.android.quickstep.views.IconAppChipView", lp.classLoader);
            if (iconChip != null) {
                XposedBridge.hookAllMethods(iconChip, "setDrawable",
                        new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam p) {
                        if (!requested() || !(p.thisObject instanceof View)) return;
                        View chip = (View) p.thisObject;
                        if (chip.getParent() instanceof View)
                            ((View) chip.getParent()).invalidate();
                    }
                });
            }

            XposedBridge.hookAllMethods(recents, "getPageScrolls", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    if (!requested() || p.args.length < 2
                            || !(p.args[0] instanceof int[])
                            || !(p.args[1] instanceof Boolean)) return;
                    ViewGroup root = (ViewGroup) p.thisObject;
                    int[] scrolls = (int[]) p.args[0];
                    if (scrolls.length != root.getChildCount()) return;
                    List<View> tasks = new ArrayList<>();
                    View clearAll = null;
                    for (int i = 0; i < root.getChildCount(); i++) {
                        View child = root.getChildAt(i);
                        if (task.isInstance(child)) {
                            tasks.add(child);
                        } else if (child.getClass().getName().endsWith(".ClearAllButton")) {
                            clearAll = child;
                        } else {
                            // Desktop/additional native UI requires a deliberate
                            // separate integration. Preserve stock for those.
                            return;
                        }
                    }
                    if (tasks.isEmpty() || !allHaveNativeIcons(tasks)) return;
                    RecentsIconGridLayout grid = geometry(root, tasks.size());
                    if (grid == null) return;

                    boolean place = (Boolean) p.args[1];
                    // Clear All is intentionally NOT another icon/page.
                    // No trailing blank 'clear all' page in the icon grid.
                    int endScroll = grid.lastTaskScroll();
                    MAX_SCROLL.put(root, endScroll);
                    boolean changed = false;
                    int taskNumber = 0;
                    for (int i = 0; i < root.getChildCount(); i++) {
                        View child = root.getChildAt(i);
                        int scroll;
                        if (task.isInstance(child)) {
                            int x = grid.x(taskNumber);
                            int y = grid.y(taskNumber);
                            scroll = grid.scrollForTask(taskNumber);
                            if (place) {
                                // Native task hit targets are exactly ONE
                                // compact icon cell, not a fullscreen card.
                                // TaskView.draw is replaced separately.
                                if (child.getLeft() != x || child.getTop() != y
                                        || child.getWidth() != grid.cellWidth
                                        || child.getHeight() != grid.cellHeight) {
                                    child.layout(x, y, x + grid.cellWidth,
                                            y + grid.cellHeight);
                                }
                            }
                            taskNumber++;
                        } else {
                            scroll = endScroll;
                            if (place) {
                                // Existing native ClearAllButton remains
                                // allocated for Quickstep internals, but
                                // cannot paint into the icon-only screen.
                                child.setVisibility(View.GONE);
                            }
                        }
                        if (scrolls[i] != scroll) {
                            scrolls[i] = scroll;
                            changed = true;
                        }
                    }
                    p.setResult(changed);
                    if (place && !firstLayoutLogged) {
                        firstLayoutLogged = true;
                        Log.i(TAG, "Single native icon-grid renderer: tasks=" + tasks.size()
                                + " pages=" + grid.pageCount + " maxScroll=" + endScroll);
                    }
                }
            });

            XposedBridge.hookAllMethods(recents, "computeMinScroll", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    if (requested() && MAX_SCROLL.containsKey((View) p.thisObject))
                        p.setResult(0);
                }
            });
            XposedBridge.hookAllMethods(recents, "computeMaxScroll", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    if (!requested()) return;
                    Integer limit = MAX_SCROLL.get((View) p.thisObject);
                    if (limit != null) p.setResult(limit);
                }
            });

            XposedBridge.hookAllMethods(recents, "dispatchKeyEvent", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    if (!requested() || p.args.length < 1
                            || !(p.args[0] instanceof KeyEvent)) return;
                    ViewGroup root = (ViewGroup) p.thisObject;
                    KeyEvent key = (KeyEvent) p.args[0];
                    int direction = direction(key.getKeyCode());
                    if (direction < 0) return;
                    // Never steal keys when native Overview isn't interactive.
                    if (!overviewEnabled(root)) return;
                    List<View> tasks = taskChildren(root, task);
                    if (tasks.isEmpty()) return;
                    if (key.getAction() == KeyEvent.ACTION_UP) {
                        p.setResult(true);
                        return;
                    }
                    if (key.getAction() != KeyEvent.ACTION_DOWN) return;
                    int current = -1;
                    View focus = root.findFocus();
                    for (int i = 0; i < tasks.size(); i++) {
                        if (focus == tasks.get(i) || descendantOf(focus, tasks.get(i))) {
                            current = i;
                            break;
                        }
                    }
                    if (current < 0) current = 0;
                    RecentsIconGridLayout grid = geometry(root, tasks.size());
                    if (grid == null) return;
                    int next = grid.nextIndex(current, direction);
                    if (next >= 0) {
                        View chosen = tasks.get(next);
                        chosen.requestFocus();
                        XposedHelpers.callMethod(root, "snapToPage", root.indexOfChild(chosen));
                    }
                    p.setResult(true);
                }
            });
            Log.i(TAG, "Native Quickstep icon-grid hooks ready; enabled=" + requested());
        } catch (Throwable error) {
            Log.e(TAG, "Quickstep icon-grid installation failed", error);
        }
    }

    private static boolean overviewEnabled(ViewGroup root) {
        try {
            return XposedHelpers.getBooleanField(root, "mOverviewStateEnabled");
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static int direction(int key) {
        switch (key) {
            case KeyEvent.KEYCODE_DPAD_UP: return 0;
            case KeyEvent.KEYCODE_DPAD_DOWN: return 1;
            case KeyEvent.KEYCODE_DPAD_LEFT: return 2;
            case KeyEvent.KEYCODE_DPAD_RIGHT: return 3;
            default: return -1;
        }
    }

    private static List<View> taskChildren(ViewGroup root, Class<?> task) {
        List<View> cards = new ArrayList<>();
        for (int i = 0; i < root.getChildCount(); i++) {
            View child = root.getChildAt(i);
            if (task.isInstance(child)) cards.add(child);
        }
        return cards;
    }

    private static boolean descendantOf(View focus, View parent) {
        if (focus == null) return false;
        for (android.view.ViewParent p = focus.getParent(); p != null; p = p.getParent()) {
            if (p == parent) return true;
        }
        return false;
    }

    private static boolean allHaveNativeIcons(List<View> tasks) {
        try {
            for (View task : tasks) {
                Object containers = XposedHelpers.callMethod(task, "getTaskContainers");
                if (!(containers instanceof List) || ((List<?>) containers).isEmpty())
                    return false;
                for (Object c : (List<?>) containers) {
                    if (!(XposedHelpers.callMethod(c, "getIconView") instanceof View))
                        return false;
                }
            }
            return true;
        } catch (Throwable ignored) {
            return false; // unsupported Q25 Quickstep build: preserve stock
        }
    }

    /**
     * Paint the already-bound native task's application Drawable directly.
     * We do not attach a new view or replicate any native task data. This
     * bypasses the native tile/card and chip rendering altogether.
     */
    private static void drawIconsOnly(View task, Canvas canvas) {
        int width = task.getWidth();
        int height = task.getHeight();
        if (width <= 0 || height <= 0) return;

        List<?> containers;
        try {
            Object nativeContainers = XposedHelpers.callMethod(
                    task, "getTaskContainers");
            if (!(nativeContainers instanceof List)) return;
            containers = (List<?>) nativeContainers;
        } catch (Throwable ignored) {
            return; // task not bound yet
        }
        if (containers.isEmpty()) return;

        int count = Math.min(4, containers.size());
        int size = Math.max(1, Math.min(dp(task, count == 1 ? 52 : 37),
                Math.min(width / (count > 2 ? 2 : count), height)
                        - dp(task, 8)));
        int cols = Math.min(2, count);
        int rows = (count + cols - 1) / cols;
        int step = size + dp(task, 3);
        int totalWidth = cols * size + (cols - 1) * dp(task, 3);
        int totalHeight = rows * size + (rows - 1) * dp(task, 3);
        int originX = (width - totalWidth) / 2;
        int originY = (height - totalHeight) / 2;

        // BBOS6 focus is blue; its target is only the icon cluster, not
        // a cell-sized rectangle or screenshot-like card.
        if (task.isFocused() || task.isPressed() || task.hasFocus()) {
            float pad = dp(task, 6);
            Paint marker = new Paint(Paint.ANTI_ALIAS_FLAG);
            marker.setColor(0xaa245b97);
            float left = originX - pad;
            float top = originY - pad;
            float right = originX + totalWidth + pad;
            float bottom = originY + totalHeight + pad;
            canvas.drawRoundRect(left, top, right, bottom,
                    dp(task, 9), dp(task, 9), marker);
            marker.setStyle(Paint.Style.STROKE);
            marker.setStrokeWidth(dp(task, 1));
            marker.setColor(0xffa8cef3);
            canvas.drawRoundRect(left, top, right, bottom,
                    dp(task, 9), dp(task, 9), marker);
        }

        for (int i = 0; i < count; i++) {
            try {
                Object nativeChip = XposedHelpers.callMethod(
                        containers.get(i), "getIconView");
                Object source = XposedHelpers.callMethod(nativeChip, "getDrawable");
                if (!(source instanceof Drawable)) continue;
                Drawable icon = (Drawable) source;
                Rect original = new Rect(icon.getBounds());
                int x = originX + (i % cols) * step;
                int y = originY + (i / cols) * step;
                try {
                    icon.setBounds(x, y, x + size, y + size);
                    icon.draw(canvas);
                } finally {
                    icon.setBounds(original);
                }
            } catch (Throwable ignored) {
                // Unbound or missing native icon: draw nothing for this task.
            }
        }
    }

    private static RecentsIconGridLayout geometry(View root, int tasks) {
        int width = root.getWidth() > 0 ? root.getWidth()
                : root.getResources().getDisplayMetrics().widthPixels;
        int height = root.getHeight() > 0 ? root.getHeight()
                : root.getResources().getDisplayMetrics().heightPixels;
        int overlap = 0;
        if (Build.VERSION.SDK_INT >= 30 && root.isAttachedToWindow()) {
            WindowInsets insets = root.getRootWindowInsets();
            if (insets != null) {
                int status = insets.getInsets(WindowInsets.Type.statusBars()).top;
                int[] location = new int[2];
                root.getLocationOnScreen(location);
                overlap = Math.max(0, status - Math.max(0, location[1]));
            }
        }
        return RecentsIconGridLayout.calculate(width, height,
                root.getResources().getDisplayMetrics().density, overlap, tasks);
    }

    private static int dp(View view, int amount) {
        return Math.round(amount * view.getResources().getDisplayMetrics().density);
    }

    private static boolean requested() {
        try {
            Class<?> props = Class.forName("android.os.SystemProperties");
            return (Boolean) XposedHelpers.callStaticMethod(
                    props, "getBoolean", FLAG, false);
        } catch (Throwable ignored) {
            return false; // fail closed to one untouched native renderer
        }
    }
}
