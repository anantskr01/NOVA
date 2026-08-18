package com.aircontrol;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.View;

/** Lightweight animated NOVA HUD; no external graphics or network required. */
public final class NovaHudView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF arc = new RectF();
    private long start = SystemClock.uptimeMillis();
    private String state = "ONLINE";

    public NovaHudView(Context context, AttributeSet attrs) { super(context, attrs); init(); }
    public NovaHudView(Context context) { super(context); init(); }

    private void init() {
        setLayerType(View.LAYER_TYPE_SOFTWARE, null);
        paint.setStrokeWidth(2f);
        paint.setStyle(Paint.Style.STROKE);
    }

    public void setState(String value) {
        state = value == null ? "ONLINE" : value.toUpperCase();
        invalidate();
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float cx = getWidth() / 2f;
        float cy = getHeight() / 2f;
        float r = Math.min(getWidth(), getHeight()) * 0.30f;
        float t = (SystemClock.uptimeMillis() - start) / 1000f;
        float pulse = 1f + 0.035f * (float)Math.sin(t * 3.0);

        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(3f);
        paint.setShadowLayer(18f, 0, 0, 0x668DEBFF);
        paint.setColor(0xFF8DEBFF);
        canvas.drawCircle(cx, cy, r * pulse, paint);
        paint.setShadowLayer(0, 0, 0, 0);

        paint.setStrokeWidth(1.5f);
        paint.setColor(0x665FA9B8);
        canvas.drawCircle(cx, cy, r * 1.22f, paint);
        canvas.drawCircle(cx, cy, r * 1.42f, paint);

        arc.set(cx-r*1.18f, cy-r*1.18f, cx+r*1.18f, cy+r*1.18f);
        paint.setColor(0xFF8DEBFF);
        canvas.drawArc(arc, (t * 45f) % 360f, 72f, false, paint);
        canvas.drawArc(arc, ((-t * 35f) + 180f) % 360f, 42f, false, paint);

        paint.setStyle(Paint.Style.FILL);
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setTextSize(Math.max(18f, r * 0.22f));
        paint.setColor(0xFFBCEEFF);
        canvas.drawText("NOVA", cx, cy + 6, paint);
        paint.setTextSize(Math.max(9f, r * 0.09f));
        paint.setColor(0xFF6F9DA8);
        canvas.drawText(state, cx, cy + 27, paint);

        postInvalidateOnAnimation();
    }
}
