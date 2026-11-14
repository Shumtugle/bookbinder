package com.shumtugle.bookbinder;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Folders shared by a link that carries its own key after the hash mark.
 * The service keeps everything encrypted: the names of files and folders,
 * the keys of the files, and the files themselves. The link's key opens the
 * keys of the files, each file's key opens its name and its bytes. The bytes
 * are a counter stream, so a chapter can be decrypted from any offset —
 * a broken fetch goes on where it stopped, and a cover is read from the head.
 *
 * Plain Java with the fetching handed in, checked on a desktop against a
 * folder sealed the same way.
 */
final class Sealed {

    static final String API = "g.api.mega.co.nz";

    private Sealed() {
    }

    /** A link taken apart: the shared node, its key, and whether it is a folder or one file. */
    static final class Shared {
        final String handle;
        final byte[] key;
        final boolean folder;

        Shared(String handle, byte[] key, boolean folder) {
            this.handle = handle;
            this.key = key;
            this.folder = folder;
        }
    }

    /** Both forms of link: the path form and the older one with marks after the hash. */
    static Shared parse(String link) {
        try {
            int hash = link.indexOf('#');
            if (hash < 0) {
                return null;
            }
            String before = link.substring(0, hash);
            String after = link.substring(hash + 1);
            if (after.startsWith("F!") || after.startsWith("!")) {
                boolean folder = after.startsWith("F!");
                String[] parts = after.substring(folder ? 2 : 1).split("!");
                return new Shared(parts[0], b64(parts[1].split("/")[0]), folder);
            }
            boolean folder = before.contains("/folder/");
            String h = before.substring(before.lastIndexOf('/') + 1);
            String k = after;
            int slash = k.indexOf('/');
            if (slash >= 0) {
                k = k.substring(0, slash);
            }
            return new Shared(h, b64(k), folder);
        } catch (Exception broken) {
            return null;
        }
    }

    /** A refusal from the service, by its own number. */
    static final class Refused extends IOException {
        final int code;

        Refused(int code) {
            super("service refused: " + code);
            this.code = code;
        }
    }

    /** Asks the service; a bare negative number in reply is a refusal. */
    private static Object ask(Sources.Fetch f, String query, String body) throws IOException {
        Object r = Json.parse(f.post(new URL("https", API, "/cs?id=" + (System.nanoTime() & 0xFFFFFF) + query), body));
        Object one = r instanceof ArrayList && !((ArrayList<?>) r).isEmpty() ? ((ArrayList<?>) r).get(0) : r;
        if (one instanceof Long && (Long) one < 0) {
            throw new Refused((int) (long) (Long) one);
        }
        return one;
    }

    // ------------------------------------------------------------ the listing

