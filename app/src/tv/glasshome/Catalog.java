package tv.glasshome;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
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

/** Installed apps and TV inputs, and the artwork for each app. */
final class Catalog {
    /** One app (id = package name) or one TV input (id = input id). */
    static final class Item {
        final String id;
        final CharSequence label;
        final Intent intent;
        ActivityInfo info;
        volatile Bitmap art;
        volatile int color = 0xFF3A4A7A;

        Item(String id, CharSequence label, Intent intent) {
            this.id = id;
            this.label = label;
            this.intent = intent;
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

    /**
     * Renders the app's TV banner (or its icon on a soft tile when it has none) into a 16:9 bitmap
     * of {@code w x h} pixels, and samples its main colour. Slow: call off the UI thread.
     */
    static void art(PackageManager pm, Item it, int w, int h) {
        if (it.info == null || it.art != null) return;
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        Drawable banner = null, icon = null;
        try {
            banner = it.info.loadBanner(pm);
            if (banner == null) icon = it.info.loadIcon(pm);
        } catch (RuntimeException ignored) {
        }
        if (banner != null) {
            banner.setBounds(0, 0, w, h);
            banner.draw(c);
        } else {
            int base = 0xFF2A3350;
            if (icon != null) base = average(icon);
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            p.setShader(new android.graphics.LinearGradient(0, 0, 0, h, lighten(base, 0.18f), darken(base, 0.35f),
                    android.graphics.Shader.TileMode.CLAMP));
            c.drawRect(0, 0, w, h, p);
            if (icon != null) {
                int s = Math.round(h * 0.56f);
                icon.setBounds((w - s) / 2, (h - s) / 2, (w + s) / 2, (h + s) / 2);
                icon.draw(c);
            }
        }
        Bitmap one = Bitmap.createScaledBitmap(b, 1, 1, true);
        it.color = one.getPixel(0, 0) | 0xFF000000;
        one.recycle();
        it.art = b;
    }

    private static int average(Drawable d) {
        Bitmap b = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888);
        d.setBounds(0, 0, 8, 8);
        d.draw(new Canvas(b));
        Bitmap one = Bitmap.createScaledBitmap(b, 1, 1, true);
        int c = one.getPixel(0, 0) | 0xFF000000;
        b.recycle();
        one.recycle();
        return c;
    }

    private static int lighten(int c, float f) {
        int r = (c >> 16) & 0xFF, g = (c >> 8) & 0xFF, b = c & 0xFF;
        r += (int) ((255 - r) * f); g += (int) ((255 - g) * f); b += (int) ((255 - b) * f);
        return 0xFF000000 | r << 16 | g << 8 | b;
    }

    private static int darken(int c, float f) {
        int r = (c >> 16) & 0xFF, g = (c >> 8) & 0xFF, b = c & 0xFF;
        r -= (int) (r * f); g -= (int) (g * f); b -= (int) (b * f);
        return 0xFF000000 | r << 16 | g << 8 | b;
    }

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
    static List<String> defaultDock(List<Item> apps) {
        String[][] wanted = {
                {"youtube"},
                {"prime video", "primevideo"},
                {"castle"},
                {"netmirror", "net mirror"},
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
            if (dock.size() >= 5) break;
            if (!dock.contains(it.id)) dock.add(it.id);
        }
        while (dock.size() > 5) dock.remove(dock.size() - 1);
        return dock;
    }
}
