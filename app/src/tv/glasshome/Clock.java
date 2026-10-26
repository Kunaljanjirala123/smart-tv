package tv.glasshome;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.service.dreams.DreamService;
import android.text.format.DateFormat;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Calendar;
import java.util.Random;

/**
 * The screensaver: a plain clock on black. Once a minute it fades to a new place on the screen,
 * so nothing stays lit in one spot for long. The installer makes it the TV's screensaver.
 */
public final class Clock extends DreamService {
    private final Random random = new Random();
    private FrameLayout root;
    private LinearLayout face;
    private TextView time, date;

    private final BroadcastReceiver tick = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            moveAndShow();
        }
    };

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        setInteractive(false);
        setFullscreen(true);
        root = new FrameLayout(this);
        root.setBackgroundColor(0xFF000000);
        face = new LinearLayout(this);
        face.setOrientation(LinearLayout.VERTICAL);
        face.setGravity(Gravity.CENTER_HORIZONTAL);
        time = Ui.text(this, 120, 0xE6FFFFFF, Ui.THIN);
        date = Ui.text(this, 24, 0x99FFFFFF, Ui.LIGHT);
        date.setPadding(0, Ui.dp(this, 4), 0, 0);
        face.addView(time);
        face.addView(date);
        root.addView(face, new FrameLayout.LayoutParams(-2, -2));
        setContentView(root);
    }

    @Override
    public void onDreamingStarted() {
        super.onDreamingStarted();
        IntentFilter f = new IntentFilter(Intent.ACTION_TIME_TICK);
        f.addAction(Intent.ACTION_TIME_CHANGED);
        f.addAction(Intent.ACTION_TIMEZONE_CHANGED);
        registerReceiver(tick, f);
        update();
        face.setAlpha(0f);
        // First placement once the face has been measured: centred, then it starts to wander.
        root.post(() -> {
            place(0.5f, 0.5f);
            face.animate().alpha(1f).setDuration(1200).withLayer().start();
        });
    }

    @Override
    public void onDreamingStopped() {
        try {
            unregisterReceiver(tick);
        } catch (IllegalArgumentException ignored) {
        }
        super.onDreamingStopped();
    }

    private void update() {
        Calendar now = Calendar.getInstance();
        time.setText(DateFormat.format(DateFormat.is24HourFormat(this) ? "H:mm" : "h:mm", now));
        date.setText(DateFormat.format("EEEE d MMMM", now));
    }

    /** Fades out, moves somewhere new with the new time, fades back in. */
    private void moveAndShow() {
        face.animate().alpha(0f).setDuration(700).withLayer().withEndAction(() -> {
            update();
            root.post(() -> {
                place(random.nextFloat(), random.nextFloat());
                face.animate().alpha(1f).setDuration(900).withLayer().start();
            });
        }).start();
    }

    /** Puts the face at a fraction across and down the free space, kept clear of the edges. */
    private void place(float fx, float fy) {
        int margin = Ui.dp(this, 60);
        int freeW = Math.max(0, root.getWidth() - face.getWidth() - 2 * margin);
        int freeH = Math.max(0, root.getHeight() - face.getHeight() - 2 * margin);
        face.setTranslationX(margin + fx * freeW);
        face.setTranslationY(margin + fy * freeH);
        face.setVisibility(View.VISIBLE);
    }
}
