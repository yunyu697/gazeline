package com.yunyu697.gazeline;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.SurfaceTexture;
import android.media.MediaDataSource;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.opengl.GLUtils;
import android.opengl.Matrix;
import android.util.Log;
import android.view.Surface;

import com.google.vr.sdk.base.Eye;
import com.google.vr.sdk.base.GvrView;
import com.google.vr.sdk.base.HeadTransform;
import com.google.vr.sdk.base.Viewport;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

import javax.microedition.khronos.egl.EGLConfig;

/**
 * Stereo playback on top of the Google VR SDK. The SDK owns the head pose, the
 * eye frusta and the lens distortion; this class only feeds it a decoded frame
 * and the 2D HUD.
 *
 * <p>The picture is sampled as a ray: every pixel of an eye viewport is turned
 * into a direction from that eye's frustum, rotated into the world and then
 * mapped onto the sphere (equirect / fisheye) or a flat virtual screen. The
 * world anchor is the pose captured when the player aligned, so a recenter is
 * just another capture and the video never follows the head on its own.
 *
 * <p>The HUD keeps its old screen-space look: {@link HudView} draws one eye
 * into a bitmap on the UI thread, this renderer stretches that bitmap over each
 * eye viewport. The SDK then distorts both layers together, so the controls
 * curve with the picture through the lenses. The playback owner methods mirror
 * {@link StereoRenderer} so the player does not care which pipeline runs.
 */
final class GvrRenderer implements GvrView.StereoRenderer {

    private static final String TAG = "GazelineGvr";

    private static final String CONTENT_VS =
            "attribute vec2 aPos;\n"
            + "varying vec2 vUv;\n"
            + "void main() {\n"
            + "  vUv = aPos * 0.5 + 0.5;\n"
            + "  gl_Position = vec4(aPos, 0.0, 1.0);\n"
            + "}\n";
    private static final String CONTENT_FS =
            "#extension GL_OES_EGL_image_external : require\n"
            + "precision highp float;\n"
            + "uniform samplerExternalOES uTex;\n"
            + "uniform mat4 uTexMatrix;\n"
            + "uniform mat3 uRot;\n"
            + "uniform float uTanL;\n"
            + "uniform float uTanR;\n"
            + "uniform float uTanB;\n"
            + "uniform float uTanT;\n"
            + "uniform int uMode;\n"
            + "uniform float uHalfLon;\n"
            + "uniform float uHalfFov;\n"
            + "uniform vec2 uPanelTan;\n"
            + "uniform vec4 uEyeRect;\n"
            + "varying vec2 vUv;\n"
            + "const float PI = 3.14159265;\n"
            + "void main() {\n"
            + "  float tx = mix(uTanL, uTanR, vUv.x);\n"
            + "  float ty = mix(uTanB, uTanT, vUv.y);\n"
            + "  vec3 d = normalize(vec3(tx, ty, -1.0));\n"
            + "  vec3 w = uRot * d;\n"
            + "  vec2 uv;\n"
            + "  if (uMode == 1) {\n"
            + "    float lon = atan(w.x, -w.z);\n"
            + "    float lat = asin(clamp(w.y, -1.0, 1.0));\n"
            + "    float s = lon / (2.0 * uHalfLon) + 0.5;\n"
            + "    if (s < 0.0 || s > 1.0) discard;\n"
            + "    uv = vec2(s, 0.5 + lat / PI);\n"
            + "  } else if (uMode == 2) {\n"
            + "    float th = acos(clamp(-w.z, -1.0, 1.0));\n"
            + "    float rr = th / uHalfFov;\n"
            + "    if (rr > 1.0) discard;\n"
            + "    float phi = atan(w.y, w.x);\n"
            + "    uv = vec2(0.5 + cos(phi) * rr * 0.5, 0.5 + sin(phi) * rr * 0.5);\n"
            + "  } else {\n"
            + "    if (w.z > -0.0001) {\n"
            + "      gl_FragColor = vec4(0.012, 0.014, 0.020, 1.0);\n"
            + "      return;\n"
            + "    }\n"
            + "    vec2 q = vec2(w.x, w.y) / (-w.z);\n"
            + "    uv = vec2(q.x / (2.0 * uPanelTan.x) + 0.5,\n"
            + "              q.y / (2.0 * uPanelTan.y) + 0.5);\n"
            + "    if (uv.x < 0.0 || uv.x > 1.0 || uv.y < 0.0 || uv.y > 1.0) {\n"
            + "      gl_FragColor = vec4(0.012, 0.014, 0.020, 1.0);\n"
            + "      return;\n"
            + "    }\n"
            + "  }\n"
            + "  vec2 tuv = uEyeRect.xy + uv * uEyeRect.zw;\n"
            + "  gl_FragColor = texture2D(uTex, (uTexMatrix * vec4(tuv, 0.0, 1.0)).xy);\n"
            + "}\n";

