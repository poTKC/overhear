package com.potkc.overhear;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

import io.nayuki.qrcodegen.QrCode;

/**
 * Draws a QrCode as black modules on white with a 4-module quiet zone,
 * scaled to the largest whole number of pixels per module that fits and
 * centred in the view. Whole pixels and no anti-aliasing keep the module
 * edges sharp for the scanning phone. Main thread only; draws nothing until
 * setCode() is given a code, or if the code does not fit at 1 px per module.
 */
final class QrView extends View {
    private static final int QUIET_ZONE = 4;

    private final Paint white = new Paint();
    private final Paint black = new Paint();
    private QrCode code;

    QrView(Context context) {
        this(context, null);
    }

    QrView(Context context, AttributeSet attrs) {
        super(context, attrs);
        white.setAntiAlias(false);
        white.setColor(Color.WHITE);
        black.setAntiAlias(false);
        black.setColor(Color.BLACK);
    }

    void setCode(QrCode code) {
        this.code = code;
        invalidate();
    }

    @Override protected void onDraw(Canvas canvas) {
        QrCode c = code;
        if (c == null) return;
        int w = getWidth() - getPaddingLeft() - getPaddingRight();
        int h = getHeight() - getPaddingTop() - getPaddingBottom();
        int modules = c.size + 2 * QUIET_ZONE;
        int scale = Math.min(w, h) / modules;
        if (scale < 1) return;
        int side = modules * scale;
        int left = getPaddingLeft() + (w - side) / 2;
        int top = getPaddingTop() + (h - side) / 2;
        canvas.drawRect(left, top, left + side, top + side, white);
        int x0 = left + QUIET_ZONE * scale;
        int y0 = top + QUIET_ZONE * scale;
        for (int y = 0; y < c.size; y++) {
            for (int x = 0; x < c.size; x++) {
                if (!c.getModule(x, y)) continue;
                int px = x0 + x * scale;
                int py = y0 + y * scale;
                canvas.drawRect(px, py, px + scale, py + scale, black);
            }
        }
    }
}
