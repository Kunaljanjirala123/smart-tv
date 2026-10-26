package tv.glasshome;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.text.format.DateFormat;
import android.text.format.DateUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/** The forecast inside the weather menu: now, the next hours, and the next five days. */
final class Forecast {
    private static final int SUN_LIGHT = 0xFFFF9F0A, SUN_DARK = 0xFFFFD60A;

    private Forecast() {}

    static View view(Context c, Weather.Report r) {
        Theme t = Theme.now;
        int sun = t.light ? SUN_LIGHT : SUN_DARK;
        LinearLayout v = new LinearLayout(c);
        v.setOrientation(LinearLayout.VERTICAL);
        int pad = Ui.dp(c, 8);
        v.setPadding(pad, 0, pad, Ui.dp(c, 10));

        // Now -------------------------------------------------------------------
        LinearLayout now = new LinearLayout(c);
        now.setOrientation(LinearLayout.HORIZONTAL);
        now.setGravity(Gravity.CENTER_VERTICAL);
        ImageView g = new ImageView(c);
        g.setImageDrawable(new Glyph(Weather.glyph(r.code, r.day), t.panelText, sun));
        int gs = Ui.dp(c, 50);
        now.addView(g, new LinearLayout.LayoutParams(gs, gs));
        TextView temp = Ui.text(c, 46, t.panelText, Ui.THIN);
        temp.setText(r.temp + "°");
        temp.setPadding(Ui.dp(c, 14), 0, Ui.dp(c, 16), 0);
        now.addView(temp);
        LinearLayout words = new LinearLayout(c);
        words.setOrientation(LinearLayout.VERTICAL);
        TextView desc = Ui.text(c, 20, t.panelText, Ui.MEDIUM);
        desc.setText(Weather.describe(r.code, r.day));
        words.addView(desc);
        TextView hl = Ui.text(c, 15, t.panelMuted, Ui.REGULAR);
        hl.setText("H: " + r.hi + "°   L: " + r.lo + "°");
        hl.setPadding(0, Ui.dp(c, 4), 0, 0);
        words.addView(hl);
        now.addView(words);
        v.addView(now);

        // Hours -----------------------------------------------------------------
        if (!r.hours.isEmpty()) {
            v.addView(header(c, "HOURLY FORECAST"));
            LinearLayout hours = new LinearLayout(c);
            hours.setOrientation(LinearLayout.HORIZONTAL);
            boolean h24 = DateFormat.is24HourFormat(c);
            for (int i = 0; i < Math.min(8, r.hours.size()); i++) {
                Weather.Hour h = r.hours.get(i);
                LinearLayout cell = new LinearLayout(c);
                cell.setOrientation(LinearLayout.VERTICAL);
                cell.setGravity(Gravity.CENTER_HORIZONTAL);
                TextView when = Ui.text(c, 13, t.panelMuted, Ui.MEDIUM);
                when.setText(i == 0 ? "Now" : DateFormat.format(h24 ? "H:00" : "h a", h.time));
                cell.addView(when);
                ImageView hg = new ImageView(c);
                hg.setImageDrawable(new Glyph(Weather.glyph(h.code, h.day), t.panelText, sun));
                int s = Ui.dp(c, 24);
                LinearLayout.LayoutParams gl = new LinearLayout.LayoutParams(s, s);
                gl.topMargin = gl.bottomMargin = Ui.dp(c, 5);
                cell.addView(hg, gl);
                TextView ht = Ui.text(c, 17, t.panelText, Ui.REGULAR);
                ht.setText(h.temp + "°");
                cell.addView(ht);
                hours.addView(cell, new LinearLayout.LayoutParams(0, -2, 1f));
            }
            v.addView(hours);
        }

        // Days ------------------------------------------------------------------
        if (!r.days.isEmpty()) {
            v.addView(header(c, "5-DAY FORECAST"));
            int min = Integer.MAX_VALUE, max = Integer.MIN_VALUE;
            for (Weather.Day d : r.days) {
                min = Math.min(min, d.lo);
                max = Math.max(max, d.hi);
            }
            for (int i = 0; i < Math.min(5, r.days.size()); i++) {
                Weather.Day d = r.days.get(i);
                LinearLayout row = new LinearLayout(c);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(0, Ui.dp(c, 3), 0, Ui.dp(c, 3));
                TextView name = Ui.text(c, 16, t.panelText, Ui.REGULAR);
                name.setText(i == 0 ? "Today" : DateUtils.formatDateTime(c, d.date + 12 * 3_600_000L,
                        DateUtils.FORMAT_SHOW_WEEKDAY | DateUtils.FORMAT_ABBREV_WEEKDAY));
                row.addView(name, new LinearLayout.LayoutParams(Ui.dp(c, 92), -2));
                ImageView dg = new ImageView(c);
                dg.setImageDrawable(new Glyph(Weather.glyph(d.code, true), t.panelText, sun));
                int s = Ui.dp(c, 24);
                row.addView(dg, new LinearLayout.LayoutParams(s, s));
                TextView lo = Ui.text(c, 17, t.panelMuted, Ui.REGULAR);
                lo.setText(d.lo + "°");
                lo.setGravity(Gravity.END);
                row.addView(lo, new LinearLayout.LayoutParams(Ui.dp(c, 58), -2));
                LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(0, Ui.dp(c, 6), 1f);
                bl.leftMargin = bl.rightMargin = Ui.dp(c, 14);
                row.addView(new Range(c, min, max, d.lo, d.hi), bl);
                TextView hi = Ui.text(c, 17, t.panelText, Ui.REGULAR);
                hi.setText(d.hi + "°");
                row.addView(hi, new LinearLayout.LayoutParams(Ui.dp(c, 44), -2));
                v.addView(row);
            }
        }
        return v;
    }

    private static TextView header(Context c, String text) {
        TextView h = Ui.text(c, 12, Theme.now.panelMuted, Ui.MEDIUM);
        h.setText(text);
        h.setLetterSpacing(0.08f);
        h.setPadding(0, Ui.dp(c, 14), 0, Ui.dp(c, 7));
        return h;
    }

    /** A day's low-to-high span on the week's scale, cool to warm. */
    private static final class Range extends View {
        private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG), span = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final int min, max, lo, hi;

        Range(Context c, int min, int max, int lo, int hi) {
            super(c);
            this.min = min;
            this.max = max;
            this.lo = lo;
            this.hi = hi;
            track.setColor(Theme.now.light ? 0x1F000000 : 0x26FFFFFF);
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight(), r = h / 2f;
            c.drawRoundRect(0, 0, w, h, r, r, track);
            float range = Math.max(1, max - min);
            float a = (lo - min) / range * w, b = (hi - min) / range * w;
            if (b - a < h) b = Math.min(w, a + h);
            span.setShader(new LinearGradient(0, 0, w, 0, 0xFF64D2FF, 0xFFFFB340, Shader.TileMode.CLAMP));
            c.drawRoundRect(a, 0, b, h, r, r, span);
        }
    }
}
