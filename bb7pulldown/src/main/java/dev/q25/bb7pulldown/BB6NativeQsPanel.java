package dev.q25.bb7pulldown;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.ColorDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import dev.q25.trackpad.theme.BB7Theme;

/**
 * BBOS6/7 presentation of installed native SystemUI Quick Settings tiles.
 *
 * One Android ListView owns both row selection and the vertical viewport.
 * Unlike ScrollView + focusable children, ListView has built-in one-item
 * Up/Down navigation and scrolls the selected item into view as needed.
 * There are no manual scroll amounts, focus-scroll workarounds or paging.
 *
 * The panel is view-only: tile lifecycles/actions are still owned by SystemUI.
 */
final class BB6NativeQsPanel extends FrameLayout {
    interface Listener {
        void onClose();
        void onAction(String spec, int lockedKind, NativeQuickSettingsTiles.Action action);
    }

    private static final int UNLOCKED = -1;
    private static final int HEADER_COUNT = 1;

    private final BB7Theme.Palette palette;
    private final Listener listener;
    private final ShadeUiController navigation;
    private final boolean lockscreenMode;
    private final NativeQuickSettingsTiles.Session nativeSession;
    private final ListView list;
    private final TextView empty;
    private final List<Row> rows = new ArrayList<>();
    private final BaseAdapter adapter;
    private boolean active;
    private float downY;
    private float downX;
    private boolean closingSwipe;

    /** Data only: ListView creates/recycles the actual views. */
    private static final class Row {
        final String key;
        final String spec;
        final int lockedKind;
        NativeQuickSettingsTiles.TileInfo tile;
        CharSequence title;

        Row(String key, String spec, int lockedKind,
                NativeQuickSettingsTiles.TileInfo tile, CharSequence title) {
            this.key = key;
            this.spec = spec;
            this.lockedKind = lockedKind;
            this.tile = tile;
            this.title = title;
        }
    }

    private static final class RowViews {
        final LinearLayout root;
        final ImageView icon;
        final TextView label;
        final TextView status;
        boolean styled;
        boolean lastFocused;
        boolean lastEnabled;

        RowViews(LinearLayout root, ImageView icon, TextView label, TextView status) {
            this.root = root;
            this.icon = icon;
            this.label = label;
            this.status = status;
        }
    }

    BB6NativeQsPanel(Context context, Listener listener, ShadeUiController navigation,
            boolean lockscreenMode) {
        super(context);
        this.listener = listener;
        this.navigation = navigation;
        this.lockscreenMode = lockscreenMode;
        this.active = lockscreenMode;
        this.palette = BB7Theme.palette(context);
        this.nativeSession = new NativeQuickSettingsTiles.Session(context, this::render);
        setFocusable(true);
        setFocusableInTouchMode(true);
        setBackgroundColor(palette.canvas);

        list = new ListView(context);
        list.setBackgroundColor(palette.canvas);
        list.setVerticalScrollBarEnabled(false);
        list.setFastScrollEnabled(false);
        list.setItemsCanFocus(false);
        list.setFocusable(true);
        list.setFocusableInTouchMode(true);
        list.setSelector(android.R.color.transparent);
        list.setDivider(new ColorDrawable(palette.divider));
        list.setDividerHeight(dp(1));
        addView(list, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        // This heading scrolls as part of the same list, exactly like the
        // previous panel. It is not a selectable tile.
        LinearLayout header = new LinearLayout(context);
        header.setOrientation(LinearLayout.VERTICAL);
        header.setPadding(dp(7), dp(7), dp(7), dp(2));

        TextView heading = new TextView(context);
        heading.setText(lockscreenMode ? "Manage Connections" : "Quick Settings");
        heading.setTextColor(palette.title);
        heading.setTypeface(null, Typeface.BOLD);
        heading.setTextSize(16);
        heading.setGravity(Gravity.CENTER_VERTICAL);
        heading.setPadding(dp(13), 0, dp(12), 0);
        heading.setBackgroundColor(palette.heading);
        header.addView(heading, new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, dp(37)));

        empty = new TextView(context);
        empty.setText("No native Quick Settings tiles available");
        empty.setTextColor(palette.muted);
        empty.setTextSize(14);
        empty.setPadding(dp(12), dp(16), dp(12), dp(16));
        empty.setVisibility(View.GONE);
        header.addView(empty);
        list.addHeaderView(header, null, false);

        adapter = new BaseAdapter() {
            @Override public int getCount() { return rows.size(); }
            @Override public Object getItem(int position) { return rows.get(position); }
            @Override public long getItemId(int position) { return position; }
            @Override public View getView(int position, View convertView, ViewGroup parent) {
                View view = convertView == null ? createRowView() : convertView;
                bindRow((RowViews) view.getTag(), rows.get(position), position);
                return view;
            }
        };
        list.setAdapter(adapter);
        list.setOnItemClickListener((parent, view, position, id) -> {
            if (navigation != null) navigation.focusContent();
            dispatchAt(position - HEADER_COUNT, NativeQuickSettingsTiles.Action.PRIMARY);
        });
        list.setOnItemLongClickListener((parent, view, position, id) -> {
            dispatchAt(position - HEADER_COUNT, NativeQuickSettingsTiles.Action.LONG_PRESS);
            return true;
        });
        list.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view,
                    int position, long id) {
                styleVisibleRows();
            }

