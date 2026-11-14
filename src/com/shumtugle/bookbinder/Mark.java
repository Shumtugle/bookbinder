package com.shumtugle.bookbinder;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.View;

/**
 * The application's marks, drawn with lines and nothing else: the same
 * drawing serves the keys on screen and the keys in the notification.
 */
final class Mark {

    static final int PLAY = 0;
    static final int PAUSE = 1;
    static final int BACK = 2;
    static final int ON = 3;
    static final int PREV = 4;
    static final int NEXT = 5;
    static final int REFRESH = 6;
    static final int LEFT = 7;
    static final int MOON = 8;
    static final int CHECK = 9;
    static final int DOWN = 10;
    static final int LINK = 11;
    static final int DOT = 12;
    static final int GEAR = 13;
    static final int CROSS = 14;
    static final int GRID = 15;
    static final int LIST = 16;

    private Mark() {
    }

    /** Draws a mark centred at (cx, cy) inside a square of the given side. */
    static void draw(Canvas c, int kind, float cx, float cy, float side, int color) {
        float u = side / 24f;
        Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeWidth(2f * u);
        line.setStrokeCap(Paint.Cap.ROUND);
        line.setStrokeJoin(Paint.Join.ROUND);
        line.setColor(color);
        Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        fill.setStyle(Paint.Style.FILL);
        fill.setColor(color);
        Path p = new Path();
        float x0 = cx - 12 * u;
        float y0 = cy - 12 * u;
        switch (kind) {
            case PLAY:
                p.moveTo(x0 + 8 * u, y0 + 5 * u);
                p.lineTo(x0 + 19 * u, y0 + 12 * u);
                p.lineTo(x0 + 8 * u, y0 + 19 * u);
                p.close();
                c.drawPath(p, fill);
                line.setStrokeWidth(1.5f * u);
                c.drawPath(p, line);
                break;
            case PAUSE:
                c.drawRoundRect(new RectF(x0 + 6.5f * u, y0 + 5 * u, x0 + 10 * u, y0 + 19 * u), u, u, fill);
                c.drawRoundRect(new RectF(x0 + 14 * u, y0 + 5 * u, x0 + 17.5f * u, y0 + 19 * u), u, u, fill);
                break;
            case BACK:
            case ON: {
                boolean back = kind == BACK;
                RectF ring = new RectF(x0 + 3 * u, y0 + 3 * u, x0 + 21 * u, y0 + 21 * u);
                c.drawArc(ring, back ? -60 : -120, back ? -260 : 260, false, line);
                /* Arrow head at the open end of the ring. */
                float ax = back ? x0 + 16.5f * u : x0 + 7.5f * u;
                float ay = y0 + 4.2f * u;
                float dir = back ? 1f : -1f;
                p.moveTo(ax + dir * 3.2f * u, ay - 2.4f * u);
                p.lineTo(ax - dir * 0.2f * u, ay);
                p.lineTo(ax + dir * 3.0f * u, ay + 2.8f * u);
                c.drawPath(p, line);
                Paint t = new Paint(Paint.ANTI_ALIAS_FLAG);
                t.setColor(color);
                t.setTextAlign(Paint.Align.CENTER);
                t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
                t.setTextSize(7.5f * u);
                c.drawText(back ? "15" : "30", cx, cy + 2.7f * u, t);
                break;
            }
            case PREV:
            case NEXT: {
                boolean prev = kind == PREV;
                float s = prev ? -1 : 1;
                p.moveTo(cx - s * 6 * u, y0 + 6 * u);
                p.lineTo(cx + s * 4 * u, cy);
                p.lineTo(cx - s * 6 * u, y0 + 18 * u);
                p.close();
                c.drawPath(p, fill);
                line.setStrokeWidth(1.5f * u);
                c.drawPath(p, line);
                line.setStrokeWidth(2.4f * u);
                c.drawLine(cx + s * 6.5f * u, y0 + 6 * u, cx + s * 6.5f * u, y0 + 18 * u, line);
                break;
            }
            case REFRESH: {
                RectF ring = new RectF(x0 + 5 * u, y0 + 5 * u, x0 + 19 * u, y0 + 19 * u);
                c.drawArc(ring, -80, 300, false, line);
                p.moveTo(x0 + 12.5f * u, y0 + 1.8f * u);
                p.lineTo(x0 + 15.6f * u, y0 + 5f * u);
                p.lineTo(x0 + 12.2f * u, y0 + 7.8f * u);
                c.drawPath(p, line);
                break;
            }
            case LEFT:
                p.moveTo(x0 + 15 * u, y0 + 5 * u);
                p.lineTo(x0 + 8 * u, cy);
                p.lineTo(x0 + 15 * u, y0 + 19 * u);
                c.drawPath(p, line);
                break;
            case MOON: {
                Path a = new Path();
                a.addCircle(cx, cy, 8 * u, Path.Direction.CW);
                Path b = new Path();
                b.addCircle(cx + 5 * u, cy - 4 * u, 7 * u, Path.Direction.CW);
                a.op(b, Path.Op.DIFFERENCE);
                c.drawPath(a, line);
                break;
            }
            case CHECK:
                p.moveTo(x0 + 5 * u, y0 + 12.5f * u);
                p.lineTo(x0 + 10 * u, y0 + 17.5f * u);
                p.lineTo(x0 + 19 * u, y0 + 7 * u);
                c.drawPath(p, line);
                break;
            case DOWN:
                c.drawLine(cx, y0 + 4 * u, cx, y0 + 15 * u, line);
                p.moveTo(x0 + 7 * u, y0 + 10.5f * u);
                p.lineTo(cx, y0 + 15.5f * u);
                p.lineTo(x0 + 17 * u, y0 + 10.5f * u);
                c.drawPath(p, line);
                c.drawLine(x0 + 5 * u, y0 + 20 * u, x0 + 19 * u, y0 + 20 * u, line);
                break;
            case LINK: {
                c.save();
                c.rotate(-45, cx, cy);
                RectF l = new RectF(cx - 10 * u, cy - 3.5f * u, cx + 1 * u, cy + 3.5f * u);
                RectF r = new RectF(cx - 1 * u, cy - 3.5f * u, cx + 10 * u, cy + 3.5f * u);
                c.drawRoundRect(l, 3.5f * u, 3.5f * u, line);
                c.drawRoundRect(r, 3.5f * u, 3.5f * u, line);
                c.restore();
                break;
            }
            case DOT:
                c.drawCircle(cx, cy, 3.2f * u, fill);
                break;
            case GEAR: {
                /* Eight teeth round a ring, a hole in the middle. */
                for (int k = 0; k < 8; k++) {
                    c.save();
                    c.rotate(k * 45f, cx, cy);
                    c.drawRoundRect(new RectF(cx - 1.8f * u, cy - 10f * u, cx + 1.8f * u, cy - 6f * u), 0.8f * u, 0.8f * u, fill);
                    c.restore();
                }
                c.drawCircle(cx, cy, 6.6f * u, line);
                c.drawCircle(cx, cy, 2.6f * u, line);
                break;
            }
            case GRID: {
                float r = 1.6f * u;
                c.drawRoundRect(new RectF(x0 + 4 * u, y0 + 4 * u, x0 + 11 * u, y0 + 11 * u), r, r, line);
                c.drawRoundRect(new RectF(x0 + 13 * u, y0 + 4 * u, x0 + 20 * u, y0 + 11 * u), r, r, line);
                c.drawRoundRect(new RectF(x0 + 4 * u, y0 + 13 * u, x0 + 11 * u, y0 + 20 * u), r, r, line);
                c.drawRoundRect(new RectF(x0 + 13 * u, y0 + 13 * u, x0 + 20 * u, y0 + 20 * u), r, r, line);
                break;
            }
            case LIST: {
                float r = 1.4f * u;
                c.drawRoundRect(new RectF(x0 + 4 * u, y0 + 5 * u, x0 + 9 * u, y0 + 10 * u), r, r, line);
                c.drawRoundRect(new RectF(x0 + 4 * u, y0 + 14 * u, x0 + 9 * u, y0 + 19 * u), r, r, line);
                c.drawLine(x0 + 12 * u, y0 + 7.5f * u, x0 + 20 * u, y0 + 7.5f * u, line);
                c.drawLine(x0 + 12 * u, y0 + 16.5f * u, x0 + 20 * u, y0 + 16.5f * u, line);
                break;
            }
            case CROSS:
                c.drawLine(x0 + 7 * u, y0 + 7 * u, x0 + 17 * u, y0 + 17 * u, line);
                c.drawLine(x0 + 17 * u, y0 + 7 * u, x0 + 7 * u, y0 + 17 * u, line);
                break;
            default:
                break;
        }
    }

