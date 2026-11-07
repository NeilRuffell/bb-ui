package dev.q25.bbmusic;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Bitmap;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.window.OnBackInvokedCallback;
import android.window.OnBackInvokedDispatcher;
import android.widget.AbsListView;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * Standalone BBOS6 Music main menu.
 *
 * Mirrors the native ListView + BaseAdapter, visible-row selection styling,
 * and one-item trackpad navigation used by BB UI's Quick Settings panel,
 * without importing or depending on BB UI, Vector or SystemUI.
 *
 * Local music is read only from user-selected SAF folder trees.
 * Symfonium is never required or started.
 * The UI uses the same recycled ListView row on every browse page.
 */
public final class MusicActivity extends Activity {
    private static final String TAG = "BBMusic";
    private static final int ADD_FOLDER_REQUEST = 6102;
    private static final int ROW_MENU = 0;
    private static final int ROW_SONG = 1;
    private static final int ROW_GROUP = 2;
    private static final int ROW_MESSAGE = 3;
    private static final int ROW_SETTING = 4;
    private static final String[] MENU = {
            "All Songs",
            "Artists",
            "Albums",
            "Genres",
            "Playlists",
            "Shuffle Songs",
            "Settings"
    };

    // BB UI's QS palette and selected-state gradient are replicated locally:
    // the standalone music APK has no dependency on BB UI at build or runtime.
    private static final int BLUE_BORDER = 0xffb4e2fb;
    private static final int[] LIGHT_SELECTION = {
            0xff088eef, 0xff088aef, 0xff0886ef, 0xff0882ef, 0xff087de7,
            0xff0879e7, 0xff1075e7, 0xff1071e7, 0xff106dde, 0xff1069de,
            0xff1065de
    };
    private static final int[] DARK_SELECTION = {
            0xff459fd9, 0xff3285c5, 0xff2368aa
    };

    private ListView list;
    private BaseAdapter adapter;
    private TrackpadPressHandler trackpadPress;
    private LocalMusicSource localSource;
    private MusicListArtworkLoader listArtwork;
    private Map<String, List<Uri>> albumArtworkSources = Collections.emptyMap();
    private MusicPlaybackClient playback;
    private FrameLayout safeViewport;
    private MusicNowPlayingView nowPlaying;
    private boolean nowPlayingVisible;
    private int savedListPosition;
    private int savedListTop;
    private boolean pendingShuffleStart;
    private List<LocalMusicSource.Song> localSongs;
    private final int[] localCategoryCounts = new int[4];
    private boolean loading;
    private int pendingMenu = -1;
    private final ArrayDeque<Page> backStack = new ArrayDeque<>();
    private Page currentPage;
    private OnBackInvokedCallback nestedBackCallback;
    private int canvasColor;
    private int rowColor;
    private int titleColor;
    private int dividerColor;
    private int[] selectionColors;

    private static final class MenuRow {
        final String title;
        final int kind;
        final int menuIndex;
        final int groupCategory;
        final List<LocalMusicSource.Song> songs;
        final LocalMusicSource.Song song;

        MenuRow(String title, int kind, int menuIndex, int groupCategory,
                List<LocalMusicSource.Song> songs, LocalMusicSource.Song song) {
            this.title = title;
            this.kind = kind;
            this.menuIndex = menuIndex;
            this.groupCategory = groupCategory;
            this.songs = songs;
            this.song = song;
        }

        static MenuRow root(String name, int index) {
            return new MenuRow(name, ROW_MENU, index, -1, null, null);
        }

        static MenuRow group(String name, int category,
                List<LocalMusicSource.Song> songs) {
            return new MenuRow(name, ROW_GROUP, -1, category, songs, null);
        }

        static MenuRow song(LocalMusicSource.Song song) {
            return new MenuRow(song.title, ROW_SONG, -1, -1, null, song);
        }

        static MenuRow message(String message) {
            return new MenuRow(message, ROW_MESSAGE, -1, -1, null, null);
        }

        static MenuRow setting(String title) {
            return new MenuRow(title, ROW_SETTING, -1, -1, null, null);
        }
    }

