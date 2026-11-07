package dev.q25.trackpad;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.util.Log;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.WeakHashMap;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * BBUI's exclusive Recents presentation, inside the original Quickstep host.
 *
 * System ownership: Quickstep keeps its task model, native task objects,
 * app icons, task loading, lifecycle, and ActivityTaskManager actions.
 *
 * Presentation ownership: the RecentsView.draw(Canvas) entry point is
 * REPLACED. Native TaskView.draw(), dispatchDraw(), native carousel cards,
 * thumbnail headers, labels, live-tile composition and stock focus visuals
 * are never invoked while this mode is active. No overlay, new Activity,
 * second Recents host or thumbnail renderer is created.
 *
 * Native TaskViews remain bound solely as Quickstep's data/action handles,
 * not as a second visual presentation. A direct ROM port can replace their
 * inflation with data-only task records.
 *
 * Q25 experimental switch: debug.bbui.recents.icons=true.
 */
public final class QuickstepIconGridHook implements IXposedHookLoadPackage {
    private static final String TAG = "BBUI-RecentsIcons";
    private static final String PACKAGE = "com.android.launcher3";
    private static final String FLAG = "debug.bbui.recents.icons";
    private static final String RECENTS = "com.android.quickstep.views.RecentsView";
    private static final String TASK = "com.android.quickstep.views.TaskView";

    private static final int BACKGROUND = Color.rgb(28, 32, 39);
    private static final int FOCUS_FILL = 0xff245b97;
    private static final int FOCUS_STROKE = 0xffa8cef3;
    private static final WeakHashMap<View, GridState> STATES = new WeakHashMap<>();
    private static final WeakHashMap<View, Boolean> CHROME_REMOVED = new WeakHashMap<>();
    private static final WeakHashMap<View, Boolean> SURFACE_PENDING = new WeakHashMap<>();
    private static final Paint PAINT = new Paint(Paint.ANTI_ALIAS_FLAG);

    private static Class<?> nativeTaskType;
    private static Class<?> nativeRecentsType;

    private static final class GridState {
        int selected = -1;
        int page;
        float startX;
        float startY;
        boolean swiping;
        boolean hadDown;
        boolean logged;
    }

