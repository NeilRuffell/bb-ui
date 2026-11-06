package dev.q25.bb7pulldown;

import dev.q25.trackpad.theme.BB7Theme;
import dev.q25.trackpad.input.TrackpadPressHandler;

import android.content.Context;
import android.os.SystemClock;
import android.text.InputType;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.os.Bundle;
import android.graphics.drawable.ClipDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.media.session.MediaSession;
import android.text.TextUtils;
import android.text.format.DateFormat;
import android.text.format.DateUtils;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewConfiguration;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.ArrayAdapter;
import android.widget.FrameLayout;
import android.widget.ListPopupWindow;
import android.widget.PopupWindow;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Independent 15/85 notification panel. The left side is a selectable
 * presentation-only list; the right side displays selected-app summaries.
 * Android owns notification lifecycle and keyguard privacy as before.
 */
final class TwoColumnPanel extends FrameLayout {
    interface CloseListener { void onClose(); }
    interface OpenNotificationListener { void onOpen(String notificationKey); }
    interface MediaControlListener {
        boolean onControl(MediaSession.Token token, int action);
        boolean onSeek(MediaSession.Token token, long positionMs);
        boolean onSetMode(MediaSession.Token token, int kind, int value);
    }
    interface MediaHeaderListener {
        void onMediaHeader(boolean expanded, NativeMediaSessions.Entry session,
                boolean modeSelected, int selectedMode);
    }
    interface NotificationActionListener {
        void onAction(String notificationKey, int actionIndex);
        void onDismiss(String notificationKey);
        boolean onReply(String notificationKey, int actionIndex, String reply);
    }

    private static final String TAG = "BB7-PullDown";
    // Solid unplayed upper/lower halves of the BBOS6 seek rail; no gradient.
    private static final int MEDIA_SEEK_TRACK_TOP = 0xff101010;
    private static final int MEDIA_SEEK_TRACK_BOTTOM = 0xff393839;
    private final BB7Theme.Palette palette;
    private final CloseListener onClose;
    private final ShadeUiController navigation;
    private final OpenNotificationListener onOpenNotification;
    private final NotificationActionListener onActions;
    private final MediaControlListener onMediaControl;
    private MediaHeaderListener onMediaHeader;
    private final LinearLayout mediaRow;
    private final LinearLayout mediaContainer;
    private final LinearLayout mediaColumns;
    private final FrameLayout mediaArtworkFrame;
    private final ImageView mediaArtworkImage;
    private final TextView mediaArtworkPlaceholder;
    private final TextView expandedMediaTitle;
    private final TextView expandedMediaArtist;
    private final MediaArtworkLoader mediaArtworkLoader = new MediaArtworkLoader();
    private MediaSession.Token shownArtworkToken;
    private String shownArtworkTitle;
    private String shownArtworkArtist;
    private String shownArtworkUri;
    private Bitmap shownArtworkBitmap;
    private final LinearLayout mediaSeekRow;
    private final SeekBar mediaSeekBar;
    private final TextView mediaElapsed;
    private final TextView mediaDuration;
    private final ImageView mediaAppIcon;
    private final ImageView[] mediaButtons = new ImageView[3];
    private final TextView mediaNowPlaying;
    private final MediaModeStatusIcon mediaShuffleStatus;
    private final MediaModeStatusIcon mediaRepeatStatus;
    private final TextView mediaIndex;
    private List<NativeMediaSessions.Entry> mediaSessions = Collections.emptyList();
    private MediaSession.Token selectedMediaToken;
    private boolean mediaControlsOpen;
    private int selectedMediaButton = NativeMediaSessions.PLAY_PAUSE;
    private boolean mediaSeekSelected;
    // Separate highlighted position from active trackpad seek-edit mode.
    private boolean mediaSeekEditing;
    private boolean mediaModeSelected;
    private int selectedMediaMode = MediaModePolicy.SHUFFLE;
    private boolean mediaSeekDragging;
    private MediaSession.Token mediaDragToken;
    private MediaSession.Token mediaPendingToken;
    private long mediaPendingPositionMs;
    private long mediaPendingUntilMs;
    private final Runnable mediaProgressTick = new Runnable() {
        @Override public void run() {
            if (!mediaControlsOpen || !isAttachedToWindow()
                    || mediaRow.getVisibility() != View.VISIBLE) return;
            renderMediaProgress();
            postDelayed(this, 1000L);
        }
    };
    private final List<LinearLayout> notificationRows = new ArrayList<>();
    // Only notification cards are trackpad stops; actions live in a stock popup.
    private final List<View> notificationTargets = new ArrayList<>();
    private final List<String> notificationTargetKeys = new ArrayList<>();
    private final List<Integer> notificationTargetActions = new ArrayList<>();
    private ListPopupWindow notificationActionPopup;
    private static final int FOCUS_CARD = -1;
    private String selectedNotificationKey;
    private int selectedActionIndex = FOCUS_CARD;
    private EditText activeReplyInput;
    private View activeReplyAnchor;
    private boolean notificationPaneActive;
    private final LinearLayout iconList;
    private final LinearLayout notificationList;
    private final ScrollView notificationScroller;
    private final TextView selectedAppHeading;
    private final List<FrameLayout> iconRows = new ArrayList<>();
    private final List<ImageView> iconImages = new ArrayList<>();
    private final List<TextView> iconCounts = new ArrayList<>();
    private final List<CharSequence> iconLabels = new ArrayList<>();
    private List<String> renderedPackages = Collections.emptyList();
    private List<NotificationItem> notifications = Collections.emptyList();
    private List<NotificationItem> visibleNotifications = Collections.emptyList();
    private String organizationMode = NotificationShadeModePolicy.APPS;
    private FavoritesRules favorites = new FavoritesRules();
    private List<NotificationItem> renderedCards = Collections.emptyList();
    private String renderedCardPackage;
    private String selectedPackage;
    private float touchStartY;
    private boolean trackingClose;

