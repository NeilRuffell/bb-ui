package dev.q25.bb7pulldown;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
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
import android.service.quicksettings.Tile;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import dev.q25.trackpad.theme.BB7Theme;
import dev.q25.trackpad.input.TrackpadPressHandler;

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
    private final TrackpadPressHandler trackpadPress;
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
        final StatusCheckbox checkbox;
        boolean styled;
        boolean lastFocused;
        boolean lastEnabled;
        boolean lastActive;

        RowViews(LinearLayout root, ImageView icon, TextView label,
                TextView status, StatusCheckbox checkbox) {
            this.root = root;
            this.icon = icon;
            this.label = label;
            this.status = status;
            this.checkbox = checkbox;
        }
    }

    /**
     * Presentation-only BBOS6 square checkbox. Never receives focus or
     * handles clicks: activating the list item remains the only action.
     */
    private static final class StatusCheckbox extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path tick = new Path();
        private boolean checked;
        private boolean highlighted;

        StatusCheckbox(Context context) {
            super(context);
            setFocusable(false);
            setClickable(false);
            setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        }

        void display(boolean checked, boolean highlighted) {
            if (this.checked == checked && this.highlighted == highlighted) return;
            this.checked = checked;
            this.highlighted = highlighted;
            invalidate();
        }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float density = getResources().getDisplayMetrics().density;
            float edge = Math.min(Math.min(getWidth(), getHeight()), 19f * density);
            float left = (getWidth() - edge) * 0.5f;
            float top = (getHeight() - edge) * 0.5f;
            float right = left + edge;
            float bottom = top + edge;
            float radius = 2f * density;

            paint.setStyle(Paint.Style.FILL);
            paint.setColor(0xfff3f5f7);
            canvas.drawRoundRect(left, top, right, bottom, radius, radius, paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(Math.max(1f, density));
            paint.setColor(highlighted ? 0xffe1efff : 0xff84909e);
            canvas.drawRoundRect(left, top, right, bottom, radius, radius, paint);

            if (checked) {
                tick.reset();
                tick.moveTo(left + edge * 0.19f, top + edge * 0.52f);
                tick.lineTo(left + edge * 0.41f, top + edge * 0.74f);
                tick.lineTo(left + edge * 0.83f, top + edge * 0.26f);
                paint.setStrokeWidth(2.3f * density);
                paint.setStrokeCap(Paint.Cap.ROUND);
                paint.setStrokeJoin(Paint.Join.ROUND);
                paint.setColor(0xff176bd1);
                canvas.drawPath(tick, paint);
            }
            paint.setStyle(Paint.Style.FILL);
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
        this.trackpadPress = new TrackpadPressHandler(
                () -> dispatchPress(selectedIndex(), false),
                () -> dispatchPress(selectedIndex(), true));
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
            dispatchPress(position - HEADER_COUNT, false);
        });
        list.setOnItemLongClickListener((parent, view, position, id) -> {
            dispatchPress(position - HEADER_COUNT, true);
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
        trackpadPress.cancel();
        nativeSession.stop();
        super.onDetachedFromWindow();
    }

    void setActive(boolean value) {
        if (active == value) return;
        if (!value) trackpadPress.cancel();
        active = value;
        if (!isAttachedToWindow()) return;
        if (active) nativeSession.start();
        else nativeSession.stop();
    }

    void focusFirst() {
        trackpadPress.cancel();
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

        empty.setVisibility(desired.isEmpty() ? View.VISIBLE : View.GONE);
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
            trackpadPress.cancel();
            int before = selectedIndex();
            String selectedKey = before >= 0 ? rows.get(before).key : null;
            View oldSelectedView = list.getSelectedView();
            int selectedTop = oldSelectedView == null ? firstRowOffset()
                    : oldSelectedView.getTop();
            rows.clear();
            rows.addAll(desired);
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

        TextView primary = new TextView(getContext());
        primary.setTextSize(15);
        primary.setSingleLine(true);
        primary.setGravity(Gravity.CENTER_VERTICAL);
        primary.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(
                0, LayoutParams.MATCH_PARENT, 1f);
        titleParams.leftMargin = dp(9);
        root.addView(primary, titleParams);

        TextView secondary = new TextView(getContext());
        secondary.setTextSize(14);
        secondary.setSingleLine(true);
        secondary.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        secondary.setEllipsize(TextUtils.TruncateAt.END);
        secondary.setMaxWidth(Math.max(dp(65),
                (int) (getResources().getDisplayMetrics().widthPixels * 0.33f)));
        LinearLayout.LayoutParams statusParams = new LinearLayout.LayoutParams(
                LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT);
        statusParams.leftMargin = dp(7);
        root.addView(secondary, statusParams);

        StatusCheckbox checkbox = new StatusCheckbox(getContext());
        LinearLayout.LayoutParams checkParams = new LinearLayout.LayoutParams(
                dp(24), dp(26));
        checkParams.leftMargin = dp(7);
        root.addView(checkbox, checkParams);

        root.setTag(new RowViews(root, icon, primary, secondary, checkbox));
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
        boolean hasCheckState = tile != null && tile.checked != null;
        views.checkbox.setVisibility(hasCheckState ? View.VISIBLE : View.GONE);
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
        NativeQuickSettingsTiles.TileInfo tile = rows.get(index).tile;
        boolean enabled = tile != null && !tile.disabledByPolicy;
        boolean active = enabled && tile.state == Tile.STATE_ACTIVE;
        views.checkbox.display(tile != null && Boolean.TRUE.equals(tile.checked), focused);
        if (views.styled && views.lastFocused == focused
                && views.lastEnabled == enabled && views.lastActive == active) {
            return;
        }
        views.styled = true;
        views.lastFocused = focused;
        views.lastEnabled = enabled;
        views.lastActive = active;
        if (focused) views.root.setBackground(BB7Theme.selected(getContext(), dp(3), dp(1)));
        else views.root.setBackgroundColor(palette.card);
        views.label.setTextColor(focused ? Color.WHITE : enabled ? palette.title : palette.muted);
        // BBOS6 uses green status text for an enabled connection, and a
        // neutral value for inactive/unavailable controls.
        int statusColor = focused ? Color.WHITE
                : active ? (palette == BB7Theme.Palette.DARK ? 0xff6bdd78 : 0xff168c27)
                : enabled ? palette.title : palette.muted;
        views.status.setTextColor(statusColor);
        views.icon.setImageTintList(ColorStateList.valueOf(
                focused ? Color.WHITE : enabled ? palette.body : palette.muted));
    }

    private int selectedIndex() {
        int position = list.getSelectedItemPosition() - HEADER_COUNT;
        return position >= 0 && position < rows.size() ? position : -1;
    }

    /**
     * Short press uses the existing QSTile's secondary/toggle action when
     * LineageOS exposes one. Holding the trackpad delegates to the native
     * long-click action (typically its Settings page), never the detail popup.
     * No direct Wi-Fi, Bluetooth or telephony API is used by BBUI.
     */
    private void dispatchPress(int index, boolean longPress) {
        if (index < 0 || index >= rows.size()) return;
        Row row = rows.get(index);
        QuickSettingsPressPolicy.Action action = QuickSettingsPressPolicy.select(
                row.spec, row.tile != null && row.tile.hasSecondaryAction, longPress);
        switch (action) {
            case SECONDARY:
                dispatchAt(index, NativeQuickSettingsTiles.Action.SECONDARY);
                break;
            case LONG_PRESS:
                dispatchAt(index, NativeQuickSettingsTiles.Action.LONG_PRESS);
                break;
            case PRIMARY:
            default:
                dispatchAt(index, NativeQuickSettingsTiles.Action.PRIMARY);
                break;
        }
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
        int code = event.getKeyCode();
        // A different navigation key invalidates a pending trackpad hold.
        // Do not let a delayed long press activate a previously selected row.
        if (!TrackpadPressHandler.isConfirmKey(code)
                && event.getAction() == KeyEvent.ACTION_DOWN) {
            trackpadPress.cancel();
        }
        if (navigation != null && navigation.handleKey(event)) {
            trackpadPress.cancel();
            return true;
        }
        if (TrackpadPressHandler.isConfirmKey(code)) {
            return trackpadPress.handle(event);
        }
        if (code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN) {
            if (event.getAction() == KeyEvent.ACTION_DOWN && !rows.isEmpty()) {
                int selected = selectedIndex();
                if (code == KeyEvent.KEYCODE_DPAD_UP
                        && lockscreenMode && selected <= 0) {
                    // Retain the lockscreen's existing Up-to-close behavior.
                    listener.onClose();
                } else if (code == KeyEvent.KEYCODE_DPAD_UP && selected == 0) {
                    // On the unlocked shade, Up is bounded at the first row.
                    // The BB popup is accessible only through the hardware key.
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
                    // Preserve MENU's existing secondary-action behavior.
                    Row row = rows.get(selected);
                    dispatchAt(selected, row.tile != null && row.tile.hasSecondaryAction
                            ? NativeQuickSettingsTiles.Action.SECONDARY
                            : NativeQuickSettingsTiles.Action.LONG_PRESS);
                }
            }
            return true;
        }
        if (code == KeyEvent.KEYCODE_SPACE && !lockscreenMode) {
            if (event.getAction() == KeyEvent.ACTION_UP) {
                dispatchPress(selectedIndex(), false);
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
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) trackpadPress.cancel();
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

    @Override public void onWindowFocusChanged(boolean hasWindowFocus) {
        super.onWindowFocusChanged(hasWindowFocus);
        if (!hasWindowFocus) trackpadPress.cancel();
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
