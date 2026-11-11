package com.yunyu697.gazeline;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.view.MotionEvent;
import android.view.View;

/**
 * Static UI skeleton for Gazeline POC.
 * Screens: launch wordmark, head-locked HUD preview (S1/S2), S5 calibration card.
 * All eye-space geometry is expressed in degrees and converted with the 90 deg / eye FOV assumption.
 */
public class StageView extends View {

    public static final int SCR_SPLASH = 0;
    public static final int SCR_HOME = 1;
    /** Static HUD demo; reached from Settings only. */
    public static final int SCR_HUD = 2;
    public static final int SCR_CALIB = 3;
    public static final int SCR_SETTINGS = 4;

    private static final float FOV = 90f;

    private static final int AMBER = 0xFFFFB454;
    private static final int TEXT = 0xFFE9EEF9;
    private static final int DIM = 0xFF93A2BA;

    private int screen = SCR_SPLASH;
    private boolean hudVisible = true;
    private boolean showGrid = false;

    interface OnOpenVideoListener {
        void onOpenVideo();
    }

    private OnOpenVideoListener openVideoListener;

    public void setOnOpenVideoListener(OnOpenVideoListener l) {
        openVideoListener = l;
    }

    interface OnEnterVrListener {
        void onEnterVr();
    }

    private OnEnterVrListener enterVrListener;

    public void setOnEnterVrListener(OnEnterVrListener l) {
        enterVrListener = l;
    }

    interface OnHistoryPlayListener {
        void onHistoryPlay(String path, String mode, long positionMs);
    }

    private OnHistoryPlayListener historyPlayListener;

    public void setOnHistoryPlayListener(OnHistoryPlayListener l) {
        historyPlayListener = l;
    }

    /** Reloads the resume list; called when the phone page returns to the front. */
    public void reloadHistory() {
        history.clear();
        history.addAll(PlayHistory.load(getContext()));
        invalidate();
    }

    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tp = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path clipPath = new Path();
    private final RectF eye = new RectF();
    private final RectF chrome = new RectF();
    /** Hit boxes of the bottom bar, recomputed by every draw. */
    private final java.util.List<RectF> barZones = new java.util.ArrayList<>();
    private final RectF wakeZone = new RectF();
    private final RectF undoZone = new RectF();
    private final RectF startZone = new RectF();
    // home: the resume card is the only focus, the two bottom buttons are the
    // only primary entries, the gear hides everything low-frequency.
    private final RectF homeGear = new RectF();
    private final RectF homeCard = new RectF();
    /** Home column bounds: full width in portrait, a centred column in landscape. */
    private float homeLeft;
    private float homeRight;
    private final RectF homeLibrary = new RectF();
    private final RectF homeVr = new RectF();
    private final RectF homeAllZone = new RectF();
    private final RectF settingsBack = new RectF();
    private final RectF[] settingsRows = {new RectF(), new RectF(), new RectF()};
    private final java.util.List<RectF> historyZones = new java.util.ArrayList<>();
    private final java.util.List<PlayHistory.Entry> history = new java.util.ArrayList<>();
    private final RectF ipdTrack = new RectF();
    private final RectF[] modelChips = {new RectF(), new RectF(), new RectF()};
    private final RectF[] screenChips = {new RectF(), new RectF(), new RectF()};
    private boolean ipdDragging;

    private String undoPath;
    private String undoMode;
    private long undoPositionMs;
    private long undoUntil;

    interface OnUndoPlayListener {
        void onUndoPlay(String path, String mode, long positionMs);
    }

    private OnUndoPlayListener undoListener;

    public void setOnUndoPlayListener(OnUndoPlayListener l) {
        undoListener = l;
    }

    interface OnStartCalibListener {
        void onStartCalib();
    }

    interface OnFlipImageListener {
        void onFlipImage();
    }

    private OnFlipImageListener flipImageListener;

    public void setOnFlipImageListener(OnFlipImageListener l) {
        flipImageListener = l;
    }

    private OnStartCalibListener startCalibListener;

    public void setOnStartCalibListener(OnStartCalibListener l) {
        startCalibListener = l;
    }

    private CalibParams calib;

    private CalibParams calibParams() {
        if (calib == null) {
            calib = CalibParams.load(getContext());
        }
        return calib;
    }

    /** Called after the VR page returns so the phone page shows what was saved. */
    public void reloadCalib() {
        calib = CalibParams.load(getContext());
        invalidate();
    }

    private void saveCalib() {
        calibParams().save(getContext());
        invalidate();
    }

    /** S4 left the player; offer a 5 s "continue" affordance on the phone side. */
    public void showUndo(String path, String mode, long positionMs) {
        undoPath = path;
        undoMode = mode;
        undoPositionMs = positionMs;
        undoUntil = android.os.SystemClock.uptimeMillis() + 5000;
        invalidate();
    }

    public void clearUndo() {
        undoPath = null;
        invalidate();
    }

    private float W, H, dp, side, ppd, cx, cy;
    /** True when the window is wider than tall; the phone page then splits into two columns. */
    private boolean landscape;
    /** Right-hand column that carries the info block, the buttons and the resume list. */
    private final RectF panel = new RectF();
    private Typeface tfReg, tfMed, tfLight;

    public StageView(Context c) {
        super(c);
        tfLight = Typeface.create("sans-serif-light", Typeface.NORMAL);
        tfReg = Typeface.create("sans-serif", Typeface.NORMAL);
        tfMed = Typeface.create("sans-serif-medium", Typeface.NORMAL);
        setFocusable(true);
    }

    public void showHome() {
        screen = SCR_HOME;
        invalidate();
    }

    public void showHud() {
        screen = SCR_HUD;
        invalidate();
    }

    public void showSettings() {
        screen = SCR_SETTINGS;
        invalidate();
    }

    public boolean back() {
        if (screen == SCR_HUD || screen == SCR_CALIB) {
            screen = SCR_SETTINGS;
            invalidate();
            return true;
        }
        if (screen == SCR_SETTINGS) {
            screen = SCR_HOME;
            invalidate();
            return true;
        }
        if (screen == SCR_SPLASH) {
            showHome();
            return true;
        }
        return false;
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        W = w;
        H = h;
        dp = getResources().getDisplayMetrics().density;
        landscape = w > h;
        float barH = landscape ? 54 * dp : 62 * dp;

        if (landscape) {
            // Landscape: square eye preview on the left, everything else stacked in
            // the right column, bottom bar kept as a thin footer.
            float margin = 18 * dp;
            float barY = H - 12 * dp - barH;
            chrome.set(margin, barY, W - margin, barY + barH);
            float top = 14 * dp;
            float bottom = chrome.top - 12 * dp;
            float contentH = Math.max(1f, bottom - top);
            side = Math.min(contentH, W * 0.44f);
            eye.set(margin, top + (contentH - side) / 2f, margin + side,
                    top + (contentH - side) / 2f + side);
            panel.set(eye.right + 22 * dp, top, W - margin, bottom);
        } else {
            side = Math.min(w, h * 0.66f);
            float top = Math.max(26 * dp, h * 0.045f);
            eye.set((w - side) / 2f, top, (w - side) / 2f + side, top + side);
            chrome.set(16 * dp, H - 16 * dp - barH, W - 16 * dp, H - 16 * dp);
            panel.setEmpty();
        }

        ppd = side / FOV;
        cx = eye.centerX();
        cy = eye.centerY();
        wakeZone.set(X(-12.5f), Y(-40.5f), X(12.5f), Y(-37.5f));

        // Home: one resume card, then the list, then the two primary entries.
        // The gear sits in the header and never moves. Landscape keeps the same
        // single column but centred, so the page no longer hugs the left edge.
        float m = 20 * dp;
        if (landscape) {
            float colW = Math.min(W - 2 * m, 640 * dp);
            homeLeft = (W - colW) / 2f;
            homeRight = homeLeft + colW;
        } else {
            homeLeft = m;
            homeRight = W - m;
        }
        homeGear.set(homeRight - 46 * dp, 8 * dp, homeRight, 54 * dp);
        settingsBack.set(m - 6 * dp, 4 * dp, m + 62 * dp, 56 * dp);

        float actionsH = landscape ? 50 * dp : 56 * dp;
        float actionsBottom = H - (landscape ? 16 : 22) * dp;
        float actionsTop = actionsBottom - actionsH;

        if (landscape) {
            homeCard.set(homeLeft, 60 * dp, homeRight, 60 * dp + 136 * dp);
            float gap = 10 * dp;
            float vrW = (homeRight - homeLeft - gap) * 0.34f;
            homeLibrary.set(homeLeft, actionsTop, homeRight - gap - vrW, actionsBottom);
            homeVr.set(homeLibrary.right + gap, actionsTop, homeRight, actionsBottom);
        } else {
            float cardH = Math.min(188 * dp, H * 0.28f);
            homeCard.set(m, 70 * dp, W - m, 70 * dp + cardH);
            float gap = 10 * dp;
            float vrW = (W - 2 * m - gap) * 0.34f;
            homeLibrary.set(m, actionsTop, W - m - gap - vrW, actionsBottom);
            homeVr.set(homeLibrary.right + gap, actionsTop, W - m, actionsBottom);
        }
        homeAllZone.setEmpty();
    }