    /** The whole shared folder in the shared shape; every file's reference holds its handle and its key. */
    static String folder(Sources.Fetch f, String link, String sourceKey) throws IOException {
        Shared s = parse(link);
        if (s == null) {
            throw new IOException("not a link with a key");
        }
        if (!s.folder) {
            Object info = ask(f, "", "[{\"a\":\"g\",\"p\":\"" + s.handle + "\"}]");
            byte[] k = fold(s.key);
            String name = attrName(Json.str(info, "at"), k);
            String n = name == null ? s.handle : name;
            int dot = n.lastIndexOf('.');
            return "{\"result\":0,\"metadata\":{\"isfolder\":true,\"name\":" + Json.quote(dot > 0 ? n.substring(0, dot) : n)
                + ",\"folderid\":" + Sources.id(sourceKey, s.handle) + ",\"contents\":["
                + file(sourceKey, s.handle, n, Json.num(info, "s", 0), s.key) + "]}}";
        }
        Object answer = ask(f, "&n=" + s.handle, "[{\"a\":\"f\",\"c\":1,\"ca\":1,\"r\":1}]");
        ArrayList<Object> nodes = Json.list(answer, "f");
        HashMap<String, ArrayList<Object>> kids = new HashMap<String, ArrayList<Object>>();
        HashMap<String, String> names = new HashMap<String, String>();
        HashMap<String, byte[]> keys = new HashMap<String, byte[]>();
        String root = null;
        int opened = 0;
        for (Object n : nodes) {
            String h = Json.str(n, "h");
            String p = Json.str(n, "p");
            byte[] k = null;
            String name = null;
            /* A node's key may come in several "handle:key" pairs; the right one opens its name. */
            for (byte[] cand : nodeKeys(Json.str(n, "k"), s.key)) {
                String tried = attrName(Json.str(n, "a"), cand.length == 32 ? fold(cand) : cand);
                if (tried != null) {
                    k = cand;
                    name = tried;
                    break;
                }
            }
            if (h == null || k == null) {
                continue;
            }
            opened++;
            keys.put(h, k);
            names.put(h, name);
            if (h.equals(s.handle) || root == null && Json.num(n, "t", 0) == 1 && !hasParentIn(nodes, p)) {
                root = h;
            }

            if (p != null) {
                ArrayList<Object> list = kids.get(p);
                if (list == null) {
                    list = new ArrayList<Object>();
                    kids.put(p, list);
                }
                list.add(n);
            }
        }
        if (root == null && !nodes.isEmpty() && opened == 0) {
            throw new IOException("the key in the link does not open this folder (" + nodes.size() + " entries)");
        }
        if (root == null) {
            throw new Refused(-9);
        }
        StringBuilder out = new StringBuilder("{\"result\":0,\"metadata\":");
        tree(root, names, kids, keys, sourceKey, 0, out);
        return out.append('}').toString();
    }

    private static boolean hasParentIn(ArrayList<Object> nodes, String parent) {
        if (parent == null) {
            return false;
        }
        for (Object n : nodes) {
            if (parent.equals(Json.str(n, "h"))) {
                return true;
            }
        }
        return false;
    }

    private static void tree(String h, HashMap<String, String> names, HashMap<String, ArrayList<Object>> kids,
                             HashMap<String, byte[]> keys, String sourceKey, int depth, StringBuilder out) {
        String name = names.get(h);
        out.append("{\"isfolder\":true,\"name\":").append(Json.quote(name == null ? h : name))
            .append(",\"folderid\":").append(Sources.id(sourceKey, h)).append(",\"contents\":[");
        boolean first = true;
        ArrayList<Object> list = kids.get(h);
        if (list != null) {
            for (Object n : list) {
                String c = Json.str(n, "h");
                String cn = names.get(c);
                if (c == null || cn == null) {
                    continue;
                }
                if (Json.num(n, "t", 0) == 1) {
                    if (depth < 5) {
                        if (!first) {
                            out.append(',');
                        }
                        first = false;
                        tree(c, names, kids, keys, sourceKey, depth + 1, out);
                    }
                } else if (Json.num(n, "t", 0) == 0 && Shelf.sound(cn, null) && keys.get(c).length == 32) {
                    if (!first) {
                        out.append(',');
                    }
                    first = false;
                    out.append(file(sourceKey, c, cn, Json.num(n, "s", 0), keys.get(c)));
                }
            }
        }
        out.append("]}");
    }

    private static String file(String sourceKey, String h, String name, long size, byte[] key) {
        return "{\"isfolder\":false,\"name\":" + Json.quote(name) + ",\"fileid\":" + Sources.id(sourceKey, h)
            + ",\"size\":" + size + ",\"ref\":" + Json.quote(h + "|" + b64e(key)) + "}";
    }

