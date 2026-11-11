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
    /**
     * Real heads are never perfectly still: sensor tremor kept leaving the
     * tight exit radius for a moment and re-zeroing the dwell, so 1.2 s could
     * never complete. The grace window absorbs that without making a deliberate
     * look-away feel sticky.
     */
    static final long CANCEL_GRACE_MS = 450;
    static final long CANCEL_RETRACT_MS = 150;

    static final long L1_MS = 1200;
    static final long L2_MS = 1800;
    static final long L3_MS = 2500;

    static final long COOLDOWN_MS = 500;
    static final long RECENTER_COOLDOWN_MS = 1000;

    static final long WAKE_DWELL_MS = 380;
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

    /**
     * The tuned Ø8°/Ø10° sizes are kept, but the full disc counts as inside on
     * entry and the exit ring is half-again larger, so a head that drifts a
     * degree or two cannot bounce the dwell on and off.
     */
    static final float ENTER_RATIO = 1f;
    static final float EXIT_RATIO = 1.5f;

    static final float ANCHOR_YAW_LIMIT = 55f;
    static final float ANCHOR_PITCH_UP_LIMIT = 40f;
    static final float ANCHOR_PITCH_DOWN_LIMIT = -40f;
    static final float ANCHOR_PULL_DEG_PER_S = 45f;
    static final float ANCHOR_SAFE_DEG = 12f;

    /**
     * The HUD follows the head horizontally: as soon as the gaze leaves this
     * half-width the panel re-centres in front of the face, so it is never left
     * behind at an oblique angle (which is what used to shear the bar). It is
     * wide enough to aim at the ±30° row ends and the −37° recenter key.
     */
    static final float HUD_YAW_KEEP = 44f;
    /**
     * The panel is fixed in the world vertically. It only creeps back once the
     * gaze is this far off, which is close to the edge of the eye view: inside
     * that band an up/down head movement must not move the panel at all.
     */
    static final float HUD_PITCH_KEEP = 45f;

    // Wake gesture: the controls come back when the gaze reaches the top or the
    // bottom band of the frame, so the threshold is an angle from the video
    // centre and both directions share it.
    static final float WAKE_PITCH_FAST = 20f;
    static final float WAKE_PITCH_DEFAULT = 30f;
    static final float WAKE_PITCH_DEEP = 46f;

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
        FILES,
        RECENTER,
        /** Opens the compact projection picker from the playing HUD. */
        PROJ,
        /** One row of that picker; the option itself rides in {@code index}. */
        PROJ_OPTION,
        /** The progress bar itself: gazing at it grabs and drags playback. */
        TIMELINE,
        MENU_SUBTITLE,
        MENU_AUDIO,
        MENU_SPEED,
        MENU_BRIGHT,
        MENU_AUTO_BRIGHT,
        MENU_WAKE,
        MENU_FILE,
        MENU_MORE,
        MENU_BACK,
        MENU_VOLUME,
        MENU_SERVER,
        MENU_SUB_STYLE,
        MENU_PROJ,
        MENU_PACK,
        /** Inter-lens distance of the virtual viewer; cycling it re-aligns the eyes. */
        MENU_IPD,
        MENU_EXIT,
        EXIT_CANCEL,
        EXIT_OK,
        // in-VR file browser
        BROWSER_ITEM,
        BROWSER_PREV,
        BROWSER_NEXT,
        BROWSER_MODE,
        BROWSER_CLOSE,
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
                case FILES:
                    return "文件管理";
                case RECENTER:
                    return "回中";
                case PROJ:
                    return "画面类型";
                case PROJ_OPTION:
                    return "画面类型";
                case TIMELINE:
                    return "拖动进度";
                case MENU_SUBTITLE:
                    return "字幕";
                case MENU_AUDIO:
                    return "音轨";
                case MENU_SPEED:
                    return "播放速度";
                case MENU_BRIGHT:
                    return "亮度";
                case MENU_AUTO_BRIGHT:
                    return "自动亮度";
                case MENU_WAKE:
                    return "唤回灵敏度";
                case MENU_FILE:
                    return "文件管理";
                case MENU_MORE:
                    return "更多设置";
                case MENU_BACK:
                    return "返回";
                case MENU_VOLUME:
                    return "音量";
                case MENU_SERVER:
                    return "切换服务器";
                case MENU_SUB_STYLE:
                    return "字幕样式";
                case MENU_PROJ:
                    return "投影";
                case MENU_PACK:
                    return "立体排布";
                case MENU_IPD:
                    return "瞳距";
                case BROWSER_PREV:
                    return "上一页";
                case BROWSER_NEXT:
                    return "下一页";
                case BROWSER_MODE:
                    return "投影模式";
                case BROWSER_CLOSE:
                    return "关闭";
                case MENU_EXIT:
                    return "退出播放";
                case EXIT_CANCEL:
                    return "取消";
                case EXIT_OK:
                    return "确认退出";
                case BROWSER_ITEM:
                    return "文件";
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
        /** Custom row copy for the browser; null on the fixed HUD targets. */
        final String text;
        final String detail;
        /** Browser row index (-1 elsewhere). */
        final int index;
        /** Poster frame for a browser card, decoded off the UI thread. */
        android.graphics.Bitmap thumb;

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
            this.text = null;
            this.detail = null;
            this.index = -1;
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
            this.text = null;
            this.detail = null;
            this.index = -1;
        }

        /** Browser row: dynamic copy plus its index in the page. */
        Target(Id id, float yaw, float pitch, float barW, float barH, long dwellMs,
               String text, String detail, int index) {
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
            this.text = text;
            this.detail = detail;
            this.index = index;
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
    /**
     * Direct way into the in-VR file browser: the menu still has a 文件管理
     * row, but reaching it took two pages, so one tap on the HUD is the entry.
     * Mirrors 回中 on the opposite corner, outside the main row's pitch.
     */
    static final Target FILES = new Target(Id.FILES, 37f, -25f, 7.5f, L2_MS, true, true, false);
    static final Target RECENTER = new Target(Id.RECENTER, -37f, -25f, 7.5f, L2_MS, true, true, true);
    /**
     * Projection picker: mirrored from 文件管理 across the centre, one step in
     * so it clears 回中's exit ring and the subtitle key above it.
     */
    static final Target PROJ_T = new Target(Id.PROJ, -22f, -26.5f, 7.5f, L2_MS, true, true, false);

    static final Target[] ROW = {SUBTITLE, REWIND, PLAY, FORWARD, MENU};

    /**
     * The bar is a gaze target, not a button: 5° tall is the aimable band while
     * the drawn track stays thin. Its width is the drag range: one bar width of
     * head yaw equals the whole film.
     */
    static final Target TIMELINE_T = new Target(
            Id.TIMELINE, 0f, HudSpec.BAR_PITCH, HudSpec.BAR_WIDTH, 5f, L1_MS);

    static final Target[] PLAYING_TARGETS = {
            SUBTITLE, REWIND, PLAY, FORWARD, MENU, FILES, RECENTER, PROJ_T, TIMELINE_T
    };

    // ------------------------------------------------- projection picker

    static final float PROJ_W = 58f;
    static final float PROJ_H = 58f;
    static final float PROJ_ROW_W = 52f;
    static final float PROJ_ROW_H = 5.6f;
    static final float PROJ_HEADER_PITCH = 24.4f;
    static final float PROJ_DIVIDER_PITCH = 20.9f;
    static final float PROJ_FOOTER_PITCH = -26.5f;

    private static Target projRow(float pitch, String text, String detail, int option) {
        return new Target(Id.PROJ_OPTION, 0f, pitch, PROJ_ROW_W, PROJ_ROW_H, L1_MS,
                text, detail, option);
    }

    /** index 0 = automatic; the rest match the presets in PlayerActivity. */
    static final Target[] PROJ_ROWS = {
            projRow(15.5f, "自动识别", "按文件名与画面比例判断", 0),
            projRow(9.5f, "360° 单眼", "整球画面 · 两眼同图", 1),
            projRow(3.5f, "360° 左右", "整球 · 左右并排两只眼", 2),
            projRow(-2.5f, "180° 左右", "半球 · 左右并排两只眼", 3),
            projRow(-8.5f, "180° 上下", "半球 · 上下两只眼", 4),
            projRow(-14.5f, "平面 2D", "普通视频 · 铺满前方屏幕", 5),
            projRow(-20.5f, "平面 左右", "左右两幅平面并排", 6),
    };

    static boolean inProjPanel(float yaw, float pitch) {
        return Math.abs(yaw) <= PROJ_W * 0.5f && Math.abs(pitch) <= PROJ_H * 0.5f;
    }

    // S4 rows: settings (L2) + file browser + isolated exit (L3).
    static final Target MENU_SUBTITLE_T = new Target(
            Id.MENU_SUBTITLE, 0f, 22.4f, 52f, 5.8f, L2_MS, true, true);
    static final Target MENU_AUDIO_T = new Target(
            Id.MENU_AUDIO, 0f, 16.0f, 52f, 5.8f, L2_MS, true, true);
    static final Target MENU_SPEED_T = new Target(
            Id.MENU_SPEED, 0f, 9.6f, 52f, 5.8f, L2_MS, true, true);
    static final Target MENU_BRIGHT_T = new Target(
            Id.MENU_BRIGHT, 0f, 3.2f, 52f, 5.8f, L2_MS, true, true);
    static final Target MENU_AUTO_BRIGHT_T = new Target(
            Id.MENU_AUTO_BRIGHT, 0f, -3.2f, 52f, 5.8f, L2_MS, true, true);
    static final Target MENU_WAKE_T = new Target(
            Id.MENU_WAKE, 0f, -9.6f, 52f, 5.8f, L2_MS, true, true);
    static final Target MENU_MORE_T = new Target(
            Id.MENU_MORE, 0f, -16.0f, 52f, 5.8f, L2_MS, true, true);
    /**
     * Every page ends with 返回: page 1 closes the menu (back to the picture),
     * page 2 pages back. Looking away used to be the only way out of page 1.
     */
    static final Target MENU_BACK_P1_T = new Target(
            Id.MENU_BACK, 0f, -22.4f, 52f, 5.8f, L1_MS, true, true);
    // Page 2 has nine rows, so it packs them tighter than page 1's 6.4° pitch.
    static final Target MENU_VOLUME_T = new Target(
            Id.MENU_VOLUME, 0f, 23.4f, 52f, 5.3f, L2_MS, true, true);
    static final Target MENU_PROJ_T = new Target(
            Id.MENU_PROJ, 0f, 18.0f, 52f, 5.3f, L2_MS, true, true);
    static final Target MENU_PACK_T = new Target(
            Id.MENU_PACK, 0f, 12.6f, 52f, 5.3f, L2_MS, true, true);
    static final Target MENU_IPD_T = new Target(
            Id.MENU_IPD, 0f, 7.1f, 52f, 5.3f, L2_MS, true, true);
    static final Target MENU_SERVER_T = new Target(
            Id.MENU_SERVER, 0f, 1.7f, 52f, 5.3f, L2_MS, true, true);
    static final Target MENU_SUB_STYLE_T = new Target(
            Id.MENU_SUB_STYLE, 0f, -3.7f, 52f, 5.3f, L2_MS, true, true);
    static final Target MENU_FILE_P2_T = new Target(
            Id.MENU_FILE, 0f, -9.2f, 52f, 5.3f, L2_MS, true, true);
    static final Target MENU_BACK_T = new Target(
            Id.MENU_BACK, 0f, -14.6f, 52f, 5.3f, L1_MS, true, true);
    static final Target MENU_EXIT_T = new Target(
            Id.MENU_EXIT, 0f, -20.0f, 52f, 5.3f, L3_MS, true, true);

    /** Page 1 keeps the everyday controls; 更多设置 flips to page 2. */
    static final Target[] MENU_ROWS_P1 = {
            MENU_SUBTITLE_T, MENU_AUDIO_T, MENU_SPEED_T, MENU_BRIGHT_T,
            MENU_AUTO_BRIGHT_T, MENU_WAKE_T, MENU_MORE_T, MENU_BACK_P1_T
    };
    static final Target[] MENU_ROWS_P2 = {
            MENU_VOLUME_T, MENU_PROJ_T, MENU_PACK_T, MENU_IPD_T, MENU_SERVER_T,
            MENU_SUB_STYLE_T, MENU_FILE_P2_T, MENU_BACK_T, MENU_EXIT_T
    };

    // Exit confirmation: default focus is cancel; confirming takes another L2.
    static final Target EXIT_CANCEL_T = new Target(
            Id.EXIT_CANCEL, -10.5f, -7f, 17f, 8f, L1_MS, true, true);
    static final Target EXIT_OK_T = new Target(
            Id.EXIT_OK, 10.5f, -7f, 17f, 8f, L2_MS, true, true);

    static final Target[] CONFIRM_TARGETS = {EXIT_CANCEL_T, EXIT_OK_T};

    // ------------------------------------------------------------- menu layout

    static final float MENU_W = 58f;
    static final float MENU_H = 70f;
    static final float MENU_ROW_W = 52f;
    static final float MENU_HEADER_PITCH = 29.4f;
    static final float MENU_DIVIDER_PITCH = 26.2f;
    static final float MENU_FOOTER1_PITCH = -29.6f;
    static final float MENU_FOOTER2_PITCH = -32.6f;

    static final float CONFIRM_W = 42f;
    static final float CONFIRM_H = 30f;
    static final float CONFIRM_TITLE_PITCH = 9.2f;
    static final float CONFIRM_BODY_PITCH = 3.4f;
    static final float CONFIRM_HINT_PITCH = -12.6f;

    // ------------------------------------------------------------- browser layout

    static final float BROWSER_W = 68f;
    static final float BROWSER_H = 66f;
    /** Card grid: four across, two down, so a page shows eight posters. */
    static final int BROWSER_COLS = 4;
    static final int BROWSER_ROWS = 2;
    static final int BROWSER_PAGE_SIZE = BROWSER_COLS * BROWSER_ROWS;
    static final float BROWSER_CARD_W = 16f;
    static final float BROWSER_CARD_H = 17f;
    static final float BROWSER_CARD_YAW = 17f;
    static final float[] BROWSER_CARD_PITCH = {13f, -8f};
    static final float BROWSER_HEADER_PITCH = 30f;
    static final float BROWSER_PATH_PITCH = 26.6f;
    /** One row of round icon buttons: prev · next · projection · close. */
    static final float BROWSER_FOOTER_PITCH = -25f;
    static final float BROWSER_BUTTON = 9f;
    static final float BROWSER_HINT_PITCH = -31.6f;

    static final Target BROWSER_PREV_T = new Target(
            Id.BROWSER_PREV, -25f, BROWSER_FOOTER_PITCH,
            BROWSER_BUTTON, BROWSER_BUTTON, L1_MS);
    static final Target BROWSER_NEXT_T = new Target(
            Id.BROWSER_NEXT, -9f, BROWSER_FOOTER_PITCH,
            BROWSER_BUTTON, BROWSER_BUTTON, L1_MS);
    static final Target BROWSER_CLOSE_T = new Target(
            Id.BROWSER_CLOSE, 25f, BROWSER_FOOTER_PITCH,
            BROWSER_BUTTON, BROWSER_BUTTON, L1_MS);

    static float browserCardYaw(int column) {
        return (column - (BROWSER_COLS - 1) * 0.5f) * BROWSER_CARD_YAW;
    }

    static Target browserCard(float yaw, float pitch, String text, String detail,
                              int index, android.graphics.Bitmap thumb) {
        Target t = new Target(Id.BROWSER_ITEM, yaw, pitch, BROWSER_CARD_W,
                BROWSER_CARD_H, L1_MS, text, detail, index);
        t.thumb = thumb;
        return t;
    }

    static Target browserMode(String text) {
        return new Target(Id.BROWSER_MODE, 9f, BROWSER_FOOTER_PITCH,
                BROWSER_BUTTON, BROWSER_BUTTON, L1_MS, text, null, -1);
    }

    static boolean inBrowserPanel(float yaw, float pitch) {
        return Math.abs(yaw) <= BROWSER_W * 0.5f && Math.abs(pitch) <= BROWSER_H * 0.5f;
    }

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
}
