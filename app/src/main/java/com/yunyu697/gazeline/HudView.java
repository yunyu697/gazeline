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
        /** 0–1 while the downward glance is being held; drawn at the screen edge. */
        float wakeProgress = 0f;
        float maskAlpha = 0f;
        float flashAlpha = 0f;
        /** Non-zero while the recenter grace countdown runs (seconds left). */
        float recenterCountdown = 0f;

        // in-VR file browser rows (overlay 3)
        HudSpec.Target[] browserTargets;
        String browserPath = "";
        String browserHint = "";
        int browserPage;
        int browserPages;
        boolean browserLoading;
        int browserGen;

        // S4 overlay (0 none, 1 menu, 2 exit confirmation)
        int overlay;
        float overlayAlpha;
        GazeController.Readout overlayGaze;
        String overlayHint = "";
        /** Rows of the menu page on screen, and which page that is. */
        HudSpec.Target[] menuTargets = HudSpec.MENU_ROWS_P1;
        int menuPage;
        int menuPages = 2;
        String audioValue = "无";
        String audioText = "音轨加载中";
        String subtitleText = "未加载 · 手机端可选";
        float speed = 1f;
        String brightnessText = "60%";
        boolean autoBright;
        String wakeText = "标准";
        String volumeText = "";
        String serverText = "";
        String subStyleText = "";
        float subtitleScale = 1f;
        boolean subtitleOutline = true;
        boolean defaultFocusHint;
        boolean menuWarn;
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
    private float pxPerDeg = 10f;

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
        frame.recenterCountdown = f.recenterCountdown;
        frame.browserTargets = f.browserTargets;
        frame.browserPath = f.browserPath;
        frame.browserHint = f.browserHint;
        frame.browserPage = f.browserPage;
        frame.browserPages = f.browserPages;
        frame.browserLoading = f.browserLoading;
        frame.browserGen = f.browserGen;
        frame.overlay = f.overlay;
        frame.overlayAlpha = f.overlayAlpha;
        frame.overlayGaze = f.overlayGaze;
        frame.overlayHint = f.overlayHint;
        frame.menuTargets = f.menuTargets;
        frame.menuPage = f.menuPage;
        frame.menuPages = f.menuPages;
        frame.audioValue = f.audioValue;
        frame.audioText = f.audioText;
        frame.subtitleText = f.subtitleText;
        frame.speed = f.speed;
        frame.brightnessText = f.brightnessText;
        frame.autoBright = f.autoBright;
        frame.wakeText = f.wakeText;
        frame.volumeText = f.volumeText;
        frame.serverText = f.serverText;
        frame.subStyleText = f.subStyleText;
        frame.subtitleScale = f.subtitleScale;
        frame.subtitleOutline = f.subtitleOutline;
        frame.defaultFocusHint = f.defaultFocusHint;
        frame.menuWarn = f.menuWarn;
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
        sig = sig * 31 + (int) (f.wakeProgress * 200);
        sig = sig * 31 + (int) (f.maskAlpha * 500);
        sig = sig * 31 + (int) (f.flashAlpha * 500);
        sig = sig * 31 + (int) (f.recenterCountdown * 10);
        sig = sig * 31 + f.browserGen;
        sig = sig * 31 + f.overlay;
        sig = sig * 31 + (int) (f.overlayAlpha * 500);
        sig = sig * 31 + (f.overlayHint == null ? 0 : f.overlayHint.hashCode());
        sig = sig * 31 + (f.audioValue == null ? 0 : f.audioValue.hashCode());
        sig = sig * 31 + (f.audioText == null ? 0 : f.audioText.hashCode());
        sig = sig * 31 + (f.subtitleText == null ? 0 : f.subtitleText.hashCode());
        sig = sig * 31 + (int) (f.speed * 100);
        sig = sig * 31 + (f.brightnessText == null ? 0 : f.brightnessText.hashCode());
        sig = sig * 31 + (f.autoBright ? 1 : 0);
        sig = sig * 31 + (f.wakeText == null ? 0 : f.wakeText.hashCode());
        sig = sig * 31 + f.menuPage;
        sig = sig * 31 + (f.volumeText == null ? 0 : f.volumeText.hashCode());
        sig = sig * 31 + (f.serverText == null ? 0 : f.serverText.hashCode());
        sig = sig * 31 + (f.subStyleText == null ? 0 : f.subStyleText.hashCode());
        sig = sig * 31 + (int) (f.subtitleScale * 100);
        sig = sig * 31 + (f.subtitleOutline ? 1 : 0);
        sig = sig * 31 + (f.defaultFocusHint ? 1 : 0);
        sig = sig * 31 + (f.menuWarn ? 1 : 0);
        GazeController.Readout mg = f.overlayGaze;
        if (mg != null) {
            sig = sig * 31 + (mg.focus == null ? 0 : mg.focus.id.ordinal() + 1);
            sig = sig * 31 + (mg.cancelling == null ? 0 : mg.cancelling.id.ordinal() + 1);
            sig = sig * 31 + (int) (mg.progress * 1000);
            sig = sig * 31 + (int) (mg.cancelProgress * 1000);
            sig = sig * 31 + (int) (mg.relYaw * 10);
            sig = sig * 31 + (int) (mg.relPitch * 10);
            sig = sig * 31 + (mg.charging ? 1 : 0);
        }
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
        pxPerDeg = halfW * 0.017453292f;

        if (frame.maskAlpha > 0f) {
            maskPaint.setColor(0xFF04070C);
            maskPaint.setAlpha((int) (Math.min(1f, frame.maskAlpha) * 255));
            c.drawRect(0, 0, w, h, maskPaint);
        }

        if (frame.overlay != 0 && frame.overlayAlpha > 0.01f) {
            maskPaint.setColor(0xFF04070C);
            maskPaint.setAlpha((int) (0.46f * frame.overlayAlpha * 255));
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

        if (frame.overlay != 0 && frame.overlayAlpha > 0.01f) {
            GazeController.Readout mg = frame.overlayGaze;
            if (frame.overlay == 1) {
                drawMenu(c, mg == null ? EMPTY : mg, frame.overlayAlpha);
            } else if (frame.overlay == 3) {
                drawBrowser(c, mg == null ? EMPTY : mg, frame.overlayAlpha);
            } else {
                drawConfirm(c, mg == null ? EMPTY : mg, frame.overlayAlpha);
            }
            drawReticle(c, frame.overlayAlpha);
            return;
        }

        float relYaw = g.relYaw;
        float relPitch = g.relPitch;

        if (frame.flashAlpha > 0f) {
            drawFlash(c, frame.flashAlpha);
        }

        if (a > 0.01f) {
            drawTopStatus(c, relYaw, relPitch, a);
            drawTimeline(c, relYaw, relPitch, a);
            for (HudSpec.Target t : HudSpec.ROW) {
                drawTarget(c, t, relYaw, relPitch, g, a);
            }
            drawTarget(c, HudSpec.RECENTER, relYaw, relPitch, g, a);
            drawReticle(c, a);
            drawGazeText(c, relYaw, relPitch, g, a);
        }

        // Subtitles outlive the HUD fade: they are content, not a control.
        if (frame.subtitleOn && frame.subtitle.length() > 0 && !g.charging) {
            drawSubtitle(c, relYaw, relPitch, Math.max(0.85f, a));
        }

        if (frame.recenterCountdown > 0f) {
            drawRecenterCountdown(c, relPitch, a);
        }

        if (frame.wakeAlpha > 0.01f) {
            drawMicroProgress(c, frame.wakeAlpha);
            drawWakeHint(c, frame.wakeAlpha);
        }

        if (frame.status.length() > 0 && SystemClock.elapsedRealtime() < frame.statusUntil
                && !g.charging) {
            drawStatus(c, relYaw, relPitch, a);
        }
    }

    // ------------------------------------------------------------- angle mapping

    // Angles map linearly around the eye. A heads-up layer then keeps the same
    // size anywhere in the field; the tangent stretch of a flat off-axis panel
    // was what sheared the control bar when the head turned.
    private float sx(float yawDeg) {
        return eyeCx + yawDeg * pxPerDeg;
    }

    private float sy(float pitchDeg) {
        return eyeCy - pitchDeg * pxPerDeg;
    }

    private boolean onScreen(float yawDeg, float pitchDeg) {
        return Math.abs(yawDeg) < 72f && Math.abs(pitchDeg) < 60f;
    }

    private RectF angularRect(float yaw, float pitch, float wDeg, float hDeg, RectF out) {
        out.set(sx(yaw - wDeg * 0.5f), sy(pitch + hDeg * 0.5f),
                sx(yaw + wDeg * 0.5f), sy(pitch - hDeg * 0.5f));
        return out;
    }

    private float angularRadius(float diameterDeg) {
        return diameterDeg * 0.5f * pxPerDeg;
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
        textPaint.setTextSize(fontPx(2.1f * frame.subtitleScale));
        tint(textPaint, 0xFFF4F7FF, (int) (alpha * 240));
        textPaint.setTextAlign(Paint.Align.CENTER);
        float maxWidth = halfW * 1.45f;
        java.util.List<String> lines = wrapLines(frame.subtitle, maxWidth, 2);
        float linePitch = 2.4f * frame.subtitleScale;
        float topPitch = pitch + (lines.size() - 1) * linePitch * 0.5f;
        if (frame.subtitleOutline) {
            strokePaint.setTypeface(regular);
            strokePaint.setTextSize(textPaint.getTextSize());
            strokePaint.setStyle(Paint.Style.STROKE);
            strokePaint.setStrokeWidth(fontPx(0.16f));
            tint(strokePaint, 0xE6000000, (int) (alpha * 240));
            strokePaint.setTextAlign(Paint.Align.CENTER);
            for (int i = 0; i < lines.size(); i++) {
                c.drawText(lines.get(i), eyeCx, sy(topPitch - i * linePitch), strokePaint);
            }
        }
        for (int i = 0; i < lines.size(); i++) {
            c.drawText(lines.get(i), eyeCx, sy(topPitch - i * linePitch), textPaint);
        }
    }

    /** Character-wise wrap that also handles CJK (no spaces); 2 lines max. */
    private java.util.List<String> wrapLines(String text, float maxWidth, int maxLines) {
        java.util.List<String> out = new java.util.ArrayList<>();
        for (String raw : text.split("\n")) {
            String line = raw.trim();
            while (!line.isEmpty() && out.size() < maxLines) {
                if (textPaint.measureText(line) <= maxWidth) {
                    out.add(line);
                    line = "";
                    break;
                }
                int cut = line.length() - 1;
                while (cut > 1 && textPaint.measureText(line.substring(0, cut)) > maxWidth) {
                    cut--;
                }
                int space = line.lastIndexOf(' ', cut);
                if (space > 0) {
                    cut = space;
                }
                out.add(line.substring(0, cut).trim());
                line = line.substring(cut).trim();
                if (out.size() == maxLines && !line.isEmpty()) {
                    String last = out.remove(out.size() - 1);
                    while (last.length() > 1
                            && textPaint.measureText(last + "…") > maxWidth) {
                        last = last.substring(0, last.length() - 1);
                    }
                    out.add(last + "…");
                }
            }
            if (out.size() >= maxLines) {
                break;
            }
        }
        return out;
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
        float radius = angularRadius(t.diameterDeg);

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
        if (target == null) {
            return;
        }
        String action = actionLabel(target);
        if (target.id == HudSpec.Id.RECENTER) {
            if (g.ready) {
                drawPillText(c, "待发 · " + oneDecimal(Math.max(0f, remainingMs(g) / 1000f))
                                + " 秒后进入姿态调整",
                        target, relYaw, relPitch, 0xFFB0FFE0, alpha);
            } else {
                drawPillText(c, "注视触发 · 之后 3.5 秒调整姿态", target, relYaw, relPitch,
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

    /** Grace window copy: the head pose captured at the end becomes forward. */
    private void drawRecenterCountdown(Canvas c, float relPitch, float alpha) {
        textPaint.setTypeface(medium);
        textPaint.setTextSize(fontPx(2.2f));
        tint(textPaint, 0xFFFFE6C2, (int) (alpha * 245));
        textPaint.setTextAlign(Paint.Align.CENTER);
        c.drawText("调整姿态 · " + oneDecimal(frame.recenterCountdown) + " 秒后回中",
                eyeCx, sy(-6.2f - relPitch), textPaint);

        textPaint.setTypeface(regular);
        textPaint.setTextSize(fontPx(1.3f));
        tint(textPaint, HudSpec.C_TEXT2, (int) (alpha * 215));
        c.drawText("结束后，你此刻的朝向就是新的正前方", eyeCx, sy(-8.6f - relPitch), textPaint);

        textPaint.setTextSize(fontPx(1.2f));
        tint(textPaint, 0xFFB7C3D8, (int) (alpha * 205));
        c.drawText("按返回键　取消回中", eyeCx, sy(-30.2f - relPitch), textPaint);
    }

    // ------------------------------------------------------------- S4 menu

    private void drawMenu(Canvas c, GazeController.Readout g, float alpha) {
        float yaw0 = -g.relYaw;
        float pitch0 = -g.relPitch;
        if (!onScreen(yaw0, pitch0)) {
            return;
        }
        angularRect(yaw0, pitch0, HudSpec.MENU_W, HudSpec.MENU_H, rect);
        drawPanel(c, rect, 16f, 0xE60B1018, 0x30FFFFFF, alpha);
        float left = rect.left + 16f;
        float right = rect.right - 16f;

        textPaint.setTypeface(medium);
        textPaint.setTextSize(fontPx(2.05f));
        tint(textPaint, HudSpec.C_TEXT, (int) (alpha * 255));
        textPaint.setTextAlign(Paint.Align.LEFT);
        c.drawText("菜单", left, sy(pitch0 + HudSpec.MENU_HEADER_PITCH), textPaint);

        textPaint.setTypeface(regular);
        textPaint.setTextSize(fontPx(1.15f));
        tint(textPaint, HudSpec.C_TEXT3, (int) (alpha * 220));
        textPaint.setTextAlign(Paint.Align.RIGHT);
        c.drawText("第 " + (frame.menuPage + 1) + "/" + frame.menuPages + " 页 · 30 s 自动关闭",
                right, sy(pitch0 + HudSpec.MENU_HEADER_PITCH), textPaint);

        tint(fillPaint, 0x1FFFFFFF, (int) (alpha * 255));
        float dy = sy(pitch0 + HudSpec.MENU_DIVIDER_PITCH);
        c.drawRect(left, dy, right, dy + 1f, fillPaint);

        HudSpec.Target[] rows = frame.menuTargets == null
                ? HudSpec.MENU_ROWS_P1 : frame.menuTargets;
        for (HudSpec.Target row : rows) {
            drawMenuRow(c, row, g, alpha);
        }

        float fy1 = sy(pitch0 + HudSpec.MENU_FOOTER1_PITCH);
        float fy2 = sy(pitch0 + HudSpec.MENU_FOOTER2_PITCH);
        textPaint.setTypeface(regular);
        textPaint.setTextSize(fontPx(1.08f));
        if (frame.overlayHint.length() > 0) {
            tint(textPaint, HudSpec.C_AMBER, (int) (alpha * 240));
            textPaint.setTextAlign(Paint.Align.CENTER);
            c.drawText(frame.overlayHint, rect.centerX(), fy1, textPaint);
        } else {
            tint(textPaint, HudSpec.C_TEXT3, (int) (alpha * 220));
            textPaint.setTextAlign(Paint.Align.LEFT);
            c.drawText(frame.menuPage == 0
                            ? "注视图末「更多设置」翻页" : "注视图末「返回」回上一页",
                    left, fy1, textPaint);
        }
        if (frame.menuWarn) {
            tint(textPaint, HudSpec.C_AMBER, (int) (alpha * 240));
            textPaint.setTextAlign(Paint.Align.CENTER);
            c.drawText("菜单将在 3 秒后关闭", rect.centerX(), fy2, textPaint);
        } else {
            tint(textPaint, HudSpec.C_TEXT3, (int) (alpha * 200));
            textPaint.setTextAlign(Paint.Align.RIGHT);
            c.drawText(frame.menuPage == 0
                    ? "视线移出面板 1.2 s 关闭" : "退出需二次确认", right, fy2, textPaint);
        }
    }

    private void drawMenuRow(Canvas c, HudSpec.Target t, GazeController.Readout g, float alpha) {
        float yaw = t.yaw - g.relYaw;
        float pitch = t.pitch - g.relPitch;
        if (!onScreen(yaw, pitch)) {
            return;
        }
        boolean danger = t.id == HudSpec.Id.MENU_EXIT;
        boolean focused = g.focus == t;
        boolean cancelling = g.cancelling == t;
        boolean hint = frame.defaultFocusHint && t == HudSpec.MENU_SUBTITLE_T && g.focus == null;
        boolean lit = focused || hint;

        angularRect(yaw, pitch, t.barW, t.barH, rect);
        tint(fillPaint, lit ? (danger ? 0x1FFF8B8B : 0x1AFFB454) : 0x0FFFFFFF,
                (int) (alpha * 255));
        c.drawRoundRect(rect, 9f, 9f, fillPaint);

        int border = focused ? (danger ? HudSpec.C_RED : HudSpec.C_AMBER)
                : hint ? 0x66FFB454 : (danger ? 0x5CFF8B8B : 0x24FFFFFF);
        tint(strokePaint, border, (int) (alpha * (focused ? 255 : 210)));
        strokePaint.setStyle(Paint.Style.STROKE);
        strokePaint.setStrokeWidth(focused ? 2.2f : 1.1f);
        c.drawRoundRect(rect, 9f, 9f, strokePaint);

        float ih = Math.min(rect.height() * 0.64f, fontPx(3.1f));
        float icx = rect.left + 12f + ih * 0.5f;
        float icy = rect.centerY();
        tint(fillPaint, 0x14FFFFFF, (int) (alpha * 255));
        c.drawCircle(icx, icy, ih * 0.5f, fillPaint);
        tint(strokePaint, 0x24FFFFFF, (int) (alpha * 210));
        strokePaint.setStrokeWidth(1f);
        c.drawCircle(icx, icy, ih * 0.5f, strokePaint);
        textPaint.setTypeface(medium);
        textPaint.setTextSize(fontPx(1.05f));
        tint(textPaint, danger ? HudSpec.C_RED : HudSpec.C_TEXT2, (int) (alpha * 240));
        textPaint.setTextAlign(Paint.Align.CENTER);
        c.drawText(iconOf(t.id), icx, icy + textPaint.getTextSize() * 0.34f, textPaint);

        float lx = icx + ih * 0.5f + 10f;
        textPaint.setTypeface(medium);
        textPaint.setTextSize(fontPx(1.5f));
        tint(textPaint, danger ? HudSpec.C_RED : HudSpec.C_TEXT, (int) (alpha * 255));
        textPaint.setTextAlign(Paint.Align.LEFT);
        c.drawText(t.id.label(), lx, rect.centerY() - fontPx(0.62f), textPaint);

        String sub;
        if (focused && g.charging) {
            sub = "继续注视 " + oneDecimal(g.remainingSec) + " 秒 · " + actionLabel(t);
        } else if (focused) {
            sub = "停留 " + oneDecimal(t.levelMs / 1000f) + " 秒 · " + actionLabel(t);
        } else {
            sub = secondaryOf(t);
        }
        textPaint.setTypeface(regular);
        textPaint.setTextSize(fontPx(1.0f));
        tint(textPaint, focused ? 0xFFE2C69A : HudSpec.C_TEXT3, (int) (alpha * 235));
        c.drawText(sub, lx, rect.centerY() + fontPx(1.05f), textPaint);

        float rr = Math.min(rect.height() * 0.30f, fontPx(1.9f));
        float ringCx = rect.right - rr - 12f;
        float ringCy = rect.centerY();
        String value;
        if (focused && g.charging) {
            value = oneDecimal(g.remainingSec) + " s";
        } else if (focused) {
            value = oneDecimal(t.levelMs / 1000f) + " s";
        } else {
            value = valueOf(t, frame);
        }
        textPaint.setTypeface(medium);
        textPaint.setTextSize(fontPx(1.25f));
        tint(textPaint, focused ? (danger ? HudSpec.C_RED : HudSpec.C_AMBER)
                : (danger ? HudSpec.C_RED : HudSpec.C_TEXT2), (int) (alpha * 245));
        textPaint.setTextAlign(Paint.Align.RIGHT);
        c.drawText(value, ringCx - rr - 8f, ringCy + fontPx(0.45f), textPaint);

        if (focused || cancelling) {
            float prog = focused ? g.progress : g.cancelProgress;
            tint(strokePaint, HudSpec.C_RING_TRACK, (int) (alpha * 220));
            strokePaint.setStyle(Paint.Style.STROKE);
            strokePaint.setStrokeWidth(2f);
            c.drawCircle(ringCx, ringCy, rr, strokePaint);
            tint(strokePaint, danger ? HudSpec.C_RED : HudSpec.C_AMBER, (int) (alpha * 255));
            strokePaint.setStrokeWidth(2.8f);
            rect2.set(ringCx - rr, ringCy - rr, ringCx + rr, ringCy + rr);
            c.drawArc(rect2, -90f, Math.max(0.5f, 360f * prog), false, strokePaint);
        }
    }

    /**
     * In-VR SMB browser: five gaze rows per page, page arrows and the same
     * projection toggle as the phone library. Large rows and short dwells keep
     * it usable while wearing the headset.
     */
    private void drawBrowser(Canvas c, GazeController.Readout g, float alpha) {
        float yaw0 = -g.relYaw;
        float pitch0 = -g.relPitch;
        if (!onScreen(yaw0, pitch0)) {
            return;
        }
        angularRect(yaw0, pitch0, HudSpec.BROWSER_W, HudSpec.BROWSER_H, rect);
        drawPanel(c, rect, 16f, 0xF20B1018, 0x33FFFFFF, alpha);
        float left = rect.left + 16f;
        float right = rect.right - 16f;

        textPaint.setTypeface(medium);
        textPaint.setTextSize(fontPx(2.05f));
        tint(textPaint, HudSpec.C_TEXT, (int) (alpha * 255));
        textPaint.setTextAlign(Paint.Align.LEFT);
        c.drawText("文件管理", left, sy(pitch0 + HudSpec.BROWSER_HEADER_PITCH), textPaint);

        textPaint.setTypeface(regular);
        textPaint.setTextSize(fontPx(1.15f));
        tint(textPaint, HudSpec.C_TEXT3, (int) (alpha * 220));
        textPaint.setTextAlign(Paint.Align.RIGHT);
        String page = frame.browserPage + 1 + " / " + Math.max(1, frame.browserPages);
        c.drawText(page, right, sy(pitch0 + HudSpec.BROWSER_HEADER_PITCH), textPaint);

        textPaint.setTextSize(fontPx(1.05f));
        tint(textPaint, 0xFF8FB3C9, (int) (alpha * 220));
        textPaint.setTextAlign(Paint.Align.LEFT);
        String path = frame.browserPath == null ? "" : frame.browserPath;
        float maxW = right - left;
        String fitted = ellipsizeStart(path, maxW);
        c.drawText(fitted, left, sy(pitch0 + HudSpec.BROWSER_PATH_PITCH), textPaint);

        tint(fillPaint, 0x1FFFFFFF, (int) (alpha * 255));
        float dividerP = HudSpec.BROWSER_HEADER_PITCH - 1.4f;
        float dy = sy(pitch0 + dividerP);
        c.drawRect(left, dy, right, dy + 1f, fillPaint);

        HudSpec.Target[] targets = frame.browserTargets;
        if (targets == null || targets.length == 0) {
            textPaint.setTypeface(regular);
            textPaint.setTextSize(fontPx(1.35f));
            tint(textPaint, HudSpec.C_TEXT3, (int) (alpha * 230));
            textPaint.setTextAlign(Paint.Align.CENTER);
            c.drawText("此目录没有可播放的视频", rect.centerX(),
                    sy(pitch0 + HudSpec.BROWSER_ROW_PITCH[2]), textPaint);
        } else {
            for (HudSpec.Target t : targets) {
                if (t.id == HudSpec.Id.BROWSER_ITEM) {
                    drawBrowserRow(c, t, g, alpha);
                }
            }
        }

        for (HudSpec.Target t : targets == null ? new HudSpec.Target[0] : targets) {
            if (t.id == HudSpec.Id.BROWSER_ITEM) {
                continue;
            }
            drawBrowserButton(c, t, g, alpha);
        }

        textPaint.setTypeface(regular);
        textPaint.setTextSize(fontPx(1.05f));
        tint(textPaint, HudSpec.C_TEXT3, (int) (alpha * 220));
        textPaint.setTextAlign(Paint.Align.CENTER);
        String hint;
        if (frame.browserLoading) {
            hint = "正在读取…";
        } else if (frame.browserHint != null && frame.browserHint.length() > 0) {
            hint = frame.browserHint;
            tint(textPaint, HudSpec.C_AMBER, (int) (alpha * 235));
        } else {
            hint = "★ 收藏夹优先显示 · 返回键退出";
        }
        c.drawText(hint, rect.centerX(), sy(pitch0 + HudSpec.BROWSER_HINT_PITCH), textPaint);
    }

    private void drawBrowserRow(Canvas c, HudSpec.Target t, GazeController.Readout g,
                                float alpha) {
        float yaw = t.yaw - g.relYaw;
        float pitch = t.pitch - g.relPitch;
        if (!onScreen(yaw, pitch)) {
            return;
        }
        boolean focused = g.focus == t;
        angularRect(yaw, pitch, t.barW, t.barH, rect);
        tint(fillPaint, focused ? 0x1AFFB454 : 0x0FFFFFFF, (int) (alpha * 255));
        c.drawRoundRect(rect, 8f, 8f, fillPaint);
        tint(strokePaint, focused ? HudSpec.C_AMBER : 0x24FFFFFF,
                (int) (alpha * (focused ? 255 : 205)));
        strokePaint.setStyle(Paint.Style.STROKE);
        strokePaint.setStrokeWidth(focused ? 2.2f : 1.1f);
        c.drawRoundRect(rect, 8f, 8f, strokePaint);

        textPaint.setTypeface(medium);
        textPaint.setTextSize(fontPx(1.4f));
        tint(textPaint, HudSpec.C_TEXT, (int) (alpha * 250));
        textPaint.setTextAlign(Paint.Align.LEFT);
        float titleMax = rect.width() * 0.62f;
        String title = t.text == null ? "" : t.text;
        String fit = ellipsizeEnd(title, titleMax);
        c.drawText(fit, rect.left + 12f,
                rect.centerY() + textPaint.getTextSize() * 0.36f, textPaint);

        if (t.detail != null && t.detail.length() > 0) {
            tint(textPaint, 0xFF98A6BC, (int) (alpha * 235));
            textPaint.setTextSize(fontPx(1.05f));
            textPaint.setTextAlign(Paint.Align.RIGHT);
            String det = ellipsizeEnd(t.detail, rect.width() * 0.36f);
            c.drawText(det, rect.right - 12f,
                    rect.centerY() + fontPx(0.35f), textPaint);
        }

        if (focused) {
            float prog = g.charging ? g.progress : 0f;
            float h = Math.max(2f, rect.height() * 0.06f);
            tint(fillPaint, HudSpec.C_AMBER, (int) (alpha * 245));
            c.drawRect(rect.left + 8f, rect.bottom - h - 3f,
                    rect.left + 8f + (rect.width() - 16f) * prog,
                    rect.bottom - 3f, fillPaint);
        }
    }

    private void drawBrowserButton(Canvas c, HudSpec.Target t, GazeController.Readout g,
                                   float alpha) {
        float yaw = t.yaw - g.relYaw;
        float pitch = t.pitch - g.relPitch;
        if (!onScreen(yaw, pitch)) {
            return;
        }
        boolean focused = g.focus == t;
        angularRect(yaw, pitch, t.barW, t.barH, rect);
        tint(fillPaint, focused ? 0x26FFB454 : 0x14FFFFFF, (int) (alpha * 255));
        c.drawRoundRect(rect, 8f, 8f, fillPaint);
        tint(strokePaint, focused ? HudSpec.C_AMBER : 0x2EFFFFFF,
                (int) (alpha * (focused ? 255 : 215)));
        strokePaint.setStyle(Paint.Style.STROKE);
        strokePaint.setStrokeWidth(focused ? 2f : 1f);
        c.drawRoundRect(rect, 8f, 8f, strokePaint);

        String label = t.text != null && t.text.length() > 0 ? t.text : t.id.label();
        textPaint.setTypeface(medium);
        textPaint.setTextSize(fontPx(1.25f));
        tint(textPaint, focused ? HudSpec.C_AMBER : HudSpec.C_TEXT2, (int) (alpha * 250));
        textPaint.setTextAlign(Paint.Align.CENTER);
        c.drawText(label, rect.centerX(),
                rect.centerY() + textPaint.getTextSize() * 0.36f, textPaint);

        if (focused && g.charging) {
            float h = Math.max(2f, rect.height() * 0.07f);
            tint(fillPaint, HudSpec.C_AMBER, (int) (alpha * 245));
            c.drawRect(rect.left + 8f, rect.bottom - h - 3f,
                    rect.left + 8f + (rect.width() - 16f) * g.progress,
                    rect.bottom - 3f, fillPaint);
        }
    }

    /** Truncates with an ellipsis, keeping the head of the string. */
    private String ellipsizeEnd(String text, float maxWidth) {
        if (text == null) {
            return "";
        }
        if (textPaint.measureText(text) <= maxWidth) {
            return text;
        }
        int n = text.length();
        while (n > 1 && textPaint.measureText(text.substring(0, n) + "…") > maxWidth) {
            n--;
        }
        return text.substring(0, n) + "…";
    }

    /** Truncates with an ellipsis from the front, so the path's tail stays. */
    private String ellipsizeStart(String text, float maxWidth) {
        if (text == null) {
            return "";
        }
        if (textPaint.measureText(text) <= maxWidth) {
            return text;
        }
        int start = 0;
        while (start < text.length() - 1
                && textPaint.measureText("…" + text.substring(start + 1)) > maxWidth) {
            start++;
        }
        return "…" + text.substring(start + 1);
    }

    private void drawConfirm(Canvas c, GazeController.Readout g, float alpha) {
        float yaw0 = -g.relYaw;
        float pitch0 = -g.relPitch;
        if (!onScreen(yaw0, pitch0)) {
            return;
        }
        angularRect(yaw0, pitch0, HudSpec.CONFIRM_W, HudSpec.CONFIRM_H, rect);
        drawPanel(c, rect, 16f, 0xF20B1018, 0x33FFFFFF, alpha);

        textPaint.setTypeface(medium);
        textPaint.setTextSize(fontPx(2.3f));
        tint(textPaint, HudSpec.C_TEXT, (int) (alpha * 255));
        textPaint.setTextAlign(Paint.Align.CENTER);
        c.drawText("退出播放？", rect.centerX(), sy(pitch0 + HudSpec.CONFIRM_TITLE_PITCH), textPaint);

        textPaint.setTypeface(regular);
        textPaint.setTextSize(fontPx(1.25f));
        tint(textPaint, HudSpec.C_TEXT2, (int) (alpha * 240));
        c.drawText("回片库 · 保留进度，5 s 内可撤销", rect.centerX(),
                sy(pitch0 + HudSpec.CONFIRM_BODY_PITCH), textPaint);

        for (HudSpec.Target t : HudSpec.CONFIRM_TARGETS) {
            boolean danger = t.id == HudSpec.Id.EXIT_OK;
            boolean focused = g.focus == t;
            boolean cancelling = g.cancelling == t;
            boolean hint = frame.defaultFocusHint && t.id == HudSpec.Id.EXIT_CANCEL
                    && g.focus == null;
            float yaw = t.yaw - g.relYaw;
            float pitch = t.pitch - g.relPitch;
            if (!onScreen(yaw, pitch)) {
                continue;
            }
            angularRect(yaw, pitch, t.barW, t.barH, rect);
            tint(fillPaint, (focused || hint) ? (danger ? 0x26FF8B8B : 0x1AFFB454)
                    : 0x14FFFFFF, (int) (alpha * 255));
            c.drawRoundRect(rect, 9f, 9f, fillPaint);
            int border = focused ? (danger ? HudSpec.C_RED : HudSpec.C_AMBER)
                    : hint ? 0x66FFB454 : (danger ? 0x7CFF8B8B : 0x2EFFFFFF);
            tint(strokePaint, border, (int) (alpha * (focused ? 255 : 220)));
            strokePaint.setStyle(Paint.Style.STROKE);
            strokePaint.setStrokeWidth(focused ? 2.2f : 1.1f);
            c.drawRoundRect(rect, 9f, 9f, strokePaint);

            textPaint.setTypeface(medium);
            textPaint.setTextSize(fontPx(1.55f));
            tint(textPaint, danger ? HudSpec.C_RED : HudSpec.C_TEXT, (int) (alpha * 255));
            textPaint.setTextAlign(Paint.Align.CENTER);
            c.drawText(t.id.label(), rect.centerX(),
                    rect.centerY() + textPaint.getTextSize() * 0.36f, textPaint);

            if (focused || cancelling) {
                float prog = focused ? g.progress : g.cancelProgress;
                float h = Math.max(2f, rect.height() * 0.06f);
                tint(fillPaint, danger ? HudSpec.C_RED : HudSpec.C_AMBER, (int) (alpha * 245));
                c.drawRect(rect.left + 10f, rect.bottom - h - 4f,
                        rect.left + 10f + (rect.width() - 20f) * prog,
                        rect.bottom - 4f, fillPaint);
            }
        }

        textPaint.setTypeface(regular);
        textPaint.setTextSize(fontPx(1.12f));
        tint(textPaint, HudSpec.C_TEXT3, (int) (alpha * 230));
        textPaint.setTextAlign(Paint.Align.CENTER);
        boolean countingOk = g.focus != null && g.focus.id == HudSpec.Id.EXIT_OK && g.charging;
        String hint = countingOk
                ? "继续注视 " + oneDecimal(g.remainingSec) + " 秒 · 确认退出"
                : "默认停在取消 · 确认项需再停留 1.8 秒";
        tint(textPaint, countingOk ? HudSpec.C_RED : HudSpec.C_TEXT3, (int) (alpha * 240));
        c.drawText(hint, rect.centerX(), sy(pitch0 + HudSpec.CONFIRM_HINT_PITCH), textPaint);
    }

    private static String iconOf(HudSpec.Id id) {
        switch (id) {
            case MENU_SUBTITLE:
                return "CC";
            case MENU_AUDIO:
                return "音";
            case MENU_SPEED:
                return "×";
            case MENU_BRIGHT:
                return "亮";
            case MENU_AUTO_BRIGHT:
                return "A";
            case MENU_WAKE:
                return "◎";
            case MENU_FILE:
                return "库";
            case MENU_MORE:
                return "…";
            case MENU_BACK:
                return "←";
            case MENU_VOLUME:
                return "声";
            case MENU_SERVER:
                return "服";
            case MENU_SUB_STYLE:
                return "字";
            case MENU_EXIT:
            default:
                return "退";
        }
    }

    private static String valueOf(HudSpec.Target t, Frame f) {
        switch (t.id) {
            case MENU_SUBTITLE:
                return f.subtitleOn ? "开" : "关";
            case MENU_AUDIO:
                return f.audioValue == null ? "无" : f.audioValue;
            case MENU_SPEED:
                return speedText(f.speed);
            case MENU_BRIGHT:
                return f.brightnessText;
            case MENU_AUTO_BRIGHT:
                return f.autoBright ? "开" : "关";
            case MENU_WAKE:
                return f.wakeText;
            case MENU_FILE:
                return "打开";
            case MENU_MORE:
                return "下一页";
            case MENU_BACK:
                return "上一页";
            case MENU_VOLUME:
                return f.volumeText;
            case MENU_SERVER:
                return f.serverText;
            case MENU_SUB_STYLE:
                return f.subStyleText;
            case MENU_EXIT:
            default:
                return "2.5s+确认";
        }
    }

    private String secondaryOf(HudSpec.Target t) {
        switch (t.id) {
            case MENU_SUBTITLE:
                return frame.subtitleText == null ? "未加载" : frame.subtitleText;
            case MENU_AUDIO:
                return frame.audioText == null ? "音轨加载中" : frame.audioText;
            case MENU_SPEED:
                return "循环 0.5–2.0 · 重进恢复 1.0×";
            case MENU_BRIGHT:
                return frame.autoBright ? "自动亮度生效 · 用完即切换手动" : "循环 20%–100% · 仅本窗口";
            case MENU_AUTO_BRIGHT:
                return frame.autoBright ? "按环境光调节 · 电影场景建议开" : "固定亮度 · 不受环境光影响";
            case MENU_WAKE:
                return "低头多少唤回控件 · 越灵敏越容易触发";
            case MENU_FILE:
                return "SMB 片库 · 收藏夹 · 最近播放";
            case MENU_MORE:
                return "音量 / 服务器 / 字幕样式 / 文件 / 退出";
            case MENU_BACK:
                return "回到常用设置";
            case MENU_VOLUME:
                return "媒体音量 · 25% 一档循环";
            case MENU_SERVER:
                return frame.serverText == null ? "服务器列表" : "下个片库使用所选服务器";
            case MENU_SUB_STYLE:
                return "字号与描边预设 · 记住选择";
            case MENU_EXIT:
            default:
                return "回片库 · 保留进度，5 s 内可撤销";
        }
    }

    private static String speedText(float speed) {
        String s = String.format("%.2f", speed);
        while (s.length() > 3 && s.endsWith("0")) {
            s = s.substring(0, s.length() - 1);
        }
        return s + "×";
    }

    /**
     * Pinned to the bottom of the eye instead of a world anchor: the gesture it
     * explains is a head-relative downward glance, so the hint has to be
     * findable no matter where the head was last pointing.
     */
    private void drawWakeHint(Canvas c, float alpha) {
        float cy = sy(-36f);
        float halfWidth = 16f * pxPerDeg;
        float halfHeight = 1.9f * pxPerDeg;
        rect.set(eyeCx - halfWidth, cy - halfHeight, eyeCx + halfWidth, cy + halfHeight);
        tint(fillPaint, 0x1FFFFFFF, (int) (alpha * 255));
        c.drawRoundRect(rect, rect.height() * 0.5f, rect.height() * 0.5f, fillPaint);
        tint(strokePaint, 0x47FFFFFF, (int) (alpha * 160));
        strokePaint.setStrokeWidth(1f);
        strokePaint.setStyle(Paint.Style.STROKE);
        c.drawRoundRect(rect, rect.height() * 0.5f, rect.height() * 0.5f, strokePaint);

        float progress = Math.min(1f, Math.max(0f, frame.wakeProgress));
        if (progress > 0f) {
            RectF inner = rect2;
            inner.set(rect.left + 2f, rect.bottom - 3f,
                    rect.left + 2f + (rect.width() - 4f) * progress, rect.bottom - 1f);
            tint(fillPaint, 0xD9FFB454, (int) (alpha * 255));
            c.drawRoundRect(inner, 1f, 1f, fillPaint);
        }

        textPaint.setTypeface(regular);
        textPaint.setTextSize(fontPx(1.05f));
        tint(textPaint, 0x66FFFFFF, (int) (alpha * 190));
        textPaint.setTextAlign(Paint.Align.CENTER);
        c.drawText(progress > 0f ? "保持低头 · 唤回控件" : "低头看下方唤回控件",
                rect.centerX(), rect.centerY() + textPaint.getTextSize() * 0.34f, textPaint);
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
            case MENU_SUBTITLE:
                return frame.subtitleOn ? "关闭字幕" : "开启字幕";
            case RECENTER:
                return "视角回中";
            case MENU_AUDIO:
                return "切换音轨";
            case MENU_SPEED:
                return "切换播放速度";
            case MENU_BRIGHT:
                return "切换亮度档位";
            case MENU_AUTO_BRIGHT:
                return frame.autoBright ? "关闭自动亮度" : "开启自动亮度";
            case MENU_WAKE:
                return "切换唤回灵敏度";
            case MENU_MORE:
                return "打开第二页设置";
            case MENU_BACK:
                return "回到第一页";
            case MENU_VOLUME:
                return "调整音量";
            case MENU_SERVER:
                return "切换服务器";
            case MENU_SUB_STYLE:
                return "切换字幕样式";
            case MENU_EXIT:
                return "退出播放";
            case EXIT_CANCEL:
                return "取消退出";
            case EXIT_OK:
                return "确认退出";
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
