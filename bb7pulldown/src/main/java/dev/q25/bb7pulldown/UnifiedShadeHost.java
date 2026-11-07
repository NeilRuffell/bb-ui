package dev.q25.bb7pulldown;

import dev.q25.trackpad.theme.BB7Theme;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.Drawable;
import android.view.Gravity;
import android.widget.TextView;
import android.view.View;
import android.view.KeyEvent;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

/**
 * One SystemUI-owned shade host with two retained pages and optional media
 * header. The BB-button popup lives in its own transient window.
 */
final class UnifiedShadeHost extends FrameLayout {
    static final int NOTIFICATIONS = ShadeStatusNavigation.NOTIFICATIONS;
    static final int QUICK_SETTINGS = ShadeStatusNavigation.QUICK_SETTINGS;

    private final FrameLayout contentViewport;
    private final LinearLayout mediaModeHeader;
    private final TextView mediaShuffle;
    private final TextView mediaRepeat;
    private boolean mediaExpanded;
    private int displayedPage = NOTIFICATIONS;
    private final TwoColumnPanel notifications;
    private final BB6NativeQsPanel connections;
    private final ShadeUiController navigation;
    private final Runnable closeShade;
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

        // No full-width navigation bar or selector placeholder.
        // The notification/QS content begins below the native status bar.
        mediaModeHeader = new LinearLayout(context);
        mediaModeHeader.setOrientation(LinearLayout.HORIZONTAL);
        mediaModeHeader.setGravity(Gravity.CENTER_VERTICAL);
        mediaModeHeader.setPadding(dp(8), 0, dp(8), 0);
        // Share the seekbar's dark vertical fill. Keep the individual
        // shuffle/repeat selection styling and the 1dp silver top rule.
        GradientDrawable mediaModeFill = MediaChromeDivider.darkMediaBackground();
        // Same silver rule as the bottom of the seekbar area, placed above
        // this header without changing its height or child layout.
        LayerDrawable mediaModeChrome = new LayerDrawable(new Drawable[] {
                mediaModeFill, MediaChromeDivider.silverLine(),
                MediaChromeDivider.darkRule()
        });
        mediaModeChrome.setLayerHeight(1, dp(MediaChromeDivider.HEIGHT_DP));
        mediaModeChrome.setLayerGravity(1, Gravity.TOP | Gravity.FILL_HORIZONTAL);
        mediaModeChrome.setLayerHeight(2, dp(MediaChromeDivider.DARK_RULE_HEIGHT_DP));
        mediaModeChrome.setLayerGravity(2, Gravity.BOTTOM | Gravity.FILL_HORIZONTAL);
        mediaModeHeader.setBackground(mediaModeChrome);
        mediaModeHeader.setVisibility(View.GONE);
        // BBOS6 shuffle/repeat header is taller than the compact seek strip.
        layout.addView(mediaModeHeader, new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, dp(40)));

        TextView heading = new TextView(context);
        heading.setText("Media");
        heading.setTextColor(BB7Theme.Palette.DARK.headingText);
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

        // Both pages remain mounted while the BB popup is transient.
        contentViewport = new FrameLayout(context);
        contentViewport.setClipChildren(true);
        contentViewport.setClipToPadding(true);
        layout.addView(contentViewport, new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, 0, 1f));
        FrameLayout pages = new FrameLayout(context);
        contentViewport.addView(pages, new FrameLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
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
        updateMediaHeaderVisibility();
    }

    private static TextView modeButton(Context context) {
        TextView button = new TextView(context);
        button.setTextSize(12);
        button.setTextColor(BB7Theme.Palette.DARK.headingText);
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
        updateMediaHeaderVisibility();
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

    private void updateMediaHeaderVisibility() {
        // An independent popup never changes the media panel's geometry.
        boolean showMedia = mediaExpanded && displayedPage == NOTIFICATIONS;
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

    /**
     * Native OnBackInvokedCallback arrives at SystemUI, not the focused BB
     * child view. Route it through the SAME BB navigation levels as hardware
     * Back rather than invoking stock shade animations or synthesizing events.
     */
    boolean handleNativeBack() {
        int route = NativeShadeBackRoute.route(navigation.isStatusBarFocused(),
                displayedPage);
        if (route == NativeShadeBackRoute.STATUS_BAR) {
            return navigation.handleKey(KeyEvent.KEYCODE_BACK, KeyEvent.ACTION_UP);
        }
        if (route == NativeShadeBackRoute.QUICK_SETTINGS) {
            closeShade.run();
            return true;
        }
        notifications.handleNativeBack();
        return true;
    }

    void clearStatusBarFocus() {
        navigation.focusContent();
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }
}
