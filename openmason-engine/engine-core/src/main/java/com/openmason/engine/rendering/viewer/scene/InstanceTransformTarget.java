package com.openmason.engine.rendering.viewer.scene;

import com.openmason.engine.rendering.viewer.gizmo.interaction.ITransformTarget;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;

/**
 * Lets the gizmo drive a scene instance — and, optionally, a group of followers.
 *
 * <p>The fifth implementation of {@link ITransformTarget}, alongside the editor's part,
 * bone, socket and model targets — evidence the seam generalized: transforming a whole
 * placed model needed no change to the gizmo at all.
 *
 * <p>Multi-selection: the gizmo only ever talks to one target, so a group drag is
 * expressed as the <em>primary</em> instance plus a list of {@link #setFollowers followers}.
 * Every write to the primary is mirrored onto the followers as a delta from the drag's
 * start pose — translation by the same offset, rotation by the same angles about each
 * follower's own pivot, scale by the same ratio — so the group keeps its shape. The start
 * poses are captured in {@link #beginDrag()} and stay readable through
 * {@link #followerStarts()} until {@link #endDrag()}, which is after the gizmo has
 * reported the drag to its undo sink; a host can therefore record every follower's
 * before/after pair in the same undo entry as the primary's.
 */
public final class InstanceTransformTarget implements ITransformTarget {

    /** One follower's pose at drag start, for delta application and undo capture. */
    public record StartPose(ModelInstance instance, Vector3f position, Vector3f rotation, Vector3f scale) {
        public StartPose {
            position = new Vector3f(position);
            rotation = new Vector3f(rotation);
            scale = new Vector3f(scale);
        }

        static StartPose of(ModelInstance instance) {
            var t = instance.transform();
            return new StartPose(instance,
                    new Vector3f(t.getPositionX(), t.getPositionY(), t.getPositionZ()),
                    new Vector3f(t.getRotationX(), t.getRotationY(), t.getRotationZ()),
                    new Vector3f(t.getScaleX(), t.getScaleY(), t.getScaleZ()));
        }
    }

    private ModelInstance instance;
    private final List<ModelInstance> followers = new ArrayList<>();

    /** Primary's pose at drag start; null outside a drag. */
    private StartPose primaryStart;
    private final List<StartPose> followerStarts = new ArrayList<>();

    public void setInstance(ModelInstance instance) {
        this.instance = instance;
        // A new primary invalidates any group: the host re-supplies followers explicitly.
        this.followers.clear();
    }

    public ModelInstance instance() {
        return instance;
    }

    /**
     * Instances that move with the primary. The primary itself and locked instances are
     * ignored; null clears the group.
     */
    public void setFollowers(List<ModelInstance> group) {
        followers.clear();
        if (group == null) {
            return;
        }
        for (ModelInstance candidate : group) {
            if (candidate != null && candidate != instance && !candidate.isLocked()
                    && !followers.contains(candidate)) {
                followers.add(candidate);
            }
        }
    }

    public List<ModelInstance> followers() {
        return List.copyOf(followers);
    }

    /** Follower start poses of the drag in progress; empty outside a drag or without a group. */
    public List<StartPose> followerStarts() {
        return List.copyOf(followerStarts);
    }

    // ------------------------------------------------------------ drag lifecycle

    @Override
    public void beginDrag() {
        if (instance == null) {
            return;
        }
        primaryStart = StartPose.of(instance);
        followerStarts.clear();
        for (ModelInstance follower : followers) {
            followerStarts.add(StartPose.of(follower));
        }
    }

    @Override
    public void endDrag() {
        primaryStart = null;
        followerStarts.clear();
    }

    /**
     * Followers without a drag in progress (a typed inspector value, say) still need a
     * reference pose to take deltas from; take it lazily from the current state.
     */
    private void ensureStartPoses() {
        if (primaryStart == null) {
            beginDrag();
        }
    }

    // ------------------------------------------------------------------ reads

    @Override
    public Vector3f getPosition() {
        if (instance == null) return new Vector3f();
        var t = instance.transform();
        return new Vector3f(t.getPositionX(), t.getPositionY(), t.getPositionZ());
    }

    @Override
    public Vector3f getRotation() {
        if (instance == null) return new Vector3f();
        var t = instance.transform();
        return new Vector3f(t.getRotationX(), t.getRotationY(), t.getRotationZ());
    }

    @Override
    public Vector3f getScale() {
        if (instance == null) return new Vector3f(1, 1, 1);
        var t = instance.transform();
        return new Vector3f(t.getScaleX(), t.getScaleY(), t.getScaleZ());
    }

    // ----------------------------------------------------------------- writes

    @Override
    public void setPosition(float x, float y, float z) {
        if (instance == null || instance.isLocked()) return;
        ensureStartPoses();
        instance.transform().setPosition(x, y, z);
        mirrorTranslation();
    }

    @Override
    public void setPosition(float x, float y, float z, boolean snap, float snapIncrement) {
        if (instance == null || instance.isLocked()) return;
        ensureStartPoses();
        instance.transform().setPosition(x, y, z, snap, snapIncrement);
        // Followers take the primary's (already snapped) delta, so the group stays
        // grid-cohesive rather than each member snapping independently.
        mirrorTranslation();
    }

    @Override
    public void setRotation(float x, float y, float z) {
        if (instance == null || instance.isLocked()) return;
        ensureStartPoses();
        instance.transform().setRotation(x, y, z);
        if (followerStarts.isEmpty()) return;
        Vector3f delta = new Vector3f(x, y, z).sub(primaryStart.rotation());
        for (StartPose start : followerStarts) {
            Vector3f r = new Vector3f(start.rotation()).add(delta);
            start.instance().transform().setRotation(r.x, r.y, r.z);
        }
    }

    @Override
    public void setScale(float x, float y, float z) {
        if (instance == null || instance.isLocked()) return;
        ensureStartPoses();
        instance.transform().setScale(x, y, z);
        if (followerStarts.isEmpty()) return;
        Vector3f s0 = primaryStart.scale();
        float rx = ratio(x, s0.x);
        float ry = ratio(y, s0.y);
        float rz = ratio(z, s0.z);
        for (StartPose start : followerStarts) {
            Vector3f s = start.scale();
            start.instance().transform().setScale(s.x * rx, s.y * ry, s.z * rz);
        }
    }

    private void mirrorTranslation() {
        if (followerStarts.isEmpty()) return;
        var t = instance.transform();
        Vector3f delta = new Vector3f(t.getPositionX(), t.getPositionY(), t.getPositionZ())
                .sub(primaryStart.position());
        for (StartPose start : followerStarts) {
            Vector3f p = new Vector3f(start.position()).add(delta);
            start.instance().transform().setPosition(p.x, p.y, p.z);
        }
    }

    private static float ratio(float now, float then) {
        return Math.abs(then) < 1e-6f ? 1.0f : now / then;
    }

    // ------------------------------------------------------------------ misc

    @Override
    public Vector3f getWorldCenter() {
        return instance == null ? new Vector3f() : instance.worldBounds().center();
    }

    @Override
    public boolean isActive() {
        return instance != null;
    }

    @Override
    public boolean isLocked() {
        return instance != null && instance.isLocked();
    }

    /** A placed instance has no other undo mechanism, so its drags must be reported. */
    @Override
    public boolean recordsDragsForUndo() {
        return true;
    }

    @Override
    public String getTargetName() {
        if (instance == null) return "No selection";
        return followers.isEmpty() ? instance.name() : instance.name() + " (+" + followers.size() + ")";
    }
}
