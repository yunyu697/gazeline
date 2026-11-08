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
        WAKE;

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
            this.id = id;
            this.yaw = yaw;
            this.pitch = pitch;
            this.diameterDeg = 0f;
            this.barW = barW;
            this.barH = barH;
            this.levelMs = dwellMs;
            this.bar = true;
            this.arming = false;
            this.ring = false;
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
