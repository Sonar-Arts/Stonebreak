package com.stonebreak.battle.camera;

import com.stonebreak.ui.startupIntro.tween.EasingType;

/**
 * One authored camera move. Pure data; {@link ShotEvaluator} turns it into a {@link CameraFrame}.
 *
 * <p>STATIC / DOLLY / TRACK interpolate eye and target linearly (TRACK is a dolly whose anchors
 * follow a dashing actor). ORBIT / CRANE interpolate the eye in cylindrical coordinates around the
 * target, so the path is an arc rather than a chord: the bearing runs from the start eye's bearing
 * through {@code sweepDeg} (or, when that is NaN, along the shortest arc to the end eye's bearing),
 * while radius and height run from the start eye's to the end eye's.
 *
 * @param sweepDeg         ORBIT/CRANE only: signed degrees about +Y (positive = counter-clockwise seen
 *                         from above); NaN = shortest arc to {@code eyeTo}
 * @param duration         seconds for one pass
 * @param end              what happens past {@code duration}
 * @param promptSafe       near-static and frames the prompt's target: may be live while a ring/parry prompt is open
 * @param crossesLine      explicitly allowed on the far side of the monk–Archon line (180° rule exemption)
 * @param timeScale        battle time scale requested while this shot is young (slow motion when &lt; 1)
 * @param timeScaleSeconds how long from the shot's start {@code timeScale} applies
 * @param subject          who must be in frame (checked by the library validation test); BOTH tags a
 *                         two-shot, MONK / ARCHON a single-subject shot, and the composition rules differ
 * @param staging          which actor may be away from its home ring while this shot is live; the
 *                         validator re-checks the shot with that actor half-way and fully advanced.
 *                         Defaults to whoever the anchors follow
 * @param shakeAtSeconds   seconds into the shot at which it adds {@code shakeTrauma} to the shake (a
 *                         beat the battle raises no event for, e.g. a body hitting the floor); negative = none
 * @param panDeg           yaw added to the view direction after the move is evaluated: positive looks
 *                         left, so the target slides screen-right by a constant amount whatever the orbit
 *                         is doing (keeps a subject out from under a centred HUD panel)
 * @param establishing     a deliberate sweep / pull-back whose subject is allowed to be small: exempt
 *                         from the composition rules (never from framing or occlusion)
 */
