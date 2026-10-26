package tv.glasshome;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Everything the household sets: the layout (dock, grid order, hidden apps, folders) in one
 * file that "Reset layout" clears, and the look, profile, weather and StandBy in another.
 */
final class Prefs {
    static final String ICONS_TVOS = "tvos", ICONS_BANNERS = "banners";
    static final String FOCUS_LIFT = "lift", FOCUS_LENS = "lens";
    static final String BAR_FULL = "full", BAR_MINIMAL = "minimal";
    /** Emoji avatars, each on its own colour. */
    static final String[] AVATARS = {"🐼", "🦊", "🐯", "🐨", "🐸", "🦁", "🐵", "🐶", "🐱", "🐰", "🦄", "🐧"};
    static final int[] AVATAR_COLOURS = {0xFF5AC8FA, 0xFFFF9F0A, 0xFFFFD60A, 0xFF8E8E93, 0xFF30D158, 0xFFFFB340,
            0xFFAC8E68, 0xFFFF6482, 0xFFBF5AF2, 0xFFFF9FB2, 0xFF7D7AFF, 0xFF64D2FF};

    private final SharedPreferences layout, settings;

    Prefs(Context c) {
        layout = c.getSharedPreferences("layout", Context.MODE_PRIVATE);
        settings = c.getSharedPreferences("settings", Context.MODE_PRIVATE);
    }

    // ---------------------------------------------------------------- layout

    boolean hasDock() {
        return layout.contains("dock");
    }

    List<String> dock() {
        return list(layout, "dock");
    }

    void setDock(List<String> ids) {
        put(layout, "dock", ids);
    }

    List<String> order() {
        return list(layout, "order");
    }

    void setOrder(List<String> ids) {
        put(layout, "order", ids);
    }

    List<String> hidden() {
        return list(layout, "hidden");
    }

    void setHidden(List<String> ids) {
        put(layout, "hidden", ids);
    }

    /** Folder ids, each "folder:" plus a number, in creation order. */
    List<String> folders() {
        return list(layout, "folders");
    }

    String folderName(String id) {
        return layout.getString(id + ".name", "Folder");
    }

    List<String> folderApps(String id) {
        return list(layout, id + ".apps");
    }

    String newFolder(String name) {
        String id = "folder:" + System.currentTimeMillis();
        List<String> all = folders();
        all.add(id);
        put(layout, "folders", all);
        layout.edit().putString(id + ".name", name).apply();
        return id;
    }

    void setFolderName(String id, String name) {
        layout.edit().putString(id + ".name", name).apply();
    }

    void setFolderApps(String id, List<String> apps) {
        put(layout, id + ".apps", apps);
    }

    void deleteFolder(String id) {
        List<String> all = folders();
        all.remove(id);
        put(layout, "folders", all);
        layout.edit().remove(id + ".name").remove(id + ".apps").apply();
    }

    /** The folder holding {@code app}, or null. */
    String folderOf(String app) {
        for (String f : folders()) if (folderApps(f).contains(app)) return f;
        return null;
    }

    void resetLayout() {
        layout.edit().clear().apply();
    }

    // ---------------------------------------------------------------- settings

    String theme() {
        return settings.getString("theme", Theme.LIGHT);
    }

    void setTheme(String id) {
        settings.edit().putString("theme", id).apply();
    }

    String icons() {
        return settings.getString("icons", ICONS_TVOS);
    }

    String focus() {
        return settings.getString("focus", FOCUS_LIFT);
    }

    String bar() {
        return settings.getString("bar", BAR_FULL);
    }

    boolean stage() {
        return settings.getBoolean("stage", true);
    }

    int dockSize() {
        return settings.getInt("dock_size", 6);
    }

    int perRow() {
        return settings.getInt("per_row", 6);
    }

    String name() {
        return settings.getString("name", "Home");
    }

    int avatar() {
        int a = settings.getInt("avatar", 0);
        return a >= 0 && a < AVATARS.length ? a : 0;
    }

    boolean setupDone() {
        return settings.getBoolean("setup_done", false);
    }

    // Weather: a place chosen by name, and the last forecast so it shows at once, offline too.
    boolean weatherOn() {
        return settings.getBoolean("weather_on", true);
    }

    String place() {
        return settings.getString("place", "");
    }

    double lat() {
        return Double.longBitsToDouble(settings.getLong("lat", 0));
    }

    double lon() {
        return Double.longBitsToDouble(settings.getLong("lon", 0));
    }

    boolean hasPlace() {
        return settings.contains("lat") && !place().isEmpty();
    }

    void setPlace(String name, double lat, double lon) {
        settings.edit().putString("place", name).putLong("lat", Double.doubleToLongBits(lat))
                .putLong("lon", Double.doubleToLongBits(lon)).remove("forecast").apply();
    }

    void clearPlace() {
        settings.edit().remove("place").remove("lat").remove("lon").remove("forecast").apply();
    }

    /** Fahrenheit where the country uses it, Celsius elsewhere, unless chosen. */
    boolean fahrenheit() {
        if (settings.contains("fahrenheit")) return settings.getBoolean("fahrenheit", false);
        String cc = Locale.getDefault().getCountry();
        return Arrays.asList("US", "LR", "MM", "BS", "BZ", "KY", "PW", "FM", "MH").contains(cc);
    }

    String forecast() {
        return settings.getString("forecast", "");
    }

    void setForecast(String json) {
        settings.edit().putString("forecast", json).apply();
    }

    String standbyStyle() {
        return settings.getString("standby_style", StandBy.CLASSIC);
    }

    int standbyColour() {
        int i = settings.getInt("standby_colour", 0);
        return i >= 0 && i < StandBy.COLOURS.length ? i : 0;
    }

    void set(String key, String value) {
        settings.edit().putString(key, value).apply();
    }

    void set(String key, int value) {
        settings.edit().putInt(key, value).apply();
    }

    void set(String key, boolean value) {
        settings.edit().putBoolean(key, value).apply();
    }

    // ---------------------------------------------------------------- storage

    private static List<String> list(SharedPreferences sp, String key) {
        String v = sp.getString(key, "");
        if (TextUtils.isEmpty(v)) return new ArrayList<>();
        return new ArrayList<>(Arrays.asList(v.split("\n")));
    }

    private static void put(SharedPreferences sp, String key, List<String> ids) {
        sp.edit().putString(key, TextUtils.join("\n", ids)).apply();
    }
}
