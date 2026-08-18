package com.aircontrol;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Handler;
import android.os.Looper;
import android.view.View;

/** Lightweight JARVIS-inspired wake animation. It draws only for a short burst. */
public final class NovaWakeOverlay extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF arc = new RectF();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final long started = System.currentTimeMillis();
    private String state = "NOVA ONLINE";
    private Runnable finish;

    public NovaWakeOverlay(Context context) {
        super(context);
        paint.setTypeface(android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.NORMAL));
        setLayerType(View.LAYER_TYPE_SOFTWARE, null);
    }

    public void showFor(long durationMs, Runnable onFinish) {
        finish = onFinish;
        handler.postDelayed(() -> {
            if (finish != null) finish.run();
        }, durationMs);
        postInvalidateOnAnimation();
    }

    @Override protected void onDetachedFromWindow() {
        handler.removeCallbacksAndMessages(null);
        super.onDetachedFromWindow();
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float w = getWidth();
        float h = getHeight();
        float cx = w * 0.5f;
        float cy = h * 0.5f;
        float t = (System.currentTimeMillis() - started) / 1000f;
        float progress = Math.min(1f, t / 1.25f);
        float fade = t < 0.85f ? 1f : Math.max(0f, 1f - (t - 0.85f) / 0.55f);

        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0x12000000);
        canvas.drawRect(0, 0, w, h, paint);

        float base = Math.min(w, h) * 0.15f;
        float pulse = base * (1f + 0.10f * (float)Math.sin(t * 8f));
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(4f);
        paint.setColor(withAlpha(0xFF8DEBFF, fade));
        paint.setShadowLayer(26f, 0, 0, withAlpha(0xFF52DFFF, fade));
        canvas.drawCircle(cx, cy, pulse + progress * base * 0.20f, paint);
        paint.setShadowLayer(0, 0, 0, 0);

        paint.setStrokeWidth(2f);
        arc.set(cx - base * 1.55f, cy - base * 1.55f, cx + base * 1.55f, cy + base * 1.55f);
        canvas.drawArc(arc, t * 150f, 105f, false, paint);
        canvas.drawArc(arc, -t * 210f + 160f, 65f, false, paint);

        paint.setStyle(Paint.Style.FILL);
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setTextSize(Math.max(18f, base * 0.42f));
        paint.setColor(withAlpha(0xFFD7F8FF, fade));
        canvas.drawText(state, cx, cy + base * 2.15f, paint);

        paint.setTextSize(Math.max(10f, base * 0.20f));
        paint.setColor(withAlpha(0xFF7CBBC8, fade));
        canvas.drawText("VOICE LINK ESTABLISHED", cx, cy + base * 2.55f, paint);

        if (fade > 0f) postInvalidateOnAnimation();
    }

    private int withAlpha(int color, float alpha) {
        int a = Math.max(0, Math.min(255, (int)(alpha * 255f)));
        return (color & 0x00FFFFFF) | (a << 24);
    }
}
