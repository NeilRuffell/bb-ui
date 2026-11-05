package dev.q25.bb7pulldown;

import android.content.res.Resources;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;

import java.lang.reflect.Method;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * Simplify only native heads-up popups, keeping all normal notification data
 * and BB UI shade actions (including RemoteInput) unchanged.
 *
 * Q25-verified entry point: NotificationContentView.setHeadsUpChild(View).
 * Expanded and contracted notification children are never modified.
 */
final class NativeSimpleHeadsUp {
    private static final String TAG = "BB7-HeadsUp";
    private static final String ENABLE_PROP = "debug.bb7pulldown.headsup_simple.enabled";
    private static final String CONTENT =
            "com.android.systemui.statusbar.notification.row.NotificationContentView";
    private static final String HEADER_WRAPPER =
            "com.android.systemui.statusbar.notification.row.wrapper."
                    + "NotificationHeaderViewWrapper";
    private static final String ROW =
            "com.android.systemui.statusbar.notification.row.ExpandableNotificationRow";
    private static boolean installed;
    private static boolean firstAppliedLogged;
    private static boolean missingControlIdsLogged;

    private NativeSimpleHeadsUp() {}

    static synchronized void install(ClassLoader loader) {
        if (installed) return;
        if (!enabled()) {
            Log.i(TAG, "Native heads-up simplification disabled by " + ENABLE_PROP);
            return;
        }

        Class<?> content = XposedHelpers.findClassIfExists(CONTENT, loader);
        Class<?> wrapper = XposedHelpers.findClassIfExists(HEADER_WRAPPER, loader);
        Class<?> row = XposedHelpers.findClassIfExists(ROW, loader);
        if (content == null || wrapper == null || row == null) {
            Log.w(TAG, "Required Q25 heads-up classes unavailable; stock popups retained");
            return;
        }

        // Q25's R8 build removed some upstream methods. Validate these exact
        // surviving signatures before registering ANY interception.
        try {
            content.getDeclaredMethod("setHeadsUpChild", View.class);
            content.getDeclaredMethod("applySystemActions", View.class);
            content.getDeclaredMethod("applyExternalSmartReplyState", View.class,
                    XposedHelpers.findClass(
                            "com.android.systemui.statusbar.policy.InflatedSmartReplyState",
                            loader));
            content.getDeclaredMethod("updateExpandButtonsDuringLayout",
                    boolean.class, boolean.class);
            row.getDeclaredMethod("isPinned");
            boolean foundExpandability = false;
            for (Method method : wrapper.getDeclaredMethods()) {
                if ("updateExpandability".equals(method.getName())
                        && method.getParameterTypes().length == 3) {
                    foundExpandability = true;
                    break;
                }
            }
            if (!foundExpandability) throw new NoSuchMethodException("updateExpandability");
        } catch (Throwable error) {
            Log.w(TAG, "Q25 heads-up API differs; stock popups retained", error);
            return;
        }

        try {
            // A heads-up child has its own native RemoteViews subtree, separate
            // from expanded/contracted children used by the shade.
            XposedHelpers.findAndHookMethod(content, "setHeadsUpChild",
                    View.class, new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam param) {
                            if (!param.hasThrowable() && param.args[0] instanceof View) {
                                simplify(param.args[0]);
                            }
                        }
                    });

