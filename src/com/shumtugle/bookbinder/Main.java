package com.shumtugle.bookbinder;

import android.Manifest;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.view.WindowInsets;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Locale;

/**
 * Two screens in one window, built in code. The library: the books of the
 * shared folder, each with its cover and how far it has been heard. The
 * book: its cover, the keys, and its chapters in the order of their
 * numbers. Light, whatever the phone's theme: a page, not a night sky.
 */
public final class Main extends Activity implements Library.Watch, Play.Ear {

    static final String VERSION = "0.4";

    /* The page: light by default, dark on request or with the phone. */
    static int PAPER;
    static int CARD;
    static int INK;
    static int SOFT;
    static int LINE;
    static int ACCENT;
    static int ACCENT_PALE;
    static boolean dark;

    /** The application's name, as the widget says it. */
    static final String APP = "Bookbinder";
    /** A book to open at once, from the widget. */
    static final String SHOW_BOOK = "show_book";

    private void paint() {
        dark = Look.dark(this);
        int[] col = Look.colors(dark);
        PAPER = col[Look.PAPER];
        CARD = col[Look.CARD];
        INK = col[Look.INK];
        SOFT = col[Look.SOFT];
        LINE = col[Look.LINE];
        ACCENT = col[Look.ACCENT];
        ACCENT_PALE = col[Look.ACCENT_PALE];
        if (frame != null) {
            frame.setBackgroundColor(PAPER);
        }
        getWindow().getDecorView().setBackgroundColor(PAPER);
        int flags = 0;
        if (!dark) {
            flags = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            if (Build.VERSION.SDK_INT >= 27) {
                flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            }
        }
        getWindow().getDecorView().setSystemUiVisibility(flags);
    }

    private int dialogTheme() {
        return dark ? android.R.style.Theme_DeviceDefault_Dialog_Alert
            : android.R.style.Theme_DeviceDefault_Light_Dialog_Alert;
    }

    @Override
    public void onConfigurationChanged(Configuration c) {
        super.onConfigurationChanged(c);
        if ("system".equals(Places.theme(this))) {
            rebuild();
        }
    }

    /** Builds every screen again — after the theme or the language changed — and comes back to where it was. */
    private void rebuild() {
        Widget.refresh(this);
        Shelf.Book was = shown;
        boolean inSettings = settingsOn;
        build();
        if (inSettings) {
            showSettings();
        } else if (was != null) {
            shown = null;
            showBook(was);
        } else {
            fillLibrary();
        }
    }

    private final Handler ui = new Handler(Looper.getMainLooper());
    private Library lib;

    private FrameLayout frame;
    private LinearLayout libraryView;
    private LinearLayout shelfList;
    private TextView status;
    private LinearLayout mini;
    private View linkOpen;
    private LinearLayout settingsView;
    private LinearLayout settingsList;
    private boolean settingsOn;
    private final java.util.HashMap<Long, Bitmap> blanks = new java.util.HashMap<Long, Bitmap>();

    private LinearLayout bookView;
    private Shelf.Book shown;
    private ImageView bookCover;
    private TextView bookTitle;
    private TextView bookWho;
    private TextView bookChapter;
    private TextView bookState;
    private SeekBar seek;
    private TextView timeHere;
    private TextView timeAll;
    private Mark.Key playKey;
    private TextView paceKey;
    private TextView sleepKey;
    private LinearLayout chapterList;
    private boolean dragging;

