package tv.glasshome;

import android.content.Context;
import android.view.Choreographer;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

/**
 * The page, which only moves when told to: no jumps of its own on focus changes. It glides its
 * content on a spring by translation, a render-node property, so scrolling re-records nothing
 * but the glass and moves in sub-pixel steps instead of whole-pixel jumps.
 */
final class Pager extends FrameLayout implements Choreographer.FrameCallback {
    interface Listener {
        void scrolled(float y);
    }

    // Critically damped: about 350 ms, no bounce past the top of the page.
    private final Spring spring = new Spring(1, 160f, 1f);
    private boolean running;
    private long lastFrame;
    private Listener listener;

    Pager(Context c) {
        super(c);
    }

    void setListener(Listener l) {
        listener = l;
    }

    /** The content is as tall as it needs to be, not as tall as the screen. */
    @Override
    protected void measureChildWithMargins(View child, int wSpec, int wUsed, int hSpec, int hUsed) {
        ViewGroup.LayoutParams lp = child.getLayoutParams();
        child.measure(getChildMeasureSpec(wSpec, getPaddingLeft() + getPaddingRight() + wUsed, lp.width),
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
    }

    /** Where the page is heading, in pixels from the top. */
    int target() {
        return Math.round(spring.target[0]);
    }

    void glideTo(int y) {
        if (spring.target[0] == y) return;
        spring.target[0] = y;
        if (running) return;
        running = true;
        lastFrame = 0;
        Choreographer.getInstance().postFrameCallback(this);
    }

    @Override
    public void doFrame(long now) {
        boolean moving = spring.step(Spring.dt(now, lastFrame));
        lastFrame = now;
        if (getChildCount() > 0) getChildAt(0).setTranslationY(-spring.pos[0]);
        Glass.invalidateAll();
        if (listener != null) listener.scrolled(spring.pos[0]);
        if (moving) Choreographer.getInstance().postFrameCallback(this);
        else running = false;
    }

    @Override
    protected void onDetachedFromWindow() {
        Choreographer.getInstance().removeFrameCallback(this);
        running = false;
        super.onDetachedFromWindow();
    }
}
