package dev.q25.bbmusic;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import java.util.List;

/**
 * One reusable, standalone BBOS6 library ListView for every Music page.
 *
 * Owns exactly one native ListView, BaseAdapter, recycled 64dp rows, album
 * thumbnails, selection gradient, touch scrolling and hardware Up/Down
 * navigation. Page hierarchy, count calculations, album selection and
 * actions remain outside this visual component.
 *
 * Uses no BB UI, SystemUI, Vector, root or third-party media application.
 */
final class MusicListView extends ListView {
    interface DataSource {
        int size();
        RowData rowAt(int position);
        void activate(int position);
    }

    /** Presentation values; deliberately independent of music categories. */
    static final class RowData {
        final String title;
        final String subtitle;
        final boolean enabled;
        final boolean showArtwork;
        final String artworkKey;
        final List<Uri> artworkCandidates;

        RowData(String title, String subtitle, boolean enabled,
                boolean showArtwork, String artworkKey,
                List<Uri> artworkCandidates) {
            this.title = title;
            this.subtitle = subtitle;
            this.enabled = enabled;
            this.showArtwork = showArtwork;
            this.artworkKey = artworkKey;
            this.artworkCandidates = artworkCandidates;
        }
    }

    // Shared height for the selection row and its edge-to-edge square artwork.
    private static final int ROW_HEIGHT_DP = 64;
    private static final int[] LIGHT_SELECTION = {
            0xff088eef, 0xff088aef, 0xff0886ef, 0xff0882ef, 0xff087de7,
            0xff0879e7, 0xff1075e7, 0xff1071e7, 0xff106dde, 0xff1069de,
            0xff1065de
    };
    private static final int[] DARK_SELECTION = {
            0xff459fd9, 0xff3285c5, 0xff2368aa
    };

    private static final class RowViews {
        final LinearLayout root;
        final ImageView artwork;
        final Drawable noArtwork;
        final TextView label;
        final TextView count;

        RowViews(LinearLayout root, ImageView artwork, Drawable noArtwork,
                TextView label, TextView count) {
            this.root = root;
            this.artwork = artwork;
            this.noArtwork = noArtwork;
            this.label = label;
            this.count = count;
        }
    }

    private final DataSource data;
    private final BaseAdapter adapter;
    private final MusicListArtworkLoader artworkLoader;
    private final int canvasColor;
    private final int rowColor;
    private final int titleColor;
    private final int[] selectionColors;

