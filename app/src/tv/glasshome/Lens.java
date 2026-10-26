package tv.glasshome;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.Choreographer;
import android.view.View;

/**
 * The single glass highlight that springs from tile to tile. It lives in the scrolling content,
 * so it moves with the page, and draws over the focused tile: a clear lens with a bright rim, a
 * sheen and a soft glow, so the app art shows through it.
 *
 * Each frame only moves the shaders' matrices and paint alphas: nothing is allocated while it
 * moves, so the garbage collector never pauses the animation.
 */
final class Lens extends View implements Choreographer.FrameCallback {
    // Settles in about 250 ms with a slight, liquid overshoot.
    private final Spring spring = new Spring(4, 420f, 0.72f);
    private final float[] pos = spring.pos, target = spring.target;
    private final Paint body = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint sheen = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Shader sheenShader, rimShader;
    private final Matrix sheenMatrix = new Matrix(), rimMatrix = new Matrix();
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
        // Unit gradients, stretched over the lens by their matrices on each draw.
        sheenShader = new LinearGradient(0, 0, 0, 1, 0x40FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP);
        sheen.setShader(sheenShader);
        rimShader = new LinearGradient(0, 0, 1, 1, 0xF2FFFFFF, 0x55FFFFFF, Shader.TileMode.CLAMP);
        rim.setShader(rimShader);
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
            spring.snap();
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
        float dt = Spring.dt(now, lastFrame);
        lastFrame = now;
        boolean moving = spring.step(dt);
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
        int a = Math.round(alpha * 255);
        r.set(pos[0], pos[1], pos[2], pos[3]);
        body.setAlpha(Math.round(alpha * 0x14));
        c.drawRoundRect(r, radius, radius, body);
        sheenMatrix.setScale(1f, r.height() * 0.55f);
        sheenMatrix.postTranslate(0, r.top);
        sheenShader.setLocalMatrix(sheenMatrix);
        sheen.setAlpha(a);
        c.drawRoundRect(r, radius, radius, sheen);
        rimMatrix.setScale(r.width(), r.height());
        rimMatrix.postTranslate(r.left, r.top);
        rimShader.setLocalMatrix(rimMatrix);
        rim.setAlpha(a);
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
