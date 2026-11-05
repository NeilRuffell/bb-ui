package dev.q25.bb7pulldown;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.HashSet;

import dev.q25.trackpad.theme.BB7Theme;

/**
 * BBOS6/7 presentation of the installed SystemUI QS tiles.
 *
 * This class is a view only. Neither this view nor its Vector entry points
 * create tiles, implement radio controls, or bypass native tile security.
 * It can later be constructed directly by a LineageOS SystemUI integration.
 */
final class BB6NativeQsPanel extends FrameLayout {
    interface Listener {
        void onClose();
        void onAction(String spec, int lockedKind, NativeQuickSettingsTiles.Action action);
    }

    private static final int UNLOCKED = -1;

    private final BB7Theme.Palette palette;
    private final Listener listener;
    private final ShadeUiController navigation;
    private final boolean lockscreenMode;
    private final NativeQuickSettingsTiles.Session nativeSession;
    private final LinearLayout list;
    private final ScrollView scroll;
    private final List<Row> rows = new ArrayList<>();
    private boolean active;
    private float downY;
    private float downX;
    private boolean closingSwipe;

    private static final class Row {
        final String key;
        final String spec;
        final int lockedKind;
        NativeQuickSettingsTiles.TileInfo tile;
        final LinearLayout view;
        final ImageView icon;
        final TextView label;
        final TextView status;

        Row(String key, String spec, int lockedKind, NativeQuickSettingsTiles.TileInfo tile,
                LinearLayout view, ImageView icon, TextView label, TextView status) {
            this.key = key;
            this.spec = spec;
            this.lockedKind = lockedKind;
            this.tile = tile;
            this.view = view;
            this.icon = icon;
            this.label = label;
            this.status = status;
        }
    }


    /** A stable list item; native state updates do not require new row views. */
    private static final class TileRow {
        final String key;
        final String spec;
        final int lockedKind;
        final NativeQuickSettingsTiles.TileInfo tile;
        final CharSequence title;

        TileRow(String key, String spec, int lockedKind,
                NativeQuickSettingsTiles.TileInfo tile, CharSequence title) {
            this.key = key;
            this.spec = spec;
            this.lockedKind = lockedKind;
            this.tile = tile;
            this.title = title;
        }
    }

    /**
     * Scroll only by the uncovered portion of the focused row.
     * Android's default ScrollView adds fading-edge space and can shift the
     * viewport by different amounts even for identical row-to-row movement.
     */
    private static final class RowScrollView extends ScrollView {
        RowScrollView(Context context) {
            super(context);
        }

