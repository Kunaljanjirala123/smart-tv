package tv.glasshome;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.text.format.DateFormat;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Calendar;
import java.util.List;

/**
 * Control Center: a pane of glass that slides in from the right with the profile, quick
 * buttons (network, Bluetooth, inputs, appearance, StandBy, memory, settings) and the apps used
 * most recently. BACK or MENU slides it away.
 */
final class ControlCenter extends FrameLayout {
    /** What a button does: everything is carried out by the home screen. */
    interface Host {
        void profileMenu();
        void networkSettings();
        void bluetoothSettings();
        void inputsMenu();
        void themeMenu();
        void standBy();
        void freeMemory();
        void settingsMenu();
        void tvSettings();
        void open(Catalog.Item it);
        void appMenu(Tile t);
    }

    /** One quick button's face. */
    static final class Button {
        final int glyph;
        final String label, sub;
        final Runnable action;

        Button(int glyph, String label, String sub, Runnable action) {
            this.glyph = glyph;
            this.label = label;
            this.sub = sub;
            this.action = action;
        }
    }

    private final Glass.Frame panel;
    private final int panelW;
    private Runnable onClose;
    private boolean closing, sawDown;
    private final Host host;

    ControlCenter(Context c, Host host, Prefs prefs, List<Button> buttons, List<Catalog.Item> recents,
            int tileW, int tileH) {
        super(c);
        this.host = host;
        Theme t = Theme.now;
        setBackgroundColor(t.dim);
        setClickable(true);
        panelW = Ui.dp(c, 404);
        panel = new Glass.Frame(c, 30);
        panel.glass().body = t.panelBody;
        int pad = Ui.dp(c, 22);
        panel.setPadding(pad, pad, pad, pad);
        panel.setOutlineProvider(Ui.rounded(Ui.dp(c, 30)));
        LayoutParams plp = new LayoutParams(panelW, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.END);
        int m = Ui.dp(c, 18);
        plp.setMargins(m, m, m, m);
        addView(panel, plp);

        LinearLayout col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setClipChildren(false);
        panel.addView(col, new FrameLayout.LayoutParams(-1, -1));

        // Profile ---------------------------------------------------------------
        LinearLayout head = new LinearLayout(c);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        int hp = Ui.dp(c, 10);
        head.setPadding(hp, hp, hp, hp);
        head.setFocusable(true);
        head.setClickable(true);
        head.setBackground(Sheet.rowBackground(c));
        head.setOutlineProvider(Ui.rounded(Ui.dp(c, 14)));
        head.setOnClickListener(v -> host.profileMenu());
        lift(head, 1.02f);
        int as = Ui.dp(c, 52);
        head.addView(new Avatar(c, prefs.avatar()), new LinearLayout.LayoutParams(as, as));
        LinearLayout who = new LinearLayout(c);
        who.setOrientation(LinearLayout.VERTICAL);
        who.setPadding(Ui.dp(c, 14), 0, 0, 0);
        TextView name = Ui.text(c, 22, t.panelText, Ui.MEDIUM);
        name.setText(prefs.name());
        who.addView(name);
        TextView date = Ui.text(c, 14, t.panelMuted, Ui.REGULAR);
        date.setText(DateFormat.format("EEEE d MMMM", Calendar.getInstance()));
        date.setPadding(0, Ui.dp(c, 4), 0, 0);
        who.addView(date);
        head.addView(who, new LinearLayout.LayoutParams(0, -2, 1f));
        ImageView chevron = new ImageView(c);
        chevron.setImageDrawable(new Glyph(Glyph.CHEVRON, t.panelMuted));
        int cs = Ui.dp(c, 18);
        head.addView(chevron, new LinearLayout.LayoutParams(cs, cs));
        col.addView(head, new LinearLayout.LayoutParams(-1, -2));

        // Quick buttons ---------------------------------------------------------
        LinearLayout row = null;
        int gap = Ui.dp(c, 10);
        for (int i = 0; i < buttons.size(); i++) {
            if (i % 2 == 0) {
                row = new LinearLayout(c);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setClipChildren(false);
                LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(-1, Ui.dp(c, 58));
                rl.topMargin = i == 0 ? Ui.dp(c, 16) : gap;
                col.addView(row, rl);
            }
            LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(0, -1, 1f);
            if (i % 2 == 1) bl.leftMargin = gap;
            row.addView(button(c, buttons.get(i)), bl);
        }

        // Recent apps -----------------------------------------------------------
        TextView rt = Ui.text(c, 15, t.panelMuted, Ui.MEDIUM);
        rt.setText("Recent apps");
        rt.setPadding(Ui.dp(c, 4), Ui.dp(c, 18), 0, Ui.dp(c, 10));
        col.addView(rt);
        if (recents == null) {
            TextView n = Ui.text(c, 14, t.panelMuted, Ui.REGULAR);
            n.setSingleLine(false);
            n.setText("Run the Windows installer again to let Glass Home see which apps you used last.");
            n.setPadding(Ui.dp(c, 4), 0, 0, 0);
            col.addView(n);
        } else if (recents.isEmpty()) {
            TextView n = Ui.text(c, 14, t.panelMuted, Ui.REGULAR);
            n.setText("No recent apps yet");
            n.setPadding(Ui.dp(c, 4), 0, 0, 0);
            col.addView(n);
        } else {
            LinearLayout rr = new LinearLayout(c);
            rr.setOrientation(LinearLayout.HORIZONTAL);
            rr.setClipChildren(false);
            rr.setPadding(Ui.dp(c, 4), 0, 0, 0);
            // Padding would otherwise clip a recent app as it grows into focus.
            rr.setClipToPadding(false);
            for (int i = 0; i < recents.size(); i++) {
                final Catalog.Item it = recents.get(i);
                final Tile tile = new Tile(c, it, tileW, tileH, false);
                tile.setOnClickListener(v -> {
                    close();
                    host.open(it);
                });
                tile.setOnLongClickListener(v -> {
                    host.appMenu(tile);
                    return true;
                });
                LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(-2, -2);
                if (i > 0) tl.leftMargin = Ui.dp(c, 12);
                rr.addView(tile, tl);
            }
            col.addView(rr);
        }
    }