    MusicListView(Context context, DataSource data) {
        super(context);
        this.data = data;
        artworkLoader = new MusicListArtworkLoader(context);

        // BBOS6 Music always uses a black canvas and black unfocused rows,
        // regardless of the host's system light/dark setting.
        canvasColor = Color.BLACK;
        rowColor = Color.BLACK;
        titleColor = Color.WHITE;
        int dividerColor = 0xff303030;

        // Keep the existing focus gradient and stroke unchanged, including
        // the original system-theme-dependent gradient selection.
        boolean dark = (getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        selectionColors = dark ? DARK_SELECTION : LIGHT_SELECTION;

        // Preserve the proven BB UI QS-style ListView configuration.
        setPadding(0, 0, 0, 0);
        setBackgroundColor(canvasColor);
        setVerticalScrollBarEnabled(false);
        setFastScrollEnabled(false);
        setItemsCanFocus(false);
        setFocusable(true);
        setFocusableInTouchMode(true);
        setSelector(android.R.color.transparent);
        setDivider(new ColorDrawable(dividerColor));
        setDividerHeight(dp(1));

        adapter = new BaseAdapter() {
            @Override public int getCount() {
                return MusicListView.this.data.size();
            }

            @Override public Object getItem(int position) {
                return MusicListView.this.data.rowAt(position);
            }

            @Override public boolean isEnabled(int position) {
                return MusicListView.this.data.rowAt(position).enabled;
            }

            @Override public long getItemId(int position) {
                return position;
            }

            @Override public View getView(int position, View convertView,
                    ViewGroup parent) {
                View view = convertView == null ? createRowView() : convertView;
                RowViews views = (RowViews) view.getTag();
                bindRow(views, MusicListView.this.data.rowAt(position), position);
                return view;
            }
        };
        setAdapter(adapter);

        setOnItemClickListener((parent, row, position, id) -> data.activate(position));
        setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent,
                    View row, int position, long id) {
                styleVisibleRows();
            }

            @Override public void onNothingSelected(AdapterView<?> parent) {
                styleVisibleRows();
            }
        });
        setOnFocusChangeListener((view, hasFocus) -> styleVisibleRows());
        setOnScrollListener(new AbsListView.OnScrollListener() {
            @Override public void onScrollStateChanged(AbsListView view, int state) { }

            @Override public void onScroll(AbsListView view, int firstVisibleItem,
                    int visibleItemCount, int totalItemCount) {
                styleVisibleRows();
            }
        });
    }

    int canvasColor() {
        return canvasColor;
    }

    void notifyRowsChanged() {
        adapter.notifyDataSetChanged();
    }

    /** Rebind counts and covers in place without resetting selection or scroll. */
    void refreshVisibleRows() {
        int first = getFirstVisiblePosition();
        for (int child = 0; child < getChildCount(); child++) {
            View row = getChildAt(child);
            int position = first + child;
            if (!(row.getTag() instanceof RowViews)
                    || position < 0 || position >= data.size()) continue;
            bindRow((RowViews) row.getTag(), data.rowAt(position), position);
        }
    }

    void refreshSelection() {
        styleVisibleRows();
    }

    void clearArtworkCache() {
        artworkLoader.clear();
    }

    void dispose() {
        artworkLoader.close();
    }

    /**
     * Exactly the prior MusicActivity QS-style trackpad routing.
     * ListView handles the actual one-row movement; this only restores
     * a visible selection after touch scrolling clears it.
     */
    boolean handleVerticalKey(KeyEvent event) {
        int code = event.getKeyCode();
        if (code != KeyEvent.KEYCODE_DPAD_UP && code != KeyEvent.KEYCODE_DPAD_DOWN) {
            return false;
        }
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            int selected = getSelectedItemPosition();
            if (selected < 0) {
                int first = Math.max(0, getFirstVisiblePosition());
                int last = Math.min(data.size() - 1, getLastVisiblePosition());
                int target = code == KeyEvent.KEYCODE_DPAD_UP
                        ? Math.max(first, last) : first;
                int visibleIndex = target - getFirstVisiblePosition();
                View visible = visibleIndex >= 0 && visibleIndex < getChildCount()
                        ? getChildAt(visibleIndex) : null;
                int top = visible == null ? 0 : visible.getTop();
                requestFocus();
                setSelectionFromTop(target, top);
            } else {
                if (!hasFocus()) requestFocus();
                dispatchKeyEvent(event);
            }
        }
        return true;
    }

    private View createRowView() {
        LinearLayout row = new LinearLayout(getContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        // Selection spans the entire row; art touches all three outer edges.
        row.setPadding(0, 0, 0, 0);
        row.setLayoutParams(new AbsListView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(ROW_HEIGHT_DP)));
        row.setFocusable(false);

        ImageView art = new ImageView(getContext());
        art.setScaleType(ImageView.ScaleType.CENTER_CROP);
        art.setFocusable(false);
        art.setContentDescription("Album artwork");
        art.setVisibility(View.GONE);
        Drawable placeholder = new MusicCoverPlaceholderDrawable();
        LinearLayout.LayoutParams artLayout =
                new LinearLayout.LayoutParams(dp(ROW_HEIGHT_DP), dp(ROW_HEIGHT_DP));
        row.addView(art, artLayout);

        LinearLayout textColumn = new LinearLayout(getContext());
        textColumn.setOrientation(LinearLayout.VERTICAL);
        textColumn.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(textColumn, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        TextView title = new TextView(getContext());
        title.setTextSize(15);
        title.setPadding(dp(16), 0, dp(16), 0);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        textColumn.addView(title, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView count = new TextView(getContext());
        count.setTextSize(12);
        count.setPadding(dp(16), 0, dp(16), 0);
        count.setSingleLine(true);
        count.setEllipsize(TextUtils.TruncateAt.END);
        textColumn.addView(count, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        row.setTag(new RowViews(row, art, placeholder, title, count));
        return row;
    }

    private void bindRow(RowViews views, RowData row, int position) {
        views.label.setText(row.title);
        String detail = row.subtitle;
        views.count.setVisibility(detail == null ? View.GONE : View.VISIBLE);
        views.count.setText(detail == null ? "" : detail);
        views.root.setContentDescription(
                detail == null ? row.title : row.title + ", " + detail);
        bindArtwork(views, row);
        styleRow(views, position);
    }

    private void bindArtwork(RowViews views, RowData row) {
        if (!row.showArtwork) {
            views.artwork.setVisibility(View.GONE);
            views.artwork.setTag(null);
            views.artwork.setImageDrawable(null);
            return;
        }
        views.artwork.setVisibility(View.VISIBLE);
        if (row.artworkKey == null
                || row.artworkCandidates == null || row.artworkCandidates.isEmpty()) {
            views.artwork.setTag(null);
            views.artwork.setImageDrawable(views.noArtwork);
            return;
        }
        String requestKey = row.artworkKey;
        if (!requestKey.equals(views.artwork.getTag())) {
            views.artwork.setTag(requestKey);
            views.artwork.setImageDrawable(views.noArtwork);
        }
        // A recycled image can never display a previous row's cover.
        artworkLoader.request(requestKey, row.artworkCandidates, image -> {
            if (!requestKey.equals(views.artwork.getTag())) return;
            if (image == null) views.artwork.setImageDrawable(views.noArtwork);
            else views.artwork.setImageBitmap(image);
        });
    }

    private void styleVisibleRows() {
        int first = getFirstVisiblePosition();
        for (int child = 0; child < getChildCount(); child++) {
            View row = getChildAt(child);
            if (!(row.getTag() instanceof RowViews)) continue;
            int position = first + child;
            if (position < 0 || position >= data.size()) continue;
            styleRow((RowViews) row.getTag(), position);
        }
    }

    private void styleRow(RowViews views, int position) {
        boolean focused = hasFocus() && getSelectedItemPosition() == position;
        if (focused) {
            GradientDrawable highlight = new GradientDrawable(
                    GradientDrawable.Orientation.TOP_BOTTOM, selectionColors);
            highlight.setCornerRadius(0);
            views.root.setBackground(highlight);
        } else {
            views.root.setBackgroundColor(rowColor);
        }
        views.label.setTextColor(focused ? Color.WHITE : titleColor);
        views.count.setTextColor(focused ? 0xffedf4ff : (rowColor == Color.WHITE
                ? 0xff66798a : 0xffb3c1ce));
    }

    private int dp(float value) {
        return Math.round(getResources().getDisplayMetrics().density * value);
    }
}