    // ------------------------------------------------------------ life

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        Words.load(this);
        lib = Library.get(this);
        frame = new FrameLayout(this);
        frame.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
            @Override
            public WindowInsets onApplyWindowInsets(View v, WindowInsets in) {
                int l;
                int t;
                int r;
                int b;
                if (Build.VERSION.SDK_INT >= 30) {
                    android.graphics.Insets s = in.getInsets(WindowInsets.Type.systemBars()
                        | WindowInsets.Type.displayCutout() | WindowInsets.Type.ime());
                    l = s.left;
                    t = s.top;
                    r = s.right;
                    b = s.bottom;
                } else {
                    l = in.getSystemWindowInsetLeft();
                    t = in.getSystemWindowInsetTop();
                    r = in.getSystemWindowInsetRight();
                    b = in.getSystemWindowInsetBottom();
                }
                v.setPadding(l, t, r, b);
                return in;
            }
        });
        setContentView(frame);
        if (Build.VERSION.SDK_INT >= 29) {
            getWindow().setStatusBarColor(Color.TRANSPARENT);
            getWindow().setNavigationBarColor(Color.TRANSPARENT);
        }
        build();
        long open = state == null ? -1 : state.getLong("book", -1);
        if (open >= 0 && lib.book(open) != null) {
            showBook(lib.book(open));
        }
        if (state == null) {
            take(getIntent());
        }
        askStorage();
    }

    @Override
    protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        take(i);
    }

    /** A link shared to the application, or opened with it, is a book put on the shelf. */
    private void take(Intent i) {
        if (i == null) {
            return;
        }
        long show = i.getLongExtra(SHOW_BOOK, -1);
        if (show != -1 && lib.book(show) != null) {
            i.removeExtra(SHOW_BOOK);
            settingsOn = false;
            settingsView.setVisibility(View.GONE);
            showBook(lib.book(show));
            return;
        }
        String text = null;
        if (Intent.ACTION_SEND.equals(i.getAction())) {
            text = i.getStringExtra(Intent.EXTRA_TEXT);
        } else if (Intent.ACTION_VIEW.equals(i.getAction()) && i.getData() != null) {
            text = i.getData().toString();
        }
        if (text != null) {
            put(text, null);
        }
    }

    /** Puts a link on the shelf and, when the cloud has answered, says what came of it. */
    private void put(String link, final String name) {
        if (shown != null) {
            showLibrary();
        }
        boolean ok = lib.add(link, new Library.Answer() {
            @Override
            public void told(int state, String detail, int books, int chapters) {
                String title = name != null && !name.isEmpty() ? name : "Bookbinder";
                say(title, state == Library.ADDED
                    ? (books > 1 ? Words.f("r_added_many", books, chapters) : Words.f("r_added", chapters))
                    : refusal(state, detail));
            }
        });
        if (!ok) {
            say("Bookbinder", Words.s("link_bad"));
            return;
        }
        Toast.makeText(this, Words.s("asking"), Toast.LENGTH_SHORT).show();
        askStorage();
    }

    /** Why a link does not work, in the listener's words. */
    private static String refusal(int state, String detail) {
        switch (state) {
            case Library.OFFLINE:
                return Words.s("r_offline");
            case Library.DEAD:
                return Words.s("r_dead");
            case Library.EXPIRED:
                return Words.s("r_expired");
            case Library.SPENT:
                return Words.s("r_spent");
            case Library.EMPTY:
                return Words.s("r_empty");
            case Library.LATER:
                return Words.s("r_later");
            default:
                return Words.f("r_refused", detail);
        }
    }

    private void say(String title, String text) {
        if (isFinishing()) {
            return;
        }
        new android.app.AlertDialog.Builder(this, dialogTheme())
            .setTitle(title)
            .setMessage(text)
            .setPositiveButton(Words.s("ok"), null)
            .show();
    }

    /**
     * Older systems need the storage permission to write the books at all;
     * newer ones only to find books written before a reinstall. Asked once.
     */
    private void askStorage() {
        String perm = Store.readPermission();
        if (checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED) {
            return;
        }
        android.content.SharedPreferences p = getSharedPreferences("bookbinder", MODE_PRIVATE);
        if (!Store.needsPermission(this) && p.getBoolean("askedStorage", false)) {
            return;
        }
        p.edit().putBoolean("askedStorage", true).apply();
        if (Store.needsPermission(this)) {
            Toast.makeText(this, Words.s("storage_why"), Toast.LENGTH_LONG).show();
        }
        requestPermissions(new String[]{perm}, 2);
    }

    @Override
    protected void onActivityResult(int code, int result, Intent data) {
        super.onActivityResult(code, result, data);
        if (code == 6) {
            if (result == RESULT_OK && data != null && data.getData() != null) {
                android.net.Uri tree = data.getData();
                try {
                    getContentResolver().takePersistableUriPermission(tree,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                } catch (SecurityException readOnly) {
                    getContentResolver().takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                }
                lib.addFolder(tree.toString());
                fillSettings();
            }
            return;
        }
        if (code == 3 && result == RESULT_OK && deleting != null) {
            Play p = Play.live();
            if (p != null && p.book() != null && p.book().id == deleting.id) {
                p.close();
            }
            lib.forget(deleting);
            showLibrary();
        }
        deleting = null;
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        super.onRequestPermissionsResult(code, perms, results);
        boolean granted = results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED;
        if (code == 2 || code == 4 && granted) {
            lib.refresh();
            lib.look();
            if (granted) {
                lib.unblock();
            }
        } else if (code == 4) {
            /* Refused for good earlier: only the application's settings can give it now. */
            Intent settings = new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                android.net.Uri.fromParts("package", getPackageName(), null));
            startActivity(settings);
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        if (shown != null) {
            out.putLong("book", shown.id);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        lib.watch(this);
        Play.listen(this);
        /* The window lives for days; the folder may have grown since it was last asked. */
        lib.refreshIfOlder(10 * 60 * 1000L);
        lib.check();
        if (lib.blocked() && checkSelfPermission(Store.readPermission()) == PackageManager.PERMISSION_GRANTED) {
            lib.unblock();
        }
        lib.pump();
        fillLibrary();
        if (shown != null) {
            fillBook();
        }
        ui.post(tick);
    }

    @Override
    protected void onPause() {
        super.onPause();
        lib.unwatch(this);
        Play.deafen(this);
        ui.removeCallbacks(tick);
    }

    @Override
    public void onBackPressed() {
        if (shown != null || settingsOn) {
            showLibrary();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    public void changed() {
        if (settingsOn) {
            return;
        }
        fillLibrary();
        if (shown != null) {
            fillBook();
        }
    }

    @Override
    public void heard() {
        changed();
    }

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (shown != null) {
                fillTime();
            }
            fillMini();
            ui.postDelayed(this, 500L);
        }
    };

    private void build() {
        paint();
        frame.removeAllViews();
        linkOpen = null;
        settingsOn = false;
        libraryView = libraryScreen();
        bookView = bookScreen();
        settingsView = settingsScreen();
        frame.addView(libraryView, new FrameLayout.LayoutParams(-1, -1));
        frame.addView(bookView, new FrameLayout.LayoutParams(-1, -1));
        frame.addView(settingsView, new FrameLayout.LayoutParams(-1, -1));
        bookView.setVisibility(View.GONE);
        settingsView.setVisibility(View.GONE);
    }

    // ------------------------------------------------------------ the library

    private LinearLayout libraryScreen() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);

        LinearLayout head = row();
        head.setPadding(dp(20), dp(14), dp(10), dp(6));
        TextView name = text("Bookbinder", 30, INK, true);
        head.addView(name, new LinearLayout.LayoutParams(0, -2, 1));
        Mark.Key refresh = new Mark.Key(this, Mark.REFRESH, INK, 0);
        refresh.setContentDescription(Words.s("refresh"));
        refresh.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                lib.refresh();
            }
        });
        head.addView(refresh, new LinearLayout.LayoutParams(dp(48), dp(48)));
        final Mark.Key view = new Mark.Key(this, "covers".equals(Places.shelf(this)) ? Mark.LIST : Mark.GRID, INK, 0);
        view.setContentDescription(Words.s("shelf"));
        view.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                boolean covers = !"covers".equals(Places.shelf(Main.this));
                Places.shelf(Main.this, covers ? "covers" : "cards");
                view.set(covers ? Mark.LIST : Mark.GRID);
                fillLibrary();
            }
        });
        head.addView(view, new LinearLayout.LayoutParams(dp(48), dp(48)));
        Mark.Key gear = new Mark.Key(this, Mark.GEAR, INK, 0);
        gear.setContentDescription(Words.s("settings"));
        gear.scale(0.6f);
        gear.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showSettings();
            }
        });
        head.addView(gear, new LinearLayout.LayoutParams(dp(48), dp(48)));
        root.addView(head);

        status = text("", 14, SOFT, false);
        status.setPadding(dp(20), 0, dp(20), dp(6));
        root.addView(status);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        shelfList = new LinearLayout(this);
        shelfList.setOrientation(LinearLayout.VERTICAL);
        shelfList.setPadding(dp(14), dp(4), dp(14), dp(20));
        scroll.addView(shelfList);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));

        mini = row();
        mini.setPadding(dp(14), dp(10), dp(8), dp(10));
        GradientDrawable bar = new GradientDrawable();
        bar.setColor(CARD);
        bar.setStroke(dp(1), LINE);
        mini.setBackground(bar);
        root.addView(mini, new LinearLayout.LayoutParams(-1, -2));
        return root;
    }

    private void fillLibrary() {
        if (shelfList == null || shown != null) {
            return;
        }
        shelfList.removeAllViews();
        if (lib.asking()) {
            status.setText(Words.s("asking"));
            status.setVisibility(View.VISIBLE);
        } else if (lib.trouble() != null) {
            status.setText(Words.s("trouble") + " · " + lib.trouble());
            status.setVisibility(View.VISIBLE);
        } else {
            status.setVisibility(View.GONE);
        }
        if (lib.blocked()) {
            shelfList.addView(seeCard());
        }
        if (lib.books().isEmpty() && !lib.asking()) {
            /* An empty shelf says where books come from. */
            LinearLayout none = new LinearLayout(this);
            none.setOrientation(LinearLayout.VERTICAL);
            none.setPadding(dp(18), dp(18), dp(18), dp(18));
            none.setBackground(card());
            none.addView(text(Words.s("empty"), 17, INK, true));
            TextView how = text(Words.f("empty_hint", Store.shown()), 14, SOFT, false);
            how.setPadding(0, dp(6), 0, dp(12));
            none.addView(how);
            TextView go = pill(Words.s("settings"), true);
            go.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    showSettings();
                }
            });
            LinearLayout.LayoutParams gl = new LinearLayout.LayoutParams(-2, -2);
            gl.gravity = Gravity.END;
            none.addView(go, gl);
            shelfList.addView(none);
        }
        if ("covers".equals(Places.shelf(this))) {
            /* Big covers, two to a row. */
            java.util.ArrayList<Shelf.Book> all = ordered();
            for (int i = 0; i < all.size(); i += 2) {
                LinearLayout pair = row();
                pair.setGravity(Gravity.TOP);
                LinearLayout.LayoutParams pl = new LinearLayout.LayoutParams(-1, -2);
                pl.bottomMargin = dp(18);
                for (int k = 0; k < 2; k++) {
                    LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(0, -2, 1);
                    if (k == 0) {
                        cl.rightMargin = dp(7);
                    } else {
                        cl.leftMargin = dp(7);
                    }
                    if (i + k < all.size()) {
                        pair.addView(bookTile(all.get(i + k)), cl);
                    } else {
                        pair.addView(new View(this), cl);
                    }
                }
                shelfList.addView(pair, pl);
            }
        } else {
            for (Shelf.Book b : ordered()) {
                shelfList.addView(bookRow(b));
            }
        }
        fillMini();
    }

    /**
     * Books being heard come first, the latest on top; the rest keep the
     * folder's order. Folder names of one series are rarely written alike,
     * so guessing the series order would be wrong as often as right.
     */
    private java.util.ArrayList<Shelf.Book> ordered() {
        java.util.ArrayList<Shelf.Book> all = new java.util.ArrayList<Shelf.Book>(lib.books());
        java.util.Collections.sort(all, new java.util.Comparator<Shelf.Book>() {
            @Override
            public int compare(Shelf.Book a, Shelf.Book b) {
                long ta = Places.touched(Main.this, a.id);
                long tb = Places.touched(Main.this, b.id);
                return ta == tb ? 0 : (ta > tb ? -1 : 1);
            }
        });
        return all;
    }

    /** A book as a big cover, its title under it and a thin line of how far it has been heard. */
    private View bookTile(final Shelf.Book b) {
        LinearLayout t = new LinearLayout(this);
        t.setOrientation(LinearLayout.VERTICAL);
        final ImageView cover = new ImageView(this);
        cover.setScaleType(ImageView.ScaleType.CENTER_CROP);
        Bitmap art = lib.cover(b);
        int side = (getResources().getDisplayMetrics().widthPixels - dp(42)) / 2;
        cover.setImageBitmap(art != null ? art : placeholder(b, side));
        rounded(cover, 12);
        cover.setElevation(dp(3));
        t.addView(cover, new LinearLayout.LayoutParams(-1, side));
        double done = Places.finished(this, b.id) ? 1.0 : progressOf(b);
        ProgressBar bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(1000);
        bar.setProgress((int) Math.round(done * 1000));
        bar.setProgressTintList(android.content.res.ColorStateList.valueOf(ACCENT));
        bar.setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf(LINE));
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(-1, dp(3));
        bl.topMargin = dp(8);
        t.addView(bar, bl);
        TextView title = text(b.title.isEmpty() ? b.folder : b.title, 14, INK, true);
        title.setMaxLines(2);
        title.setEllipsize(TextUtils.TruncateAt.END);
        title.setPadding(0, dp(6), 0, 0);
        t.addView(title);
        String who = b.author.isEmpty() ? b.reader : b.author;
        if (!who.isEmpty()) {
            TextView w = text(who, 12, SOFT, false);
            w.setSingleLine(true);
            w.setEllipsize(TextUtils.TruncateAt.END);
            t.addView(w);
        }
        int link = lib.linkState(b);
        if (link != Library.ADDED) {
            t.addView(text(Words.s(link == Library.EXPIRED ? "link_expired"
                : link == Library.SPENT ? "link_spent" : "link_dead"), 12, 0xFFB3412E, false));
        }
        t.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showBook(b);
            }
        });
        return t;
    }

    private View bookRow(final Shelf.Book b) {
        LinearLayout r = row();
        r.setPadding(dp(10), dp(10), dp(12), dp(10));
        r.setBackground(card());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = dp(10);
        r.setLayoutParams(lp);

        r.addView(coverView(b, 64), new LinearLayout.LayoutParams(dp(64), dp(64)));

        LinearLayout words = new LinearLayout(this);
        words.setOrientation(LinearLayout.VERTICAL);
        words.setPadding(dp(14), 0, 0, 0);
        TextView t = text(b.title.isEmpty() ? b.folder : b.title, 16, INK, true);
        t.setMaxLines(2);
        t.setEllipsize(TextUtils.TruncateAt.END);
        words.addView(t);
        String who = who(b);
        if (!who.isEmpty()) {
            TextView w = text(who, 13, SOFT, false);
            w.setSingleLine(true);
            w.setEllipsize(TextUtils.TruncateAt.END);
            words.addView(w);
        }
        int at = chapterAt(b);
        double done = Places.finished(this, b.id) ? 1.0 : progressOf(b);
        TextView where = text(Places.finished(this, b.id) ? Words.s("finished")
            : Words.f("chapter_of", at + 1, b.chapters.size())
            + (done > 0 ? " · " + Math.round(done * 100) + "%" : ""), 13, ACCENT, false);
        where.setPadding(0, dp(4), 0, dp(4));
        words.addView(where);
        int link = lib.linkState(b);
        if (link != Library.ADDED) {
            TextView dead = text(Words.s(link == Library.EXPIRED ? "link_expired"
                : link == Library.SPENT ? "link_spent" : "link_dead"), 12, 0xFFB3412E, false);
            dead.setPadding(0, 0, 0, dp(4));
            words.addView(dead);
        }
        int got = lib.brought(b);
        if (got < b.chapters.size()) {
            TextView have = text(Words.f("brought", got, b.chapters.size()), 12, SOFT, false);
            have.setPadding(0, 0, 0, dp(4));
            words.addView(have);
        }
        ProgressBar bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(1000);
        bar.setProgress((int) Math.round(done * 1000));
        bar.setProgressTintList(android.content.res.ColorStateList.valueOf(ACCENT));
        bar.setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf(LINE));
        words.addView(bar, new LinearLayout.LayoutParams(-1, dp(4)));
        r.addView(words, new LinearLayout.LayoutParams(0, -2, 1));

        r.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showBook(b);
            }
        });
        return r;
    }

    private View linkCard(String text) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(18), dp(18), dp(18), dp(18));
        c.setBackground(card());
        c.addView(text(Words.s("link_title"), 18, INK, true));
        TextView about = text(Words.f("link_text", Store.shown()) + "\n\n" + Words.s("link_text_more"), 14, SOFT, false);
        about.setPadding(0, dp(6), 0, dp(12));
        c.addView(about);
        final EditText field = new EditText(this);
        field.setHint(Words.s("link_hint"));
        field.setText(text);
        field.setTextColor(INK);
        field.setHintTextColor(SOFT);
        field.setSingleLine(true);
        field.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        field.setImeOptions(EditorInfo.IME_ACTION_GO);
        field.setBackgroundTintList(android.content.res.ColorStateList.valueOf(ACCENT));
        c.addView(field, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout keys = row();
        keys.setGravity(Gravity.END);
        keys.setPadding(0, dp(10), 0, 0);
        TextView paste = pill(Words.s("link_paste"), false);
        paste.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                ClipData clip = cm == null ? null : cm.getPrimaryClip();
                if (clip != null && clip.getItemCount() > 0) {
                    CharSequence s = clip.getItemAt(0).coerceToText(Main.this);
                    if (s != null) {
                        field.setText(s);
                        field.setSelection(field.getText().length());
                    }
                }
            }
        });
        keys.addView(paste);
        c.addView(keys);

        LinearLayout two = row();
        two.setPadding(0, dp(14), 0, 0);
        two.setGravity(Gravity.END);
        TextView go = pill(Words.s("to_shelf"), true);
        go.setGravity(Gravity.CENTER);
        go.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (Link.parse(field.getText().toString()) == null) {
                    Toast.makeText(Main.this, Words.s("link_bad"), Toast.LENGTH_SHORT).show();
                } else {
                    put(field.getText().toString(), null);
                    linkOpen = null;
                    showLibrary();
                }
            }
        });
        two.addView(go);
        c.addView(two);
        return c;
    }

    /** Some folder holds files the application may not see; downloading there waits for the permission. */
    private View seeCard() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(18), dp(16), dp(18), dp(16));
        c.setBackground(card());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = dp(10);
        c.setLayoutParams(lp);
        TextView t = text(Words.s("unseen"), 14, INK, false);
        c.addView(t);
        TextView allow = pill(Words.s("allow"), true);
        allow.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                requestPermissions(new String[]{Store.readPermission()}, 4);
            }
        });
        LinearLayout.LayoutParams al = new LinearLayout.LayoutParams(-2, -2);
        al.gravity = Gravity.END;
        al.topMargin = dp(10);
        c.addView(allow, al);
        return c;
    }

    // ------------------------------------------------------------ settings

    private LinearLayout settingsScreen() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(PAPER);
        LinearLayout head = row();
        head.setPadding(dp(4), dp(6), dp(16), dp(2));
        Mark.Key back = new Mark.Key(this, Mark.LEFT, INK, 0);
        back.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showLibrary();
            }
        });
        head.addView(back, new LinearLayout.LayoutParams(dp(48), dp(48)));
        head.addView(text(Words.s("settings"), 20, INK, true));
        root.addView(head);
        ScrollView scroll = new ScrollView(this);
        settingsList = new LinearLayout(this);
        settingsList.setOrientation(LinearLayout.VERTICAL);
        settingsList.setPadding(dp(14), dp(6), dp(14), dp(28));
        scroll.addView(settingsList);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        return root;
    }

    private void showSettings() {
        shown = null;
        settingsOn = true;
        libraryView.setVisibility(View.GONE);
        bookView.setVisibility(View.GONE);
        settingsView.setVisibility(View.VISIBLE);
        fillSettings();
    }

    private void fillSettings() {
        if (settingsList == null) {
            return;
        }
        settingsList.removeAllViews();

        heading(Words.s("s_look"));
        LinearLayout look = group();
        String theme = Places.theme(this);
        look.addView(choice(Words.s("theme"), Words.s("theme_" + theme), new Runnable() {
            @Override
            public void run() {
                String t = Places.theme(Main.this);
                Places.theme(Main.this, "light".equals(t) ? "dark" : "dark".equals(t) ? "system" : "light");
                rebuild();
            }
        }));
        look.addView(line());
        look.addView(choice(Words.s("shelf"), Words.s("shelf_" + Places.shelf(this)), new Runnable() {
            @Override
            public void run() {
                Places.shelf(Main.this, "covers".equals(Places.shelf(Main.this)) ? "cards" : "covers");
                rebuild();
                showSettings();
            }
        }));
        look.addView(line());
        int ground = Places.widgetGround(this);
        look.addView(choice(Words.s("widget_ground"), ground == 0 ? Words.s("widget_ground_none") : ground + "%",
            new Runnable() {
                @Override
                public void run() {
                    int[] steps = {100, 75, 50, 25, 0};
                    int now = Places.widgetGround(Main.this);
                    int at = 0;
                    for (int i = 0; i < steps.length; i++) {
                        if (steps[i] == now) {
                            at = i;
                        }
                    }
                    Places.widgetGround(Main.this, steps[(at + 1) % steps.length]);
                    Widget.refresh(Main.this);
                    fillSettings();
                }
            }));
        look.addView(line());
        look.addView(choice(Words.s("language"), Words.s("lang_name"), new Runnable() {
            @Override
            public void run() {
                Words.choose(Main.this, Words.after());
                rebuild();
            }
        }));
        settingsList.addView(look);

        heading(Words.s("s_listen"));
        LinearLayout listen = group();
        int back = Places.rollback(this);
        listen.addView(choice(Words.s("rollback"), back == 0 ? Words.s("rollback_off") : Words.f("rollback_s", back),
            new Runnable() {
                @Override
                public void run() {
                    int[] steps = {0, 2, 3, 5, 10};
                    int now = Places.rollback(Main.this);
                    int at = 0;
                    for (int i = 0; i < steps.length; i++) {
                        if (steps[i] == now) {
                            at = i;
                        }
                    }
                    Places.rollback(Main.this, steps[(at + 1) % steps.length]);
                    fillSettings();
                }
            }));
        settingsList.addView(listen);

        heading(Words.s("s_folders"));
        LinearLayout folders = group();
        folders.addView(choice(Store.shown(), Words.s("folder_always"), null));
        for (final String f : lib.folders()) {
            folders.addView(line());
            LinearLayout r = row();
            r.setPadding(dp(16), dp(10), dp(6), dp(10));
            TextView n = text(Store.folderName(f), 15, INK, false);
            r.addView(n, new LinearLayout.LayoutParams(0, -2, 1));
            Mark.Key x = new Mark.Key(this, Mark.CROSS, SOFT, 0);
            x.setContentDescription(Words.s("folder_drop"));
            x.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    lib.dropFolder(f);
                    fillSettings();
                }
            });
            r.addView(x, new LinearLayout.LayoutParams(dp(40), dp(40)));
            folders.addView(r);
        }
        folders.addView(line());
        TextView add = text("+  " + Words.s("folder_add"), 15, ACCENT, true);
        add.setPadding(dp(16), dp(14), dp(16), dp(14));
        add.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent pick = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
                pick.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
                startActivityForResult(pick, 6);
            }
        });
        folders.addView(add);
        settingsList.addView(folders);

        heading(Words.s("s_links"));
        if (linkOpen == null) {
            linkOpen = linkCard("");
        }
        /* The same card, not a new one: what is being typed survives a refresh. */
        if (linkOpen.getParent() != null) {
            ((android.view.ViewGroup) linkOpen.getParent()).removeView(linkOpen);
        }
        settingsList.addView(linkOpen);
        TextView ver = text("Bookbinder " + VERSION, 12, SOFT, false);
        ver.setGravity(Gravity.CENTER);
        ver.setPadding(0, dp(28), 0, 0);
        settingsList.addView(ver, new LinearLayout.LayoutParams(-1, -2));
    }

    private void heading(String s) {
        TextView h = text(s, 13, SOFT, true);
        h.setAllCaps(true);
        h.setLetterSpacing(0.08f);
        h.setPadding(dp(6), dp(20), 0, dp(8));
        settingsList.addView(h);
    }

    private LinearLayout group() {
        LinearLayout g = new LinearLayout(this);
        g.setOrientation(LinearLayout.VERTICAL);
        g.setBackground(card());
        return g;
    }

    private View line() {
        View v = new View(this);
        v.setBackgroundColor(LINE);
        v.setLayoutParams(new LinearLayout.LayoutParams(-1, dp(1)));
        return v;
    }

    /** A row with a name on the left and the current choice on the right; a touch moves to the next. */
    private View choice(String name, String value, final Runnable next) {
        LinearLayout r = row();
        r.setPadding(dp(16), dp(14), dp(16), dp(14));
        r.addView(text(name, 15, INK, false), new LinearLayout.LayoutParams(0, -2, 1));
        TextView v = text(value, 15, next == null ? SOFT : ACCENT, next != null);
        r.addView(v);
        if (next != null) {
            r.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    next.run();
                }
            });
        }
        return r;
    }

    /** The bar at the foot of the library: what plays, and one key. */
    private void fillMini() {
        if (mini == null) {
            return;
        }
        Play p = Play.live();
        final Shelf.Book b = p == null ? null : p.book();
        if (b == null) {
            mini.setVisibility(View.GONE);
            return;
        }
        Object was = mini.getTag();
        boolean on = p.meant();
        String key = b.id + "/" + p.index() + "/" + on + "/" + (lib.cover(b) != null);
        mini.setVisibility(View.VISIBLE);
        if (key.equals(was)) {
            return;
        }
        mini.setTag(key);
        mini.removeAllViews();
        mini.addView(coverView(b, 44), new LinearLayout.LayoutParams(dp(44), dp(44)));
        LinearLayout words = new LinearLayout(this);
        words.setOrientation(LinearLayout.VERTICAL);
        words.setPadding(dp(12), 0, dp(8), 0);
        TextView t = text(b.title.isEmpty() ? b.folder : b.title, 15, INK, true);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        words.addView(t);
        words.addView(text(Words.f("chapter_of", p.index() + 1, b.chapters.size()), 13, SOFT, false));
        mini.addView(words, new LinearLayout.LayoutParams(0, -2, 1));
        Mark.Key k = new Mark.Key(this, on ? Mark.PAUSE : Mark.PLAY, CARD, ACCENT);
        k.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                send(Play.TOGGLE);
            }
        });
        mini.addView(k, new LinearLayout.LayoutParams(dp(48), dp(48)));
        mini.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showBook(b);
            }
        });
    }

    private void showLibrary() {
        shown = null;
        settingsOn = false;
        settingsView.setVisibility(View.GONE);
        bookView.setVisibility(View.GONE);
        libraryView.setVisibility(View.VISIBLE);
        fillLibrary();
    }

    // ------------------------------------------------------------ one book

    private LinearLayout bookScreen() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(PAPER);

        LinearLayout head = row();
        head.setPadding(dp(4), dp(6), dp(16), dp(2));
        Mark.Key back = new Mark.Key(this, Mark.LEFT, INK, 0);
        back.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showLibrary();
            }
        });
        head.addView(back, new LinearLayout.LayoutParams(dp(48), dp(48)));
        TextView label = text(Words.s("library"), 15, SOFT, false);
        label.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showLibrary();
            }
        });
        head.addView(label);
        root.addView(head);

        ScrollView scroll = new ScrollView(this);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(24), dp(6), dp(24), dp(28));
        body.setGravity(Gravity.CENTER_HORIZONTAL);
        scroll.addView(body);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));

        bookCover = new ImageView(this);
        bookCover.setScaleType(ImageView.ScaleType.CENTER_CROP);
        rounded(bookCover, 18);
        int side = Math.min(dp(300), getResources().getDisplayMetrics().widthPixels - dp(96));
        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(side, side);
        cl.gravity = Gravity.CENTER_HORIZONTAL;
        cl.topMargin = dp(8);
        bookCover.setElevation(dp(6));
        body.addView(bookCover, cl);

        bookTitle = text("", 22, INK, true);
        bookTitle.setGravity(Gravity.CENTER);
        bookTitle.setPadding(0, dp(22), 0, 0);
        body.addView(bookTitle, new LinearLayout.LayoutParams(-1, -2));
        bookWho = text("", 14, SOFT, false);
        bookWho.setGravity(Gravity.CENTER);
        bookWho.setPadding(0, dp(4), 0, 0);
        body.addView(bookWho, new LinearLayout.LayoutParams(-1, -2));
        bookChapter = text("", 15, ACCENT, true);
        bookChapter.setGravity(Gravity.CENTER);
        bookChapter.setPadding(0, dp(14), 0, 0);
        body.addView(bookChapter, new LinearLayout.LayoutParams(-1, -2));
        bookState = text("", 13, SOFT, false);
        bookState.setGravity(Gravity.CENTER);
        body.addView(bookState, new LinearLayout.LayoutParams(-1, -2));

        seek = new SeekBar(this);
        seek.setMax(1000);
        seek.setProgressTintList(android.content.res.ColorStateList.valueOf(ACCENT));
        seek.setThumbTintList(android.content.res.ColorStateList.valueOf(ACCENT));
        seek.setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf(LINE));
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s, int v, boolean user) {
                if (user) {
                    Play p = mine();
                    if (p != null && p.duration() > 0) {
                        timeHere.setText(clock(p.duration() * v / 1000));
                    }
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar s) {
                dragging = true;
            }

            @Override
            public void onStopTrackingTouch(SeekBar s) {
                dragging = false;
                Play p = mine();
                if (p != null && p.duration() > 0) {
                    p.seek(p.duration() * s.getProgress() / 1000);
                }
            }
        });
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(-1, dp(40));
        sl.topMargin = dp(16);
        body.addView(seek, sl);
        LinearLayout times = row();
        times.setPadding(dp(16), 0, dp(16), 0);
        timeHere = text("0:00", 13, SOFT, false);
        timeAll = text("", 13, SOFT, false);
        timeAll.setGravity(Gravity.END);
        times.addView(timeHere, new LinearLayout.LayoutParams(0, -2, 1));
        times.addView(timeAll, new LinearLayout.LayoutParams(0, -2, 1));
        body.addView(times, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout keys = row();
        keys.setGravity(Gravity.CENTER);
        keys.setPadding(0, dp(14), 0, dp(6));
        keys.addView(key(Mark.PREV, Play.PREV, 52, 0.42f), new LinearLayout.LayoutParams(dp(52), dp(52)));
        keys.addView(key(Mark.BACK, Play.BACK, 60, 0.62f), new LinearLayout.LayoutParams(dp(60), dp(60)));
        playKey = new Mark.Key(this, Mark.PLAY, CARD, ACCENT);
        playKey.scale(0.5f);
        playKey.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pressPlay();
            }
        });
        LinearLayout.LayoutParams pl = new LinearLayout.LayoutParams(dp(78), dp(78));
        pl.leftMargin = dp(10);
        pl.rightMargin = dp(10);
        keys.addView(playKey, pl);
        keys.addView(key(Mark.ON, Play.ON, 60, 0.62f), new LinearLayout.LayoutParams(dp(60), dp(60)));
        keys.addView(key(Mark.NEXT, Play.NEXT, 52, 0.42f), new LinearLayout.LayoutParams(dp(52), dp(52)));
        body.addView(keys, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout extra = row();
        extra.setGravity(Gravity.CENTER);
        extra.setPadding(0, dp(8), 0, dp(10));
        paceKey = pill("1.0×", false);
        paceKey.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Play p = Play.live();
                if (p != null) {
                    p.nextPace();
                } else {
                    float now = Places.pace(Main.this);
                    int at = 0;
                    for (int i = 0; i < Play.PACES.length; i++) {
                        if (Math.abs(Play.PACES[i] - now) < 0.01f) {
                            at = i;
                        }
                    }
                    Places.pace(Main.this, Play.PACES[(at + 1) % Play.PACES.length]);
                    fillBook();
                }
            }
        });
        extra.addView(paceKey);
        View gap = new View(this);
        extra.addView(gap, new LinearLayout.LayoutParams(dp(12), 1));
        sleepKey = pill("", false);
        sleepKey.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Play p = mine();
                if (p != null) {
                    p.nextSleep();
                }
            }
        });
        extra.addView(sleepKey);
        body.addView(extra, new LinearLayout.LayoutParams(-1, -2));

        TextView heading = text(Words.s("chapters"), 13, SOFT, true);
        heading.setAllCaps(true);
        heading.setLetterSpacing(0.08f);
        heading.setPadding(dp(4), dp(18), 0, dp(8));
        body.addView(heading, new LinearLayout.LayoutParams(-1, -2));
        chapterList = new LinearLayout(this);
        chapterList.setOrientation(LinearLayout.VERTICAL);
        chapterList.setBackground(card());
        body.addView(chapterList, new LinearLayout.LayoutParams(-1, -2));

        TextView delete = text(Words.s("delete"), 14, 0xFFB3412E, false);
        delete.setGravity(Gravity.CENTER);
        delete.setPadding(dp(16), dp(26), dp(16), dp(10));
        delete.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                askDelete();
            }
        });
        body.addView(delete, new LinearLayout.LayoutParams(-1, -2));
        return root;
    }

    private Mark.Key key(int mark, final String act, int size, float scale) {
        Mark.Key k = new Mark.Key(this, mark, INK, 0);
        k.scale(scale);
        k.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (mine() != null) {
                    send(act);
                }
            }
        });
        return k;
    }

    private void showBook(Shelf.Book b) {
        shown = b;
        libraryView.setVisibility(View.GONE);
        bookView.setVisibility(View.VISIBLE);
        chapterList.setTag(null);
        fillBook();
    }

    private Shelf.Book deleting;

    private void askDelete() {
        final Shelf.Book b = shown;
        if (b == null) {
            return;
        }
        if (Build.VERSION.SDK_INT >= 30) {
            /* The system asks the question itself, and only it may delete files
               the application did not write — such as a book put there by hand. */
            java.util.ArrayList<android.net.Uri> uris = new java.util.ArrayList<android.net.Uri>();
            for (String w : lib.locations(b)) {
                if (w.startsWith("content:")) {
                    uris.add(android.net.Uri.parse(w));
                }
            }
            if (!uris.isEmpty()) {
                try {
                    android.app.PendingIntent ask = android.provider.MediaStore.createDeleteRequest(getContentResolver(), uris);
                    deleting = b;
                    startIntentSenderForResult(ask.getIntentSender(), 3, null, 0, 0, 0);
                    return;
                } catch (Exception e) {
                    Log.note("main: delete request refused: " + e);
                }
            }
        }
        String name = b.title.isEmpty() ? b.folder : b.title;
        new android.app.AlertDialog.Builder(this, dialogTheme())
            .setMessage(Words.f("delete_ask", name, Store.shown()))
            .setNegativeButton(Words.s("cancel"), null)
            .setPositiveButton(Words.s("delete"), new android.content.DialogInterface.OnClickListener() {
                @Override
                public void onClick(android.content.DialogInterface d, int which) {
                    Play p = mine();
                    if (p != null) {
                        p.close();
                    }
                    lib.forget(b);
                    showLibrary();
                }
            })
            .show();
    }

    /** The player, if it holds the book on screen. */
    private Play mine() {
        Play p = Play.live();
        return p != null && shown != null && p.book() != null && p.book().id == shown.id ? p : null;
    }

    private void pressPlay() {
        askNotes();
        Play p = mine();
        if (p != null) {
            send(Play.TOGGLE);
        } else {
            Intent i = new Intent(this, Play.class).setAction(Play.OPEN);
            i.putExtra("book", shown.id);
            i.putExtra("chapter", -1);
            i.putExtra("play", true);
            startService(i);
        }
    }

    private void fillBook() {
        Shelf.Book b = shown;
        if (b == null) {
            return;
        }
        Shelf.Book fresh = lib.book(b.id);
        if (fresh != null && fresh != b) {
            shown = b = fresh;
        }
        Bitmap art = lib.cover(b);
        Bitmap blank = blanks.get(b.id);
        if (art == null && blank == null) {
            blank = placeholder(b, dp(300));
            blanks.put(b.id, blank);
        }
        if (bookCover.getTag() != (art != null ? art : blank)) {
            bookCover.setImageBitmap(art != null ? art : blank);
            bookCover.setTag(art != null ? art : blank);
        }
        bookTitle.setText(b.title.isEmpty() ? b.folder : b.title);
        String who = who(b);
        bookWho.setText(who);
        bookWho.setVisibility(who.isEmpty() ? View.GONE : View.VISIBLE);
        Play p = mine();
        int at = p != null ? p.index() : chapterAt(b);
        Shelf.Chapter ch = at >= 0 && at < b.chapters.size() ? b.chapters.get(at) : null;
        bookChapter.setText(Words.f("chapter_of", at + 1, b.chapters.size())
            + (ch == null ? "" : " · " + ch.stem()));
        bookState.setText(ch == null ? "" : stateOf(ch));
        playKey.set(p != null && p.meant() ? Mark.PAUSE : Mark.PLAY);
        paceKey.setText(String.format(Locale.ROOT, "%.2f", Places.pace(this)).replaceAll("0$", "") + "×");
        fillSleep();
        fillTime();
        fillChapters(b, at);
    }

    private String stateOf(Shelf.Chapter ch) {
        if (lib.ready(ch)) {
            return "";
        }
        long[] got = lib.progress(ch);
        if (got != null && got[1] > 0) {
            return Words.f("loading", (int) (100 * got[0] / got[1]));
        }
        if (lib.failed(ch)) {
            return Words.s("failed");
        }
        if (lib.isGone(ch)) {
            return Words.s("gone");
        }
        return Words.s("waiting");
    }

    private void fillSleep() {
        Play p = mine();
        String v;
        long left = p == null ? 0 : p.sleepLeft();
        if (left == 0) {
            v = Words.s("sleep_off");
        } else if (left < 0) {
            v = Words.s("sleep_end");
        } else {
            v = clock(left);
        }
        sleepKey.setText("☾  " + Words.s("sleep") + ": " + v);
        sleepKey.setAlpha(p == null ? 0.5f : 1f);
    }

    private void fillTime() {
        Play p = mine();
        if (p == null || !p.ready()) {
            long pos = shown == null ? 0 : Places.position(this, shown.id);
            timeHere.setText(clock(p != null ? p.position() : pos));
            timeAll.setText("");
            if (!dragging) {
                seek.setProgress(0);
            }
            seek.setEnabled(false);
            return;
        }
        seek.setEnabled(true);
        long d = p.duration();
        long at = p.position();
        if (!dragging) {
            timeHere.setText(clock(at));
            seek.setProgress(d > 0 ? (int) (1000 * at / d) : 0);
        }
        timeAll.setText("−" + clock(Math.max(0, d - at)));
        if (p.sleep() > 0) {
            fillSleep();
        }
    }

    private void fillChapters(final Shelf.Book b, int at) {
        StringBuilder sig = new StringBuilder().append(b.id).append('/').append(at);
        for (Shelf.Chapter ch : b.chapters) {
            sig.append(lib.ready(ch) ? 'r' : '-');
            long[] got = lib.progress(ch);
            if (got != null && got[1] > 0) {
                sig.append(100 * got[0] / got[1]);
            }
            sig.append(lib.failed(ch) ? 'x' : lib.isGone(ch) ? 'g' : '.');
        }
        Play p = mine();
        sig.append(p != null && p.meant());
        if (sig.toString().equals(chapterList.getTag())) {
            return;
        }
        chapterList.setTag(sig.toString());
        chapterList.removeAllViews();
        for (int i = 0; i < b.chapters.size(); i++) {
            final int index = i;
            Shelf.Chapter ch = b.chapters.get(i);
            LinearLayout r = row();
            r.setPadding(dp(14), dp(12), dp(10), dp(12));
            boolean here = i == at;
            if (here) {
                r.setBackgroundColor(ACCENT_PALE);
            }
            TextView n = text(String.valueOf(i + 1), 14, here ? ACCENT : SOFT, here);
            n.setMinWidth(dp(34));
            r.addView(n);
            TextView name = text(ch.stem(), 15, i < at ? SOFT : INK, here);
            name.setSingleLine(true);
            name.setEllipsize(TextUtils.TruncateAt.MIDDLE);
            r.addView(name, new LinearLayout.LayoutParams(0, -2, 1));
            String state = stateOf(ch);
            long[] got = lib.progress(ch);
            String small = lib.ready(ch) ? megabytes(ch.size)
                : got != null && got[1] > 0 ? (100 * got[0] / got[1]) + "%"
                : lib.isGone(ch) ? Words.s("gone") : "";
            TextView size = text(small, 12, SOFT, false);
            size.setPadding(dp(8), 0, dp(6), 0);
            r.addView(size);
            int mark = here ? (p != null && p.meant() ? Mark.PLAY : Mark.DOT)
                : i < at ? Mark.CHECK : lib.ready(ch) ? Mark.DOWN : -1;
            Mark.Key m = new Mark.Key(this, mark, here ? ACCENT : SOFT, 0);
            m.setClickable(false);
            m.scale(0.7f);
            r.addView(m, new LinearLayout.LayoutParams(dp(22), dp(22)));
            r.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    askNotes();
                    Intent go = new Intent(Main.this, Play.class).setAction(Play.OPEN);
                    go.putExtra("book", b.id);
                    go.putExtra("chapter", index);
                    go.putExtra("play", true);
                    startService(go);
                }
            });
            chapterList.addView(r, new LinearLayout.LayoutParams(-1, -2));
            if (i < b.chapters.size() - 1) {
                View line = new View(this);
                line.setBackgroundColor(LINE);
                LinearLayout.LayoutParams ll = new LinearLayout.LayoutParams(-1, dp(1));
                ll.leftMargin = dp(48);
                chapterList.addView(line, ll);
            }
        }
    }

    // ------------------------------------------------------------ helpers

    private void send(String act) {
        Intent i = new Intent(this, Play.class).setAction(act);
        startService(i);
    }

    private void askNotes() {
        if (Build.VERSION.SDK_INT >= 33
            && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
        }
    }

    /** The chapter a book was left at, by the kept place; the first if none. */
    private int chapterAt(Shelf.Book b) {
        Play p = Play.live();
        if (p != null && p.book() != null && p.book().id == b.id) {
            return p.index();
        }
        int i = b.find(Places.chapter(this, b.id));
        return Math.max(0, i);
    }

    private double progressOf(Shelf.Book b) {
        Play p = Play.live();
        if (p != null && p.book() != null && p.book().id == b.id && p.duration() > 0) {
            return b.done(p.index(), p.position() / (double) p.duration());
        }
        int i = b.find(Places.chapter(this, b.id));
        if (i < 0) {
            return 0;
        }
        /* The place within a chapter is known in time, not bytes: count the chapter as begun. */
        return b.done(i, Places.position(this, b.id) > 0 ? 0.05 : 0);
    }

    private static String who(Shelf.Book b) {
        if (b.author.isEmpty()) {
            return b.reader;
        }
        return b.reader.isEmpty() ? b.author : b.author + " · " + b.reader;
    }

    static String clock(long ms) {
        long s = Math.max(0, ms / 1000);
        long h = s / 3600;
        long m = (s / 60) % 60;
        long sec = s % 60;
        return h > 0 ? String.format(Locale.ROOT, "%d:%02d:%02d", h, m, sec)
            : String.format(Locale.ROOT, "%d:%02d", m, sec);
    }

    private static String megabytes(long bytes) {
        return String.format(Locale.ROOT, "%.1f MB", bytes / 1048576.0);
    }

    private View coverView(Shelf.Book b, int sizeDp) {
        ImageView v = new ImageView(this);
        v.setScaleType(ImageView.ScaleType.CENTER_CROP);
        Bitmap art = lib.cover(b);
        v.setImageBitmap(art != null ? art : placeholder(b, dp(sizeDp)));
        rounded(v, sizeDp > 50 ? 10 : 8);
        return v;
    }

    /** Until the cover comes: the first letter of the title on a pale square. */
    private Bitmap placeholder(Shelf.Book b, int side) {
        Bitmap bm = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bm);
        c.drawColor(ACCENT_PALE);
        String t = b.title.isEmpty() ? b.folder : b.title;
        String letter = t.isEmpty() ? "·" : t.substring(0, 1).toUpperCase(Locale.ROOT);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(ACCENT);
        p.setTextAlign(Paint.Align.CENTER);
        p.setTypeface(Typeface.create("serif", Typeface.NORMAL));
        p.setTextSize(side * 0.46f);
        c.drawText(letter, side / 2f, side / 2f - (p.descent() + p.ascent()) / 2f, p);
        return bm;
    }

    private void rounded(View v, final int radiusDp) {
        v.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline o) {
                o.setRoundRect(0, 0, view.getWidth(), view.getHeight(), dp(radiusDp));
            }
        });
        v.setClipToOutline(true);
    }

    private GradientDrawable card() {
        GradientDrawable g = new GradientDrawable();
        g.setColor(CARD);
        g.setCornerRadius(dp(14));
        g.setStroke(dp(1), LINE);
        return g;
    }

    private TextView pill(String s, boolean strong) {
        TextView t = text(s, 14, strong ? CARD : INK, strong);
        t.setPadding(dp(18), dp(10), dp(18), dp(10));
        GradientDrawable g = new GradientDrawable();
        g.setColor(strong ? ACCENT : CARD);
        g.setCornerRadius(dp(22));
        if (!strong) {
            g.setStroke(dp(1), LINE);
        }
        t.setBackground(g);
        return t;
    }

    private LinearLayout row() {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        return r;
    }

    private TextView text(String s, int sp, int color, boolean strong) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(color);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTypeface(Typeface.create(strong ? "sans-serif-medium" : "sans-serif", Typeface.NORMAL));
        return t;
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
