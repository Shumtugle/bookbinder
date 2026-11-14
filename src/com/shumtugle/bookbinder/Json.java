package com.shumtugle.bookbinder;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Just enough of a JSON reader for the answers of a storage service: objects
 * become maps, arrays lists, numbers longs or doubles, the rest strings,
 * booleans and null. Kept in plain Java so the library logic can be checked
 * on a desktop machine, where the platform's own reader is only a stub.
 */
final class Json {

    private final String s;
    private int at;

    private Json(String s) {
        this.s = s;
    }

    static Object parse(String text) {
        Json j = new Json(text);
        j.space();
        Object v = j.value();
        j.space();
        if (j.at != j.s.length()) {
            throw new IllegalArgumentException("trailing text at " + j.at);
        }
        return v;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> obj(Object o, String key) {
        Object v = o instanceof Map ? ((Map<String, Object>) o).get(key) : null;
        return v instanceof Map ? (Map<String, Object>) v : null;
    }

    @SuppressWarnings("unchecked")
    static ArrayList<Object> list(Object o, String key) {
        Object v = o instanceof Map ? ((Map<String, Object>) o).get(key) : null;
        return v instanceof ArrayList ? (ArrayList<Object>) v : new ArrayList<Object>();
    }

    @SuppressWarnings("unchecked")
    static String str(Object o, String key) {
        Object v = o instanceof Map ? ((Map<String, Object>) o).get(key) : null;
        return v == null ? null : String.valueOf(v);
    }

    @SuppressWarnings("unchecked")
    static long num(Object o, String key, long fallback) {
        Object v = o instanceof Map ? ((Map<String, Object>) o).get(key) : null;
        if (v instanceof Long) {
            return (Long) v;
        }
        if (v instanceof Double) {
            return (long) (double) (Double) v;
        }
        if (v instanceof String) {
            try {
                return Long.parseLong((String) v);
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    @SuppressWarnings("unchecked")
    static boolean bool(Object o, String key) {
        Object v = o instanceof Map ? ((Map<String, Object>) o).get(key) : null;
        return Boolean.TRUE.equals(v);
    }

    // ------------------------------------------------------------ reading

    private void space() {
        while (at < s.length()) {
            char c = s.charAt(at);
            if (c == ' ' || c == '\n' || c == '\r' || c == '\t') {
                at++;
            } else {
                break;
            }
        }
    }

    private Object value() {
        if (at >= s.length()) {
            throw new IllegalArgumentException("unexpected end");
        }
        char c = s.charAt(at);
        if (c == '{') {
            return object();
        }
        if (c == '[') {
            return array();
        }
        if (c == '"') {
            return string();
        }
        if (s.startsWith("true", at)) {
            at += 4;
            return Boolean.TRUE;
        }
        if (s.startsWith("false", at)) {
            at += 5;
            return Boolean.FALSE;
        }
        if (s.startsWith("null", at)) {
            at += 4;
            return null;
        }
        return number();
    }

    private Map<String, Object> object() {
        LinkedHashMap<String, Object> m = new LinkedHashMap<String, Object>();
        at++;
        space();
        if (peek() == '}') {
            at++;
            return m;
        }
        while (true) {
            space();
            String k = string();
            space();
            expect(':');
            space();
            m.put(k, value());
            space();
            char c = s.charAt(at++);
            if (c == '}') {
                return m;
            }
            if (c != ',') {
                throw new IllegalArgumentException("expected , or } at " + (at - 1));
            }
        }
    }

    private ArrayList<Object> array() {
        ArrayList<Object> a = new ArrayList<Object>();
        at++;
        space();
        if (peek() == ']') {
            at++;
            return a;
        }
        while (true) {
            space();
            a.add(value());
            space();
            char c = s.charAt(at++);
            if (c == ']') {
                return a;
            }
            if (c != ',') {
                throw new IllegalArgumentException("expected , or ] at " + (at - 1));
            }
        }
    }

    private String string() {
        expect('"');
        StringBuilder b = new StringBuilder();
        while (true) {
            char c = s.charAt(at++);
            if (c == '"') {
                return b.toString();
            }
            if (c != '\\') {
                b.append(c);
                continue;
            }
            char e = s.charAt(at++);
            switch (e) {
                case 'n': b.append('\n'); break;
                case 't': b.append('\t'); break;
                case 'r': b.append('\r'); break;
                case 'b': b.append('\b'); break;
                case 'f': b.append('\f'); break;
                case 'u':
                    b.append((char) Integer.parseInt(s.substring(at, at + 4), 16));
                    at += 4;
                    break;
                default: b.append(e);
            }
        }
    }

    private Object number() {
        int from = at;
        boolean real = false;
        while (at < s.length()) {
            char c = s.charAt(at);
            if (c == '.' || c == 'e' || c == 'E') {
                real = true;
            } else if (!(c == '-' || c == '+' || (c >= '0' && c <= '9'))) {
                break;
            }
            at++;
        }
        String n = s.substring(from, at);
        if (n.isEmpty()) {
            throw new IllegalArgumentException("unexpected '" + s.charAt(from) + "' at " + from);
        }
        if (real) {
            return Double.parseDouble(n);
        }
        try {
            return Long.parseLong(n);
        } catch (NumberFormatException big) {
            return Double.parseDouble(n);
        }
    }

    private char peek() {
        return at < s.length() ? s.charAt(at) : 0;
    }

    private void expect(char c) {
        if (at >= s.length() || s.charAt(at) != c) {
            throw new IllegalArgumentException("expected " + c + " at " + at);
        }
        at++;
    }

    /** Quotes a string for writing JSON back. */
    static String quote(String v) {
        StringBuilder b = new StringBuilder("\"");
        for (int i = 0; i < v.length(); i++) {
            char c = v.charAt(i);
            if (c == '"' || c == '\\') {
                b.append('\\').append(c);
            } else if (c < 0x20) {
                b.append(String.format("\\u%04x", (int) c));
            } else {
                b.append(c);
            }
        }
        return b.append('"').toString();
    }
}
