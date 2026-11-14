package com.shumtugle.bookbinder;

import java.util.Locale;

/**
 * A shared-folder link taken apart: the code that names the folder, and which
 * of the service's two regions to ask first. Accounts live in one region or
 * the other and a code is only known in its own, so the other is the
 * fallback, and the one that answered is remembered.
 */
final class Link {

    static final String EU = "eapi.pcloud.com";
    static final String US = "api.pcloud.com";

    final String code;
    final String first;
    final String second;

    private Link(String code, boolean eu) {
        this.code = code;
        this.first = eu ? EU : US;
        this.second = eu ? US : EU;
    }

    /** Null when nothing in the text looks like a link or a code. */
    static Link parse(String text) {
        if (text == null) {
            return null;
        }
        String t = text.trim();
        int sp = t.indexOf("http");
        if (sp > 0) {
            t = t.substring(sp);
        }
        int ws = firstSpace(t);
        if (ws > 0) {
            t = t.substring(0, ws);
        }
        String low = t.toLowerCase(Locale.ROOT);
        boolean eu = low.contains("e.pcloud.link") || low.contains("e.pcloud.com") || low.contains("eapi.");
        int at = low.indexOf("code=");
        if (at >= 0) {
            String c = t.substring(at + 5);
            int end = 0;
            while (end < c.length() && codeChar(c.charAt(end))) {
                end++;
            }
            c = c.substring(0, end);
            return c.isEmpty() ? null : new Link(c, eu);
        }
        if (low.startsWith("http")) {
            /* A short link carries the code as its only path segment. */
            int slash = t.lastIndexOf('/');
            String c = slash >= 0 ? t.substring(slash + 1) : "";
            int end = 0;
            while (end < c.length() && codeChar(c.charAt(end))) {
                end++;
            }
            c = c.substring(0, end);
            return c.length() >= 4 ? new Link(c, eu) : null;
        }
        for (int i = 0; i < t.length(); i++) {
            if (!codeChar(t.charAt(i))) {
                return null;
            }
        }
        return t.length() >= 8 ? new Link(t, eu) : null;
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
