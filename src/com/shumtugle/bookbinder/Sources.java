package com.shumtugle.bookbinder;

import java.io.IOException;
import java.net.URL;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Folders shared by other kinds of link: a public disk folder with an open
 * listing service, and a plain web folder — a server that answers a folder's
 * address with a page of links to its files. Each is read into the same
 * shape the first kind of link answers with, so the shelf reads them all
 * alike; every file keeps a reference to fetch it by later.
 *
 * Plain Java with the fetching handed in, so the reading is checked on a
 * desktop against sample answers.
 */
final class Sources {

    /** Fetches text from an address; a refusal arrives as an exception carrying the status. */
    interface Fetch {
        String text(URL u) throws IOException;

        /** The length of the file at an address, or -1 when the server does not say. */
        long length(URL u) throws IOException;

        /** Sends a body and reads the answer as text. */
        String post(URL u, String body) throws IOException;
    }

    /** A refusal with a status code from the server. */
    static final class Refused extends IOException {
        final int status;

        Refused(int status, String what) {
            super(what);
            this.status = status;
        }
    }

    private Sources() {
    }

    static final String DISK_API = "cloud-api.yandex.net";

    /** A number for a path within a link that does not change between askings, kept clear of other kinds. */
    static long id(String sourceKey, String path) {
        java.util.zip.CRC32 a = new java.util.zip.CRC32();
        java.util.zip.CRC32 b = new java.util.zip.CRC32();
        try {
            a.update((sourceKey + "\n" + path).getBytes("UTF-8"));
            b.update((path + "\n" + sourceKey).getBytes("UTF-8"));
        } catch (java.io.UnsupportedEncodingException never) {
            a.update(path.getBytes());
        }
        return (1L << 61) | ((a.getValue() << 29) ^ b.getValue()) & ((1L << 61) - 1);
    }

