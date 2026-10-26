package tv.glasshome;

import android.accessibilityservice.AccessibilityService;
import android.app.ActivityOptions;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.PixelFormat;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;

/**
 * Takes the HOME button from Amazon's launcher. Adapted from dumbtv home's Guard
 * (github.com/humblemuzzu/dumbtv, MIT licence), which worked this out on Fire OS 7 and 8:
 *
 * Fire OS keeps HOME bound to Amazon's protected launcher, so this service waits for Amazon's
 * home window and replaces it. A plain startActivity after a HOME press is held back by Android's
 * five-second app-switch delay; an alarm-fired PendingIntent is sent by system_server, which is
 * exempt, and fires at once with {@code min_futurity=0} (set by the installer). Android 11 also
 * refuses an activity start from an app with no visible window, so a plain cover overlay stays
 * added at zero alpha (still a visible window, though SurfaceFlinger skips drawing it): covering
 * is a single alpha change and the launch goes out at the same moment. The cover also hides
 * Amazon's home, so it never flashes on screen.
 */
public final class Guard extends AccessibilityService {
    static final String AMAZON = "com.amazon.tv.launcher";
    private static final String AMAZON_HOME = "com.amazon.tv.launcher.ui.HomeActivity_vNext";
    private static final int COVER_COLOR = 0xFF070912;
    private static final long COVER_MAX_MS = 1500;
    private static final long REPEAT_MS = 1000;

    private static volatile boolean paused;
    private static Guard running;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable uncover = this::uncover;
    private View cover;
    private WindowManager.LayoutParams coverLp;
    private Volume volume;
    private long lastLaunch;
    private boolean onAmazonHome;
    private final BroadcastReceiver screenOn = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            if (!paused && onAmazonHome) launchWhenCovered();
        }
    };

    /** Lets Amazon's home stay on screen until the user leaves it. */
    static void pause() {
        paused = true;
    }

    static void home(Context c) {
        AlarmManager alarms = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        alarms.setExact(AlarmManager.ELAPSED_REALTIME, SystemClock.elapsedRealtime(), launch(c));
    }

    /** Drops a queued launch, so it cannot pull the user back after they opened an app. */
    static void arrived(Context c) {
        AlarmManager alarms = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        alarms.cancel(launch(c));
    }

    /** Called once our home window is on screen. */
    static void shown() {
        if (running != null) running.uncover();
    }

    private static PendingIntent launch(Context c) {
        Intent i = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                .setClass(c, Home.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        return PendingIntent.getActivity(c, 0, i, PendingIntent.FLAG_UPDATE_CURRENT,
                ActivityOptions.makeCustomAnimation(c, 0, 0).toBundle());
    }

    /** Opens Amazon's own home screen once, without the Guard taking it back. */
    static void amazon(Context c) {
        pause();
        Intent i = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                .setPackage(AMAZON).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            c.startActivity(i);
        } catch (ActivityNotFoundException e) {
            paused = false;
        }
    }

    @Override
    protected void onServiceConnected() {
        running = this;
        View view = new View(this);
        view.setBackgroundColor(COVER_COLOR);
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.OPAQUE);
        lp.windowAnimations = 0;
        lp.alpha = 0f;
        coverLp = lp;
        // The service can be re-bound while it is still starting (the installer switches it off
        // and on); the old connection's window token is then gone, so go on without the cover.
        try {
            ((WindowManager) getSystemService(WINDOW_SERVICE)).addView(view, lp);
            cover = view;
        } catch (WindowManager.BadTokenException e) {
            cover = null;
        }
        registerReceiver(screenOn, new IntentFilter(Intent.ACTION_SCREEN_ON));
        Volume.enabled = new Prefs(this).glassVolume();
        volume = new Volume(this);
        volume.listen();
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacks(uncover);
        try {
            unregisterReceiver(screenOn);
        } catch (IllegalArgumentException ignored) {
        }
        if (cover != null) ((WindowManager) getSystemService(WINDOW_SERVICE)).removeView(cover);
        if (volume != null) volume.dismiss();
        if (running == this) running = null;
        super.onDestroy();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent e) {
        if (e.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;
        CharSequence pkg = e.getPackageName(), cls = e.getClassName();
        if (pkg == null) return;
        if (!AMAZON.contentEquals(pkg)) {
            if ("com.android.systemui".contentEquals(pkg)) {
                // Amazon's volume bar opening: cover it with the glass one straight away.
                if (volume != null && cls != null && cls.toString().contains("VolumeDialog")) volume.amazonShown();
                return;
            }
            if ("android".contentEquals(pkg)) return;
            onAmazonHome = false;
            paused = false;
            if (!getPackageName().contentEquals(pkg)) arrived(this);
            return;
        }
        onAmazonHome = cls != null && AMAZON_HOME.contentEquals(cls);
        // Glass Home already in front of it: nothing to replace, and no cover to flash.
        if (paused || !onAmazonHome || Home.showing) return;
        long now = SystemClock.uptimeMillis();
        if (now - lastLaunch < REPEAT_MS) return;
        lastLaunch = now;
        launchWhenCovered();
    }

    private void launchWhenCovered() {
        cover();
        home(this);
    }

    private void cover() {
        handler.removeCallbacks(uncover);
        handler.postDelayed(uncover, COVER_MAX_MS);
        setCoverAlpha(1f);
    }

    private void uncover() {
        handler.removeCallbacks(uncover);
        setCoverAlpha(0f);
    }

    private void setCoverAlpha(float a) {
        if (cover == null || coverLp.alpha == a) return;
        coverLp.alpha = a;
        try {
            ((WindowManager) getSystemService(WINDOW_SERVICE)).updateViewLayout(cover, coverLp);
        } catch (IllegalArgumentException ignored) {
        }
    }

    @Override
    public void onInterrupt() {}
}
