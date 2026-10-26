package tv.glasshome;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.util.DisplayMetrics;
import android.view.View;

/**
 * The wallpaper for the current theme, drawn once in code (no image files, nothing downloaded),
 * and a blurred, brightened copy of it that every glass surface samples at its own screen
 * position. Sampling a pre-blurred copy gives the frosted-glass look without live blur, which
 * these TVs cannot afford.
 */
final class Backdrop {
    private static Backdrop instance;

    final String theme;
    final int screenW, screenH;
    final Bitmap sharp, frosted;

    static Backdrop get(Context c) {
        DisplayMetrics m = c.getResources().getDisplayMetrics();
        int w = Math.max(m.widthPixels, m.heightPixels), h = Math.min(m.widthPixels, m.heightPixels);
        Theme t = Theme.now;
        if (instance == null || instance.screenW != w || instance.screenH != h || !instance.theme.equals(t.id)) {
            instance = new Backdrop(t, w, h);
        }
        return instance;
    }

    private Backdrop(Theme t, int w, int h) {
        theme = t.id;
        screenW = w;
        screenH = h;
        int bw = Math.max(1, w / 3), bh = Math.max(1, h / 3);
        sharp = t.sky == null ? aurora(bw, bh) : sky(t, bw, bh);
        frosted = frost(sharp, t.frostSaturation, t.frostLift);
    }

