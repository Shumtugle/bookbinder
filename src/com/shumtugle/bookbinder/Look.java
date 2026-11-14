package com.shumtugle.bookbinder;

import android.content.Context;
import android.content.res.Configuration;
import android.os.Build;

/**
 * The page's colours and shape, for the window and for the widget alike.
 * Three things are chosen apart: light or dark (or as the phone), the
 * palette, and how round the corners are. The widget is often drawn in a
 * fresh process, so every choice is read from the settings each time,
 * never kept in memory.
 */
final class Look {

    static final int PAPER = 0;
    static final int CARD = 1;
    static final int INK = 2;
    static final int SOFT = 3;
    static final int LINE = 4;
    static final int ACCENT = 5;
    static final int ACCENT_PALE = 6;

    /* Paper: warm white and deep green. The first look, and the default. */
    private static final int[] PAPER_LIGHT = {0xFFF6F3EC, 0xFFFFFFFF, 0xFF22201C, 0xFF7D776C, 0xFFE7E1D5, 0xFF2F6B5F, 0xFFDCE8E3};
    private static final int[] PAPER_DARK = {0xFF151411, 0xFF201E1A, 0xFFEDE8DE, 0xFF9C958A, 0xFF302D27, 0xFF86C8B6, 0xFF233631};

    /* Warm: aubergine ground and ink, orange for what is active. */
    private static final int[] WARM_LIGHT = {0xFFF7F2EF, 0xFFFFFFFF, 0xFF2C0F24, 0xFF7B6872, 0xFFEADCDF, 0xFFC74117, 0xFFFBE2D8};
    private static final int[] WARM_DARK = {0xFF2A0B21, 0xFF3B1531, 0xFFF4EBF0, 0xFFBCA4B3, 0xFF552A49, 0xFFF27A42, 0xFF5C2338};

    /* Wallpaper: before the phone can lend its own colours, a calm violet in the same tones. */
    private static final int[] TONAL_LIGHT = {0xFFF3EDF7, 0xFFFEF7FF, 0xFF1D1B20, 0xFF49454F, 0xFFCAC4D0, 0xFF6750A4, 0xFFEADDFF};
    private static final int[] TONAL_DARK = {0xFF141218, 0xFF211F26, 0xFFE6E0E9, 0xFFCAC4D0, 0xFF49454F, 0xFFD0BCFF, 0xFF4F378B};

    private Look() {
    }

    static boolean dark(Context c) {
        String t = Places.theme(c);
        return "dark".equals(t) || "system".equals(t)
            && (c.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
            == Configuration.UI_MODE_NIGHT_YES;
    }

    /** The colours for the chosen palette, light or dark as the settings say. */
    static int[] colors(Context c) {
        boolean dark = dark(c);
        String p = Places.palette(c);
        if ("warm".equals(p)) {
            return (dark ? WARM_DARK : WARM_LIGHT).clone();
        }
        if ("wallpaper".equals(p)) {
            if (Build.VERSION.SDK_INT >= 31) {
                return wallpaper(c, dark);
            }
            return (dark ? TONAL_DARK : TONAL_LIGHT).clone();
        }
        return (dark ? PAPER_DARK : PAPER_LIGHT).clone();
    }

    /**
     * The phone's own tones, taken from the wallpaper. Each slot takes the
     * tone that plays the same part in the system's scheme: the ground a
     * shade under the cards, ink at the far end, the accent at forty in
     * light and eighty in dark, so text on it reads either way.
     */
    private static int[] wallpaper(Context c, boolean dark) {
        if (dark) {
            return new int[] {
                tone(c, android.R.color.system_neutral1_900),
                tone(c, android.R.color.system_neutral1_800),
                tone(c, android.R.color.system_neutral1_100),
                tone(c, android.R.color.system_neutral2_300),
                tone(c, android.R.color.system_neutral2_700),
                tone(c, android.R.color.system_accent1_200),
                tone(c, android.R.color.system_accent1_800),
            };
        }
        return new int[] {
            tone(c, android.R.color.system_neutral1_50),
            tone(c, android.R.color.system_neutral1_10),
            tone(c, android.R.color.system_neutral1_900),
            tone(c, android.R.color.system_neutral2_600),
            tone(c, android.R.color.system_neutral2_200),
            tone(c, android.R.color.system_accent1_600),
            tone(c, android.R.color.system_accent1_100),
        };
    }

    private static int tone(Context c, int id) {
        return c.getResources().getColor(id, null);
    }

    /**
     * How much of the usual radius a corner keeps: sharp corners keep a
     * hint of it, so nothing looks cut by a knife; round ones nearly
     * double it, and whatever is short enough turns into a pill.
     */
    static float roundness(Context c) {
        String k = Places.corners(c);
        if ("sharp".equals(k)) {
            return 0.2f;
        }
        if ("round".equals(k)) {
            return 1.8f;
        }
        return 1f;
    }
}
