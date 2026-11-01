package dev.q25.trackpad;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.EditText;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * BB UI: experimental physical-keyboard type-to-search for Settings.
 * Scope in LSPosed to com.android.settings and com.android.settings.intelligence.
 */
public final class SettingsTypeToSearchHook implements IXposedHookLoadPackage {
    private static final String SETTINGS = "com.android.settings";
    private static final String SEARCH_PACKAGE = "com.android.settings.intelligence";
    private static final String HOMEPAGE = "com.android.settings.homepage.SettingsHomepageActivity";
    private static final String SEARCH_ACTIVITY = "com.android.settings.intelligence.search.SearchActivity";
    private static final String SEARCH_ACTION = "com.android.settings.action.SETTINGS_SEARCH";
    private static final String EXTRA_SEED = "dev.q25.trackpad.INITIAL_QUERY";

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lp) {
        if (SETTINGS.equals(lp.packageName)) {
            hookHomepage(lp.classLoader);
        } else if (SEARCH_PACKAGE.equals(lp.packageName)) {
            hookSearch(lp.classLoader);
        }
    }

    private void hookHomepage(ClassLoader loader) {
        try {
            XposedHelpers.findAndHookMethod(HOMEPAGE, loader, "dispatchKeyEvent",
                    KeyEvent.class, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    KeyEvent key = (KeyEvent) param.args[0];
                    if (!eligible(key)) return;
                    Activity activity = (Activity) param.thisObject;
                    if (hasFocusedEditor(activity.getWindow().getDecorView())) return;
                    int point = key.getUnicodeChar();
                    if (point == 0 || !Character.isLetterOrDigit(point)) return;

                    Intent intent = new Intent(SEARCH_ACTION);
                    intent.setPackage(SEARCH_PACKAGE);
                    intent.putExtra(EXTRA_SEED, new String(Character.toChars(point)));
                    try {
                        activity.startActivity(intent);
                        param.setResult(true);
                    } catch (RuntimeException error) {
                        XposedBridge.log("BB UI: search launch unavailable: " + error);
                    }
                }
            });
            XposedBridge.log("BB UI: Settings homepage type-to-search enabled");
        } catch (Throwable t) {
            XposedBridge.log("BB UI: homepage hook unavailable: " + t);
        }
    }

    private static boolean eligible(KeyEvent key) {
        if (key.getAction() != KeyEvent.ACTION_DOWN || key.getRepeatCount() != 0) return false;
        if (key.isCtrlPressed() || key.isAltPressed() || key.isMetaPressed()) return false;
        if (key.getKeyCode() == KeyEvent.KEYCODE_UNKNOWN) return false;
        InputDevice device = key.getDevice();
        return device != null
                && !device.isVirtual()
                && device.getKeyboardType() == InputDevice.KEYBOARD_TYPE_ALPHABETIC;
    }

    private static boolean hasFocusedEditor(View view) {
        if (view == null) return false;
        if (view instanceof EditText && view.hasFocus()) return true;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                if (hasFocusedEditor(group.getChildAt(i))) return true;
            }
        }
        return false;
    }

    private void hookSearch(ClassLoader loader) {
        try {
            XposedHelpers.findAndHookMethod(SEARCH_ACTIVITY, loader, "onCreate",
                    Bundle.class, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    Activity activity = (Activity) param.thisObject;
                    Intent intent = activity.getIntent();
                    if (intent == null) return;
                    final String initial = intent.getStringExtra(EXTRA_SEED);
                    if (initial == null || initial.isEmpty()) return;
                    // Clear the seed to ensure a recreation cannot overwrite edited text.
                    intent.removeExtra(EXTRA_SEED);
                    final View root = activity.getWindow().getDecorView();
                    final ViewTreeObserver observer = root.getViewTreeObserver();
                    observer.addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
                        private boolean applied;

                        @Override
                        public void onGlobalLayout() {
                            if (applied) return;
                            EditText editor = findVisibleEditor(root);
                            if (editor == null) return;
                            applied = true;
                            root.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                            editor.requestFocus();
                            editor.setText(initial);
                            editor.setSelection(editor.length());
                        }
                    });
                }
            });
            XposedBridge.log("BB UI: search input seed enabled");
        } catch (Throwable t) {
            XposedBridge.log("BB UI: search hook unavailable: " + t);
        }
    }

    private static EditText findVisibleEditor(View view) {
        if (view instanceof EditText && view.isShown() && view.isEnabled()) {
            return (EditText) view;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                EditText editor = findVisibleEditor(group.getChildAt(i));
                if (editor != null) return editor;
            }
        }
        return null;
    }
}