    private static final String HUD_VS =
            "attribute vec2 aPos;\n"
            + "varying vec2 vUv;\n"
            + "void main() {\n"
            + "  vUv = aPos * 0.5 + 0.5;\n"
            + "  gl_Position = vec4(aPos, 0.0, 1.0);\n"
            + "}\n";
    /**
     * The HUD arrives as an Android Bitmap, whose first row is the top of the
     * picture, while GLUtils uploads it so that t=0 is that first row. Sampling
     * t directly therefore paints the layer upside down, so v is flipped here.
     */
    private static final String HUD_FS =
            "precision mediump float;\n"
            + "uniform sampler2D uTex;\n"
            + "varying vec2 vUv;\n"
            + "void main() { gl_FragColor = texture2D(uTex, vec2(vUv.x, 1.0 - vUv.y)); }\n";

    /** Full horizontal angle of the flat virtual screen, matching the old 70° panel. */
    private static final float FLAT_FOV_DEG = 70f;

    private static final int PROBE_W = 64;
    private static final int PROBE_H = 32;

    private final Context context;
    private final String dataSource;
    private volatile MediaDataSource mediaSource;
    private volatile boolean waitingForSource;
    private final VideoDecoder.Listener decoderListener;
    private final AudioPlayer.Listener audioListener;
    private final float[] texMatrix = new float[16];

    private volatile ProjectionMode mode;
    private volatile boolean freeze;
    private volatile float speed = 1f;
    private volatile boolean playing = true;

    private int textureId;
    private SurfaceTexture surfaceTexture;
    private Surface surface;
    private VideoDecoder decoder;
    private AudioPlayer audioPlayer;
    private int audioTrackIndex;

    private int contentProgram;
    private int contentPos;
    private int contentTex;
    private int contentTexMatrix;
    private int contentRot;
    private int contentTanL;
    private int contentTanR;
    private int contentTanB;
    private int contentTanT;
    private int contentMode;
    private int contentHalfLon;
    private int contentHalfFov;
    private int contentPanelTan;
    private int contentEyeRect;

    private int hudProgram;
    private int hudPos;
    private int hudTex;
    private int hudTextureId;
    private boolean hudTextureReady;

    private FloatBuffer quad;

    /** Shared with the UI thread while a HUD bitmap is drawn and uploaded. */
    final Object hudLock = new Object();
    private Bitmap hudBitmap;
    private boolean hudDirty;

    // Head pose, written on the GL thread and read on the UI thread.
    private volatile float headYaw;
    private volatile float headPitchAbs;
    private volatile float anchorYaw;
    private volatile float anchorPitch;
    private volatile boolean anchored;
    private volatile float lookYaw;
    private volatile float lookPitch;

    private final float[] eyeToWorld = new float[16];
    private final float[] anchorRot = new float[16];
    private final float[] anchorTranspose = new float[16];
    private final float[] lookRot = new float[16];
    private final float[] dragTmp = new float[16];
    private final float[] rotWorld = new float[16];
    private final float[] rotPass = new float[9];
    private final float[] forward = new float[3];
    private volatile boolean captureAnchor = true;
    private int eyeIndex;

