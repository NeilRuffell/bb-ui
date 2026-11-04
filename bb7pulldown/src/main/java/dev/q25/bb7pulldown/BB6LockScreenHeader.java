package dev.q25.bb7pulldown;

import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Map;

/**
 * BBOS6 notification indicator strip. Date, time and carrier come from the
 * native SystemUI keyguard and are NOT duplicated in this display-only view.
 *
 * Future native ROM implementation will replace the keyguard blueprint
 * construction; the Vector prototype must not detach native clock/AOD views.
 */
final class BB6LockScreenHeader extends LinearLayout {
    private static final int WHITE = Color.WHITE;
    private final HorizontalScrollView notificationScroll;
    private final LinearLayout notificationRow;

    BB6LockScreenHeader(Context context) {
        super(context);
        setOrientation(VERTICAL);
        setClipChildren(false);
        setFocusable(false);
        setFocusableInTouchMode(false);
        setClickable(false);
        setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        setPadding(dp(12), dp(4), dp(12), dp(5));
        setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xdf111821, 0xbe131b25, 0x99131b25}));

        notificationScroll = new HorizontalScrollView(context);
        notificationScroll.setHorizontalScrollBarEnabled(false);
        notificationScroll.setFillViewport(true);
        notificationScroll.setFocusable(false);
        notificationScroll.setFocusableInTouchMode(false);
        notificationScroll.setClickable(false);
        notificationScroll.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        notificationRow = new LinearLayout(context);
        notificationRow.setOrientation(HORIZONTAL);
        notificationRow.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        notificationRow.setFocusable(false);
        notificationRow.setFocusableInTouchMode(false);
        notificationScroll.addView(notificationRow, new HorizontalScrollView.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT));
        addView(notificationScroll, new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, dp(31)));
        notificationScroll.setVisibility(GONE);
    }

    void showCounts(Map<String, Integer> counts) {
        notificationRow.removeAllViews();
        if (counts == null || counts.isEmpty()) {
            notificationScroll.setVisibility(GONE);
            return;
        }

        final Context context = getContext();
        final PackageManager pm = context.getPackageManager();
        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
            if (entry.getValue() == null || entry.getValue() <= 0) continue;
            LinearLayout indicator = new LinearLayout(context);
            indicator.setOrientation(HORIZONTAL);
            indicator.setGravity(Gravity.CENTER_VERTICAL);
            indicator.setFocusable(false);
            indicator.setClickable(false);
            LinearLayout.LayoutParams indicatorParams = new LinearLayout.LayoutParams(
                    LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT);
            indicatorParams.rightMargin = dp(14);

            ImageView icon = new ImageView(context);
            icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
            icon.setFocusable(false);
            icon.setClickable(false);
            try {
                Drawable drawable = pm.getApplicationIcon(entry.getKey());
                icon.setImageDrawable(drawable);
            } catch (PackageManager.NameNotFoundException missing) {
                icon.setImageResource(android.R.drawable.sym_def_app_icon);
            }
            indicator.addView(icon, new LinearLayout.LayoutParams(dp(20), dp(20)));

            TextView number = new TextView(context);
            number.setTextSize(14f);
            number.setTextColor(WHITE);
            number.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            number.setGravity(Gravity.CENTER_VERTICAL);
            number.setIncludeFontPadding(false);
            number.setFocusable(false);
            number.setClickable(false);
            number.setText(entry.getValue() > 99 ? "99+" : String.valueOf(entry.getValue()));
            LinearLayout.LayoutParams numberParams = new LinearLayout.LayoutParams(
                    LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT);
            numberParams.leftMargin = dp(4);
            indicator.addView(number, numberParams);
            notificationRow.addView(indicator, indicatorParams);
        }
        notificationScroll.setVisibility(
                notificationRow.getChildCount() == 0 ? GONE : VISIBLE);
    }

    private int dp(float value) {
        return (int) (getResources().getDisplayMetrics().density * value + 0.5f);
    }
}
