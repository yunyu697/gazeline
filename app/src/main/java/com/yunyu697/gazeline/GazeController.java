package com.yunyu697.gazeline;

/**
 * Gaze state machine from §1.2–1.7: hysteresis + debounce, 150 ms focus,
 * 250 ms arming, L1/L2/L3 dwell, angle-speed gating, cancel grace, cooldown,
 * the downward wake gesture and the recenter key's two-stage charge. It also
 * keeps the HUD anchor: the controls live at the direction captured when the
 * panel opened and never move on their own, so a target stays exactly where
 * the head found it.
 */
final class GazeController {

    interface Listener {
        void onGazeFocus(HudSpec.Target target);

        void onGazeCancel(HudSpec.Target target);

        void onGazeConfirm(HudSpec.Target target);

        void onRecenterReady(HudSpec.Target target);

        /** @param up true when the wake came from the top band of the frame. */
        void onWake(boolean up);
    }

    static final class Readout {
        HudSpec.Target focus;
        HudSpec.Target cancelling;
        float progress;
        float cancelProgress;
        float remainingSec;
        boolean charging;
        boolean ready;
        float dwell;
        float relYaw;
        float relPitch;
        /** The layer's anchor in view space, so the GL projection can place it. */
        float anchorYaw;
        float anchorPitch;
        /** 0–1 while the wake gesture is being held; 0 otherwise. */
        float wakeProgress;
        /** Which band that progress belongs to: true for the top of the frame. */
        boolean wakeUp;
    }

    private final Listener listener;
    private final Readout readout = new Readout();

    private float anchorYaw;
    private float anchorPitch;
    private boolean anchored;

    private HudSpec.Target focus;
    private HudSpec.Target candidate;
    private long candidateSince;
    private long focusSince;
    private long chargeMs;
    private long outSince;
    private boolean charging;
    private boolean ready;
    private boolean speedPaused;
    private long lastNow;

    private HudSpec.Target cooldownTarget;
    private long cooldownUntil;

    // After an action fires, the same target stays deaf until the gaze has left
    // its exit radius, so one long stare cannot toggle play/pause on repeat.
    private HudSpec.Target spent;
    private long spentOutSince;

    private HudSpec.Target cancelling;
    private long cancelStart;
    private float cancelFrom;

    private boolean hudVisible = true;
    /** With a physical trigger there is no dwell: aiming highlights, firing acts. */
    private boolean triggerMode;

    /** Which target set this controller drives: HUD row, S4 menu or exit dialog. */
    private HudSpec.Target[] targets = HudSpec.PLAYING_TARGETS;
    private boolean anchorPull = true;
    private long protectUntil;

    /**
     * The HUD and the in-VR panels are fixed in the world: their anchor is
     * written only when the panel opens or after a recenter, never by the gaze.
     */
    private boolean yawFollow = true;

    // Wake gesture: the controls come back when the gaze enters the top or the
    // bottom band of the frame (0° pitch is the video centre after a recenter),
    // so either direction works no matter where the head was resting.
    private float wakePitchDeg = HudSpec.WAKE_PITCH_DEFAULT;
    private long wakeSince;
    private boolean wakeArmed = true;
    private float wakeProgress;
    private boolean wakeUp;
    /**
     * Low-passed control angles. Head tracking is noisy at the sub-degree
     * level; feeding the raw angles to the hit test made a held gaze flicker
     * in and out of a Ø8° disc.
     */
    private float smoothYaw;
    private float smoothPitch;
    private boolean smoothValid;

    /** What the layer is drawn at: filtered again and held under the dead zone. */
    private float shownYaw;
    private float shownPitch;
    private boolean shownValid;
    private float shownYawOut;
    private float shownPitchOut;

    GazeController(Listener listener) {
        this.listener = listener;
    }

    /**
     * Re-point the state machine at another layer. The menu and dialog keep the
     * pitch captured when they open, so their rows are aimable by nodding; the
     * horizontal anchor follows the head (see {@link #setYawFollow}).
     */
    void setLayer(HudSpec.Target[] targets, boolean anchorPull) {
        this.targets = targets;
        this.anchorPull = anchorPull;
    }

    /**
     * Calibration places its −/+ columns at ±37°, which is outside the follow
     * band, so that page opts out and keeps the classic world anchor.
     */
    void setYawFollow(boolean yawFollow) {
        this.yawFollow = yawFollow;
    }

    void setTriggerMode(boolean on) {
        triggerMode = on;
    }

    /**
     * Fires the target under the reticle. The focus still needs its short
     * debounce so a glance across the bar does not pick a neighbour, but the
     * action itself is the user's one trigger press.
     */
    boolean confirmFocused(long now) {
        if (focus == null) {
            return false;
        }
        confirm(now);
        refreshReadout(now);
        return true;
    }

