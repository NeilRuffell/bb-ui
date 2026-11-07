package dev.q25.bbmusic;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.content.res.Configuration;
import android.graphics.Color;
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
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Standalone BBOS6 Music main menu.
 *
 * Mirrors the native ListView + BaseAdapter, visible-row selection styling,
 * and one-item trackpad navigation used by BB UI's Quick Settings panel,
 * without importing or depending on BB UI, Vector or SystemUI.
 *
 * Local music is read from Android MediaStore after the ordinary Android 13+
 * SAF folder selection. Symfonium is never required or started.
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
    private List<LocalMusicSource.Song> localSongs;
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
        final TextView label;

        RowViews(LinearLayout root, TextView label) {
            this.root = root;
            this.label = label;
        }
    }

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
                String title = currentPage.rows.get(position).title;
                views.label.setText(title);
                views.root.setContentDescription(title);
                styleRow(views, position);
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
        FrameLayout safeViewport = new FrameLayout(this);
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
    }

    private View createRowView() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        // Keep the row/background edge-to-edge; indent only the label.
        row.setPadding(0, dp(4), 0, dp(4));
        row.setLayoutParams(new AbsListView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(55)));
        row.setFocusable(false);

        TextView title = new TextView(this);
        title.setTextSize(15);
        title.setPadding(dp(16), 0, dp(16), 0);
        title.setGravity(Gravity.CENTER_VERTICAL);
        title.setSingleLine(true);
        row.addView(title, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        row.setTag(new RowViews(row, title));
        return row;
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
    }

    private void activateSelected() {
        activate(list.getSelectedItemPosition());
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
                Toast.makeText(this, "Playback is not implemented yet",
                        Toast.LENGTH_SHORT).show();
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
            // The selected document tree provided this URI for our native player.
            Log.i(TAG, "Local track selected, media id=" + row.song.id);
            Toast.makeText(this, "Playback is not implemented yet",
                    Toast.LENGTH_SHORT).show();
        }
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
                loading = false;
                pendingMenu = -1;
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
            loading = true;
            Toast.makeText(this, "Reading selected music folders...",
                    Toast.LENGTH_SHORT).show();
            localSource.load(new LocalMusicSource.Listener() {
                @Override public void onLoaded(List<LocalMusicSource.Song> songs) {
                    loading = false;
                    localSongs = songs;
                    Log.i(TAG, "Selected-folder music ready: " + songs.size() + " tracks");
                    int menu = pendingMenu;
                    pendingMenu = -1;
                    if (menu >= 0 && backStack.isEmpty()) showLocalCategory(menu);
                }

                @Override public void onError(String reason) {
                    loading = false;
                    pendingMenu = -1;
                    Toast.makeText(MusicActivity.this, reason, Toast.LENGTH_LONG).show();
                }
            });
        }
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
            String label;
            String key;
            if (category == 1) {
                label = song.artist;
                key = label.toLowerCase(Locale.ROOT);
            } else if (category == 2) {
                label = song.album;
                key = song.albumId >= 0 ? "album:" + song.albumId
                        : "album:" + label + ":" + song.artist;
            } else {
                label = song.genre;
                key = label.toLowerCase(Locale.ROOT);
            }
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

    private void navigateTo(Page next) {
        Page previous = currentPage;
        previous.selected = list.getSelectedItemPosition();
        View selectedView = list.getSelectedView();
        previous.selectedTop = selectedView == null ? 0 : selectedView.getTop();
        backStack.push(previous);
        if (backStack.size() == 1) {
            // Keep system Back navigation at the root; intercept it only
            // while a nested library list is actually displayed.
            nestedBackCallback = this::navigateBack;
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    OnBackInvokedDispatcher.PRIORITY_DEFAULT, nestedBackCallback);
        }
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

    private void navigateBack() {
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
        if (!backStack.isEmpty()) navigateBack();
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
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        unregisterNestedBack();
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
