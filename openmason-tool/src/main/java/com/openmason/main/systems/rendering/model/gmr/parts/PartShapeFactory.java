package com.openmason.main.systems.rendering.model.gmr.parts;

import com.openmason.main.systems.rendering.model.ModelPart;
import com.openmason.main.systems.rendering.model.UVMode;
import com.openmason.main.systems.rendering.model.gmr.uv.UVCoordinateGenerator;
import org.joml.Vector3f;

/**
 * Factory for creating standard part shapes.
 * Each shape produces a {@link ModelPart} with proper geometry, UV coordinates,
 * and topology hints ready for registration with the {@link ModelPartManager}.
 *
 * <p>All shapes are centered at the origin with configurable size.
 */
public final class PartShapeFactory {

    private static final UVCoordinateGenerator UV_GENERATOR = new UVCoordinateGenerator();

    private PartShapeFactory() {
        throw new UnsupportedOperationException("Factory class");
    }

    /**
     * Available primitive shapes for new model parts.
     */
    public enum Shape {
        CUBE("Cube", "Standard 6-face cube with quad topology"),
        PYRAMID("Pyramid", "4-sided pyramid with triangular faces and a quad base");

        private final String displayName;
        private final String description;

        Shape(String displayName, String description) {
            this.displayName = displayName;
            this.description = description;
        }

        public String getDisplayName() { return displayName; }
        public String getDescription() { return description; }
    }

    /**
     * Create a model part for the given shape.
     *
     * @param shape The primitive shape to create
     * @param name  Part name
     * @param size  Size in each axis (width, height, depth)
     * @return A ModelPart with the shape's geometry
     */
    public static ModelPart create(Shape shape, String name, Vector3f size) {
        return switch (shape) {
            case CUBE -> createCube(name, size);
            case PYRAMID -> createPyramid(name, size);
        };
    }

    /**
     * Create a unit-sized (1x1x1) model part for the given shape.
     *
     * @param shape The primitive shape
     * @param name  Part name
     * @return A ModelPart with unit-sized geometry
     */
    public static ModelPart createUnit(Shape shape, String name) {
        return create(shape, name, new Vector3f(1, 1, 1));
    }

    // ========== Cube ==========

    /**
     * Create a cube with the same mesh as the generic base model.
     * 24 vertices (4 per face), 36 indices, 6 quad faces, cube net UVs.
     */
    @SuppressWarnings("deprecation")
    private static ModelPart createCube(String name, Vector3f size) {
        return ModelPart.createCube(name, new Vector3f(0, 0, 0), size, UVMode.CUBE_NET);
    }

    // ========== Pyramid ==========

