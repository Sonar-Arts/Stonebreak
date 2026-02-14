package com.openmason.main.systems.rendering.model.gmr.topology;

/**
 * Immutable per-edge classification metadata stored in a parallel array
 * alongside {@link MeshEdge}[].
 *
 * <p>Fields:
 * <ul>
 *   <li>{@code kind} — topological classification ({@link EdgeKind})</li>
 *   <li>{@code sharp} — whether the edge produces a hard normal break for rendering</li>
 *   <li>{@code seam} — whether the edge is a UV seam (texture discontinuity)</li>
 *   <li>{@code creaseWeight} — subdivision crease weight in [0, 1], where
 *       0 = fully smooth and 1 = infinitely sharp crease</li>
 * </ul>
 *
 * <p>Auto-sharp rules applied at build time:
 * <ul>
 *   <li>{@link EdgeKind#OPEN} and {@link EdgeKind#NON_MANIFOLD} edges are always sharp</li>
 *   <li>{@link EdgeKind#MANIFOLD} edges are sharp when their dihedral angle exceeds
 *       the user-configurable threshold</li>
 * </ul>
 *
 * @param kind          Topological classification
 * @param sharp         Hard normal break flag
 * @param seam          UV seam flag
 * @param creaseWeight  Subdivision crease weight (0 = smooth, 1 = infinitely sharp)
 */
public record EdgeClassification(
    EdgeKind kind,
    boolean sharp,
    boolean seam,
    float creaseWeight
) {

    /**
     * Compact constructor — clamps creaseWeight to [0, 1].
     */
    public EdgeClassification {
        creaseWeight = Math.clamp(creaseWeight, 0.0f, 1.0f);
    }

    /**
     * Create a classification for a manifold edge that passed auto-sharp.
     *
     * @param sharp        Whether the dihedral angle exceeded the threshold
     * @param creaseWeight Crease weight derived from the dihedral angle
     * @return New classification with {@link EdgeKind#MANIFOLD}
     */
    public static EdgeClassification manifold(boolean sharp, float creaseWeight) {
        return new EdgeClassification(EdgeKind.MANIFOLD, sharp, false, creaseWeight);
    }

    /**
     * Create a classification for an open (boundary) edge.
     * Always sharp, crease weight 1.
     *
     * @return New classification with {@link EdgeKind#OPEN}
     */
    public static EdgeClassification open() {
        return new EdgeClassification(EdgeKind.OPEN, true, false, 1.0f);
    }

    /**
     * Create a classification for a non-manifold edge.
     * Always sharp, crease weight 1.
     *
     * @return New classification with {@link EdgeKind#NON_MANIFOLD}
     */
    public static EdgeClassification nonManifold() {
        return new EdgeClassification(EdgeKind.NON_MANIFOLD, true, false, 1.0f);
    }

    /**
     * Return a copy with the sharp flag overridden.
     *
     * @param sharp New sharp value
     * @return New classification with updated sharp flag
     */
    public EdgeClassification withSharp(boolean sharp) {
        return new EdgeClassification(kind, sharp, seam, creaseWeight);
    }

    /**
     * Return a copy with the seam flag overridden.
     *
     * @param seam New seam value
     * @return New classification with updated seam flag
     */
    public EdgeClassification withSeam(boolean seam) {
        return new EdgeClassification(kind, sharp, seam, creaseWeight);
    }

    /**
     * Return a copy with the crease weight overridden.
     *
     * @param creaseWeight New crease weight (clamped to [0, 1])
     * @return New classification with updated crease weight
     */
    public EdgeClassification withCreaseWeight(float creaseWeight) {
        return new EdgeClassification(kind, sharp, seam, creaseWeight);
    }
}