    /** A mark drawn white on nothing, for the notification to tint. */
    static Bitmap bitmap(int kind) {
        int side = 96;
        Bitmap b = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888);
        draw(new Canvas(b), kind, side / 2f, side / 2f, side * 0.8f, 0xFFFFFFFF);
        return b;
    }

    /** A key on screen: a mark, and under it an optional disc. */
    static final class Key extends View {
        private int kind;
        private int ink;
        private int disc;
        private float scale = 0.55f;
        private final Paint round = new Paint(Paint.ANTI_ALIAS_FLAG);

        Key(Context c, int kind, int ink, int disc) {
            super(c);
            this.kind = kind;
            this.ink = ink;
            this.disc = disc;
            setClickable(true);
        }

        void set(int kind) {
            if (this.kind != kind) {
                this.kind = kind;
                invalidate();
            }
        }

        void scale(float s) {
            scale = s;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth();
            float h = getHeight();
            float side = Math.min(w, h);
            if (disc != 0) {
                round.setColor(disc);
                c.drawCircle(w / 2f, h / 2f, side / 2f, round);
            }
            float a = isPressed() ? 0.55f : 1f;
            int col = (Math.round(((ink >>> 24) & 0xFF) * a) << 24) | (ink & 0xFFFFFF);
            Mark.draw(c, kind, w / 2f, h / 2f, side * scale, col);
        }

        @Override
        protected void drawableStateChanged() {
            super.drawableStateChanged();
            invalidate();
        }
    }
}