    /** Every "handle:key" pair of a node, each key opened block by block with the folder's key. */
    private static ArrayList<byte[]> nodeKeys(String k, byte[] folderKey) {
        ArrayList<byte[]> out = new ArrayList<byte[]>();
        if (k == null) {
            return out;
        }
        for (String pair : k.split("/")) {
            int colon = pair.indexOf(':');
            String part = colon >= 0 ? pair.substring(colon + 1) : pair;
            try {
                byte[] enc = b64(part);
                if (enc.length != 16 && enc.length != 32) {
                    continue;
                }
                Cipher c = Cipher.getInstance("AES/ECB/NoPadding");
                c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(folderKey, "AES"));
                out.add(c.doFinal(enc));
            } catch (Exception broken) {
                /* A pair this key does not open: the next one may. */
            }
        }
        return out;
    }

    /** A name from the encrypted attributes: zero vector, chained blocks, a four-letter mark and a small object. */
    private static String attrName(String a, byte[] k) {
        if (a == null) {
            return null;
        }
        try {
            byte[] enc = b64(a);
            Cipher c = Cipher.getInstance("AES/CBC/NoPadding");
            c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(k, "AES"), new IvParameterSpec(new byte[16]));
            String text = new String(c.doFinal(enc), "UTF-8");
            int end = text.length();
            while (end > 0 && text.charAt(end - 1) == 0) {
                end--;
            }
            text = text.substring(0, end);
            if (!text.startsWith("MEGA{")) {
                return null;
            }
            return Json.str(Json.parse(text.substring(4)), "n");
        } catch (Exception broken) {
            return null;
        }
    }

    // ------------------------------------------------------------ the bytes

    /** Where a file of the shared link can be fetched now; such addresses are short-lived. */
    static URL address(Sources.Fetch f, String link, String ref) throws IOException {
        Shared s = parse(link);
        if (s == null) {
            throw new IOException("not a link with a key");
        }
        String h = ref.substring(0, ref.indexOf('|'));
        Object info = s.folder
            ? ask(f, "&n=" + s.handle, "[{\"a\":\"g\",\"g\":1,\"ssl\":1,\"n\":\"" + h + "\"}]")
            : ask(f, "", "[{\"a\":\"g\",\"g\":1,\"ssl\":1,\"p\":\"" + h + "\"}]");
        String g = Json.str(info, "g");
        if (g == null) {
            throw new IOException("no address");
        }
        return new URL(g);
    }

    /** The same address asking for the bytes from an offset to the end. */
    static URL from(URL u, long offset, long size) throws IOException {
        if (offset <= 0) {
            return u;
        }
        return new URL(u.toString() + "/" + offset + "-" + (size > 0 ? size - 1 : ""));
    }

    /** The plain bytes of a file read from an offset: the counter starts at that block. */
    static InputStream open(InputStream sealed, String ref, long offset) throws IOException {
        byte[] full = b64(ref.substring(ref.indexOf('|') + 1));
        byte[] k = fold(full);
        byte[] iv = new byte[16];
        System.arraycopy(full, 16, iv, 0, 8);
        long block = offset / 16;
        for (int i = 0; i < 8; i++) {
            iv[15 - i] = (byte) (block >>> (8 * i));
        }
        try {
            final Cipher c = Cipher.getInstance("AES/CTR/NoPadding");
            c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(k, "AES"), new IvParameterSpec(iv));
            int skip = (int) (offset % 16);
            if (skip > 0) {
                c.update(new byte[skip]);
            }
            return new FilterInputStream(sealed) {
                @Override
                public int read() throws IOException {
                    byte[] one = new byte[1];
                    int n = read(one, 0, 1);
                    return n <= 0 ? -1 : one[0] & 0xFF;
                }

                @Override
                public int read(byte[] b, int off, int len) throws IOException {
                    int n = super.read(b, off, len);
                    if (n > 0) {
                        byte[] plain = c.update(b, off, n);
                        System.arraycopy(plain, 0, b, off, n);
                    }
                    return n;
                }
            };
        } catch (Exception e) {
            throw new IOException("cannot open: " + e);
        }
    }

    /** A file's 32-byte key folded to the 16 bytes that seal its name and its content. */
    static byte[] fold(byte[] full) {
        if (full.length != 32) {
            return full;
        }
        byte[] k = new byte[16];
        for (int i = 0; i < 16; i++) {
            k[i] = (byte) (full[i] ^ full[i + 16]);
        }
        return k;
    }

    static byte[] b64(String s) {
        String t = s.replace('-', '+').replace('_', '/').replace(",", "");
        while (t.length() % 4 != 0) {
            t += "=";
        }
        return java.util.Base64.getDecoder().decode(t);
    }

    static String b64e(byte[] b) {
        return java.util.Base64.getEncoder().encodeToString(b).replace('+', '-').replace('/', '_').replace("=", "");
    }
}
