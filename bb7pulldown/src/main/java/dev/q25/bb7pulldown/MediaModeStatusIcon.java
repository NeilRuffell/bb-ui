package dev.q25.bb7pulldown;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;

/**
 * Read-only, theme-tinted shuffle/repeat status glyphs for the collapsed bar.
 * These are indicators, not playback buttons or trackpad focus targets.
 */
final class MediaModeStatusIcon extends View {
    static final int SHUFFLE = 0;
    static final int REPEAT = 1;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final int kind;
    private boolean repeatOne;

    MediaModeStatusIcon(Context context, int kind) {
        super(context);
        this.kind = kind;
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
        paint.setStrokeWidth(1.8f);
        setFocusable(false);
        setClickable(false);
        // Parent media row describes the active states for accessibility.
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        setVisibility(GONE);
    }

    void setTint(int color) {
        paint.setColor(color);
        invalidate();
    }

    void setRepeatOne(boolean one) {
        if (repeatOne != one) {
            repeatOne = one;
            invalidate();
        }
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int save = canvas.save();
        float size = Math.min(getWidth(), getHeight()) * 0.78f;
        if (size <= 0) {
            canvas.restoreToCount(save);
            return;
        }
        canvas.translate((getWidth() - size) * 0.5f,
                (getHeight() - size) * 0.5f);
        canvas.scale(size / 24f, size / 24f);
        path.reset();
        if (kind == SHUFFLE) {
            // Crossed lines with independent forward arrowheads.
            path.moveTo(3, 6);
            path.cubicTo(10, 6, 12, 18, 21, 18);
            path.moveTo(3, 18);
            path.cubicTo(10, 18, 12, 6, 21, 6);
            path.moveTo(17, 3);
            path.lineTo(21, 6);
            path.lineTo(17, 9);
            path.moveTo(17, 15);
            path.lineTo(21, 18);
            path.lineTo(17, 21);
        } else {
            // Parallel return arrows, including the "repeat one" state.
            path.moveTo(5, 10);
            path.lineTo(5, 8);
            path.quadTo(5, 6, 7, 6);
            path.lineTo(20, 6);
            path.moveTo(16, 3);
            path.lineTo(20, 6);
            path.lineTo(16, 9);
            path.moveTo(19, 14);
            path.lineTo(19, 16);
            path.quadTo(19, 18, 17, 18);
            path.lineTo(4, 18);
            path.moveTo(8, 15);
            path.lineTo(4, 18);
            path.lineTo(8, 21);
        }
        canvas.drawPath(path, paint);
        if (kind == REPEAT && repeatOne) {
            Paint label = new Paint(paint);
            label.setStyle(Paint.Style.FILL);
            label.setTextAlign(Paint.Align.CENTER);
            label.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            label.setTextSize(9f);
            canvas.drawText("1", 12, 15f, label);
        }
        canvas.restoreToCount(save);
    }
}
