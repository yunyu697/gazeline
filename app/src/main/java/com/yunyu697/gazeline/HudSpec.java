package com.yunyu697.gazeline;

/**
 * Canonical angle table from the approved spec (§1.11) plus the timings from
 * §1.2–1.9. Everything the HUD draws and the gaze engine hits is derived from
 * here; pixel sizes are computed at draw time from the eye viewport (90°
 * horizontal field of view, tangent mapping).
 */
final class HudSpec {

    private HudSpec() {
    }

    // ------------------------------------------------------------- timing

    static final long DEBOUNCE_MS = 120;
    static final long FOCUS_MS = 150;
    static final long ARM_MS = 250;
    static final long CANCEL_GRACE_MS = 250;
    static final long CANCEL_RETRACT_MS = 150;

    static final long L1_MS = 1200;
    static final long L2_MS = 1800;
    static final long L3_MS = 2500;

    static final long COOLDOWN_MS = 500;
    static final long RECENTER_COOLDOWN_MS = 1000;

    static final long WAKE_DWELL_MS = 800;
    static final long RECENTER_CHARGE_MS = 1200;
    static final long RECENTER_READY_MS = 600;

    // S4 menu / exit confirmation (§1.5, §2 S4)
    static final long MENU_PROTECT_MS = 400;
    static final long MENU_AUTO_CLOSE_MS = 30000;
    static final long MENU_CLOSE_WARN_MS = 3000;
    static final long MENU_LEAVE_CLOSE_MS = 1200;
    static final long CONFIRM_PROTECT_MS = 400;

    static final long IDLE_START_MS = 5000;
    static final long IDLE_FADE_MS = 1000;
    static final long NEW_VIDEO_ALWAYS_MS = 15000;

    static final float SPEED_PAUSE_DEG = 10f;
    static final float SPEED_RESUME_DEG = 6f;
    static final float SPEED_CANCEL_DEG = 25f;

    static final float ENTER_RATIO = 0.85f;
    static final float EXIT_RATIO = 1.15f;

    static final float ANCHOR_YAW_LIMIT = 55f;
    static final float ANCHOR_PITCH_UP_LIMIT = 35f;
    static final float ANCHOR_PITCH_DOWN_LIMIT = -50f;
    static final float ANCHOR_PULL_DEG_PER_S = 30f;
    static final float ANCHOR_SAFE_DEG = 12f;

    // ------------------------------------------------------------- colors

    static final int C_AMBER = 0xFFFFB454;
    static final int C_TEXT = 0xFFE9EEF9;
    static final int C_TEXT2 = 0xFFA7B2C7;
    static final int C_TEXT3 = 0xFF6F7A91;
    static final int C_BLUE = 0xFF6C8CFF;
    static final int C_GREEN = 0xFF43D9A0;
    static final int C_RED = 0xFFFF8B8B;
    static final int C_BUTTON_FILL = 0x8C0A0E14;
    static final int C_BUTTON_BORDER = 0x2BFFFFFF;
    static final int C_RING_TRACK = 0x21FFFFFF;

    // ------------------------------------------------------------- targets

    enum Id {
        PLAY,
        REWIND,
        FORWARD,
        SUBTITLE,
        MENU,
        RECENTER,
        WAKE,
        MENU_SUBTITLE,
        MENU_AUDIO,
        MENU_SPEED,
        MENU_EXIT,
        EXIT_CANCEL,
        EXIT_OK,
        // S5 calibration page: four step groups, two actions, eye mask, self-test
        CAL_IPD_MINUS,
        CAL_IPD_PLUS,
        CAL_K1_MINUS,
        CAL_K1_PLUS,
        CAL_K2_MINUS,
        CAL_K2_PLUS,
        CAL_VERT_MINUS,
        CAL_VERT_PLUS,
        CAL_RESET,
        CAL_SAVE,
        CAL_UNLOCK,
        EYE_LEFT,
        EYE_BOTH,
        EYE_RIGHT,
        TEST_CLEAR,
        TEST_BLUR;

        String label() {
            switch (this) {
                case PLAY:
                    return "暂停";
                case REWIND:
                    return "快退 10 秒";
                case FORWARD:
                    return "快进 10 秒";
                case SUBTITLE:
                    return "字幕";
                case MENU:
                    return "打开菜单";
                case RECENTER:
                    return "回中";
                case MENU_SUBTITLE:
                    return "字幕";
                case MENU_AUDIO:
                    return "音轨";
                case MENU_SPEED:
                    return "播放速度";
                case MENU_EXIT:
                    return "退出播放";
                case EXIT_CANCEL:
                    return "取消";
                case EXIT_OK:
                    return "确认退出";
                case WAKE:
                default:
                    return "唤回控件";
            }
        }
    }

    static final class Target {
        final Id id;
        final float yaw;
        final float pitch;
        /** Angular diameter for round targets; 0 for the wake bar. */
        final float diameterDeg;
        /** Wake bar angular extents (width x height). */
        final float barW;
        final float barH;
        final long levelMs;
        final boolean bar;
        final boolean arming;
        final boolean ring;
        final boolean readyTwoStage;

        Target(Id id, float yaw, float pitch, float diameterDeg, long levelMs,
               boolean arming, boolean ring, boolean readyTwoStage) {
            this.id = id;
            this.yaw = yaw;
            this.pitch = pitch;
            this.diameterDeg = diameterDeg;
            this.barW = 0f;
            this.barH = 0f;
            this.levelMs = levelMs;
            this.bar = false;
            this.arming = arming;
            this.ring = ring;
            this.readyTwoStage = readyTwoStage;
        }

