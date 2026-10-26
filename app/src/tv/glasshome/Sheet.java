package tv.glasshome;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * A floating glass menu over a dimmed screen: a title and a list of rows. Rows can open
 * something (the menu closes first), change a setting in place (the value on the right updates),
 * carry a check mark, take text from the TV keyboard, or hold any view. BACK closes it.
 */
final class Sheet extends FrameLayout {
    interface Choice {
        void run();
    }

    interface Value {
        CharSequence get();
    }

    interface Text {
        void done(String text);
    }

    interface TileMenu {
        void open(Tile t);
    }

    private final LinearLayout list;
    private final Glass.Frame panel;
    private final List<Runnable> refreshers = new ArrayList<>();
    private Runnable onClose;
    private TileMenu tileMenu;
    private View lastFocus;
    private boolean closing, sawDown;

    Sheet(Context c, CharSequence title, CharSequence subtitle) {
        super(c);
        Theme t = Theme.now;
        setBackgroundColor(t.dim);
        setClickable(true);
        panel = new Glass.Frame(c, 28);
        panel.glass().body = t.panelBody;
        panel.glass().sheenAlpha = Math.min(255, t.glassSheen + 8);
        int pad = Ui.dp(c, 22);
        panel.setPadding(pad, pad, pad, Ui.dp(c, 14));
        LinearLayout col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);
        TextView tt = Ui.text(c, 24, t.panelText, Ui.MEDIUM);
        tt.setText(title);
        tt.setPadding(Ui.dp(c, 8), 0, Ui.dp(c, 8), 0);
        col.addView(tt);
        if (subtitle != null) {
            TextView s = Ui.text(c, 14, t.panelMuted, Ui.REGULAR);
            s.setSingleLine(false);
            s.setText(subtitle);
            s.setPadding(Ui.dp(c, 8), Ui.dp(c, 6), Ui.dp(c, 8), 0);
            col.addView(s);
        }
        list = new LinearLayout(c);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(0, Ui.dp(c, 14), 0, 0);
        list.setClipChildren(false);
        list.setClipToPadding(false);
        final int maxH = Ui.dp(c, 400);
        ScrollView scroll = new ScrollView(c) {
            @Override
            protected void onMeasure(int w, int h) {
                super.onMeasure(w, MeasureSpec.makeMeasureSpec(maxH, MeasureSpec.AT_MOST));
            }
        };
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setClipChildren(false);
        scroll.setClipToPadding(false);
        scroll.addView(list);
        col.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        panel.addView(col);
        addView(panel, new LayoutParams(Ui.dp(c, 460), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
    }

    /** Panel width, in dp. */
    Sheet width(int dp) {
        LayoutParams lp = (LayoutParams) panel.getLayoutParams();
        lp.width = Ui.dp(getContext(), dp);
        panel.setLayoutParams(lp);
        return this;
    }

    /** For menus holding app tiles (a folder): the Menu button opens that app's menu instead of closing. */
    Sheet tileMenu(TileMenu m) {
        tileMenu = m;
        return this;
    }

    /** Sits in the top half, clear of the on-screen keyboard. */
    Sheet atTop() {
        LayoutParams lp = (LayoutParams) panel.getLayoutParams();
        lp.gravity = Gravity.CENTER_HORIZONTAL | Gravity.TOP;
        lp.topMargin = Ui.dp(getContext(), 40);
        panel.setLayoutParams(lp);
        return this;
    }

    /** A row that closes the menu, then runs {@code choice}. */
    Sheet add(CharSequence label, Choice choice) {
        return add(0, label, choice);
    }

    Sheet add(int glyph, CharSequence label, Choice choice) {
        View row = row(glyph, label, null, false);
        row.setOnClickListener(v -> {
            close();
            choice.run();
        });
        return this;
    }

    /** A row with a check mark when {@code on}; closes the menu, then runs {@code choice}. */
    Sheet check(CharSequence label, boolean on, Choice choice) {
        View row = row(0, label, null, on);
        row.setOnClickListener(v -> {
            close();
            choice.run();
        });
        return this;
    }

    /** A setting: its current value on the right; OK runs {@code change} and the value updates in place. */
    Sheet option(CharSequence label, Value value, Choice change) {
        View row = row(0, label, value.get(), false);
        final TextView v = row.findViewWithTag("value");
        refreshers.add(() -> v.setText(value.get()));
        row.setOnClickListener(x -> {
            change.run();
            for (Runnable r : refreshers) r.run();
        });
        return this;
    }

    /** A setting that opens another menu: its value on the right and a chevron. */
    Sheet more(CharSequence label, Value value, Choice open) {
        View row = row(0, label, value == null ? null : value.get(), false);
        ImageView chevron = new ImageView(getContext());
        chevron.setImageDrawable(new Glyph(Glyph.CHEVRON, Theme.now.panelMuted));
        int s = Ui.dp(getContext(), 16);
        ((LinearLayout) row).addView(chevron, new LinearLayout.LayoutParams(s, s));
        if (value != null) {
            final TextView v = row.findViewWithTag("value");
            refreshers.add(() -> v.setText(value.get()));
        }
        row.setOnClickListener(x -> open.run());
        return this;
    }

    Sheet note(CharSequence text) {
        TextView t = Ui.text(getContext(), 15, Theme.now.panelMuted, Ui.REGULAR);
        t.setSingleLine(false);
        t.setText(text);
        int h = Ui.dp(getContext(), 16);
        t.setPadding(h, Ui.dp(getContext(), 8), h, Ui.dp(getContext(), 8));
        list.addView(t);
        return this;
    }

    Sheet view(View v) {
        list.addView(v);
        return this;
    }

    /** A text field for the TV keyboard; {@code done} gets the text when the keyboard's action key is pressed. */
    EditText input(CharSequence hint, String initial, int imeAction, Text done) {
        Context c = getContext();
        EditText e = field(c, hint, initial, imeAction);
        e.setOnEditorActionListener((view, action, ev) -> {
            if (action == imeAction || ev != null && ev.getKeyCode() == KeyEvent.KEYCODE_ENTER
                    && ev.getAction() == KeyEvent.ACTION_DOWN) {
                hideKeyboard(view);
                done.done(view.getText().toString().trim());
                return true;
            }
            return false;
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = Ui.dp(c, 8);
        list.addView(e, lp);
        return e;
    }

    /** A text field in the glass style; the TV keyboard opens on OK and stays clear of it. */
    static EditText field(Context c, CharSequence hint, String initial, int imeAction) {
        Theme t = Theme.now;
        EditText e = new EditText(c);
        e.setSingleLine(true);
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        e.setImeOptions(imeAction | EditorInfo.IME_FLAG_NO_EXTRACT_UI | EditorInfo.IME_FLAG_NO_FULLSCREEN);
        e.setTextSize(TypedValue.COMPLEX_UNIT_SP, 19);
        e.setTextColor(t.panelText);
        e.setHintTextColor(t.panelMuted);
        e.setHint(hint);
        if (initial != null) {
            e.setText(initial);
            e.setSelection(initial.length());
        }
        int h = Ui.dp(c, 16), v = Ui.dp(c, 12);
        e.setPadding(h, v, h, v);
        StateListDrawable bg = new StateListDrawable();
        GradientDrawable on = new GradientDrawable();
        on.setCornerRadius(Ui.dp(c, 14));
        on.setColor(t.light ? 0xFFFFFFFF : 0x2EFFFFFF);
        on.setStroke(Ui.dp(c, 2), t.light ? 0xFF0A84FF : 0xCCFFFFFF);
        GradientDrawable off = new GradientDrawable();
        off.setCornerRadius(Ui.dp(c, 14));
        off.setColor(t.light ? 0x14000000 : 0x1AFFFFFF);
        bg.addState(new int[] {android.R.attr.state_focused}, on);
        bg.addState(new int[] {}, off);
        e.setBackground(bg);
        return e;
    }

    static void hideKeyboard(View v) {
        InputMethodManager im = (InputMethodManager) v.getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        if (im != null) im.hideSoftInputFromWindow(v.getWindowToken(), 0);
    }

    /** Removes every row: for menus that fill themselves in later, like search results. */
    void clearRows() {
        list.removeAllViews();
        refreshers.clear();
    }

    private View row(int glyph, CharSequence label, CharSequence value, boolean checked) {
        Context c = getContext();
        Theme t = Theme.now;
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        int h = Ui.dp(c, 16);
        row.setPadding(h, Ui.dp(c, 12), h, Ui.dp(c, 12));
        row.setFocusable(true);
        row.setClickable(true);
        row.setBackground(rowBackground(c));
        row.setOutlineProvider(Ui.rounded(Ui.dp(c, 14)));
        int icon = Ui.dp(c, 22);
        if (glyph != 0) {
            ImageView g = new ImageView(c);
            g.setImageDrawable(new Glyph(glyph, t.panelText));
            LinearLayout.LayoutParams gl = new LinearLayout.LayoutParams(icon, icon);
            gl.rightMargin = Ui.dp(c, 14);
            row.addView(g, gl);
        }
        TextView l = Ui.text(c, 18, t.panelText, Ui.REGULAR);
        l.setText(label);
        row.addView(l, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView v = Ui.text(c, 16, t.panelMuted, Ui.REGULAR);
        v.setTag("value");
        if (value != null) v.setText(value);
        LinearLayout.LayoutParams vl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        vl.leftMargin = Ui.dp(c, 12);
        row.addView(v, vl);
        if (checked) {
            ImageView g = new ImageView(c);
            g.setImageDrawable(new Glyph(Glyph.CHECK, t.light ? 0xFF0A84FF : 0xFFFFFFFF));
            LinearLayout.LayoutParams gl = new LinearLayout.LayoutParams(icon, icon);
            gl.leftMargin = Ui.dp(c, 10);
            row.addView(g, gl);
        }
        row.setOnFocusChangeListener((x, has) -> {
            if (has) lastFocus = x;
            // The lit row lifts off the glass a little, like a tvOS menu item.
            x.animate().scaleX(has ? 1.03f : 1f).scaleY(has ? 1.03f : 1f)
                    .translationZ(has ? Ui.dp(c, 4) : 0f)
                    .setInterpolator(has ? Ui.POP : Ui.EASE).setDuration(200).start();
        });
        list.addView(row, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        return row;
    }

    /** The focused-row highlight used by every menu: a soft lit pill. */
    static StateListDrawable rowBackground(Context c) {
        Theme t = Theme.now;
        StateListDrawable bg = new StateListDrawable();
        GradientDrawable on = new GradientDrawable();
        on.setCornerRadius(Ui.dp(c, 14));
        on.setColor(t.rowFocus);
        on.setStroke(Ui.dp(c, 1), t.rowFocusRim);
        bg.addState(new int[] {android.R.attr.state_focused}, on);
        bg.addState(new int[] {}, new GradientDrawable());
        bg.setEnterFadeDuration(110);
        bg.setExitFadeDuration(150);
        return bg;
    }

    /** Adds the sheet to {@code root} with a soft, springy entrance and focuses its first row. */
    void show(ViewGroup root, Runnable onClose) {
        this.onClose = onClose;
        root.addView(this, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        setAlpha(0f);
        animate().alpha(1f).setDuration(160).start();
        panel.setScaleX(0.92f);
        panel.setScaleY(0.92f);
        panel.animate().scaleX(1f).scaleY(1f).setInterpolator(new android.view.animation.OvershootInterpolator(1.2f))
                .setDuration(300).start();
        focusFirst();
    }

    void focusFirst() {
        if (lastFocus != null && lastFocus.getParent() != null) {
            lastFocus.requestFocus();
            return;
        }
        for (int i = 0; i < list.getChildCount(); i++) {
            View v = list.getChildAt(i);
            if (v.isFocusable()) {
                v.requestFocus();
                return;
            }
            if (v instanceof ViewGroup) {
                ArrayList<View> f = new ArrayList<>();
                v.addFocusables(f, View.FOCUS_DOWN);
                if (!f.isEmpty()) {
                    f.get(0).requestFocus();
                    return;
                }
            }
        }
    }

    /** While a menu opened from this one is showing: hidden and out of the focus order. */
    void cover(boolean covered) {
        setDescendantFocusability(covered ? FOCUS_BLOCK_DESCENDANTS : FOCUS_AFTER_DESCENDANTS);
        animate().alpha(covered ? 0f : 1f).setDuration(covered ? 120 : 180).start();
        if (!covered) {
            for (Runnable r : refreshers) r.run();
            focusFirst();
        }
    }

    boolean isClosing() {
        return closing;
    }

    void close() {
        if (closing) return;
        closing = true;
        setDescendantFocusability(FOCUS_BLOCK_DESCENDANTS);
        final ViewGroup parent = (ViewGroup) getParent();
        animate().alpha(0f).setDuration(140).setInterpolator(Ui.EASE)
                .withEndAction(() -> {
                    if (parent != null) parent.removeView(this);
                }).start();
        if (onClose != null) onClose.run();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        if (e.getKeyCode() == KeyEvent.KEYCODE_MENU && tileMenu != null && findFocus() instanceof Tile) {
            if (e.getAction() == KeyEvent.ACTION_UP && sawDown) tileMenu.open((Tile) findFocus());
            if (e.getAction() == KeyEvent.ACTION_DOWN) sawDown = true;
            return true;
        }
        if (e.getKeyCode() == KeyEvent.KEYCODE_BACK || e.getKeyCode() == KeyEvent.KEYCODE_MENU) {
            // The press that opened the sheet ends here; only a new press closes it.
            if (e.getAction() == KeyEvent.ACTION_DOWN) sawDown = true;
            else if (e.getAction() == KeyEvent.ACTION_UP && sawDown) close();
            return true;
        }
        return super.dispatchKeyEvent(e);
    }
}
