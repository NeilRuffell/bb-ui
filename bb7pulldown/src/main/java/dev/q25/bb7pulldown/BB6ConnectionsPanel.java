package dev.q25.bb7pulldown;

import dev.q25.trackpad.theme.BB7Theme;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Curve 9300 / BBOS6 Manage Connections layout only.
 * Android SystemUI remains the authority for radio settings and QS actions.
 */
final class BB6ConnectionsPanel extends FrameLayout {
    interface Listener {
        void onClose();
        void onActivate(int kind);
    }

    private final BB7Theme.Palette palette;
    private final Listener listener;
    private final ShadeUiController navigation;
    private final boolean lockscreenMode;
    private final int[] visibleKinds;
    private final LinearLayout[] rows;
    private final TextView[] labels;
    private final TextView[] statuses;
    private final StatusMark[] marks;
    private float downY;
    private float downX;
    private boolean closeStarted;

    BB6ConnectionsPanel(Context context, Listener listener,
            ShadeUiController navigation) {
        this(context, listener, navigation, false);
    }

    /**
     * Both screens use the same connection rows and Android state provider.
     * The locked presentation has no independent status-zone controller,
     * and cannot navigate into the unlocked shade.
     */
    BB6ConnectionsPanel(Context context, Listener listener,
            ShadeUiController navigation, boolean lockscreenMode) {
        super(context);
        this.listener = listener;
        this.navigation = navigation;
        this.lockscreenMode = lockscreenMode;
        if (lockscreenMode) {
            visibleKinds = LockscreenQuickSettingsPolicy.visibleKinds();
        } else {
            visibleKinds = new int[ConnectionControlPolicy.COUNT];
            for (int i = 0; i < visibleKinds.length; i++) visibleKinds[i] = i;
        }
        rows = new LinearLayout[visibleKinds.length];
        labels = new TextView[visibleKinds.length];
        statuses = new TextView[visibleKinds.length];
        marks = new StatusMark[visibleKinds.length];
        palette = BB7Theme.palette(context);
        setBackgroundColor(palette.canvas);
        setFocusable(true);
        setFocusableInTouchMode(true);

        LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        addView(content, new LayoutParams(LayoutParams.MATCH_PARENT,
                LayoutParams.MATCH_PARENT));

        // UnifiedShadeHost renders the common date strip above this page.

        LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundColor(palette.card);
        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        cardParams.setMargins(dp(7), dp(8), dp(7), 0);
        content.addView(card, cardParams);

        TextView heading = new TextView(context);
        heading.setText("Manage Connections");
        heading.setTypeface(null, android.graphics.Typeface.BOLD);
        heading.setTextColor(palette.muted);
        heading.setTextSize(14);
        heading.setGravity(Gravity.CENTER_VERTICAL);
        heading.setPadding(dp(10), 0, dp(10), 0);
        heading.setBackgroundColor(palette.heading);
        card.addView(heading, new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, dp(31)));

        for (int position = 0; position < visibleKinds.length; position++) {
            final int kind = visibleKinds[position];
            if (position != 0) {
                View divider = new View(context);
                divider.setBackgroundColor(palette.divider);
                LinearLayout.LayoutParams rule = new LinearLayout.LayoutParams(
                        LayoutParams.MATCH_PARENT, dp(1));
                rule.setMargins(dp(6), 0, dp(6), 0);
                card.addView(divider, rule);
            }
            final int index = position;
            LinearLayout row = new LinearLayout(context);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(8), dp(3), dp(8), dp(3));
            row.setFocusable(true);
            row.setFocusableInTouchMode(true);
            row.setClickable(true);
            row.setOnClickListener(view -> listener.onActivate(kind));
            row.setOnFocusChangeListener((view, focused) -> {
                if (focused && navigation != null) navigation.focusContent();
                updateSelection();
            });
            row.setOnKeyListener((view, code, event) -> onRowKey(index, code, event));
            card.addView(row, new LinearLayout.LayoutParams(
                    LayoutParams.MATCH_PARENT, dp(52)));
            rows[position] = row;

