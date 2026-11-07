package dev.q25.bbmusic;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;

/**
 * Expanded media artwork/metadata backdrop only.
 *
 * The radial circle is centered at the right-middle edge of the content,
 * leaving its left semicircle visible. It fades from #393c4a to black near
 * the right edge of the left-hand artwork area.
 */
final class HalfRadialGradientDrawable extends Drawable {
    private static final int CIRCLE_COLOR = 0xff393c4a;
    private static final float RADIUS_FRACTION = 0.56f;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

    @Override protected void onBoundsChange(Rect bounds) {
        super.onBoundsChange(bounds);
        if (bounds.isEmpty()) {
            paint.setShader(null);
            paint.setColor(Color.BLACK);
            return;
        }
        paint.setShader(new RadialGradient(
                bounds.right,
                bounds.exactCenterY(),
                Math.max(1f, bounds.width() * RADIUS_FRACTION),
                CIRCLE_COLOR,
                Color.BLACK,
                Shader.TileMode.CLAMP));
    }

    @Override public void draw(Canvas canvas) {
        canvas.drawRect(getBounds(), paint);
    }

    @Override public void setAlpha(int alpha) {
        paint.setAlpha(alpha);
        invalidateSelf();
    }

    @Override public void setColorFilter(ColorFilter colorFilter) {
        paint.setColorFilter(colorFilter);
        invalidateSelf();
    }

    @Override public int getOpacity() {
        return paint.getAlpha() == 255 ? PixelFormat.OPAQUE : PixelFormat.TRANSLUCENT;
    }
}
