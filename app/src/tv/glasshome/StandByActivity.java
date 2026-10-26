package tv.glasshome;

import android.app.Activity;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.WindowManager;

/** StandBy full screen, opened from Control Center: any button goes back home. */
public final class StandByActivity extends Activity {
    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_FULLSCREEN);
        setContentView(new StandBy(this));
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        int k = e.getKeyCode();
        if (k == KeyEvent.KEYCODE_VOLUME_UP || k == KeyEvent.KEYCODE_VOLUME_DOWN || k == KeyEvent.KEYCODE_VOLUME_MUTE) {
            return super.dispatchKeyEvent(e);
        }
        if (e.getAction() == KeyEvent.ACTION_UP) finish();
        return true;
    }
}
