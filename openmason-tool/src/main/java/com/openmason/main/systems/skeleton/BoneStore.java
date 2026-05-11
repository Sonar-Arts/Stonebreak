package com.openmason.main.systems.skeleton;

import com.openmason.engine.format.omo.OMOFormat;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/**
 * Session-level skeleton storage for the currently loaded model.
 *
 * <p>Holds the editable list of {@link OMOFormat.BoneEntry} records along with cached
 * world-space transforms. Pure data + math; rendering is handled separately by
 * {@code BoneGizmoRenderer}, and serialization by the OMO I/O layer.
 *
 * <p>The skeleton is treated as a tree where each bone's local rest-pose transform is
 * {@code T(origin) * T(pos) * R(rot_xyz)}. World transforms are composed by walking
 * each bone's parent chain. Cycles (malformed data) are detected and broken silently
 * to avoid infinite recursion.
 *
 * <p>Single responsibility: own the bone list and expose derived pose information.
 */
public final class BoneStore {

    private static final Logger logger = LoggerFactory.getLogger(BoneStore.class);

    private final List<OMOFormat.BoneEntry> bones = new ArrayList<>();
    private final Map<String, OMOFormat.BoneEntry> byId = new HashMap<>();

    private Map<String, Matrix4f> cachedWorld;     // head-frame world matrices
    private Map<String, Matrix4f> cachedTailWorld; // tail-frame world matrices (children inherit from these)
    private boolean cacheDirty = true;

    /**
     * Resolves a parent matrix for a {@code parentBoneId} that is not a known bone id.
     * Used when bones are nested under non-bone nodes (e.g. parts) in a unified hierarchy.
     * Returning {@code null} treats the bone as a root.
     */
    private Function<String, Matrix4f> externalParentResolver;

    /**
     * Fired after any structural mutation (set, put, remove, clear). Lets external
     * stores (e.g. a part manager that composes its transforms with bone matrices)
     * invalidate their own caches and rebuild.
     */
    private Runnable onChange;

    /**
     * Id of the bone the editor currently treats as "selected", or {@code null} when
     * no bone is selected. Used by the gizmo system to target the joint location.
     */
    private String selectedBoneId;

    public String getSelectedBoneId() { return selectedBoneId; }

    public void setSelectedBoneId(String boneId) { this.selectedBoneId = boneId; }

    /** Wire an external parent resolver (e.g. backed by a part manager's world matrices). */
    public void setExternalParentResolver(Function<String, Matrix4f> resolver) {
        this.externalParentResolver = resolver;
        invalidate();
    }

    /** Wire a callback fired after any structural mutation. */
    public void setOnChange(Runnable onChange) {
        this.onChange = onChange;
    }

    private void fireOnChange() {
        if (onChange != null) onChange.run();
    }

    /** Replace the entire skeleton. Null or empty clears the skeleton. */
    public void setBones(List<OMOFormat.BoneEntry> entries) {
        bones.clear();
        byId.clear();
        if (entries != null) {
            for (OMOFormat.BoneEntry b : entries) {
                if (b == null) continue;
                bones.add(b);
                byId.put(b.id(), b);
            }
        }
        invalidate();
        fireOnChange();
    }

    /** Add or replace a single bone (by id). */
    public void put(OMOFormat.BoneEntry entry) {
        Objects.requireNonNull(entry, "entry");
        OMOFormat.BoneEntry existing = byId.get(entry.id());
        if (existing != null) {
            bones.remove(existing);
        }
        bones.add(entry);
        byId.put(entry.id(), entry);
        invalidate();
        fireOnChange();
    }