        Target(Id id, float yaw, float pitch, float barW, float barH, long dwellMs) {
            this(id, yaw, pitch, barW, barH, dwellMs, false, false);
        }

        Target(Id id, float yaw, float pitch, float barW, float barH, long dwellMs,
               boolean arming, boolean ring) {
            this.id = id;
            this.yaw = yaw;
            this.pitch = pitch;
            this.diameterDeg = 0f;
            this.barW = barW;
            this.barH = barH;
            this.levelMs = dwellMs;
            this.bar = true;
            this.arming = arming;
            this.ring = ring;
            this.readyTwoStage = false;
        }

        float distance(float py, float pp) {
            if (bar) {
                float dx = Math.max(0f, Math.abs(py - yaw) - barW * 0.5f);
                float dy = Math.max(0f, Math.abs(pp - pitch) - barH * 0.5f);
                return (float) Math.sqrt(dx * dx + dy * dy);
            }
            float dx = py - yaw;
            float dy = pp - pitch;
            return (float) Math.sqrt(dx * dx + dy * dy);
        }

        boolean hit(float py, float pp, float ratio) {
            if (bar) {
                return Math.abs(py - yaw) <= barW * 0.5f * ratio
                        && Math.abs(pp - pitch) <= barH * 0.5f * ratio;
            }
            return distance(py, pp) <= diameterDeg * 0.5f * ratio;
        }

        boolean near(float py, float pp, float deg) {
            return distance(py, pp) <= deg;
        }
    }

    static final Target PLAY = new Target(Id.PLAY, 0f, -18f, 10f, L1_MS, true, true, false);
    static final Target REWIND = new Target(Id.REWIND, -16f, -18f, 8f, L1_MS, true, true, false);
    static final Target FORWARD = new Target(Id.FORWARD, 16f, -18f, 8f, L1_MS, true, true, false);
    static final Target SUBTITLE = new Target(Id.SUBTITLE, -30f, -18f, 8f, L2_MS, true, true, false);
    static final Target MENU = new Target(Id.MENU, 30f, -18f, 8f, L2_MS, true, true, false);
    static final Target RECENTER = new Target(Id.RECENTER, -37f, -25f, 7.5f, L2_MS, true, true, true);
    static final Target WAKE = new Target(Id.WAKE, 0f, -39f, 25f, 3f, WAKE_DWELL_MS);

    static final Target[] ROW = {SUBTITLE, REWIND, PLAY, FORWARD, MENU};

    static final Target[] PLAYING_TARGETS = {SUBTITLE, REWIND, PLAY, FORWARD, MENU, RECENTER};

    // S4 rows: 3 settings (L2) + isolated exit (L3). Pitches sum to the 57° panel.
    static final Target MENU_SUBTITLE_T = new Target(
            Id.MENU_SUBTITLE, 0f, 14.3f, 52f, 8f, L2_MS, true, true);
    static final Target MENU_AUDIO_T = new Target(
            Id.MENU_AUDIO, 0f, 5.1f, 52f, 8f, L2_MS, true, true);
    static final Target MENU_SPEED_T = new Target(
            Id.MENU_SPEED, 0f, -4.1f, 52f, 8f, L2_MS, true, true);
    static final Target MENU_EXIT_T = new Target(
            Id.MENU_EXIT, 0f, -13.7f, 52f, 9f, L3_MS, true, true);

    static final Target[] MENU_ROWS = {
            MENU_SUBTITLE_T, MENU_AUDIO_T, MENU_SPEED_T, MENU_EXIT_T
    };

    // Exit confirmation: default focus is cancel; confirming takes another L2.
    static final Target EXIT_CANCEL_T = new Target(
            Id.EXIT_CANCEL, -10.5f, -7f, 17f, 8f, L1_MS, true, true);
    static final Target EXIT_OK_T = new Target(
            Id.EXIT_OK, 10.5f, -7f, 17f, 8f, L2_MS, true, true);

    static final Target[] CONFIRM_TARGETS = {EXIT_CANCEL_T, EXIT_OK_T};

    // ------------------------------------------------------------- menu layout

    static final float MENU_W = 56f;
    static final float MENU_H = 57f;
    static final float MENU_ROW_W = 52f;
    static final float MENU_HEADER_PITCH = 25.2f;
    static final float MENU_DIVIDER_PITCH = 21.8f;
    static final float MENU_FOOTER1_PITCH = -22.0f;
    static final float MENU_FOOTER2_PITCH = -25.0f;

    static final float CONFIRM_W = 42f;
    static final float CONFIRM_H = 30f;
    static final float CONFIRM_TITLE_PITCH = 9.2f;
    static final float CONFIRM_BODY_PITCH = 3.4f;
    static final float CONFIRM_HINT_PITCH = -12.6f;

    static boolean inMenuPanel(float yaw, float pitch) {
        return Math.abs(yaw) <= MENU_W * 0.5f && Math.abs(pitch) <= MENU_H * 0.5f;
    }

    static boolean inConfirmPanel(float yaw, float pitch) {
        return Math.abs(yaw) <= CONFIRM_W * 0.5f && Math.abs(pitch) <= CONFIRM_H * 0.5f;
    }

    // ------------------------------------------------------------- read-only layout

    static final float TOP_PITCH = 39f;
    static final float TOP_WIDTH = 40f;
    static final float TOP_HEIGHT = 4f;
    static final float SUB_PITCH = -33f;
    static final float SUB_WIDTH = 75f;
    static final float SUB_HEIGHT = 3f;
    static final float BAR_PITCH = -11f;
    static final float BAR_WIDTH = 75f;
    static final float WAKE_PITCH = -39f;
    static final float WAKE_WIDTH = 25f;
    static final float WAKE_HEIGHT = 3f;
}
