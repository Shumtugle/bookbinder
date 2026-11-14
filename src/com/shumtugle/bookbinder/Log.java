package com.shumtugle.bookbinder;

/** The system log, under one tag. */
final class Log {

    private Log() {
    }

    static void note(String what) {
        android.util.Log.i("bookbinder", what);
    }
}
