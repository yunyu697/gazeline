package com.yunyu697.gazeline;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.SystemClock;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;

/**
 * The real outer HUD: head-anchored screen-space layer drawn twice (one copy
 * per eye). Angles come from {@link HudSpec}; this class only converts degrees
 * to pixels with the eye's 90° horizontal tangent projection. States S1–S3 plus
 * the recenter mask live here.
 */
final class HudView extends View {

    interface Listener {
        void onLook(float dyawDeg, float dpitchDeg);

        void onRecenterTap();
    }

    static final class Frame {
        String title = "";
        String format = "";
        String subtitle = "";
        String status = "";
        long statusUntil;
        boolean playing = true;
        boolean subtitleOn;
        long positionMs;
        long durationMs;
        GazeController.Readout gaze;
        float hudAlpha = 1f;
        float wakeAlpha = 0f;
        float maskAlpha = 0f;
        float flashAlpha = 0f;
    }

    private final Frame frame = new Frame();

    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint arcPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint maskPaint = new Paint();
    private final Path path = new Path();
    private final RectF rect = new RectF();
    private final RectF rect2 = new RectF();

    private final Typeface regular = Typeface.create("sans-serif", Typeface.NORMAL);
    private final Typeface medium = Typeface.create("sans-serif-medium", Typeface.NORMAL);
    private final Typeface numbers = Typeface.create("sans-serif-condensed", Typeface.NORMAL);

    private Listener listener;
    private final GestureDetector gestures;

    private float eyeCx;
    private float eyeCy;
    private float halfW;
    private float ppd;

    private long lastSignature;

    private static final GazeController.Readout EMPTY = new GazeController.Readout();

