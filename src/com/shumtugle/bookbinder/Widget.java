package com.shumtugle.bookbinder;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.TextPaint;
import android.text.TextUtils;
import android.widget.RemoteViews;

/**
 * The book on the home screen. The whole face is one picture, drawn to the
 * exact size of the widget's cell; over it lie clear touch zones cut in the
 * same proportions as the keys drawn on the picture, so a key and its zone
 * always meet. Low and wide, it is a line: cover, title, chapter, three
 * keys. Taller, it is a card: a big cover on the left, the words and the
 * keys on the right.
 *
 * It is drawn only when something changes — the book, the chapter, the
 * sound starting or stopping, a cover arriving, the size — and every time
 * the language and the colours are read from the settings, because the
 * widget is often drawn in a process started for nothing else.
 */
public final class Widget extends AppWidgetProvider {

    /* The zones' proportions; the layouts hold the same numbers as weights. */
    private static final float LINE_OPEN = 0.55f;
    private static final float CARD_COVER = 0.40f;
    private static final int CARD_KEYS_DP = 64;
    /** From this height on the widget is a card rather than a line. */
    private static final int CARD_FROM_DP = 120;

    @Override
    public void onUpdate(Context c, AppWidgetManager m, int[] ids) {
        for (int id : ids) {
            draw(c, m, id);
        }
    }

    @Override
    public void onAppWidgetOptionsChanged(Context c, AppWidgetManager m, int id, Bundle options) {
        draw(c, m, id);
    }

    /** Draws every widget of the application again. */
    static void refresh(Context c) {
        try {
            AppWidgetManager m = AppWidgetManager.getInstance(c);
            for (int id : m.getAppWidgetIds(new ComponentName(c, Widget.class))) {
                draw(c, m, id);
            }
            for (int id : m.getAppWidgetIds(new ComponentName(c, Button.class))) {
                drawButton(c, m, id);
            }
        } catch (Exception e) {
            Log.note("widget: " + e);
        }
    }

    /**
     * The smallest widget: one cell, one key. Play or pause on a disc, and
     * round it a thin ring of how far the book has been heard. Without a
     * book, it opens the application.
     */
    public static final class Button extends AppWidgetProvider {
        @Override
        public void onUpdate(Context c, AppWidgetManager m, int[] ids) {
            for (int id : ids) {
                drawButton(c, m, id);
            }
        }

        @Override
        public void onAppWidgetOptionsChanged(Context c, AppWidgetManager m, int id, Bundle options) {
            drawButton(c, m, id);
        }
    }

