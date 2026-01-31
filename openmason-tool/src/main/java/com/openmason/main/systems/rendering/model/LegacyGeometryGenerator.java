package com.openmason.main.systems.rendering.model;

import com.openmason.main.systems.rendering.model.io.omo.OMOFormat;
import org.joml.Vector3f;

/**
 * DEPRECATED: Legacy geometry generator for backward compatibility with old BlockModel system.
 *
 * <p><strong>DO NOT USE THIS FOR NEW MODELS OR LOGIC.</strong></p>
 *
 * <p>This class exists ONLY to support the legacy single-cube BlockModel format.
 * New models should provide their own topology via .omo files or other explicit geometry sources.
 *
 * <p>GenericModelRenderer should NEVER generate geometry - it only loads and renders topology.
 * This generator bridges the gap between legacy BlockModel (which only stores dimensions)
 * and the modern renderer (which expects complete mesh data).
 *
 * @deprecated Use .omo files or explicit mesh data instead
 */
@Deprecated
public class LegacyGeometryGenerator {

    /**
     * LEGACY ONLY: Generate box mesh from dimensions for old BlockModel support.
     *
     * <p><strong>DO NOT USE FOR NEW MODELS.</strong> This method exists only to support
     * the deprecated BlockModel single-cube format.
     *
     * @param width Width in pixels
     * @param height Height in pixels
     * @param depth Depth in pixels
     * @param originX Origin X in units
     * @param originY Origin Y in units
     * @param originZ Origin Z in units
     * @param uvMode UV mapping mode
     * @return MeshData for the legacy box geometry
     * @deprecated Legacy BlockModel support only - do not use for new code
     */
    @Deprecated
    public static OMOFormat.MeshData generateLegacyBoxMesh(
            int width, int height, int depth,
            double originX, double originY, double originZ,
            UVMode uvMode) {

        // Convert pixel dimensions to units (16 pixels = 1 unit)
        Vector3f size = new Vector3f(width / 16f, height / 16f, depth / 16f);
        Vector3f origin = new Vector3f((float) originX, (float) originY, (float) originZ);

        // Generate cube geometry using deprecated ModelPart factory (legacy support only)
        @SuppressWarnings("deprecation")
        ModelPart part = ModelPart.createCube("legacy_box", origin, size, uvMode);

        // Generate proper face mapping for quad topology (2 triangles per face = 6 faces)
        // Without this, renderer falls back to 1:1 mapping (12 triangles = 12 faces)
        int[] triangleToFaceId = generateQuadTopologyMapping(part.indices().length / 3);

        return new OMOFormat.MeshData(
            part.vertices(),
            part.texCoords(),
            part.indices(),
            triangleToFaceId,
            uvMode.name()
        );
    }

    /**
     * Generate triangle-to-face mapping for quad topology.
     * Each face consists of 2 triangles (quad topology).
     *
     * <p>For a 24-vertex cube with 36 indices (12 triangles):
     * <ul>
     *   <li>Triangles 0,1 → Face 0 (front)</li>
     *   <li>Triangles 2,3 → Face 1 (back)</li>
     *   <li>Triangles 4,5 → Face 2 (left)</li>
     *   <li>Triangles 6,7 → Face 3 (right)</li>
     *   <li>Triangles 8,9 → Face 4 (top)</li>
     *   <li>Triangles 10,11 → Face 5 (bottom)</li>
     * </ul>
     *
     * @param triangleCount Number of triangles
     * @return Mapping array where each pair of triangles maps to the same face
     */
    private static int[] generateQuadTopologyMapping(int triangleCount) {
        int[] mapping = new int[triangleCount];
        for (int i = 0; i < triangleCount; i++) {
            mapping[i] = i / 2; // Every 2 triangles = 1 face
        }
        return mapping;
    }
}
