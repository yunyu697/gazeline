package com.yunyu697.gazeline;

import android.app.Activity;
import android.content.Intent;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.opengl.Matrix;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Surface;
import android.view.View;
import android.view.WindowManager;

import java.util.Locale;

/**
 * §1.12 second stage: the in-viewer calibration page. It is its own screen —
 * no playback HUD, progress bar, subtitle or recenter key. Every edit lives in
 * memory; only 保存并继续 writes to CalibParams, and the back key rolls back to
 * the values that were in force when the page opened.
 */
public class CalibActivity extends Activity
        implements GazeController.Listener, SensorEventListener, CalibView.Listener {

    static final String EXTRA_SENSOR = "sensor";

    private static final long SELF_TEST_MS = 20000;
    private static final int STEP_WARN_AT = 6;

    private CalibView view;
    private GazeController gaze;
    private Feedback feedback;
    private SensorManager sensors;
    private Sensor rotationSensor;
    private boolean sensorWanted;
    private boolean sensorActive;
    private boolean sensorPrimed;

    private CalibParams params;
    private CalibParams saved;
    private int phase = CalibView.PHASE_SELF_TEST;
    private int eyeMode = CalibView.EYE_BOTH;
    private boolean folded;
    private long selfTestStart;
    private int stepsSinceReset;
    private String hint = "";
    private long hintUntil;
    private boolean finishing;

    private final float[] sensorMatrix = new float[16];
    private final float[] remapped = new float[16];
    private final float[] baseRot = new float[9];
    private final float[] dragRot = new float[9];
    private final float[] headRot = new float[9];
    private final float[] mulTmp = new float[9];

    /** sin 45°: an up/down swap needs the phone rolled 45° past vertical. */
    private static final float UP_FLIP_SIN = 0.70710678f;
    private int camRotation = -1;

    private float yaw;
    private float pitch;
    private volatile float viewYaw;
    private volatile float viewPitch;
    private float angularSpeed;
    private long prevTickMs;
    private float prevViewYaw;
    private float prevViewPitch;

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

        params = CalibParams.load(this);
        saved = params.copy();

        sensors = (SensorManager) getSystemService(SENSOR_SERVICE);
        if (sensors != null) {
            rotationSensor = sensors.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);
        }
        sensorWanted = getIntent().getBooleanExtra(EXTRA_SENSOR, true);
        sensorActive = sensorWanted && rotationSensor != null;
        sensorPrimed = !sensorActive;

        Matrix.setIdentityM(sensorMatrix, 0);
        feedback = new Feedback(this);
        gaze = new GazeController(this);

        view = new CalibView(this);
        view.setListener(this);
        view.state().params = params;
        view.state().saved = saved;
        view.state().phase = phase;
        view.state().eyeMode = eyeMode;
        view.state().gaze = gaze.readout();
        setContentView(view);

        applyLayer(false);
        if (sensorPrimed) {
            updateRotation();
            gaze.setAnchor(viewYaw, viewPitch);
        }
        selfTestStart = SystemClock.elapsedRealtime();
        showHint("可读性自测 20 s：先看右半三行样本，再看边缘直线");
    }

    @Override
    protected void onResume() {
        super.onResume();
        finishing = false;
        if (sensorActive) {
            sensors.registerListener(this, rotationSensor, SensorManager.SENSOR_DELAY_GAME);
        }
        if (!sensorActive) {
            updateRotation();
            gaze.setAnchor(viewYaw, viewPitch);
        }
        prevTickMs = 0;
        ui.post(tick);
    }

    @Override
    protected void onPause() {
        super.onPause();
        ui.removeCallbacks(tick);
        if (sensors != null) {
            sensors.unregisterListener(this);
        }
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
        if (gaze.cancelByKey(now)) {
            return;
        }
        // Nothing was persisted, so leaving is the rollback.
        setResult(RESULT_CANCELED);
        finish();
    }

    // ------------------------------------------------------------- tick

    private void onTick() {
        long now = SystemClock.elapsedRealtime();
        updateRotation();

        if (prevTickMs == 0) {
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

        if (phase == CalibView.PHASE_SELF_TEST) {
            long elapsed = now - selfTestStart;
            long remain = SELF_TEST_MS - elapsed;
            view.setSelfTest(Math.max(0L, remain), elapsed / (float) SELF_TEST_MS);
            if (remain <= 0) {
                finishSelfTest(false);
            }
        }
        if (hint.length() > 0 && now > hintUntil) {
            hint = "";
            view.showHint("");
        }

        if (sensorPrimed) {
            gaze.update(now, viewYaw, viewPitch, angularSpeed, true, true);
        }
        view.setView(viewYaw, viewPitch);
        view.setGaze(gaze.readout());
        checkStepWarning();
    }

    private void checkStepWarning() {
        if (stepsSinceReset >= STEP_WARN_AT && !folded) {
            view.setResetPulse(true);
        }
    }

    // ------------------------------------------------------------- layers

    private HudSpec.Target[] targets() {
        if (phase == CalibView.PHASE_SELF_TEST) {
            return CalibSpec.SELF_TEST;
        }
        return folded ? CalibSpec.ADJUST_FOLDED : CalibSpec.ADJUST_FULL;
    }

    private void applyLayer(boolean protect) {
        long now = SystemClock.elapsedRealtime();
        gaze.setLayer(targets(), null, false);
        if (protect) {
            gaze.protect(now, HudSpec.MENU_PROTECT_MS);
        }
    }

    private void showHint(String text) {
        hint = text == null ? "" : text;
        hintUntil = SystemClock.elapsedRealtime() + 3200;
        view.showHint(hint);
    }

    // ------------------------------------------------------------- self test

    private void finishSelfTest(boolean blur) {
        phase = CalibView.PHASE_ADJUST;
        view.setPhase(phase);
        view.setBlurMode(blur);
        folded = blur;
        view.setFolded(folded);
        applyLayer(true);
        if (blur) {
            showHint("已按「三档全糊」处理：先解决屈光，畸变微调已收起");
        }
    }

    // ------------------------------------------------------------- gaze callbacks

    @Override
    public void onGazeFocus(HudSpec.Target target) {
        feedback.focus();
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
    }

    @Override
    public void onGazeConfirm(HudSpec.Target target) {
        feedback.confirm();
        switch (target.id) {
            case TEST_CLEAR:
                finishSelfTest(false);
                showHint("进入微调：一次只调一组，改完立即看效果");
                break;
            case TEST_BLUR:
                finishSelfTest(true);
                break;
            case CAL_IPD_MINUS:
                step(params, CalibSpec.ROW_IPD_INDEX, -1);
                break;
            case CAL_IPD_PLUS:
                step(params, CalibSpec.ROW_IPD_INDEX, 1);
                break;
            case CAL_K1_MINUS:
                step(params, CalibSpec.ROW_K1_INDEX, -1);
                break;
            case CAL_K1_PLUS:
                step(params, CalibSpec.ROW_K1_INDEX, 1);
                break;
            case CAL_K2_MINUS:
                step(params, CalibSpec.ROW_K2_INDEX, -1);
                break;
            case CAL_K2_PLUS:
                step(params, CalibSpec.ROW_K2_INDEX, 1);
                break;
            case CAL_VERT_MINUS:
                step(params, CalibSpec.ROW_VERT_INDEX, -1);
                break;
            case CAL_VERT_PLUS:
                step(params, CalibSpec.ROW_VERT_INDEX, 1);
                break;
            case EYE_LEFT:
                setEyeMode(CalibView.EYE_LEFT_ONLY);
                showHint("左眼模式：右眼全黑，只看左眼是否对齐");
                break;
            case EYE_BOTH:
                setEyeMode(CalibView.EYE_BOTH);
                showHint("双眼模式：两条竖线应重合为一条");
                break;
            case EYE_RIGHT:
                setEyeMode(CalibView.EYE_RIGHT_ONLY);
                showHint("右眼模式：左眼全黑，只看右眼是否对齐");
                break;
            case CAL_RESET:
                resetToSaved();
                break;
            case CAL_UNLOCK:
                folded = false;
                view.setFolded(false);
                applyLayer(true);
                showHint("畸变微调已展开：k1 ±0.02 / k2 ±0.01，中央清楚时别动 k2");
                break;
            case CAL_SAVE:
                saveAndFinish();
                break;
            default:
                break;
        }
        view.setGaze(gaze.readout());
    }

    private void step(CalibParams p, int row, int dir) {
        switch (row) {
            case CalibSpec.ROW_IPD_INDEX:
                p.stepIpd(dir);
                showHint("瞳距 " + p.ipdText() + " · 两条竖线重合即停");
                break;
            case CalibSpec.ROW_K1_INDEX:
                p.stepK1(dir);
                showHint("k1 " + p.k1Text() + " · 看网格直线是否还弯");
                break;
            case CalibSpec.ROW_K2_INDEX:
                p.stepK2(dir);
                showHint("k2 " + p.k2Text() + " · 只修边缘弯曲");
                break;
            default:
                if (eyeMode == CalibView.EYE_LEFT_ONLY) {
                    p.stepVert(true, dir);
                    showHint("左眼垂直 " + degree(p.vertLeftDeg));
                } else if (eyeMode == CalibView.EYE_RIGHT_ONLY) {
                    p.stepVert(false, dir);
                    showHint("右眼垂直 " + degree(p.vertRightDeg));
                } else {
                    p.stepVertBoth(dir);
                    showHint("双眼垂直 " + p.vertText() + "（单眼模式下只调该眼）");
                }
                break;
        }
        stepsSinceReset++;
        view.apply(p);
        if (stepsSinceReset >= STEP_WARN_AT) {
            view.setResetPulse(true);
            showHint("连续 " + stepsSinceReset + " 步未改善：先恢复默认，或摘机重选型号");
        }
    }

    private void setEyeMode(int mode) {
        eyeMode = mode;
        view.setEyeMode(mode);
        applyLayer(true);
    }

    private void resetToSaved() {
        params.copyFrom(saved);
        params.clamp();
        stepsSinceReset = 0;
        view.setResetPulse(false);
        view.apply(params);
        showHint("已恢复上次保存值：" + params.ipdText() + " · k1 " + params.k1Text()
                + " · k2 " + params.k2Text() + " · " + params.vertText());
    }

    private void saveAndFinish() {
        if (finishing) {
            return;
        }
        finishing = true;
        params.save(this);
        setResult(RESULT_OK, new Intent());
        showHint("已保存：" + params.ipdText() + " · k1 " + params.k1Text()
                + " · k2 " + params.k2Text());
        ui.postDelayed(new Runnable() {
            @Override
            public void run() {
                finish();
            }
        }, 520);
    }

    private static String degree(float v) {
        return String.format(Locale.US, "%+.1f°", v);
    }

    // ------------------------------------------------------------- input

    @Override
    public void onLook(float dyawDeg, float dpitchDeg) {
        yaw += dyawDeg;
        pitch = Math.max(-70f, Math.min(70f, pitch + dpitchDeg));
        updateRotation();
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

    /**
     * Same camera basis as the player: remap the sensor matrix into the display
     * frame, then convert East/North/Up into the Y-up world the view angles use.
     */
    private void updateRotation() {
        int rotation = displayRotation();
        if (camRotation < 0 || !sensorActive) {
            camRotation = rotation;
        } else if (rotation != camRotation) {
            boolean bothLandscape = isLandscape(camRotation) && isLandscape(rotation);
            if (!bothLandscape || screenUpComponent(camRotation) < -UP_FLIP_SIN) {
                camRotation = rotation;
            }
        }

        if (sensorActive) {
            int axisX;
            int axisY;
            switch (camRotation) {
                case Surface.ROTATION_90:
                    axisX = SensorManager.AXIS_Y;
                    axisY = SensorManager.AXIS_MINUS_X;
                    break;
                case Surface.ROTATION_180:
                    axisX = SensorManager.AXIS_MINUS_X;
                    axisY = SensorManager.AXIS_MINUS_Y;
                    break;
                case Surface.ROTATION_270:
                    axisX = SensorManager.AXIS_MINUS_Y;
                    axisY = SensorManager.AXIS_X;
                    break;
                default:
                    axisX = SensorManager.AXIS_X;
                    axisY = SensorManager.AXIS_Y;
                    break;
            }
            SensorManager.remapCoordinateSystem(sensorMatrix, axisX, axisY, remapped);
            baseRot[0] = remapped[0];
            baseRot[1] = remapped[1];
            baseRot[2] = remapped[2];
            baseRot[3] = remapped[8];
            baseRot[4] = remapped[9];
            baseRot[5] = remapped[10];
            baseRot[6] = -remapped[4];
            baseRot[7] = -remapped[5];
            baseRot[8] = -remapped[6];
        } else {
            baseRot[0] = 1f;
            baseRot[1] = 0f;
            baseRot[2] = 0f;
            baseRot[3] = 0f;
            baseRot[4] = 1f;
            baseRot[5] = 0f;
            baseRot[6] = 0f;
            baseRot[7] = 0f;
            baseRot[8] = 1f;
        }

        buildYawPitch(dragRot, yaw, pitch);
        multiply3(headRot, baseRot, dragRot);

        float fx = -headRot[2];
        float fy = -headRot[5];
        float fz = -headRot[8];
        float len = (float) Math.sqrt(fx * fx + fy * fy + fz * fz);
        if (len > 1e-4f) {
            float vp = (float) Math.toDegrees(Math.asin(Math.max(-1f, Math.min(1f, fy / len))));
            viewYaw = GazeController.wrap((float) Math.toDegrees(Math.atan2(fx, -fz)));
            viewPitch = vp;
        }
    }

    private static boolean isLandscape(int rotation) {
        return rotation == Surface.ROTATION_90 || rotation == Surface.ROTATION_270;
    }

    /** How strongly the screen-up axis of {@code rotation} points at world up. */
    private float screenUpComponent(int rotation) {
        float[] m = sensorMatrix;
        switch (rotation) {
            case Surface.ROTATION_90:
                return -m[8];
            case Surface.ROTATION_180:
                return -m[9];
            case Surface.ROTATION_270:
                return m[8];
            default:
                return m[9];
        }
    }

    /** Row-major 3x3: rotate about Y by {@code yawDeg}, then about X by {@code pitchDeg}. */
    private static void buildYawPitch(float[] out, float yawDeg, float pitchDeg) {
        float cy = (float) Math.cos(Math.toRadians(yawDeg));
        float sy = (float) Math.sin(Math.toRadians(yawDeg));
        float cp = (float) Math.cos(Math.toRadians(pitchDeg));
        float sp = (float) Math.sin(Math.toRadians(pitchDeg));
        out[0] = cy;
        out[1] = sy * sp;
        out[2] = sy * cp;
        out[3] = 0f;
        out[4] = cp;
        out[5] = -sp;
        out[6] = -sy;
        out[7] = cy * sp;
        out[8] = cy * cp;
    }

    /** out = a * b for row-major 3x3; {@code out} may alias {@code a} or {@code b}. */
    private void multiply3(float[] out, float[] a, float[] b) {
        float[] r = mulTmp;
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 3; col++) {
                r[row * 3 + col] =
                        a[row * 3] * b[col]
                                + a[row * 3 + 1] * b[3 + col]
                                + a[row * 3 + 2] * b[6 + col];
            }
        }
        System.arraycopy(r, 0, out, 0, 9);
    }

    private int displayRotation() {
        try {
            return getWindowManager().getDefaultDisplay().getRotation();
        } catch (Throwable t) {
            return Surface.ROTATION_0;
        }
    }
}