    /** Remove a bone by id. Children of the removed bone become roots. */
    public boolean remove(String boneId) {
        OMOFormat.BoneEntry existing = byId.remove(boneId);
        if (existing == null) {
            return false;
        }
        bones.remove(existing);
        // Re-parent any children to null (root).
        List<OMOFormat.BoneEntry> reparented = new ArrayList<>();
        for (OMOFormat.BoneEntry b : bones) {
            if (boneId.equals(b.parentBoneId())) {
                reparented.add(new OMOFormat.BoneEntry(
                        b.id(), b.name(), null,
                        b.originX(), b.originY(), b.originZ(),
                        b.posX(), b.posY(), b.posZ(),
                        b.rotX(), b.rotY(), b.rotZ(),
                        b.endpointX(), b.endpointY(), b.endpointZ()
                ));
            }
        }
        for (OMOFormat.BoneEntry r : reparented) {
            byId.put(r.id(), r);
            int idx = indexOfId(r.id());
            if (idx >= 0) {
                bones.set(idx, r);
            }
        }
        invalidate();
        fireOnChange();
        return true;
    }

    /** Clear all bones. */
    public void clear() {
        if (bones.isEmpty()) return;
        bones.clear();
        byId.clear();
        invalidate();
        fireOnChange();
    }

    public boolean isEmpty() {
        return bones.isEmpty();
    }

    public int size() {
        return bones.size();
    }

    /** Returns an unmodifiable snapshot of the current bone list. */
    public List<OMOFormat.BoneEntry> getBones() {
        return Collections.unmodifiableList(new ArrayList<>(bones));
    }

    public OMOFormat.BoneEntry getById(String id) {
        return id == null ? null : byId.get(id);
    }

    /** Force the world-pose cache to rebuild on next query. */
    public void invalidate() {
        cacheDirty = true;
    }

    /**
     * Returns the head-frame world matrix for the given bone — the matrix whose
     * translation is the head joint position and whose rotation is the bone's
     * applied rest-pose rotation. {@code null} if the bone is unknown.
     */
    public Matrix4f getWorldTransform(String boneId) {
        OMOFormat.BoneEntry bone = byId.get(boneId);
        if (bone == null) return null;
        ensureCacheReady();
        Matrix4f cached = cachedWorld.get(boneId);
        if (cached != null) return cached;
        return computeHeadWorld(bone, new ArrayList<>());
    }

    /**
     * Returns the tail-frame world matrix for the given bone — head matrix
     * translated by the bone's local-space {@code endpoint} offset. Children
     * (parts or other bones) inherit from this frame, so rotating a bone rotates
     * everything below it. {@code null} if the bone is unknown.
     */
    public Matrix4f getTailWorldTransform(String boneId) {
        OMOFormat.BoneEntry bone = byId.get(boneId);
        if (bone == null) return null;
        ensureCacheReady();
        Matrix4f cached = cachedTailWorld.get(boneId);
        if (cached != null) return cached;
        return computeTailWorld(bone, new ArrayList<>());
    }

    /**
     * Returns the world-space joint position (head) for the given bone, or
     * {@code null} if the bone is unknown.
     */
    public Vector3f getJointWorldPosition(String boneId) {
        Matrix4f world = getWorldTransform(boneId);
        return world == null ? null : world.transformPosition(new Vector3f());
    }

    /** World-space tail position for the given bone, or {@code null} if unknown. */
    public Vector3f getTailWorldPosition(String boneId) {
        Matrix4f world = getTailWorldTransform(boneId);
        return world == null ? null : world.transformPosition(new Vector3f());
    }

    /**
     * Snapshot of head joint world positions keyed by bone id. Useful for renderers
     * that want to walk every bone once per frame.
     */
    public Map<String, Vector3f> snapshotJointWorldPositions() {
        ensureCacheReady();
        for (OMOFormat.BoneEntry b : bones) {
            if (!cachedWorld.containsKey(b.id())) {
                computeHeadWorld(b, new ArrayList<>());
            }
        }
        Map<String, Vector3f> out = new LinkedHashMap<>(cachedWorld.size());
        for (Map.Entry<String, Matrix4f> e : cachedWorld.entrySet()) {
            out.put(e.getKey(), e.getValue().transformPosition(new Vector3f()));
        }
        return out;
    }

