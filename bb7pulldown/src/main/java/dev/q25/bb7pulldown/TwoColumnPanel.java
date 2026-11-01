package dev.q25.bb7pulldown;

import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Independent 15/85 notification panel. The left side is a selectable
 * presentation-only list; the right side remains empty at this stage.
 * Android owns notification lifecycle and keyguard privacy as before.
 */
final class TwoColumnPanel extends FrameLayout {
    interface CloseListener { void onClose(); }

    private static final String TAG = "BB7-PullDown";
    private final CloseListener onClose;
    private final LinearLayout iconList;
    private final List<FrameLayout> iconRows = new ArrayList<>();
    private List<String> renderedPackages = Collections.emptyList();
    private String selectedPackage;
    private float touchStartY;
    private boolean trackingClose;

    TwoColumnPanel(Context context, CloseListener close) {
        super(context);
        onClose = close;
        setFocusable(true);
        setFocusableInTouchMode(true);
        setBackgroundColor(Color.rgb(16, 20, 26));
        setContentDescription("BB7 two-column pull-down");

        LinearLayout columns = new LinearLayout(context);
        columns.setOrientation(LinearLayout.HORIZONTAL);
        columns.setWeightSum(PanelRatio.TOTAL_WEIGHT);
        addView(columns, new FrameLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        FrameLayout left = new FrameLayout(context);
        left.setBackgroundColor(Color.rgb(34, 42, 52));
        columns.addView(left, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.MATCH_PARENT, PanelRatio.LEFT_WEIGHT));

        ScrollView iconScroller = new ScrollView(context);
        iconScroller.setFillViewport(false);
        iconScroller.setVerticalScrollBarEnabled(false);
        iconScroller.setClipToPadding(false);
        iconScroller.setPadding(0, dp(10), 0, dp(10));
        left.addView(iconScroller, new FrameLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        iconList = new LinearLayout(context);
        iconList.setOrientation(LinearLayout.VERTICAL);
        iconList.setGravity(Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        iconScroller.addView(iconList, new ScrollView.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

        View divider = new View(context);
        divider.setBackgroundColor(Color.rgb(71, 111, 143));
        left.addView(divider, new FrameLayout.LayoutParams(
                dp(1), LayoutParams.MATCH_PARENT, Gravity.END));

        FrameLayout right = new FrameLayout(context);
        right.setBackgroundColor(Color.rgb(22, 28, 36));
        columns.addView(right, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.MATCH_PARENT, PanelRatio.RIGHT_WEIGHT));
    }

    /**
     * Called on the SystemUI UI thread after a notification app list update.
     * Preserve selection by package, and restore focus if a row was focused.
     */
    void setNotificationPackages(List<String> packages) {
        List<String> distinct = NotificationAppIndex.uniquePackages(packages);
        if (renderedPackages.equals(distinct)) return;

        int previousIndex = renderedPackages.indexOf(selectedPackage);
        boolean hadRowFocus = false;
        for (FrameLayout row : iconRows) {
            if (row.isFocused()) {
                hadRowFocus = true;
                break;
            }
        }
        int newIndex = AppSelectionPolicy.afterUpdate(selectedPackage, previousIndex, distinct);
        renderedPackages = distinct;
        selectedPackage = newIndex < 0 ? null : distinct.get(newIndex);

        iconList.removeAllViews();
        iconRows.clear();
        PackageManager pm = getContext().getPackageManager();
        for (int i = 0; i < distinct.size(); i++) {
            final String pkg = distinct.get(i);
            FrameLayout row = new FrameLayout(getContext());
            row.setFocusable(true);
            row.setFocusableInTouchMode(true);
            row.setClickable(true);
            row.setOnFocusChangeListener((view, focused) -> {
                if (focused) select(pkg);
            });
            row.setOnClickListener(view -> {
                select(pkg);
                view.requestFocus();
            });
            row.setOnKeyListener((view, keyCode, event) -> onIconKey(view, keyCode, event));

            ImageView icon = new ImageView(getContext());
            Drawable drawable;
            CharSequence label;
            try {
                drawable = pm.getApplicationIcon(pkg);
                label = pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0));
            } catch (PackageManager.NameNotFoundException | RuntimeException error) {
                drawable = pm.getDefaultActivityIcon();
                label = pkg;
            }
            row.setContentDescription(label);
            icon.setImageDrawable(drawable);
            icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
            icon.setFocusable(false);
            icon.setClickable(false);
            icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            icon.setPadding(dp(5), dp(8), dp(5), dp(8));
            row.addView(icon, new FrameLayout.LayoutParams(
                    LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
            iconList.addView(row, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(56)));
            iconRows.add(row);
        }

        updateSelectedHighlight();
        if (hadRowFocus && !iconRows.isEmpty()) {
            // Removing a focused row may move focus to the root. Restore it
            // explicitly so keyboard/trackpad navigation continues smoothly.
            focusSelectedAppIcon();
        }
        Log.i(TAG, "App icon selector updated: count=" + distinct.size()
                + " selectedIndex=" + newIndex);
    }

    /** Called once the pull-down entrance animation finishes. */
    void focusSelectedAppIcon() {
        int index = renderedPackages.indexOf(selectedPackage);
        if (index >= 0 && index < iconRows.size()) {
            iconRows.get(index).requestFocus();
        } else {
            requestFocus();
        }
    }

    private void select(String pkg) {
        if (pkg.equals(selectedPackage)) return;
        selectedPackage = pkg;
        updateSelectedHighlight();
        Log.i(TAG, "App icon selected: index="
                + (renderedPackages.indexOf(pkg) + 1)
                + " of=" + renderedPackages.size());
    }

    private void updateSelectedHighlight() {
        for (int i = 0; i < iconRows.size(); i++) {
            FrameLayout row = iconRows.get(i);
            boolean selected = renderedPackages.get(i).equals(selectedPackage);
            row.setSelected(selected);
            if (selected) {
                GradientDrawable blue = new GradientDrawable(
                        GradientDrawable.Orientation.TOP_BOTTOM,
                        new int[]{Color.rgb(91, 180, 231), Color.rgb(20, 95, 169)});
                blue.setCornerRadius(dp(3));
                blue.setStroke(dp(1), Color.rgb(180, 226, 251));
                row.setBackground(blue);
            } else {
                row.setBackground(null);
            }
        }
    }

    private boolean onIconKey(View view, int keyCode, KeyEvent event) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (event.getAction() == KeyEvent.ACTION_DOWN) {
                    int selectedIndex = renderedPackages.indexOf(selectedPackage);
                    int direction = keyCode == KeyEvent.KEYCODE_DPAD_UP ? -1 : 1;
                    int next = AppSelectionPolicy.move(selectedIndex,
                            direction, iconRows.size());
                    if (next >= 0) iconRows.get(next).requestFocus();
                }
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                // Until the right pane is implemented, keep focus in the
                // only navigable column.
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
            case KeyEvent.KEYCODE_SPACE:
                if (event.getAction() == KeyEvent.ACTION_UP) {
                    view.performClick();
                }
                return true;
            default:
                return false;
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                trackingClose = true;
                touchStartY = event.getRawY();
                return true;
            case MotionEvent.ACTION_MOVE:
                if (trackingClose && touchStartY - event.getRawY() >= dp(65)) {
                    trackingClose = false;
                    onClose.onClose();
                }
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                trackingClose = false;
                return true;
            default:
                return true;
        }
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getKeyCode() == KeyEvent.KEYCODE_BACK) {
            if (event.getAction() == KeyEvent.ACTION_UP) onClose.onClose();
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    private int dp(float value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }
}
