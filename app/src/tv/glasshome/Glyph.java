package tv.glasshome;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;

/**
 * Line icons drawn in code on a 24-unit grid, so they are sharp at any size and need no image
 * files. Weather icons can paint the sun in an accent colour.
 */
final class Glyph extends Drawable {
    static final int WIFI = 1, WIFI_OFF = 2, ETHERNET = 3, BLUETOOTH = 4, GEAR = 5, INPUT = 6, SOUND = 7,
            DISPLAY = 8, MOON = 9, SUN = 10, CLOCK = 11, FOLDER = 12, PLUS = 13, CLOSE = 14, CHECK = 15,
            CHEVRON = 16, SEARCH = 17, PIN = 18, PERSON = 19, APPS = 20, CONTRAST = 21, CHIP = 22, TV = 23,
            CLOUD = 30, SUN_CLOUD = 31, MOON_CLOUD = 32, RAIN = 33, DRIZZLE = 34, SNOW = 35, THUNDER = 36, FOG = 37;

    private final int kind;
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF oval = new RectF();
    private int colour, accent, alpha = 255;

    Glyph(int kind, int colour) {
        this(kind, colour, colour);
    }

    /** {@code accent} paints the sun in weather icons. */
    Glyph(int kind, int colour, int accent) {
        this.kind = kind;
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        stroke.setStrokeJoin(Paint.Join.ROUND);
        stroke.setStrokeWidth(1.9f);
        fill.setStyle(Paint.Style.FILL);
        setColours(colour, accent);
    }

    void setColours(int colour, int accent) {
        this.colour = colour;
        this.accent = accent;
        invalidateSelf();
    }

    @Override
    public void draw(Canvas c) {
        Rect b = getBounds();
        if (b.isEmpty()) return;
        c.save();
        c.translate(b.left, b.top);
        c.scale(b.width() / 24f, b.height() / 24f);
        paint(colour);
        switch (kind) {
            case WIFI: wifi(c, false); break;
            case WIFI_OFF: wifi(c, true); break;
            case ETHERNET: ethernet(c); break;
            case BLUETOOTH: poly(c, false, 7, 7, 17, 16.5f, 12, 21, 12, 3, 17, 7.5f, 7, 17); break;
            case GEAR: gear(c); break;
            case INPUT: input(c); break;
            case SOUND: sound(c); break;
            case DISPLAY: display(c); break;
            case MOON: moon(c, 12, 12, 1f); break;
            case SUN: sun(c, 12, 12, 1f); break;
            case CLOCK: clock(c); break;
            case FOLDER: poly(c, true, 3, 6.5f, 9.5f, 6.5f, 11.5f, 9, 21, 9, 21, 19, 3, 19); break;
            case PLUS: line(c, 12, 5, 12, 19); line(c, 5, 12, 19, 12); break;
            case CLOSE: line(c, 6, 6, 18, 18); line(c, 18, 6, 6, 18); break;
            case CHECK: poly(c, false, 5, 12.5f, 10, 17, 19, 7.5f); break;
            case CHEVRON: poly(c, false, 9, 5, 16, 12, 9, 19); break;
            case SEARCH: c.drawCircle(10.5f, 10.5f, 6.5f, stroke); line(c, 15.5f, 15.5f, 20.5f, 20.5f); break;
            case PIN: pin(c); break;
            case PERSON: person(c); break;
            case APPS: apps(c); break;
            case CONTRAST: contrast(c); break;
            case CHIP: chip(c); break;
            case TV: tv(c); break;
            case CLOUD: cloud(c, 0, 1.5f, 1f); break;
            case SUN_CLOUD: paint(accent); sun(c, 9, 8.5f, 0.62f); paint(colour); cloud(c, 2.5f, 4.5f, 0.82f); break;
            case MOON_CLOUD: paint(accent); moon(c, 9.5f, 8.5f, 0.55f); paint(colour); cloud(c, 2.5f, 4.5f, 0.82f); break;
            case RAIN: cloud(c, 0, -2, 1f); drops(c, false); break;
            case DRIZZLE: cloud(c, 0, -2, 1f); drops(c, true); break;
            case SNOW: cloud(c, 0, -2, 1f); flakes(c); break;
            case THUNDER: cloud(c, 0, -2, 1f); bolt(c); break;
            case FOG: fog(c); break;
            default: break;
        }
        c.restore();
    }

    private void paint(int col) {
        col = Ui.alpha(col, (col >>> 24) * alpha / 255);
        stroke.setColor(col);
        fill.setColor(col);
    }

    private void line(Canvas c, float x1, float y1, float x2, float y2) {
        c.drawLine(x1, y1, x2, y2, stroke);
    }

