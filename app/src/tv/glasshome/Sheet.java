package tv.glasshome;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
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

/**
 * A floating glass menu over a dimmed screen: a title and a list of choices. BACK closes it.
 * Used for an app's options, the HDMI inputs and the edit menu.
 */
final class Sheet extends FrameLayout {
    interface Choice {
        void run();
    }

    private final LinearLayout list;
    private final Glass.Frame panel;
    private Runnable onClose;
    private boolean closing, sawDown;

    Sheet(Context c, CharSequence title, CharSequence subtitle) {
        super(c);
        setBackgroundColor(0x8C02030A);
        setClickable(true);
        panel = new Glass.Frame(c, 28);
        panel.glass().bodyAlpha = 0x1C;
        panel.glass().sheenAlpha = 0x38;
        int pad = Ui.dp(c, 22);
        panel.setPadding(pad, pad, pad, Ui.dp(c, 14));
        LinearLayout col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);
        TextView t = Ui.text(c, 24, Ui.TEXT, Ui.LIGHT);
        t.setText(title);
        t.setPadding(Ui.dp(c, 8), 0, Ui.dp(c, 8), 0);
        col.addView(t);
        if (subtitle != null) {
            TextView s = Ui.text(c, 14, Ui.MUTED, Ui.REGULAR);
            s.setText(subtitle);
            s.setPadding(Ui.dp(c, 8), Ui.dp(c, 6), Ui.dp(c, 8), 0);
            col.addView(s);
        }
        list = new LinearLayout(c);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(0, Ui.dp(c, 14), 0, 0);
        final int maxH = Ui.dp(c, 380);
        ScrollView scroll = new ScrollView(c) {
            @Override
            protected void onMeasure(int w, int h) {
                super.onMeasure(w, MeasureSpec.makeMeasureSpec(maxH, MeasureSpec.AT_MOST));
            }
        };
        scroll.setVerticalScrollBarEnabled(false);
        scroll.addView(list);
        col.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        panel.addView(col);
        LayoutParams lp = new LayoutParams(Ui.dp(c, 420), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
        addView(panel, lp);
    }

    Sheet add(CharSequence label, Choice choice) {
        Context c = getContext();
        TextView row = Ui.text(c, 18, Ui.TEXT, Ui.REGULAR);
        row.setText(label);
        row.setGravity(Gravity.CENTER_VERTICAL);
        int h = Ui.dp(c, 16);
        row.setPadding(h, Ui.dp(c, 12), h, Ui.dp(c, 12));
        row.setFocusable(true);
        row.setClickable(true);
        StateListDrawable bg = new StateListDrawable();
        GradientDrawable on = new GradientDrawable();
        on.setCornerRadius(Ui.dp(c, 14));
        on.setColor(0x2EFFFFFF);
        on.setStroke(Ui.dp(c, 1), 0x66FFFFFF);
        bg.addState(new int[] {android.R.attr.state_focused}, on);
        bg.addState(new int[] {}, new GradientDrawable());
        bg.setEnterFadeDuration(120);
        bg.setExitFadeDuration(160);
        row.setBackground(bg);
        row.setOnClickListener(v -> {
            close();
            choice.run();
        });
        list.addView(row, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        return this;
    }

    Sheet note(CharSequence text) {
        TextView t = Ui.text(getContext(), 15, Ui.MUTED, Ui.REGULAR);
        t.setSingleLine(false);
        t.setText(text);
        int h = Ui.dp(getContext(), 16);
        t.setPadding(h, Ui.dp(getContext(), 8), h, Ui.dp(getContext(), 8));
        list.addView(t);
        return this;
    }

    /** Adds the sheet to {@code root} with a soft, springy entrance and focuses its first choice. */
    void show(ViewGroup root, Runnable onClose) {
        this.onClose = onClose;
        root.addView(this, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        setAlpha(0f);
        animate().alpha(1f).setDuration(160).start();
        panel.setScaleX(0.92f);
        panel.setScaleY(0.92f);
        panel.animate().scaleX(1f).scaleY(1f).setInterpolator(new OvershootInterpolator(1.2f))
                .setDuration(300).start();
        for (int i = 0; i < list.getChildCount(); i++) {
            View v = list.getChildAt(i);
            if (v.isFocusable()) {
                v.requestFocus();
                break;
            }
        }
    }

    void close() {
        if (closing) return;
        closing = true;
        final ViewGroup parent = (ViewGroup) getParent();
        animate().alpha(0f).setDuration(140).setInterpolator(new DecelerateInterpolator())
                .withEndAction(() -> {
                    if (parent != null) parent.removeView(this);
                }).start();
        if (onClose != null) onClose.run();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        if (e.getKeyCode() == KeyEvent.KEYCODE_BACK || e.getKeyCode() == KeyEvent.KEYCODE_MENU) {
            // The press that opened the sheet ends here; only a new press closes it.
            if (e.getAction() == KeyEvent.ACTION_DOWN) sawDown = true;
            else if (e.getAction() == KeyEvent.ACTION_UP && sawDown) close();
            return true;
        }
        return super.dispatchKeyEvent(e);
    }
}
