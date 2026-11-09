package com.yunyu697.gazeline;

import android.content.Context;
import android.graphics.SurfaceTexture;
import android.media.MediaDataSource;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.util.Log;
import android.view.Surface;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/**
 * Draws the decoded external texture into two eye viewports. 360 and SBS keep
 * separate shader pipelines (Projection360 / ProjectionSbs); nothing about the
 * lens or FOV is shared between them.
 */
final class StereoRenderer implements GLSurfaceView.Renderer {

    private static final String TAG = "GazelineGL";
    private static final float[] IDENTITY = {
            1f, 0f, 0f,
            0f, 1f, 0f,
            0f, 0f, 1f
    };

    private final Context context;
    private final String dataSource;
    /** SMB source, set asynchronously by {@link #setMediaSource}. */
    private volatile MediaDataSource mediaSource;
    /** True until an SMB connection hands over its MediaDataSource. */
    private volatile boolean waitingForSource;
    private final boolean panorama;
    private final VideoDecoder.Listener decoderListener;
    private final AudioPlayer.Listener audioListener;
    private final float[] texMatrix = new float[16];

    private volatile float[] rot = IDENTITY;
    private volatile boolean freeze;
    private volatile CalibParams calib = CalibParams.defaults();
    private volatile float speed = 1f;
    private volatile boolean playing = true;

    private int textureId;
    private SurfaceTexture surfaceTexture;
    private Surface surface;
    private VideoDecoder decoder;
    private AudioPlayer audioPlayer;
    private int audioTrackIndex;
    private Projection360 p360;
    private ProjectionSbs psbs;
    private FloatBuffer quad;

    private int viewWidth;
    private int viewHeight;

    private long drawnFrames;
    private long textureUpdates;
    private long lastTexTimestamp = -1;
    private boolean matrixLogged;
    private long fpsStartMs;
    private long fpsStartDrawn;
    private long fpsStartTex;
    private volatile float drawFps;
    private volatile float textureFps;

    StereoRenderer(Context context, String dataSource, boolean panorama,
                   VideoDecoder.Listener decoderListener, AudioPlayer.Listener audioListener) {
        this.context = context.getApplicationContext();
        this.dataSource = dataSource;
        this.panorama = panorama;
        this.decoderListener = decoderListener;
        this.audioListener = audioListener;
    }

    /**
     * SMB variant: the decoder waits until the player hands over the opened
     * random-access source, so a slow TCP connect never blocks the GL thread.
     */
    StereoRenderer(Context context, MediaDataSource mediaSource, boolean panorama,
                   VideoDecoder.Listener decoderListener, AudioPlayer.Listener audioListener) {
        this.context = context.getApplicationContext();
        this.dataSource = null;
        this.mediaSource = mediaSource;
        this.waitingForSource = mediaSource == null;
        this.panorama = panorama;
        this.decoderListener = decoderListener;
        this.audioListener = audioListener;
    }

    void setMediaSource(MediaDataSource source) {
        if (source == null) {
            return;
        }
        mediaSource = source;
        waitingForSource = false;
        Log.i(TAG, "media source ready, starting decoder");
        startDecoder();
    }

    void setRotation(float[] rowMajor3x3) {
        rot = rowMajor3x3;
    }

    /** §1.12 values take effect on the next frame; no shader rebuild needed. */
    void setCalib(CalibParams params) {
        calib = params;
    }

    CalibParams calib() {
        return calib;
    }

    /** Recenter holds the last decoded frame on screen while the world rotates. */
    void setFreeze(boolean frozen) {
        freeze = frozen;
    }

    VideoDecoder decoder() {
        return decoder;
    }

    AudioPlayer audioPlayer() {
        return audioPlayer;
    }

    /** Pause both tracks without touching the activity's own state. */
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

    /** Seek both tracks; the decoder clamps against the known duration. */
    void seekTo(long ms) {
        if (decoder != null) {
            decoder.seekTo(ms);
        }
        if (audioPlayer != null) {
            audioPlayer.seekTo(ms);
        }
    }

    /**
     * Speed changes are a placeholder for audio: 1.0x has sound, other rates
     * mute the track. Returning to 1.0x re-seeks the audio to the video clock
     * so the two do not drift.
     */
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