            // Android may rebind bubble/snooze buttons or smart replies after
            // the initial RemoteViews inflation; do not alter those backends.
            XposedBridge.hookAllMethods(content, "applySystemActions",
                    new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam param) {
                            if (!param.hasThrowable() && param.args.length == 1
                                    && param.args[0] instanceof View) {
                                simplifyIfHeadsUp(param.args[0]);
                            }
                        }
                    });
            XposedBridge.hookAllMethods(content, "applyExternalSmartReplyState",
                    new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam param) {
                            if (!param.hasThrowable() && param.args.length > 0
                                    && param.args[0] instanceof View) {
                                simplifyIfHeadsUp(param.args[0]);
                            }
                        }
                    });
            XposedBridge.hookAllMethods(content, "updateExpandButtonsDuringLayout",
                    new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam param) {
                            simplifyCurrentHeadsUp(param.thisObject);
                        }
                    });

            // The native template binder may restore the expander/header
            // listener after inflation. Reapply only to the HUN child.
            XposedBridge.hookAllMethods(wrapper, "updateExpandability",
                    new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                simplifyIfHeadsUp(
                                        XposedHelpers.getObjectField(param.thisObject, "mView"));
                            } catch (Throwable error) {
                                Log.w(TAG, "Heads-up wrapper view unavailable", error);
                            }
                        }
                    });

            // Long press normally opens notification settings/snooze guts.
            // Block it only on a pinned popup; the shade retains long-press.
            XposedBridge.hookAllMethods(row, "doLongClickCallback", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    if (isPinnedPopup(param.thisObject)) param.setResult(null);
                }
            });
            XposedBridge.hookAllMethods(row, "onKeyLongPress", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    if (isPinnedPopup(param.thisObject)) param.setResult(true);
                }
            });

            installed = true;
            Log.i(TAG, "Native tap/open and swipe/dismiss heads-up simplification registered");
        } catch (Throwable error) {
            Log.e(TAG, "Unable to install Q25 native heads-up simplification", error);
        }
    }

    private static boolean isPinnedPopup(Object row) {
        try {
            return Boolean.TRUE.equals(XposedHelpers.callMethod(row, "isPinned"));
        } catch (Throwable ignored) {
            // No long-press suppression when the native state is unknown.
            return false;
        }
    }

    private static void simplifyCurrentHeadsUp(Object content) {
        try {
            simplifyIfHeadsUp(XposedHelpers.getObjectField(content, "mHeadsUpChild"));
        } catch (Throwable error) {
            Log.w(TAG, "Cannot obtain native heads-up child", error);
        }
    }

    private static void simplifyIfHeadsUp(Object candidate) {
        if (!(candidate instanceof View)) return;
        View view = (View) candidate;
        if (!isExactHeadsUpChild(view)) return;
        simplify(view);
    }

    private static boolean isExactHeadsUpChild(View view) {
        if (!(view.getParent() instanceof View)) return false;
        Object parent = view.getParent();
        if (!CONTENT.equals(parent.getClass().getName())) return false;
        try {
            return XposedHelpers.getObjectField(parent, "mHeadsUpChild") == view;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static void simplify(Object candidate) {
        if (!(candidate instanceof View)) return;
        View popup = (View) candidate;
        // setHeadsUpChild() invokes this after the child has been attached.
        if (!isExactHeadsUpChild(popup)) return;

        Resources resources = popup.getResources();
        int hidden = 0;
        int seen = 0;

        for (String name : SimpleHeadsUpPolicy.HIDDEN_VIEW_IDS) {
            int id = resources.getIdentifier(name, "id", "android");
            if (id == 0) continue;
            View target = popup.findViewById(id);
            if (target == null || target == popup) continue;
            seen++;
            if (target.getVisibility() != View.GONE) {
                target.setVisibility(View.GONE);
                hidden++;
            }
            // Invisible controls cannot retain independent interaction.
            target.setOnClickListener(null);
            target.setClickable(false);
            target.setLongClickable(false);
        }

        // Notification headers, conversation-icon containers and top-line
        // expand targets must pass taps through to Android's notification row.
        for (String name : SimpleHeadsUpPolicy.PASS_THROUGH_VIEW_IDS) {
            int id = resources.getIdentifier(name, "id", "android");
            if (id == 0) continue;
            View target = popup.findViewById(id);
            if (target == null || target == popup) continue;
            seen++;
            target.setOnClickListener(null);
            target.setClickable(false);
            target.setLongClickable(false);
        }

        if (seen == 0 && !missingControlIdsLogged) {
            missingControlIdsLogged = true;
            Log.w(TAG, "No framework action/expand IDs resolved in heads-up child");
        }
        if (!firstAppliedLogged) {
            firstAppliedLogged = true;
            Log.i(TAG, "First native heads-up simplified; matched controls=" + seen
                    + " hidden=" + hidden);
        }
        if (hidden > 0) popup.requestLayout();
    }

    private static boolean enabled() {
        try {
            Class<?> props = Class.forName("android.os.SystemProperties");
            return Boolean.TRUE.equals(XposedHelpers.callStaticMethod(
                    props, "getBoolean", ENABLE_PROP, true));
        } catch (Throwable error) {
            Log.w(TAG, "Unable to read heads-up rollback property; stock popups retained",
                    error);
            return false;
        }
    }
}
