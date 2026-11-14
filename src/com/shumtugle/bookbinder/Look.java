package com.shumtugle.bookbinder;

import android.content.Context;
import android.content.res.Configuration;

/**
 * The page's colours, light or dark, for the window and for the widget
 * alike. The widget is often drawn in a fresh process, so the choice is
 * read from the settings every time, never kept in memory.
 */
final class Look {

    static final int PAPER = 0;
    static final int CARD = 1;
    static final int INK = 2;
    static final int SOFT = 3;
    static final int LINE = 4;
    static final int ACCENT = 5;
    static final int ACCENT_PALE = 6;

    private static final int[] LIGHT = {0xFFF6F3EC, 0xFFFFFFFF, 0xFF22201C, 0xFF7D776C, 0xFFE7E1D5, 0xFF2F6B5F, 0xFFDCE8E3};
    private static final int[] DARK = {0xFF151411, 0xFF201E1A, 0xFFEDE8DE, 0xFF9C958A, 0xFF302D27, 0xFF86C8B6, 0xFF233631};

    private Look() {
    }

    static boolean dark(Context c) {
        String t = Places.theme(c);
        return "dark".equals(t) || "system".equals(t)
            && (c.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
            == Configuration.UI_MODE_NIGHT_YES;
    }

    static int[] colors(boolean dark) {
        return (dark ? DARK : LIGHT).clone();
    }
}
