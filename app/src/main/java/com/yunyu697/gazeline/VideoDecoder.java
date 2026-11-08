package com.yunyu697.gazeline;

import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;
import android.os.Build;
import android.os.SystemClock;
import android.util.Log;
import android.view.Surface;

import java.nio.ByteBuffer;

/**
 * MediaCodec decode loop. Picks c2.qti.hevc.decoder first for HEVC, then any
 * Qualcomm decoder, then any hardware decoder, then whatever the platform offers.
 * Decoded frames go straight to the SurfaceTexture-backed Surface.
 */
final class VideoDecoder implements Runnable {

    private static final String TAG = "GazelineDec";
    static final String PREFERRED_HEVC = "c2.qti.hevc.decoder";

    interface Listener {
        void onVideoInfo(VideoDecoder source, String codecName, boolean hardware, String mime);

        void onVideoSize(VideoDecoder source, int width, int height);

        void onDecodeError(VideoDecoder source, String message);
    }

    private final Context context;
    private final String dataSource;
    private final Surface surface;
    private final Listener listener;

    private volatile boolean running = true;
    private Thread thread;

    volatile int width;
    volatile int height;
    volatile String codecName = "n/a";
    volatile boolean hardwareAccelerated;
    volatile String mimeType = "n/a";
    volatile long decodedFrames;
    volatile long lateFrames;
    volatile long loopCount;
    volatile String error;

    VideoDecoder(Context context, String dataSource, Surface surface, Listener listener) {
        this.context = context.getApplicationContext();
        this.dataSource = dataSource;
        this.surface = surface;
        this.listener = listener;
    }

    void start() {
        if (thread != null) {
            return;
        }
        thread = new Thread(this, "gazeline-decode");
        thread.start();
    }

