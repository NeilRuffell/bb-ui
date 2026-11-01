package dev.q25.bb7pulldown;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * The new independent pull-down surface. No native QS / notification
 * hierarchy is borrowed, moved or manipulated.
 */
final class TwoColumnPanel extends FrameLayout {
    interface CloseListener { void onClose(); }

    private final CloseListener onClose;
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
        left.addView(label(context, "15%"), new FrameLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, dp(64), Gravity.TOP));

        View divider = new View(context);
        divider.setBackgroundColor(Color.rgb(71, 111, 143));
        FrameLayout.LayoutParams dividerParams = new FrameLayout.LayoutParams(
                dp(1), LayoutParams.MATCH_PARENT, Gravity.END);
        left.addView(divider, dividerParams);

        FrameLayout right = new FrameLayout(context);
        right.setBackgroundColor(Color.rgb(22, 28, 36));
        columns.addView(right, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.MATCH_PARENT, PanelRatio.RIGHT_WEIGHT));
        right.addView(label(context, "85%"), new FrameLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, dp(64), Gravity.TOP));
    }

    private TextView label(Context context, String text) {
        TextView view = new TextView(context);
        view.setText(text);
        view.setTextColor(Color.rgb(177, 195, 207));
        view.setTextSize(12);
        view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        view.setGravity(Gravity.CENTER);
        view.setPadding(0, dp(20), 0, 0);
        return view;
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
