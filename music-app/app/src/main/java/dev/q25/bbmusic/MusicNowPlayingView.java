package dev.q25.bbmusic;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.session.MediaController;

/**
 * Standalone presentation port of the BB UI shade's expanded Now Playing:
 * 40dp shuffle/repeat header with the BBOS6 Shuffle icon on the left,
 * half-radial artwork/metadata, 30dp seek strip
 * and 62dp three-button transport. Its drawables are ported unchanged into
 * MusicPlayerChrome, MediaChromeDivider and HalfRadialGradientDrawable.
 *
 * Trackpad hierarchy matches TwoColumnPanel: modes -> seek -> transport.
 * Trackpad confirmation toggles seek edit mode; left/right seek only while
 * editing. This is a View, not a SystemUI hook or independent player.
 */
final class MusicNowPlayingView extends LinearLayout {
    private static final int ZONE_MODE = 0;
    private static final int ZONE_SEEK = 1;
    private static final int ZONE_TRANSPORT = 2;

    private final MusicPlaybackClient client;
    private final MusicArtworkLoader artworkLoader;
    private final ImageView modeShuffle;
    private final TextView modeRepeat;
    private final ImageView artworkImage;
    private final TextView trackTitle;
    private final TextView trackArtist;
    private final TextView elapsed;
    private final TextView total;
    private final SeekBar seekBar;
    private final LinearLayout transportRow;
    private final ImageView[] transportButtons = new ImageView[3];
    private final Handler ticker = new Handler(Looper.getMainLooper());

