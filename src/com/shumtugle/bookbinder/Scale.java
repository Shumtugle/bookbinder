package com.shumtugle.bookbinder;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

/**
 * How far a book has been heard, as a wide bar cut into its chapters: each
 * chapter a segment as long as its share of the book, the heard part
 * filled. When the chapters are too many to tell apart, one whole bar.
 */
final class Scale extends View {

    private final double[] starts;
    private final double done;
    private final int[] col;
    private final float round;
    private final float d;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF shape = new RectF();

    /** Where each chapter begins, as a share of the book from 0 to 1, and how much is heard. */
    Scale(Context c, double[] starts, double done) {
        super(c);
        this.starts = starts;
        this.done = Math.max(0, Math.min(1, done));
        this.col = Look.colors(c);
        this.round = Look.roundness(c);
        this.d = c.getResources().getDisplayMetrics().density;
    }

    @Override
    protected void onDraw(Canvas cv) {
        float w = getWidth();
        float h = getHeight();
        float gap = 2 * d;
        int n = starts.length;
        boolean cut = n > 1 && w / n >= gap * 3;
        float fill = (float) (done * w);
        for (int pass = 0; pass < 2; pass++) {
            if (pass == 1) {
                if (fill <= 0) {
                    break;
                }
                cv.save();
                cv.clipRect(0, 0, fill, h);
            }
            paint.setColor(pass == 0 ? col[Look.LINE] : col[Look.ACCENT]);
            if (!cut) {
                piece(cv, 0, w, h);
            } else {
                for (int i = 0; i < n; i++) {
                    float a = (float) (starts[i] * w) + (i > 0 ? gap / 2f : 0f);
                    float b = (i + 1 < n ? (float) (starts[i + 1] * w) - gap / 2f : w);
                    if (b - a >= 1f) {
                        piece(cv, a, b, h);
                    }
                }
            }
            if (pass == 1) {
                cv.restore();
            }
        }
    }

    private void piece(Canvas cv, float a, float b, float h) {
        float r = Math.min(h / 2f * Math.min(1f, round), (b - a) / 2f);
        shape.set(a, 0, b, h);
        cv.drawRoundRect(shape, r, r, paint);
    }
}
