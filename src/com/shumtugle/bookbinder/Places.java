package com.shumtugle.bookbinder;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Where each book was left: which chapter, and how far into it. A chapter is
 * named by its id in the shared folder, so renaming a file or adding one in
 * the middle does not move the listener. Also the book that played last,
 * the pace, and the language.
 */
final class Places {

    private Places() {
    }

    private static SharedPreferences p(Context c) {
        return c.getSharedPreferences("places", Context.MODE_PRIVATE);
    }

    static long chapter(Context c, long book) {
        return p(c).getLong("c" + book, -1);
    }

    static long position(Context c, long book) {
        return p(c).getLong("p" + book, 0);
    }

    static boolean finished(Context c, long book) {
        return p(c).getBoolean("f" + book, false);
    }

    static void put(Context c, long book, long chapter, long position) {
        p(c).edit().putLong("c" + book, chapter).putLong("p" + book, position)
            .putBoolean("f" + book, false).putLong("t" + book, System.currentTimeMillis()).apply();
    }

    /** When the book was last heard; 0 if never. */
    static long touched(Context c, long book) {
        return p(c).getLong("t" + book, 0);
    }

    /** When the book was last opened on the shelf, heard or not; 0 if never. */
    static long opened(Context c, long book) {
        return p(c).getLong("o" + book, 0);
    }

    static void opened(Context c, long book, long when) {
        p(c).edit().putLong("o" + book, when).apply();
    }

    /** How long the chapter the place is in lasts, once it has been played; 0 if unknown. */
    static long span(Context c, long book) {
        return p(c).getLong("d" + book, 0);
    }

    static void span(Context c, long book, long ms) {
        p(c).edit().putLong("d" + book, ms).apply();
    }

    static void finish(Context c, long book) {
        p(c).edit().putBoolean("f" + book, true).apply();
    }

    static long last(Context c) {
        return p(c).getLong("last", -1);
    }

    static void last(Context c, long book) {
        p(c).edit().putLong("last", book).apply();
    }

    static float pace(Context c) {
        return p(c).getFloat("pace", 1f);
    }

    static void pace(Context c, float v) {
        p(c).edit().putFloat("pace", v).apply();
    }

    /** "light", "dark" or "system". */
    static String theme(Context c) {
        return p(c).getString("theme", "light");
    }

    static void theme(Context c, String v) {
        p(c).edit().putString("theme", v).apply();
    }

    /** The palette: "paper", "warm" or "wallpaper". */
    static String palette(Context c) {
        return p(c).getString("palette", "paper");
    }

    static void palette(Context c, String v) {
        p(c).edit().putString("palette", v).apply();
    }

    /** How round the corners are: "sharp", "soft" or "round". */
    static String corners(Context c) {
        return p(c).getString("corners", "soft");
    }

    static void corners(Context c, String v) {
        p(c).edit().putString("corners", v).apply();
    }

    /** How the shelf is shown: "cards" or "covers". */
    static String shelf(Context c) {
        return p(c).getString("shelf", "cards");
    }

    static void shelf(Context c, String v) {
        p(c).edit().putString("shelf", v).apply();
    }

    /** How solid the widgets' ground is, in percent: 100 solid, 0 none. */
    static int widgetGround(Context c) {
        return p(c).getInt("widgetGround", 100);
    }

    static void widgetGround(Context c, int v) {
        p(c).edit().putInt("widgetGround", v).apply();
    }

    /** Seconds a book steps back when taken up after a pause. */
    static int rollback(Context c) {
        return p(c).getInt("rollback", 3);
    }

    static void rollback(Context c, int v) {
        p(c).edit().putInt("rollback", v).apply();
    }

    static String language(Context c) {
        return p(c).getString("lang", "");
    }

    static void language(Context c, String v) {
        p(c).edit().putString("lang", v).apply();
    }
}
