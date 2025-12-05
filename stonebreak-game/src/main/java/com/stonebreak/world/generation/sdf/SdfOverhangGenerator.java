package com.stonebreak.world.generation.sdf;

import com.stonebreak.world.generation.sdf.primitives.SdfCapsule;

import java.util.Random;

/**
 * Generates dramatic cliff overhangs using SDF primitives.
 *
 * <p>Overhang generation analyzes terrain slope to detect cliffs, then places
 * horizontal capsule primitives extending from cliff faces. This creates
 * natural-looking overhangs that are impossible with 2D heightmaps.</p>
 *
 * <p><b>Integration with Factor Spline:</b></p>
 * <p>Overhang intensity is controlled by the existing FactorSplineRouter from
 * the spline terrain system. Higher factor values create more pronounced overhangs:</p>
 * <ul>
 *   <li>Factor &lt; 0.5: No overhangs (fast rejection)</li>
 *   <li>Factor 0.5-1.0: Subtle overhangs</li>
 *   <li>Factor 1.0-2.0: Moderate overhangs</li>
 *   <li>Factor &gt; 2.0: Dramatic overhangs (swiss-cheese mountains)</li>
 * </ul>
 *
 * <p><b>Performance:</b></p>
 * <p>Overhang evaluation is factor-gated - when factor &lt; 0.5, no SDF evaluation
 * occurs. This keeps performance high in non-overhang regions.</p>
 *
 * <p><b>Thread Safety:</b></p>
 * <p>Not thread-safe. Each chunk should have its own instance.</p>
 */
public class SdfOverhangGenerator {

    private final long seed;
    private final SpatialHashGrid<SdfPrimitive> overhangGrid;

    private static final float SLOPE_THRESHOLD = 0.5f;          // tan(angle) for cliff detection
    private static final float OVERHANG_LENGTH_MIN = 3.0f;
    private static final float OVERHANG_LENGTH_MAX = 8.0f;
    private static final float OVERHANG_THICKNESS_MIN = 1.5f;
    private static final float OVERHANG_THICKNESS_MAX = 3.0f;
    private static final int OVERHANG_DETECTION_RADIUS = 4;     // Blocks to check for slope

    /**
     * Creates an overhang generator for a chunk region.
     *
     * @param seed World seed for deterministic generation
     */
    public SdfOverhangGenerator(long seed) {
        this.seed = seed;
        this.overhangGrid = new SpatialHashGrid<>(16);
    }

    /**
     * Generate overhangs for a chunk based on heightmap analysis.
     *
     * <p>Analyzes terrain slope to detect cliffs, then places overhang primitives
     * at steep locations. This should be called once per chunk before block evaluation.</p>
     *
     * @param chunkX Chunk X coordinate
     * @param chunkZ Chunk Z coordinate
     * @param heightmap 16×16 heightmap for this chunk (local coordinates)
     * @param factorMap 16×16 factor values from FactorSplineRouter (controls overhang intensity)
     */
    public void generateOverhangs(int chunkX, int chunkZ, float[][] heightmap, float[][] factorMap) {
        int chunkBlockX = chunkX * 16;
        int chunkBlockZ = chunkZ * 16;

        // Scan heightmap for cliffs (steep slopes)
        for (int x = OVERHANG_DETECTION_RADIUS; x < 16 - OVERHANG_DETECTION_RADIUS; x++) {
            for (int z = OVERHANG_DETECTION_RADIUS; z < 16 - OVERHANG_DETECTION_RADIUS; z++) {
                float factor = factorMap[x][z];

                // Fast rejection: no overhangs at low factor
                if (factor < 0.5f) {
                    continue;
                }

                // Calculate terrain slope
                float slope = calculateSlope(heightmap, x, z);

                // Only create overhangs on steep slopes (cliffs)
                if (slope > SLOPE_THRESHOLD) {
                    float height = heightmap[x][z];
                    int worldX = chunkBlockX + x;
                    int worldZ = chunkBlockZ + z;

                    // Generate overhang primitive
                    generateOverhangPrimitive(worldX, (int) height, worldZ, slope, factor);
                }
            }
        }
    }

    /**
     * Calculate terrain slope at a heightmap position.
     *
     * <p>Uses central difference to compute gradient magnitude.</p>
     *
     * @param heightmap Chunk heightmap
     * @param x Local X coordinate (0-15)
     * @param z Local Z coordinate (0-15)
     * @return Slope magnitude (0 = flat, higher = steeper)
     */
    private float calculateSlope(float[][] heightmap, int x, int z) {
        // Sample heights in 4 cardinal directions
        float h_center = heightmap[x][z];
        float h_east = heightmap[x + 1][z];
        float h_west = heightmap[x - 1][z];
        float h_north = heightmap[x][z + 1];
        float h_south = heightmap[x][z - 1];

        // Calculate gradient
        float dx = (h_east - h_west) / 2.0f;
        float dz = (h_north - h_south) / 2.0f;

        // Slope magnitude
        return (float) Math.sqrt(dx * dx + dz * dz);
    }

