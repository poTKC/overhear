package jp.tkc.st15iplayer;

import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;

/**
 * A bitmap drawn center-cropped into its bounds with rounded corners, through
 * a BitmapShader (works with hardware acceleration on API 18, unlike
 * clipPath). Give the ImageView scaleType fitXY so the bounds are the view.
 */
final class RoundedArt extends Drawable {
    private static Bitmap placeholder;

    private final Bitmap bitmap;
    private final float radius;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final BitmapShader shader;
    private final Matrix matrix = new Matrix();
    private final RectF rect = new RectF();

    RoundedArt(Bitmap bitmap, float radiusPx) {
        this.bitmap = bitmap;
        this.radius = radiusPx;
        shader = new BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
        paint.setShader(shader);
    }

    /** The artwork placeholder, decoded once (main thread). */
    static RoundedArt placeholder(Resources res, float radiusPx) {
        if (placeholder == null) placeholder = BitmapFactory.decodeResource(res, R.drawable.art_placeholder);
        return new RoundedArt(placeholder, radiusPx);
    }

    @Override protected void onBoundsChange(Rect b) {
        super.onBoundsChange(b);
        rect.set(b);
        float bw = bitmap.getWidth(), bh = bitmap.getHeight();
        float scale = Math.max(b.width() / bw, b.height() / bh);
        matrix.setScale(scale, scale);
        matrix.postTranslate(b.left + (b.width() - bw * scale) / 2f, b.top + (b.height() - bh * scale) / 2f);
        shader.setLocalMatrix(matrix);
    }

    @Override public void draw(Canvas canvas) {
        canvas.drawRoundRect(rect, radius, radius, paint);
    }

    @Override public void setAlpha(int alpha) {
        paint.setAlpha(alpha);
        invalidateSelf();
    }

    @Override public void setColorFilter(ColorFilter cf) {
        paint.setColorFilter(cf);
        invalidateSelf();
    }

    @Override public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