    private static void drawButton(Context c, AppWidgetManager m, int id) {
        Words.load(c);
        Bundle o = m.getAppWidgetOptions(id);
        int wDp = Math.max(40, o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 60));
        int hDp = Math.max(40, o.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 60));
        float d = c.getResources().getDisplayMetrics().density;
        int w = Math.round(wDp * d);
        int h = Math.round(hDp * d);
        int[] col = Look.colors(Look.dark(c));
        State s = state(c);
        Bitmap bm = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(bm);
        float side = Math.min(w, h);
        float cx = w / 2f;
        float cy = h / 2f;
        /* The ground is a circle here: one key wants one round shape. */
        Paint g = new Paint(Paint.ANTI_ALIAS_FLAG);
        g.setColor(withAlpha(col[Look.CARD], ground(c)));
        cv.drawCircle(cx, cy, side / 2f - d, g);
        float ring = side * 0.40f;
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(Math.max(2f, side * 0.045f));
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setColor(col[Look.LINE]);
        RectF arc = new RectF(cx - ring, cy - ring, cx + ring, cy + ring);
        cv.drawArc(arc, 0, 360, false, p);
        if (s.book != null && s.done > 0) {
            p.setColor(col[Look.ACCENT]);
            cv.drawArc(arc, -90, (float) (360 * Math.min(1, s.done)), false, p);
        }
        Paint disc = new Paint(Paint.ANTI_ALIAS_FLAG);
        disc.setColor(col[Look.ACCENT]);
        float r = side * 0.30f;
        cv.drawCircle(cx, cy, r, disc);
        Mark.draw(cv, s.playing ? Mark.PAUSE : Mark.PLAY, cx, cy, r * 1.15f, col[Look.CARD]);
        RemoteViews v = new RemoteViews(c.getPackageName(), R.layout.widget_button);
        v.setImageViewBitmap(R.id.face, bm);
        v.setOnClickPendingIntent(R.id.face, s.book == null ? openBook(c, null) : key(c, Play.TOGGLE, 14));
        m.updateAppWidget(id, v);
    }

    /** How much of the ground shows, 0 to 100, from the settings. */
    private static int ground(Context c) {
        return Places.widgetGround(c);
    }

    private static int withAlpha(int color, int percent) {
        int a = Math.round(((color >>> 24) & 0xFF) * Math.max(0, Math.min(100, percent)) / 100f);
        return (a << 24) | (color & 0xFFFFFF);
    }

    private static void draw(Context c, AppWidgetManager m, int id) {
        Words.load(c);
        Bundle o = m.getAppWidgetOptions(id);
        /* Width from the narrow end, height from the tall one: the portrait cell. */
        int wDp = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 250);
        int hDp = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 60);
        if (wDp <= 0) {
            wDp = 250;
        }
        if (hDp <= 0) {
            hDp = 60;
        }
        boolean card = hDp >= CARD_FROM_DP;
        float d = c.getResources().getDisplayMetrics().density;
        int w = Math.max(1, Math.round(wDp * d));
        int h = Math.max(1, Math.round(hDp * d));
        /* A picture larger than the system allows a widget is refused whole. */
        float cap = (float) Math.sqrt(2_000_000.0 / (w * (double) h));
        if (cap < 1f) {
            w = Math.round(w * cap);
            h = Math.round(h * cap);
            d *= cap;
        }
        RemoteViews v = new RemoteViews(c.getPackageName(), card ? R.layout.widget_card : R.layout.widget_line);
        v.setImageViewBitmap(R.id.face, card ? cardFace(c, w, h, d) : lineFace(c, w, h, d));
        State s = state(c);
        PendingIntent open = openBook(c, s.book);
        v.setOnClickPendingIntent(R.id.open, open);
        if (card) {
            v.setOnClickPendingIntent(R.id.open2, open);
        }
        if (s.book == null) {
            v.setOnClickPendingIntent(R.id.back, open);
            v.setOnClickPendingIntent(R.id.play, open);
            v.setOnClickPendingIntent(R.id.on, open);
        } else {
            v.setOnClickPendingIntent(R.id.back, key(c, Play.BACK, 11));
            v.setOnClickPendingIntent(R.id.play, key(c, Play.TOGGLE, 12));
            v.setOnClickPendingIntent(R.id.on, key(c, Play.ON, 13));
        }
        m.updateAppWidget(id, v);
    }

    private static PendingIntent key(Context c, String act, int code) {
        Intent i = new Intent(c, Play.class).setAction(act);
        return PendingIntent.getForegroundService(c, code, i,
            PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private static PendingIntent openBook(Context c, Shelf.Book b) {
        Intent i = new Intent(c, Main.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        if (b != null) {
            i.putExtra(Main.SHOW_BOOK, b.id);
        }
        return PendingIntent.getActivity(c, 10, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    // ------------------------------------------------------------ what to show

    private static final class State {
        Shelf.Book book;
        int index;
        boolean playing;
        double done;
    }

    /** The book playing, or else the one heard last; how far, and whether it sounds. */
    private static State state(Context c) {
        State s = new State();
        Library lib = Library.get(c);
        Play p = Play.live();
        if (p != null && p.book() != null) {
            s.book = p.book();
            s.index = p.index();
            s.playing = p.meant();
            long dur = p.duration();
            s.done = s.book.done(s.index, dur > 0 ? p.position() / (double) dur : 0);
        } else {
            long last = Places.last(c);
            s.book = last >= 0 ? lib.book(last) : null;
            if (s.book != null) {
                s.index = Math.max(0, s.book.find(Places.chapter(c, s.book.id)));
                s.done = Places.finished(c, s.book.id) ? 1 : s.book.done(s.index, 0);
            }
        }
        return s;
    }

    // ------------------------------------------------------------ drawing

    private static Bitmap lineFace(Context c, int w, int h, float d) {
        int[] col = Look.colors(Look.dark(c));
        State s = state(c);
        Bitmap bm = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(bm);
        float pad = 8 * d;
        ground(cv, w, h, Math.min(22 * d, h / 2f), col, ground(c));
        float side = h - 2 * pad;
        float openW = w * LINE_OPEN;
        cover(c, cv, s.book, pad, pad, side, 10 * d, col);
        float tx = pad + side + 10 * d;
        float tw = openW - tx - 4 * d;
        float sp = c.getResources().getDisplayMetrics().scaledDensity * (d / c.getResources().getDisplayMetrics().density);
        String title = s.book == null ? Words.s("widget_empty") : name(s.book);
        String sub = s.book == null ? Main.APP : Words.f("chapter_of", s.index + 1, s.book.chapters.size());
        /* Title, chapter and the line of progress stand as one block in the middle;
           in a cell too low for all three the line goes first. */
        float titleH = 14 * sp * 1.15f;
        float subH = 12 * sp * 1.15f;
        float gap = 3 * d;
        float barGap = 6 * d;
        float barH = 3 * d;
        boolean withBar = s.book != null && titleH + gap + subH + barGap + barH <= h - 2 * pad;
        float block = titleH + gap + subH + (withBar ? barGap + barH : 0);
        float top = (h - block) / 2f;
        float base = top + 14 * sp * 0.92f;
        text(cv, title, tx, base, tw, 14 * sp, col[Look.INK], true);
        float subBase = top + titleH + gap + 12 * sp * 0.92f;
        text(cv, sub, tx, subBase, tw, 12 * sp, col[Look.SOFT], false);
        if (withBar) {
            bar(cv, tx, top + titleH + gap + subH + barGap, tw, barH, s.done, col);
        }
        float zw = (w - openW) / 3f;
        keys(cv, openW, 0, zw, h, s.playing, d, col);
        return bm;
    }

    private static Bitmap cardFace(Context c, int w, int h, float d) {
        int[] col = Look.colors(Look.dark(c));
        State s = state(c);
        Bitmap bm = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(bm);
        float pad = 10 * d;
        ground(cv, w, h, 24 * d, col, ground(c));
        float leftW = w * CARD_COVER;
        float side = Math.min(leftW - 2 * pad, h - 2 * pad);
        cover(c, cv, s.book, (leftW - side) / 2f + pad / 2f, (h - side) / 2f, side, 14 * d, col);
        float keysH = CARD_KEYS_DP * d;
        float x0 = leftW + 4 * d;
        float tw = w - x0 - pad;
        float sp = c.getResources().getDisplayMetrics().scaledDensity * (d / c.getResources().getDisplayMetrics().density);
        float y = pad + 16 * sp;
        if (s.book == null) {
            text(cv, Words.s("widget_empty"), x0, y, tw, 16 * sp, col[Look.INK], true);
            text(cv, Main.APP, x0, y + 20 * sp, tw, 12 * sp, col[Look.SOFT], false);
        } else {
            /* Two lines of title only where the card is tall enough to keep the chapter too. */
            String[] lines = wrap(name(s.book), tw, 16 * sp, true, h / d >= 150 ? 2 : 1);
            for (String line : lines) {
                text(cv, line, x0, y, tw, 16 * sp, col[Look.INK], true);
                y += 20 * sp;
            }
            String who = s.book.author.isEmpty() ? s.book.reader : s.book.author;
            if (!who.isEmpty() && y + 14 * sp < h - keysH - 10 * d) {
                text(cv, who, x0, y, tw, 12 * sp, col[Look.SOFT], false);
                y += 18 * sp;
            }
            if (y + 6 * sp < h - keysH - 6 * d) {
                text(cv, Words.f("chapter_of", s.index + 1, s.book.chapters.size()), x0, y, tw, 13 * sp,
                    col[Look.ACCENT], true);
            }
            bar(cv, x0, h - keysH - 4 * d, tw, 3 * d, s.done, col);
        }
        float zw = (w - leftW) / 3f;
        keys(cv, leftW, h - keysH, zw, keysH, s.playing, d, col);
        return bm;
    }

    /** The card under the face, as solid as the settings say; with no ground at all, no edge either. */
    private static void ground(Canvas cv, int w, int h, float r, int[] col, int percent) {
        if (percent <= 0) {
            return;
        }
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(withAlpha(col[Look.CARD], percent));
        cv.drawRoundRect(new RectF(0, 0, w, h), r, r, p);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(Math.max(1f, h / 300f));
        p.setColor(withAlpha(col[Look.LINE], percent));
        cv.drawRoundRect(new RectF(0.5f, 0.5f, w - 0.5f, h - 0.5f), r, r, p);
    }

    /** The cover, cropped to a rounded square; the first letter of the title until it comes. */
    private static void cover(Context c, Canvas cv, Shelf.Book b, float x, float y, float side, float r, int[] col) {
        RectF box = new RectF(x, y, x + side, y + side);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        Bitmap art = b == null ? null : Library.get(c).cover(b);
        if (art != null) {
            /* No recycling: the same picture lives in the application's window. */
            BitmapShader sh = new BitmapShader(art, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            float k = side / Math.min(art.getWidth(), art.getHeight());
            Matrix mx = new Matrix();
            mx.setScale(k, k);
            mx.postTranslate(x - (art.getWidth() * k - side) / 2f, y - (art.getHeight() * k - side) / 2f);
            sh.setLocalMatrix(mx);
            p.setShader(sh);
            cv.drawRoundRect(box, r, r, p);
            return;
        }
        p.setColor(col[Look.ACCENT_PALE]);
        cv.drawRoundRect(box, r, r, p);
        String t = b == null ? "" : name(b);
        String letter = t.isEmpty() ? "" : t.substring(0, 1).toUpperCase(java.util.Locale.ROOT);
        if (!letter.isEmpty()) {
            Paint tp = new Paint(Paint.ANTI_ALIAS_FLAG);
            tp.setColor(col[Look.ACCENT]);
            tp.setTextAlign(Paint.Align.CENTER);
            tp.setTypeface(Typeface.create("serif", Typeface.NORMAL));
            tp.setTextSize(side * 0.46f);
            cv.drawText(letter, x + side / 2f, y + side / 2f - (tp.descent() + tp.ascent()) / 2f, tp);
        } else {
            Mark.draw(cv, Mark.PLAY, x + side / 2f, y + side / 2f, side * 0.5f, col[Look.ACCENT]);
        }
    }

    /** Back, play or pause on a disc, forward — each centred in its third of the zone. */
    private static void keys(Canvas cv, float x0, float y0, float zw, float zh, boolean playing, float d, int[] col) {
        float cy = y0 + zh / 2f;
        float small = Math.min(zw, zh) * 0.56f;
        Mark.draw(cv, Mark.BACK, x0 + zw * 0.5f, cy, small, col[Look.INK]);
        float r = Math.min(zw, zh) * 0.36f;
        Paint disc = new Paint(Paint.ANTI_ALIAS_FLAG);
        disc.setColor(col[Look.ACCENT]);
        cv.drawCircle(x0 + zw * 1.5f, cy, r, disc);
        Mark.draw(cv, playing ? Mark.PAUSE : Mark.PLAY, x0 + zw * 1.5f, cy, r * 1.15f, col[Look.CARD]);
        Mark.draw(cv, Mark.ON, x0 + zw * 2.5f, cy, small, col[Look.INK]);
    }

    private static void bar(Canvas cv, float x, float y, float w, float h, double done, int[] col) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(col[Look.LINE]);
        cv.drawRoundRect(new RectF(x, y, x + w, y + h), h / 2f, h / 2f, p);
        if (done > 0) {
            p.setColor(col[Look.ACCENT]);
            cv.drawRoundRect(new RectF(x, y, x + (float) (w * Math.min(1, done)), y + h), h / 2f, h / 2f, p);
        }
    }

    private static TextPaint pen(float size, int color, boolean strong) {
        TextPaint p = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(color);
        p.setTextSize(size);
        p.setTypeface(Typeface.create(strong ? "sans-serif-medium" : "sans-serif", Typeface.NORMAL));
        return p;
    }

    private static void text(Canvas cv, String s, float x, float y, float w, float size, int color, boolean strong) {
        TextPaint p = pen(size, color, strong);
        cv.drawText(TextUtils.ellipsize(s, p, Math.max(0, w), TextUtils.TruncateAt.END).toString(), x, y, p);
    }

    /** Breaks a title into at most the given number of lines, the last one shortened with an ellipsis. */
    private static String[] wrap(String s, float w, float size, boolean strong, int most) {
        TextPaint p = pen(size, 0, strong);
        java.util.ArrayList<String> out = new java.util.ArrayList<String>();
        String rest = s;
        while (!rest.isEmpty() && out.size() < most - 1) {
            int n = p.breakText(rest, true, w, null);
            if (n >= rest.length()) {
                break;
            }
            int cut = rest.lastIndexOf(' ', n);
            if (cut <= 0) {
                cut = n;
            }
            out.add(rest.substring(0, cut).trim());
            rest = rest.substring(cut).trim();
        }
        if (!rest.isEmpty()) {
            out.add(rest);
        }
        return out.toArray(new String[0]);
    }

    private static String name(Shelf.Book b) {
        return b.title.isEmpty() ? b.folder : b.title;
    }
}
