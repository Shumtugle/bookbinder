package com.shumtugle.bookbinder;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.PopupWindow;

/**
 * The place in a chapter, as a wide strip, and the drum that rises from it.
 *
 * A long chapter breaks an ordinary slider: one pixel is several seconds,
 * so an exact place cannot be found by dragging. Held, or dragged, the
 * strip raises a drum above itself instead: every five seconds a tick on a
 * curving drum under a fixed needle, every minute a taller one, and above
 * them the time the needle stands at and how far that is from now.
 *
 * The same finger, still down, turns it, scaled as a slide rule is: near
 * where it took hold, a tick to each small movement; further off, faster
 * and faster, so that a little under half the strip's width reaches the
 * start or the end of the chapter. A little is exact, far is quick, and
 * the finger brought back to where it began brings the needle back too.
 * Held at either edge, the drum keeps turning, faster nearer the rim.
 * Lifting the finger goes to the needle's place; a cancelled touch goes
 * nowhere.
 */
final class Dial extends View {

    interface Go {
        /** The finger was lifted with the needle here. */
        void go(long ms);

        /** The needle stands here while the drum is up; -1 once it is put away. */
        void aim(long ms);
    }

    private static final long TICK_MS = 5000;
    /** Every how many ticks one stands taller: a minute of five-second ticks. */
    private static final int MAJOR = 12;
    /** Ticks on each side of the needle, how far round the drum the eye sees, and the angle of one tick. */
    private static final int SIDE = 18;
    private static final float REACH = (float) Math.toRadians(62.0);
    private static final float STEP = REACH / SIDE;
    private static final float SPAN = 0.47f;
    /** The part of the width at either side where the drum keeps turning. */
    private static final float EDGE = 0.1f;
    private static final long RAISE_MS = 280;

    private final Go go;
    private final float d;
    private final int slop;
    private final int[] col;
    private final float round;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF shape = new RectF();
    private final Handler ui = new Handler(Looper.getMainLooper());

    private long duration;
    private long position;

    private boolean raised;
    private float downX;
    private float raiseX;
    private float lastX;
    /** Ticks: how many the chapter has, where the needle stood when the drum rose, and where it stands. */
    private float last;
    private float origin;
    private float drag;
    private float rolled;
    private float pos;
    private int felt = Integer.MIN_VALUE;
    private float edgeSpeed;

    /** Each way from where it rose, the travel fitted to the ticks that way: evenly, or on a logarithm. */
    private boolean laid;
    private boolean evenAhead;
    private boolean evenBack;
    private float stepAhead;
    private float stepBack;
    private float fineAhead;
    private float fineBack;
    private float spanAhead;
    private float spanBack;

    private Drum drum;
    private PopupWindow pop;

    Dial(Context c, Go go) {
        super(c);
        this.go = go;
        this.d = c.getResources().getDisplayMetrics().density;
        this.slop = ViewConfiguration.get(c).getScaledTouchSlop();
        this.col = Look.colors(c);
        this.round = Look.roundness(c);
        setHapticFeedbackEnabled(true);
    }

    /** The chapter's length and the place in it, both in milliseconds. */
    void set(long duration, long position) {
        this.duration = Math.max(0, duration);
        this.position = Math.max(0, Math.min(position, this.duration));
        if (!raised) {
            invalidate();
        }
    }

    boolean raised() {
        return raised;
    }

    // ------------------------------------------------------------ touch

    private final Runnable raiseLater = new Runnable() {
        @Override
        public void run() {
            raise(lastX);
        }
    };

