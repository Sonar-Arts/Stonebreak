package com.openmason.engine.rendering.model.gmr.editable;

import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Authoritative, editable polygon mesh: shared vertices + faces as ordered
 * vertex-id loops with authoritative winding.
 *
 * <p>This is the single source of truth the render mesh (duplicated corners,
 * triangulated — see {@link RenderMeshBuilder}) is derived from. Unlike the
 * legacy triangle-soup representation there is exactly ONE vertex per
 * geometric position: no epsilon welding is needed after construction, and
 * moving a vertex moves it for every face that references it.
 *
 * <p>Invariants (enforced on every mutation):
 * <ul>
 *   <li>Vertex ids are stable list indices; vertices are never removed
 *       (orphans are allowed — faces may be deleted around them).</li>
 *   <li>Face ids are stable and sparse-safe: deleting a face leaves a gap;
 *       ids are never reused (materials/UV regions key off them).</li>
 *   <li>Every face loop has ≥ 3 vertices, valid ids, and no repeats.</li>
 * </ul>
 *
 * <p>Not thread-safe; callers synchronize at the coordinator level, as with
 * the rest of the GMR mutation stack.
 */
public final class EditableMesh {

    private final List<Vector3f> positions = new ArrayList<>();
    private final Map<Integer, EditableFace> faces = new LinkedHashMap<>();
    private int nextFaceId = 0;

    // ── Vertices ────────────────────────────────────────────────────────────

    /**
     * Add a vertex at the given position. Never welds implicitly — callers
     * that want coincident positions merged must do so explicitly (import
     * path uses {@link VertexWelder}).
     *
     * @return the new vertex id
     */
    public int addVertex(Vector3f position) {
        positions.add(new Vector3f(position));
        return positions.size() - 1;
    }

    /** Copy of the position of vertex {@code vertexId}. */
    public Vector3f position(int vertexId) {
        return new Vector3f(positions.get(vertexId));
    }

    /** Write {@code vertexId}'s position into {@code dest} (no allocation). */
    public void position(int vertexId, Vector3f dest) {
        dest.set(positions.get(vertexId));
    }

    /** Move vertex {@code vertexId}; affects every face referencing it. */
    public void setPosition(int vertexId, Vector3f position) {
        positions.get(vertexId).set(position);
    }

    /** Number of vertices ever added (orphans included). */
    public int vertexCount() {
        return positions.size();
    }

    /** @return true if {@code vertexId} identifies an existing vertex */
    public boolean isValidVertex(int vertexId) {
        return vertexId >= 0 && vertexId < positions.size();
    }

    // ── Faces ───────────────────────────────────────────────────────────────

    /**
     * Add a face with the given ordered vertex loop (winding authoritative);
     * UVs will be derived by projection.
     *
     * @return the newly allocated face id
     * @throws IllegalArgumentException if the loop is invalid
     */
    public int addFace(int[] vertexLoop) {
        return addFace(vertexLoop, null);
    }

    /**
     * Add a face with authored per-corner UVs ({@code null} = derive by
     * projection; otherwise u,v per loop vertex).
     */
    public int addFace(int[] vertexLoop, float[] cornerUVs) {
        int[] loop = validatedLoop(vertexLoop);
        int faceId = nextFaceId++;
        faces.put(faceId, new EditableFace(faceId, loop, validatedUVs(loop, cornerUVs)));
        return faceId;
    }

    /**
     * Add a face with an explicit id — import path only, so file-format face
     * ids are preserved verbatim.
     *
     * @throws IllegalArgumentException if the loop is invalid or the id taken
     */
    public void addFaceWithId(int faceId, int[] vertexLoop) {
        addFaceWithId(faceId, vertexLoop, null);
    }

    /** Import-path variant carrying authored per-corner UVs. */
    public void addFaceWithId(int faceId, int[] vertexLoop, float[] cornerUVs) {
        if (faceId < 0) {
            throw new IllegalArgumentException("Face id must be >= 0: " + faceId);
        }
        if (faces.containsKey(faceId)) {
            throw new IllegalArgumentException("Face id already in use: " + faceId);
        }
        int[] loop = validatedLoop(vertexLoop);
        faces.put(faceId, new EditableFace(faceId, loop, validatedUVs(loop, cornerUVs)));
        nextFaceId = Math.max(nextFaceId, faceId + 1);
    }

    /**
     * Replace a face's vertex loop, dropping any authored UVs (they no longer
     * correspond to the loop). Ops that can maintain UVs use the 3-arg form.
     *
     * @throws IllegalArgumentException if the face does not exist or the loop is invalid
     */
    public void replaceFaceLoop(int faceId, int[] vertexLoop) {
        replaceFaceLoop(faceId, vertexLoop, null);
    }

