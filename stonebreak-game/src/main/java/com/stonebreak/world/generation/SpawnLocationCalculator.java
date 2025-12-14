package com.stonebreak.world.generation;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.world.World;
import com.stonebreak.world.chunk.Chunk;
import com.stonebreak.world.operations.WorldConfiguration;
import org.joml.Vector3f;

import java.util.Random;

/**
 * Calculates spawn positions in the world using terrain analysis.
 * Supports randomized spawn chunk selection and intelligent surface detection.
 */
public class SpawnLocationCalculator {

    private final World world;
    private final Random random;

    /**
     * Creates a new SpawnLocationCalculator with a random seed.
     *
     * @param world The world to calculate spawn positions in
     */
    public SpawnLocationCalculator(World world) {
        this(world, new Random());
    }

    /**
     * Creates a new SpawnLocationCalculator with a specific random instance.
     *
     * @param world The world to calculate spawn positions in
     * @param random The random number generator to use
     */
    public SpawnLocationCalculator(World world, Random random) {
        this.world = world;
        this.random = random;
    }

    /**
     * Calculates a spawn position with randomized chunk selection.
     * The spawn chunk is randomly selected in the range of 1-100 blocks from origin.
     *
     * @return The calculated spawn position (world coordinates)
     */
    public Vector3f calculateSpawnPosition() {
        // Randomize spawn chunk in range 1-100
        int spawnChunkX = random.nextInt(100) + 1;
        int spawnChunkZ = random.nextInt(100) + 1;

        // Randomly decide on positive or negative direction for each axis
        if (random.nextBoolean()) {
            spawnChunkX = -spawnChunkX;
        }
        if (random.nextBoolean()) {
            spawnChunkZ = -spawnChunkZ;
        }

        System.out.println("[SPAWN] Randomized spawn chunk selected: (" + spawnChunkX + ", " + spawnChunkZ + ")");

        return calculateSpawnPositionAt(spawnChunkX, spawnChunkZ);
    }

    /**
     * Calculates a spawn position at a specific chunk coordinate.
     *
     * @param chunkX The chunk X coordinate
     * @param chunkZ The chunk Z coordinate
     * @return The calculated spawn position (world coordinates)
     */
    public Vector3f calculateSpawnPositionAt(int chunkX, int chunkZ) {
        // Wait for spawn chunk terrain and surface cache to be ready
        if (!waitForChunkTerrain(chunkX, chunkZ, 5000, true)) {
            System.err.println("[SPAWN] Warning: Spawn chunk terrain not ready after 5000ms");
        }

        // Validate spawn chunk is fully ready
        Chunk spawnChunk = world.getChunkAt(chunkX, chunkZ);
        if (!validateSpawnChunkReadiness(spawnChunk)) {
            System.err.println("[SPAWN] Warning: Spawn chunk validation failed, spawn calculation may be inaccurate");
        }

        // Calculate spawn height
        int spawnY = findSpawnSurfaceHeight(chunkX, chunkZ);

        // Convert chunk coordinates to world coordinates (center of chunk)
        float worldX = chunkX * 16 + 8;
        float worldZ = chunkZ * 16 + 8;

        return new Vector3f(worldX, spawnY, worldZ);
    }