    void stop() {
        running = false;
        Thread t = thread;
        if (t != null) {
            try {
                t.join(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        thread = null;
    }

    boolean isRunning() {
        return thread != null && running;
    }

    @Override
    public void run() {
        MediaExtractor extractor = new MediaExtractor();
        MediaCodec codec = null;
        try {
            openSource(extractor);
            int track = selectVideoTrack(extractor);
            if (track < 0) {
                fail("没有视频轨道");
                return;
            }
            extractor.selectTrack(track);
            MediaFormat format = extractor.getTrackFormat(track);
            mimeType = format.getString(MediaFormat.KEY_MIME);
            width = intOf(format, MediaFormat.KEY_WIDTH, 0);
            height = intOf(format, MediaFormat.KEY_HEIGHT, 0);
            if (width <= 0 || height <= 0) {
                fail("视频尺寸无效");
                return;
            }
            logColor(format);
            listener.onVideoSize(this, width, height);

            String chosen = pickCodec(mimeType);
            if (chosen != null) {
                codec = MediaCodec.createByCodecName(chosen);
            } else {
                codec = MediaCodec.createDecoderByType(mimeType);
            }
            codec.configure(format, surface, null, 0);
            codec.start();

            codecName = codec.getName();
            hardwareAccelerated = isHardwareCodec(codecName);
            listener.onVideoInfo(this, codecName, hardwareAccelerated, mimeType);
            Log.i(TAG, "codec=" + codecName + " hw=" + hardwareAccelerated
                    + " mime=" + mimeType + " " + width + "x" + height
                    + " requested=" + (chosen == null ? "by-type" : chosen));

            while (running) {
                decodePass(codec, extractor);
                if (!running) {
                    break;
                }
                loopCount++;
                Log.i(TAG, "loop#" + loopCount + " seek 0 and flush");
                extractor.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC);
                codec.flush();
            }
        } catch (Exception e) {
            Log.e(TAG, "decode failed", e);
            fail(e.getClass().getSimpleName() + ": " + e.getMessage());
        } finally {
            if (codec != null) {
                try {
                    codec.stop();
                } catch (Exception ignored) {
                }
                try {
                    codec.release();
                } catch (Exception ignored) {
                }
            }
            extractor.release();
            Log.i(TAG, "decoder released, frames=" + decodedFrames + " late=" + lateFrames);
        }
    }

    private void openSource(MediaExtractor extractor) throws Exception {
        if (dataSource.startsWith("content://") || dataSource.startsWith("file://")) {
            extractor.setDataSource(context, Uri.parse(dataSource), null);
        } else {
            extractor.setDataSource(dataSource);
        }
    }

    private int selectVideoTrack(MediaExtractor extractor) {
        for (int i = 0; i < extractor.getTrackCount(); i++) {
            MediaFormat f = extractor.getTrackFormat(i);
            String mime = f.getString(MediaFormat.KEY_MIME);
            if (mime != null && mime.startsWith("video/")) {
                return i;
            }
        }
        return -1;
    }

    private void decodePass(MediaCodec codec, MediaExtractor extractor) {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        boolean inputDone = false;
        boolean outputDone = false;
        long firstPts = -1;
        long startMs = 0;

        while (running && !outputDone) {
            if (!inputDone) {
                int in = codec.dequeueInputBuffer(10000);
                if (in >= 0) {
                    ByteBuffer buf = codec.getInputBuffer(in);
                    int size = buf == null ? -1 : extractor.readSampleData(buf, 0);
                    if (size < 0) {
                        codec.queueInputBuffer(in, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                        inputDone = true;
                    } else {
                        long pts = extractor.getSampleTime();
                        codec.queueInputBuffer(in, 0, size, pts, 0);
                        extractor.advance();
                    }
                }
            }

            int out = codec.dequeueOutputBuffer(info, 10000);
            if (out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                MediaFormat f = codec.getOutputFormat();
                int w = intOf(f, "crop-width", intOf(f, MediaFormat.KEY_WIDTH, width));
                int h = intOf(f, "crop-height", intOf(f, MediaFormat.KEY_HEIGHT, height));
                if (w > 0 && h > 0 && (w != width || h != height)) {
                    width = w;
                    height = h;
                    listener.onVideoSize(this, w, h);
                }
            } else if (out == MediaCodec.INFO_TRY_AGAIN_LATER
                    || out == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
                // keep polling
            } else if (out >= 0) {
                boolean render = info.size != 0;
                if (render) {
                    if (firstPts < 0) {
                        firstPts = info.presentationTimeUs;
                        startMs = SystemClock.elapsedRealtime();
                    } else {
                        pace(info.presentationTimeUs, firstPts, startMs);
                    }
                }
                codec.releaseOutputBuffer(out, render);
                if (render) {
                    decodedFrames++;
                }
                if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    outputDone = true;
                }
            }
        }
    }

    private void pace(long ptsUs, long firstPts, long startMs) {
        long target = startMs + (ptsUs - firstPts) / 1000L;
        long now = SystemClock.elapsedRealtime();
        if (target > now + 2) {
            try {
                Thread.sleep(Math.min(target - now, 50));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        } else if (now - target > 32) {
            lateFrames++;
        }
    }

    private void fail(String message) {
        error = message;
        Log.e(TAG, "decode error: " + message);
        listener.onDecodeError(this, message);
    }

    // ------------------------------------------------------------- codec choice

    private static String pickCodec(String mime) {
        try {
            MediaCodecList list = new MediaCodecList(MediaCodecList.REGULAR_CODECS);
            MediaCodecInfo[] infos = list.getCodecInfos();
            String qti = null;
            String hw = null;
            String any = null;
            for (MediaCodecInfo ci : infos) {
                if (ci.isEncoder()) {
                    continue;
                }
                boolean supports = false;
                for (String type : ci.getSupportedTypes()) {
                    if (type.equalsIgnoreCase(mime)) {
                        supports = true;
                        break;
                    }
                }
                if (!supports) {
                    continue;
                }
                String name = ci.getName();
                if (mime.equalsIgnoreCase("video/hevc") && PREFERRED_HEVC.equals(name)) {
                    return name;
                }
                if (qti == null && name.contains("qti")) {
                    qti = name;
                }
                if (hw == null && isHardwareInfo(ci)) {
                    hw = name;
                }
                if (any == null) {
                    any = name;
                }
            }
            if (qti != null) {
                Log.i(TAG, "codec pick: qti fallback " + qti);
                return qti;
            }
            if (hw != null) {
                Log.i(TAG, "codec pick: hardware fallback " + hw);
                return hw;
            }
            Log.i(TAG, "codec pick: generic " + any);
            return any;
        } catch (Throwable t) {
            Log.w(TAG, "codec enumeration failed, using by-type", t);
            return null;
        }
    }

    private static boolean isHardwareInfo(MediaCodecInfo ci) {
        if (Build.VERSION.SDK_INT >= 29) {
            try {
                return ci.isHardwareAccelerated();
            } catch (Throwable ignored) {
            }
        }
        String n = ci.getName();
        return n.contains("qti") || n.contains(".hw.") || n.startsWith("omx.qcom")
                || n.startsWith("c2.qti");
    }

    private static boolean isHardwareCodec(String name) {
        try {
            MediaCodecList list = new MediaCodecList(MediaCodecList.REGULAR_CODECS);
            for (MediaCodecInfo ci : list.getCodecInfos()) {
                if (ci.getName().equals(name)) {
                    return isHardwareInfo(ci);
                }
            }
        } catch (Throwable ignored) {
        }
        return name.contains("qti") || name.startsWith("omx.qcom");
    }

    private static int intOf(MediaFormat f, String key, int fallback) {
        try {
            if (f.containsKey(key)) {
                return f.getInteger(key);
            }
        } catch (Throwable ignored) {
        }
        return fallback;
    }

    private static void logColor(MediaFormat f) {
        StringBuilder sb = new StringBuilder("color:");
        sb.append(" standard=").append(intOf(f, MediaFormat.KEY_COLOR_STANDARD, -1));
        sb.append(" transfer=").append(intOf(f, MediaFormat.KEY_COLOR_TRANSFER, -1));
        sb.append(" range=").append(intOf(f, MediaFormat.KEY_COLOR_RANGE, -1));
        Log.i(TAG, sb.toString());
    }
}