    /** Snapshot of tail world positions keyed by bone id. */
    public Map<String, Vector3f> snapshotTailWorldPositions() {
        ensureCacheReady();
        for (OMOFormat.BoneEntry b : bones) {
            if (!cachedTailWorld.containsKey(b.id())) {
                computeTailWorld(b, new ArrayList<>());
            }
        }
        Map<String, Vector3f> out = new LinkedHashMap<>(cachedTailWorld.size());
        for (Map.Entry<String, Matrix4f> e : cachedTailWorld.entrySet()) {
            out.put(e.getKey(), e.getValue().transformPosition(new Vector3f()));
        }
        return out;
    }

    /**
     * Lazily prepare the cache maps. Safe under cross-store re-entrancy — never
     * recursively replaces an in-progress cache, which would clobber partial results
     * when the external resolver calls back into us through a parts-with-bone-parents
     * chain.
     */
    private void ensureCacheReady() {
        if (cacheDirty || cachedWorld == null) {
            cachedWorld = new LinkedHashMap<>(bones.size());
            cachedTailWorld = new LinkedHashMap<>(bones.size());
            cacheDirty = false;
        }
    }

    /**
     * Compute the bone's head-frame world matrix.
     * Composition: {@code parent_tail × T(origin) × T(pos) × R(rot)} — head sits at
     * {@code origin + pos} in the parent's tail space, with the bone's local rotation
     * applied around the head joint.
     */
    private Matrix4f computeHeadWorld(OMOFormat.BoneEntry bone, List<String> visiting) {
        if (bone == null) {
            return new Matrix4f();
        }
        Matrix4f cached = cachedWorld.get(bone.id());
        if (cached != null) {
            return cached;
        }
        if (visiting.contains(bone.id())) {
            logger.warn("Bone hierarchy cycle detected at '{}' — breaking chain", bone.id());
            Matrix4f identity = new Matrix4f();
            cachedWorld.put(bone.id(), identity);
            return identity;
        }
        visiting.add(bone.id());

        Matrix4f parentTail = parentFrame(bone, visiting);

        Matrix4f local = new Matrix4f()
                .translate(bone.originX(), bone.originY(), bone.originZ())
                .translate(bone.posX(), bone.posY(), bone.posZ())
                .rotateXYZ(
                        (float) Math.toRadians(bone.rotX()),
                        (float) Math.toRadians(bone.rotY()),
                        (float) Math.toRadians(bone.rotZ())
                );

        Matrix4f world = new Matrix4f(parentTail).mul(local);
        cachedWorld.put(bone.id(), world);
        visiting.remove(bone.id());
        return world;
    }

    /**
     * Compute the bone's tail-frame world matrix.
     * Composition: {@code head × T(endpoint)} — the bone's local-space endpoint
     * offset applied after the head rotation, so rotating the bone rotates its
     * tail (and everything that inherits from it).
     */
    private Matrix4f computeTailWorld(OMOFormat.BoneEntry bone, List<String> visiting) {
        if (bone == null) {
            return new Matrix4f();
        }
        Matrix4f cached = cachedTailWorld.get(bone.id());
        if (cached != null) {
            return cached;
        }
        Matrix4f head = computeHeadWorld(bone, visiting);
        Matrix4f tail = new Matrix4f(head)
                .translate(bone.endpointX(), bone.endpointY(), bone.endpointZ());
        cachedTailWorld.put(bone.id(), tail);
        return tail;
    }

    /**
     * Resolve the frame this bone inherits from — its parent's tail frame for
     * bone parents, the external resolver's value for non-bone parents (e.g.
     * a parent part), or identity for roots.
     */
    private Matrix4f parentFrame(OMOFormat.BoneEntry bone, List<String> visiting) {
        if (bone.isRoot()) return new Matrix4f();
        OMOFormat.BoneEntry parent = byId.get(bone.parentBoneId());
        if (parent != null) {
            return computeTailWorld(parent, visiting);
        }
        if (externalParentResolver != null) {
            Matrix4f external = externalParentResolver.apply(bone.parentBoneId());
            return external != null ? new Matrix4f(external) : new Matrix4f();
        }
        return new Matrix4f();
    }

    private int indexOfId(String id) {
        for (int i = 0; i < bones.size(); i++) {
            if (bones.get(i).id().equals(id)) return i;
        }
        return -1;
    }
}
