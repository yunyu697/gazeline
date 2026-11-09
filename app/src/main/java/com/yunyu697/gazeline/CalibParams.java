package com.yunyu697.gazeline;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * §1.12 calibration values. They are edited inside the viewer (CalibActivity)
 * but persisted only when the user saves, so a cancelled session leaves the
 * last good values untouched.
 */
final class CalibParams {

    private static final String PREFS = "gazeline_calib";
    private static final String KEY_SAVED = "saved";
    private static final String KEY_MODEL = "model";
    private static final String KEY_IPD = "ipd";
    private static final String KEY_K1 = "k1";
    private static final String KEY_K2 = "k2";
    private static final String KEY_VL = "vertLeft";
    private static final String KEY_VR = "vertRight";
    private static final String KEY_SCREEN = "screenInch";

    static final int MODEL_V2 = 0;
    static final int MODEL_V3 = 1;
    static final int MODEL_GENERIC = 2;

    static final float IPD_MIN = 50f;
    static final float IPD_MAX = 75f;
    static final float IPD_STEP = 0.5f;
    static final float IPD_DEFAULT = 63f;

    static final float K1_MIN = -0.60f;
    static final float K1_MAX = 0f;
    static final float K1_STEP = 0.02f;

    static final float K2_MIN = -0.30f;
    static final float K2_MAX = 0f;
    static final float K2_STEP = 0.01f;

    static final float VERT_MIN = -2f;
    static final float VERT_MAX = 2f;
    static final float VERT_STEP = 0.5f;

    /** The calibration card and the SBS window offset are defined at 2.5 m. */
    static final float SCREEN_DISTANCE_MM = 2500f;

    int model = MODEL_GENERIC;
    float ipdMm = IPD_DEFAULT;
    float k1 = -0.20f;
    float k2 = 0f;
    float vertLeftDeg = 0f;
    float vertRightDeg = 0f;
    float screenInch = 6.3f;

    private CalibParams() {
    }

    static CalibParams defaults() {
        CalibParams p = new CalibParams();
        p.resetToModel(MODEL_GENERIC);
        return p;
    }

    static CalibParams load(Context context) {
        SharedPreferences sp = prefs(context);
        if (!sp.getBoolean(KEY_SAVED, false)) {
            return defaults();
        }
        CalibParams p = new CalibParams();
        p.model = sp.getInt(KEY_MODEL, MODEL_GENERIC);
        p.ipdMm = sp.getFloat(KEY_IPD, IPD_DEFAULT);
        p.k1 = sp.getFloat(KEY_K1, modelK1(p.model));
        p.k2 = sp.getFloat(KEY_K2, modelK2(p.model));
        p.vertLeftDeg = sp.getFloat(KEY_VL, 0f);
        p.vertRightDeg = sp.getFloat(KEY_VR, 0f);
        p.screenInch = sp.getFloat(KEY_SCREEN, 6.3f);
        p.clamp();
        return p;
    }

    static boolean hasSaved(Context context) {
        return prefs(context).getBoolean(KEY_SAVED, false);
    }

    void save(Context context) {
        clamp();
        prefs(context).edit()
                .putBoolean(KEY_SAVED, true)
                .putInt(KEY_MODEL, model)
                .putFloat(KEY_IPD, ipdMm)
                .putFloat(KEY_K1, k1)
                .putFloat(KEY_K2, k2)
                .putFloat(KEY_VL, vertLeftDeg)
                .putFloat(KEY_VR, vertRightDeg)
                .putFloat(KEY_SCREEN, screenInch)
                .apply();
    }

    CalibParams copy() {
        CalibParams p = new CalibParams();
        p.model = model;
        p.ipdMm = ipdMm;
        p.k1 = k1;
        p.k2 = k2;
        p.vertLeftDeg = vertLeftDeg;
        p.vertRightDeg = vertRightDeg;
        p.screenInch = screenInch;
        return p;
    }

    void copyFrom(CalibParams o) {
        model = o.model;
        ipdMm = o.ipdMm;
        k1 = o.k1;
        k2 = o.k2;
        vertLeftDeg = o.vertLeftDeg;
        vertRightDeg = o.vertRightDeg;
        screenInch = o.screenInch;
    }

