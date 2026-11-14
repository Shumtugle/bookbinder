package com.shumtugle.bookbinder;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.os.IBinder;

/**
 * Keeps the application alive while books are being brought to the phone
 * and nothing plays: a quiet notification says which book and how far, and
 * the service leaves by itself when there is nothing more to bring.
 */
public final class Fetch extends Service implements Library.Watch {

    private static final String CHANNEL = "fetch";
    private static final int NOTE = 2;
    private static boolean running;

    private Library lib;
    private String shown = "";

    /** Starts the service if it is not running; a refusal (the window is away) is harmless. */
    static void keep(Context c) {
        if (running) {
            return;
        }
        try {
            c.startForegroundService(new Intent(c, Fetch.class));
            running = true;
        } catch (Exception refused) {
            Log.note("fetch: service refused: " + refused);
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        running = true;
        Words.load(this);
        lib = Library.get(this);
        NotificationManager notes = getSystemService(NotificationManager.class);
        if (notes != null) {
            NotificationChannel ch = new NotificationChannel(CHANNEL, Words.s("fetch_channel"),
                NotificationManager.IMPORTANCE_MIN);
            ch.setShowBadge(false);
            notes.createNotificationChannel(ch);
        }
        lib.watch(this);
    }

    @Override
    public int onStartCommand(Intent i, int flags, int id) {
        show(true);
        if (!lib.busy()) {
            leave();
        }
        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent i) {
        return null;
    }

    @Override
    public void changed() {
        if (!lib.busy()) {
            leave();
            return;
        }
        show(false);
    }

    /** The system's limit on such work for the day has come: stop now, go on another time. */
    public void onTimeout(int startId, int type) {
        leave();
    }

    @Override
    public void onDestroy() {
        lib.unwatch(this);
        running = false;
        super.onDestroy();
    }

    private void leave() {
        running = false;
        lib.unwatch(this);
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    private void show(boolean first) {
        Shelf.Chapter ch = lib.fetching();
        Shelf.Book b = null;
        if (ch != null) {
            for (Shelf.Book x : lib.books()) {
                if (x.find(ch.id) >= 0) {
                    b = x;
                }
            }
        }
        String title = b == null ? "Bookbinder" : (b.title.isEmpty() ? b.folder : b.title);
        String text = b == null ? Words.s("fetch_channel")
            : Words.f("fetch_note", lib.brought(b), b.chapters.size());
        long[] got = ch == null ? null : lib.progress(ch);
        int pct = got != null && got[1] > 0 ? (int) (100 * got[0] / got[1]) : 0;
        String key = title + text + (pct / 5);
        if (!first && key.equals(shown)) {
            return;
        }
        shown = key;
        Intent open = new Intent(this, Main.class);
        open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        Notification n = new Notification.Builder(this, CHANNEL)
            .setSmallIcon(Icon.createWithBitmap(Mark.bitmap(Mark.DOWN)))
            .setContentTitle(title)
            .setContentText(text)
            .setProgress(100, pct, got == null)
            .setOngoing(true)
            .setShowWhen(false)
            .setContentIntent(PendingIntent.getActivity(this, 1, open,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT))
            .build();
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTE, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
            } else {
                startForeground(NOTE, n);
            }
        } catch (Exception refused) {
            Log.note("fetch: the shade refused: " + refused);
        }
    }
}
