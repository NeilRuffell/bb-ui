package dev.q25.bb7pulldown;

import dev.q25.trackpad.theme.BB7Theme;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.widget.TextView;
import android.view.View;
import android.view.MotionEvent;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

/**
 * One system-owned overlay window, one date header and two retained content
 * pages. Page switches change visibility, never WindowManager ownership or
 * the root's translation/entrance animation.
 */
final class UnifiedShadeHost extends FrameLayout {
    static final int NOTIFICATIONS = ShadeStatusNavigation.NOTIFICATIONS;
    static final int QUICK_SETTINGS = ShadeStatusNavigation.QUICK_SETTINGS;

    private final ShadeDateHeader dateHeader;
    private final LinearLayout mediaModeHeader;
    private final TextView mediaShuffle;
    private final TextView mediaRepeat;
    private boolean mediaExpanded;
    private int displayedPage = NOTIFICATIONS;
    private final TwoColumnPanel notifications;
    private final BB6NativeQsPanel connections;
    private final ShadeUiController navigation;
    private final Runnable closeShade;
    private float headerTouchDownY;
    private boolean headerSwipeClosed;

    UnifiedShadeHost(Context context, TwoColumnPanel notifications,
            BB6NativeQsPanel connections, ShadeUiController navigation,
            int initialPage, Runnable closeShade) {
        super(context);
        this.notifications = notifications;
        this.connections = connections;
        this.navigation = navigation;
        this.closeShade = closeShade;

        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        addView(layout, new FrameLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        dateHeader = new ShadeDateHeader(context);
        // Previously the header lived inside each page and inherited its
        // swipe-up dismissal. Preserve that gesture at the shared host level.
        dateHeader.setOnTouchListener((view, event) -> {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    headerTouchDownY = event.getRawY();
                    headerSwipeClosed = false;
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if (!headerSwipeClosed
                            && headerTouchDownY - event.getRawY() >= dp(65)) {
                        headerSwipeClosed = true;
                        closeShade.run();
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    headerSwipeClosed = false;
                    return true;
                default:
                    return true;
            }
        });
        layout.addView(dateHeader, ShadeDateHeader.layoutParams(context));

        // BBOS6 media header replaces the taller date bar only while expanded.
        // This host owns both headers; no stock SystemUI view is modified.
        mediaModeHeader = new LinearLayout(context);
        mediaModeHeader.setOrientation(LinearLayout.HORIZONTAL);
        mediaModeHeader.setGravity(Gravity.CENTER_VERTICAL);
        mediaModeHeader.setPadding(dp(8), 0, dp(8), 0);
        // Neutral, unfocused media surface: reserve BB UI blue for the
        // individually selected shuffle/repeat control, not the whole header.
        mediaModeHeader.setBackground(new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                BB7Theme.palette(context) == BB7Theme.Palette.DARK
                        ? new int[] {0xff2f4053, 0xff1e2c3b}
                        : new int[] {0xffecf1f7, 0xffd8e2ec}));
        mediaModeHeader.setVisibility(View.GONE);
        layout.addView(mediaModeHeader, new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, dp(32)));

        TextView heading = new TextView(context);
        heading.setText("Media");
        heading.setTextColor(BB7Theme.palette(context).headingText);
        heading.setTextSize(12);
        heading.setGravity(Gravity.CENTER_VERTICAL);
        mediaModeHeader.addView(heading, new LinearLayout.LayoutParams(
                0, LayoutParams.MATCH_PARENT, 1f));

        mediaShuffle = modeButton(context);
        mediaShuffle.setOnClickListener(view -> notifications.activateMediaHeaderMode(
                MediaModePolicy.SHUFFLE));
        mediaModeHeader.addView(mediaShuffle, new LinearLayout.LayoutParams(
                LayoutParams.WRAP_CONTENT, dp(28)));

        mediaRepeat = modeButton(context);
        mediaRepeat.setOnClickListener(view -> notifications.activateMediaHeaderMode(
                MediaModePolicy.REPEAT));
        mediaModeHeader.addView(mediaRepeat, new LinearLayout.LayoutParams(
                LayoutParams.WRAP_CONTENT, dp(28)));

