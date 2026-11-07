package dev.q25.bb7pulldown;

import dev.q25.trackpad.theme.BB7Theme;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.TextView;
import android.widget.LinearLayout;

/** Transient bottom-left BlackBerry application menu. Not part of the shade. */
final class ShadeDateHeader extends LinearLayout {
    private static final int WIDTH_DP = 224;
    private static final int ROW_HEIGHT_DP = 48;
    private final TextView closeApplicationRow;
    private final TextView switchApplicationRow;
    private final TextView notificationsRow;
    private final TextView quickSettingsRow;
    private final int normalTextColor;
    private final boolean closeAvailable;
    private int focusedZone = ShadeStatusNavigation.NONE;

    ShadeDateHeader(Context context, boolean closeAvailable) {
        super(context);
        this.closeAvailable = closeAvailable;
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
        setContentDescription("BlackBerry application menu");

        closeApplicationRow = row(context, "Close Application");
        switchApplicationRow = row(context, "Switch Application");
        notificationsRow = row(context, "Notifications");
        quickSettingsRow = row(context, "Quick Settings");

        if (closeAvailable) addRow(closeApplicationRow);
        else closeApplicationRow.setVisibility(View.GONE);
        addRow(switchApplicationRow);
        addRow(notificationsRow);
        addRow(quickSettingsRow);
    }

    private void addRow(TextView row) {
        addView(row, new LinearLayout.LayoutParams(
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
        style(closeApplicationRow, zone == ShadeStatusNavigation.CLOSE_APPLICATION);
        style(switchApplicationRow, zone == ShadeStatusNavigation.SWITCH_APPLICATION);
        style(notificationsRow, zone == ShadeStatusNavigation.NOTIFICATIONS);
        style(quickSettingsRow, zone == ShadeStatusNavigation.QUICK_SETTINGS);
    }

    private void style(TextView view, boolean selected) {
        view.setBackground(selected
                ? BB7Theme.selected(getContext(), dp(2), dp(1)) : null);
        view.setTextColor(selected ? Color.WHITE : normalTextColor);
    }

    static int widthPx(Context context) {
        return Math.round(WIDTH_DP * context.getResources().getDisplayMetrics().density);
    }

    static int heightPx(Context context, boolean closeAvailable) {
        int rowHeight = Math.round(ROW_HEIGHT_DP
                * context.getResources().getDisplayMetrics().density);
        return rowHeight * (closeAvailable ? 4 : 3);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
