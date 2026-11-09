package com.yunyu697.gazeline;

/**
 * Geometry of the in-viewer calibration page (§1.12). The test card occupies
 * the central 60°×60°; the four −/+ groups sit in two columns outside the card
 * so nothing covers the grid; the eye capsules and the two actions use the
 * remaining top and bottom bands. Drawing and hit-testing both read this file.
 */
final class CalibSpec {

    private CalibSpec() {
    }

    static final float CARD_HALF = 30f;

    static final float COL_MINUS = -37f;
    static final float COL_PLUS = 37f;
    static final float ROW_IPD = 15f;
    static final float ROW_K1 = 4f;
    static final float ROW_K2 = -7f;
    static final float ROW_VERT = -18f;

    static final float LABEL_MINUS = -31.2f;
    static final float LABEL_PLUS = 31.2f;
    static final float VALUE_OFFSET = -6.4f;

    static final float BUTTON_DEG = 8f;

    static final float CAPSULE_PITCH = 36.5f;
    static final float CAPSULE_YAW = 14f;
    static final float CAPSULE_HOT_DEG = 7.5f;

    static final float ACTION_PITCH = -36f;
    static final float ACTION_YAW = 18f;

    /** Transient line inside the card, above the action row. */
    static final float HINT_PITCH = -27f;

    static final long STEP_LEVEL_MS = HudSpec.L1_MS;
    static final long ACTION_LEVEL_MS = HudSpec.L2_MS;

    static final int ROW_IPD_INDEX = 0;
    static final int ROW_K1_INDEX = 1;
    static final int ROW_K2_INDEX = 2;
    static final int ROW_VERT_INDEX = 3;

    private static HudSpec.Target step(HudSpec.Id id, float yaw, float pitch) {
        return new HudSpec.Target(id, yaw, pitch, BUTTON_DEG, STEP_LEVEL_MS, true, true, false);
    }

    private static HudSpec.Target action(HudSpec.Id id, float yaw, float pitch) {
        return new HudSpec.Target(id, yaw, pitch, BUTTON_DEG, ACTION_LEVEL_MS, true, true, false);
    }

    static final HudSpec.Target IPD_MINUS = step(HudSpec.Id.CAL_IPD_MINUS, COL_MINUS, ROW_IPD);
    static final HudSpec.Target IPD_PLUS = step(HudSpec.Id.CAL_IPD_PLUS, COL_PLUS, ROW_IPD);
    static final HudSpec.Target K1_MINUS = step(HudSpec.Id.CAL_K1_MINUS, COL_MINUS, ROW_K1);
    static final HudSpec.Target K1_PLUS = step(HudSpec.Id.CAL_K1_PLUS, COL_PLUS, ROW_K1);
    static final HudSpec.Target K2_MINUS = step(HudSpec.Id.CAL_K2_MINUS, COL_MINUS, ROW_K2);
    static final HudSpec.Target K2_PLUS = step(HudSpec.Id.CAL_K2_PLUS, COL_PLUS, ROW_K2);
    static final HudSpec.Target VERT_MINUS = step(HudSpec.Id.CAL_VERT_MINUS, COL_MINUS, ROW_VERT);
    static final HudSpec.Target VERT_PLUS = step(HudSpec.Id.CAL_VERT_PLUS, COL_PLUS, ROW_VERT);

    static final HudSpec.Target RESET = action(HudSpec.Id.CAL_RESET, -ACTION_YAW, ACTION_PITCH);
    static final HudSpec.Target SAVE = action(HudSpec.Id.CAL_SAVE, ACTION_YAW, ACTION_PITCH);
    static final HudSpec.Target UNLOCK = action(HudSpec.Id.CAL_UNLOCK, 0f, ACTION_PITCH);

    static final HudSpec.Target EYE_LEFT = new HudSpec.Target(
            HudSpec.Id.EYE_LEFT, -CAPSULE_YAW, CAPSULE_PITCH, CAPSULE_HOT_DEG,
            HudSpec.L1_MS, true, false, false);
    static final HudSpec.Target EYE_BOTH = new HudSpec.Target(
            HudSpec.Id.EYE_BOTH, 0f, CAPSULE_PITCH, CAPSULE_HOT_DEG,
            HudSpec.L1_MS, true, false, false);
    static final HudSpec.Target EYE_RIGHT = new HudSpec.Target(
            HudSpec.Id.EYE_RIGHT, CAPSULE_YAW, CAPSULE_PITCH, CAPSULE_HOT_DEG,
            HudSpec.L1_MS, true, false, false);

    static final HudSpec.Target TEST_CLEAR = action(HudSpec.Id.TEST_CLEAR, ACTION_YAW, ACTION_PITCH);
    static final HudSpec.Target TEST_BLUR = action(HudSpec.Id.TEST_BLUR, -ACTION_YAW, ACTION_PITCH);

    static final HudSpec.Target[] STEPS = {
            IPD_MINUS, IPD_PLUS, K1_MINUS, K1_PLUS, K2_MINUS, K2_PLUS, VERT_MINUS, VERT_PLUS
    };

    static final HudSpec.Target[] EYES = {EYE_LEFT, EYE_BOTH, EYE_RIGHT};

    static final HudSpec.Target[] ADJUST_FULL = {
            IPD_MINUS, IPD_PLUS, K1_MINUS, K1_PLUS, K2_MINUS, K2_PLUS, VERT_MINUS, VERT_PLUS,
            RESET, SAVE, EYE_LEFT, EYE_BOTH, EYE_RIGHT
    };

    static final HudSpec.Target[] ADJUST_FOLDED = {
            IPD_MINUS, IPD_PLUS, VERT_MINUS, VERT_PLUS,
            RESET, SAVE, UNLOCK, EYE_LEFT, EYE_BOTH, EYE_RIGHT
    };

    static final HudSpec.Target[] SELF_TEST = {TEST_CLEAR, TEST_BLUR};

    static int rowOf(HudSpec.Id id) {
        switch (id) {
            case CAL_IPD_MINUS:
            case CAL_IPD_PLUS:
                return ROW_IPD_INDEX;
            case CAL_K1_MINUS:
            case CAL_K1_PLUS:
                return ROW_K1_INDEX;
            case CAL_K2_MINUS:
            case CAL_K2_PLUS:
                return ROW_K2_INDEX;
            case CAL_VERT_MINUS:
            case CAL_VERT_PLUS:
                return ROW_VERT_INDEX;
            default:
                return -1;
        }
    }

    static boolean isStep(HudSpec.Id id) {
        return rowOf(id) >= 0;
    }

    static boolean isEye(HudSpec.Id id) {
        return id == HudSpec.Id.EYE_LEFT || id == HudSpec.Id.EYE_BOTH || id == HudSpec.Id.EYE_RIGHT;
    }
}