    /**
     * Finds the surface height at spawn chunk for spawn placement.
     * Uses surface height cache if available, falls back to manual scan.
     * If spawn column is entirely air, searches nearby columns.
     *
     * @param chunkX The spawn chunk X coordinate
     * @param chunkZ The spawn chunk Z coordinate
     * @return Y coordinate of first air block above surface, or 100 if column is empty
     */
    private int findSpawnSurfaceHeight(int chunkX, int chunkZ) {
        System.out.println("[SPAWN] Calculating spawn height at chunk (" + chunkX + ", " + chunkZ + ")...");

        // Get spawn chunk
        Chunk spawnChunk = world.getChunkAt(chunkX, chunkZ);
        if (spawnChunk == null) {
            System.err.println("[SPAWN] Calculation method: DEFAULT - Failed to get spawn chunk at (" + chunkX + ", " + chunkZ + "), using Y=100");
            System.err.println("[SPAWN] Final calculated spawn position: (" + (chunkX * 16 + 8) + ", 100, " + (chunkZ * 16 + 8) + ")");
            return 100;
        }

        // Log chunk state for debugging
        boolean hasStateManager = spawnChunk.getCcoStateManager() != null;
        boolean blocksPopulated = hasStateManager && spawnChunk.getCcoStateManager().hasState(
            com.stonebreak.world.chunk.api.commonChunkOperations.data.CcoChunkState.BLOCKS_POPULATED
        );
        int[][] surfaceCache = spawnChunk.getSurfaceHeightCache();
        System.out.println("[SPAWN] Chunk state before calculation: BLOCKS_POPULATED=" + blocksPopulated +
                          ", cache=" + (surfaceCache != null ? "exists" : "null") +
                          (surfaceCache != null ? ", cache[8][8]=" + surfaceCache[8][8] : ""));

        // Try surface height at center of chunk (8, 8) first
        int surfaceHeight = findSurfaceHeightAt(spawnChunk, 8, 8);
        if (surfaceHeight >= 0) {
            System.out.println("[SPAWN] Calculation method: PRIMARY - Using spawn column (8, 8), Y=" + surfaceHeight);
            System.out.println("[SPAWN] Final calculated spawn position: (" + (chunkX * 16 + 8) + ", " + surfaceHeight + ", " + (chunkZ * 16 + 8) + ")");
            return surfaceHeight;
        }

        // Edge case: Column (8, 8) is all air - search nearby columns
        System.err.println("[SPAWN] Spawn column (8, 8) is entirely air, searching nearby columns...");

        // Search pattern: expanding radius up to 3 blocks from center
        int[][] searchOffsets = {
            {1, 0}, {0, 1}, {-1, 0}, {0, -1},      // Adjacent (distance 1)
            {1, 1}, {-1, 1}, {-1, -1}, {1, -1},    // Diagonal (distance √2)
            {2, 0}, {0, 2}, {-2, 0}, {0, -2},      // Distance 2
            {2, 1}, {1, 2}, {-1, 2}, {-2, 1},
            {-2, -1}, {-1, -2}, {1, -2}, {2, -1},
            {3, 0}, {0, 3}, {-3, 0}, {0, -3}       // Distance 3
        };

        for (int[] offset : searchOffsets) {
            int localX = 8 + offset[0];
            int localZ = 8 + offset[1];

            // Keep search within chunk bounds
            if (localX >= 0 && localX < 16 && localZ >= 0 && localZ < 16) {
                surfaceHeight = findSurfaceHeightAt(spawnChunk, localX, localZ);
                if (surfaceHeight >= 0) {
                    System.out.println("[SPAWN] Calculation method: NEARBY - Found surface at offset (" + localX + ", " + localZ + "), Y=" + surfaceHeight);
                    System.out.println("[SPAWN] Final calculated spawn position: (" + (chunkX * 16 + 8) + ", " + surfaceHeight + ", " + (chunkZ * 16 + 8) + ")");
                    return surfaceHeight;
                }
            }
        }

        // No solid ground found within 3-block radius - calculate intelligent fallback
        System.err.println("[SPAWN] No solid ground found, calculating intelligent fallback...");

        int[][] cache = spawnChunk.getSurfaceHeightCache();
        if (cache != null) {
            int totalHeight = 0;
            int validColumns = 0;

            for (int x = 0; x < 16; x++) {
                for (int z = 0; z < 16; z++) {
                    if (cache[x][z] >= 0) {
                        totalHeight += cache[x][z];
                        validColumns++;
                    }
                }
            }

            if (validColumns > 0) {
                int avgHeight = totalHeight / validColumns;
                int fallbackY = avgHeight + 1;
                System.out.println("[SPAWN] Calculation method: AVERAGE - Using chunk average surface height: Y=" + fallbackY + " (avg of " + validColumns + " columns)");
                System.out.println("[SPAWN] Final calculated spawn position: (" + (chunkX * 16 + 8) + ", " + fallbackY + ", " + (chunkZ * 16 + 8) + ")");
                return fallbackY;
            }
        }

        // Final fallback if cache unavailable or all columns are air
        System.err.println("[SPAWN] Calculation method: DEFAULT - Using default fallback Y=100");
        System.err.println("[SPAWN] Final calculated spawn position: (" + (chunkX * 16 + 8) + ", 100, " + (chunkZ * 16 + 8) + ")");
        return 100;
    }

    /**
     * Tries to find a surface height at a specific block column within a chunk.
     *
     * @param chunk The chunk to search
     * @param localX Local X coordinate within chunk (0-15)
     * @param localZ Local Z coordinate within chunk (0-15)
     * @return Y coordinate of surface, or -1 if column is all air
     */
    private int findSurfaceHeightAt(Chunk chunk, int localX, int localZ) {
        if (chunk == null) {
            return -1;
        }

        // Try cache first if available
        int[][] surfaceCache = chunk.getSurfaceHeightCache();
        if (surfaceCache != null && localX >= 0 && localX < 16 && localZ >= 0 && localZ < 16) {
            int cacheValue = surfaceCache[localX][localZ];
            if (cacheValue >= 0) {
                return cacheValue + 1; // +1 for air block above surface
            }
        }

        // Manual scan
        for (int y = WorldConfiguration.WORLD_HEIGHT - 1; y >= 0; y--) {
            BlockType block = chunk.getBlock(localX, y, localZ);
            if (block != null && block != BlockType.AIR && block != BlockType.WATER) {
                return y + 1; // +1 for air block above surface
            }
        }

        return -1; // Column is all air
    }

