package com.shumtugle.bookbinder;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.drawable.Icon;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.MediaPlayer;
import android.media.PlaybackParams;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;

import java.util.ArrayList;

/**
 * The player, kept in a service because a book goes on when the screen
 * goes dark. One book at a time, its chapters one after another; each
 * chapter is played from the phone's own copy, fetched just before it is
 * needed. Where the listener is gets written every five seconds and at
 * every pause, and a book picked up after a pause steps back a few seconds,
 * so a sentence broken off is heard again from its start.
 *
 * The phone is a shared room: a call takes the sound away and gives it
 * back, headphones pulled out stop it, and the keys on a headset speak to
 * this player while it plays.
 */
public final class Play extends Service implements MediaPlayer.OnCompletionListener,
    MediaPlayer.OnErrorListener, MediaPlayer.OnPreparedListener, Library.Watch {

    static final String OPEN = "com.shumtugle.bookbinder.OPEN";
    static final String TOGGLE = "com.shumtugle.bookbinder.TOGGLE";
    static final String BACK = "com.shumtugle.bookbinder.BACK";
    static final String ON = "com.shumtugle.bookbinder.ON";
    static final String PREV = "com.shumtugle.bookbinder.PREV";
    static final String NEXT = "com.shumtugle.bookbinder.NEXT";

    static final long BACK_MS = 15000L;
    static final long ON_MS = 30000L;
    private static final long SAVE_EVERY = 5000L;
    private static final String CHANNEL = "play";
    private static final int NOTE = 1;

    static final float[] PACES = {1f, 1.15f, 1.3f, 1.5f, 1.75f, 0.9f};
    /** Sleep choices in minutes; -1 stops at the end of the chapter. */
    static final int[] SLEEPS = {0, 15, 30, 45, 60, -1};

    /** Told whenever what plays, or whether it plays, changes. */
    interface Ear {
        void heard();
    }

    private static Play live;
    private static final ArrayList<Ear> ears = new ArrayList<Ear>();

    static Play live() {
        return live;
    }

    static void listen(Ear e) {
        if (!ears.contains(e)) {
            ears.add(e);
        }
    }

    static void deafen(Ear e) {
        ears.remove(e);
    }

    private final Handler ui = new Handler(Looper.getMainLooper());
    private Library lib;
    private MediaPlayer player;
    private boolean prepared;
    /** The listener asked for sound; it starts as soon as the chapter can. */
    private boolean wanted;
    private boolean resuming;
    private boolean pausedByFocus;
    private boolean noisyOn;
    private boolean foreground;
    private Shelf.Book book;
    private int index = -1;
    /** Where to start the chapter being prepared. */
    private long startAt;
    private MediaSession session;
    private AudioManager audio;
    private AudioFocusRequest focus;
    private int sleep;
    private boolean hadCover;
    private long sleepAt;

    // ------------------------------------------------------------ life

    @Override
    public void onCreate() {
        super.onCreate();
        live = this;
        Words.load(this);
        lib = Library.get(this);
        lib.watch(this);
        audio = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        NotificationManager notes = getSystemService(NotificationManager.class);
        if (notes != null) {
            NotificationChannel ch = new NotificationChannel(CHANNEL, Words.s("channel"),
                NotificationManager.IMPORTANCE_LOW);
            ch.setShowBadge(false);
            notes.createNotificationChannel(ch);
        }
        session = new MediaSession(this, "bookbinder");
        session.setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS
            | MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS);
        session.setCallback(new MediaSession.Callback() {
            @Override
            public void onPlay() {
                play();
            }

            @Override
            public void onPause() {
                pause(true);
            }

            @Override
            public void onStop() {
                pause(true);
            }

            /* A headset's "next" and "previous" move within the chapter: a
               book is not skipped through like songs. Whole chapters are
               changed from the screen. */
            @Override
            public void onSkipToNext() {
                skip(ON_MS);
            }

            @Override
            public void onSkipToPrevious() {
                skip(-BACK_MS);
            }

            @Override
            public void onFastForward() {
                skip(ON_MS);
            }

            @Override
            public void onRewind() {
                skip(-BACK_MS);
            }

            @Override
            public void onSeekTo(long ms) {
                seek(ms);
            }
        });
        long last = Places.last(this);
        Shelf.Book b = last >= 0 ? lib.book(last) : null;
        if (b != null) {
            open(b, -1, false);
        }
    }

    @Override
    public int onStartCommand(Intent i, int flags, int id) {
        String act = i == null ? null : i.getAction();
        if (OPEN.equals(act)) {
            Shelf.Book b = lib.book(i.getLongExtra("book", -1));
            if (b != null) {
                open(b, i.getIntExtra("chapter", -1), i.getBooleanExtra("play", true));
            }
        } else if (TOGGLE.equals(act)) {
            toggle();
        } else if (BACK.equals(act)) {
            skip(-BACK_MS);
        } else if (ON.equals(act)) {
            skip(ON_MS);
        } else if (PREV.equals(act)) {
            chapter(-1);
        } else if (NEXT.equals(act)) {
            chapter(1);
        }
        /* Started from a notification key as a foreground service, it must
           show itself at once, whatever the key did. */
        showNote();
        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent i) {
        return null;
    }

    @Override
    public void onDestroy() {
        save();
        lib.unwatch(this);
        ui.removeCallbacksAndMessages(null);
        release();
        noisy(false);
        dropFocus();
        if (session != null) {
            session.setActive(false);
            session.release();
            session = null;
        }
        live = null;
        told();
        super.onDestroy();
    }

    @Override
    public void onTaskRemoved(Intent root) {
        /* Swiped away while silent: nothing to keep alive. */
        if (!playing()) {
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
        }
        super.onTaskRemoved(root);
    }

    // ------------------------------------------------------------ what is open

    Shelf.Book book() {
        return book;
    }

    int index() {
        return index;
    }

    Shelf.Chapter chapter() {
        return book == null || index < 0 || index >= book.chapters.size() ? null : book.chapters.get(index);
    }

    boolean playing() {
        try {
            return player != null && prepared && player.isPlaying();
        } catch (IllegalStateException e) {
            return false;
        }
    }

    /** Playing, or about to: the chapter is still on its way and will start when it comes. */
    boolean meant() {
        return playing() || wanted;
    }

    boolean ready() {
        return prepared;
    }

    long position() {
        try {
            return player != null && prepared ? player.getCurrentPosition() : startAt;
        } catch (IllegalStateException e) {
            return startAt;
        }
    }

    long duration() {
        try {
            return player != null && prepared ? Math.max(0, player.getDuration()) : 0;
        } catch (IllegalStateException e) {
            return 0;
        }
    }

    /**
     * Opens a book: at a chapter, or (chapter -1) where it was left. The
     * book already open is not opened again unless another chapter is asked.
     */
    void open(Shelf.Book b, int chapter, boolean play) {
        if (book != null && book.id == b.id && (chapter < 0 || chapter == index) && index >= 0) {
            if (play) {
                play();
            }
            return;
        }
        save();
        release();
        book = b;
        long pos = 0;
        if (chapter < 0) {
            int at = b.find(Places.chapter(this, b.id));
            if (at >= 0) {
                chapter = at;
                pos = Places.position(this, b.id);
                if (Places.finished(this, b.id)) {
                    chapter = 0;
                    pos = 0;
                }
            } else {
                chapter = 0;
            }
        }
        index = Math.max(0, Math.min(chapter, b.chapters.size() - 1));
        startAt = pos;
        resuming = pos > 0;
        wanted = play;
        Places.last(this, b.id);
        Places.put(this, b.id, b.chapters.get(index).id, startAt);
        prepare();
        told();
    }

    /** Lets go of the book entirely: its files are about to be deleted. */
    void close() {
        pause(true);
        release();
        book = null;
        index = -1;
        Places.last(this, -1);
        stopForeground(STOP_FOREGROUND_REMOVE);
        foreground = false;
        told();
        stopSelf();
    }

    /** Moves by whole chapters; at the start of a chapter "back" goes to the one before. */
    void chapter(int step) {
        if (book == null) {
            return;
        }
        int to = index + step;
        if (step < 0 && position() > 5000L) {
            to = index;
        }
        if (to < 0 || to >= book.chapters.size()) {
            if (to < 0) {
                seek(0);
            }
            return;
        }
        boolean go = meant();
        save();
        release();
        index = to;
        startAt = 0;
        resuming = false;
        wanted = go;
        Places.put(this, book.id, book.chapters.get(index).id, 0);
        prepare();
        told();
    }

    /** Asks for the chapter's file, and prepares the player when it is here. */
    private void prepare() {
        Shelf.Chapter ch = chapter();
        if (ch == null) {
            return;
        }
        lib.hearing(book, index);
        if (!lib.ready(ch)) {
            showNote();
            return;
        }
        release();
        try {
            player = new MediaPlayer();
            player.setAudioAttributes(new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build());
            player.setWakeMode(this, PowerManager.PARTIAL_WAKE_LOCK);
            player.setOnPreparedListener(this);
            player.setOnCompletionListener(this);
            player.setOnErrorListener(this);
            String where = lib.location(ch);
            if (where.startsWith("content:")) {
                player.setDataSource(this, android.net.Uri.parse(where));
            } else {
                player.setDataSource(where);
            }
            player.prepareAsync();
        } catch (Exception e) {
            Log.note("play: could not open " + ch.name + ": " + e);
            release();
            /* Deleted or moved by hand: brought again, since it is wanted now. */
            lib.lost(ch);
        }
    }

    @Override
    public void onPrepared(MediaPlayer p) {
        prepared = true;
        if (startAt > 0) {
            try {
                p.seekTo((int) Math.min(startAt, Math.max(0, p.getDuration() - 1000)));
            } catch (Exception ignored) {
                Log.note("play: seek on open refused");
            }
        }
        if (wanted) {
            play();
        } else {
            showNote();
            told();
        }
    }

    @Override
    public void changed() {
        /* A cover arrived for the book on the widget: draw it there. */
        boolean cover = book != null && lib.cover(book) != null;
        if (cover != hadCover) {
            hadCover = cover;
            Widget.refresh(this);
        }
        /* A chapter arrived: if it is the one waited for, it is opened now. */
        Shelf.Chapter ch = chapter();
        if (ch != null && player == null && lib.ready(ch)) {
            prepare();
        } else if (ch != null && player == null && wanted) {
            showNote();
        }
    }

    private void release() {
        ui.removeCallbacks(settle);
        if (player != null) {
            try {
                player.release();
            } catch (Exception ignored) {
                Log.note("play: release refused");
            }
        }
        player = null;
        prepared = false;
    }

    // ------------------------------------------------------------ sound

    void toggle() {
        if (meant()) {
            pause(true);
        } else {
            play();
        }
    }

    void play() {
        if (book == null) {
            return;
        }
        wanted = true;
        if (player == null || !prepared) {
            Shelf.Chapter ch = chapter();
            if (ch != null && lib.failed(ch)) {
                lib.retry(ch);
            }
            showNote();
            told();
            return;
        }
        if (!takeFocus()) {
            Log.note("play: focus refused");
            return;
        }
        noisy(true);
        if (resuming) {
            resuming = false;
            long p = position();
            if (p > 0) {
                seekRaw(Math.max(0L, p - Places.rollback(this) * 1000L));
            }
        }
        ui.removeCallbacks(settle);
        boolean around = detour();
        try {
            if (around) {
                pace(0.99f);
            }
            player.start();
            if (around) {
                ui.postDelayed(settle, 1000L);
            } else {
                pace(Places.pace(this));
            }
        } catch (Exception e) {
            Log.note("play: start refused: " + e);
            return;
        }
        pausedByFocus = false;
        if (session != null) {
            session.setActive(true);
        }
        ui.removeCallbacks(saver);
        ui.postDelayed(saver, SAVE_EVERY);
        armSleep();
        showNote();
        told();
    }

    /** A pause asked for by hand is final; one caused by the phone gives the sound back when it can. */
    void pause(boolean byHand) {
        wanted = false;
        ui.removeCallbacks(settle);
        try {
            if (player != null && prepared && player.isPlaying()) {
                player.pause();
            }
        } catch (IllegalStateException ignored) {
            Log.note("play: pause refused");
        }
        resuming = true;
        if (byHand) {
            pausedByFocus = false;
            dropFocus();
            sleep = 0;
            ui.removeCallbacks(sleeper);
        }
        noisy(false);
        save();
        ui.removeCallbacks(saver);
        ui.removeCallbacks(letGo);
        ui.postDelayed(letGo, LET_GO_MS);
        showNote();
        told();
    }

    /*
     * A headset key goes to the player whose session is active. Held for
     * ever, it would stop starting the listener's music elsewhere; so after
     * a long pause the session is let go, and taken again at the next play.
     */
    private static final long LET_GO_MS = 10 * 60 * 1000L;

    private final Runnable letGo = new Runnable() {
        @Override
        public void run() {
            if (!meant() && session != null) {
                session.setActive(false);
            }
        }
    };

    void skip(long ms) {
        if (player == null || !prepared) {
            return;
        }
        long to = position() + ms;
        if (to < 0) {
            to = 0;
        }
        long d = duration();
        if (d > 0 && to > d - 500) {
            to = d - 500;
        }
        seek(to);
    }

    void seek(long ms) {
        if (player == null || !prepared) {
            startAt = Math.max(0, ms);
            return;
        }
        seekRaw(ms);
        resuming = false;
        save();
        showNote();
        told();
    }

    private void seekRaw(long ms) {
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                player.seekTo(ms, MediaPlayer.SEEK_CLOSEST);
            } else {
                player.seekTo((int) ms);
            }
        } catch (Exception e) {
            Log.note("play: seek refused: " + e);
        }
    }

    float pace() {
        return Places.pace(this);
    }

    /** The next pace in the circle of choices. */
    void nextPace() {
        float now = Places.pace(this);
        int at = 0;
        for (int i = 0; i < PACES.length; i++) {
            if (Math.abs(PACES[i] - now) < 0.01f) {
                at = i;
            }
        }
        Places.pace(this, PACES[(at + 1) % PACES.length]);
        if (playing()) {
            ui.removeCallbacks(settle);
            pace(Places.pace(this));
        }
        told();
    }

    /*
     * Long AAC recordings go silent on some phones at the natural pace: the
     * platform hands an audio-only file to the phone's sound processor, and
     * it plays nothing while the clock runs on. Any other pace keeps the
     * sound on the ordinary road, and the road is not given back when the
     * pace returns to one. So such a chapter starts a hair slower and is set
     * right a second later, at every start.
     */
    private boolean detour() {
        Shelf.Chapter ch = chapter();
        return ch != null && ch.aac() && Places.pace(this) == 1f;
    }

    private final Runnable settle = new Runnable() {
        @Override
        public void run() {
            if (playing()) {
                pace(Places.pace(Play.this));
            }
        }
    };

    private void pace(float v) {
        if (player == null || !prepared) {
            return;
        }
        try {
            boolean was = player.isPlaying();
            PlaybackParams params = player.getPlaybackParams();
            params.setSpeed(v);
            /* On some phones setting the pace of a paused player starts it. */
            player.setPlaybackParams(params);
            if (!was && player.isPlaying()) {
                player.pause();
            }
        } catch (Exception e) {
            Log.note("play: pace refused: " + e);
        }
    }

    @Override
    public void onCompletion(MediaPlayer p) {
        Shelf.Chapter ch = chapter();
        if (book == null || ch == null) {
            return;
        }
        boolean last = index >= book.chapters.size() - 1;
        if (last) {
            Places.put(this, book.id, ch.id, 0);
            Places.finish(this, book.id);
            pause(true);
            seekRaw(0);
            return;
        }
        if (sleep == -1) {
            /* The sleep was set for the end of this chapter: it has come. */
            sleep = 0;
            index++;
            startAt = 0;
            resuming = false;
            wanted = false;
            release();
            Places.put(this, book.id, book.chapters.get(index).id, 0);
            pause(true);
            prepare();
            return;
        }
        release();
        index++;
        startAt = 0;
        resuming = false;
        wanted = true;
        Places.put(this, book.id, book.chapters.get(index).id, 0);
        prepare();
        told();
    }

    @Override
    public boolean onError(MediaPlayer p, int what, int extra) {
        Log.note("play: error " + what + "/" + extra);
        /* A broken copy is let go and fetched again. */
        Shelf.Chapter ch = chapter();
        boolean go = wanted || playing();
        release();
        if (ch != null) {
            wanted = go;
            lib.lost(ch);
        }
        return true;
    }

    // ------------------------------------------------------------ sleep

    int sleep() {
        return sleep;
    }

    /** Minutes left before sleep, or -1 when it waits for the end of the chapter, 0 when off. */
    long sleepLeft() {
        if (sleep > 0) {
            return Math.max(0, sleepAt - SystemClock.elapsedRealtime());
        }
        return sleep;
    }

    void nextSleep() {
        int at = 0;
        for (int i = 0; i < SLEEPS.length; i++) {
            if (SLEEPS[i] == sleep) {
                at = i;
            }
        }
        sleep = SLEEPS[(at + 1) % SLEEPS.length];
        sleepAt = SystemClock.elapsedRealtime() + sleep * 60000L;
        armSleep();
        told();
    }

    private void armSleep() {
        ui.removeCallbacks(sleeper);
        if (sleep > 0) {
            ui.postDelayed(sleeper, Math.max(1000L, sleepAt - SystemClock.elapsedRealtime()));
        }
    }

    private final Runnable sleeper = new Runnable() {
        @Override
        public void run() {
            if (sleep > 0 && SystemClock.elapsedRealtime() >= sleepAt - 500) {
                pause(true);
            } else {
                armSleep();
            }
        }
    };

    // ------------------------------------------------------------ keeping the place

    private final Runnable saver = new Runnable() {
        @Override
        public void run() {
            save();
            if (playing()) {
                ui.postDelayed(this, SAVE_EVERY);
            }
        }
    };

    private void save() {
        Shelf.Chapter ch = chapter();
        if (book == null || ch == null || !prepared) {
            return;
        }
        Places.put(this, book.id, ch.id, position());
    }

    // ------------------------------------------------------------ the room

    private final AudioManager.OnAudioFocusChangeListener shared =
        new AudioManager.OnAudioFocusChangeListener() {
            @Override
            public void onAudioFocusChange(int change) {
                if (change == AudioManager.AUDIOFOCUS_LOSS) {
                    pause(true);
                } else if (change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT
                    || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK) {
                    /* Speech is not ducked: a lowered voice is still a voice
                       over whatever spoke. The book waits instead. */
                    if (playing()) {
                        pause(false);
                        pausedByFocus = true;
                    }
                } else if (change == AudioManager.AUDIOFOCUS_GAIN) {
                    if (pausedByFocus) {
                        pausedByFocus = false;
                        play();
                    }
                }
            }
        };

    private boolean takeFocus() {
        if (audio == null) {
            return true;
        }
        if (focus == null) {
            focus = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .setWillPauseWhenDucked(true)
                .setOnAudioFocusChangeListener(shared, ui)
                .build();
        }
        return audio.requestAudioFocus(focus) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
    }

    private void dropFocus() {
        if (audio != null && focus != null) {
            audio.abandonAudioFocusRequest(focus);
        }
    }

    private final BroadcastReceiver unplugged = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            if (AudioManager.ACTION_AUDIO_BECOMING_NOISY.equals(i.getAction())) {
                pause(true);
            }
        }
    };

    private void noisy(boolean on) {
        if (on == noisyOn) {
            return;
        }
        try {
            if (on) {
                registerReceiver(unplugged, new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY));
            } else {
                unregisterReceiver(unplugged);
            }
            noisyOn = on;
        } catch (Exception e) {
            Log.note("play: noisy receiver: " + e);
        }
    }

    // ------------------------------------------------------------ telling

    private void told() {
        state();
        Widget.refresh(this);
        for (Ear e : new ArrayList<Ear>(ears)) {
            e.heard();
        }
    }

    private void state() {
        if (session == null) {
            return;
        }
        session.setPlaybackState(new PlaybackState.Builder()
            .setActions(PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE
                | PlaybackState.ACTION_PLAY_PAUSE | PlaybackState.ACTION_SEEK_TO
                | PlaybackState.ACTION_SKIP_TO_NEXT | PlaybackState.ACTION_SKIP_TO_PREVIOUS
                | PlaybackState.ACTION_FAST_FORWARD | PlaybackState.ACTION_REWIND)
            .setState(meant() ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED,
                position(), playing() ? Places.pace(this) : 0f)
            .build());
    }

    private void describe() {
        Shelf.Chapter ch = chapter();
        if (session == null || book == null || ch == null) {
            return;
        }
        MediaMetadata.Builder m = new MediaMetadata.Builder()
            .putString(MediaMetadata.METADATA_KEY_TITLE, book.title.isEmpty() ? book.folder : book.title)
            .putString(MediaMetadata.METADATA_KEY_ARTIST, Words.f("chapter_of", index + 1, book.chapters.size()))
            .putString(MediaMetadata.METADATA_KEY_ALBUM, book.author)
            .putLong(MediaMetadata.METADATA_KEY_DURATION, duration());
        Bitmap art = lib.cover(book);
        if (art != null) {
            m.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, art);
        }
        session.setMetadata(m.build());
    }

    /**
     * The notification: the book, the chapter, and five keys. It holds the
     * service in front while the sound plays, and lets go of it, without
     * disappearing, while it is paused.
     */
    private void showNote() {
        Shelf.Chapter ch = chapter();
        describe();
        state();
        Intent back = new Intent(this, Main.class);
        back.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent open = PendingIntent.getActivity(this, 0, back,
            PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        boolean on = meant();
        String title = book == null ? "Bookbinder" : (book.title.isEmpty() ? book.folder : book.title);
        String text = ch == null ? "" : Words.f("chapter_of", index + 1, book.chapters.size());
        if (ch != null && !lib.ready(ch)) {
            long[] got = lib.progress(ch);
            text += " · " + (got != null && got[1] > 0
                ? Words.f("loading", (int) (100 * got[0] / got[1])) : Words.s("waiting"));
        }
        Notification.Builder note = new Notification.Builder(this, CHANNEL)
            .setSmallIcon(Icon.createWithBitmap(Mark.bitmap(Mark.PLAY)))
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(on)
            .setShowWhen(false)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .addAction(key(Mark.PREV, PREV, Words.s("prev")))
            .addAction(key(Mark.BACK, BACK, Words.s("back")))
            .addAction(key(on ? Mark.PAUSE : Mark.PLAY, TOGGLE, Words.s(on ? "pause" : "play")))
            .addAction(key(Mark.ON, ON, Words.s("on")))
            .addAction(key(Mark.NEXT, NEXT, Words.s("next")))
            .setStyle(new Notification.MediaStyle()
                .setMediaSession(session == null ? null : session.getSessionToken())
                .setShowActionsInCompactView(1, 2, 3));
        Bitmap art = book == null ? null : lib.cover(book);
        if (art != null) {
            note.setLargeIcon(art);
        }
        Notification made = note.build();
        try {
            if (on || !foreground) {
                if (Build.VERSION.SDK_INT >= 29) {
                    startForeground(NOTE, made, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
                } else {
                    startForeground(NOTE, made);
                }
                foreground = true;
            } else {
                NotificationManager notes = getSystemService(NotificationManager.class);
                if (notes != null) {
                    notes.notify(NOTE, made);
                }
            }
            if (!on && foreground) {
                stopForeground(STOP_FOREGROUND_DETACH);
                foreground = false;
            }
        } catch (Exception refused) {
            Log.note("play: the shade refused: " + refused);
        }
    }

    private Notification.Action key(int mark, String act, String name) {
        Intent i = new Intent(this, Play.class).setAction(act);
        PendingIntent pi = PendingIntent.getForegroundService(this, mark, i,
            PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Action.Builder(Icon.createWithBitmap(Mark.bitmap(mark)), name, pi).build();
    }
}