    /**
     * Panels are world-fixed: they never ride along with the head, otherwise
     * the reticle can never cross to a distant card. Only a deliberate stop
     * past the edge brings the panel back in front of the face.
     */
    void setWakePitch(float deg) {
        wakePitchDeg = deg;
    }

    /** Menu protection window: no target may be acquired before it expires. */
    void protect(long now, long ms) {
        protectUntil = now + ms;
        clearFocus();
    }

    Readout readout() {
        return readout;
    }

    void setAnchor(float yaw, float pitch) {
        anchorYaw = wrap(yaw);
        anchorPitch = pitch;
        anchored = true;
        spent = null;
        spentOutSince = 0;
        // The drawn layer snaps to the new anchor instead of sliding there.
        shownValid = false;
        clearFocus();
    }

    boolean isAnchored() {
        return anchored;
    }

    void update(long now, float viewYaw, float viewPitch, float speedDegPerSec,
                boolean hudVisible, boolean enabled) {
        this.hudVisible = hudVisible;
        if (!smoothValid) {
            smoothYaw = viewYaw;
            smoothPitch = viewPitch;
            smoothValid = true;
        } else {
            smoothYaw = wrap(smoothYaw + wrap(viewYaw - smoothYaw) * SMOOTH_ALPHA);
            smoothPitch += (viewPitch - smoothPitch) * SMOOTH_ALPHA;
        }
        viewYawCache = smoothYaw;
        viewPitchCache = smoothPitch;
        if (lastNow == 0) {
            lastNow = now;
            return;
        }
        long dt = now - lastNow;
        lastNow = now;
        if (dt > 60) {
            dt = 60;
        }
        float dtSec = dt / 1000f;

        if (!anchored) {
            setAnchor(smoothYaw, smoothPitch);
        }

        if (!enabled) {
            if (focus != null) {
                cancel(now, true);
            }
            candidate = null;
            candidateSince = 0;
            refreshReadout(now);
            return;
        }

        if (speedDegPerSec > HudSpec.SPEED_CANCEL_DEG) {
            if (focus != null) {
                cancel(now, true);
            }
            candidate = null;
            candidateSince = 0;
            speedPaused = true;
        } else if (speedDegPerSec > HudSpec.SPEED_PAUSE_DEG) {
            speedPaused = true;
        } else if (speedDegPerSec < HudSpec.SPEED_RESUME_DEG) {
            speedPaused = false;
        }

        updateAnchor(smoothYaw, smoothPitch, now, dtSec, hudVisible);

        if (!hudVisible) {
            // Measured against the video centre (pitch 0 after any recenter or
            // auto-align), not against the head: both the top band and the
            // bottom band of the frame bring the controls back, and the panel
            // opens wherever the glance happened. Looking down alone was
            // unreachable after a head-down recenter.
            boolean up = smoothPitch >= wakePitchDeg;
            boolean down = smoothPitch <= -wakePitchDeg;
            if (!wakeArmed) {
                if (Math.abs(smoothPitch) < wakePitchDeg * WAKE_REARM_RATIO) {
                    wakeArmed = true;
                }
                wakeSince = 0;
                wakeProgress = 0f;
            } else if ((!up && !down) || speedPaused) {
                wakeSince = 0;
                wakeProgress = 0f;
            } else if (wakeSince == 0 || up != wakeUp) {
                wakeSince = now;
                wakeUp = up;
                wakeProgress = 0f;
            } else if (now - wakeSince >= HudSpec.WAKE_DWELL_MS) {
                wakeSince = 0;
                wakeProgress = 0f;
                wakeArmed = false;
                refreshReadout(now);
                listener.onWake(wakeUp);
                return;
            } else {
                wakeProgress = (now - wakeSince) / (float) HudSpec.WAKE_DWELL_MS;
            }
            refreshReadout(now);
            return;
        }
        wakeSince = 0;
        wakeProgress = 0f;
        wakeArmed = false;

        float py = wrap(smoothYaw - anchorYaw);
        float pp = smoothPitch - anchorPitch;

        if (spent != null) {
            if (!spent.hit(py, pp, HudSpec.EXIT_RATIO)) {
                if (spentOutSince == 0) {
                    spentOutSince = now;
                } else if (now - spentOutSince >= HudSpec.DEBOUNCE_MS) {
                    spent = null;
                    spentOutSince = 0;
                }
            } else {
                spentOutSince = 0;
            }
        }

        if (focus != null) {
            if (!focus.hit(py, pp, HudSpec.EXIT_RATIO)) {
                if (outSince == 0) {
                    outSince = now;
                } else if (now - outSince >= HudSpec.CANCEL_GRACE_MS) {
                    cancel(now, true);
                }
            } else {
                outSince = 0;
            }
            if (focus != null) {
                advance(now, dt);
            }
            refreshReadout(now);
            return;
        }

        HudSpec.Target near = nearest(py, pp, HudSpec.ENTER_RATIO, now);
        if (near == null) {
            candidate = null;
            candidateSince = 0;
        } else {
            if (candidate != near) {
                candidate = near;
                candidateSince = now;
            }
            if (speedPaused) {
                candidateSince = now;
            }
            if (now - candidateSince >= HudSpec.FOCUS_MS) {
                focus = near;
                focusSince = now;
                chargeMs = 0;
                charging = false;
                ready = false;
                outSince = 0;
                listener.onGazeFocus(focus);
            }
        }
        refreshReadout(now);
    }