    private void poly(Canvas c, boolean closed, float... xy) {
        path.reset();
        path.moveTo(xy[0], xy[1]);
        for (int i = 2; i < xy.length; i += 2) path.lineTo(xy[i], xy[i + 1]);
        if (closed) path.close();
        c.drawPath(path, stroke);
    }

    private void arc(Canvas c, float cx, float cy, float r, float start, float sweep) {
        oval.set(cx - r, cy - r, cx + r, cy + r);
        c.drawArc(oval, start, sweep, false, stroke);
    }

    private void wifi(Canvas c, boolean off) {
        int full = stroke.getAlpha();
        if (off) stroke.setAlpha(Math.round(full * 0.45f));
        c.drawCircle(12, 18.6f, 1.5f, fill);
        arc(c, 12, 19, 4.6f, 225, 90);
        arc(c, 12, 19, 9.2f, 225, 90);
        arc(c, 12, 19.5f, 13.8f, 228, 84);
        if (off) {
            stroke.setAlpha(full);
            line(c, 4, 4, 20, 20);
        }
    }

    private void ethernet(Canvas c) {
        oval.set(9, 3, 15, 9);
        c.drawRoundRect(oval, 1.2f, 1.2f, stroke);
        oval.set(3, 15, 9, 21);
        c.drawRoundRect(oval, 1.2f, 1.2f, stroke);
        oval.set(15, 15, 21, 21);
        c.drawRoundRect(oval, 1.2f, 1.2f, stroke);
        poly(c, false, 6, 15, 6, 12, 18, 12, 18, 15);
        line(c, 12, 9, 12, 12);
    }

    private void gear(Canvas c) {
        path.reset();
        int teeth = 8;
        for (int i = 0; i < teeth * 2; i++) {
            double a0 = Math.PI * 2 * i / (teeth * 2) - Math.PI / (teeth * 2);
            double a1 = a0 + Math.PI * 2 / (teeth * 2);
            float r = i % 2 == 0 ? 9.6f : 7.4f;
            float x0 = 12 + (float) Math.cos(a0) * r, y0 = 12 + (float) Math.sin(a0) * r;
            float x1 = 12 + (float) Math.cos(a1) * r, y1 = 12 + (float) Math.sin(a1) * r;
            if (i == 0) path.moveTo(x0, y0);
            else path.lineTo(x0, y0);
            path.lineTo(x1, y1);
        }
        path.close();
        c.drawPath(path, stroke);
        c.drawCircle(12, 12, 3, stroke);
    }

    private void input(Canvas c) {
        oval.set(7.5f, 4.5f, 21, 19.5f);
        c.drawRoundRect(oval, 2.4f, 2.4f, stroke);
        line(c, 2.5f, 12, 14, 12);
        poly(c, false, 10.5f, 8.5f, 14, 12, 10.5f, 15.5f);
    }

    private void sound(Canvas c) {
        poly(c, true, 3, 9, 7, 9, 12, 4.8f, 12, 19.2f, 7, 15, 3, 15);
        arc(c, 12, 12, 4.4f, -45, 90);
        arc(c, 12, 12, 8f, -45, 90);
    }

    private void display(Canvas c) {
        oval.set(2.5f, 4, 21.5f, 16.5f);
        c.drawRoundRect(oval, 2f, 2f, stroke);
        line(c, 8, 20.5f, 16, 20.5f);
        line(c, 12, 16.5f, 12, 20.5f);
    }

    private void tv(Canvas c) {
        oval.set(2.5f, 5, 21.5f, 17.5f);
        c.drawRoundRect(oval, 2f, 2f, stroke);
        line(c, 7, 20.5f, 17, 20.5f);
        poly(c, false, 8.5f, 2, 12, 5, 15.5f, 2);
    }

    private void moon(Canvas c, float cx, float cy, float s) {
        path.reset();
        path.addCircle(cx, cy, 8.5f * s, Path.Direction.CW);
        Path bite = new Path();
        bite.addCircle(cx + 5f * s, cy - 4f * s, 7.5f * s, Path.Direction.CW);
        path.op(bite, Path.Op.DIFFERENCE);
        c.drawPath(path, fill);
    }

    private void sun(Canvas c, float cx, float cy, float s) {
        c.drawCircle(cx, cy, 4.2f * s, fill);
        for (int i = 0; i < 8; i++) {
            double a = Math.PI * i / 4;
            float cos = (float) Math.cos(a), sin = (float) Math.sin(a);
            line(c, cx + cos * 6.8f * s, cy + sin * 6.8f * s, cx + cos * 9.4f * s, cy + sin * 9.4f * s);
        }
    }

    private void clock(Canvas c) {
        c.drawCircle(12, 12, 9, stroke);
        poly(c, false, 12, 6.8f, 12, 12, 15.6f, 14.2f);
    }

