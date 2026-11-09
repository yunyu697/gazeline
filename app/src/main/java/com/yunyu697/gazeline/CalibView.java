package com.yunyu697.gazeline;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.MotionEvent;
import android.view.View;

import java.util.Locale;

/**
 * The in-viewer S5 calibration page (§1.12). The test card is drawn through the
 * same radial lens model and per-eye vertical trim as the video, so k1/k2 and
 * the eye offsets are visible while they are changed; the −/+ groups, the eye
 * capsules and the two actions are plain screen-space UI with Ø8°/Ø7.5° hot
 * zones. No progress bar, subtitles or recenter key here.
 */
final class CalibView extends View {

    static final int PHASE_SELF_TEST = 0;
    static final int PHASE_ADJUST = 1;

    static final int EYE_BOTH = 0;
    static final int EYE_LEFT_ONLY = 1;
    static final int EYE_RIGHT_ONLY = 2;

    interface Listener {
        void onLook(float dyawDeg, float dpitchDeg);
    }

    static final class State {
        CalibParams params = CalibParams.defaults();
        CalibParams saved = CalibParams.defaults();
        GazeController.Readout gaze;
        int phase = PHASE_SELF_TEST;
        int eyeMode = EYE_BOTH;
        boolean folded;
        boolean blurMode;
        boolean resetPulse;
        String hint = "";
        long selfTestRemainMs = 20000;
        float selfTestElapsed;
        float viewYaw;
        float viewPitch;
    }

    private final State state = new State();
    private Listener listener;

    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tp = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF rect = new RectF();
    private final float[] pt = new float[3];

    private Typeface tfLight;
    private Typeface tfReg;
    private Typeface tfMed;

    private float W, H, dp, halfW, eyeCx, eyeCy, aspectEye, k1, k2, vertDeg, rqFold = 1f;

    private float lastX, lastY;

    CalibView(Context c) {
        super(c);
        tfLight = Typeface.create("sans-serif-light", Typeface.NORMAL);
        tfReg = Typeface.create("sans-serif", Typeface.NORMAL);
        tfMed = Typeface.create("sans-serif-medium", Typeface.NORMAL);
        setFocusable(true);
    }

    void setListener(Listener l) {
        listener = l;
    }

    State state() {
        return state;
    }

    void apply(CalibParams params) {
        state.params = params;
        invalidate();
    }

    void setPhase(int phase) {
        state.phase = phase;
        invalidate();
    }

    void setEyeMode(int mode) {
        state.eyeMode = mode;
        invalidate();
    }

    void setFolded(boolean folded) {
        state.folded = folded;
        invalidate();
    }

    void setBlurMode(boolean blur) {
        state.blurMode = blur;
        invalidate();
    }

    void setResetPulse(boolean pulse) {
        state.resetPulse = pulse;
        invalidate();
    }

    void showHint(String text) {
        state.hint = text == null ? "" : text;
        invalidate();
    }

    void setSelfTest(long remainMs, float elapsed) {
        state.selfTestRemainMs = remainMs;
        state.selfTestElapsed = elapsed;
    }

    /** The activity pushes the gaze readout every tick. */
    void setGaze(GazeController.Readout readout) {
        state.gaze = readout;
        invalidate();
    }

    /** Head (or drag) direction: the card is world-fixed, the reticle is centre. */
    void setView(float viewYawDeg, float viewPitchDeg) {
        state.viewYaw = viewYawDeg;
        state.viewPitch = viewPitchDeg;
    }

    // ------------------------------------------------------------- geometry

    private float sx(float yawDeg) {
        float d = clampDeg(GazeController.wrap(yawDeg - state.viewYaw));
        return eyeCx + (float) Math.tan(Math.toRadians(d)) * halfW;
    }

    private float sy(float pitchDeg) {
        float d = clampDeg(pitchDeg - state.viewPitch);
        return eyeCy - (float) Math.tan(Math.toRadians(d)) * halfW;
    }

    private static float clampDeg(float d) {
        return d > 88f ? 88f : (d < -88f ? -88f : d);
    }

    /**
     * q = p(1 + k1 r² + k2 r⁴) folds back once k1 is negative enough: beyond
     * that sample radius the inverse has no solution, so anything further out
     * is genuinely invisible through the lens. Drawing past the fold would
     * produce the long sweeping arcs that the inverse Newton throws up, so the
     * fold radius is computed per frame and used to clip the card.
     */
    private float foldRadius() {
        float best = 0f;
        float prev = 0f;
        for (int i = 1; i <= 400; i++) {
            float s = i * 0.01f;
            float s2 = s * s;
            float f = s * (1f + k1 * s2 + k2 * s2 * s2);
            if (f <= prev) {
                return best;
            }
            prev = f;
            best = f;
        }
        return best;
    }

