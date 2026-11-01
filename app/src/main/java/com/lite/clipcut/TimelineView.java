package com.lite.clipcut;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;
import java.util.Locale;

/** CapCut-style timeline: time ruler, filmstrip, trim handles and a draggable playhead. */
public class TimelineView extends View {
    public interface Listener { void onSeek(int ms, boolean fin); void onTrim(int startMs, int endMs); }
    Listener listener; Bitmap[] thumbs; boolean has; int dur = 1, start, end = 1, pos, drag;
    final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG); final float d;
    final Rect src = new Rect(), dst = new Rect(); final RectF rf = new RectF();

    public TimelineView(Context c) { super(c); d = c.getResources().getDisplayMetrics().density; p.setTextSize(10 * d); }
    void setClip(int ms) { dur = Math.max(1, ms); start = 0; end = dur; pos = 0; thumbs = null; has = true; invalidate(); }
    void setPos(int ms) { pos = ms; invalidate(); }
    float L() { return 22 * d; }
    float R() { return getWidth() - 22 * d; }
    float x(int ms) { return L() + (R() - L()) * ms / dur; }
    int ms(float x) { return (int) Math.max(0, Math.min(dur, (x - L()) / (R() - L()) * dur)); }
    float top() { return 28 * d; }
    float bot() { return top() + 56 * d; }

    @Override protected void onDraw(Canvas c) {
        float t = top(), b = bot(), l = L(), r = R();
        p.setStyle(Paint.Style.FILL); p.setColor(0xFF26262B);
        rf.set(l, t, r, b); c.drawRoundRect(rf, 4 * d, 4 * d, p);
        if (!has) { p.setColor(0xFF8A8A92); c.drawText("Open a video to start", l + 10 * d, (t + b) / 2, p); return; }
        // ruler
        float pxSec = (r - l) / (dur / 1000f); int step = 300;
        for (int s : new int[]{1, 2, 5, 10, 15, 30, 60, 120, 300}) if (pxSec * s >= 56 * d) { step = s; break; }
        p.setColor(0xFF8A8A92);
        for (int s = 0; s * 1000 <= dur; s += step) {
            float xx = x(s * 1000);
            c.drawText(String.format(Locale.US, "%02d:%02d", s / 60, s % 60), xx - 12 * d, 14 * d, p);
            c.drawCircle(xx, 21 * d, 1.5f * d, p);
        }
        // filmstrip
        Bitmap[] th = thumbs; int n = th == null ? 0 : th.length; float cw = (r - l) / Math.max(n, 1);
        for (int i = 0; i < n; i++) {
            Bitmap bm = th[i]; if (bm == null) continue;
            float ar = cw / (b - t); int bw = bm.getWidth(), bh = bm.getHeight();
            int sw = Math.min(bw, (int) (bh * ar)), sh = Math.min(bh, (int) (bw / ar));
            src.set((bw - sw) / 2, (bh - sh) / 2, (bw + sw) / 2, (bh + sh) / 2);
            dst.set((int) (l + i * cw), (int) t, (int) (l + (i + 1) * cw) + 1, (int) b);
            c.drawBitmap(bm, src, dst, null);
        }
        // dim the trimmed-away parts, then frame + handles
        float xs = x(start), xe = x(end);
        p.setColor(0xB0000000); c.drawRect(l, t, xs, b, p); c.drawRect(xe, t, r, b, p);
        p.setColor(0xFFFFFFFF); p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(2 * d); c.drawRect(xs, t, xe, b, p);
        p.setStyle(Paint.Style.FILL);
        rf.set(xs - 14 * d, t, xs, b); c.drawRoundRect(rf, 4 * d, 4 * d, p);
        rf.set(xe, t, xe + 14 * d, b); c.drawRoundRect(rf, 4 * d, 4 * d, p);
        p.setColor(0xFF333338);
        for (float hx : new float[]{xs - 7 * d, xe + 7 * d}) c.drawRect(hx - d, (t + b) / 2 - 8 * d, hx + d, (t + b) / 2 + 8 * d, p);
        // playhead
        float px = x(pos); p.setColor(0xFFFFFFFF);
        c.drawRect(px - d, t - 6 * d, px + d, b + 6 * d, p); c.drawCircle(px, t - 6 * d, 4 * d, p);
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        if (!has) return true;
        float ex = e.getX(); int a = e.getAction() & MotionEvent.ACTION_MASK;
        if (a == MotionEvent.ACTION_DOWN) {
            float ds = Math.abs(ex - (x(start) - 7 * d)), de = Math.abs(ex - (x(end) + 7 * d)), lim = 24 * d;
            drag = (ds < lim && ds <= de) ? 1 : (de < lim) ? 2 : 3;
            if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
        }
        if (a == MotionEvent.ACTION_DOWN || a == MotionEvent.ACTION_MOVE) {
            int m = ms(ex);
            if (drag == 1) { start = Math.max(0, Math.min(m, end - 500)); pos = start; }
            else if (drag == 2) { end = Math.min(dur, Math.max(m, start + 500)); pos = end; }
            else pos = m;
            if (listener != null) { if (drag != 3) listener.onTrim(start, end); listener.onSeek(pos, false); }
            invalidate();
        } else if ((a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) && drag != 0) {
            if (listener != null) listener.onSeek(pos, true);
            drag = 0;
        }
        return true;
    }
}
