package dev.q25.bb7pulldown;

import dev.q25.trackpad.theme.BB7Theme;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

/**
 * Unlabeled BBOS6-style header for Notifications and Quick Settings.
 *
 * The 49dp gradient bar formerly displayed the date. It now presents two
 * equal-width focus regions while the shared navigation owner keeps all
 * keyboard state and action routing.
 */
final class ShadeDateHeader extends LinearLayout {
    private static final int HEIGHT_DP = 49;
    private final FrameLayout notificationsSegment;
    private final FrameLayout quickSettingsSegment;
    private int focusedZone = ShadeStatusNavigation.NONE;

    ShadeDateHeader(Context context) {
        super(context);
        setOrientation(HORIZONTAL);
        setGravity(Gravity.CENTER_VERTICAL);
        final BB7Theme.Palette palette = BB7Theme.palette(context);
        final int[] colors = palette == BB7Theme.Palette.DARK
                ? new int[]{0xff526477, 0xff3b4e60, 0xff263747}
                : new int[]{0xfff9fbfe, 0xffe5ecf4, 0xffc8d4e1};
        setBackground(new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM, colors));
        setFocusable(false);
        setClickable(false);
        setContentDescription("BB UI shade navigation");
        setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);

        notificationsSegment = segment(context, "Notifications");
        quickSettingsSegment = segment(context, "Quick Settings");
        addView(notificationsSegment, new LinearLayout.LayoutParams(
                0, LayoutParams.MATCH_PARENT, 1f));
        addView(quickSettingsSegment, new LinearLayout.LayoutParams(
                0, LayoutParams.MATCH_PARENT, 1f));
    }

    private static FrameLayout segment(Context context, String description) {
        FrameLayout view = new FrameLayout(context);
        view.setFocusable(false);
        view.setClickable(false);
        view.setContentDescription(description);
        view.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        return view;
    }

    void setFocusedZone(int zone) {
        if (focusedZone == zone) return;
        focusedZone = zone;
        notificationsSegment.setBackground(zone == ShadeStatusNavigation.NOTIFICATIONS
                ? BB7Theme.selected(getContext(), dp(3), dp(1)) : null);
        quickSettingsSegment.setBackground(zone == ShadeStatusNavigation.QUICK_SETTINGS
                ? BB7Theme.selected(getContext(), dp(3), dp(1)) : null);
    }

    static LinearLayout.LayoutParams layoutParams(Context context) {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (int) (HEIGHT_DP * context.getResources().getDisplayMetrics().density + 0.5f));
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