    /**
     * Waits for a chunk to have terrain generated, with timeout.
     *
     * @param chunkX The chunk X coordinate
     * @param chunkZ The chunk Z coordinate
     * @param maxWaitMs Maximum time to wait in milliseconds
     * @param requireSurfaceCache If true, also waits for surface height cache to be initialized
     * @return true if chunk terrain was generated within timeout
     */
    private boolean waitForChunkTerrain(int chunkX, int chunkZ, int maxWaitMs, boolean requireSurfaceCache) {
        long startTime = System.currentTimeMillis();
        int attempts = 0;

        System.out.println("[SPAWN] Waiting for chunk (" + chunkX + ", " + chunkZ + ") terrain (timeout: " + maxWaitMs + "ms, requireCache: " + requireSurfaceCache + ")...");

        while (System.currentTimeMillis() - startTime < maxWaitMs) {
            Chunk chunk = world.getChunkAt(chunkX, chunkZ);
            if (chunk != null && chunk.getCcoStateManager() != null) {
                // Check if blocks have been populated (terrain generated)
                boolean blocksPopulated = chunk.getCcoStateManager().hasState(
                    com.stonebreak.world.chunk.api.commonChunkOperations.data.CcoChunkState.BLOCKS_POPULATED
                );

                // Check surface cache if required
                boolean cacheReady = true;
                if (requireSurfaceCache) {
                    int[][] surfaceCache = chunk.getSurfaceHeightCache();
                    cacheReady = surfaceCache != null;

                    // Log state every 20 attempts (every 1 second)
                    if (attempts % 20 == 0) {
                        System.out.println("[SPAWN] Chunk state: BLOCKS_POPULATED=" + blocksPopulated + ", cache=" + (cacheReady ? "exists" : "null"));
                    }
                }

                if (blocksPopulated && cacheReady) {
                    long elapsed = System.currentTimeMillis() - startTime;
                    System.out.println("[SPAWN] Chunk (" + chunkX + ", " + chunkZ + ") terrain ready after " + elapsed + "ms (state: BLOCKS_POPULATED, cache: " + (requireSurfaceCache ? "validated" : "not checked") + ")");
                    return true;
                }
            }

            attempts++;
            try {
                Thread.sleep(50); // Poll every 50ms
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }

        System.err.println("[SPAWN] Timeout waiting for chunk (" + chunkX + ", " + chunkZ + ") terrain generation after "
            + attempts + " attempts (" + maxWaitMs + "ms)");
        return false;
    }

    /**
     * Validates that the spawn chunk is fully ready for spawn calculation.
     *
     * @param spawnChunk The chunk at spawn coordinates
     * @return true if chunk is ready for spawn calculation
     */
    private boolean validateSpawnChunkReadiness(Chunk spawnChunk) {
        System.out.println("[SPAWN] Validating spawn chunk readiness...");

        if (spawnChunk == null) {
            System.err.println("[SPAWN] Validation FAILED: spawn chunk is null");
            return false;
        }

        if (spawnChunk.getCcoStateManager() == null) {
            System.err.println("[SPAWN] Validation FAILED: chunk state manager is null");
            return false;
        }

        boolean blocksPopulated = spawnChunk.getCcoStateManager().hasState(
            com.stonebreak.world.chunk.api.commonChunkOperations.data.CcoChunkState.BLOCKS_POPULATED
        );
        if (!blocksPopulated) {
            System.err.println("[SPAWN] Validation FAILED: chunk not in BLOCKS_POPULATED state");
            return false;
        }

        int[][] surfaceCache = spawnChunk.getSurfaceHeightCache();
        if (surfaceCache == null) {
            System.err.println("[SPAWN] Validation FAILED: surface height cache is null");
            return false;
        }

        int cacheValue = surfaceCache[8][8]; // Check center of chunk instead of [0][0]
        if (cacheValue < 0) {
            System.err.println("[SPAWN] Validation FAILED: invalid cache value at [8][8]: " + cacheValue);
            return false;
        }

        System.out.println("[SPAWN] Validation PASSED: chunk ready (BLOCKS_POPULATED=true, cache exists, cache[8][8]=" + cacheValue + ")");
        return true;
    }
}
