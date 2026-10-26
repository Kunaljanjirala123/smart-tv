package tv.glasshome;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.List;

/**
 * First-run setup on one pane of glass: welcome, a look, a profile, the weather's city, done.
 * Every step can be skipped, and Settings can run it again.
 */
final class Setup extends FrameLayout {
    interface Places {
        void found(List<Weather.Place> places, String error);
    }

    interface Host {
        void searchPlaces(String query, Places done);

        void finishSetup(String theme, int avatar, String name, Weather.Place place);
    }

    private final Host host;
    private final Prefs prefs;
    private final Glass.Frame card;
    private final LinearLayout body;
    private String theme;
    private int avatar;
    private String name;
    private Weather.Place place;

    Setup(Context c, Host host, Prefs prefs) {
        super(c);
        this.host = host;
        this.prefs = prefs;
        theme = prefs.theme();
        avatar = prefs.avatar();
        name = prefs.name();
        setBackgroundColor(Theme.now.dim);
        setClickable(true);
        card = new Glass.Frame(c, 34);
        card.glass().body = Theme.now.panelBody;
        int pad = Ui.dp(c, 34);
        card.setPadding(pad, Ui.dp(c, 30), pad, Ui.dp(c, 28));
        body = new LinearLayout(c);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setClipChildren(false);
        card.addView(body, new FrameLayout.LayoutParams(-1, -1));
        addView(card, new LayoutParams(Ui.dp(c, 660), Ui.dp(c, 420), Gravity.CENTER));
    }

    void show(ViewGroup root) {
        root.addView(this, new ViewGroup.LayoutParams(-1, -1));
        setAlpha(0f);
        animate().alpha(1f).setDuration(300).start();
        // Built once attached, so the first button can take focus.
        welcome();
    }

    /** The keyboard's action key, or ENTER pressed (not also released) on a remote or keyboard. */
    private static boolean fires(int action, KeyEvent e, int want) {
        if (e == null) return action == want || action == EditorInfo.IME_ACTION_DONE;
        return e.getKeyCode() == KeyEvent.KEYCODE_ENTER && e.getAction() == KeyEvent.ACTION_DOWN;
    }

    // ---------------------------------------------------------------- steps

    private void welcome() {
        step("Welcome to Glass Home", "A fast home screen for your TV, in glass, with no adverts. "
                + "Four quick choices and you are done.");
        TextView start = button("Get Started", true, this::look);
        TextView skip = button("Skip Setup", false, () -> host.finishSetup(theme, avatar, name, null));
        buttons(start, skip);
        start.requestFocus();
    }

    private void look() {
        step("Choose a Look", "Light is the tvOS look. Auto is Light by day and Dark at night.");
        LinearLayout row = new LinearLayout(getContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setClipChildren(false);
        row.setPadding(0, Ui.dp(getContext(), 26), 0, 0);
        View chosen = null;
        for (String id : Theme.CHOICES) {
            View v = swatch(id);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, Ui.dp(getContext(), 150), 1f);
            lp.leftMargin = lp.rightMargin = Ui.dp(getContext(), 9);
            row.addView(v, lp);
            if (id.equals(theme)) chosen = v;
        }
        body.addView(row);
        (chosen != null ? chosen : row.getChildAt(0)).requestFocus();
    }

    private View swatch(final String id) {
        Context c = getContext();
        LinearLayout v = new LinearLayout(c);
        v.setOrientation(LinearLayout.VERTICAL);
        v.setGravity(Gravity.CENTER_HORIZONTAL);
        int p = Ui.dp(c, 10);
        v.setPadding(p, p, p, p);
        v.setFocusable(true);
        v.setClickable(true);
        v.setBackground(Sheet.rowBackground(c));
        lift(v, 1.06f);
        v.addView(new Preview(c, id), new LinearLayout.LayoutParams(-1, 0, 1f));
        TextView l = Ui.text(c, 17, Theme.now.panelText, Ui.MEDIUM);
        l.setText(Theme.label(id));
        l.setGravity(Gravity.CENTER);
        l.setPadding(0, Ui.dp(c, 10), 0, 0);
        v.addView(l, new LinearLayout.LayoutParams(-1, -2));
        v.setOnClickListener(x -> {
            theme = id;
            profile();
        });
        return v;
    }