    HudView(Context c) {
        super(c);
        setLayerType(LAYER_TYPE_HARDWARE, null);
        textPaint.setTypeface(regular);
        textPaint.setStyle(Paint.Style.FILL);
        strokePaint.setStyle(Paint.Style.STROKE);
        strokePaint.setStrokeWidth(1.2f);
        arcPaint.setStyle(Paint.Style.STROKE);
        arcPaint.setStrokeWidth(3.2f);
        gestures = new GestureDetector(c, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDown(MotionEvent e) {
                return true;
            }

            @Override
            public boolean onScroll(MotionEvent e1, MotionEvent e2, float dx, float dy) {
                if (listener != null) {
                    listener.onLook(-dx / Math.max(1f, ppd), dy / Math.max(1f, ppd));
                }
                return true;
            }

            @Override
            public boolean onDoubleTap(MotionEvent e) {
                if (listener != null) {
                    listener.onRecenterTap();
                }
                return true;
            }
        });
    }

    void setListener(Listener l) {
        listener = l;
    }

    void setFrame(Frame f, long now) {
        copyFrame(f);
        long sig = signature(f);
        if (sig != lastSignature) {
            lastSignature = sig;
            postInvalidateOnAnimation();
        }
    }

    private void copyFrame(Frame f) {
        frame.title = f.title;
        frame.format = f.format;
        frame.subtitle = f.subtitle;
        frame.status = f.status;
        frame.statusUntil = f.statusUntil;
        frame.playing = f.playing;
        frame.subtitleOn = f.subtitleOn;
        frame.positionMs = f.positionMs;
        frame.durationMs = f.durationMs;
        frame.gaze = f.gaze;
        frame.hudAlpha = f.hudAlpha;
        frame.wakeAlpha = f.wakeAlpha;
        frame.maskAlpha = f.maskAlpha;
        frame.flashAlpha = f.flashAlpha;
    }

    private long signature(Frame f) {
        long sig = 0;
        sig = sig * 31 + (f.title == null ? 0 : f.title.hashCode());
        sig = sig * 31 + (f.format == null ? 0 : f.format.hashCode());
        sig = sig * 31 + (f.subtitle == null ? 0 : f.subtitle.hashCode());
        sig = sig * 31 + (f.status == null ? 0 : f.status.hashCode());
        sig = sig * 31 + f.statusUntil;
        sig = sig * 31 + (f.playing ? 1 : 0);
        sig = sig * 31 + (f.subtitleOn ? 1 : 0);
        sig = sig * 31 + (f.positionMs / 400);
        sig = sig * 31 + (f.durationMs / 1000);
        sig = sig * 31 + (int) (f.hudAlpha * 500);
        sig = sig * 31 + (int) (f.wakeAlpha * 500);
        sig = sig * 31 + (int) (f.maskAlpha * 500);
        sig = sig * 31 + (int) (f.flashAlpha * 500);
        GazeController.Readout g = f.gaze;
        if (g != null) {
            sig = sig * 31 + (g.focus == null ? 0 : g.focus.id.ordinal() + 1);
            sig = sig * 31 + (g.cancelling == null ? 0 : g.cancelling.id.ordinal() + 1);
            sig = sig * 31 + (int) (g.progress * 1000);
            sig = sig * 31 + (int) (g.cancelProgress * 1000);
            sig = sig * 31 + (int) (g.relYaw * 10);
            sig = sig * 31 + (int) (g.relPitch * 10);
            sig = sig * 31 + (g.charging ? 1 : 0);
            sig = sig * 31 + (g.ready ? 1 : 0);
        }
        return sig;
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        ppd = Math.max(1f, (w / 2f) / 90f);
    }

    @Override
    protected void onDraw(Canvas c) {
        super.onDraw(c);
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) {
            return;
        }
        halfW = w / 4f;

        if (frame.maskAlpha > 0f) {
            maskPaint.setColor(0xFF04070C);
            maskPaint.setAlpha((int) (Math.min(1f, frame.maskAlpha) * 255));
            c.drawRect(0, 0, w, h, maskPaint);
        }

        for (int eye = 0; eye < 2; eye++) {
            eyeCx = halfW + eye * halfW * 2f;
            eyeCy = h / 2f;
            drawEye(c);
        }
    }

    private void drawEye(Canvas c) {
        float a = frame.hudAlpha;
        GazeController.Readout g = frame.gaze;
        if (g == null) {
            g = EMPTY;
        }
        float relYaw = g.relYaw;
        float relPitch = g.relPitch;

        if (frame.flashAlpha > 0f) {
            drawFlash(c, frame.flashAlpha);
        }

        if (a > 0.01f) {
            drawTopStatus(c, relYaw, relPitch, a);
            if (frame.subtitleOn && frame.subtitle.length() > 0 && !g.charging) {
                drawSubtitle(c, relYaw, relPitch, a);
            }
            drawTimeline(c, relYaw, relPitch, a);
            for (HudSpec.Target t : HudSpec.ROW) {
                drawTarget(c, t, relYaw, relPitch, g, a);
            }
            drawTarget(c, HudSpec.RECENTER, relYaw, relPitch, g, a);
            drawReticle(c, a);
            drawGazeText(c, relYaw, relPitch, g, a);
        }

        if (frame.wakeAlpha > 0.01f) {
            drawMicroProgress(c, frame.wakeAlpha);
            drawWakeBar(c, relYaw, relPitch, frame.wakeAlpha);
        }

        if (frame.status.length() > 0 && SystemClock.elapsedRealtime() < frame.statusUntil
                && !g.charging) {
            drawStatus(c, relYaw, relPitch, a);
        }
    }

    // ------------------------------------------------------------- angle mapping

    private float sx(float yawDeg) {
        return eyeCx + (float) Math.tan(Math.toRadians(yawDeg)) * halfW;
    }

    private float sy(float pitchDeg) {
        return eyeCy - (float) Math.tan(Math.toRadians(pitchDeg)) * halfW;
    }

    private boolean onScreen(float yawDeg, float pitchDeg) {
        return Math.abs(yawDeg) < 72f && Math.abs(pitchDeg) < 60f;
    }

    private RectF angularRect(float yaw, float pitch, float wDeg, float hDeg, RectF out) {
        out.set(sx(yaw - wDeg * 0.5f), sy(pitch + hDeg * 0.5f),
                sx(yaw + wDeg * 0.5f), sy(pitch - hDeg * 0.5f));
        return out;
    }

    private float angularRadius(float diameterDeg, float yaw, float pitch) {
        float r = diameterDeg * 0.5f;
        float rx = halfW * (float) (Math.tan(Math.toRadians(yaw + r)) - Math.tan(Math.toRadians(yaw - r))) * 0.5f;
        float ry = halfW * (float) (Math.tan(Math.toRadians(pitch + r)) - Math.tan(Math.toRadians(pitch - r))) * 0.5f;
        return (Math.abs(rx) + Math.abs(ry)) * 0.5f;
    }

    private float fontPx(float deg) {
        return (float) (2.0 * halfW * Math.tan(Math.toRadians(deg * 0.5f)));
    }

    /**
     * Paint.setAlpha replaces the alpha packed in the colour, so tint() multiplies
     * the colour's own alpha with the layer alpha instead. Without this, any
     * translucent constant (18% track, 17% border) comes out fully opaque.
     */
    private static void tint(Paint p, int color, int alpha255) {
        p.setColor(color);
        int a = (int) ((color >>> 24) * alpha255 / 255f);
        p.setAlpha(Math.max(0, Math.min(255, a)));
    }

    // ------------------------------------------------------------- elements

    private void drawTopStatus(Canvas c, float relYaw, float relPitch, float alpha) {
        float p = HudSpec.TOP_PITCH;
        float yaw = -18f - relYaw;
        float pitch = p - relPitch;
        if (!onScreen(yaw, pitch)) {
            return;
        }
        angularRect(yaw, pitch, 30f, 5.6f, rect);
        drawPanel(c, rect, 10f, 0x66080C12, 0x24FFFFFF, alpha);

        textPaint.setTypeface(medium);
        textPaint.setTextSize(fontPx(1.55f));
        tint(textPaint, HudSpec.C_TEXT, (int) (alpha * 255));
        textPaint.setTextAlign(Paint.Align.LEFT);
        String title = ellipsize(frame.title, textPaint, rect.width() - 16f);
        float tx = rect.left + 8f;
        float ty = rect.top + 8f + textPaint.getTextSize();
        c.drawText(title, tx, ty, textPaint);

        textPaint.setTypeface(regular);
        textPaint.setTextSize(fontPx(1.3f));
        tint(textPaint, HudSpec.C_TEXT2, (int) (alpha * 225));
        c.drawText(frame.format, tx, rect.bottom - 8f, textPaint);

        yaw = 18f - relYaw;
        pitch = p - relPitch;
        if (!onScreen(yaw, pitch)) {
            return;
        }
        angularRect(yaw, pitch, 30f, 5.6f, rect);
        drawPanel(c, rect, 10f, 0x66080C12, 0x24FFFFFF, alpha);
        textPaint.setTypeface(regular);
        textPaint.setTextSize(fontPx(1.35f));
        tint(textPaint, HudSpec.C_TEXT2, (int) (alpha * 230));
        textPaint.setTextAlign(Paint.Align.LEFT);
        c.drawText("本地播放", rect.left + 8f, rect.top + 8f + textPaint.getTextSize(), textPaint);
        textPaint.setTypeface(medium);
        tint(textPaint, HudSpec.C_TEXT, (int) (alpha * 255));
        textPaint.setTextAlign(Paint.Align.RIGHT);
        c.drawText(percent() + "%", rect.right - 8f, rect.top + 8f + textPaint.getTextSize(), textPaint);
    }

    private void drawSubtitle(Canvas c, float relYaw, float relPitch, float alpha) {
        float pitch = HudSpec.SUB_PITCH - relPitch;
        if (!onScreen(-relYaw, pitch)) {
            return;
        }
        textPaint.setTypeface(regular);
        textPaint.setTextSize(fontPx(2.1f));
        tint(textPaint, 0xFFF4F7FF, (int) (alpha * 235));
        textPaint.setTextAlign(Paint.Align.CENTER);
        c.drawText(frame.subtitle, eyeCx, sy(pitch), textPaint);
    }

    private void drawTimeline(Canvas c, float relYaw, float relPitch, float alpha) {
        float yaw = -relYaw;
        float pitch = HudSpec.BAR_PITCH - relPitch;
        if (!onScreen(yaw, pitch)) {
            return;
        }
        angularRect(yaw, pitch, HudSpec.BAR_WIDTH, 0.42f, rect);
        float trackH = Math.max(3f, rect.height());
        float cy = rect.centerY();

        tint(fillPaint, 0x30FFFFFF, (int) (alpha * 255));
        rect2.set(rect.left, cy - trackH * 0.5f, rect.right, cy + trackH * 0.5f);
        c.drawRoundRect(rect2, trackH * 0.5f, trackH * 0.5f, fillPaint);

        float progress = durationProgress();
        float fillRight = rect.left + rect.width() * progress;
        tint(fillPaint, HudSpec.C_AMBER, (int) (alpha * 210));
        rect2.set(rect.left, cy - trackH * 0.5f, Math.max(rect.left + trackH, fillRight),
                cy + trackH * 0.5f);
        c.drawRoundRect(rect2, trackH * 0.5f, trackH * 0.5f, fillPaint);

        tint(fillPaint, 0xFFFFFFFF, (int) (alpha * 235));
        c.drawCircle(fillRight, cy, Math.max(3.5f, trackH * 1.1f), fillPaint);

        textPaint.setTypeface(numbers);
        textPaint.setTextSize(fontPx(1.35f));
        tint(textPaint, 0xFFCFDAEA, (int) (alpha * 230));
        textPaint.setTextAlign(Paint.Align.LEFT);
        c.drawText(timeOf(frame.positionMs), rect.left, rect.top - 6f, textPaint);
        textPaint.setTextAlign(Paint.Align.RIGHT);
        c.drawText("-" + timeOf(Math.max(0, frame.durationMs - frame.positionMs)),
                rect.right, rect.top - 6f, textPaint);
    }

    private void drawTarget(Canvas c, HudSpec.Target t, float relYaw, float relPitch,
                            GazeController.Readout g, float alpha) {
        float yaw = t.yaw - relYaw;
        float pitch = t.pitch - relPitch;
        if (!onScreen(yaw, pitch)) {
            return;
        }
        float cx = sx(yaw);
        float cy = sy(pitch);
        float radius = angularRadius(t.diameterDeg, yaw, pitch);

        boolean focused = g.focus == t;
        boolean cancelling = g.cancelling == t;
        float cancellingProgress = cancelling ? g.cancelProgress : 0f;
        boolean ready = focused && g.ready;
        float scale = focused ? (ready ? 1.08f : 1.06f) : 1f;
        float r = radius * scale;

        if (focused) {
            tint(fillPaint, 0x24FFB454, (int) (alpha * 255));
            c.drawCircle(cx, cy, r + radius * 0.22f, fillPaint);
        }

        if (t.id == HudSpec.Id.RECENTER) {
            drawRecenter(c, cx, cy, r, focused, ready, alpha);
            if (focused) {
                drawProgressArc(c, cx, cy, r + r * 0.16f, ready ? g.progress : g.progress,
                        ready ? HudSpec.C_GREEN : HudSpec.C_AMBER, alpha);
            } else if (cancelling) {
                drawProgressArc(c, cx, cy, r + r * 0.16f, cancellingProgress,
                        HudSpec.C_AMBER, alpha * 0.7f);
            }
            return;
        }

        int border = focused ? (ready ? HudSpec.C_GREEN : HudSpec.C_AMBER) : HudSpec.C_BUTTON_BORDER;
        boolean main = t.id == HudSpec.Id.PLAY;
        tint(fillPaint, main ? 0xFFFFB454 : HudSpec.C_BUTTON_FILL, (int) (alpha * 255));
        c.drawCircle(cx, cy, r, fillPaint);
        tint(strokePaint, border, (int) (alpha * 255));
        strokePaint.setStyle(Paint.Style.STROKE);
        strokePaint.setStrokeWidth(focused ? 2.4f : 1.3f);
        c.drawCircle(cx, cy, r, strokePaint);

        int icon = main ? 0xFF1A1204 : HudSpec.C_TEXT;
        drawIcon(c, t.id, cx, cy, r, icon, alpha);

        if (focused || cancelling) {
            float progress = focused ? g.progress : cancellingProgress;
            if (progress > 0.005f || focused) {
                drawProgressArc(c, cx, cy, r + r * 0.16f, progress,
                        ready ? HudSpec.C_GREEN : HudSpec.C_AMBER, alpha);
            }
        }
    }

    private void drawProgressArc(Canvas c, float cx, float cy, float radius, float progress,
                                 int color, float alpha) {
        if (progress <= 0.002f) {
            return;
        }
        tint(arcPaint, HudSpec.C_RING_TRACK, (int) (alpha * 230));
        arcPaint.setStrokeWidth(Math.max(2.6f, radius * 0.075f));
        c.drawCircle(cx, cy, radius, arcPaint);

        rect.set(cx - radius, cy - radius, cx + radius, cy + radius);
        tint(arcPaint, color, (int) (alpha * 255));
        arcPaint.setStrokeWidth(Math.max(3.2f, radius * 0.11f));
        c.drawArc(rect, -90f, Math.max(0.5f, 360f * Math.min(1f, progress)), false, arcPaint);
    }

    private void drawRecenter(Canvas c, float cx, float cy, float r, boolean focused,
                              boolean ready, float alpha) {
        tint(fillPaint, HudSpec.C_BUTTON_FILL, (int) (alpha * 255));
        c.drawCircle(cx, cy, r, fillPaint);

        strokePaint.setStyle(Paint.Style.STROKE);
        strokePaint.setStrokeWidth(1.4f);
        tint(strokePaint, focused ? (ready ? HudSpec.C_GREEN : HudSpec.C_AMBER) : 0x47FFFFFF,
                (int) (alpha * 255));
        strokePaint.setPathEffect(new DashPathEffect(new float[]{r * 0.22f, r * 0.14f}, 0f));
        c.drawCircle(cx, cy, r, strokePaint);
        strokePaint.setPathEffect(null);

        float inner = r * 0.58f;
        tint(strokePaint, 0xE6E9F0FF, (int) (alpha * 255));
        strokePaint.setStrokeWidth(Math.max(1.2f, r * 0.09f));
        c.drawCircle(cx, cy, inner, strokePaint);
        for (int i = 0; i < 4; i++) {
            float ang = (float) Math.toRadians(90 * i);
            float ix = (float) Math.cos(ang);
            float iy = (float) Math.sin(ang);
            c.drawLine(cx + ix * inner * 0.78f, cy + iy * inner * 0.78f,
                    cx + ix * inner * 1.18f, cy + iy * inner * 1.18f, strokePaint);
        }
        tint(fillPaint, 0xFFFFFFFF, (int) (alpha * 235));
        c.drawCircle(cx, cy, Math.max(1.6f, r * 0.13f), fillPaint);

        textPaint.setTypeface(regular);
        textPaint.setTextSize(fontPx(1.25f));
        tint(textPaint, focused ? 0xFFFFE0B0 : 0x8AFFFFFF, (int) (alpha * 220));
        textPaint.setTextAlign(Paint.Align.CENTER);
        c.drawText("回中", cx, cy + r + fontPx(1.45f), textPaint);
    }

    private void drawIcon(Canvas c, HudSpec.Id id, float cx, float cy, float r, int color, float alpha) {
        tint(fillPaint, color, (int) (alpha * 255));
        tint(strokePaint, color, (int) (alpha * 255));
        strokePaint.setStyle(Paint.Style.STROKE);
        float u = r * 0.5f;
        switch (id) {
            case PLAY:
                if (frame.playing) {
                    float bw = u * 0.30f;
                    float bh = u * 0.86f;
                    rect.set(cx - bw * 1.55f, cy - bh, cx - bw * 0.65f, cy + bh);
                    c.drawRoundRect(rect, bw * 0.2f, bw * 0.2f, fillPaint);
                    rect.set(cx + bw * 0.65f, cy - bh, cx + bw * 1.55f, cy + bh);
                    c.drawRoundRect(rect, bw * 0.2f, bw * 0.2f, fillPaint);
                } else {
                    path.reset();
                    path.moveTo(cx - u * 0.42f, cy - u * 0.78f);
                    path.lineTo(cx + u * 0.68f, cy);
                    path.lineTo(cx - u * 0.42f, cy + u * 0.78f);
                    path.close();
                    c.drawPath(path, fillPaint);
                }
                break;
            case SUBTITLE: {
                strokePaint.setStrokeWidth(Math.max(1.4f, r * 0.075f));
                rect.set(cx - u * 0.94f, cy - u * 0.58f, cx + u * 0.94f, cy + u * 0.58f);
                c.drawRoundRect(rect, u * 0.18f, u * 0.18f, strokePaint);
                strokePaint.setStrokeWidth(Math.max(1.2f, r * 0.05f));
                c.drawLine(cx - u * 0.42f, cy - u * 0.16f, cx + u * 0.42f, cy - u * 0.16f, strokePaint);
                c.drawLine(cx - u * 0.42f, cy + u * 0.22f, cx + u * 0.16f, cy + u * 0.22f, strokePaint);
                break;
            }
            case REWIND:
            case FORWARD: {
                boolean back = id == HudSpec.Id.REWIND;
                float rr = u * 0.62f;
                rect.set(cx - rr, cy - rr, cx + rr, cy + rr);
                tint(arcPaint, color, (int) (alpha * 255));
                arcPaint.setStrokeWidth(Math.max(1.5f, r * 0.085f));
                c.drawArc(rect, back ? 205f : -25f, back ? 290f : -290f, false, arcPaint);
                float tipAngle = back ? 205f + 290f : -25f - 290f;
                float ax = (float) Math.cos(Math.toRadians(tipAngle));
                float ay = (float) Math.sin(Math.toRadians(tipAngle));
                path.reset();
                path.moveTo(cx + ax * rr * 1.28f, cy + ay * rr * 1.28f);
                path.lineTo(cx + (ax * 0.72f - ay * 0.34f) * rr,
                        cy + (ay * 0.72f + ax * 0.34f) * rr);
                path.lineTo(cx + (ax * 0.72f + ay * 0.34f) * rr,
                        cy + (ay * 0.72f - ax * 0.34f) * rr);
                path.close();
                c.drawPath(path, fillPaint);
                textPaint.setTypeface(numbers);
                textPaint.setTextSize(fontPx(1.15f));
                tint(textPaint, color, (int) (alpha * 255));
                textPaint.setTextAlign(Paint.Align.CENTER);
                c.drawText("10", cx, cy + r * 0.56f, textPaint);
                break;
            }
            case MENU: {
                float dot = Math.max(1.8f, u * 0.17f);
                tint(fillPaint, color, (int) (alpha * 255));
                c.drawCircle(cx - u * 0.58f, cy, dot, fillPaint);
                c.drawCircle(cx, cy, dot, fillPaint);
                c.drawCircle(cx + u * 0.58f, cy, dot, fillPaint);
                break;
            }
            default:
                break;
        }
    }

    private void drawReticle(Canvas c, float alpha) {
        tint(strokePaint, 0xCCFFFFFF, (int) (alpha * 235));
        strokePaint.setStrokeWidth(1.2f);
        strokePaint.setStyle(Paint.Style.STROKE);
        c.drawCircle(eyeCx, eyeCy, fontPx(1.9f) * 0.5f, strokePaint);
        tint(fillPaint, 0xFFFFFFFF, (int) (alpha * 245));
        c.drawCircle(eyeCx, eyeCy, Math.max(1.3f, fontPx(0.45f)), fillPaint);
    }

    private void drawFlash(Canvas c, float alpha) {
        float len = fontPx(6f);
        float thick = Math.max(1.6f, fontPx(0.4f));
        tint(strokePaint, 0xFFFFFFFF, (int) (alpha * 220));
        strokePaint.setStrokeWidth(thick);
        strokePaint.setStyle(Paint.Style.STROKE);
        c.drawLine(eyeCx - len, eyeCy, eyeCx + len, eyeCy, strokePaint);
        c.drawLine(eyeCx, eyeCy - len, eyeCx, eyeCy + len, strokePaint);
    }

    private void drawGazeText(Canvas c, float relYaw, float relPitch,
                              GazeController.Readout g, float alpha) {
        HudSpec.Target target = g.focus != null ? g.focus : g.cancelling;
        if (target == null || target.id == HudSpec.Id.WAKE) {
            return;
        }
        String action = actionLabel(target);
        if (target.id == HudSpec.Id.RECENTER) {
            if (g.ready) {
                drawPillText(c, "待发 · " + oneDecimal(Math.max(0f, remainingMs(g) / 1000f)) + " 秒后回中",
                        target, relYaw, relPitch, 0xFFB0FFE0, alpha);
            } else {
                drawPillText(c, "1.2 秒充能 · " + action, target, relYaw, relPitch,
                        HudSpec.C_AMBER, alpha);
            }
        } else if (g.charging) {
            // main countdown text is centered; keep the pill minimal
        } else {
            drawPillText(c, "停留 " + oneDecimal(target.levelMs / 1000f) + " 秒 · " + action,
                    target, relYaw, relPitch, HudSpec.C_AMBER, alpha);
        }

        if (g.charging) {
            String line;
            if (g.ready) {
                line = "待发 · " + oneDecimal(Math.max(0f, remainingMs(g) / 1000f)) + " 秒后回中";
            } else {
                line = "继续注视 " + oneDecimal(g.remainingSec) + " 秒 · " + action;
            }
            textPaint.setTypeface(medium);
            textPaint.setTextSize(fontPx(2.2f));
            tint(textPaint, 0xFFFFE6C2, (int) (alpha * 245));
            textPaint.setTextAlign(Paint.Align.CENTER);
            c.drawText(line, eyeCx, sy(-6.2f - relPitch), textPaint);
            textPaint.setTypeface(regular);
            textPaint.setTextSize(fontPx(1.3f));
            tint(textPaint, HudSpec.C_TEXT2, (int) (alpha * 210));
            c.drawText("环量的是剩余等待，不是按了多少", eyeCx, sy(-8.6f - relPitch), textPaint);

            textPaint.setTypeface(regular);
            textPaint.setTextSize(fontPx(1.25f));
            tint(textPaint, 0xFFB7C3D8, (int) (alpha * 210));
            c.drawText("移开视线 / 按任意键　即取消", eyeCx, sy(-30.2f - relPitch), textPaint);
        }
    }

    private void drawPillText(Canvas c, String text, HudSpec.Target target,
                              float relYaw, float relPitch, int color, float alpha) {
        float yaw = target.yaw - relYaw;
        float pitch = target.pitch - relPitch - target.diameterDeg * 0.5f - 2.6f;
        if (!onScreen(yaw, pitch)) {
            return;
        }
        textPaint.setTypeface(regular);
        textPaint.setTextSize(fontPx(1.35f));
        float tw = textPaint.measureText(text);
        float ph = fontPx(2.3f);
        float cx = sx(yaw);
        float cy = sy(pitch);
        cx = Math.max(eyeCx - halfW + tw * 0.5f + 12f,
                Math.min(eyeCx + halfW - tw * 0.5f - 12f, cx));
        rect.set(cx - tw * 0.5f - 12f, cy - ph * 0.5f, cx + tw * 0.5f + 12f, cy + ph * 0.5f);
        tint(fillPaint, 0xCC000000, (int) (alpha * 235));
        c.drawRoundRect(rect, ph * 0.5f, ph * 0.5f, fillPaint);
        tint(strokePaint, color, (int) (alpha * 160));
        strokePaint.setStrokeWidth(1.2f);
        strokePaint.setStyle(Paint.Style.STROKE);
        c.drawRoundRect(rect, ph * 0.5f, ph * 0.5f, strokePaint);
        tint(textPaint, color, (int) (alpha * 245));
        textPaint.setTextAlign(Paint.Align.CENTER);
        c.drawText(text, cx, cy + textPaint.getTextSize() * 0.36f, textPaint);
    }

    private void drawStatus(Canvas c, float relYaw, float relPitch, float alpha) {
        textPaint.setTypeface(regular);
        textPaint.setTextSize(fontPx(1.6f));
        float tw = textPaint.measureText(frame.status);
        float ph = fontPx(2.8f);
        float cy = sy(-2.6f - relPitch);
        float cx = eyeCx;
        rect.set(cx - tw * 0.5f - 14f, cy - ph * 0.5f, cx + tw * 0.5f + 14f, cy + ph * 0.5f);
        tint(fillPaint, 0xE60B1018, (int) (alpha * 240));
        c.drawRoundRect(rect, ph * 0.5f, ph * 0.5f, fillPaint);
        tint(strokePaint, HudSpec.C_BLUE, (int) (alpha * 180));
        strokePaint.setStrokeWidth(1.2f);
        strokePaint.setStyle(Paint.Style.STROKE);
        c.drawRoundRect(rect, ph * 0.5f, ph * 0.5f, strokePaint);
        tint(textPaint, 0xFFDCE7FF, (int) (alpha * 245));
        textPaint.setTextAlign(Paint.Align.CENTER);
        c.drawText(frame.status, cx, cy + textPaint.getTextSize() * 0.34f, textPaint);
    }

    private void drawWakeBar(Canvas c, float relYaw, float relPitch, float alpha) {
        float yaw = -relYaw;
        float pitch = HudSpec.WAKE_PITCH - relPitch;
        if (!onScreen(yaw, pitch)) {
            return;
        }
        angularRect(yaw, pitch, HudSpec.WAKE_WIDTH, HudSpec.WAKE_HEIGHT, rect);
        tint(fillPaint, 0x1FFFFFFF, (int) (alpha * 255));
        c.drawRoundRect(rect, rect.height() * 0.5f, rect.height() * 0.5f, fillPaint);
        tint(strokePaint, 0x47FFFFFF, (int) (alpha * 160));
        strokePaint.setStrokeWidth(1f);
        strokePaint.setStyle(Paint.Style.STROKE);
        c.drawRoundRect(rect, rect.height() * 0.5f, rect.height() * 0.5f, strokePaint);

        textPaint.setTypeface(regular);
        textPaint.setTextSize(fontPx(1.05f));
        tint(textPaint, 0x66FFFFFF, (int) (alpha * 190));
        textPaint.setTextAlign(Paint.Align.CENTER);
        c.drawText("轻看此处唤回控件", rect.centerX(), rect.centerY() + textPaint.getTextSize() * 0.34f, textPaint);
    }

    private void drawMicroProgress(Canvas c, float alpha) {
        int h = getHeight();
        float x0 = eyeCx - halfW;
        float x1 = eyeCx + halfW;
        float y = h - 2f;
        tint(fillPaint, 0x24FFFFFF, (int) (Math.min(1f, alpha) * 255));
        c.drawRect(x0, y, x1, h, fillPaint);
        tint(fillPaint, 0xD9FFB454, (int) (Math.min(1f, alpha) * 255));
        c.drawRect(x0, y, x0 + (x1 - x0) * durationProgress(), h, fillPaint);
    }

    private void drawPanel(Canvas c, RectF r, float radius, int fill, int border, float alpha) {
        tint(fillPaint, fill, (int) (alpha * 255));
        c.drawRoundRect(r, radius, radius, fillPaint);
        tint(strokePaint, border, (int) (alpha * 210));
        strokePaint.setStrokeWidth(1.1f);
        strokePaint.setStyle(Paint.Style.STROKE);
        c.drawRoundRect(r, radius, radius, strokePaint);
    }

    // ------------------------------------------------------------- helpers

    private String actionLabel(HudSpec.Target t) {
        switch (t.id) {
            case PLAY:
                return frame.playing ? "暂停播放" : "继续播放";
            case SUBTITLE:
                return frame.subtitleOn ? "关闭字幕" : "开启字幕";
            case RECENTER:
                return "视角回中";
            default:
                return t.id.label();
        }
    }

    private float remainingMs(GazeController.Readout g) {
        if (g.focus == null) {
            return 0f;
        }
        return g.focus.levelMs * (1f - g.progress);
    }

    private float durationProgress() {
        if (frame.durationMs <= 0) {
            return 0f;
        }
        return Math.max(0f, Math.min(1f, (float) frame.positionMs / frame.durationMs));
    }

    private String percent() {
        return String.format("%d", (int) (durationProgress() * 100));
    }

    private static String oneDecimal(float v) {
        return String.format("%.1f", v);
    }

    private static String timeOf(long ms) {
        long total = Math.max(0, ms) / 1000L;
        long h = total / 3600L;
        long m = (total / 60L) % 60L;
        long s = total % 60L;
        if (h > 0) {
            return String.format("%02d:%02d:%02d", h, m, s);
        }
        return String.format("%02d:%02d", m, s);
    }

    private static String ellipsize(String s, Paint p, float maxWidth) {
        if (s == null) {
            return "";
        }
        if (p.measureText(s) <= maxWidth) {
            return s;
        }
        String ell = "…";
        int n = s.length();
        while (n > 1 && p.measureText(s.substring(0, n) + ell) > maxWidth) {
            n--;
        }
        return s.substring(0, n) + ell;
    }

    // ------------------------------------------------------------- touch

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        return gestures.onTouchEvent(e);
    }
}