    private void pin(Canvas c) {
        path.reset();
        path.moveTo(12, 21.5f);
        path.cubicTo(7.5f, 16.5f, 5.2f, 13.2f, 5.2f, 9.8f);
        path.cubicTo(5.2f, 6, 8.2f, 3, 12, 3);
        path.cubicTo(15.8f, 3, 18.8f, 6, 18.8f, 9.8f);
        path.cubicTo(18.8f, 13.2f, 16.5f, 16.5f, 12, 21.5f);
        path.close();
        c.drawPath(path, stroke);
        c.drawCircle(12, 9.8f, 2.4f, stroke);
    }

    private void person(Canvas c) {
        c.drawCircle(12, 8, 4, stroke);
        path.reset();
        path.moveTo(4.5f, 20.5f);
        path.cubicTo(5.5f, 15.5f, 8.5f, 13.8f, 12, 13.8f);
        path.cubicTo(15.5f, 13.8f, 18.5f, 15.5f, 19.5f, 20.5f);
        c.drawPath(path, stroke);
    }

    private void apps(Canvas c) {
        for (int i = 0; i < 4; i++) {
            float x = i % 2 == 0 ? 4 : 13.5f, y = i < 2 ? 4 : 13.5f;
            oval.set(x, y, x + 6.5f, y + 6.5f);
            c.drawRoundRect(oval, 1.8f, 1.8f, fill);
        }
    }

    private void contrast(Canvas c) {
        c.drawCircle(12, 12, 9, stroke);
        oval.set(3, 3, 21, 21);
        c.drawArc(oval, 90, 180, true, fill);
    }

    private void chip(Canvas c) {
        oval.set(6.5f, 6.5f, 17.5f, 17.5f);
        c.drawRoundRect(oval, 2f, 2f, stroke);
        oval.set(9.5f, 9.5f, 14.5f, 14.5f);
        c.drawRoundRect(oval, 1f, 1f, fill);
        for (int i = 0; i < 3; i++) {
            float p = 9 + i * 3;
            line(c, p, 3, p, 6.5f);
            line(c, p, 17.5f, p, 21);
            line(c, 3, p, 6.5f, p);
            line(c, 17.5f, p, 21, p);
        }
    }

    /** A filled cloud: three puffs on a flat base. */
    private void cloud(Canvas c, float dx, float dy, float s) {
        c.save();
        c.translate(dx, dy);
        c.scale(s, s, 12, 12);
        path.reset();
        path.addCircle(8, 14.5f, 3.6f, Path.Direction.CW);
        Path p = new Path();
        p.addCircle(12.8f, 11.2f, 5.2f, Path.Direction.CW);
        path.op(p, Path.Op.UNION);
        p.reset();
        p.addCircle(17.2f, 14.6f, 3.6f, Path.Direction.CW);
        path.op(p, Path.Op.UNION);
        p.reset();
        p.addRoundRect(new RectF(4.4f, 13.2f, 20.8f, 18.2f), 2.5f, 2.5f, Path.Direction.CW);
        path.op(p, Path.Op.UNION);
        c.drawPath(path, fill);
        c.restore();
    }

    private void drops(Canvas c, boolean light) {
        float len = light ? 1.2f : 3f;
        for (int i = 0; i < 3; i++) {
            float x = 8 + i * 4;
            line(c, x, 18.5f, x - len * 0.4f, 18.5f + len);
            if (light) line(c, x - 1.5f, 21.8f, x - 1.6f, 22);
        }
    }

    private void flakes(Canvas c) {
        for (int i = 0; i < 3; i++) {
            float x = 8 + i * 4, y = i % 2 == 0 ? 19.5f : 21.2f;
            c.drawCircle(x, y, 1.15f, fill);
        }
    }

    private void bolt(Canvas c) {
        path.reset();
        path.moveTo(13.2f, 14.5f);
        path.lineTo(9.6f, 19.2f);
        path.lineTo(12, 19.2f);
        path.lineTo(10.6f, 23);
        path.lineTo(15, 17.6f);
        path.lineTo(12.5f, 17.6f);
        path.lineTo(14, 14.5f);
        path.close();
        c.drawPath(path, fill);
    }

    private void fog(Canvas c) {
        cloud(c, 0, -3.5f, 0.9f);
        line(c, 4, 18.5f, 20, 18.5f);
        line(c, 6.5f, 21.5f, 17.5f, 21.5f);
    }

    @Override
    public void setAlpha(int a) {
        alpha = a;
        invalidateSelf();
    }

    @Override
    public void setColorFilter(ColorFilter f) {
        stroke.setColorFilter(f);
        fill.setColorFilter(f);
        invalidateSelf();
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