    private static String enc(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8");
        } catch (java.io.UnsupportedEncodingException never) {
            return s;
        }
    }

    // ------------------------------------------------------------ the public disk

    /**
     * The whole tree under a public disk link, in the shared shape. The
     * service lists a folder a page at a time; folders inside are walked
     * a few levels down.
     */
    static String disk(Fetch f, String link, String key) throws IOException {
        Object root = Json.parse(f.text(new URL("https", DISK_API,
            "/v1/disk/public/resources?public_key=" + enc(link) + "&limit=1")));
        String name = Json.str(root, "name");
        StringBuilder out = new StringBuilder("{\"result\":0,\"metadata\":");
        if ("file".equals(Json.str(root, "type"))) {
            /* One shared file: a book of one chapter, named after the file. */
            String n = name == null ? "book" : name;
            int dot = n.lastIndexOf('.');
            out.append("{\"isfolder\":true,\"name\":").append(Json.quote(dot > 0 ? n.substring(0, dot) : n))
                .append(",\"folderid\":").append(id(key, "/")).append(",\"contents\":[")
                .append(file(key, n, "", Json.num(root, "size", 0), Json.str(root, "mime_type"))).append("]}");
        } else {
            diskFolder(f, link, key, "/", name == null ? "" : name, 0, out);
        }
        return out.append('}').toString();
    }

    private static void diskFolder(Fetch f, String link, String key, String path, String name, int depth,
                                   StringBuilder out) throws IOException {
        out.append("{\"isfolder\":true,\"name\":").append(Json.quote(name))
            .append(",\"folderid\":").append(id(key, path)).append(",\"contents\":[");
        boolean first = true;
        int offset = 0;
        while (true) {
            Object page = Json.parse(f.text(new URL("https", DISK_API, "/v1/disk/public/resources?public_key="
                + enc(link) + "&path=" + enc(path) + "&limit=200&offset=" + offset)));
            Object list = Json.obj(page, "_embedded");
            ArrayList<Object> items = Json.list(list, "items");
            for (Object it : items) {
                String type = Json.str(it, "type");
                String n = Json.str(it, "name");
                String p = Json.str(it, "path");
                if (n == null || p == null) {
                    continue;
                }
                if ("dir".equals(type)) {
                    if (depth < 4) {
                        if (!first) {
                            out.append(',');
                        }
                        first = false;
                        diskFolder(f, link, key, p, n, depth + 1, out);
                    }
                } else if (Shelf.sound(n, Json.str(it, "mime_type"))) {
                    if (!first) {
                        out.append(',');
                    }
                    first = false;
                    out.append(file(key, n, p, Json.num(it, "size", 0), Json.str(it, "mime_type")));
                }
            }
            long total = Json.num(list, "total", 0);
            offset += items.size();
            if (items.isEmpty() || offset >= total) {
                break;
            }
        }
        out.append("]}");
    }

    /** Where one file of a public disk link can be fetched right now; such addresses are short-lived. */
    static URL diskFile(Fetch f, String link, String path) throws IOException {
        String q = "/v1/disk/public/resources/download?public_key=" + enc(link)
            + (path.isEmpty() ? "" : "&path=" + enc(path));
        String href = Json.str(Json.parse(f.text(new URL("https", DISK_API, q))), "href");
        if (href == null) {
            throw new IOException("no address");
        }
        return new URL(href);
    }

    // ------------------------------------------------------------ the plain web folder

    private static final Pattern HREF = Pattern.compile("href\\s*=\\s*[\"']([^\"'#]+)[\"']", Pattern.CASE_INSENSITIVE);

    /**
     * A web folder's page read as a folder: every link to a sound file in it
     * is a chapter, every link that ends in a slash and stays below it is a
     * folder inside. Sorting links, the way up and other sites are passed by.
     */
    static String web(Fetch f, String address, String key) throws IOException {
        URL one = new URL(address);
        String n1 = lastName(one);
        if (Shelf.sound(n1, null)) {
            /* The address of one sound file: a book of one chapter. */
            long size = -1;
            try {
                size = f.length(one);
            } catch (IOException silent) {
                size = -1;
            }
            int dot = n1.lastIndexOf('.');
            return "{\"result\":0,\"metadata\":{\"isfolder\":true,\"name\":" + Json.quote(dot > 0 ? n1.substring(0, dot) : n1)
                + ",\"folderid\":" + id(key, "/") + ",\"contents\":[" + file(key, n1, one.toString(), Math.max(0, size), null) + "]}}";
        }
        URL base = new URL(address.endsWith("/") ? address : address + "/");
        StringBuilder out = new StringBuilder("{\"result\":0,\"metadata\":");
        webFolder(f, base, base, key, 0, new HashSet<String>(), out);
        return out.append('}').toString();
    }

    private static void webFolder(Fetch f, URL top, URL here, String key, int depth, HashSet<String> seen,
                                  StringBuilder out) throws IOException {
        String page = f.text(here);
        out.append("{\"isfolder\":true,\"name\":").append(Json.quote(lastName(here)))
            .append(",\"folderid\":").append(id(key, here.getPath())).append(",\"contents\":[");
        boolean first = true;
        Matcher m = HREF.matcher(page);
        ArrayList<URL> folders = new ArrayList<URL>();
        while (m.find()) {
            String h = m.group(1).trim().replace("&amp;", "&");
            if (h.isEmpty() || h.startsWith("?") || h.startsWith("mailto:") || h.startsWith("javascript:")) {
                continue;
            }
            URL u;
            try {
                u = new URL(here, h);
            } catch (IOException bad) {
                continue;
            }
            if (u.getQuery() != null || !u.getHost().equalsIgnoreCase(top.getHost())
                || !u.getPath().startsWith(top.getPath()) || !seen.add(u.getPath())) {
                continue;
            }
            if (u.getPath().endsWith("/")) {
                if (u.getPath().length() > here.getPath().length() && depth < 3) {
                    folders.add(u);
                }
                continue;
            }
            String n = lastName(u);
            if (!Shelf.sound(n, null)) {
                continue;
            }
            long size = -1;
            try {
                size = f.length(u);
            } catch (IOException silent) {
                size = -1;
            }
            if (!first) {
                out.append(',');
            }
            first = false;
            out.append(file(key, n, u.toString(), Math.max(0, size), null));
        }
        for (URL u : folders) {
            if (!first) {
                out.append(',');
            }
            first = false;
            webFolder(f, top, u, key, depth + 1, seen, out);
        }
        out.append("]}");
    }

    /** The last part of an address's path, decoded: a folder's or a file's name. */
    static String lastName(URL u) {
        String p = u.getPath();
        if (p.endsWith("/")) {
            p = p.substring(0, p.length() - 1);
        }
        String n = p.substring(p.lastIndexOf('/') + 1);
        try {
            n = URLDecoder.decode(n.replace("+", "%2B"), "UTF-8");
        } catch (Exception keep) {
            /* A name that is not well encoded is shown as it is. */
        }
        return n.isEmpty() ? u.getHost() : n;
    }

    private static String file(String key, String name, String ref, long size, String type) {
        return "{\"isfolder\":false,\"name\":" + Json.quote(name) + ",\"fileid\":" + id(key, ref.isEmpty() ? name : ref)
            + ",\"size\":" + size + ",\"ref\":" + Json.quote(ref)
            + (type == null ? "" : ",\"contenttype\":" + Json.quote(type.toLowerCase(Locale.ROOT))) + "}";
    }
}