    @Override public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lp) {
        if (!PACKAGE.equals(lp.packageName) || !PACKAGE.equals(lp.processName)) return;
        final Class<?> recents = XposedHelpers.findClassIfExists(RECENTS, lp.classLoader);
        final Class<?> task = XposedHelpers.findClassIfExists(TASK, lp.classLoader);
        final Class<?> paged = XposedHelpers.findClassIfExists(
                "com.android.launcher3.PagedView", lp.classLoader);
        if (recents == null || task == null || paged == null) {
            Log.e(TAG, "Native Quickstep host unavailable; no changes made");
            return;
        }
        nativeTaskType = task;
        nativeRecentsType = recents;

        try {
            // This is the decisive renderer boundary. Unlike the former
            // approach, NO native TaskView/card/header paint is executed.
            XposedBridge.hookAllMethods(recents, "draw", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    if (!enabled() || !(p.args[0] instanceof Canvas)) return;
                    ViewGroup root = (ViewGroup) p.thisObject;
                    paintGrid(root, (Canvas) p.args[0]);
                    p.setResult(null); // bypass RecentsView.draw AND ViewGroup children
                }
            });

            // Take the input stream before native carousel children receive
            // any touch. Coordinates now refer to BBUI icon cells only.
            XposedBridge.hookAllMethods(paged, "onInterceptTouchEvent",
                    new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    if (enabled() && recents.isInstance(p.thisObject))
                        p.setResult(true);
                }
            });
            XposedBridge.hookAllMethods(recents, "onTouchEvent", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    if (!enabled() || !(p.args[0] instanceof MotionEvent)) return;
                    handleTouch((ViewGroup) p.thisObject, (MotionEvent) p.args[0]);
                    p.setResult(true);
                }
            });
            XposedBridge.hookAllMethods(recents, "dispatchKeyEvent",
                    new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    if (!enabled() || !(p.args[0] instanceof KeyEvent)) return;
                    if (handleKey((ViewGroup) p.thisObject, (KeyEvent) p.args[0]))
                        p.setResult(true);
                }
            });

            // An entry transition still follows Android's real Overview
            // lifecycle. Only the presentation is ours.
            XposedBridge.hookAllMethods(recents, "setOverviewStateEnabled",
                    new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    if (!enabled() || p.args.length != 1
                            || !Boolean.TRUE.equals(p.args[0])) return;
                    ViewGroup root = (ViewGroup) p.thisObject;
                    GridState state = state(root);
                    state.selected = -1;
                    state.page = 0;
                    root.setFocusable(true);
                    root.post(() -> {
                        if (!enabled()) return;
                        removeStockChrome(root);
                        root.requestFocus();
                        root.invalidate();
                        settleLiveTile(root);
                    });
                }
            });

            // Native Quickstep can insert a temporary HOME TaskView during
            // entry. It is not an application to show in the icon switcher.
            XposedBridge.hookAllMethods(recents, "showCurrentTask",
                    new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    if (!enabled() || p.args.length < 1 || p.args[0] == null) return;
                    try {
                        Object info = XposedHelpers.callMethod(
                                p.args[0], "getTaskInfo1");
                        if (info != null && ((Number) XposedHelpers.callMethod(
                                info, "getActivityType")).intValue() == 2) {
                            p.setResult(null);
                        }
                    } catch (Throwable ignored) {
                        // Keep native task lifecycle if task type is unknown.
                    }
                }
            });

            // Native task data/icon updates invalidate this *one* painter.
            XposedBridge.hookAllMethods(recents, "onLayout", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    if (enabled()) ((View) p.thisObject).invalidate();
                }
            });
            Class<?> chip = XposedHelpers.findClassIfExists(
                    "com.android.quickstep.views.IconAppChipView", lp.classLoader);
            if (chip != null) {
                XposedBridge.hookAllMethods(chip, "setDrawable",
                        new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam p) {
                        if (!enabled() || !(p.thisObject instanceof View)) return;
                        View view = (View) p.thisObject;
                        for (int i = 0; view != null && i < 8; i++) {
                            if (recents.isInstance(view)) {
                                view.invalidate();
                                break;
                            }
                            view = view.getParent() instanceof View
                                    ? (View) view.getParent() : null;
                        }
                    }
                });
            }
            Log.i(TAG, "Exclusive native Overview painter registered; requested=" + enabled());
        } catch (Throwable error) {
            Log.e(TAG, "Could not register native Overview replacement", error);
        }
    }

    private static GridState state(View root) {
        GridState result = STATES.get(root);
        if (result == null) {
            result = new GridState();
            STATES.put(root, result);
        }
        return result;
    }

    private static List<View> tasks(ViewGroup root) {
        List<View> result = new ArrayList<>();
        for (int i = 0; i < root.getChildCount(); i++) {
            View child = root.getChildAt(i);
            if (nativeTaskType != null && nativeTaskType.isInstance(child))
                result.add(child);
        }
        return result;
    }

    private static RecentsIconGridLayout geometry(ViewGroup root, int count) {
        float density = root.getResources().getDisplayMetrics().density;
        return RecentsIconGridLayout.calculate(root.getWidth(), root.getHeight(),
                density, 0, count);
    }

    private static void normalize(GridState state, int count) {
        if (count <= 0) {
            state.selected = -1;
            state.page = 0;
            return;
        }
        if (state.selected < 0 || state.selected >= count) state.selected = 0;
        int maxPage = (count - 1) / RecentsIconGridLayout.PAGE_SIZE;
        state.page = Math.min(Math.max(state.page, 0), maxPage);
        if (state.selected / RecentsIconGridLayout.PAGE_SIZE != state.page)
            state.selected = state.page * RecentsIconGridLayout.PAGE_SIZE;
    }

    private static Drawable nativeIcon(View task, int containerIndex) {
        try {
            Object containers = XposedHelpers.callMethod(task, "getTaskContainers");
            if (!(containers instanceof List)) return null;
            List<?> all = (List<?>) containers;
            if (containerIndex < 0 || containerIndex >= all.size()) return null;
            Object chip = XposedHelpers.callMethod(
                    all.get(containerIndex), "getIconView");
            Object drawable = XposedHelpers.callMethod(chip, "getDrawable");
            return drawable instanceof Drawable ? (Drawable) drawable : null;
        } catch (Throwable ignored) {
            return null; // native model can be empty during initial binding
        }
    }

    private static int iconCount(View task) {
        try {
            Object containers = XposedHelpers.callMethod(task, "getTaskContainers");
            return containers instanceof List ? ((List<?>) containers).size() : 0;
        } catch (Throwable ignored) {
            return 0;
        }
    }

    private static void paintGrid(ViewGroup root, Canvas canvas) {
        canvas.drawColor(BACKGROUND); // entire stock Overview renderer is replaced
        List<View> all = tasks(root);
        if (all.isEmpty()) return;
        RecentsIconGridLayout grid = geometry(root, all.size());
        if (grid == null) return;
        GridState state = state(root);
        normalize(state, all.size());
        if (!state.logged) {
            state.logged = true;
            Log.i(TAG, "BBUI-only Recents drawing: " + all.size()
                    + " tasks, 4x4 icon slots, native cards NOT painted");
        }

        int pageOffset = state.page * grid.pageWidth;
        int first = state.page * RecentsIconGridLayout.PAGE_SIZE;
        int end = Math.min(all.size(), first + RecentsIconGridLayout.PAGE_SIZE);
        int normalIcon = Math.min(dp(root, 55),
                Math.min(grid.cellWidth, grid.cellHeight) - dp(root, 18));
        for (int index = first; index < end; index++) {
            View task = all.get(index);
            int number = Math.min(4, iconCount(task));
            if (number == 0) continue;
            int columns = number == 1 ? 1 : 2;
            int rows = (number + columns - 1) / columns;
            int iconSize = number == 1 ? normalIcon : Math.max(1, normalIcon / 2);
            int sep = number == 1 ? 0 : dp(root, 3);
            int iconsWide = columns * iconSize + (columns - 1) * sep;
            int iconsTall = rows * iconSize + (rows - 1) * sep;
            int x = grid.x(index) - pageOffset
                    + (grid.cellWidth - iconsWide) / 2;
            int y = grid.y(index) + (grid.cellHeight - iconsTall) / 2;

            if (state.selected == index) {
                synchronized (PAINT) {
                    PAINT.setStyle(Paint.Style.FILL);
                    PAINT.setColor(FOCUS_FILL);
                    float padding = dp(root, 7);
                    canvas.drawRoundRect(x - padding, y - padding,
                            x + iconsWide + padding, y + iconsTall + padding,
                            dp(root, 10), dp(root, 10), PAINT);
                    PAINT.setStyle(Paint.Style.STROKE);
                    PAINT.setStrokeWidth(dp(root, 2));
                    PAINT.setColor(FOCUS_STROKE);
                    canvas.drawRoundRect(x - padding, y - padding,
                            x + iconsWide + padding, y + iconsTall + padding,
                            dp(root, 10), dp(root, 10), PAINT);
                    PAINT.setStyle(Paint.Style.FILL);
                }
            }

            for (int j = 0; j < number; j++) {
                Drawable icon = nativeIcon(task, j);
                if (icon == null) continue;
                Rect restore = new Rect(icon.getBounds());
                int left = x + (j % columns) * (iconSize + sep);
                int top = y + (j / columns) * (iconSize + sep);
                try {
                    icon.setBounds(left, top, left + iconSize, top + iconSize);
                    icon.draw(canvas);
                } finally {
                    icon.setBounds(restore);
                }
            }
        }
    }

    private static boolean handleKey(ViewGroup root, KeyEvent key) {
        int code = key.getKeyCode();
        boolean navigate = code == KeyEvent.KEYCODE_DPAD_UP
                || code == KeyEvent.KEYCODE_DPAD_DOWN
                || code == KeyEvent.KEYCODE_DPAD_LEFT
                || code == KeyEvent.KEYCODE_DPAD_RIGHT;
        boolean activate = code == KeyEvent.KEYCODE_DPAD_CENTER
                || code == KeyEvent.KEYCODE_ENTER
                || code == KeyEvent.KEYCODE_NUMPAD_ENTER;
        boolean nextPage = code == KeyEvent.KEYCODE_PAGE_DOWN;
        boolean previousPage = code == KeyEvent.KEYCODE_PAGE_UP;
        if (!navigate && !activate && !nextPage && !previousPage) return false;
        if (key.getAction() != KeyEvent.ACTION_DOWN) return true;

        List<View> all = tasks(root);
        if (all.isEmpty()) return true;
        GridState state = state(root);
        normalize(state, all.size());
        RecentsIconGridLayout grid = geometry(root, all.size());
        if (grid == null) return true;

        if (activate) {
            launchTask(all.get(state.selected));
            return true;
        }

        int selected = state.selected;
        if (nextPage || previousPage) {
            int page = state.page + (nextPage ? 1 : -1);
            if (page >= 0 && page < grid.pageCount) {
                state.page = page;
                state.selected = page * RecentsIconGridLayout.PAGE_SIZE;
            }
        } else {
            int direction;
            switch (code) {
                case KeyEvent.KEYCODE_DPAD_UP: direction = 0; break;
                case KeyEvent.KEYCODE_DPAD_DOWN: direction = 1; break;
                case KeyEvent.KEYCODE_DPAD_LEFT: direction = 2; break;
                case KeyEvent.KEYCODE_DPAD_RIGHT: direction = 3; break;
                default: return true;
            }
            // The grid owns its geometry. Native Quickstep carousel
            // navigation, snaps and focus movement are NEVER used.
            int destination = grid.nextIndex(selected, direction);
            if (destination >= 0) {
                state.selected = destination;
                state.page = destination / RecentsIconGridLayout.PAGE_SIZE;
            }
        }
        root.invalidate();
        return true;
    }

    private static void handleTouch(ViewGroup root, MotionEvent event) {
        List<View> all = tasks(root);
        GridState state = state(root);
        normalize(state, all.size());
        RecentsIconGridLayout grid = geometry(root, all.size());
        if (grid == null) return;
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                state.startX = event.getX();
                state.startY = event.getY();
                state.hadDown = true;
                state.swiping = false;
                break;
            case MotionEvent.ACTION_MOVE:
                if (state.hadDown && Math.abs(event.getX() - state.startX)
                        > dp(root, 20)) state.swiping = true;
                break;
            case MotionEvent.ACTION_UP:
                if (!state.hadDown) break;
                float xDistance = event.getX() - state.startX;
                float yDistance = event.getY() - state.startY;
                if (Math.abs(xDistance) > root.getWidth() * 0.16f
                        && Math.abs(xDistance) > Math.abs(yDistance)) {
                    int next = state.page + (xDistance < 0 ? 1 : -1);
                    if (next >= 0 && next < grid.pageCount) {
                        state.page = next;
                        state.selected = next * RecentsIconGridLayout.PAGE_SIZE;
                        root.invalidate();
                    }
                } else if (!state.swiping && Math.abs(yDistance) < dp(root, 20)) {
                    int hit = hitTest(grid, state.page, event.getX(),
                            event.getY(), all.size());
                    if (hit >= 0) {
                        state.selected = hit;
                        root.invalidate();
                        launchTask(all.get(hit));
                    }
                }
                state.hadDown = false;
                state.swiping = false;
                break;
            case MotionEvent.ACTION_CANCEL:
                state.hadDown = false;
                state.swiping = false;
                break;
            default:
                break;
        }
    }

    private static int hitTest(RecentsIconGridLayout grid, int page,
            float x, float y, int count) {
        int begin = page * RecentsIconGridLayout.PAGE_SIZE;
        int end = Math.min(count, begin + RecentsIconGridLayout.PAGE_SIZE);
        for (int i = begin; i < end; i++) {
            int left = grid.x(i) - page * grid.pageWidth;
            int top = grid.y(i);
            if (x >= left && x < left + grid.cellWidth
                    && y >= top && y < top + grid.cellHeight) return i;
        }
        return -1;
    }

    /** Calls Android's own task-launch method, without the stock card animation. */
    private static void launchTask(View nativeTask) {
        try {
            ClassLoader loader = nativeTask.getClass().getClassLoader();
            Class<?> callbackType = Class.forName("kotlin.jvm.functions.Function1",
                    false, loader);
            Class<?> unitType = Class.forName("kotlin.Unit", false, loader);
            Object unit = XposedHelpers.getStaticObjectField(unitType, "INSTANCE");
            Object callback = Proxy.newProxyInstance(loader, new Class<?>[]{callbackType},
                    (proxy, method, args) -> {
                        if ("invoke".equals(method.getName())) return unit;
                        if ("toString".equals(method.getName())) return "BBUIRecentsCallback";
                        if ("hashCode".equals(method.getName()))
                            return System.identityHashCode(proxy);
                        if ("equals".equals(method.getName()))
                            return proxy == args[0];
                        return null;
                    });
            XposedHelpers.callMethod(nativeTask, "launchWithoutAnimation",
                    false, callback);
        } catch (Throwable error) {
            Log.w(TAG, "Native no-card launch unavailable, using TaskView action: "
                    + error.getClass().getSimpleName());
            // Still the same Android task action, never start a new app intent.
            nativeTask.performClick();
        }
    }

    private static void removeStockChrome(ViewGroup root) {
        if (CHROME_REMOVED.containsKey(root)) return;
        CHROME_REMOVED.put(root, Boolean.TRUE);
        removeNamedView(root, "overview_actions_view");
        removeNamedView(root, "empty_recents_message_view");
    }

    private static void removeNamedView(View root, String name) {
        Context context = root.getContext();
        int id = context.getResources().getIdentifier(name, "id", PACKAGE);
        if (id == 0) return;
        View found = root.getRootView().findViewById(id);
        if (found != null && found.getParent() instanceof ViewGroup) {
            ((ViewGroup) found.getParent()).removeView(found);
            Log.i(TAG, "Detached stock Overview chrome: " + name);
        }
    }

    /** End stock live-tile remote drawing after native Overview entry. */
    private static void settleLiveTile(View root) {
        if (SURFACE_PENDING.containsKey(root)) return;
        try {
            if (!XposedHelpers.getBooleanField(root, "mOverviewStateEnabled")
                    || XposedHelpers.getObjectField(root, "mRecentsAnimationController") == null
                    || XposedHelpers.getBooleanField(root, "mRunningTaskShowScreenshot"))
                return;
        } catch (Throwable ignored) {
            return;
        }
        SURFACE_PENDING.put(root, Boolean.TRUE);
        try {
            XposedHelpers.callMethod(root, "switchToScreenshot", (Runnable) () -> {
                try {
                    XposedHelpers.callMethod(root, "finishRecentsAnimation",
                            true, false, (Runnable) () -> {
                                SURFACE_PENDING.remove(root);
                                root.invalidate();
                            });
                } catch (Throwable error) {
                    SURFACE_PENDING.remove(root);
                    Log.w(TAG, "Native animation completion unavailable", error);
                }
            });
        } catch (Throwable error) {
            SURFACE_PENDING.remove(root);
            Log.w(TAG, "Native screenshot conversion unavailable", error);
        }
    }

    private static int dp(View root, float value) {
        return Math.round(value * root.getResources().getDisplayMetrics().density);
    }

    private static boolean enabled() {
        try {
            Class<?> properties = Class.forName("android.os.SystemProperties");
            return (Boolean) XposedHelpers.callStaticMethod(properties,
                    "getBoolean", FLAG, false);
        } catch (Throwable error) {
            return false;
        }
    }
}