            RadioIcon icon = new RadioIcon(context, kind, palette.body);
            row.addView(icon, new LinearLayout.LayoutParams(dp(25), dp(25)));

            TextView label = new TextView(context);
            label.setText(ConnectionControlPolicy.label(kind));
            label.setTextColor(palette.title);
            label.setTextSize(15);
            label.setSingleLine(true);
            label.setEllipsize(TextUtils.TruncateAt.END);
            label.setGravity(Gravity.CENTER_VERTICAL);
            LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(
                    0, LayoutParams.MATCH_PARENT, 1f);
            labelParams.leftMargin = dp(7);
            row.addView(label, labelParams);
            labels[position] = label;

            TextView status = new TextView(context);
            status.setText("Unknown");
            status.setTextColor(palette.muted);
            status.setTextSize(12);
            status.setSingleLine(true);
            status.setGravity(Gravity.CENTER_VERTICAL | Gravity.END);
            row.addView(status, new LinearLayout.LayoutParams(
                    LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT));
            statuses[position] = status;

            StatusMark mark = new StatusMark(context, palette.muted);
            LinearLayout.LayoutParams markParams =
                    new LinearLayout.LayoutParams(dp(17), dp(17));
            markParams.leftMargin = dp(6);
            row.addView(mark, markParams);
            marks[position] = mark;
        }

        refresh(NativeConnectionControls.read(context));
        updateSelection();
    }

    void focusFirst() {
        if (navigation != null) navigation.focusContent();
        rows[0].requestFocus();
    }

    private void enterStatusZone() {
        if (lockscreenMode) {
            listener.onClose();
        } else {
            requestFocus();
            navigation.focusStatusBarFromPage(ShadeStatusNavigation.QUICK_SETTINGS);
        }
    }

    void refresh(NativeConnectionControls.Status snapshot) {
        for (int i = 0; i < visibleKinds.length; i++) {
            int kind = visibleKinds[i];
            Boolean state = snapshot == null ? null : snapshot.forKind(kind);
            statuses[i].setText(ConnectionControlPolicy.stateLabel(kind, state));
            statuses[i].setTag(state);
            marks[i].setChecked(ConnectionControlPolicy.checked(state));
            rows[i].setContentDescription(ConnectionControlPolicy.label(kind) + ", "
                    + ConnectionControlPolicy.stateLabel(kind, state)
                    + (lockscreenMode ? ", native Quick Settings control"
                            : ", open native connection controls"));
        }
        updateSelection();
    }

    private void updateSelection() {
        for (int i = 0; i < rows.length; i++) {
            boolean selected = rows[i].isFocused();
            if (selected) {
                rows[i].setBackground(BB7Theme.selected(dp(3), dp(1)));
                labels[i].setTextColor(Color.WHITE);
                statuses[i].setTextColor(Color.WHITE);
                marks[i].setInk(Color.WHITE);
            } else {
                rows[i].setBackgroundColor(palette.card);
                labels[i].setTextColor(palette.title);
                Boolean state = (Boolean) statuses[i].getTag();
                statuses[i].setTextColor(Boolean.TRUE.equals(state) ? 0xff159a37
                        : palette.muted);
                marks[i].setInk(palette.muted);
            }
        }
    }

    private boolean onRowKey(int index, int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_DPAD_UP
                || keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                if (keyCode == KeyEvent.KEYCODE_DPAD_UP && index == 0) {
                    enterStatusZone();
                } else {
                    int target = Math.max(0, Math.min(rows.length - 1,
                            index + (keyCode == KeyEvent.KEYCODE_DPAD_UP ? -1 : 1)));
                    rows[target].requestFocus();
                }
            }
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT
                || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) return true;
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER
                || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER
                || keyCode == KeyEvent.KEYCODE_SPACE) {
            // Space is NOT an activation key on the locked panel. The
            // WindowRootView-level Space-to-password hook runs first.
            if (lockscreenMode && keyCode == KeyEvent.KEYCODE_SPACE) return true;
            if (event.getAction() == KeyEvent.ACTION_UP) rows[index].performClick();
            return true;
        }
        return false;
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        if (navigation != null && navigation.handleKey(event)) {
            return true;
        }
        if (event.getKeyCode() == KeyEvent.KEYCODE_BACK
                || event.getKeyCode() == KeyEvent.KEYCODE_ESCAPE) {
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
                closeStarted = false;
                break;
            case MotionEvent.ACTION_MOVE:
                if (!closeStarted && downY - event.getRawY() > dp(65)
                        && Math.abs(downY - event.getRawY())
                        > Math.abs(downX - event.getRawX()) * 1.2f) {
                    closeStarted = true;
                    listener.onClose();
                    return true;
                }
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (closeStarted) return true;
                break;
            default:
                break;
        }
        return super.dispatchTouchEvent(event);
    }

    private int dp(int n) {
        return (int) (n * getResources().getDisplayMetrics().density + 0.5f);
    }

    private static final class StatusMark extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF bounds = new RectF();
        private boolean checked;
        private int ink;
        StatusMark(Context context, int ink) {
            super(context);
            this.ink = ink;
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        }
        void setChecked(boolean checked) {
            this.checked = checked;
            invalidate();
        }
        void setInk(int color) {
            ink = color;
            invalidate();
        }
        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float unit = getResources().getDisplayMetrics().density;
            float inset = unit;
            bounds.set(inset, inset, getWidth() - inset, getHeight() - inset);
            paint.setColor(ink);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(unit * 1.1f);
            canvas.drawRoundRect(bounds, unit * 1.5f, unit * 1.5f, paint);
            if (checked) {
                Path tick = new Path();
                tick.moveTo(getWidth() * .22f, getHeight() * .50f);
                tick.lineTo(getWidth() * .43f, getHeight() * .71f);
                tick.lineTo(getWidth() * .80f, getHeight() * .28f);
                paint.setStrokeWidth(unit * 2f);
                paint.setStrokeCap(Paint.Cap.ROUND);
                paint.setStrokeJoin(Paint.Join.ROUND);
                canvas.drawPath(tick, paint);
            }
        }
    }

    private static final class RadioIcon extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final int kind;
        RadioIcon(Context context, int kind, int color) {
            super(context);
            this.kind = kind;
            paint.setColor(color);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);
        }
        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float cx = getWidth() * .5f, cy = getHeight() * .5f;
            float s = Math.min(getWidth(), getHeight()) * .36f;
            paint.setStrokeWidth(Math.max(1f, s * .17f));
            if (kind == ConnectionControlPolicy.WIFI) {
                RectF rect = new RectF(cx - s, cy - s * .60f,
                        cx + s, cy + s * 1.40f);
                canvas.drawArc(rect, 225, 90, false, paint);
                rect.inset(s * .35f, s * .35f);
                canvas.drawArc(rect, 225, 90, false, paint);
                paint.setStyle(Paint.Style.FILL);
                canvas.drawCircle(cx, cy + s * .64f, s * .12f, paint);
                paint.setStyle(Paint.Style.STROKE);
            } else if (kind == ConnectionControlPolicy.BLUETOOTH) {
                Path rune = new Path();
                rune.moveTo(cx, cy - s);
                rune.lineTo(cx + s * .65f, cy - s * .40f);
                rune.lineTo(cx - s * .65f, cy + s * .67f);
                rune.moveTo(cx, cy + s);
                rune.lineTo(cx + s * .65f, cy + s * .40f);
                rune.lineTo(cx - s * .65f, cy - s * .67f);
                rune.moveTo(cx, cy - s);
                rune.lineTo(cx, cy + s);
                canvas.drawPath(rune, paint);
            } else {
                canvas.drawLine(cx, cy - s * .2f, cx, cy + s, paint);
                canvas.drawLine(cx - s * .4f, cy + s, cx + s * .4f, cy + s, paint);
                RectF rect = new RectF(cx - s * .70f, cy - s,
                        cx + s * .70f, cy + s * .45f);
                canvas.drawArc(rect, 190, 160, false, paint);
                rect.inset(-s * .35f, -s * .35f);
                canvas.drawArc(rect, 190, 160, false, paint);
            }
        }
    }
}