        FrameLayout pages = new FrameLayout(context);
        layout.addView(pages, new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, 0, 1f));
        pages.addView(notifications, new FrameLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        pages.addView(connections, new FrameLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        // Media focus remains in TwoColumnPanel; these header items are passive
        // to hardware focus, but can be activated directly by touch.
        notifications.setMediaHeaderListener(this::onMediaHeader);
        // Do not request focus until the one-time entrance animation finishes.
        setPage(initialPage);
    }

    void setPage(int next) {
        if (next != NOTIFICATIONS && next != QUICK_SETTINGS) {
            throw new IllegalArgumentException("Unknown shade page");
        }
        displayedPage = next;
        notifications.setVisibility(next == NOTIFICATIONS ? View.VISIBLE : View.GONE);
        connections.setVisibility(next == QUICK_SETTINGS ? View.VISIBLE : View.GONE);
        connections.setActive(next == QUICK_SETTINGS);
        updateHeaderVisibility();
    }

    private static TextView modeButton(Context context) {
        TextView button = new TextView(context);
        button.setTextSize(12);
        button.setTextColor(BB7Theme.palette(context).headingText);
        button.setGravity(Gravity.CENTER);
        button.setPadding(12, 0, 12, 0);
        button.setFocusable(false);
        button.setClickable(true);
        return button;
    }

    private void onMediaHeader(boolean expanded, NativeMediaSessions.Entry entry,
            boolean modeSelected, int selectedMode) {
        mediaExpanded = expanded;
        boolean shuffle = entry != null && entry.canShuffle;
        boolean repeat = entry != null && entry.canRepeat;
        mediaShuffle.setVisibility(shuffle ? View.VISIBLE : View.GONE);
        mediaRepeat.setVisibility(repeat ? View.VISIBLE : View.GONE);
        if (shuffle) {
            boolean enabled = entry.shuffleMode != MediaModePolicy.SHUFFLE_NONE;
            mediaShuffle.setText(enabled ? "Shuffle On" : "Shuffle");
            mediaShuffle.setContentDescription(enabled ? "Shuffle on" : "Shuffle off");
            styleMode(mediaShuffle, enabled, modeSelected
                    && selectedMode == MediaModePolicy.SHUFFLE);
        }
        if (repeat) {
            String label = entry.repeatMode == MediaModePolicy.REPEAT_ONE ? "Repeat 1"
                    : entry.repeatMode == MediaModePolicy.REPEAT_ALL ? "Repeat All"
                    : "Repeat";
            mediaRepeat.setText(label);
            mediaRepeat.setContentDescription(label);
            styleMode(mediaRepeat,
                    entry.repeatMode != MediaModePolicy.REPEAT_NONE,
                    modeSelected && selectedMode == MediaModePolicy.REPEAT);
        }
        updateHeaderVisibility();
    }

    private void styleMode(TextView view, boolean enabled, boolean selected) {
        if (selected) {
            GradientDrawable selectedBackground = new GradientDrawable();
            selectedBackground.setColor(0xff153a65);
            selectedBackground.setCornerRadius(dp(4));
            selectedBackground.setStroke(dp(1), BB7Theme.BLUE_BORDER);
            view.setBackground(selectedBackground);
        } else if (enabled) {
            GradientDrawable active = new GradientDrawable();
            active.setColor(0x663c5369);
            active.setCornerRadius(dp(4));
            view.setBackground(active);
        } else {
            view.setBackground(null);
        }
        view.setAlpha(enabled || selected ? 1f : 0.78f);
    }

    private void updateHeaderVisibility() {
        boolean showMedia = mediaExpanded && displayedPage == NOTIFICATIONS;
        dateHeader.setVisibility(showMedia ? View.GONE : View.VISIBLE);
        mediaModeHeader.setVisibility(showMedia ? View.VISIBLE : View.GONE);
    }

    int page() {
        return navigation.activePage();
    }

    void focusContent() {
        navigation.focusContent();
        if (navigation.activePage() == QUICK_SETTINGS) {
            connections.focusFirst();
        } else {
            notifications.focusSelectedAppIcon();
        }
    }

    void clearStatusBarFocus() {
        navigation.focusContent();
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }
}
