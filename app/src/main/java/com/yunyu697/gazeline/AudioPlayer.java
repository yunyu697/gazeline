package com.yunyu697.gazeline;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;
import android.os.SystemClock;
import android.util.Log;

import java.nio.ByteBuffer;

/**
 * Audio decode loop. Decodes the Nth audio track of the same source the video
 * decoder opened and writes PCM into a streaming AudioTrack. While enabled it
 * is the playback master clock: {@link #clockUs()} exposes the media time the
 * AudioTrack has actually played, and the video thread paces frames against it.
 * Files without an audio track stay silent and produce no error.
 *
 * Speed changes are a placeholder: at any rate other than 1.0x the audio is
 * muted (the deck keeps decoding video on the wall clock only).
 */
final class AudioPlayer implements Runnable {

    private static final String TAG = "GazelineAudio";

    interface Listener {
        void onAudioInfo(AudioPlayer source, int trackCount, int trackIndex,
                         String codecName, int sampleRate, int channelCount);

        void onAudioError(AudioPlayer source, String message);
    }

    private final Context context;
    private final String dataSource;
    private final int wantedTrack;
    private final long initialSeekUs;
    private final Listener listener;

    private volatile boolean running = true;
    private volatile boolean wantPlaying = true;
    private volatile boolean muted;
    private volatile long seekRequestUs = -1;
    private Thread thread;

    private volatile int trackCount;
    private volatile int trackIndex;
    private volatile String codecName = "n/a";
    private volatile String mimeType = "n/a";
    private volatile int sampleRate;
    private volatile int channelCount;
    private volatile int pcmEncoding = AudioFormat.ENCODING_PCM_16BIT;
    private volatile String summary = "无音轨";
    private volatile boolean hasTrack;
    private volatile String error;

    // Clock: mediaBaseUs is the PTS of the first buffer written after a flush;
    // headBaseFrames is the playback head at that moment (normally 0 because
    // flush resets the head on API 26+).
    private volatile long mediaBaseUs = -1;
    private volatile long headBaseFrames;
    private volatile long lastPtsUs;
    private volatile AudioTrack audioTrack;
    private volatile long loops;

    AudioPlayer(Context context, String dataSource, int trackIndex,
                long initialSeekUs, Listener listener) {
        this.context = context.getApplicationContext();
        this.dataSource = dataSource;
        this.wantedTrack = trackIndex;
        this.initialSeekUs = Math.max(0L, initialSeekUs);
        this.listener = listener;
    }

