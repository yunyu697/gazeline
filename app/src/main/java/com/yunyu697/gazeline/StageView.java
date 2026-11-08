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
    public static final int SCR_HUD = 1;
    public static final int SCR_CALIB = 2;

    private static final float FOV = 90f;

    private static final int AMBER = 0xFFFFB454;
    private static final int TEXT = 0xFFE9EEF9;
    private static final int DIM = 0xFF93A2BA;

    private int screen = SCR_SPLASH;
    private boolean hudVisible = true;
    private boolean showGrid = false;

    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tp = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path clipPath = new Path();
    private final RectF eye = new RectF();
    private final RectF chrome = new RectF();
    private final RectF[] zones = {new RectF(), new RectF(), new RectF()};
    private final RectF wakeZone = new RectF();

    private float W, H, dp, side, ppd, cx, cy;
    private Typeface tfReg, tfMed, tfLight;

    public StageView(Context c) {
        super(c);
        tfLight = Typeface.create("sans-serif-light", Typeface.NORMAL);
        tfReg = Typeface.create("sans-serif", Typeface.NORMAL);
        tfMed = Typeface.create("sans-serif-medium", Typeface.NORMAL);
        setFocusable(true);
    }

    public void showHud() {
        screen = SCR_HUD;
        invalidate();
    }

    public boolean back() {
        if (screen == SCR_CALIB) {
            screen = SCR_HUD;
            invalidate();
            return true;
        }
        if (screen == SCR_SPLASH) {
            showHud();
            return true;
        }
        return false;
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        W = w;
        H = h;
        dp = getResources().getDisplayMetrics().density;
        side = Math.min(w, h * 0.66f);
        float top = Math.max(26 * dp, h * 0.045f);
        eye.set((w - side) / 2f, top, (w - side) / 2f + side, top + side);
        ppd = side / FOV;
        cx = eye.centerX();
        cy = eye.centerY();

        float barH = 62 * dp;
        float barY = H - 16 * dp - barH;
        chrome.set(16 * dp, barY, W - 16 * dp, barY + barH);
        float cell = chrome.width() / 3f;
        for (int i = 0; i < 3; i++) {
            zones[i].set(chrome.left + i * cell, chrome.top, chrome.left + (i + 1) * cell, chrome.bottom);
        }
        wakeZone.set(X(-12.5f), Y(-40.5f), X(12.5f), Y(-37.5f));
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
        } else if (screen == SCR_HUD) {
            drawHudScreen(c);
        } else {
            drawCalibScreen(c);
        }
    }

    // ---------------------------------------------------------------- splash

    private void drawSplash(Canvas c) {
        p.setShader(new RadialGradient(W / 2f, H * 0.32f, W * 0.95f,
                new int[]{0x33FFB454, 0x0AFFB454, 0x0005070A}, new float[]{0f, 0.45f, 1f}, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, W, H, p);
        p.setShader(null);

        float markR = W * 0.075f;
        float markY = H * 0.30f;
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(1.4f * dp);
        p.setColor(0x66FFB454);
        c.drawCircle(W / 2f, markY, markR, p);
        p.setStyle(Paint.Style.FILL);
        p.setColor(AMBER);
        Path tri = new Path();
        tri.moveTo(W / 2f - markR * 0.30f, markY - markR * 0.42f);
        tri.lineTo(W / 2f + markR * 0.48f, markY);
        tri.lineTo(W / 2f - markR * 0.30f, markY + markR * 0.42f);
        tri.close();
        c.drawPath(tri, p);

        tp.setLetterSpacing(0.22f);
        txt(c, "GAZELINE", W / 2f, H * 0.445f, W * 0.098f, 0xFFF4F8FF, tfLight, Paint.Align.CENTER);
        tp.setLetterSpacing(0f);

        p.setColor(0x55FFB454);
        c.drawRect(W / 2f - W * 0.10f, H * 0.472f, W / 2f + W * 0.10f, H * 0.472f + 1.2f * dp, p);

        tp.setLetterSpacing(0.28f);
        txt(c, "CARDBOARD VR PLAYER", W / 2f, H * 0.515f, W * 0.030f, 0xFF8FA0BA, tfReg, Paint.Align.CENTER);
        tp.setLetterSpacing(0f);

        txt(c, "v1.0 · POC static UI", W / 2f, H - 54 * dp, 11 * dp, 0x55A7B2C7, tfReg, Paint.Align.CENTER);
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

        txt(c, "GAZELINE · POC", eye.left + 14 * dp, eye.top + 22 * dp, 9.5f * dp, 0x66C7D4E8, tfMed, Paint.Align.LEFT);

        drawHudChrome(c);
        drawBar(c, new String[]{"HUD 预览", "S5 校准", hudVisible ? "收起 HUD" : "叫回 HUD"}, 0);
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
        float x = 24 * dp;
        float y = eye.bottom + 22 * dp;
        txt(c, "外层 HUD · 头锁定（屏幕空间层）", x, y, 13 * dp, 0xFFDCE6F7, tfMed, Paint.Align.LEFT);

        y += 22 * dp;
        String[] lines = {
                "控件行 pitch −18°：字幕 −30° · −10 s −16° · 播放 0° · +10 s +16° · 菜单 +30°",
                "回中键 (−37°, −25°) Ø7.5° · L2 1.8 s（1.2 s 充能 + 0.6 s 待发）· 随 HUD 一起显隐",
                "唤醒条 25°×3° @ −39° · 字幕 @ −33° · 与字幕带、快退键中心距均满足 ≥ 2° 隔离"
        };
        for (String s : lines) {
            txt(c, s, x, y, 10.6f * dp, 0xFF93A2BA, tfReg, Paint.Align.LEFT);
            y += 18 * dp;
        }

        RectF card = new RectF(x, y + 4 * dp, W - x, y + 48 * dp);
        p.setColor(0x0FFFFFFF);
        c.drawRoundRect(card, 10 * dp, 10 * dp, p);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(1);
        p.setColor(0x1FFFFFFF);
        c.drawRoundRect(card, 10 * dp, 10 * dp, p);
        p.setStyle(Paint.Style.FILL);

        txt(c, "进入 0.85R / 离开 1.15R · 120 ms 去抖 · 150 ms 聚焦 · 250 ms 预备",
                card.left + 12 * dp, card.top + 17 * dp, 10 * dp, 0xFFB9C4DA, tfReg, Paint.Align.LEFT);
        txt(c, "停留 L1 1.2 / L2 1.8 / L3 2.5 s（L3 再确认）· 执行后 500 ms 冷却 · 菜单 400 ms 保护",
                card.left + 12 * dp, card.top + 34 * dp, 9.2f * dp, 0xFFB9C4DA, tfReg, Paint.Align.LEFT);
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

        drawCalibChrome(c);
        drawBar(c, new String[]{"HUD 预览", "S5 校准", "重置参数"}, 1);
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

        for (int s = -1; s <= 1; s += 2) {
            float lx = X(s * 2.4f);
            p.setColor(0x66FFB454);
            c.drawRect(lx - 1, card.top + 6 * dp, lx + 1, card.bottom - 6 * dp, p);
        }

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

    private void drawCalibChrome(Canvas c) {
        float x = 24 * dp;
        float y = eye.bottom + 22 * dp;
        txt(c, "S5 校准 · 测试卡 + 步进微调（静态形态）", x, y, 13 * dp, 0xFFDCE6F7, tfMed, Paint.Align.LEFT);

        y += 18 * dp;
        txt(c, "整体均匀发虚 · 远近都糊 · 双眼一样 → 屈光：戴眼镜或换屈光镜片，别动畸变",
                x, y, 9.6f * dp, 0xFF7FE3B9, tfReg, Paint.Align.LEFT);
        y += 16 * dp;
        txt(c, "重影 · 桶形 · 边缘糊 · 单眼垂直偏移 → 参数：用下方步进微调，改完即时生效",
                x, y, 9.6f * dp, 0xFFFFC97A, tfReg, Paint.Align.LEFT);

        y += 10 * dp;
        String[] names = {"瞳距 (IPD)", "k1", "k2", "垂直偏移"};
        String[] values = {"62.0 mm", "−0.30", "+0.05", "0.0°"};
        float rowH = 38 * dp;
        for (int i = 0; i < 4; i++) {
            float by = y + 20 * dp + i * rowH;
            txt(c, names[i], x, by + 4 * dp, 11 * dp, 0xFF93A2BA, tfReg, Paint.Align.LEFT);

            float mx = x + 118 * dp;
            p.setColor(0x990A0E14);
            c.drawCircle(mx, by, 14 * dp, p);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(1);
            p.setColor(0x2EFFFFFF);
            c.drawCircle(mx, by, 14 * dp, p);
            p.setStyle(Paint.Style.FILL);
            txt(c, "−", mx, by + 5 * dp, 15 * dp, 0xFFE9F0FF, tfReg, Paint.Align.CENTER);

            p.setColor(0xFF16202C);
            c.drawRoundRect(mx + 22 * dp, by - 13 * dp, mx + 104 * dp, by + 13 * dp, 8 * dp, 8 * dp, p);
            txt(c, values[i], mx + 63 * dp, by + 4.4f * dp, 11.4f * dp, 0xFFE9F0FF, tfMed, Paint.Align.CENTER);

            float px = mx + 126 * dp;
            p.setColor(0x990A0E14);
            c.drawCircle(px, by, 14 * dp, p);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(1);
            p.setColor(0x2EFFFFFF);
            c.drawCircle(px, by, 14 * dp, p);
            p.setStyle(Paint.Style.FILL);
            txt(c, "+", px, by + 5 * dp, 15 * dp, 0xFFE9F0FF, tfReg, Paint.Align.CENTER);
        }

        txt(c, "VR 内按钮 Ø8°（≈118 px @14.8 px/°）· 步进 ±0.5 mm / ±0.02 / ±0.01 / ±0.5° · 20 s 可读性自测",
                x, y + 20 * dp + 4 * rowH + 4 * dp, 9.2f * dp, 0x8893A2BA, tfReg, Paint.Align.LEFT);
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

        for (int i = 0; i < 3; i++) {
            RectF z = zones[i];
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
        if (e.getAction() == MotionEvent.ACTION_UP) {
            float x = e.getX(), y = e.getY();
            if (screen == SCR_SPLASH) {
                showHud();
                return true;
            }
            if (!hudVisible && wakeZone.contains(x, y)) {
                hudVisible = true;
                invalidate();
                return true;
            }
            if (zones[0].contains(x, y)) {
                screen = SCR_HUD;
                invalidate();
                return true;
            }
            if (zones[1].contains(x, y)) {
                screen = SCR_CALIB;
                invalidate();
                return true;
            }
            if (zones[2].contains(x, y)) {
                if (screen == SCR_CALIB) {
                    screen = SCR_HUD;
                } else {
                    hudVisible = !hudVisible;
                }
                invalidate();
                return true;
            }
        }
        return true;
    }
}
