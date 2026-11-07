package dev.q25.bb7pulldown;

import dev.q25.trackpad.theme.BB7Theme;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.widget.TextView;
import android.widget.LinearLayout;

/** Transient bottom-left BB-button page popup. Never attached to the shade. */
final class ShadeDateHeader extends LinearLayout {
    private static final int WIDTH_DP = 224;
    private static final int ROW_HEIGHT_DP = 48;
    private final TextView notificationsRow;
    private final TextView quickSettingsRow;
    private final int normalTextColor;
    private int focusedZone = ShadeStatusNavigation.NONE;

    ShadeDateHeader(Context context) {
        super(context);
        setOrientation(VERTICAL);
        final BB7Theme.Palette palette = BB7Theme.palette(context);
        normalTextColor = palette.headingText;
        int[] colors = palette == BB7Theme.Palette.DARK
                ? new int[]{0xff526477, 0xff3b4e60, 0xff263747}
                : new int[]{0xfff9fbfe, 0xffe5ecf4, 0xffc8d4e1};
        GradientDrawable background = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM, colors);
        background.setCornerRadius(dp(3));
        background.setStroke(dp(1), 0xff526477);
        setBackground(background);
        setClipToOutline(true);
        setFocusable(false);
        setContentDescription("BB UI page menu");

        notificationsRow = row(context, "Notifications");
        quickSettingsRow = row(context, "Quick Settings");
        addView(notificationsRow, new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, dp(ROW_HEIGHT_DP)));
        addView(quickSettingsRow, new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, dp(ROW_HEIGHT_DP)));
    }

    private TextView row(Context context, String label) {
        TextView view = new TextView(context);
        view.setText(label);
        view.setTextSize(17);
        view.setTextColor(normalTextColor);
        view.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        view.setPadding(dp(14), 0, dp(10), 0);
        view.setSingleLine(true);
        view.setFocusable(false);
        view.setClickable(false);
        return view;
    }

    void setFocusedZone(int zone) {
        if (focusedZone == zone) return;
        focusedZone = zone;
        notificationsRow.setBackground(zone == ShadeStatusNavigation.NOTIFICATIONS
                ? BB7Theme.selected(getContext(), dp(2), dp(1)) : null);
        quickSettingsRow.setBackground(zone == ShadeStatusNavigation.QUICK_SETTINGS
                ? BB7Theme.selected(getContext(), dp(2), dp(1)) : null);
        notificationsRow.setTextColor(
                zone == ShadeStatusNavigation.NOTIFICATIONS ? Color.WHITE : normalTextColor);
        quickSettingsRow.setTextColor(
                zone == ShadeStatusNavigation.QUICK_SETTINGS ? Color.WHITE : normalTextColor);
    }

    static int widthPx(Context context) {
        return Math.round(WIDTH_DP * context.getResources().getDisplayMetrics().density);
    }

    static int heightPx(Context context) {
        return Math.round(ROW_HEIGHT_DP * 2
                * context.getResources().getDisplayMetrics().density);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
