package tv.glasshome;

import android.animation.ValueAnimator;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.text.format.DateFormat;
import android.view.View;

import java.util.Calendar;
import java.util.Random;

/**
 * StandBy: a large clock with the date, a month calendar and the weather, on black, for when
 * the TV is idle. Used as the screensaver (StandByDream) and full screen from Control Center
 * (StandByActivity). Everything drifts a little every minute so nothing burns into the panel.
 */
final class StandBy extends View {
    static final String CLASSIC = "classic", SPLIT = "split";
    static final int[] COLOURS = {0xFFFFFFFF, 0xFFFFB340, 0xFFFF453A, 0xFF64D2FF, 0xFF30D158};
    static final String[] COLOUR_NAMES = {"White", "Amber", "Red", "Blue", "Green"};

    private final Prefs prefs;
    private final Paint time = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint cal = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint today = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Rect bounds = new Rect();
    private final Random random = new Random();
    private float dx, dy;
    private ValueAnimator drift;
    private Glyph weatherGlyph;
    private int weatherCode = -1;
    private Weather.Report report;
    private final BroadcastReceiver tick = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            readWeather();
            wander();
            invalidate();
        }
    };

    StandBy(Context c) {
        super(c);
        prefs = new Prefs(c);
        setBackgroundColor(0xFF000000);
        time.setTypeface(Ui.THIN);
        time.setTextAlign(Paint.Align.CENTER);
        line.setTypeface(Ui.LIGHT);
        line.setTextAlign(Paint.Align.CENTER);
        cal.setTypeface(Ui.REGULAR);
        cal.setTextAlign(Paint.Align.CENTER);
        today.setStyle(Paint.Style.FILL);
        setKeepScreenOn(true);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        IntentFilter f = new IntentFilter(Intent.ACTION_TIME_TICK);
        f.addAction(Intent.ACTION_TIME_CHANGED);
        f.addAction(Intent.ACTION_TIMEZONE_CHANGED);
        getContext().registerReceiver(tick, f);
        readWeather();
    }

    /** The kept forecast, if it is recent enough to show; read once a minute, not every frame. */
    private void readWeather() {
        Weather.Report r = Weather.parse(prefs.forecast());
        report = r != null && System.currentTimeMillis() - r.fetched < 3 * 3_600_000L ? r : null;
    }

    @Override
    protected void onDetachedFromWindow() {
        getContext().unregisterReceiver(tick);
        if (drift != null) drift.cancel();
        super.onDetachedFromWindow();
    }

    /** A slow, small move to a new resting place, so no pixel stays lit in one spot. */
    private void wander() {
        final float fx = dx, fy = dy;
        final float tx = (random.nextFloat() * 2 - 1) * getWidth() * 0.03f;
        final float ty = (random.nextFloat() * 2 - 1) * getHeight() * 0.04f;
        if (drift != null) drift.cancel();
        drift = ValueAnimator.ofFloat(0f, 1f);
        drift.setDuration(4000);
        drift.addUpdateListener(a -> {
            float f = (float) a.getAnimatedValue();
            dx = fx + (tx - fx) * f;
            dy = fy + (ty - fy) * f;
            invalidate();
        });
        drift.start();
    }

    @Override
    protected void onDraw(Canvas c) {
        int w = getWidth(), h = getHeight();
        int colour = COLOURS[prefs.standbyColour()];
        Calendar now = Calendar.getInstance();
        String clock = DateFormat.format(DateFormat.is24HourFormat(getContext()) ? "H:mm" : "h:mm", now).toString();
        String date = DateFormat.format("EEEE d MMMM", now).toString();
        Weather.Report r = report;
        c.save();
        c.translate(dx, dy);
        if (SPLIT.equals(prefs.standbyStyle())) {
            split(c, w, h, colour, clock, date, now, r);
        } else {
            classic(c, w, h, colour, clock, date, r);
        }
        c.restore();
    }

    private void classic(Canvas c, int w, int h, int colour, String clock, String date, Weather.Report r) {
        time.setColor(colour);
        time.setTextSize(h * 0.36f);
        float y = h * 0.52f;
        c.drawText(clock, w / 2f, y, time);
        line.setColor(Ui.alpha(colour, 0xC0));
        line.setTextSize(h * 0.05f);
        c.drawText(date, w / 2f, y + h * 0.11f, line);
        if (r != null) weather(c, w / 2f, y + h * 0.2f, h * 0.045f, colour, r);
    }

    private void split(Canvas c, int w, int h, int colour, String clock, String date, Calendar now, Weather.Report r) {
        time.setColor(colour);
        time.setTextSize(h * 0.3f);
        float lx = w * 0.3f, y = h * 0.5f;
        c.drawText(clock, lx, y, time);
        line.setColor(Ui.alpha(colour, 0xC0));
        line.setTextSize(h * 0.045f);
        c.drawText(date, lx, y + h * 0.1f, line);
        if (r != null) weather(c, lx, y + h * 0.18f, h * 0.04f, colour, r);
        month(c, w * 0.72f, h * 0.24f, Math.min(w * 0.34f, h * 0.62f), colour, now);
    }

    /** The current month as a grid, today marked with a disc in the clock's colour. */
    private void month(Canvas c, float cx, float top, float size, int colour, Calendar now) {
        float cell = size / 7f;
        cal.setTextSize(cell * 0.42f);
        cal.setColor(Ui.alpha(colour, 0xE6));
        cal.setTypeface(Ui.MEDIUM);
        c.drawText(DateFormat.format("MMMM", now).toString().toUpperCase(), cx, top, cal);
        cal.setTypeface(Ui.REGULAR);
        float left = cx - size / 2f + cell / 2f, row0 = top + cell * 1.1f;
        Calendar d = (Calendar) now.clone();
        d.set(Calendar.DAY_OF_MONTH, 1);
        int first = d.getFirstDayOfWeek();
        String[] initials = {"S", "M", "T", "W", "T", "F", "S"};
        cal.setColor(Ui.alpha(colour, 0x80));
        for (int i = 0; i < 7; i++) {
            int dow = (first - 1 + i) % 7;
            c.drawText(initials[dow], left + i * cell, row0, cal);
        }
        int offset = (d.get(Calendar.DAY_OF_WEEK) - first + 7) % 7;
        int days = d.getActualMaximum(Calendar.DAY_OF_MONTH), todayN = now.get(Calendar.DAY_OF_MONTH);
        Paint.FontMetrics fm = cal.getFontMetrics();
        for (int n = 1; n <= days; n++) {
            int slot = offset + n - 1;
            float x = left + (slot % 7) * cell, y = row0 + (slot / 7 + 1) * cell;
            if (n == todayN) {
                today.setColor(colour);
                c.drawCircle(x, y + (fm.ascent + fm.descent) / 2f, cell * 0.4f, today);
                cal.setColor(0xFF000000);
            } else {
                cal.setColor(Ui.alpha(colour, 0xCC));
            }
            c.drawText(String.valueOf(n), x, y, cal);
        }
    }

    private void weather(Canvas c, float cx, float baseline, float size, int colour, Weather.Report r) {
        String text = r.temp + "°  " + Weather.describe(r.code, r.day);
        line.setTextSize(size);
        line.setColor(Ui.alpha(colour, 0xB3));
        float tw = line.measureText(text), g = size * 1.3f, gap = size * 0.5f;
        float start = cx - (tw + g + gap) / 2f;
        if (weatherGlyph == null || weatherCode != r.code) {
            weatherCode = r.code;
            weatherGlyph = new Glyph(Weather.glyph(r.code, r.day), Ui.alpha(colour, 0xB3));
        }
        line.getTextBounds("8", 0, 1, bounds);
        int gt = Math.round(baseline - bounds.height() / 2f - g / 2f);
        weatherGlyph.setBounds(Math.round(start), gt, Math.round(start + g), Math.round(gt + g));
        weatherGlyph.draw(c);
        c.drawText(text, start + g + gap + tw / 2f, baseline, line);
    }
}
