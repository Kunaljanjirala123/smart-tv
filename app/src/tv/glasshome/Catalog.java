package tv.glasshome;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.AdaptiveIconDrawable;
import android.graphics.drawable.Drawable;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.text.TextUtils;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Installed apps and TV inputs, and the artwork for each app: tiles, folders and the stage. */
final class Catalog {
    static final int DEFAULT_BRAND = 0xFF3A4A7A;

    /** One app (id = package name), one folder (id = "folder:..."), or one TV input (id = input id). */
    static final class Item {
        final String id;
        final CharSequence label;
        final Intent intent;
        ActivityInfo info;
        /** Apps in this folder; null for an app. */
        List<Item> members;
        volatile Bitmap art;
        volatile int brand = DEFAULT_BRAND;

        Item(String id, CharSequence label, Intent intent) {
            this.id = id;
            this.label = label;
            this.intent = intent;
        }

        boolean isFolder() {
            return members != null;
        }
    }

    /** What the stage shows for an app: its banner (or icon tile) and a small, blurred copy for the glow. */
    static final class StageArt {
        final Bitmap art, glow;

        StageArt(Bitmap art, Bitmap glow) {
            this.art = art;
            this.glow = glow;
        }
    }

    private Catalog() {}

    /** Every launchable app, TV apps first, sorted by name. */
    static List<Item> apps(Context c) {
        PackageManager pm = c.getPackageManager();
        Map<String, Item> found = new LinkedHashMap<>();
        collect(pm, found, Intent.CATEGORY_LEANBACK_LAUNCHER, c.getPackageName());
        collect(pm, found, Intent.CATEGORY_LAUNCHER, c.getPackageName());
        List<Item> out = new ArrayList<>(found.values());
        final Collator collator = Collator.getInstance();
        Collections.sort(out, (a, b) -> collator.compare(a.label.toString(), b.label.toString()));
        return out;
    }

    private static void collect(PackageManager pm, Map<String, Item> found, String category, String self) {
        Intent query = new Intent(Intent.ACTION_MAIN).addCategory(category);
        for (ResolveInfo ri : pm.queryIntentActivities(query, 0)) {
            ActivityInfo ai = ri.activityInfo;
            if (ai.packageName.equals(self) || found.containsKey(ai.packageName)) continue;
            // Amazon's own launcher and the old dumbtv home are not apps to open from here.
            if (ai.packageName.equals(Guard.AMAZON) || ai.packageName.equals("tv.dumbtv.home")) continue;
            Intent launch = new Intent(Intent.ACTION_MAIN).addCategory(category)
                    .setClassName(ai.packageName, ai.name);
            Item it = new Item(ai.packageName, ri.loadLabel(pm), launch);
            it.info = ai;
            found.put(ai.packageName, it);
        }
    }

    // ---------------------------------------------------------------- tile art

    /**
     * Renders the app's tile into a {@code w x h} bitmap and finds its brand colour. tvOS style:
     * the app's own icon centred on its brand colour, full bleed, with a glass shine; banner
     * style: its Android TV banner. Slow: call off the UI thread.
     */
    static void art(PackageManager pm, Item it, int w, int h, boolean tvos) {
        if (it.info == null || it.art != null) return;
        Drawable icon = null, banner = null;
        try {
            icon = it.info.loadIcon(pm);
        } catch (RuntimeException ignored) {
        }
        try {
            banner = it.info.loadBanner(pm);
        } catch (RuntimeException ignored) {
        }
        it.brand = brand(icon, banner);
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        if (!tvos && banner != null) {
            banner.setBounds(0, 0, w, h);
            banner.draw(c);
            shine(c, w, h, 0.6f);
        } else {
            iconTile(c, icon, it.brand, w, h);
            shine(c, w, h, 1f);
        }
        it.art = b;
    }

