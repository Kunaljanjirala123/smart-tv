package tv.glasshome;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Typeface;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.Interpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.TextView;

/** Small shared helpers: sizes, text styles, colour arithmetic and the shared easing curves. */
final class Ui {
    static final Typeface THIN = Typeface.create("sans-serif-thin", Typeface.NORMAL);
    static final Typeface LIGHT = Typeface.create("sans-serif-light", Typeface.NORMAL);
    static final Typeface REGULAR = Typeface.create("sans-serif", Typeface.NORMAL);
    static final Typeface MEDIUM = Typeface.create("sans-serif-medium", Typeface.NORMAL);
    static final Typeface BOLD = Typeface.create("sans-serif", Typeface.BOLD);

    /** A small, springy overshoot for things that grow into focus. */
    static final Interpolator POP = new OvershootInterpolator(1.4f);
    static final Interpolator EASE = new DecelerateInterpolator(1.6f);

    /**
     * Shadows follow a view's outline. Pills, circles and glass have no shaped background to take
     * one from (or one the renderer reads as a rectangle), so they say their shape here.
     */
    static final ViewOutlineProvider PILL = new ViewOutlineProvider() {
        @Override
        public void getOutline(View v, Outline o) {
            o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), Math.min(v.getWidth(), v.getHeight()) / 2f);
        }
    };

    static ViewOutlineProvider rounded(final float radius) {
        return new ViewOutlineProvider() {
            @Override
            public void getOutline(View v, Outline o) {
                o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), radius);
            }
        };
    }

    private Ui() {}

    static int dp(Context c, float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                c.getResources().getDisplayMetrics()));
    }

    static TextView text(Context c, float sp, int color, Typeface face) {
        TextView t = new TextView(c);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setTypeface(face);
        t.setIncludeFontPadding(false);
        t.setSingleLine(true);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        return t;
    }

    /** A colour made livelier and lighter, for glows taken from app art. */
    static int glow(int color) {
        float[] hsv = new float[3];
        Color.colorToHSV(color, hsv);
        // Greys and whites stay neutral rather than picking up the hue of their rounding error.
        hsv[1] = hsv[1] < 0.08f ? 0f : Math.min(1f, hsv[1] * 1.35f + 0.1f);
        hsv[2] = Math.max(0.75f, hsv[2]);
        return Color.HSVToColor(0xFF, hsv);
    }

    /** {@code color} with its alpha replaced by {@code a} (0 to 255). */
    static int alpha(int color, int a) {
        return (Math.max(0, Math.min(255, a)) << 24) | (color & 0xFFFFFF);
    }

    /** Straight blend from {@code a} to {@code b}, alpha included. */
    static int mix(int a, int b, float t) {
        float u = 1f - t;
        return (Math.round((a >>> 24) * u + (b >>> 24) * t) << 24)
                | (Math.round(((a >> 16) & 0xFF) * u + ((b >> 16) & 0xFF) * t) << 16)
                | (Math.round(((a >> 8) & 0xFF) * u + ((b >> 8) & 0xFF) * t) << 8)
                | Math.round((a & 0xFF) * u + (b & 0xFF) * t);
    }

    static int lighten(int c, float f) {
        return mix(c | 0xFF000000, 0xFFFFFFFF, f);
    }

    static int darken(int c, float f) {
        return mix(c | 0xFF000000, 0xFF000000, f);
    }

    /** Relative luminance, 0 (black) to 1 (white). */
    static float luminance(int c) {
        return (0.2126f * ((c >> 16) & 0xFF) + 0.7152f * ((c >> 8) & 0xFF) + 0.0722f * (c & 0xFF)) / 255f;
    }
}
