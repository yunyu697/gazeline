package com.yunyu697.gazeline;

/**
 * Gaze state machine from §1.2–1.7: hysteresis + debounce, 150 ms focus,
 * 250 ms arming, L1/L2/L3 dwell, angle-speed gating, cancel grace, cooldown,
 * the downward wake gesture and the recenter key's two-stage charge. It also
 * keeps the HUD anchor: the controls live at the pitch captured on open and
 * follow the head horizontally, so the panel is always in front of the face
 * instead of being left behind at an oblique angle.
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
        /** 0–1 while the wake gesture is being held; 0 otherwise. */
        float wakeProgress;
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
    private boolean anchorPull = true;
    private long protectUntil;

    /** Horizontal follow: the panel re-centres once the gaze leaves its band. */
    private boolean yawFollow = true;

    // Downward wake gesture (head-relative, so it is always reachable).
    private float wakePitchDeg = HudSpec.WAKE_PITCH_DEFAULT;
    private long wakeSince;
    private boolean wakeArmed = true;
    private float wakeProgress;

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

        if (!hudVisible) {
            // The wake gesture is measured against the head, not against a world
            // anchor: glancing down far enough always works, even after an
            // hour of looking around. Once armed it stays armed until the gaze
            // climbs back out of the band.
            if (!wakeArmed) {
                if (viewPitch > -wakePitchDeg * 0.75f) {
                    wakeArmed = true;
                }
                wakeSince = 0;
                wakeProgress = 0f;
            } else if (viewPitch > -wakePitchDeg || speedPaused) {
                wakeSince = 0;
                wakeProgress = 0f;
            } else if (wakeSince == 0) {
                wakeSince = now;
                wakeProgress = 0f;
            } else if (now - wakeSince >= HudSpec.WAKE_DWELL_MS) {
                wakeSince = 0;
                wakeProgress = 0f;
                wakeArmed = false;
                refreshReadout(now);
                listener.onWake();
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

    private void updateAnchor(float viewYaw, float viewPitch, float dtSec, boolean visible) {
        if (!anchorPull) {
            return;
        }
        if (!visible || speedPaused) {
            return;
        }
        if (yawFollow) {
            // The panel is a heads-up layer: it sits at a fixed angular offset
            // from the gaze and only snaps back when the gaze leaves its band.
            // Snapping drops the focus so the jump cannot hand a dwell to
            // whatever lands under the reticle; the band is wide enough that
            // every button can still be aimed at without triggering it.
            float relYaw = wrap(viewYaw - anchorYaw);
            float relPitch = viewPitch - anchorPitch;
            boolean jumpYaw = relYaw > HudSpec.HUD_YAW_KEEP || relYaw < -HudSpec.HUD_YAW_KEEP;
            boolean jumpPitch = relPitch > HudSpec.ANCHOR_PITCH_UP_LIMIT
                    || relPitch < HudSpec.ANCHOR_PITCH_DOWN_LIMIT;
            if (jumpYaw || jumpPitch) {
                if (jumpYaw) {
                    anchorYaw = wrap(viewYaw);
                }
                if (jumpPitch) {
                    anchorPitch = viewPitch;
                }
                clearFocus();
            }
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
        readout.wakeProgress = wakeProgress;
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
