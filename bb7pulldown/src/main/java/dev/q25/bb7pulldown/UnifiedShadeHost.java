package dev.q25.bb7pulldown;

import dev.q25.trackpad.theme.BB7Theme;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.Drawable;
import android.view.Gravity;
import android.widget.TextView;
import android.view.View;
import android.view.MotionEvent;
import android.view.KeyEvent;
import android.view.animation.DecelerateInterpolator;
import java.util.function.IntConsumer;
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
    private final View headerPlaceholder;
    private final boolean externalHeader;
    private final java.util.function.Consumer<Boolean> onExternalHeaderVisibility;
    private final FrameLayout contentViewport;
    private ValueAnimator contentReveal;
    private boolean selectorRevealPrepared;
    private boolean selectorHandoffActive;
    private final LinearLayout mediaModeHeader;
    private final TextView mediaShuffle;
    private final TextView mediaRepeat;
    private boolean mediaExpanded;
    private int displayedPage = NOTIFICATIONS;
    private final TwoColumnPanel notifications;
    private final BB6NativeQsPanel connections;
    private final ShadeUiController navigation;
    private final Runnable closeShade;
    private final IntConsumer onHeaderPageSelected;
    private float headerTouchDownY;
    private float headerTouchDownX;
    private boolean headerTouchMoved;
    private boolean headerSwipeClosed;

    UnifiedShadeHost(Context context, TwoColumnPanel notifications,
            BB6NativeQsPanel connections, ShadeUiController navigation,
            int initialPage, Runnable closeShade, IntConsumer onHeaderPageSelected,
            ShadeDateHeader existingSelectorHeader,
            java.util.function.Consumer<Boolean> onExternalHeaderVisibility) {
        super(context);
        this.notifications = notifications;
        this.connections = connections;
        this.navigation = navigation;
        this.closeShade = closeShade;
        this.onHeaderPageSelected = onHeaderPageSelected;
        this.externalHeader = existingSelectorHeader != null;
        // Enter already selected this exact visible header. The constructor
        // must NOT clear its highlight while building the shade's content.
        selectorHandoffActive = externalHeader;
        this.onExternalHeaderVisibility = onExternalHeaderVisibility;

        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        addView(layout, new FrameLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        // The compact selector keeps the ORIGINAL header in its ORIGINAL
        // WindowManager window throughout the expanded shade lifetime.
        // The full shade reserves matching geometry rather than reparenting,
        // removing, recreating or redrawing that visible view.
        dateHeader = existingSelectorHeader != null
                ? existingSelectorHeader : new ShadeDateHeader(context);
        headerPlaceholder = externalHeader ? new View(context) : null;
        // Normal gesture-open shade owns its own header touch routing. The
        // already-mounted selector retains its existing touch handler.
        if (!externalHeader) dateHeader.setOnTouchListener((view, event) -> {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    headerTouchDownY = event.getRawY();
                    headerTouchDownX = event.getRawX();
                    headerSwipeClosed = false;
                    headerTouchMoved = false;
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if (Math.abs(event.getRawY() - headerTouchDownY) >= dp(12)
                            || Math.abs(event.getRawX() - headerTouchDownX) >= dp(12)) {
                        headerTouchMoved = true;
                    }
                    if (!headerSwipeClosed
                            && headerTouchDownY - event.getRawY() >= dp(65)) {
                        headerSwipeClosed = true;
                        closeShade.run();
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                    if (!headerSwipeClosed && !headerTouchMoved) {
                        int zone = ShadeHeaderSegmentPolicy.zoneAt(event.getX(), view.getWidth());
                        if (zone != ShadeStatusNavigation.NONE) onHeaderPageSelected.accept(zone);
                    }
                    headerSwipeClosed = false;
                    headerTouchMoved = false;
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    headerSwipeClosed = false;
                    headerTouchMoved = false;
                    return true;
                default:
                    return true;
            }
        });
        layout.addView(externalHeader ? headerPlaceholder : dateHeader,
                ShadeDateHeader.layoutParams(context));

        // BBOS6 media header replaces the taller date bar only while expanded.
        // This host owns both headers; no stock SystemUI view is modified.
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

        // Only the content viewport moves/reveals on selector confirmation.
        // The header sits above it as an unaffected sibling.
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

    /**
     * Selection from the compact header uses the same header instance.
     * Reveal just the area below it, leaving its position fixed throughout.
     * Ordinary status-bar touch swipes continue using the native window slide.
     */
    void beginSelectorHandoff(int selectedPage) {
        selectorHandoffActive = true;
        dateHeader.setFocusedZone(selectedPage);
        updateHeaderVisibility();
    }

    void completeSelectorHandoff() {
        selectorHandoffActive = false;
        dateHeader.setFocusedZone(navigation.focusedZone());
        updateHeaderVisibility();
    }

    void prepareSelectorReveal() {
        selectorRevealPrepared = true;
        contentViewport.setClipBounds(new Rect(0, 0, 0, 0));
        contentViewport.setImportantForAccessibility(
                View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
    }

    void animateSelectorReveal(Runnable completed) {
        if (!selectorRevealPrepared) {
            completed.run();
            return;
        }
        contentViewport.post(() -> {
            if (!selectorRevealPrepared || !isAttachedToWindow()) return;
            int width = contentViewport.getWidth();
            int height = contentViewport.getHeight();
            if (width <= 0 || height <= 0) {
                finishSelectorReveal();
                completed.run();
                return;
            }
            ValueAnimator animation = ValueAnimator.ofInt(0, height);
            contentReveal = animation;
            animation.setDuration(220);
            animation.setInterpolator(new DecelerateInterpolator(1.4f));
            animation.addUpdateListener(frame -> contentViewport.setClipBounds(
                    new Rect(0, 0, width, (Integer) frame.getAnimatedValue())));
            animation.addListener(new AnimatorListenerAdapter() {
                @Override public void onAnimationEnd(Animator finished) {
                    if (contentReveal != finished) return;
                    finishSelectorReveal();
                    completed.run();
                }
            });
            animation.start();
        });
    }

    void finishSelectorReveal() {
        selectorRevealPrepared = false;
        ValueAnimator animation = contentReveal;
        contentReveal = null;
        if (animation != null) animation.cancel();
        contentViewport.setClipBounds(null);
        contentViewport.setImportantForAccessibility(
                View.IMPORTANT_FOR_ACCESSIBILITY_AUTO);
        updateHeaderVisibility();
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
        updateHeaderFocus(navigation.focusedZone());
    }

    void updateHeaderFocus(int zone) {
        if (!selectorHandoffActive) dateHeader.setFocusedZone(zone);
        updateHeaderVisibility();
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
        // Show navigation segments when selected even if the compact media
        // header normally replaces them. Restore it on focus return.
        boolean showMedia = mediaExpanded && displayedPage == NOTIFICATIONS
                && !navigation.isStatusBarFocused()
                && !selectorRevealPrepared && !selectorHandoffActive;
        if (externalHeader) {
            headerPlaceholder.setVisibility(showMedia ? View.GONE : View.VISIBLE);
            dateHeader.setVisibility(showMedia ? View.GONE : View.VISIBLE);
            if (onExternalHeaderVisibility != null) {
                onExternalHeaderVisibility.accept(!showMedia);
            }
        } else {
            dateHeader.setVisibility(showMedia ? View.GONE : View.VISIBLE);
        }
        mediaModeHeader.setVisibility(showMedia ? View.VISIBLE : View.GONE);
    }

    int externalHeaderHeight() {
        return externalHeader
                ? ShadeDateHeader.layoutParams(getContext()).height : 0;
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
