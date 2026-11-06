package dev.q25.bb7pulldown;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;

/**
 * Simple monochrome category glyphs drawn at 24-unit scale.
 * Independent of launcher app resources for eventual ROM/SystemUI reuse.
 */
final class NotificationTypeIconDrawable extends Drawable {
    private final String category;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private int alpha = 255;

    NotificationTypeIconDrawable(String category) {
        this.category = category;
        paint.setColor(Color.WHITE);
        paint.setStrokeWidth(1.9f);
        paint.setStrokeJoin(Paint.Join.ROUND);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStyle(Paint.Style.STROKE);
    }

    @Override public void draw(Canvas canvas) {
        Rect bounds = getBounds();
        if (bounds.isEmpty()) return;
        float side = Math.min(bounds.width(), bounds.height());
        int save = canvas.save();
        canvas.translate(bounds.exactCenterX() - side / 2f,
                bounds.exactCenterY() - side / 2f);
        canvas.scale(side / 24f, side / 24f);
        paint.setStrokeWidth(1.9f);
        paint.setStyle(Paint.Style.STROKE);

        if (NotificationTypePolicy.MESSAGES.equals(category)) {
            canvas.drawRoundRect(3, 4, 21, 17, 3, 3, paint);
            Path tail = new Path();
            tail.moveTo(8, 17);
            tail.lineTo(6, 21);
            tail.lineTo(13, 17);
            canvas.drawPath(tail, paint);
        } else if (NotificationTypePolicy.EMAIL.equals(category)) {
            canvas.drawRoundRect(3, 6, 21, 18, 1.5f, 1.5f, paint);
            Path envelope = new Path();
            envelope.moveTo(3, 7);
            envelope.lineTo(12, 13);
            envelope.lineTo(21, 7);
            canvas.drawPath(envelope, paint);
        } else if (NotificationTypePolicy.CALLS.equals(category)) {
            Path handset = new Path();
            handset.moveTo(6.5f, 3.5f);
            handset.lineTo(4.5f, 6);
            handset.cubicTo(3.8f, 10, 13.7f, 20, 18, 19.5f);
            handset.lineTo(20.5f, 17);
            handset.lineTo(16, 13.5f);
            handset.lineTo(13.8f, 16);
            handset.cubicTo(11.5f, 14.9f, 9.2f, 12.5f, 8, 10);
            handset.lineTo(10.2f, 8);
            handset.close();
            canvas.drawPath(handset, paint);
        } else if (NotificationTypePolicy.CALENDAR.equals(category)) {
            canvas.drawRoundRect(4, 5, 20, 21, 2, 2, paint);
            canvas.drawLine(4, 10, 20, 10, paint);
            canvas.drawLine(8, 3, 8, 7, paint);
            canvas.drawLine(16, 3, 16, 7, paint);
            canvas.drawLine(8, 14, 10, 14, paint);
            canvas.drawLine(14, 14, 16, 14, paint);
        } else if (NotificationTypePolicy.REMINDERS.equals(category)) {
            for (int y : new int[]{6, 12, 18}) {
                canvas.drawRect(4, y - 1, 6, y + 1, paint);
                canvas.drawLine(10, y, 20, y, paint);
            }
        } else if (NotificationTypePolicy.ALARMS.equals(category)) {
            canvas.drawCircle(12, 13, 7.2f, paint);
            canvas.drawLine(12, 13, 12, 8.5f, paint);
            canvas.drawLine(12, 13, 15, 15, paint);
            canvas.drawLine(4, 6, 6.5f, 3.5f, paint);
            canvas.drawLine(20, 6, 17.5f, 3.5f, paint);
        } else {
            Path bell = new Path();
            bell.moveTo(6, 17);
            bell.lineTo(8, 15);
            bell.lineTo(8, 10);
            bell.cubicTo(8, 4, 16, 4, 16, 10);
            bell.lineTo(16, 15);
            bell.lineTo(18, 17);
            bell.close();
            canvas.drawPath(bell, paint);
            canvas.drawArc(10, 17, 14, 21, 0, 180, false, paint);
        }
        canvas.restoreToCount(save);
    }

    @Override public void setAlpha(int alpha) {
        this.alpha = alpha;
        paint.setAlpha(alpha);
        invalidateSelf();
    }

    @Override public void setColorFilter(ColorFilter filter) {
        paint.setColorFilter(filter);
        invalidateSelf();
    }

    @Override public int getOpacity() {
        return alpha == 255 ? PixelFormat.TRANSLUCENT : PixelFormat.TRANSLUCENT;
    }

    @Override public int getIntrinsicWidth() { return 24; }
    @Override public int getIntrinsicHeight() { return 24; }
}