    /**
     * Calculate surface normal vector at a heightmap position.
     *
     * @param heightmap Chunk heightmap
     * @param x Local X coordinate
     * @param z Local Z coordinate
     * @return Normalized normal vector {nx, ny, nz}
     */
    private float[] calculateNormal(float[][] heightmap, int x, int z) {
        // Sample heights in 4 cardinal directions
        float h_center = heightmap[x][z];
        float h_east = heightmap[x + 1][z];
        float h_west = heightmap[x - 1][z];
        float h_north = heightmap[x][z + 1];
        float h_south = heightmap[x][z - 1];

        // Calculate gradient
        float dx = (h_east - h_west) / 2.0f;
        float dz = (h_north - h_south) / 2.0f;

        // Normal vector (cross product of tangent vectors)
        float nx = -dx;
        float ny = 1.0f; // Vertical component
        float nz = -dz;

        // Normalize
        float length = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
        return new float[] { nx / length, ny / length, nz / length };
    }

    /**
     * Generate a single overhang primitive at a cliff location.
     *
     * @param worldX World X coordinate
     * @param height Surface height
     * @param worldZ World Z coordinate
     * @param slope Terrain slope
     * @param factor Factor value (controls overhang intensity)
     */
    private void generateOverhangPrimitive(int worldX, int height, int worldZ, float slope, float factor) {
        Random rng = new Random(hashCoords(worldX, worldZ, seed));

        // Overhang extends horizontally from cliff face
        float length = OVERHANG_LENGTH_MIN + (OVERHANG_LENGTH_MAX - OVERHANG_LENGTH_MIN) * Math.min(factor, 1.5f) / 1.5f;
        length *= (0.8f + rng.nextFloat() * 0.4f); // Randomize ±20%

        float thickness = OVERHANG_THICKNESS_MIN + (OVERHANG_THICKNESS_MAX - OVERHANG_THICKNESS_MIN) * factor / 2.0f;

        // Random horizontal direction (prefer perpendicular to slope)
        float angle = rng.nextFloat() * 2.0f * (float) Math.PI;
        float dx = (float) Math.cos(angle) * length;
        float dz = (float) Math.sin(angle) * length;

        // Overhang starts slightly below surface, extends outward
        float startY = height - 2.0f - rng.nextFloat() * 2.0f;
        float endY = startY - rng.nextFloat() * 1.5f; // Slight downward slope

        // Create horizontal capsule
        SdfCapsule overhang = new SdfCapsule(
            worldX, startY, worldZ,
            worldX + dx, endY, worldZ + dz,
            thickness
        );

        overhangGrid.insert(overhang);
    }

    /**
     * Sample overhang SDF at a position.
     *
     * <p>Returns positive density where overhang material exists (adds terrain).
     * Factor-gated for performance.</p>
     *
     * @param x World X coordinate
     * @param y World Y coordinate
     * @param z World Z coordinate
     * @param factor Factor value from FactorSplineRouter
     * @return Overhang density (0 = no overhang, positive = add terrain)
     */
    public float sampleOverhang(float x, float y, float z, float factor) {
        // Fast rejection: no overhangs at low factor
        if (factor < 0.5f) {
            return 0.0f;
        }

        // Query spatial hash for nearby overhangs
        if (!overhangGrid.hasNearbyPrimitives(x, y, z)) {
            return 0.0f;
        }

        var nearbyOverhangs = overhangGrid.query(x, y, z);
        if (nearbyOverhangs.isEmpty()) {
            return 0.0f;
        }

        // Find minimum distance to any overhang
        float minDist = Float.MAX_VALUE;
        for (SdfPrimitive overhang : nearbyOverhangs) {
            float dist = overhang.evaluate(x, y, z);
            minDist = Math.min(minDist, dist);
        }

        // Inside overhang = add terrain (negative SDF = positive density)
        // Scale by factor for smooth blending
        if (minDist < 0) {
            return -minDist * Math.min(factor, 1.0f);
        }

        return 0.0f;
    }

    /**
     * Clear overhang primitives (call after chunk completes).
     */
    public void clear() {
        overhangGrid.clear();
    }

    /**
     * Get overhang grid statistics (for debugging).
     * @return Grid statistics string
     */
    public String getGridStats() {
        return overhangGrid.toString();
    }

    /**
     * Hash coordinates for deterministic random generation.
     */
    private long hashCoords(int x, int z, long seed) {
        long hash = seed;
        hash ^= x * 1619;
        hash ^= z * 31337;
        hash ^= hash >>> 32;
        return hash;
    }
}
