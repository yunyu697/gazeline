package com.yunyu697.gazeline;

import android.app.Activity;
import android.content.Intent;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.net.Uri;
import android.opengl.GLSurfaceView;
import android.opengl.Matrix;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Surface;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.Toast;

import java.io.File;

/**
 * Fullscreen stereo playback with the gaze layer. Orientation is
 * world←camera = recenterCorrection * sensorBasis * roll/drag; the gaze
 * controller anchors its HUD to the view direction captured on open, wake and
 * recenter, so looking at a control is a head movement rather than a reticle
 * chase. On the emulator (no sensor) drag simulates head movement.
 */
public class PlayerActivity extends Activity
        implements HudView.Listener, GazeController.Listener,
        VideoDecoder.Listener, SensorEventListener {

    static final String EXTRA_PATH = "path";
    static final String EXTRA_MODE = "mode";
    static final String EXTRA_SENSOR = "sensor";
    static final String EXTRA_POSITION = "position";

    private static final int OVERLAY_NONE = 0;
    private static final int OVERLAY_MENU = 1;
    private static final int OVERLAY_CONFIRM = 2;

    private static final int REC_IDLE = 0;
    private static final int REC_MASK_IN = 1;
    private static final int REC_ROTATE = 2;
    private static final int REC_MASK_OUT = 3;

    private GLSurfaceView glView;
    private StereoRenderer renderer;
    private HudView hud;
    private SensorManager sensors;
    private Sensor rotationSensor;
    private Feedback feedback;
    private GazeController gaze;
    private GazeController menuGaze;

    private final HudView.Frame frame = new HudView.Frame();

    private boolean panorama;
    private boolean sensorWanted;
    private boolean sensorActive;
    private String dataSource;
    private String videoName = "?";
    private String videoFormat = "正在打开…";

    private final float[] sensorMatrix = new float[16];
    private final float[] basis = new float[16];
    private final float[] offset = new float[16];
    private final float[] worldRot = new float[16];
    private final float[] composed = new float[16];
    private final float[] tmpMatrix = new float[16];
    private final float[] rowOut = new float[9];

    // drag / look offsets (no-sensor emulation and small trim)
    private float yaw;
    private float pitch;

    // world-space recenter correction (applied on the left of the camera basis)
    private float worldYaw;
    private float worldPitch;

    private long sessionStartMs;
    private long lastActivityMs;
    private long lastTickMs;
    private long prevTickMs;
    private float prevViewYaw;
    private float prevViewPitch;
    private volatile float viewYaw;
    private volatile float viewPitch;
    private float angularSpeed;
    private long wakeFadeStart = -1;

    private boolean playing = true;
    private boolean subtitleOn;
    private boolean sensorPrimed;
    private String statusText = "";
    private long statusUntil;

    // S4 overlay: menu, then the exit confirmation
    private int overlay = OVERLAY_NONE;
    private long overlayStart;
    private float overlayAlpha;
    private String overlayHint = "";
    private long overlayHintUntil;
    private long menuOpenedAt;
    private long menuLeaveSince;
    private float playbackRate = 1f;
    private long startAtMs;
    private boolean startSeekApplied;
    private boolean exiting;

    // recenter animation
    private int recPhase = REC_IDLE;
    private long recPhaseStart;
    private long recDurationMs;
    private float recTotalYaw;
    private float recTotalPitch;
    private float recBaseWorldYaw;
    private float recBaseWorldPitch;
    private float maskAlpha;
    private float flashAlpha;
    private long flashStart;

    private final Handler ui = new Handler(Looper.getMainLooper());

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            onTick();
            ui.postDelayed(this, 16);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);

        dataSource = getIntent().getStringExtra(EXTRA_PATH);
        if (dataSource == null || dataSource.length() == 0) {
            Toast.makeText(this, "未指定视频文件", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        panorama = "360".equals(getIntent().getStringExtra(EXTRA_MODE));
        sensorWanted = getIntent().getBooleanExtra(EXTRA_SENSOR, true);
        startAtMs = Math.max(0L, getIntent().getLongExtra(EXTRA_POSITION, 0L));
        videoName = displayName(dataSource);

        sensors = (SensorManager) getSystemService(SENSOR_SERVICE);
        if (sensors != null) {
            rotationSensor = sensors.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);
        }
        sensorActive = sensorWanted && rotationSensor != null;

        Matrix.setIdentityM(sensorMatrix, 0);

        feedback = new Feedback(this);
        gaze = new GazeController(this);
        menuGaze = new GazeController(this);
        menuGaze.setLayer(HudSpec.MENU_ROWS, null, false);

        glView = new GLSurfaceView(this);
        glView.setEGLContextClientVersion(2);
        renderer = new StereoRenderer(this, dataSource, panorama, this);
        glView.setRenderer(renderer);
        glView.setRenderMode(GLSurfaceView.RENDERMODE_CONTINUOUSLY);

        hud = new HudView(this);
        hud.setListener(this);

        FrameLayout root = new FrameLayout(this);
        root.addView(glView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        root.addView(hud, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        setContentView(root);

        sessionStartMs = SystemClock.elapsedRealtime();
        lastActivityMs = sessionStartMs;
        updateRotation();
        sensorPrimed = !sensorActive;
        if (!sensorActive) {
            gaze.setAnchor(viewYaw, viewPitch);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        glView.onResume();
        renderer.setFreeze(false);
        renderer.restartDecoder();
        VideoDecoder d = renderer.decoder();
        if (d != null && playbackRate != 1f) {
            d.setSpeed(playbackRate);
        }
        if (!playing && d != null) {
            d.pause();
        }
        if (sensorActive) {
            sensors.registerListener(this, rotationSensor, SensorManager.SENSOR_DELAY_GAME);
        }
        recPhase = REC_IDLE;
        maskAlpha = 0f;
        flashAlpha = 0f;
        overlay = OVERLAY_NONE;
        overlayAlpha = 0f;
        overlayHint = "";
        overlayHintUntil = 0;
        lastTickMs = 0;
        lastActivityMs = SystemClock.elapsedRealtime();
        sensorPrimed = !sensorActive;
        if (!sensorActive) {
            gaze.setAnchor(viewYaw, viewPitch);
        }
        ui.post(tick);
    }

    @Override
    protected void onPause() {
        super.onPause();
        ui.removeCallbacks(tick);
        if (sensors != null) {
            sensors.unregisterListener(this);
        }
        renderer.setFreeze(false);
        renderer.stopDecoder();
        glView.onPause();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (feedback != null) {
            feedback.release();
        }
    }

    @Override
    public void onBackPressed() {
        long now = SystemClock.elapsedRealtime();
        if (overlay == OVERLAY_MENU) {
            closeOverlay("菜单已关闭");
            return;
        }
        if (overlay == OVERLAY_CONFIRM) {
            closeOverlay("已取消退出");
            return;
        }
        if (gaze.cancelByKey(now)) {
            lastActivityMs = now;
            return;
        }
        if (recPhase != REC_IDLE) {
            finishRecenter(now, true);
            return;
        }
        super.onBackPressed();
    }

    // ------------------------------------------------------------- tick

    private void onTick() {
        long now = SystemClock.elapsedRealtime();
        updateRotation();

        if (lastTickMs == 0) {
            lastTickMs = now;
            prevTickMs = now;
            prevViewYaw = viewYaw;
            prevViewPitch = viewPitch;
        }
        float dt = (now - prevTickMs) / 1000f;
        if (dt < 0.004f) {
            dt = 0.004f;
        }
        if (dt > 0.08f) {
            dt = 0.08f;
        }
        float dy = GazeController.wrap(viewYaw - prevViewYaw);
        float dp = viewPitch - prevViewPitch;
        float inst = (float) Math.sqrt(dy * dy + dp * dp) / dt;
        angularSpeed = angularSpeed * 0.72f + inst * 0.28f;
        prevViewYaw = viewYaw;
        prevViewPitch = viewPitch;
        prevTickMs = now;

        updateRecenter(now);

        float hudAlpha = computeHudAlpha(now);
        boolean controlsShown = !playing || hudAlpha >= 0.45f;

        float speedForGaze = recPhase == REC_IDLE ? angularSpeed : 0f;
        if (overlay != OVERLAY_NONE) {
            updateOverlay(now, speedForGaze);
            if (sensorPrimed) {
                gaze.update(now, viewYaw, viewPitch, speedForGaze, false, false);
            }
            hudAlpha = 0f;
        } else if (sensorPrimed) {
            gaze.update(now, viewYaw, viewPitch, speedForGaze,
                    controlsShown, recPhase == REC_IDLE);
        }

        GazeController.Readout g = gaze.readout();
        GazeController.Readout mg = menuGaze.readout();
        if (g.focus != null || (overlay != OVERLAY_NONE && mg.focus != null)) {
            lastActivityMs = now;
        }

        if (now > statusUntil) {
            statusText = "";
        }

        float wakeAlpha = playing ? Math.max(0f, 1f - hudAlpha) : 0f;

        buildFrame(now, hudAlpha, wakeAlpha);
        hud.setFrame(frame, now);
    }

    private float computeHudAlpha(long now) {
        if (!playing) {
            return 0.4f;
        }
        float base;
        if (now - sessionStartMs < HudSpec.NEW_VIDEO_ALWAYS_MS || recPhase != REC_IDLE) {
            base = 1f;
        } else {
            long idle = now - lastActivityMs;
            if (idle <= HudSpec.IDLE_START_MS) {
                base = 1f;
            } else if (idle >= HudSpec.IDLE_START_MS + HudSpec.IDLE_FADE_MS) {
                base = 0f;
            } else {
                base = 1f - (idle - HudSpec.IDLE_START_MS)
                        / (float) HudSpec.IDLE_FADE_MS;
            }
        }
        if (wakeFadeStart >= 0) {
            long t = now - wakeFadeStart;
            if (t < 180) {
                base = t / 180f;
            }
        }
        return base;
    }

    private void updateOverlay(long now, float speedForGaze) {
        overlayAlpha = Math.min(1f, (now - overlayStart) / 120f);
        if (now > overlayHintUntil) {
            overlayHint = "";
        }
        if (!sensorPrimed) {
            return;
        }
        menuGaze.update(now, viewYaw, viewPitch, speedForGaze, true, true);

        GazeController.Readout mg = menuGaze.readout();
        boolean counting = mg.focus != null;
        if (counting) {
            menuLeaveSince = 0;
        } else {
            boolean inside = overlay == OVERLAY_MENU
                    ? HudSpec.inMenuPanel(mg.relYaw, mg.relPitch)
                    : HudSpec.inConfirmPanel(mg.relYaw, mg.relPitch);
            if (inside) {
                menuLeaveSince = 0;
            } else if (menuLeaveSince == 0) {
                menuLeaveSince = now;
            } else if (now - menuLeaveSince >= HudSpec.MENU_LEAVE_CLOSE_MS) {
                closeOverlay(overlay == OVERLAY_MENU ? "菜单已关闭" : "已取消退出");
                return;
            }
        }
        if (overlay == OVERLAY_MENU && now - menuOpenedAt >= HudSpec.MENU_AUTO_CLOSE_MS) {
            closeOverlay("菜单已关闭");
        }
    }

    private void openMenu() {
        long now = SystemClock.elapsedRealtime();
        overlay = OVERLAY_MENU;
        overlayStart = now;
        overlayAlpha = 0f;
        overlayHint = "";
        overlayHintUntil = 0;
        menuOpenedAt = now;
        menuLeaveSince = 0;
        menuGaze.setLayer(HudSpec.MENU_ROWS, null, false);
        menuGaze.setAnchor(viewYaw, viewPitch);
        menuGaze.onHudOpened(now);
        menuGaze.protect(now, HudSpec.MENU_PROTECT_MS);
        feedback.menuOpen();
        lastActivityMs = now;
    }

    private void openConfirm() {
        long now = SystemClock.elapsedRealtime();
        overlay = OVERLAY_CONFIRM;
        overlayStart = now;
        overlayAlpha = 0f;
        overlayHint = "";
        overlayHintUntil = 0;
        menuLeaveSince = 0;
        menuGaze.setLayer(HudSpec.CONFIRM_TARGETS, null, false);
        menuGaze.setAnchor(viewYaw, viewPitch);
        menuGaze.onHudOpened(now);
        menuGaze.protect(now, HudSpec.CONFIRM_PROTECT_MS);
        feedback.ready();
        lastActivityMs = now;
    }

    private void closeOverlay(String status) {
        long now = SystemClock.elapsedRealtime();
        overlay = OVERLAY_NONE;
        overlayAlpha = 0f;
        overlayHint = "";
        overlayHintUntil = 0;
        menuLeaveSince = 0;
        feedback.menuClose();
        gaze.setAnchor(viewYaw, viewPitch);
        gaze.onHudOpened(now);
        lastActivityMs = now;
        if (status != null && status.length() > 0) {
            showStatus(status);
        }
    }

    private void exitPlayback(VideoDecoder d) {
        if (exiting) {
            return;
        }
        exiting = true;
        long position = d == null ? 0L : d.positionMs();
        Intent out = new Intent();
        out.putExtra(EXTRA_PATH, dataSource);
        out.putExtra(EXTRA_MODE, panorama ? "360" : "sbs");
        out.putExtra(EXTRA_POSITION, position);
        setResult(RESULT_OK, out);
        finish();
    }

    private void buildFrame(long now, float hudAlpha, float wakeAlpha) {
        frame.title = stripExtension(videoName);
        frame.format = videoFormat;
        frame.subtitle = "";
        frame.status = statusText;
        frame.statusUntil = statusUntil;
        frame.playing = playing;
        frame.subtitleOn = subtitleOn;
        VideoDecoder d = renderer.decoder();
        frame.positionMs = d == null ? 0 : d.positionMs();
        frame.durationMs = d == null ? 0 : d.durationMs();
        frame.gaze = gaze.readout();
        frame.hudAlpha = hudAlpha;
        frame.wakeAlpha = wakeAlpha;
        frame.maskAlpha = maskAlpha;
        frame.flashAlpha = flashAlpha;

        frame.overlay = overlay;
        frame.overlayAlpha = overlayAlpha;
        frame.overlayGaze = menuGaze.readout();
        frame.overlayHint = overlayHint;
        frame.speed = playbackRate;
        frame.defaultFocusHint = overlay != OVERLAY_NONE
                && menuGaze.readout().focus == null
                && now - overlayStart < HudSpec.MENU_PROTECT_MS;
        frame.menuWarn = overlay == OVERLAY_MENU
                && now - menuOpenedAt > HudSpec.MENU_AUTO_CLOSE_MS - HudSpec.MENU_CLOSE_WARN_MS
                && now - menuOpenedAt < HudSpec.MENU_AUTO_CLOSE_MS;
    }

    // ------------------------------------------------------------- gaze callbacks

    @Override
    public void onGazeFocus(HudSpec.Target target) {
        feedback.focus();
        lastActivityMs = SystemClock.elapsedRealtime();
    }

    @Override
    public void onGazeCancel(HudSpec.Target target) {
        feedback.cancel();
    }

    @Override
    public void onRecenterReady(HudSpec.Target target) {
        feedback.ready();
    }

    @Override
    public void onWake() {
        long now = SystemClock.elapsedRealtime();
        feedback.wake();
        lastActivityMs = now;
        wakeFadeStart = now;
        gaze.setAnchor(viewYaw, viewPitch);
        gaze.onHudOpened(now);
    }

    @Override
    public void onGazeConfirm(HudSpec.Target target) {
        feedback.confirm();
        lastActivityMs = SystemClock.elapsedRealtime();
        switch (target.id) {
            case PLAY:
                togglePlay();
                break;
            case REWIND:
                seekBy(-10000L, "快退 10 秒");
                break;
            case FORWARD:
                seekBy(10000L, "快进 10 秒");
                break;
            case SUBTITLE:
                subtitleOn = !subtitleOn;
                showStatus(subtitleOn ? "字幕已开启 · 暂无字幕轨" : "字幕已关闭");
                break;
            case RECENTER:
                startRecenter();
                break;
            case MENU:
                openMenu();
                break;
            case MENU_SUBTITLE:
                subtitleOn = !subtitleOn;
                setOverlayHint(subtitleOn ? "字幕已开启 · 暂无字幕轨" : "字幕已关闭");
                break;
            case MENU_AUDIO:
                setOverlayHint("暂无音轨 · 当前只解码视频轨");
                break;
            case MENU_SPEED:
                cycleSpeed();
                break;
            case MENU_EXIT:
                openConfirm();
                break;
            case EXIT_CANCEL:
                closeOverlay("已取消退出");
                break;
            case EXIT_OK:
                feedback.confirm();
                exitPlayback(renderer.decoder());
                break;
            default:
                break;
        }
    }

    private void setOverlayHint(String text) {
        overlayHint = text;
        overlayHintUntil = SystemClock.elapsedRealtime() + 2200;
        lastActivityMs = SystemClock.elapsedRealtime();
    }

    private void cycleSpeed() {
        float next;
        if (playbackRate < 0.75f) {
            next = 0.75f;
        } else if (playbackRate < 1f) {
            next = 1f;
        } else if (playbackRate < 1.25f) {
            next = 1.25f;
        } else if (playbackRate < 1.5f) {
            next = 1.5f;
        } else if (playbackRate < 2f) {
            next = 2f;
        } else {
            next = 0.5f;
        }
        playbackRate = next;
        VideoDecoder d = renderer.decoder();
        if (d != null) {
            d.setSpeed(next);
        }
        setOverlayHint("播放速度 " + String.format("%.2f", next).replaceAll("0$", "") + "×");
    }

    private void togglePlay() {
        playing = !playing;
        VideoDecoder d = renderer.decoder();
        if (d != null) {
            if (playing) {
                d.resume();
            } else {
                d.pause();
            }
        }
        showStatus(playing ? "继续播放" : "已暂停");
        lastActivityMs = SystemClock.elapsedRealtime();
    }

    private void seekBy(long deltaMs, String label) {
        VideoDecoder d = renderer.decoder();
        if (d == null) {
            return;
        }
        long target = d.positionMs() + deltaMs;
        if (target < 0) {
            target = 0;
        }
        if (d.durationMs() > 0 && target > d.durationMs()) {
            target = d.durationMs();
        }
        d.seekTo(target);
        showStatus(label);
        lastActivityMs = SystemClock.elapsedRealtime();
    }

    private void showStatus(String text) {
        statusText = text;
        statusUntil = SystemClock.elapsedRealtime() + 1800;
    }

    // ------------------------------------------------------------- recenter

    private void startRecenter() {
        if (recPhase != REC_IDLE) {
            return;
        }
        long now = SystemClock.elapsedRealtime();
        updateRotation();
        recBaseWorldYaw = worldYaw;
        recBaseWorldPitch = worldPitch;
        recTotalYaw = -viewYaw;
        recTotalPitch = -viewPitch;
        float max = Math.max(Math.abs(recTotalYaw), Math.abs(recTotalPitch));
        recDurationMs = (long) Math.min(300f, Math.max(140f, max / 180f * 1000f + 60f));
        recPhase = REC_MASK_IN;
        recPhaseStart = now;
        maskAlpha = 0f;
        flashAlpha = 0f;
        renderer.setFreeze(true);
        lastActivityMs = now;
    }

    private void updateRecenter(long now) {
        switch (recPhase) {
            case REC_MASK_IN: {
                float t = (now - recPhaseStart) / 110f;
                if (t >= 1f) {
                    maskAlpha = 0.8f;
                    recPhase = REC_ROTATE;
                    recPhaseStart = now;
                } else {
                    maskAlpha = 0.8f * Math.max(0f, t);
                }
                break;
            }
            case REC_ROTATE: {
                maskAlpha = 0.8f;
                float f = (now - recPhaseStart) / (float) recDurationMs;
                boolean timeout = now - recPhaseStart > 600;
                if (f > 1f) {
                    f = 1f;
                }
                float ease = f * f * (3f - 2f * f);
                float targetYaw = recBaseWorldYaw + recTotalYaw * ease;
                float targetPitch = recBaseWorldPitch + recTotalPitch * ease;
                float stepY = clamp(targetYaw - worldYaw, -2.5f, 2.5f);
                float stepP = clamp(targetPitch - worldPitch, -2.5f, 2.5f);
                worldYaw += stepY;
                worldPitch += stepP;
                updateRotation();
                boolean done = (f >= 1f && Math.abs(targetYaw - worldYaw) < 0.15f
                        && Math.abs(targetPitch - worldPitch) < 0.15f) || timeout;
                if (done) {
                    worldYaw = targetYaw;
                    worldPitch = targetPitch;
                    updateRotation();
                    maskAlpha = 0.8f;
                    recPhase = REC_MASK_OUT;
                    recPhaseStart = now;
                    flashStart = now;
                    flashAlpha = 1f;
                    renderer.setFreeze(false);
                    feedback.confirm();
                }
                break;
            }
            case REC_MASK_OUT: {
                float t = (now - recPhaseStart) / 120f;
                maskAlpha = 0.8f * Math.max(0f, 1f - t);
                if (t >= 1f) {
                    finishRecenter(now, false);
                }
                break;
            }
            default:
                break;
        }

        if (flashAlpha > 0f) {
            long t = now - flashStart;
            if (t >= 200) {
                flashAlpha = 0f;
            } else {
                flashAlpha = 1f - t / 200f;
            }
        }
    }

    private void finishRecenter(long now, boolean interrupted) {
        recPhase = REC_IDLE;
        maskAlpha = 0f;
        flashAlpha = 0f;
        renderer.setFreeze(false);
        updateRotation();
        gaze.setAnchor(viewYaw, viewPitch);
        gaze.onHudOpened(now);
        lastActivityMs = now;
        if (interrupted) {
            showStatus("回中已取消");
        } else {
            showStatus("已回中");
        }
    }

    // ------------------------------------------------------------- HUD input

    @Override
    public void onLook(float dyawDeg, float dpitchDeg) {
        yaw += dyawDeg;
        pitch = Math.max(-70f, Math.min(70f, pitch + dpitchDeg));
        lastActivityMs = SystemClock.elapsedRealtime();
        updateRotation();
    }

    @Override
    public void onRecenterTap() {
        startRecenter();
    }

    // ------------------------------------------------------------- sensors

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event.sensor.getType() != Sensor.TYPE_ROTATION_VECTOR) {
            return;
        }
        SensorManager.getRotationMatrixFromVector(sensorMatrix, event.values);
        updateRotation();
        if (!sensorPrimed) {
            sensorPrimed = true;
            gaze.setAnchor(viewYaw, viewPitch);
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
    }

    private void updateRotation() {
        float[] m = sensorMatrix;
        float xx = m[0], xy = m[1], xz = m[2];
        float yx = m[4], yy = m[5], yz = m[6];
        float zx = m[8], zy = m[9], zz = m[10];

        float[] c0;
        float[] c1;
        float[] c2;
        if (!sensorActive) {
            c0 = new float[]{1f, 0f, 0f};
            c1 = new float[]{0f, 1f, 0f};
            c2 = new float[]{0f, 0f, 1f};
        } else {
            c0 = new float[]{xx, xy, xz};
            c1 = new float[]{yx, yy, yz};
            c2 = new float[]{zx, zy, zz};
        }

        float rollDeg;
        switch (displayRotation()) {
            case Surface.ROTATION_90:
                rollDeg = 90f;
                break;
            case Surface.ROTATION_180:
                rollDeg = 180f;
                break;
            case Surface.ROTATION_270:
                rollDeg = -90f;
                break;
            default:
                rollDeg = 0f;
                break;
        }

        Matrix.setIdentityM(basis, 0);
        basis[0] = c0[0];
        basis[1] = c0[1];
        basis[2] = c0[2];
        basis[4] = c1[0];
        basis[5] = c1[1];
        basis[6] = c1[2];
        basis[8] = c2[0];
        basis[9] = c2[1];
        basis[10] = c2[2];

        // Roll correction first, then the drag, so the drag axes are the user's
        // screen axes in landscape instead of the phone's portrait axes.
        Matrix.setIdentityM(offset, 0);
        Matrix.rotateM(offset, 0, pitch, 1f, 0f, 0f);
        Matrix.rotateM(offset, 0, yaw, 0f, 1f, 0f);
        Matrix.rotateM(offset, 0, -rollDeg, 0f, 0f, 1f);

        Matrix.setIdentityM(worldRot, 0);
        Matrix.rotateM(worldRot, 0, -worldYaw, 0f, 1f, 0f);
        Matrix.rotateM(worldRot, 0, worldPitch, 1f, 0f, 0f);

        Matrix.multiplyMM(tmpMatrix, 0, worldRot, 0, basis, 0);
        Matrix.multiplyMM(composed, 0, tmpMatrix, 0, offset, 0);

        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 3; col++) {
                rowOut[row * 3 + col] = composed[col * 4 + row];
            }
        }
        if (renderer != null) {
            renderer.setRotation(new float[]{
                    rowOut[0], rowOut[1], rowOut[2],
                    rowOut[3], rowOut[4], rowOut[5],
                    rowOut[6], rowOut[7], rowOut[8]
            });
        }

        float fx = -rowOut[2];
        float fy = -rowOut[5];
        float fz = -rowOut[8];
        float len = (float) Math.sqrt(fx * fx + fy * fy + fz * fz);
        if (len > 1e-4f) {
            float vp = (float) Math.toDegrees(Math.asin(Math.max(-1f, Math.min(1f, fy / len))));
            viewYaw = GazeController.wrap((float) Math.toDegrees(Math.atan2(fx, -fz)));
            viewPitch = vp;
        }
    }

    private int displayRotation() {
        try {
            return getWindowManager().getDefaultDisplay().getRotation();
        } catch (Throwable t) {
            return Surface.ROTATION_0;
        }
    }

    // ------------------------------------------------------------- decoder callbacks

    @Override
    public void onVideoInfo(final VideoDecoder source, final String codecName,
                            final boolean hardware, final String mime) {
        ui.post(new Runnable() {
            @Override
            public void run() {
                if (source != renderer.decoder()) {
                    return;
                }
                VideoDecoder d = source;
                String mode = panorama ? "360°" : "SBS";
                String shortMime = mime == null ? "?" : mime.replace("video/", "").toUpperCase();
                videoFormat = mode + " · " + d.width + "×" + d.height + " · " + shortMime;
                if (!hardware) {
                    videoFormat += " · 软解";
                }
                if (!startSeekApplied && startAtMs > 0 && d.durationMs() > 0) {
                    startSeekApplied = true;
                    d.seekTo(startAtMs);
                    showStatus("已回到上次位置");
                }
            }
        });
    }

    @Override
    public void onVideoSize(VideoDecoder source, int width, int height) {
    }

    @Override
    public void onDecodeError(final VideoDecoder source, final String message) {
        if (isFinishing() || isDestroyed()) {
            return;
        }
        ui.post(new Runnable() {
            @Override
            public void run() {
                if (source != renderer.decoder()) {
                    return;
                }
                Toast.makeText(PlayerActivity.this, "解码失败: " + message, Toast.LENGTH_LONG).show();
                showStatus("解码失败");
            }
        });
    }

    // ------------------------------------------------------------- helpers

    private static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    private static String displayName(String path) {
        if (path.startsWith("content://")) {
            try {
                String seg = Uri.parse(path).getLastPathSegment();
                if (seg != null && seg.length() > 0) {
                    return seg;
                }
            } catch (Throwable ignored) {
            }
            return path;
        }
        return new File(path).getName();
    }

    private static String stripExtension(String name) {
        int dot = name.lastIndexOf('.');
        if (dot > 0 && dot > name.length() - 6) {
            return name.substring(0, dot);
        }
        return name;
    }
}