    /**
     * Create a 4-sided pyramid with an apex at the top center.
     * <p>
     * Geometry: 5 unique positions (4 base corners + 1 apex).
     * Expanded to 16 vertices for proper per-face normals/UVs.
     * <ul>
     *   <li>4 triangular side faces (3 vertices each = 12 vertices)</li>
     *   <li>1 quad base face (4 vertices = 4 vertices, 2 triangles)</li>
     *   <li>Total: 16 vertices, 18 indices, 5 logical faces</li>
     *   <li>Topology: mixed (sides are 1 tri/face, base is 2 tri/face)</li>
     * </ul>
     */
    private static ModelPart createPyramid(String name, Vector3f size) {
        float hw = size.x / 2.0f;
        float hh = size.y / 2.0f;
        float hd = size.z / 2.0f;

        // 5 unique positions
        // Base corners (y = -hh)
        float bfl_x = -hw, bfl_y = -hh, bfl_z =  hd; // base front-left
        float bfr_x =  hw, bfr_y = -hh, bfr_z =  hd; // base front-right
        float bbr_x =  hw, bbr_y = -hh, bbr_z = -hd; // base back-right
        float bbl_x = -hw, bbl_y = -hh, bbl_z = -hd; // base back-left
        // Apex (y = +hh)
        float ax = 0, ay = hh, az = 0;

        // 16 vertices: 4 side faces (3 verts each) + 1 base face (4 verts)
        float[] vertices = {
            // Face 0: Front side (apex, front-left, front-right)
            ax,    ay,    az,
            bfl_x, bfl_y, bfl_z,
            bfr_x, bfr_y, bfr_z,

            // Face 1: Right side (apex, front-right, back-right)
            ax,    ay,    az,
            bfr_x, bfr_y, bfr_z,
            bbr_x, bbr_y, bbr_z,

            // Face 2: Back side (apex, back-right, back-left)
            ax,    ay,    az,
            bbr_x, bbr_y, bbr_z,
            bbl_x, bbl_y, bbl_z,

            // Face 3: Left side (apex, back-left, front-left)
            ax,    ay,    az,
            bbl_x, bbl_y, bbl_z,
            bfl_x, bfl_y, bfl_z,

            // Face 4: Base (quad: front-left, front-right, back-right, back-left)
            bfl_x, bfl_y, bfl_z,
            bfr_x, bfr_y, bfr_z,
            bbr_x, bbr_y, bbr_z,
            bbl_x, bbl_y, bbl_z,
        };

        // UV coordinates: simple per-face mapping
        // Side faces get a triangle region, base gets full quad
        float[] texCoords = {
            // Face 0: Front side triangle
            0.5f, 0.0f,   0.0f, 1.0f,   1.0f, 1.0f,
            // Face 1: Right side triangle
            0.5f, 0.0f,   0.0f, 1.0f,   1.0f, 1.0f,
            // Face 2: Back side triangle
            0.5f, 0.0f,   0.0f, 1.0f,   1.0f, 1.0f,
            // Face 3: Left side triangle
            0.5f, 0.0f,   0.0f, 1.0f,   1.0f, 1.0f,
            // Face 4: Base quad
            0.0f, 0.0f,   1.0f, 0.0f,   1.0f, 1.0f,   0.0f, 1.0f,
        };

        // Indices: 4 side triangles + 2 base triangles = 6 triangles, 18 indices
        int[] indices = {
            // Face 0: Front side (1 triangle)
            0, 1, 2,
            // Face 1: Right side (1 triangle)
            3, 4, 5,
            // Face 2: Back side (1 triangle)
            6, 7, 8,
            // Face 3: Left side (1 triangle)
            9, 10, 11,
            // Face 4: Base (2 triangles)
            12, 13, 14,
            14, 15, 12,
        };

        // Triangle-to-face mapping: faces 0-3 are 1 triangle each, face 4 is 2 triangles
        // Cannot use trianglesPerFace hint (mixed topology) — must be explicit
        // This is null so addPart will use explicit mapping or 1:1 default;
        // we'll provide it explicitly via PartGeometry instead
        return new ModelPart(name, new Vector3f(0, 0, 0), vertices, texCoords, indices, null);
    }

    /**
     * Create a PartGeometry for the given shape with a proper triangle-to-face mapping.
     * Use this instead of {@link #create} when adding parts to the ModelPartManager
     * to preserve the exact face mapping.
     *
     * @param shape The primitive shape
     * @param name  Part name (unused in geometry, kept for consistency)
     * @param size  Size in each axis
     * @return PartGeometry with correct face mapping
     */
    public static PartMeshRebuilder.PartGeometry createGeometry(Shape shape, String name, Vector3f size) {
        ModelPart part = create(shape, name, size);

        int[] triangleToFaceId = switch (shape) {
            case CUBE -> {
                // 12 triangles, 6 faces, 2 triangles per face
                int[] mapping = new int[12];
                for (int i = 0; i < 12; i++) {
                    mapping[i] = i / 2;
                }
                yield mapping;
            }
            case PYRAMID -> {
                // 6 triangles: faces 0-3 get 1 triangle each, face 4 gets 2 triangles
                yield new int[]{0, 1, 2, 3, 4, 4};
            }
        };

        return PartMeshRebuilder.PartGeometry.of(
                part.vertices(), part.texCoords(), part.indices(), triangleToFaceId
        );
    }
}