    private static final class Page {
        final String name;
        final List<MenuRow> rows;
        int selected;
        int selectedTop;

        Page(String name, List<MenuRow> rows) {
            this.name = name;
            this.rows = rows;
        }
    }

    private static final class Group {
        final String label;
        final ArrayList<LocalMusicSource.Song> songs = new ArrayList<>();
        Group(String label) { this.label = label; }
    }

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

    // Artist/genre representative: alphabetical first album title, with
    // deterministic tie-breaking for identical album names.
    private static final Comparator<LocalMusicSource.Song> FIRST_ALBUM =
            Comparator.comparing((LocalMusicSource.Song song) -> song.album,
                    String.CASE_INSENSITIVE_ORDER)
                    .thenComparing(song -> song.album)
                    .thenComparing(song -> song.artist, String.CASE_INSENSITIVE_ORDER)
                    .thenComparing(song -> song.artist)
                    .thenComparing(song -> song.uri.toString());

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        boolean dark = (getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        canvasColor = dark ? 0xff10141a : 0xfff4f6f8;
        rowColor = dark ? 0xff283442 : Color.WHITE;
        titleColor = dark ? Color.WHITE : 0xff182838;
        dividerColor = dark ? 0xff476f8f : 0xff9eafbe;
        selectionColors = dark ? DARK_SELECTION : LIGHT_SELECTION;

        trackpadPress = new TrackpadPressHandler(this::activateSelected,
                () -> { /* Long-press behavior will be specified later. */ });
        localSource = new LocalMusicSource(this);
        listArtwork = new MusicListArtworkLoader(this);
        playback = new MusicPlaybackClient(this, new MusicPlaybackClient.Listener() {
            @Override public void onPlaybackChanged() {
                if (nowPlayingVisible && nowPlaying != null) nowPlaying.render();
            }

            @Override public void onPlaybackError(String message) {
                Toast.makeText(MusicActivity.this, message, Toast.LENGTH_LONG).show();
            }
        });
        ArrayList<MenuRow> rootRows = new ArrayList<>();
        for (int index = 0; index < MENU.length; index++) {
            rootRows.add(MenuRow.root(MENU[index], index));
        }
        currentPage = new Page("Music", rootRows);

        list = new ListView(this);
        // Rows and their selection backgrounds must span the full content width.
        list.setPadding(0, 0, 0, 0);
        list.setBackgroundColor(canvasColor);
        list.setVerticalScrollBarEnabled(false);
        list.setFastScrollEnabled(false);
        list.setItemsCanFocus(false);
        list.setFocusable(true);
        list.setFocusableInTouchMode(true);
        list.setSelector(android.R.color.transparent);
        list.setDivider(new ColorDrawable(dividerColor));
        list.setDividerHeight(dp(1));
        adapter = new BaseAdapter() {
            @Override public int getCount() {
                return currentPage.rows.size();
            }

            @Override public Object getItem(int position) {
                return currentPage.rows.get(position);
            }

            @Override public boolean isEnabled(int position) {
                return currentPage.rows.get(position).kind != ROW_MESSAGE;
            }

            @Override public long getItemId(int position) {
                return position;
            }

            @Override public View getView(int position, View convertView,
                    ViewGroup parent) {
                View view = convertView == null ? createRowView() : convertView;
                RowViews views = (RowViews) view.getTag();
                bindRow(views, currentPage.rows.get(position), position);
                return view;
            }
        };
        list.setAdapter(adapter);

        list.setOnItemClickListener((parent, row, position, id) -> activate(position));
        list.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent,
                    View row, int position, long id) {
                styleVisibleRows();
            }