    private float X(float yaw) {
        return cx + yaw * ppd;
    }

    private float Y(float pitch) {
        return cy - pitch * ppd;
    }

    @Override
    protected void onDraw(Canvas c) {
        c.drawColor(0xFF05070A);
        if (screen == SCR_SPLASH) {
            drawSplash(c);
        } else if (screen == SCR_HOME) {
            drawHomeScreen(c);
        } else if (screen == SCR_HUD) {
            drawHudScreen(c);
        } else if (screen == SCR_SETTINGS) {
            drawSettingsScreen(c);
        } else {
            drawCalibScreen(c);
        }
    }

    // ---------------------------------------------------------------- splash

    private void drawSplash(Canvas c) {
        p.setShader(new RadialGradient(W / 2f, H * 0.32f, W * 0.95f,
                new int[]{0x3367E8F9, 0x0A67E8F9, 0x0005070A}, new float[]{0f, 0.45f, 1f}, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, W, H, p);
        p.setShader(null);

        float markW = landscape ? Math.min(W * 0.17f, H * 0.42f) : W * 0.21f;
        float markH = markW * 0.62f;
        float markY = landscape ? H * 0.28f : H * 0.305f;
        float titleY = landscape ? H * 0.60f : H * 0.445f;
        float ruleY = landscape ? H * 0.645f : H * 0.472f;
        float subY = landscape ? H * 0.700f : H * 0.515f;
        float titleSize = landscape ? 26 * dp : W * 0.098f;
        float subSize = landscape ? 10 * dp : W * 0.030f;
        float l = W / 2f - markW / 2f, r = W / 2f + markW / 2f;
        float t = markY - markH / 2f, b = markY + markH / 2f;
        float rr = markH * 0.26f;

        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(1.6f * dp);
        p.setColor(0xFF67E8F9);

        RectF arch = new RectF(W / 2f - markW * 0.36f, t - markH * 0.62f,
                W / 2f + markW * 0.36f, t + markH * 0.18f);
        c.drawArc(arch, 180, 180, false, p);

        Path visor = new Path();
        visor.moveTo(l + rr, t);
        visor.lineTo(r - rr, t);
        visor.quadTo(r, t, r, t + rr);
        visor.lineTo(r, b - rr);
        visor.quadTo(r, b, r - rr, b);
        visor.lineTo(W / 2f + markW * 0.11f, b);
        visor.lineTo(W / 2f, b - markH * 0.09f);
        visor.lineTo(W / 2f - markW * 0.11f, b);
        visor.lineTo(l + rr, b);
        visor.quadTo(l, b, l, b - rr);
        visor.lineTo(l, t + rr);
        visor.quadTo(l, t, l + rr, t);
        visor.close();
        c.drawPath(visor, p);

        float triH = markH * 0.42f;
        float triW = triH * 0.78f;
        float triCx = W / 2f - markW * 0.02f;
        p.setStyle(Paint.Style.FILL);
        p.setShader(new LinearGradient(triCx - triW / 2, markY - triH / 2, triCx + triW / 2, markY + triH / 2,
                0xFF67E8F9, 0xFF2563EB, Shader.TileMode.CLAMP));
        Path tri = new Path();
        tri.moveTo(triCx - triW / 2, markY - triH / 2);
        tri.lineTo(triCx + triW / 2, markY);
        tri.lineTo(triCx - triW / 2, markY + triH / 2);
        tri.close();
        c.drawPath(tri, p);
        p.setShader(null);
        p.setStyle(Paint.Style.STROKE);

        tp.setLetterSpacing(0.22f);
        txt(c, "GAZELINE", W / 2f, titleY, titleSize, 0xFFF4F8FF, tfLight, Paint.Align.CENTER);
        tp.setLetterSpacing(0f);

        p.setColor(0x5567E8F9);
        c.drawRect(W / 2f - W * 0.10f, ruleY, W / 2f + W * 0.10f, ruleY + 1.2f * dp, p);

        tp.setLetterSpacing(0.28f);
        txt(c, "CARDBOARD VR PLAYER", W / 2f, subY, subSize, 0xFF8FA0BA, tfReg, Paint.Align.CENTER);
        tp.setLetterSpacing(0f);

        txt(c, versionText(), W / 2f, H - (landscape ? 30 : 54) * dp, 11 * dp, 0x55A7B2C7,
                tfReg, Paint.Align.CENTER);
    }

    private String versionLabel;

    /** Real installed versionName, so the splash can never lie about the build. */
    private String versionText() {
        if (versionLabel == null) {
            String v = "";
            try {
                v = getContext().getPackageManager()
                        .getPackageInfo(getContext().getPackageName(), 0).versionName;
            } catch (Throwable ignored) {
            }
            versionLabel = (v == null || v.length() == 0) ? "" : "v" + v;
        }
        return versionLabel;
    }

    // ---------------------------------------------------------------- HUD

    private void drawHudScreen(Canvas c) {
        clipPath.reset();
        clipPath.addRoundRect(eye, 22 * dp, 22 * dp, Path.Direction.CW);
        c.save();
        c.clipPath(clipPath);

        drawScene(c);
        if (showGrid) {
            drawAngleGrid(c);
        }
        drawRings(c);
        if (hudVisible) {
            drawHud(c);
            drawReticle(c);
        } else {
            drawWakeState(c);
        }
        c.restore();

        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(1);
        p.setColor(0x1FFFFFFF);
        c.drawRoundRect(eye, 22 * dp, 22 * dp, p);
        p.setStyle(Paint.Style.FILL);

        txt(c, "GAZELINE", eye.left + 14 * dp, eye.top + 22 * dp, 9.5f * dp, 0x66C7D4E8, tfMed, Paint.Align.LEFT);

        drawHudChrome(c);
        drawBar(c, new String[]{hudVisible ? "收起 HUD" : "叫回 HUD", "返回设置"}, -1);
    }

    // ---------------------------------------------------------------- home

    private void drawHomeScreen(Canvas c) {
        p.setShader(new LinearGradient(0, 0, 0, H,
                new int[]{0xFF0B1220, 0xFF05070A}, null, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, W, H, p);
        p.setShader(null);

        tp.setLetterSpacing(0.16f);
        txt(c, "GAZELINE", homeLeft, 32 * dp, 15 * dp, 0xFFF4F8FF, tfLight, Paint.Align.LEFT);
        tp.setLetterSpacing(0f);
        txt(c, versionText(), homeLeft, 48 * dp, 9.5f * dp, 0xFF6F8099, tfReg, Paint.Align.LEFT);

        drawGear(c);
        drawHomeCard(c);
        if (!history.isEmpty()) {
            drawHomeHistory(c);
        }
        drawHomeActions(c);
        if (undoPath != null) {
            drawUndo(c);
        }
    }

    private void drawGear(Canvas c) {
        float gx = homeGear.centerX();
        float gy = homeGear.centerY();
        float r = 8.2f * dp;
        p.setColor(0x14FFFFFF);
        c.drawCircle(gx, gy, 19 * dp, p);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setColor(0xFFA7B2C7);
        // Teeth: eight short thick spokes on the rim, not rays off a disc.
        p.setStrokeWidth(3.4f * dp);
        for (int i = 0; i < 8; i++) {
            double a = Math.PI * i / 4.0 + Math.PI / 8.0;
            float in = r * 0.72f;
            float out = r * 1.02f;
            c.drawLine(gx + (float) Math.cos(a) * in,
                    gy + (float) Math.sin(a) * in,
                    gx + (float) Math.cos(a) * out,
                    gy + (float) Math.sin(a) * out, p);
        }
        // Body ring and the hole in the middle: reads as a cog, not a sun.
        p.setStrokeWidth(2.4f * dp);
        c.drawCircle(gx, gy, r * 0.62f, p);
        p.setStrokeWidth(2f * dp);
        c.drawCircle(gx, gy, r * 0.26f, p);
        p.setStrokeCap(Paint.Cap.BUTT);
        p.setStyle(Paint.Style.FILL);
    }

    /** The one focal card: resume the newest film, or start with the library. */
    private void drawHomeCard(Canvas c) {
        cardBox(c, homeCard);
        float left = homeCard.left + 18 * dp;
        float right = homeCard.right - 18 * dp;
        boolean has = !history.isEmpty();

        txt(c, has ? "继续观看" : "开始观看", left, homeCard.top + 26 * dp,
                11.5f * dp, 0xFF93A2BA, tfMed, Paint.Align.LEFT);

        if (has) {
            PlayHistory.Entry e = history.get(0);
            String name = e.name == null || e.name.length() == 0 ? e.source : e.name;
            txt(c, fitText(name, right - left, 16 * dp, tfMed), left,
                    homeCard.top + 56 * dp, 16 * dp, 0xFFE9EEF9, tfMed, Paint.Align.LEFT);
            txt(c, modeText(e) + " · " + posText(e), left, homeCard.top + 78 * dp,
                    10.5f * dp, 0xFF9AA8BE, tfReg, Paint.Align.LEFT);
            float progress = e.durationMs > 0
                    ? Math.min(1f, Math.max(0f, (float) e.positionMs / e.durationMs)) : 0f;
            float barY = homeCard.top + 100 * dp;
            p.setColor(0x22FFFFFF);
            c.drawRect(left, barY, right, barY + 3 * dp, p);
            p.setColor(0xCCFFB454);
            c.drawRect(left, barY, left + (right - left) * progress, barY + 3 * dp, p);
        } else {
            txt(c, "还没有观看记录", left, homeCard.top + 56 * dp, 16 * dp,
                    0xFFE9EEF9, tfMed, Paint.Align.LEFT);
            txt(c, "从片库或局域网共享选一个视频", left, homeCard.top + 78 * dp,
                    10.5f * dp, 0xFF9AA8BE, tfReg, Paint.Align.LEFT);
        }

        RectF btn = new RectF(left, homeCard.bottom - 58 * dp, right, homeCard.bottom - 16 * dp);
        p.setShader(new LinearGradient(btn.left, btn.top, btn.right, btn.bottom,
                0xFFFFC46B, 0xFFF0A33C, Shader.TileMode.CLAMP));
        c.drawRoundRect(btn, btn.height() / 2f, btn.height() / 2f, p);
        p.setShader(null);

        float tx = btn.left + 20 * dp;
        float cy2 = btn.centerY();
        Path tri = new Path();
        tri.moveTo(tx, cy2 - 6.5f * dp);
        tri.lineTo(tx + 11 * dp, cy2);
        tri.lineTo(tx, cy2 + 6.5f * dp);
        tri.close();
        p.setColor(0xFF1A1204);
        c.drawPath(tri, p);
        txt(c, has ? "继续播放" : "打开片库", tx + 20 * dp, cy2 + 5.2f * dp,
                14 * dp, 0xFF1A1204, tfMed, Paint.Align.LEFT);
        txt(c, has ? "从断点接着看" : "本地 / SMB", btn.right - 14 * dp, cy2 + 4.4f * dp,
                9.5f * dp, 0xCC1A1204, tfReg, Paint.Align.RIGHT);
    }

    /** At most three rows; the list never competes with the card or the buttons. */
    private void drawHomeHistory(Canvas c) {
        historyZones.clear();
        float left = homeCard.left;
        float right = homeCard.right;
        float top = homeCard.bottom + 24 * dp;
        float limit = homeLibrary.top - 12 * dp;
        if (undoPath != null) {
            limit = Math.min(limit, homeLibrary.top - 62 * dp);
        }
        txt(c, "最近播放", left, top, 12 * dp, 0xFF93A2BA, tfMed, Paint.Align.LEFT);
        homeAllZone.set(right - 64 * dp, top - 16 * dp, right, top + 8 * dp);
        txt(c, "全部 ›", right, top, 11 * dp, 0xFF7A8DA8, tfReg, Paint.Align.RIGHT);

        float y = top + 12 * dp;
        float rowH = landscape ? 42 * dp : 52 * dp;
        float gap = landscape ? 6 * dp : 8 * dp;
        int max = (int) Math.min(3, Math.max(0, (limit - y + gap) / (rowH + gap)));
        max = Math.min(max, history.size());
        for (int i = 0; i < max; i++) {
            PlayHistory.Entry e = history.get(i);
            RectF r = new RectF(left, y, right, y + rowH);
            historyZones.add(r);
            p.setColor(0xE60B1018);
            c.drawRoundRect(r, 14 * dp, 14 * dp, p);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(1);
            p.setColor(0x1FFFFFFF);
            c.drawRoundRect(r, 14 * dp, 14 * dp, p);
            p.setStyle(Paint.Style.FILL);

            String name = e.name == null || e.name.length() == 0 ? e.source : e.name;
            if (landscape) {
                txt(c, fitText(name, r.width() - 250 * dp, 13.5f * dp, tfMed), r.left + 16 * dp,
                        r.centerY() + 5 * dp, 13.5f * dp, 0xFFE9EEF9, tfMed, Paint.Align.LEFT);
                txt(c, modeText(e) + " · " + posText(e), r.right - 62 * dp,
                        r.centerY() + 4 * dp, 10 * dp, 0xFF9AA8BE, tfReg, Paint.Align.RIGHT);
                txt(c, "继续", r.right - 16 * dp, r.centerY() + 5 * dp, 12 * dp,
                        0xFFFFB454, tfMed, Paint.Align.RIGHT);
            } else {
                txt(c, fitText(name, r.width() - 150 * dp, 13.5f * dp, tfMed), r.left + 16 * dp,
                        r.top + 21 * dp, 13.5f * dp, 0xFFE9EEF9, tfMed, Paint.Align.LEFT);
                txt(c, modeText(e) + " · " + posText(e), r.left + 16 * dp,
                        r.top + 37 * dp, 10 * dp, 0xFF9AA8BE, tfReg, Paint.Align.LEFT);
                txt(c, "继续", r.right - 16 * dp, r.centerY() + 4.5f * dp, 12 * dp,
                        0xFFFFB454, tfMed, Paint.Align.RIGHT);
            }

            float progress = e.durationMs > 0
                    ? Math.min(1f, Math.max(0f, (float) e.positionMs / e.durationMs)) : 0f;
            float barY = r.bottom - 4 * dp;
            p.setColor(0x22FFFFFF);
            c.drawRect(r.left + 16 * dp, barY, r.right - 16 * dp, barY + 2 * dp, p);
            p.setColor(0xCCFFB454);
            c.drawRect(r.left + 16 * dp, barY,
                    r.left + 16 * dp + (r.width() - 32 * dp) * progress, barY + 2 * dp, p);
            y += rowH + gap;
        }
    }

    /** The only two primary entries: library is filled, headset entry is outlined. */
    private void drawHomeActions(Canvas c) {
        p.setStyle(Paint.Style.FILL);
        p.setShader(new LinearGradient(homeLibrary.left, homeLibrary.top,
                homeLibrary.right, homeLibrary.bottom, 0xFFFFC46B, 0xFFF0A33C, Shader.TileMode.CLAMP));
        c.drawRoundRect(homeLibrary, homeLibrary.height() / 2f, homeLibrary.height() / 2f, p);
        p.setShader(null);

        float cy2 = homeLibrary.centerY();
        float tx = homeLibrary.left + 20 * dp;
        Path tri = new Path();
        tri.moveTo(tx, cy2 - 7.5f * dp);
        tri.lineTo(tx + 13 * dp, cy2);
        tri.lineTo(tx, cy2 + 7.5f * dp);
        tri.close();
        p.setColor(0xFF1A1204);
        c.drawPath(tri, p);
        txt(c, "片库", tx + 22 * dp, cy2 + 5.4f * dp, 15 * dp, 0xFF1A1204, tfMed, Paint.Align.LEFT);
        txt(c, "本地 / SMB", homeLibrary.right - 14 * dp, cy2 + 4.6f * dp,
                9.8f * dp, 0xCC1A1204, tfReg, Paint.Align.RIGHT);

        p.setColor(0x1AFFFFFF);
        c.drawRoundRect(homeVr, homeVr.height() / 2f, homeVr.height() / 2f, p);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(1.2f * dp);
        p.setColor(0x6667E8F9);
        c.drawRoundRect(homeVr, homeVr.height() / 2f, homeVr.height() / 2f, p);
        p.setStyle(Paint.Style.FILL);
        txt(c, "进入 VR", homeVr.centerX(), homeVr.centerY() - 1 * dp, 14 * dp,
                0xFF9FE9F7, tfMed, Paint.Align.CENTER);
        txt(c, "头显片库", homeVr.centerX(), homeVr.centerY() + 13 * dp, 9 * dp,
                0x8867E8F9, tfReg, Paint.Align.CENTER);
    }

    private String modeText(PlayHistory.Entry e) {
        return "sbs".equals(e.mode) ? "SBS" : "360°";
    }

    private String posText(PlayHistory.Entry e) {
        return e.durationMs > 0
                ? timeText(e.positionMs) + " / " + timeText(e.durationMs)
                : timeText(e.positionMs);
    }

    private String fitText(String text, float maxWidth, float size, Typeface tf) {
        if (text == null) {
            return "";
        }
        tp.setTypeface(tf);
        tp.setTextSize(size);
        if (tp.measureText(text) <= maxWidth) {
            return text;
        }
        int n = text.length();
        while (n > 1 && tp.measureText(text.substring(0, n) + "…") > maxWidth) {
            n--;
        }
        return text.substring(0, n) + "…";
    }

    private String fit(String text, float maxWidth) {
        if (text == null) {
            return "";
        }
        tp.setTextSize(13.5f * dp);
        tp.setTypeface(tfMed);
        if (tp.measureText(text) <= maxWidth) {
            return text;
        }
        int n = text.length();
        while (n > 1 && tp.measureText(text.substring(0, n) + "…") > maxWidth) {
            n--;
        }
        return text.substring(0, n) + "…";
    }

    private static String timeText(long ms) {
        long total = Math.max(0, ms) / 1000L;
        long h = total / 3600L;
        long m = (total / 60L) % 60L;
        long s = total % 60L;
        return h > 0 ? String.format("%02d:%02d:%02d", h, m, s)
                : String.format("%02d:%02d", m, s);
    }

    private void drawUndo(Canvas c) {
        float h = 42 * dp;
        undoZone.set(homeLibrary.left, homeLibrary.top - 10 * dp - h,
                homeLibrary.right, homeLibrary.top - 10 * dp);
        p.setStyle(Paint.Style.FILL);
        p.setColor(0xE60B1018);
        c.drawRoundRect(undoZone, h * 0.5f, h * 0.5f, p);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(1.2f * dp);
        p.setColor(0x99FFB454);
        c.drawRoundRect(undoZone, h * 0.5f, h * 0.5f, p);
        p.setStyle(Paint.Style.FILL);

        txt(c, "已退出播放 · 继续播放", undoZone.left + 18 * dp,
                undoZone.centerY() + 4 * dp, 13 * dp, 0xFFE9EEF9, tfMed, Paint.Align.LEFT);
        txt(c, "5 s 内可撤销", undoZone.right - 18 * dp,
                undoZone.centerY() + 4 * dp, 10.5f * dp, 0x99FFB454, tfReg, Paint.Align.RIGHT);
    }

    private void drawScene(Canvas c) {
        p.setShader(new LinearGradient(0, eye.top, 0, eye.bottom,
                new int[]{0xFF1A2840, 0xFF0C1624, 0xFF05070C}, new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP));
        c.drawRect(eye, p);
        p.setShader(null);

        float horizon = Y(-5f);
        Path hills = new Path();
        hills.moveTo(eye.left, horizon);
        hills.lineTo(X(-46), Y(-5f));
        hills.lineTo(X(-34), Y(-7.5f));
        hills.lineTo(X(-24), Y(-5.6f));
        hills.lineTo(X(-12), Y(-8.4f));
        hills.lineTo(X(2), Y(-4.8f));
        hills.lineTo(X(16), Y(-7.2f));
        hills.lineTo(X(30), Y(-4.4f));
        hills.lineTo(X(46), Y(-6.2f));
        hills.lineTo(eye.right, horizon);
        hills.lineTo(eye.right, eye.bottom);
        hills.lineTo(eye.left, eye.bottom);
        hills.close();
        p.setColor(0xFF070C13);
        c.drawPath(hills, p);

        p.setColor(0x553A6A8E);
        c.drawRect(eye.left, horizon, eye.right, horizon + 1, p);

        p.setColor(0x22FFFFFF);
        for (int i = 0; i < 4; i++) {
            float yaw = new float[]{-30, -9, 12, 26}[i];
            float wpx = 0.5f * ppd;
            c.drawRect(X(yaw), Y(-5f), X(yaw) + wpx, Y(1.5f), p);
        }

        p.setShader(new RadialGradient(cx, cy, side * 0.52f,
                new int[]{0x00000000, 0x00000000, 0xAA000000}, new float[]{0f, 0.62f, 1f}, Shader.TileMode.CLAMP));
        c.drawRect(eye, p);
        p.setShader(null);
    }

    private void drawAngleGrid(Canvas c) {
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(1);
        for (int a = -40; a <= 40; a += 10) {
            if (a == 0) continue;
            p.setColor(0x22FFB454);
            c.drawLine(X(a), eye.top, X(a), eye.bottom, p);
            c.drawLine(eye.left, Y(a), eye.right, Y(a), p);
        }
        p.setStyle(Paint.Style.FILL);
        for (int a = -40; a <= 40; a += 10) {
            if (a == 0) continue;
            txt(c, (a > 0 ? "+" : "") + a + "°", X(a) + 3, eye.top + 12, 8 * dp, 0x66FFB454, tfReg, Paint.Align.LEFT);
            if (a < 0) {
                txt(c, a + "°", eye.left + 6, Y(a) - 4, 8 * dp, 0x66FFB454, tfReg, Paint.Align.LEFT);
            }
        }
    }

    private void drawRings(Canvas c) {
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(1);
        p.setColor(0x18FFFFFF);
        c.drawCircle(cx, cy, 30 * ppd, p);
        c.drawCircle(cx, cy, 60 * ppd, p);
        p.setStyle(Paint.Style.FILL);
    }

    private void drawReticle(Canvas c) {
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(1);
        p.setColor(0xCCFFFFFF);
        c.drawCircle(cx, cy, 5 * dp, p);
        p.setStyle(Paint.Style.FILL);
        p.setColor(0xFFFFFFFF);
        c.drawCircle(cx, cy, 1.4f * dp, p);

        p.setColor(0x66FFB454);
        c.drawRect(cx - 11 * dp, cy - 0.6f, cx - 6 * dp, cy + 0.6f, p);
        c.drawRect(cx + 6 * dp, cy - 0.6f, cx + 11 * dp, cy + 0.6f, p);
    }

    private void drawHud(Canvas c) {
        // timeline @ pitch -11.5
        float ty = Y(-11.5f);
        float left = X(-22), right = X(22);
        p.setColor(0x33FFFFFF);
        c.drawRoundRect(left, ty - 2, right, ty + 2, 2, 2, p);
        p.setColor(0x66FFFFFF);
        c.drawRoundRect(left, ty - 2, left + (right - left) * 0.58f, ty + 2, 2, 2, p);
        p.setColor(AMBER);
        c.drawRoundRect(left, ty - 2, left + (right - left) * 0.38f, ty + 2, 2, 2, p);
        p.setColor(0xFFFFFFFF);
        c.drawCircle(left + (right - left) * 0.38f, ty, 4, p);

        txt(c, "00:12:40", left, Y(-9.4f), 12.6f, 0xFFCFDAEA, tfReg, Paint.Align.LEFT);
        txt(c, "00:34:08", right, Y(-9.4f), 12.6f, 0xFFCFDAEA, tfReg, Paint.Align.RIGHT);

        button(c, -30, -18, 8, ICON_CC, false, 1f, null);
        button(c, -16, -18, 8, ICON_BACK, false, 1f, null);
        button(c, 16, -18, 8, ICON_FWD, false, 1f, null);
        button(c, 30, -18, 8, ICON_MENU, false, 1f, null);
        button(c, 0, -18, 10, ICON_PAUSE, true, 1f, 0.64f);

        String[] labels = {"字幕", "−10 s", "暂停", "+10 s", "菜单"};
        float[] yaws = {-30, -16, 0, 16, 30};
        for (int i = 0; i < labels.length; i++) {
            txt(c, labels[i], X(yaws[i]), Y(-23.4f), 10.2f, 0x88D7E2F2, tfReg, Paint.Align.CENTER);
        }

        // focus hint above the main button
        float lw = 104, lh = 26;
        p.setColor(0xCC000000);
        c.drawRoundRect(cx - lw / 2, Y(-12.9f) - lh, cx + lw / 2, Y(-12.9f), 13, 13, p);
        txt(c, "继续注视 0.4 秒", cx, Y(-12.9f) - 8, 11.4f, 0xFFFFDCA8, tfMed, Paint.Align.CENTER);

        // recenter key (-37, -25), diameter 7.5
        float rx = X(-37), ry = Y(-25), rr = 7.5f / 2f * ppd;
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(1.6f);
        p.setColor(0x8CFFFFFF);
        c.drawCircle(rx, ry, rr, p);
        p.setStrokeWidth(1.2f);
        p.setColor(0x33FFFFFF);
        c.drawArc(rx - rr - 5, ry - rr - 5, rx + rr + 5, ry + rr + 5, -90, 360, false, p);
        p.setColor(0xAAFFB454);
        c.drawArc(rx - rr - 5, ry - rr - 5, rx + rr + 5, ry + rr + 5, -90, 360 * 0.67f, false, p);
        p.setStyle(Paint.Style.FILL);

        paintIcon(c, ICON_RECENTER, rx, ry, rr * 0.72f, 0xFFE9F0FF);
        txt(c, "回中 · 1.8 s", rx, Y(-20.2f), 10.6f, 0x99FFD9A6, tfReg, Paint.Align.CENTER);

        // subtitle line @ -33
        tp.setShadowLayer(4, 0, 1.2f * dp, 0xE6000000);
        tp.setTypeface(tfReg);
        txt(c, "这是一条字幕样本 · 用来检查画面下缘的可读性与遮挡", cx, Y(-33f), 1.36f * ppd, 0xFFEFF5FF, tfReg, Paint.Align.CENTER);
        tp.clearShadowLayer();
    }

    private void drawWakeState(Canvas c) {
        float w = 25 * ppd, h = 3 * ppd;
        RectF r = new RectF(cx - w / 2, Y(-39) - h / 2, cx + w / 2, Y(-39) + h / 2);
        p.setColor(0x1FFFFFFF);
        c.drawRoundRect(r, h / 2, h / 2, p);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(1);
        p.setColor(0x3DFFFFFF);
        c.drawRoundRect(r, h / 2, h / 2, p);
        p.setStyle(Paint.Style.FILL);
        txt(c, "注视 0.8 s 唤醒", cx, Y(-41.2f), 11.2f, 0x55CFDAEA, tfReg, Paint.Align.CENTER);

        p.setColor(0x22FFFFFF);
        c.drawRect(eye.left, Y(-44), eye.right, Y(-44) + 1, p);
        p.setColor(0x99FFB454);
        c.drawRect(eye.left, Y(-44), eye.left + eye.width() * 0.38f, Y(-44) + 1, p);
    }

    private static final int ICON_PAUSE = 0, ICON_BACK = 1, ICON_FWD = 2, ICON_CC = 3, ICON_MENU = 4, ICON_RECENTER = 5;

    private void button(Canvas c, float yaw, float pitch, float deg, int icon, boolean main, float alpha, Float ring) {
        float x = X(yaw), y = Y(pitch), r = deg / 2f * ppd;
        if (main) {
            p.setColor(0x33FFB454);
            c.drawCircle(x, y, r + 5, p);
        }
        p.setColor(main ? AMBER : 0x990A0E14);
        c.drawCircle(x, y, r, p);

        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(1);
        p.setColor(main ? 0x00FFFFFF : 0x2EFFFFFF);
        c.drawCircle(x, y, r, p);
        p.setStyle(Paint.Style.FILL);

        if (ring != null) {
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(4);
            p.setColor(0x33FFFFFF);
            c.drawArc(x - r - 6, y - r - 6, x + r + 6, y + r + 6, -90, 360, false, p);
            p.setColor(0xFFFFFFFF);
            c.drawArc(x - r - 6, y - r - 6, x + r + 6, y + r + 6, -90, 360 * ring, false, p);
            p.setStyle(Paint.Style.FILL);
        }

        paintIcon(c, icon, x, y, r * 0.82f, main ? 0xFF1A1204 : 0xFFE9F0FF);
    }

    private void paintIcon(Canvas c, int icon, float x, float y, float s, int color) {
        p.setColor(color);
        p.setStyle(Paint.Style.FILL);
        p.setStrokeWidth(1);
        Path path = new Path();
        switch (icon) {
            case ICON_PAUSE:
                c.drawRoundRect(x - s * 0.36f, y - s * 0.46f, x - s * 0.12f, y + s * 0.46f, 3, 3, p);
                c.drawRoundRect(x + s * 0.12f, y - s * 0.46f, x + s * 0.36f, y + s * 0.46f, 3, 3, p);
                break;
            case ICON_BACK:
            case ICON_FWD: {
                float dir = icon == ICON_BACK ? -1 : 1;
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(1.1f * dp);
                c.drawArc(x - s * 0.46f, y - s * 0.46f, x + s * 0.46f, y + s * 0.46f,
                        90, dir * 290, false, p);
                p.setStyle(Paint.Style.FILL);
                path.moveTo(x + dir * s * 0.46f, y - s * 0.34f);
                path.lineTo(x + dir * s * 0.10f, y - s * 0.34f);
                path.lineTo(x + dir * s * 0.40f, y - s * 0.02f);
                path.close();
                c.drawPath(path, p);
                txt(c, "10", x + dir * s * 0.05f, y + s * 0.20f, s * 0.52f, color, tfMed, Paint.Align.CENTER);
                break;
            }
            case ICON_CC:
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(1.6f);
                c.drawRoundRect(x - s * 0.48f, y - s * 0.30f, x + s * 0.48f, y + s * 0.30f, 4, 4, p);
                p.setStyle(Paint.Style.FILL);
                txt(c, "CC", x, y + s * 0.22f, s * 0.62f, color, tfMed, Paint.Align.CENTER);
                break;
            case ICON_MENU: {
                float w = s * 0.44f;
                c.drawRoundRect(x - w, y - s * 0.30f, x + w, y - s * 0.22f, 2, 2, p);
                c.drawRoundRect(x - w, y - s * 0.05f, x + w, y + s * 0.03f, 2, 2, p);
                c.drawRoundRect(x - w, y + s * 0.20f, x + w, y + s * 0.28f, 2, 2, p);
                break;
            }
            case ICON_RECENTER: {
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(1.3f * dp);
                c.drawArc(x - s * 0.50f, y - s * 0.50f, x + s * 0.50f, y + s * 0.50f, 200, 300, false, p);
                p.setStyle(Paint.Style.FILL);
                path.moveTo(x + s * 0.50f, y - s * 0.08f);
                path.lineTo(x + s * 0.28f, y - s * 0.24f);
                path.lineTo(x + s * 0.50f, y - s * 0.44f);
                path.close();
                c.drawPath(path, p);
                c.drawCircle(x, y, s * 0.14f, p);
                break;
            }
        }
    }

    private void drawHudChrome(Canvas c) {
        if (landscape) {
            // Right column: a compact spec block instead of four full-width lines.
            float x = panel.left;
            float y = panel.top + 18 * dp;
            txt(c, "手机端 · 静态 HUD 预览", x, y, 13 * dp, 0xFFDCE6F7, tfMed, Paint.Align.LEFT);
            txt(c, "头锁定层", panel.right, y, 9.8f * dp, 0xFF6F7A91, tfReg, Paint.Align.RIGHT);

            String[] lines = {
                    "控件行 −18°：字幕 −30° · −10 s −16° · 播放 0° · +10 s +16° · 菜单 +30°",
                    "回中 (−37°, −25°) Ø7.5° · 唤醒条 25°×3° @ −39°",
                    "字幕 @ −33° · 进入 0.85R / 离开 1.15R · 去抖 120 ms",
                    "聚焦 150 ms · 预备 250 ms · 停留 L1 1.2 / L2 1.8 / L3 2.5 s"
            };
            int step = panel.height() > 200 * dp ? 1 : 2;
            y += 18 * dp;
            for (int i = 0; i < lines.length; i += step) {
                txt(c, lines[i], x, y, 9.6f * dp, 0xFF93A2BA, tfReg, Paint.Align.LEFT);
                y += 13 * dp;
            }
            return;
        }

        float x = 24 * dp;
        float y = eye.bottom + 22 * dp;
        txt(c, "外层 HUD · 头锁定（屏幕空间层）", x, y, 13 * dp, 0xFFDCE6F7, tfMed, Paint.Align.LEFT);

        y += 20 * dp;
        String[] lines = {
                "控件行 pitch −18°：字幕 −30° · −10 s −16° · 播放 0° · +10 s +16° · 菜单 +30°",
                "回中键 (−37°, −25°) Ø7.5° · L2 1.8 s → 3.5 s 调整姿态 → 按当前朝向重置",
                "唤醒条 25°×3° @ −39° · 命中区 64°×16° · 停留 0.38 s 唤回",
                "进入 0.85R / 离开 1.15R · 去抖 120 ms · 聚焦 150 ms · 250 ms 预备"
        };
        for (String s : lines) {
            txt(c, s, x, y, 10.2f * dp, 0xFF93A2BA, tfReg, Paint.Align.LEFT);
            y += 16 * dp;
        }
    }

    // ---------------------------------------------------------------- S5 calibration

    private void drawCalibScreen(Canvas c) {
        clipPath.reset();
        clipPath.addRoundRect(eye, 22 * dp, 22 * dp, Path.Direction.CW);
        c.save();
        c.clipPath(clipPath);
        c.drawColor(0xFF070A0F);
        drawTestCard(c);
        c.restore();

        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(1);
        p.setColor(0x1FFFFFFF);
        c.drawRoundRect(eye, 22 * dp, 22 * dp, p);
        p.setStyle(Paint.Style.FILL);

        drawCalibForm(c);
        drawBar(c, new String[]{"返回设置", "恢复出厂"}, -1);
    }

    private void drawTestCard(Canvas c) {
        float half = 30 * ppd;
        RectF card = new RectF(cx - half, cy - half, cx + half, cy + half);
        p.setColor(0xFF0A0E14);
        c.drawRect(card, p);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(1);
        p.setColor(0x33AFC4E6);
        c.drawRect(card, p);

        // grid every 10 deg, 5 deg in the central 30 deg
        p.setStrokeWidth(1);
        for (int a = -30; a <= 30; a += 10) {
            p.setColor(0x59AFC4E6);
            c.drawLine(X(a), card.top, X(a), card.bottom, p);
            c.drawLine(card.left, Y(a), card.right, Y(a), p);
        }
        for (int a = -15; a <= 15; a += 5) {
            p.setColor(0x33AFC4E6);
            c.drawLine(X(a), Y(-15), X(a), Y(15), p);
            c.drawLine(X(-15), Y(a), X(15), Y(a), p);
        }
        p.setColor(0x66FFB454);
        c.drawLine(cx, card.top, cx, card.bottom, p);
        c.drawLine(card.left, cy, card.right, cy, p);

        // corner crosses
        p.setStyle(Paint.Style.FILL);
        for (int sx = -1; sx <= 1; sx += 2) {
            for (int sy = -1; sy <= 1; sy += 2) {
                float x = X(sx * 25), y = Y(sy * 25);
                p.setColor(0xCCFFFFFF);
                c.drawRect(x - 1.5f * ppd, y - 0.6f, x + 1.5f * ppd, y + 0.6f, p);
                c.drawRect(x - 0.6f, y - 1.5f * ppd, x + 0.6f, y + 1.5f * ppd, p);
            }
        }

        // edge circles
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(1);
        p.setColor(0x66FFFFFF);
        float[] ex = {-27, -27, 27, 27, 0, 0, -27, 27};
        float[] ey = {0, 14, 0, -14, 27, -27, 14, -14};
        for (int i = 0; i < ex.length; i++) {
            c.drawCircle(X(ex[i]), Y(ey[i]), 2 * ppd, p);
        }

        // centre target + IPD guide lines
        p.setColor(0xAAFFB454);
        c.drawCircle(cx, cy, 1.2f * ppd, p);
        p.setStyle(Paint.Style.FILL);
        c.drawRect(cx - 2.5f * ppd, cy - 0.5f, cx + 2.5f * ppd, cy + 0.5f, p);
        c.drawRect(cx - 0.5f, cy - 2.5f * ppd, cx + 0.5f, cy + 2.5f * ppd, p);

        float halfIpd = calibParams().halfIpdDeg();
        for (int s = -1; s <= 1; s += 2) {
            float lx = X(s * halfIpd);
            p.setColor(0x99FFB454);
            c.drawRect(lx - 1, card.top + 6 * dp, lx + 1, card.bottom - 6 * dp, p);
        }
        txt(c, "两条对齐线间距 = 瞳距映射（" + calibParams().ipdText() + " → ±"
                        + String.format(java.util.Locale.US, "%.2f°", halfIpd) + "）",
                cx, Y(-27.5f), 11f, 0x8893A2BA, tfReg, Paint.Align.CENTER);

        // readability samples (right half)
        float rx = X(7);
        txt(c, "可读性样本 A", rx, Y(-12), 15f, 0xFFEAF1FB, tfReg, Paint.Align.LEFT);
        txt(c, "可读性样本 B", rx, Y(-17.5f), 21f, 0xFFEAF1FB, tfReg, Paint.Align.LEFT);
        txt(c, "可读性样本 C", rx, Y(-23.5f), 28f, 0xFFEAF1FB, tfReg, Paint.Align.LEFT);
        txt(c, "A 1.0 / B 1.5 / C 2.0 mm @2.5 m（示意）",
                rx, Y(-27.5f), 9.6f, 0x8893A2BA, tfReg, Paint.Align.LEFT);

        // single-eye mask instruction (left half)
        RectF mask = new RectF(X(-27), Y(-25), X(-9), Y(-10));
        p.setColor(0x14FFFFFF);
        c.drawRoundRect(mask, 8, 8, p);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(1);
        p.setColor(0x33FFFFFF);
        c.drawRoundRect(mask, 8, 8, p);
        p.setStyle(Paint.Style.FILL);
        txt(c, "单眼遮盖测试", mask.left + 10 * dp, mask.bottom + 30, 12.4f, 0xFFDCE6F7, tfMed, Paint.Align.LEFT);
        txt(c, "遮左眼 / 遮右眼各看一次中心十字", mask.left + 10 * dp, mask.bottom + 58, 10f, 0xFFA7B2C7, tfReg, Paint.Align.LEFT);
        txt(c, "单眼仍重影 → 屈光或垂直未对齐", mask.left + 10 * dp, mask.bottom + 86, 10f, 0xFFA7B2C7, tfReg, Paint.Align.LEFT);

        txt(c, "S5 · 2.5 m · 60°×60°", card.left + 10 * dp, card.top + 20 * dp, 10f, 0x8893A2BA, tfReg, Paint.Align.LEFT);
    }

    /**
     * Phone-side "before you put it on" page: model, IPD slider and the screen
     * record are edited here; every step-level tweak stays in the VR page.
     */
    private void drawCalibForm(Canvas c) {
        float x = landscape ? panel.left : 24 * dp;
        float avail = landscape ? panel.width() : W - 48 * dp;
        float y = landscape ? panel.top + 20 * dp : eye.bottom + 22 * dp;
        CalibParams cp = calibParams();

        txt(c, "S5 校准 · 戴前参数", x, y, 13 * dp, 0xFFDCE6F7, tfMed, Paint.Align.LEFT);
        txt(c, CalibParams.hasSaved(getContext()) ? "已保存" : "尚未保存",
                landscape ? panel.right : W - 24 * dp, y, 9.6f * dp, 0x8893A2BA, tfReg,
                Paint.Align.RIGHT);

        y += 12 * dp;
        txt(c, "这里只需点选；±1 步进的现场微调请进 VR 校准页", x, y, 9.6f * dp,
                0xFF8FA0BA, tfReg, Paint.Align.LEFT);

        y += 22 * dp;
        txt(c, "镜片型号（决定 k1/k2 初值）", x, y, 10.4f * dp, 0xFF93A2BA, tfReg,
                Paint.Align.LEFT);
        y += 7 * dp;
        String[] models = {CalibParams.modelName(CalibParams.MODEL_V2),
                CalibParams.modelName(CalibParams.MODEL_V3),
                CalibParams.modelName(CalibParams.MODEL_GENERIC)};
        float cw = (avail - 16 * dp) / 3f;
        for (int i = 0; i < 3; i++) {
            float l = x + i * (cw + 8 * dp);
            modelChips[i].set(l, y, l + cw, y + 30 * dp);
            chip(c, modelChips[i], models[i], cp.model == i);
        }

        y += 30 * dp + 16 * dp;
        txt(c, "瞳距 IPD · 50–75 mm", x, y, 10.4f * dp, 0xFF93A2BA, tfReg, Paint.Align.LEFT);
        txt(c, cp.ipdText(), landscape ? panel.right : W - 24 * dp, y, 12.4f * dp,
                0xFFFFD9A6, tfMed, Paint.Align.RIGHT);
        y += 12 * dp;
        ipdTrack.set(x, y, landscape ? panel.right : W - 24 * dp, y + 32 * dp);
        drawIpdSlider(c, cp);

        y = ipdTrack.bottom + 26 * dp;
        txt(c, "屏幕（只做记录，不参与几何计算）", x, y, 10.4f * dp, 0xFF93A2BA, tfReg,
                Paint.Align.LEFT);
        y += 7 * dp;
        String[] screens = {"6.3″", "6.7″", "未知"};
        float[] sizes = {6.3f, 6.7f, 0f};
        for (int i = 0; i < 3; i++) {
            float l = x + i * (cw + 8 * dp);
            screenChips[i].set(l, y, l + cw, y + 28 * dp);
            boolean active = (sizes[i] == 0f && cp.screenInch <= 0f)
                    || (sizes[i] > 0f && Math.abs(cp.screenInch - sizes[i]) < 0.01f);
            chip(c, screenChips[i], screens[i], active);
        }

        y += 28 * dp + 14 * dp;
        txt(c, "当前：k1 " + cp.k1Text() + " · k2 " + cp.k2Text() + " · 垂直 " + cp.vertText(),
                x, y, 9.8f * dp, 0xFFB9C4DA, tfReg, Paint.Align.LEFT);

        // Start button keeps the HUD entry's footprint (portrait) or the
        // bottom of the right column (landscape).
        if (landscape) {
            startZone.set(panel.left, panel.bottom - 52 * dp, panel.right, panel.bottom);
        } else {
            float btnH = 54 * dp;
            float btnBottom = chrome.top - 16 * dp;
            startZone.set(24 * dp, btnBottom - btnH, W - 24 * dp, btnBottom);
        }
        p.setStyle(Paint.Style.FILL);
        p.setAlpha(255);
        p.setShader(new LinearGradient(startZone.left, startZone.top, startZone.right, startZone.bottom,
                0xFFFFC46B, 0xFFF0A33C, Shader.TileMode.CLAMP));
        c.drawRoundRect(startZone, startZone.height() / 2f, startZone.height() / 2f, p);
        p.setShader(null);
        p.setAlpha(255);
        txt(c, "开始校准 · 戴上看 60° 测试卡", startZone.centerX(),
                startZone.centerY() + 5.4f * dp, 15f * dp, 0xFF1A1204, tfMed,
                Paint.Align.CENTER);
        txt(c, "VR 内：可读性自测 20 s → 瞳距/k1/k2/垂直 四组 ± 步进 → 保存",
                startZone.centerX(), startZone.top - 12 * dp, 9.2f * dp, 0xFF7A879E, tfReg,
                Paint.Align.CENTER);
    }

    private void drawIpdSlider(Canvas c, CalibParams cp) {
        float cy2 = ipdTrack.centerY();
        float left = ipdTrack.left + 12 * dp;
        float right = ipdTrack.right - 12 * dp;
        p.setStyle(Paint.Style.FILL);
        p.setColor(0x26FFFFFF);
        c.drawRoundRect(new RectF(left, cy2 - 3 * dp, right, cy2 + 3 * dp), 3 * dp, 3 * dp, p);

        float pct = (cp.ipdMm - CalibParams.IPD_MIN)
                / (CalibParams.IPD_MAX - CalibParams.IPD_MIN);
        float fill = left + (right - left) * pct;
        p.setColor(0xFFFFB454);
        c.drawRoundRect(new RectF(left, cy2 - 3 * dp, fill, cy2 + 3 * dp), 3 * dp, 3 * dp, p);

        p.setColor(0xFFFFC46B);
        c.drawCircle(fill, cy2, 12 * dp, p);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(2 * dp);
        p.setColor(0xFF16202C);
        c.drawCircle(fill, cy2, 12 * dp, p);
        p.setStyle(Paint.Style.FILL);

        txt(c, "50", left, cy2 + 24 * dp, 9f * dp, 0xFF7A879E, tfReg, Paint.Align.CENTER);
        txt(c, "63", (left + right) / 2f, cy2 + 24 * dp, 9f * dp, 0xFF7A879E, tfReg,
                Paint.Align.CENTER);
        txt(c, "75", right, cy2 + 24 * dp, 9f * dp, 0xFF7A879E, tfReg, Paint.Align.CENTER);
    }

    private void chip(Canvas c, RectF r, String label, boolean active) {
        p.setStyle(Paint.Style.FILL);
        p.setColor(active ? 0x33FFB454 : 0x14FFFFFF);
        c.drawRoundRect(r, 10 * dp, 10 * dp, p);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(1);
        p.setColor(active ? 0x99FFB454 : 0x26FFFFFF);
        c.drawRoundRect(r, 10 * dp, 10 * dp, p);
        p.setStyle(Paint.Style.FILL);
        txt(c, label, r.centerX(), r.centerY() + 4.2f * dp, 10.4f * dp,
                active ? 0xFFFFD9A6 : 0xFFC7D4E8, active ? tfMed : tfReg,
                Paint.Align.CENTER);
    }

    private void setIpdFromX(float x) {
        float left = ipdTrack.left + 12 * dp;
        float right = ipdTrack.right - 12 * dp;
        float pct = (x - left) / Math.max(1f, right - left);
        pct = Math.max(0f, Math.min(1f, pct));
        float v = CalibParams.IPD_MIN + pct * (CalibParams.IPD_MAX - CalibParams.IPD_MIN);
        v = Math.round(v * 2f) / 2f;
        CalibParams cp = calibParams();
        if (Math.abs(cp.ipdMm - v) > 0.01f) {
            cp.ipdMm = v;
            cp.clamp();
            saveCalib();
        }
    }

    // -------------------------------------------------------------- settings

    /**
     * One page for everything that is not playback: HUD demo, calibration and
     * the credits. The home screen never links here by accident.
     */
    private void drawSettingsScreen(Canvas c) {
        p.setStyle(Paint.Style.FILL);
        p.setShader(new LinearGradient(0, 0, 0, H,
                new int[]{0xFF0B1220, 0xFF05070A}, null, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, W, H, p);
        p.setShader(null);

        txt(c, "‹ 返回", settingsBack.left + 4 * dp, 34 * dp, 13 * dp, 0xFFA7B2C7,
                tfMed, Paint.Align.LEFT);
        txt(c, "设置", W / 2f, 34 * dp, 16 * dp, 0xFFE9EEF9, tfMed, Paint.Align.CENTER);
        txt(c, versionText(), W - 20 * dp, 34 * dp, 10 * dp, 0xFF6F8099, tfReg, Paint.Align.RIGHT);

        String[] titles = {"HUD 预览", "S5 镜头校准", "画面翻转 180°"};
        String[] subs = {"静态演示 VR 内控件，不影响播放", "调整画面畸变与两眼间距",
                "播放时画面上下颠倒，点此翻转"};

        if (landscape) {
            float m = 20 * dp;
            float colW = Math.min(W - 2 * m, 820 * dp);
            float left = (W - colW) / 2f;
            float right = left + colW;
            float rowsRight = left + colW * 0.52f;
            float rowH = 56 * dp;
            float gap = 10 * dp;
            for (int i = 0; i < 3; i++) {
                float top = 60 * dp + i * (rowH + gap);
                settingsRows[i].set(left, top, rowsRight, top + rowH);
                drawSettingsRow(c, settingsRows[i], titles[i], subs[i], rowH);
            }
            drawAboutCard(c, new RectF(rowsRight + 16 * dp, 60 * dp, right, H - 16 * dp), true);
        } else {
            float left = 20 * dp;
            float right = W - 20 * dp;
            float rowH = 66 * dp;
            float gap = 10 * dp;
            for (int i = 0; i < 3; i++) {
                float top = 66 * dp + i * (rowH + gap);
                settingsRows[i].set(left, top, right, top + rowH);
                drawSettingsRow(c, settingsRows[i], titles[i], subs[i], rowH);
            }
            float aboutTop = 66 * dp + 3 * (rowH + gap) + 6 * dp;
            float aboutBottom = Math.min(H - 20 * dp, aboutTop + 302 * dp);
            drawAboutCard(c, new RectF(left, aboutTop, right, aboutBottom), false);
        }
    }

    private void drawSettingsRow(Canvas c, RectF r, String title, String sub, float rowH) {
        cardBox(c, r);
        txt(c, title, r.left + 18 * dp, r.top + 26 * dp, 14 * dp, 0xFFE9EEF9,
                tfMed, Paint.Align.LEFT);
        txt(c, sub, r.left + 18 * dp, r.top + 46 * dp, 10 * dp, 0xFF93A2BA,
                tfReg, Paint.Align.LEFT);
        txt(c, "›", r.right - 18 * dp, r.top + rowH / 2f + 6 * dp, 18 * dp,
                0xFF7A8DA8, tfReg, Paint.Align.RIGHT);
    }

    private void drawAboutCard(Canvas c, RectF box, boolean compact) {
        cardBox(c, box);
        float x = box.left + 16 * dp;
        float y = box.top + (compact ? 22 : 26) * dp;
        float step = compact ? 16 * dp : 21 * dp;
        float body = compact ? 10.5f * dp : 11.5f * dp;
        float maxW = box.width() - 30 * dp;

        txt(c, "关于", x, y, 9.8f * dp, 0xFF7A8DA8, tfMed, Paint.Align.LEFT);
        y += compact ? 20 * dp : 26 * dp;
        txt(c, fitText("Android 手机 + Cardboard 卡纸盒的 VR 视频播放器", maxW, body, tfReg),
                x, y, body, 0xFFDDE6F3, tfReg, Paint.Align.LEFT);
        y += step;
        txt(c, fitText("360°/180° 全景 · SBS/上下 3D · 鱼眼", maxW, body, tfReg),
                x, y, body, 0xFFDDE6F3, tfReg, Paint.Align.LEFT);
        y += step;
        txt(c, fitText("片源：本地存储 / SMB2-3 局域网共享", maxW, body, tfReg),
                x, y, body, 0xFFDDE6F3, tfReg, Paint.Align.LEFT);

        y += compact ? 22 * dp : 28 * dp;
        txt(c, "开源组件", x, y, 9.5f * dp, 0xFF7A8DA8, tfMed, Paint.Align.LEFT);
        y += compact ? 17 * dp : 20 * dp;
        String[] libs = {
                "smbj 0.15.0 · SMB2/3 · Apache-2.0",
                "asn-one 0.6.0 · ASN.1 · Apache-2.0",
                "mbassador 1.3.2 · 事件总线 · Apache-2.0",
                "Bouncy Castle 1.85.2 · NTLM · MIT-style",
                "slf4j-api 2.0.18 · 日志门面 · MIT"
        };
        float libSize = compact ? 9.4f * dp : 10 * dp;
        float libStep = compact ? 14.5f * dp : 17 * dp;
        for (String s : libs) {
            txt(c, fitText(s, maxW, libSize, tfReg), x, y, libSize, 0xFFA7B2C7,
                    tfReg, Paint.Align.LEFT);
            y += libStep;
        }

        y += 8 * dp;
        txt(c, fitText("组件只用于局域网串流，不上传视频或账号。", maxW, libSize, tfReg),
                x, y, libSize, 0xFF9FB0C6, tfReg, Paint.Align.LEFT);
        y += libStep;
        txt(c, fitText(buildLine(), maxW, libSize, tfReg), x, y, libSize,
                0xFF6F8099, tfReg, Paint.Align.LEFT);
    }

    private void cardBox(Canvas c, RectF r) {
        p.setStyle(Paint.Style.FILL);
        p.setColor(0xCC0E131C);
        c.drawRoundRect(r, 18 * dp, 18 * dp, p);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(1);
        p.setColor(0x1FFFFFFF);
        c.drawRoundRect(r, 18 * dp, 18 * dp, p);
        p.setStyle(Paint.Style.FILL);
    }

    private String buildLine() {
        try {
            android.content.pm.PackageInfo info = getContext().getPackageManager()
                    .getPackageInfo(getContext().getPackageName(), 0);
            return "包名 " + getContext().getPackageName() + " · versionCode " + info.versionCode;
        } catch (Throwable t) {
            return "包名 com.yunyu697.gazeline";
        }
    }

    // ---------------------------------------------------------------- shared chrome

    private void drawBar(Canvas c, String[] labels, int selected) {
        p.setColor(0xD90E131C);
        c.drawRoundRect(chrome, 30 * dp, 30 * dp, p);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(1);
        p.setColor(0x1FFFFFFF);
        c.drawRoundRect(chrome, 30 * dp, 30 * dp, p);
        p.setStyle(Paint.Style.FILL);

        barZones.clear();
        float cell = chrome.width() / labels.length;
        for (int i = 0; i < labels.length; i++) {
            RectF z = new RectF(chrome.left + i * cell, chrome.top,
                    chrome.left + (i + 1) * cell, chrome.bottom);
            barZones.add(z);
            if (i == selected) {
                p.setColor(0x1FFFB454);
                c.drawRoundRect(z.left + 5 * dp, z.top + 7 * dp, z.right - 5 * dp, z.bottom - 7 * dp, 24 * dp, 24 * dp, p);
            }
            txt(c, labels[i], z.centerX(), z.centerY() + 4.4f * dp, 12 * dp,
                    i == selected ? 0xFFFFD9A6 : 0xFFA7B2C7, i == selected ? tfMed : tfReg, Paint.Align.CENTER);
        }
    }

    private void txt(Canvas c, String s, float x, float y, float size, int color, Typeface tf, Paint.Align align) {
        tp.setTypeface(tf);
        tp.setTextSize(size);
        tp.setColor(color);
        tp.setTextAlign(align);
        c.drawText(s, x, y, tp);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (screen == SCR_CALIB) {
            if (e.getActionMasked() == MotionEvent.ACTION_DOWN && ipdTrack.contains(e.getX(), e.getY())) {
                ipdDragging = true;
                setIpdFromX(e.getX());
                return true;
            }
            if (e.getActionMasked() == MotionEvent.ACTION_MOVE && ipdDragging) {
                setIpdFromX(e.getX());
                return true;
            }
            if (ipdDragging && (e.getActionMasked() == MotionEvent.ACTION_UP
                    || e.getActionMasked() == MotionEvent.ACTION_CANCEL)) {
                ipdDragging = false;
                return true;
            }
        }
        if (e.getAction() == MotionEvent.ACTION_UP) {
            float x = e.getX(), y = e.getY();

            if (screen == SCR_CALIB) {
                for (int i = 0; i < 3; i++) {
                    if (modelChips[i].contains(x, y)) {
                        calibParams().resetToModel(i);
                        saveCalib();
                        return true;
                    }
                    if (screenChips[i].contains(x, y)) {
                        calibParams().screenInch = i == 0 ? 6.3f : (i == 1 ? 6.7f : 0f);
                        saveCalib();
                        return true;
                    }
                }
                if (startZone.contains(x, y)) {
                    if (startCalibListener != null) {
                        startCalibListener.onStartCalib();
                    }
                    return true;
                }
                if (!barZones.isEmpty() && barZones.get(0).contains(x, y)) {
                    screen = SCR_SETTINGS;
                    invalidate();
                    return true;
                }
                if (barZones.size() > 1 && barZones.get(1).contains(x, y)) {
                    CalibParams cp = calibParams();
                    cp.resetToModel(CalibParams.MODEL_GENERIC);
                    cp.ipdMm = CalibParams.IPD_DEFAULT;
                    cp.vertLeftDeg = 0f;
                    cp.vertRightDeg = 0f;
                    cp.screenInch = 6.3f;
                    saveCalib();
                    return true;
                }
                return true;
            }

            if (screen == SCR_SPLASH) {
                showHome();
                return true;
            }

            if (screen == SCR_SETTINGS) {
                if (settingsBack.contains(x, y)) {
                    screen = SCR_HOME;
                    invalidate();
                    return true;
                }
                if (settingsRows[0].contains(x, y)) {
                    hudVisible = true;
                    screen = SCR_HUD;
                    invalidate();
                    return true;
                }
                if (settingsRows[1].contains(x, y)) {
                    if (startCalibListener != null) {
                        startCalibListener.onStartCalib();
                    }
                    return true;
                }
                if (settingsRows[2].contains(x, y)) {
                    if (flipImageListener != null) {
                        flipImageListener.onFlipImage();
                    }
                    return true;
                }
                return true;
            }

            if (screen == SCR_HOME) {
                if (homeGear.contains(x, y)) {
                    screen = SCR_SETTINGS;
                    invalidate();
                    return true;
                }
                if (undoPath != null && undoZone.contains(x, y)) {
                    if (android.os.SystemClock.uptimeMillis() <= undoUntil && undoListener != null) {
                        String path = undoPath;
                        String mode = undoMode;
                        long position = undoPositionMs;
                        clearUndo();
                        undoListener.onUndoPlay(path, mode, position);
                    }
                    return true;
                }
                if (homeCard.contains(x, y)) {
                    if (!history.isEmpty()) {
                        playHistory(history.get(0));
                    } else if (openVideoListener != null) {
                        openVideoListener.onOpenVideo();
                    }
                    return true;
                }
                for (int i = 0; i < historyZones.size() && i < history.size(); i++) {
                    if (historyZones.get(i).contains(x, y)) {
                        playHistory(history.get(i));
                        return true;
                    }
                }
                if (homeAllZone.contains(x, y) || homeLibrary.contains(x, y)) {
                    if (openVideoListener != null) {
                        openVideoListener.onOpenVideo();
                    }
                    return true;
                }
                if (homeVr.contains(x, y)) {
                    if (enterVrListener != null) {
                        enterVrListener.onEnterVr();
                    }
                    return true;
                }
                return true;
            }

            if (screen == SCR_HUD) {
                if (!hudVisible && wakeZone.contains(x, y)) {
                    hudVisible = true;
                    invalidate();
                    return true;
                }
                if (barZones.size() > 1
                        && barZones.get(0).contains(x, y)) {
                    hudVisible = !hudVisible;
                    invalidate();
                    return true;
                }
                if (barZones.size() > 1
                        && barZones.get(1).contains(x, y)) {
                    screen = SCR_SETTINGS;
                    invalidate();
                    return true;
                }
                return true;
            }
        }
        return true;
    }

    private void playHistory(PlayHistory.Entry entry) {
        if (historyPlayListener != null) {
            historyPlayListener.onHistoryPlay(entry.source, entry.mode, entry.startAt());
        }
    }
}
