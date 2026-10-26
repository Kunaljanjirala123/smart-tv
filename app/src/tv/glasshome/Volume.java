package tv.glasshome;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.Shader;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;
import android.view.Choreographer;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.WindowManager;

/**
 * The glass volume bar. The remote's volume keys reach Guard first; this changes the volume
 * itself, so the TV's own bar never appears, and shows a glass pill whose level glides on a
 * spring. A soundbar on HDMI ARC still follows, since Android forwards the change over CEC.
 *
 * If a press does not move the volume (the TV sends it somewhere Android cannot see), the key is
 * handed back to the TV untouched and the bar stays out of the way until Glass Home restarts.
 * Like the lens, nothing is allocated while it moves.
 */
final class Volume extends Glass.Frame implements Choreographer.FrameCallback {
    /** Edit > Glass volume bar; read on every volume key, so it is kept in memory. */
    static volatile boolean enabled = true;
    private static final long SHOW_MS = 1600;
    private static final int STREAM = AudioManager.STREAM_MUSIC;

    private final WindowManager wm;
    private final AudioManager audio;
    private final Handler handler = new Handler(Looper.getMainLooper());
    // pos[0]: the level, 0 to 1000; pos[1]: how far the bar has appeared, 0 to 100.
    private final Spring spring = new Spring(2, 300f, 0.85f);
    private final Runnable fade = () -> {
        spring.target[1] = 0;
        start();
    };
    private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint icon = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint wave = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint number = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path speaker = new Path();
    private final RectF r = new RectF();
    private final int w, h;
    private final float barH;
    private boolean added, shownOnce, running, muted, bypass;
    private long lastFrame;
    private int percent = -1;
    private String label = "";

