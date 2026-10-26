package tv.glasshome;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;

/**
 * The top shelf: the focused dock app's banner, large, in the middle of the first screen and
 * glowing with its own colours, with its name underneath. A change of app crossfades two layers;
 * alpha and scale are render-node properties applied to each bitmap as it is drawn, so the art is
 * never redrawn and no offscreen buffer is needed during the transition.
 */
final class Stage extends FrameLayout {
    private final Layer[] layers = new Layer[2];
    private final TextView caption;
    private final int artW, artH, captionSpace;
    private int front;
    private String showing;

    /** The height the stage needs for art {@code artH} tall: room for the glow and the caption. */
    static int height(Context c, int artH) {
        return Math.round(artH * 1.72f) + Ui.dp(c, 36);
    }

    /** {@code artW x artH} is the art, drawn centred in the space above the caption. */
    Stage(Context c, int artW, int artH) {
        super(c);
        this.artW = artW;
        this.artH = artH;
        captionSpace = Ui.dp(c, 36);
        setClipToPadding(false);
        for (int i = 0; i < 2; i++) {
            layers[i] = new Layer(c);
            layers[i].setAlpha(0f);
            addView(layers[i], new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        }
        caption = Ui.text(c, 17, Theme.now.soft, Ui.REGULAR);
        caption.setGravity(Gravity.CENTER);
        caption.setAlpha(0f);
        // Just under the art, not at the bottom of the room left for the glow.
        LayoutParams cl = new LayoutParams(LayoutParams.MATCH_PARENT, captionSpace, Gravity.TOP);
        cl.topMargin = (height(c, artH) - captionSpace) / 2 + artH / 2 + Ui.dp(c, 10);
        addView(caption, cl);
        // After the children are added, so their render nodes do not clip the glow to their bounds.
        setClipChildren(false);
    }

    String showing() {
        return showing;
    }

    void show(Catalog.Item it, Catalog.StageArt art) {
        if (art == null || it.id.equals(showing) && layers[front].art == art) return;
        showing = it.id;
        Layer out = layers[front];
        front ^= 1;
        Layer in = layers[front];
        in.art = art;
        in.invalidate();
        in.bringToFront();
        caption.bringToFront();
        in.animate().cancel();
        in.setAlpha(0f);
        in.setScaleX(0.94f);
        in.setScaleY(0.94f);
        in.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(460).setInterpolator(Ui.EASE).start();
        out.animate().cancel();
        out.animate().alpha(0f).scaleX(1.04f).scaleY(1.04f).setDuration(280).setInterpolator(Ui.EASE).start();
        caption.animate().cancel();
        caption.setText(it.label);
        caption.setAlpha(0f);
        caption.setTranslationY(Ui.dp(getContext(), 6));
        caption.animate().alpha(1f).translationY(0).setStartDelay(90).setDuration(320).setInterpolator(Ui.EASE).start();
    }

    /** A pane drawing one app's glow and art, centred in the space above the caption. */
    private final class Layer extends View {
        Catalog.StageArt art;
        private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
        private final Paint glowPaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
        private final RectF dst = new RectF(), glow = new RectF();

        Layer(Context c) {
            super(c);
            glowPaint.setAlpha(0xF2);
        }

        /** Fade each bitmap by itself instead of through a full-width offscreen buffer. */
        @Override
        public boolean hasOverlappingRendering() {
            return false;
        }

        @Override
        protected void onLayout(boolean changed, int l, int t, int r, int b) {
            super.onLayout(changed, l, t, r, b);
            float cx = getWidth() / 2f, cy = (getHeight() - captionSpace) / 2f;
            setPivotX(cx);
            setPivotY(cy);
        }

        @Override
        protected void onDraw(Canvas c) {
            if (art == null) return;
            float cx = getWidth() / 2f, cy = (getHeight() - captionSpace) / 2f;
            dst.set(cx - artW / 2f, cy - artH / 2f, cx + artW / 2f, cy + artH / 2f);
            glow.set(dst);
            glow.inset(-art.glowPad, -art.glowPad);
            c.drawBitmap(art.glow, null, glow, glowPaint);
            c.drawBitmap(art.art, null, dst, paint);
        }
    }
}
