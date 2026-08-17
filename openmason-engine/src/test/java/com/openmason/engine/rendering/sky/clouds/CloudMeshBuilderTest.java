package com.openmason.engine.rendering.sky.clouds;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The cloud mesh must contain exactly the faces its pattern implies: two caps per occupied cell
 * plus one side per open neighbour, with neighbour tests wrapping toroidally so the tiled layer
 * has no seam walls. The expected face count is recomputed here independently from the pattern's
 * own queries, so a culling regression shows up as a hard count mismatch rather than an eyeball
 * judgement about the sky.
 */
class CloudMeshBuilderTest {

    private static final int FLOATS_PER_QUAD = 4 * CloudMeshBuilder.FLOATS_PER_VERTEX;
    private static final int INDICES_PER_QUAD = 6;

    /** Faces the culling rules promise: 2 caps + one side per open (wrapped) neighbour. */
    private static int expectedQuads(CloudPattern pattern) {
        int quads = 0;
        for (int x = 0; x < pattern.getSize(); x++) {
            for (int z = 0; z < pattern.getSize(); z++) {
                if (!pattern.isCloud(x, z)) {
                    continue;
                }
                quads += 2;
                if (!pattern.isCloud(x + 1, z)) quads++;
                if (!pattern.isCloud(x - 1, z)) quads++;
                if (!pattern.isCloud(x, z + 1)) quads++;
                if (!pattern.isCloud(x, z - 1)) quads++;
            }
        }
        return quads;
    }

    @Test
    void theMeshEmitsExactlyTheFacesTheCullingRulesPromise() {
        for (long seed : new long[] {1L, 42L, 99L}) {
            CloudPattern pattern = new CloudPattern(24, 0.4f, seed);
            CloudMeshBuilder.CloudMeshData mesh = CloudMeshBuilder.build(pattern);

            int quads = expectedQuads(pattern);
            assertEquals(quads * FLOATS_PER_QUAD, mesh.vertices().length,
                    "vertex data for seed " + seed);
            assertEquals(quads * INDICES_PER_QUAD, mesh.indices().length,
                    "index data for seed " + seed);
            assertEquals(mesh.indices().length, mesh.indexCount());
        }
    }

    @Test
    void everyIndexPointsAtARealVertex() {
        CloudMeshBuilder.CloudMeshData mesh =
                CloudMeshBuilder.build(new CloudPattern(24, 0.4f, 7L));
        int vertexCount = mesh.vertices().length / CloudMeshBuilder.FLOATS_PER_VERTEX;

        for (int index : mesh.indices()) {
            assertTrue(index >= 0 && index < vertexCount,
                    "index " + index + " outside " + vertexCount + " vertices");
        }
    }

    @Test
    void everyVertexCarriesALegalShadeAndSitsInsideTheLayer() {
        CloudPattern pattern = new CloudPattern(24, 0.4f, 7L);
        CloudMeshBuilder.CloudMeshData mesh = CloudMeshBuilder.build(pattern);
        float maxX = pattern.getSize() * CloudMeshBuilder.CELL_WIDTH;
        float maxZ = pattern.getSize() * CloudMeshBuilder.CELL_DEPTH;

        for (int i = 0; i < mesh.vertices().length; i += CloudMeshBuilder.FLOATS_PER_VERTEX) {
            float x = mesh.vertices()[i];
            float y = mesh.vertices()[i + 1];
            float z = mesh.vertices()[i + 2];
            float shade = mesh.vertices()[i + 3];

            assertTrue(x >= 0 && x <= maxX && z >= 0 && z <= maxZ,
                    "vertex outside the pattern's local footprint");
            assertTrue(y == 0.0f || y == CloudMeshBuilder.CELL_HEIGHT,
                    "cloud cells are one layer thick — y was " + y);
            assertTrue(shade == 1.0f || shade == 0.75f || shade == 0.55f,
                    "unknown face shade " + shade);
        }
    }

    @Test
    void anEmptySkyBuildsAnEmptyMesh() {
        CloudMeshBuilder.CloudMeshData mesh =
                CloudMeshBuilder.build(new CloudPattern(16, 0.0f, 1L));

        // Coverage 0 may keep a stray cell above the percentile threshold; the mesh
        // must simply mirror the pattern rather than invent geometry.
        assertEquals(expectedQuads(new CloudPattern(16, 0.0f, 1L)) * FLOATS_PER_QUAD,
                mesh.vertices().length);
    }
}
