package dev.q25.bb7pulldown;

import android.content.Context;
import android.graphics.Color;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ImageView;
import android.widget.ScrollView;

import java.util.ArrayList;
import java.util.List;

/**
 * The new independent pull-down surface. No native QS / notification
 * hierarchy is borrowed, moved or manipulated.
 */
final class TwoColumnPanel extends FrameLayout {
    interface CloseListener { void onClose(); }

    private final CloseListener onClose;
    private float touchStartY;
    private boolean trackingClose;
    private final LinearLayout iconList;
    private List<String> renderedPackages = new ArrayList<>();

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
        FrameLayout.LayoutParams dividerParams = new FrameLayout.LayoutParams(
                dp(1), LayoutParams.MATCH_PARENT, Gravity.END);
        left.addView(divider, dividerParams);

        FrameLayout right = new FrameLayout(context);
        right.setBackgroundColor(Color.rgb(22, 28, 36));
        columns.addView(right, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.MATCH_PARENT, PanelRatio.RIGHT_WEIGHT));
        // The 85% content pane deliberately remains empty at this stage.
    }

    /** A stable vertical list of package icons; only changes on index updates. */
    void setNotificationPackages(List<String> packages) {
        List<String> distinct = NotificationAppIndex.uniquePackages(packages);
        if (renderedPackages.equals(distinct)) return;
        renderedPackages = distinct;
        iconList.removeAllViews();

        PackageManager pm = getContext().getPackageManager();
        for (String pkg : distinct) {
            ImageView icon = new ImageView(getContext());
            Drawable drawable;
            CharSequence label;
            try {
                drawable = pm.getApplicationIcon(pkg);
                label = pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0));
            } catch (PackageManager.NameNotFoundException error) {
                // Keep a predictable icon even if its package was uninstalled
                // or lives in another profile.
                drawable = pm.getDefaultActivityIcon();
                label = pkg;
            }
            icon.setImageDrawable(drawable);
            icon.setContentDescription(label);
            icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
            icon.setFocusable(false);
            icon.setClickable(false);
            icon.setPadding(dp(5), dp(8), dp(5), dp(8));
            iconList.addView(icon, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(54)));
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
        if (event.getKeyCode() == KeyEvent.KEYCODE_BACK
                && event.getAction() == KeyEvent.ACTION_UP) {
            onClose.onClose();
            return true;
        }
        if (event.getKeyCode() == KeyEvent.KEYCODE_BACK) return true;
        return super.dispatchKeyEvent(event);
    }

    private int dp(float value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }
}