    private void profile() {
        step("Create Your Profile", "Your picture and name show next to the clock and in Control Center.");
        Context c = getContext();
        LinearLayout grid = new LinearLayout(c);
        grid.setOrientation(LinearLayout.VERTICAL);
        grid.setClipChildren(false);
        grid.setPadding(0, Ui.dp(c, 18), 0, Ui.dp(c, 6));
        View chosen = null;
        LinearLayout row = null;
        for (int i = 0; i < Prefs.AVATARS.length; i++) {
            if (i % 6 == 0) {
                row = new LinearLayout(c);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_HORIZONTAL);
                row.setClipChildren(false);
                grid.addView(row, new LinearLayout.LayoutParams(-1, -2));
            }
            View a = avatarChoice(i);
            int s = Ui.dp(c, 60);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(s, s);
            lp.setMargins(Ui.dp(c, 10), Ui.dp(c, 5), Ui.dp(c, 10), Ui.dp(c, 5));
            row.addView(a, lp);
            if (i == avatar) chosen = a;
        }
        body.addView(grid);
        final EditText field = field("Your name", "Home".equals(name) ? "" : name, EditorInfo.IME_ACTION_DONE);
        field.setOnEditorActionListener((v, action, e) -> {
            if (fires(action, e, EditorInfo.IME_ACTION_DONE)) {
                Sheet.hideKeyboard(v);
                keepName(field);
                afterProfile();
                return true;
            }
            return false;
        });
        body.addView(field, new LinearLayout.LayoutParams(-1, -2));
        TextView next = button("Continue", true, () -> {
            keepName(field);
            afterProfile();
        });
        buttons(next);
        if (chosen != null) chosen.requestFocus();
    }

    /** The installer may already have set the weather's city; then there is nothing to ask. */
    private void afterProfile() {
        if (prefs.hasPlace()) done();
        else weather();
    }

    private void keepName(EditText field) {
        String n = field.getText().toString().trim();
        name = n.isEmpty() ? "Home" : n;
    }

    private View avatarChoice(final int i) {
        Context c = getContext();
        FrameLayout f = new FrameLayout(c);
        f.setFocusable(true);
        f.setClickable(true);
        GradientDrawable ring = new GradientDrawable();
        ring.setShape(GradientDrawable.OVAL);
        ring.setStroke(Ui.dp(c, 3), Theme.now.light ? 0xFF0A84FF : 0xFFFFFFFF);
        StateListDrawable bg = new StateListDrawable();
        bg.addState(new int[] {android.R.attr.state_focused}, ring);
        bg.addState(new int[] {}, new GradientDrawable());
        f.setForeground(bg);
        int in = Ui.dp(c, 5);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-1, -1);
        lp.setMargins(in, in, in, in);
        f.addView(new Avatar(c, i), lp);
        lift(f, 1.14f);
        f.setOnClickListener(v -> {
            avatar = i;
            View field = body.findViewWithTag("field");
            if (field != null) field.requestFocus();
        });
        return f;
    }

    private void weather() {
        step("Weather", "Type your city and press the keyboard's search key. Only that city's "
                + "forecast is fetched, from Open-Meteo, with no account and no tracking.");
        Context c = getContext();
        final LinearLayout results = new LinearLayout(c);
        results.setOrientation(LinearLayout.VERTICAL);
        final EditText field = field("City", "", EditorInfo.IME_ACTION_SEARCH);
        LinearLayout.LayoutParams fl = new LinearLayout.LayoutParams(-1, -2);
        fl.topMargin = Ui.dp(c, 18);
        body.addView(field, fl);
        body.addView(results, new LinearLayout.LayoutParams(-1, 0, 1f));
        field.setOnEditorActionListener((v, action, e) -> {
            if (fires(action, e, EditorInfo.IME_ACTION_SEARCH)) {
                Sheet.hideKeyboard(v);
                String q = v.getText().toString().trim();
                if (!q.isEmpty()) search(q, results);
                return true;
            }
            return false;
        });
        TextView skip = button("Skip", false, this::done);
        buttons(skip);
        field.requestFocus();
    }

    private void search(String q, final LinearLayout results) {
        results.removeAllViews();
        note(results, "Searching…");
        host.searchPlaces(q, (places, error) -> {
            results.removeAllViews();
            if (error != null) {
                note(results, error);
                return;
            }
            if (places.isEmpty()) {
                note(results, "No place called \"" + q + "\". Check the spelling or try a nearby city.");
                return;
            }
            for (int i = 0; i < Math.min(3, places.size()); i++) {
                final Weather.Place p = places.get(i);
                TextView row = Ui.text(getContext(), 17, Theme.now.panelText, Ui.REGULAR);
                row.setText(p.area.isEmpty() ? p.name : p.name + "  ·  " + p.area);
                int h = Ui.dp(getContext(), 14);
                row.setPadding(h, Ui.dp(getContext(), 9), h, Ui.dp(getContext(), 9));
                row.setFocusable(true);
                row.setClickable(true);
                row.setBackground(Sheet.rowBackground(getContext()));
                row.setOnClickListener(v -> {
                    place = p;
                    done();
                });
                results.addView(row, new LinearLayout.LayoutParams(-1, -2));
            }
            results.getChildAt(0).requestFocus();
        });
    }

    private void done() {
        step("You're All Set", null);
        Context c = getContext();
        String[] tips = {
                "Press OK on the clock for Control Center: Wi-Fi, inputs, StandBy and recent apps.",
                "Press the Menu button (≡) on any app to move it, hide it or put it in a folder.",
                place != null || prefs.hasPlace()
                        ? "The weather for " + (place != null ? place.name : prefs.place())
                                + " shows at the top left. Press OK on it for the forecast."
                        : "Add the weather any time from Settings.",
                "Press Down from the dock for every app.",
        };
        for (String t : tips) {
            TextView v = Ui.text(c, 16, Theme.now.panelSoft, Ui.REGULAR);
            v.setSingleLine(false);
            v.setText("•  " + t);
            v.setPadding(0, Ui.dp(c, 12), 0, 0);
            body.addView(v);
        }
        TextView go = button("Go to Home", true, () -> host.finishSetup(theme, avatar, name, place));
        buttons(go);
        go.requestFocus();
    }

    // ---------------------------------------------------------------- parts

    private void step(String title, String sub) {
        body.removeAllViews();
        body.setAlpha(0f);
        body.setTranslationX(Ui.dp(getContext(), 24));
        body.animate().alpha(1f).translationX(0).setDuration(320).setInterpolator(Ui.EASE).start();
        TextView t = Ui.text(getContext(), 32, Theme.now.panelText, Ui.LIGHT);
        t.setText(title);
        body.addView(t);
        if (sub != null) {
            TextView s = Ui.text(getContext(), 16, Theme.now.panelMuted, Ui.REGULAR);
            s.setSingleLine(false);
            s.setText(sub);
            s.setPadding(0, Ui.dp(getContext(), 10), 0, 0);
            body.addView(s);
        }
    }

    private void note(LinearLayout into, String text) {
        TextView v = Ui.text(getContext(), 15, Theme.now.panelMuted, Ui.REGULAR);
        v.setSingleLine(false);
        v.setText(text);
        v.setPadding(Ui.dp(getContext(), 6), Ui.dp(getContext(), 12), 0, 0);
        into.addView(v);
    }

    private EditText field(String hint, String initial, int action) {
        EditText e = Sheet.field(getContext(), hint, initial, action);
        e.setTag("field");
        return e;
    }

    /** The row of buttons along the bottom of the card. */
    private void buttons(TextView... bs) {
        LinearLayout row = new LinearLayout(getContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        row.setClipChildren(false);
        for (TextView b : bs) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, Ui.dp(getContext(), 48));
            lp.leftMargin = Ui.dp(getContext(), 12);
            row.addView(b, lp);
        }
        View spacer = new View(getContext());
        body.addView(spacer, new LinearLayout.LayoutParams(1, 0, 1f));
        body.addView(row, new LinearLayout.LayoutParams(-1, -2));
    }

    private TextView button(String label, boolean primary, Runnable click) {
        Context c = getContext();
        Theme t = Theme.now;
        TextView b = Ui.text(c, 17, primary ? 0xFFFFFFFF : t.panelText, Ui.MEDIUM);
        b.setText(label);
        b.setGravity(Gravity.CENTER);
        int h = Ui.dp(c, 26);
        b.setPadding(h, 0, h, 0);
        b.setFocusable(true);
        b.setClickable(true);
        StateListDrawable bg = new StateListDrawable();
        GradientDrawable on = new GradientDrawable();
        on.setCornerRadius(Ui.dp(c, 24));
        on.setColor(primary ? 0xFF0A84FF : (t.light ? 0xFFFFFFFF : 0x4DFFFFFF));
        on.setStroke(Ui.dp(c, 2), primary ? 0xFFFFFFFF : t.rowFocusRim);
        GradientDrawable off = new GradientDrawable();
        off.setCornerRadius(Ui.dp(c, 24));
        off.setColor(primary ? 0xCC0A84FF : (t.light ? 0x14000000 : 0x1FFFFFFF));
        bg.addState(new int[] {android.R.attr.state_focused}, on);
        bg.addState(new int[] {}, off);
        b.setBackground(bg);
        lift(b, 1.06f);
        b.setOnClickListener(v -> click.run());
        return b;
    }

    private static void lift(View v, float scale) {
        v.setOnFocusChangeListener((x, has) -> x.animate().scaleX(has ? scale : 1f).scaleY(has ? scale : 1f)
                .setInterpolator(has ? Ui.POP : Ui.EASE).setDuration(220).start());
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        // BACK steps out of setup altogether, keeping the choices made so far.
        if (e.getKeyCode() == KeyEvent.KEYCODE_BACK) {
            if (e.getAction() == KeyEvent.ACTION_UP) host.finishSetup(theme, avatar, name, place);
            return true;
        }
        return super.dispatchKeyEvent(e);
    }

    /** A small picture of a theme: its sky and a glass dock. */
    private static final class Preview extends View {
        private final Theme theme;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();

        Preview(Context c, String id) {
            super(c);
            theme = Theme.make(Theme.AUTO.equals(id) ? Theme.LIGHT : id);
            setTag(id);
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight(), rad = h * 0.14f;
            r.set(0, 0, w, h);
            int[] sky = theme.sky != null ? theme.sky : new int[] {0xFF1B1F4A, 0xFF2A1F6E, 0xFF0E5C6E, 0xFF04050C};
            float[] at = theme.skyAt != null ? theme.skyAt : null;
            if (Theme.AUTO.equals(getTag())) {
                // Half day, half night.
                p.setShader(new LinearGradient(0, 0, w, 0, new int[] {0xFFD9D9DD, 0xFFD9D9DD, 0xFF1C1C1F, 0xFF1C1C1F},
                        new float[] {0f, 0.5f, 0.5f, 1f}, Shader.TileMode.CLAMP));
            } else {
                p.setShader(new LinearGradient(0, 0, 0, h, sky, at, Shader.TileMode.CLAMP));
            }
            c.drawRoundRect(r, rad, rad, p);
            p.setShader(null);
            p.setColor(theme.light ? 0x99FFFFFF : 0x40FFFFFF);
            r.set(w * 0.1f, h * 0.72f, w * 0.9f, h * 0.9f);
            c.drawRoundRect(r, h * 0.06f, h * 0.06f, p);
            p.setColor(0xF2FFFFFF);
            r.set(w * 0.3f, h * 0.22f, w * 0.7f, h * 0.48f);
            c.drawRoundRect(r, h * 0.05f, h * 0.05f, p);
        }
    }
}
