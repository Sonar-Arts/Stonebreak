package com.stonebreak.world.structure.finders;

import com.stonebreak.world.World;
import com.stonebreak.world.generation.water.basin.BasinWaterLevelGrid;
import com.stonebreak.world.structure.StructureSearchConfig;
import com.stonebreak.world.structure.StructureSearchResult;
import com.stonebreak.world.structure.StructureType;
import org.joml.Vector3f;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Strategy for finding lakes (elevated water bodies at y >= 65).
 *
 * <p>This finder uses the same lake detection algorithm as the terrain generator,
 * ensuring consistency between generation and finding. Lakes are detected via:</p>
 * <ul>
 *     <li>Terrain height >= 65</li>
 *     <li>Humidity >= 0.3</li>
 *     <li>Temperature between 0.1 and 0.95</li>
 *     <li>Basin depth >= 3 blocks (via rim detection)</li>
 *     <li>Elevation probability: P(y) = e^(-0.03 * (y - 65))</li>
 * </ul>
 *
 * <p><strong>Algorithm:</strong></p>
 * <ol>
 *     <li>Start at player position, snap to grid (256-block resolution)</li>
 *     <li>Expand outward in concentric square rings</li>
 *     <li>For each grid point, check if lake exists via {@link BasinWaterLevelGrid}</li>
 *     <li>Track closest lake found</li>
 *     <li>Early exit: stop after finding lake + checking next ring</li>
 * </ol>
 *
 * <p><strong>Performance:</strong></p>
 * <ul>
 *     <li>Grid resolution: 256 blocks (matches terrain generation)</li>
 *     <li>Search radius 2048: ~260 grid points worst case (no lake)</li>
 *     <li>Typical case: ~100 grid points (lake found in 3-5 rings)</li>
 *     <li>Estimated time: ~5-10s typical (with climate pre-filtering)</li>
 *     <li>Note: Basin rim detection is expensive (~300 terrain samples per point)</li>
 * </ul>
 *
 * @see BasinWaterLevelGrid
 * @see com.stonebreak.world.generation.water.BasinWaterFiller
 */
public class LakeFinderStrategy implements StructureFinderStrategy {

    private final World world;
    private final BasinWaterLevelGrid basinGrid;

    /**
     * Grid resolution in blocks.
     * Must match terrain generation grid resolution (256 blocks).
     */
    private static final int GRID_RESOLUTION = 256;

    /**
     * Minimum elevation for basin water (lakes).
     * Must match WaterGenerationConfig.basinMinimumElevation.
     */
    private static final int MIN_LAKE_ELEVATION = 65;

    /**
     * Creates a lake finder strategy for the specified world.
     *
     * @param world The world to search in (must not be null)
     * @throws IllegalArgumentException if world is null
     */
    public LakeFinderStrategy(World world) {
        if (world == null) {
            throw new IllegalArgumentException("World cannot be null");
        }

        this.world = world;

        // Access the actual basin grid from terrain system
        this.basinGrid = world.getTerrainGenerationSystem()
            .getBasinWaterFiller()
            .getBasinWaterLevelGrid();
    }