    private int probeProgram;
    private int probePos;
    private int probeUTex;
    private int probeUMat;
    private int probeFbo;
    private int probeTexId;
    private boolean probeDone;
    private volatile int packEvidence = -1;
    private final ByteBuffer probeBuf =
            ByteBuffer.allocateDirect(PROBE_W * PROBE_H * 4).order(ByteOrder.nativeOrder());

    private long drawnFrames;
    private long textureUpdates;
    private long lastTexTimestamp = -1;
    private boolean eyeLogged;
    private long fpsStartMs;
    private long fpsStartDrawn;
    private long fpsStartTex;
    private volatile float drawFps;
    private volatile float textureFps;

    GvrRenderer(Context context, String dataSource, ProjectionMode mode,
                VideoDecoder.Listener decoderListener, AudioPlayer.Listener audioListener) {
        this.context = context.getApplicationContext();
        this.dataSource = dataSource;
        this.mode = mode;
        this.decoderListener = decoderListener;
        this.audioListener = audioListener;
    }

    /** SMB variant: the decoder waits for the opened random-access source. */
    GvrRenderer(Context context, MediaDataSource mediaSource, ProjectionMode mode,
                VideoDecoder.Listener decoderListener, AudioPlayer.Listener audioListener) {
        this.context = context.getApplicationContext();
        this.dataSource = null;
        this.mediaSource = mediaSource;
        this.waitingForSource = mediaSource == null;
        this.mode = mode;
        this.decoderListener = decoderListener;
        this.audioListener = audioListener;
    }

    // ------------------------------------------------------------- playback API

    void setMode(ProjectionMode m) {
        if (m != null) {
            mode = m;
        }
    }

    ProjectionMode mode() {
        return mode;
    }

    void setMediaSource(MediaDataSource source) {
        if (source == null) {
            return;
        }
        mediaSource = source;
        waitingForSource = false;
        startDecoder();
    }

    /** The SDK owns the head pose now; kept so the player's calls still compile. */
    void setRotation(float[] ignored) {
    }

    void setCalib(CalibParams params) {
    }

    CalibParams calib() {
        return CalibParams.defaults();
    }

    void setFreeze(boolean frozen) {
        freeze = frozen;
    }

    VideoDecoder decoder() {
        return decoder;
    }

    AudioPlayer audioPlayer() {
        return audioPlayer;
    }

    void pausePlayback() {
        playing = false;
        if (decoder != null) {
            decoder.pause();
        }
        if (audioPlayer != null) {
            audioPlayer.pause();
        }
    }

    void resumePlayback() {
        playing = true;
        if (decoder != null) {
            decoder.resume();
        }
        if (audioPlayer != null) {
            audioPlayer.resume();
        }
    }

    void seekTo(long ms) {
        if (decoder != null) {
            decoder.seekTo(ms);
        }
        if (audioPlayer != null) {
            audioPlayer.seekTo(ms);
        }
    }

    void setSpeed(float rate) {
        speed = rate;
        if (decoder != null) {
            decoder.setSpeed(rate);
        }
        AudioPlayer audio = audioPlayer;
        if (audio != null) {
            boolean wasMuted = audio.isMuted();
            audio.setSpeed(rate);
            if (wasMuted && rate >= 0.99f && rate <= 1.01f) {
                audio.seekTo(decoder == null ? 0L : decoder.positionMs());
                if (playing) {
                    audio.resume();
                } else {
                    audio.pause();
                }
            }
        }
    }

    int audioTrackCount() {
        AudioPlayer audio = audioPlayer;
        return audio == null ? 0 : audio.trackCount();
    }

    int audioTrackIndex() {
        AudioPlayer audio = audioPlayer;
        return audio == null ? 0 : audio.trackIndex();
    }

    void nextAudioTrack() {
        AudioPlayer audio = audioPlayer;
        if (audio == null || audio.trackCount() <= 1) {
            return;
        }
        int next = (audio.trackIndex() + 1) % audio.trackCount();
        long posUs = decoder == null ? 0L : decoder.positionMs() * 1000L;
        audioTrackIndex = next;
        stopAudio();
        startAudio(next, posUs);
    }

