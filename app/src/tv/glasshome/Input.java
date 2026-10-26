package tv.glasshome;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioManager;
import android.media.tv.TvContract;
import android.media.tv.TvInputManager;
import android.media.tv.TvView;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;

/**
 * Shows an HDMI or other passthrough input through Android's TvView. Adapted from dumbtv home
 * (github.com/humblemuzzu/dumbtv, MIT licence): on a Fire TV Edition television, handing the
 * input to Amazon's Live TV app lands in its channel-scan settings, while TvView needs no
 * permission and plays the input directly. BACK returns to the home screen.
 */
public final class Input extends Activity {
    private static final int MATCH = ViewGroup.LayoutParams.MATCH_PARENT;
    private static final int WRAP = ViewGroup.LayoutParams.WRAP_CONTENT;
    static final String EXTRA_ID = "input", EXTRA_NAME = "name";
    private static final long NAME_MS = 2500, SIGNAL_MS = 6000;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final AudioManager.OnAudioFocusChangeListener focus = change -> { };
    private TvView view;
    private TextView name, status;
    private String id;
    private final Runnable hideName = () -> name.animate().alpha(0f).setDuration(250)
            .withEndAction(() -> name.setVisibility(View.GONE)).start();
    private final Runnable noSignal = () -> status("No signal");
    private final TvView.TvInputCallback watch = new TvView.TvInputCallback() {
        @Override
        public void onConnectionFailed(String inputId) {
            status("Could not open input");
        }

        @Override
        public void onDisconnected(String inputId) {
            status("Could not open input");
        }

        @Override
        public void onVideoAvailable(String inputId) {
            status(null);
        }

        @Override
        public void onVideoUnavailable(String inputId, int reason) {
            boolean pending = reason == TvInputManager.VIDEO_UNAVAILABLE_REASON_TUNING
                    || reason == TvInputManager.VIDEO_UNAVAILABLE_REASON_BUFFERING;
            if (!pending) status("No signal");
        }
    };

    static Intent intent(Context c, Catalog.Item it) {
        return new Intent(c, Input.class).putExtra(EXTRA_ID, it.id).putExtra(EXTRA_NAME, it.label);
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        id = getIntent().getStringExtra(EXTRA_ID);
        getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        FrameLayout root = new FrameLayout(this);
        if (getSystemService(Context.TV_INPUT_SERVICE) != null) {
            view = new TvView(this);
            view.setCallback(watch);
            view.setFocusable(true);
            root.addView(view, new FrameLayout.LayoutParams(MATCH, MATCH));
        }
        status = Ui.text(this, 22, 0xCCFFFFFF, Ui.LIGHT);
        status.setVisibility(View.GONE);
        root.addView(status, new FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER));
        name = Ui.text(this, 20, 0xFFFFFFFF, Ui.REGULAR);
        name.setText(getIntent().getCharSequenceExtra(EXTRA_NAME));
        name.setPadding(Ui.dp(this, 22), Ui.dp(this, 10), Ui.dp(this, 22), Ui.dp(this, 10));
        GradientDrawable pill = new GradientDrawable();
        pill.setCornerRadius(Ui.dp(this, 22));
        pill.setColor(0x99101420);
        pill.setStroke(Ui.dp(this, 1), 0x66FFFFFF);
        name.setBackground(pill);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(WRAP, WRAP, Gravity.TOP | Gravity.START);
        lp.setMargins(Ui.dp(this, 48), Ui.dp(this, 36), 0, 0);
        root.addView(name, lp);
        setContentView(root);
    }

    @Override
    @SuppressWarnings("deprecation")
    protected void onStart() {
        super.onStart();
        showName();
        if (view == null || id == null) {
            status("Could not open input");
            return;
        }
        ((AudioManager) getSystemService(Context.AUDIO_SERVICE))
                .requestAudioFocus(focus, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN);
        view.setStreamVolume(1f);
        view.tune(id, TvContract.buildChannelUriForPassthroughInput(id));
        view.requestFocus();
        ui.postDelayed(noSignal, SIGNAL_MS);
    }

    @Override
    @SuppressWarnings("deprecation")
    protected void onStop() {
        ui.removeCallbacksAndMessages(null);
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (view != null) {
            view.reset();
            ((AudioManager) getSystemService(Context.AUDIO_SERVICE)).abandonAudioFocus(focus);
        }
        super.onStop();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        switch (e.getKeyCode()) {
            case KeyEvent.KEYCODE_BACK:
                if (e.getAction() == KeyEvent.ACTION_UP) finish();
                return true;
            case KeyEvent.KEYCODE_MENU:
            case KeyEvent.KEYCODE_INFO:
                if (e.getAction() == KeyEvent.ACTION_DOWN) showName();
                return true;
            default:
                return super.dispatchKeyEvent(e);
        }
    }

    private void showName() {
        ui.removeCallbacks(hideName);
        name.animate().cancel();
        name.setAlpha(1f);
        name.setVisibility(View.VISIBLE);
        ui.postDelayed(hideName, NAME_MS);
    }

    /** Keeps the TV awake while the input shows a picture, as Amazon's input player does. */
    private void status(String text) {
        if (text == null) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        ui.removeCallbacks(noSignal);
        status.setText(text);
        status.setVisibility(text == null ? View.GONE : View.VISIBLE);
    }
}