    /** The brand colour fills the tile with a gentle top-to-bottom fall, the icon sits in the middle. */
    private static void iconTile(Canvas c, Drawable icon, int brand, int w, int h) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
        p.setShader(new LinearGradient(0, 0, 0, h, Ui.lighten(brand, 0.10f), Ui.darken(brand, 0.10f),
                Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, p);
        if (icon instanceof AdaptiveIconDrawable) {
            // Adaptive icons come in two layers: the background fills the tile, the logo sits on it.
            AdaptiveIconDrawable a = (AdaptiveIconDrawable) icon;
            Drawable bg = a.getBackground(), fg = a.getForeground();
            if (bg != null) {
                int top = (h - w) / 2;
                bg.setBounds(0, top, w, top + w);
                bg.draw(c);
            }
            if (fg != null) {
                int s = Math.round(h * 1.12f);
                fg.setBounds((w - s) / 2, (h - s) / 2, (w + s) / 2, (h + s) / 2);
                fg.draw(c);
            }
        } else if (icon != null) {
            int s = Math.round(h * 0.6f);
            icon.setBounds((w - s) / 2, (h - s) / 2, (w + s) / 2, (h + s) / 2);
            icon.draw(c);
        }
    }

    /** Light catching the top of the glass: a soft sheen and a fine bright edge. */
    private static void shine(Canvas c, int w, int h, float strength) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setShader(new LinearGradient(0, 0, 0, h * 0.55f, Ui.alpha(0xFFFFFF, Math.round(0x38 * strength)),
                0x00FFFFFF, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h * 0.55f, p);
        p.setShader(new LinearGradient(0, 0, w, 0, new int[] {0x00FFFFFF, Ui.alpha(0xFFFFFF,
                Math.round(0x66 * strength)), 0x00FFFFFF}, null, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, Math.max(1, h / 90f), p);
    }

    /**
     * The colour an app is known by: an adaptive icon's background, else the edge of its banner,
     * else the edge (or the whole) of its icon.
     */
    private static int brand(Drawable icon, Drawable banner) {
        int c = 0;
        if (icon instanceof AdaptiveIconDrawable && ((AdaptiveIconDrawable) icon).getBackground() != null) {
            c = average(((AdaptiveIconDrawable) icon).getBackground(), 12, 12, false);
        }
        if (c == 0 && banner != null) c = average(banner, 32, 18, true);
        if (c == 0 && icon != null) {
            c = average(icon, 16, 16, true);
            if (c == 0) c = average(icon, 16, 16, false);
        }
        return c == 0 ? DEFAULT_BRAND : c | 0xFF000000;
    }

    /** Alpha-weighted average colour of the drawable, of its outer ring only when {@code edge}; 0 if empty. */
    private static int average(Drawable d, int w, int h, boolean edge) {
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        d.setBounds(0, 0, w, h);
        d.draw(new Canvas(b));
        long r = 0, g = 0, bl = 0, a = 0;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                if (edge && x > 0 && y > 0 && x < w - 1 && y < h - 1) continue;
                int p = b.getPixel(x, y), pa = p >>> 24;
                if (pa < 0x80) continue;
                r += ((p >> 16) & 0xFF) * pa;
                g += ((p >> 8) & 0xFF) * pa;
                bl += (p & 0xFF) * pa;
                a += pa;
            }
        }
        b.recycle();
        if (a == 0) return 0;
        return 0xFF000000 | (int) (r / a) << 16 | (int) (g / a) << 8 | (int) (bl / a);
    }

    /** A folder: a pane of glass holding its first four apps. */
    static void folderArt(Item folder, int w, int h) {
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        p.setColor(Theme.now.light ? 0x66FFFFFF : 0x2EFFFFFF);
        c.drawRect(0, 0, w, h, p);
        float gap = h * 0.08f, cellH = (h - gap * 3) / 2f, cellW = cellH * 16f / 9f;
        float left = (w - cellW * 2 - gap) / 2f;
        RectF cell = new RectF();
        Matrix m = new Matrix();
        for (int i = 0; i < Math.min(4, folder.members.size()); i++) {
            Item it = folder.members.get(i);
            float x = left + (i % 2) * (cellW + gap), y = gap + (i / 2) * (cellH + gap);
            cell.set(x, y, x + cellW, y + cellH);
            float r = cellH * 0.16f;
            Bitmap art = it.art;
            if (art != null) {
                BitmapShader s = new BitmapShader(art, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
                m.setScale(cellW / art.getWidth(), cellH / art.getHeight());
                m.postTranslate(x, y);
                s.setLocalMatrix(m);
                p.setShader(s);
                c.drawRoundRect(cell, r, r, p);
                p.setShader(null);
            } else {
                p.setColor(Theme.now.placeholder);
                c.drawRoundRect(cell, r, r, p);
            }
        }
        shine(c, w, h, 0.8f);
        folder.art = b;
    }

    // ---------------------------------------------------------------- stage art

    /**
     * The stage: the app's banner at {@code w x h} with feathered edges (or, without a banner,
     * its icon tile with rounded corners), and a small blurred copy that is drawn larger behind
     * it as a glow. Slow: call off the UI thread.
     */
    static StageArt stage(PackageManager pm, Item it, int w, int h) {
        if (it.info == null) return null;
        Drawable icon = null, banner = null;
        try {
            banner = it.info.loadBanner(pm);
            icon = it.info.loadIcon(pm);
        } catch (RuntimeException ignored) {
        }
        Bitmap art = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(art);
        if (banner != null) {
            banner.setBounds(0, 0, w, h);
            banner.draw(c);
            feather(c, w, h);
        } else {
            Bitmap tile = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            Canvas tc = new Canvas(tile);
            iconTile(tc, icon, it.brand, w, h);
            shine(tc, w, h, 1f);
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
            p.setShader(new BitmapShader(tile, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP));
            float r = h * 0.12f;
            c.drawRoundRect(new RectF(0, 0, w, h), r, r, p);
            tile.recycle();
        }
        return new StageArt(art, Backdrop.blur(art, 6, 3));
    }

    /** Fades the outer edges, so the banner melts into its own glow instead of ending in a hard box. */
    private static void feather(Canvas c, int w, int h) {
        Paint p = new Paint();
        p.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_IN));
        p.setShader(new LinearGradient(0, 0, w, 0, new int[] {0x00000000, 0xFF000000, 0xFF000000, 0x00000000},
                new float[] {0f, 0.07f, 0.93f, 1f}, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, p);
        p.setShader(new LinearGradient(0, 0, 0, h, new int[] {0x00000000, 0xFF000000, 0xFF000000, 0x00000000},
                new float[] {0f, 0.11f, 0.89f, 1f}, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, p);
    }

    // ---------------------------------------------------------------- inputs and defaults

    /** HDMI, AV and other passthrough inputs; HDMI first. */
    static List<Item> inputs(Context c) {
        List<Item> out = new ArrayList<>();
        TvInputManager tv = (TvInputManager) c.getSystemService(Context.TV_INPUT_SERVICE);
        if (tv == null) return out;
        List<Item> other = new ArrayList<>();
        try {
            for (TvInputInfo in : tv.getTvInputList()) {
                if (!in.isPassthroughInput() || in.isHidden(c)) continue;
                CharSequence label = in.loadCustomLabel(c);
                if (TextUtils.isEmpty(label)) label = in.loadLabel(c);
                Item it = new Item(in.getId(), label, null);
                (in.getType() == TvInputInfo.TYPE_HDMI ? out : other).add(it);
            }
        } catch (RuntimeException ignored) {
        }
        final Collator collator = Collator.getInstance();
        Collections.sort(out, (a, b) -> collator.compare(a.label.toString(), b.label.toString()));
        out.addAll(other);
        return out;
    }

    /** The default dock: the apps this household watches, in this order, where installed. */
    static List<String> defaultDock(List<Item> apps, int size) {
        String[][] wanted = {
                {"youtube"},
                {"castle"},
                {"netmirror", "net mirror"},
                {"prime video", "primevideo"},
                {"downloader"},
                {"amazon music", "amazonmusic"},
                {"netflix"},
        };
        List<String> dock = new ArrayList<>();
        for (String[] names : wanted) {
            for (Item it : apps) {
                String label = it.label.toString().toLowerCase(Locale.ROOT);
                boolean match = false;
                for (String n : names) if (label.contains(n) || it.id.toLowerCase(Locale.ROOT).contains(n.replace(" ", ""))) match = true;
                if (match && !dock.contains(it.id) && !label.contains("kids")) {
                    dock.add(it.id);
                    break;
                }
            }
        }
        for (Item it : apps) {
            if (dock.size() >= size) break;
            if (!dock.contains(it.id)) dock.add(it.id);
        }
        while (dock.size() > size) dock.remove(dock.size() - 1);
        return dock;
    }
}
