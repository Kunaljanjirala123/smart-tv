package tv.glasshome;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.ActivityOptions;
import android.app.AppOpsManager;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.provider.Settings;
import android.text.format.DateFormat;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The home screen, in the tvOS style. Page one: the weather and the clock along the top, the
 * focused app glowing on the stage in the middle, and the favourites in a glass dock at the
 * bottom. Press Down from the dock and the page glides up to every other app and folder.
 * Control Center opens from the clock; settings from the gear or the Menu button.
 */
public final class Home extends Activity implements ControlCenter.Host, Setup.Host {
    private static final int STAGE_CACHE = 6;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService artWorker = Executors.newSingleThreadExecutor();
    private final ExecutorService stageWorker = Executors.newSingleThreadExecutor();
    private final ExecutorService net = Executors.newSingleThreadExecutor();
    // Art outlives the activity, which a new theme recreates: switching looks is then instant.
    // App art depends on the icon style, never on the theme. All touched on the UI thread only.
    private static final Map<String, Bitmap> artCache = new HashMap<>();
    private static final Map<String, Integer> brandCache = new HashMap<>();
    private static final Map<String, Catalog.StageArt> stageCache = new LinkedHashMap<String, Catalog.StageArt>(8, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Catalog.StageArt> e) {
            return size() > STAGE_CACHE;
        }
    };
    private final List<Tile> tiles = new ArrayList<>();
    private final List<Catalog.Item> folderItems = new ArrayList<>();
    private final List<Sheet> sheets = new ArrayList<>();
    private final RectF rect = new RectF();
    private final Rect tmp = new Rect();

    private Prefs prefs;
    private List<Catalog.Item> apps = new ArrayList<>();
    private FrameLayout root, content;
    private Pager pager;
    private LinearLayout column, dockRow, grid;
    private TextView appsHeader, clock, hint;
    private View scrim;
    private Backdrop.Wallpaper wallpaper;
    private int washColour;
    private Stage stage;
    private Lens lens;
    private View weatherPill;
    private ImageView weatherGlyph, netGlyph;
    private TextView weatherTemp, weatherWords, weatherPlace;
    private Avatar barAvatar;
    private ControlCenter cc;
    private Setup setup;
    private Weather.Report report;
    private String lastFocusId, stageWant, movingId, netLabel = "Offline", netSub = "Not connected";
    private int netGlyphKind = Glyph.WIFI_OFF;
    private int pageH, screenW, side, perRow, dockN, dockW, dockH, gridW, gridH, artW, artH, stageW, stageH;
    private boolean resumed, pendingRecreate, useLens, timeWatched;

    private final BroadcastReceiver time = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            tick();
        }
    };
    private final BroadcastReceiver packages = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            // An installed, updated or removed app may have new art: draw it again.
            String pkg = i.getData() != null ? i.getData().getSchemeSpecificPart() : null;
            if (pkg != null) {
                artCache.remove(Prefs.ICONS_TVOS + ":" + pkg);
                artCache.remove(Prefs.ICONS_BANNERS + ":" + pkg);
                brandCache.remove(pkg);
                stageCache.remove(pkg);
            }
            ui.removeCallbacks(reload);
            ui.postDelayed(reload, 400);
        }
    };
    private final Runnable reload = this::reload;
    private final Runnable weatherTick = () -> refreshWeather(false);
    private ConnectivityManager.NetworkCallback netCallback;

    // ---------------------------------------------------------------- lifecycle

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = new Prefs(this);
        report = Weather.parse(prefs.forecast());
        Theme.now = Theme.make(Theme.resolve(prefs.theme(), report != null ? report.sunrise : 0,
                report != null ? report.sunset : 0));
        Backdrop b = Backdrop.get(this);
        pageH = b.screenH;
        screenW = b.screenW;
        side = Ui.dp(this, 48);
        perRow = Math.max(5, Math.min(7, prefs.perRow()));
        dockN = Math.max(4, Math.min(6, prefs.dockSize()));
        useLens = Prefs.FOCUS_LENS.equals(prefs.focus());
        // tvOS app icons are 5:3; the stage shows 16:9 TV banners.
        int gridGap = Ui.dp(this, 22);
        gridW = (screenW - 2 * side - (perRow - 1) * gridGap) / perRow;
        gridH = Math.round(gridW * 0.6f);
        int dockGap = Ui.dp(this, dockN >= 6 ? 30 : dockN == 5 ? 34 : 40);
        int inner = screenW - 2 * side - 2 * Ui.dp(this, 14);
        dockW = (inner - (dockN - 1) * dockGap) / dockN;
        dockH = Math.round(dockW * 0.6f);
        artW = Math.max(dockW, gridW);
        artH = Math.round(artW * 0.6f);
        stageW = Ui.dp(this, 352);
        stageH = Ui.dp(this, 198);
        build(gridGap, dockGap);
        reload();
        IntentFilter pf = new IntentFilter();
        pf.addAction(Intent.ACTION_PACKAGE_ADDED);
        pf.addAction(Intent.ACTION_PACKAGE_REMOVED);
        pf.addAction(Intent.ACTION_PACKAGE_CHANGED);
        pf.addDataScheme("package");
        registerReceiver(packages, pf);
        watchNetwork();
        updateWeatherPill();
        if (!prefs.setupDone() && !"setup".equals(getIntent().getStringExtra("open"))) showSetup();
        handleExtras(getIntent());
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        Guard.arrived(this);
        // Auto: day turned to night (or back) while away.
        String want = Theme.resolve(prefs.theme(), report != null ? report.sunrise : 0, report != null ? report.sunset : 0);
        if (!want.equals(Theme.now.id) && sheets.isEmpty() && cc == null && setup == null) {
            recreate();
            return;
        }
        IntentFilter f = new IntentFilter(Intent.ACTION_TIME_TICK);
        f.addAction(Intent.ACTION_TIME_CHANGED);
        f.addAction(Intent.ACTION_TIMEZONE_CHANGED);
        registerReceiver(time, f);
        timeWatched = true;
        tick();
        updateNetwork();
        refreshWeather(false);
        if (sheets.isEmpty() && cc == null && setup == null) restoreFocus();
    }

    @Override
    public void onEnterAnimationComplete() {
        Guard.shown();
    }

    @Override
    protected void onPause() {
        super.onPause();
        resumed = false;
        // onResume returns early when it recreates for a new theme, before watching the time.
        if (timeWatched) unregisterReceiver(time);
        timeWatched = false;
        ui.removeCallbacks(weatherTick);
        if (movingId != null) endMove();
    }

    @Override
    protected void onDestroy() {
        unregisterReceiver(packages);
        if (netCallback != null) {
            try {
                ((ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE)).unregisterNetworkCallback(netCallback);
            } catch (RuntimeException ignored) {
            }
        }
        artWorker.shutdownNow();
        stageWorker.shutdownNow();
        net.shutdownNow();
        super.onDestroy();
    }

    /** Under memory pressure in the background, the large stage pictures go first; they are quick to redo. */
    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        if (level >= TRIM_MEMORY_BACKGROUND) stageCache.clear();
    }

    /** HOME while already home: everything closed, back to the first dock app. */
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (movingId != null) endMove();
        while (!sheets.isEmpty()) sheets.get(sheets.size() - 1).close();
        if (cc != null) cc.close();
        if (setup == null) focusDock(0);
        handleExtras(intent);
    }

    /**
     * Extras for the installer and for testing: {@code city} sets the weather's place,
     * {@code theme} the look, {@code open} shows control, weather, settings, standby or setup.
     */
    private void handleExtras(Intent i) {
        if (i == null) return;
        String theme = i.getStringExtra("theme");
        i.removeExtra("theme");
        if (theme != null && java.util.Arrays.asList(Theme.CHOICES).contains(theme) && !theme.equals(prefs.theme())) {
            // onResume, which always follows, sees the new look and recreates once.
            prefs.setTheme(theme);
            return;
        }
        if (i.getBooleanExtra("skip_setup", false) && setup != null) {
            prefs.set("setup_done", true);
            setup.animate().alpha(0f).setDuration(150).withEndAction(this::removeSetup).start();
        }
        final String city = i.getStringExtra("city");
        i.removeExtra("city");
        if (city != null && !city.trim().isEmpty()) {
            searchPlaces(city, (places, error) -> {
                if (places != null && !places.isEmpty()) {
                    Weather.Place p = places.get(0);
                    prefs.setPlace(p.name, p.lat, p.lon);
                    prefs.set("weather_on", true);
                    report = null;
                    updateWeatherPill();
                    refreshWeather(true);
                }
            });
        }
        String open = i.getStringExtra("open");
        i.removeExtra("open");
        if (open == null) return;
        switch (open) {
            case "control": ui.postDelayed(this::controlCenter, 300); break;
            case "weather": ui.postDelayed(this::weatherSheet, 300); break;
            case "settings": ui.postDelayed(this::settingsMenu, 300); break;
            case "standby": standBy(); break;
            case "setup": ui.postDelayed(this::showSetup, 300); break;
            case "apps": ui.postDelayed(() -> { if (!tiles.isEmpty()) gridFirst(); }, 300); break;
            default: break;
        }
    }

    @Override
    public void onBackPressed() {
        if (movingId != null) {
            endMove();
            return;
        }
        if (!sheets.isEmpty()) {
            sheets.get(sheets.size() - 1).close();
            return;
        }
        if (cc != null) {
            cc.close();
            return;
        }
        focusDock(0);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        if (movingId != null && sheets.isEmpty()) {
            int k = e.getKeyCode();
            if (k == KeyEvent.KEYCODE_HOME || k == KeyEvent.KEYCODE_VOLUME_UP || k == KeyEvent.KEYCODE_VOLUME_DOWN
                    || k == KeyEvent.KEYCODE_VOLUME_MUTE) {
                return super.dispatchKeyEvent(e);
            }
            if (e.getAction() == KeyEvent.ACTION_DOWN) {
                switch (k) {
                    case KeyEvent.KEYCODE_DPAD_LEFT: shift(-1, 0); break;
                    case KeyEvent.KEYCODE_DPAD_RIGHT: shift(1, 0); break;
                    case KeyEvent.KEYCODE_DPAD_UP: shift(0, -1); break;
                    case KeyEvent.KEYCODE_DPAD_DOWN: shift(0, 1); break;
                    default: break;
                }
            } else if (e.getAction() == KeyEvent.ACTION_UP) {
                if (k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER || k == KeyEvent.KEYCODE_BACK
                        || k == KeyEvent.KEYCODE_MENU) {
                    endMove();
                }
            }
            return true;
        }
        return super.dispatchKeyEvent(e);
    }

    @Override
    public boolean onKeyUp(int code, KeyEvent e) {
        if (code == KeyEvent.KEYCODE_MENU && sheets.isEmpty() && cc == null && setup == null) {
            View f = getCurrentFocus();
            if (f instanceof Tile) appMenu((Tile) f);
            else settingsMenu();
            return true;
        }
        return super.onKeyUp(code, e);
    }

    @Override
    public boolean onKeyDown(int code, KeyEvent e) {
        if (code == KeyEvent.KEYCODE_MENU) return true;
        return super.onKeyDown(code, e);
    }

    // ---------------------------------------------------------------- layout

    private void build(int gridGap, int dockGap) {
        Theme t = Theme.now;
        root = new FrameLayout(this);
        wallpaper = new Backdrop.Wallpaper(this);
        root.addView(wallpaper, new FrameLayout.LayoutParams(-1, -1));
        // A single fill, faded as the page scrolls: no offscreen buffer needed.
        scrim = new View(this) {
            @Override
            public boolean hasOverlappingRendering() {
                return false;
            }
        };
        scrim.setBackgroundColor(t.appsScrim);
        scrim.setAlpha(0f);
        root.addView(scrim, new FrameLayout.LayoutParams(-1, -1));

        pager = new Pager(this);
        content = new FrameLayout(this);
        content.setClipChildren(false);
        column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setClipChildren(false);
        content.addView(column, new FrameLayout.LayoutParams(-1, -2));
        lens = new Lens(this);
        content.addView(lens, new FrameLayout.LayoutParams(-1, -1));
        pager.addView(content, new FrameLayout.LayoutParams(-1, -2));
        pager.setListener(y -> scrim.setAlpha(Math.max(0f, Math.min(1f, y / (pageH * 0.5f)))));
        root.addView(pager, new FrameLayout.LayoutParams(-1, -1));

        // Page one ------------------------------------------------------------
        FrameLayout page = new FrameLayout(this);
        page.setClipChildren(false);
        column.addView(page, new LinearLayout.LayoutParams(-1, pageH));

        if (prefs.stage()) {
            stage = new Stage(this, stageW, stageH);
            int sh = Stage.height(this, stageH), artCentre = Ui.dp(this, 200);
            FrameLayout.LayoutParams sl = new FrameLayout.LayoutParams(-1, sh, Gravity.TOP);
            sl.topMargin = artCentre - (sh - Ui.dp(this, 36)) / 2;
            page.addView(stage, sl);
        }
        page.addView(statusBar(), new FrameLayout.LayoutParams(-1, -2, Gravity.TOP));

        Glass.Frame dockBand = new Glass.Frame(this, 28);
        int bp = Ui.dp(this, 14);
        dockBand.setPadding(bp, bp, bp, bp);
        dockRow = new LinearLayout(this);
        dockRow.setOrientation(LinearLayout.HORIZONTAL);
        dockRow.setClipChildren(false);
        dockRow.setGravity(Gravity.CENTER);
        dockBand.addView(dockRow, new FrameLayout.LayoutParams(-1, -2));
        FrameLayout.LayoutParams dl = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
        dl.setMargins(side, 0, side, Ui.dp(this, 24));
        page.addView(dockBand, dl);
        dockRow.setTag(dockGap);

        // Every other app -----------------------------------------------------
        appsHeader = Ui.text(this, 26, t.light ? t.panelText : t.text, Ui.MEDIUM);
        appsHeader.setText("Apps");
        appsHeader.setPadding(side + Ui.dp(this, 4), Ui.dp(this, 34), side, Ui.dp(this, 18));
        column.addView(appsHeader);
        grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.VERTICAL);
        grid.setClipChildren(false);
        grid.setPadding(side, 0, side, Ui.dp(this, 90));
        grid.setClipToPadding(false);
        grid.setTag(gridGap);
        column.addView(grid, new LinearLayout.LayoutParams(-1, -2));

        // The hint shown while an app is being moved.
        hint = Ui.text(this, 15, t.chipText, Ui.MEDIUM);
        hint.setText("Use the arrows to move  ·  OK when done");
        int hp = Ui.dp(this, 18);
        hint.setPadding(hp, Ui.dp(this, 9), hp, Ui.dp(this, 9));
        GradientDrawable hb = new GradientDrawable();
        hb.setCornerRadius(Ui.dp(this, 20));
        hb.setColor(t.chip);
        hint.setBackground(hb);
        hint.setAlpha(0f);
        FrameLayout.LayoutParams hl = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        hl.topMargin = Ui.dp(this, 22);
        root.addView(hint, hl);

        root.getViewTreeObserver().addOnGlobalFocusChangeListener((old, now) -> focused(now));
        setContentView(root);
        // The wallpaper covers the whole window, so the window's own background would only be
        // painted to be painted over: drop it and save a full-screen fill on every frame.
        getWindow().setBackgroundDrawable(null);
    }

    /** Weather on the left; inputs, settings and the clock with the profile picture on the right. */
    private View statusBar() {
        Theme t = Theme.now;
        boolean full = !Prefs.BAR_MINIMAL.equals(prefs.bar());
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(side, Ui.dp(this, 24), side, 0);
        // Padding would otherwise clip the pills as they grow into focus.
        bar.setClipChildren(false);
        bar.setClipToPadding(false);

        // Weather ---------------------------------------------------------------
        LinearLayout w = new LinearLayout(this);
        w.setOrientation(LinearLayout.HORIZONTAL);
        w.setGravity(Gravity.CENTER_VERTICAL);
        int textColour = t.light ? t.cardText : t.pillText;
        weatherGlyph = new ImageView(this);
        int gs = Ui.dp(this, 30);
        w.addView(weatherGlyph, new LinearLayout.LayoutParams(gs, gs));
        weatherTemp = Ui.text(this, 27, textColour, Ui.LIGHT);
        weatherTemp.setPadding(Ui.dp(this, 10), 0, Ui.dp(this, 10), 0);
        w.addView(weatherTemp);
        LinearLayout words = new LinearLayout(this);
        words.setOrientation(LinearLayout.VERTICAL);
        weatherWords = Ui.text(this, 14, textColour, Ui.MEDIUM);
        weatherPlace = Ui.text(this, 12, Ui.alpha(textColour, 0x99), Ui.REGULAR);
        weatherPlace.setPadding(0, Ui.dp(this, 2), 0, 0);
        words.addView(weatherWords);
        words.addView(weatherPlace);
        w.addView(words, new LinearLayout.LayoutParams(-2, -2));
        weatherWords.setMaxWidth(Ui.dp(this, 150));
        weatherPlace.setMaxWidth(Ui.dp(this, 150));
        weatherPill = pill(w, t.light, Ui.dp(this, 16), Ui.dp(this, 11), v -> weatherSheet());
        bar.addView(weatherPill, new LinearLayout.LayoutParams(-2, Ui.dp(this, 56)));

        bar.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1f));

        // Buttons and the clock ---------------------------------------------------
        if (full) {
            bar.addView(roundButton(Glyph.INPUT, v -> inputsMenu()));
            bar.addView(roundButton(Glyph.GEAR, v -> settingsMenu()));
        }
        LinearLayout ck = new LinearLayout(this);
        ck.setOrientation(LinearLayout.HORIZONTAL);
        ck.setGravity(Gravity.CENTER_VERTICAL);
        if (full) {
            netGlyph = new ImageView(this);
            int ns = Ui.dp(this, 20);
            LinearLayout.LayoutParams nl = new LinearLayout.LayoutParams(ns, ns);
            nl.rightMargin = Ui.dp(this, 12);
            ck.addView(netGlyph, nl);
        }
        clock = Ui.text(this, 24, t.pillText, Ui.REGULAR);
        ck.addView(clock);
        barAvatar = new Avatar(this, prefs.avatar());
        int as = Ui.dp(this, 38);
        LinearLayout.LayoutParams al = new LinearLayout.LayoutParams(as, as);
        al.leftMargin = Ui.dp(this, 14);
        ck.addView(barAvatar, al);
        View clockPill = pill(ck, false, Ui.dp(this, 20), Ui.dp(this, 6), v -> controlCenter());
        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(-2, Ui.dp(this, 52));
        cl.leftMargin = Ui.dp(this, 12);
        bar.addView(clockPill, cl);
        return bar;
    }

    /**
     * A pill holding {@code inside}: solid white on the light theme (the weather card), glass
     * otherwise. It grows a little when focused.
     */
    private View pill(View inside, boolean solid, int padH, int padV, View.OnClickListener click) {
        FrameLayout f;
        if (solid) {
            f = new FrameLayout(this);
            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(Ui.dp(this, 28));
            bg.setColor(Theme.now.card);
            f.setBackground(bg);
            f.setElevation(Ui.dp(this, 5));
        } else {
            Glass.Frame g = new Glass.Frame(this, 28);
            g.glass().body = Theme.now.pill;
            f = g;
        }
        f.setOutlineProvider(Ui.PILL);
        f.setPadding(padH, padV, padH + Ui.dp(this, 4), padV);
        f.addView(inside, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER_VERTICAL));
        f.setFocusable(true);
        f.setClickable(true);
        f.setOnClickListener(click);
        f.setOnFocusChangeListener((v, has) -> v.animate().scaleX(has ? 1.08f : 1f).scaleY(has ? 1.08f : 1f)
                .translationZ(has ? Ui.dp(this, 8) : 0f).setInterpolator(has ? Ui.POP : Ui.EASE).setDuration(240).start());
        return f;
    }

    private View roundButton(int glyph, View.OnClickListener click) {
        Glass.Frame f = new Glass.Frame(this, 26);
        f.glass().body = Theme.now.pill;
        f.setOutlineProvider(Ui.PILL);
        ImageView g = new ImageView(this);
        g.setImageDrawable(new Glyph(glyph, Theme.now.pillText));
        int gs = Ui.dp(this, 22);
        f.addView(g, new FrameLayout.LayoutParams(gs, gs, Gravity.CENTER));
        f.setFocusable(true);
        f.setClickable(true);
        f.setOnClickListener(click);
        f.setOnFocusChangeListener((v, has) -> v.animate().scaleX(has ? 1.12f : 1f).scaleY(has ? 1.12f : 1f)
                .translationZ(has ? Ui.dp(this, 8) : 0f).setInterpolator(has ? Ui.POP : Ui.EASE).setDuration(240).start());
        int s = Ui.dp(this, 52);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(s, s);
        lp.leftMargin = Ui.dp(this, 12);
        f.setLayoutParams(lp);
        return f;
    }

    // ---------------------------------------------------------------- content

    /** Cached art is kept per icon style. */
    private String artKey(String id) {
        return prefs.icons() + ":" + id;
    }

    private void reload() {
        List<Catalog.Item> fresh = Catalog.apps(this);
        for (Catalog.Item it : fresh) {
            Bitmap b = artCache.get(artKey(it.id));
            if (b != null) {
                it.art = b;
                Integer col = brandCache.get(it.id);
                if (col != null) it.brand = col;
            }
        }
        apps = fresh;
        if (!prefs.hasDock()) prefs.setDock(Catalog.defaultDock(apps, dockN));
        populate();
        loadArt();
    }

    private Catalog.Item find(String id) {
        for (Catalog.Item it : apps) if (it.id.equals(id)) return it;
        return null;
    }

    private List<Catalog.Item> dockItems() {
        List<Catalog.Item> out = new ArrayList<>();
        for (String id : prefs.dock()) {
            Catalog.Item it = find(id);
            if (it != null && out.size() < dockN) out.add(it);
        }
        return out;
    }

    /** Grid entries: apps not in the dock, hidden or in a folder, and the folders, in the household's order. */
    private List<Catalog.Item> gridItems() {
        List<String> dock = prefs.dock(), hidden = prefs.hidden(), order = prefs.order();
        Map<String, Catalog.Item> folders = new LinkedHashMap<>();
        List<String> foldered = new ArrayList<>();
        for (String f : prefs.folders()) {
            Catalog.Item folder = new Catalog.Item(f, prefs.folderName(f), null);
            folder.members = new ArrayList<>();
            for (String id : prefs.folderApps(f)) {
                Catalog.Item it = find(id);
                if (it != null && !hidden.contains(id)) folder.members.add(it);
                foldered.add(id);
            }
            folders.put(f, folder);
        }
        List<Catalog.Item> out = new ArrayList<>();
        for (String id : order) {
            Catalog.Item it = folders.containsKey(id) ? folders.get(id) : find(id);
            if (it != null && !out.contains(it) && (it.isFolder() || eligible(it.id, dock, hidden, foldered))) out.add(it);
        }
        for (Catalog.Item f : folders.values()) if (!out.contains(f)) out.add(f);
        for (Catalog.Item it : apps) {
            if (!out.contains(it) && eligible(it.id, dock, hidden, foldered)) out.add(it);
        }
        return out;
    }

    private static boolean eligible(String id, List<String> dock, List<String> hidden, List<String> foldered) {
        return !dock.contains(id) && !hidden.contains(id) && !foldered.contains(id);
    }

    private void populate() {
        String keep = lastFocusId;
        tiles.clear();
        folderItems.clear();
        dockRow.removeAllViews();
        grid.removeAllViews();
        int dockGap = (Integer) dockRow.getTag(), gridGap = (Integer) grid.getTag();
        List<Catalog.Item> dock = dockItems();
        for (int i = 0; i < dock.size(); i++) {
            Tile t = tile(dock.get(i), dockW, dockH, false);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            if (i > 0) lp.leftMargin = dockGap;
            dockRow.addView(t, lp);
        }
        if (dock.isEmpty()) {
            TextView h = Ui.text(this, 16, Theme.now.text, Ui.REGULAR);
            h.setText("Press the Menu button (≡) on any app below and choose \"Add to Dock\"");
            h.setPadding(0, dockH / 3, 0, dockH / 3);
            dockRow.addView(h);
        }
        List<Catalog.Item> rest = gridItems();
        LinearLayout row = null;
        for (int i = 0; i < rest.size(); i++) {
            if (i % perRow == 0) {
                row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setClipChildren(false);
                LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(-1, -2);
                if (i > 0) rp.topMargin = Ui.dp(this, 6);
                grid.addView(row, rp);
            }
            Catalog.Item it = rest.get(i);
            if (it.isFolder()) {
                folderItems.add(it);
                Catalog.folderArt(it, artW, artH);
            }
            Tile t = tile(it, gridW, gridH, true);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            if (i % perRow > 0) lp.leftMargin = gridGap;
            row.addView(t, lp);
        }
        appsHeader.setVisibility(rest.isEmpty() ? View.GONE : View.VISIBLE);
        lastFocusId = keep;
        root.post(this::restoreFocus);
    }

    private Tile tile(Catalog.Item it, int w, int h, boolean label) {
        Tile t = new Tile(this, it, w, h, label);
        t.setOnClickListener(v -> {
            if (it.isFolder()) openFolder(it);
            else launch(it);
        });
        t.setOnLongClickListener(v -> {
            appMenu(t);
            return true;
        });
        tiles.add(t);
        return t;
    }

    private Tile tileFor(String id) {
        for (Tile t : tiles) if (t.item.id.equals(id) && t.getParent() != null && isInside(t, column)) return t;
        return null;
    }

    private void loadArt() {
        final List<Catalog.Item> todo = new ArrayList<>();
        for (Catalog.Item it : apps) if (it.art == null) todo.add(it);
        if (todo.isEmpty()) return;
        final PackageManager pm = getPackageManager();
        final boolean tvos = !Prefs.ICONS_BANNERS.equals(prefs.icons());
        // Dock apps first, so the first screen fills in before anything else.
        final List<String> dock = prefs.dock();
        Collections.sort(todo, (a, b) -> Boolean.compare(!dock.contains(a.id), !dock.contains(b.id)));
        artWorker.execute(() -> {
            for (Catalog.Item it : todo) {
                if (Thread.currentThread().isInterrupted()) return;
                try {
                    Catalog.art(pm, it, artW, artH, tvos);
                } catch (RuntimeException | OutOfMemoryError ignored) {
                }
                ui.post(() -> artArrived(it));
            }
        });
    }

    private void artArrived(Catalog.Item it) {
        if (it.art != null) {
            artCache.put(artKey(it.id), it.art);
            brandCache.put(it.id, it.brand);
        }
        for (Tile t : tiles) if (t.item.id.equals(it.id)) t.invalidate();
        // Folders show their apps' art: redraw the ones holding this app.
        for (Catalog.Item f : folderItems) {
            if (f.members.contains(it)) {
                Catalog.folderArt(f, artW, artH);
                for (Tile t : tiles) if (t.item == f) t.invalidate();
            }
        }
        View f = getCurrentFocus();
        if (f instanceof Tile && ((Tile) f).item == it && f.getParent() == dockRow) setWash(it.brand);
    }

    // ---------------------------------------------------------------- focus and motion

    private void focused(View now) {
        if (now == null || !isInside(now, column)) {
            if (now != null) lens.hide();
            return;
        }
        if (now instanceof Tile) {
            Tile t = (Tile) now;
            if (movingId == null) lastFocusId = t.item.id;
            tmp.set(0, 0, 0, 0);
            content.offsetDescendantRectToMyCoords(t, tmp);
            if (useLens && movingId == null) {
                float s = Tile.FOCUS_SCALE, pad = Ui.dp(this, 3);
                float cx = t.artW / 2f, cy = t.artH / 2f;
                float hw = t.artW * s / 2f + pad, hh = t.artH * s / 2f + pad;
                rect.set(tmp.left + cx - hw, tmp.top + cy - hh, tmp.left + cx + hw, tmp.top + cy + hh);
                lens.moveTo(rect);
            }
            if (t.getParent() == dockRow) {
                showStage(t.item);
                setWash(t.item.brand);
                scrollTo(0);
            } else {
                scrollToShow(tmp.top, tmp.top + t.getHeight());
            }
        } else {
            // A pill or button at the top.
            lens.hide();
            scrollTo(0);
        }
    }

    private static boolean isInside(View v, View container) {
        for (Object p = v; p instanceof View; p = ((View) p).getParent()) if (p == container) return true;
        return false;
    }

    private void showStage(final Catalog.Item it) {
        if (stage == null || it.isFolder()) return;
        stageWant = it.id;
        Catalog.StageArt a = stageCache.get(it.id);
        if (a != null) {
            stage.show(it, a);
            return;
        }
        final PackageManager pm = getPackageManager();
        stageWorker.execute(() -> {
            Catalog.StageArt s = null;
            try {
                s = Catalog.stage(pm, it, stageW, stageH);
            } catch (RuntimeException | OutOfMemoryError ignored) {
            }
            final Catalog.StageArt done = s;
            ui.post(() -> {
                if (done == null) return;
                stageCache.put(it.id, done);
                if (it.id.equals(stageWant)) stage.show(it, done);
            });
        });
    }

    /** The backdrop takes on the focused app's colour, crossfading from the last one. */
    private void setWash(int colour) {
        if (colour == washColour) return;
        washColour = colour;
        wallpaper.wash(colour);
    }

    /** Keeps the band from {@code top} to {@code bottom} (content coordinates) comfortably on screen. */
    private void scrollToShow(int top, int bottom) {
        int view = pager.getHeight() > 0 ? pager.getHeight() : pageH;
        int min = appsHeader.getTop() - Ui.dp(this, 26);
        int target = Math.max(min, bottom + Ui.dp(this, 70) - view);
        if (top - target < Ui.dp(this, 90)) target = top - Ui.dp(this, 90);
        int max = Math.max(0, content.getHeight() - view);
        scrollTo(Math.max(0, Math.min(target, max)));
    }

    private void scrollTo(int y) {
        pager.glideTo(y);
    }

    private void restoreFocus() {
        if (!sheets.isEmpty() || cc != null || setup != null) return;
        if (lastFocusId != null) {
            Tile t = tileFor(lastFocusId);
            if (t != null) {
                t.requestFocus();
                // Focus given before the window is attached sends no focus event: sync the stage.
                focused(t);
                return;
            }
        }
        focusDock(0);
    }

    private void focusDock(int i) {
        if (dockRow.getChildCount() > 0) {
            View v = dockRow.getChildAt(Math.min(i, dockRow.getChildCount() - 1));
            if (v instanceof Tile) {
                v.requestFocus();
                focused(v);
                scrollTo(0);
                return;
            }
        }
        if (!tiles.isEmpty()) tiles.get(0).requestFocus();
    }

    private void gridFirst() {
        for (Tile t : tiles) {
            if (t.getParent() != dockRow && isInside(t, grid)) {
                t.requestFocus();
                return;
            }
        }
    }

    // ---------------------------------------------------------------- move mode

    /** Picks the app up: it jiggles, the arrows move it, OK puts it down. */
    private void startMove(String id) {
        Tile t = tileFor(id);
        if (t == null) return;
        movingId = id;
        lens.hide();
        t.requestFocus();
        t.setMoving(true);
        hint.animate().alpha(1f).setDuration(200).start();
    }

    private void shift(int dx, int dy) {
        List<String> dock = prefs.dock();
        int i = dock.indexOf(movingId);
        if (i >= 0) {
            if (dy != 0) return;
            int j = i + dx;
            if (j < 0 || j >= Math.min(dock.size(), dockN)) return;
            Collections.swap(dock, i, j);
            prefs.setDock(dock);
        } else {
            List<String> ids = new ArrayList<>();
            for (Catalog.Item it : gridItems()) ids.add(it.id);
            i = ids.indexOf(movingId);
            int j = i + dx + dy * perRow;
            if (i < 0 || j < 0 || j >= ids.size()) return;
            ids.remove(i);
            ids.add(j, movingId);
            prefs.setOrder(ids);
        }
        lastFocusId = movingId;
        populate();
        root.post(() -> {
            Tile t = tileFor(movingId);
            if (t != null) {
                t.requestFocus();
                t.setMoving(true);
            }
        });
    }

    private void endMove() {
        Tile t = tileFor(movingId);
        movingId = null;
        hint.animate().alpha(0f).setDuration(200).start();
        if (t != null) {
            t.setMoving(false);
            t.requestFocus();
            focused(t);
        }
    }

    // ---------------------------------------------------------------- clock, network, weather

    private void tick() {
        Calendar now = Calendar.getInstance();
        clock.setText(DateFormat.format(DateFormat.is24HourFormat(this) ? "H:mm" : "h:mm a", now));
    }

    private void watchNetwork() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        if (cm == null) return;
        netCallback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(Network n) {
                ui.post(Home.this::updateNetwork);
            }

            @Override
            public void onLost(Network n) {
                ui.post(Home.this::updateNetwork);
            }

            @Override
            public void onCapabilitiesChanged(Network n, NetworkCapabilities caps) {
                ui.post(Home.this::updateNetwork);
            }
        };
        try {
            cm.registerDefaultNetworkCallback(netCallback);
        } catch (RuntimeException e) {
            netCallback = null;
        }
    }

    private void updateNetwork() {
        String label = "Offline", sub = "Not connected";
        int glyph = Glyph.WIFI_OFF;
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
            NetworkCapabilities caps = cm.getNetworkCapabilities(cm.getActiveNetwork());
            if (caps != null) {
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
                    label = "Ethernet";
                    glyph = Glyph.ETHERNET;
                } else {
                    label = "Wi-Fi";
                    glyph = Glyph.WIFI;
                }
                sub = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) ? "Connected" : "No internet";
            }
        } catch (RuntimeException ignored) {
        }
        netLabel = label;
        netSub = sub;
        netGlyphKind = glyph;
        if (netGlyph != null) netGlyph.setImageDrawable(new Glyph(glyph, Theme.now.pillText));
        if (!"Offline".equals(label)) refreshWeather(false);
    }

    /** Fetches a new forecast when the kept one is old (or {@code force}), then shows it. */
    private void refreshWeather(boolean force) {
        ui.removeCallbacks(weatherTick);
        if (!prefs.weatherOn() || !prefs.hasPlace()) {
            updateWeatherPill();
            return;
        }
        final boolean f = prefs.fahrenheit();
        Weather.Report kept = report != null ? report : Weather.parse(prefs.forecast());
        boolean fresh = kept != null && kept.fahrenheit == f
                && System.currentTimeMillis() - kept.fetched < Weather.FRESH_MS;
        report = kept != null && kept.fahrenheit == f ? kept : null;
        updateWeatherPill();
        if (resumed) ui.postDelayed(weatherTick, Weather.FRESH_MS);
        if (fresh && !force) return;
        final double lat = prefs.lat(), lon = prefs.lon();
        net.execute(() -> {
            try {
                final String json = Weather.fetch(lat, lon, f);
                ui.post(() -> {
                    if (lat != prefs.lat() || lon != prefs.lon()) return;
                    prefs.setForecast(json);
                    report = Weather.parse(json);
                    updateWeatherPill();
                });
            } catch (IOException | RuntimeException ignored) {
                // Offline: keep showing what we have.
            }
        });
    }

    private void updateWeatherPill() {
        if (weatherPill == null) return;
        if (!prefs.weatherOn()) {
            weatherPill.setVisibility(View.GONE);
            return;
        }
        weatherPill.setVisibility(View.VISIBLE);
        Theme t = Theme.now;
        int ink = t.light ? t.cardText : t.pillText;
        if (!prefs.hasPlace()) {
            weatherGlyph.setImageDrawable(new Glyph(Glyph.PIN, ink));
            weatherTemp.setText("");
            weatherTemp.setVisibility(View.GONE);
            weatherWords.setText("Add Weather");
            weatherPlace.setText("Choose your city");
            return;
        }
        weatherTemp.setVisibility(View.VISIBLE);
        Weather.Report r = report;
        if (r == null) {
            weatherGlyph.setImageDrawable(new Glyph(Glyph.CLOUD, Ui.alpha(ink, 0x80)));
            weatherTemp.setText("--°");
            weatherWords.setText("Getting the weather…");
        } else {
            weatherGlyph.setImageDrawable(new Glyph(Weather.glyph(r.code, r.day), ink, t.light ? ink : 0xFFFFD60A));
            weatherTemp.setText(r.temp + "°");
            weatherWords.setText(Weather.describe(r.code, r.day));
        }
        weatherPlace.setText(prefs.place());
    }

    // ---------------------------------------------------------------- opening things

    @Override
    public void open(Catalog.Item it) {
        launch(it);
    }

    private void launch(Catalog.Item it) {
        if (it.intent == null) return;
        if (Guard.AMAZON.equals(it.id)) Guard.pause();
        try {
            Intent i = new Intent(it.intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            // No transition: the app's own window appears the moment it is ready.
            startActivity(i, ActivityOptions.makeCustomAnimation(this, 0, 0).toBundle());
        } catch (ActivityNotFoundException | SecurityException e) {
            Toast.makeText(this, "Could not open " + it.label, Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * Fire OS answers the generic settings action with a stub that reopens Amazon's home; the TV
     * settings entry point opens the real settings screen (found by dumbtv home).
     */
    @Override
    public void tvSettings() {
        Intent tv = new Intent(Intent.ACTION_MAIN).addCategory("android.intent.category.LEANBACK_SETTINGS")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            if (tv.resolveActivity(getPackageManager()) != null) startActivity(tv);
            else startActivity(new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "Settings could not be opened", Toast.LENGTH_SHORT).show();
        }
    }

    /** A specific settings screen, when this TV has a real one; the TV's settings otherwise. */
    private void settingsScreen(String action) {
        Intent i = new Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        ResolveInfo ri = getPackageManager().resolveActivity(i, 0);
        if (ri != null && ri.activityInfo != null && !Guard.AMAZON.equals(ri.activityInfo.packageName)) {
            try {
                startActivity(i);
                return;
            } catch (ActivityNotFoundException | SecurityException ignored) {
            }
        }
        tvSettings();
    }

    @Override
    public void networkSettings() {
        settingsScreen(Settings.ACTION_WIFI_SETTINGS);
    }

    @Override
    public void bluetoothSettings() {
        settingsScreen(Settings.ACTION_BLUETOOTH_SETTINGS);
    }

    @Override
    public void standBy() {
        if (cc != null) cc.close();
        try {
            startActivity(new Intent(this, StandByActivity.class));
        } catch (ActivityNotFoundException ignored) {
        }
    }

    /** Asks the background processes of every app to stop, then reports the memory won back. */
    @Override
    public void freeMemory() {
        final ActivityManager am = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
        final List<String> ids = new ArrayList<>();
        for (Catalog.Item it : apps) ids.add(it.id);
        net.execute(() -> {
            long before = memory(am).availMem;
            for (String id : ids) {
                try {
                    am.killBackgroundProcesses(id);
                } catch (RuntimeException ignored) {
                }
            }
            try {
                Thread.sleep(700);
            } catch (InterruptedException e) {
                return;
            }
            ActivityManager.MemoryInfo after = memory(am);
            final long freed = Math.max(0, after.availMem - before);
            final String free = gb(after.availMem);
            ui.post(() -> Toast.makeText(this, "Freed " + (freed / (1024 * 1024)) + " MB  ·  " + free + " free",
                    Toast.LENGTH_SHORT).show());
        });
    }

    private static ActivityManager.MemoryInfo memory(ActivityManager am) {
        ActivityManager.MemoryInfo m = new ActivityManager.MemoryInfo();
        am.getMemoryInfo(m);
        return m;
    }

    private static String gb(long bytes) {
        return String.format(Locale.getDefault(), "%.1f GB", bytes / (1024f * 1024f * 1024f));
    }

    /** The apps used most recently, newest first; null when the TV has not allowed Glass Home to see them. */
    private List<Catalog.Item> recents() {
        try {
            AppOpsManager ops = (AppOpsManager) getSystemService(APP_OPS_SERVICE);
            if (ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), getPackageName())
                    != AppOpsManager.MODE_ALLOWED) {
                return null;
            }
            UsageStatsManager usm = (UsageStatsManager) getSystemService(USAGE_STATS_SERVICE);
            long now = System.currentTimeMillis();
            List<UsageStats> stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_BEST, now - 3 * 86_400_000L, now);
            if (stats == null) return null;
            Collections.sort(stats, (a, b) -> Long.compare(b.getLastTimeUsed(), a.getLastTimeUsed()));
            List<Catalog.Item> out = new ArrayList<>();
            for (UsageStats s : stats) {
                if (s.getTotalTimeInForeground() <= 0) continue;
                Catalog.Item it = find(s.getPackageName());
                if (it != null && !out.contains(it)) out.add(it);
                if (out.size() == 4) break;
            }
            return out;
        } catch (RuntimeException e) {
            return null;
        }
    }

    // ---------------------------------------------------------------- Control Center

    private void controlCenter() {
        if (cc != null || setup != null) return;
        while (!sheets.isEmpty()) sheets.get(sheets.size() - 1).close();
        ActivityManager am = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
        String free = gb(memory(am).availMem) + " free";
        List<ControlCenter.Button> bs = new ArrayList<>();
        bs.add(new ControlCenter.Button(netGlyphKind, netLabel, netSub, this::networkSettings));
        bs.add(new ControlCenter.Button(Glyph.BLUETOOTH, "Bluetooth", "Remotes & devices", this::bluetoothSettings));
        bs.add(new ControlCenter.Button(Glyph.INPUT, "Inputs", "HDMI and sources", this::inputsMenu));
        bs.add(new ControlCenter.Button(Glyph.CONTRAST, "Appearance", Theme.label(prefs.theme()), this::themeMenu));
        bs.add(new ControlCenter.Button(Glyph.CLOCK, "StandBy", "Clock and calendar", this::standBy));
        bs.add(new ControlCenter.Button(Glyph.CHIP, "Free Memory", free, this::freeMemory));
        bs.add(new ControlCenter.Button(Glyph.GEAR, "Settings", "Glass Home", this::settingsMenu));
        bs.add(new ControlCenter.Button(Glyph.TV, "TV Settings", "Picture, sound, apps", this::tvSettings));
        int rw = Ui.dp(this, 76), rh = Math.round(rw * 0.6f);
        cc = new ControlCenter(this, this, prefs, bs, recents(), rw, rh);
        // Recent apps need their art; the grid has usually drawn it already.
        column.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        lens.hide();
        cc.show(root, () -> {
            cc = null;
            column.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
            if (pendingRecreate && sheets.isEmpty()) {
                pendingRecreate = false;
                recreate();
                return;
            }
            root.post(this::restoreFocus);
        });
    }

    // ---------------------------------------------------------------- menus

    private void open(Sheet s) {
        if (setup != null) return;
        Sheet below = sheets.isEmpty() ? null : sheets.get(sheets.size() - 1);
        if (below != null) below.cover(true);
        sheets.add(s);
        lens.hide();
        column.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        if (cc != null) cc.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        s.show(root, () -> {
            sheets.remove(s);
            if (!sheets.isEmpty()) {
                sheets.get(sheets.size() - 1).cover(false);
                return;
            }
            if (cc != null) {
                cc.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
                root.post(() -> {
                    if (cc != null && sheets.isEmpty()) {
                        View first = cc.findFocus();
                        if (first == null) cc.requestFocus(View.FOCUS_DOWN);
                    }
                });
                return;
            }
            column.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
            if (pendingRecreate) {
                pendingRecreate = false;
                ui.postDelayed(this::recreate, 150);
                return;
            }
            root.post(this::restoreFocus);
        });
    }

    @Override
    public void appMenu(Tile t) {
        final Catalog.Item it = t.item;
        if (it.isFolder()) {
            folderMenu(it);
            return;
        }
        final List<String> dock = prefs.dock();
        final boolean inDock = dock.contains(it.id);
        final String folder = prefs.folderOf(it.id);
        boolean inCc = cc != null && isInside(t, cc);
        Sheet s = new Sheet(this, it.label, inDock ? "In the dock" : folder != null ? "In " + prefs.folderName(folder) : null);
        s.add("Open", () -> {
            if (cc != null) cc.close();
            launch(it);
        });
        if (!inCc && folder == null) s.add("Move", () -> startMove(it.id));
        if (inDock) {
            s.add("Remove from Dock", () -> {
                List<String> d = prefs.dock();
                d.remove(it.id);
                prefs.setDock(d);
                lastFocusId = it.id;
                populate();
            });
        } else if (folder == null) {
            s.add(dock.size() >= dockN ? "Add to Dock (replaces the last one)" : "Add to Dock", () -> {
                List<String> d = prefs.dock();
                while (d.size() >= dockN) d.remove(d.size() - 1);
                d.add(it.id);
                prefs.setDock(d);
                lastFocusId = it.id;
                populate();
            });
        }
        if (!inDock) {
            if (folder == null) {
                s.add("Move to Folder…", () -> folderPicker(it));
            } else {
                s.add("Remove from Folder", () -> {
                    List<String> a = prefs.folderApps(folder);
                    a.remove(it.id);
                    prefs.setFolderApps(folder, a);
                    lastFocusId = it.id;
                    populate();
                });
            }
            s.add("Hide", () -> {
                List<String> h = prefs.hidden();
                if (!h.contains(it.id)) h.add(it.id);
                prefs.setHidden(h);
                lastFocusId = null;
                populate();
            });
        }
        s.add("Close App", () -> {
            try {
                ((ActivityManager) getSystemService(ACTIVITY_SERVICE)).killBackgroundProcesses(it.id);
                Toast.makeText(this, it.label + " closed", Toast.LENGTH_SHORT).show();
            } catch (RuntimeException e) {
                Toast.makeText(this, "Could not close " + it.label, Toast.LENGTH_SHORT).show();
            }
        });
        s.add("App Info", () -> {
            try {
                startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.fromParts("package", it.id, null)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (ActivityNotFoundException e) {
                Toast.makeText(this, "Not available on this TV", Toast.LENGTH_SHORT).show();
            }
        });
        open(s);
    }

    // ---------------------------------------------------------------- folders

    private void openFolder(final Catalog.Item folder) {
        Sheet s = new Sheet(this, folder.label, folder.members.size() == 1 ? "1 app" : folder.members.size() + " apps")
                .width(600);
        int cols = 4, gap = Ui.dp(this, 16);
        int w = (Ui.dp(this, 600) - Ui.dp(this, 44) - (cols - 1) * gap - Ui.dp(this, 8)) / cols;
        int h = Math.round(w * 0.6f);
        LinearLayout g = new LinearLayout(this);
        g.setOrientation(LinearLayout.VERTICAL);
        g.setClipChildren(false);
        g.setPadding(Ui.dp(this, 4), Ui.dp(this, 8), Ui.dp(this, 4), Ui.dp(this, 4));
        g.setClipToPadding(false);
        LinearLayout row = null;
        for (int i = 0; i < folder.members.size(); i++) {
            if (i % cols == 0) {
                row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setClipChildren(false);
                g.addView(row);
            }
            final Catalog.Item it = folder.members.get(i);
            final Tile t = new Tile(this, it, w, h, true);
            t.setOnClickListener(v -> {
                while (!sheets.isEmpty()) sheets.get(sheets.size() - 1).close();
                launch(it);
            });
            t.setOnLongClickListener(v -> {
                appMenu(t);
                return true;
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            if (i % cols > 0) lp.leftMargin = gap;
            row.addView(t, lp);
        }
        if (folder.members.isEmpty()) s.note("Empty. Press the Menu button (≡) on an app and choose \"Move to Folder\".");
        else s.view(g);
        s.tileMenu(this::appMenu);
        open(s);
    }

    private void folderMenu(final Catalog.Item folder) {
        Sheet s = new Sheet(this, folder.label, "Folder");
        s.add("Open", () -> openFolder(folder));
        s.add("Move", () -> startMove(folder.id));
        s.add("Rename…", () -> {
            Sheet n = new Sheet(this, "Rename Folder", null).atTop();
            n.input("Folder name", folder.label.toString(), EditorInfo.IME_ACTION_DONE, name -> {
                if (!name.isEmpty()) prefs.setFolderName(folder.id, name);
                lastFocusId = folder.id;
                n.close();
                populate();
            });
            open(n);
        });
        s.add("Delete Folder", () -> {
            prefs.deleteFolder(folder.id);
            lastFocusId = null;
            populate();
        });
        s.note("Deleting a folder keeps its apps: they go back to the grid.");
        open(s);
    }

    private void folderPicker(final Catalog.Item app) {
        Sheet s = new Sheet(this, "Move to Folder", app.label);
        for (final String f : prefs.folders()) {
            s.add(Glyph.FOLDER, prefs.folderName(f), () -> addToFolder(f, app));
        }
        s.add(Glyph.PLUS, "New Folder…", () -> {
            Sheet n = new Sheet(this, "New Folder", "Name it, then press the keyboard's Done key").atTop();
            n.input("Folder name", "", EditorInfo.IME_ACTION_DONE, name -> {
                String id = prefs.newFolder(name.isEmpty() ? "Folder" : name);
                n.close();
                addToFolder(id, app);
            });
            open(n);
        });
        open(s);
    }

    private void addToFolder(String folder, Catalog.Item app) {
        List<String> a = prefs.folderApps(folder);
        if (!a.contains(app.id)) a.add(app.id);
        prefs.setFolderApps(folder, a);
        // The folder takes the app's place in the grid.
        List<String> order = new ArrayList<>();
        for (Catalog.Item it : gridItems()) order.add(it.id);
        prefs.setOrder(order);
        lastFocusId = folder;
        populate();
    }

    // ---------------------------------------------------------------- weather menus

    private void weatherSheet() {
        if (!prefs.hasPlace()) {
            placeSearch();
            return;
        }
        Weather.Report r = report;
        String sub = r == null ? "Getting the weather…"
                : "Updated " + DateFormat.format(DateFormat.is24HourFormat(this) ? "H:mm" : "h:mm a", r.fetched)
                + "  ·  Open-Meteo";
        final Sheet s = new Sheet(this, prefs.place(), sub).width(660);
        // The actions sit in one row of chips under the forecast, so all of it fits on screen.
        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setClipChildren(false);
        actions.setPadding(0, Ui.dp(this, 6), 0, 0);
        actions.addView(chip(Glyph.PIN, "Change Location", v -> {
            s.close();
            placeSearch();
        }));
        actions.addView(chip(0, prefs.fahrenheit() ? "Show °C" : "Show °F", v -> {
            prefs.set("fahrenheit", !prefs.fahrenheit());
            report = null;
            s.close();
            refreshWeather(true);
        }));
        actions.addView(chip(Glyph.CLOSE, "Hide Weather", v -> {
            s.close();
            prefs.set("weather_on", false);
            updateWeatherPill();
            Toast.makeText(this, "Turn it back on in Settings > Weather", Toast.LENGTH_LONG).show();
        }));
        if (r != null) s.view(Forecast.view(this, r));
        s.view(actions);
        open(s);
    }

    /** A small lit button for a row of actions inside a menu. */
    private View chip(int glyph, String label, View.OnClickListener click) {
        Theme t = Theme.now;
        LinearLayout v = new LinearLayout(this);
        v.setOrientation(LinearLayout.HORIZONTAL);
        v.setGravity(Gravity.CENTER_VERTICAL);
        int h = Ui.dp(this, 16);
        v.setPadding(h, Ui.dp(this, 10), h + Ui.dp(this, 2), Ui.dp(this, 10));
        v.setFocusable(true);
        v.setClickable(true);
        GradientDrawable on = new GradientDrawable();
        on.setCornerRadius(Ui.dp(this, 22));
        on.setColor(t.rowFocus);
        on.setStroke(Ui.dp(this, 1), t.rowFocusRim);
        GradientDrawable off = new GradientDrawable();
        off.setCornerRadius(Ui.dp(this, 22));
        off.setColor(t.light ? 0x12000000 : 0x1AFFFFFF);
        android.graphics.drawable.StateListDrawable bg = new android.graphics.drawable.StateListDrawable();
        bg.addState(new int[] {android.R.attr.state_focused}, on);
        bg.addState(new int[] {}, off);
        v.setBackground(bg);
        v.setOutlineProvider(Ui.PILL);
        if (glyph != 0) {
            ImageView g = new ImageView(this);
            g.setImageDrawable(new Glyph(glyph, t.panelText));
            int gs = Ui.dp(this, 18);
            LinearLayout.LayoutParams gl = new LinearLayout.LayoutParams(gs, gs);
            gl.rightMargin = Ui.dp(this, 8);
            v.addView(g, gl);
        }
        TextView l = Ui.text(this, 16, t.panelText, Ui.MEDIUM);
        l.setText(label);
        v.addView(l);
        v.setOnClickListener(click);
        v.setOnFocusChangeListener((x, has) -> x.animate().scaleX(has ? 1.06f : 1f).scaleY(has ? 1.06f : 1f)
                .translationZ(has ? Ui.dp(this, 4) : 0f).setInterpolator(has ? Ui.POP : Ui.EASE).setDuration(200).start());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.rightMargin = Ui.dp(this, 12);
        v.setLayoutParams(lp);
        return v;
    }

    private void placeSearch() {
        final Sheet s = new Sheet(this, "Weather Location", "Type a city and press the keyboard's search key").atTop()
                .width(560);
        final LinearLayout results = new LinearLayout(this);
        results.setOrientation(LinearLayout.VERTICAL);
        s.input("City", "", EditorInfo.IME_ACTION_SEARCH, q -> {
            if (q.isEmpty()) return;
            results.removeAllViews();
            message(results, "Searching…");
            searchPlaces(q, (places, error) -> {
                results.removeAllViews();
                if (error != null) {
                    message(results, error);
                    return;
                }
                if (places.isEmpty()) {
                    message(results, "Couldn't find " + q + ". Check the spelling or try a nearby city.");
                    return;
                }
                for (int i = 0; i < Math.min(5, places.size()); i++) {
                    final Weather.Place p = places.get(i);
                    TextView row = Ui.text(this, 18, Theme.now.panelText, Ui.REGULAR);
                    row.setText(p.area.isEmpty() ? p.name : p.name + "  ·  " + p.area);
                    int h = Ui.dp(this, 16);
                    row.setPadding(h, Ui.dp(this, 11), h, Ui.dp(this, 11));
                    row.setFocusable(true);
                    row.setClickable(true);
                    row.setBackground(Sheet.rowBackground(this));
                    row.setOnClickListener(v -> {
                        prefs.setPlace(p.name, p.lat, p.lon);
                        prefs.set("weather_on", true);
                        report = null;
                        s.close();
                        refreshWeather(true);
                    });
                    results.addView(row, new LinearLayout.LayoutParams(-1, -2));
                }
                results.getChildAt(0).requestFocus();
            });
        });
        s.view(results);
        if (prefs.hasPlace()) s.add("Turn Off the Weather", () -> {
            prefs.set("weather_on", false);
            updateWeatherPill();
        });
        open(s);
    }

    private void message(LinearLayout into, String text) {
        TextView v = Ui.text(this, 15, Theme.now.panelMuted, Ui.REGULAR);
        v.setSingleLine(false);
        v.setText(text);
        int h = Ui.dp(this, 16);
        v.setPadding(h, Ui.dp(this, 10), h, Ui.dp(this, 10));
        into.addView(v);
    }

    @Override
    public void searchPlaces(final String query, final Setup.Places done) {
        net.execute(() -> {
            try {
                final List<Weather.Place> found = Weather.search(query);
                ui.post(() -> done.found(found, null));
            } catch (IOException | RuntimeException e) {
                ui.post(() -> done.found(null, "Couldn't reach the weather service. Check the TV's internet connection."));
            }
        });
    }

    // ---------------------------------------------------------------- settings

    @Override
    public void settingsMenu() {
        Sheet s = new Sheet(this, "Settings", "Glass Home " + version()).width(540);
        s.more("Appearance", () -> Theme.label(prefs.theme()), this::themeMenu);
        s.option("App Icons", () -> Prefs.ICONS_BANNERS.equals(prefs.icons()) ? "TV Banners" : "tvOS", () -> {
            prefs.set("icons", Prefs.ICONS_BANNERS.equals(prefs.icons()) ? Prefs.ICONS_TVOS : Prefs.ICONS_BANNERS);
            pendingRecreate = true;
        });
        s.option("Focus Effect", () -> Prefs.FOCUS_LENS.equals(prefs.focus()) ? "Glass Lens" : "Lift", () -> {
            prefs.set("focus", Prefs.FOCUS_LENS.equals(prefs.focus()) ? Prefs.FOCUS_LIFT : Prefs.FOCUS_LENS);
            pendingRecreate = true;
        });
        s.option("Stage", () -> prefs.stage() ? "On" : "Off", () -> {
            prefs.set("stage", !prefs.stage());
            pendingRecreate = true;
        });
        s.option("Dock", () -> prefs.dockSize() + " apps", () -> {
            prefs.set("dock_size", prefs.dockSize() >= 6 ? 4 : prefs.dockSize() + 1);
            pendingRecreate = true;
        });
        s.option("Apps per Row", () -> String.valueOf(prefs.perRow()), () -> {
            prefs.set("per_row", prefs.perRow() >= 7 ? 5 : prefs.perRow() + 1);
            pendingRecreate = true;
        });
        s.option("Status Bar", () -> Prefs.BAR_MINIMAL.equals(prefs.bar()) ? "Minimal" : "Full", () -> {
            prefs.set("bar", Prefs.BAR_MINIMAL.equals(prefs.bar()) ? Prefs.BAR_FULL : Prefs.BAR_MINIMAL);
            pendingRecreate = true;
        });
        s.more("Weather", () -> !prefs.weatherOn() ? "Off" : prefs.hasPlace() ? prefs.place() : "Not set",
                this::weatherSettings);
        s.more("Profile", prefs::name, this::profileMenu);
        s.more("StandBy", () -> StandBy.SPLIT.equals(prefs.standbyStyle()) ? "Split" : "Classic", this::standbyMenu);
        if (!prefs.hidden().isEmpty()) s.more("Hidden Apps", () -> String.valueOf(prefs.hidden().size()), this::hiddenMenu);
        s.add("Run Setup Again", this::showSetup);
        s.add("Reset Layout", () -> {
            prefs.resetLayout();
            prefs.setDock(Catalog.defaultDock(apps, dockN));
            lastFocusId = null;
            populate();
        });
        s.add("Open Amazon's Home Screen", () -> Guard.amazon(this));
        s.note("No adverts and no tracking. The weather comes from Open-Meteo, and only for the city you choose.");
        open(s);
    }

    private String version() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (PackageManager.NameNotFoundException e) {
            return "";
        }
    }

    @Override
    public void themeMenu() {
        Sheet s = new Sheet(this, "Appearance", "Auto is Light from sunrise to sunset and Dark at night.");
        for (final String id : Theme.CHOICES) {
            s.check(Theme.label(id), id.equals(prefs.theme()), () -> {
                if (id.equals(prefs.theme())) return;
                prefs.setTheme(id);
                ui.postDelayed(this::recreate, 160);
            });
        }
        open(s);
    }

    private void weatherSettings() {
        Sheet s = new Sheet(this, "Weather", "From Open-Meteo: open data, no account, no tracking.");
        s.option("Show Weather", () -> prefs.weatherOn() ? "On" : "Off", () -> {
            prefs.set("weather_on", !prefs.weatherOn());
            updateWeatherPill();
            refreshWeather(false);
        });
        s.more("Location", () -> prefs.hasPlace() ? prefs.place() : "Not set", this::placeSearch);
        s.option("Units", () -> prefs.fahrenheit() ? "Fahrenheit (°F)" : "Celsius (°C)", () -> {
            prefs.set("fahrenheit", !prefs.fahrenheit());
            report = null;
            refreshWeather(true);
        });
        open(s);
    }

    @Override
    public void profileMenu() {
        Sheet s = new Sheet(this, "Profile", "Shown next to the clock and in Control Center").width(560);
        s.more("Name", prefs::name, () -> {
            Sheet n = new Sheet(this, "Profile Name", null).atTop();
            n.input("Name", "Home".equals(prefs.name()) ? "" : prefs.name(), EditorInfo.IME_ACTION_DONE, name -> {
                prefs.set("name", name.isEmpty() ? "Home" : name);
                n.close();
            });
            open(n);
        });
        // The pictures, two rows of six; OK picks one.
        LinearLayout g = new LinearLayout(this);
        g.setOrientation(LinearLayout.VERTICAL);
        g.setClipChildren(false);
        g.setPadding(0, Ui.dp(this, 10), 0, Ui.dp(this, 6));
        g.setClipToPadding(false);
        LinearLayout row = null;
        for (int i = 0; i < Prefs.AVATARS.length; i++) {
            if (i % 6 == 0) {
                row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_HORIZONTAL);
                row.setClipChildren(false);
                g.addView(row, new LinearLayout.LayoutParams(-1, -2));
            }
            final int index = i;
            FrameLayout f = new FrameLayout(this);
            f.setFocusable(true);
            f.setClickable(true);
            f.setBackground(Sheet.rowBackground(this));
            int in = Ui.dp(this, 6);
            f.setPadding(in, in, in, in);
            f.addView(new Avatar(this, i), new FrameLayout.LayoutParams(-1, -1));
            f.setOnFocusChangeListener((v, has) -> v.animate().scaleX(has ? 1.12f : 1f).scaleY(has ? 1.12f : 1f)
                    .setInterpolator(has ? Ui.POP : Ui.EASE).setDuration(200).start());
            f.setOnClickListener(v -> {
                prefs.set("avatar", index);
                barAvatar.set(index);
                s.close();
                if (cc != null) {
                    cc.close();
                    ui.postDelayed(this::controlCenter, 300);
                }
            });
            int sz = Ui.dp(this, 64);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(sz, sz);
            lp.setMargins(Ui.dp(this, 6), Ui.dp(this, 4), Ui.dp(this, 6), Ui.dp(this, 4));
            row.addView(f, lp);
        }
        s.view(g);
        open(s);
    }

    private void standbyMenu() {
        Sheet s = new Sheet(this, "StandBy", "A large clock, the date and the weather, on black, when the TV is idle.");
        s.option("Style", () -> StandBy.SPLIT.equals(prefs.standbyStyle()) ? "Split: clock and calendar" : "Classic",
                () -> prefs.set("standby_style", StandBy.SPLIT.equals(prefs.standbyStyle()) ? StandBy.CLASSIC : StandBy.SPLIT));
        s.option("Colour", () -> StandBy.COLOUR_NAMES[prefs.standbyColour()],
                () -> prefs.set("standby_colour", (prefs.standbyColour() + 1) % StandBy.COLOURS.length));
        s.add(Glyph.CLOCK, "Preview StandBy", this::standBy);
        s.add("Use as the TV's Screensaver", () -> settingsScreen(Settings.ACTION_DREAM_SETTINGS));
        s.note("The Windows installer can also make StandBy the screensaver for you.");
        open(s);
    }

    private void hiddenMenu() {
        Sheet s = new Sheet(this, "Hidden Apps", "Choose one to show it again");
        for (final String id : prefs.hidden()) {
            Catalog.Item it = find(id);
            s.add(it != null ? it.label : id, () -> {
                List<String> h = prefs.hidden();
                h.remove(id);
                prefs.setHidden(h);
                lastFocusId = id;
                populate();
            });
        }
        open(s);
    }

    @Override
    public void inputsMenu() {
        List<Catalog.Item> inputs = Catalog.inputs(this);
        Sheet s = new Sheet(this, "Inputs", "HDMI and other sources on this TV");
        for (final Catalog.Item in : inputs) {
            s.add(Glyph.INPUT, in.label, () -> {
                if (cc != null) cc.close();
                startActivity(Input.intent(this, in));
            });
        }
        if (inputs.isEmpty()) s.note("This TV reports no HDMI or other inputs.");
        open(s);
    }

    // ---------------------------------------------------------------- setup

    private void showSetup() {
        if (setup != null) return;
        while (!sheets.isEmpty()) sheets.get(sheets.size() - 1).close();
        if (cc != null) cc.close();
        column.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        setup = new Setup(this, this, prefs);
        setup.show(root);
    }

    private void removeSetup() {
        if (setup == null) return;
        root.removeView(setup);
        setup = null;
        column.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        root.post(this::restoreFocus);
    }

    @Override
    public void finishSetup(String theme, int avatar, String name, Weather.Place place) {
        prefs.set("setup_done", true);
        prefs.set("avatar", avatar);
        prefs.set("name", name == null || name.isEmpty() ? "Home" : name);
        if (place != null) {
            prefs.setPlace(place.name, place.lat, place.lon);
            prefs.set("weather_on", true);
            report = null;
        }
        boolean newLook = !theme.equals(prefs.theme());
        prefs.setTheme(theme);
        barAvatar.set(prefs.avatar());
        if (newLook) {
            recreate();
            return;
        }
        setup.animate().alpha(0f).setDuration(220).withEndAction(this::removeSetup).start();
        updateWeatherPill();
        refreshWeather(place != null);
    }
}
