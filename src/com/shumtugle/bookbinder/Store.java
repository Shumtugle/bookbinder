package com.shumtugle.bookbinder;

import android.Manifest;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Locale;

/**
 * Where the books live on the phone: the shared music folder, a folder per
 * book under "Audiobooks", named as the book is named in the cloud. Files
 * there belong to the listener, not to the application — other players and
 * file managers see them, and only the listener deletes them.
 *
 * On newer systems the files are written through the media index, which
 * needs no permission for an application's own files; on older ones they
 * are plain files, written with the storage permission and then shown to
 * the index. A finished chapter is kept as a location: a content address
 * on the newer systems, a path on the older.
 */
final class Store {

    static final String ROOT = "Audiobooks";

    /**
     * A file of the same name is already in the book's folder, but this
     * application may not see it: the system gave the new file another name.
     * The new file is taken back; the old one is the listener's.
     */
    static final class Unseen extends IOException {
        Unseen(String name) {
            super(name);
        }
    }

    private Store() {
    }

    /** A folder name the file system accepts: no separators, no reserved marks, no trailing dots. */
    static String safe(String name) {
        String s = name.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").trim();
        while (s.endsWith(".") || s.endsWith(" ")) {
            s = s.substring(0, s.length() - 1);
        }
        if (s.length() > 120) {
            s = s.substring(0, 120).trim();
        }
        return s.isEmpty() ? "_" : s;
    }

    private static String relative(Shelf.Book b) {
        return Environment.DIRECTORY_MUSIC + "/" + ROOT + "/" + safe(b.folder) + "/";
    }