        @Override protected int computeScrollDeltaToGetChildRectOnScreen(Rect rect) {
            if (getChildCount() == 0) return 0;
            int scrollY = getScrollY();
            return QuickSettingsRowScrollPolicy.scrollDelta(
                    scrollY,
                    scrollY + getPaddingTop(),
                    scrollY + getHeight() - getPaddingBottom(),
                    getChildAt(0).getBottom(),
                    rect.top, rect.bottom);
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

        scroll = new RowScrollView(context);
        scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(false);
        addView(scroll, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(content, new ScrollView.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

        TextView heading = new TextView(context);
        heading.setText(lockscreenMode ? "Manage Connections" : "Quick Settings");
        heading.setTextColor(palette.title);
        heading.setTypeface(null, Typeface.BOLD);
        heading.setTextSize(16);
        heading.setGravity(Gravity.CENTER_VERTICAL);
        heading.setPadding(dp(13), 0, dp(12), 0);
        heading.setBackgroundColor(palette.heading);
        LinearLayout.LayoutParams headingParams = new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, dp(37));
        headingParams.setMargins(dp(7), dp(7), dp(7), dp(2));
        content.addView(heading, headingParams);

        list = new LinearLayout(context);
        list.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams listParams = new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        listParams.setMargins(dp(7), 0, dp(7), dp(10));
        content.addView(list, listParams);
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
        if (!rows.isEmpty()) rows.get(0).view.requestFocus();
        else requestFocus();
    }

    /** Called by SystemUI's own QSHost and QSTile callback sessions. */
    private void render() {
        if (!active || !isAttachedToWindow()) return;
        List<NativeQuickSettingsTiles.TileInfo> nativeTiles = nativeSession.snapshot();
        List<TileRow> desired = new ArrayList<>();

        if (lockscreenMode) {
            Set<String> available = new HashSet<>();
            for (NativeQuickSettingsTiles.TileInfo tile : nativeTiles) {
                if (tile.available) available.add(tile.spec);
            }
            for (int kind : LockscreenQuickSettingsPolicy.visibleKinds()) {
                String spec = LockscreenQuickSettingsPolicy.selectNativeSpec(kind, available);
                desired.add(new TileRow("locked:" + kind, spec, kind,
                        find(nativeTiles, spec), ConnectionControlPolicy.label(kind)));
            }
        } else {
            for (NativeQuickSettingsTiles.TileInfo tile : nativeTiles) {
                if (tile.available) {
                    desired.add(new TileRow(tile.spec, tile.spec, UNLOCKED,
                            tile, tile.label));
                }
            }
        }

        boolean structureChanged = desired.size() != rows.size();
        if (!structureChanged) {
            for (int i = 0; i < desired.size(); i++) {
                TileRow wanted = desired.get(i);
                Row existing = rows.get(i);
                if (!existing.key.equals(wanted.key)
                        || !java.util.Objects.equals(existing.spec, wanted.spec)
                        || existing.lockedKind != wanted.lockedKind) {
                    structureChanged = true;
                    break;
                }
            }
        }

        if (structureChanged) {
            // Rebuild only when the installed tile list or its ordering changes.
            // State updates must not detach the selected row or reset scrolling.
            String selectedKey = null;
            int selectedIndex = -1;
            for (int i = 0; i < rows.size(); i++) {
                if (rows.get(i).view.isFocused()) {
                    selectedKey = rows.get(i).key;
                    selectedIndex = i;
                    break;
                }
            }
            boolean panelFocused = isFocused();
            list.removeAllViews();
            rows.clear();
            for (TileRow wanted : desired) {
                addRow(wanted.key, wanted.spec, wanted.lockedKind,
                        wanted.tile, wanted.title);
            }
            if (rows.isEmpty()) {
                TextView empty = new TextView(getContext());
                empty.setText("No native Quick Settings tiles available");
                empty.setTextColor(palette.muted);
                empty.setTextSize(14);
                empty.setPadding(dp(12), dp(16), dp(12), dp(16));
                list.addView(empty);
            } else if (navigation == null || !navigation.isStatusBarFocused()) {
                Row target = null;
                if (selectedKey != null) {
                    for (Row candidate : rows) {
                        if (candidate.key.equals(selectedKey)) {
                            target = candidate;
                            break;
                        }
                    }
                    if (target == null) {
                        target = rows.get(Math.min(selectedIndex, rows.size() - 1));
                    }
                } else if (panelFocused) {
                    target = rows.get(0);
                }
                if (target != null) target.view.requestFocus();
            }
        } else {
            // Native tile-state callbacks update existing rows in place.
            for (int i = 0; i < desired.size(); i++) {
                TileRow wanted = desired.get(i);
                updateRow(rows.get(i), wanted.tile, wanted.title);
            }
        }
        updateFocus();
    }

    private static NativeQuickSettingsTiles.TileInfo find(
            List<NativeQuickSettingsTiles.TileInfo> tiles, String spec) {
        if (spec == null) return null;
        for (NativeQuickSettingsTiles.TileInfo tile : tiles) {
            if (spec.equals(tile.spec) && tile.available) return tile;
        }
        return null;
    }

    private void addRow(String key, String spec, int kind,
            NativeQuickSettingsTiles.TileInfo tile, CharSequence title) {
        if (!rows.isEmpty()) {
            View separator = new View(getContext());
            separator.setBackgroundColor(palette.divider);
            list.addView(separator, new LinearLayout.LayoutParams(
                    LayoutParams.MATCH_PARENT, dp(1)));
        }
        LinearLayout row = new LinearLayout(getContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(9), dp(4), dp(9), dp(4));
        row.setBackgroundColor(palette.card);
        row.setFocusable(true);
        row.setFocusableInTouchMode(true);
        list.addView(row, new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, dp(55)));

        ImageView icon = new ImageView(getContext());
        Drawable drawable = tile == null ? null :
                NativeQuickSettingsTiles.iconDrawable(getContext(), tile.icon);
        if (drawable != null) icon.setImageDrawable(drawable);
        else icon.setImageResource(android.R.drawable.ic_menu_manage);
        row.addView(icon, new LinearLayout.LayoutParams(dp(23), dp(23)));

        LinearLayout labels = new LinearLayout(getContext());
        labels.setOrientation(LinearLayout.VERTICAL);
        labels.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams labelsParams = new LinearLayout.LayoutParams(
                0, LayoutParams.MATCH_PARENT, 1);
        labelsParams.leftMargin = dp(9);
        row.addView(labels, labelsParams);

        TextView primary = new TextView(getContext());
        primary.setText(TextUtils.isEmpty(title) ? (spec == null ? "Unavailable" : spec) : title);
        primary.setTextSize(15);
        primary.setSingleLine(true);
        primary.setEllipsize(TextUtils.TruncateAt.END);
        labels.addView(primary);

        TextView secondary = new TextView(getContext());
        secondary.setText(tile == null ? "Unavailable" :
                tile.disabledByPolicy ? "Restricted" : tile.statusText());
        secondary.setTextSize(12);
        secondary.setSingleLine(true);
        secondary.setEllipsize(TextUtils.TruncateAt.END);
        labels.addView(secondary);

        Row item = new Row(key, spec, kind, tile, row, icon, primary, secondary);
        rows.add(item);
        String description = tile == null ? "Unavailable" :
                (tile.contentDescription == null ? String.valueOf(title == null ? spec : title)
                        : tile.contentDescription.toString());
        row.setContentDescription(description + ", " + secondary.getText());
        row.setOnClickListener(view -> dispatch(item, NativeQuickSettingsTiles.Action.PRIMARY));
        row.setOnLongClickListener(view -> {
            dispatch(item, NativeQuickSettingsTiles.Action.LONG_PRESS);
            return true;
        });
        updateRow(item, tile, title);
        row.setOnFocusChangeListener((view, focused) -> {
            if (focused && navigation != null) navigation.focusContent();
            updateFocus();
        });
        row.setOnKeyListener((view, code, event) -> onRowKey(item, code, event));
    }

