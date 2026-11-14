package com.shumtugle.bookbinder;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.Looper;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The listener's library. A shared link is a book, or a folder of books,
 * put on the shelf: the application makes a folder for each book in the
 * phone's music folder and brings the chapters in, one at a time, the
 * chapter being heard first. What has been brought stays until the listener
 * deletes it. A chapter deleted by hand is not brought back on its own —
 * only when the listener asks for it by playing it.
 *
 * The lists of the shared folders are kept on the phone and shown at once;
 * asking the folders again happens behind the screen.
 */
final class Library {

    /** Told on the main thread whenever the list, a cover or a fetch changes. */
    interface Watch {
        void changed();
    }

    /** What became of a link put on the shelf; told once, on the main thread. */
    interface Answer {
        void told(int state, String detail, int books, int chapters);
    }

    static final int ADDED = 0;
    /** No connection: the link is kept and asked again later. */
    static final int OFFLINE = 1;
    /** The link was deleted by its owner or never existed. */
    static final int DEAD = 2;
    static final int EXPIRED = 3;
    /** The link has used up what its owner's plan allows: traffic or downloads. */
    static final int SPENT = 4;
    /** The folder holds no sound files. */
    static final int EMPTY = 5;
    /** Any other refusal; the detail holds the service's own words. */
    static final int REFUSED = 6;
    /** A kind of link that is known but not read yet. */
    static final int LATER = 7;

    /** Sorts the sealed-folder service's own refusal numbers into what the listener needs to know. */
    static int classifySealed(int code) {
        switch (code) {
            case -9:
            case -11:
            case -16:
                return DEAD;
            case -4:
            case -17:
                return SPENT;
            default:
                return REFUSED;
        }
    }

    /** Sorts a server's answer by status into what the listener needs to know. */
    static int classifyStatus(int status) {
        if (status == 404 || status == 410) {
            return DEAD;
        }
        if (status == 429 || status == 509) {
            return SPENT;
        }
        return REFUSED;
    }

    /** Fetching for the other kinds of link: a refusal comes as an exception with its status. */
    private final Sources.Fetch web = new Sources.Fetch() {
        @Override
        public String text(URL u) throws IOException {
            HttpURLConnection c = (HttpURLConnection) u.openConnection();
            c.setConnectTimeout(15000);
            c.setReadTimeout(30000);
            c.setRequestProperty("Accept", "application/json, text/html;q=0.9, */*;q=0.5");
            try {
                int code = c.getResponseCode();
                if (code >= 400) {
                    throw new Sources.Refused(code, "http " + code);
                }
                InputStream in = c.getInputStream();
                try {
                    return new String(head(in, 8 * 1024 * 1024), "UTF-8");
                } finally {
                    in.close();
                }
            } finally {
                c.disconnect();
            }
        }

        @Override
        public String post(URL u, String body) throws IOException {
            HttpURLConnection c = (HttpURLConnection) u.openConnection();
            c.setConnectTimeout(15000);
            c.setReadTimeout(60000);
            c.setDoOutput(true);
            c.setRequestMethod("POST");
            c.setRequestProperty("Content-Type", "application/json");
            try {
                OutputStream out = c.getOutputStream();
                try {
                    out.write(body.getBytes("UTF-8"));
                } finally {
                    out.close();
                }
                int code = c.getResponseCode();
                if (code >= 400) {
                    throw new Sources.Refused(code, "http " + code);
                }
                InputStream in = c.getInputStream();
                try {
                    return new String(head(in, 16 * 1024 * 1024), "UTF-8");
                } finally {
                    in.close();
                }
            } finally {
                c.disconnect();
            }
        }

        @Override
        public long length(URL u) throws IOException {
            HttpURLConnection c = (HttpURLConnection) u.openConnection();
            c.setRequestMethod("HEAD");
            c.setConnectTimeout(10000);
            c.setReadTimeout(10000);
            try {
                return c.getResponseCode() < 400 ? c.getContentLengthLong() : -1;
            } finally {
                c.disconnect();
            }
        }
    };

    private static Library one;

    static synchronized Library get(Context c) {
        if (one == null) {
            one = new Library(c.getApplicationContext());
        }
        return one;
    }

    private final Context app;
    private final SharedPreferences prefs;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ArrayList<Watch> watchers = new ArrayList<Watch>();
    private final ExecutorService list = Executors.newSingleThreadExecutor();
    private final ExecutorService fetch = Executors.newSingleThreadExecutor();
    private final ExecutorService pictures = Executors.newSingleThreadExecutor();

    /** Books of every link, in the order the links were added. */
    private volatile ArrayList<Shelf.Book> books = new ArrayList<Shelf.Book>();
    private final HashMap<String, ArrayList<Shelf.Book>> bySource = new HashMap<String, ArrayList<Shelf.Book>>();
    private int asking;
    private long asked;
    private String trouble;

