package tv.glasshome;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.View;

/** The profile picture: an emoji animal on its own colour, in a circle with a fine bright rim. */
final class Avatar extends View {
    private final Paint disc = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint face = new Paint(Paint.ANTI_ALIAS_FLAG);
    private int index;

    Avatar(Context c, int index) {
        super(c);
        rim.setStyle(Paint.Style.STROKE);
        rim.setStrokeWidth(Ui.dp(c, 1.5f));
        rim.setColor(0x99FFFFFF);
        face.setTextAlign(Paint.Align.CENTER);
        set(index);
    }

    void set(int index) {
        this.index = index;
        disc.setColor(Prefs.AVATAR_COLOURS[index]);
        invalidate();
    }

    int index() {
        return index;
    }

    @Override
    protected void onDraw(Canvas c) {
        float r = Math.min(getWidth(), getHeight()) / 2f, cx = getWidth() / 2f, cy = getHeight() / 2f;
        c.drawCircle(cx, cy, r, disc);
        c.drawCircle(cx, cy, r - rim.getStrokeWidth() / 2f, rim);
        face.setTextSize(r * 1.18f);
        Paint.FontMetrics fm = face.getFontMetrics();
        c.drawText(Prefs.AVATARS[index], cx, cy - (fm.ascent + fm.descent) / 2f, face);
    }
}
