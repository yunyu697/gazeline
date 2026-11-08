package com.yunyu697.gazeline;

import android.app.Activity;
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
import android.view.Surface;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.Toast;

import java.io.File;

/**
 * Fullscreen stereo playback: GLSurfaceView underneath, head-locked HUD on top.
 * Head tracking comes from TYPE_ROTATION_VECTOR; without a sensor the panorama
 * still renders and drag still works (used on the emulator).
 */
public class PlayerActivity extends Activity
        implements PlayerHudView.Listener, VideoDecoder.Listener, SensorEventListener {

    static final String EXTRA_PATH = "path";
    static final String EXTRA_MODE = "mode";
    static final String EXTRA_SENSOR = "sensor";

    private GLSurfaceView glView;
    private StereoRenderer renderer;
    private PlayerHudView hud;
    private SensorManager sensors;
    private Sensor rotationSensor;

    private boolean panorama;
    private boolean sensorWanted;
    private boolean sensorActive;
    private String dataSource;
    private String videoName = "?";

    private final float[] sensorMatrix = new float[16];
    private final float[] basis = new float[16];
    private final float[] offset = new float[16];
    private final float[] composed = new float[16];
    private final float[] rowOut = new float[9];

    private float yaw;
    private float pitch;

    private long lastStatsMs;
    private long lastDecoded;
    private long lastDrawn;
    private float decodeFps;
    private float drawnFps;

    private final Handler ui = new Handler(Looper.getMainLooper());

    private final Runnable statsTask = new Runnable() {
        @Override
        public void run() {
            refreshHud();
            ui.postDelayed(this, 500);
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
        videoName = displayName(dataSource);

        sensors = (SensorManager) getSystemService(SENSOR_SERVICE);
        if (sensors != null) {
            rotationSensor = sensors.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);
        }
        sensorActive = sensorWanted && rotationSensor != null;

        Matrix.setIdentityM(sensorMatrix, 0);
        sensorMatrix[0] = 1;
        sensorMatrix[5] = 1;
        sensorMatrix[10] = 1;
        sensorMatrix[15] = 1;

        glView = new GLSurfaceView(this);
        glView.setEGLContextClientVersion(2);
        renderer = new StereoRenderer(this, dataSource, panorama, this);
        glView.setRenderer(renderer);
        glView.setRenderMode(GLSurfaceView.RENDERMODE_CONTINUOUSLY);

        hud = new PlayerHudView(this);
        hud.setListener(this);

        FrameLayout root = new FrameLayout(this);
        root.addView(glView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        root.addView(hud, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        setContentView(root);

        updateRotation();
    }

    @Override
    protected void onResume() {
        super.onResume();
        glView.onResume();
        renderer.restartDecoder();
        if (sensorActive) {
            sensors.registerListener(this, rotationSensor, SensorManager.SENSOR_DELAY_GAME);
        }
        ui.post(statsTask);
    }

    @Override
    protected void onPause() {
        super.onPause();
        ui.removeCallbacks(statsTask);
        if (sensors != null) {
            sensors.unregisterListener(this);
        }
        renderer.stopDecoder();
        glView.onPause();
    }

    // ------------------------------------------------------------- HUD input

    @Override
    public void onLook(float dyawDeg, float dpitchDeg) {
        yaw += dyawDeg;
        pitch = Math.max(-70f, Math.min(70f, pitch + dpitchDeg));
        if (!sensorActive) {
            updateRotation();
        }
    }

    @Override
    public void onRecenter() {
        yaw = 0f;
        pitch = 0f;
        updateRotation();
        Toast.makeText(this, "已回中", Toast.LENGTH_SHORT).show();
    }

    // ------------------------------------------------------------- sensors

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event.sensor.getType() != Sensor.TYPE_ROTATION_VECTOR) {
            return;
        }
        SensorManager.getRotationMatrixFromVector(sensorMatrix, event.values);
        updateRotation();
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
    }

    private void updateRotation() {
        float[] m = sensorMatrix;
        // columns of the world<-device rotation: X, Y, Z device axes in world space
        float xx = m[0], xy = m[1], xz = m[2];
        float yx = m[4], yy = m[5], yz = m[6];
        float zx = m[8], zy = m[9], zz = m[10];

        // Camera basis stays a proper rotation (det +1, no mirroring). Without a
        // sensor the frame is the identity, so the initial view is the panorama centre.
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

        synchronized (rowOut) {
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

            Matrix.setIdentityM(offset, 0);
            Matrix.rotateM(offset, 0, -rollDeg, 0f, 0f, 1f);
            Matrix.rotateM(offset, 0, pitch, 1f, 0f, 0f);
            Matrix.rotateM(offset, 0, yaw, 0f, 1f, 0f);
            Matrix.multiplyMM(composed, 0, basis, 0, offset, 0);

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
    public void onVideoInfo(VideoDecoder source, String codecName, boolean hardware, String mime) {
        if (source != renderer.decoder()) {
            return;
        }
        ui.post(new Runnable() {
            @Override
            public void run() {
                hud.showPanel();
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
                refreshHud();
            }
        });
    }

    // ------------------------------------------------------------- HUD text

    private void refreshHud() {
        VideoDecoder d = renderer.decoder();
        String codec = d == null ? "n/a" : d.codecName;
        String hw = d == null ? "?" : (d.hardwareAccelerated ? "YES" : "NO");
        int w = d == null ? 0 : d.width;
        int h = d == null ? 0 : d.height;
        long decoded = d == null ? 0 : d.decodedFrames;
        long late = d == null ? 0 : d.lateFrames;
        String err = d == null ? null : d.error;
        String lens = panorama
                ? "FOV 90° · k1 -0.20 · k2 0.032 · IPD 0°"
                : "FOV 85° · k1 -0.26 · k2 0.045 · 屏宽 70°";

        long now = android.os.SystemClock.elapsedRealtime();
        if (lastStatsMs > 0) {
            float secs = (now - lastStatsMs) / 1000f;
            if (secs > 0.2f) {
                decodeFps = (decoded - lastDecoded) / secs;
                drawnFps = (renderer.drawnFrames() - lastDrawn) / secs;
            }
        }
        lastStatsMs = now;
        lastDecoded = decoded;
        lastDrawn = renderer.drawnFrames();

        String[] lines = new String[]{
                "GAZELINE · M2 " + (panorama ? "360" : "SBS"),
                "文件  " + videoName,
                "解码器 " + codec,
                "硬解  " + hw,
                "分辨率 " + (w > 0 ? w + "×" + h : "等待首帧"),
                "解码帧 " + decoded + " · 迟到 " + late,
                "GL 帧 " + renderer.drawnFrames(),
                "帧率   解码/绘制 " + one(decodeFps) + " / " + one(drawnFps) + " fps",
                "镜头   " + lens,
                "传感器 " + (sensorActive ? "开" : "关") + " · 拖动转头 · 双击回中"
        };
        if (err != null) {
            lines = new String[]{
                    "GAZELINE · 解码失败",
                    err,
                    "文件 " + videoName,
                    "解码器 " + codec
            };
        }
        hud.setLines(lines);
    }

    private static String one(float v) {
        return String.format("%.1f", v);
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
}