    TwoColumnPanel(Context context, CloseListener close,
            OpenNotificationListener openNotification, NotificationActionListener actions,
            ShadeUiController navigation, MediaControlListener mediaControl) {
        super(context);
        palette = BB7Theme.palette(context);
        Log.i(TAG, "BB7 shade palette: " + (palette == BB7Theme.Palette.DARK ? "dark" : "light"));
        onClose = close;
        onOpenNotification = openNotification;
        onActions = actions;
        this.navigation = navigation;
        onMediaControl = mediaControl;
        setFocusable(true);
        setFocusableInTouchMode(true);
        setBackgroundColor(palette.canvas);
        setContentDescription("BB7 two-column pull-down");

        // The same date-only header is used by the Quick Settings shade.
        LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        addView(content, new FrameLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        // Shared date bar belongs to UnifiedShadeHost, above both pages.

        // One focusable row for all native media sessions, hidden when none exist.
        mediaRow = new LinearLayout(context);
        mediaRow.setOrientation(LinearLayout.HORIZONTAL);
        mediaRow.setGravity(Gravity.CENTER_VERTICAL);
        mediaRow.setPadding(dp(9), dp(3), dp(9), dp(3));
        mediaRow.setBackgroundColor(palette.heading);
        mediaRow.setVisibility(View.GONE);
        mediaRow.setFocusable(true);
        mediaRow.setFocusableInTouchMode(true);
        mediaRow.setClickable(true);
        mediaRow.setOnKeyListener((view, code, event) -> onMediaKey(code, event));
        mediaRow.setOnFocusChangeListener((view, focused) -> {
            if (focused) leaveStatusZone();
            else setMediaControlsOpen(false);
            updateMediaHighlight();
        });
        mediaRow.setOnClickListener(view -> {
            if (!mediaControlsOpen) {
                selectedMediaButton = NativeMediaSessions.PLAY_PAUSE;
                mediaRow.requestFocus();
                setMediaControlsOpen(true);
            } else if (mediaSeekSelected) {
                toggleMediaSeekEditing();
            } else {
                mediaSeekEditing = false;
                mediaSeekSelected = false;
                mediaModeSelected = false;
                performMediaAction();
                updateMediaHighlight();
                notifyMediaHeader();
            }
        });

        mediaAppIcon = new ImageView(context);
        mediaAppIcon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        mediaAppIcon.setFocusable(false);
        mediaRow.addView(mediaAppIcon, new LinearLayout.LayoutParams(dp(29), dp(29)));

        mediaNowPlaying = text("", 12, palette.title);
        mediaNowPlaying.setSingleLine(true);
        // Android's native TextView marquee scrolls only when the track info overflows.
        mediaNowPlaying.setEllipsize(TextUtils.TruncateAt.MARQUEE);
        mediaNowPlaying.setMarqueeRepeatLimit(-1);
        mediaNowPlaying.setSelected(true);
        mediaNowPlaying.setGravity(Gravity.CENTER_VERTICAL);
        mediaNowPlaying.setPadding(dp(7), 0, dp(3), 0);
        mediaNowPlaying.setFocusable(false);
        mediaRow.addView(mediaNowPlaying, new LinearLayout.LayoutParams(
                0, LayoutParams.MATCH_PARENT, 1f));

        // Passive native-mode indicators occupy the space previously used by
        // the collapsed transport buttons; absent/off modes take no space.
        mediaShuffleStatus = new MediaModeStatusIcon(context, MediaModeStatusIcon.SHUFFLE);
        LinearLayout.LayoutParams shuffleStatusLayout = new LinearLayout.LayoutParams(
                dp(28), dp(28));
        shuffleStatusLayout.rightMargin = dp(3);
        mediaRow.addView(mediaShuffleStatus, shuffleStatusLayout);

        mediaRepeatStatus = new MediaModeStatusIcon(context, MediaModeStatusIcon.REPEAT);
        LinearLayout.LayoutParams repeatStatusLayout = new LinearLayout.LayoutParams(
                dp(28), dp(28));
        repeatStatusLayout.rightMargin = dp(3);
        mediaRow.addView(mediaRepeatStatus, repeatStatusLayout);

        final int[] icons = {
                android.R.drawable.ic_media_previous,
                android.R.drawable.ic_media_play,
                android.R.drawable.ic_media_next
        };
        final String[] descriptions = {"Previous", "Play or pause", "Next"};
        for (int i = 0; i < mediaButtons.length; i++) {
            final int action = i;
            ImageView button = new ImageView(context);
            button.setImageResource(icons[i]);
            button.setScaleType(ImageView.ScaleType.FIT_CENTER);
            button.setPadding(dp(9), dp(9), dp(9), dp(9));
            button.setFocusable(false); // Trackpad focus remains on the parent row.
            // Transport buttons belong exclusively to the expanded media view.
            // Initially collapsed: these must be hidden even before first open.
            button.setVisibility(View.GONE);
            button.setClickable(true);
            button.setContentDescription(descriptions[i]);
            button.setOnClickListener(view -> {
                selectedMediaButton = action;
                mediaRow.requestFocus();
                setMediaControlsOpen(true);
                mediaSeekEditing = false;
                mediaSeekSelected = false;
                mediaModeSelected = false;
                performMediaAction();
                updateMediaHighlight();
                notifyMediaHeader();
            });
            mediaButtons[i] = button;
            mediaRow.addView(button, new LinearLayout.LayoutParams(dp(39), dp(39)));
        }

        mediaIndex = text("", 11, palette.muted);
        mediaIndex.setGravity(Gravity.CENTER);
        mediaIndex.setFocusable(false);
        mediaRow.addView(mediaIndex, new LinearLayout.LayoutParams(
                LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT));
        mediaContainer = new LinearLayout(context);
        mediaContainer.setOrientation(LinearLayout.VERTICAL);
        content.addView(mediaContainer, new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
        mediaContainer.addView(mediaRow, new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, dp(46)));

        // Expanded-only seek row sits immediately above the transport controls.
        mediaSeekRow = new LinearLayout(context);
        mediaSeekRow.setOrientation(LinearLayout.HORIZONTAL);
        mediaSeekRow.setGravity(Gravity.CENTER_VERTICAL);
        mediaSeekRow.setPadding(dp(10), 0, dp(10), 0);
        mediaSeekRow.setBackground(mediaSeekBackground());
        mediaSeekRow.setVisibility(View.GONE);

        mediaElapsed = text("0:00", 14, palette.muted);
        mediaElapsed.setGravity(Gravity.CENTER_VERTICAL);
        mediaSeekRow.addView(mediaElapsed, new LinearLayout.LayoutParams(
                dp(45), LayoutParams.MATCH_PARENT));

        mediaSeekBar = new SeekBar(context);
        mediaSeekBar.setMax(MediaSeekPolicy.PROGRESS_MAX);
        // Parent row owns trackpad focus; SeekBar owns direct touch dragging.
        mediaSeekBar.setFocusable(false);
        mediaSeekBar.setFocusableInTouchMode(false);
        // The selected 56dp pill extends 28dp beyond its progress position.
        // Reserve that width inside SeekBar at both endpoints so Android does
        // not clip it against the separate elapsed/total-time views.
        // This also keeps the touch-to-seek range aligned with the visible track.
        mediaSeekBar.setPadding(dp(32), 0, dp(32), 0);
        // Flat white played portion over the existing two-tone unplayed rail.
        // Use native progress levels so the played segment tracks the thumb.
        mediaSeekBar.setProgressTintList(null);
        mediaSeekBar.setProgressBackgroundTintList(null);
        mediaSeekBar.setSecondaryProgressTintList(null);
        mediaSeekBar.setProgressDrawable(mediaSeekTrackDrawable());
        // Preserve the silver pill's own fill and border rather than applying
        // the current Android theme's SeekBar thumb tint.
        mediaSeekBar.setThumbTintList(null);
        mediaSeekBar.setThumb(mediaSeekThumb(false));
        mediaSeekBar.setContentDescription("Playback position");
        mediaSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onStartTrackingTouch(SeekBar seekBar) {
                mediaSeekDragging = true;
                mediaDragToken = selectedMediaToken;
                // Touch dragging bypasses keyboard edit mode.
                mediaSeekEditing = false;
                mediaSeekSelected = true;
                mediaModeSelected = false;
                mediaRow.requestFocus();
                updateMediaHighlight();
                notifyMediaHeader();
            }

            @Override public void onProgressChanged(SeekBar seekBar, int progress,
                    boolean fromUser) {
                if (!fromUser || !mediaSeekDragging) return;
                int index = mediaSessionIndex();
                if (index < 0 || !Objects.equals(mediaDragToken, selectedMediaToken)) return;
                long durationMs = mediaSessions.get(index).durationMs;
                mediaElapsed.setText(MediaSeekPolicy.formatTime(
                        MediaSeekPolicy.fromProgress(progress, durationMs)));
            }

            @Override public void onStopTrackingTouch(SeekBar seekBar) {
                mediaSeekDragging = false;
                int index = mediaSessionIndex();
                if (index >= 0 && Objects.equals(mediaDragToken, selectedMediaToken)) {
                    NativeMediaSessions.Entry entry = mediaSessions.get(index);
                    seekMedia(entry, MediaSeekPolicy.fromProgress(
                            seekBar.getProgress(), entry.durationMs));
                }
                mediaDragToken = null;
                renderMediaProgress();
            }
        });
        mediaSeekRow.addView(mediaSeekBar, new LinearLayout.LayoutParams(
                0, dp(48), 1f));

