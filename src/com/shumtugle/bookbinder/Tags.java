package com.shumtugle.bookbinder;

/**
 * The picture a recording carries in its tag at the head of the file. Only
 * the head is needed, so only the head is fetched: a few hundred kilobytes
 * of a twenty-megabyte chapter. The front cover is preferred; any picture
 * will do when there is none.
 */
final class Tags {

    private Tags() {
    }

    /** Bytes of the tag as the header declares them, header included; 0 when there is no tag. */
    static int length(byte[] b) {
        if (b.length < 10 || b[0] != 'I' || b[1] != 'D' || b[2] != '3') {
            return 0;
        }
        return 10 + syncsafe(b, 6);
    }

    /** The picture's bytes (a JPEG or a PNG), or null. */
    static byte[] picture(byte[] b) {
        int whole = length(b);
        if (whole == 0) {
            return null;
        }
        int version = b[3] & 0xFF;
        int flags = b[5] & 0xFF;
        byte[] t = b;
        int end = Math.min(whole, b.length);
        if ((flags & 0x80) != 0 && version < 4) {
            t = unsync(b, 10, end);
            end = t.length;
            return frames(t, 0, end, version, false);
        }
        int at = 10;
        if ((flags & 0x40) != 0 && end > 14) {
            at += version == 4 ? syncsafe(b, 10) : 4 + be32(b, 10);
        }
        return frames(t, at, end, version, (flags & 0x80) != 0);
    }

    private static byte[] frames(byte[] b, int at, int end, int version, boolean frameUnsync) {
        byte[] any = null;
        int head = version == 2 ? 6 : 10;
        while (at + head <= end) {
            if (b[at] == 0) {
                break;
            }
            String id;
            int size;
            int fflags = 0;
            if (version == 2) {
                id = new String(b, at, 3, java.nio.charset.Charset.forName("ISO-8859-1"));
                size = ((b[at + 3] & 0xFF) << 16) | ((b[at + 4] & 0xFF) << 8) | (b[at + 5] & 0xFF);
            } else {
                id = new String(b, at, 4, java.nio.charset.Charset.forName("ISO-8859-1"));
                size = version == 4 ? syncsafe(b, at + 4) : be32(b, at + 4);
                fflags = b[at + 9] & 0xFF;
            }
            int body = at + head;
            if (size <= 0 || body + size > end) {
                break;
            }
            if (id.equals("APIC") || id.equals("PIC")) {
                byte[] frame = new byte[size];
                System.arraycopy(b, body, frame, 0, size);
                if (version == 4 && (frameUnsync || (fflags & 0x02) != 0)) {
                    frame = unsync(frame, 0, frame.length);
                }
                if (version == 4 && (fflags & 0x01) != 0 && frame.length > 4) {
                    byte[] cut = new byte[frame.length - 4];
                    System.arraycopy(frame, 4, cut, 0, cut.length);
                    frame = cut;
                }
                int[] kind = new int[1];
                byte[] pic = image(frame, version == 2, kind);
                if (pic != null) {
                    if (kind[0] == 3) {
                        return pic;
                    }
                    if (any == null) {
                        any = pic;
                    }
                }
            }
            at = body + size;
        }
        return any;
    }

    /** Text encoding, kind of image, picture type, description, data. */
    private static byte[] image(byte[] f, boolean old, int[] kind) {
        if (f.length < 4) {
            return null;
        }
        int enc = f[0] & 0xFF;
        int i = 1;
        if (old) {
            i += 3;
        } else {
            while (i < f.length && f[i] != 0) {
                i++;
            }
            i++;
        }
        if (i >= f.length) {
            return null;
        }
        kind[0] = f[i] & 0xFF;
        i++;
        if (enc == 1 || enc == 2) {
            while (i + 1 < f.length && !(f[i] == 0 && f[i + 1] == 0)) {
                i += 2;
            }
            i += 2;
        } else {
            while (i < f.length && f[i] != 0) {
                i++;
            }
            i++;
        }
        /* Some taggers leave stray zero bytes before the image itself. */
        int s = i;
        while (s < f.length - 1 && !looksLikeImage(f, s) && s < i + 4) {
            s++;
        }
        if (looksLikeImage(f, s)) {
            i = s;
        }
        if (i >= f.length) {
            return null;
        }
        byte[] out = new byte[f.length - i];
        System.arraycopy(f, i, out, 0, out.length);
        return out;
    }

    private static boolean looksLikeImage(byte[] f, int i) {
        if (i + 3 >= f.length) {
            return false;
        }
        boolean jpeg = (f[i] & 0xFF) == 0xFF && (f[i + 1] & 0xFF) == 0xD8;
        boolean png = (f[i] & 0xFF) == 0x89 && f[i + 1] == 'P' && f[i + 2] == 'N' && f[i + 3] == 'G';
        return jpeg || png;
    }

    /** Undoes the escaping that turns every 0xFF 0x00 back into 0xFF. */
    private static byte[] unsync(byte[] b, int from, int to) {
        byte[] out = new byte[to - from];
        int n = 0;
        for (int i = from; i < to; i++) {
            out[n++] = b[i];
            if ((b[i] & 0xFF) == 0xFF && i + 1 < to && b[i + 1] == 0) {
                i++;
            }
        }
        byte[] cut = new byte[n];
        System.arraycopy(out, 0, cut, 0, n);
        return cut;
    }

    private static int syncsafe(byte[] b, int at) {
        return ((b[at] & 0x7F) << 21) | ((b[at + 1] & 0x7F) << 14) | ((b[at + 2] & 0x7F) << 7) | (b[at + 3] & 0x7F);
    }

    private static int be32(byte[] b, int at) {
        return ((b[at] & 0xFF) << 24) | ((b[at + 1] & 0xFF) << 16) | ((b[at + 2] & 0xFF) << 8) | (b[at + 3] & 0xFF);
    }
}