    long drawnFrames() {
        return drawnFrames;
    }

    long textureUpdates() {
        return textureUpdates;
    }

    float drawFps() {
        return drawFps;
    }

    float textureFps() {
        return textureFps;
    }

    boolean isPanorama() {
        ProjectionMode m = mode;
        return m != null && !m.isFlat();
    }

    int packEvidence() {
        return packEvidence;
    }

    void restartDecoder() {
        if (decoder != null && decoder.isRunning()) {
            return;
        }
        startDecoder();
    }

    void stopDecoder() {
        if (decoder != null) {
            decoder.stop();
        }
        stopAudio();
    }

    void release() {
        stopDecoder();
        if (surface != null) {
            surface.release();
            surface = null;
        }
        if (surfaceTexture != null) {
            surfaceTexture.release();
            surfaceTexture = null;
        }
    }

    // ------------------------------------------------------------- head pose

    /** Heading of the head relative to the anchor, 0 when looking at the centre. */
    float headYawRel() {
        return GazeController.wrap(headYaw - anchorYaw);
    }

    float headPitchRel() {
        return headPitchAbs - anchorPitch;
    }

    /** Absolute pitch, used to tell a worn headset from a phone on the table. */
    float headPitchAbs() {
        return headPitchAbs;
    }

    /** Bake the current head direction in as the picture's forward. */
    void recenter() {
        anchorYaw = headYaw;
        anchorPitch = headPitchAbs;
        captureAnchor = true;
    }

    /** Emulator drag trim: rotates the picture the same way the gaze moves. */
    void setLookOffset(float yawDeg, float pitchDeg) {
        lookYaw = yawDeg;
        lookPitch = pitchDeg;
    }

    void invalidateHud(Bitmap bmp) {
        hudBitmap = bmp;
        hudDirty = true;
    }

    // ------------------------------------------------------------- renderer

    @Override
    public void onSurfaceCreated(EGLConfig config) {
        GLES20.glClearColor(0.012f, 0.014f, 0.020f, 1f);
        textureId = createExternalTexture();
        if (surfaceTexture == null) {
            surfaceTexture = new SurfaceTexture(textureId);
            surfaceTexture.setDefaultBufferSize(1920, 1080);
        }
        surface = new Surface(surfaceTexture);
        quad = createQuad();
        createContentProgram();
        createHudProgram();
        createProbe();
        fpsStartMs = android.os.SystemClock.elapsedRealtime();
        startDecoder();
        Log.i(TAG, "surface created, mode=" + (mode == null ? "?" : mode.label())
                + " tex=" + textureId);
    }

    @Override
    public void onSurfaceChanged(int width, int height) {
        Log.i(TAG, "surface changed " + width + "x" + height);
    }

    @Override
    public void onNewFrame(HeadTransform headTransform) {
        eyeIndex = 0;
        if (headTransform != null) {
            headTransform.getForwardVector(forward, 0);
            float yaw = (float) Math.toDegrees(Math.atan2(forward[0], -forward[2]));
            float pitch = (float) Math.toDegrees(Math.asin(
                    Math.max(-1f, Math.min(1f, forward[1]))));
            headYaw = yaw;
            headPitchAbs = pitch;
            if (!anchored) {
                anchorYaw = yaw;
                anchorPitch = pitch;
                anchored = true;
                captureAnchor = true;
            }
        }

        if (surfaceTexture != null && !freeze) {
            try {
                surfaceTexture.updateTexImage();
                surfaceTexture.getTransformMatrix(texMatrix);
                long ts = surfaceTexture.getTimestamp();
                if (ts != lastTexTimestamp) {
                    lastTexTimestamp = ts;
                    textureUpdates++;
                }
            } catch (Exception e) {
                Log.w(TAG, "updateTexImage: " + e.getMessage());
            }
        }

        uploadHud();
        probePacking();

        // The SDK leaves the strip between the two eye viewports untouched;
        // one full clear keeps it black instead of showing the leftovers of the
        // previous frame (visible when distortion correction is off).
        GLES20.glDisable(GLES20.GL_SCISSOR_TEST);
        GLES20.glClearColor(0.012f, 0.014f, 0.020f, 1f);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);

