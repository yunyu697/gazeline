package com.yunyu697.gazeline;

import android.app.Activity;
import android.util.Log;
import android.view.Display;
import android.view.WindowManager;

import java.util.Locale;

/**
 * Prefers a 72 Hz display mode (90 Hz as fallback) without changing the
 * resolution, per the POC baseline. Phones that do not expose those modes keep
 * whatever the system picked; the chosen/actual rate is logged for the report.
 */
final class DisplayModes {

    private static final String TAG = "GazelineDisplay";

    private DisplayModes() {
    }

    static String request(Activity activity) {
        Display display = activity.getWindowManager().getDefaultDisplay();
        Display.Mode current = display.getMode();
        Display.Mode[] modes = display.getSupportedModes();
        Display.Mode best = null;
        for (float target : new float[]{72f, 90f}) {
            best = findAt(modes, target, current);
            if (best != null) {
                break;
            }
        }
        if (best == null) {
            for (Display.Mode m : modes) {
                if (Math.abs(m.getRefreshRate() - 72f) < 1.1f) {
                    best = m;
                    break;
                }
            }
        }
        if (best == null) {
            Log.i(TAG, "no 72/90Hz mode exposed, keep "
                    + current.getRefreshRate() + "Hz " + current.getPhysicalWidth()
                    + "x" + current.getPhysicalHeight());
            return String.format(Locale.US, "%.0f Hz · 本机未暴露 72/90 Hz",
                    current.getRefreshRate());
        }
        try {
            WindowManager.LayoutParams params = activity.getWindow().getAttributes();
            params.preferredDisplayModeId = best.getModeId();
            activity.getWindow().setAttributes(params);
            Log.i(TAG, "requested mode#" + best.getModeId() + " "
                    + best.getPhysicalWidth() + "x" + best.getPhysicalHeight()
                    + " @" + best.getRefreshRate() + "Hz (was " + current.getRefreshRate() + ")");
            return String.format(Locale.US, "%.0f Hz 已请求", best.getRefreshRate());
        } catch (Throwable t) {
            Log.w(TAG, "setPreferredDisplayModeId failed: " + t.getMessage());
            return String.format(Locale.US, "%.0f Hz · 请求失败", best.getRefreshRate());
        }
    }

    private static Display.Mode findAt(Display.Mode[] modes, float target,
                                       Display.Mode current) {
        Display.Mode loose = null;
        for (Display.Mode m : modes) {
            if (Math.abs(m.getRefreshRate() - target) >= 1.1f) {
                continue;
            }
            if (m.getPhysicalWidth() == current.getPhysicalWidth()
                    && m.getPhysicalHeight() == current.getPhysicalHeight()) {
                return m;
            }
            if (loose == null) {
                loose = m;
            }
        }
        return loose;
    }
}
