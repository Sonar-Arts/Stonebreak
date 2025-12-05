package com.stonebreak.world.generation.sdf;

import com.stonebreak.world.generation.sdf.primitives.SdfCylinder;
import com.stonebreak.world.generation.sdf.primitives.SdfSphere;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Generates natural stone arches at high-weirdness cliff locations.
 *
 * <p>Arches are created by subtracting cylindrical or spherical voids from
 * cliff faces, creating dramatic natural bridges and windows. This feature
 * was present in the original TerrainDensityFunction but limited by noise-based
 * generation. SDFs enable more precise and dramatic arches.</p>
 *
 * <p><b>Triggering Conditions:</b></p>
 * <ul>
 *   <li>Weirdness &gt; 0.7 (high weirdness parameter)</li>
 *   <li>Steep slope (cliff face detected)</li>
 *   <li>Appropriate height range (not too deep underground)</li>
 * </ul>
 *
 * <p><b>Arch Types:</b></p>
 * <ul>
 *   <li><b>Horizontal Tunnel:</b> Cylinder cutting through cliff horizontally</li>
 *   <li><b>Natural Bridge:</b> Sphere void creating arch opening</li>
 * </ul>
 *
 * <p><b>Performance:</b></p>
 * <p>Arch evaluation is weirdness-gated - no evaluation when weirdness &lt; 0.7.</p>
 *
 * <p><b>Thread Safety:</b></p>
 * <p>Not thread-safe. Each chunk should have its own instance.</p>
 */
public class SdfArchGenerator {

    private final long seed;
    private final SpatialHashGrid<SdfPrimitive> archGrid;

    // Arch generation parameters
    private static final float WEIRDNESS_THRESHOLD = 0.7f;     // High weirdness required
    private static final float SLOPE_THRESHOLD = 0.6f;         // Steeper than overhangs
    private static final float ARCH_RADIUS_MIN = 3.0f;
    private static final float ARCH_RADIUS_MAX = 6.0f;
    private static final float ARCH_DEPTH_MIN = 5.0f;          // How far arch cuts into cliff
    private static final float ARCH_DEPTH_MAX = 12.0f;
    private static final int ARCH_MIN_Y = 80;                  // Arches only in mid-to-high altitude
    private static final int ARCH_DETECTION_RADIUS = 4;

    /**
     * Creates an arch generator for a chunk region.
     *
     * @param seed World seed for deterministic generation
     */
    public SdfArchGenerator(long seed) {
        this.seed = seed;
        this.archGrid = new SpatialHashGrid<>(16);
    }

    /**
     * Generate arches for a chunk based on heightmap and weirdness analysis.
     *
     * <p>Scans for high-weirdness cliff locations and places arch void primitives.
     * These voids will be subtracted from terrain to create arch openings.</p>
     *
     * @param chunkX Chunk X coordinate
     * @param chunkZ Chunk Z coordinate
     * @param heightmap 16×16 heightmap for this chunk (local coordinates)
     * @param weirdnessMap 16×16 weirdness values from multi-noise parameters
     */
    public void generateArches(int chunkX, int chunkZ, float[][] heightmap, float[][] weirdnessMap) {
        int chunkBlockX = chunkX * 16;
        int chunkBlockZ = chunkZ * 16;

        // Scan heightmap for high-weirdness cliffs
        for (int x = ARCH_DETECTION_RADIUS; x < 16 - ARCH_DETECTION_RADIUS; x++) {
            for (int z = ARCH_DETECTION_RADIUS; z < 16 - ARCH_DETECTION_RADIUS; z++) {
                float weirdness = weirdnessMap[x][z];

                // Fast rejection: need high weirdness
                if (weirdness < WEIRDNESS_THRESHOLD) {
                    continue;
                }

                float height = heightmap[x][z];

                // Only generate arches in mid-to-high altitude (dramatic visibility)
                if (height < ARCH_MIN_Y) {
                    continue;
                }

                // Calculate terrain slope
                float slope = calculateSlope(heightmap, x, z);

                // Need steep cliff for arch
                if (slope > SLOPE_THRESHOLD) {
                    int worldX = chunkBlockX + x;
                    int worldZ = chunkBlockZ + z;

                    // Generate arch primitive with some randomness to avoid all chunks having arches
                    Random rng = new Random(hashCoords(worldX, worldZ, seed));
                    if (rng.nextFloat() < 0.3f) { // 30% chance at valid locations
                        generateArchPrimitive(worldX, (int) height, worldZ, slope, weirdness, heightmap, x, z);
                    }
                }
            }
        }
    }

    /**
     * Calculate terrain slope (same as overhang generator).
     */
    private float calculateSlope(float[][] heightmap, int x, int z) {
        float h_east = heightmap[x + 1][z];
        float h_west = heightmap[x - 1][z];
        float h_north = heightmap[x][z + 1];
        float h_south = heightmap[x][z - 1];

        float dx = (h_east - h_west) / 2.0f;
        float dz = (h_north - h_south) / 2.0f;

        return (float) Math.sqrt(dx * dx + dz * dz);
    }

