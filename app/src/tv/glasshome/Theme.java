package tv.glasshome;

import java.util.Calendar;

/**
 * The look: backdrop, glass, text and pill colours for each theme. Light is the tvOS look (a
 * pale sky that darkens towards the dock), Dark goes from grey to black, Aurora is the original
 * night-sky glass, and Auto is Light by day and Dark at night.
 */
final class Theme {
    static final String LIGHT = "light", DARK = "dark", AURORA = "aurora", AUTO = "auto";
    static final String[] CHOICES = {LIGHT, DARK, AURORA, AUTO};

    /** The palette in use; set before any view is built. */
    static Theme now = make(LIGHT);

    final String id;
    final boolean light;
    /** Backdrop gradient, top to bottom (Aurora paints its own). */
    final int[] sky;
    final float[] skyAt;
    final int frostLift;
    final float frostSaturation;

    /** Glass panels: body colour, sheen and rim alphas. */
    final int glassBody, glassSheen, rimTop, rimBottom;
    /** Menus and Control Center: denser glass, its text, and a focused row. */
    final int panelBody, panelText, panelSoft, panelMuted, rowFocus, rowFocusRim, rowIdle;
    /** Text drawn straight on the backdrop, and the wash behind the apps page. */
    final int text, soft, appsScrim;
    /** The name chip under a focused tile. */
    final int chip, chipText;
    /** The clock pill and round buttons, and the solid weather card. */
    final int pill, pillText, card, cardText, cardSoft;
    /** Strength of the focused app's colour wash, and the dimming behind menus. */
    final int tintAlpha, dim, placeholder;

    private Theme(String id, boolean light, int[] sky, float[] skyAt, int frostLift, float frostSaturation,
            int glassBody, int glassSheen, int rimTop, int rimBottom,
            int panelBody, int panelText, int rowFocus, int rowFocusRim, int rowIdle,
            int text, int appsScrim, int chip, int chipText,
            int pill, int pillText, int card, int cardText, int tintAlpha, int dim, int placeholder) {
        this.id = id;
        this.light = light;
        this.sky = sky;
        this.skyAt = skyAt;
        this.frostLift = frostLift;
        this.frostSaturation = frostSaturation;
        this.glassBody = glassBody;
        this.glassSheen = glassSheen;
        this.rimTop = rimTop;
        this.rimBottom = rimBottom;
        this.panelBody = panelBody;
        this.panelText = panelText;
        this.panelSoft = Ui.alpha(panelText, 0xCC);
        this.panelMuted = Ui.alpha(panelText, 0x8C);
        this.rowFocus = rowFocus;
        this.rowFocusRim = rowFocusRim;
        this.rowIdle = rowIdle;
        this.text = text;
        this.soft = Ui.alpha(text, 0xB3);
        this.appsScrim = appsScrim;
        this.chip = chip;
        this.chipText = chipText;
        this.pill = pill;
        this.pillText = pillText;
        this.card = card;
        this.cardText = cardText;
        this.cardSoft = Ui.alpha(cardText, 0x99);
        this.tintAlpha = tintAlpha;
        this.dim = dim;
        this.placeholder = placeholder;
    }

    static Theme make(String id) {
        switch (id) {
            case DARK:
                return new Theme(DARK, false,
                        new int[] {0xFF3C3C41, 0xFF2A2A2E, 0xFF141416, 0xFF050506},
                        new float[] {0f, 0.38f, 0.75f, 1f}, 14, 1.2f,
                        0x17FFFFFF, 0x2E, 0x8C, 0x18,
                        0xD01C1C1F, 0xFFFFFFFF, 0x38FFFFFF, 0x80FFFFFF, 0x14FFFFFF,
                        0xFFFFFFFF, 0xE80B0B0C, 0xB3000000, 0xFFFFFFFF,
                        0x2EFFFFFF, 0xFFFFFFFF, 0x2EFFFFFF, 0xFFFFFFFF, 0x5C, 0x99000000, 0xFF3A3A3F);
            case AURORA:
                return new Theme(AURORA, false, null, null, 20, 1.45f,
                        0x12FFFFFF, 0x30, 0xA6, 0x1A,
                        0x30FFFFFF, 0xFFFFFFFF, 0x2EFFFFFF, 0x66FFFFFF, 0x0FFFFFFF,
                        0xFFFFFFFF, 0xCC05070F, 0xB3000000, 0xFFFFFFFF,
                        0x24FFFFFF, 0xFFFFFFFF, 0x29FFFFFF, 0xFFFFFFFF, 0x40, 0x8C02030A, 0x26FFFFFF);
            default:
                return new Theme(LIGHT, true,
                        new int[] {0xFFBDBDC2, 0xFFD9D9DD, 0xFFA3A3A8, 0xFF3A3A3E, 0xFF1C1C1E},
                        new float[] {0f, 0.36f, 0.62f, 0.86f, 1f}, 10, 1.15f,
                        0x3DFFFFFF, 0x44, 0xD9, 0x2E,
                        0xE6F4F4F7, 0xFF1C1C1E, 0xFFFFFFFF, 0x26000000, 0x0F000000,
                        0xFF1C1C1E, 0xF2E9E9ED, 0xCC1C1C1E, 0xFFFFFFFF,
                        0x4D000000, 0xFFFFFFFF, 0xF7FFFFFF, 0xFF1C1C1E, 0x3D, 0x59000000, 0xFF8E8E93);
        }
    }

    /**
     * The plain colour the HOME guard shows for the instant before the home screen draws: close
     * to the top of the household's backdrop, so pressing HOME never flashes a different colour.
     */
    static int cover(android.content.Context c) {
        Prefs p = new Prefs(c);
        // Only Auto needs the forecast's sunrise and sunset; skip reading it on the HOME path otherwise.
        Weather.Report r = AUTO.equals(p.theme()) ? Weather.parse(p.forecast()) : null;
        String id = resolve(p.theme(), r != null ? r.sunrise : 0, r != null ? r.sunset : 0);
        switch (id) {
            case DARK: return 0xFF2E2E33;
            case AURORA: return 0xFF070912;
            default: return 0xFFC9C9CD;
        }
    }

    static String label(String id) {
        switch (id) {
            case DARK: return "Dark";
            case AURORA: return "Aurora";
            case AUTO: return "Auto";
            default: return "Light";
        }
    }

    /**
     * The theme to show for the user's choice: Auto is Light from sunrise to sunset (from the
     * weather when known, otherwise 7:00 to 19:00) and Dark the rest of the time.
     */
    static String resolve(String chosen, long sunrise, long sunset) {
        if (!AUTO.equals(chosen)) return chosen == null ? LIGHT : chosen;
        long now = System.currentTimeMillis();
        if (sunrise > 0 && sunset > sunrise && now - sunrise < 86_400_000L) {
            return now >= sunrise && now < sunset ? LIGHT : DARK;
        }
        int h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        return h >= 7 && h < 19 ? LIGHT : DARK;
    }
}
