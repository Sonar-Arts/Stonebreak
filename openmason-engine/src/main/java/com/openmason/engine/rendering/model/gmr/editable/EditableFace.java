package com.openmason.engine.rendering.model.gmr.editable;

/**
 * One polygon face of an {@link EditableMesh}.
 *
 * <p>The vertex loop is an ordered list of {@link EditableMesh} vertex ids whose
 * order IS the authoritative winding (counter-clockwise when viewed from the
 * face's outward normal). Loops have at least 3 vertices and no repeats.
 *
 * <p>A face may carry AUTHORED per-corner UVs (u,v per loop vertex) — imported
 * texture coordinates for shapes like spheres whose texturing is not
 * expressible as a per-face region projection. Faces without authored UVs get
 * projected UVs at render derivation. Mutation ops maintain authored UVs where
 * interpolation is well-defined (edge subdivision, face split) and drop them
 * otherwise.
 *
 * <p>Instances are owned by their mesh: the loop can only be replaced through
 * {@link EditableMesh#replaceFaceLoop(int, int[], float[])} so invariants are
 * validated in one place.
 */
public final class EditableFace {

    private final int faceId;
    private int[] vertexLoop;
    private float[] cornerUVs; // (u,v) per loop vertex, or null = derive by projection

    EditableFace(int faceId, int[] vertexLoop, float[] cornerUVs) {
        this.faceId = faceId;
        this.vertexLoop = vertexLoop;
        this.cornerUVs = cornerUVs;
    }

    /** Stable face identifier (survives edits; materials and UV regions key off it). */
    public int faceId() {
        return faceId;
    }

    /** Number of vertices in the loop. */
    public int loopLength() {
        return vertexLoop.length;
    }

    /** Vertex id at loop position {@code i} (no wrap-around). */
    public int vertexAt(int i) {
        return vertexLoop[i];
    }

    /**
     * Copy of the ordered vertex loop. Mutating the returned array has no
     * effect on the mesh — use {@link EditableMesh#replaceFaceLoop(int, int[])}.
     */
    public int[] loop() {
        return vertexLoop.clone();
    }

    /** @return position of {@code vertexId} in the loop, or -1 if absent */
    public int indexOf(int vertexId) {
        for (int i = 0; i < vertexLoop.length; i++) {
            if (vertexLoop[i] == vertexId) {
                return i;
            }
        }
        return -1;
    }

    /** @return true if the loop contains {@code vertexId} */
    public boolean containsVertex(int vertexId) {
        return indexOf(vertexId) >= 0;
    }

    /**
     * If vertices {@code a} and {@code b} are adjacent in the loop (in either
     * direction, including the wrap-around pair), returns the loop position
     * {@code i} such that the directed pair is {@code (loop[i], loop[i+1])}
     * with {@code loop[i] == a} or {@code loop[i] == b}. Returns -1 if not adjacent.
     */
    public int adjacentPairIndex(int a, int b) {
        int n = vertexLoop.length;
        for (int i = 0; i < n; i++) {
            int cur = vertexLoop[i];
            int next = vertexLoop[(i + 1) % n];
            if ((cur == a && next == b) || (cur == b && next == a)) {
                return i;
            }
        }
        return -1;
    }

    /** @return true if this face carries authored per-corner UVs */
    public boolean hasAuthoredUVs() {
        return cornerUVs != null;
    }

    /**
     * Copy of the authored per-corner UVs (u,v per loop vertex, same order as
     * the loop), or {@code null} when UVs are derived by projection.
     */
    public float[] cornerUVs() {
        return cornerUVs != null ? cornerUVs.clone() : null;
    }

    // Package-private direct access for EditableMesh (validation lives there).
    int[] loopRef() {
        return vertexLoop;
    }

    void setLoop(int[] validatedLoop, float[] validatedUVs) {
        this.vertexLoop = validatedLoop;
        this.cornerUVs = validatedUVs;
    }
}
