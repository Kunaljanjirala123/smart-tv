package tv.glasshome;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.ActivityOptions;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Rect;
import android.graphics.RectF;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.format.DateFormat;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The home screen. Page one: clock and controls at the top, the focused app's name and the glass
 * dock at the bottom. Press Down from the dock and the page glides up to every other app.
 */
public final class Home extends Activity {
    private static final int COLS = 5;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Map<String, android.graphics.Bitmap> artCache = new HashMap<>();
    private final Map<String, Integer> colorCache = new HashMap<>();
    private final List<Tile> tiles = new ArrayList<>();
    private final RectF rect = new RectF();
    private final Rect tmp = new Rect();

    private Prefs prefs;
    private List<Catalog.Item> apps = new ArrayList<>();
    private FrameLayout root, content;
    private Pager pager;
    private LinearLayout column, dockRow, grid;
    private Glass.Frame dockBand;
    private TextView clock, date, hero, appsHeader, network;
    private Lens lens;
    private Sheet sheet;
    private ValueAnimator scrollAnim;
    private int scrollTarget = -1;
    private String lastFocusId;
    private int pageH, artW, artH, dockW, dockH, gap, side;
    private boolean resumed;

    private final BroadcastReceiver time = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            tick();
        }
    };
    private final BroadcastReceiver packages = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            ui.removeCallbacks(reload);
            ui.postDelayed(reload, 400);
        }
    };
    private final Runnable reload = this::reload;
    private ConnectivityManager.NetworkCallback netCallback;

    /** A ScrollView that only moves when told to: no jumps of its own on focus changes. */
    static final class Pager extends ScrollView {
        Pager(Context c) {
            super(c);
            setVerticalScrollBarEnabled(false);
            setOverScrollMode(OVER_SCROLL_NEVER);
            setSmoothScrollingEnabled(false);
        }

        @Override
        protected int computeScrollDeltaToGetChildRectOnScreen(Rect r) {
            return 0;
        }

        @Override
        public boolean executeKeyEvent(KeyEvent e) {
            return false;
        }

        @Override
        protected void onScrollChanged(int l, int t, int ol, int ot) {
            super.onScrollChanged(l, t, ol, ot);
            Glass.invalidateAll();
        }
    }

    // ---------------------------------------------------------------- lifecycle

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = new Prefs(this);
        Backdrop b = Backdrop.get(this);
        pageH = b.screenH;
        side = Ui.dp(this, 48);
        gap = Ui.dp(this, 16);
        int usable = b.screenW - 2 * side;
        artW = (usable - (COLS - 1) * gap) / COLS;
        artH = Math.round(artW * 9f / 16f);
        dockW = Math.round(artW * 0.93f);
        dockH = Math.round(dockW * 9f / 16f);
        build();
        reload();
        IntentFilter pf = new IntentFilter();
        pf.addAction(Intent.ACTION_PACKAGE_ADDED);
        pf.addAction(Intent.ACTION_PACKAGE_REMOVED);
        pf.addAction(Intent.ACTION_PACKAGE_CHANGED);
        pf.addDataScheme("package");
        registerReceiver(packages, pf);
        watchNetwork();
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        Guard.arrived(this);
        IntentFilter f = new IntentFilter(Intent.ACTION_TIME_TICK);
        f.addAction(Intent.ACTION_TIME_CHANGED);
        f.addAction(Intent.ACTION_TIMEZONE_CHANGED);
        registerReceiver(time, f);
        tick();
        updateNetwork();
        restoreFocus();
    }

    @Override
    public void onEnterAnimationComplete() {
        Guard.shown();
    }

    @Override
    protected void onPause() {
        super.onPause();
        resumed = false;
        unregisterReceiver(time);
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
        worker.shutdownNow();
        super.onDestroy();
    }

    /** HOME while already home: back to the first dock app, menus closed. */
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (sheet != null) sheet.close();
        focusDock(0);
    }

    @Override
    public void onBackPressed() {
        if (sheet != null) {
            sheet.close();
            return;
        }
        focusDock(0);
    }

    @Override
    public boolean onKeyUp(int code, KeyEvent e) {
        if (code == KeyEvent.KEYCODE_MENU && sheet == null) {
            View f = getCurrentFocus();
            if (f instanceof Tile) appMenu((Tile) f);
            else editMenu();
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

    private void build() {
        root = new FrameLayout(this);
        root.addView(new Backdrop.Wallpaper(this), new FrameLayout.LayoutParams(-1, -1));

        pager = new Pager(this);
        content = new FrameLayout(this);
        content.setClipChildren(false);
        column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setClipChildren(false);
        content.addView(column, new FrameLayout.LayoutParams(-1, -2));
        lens = new Lens(this);
        content.addView(lens, new FrameLayout.LayoutParams(-1, -1));
        pager.addView(content, new ScrollView.LayoutParams(-1, -2));
        root.addView(pager, new FrameLayout.LayoutParams(-1, -1));

        // Page one ------------------------------------------------------------
        FrameLayout page = new FrameLayout(this);
        page.setClipChildren(false);
        column.addView(page, new LinearLayout.LayoutParams(-1, pageH));

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setPadding(side, Ui.dp(this, 34), side, 0);
        top.setClipChildren(false);
        LinearLayout when = new LinearLayout(this);
        when.setOrientation(LinearLayout.VERTICAL);
        clock = Ui.text(this, 78, Ui.TEXT, Ui.THIN);
        clock.setShadowLayer(Ui.dp(this, 18), 0, 0, 0x55000000);
        date = Ui.text(this, 17, Ui.SOFT, Ui.LIGHT);
        date.setPadding(Ui.dp(this, 4), Ui.dp(this, 6), 0, 0);
        when.addView(clock);
        when.addView(date);
        top.addView(when, new LinearLayout.LayoutParams(0, -2, 1f));

        LinearLayout pills = new LinearLayout(this);
        pills.setOrientation(LinearLayout.HORIZONTAL);
        pills.setClipChildren(false);
        pills.addView(pill("Inputs", v -> inputsMenu()));
        network = pillText();
        pills.addView(pill(network, v -> openSettings()));
        pills.addView(pill("Settings", v -> openSettings()));
        pills.addView(pill("Edit", v -> editMenu()));
        top.addView(pills, new LinearLayout.LayoutParams(-2, -2));
        page.addView(top, new FrameLayout.LayoutParams(-1, -2, Gravity.TOP));

        LinearLayout bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.VERTICAL);
        bottom.setClipChildren(false);
        bottom.setPadding(side, 0, side, Ui.dp(this, 30));
        hero = Ui.text(this, 34, Ui.TEXT, Ui.LIGHT);
        hero.setPadding(Ui.dp(this, 10), 0, 0, Ui.dp(this, 16));
        hero.setAlpha(0f);
        bottom.addView(hero);
        dockBand = new Glass.Frame(this, 30);
        int bp = Ui.dp(this, 16);
        dockBand.setPadding(bp, bp, bp, bp);
        dockRow = new LinearLayout(this);
        dockRow.setOrientation(LinearLayout.HORIZONTAL);
        dockRow.setClipChildren(false);
        dockRow.setGravity(Gravity.CENTER);
        dockBand.addView(dockRow, new FrameLayout.LayoutParams(-1, -2));
        bottom.addView(dockBand, new LinearLayout.LayoutParams(-1, -2));
        page.addView(bottom, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));

        // Everything else -------------------------------------------------------
        appsHeader = Ui.text(this, 22, Ui.SOFT, Ui.LIGHT);
        appsHeader.setText("All apps");
        appsHeader.setPadding(side + Ui.dp(this, 6), Ui.dp(this, 40), side, Ui.dp(this, 18));
        column.addView(appsHeader);
        grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.VERTICAL);
        grid.setClipChildren(false);
        grid.setPadding(side, 0, side, Ui.dp(this, 80));
        column.addView(grid, new LinearLayout.LayoutParams(-1, -2));

        root.getViewTreeObserver().addOnGlobalFocusChangeListener((old, now) -> focused(now));
        setContentView(root);
    }

    private TextView pillText() {
        TextView t = Ui.text(this, 15, Ui.TEXT, Ui.REGULAR);
        t.setGravity(Gravity.CENTER);
        return t;
    }

    private View pill(CharSequence label, View.OnClickListener click) {
        TextView t = pillText();
        t.setText(label);
        return pill(t, click);
    }

    private View pill(TextView t, View.OnClickListener click) {
        Glass.Frame f = new Glass.Frame(this, 22);
        f.setPadding(Ui.dp(this, 20), Ui.dp(this, 10), Ui.dp(this, 20), Ui.dp(this, 10));
        f.addView(t, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
        f.setFocusable(true);
        f.setClickable(true);
        f.setOnClickListener(click);
        f.setOnFocusChangeListener((v, has) -> v.animate().scaleX(has ? 1.08f : 1f).scaleY(has ? 1.08f : 1f)
                .setInterpolator(has ? new OvershootInterpolator(1.5f) : new DecelerateInterpolator())
                .setDuration(240).start());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, Ui.dp(this, 42));
        lp.leftMargin = Ui.dp(this, 12);
        f.setLayoutParams(lp);
        return f;
    }

    // ---------------------------------------------------------------- content

    private void reload() {
        List<Catalog.Item> fresh = Catalog.apps(this);
        for (Catalog.Item it : fresh) {
            android.graphics.Bitmap b = artCache.get(it.id);
            if (b != null) {
                it.art = b;
                Integer col = colorCache.get(it.id);
                if (col != null) it.color = col;
            }
        }
        apps = fresh;
        if (!prefs.hasDock()) prefs.setDock(Catalog.defaultDock(apps));
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
            if (it != null) out.add(it);
        }
        return out;
    }

    /** Grid apps: not in the dock, not hidden, in the household's order, new apps at the end. */
    private List<Catalog.Item> gridItems() {
        List<String> dock = prefs.dock(), hidden = prefs.hidden(), order = prefs.order();
        List<Catalog.Item> out = new ArrayList<>();
        for (String id : order) {
            Catalog.Item it = find(id);
            if (it != null && !dock.contains(id) && !hidden.contains(id) && !out.contains(it)) out.add(it);
        }
        for (Catalog.Item it : apps) {
            if (!dock.contains(it.id) && !hidden.contains(it.id) && !out.contains(it)) out.add(it);
        }
        return out;
    }

    private void populate() {
        String keep = lastFocusId;
        tiles.clear();
        dockRow.removeAllViews();
        grid.removeAllViews();
        List<Catalog.Item> dock = dockItems();
        for (int i = 0; i < dock.size(); i++) {
            Tile t = tile(dock.get(i), dockW, dockH, false);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            if (i > 0) lp.leftMargin = Ui.dp(this, 18);
            dockRow.addView(t, lp);
        }
        if (dock.isEmpty()) {
            TextView hint = Ui.text(this, 16, Ui.MUTED, Ui.REGULAR);
            hint.setText("Press the Menu button (≡) on any app below and choose \"Add to dock\"");
            hint.setPadding(0, dockH / 3, 0, dockH / 3);
            dockRow.addView(hint);
        }
        List<Catalog.Item> rest = gridItems();
        LinearLayout row = null;
        for (int i = 0; i < rest.size(); i++) {
            if (i % COLS == 0) {
                row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setClipChildren(false);
                LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(-1, -2);
                if (i > 0) rp.topMargin = Ui.dp(this, 10);
                grid.addView(row, rp);
            }
            Tile t = tile(rest.get(i), artW, artH, true);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            if (i % COLS > 0) lp.leftMargin = gap;
            row.addView(t, lp);
        }
        appsHeader.setVisibility(rest.isEmpty() ? View.GONE : View.VISIBLE);
        lastFocusId = keep;
        root.post(this::restoreFocus);
    }

    private Tile tile(Catalog.Item it, int w, int h, boolean label) {
        Tile t = new Tile(this, it, w, h, label);
        t.setOnClickListener(v -> launch(it));
        t.setOnLongClickListener(v -> {
            appMenu(t);
            return true;
        });
        tiles.add(t);
        return t;
    }

    private void loadArt() {
        final List<Catalog.Item> todo = new ArrayList<>();
        for (Catalog.Item it : apps) if (it.art == null) todo.add(it);
        if (todo.isEmpty()) return;
        final android.content.pm.PackageManager pm = getPackageManager();
        // Dock apps first, so the first screen fills in before anything else.
        final List<String> dock = prefs.dock();
        java.util.Collections.sort(todo, (a, b) -> Boolean.compare(!dock.contains(a.id), !dock.contains(b.id)));
        worker.execute(() -> {
            for (Catalog.Item it : todo) {
                if (Thread.currentThread().isInterrupted()) return;
                try {
                    Catalog.art(pm, it, artW, artH);
                } catch (RuntimeException | OutOfMemoryError ignored) {
                }
                ui.post(() -> {
                    if (it.art != null) {
                        artCache.put(it.id, it.art);
                        colorCache.put(it.id, it.color);
                    }
                    for (Tile t : tiles) if (t.item.id.equals(it.id)) t.invalidate();
                    View f = getCurrentFocus();
                    if (f instanceof Tile && ((Tile) f).item == it) showHero((Tile) f);
                });
            }
        });
    }

    // ---------------------------------------------------------------- focus and motion

    private void focused(View now) {
        if (now == null || sheet != null && isInside(now, sheet)) {
            if (now != null) lens.hide();
            return;
        }
        if (now instanceof Tile) {
            Tile t = (Tile) now;
            lastFocusId = t.item.id;
            float s = Tile.FOCUS_SCALE, pad = Ui.dp(this, 3);
            float cx = t.artW / 2f, cy = t.artH / 2f;
            float hw = t.artW * s / 2f + pad, hh = t.artH * s / 2f + pad;
            tmp.set(0, 0, 0, 0);
            content.offsetDescendantRectToMyCoords(t, tmp);
            rect.set(tmp.left + cx - hw, tmp.top + cy - hh, tmp.left + cx + hw, tmp.top + cy + hh);
            lens.moveTo(rect);
            boolean inDock = t.getParent() == dockRow;
            if (inDock) showHero(t);
            else hero.animate().alpha(0f).setDuration(150).start();
            if (inDock) scrollTo(0);
            else scrollToShow(tmp.top, tmp.top + t.getHeight());
        } else if (now.getParent() != null && isInside(now, column)) {
            // A pill at the top.
            lens.hide();
            hero.animate().alpha(0f).setDuration(150).start();
            scrollTo(0);
        }
    }

    private static boolean isInside(View v, View container) {
        for (Object p = v; p instanceof View; p = ((View) p).getParent()) if (p == container) return true;
        return false;
    }

    private void showHero(Tile t) {
        CharSequence name = t.item.label;
        int glow = Ui.glow(t.item.color);
        hero.setShadowLayer(Ui.dp(this, 26), 0, 0, (0xCC << 24) | (glow & 0xFFFFFF));
        if (!name.toString().contentEquals(hero.getText()) || hero.getAlpha() < 1f) {
            hero.animate().cancel();
            hero.setText(name);
            hero.setTranslationX(-Ui.dp(this, 10));
            hero.animate().alpha(1f).translationX(0).setDuration(220)
                    .setInterpolator(new DecelerateInterpolator()).start();
        }
    }

    /** Keeps the band from {@code top} to {@code bottom} (content coordinates) comfortably on screen. */
    private void scrollToShow(int top, int bottom) {
        int view = pager.getHeight() > 0 ? pager.getHeight() : pageH;
        int min = appsHeader.getTop() - Ui.dp(this, 26);
        int target = Math.max(min, bottom + Ui.dp(this, 70) - view);
        if (top - target < Ui.dp(this, 70)) target = top - Ui.dp(this, 70);
        int max = Math.max(0, content.getHeight() - view);
        scrollTo(Math.max(0, Math.min(target, max)));
    }

    private void scrollTo(int y) {
        if (y == scrollTarget && scrollAnim != null && scrollAnim.isRunning()) return;
        if (y == pager.getScrollY()) {
            scrollTarget = y;
            return;
        }
        scrollTarget = y;
        if (scrollAnim != null) scrollAnim.cancel();
        scrollAnim = ValueAnimator.ofInt(pager.getScrollY(), y);
        scrollAnim.setDuration(Math.abs(y - pager.getScrollY()) > pageH / 2 ? 520 : 360);
        scrollAnim.setInterpolator(new DecelerateInterpolator(2.2f));
        scrollAnim.addUpdateListener(a -> pager.scrollTo(0, (int) a.getAnimatedValue()));
        scrollAnim.start();
    }

    private void restoreFocus() {
        if (sheet != null) return;
        if (lastFocusId != null) {
            for (Tile t : tiles) {
                if (t.item.id.equals(lastFocusId)) {
                    t.requestFocus();
                    return;
                }
            }
        }
        focusDock(0);
    }

    private void focusDock(int i) {
        for (int k = 0; k < dockRow.getChildCount(); k++) {
            View v = dockRow.getChildAt(Math.min(i, dockRow.getChildCount() - 1));
            if (v instanceof Tile) {
                v.requestFocus();
                scrollTo(0);
                return;
            }
        }
        if (!tiles.isEmpty()) tiles.get(0).requestFocus();
    }

    // ---------------------------------------------------------------- clock and network

    private void tick() {
        Calendar now = Calendar.getInstance();
        clock.setText(DateFormat.format(DateFormat.is24HourFormat(this) ? "H:mm" : "h:mm", now));
        int h = now.get(Calendar.HOUR_OF_DAY);
        String hello = h < 5 ? "Good night" : h < 12 ? "Good morning" : h < 17 ? "Good afternoon" : "Good evening";
        date.setText(hello + "  ·  " + DateFormat.format("EEEE d MMMM", now));
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
        String label = "Offline";
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
            NetworkCapabilities caps = cm.getNetworkCapabilities(cm.getActiveNetwork());
            if (caps != null) {
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) label = "Ethernet";
                else if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) label = "Wi-Fi";
                else label = "Online";
                if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) label += " · no internet";
            }
        } catch (RuntimeException ignored) {
        }
        network.setText(label);
    }

    // ---------------------------------------------------------------- opening things

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
    private void openSettings() {
        Intent tv = new Intent(Intent.ACTION_MAIN).addCategory("android.intent.category.LEANBACK_SETTINGS")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            if (tv.resolveActivity(getPackageManager()) != null) startActivity(tv);
            else startActivity(new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "Settings could not be opened", Toast.LENGTH_SHORT).show();
        }
    }

    // ---------------------------------------------------------------- menus

    private void open(Sheet s) {
        if (sheet != null) return;
        sheet = s;
        lens.hide();
        column.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        s.show(root, () -> {
            sheet = null;
            column.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
            root.post(this::restoreFocus);
        });
    }

    private void appMenu(Tile t) {
        final Catalog.Item it = t.item;
        final List<String> dock = prefs.dock();
        final boolean inDock = dock.contains(it.id);
        Sheet s = new Sheet(this, it.label, inDock ? "In the dock" : null);
        s.add("Open", () -> launch(it));
        if (inDock) {
            s.add("Move left", () -> moveInDock(it.id, -1));
            s.add("Move right", () -> moveInDock(it.id, 1));
            s.add("Remove from dock", () -> {
                List<String> d = prefs.dock();
                d.remove(it.id);
                prefs.setDock(d);
                lastFocusId = it.id;
                populate();
            });
        } else {
            s.add(dock.size() >= Prefs.DOCK_MAX ? "Add to dock (replaces the last one)" : "Add to dock", () -> {
                List<String> d = prefs.dock();
                while (d.size() >= Prefs.DOCK_MAX) d.remove(d.size() - 1);
                d.add(it.id);
                prefs.setDock(d);
                lastFocusId = it.id;
                populate();
            });
            s.add("Move earlier", () -> moveInGrid(it.id, -1));
            s.add("Move later", () -> moveInGrid(it.id, 1));
            s.add("Hide this app", () -> {
                List<String> h = prefs.hidden();
                if (!h.contains(it.id)) h.add(it.id);
                prefs.setHidden(h);
                lastFocusId = null;
                populate();
            });
        }
        s.add("App info", () -> {
            try {
                startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.fromParts("package", it.id, null)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (ActivityNotFoundException e) {
                Toast.makeText(this, "Not available on this TV", Toast.LENGTH_SHORT).show();
            }
        });
        open(s);
    }

    private void moveInDock(String id, int by) {
        List<String> d = prefs.dock();
        int i = d.indexOf(id), j = i + by;
        if (i < 0 || j < 0 || j >= d.size()) return;
        d.remove(i);
        d.add(j, id);
        prefs.setDock(d);
        lastFocusId = id;
        populate();
    }

    private void moveInGrid(String id, int by) {
        List<String> ids = new ArrayList<>();
        for (Catalog.Item it : gridItems()) ids.add(it.id);
        int i = ids.indexOf(id), j = i + by;
        if (i < 0 || j < 0 || j >= ids.size()) return;
        ids.remove(i);
        ids.add(j, id);
        prefs.setOrder(ids);
        lastFocusId = id;
        populate();
    }

    private void inputsMenu() {
        List<Catalog.Item> inputs = Catalog.inputs(this);
        Sheet s = new Sheet(this, "Inputs", "HDMI and other sources on this TV");
        for (final Catalog.Item in : inputs) {
            s.add(in.label, () -> startActivity(Input.intent(this, in)));
        }
        if (inputs.isEmpty()) s.note("This TV reports no HDMI or other inputs.");
        open(s);
    }

    private void editMenu() {
        final List<String> hidden = prefs.hidden();
        Sheet s = new Sheet(this, "Edit home", "Tip: press the Menu button (≡) on any app to pin, move or hide it");
        if (!hidden.isEmpty()) s.add("Show a hidden app (" + hidden.size() + ")", this::hiddenMenu);
        s.add("Reset layout", () -> {
            prefs.reset();
            prefs.setDock(Catalog.defaultDock(apps));
            lastFocusId = null;
            populate();
        });
        s.add("Open Amazon's home screen", () -> Guard.amazon(this));
        s.note("Glass Home has no internet access: no ads, no tracking, nothing downloaded.");
        open(s);
    }

    private void hiddenMenu() {
        Sheet s = new Sheet(this, "Hidden apps", "Choose one to show it again");
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
        // The edit sheet is still closing; open this one once it has gone.
        ui.postDelayed(() -> open(s), 180);
    }
}
