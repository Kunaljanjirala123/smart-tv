package tv.glasshome;

import android.content.Context;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.View;
import android.widget.FrameLayout;

import java.util.ArrayList;
import java.util.List;

/**
 * Liquid glass: the frosted backdrop seen through the surface at its exact screen position,
 * a body tinted for the theme, a sheen across the top and a bright rim that fades towards the
 * bottom.
 */
final class Glass {
    /** Every live glass surface, so they can be redrawn when the content scrolls under them. */
    private static final List<Frame> live = new ArrayList<>();

    private final Backdrop backdrop;
    private final Paint frost = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint bodyPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint sheen = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final BitmapShader shader;
    private final Matrix matrix = new Matrix();
    private final int[] loc = new int[2];
    private final RectF r = new RectF();
    private float lastH = -1;
    private final float rimWidth;

    /** Body colour over the frost, then the sheen and rim alphas: panels use more body. */
    int body = Theme.now.glassBody;
    int sheenAlpha = Theme.now.glassSheen;
    int rimTop = Theme.now.rimTop, rimBottom = Theme.now.rimBottom;

    Glass(Context c) {
        backdrop = Backdrop.get(c);
        shader = new BitmapShader(backdrop.frosted, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
        frost.setShader(shader);
        rimWidth = Ui.dp(c, 1.2f);
        rim.setStyle(Paint.Style.STROKE);
        rim.setStrokeWidth(rimWidth);
    }

    /** Draws a glass rounded rect filling {@code w x h} of view {@code v}. */
    void draw(Canvas c, View v, float w, float h, float radius) {
        v.getLocationOnScreen(loc);
        float sx = (float) backdrop.screenW / backdrop.frosted.getWidth();
        float sy = (float) backdrop.screenH / backdrop.frosted.getHeight();
        matrix.setScale(sx, sy);
        matrix.postTranslate(-loc[0], -loc[1]);
        shader.setLocalMatrix(matrix);
        r.set(0, 0, w, h);
        c.drawRoundRect(r, radius, radius, frost);
        bodyPaint.setColor(body);
        c.drawRoundRect(r, radius, radius, bodyPaint);
        if (h != lastH) {
            lastH = h;
            sheen.setShader(new LinearGradient(0, 0, 0, h * 0.6f,
                    sheenAlpha << 24 | 0xFFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
            rim.setShader(new LinearGradient(0, 0, 0, h,
                    rimTop << 24 | 0xFFFFFF, rimBottom << 24 | 0xFFFFFF, Shader.TileMode.CLAMP));
        }
        c.drawRoundRect(r, radius, radius, sheen);
        float in = rimWidth / 2f;
        r.inset(in, in);
        c.drawRoundRect(r, radius - in, radius - in, rim);
    }

    /** Redraws every glass surface; call when content moves under them. */
    static void invalidateAll() {
        for (Frame f : live) f.invalidate();
    }

    /** A container whose background is liquid glass. */
    static class Frame extends FrameLayout {
        private final Glass glass;
        float radius;

        Frame(Context c, float radiusDp) {
            super(c);
            glass = new Glass(c);
            radius = Ui.dp(c, radiusDp);
            setWillNotDraw(false);
            setClipToPadding(false);
            setClipChildren(false);
        }

        Glass glass() {
            return glass;
        }

        @Override
        protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            live.add(this);
        }

        @Override
        protected void onDetachedFromWindow() {
            live.remove(this);
            super.onDetachedFromWindow();
        }

        @Override
        protected void onDraw(Canvas c) {
            glass.draw(c, this, getWidth(), getHeight(), Math.min(radius, getHeight() / 2f));
        }
    }
}
