package tv.glasshome;

import android.service.dreams.DreamService;

/** StandBy as the TV's screensaver (the installer can choose it; so can the TV's screensaver setting). */
public final class StandByDream extends DreamService {
    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        setInteractive(false);
        setFullscreen(true);
        setContentView(new StandBy(this));
    }
}
