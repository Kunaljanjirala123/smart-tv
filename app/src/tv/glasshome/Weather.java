package tv.glasshome;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/**
 * The weather, from Open-Meteo (open data, no account, no key, no tracking). Glass Home only
 * asks once the household has chosen a place, and only for that place's forecast. The last
 * forecast is kept, so the pill shows at once and offline.
 */
final class Weather {
    static final long FRESH_MS = 30 * 60 * 1000L;

    /** A place found by name. */
    static final class Place {
        final String name, area;
        final double lat, lon;

        Place(String name, String area, double lat, double lon) {
            this.name = name;
            this.area = area;
            this.lat = lat;
            this.lon = lon;
        }
    }

    static final class Hour {
        long time;
        int temp, code;
        boolean day;
    }

    static final class Day {
        long date;
        int hi, lo, code;
    }

    /** One forecast: now, the next hours and the next days. */
    static final class Report {
        int temp, code, hi, lo;
        boolean day, fahrenheit;
        long fetched, sunrise, sunset;
        final List<Hour> hours = new ArrayList<>();
        final List<Day> days = new ArrayList<>();
    }

    private Weather() {}

    // ---------------------------------------------------------------- network (worker thread only)

    /** Places matching {@code name}, best first. */
    static List<Place> search(String name) throws IOException {
        List<Place> out = new ArrayList<>();
        String url = "https://geocoding-api.open-meteo.com/v1/search?count=8&language=en&format=json&name="
                + URLEncoder.encode(name.trim(), "UTF-8");
        try {
            JSONObject o = new JSONObject(get(url));
            JSONArray rs = o.optJSONArray("results");
            if (rs == null) return out;
            for (int i = 0; i < rs.length(); i++) {
                JSONObject r = rs.getJSONObject(i);
                String area = join(r.optString("admin1", ""), r.optString("country", ""));
                out.add(new Place(r.getString("name"), area, r.getDouble("latitude"), r.getDouble("longitude")));
            }
        } catch (JSONException e) {
            throw new IOException("Unexpected answer from the weather service");
        }
        return out;
    }

    /** The forecast's raw JSON, for {@link #parse} and for keeping. */
    static String fetch(double lat, double lon, boolean fahrenheit) throws IOException {
        String url = String.format(Locale.ROOT, "https://api.open-meteo.com/v1/forecast?latitude=%.4f&longitude=%.4f"
                + "&current=temperature_2m,weather_code,is_day"
                + "&hourly=temperature_2m,weather_code,is_day"
                + "&daily=weather_code,temperature_2m_max,temperature_2m_min,sunrise,sunset"
                + "&timezone=auto&forecast_days=6%s", lat, lon, fahrenheit ? "&temperature_unit=fahrenheit" : "");
        String body = get(url);
        try {
            JSONObject o = new JSONObject(body);
            o.put("fetched", System.currentTimeMillis());
            o.put("fahrenheit", fahrenheit);
            return o.toString();
        } catch (JSONException e) {
            throw new IOException("Unexpected answer from the weather service");
        }
    }