    /** Refresh native icon, label, state and action eligibility without moving focus. */
    private void updateRow(Row row, NativeQuickSettingsTiles.TileInfo tile,
            CharSequence title) {
        row.tile = tile;
        Drawable icon = tile == null ? null
                : NativeQuickSettingsTiles.iconDrawable(getContext(), tile.icon);
        if (icon != null) row.icon.setImageDrawable(icon);
        else row.icon.setImageResource(android.R.drawable.ic_menu_manage);
        row.label.setText(TextUtils.isEmpty(title)
                ? (row.spec == null ? "Unavailable" : row.spec) : title);
        row.status.setText(tile == null ? "Unavailable"
                : tile.disabledByPolicy ? "Restricted" : tile.statusText());
        String description = tile == null ? "Unavailable"
                : tile.contentDescription == null ? String.valueOf(
                        TextUtils.isEmpty(title) ? row.spec : title)
                : tile.contentDescription.toString();
        row.view.setContentDescription(description + ", " + row.status.getText());
        row.view.setClickable(tile != null);
        row.view.setLongClickable(tile != null && tile.hasLongAction);
    }

    private void dispatch(Row row, NativeQuickSettingsTiles.Action action) {
        if (row.tile == null || row.spec == null) return;
        if (lockscreenMode && action != NativeQuickSettingsTiles.Action.PRIMARY) return;
        if (action == NativeQuickSettingsTiles.Action.SECONDARY
                && !row.tile.hasSecondaryAction) return;
        if (action == NativeQuickSettingsTiles.Action.LONG_PRESS
                && !row.tile.hasLongAction) return;
        listener.onAction(row.spec, row.lockedKind, action);
    }

    private boolean onRowKey(Row row, int code, KeyEvent event) {
        int index = rows.indexOf(row);
        if (index < 0) return false;
        if (code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN) {
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                if (code == KeyEvent.KEYCODE_DPAD_UP && index == 0) {
                    if (lockscreenMode) listener.onClose();
                    else if (navigation != null)
                        navigation.focusStatusBarFromPage(ShadeStatusNavigation.QUICK_SETTINGS);
                } else {
                    int next = QuickSettingsRowScrollPolicy.nextIndex(index, rows.size(),
                            code == KeyEvent.KEYCODE_DPAD_UP ? -1 : 1);
                    if (next >= 0) rows.get(next).view.requestFocus();
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
                dispatch(row, row.tile != null && row.tile.hasSecondaryAction
                        ? NativeQuickSettingsTiles.Action.SECONDARY
                        : NativeQuickSettingsTiles.Action.LONG_PRESS);
            }
            return true;
        }
        if (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER
                || code == KeyEvent.KEYCODE_NUMPAD_ENTER
                || (code == KeyEvent.KEYCODE_SPACE && !lockscreenMode)) {
            if (event.getAction() == KeyEvent.ACTION_UP && row.tile != null)
                row.view.performClick();
            return true;
        }
        return false;
    }

    private void updateFocus() {
        for (Row row : rows) {
            boolean focused = row.view.isFocused();
            boolean enabled = row.tile != null;
            row.view.setBackground(focused
                    ? BB7Theme.selected(dp(3), dp(1))
                    : null);
            if (!focused) row.view.setBackgroundColor(palette.card);
            int titleColor = focused ? Color.WHITE : enabled ? palette.title : palette.muted;
            int stateColor = focused ? Color.WHITE : palette.muted;
            row.label.setTextColor(titleColor);
            row.status.setTextColor(stateColor);
            row.icon.setImageTintList(ColorStateList.valueOf(
                    focused ? Color.WHITE : enabled ? palette.body : palette.muted));
        }
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        if (navigation != null && navigation.handleKey(event)) return true;
        int key = event.getKeyCode();
        if (key == KeyEvent.KEYCODE_BACK || key == KeyEvent.KEYCODE_ESCAPE) {
            if (event.getAction() == KeyEvent.ACTION_UP) listener.onClose();
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    @Override public boolean dispatchTouchEvent(MotionEvent event) {
        // Full QS must scroll vertically; the legacy three-radio swipe-to-exit
        // gesture is retained only for the compact locked panel.
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

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }
}