            @Override public void onNothingSelected(AdapterView<?> parent) {
                styleVisibleRows();
            }
        });
        list.setOnFocusChangeListener((view, hasFocus) -> styleVisibleRows());
        list.setOnScrollListener(new AbsListView.OnScrollListener() {
            @Override public void onScrollStateChanged(AbsListView view, int state) { }

            @Override public void onScroll(AbsListView view, int firstVisibleItem,
                    int visibleItemCount, int totalItemCount) {
                styleVisibleRows();
            }
        });

        // Reserve the standard system-window insets at the outer container.
        // The ListView itself retains its unmodified scroll/focus behavior.
        // On edge-to-edge Android versions, this keeps rows and their selected
        // backgrounds beneath the visible status bar without fixed offsets.
        safeViewport = new FrameLayout(this);
        safeViewport.setBackgroundColor(canvasColor);
        safeViewport.setFitsSystemWindows(true);
        safeViewport.setClipChildren(true);
        safeViewport.setClipToPadding(true);
        safeViewport.addView(list, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(safeViewport);

        // Keep native list navigation in charge: no custom scroll amounts.
        list.post(() -> {
            if (isFinishing() || isDestroyed()) return;
            list.requestFocus();
            list.setSelectionFromTop(0, 0);
            styleVisibleRows();
        });

        // Derive home-menu counts from precisely the selected folder trees.
        // No folder grants means zero music queries and zero children.
        if (MusicFolders.selectedTrees(this).isEmpty()) {
            localSongs = Collections.emptyList();
        } else {
            startLocalScan(false);
        }
    }

    private View createRowView() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        // The cover touches every edge of the selected row. Only labels
        // have their own internal inset, not the row or artwork.
        row.setPadding(0, 0, 0, 0);
        row.setLayoutParams(new AbsListView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(55)));
        row.setFocusable(false);

        // Only song/artist/album/genre lists have album art. The root
        // menu and Settings retain the original label-only presentation.
        ImageView art = new ImageView(this);
        art.setScaleType(ImageView.ScaleType.CENTER_CROP);
        art.setFocusable(false);
        art.setContentDescription("Album artwork");
        art.setVisibility(View.GONE);
        Drawable placeholder = new MusicCoverPlaceholderDrawable();
        // Full 55dp row height and flush to the screen's left edge.
        // No inset between artwork and the square selection background.
        LinearLayout.LayoutParams artLayout =
                new LinearLayout.LayoutParams(dp(55), dp(55));
        row.addView(art, artLayout);

        LinearLayout textColumn = new LinearLayout(this);
        textColumn.setOrientation(LinearLayout.VERTICAL);
        textColumn.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(textColumn, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        TextView title = new TextView(this);
        title.setTextSize(15);
        title.setPadding(dp(16), 0, dp(16), 0);
        title.setSingleLine(true);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        textColumn.addView(title, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView count = new TextView(this);
        count.setTextSize(12);
        count.setPadding(dp(16), 0, dp(16), 0);
        count.setSingleLine(true);
        count.setEllipsize(android.text.TextUtils.TruncateAt.END);
        textColumn.addView(count, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        row.setTag(new RowViews(row, art, placeholder, title, count));
        return row;
    }

    /** Rebind visible count labels in place without disturbing ListView selection. */
    private void refreshVisibleRows() {
        if (list == null) return;
        int first = list.getFirstVisiblePosition();
        for (int child = 0; child < list.getChildCount(); child++) {
            View row = list.getChildAt(child);
            int position = first + child;
            if (!(row.getTag() instanceof RowViews)
                    || position < 0 || position >= currentPage.rows.size()) continue;
            bindRow((RowViews) row.getTag(), currentPage.rows.get(position), position);
        }
    }

    private void bindRow(RowViews views, MenuRow row, int position) {
        views.label.setText(row.title);
        String detail = childCountLabel(row);
        views.count.setVisibility(detail == null ? View.GONE : View.VISIBLE);
        views.count.setText(detail == null ? "" : detail);
        views.root.setContentDescription(
                detail == null ? row.title : row.title + ", " + detail);
        bindArtwork(views, row);
        styleRow(views, position);
    }

    private void bindArtwork(RowViews views, MenuRow row) {
        if (row.kind != ROW_GROUP && row.kind != ROW_SONG) {
            views.artwork.setVisibility(View.GONE);
            views.artwork.setTag(null);
            views.artwork.setImageDrawable(null);
            return;
        }
        views.artwork.setVisibility(View.VISIBLE);
        String albumKey = albumKeyForRow(row);
        List<Uri> candidates = albumKey == null
                ? null : albumArtworkSources.get(albumKey);
        if (candidates == null || candidates.isEmpty()) {
            views.artwork.setTag(null);
            views.artwork.setImageDrawable(views.noArtwork);
            return;
        }
        String requestKey = "album:" + albumKey;
        if (!requestKey.equals(views.artwork.getTag())) {
            views.artwork.setTag(requestKey);
            views.artwork.setImageDrawable(views.noArtwork);
        }
        // The view's tag protects against asynchronous results arriving
        // after the native ListView recycles a row for a different album.
        listArtwork.request(requestKey, candidates, image -> {
            if (!requestKey.equals(views.artwork.getTag())) return;
            if (image == null) views.artwork.setImageDrawable(views.noArtwork);
            else views.artwork.setImageBitmap(image);
        });
    }

    private String albumKeyForRow(MenuRow row) {
        if (row.kind == ROW_SONG) return artworkAlbumKey(row.song);
        if (row.kind != ROW_GROUP || row.songs == null || row.songs.isEmpty()) {
            return null;
        }
        if (row.groupCategory == 2) {
            // The album entry always displays its own album art.
            return artworkAlbumKey(row.songs.get(0));
        }
        // Artists and genres take the artwork of their first alphabetical
        // album. Missing art on that album never selects a later album.
        return artworkAlbumKey(Collections.min(row.songs, FIRST_ALBUM));
    }

    private static String artworkAlbumKey(LocalMusicSource.Song song) {
        // No album tag means no verified album association. Such tracks must
        // not borrow a different untagged song's embedded image.
        if ("Unknown Album".equalsIgnoreCase(song.album)) {
            return "untagged:" + song.uri;
        }
        return groupKey(song, 2);
    }

    /**
     * Index artwork candidates from the selected-folder scan only.
     * All songs in an album share one cover. If its first track has no
     * embedded image, the loader tries the remaining tracks OF THAT ALBUM.
     */
    private void updateAlbumArtworkSources() {
        if (localSongs == null || localSongs.isEmpty()) {
            albumArtworkSources = Collections.emptyMap();
            return;
        }
        Map<String, ArrayList<LocalMusicSource.Song>> byAlbum = new LinkedHashMap<>();
        for (LocalMusicSource.Song song : localSongs) {
            String albumKey = artworkAlbumKey(song);
            byAlbum.computeIfAbsent(albumKey, unused -> new ArrayList<>()).add(song);
        }
        Map<String, List<Uri>> indexed = new LinkedHashMap<>();
        Comparator<LocalMusicSource.Song> trackOrder =
                Comparator.comparingInt((LocalMusicSource.Song song) ->
                        song.trackNumber > 0 ? song.trackNumber : Integer.MAX_VALUE)
                        .thenComparing(song -> song.title, String.CASE_INSENSITIVE_ORDER)
                        .thenComparing(song -> song.uri.toString());
        for (Map.Entry<String, ArrayList<LocalMusicSource.Song>> album
                : byAlbum.entrySet()) {
            album.getValue().sort(trackOrder);
            ArrayList<Uri> uris = new ArrayList<>();
            for (LocalMusicSource.Song song : album.getValue()) {
                uris.add(song.uri);
            }
            indexed.put(album.getKey(), Collections.unmodifiableList(uris));
        }
        albumArtworkSources = indexed;
    }

    private String childCountLabel(MenuRow row) {
        if (row.kind == ROW_GROUP) {
            return countLabel(row.songs.size(), "Song", "Songs");
        }
        if (row.kind != ROW_MENU) return null;
        if (row.menuIndex < 4) {
            if (localSongs == null) return loading ? "Scanning..." : "Unavailable";
            int count = row.menuIndex == 0
                    ? localSongs.size() : localCategoryCounts[row.menuIndex];
            return row.menuIndex == 0
                    ? countLabel(count, "Song", "Songs")
                    : countLabel(count, "Item", "Items");
        }
        if (row.menuIndex == 4) {
            // Playlist storage is not implemented, hence no playlist children.
            return "0 Items";
        }
        if (row.menuIndex == 6) {
            // Settings has one implemented child: Add folders.
            return "1 Item";
        }
        // Shuffle Songs is an action, not a container.
        return null;
    }

    private static String countLabel(int count, String singular, String plural) {
        return count + " " + (count == 1 ? singular : plural);
    }

    private void updateLocalCategoryCounts() {
        if (localSongs == null) return;
        localCategoryCounts[0] = localSongs.size();
        for (int category = 1; category <= 3; category++) {
            Set<String> keys = new HashSet<>();
            for (LocalMusicSource.Song song : localSongs) {
                keys.add(groupKey(song, category));
            }
            localCategoryCounts[category] = keys.size();
        }
        // No notifyDataSetChanged(): a count update must not reset focus
        // or the scroll position of the user's selected row.
        refreshVisibleRows();
    }

    private void styleVisibleRows() {
        if (list == null) return;
        int first = list.getFirstVisiblePosition();
        for (int child = 0; child < list.getChildCount(); child++) {
            View row = list.getChildAt(child);
            if (!(row.getTag() instanceof RowViews)) continue;
            int position = first + child;
            if (position < 0 || position >= currentPage.rows.size()) continue;
            styleRow((RowViews) row.getTag(), position);
        }
    }

    private void styleRow(RowViews views, int position) {
        boolean focused = list.hasFocus() && list.getSelectedItemPosition() == position;
        if (focused) {
            GradientDrawable highlight = new GradientDrawable(
                    GradientDrawable.Orientation.TOP_BOTTOM, selectionColors);
            highlight.setCornerRadius(0);
            highlight.setStroke(dp(1), BLUE_BORDER);
            views.root.setBackground(highlight);
        } else {
            views.root.setBackgroundColor(rowColor);
        }
        views.label.setTextColor(focused ? Color.WHITE : titleColor);
        views.count.setTextColor(focused ? 0xffedf4ff : (rowColor == Color.WHITE
                ? 0xff66798a : 0xffb3c1ce));
    }

    private void activateSelected() {
        if (nowPlayingVisible) {
            if (nowPlaying != null) nowPlaying.activate();
        } else {
            activate(list.getSelectedItemPosition());
        }
    }

    private void activate(int position) {
        if (position < 0 || position >= currentPage.rows.size()) return;
        MenuRow row = currentPage.rows.get(position);
        if (row.kind == ROW_MENU) {
            if (row.menuIndex < 4) {
                openLocalCategory(row.menuIndex);
            } else if (row.menuIndex == 4) {
                Toast.makeText(this, "Playlists are not implemented yet",
                        Toast.LENGTH_SHORT).show();
            } else if (row.menuIndex == 5) {
                shuffleAllSongs();
            } else if (row.menuIndex == 6) {
                ArrayList<MenuRow> settings = new ArrayList<>();
                settings.add(MenuRow.setting("Add folders"));
                navigateTo(new Page("Settings", settings));
            }
        } else if (row.kind == ROW_SETTING) {
            showFolderPicker();
        } else if (row.kind == ROW_GROUP) {
            List<LocalMusicSource.Song> groupSongs = new ArrayList<>(row.songs);
            if (row.groupCategory == 2) {
                // Tagged track numbers provide album ordering where available.
                groupSongs.sort(Comparator.comparingInt(song -> song.trackNumber));
            }
            navigateTo(new Page(row.title, songRows(groupSongs)));
        } else if (row.kind == ROW_SONG) {
            // Play this track within its currently displayed folder-scoped
            // song list, preserving the order for native Previous/Next.
            ArrayList<LocalMusicSource.Song> queue = new ArrayList<>();
            int startIndex = -1;
            for (MenuRow item : currentPage.rows) {
                if (item.kind != ROW_SONG) continue;
                if (item == row) startIndex = queue.size();
                queue.add(item.song);
            }
            if (startIndex >= 0) {
                openNowPlaying();
                playback.playSongs(queue, startIndex, false);
            }
        }
    }

    private void shuffleAllSongs() {
        if (MusicFolders.selectedTrees(this).isEmpty()) {
            Toast.makeText(this, "Add a music folder in Settings first",
                    Toast.LENGTH_LONG).show();
            return;
        }
        if (localSongs == null) {
            pendingShuffleStart = true;
            startLocalScan(true);
            return;
        }
        if (localSongs.isEmpty()) {
            Toast.makeText(this, "No songs in selected folders",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        openNowPlaying();
        int index = new Random().nextInt(localSongs.size());
        playback.playSongs(localSongs, index, true);
    }

    private void openNowPlaying() {
        if (nowPlayingVisible) return;
        savedListPosition = list.getSelectedItemPosition();
        View selectedRow = list.getSelectedView();
        savedListTop = selectedRow == null ? 0 : selectedRow.getTop();
        safeViewport.removeView(list);
        nowPlaying = new MusicNowPlayingView(this, playback);
        safeViewport.addView(nowPlaying, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        nowPlayingVisible = true;
        ensureBackCallback();
        nowPlaying.requestFocus();
        nowPlaying.render();
    }

    private void closeNowPlaying() {
        if (!nowPlayingVisible) return;
        nowPlayingVisible = false;
        if (nowPlaying != null) {
            safeViewport.removeView(nowPlaying);
            nowPlaying.dispose();
            nowPlaying = null;
        }
        safeViewport.addView(list, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        if (backStack.isEmpty()) unregisterNestedBack();
        list.post(() -> {
            if (nowPlayingVisible || isFinishing() || isDestroyed()) return;
            list.requestFocus();
            list.setSelectionFromTop(Math.max(0, savedListPosition), savedListTop);
            styleVisibleRows();
        });
    }

    @Override protected void onStart() {
        super.onStart();
        playback.connect();
    }

    private void showFolderPicker() {
        // Folder choice is made by Android's trusted document picker. Only
        // the explicitly selected tree, not device-wide storage, is granted.
        Intent picker = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        picker.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        picker.putExtra(Intent.EXTRA_LOCAL_ONLY, true);
        try {
            startActivityForResult(picker, ADD_FOLDER_REQUEST);
        } catch (RuntimeException exception) {
            Log.w(TAG, "Cannot open Android folder picker", exception);
            Toast.makeText(this, "Folder picker unavailable", Toast.LENGTH_LONG).show();
        }
    }

    @Override
    @SuppressWarnings("deprecation")
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != ADD_FOLDER_REQUEST || resultCode != RESULT_OK
                || data == null || data.getData() == null) return;
        Uri selectedTree = data.getData();
        if (!DocumentsContract.isTreeUri(selectedTree)) {
            Toast.makeText(this, "Invalid folder selection",
                    Toast.LENGTH_LONG).show();
            return;
        }
        try {
            // Persist read permission only; BB Music never modifies files.
            getContentResolver().takePersistableUriPermission(selectedTree,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION);
            boolean added = MusicFolders.add(this, selectedTree);
            if (added) {
                // Invalidate old lists and stop accepting any pending scan.
                // New scans can read only the updated authorized allowlist.
                localSource.invalidate();
                localSongs = null;
                albumArtworkSources = Collections.emptyMap();
                listArtwork.clear();
                loading = false;
                pendingMenu = -1;
                startLocalScan(false);
                Log.i(TAG, "Selected music folder added; total="
                        + MusicFolders.selectedTrees(this).size());
            }
            Toast.makeText(this, added ? "Music folder added"
                    : "Folder already selected", Toast.LENGTH_SHORT).show();
        } catch (SecurityException | IllegalStateException exception) {
            Log.w(TAG, "Cannot persist selected folder", exception);
            Toast.makeText(this, "Unable to save folder access",
                    Toast.LENGTH_LONG).show();
        }
    }

    private void openLocalCategory(int menuIndex) {
        pendingMenu = menuIndex;
        if (MusicFolders.selectedTrees(this).isEmpty()) {
            pendingMenu = -1;
            ArrayList<MenuRow> message = new ArrayList<>();
            message.add(MenuRow.message("No folders selected"));
            navigateTo(new Page(MENU[menuIndex], message));
            return;
        }
        if (localSongs != null) {
            showLocalCategory(menuIndex);
        } else if (!loading) {
            startLocalScan(true);
        }
    }

    private void startLocalScan(boolean showFeedback) {
        if (loading) return;
        if (MusicFolders.selectedTrees(this).isEmpty()) {
            localSongs = Collections.emptyList();
            updateLocalCategoryCounts();
            return;
        }
        loading = true;
        localSongs = null;
        refreshVisibleRows();
        if (showFeedback) {
            Toast.makeText(this, "Reading selected music folders...",
                    Toast.LENGTH_SHORT).show();
        }
        localSource.load(new LocalMusicSource.Listener() {
            @Override public void onLoaded(List<LocalMusicSource.Song> songs) {
                loading = false;
                localSongs = songs;
                updateAlbumArtworkSources();
                updateLocalCategoryCounts();
                Log.i(TAG, "Selected-folder music ready: " + songs.size() + " tracks");
                if (pendingShuffleStart) {
                    pendingShuffleStart = false;
                    shuffleAllSongs();
                }
                int menu = pendingMenu;
                pendingMenu = -1;
                if (menu >= 0 && backStack.isEmpty()) showLocalCategory(menu);
            }

            @Override public void onError(String reason) {
                loading = false;
                pendingMenu = -1;
                pendingShuffleStart = false;
                refreshVisibleRows();
                Toast.makeText(MusicActivity.this, reason, Toast.LENGTH_LONG).show();
            }
        });
    }

    private void showLocalCategory(int category) {
        if (localSongs == null || category < 0 || category > 3) return;
        List<MenuRow> rows;
        switch (category) {
            case 0:
                rows = songRows(localSongs);
                break;
            case 1:
            case 2:
            case 3:
                rows = groupedRows(category);
                break;
            default:
                return;
        }
        navigateTo(new Page(MENU[category], rows));
        pendingMenu = -1;
    }

    private List<MenuRow> songRows(List<LocalMusicSource.Song> songs) {
        ArrayList<MenuRow> rows = new ArrayList<>(songs.size());
        for (LocalMusicSource.Song song : songs) rows.add(MenuRow.song(song));
        if (rows.isEmpty()) rows.add(MenuRow.message("No local music found"));
        return rows;
    }

    private List<MenuRow> groupedRows(int category) {
        // All grouping is read-only; no secondary music scanner is required.
        Map<String, Group> groups = new LinkedHashMap<>();
        for (LocalMusicSource.Song song : localSongs) {
            String label = category == 1 ? song.artist
                    : category == 2 ? song.album : song.genre;
            String key = groupKey(song, category);
            Group group = groups.get(key);
            if (group == null) {
                group = new Group(label);
                groups.put(key, group);
            }
            group.songs.add(song);
        }

        ArrayList<Group> sorted = new ArrayList<>(groups.values());
        sorted.sort(Comparator.comparing(group -> group.label,
                String.CASE_INSENSITIVE_ORDER));
        ArrayList<MenuRow> rows = new ArrayList<>(sorted.size());
        for (Group group : sorted) {
            rows.add(MenuRow.group(group.label, category, group.songs));
        }
        if (rows.isEmpty()) rows.add(MenuRow.message("No local music found"));
        return rows;
    }

    private static String groupKey(LocalMusicSource.Song song, int category) {
        if (category == 1) return song.artist.toLowerCase(Locale.ROOT);
        if (category == 2) {
            return song.albumId >= 0 ? "album:" + song.albumId
                    : "album:" + song.album + ":" + song.artist;
        }
        return song.genre.toLowerCase(Locale.ROOT);
    }

    private void navigateTo(Page next) {
        Page previous = currentPage;
        previous.selected = list.getSelectedItemPosition();
        View selectedView = list.getSelectedView();
        previous.selectedTop = selectedView == null ? 0 : selectedView.getTop();
        backStack.push(previous);
        ensureBackCallback();
        showPage(next);
    }

    private void showPage(Page page) {
        currentPage = page;
        trackpadPress.cancel();
        adapter.notifyDataSetChanged();
        list.post(() -> {
            if (currentPage != page || isFinishing() || isDestroyed()) return;
            list.requestFocus();
            list.setSelectionFromTop(Math.max(0, page.selected), page.selectedTop);
            styleVisibleRows();
        });
    }

    private void ensureBackCallback() {
        if (nestedBackCallback != null) return;
        nestedBackCallback = this::navigateBack;
        getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                OnBackInvokedDispatcher.PRIORITY_DEFAULT, nestedBackCallback);
    }

    private void navigateBack() {
        if (nowPlayingVisible) {
            closeNowPlaying();
            return;
        }
        if (backStack.isEmpty()) return;
        Page previous = backStack.pop();
        if (backStack.isEmpty()) unregisterNestedBack();
        showPage(previous);
    }

    private void unregisterNestedBack() {
        if (nestedBackCallback == null) return;
        getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(
                nestedBackCallback);
        nestedBackCallback = null;
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        if (nowPlayingVisible || !backStack.isEmpty()) navigateBack();
        else super.onBackPressed();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int code = event.getKeyCode();
        if (!TrackpadPressHandler.isConfirmKey(code)
                && event.getAction() == KeyEvent.ACTION_DOWN) {
            trackpadPress.cancel();
        }
        if (TrackpadPressHandler.isConfirmKey(code)) {
            return trackpadPress.handle(event);
        }
        if (nowPlayingVisible) {
            if (code == KeyEvent.KEYCODE_DPAD_UP
                    || code == KeyEvent.KEYCODE_DPAD_DOWN
                    || code == KeyEvent.KEYCODE_DPAD_LEFT
                    || code == KeyEvent.KEYCODE_DPAD_RIGHT) {
                if (event.getAction() == KeyEvent.ACTION_DOWN && nowPlaying != null) {
                    nowPlaying.directional(code);
                }
                return true;
            }
            return super.dispatchKeyEvent(event);
        }
        if (code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN) {
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                int selected = list.getSelectedItemPosition();
                if (selected < 0) {
                    // As in QS: if touch scrolling cleared selection, resume
                    // trackpad navigation from a currently visible list row.
                    int first = Math.max(0, list.getFirstVisiblePosition());
                    int last = Math.min(currentPage.rows.size() - 1,
                            list.getLastVisiblePosition());
                    int target = code == KeyEvent.KEYCODE_DPAD_UP
                            ? Math.max(first, last) : first;
                    int visibleIndex = target - list.getFirstVisiblePosition();
                    View visible = visibleIndex >= 0 && visibleIndex < list.getChildCount()
                            ? list.getChildAt(visibleIndex) : null;
                    int top = visible == null ? 0 : visible.getTop();
                    list.requestFocus();
                    list.setSelectionFromTop(target, top);
                } else {
                    // ListView itself owns one-row movement and viewport
                    // changes; there is deliberately no manual scrolling.
                    if (!list.hasFocus()) list.requestFocus();
                    list.dispatchKeyEvent(event);
                }
            }
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
            trackpadPress.cancel();
        }
        return super.dispatchTouchEvent(event);
    }

    @Override
    protected void onStop() {
        trackpadPress.cancel();
        playback.disconnect();
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        unregisterNestedBack();
        if (nowPlaying != null) nowPlaying.dispose();
        playback.disconnect();
        listArtwork.close();
        localSource.close();
        super.onDestroy();
    }

    @Override
    public void onWindowFocusChanged(boolean hasWindowFocus) {
        super.onWindowFocusChanged(hasWindowFocus);
        if (!hasWindowFocus) trackpadPress.cancel();
    }

    private int dp(float value) {
        return Math.round(getResources().getDisplayMetrics().density * value);
    }
}
