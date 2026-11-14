package com.shumtugle.bookbinder;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Locale;

/**
 * The library as the shared folder describes it. A folder that holds sound
 * files is a book; its files are the chapters, in the order their numbers
 * say — not the order they were uploaded in, and not the order of letters,
 * in which ten comes before two. Folders inside folders are walked too, so
 * one link to the top of the library brings every book under it.
 *
 * Plain Java: nothing here needs the phone, so it is checked on a desktop.
 */
final class Shelf {

    private Shelf() {
    }

    static final class Chapter {
        final long id;
        final String name;
        final long size;
        /** Where to fetch it from, for kinds of link that fetch by path or address. */
        String ref = "";

        Chapter(long id, String name, long size) {
            this.id = id;
            this.name = name;
            this.size = size;
        }

        /** The name without its ending: "01-09" for "01-09.mp3". */
        String stem() {
            int dot = name.lastIndexOf('.');
            return dot > 0 ? name.substring(0, dot) : name;
        }

        /** Files of this kind fall silent on some phones unless started off the normal pace. */
        boolean aac() {
            String n = name.toLowerCase(Locale.ROOT);
            return n.endsWith(".m4a") || n.endsWith(".m4b") || n.endsWith(".aac") || n.endsWith(".mp4");
        }
    }

    static final class Book {
        final long id;
        final String folder;
        final String author;
        final String title;
        final String reader;
        final ArrayList<Chapter> chapters = new ArrayList<Chapter>();
        long bytes;
        /** The code of the shared link the book came from; empty for a book put on the shelf by hand. */
        String source = "";
        /** For a book put on the shelf by hand: its folder. */
        String place = "";

        Book(long id, String folder) {
            this.id = id;
            this.folder = folder;
            String[] parts = split(folder);
            this.author = parts[0];
            this.title = parts[1];
            this.reader = parts[2];
        }

        String key() {
            return "b" + id;
        }

        int find(long chapterId) {
            for (int i = 0; i < chapters.size(); i++) {
                if (chapters.get(i).id == chapterId) {
                    return i;
                }
            }
            return -1;
        }

        /** How far into the book, by bytes: a chapter's length is not known until it is opened. */
        double done(int chapter, double within) {
            if (bytes <= 0 || chapter < 0) {
                return 0;
            }
            long before = 0;
            for (int i = 0; i < chapter && i < chapters.size(); i++) {
                before += chapters.get(i).size;
            }
            long here = chapter < chapters.size() ? chapters.get(chapter).size : 0;
            return Math.min(1.0, (before + here * Math.max(0, Math.min(1, within))) / (double) bytes);
        }
    }

    private static final String[] SOUND = {".mp3", ".m4a", ".m4b", ".aac", ".ogg", ".opus", ".oga", ".flac", ".wav", ".mp4"};

    static boolean sound(String name, String type) {
        if (type != null && type.startsWith("audio/")) {
            return true;
        }
        String n = name.toLowerCase(Locale.ROOT);
        for (String end : SOUND) {
            if (n.endsWith(end)) {
                return true;
            }
        }
        return false;
    }

    /** Every book under the folder the link points to, the link's own folder included. */
    static ArrayList<Book> read(Object metadata) {
        ArrayList<Book> books = new ArrayList<Book>();
        if (metadata != null) {
            walk(metadata, books);
        }
        Collections.sort(books, new Comparator<Book>() {
            @Override
            public int compare(Book a, Book b) {
                int c = natural(a.author, b.author);
                return c != 0 ? c : natural(a.title, b.title);
            }
        });
        return books;
    }

    private static void walk(Object folder, ArrayList<Book> books) {
        Book here = new Book(Json.num(folder, "folderid", 0), name(folder));
        ArrayList<Object> inner = Json.list(folder, "contents");
        for (Object o : inner) {
            if (Json.bool(o, "isfolder")) {
                walk(o, books);
            } else if (sound(name(o), Json.str(o, "contenttype"))) {
                Chapter ch = new Chapter(Json.num(o, "fileid", 0), name(o), Json.num(o, "size", 0));
                String ref = Json.str(o, "ref");
                ch.ref = ref == null ? "" : ref;
                here.chapters.add(ch);
                here.bytes += ch.size;
            }
        }
        if (!here.chapters.isEmpty()) {
            order(here);
            books.add(here);
        }
    }

    /** Puts a book's chapters in the order of their numbers. */
    static void order(Book b) {
        Collections.sort(b.chapters, new Comparator<Chapter>() {
            @Override
            public int compare(Chapter x, Chapter y) {
                return natural(x.name, y.name);
            }
        });
    }

    private static String name(Object o) {
        String n = Json.str(o, "name");
        return n == null ? "" : n;
    }

    /**
     * Compares names the way a person reads them: runs of digits as numbers,
     * the rest as letters without regard to case. "2" before "10", and
     * "01-09" before "01-18" before "01-19".
     */
    static int natural(String a, String b) {
        int i = 0;
        int j = 0;
        while (i < a.length() && j < b.length()) {
            char x = a.charAt(i);
            char y = b.charAt(j);
            if (Character.isDigit(x) && Character.isDigit(y)) {
                int si = i;
                int sj = j;
                while (i < a.length() && Character.isDigit(a.charAt(i))) {
                    i++;
                }
                while (j < b.length() && Character.isDigit(b.charAt(j))) {
                    j++;
                }
                String na = strip(a.substring(si, i));
                String nb = strip(b.substring(sj, j));
                if (na.length() != nb.length()) {
                    return na.length() - nb.length();
                }
                int c = na.compareTo(nb);
                if (c != 0) {
                    return c;
                }
            } else {
                int c = Character.toLowerCase(x) - Character.toLowerCase(y);
                if (c != 0) {
                    return c;
                }
                i++;
                j++;
            }
        }
        return (a.length() - i) - (b.length() - j);
    }

    private static String strip(String digits) {
        int k = 0;
        while (k < digits.length() - 1 && digits.charAt(k) == '0') {
            k++;
        }
        return digits.substring(k);
    }

    /**
     * "Author - Title [Reader]" taken apart: the reader from the last square
     * brackets, the author before the first dash, the title after it. A name
     * without a dash is all title.
     */
    static String[] split(String folder) {
        /* Names saved with underscores for spaces are read as words. */
        String rest = folder.replace('_', ' ').replaceAll("\\s+", " ").trim();
        String reader = "";
        int open = rest.lastIndexOf('[');
        int close = rest.lastIndexOf(']');
        if (open >= 0 && close > open) {
            reader = rest.substring(open + 1, close).trim();
            rest = (rest.substring(0, open) + rest.substring(close + 1)).trim();
        }
        String author = "";
        String title = rest;
        String[] dashes = {" - ", " – ", " — "};
        int best = -1;
        int len = 0;
        for (String d : dashes) {
            int k = rest.indexOf(d);
            if (k > 0 && (best < 0 || k < best)) {
                best = k;
                len = d.length();
            }
        }
        if (best > 0) {
            author = rest.substring(0, best).trim();
            title = rest.substring(best + len).trim();
        }
        return new String[]{author, title, reader};
    }

    static Book byId(ArrayList<Book> books, long id) {
        for (Book b : books) {
            if (b.id == id) {
                return b;
            }
        }
        return null;
    }
}
