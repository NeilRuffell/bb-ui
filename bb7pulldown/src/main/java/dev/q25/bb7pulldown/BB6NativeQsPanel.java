package dev.q25.bb7pulldown;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
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
    private final List<Row> rows = new ArrayList<>();
    private boolean active;
    private float downY;
    private float downX;
    private boolean closingSwipe;

    private static final class Row {
        final String key;
        final String spec;
        final int lockedKind;
        final NativeQuickSettingsTiles.TileInfo tile;
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

        ScrollView scroll = new ScrollView(context);
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
        String currentFocus = null;
        for (Row row : rows) {
            if (row.view.isFocused()) {
                currentFocus = row.key;
                break;
            }
        }
        List<NativeQuickSettingsTiles.TileInfo> nativeTiles = nativeSession.snapshot();
        list.removeAllViews();
        rows.clear();
        if (lockscreenMode) {
            for (int kind : LockscreenQuickSettingsPolicy.visibleKinds()) {
                String spec = allowedSpecForKind(kind, nativeTiles);
                NativeQuickSettingsTiles.TileInfo tile = find(nativeTiles, spec);
                addRow("locked:" + kind, spec, kind, tile,
                        ConnectionControlPolicy.label(kind));
            }
        } else {
            for (NativeQuickSettingsTiles.TileInfo tile : nativeTiles) {
                if (tile.available) addRow(tile.spec, tile.spec, UNLOCKED, tile,
                        tile.label);
            }
        }
        if (rows.isEmpty()) {
            TextView empty = new TextView(getContext());
            empty.setText("No native Quick Settings tiles available");
            empty.setTextColor(palette.muted);
            empty.setTextSize(14);
            empty.setPadding(dp(12), dp(16), dp(12), dp(16));
            list.addView(empty);
        }
        if (currentFocus != null) {
            for (Row row : rows) {
                if (row.key.equals(currentFocus)) {
                    row.view.requestFocus();
                    break;
                }
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

    /**
     * Android 17 may use the combined "internet" tile or separate
     * "wifi" / "cell" specs. Select only INSTALLED native tiles;
     * never synthesize separate radio backends or new QS tile instances.
     */
    private static String allowedSpecForKind(int kind,
            List<NativeQuickSettingsTiles.TileInfo> tiles) {
        if (kind == ConnectionControlPolicy.MOBILE) {
            if (find(tiles, "cell") != null) return "cell";
            if (find(tiles, "internet") != null) return "internet";
        } else if (kind == ConnectionControlPolicy.WIFI) {
            if (find(tiles, "wifi") != null) return "wifi";
            if (find(tiles, "internet") != null) return "internet";
        } else if (kind == ConnectionControlPolicy.BLUETOOTH) {
            if (find(tiles, "bt") != null) return "bt";
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
        row.setClickable(tile != null);
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
        if (tile != null) {
            row.setOnClickListener(view -> dispatch(item, NativeQuickSettingsTiles.Action.PRIMARY));
            row.setOnLongClickListener(view -> {
                dispatch(item, NativeQuickSettingsTiles.Action.LONG_PRESS);
                return true;
            });
        }
        row.setOnFocusChangeListener((view, focused) -> {
            if (focused && navigation != null) navigation.focusContent();
            updateFocus();
        });
        row.setOnKeyListener((view, code, event) -> onRowKey(item, code, event));
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
                    int next = Math.max(0, Math.min(rows.size() - 1,
                            index + (code == KeyEvent.KEYCODE_DPAD_UP ? -1 : 1)));
                    rows.get(next).view.requestFocus();
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
