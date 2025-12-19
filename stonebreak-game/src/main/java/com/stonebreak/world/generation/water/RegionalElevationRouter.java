package com.stonebreak.world.generation.water;

import com.stonebreak.world.generation.noise.NoiseRouter;

/**
 * Calculates expected regional elevation at any world position.
 *
 * @deprecated Functionality absorbed into {@link WaterLevelGrid} for better integration
 *             with grid-based water level calculation. Kept for reference only.
 *
 * Uses large-scale (3200-block) noise to determine what elevation the terrain
 * "should be" at a given location. Water bodies form in basins where actual
 * terrain is significantly below this expected elevation.
 *
 * This creates natural-looking water distribution:
 * - Ocean basins: Low regional elevation (Y=40-60), large flat areas below it
 * - Valley lakes: Medium regional elevation (Y=60-80), valleys below mountains
 * - Alpine lakes: High regional elevation (Y=90-104), rare high-altitude basins
 *
 * Follows Single Responsibility Principle - only calculates regional elevation.
 */
@Deprecated(since = "Project-Gaia", forRemoval = false)
public class RegionalElevationRouter {

    private final NoiseRouter noiseRouter;

    /**
     * Creates a new regional elevation router.
     *
     * @param noiseRouter Noise router for accessing regional elevation noise
     */
    public RegionalElevationRouter(NoiseRouter noiseRouter) {
        this.noiseRouter = noiseRouter;
    }

    /**
     * Gets the expected regional elevation at a world position.
     *
     * Maps noise range [-1.0, 1.0] to elevation range [40, 104]:
     * - noise = -1.0 → Y = 40 (ocean basin regions)
     * - noise =  0.0 → Y = 64 (mid-level regions)
     * - noise = +1.0 → Y = 104 (highland regions)
     *
     * Formula: Y = 64 + (noise * 32)
     *
     * This is sampled at 3200-block scale for continental-scale variation,
     * much larger than terrain features (which use 100-500 block scales).
     *
     * @param worldX World X coordinate
     * @param worldZ World Z coordinate
     * @return Expected elevation at this position (Y=40-104)
     */
    public int getRegionalElevation(int worldX, int worldZ) {
        float noise = noiseRouter.getRegionalElevationNoise(worldX, worldZ);

        // Map [-1.0, 1.0] to [40, 104], centered on Y=64
        // Y = 64 + (noise * 32)
        // Range: 64 - 32 = 32 (min), 64 + 32 = 96 (max)
        // Actually maps to [32, 96] but we clamp to [40, 104] for safety
        int elevation = (int) (64 + noise * 32);

        // Clamp to valid range (safety check, noise should already be in bounds)
        return Math.max(40, Math.min(104, elevation));
    }
}
