package dev.q25.trackpad;

import android.content.Context;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Build;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.text.TextUtils;

import java.lang.reflect.Method;
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
 * views. We arrange those objects as icons, using the existing Quickstep icon
 * and label from each TaskContainer instead of creating an overlay, activity,
 * task snapshot model or duplicate task views.
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
    private static final WeakHashMap<View, Boolean> STYLED = new WeakHashMap<>();
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
            // These are native Quickstep layout hooks, not a second renderer.
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
                    int extraClearPage = clearAll == null ? 0 : 1;
                    int endScroll = Math.max(0,
                            grid.pageCount + extraClearPage - 1) * grid.pageWidth;
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
                                // No reparenting, cloning or hidden stock
                                // RecentsView: these ARE Quickstep TaskViews.
                                if (child.getLeft() != x || child.getTop() != y
                                        || child.getWidth() != child.getMeasuredWidth()
                                        || child.getHeight() != child.getMeasuredHeight()) {
                                    child.layout(x, y, x + child.getMeasuredWidth(),
                                            y + child.getMeasuredHeight());
                                }
                                makeNativeTaskIconOnly(child, grid);
                            }
                            taskNumber++;
                        } else {
                            scroll = endScroll;
                            if (place) {
                                int x = scroll + (root.getWidth()
                                        - child.getMeasuredWidth()) / 2;
                                int y = (root.getHeight() - child.getMeasuredHeight()) / 2;
                                child.layout(x, y, x + child.getMeasuredWidth(),
                                        y + child.getMeasuredHeight());
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
                    if (!(XposedHelpers.callMethod(c, "getIconView") instanceof View)
                            || !(XposedHelpers.callMethod(c, "getTaskContentView") instanceof View))
                        return false;
                }
            }
            return true;
        } catch (Throwable ignored) {
            return false; // unsupported Q25 Quickstep build: preserve stock
        }
    }

    /**
     * Reconfigure native TaskView's existing app-chip icon and title.
     * The native thumbnail component is not a second Recents presentation;
     * it remains measured for Quickstep animations but is not rendered in
     * this one icon-only TaskView presentation.
     */
    private static void makeNativeTaskIconOnly(View task, RecentsIconGridLayout grid) {
        if (!(task instanceof FrameLayout)) return;
        if (STYLED.put(task, Boolean.TRUE) == null) {
            task.setBackground(focusBackground(task));
            ((FrameLayout) task).setDescendantFocusability(
                    ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        }
        try {
            @SuppressWarnings("unchecked")
            List<Object> containers = (List<Object>)
                    XposedHelpers.callMethod(task, "getTaskContainers");
            for (Object container : containers) {
                View preview = (View) XposedHelpers.callMethod(
                        container, "getTaskContentView");
                preview.setVisibility(View.INVISIBLE);
                View iconChip = (View) XposedHelpers.callMethod(container, "getIconView");
                iconChip.setVisibility(View.VISIBLE);
                iconChip.setFocusable(false);
                iconChip.setClickable(false);
                iconChip.setClipToOutline(false);
                iconChip.setBackground(null);
                iconChip.setForeground(null);
                if (iconChip.getLayoutParams() instanceof FrameLayout.LayoutParams) {
                    FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams)
                            iconChip.getLayoutParams();
                    int width = grid.cellWidth - dp(task, 8);
                    int height = grid.cellHeight - dp(task, 8);
                    if (lp.width != width || lp.height != height
                            || lp.gravity != Gravity.CENTER) {
                        lp.width = width;
                        lp.height = height;
                        lp.gravity = Gravity.CENTER;
                        lp.leftMargin = 0;
                        lp.rightMargin = 0;
                        lp.topMargin = 0;
                        lp.bottomMargin = 0;
                        iconChip.setLayoutParams(lp);
                    }
                }
                layoutNativeAppChip(iconChip, grid);
                // Icon tap is now the same TaskView action as tapping its
                // normal native thumbnail: native launch, never a new task API.
                iconChip.setOnClickListener(v -> task.performClick());
            }
        } catch (Throwable error) {
            Log.e(TAG, "Native icon-chip presentation unavailable", error);
        }
    }

    private static void layoutNativeAppChip(View chip, RecentsIconGridLayout grid) {
        if (!(chip instanceof FrameLayout)) return;
        Context context = chip.getContext();
        int iconId = context.getResources().getIdentifier(
                "icon_view", "id", PACKAGE);
        int titleId = context.getResources().getIdentifier(
                "icon_title", "id", PACKAGE);
        int arrowId = context.getResources().getIdentifier(
                "icon_arrow", "id", PACKAGE);
        if (arrowId != 0) {
            View arrow = chip.findViewById(arrowId);
            if (arrow != null) arrow.setVisibility(View.GONE);
        }
        if (iconId != 0) {
            View icon = chip.findViewById(iconId);
            if (icon != null && icon.getLayoutParams() instanceof FrameLayout.LayoutParams) {
                FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams)
                        icon.getLayoutParams();
                int iconSize = Math.min(dp(chip, 52), grid.cellWidth - dp(chip, 20));
                if (lp.width != iconSize || lp.height != iconSize
                        || lp.gravity != (Gravity.TOP | Gravity.CENTER_HORIZONTAL)) {
                    lp.width = iconSize;
                    lp.height = iconSize;
                    lp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
                    lp.topMargin = dp(chip, 9);
                    lp.leftMargin = 0;
                    lp.rightMargin = 0;
                    icon.setLayoutParams(lp);
                }
            }
        }
        if (titleId != 0) {
            View title = chip.findViewById(titleId);
            if (title instanceof TextView) {
                TextView label = (TextView) title;
                label.setVisibility(View.VISIBLE);
                label.setGravity(Gravity.CENTER);
                label.setTextSize(11f);
                label.setTextColor(0xfff2f2f4);
                label.setSingleLine(true);
                label.setEllipsize(TextUtils.TruncateAt.END);
                if (label.getLayoutParams() instanceof FrameLayout.LayoutParams) {
                    FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams)
                            label.getLayoutParams();
                    int width = grid.cellWidth - dp(chip, 14);
                    if (lp.width != width || lp.gravity != (Gravity.BOTTOM
                            | Gravity.CENTER_HORIZONTAL)) {
                        lp.width = width;
                        lp.height = dp(chip, 28);
                        lp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
                        lp.bottomMargin = dp(chip, 6);
                        lp.leftMargin = 0;
                        lp.rightMargin = 0;
                        label.setLayoutParams(lp);
                    }
                }
            }
        }
    }

    private static StateListDrawable focusBackground(View view) {
        GradientDrawable selected = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xff4384c7, 0xff204e83});
        selected.setCornerRadius(dp(view, 7));
        selected.setStroke(dp(view, 2), 0xffadcef5);
        GradientDrawable normal = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xff4b4e53, 0xff32363c});
        normal.setCornerRadius(dp(view, 7));
        normal.setStroke(dp(view, 1), 0xff5a5d63);
        StateListDrawable states = new StateListDrawable();
        states.addState(new int[]{android.R.attr.state_focused}, selected);
        states.addState(new int[]{android.R.attr.state_pressed}, selected);
        states.addState(new int[]{}, normal);
        return states;
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
