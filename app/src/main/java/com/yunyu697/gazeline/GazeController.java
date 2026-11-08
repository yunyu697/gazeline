package com.yunyu697.gazeline;

/**
 * Gaze state machine from §1.2–1.7: hysteresis + debounce, 150 ms focus,
 * 250 ms arming, L1/L2/L3 dwell, angle-speed gating, cancel grace, cooldown,
 * wake bar and the recenter key's two-stage charge. It also keeps the HUD
 * anchor: the controls live at the head direction captured on open/recenter
 * and are pulled back only when the user has clearly looked away.
 */
final class GazeController {

    interface Listener {
        void onGazeFocus(HudSpec.Target target);

        void onGazeCancel(HudSpec.Target target);

        void onGazeConfirm(HudSpec.Target target);

        void onRecenterReady(HudSpec.Target target);

        void onWake();
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

    /** Which target set this controller drives: HUD row, S4 menu or exit dialog. */
    private HudSpec.Target[] targets = HudSpec.PLAYING_TARGETS;
    private HudSpec.Target wakeTarget = HudSpec.WAKE;
    private boolean anchorPull = true;
    private long protectUntil;

    GazeController(Listener listener) {
        this.listener = listener;
    }

    /**
     * Re-point the state machine at another layer. The menu and dialog are
     * anchored to the head direction captured when they open, so their targets
     * are aimable by turning the head; a purely head-locked panel would put
     * every row under the centre reticle and make selection impossible.
     */
    void setLayer(HudSpec.Target[] targets, HudSpec.Target wakeTarget, boolean anchorPull) {
        this.targets = targets;
        this.wakeTarget = wakeTarget;
        this.anchorPull = anchorPull;
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
        clearFocus();
    }

    boolean isAnchored() {
        return anchored;
    }

    void update(long now, float viewYaw, float viewPitch, float speedDegPerSec,
                boolean hudVisible, boolean enabled) {
        this.hudVisible = hudVisible;
        viewYawCache = viewYaw;
        viewPitchCache = viewPitch;
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
            setAnchor(viewYaw, viewPitch);
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

        updateAnchor(viewYaw, viewPitch, dtSec, hudVisible);

        float py = wrap(viewYaw - anchorYaw);
        float pp = viewPitch - anchorPitch;

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
        if (t == null) {
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
        if (t.id == HudSpec.Id.WAKE) {
            listener.onWake();
        } else {
            listener.onGazeConfirm(t);
        }
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
        if (hudVisible) {
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
        } else if (wakeTarget != null) {
            HudSpec.Target wake = wakeTarget;
            if (wake.hit(py, pp, ratio) && now >= cooldownUntil && wake != spent) {
                best = wake;
            }
        }
        return best;
    }

    private void updateAnchor(float viewYaw, float viewPitch, float dtSec, boolean visible) {
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
        readout.relYaw = anchored ? wrap(viewYawCache - anchorYaw) : 0f;
        readout.relPitch = anchored ? viewPitchCache - anchorPitch : 0f;
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
