package com.openmason.main.systems.skeleton;

import com.openmason.engine.format.omo.OMOFormat;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Session-level attachment point (socket) storage for the currently loaded model.
 *
 * <p>Holds the editable list of {@link OMOFormat.AttachmentPointEntry} records.
 * Pure data + math; rendering is handled by {@code AttachmentGizmoRenderer}, and
 * serialization by the OMO I/O layer.
 *
 * <p>Socket positions/rotations are authored in <b>rest-pose model space</b> —
 * the same space the mesh vertices live in — so a socket's model-space frame is
 * simply {@code T(pos) · R_xyz(rot)}. The model-level transform is applied by
 * consumers (gizmo target, marker renderer), exactly as bone math does. The
 * {@code parentPartId} is runtime binding metadata (which part the socket
 * follows through animation in-game); it does not affect the editor pose.
 *
 * <p>Single responsibility: own the socket list and expose derived pose info.
 */
public final class AttachmentStore {

    private final List<OMOFormat.AttachmentPointEntry> points = new ArrayList<>();
    private final Map<String, OMOFormat.AttachmentPointEntry> byId = new HashMap<>();

    /** Fired after any structural mutation (set, put, remove, clear). */
    private Runnable onChange;

    /** Id of the socket the editor currently treats as "selected", or null. */
    private String selectedAttachmentId;

    public String getSelectedAttachmentId() { return selectedAttachmentId; }

    public void setSelectedAttachmentId(String id) { this.selectedAttachmentId = id; }

    /** Wire a callback fired after any structural mutation. */
    public void setOnChange(Runnable onChange) {
        this.onChange = onChange;
    }

    private void fireOnChange() {
        if (onChange != null) onChange.run();
    }

    /** Replace the entire socket list. Null or empty clears it. */
    public void setPoints(List<OMOFormat.AttachmentPointEntry> entries) {
        points.clear();
        byId.clear();
        if (entries != null) {
            for (OMOFormat.AttachmentPointEntry p : entries) {
                if (p == null) continue;
                points.add(p);
                byId.put(p.id(), p);
            }
        }
        fireOnChange();
    }

    /** Add or replace a single socket (by id). */
    public void put(OMOFormat.AttachmentPointEntry entry) {
        Objects.requireNonNull(entry, "entry");
        OMOFormat.AttachmentPointEntry existing = byId.get(entry.id());
        if (existing != null) {
            points.remove(existing);
        }
        points.add(entry);
        byId.put(entry.id(), entry);
        fireOnChange();
    }

    /** Remove a socket by id. */
    public boolean remove(String id) {
        OMOFormat.AttachmentPointEntry existing = byId.remove(id);
        if (existing == null) {
            return false;
        }
        points.remove(existing);
        if (id.equals(selectedAttachmentId)) {
            selectedAttachmentId = null;
        }
        fireOnChange();
        return true;
    }

    /** Clear all sockets. */
    public void clear() {
        if (points.isEmpty()) return;
        points.clear();
        byId.clear();
        selectedAttachmentId = null;
        fireOnChange();
    }

    public boolean isEmpty() {
        return points.isEmpty();
    }

    public int size() {
        return points.size();
    }

    /** Returns an unmodifiable snapshot of the current socket list. */
    public List<OMOFormat.AttachmentPointEntry> getPoints() {
        return Collections.unmodifiableList(new ArrayList<>(points));
    }

    public OMOFormat.AttachmentPointEntry getById(String id) {
        return id == null ? null : byId.get(id);
    }

    /**
     * The socket's model-space frame: {@code T(pos) · R_xyz(rot) · S(scale)} —
     * the transform an attached model inherits (position, orientation, and
     * scale). Consumers apply the model-level transform on top. {@code null}
     * if unknown.
     */
    public Matrix4f getWorldTransform(String id) {
        OMOFormat.AttachmentPointEntry p = byId.get(id);
        if (p == null) return null;
        return new Matrix4f()
                .translate(p.posX(), p.posY(), p.posZ())
                .rotateXYZ(
                        (float) Math.toRadians(p.rotX()),
                        (float) Math.toRadians(p.rotY()),
                        (float) Math.toRadians(p.rotZ()))
                .scale(p.scaleX(), p.scaleY(), p.scaleZ());
    }

    /** The socket's model-space position, or {@code null} if unknown. */
    public Vector3f getWorldPosition(String id) {
        OMOFormat.AttachmentPointEntry p = byId.get(id);
        if (p == null) return null;
        return new Vector3f(p.posX(), p.posY(), p.posZ());
    }
}
