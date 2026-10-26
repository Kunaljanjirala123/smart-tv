package tv.glasshome;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.Interpolator;
import android.view.animation.OvershootInterpolator;

/**
 * One app: its 16:9 art with rounded corners, lifted and enlarged when focused, with its name
 * fading in underneath (grid tiles only; the dock shows the name large above itself).
 *
 * The lift is a real elevation shadow drawn by the render thread from the tile's outline, and the
 * scale is a view property, so focusing a tile never redraws its art.
 */
final class Tile extends View {
    static final float FOCUS_SCALE = 1.1f;
    private static final Interpolator GROW = new OvershootInterpolator(1.2f);
    private static final Interpolator SETTLE = new DecelerateInterpolator(1.6f);

    final Catalog.Item item;
    final boolean showLabel;
    final int artW, artH, labelSpace;
    private final float radius;
    private final Paint artPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint placeholder = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint letter = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint label = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();
    private final Rect bounds = new Rect();
    private final float liftZ;
    private Bitmap shaderFor;
    private String labelText;
    private float labelAlpha;
    private ValueAnimator labelAnim;

    Tile(Context c, Catalog.Item item, int artW, int artH, boolean showLabel) {
        super(c);
        this.item = item;
        this.artW = artW;
        this.artH = artH;
        this.showLabel = showLabel;
        // Room for the whole label, descenders included, below the enlarged art: the tile's
        // bounds clip what it draws, so a shorter space cut the bottom off the name.
        this.labelSpace = showLabel ? Math.round(Ui.dp(c, 34) + artH * (FOCUS_SCALE - 1f) / 2f) : 0;
        radius = Ui.dp(c, 12);
        liftZ = Ui.dp(c, 14);
        setFocusable(true);
        setFocusableInTouchMode(true);
        setClickable(true);
        setLongClickable(true);
        placeholder.setColor(0x26FFFFFF);
        letter.setColor(Ui.SOFT);
        letter.setTypeface(Ui.LIGHT);
        letter.setTextSize(artH * 0.38f);
        letter.setTextAlign(Paint.Align.CENTER);
        label.setColor(Ui.TEXT);
        label.setTypeface(Ui.REGULAR);
        label.setTextSize(Ui.dp(c, 14));
        label.setTextAlign(Paint.Align.CENTER);
        label.setShadowLayer(Ui.dp(c, 6), 0, 0, 0x99000000);
        setPivotX(artW / 2f);
        setPivotY(artH / 2f);
        setContentDescription(item.label);
        // The shadow follows the art only, not the label space under it.
        setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View v, Outline o) {
                o.setRoundRect(0, 0, Tile.this.artW, Tile.this.artH, radius);
                o.setAlpha(0.9f);
            }
        });
    }

    @Override
    protected void onMeasure(int w, int h) {
        setMeasuredDimension(artW, artH + labelSpace);
    }

    /** The art rectangle in this view's own (unscaled) coordinates. */
    void artRect(RectF out) {
        out.set(0, 0, artW, artH);
    }

    @Override
    protected void onFocusChanged(boolean focused, int direction, Rect previous) {
        super.onFocusChanged(focused, direction, previous);
        float s = focused ? FOCUS_SCALE : 1f;
        animate().scaleX(s).scaleY(s).translationZ(focused ? liftZ : 0f)
                .setInterpolator(focused ? GROW : SETTLE)
                .setDuration(focused ? 260 : 200).start();
        if (showLabel) fadeLabel(focused ? 1f : 0f);
    }

    private void fadeLabel(float to) {
        if (labelAnim != null) labelAnim.cancel();
        labelAnim = ValueAnimator.ofFloat(labelAlpha, to);
        labelAnim.setDuration(200);
        labelAnim.setInterpolator(SETTLE);
        labelAnim.addUpdateListener(a -> {
            labelAlpha = (float) a.getAnimatedValue();
            invalidate();
        });
        labelAnim.start();
    }

    @Override
    protected void onDraw(Canvas c) {
        r.set(0, 0, artW, artH);
        Bitmap art = item.art;
        if (art != null) {
            if (shaderFor != art) {
                BitmapShader s = new BitmapShader(art, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
                Matrix m = new Matrix();
                m.setScale((float) artW / art.getWidth(), (float) artH / art.getHeight());
                s.setLocalMatrix(m);
                artPaint.setShader(s);
                shaderFor = art;
            }
            c.drawRoundRect(r, radius, radius, artPaint);
        } else {
            c.drawRoundRect(r, radius, radius, placeholder);
            String first = item.label.length() > 0 ? item.label.subSequence(0, 1).toString().toUpperCase() : "?";
            letter.getTextBounds(first, 0, 1, bounds);
            c.drawText(first, artW / 2f, artH / 2f + bounds.height() / 2f, letter);
        }
        if (showLabel && labelAlpha > 0.01f) {
            label.setAlpha(Math.round(255 * labelAlpha));
            if (labelText == null) labelText = ellipsize(item.label.toString());
            String text = labelText;
            // Below the enlarged art, so the label sits clear of the lens.
            float y = artH + Ui.dp(getContext(), 8) + (artH * (FOCUS_SCALE - 1f) / 2f) + label.getTextSize();
            c.drawText(text, artW / 2f, y, label);
        }
    }

    private String ellipsize(String s) {
        float max = artW * 1.15f;
        if (label.measureText(s) <= max) return s;
        while (s.length() > 1 && label.measureText(s + "…") > max) s = s.substring(0, s.length() - 1);
        return s + "…";
    }
}