    /** Cycle to the next audio track of the same file, keeping the video position. */
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
        return panorama;
    }

    @Override
    public void onSurfaceCreated(GL10 gl, EGLConfig config) {
        GLES20.glClearColor(0.012f, 0.014f, 0.020f, 1f);
        textureId = createExternalTexture();
        if (surfaceTexture == null) {
            surfaceTexture = new SurfaceTexture(textureId);
            surfaceTexture.setDefaultBufferSize(1920, 1080);
        }
        surface = new Surface(surfaceTexture);
        p360 = new Projection360();
        psbs = new ProjectionSbs();
        p360.setCalib(calib);
        psbs.setCalib(calib);
        quad = createQuad();
        fpsStartMs = SystemClockCompat.now();
        fpsStartDrawn = drawnFrames;
        fpsStartTex = textureUpdates;
        startDecoder();
        Log.i(TAG, "surface created, mode=" + (panorama ? "360" : "SBS") + " tex=" + textureId);
    }

    @Override
    public void onSurfaceChanged(GL10 gl, int width, int height) {
        viewWidth = width;
        viewHeight = height;
        Log.i(TAG, "surface changed " + width + "x" + height);
    }

    @Override
    public void onDrawFrame(GL10 gl) {
        if (viewWidth <= 0 || viewHeight <= 0) {
            return;
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
                if (!matrixLogged) {
                    matrixLogged = true;
                    Log.i(TAG, "texMatrix=" + java.util.Arrays.toString(texMatrix));
                    Log.i(TAG, "rot=" + java.util.Arrays.toString(rot));
                }
            } catch (Exception e) {
                Log.w(TAG, "updateTexImage: " + e.getMessage());
            }
        }

        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
        int halfW = viewWidth / 2;
        float eyeAspect = (halfW > 0) ? (halfW * 1f) / viewHeight : 1f;
        float[] r = rot;

        if (panorama) {
            p360.setCalib(calib);
            GLES20.glViewport(0, 0, halfW, viewHeight);
            p360.draw(textureId, texMatrix, r, eyeAspect, 0, quad);
            GLES20.glViewport(halfW, 0, viewWidth - halfW, viewHeight);
            p360.draw(textureId, texMatrix, r, eyeAspect, 1, quad);
        } else {
            psbs.setCalib(calib);
            int vw = decoder != null ? decoder.width : 0;
            int vh = decoder != null ? decoder.height : 0;
            GLES20.glViewport(0, 0, halfW, viewHeight);
            psbs.draw(textureId, texMatrix, eyeAspect, 0, vw, vh, quad);
            GLES20.glViewport(halfW, 0, viewWidth - halfW, viewHeight);
            psbs.draw(textureId, texMatrix, eyeAspect, 1, vw, vh, quad);
        }
        GlUtil.check("frame");

        drawnFrames++;
        long now = SystemClockCompat.now();
        if (now - fpsStartMs >= 1000) {
            float secs = (now - fpsStartMs) / 1000f;
            drawFps = (drawnFrames - fpsStartDrawn) / secs;
            textureFps = (textureUpdates - fpsStartTex) / secs;
            fpsStartMs = now;
            fpsStartDrawn = drawnFrames;
            fpsStartTex = textureUpdates;
        }
    }

    /** Rebuild the decoder after onPause stopped it; the Surface stays valid. */
    void restartDecoder() {
        if (decoder != null && decoder.isRunning()) {
            return;
        }
        startDecoder();
    }

    private void startDecoder() {
        if (surface == null || waitingForSource) {
            return;
        }
        stopDecoder();
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

    void stopDecoder() {
        if (decoder != null) {
            decoder.stop();
        }
        stopAudio();
    }

    void release() {
        stopDecoder();
        MediaDataSource source = mediaSource;
        mediaSource = null;
        if (source != null) {
            try {
                source.close();
            } catch (Throwable ignored) {
            }
        }
        if (p360 != null) {
            p360.release();
            p360 = null;
        }
        if (psbs != null) {
            psbs.release();
            psbs = null;
        }
        if (surface != null) {
            surface.release();
            surface = null;
        }
        if (surfaceTexture != null) {
            surfaceTexture.release();
            surfaceTexture = null;
        }
        if (textureId != 0) {
            GLES20.glDeleteTextures(1, new int[]{textureId}, 0);
            textureId = 0;
        }
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

    private static FloatBuffer createQuad() {
        float[] verts = {-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f};
        FloatBuffer b = ByteBuffer.allocateDirect(verts.length * 4)
                .order(ByteOrder.nativeOrder()).asFloatBuffer();
        b.put(verts).position(0);
        return b;
    }

    private static final class SystemClockCompat {
        static long now() {
            return android.os.SystemClock.elapsedRealtime();
        }
    }
}
