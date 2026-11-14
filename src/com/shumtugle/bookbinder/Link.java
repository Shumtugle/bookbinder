package com.shumtugle.bookbinder;

import java.util.Locale;

/**
 * A link taken apart: which kind of folder it opens and a key that names
 * it on the shelf. The first kind answers with a code and lives in one of
 * two regions — the other is the fallback, and the one that answered is
 * remembered. A public disk folder and a plain web folder are named by
 * their address. Kinds that are known but not read yet are recognised, so
 * the listener hears "not yet" rather than "not a link".
 */
final class Link {

    static final String EU = "eapi.pcloud.com";
    static final String US = "api.pcloud.com";

    static final char CODE = 'p';
    static final char DISK = 'y';
    static final char WEB = 'h';
    /** A folder whose key travels in the link after the hash mark. */
    static final char SEALED = 'm';
    /** Recognised, not read yet. */
    static final char LATER = 'x';

    final char kind;
    /** The name of the link on the shelf: the code for the first kind, else a short mark of the address. */
    final String code;
    /** The link as it was given, cut to the link itself. */
    final String text;
    final String first;
    final String second;

    private Link(char kind, String code, String text, boolean eu) {
        this.kind = kind;
        this.code = code;
        this.text = text;
        this.first = eu ? EU : US;
        this.second = eu ? US : EU;
    }

    /*
     * A link usually comes wrapped: "The folder is here: «<link>»", a line
     * break, a word of description, a full stop after it. Every address in
     * the text is taken out and stripped of the punctuation that clings to
     * it; a folder of a known kind wins over a plain web address, and the
     * earlier wins among equals.
     */
    private static final java.util.regex.Pattern ADDRESS = java.util.regex.Pattern.compile(
        "(?i)(?:https?:/{2}|magnet:\\?)[^\\s<>\"'\u00ab\u00bb\u201c\u201d\u201e\u2018\u2019]+");
    private static final String CLINGS = ".,;:!?)]}>\u2026\u00bb\u201d\u2019*_";

    /** Every address in a text, in order, without the punctuation around it. */
    static java.util.ArrayList<String> addresses(String text) {
        java.util.ArrayList<String> out = new java.util.ArrayList<String>();
        java.util.regex.Matcher m = ADDRESS.matcher(text);
        while (m.find()) {
            String a = m.group();
            while (!a.isEmpty() && CLINGS.indexOf(a.charAt(a.length() - 1)) >= 0) {
                /* A closing bracket stays when the address itself opened one. */
                char last = a.charAt(a.length() - 1);
                if (last == ')' && a.indexOf('(') >= 0 && count(a, '(') >= count(a, ')')) {
                    break;
                }
                a = a.substring(0, a.length() - 1);
            }
            if (a.length() > 10) {
                out.add(a);
            }
        }
        return out;
    }

    private static int count(String s, char c) {
        int n = 0;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == c) {
                n++;
            }
        }
        return n;
    }

    /** Null when nothing in the text looks like a link or a code. */
    static Link parse(String text) {
        if (text == null) {
            return null;
        }
        Link best = null;
        for (String a : addresses(text)) {
            Link l = one(a);
            if (l != null && (best == null || best.kind == WEB && l.kind != WEB)) {
                best = l;
            }
        }
        if (best != null) {
            return best;
        }
        return one(text.trim());
    }

    /** One address, or a bare code, already cut out of the text. */
    private static Link one(String t) {
        String low = t.toLowerCase(Locale.ROOT);
        if (low.indexOf(' ') >= 0 || low.indexOf('\n') >= 0) {
            return null;
        }
        if (low.startsWith("magnet:")) {
            return new Link(LATER, "x" + mark(t), t, false);
        }
        String host = "";
        int colon0 = low.indexOf(':');
        String scheme = colon0 > 0 ? low.substring(0, colon0) : "";
        if ((scheme.equals("http") || scheme.equals("https")) && low.startsWith("//", colon0 + 1)) {
            int from = colon0 + 3;
            int to = from;
            while (to < low.length() && "/?#".indexOf(low.charAt(to)) < 0) {
                to++;
            }
            host = low.substring(from, to);
            int colon = host.indexOf(':');
            if (colon >= 0) {
                host = host.substring(0, colon);
            }
        }
        boolean eu = low.contains("e.pcloud.link") || low.contains("e.pcloud.com") || low.contains("eapi.");
        boolean code = host.endsWith("pcloud.link") || host.endsWith("pcloud.com") || host.equals("pc.cd");
        int at = low.indexOf("code=");
        if (code && at >= 0 || host.isEmpty() && at >= 0) {
            String c = cut(t.substring(at + 5));
            return c.isEmpty() ? null : new Link(CODE, c, t, eu);
        }
        if (host.equals("pc.cd")) {
            int slash = t.lastIndexOf('/');
            String c = cut(slash >= 0 ? t.substring(slash + 1) : "");
            return c.length() >= 4 ? new Link(CODE, c, t, eu) : null;
        }
        if (!host.isEmpty()) {
            String plain = t;
            int hash = plain.indexOf('#');
            boolean keyInside = host.startsWith("mega.") || host.endsWith(".mega.nz");
            if (hash >= 0 && !keyInside) {
                plain = plain.substring(0, hash);
            }
            if (keyInside) {
                return new Link(hash >= 0 ? SEALED : LATER, "m" + mark(plain), plain, false);
            }
            if (host.startsWith("disk.yandex.") || host.equals("yadi.sk") || host.endsWith(".yadi.sk")) {
                return new Link(DISK, "y" + mark(plain), plain, false);
            }
            return new Link(WEB, "h" + mark(plain), plain, false);
        }
        /* A bare code of the first kind. */
        for (int i = 0; i < t.length(); i++) {
            if (!codeChar(t.charAt(i))) {
                return null;
            }
        }
        return t.length() >= 8 ? new Link(CODE, t, t, eu) : null;
    }

    private static String cut(String c) {
        int end = 0;
        while (end < c.length() && codeChar(c.charAt(end))) {
            end++;
        }
        return c.substring(0, end);
    }

    /** A short mark of an address, the same every time. */
    private static String mark(String s) {
        java.util.zip.CRC32 a = new java.util.zip.CRC32();
        try {
            a.update(s.getBytes("UTF-8"));
        } catch (java.io.UnsupportedEncodingException never) {
            a.update(s.getBytes());
        }
        return Long.toHexString(a.getValue()) + Integer.toHexString(s.length());
    }

    private static boolean codeChar(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '_' || c == '-';
    }

    static java.net.URL list(String host, String code) throws java.net.MalformedURLException {
        return new java.net.URL("https", host, "/showpublink?code=" + code);
    }

    static java.net.URL fetch(String host, String code, long file) throws java.net.MalformedURLException {
        return new java.net.URL("https", host, "/getpublinkdownload?code=" + code + "&fileid=" + file);
    }
}