    /** Where each brought chapter is kept: chapter id to location. */
    private final HashMap<Long, String> kept = new HashMap<Long, String>();
    /** Books put on the shelf by hand, and where their chapters are; found anew at every look. */
    private volatile ArrayList<Shelf.Book> locals = new ArrayList<Shelf.Book>();
    private final HashMap<Long, String> localKept = new HashMap<Long, String>();
    /** Chapters that were brought and then deleted by the listener. */
    private final HashSet<Long> gone = new HashSet<Long>();
    /** Chapters asked for by playing them: brought even if once deleted. */
    private final HashSet<Long> askedFor = new HashSet<Long>();
    private final HashMap<Long, long[]> progress = new HashMap<Long, long[]>();
    private final HashMap<Long, Integer> failures = new HashMap<Long, Integer>();
    private final HashMap<Long, Integer> losses = new HashMap<Long, Integer>();
    /** Books whose folder holds files this application may not see: paused until it may. */
    private final HashSet<Long> blocked = new HashSet<Long>();
    private final HashSet<Long> pictureAsked = new HashSet<Long>();
    private final HashMap<Long, Bitmap> covers = new HashMap<Long, Bitmap>();
    private volatile Shelf.Chapter fetching;
    private boolean pumping;
    /** The book being heard and its chapter: fetched first. */
    private volatile long hearingBook = -1;
    private volatile int hearingIndex = -1;

    private Library(Context app) {
        this.app = app;
        this.prefs = app.getSharedPreferences("bookbinder", Context.MODE_PRIVATE);
        readTable(new File(app.getFilesDir(), "kept.tsv"), kept);
        HashMap<Long, String> g = new HashMap<Long, String>();
        readTable(new File(app.getFilesDir(), "gone.tsv"), g);
        gone.addAll(g.keySet());
        for (String code : sources()) {
            File tree = treeFile(code);
            if (tree.exists()) {
                try {
                    bySource.put(code, mark(Shelf.read(Json.obj(Json.parse(read(tree)), "metadata")), code));
                } catch (Exception broken) {
                    Log.note("library: a kept list could not be read: " + broken);
                }
            }
        }
        merge();
        look();
    }

    /** Looks through the shelf folders for books put there by hand. */
    void look() {
        list.execute(new Runnable() {
            @Override
            public void run() {
                HashMap<Long, String> where = new HashMap<Long, String>();
                ArrayList<Shelf.Book> found = Store.scan(app, where);
                /* Folders shown by the listener come after the music folder; one folder, one book. */
                HashSet<String> seenPlaces = new HashSet<String>();
                for (Shelf.Book b : found) {
                    seenPlaces.add(b.place.toLowerCase(java.util.Locale.ROOT));
                }
                for (Shelf.Book b : Store.scanTrees(app, folders(), where)) {
                    if (seenPlaces.add(b.place.toLowerCase(java.util.Locale.ROOT))) {
                        found.add(b);
                    }
                }
                synchronized (kept) {
                    localKept.clear();
                    localKept.putAll(where);
                }
                locals = found;
                merge();
                tell();
                Widget.refresh(app);
                for (Shelf.Book b : found) {
                    cover(b);
                }
            }
        });
    }

    private static ArrayList<Shelf.Book> mark(ArrayList<Shelf.Book> found, String code) {
        for (Shelf.Book b : found) {
            b.source = code;
        }
        return found;
    }

    private File treeFile(String code) {
        return new File(app.getFilesDir(), "tree-" + code + ".json");
    }

    // ------------------------------------------------------------ watching

    void watch(Watch w) {
        if (!watchers.contains(w)) {
            watchers.add(w);
        }
    }

    void unwatch(Watch w) {
        watchers.remove(w);
    }

    private void tell() {
        ui.post(new Runnable() {
            @Override
            public void run() {
                for (Watch w : new ArrayList<Watch>(watchers)) {
                    w.changed();
                }
            }
        });
    }

    // ------------------------------------------------------------ links and books

    /** The codes of the links on the shelf, in the order they were added. */
    ArrayList<String> sources() {
        ArrayList<String> out = new ArrayList<String>();
        for (String line : prefs.getString("sources", "").split("\n")) {
            if (!line.trim().isEmpty()) {
                out.add(line.trim());
            }
        }
        return out;
    }

    private void addSource(String code, String text) {
        ArrayList<String> all = sources();
        if (!all.contains(code)) {
            all.add(code);
        }
        StringBuilder b = new StringBuilder();
        for (String s : all) {
            b.append(s).append('\n');
        }
        prefs.edit().putString("sources", b.toString()).putString("link:" + code, text.trim()).apply();
    }

    /** Folders of books the listener has shown in the system picker. */
    ArrayList<String> folders() {
        ArrayList<String> out = new ArrayList<String>();
        for (String line : prefs.getString("folders", "").split("\n")) {
            if (!line.trim().isEmpty()) {
                out.add(line.trim());
            }
        }
        return out;
    }

    void addFolder(String tree) {
        ArrayList<String> all = folders();
        if (!all.contains(tree)) {
            all.add(tree);
        }
        saveFolders(all);
        look();
    }

