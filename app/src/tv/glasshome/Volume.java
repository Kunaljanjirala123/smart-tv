package tv.glasshome;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
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
import android.view.WindowManager;

/**
 * The glass volume bar. Fire OS keeps the volume keys to itself (it never passes them to an
 * accessibility service), so the TV still changes the volume and shows its own bar; this listens
 * for the change and draws an opaque glass pill exactly over Amazon's bar, which sits at the top
 * centre, from an accessibility overlay that is layered above it. The level glides on a spring.
 *
 * It stays up a little longer than Amazon's bar (about four seconds), so that one never shows
 * when this fades. Like the lens, nothing is allocated while it moves.
 */
final class Volume extends Glass.Frame implements Choreographer.FrameCallback {
    /** Edit > Glass volume bar. */
    static volatile boolean enabled = true;
    private static final String VOLUME_CHANGED = "android.media.VOLUME_CHANGED_ACTION";
    private static final String MUTE_CHANGED = "android.media.STREAM_MUTE_CHANGED_ACTION";
    private static final long SHOW_MS = 4600;
    private static final int STREAM = AudioManager.STREAM_MUSIC;

    private final Context context;
    private final WindowManager wm;
    private final AudioManager audio;
    private final Handler handler = new Handler(Looper.getMainLooper());
    // pos[0]: the level, 0 to 1000; pos[1]: how far the bar has appeared, 0 to 100.
    private final Spring spring = new Spring(2, 300f, 0.85f);
    private final Runnable fade = () -> {
        spring.target[1] = 0;
        start();
    };
    private final BroadcastReceiver changed = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            int stream = i.getIntExtra("android.media.EXTRA_VOLUME_STREAM_TYPE", STREAM);
            if (enabled && stream == STREAM) show();
        }
    };
    private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint icon = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint wave = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint number = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path speaker = new Path();
    private final RectF r = new RectF();
    // Amazon's bar is 362 x 66 dp at the top centre; the pill overlaps it on every side, and its
    // top edge (with the rounded corners) sits above the screen.
    private final int w, h, above;
    private final float barH;
    private WindowManager.LayoutParams lp;
    private boolean added, onScreen, shownOnce, running, muted;
    private long lastFrame;
    private int percent = -1;
    private String label = "";

    Volume(Context c) {
        super(c, 30);
        context = c;
        wm = (WindowManager) c.getSystemService(Context.WINDOW_SERVICE);
        audio = (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
        w = Ui.dp(c, 400);
        h = Ui.dp(c, 92);
        above = Ui.dp(c, 14);
        barH = Ui.dp(c, 6);
        // Fully opaque: Amazon's bar must not show through.
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
    }

    /**
     * Starts following volume changes. The window is added now, drawn but with zero alpha:
     * SurfaceFlinger skips a fully transparent window, so it costs nothing while hidden, and
     * showing it is a single alpha change instead of a new window and its first draw.
     */
    void listen() {
        IntentFilter f = new IntentFilter(VOLUME_CHANGED);
        f.addAction(MUTE_CHANGED);
        context.registerReceiver(changed, f);
        lp = params();
        lp.alpha = 0f;
        try {
            wm.addView(this, lp);
            added = true;
        } catch (RuntimeException ignored) {
        }
    }

    /**
     * Amazon's volume bar has just opened (Guard sees its window appear): the fastest signal
     * there is, about a second ahead of the volume broadcast on this TV.
     */
    void amazonShown() {
        if (enabled) show();
    }

    private void show() {
        int max = audio.getStreamMaxVolume(STREAM);
        int pct = max > 0 ? Math.round(audio.getStreamVolume(STREAM) * 100f / max) : 0;
        boolean mute = audio.isStreamMute(STREAM);
        if (pct != percent || mute != muted) label = mute ? "Muted" : Integer.toString(pct);
        percent = pct;
        muted = mute;
        spring.target[0] = mute ? 0 : pct * 10;
        if (!shownOnce) {
            spring.pos[0] = spring.target[0];
            shownOnce = true;
        }
        if (!added) return;
        invalidate();
        if (!onScreen) {
            // Straight to full opacity: Amazon's bar is already on screen and must be covered now.
            onScreen = true;
            spring.pos[1] = 100;
            setAlpha(1f);
            lp.alpha = 1f;
            wm.updateViewLayout(this, lp);
        }
        spring.target[1] = 100;
        handler.removeCallbacks(fade);
        handler.postDelayed(fade, SHOW_MS);
        start();
    }

    /** Stops following volume changes and takes the bar off the screen. */
    void dismiss() {
        try {
            context.unregisterReceiver(changed);
        } catch (IllegalArgumentException ignored) {
        }
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
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                        | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        lp.y = -above;
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
        // Leaving, Amazon's bar has gone, so it fades.
        if (spring.target[1] == 0) setAlpha(Math.max(0f, Math.min(1f, spring.pos[1] / 100f)));
        invalidate();
        if (moving) {
            Choreographer.getInstance().postFrameCallback(this);
            return;
        }
        running = false;
        if (spring.target[1] == 0 && onScreen) {
            onScreen = false;
            lp.alpha = 0f;
            wm.updateViewLayout(this, lp);
            setAlpha(1f);
        }
    }

    /** Vertical centre of the part of the pill that is on screen. */
    private float middle(float ph) {
        return (ph + above) / 2f;
    }

    @Override
    protected void onSizeChanged(int nw, int nh, int ow, int oh) {
        super.onSizeChanged(nw, nh, ow, oh);
        float side = nh - above;
        fill.setShader(new LinearGradient(side, 0, nw - side, 0, 0xFF8FB8FF, 0xFFFFFFFF, Shader.TileMode.CLAMP));
        // A speaker: a small box and its cone, centred in the left square of the pill.
        float cx = side * 0.62f, cy = middle(nh), u = side / 64f;
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
        float pw = getWidth(), ph = getHeight(), side = ph - above, cy = middle(ph), u = side / 64f;
        c.drawPath(speaker, icon);
        float cx = side * 0.62f;
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
        float x0 = side * 1.15f, x1 = pw - side * 1.15f, half = barH / 2f;
        r.set(x0, cy - half, x1, cy + half);
        c.drawRoundRect(r, half, half, track);
        float f = Math.max(0f, Math.min(1f, spring.pos[0] / 1000f));
        if (f > 0f) {
            r.right = x0 + Math.max(barH, (x1 - x0) * f);
            c.drawRoundRect(r, half, half, fill);
            // A bright bead at the end of the level, the drop of liquid in the glass.
            c.drawCircle(r.right - half, cy, barH * 1.15f, icon);
        }
        c.drawText(label, pw - side * 0.6f, cy + number.getTextSize() * 0.36f, number);
    }
}