    private static String get(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(8000);
        c.setRequestProperty("User-Agent", "GlassHome/2 (Android TV)");
        try {
            if (c.getResponseCode() != 200) throw new IOException("Weather service said " + c.getResponseCode());
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
                return out.toString("UTF-8");
            }
        } finally {
            c.disconnect();
        }
    }

    // ---------------------------------------------------------------- reading a forecast

    /** The kept forecast, or null when there is none or it cannot be read. */
    static Report parse(String json) {
        if (json == null || json.isEmpty()) return null;
        try {
            JSONObject o = new JSONObject(json);
            TimeZone tz = TimeZone.getTimeZone("GMT");
            int offset = o.optInt("utc_offset_seconds", 0);
            Report r = new Report();
            r.fetched = o.optLong("fetched", 0);
            r.fahrenheit = o.optBoolean("fahrenheit", false);
            JSONObject cur = o.getJSONObject("current");
            r.temp = (int) Math.round(cur.getDouble("temperature_2m"));
            r.code = cur.getInt("weather_code");
            r.day = cur.optInt("is_day", 1) == 1;
            long now = System.currentTimeMillis();
            JSONObject h = o.getJSONObject("hourly");
            JSONArray ht = h.getJSONArray("time"), htemp = h.getJSONArray("temperature_2m"),
                    hcode = h.getJSONArray("weather_code"), hday = h.getJSONArray("is_day");
            for (int i = 0; i < ht.length() && r.hours.size() < 12; i++) {
                long t = local(ht.getString(i), "yyyy-MM-dd'T'HH:mm", offset, tz);
                if (t + 3_600_000L < now) continue;
                Hour hr = new Hour();
                hr.time = t;
                hr.temp = (int) Math.round(htemp.getDouble(i));
                hr.code = hcode.getInt(i);
                hr.day = hday.getInt(i) == 1;
                r.hours.add(hr);
            }
            JSONObject d = o.getJSONObject("daily");
            JSONArray dt = d.getJSONArray("time"), dcode = d.getJSONArray("weather_code"),
                    dmax = d.getJSONArray("temperature_2m_max"), dmin = d.getJSONArray("temperature_2m_min"),
                    rise = d.getJSONArray("sunrise"), set = d.getJSONArray("sunset");
            for (int i = 0; i < dt.length(); i++) {
                long date = local(dt.getString(i), "yyyy-MM-dd", offset, tz);
                if (date + 86_400_000L < now) continue;
                Day day = new Day();
                day.date = date;
                day.code = dcode.getInt(i);
                day.hi = (int) Math.round(dmax.getDouble(i));
                day.lo = (int) Math.round(dmin.getDouble(i));
                if (r.days.isEmpty()) {
                    r.hi = day.hi;
                    r.lo = day.lo;
                    r.sunrise = local(rise.getString(i), "yyyy-MM-dd'T'HH:mm", offset, tz);
                    r.sunset = local(set.getString(i), "yyyy-MM-dd'T'HH:mm", offset, tz);
                }
                r.days.add(day);
            }
            return r;
        } catch (JSONException | ParseException | RuntimeException e) {
            return null;
        }
    }

    /** A local time at the place, as written by Open-Meteo, to a moment in time. */
    private static long local(String s, String pattern, int offsetSeconds, TimeZone gmt) throws ParseException {
        SimpleDateFormat f = new SimpleDateFormat(pattern, Locale.ROOT);
        f.setTimeZone(gmt);
        return f.parse(s).getTime() - offsetSeconds * 1000L;
    }

    // ---------------------------------------------------------------- words and icons

    /** WMO weather code to words, as on the tvOS weather pill. */
    static String describe(int code, boolean day) {
        switch (code) {
            case 0: return day ? "Sunny" : "Clear";
            case 1: return day ? "Mostly Sunny" : "Mostly Clear";
            case 2: return "Partly Cloudy";
            case 3: return "Cloudy";
            case 45: case 48: return "Fog";
            case 51: case 53: case 55: return "Drizzle";
            case 56: case 57: return "Freezing Drizzle";
            case 61: return "Light Rain";
            case 63: return "Rain";
            case 65: return "Heavy Rain";
            case 66: case 67: return "Freezing Rain";
            case 71: return "Light Snow";
            case 73: return "Snow";
            case 75: return "Heavy Snow";
            case 77: return "Snow Grains";
            case 80: case 81: return "Showers";
            case 82: return "Heavy Showers";
            case 85: case 86: return "Snow Showers";
            case 95: return "Thunderstorms";
            case 96: case 99: return "Thunderstorms and Hail";
            default: return "—";
        }
    }

    static int glyph(int code, boolean day) {
        if (code == 0 || code == 1) return day ? Glyph.SUN : Glyph.MOON;
        if (code == 2) return day ? Glyph.SUN_CLOUD : Glyph.MOON_CLOUD;
        if (code == 3) return Glyph.CLOUD;
        if (code == 45 || code == 48) return Glyph.FOG;
        if (code >= 51 && code <= 57) return Glyph.DRIZZLE;
        if (code >= 61 && code <= 67 || code >= 80 && code <= 82) return Glyph.RAIN;
        if (code >= 71 && code <= 77 || code == 85 || code == 86) return Glyph.SNOW;
        if (code >= 95) return Glyph.THUNDER;
        return Glyph.CLOUD;
    }

    private static String join(String a, String b) {
        if (a.isEmpty()) return b;
        if (b.isEmpty()) return a;
        return a + ", " + b;
    }
}
