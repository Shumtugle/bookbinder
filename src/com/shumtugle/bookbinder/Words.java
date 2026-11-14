package com.shumtugle.bookbinder;

import android.content.Context;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.HashMap;
import java.util.Locale;

/**
 * The interface's words, one table per language in the assets. The language
 * is the reader's choice inside the application, not the phone's: a phone
 * set to one language may belong to a reader of another. Until a choice is
 * made, the phone's language is used if there is a table for it.
 */
final class Words {

    static final String[] LANGS = {"ru", "en", "fi"};

    private static final HashMap<String, String> table = new HashMap<String, String>();
    private static final HashMap<String, String> english = new HashMap<String, String>();
    private static String current = "";

    private Words() {
    }

    static String lang() {
        return current;
    }

    static synchronized void load(Context c) {
        String want = Places.language(c);
        if (want.isEmpty()) {
            String sys = Locale.getDefault().getLanguage();
            want = "en";
            for (String l : LANGS) {
                if (l.equals(sys)) {
                    want = l;
                }
            }
        }
        if (want.equals(current) && !table.isEmpty()) {
            return;
        }
        if (english.isEmpty()) {
            read(c, "en", english);
        }
        table.clear();
        read(c, want, table);
        current = want;
    }

    static void choose(Context c, String lang) {
        Places.language(c, lang);
        current = "";
        load(c);
    }

    /** The next language after the current one, for a single key that cycles them. */
    static String after() {
        for (int i = 0; i < LANGS.length; i++) {
            if (LANGS[i].equals(current)) {
                return LANGS[(i + 1) % LANGS.length];
            }
        }
        return LANGS[0];
    }

    static String s(String key) {
        String v = table.get(key);
        if (v == null) {
            v = english.get(key);
        }
        return v == null ? key : v;
    }

    /**
     * A number with its noun in the right form: "<key>_one", "<key>_few" or
     * "<key>_many" from the table. The rule of forms is the language's own;
     * a table without "few" falls back to "many".
     */
    static String count(String key, int n) {
        String form;
        if ("ru".equals(current)) {
            int ten = n % 10;
            int hundred = n % 100;
            form = ten == 1 && hundred != 11 ? "one"
                : ten >= 2 && ten <= 4 && (hundred < 12 || hundred > 14) ? "few" : "many";
        } else {
            form = n == 1 ? "one" : "many";
        }
        String v = table.get(key + "_" + form);
        if (v == null && form.equals("few")) {
            v = table.get(key + "_many");
        }
        if (v == null) {
            v = english.get(key + "_" + (n == 1 ? "one" : "many"));
        }
        return String.format(Locale.ROOT, v == null ? "%1$d" : v, n);
    }

    static String f(String key, Object... args) {
        return String.format(Locale.ROOT, s(key), args);
    }

    private static void read(Context c, String lang, HashMap<String, String> into) {
        try {
            BufferedReader r = new BufferedReader(new InputStreamReader(
                c.getAssets().open("lang/" + lang + ".txt"), "UTF-8"));
            try {
                String line;
                while ((line = r.readLine()) != null) {
                    int eq = line.indexOf('=');
                    if (eq > 0 && !line.startsWith("#")) {
                        into.put(line.substring(0, eq).trim(), line.substring(eq + 1));
                    }
                }
            } finally {
                r.close();
            }
        } catch (Exception missing) {
            Log.note("words: no table for " + lang);
        }
    }
}
