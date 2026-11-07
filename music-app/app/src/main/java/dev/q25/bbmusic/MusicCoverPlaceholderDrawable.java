package dev.q25.bbmusic;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;

/** Neutral cover placeholder when the selected album has no embedded art. */
final class MusicCoverPlaceholderDrawable extends Drawable {
    private final Paint background = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint note = new Paint(Paint.ANTI_ALIAS_FLAG);

    MusicCoverPlaceholderDrawable() {
        background.setColor(0xff263443);
        edge.setColor(0xff667b90);
        edge.setStyle(Paint.Style.STROKE);
        edge.setStrokeWidth(1.5f);
        note.setColor(0xffa8bcd0);
        note.setStyle(Paint.Style.FILL);
    }

    @Override public void draw(Canvas canvas) {
        Rect b = getBounds();
        float x = b.left, y = b.top, w = b.width(), h = b.height();
        canvas.drawRect(b, background);
        canvas.drawRect(x + 1, y + 1, x + w - 1, y + h - 1, edge);
        if (w <= 0 || h <= 0) return;
        // Simple music note, deliberately independent of BB UI resources.
        float stemX = x + w * 0.61f;
        float stemTop = y + h * 0.19f;
        float stemBottom = y + h * 0.69f;
        float stemWidth = Math.max(2f, w * 0.075f);
        canvas.drawRect(stemX, stemTop, stemX + stemWidth, stemBottom, note);
        canvas.drawOval(x + w * 0.30f, y + h * 0.60f,
                x + w * 0.64f, y + h * 0.80f, note);
        canvas.drawRect(stemX, stemTop, x + w * 0.79f,
                stemTop + Math.max(3f, h * 0.09f), note);
    }

    @Override public void setAlpha(int alpha) {
        note.setAlpha(alpha);
        edge.setAlpha(alpha);
        background.setAlpha(alpha);
        invalidateSelf();
    }

    @Override public void setColorFilter(ColorFilter filter) {
        note.setColorFilter(filter);
        edge.setColorFilter(filter);
        background.setColorFilter(filter);
        invalidateSelf();
    }

    @Override public int getOpacity() { return PixelFormat.OPAQUE; }
}