    /** Controls live on the panel: past ~44° they are outside the lens entirely. */
    private boolean inView(float yaw, float pitch) {
        return Math.abs(clampDeg(GazeController.wrap(yaw - state.viewYaw))) <= 44f
                && Math.abs(clampDeg(pitch - state.viewPitch)) <= 44f;
    }

    /** tan clamped to the panel edge, so no size can explode near the fold. */
    private static float tanEdge(float deg) {
        float d = deg > 44f ? 44f : (deg < -44f ? -44f : deg);
        return (float) Math.tan(Math.toRadians(d));
    }

    private float rad(float diameterDeg, float yaw, float pitch) {
        float r = diameterDeg * 0.5f;
        float ry = clampDeg(GazeController.wrap(yaw - state.viewYaw));
        float rp = clampDeg(pitch - state.viewPitch);
        float rx = halfW * (tanEdge(ry + r) - tanEdge(ry - r)) * 0.5f;
        float ryy = halfW * (tanEdge(rp + r) - tanEdge(rp - r)) * 0.5f;
        return (Math.abs(rx) + Math.abs(ryy)) * 0.5f;
    }

    private float fontPx(float deg) {
        return (float) (2.0 * halfW * Math.tan(Math.toRadians(deg * 0.5f)));
    }

    /**
     * Content angle → screen. q is the distorted sample coordinate; the
     * shaders apply q = p(1 + k1 r² + k2 r⁴), so drawing needs the inverse
     * radial solve to place a straight world line where the lens would.
     */
    private boolean warp(float yawC, float pitchC, float[] out) {
        float yawRel = clampDeg(GazeController.wrap(yawC - state.viewYaw));
        float pitchRel = clampDeg(pitchC - state.viewPitch);
        float qx = (float) Math.tan(Math.toRadians(yawRel));
        float qy = (float) (Math.tan(Math.toRadians(pitchRel)) - Math.tan(Math.toRadians(vertDeg))) * aspectEye;
        float rq = (float) Math.sqrt(qx * qx + qy * qy);
        if (rq >= rqFold) {
            return false;
        }
        if (rq < 1e-6f) {
            out[0] = eyeCx;
            out[1] = eyeCy;
            out[2] = 1f;
            return true;
        }
        float s = invRadial(rq);
        float px = qx * s / rq;
        float py = qy * s / rq;
        if (Math.abs(px) > 1.15f || Math.abs(py) > 1.15f) {
            return false;
        }
        out[0] = eyeCx + px * halfW;
        out[1] = eyeCy - (py / aspectEye) * halfW;
        float scale = invRadial(rq + 0.01f) - invRadial(rq);
        out[2] = Math.max(0.35f, Math.min(1.8f, scale / 0.01f));
        return true;
    }

    private float invRadial(float rq) {
        float s = rq;
        for (int i = 0; i < 6; i++) {
            float s2 = s * s;
            float f = s * (1f + k1 * s2 + k2 * s2 * s2) - rq;
            float df = 1f + 3f * k1 * s2 + 5f * k2 * s2 * s2;
            if (Math.abs(df) < 0.08f) {
                return s;
            }
            s -= f / df;
            if (s < 0f) {
                s = 0f;
            }
        }
        return s;
    }

    private void wLine(Path out, float y0, float p0, float y1, float p1) {
        float dist = (float) Math.hypot(y1 - y0, p1 - p0);
        int n = Math.max(1, (int) Math.ceil(dist / 2f));
        boolean started = false;
        for (int i = 0; i <= n; i++) {
            float t = i / (float) n;
            if (!warp(y0 + (y1 - y0) * t, p0 + (p1 - p0) * t, pt)) {
                started = false;
                continue;
            }
            if (!started) {
                out.moveTo(pt[0], pt[1]);
                started = true;
            } else {
                out.lineTo(pt[0], pt[1]);
            }
        }
    }

