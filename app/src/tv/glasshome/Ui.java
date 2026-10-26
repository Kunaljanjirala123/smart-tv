package tv.glasshome;

import android.content.Context;
import android.graphics.Typeface;
import android.util.TypedValue;
import android.widget.TextView;

/** Small shared helpers: sizes, colours and text styles. */
final class Ui {
    static final int TEXT = 0xFFFFFFFF;
    static final int SOFT = 0xCCFFFFFF;
    static final int MUTED = 0x99FFFFFF;

    static final Typeface THIN = Typeface.create("sans-serif-thin", Typeface.NORMAL);
    static final Typeface LIGHT = Typeface.create("sans-serif-light", Typeface.NORMAL);
    static final Typeface REGULAR = Typeface.create("sans-serif", Typeface.NORMAL);
    static final Typeface MEDIUM = Typeface.create("sans-serif-medium", Typeface.NORMAL);

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
        return t;
    }

    /** A colour made livelier and lighter, for glows taken from app art. */
    static int glow(int color) {
        float[] hsv = new float[3];
        android.graphics.Color.colorToHSV(color, hsv);
        hsv[1] = Math.min(1f, hsv[1] * 1.35f + 0.1f);
        hsv[2] = Math.max(0.75f, hsv[2]);
        return android.graphics.Color.HSVToColor(0xFF, hsv);
    }
}