    private final Runnable edgeRoll = new Runnable() {
        @Override
        public void run() {
            if (!raised || edgeSpeed == 0f) {
                return;
            }
            rolled += edgeSpeed;
            moved();
            ui.postDelayed(this, 16L);
        }
    };

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (!isEnabled() || duration <= 0) {
            return false;
        }
        float x = e.getX();
        lastX = x;
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = x;
                getParent().requestDisallowInterceptTouchEvent(true);
                ui.postDelayed(raiseLater, RAISE_MS);
                return true;
            case MotionEvent.ACTION_MOVE:
                if (!raised && Math.abs(x - downX) > slop) {
                    ui.removeCallbacks(raiseLater);
                    raise(x);
                }
                if (raised) {
                    drag = x - raiseX;
                    float across = x / Math.max(1f, getWidth());
                    float was = edgeSpeed;
                    edgeSpeed = across < EDGE ? -(EDGE - across) * 12f
                        : across > 1f - EDGE ? (across - (1f - EDGE)) * 12f : 0f;
                    if (was == 0f && edgeSpeed != 0f) {
                        ui.post(edgeRoll);
                    }
                    moved();
                }
                return true;
            case MotionEvent.ACTION_UP:
                ui.removeCallbacks(raiseLater);
                if (raised) {
                    long target = target();
                    lower();
                    if (Math.abs(target - position) >= 500) {
                        position = target;
                        go.go(target);
                    }
                    invalidate();
                }
                return true;
            case MotionEvent.ACTION_CANCEL:
                ui.removeCallbacks(raiseLater);
                lower();
                return true;
            default:
                return true;
        }
    }

    private void raise(float x) {
        if (raised || duration <= 0) {
            return;
        }
        raised = true;
        raiseX = x;
        last = duration / (float) TICK_MS;
        origin = position / (float) TICK_MS;
        pos = origin;
        drag = 0f;
        rolled = 0f;
        laid = false;
        felt = (int) Math.floor(pos);
        edgeSpeed = 0f;
        drum = new Drum(getContext());
        int w = getWidth();
        int h = Math.round(132 * d);
        pop = new PopupWindow(drum, w, h, false);
        pop.setTouchable(false);
        pop.setClippingEnabled(false);
        pop.setElevation(8 * d);
        int[] at = new int[2];
        getLocationInWindow(at);
        pop.showAtLocation(this, Gravity.NO_GRAVITY, at[0], at[1] - h - Math.round(6 * d));
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        go.aim(target());
        invalidate();
    }

    private void lower() {
        ui.removeCallbacks(edgeRoll);
        edgeSpeed = 0f;
        if (pop != null) {
            pop.dismiss();
        }
        pop = null;
        drum = null;
        if (raised) {
            raised = false;
            go.aim(-1);
        }
        invalidate();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        ui.removeCallbacksAndMessages(null);
        if (pop != null) {
            pop.dismiss();
            pop = null;
        }
        raised = false;
    }

    private void moved() {
        if (!laid) {
            lay();
        }
        pos = Math.max(0f, Math.min(last, origin + rolled + travelled()));
        int tick = (int) Math.floor(pos);
        if (tick != felt) {
            felt = tick;
            performHapticFeedback(tick % MAJOR == 0 ? HapticFeedbackConstants.VIRTUAL_KEY
                : HapticFeedbackConstants.CLOCK_TICK);
        }
        go.aim(target());
        if (drum != null) {
            drum.invalidate();
        }
        invalidate();
    }

    private long target() {
        return Math.max(0, Math.min(duration, Math.round(pos * TICK_MS)));
    }

    /**
     * The travel, laid out once: near the start a tick to each spacing of
     * the drum's ticks, and so much faster further off that a little under
     * half the width reaches the last tick that way.
     */
    private void lay() {
        float w = Math.max(1f, getWidth());
        float near = Math.max(1f, w * SPAN * STEP / (float) Math.sin(REACH));
        float room = Math.max(near * 4f, w * 0.42f);
        float[] ahead = fit(last - origin, room, near);
        float[] back = fit(origin, room, near);
        evenAhead = ahead[0] > 0f;
        stepAhead = ahead[1];
        fineAhead = ahead[2];
        spanAhead = ahead[3];
        evenBack = back[0] > 0f;
        stepBack = back[1];
        fineBack = back[2];
        spanBack = back[3];
        laid = true;
    }

    /** Evenly if every tick can have its spacing within the room; otherwise the logarithm whose end lands on the last tick. */
    private static float[] fit(float ticks, float room, float near) {
        if (ticks <= 0f || near * ticks <= room) {
            return new float[] {1f, near, 0f, 0f};
        }
        double want = room / near;
        double lo = 1e-4;
        double hi = 1e7;
        for (int k = 0; k < 60; k++) {
            double mid = (lo + hi) / 2.0;
            if (mid * Math.log(1.0 + ticks / mid) < want) {
                lo = mid;
            } else {
                hi = mid;
            }
        }
        float fine = (float) ((lo + hi) / 2.0);
        return new float[] {0f, near, fine, near * fine};
    }

    /** The ticks passed for the finger's travel since the drum rose: later to the right, earlier to the left. */
    private float travelled() {
        if (drag >= 0f) {
            return evenAhead ? drag / stepAhead : fineAhead * (float) (Math.exp(drag / spanAhead) - 1.0);
        }
        float back = -drag;
        return -(evenBack ? back / stepBack : fineBack * (float) (Math.exp(back / spanBack) - 1.0));
    }

    // ------------------------------------------------------------ the strip

    @Override
    protected void onDraw(Canvas cv) {
        float w = getWidth();
        float h = getHeight();
        float knob = 10 * d;
        float x0 = knob;
        float x1 = w - knob;
        float thick = 12 * d;
        float cy = h / 2f;
        float r = thick / 2f * Math.min(1f, round);
        double frac = duration > 0 ? (raised ? target() : position) / (double) duration : 0;
        float fx = x0 + (float) (frac * (x1 - x0));
        paint.setColor(col[Look.LINE]);
        shape.set(x0, cy - thick / 2f, x1, cy + thick / 2f);
        cv.drawRoundRect(shape, r, r, paint);
        if (fx > x0) {
            cv.save();
            cv.clipRect(x0, 0, fx, h);
            paint.setColor(col[Look.ACCENT]);
            cv.drawRoundRect(shape, r, r, paint);
            cv.restore();
        }
        if (raised && duration > 0) {
            /* Where the voice is now stays marked while the needle looks elsewhere. */
            float nx = x0 + (float) (position / (double) duration * (x1 - x0));
            paint.setColor(col[Look.INK]);
            shape.set(nx - d, cy - thick, nx + d, cy + thick);
            cv.drawRect(shape, paint);
        }
        if (duration > 0) {
            float kr = raised ? knob : knob * 0.8f;
            paint.setColor(col[Look.CARD]);
            cv.drawCircle(fx, cy, kr + 3 * d, paint);
            paint.setColor(col[Look.ACCENT]);
            cv.drawCircle(fx, cy, kr, paint);
        }
    }

    static String clock(long ms) {
        long s = Math.max(0, ms) / 1000;
        long hh = s / 3600;
        long mm = s / 60 % 60;
        long ss = s % 60;
        return hh > 0 ? hh + ":" + two(mm) + ":" + two(ss) : mm + ":" + two(ss);
    }

    private static String two(long v) {
        return v < 10 ? "0" + v : String.valueOf(v);
    }

    // ------------------------------------------------------------ the drum

    /** The drum itself, shown above the strip while the finger holds it. */
    private final class Drum extends View {

        private final Paint tick = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint card = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint big = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint small = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF box = new RectF();

        Drum(Context c) {
            super(c);
            float sp = c.getResources().getDisplayMetrics().scaledDensity;
            card.setColor(col[Look.CARD]);
            big.setColor(col[Look.INK]);
            big.setTextSize(26 * sp);
            big.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            big.setTextAlign(Paint.Align.CENTER);
            small.setColor(col[Look.ACCENT]);
            small.setTextSize(15 * sp);
            small.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            small.setTextAlign(Paint.Align.CENTER);
        }

        @Override
        protected void onDraw(Canvas cv) {
            float w = getWidth();
            float h = getHeight();
            float radius = 20 * d * round;
            box.set(0, 0, w, h);
            cv.drawRoundRect(box, radius, radius, card);

            long at = target();
            long diff = at - position;
            String said = Dial.clock(at);
            String far = (diff >= 0 ? "+" : "−") + Dial.clock(Math.abs(diff));
            float top = 14 * d;
            cv.drawText(said, w / 2f, top + big.getTextSize(), big);
            cv.drawText(far, w / 2f, top + big.getTextSize() + small.getTextSize() * 1.35f, small);

            float cx = w / 2f;
            float drumR = w * SPAN / (float) Math.sin(REACH);
            float rimCos = (float) Math.cos(REACH);
            float mid = h * 0.74f;
            float tall = 20 * d;
            float sag = 14 * d;
            int count = (int) Math.floor(last) + 1;
            int now = Math.round(position / (float) TICK_MS);
            int first = (int) Math.floor(pos) - SIDE;
            for (int i = first; i <= first + SIDE * 2 + 1; i++) {
                if (i < 0 || i >= count) {
                    continue;
                }
                float theta = (i - pos) * STEP;
                if (Math.abs(theta) >= REACH) {
                    continue;
                }
                /* How far from the rim the tick is: one under the needle, nought at the edge of sight. */
                float cos = ((float) Math.cos(theta) - rimCos) / (1f - rimCos);
                float x = cx + drumR * (float) Math.sin(theta);
                float y = mid + sag * (1f - cos);
                boolean minute = i % MAJOR == 0;
                boolean voice = i == now;
                float len = tall * (minute ? 1.45f : 1f) * (0.55f + 0.45f * cos);
                float thick = d * (minute ? 2.2f : 1.6f) * (0.6f + 0.4f * cos);
                int alpha = (int) (255 * Math.pow(cos, 1.6) * (minute ? 0.75f : 0.45f));
                tick.setColor(voice ? col[Look.ACCENT] : col[Look.INK]);
                tick.setAlpha(Math.max(0, Math.min(255, voice ? Math.max(alpha, 200) : alpha)));
                box.set(x - thick / 2f, y - len / 2f, x + thick / 2f, y + len / 2f);
                cv.drawRoundRect(box, thick / 2f, thick / 2f, tick);
            }
            float nw = 2.5f * d;
            tick.setColor(col[Look.ACCENT]);
            tick.setAlpha(255);
            box.set(cx - nw / 2f, mid - tall * 1.2f, cx + nw / 2f, h - 10 * d);
            cv.drawRoundRect(box, nw / 2f, nw / 2f, tick);
        }
    }
}
