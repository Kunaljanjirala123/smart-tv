package tv.glasshome;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.Choreographer;
import android.view.View;

/**
 * The single glass highlight that springs from tile to tile. It lives in the scrolling content,
 * so it moves with the page, and draws over the focused tile: a clear lens with a bright rim, a
 * sheen and a soft glow, so the app art shows through it.
 */
final class Lens extends View implements Choreographer.FrameCallback {
    // Spring tuned to settle in about 250 ms with a slight, liquid overshoot.
    private static final float STIFFNESS = 420f, DAMPING = 0.68f;

    private final float[] pos = new float[4], vel = new float[4], target = new float[4];
    private final Paint body = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint sheen = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();
    private final float radius, rimW;
    private boolean visible, running, placed;
    private long lastFrame;
    private float alpha;

    Lens(Context c) {
        super(c);
        radius = Ui.dp(c, 14);
        rimW = Ui.dp(c, 2);
        body.setColor(0x14FFFFFF);
        rim.setStyle(Paint.Style.STROKE);
        rim.setStrokeWidth(rimW);
    }

    /** Moves (or, the first time, places) the lens over {@code rect}, in this view's coordinates. */
    void moveTo(RectF rect) {
        target[0] = rect.left;
        target[1] = rect.top;
        target[2] = rect.right;
        target[3] = rect.bottom;
        if (!placed || !visible) {
            System.arraycopy(target, 0, pos, 0, 4);
            vel[0] = vel[1] = vel[2] = vel[3] = 0;
            placed = true;
        }
        visible = true;
        start();
    }

    void hide() {
        visible = false;
        start();
    }

    private void start() {
        if (running) return;
        running = true;
        lastFrame = 0;
        Choreographer.getInstance().postFrameCallback(this);
    }

    @Override
    public void doFrame(long now) {
        float dt = lastFrame == 0 ? 1f / 60 : Math.min((now - lastFrame) / 1e9f, 1f / 20);
        lastFrame = now;
        boolean moving = false;
        float c = 2f * (float) Math.sqrt(STIFFNESS) * DAMPING;
        // Two half steps keep the spring stable on a frame that came late.
        for (int step = 0; step < 2; step++) {
            float h = dt / 2f;
            for (int i = 0; i < 4; i++) {
                float a = -STIFFNESS * (pos[i] - target[i]) - c * vel[i];
                vel[i] += a * h;
                pos[i] += vel[i] * h;
            }
        }
        for (int i = 0; i < 4; i++) {
            if (Math.abs(pos[i] - target[i]) > 0.3f || Math.abs(vel[i]) > 4f) moving = true;
            else { pos[i] = target[i]; vel[i] = 0; }
        }
        float wantAlpha = visible ? 1f : 0f;
        alpha += (wantAlpha - alpha) * Math.min(1f, dt * 14f);
        if (Math.abs(alpha - wantAlpha) > 0.01f) moving = true;
        else alpha = wantAlpha;
        invalidate();
        if (moving) Choreographer.getInstance().postFrameCallback(this);
        else running = false;
    }

    @Override
    protected void onDraw(Canvas c) {
        if (alpha <= 0f || !placed) return;
        r.set(pos[0], pos[1], pos[2], pos[3]);
        body.setAlpha(Math.round(alpha * 0x14));
        c.drawRoundRect(r, radius, radius, body);
        sheen.setShader(new LinearGradient(0, r.top, 0, r.top + r.height() * 0.55f,
                Math.round(alpha * 0x40) << 24 | 0xFFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
        c.drawRoundRect(r, radius, radius, sheen);
        rim.setShader(new LinearGradient(r.left, r.top, r.right, r.bottom,
                Math.round(alpha * 0xF2) << 24 | 0xFFFFFF, Math.round(alpha * 0x55) << 24 | 0xFFFFFF,
                Shader.TileMode.CLAMP));
        float in = rimW / 2f;
        r.inset(in, in);
        c.drawRoundRect(r, radius - in, radius - in, rim);
    }

    @Override
    protected void onDetachedFromWindow() {
        Choreographer.getInstance().removeFrameCallback(this);
        running = false;
        super.onDetachedFromWindow();
    }
}
