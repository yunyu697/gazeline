package com.yunyu697.gazeline;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;

/** Head-locked stats overlay plus drag-to-look / double-tap-to-recenter input. */
final class PlayerHudView extends View {

    interface Listener {
        void onLook(float dyawDeg, float dpitchDeg);

        void onRecenter();
    }

    private Listener listener;
    private String[] lines = new String[0];

    private final Paint panelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF panel = new RectF();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final GestureDetector gestures;

    private boolean panelVisible = true;
    private float density;
    private float ppd = 1f;

    private final Runnable hideTask = new Runnable() {
        @Override
        public void run() {
            panelVisible = false;
            invalidate();
        }
    };

    PlayerHudView(Context c) {
        super(c);
        density = getResources().getDisplayMetrics().density;
        panelPaint.setColor(0xD90B1018);
        borderPaint.setStyle(Paint.Style.STROKE);
        borderPaint.setStrokeWidth(1f);
        borderPaint.setColor(0x33FFFFFF);
        textPaint.setTypeface(Typeface.MONOSPACE);
        textPaint.setTextSize(11f * getResources().getDisplayMetrics().scaledDensity);
        gestures = new GestureDetector(c, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDown(MotionEvent e) {
                return true;
            }

            @Override
            public boolean onScroll(MotionEvent e1, MotionEvent e2, float dx, float dy) {
                if (listener != null) {
                    listener.onLook(-dx / ppd, dy / ppd);
                }
                return true;
            }

            @Override
            public boolean onSingleTapUp(MotionEvent e) {
                showPanel();
                return true;
            }

            @Override
            public boolean onDoubleTap(MotionEvent e) {
                if (listener != null) {
                    listener.onRecenter();
                }
                showPanel();
                return true;
            }
        });
        handler.postDelayed(hideTask, 5000);
    }

    void setListener(Listener l) {
        listener = l;
    }

    void setLines(String[] newLines) {
        lines = newLines == null ? new String[0] : newLines;
        if (panelVisible) {
            invalidate();
        }
    }

    void showPanel() {
        panelVisible = true;
        invalidate();
        handler.removeCallbacks(hideTask);
        handler.postDelayed(hideTask, 5000);
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        ppd = Math.max(1f, w / 90f);
    }

    @Override
    protected void onDraw(Canvas c) {
        if (!panelVisible || lines.length == 0) {
            return;
        }
        float pad = 12 * density;
        float lineH = 16 * density;
        float width = 0f;
        for (String s : lines) {
            width = Math.max(width, textPaint.measureText(s));
        }
        panel.set(pad, pad, pad + pad * 2 + width, pad + pad * 2 + lineH * lines.length + 4 * density);

        c.drawRoundRect(panel, 10 * density, 10 * density, panelPaint);
        c.drawRoundRect(panel, 10 * density, 10 * density, borderPaint);

        float x = panel.left + pad;
        float y = panel.top + pad + textPaint.getTextSize();
        for (int i = 0; i < lines.length; i++) {
            textPaint.setColor(i == 0 ? 0xFF67E8F9 : (i == 1 ? 0xFFF4F8FF : 0xFFAFC0D8));
            c.drawText(lines[i], x, y, textPaint);
            y += lineH;
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        return gestures.onTouchEvent(e);
    }
}
