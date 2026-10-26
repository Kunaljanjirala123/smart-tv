package tv.glasshome;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** The household's layout (dock, grid order, hidden apps) and the few settings. */
final class Prefs {
    static final int DOCK_MAX = 5;
    private final SharedPreferences sp, settings;

    Prefs(Context c) {
        sp = c.getSharedPreferences("layout", Context.MODE_PRIVATE);
        // Kept apart from the layout, so Reset layout leaves them alone.
        settings = c.getSharedPreferences("settings", Context.MODE_PRIVATE);
    }

    boolean glassVolume() {
        return settings.getBoolean("glass_volume", true);
    }

    void setGlassVolume(boolean on) {
        settings.edit().putBoolean("glass_volume", on).apply();
    }

    boolean hasDock() {
        return sp.contains("dock");
    }

    List<String> dock() {
        return list("dock");
    }

    void setDock(List<String> ids) {
        put("dock", ids);
    }

    List<String> order() {
        return list("order");
    }

    void setOrder(List<String> ids) {
        put("order", ids);
    }

    List<String> hidden() {
        return list("hidden");
    }

    void setHidden(List<String> ids) {
        put("hidden", ids);
    }

    void reset() {
        sp.edit().clear().apply();
    }

    private List<String> list(String key) {
        String v = sp.getString(key, "");
        if (TextUtils.isEmpty(v)) return new ArrayList<>();
        return new ArrayList<>(Arrays.asList(v.split("\n")));
    }

    private void put(String key, List<String> ids) {
        sp.edit().putString(key, TextUtils.join("\n", ids)).apply();
    }
}
