package tv.glasshome;

import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.View;
import android.view.ViewOutlineProvider;

/**
 * One app (or folder): its art with rounded corners. Focused, it lifts towards the viewer like a
 * tvOS icon: it grows, casts a real shadow, and a band of light sweeps across its glass. Grid
 * tiles also show their name in a small chip underneath. In move mode the tile jiggles.
 *
 * The lift is an elevation shadow drawn by the render thread from the tile's outline, and the
 * scale is a view property, so focusing a tile never redraws its art.
 */
final class Tile extends View {
    static final float FOCUS_SCALE = 1.12f;

    final Catalog.Item item;
    final boolean showLabel;
    final int artW, artH, labelSpace;
    private final float radius, liftZ, chipH, chipPad, chipGap;
    private final Paint artPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint placeholder = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint letter = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint chip = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint chipText = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint sweepPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Shader sweepShader;
    private final Matrix sweepMatrix = new Matrix();
    private final RectF r = new RectF(), chipRect = new RectF();
    private final Rect bounds = new Rect();
    private Bitmap shaderFor;
    private String labelText;
    private float labelAlpha, sweep = -1f;
    private ValueAnimator labelAnim, sweepAnim;
    private ObjectAnimator wiggle;
    private boolean moving;

    Tile(Context c, Catalog.Item item, int artW, int artH, boolean showLabel) {
        super(c);
        this.item = item;
        this.artW = artW;
        this.artH = artH;
        this.showLabel = showLabel;
        radius = Math.max(Ui.dp(c, 9), Math.min(Ui.dp(c, 16), artH * 0.14f));
        liftZ = Ui.dp(c, 14);
        chipH = Ui.dp(c, 24);
        chipPad = Ui.dp(c, 10);
        chipGap = Ui.dp(c, 9);
        labelSpace = showLabel ? Math.round(artH * (FOCUS_SCALE - 1f) / 2f + chipGap + chipH + Ui.dp(c, 4)) : 0;
        setFocusable(true);
        setFocusableInTouchMode(true);
        setClickable(true);
        setLongClickable(true);
        Theme t = Theme.now;
        placeholder.setColor(t.placeholder);
        letter.setColor(0xE6FFFFFF);
        letter.setTypeface(Ui.LIGHT);
        letter.setTextSize(artH * 0.4f);
        letter.setTextAlign(Paint.Align.CENTER);
        chip.setColor(t.chip);
        chipText.setColor(t.chipText);
        chipText.setTypeface(Ui.MEDIUM);
        chipText.setTextSize(Ui.dp(c, 13));
        chipText.setTextAlign(Paint.Align.CENTER);
        ring.setStyle(Paint.Style.STROKE);
        ring.setStrokeWidth(Ui.dp(c, 2.5f));
        ring.setColor(0xF2FFFFFF);
        // A unit-wide band of light, stretched, slanted and slid across the tile by its matrix.
        sweepShader = new LinearGradient(0, 0, 1, 0, new int[] {0x00FFFFFF, 0x5CFFFFFF, 0x00FFFFFF},
                null, Shader.TileMode.CLAMP);
        sweepPaint.setShader(sweepShader);
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

    @Override
    protected void onFocusChanged(boolean focused, int direction, Rect previous) {
        super.onFocusChanged(focused, direction, previous);
        float s = focused || moving ? FOCUS_SCALE : 1f;
        animate().scaleX(s).scaleY(s).translationZ(focused || moving ? liftZ : 0f)
                .setInterpolator(focused ? Ui.POP : Ui.EASE)
                .setDuration(focused ? 280 : 200).start();
        if (focused) sweep();
        if (showLabel) fadeLabel(focused ? 1f : 0f);
    }

    /** One pass of light across the glass, as the tile arrives in focus. */
    private void sweep() {
        if (sweepAnim != null) sweepAnim.cancel();
        sweepAnim = ValueAnimator.ofFloat(0f, 1f);
        sweepAnim.setStartDelay(70);
        sweepAnim.setDuration(820);
        sweepAnim.setInterpolator(Ui.EASE);
        sweepAnim.addUpdateListener(a -> {
            sweep = (float) a.getAnimatedValue();
            invalidate();
        });
        sweepAnim.start();
    }

    private void fadeLabel(float to) {
        if (labelAnim != null) labelAnim.cancel();
        labelAnim = ValueAnimator.ofFloat(labelAlpha, to);
        labelAnim.setDuration(200);
        labelAnim.setInterpolator(Ui.EASE);
        labelAnim.addUpdateListener(a -> {
            labelAlpha = (float) a.getAnimatedValue();
            invalidate();
        });
        labelAnim.start();
    }

    /** Move mode: the tile jiggles and wears a bright ring until it is put down. */
    void setMoving(boolean on) {
        if (moving == on) return;
        moving = on;
        if (wiggle != null) wiggle.cancel();
        if (on) {
            wiggle = ObjectAnimator.ofFloat(this, View.ROTATION, -1.8f, 1.8f);
            wiggle.setDuration(130);
            wiggle.setRepeatCount(ValueAnimator.INFINITE);
            wiggle.setRepeatMode(ValueAnimator.REVERSE);
            wiggle.start();
            animate().scaleX(FOCUS_SCALE).scaleY(FOCUS_SCALE).translationZ(liftZ).setDuration(200).start();
        } else {
            setRotation(0f);
        }
        invalidate();
    }

    @Override
    protected void onDetachedFromWindow() {
        if (wiggle != null) wiggle.cancel();
        if (sweepAnim != null) sweepAnim.cancel();
        if (labelAnim != null) labelAnim.cancel();
        super.onDetachedFromWindow();
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
        if (sweep > 0f && sweep < 1f) {
            float band = artW * 0.6f;
            sweepMatrix.setScale(band, 1f);
            sweepMatrix.postRotate(-16f);
            sweepMatrix.postTranslate(-band + (artW + band) * sweep, 0);
            sweepShader.setLocalMatrix(sweepMatrix);
            c.drawRoundRect(r, radius, radius, sweepPaint);
        }
        if (moving) {
            float in = ring.getStrokeWidth() / 2f;
            r.inset(in, in);
            c.drawRoundRect(r, radius - in, radius - in, ring);
        }
        if (showLabel && labelAlpha > 0.01f) {
            if (labelText == null) labelText = ellipsize(item.label.toString());
            float tw = chipText.measureText(labelText);
            float top = artH + artH * (FOCUS_SCALE - 1f) / 2f + chipGap;
            chipRect.set(artW / 2f - tw / 2f - chipPad, top, artW / 2f + tw / 2f + chipPad, top + chipH);
            chip.setAlpha(Math.round((Theme.now.chip >>> 24) * labelAlpha));
            chipText.setAlpha(Math.round(255 * labelAlpha));
            c.drawRoundRect(chipRect, chipH / 2f, chipH / 2f, chip);
            Paint.FontMetrics fm = chipText.getFontMetrics();
            c.drawText(labelText, artW / 2f, chipRect.centerY() - (fm.ascent + fm.descent) / 2f, chipText);
        }
    }

    /** The tile's bounds clip what it draws, so the chip must fit within the tile's width. */
    private String ellipsize(String s) {
        float max = artW - chipPad * 2;
        if (chipText.measureText(s) <= max) return s;
        while (s.length() > 1 && chipText.measureText(s + "…") > max) s = s.substring(0, s.length() - 1);
        return s + "…";
    }
}