            @Override public void onNothingSelected(AdapterView<?> parent) {
                styleVisibleRows();
            }
        });
        list.setOnFocusChangeListener((view, focused) -> styleVisibleRows());
        list.setOnScrollListener(new AbsListView.OnScrollListener() {
            @Override public void onScrollStateChanged(AbsListView view, int state) { }
            @Override public void onScroll(AbsListView view, int firstVisible,
                    int visibleCount, int totalCount) {
                styleVisibleRows();
            }
        });
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (active) nativeSession.start();
    }

    @Override protected void onDetachedFromWindow() {
        nativeSession.stop();
        super.onDetachedFromWindow();
    }

    void setActive(boolean value) {
        if (active == value) return;
        active = value;
        if (!isAttachedToWindow()) return;
        if (active) nativeSession.start();
        else nativeSession.stop();
    }

    void focusFirst() {
        if (navigation != null) navigation.focusContent();
        list.requestFocus();
        if (!rows.isEmpty()) list.setSelectionFromTop(HEADER_COUNT, firstRowOffset());
        styleVisibleRows();
    }

    /** QSTile state callbacks change data, not the selection or scroll position. */
    private void render() {
        if (!active || !isAttachedToWindow()) return;
        List<NativeQuickSettingsTiles.TileInfo> nativeTiles = nativeSession.snapshot();
        List<Row> desired = new ArrayList<>();
        if (lockscreenMode) {
            Set<String> available = new HashSet<>();
            for (NativeQuickSettingsTiles.TileInfo tile : nativeTiles) {
                if (tile.available) available.add(tile.spec);
            }
            for (int kind : LockscreenQuickSettingsPolicy.visibleKinds()) {
                String spec = LockscreenQuickSettingsPolicy.selectNativeSpec(kind, available);
                desired.add(new Row("locked:" + kind, spec, kind,
                        find(nativeTiles, spec), ConnectionControlPolicy.label(kind)));
            }
        } else {
            for (NativeQuickSettingsTiles.TileInfo tile : nativeTiles) {
                if (tile.available) {
                    desired.add(new Row(tile.spec, tile.spec, UNLOCKED, tile, tile.label));
                }
            }
        }

        boolean structureChanged = desired.size() != rows.size();
        if (!structureChanged) {
            for (int i = 0; i < desired.size(); i++) {
                Row current = rows.get(i);
                Row updated = desired.get(i);
                if (!current.key.equals(updated.key)
                        || !Objects.equals(current.spec, updated.spec)
                        || current.lockedKind != updated.lockedKind) {
                    structureChanged = true;
                    break;
                }
            }
        }

        if (structureChanged) {
            int before = selectedIndex();
            String selectedKey = before >= 0 ? rows.get(before).key : null;
            View oldSelectedView = list.getSelectedView();
            int selectedTop = oldSelectedView == null ? firstRowOffset()
                    : oldSelectedView.getTop();
            rows.clear();
            rows.addAll(desired);
            empty.setVisibility(rows.isEmpty() ? View.VISIBLE : View.GONE);
            adapter.notifyDataSetChanged();
            if (selectedKey != null) {
                int next = -1;
                for (int i = 0; i < rows.size(); i++) {
                    if (selectedKey.equals(rows.get(i).key)) {
                        next = i;
                        break;
                    }
                }
                if (next < 0 && !rows.isEmpty()) {
                    next = Math.min(before, rows.size() - 1);
                }
                if (next >= 0) {
                    list.setSelectionFromTop(next + HEADER_COUNT, selectedTop);
                }
            } else if (list.hasFocus() && !rows.isEmpty()) {
                list.setSelectionFromTop(HEADER_COUNT, firstRowOffset());
            }
        } else {
            // No notifyDataSetChanged(): rebinding can otherwise reset the
            // focused item and cause ListView to change its viewport.
            for (int i = 0; i < desired.size(); i++) {
                rows.get(i).tile = desired.get(i).tile;
                rows.get(i).title = desired.get(i).title;
            }
            refreshVisibleRows();
        }
    }

    private static NativeQuickSettingsTiles.TileInfo find(
            List<NativeQuickSettingsTiles.TileInfo> tiles, String spec) {
        if (spec == null) return null;
        for (NativeQuickSettingsTiles.TileInfo tile : tiles) {
            if (spec.equals(tile.spec) && tile.available) return tile;
        }
        return null;
    }

    private View createRowView() {
        LinearLayout root = new LinearLayout(getContext());
        root.setOrientation(LinearLayout.HORIZONTAL);
        root.setGravity(Gravity.CENTER_VERTICAL);
        root.setPadding(dp(16), dp(4), dp(16), dp(4));
        root.setLayoutParams(new AbsListView.LayoutParams(
                LayoutParams.MATCH_PARENT, dp(55)));
        root.setFocusable(false);

        ImageView icon = new ImageView(getContext());
        root.addView(icon, new LinearLayout.LayoutParams(dp(23), dp(23)));

        LinearLayout labels = new LinearLayout(getContext());
        labels.setOrientation(LinearLayout.VERTICAL);
        labels.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams labelsParams = new LinearLayout.LayoutParams(
                0, LayoutParams.MATCH_PARENT, 1);
        labelsParams.leftMargin = dp(9);
        root.addView(labels, labelsParams);

        TextView primary = new TextView(getContext());
        primary.setTextSize(15);
        primary.setSingleLine(true);
        primary.setEllipsize(TextUtils.TruncateAt.END);
        labels.addView(primary);

        TextView secondary = new TextView(getContext());
        secondary.setTextSize(12);
        secondary.setSingleLine(true);
        secondary.setEllipsize(TextUtils.TruncateAt.END);
        labels.addView(secondary);

        root.setTag(new RowViews(root, icon, primary, secondary));
        return root;
    }

    private void bindRow(RowViews views, Row row, int index) {
        NativeQuickSettingsTiles.TileInfo tile = row.tile;
        Drawable drawable = tile == null ? null
                : NativeQuickSettingsTiles.iconDrawable(getContext(), tile.icon);
        if (drawable != null) views.icon.setImageDrawable(drawable);
        else views.icon.setImageResource(android.R.drawable.ic_menu_manage);
        CharSequence title = TextUtils.isEmpty(row.title)
                ? (row.spec == null ? "Unavailable" : row.spec) : row.title;
        views.label.setText(title);
        views.status.setText(tile == null ? "Unavailable"
                : tile.disabledByPolicy ? "Restricted" : tile.statusText());
        CharSequence description = tile == null ? "Unavailable"
                : tile.contentDescription == null ? title : tile.contentDescription;
        views.root.setContentDescription(description + ", " + views.status.getText());
        styleRow(views, index);
    }

    private void refreshVisibleRows() {
        int start = list.getFirstVisiblePosition();
        for (int i = 0; i < list.getChildCount(); i++) {
            int rowIndex = start + i - HEADER_COUNT;
            if (rowIndex >= 0 && rowIndex < rows.size()) {
                View child = list.getChildAt(i);
                if (child.getTag() instanceof RowViews) {
                    bindRow((RowViews) child.getTag(), rows.get(rowIndex), rowIndex);
                }
            }
        }
    }

    private void styleVisibleRows() {
        int start = list.getFirstVisiblePosition();
        for (int i = 0; i < list.getChildCount(); i++) {
            int rowIndex = start + i - HEADER_COUNT;
            if (rowIndex >= 0 && rowIndex < rows.size()) {
                View child = list.getChildAt(i);
                if (child.getTag() instanceof RowViews) {
                    styleRow((RowViews) child.getTag(), rowIndex);
                }
            }
        }
    }

    private void styleRow(RowViews views, int index) {
        boolean focused = list.hasFocus() && selectedIndex() == index
                && (navigation == null || !navigation.isStatusBarFocused());
        boolean enabled = rows.get(index).tile != null;
        if (views.styled && views.lastFocused == focused && views.lastEnabled == enabled) {
            return;
        }
        views.styled = true;
        views.lastFocused = focused;
        views.lastEnabled = enabled;
        if (focused) views.root.setBackground(BB7Theme.selected(dp(3), dp(1)));
        else views.root.setBackgroundColor(palette.card);
        views.label.setTextColor(focused ? Color.WHITE : enabled ? palette.title : palette.muted);
        views.status.setTextColor(focused ? Color.WHITE : palette.muted);
        views.icon.setImageTintList(ColorStateList.valueOf(
                focused ? Color.WHITE : enabled ? palette.body : palette.muted));
    }

    private int selectedIndex() {
        int position = list.getSelectedItemPosition() - HEADER_COUNT;
        return position >= 0 && position < rows.size() ? position : -1;
    }

    private void dispatchAt(int index, NativeQuickSettingsTiles.Action action) {
        if (index < 0 || index >= rows.size()) return;
        Row row = rows.get(index);
        if (row.tile == null || row.spec == null) return;
        if (lockscreenMode && action != NativeQuickSettingsTiles.Action.PRIMARY) return;
        if (action == NativeQuickSettingsTiles.Action.SECONDARY
                && !row.tile.hasSecondaryAction) return;
        if (action == NativeQuickSettingsTiles.Action.LONG_PRESS
                && !row.tile.hasLongAction) return;
        listener.onAction(row.spec, row.lockedKind, action);
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        if (navigation != null && navigation.handleKey(event)) return true;
        int code = event.getKeyCode();
        if (code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN) {
            if (event.getAction() == KeyEvent.ACTION_DOWN && !rows.isEmpty()) {
                int selected = selectedIndex();
                if (code == KeyEvent.KEYCODE_DPAD_UP && selected <= 0) {
                    if (lockscreenMode) listener.onClose();
                    else if (navigation != null) {
                        navigation.focusStatusBarFromPage(ShadeStatusNavigation.QUICK_SETTINGS);
                        list.clearFocus();
                        styleVisibleRows();
                    }
                } else if (selected < 0) {
                    // Re-enter keyboard navigation at an already visible row
                    // if touch scrolling has cleared ListView's selection.
                    int first = Math.max(HEADER_COUNT, list.getFirstVisiblePosition());
                    int last = Math.min(rows.size(), list.getLastVisiblePosition());
                    int target = code == KeyEvent.KEYCODE_DPAD_UP
                            ? Math.max(first, last) : first;
                    int visibleIndex = target - list.getFirstVisiblePosition();
                    View visible = visibleIndex >= 0 && visibleIndex < list.getChildCount()
                            ? list.getChildAt(visibleIndex) : null;
                    int top = visible == null ? 0 : visible.getTop();
                    list.requestFocus();
                    list.setSelectionFromTop(target, top);
                } else {
                    // This is ListView's own one-item arrow navigation.
                    // Do not give the event to any ScrollView/page-scroll handler.
                    if (!list.hasFocus()) list.requestFocus();
                    list.dispatchKeyEvent(event);
                }
            }
            return true;
        }
        if (code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT) {
            return true;
        }
        if (code == KeyEvent.KEYCODE_SPACE && lockscreenMode) return true;
        if (code == KeyEvent.KEYCODE_MENU) {
            if (event.getAction() == KeyEvent.ACTION_UP) {
                int selected = selectedIndex();
                if (selected >= 0) {
                    Row row = rows.get(selected);
                    dispatchAt(selected, row.tile != null && row.tile.hasSecondaryAction
                            ? NativeQuickSettingsTiles.Action.SECONDARY
                            : NativeQuickSettingsTiles.Action.LONG_PRESS);
                }
            }
            return true;
        }
        if (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER
                || code == KeyEvent.KEYCODE_NUMPAD_ENTER
                || (code == KeyEvent.KEYCODE_SPACE && !lockscreenMode)) {
            if (event.getAction() == KeyEvent.ACTION_UP) {
                dispatchAt(selectedIndex(), NativeQuickSettingsTiles.Action.PRIMARY);
            }
            return true;
        }
        if (code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_ESCAPE) {
            if (event.getAction() == KeyEvent.ACTION_UP) listener.onClose();
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    @Override public boolean dispatchTouchEvent(MotionEvent event) {
        // Full QS retains ListView's native touch scrolling. Only the compact
        // lock-screen panel keeps its existing upward swipe-to-exit gesture.
        if (!lockscreenMode) return super.dispatchTouchEvent(event);
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downY = event.getRawY();
                downX = event.getRawX();
                closingSwipe = false;
                break;
            case MotionEvent.ACTION_MOVE:
                if (!closingSwipe && downY - event.getRawY() > dp(65)
                        && downY - event.getRawY() > Math.abs(downX - event.getRawX()) * 1.2f) {
                    closingSwipe = true;
                    listener.onClose();
                    return true;
                }
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (closingSwipe) return true;
                break;
            default:
                break;
        }
        return super.dispatchTouchEvent(event);
    }

    /** Start the first tile immediately below the nonselectable list header. */
    private int firstRowOffset() {
        // Header: 7dp top inset + 37dp label + 2dp bottom + the list divider.
        return dp(7) + dp(37) + dp(2) + list.getDividerHeight();
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }
}
