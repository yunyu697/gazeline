package com.yunyu697.gazeline;

import android.content.Context;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.os.Handler;
import android.os.Looper;

/**
 * Audio channel for the gaze layer (§1.9): focus tick, rising confirm pair,
 * falling cancel, recenter "ready" tick. Media volume, never the ring volume.
 */
final class Feedback {

    private final Handler handler = new Handler(Looper.getMainLooper());
    private ToneGenerator tone;

    Feedback(Context context) {
        try {
            tone = new ToneGenerator(AudioManager.STREAM_MUSIC, 40);
        } catch (Throwable t) {
            tone = null;
        }
    }

    void focus() {
        play(ToneGenerator.TONE_PROP_BEEP, 45);
    }

    void confirm() {
        play(ToneGenerator.TONE_DTMF_5, 55);
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                play(ToneGenerator.TONE_DTMF_8, 75);
            }
        }, 90);
    }

    void cancel() {
        play(ToneGenerator.TONE_PROP_NACK, 90);
    }

    void ready() {
        play(ToneGenerator.TONE_DTMF_7, 60);
    }

    void wake() {
        play(ToneGenerator.TONE_PROP_BEEP, 40);
    }

    /** S4 open/close: a single short tone stands in for the spec's whoosh. */
    void menuOpen() {
        play(ToneGenerator.TONE_PROP_BEEP2, 55);
    }

    void menuClose() {
        play(ToneGenerator.TONE_PROP_NACK, 45);
    }

    void release() {
        handler.removeCallbacksAndMessages(null);
        if (tone != null) {
            tone.release();
            tone = null;
        }
    }

    private void play(int type, int durationMs) {
        if (tone == null) {
            return;
        }
        try {
            tone.startTone(type, durationMs);
        } catch (Throwable ignored) {
        }
    }
}