    /** A model change resets the lens coefficients to that model's preset. */
    void resetToModel(int model) {
        this.model = model;
        k1 = modelK1(model);
        k2 = modelK2(model);
    }

    void stepIpd(int dir) {
        ipdMm = clamp(roundTo(ipdMm + dir * IPD_STEP, 2), IPD_MIN, IPD_MAX);
    }

    void stepK1(int dir) {
        k1 = clamp(roundTo(k1 + dir * K1_STEP, 3), K1_MIN, K1_MAX);
    }

    void stepK2(int dir) {
        k2 = clamp(roundTo(k2 + dir * K2_STEP, 3), K2_MIN, K2_MAX);
    }

    /** Adds one step to one eye; the caller decides which eye is in scope. */
    void stepVert(boolean left, int dir) {
        if (left) {
            vertLeftDeg = clamp(roundTo(vertLeftDeg + dir * VERT_STEP, 2), VERT_MIN, VERT_MAX);
        } else {
            vertRightDeg = clamp(roundTo(vertRightDeg + dir * VERT_STEP, 2), VERT_MIN, VERT_MAX);
        }
    }

    void stepVertBoth(int dir) {
        vertLeftDeg = clamp(roundTo(vertLeftDeg + dir * VERT_STEP, 2), VERT_MIN, VERT_MAX);
        vertRightDeg = clamp(roundTo(vertRightDeg + dir * VERT_STEP, 2), VERT_MIN, VERT_MAX);
    }

    void clamp() {
        model = Math.max(MODEL_V2, Math.min(MODEL_GENERIC, model));
        ipdMm = clamp(roundTo(ipdMm, 2), IPD_MIN, IPD_MAX);
        k1 = clamp(roundTo(k1, 3), K1_MIN, K1_MAX);
        k2 = clamp(roundTo(k2, 3), K2_MIN, K2_MAX);
        vertLeftDeg = clamp(roundTo(vertLeftDeg, 2), VERT_MIN, VERT_MAX);
        vertRightDeg = clamp(roundTo(vertRightDeg, 2), VERT_MIN, VERT_MAX);
        if (screenInch <= 0f || screenInch > 12f) {
            screenInch = 6.3f;
        }
    }

    static float modelK1(int model) {
        switch (model) {
            case MODEL_V2:
                return -0.30f;
            case MODEL_V3:
                return -0.22f;
            default:
                return -0.20f;
        }
    }

    static float modelK2(int model) {
        switch (model) {
            case MODEL_V2:
                return -0.03f;
            case MODEL_V3:
                return -0.01f;
            default:
                return 0f;
        }
    }

    static String modelName(int model) {
        switch (model) {
            case MODEL_V2:
                return "Cardboard v2";
            case MODEL_V3:
                return "Cardboard v3";
            default:
                return "通用（未校准型号）";
        }
    }

    String modelName() {
        return modelName(model);
    }

    /**
     * Half of the angular eye separation for the IPD guide lines seen through
     * the card at 2.5 m. 63 mm → 0.72°, so both lines fuse when the IPD is right.
     */
    float halfIpdDeg() {
        return (float) Math.toDegrees(Math.atan((ipdMm * 0.5f) / SCREEN_DISTANCE_MM));
    }

    /**
     * SBS window offset: only the deviation from the nominal 63 mm pair is
     * compensated, so an untouched install keeps the author's stereo layout.
     */
    float sbsWindowOffsetDeg() {
        return (float) Math.toDegrees(
                Math.atan(((ipdMm - IPD_DEFAULT) * 0.5f) / SCREEN_DISTANCE_MM));
    }

    String ipdText() {
        return String.format(java.util.Locale.US, "%.1f mm", ipdMm);
    }

    String k1Text() {
        return String.format(java.util.Locale.US, "%.2f", k1);
    }

    String k2Text() {
        return String.format(java.util.Locale.US, "%.2f", k2);
    }

    String vertText() {
        return String.format(java.util.Locale.US, "L %+.1f° / R %+.1f°", vertLeftDeg, vertRightDeg);
    }

    private static float roundTo(float v, int degrade) {
        if (degrade <= 0) {
            return v;
        }
        double f = Math.pow(10, degrade);
        double scaled = Math.round(v * f) / f;
        return (float) scaled;
    }

    private static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