public record CameraShot(String name, Motion motion,
                         AnchoredPoint eyeFrom, AnchoredPoint eyeTo,
                         AnchoredPoint targetFrom, AnchoredPoint targetTo,
                         float sweepDeg, float fovFrom, float fovTo, float rollFrom, float rollTo,
                         float duration, EasingType easing, End end,
                         boolean promptSafe, boolean crossesLine,
                         float timeScale, float timeScaleSeconds, Subject subject,
                         Staging staging, boolean establishing, float shakeAtSeconds, float shakeTrauma,
                         float panDeg) {

    public enum Motion { STATIC, DOLLY, ORBIT, TRACK, CRANE;
        public boolean cylindrical() { return this == ORBIT || this == CRANE; }
    }

    /** Behaviour once {@code duration} has elapsed and nothing has advanced the sequence. */
    public enum End { HOLD, LOOP, PING_PONG }

    public enum Subject { NONE, MONK, ARCHON, BOTH }

    /**
     * Where the actors may be while a shot is on screen. A reaction shot does not follow the attacker,
     * yet the attacker is standing right next to its victim when the shot cuts in: occlusion and
     * framing have to be judged against that, not against two actors on their home rings.
     */
    public enum Staging {
        /** Both actors on their home rings. */
        HOME,
        /** The monk is anywhere between its ring and the strike anchor (run-in, blow, run back). */
        MONK_ADVANCES,
        /** The Archon is anywhere between its ring and the strike anchor. */
        ARCHON_ADVANCES,
        /**
         * The monk is at the strike anchor whenever this shot is on screen (timing rings, the combo
         * string). Judged there only: it may run in past the camera and out again.
         */
        MONK_ENGAGED,
        /**
         * An impact shot: the attacker stands at the strike anchor while the shot makes its move and
         * only travels home once it has settled. The move is judged with the attacker engaged, the
         * end frame with the attacker anywhere on its way back, so the shot may open tight on the blow
         * as long as it has pulled back by the time the attacker leaves.
         */
        MONK_STRIKES,
        ARCHON_STRIKES
    }

    public static final float BASE_FOV = 70f;
    public static final float MIN_FOV = 30f;
    public static final float MAX_FOV = 90f;

    /** True when any anchor tracks a live actor pose. */
    public boolean followsPose() {
        return eyeFrom.followPose() || eyeTo.followPose() || targetFrom.followPose() || targetTo.followPose();
    }

    /** True when any anchor tracks this particular anchor's live pose (MIDPOINT follows both actors). */
    public boolean follows(CameraAnchor actor) {
        for (AnchoredPoint p : new AnchoredPoint[]{eyeFrom, eyeTo, targetFrom, targetTo}) {
            if (p.followPose() && (p.anchor() == actor || p.anchor() == CameraAnchor.MIDPOINT)) return true;
        }
        return false;
    }

    public static Builder shot(String name, Motion motion) {
        return new Builder(name, motion);
    }

    /** Fluent authoring helper so {@link ShotLibrary} reads like a shot list. */
    public static final class Builder {
        private final String name;
        private final Motion motion;
        private AnchoredPoint eyeFrom, eyeTo, targetFrom, targetTo;
        private float sweepDeg = Float.NaN;
        private float fovFrom = BASE_FOV, fovTo = BASE_FOV, rollFrom, rollTo;
        private float duration = 1f;
        private EasingType easing = EasingType.EaseInOutQuad;
        private End end = End.HOLD;
        private boolean promptSafe, crossesLine;
        private float timeScale = 1f, timeScaleSeconds;
        private Subject subject = Subject.NONE;
        private Staging staging;
        private boolean establishing;
        private float shakeAtSeconds = -1f, shakeTrauma;
        private float panDeg;

        private Builder(String name, Motion motion) {
            this.name = name;
            this.motion = motion;
        }

        public Builder eye(AnchoredPoint at) { return eye(at, at); }
        public Builder eye(AnchoredPoint from, AnchoredPoint to) { eyeFrom = from; eyeTo = to; return this; }
        public Builder target(AnchoredPoint at) { return target(at, at); }
        public Builder target(AnchoredPoint from, AnchoredPoint to) { targetFrom = from; targetTo = to; return this; }
        public Builder sweep(float degrees) { sweepDeg = degrees; return this; }
        public Builder fov(float fov) { return fov(fov, fov); }
        public Builder fov(float from, float to) { fovFrom = from; fovTo = to; return this; }
        public Builder roll(float from, float to) { rollFrom = from; rollTo = to; return this; }
        public Builder duration(float seconds) { duration = seconds; return this; }
        public Builder easing(EasingType type) { easing = type; return this; }
        public Builder end(End behaviour) { end = behaviour; return this; }
        public Builder promptSafe() { promptSafe = true; return this; }
        public Builder crossesLine() { crossesLine = true; return this; }
        public Builder slowMotion(float scale, float seconds) { timeScale = scale; timeScaleSeconds = seconds; return this; }
        public Builder subject(Subject who) { subject = who; return this; }
        public Builder during(Staging where) { staging = where; return this; }
        public Builder establishing() { establishing = true; return this; }
        public Builder pan(float degrees) { panDeg = degrees; return this; }
        public Builder shakeBeat(float atSeconds, float trauma) { shakeAtSeconds = atSeconds; shakeTrauma = trauma; return this; }

        private boolean follows(CameraAnchor actor) {
            for (AnchoredPoint p : new AnchoredPoint[]{eyeFrom, eyeTo, targetFrom, targetTo}) {
                if (p != null && p.followPose() && (p.anchor() == actor || p.anchor() == CameraAnchor.MIDPOINT)) return true;
            }
            return false;
        }

        public CameraShot build() {
            if (eyeFrom == null || targetFrom == null) {
                throw new IllegalStateException("shot " + name + " needs an eye and a target");
            }
            Staging where = staging;
            if (where == null) { // not stated: whoever the anchors follow is the one on the move
                boolean monk = follows(CameraAnchor.MONK);
                boolean archon = follows(CameraAnchor.ARCHON);
                where = monk ? Staging.MONK_ADVANCES : archon ? Staging.ARCHON_ADVANCES : Staging.HOME;
            }
            return new CameraShot(name, motion, eyeFrom, eyeTo, targetFrom, targetTo, sweepDeg, fovFrom, fovTo,
                    rollFrom, rollTo, duration, easing, end, promptSafe, crossesLine, timeScale, timeScaleSeconds, subject,
                    where, establishing, shakeAtSeconds, shakeTrauma, panDeg);
        }
    }
}
