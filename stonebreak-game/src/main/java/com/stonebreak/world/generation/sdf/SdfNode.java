package com.stonebreak.world.generation.sdf;

/**
 * Construction tree node for composing complex SDFs from primitives.
 *
 * <p>SdfNode represents a node in a tree structure where:</p>
 * <ul>
 *   <li><b>Leaf nodes</b> contain a single primitive (sphere, box, capsule, etc.)</li>
 *   <li><b>Binary operation nodes</b> combine two child nodes with a CSG operation</li>
 * </ul>
 *
 * <p><b>Example Construction Tree:</b></p>
 * <pre>
 *                    [Subtract]
 *                    /        \
 *              [Terrain]   [SmoothUnion]
 *                          /            \
 *                    [Sphere1]        [Sphere2]
 *
 * Result: Terrain with two smoothly connected cave chambers carved out
 * </pre>
 *
 * <p><b>Usage Pattern:</b></p>
 * <pre>
 * // Create cave chambers
 * SdfNode chamber1 = SdfNode.leaf(new SdfSphere(100, 50, 200, 8));
 * SdfNode chamber2 = SdfNode.leaf(new SdfSphere(120, 55, 210, 6));
 *
 * // Combine chambers with smooth union
 * SdfNode caves = SdfNode.smoothUnion(chamber1, chamber2, 4.0f);
 *
 * // Create terrain heightfield
 * SdfNode terrain = SdfNode.leaf(terrainHeightfield);
 *
 * // Carve caves from terrain
 * SdfNode result = SdfNode.subtract(terrain, caves);
 *
 * // Evaluate at any point
 * float distance = result.evaluate(x, y, z);
 * </pre>
 *
 * <p><b>Performance:</b> Tree evaluation is O(n) where n is number of nodes.
 * Typical cave systems have 10-50 nodes, resulting in 50-250 primitive evaluations
 * per query. Still much faster than noise-based approaches.</p>
 *
 * <p><b>Thread Safety:</b> Immutable and thread-safe if primitives are immutable.</p>
 */
public abstract class SdfNode implements SdfPrimitive {

    /**
     * CSG operation type for binary nodes.
     */
    public enum Operation {
        UNION,
        SUBTRACT,
        INTERSECT,
        SMOOTH_UNION,
        SMOOTH_SUBTRACT,
        SMOOTH_INTERSECT
    }

    /**
     * Create a leaf node containing a single primitive.
     *
     * @param primitive The SDF primitive (sphere, box, capsule, etc.)
     * @return Leaf node wrapping the primitive
     */
    public static SdfNode leaf(SdfPrimitive primitive) {
        return new Leaf(primitive);
    }

    /**
     * Create a union node (A ∪ B).
     *
     * @param left First child node
     * @param right Second child node
     * @return Union operation node
     */
    public static SdfNode union(SdfNode left, SdfNode right) {
        return new BinaryOp(left, right, Operation.UNION, 0.0f);
    }

    /**
     * Create a subtraction node (A \ B).
     *
     * @param left Base node (what to keep)
     * @param right Subtracted node (what to carve out)
     * @return Subtraction operation node
     */
    public static SdfNode subtract(SdfNode left, SdfNode right) {
        return new BinaryOp(left, right, Operation.SUBTRACT, 0.0f);
    }

    /**
     * Create an intersection node (A ∩ B).
     *
     * @param left First child node
     * @param right Second child node
     * @return Intersection operation node
     */
    public static SdfNode intersect(SdfNode left, SdfNode right) {
        return new BinaryOp(left, right, Operation.INTERSECT, 0.0f);
    }

    /**
     * Create a smooth union node with polynomial blending.
     *
     * @param left First child node
     * @param right Second child node
     * @param smoothness Blend radius in blocks (typically 4-16)
     * @return Smooth union operation node
     */
    public static SdfNode smoothUnion(SdfNode left, SdfNode right, float smoothness) {
        return new BinaryOp(left, right, Operation.SMOOTH_UNION, smoothness);
    }

    /**
     * Create a smooth subtraction node with polynomial blending.
     *
     * @param left Base node
     * @param right Subtracted node
     * @param smoothness Blend radius in blocks (typically 2-8)
     * @return Smooth subtraction operation node
     */
    public static SdfNode smoothSubtract(SdfNode left, SdfNode right, float smoothness) {
        return new BinaryOp(left, right, Operation.SMOOTH_SUBTRACT, smoothness);
    }

    /**
     * Create a smooth intersection node with polynomial blending.
     *
     * @param left First child node
     * @param right Second child node
     * @param smoothness Blend radius in blocks
     * @return Smooth intersection operation node
     */
    public static SdfNode smoothIntersect(SdfNode left, SdfNode right, float smoothness) {
        return new BinaryOp(left, right, Operation.SMOOTH_INTERSECT, smoothness);
    }

    // Leaf node implementation
    private static final class Leaf extends SdfNode {
        private final SdfPrimitive primitive;

        Leaf(SdfPrimitive primitive) {
            if (primitive == null) {
                throw new NullPointerException("Primitive cannot be null");
            }
            this.primitive = primitive;
        }

        @Override
        public float evaluate(float x, float y, float z) {
            return primitive.evaluate(x, y, z);
        }

        @Override
        public float[] getBounds() {
            return primitive.getBounds();
        }

        @Override
        public String toString() {
            return "Leaf[" + primitive + "]";
        }
    }

    // Binary operation node implementation
    private static final class BinaryOp extends SdfNode {
        private final SdfNode left;
        private final SdfNode right;
        private final Operation operation;
        private final float smoothness;
        private final float[] bounds;

        BinaryOp(SdfNode left, SdfNode right, Operation operation, float smoothness) {
            if (left == null || right == null) {
                throw new NullPointerException("Child nodes cannot be null");
            }
            if (operation == null) {
                throw new NullPointerException("Operation cannot be null");
            }

            this.left = left;
            this.right = right;
            this.operation = operation;
            this.smoothness = smoothness;

            // Compute conservative AABB (union of child bounds)
            float[] leftBounds = left.getBounds();
            float[] rightBounds = right.getBounds();
            this.bounds = new float[] {
                Math.min(leftBounds[0], rightBounds[0]),
                Math.min(leftBounds[1], rightBounds[1]),
                Math.min(leftBounds[2], rightBounds[2]),
                Math.max(leftBounds[3], rightBounds[3]),
                Math.max(leftBounds[4], rightBounds[4]),
                Math.max(leftBounds[5], rightBounds[5])
            };
        }

        @Override
        public float evaluate(float x, float y, float z) {
            float d1 = left.evaluate(x, y, z);
            float d2 = right.evaluate(x, y, z);

            return switch (operation) {
                case UNION -> SdfBlendOperations.union(d1, d2);
                case SUBTRACT -> SdfBlendOperations.subtract(d1, d2);
                case INTERSECT -> SdfBlendOperations.intersect(d1, d2);
                case SMOOTH_UNION -> SdfBlendOperations.smoothUnion(d1, d2, smoothness);
                case SMOOTH_SUBTRACT -> SdfBlendOperations.smoothSubtract(d1, d2, smoothness);
                case SMOOTH_INTERSECT -> SdfBlendOperations.smoothIntersect(d1, d2, smoothness);
            };
        }

        @Override
        public float[] getBounds() {
            return bounds;
        }

        @Override
        public String toString() {
            String opName = operation.name() + (smoothness > 0 ? "(k=" + smoothness + ")" : "");
            return "BinaryOp[" + opName + ", left=" + left + ", right=" + right + "]";
        }
    }
}