    void dropFolder(String tree) {
        ArrayList<String> all = folders();
        all.remove(tree);
        saveFolders(all);
        try {
            app.getContentResolver().releasePersistableUriPermission(android.net.Uri.parse(tree),
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION | android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        } catch (Exception ignored) {
            Log.note("library: folder permission already gone");
        }
        look();
    }

    private void saveFolders(ArrayList<String> all) {
        StringBuilder b = new StringBuilder();
        for (String s : all) {
            b.append(s).append('\n');
        }
        prefs.edit().putString("folders", b.toString()).apply();
    }

    boolean linked() {
        return !sources().isEmpty();
    }

    ArrayList<Shelf.Book> books() {
        return books;
    }

    Shelf.Book book(long id) {
        return Shelf.byId(books, id);
    }

    boolean asking() {
        return asking > 0;
    }

    /** What went wrong the last time a folder was asked, or null. */
    String trouble() {
        return trouble;
    }

    /**
     * The shelf from all links and from the folders filled by hand. One book
     * appears once: by its number in the cloud, and by its folder on the
     * phone. When two links lead to the same book, the one that works wins.
     */
    private synchronized void merge() {
        HashSet<String> hidden = hidden();
        ArrayList<String> order = new ArrayList<String>();
        for (String code : sources()) {
            if (prefs.getInt("state:" + code, ADDED) == ADDED) {
                order.add(code);
            }
        }
        for (String code : sources()) {
            if (!order.contains(code)) {
                order.add(code);
            }
        }
        ArrayList<Shelf.Book> all = new ArrayList<Shelf.Book>();
        HashSet<Long> seen = new HashSet<Long>();
        HashSet<String> places = new HashSet<String>();
        for (String code : order) {
            ArrayList<Shelf.Book> some = bySource.get(code);
            if (some == null) {
                continue;
            }
            for (Shelf.Book b : some) {
                String place = Store.placeOf(b).toLowerCase(java.util.Locale.ROOT);
                if (hidden.contains(String.valueOf(b.id)) || seen.contains(b.id) || places.contains(place)) {
                    continue;
                }
                seen.add(b.id);
                places.add(place);
                all.add(b);
            }
        }
        /* A folder that a cloud book already writes to is that book, not another. */
        for (ArrayList<Shelf.Book> some : bySource.values()) {
            for (Shelf.Book b : some) {
                places.add(Store.placeOf(b).toLowerCase(java.util.Locale.ROOT));
            }
        }
        for (Shelf.Book b : locals) {
            if (!places.contains(b.place.toLowerCase(java.util.Locale.ROOT)) && seen.add(b.id)) {
                all.add(b);
            }
        }
        books = all;
    }

    private HashSet<String> hidden() {
        HashSet<String> h = new HashSet<String>();
        for (String s : prefs.getString("hidden", "").split(",")) {
            if (!s.isEmpty()) {
                h.add(s);
            }
        }
        return h;
    }

    private synchronized void hide(long id, boolean on) {
        HashSet<String> h = hidden();
        if (on) {
            h.add(String.valueOf(id));
        } else {
            h.remove(String.valueOf(id));
        }
        StringBuilder b = new StringBuilder();
        for (String s : h) {
            b.append(s).append(',');
        }
        prefs.edit().putString("hidden", b.toString()).apply();
    }

    /**
     * Puts a link on the shelf and asks it at once. A link already there is
     * asked again, and its books come back if they were taken off.
     * False when the text holds no link.
     */
    boolean add(String text) {
        return add(text, null);
    }

    /**
     * Puts a link on the shelf and says what came of it. A link that turns
     * out dead, expired or empty is taken off again; one that could not be
     * asked for want of a connection stays, to be asked later.
     */
    boolean add(String text, Answer answer) {
        Link l = Link.parse(text);
        if (l == null) {
            return false;
        }
        addSource(l.code, text);
        ask(l, true, answer);
        return true;
    }

    /** The state of the link a book came from: ADDED while it works, else why it does not. */
    int linkState(Shelf.Book b) {
        return b.source.isEmpty() ? ADDED : prefs.getInt("state:" + b.source, ADDED);
    }

    private void dropSource(String code) {
        ArrayList<String> all = sources();
        all.remove(code);
        StringBuilder b = new StringBuilder();
        for (String s : all) {
            b.append(s).append('\n');
        }
        prefs.edit().putString("sources", b.toString()).remove("link:" + code).remove("state:" + code).apply();
        synchronized (this) {
            bySource.remove(code);
        }
        treeFile(code).delete();
        merge();
    }

    /** Sorts the service's refusal codes into what the listener needs to know. */
    static int classify(long code) {
        if (code == 7001 || code == 7002) {
            return DEAD;
        }
        if (code == 7004) {
            return EXPIRED;
        }
        if (code == 7005 || code == 7006) {
            return SPENT;
        }
        return REFUSED;
    }

    /** Asks the folders again if they were last asked longer ago than the given time. */
    void refreshIfOlder(long ms) {
        if (System.currentTimeMillis() - asked > ms) {
            refresh();
        }
    }

    void refresh() {
        for (String code : sources()) {
            Link l = Link.parse(prefs.getString("link:" + code, code));
            if (l != null) {
                ask(l, false, null);
            }
        }
        pump();
    }

    /** What a link's folder said when asked. */
    private static final class Probe {
        int state = OFFLINE;
        String detail = "";
        String error;
        String body;
        String host;
        String name = "";
        ArrayList<Shelf.Book> found;
    }

    /** Asks a link's folder in both regions, the one that answered last time first. Blocking. */
    private Probe probe(Link l) {
        Probe p = new Probe();
        if (l.kind == Link.LATER) {
            p.state = LATER;
            p.error = "this kind of link is not read yet";
            return p;
        }
        if (l.kind != Link.CODE) {
            try {
                String body = l.kind == Link.DISK ? Sources.disk(web, l.text, l.code)
                    : l.kind == Link.SEALED ? Sealed.folder(web, l.text, l.code) : Sources.web(web, l.text, l.code);
                Object meta = Json.obj(Json.parse(body), "metadata");
                p.found = mark(Shelf.read(meta), l.code);
                p.name = Json.str(meta, "name");
                p.body = body;
                p.host = "";
                p.state = p.found.isEmpty() ? EMPTY : ADDED;
                p.error = p.found.isEmpty() ? "no sound files" : null;
            } catch (Sources.Refused r) {
                p.state = classifyStatus(r.status);
                p.detail = r.getMessage();
                p.error = r.getMessage();
            } catch (Sealed.Refused r) {
                p.state = classifySealed(r.code);
                p.detail = r.getMessage();
                p.error = r.getMessage();
            } catch (Exception e) {
                p.error = e.getClass().getSimpleName() + ": " + e.getMessage();
            }
            return p;
        }
        String known = prefs.getString("host:" + l.code, "");
        String[] hosts = known.isEmpty() ? new String[]{l.first, l.second}
            : new String[]{known, known.equals(l.first) ? l.second : l.first};
        for (String host : hosts) {
            try {
                String body = text(Link.list(host, l.code));
                Object said = Json.parse(body);
                long result = Json.num(said, "result", -1);
                if (result != 0) {
                    p.error = "code " + result + ": " + Json.str(said, "error");
                    int k = classify(result);
                    /* An unknown code in one region says less than a known one in the other. */
                    if (p.state == OFFLINE || p.state == REFUSED || p.state == DEAD && k != REFUSED) {
                        p.state = k;
                        p.detail = Json.str(said, "error");
                    }
                    continue;
                }
                Object meta = Json.obj(said, "metadata");
                p.found = mark(Shelf.read(meta), l.code);
                p.name = Json.str(meta, "name");
                p.body = body;
                p.host = host;
                if (p.found.isEmpty()) {
                    p.state = EMPTY;
                    p.error = "no sound files";
                } else {
                    p.state = ADDED;
                    p.error = null;
                }
                break;
            } catch (Exception e) {
                p.error = e.getClass().getSimpleName() + ": " + e.getMessage();
            }
        }
        if (p.detail == null) {
            p.detail = "";
        }
        return p;
    }

    private void ask(final Link l, final boolean fresh, final Answer answer) {
        synchronized (this) {
            asking++;
        }
        asked = System.currentTimeMillis();
        trouble = null;
        tell();
        list.execute(new Runnable() {
            @Override
            public void run() {
                Probe p = probe(l);
                String error = p.error;
                int state = p.state;
                String detail = p.detail;
                ArrayList<Shelf.Book> found = p.found;
                if (state == ADDED) {
                    try {
                        write(treeFile(l.code), p.body);
                        if (l.kind == Link.CODE) {
                            prefs.edit().putString("host:" + l.code, p.host).apply();
                        }
                        synchronized (Library.this) {
                            bySource.put(l.code, found);
                        }
                        if (fresh) {
                            for (Shelf.Book b : found) {
                                hide(b.id, false);
                            }
                        }
                        merge();
                        adopt(found);
                    } catch (Exception e) {
                        error = e.getClass().getSimpleName() + ": " + e.getMessage();
                    }
                }
                if (state == ADDED || state == OFFLINE) {
                    prefs.edit().remove("state:" + l.code).apply();
                } else if (fresh) {
                    /* A link that does not work is not put on the shelf at all. */
                    dropSource(l.code);
                } else {
                    prefs.edit().putInt("state:" + l.code, state).apply();
                }
                if (answer != null) {
                    final int st = state;
                    final String dt = detail == null ? "" : detail;
                    int ch = 0;
                    int bk = found == null ? 0 : found.size();
                    if (found != null) {
                        for (Shelf.Book b : found) {
                            ch += b.chapters.size();
                        }
                    }
                    final int books = bk;
                    final int chapters = ch;
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            answer.told(st, dt, books, chapters);
                        }
                    });
                }
                if (error != null) {
                    trouble = error;
                    Log.note("library: " + error);
                }
                synchronized (Library.this) {
                    asking--;
                }
                tell();
                for (Shelf.Book b : books) {
                    cover(b);
                }
                pump();
            }
        });
    }

    /** Chapters already in a book's folder — from an earlier installation — are taken as brought. */
    private void adopt(ArrayList<Shelf.Book> found) {
        boolean any = false;
        for (Shelf.Book b : found) {
            for (Shelf.Chapter ch : b.chapters) {
                synchronized (kept) {
                    if (kept.containsKey(ch.id) || gone.contains(ch.id)) {
                        continue;
                    }
                }
                String where = Store.find(app, b, ch);
                if (where != null) {
                    synchronized (kept) {
                        kept.put(ch.id, where);
                    }
                    any = true;
                }
            }
        }
        if (any) {
            saveKept();
        }
    }

    /**
     * Looks whether the kept chapters are still where they were. A chapter
     * the listener deleted is marked gone and is not brought again unasked.
     */
    void check() {
        list.execute(new Runnable() {
            @Override
            public void run() {
                HashMap<Long, String> copy;
                synchronized (kept) {
                    copy = new HashMap<Long, String>(kept);
                }
                boolean any = false;
                for (Map.Entry<Long, String> e : copy.entrySet()) {
                    if (!Store.exists(app, e.getValue())) {
                        synchronized (kept) {
                            kept.remove(e.getKey());
                            gone.add(e.getKey());
                            askedFor.remove(e.getKey());
                        }
                        any = true;
                    }
                }
                if (any) {
                    saveKept();
                    tell();
                }
            }
        });
        look();
    }

    /** Takes a book off the shelf and deletes its chapters from the phone. */
    void forget(final Shelf.Book b) {
        if (!b.source.isEmpty()) {
            hide(b.id, true);
        }
        merge();
        tell();
        list.execute(new Runnable() {
            @Override
            public void run() {
                for (Shelf.Chapter ch : b.chapters) {
                    String where;
                    synchronized (kept) {
                        where = kept.remove(ch.id);
                        if (where == null) {
                            where = localKept.remove(ch.id);
                        }
                        gone.remove(ch.id);
                        askedFor.remove(ch.id);
                    }
                    if (where != null) {
                        Store.delete(app, where);
                    }
                    new File(parts(), ch.id + ".part").delete();
                }
                saveKept();
                tell();
                look();
            }
        });
    }

    /** The content addresses of a book's chapters on the phone, for the system's own delete question. */
    ArrayList<String> locations(Shelf.Book b) {
        ArrayList<String> out = new ArrayList<String>();
        for (Shelf.Chapter ch : b.chapters) {
            String w = location(ch);
            if (w != null) {
                out.add(w);
            }
        }
        return out;
    }

    // ------------------------------------------------------------ chapters

    boolean ready(Shelf.Chapter ch) {
        synchronized (kept) {
            return kept.containsKey(ch.id) || localKept.containsKey(ch.id);
        }
    }

    String location(Shelf.Chapter ch) {
        synchronized (kept) {
            String w = kept.get(ch.id);
            return w != null ? w : localKept.get(ch.id);
        }
    }

    /** How many chapters of a book are on the phone. */
    int brought(Shelf.Book b) {
        int n = 0;
        synchronized (kept) {
            for (Shelf.Chapter ch : b.chapters) {
                if (kept.containsKey(ch.id) || localKept.containsKey(ch.id)) {
                    n++;
                }
            }
        }
        return n;
    }

    boolean isGone(Shelf.Chapter ch) {
        synchronized (kept) {
            return gone.contains(ch.id) && !askedFor.contains(ch.id);
        }
    }

    /** A kept chapter that would not open: forgotten, to be brought again. */
    void lost(Shelf.Chapter ch) {
        synchronized (kept) {
            kept.remove(ch.id);
            if (localKept.remove(ch.id) != null) {
                /* A book put by hand has no cloud to bring it from. */
                failures.put(ch.id, 3);
                tell();
                return;
            }
            askedFor.add(ch.id);
            /* A file that will not play however often it is brought is given up on. */
            Integer n = losses.get(ch.id);
            losses.put(ch.id, n == null ? 1 : n + 1);
            if (n != null && n >= 2) {
                failures.put(ch.id, 3);
            }
        }
        saveKept();
        pump();
        tell();
    }

    /** Fetched and total bytes of the chapter on its way, or null. */
    long[] progress(Shelf.Chapter ch) {
        synchronized (kept) {
            return progress.get(ch.id);
        }
    }

    /** The chapter being fetched now, or null. */
    Shelf.Chapter fetching() {
        return fetching;
    }

    boolean failed(Shelf.Chapter ch) {
        synchronized (kept) {
            Integer n = failures.get(ch.id);
            return n != null && n >= 3;
        }
    }

    /** The chapter being heard and the next come before anything else; asked for, they come even if deleted. */
    void hearing(Shelf.Book book, int index) {
        hearingBook = book.id;
        hearingIndex = index;
        synchronized (kept) {
            for (int i = index; i <= index + 1 && i < book.chapters.size(); i++) {
                if (i >= 0) {
                    long id = book.chapters.get(i).id;
                    askedFor.add(id);
                    failures.remove(id);
                }
            }
        }
        pump();
        tell();
    }

    /** Asks again for a chapter that gave up. */
    void retry(Shelf.Chapter ch) {
        synchronized (kept) {
            failures.remove(ch.id);
            askedFor.add(ch.id);
        }
        pump();
        tell();
    }

    /** Whether some book waits for the permission to see files put there by hand. */
    boolean blocked() {
        synchronized (kept) {
            return !blocked.isEmpty();
        }
    }

    /** The permission came: the waiting books look at their folders again and go on. */
    void unblock() {
        synchronized (kept) {
            blocked.clear();
        }
        list.execute(new Runnable() {
            @Override
            public void run() {
                adopt(new ArrayList<Shelf.Book>(books));
                tell();
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        pump();
                    }
                });
            }
        });
    }

    /** Whether anything is still to be brought. */
    boolean busy() {
        return fetching != null || next() != null;
    }

    /**
     * What to bring next: the chapter being heard and the one after it;
     * then the rest of that book, onwards and then back; then the other
     * books, the latest heard first, each from where it was left.
     */
    Shelf.Chapter next() {
        ArrayList<Shelf.Book> order = new ArrayList<Shelf.Book>(books);
        final long first = hearingBook;
        Collections.sort(order, new Comparator<Shelf.Book>() {
            @Override
            public int compare(Shelf.Book a, Shelf.Book b) {
                if (a.id == b.id) {
                    return 0;
                }
                if (a.id == first) {
                    return -1;
                }
                if (b.id == first) {
                    return 1;
                }
                long ta = Places.touched(app, a.id);
                long tb = Places.touched(app, b.id);
                return ta == tb ? 0 : (ta > tb ? -1 : 1);
            }
        });
        for (Shelf.Book b : order) {
            if (b.source.isEmpty() || linkState(b) != ADDED) {
                continue;
            }
            synchronized (kept) {
                if (blocked.contains(b.id)) {
                    continue;
                }
            }
            int n = b.chapters.size();
            int from = b.id == first ? hearingIndex : b.find(Places.chapter(app, b.id));
            from = Math.max(0, Math.min(from, n - 1));
            for (int k = 0; k < n; k++) {
                int i = from + k < n ? from + k : from - 1 - (k - (n - from));
                if (i < 0 || i >= n) {
                    continue;
                }
                Shelf.Chapter ch = b.chapters.get(i);
                synchronized (kept) {
                    if (kept.containsKey(ch.id)) {
                        continue;
                    }
                    if (gone.contains(ch.id) && !askedFor.contains(ch.id)) {
                        continue;
                    }
                    Integer f = failures.get(ch.id);
                    if (f != null && f >= 3) {
                        continue;
                    }
                }
                return ch;
            }
        }
        return null;
    }

    private Shelf.Book owner(Shelf.Chapter ch) {
        for (Shelf.Book b : books) {
            if (b.find(ch.id) >= 0) {
                return b;
            }
        }
        return null;
    }

    private File parts() {
        File dir = new File(app.getCacheDir(), "parts");
        dir.mkdirs();
        return dir;
    }

    /** Brings chapters one after another while there are chapters to bring. */
    void pump() {
        if (Store.needsPermission(app) || next() == null) {
            return;
        }
        synchronized (this) {
            if (pumping) {
                return;
            }
            pumping = true;
        }
        Fetch.keep(app);
        fetch.execute(new Runnable() {
            @Override
            public void run() {
                WifiManager.WifiLock lock = null;
                try {
                    WifiManager wifi = (WifiManager) app.getSystemService(Context.WIFI_SERVICE);
                    if (wifi != null) {
                        lock = wifi.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "bookbinder:fetch");
                        lock.acquire();
                    }
                    Shelf.Chapter ch;
                    int idle = 0;
                    while ((ch = next()) != null) {
                        Shelf.Book b = owner(ch);
                        if (b == null) {
                            break;
                        }
                        fetching = ch;
                        tell();
                        boolean ok = false;
                        try {
                            ok = download(b, ch);
                        } catch (Store.Unseen hidden) {
                            /* Not a failure of the chapter: the book waits for the permission. */
                            Log.note("fetch " + ch.name + ": a file of that name is there but unseen");
                            synchronized (kept) {
                                blocked.add(b.id);
                            }
                            fetching = null;
                            tell();
                            continue;
                        } catch (Exception e) {
                            Log.note("fetch " + ch.name + ": " + e);
                        }
                        synchronized (kept) {
                            progress.remove(ch.id);
                            if (!ok) {
                                Integer n = failures.get(ch.id);
                                failures.put(ch.id, (n == null ? 0 : n) + 1);
                            }
                        }
                        fetching = null;
                        tell();
                        if (!ok) {
                            idle++;
                            Thread.sleep(Math.min(30000L, 4000L * idle));
                        } else {
                            idle = 0;
                        }
                    }
                } catch (InterruptedException stop) {
                    Log.note("fetch: stopped");
                } finally {
                    if (lock != null && lock.isHeld()) {
                        lock.release();
                    }
                    fetching = null;
                    synchronized (Library.this) {
                        pumping = false;
                    }
                    tell();
                    /* Work that came while the loop was ending is not left lying. */
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            pump();
                        }
                    });
                }
            }
        });
    }

    /** Where a chapter can be fetched from right now; the address is short-lived, so it is asked each time. */
    private URL address(Shelf.Book b, Shelf.Chapter ch) throws IOException {
        String code = b.source;
        long file = ch.id;
        Link l = Link.parse(prefs.getString("link:" + code, code));
        if (l != null && l.kind == Link.WEB) {
            return new URL(ch.ref);
        }
        if (l != null && l.kind == Link.SEALED) {
            try {
                return Sealed.address(web, l.text, ch.ref);
            } catch (Sealed.Refused r) {
                int k = classifySealed(r.code);
                if (k != REFUSED) {
                    prefs.edit().putInt("state:" + code, k).apply();
                    tell();
                }
                throw r;
            }
        }
        if (l != null && l.kind == Link.DISK) {
            try {
                return Sources.diskFile(web, l.text, ch.ref);
            } catch (Sources.Refused r) {
                int k = classifyStatus(r.status);
                if (k != REFUSED) {
                    /* The link died while its book was coming in: the shelf says so, fetching stops. */
                    prefs.edit().putInt("state:" + code, k).apply();
                    tell();
                }
                throw r;
            }
        }
        String host = prefs.getString("host:" + code, l == null ? Link.EU : l.first);
        Object answer = Json.parse(text(Link.fetch(host, code, file)));
        long result = Json.num(answer, "result", -1);
        if (result != 0) {
            int k = classify(result);
            if (k != REFUSED) {
                /* The link died while its book was coming in: the shelf says so, fetching stops. */
                prefs.edit().putInt("state:" + code, k).apply();
                tell();
            }
            throw new IOException("code " + result + ": " + Json.str(answer, "error"));
        }
        ArrayList<Object> hosts = Json.list(answer, "hosts");
        String path = Json.str(answer, "path");
        if (hosts.isEmpty() || path == null) {
            throw new IOException("no address");
        }
        return new URL("https", String.valueOf(hosts.get(0)), path);
    }

    /**
     * Fetches into a partial file in the cache, going on where a broken
     * fetch stopped, and moves the whole file into the book's folder.
     */
    private boolean download(Shelf.Book b, Shelf.Chapter ch) throws IOException {
        String there = Store.find(app, b, ch);
        if (there != null) {
            /* Already in the folder — put there by hand, or by an earlier installation. */
            synchronized (kept) {
                kept.put(ch.id, there);
                gone.remove(ch.id);
            }
            saveKept();
            return true;
        }
        Store.check(app, b, ch);
        File part = new File(parts(), ch.id + ".part");
        long have = part.exists() ? part.length() : 0;
        if (ch.size > 0 && have > ch.size) {
            part.delete();
            have = 0;
        }
        if (ch.size <= 0 || have < ch.size) {
            boolean sealed = sealed(b);
            URL where = address(b, ch);
            HttpURLConnection c = (HttpURLConnection) (sealed ? Sealed.from(where, have, ch.size) : where).openConnection();
            c.setConnectTimeout(15000);
            c.setReadTimeout(30000);
            if (have > 0 && !sealed) {
                c.setRequestProperty("Range", "bytes=" + have + "-");
            }
            int code = c.getResponseCode();
            if (code != 200 && code != 206) {
                c.disconnect();
                if (classifyStatus(code) == SPENT) {
                    prefs.edit().putInt("state:" + b.source, SPENT).apply();
                }
                throw new IOException("http " + code);
            }
            if (code == 200 && !sealed) {
                have = 0;
            }
            long total = ch.size > 0 ? ch.size : (have + Math.max(0, c.getContentLength()));
            InputStream in = sealed ? Sealed.open(c.getInputStream(), ch.ref, have) : c.getInputStream();
            OutputStream out = new FileOutputStream(part, have > 0);
            byte[] buf = new byte[64 * 1024];
            long got = have;
            long told = 0;
            try {
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    got += n;
                    synchronized (kept) {
                        progress.put(ch.id, new long[]{got, total});
                    }
                    if (got - told > 512 * 1024) {
                        told = got;
                        tell();
                    }
                }
            } finally {
                out.close();
                in.close();
                c.disconnect();
            }
        }
        if (ch.size > 0 && part.length() != ch.size) {
            throw new IOException("short: " + part.length() + " of " + ch.size);
        }
        String where = Store.put(app, b, ch, part);
        synchronized (kept) {
            kept.put(ch.id, where);
            gone.remove(ch.id);
            failures.remove(ch.id);
        }
        saveKept();
        return true;
    }

    private void saveKept() {
        HashMap<Long, String> k;
        HashMap<Long, String> g = new HashMap<Long, String>();
        synchronized (kept) {
            k = new HashMap<Long, String>(kept);
            for (Long id : gone) {
                g.put(id, "");
            }
        }
        synchronized (this) {
            try {
                writeTable(new File(app.getFilesDir(), "kept.tsv"), k);
                writeTable(new File(app.getFilesDir(), "gone.tsv"), g);
            } catch (IOException e) {
                Log.note("library: could not keep the index: " + e);
            }
        }
    }

    // ------------------------------------------------------------ covers

    Bitmap cover(Shelf.Book b) {
        synchronized (covers) {
            Bitmap known = covers.get(b.id);
            if (known != null) {
                return known;
            }
        }
        final File f = new File(new File(app.getFilesDir(), "covers"), b.id + ".jpg");
        final File none = new File(new File(app.getFilesDir(), "covers"), b.id + ".none");
        if (none.exists()) {
            return null;
        }
        if (f.exists()) {
            Bitmap bm = BitmapFactory.decodeFile(f.getPath());
            if (bm != null) {
                synchronized (covers) {
                    covers.put(b.id, bm);
                }
                return bm;
            }
        }
        final Shelf.Book book = b;
        synchronized (pictureAsked) {
            if (pictureAsked.contains(b.id) || b.chapters.isEmpty()) {
                return null;
            }
            pictureAsked.add(b.id);
        }
        pictures.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    Shelf.Chapter first = book.chapters.get(0);
                    byte[] head = local(first, 256 * 1024);
                    URL where = null;
                    if (head == null) {
                        if (book.source.isEmpty()) {
                            synchronized (pictureAsked) {
                                pictureAsked.remove(book.id);
                            }
                            return;
                        }
                        where = address(book, first);
                        head = sealed(book) ? sealedHead(where, first, 256 * 1024) : range(where, 256 * 1024);
                    }
                    int whole = Tags.length(head);
                    if (whole > head.length) {
                        int want = Math.min(whole, 4 * 1024 * 1024);
                        head = local(first, want);
                        if (head == null && !book.source.isEmpty()) {
                            URL at = where != null ? where : address(book, first);
                            head = sealed(book) ? sealedHead(at, first, want) : range(at, want);
                        }
                    }
                    byte[] pic = head == null ? null : Tags.picture(head);
                    Bitmap raw = pic == null ? null : BitmapFactory.decodeByteArray(pic, 0, pic.length);
                    if (raw == null) {
                        /* No picture in the tag: remembered, so it is not asked again. */
                        none.getParentFile().mkdirs();
                        new FileOutputStream(none).close();
                        return;
                    }
                    int side = 640;
                    float k = Math.min(1f, side / (float) Math.max(raw.getWidth(), raw.getHeight()));
                    Bitmap small = k < 1f ? Bitmap.createScaledBitmap(raw,
                        Math.round(raw.getWidth() * k), Math.round(raw.getHeight() * k), true) : raw;
                    f.getParentFile().mkdirs();
                    FileOutputStream out = new FileOutputStream(f);
                    small.compress(Bitmap.CompressFormat.JPEG, 88, out);
                    out.close();
                    synchronized (covers) {
                        covers.put(book.id, small);
                    }
                    tell();
                } catch (Exception e) {
                    Log.note("cover " + book.folder + ": " + e);
                    synchronized (pictureAsked) {
                        pictureAsked.remove(book.id);
                    }
                }
            }
        });
        return null;
    }

    /** The head of a chapter already on the phone, or null. */
    private byte[] local(Shelf.Chapter ch, int bytes) {
        String where = location(ch);
        if (where == null) {
            return null;
        }
        try {
            InputStream in = Store.open(app, where);
            try {
                return head(in, bytes);
            } finally {
                in.close();
            }
        } catch (IOException e) {
            return null;
        }
    }

    private boolean sealed(Shelf.Book b) {
        Link l = Link.parse(prefs.getString("link:" + b.source, b.source));
        return l != null && l.kind == Link.SEALED;
    }

    /** The head of a sealed file: the range is asked in the address, the bytes opened from the start. */
    private static byte[] sealedHead(URL address, Shelf.Chapter ch, int bytes) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(address.toString() + "/0-" + (bytes - 1)).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(30000);
        try {
            InputStream in = Sealed.open(c.getInputStream(), ch.ref, 0);
            try {
                return head(in, bytes);
            } finally {
                in.close();
            }
        } finally {
            c.disconnect();
        }
    }

    private static byte[] range(URL address, int bytes) throws IOException {
        HttpURLConnection c = (HttpURLConnection) address.openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(30000);
        c.setRequestProperty("Range", "bytes=0-" + (bytes - 1));
        try {
            InputStream in = c.getInputStream();
            try {
                return head(in, bytes);
            } finally {
                in.close();
            }
        } finally {
            c.disconnect();
        }
    }

    private static byte[] head(InputStream in, int bytes) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[32 * 1024];
        int n;
        while (out.size() < bytes && (n = in.read(buf, 0, Math.min(buf.length, bytes - out.size()))) > 0) {
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }

    // ------------------------------------------------------------ plain io

    private static String text(URL address) throws IOException {
        HttpURLConnection c = (HttpURLConnection) address.openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(30000);
        try {
            int code = c.getResponseCode();
            InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
            if (in == null) {
                throw new IOException("http " + code);
            }
            try {
                return new String(head(in, 16 * 1024 * 1024), "UTF-8");
            } finally {
                in.close();
            }
        } finally {
            c.disconnect();
        }
    }

    private static String read(File f) throws IOException {
        InputStream in = new FileInputStream(f);
        try {
            return new String(head(in, (int) Math.min(Integer.MAX_VALUE, f.length())), "UTF-8");
        } finally {
            in.close();
        }
    }

    private static void write(File f, String s) throws IOException {
        File tmp = new File(f.getPath() + ".tmp");
        OutputStream out = new FileOutputStream(tmp);
        try {
            out.write(s.getBytes("UTF-8"));
        } finally {
            out.close();
        }
        if (!tmp.renameTo(f)) {
            throw new IOException("rename failed");
        }
    }

    private static void readTable(File f, HashMap<Long, String> into) {
        if (!f.exists()) {
            return;
        }
        try {
            for (String line : read(f).split("\n")) {
                int tab = line.indexOf('\t');
                String id = tab < 0 ? line : line.substring(0, tab);
                if (id.trim().isEmpty()) {
                    continue;
                }
                into.put(Long.parseLong(id.trim()), tab < 0 ? "" : line.substring(tab + 1));
            }
        } catch (Exception e) {
            Log.note("library: table " + f.getName() + ": " + e);
        }
    }

    private static void writeTable(File f, HashMap<Long, String> t) throws IOException {
        StringBuilder b = new StringBuilder();
        for (Map.Entry<Long, String> e : t.entrySet()) {
            b.append(e.getKey()).append('\t').append(e.getValue()).append('\n');
        }
        write(f, b.toString());
    }
}