    /** A vertical sky with a soft pool of light where the stage sits. */
    private static Bitmap sky(Theme t, int w, int h) {
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
        p.setShader(new LinearGradient(0, 0, 0, h, t.sky, t.skyAt, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, p);
        int pool = t.light ? 0x59FFFFFF : 0x1AFFFFFF;
        p.setShader(new RadialGradient(w * 0.5f, h * 0.36f, w * 0.42f,
                new int[] {pool, alpha(pool, 0.35f), 0x00FFFFFF}, new float[] {0f, 0.5f, 1f}, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, p);
        return b;
    }

    /** Deep navy with slow, overlapping pools of colour: an aurora behind frosted glass. */
    private static Bitmap aurora(int w, int h) {
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
        p.setShader(new LinearGradient(0, 0, w * 0.3f, h, 0xFF0A0F24, 0xFF04050C, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, p);
        blob(c, p, w * 0.12f, h * 0.10f, w * 0.55f, 0xCC3B2D9E);
        blob(c, p, w * 0.78f, h * 0.18f, w * 0.48f, 0xB31B5FD1);
        blob(c, p, w * 0.58f, h * 0.92f, w * 0.60f, 0xA60E8C9E);
        blob(c, p, w * 0.05f, h * 0.95f, w * 0.40f, 0x99852D86);
        blob(c, p, w * 0.95f, h * 0.70f, w * 0.30f, 0x80D46A3A);
        // A soft diagonal ribbon of light, the "liquid" in the glass.
        c.save();
        c.rotate(-14, w / 2f, h / 2f);
        p.setShader(new LinearGradient(0, h * 0.40f, 0, h * 0.62f,
                new int[] {0x00FFFFFF, 0x1FBFD8FF, 0x00FFFFFF}, null, Shader.TileMode.CLAMP));
        c.drawRect(-w * 0.2f, h * 0.40f, w * 1.2f, h * 0.62f, p);
        c.restore();
        // Darker towards the bottom so the dock and text read clearly.
        p.setShader(new LinearGradient(0, h * 0.45f, 0, h, 0x00000000, 0x8C000000, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, p);
        return b;
    }

    private static void blob(Canvas c, Paint p, float x, float y, float r, int color) {
        p.setShader(new RadialGradient(x, y, r, new int[] {color, alpha(color, 0.38f), alpha(color, 0f)},
                new float[] {0f, 0.45f, 1f}, Shader.TileMode.CLAMP));
        c.drawCircle(x, y, r, p);
    }

    private static int alpha(int color, float f) {
        return (Math.round((color >>> 24) * f) << 24) | (color & 0xFFFFFF);
    }

    /** Small, blurred, a little brighter and more saturated: what light looks like through glass. */
    private static Bitmap frost(Bitmap src, float saturation, int lift) {
        Bitmap blurred = blur(src, 4, 3);
        Bitmap out = Bitmap.createBitmap(blurred.getWidth(), blurred.getHeight(), Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        ColorMatrix cm = new ColorMatrix();
        cm.setSaturation(saturation);
        cm.postConcat(new ColorMatrix(new float[] {
                1.12f, 0, 0, 0, lift,
                0, 1.12f, 0, 0, lift,
                0, 0, 1.12f, 0, lift + 4,
                0, 0, 0, 1, 0}));
        Paint p = new Paint(Paint.FILTER_BITMAP_FLAG);
        p.setColorFilter(new ColorMatrixColorFilter(cm));
        c.drawBitmap(blurred, 0, 0, p);
        blurred.recycle();
        return out;
    }

    /**
     * {@code src} shrunk by {@code shrink} and box-blurred three times with {@code radius}: close
     * to a Gaussian blur of {@code shrink * radius} pixels, for very little work. The blur runs on
     * premultiplied colour, so transparent pixels fade a colour out instead of darkening it.
     */
    static Bitmap blur(Bitmap src, int shrink, int radius) {
        int w = Math.max(1, src.getWidth() / shrink), h = Math.max(1, src.getHeight() / shrink);
        Bitmap small = Bitmap.createScaledBitmap(src, w, h, true);
        int[] px = new int[w * h];
        small.getPixels(px, 0, w, 0, 0, w, h);
        if (small != src) small.recycle();
        premultiply(px, true);
        for (int i = 0; i < 3; i++) boxBlur(px, w, h, radius);
        premultiply(px, false);
        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        out.setPixels(px, 0, w, 0, 0, w, h);
        return out;
    }

    /** Colour times alpha ({@code on}), or back again; opaque pixels are left as they are. */
    private static void premultiply(int[] px, boolean on) {
        for (int i = 0; i < px.length; i++) {
            int c = px[i], a = c >>> 24;
            if (a == 255) continue;
            if (a == 0) {
                px[i] = 0;
                continue;
            }
            int r = (c >> 16) & 0xFF, g = (c >> 8) & 0xFF, b = c & 0xFF;
            if (on) {
                r = r * a / 255;
                g = g * a / 255;
                b = b * a / 255;
            } else {
                r = Math.min(255, r * 255 / a);
                g = Math.min(255, g * 255 / a);
                b = Math.min(255, b * 255 / a);
            }
            px[i] = a << 24 | r << 16 | g << 8 | b;
        }
    }

    /** One horizontal and one vertical running-sum pass. */
    private static void boxBlur(int[] px, int w, int h, int r) {
        int[] tmp = new int[px.length];
        pass(px, tmp, w, h, r, true);
        pass(tmp, px, w, h, r, false);
    }

    private static void pass(int[] in, int[] out, int w, int h, int r, boolean horizontal) {
        int lines = horizontal ? h : w, len = horizontal ? w : h;
        int div = 2 * r + 1;
        for (int l = 0; l < lines; l++) {
            int a = 0, rr = 0, g = 0, bb = 0;
            for (int k = -r; k <= r; k++) {
                int c = in[index(l, clamp(k, len), w, horizontal)];
                a += c >>> 24; rr += (c >> 16) & 0xFF; g += (c >> 8) & 0xFF; bb += c & 0xFF;
            }
            for (int i = 0; i < len; i++) {
                out[index(l, i, w, horizontal)] = ((a / div) << 24) | ((rr / div) << 16) | ((g / div) << 8) | (bb / div);
                int add = in[index(l, clamp(i + r + 1, len), w, horizontal)];
                int sub = in[index(l, clamp(i - r, len), w, horizontal)];
                a += (add >>> 24) - (sub >>> 24);
                rr += ((add >> 16) & 0xFF) - ((sub >> 16) & 0xFF);
                g += ((add >> 8) & 0xFF) - ((sub >> 8) & 0xFF);
                bb += (add & 0xFF) - (sub & 0xFF);
            }
        }
    }

    private static int clamp(int i, int len) {
        return i < 0 ? 0 : (i >= len ? len - 1 : i);
    }

    private static int index(int line, int i, int w, boolean horizontal) {
        return horizontal ? line * w + i : i * w + line;
    }

    /**
     * Full-screen wallpaper view; it never moves, the content scrolls over it. The focused app's
     * colour is pooled into a copy of the (small) wallpaper bitmap once per change, so every frame
     * paints the background once; only during the short crossfade are two copies painted.
     */
    static final class Wallpaper extends View {
        private final Backdrop b;
        private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
        private final Paint fade = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
        private final Rect src = new Rect();
        private final RectF dst = new RectF();
        private Bitmap now, before;
        private float mix = 1f;
        private int colour;
        private ValueAnimator anim;

        Wallpaper(Context c) {
            super(c);
            b = get(c);
            now = b.sharp;
        }

        /** Crossfades to the backdrop with {@code colour} pooled behind the stage. */
        void wash(int colour) {
            if (colour == this.colour && now != b.sharp) return;
            this.colour = colour;
            // Mid-fade, the picture on screen is closest to the one being faded in.
            before = mix >= 0.5f ? now : before;
            now = pool(colour);
            if (anim != null) anim.cancel();
            mix = 0f;
            anim = ValueAnimator.ofFloat(0f, 1f);
            anim.setDuration(420);
            anim.setInterpolator(Ui.EASE);
            anim.addUpdateListener(a -> {
                mix = (float) a.getAnimatedValue();
                invalidate();
            });
            anim.start();
        }

        private Bitmap pool(int colour) {
            Bitmap out = b.sharp.copy(Bitmap.Config.ARGB_8888, true);
            int w = out.getWidth(), h = out.getHeight(), a = Theme.now.tintAlpha, col = Ui.glow(colour);
            Paint p = new Paint(Paint.DITHER_FLAG);
            p.setShader(new RadialGradient(w * 0.5f, h * 0.36f, w * 0.62f,
                    new int[] {Ui.alpha(col, a), Ui.alpha(col, a * 2 / 5), Ui.alpha(col, 0)},
                    new float[] {0f, 0.5f, 1f}, Shader.TileMode.CLAMP));
            new Canvas(out).drawRect(0, 0, w, h, p);
            return out;
        }

        @Override
        protected void onDetachedFromWindow() {
            if (anim != null) anim.cancel();
            super.onDetachedFromWindow();
        }

        @Override
        protected void onDraw(Canvas c) {
            src.set(0, 0, b.sharp.getWidth(), b.sharp.getHeight());
            dst.set(0, 0, getWidth(), getHeight());
            if (mix < 1f && before != null) {
                c.drawBitmap(before, src, dst, paint);
                fade.setAlpha(Math.round(mix * 255));
                c.drawBitmap(now, src, dst, fade);
            } else {
                c.drawBitmap(now, src, dst, paint);
            }
        }
    }
}
