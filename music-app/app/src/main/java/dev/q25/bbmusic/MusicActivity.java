package dev.q25.bbmusic;

import android.app.Activity;
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
import android.widget.AbsListView;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

/**
 * Standalone BBOS6 Music main menu.
 *
 * Mirrors the native ListView + BaseAdapter, visible-row selection styling,
 * and one-item trackpad navigation used by BB UI's Quick Settings panel,
 * without importing or depending on BB UI, Vector or SystemUI.
 *
 * Category destinations are intentionally not implemented yet. Symfonium is
 * not connected on startup and no media app is required to open this screen.
 */
public final class MusicActivity extends Activity {
    private static final String TAG = "BBMusic";
    private static final String[] MENU = {
            "All Songs",
            "Artists",
            "Albums",
            "Genres",
            "Playlists",
            "Shuffle Songs"
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
    private TrackpadPressHandler trackpadPress;
    private int canvasColor;
    private int rowColor;
    private int titleColor;
    private int dividerColor;
    private int[] selectionColors;

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
                () -> { /* No long-press menu has been specified yet. */ });

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
        list.setAdapter(new BaseAdapter() {
            @Override public int getCount() {
                return MENU.length;
            }

            @Override public Object getItem(int position) {
                return MENU[position];
            }

            @Override public long getItemId(int position) {
                return position;
            }

            @Override public View getView(int position, View convertView,
                    ViewGroup parent) {
                View view = convertView == null ? createRowView() : convertView;
                RowViews views = (RowViews) view.getTag();
                views.label.setText(MENU[position]);
                views.root.setContentDescription(MENU[position]);
                styleRow(views, position);
                return view;
            }
        });

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
            if (position < 0 || position >= MENU.length) continue;
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
        if (position < 0 || position >= MENU.length) return;
        // We will implement each destination under user guidance. Selection
        // is deliberately inert until its screen/action has been defined.
        Log.i(TAG, "Menu activated (not wired): " + MENU[position]);
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
                    int last = Math.min(MENU.length - 1, list.getLastVisiblePosition());
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
    public void onWindowFocusChanged(boolean hasWindowFocus) {
        super.onWindowFocusChanged(hasWindowFocus);
        if (!hasWindowFocus) trackpadPress.cancel();
    }

    private int dp(float value) {
        return Math.round(getResources().getDisplayMetrics().density * value);
    }
}
