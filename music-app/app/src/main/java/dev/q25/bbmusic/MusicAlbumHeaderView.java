package dev.q25.bbmusic;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.List;

/**
 * BBOS6 album-song-page header, separate from the song ListView adapter.
 *
 * The cover is flush with the top and left of the content area, directly
 * below the existing Android status-bar inset. It uses the exact same
 * selected-folder album artwork cache as the music browse rows.
 */
final class MusicAlbumHeaderView extends LinearLayout {
    private static final int CONTENT_HEIGHT_DP = 88;
    private static final int SEPARATOR_HEIGHT_DP = 1;
    private static final int SEPARATOR_COLOR = 0xff515251;
    private final MusicListView list;
    private final ImageView cover;
    private final TextView title;
    private final TextView artist;
    private final TextView genre;
    private final TextView count;
    private final Drawable noCover;
    private int bindingGeneration;

    MusicAlbumHeaderView(Context context, MusicListView list) {
        super(context);
        this.list = list;
        noCover = context.getDrawable(R.drawable.bbos6_album_no_art);
        setOrientation(VERTICAL);
        setPadding(0, 0, 0, 0);
        setBackgroundColor(Color.BLACK);
        setFocusable(false);

        // The content starts flush at the screen's left edge and just
        // below the existing status-bar inset. The rule is a separate
        // full-width line below the art, not an inset around the cover.
        LinearLayout content = new LinearLayout(context);
        content.setOrientation(HORIZONTAL);
        content.setGravity(Gravity.CENTER_VERTICAL);
        content.setPadding(0, 0, 0, 0);
        addView(content, new LayoutParams(
                LayoutParams.MATCH_PARENT, dp(CONTENT_HEIGHT_DP)));

        cover = new ImageView(context);
        cover.setScaleType(ImageView.ScaleType.CENTER_CROP);
        cover.setContentDescription("Album artwork");
        cover.setImageDrawable(noCover);
        content.addView(cover, new LayoutParams(
                dp(CONTENT_HEIGHT_DP), dp(CONTENT_HEIGHT_DP)));

        LinearLayout info = new LinearLayout(context);
        info.setOrientation(VERTICAL);
        info.setGravity(Gravity.CENTER_VERTICAL);
        info.setPadding(dp(10), 0, dp(4), 0);
        content.addView(info, new LayoutParams(0, LayoutParams.MATCH_PARENT, 1f));

        View separator = new View(context);
        separator.setBackgroundColor(SEPARATOR_COLOR);
        addView(separator, new LayoutParams(
                LayoutParams.MATCH_PARENT, dp(SEPARATOR_HEIGHT_DP)));

        title = line(info, 16, Color.WHITE);
        artist = line(info, 12, 0xffc9c9c9);
        genre = line(info, 12, 0xffa9a9a9);
        count = line(info, 12, 0xffc9c9c9);
    }

    static int heightDp() {
        return CONTENT_HEIGHT_DP + SEPARATOR_HEIGHT_DP;
    }

    private TextView line(LinearLayout column, float sp, int color) {
        TextView text = new TextView(getContext());
        text.setTextColor(color);
        text.setTextSize(sp);
        text.setSingleLine(true);
        text.setEllipsize(TextUtils.TruncateAt.END);
        column.addView(text, new LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
        return text;
    }

    void bind(String albumTitle, String albumArtist, String albumGenre,
            int songCount, String artworkKey, List<Uri> albumTracks) {
        final int binding = ++bindingGeneration;
        title.setText(albumTitle);
        artist.setText(albumArtist);
        String genreLabel = albumGenre == null || albumGenre.trim().isEmpty()
                || "Unknown Genre".equalsIgnoreCase(albumGenre)
                ? "Unknown" : albumGenre;
        genre.setText("(" + genreLabel + ")");
        count.setText(songCount + (songCount == 1 ? " Song" : " Songs"));
        cover.setImageDrawable(noCover);

        if (artworkKey != null && albumTracks != null && !albumTracks.isEmpty()) {
            list.loadAlbumArtwork(artworkKey, albumTracks, image -> {
                if (binding != bindingGeneration) return;
                if (image != null) cover.setImageBitmap(image);
                else cover.setImageDrawable(noCover);
            });
        }
    }

    /** Invalidate callbacks before the header is hidden for another page. */
    void clear() {
        bindingGeneration++;
        cover.setImageDrawable(noCover);
    }

    private int dp(float value) {
        return Math.round(getResources().getDisplayMetrics().density * value);
    }
}