    private int zone = ZONE_TRANSPORT;
    private int selectedButton = 1;
    private int selectedMode;
    private boolean seekEditing;
    private boolean seekDragging;
    private String shownArtworkUri;
    private long durationMs;
    private boolean active = true;
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!active) return;
            renderProgress();
            ticker.postDelayed(this, 1000);
        }
    };

    MusicNowPlayingView(Context context, MusicPlaybackClient client) {
        super(context);
        this.client = client;
        artworkLoader = new MusicArtworkLoader(context);
        setOrientation(VERTICAL);
        setBackgroundColor(Color.BLACK);
        setFocusable(true);
        setFocusableInTouchMode(true);

        LinearLayout modeHeader = new LinearLayout(context);
        modeHeader.setOrientation(HORIZONTAL);
        modeHeader.setGravity(Gravity.CENTER_VERTICAL);
        modeHeader.setPadding(dp(8), 0, dp(8), 0);
        modeHeader.setBackground(MusicPlayerChrome.modeHeaderBackground(context));
        addView(modeHeader, new LayoutParams(LayoutParams.MATCH_PARENT, dp(40)));

        // Original BBOS6 Shuffle button takes the former "Media" title slot.
        modeShuffle = new ImageView(context);
        modeShuffle.setScaleType(ImageView.ScaleType.FIT_CENTER);
        modeShuffle.setPadding(dp(7), dp(3), dp(7), dp(3));
        modeShuffle.setContentDescription("Shuffle unavailable");
        modeShuffle.setOnClickListener(view -> {
            zone = ZONE_MODE;
            selectedMode = 0;
            requestFocus();
            client.toggleShuffle();
            render();
        });
        modeHeader.addView(modeShuffle, new LayoutParams(dp(48), dp(34)));
        modeHeader.addView(new View(context), new LayoutParams(0, dp(1), 1f));

        modeRepeat = text("Repeat", 12, Color.WHITE);
        modeRepeat.setGravity(Gravity.CENTER);
        modeRepeat.setPadding(dp(9), 0, dp(9), 0);
        modeRepeat.setOnClickListener(view -> {
            zone = ZONE_MODE;
            selectedMode = 1;
            requestFocus();
            client.toggleRepeat();
            render();
        });
        modeHeader.addView(modeRepeat, new LayoutParams(
                LayoutParams.WRAP_CONTENT, dp(28)));

        // The BB UI expanded player uses its artwork stage to consume all
        // available height, preserving the compact fixed-size bottom controls.
        FrameLayout artStage = new FrameLayout(context);
        artStage.setBackground(new HalfRadialGradientDrawable());
        addView(artStage, new LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout artInfo = new LinearLayout(context);
        artInfo.setOrientation(HORIZONTAL);
        artInfo.setGravity(Gravity.CENTER_VERTICAL);
        artInfo.setPadding(dp(10), dp(8), dp(10), dp(8));
        artStage.addView(artInfo, new FrameLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        FrameLayout artArea = new FrameLayout(context);
        artInfo.addView(artArea, new LayoutParams(0, LayoutParams.MATCH_PARENT, 1f));

        artworkImage = new ImageView(context);
        artworkImage.setScaleType(ImageView.ScaleType.FIT_CENTER);
        artworkImage.setPadding(dp(6), dp(6), dp(6), dp(6));
        artworkImage.setContentDescription("Album artwork");
        artArea.addView(artworkImage, new FrameLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        // Same original BBOS6 543.png fallback used in album browse lists.
        // This is replaced only when embedded cover artwork is decoded.
        artworkImage.setImageResource(R.drawable.bbos6_album_no_art);

        LinearLayout trackInfo = new LinearLayout(context);
        trackInfo.setOrientation(VERTICAL);
        trackInfo.setGravity(Gravity.CENTER_VERTICAL);
        trackInfo.setPadding(dp(14), dp(8), dp(8), dp(8));
        artInfo.addView(trackInfo, new LayoutParams(0, LayoutParams.MATCH_PARENT, 1f));

        trackTitle = text("Now playing", 18, Color.WHITE);
        trackTitle.setMaxLines(3);
        trackTitle.setEllipsize(TextUtils.TruncateAt.END);
        trackInfo.addView(trackTitle, new LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

        trackArtist = text("", 13, 0xff99aab8);
        trackArtist.setMaxLines(3);
        trackArtist.setEllipsize(TextUtils.TruncateAt.END);
        trackArtist.setPadding(0, dp(8), 0, 0);
        trackInfo.addView(trackArtist, new LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

        LinearLayout seekRow = new LinearLayout(context);
        seekRow.setOrientation(HORIZONTAL);
        seekRow.setGravity(Gravity.CENTER_VERTICAL);
        seekRow.setPadding(dp(10), 0, dp(10), 0);
        seekRow.setBackground(MusicPlayerChrome.seekBackground(context));
        addView(seekRow, new LayoutParams(LayoutParams.MATCH_PARENT, dp(30)));

        elapsed = text("0:00", 14, 0xff99aab8);
        elapsed.setGravity(Gravity.CENTER_VERTICAL);
        seekRow.addView(elapsed, new LayoutParams(dp(45), LayoutParams.MATCH_PARENT));

        seekBar = new SeekBar(context);
        seekBar.setMax(MediaSeekPolicy.PROGRESS_MAX);
        seekBar.setFocusable(false);
        seekBar.setFocusableInTouchMode(false);
        seekBar.setPadding(dp(32), 0, dp(32), 0);
        seekBar.setProgressTintList(null);
        seekBar.setProgressBackgroundTintList(null);
        seekBar.setSecondaryProgressTintList(null);
        seekBar.setProgressDrawable(MusicPlayerChrome.seekTrack(context));
        seekBar.setThumbTintList(null);
        seekBar.setThumb(MusicPlayerChrome.seekThumb(context, false, false));
        seekBar.setContentDescription("Playback position");
        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onStartTrackingTouch(SeekBar bar) {
                seekDragging = true;
                zone = ZONE_SEEK;
                seekEditing = false;
                requestFocus();
                renderFocus();
            }

            @Override public void onProgressChanged(SeekBar bar,
                    int progress, boolean fromUser) {
                if (fromUser && seekDragging) {
                    elapsed.setText(MediaSeekPolicy.formatTime(
                            MediaSeekPolicy.fromProgress(progress, durationMs)));
                }
            }

            @Override public void onStopTrackingTouch(SeekBar bar) {
                seekDragging = false;
                client.seek(MediaSeekPolicy.fromProgress(bar.getProgress(), durationMs));
                renderProgress();
            }
        });
        seekRow.addView(seekBar, new LayoutParams(0, dp(30), 1f));

        total = text("--:--", 14, 0xff99aab8);
        total.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        seekRow.addView(total, new LayoutParams(dp(52), LayoutParams.MATCH_PARENT));

        transportRow = new LinearLayout(context);
        transportRow.setGravity(Gravity.CENTER_VERTICAL);
        transportRow.setOrientation(HORIZONTAL);
        transportRow.setBackground(MusicPlayerChrome.transportBackground());
        addView(transportRow, new LayoutParams(LayoutParams.MATCH_PARENT, dp(62)));

        String[] descriptions = {"Previous", "Play or pause", "Next"};
        for (int i = 0; i < 3; i++) {
            final int buttonIndex = i;
            ImageView button = new ImageView(context);
            button.setScaleType(ImageView.ScaleType.CENTER);
            button.setImageDrawable(MusicPlayerChrome.transportIcon(context, i, false));
            button.setPadding(dp(10), dp(10), dp(10), dp(10));
            button.setContentDescription(descriptions[i]);
            button.setOnClickListener(view -> {
                zone = ZONE_TRANSPORT;
                selectedButton = buttonIndex;
                seekEditing = false;
                requestFocus();
                activate();
            });
            transportButtons[i] = button;
            transportRow.addView(button, new LayoutParams(
                    0, LayoutParams.MATCH_PARENT, 1f));
        }
        transportRow.setForeground(
                MusicPlayerChrome.transportSeparators(context, transportButtons));
        render();
        ticker.postDelayed(tick, 1000);
    }

    private TextView text(String value, float size, int color) {
        TextView result = new TextView(getContext());
        result.setText(value);
        result.setTextSize(size);
        result.setTextColor(color);
        return result;
    }

    private int dp(float value) {
        return MusicPlayerChrome.dp(getContext(), value);
    }

    void render() {
        if (!active) return;
        MediaController controller = client.controller();
        MediaItem item = controller == null ? null : controller.getCurrentMediaItem();
        if (item == null) {
            trackTitle.setText("Now playing");
            trackArtist.setText("");
            trackArtist.setVisibility(View.GONE);
            updateArtwork(null);
        } else {
            CharSequence title = item.mediaMetadata.title;
            CharSequence artist = item.mediaMetadata.artist;
            trackTitle.setText(title == null || title.length() == 0
                    ? "Now playing" : title);
            trackArtist.setText(artist == null ? "" : artist);
            trackArtist.setVisibility(artist == null || artist.length() == 0
                    ? View.GONE : View.VISIBLE);
            // A MediaSession may omit LocalConfiguration from the
            // controller-facing MediaItem. Our own mediaId preserves the
            // exact SAF URI already approved by MusicFolders.
            Uri artworkSource = item.localConfiguration == null
                    ? null : item.localConfiguration.uri;
            if (artworkSource == null && item.mediaId.startsWith("content://")) {
                artworkSource = Uri.parse(item.mediaId);
            }
            updateArtwork(artworkSource);
        }
        boolean playing = controller != null && controller.getPlayWhenReady();
        transportButtons[1].setImageDrawable(
                MusicPlayerChrome.transportIcon(getContext(), 1, playing));
        transportButtons[1].setContentDescription(playing ? "Pause" : "Play");
        if (controller == null) {
            modeRepeat.setText("Repeat");
        } else {
            int repeat = controller.getRepeatMode();
            modeRepeat.setText(repeat == Player.REPEAT_MODE_ONE ? "Repeat 1"
                    : repeat == Player.REPEAT_MODE_ALL ? "Repeat All" : "Repeat");
        }
        renderProgress();
        renderFocus();
    }

    private void updateArtwork(Uri uri) {
        String key = uri == null ? "" : uri.toString();
        if (key.equals(shownArtworkUri)) return;
        shownArtworkUri = key;
        // Display the bundled original cover whenever embedded art is absent.
        // Reset immediately so a previous track's art is never shown for a
        // new track while the metadata loader is still working.
        artworkImage.setImageResource(R.drawable.bbos6_album_no_art);
        artworkLoader.load(uri, bitmap -> {
            if (!active || !key.equals(shownArtworkUri)) return;
            if (bitmap != null) artworkImage.setImageBitmap(bitmap);
            // A null result keeps the already displayed BBOS6 fallback.
        });
    }

    private void renderProgress() {
        if (!active || seekDragging) return;
        MediaController controller = client.controller();
        if (controller == null) {
            durationMs = 0L;
            elapsed.setText("0:00");
            total.setText("--:--");
            seekBar.setProgress(0);
            seekBar.setEnabled(false);
            return;
        }
        long duration = controller.getDuration();
        durationMs = duration == C.TIME_UNSET || duration < 0L ? 0L : duration;
        long position = Math.max(0L, controller.getCurrentPosition());
        elapsed.setText(MediaSeekPolicy.formatTime(position));
        total.setText(durationMs > 0L
                ? MediaSeekPolicy.formatTime(durationMs) : "--:--");
        seekBar.setProgress(MediaSeekPolicy.toProgress(position, durationMs));
        boolean canSeek = durationMs > 0L && controller.isCurrentMediaItemSeekable();
        seekBar.setEnabled(canSeek);
        seekBar.setAlpha(canSeek ? 1f : 0.4f);
        if (!canSeek && seekEditing) {
            seekEditing = false;
            renderFocus();
        }
    }

    private void renderFocus() {
        boolean focused = hasFocus();
        MediaController controller = client.controller();
        boolean shuffleAvailable = controller != null
                && controller.isCommandAvailable(Player.COMMAND_SET_SHUFFLE_MODE);
        boolean shuffle = shuffleAvailable && controller.getShuffleModeEnabled();
        boolean shuffleFocused = shuffleAvailable && focused
                && zone == ZONE_MODE && selectedMode == 0;
        modeShuffle.setImageResource(!shuffleAvailable
                ? R.drawable.bbos6_shuffle_unavailable
                : shuffle
                ? (shuffleFocused
                        ? R.drawable.bbos6_shuffle_enabled_focused
                        : R.drawable.bbos6_shuffle_enabled)
                : R.drawable.bbos6_shuffle_normal);
        modeShuffle.setContentDescription(!shuffleAvailable ? "Shuffle unavailable"
                : shuffle ? "Shuffle on" : "Shuffle off");
        modeShuffle.setEnabled(shuffleAvailable);
        MusicPlayerChrome.styleMode(getContext(), modeShuffle, shuffle, shuffleFocused);
        // Preserve the supplied PNG's actual colors, including its disabled variant.
        modeShuffle.setAlpha(1f);
        boolean repeat = controller != null
                && controller.getRepeatMode() != Player.REPEAT_MODE_OFF;
        MusicPlayerChrome.styleMode(getContext(), modeRepeat, repeat,
                focused && zone == ZONE_MODE && selectedMode == 1);
        seekBar.setThumb(MusicPlayerChrome.seekThumb(getContext(),
                focused && zone == ZONE_SEEK,
                focused && zone == ZONE_SEEK && seekEditing));
        for (int i = 0; i < transportButtons.length; i++) {
            boolean selected = focused && zone == ZONE_TRANSPORT && selectedButton == i;
            ImageView button = transportButtons[i];
            button.setBackground(selected ? MusicPlayerChrome.transportSelection() : null);
            boolean enabled = controller != null
                    && (i == 0 ? controller.hasPreviousMediaItem()
                    : i == 2 ? controller.hasNextMediaItem() : true);
            button.setAlpha(enabled ? 1f : 0.35f);
        }
    }

    void directional(int code) {
        if (code == KeyEvent.KEYCODE_DPAD_UP) {
            if (seekEditing) return;
            if (zone == ZONE_TRANSPORT) zone = ZONE_SEEK;
            else if (zone == ZONE_SEEK) zone = ZONE_MODE;
        } else if (code == KeyEvent.KEYCODE_DPAD_DOWN) {
            if (seekEditing) return;
            if (zone == ZONE_MODE) zone = ZONE_SEEK;
            else if (zone == ZONE_SEEK) zone = ZONE_TRANSPORT;
        } else if (code == KeyEvent.KEYCODE_DPAD_LEFT
                || code == KeyEvent.KEYCODE_DPAD_RIGHT) {
            int step = code == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : 1;
            if (zone == ZONE_SEEK) {
                if (seekEditing) {
                    MediaController controller = client.controller();
                    if (controller != null && durationMs > 0L) {
                        client.seek(MediaSeekPolicy.seekStep(
                                controller.getCurrentPosition(), durationMs, step));
                    }
                }
            } else if (zone == ZONE_MODE) {
                selectedMode = Math.max(0, Math.min(1, selectedMode + step));
            } else {
                selectedButton = Math.max(0, Math.min(2, selectedButton + step));
            }
        }
        renderFocus();
    }

    void activate() {
        if (zone == ZONE_MODE) {
            if (selectedMode == 0) {
                MediaController controller = client.controller();
                if (controller != null
                        && controller.isCommandAvailable(Player.COMMAND_SET_SHUFFLE_MODE)) {
                    client.toggleShuffle();
                }
            } else client.toggleRepeat();
        } else if (zone == ZONE_SEEK) {
            MediaController controller = client.controller();
            boolean canSeek = controller != null && durationMs > 0L
                    && controller.isCurrentMediaItemSeekable();
            seekEditing = MediaSeekPolicy.toggleTrackpadEditing(
                    true, seekEditing, canSeek);
        } else {
            if (selectedButton == 0) client.previous();
            else if (selectedButton == 1) client.playPause();
            else client.next();
        }
        render();
    }

    @Override public void onWindowFocusChanged(boolean focused) {
        super.onWindowFocusChanged(focused);
        renderFocus();
    }

    void dispose() {
        active = false;
        ticker.removeCallbacks(tick);
        artworkLoader.close();
        artworkImage.setImageDrawable(null);
    }
}