    /** Physical return key / cable button: cancel the running countdown now. */
    boolean cancelByKey(long now) {
        if (focus == null) {
            return false;
        }
        cancel(now, true);
        refreshReadout(now);
        return true;
    }

    void onHudOpened(long now) {
        clearFocus();
        lastNow = now;
        cooldownTarget = null;
        cooldownUntil = 0;
        spent = null;
        spentOutSince = 0;
    }

    private void advance(long now, long dt) {
        HudSpec.Target t = focus;
        if (t == null || triggerMode) {
            return;
        }
        if (!t.arming) {
            if (!speedPaused) {
                chargeMs += dt;
            }
            if (chargeMs >= t.levelMs) {
                confirm(now);
            }
            return;
        }
        if (!charging) {
            if (speedPaused) {
                focusSince += dt;
            } else if (now - focusSince >= HudSpec.ARM_MS) {
                charging = true;
                chargeMs = 0;
            }
            return;
        }
        if (!speedPaused) {
            chargeMs += dt;
        }
        if (t.readyTwoStage && !ready && chargeMs >= HudSpec.RECENTER_CHARGE_MS) {
            ready = true;
            listener.onRecenterReady(t);
        }
        if (chargeMs >= t.levelMs) {
            confirm(now);
        }
    }

    private void confirm(long now) {
        HudSpec.Target t = focus;
        if (t == null) {
            return;
        }
        focus = null;
        candidate = null;
        candidateSince = 0;
        charging = false;
        chargeMs = 0;
        ready = false;
        outSince = 0;
        cooldownTarget = t;
        cooldownUntil = now + (t.id == HudSpec.Id.RECENTER
                ? HudSpec.RECENTER_COOLDOWN_MS : HudSpec.COOLDOWN_MS);
        spent = t;
        spentOutSince = 0;
        listener.onGazeConfirm(t);
    }

    private void cancel(long now, boolean audible) {
        HudSpec.Target t = focus;
        if (t == null) {
            return;
        }
        if (audible && charging && chargeMs > 0) {
            listener.onGazeCancel(t);
        }
        if (chargeMs > 0) {
            cancelling = t;
            cancelStart = now;
            cancelFrom = t.levelMs > 0 ? (float) chargeMs / t.levelMs : 0f;
        }
        focus = null;
        candidate = null;
        candidateSince = 0;
        charging = false;
        chargeMs = 0;
        ready = false;
        outSince = 0;
    }

    private void clearFocus() {
        focus = null;
        candidate = null;
        candidateSince = 0;
        charging = false;
        chargeMs = 0;
        ready = false;
        outSince = 0;
    }

    private HudSpec.Target nearest(float py, float pp, float ratio, long now) {
        if (now < protectUntil) {
            return null;
        }
        HudSpec.Target best = null;
        float bestDist = Float.MAX_VALUE;
        for (HudSpec.Target t : targets) {
            if (t == cooldownTarget && now < cooldownUntil) {
                continue;
            }
            if (t == spent) {
                continue;
            }
            if (!t.hit(py, pp, ratio)) {
                continue;
            }
            float d = t.distance(py, pp);
            if (d < bestDist) {
                bestDist = d;
                best = t;
            }
        }
        return best;
    }