    Volume(Context c) {
        super(c, 32);
        wm = (WindowManager) c.getSystemService(Context.WINDOW_SERVICE);
        audio = (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
        w = Ui.dp(c, 340);
        h = Ui.dp(c, 64);
        barH = Ui.dp(c, 6);
        glass().bodyAlpha = 0x22;
        track.setColor(0x33FFFFFF);
        icon.setColor(Ui.TEXT);
        wave.setColor(Ui.TEXT);
        wave.setStyle(Paint.Style.STROKE);
        wave.setStrokeCap(Paint.Cap.ROUND);
        wave.setStrokeWidth(Ui.dp(c, 2.2f));
        number.setColor(Ui.TEXT);
        number.setTypeface(Ui.REGULAR);
        number.setTextSize(Ui.dp(c, 17));
        number.setTextAlign(Paint.Align.CENTER);
        setPivotX(w);
        setPivotY(h / 2f);
        setAlpha(0f);
    }

    /** Handles one volume key press; false hands it back to the TV. */
    boolean press(int code, int repeat) {
        if (bypass || !enabled || audio.isVolumeFixed()) return false;
        int max = audio.getStreamMaxVolume(STREAM), min = audio.getStreamMinVolume(STREAM);
        int before = audio.getStreamVolume(STREAM);
        boolean wasMuted = audio.isStreamMute(STREAM);
        if (code == KeyEvent.KEYCODE_VOLUME_MUTE) {
            if (repeat > 0) return true;
            audio.adjustStreamVolume(STREAM, AudioManager.ADJUST_TOGGLE_MUTE, 0);
            if (audio.isStreamMute(STREAM) == wasMuted) {
                bypass = true;
                return false;
            }
        } else {
            boolean up = code == KeyEvent.KEYCODE_VOLUME_UP;
            audio.adjustStreamVolume(STREAM, up ? AudioManager.ADJUST_RAISE : AudioManager.ADJUST_LOWER, 0);
            boolean atEnd = up ? before >= max : before <= min;
            if (audio.getStreamVolume(STREAM) == before && !atEnd && !wasMuted) {
                bypass = true;
                return false;
            }
        }
        show(audio.getStreamVolume(STREAM), max, audio.isStreamMute(STREAM));
        return true;
    }

    private void show(int level, int max, boolean mute) {
        int pct = max > 0 ? Math.round(level * 100f / max) : 0;
        if (pct != percent || mute != muted) label = mute ? "Muted" : Integer.toString(pct);
        percent = pct;
        muted = mute;
        spring.target[0] = mute ? 0 : pct * 10;
        if (!shownOnce) {
            spring.pos[0] = spring.target[0];
            shownOnce = true;
        }
        if (!added) {
            try {
                wm.addView(this, params());
                added = true;
            } catch (RuntimeException e) {
                return;
            }
        }
        spring.target[1] = 100;
        handler.removeCallbacks(fade);
        handler.postDelayed(fade, SHOW_MS);
        start();
    }

    /** Takes the bar off the screen at once (the service is going away). */
    void dismiss() {
        handler.removeCallbacks(fade);
        Choreographer.getInstance().removeFrameCallback(this);
        running = false;
        if (added) wm.removeViewImmediate(this);
        added = false;
    }

    private WindowManager.LayoutParams params() {
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(w, h,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.END;
        lp.x = Ui.dp(getContext(), 48);
        lp.y = Ui.dp(getContext(), 40);
        lp.windowAnimations = 0;
        return lp;
    }

    private void start() {
        if (running) return;
        running = true;
        lastFrame = 0;
        Choreographer.getInstance().postFrameCallback(this);
    }

    @Override
    public void doFrame(long now) {
        boolean moving = spring.step(Spring.dt(now, lastFrame));
        lastFrame = now;
        // Appears with a small glassy pop from the right edge; the overshoot only grows it a little.
        float p = spring.pos[1] / 100f;
        setAlpha(Math.max(0f, Math.min(1f, p)));
        float s = 0.88f + 0.12f * p;
        setScaleX(s);
        setScaleY(s);
        invalidate();
        if (moving) {
            Choreographer.getInstance().postFrameCallback(this);
            return;
        }
        running = false;
        if (spring.target[1] == 0 && added) {
            wm.removeView(this);
            added = false;
        }
    }

    @Override
    protected void onSizeChanged(int nw, int nh, int ow, int oh) {
        super.onSizeChanged(nw, nh, ow, oh);
        fill.setShader(new LinearGradient(nh, 0, nw - nh, 0, 0xFF8FB8FF, 0xFFFFFFFF, Shader.TileMode.CLAMP));
        // A speaker: a small box and its cone, centred in the left square of the pill.
        float cx = nh * 0.55f, cy = nh / 2f, u = nh / 64f;
        speaker.reset();
        speaker.moveTo(cx - 13 * u, cy - 5 * u);
        speaker.lineTo(cx - 6 * u, cy - 5 * u);
        speaker.lineTo(cx + 2 * u, cy - 12 * u);
        speaker.lineTo(cx + 2 * u, cy + 12 * u);
        speaker.lineTo(cx - 6 * u, cy + 5 * u);
        speaker.lineTo(cx - 13 * u, cy + 5 * u);
        speaker.close();
    }

    @Override
    protected void onDraw(Canvas c) {
        super.onDraw(c);
        float pw = getWidth(), ph = getHeight(), cy = ph / 2f, u = ph / 64f;
        c.drawPath(speaker, icon);
        float cx = ph * 0.55f;
        if (muted) {
            c.drawLine(cx + 8 * u, cy - 6 * u, cx + 18 * u, cy + 6 * u, wave);
            c.drawLine(cx + 8 * u, cy + 6 * u, cx + 18 * u, cy - 6 * u, wave);
        } else {
            float level = spring.pos[0] / 1000f;
            if (level > 0.005f) {
                r.set(cx - 2 * u, cy - 8 * u, cx + 12 * u, cy + 8 * u);
                c.drawArc(r, -45, 90, false, wave);
            }
            if (level > 0.5f) {
                r.set(cx - 6 * u, cy - 14 * u, cx + 18 * u, cy + 14 * u);
                c.drawArc(r, -45, 90, false, wave);
            }
        }
        float x0 = ph * 1.05f, x1 = pw - ph * 1.05f, half = barH / 2f;
        r.set(x0, cy - half, x1, cy + half);
        c.drawRoundRect(r, half, half, track);
        float f = Math.max(0f, Math.min(1f, spring.pos[0] / 1000f));
        if (f > 0f) {
            r.right = x0 + Math.max(barH, (x1 - x0) * f);
            c.drawRoundRect(r, half, half, fill);
            // A bright bead at the end of the level, the drop of liquid in the glass.
            c.drawCircle(r.right - half, cy, barH * 1.15f, icon);
        }
        c.drawText(label, pw - ph * 0.55f, cy + number.getTextSize() * 0.36f, number);
    }
}
