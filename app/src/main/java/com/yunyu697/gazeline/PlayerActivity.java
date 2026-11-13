package com.yunyu697.gazeline;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Bitmap;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.media.MediaDataSource;
import android.net.Uri;
import android.opengl.Matrix;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.DisplayCutout;
import android.view.Surface;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.Toast;

import com.google.vr.sdk.base.GvrView;
import com.google.vr.sdk.base.GvrViewerParams;

import java.io.File;

/**
 * Fullscreen stereo playback with the gaze layer. Orientation is
 * world←camera = recenterCorrection * sensorBasis * roll/drag; the gaze
 * controller anchors its HUD to the video's centre — world (0, 0) after the
 * recenter correction — so the bar, the menu and the file browser keep one
 * fixed place in the picture instead of following the head. On the emulator
 * (no sensor) drag simulates head movement.
 */
public class PlayerActivity extends Activity
        implements HudView.Listener, GazeController.Listener,
        VideoDecoder.Listener, AudioPlayer.Listener, SensorEventListener {

    static final String EXTRA_PATH = "path";
    static final String EXTRA_MODE = "mode";
    static final String EXTRA_SENSOR = "sensor";
    static final String EXTRA_POSITION = "position";
    static final String EXTRA_SUBTITLE = "subtitle";
    static final String EXTRA_BROWSE = "browse";

    private static final int OVERLAY_NONE = 0;
    private static final int OVERLAY_MENU = 1;
    private static final int OVERLAY_CONFIRM = 2;
    private static final int OVERLAY_BROWSER = 3;
    private static final int OVERLAY_PROJ = 4;

    private static final long HISTORY_EVERY_MS = 5000L;

    private static final int REC_IDLE = 0;
    private static final int REC_COUNTDOWN = 1;
    private static final int REC_MASK_IN = 2;
    private static final int REC_MASK_OUT = 3;

    /** Grace window after the gaze trigger: turn back to a comfortable pose. */
    private static final long RECENTER_GRACE_MS = 3500;

    private GvrView gvrView;
    private GvrRenderer renderer;
    private HudView hud;
    private SensorManager sensors;
    private Sensor rotationSensor;
    private Feedback feedback;
    private GazeController gaze;
    private GazeController menuGaze;

    private final HudView.Frame frame = new HudView.Frame();
    private Bitmap hudBitmap;
    private long renderedHudSig = -1;
    /** When the SDK's trigger emulation last fired, to fold the tap paths. */
    private long sdkTriggerMs;
    /** When the screen was last pressed, to tell a tap from the viewer button. */
    private long lastTouchDownMs;
    /** SDK trigger parked on a finger-down, to be re-issued on the matching up. */
    private boolean pendingScreenTap;
    /** A 1024² redraw plus upload is heavy; ~15 Hz is plenty for the bar. */
    private static final long HUD_RENDER_MIN_MS = 65L;
    private long lastHudRenderMs;
    private long lastTriggerMs;
    /** Inter-lens distance in millimetres; 0 means "use the viewer default". */
    private int ipdMm;
    private GvrViewerParams viewerParams;

    private ProjectionMode mode = ProjectionMode.FLAT_MONO;
    /** The user picked a projection in the menu; detection must not undo it. */
    private boolean modeUserSet;
    /** One-time hint that the top/bottom edges bring the controls back. */
    private long wakeHintUntil;
    private boolean wakeHintShown;
    private boolean sensorWanted;
    private boolean sensorActive;
    private String dataSource;
    private String videoName = "?";
    private String videoFormat = "正在打开…";
    private int videoWidth;
    private int videoHeight;
    private String videoMime = "?";
    private boolean videoSoft;

    private final float[] sensorMatrix = new float[16];
    private final float[] remapped = new float[16];
    private final float[] baseRot = new float[9];
    private final float[] dragRot = new float[9];
    private final float[] worldMat = new float[9];
    private final float[] headRot = new float[9];
    private final float[] mulTmp = new float[9];

    /**
     * The 180° choice is the screen's own rotation plus a user toggle, and it
     * is frozen for the session: guessing it from head roll is what used to
     * spin the picture while watching.
     */
    private boolean flipImage;

    // Screen rotation the camera currently uses. Chosen from the sensors after
    // the first frame: the system's 90/270 guess churns as soon as the phone is
    // near a flat hold, and following it only swung the scene about.
    private int camRotation = -1;

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
    private boolean smbMode;
    private SmbMediaDataSource smbSource;
    private boolean smbOpening;
    private boolean smbApplied;
    private boolean resumed;
    private boolean browserOnly;
    private long lastHistoryMs;
    private boolean subtitleOn;
    private SubtitleTrack subtitleTrack;
    private String subtitleSource;
    private boolean subtitleLoading;
    private String displayMode = "";

    // In-VR brightness is a per-window override, so it needs no WRITE_SETTINGS
    // and does not change the phone's own setting.
    private static final float[] BRIGHT_LEVELS = {
            0.2f, 0.3f, 0.4f, 0.5f, 0.6f, 0.7f, 0.8f, 0.9f, 1f
    };
    private static final float[] WAKE_PITCHES = {
            HudSpec.WAKE_PITCH_FAST, HudSpec.WAKE_PITCH_DEFAULT, HudSpec.WAKE_PITCH_DEEP
    };
    private static final String[] WAKE_LABELS = {"灵敏", "标准", "深沉"};
    private static final String[] SUB_STYLE_LABELS = {"标准", "大字", "小字·无描边"};
    private static final float[] SUB_STYLE_SCALE = {1f, 1.22f, 0.86f};
    private static final String DISPLAY_PREFS = "gazeline_display";

    private Sensor lightSensor;
    private Sensor accelSensor;
    private int brightLevel = 4;

    // Double-tap on the phone's back: an accelerometer spike pair. The video
    // and the HUD keep moving the phone, so only a sharp pair counts.
    private final float[] gravity = new float[3];
    private boolean gravityReady;
    private boolean accelWasHigh;
    private long lastTapMs;
    private long tapCooldownUntil;
    private static final float TAP_PEAK = 3.2f;
    private static final float TAP_REST = 1.2f;
    private static final float TAP_GRAVITY_ALPHA = 0.22f;
    private static final long TAP_GAP_MIN_MS = 60;
    private static final long TAP_GAP_MAX_MS = 450;
    private static final long TAP_COOLDOWN_MS = 1200;

    // Timeline drag: gaze grabs the bar, head yaw scrubs while the gaze stays
    // on it. Independent of the gaze focus, which a fast turn would cancel.
    private boolean scrubbing;
    private boolean scrubResume;
    private float scrubBaseProgress;
    private float scrubBaseRelYaw;
    private long scrubTargetMs;
    private long scrubSeekMs;
    private boolean autoBright;
    private int wakeLevel = 1;
    private int subStyle;
    private int menuPage;
    private float lux = 50f;
    private float autoTarget = -1f;
    private long lastBrightApplyMs;

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

    // in-VR file browser (SMB), same configuration as the phone library
    private String browserDir = "";
    private boolean browserLoading;
    private String browserHint = "";
    private int browserPage;
    private int browserPages = 1;
    private int browserGen;
    private final java.util.List<HudSpec.Target> browserTargets = new java.util.ArrayList<>();
    private final java.util.List<BrowserRow> browserRows = new java.util.ArrayList<>();
    private final java.util.Set<String> thumbPending = new java.util.HashSet<>();

    static final class BrowserRow {
        String title = "";
        String detail = "";
        String path = "";
        /** Playable URL for a video row; doubles as the poster cache key. */
        String source = "";
        int kind;
    }

    // recenter: grace countdown, then a short mask while the correction lands
    private int recPhase = REC_IDLE;
    private long recPhaseStart;
    private float recCountdown;
    private float maskAlpha;
    private float flashAlpha;
    private long flashStart;
    private long settleSince;

    /**
     * The launch pose (phone in hand, on a table) must not define the video
     * centre: it is not where the head will be. The reference is established
     * once the device settles in a wearing pose instead. {@code interacted}
     * means the user already acted, so silently moving the view would be rude.
     */
    private boolean viewAligned;
    private boolean interacted;
    private long steadySince;
    private int alignCount;
    private long lastAlignMs;
    private float alignYawRef;
    private float alignPitchRef;

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
        if (Build.VERSION.SDK_INT >= 28) {
            // Let the picture and the black surround reach the camera cutout;
            // the HUD layer pulls its own content inside the safe box instead.
            WindowManager.LayoutParams lp = getWindow().getAttributes();
            lp.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            getWindow().setAttributes(lp);
        }
        displayMode = DisplayModes.request(this);

        dataSource = getIntent().getStringExtra(EXTRA_PATH);
        boolean browseRequest = getIntent().getBooleanExtra(EXTRA_BROWSE, false);
        if (dataSource == null || dataSource.length() == 0) {
            if (!browseRequest) {
                Toast.makeText(this, "未指定视频文件", Toast.LENGTH_SHORT).show();
                finish();
                return;
            }
            browserOnly = true;
            dataSource = "";
            playing = false;
        }
        sensorWanted = getIntent().getBooleanExtra(EXTRA_SENSOR, true);
        startAtMs = Math.max(0L, getIntent().getLongExtra(EXTRA_POSITION, 0L));
        smbMode = !browserOnly && SmbSettings.isSmb(dataSource);
        videoName = browserOnly ? "片库" : displayName(dataSource);
        // Projection: an explicit extra wins, otherwise the filename decides and
        // the track shape refines it once the decoder reports the size. The
        // library's global toggle is only the fallback for unrecognised names.
        ProjectionMode requested = ProjectionMode.parse(getIntent().getStringExtra(EXTRA_MODE));
        ProjectionMode fallback = ProjectionMode.parse(SmbSettings.load(this).projection);
        if (fallback == null) {
            fallback = new ProjectionMode(ProjectionMode.EQ360, ProjectionMode.MONO);
        }
        mode = requested != null ? requested
                : ProjectionMode.detect(videoName, 0, 0, fallback);
        modeUserSet = requested != null;
        if (!modeUserSet) {
            // A type picked in the player last time outranks name detection;
            // the picker's 自动 row clears it again.
            ProjectionMode saved = ProjectionMode.parse(getSharedPreferences(
                    DISPLAY_PREFS, MODE_PRIVATE).getString("proj", null));
            if (saved != null) {
                mode = saved;
                modeUserSet = true;
            }
        }
        String subtitle = getIntent().getStringExtra(EXTRA_SUBTITLE);
        if (!browserOnly && (subtitle == null || subtitle.length() == 0)) {
            subtitle = SubtitleTrack.findSidecar(dataSource);
        }
        if (!browserOnly && subtitle != null && subtitle.length() > 0) {
            loadSubtitle(subtitle);
        }

        sensors = (SensorManager) getSystemService(SENSOR_SERVICE);
        if (sensors != null) {
            rotationSensor = sensors.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);
            lightSensor = sensors.getDefaultSensor(Sensor.TYPE_LIGHT);
            accelSensor = sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        }
        sensorActive = sensorWanted && rotationSensor != null;

        Matrix.setIdentityM(sensorMatrix, 0);

        feedback = new Feedback(this);
        gaze = new GazeController(this);
        menuGaze = new GazeController(this);
        menuGaze.setLayer(HudSpec.MENU_ROWS_P1, false);
        gaze.setTriggerMode(true);
        menuGaze.setTriggerMode(true);
        loadDisplayPrefs();

        if (!browserOnly) {
            gvrView = new GvrView(this);
            renderer = smbMode
                    ? new GvrRenderer(this, (MediaDataSource) null, mode, this, this)
                    : new GvrRenderer(this, dataSource, mode, this, this);
            renderer.setCalib(CalibParams.load(this));
            gvrView.setTransitionViewEnabled(false);
            // No paired viewer means the SDK would sit in its setup overlay;
            // the built-in Cardboard profile gets straight stereo playback.
            // The lens distance is the only viewer value worth exposing: it is
            // what users actually tune, and playback keeps running while they do.
            viewerParams = GvrViewerParams.cardboardV1ViewerParams();
            if (ipdMm <= 0) {
                ipdMm = Math.round(viewerParams.getInterLensDistance() * 1000f);
            } else {
                viewerParams.setInterLensDistance(ipdMm / 1000f);
            }
            gvrView.updateGvrViewerParams(viewerParams);
            gvrView.setDistortionCorrectionEnabled(!isEmulator());
            gvrView.setRenderer(renderer);
            // The SDK fires its trigger callback the moment a finger goes down
            // (its tap emulation runs on DOWN, not on release), which would act
            // on the press that merely starts a head-turn drag. A callback that
            // rides on a fresh screen-down is therefore parked and re-issued on
            // the matching ACTION_UP only when the finger never left the tap
            // radius; a callback with no recent touch is the viewer's physical
            // button or magnet and acts at once.
            gvrView.setOnCardboardTriggerListener(new Runnable() {
                @Override
                public void run() {
                    long now = SystemClock.elapsedRealtime();
                    sdkTriggerMs = now;
                    if (now - lastTouchDownMs < 120) {
                        pendingScreenTap = true;
                        return;
                    }
                    onTrigger();
                }
            });
            gvrView.setOnTouchListener(new View.OnTouchListener() {
                private float lastX;
                private float lastY;
                private float moved;

                @Override
                public boolean onTouch(View v, android.view.MotionEvent e) {
                    int a = e.getActionMasked();
                    if (a == android.view.MotionEvent.ACTION_DOWN) {
                        lastTouchDownMs = SystemClock.elapsedRealtime();
                        pendingScreenTap = false;
                        lastX = e.getX();
                        lastY = e.getY();
                        moved = 0f;
                    } else if (a == android.view.MotionEvent.ACTION_MOVE) {
                        float dx = e.getX() - lastX;
                        float dy = e.getY() - lastY;
                        lastX = e.getX();
                        lastY = e.getY();
                        moved += Math.abs(dx) + Math.abs(dy);
                        // Past the tap radius the drag is the head turn of a
                        // device without a tracker (the emulator): the same
                        // trim the old attached-HudView gesture applied.
                        if (moved >= 48f) {
                            yaw += dx / 10f;
                            pitch -= dy / 10f;
                        }
                    } else if (a == android.view.MotionEvent.ACTION_UP) {
                        boolean parkedTap = pendingScreenTap;
                        pendingScreenTap = false;
                        if (moved < 48f
                                && (parkedTap
                                    || SystemClock.elapsedRealtime() - sdkTriggerMs > 1000)) {
                            onTrigger();
                        }
                    }
                    return false;
                }
            });
        }

        hud = new HudView(this);
        hud.setListener(this);
        hud.setVrMode(gvrView != null);
        hud.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
            @Override
            public WindowInsets onApplyWindowInsets(View v, WindowInsets insets) {
                applyCutoutInsets(insets);
                return insets;
            }
        });

        FrameLayout root = new FrameLayout(this);
        if (gvrView != null) {
            root.addView(gvrView, new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        } else {
            root.setBackgroundColor(0xFF05070A);
            // No stereo pipeline to draw into: the browser page keeps the old
            // flat, two-eye layout on the phone screen.
            root.addView(hud, new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        }
        setContentView(root);

        sessionStartMs = SystemClock.elapsedRealtime();
        lastActivityMs = sessionStartMs;
        showStatus(browserOnly ? "片库 · 凝视选择" : "显示 " + displayMode);
        updateRotation();
        sensorPrimed = true;
        gaze.setAnchor(0f, 0f);
        if (smbMode) {
            videoFormat = "SMB · 正在连接…";
            beginSmbOpen();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (gvrView != null) {
            gvrView.onResume();
            // The SDK restores its own saved viewer while resuming, which would
            // undo a lens distance tuned in an earlier session; re-apply ours.
            if (viewerParams != null) {
                gvrView.updateGvrViewerParams(viewerParams);
            }
        }
        if (renderer != null) {
            renderer.setFreeze(false);
            renderer.setCalib(CalibParams.load(this));
            renderer.restartDecoder();
            renderer.setSpeed(playbackRate);
            if (!playing) {
                renderer.pausePlayback();
            }
        }
        if (accelSensor != null && !browserOnly) {
            sensors.registerListener(this, accelSensor, SensorManager.SENSOR_DELAY_GAME);
        }
        if (hud != null) {
            hud.requestApplyInsets();
        }
        registerLight();
        autoTarget = -1f;
        refreshBrightness(SystemClock.elapsedRealtime());
        recPhase = REC_IDLE;
        maskAlpha = 0f;
        flashAlpha = 0f;
        overlay = OVERLAY_NONE;
        overlayAlpha = 0f;
        overlayHint = "";
        overlayHintUntil = 0;
        lastTickMs = 0;
        lastActivityMs = SystemClock.elapsedRealtime();
        sensorPrimed = true;
        gaze.setAnchor(0f, 0f);
        resumed = true;
        if (smbSource != null && !smbApplied && renderer != null) {
            smbApplied = true;
            renderer.setMediaSource(smbSource);
        }
        if (browserOnly) {
            openBrowser();
        }
        ui.post(tick);
    }

    @Override
    protected void onPause() {
        super.onPause();
        resumed = false;
        recordProgress();
        ui.removeCallbacks(tick);
        if (sensors != null) {
            sensors.unregisterListener(this);
        }
        if (renderer != null) {
            renderer.setFreeze(false);
            renderer.stopDecoder();
        }
        if (gvrView != null) {
            gvrView.onPause();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (smbSource != null) {
            smbSource.close();
            smbSource = null;
        }
        if (gvrView != null) {
            gvrView.shutdown();
        }
        if (renderer != null) {
            renderer.release();
        }
        if (feedback != null) {
            feedback.release();
        }
    }

    /** Persists the watch position so phone and VR share one resume point. */
    private void recordProgress() {
        if (browserOnly || dataSource == null || dataSource.length() == 0) {
            return;
        }
        VideoDecoder d = renderer == null ? null : renderer.decoder();
        if (d == null) {
            return;
        }
        long position = d.positionMs();
        if (position <= 0L) {
            return;
        }
        PlayHistory.record(this, dataSource, stripExtension(videoName),
                mode.key(), position, d.durationMs());
        lastHistoryMs = SystemClock.elapsedRealtime();
    }

    /** Opens the SMB file off the UI/GL thread; playback starts when ready. */
    private void beginSmbOpen() {
        if (smbOpening || smbSource != null) {
            return;
        }
        smbOpening = true;
        videoFormat = "SMB · 连接中…";
        showStatus("SMB 连接中…");
        final SmbSettings settings = SmbSettings.load(this);
        final String url = dataSource;
        new Thread(new Runnable() {
            @Override
            public void run() {
                SmbMediaDataSource opened = null;
                String failure = null;
                try {
                    opened = SmbMediaDataSource.open(settings, url);
                } catch (Throwable t) {
                    failure = t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
                }
                final SmbMediaDataSource source = opened;
                final String error = failure;
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        smbOpening = false;
                        if (isFinishing() || isDestroyed()) {
                            if (source != null) {
                                source.close();
                            }
                            return;
                        }
                        if (source == null) {
                            showSmbFailure(error);
                            return;
                        }
                        smbSource = source;
                        videoFormat = "SMB · " + source.label();
                        showStatus("SMB 已连接 · " + source.label());
                        if (resumed) {
                            smbApplied = true;
                            renderer.setMediaSource(source);
                        }
                    }
                });
            }
        }, "gazeline-smb").start();
    }

    private void showSmbFailure(String message) {
        videoFormat = "SMB · 连接失败";
        showStatus("SMB 连接失败");
        new AlertDialog.Builder(this)
                .setTitle("SMB 连接失败")
                .setMessage((message == null ? "未知错误" : message)
                        + "\n\n请确认手机与电脑在同一网段、共享已开启、账号密码正确，且 445 端口可达。")
                .setPositiveButton("重试", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        beginSmbOpen();
                    }
                })
                .setNegativeButton("返回", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        finish();
                    }
                })
                .setCancelable(false)
                .show();
    }

    @Override
    public void onBackPressed() {
        long now = SystemClock.elapsedRealtime();
        if (scrubbing) {
            endScrub(now, true);
            return;
        }
        if (overlay == OVERLAY_MENU) {
            closeOverlay("菜单已关闭");
            return;
        }
        if (overlay == OVERLAY_PROJ) {
            closeOverlay("画面类型已关闭");
            return;
        }
        if (overlay == OVERLAY_CONFIRM) {
            closeOverlay("已取消退出");
            return;
        }
        if (overlay == OVERLAY_BROWSER) {
            if (browserOnly) {
                finish();
            } else {
                closeOverlay("已关闭文件管理");
            }
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

        maybeAutoAlign(now);
        updateRecenter(now);
        applyPackEvidence();

        if (!browserOnly && now - lastHistoryMs >= HISTORY_EVERY_MS) {
            recordProgress();
        }

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
        updateScrub(now);

        GazeController.Readout g = gaze.readout();
        GazeController.Readout mg = menuGaze.readout();
        if (g.focus != null || (overlay != OVERLAY_NONE && mg.focus != null)) {
            lastActivityMs = now;
        }

        if (now > statusUntil) {
            statusText = "";
        }

        // The settings panel owns the screen: while it is up no HUD layer may
        // appear, so the wake bar and its lesson text stay away from it.
        float wakeAlpha = playing && overlay == OVERLAY_NONE
                ? Math.max(0f, 1f - hudAlpha) : 0f;
        if (!wakeHintShown && playing && wakeAlpha > 0.8f) {
            // One short lesson right after the controls first tuck away; after
            // that the picture stays clean and the edges only react on approach.
            wakeHintShown = true;
            wakeHintUntil = now + 4500L;
        }

        refreshBrightness(now);

        buildFrame(now, hudAlpha, wakeAlpha);
        hud.setFrame(frame, now);
        if (renderer != null) {
            updateHudPose(hudAlpha);
            renderHud(now);
        }
    }

    /**
     * Hands the GL layer the gaze ray for the reticle, the anchor of whichever
     * layer is on screen, and the full-view dim used by the overlays.
     */
    private void updateHudPose(float hudAlpha) {
        GazeController.Readout active =
                overlay != OVERLAY_NONE ? menuGaze.readout() : gaze.readout();
        float reticleA = overlay != OVERLAY_NONE ? overlayAlpha : hudAlpha;
        float dim = Math.max(maskAlpha,
                overlay != OVERLAY_NONE ? 0.46f * overlayAlpha : 0f);
        renderer.setHudPose(viewYaw, viewPitch, active.anchorYaw, active.anchorPitch,
                reticleA, dim, active.focus != null ? 0xFFB0FFE0 : 0xFFEAF4FF);
    }

    /**
     * Rasterises the HUD for the VR renderer, on the UI thread and only when
     * the frame token changes. {@link GvrView} cannot host a child View: it
     * would be neither duplicated per eye nor bent with the lenses, so the
     * layer is handed over as a texture instead.
     */
    private void renderHud(long now) {
        long sig = hud.signature();
        if (hudBitmap != null && sig == renderedHudSig) {
            return;
        }
        if (renderedHudSig != -1 && now - lastHudRenderMs < HUD_RENDER_MIN_MS) {
            return;
        }
        if (hudBitmap == null) {
            hudBitmap = Bitmap.createBitmap(1024, 1024, Bitmap.Config.ARGB_8888);
        }
        renderedHudSig = sig;
        lastHudRenderMs = now;
        synchronized (renderer.hudLock) {
            hud.renderEye(hudBitmap);
            renderer.invalidateHud(hudBitmap);
        }
    }

    private float computeHudAlpha(long now) {
        if (scrubbing) {
            // The bar is the feedback for the drag: keep it fully lit.
            return 1f;
        }
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
        if (overlay == OVERLAY_BROWSER) {
            // The file browser stays up until 关闭/返回; browsing means looking
            // around the room, so leaving the panel must not close it.
            return;
        }
        if (counting) {
            menuLeaveSince = 0;
        } else {
            boolean inside = overlay == OVERLAY_MENU
                    ? HudSpec.inMenuPanel(mg.relYaw, mg.relPitch)
                    : overlay == OVERLAY_PROJ
                    ? HudSpec.inProjPanel(mg.relYaw, mg.relPitch)
                    : HudSpec.inConfirmPanel(mg.relYaw, mg.relPitch);
            if (inside) {
                menuLeaveSince = 0;
            } else if (menuLeaveSince == 0) {
                menuLeaveSince = now;
            } else if (now - menuLeaveSince >= HudSpec.MENU_LEAVE_CLOSE_MS) {
                closeOverlay(overlay == OVERLAY_MENU ? "菜单已关闭"
                        : overlay == OVERLAY_PROJ ? "画面类型已关闭" : "已取消退出");
                return;
            }
        }
        if (overlay != OVERLAY_CONFIRM && now - menuOpenedAt >= HudSpec.MENU_AUTO_CLOSE_MS) {
            closeOverlay(overlay == OVERLAY_MENU ? "菜单已关闭" : "画面类型已关闭");
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
        menuPage = 0;
        menuGaze.setLayer(menuRows(), false);
        menuGaze.setAnchor(0f, 0f);
        menuGaze.onHudOpened(now);
        menuGaze.protect(now, HudSpec.MENU_PROTECT_MS);
        feedback.menuOpen();
        lastActivityMs = now;
    }

    /**
     * Page 1 holds the controls touched during playback; page 2 collects the
     * ones that are set once (volume, server, subtitle style) plus file/exit,
     * so neither page has to squeeze extra rows into the panel.
     */
    private HudSpec.Target[] menuRows() {
        if (overlay == OVERLAY_PROJ) {
            return HudSpec.PROJ_ROWS;
        }
        return menuPage == 0 ? HudSpec.MENU_ROWS_P1 : HudSpec.MENU_ROWS_P2;
    }

    /**
     * Compact picker straight from the HUD: the automatic choice plus the six
     * combinations people actually use, so no file has to be cycled through the
     * five families one row at a time.
     */
    private void openProjPanel() {
        long now = SystemClock.elapsedRealtime();
        overlay = OVERLAY_PROJ;
        overlayStart = now;
        overlayAlpha = 0f;
        overlayHint = "";
        overlayHintUntil = 0;
        menuOpenedAt = now;
        menuLeaveSince = 0;
        menuGaze.setLayer(HudSpec.PROJ_ROWS, false);
        menuGaze.setAnchor(0f, 0f);
        menuGaze.onHudOpened(now);
        menuGaze.protect(now, HudSpec.MENU_PROTECT_MS);
        feedback.menuOpen();
        lastActivityMs = now;
    }

    private static final ProjectionMode[] PROJ_PRESETS = {
            null,
            new ProjectionMode(ProjectionMode.EQ360, ProjectionMode.MONO),
            new ProjectionMode(ProjectionMode.EQ360, ProjectionMode.SBS),
            new ProjectionMode(ProjectionMode.EQ180, ProjectionMode.SBS),
            new ProjectionMode(ProjectionMode.EQ180, ProjectionMode.TB),
            ProjectionMode.FLAT_MONO,
            new ProjectionMode(ProjectionMode.FLAT, ProjectionMode.SBS),
    };

    /**
     * Picker row → mode. 自动 drops the stored choice and re-detects, the rest
     * apply at once and are remembered for the next file; a user pick also
     * stops the packing evidence from overriding it mid-playback.
     */
    private void applyProjectionOption(HudSpec.Target target) {
        int option = target.index;
        if (option <= 0) {
            modeUserSet = false;
            getSharedPreferences(DISPLAY_PREFS, MODE_PRIVATE).edit().remove("proj").apply();
            ProjectionMode detected = ProjectionMode.detect(videoName, videoWidth, videoHeight, null);
            if (renderer != null) {
                renderer.setMode(detected);
            }
            mode = detected;
            videoFormat = formatLine();
            String hint = "自动识别 · " + detected.label();
            setOverlayHint(hint);
            showStatus("画面类型 " + hint);
            return;
        }
        if (option >= PROJ_PRESETS.length || PROJ_PRESETS[option] == null) {
            return;
        }
        ProjectionMode picked = PROJ_PRESETS[option];
        getSharedPreferences(DISPLAY_PREFS, MODE_PRIVATE).edit()
                .putString("proj", picked.key()).apply();
        applyProjection(picked, true);
        setOverlayHint("画面类型 " + picked.label());
    }

    /** Which picker row is in force, or -1 when the mode matches no preset. */
    private int currentProjOption() {
        if (!modeUserSet) {
            return 0;
        }
        for (int i = 1; i < PROJ_PRESETS.length; i++) {
            ProjectionMode p = PROJ_PRESETS[i];
            if (p != null && p.proj == mode.proj && p.pack == mode.pack) {
                return i;
            }
        }
        return -1;
    }

    private void setMenuPage(int page) {
        long now = SystemClock.elapsedRealtime();
        menuPage = page == 0 ? 0 : 1;
        menuGaze.setLayer(menuRows(), false);
        menuGaze.setAnchor(0f, 0f);
        menuGaze.onHudOpened(now);
        menuGaze.protect(now, HudSpec.MENU_PROTECT_MS);
        overlayHint = menuPage == 0 ? "已回到第一页" : "第二页：音量 / 服务器 / 字幕样式";
        overlayHintUntil = now + 1800;
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
        menuGaze.setLayer(HudSpec.CONFIRM_TARGETS, false);
        menuGaze.setAnchor(0f, 0f);
        menuGaze.onHudOpened(now);
        menuGaze.protect(now, HudSpec.CONFIRM_PROTECT_MS);
        feedback.ready();
        lastActivityMs = now;
    }

    private void closeOverlay(String status) {
        long now = SystemClock.elapsedRealtime();
        Log.i("Gazeline", "closeOverlay '" + status + "' ov=" + overlay
                + " leaveSince=" + menuLeaveSince + " openedAgo=" + (now - menuOpenedAt)
                + " rel=" + menuGaze.readout().relYaw + "," + menuGaze.readout().relPitch
                + " by " + new Throwable().getStackTrace()[1]);
        overlay = OVERLAY_NONE;
        overlayAlpha = 0f;
        overlayHint = "";
        overlayHintUntil = 0;
        menuLeaveSince = 0;
        feedback.menuClose();
        gaze.setAnchor(0f, 0f);
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
        out.putExtra(EXTRA_MODE, mode.key());
        out.putExtra(EXTRA_POSITION, position);
        setResult(RESULT_OK, out);
        finish();
    }

    private String subtitleText() {
        if (subtitleLoading) {
            return "字幕加载中…";
        }
        if (subtitleTrack == null) {
            return subtitleSource == null ? "未加载 · 手机端可选" : "字幕文件没有可用条目";
        }
        return SubtitleTrack.describe(subtitleSource) + " · " + subtitleTrack.cueCount() + " 条";
    }

    private void buildFrame(long now, float hudAlpha, float wakeAlpha) {
        AudioPlayer audio = renderer == null ? null : renderer.audioPlayer();
        frame.title = browserOnly ? "片库" : stripExtension(videoName);
        String audioSuffix = "";
        if (audio != null && audio.hasTrack()) {
            audioSuffix = audio.isMuted() ? " · 静音" : " · 有声";
        }
        frame.format = browserOnly ? "SMB · 凝视文件管理" : videoFormat + audioSuffix;
        String cue = "";
        VideoDecoder vd = renderer == null ? null : renderer.decoder();
        if (subtitleOn && subtitleTrack != null && vd != null) {
            cue = subtitleTrack.textAt(vd.positionMs());
        }
        frame.subtitle = cue;
        frame.subtitleText = subtitleText();
        frame.status = statusText;
        frame.statusUntil = statusUntil;
        frame.playing = playing;
        frame.subtitleOn = subtitleOn;
        if (audio == null) {
            frame.audioValue = "…";
            frame.audioText = "音轨加载中";
        } else if (!audio.hasTrack()) {
            frame.audioValue = "无";
            frame.audioText = audio.error() != null ? "音轨解码失败" : "该文件没有音频轨道";
        } else {
            frame.audioValue = (audio.trackIndex() + 1) + "/" + audio.trackCount();
            frame.audioText = audio.summary() + (audio.isMuted() ? " · 倍速静音" : " · 有声");
        }
        VideoDecoder d = renderer == null ? null : renderer.decoder();
        frame.positionMs = scrubbing ? scrubTargetMs : (d == null ? 0 : d.positionMs());
        frame.durationMs = d == null ? 0 : d.durationMs();
        frame.scrubbing = scrubbing;
        frame.browserTargets = browserTargets.toArray(new HudSpec.Target[0]);
        frame.browserPath = browserPathText();
        frame.browserHint = browserHint;
        frame.browserPage = browserPage;
        frame.browserPages = browserPages;
        frame.browserLoading = browserLoading;
        frame.browserGen = browserGen;
        frame.gaze = gaze.readout();
        frame.hudAlpha = hudAlpha;
        frame.wakeHintUntil = wakeHintUntil;
        frame.wakeAlpha = wakeAlpha;
        frame.wakeProgress = frame.gaze == null ? 0f : frame.gaze.wakeProgress;
        frame.wakeUp = frame.gaze != null && frame.gaze.wakeUp;
        frame.maskAlpha = maskAlpha;
        frame.flashAlpha = flashAlpha;
        frame.recenterCountdown = recPhase == REC_COUNTDOWN ? recCountdown : 0f;

        frame.overlay = overlay;
        frame.overlayAlpha = overlayAlpha;
        frame.overlayGaze = menuGaze.readout();
        frame.overlayHint = overlayHint;
        frame.speed = playbackRate;
        frame.brightnessText = brightnessText();
        frame.autoBright = autoBright;
        frame.wakeText = WAKE_LABELS[wakeLevel];
        frame.menuTargets = menuRows();
        frame.menuPage = menuPage;
        frame.menuPages = 2;
        frame.volumeText = volumeText();
        frame.serverText = serverText();
        frame.subStyleText = SUB_STYLE_LABELS[subStyle];
        frame.projText = mode.projLabel();
        frame.packText = mode.packLabel();
        frame.ipdText = ipdMm + " mm";
        frame.projOption = currentProjOption();
        frame.subtitleScale = SUB_STYLE_SCALE[subStyle];
        frame.subtitleOutline = subStyle != 2;
        frame.defaultFocusHint = overlay != OVERLAY_BROWSER
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
    public void onWake(boolean up) {
        showHud();
    }

    /**
     * Brings the controls back at the picture's centre. The glance and the
     * double-tap both land here; the layer is anchored to the video centre (the
     * recenter direction), not to wherever the head happens to point, so the
     * bar and the panels never drift or re-seat themselves while watching.
     */
    private void showHud() {
        final long now = SystemClock.elapsedRealtime();
        feedback.wake();
        lastActivityMs = now;
        wakeFadeStart = now;
        interacted = true;
        viewAligned = true;
        gaze.setAnchor(0f, 0f);
        gaze.onHudOpened(now);
    }

    // ------------------------------------------------------------- display

    private void loadDisplayPrefs() {
        android.content.SharedPreferences p =
                getSharedPreferences(DISPLAY_PREFS, MODE_PRIVATE);
        brightLevel = p.getInt("level", 4);
        autoBright = p.getBoolean("auto", false);
        wakeLevel = p.getInt("wake", 1);
        subStyle = p.getInt("sub_style", 0);
        ipdMm = p.getInt("ipd", 0);
        flipImage = p.getBoolean("flip", false);
        if (brightLevel < 0 || brightLevel >= BRIGHT_LEVELS.length) {
            brightLevel = 4;
        }
        if (wakeLevel < 0 || wakeLevel >= WAKE_LABELS.length) {
            wakeLevel = 1;
        }
        if (subStyle < 0 || subStyle >= SUB_STYLE_LABELS.length) {
            subStyle = 0;
        }
        gaze.setWakePitch(WAKE_PITCHES[wakeLevel]);
    }

    private void saveDisplayPrefs() {
        getSharedPreferences(DISPLAY_PREFS, MODE_PRIVATE).edit()
                .putInt("level", brightLevel)
                .putBoolean("auto", autoBright)
                .putInt("wake", wakeLevel)
                .putInt("sub_style", subStyle)
                .putInt("ipd", ipdMm)
                .apply();
    }

    private void registerLight() {
        if (sensors == null || lightSensor == null || !autoBright) {
            return;
        }
        sensors.registerListener(this, lightSensor, SensorManager.SENSOR_DELAY_NORMAL);
    }

    private void refreshBrightness(long now) {
        if (!autoBright) {
            applyBrightness(BRIGHT_LEVELS[brightLevel]);
            return;
        }
        float target = autoBrightnessTarget();
        if (autoTarget < 0f) {
            autoTarget = target;
        } else {
            autoTarget += (target - autoTarget) * 0.35f;
        }
        if (now - lastBrightApplyMs < 250) {
            return;
        }
        lastBrightApplyMs = now;
        applyBrightness(autoTarget);
    }

    /** 1 lx → 15 %, 10 000 lx → 100 %, log scale like the system curve. */
    private float autoBrightnessTarget() {
        float t = (float) (Math.log10(Math.max(1f, lux)) / 4.0);
        t = Math.max(0f, Math.min(1f, t));
        return 0.15f + 0.85f * t;
    }

    private void applyBrightness(float value) {
        WindowManager.LayoutParams lp = getWindow().getAttributes();
        if (Math.abs(lp.screenBrightness - value) < 0.005f) {
            return;
        }
        lp.screenBrightness = Math.max(0.02f, Math.min(1f, value));
        getWindow().setAttributes(lp);
    }

    private void cycleBrightness() {
        autoBright = false;
        brightLevel = (brightLevel + 1) % BRIGHT_LEVELS.length;
        autoTarget = -1f;
        saveDisplayPrefs();
        refreshBrightness(SystemClock.elapsedRealtime());
        showStatus("亮度 " + brightnessText());
    }

    private void toggleAutoBright() {
        autoBright = !autoBright;
        autoTarget = -1f;
        if (autoBright) {
            registerLight();
        } else if (sensors != null && lightSensor != null) {
            sensors.unregisterListener(this, lightSensor);
        }
        saveDisplayPrefs();
        refreshBrightness(SystemClock.elapsedRealtime());
        showStatus(autoBright ? "自动亮度已开启" : "自动亮度已关闭");
    }

    private void cycleWake() {
        wakeLevel = (wakeLevel + 1) % WAKE_LABELS.length;
        gaze.setWakePitch(WAKE_PITCHES[wakeLevel]);
        saveDisplayPrefs();
        showStatus("唤回灵敏度 " + WAKE_LABELS[wakeLevel]
                + " · 下看或上看 " + (int) WAKE_PITCHES[wakeLevel] + "°");
    }

    private String brightnessText() {
        if (autoBright) {
            return "自动 " + Math.round((autoTarget < 0f ? autoBrightnessTarget() : autoTarget) * 100f) + "%";
        }
        return Math.round(BRIGHT_LEVELS[brightLevel] * 100f) + "%";
    }

    private android.media.AudioManager audioManager() {
        return (android.media.AudioManager) getSystemService(AUDIO_SERVICE);
    }

    private String volumeText() {
        android.media.AudioManager am = audioManager();
        if (am == null) {
            return "不可用";
        }
        int max = am.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC);
        if (max <= 0) {
            return "不可用";
        }
        return Math.round(am.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)
                * 100f / max) + "%";
    }

    /** 25 % steps, wrapping back to silence at the top of the scale. */
    private void cycleVolume() {
        android.media.AudioManager am = audioManager();
        if (am == null) {
            showStatus("无法调节音量");
            return;
        }
        int max = am.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC);
        if (max <= 0) {
            showStatus("无法调节音量");
            return;
        }
        int step = Math.max(1, Math.round(max / 4f));
        int next = am.getStreamVolume(android.media.AudioManager.STREAM_MUSIC) + step;
        if (next > max) {
            next = 0;
        }
        am.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, next, 0);
        showStatus("音量 " + Math.round(next * 100f / max) + "%");
    }

    private String serverText() {
        SmbSettings current = SmbSettings.load(this);
        if (current.profileName.length() > 0) {
            return current.profileName;
        }
        java.util.List<SmbSettings.Profile> list = SmbSettings.profiles(this);
        return list.isEmpty() ? "未配置" : list.get(0).name;
    }

    /**
     * Playback already holds a connection, so switching only changes the
     * record the library will use next time; saying so avoids a silent no-op.
     */
    private void cycleServerRecord() {
        java.util.List<SmbSettings.Profile> list = SmbSettings.profiles(this);
        if (list.isEmpty()) {
            showStatus("还没有保存的服务器 · 先在片库添加");
            return;
        }
        SmbSettings current = SmbSettings.load(this);
        int index = 0;
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).name.equals(current.profileName)) {
                index = i;
                break;
            }
        }
        SmbSettings.Profile next = list.get((index + 1) % list.size());
        current.selectProfile(this, next.name);
        showStatus("已选服务器 " + next.name + " · 重新打开片库生效");
    }

    private void cycleSubtitleStyle() {
        subStyle = (subStyle + 1) % SUB_STYLE_LABELS.length;
        saveDisplayPrefs();
        showStatus("字幕样式 " + SUB_STYLE_LABELS[subStyle]);
    }

    /**
     * The projection cannot always be read from the file, so the menu walks the
     * five families and the packing walks mono/左右/上下. The pair is applied
     * on the next frame and stays until playback ends.
     */
    private void cycleProjection() {
        int next = (mode.proj + 1) % ProjectionMode.PROJ_COUNT;
        applyProjection(ProjectionMode.of(next, mode.pack), true);
    }

    private void cyclePacking() {
        int next = (mode.pack + 1) % ProjectionMode.PACK_COUNT;
        applyProjection(ProjectionMode.of(mode.proj, next), true);
    }

    private static final int IPD_MIN_MM = 54;
    private static final int IPD_MAX_MM = 72;
    private static final int IPD_STEP_MM = 2;

    /**
     * The viewer's lens spacing is the one number worth tuning while watching:
     * the two images merge or split as it changes, so the picture is the gauge.
     * Stepping it on each trigger keeps the single-trigger interaction intact.
     */
    private void cycleIpd() {
        int next = ipdMm + IPD_STEP_MM;
        if (next > IPD_MAX_MM) {
            next = IPD_MIN_MM;
        }
        ipdMm = next;
        if (viewerParams != null && gvrView != null) {
            viewerParams.setInterLensDistance(ipdMm / 1000f);
            gvrView.updateGvrViewerParams(viewerParams);
        }
        saveDisplayPrefs();
        String text = "瞳距 " + ipdMm + " mm";
        showStatus(text);
        if (overlay == OVERLAY_MENU) {
            setOverlayHint(text);
        }
    }

    private void applyProjection(ProjectionMode m, boolean byUser) {
        if (m.proj == mode.proj && m.pack == mode.pack) {
            if (byUser) {
                modeUserSet = true;
            }
            return;
        }
        mode = m;
        if (byUser) {
            modeUserSet = true;
        }
        if (renderer != null) {
            renderer.setMode(m);
        }
        if (byUser) {
            String text = "投影 " + m.label();
            showStatus(text);
            if (overlay == OVERLAY_MENU || overlay == OVERLAY_PROJ) {
                setOverlayHint(text);
            }
        } else {
            showStatus("已识别 " + m.label());
        }
        videoFormat = formatLine();
    }

    /**
     * Adopt the packing the first frames proved. The shape alone cannot tell a
     * 2:1 mono sphere from a 2:1 pair of 1:1 eyes, and guessing wrong put the
     * seam of a side-by-side film dead ahead; the pixels decide instead, once
     * per video, unless the name or the user already spoke.
     */
    private void applyPackEvidence() {
        if (renderer == null || modeUserSet || ProjectionMode.nameHasPack(videoName)) {
            return;
        }
        int ev = renderer.packEvidence();
        if (ev < 0 || ev == mode.pack) {
            return;
        }
        float ar = videoHeight > 0 ? videoWidth / (float) videoHeight : 0f;
        ProjectionMode next = ProjectionMode.withPack(mode, ev, ar);
        if (next.pack != mode.pack) {
            applyProjection(next, false);
        }
    }

    private String formatLine() {
        String pack = mode.pack == ProjectionMode.MONO ? "" : " " + mode.packLabel();
        String text = mode.projLabel() + pack + " · " + videoWidth + "×" + videoHeight
                + " · " + videoMime;
        if (videoSoft) {
            text += " · 软解";
        }
        return text;
    }

    @Override
    public void onGazeConfirm(HudSpec.Target target) {
        if (scrubbing && target.id != HudSpec.Id.TIMELINE) {
            return;
        }
        feedback.confirm();
        lastActivityMs = SystemClock.elapsedRealtime();
        interacted = true;
        viewAligned = true;
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
                toggleSubtitle(false);
                break;
            case RECENTER:
                startRecenter();
                break;
            case TIMELINE:
                beginScrub();
                break;
            case MENU:
                openMenu();
                break;
            case FILES:
                openBrowser();
                break;
            case PROJ:
                openProjPanel();
                break;
            case PROJ_OPTION:
                applyProjectionOption(target);
                break;
            case MENU_SUBTITLE:
                toggleSubtitle(true);
                break;
            case MENU_AUDIO:
                handleAudioRow();
                break;
            case MENU_SPEED:
                cycleSpeed();
                break;
            case MENU_BRIGHT:
                cycleBrightness();
                break;
            case MENU_AUTO_BRIGHT:
                toggleAutoBright();
                break;
            case MENU_WAKE:
                cycleWake();
                break;
            case MENU_FILE:
                openBrowser();
                break;
            case MENU_MORE:
                setMenuPage(1);
                break;
            case MENU_BACK:
                if (menuPage == 0) {
                    closeOverlay("菜单已关闭");
                } else {
                    setMenuPage(0);
                }
                break;
            case MENU_VOLUME:
                cycleVolume();
                break;
            case MENU_SERVER:
                cycleServerRecord();
                break;
            case MENU_SUB_STYLE:
                cycleSubtitleStyle();
                break;
            case MENU_PROJ:
                cycleProjection();
                break;
            case MENU_PACK:
                cyclePacking();
                break;
            case MENU_IPD:
                cycleIpd();
                break;
            case BROWSER_ITEM:
            case BROWSER_PREV:
            case BROWSER_NEXT:
            case BROWSER_MODE:
            case BROWSER_CLOSE:
                handleBrowserTarget(target);
                break;
            case MENU_EXIT:
                openConfirm();
                break;
            case EXIT_CANCEL:
                closeOverlay("已取消退出");
                break;
            case EXIT_OK:
                feedback.confirm();
                exitPlayback(renderer == null ? null : renderer.decoder());
                break;
            default:
                break;
        }
    }

    /** Parses the picked or sidecar subtitle off the UI thread. */
    private void loadSubtitle(final String source) {
        subtitleSource = source;
        subtitleLoading = true;
        final Context app = getApplicationContext();
        new Thread(new Runnable() {
            @Override
            public void run() {
                SubtitleTrack track = null;
                String failure = null;
                try {
                    track = SubtitleTrack.load(app, source);
                } catch (Throwable t) {
                    failure = t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
                }
                final SubtitleTrack loaded = track;
                final String error = failure;
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        subtitleLoading = false;
                        if (isFinishing() || isDestroyed()) {
                            return;
                        }
                        if (loaded == null) {
                            showStatus("字幕加载失败：" + error);
                            return;
                        }
                        subtitleTrack = loaded;
                        if (loaded.cueCount() == 0) {
                            showStatus("字幕文件没有可用条目");
                        } else {
                            subtitleOn = true;
                            showStatus("字幕已加载 · " + SubtitleTrack.describe(source)
                                    + " · " + loaded.cueCount() + " 条");
                        }
                    }
                });
            }
        }, "gazeline-subtitle").start();
    }

    /** Subtitle target and menu row share this toggle. */
    private void toggleSubtitle(boolean inMenu) {
        String hint;
        if (subtitleLoading) {
            subtitleOn = !subtitleOn;
            hint = subtitleOn ? "字幕已开启 · 正在加载" : "字幕已关闭";
        } else if (subtitleTrack == null || subtitleTrack.cueCount() == 0) {
            hint = subtitleSource == null ? "暂无字幕轨" : "字幕文件没有可用条目";
        } else {
            subtitleOn = !subtitleOn;
            hint = subtitleOn ? "字幕已开启" : "字幕已关闭";
        }
        if (inMenu) {
            setOverlayHint(hint);
        } else {
            showStatus(hint);
        }
    }

    private void setOverlayHint(String text) {
        overlayHint = text;
        overlayHintUntil = SystemClock.elapsedRealtime() + 2200;
        lastActivityMs = SystemClock.elapsedRealtime();
    }

    /** Menu audio row: show the track, or cycle to the next one when present. */
    private void handleAudioRow() {
        AudioPlayer audio = renderer.audioPlayer();
        if (audio == null) {
            setOverlayHint("音轨加载中…");
            return;
        }
        if (!audio.hasTrack()) {
            setOverlayHint(audio.error() != null
                    ? "音轨不可用：" + audio.error()
                    : "无音轨 · 该文件没有音频轨道");
            return;
        }
        if (audio.trackCount() <= 1) {
            setOverlayHint(audio.summary() + " · 仅 1 条音轨");
            return;
        }
        renderer.nextAudioTrack();
        setOverlayHint("正在切换音轨…");
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
        renderer.setSpeed(next);
        String hint = "播放速度 " + String.format("%.2f", next).replaceAll("0$", "") + "×";
        if (next != 1f) {
            hint += " · 音频静音（占位）";
        }
        setOverlayHint(hint);
    }

    private void togglePlay() {
        playing = !playing;
        if (playing) {
            renderer.resumePlayback();
        } else {
            renderer.pausePlayback();
        }
        showStatus(playing ? "继续播放" : "已暂停");
        lastActivityMs = SystemClock.elapsedRealtime();
    }

    /**
     * Gaze held on the bar: pause, then map head yaw onto the film. The grab
     * point is kept, so the picture does not jump when the drag starts, and the
     * progress bar itself shows the target time while the decoder is paused.
     */
    private void beginScrub() {
        if (scrubbing || renderer == null) {
            return;
        }
        VideoDecoder d = renderer.decoder();
        if (d == null || d.durationMs() <= 0) {
            showStatus("进度不可用");
            return;
        }
        long now = SystemClock.elapsedRealtime();
        scrubbing = true;
        scrubResume = playing;
        if (playing) {
            playing = false;
            renderer.pausePlayback();
        }
        GazeController.Readout r = gaze.readout();
        scrubBaseRelYaw = r.relYaw;
        scrubTargetMs = d.positionMs();
        scrubBaseProgress = Math.max(0f,
                Math.min(1f, scrubTargetMs / (float) d.durationMs()));
        scrubSeekMs = 0;
        interacted = true;
        viewAligned = true;
        lastActivityMs = now;
        showStatus("拖动进度：左右转头 · 视线离开进度条结束");
    }

    private void updateScrub(long now) {
        if (!scrubbing) {
            return;
        }
        VideoDecoder d = renderer == null ? null : renderer.decoder();
        if (overlay != OVERLAY_NONE || d == null || d.durationMs() <= 0) {
            endScrub(now, d != null);
            return;
        }
        GazeController.Readout r = gaze.readout();
        float dyaw = GazeController.wrap(r.relYaw - scrubBaseRelYaw);
        float progress = scrubBaseProgress + dyaw / HudSpec.BAR_WIDTH;
        progress = Math.max(0f, Math.min(1f, progress));
        scrubTargetMs = (long) (progress * d.durationMs());
        if (Math.abs(r.relPitch - HudSpec.BAR_PITCH) > 7f || Math.abs(r.relYaw) > 40f) {
            endScrub(now, true);
            return;
        }
        if (scrubSeekMs == 0 || now - scrubSeekMs >= 120) {
            scrubSeekMs = now;
            renderer.seekTo(scrubTargetMs);
        }
        // A drag must not be interrupted by the idle fade.
        lastActivityMs = now;
    }

    private void endScrub(long now, boolean seek) {
        if (!scrubbing) {
            return;
        }
        scrubbing = false;
        if (seek && renderer != null) {
            renderer.seekTo(scrubTargetMs);
        }
        if (scrubResume) {
            playing = true;
            if (renderer != null) {
                renderer.resumePlayback();
            }
        }
        scrubResume = false;
        lastActivityMs = now;
        showStatus("进度 " + timeText(scrubTargetMs));
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
        renderer.seekTo(target);
        showStatus(label);
        lastActivityMs = SystemClock.elapsedRealtime();
    }

    private void showStatus(String text) {
        statusText = text;
        statusUntil = SystemClock.elapsedRealtime() + 1800;
    }

    // ------------------------------------------------------------- recenter

    /**
     * Establishes the content reference once the device is actually being worn.
     *
     * The launch pose cannot be used as "forward": the phone is in the hand or
     * on a table when playback starts, and baking that pitch left the video
     * pointing at the zenith (and made the down-glance wake unreachable). Here
     * the reference is re-taken only after the phone has been held upright and
     * still for a moment, and it is allowed to happen again while the holder
     * moves the phone into the headset (pitch changes by tens of degrees), so
     * the last reference is the wearing pose. It stops for good on the first
     * real action.
     */
    private void maybeAutoAlign(long now) {
        if (renderer == null || !sensorPrimed || interacted || viewAligned) {
            steadySince = 0;
            return;
        }
        if (recPhase != REC_IDLE || overlay != OVERLAY_NONE) {
            steadySince = 0;
            return;
        }
        if (now - sessionStartMs < 2000) {
            steadySince = 0;
            return;
        }
        if (alignCount >= 3) {
            return;
        }
        if (alignCount > 0) {
            if (now - lastAlignMs < 1500) {
                steadySince = 0;
                return;
            }
            // Only a real pose change justifies moving the view again; looking
            // around (mostly yaw) must not keep re-centring it.
            boolean moved = Math.abs(viewPitch - alignPitchRef) >= 18f
                    || Math.abs(GazeController.wrap(viewYaw - alignYawRef)) >= 60f;
            if (!moved) {
                return;
            }
        }
        // A phone on a table points at the ceiling or the floor; a worn headset
        // is close to level. That is enough to tell "not being used yet" from
        // "watching", so the launch pose never becomes the video's centre.
        boolean upright = Math.abs(renderer.headPitchAbs()) <= 32f;
        if (!upright || angularSpeed >= 20f) {
            steadySince = 0;
            return;
        }
        if (steadySince == 0) {
            steadySince = now;
            return;
        }
        if (now - steadySince < 800) {
            return;
        }
        steadySince = 0;
        alignCount++;
        lastAlignMs = now;
        applyRecenter();
        gaze.setAnchor(0f, 0f);
        lastActivityMs = now;
        alignYawRef = viewYaw;
        alignPitchRef = viewPitch;
        if (alignCount == 1) {
            showStatus("已对准视频中央");
        }
    }

    /**
     * Gaze triggers the action; the user then gets a grace window to settle into
     * a comfortable pose. When it expires, whatever direction the head is
     * pointing at that moment becomes the new forward — no forced rotation.
     */
    private void startRecenter() {
        if (recPhase != REC_IDLE) {
            return;
        }
        long now = SystemClock.elapsedRealtime();
        interacted = true;
        viewAligned = true;
        settleSince = 0;
        recPhase = REC_COUNTDOWN;
        recPhaseStart = now;
        recCountdown = RECENTER_GRACE_MS / 1000f;
        maskAlpha = 0f;
        flashAlpha = 0f;
        lastActivityMs = now;
        showStatus("回中：抬头看正前方，停稳即对准");
    }

    private void updateRecenter(long now) {
        switch (recPhase) {
            case REC_COUNTDOWN: {
                float left = RECENTER_GRACE_MS - (now - recPhaseStart);
                recCountdown = Math.max(0f, left / 1000f);
                // Commit as soon as the head is back near the panel's level and
                // still, instead of always waiting out the grace window: aiming
                // at the key means looking 25° down-left, and baking *that*
                // direction as forward was what made 回中 feel like it did not
                // return to the video centre.
                GazeController.Readout r = gaze.readout();
                boolean level = Math.abs(r.relPitch) <= 9f;
                if (level && angularSpeed < HudSpec.SPEED_RESUME_DEG) {
                    if (settleSince == 0) {
                        settleSince = now;
                    }
                } else {
                    settleSince = 0;
                }
                boolean settled = settleSince != 0 && now - settleSince >= 260;
                if (left <= 0f || settled) {
                    settleSince = 0;
                    recCountdown = 0f;
                    recPhase = REC_MASK_IN;
                    recPhaseStart = now;
                    feedback.confirm();
                }
                break;
            }
            case REC_MASK_IN: {
                float t = (now - recPhaseStart) / 110f;
                maskAlpha = 0.8f * Math.max(0f, Math.min(1f, t));
                if (t >= 1f) {
                    applyRecenter();
                    maskAlpha = 0.8f;
                    recPhase = REC_MASK_OUT;
                    recPhaseStart = now;
                    flashStart = now;
                    flashAlpha = 1f;
                }
                break;
            }
            case REC_MASK_OUT: {
                float t = (now - recPhaseStart) / 140f;
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

    /** Bakes the current head direction in as forward, behind the short mask. */
    private void applyRecenter() {
        if (renderer != null) {
            renderer.recenter();
        }
        updateRotation();
    }

    private void finishRecenter(long now, boolean interrupted) {
        recPhase = REC_IDLE;
        recCountdown = 0f;
        maskAlpha = 0f;
        flashAlpha = 0f;
        updateRotation();
        gaze.setAnchor(0f, 0f);
        gaze.onHudOpened(now);
        lastActivityMs = now;
        if (interrupted) {
            showStatus("回中已取消");
        } else {
            showStatus("已回中");
        }
    }

    // ------------------------------------------------------------- VR browser

    private static final int BROW_KIND_DIR = 1;
    private static final int BROW_KIND_VIDEO = 2;
    private static final int BROW_KIND_FAV = 3;
    private static final int BROW_KIND_SERVER = 4;
    private static final int BROWSER_PAGE_SIZE = HudSpec.BROWSER_PAGE_SIZE;

    private void openBrowser() {
        SmbSettings settings = SmbSettings.load(this);
        long now = SystemClock.elapsedRealtime();
        overlay = OVERLAY_BROWSER;
        overlayStart = now;
        overlayAlpha = 0f;
        overlayHint = "";
        overlayHintUntil = 0;
        menuOpenedAt = now;
        menuLeaveSince = 0;
        browserDir = SmbFavorites.normalize(settings.path);
        browserPage = 0;
        browserPages = 1;
        browserRows.clear();
        browserTargets.clear();
        browserHint = "";
        browserLoading = settings.isConfigured();
        menuGaze.setAnchor(0f, 0f);
        menuGaze.onHudOpened(now);
        menuGaze.protect(now, 500);
        feedback.menuOpen();
        lastActivityMs = now;
        if (!settings.isConfigured()) {
            browserLoading = false;
            browserHint = "请先在手机端完成 SMB 配置";
            rebuildBrowser();
            return;
        }
        loadBrowserDir(settings, browserDir);
    }

    private void loadBrowserDir(final SmbSettings settings, final String target) {
        final String dir = SmbFavorites.normalize(target);
        browserDir = dir;
        browserPage = 0;
        browserLoading = true;
        browserHint = "";
        browserRows.clear();
        rebuildBrowser();
        new Thread(new Runnable() {
            @Override
            public void run() {
                java.util.List<SmbConnection.Entry> entries = null;
                try {
                    SmbConnection conn = SmbConnection.connect(settings.host, settings.port,
                            settings.share, settings.user, settings.password);
                    try {
                        entries = conn.list(dir);
                    } finally {
                        conn.close();
                    }
                } catch (Throwable ignored) {
                    // handled below: a null list shows the failure hint
                }
                final java.util.List<SmbConnection.Entry> result = entries;
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        if (isFinishing() || isDestroyed() || overlay != OVERLAY_BROWSER) {
                            return;
                        }
                        browserLoading = false;
                        if (result == null) {
                            // Still build rows: the server switcher must stay
                            // reachable when this record is down.
                            buildBrowserRows(settings,
                                    new java.util.ArrayList<SmbConnection.Entry>());
                            browserHint = "连接失败 · 检查网段 / 445 / 账号";
                        } else {
                            buildBrowserRows(settings, result);
                        }
                        rebuildBrowser();
                    }
                });
            }
        }, "gazeline-vr-browser").start();
    }

    private void buildBrowserRows(SmbSettings settings, java.util.List<SmbConnection.Entry> entries) {
        browserRows.clear();
        String root = SmbFavorites.normalize(settings.path);
        // The switcher stays on page 1, not just at the root, so an
        // unreachable record can still be swapped for another one.
        java.util.List<SmbSettings.Profile> saved = SmbSettings.profiles(this);
        BrowserRow server = new BrowserRow();
        server.kind = BROW_KIND_SERVER;
        server.title = "服务器：" + SmbSettings.profileLabel(settings.profileName);
        server.detail = saved.size() > 1
                ? "切换到下一个 · 共 " + saved.size() + " 条"
                : "手机端可添加多条记录";
        browserRows.add(server);
        if (browserDir.equals(root)) {
            for (String fav : SmbFavorites.list(this, settings)) {
                if (fav.length() == 0) {
                    continue;
                }
                BrowserRow r = new BrowserRow();
                r.kind = BROW_KIND_FAV;
                r.path = fav;
                r.title = "★ " + SmbFavorites.displayName(fav);
                r.detail = "收藏";
                browserRows.add(r);
            }
        }
        if (browserDir.length() > 0) {
            BrowserRow r = new BrowserRow();
            r.kind = BROW_KIND_DIR;
            r.path = parentOf(browserDir);
            r.title = ".. 上一级";
            r.detail = r.path.length() == 0 ? settings.share : settings.share + "/" + r.path;
            browserRows.add(r);
        }
        for (SmbConnection.Entry e : entries) {
            if (!e.directory) {
                continue;
            }
            BrowserRow r = new BrowserRow();
            r.kind = BROW_KIND_DIR;
            r.path = e.path;
            r.title = e.name;
            r.detail = "目录";
            browserRows.add(r);
        }
        for (SmbConnection.Entry e : entries) {
            if (e.directory || !playable(e.name)) {
                continue;
            }
            BrowserRow r = new BrowserRow();
            r.kind = BROW_KIND_VIDEO;
            r.path = e.path;
            r.source = settings.url(e.path);
            r.title = e.name;
            String detail = sizeText(e.size);
            PlayHistory.Entry h = PlayHistory.find(this, r.source);
            if (h != null && h.resumable()) {
                detail = (detail.length() == 0 ? "" : detail + " · ")
                        + "继续 " + timeText(h.positionMs);
            }
            r.detail = detail.length() == 0 ? "视频" : detail;
            browserRows.add(r);
        }
    }

    private void rebuildBrowser() {
        browserTargets.clear();
        int size = browserRows.size();
        int pageSize = BROWSER_PAGE_SIZE;
        browserPages = Math.max(1, (size + pageSize - 1) / pageSize);
        if (browserPage >= browserPages) {
            browserPage = browserPages - 1;
        }
        if (browserPage < 0) {
            browserPage = 0;
        }
        int start = browserPage * pageSize;
        for (int slot = 0; slot < pageSize; slot++) {
            int index = start + slot;
            if (index >= size) {
                break;
            }
            BrowserRow r = browserRows.get(index);
            int col = slot % HudSpec.BROWSER_COLS;
            int row = slot / HudSpec.BROWSER_COLS;
            String key = r.kind == BROW_KIND_VIDEO ? r.source : null;
            android.graphics.Bitmap thumb = key == null ? null : Thumbnails.peek(key);
            browserTargets.add(HudSpec.browserCard(HudSpec.browserCardYaw(col),
                    HudSpec.BROWSER_CARD_PITCH[row], r.title, r.detail, index, thumb));
            if (key != null && thumb == null) {
                requestThumb(key);
            }
        }
        browserTargets.add(HudSpec.BROWSER_PREV_T);
        browserTargets.add(HudSpec.BROWSER_NEXT_T);
        browserTargets.add(HudSpec.browserMode(ProjectionMode.labelOf(
                SmbSettings.load(this).projection)));
        browserTargets.add(HudSpec.BROWSER_CLOSE_T);
        menuGaze.setLayer(browserTargets.toArray(new HudSpec.Target[0]), false);
        menuGaze.protect(SystemClock.elapsedRealtime(), 260);
        browserGen++;
    }

    /**
     * Posters decode one at a time off the UI thread. Each finished frame is
     * patched into the live target list and only bumps the redraw generation,
     * so the gaze layer is never rebuilt while the user is looking at it.
     */
    private void requestThumb(final String key) {
        if (key == null || key.length() == 0 || thumbPending.contains(key)) {
            return;
        }
        thumbPending.add(key);
        Thumbnails.load(this, key, new Runnable() {
            @Override
            public void run() {
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        thumbPending.remove(key);
                        if (overlay != OVERLAY_BROWSER) {
                            return;
                        }
                        android.graphics.Bitmap bmp = Thumbnails.peek(key);
                        if (bmp == null) {
                            return;
                        }
                        boolean hit = false;
                        for (HudSpec.Target t : browserTargets) {
                            if (t.id != HudSpec.Id.BROWSER_ITEM || t.thumb != null
                                    || t.index < 0 || t.index >= browserRows.size()) {
                                continue;
                            }
                            if (key.equals(browserRows.get(t.index).source)) {
                                t.thumb = bmp;
                                hit = true;
                            }
                        }
                        if (hit) {
                            browserGen++;
                        }
                    }
                });
            }
        });
    }

    private void handleBrowserTarget(HudSpec.Target t) {
        switch (t.id) {
            case BROWSER_ITEM: {
                if (t.index < 0 || t.index >= browserRows.size()) {
                    return;
                }
                BrowserRow row = browserRows.get(t.index);
                if (row.kind == BROW_KIND_SERVER) {
                    cycleServer();
                } else if (row.kind == BROW_KIND_VIDEO) {
                    playFromBrowser(row);
                } else {
                    browserHint = "";
                    loadBrowserDir(SmbSettings.load(this), row.path);
                }
                break;
            }
            case BROWSER_PREV:
                if (browserPage > 0) {
                    browserPage--;
                    rebuildBrowser();
                }
                break;
            case BROWSER_NEXT:
                if (browserPage + 1 < browserPages) {
                    browserPage++;
                    rebuildBrowser();
                }
                break;
            case BROWSER_MODE: {
                SmbSettings settings = SmbSettings.load(this);
                settings.projection = ProjectionMode.cycleKey(settings.projection);
                settings.save(this);
                browserHint = "默认投影 " + ProjectionMode.labelOf(settings.projection)
                        + " · 播放时可再切换";
                rebuildBrowser();
                break;
            }
            case BROWSER_CLOSE:
                if (browserOnly) {
                    finish();
                } else {
                    closeOverlay("已关闭文件管理");
                }
                break;
            default:
                break;
        }
    }

    /** Gaze on the server row: rotate to the next saved record and reconnect. */
    private void cycleServer() {
        SmbSettings settings = SmbSettings.load(this);
        java.util.List<SmbSettings.Profile> saved = SmbSettings.profiles(this);
        if (saved.size() <= 1) {
            browserHint = "只有一个记录 · 在手机端「设置」里添加";
            rebuildBrowser();
            return;
        }
        int index = 0;
        for (int i = 0; i < saved.size(); i++) {
            if (saved.get(i).name.equals(settings.profileName)) {
                index = i;
                break;
            }
        }
        SmbSettings.Profile next = saved.get((index + 1) % saved.size());
        if (!settings.selectProfile(this, next.name)) {
            return;
        }
        browserDir = SmbFavorites.normalize(settings.path);
        browserPage = 0;
        loadBrowserDir(settings, browserDir);
        browserHint = "已切换服务器 · " + next.name;
    }

    private void playFromBrowser(BrowserRow row) {
        SmbSettings settings = SmbSettings.load(this);
        String source = settings.url(row.path);
        recordProgress();
        Intent play = new Intent(this, PlayerActivity.class);
        play.putExtra(EXTRA_PATH, source);
        play.putExtra(EXTRA_SENSOR, sensorWanted);
        long resume = PlayHistory.positionFor(this, source);
        if (resume > 0) {
            play.putExtra(EXTRA_POSITION, resume);
        }
        startActivity(play);
        finish();
    }

    private String browserPathText() {
        SmbSettings settings = SmbSettings.load(this);
        if (!settings.isConfigured()) {
            return "SMB 未配置";
        }
        return SmbSettings.profileLabel(settings.profileName) + " · "
                + settings.share + (browserDir.length() == 0 ? "" : "/" + browserDir);
    }

    private static String parentOf(String path) {
        if (path == null || path.length() == 0) {
            return "";
        }
        int slash = path.lastIndexOf('/');
        return slash < 0 ? "" : path.substring(0, slash);
    }

    private static boolean playable(String name) {
        String n = name.toLowerCase(java.util.Locale.US);
        return n.endsWith(".mp4") || n.endsWith(".mkv") || n.endsWith(".mov")
                || n.endsWith(".m4v") || n.endsWith(".webm") || n.endsWith(".ts")
                || n.endsWith(".avi");
    }

    private static String sizeText(long bytes) {
        if (bytes <= 0) {
            return "";
        }
        if (bytes >= 1073741824L) {
            return String.format(java.util.Locale.US, "%.2f GB", bytes / 1073741824f);
        }
        if (bytes >= 1048576L) {
            return String.format(java.util.Locale.US, "%.0f MB", bytes / 1048576f);
        }
        return String.format(java.util.Locale.US, "%.0f KB", bytes / 1024f);
    }

    private static String timeText(long ms) {
        long total = Math.max(0, ms) / 1000L;
        long h = total / 3600L;
        long m = (total / 60L) % 60L;
        long s = total % 60L;
        return h > 0 ? String.format("%02d:%02d:%02d", h, m, s)
                : String.format("%02d:%02d", m, s);
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

    /**
     * One press of the cardboard trigger (screen tap, magnet, or the button on
     * a paired viewer). It brings the controls up when they are tucked away and
     * otherwise acts on the target under the reticle; aiming already highlights
     * it, so there is no dwell timer to wait for.
     */
    private void onTrigger() {
        final long now = SystemClock.elapsedRealtime();
        if (now - lastTriggerMs < 350) {
            return;
        }
        lastTriggerMs = now;
        lastActivityMs = now;
        if (overlay != OVERLAY_NONE) {
            if (!menuGaze.confirmFocused(now)) {
                if (overlay == OVERLAY_MENU) {
                    closeOverlay("菜单已关闭");
                } else if (overlay == OVERLAY_CONFIRM) {
                    closeOverlay("已取消退出");
                } else if (overlay == OVERLAY_BROWSER) {
                    closeOverlay("已关闭文件管理");
                } else {
                    closeOverlay("画面类型已关闭");
                }
            }
            return;
        }
        if (computeHudAlpha(now) < 0.45f) {
            showHud();
            return;
        }
        if (!gaze.confirmFocused(now)) {
            // Nothing under the reticle: the press opens the full menu, the
            // same way a tap does in the viewer apps this mirrors.
            openMenu();
        }
    }

    /** The emulator's GL stack renders the lens pass black; straight stereo works. */
    private static boolean isEmulator() {
        return Build.FINGERPRINT.startsWith("generic")
                || Build.FINGERPRINT.contains("emulator")
                || Build.MODEL.contains("sdk_gphone")
                || Build.MODEL.contains("Emulator")
                || Build.MODEL.contains("Android SDK built for");
    }

    /** Keeps the head-anchored layer out of the camera cutout. */
    private void applyCutoutInsets(WindowInsets insets) {
        if (hud == null) {
            return;
        }
        int left = 0;
        int top = 0;
        int right = 0;
        int bottom = 0;
        if (Build.VERSION.SDK_INT >= 28 && insets != null) {
            DisplayCutout cutout = insets.getDisplayCutout();
            if (cutout != null) {
                left = cutout.getSafeInsetLeft();
                top = cutout.getSafeInsetTop();
                right = cutout.getSafeInsetRight();
                bottom = cutout.getSafeInsetBottom();
            }
        }
        hud.setSafeInset(left, top, right, bottom);
    }

    // ------------------------------------------------------------- double tap

    /**
     * Two sharp knocks on the phone's back wake the controls, alongside the
     * look-up/look-down glance. The low-passed gravity is subtracted first, so
     * slow head movement (well under the peak threshold) never counts.
     */
    private void handleAccel(float[] values) {
        if (!gravityReady) {
            System.arraycopy(values, 0, gravity, 0, 3);
            gravityReady = true;
            return;
        }
        gravity[0] += (values[0] - gravity[0]) * TAP_GRAVITY_ALPHA;
        gravity[1] += (values[1] - gravity[1]) * TAP_GRAVITY_ALPHA;
        gravity[2] += (values[2] - gravity[2]) * TAP_GRAVITY_ALPHA;
        float ax = values[0] - gravity[0];
        float ay = values[1] - gravity[1];
        float az = values[2] - gravity[2];
        float mag = (float) Math.sqrt(ax * ax + ay * ay + az * az);
        long now = SystemClock.elapsedRealtime();
        if (mag >= TAP_PEAK) {
            if (accelWasHigh) {
                return;
            }
            accelWasHigh = true;
            if (now < tapCooldownUntil) {
                return;
            }
            long gap = now - lastTapMs;
            if (lastTapMs != 0 && gap >= TAP_GAP_MIN_MS && gap <= TAP_GAP_MAX_MS) {
                lastTapMs = 0;
                tapCooldownUntil = now + TAP_COOLDOWN_MS;
                wakeByTap();
            } else {
                lastTapMs = now;
            }
        } else if (mag < TAP_REST) {
            accelWasHigh = false;
        }
    }

    /** The picture is already clean and the phone was knocked: show the HUD. */
    private void wakeByTap() {
        if (!playing || overlay != OVERLAY_NONE) {
            return;
        }
        if (angularSpeed > 20f) {
            // A head turn also shakes the accelerometer; a knock happens while
            // the head is held, and a wake during a turn would re-anchor the
            // panel under the moving gaze.
            return;
        }
        if (computeHudAlpha(SystemClock.elapsedRealtime()) >= 0.45f) {
            return;
        }
        showHud();
    }

    // ------------------------------------------------------------- sensors

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event.sensor.getType() == Sensor.TYPE_LIGHT) {
            lux = Math.max(1f, event.values[0]);
            return;
        }
        if (event.sensor.getType() == Sensor.TYPE_ACCELEROMETER) {
            handleAccel(event.values);
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
    }

    /**
     * The Google VR SDK owns the head pose. Angles are read relative to the
     * anchor the renderer captured at startup or at the last recenter, so 0° is
     * the centre of the picture and the gaze layer keeps its world-fixed HUD.
     * The drag trim is only for a device without a head tracker (the emulator).
     */
    private void updateRotation() {
        if (renderer != null) {
            renderer.setLookOffset(yaw, pitch);
            viewYaw = GazeController.wrap(renderer.headYawRel() + yaw);
            viewPitch = Math.max(-80f, Math.min(80f, renderer.headPitchRel() + pitch));
        } else {
            viewYaw = GazeController.wrap(yaw);
            viewPitch = Math.max(-80f, Math.min(80f, pitch));
        }
    }

    /** The landscape/portrait mapping turned 180° about the screen normal. */
    private static int oppositeRotation(int rotation) {
        switch (rotation) {
            case Surface.ROTATION_90:
                return Surface.ROTATION_270;
            case Surface.ROTATION_270:
                return Surface.ROTATION_90;
            case Surface.ROTATION_180:
                return Surface.ROTATION_0;
            default:
                return Surface.ROTATION_180;
        }
    }

    /**
     * How strongly the screen-up axis of {@code rotation} points at world up.
     *
     * getRotationMatrixFromVector() maps device vectors into the
     * East/North/Up world, so its columns are the device axes: device X is
     * (m[0], m[4], m[8]), device Y is (m[1], m[5], m[9]) and device Z is
     * (m[2], m[6], m[10]). The display up axis is device +Y for ROTATION_0,
     * -Y for 180, -X for 90 and +X for 270 (exactly the remap updateRotation
     * applies), so the world-up component is m[9], -m[9], -m[8] and m[8].
     * Reading m[2]/m[6] compared the screen *normal* with East/North instead,
     * which made a 90° head turn look like the 180° roll.
     */
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

    /**
     * Row-major 3x3: rotate about Y by {@code yawDeg}, then about X by
     * {@code pitchDeg}.
     */
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

    /** Row-major 3x3: rotate about X by {@code pitchDeg}, then about Y by {@code yawDeg}. */
    private static void buildPitchYaw(float[] out, float yawDeg, float pitchDeg) {
        float cy = (float) Math.cos(Math.toRadians(yawDeg));
        float sy = (float) Math.sin(Math.toRadians(yawDeg));
        float cp = (float) Math.cos(Math.toRadians(pitchDeg));
        float sp = (float) Math.sin(Math.toRadians(pitchDeg));
        out[0] = cy;
        out[1] = 0f;
        out[2] = sy;
        out[3] = sp * sy;
        out[4] = cp;
        out[5] = -sp * cy;
        out[6] = -cp * sy;
        out[7] = sp;
        out[8] = cp * cy;
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
                videoWidth = d.width;
                videoHeight = d.height;
                videoSoft = !hardware;
                videoMime = mime == null ? "?" : mime.replace("video/", "").toUpperCase();
                // The track shape is the second detection hint: a 2:1 frame is
                // a matched 360 sphere, 4:1 two spheres, 1:1 a half sphere. Only
                // applied while the user has not overridden the projection.
                if (!modeUserSet && videoWidth > 0 && videoHeight > 0) {
                    ProjectionMode detected = ProjectionMode.detect(
                            videoName, videoWidth, videoHeight, mode);
                    if (detected.proj != mode.proj || detected.pack != mode.pack) {
                        applyProjection(detected, false);
                    }
                }
                videoFormat = formatLine();
                if (!startSeekApplied && startAtMs > 0 && d.durationMs() > 0) {
                    startSeekApplied = true;
                    renderer.seekTo(startAtMs);
                    showStatus("已回到上次位置");
                }
            }
        });
    }

    @Override
    public void onVideoSize(VideoDecoder source, int width, int height) {
    }

    @Override
    public void onAudioInfo(final AudioPlayer source, final int trackCount,
                            final int trackIndex, final String codecName,
                            final int sampleRate, final int channelCount) {
        ui.post(new Runnable() {
            @Override
            public void run() {
                if (source != renderer.audioPlayer()) {
                    return;
                }
                String text = "音轨 " + (trackIndex + 1) + "/" + trackCount
                        + " · " + source.summary();
                if (overlay == OVERLAY_MENU) {
                    setOverlayHint(text);
                } else {
                    showStatus(text);
                }
            }
        });
    }

    @Override
    public void onAudioError(final AudioPlayer source, final String message) {
        if (isFinishing() || isDestroyed()) {
            return;
        }
        ui.post(new Runnable() {
            @Override
            public void run() {
                if (source != renderer.audioPlayer()) {
                    return;
                }
                showStatus("音轨不可用：" + message);
            }
        });
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
        if (SmbSettings.isSmb(path)) {
            int slash = path.lastIndexOf('/');
            return slash < 0 ? path : path.substring(slash + 1);
        }
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