    private void wCircle(Path out, float cyaw, float cpitch, float rDeg) {
        boolean started = false;
        for (int i = 0; i <= 40; i++) {
            double a = Math.PI * 2 * i / 40.0;
            float y = cyaw + (float) (Math.cos(a) * rDeg);
            float pi = cpitch + (float) (Math.sin(a) * rDeg);
            if (!warp(y, pi, pt)) {
                started = false;
                continue;
            }
            if (!started) {
                out.moveTo(pt[0], pt[1]);
                started = true;
            } else {
                out.lineTo(pt[0], pt[1]);
            }
        }
    }

    private void wRect(Path out, float y0, float p0, float y1, float p1) {
        wLine(out, y0, p0, y1, p0);
        wLine(out, y1, p0, y1, p1);
        wLine(out, y1, p1, y0, p1);
        wLine(out, y0, p1, y0, p0);
    }

    private void wText(Canvas c, String s, float yaw, float pitch, float sizeDeg,
                       int color, Typeface tf, Paint.Align align) {
        if (!warp(yaw, pitch, pt)) {
            return;
        }
        float size = fontPx(sizeDeg) * pt[2];
        if (size < 4f) {
            return;
        }
        tp.setTypeface(tf);
        tp.setTextSize(size);
        tp.setColor(color);
        tp.setTextAlign(align);
        c.drawText(s, pt[0], pt[1], tp);
    }

    private void txt(Canvas c, String s, float x, float y, float sizePx, int color,
                     Typeface tf, Paint.Align align) {
        tp.setTypeface(tf);
        tp.setTextSize(sizePx);
        tp.setColor(color);
        tp.setTextAlign(align);
        c.drawText(s, x, y, tp);
    }

    /** Text on a dark pill so the 10° grid never fights the labels. */
    private void chipText(Canvas c, String s, float x, float y, float sizePx, int color,
                          Typeface tf) {
        tp.setTypeface(tf);
        tp.setTextSize(sizePx);
        tp.setTextAlign(Paint.Align.CENTER);
        float tw = tp.measureText(s);
        rect.set(x - tw * 0.5f - 5 * dp, y - sizePx * 0.92f,
                x + tw * 0.5f + 5 * dp, y + sizePx * 0.32f);
        p.setStyle(Paint.Style.FILL);
        p.setColor(0xD9080B11);
        c.drawRoundRect(rect, 5 * dp, 5 * dp, p);
        tp.setColor(color);
        c.drawText(s, x, y, tp);
    }

    // ------------------------------------------------------------- draw

    @Override
    protected void onDraw(Canvas c) {
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) {
            return;
        }
        W = w;
        H = h;
        dp = getResources().getDisplayMetrics().density;
        halfW = w / 4f;
        aspectEye = halfW / (h / 2f);

        c.drawColor(0xFF04060A);

        CalibParams cp = state.params;
        GazeController.Readout g = state.gaze;