    private static File folder(Shelf.Book b) {
        File music = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC);
        return new File(new File(music, ROOT), safe(b.folder));
    }

    /** Older systems write plain files and need the storage permission for it. */
    static boolean needsPermission(Context c) {
        return Build.VERSION.SDK_INT < 29
            && c.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED;
    }

    /**
     * The permission to see the books written before the application was
     * installed again: without it, the index hides files it did not write.
     */
    static String readPermission() {
        if (Build.VERSION.SDK_INT >= 33) {
            return Manifest.permission.READ_MEDIA_AUDIO;
        }
        return Build.VERSION.SDK_INT >= 29 ? Manifest.permission.READ_EXTERNAL_STORAGE
            : Manifest.permission.WRITE_EXTERNAL_STORAGE;
    }

    private static String mime(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        if (n.endsWith(".mp3")) {
            return "audio/mpeg";
        }
        if (n.endsWith(".m4a") || n.endsWith(".m4b") || n.endsWith(".mp4")) {
            return "audio/mp4";
        }
        if (n.endsWith(".aac")) {
            return "audio/aac";
        }
        if (n.endsWith(".ogg") || n.endsWith(".oga")) {
            return "audio/ogg";
        }
        if (n.endsWith(".opus")) {
            return "audio/opus";
        }
        if (n.endsWith(".flac")) {
            return "audio/flac";
        }
        if (n.endsWith(".wav")) {
            return "audio/wav";
        }
        return "audio/mpeg";
    }

    /** Moves a whole fetched file into the book's folder; returns its location. */
    static String put(Context c, Shelf.Book b, Shelf.Chapter ch, File whole) throws IOException {
        if (Build.VERSION.SDK_INT >= 29) {
            ContentResolver cr = c.getContentResolver();
            String old = find(c, b, ch);
            if (old != null) {
                delete(c, old);
            }
            ContentValues v = new ContentValues();
            v.put(MediaStore.MediaColumns.DISPLAY_NAME, ch.name);
            v.put(MediaStore.MediaColumns.MIME_TYPE, mime(ch.name));
            v.put(MediaStore.MediaColumns.RELATIVE_PATH, relative(b));
            v.put(MediaStore.MediaColumns.IS_PENDING, 1);
            Uri uri = cr.insert(MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), v);
            if (uri == null) {
                throw new IOException("the index refused the file");
            }
            String given = nameOf(c, uri);
            if (given != null && !given.equals(ch.name)) {
                cr.delete(uri, null, null);
                throw new Unseen(ch.name);
            }
            try {
                OutputStream out = cr.openOutputStream(uri, "w");
                if (out == null) {
                    throw new IOException("no stream");
                }
                copy(new FileInputStream(whole), out);
                ContentValues done = new ContentValues();
                done.put(MediaStore.MediaColumns.IS_PENDING, 0);
                cr.update(uri, done, null, null);
            } catch (IOException e) {
                cr.delete(uri, null, null);
                throw e;
            }
            whole.delete();
            return uri.toString();
        }
        File dir = folder(b);
        if (!dir.isDirectory() && !dir.mkdirs()) {
            throw new IOException("cannot make " + dir.getName());
        }
        File to = new File(dir, safe(ch.name));
        if (!whole.renameTo(to)) {
            copy(new FileInputStream(whole), new FileOutputStream(to));
            whole.delete();
        }
        MediaScannerConnection.scanFile(c, new String[]{to.getPath()}, new String[]{mime(ch.name)}, null);
        return to.getPath();
    }

    /**
     * A chapter already in the book's folder, written by this installation
     * or an earlier one (the latter is visible only with the read permission).
     */
    static String find(Context c, Shelf.Book b, Shelf.Chapter ch) {
        if (Build.VERSION.SDK_INT >= 29) {
            Uri table = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
            Cursor cur = null;
            try {
                cur = c.getContentResolver().query(table,
                    new String[]{MediaStore.MediaColumns._ID, MediaStore.MediaColumns.SIZE},
                    MediaStore.MediaColumns.RELATIVE_PATH + "=? AND " + MediaStore.MediaColumns.DISPLAY_NAME + "=?",
                    new String[]{relative(b), ch.name}, null);
                if (cur != null && cur.moveToFirst()) {
                    long size = cur.getLong(1);
                    if (ch.size > 0 && size > 0 && size != ch.size) {
                        return null;
                    }
                    return ContentUris.withAppendedId(table, cur.getLong(0)).toString();
                }
            } catch (Exception e) {
                Log.note("store: find " + ch.name + ": " + e);
            } finally {
                if (cur != null) {
                    cur.close();
                }
            }
            return null;
        }
        File f = new File(folder(b), safe(ch.name));
        return f.isFile() && (ch.size <= 0 || f.length() == ch.size) ? f.getPath() : null;
    }

    /**
     * Before a chapter is fetched: whether its name is free in the book's
     * folder, or taken by a file this application may not see. A pending
     * entry is made and taken back at once; it costs nothing and saves
     * fetching a whole chapter only to throw it away.
     */
    static void check(Context c, Shelf.Book b, Shelf.Chapter ch) throws IOException {
        if (Build.VERSION.SDK_INT < 29) {
            return;
        }
        ContentResolver cr = c.getContentResolver();
        ContentValues v = new ContentValues();
        v.put(MediaStore.MediaColumns.DISPLAY_NAME, ch.name);
        v.put(MediaStore.MediaColumns.MIME_TYPE, mime(ch.name));
        v.put(MediaStore.MediaColumns.RELATIVE_PATH, relative(b));
        v.put(MediaStore.MediaColumns.IS_PENDING, 1);
        Uri uri;
        try {
            uri = cr.insert(MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), v);
        } catch (Exception e) {
            return;
        }
        if (uri == null) {
            return;
        }
        String given = nameOf(c, uri);
        cr.delete(uri, null, null);
        if (given != null && !given.equals(ch.name)) {
            throw new Unseen(ch.name);
        }
    }

    private static String nameOf(Context c, Uri uri) {
        Cursor cur = null;
        try {
            cur = c.getContentResolver().query(uri, new String[]{MediaStore.MediaColumns.DISPLAY_NAME}, null, null, null);
            return cur != null && cur.moveToFirst() ? cur.getString(0) : null;
        } catch (Exception e) {
            return null;
        } finally {
            if (cur != null) {
                cur.close();
            }
        }
    }

    /** Whether a kept location still holds a file. */
    static boolean exists(Context c, String where) {
        if (where == null) {
            return false;
        }
        if (!where.startsWith("content:")) {
            return new File(where).isFile();
        }
        Cursor cur = null;
        try {
            cur = c.getContentResolver().query(Uri.parse(where),
                new String[]{MediaStore.MediaColumns._ID}, null, null, null);
            return cur != null && cur.moveToFirst();
        } catch (Exception gone) {
            return false;
        } finally {
            if (cur != null) {
                cur.close();
            }
        }
    }

    static InputStream open(Context c, String where) throws IOException {
        if (where.startsWith("content:")) {
            InputStream in = c.getContentResolver().openInputStream(Uri.parse(where));
            if (in == null) {
                throw new IOException("no stream");
            }
            return in;
        }
        return new FileInputStream(where);
    }

    static void delete(Context c, String where) {
        try {
            if (where.startsWith("content:") && android.provider.DocumentsContract.isDocumentUri(c, Uri.parse(where))) {
                android.provider.DocumentsContract.deleteDocument(c.getContentResolver(), Uri.parse(where));
            } else if (where.startsWith("content:")) {
                c.getContentResolver().delete(Uri.parse(where), null, null);
            } else {
                File f = new File(where);
                if (f.delete()) {
                    MediaScannerConnection.scanFile(c, new String[]{f.getPath()}, null, null);
                }
                File dir = f.getParentFile();
                String[] left = dir == null ? null : dir.list();
                if (left != null && left.length == 0) {
                    dir.delete();
                }
            }
        } catch (Exception e) {
            Log.note("store: delete: " + e);
        }
    }

    /**
     * Books put on the shelf by hand: every folder of sound files under the
     * music folder's "Audiobooks", or under a top-level "Audiobooks". Their
     * chapters are numbered below zero, so they never meet a cloud file's
     * number; their locations go into the given table. Files written by
     * someone else are seen only with the read permission.
     */
    static java.util.ArrayList<Shelf.Book> scan(Context c, java.util.HashMap<Long, String> where) {
        java.util.LinkedHashMap<String, Shelf.Book> found = new java.util.LinkedHashMap<String, Shelf.Book>();
        if (Build.VERSION.SDK_INT >= 29) {
            Uri table = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
            String music = Environment.DIRECTORY_MUSIC + "/" + ROOT + "/";
            Cursor cur = null;
            try {
                cur = c.getContentResolver().query(table,
                    new String[]{MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME,
                        MediaStore.MediaColumns.RELATIVE_PATH, MediaStore.MediaColumns.SIZE},
                    MediaStore.MediaColumns.RELATIVE_PATH + " LIKE ? OR " + MediaStore.MediaColumns.RELATIVE_PATH + " LIKE ?",
                    new String[]{music + "%", ROOT + "/%"}, null);
                while (cur != null && cur.moveToNext()) {
                    String rel = cur.getString(2);
                    String name = cur.getString(1);
                    if (rel == null || name == null || rel.equalsIgnoreCase(music) || rel.equalsIgnoreCase(ROOT + "/")) {
                        continue;
                    }
                    Shelf.Book b = found.get(rel.toLowerCase(Locale.ROOT));
                    if (b == null) {
                        b = new Shelf.Book(localId(rel), last(rel));
                        b.place = rel;
                        found.put(rel.toLowerCase(Locale.ROOT), b);
                    }
                    long id = cur.getLong(0);
                    Shelf.Chapter ch = new Shelf.Chapter(-id, name, cur.getLong(3));
                    b.chapters.add(ch);
                    b.bytes += ch.size;
                    where.put(ch.id, ContentUris.withAppendedId(table, id).toString());
                }
            } catch (Exception e) {
                Log.note("store: scan: " + e);
            } finally {
                if (cur != null) {
                    cur.close();
                }
            }
        } else {
            File music = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC), ROOT);
            File top = new File(Environment.getExternalStorageDirectory(), ROOT);
            for (File root : new File[]{music, top}) {
                walk(root, root, found, where);
            }
        }
        java.util.ArrayList<Shelf.Book> out = new java.util.ArrayList<Shelf.Book>(found.values());
        for (Shelf.Book b : out) {
            Shelf.order(b);
        }
        return out;
    }

    private static void walk(File root, File dir, java.util.LinkedHashMap<String, Shelf.Book> found,
                             java.util.HashMap<Long, String> where) {
        File[] all = dir.listFiles();
        if (all == null) {
            return;
        }
        for (File f : all) {
            if (f.isDirectory()) {
                walk(root, f, found, where);
            } else if (dir != root && Shelf.sound(f.getName(), null)) {
                String rel = dir.getPath() + "/";
                Shelf.Book b = found.get(rel.toLowerCase(Locale.ROOT));
                if (b == null) {
                    b = new Shelf.Book(localId(rel), dir.getName());
                    b.place = rel;
                    found.put(rel.toLowerCase(Locale.ROOT), b);
                }
                Shelf.Chapter ch = new Shelf.Chapter(-(Math.abs((long) f.getPath().hashCode()) + 1), f.getName(), f.length());
                b.chapters.add(ch);
                b.bytes += ch.size;
                where.put(ch.id, f.getPath());
            }
        }
    }

    /** A book on the shelf by hand is named by its folder; the number stays the same between scans. */
    private static long localId(String rel) {
        java.util.zip.CRC32 crc = new java.util.zip.CRC32();
        try {
            crc.update(rel.toLowerCase(Locale.ROOT).getBytes("UTF-8"));
        } catch (java.io.UnsupportedEncodingException never) {
            crc.update(rel.getBytes());
        }
        return -(crc.getValue() + 1);
    }

    private static String last(String rel) {
        String r = rel.endsWith("/") ? rel.substring(0, rel.length() - 1) : rel;
        int slash = r.lastIndexOf('/');
        return slash >= 0 ? r.substring(slash + 1) : r;
    }

    /** Where a book from the cloud is kept, in the same terms as the hand-placed books. */
    static String placeOf(Shelf.Book b) {
        return Build.VERSION.SDK_INT >= 29 ? relative(b) : folder(b).getPath() + "/";
    }

    /**
     * Books in folders the listener has shown the application in the system
     * folder picker: on the phone or on a memory card. Any folder with sound
     * files in it, at any depth below the shown one, is a book. The place of
     * a book on the phone's own memory is written the way the media index
     * writes it, so the same folder found both ways is one book.
     */
    static java.util.ArrayList<Shelf.Book> scanTrees(Context c, java.util.List<String> trees,
                                                     java.util.HashMap<Long, String> where) {
        java.util.ArrayList<Shelf.Book> out = new java.util.ArrayList<Shelf.Book>();
        for (String t : trees) {
            try {
                Uri tree = Uri.parse(t);
                String root = android.provider.DocumentsContract.getTreeDocumentId(tree);
                walkTree(c, tree, root, last(root.replace(':', '/')), 0, out, where);
            } catch (Exception e) {
                Log.note("store: folder " + t + ": " + e);
            }
        }
        for (Shelf.Book b : out) {
            Shelf.order(b);
        }
        return out;
    }

    private static void walkTree(Context c, Uri tree, String docId, String name, int depth,
                                 java.util.ArrayList<Shelf.Book> out, java.util.HashMap<Long, String> where) {
        if (depth > 6) {
            return;
        }
        Uri kids = android.provider.DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId);
        Cursor cur = c.getContentResolver().query(kids, new String[]{
            android.provider.DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            android.provider.DocumentsContract.Document.COLUMN_MIME_TYPE,
            android.provider.DocumentsContract.Document.COLUMN_SIZE}, null, null, null);
        if (cur == null) {
            return;
        }
        Shelf.Book here = null;
        java.util.ArrayList<String[]> folders = new java.util.ArrayList<String[]>();
        try {
            while (cur.moveToNext()) {
                String id = cur.getString(0);
                String n = cur.getString(1);
                String type = cur.getString(2);
                if (android.provider.DocumentsContract.Document.MIME_TYPE_DIR.equals(type)) {
                    folders.add(new String[]{id, n});
                } else if (n != null && Shelf.sound(n, type)) {
                    if (here == null) {
                        String place = placeOfDocument(docId);
                        here = new Shelf.Book(localId(place), name);
                        here.place = place;
                    }
                    Uri doc = android.provider.DocumentsContract.buildDocumentUriUsingTree(tree, id);
                    Shelf.Chapter ch = new Shelf.Chapter(localId(doc.toString()), n, cur.isNull(3) ? 0 : cur.getLong(3));
                    here.chapters.add(ch);
                    here.bytes += ch.size;
                    where.put(ch.id, doc.toString());
                }
            }
        } finally {
            cur.close();
        }
        if (here != null) {
            out.add(here);
        }
        for (String[] f : folders) {
            walkTree(c, tree, f[0], f[1], depth + 1, out, where);
        }
    }

    /** "primary:Music/Audiobooks/Book" is the media index's "Music/Audiobooks/Book/"; a card keeps its own name. */
    private static String placeOfDocument(String docId) {
        if (docId.startsWith("primary:")) {
            return docId.substring("primary:".length()) + "/";
        }
        return docId + "/";
    }

    /** A folder shown in the picker, in words: "Music/Audiobooks", or the card's folder. */
    static String folderName(String tree) {
        try {
            String id = android.provider.DocumentsContract.getTreeDocumentId(Uri.parse(tree));
            return id.startsWith("primary:") ? id.substring("primary:".length()) : id;
        } catch (Exception e) {
            return tree;
        }
    }

    /** Where the books are, in words for the listener. */
    static String shown() {
        return Environment.DIRECTORY_MUSIC + "/" + ROOT;
    }

    private static void copy(InputStream in, OutputStream out) throws IOException {
        try {
            byte[] buf = new byte[128 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
        } finally {
            in.close();
            out.close();
        }
    }
}