        long now = android.os.SystemClock.elapsedRealtime();
        if (now - fpsStartMs >= 1000) {
            float secs = (now - fpsStartMs) / 1000f;
            drawFps = (drawnFrames - fpsStartDrawn) / secs;
            textureFps = (textureUpdates - fpsStartTex) / secs;
            fpsStartMs = now;
            fpsStartDrawn = drawnFrames;
            fpsStartTex = textureUpdates;
        }
    }

    @Override
    public void onDrawEye(Eye eye) {
        Viewport vp = eye.getViewport();
        GLES20.glViewport(vp.x, vp.y, vp.width, vp.height);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);

        float[] proj = eye.getPerspective(0.1f, 100f);
        float tanHalfX = proj[0] == 0f ? 1f : 1f / proj[0];
        float tanHalfY = proj[5] == 0f ? 1f : 1f / proj[5];
        float centerX = proj[8] * tanHalfX;
        float centerY = proj[9] * tanHalfY;

        eyeToWorldRotation(eyeToWorld, eye.getEyeView());
        if (captureAnchor) {
            captureAnchor = false;
            System.arraycopy(eyeToWorld, 0, anchorRot, 0, 16);
        }
        // Content <- eye = anchor^T * drag * eye->world.
        transposeRotation(anchorTranspose, anchorRot);
        buildLookRotation(lookRot, lookYaw, lookPitch);
        Matrix.multiplyMM(dragTmp, 0, lookRot, 0, eyeToWorld, 0);
        Matrix.multiplyMM(rotWorld, 0, anchorTranspose, 0, dragTmp, 0);

        ProjectionMode m = mode == null ? ProjectionMode.FLAT_MONO : mode;
        drawContent(vp, m, tanHalfX, centerX, tanHalfY, centerY, eyeIndex);
        drawHud(vp);
        eyeIndex = 1 - eyeIndex;

        if (!eyeLogged) {
            eyeLogged = true;
            Log.i(TAG, "first eye vp=" + vp.x + "," + vp.y + " " + vp.width + "x" + vp.height
                    + " type=" + eye.getType() + " tan=" + tanHalfX + "/" + tanHalfY);
        }
        drawnFrames++;
        GlUtil.check("eye");
    }

    @Override
    public void onFinishFrame(Viewport viewport) {
    }

    @Override
    public void onRendererShutdown() {
    }

    // ------------------------------------------------------------- drawing

    private void drawContent(Viewport vp, ProjectionMode m, float tanHalfX, float centerX,
                             float tanHalfY, float centerY, int eye) {
        GLES20.glUseProgram(contentProgram);

        setEyeRect(m.pack, eye);
        int shaderMode = m.isFlat() ? 0 : (m.isFisheye() ? 2 : 1);
        GLES20.glUniform1i(contentMode, shaderMode);
        GLES20.glUniform1f(contentHalfLon, (float) Math.toRadians(m.halfLonDeg()));
        GLES20.glUniform1f(contentHalfFov,
                (float) Math.toRadians(m.fisheyeFovDeg() / 2f));
        GLES20.glUniform1f(contentTanL, centerX - tanHalfX);
        GLES20.glUniform1f(contentTanR, centerX + tanHalfX);
        GLES20.glUniform1f(contentTanB, centerY - tanHalfY);
        GLES20.glUniform1f(contentTanT, centerY + tanHalfY);

        int vw = decoder != null ? decoder.width : 0;
        int vh = decoder != null ? decoder.height : 0;
        float frameAspect = (vw > 0 && vh > 0) ? (float) vw / vh : 16f / 9f;
        float videoAspect = frameAspect;
        if (m.pack == ProjectionMode.SBS) {
            videoAspect = frameAspect * 0.5f;
        } else if (m.pack == ProjectionMode.TB) {
            videoAspect = frameAspect * 2f;
        }
        float panelX = (float) Math.tan(Math.toRadians(FLAT_FOV_DEG / 2f));
        float panelY = panelX / videoAspect;
        GLES20.glUniform2f(contentPanelTan, panelX, panelY);

        rotPass[0] = rotWorld[0];
        rotPass[1] = rotWorld[1];
        rotPass[2] = rotWorld[2];
        rotPass[3] = rotWorld[4];
        rotPass[4] = rotWorld[5];
        rotPass[5] = rotWorld[6];
        rotPass[6] = rotWorld[8];
        rotPass[7] = rotWorld[9];
        rotPass[8] = rotWorld[10];
        GLES20.glUniformMatrix3fv(contentRot, 1, false, rotPass, 0);
        GLES20.glUniformMatrix4fv(contentTexMatrix, 1, false, texMatrix, 0);

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId);
        GLES20.glUniform1i(contentTex, 0);

        GLES20.glEnableVertexAttribArray(contentPos);
        GLES20.glVertexAttribPointer(contentPos, 2, GLES20.GL_FLOAT, false, 0, quad);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        GLES20.glDisableVertexAttribArray(contentPos);
    }

    private void drawHud(Viewport vp) {
        if (!hudTextureReady || hudProgram == 0) {
            return;
        }
        GLES20.glUseProgram(hudProgram);
        GLES20.glDisable(GLES20.GL_DEPTH_TEST);
        GLES20.glEnable(GLES20.GL_BLEND);
        GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, hudTextureId);
        GLES20.glUniform1i(hudTex, 0);
        GLES20.glEnableVertexAttribArray(hudPos);
        GLES20.glVertexAttribPointer(hudPos, 2, GLES20.GL_FLOAT, false, 0, quad);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        GLES20.glDisableVertexAttribArray(hudPos);
        GLES20.glDisable(GLES20.GL_BLEND);
    }

    private void uploadHud() {
        if (!hudDirty || hudBitmap == null || hudTextureId == 0) {
            return;
        }
        synchronized (hudLock) {
            if (!hudDirty || hudBitmap == null) {
                return;
            }
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, hudTextureId);
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, hudBitmap, 0);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);
            hudTextureReady = true;
            hudDirty = false;
        }
    }

    private void setEyeRect(int pack, int eye) {
        float x = 0f;
        float y = 0f;
        float w = 1f;
        float h = 1f;
        if (pack == ProjectionMode.SBS) {
            w = 0.5f;
            x = eye == 0 ? 0f : 0.5f;
        } else if (pack == ProjectionMode.TB) {
            h = 0.5f;
            y = eye == 0 ? 0f : 0.5f;
        }
        GLES20.glUniform4f(contentEyeRect, x, y, w, h);
    }

    // ------------------------------------------------------------- programs

    private void createContentProgram() {
        contentProgram = GlUtil.link(CONTENT_VS, CONTENT_FS);
        contentPos = GLES20.glGetAttribLocation(contentProgram, "aPos");
        contentTex = GlUtil.uniform(contentProgram, "uTex");
        contentTexMatrix = GlUtil.uniform(contentProgram, "uTexMatrix");
        contentRot = GlUtil.uniform(contentProgram, "uRot");
        contentTanL = GlUtil.uniform(contentProgram, "uTanL");
        contentTanR = GlUtil.uniform(contentProgram, "uTanR");
        contentTanB = GlUtil.uniform(contentProgram, "uTanB");
        contentTanT = GlUtil.uniform(contentProgram, "uTanT");
        contentMode = GlUtil.uniform(contentProgram, "uMode");
        contentHalfLon = GlUtil.uniform(contentProgram, "uHalfLon");
        contentHalfFov = GlUtil.uniform(contentProgram, "uHalfFov");
        contentPanelTan = GlUtil.uniform(contentProgram, "uPanelTan");
        contentEyeRect = GlUtil.uniform(contentProgram, "uEyeRect");
    }

    private void createHudProgram() {
        hudProgram = GlUtil.link(HUD_VS, HUD_FS);
        hudPos = GLES20.glGetAttribLocation(hudProgram, "aPos");
        hudTex = GlUtil.uniform(hudProgram, "uTex");
        int[] tex = new int[1];
        GLES20.glGenTextures(1, tex, 0);
        hudTextureId = tex[0];
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, hudTextureId);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,
                GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,
                GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,
                GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,
                GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);
    }

    // ------------------------------------------------------------- math

    /** eyeView is world->eye; this stores its transposed rotation (eye->world). */
    private static void eyeToWorldRotation(float[] out, float[] eyeView) {
        Matrix.setIdentityM(out, 0);
        out[0] = eyeView[0];
        out[1] = eyeView[4];
        out[2] = eyeView[8];
        out[4] = eyeView[1];
        out[5] = eyeView[5];
        out[6] = eyeView[9];
        out[8] = eyeView[2];
        out[9] = eyeView[6];
        out[10] = eyeView[10];
    }

    private static void transposeRotation(float[] out, float[] in) {
        Matrix.setIdentityM(out, 0);
        out[0] = in[0];
        out[1] = in[4];
        out[2] = in[8];
        out[4] = in[1];
        out[5] = in[5];
        out[6] = in[9];
        out[8] = in[2];
        out[9] = in[6];
        out[10] = in[10];
    }

    private static void buildLookRotation(float[] out, float yawDeg, float pitchDeg) {
        Matrix.setIdentityM(out, 0);
        Matrix.rotateM(out, 0, yawDeg, 0f, 1f, 0f);
        Matrix.rotateM(out, 0, pitchDeg, 1f, 0f, 0f);
    }

    private static FloatBuffer createQuad() {
        float[] verts = {-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f};
        FloatBuffer b = ByteBuffer.allocateDirect(verts.length * 4)
                .order(ByteOrder.nativeOrder()).asFloatBuffer();
        b.put(verts).position(0);
        return b;
    }

    private static int createExternalTexture() {
        int[] tex = new int[1];
        GLES20.glGenTextures(1, tex, 0);
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, tex[0]);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0);
        return tex[0];
    }

    // ------------------------------------------------------------- decoder

    private void startDecoder() {
        if (surface == null || waitingForSource) {
            return;
        }
        probeDone = false;
        packEvidence = -1;
        stopAudio();
        startAudio(audioTrackIndex, 0L);
        decoder = new VideoDecoder(context, dataSource, mediaSource, surface, decoderListener, audioPlayer);
        decoder.setSpeed(speed);
        if (!playing) {
            decoder.pause();
        }
        decoder.start();
    }

    private void startAudio(int trackIndex, long startUs) {
        if (audioListener == null) {
            return;
        }
        audioPlayer = new AudioPlayer(context, dataSource, mediaSource, trackIndex, startUs, audioListener);
        audioPlayer.setSpeed(speed);
        if (!playing) {
            audioPlayer.pause();
        }
        audioPlayer.start();
    }

    private void stopAudio() {
        if (audioPlayer != null) {
            audioPlayer.stop();
            audioPlayer = null;
        }
    }

    // ------------------------------------------------------------- packing probe

    private void createProbe() {
        probeProgram = GlUtil.link(
                "attribute vec2 aPos;\n"
                + "varying vec2 vUv;\n"
                + "void main() { vUv = aPos * 0.5 + 0.5; gl_Position = vec4(aPos, 0.0, 1.0); }\n",
                "#extension GL_OES_EGL_image_external : require\n"
                + "precision highp float;\n"
                + "uniform samplerExternalOES uTex;\n"
                + "uniform mat4 uTexMatrix;\n"
                + "varying vec2 vUv;\n"
                + "void main() { gl_FragColor = texture2D(uTex, (uTexMatrix * vec4(vUv, 0.0, 1.0)).xy); }\n");
        probePos = GLES20.glGetAttribLocation(probeProgram, "aPos");
        probeUTex = GlUtil.uniform(probeProgram, "uTex");
        probeUMat = GlUtil.uniform(probeProgram, "uTexMatrix");
        int[] tex = new int[1];
        GLES20.glGenTextures(1, tex, 0);
        probeTexId = tex[0];
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, probeTexId);
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, PROBE_W, PROBE_H, 0,
                GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,
                GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,
                GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,
                GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,
                GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        int[] fbo = new int[1];
        GLES20.glGenFramebuffers(1, fbo, 0);
        probeFbo = fbo[0];
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, probeFbo);
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER,
                GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, probeTexId, 0);
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);
    }

    private void probePacking() {
        if (probeDone || probeFbo == 0 || textureUpdates < 3 || quad == null) {
            return;
        }
        int[] prevFbo = new int[1];
        int[] prevVp = new int[4];
        GLES20.glGetIntegerv(GLES20.GL_FRAMEBUFFER_BINDING, prevFbo, 0);
        GLES20.glGetIntegerv(GLES20.GL_VIEWPORT, prevVp, 0);

        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, probeFbo);
        GLES20.glViewport(0, 0, PROBE_W, PROBE_H);
        GLES20.glUseProgram(probeProgram);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId);
        GLES20.glUniform1i(probeUTex, 0);
        GLES20.glUniformMatrix4fv(probeUMat, 1, false, texMatrix, 0);
        GLES20.glEnableVertexAttribArray(probePos);
        GLES20.glVertexAttribPointer(probePos, 2, GLES20.GL_FLOAT, false, 0, quad);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        GLES20.glDisableVertexAttribArray(probePos);
        probeBuf.position(0);
        GLES20.glReadPixels(0, 0, PROBE_W, PROBE_H,
                GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, probeBuf);
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, prevFbo[0]);
        GLES20.glViewport(prevVp[0], prevVp[1], prevVp[2], prevVp[3]);
        GLES20.glUseProgram(0);
        GlUtil.check("probe");

        int half = PROBE_W / 2;
        long lr = 0;
        long tb = 0;
        long sum = 0;
        for (int y = 0; y < PROBE_H; y++) {
            for (int x = 0; x < PROBE_W; x++) {
                int i = (y * PROBE_W + x) * 4;
                int r = probeBuf.get(i) & 0xFF;
                int g = probeBuf.get(i + 1) & 0xFF;
                int b = probeBuf.get(i + 2) & 0xFF;
                sum += r + g + b;
                int j = (y * PROBE_W + ((x + half) % PROBE_W)) * 4;
                lr += Math.abs(r - (probeBuf.get(j) & 0xFF))
                        + Math.abs(g - (probeBuf.get(j + 1) & 0xFF))
                        + Math.abs(b - (probeBuf.get(j + 2) & 0xFF));
                int k = (((y + PROBE_H / 2) % PROBE_H) * PROBE_W + x) * 4;
                tb += Math.abs(r - (probeBuf.get(k) & 0xFF))
                        + Math.abs(g - (probeBuf.get(k + 1) & 0xFF))
                        + Math.abs(b - (probeBuf.get(k + 2) & 0xFF));
            }
        }
        float n = PROBE_W * PROBE_H * 3f;
        float dLR = lr / n;
        float dTB = tb / n;
        if (sum / n < 6f) {
            return;
        }
        probeDone = true;
        if (dLR < 10f && dTB < 10f) {
            Log.i(TAG, "pack probe ambiguous lr=" + dLR + " tb=" + dTB);
            return;
        }
        if (dLR < 10f && dLR * 2.2f < dTB) {
            packEvidence = ProjectionMode.SBS;
        } else if (dTB < 10f && dTB * 2.2f < dLR) {
            packEvidence = ProjectionMode.TB;
        } else {
            Log.i(TAG, "pack probe unclear lr=" + dLR + " tb=" + dTB);
            return;
        }
        Log.i(TAG, "pack probe lr=" + dLR + " tb=" + dTB + " -> " + packEvidence);
    }
}