    /**
     * Replace a face's vertex loop together with maintained authored UVs
     * ({@code null} drops them). The single mutation point for loops so
     * invariants are checked in one place.
     */
    public void replaceFaceLoop(int faceId, int[] vertexLoop, float[] cornerUVs) {
        EditableFace face = faces.get(faceId);
        if (face == null) {
            throw new IllegalArgumentException("No such face: " + faceId);
        }
        int[] loop = validatedLoop(vertexLoop);
        face.setLoop(loop, validatedUVs(loop, cornerUVs));
    }

    /** @return the face, or {@code null} if deleted / never existed */
    public EditableFace face(int faceId) {
        return faces.get(faceId);
    }

    /**
     * Remove a face. Its vertices are intentionally left in place (holes and
     * later re-fills are a feature; orphan vertices are allowed).
     *
     * @return true if the face existed
     */
    public boolean removeFace(int faceId) {
        return faces.remove(faceId) != null;
    }

    /** Live faces in insertion order (unmodifiable view). */
    public Collection<EditableFace> faces() {
        return Collections.unmodifiableCollection(faces.values());
    }

    /** Number of live faces. */
    public int faceCount() {
        return faces.size();
    }

    /** Exclusive upper bound on face ids ever allocated (sparse arrays size off this). */
    public int faceIdUpperBound() {
        return nextFaceId;
    }

    // ── Queries used by mutation ops ────────────────────────────────────────

    /**
     * Faces whose loop contains vertices {@code a} and {@code b} as an
     * adjacent pair (either direction) — i.e. the faces bordering edge (a,b).
     */
    public List<EditableFace> facesWithEdge(int a, int b) {
        List<EditableFace> result = new ArrayList<>(2);
        for (EditableFace face : faces.values()) {
            if (face.adjacentPairIndex(a, b) >= 0) {
                result.add(face);
            }
        }
        return result;
    }

    /** Faces whose loop contains {@code vertexId}. */
    public List<EditableFace> facesWithVertex(int vertexId) {
        List<EditableFace> result = new ArrayList<>(4);
        for (EditableFace face : faces.values()) {
            if (face.containsVertex(vertexId)) {
                result.add(face);
            }
        }
        return result;
    }

    // ── Snapshot support ────────────────────────────────────────────────────

    /** Deep copy (positions and loops); face ids and iteration order preserved. */
    public EditableMesh deepCopy() {
        EditableMesh copy = new EditableMesh();
        for (Vector3f p : positions) {
            copy.positions.add(new Vector3f(p));
        }
        for (EditableFace face : faces.values()) {
            copy.faces.put(face.faceId(),
                new EditableFace(face.faceId(), face.loop(), face.cornerUVs()));
        }
        copy.nextFaceId = nextFaceId;
        return copy;
    }

    /**
     * Exact content comparison (positions, face ids, loops, authored UVs) —
     * no epsilon. Used for undo no-op detection; {@code nextFaceId} is
     * intentionally ignored (a created-then-deleted face is a no-op).
     */
    public boolean contentEquals(EditableMesh other) {
        if (other == this) {
            return true;
        }
        if (other == null
                || positions.size() != other.positions.size()
                || faces.size() != other.faces.size()) {
            return false;
        }
        for (int i = 0; i < positions.size(); i++) {
            if (!positions.get(i).equals(other.positions.get(i))) {
                return false;
            }
        }
        for (EditableFace face : faces.values()) {
            EditableFace o = other.faces.get(face.faceId());
            if (o == null
                    || !Arrays.equals(face.loopRef(), o.loopRef())
                    || !Arrays.equals(face.cornerUVs(), o.cornerUVs())) {
                return false;
            }
        }
        return true;
    }

    // ── Validation ──────────────────────────────────────────────────────────

    private int[] validatedLoop(int[] vertexLoop) {
        if (vertexLoop == null || vertexLoop.length < 3) {
            throw new IllegalArgumentException("Face loop must have >= 3 vertices: "
                + (vertexLoop == null ? "null" : vertexLoop.length));
        }
        for (int i = 0; i < vertexLoop.length; i++) {
            int v = vertexLoop[i];
            if (!isValidVertex(v)) {
                throw new IllegalArgumentException("Loop references invalid vertex id " + v);
            }
            for (int j = i + 1; j < vertexLoop.length; j++) {
                if (vertexLoop[j] == v) {
                    throw new IllegalArgumentException("Loop repeats vertex id " + v);
                }
            }
        }
        return vertexLoop.clone();
    }

    private float[] validatedUVs(int[] loop, float[] cornerUVs) {
        if (cornerUVs == null) {
            return null;
        }
        if (cornerUVs.length != loop.length * 2) {
            throw new IllegalArgumentException("cornerUVs length " + cornerUVs.length
                + " does not match loop length " + loop.length);
        }
        return cornerUVs.clone();
    }
}
