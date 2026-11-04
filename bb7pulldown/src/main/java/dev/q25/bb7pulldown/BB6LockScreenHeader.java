package dev.q25.bb7pulldown;

import dev.q25.trackpad.theme.BB7Theme;

import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextClock;
import android.widget.TextView;

import java.util.Map;

/**
 * BBOS6-inspired, display-only banner in the existing SystemUI keyguard view.
 * Date/time and notification icons/counts on the native keyguard. The
 * native status bar remains sole owner of carrier and system status.
 *
 * One native keyboard focus target is the clock/date row. Its focus
 * styling also selects the native keyguard_header status bar as a single
 * status/date-time item. Notification icons remain outside that focus area.
 * Enter/trackpad click do nothing; Space remains routed to the existing
 * WindowRootView-level credential hook.
 *
 * The native status bar keeps ownership of radios and battery indications.
 */
final class BB6LockScreenHeader extends LinearLayout {
    private static final int WHITE = Color.WHITE;
    private static final int RULE = 0x75ffffff;
    private final LinearLayout clockRow;
    private View nativeStatusBar;
    private Drawable nativeStatusBarBackground;
    private boolean nativeStatusBarHighlighted;
    private final HorizontalScrollView notificationScroll;
    private final LinearLayout notificationRow;

    BB6LockScreenHeader(Context context) {
        super(context);
        setOrientation(VERTICAL);
        setClipChildren(false);
        // The whole header is NOT focusable. A passive notification strip
        // must never be included in the status/date-time focus region.
        setFocusable(false);
        setFocusableInTouchMode(false);
        setClickable(false);
        setDescendantFocusability(FOCUS_AFTER_DESCENDANTS);
        setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        setPadding(dp(12), dp(4), dp(12), dp(5));
        setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xdf111821, 0xbe131b25, 0x99131b25}));

        clockRow = new LinearLayout(context);
        clockRow.setOrientation(HORIZONTAL);
        clockRow.setGravity(Gravity.CENTER_VERTICAL);
        // Exactly one focus owner: this row. It coordinates the background
        // of the separate native KeyguardStatusBarView on focus changes.
        clockRow.setFocusable(true);
        clockRow.setFocusableInTouchMode(false);
        clockRow.setClickable(false);
        clockRow.setDescendantFocusability(FOCUS_BLOCK_DESCENDANTS);
        clockRow.setOnKeyListener((view, keyCode, event) ->
                BB6LockScreenSelectionPolicy.isNoopSelectionKey(keyCode));
        clockRow.setOnFocusChangeListener((view, focused) ->
                updateNativeStatusBarHighlight(focused));
        StateListDrawable clockBackground = new StateListDrawable();
        clockBackground.addState(new int[]{android.R.attr.state_focused},
                BB7Theme.selected(dp(5), dp(1)));
        clockBackground.addState(new int[0], new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x00000000, 0x00000000}));
        clockRow.setBackground(clockBackground);
        addView(clockRow, new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, dp(49)));

        TextClock date = new TextClock(context);
        date.setFormat12Hour("EEE, MMM d");
        date.setFormat24Hour("EEE, MMM d");
        date.setTextSize(17f);
        date.setTypeface(Typeface.DEFAULT, Typeface.NORMAL);
        date.setTextColor(WHITE);
        date.setSingleLine(true);
        date.setEllipsize(TextUtils.TruncateAt.END);
        date.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        date.setFocusable(false);
        date.setShadowLayer(dp(2), 0, dp(1), 0x99000000);
        clockRow.addView(date, new LinearLayout.LayoutParams(
                0, LayoutParams.MATCH_PARENT, 1f));

        TextClock clock = new TextClock(context);
        clock.setFormat12Hour("h:mm");
        clock.setFormat24Hour("HH:mm");
        clock.setTextSize(34f);
        clock.setTypeface(Typeface.DEFAULT, Typeface.NORMAL);
        clock.setTextColor(WHITE);
        clock.setGravity(Gravity.CENTER_VERTICAL | Gravity.END);
        clock.setSingleLine(true);
        clock.setFocusable(false);
        clock.setShadowLayer(dp(2), 0, dp(1), 0x99000000);
        clockRow.addView(clock, new LinearLayout.LayoutParams(
                LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT));

        View line = new View(context);
        line.setBackgroundColor(RULE);
        line.setFocusable(false);
        addView(line, new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, dp(1)));

        notificationScroll = new HorizontalScrollView(context);
        notificationScroll.setHorizontalScrollBarEnabled(false);
        notificationScroll.setFillViewport(true);
        notificationScroll.setFocusable(false);
        notificationScroll.setFocusableInTouchMode(false);
        notificationScroll.setClickable(false);
        notificationScroll.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        notificationRow = new LinearLayout(context);
        notificationRow.setOrientation(HORIZONTAL);
        notificationRow.setGravity(Gravity.CENTER_VERTICAL | Gravity.CENTER_HORIZONTAL);
        notificationRow.setFocusable(false);
        notificationRow.setFocusableInTouchMode(false);
        notificationScroll.addView(notificationRow, new HorizontalScrollView.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT));
        addView(notificationScroll, new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, dp(31)));
        notificationScroll.setVisibility(GONE);
        clockRow.setVisibility(GONE);
    }

    /**
     * The SystemUI blueprint owns the stock clock/date sections. Show our
     * BB6 clock/date ONLY when the alternate native blueprint has actually
     * been installed and selected; otherwise use indicator-only fallback.
     */
    void setBb6CompositionActive(boolean active) {
        if (!active) {
            // Restores any native background before AOD, shade or bouncer
            // takes over, including if Android doesn't send focus-lost.
            clearFocus();
            clockRow.clearFocus();
            updateNativeStatusBarHighlight(false);
        }
        clockRow.setVisibility(active ? VISIBLE : GONE);
    }

    /**
     * The native KeyguardStatusBarView and our clock row are separate
     * SystemUI siblings. Give them one focus state without moving either
     * view, replacing their input handlers, or making notification icons
     * part of that selection.
     */
    void bindNativeStatusBar(View statusBar) {
        if (nativeStatusBar == statusBar) return;
        updateNativeStatusBarHighlight(false);
        nativeStatusBar = statusBar;
        nativeStatusBarBackground = null;
        if (clockRow.isFocused()) updateNativeStatusBarHighlight(true);
    }

    private void updateNativeStatusBarHighlight(boolean focused) {
        if (nativeStatusBar == null) return;
        if (focused == nativeStatusBarHighlighted) return;
        if (focused) {
            // Only the view background is exchanged. Native carrier/signal/
            // battery children and their system-managed contents remain.
            nativeStatusBarBackground = nativeStatusBar.getBackground();
            nativeStatusBar.setBackground(BB7Theme.selected(dp(5), dp(1)));
            nativeStatusBarHighlighted = true;
        } else {
            if (nativeStatusBarHighlighted) {
                nativeStatusBar.setBackground(nativeStatusBarBackground);
            }
            nativeStatusBarBackground = null;
            nativeStatusBarHighlighted = false;
        }
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
            // Symmetric spacing preserves the actual center of the group.
            indicatorParams.leftMargin = dp(7);
            indicatorParams.rightMargin = dp(7);

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

            TextView number = text(14f, WHITE);
            number.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            number.setGravity(Gravity.CENTER_VERTICAL);
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

    private TextView text(float sp, int color) {
        TextView value = new TextView(getContext());
        value.setTextSize(sp);
        value.setTextColor(color);
        value.setFocusable(false);
        value.setClickable(false);
        value.setIncludeFontPadding(false);
        return value;
    }

    private int dp(float value) {
        return (int) (getResources().getDisplayMetrics().density * value + 0.5f);
    }
}