    @Override
    public Optional<StructureSearchResult> findNearest(Vector3f origin, StructureSearchConfig config) {
        if (origin == null) {
            throw new IllegalArgumentException("Origin cannot be null");
        }
        if (config == null) {
            throw new IllegalArgumentException("Config cannot be null");
        }

        long startTime = System.currentTimeMillis();
        System.out.println("[LakeFinder] Starting search from (" + origin.x + ", " + origin.z +
                           ") radius=" + config.searchRadius());

        // Snap origin to grid alignment (center of grid cell)
        int startGridX = snapToGrid((int) origin.x);
        int startGridZ = snapToGrid((int) origin.z);

        StructureSearchResult closest = null;
        float closestDistSq = Float.MAX_VALUE;

        // Calculate maximum rings to search
        int maxRings = (int) Math.ceil(config.searchRadius() / (double) GRID_RESOLUTION);
        System.out.println("[LakeFinder] Will search up to " + maxRings + " rings (" +
                           (maxRings * maxRings * 4) + " grid points worst case)");

        // Expanding square spiral search
        int pointsChecked = 0;
        for (int ring = 0; ring <= maxRings; ring++) {
            int ringMin = -ring * GRID_RESOLUTION;
            int ringMax = ring * GRID_RESOLUTION;

            // Search perimeter of current ring
            for (int dx = ringMin; dx <= ringMax; dx += GRID_RESOLUTION) {
                for (int dz = ringMin; dz <= ringMax; dz += GRID_RESOLUTION) {
                    // Only check perimeter (skip interior already checked in previous rings)
                    if (ring > 0 && Math.abs(dx) < ringMax && Math.abs(dz) < ringMax) {
                        continue;
                    }

                    int gridX = startGridX + dx;
                    int gridZ = startGridZ + dz;

                    // Distance check (early exit if outside search radius)
                    float distSq = dx * dx + dz * dz;
                    if (distSq > config.searchRadius() * config.searchRadius()) {
                        continue;
                    }

                    pointsChecked++;

                    // Check if this grid point has a lake
                    if (isLakeAtGridPoint(gridX, gridZ)) {
                        if (distSq < closestDistSq) {
                            // Get water level for Y coordinate
                            int terrainY = world.getFinalTerrainHeight(gridX, gridZ);
                            Vector3f lakePos = new Vector3f(gridX, terrainY, gridZ);
                            float distance = (float) Math.sqrt(distSq);

                            closestDistSq = distSq;
                            closest = new StructureSearchResult(
                                StructureType.LAKE,
                                lakePos,
                                distance
                            );
                            System.out.println("[LakeFinder] Found lake at ring " + ring +
                                               " (" + lakePos.x + ", " + lakePos.y + ", " + lakePos.z + ")");
                        }
                    }
                }
            }

            // Progress logging every 5 rings
            if (ring > 0 && ring % 5 == 0) {
                long elapsed = System.currentTimeMillis() - startTime;
                System.out.println("[LakeFinder] Ring " + ring + "/" + maxRings +
                                   " - " + pointsChecked + " points checked, " + elapsed + "ms elapsed");
            }

            // Early exit: if we found a lake and the next ring is farther than needed
            if (closest != null && (ring + 1) * GRID_RESOLUTION > closest.distance() + GRID_RESOLUTION) {
                System.out.println("[LakeFinder] Early exit at ring " + ring);
                break;
            }
        }

        long duration = System.currentTimeMillis() - startTime;
        System.out.println("[LakeFinder] Search complete: " + pointsChecked + " points checked in " +
                           duration + "ms, result=" + (closest != null ? "FOUND" : "NOT_FOUND"));

        return Optional.ofNullable(closest);
    }

    /**
     * Checks if a lake exists at the specified grid point.
     *
     * <p>Optimized two-phase check:</p>
     * <ol>
     *     <li>Fast climate pre-filter (skips expensive rim detection if climate is unsuitable)</li>
     *     <li>Full basin water check only if climate is suitable</li>
     * </ol>
     *
     * @param gridX Grid X coordinate (world coordinates)
     * @param gridZ Grid Z coordinate (world coordinates)
     * @return true if a lake exists at this grid point
     */
    private boolean isLakeAtGridPoint(int gridX, int gridZ) {
        // PHASE 1: Fast climate pre-filter (cheap checks first)
        // Get climate parameters at grid point
        float temperature = world.getTemperatureAt(gridX, gridZ);
        float humidity = world.getMoistureAt(gridX, gridZ);

        // Quick reject: climate unsuitable (same checks as basin water grid)
        if (humidity < 0.3f || temperature <= 0.1f || temperature >= 0.95f) {
            return false;
        }

        // PHASE 2: Terrain height check (moderate cost)
        int terrainHeight = world.getFinalTerrainHeight(gridX, gridZ);

        // Quick reject: must be above minimum lake elevation
        if (terrainHeight < MIN_LAKE_ELEVATION) {
            return false;
        }

        // PHASE 3: Full basin water check (expensive - rim detection with 289 samples)
        // Only reach here if climate and elevation are suitable
        int waterLevel = basinGrid.getWaterLevel(gridX, gridZ, terrainHeight, temperature, humidity);

        // If water level > -1, a lake exists here
        return waterLevel > -1;
    }

    /**
     * Snaps a world coordinate to the nearest grid center.
     *
     * <p>Grid centers are at multiples of GRID_RESOLUTION (256 blocks).
     * Example: 123 -> 128, 300 -> 256, -100 -> -128</p>
     *
     * @param worldCoord World coordinate (X or Z)
     * @return Nearest grid center coordinate
     */
    private int snapToGrid(int worldCoord) {
        // Floor division to get grid cell index
        int gridCell = Math.floorDiv(worldCoord, GRID_RESOLUTION);

        // Grid center is at gridCell * GRID_RESOLUTION + GRID_RESOLUTION/2
        return gridCell * GRID_RESOLUTION + GRID_RESOLUTION / 2;
    }
}