    void start() {
        if (thread != null) {
            return;
        }
        thread = new Thread(this, "gazeline-audio");
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

    void pause() {
        wantPlaying = false;
        pauseTrack();
    }

    void resume() {
        wantPlaying = true;
    }

    /** Playback rate; only 1.0x has sound for now, others mute the track. */
    void setSpeed(float rate) {
        muted = rate < 0.99f || rate > 1.01f;
        if (muted) {
            pauseTrack();
        }
    }

    boolean isMuted() {
        return muted;
    }

    boolean isPlaying() {
        return wantPlaying;
    }

    /** Seek by wall-clock milliseconds; the extractor clamps to sync samples. */
    void seekTo(long ms) {
        seekRequestUs = Math.max(0L, ms) * 1000L;
    }

    int trackCount() {
        return trackCount;
    }

    int trackIndex() {
        return trackIndex;
    }

    boolean hasTrack() {
        return hasTrack;
    }

    String summary() {
        return summary;
    }

    String error() {
        return error;
    }

    /**
     * The media position the AudioTrack has actually played, or -1 when the
     * audio layer is not a usable clock (no track, paused, muted, initialising).
     */
    long clockUs() {
        AudioTrack t = audioTrack;
        long base = mediaBaseUs;
        if (!hasTrack || muted || !wantPlaying || t == null || base < 0 || sampleRate <= 0) {
            return -1L;
        }
        long head;
        try {
            head = t.getPlaybackHeadPosition();
        } catch (Throwable e) {
            return -1L;
        }
        long frames = head - headBaseFrames;
        if (frames < 0) {
            frames = 0;
        }
        return base + frames * 1000000L / sampleRate;
    }

    @Override
    public void run() {
        MediaExtractor extractor = new MediaExtractor();
        MediaCodec codec = null;
        try {
            openSource(extractor);
            trackCount = countAudioTracks(extractor);
            if (trackCount <= 0) {
                hasTrack = false;
                summary = "无音轨";
                Log.i(TAG, "source has no audio track, staying silent");
                return;
            }
            trackIndex = Math.max(0, wantedTrack) % trackCount;
            int track = selectAudioTrack(extractor, trackIndex);
            extractor.selectTrack(track);
            MediaFormat format = extractor.getTrackFormat(track);
            mimeType = format.getString(MediaFormat.KEY_MIME);
            sampleRate = intOf(format, MediaFormat.KEY_SAMPLE_RATE, 44100);
            channelCount = intOf(format, MediaFormat.KEY_CHANNEL_COUNT, 2);
            pcmEncoding = intOf(format, MediaFormat.KEY_PCM_ENCODING,
                    AudioFormat.ENCODING_PCM_16BIT);
            summary = shortMime(mimeType) + " · " + (sampleRate / 1000) + " kHz · "
                    + (channelCount > 1 ? "立体声" : "单声道");

            codec = MediaCodec.createDecoderByType(mimeType);
            codec.configure(format, null, null, 0);
            codec.start();
            codecName = codec.getName();
            hasTrack = true;
            ensureTrack(sampleRate, channelCount, pcmEncoding);
            if (initialSeekUs > 0) {
                extractor.seekTo(initialSeekUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC);
            }
            listener.onAudioInfo(this, trackCount, trackIndex, codecName, sampleRate, channelCount);
            Log.i(TAG, "audio codec=" + codecName + " mime=" + mimeType + " " + sampleRate
                    + "Hz ch=" + channelCount + " pcm=" + pcmEncoding
                    + " track=" + trackIndex + "/" + trackCount);

            decodeLoop(codec, extractor);
        } catch (Throwable t) {
            Log.e(TAG, "audio failed", t);
            fail(t.getClass().getSimpleName() + ": " + t.getMessage());
        } finally {
            releaseCodec(codec);
            releaseTrack();
            extractor.release();
            Log.i(TAG, "audio released, loops=" + loops + " error=" + error);
        }
    }

    private void decodeLoop(MediaCodec codec, MediaExtractor extractor) throws Exception {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        boolean inputDone = false;
        boolean outputDone = false;

        while (running) {
            long seekUs = seekRequestUs;
            if (seekUs >= 0) {
                seekRequestUs = -1;
                flushAudio();
                extractor.seekTo(seekUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC);
                codec.flush();
                mediaBaseUs = -1;
                lastPtsUs = 0;
                inputDone = false;
                outputDone = false;
                Log.i(TAG, "audio seek " + seekUs / 1000L + "ms");
                continue;
            }
            if (!wantPlaying || muted) {
                pauseTrack();
                sleepQuiet(10);
                continue;
            }
            ensurePlaying();

            if (!inputDone) {
                int in = codec.dequeueInputBuffer(10000);
                if (in >= 0) {
                    ByteBuffer buf = codec.getInputBuffer(in);
                    int size = buf == null ? -1 : extractor.readSampleData(buf, 0);
                    if (size < 0) {
                        codec.queueInputBuffer(in, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                        inputDone = true;
                    } else {
                        codec.queueInputBuffer(in, 0, size, extractor.getSampleTime(), 0);
                        extractor.advance();
                    }
                }
            }

            int out = codec.dequeueOutputBuffer(info, 10000);
            if (out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                MediaFormat f = codec.getOutputFormat();
                int rate = intOf(f, MediaFormat.KEY_SAMPLE_RATE, sampleRate);
                int ch = intOf(f, MediaFormat.KEY_CHANNEL_COUNT, channelCount);
                int enc = intOf(f, MediaFormat.KEY_PCM_ENCODING, pcmEncoding);
                if (rate != sampleRate || ch != channelCount || enc != pcmEncoding) {
                    sampleRate = rate;
                    channelCount = ch;
                    pcmEncoding = enc;
                    try {
                        ensureTrack(rate, ch, enc);
                    } catch (Throwable t) {
                        Log.w(TAG, "AudioTrack rebuild failed, keeping old", t);
                    }
                }
            } else if (out == MediaCodec.INFO_TRY_AGAIN_LATER
                    || out == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
                // keep polling
            } else if (out >= 0) {
                if (info.size > 0) {
                    ByteBuffer buf = codec.getOutputBuffer(out);
                    if (buf != null) {
                        buf.position(info.offset);
                        buf.limit(info.offset + info.size);
                        if (mediaBaseUs < 0) {
                            mediaBaseUs = info.presentationTimeUs;
                            headBaseFrames = 0;
                        }
                        lastPtsUs = info.presentationTimeUs;
                        writePcm(buf, info.size);
                    }
                }
                codec.releaseOutputBuffer(out, false);
                if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    outputDone = true;
                }
            }

            if (outputDone && running) {
                drainTail();
                if (!running) {
                    break;
                }
                loops++;
                Log.i(TAG, "audio loop#" + loops);
                flushAudio();
                extractor.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC);
                codec.flush();
                mediaBaseUs = -1;
                lastPtsUs = 0;
                inputDone = false;
                outputDone = false;
            }
        }
    }

    /** Writes a decoded PCM chunk; non-blocking so pause/seek stay responsive. */
    private void writePcm(ByteBuffer buf, int size) throws Exception {
        AudioTrack track = audioTrack;
        if (track == null) {
            return;
        }
        int written = 0;
        while (written < size && running && wantPlaying && !muted) {
            int n;
            try {
                n = track.write(buf, size - written, AudioTrack.WRITE_NON_BLOCKING);
            } catch (Throwable t) {
                fail("AudioTrack.write: " + t.getMessage());
                return;
            }
            if (n > 0) {
                written += n;
            } else if (n < 0) {
                fail("AudioTrack.write error " + n);
                return;
            } else {
                sleepQuiet(8);
            }
        }
    }