        for (int eye = 0; eye < 2; eye++) {
            eyeCx = halfW + eye * halfW * 2f;
            eyeCy = h / 2f;
            if (masked(eye)) {
                continue;
            }
            k1 = cp.k1;
            k2 = cp.k2;
            vertDeg = eye == 0 ? cp.vertLeftDeg : cp.vertRightDeg;
            rqFold = foldRadius();
            drawEye(c, eye, cp, g);
        }
    }

    private boolean masked(int eye) {
        if (state.eyeMode == EYE_LEFT_ONLY) {
            return eye == 1;
        }
        if (state.eyeMode == EYE_RIGHT_ONLY) {
            return eye == 0;
        }
        return false;
    }

    private void drawEye(Canvas c, int eye, CalibParams cp, GazeController.Readout g) {
        drawCard(c, eye, cp);
        if (state.blurMode) {
            drawBlurBanner(c);
        }
        drawReticle(c);
        drawCapsules(c, g);
        if (state.phase == PHASE_SELF_TEST) {
            drawSelfTest(c, g);
        } else {
            drawSteps(c, eye, cp, g);
            drawActions(c, g);
            if (state.hint.length() > 0) {
                drawHint(c);
            }
        }
    }

    // ------------------------------------------------------------- test card

    private void drawCard(Canvas c, int eye, CalibParams cp) {
        float half = CalibSpec.CARD_HALF;

        path.reset();
        wRect(path, -half, -half, half, half);
        p.setStyle(Paint.Style.FILL);
        p.setColor(0xFF080B11);
        c.drawPath(path, p);

        path.reset();
        wRect(path, -half, -half, half, half);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(Math.max(1f, 0.9f * dp));
        p.setColor(0x33AFC4E6);
        c.drawPath(path, p);

        // 10° grid over the whole card, 5° inside the central 30°.
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(Math.max(1f, 0.8f * dp));
        p.setColor(0x59AFC4E6);
        for (int a = -30; a <= 30; a += 10) {
            if (a == 0) {
                continue;
            }
            path.reset();
            wLine(path, a, -half, a, half);
            wLine(path, -half, a, half, a);
            c.drawPath(path, p);
        }
        p.setColor(0x33AFC4E6);
        for (int a = -15; a <= 15; a += 5) {
            if (a == 0) {
                continue;
            }
            path.reset();
            wLine(path, a, -15, a, 15);
            wLine(path, -15, a, 15, a);
            c.drawPath(path, p);
        }

        p.setColor(0x66FFB454);
        path.reset();
        wLine(path, 0, -half, 0, half);
        wLine(path, -half, 0, half, 0);
        c.drawPath(path, p);

        // Corner crosses and edge circles: barrel/pincushion at the extremes.
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(Math.max(1f, 1.1f * dp));
        p.setColor(0xCCFFFFFF);
        for (int sx2 = -1; sx2 <= 1; sx2 += 2) {
            for (int sy2 = -1; sy2 <= 1; sy2 += 2) {
                path.reset();
                wLine(path, sx2 * 25f - 1.6f, sy2 * 25f, sx2 * 25f + 1.6f, sy2 * 25f);
                wLine(path, sx2 * 25f, sy2 * 25f - 1.6f, sx2 * 25f, sy2 * 25f + 1.6f);
                c.drawPath(path, p);
            }
        }
        p.setColor(0x77FFFFFF);
        p.setStrokeWidth(Math.max(1f, 0.9f * dp));
        float[] ex = {-27, -27, 27, 27, 0, 0, -27, 27};
        float[] ey = {0, 14, 0, -14, 27, -27, 14, -14};
        for (int i = 0; i < ex.length; i++) {
            path.reset();
            wCircle(path, ex[i], ey[i], 2f);
            c.drawPath(path, p);
        }

        // Central 1° cross.
        p.setColor(0xFFFFB454);
        p.setStrokeWidth(Math.max(1.2f, 1.1f * dp));
        path.reset();
        wLine(path, -2.5f, 0, 2.5f, 0);
        wLine(path, 0, -2.5f, 0, 2.5f);
        c.drawPath(path, p);

        // IPD guide: left eye sees the line on its right, right eye on its left,
        // so a correct IPD fuses them into one line.
        float ipd = cp.halfIpdDeg();
        float sign = eye == 0 ? 1f : -1f;
        p.setColor(0xAA6C8CFF);
        p.setStrokeWidth(Math.max(1.4f, 1.2f * dp));
        path.reset();
        wLine(path, sign * ipd, -26f, sign * ipd, 26f);
        c.drawPath(path, p);

        p.setStrokeWidth(Math.max(1f, 0.9f * dp));
        p.setColor(0x886C8CFF);
        for (int t = -4; t <= 4; t++) {
            if (t == 0) {
                continue;
            }
            float x = sign * ipd + t * 0.5f;
            path.reset();
            wLine(path, x, 26f, x, 25.2f);
            c.drawPath(path, p);
        }
        wText(c, "±2°", sign * ipd + sign * 1.2f, 23.6f, 1.15f, 0x996C8CFF, tfReg,
                eye == 0 ? Paint.Align.RIGHT : Paint.Align.LEFT);
        wText(c, eye == 0 ? "左眼对齐线" : "右眼对齐线", sign * ipd, -23.4f, 1.2f,
                0x996C8CFF, tfReg, Paint.Align.CENTER);

        drawSamples(c);
        drawMaskNote(c);

        wText(c, "S5 · 2.5 m · 60°×60°", -27.4f, 26.8f, 1.3f, 0x8893A2BA, tfReg,
                Paint.Align.LEFT);
        wText(c, "网格 10° / 中央 30° 加密", -27.4f, 23.6f, 1.25f, 0x8893A2BA, tfReg,
                Paint.Align.LEFT);
    }

    private void drawSamples(Canvas c) {
        wText(c, "可读性样本 · 三行都应清晰", 16.5f, 17.5f, 1.45f, 0xFFDCE6F7, tfMed,
                Paint.Align.CENTER);
        String[] samples = {"样本 A", "样本 B", "样本 C"};
        // 1.0 / 1.5 / 2.0 mm at 2.5 m is well under one pixel on a 1080p eye
        // panel, so the three tiers are drawn at resolvable sizes instead; the
        // judgement (all three equally soft = refraction) is unchanged.
        float[] sizes = {1.0f, 1.35f, 1.75f};
        float[] pitches = {10.5f, 4.6f, -1.6f};
        for (int i = 0; i < samples.length; i++) {
            wText(c, samples[i], 16.5f, pitches[i], sizes[i], 0xFFF2F7FF, tfReg,
                    Paint.Align.CENTER);
        }
        wText(c, "三行全糊 = 屈光，不是参数", 16.5f, -7.6f, 1.2f, 0xCC8FE3B9, tfReg,
                Paint.Align.CENTER);
        wText(c, "只有边缘糊 = 畸变或镜片边缘", 16.5f, -12.2f, 1.2f, 0xCCFFC97A, tfReg,
                Paint.Align.CENTER);
        wText(c, "显示字号已放大，真机仍是同一判定", 16.5f, -16.4f, 1.05f, 0x7FA7B2C7, tfReg,
                Paint.Align.CENTER);
    }

    private void drawMaskNote(Canvas c) {
        wText(c, "单眼遮盖", -17f, 15.5f, 1.45f, 0xFFDCE6F7, tfMed, Paint.Align.CENTER);
        wText(c, "独眼看中央十字是否比双眼更实", -17f, 10.5f, 1.15f, 0xFFA7B2C7, tfReg,
                Paint.Align.CENTER);
        wText(c, "一眼偏高 → 调该眼垂直偏移 ≤ ±2°", -17f, 6.2f, 1.15f, 0xFFA7B2C7, tfReg,
                Paint.Align.CENTER);
        wText(c, "两条竖线不重合 → 调瞳距 0.5 mm", -17f, 1.9f, 1.15f, 0xFFA7B2C7, tfReg,
                Paint.Align.CENTER);
        wText(c, "直线内侧弯 = 枕形，外侧鼓 = 桶形", -17f, -2.6f, 1.15f, 0xFFA7B2C7, tfReg,
                Paint.Align.CENTER);
        wText(c, "中央清楚边缘成团 → 先降 k2 再试", -17f, -7.1f, 1.15f, 0xFFA7B2C7, tfReg,
                Paint.Align.CENTER);
    }

    private void drawBlurBanner(Canvas c) {
        rect.set(sx(-26), sy(30f), sx(26), sy(24.4f));
        p.setColor(0xE61A1206);
        c.drawRoundRect(rect, 10 * dp, 10 * dp, p);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(Math.max(1f, dp));
        p.setColor(0x66FFB454);
        c.drawRoundRect(rect, 10 * dp, 10 * dp, p);
        p.setStyle(Paint.Style.FILL);
        txt(c, "三档全糊：这通常不是参数问题——请戴眼镜或换带屈光度的镜片",
                sx(0f), rect.centerY() - 1.5f * dp, fontPx(1.25f), 0xFFFFD9A6, tfMed,
                Paint.Align.CENTER);
        txt(c, "畸变微调已收起，确需调整请再凝视「展开畸变」",
                sx(0f), rect.centerY() + 3.4f * dp, fontPx(1.05f), 0xAAFFD9A6, tfReg,
                Paint.Align.CENTER);
    }

    private void drawReticle(Canvas c) {
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(1);
        p.setColor(0x99FFFFFF);
        c.drawCircle(eyeCx, eyeCy, 3.2f * dp, p);
        p.setStyle(Paint.Style.FILL);
        p.setColor(0xFFFFFFFF);
        c.drawCircle(eyeCx, eyeCy, 1.1f * dp, p);
    }

    // ------------------------------------------------------------- controls

    private void drawCapsules(Canvas c, GazeController.Readout g) {
        String[] names = {"左眼", "双眼", "右眼"};
        HudSpec.Target[] targets = CalibSpec.EYES;
        int[] modes = {EYE_LEFT_ONLY, EYE_BOTH, EYE_RIGHT_ONLY};
        for (int i = 0; i < 3; i++) {
            if (!inView(targets[i].yaw, targets[i].pitch)) {
                continue;
            }
            float x = sx(targets[i].yaw);
            float y = sy(targets[i].pitch);
            float w = rad(10f, targets[i].yaw, targets[i].pitch);
            float hh = rad(4.4f, targets[i].yaw, targets[i].pitch);
            boolean active = state.eyeMode == modes[i];
            rect.set(x - w, y - hh, x + w, y + hh);

            p.setStyle(Paint.Style.FILL);
            p.setColor(active ? 0xCCFFB454 : 0x8C0A0E14);
            c.drawRoundRect(rect, hh, hh, p);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(Math.max(1f, dp));
            p.setColor(active ? 0x00FFFFFF : 0x2BFFFFFF);
            c.drawRoundRect(rect, hh, hh, p);
            p.setStyle(Paint.Style.FILL);
            txt(c, names[i], x, y + fontPx(1.35f) * 0.34f, fontPx(1.35f),
                    active ? 0xFF1A1204 : 0xFFE9EEF9, active ? tfMed : tfReg,
                    Paint.Align.CENTER);
            drawFocusRing(c, targets[i], x, y, hh, g);
        }
    }

    private void drawSteps(Canvas c, int eye, CalibParams cp, GazeController.Readout g) {
        int focusRow = -1;
        if (g != null && g.focus != null) {
            focusRow = CalibSpec.rowOf(g.focus.id);
        }

        for (int row = 0; row < 4; row++) {
            boolean folded = state.folded && (row == CalibSpec.ROW_K1_INDEX || row == CalibSpec.ROW_K2_INDEX);
            float pitch = rowPitch(row);
            boolean highlighted = focusRow == row && !folded;

            String[] names = {"瞳距", "k1", "k2", "垂直"};
            String[] values = {
                    cp.ipdText(),
                    cp.k1Text(),
                    cp.k2Text(),
                    vertValue(cp)
            };

            if (highlighted) {
                rect.set(sx(-43f), sy(pitch + 5.6f), sx(43f), sy(pitch - 9.6f));
                p.setStyle(Paint.Style.FILL);
                p.setColor(0x14FFB454);
                c.drawRoundRect(rect, 12 * dp, 12 * dp, p);
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(Math.max(1f, dp));
                p.setColor(0x40FFB454);
                c.drawRoundRect(rect, 12 * dp, 12 * dp, p);
                p.setStyle(Paint.Style.FILL);
            }

            int nameColor = folded ? 0x556F7A91 : (highlighted ? 0xFFFFD9A6 : 0xFFC7D4E8);
            chipText(c, names[row], sx(CalibSpec.LABEL_MINUS),
                    sy(pitch) + fontPx(1.45f) * 0.34f, fontPx(1.45f), nameColor,
                    highlighted ? tfMed : tfReg);
            chipText(c, names[row], sx(CalibSpec.LABEL_PLUS),
                    sy(pitch) + fontPx(1.45f) * 0.34f, fontPx(1.45f), nameColor,
                    highlighted ? tfMed : tfReg);

            if (!folded) {
                drawStepButton(c, minusOf(row), pitch, g, "−");
                drawStepButton(c, plusOf(row), pitch, g, "+");
            } else {
                drawLocked(c, minusOf(row), pitch);
                drawLocked(c, plusOf(row), pitch);
            }

            int valueColor = folded ? 0x446F7A91 : 0xFFDCE6F7;
            float valueY = sy(pitch) + rad(CalibSpec.BUTTON_DEG, CalibSpec.COL_MINUS, pitch)
                    + fontPx(1.3f) * 1.15f;
            if (inView(CalibSpec.COL_MINUS, pitch)) {
                chipText(c, values[row], sx(CalibSpec.COL_MINUS), valueY, fontPx(1.3f),
                        valueColor, folded ? tfReg : tfMed);
            }
            if (inView(CalibSpec.COL_PLUS, pitch)) {
                chipText(c, values[row], sx(CalibSpec.COL_PLUS), valueY, fontPx(1.3f),
                        valueColor, folded ? tfReg : tfMed);
            }
        }

        // "上次保存值"参照行，满足 A/B 对比要求。
        CalibParams s = state.saved;
        chipText(c, "上次保存：" + s.ipdText() + " · k1 " + s.k1Text() + " · k2 " + s.k2Text()
                        + " · " + s.vertText(),
                sx(0f), sy(-23.8f), fontPx(1.15f), 0xFFA7B2C7, tfReg);

        if (state.folded) {
            chipText(c, "屈光模式下畸变微调已收起", sx(0f), sy(CalibSpec.ROW_K1 - 6.8f),
                    fontPx(1.1f), 0xFFFFC97A, tfReg);
        }
    }

    private float rowPitch(int row) {
        switch (row) {
            case CalibSpec.ROW_IPD_INDEX:
                return CalibSpec.ROW_IPD;
            case CalibSpec.ROW_K1_INDEX:
                return CalibSpec.ROW_K1;
            case CalibSpec.ROW_K2_INDEX:
                return CalibSpec.ROW_K2;
            default:
                return CalibSpec.ROW_VERT;
        }
    }

    private HudSpec.Target minusOf(int row) {
        switch (row) {
            case CalibSpec.ROW_IPD_INDEX:
                return CalibSpec.IPD_MINUS;
            case CalibSpec.ROW_K1_INDEX:
                return CalibSpec.K1_MINUS;
            case CalibSpec.ROW_K2_INDEX:
                return CalibSpec.K2_MINUS;
            default:
                return CalibSpec.VERT_MINUS;
        }
    }

    private HudSpec.Target plusOf(int row) {
        switch (row) {
            case CalibSpec.ROW_IPD_INDEX:
                return CalibSpec.IPD_PLUS;
            case CalibSpec.ROW_K1_INDEX:
                return CalibSpec.K1_PLUS;
            case CalibSpec.ROW_K2_INDEX:
                return CalibSpec.K2_PLUS;
            default:
                return CalibSpec.VERT_PLUS;
        }
    }

    private String vertValue(CalibParams cp) {
        if (state.eyeMode == EYE_LEFT_ONLY) {
            return String.format(Locale.US, "L %+.1f°", cp.vertLeftDeg);
        }
        if (state.eyeMode == EYE_RIGHT_ONLY) {
            return String.format(Locale.US, "R %+.1f°", cp.vertRightDeg);
        }
        return String.format(Locale.US, "%+.1f°", cp.vertLeftDeg);
    }

    private void drawStepButton(Canvas c, HudSpec.Target t, float pitch,
                                GazeController.Readout g, String label) {
        if (!inView(t.yaw, pitch)) {
            return;
        }
        float x = sx(t.yaw);
        float y = sy(pitch);
        float r = rad(CalibSpec.BUTTON_DEG, t.yaw, pitch);
        p.setStyle(Paint.Style.FILL);
        p.setColor(0x990A0E14);
        c.drawCircle(x, y, r, p);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(Math.max(1f, dp));
        p.setColor(0x2EFFFFFF);
        c.drawCircle(x, y, r, p);
        p.setStyle(Paint.Style.FILL);
        txt(c, label, x, y + fontPx(2.2f) * 0.34f, fontPx(2.2f), 0xFFE9F0FF, tfLight,
                Paint.Align.CENTER);
        drawFocusRing(c, t, x, y, r, g);
    }

    private void drawLocked(Canvas c, HudSpec.Target t, float pitch) {
        if (!inView(t.yaw, pitch)) {
            return;
        }
        float x = sx(t.yaw);
        float y = sy(pitch);
        float r = rad(CalibSpec.BUTTON_DEG, t.yaw, pitch);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(Math.max(1f, dp));
        p.setColor(0x22FFFFFF);
        c.drawCircle(x, y, r, p);
        p.setStyle(Paint.Style.FILL);
        txt(c, "锁", x, y + fontPx(1.6f) * 0.34f, fontPx(1.6f), 0x556F7A91, tfReg,
                Paint.Align.CENTER);
    }

    private void drawActions(Canvas c, GazeController.Readout g) {
        boolean folded = state.folded;
        drawActionButton(c, CalibSpec.RESET, "恢复", "默认", g,
                state.resetPulse ? 0xFFFFB454 : 0xFFE9EEF9);
        drawActionButton(c, CalibSpec.SAVE, "保存", "继续", g, 0xFFE9EEF9);
        if (folded) {
            drawActionButton(c, CalibSpec.UNLOCK, "展开", "畸变", g, 0xFFE9EEF9);
        }
        if (state.resetPulse) {
            txt(c, "连续 6 步未改善 · 先恢复默认或摘机重选型号",
                    sx(0f), sy(CalibSpec.ACTION_PITCH + 7.4f), fontPx(1.2f), 0xFFFFC97A,
                    tfMed, Paint.Align.CENTER);
        }
    }

    private void drawActionButton(Canvas c, HudSpec.Target t, String line1, String line2,
                                  GazeController.Readout g, int color) {
        if (!inView(t.yaw, t.pitch)) {
            return;
        }
        float x = sx(t.yaw);
        float y = sy(t.pitch);
        float r = rad(CalibSpec.BUTTON_DEG, t.yaw, t.pitch);
        p.setStyle(Paint.Style.FILL);
        p.setColor(0xCC0F1620);
        c.drawCircle(x, y, r, p);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(Math.max(1f, dp));
        p.setColor(0x3DFFFFFF);
        c.drawCircle(x, y, r, p);
        p.setStyle(Paint.Style.FILL);
        float fs = fontPx(1.45f);
        txt(c, line1, x, y - 0.6f * dp, fs, color, tfMed, Paint.Align.CENTER);
        txt(c, line2, x, y + fs * 1.05f, fs, color, tfReg, Paint.Align.CENTER);
        drawFocusRing(c, t, x, y, r, g);
    }

    private void drawFocusRing(Canvas c, HudSpec.Target t, float x, float y, float r,
                               GazeController.Readout g) {
        if (g == null) {
            return;
        }
        float progress = 0f;
        if (g.focus == t) {
            progress = g.progress;
        } else if (g.cancelling == t) {
            progress = g.cancelProgress;
        }
        if (progress <= 0.001f) {
            return;
        }
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(Math.max(2f, 3.2f * dp));
        rect.set(x - r - 6 * dp, y - r - 6 * dp, x + r + 6 * dp, y + r + 6 * dp);
        p.setColor(0x33FFFFFF);
        c.drawArc(rect, -90f, 360f, false, p);
        p.setColor(g.cancelling == t ? 0xFFFF8B8B : 0xFFFFD9A6);
        c.drawArc(rect, -90f, 360f * Math.min(1f, progress), false, p);
        p.setStyle(Paint.Style.FILL);
    }

    private void drawSelfTest(Canvas c, GazeController.Readout g) {
        rect.set(sx(-26), sy(24.4f), sx(26), sy(18.6f));
        p.setColor(0xD90A0E14);
        c.drawRoundRect(rect, 12 * dp, 12 * dp, p);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(Math.max(1f, dp));
        p.setColor(0x26FFFFFF);
        c.drawRoundRect(rect, 12 * dp, 12 * dp, p);
        p.setStyle(Paint.Style.FILL);

        int remain = (int) Math.ceil(state.selfTestRemainMs / 1000.0);
        txt(c, "可读性自测 " + remain + " s · 先看右半三行样本，再看边缘",
                sx(0f), rect.centerY() - 1.5f * dp, fontPx(1.3f), 0xFFDCE6F7, tfMed,
                Paint.Align.CENTER);
        txt(c, "看不清就先别调畸变：越调越像只是没戴眼镜",
                sx(0f), rect.centerY() + 4.0f * dp, fontPx(1.1f), 0xFF8FA0BA, tfReg,
                Paint.Align.CENTER);

        drawActionButton(c, CalibSpec.TEST_BLUR, "三档", "全糊", g, 0xFFFFC97A);
        drawActionButton(c, CalibSpec.TEST_CLEAR, "能看清", "继续", g, 0xFF8FE3B9);
    }

    /** Transient status: head-locked at the bottom of each eye so it is never
     *  hidden by turning towards a button. */
    private void drawHint(Canvas c) {
        float y = H - 26 * dp;
        float size = fontPx(1.2f);
        tp.setTypeface(tfReg);
        tp.setTextSize(size);
        tp.setTextAlign(Paint.Align.CENTER);
        float tw = Math.min(halfW * 1.7f, tp.measureText(state.hint));
        rect.set(eyeCx - tw * 0.5f - 12 * dp, y - size * 1.15f,
                eyeCx + tw * 0.5f + 12 * dp, y + size * 0.55f);
        p.setStyle(Paint.Style.FILL);
        p.setColor(0xE60A0E14);
        c.drawRoundRect(rect, 9 * dp, 9 * dp, p);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(Math.max(1f, dp));
        p.setColor(0x33FFFFFF);
        c.drawRoundRect(rect, 9 * dp, 9 * dp, p);
        p.setStyle(Paint.Style.FILL);
        txt(c, state.hint, eyeCx, y, size, 0xFFE9EEF9, tfReg, Paint.Align.CENTER);
    }

    // ------------------------------------------------------------- touch

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                lastX = e.getX();
                lastY = e.getY();
                return true;
            case MotionEvent.ACTION_MOVE: {
                float dx = e.getX() - lastX;
                float dy = e.getY() - lastY;
                lastX = e.getX();
                lastY = e.getY();
                if (listener != null) {
                    listener.onLook(-dx / Math.max(1f, 18f * dp), dy / Math.max(1f, 18f * dp));
                }
                return true;
            }
            default:
                return true;
        }
    }
}
