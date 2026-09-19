package com.stonebreak.battle.camera;

import com.stonebreak.battle.api.BattleStageLayout;
import com.stonebreak.battle.api.CombatantId;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Checks shots against a stage: arena bounds, floor, obstacle boxes, eye→target line of sight, and
 * (for the authoring test) the 180° rule, subject framing (in frustum, not hidden by an obstacle or
 * by the other actor) and composition (how large the subjects read on screen). The director uses
 * {@link #usable} to reject a blocked variant before it ever reaches the screen.
 *
 * <p>Actors are vertical cylinders at their <em>posed</em> feet: a reaction shot is judged with the
 * attacker standing next to its victim, which is where it is when the shot cuts in.
 */
public final class ShotValidator {

    /** One failed sample. */
    public record Issue(String shot, String poses, float time, String problem) {
        @Override public String toString() {
            return shot + " @" + String.format("%.2f", time) + "s [" + poses + "]: " + problem;
        }
    }

    public static final float FLOOR_CLEARANCE = 0.3f;
    public static final float OBSTACLE_MARGIN = 0.2f;
    /** Path samples per shot and pose set. */
    public static final int SAMPLES = 13;
    /** Framing is checked at the baseline FOV (or the shot's own, when narrower) and 16:9. */
    private static final float FRAMING_ASPECT = 16f / 9f;
    private static final float FRAMING_NDC_LIMIT = 0.98f;
    private static final float CHEST = 0.6f;
    private static final float HEAD = 0.92f;
    private static final float HIPS = 0.45f;

    /**
     * Occluder half-width of the monk / the Archon across its facing (shoulders and pauldrons, not the
     * sword's reach), and half-depth along it. The footprint is an ellipse, not a circle: a striking
     * monk stands 0.78 blocks from the Archon's centre, inside a 0.9 circle but well in front of its
     * chest (rig-measured torso front: 0.21–0.29 from its centre), and a circle would call him
     * "hidden" from every angle.
     */
    public static final float MONK_RADIUS = 0.35f;
    public static final float ARCHON_RADIUS = 0.9f;
    public static final float MONK_DEPTH = 0.25f;
    public static final float ARCHON_DEPTH = 0.45f;
    /** The eye keeps this far outside an actor's cylinder: closer and the near plane slices the model. */
    public static final float ACTOR_EYE_MARGIN = 0.25f;

    /** Two-shot: the taller actor fills at least this fraction of the frame height... */
    public static final float TWO_SHOT_MIN_TALLER = 0.22f;
    /** ...and the other one is never a speck. */
    public static final float TWO_SHOT_MIN_OTHER = 0.12f;
    /** Single-subject shot: the subject's feet-to-head span as a fraction of the frame height. */
    public static final float SINGLE_MIN = 0.30f;
    public static final float SINGLE_MAX = 0.95f;
    /**
     * The battle HUD (help strip, command menu, party window) owns the frame below this NDC height
     * (65 % of the way down at the 1080p HUD scale) while the command menu is up, and its bottom-right
     * corner always. Feet may stand behind it; a subject's hips may not. Shots that hold while the menu
     * is open are held to a stricter line by the library test.
     */
    public static final float HUD_LINE_NDC = -0.30f;
    /** A single-subject shot's other actor counts as foreground when this much nearer (ground distance). */
    public static final float FOREGROUND_RATIO = 0.85f;
    /**
     * Share of a full orbit's samples in which one actor may pass in front of the other. Generous
     * because the orbit that needs it circles two actors standing chest to chest: the Archon's
     * shoulders cover the monk for the whole arc behind its back, not for an instant.
     */
    public static final float MOMENTARY_OCCLUSION_FRACTION = 0.4f;

    private final BattleStageLayout layout;
    private final Map<ShotSequence, Boolean> usableCache = new HashMap<>();

    public ShotValidator(BattleStageLayout layout) {
        this.layout = layout;
    }

    /** True when every shot of the sequence keeps a clear, in-bounds eye along its whole path. Cached. */
    public boolean usable(ShotSequence sequence) {
        return usableCache.computeIfAbsent(sequence, s -> {
            for (ShotSequence.Step step : s.steps()) {
                if (!check(step.shot(), false).isEmpty()) return false;
            }
            return true;
        });
    }

    /**
     * Samples the shot over its duration for the home poses and, when it follows an actor, for that
     * actor half-way and fully through its dash.
     *
     * @param authoringRules also check the 180° rule, FOV range and subject framing
     */
    public List<Issue> check(CameraShot shot, boolean authoringRules) {
        List<Issue> issues = new ArrayList<>();
        // An orbit of half a turn or more round two actors must cross their line: for that instant one
        // stands in front of the other. Tolerated while it stays an instant.
        boolean passesThroughTheLine = shot.motion().cylindrical() && Math.abs(shot.sweepDeg()) >= 180f;
        for (Map.Entry<String, StagePoses> poses : poseSets(shot).entrySet()) {
            List<Issue> momentary = new ArrayList<>();
            for (int i = 0; i < SAMPLES; i++) {
                if (i < SAMPLES - 1 && attackerStillEngaged(shot, poses.getValue())) continue;
                float time = shot.duration() * i / (SAMPLES - 1);
                CameraFrame frame = ShotEvaluator.evaluate(shot, time, layout, poses.getValue());
                String problem = problemAt(shot, frame, poses.getValue(), authoringRules, true);
                if (problem == null) continue;
                Issue issue = new Issue(shot.name(), poses.getKey(), time, problem);
                if (passesThroughTheLine && problemAt(shot, frame, poses.getValue(), authoringRules, false) == null) {
                    momentary.add(issue);
                } else {
                    issues.add(issue);
                }
            }
            if (momentary.size() > SAMPLES * MOMENTARY_OCCLUSION_FRACTION) issues.addAll(momentary);
        }
        return issues;
    }

    /** Impact shots ({@code *_STRIKES}): before the end frame the attacker cannot have left the strike anchor yet. */
    private static boolean attackerStillEngaged(CameraShot shot, StagePoses poses) {
        return switch (shot.staging()) {
            case MONK_STRIKES -> poses.monk().dashProgress() < 1f;
            case ARCHON_STRIKES -> poses.archon().dashProgress() < 1f;
            default -> false;
        };
    }

    /** Pose sets a shot must survive, from its {@link CameraShot.Staging}. */
    public static Map<String, StagePoses> poseSets(CameraShot shot) {
        Map<String, StagePoses> sets = new java.util.LinkedHashMap<>();
        switch (shot.staging()) {
            case HOME -> sets.put("home", StagePoses.HOME);
            case MONK_ADVANCES, MONK_STRIKES -> {
                sets.put("home", StagePoses.HOME);
                sets.put("monk dash 0.5", StagePoses.dashing(CombatantId.MONK, 0.5f));
                sets.put("monk dash 1.0", StagePoses.dashing(CombatantId.MONK, 1f));
            }
            case ARCHON_ADVANCES, ARCHON_STRIKES -> {
                sets.put("home", StagePoses.HOME);
                sets.put("archon dash 0.5", StagePoses.dashing(CombatantId.ARCHON, 0.5f));
                sets.put("archon dash 1.0", StagePoses.dashing(CombatantId.ARCHON, 1f));
            }
            case MONK_ENGAGED -> sets.put("monk dash 1.0", StagePoses.dashing(CombatantId.MONK, 1f));
        }
        return sets;
    }

    private String problemAt(CameraShot shot, CameraFrame frame, StagePoses poses, boolean authoringRules,
                             boolean actorsOcclude) {
        Vector3f eye = frame.eye();
        Vector3f target = frame.target();
        if (!eye.isFinite() || !target.isFinite() || !Float.isFinite(frame.fovDeg()) || !Float.isFinite(frame.rollDeg())) {
            return "non-finite frame";
        }
        Vector3f mid = layout.midpoint();
        float horizontal = (float) Math.hypot(eye.x - mid.x, eye.z - mid.z);
        if (horizontal > layout.arenaRadius()) return "eye outside arena radius (" + horizontal + ")";
        if (eye.y < layout.floorY() + FLOOR_CLEARANCE) return "eye below floor clearance (y=" + eye.y + ")";
        for (float[] box : layout.obstacles()) {
            if (insideBox(eye, box, OBSTACLE_MARGIN)) return "eye inside obstacle " + describe(box);
            if (segmentHitsBox(eye, target, box)) return "eye→target blocked by " + describe(box);
        }
        if (!authoringRules) return null;

        if (!shot.crossesLine() && lineSide(eye) > 1.0e-3f) {
            return "180° rule: eye on the +right side of the line (" + lineSide(eye) + ")";
        }
        if (frame.fovDeg() < CameraShot.MIN_FOV || frame.fovDeg() > CameraShot.MAX_FOV) return "fov out of range";
        for (CombatantId id : CombatantId.values()) {
            if (insideActor(eye, id, poses, ACTOR_EYE_MARGIN)) return "eye inside " + id;
        }
        String framing = framingProblem(shot, frame, poses, actorsOcclude);
        return framing != null ? framing : compositionProblem(shot, frame, poses);
    }

    /** The eye's {@code right} coordinate in the MIDPOINT frame (forward = monk→Archon): ≤ 0 is the legal side. */
    public float lineSide(Vector3f eye) {
        Vector3f fwd = layout.facing(CombatantId.MONK);
        Vector3f mid = layout.midpoint();
        return (eye.x - mid.x) * -fwd.z + (eye.z - mid.z) * fwd.x;
    }

    private String framingProblem(CameraShot shot, CameraFrame frame, StagePoses poses, boolean actorsOcclude) {
        Matrix4f viewProj = viewProjection(frame, Math.min(frame.fovDeg(), CameraShot.BASE_FOV));
        for (CombatantId id : CombatantId.values()) {
            boolean wanted = switch (shot.subject()) {
                case NONE -> false;
                case BOTH -> true;
                case MONK -> id == CombatantId.MONK;
                case ARCHON -> id == CombatantId.ARCHON;
            };
            if (!wanted) continue;
            for (float fraction : new float[]{CHEST, HEAD}) {
                Vector3f p = layout.bodyPoint(id, poses.of(id), fraction);
                Vector4f clip = new Vector4f(p, 1f).mul(viewProj);
                if (clip.w <= 0f) return id + " is behind the camera";
                float nx = clip.x / clip.w;
                float ny = clip.y / clip.w;
                if (Math.abs(nx) > FRAMING_NDC_LIMIT || Math.abs(ny) > FRAMING_NDC_LIMIT) {
                    return id + " out of frame (ndc " + nx + ", " + ny + ")";
                }
                for (float[] box : layout.obstacles()) {
                    if (segmentHitsBox(frame.eye(), p, box)) return id + " hidden behind " + describe(box);
                }
                if (actorsOcclude && segmentHitsActor(frame.eye(), p, id.opponent(), poses)) {
                    return id + " hidden behind " + id.opponent();
                }
            }
        }
        return null;
    }

    /**
     * How large the subjects read. A two-shot must keep both actors a readable size; a single-subject
     * shot must neither lose its subject in the scenery nor crop it to a torso, and must not have the
     * other actor standing in the foreground (tag it a two-shot if that is the intent).
     */
    private String compositionProblem(CameraShot shot, CameraFrame frame, StagePoses poses) {
        if (shot.subject() == CameraShot.Subject.NONE || shot.establishing()) return null;
        if (shot.subject() == CameraShot.Subject.BOTH) {
            CombatantId taller = layout.archonHeight() >= layout.monkHeight() ? CombatantId.ARCHON : CombatantId.MONK;
            float tall = projectedHeight(frame, taller, poses);
            float other = projectedHeight(frame, taller.opponent(), poses);
            if (tall < TWO_SHOT_MIN_TALLER) return "two-shot too far: " + taller + " is " + percent(tall) + " of the frame height";
            if (other < TWO_SHOT_MIN_OTHER) return "two-shot too far: " + taller.opponent() + " is " + percent(other) + " of the frame height";
            return hudProblem(shot, frame, poses);
        }
        CombatantId subject = shot.subject() == CameraShot.Subject.MONK ? CombatantId.MONK : CombatantId.ARCHON;
        float size = projectedHeight(frame, subject, poses);
        if (size < SINGLE_MIN) return subject + " too small: " + percent(size) + " of the frame height";
        if (size > SINGLE_MAX) return subject + " too large: " + percent(size) + " of the frame height";
        String buried = hudProblem(shot, frame, poses);
        return buried != null ? buried : foregroundIntrusion(frame, subject, poses);
    }

    /**
     * The subject whose hips must clear the HUD: the single subject, or in a two-shot the actor farther
     * from the lens (over a shoulder, the near actor is a framing element and may sit low).
     */
    private String hudProblem(CameraShot shot, CameraFrame frame, StagePoses poses) {
        CombatantId who = hudSubject(shot, frame, poses);
        float hips = ndcHeight(frame, who, poses, HIPS);
        return hips < HUD_LINE_NDC ? who + " sits behind the HUD (hips at ndc y " + hips + ")" : null;
    }

    /** The actor the HUD rule protects in this frame (see {@link #HUD_LINE_NDC}). */
    public CombatantId hudSubject(CameraShot shot, CameraFrame frame, StagePoses poses) {
        return switch (shot.subject()) {
            case MONK -> CombatantId.MONK;
            case ARCHON -> CombatantId.ARCHON;
            default -> groundDistance(frame.eye(), layout.feet(CombatantId.MONK, poses.monk()))
                    > groundDistance(frame.eye(), layout.feet(CombatantId.ARCHON, poses.archon()))
                    ? CombatantId.MONK : CombatantId.ARCHON;
        };
    }

    /** NDC y (−1 bottom … +1 top) of a point on an actor's body; +∞ when it is behind the camera. */
    public float ndcHeight(CameraFrame frame, CombatantId id, StagePoses poses, float heightFraction) {
        Vector4f clip = new Vector4f(layout.bodyPoint(id, poses.of(id), heightFraction), 1f)
                .mul(viewProjection(frame, frame.fovDeg()));
        return clip.w > 0f ? clip.y / clip.w : Float.POSITIVE_INFINITY;
    }

    /** Feet-to-head span of an actor as a fraction of the frame height; 0 when it is behind the camera. */
    public float projectedHeight(CameraFrame frame, CombatantId id, StagePoses poses) {
        Matrix4f viewProj = viewProjection(frame, frame.fovDeg());
        Vector4f feet = new Vector4f(layout.bodyPoint(id, poses.of(id), 0f), 1f).mul(viewProj);
        Vector4f head = new Vector4f(layout.bodyPoint(id, poses.of(id), 1f), 1f).mul(viewProj);
        if (feet.w <= 0f || head.w <= 0f) return 0f;
        float dx = head.x / head.w - feet.x / feet.w;
        float dy = head.y / head.w - feet.y / feet.w;
        // NDC x spans the wider axis: bring it to frame-height units before measuring (rolled shots).
        return (float) Math.hypot(dx * FRAMING_ASPECT, dy) * 0.5f;
    }

    private String foregroundIntrusion(CameraFrame frame, CombatantId subject, StagePoses poses) {
        CombatantId other = subject.opponent();
        float subjectDistance = groundDistance(frame.eye(), layout.feet(subject, poses.of(subject))) * FOREGROUND_RATIO;
        Matrix4f viewProj = viewProjection(frame, frame.fovDeg());
        Vector3f view = new Vector3f(frame.target()).sub(frame.eye());
        Vector3f side = new Vector3f(-view.z, 0f, view.x);
        if (side.lengthSquared() < 1.0e-8f) side.set(1f, 0f, 0f);
        side.normalize().mul(radius(other));
        for (float fraction : new float[]{0.1f, 0.5f, 0.9f}) {
            for (float lateral : new float[]{-1f, 0f, 1f}) {
                Vector3f p = layout.bodyPoint(other, poses.of(other), fraction).add(side.x * lateral, 0f, side.z * lateral);
                if (groundDistance(frame.eye(), p) >= subjectDistance) continue;
                Vector4f clip = new Vector4f(p, 1f).mul(viewProj);
                if (clip.w > 0f && Math.abs(clip.x / clip.w) < 1f && Math.abs(clip.y / clip.w) < 1f) {
                    return other + " stands in the foreground of a " + subject + " shot";
                }
            }
        }
        return null;
    }

    private static float groundDistance(Vector3f a, Vector3f b) {
        return (float) Math.hypot(a.x - b.x, a.z - b.z);
    }

    private static Matrix4f viewProjection(CameraFrame frame, float fovDeg) {
        return new Matrix4f().perspective((float) Math.toRadians(fovDeg), FRAMING_ASPECT, 0.1f, 500f)
                .lookAt(frame.eye(), frame.target(), new Vector3f(0f, 1f, 0f));
    }

    private static String percent(float fraction) {
        return Math.round(fraction * 100f) + "%";
    }

    // ---- actors as occluders -----------------------------------------------------------------------

    public static float radius(CombatantId id) {
        return id == CombatantId.MONK ? MONK_RADIUS : ARCHON_RADIUS;
    }

    public static float depth(CombatantId id) {
        return id == CombatantId.MONK ? MONK_DEPTH : ARCHON_DEPTH;
    }

    /**
     * A world point in the actor's footprint frame, scaled so the footprint (grown by {@code margin})
     * is the unit circle: x across its facing, z along it, y untouched.
     */
    private Vector3f inFootprintFrame(Vector3f p, CombatantId id, Vector3f feet, float margin) {
        Vector3f facing = layout.facing(id);
        float dx = p.x - feet.x;
        float dz = p.z - feet.z;
        float along = dx * facing.x + dz * facing.z;
        float across = dx * -facing.z + dz * facing.x;
        return new Vector3f(across / (radius(id) + margin), p.y, along / (depth(id) + margin));
    }

    boolean insideActor(Vector3f p, CombatantId id, StagePoses poses, float margin) {
        Vector3f feet = layout.feet(id, poses.of(id));
        Vector3f local = inFootprintFrame(p, id, feet, margin);
        return local.x * local.x + local.z * local.z < 1f
                && p.y > feet.y - margin && p.y < feet.y + layout.height(id) + margin;
    }

    /** Segment against an actor's vertical elliptical cylinder at its posed feet. */
    boolean segmentHitsActor(Vector3f from, Vector3f to, CombatantId id, StagePoses poses) {
        Vector3f feet = layout.feet(id, poses.of(id));
        return segmentHitsCylinder(inFootprintFrame(from, id, feet, 0f), inFootprintFrame(to, id, feet, 0f),
                0f, 0f, 1f, feet.y, feet.y + layout.height(id));
    }

    static boolean segmentHitsCylinder(Vector3f from, Vector3f to, float cx, float cz, float radius, float minY, float maxY) {
        float ox = from.x - cx;
        float oz = from.z - cz;
        float dx = to.x - from.x;
        float dz = to.z - from.z;
        float a = dx * dx + dz * dz;
        float c = ox * ox + oz * oz - radius * radius;
        float t0;
        float t1;
        if (a < 1.0e-10f) { // vertical segment: inside the circle for all of it, or none of it
            if (c > 0f) return false;
            t0 = 0f;
            t1 = 1f;
        } else {
            float b = ox * dx + oz * dz;
            float disc = b * b - a * c;
            if (disc < 0f) return false;
            float root = (float) Math.sqrt(disc);
            t0 = Math.max(0f, (-b - root) / a);
            t1 = Math.min(1f, (-b + root) / a);
            if (t0 > t1) return false;
        }
        float y0 = from.y + (to.y - from.y) * t0;
        float y1 = from.y + (to.y - from.y) * t1;
        return Math.max(y0, y1) >= minY && Math.min(y0, y1) <= maxY;
    }

    static boolean insideBox(Vector3f p, float[] b, float margin) {
        return p.x > b[0] - margin && p.x < b[3] + margin
                && p.y > b[1] - margin && p.y < b[4] + margin
                && p.z > b[2] - margin && p.z < b[5] + margin;
    }

    /** Segment–AABB slab test. */
    static boolean segmentHitsBox(Vector3f from, Vector3f to, float[] b) {
        float tMin = 0f;
        float tMax = 1f;
        float[] origin = {from.x, from.y, from.z};
        float[] delta = {to.x - from.x, to.y - from.y, to.z - from.z};
        for (int axis = 0; axis < 3; axis++) {
            float lo = b[axis];
            float hi = b[axis + 3];
            if (Math.abs(delta[axis]) < 1.0e-7f) {
                if (origin[axis] < lo || origin[axis] > hi) return false;
                continue;
            }
            float t1 = (lo - origin[axis]) / delta[axis];
            float t2 = (hi - origin[axis]) / delta[axis];
            tMin = Math.max(tMin, Math.min(t1, t2));
            tMax = Math.min(tMax, Math.max(t1, t2));
            if (tMin > tMax) return false;
        }
        return true;
    }

    private static String describe(float[] b) {
        return String.format("[%.1f,%.1f,%.1f → %.1f,%.1f,%.1f]", b[0], b[1], b[2], b[3], b[4], b[5]);
    }
}
