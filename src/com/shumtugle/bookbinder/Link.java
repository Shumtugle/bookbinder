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

    /** Null when nothing in the text looks like a link or a code. */
    static Link parse(String text) {
        if (text == null) {
            return null;
        }
        String t = text.trim();
        int sp = t.toLowerCase(Locale.ROOT).indexOf("http");
        int mg = t.toLowerCase(Locale.ROOT).indexOf("magnet:");
        if (mg >= 0 && (sp < 0 || mg < sp)) {
            sp = mg;
        }
        if (sp > 0) {
            t = t.substring(sp);
        }
        int ws = firstSpace(t);
        if (ws > 0) {
            t = t.substring(0, ws);
        }
        String low = t.toLowerCase(Locale.ROOT);
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
                return new Link(LATER, "x" + mark(plain), plain, false);
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

    private static int firstSpace(String t) {
        for (int i = 0; i < t.length(); i++) {
            if (Character.isWhitespace(t.charAt(i))) {
                return i;
            }
        }
        return -1;
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