        mediaDuration = text("--:--", 14, palette.muted);
        mediaDuration.setGravity(Gravity.CENTER_VERTICAL | Gravity.RIGHT);
        mediaSeekRow.addView(mediaDuration, new LinearLayout.LayoutParams(
                dp(52), LayoutParams.MATCH_PARENT));
        // Insert ahead of the transport row; artwork is prepended afterwards.
        // Final expanded order: artwork, seekbar, transport controls.
        mediaContainer.addView(mediaSeekRow, 0, new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, dp(48)));

        // Expanded-only artwork and metadata: artwork on the left, track info
        // centered vertically on the right. The collapsed 46dp row is unchanged.
        mediaArtworkFrame = new FrameLayout(context);
        mediaArtworkFrame.setBackground(new HalfRadialGradientDrawable());
        mediaArtworkFrame.setFocusable(false);
        mediaArtworkFrame.setVisibility(View.GONE);

        LinearLayout artworkInfo = new LinearLayout(context);
        artworkInfo.setOrientation(LinearLayout.HORIZONTAL);
        artworkInfo.setGravity(Gravity.CENTER_VERTICAL);
        artworkInfo.setPadding(dp(10), dp(8), dp(10), dp(8));
        artworkInfo.setFocusable(false);
        mediaArtworkFrame.addView(artworkInfo, new FrameLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        FrameLayout artworkArea = new FrameLayout(context);
        artworkArea.setFocusable(false);
        artworkInfo.addView(artworkArea, new LinearLayout.LayoutParams(
                0, LayoutParams.MATCH_PARENT, 1f));

        mediaArtworkImage = new ImageView(context);
        mediaArtworkImage.setScaleType(ImageView.ScaleType.FIT_CENTER);
        mediaArtworkImage.setPadding(dp(6), dp(6), dp(6), dp(6));
        mediaArtworkImage.setFocusable(false);
        mediaArtworkImage.setContentDescription("Album artwork");
        artworkArea.addView(mediaArtworkImage, new FrameLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        mediaArtworkPlaceholder = text("Artwork unavailable", 13, palette.muted);
        mediaArtworkPlaceholder.setGravity(Gravity.CENTER);
        mediaArtworkPlaceholder.setFocusable(false);
        artworkArea.addView(mediaArtworkPlaceholder, new FrameLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        LinearLayout metadataArea = new LinearLayout(context);
        metadataArea.setOrientation(LinearLayout.VERTICAL);
        metadataArea.setGravity(Gravity.CENTER_VERTICAL);
        metadataArea.setPadding(dp(14), dp(8), dp(8), dp(8));
        metadataArea.setFocusable(false);
        artworkInfo.addView(metadataArea, new LinearLayout.LayoutParams(
                0, LayoutParams.MATCH_PARENT, 1f));

        expandedMediaTitle = text("", 18, palette.title);
        expandedMediaTitle.setMaxLines(3);
        expandedMediaTitle.setEllipsize(TextUtils.TruncateAt.END);
        expandedMediaTitle.setFocusable(false);
        metadataArea.addView(expandedMediaTitle, new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

        expandedMediaArtist = text("", 13, palette.muted);
        expandedMediaArtist.setMaxLines(3);
        expandedMediaArtist.setEllipsize(TextUtils.TruncateAt.END);
        expandedMediaArtist.setPadding(0, dp(8), 0, 0);
        expandedMediaArtist.setFocusable(false);
        metadataArea.addView(expandedMediaArtist, new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

        // Artwork/info consumes the remaining height above the seek bar
        // and the dedicated full-width transport controls.
        mediaContainer.addView(mediaArtworkFrame, 0, new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, 0, 1f));

        mediaColumns = new LinearLayout(context);
        mediaColumns.setOrientation(LinearLayout.HORIZONTAL);
        mediaColumns.setWeightSum(PanelRatio.TOTAL_WEIGHT);
        content.addView(mediaColumns, new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, 0, 1f));

        FrameLayout left = new FrameLayout(context);
        left.setBackgroundColor(palette.left);
        mediaColumns.addView(left, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.MATCH_PARENT, PanelRatio.LEFT_WEIGHT));

        ScrollView iconScroller = new ScrollView(context);
        iconScroller.setFillViewport(true);
        iconScroller.setVerticalScrollBarEnabled(false);
        iconScroller.setClipToPadding(false);
        iconScroller.setPadding(0, dp(10), 0, dp(10));
        addSwipeUpDismiss(iconScroller);
        left.addView(iconScroller, new FrameLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        iconList = new LinearLayout(context);
        iconList.setOrientation(LinearLayout.VERTICAL);
        iconList.setGravity(Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        iconScroller.addView(iconList, new ScrollView.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

        View divider = new View(context);
        divider.setBackgroundColor(palette.divider);
        left.addView(divider, new FrameLayout.LayoutParams(
                dp(1), LayoutParams.MATCH_PARENT, Gravity.END));

        FrameLayout right = new FrameLayout(context);
        right.setBackgroundColor(palette.right);
        mediaColumns.addView(right, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.MATCH_PARENT, PanelRatio.RIGHT_WEIGHT));

        LinearLayout rightContent = new LinearLayout(context);
        rightContent.setOrientation(LinearLayout.VERTICAL);
        right.addView(rightContent, new FrameLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        selectedAppHeading = new TextView(context);
        selectedAppHeading.setTextColor(palette.headingText);
        selectedAppHeading.setTextSize(14);
        selectedAppHeading.setGravity(Gravity.CENTER_VERTICAL);
        selectedAppHeading.setPadding(dp(12), dp(7), dp(12), dp(7));
        selectedAppHeading.setBackgroundColor(palette.heading);
        rightContent.addView(selectedAppHeading, new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, dp(44)));

        notificationScroller = new ScrollView(context);
        notificationScroller.setFillViewport(true);
        notificationScroller.setClipToPadding(false);
        notificationScroller.setPadding(0, dp(4), 0, dp(4));
        addSwipeUpDismiss(notificationScroller);
        rightContent.addView(notificationScroller, new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, 0, 1f));

        notificationList = new LinearLayout(context);
        notificationList.setOrientation(LinearLayout.VERTICAL);
        notificationScroller.addView(notificationList, new ScrollView.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
    }

    /** Media sessions are supplied by Android's MediaSessionManager (not action text). */
    void setMediaSessions(List<NativeMediaSessions.Entry> updated) {
        boolean hadFocus = mediaRow.isFocused();
        int previousIndex = mediaSessionIndex();
        mediaSessions = updated == null ? Collections.emptyList() : updated;
        if (mediaSessions.isEmpty()) {
            selectedMediaToken = null;
            mediaPendingToken = null;
            setMediaControlsOpen(false);
            mediaRow.setVisibility(View.GONE);
            updateVisibleNotifications();
            if (hadFocus) focusSelectedAppIcon();
            return;
        }
        int index = mediaSessionIndex();
        if (index < 0) index = Math.min(Math.max(previousIndex, 0),
                mediaSessions.size() - 1);
        selectedMediaToken = mediaSessions.get(index).token;
        mediaRow.setVisibility(View.VISIBLE);
        renderMediaSession();
        updateMediaTicker();
        updateVisibleNotifications();
    }

    private int mediaSessionIndex() {
        for (int i = 0; i < mediaSessions.size(); i++) {
            if (Objects.equals(selectedMediaToken, mediaSessions.get(i).token)) return i;
        }
        return -1;
    }

    /** Session browsing stops at the first and last session; never wraps. */
    private void moveMediaSession(int step) {
        if (mediaSessions.isEmpty()) return;
        int index = mediaSessionIndex();
        if (index < 0) index = 0;
        int next = MediaNavigationPolicy.moveSession(index, step, mediaSessions.size());
        if (next == index) return;
        selectedMediaToken = mediaSessions.get(next).token;
        mediaPendingToken = null;
        mediaSeekEditing = false;
        mediaSeekSelected = false;
        mediaModeSelected = false;
        renderMediaSession();
    }

    private void renderMediaSession() {
        int index = mediaSessionIndex();
        if (index < 0) return;
        NativeMediaSessions.Entry entry = mediaSessions.get(index);
        if (!entry.canSeek) mediaSeekEditing = false;
        PackageManager pm = getContext().getPackageManager();
        try {
            mediaAppIcon.setImageDrawable(pm.getApplicationIcon(entry.packageName));
        } catch (PackageManager.NameNotFoundException | RuntimeException error) {
            mediaAppIcon.setImageDrawable(pm.getDefaultActivityIcon());
        }
        String nowPlaying = entry.title;
        if (!entry.artist.isEmpty()) {
            nowPlaying = nowPlaying.isEmpty() ? entry.artist
                    : nowPlaying + "  ·  " + entry.artist;
        }
        if (nowPlaying.isEmpty()) nowPlaying = "Now playing";
        mediaNowPlaying.setText(nowPlaying);
        updateCollapsedMediaStatuses(entry);
        expandedMediaTitle.setText(entry.title.isEmpty() ? "Now playing" : entry.title);
        expandedMediaArtist.setText(entry.artist);
        expandedMediaArtist.setVisibility(entry.artist.isEmpty() ? View.GONE : View.VISIBLE);
        mediaButtons[NativeMediaSessions.PLAY_PAUSE].setImageResource(entry.playing
                ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play);
        mediaButtons[NativeMediaSessions.PLAY_PAUSE].setContentDescription(
                entry.playing ? "Pause" : "Play");
        mediaIndex.setText(mediaSessions.size() > 1
                ? (index + 1) + "/" + mediaSessions.size() : "");
        mediaIndex.setVisibility(!mediaControlsOpen && mediaSessions.size() > 1
                ? View.VISIBLE : View.GONE);
        mediaRow.setContentDescription("Now playing: " + nowPlaying
                + (mediaSessions.size() > 1
                        ? ", " + (index + 1) + " of " + mediaSessions.size() : "")
                + (MediaModePolicy.showShuffleStatus(entry.canShuffle, entry.shuffleMode)
                        ? ", shuffle on" : "")
                + (MediaModePolicy.showRepeatStatus(entry.canRepeat, entry.repeatMode)
                        ? entry.repeatMode == MediaModePolicy.REPEAT_ONE
                                ? ", repeat one" : ", repeat on" : ""));
        renderMediaProgress();
        updateMediaArtwork();
        updateMediaHighlight();
        notifyMediaHeader();
    }

    /** Artwork changes with the selected native media session or track metadata. */
    private void updateMediaArtwork() {
        if (!mediaControlsOpen) return;
        int index = mediaSessionIndex();
        if (index < 0) return;
        NativeMediaSessions.Entry entry = mediaSessions.get(index);
        if (Objects.equals(shownArtworkToken, entry.token)
                && Objects.equals(shownArtworkTitle, entry.title)
                && Objects.equals(shownArtworkArtist, entry.artist)
                && Objects.equals(shownArtworkUri, entry.artworkUri)
                && shownArtworkBitmap == entry.artwork) {
            return;
        }

        shownArtworkToken = entry.token;
        shownArtworkTitle = entry.title;
        shownArtworkArtist = entry.artist;
        shownArtworkUri = entry.artworkUri;
        shownArtworkBitmap = entry.artwork;
        mediaArtworkLoader.cancel();
        mediaArtworkImage.setImageBitmap(entry.artwork);
        mediaArtworkPlaceholder.setVisibility(entry.artwork == null ? View.VISIBLE : View.GONE);
        mediaArtworkPlaceholder.setText(entry.artworkUri.isEmpty()
                ? "Artwork unavailable" : "Loading artwork");

        // Embedded bitmaps provide immediate feedback; an accessible metadata URI
        // may provide higher-resolution art without blocking SystemUI's main thread.
        if (!entry.artworkUri.isEmpty()) {
            mediaArtworkLoader.load(getContext(), entry.artworkUri, artwork -> {
                if (!mediaControlsOpen) return;
                if (artwork != null) {
                    mediaArtworkImage.setImageBitmap(artwork);
                    mediaArtworkPlaceholder.setVisibility(View.GONE);
                } else if (mediaArtworkImage.getDrawable() == null) {
                    mediaArtworkPlaceholder.setText("Artwork unavailable");
                    mediaArtworkPlaceholder.setVisibility(View.VISIBLE);
                }
            });
        }
    }

    /** Display state only: these icons never issue commands or consume focus. */
    private void updateCollapsedMediaStatuses(NativeMediaSessions.Entry entry) {
        boolean collapsed = !mediaControlsOpen && entry != null;
        boolean shuffleOn = collapsed
                && MediaModePolicy.showShuffleStatus(entry.canShuffle, entry.shuffleMode);
        boolean repeatOn = collapsed
                && MediaModePolicy.showRepeatStatus(entry.canRepeat, entry.repeatMode);
        mediaShuffleStatus.setVisibility(shuffleOn ? View.VISIBLE : View.GONE);
        mediaRepeatStatus.setVisibility(repeatOn ? View.VISIBLE : View.GONE);
        if (repeatOn) {
            mediaRepeatStatus.setRepeatOne(entry.repeatMode == MediaModePolicy.REPEAT_ONE);
        }
    }

    private void setMediaControlsOpen(boolean open) {
        if (mediaControlsOpen == open) return;
        mediaControlsOpen = open;
        mediaSeekEditing = false;
        mediaSeekSelected = false;
        mediaModeSelected = false;
        mediaSeekDragging = false;
        mediaDragToken = null;
        mediaSeekRow.setVisibility(open ? View.VISIBLE : View.GONE);
        mediaArtworkFrame.setVisibility(open ? View.VISIBLE : View.GONE);
        mediaColumns.setVisibility(open ? View.GONE : View.VISIBLE);

        // Reuse the same focusable transport row and native media actions.
        // The collapsed bar shows only app icon, track info and session index;
        // transport buttons are available exclusively when expanded.
        mediaAppIcon.setVisibility(open ? View.GONE : View.VISIBLE);
        mediaNowPlaying.setVisibility(open ? View.GONE : View.VISIBLE);
        updateCollapsedMediaStatuses(currentMediaSession());
        mediaIndex.setVisibility(open ? View.GONE
                : mediaSessions.size() > 1 ? View.VISIBLE : View.GONE);
        mediaRow.setPadding(dp(open ? 10 : 9), dp(3),
                dp(open ? 10 : 9), dp(3));
        mediaRow.setLayoutParams(new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, dp(open ? 62 : 46)));
        for (ImageView button : mediaButtons) {
            button.setVisibility(open ? View.VISIBLE : View.GONE);
            button.setLayoutParams(open
                    ? new LinearLayout.LayoutParams(0, LayoutParams.MATCH_PARENT, 1f)
                    : new LinearLayout.LayoutParams(dp(39), dp(39)));
            button.setPadding(dp(open ? 14 : 9), dp(open ? 14 : 9),
                    dp(open ? 14 : 9), dp(open ? 14 : 9));
        }
        mediaContainer.setLayoutParams(new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT,
                open ? 0 : LayoutParams.WRAP_CONTENT, open ? 1f : 0f));

        if (open) {
            renderMediaProgress();
            updateMediaArtwork();
        } else {
            mediaArtworkLoader.cancel();
            shownArtworkToken = null;
            shownArtworkTitle = null;
            shownArtworkArtist = null;
            shownArtworkUri = null;
            shownArtworkBitmap = null;
            mediaArtworkImage.setImageDrawable(null);
        }
        updateMediaHighlight();
        updateMediaTicker();
        notifyMediaHeader();
    }

    /** The shared host owns the media header; the player only reports its state. */
    void setMediaHeaderListener(MediaHeaderListener listener) {
        onMediaHeader = listener;
        notifyMediaHeader();
    }

    private NativeMediaSessions.Entry currentMediaSession() {
        int index = mediaSessionIndex();
        return index < 0 ? null : mediaSessions.get(index);
    }

    private void notifyMediaHeader() {
        if (onMediaHeader == null) return;
        NativeMediaSessions.Entry entry = currentMediaSession();
        if (entry != null && mediaModeSelected) {
            selectedMediaMode = MediaModePolicy.stepMode(selectedMediaMode, 0,
                    entry.canShuffle, entry.canRepeat);
            if (selectedMediaMode < 0) mediaModeSelected = false;
        }
        onMediaHeader.onMediaHeader(mediaControlsOpen, entry,
                mediaModeSelected, selectedMediaMode);
    }

    void activateMediaHeaderMode(int kind) {
        if (!mediaControlsOpen) return;
        NativeMediaSessions.Entry entry = currentMediaSession();
        if (entry == null || (kind == MediaModePolicy.SHUFFLE && !entry.canShuffle)
                || (kind == MediaModePolicy.REPEAT && !entry.canRepeat)) return;
        selectedMediaMode = kind;
        mediaModeSelected = true;
        mediaSeekEditing = false;
        mediaSeekSelected = false;
        mediaRow.requestFocus();
        updateMediaHighlight();
        notifyMediaHeader();
        performMediaModeAction();
    }

    private void performMediaModeAction() {
        NativeMediaSessions.Entry entry = currentMediaSession();
        if (entry == null) return;
        if (selectedMediaMode == MediaModePolicy.SHUFFLE && entry.canShuffle) {
            onMediaControl.onSetMode(entry.token, MediaModePolicy.SHUFFLE,
                    MediaModePolicy.nextShuffle(entry.shuffleMode));
        } else if (selectedMediaMode == MediaModePolicy.REPEAT && entry.canRepeat) {
            onMediaControl.onSetMode(entry.token, MediaModePolicy.REPEAT,
                    MediaModePolicy.nextRepeat(entry.repeatMode));
        }
    }

    private void updateMediaTicker() {
        removeCallbacks(mediaProgressTick);
        if (mediaControlsOpen && isAttachedToWindow()
                && mediaRow.getVisibility() == View.VISIBLE) {
            postDelayed(mediaProgressTick, 1000L);
        }
    }

    private long displayedMediaPosition(NativeMediaSessions.Entry entry) {
        long nowMs = SystemClock.elapsedRealtime();
        long reported = entry.positionAt(nowMs);
        if (Objects.equals(mediaPendingToken, entry.token)) {
            if (nowMs < mediaPendingUntilMs
                    && Math.abs(reported - mediaPendingPositionMs) > 1500L) {
                return mediaPendingPositionMs;
            }
            mediaPendingToken = null;
        }
        return reported;
    }

    private void renderMediaProgress() {
        int index = mediaSessionIndex();
        if (index < 0) return;
        NativeMediaSessions.Entry entry = mediaSessions.get(index);
        if (mediaSeekDragging) return;
        long positionMs = displayedMediaPosition(entry);
        mediaElapsed.setText(MediaSeekPolicy.formatTime(positionMs));
        mediaDuration.setText(entry.durationMs > 0
                ? MediaSeekPolicy.formatTime(entry.durationMs) : "--:--");
        mediaSeekBar.setProgress(MediaSeekPolicy.toProgress(positionMs, entry.durationMs));
        mediaSeekBar.setEnabled(entry.canSeek);
        mediaSeekBar.setAlpha(entry.canSeek ? 1f : 0.4f);
        if (!entry.canSeek && mediaSeekEditing) {
            mediaSeekEditing = false;
            updateMediaHighlight();
        }
        updateSeekAccessibility(entry);
    }

    private void updateSeekAccessibility(NativeMediaSessions.Entry entry) {
        String position = "Playback position, " + mediaElapsed.getText() + " of "
                + mediaDuration.getText();
        mediaSeekBar.setContentDescription(!entry.canSeek
                ? "Playback position, seeking unavailable"
                : position + ", draggable. " + (mediaSeekEditing
                        ? "Trackpad seek mode active; press to finish"
                        : mediaSeekSelected
                                ? "Press trackpad to enter seek mode"
                                : "Highlight to use trackpad seeking"));
    }

    /** First trackpad click enters seek mode; second click exits it. */
    private void toggleMediaSeekEditing() {
        NativeMediaSessions.Entry entry = currentMediaSession();
        mediaSeekEditing = MediaSeekPolicy.toggleTrackpadEditing(
                mediaSeekSelected, mediaSeekEditing, entry != null && entry.canSeek);
        updateMediaHighlight();
    }

    private void seekMedia(NativeMediaSessions.Entry entry, long positionMs) {
        if (!entry.canSeek || entry.durationMs <= 0) return;
        long bounded = MediaSeekPolicy.clamp(positionMs, entry.durationMs);
        if (onMediaControl.onSeek(entry.token, bounded)) {
            mediaPendingToken = entry.token;
            mediaPendingPositionMs = bounded;
            mediaPendingUntilMs = SystemClock.elapsedRealtime() + 1600L;
        }
        renderMediaProgress();
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        updateMediaTicker();
    }

    @Override protected void onDetachedFromWindow() {
        dismissNotificationActionPopup();
        removeCallbacks(mediaProgressTick);
        mediaArtworkLoader.close();
        mediaArtworkImage.setImageDrawable(null);
        super.onDetachedFromWindow();
    }

    /** BBOS6 seek area: dark vertical fill, 2dp top rule and silver bottom rule. */
    private Drawable mediaSeekBackground() {
        GradientDrawable fill = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                new int[] {0xff222422, 0xff181818});
        GradientDrawable bottomBorder = new GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                new int[] {0xffb5b6b5, 0xffffffff, 0xffb5b6b5});
        LayerDrawable layers = new LayerDrawable(new Drawable[] {
                fill, new ColorDrawable(0xff393c3c), bottomBorder
        });
        layers.setLayerHeight(1, dp(2));
        layers.setLayerGravity(1, Gravity.TOP | Gravity.FILL_HORIZONTAL);
        layers.setLayerHeight(2, dp(2));
        layers.setLayerGravity(2, Gravity.BOTTOM | Gravity.FILL_HORIZONTAL);
        return layers;
    }

    /** Centered 4dp rail: hard two-tone unplayed track, solid white played track. */
    private Drawable mediaSeekTrackDrawable() {
        final int railHeight = dp(4);
        Drawable unplayed = new Drawable() {
            private final Paint topPaint = new Paint();
            private final Paint bottomPaint = new Paint();

            {
                topPaint.setColor(MEDIA_SEEK_TRACK_TOP);
                bottomPaint.setColor(MEDIA_SEEK_TRACK_BOTTOM);
            }

            @Override public void draw(Canvas canvas) {
                Rect bounds = getBounds();
                // ProgressBar may size the drawable to the entire seek row.
                // Paint only the centered 4dp rail, not the drawable's full height.
                int railTop = bounds.centerY() - railHeight / 2;
                int halfway = railTop + railHeight / 2;
                int railBottom = railTop + railHeight;
                canvas.drawRect(bounds.left, railTop, bounds.right, halfway, topPaint);
                canvas.drawRect(bounds.left, halfway, bounds.right, railBottom, bottomPaint);
            }

            @Override public int getIntrinsicHeight() {
                return railHeight;
            }

            @Override public void setAlpha(int alpha) {
                topPaint.setAlpha(alpha);
                bottomPaint.setAlpha(alpha);
                invalidateSelf();
            }

            @Override public void setColorFilter(ColorFilter filter) {
                topPaint.setColorFilter(filter);
                bottomPaint.setColorFilter(filter);
                invalidateSelf();
            }

            @Override public int getOpacity() {
                return PixelFormat.TRANSLUCENT;
            }
        };

        // Android updates R.id.progress with a 0..10000 drawable level.
        // Clip only the white overlay from the left; do not clip the base track.
        ClipDrawable played = new ClipDrawable(
                new ColorDrawable(Color.WHITE), Gravity.LEFT, ClipDrawable.HORIZONTAL);
        LayerDrawable layers = new LayerDrawable(new Drawable[] {unplayed, played});
        layers.setId(0, android.R.id.background);
        layers.setId(1, android.R.id.progress);
        layers.setLayerHeight(1, railHeight);
        layers.setLayerGravity(1, Gravity.CENTER_VERTICAL | Gravity.FILL_HORIZONTAL);
        return layers;
    }

    /** Silver by default; selected pill alone uses the existing BB UI blue. */
    private GradientDrawable mediaSeekThumb(boolean selected) {
        GradientDrawable pill = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                selected ? BB7Theme.gradientColors(getContext())
                        : new int[] {0xffe5e9ef, 0xffaeb6c0});
        pill.setShape(GradientDrawable.RECTANGLE);
        pill.setCornerRadius(dp(10));
        pill.setSize(dp(selected ? 56 : 54), dp(selected ? 18 : 16));
        pill.setStroke(dp(selected ? 2 : 1),
                selected ? BB7Theme.BLUE_BORDER : 0xff87919c);
        return pill;
    }

    private void updateMediaHighlight() {
        boolean focused = mediaRow.isFocused();
        mediaRow.setBackground(focused && !mediaControlsOpen
                ? BB7Theme.selected(getContext(), dp(2), dp(1))
                : new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                        palette == BB7Theme.Palette.DARK
                                ? new int[] {0xff2f4053, 0xff1e2c3b}
                                : new int[] {0xffecf1f7, 0xffd8e2ec}));
        mediaNowPlaying.setTextColor(focused && !mediaControlsOpen
                ? Color.WHITE : palette.title);
        mediaIndex.setTextColor(focused && !mediaControlsOpen
                ? Color.WHITE : palette.muted);
        int statusTint = focused && !mediaControlsOpen ? Color.WHITE : palette.body;
        mediaShuffleStatus.setTint(statusTint);
        mediaRepeatStatus.setTint(statusTint);
        boolean seekFocused = focused && mediaControlsOpen && mediaSeekSelected;
        // Keep the fixed seek-area background; focus changes only the pill.
        // The elapsed/total timestamps stay unchanged.
        mediaSeekBar.setThumb(mediaSeekThumb(seekFocused));
        mediaElapsed.setTextColor(palette.muted);
        mediaDuration.setTextColor(palette.muted);
        int index = mediaSessionIndex();
        if (index < 0) return;
        NativeMediaSessions.Entry entry = mediaSessions.get(index);
        updateSeekAccessibility(entry);
        for (int i = 0; i < mediaButtons.length; i++) {
            boolean enabled = i == NativeMediaSessions.PREVIOUS ? entry.canPrevious
                    : i == NativeMediaSessions.NEXT ? entry.canNext : entry.canPlayPause;
            boolean selected = focused && mediaControlsOpen
                    && !mediaSeekSelected && !mediaModeSelected
                    && selectedMediaButton == i;
            ImageView button = mediaButtons[i];
            button.setBackground(selected ? BB7Theme.selected(getContext(), dp(3), dp(1)) : null);
            button.setColorFilter(selected || focused && !mediaControlsOpen
                    ? Color.WHITE : palette.headingText);
            button.setAlpha(enabled ? 1f : 0.35f);
        }
    }

    private void performMediaAction() {
        int index = mediaSessionIndex();
        if (index < 0) return;
        NativeMediaSessions.Entry entry = mediaSessions.get(index);
        boolean allowed = selectedMediaButton == NativeMediaSessions.PREVIOUS
                ? entry.canPrevious : selectedMediaButton == NativeMediaSessions.NEXT
                ? entry.canNext : entry.canPlayPause;
        if (allowed) onMediaControl.onControl(entry.token, selectedMediaButton);
    }

    private boolean onMediaKey(int code, KeyEvent event) {
        if (mediaSessions.isEmpty()) return false;
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (event.getAction() == KeyEvent.ACTION_DOWN) {
                    int step = code == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : 1;
                    if (mediaControlsOpen && mediaSeekSelected) {
                        int index = mediaSessionIndex();
                        if (index >= 0) {
                            NativeMediaSessions.Entry entry = mediaSessions.get(index);
                            if (MediaSeekPolicy.canTrackpadSeek(mediaSeekSelected,
                                    mediaSeekEditing, entry.canSeek)) {
                                seekMedia(entry, MediaSeekPolicy.seekStep(
                                        displayedMediaPosition(entry), entry.durationMs, step));
                            }
                        }
                    } else if (mediaControlsOpen && mediaModeSelected) {
                        NativeMediaSessions.Entry entry = currentMediaSession();
                        if (entry != null) {
                            selectedMediaMode = MediaModePolicy.stepMode(selectedMediaMode, step,
                                    entry.canShuffle, entry.canRepeat);
                            notifyMediaHeader();
                        }
                    } else if (mediaControlsOpen) {
                        selectedMediaButton = AppSelectionPolicy.move(
                                selectedMediaButton, step, mediaButtons.length);
                        updateMediaHighlight();
                    } else {
                        moveMediaSession(step);
                    }
                }
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (event.getAction() == KeyEvent.ACTION_DOWN) {
                    if (mediaControlsOpen) {
                        // Trackpad seek-edit mode is exited by a second click,
                        // not by navigating away with an up/down swipe.
                        if (mediaSeekEditing) return true;
                        // Visual order from top: mode header, artwork, seekbar,
                        // transport controls. Keep hardware navigation aligned.
                        if (mediaModeSelected) {
                            if (code == KeyEvent.KEYCODE_DPAD_DOWN) {
                                mediaModeSelected = false;
                                mediaSeekSelected = true;
                                updateMediaHighlight();
                                notifyMediaHeader();
                            }
                        } else if (mediaSeekSelected) {
                            if (code == KeyEvent.KEYCODE_DPAD_DOWN) {
                                mediaSeekSelected = false;
                                updateMediaHighlight();
                            } else {
                                NativeMediaSessions.Entry entry = currentMediaSession();
                                if (entry != null) {
                                    int first = MediaModePolicy.firstAvailable(
                                            entry.canShuffle, entry.canRepeat);
                                    if (first >= 0) {
                                        selectedMediaMode = first;
                                        mediaModeSelected = true;
                                        mediaSeekSelected = false;
                                        updateMediaHighlight();
                                        notifyMediaHeader();
                                    }
                                }
                            }
                        } else if (code == KeyEvent.KEYCODE_DPAD_UP) {
                            mediaSeekSelected = true;
                            updateMediaHighlight();
                        }
                    } else if (code == KeyEvent.KEYCODE_DPAD_UP) {
                        enterStatusZone();
                    } else {
                        focusSelectedAppIcon();
                    }
                }
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
            case KeyEvent.KEYCODE_SPACE:
                if (event.getAction() == KeyEvent.ACTION_UP) {
                    if (!mediaControlsOpen) {
                        selectedMediaButton = NativeMediaSessions.PLAY_PAUSE;
                        setMediaControlsOpen(true);
                    } else if (mediaModeSelected) {
                        performMediaModeAction();
                    } else if (mediaSeekSelected) {
                        toggleMediaSeekEditing();
                    } else {
                        performMediaAction();
                    }
                }
                return true;
            default:
                return false;
        }
    }

    private void focusMediaRow() {
        if (mediaRow.getVisibility() == View.VISIBLE) {
            leaveStatusZone();
            mediaRow.requestFocus();
        } else {
            focusSelectedAppIcon();
        }
    }

    /** Update the entire Favorites category when app-side settings change. */
    void setFavorites(FavoritesRules updated) {
        favorites = updated == null ? new FavoritesRules() : updated;
        updateVisibleNotifications();
    }

    /** Switch grouping without replacing the native shade or its navigation owner. */
    void setOrganizationMode(String requested) {
        String next = NotificationShadeModePolicy.normalized(requested);
        if (next.equals(organizationMode)) return;
        organizationMode = next;
        updateVisibleNotifications();
    }

    /** Update the two columns from the same notification snapshot. */
    void setNotifications(List<NotificationItem> items) {
        notifications = items == null ? Collections.emptyList()
                : Collections.unmodifiableList(new ArrayList<>(items));
        updateVisibleNotifications();
    }

    /** Hide silent media cards only when the native media bar represents them. */
    private void updateVisibleNotifications() {
        Set<MediaSession.Token> representedTokens = new HashSet<>();
        for (NativeMediaSessions.Entry session : mediaSessions) {
            representedTokens.add(session.token);
        }
        Set<String> representedKeys =
                SystemUiNotificationObserver.notificationKeysForMediaTokens(representedTokens);
        visibleNotifications = NotificationSelectionPolicy.withoutRepresentedSilentMedia(
                notifications, representedKeys);
        setNotificationPackages(NotificationShadeModePolicy.categoriesFor(
                visibleNotifications, favorites.enabled, organizationMode));
        renderSelectedNotifications();
    }

    /**
     * Called on the SystemUI UI thread after a notification app list update.
     * Preserve selection by package, and restore focus if a row was focused.
     */
    void setNotificationPackages(List<String> packages) {
        List<String> distinct = NotificationAppIndex.uniquePackages(packages);
        if (renderedPackages.equals(distinct)) {
            // Counts can change without adding or removing an app.
            updateNotificationCounts();
            return;
        }

        int previousIndex = renderedPackages.indexOf(selectedPackage);
        String previousPackage = selectedPackage;
        boolean hadRowFocus = false;
        for (FrameLayout row : iconRows) {
            if (row.isFocused()) {
                hadRowFocus = true;
                break;
            }
        }
        int newIndex = AppSelectionPolicy.afterUpdate(selectedPackage, previousIndex, distinct);
        renderedPackages = distinct;
        selectedPackage = newIndex < 0 ? null : distinct.get(newIndex);
        boolean previousAppVanished = notificationPaneActive
                && !Objects.equals(previousPackage, selectedPackage);
        if (previousAppVanished) {
            notificationPaneActive = false;
            selectedNotificationKey = null;
            selectedActionIndex = FOCUS_CARD;
        }

        iconList.removeAllViews();
        iconRows.clear();
        iconImages.clear();
        iconCounts.clear();
        iconLabels.clear();
        PackageManager pm = getContext().getPackageManager();
        for (int i = 0; i < distinct.size(); i++) {
            final String pkg = distinct.get(i);
            final boolean silentCategory = NotificationCategoryPolicy.isSilent(pkg);
            final boolean favoritesCategory = NotificationCategoryPolicy.isFavorites(pkg);
            final boolean typeCategory = NotificationTypePolicy.isType(pkg);
            if (silentCategory) {
                // Remaining height pushes Silent to the bottom; excess app rows scroll.
                View spacer = new View(getContext());
                spacer.setFocusable(false);
                iconList.addView(spacer, new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
            }
            FrameLayout row = new FrameLayout(getContext());
            row.setFocusable(!notificationPaneActive);
            row.setFocusableInTouchMode(!notificationPaneActive);
            row.setClickable(true);
            row.setOnFocusChangeListener((view, focused) -> {
                if (focused && !notificationPaneActive) select(pkg);
                // Redraw on both focus gain and loss, not just package changes.
                updateSelectedHighlight();
            });
            row.setOnClickListener(view -> {
                select(pkg);
                if (!enterNotificationPane()) view.requestFocus();
            });
            row.setOnKeyListener((view, keyCode, event) -> onIconKey(view, keyCode, event));

            ImageView icon = new ImageView(getContext());
            Drawable drawable;
            CharSequence label;
            if (silentCategory) {
                drawable = getContext().getDrawable(android.R.drawable.ic_lock_silent_mode);
                if (drawable == null) drawable = pm.getDefaultActivityIcon();
                label = "Silent notifications";
            } else if (favoritesCategory) {
                drawable = getContext().getDrawable(android.R.drawable.btn_star_big_on);
                if (drawable == null) drawable = pm.getDefaultActivityIcon();
                label = "Favorite notifications";
            } else if (typeCategory) {
                drawable = new NotificationTypeIconDrawable(pkg);
                label = NotificationTypePolicy.label(pkg) + " notifications";
            } else {
                try {
                    drawable = pm.getApplicationIcon(pkg);
                    label = pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0));
                } catch (PackageManager.NameNotFoundException | RuntimeException error) {
                    drawable = pm.getDefaultActivityIcon();
                    label = pkg;
                }
            }
            row.setContentDescription(label);
            iconLabels.add(label);
            icon.setImageDrawable(drawable);
            if (silentCategory || favoritesCategory || typeCategory)
                icon.setColorFilter(palette.headingText);
            icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
            icon.setFocusable(false);
            icon.setClickable(false);
            icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            // Keep one focusable/clickable row. Both children are display-only.
            LinearLayout contents = new LinearLayout(getContext());
            contents.setOrientation(LinearLayout.HORIZONTAL);
            contents.setGravity(Gravity.CENTER_VERTICAL);
            contents.setBaselineAligned(false);
            contents.setFocusable(false);
            contents.setClickable(false);
            contents.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);

            icon.setPadding(dp(3), dp(8), dp(1), dp(8));
            contents.addView(icon, new LinearLayout.LayoutParams(
                    0, LayoutParams.MATCH_PARENT, 1f));

            TextView count = new TextView(getContext());
            count.setTextSize(11);
            count.setMinWidth(dp(16));
            count.setTypeface(null, android.graphics.Typeface.BOLD);
            count.setTextColor(palette.headingText);
            count.setGravity(Gravity.CENTER);
            count.setIncludeFontPadding(false);
            count.setSingleLine(true);
            count.setPadding(0, 0, dp(3), 0);
            count.setFocusable(false);
            count.setClickable(false);
            count.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            contents.addView(count, new LinearLayout.LayoutParams(
                    LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT));
            row.addView(contents, new FrameLayout.LayoutParams(
                    LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
            iconList.addView(row, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(56)));
            iconRows.add(row);
            iconImages.add(icon);
            iconCounts.add(count);
        }

        updateNotificationCounts();
        updateSelectedHighlight();
        if ((hadRowFocus && !notificationPaneActive) || previousAppVanished) {
            // Removing a focused row may move focus to the root. Restore it
            // explicitly so keyboard/trackpad navigation continues smoothly.
            focusSelectedAppIcon();
        }
        Log.i(TAG, "App icon selector updated: count=" + distinct.size()
                + " selectedIndex=" + newIndex);
    }

    /** Update count text in-place so incoming notifications cannot reset trackpad focus. */
    private void updateNotificationCounts() {
        for (int i = 0; i < renderedPackages.size(); i++) {
            int count = NotificationCategoryCounts.countFor(
                    visibleNotifications, favorites, renderedPackages.get(i));
            TextView label = iconCounts.get(i);
            // Keep the numeric slot reserved to avoid shifting persistent icons.
            String value = NotificationTypePolicy.isType(renderedPackages.get(i))
                    && count == 0 ? "" : Integer.toString(count);
            if (!TextUtils.equals(label.getText(), value)) label.setText(value);
            iconRows.get(i).setContentDescription(iconLabels.get(i) + ", " + count
                    + (count == 1 ? " notification" : " notifications"));
        }
    }

    /** Called once the pull-down entrance animation finishes. */
    void focusSelectedAppIcon() {
        leaveStatusZone();
        int index = renderedPackages.indexOf(selectedPackage);
        if (index >= 0 && index < iconRows.size()) {
            iconRows.get(index).requestFocus();
        } else if (mediaRow.getVisibility() == View.VISIBLE) {
            mediaRow.requestFocus();
        } else {
            requestFocus();
        }
    }

    private void select(String pkg) {
        if (pkg.equals(selectedPackage)) return;
        selectedPackage = pkg;
        updateSelectedHighlight();
        renderSelectedNotifications();
        Log.i(TAG, "App icon selected: index="
                + (renderedPackages.indexOf(pkg) + 1)
                + " of=" + renderedPackages.size());
    }

    private void renderSelectedNotifications() {
        boolean silentCategory = NotificationCategoryPolicy.isSilent(selectedPackage);
        boolean favoritesCategory = NotificationCategoryPolicy.isFavorites(selectedPackage);
        List<NotificationItem> selected = NotificationShadeModePolicy.select(
                visibleNotifications, favorites, selectedPackage);
        if (Objects.equals(renderedCardPackage, selectedPackage)
                && renderedCards.equals(selected)) return;

        // Avoid leaving an open popup anchored to a detached card.
        dismissNotificationActionPopup();
        int oldCardIndex = NotificationFocusPolicy.indexOf(
                renderedCards, selectedNotificationKey);
        String previousKey = selectedNotificationKey;
        boolean sameApp = Objects.equals(renderedCardPackage, selectedPackage);
        selectedNotificationKey = NotificationFocusPolicy.retainedKey(
                selected, sameApp ? selectedNotificationKey : null,
                sameApp ? oldCardIndex : -1);
        if (!Objects.equals(previousKey, selectedNotificationKey)) {
            selectedActionIndex = FOCUS_CARD;
        }
        activeReplyInput = null;
        activeReplyAnchor = null;
        renderedCardPackage = selectedPackage;
        renderedCards = selected;
        notificationList.removeAllViews();
        notificationRows.clear();
        notificationTargets.clear();
        notificationTargetKeys.clear();
        notificationTargetActions.clear();

        if (selectedPackage == null) {
            selectedAppHeading.setText("Notifications");
            addPlaceholder("No active notifications");
            return;
        }

        boolean typeCategory = NotificationTypePolicy.isType(selectedPackage);
        CharSequence appName = favoritesCategory ? "Favorites"
                : silentCategory ? "Silent"
                : typeCategory ? NotificationTypePolicy.label(selectedPackage)
                : selectedPackage;
        if (!silentCategory && !favoritesCategory && !typeCategory) {
            try {
                PackageManager pm = getContext().getPackageManager();
                appName = pm.getApplicationLabel(pm.getApplicationInfo(selectedPackage, 0));
            } catch (PackageManager.NameNotFoundException | RuntimeException ignored) { }
        }
        selectedAppHeading.setText(appName + " (" + selected.size() + ")");

        if (selected.isEmpty()) {
            addPlaceholder(favoritesCategory ? "No notifications match Favorites"
                    : typeCategory ? "No notifications in this category"
                    : "No notifications for this app");
        } else {
            Long previousDay = null;
            for (int i = 0; i < selected.size(); i++) {
                NotificationItem item = selected.get(i);
                long timestamp = item.postedAt > 0
                        ? item.postedAt : System.currentTimeMillis();
                long day = localDayKey(timestamp);
                if (previousDay == null || previousDay.longValue() != day) {
                    addDateHeader(timestamp);
                    previousDay = day;
                }
                LinearLayout card = createNotificationCard(item);
                notificationList.addView(card);
                notificationRows.add(card);
                if (i < selected.size() - 1) {
                    // A hairline between list entries, not a keyboard/trackpad stop.
                    View separator = new View(getContext());
                    separator.setFocusable(false);
                    separator.setBackgroundColor(palette.divider);
                    notificationList.addView(separator, new LinearLayout.LayoutParams(
                            LayoutParams.MATCH_PARENT, dp(1)));
                }
            }
        }
        if (notificationPaneActive && notificationRows.isEmpty()) {
            returnToAppIcons();
        } else if (navigation.isStatusBarFocused()) {
            // A live notification refresh must not steal focus from the status bar.
            updateNotificationHighlight();
        } else if (notificationPaneActive) {
            focusSelectedNotification();
        } else {
            updateNotificationHighlight();
            notificationScroller.scrollTo(0, 0);
        }
        Log.i(TAG, "Selected app notifications displayed: count=" + selected.size());
    }

    /** BBOS6-style date strip above each run of notification entries. */
    private void addDateHeader(long timestamp) {
        String label = DateUtils.isToday(timestamp) ? "Today"
                : DateFormat.getMediumDateFormat(getContext()).format(new Date(timestamp));
        TextView heading = text(label, 11, palette.headingText);
        heading.setTypeface(null, android.graphics.Typeface.BOLD);
        heading.setGravity(Gravity.CENTER_VERTICAL);
        heading.setFocusable(false);
        heading.setPadding(dp(12), 0, dp(12), 0);
        heading.setBackgroundColor(palette.heading);
        notificationList.addView(heading, new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, dp(27)));
    }

    private static long localDayKey(long timestamp) {
        Calendar calendar = Calendar.getInstance();
        calendar.setTimeInMillis(timestamp);
        return calendar.get(Calendar.YEAR) * 1000L + calendar.get(Calendar.DAY_OF_YEAR);
    }

    private void addPlaceholder(String message) {
        TextView placeholder = text(message, 13, palette.muted);
        placeholder.setPadding(dp(8), dp(20), dp(8), dp(16));
        notificationList.addView(placeholder);
    }

    private LinearLayout createNotificationCard(NotificationItem item) {
        LinearLayout card = new SwipeDismissCard(getContext(), item.clearable,
                () -> onActions.onDismiss(item.key));
        card.setFocusable(true);
        card.setFocusableInTouchMode(true);
        card.setClickable(true);
        TrackpadPressHandler press = new TrackpadPressHandler(
                card::performClick, card::performLongClick);
        card.setOnFocusChangeListener((view, focused) -> {
            if (focused) {
                notificationPaneActive = true;
                selectNotification(item.key, FOCUS_CARD);
            } else {
                press.cancel();
            }
        });
        card.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override public void onViewAttachedToWindow(View view) { }
            @Override public void onViewDetachedFromWindow(View view) {
                press.cancel();
            }
        });
        card.setOnClickListener(view -> {
            notificationPaneActive = true;
            selectNotification(item.key, FOCUS_CARD);
            view.requestFocus();
            onOpenNotification.onOpen(item.key);
        });
        card.setOnKeyListener((view, keyCode, event) -> {
            if (TrackpadPressHandler.isConfirmKey(keyCode)) return press.handle(event);
            if (event.getAction() == KeyEvent.ACTION_DOWN) press.cancel();
            return onNotificationKey(view, keyCode, event);
        });
        card.setOnLongClickListener(view -> showNotificationActionPopup(card, item));
        notificationTargets.add(card);
        notificationTargetKeys.add(item.key);
        notificationTargetActions.add(FOCUS_CARD);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(12), dp(8), dp(12), dp(8));
        GradientDrawable background = new GradientDrawable();
        background.setColor(palette.card);
        card.setBackground(background);

        LinearLayout titleLine = new LinearLayout(getContext());
        titleLine.setOrientation(LinearLayout.HORIZONTAL);
        titleLine.setGravity(Gravity.TOP);
        if (NotificationCategoryPolicy.isFavorites(selectedPackage)) {
            ImageView sourceIcon = new ImageView(getContext());
            try {
                sourceIcon.setImageDrawable(
                        getContext().getPackageManager().getApplicationIcon(item.packageName));
            } catch (PackageManager.NameNotFoundException error) {
                sourceIcon.setImageDrawable(
                        getContext().getPackageManager().getDefaultActivityIcon());
            }
            sourceIcon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            LinearLayout.LayoutParams iconParams =
                    new LinearLayout.LayoutParams(dp(19), dp(19));
            iconParams.rightMargin = dp(7);
            titleLine.addView(sourceIcon, iconParams);
        }
        TextView title = text(item.title.isEmpty() ? "Notification" : item.title,
                14, palette.title);
        title.setTag(Integer.valueOf(palette.title));
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        title.setMaxLines(2);
        title.setEllipsize(TextUtils.TruncateAt.END);
        titleLine.addView(title, new LinearLayout.LayoutParams(
                0, LayoutParams.WRAP_CONTENT, 1f));
        if (item.postedAt > 0) {
            TextView time = text(DateFormat.getTimeFormat(getContext())
                    .format(new Date(item.postedAt)), 10, palette.muted);
            time.setTag(Integer.valueOf(palette.muted));
            time.setSingleLine(true);
            time.setGravity(Gravity.END);
            LinearLayout.LayoutParams timeParams = new LinearLayout.LayoutParams(
                    LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
            timeParams.leftMargin = dp(8);
            titleLine.addView(time, timeParams);
        }
        card.addView(titleLine);

        if (!item.text.isEmpty()) {
            TextView body = text(item.text, 12, palette.body);
            body.setTag(Integer.valueOf(palette.body));
            body.setMaxLines(8);
            body.setEllipsize(TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams bodyParams = new LinearLayout.LayoutParams(
                    LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
            bodyParams.topMargin = dp(5);
            card.addView(body, bodyParams);
        }

        card.setContentDescription(
                (NotificationCategoryPolicy.isFavorites(selectedPackage)
                        ? item.packageName + ", " : "")
                + (item.title.isEmpty() ? "Notification" : item.title)
                + (item.text.isEmpty() ? "" : ", " + item.text));
        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        card.setLayoutParams(cardParams);
        return card;
    }

    /**
     * Horizontal card gestures dismiss through the existing native NLS callback.
     * Vertical motion is left to ScrollView; taps and native action buttons
     * retain their normal framework event dispatch.
     */
    private static final class SwipeDismissCard extends LinearLayout {
        private final boolean clearable;
        private final Runnable onDismiss;
        private final int touchSlop;
        private float downX;
        private float downY;
        private boolean swiping;
        private boolean ignoreTouch;
        private boolean dismissing;

        SwipeDismissCard(Context context, boolean clearable, Runnable onDismiss) {
            super(context);
            this.clearable = clearable;
            this.onDismiss = onDismiss;
            touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        }

        @Override public boolean onInterceptTouchEvent(MotionEvent event) {
            if (!clearable) return super.onInterceptTouchEvent(event);
            if (dismissing) return true;
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX = event.getRawX();
                    downY = event.getRawY();
                    swiping = false;
                    ignoreTouch = false;
                    break;
                case MotionEvent.ACTION_POINTER_DOWN:
                    ignoreTouch = true;
                    if (swiping) resetSwipe();
                    break;
                case MotionEvent.ACTION_MOVE:
                    if (!ignoreTouch && !swiping && event.getPointerCount() == 1
                            && NotificationSwipePolicy.isHorizontal(
                                    event.getRawX() - downX, event.getRawY() - downY,
                                    touchSlop)) {
                        swiping = true;
                        setPressed(false);
                        if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                        return true;
                    }
                    break;
                default:
                    break;
            }
            return super.onInterceptTouchEvent(event);
        }

        @Override public boolean onTouchEvent(MotionEvent event) {
            if (!clearable) return super.onTouchEvent(event);
            if (dismissing) return true;
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX = event.getRawX();
                    downY = event.getRawY();
                    swiping = false;
                    ignoreTouch = false;
                    return super.onTouchEvent(event);
                case MotionEvent.ACTION_POINTER_DOWN:
                    ignoreTouch = true;
                    if (swiping) resetSwipe();
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if (ignoreTouch || event.getPointerCount() != 1) return true;
                    float dx = event.getRawX() - downX;
                    if (!swiping) {
                        if (!NotificationSwipePolicy.isHorizontal(
                                dx, event.getRawY() - downY, touchSlop)) {
                            return super.onTouchEvent(event);
                        }
                        swiping = true;
                        setPressed(false);
                        if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                    }
                    setTranslationX(dx);
                    setAlpha(1f - Math.min(0.5f, Math.abs(dx) / Math.max(1, getWidth())));
                    return true;
                case MotionEvent.ACTION_UP:
                    if (ignoreTouch) {
                        ignoreTouch = false;
                        return true;
                    }
                    if (!swiping) return super.onTouchEvent(event);
                    float distance = event.getRawX() - downX;
                    swiping = false;
                    if (NotificationSwipePolicy.shouldDismiss(distance, getWidth(), touchSlop)) {
                        dismissing = true;
                        animate().translationX(Math.signum(distance) * getWidth())
                                .alpha(0f).setDuration(150).withEndAction(() -> {
                                    onDismiss.run();
                                    // If Android retains this view (failed dismissal),
                                    // return it to its original position.
                                    postDelayed(() -> {
                                        if (getParent() != null) resetSwipe();
                                        dismissing = false;
                                    }, 350);
                                }).start();
                    } else {
                        resetSwipe();
                    }
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    ignoreTouch = false;
                    if (!swiping) return super.onTouchEvent(event);
                    resetSwipe();
                    return true;
                default:
                    return super.onTouchEvent(event);
            }
        }

        @Override public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
            super.onInitializeAccessibilityNodeInfo(info);
            if (clearable) {
                info.setDismissable(true);
                info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_DISMISS);
            }
        }

        @Override public boolean performAccessibilityAction(int action, Bundle arguments) {
            if (clearable && action == AccessibilityNodeInfo.ACTION_DISMISS) {
                onDismiss.run();
                return true;
            }
            return super.performAccessibilityAction(action, arguments);
        }

        private void resetSwipe() {
            swiping = false;
            animate().translationX(0f).alpha(1f).setDuration(130).start();
        }
    }

    /**
     * Stock Android ListPopupWindow owns keyboard navigation, selection,
     * theme and Back dismissal. Deliberately no per-popup BBUI styling.
     */
    private boolean showNotificationActionPopup(LinearLayout card, NotificationItem item) {
        if (item.actions.isEmpty() || !card.isAttachedToWindow()) return false;
        dismissNotificationActionPopup();
        String[] titles = new String[item.actions.size()];
        for (int i = 0; i < item.actions.size(); i++) {
            String title = item.actions.get(i).title;
            titles[i] = title.isEmpty() ? "Action" : title;
        }

        ListPopupWindow popup = new ListPopupWindow(getContext());
        popup.setAnchorView(card);
        popup.setAdapter(new ArrayAdapter<>(getContext(),
                android.R.layout.simple_list_item_1, titles));
        popup.setModal(true);
        popup.setInputMethodMode(PopupWindow.INPUT_METHOD_NOT_NEEDED);
        popup.setOnDismissListener(() -> {
            if (notificationActionPopup == popup) notificationActionPopup = null;
        });
        popup.setOnItemClickListener((parent, view, position, id) -> {
            NotificationItem.ActionItem action = item.actions.get(position);
            popup.dismiss();
            // A notification could disappear while its menu is open.
            if (!renderedCards.contains(item) || !card.isAttachedToWindow()) return;
            if (action.freeFormReply) {
                // Wait for the popup window to release focus before editing.
                card.post(() -> {
                    if (card.isAttachedToWindow()) {
                        openInlineReply(card, item.key, action.index);
                    }
                });
            } else {
                onActions.onAction(item.key, action.index);
            }
        });
        notificationActionPopup = popup;
        popup.show();
        if (popup.getListView() != null) {
            popup.getListView().setSelection(0);
            popup.getListView().requestFocus();
        }
        return true;
    }

    private void dismissNotificationActionPopup() {
        ListPopupWindow popup = notificationActionPopup;
        notificationActionPopup = null;
        if (popup != null && popup.isShowing()) popup.dismiss();
    }

    private void closeInlineReply() {
        EditText input = activeReplyInput;
        View anchor = activeReplyAnchor;
        activeReplyInput = null;
        activeReplyAnchor = null;
        if (input != null && input.getParent() instanceof LinearLayout) {
            ((LinearLayout) input.getParent()).removeView(input);
        }
        if (anchor != null && anchor.isAttachedToWindow()) anchor.requestFocus();
    }

    private void openInlineReply(LinearLayout card, String key, int index) {
        if (activeReplyInput != null) closeInlineReply();
        EditText input = new EditText(getContext());
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        input.setImeOptions(EditorInfo.IME_ACTION_SEND);
        input.setHint("Type reply, Enter to send");
        input.setTextColor(palette.title);
        input.setHintTextColor(palette.muted);
        input.setTextSize(13);
        input.setShowSoftInputOnFocus(false); // Q25 hardware keyboard
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, dp(42));
        params.topMargin = dp(4);
        card.addView(input, params);
        activeReplyInput = input;
        activeReplyAnchor = card;
        input.setOnEditorActionListener((view, actionId, event) -> {
            if (actionId != EditorInfo.IME_ACTION_SEND
                    && (event == null || event.getKeyCode() != KeyEvent.KEYCODE_ENTER)) {
                return false;
            }
            if (event != null && event.getAction() != KeyEvent.ACTION_UP) return true;
            String value = input.getText().toString();
            if (!value.trim().isEmpty() && onActions.onReply(key, index, value)) {
                closeInlineReply();
            }
            return true;
        });
        input.requestFocus();
    }

    private void enterNotificationPaneFocus() {
        leaveStatusZone();
        notificationPaneActive = true;
        for (FrameLayout icon : iconRows) {
            icon.setFocusable(false);
            icon.setFocusableInTouchMode(false);
        }
        focusSelectedNotification();
        Log.i(TAG, "Navigation entered notification pane");
    }

    /** Trackpad click/Enter enters, directional navigation never switches panes. */
    private boolean enterNotificationPane() {
        if (notificationRows.isEmpty()) return false;
        enterNotificationPaneFocus();
        return true;
    }

    /** Back restores the previously selected app icon. */
    private void returnToAppIcons() {
        dismissNotificationActionPopup();
        leaveStatusZone();
        notificationPaneActive = false;
        for (FrameLayout icon : iconRows) {
            icon.setFocusable(true);
            icon.setFocusableInTouchMode(true);
        }
        updateNotificationHighlight();
        focusSelectedAppIcon();
        Log.i(TAG, "Navigation returned to app icons");
    }

    private void focusSelectedNotification() {
        int index = findFocusTarget(selectedNotificationKey, selectedActionIndex);
        if (index < 0) index = findFocusTarget(selectedNotificationKey, FOCUS_CARD);
        if (index < 0) return;
        notificationTargets.get(index).requestFocus();
        updateNotificationHighlight();
    }

    private int findFocusTarget(String key, int actionIndex) {
        for (int i = 0; i < notificationTargets.size(); i++) {
            if (Objects.equals(key, notificationTargetKeys.get(i))
                    && notificationTargetActions.get(i) == actionIndex) return i;
        }
        return -1;
    }

    private void selectNotification(String key, int actionIndex) {
        boolean changed = !Objects.equals(selectedNotificationKey, key)
                || selectedActionIndex != actionIndex;
        selectedNotificationKey = key;
        selectedActionIndex = actionIndex;
        updateNotificationHighlight();
        if (changed) {
            Log.i(TAG, "Selected notification focus: card="
                    + (NotificationFocusPolicy.indexOf(renderedCards, key) + 1)
                    + " action=" + actionIndex);
        }
    }

    private void updateNotificationHighlight() {
        for (int i = 0; i < notificationRows.size(); i++) {
            LinearLayout card = notificationRows.get(i);
            boolean selected = notificationPaneActive && selectedActionIndex == FOCUS_CARD
                    && Objects.equals(renderedCards.get(i).key, selectedNotificationKey);
            GradientDrawable background;
            if (selected) {
                // Keep the existing blue gradient, but make the list row square.
                background = BB7Theme.selected(getContext(), 0, dp(1));
            } else {
                background = new GradientDrawable();
                background.setColor(palette.card);
            }
            card.setBackground(background);
            for (int child = 0; child < card.getChildCount(); child++) {
                updateTaggedTextColor(card.getChildAt(child), selected);
            }
        }

    }

    /** Preserve high-contrast text when the title and timestamp share a row. */
    private static void updateTaggedTextColor(View view, boolean selected) {
        if (view instanceof TextView && view.getTag() instanceof Integer) {
            int regularColor = (Integer) view.getTag();
            ((TextView) view).setTextColor(selected ? Color.WHITE : regularColor);
        } else if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                updateTaggedTextColor(group.getChildAt(i), selected);
            }
        }
    }

    private GradientDrawable selectedBlueBackground() {
        return BB7Theme.selected(getContext(), dp(5), dp(1));
    }

    private void enterStatusZone() {
        if (activeReplyInput != null) return;
        requestFocus();
        navigation.focusStatusBarFromPage(ShadeStatusNavigation.NOTIFICATIONS);
        Log.i(TAG, "Shade navigation header focused: notifications");
    }

    private void leaveStatusZone() {
        navigation.focusContent();
    }

    private boolean onNotificationKey(View view, int keyCode, KeyEvent event) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (event.getAction() == KeyEvent.ACTION_DOWN) {
                    int current = notificationTargets.indexOf(view);
                    int direction = keyCode == KeyEvent.KEYCODE_DPAD_UP ? -1 : 1;
                    int next = AppSelectionPolicy.move(current, direction,
                            notificationTargets.size());
                    // Keep Up bounded inside the notification/action list.
                    // Back explicitly returns to the app-icon column; only
                    // that column may move Up into the navigation header.
                    if (next >= 0) {
                        notificationTargets.get(next).requestFocus();
                    }
                }
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
            case KeyEvent.KEYCODE_SPACE:
                if (event.getAction() == KeyEvent.ACTION_UP) view.performClick();
                return true;
            default:
                return false;
        }
    }

    private TextView text(String value, float size, int color) {
        TextView text = new TextView(getContext());
        text.setText(value);
        text.setTextSize(size);
        text.setTextColor(color);
        return text;
    }

    /**
     * Swipe-up dismiss remains available when the column has no more
     * content below. If it can scroll further, ordinary scrolling wins.
     * A gesture that begins while scrollable cannot dismiss mid-scroll.
     */
    private void addSwipeUpDismiss(ScrollView scroller) {
        scroller.setOnTouchListener(new View.OnTouchListener() {
            private float startY;
            private boolean canDismiss;
            private boolean dismissed;

            @Override public boolean onTouch(View view, MotionEvent event) {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        startY = event.getRawY();
                        canDismiss = !view.canScrollVertically(1);
                        dismissed = false;
                        return false;
                    case MotionEvent.ACTION_MOVE:
                        if (canDismiss && !dismissed
                                && startY - event.getRawY() > dp(65)) {
                            dismissed = true;
                            onClose.onClose();
                            return true;
                        }
                        return false;
                    default:
                        return false;
                }
            }
        });
    }

    /** Refresh when the native status-bar focus zone changes. */
    void refreshAppIconFocus() {
        updateSelectedHighlight();
    }

    private void updateSelectedHighlight() {
        for (int i = 0; i < iconRows.size(); i++) {
            FrameLayout row = iconRows.get(i);
            boolean selected = AppSelectionPolicy.shouldHighlight(
                    renderedPackages.get(i), selectedPackage, row.isFocused(),
                    navigation.isStatusBarFocused());
            row.setSelected(selected);
            if (selected) {
                row.setBackground(BB7Theme.selected(getContext(), dp(3), dp(1)));
            } else {
                row.setBackground(null);
            }
            iconCounts.get(i).setTextColor(selected ? Color.WHITE : palette.headingText);
            if (NotificationCategoryPolicy.isSilent(renderedPackages.get(i))
                    || NotificationCategoryPolicy.isFavorites(renderedPackages.get(i))
                    || NotificationTypePolicy.isType(renderedPackages.get(i))) {
                iconImages.get(i).setColorFilter(
                        selected ? Color.WHITE : palette.headingText);
            }
        }
    }

    private boolean onIconKey(View view, int keyCode, KeyEvent event) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (event.getAction() == KeyEvent.ACTION_DOWN) {
                    int selectedIndex = renderedPackages.indexOf(selectedPackage);
                    int direction = keyCode == KeyEvent.KEYCODE_DPAD_UP ? -1 : 1;
                    int next = AppSelectionPolicy.move(selectedIndex,
                            direction, iconRows.size());
                    if (keyCode == KeyEvent.KEYCODE_DPAD_UP && selectedIndex == 0) {
                        if (mediaRow.getVisibility() == View.VISIBLE) focusMediaRow();
                        else enterStatusZone();
                    } else if (next >= 0) {
                        iconRows.get(next).requestFocus();
                    }
                }
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                // Back, not Left/Right, changes navigation levels.
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
            case KeyEvent.KEYCODE_SPACE:
                if (event.getAction() == KeyEvent.ACTION_UP) {
                    view.performClick();
                }
                return true;
            default:
                return false;
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

    /**
     * Identical Back behaviour whether a key reached the focused child
     * or SystemUI delivered a native OnBackInvokedCallback.
     */
    void handleNativeBack() {
        if (notificationActionPopup != null && notificationActionPopup.isShowing()) {
            dismissNotificationActionPopup();
            return;
        }
        if (activeReplyInput != null) closeInlineReply();
        else if (mediaControlsOpen) {
            setMediaControlsOpen(false);
        } else if (notificationPaneActive) returnToAppIcons();
        else onClose.onClose();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (navigation.handleKey(event)) {
            return true;
        }
        // Hardware Delete/Backspace dismisses the focused clearable notification,
        // including when one of its native action controls has focus. Never
        // intercept editing keys while an inline reply is active.
        if ((event.getKeyCode() == KeyEvent.KEYCODE_DEL
                || event.getKeyCode() == KeyEvent.KEYCODE_FORWARD_DEL)
                && notificationPaneActive && activeReplyInput == null) {
            int focusedIndex = notificationTargets.indexOf(findFocus());
            if (focusedIndex >= 0) {
                String key = notificationTargetKeys.get(focusedIndex);
                if (event.getAction() == KeyEvent.ACTION_UP) {
                    for (NotificationItem item : renderedCards) {
                        if (item.key.equals(key) && item.clearable) {
                            onActions.onDismiss(key);
                            break;
                        }
                    }
                }
                // Consume both DOWN and UP; non-clearable notifications are
                // intentionally protected from keyboard dismissal.
                return true;
            }
        }
        if (event.getKeyCode() == KeyEvent.KEYCODE_BACK) {
            if (event.getAction() == KeyEvent.ACTION_UP) handleNativeBack();
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    private int dp(float value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }
}