    private void updateAnchor(float viewYaw, float viewPitch, long now, float dtSec,
                              boolean visible) {
        if (yawFollow) {
            // Nothing here: a panel that moves while the head turns can never
            // be aimed at. It stays exactly where it opened until setAnchor().
            return;
        }
        if (!anchorPull) {
            return;
        }
        if (!visible || speedPaused) {
            return;
        }
        float relYaw = wrap(viewYaw - anchorYaw);
        float relPitch = viewPitch - anchorPitch;
        boolean out = relYaw > HudSpec.ANCHOR_YAW_LIMIT
                || relYaw < -HudSpec.ANCHOR_YAW_LIMIT
                || relPitch > HudSpec.ANCHOR_PITCH_UP_LIMIT
                || relPitch < HudSpec.ANCHOR_PITCH_DOWN_LIMIT;
        if (!out) {
            return;
        }
        if (nearAnyTarget(relYaw, relPitch)) {
            return;
        }
        float step = HudSpec.ANCHOR_PULL_DEG_PER_S * dtSec;
        if (relYaw > HudSpec.ANCHOR_YAW_LIMIT) {
            anchorYaw = wrap(anchorYaw + Math.min(step, relYaw - HudSpec.ANCHOR_YAW_LIMIT));
        } else if (relYaw < -HudSpec.ANCHOR_YAW_LIMIT) {
            anchorYaw = wrap(anchorYaw - Math.min(step, -HudSpec.ANCHOR_YAW_LIMIT - relYaw));
        }
        if (relPitch > HudSpec.ANCHOR_PITCH_UP_LIMIT) {
            anchorPitch += Math.min(step, relPitch - HudSpec.ANCHOR_PITCH_UP_LIMIT);
        } else if (relPitch < HudSpec.ANCHOR_PITCH_DOWN_LIMIT) {
            anchorPitch -= Math.min(step, -HudSpec.ANCHOR_PITCH_DOWN_LIMIT - relPitch);
        }
    }

    private boolean nearAnyTarget(float py, float pp) {
        for (HudSpec.Target t : HudSpec.PLAYING_TARGETS) {
            if (t.near(py, pp, HudSpec.ANCHOR_SAFE_DEG)) {
                return true;
            }
        }
        return false;
    }

    private void refreshReadout(long now) {
        // Drawing angles are filtered harder than the hit test: a worn phone
        // jitters by a few tenths of a degree, which made the whole HUD shimmer.
        // The hit test keeps smoothYaw/smoothPitch so dwell timing is unaffected.
        float rawYaw = anchored ? wrap(viewYawCache - anchorYaw) : 0f;
        float rawPitch = anchored ? viewPitchCache - anchorPitch : 0f;
        if (!shownValid) {
            shownYaw = rawYaw;
            shownPitch = rawPitch;
            shownValid = true;
        } else {
            shownYaw = wrap(shownYaw + wrap(rawYaw - shownYaw) * SHOWN_ALPHA);
            shownPitch += (rawPitch - shownPitch) * SHOWN_ALPHA;
        }
        readout.relYaw = deaden(shownYaw, shownYawOut);
        readout.relPitch = deaden(shownPitch, shownPitchOut);
        readout.anchorYaw = anchorYaw;
        readout.anchorPitch = anchorPitch;
        shownYawOut = readout.relYaw;
        shownPitchOut = readout.relPitch;
        readout.wakeProgress = wakeProgress;
        readout.wakeUp = wakeUp;
        readout.focus = focus;
        readout.charging = charging;
        readout.ready = ready;
        readout.progress = 0f;
        readout.remainingSec = 0f;
        readout.dwell = 0f;
        if (focus != null) {
            if (focus.levelMs > 0) {
                readout.progress = Math.min(1f, (float) chargeMs / focus.levelMs);
                readout.remainingSec = Math.max(0f, (focus.levelMs - chargeMs) / 1000f);
            }
            readout.dwell = focus.levelMs > 0
                    ? Math.min(1f, (float) chargeMs / focus.levelMs) : 0f;
        }
        if (cancelling != null) {
            long since = now - cancelStart;
            if (since >= HudSpec.CANCEL_RETRACT_MS) {
                cancelling = null;
                readout.cancelling = null;
                readout.cancelProgress = 0f;
            } else {
                readout.cancelling = cancelling;
                readout.cancelProgress = cancelFrom
                        * (1f - since / (float) HudSpec.CANCEL_RETRACT_MS);
            }
        } else {
            readout.cancelling = null;
            readout.cancelProgress = 0f;
        }
    }

    private float viewYawCache;
    private float viewPitchCache;

    /** Per-frame blend for the control angles (~5 frames to settle). */
    private static final float SMOOTH_ALPHA = 0.45f;
    /** Softer blend for the drawn angles: the HUD must look nailed in place. */
    private static final float SHOWN_ALPHA = 0.35f;
    /** Changes smaller than this are ignored, so sensor tremor never shows. */
    private static final float SHOWN_DEAD_DEG = 0.25f;

    private static float deaden(float value, float last) {
        return Math.abs(value - last) < SHOWN_DEAD_DEG ? last : value;
    }
    /** The gaze must come back this far inside a band before it can fire again. */
    private static final float WAKE_REARM_RATIO = 0.6f;

    static float wrap(float deg) {
        while (deg > 180f) {
            deg -= 360f;
        }
        while (deg < -180f) {
            deg += 360f;
        }
        return deg;
    }
}