    private View button(Context c, Button b) {
        Theme t = Theme.now;
        LinearLayout v = new LinearLayout(c);
        v.setOrientation(LinearLayout.HORIZONTAL);
        v.setGravity(Gravity.CENTER_VERTICAL);
        int p = Ui.dp(c, 12);
        v.setPadding(p, 0, p, 0);
        v.setFocusable(true);
        v.setClickable(true);
        StateListDrawable bg = new StateListDrawable();
        GradientDrawable on = new GradientDrawable();
        on.setCornerRadius(Ui.dp(c, 16));
        on.setColor(t.light ? 0xFFFFFFFF : 0x4DFFFFFF);
        on.setStroke(Ui.dp(c, 1), t.light ? 0x1F000000 : 0x8CFFFFFF);
        GradientDrawable off = new GradientDrawable();
        off.setCornerRadius(Ui.dp(c, 16));
        off.setColor(t.light ? 0x8CFFFFFF : 0x1AFFFFFF);
        bg.addState(new int[] {android.R.attr.state_focused}, on);
        bg.addState(new int[] {}, off);
        bg.setEnterFadeDuration(110);
        bg.setExitFadeDuration(150);
        v.setBackground(bg);
        v.setOutlineProvider(Ui.rounded(Ui.dp(c, 16)));
        v.setElevation(Ui.dp(c, 1));
        ImageView g = new ImageView(c);
        g.setImageDrawable(new Glyph(b.glyph, t.panelText));
        int gs = Ui.dp(c, 22);
        v.addView(g, new LinearLayout.LayoutParams(gs, gs));
        LinearLayout words = new LinearLayout(c);
        words.setOrientation(LinearLayout.VERTICAL);
        words.setPadding(Ui.dp(c, 11), 0, 0, 0);
        TextView l = Ui.text(c, 15, t.panelText, Ui.MEDIUM);
        l.setText(b.label);
        words.addView(l);
        TextView s = Ui.text(c, 12, t.panelMuted, Ui.REGULAR);
        s.setText(b.sub);
        s.setPadding(0, Ui.dp(c, 3), 0, 0);
        words.addView(s);
        v.addView(words, new LinearLayout.LayoutParams(0, -2, 1f));
        v.setOnClickListener(x -> b.action.run());
        lift(v, 1.05f);
        return v;
    }

    private static void lift(View v, float scale) {
        v.setOnFocusChangeListener((x, has) -> x.animate().scaleX(has ? scale : 1f).scaleY(has ? scale : 1f)
                .translationZ(has ? Ui.dp(x.getContext(), 6) : 0f)
                .setInterpolator(has ? Ui.POP : Ui.EASE).setDuration(220).start());
    }

    void show(ViewGroup root, Runnable onClose) {
        this.onClose = onClose;
        root.addView(this, new ViewGroup.LayoutParams(-1, -1));
        setAlpha(0f);
        animate().alpha(1f).setDuration(200).start();
        panel.setTranslationX(panelW * 0.6f);
        panel.animate().translationX(0).setDuration(380).setInterpolator(Ui.EASE)
                .setUpdateListener(a -> Glass.invalidateAll()).start();
        post(() -> {
            View first = findFirstButton(this);
            if (first != null) first.requestFocus();
        });
    }

    /** The first quick button, so the most-used controls are one press away. */
    private static View findFirstButton(ViewGroup g) {
        java.util.ArrayList<View> f = new java.util.ArrayList<>();
        g.addFocusables(f, View.FOCUS_FORWARD);
        return f.size() > 1 ? f.get(1) : f.isEmpty() ? null : f.get(0);
    }

    void close() {
        if (closing) return;
        closing = true;
        setDescendantFocusability(FOCUS_BLOCK_DESCENDANTS);
        final ViewGroup parent = (ViewGroup) getParent();
        panel.animate().translationX(panelW * 0.6f).setDuration(240).setInterpolator(Ui.EASE)
                .setUpdateListener(a -> Glass.invalidateAll()).start();
        animate().alpha(0f).setDuration(240).withEndAction(() -> {
            if (parent != null) parent.removeView(this);
        }).start();
        if (onClose != null) onClose.run();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        int k = e.getKeyCode();
        if (k == KeyEvent.KEYCODE_BACK || k == KeyEvent.KEYCODE_MENU) {
            View f = findFocus();
            if (k == KeyEvent.KEYCODE_MENU && f instanceof Tile) {
                if (e.getAction() == KeyEvent.ACTION_UP) host.appMenu((Tile) f);
                return true;
            }
            if (e.getAction() == KeyEvent.ACTION_DOWN) sawDown = true;
            else if (e.getAction() == KeyEvent.ACTION_UP && sawDown) close();
            return true;
        }
        return super.dispatchKeyEvent(e);
    }
}