    /** Lets the last queued samples actually play before looping. */
    private void drainTail() {
        long deadline = SystemClock.elapsedRealtime() + 900;
        while (running && wantPlaying && !muted) {
            long now = clockUs();
            if (now < 0 || now >= lastPtsUs) {
                break;
            }
            if (SystemClock.elapsedRealtime() > deadline) {
                break;
            }
            sleepQuiet(10);
        }
    }

    private void ensureTrack(int rate, int channels, int encoding) throws Exception {
        int chMask = channels >= 2
                ? AudioFormat.CHANNEL_OUT_STEREO : AudioFormat.CHANNEL_OUT_MONO;
        if (channels > 2) {
            Log.w(TAG, "channelCount=" + channels + " not supported, decoding as stereo");
            channels = 2;
        }
        int minBuf = AudioTrack.getMinBufferSize(rate, chMask, encoding);
        int bufferBytes = minBuf > 0 ? minBuf * 2 : 65536;

        releaseTrack();
        AudioAttributes attrs = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                .build();
        AudioFormat format = new AudioFormat.Builder()
                .setEncoding(encoding)
                .setSampleRate(rate)
                .setChannelMask(chMask)
                .build();
        AudioTrack track = new AudioTrack.Builder()
                .setAudioAttributes(attrs)
                .setAudioFormat(format)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(bufferBytes)
                .build();
        if (track.getState() != AudioTrack.STATE_INITIALIZED) {
            track.release();
            throw new IllegalStateException("AudioTrack init failed");
        }
        audioTrack = track;
        channelCount = channels;
        if (wantPlaying && !muted) {
            track.play();
        }
        mediaBaseUs = -1;
        headBaseFrames = 0;
        Log.i(TAG, "AudioTrack " + rate + "Hz ch=" + channels + " enc=" + encoding
                + " buf=" + bufferBytes);
    }

    private void ensurePlaying() {
        AudioTrack track = audioTrack;
        if (track == null) {
            return;
        }
        try {
            if (track.getPlayState() != AudioTrack.PLAYSTATE_PLAYING) {
                track.play();
            }
        } catch (Throwable ignored) {
        }
    }

    private void pauseTrack() {
        AudioTrack track = audioTrack;
        if (track == null) {
            return;
        }
        try {
            if (track.getPlayState() == AudioTrack.PLAYSTATE_PLAYING) {
                track.pause();
            }
        } catch (Throwable ignored) {
        }
    }

    private void flushAudio() {
        AudioTrack track = audioTrack;
        if (track != null) {
            try {
                if (track.getPlayState() == AudioTrack.PLAYSTATE_PLAYING) {
                    track.pause();
                }
                track.flush();
                if (wantPlaying && !muted) {
                    track.play();
                }
            } catch (Throwable t) {
                Log.w(TAG, "audio flush: " + t.getMessage());
            }
        }
        mediaBaseUs = -1;
        headBaseFrames = 0;
    }

    private void releaseTrack() {
        AudioTrack track = audioTrack;
        audioTrack = null;
        if (track != null) {
            try {
                track.pause();
            } catch (Throwable ignored) {
            }
            try {
                track.flush();
            } catch (Throwable ignored) {
            }
            try {
                track.release();
            } catch (Throwable ignored) {
            }
        }
    }

    private static void releaseCodec(MediaCodec codec) {
        if (codec == null) {
            return;
        }
        try {
            codec.stop();
        } catch (Throwable ignored) {
        }
        try {
            codec.release();
        } catch (Throwable ignored) {
        }
    }

    private void openSource(MediaExtractor extractor) throws Exception {
        if (dataSource.startsWith("content://") || dataSource.startsWith("file://")) {
            extractor.setDataSource(context, Uri.parse(dataSource), null);
        } else {
            extractor.setDataSource(dataSource);
        }
    }

    private static int countAudioTracks(MediaExtractor extractor) {
        int count = 0;
        for (int i = 0; i < extractor.getTrackCount(); i++) {
            MediaFormat f = extractor.getTrackFormat(i);
            String mime = f.getString(MediaFormat.KEY_MIME);
            if (mime != null && mime.startsWith("audio/")) {
                count++;
            }
        }
        return count;
    }

    private static int selectAudioTrack(MediaExtractor extractor, int index) {
        int seen = -1;
        for (int i = 0; i < extractor.getTrackCount(); i++) {
            MediaFormat f = extractor.getTrackFormat(i);
            String mime = f.getString(MediaFormat.KEY_MIME);
            if (mime != null && mime.startsWith("audio/")) {
                seen++;
                if (seen == index) {
                    return i;
                }
            }
        }
        return -1;
    }

    private void fail(String message) {
        error = message;
        hasTrack = false;
        Log.e(TAG, "audio error: " + message);
        if (listener != null) {
            listener.onAudioError(this, message);
        }
    }

    private static String shortMime(String mime) {
        if (mime == null) {
            return "?";
        }
        if (mime.startsWith("audio/")) {
            return mime.substring(6).toUpperCase();
        }
        return mime.toUpperCase();
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

    private static void sleepQuiet(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
