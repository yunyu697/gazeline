package com.yunyu697.gazeline;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.media.MediaDataSource;
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

    private static final long HISTORY_EVERY_MS = 5000L;

    private static final int REC_IDLE = 0;
    private static final int REC_COUNTDOWN = 1;
    private static final int REC_MASK_IN = 2;
    private static final int REC_MASK_OUT = 3;

    /** Grace window after the gaze trigger: turn back to a comfortable pose. */
    private static final long RECENTER_GRACE_MS = 3500;

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

    static final class BrowserRow {
        String title = "";
        String detail = "";
        String path = "";
        int kind;
    }

    // recenter: grace countdown, then a short mask while the correction lands
    private int recPhase = REC_IDLE;
    private long recPhaseStart;
    private float recCountdown;
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
        panorama = "360".equals(getIntent().getStringExtra(EXTRA_MODE));
        sensorWanted = getIntent().getBooleanExtra(EXTRA_SENSOR, true);
        startAtMs = Math.max(0L, getIntent().getLongExtra(EXTRA_POSITION, 0L));
        smbMode = !browserOnly && SmbSettings.isSmb(dataSource);
        videoName = browserOnly ? "片库" : displayName(dataSource);
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
        }
        sensorActive = sensorWanted && rotationSensor != null;

        Matrix.setIdentityM(sensorMatrix, 0);

        feedback = new Feedback(this);
        gaze = new GazeController(this);
        menuGaze = new GazeController(this);
        menuGaze.setLayer(HudSpec.MENU_ROWS, null, false);

        if (!browserOnly) {
            glView = new GLSurfaceView(this);
            glView.setEGLContextClientVersion(2);
            renderer = smbMode
                    ? new StereoRenderer(this, (MediaDataSource) null, panorama, this, this)
                    : new StereoRenderer(this, dataSource, panorama, this, this);
            renderer.setCalib(CalibParams.load(this));
            glView.setRenderer(renderer);
            glView.setRenderMode(GLSurfaceView.RENDERMODE_CONTINUOUSLY);
        }

        hud = new HudView(this);
        hud.setListener(this);

        FrameLayout root = new FrameLayout(this);
        if (glView != null) {
            root.addView(glView, new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        } else {
            root.setBackgroundColor(0xFF05070A);
        }
        root.addView(hud, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        setContentView(root);

        sessionStartMs = SystemClock.elapsedRealtime();
        lastActivityMs = sessionStartMs;
        showStatus(browserOnly ? "片库 · 凝视选择" : "显示 " + displayMode);
        updateRotation();
        sensorPrimed = !sensorActive;
        if (!sensorActive) {
            gaze.setAnchor(viewYaw, viewPitch);
        }
        if (smbMode) {
            videoFormat = "SMB · 正在连接…";
            beginSmbOpen();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (glView != null) {
            glView.onResume();
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
        if (glView != null) {
            glView.onPause();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (smbSource != null) {
            smbSource.close();
            smbSource = null;
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
                panorama ? "360" : "sbs", position, d.durationMs());
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
        if (overlay == OVERLAY_MENU) {
            closeOverlay("菜单已关闭");
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

        updateRecenter(now);

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
        frame.positionMs = d == null ? 0 : d.positionMs();
        frame.durationMs = d == null ? 0 : d.durationMs();
        frame.browserTargets = browserTargets.toArray(new HudSpec.Target[0]);
        frame.browserPath = browserPathText();
        frame.browserHint = browserHint;
        frame.browserPage = browserPage;
        frame.browserPages = browserPages;
        frame.browserLoading = browserLoading;
        frame.browserGen = browserGen;
        frame.gaze = gaze.readout();
        frame.hudAlpha = hudAlpha;
        frame.wakeAlpha = wakeAlpha;
        frame.maskAlpha = maskAlpha;
        frame.flashAlpha = flashAlpha;
        frame.recenterCountdown = recPhase == REC_COUNTDOWN ? recCountdown : 0f;

        frame.overlay = overlay;
        frame.overlayAlpha = overlayAlpha;
        frame.overlayGaze = menuGaze.readout();
        frame.overlayHint = overlayHint;
        frame.speed = playbackRate;
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
                toggleSubtitle(false);
                break;
            case RECENTER:
                startRecenter();
                break;
            case MENU:
                openMenu();
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
            case MENU_FILE:
                openBrowser();
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
     * Gaze triggers the action; the user then gets a grace window to settle into
     * a comfortable pose. When it expires, whatever direction the head is
     * pointing at that moment becomes the new forward — no forced rotation.
     */
    private void startRecenter() {
        if (recPhase != REC_IDLE) {
            return;
        }
        long now = SystemClock.elapsedRealtime();
        recPhase = REC_COUNTDOWN;
        recPhaseStart = now;
        recCountdown = RECENTER_GRACE_MS / 1000f;
        maskAlpha = 0f;
        flashAlpha = 0f;
        lastActivityMs = now;
        showStatus("请调整姿态 · 随后按当前朝向回中");
    }

    private void updateRecenter(long now) {
        switch (recPhase) {
            case REC_COUNTDOWN: {
                float left = RECENTER_GRACE_MS - (now - recPhaseStart);
                recCountdown = Math.max(0f, left / 1000f);
                if (left <= 0f) {
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
        updateRotation();
        worldYaw = GazeController.wrap(worldYaw - viewYaw);
        worldPitch = clamp(worldPitch - viewPitch, -80f, 80f);
        updateRotation();
    }

    private void finishRecenter(long now, boolean interrupted) {
        recPhase = REC_IDLE;
        recCountdown = 0f;
        maskAlpha = 0f;
        flashAlpha = 0f;
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

    // ------------------------------------------------------------- VR browser

    private static final int BROW_KIND_DIR = 1;
    private static final int BROW_KIND_VIDEO = 2;
    private static final int BROW_KIND_FAV = 3;
    private static final int BROW_KIND_SERVER = 4;
    private static final int BROWSER_PAGE_SIZE = 5;

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
        menuGaze.setAnchor(viewYaw, viewPitch);
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
            r.title = e.name;
            String detail = sizeText(e.size);
            PlayHistory.Entry h = PlayHistory.find(this, settings.url(e.path));
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
        browserPages = Math.max(1, (size + BROWSER_PAGE_SIZE - 1) / BROWSER_PAGE_SIZE);
        if (browserPage >= browserPages) {
            browserPage = browserPages - 1;
        }
        if (browserPage < 0) {
            browserPage = 0;
        }
        int start = browserPage * BROWSER_PAGE_SIZE;
        for (int slot = 0; slot < BROWSER_PAGE_SIZE; slot++) {
            int index = start + slot;
            if (index >= size) {
                break;
            }
            BrowserRow r = browserRows.get(index);
            browserTargets.add(HudSpec.browserRow(HudSpec.BROWSER_ROW_PITCH[slot],
                    r.title, r.detail, index));
        }
        browserTargets.add(HudSpec.BROWSER_PREV_T);
        browserTargets.add(HudSpec.BROWSER_NEXT_T);
        SmbSettings settings = SmbSettings.load(this);
        browserTargets.add(HudSpec.browserMode(
                "360".equals(settings.projection) ? "360°" : "SBS"));
        browserTargets.add(HudSpec.BROWSER_CLOSE_T);
        menuGaze.setLayer(browserTargets.toArray(new HudSpec.Target[0]), null, false);
        menuGaze.protect(SystemClock.elapsedRealtime(), 260);
        browserGen++;
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
                settings.projection = "360".equals(settings.projection) ? "sbs" : "360";
                settings.save(this);
                browserHint = "投影已切换为 " + ("360".equals(settings.projection)
                        ? "360°" : "SBS");
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
        String mode = "360".equals(settings.projection) ? "360" : "sbs";
        Intent play = new Intent(this, PlayerActivity.class);
        play.putExtra(EXTRA_PATH, source);
        play.putExtra(EXTRA_MODE, mode);
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
            // The first real head sample defines the video's forward direction,
            // so playback starts facing the way the headset is worn.
            applyRecenter();
            gaze.setAnchor(viewYaw, viewPitch);
            if (overlay != OVERLAY_NONE) {
                // The panel may have opened before the first real head sample;
                // re-anchor it so it sits in front of the user instead of off screen.
                menuGaze.setAnchor(viewYaw, viewPitch);
            }
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