    /**
     * Calculate surface normal direction.
     */
    private float[] calculateNormal(float[][] heightmap, int x, int z) {
        float h_east = heightmap[Math.min(x + 1, 15)][z];
        float h_west = heightmap[Math.max(x - 1, 0)][z];
        float h_north = heightmap[x][Math.min(z + 1, 15)];
        float h_south = heightmap[x][Math.max(z - 1, 0)];

        float dx = (h_east - h_west) / 2.0f;
        float dz = (h_north - h_south) / 2.0f;

        float nx = -dx;
        float ny = 1.0f;
        float nz = -dz;

        float length = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
        if (length < 0.001f) length = 1.0f; // Avoid divide by zero

        return new float[] { nx / length, ny / length, nz / length };
    }

    /**
     * Generate a single arch primitive at a high-weirdness cliff location.
     *
     * @param worldX World X coordinate
     * @param height Surface height
     * @param worldZ World Z coordinate
     * @param slope Terrain slope
     * @param weirdness Weirdness parameter value
     * @param heightmap Heightmap for normal calculation
     * @param localX Local X coordinate in heightmap
     * @param localZ Local Z coordinate in heightmap
     */
    private void generateArchPrimitive(int worldX, int height, int worldZ,
                                        float slope, float weirdness,
                                        float[][] heightmap, int localX, int localZ) {
        Random rng = new Random(hashCoords(worldX, worldZ, seed));

        // Arch void radius (larger at higher weirdness)
        float radius = ARCH_RADIUS_MIN + (ARCH_RADIUS_MAX - ARCH_RADIUS_MIN) *
                      ((weirdness - WEIRDNESS_THRESHOLD) / (1.0f - WEIRDNESS_THRESHOLD));
        radius *= (0.8f + rng.nextFloat() * 0.4f); // Randomize ±20%

        // How far arch cuts into cliff
        float depth = ARCH_DEPTH_MIN + (ARCH_DEPTH_MAX - ARCH_DEPTH_MIN) * weirdness;

        // Calculate normal direction (perpendicular to cliff face)
        float[] normal = calculateNormal(heightmap, localX, localZ);

        // Arch opening position (slightly below surface)
        float archY = height - 5.0f - rng.nextFloat() * 3.0f;

        // Choose arch type
        if (rng.nextFloat() < 0.6f) {
            // Horizontal tunnel arch (cylinder)
            generateTunnelArch(worldX, archY, worldZ, normal, radius, depth);
        } else {
            // Spherical void arch (more dramatic)
            generateSphereArch(worldX, archY, worldZ, normal, radius, depth);
        }
    }

    /**
     * Generate a tunnel-style arch using horizontal cylinder.
     */
    private void generateTunnelArch(float x, float y, float z, float[] normal,
                                     float radius, float depth) {
        // Cylinder extends in horizontal direction (perpendicular to cliff normal)
        // Use horizontal component of normal for direction
        float horizLength = (float) Math.sqrt(normal[0] * normal[0] + normal[2] * normal[2]);
        if (horizLength < 0.001f) horizLength = 1.0f;

        float dirX = normal[0] / horizLength;
        float dirZ = normal[2] / horizLength;

        // Cylinder cuts through cliff
        SdfCylinder archCylinder = new SdfCylinder(
            x + dirX * depth / 2.0f,
            y,
            z + dirZ * depth / 2.0f,
            radius,
            depth / 2.0f  // Half-height (vertical extent)
        );

        archGrid.insert(archCylinder);
    }

    /**
     * Generate a spherical arch (natural bridge opening).
     */
    private void generateSphereArch(float x, float y, float z, float[] normal,
                                     float radius, float depth) {
        // Sphere positioned to create arch opening
        // Center slightly into the cliff
        float centerX = x + normal[0] * depth * 0.3f;
        float centerY = y;
        float centerZ = z + normal[2] * depth * 0.3f;

        SdfSphere archSphere = new SdfSphere(centerX, centerY, centerZ, radius);
        archGrid.insert(archSphere);
    }

    /**
     * Sample arch SDF at a position.
     *
     * <p>Returns negative density where arch void exists (subtracts from terrain).
     * Weirdness-gated for performance.</p>
     *
     * @param x World X coordinate
     * @param y World Y coordinate
     * @param z World Z coordinate
     * @param weirdness Weirdness parameter value
     * @return Arch density (0 = no arch, negative = carve out terrain)
     */
    public float sampleArch(float x, float y, float z, float weirdness) {
        // Fast rejection: need high weirdness for arches
        if (weirdness < WEIRDNESS_THRESHOLD) {
            return 0.0f;
        }

        // Query spatial hash for nearby arches
        if (!archGrid.hasNearbyPrimitives(x, y, z)) {
            return 0.0f;
        }

        var nearbyArches = archGrid.query(x, y, z);
        if (nearbyArches.isEmpty()) {
            return 0.0f;
        }

        // Find minimum distance to any arch void
        float minDist = Float.MAX_VALUE;
        for (SdfPrimitive arch : nearbyArches) {
            float dist = arch.evaluate(x, y, z);
            minDist = Math.min(minDist, dist);
        }

        // Inside arch void = subtract terrain (negative SDF = negative density)
        // This carves out the arch opening
        if (minDist < 0) {
            return minDist; // Return negative value to subtract from terrain
        }

        return 0.0f;
    }

    /**
     * Clear arch primitives (call after chunk completes).
     */
    public void clear() {
        archGrid.clear();
    }

    /**
     * Get arch grid statistics (for debugging).
     * @return Grid statistics string
     */
    public String getGridStats() {
        return archGrid.toString();
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
