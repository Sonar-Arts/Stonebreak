package com.stonebreak.world.generation;

import java.util.Random;
import java.util.HashMap;
import java.util.Map;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.world.chunk.Chunk;
import com.stonebreak.world.World;
import com.stonebreak.world.operations.WorldConfiguration;

/**
 * Handles generation of various tree types in the world.
 * Uses CCO-aware batch operations for efficient block placement with proper dirty tracking.
 */
public class TreeGenerator {

    /**
     * Helper class for batching tree block placements across multiple chunks.
     * Tracks affected chunks and marks them dirty after batch operations complete.
     */
    private static class TreeBlockPlacer {
        private final World world;
        private final Map<Chunk, Integer> affectedChunks = new HashMap<>();

        TreeBlockPlacer(World world) {
            this.world = world;
        }

        /**
         * Places a block at world coordinates using CCO operations.
         * Batches operations per chunk for efficiency.
         */
        void placeBlock(int worldX, int worldY, int worldZ, BlockType blockType) {
            if (worldY < 0 || worldY >= WorldConfiguration.WORLD_HEIGHT) {
                return;
            }

            int chunkX = Math.floorDiv(worldX, WorldConfiguration.CHUNK_SIZE);
            int chunkZ = Math.floorDiv(worldZ, WorldConfiguration.CHUNK_SIZE);

            Chunk chunk = world.getChunkAt(chunkX, chunkZ);
            if (chunk == null) {
                return;
            }

            int localX = Math.floorMod(worldX, WorldConfiguration.CHUNK_SIZE);
            int localZ = Math.floorMod(worldZ, WorldConfiguration.CHUNK_SIZE);

            // Use CCO writer for automatic dirty tracking
            chunk.setBlock(localX, worldY, localZ, blockType);

            // Track affected chunk for mesh rebuild
            affectedChunks.merge(chunk, 1, Integer::sum);
        }

        /**
         * Completes the batch operation and triggers mesh rebuilds for all affected chunks.
         * This ensures CCO dirty flags are respected and MMS mesh regeneration is triggered.
         */
        void complete() {
            // World.setBlockAt already handles mesh rebuild scheduling via neighborCoordinator
            // Since we're using chunk.setBlock directly, chunks are marked dirty by CCO writer
            // No additional mesh rebuild needed - CCO dirty tracking + MMS handles it
            affectedChunks.clear();
        }
    }
    
    /**
     * Generates a tree at the specified position.
     * Uses CCO batch operations for efficient cross-chunk placement.
     */
    public static void generateTree(World world, Chunk chunk, int x, int y, int z) { // x, z are local to chunk; y is worldYBase
        // Calculate world coordinates for the base of the trunk
        int worldXBase = chunk.getWorldX(x); // x is localXInOriginChunk
        int worldZBase = chunk.getWorldZ(z); // z is localZInOriginChunk
        int worldYBase = y;                  // y is worldYBase for the bottom of the trunk

        // Check if the top of the tree goes out of world bounds vertically.
        // Trunk is 5 blocks (worldYBase to worldYBase+4), leaves extend to worldYBase+6.
        if (worldYBase + 6 >= WorldConfiguration.WORLD_HEIGHT) {
            return;
        }

        // Create batch placer for CCO-aware operations
        TreeBlockPlacer placer = new TreeBlockPlacer(world);

        // Place tree trunk
        for (int dyTrunk = 0; dyTrunk < 5; dyTrunk++) { // 5 blocks high trunk
            placer.placeBlock(worldXBase, worldYBase + dyTrunk, worldZBase, BlockType.WOOD);
        }

        // Place leaves
        int leafRadius = 2; // Leaves spread -2 to +2 blocks from the trunk's center line
        // Leaf layers are relative to worldYBase, starting at an offset of 3 blocks up, to 6 blocks up.
        for (int leafLayerYOffset = 3; leafLayerYOffset <= 6; leafLayerYOffset++) {
            int currentLeafWorldY = worldYBase + leafLayerYOffset;

            // Ensure this leaf layer is within world height (mostly covered by initial check)
            if (currentLeafWorldY >= WorldConfiguration.WORLD_HEIGHT) {
                continue;
            }

            for (int dxLeaf = -leafRadius; dxLeaf <= leafRadius; dxLeaf++) {
                for (int dzLeaf = -leafRadius; dzLeaf <= leafRadius; dzLeaf++) {
                    // Skip the four far corners of the 5x5 leaf square for a rounder canopy
                    if (Math.abs(dxLeaf) == leafRadius && Math.abs(dzLeaf) == leafRadius) {
                        continue;
                    }
                    // Skip the center column (directly above trunk) for the lower two leaf layers (offset 3 and 4)
                    // This makes the tree look less blocky from underneath.
                    if (dxLeaf == 0 && dzLeaf == 0 && leafLayerYOffset < 5) {
                        continue;
                    }

                    placer.placeBlock(worldXBase + dxLeaf, currentLeafWorldY, worldZBase + dzLeaf, BlockType.LEAVES);
                }
            }
        }

        // Complete batch operation - CCO dirty tracking handles mesh rebuild
        placer.complete();
    }
    
    /**
     * Generates a pine tree at the specified position.
     * Uses CCO batch operations for efficient cross-chunk placement.
     */
    public static void generatePineTree(World world, Chunk chunk, int x, int y, int z) { // x, z are local to chunk; y is worldYBase
        // Calculate world coordinates for the base of the trunk
        int worldXBase = chunk.getWorldX(x);
        int worldZBase = chunk.getWorldZ(z);
        int worldYBase = y;

        // Pine trees are taller - check if the top goes out of world bounds
        // Trunk is 7 blocks, leaves extend to worldYBase+8
        if (worldYBase + 8 >= WorldConfiguration.WORLD_HEIGHT) {
            return;
        }

        // Create batch placer for CCO-aware operations
        TreeBlockPlacer placer = new TreeBlockPlacer(world);

        // Place pine tree trunk (darker wood)
        for (int dyTrunk = 0; dyTrunk < 7; dyTrunk++) { // 7 blocks high trunk
            placer.placeBlock(worldXBase, worldYBase + dyTrunk, worldZBase, BlockType.PINE);
        }

        // Place snowy leaves in a more conical shape
        // Bottom layer (offset 3-4): radius 2
        for (int leafLayerYOffset = 3; leafLayerYOffset <= 4; leafLayerYOffset++) {
            int currentLeafWorldY = worldYBase + leafLayerYOffset;
            if (currentLeafWorldY >= WorldConfiguration.WORLD_HEIGHT) continue;

            int leafRadius = 2;
            for (int dxLeaf = -leafRadius; dxLeaf <= leafRadius; dxLeaf++) {
                for (int dzLeaf = -leafRadius; dzLeaf <= leafRadius; dzLeaf++) {
                    // Skip corners for rounder shape
                    if (Math.abs(dxLeaf) == leafRadius && Math.abs(dzLeaf) == leafRadius) {
                        continue;
                    }
                    // Skip center column for trunk
                    if (dxLeaf == 0 && dzLeaf == 0) {
                        continue;
                    }
                    placer.placeBlock(worldXBase + dxLeaf, currentLeafWorldY, worldZBase + dzLeaf, BlockType.PINE_LEAVES);
                }
            }
        }

        // Middle layer (offset 5-6): radius 1
        for (int leafLayerYOffset = 5; leafLayerYOffset <= 6; leafLayerYOffset++) {
            int currentLeafWorldY = worldYBase + leafLayerYOffset;
            if (currentLeafWorldY >= WorldConfiguration.WORLD_HEIGHT) continue;

            int leafRadius = 1;
            for (int dxLeaf = -leafRadius; dxLeaf <= leafRadius; dxLeaf++) {
                for (int dzLeaf = -leafRadius; dzLeaf <= leafRadius; dzLeaf++) {
                    // Skip center column for trunk
                    if (dxLeaf == 0 && dzLeaf == 0) {
                        continue;
                    }
                    placer.placeBlock(worldXBase + dxLeaf, currentLeafWorldY, worldZBase + dzLeaf, BlockType.PINE_LEAVES);
                }
            }
        }

        // Top layer (offset 7-8): just around the top
        for (int leafLayerYOffset = 7; leafLayerYOffset <= 8; leafLayerYOffset++) {
            int currentLeafWorldY = worldYBase + leafLayerYOffset;
            if (currentLeafWorldY >= WorldConfiguration.WORLD_HEIGHT) continue;

            // Very small cap
            for (int dxLeaf = -1; dxLeaf <= 1; dxLeaf++) {
                for (int dzLeaf = -1; dzLeaf <= 1; dzLeaf++) {
                    // Only place on the sides for the very top
                    if (leafLayerYOffset == 8 && (dxLeaf == 0 && dzLeaf == 0)) {
                        continue; // Skip center for very top
                    }
                    if (leafLayerYOffset == 7 && (dxLeaf == 0 && dzLeaf == 0)) {
                        continue; // Skip center for trunk
                    }
                    placer.placeBlock(worldXBase + dxLeaf, currentLeafWorldY, worldZBase + dzLeaf, BlockType.PINE_LEAVES);
                }
            }
        }

        // Complete batch operation - CCO dirty tracking handles mesh rebuild
        placer.complete();
    }
    
    /**
     * Generates an elm tree at the specified position.
     * Elm trees have the characteristic vase shape - wide canopy supported by branching trunk.
     * Uses CCO batch operations for efficient cross-chunk placement.
     */
    public static void generateElmTree(World world, Chunk chunk, int x, int y, int z, Random random, Object randomLock) {
        int worldXBase = chunk.getWorldX(x);
        int worldZBase = chunk.getWorldZ(z);
        int worldYBase = y;

        // Elm trees are larger - trunk is 8-12 blocks, canopy extends to worldYBase+16
        // Randomly vary height based on research: elms can be quite tall
        int trunkHeight;
        synchronized (randomLock) {
            trunkHeight = 8 + random.nextInt(5); // 8-12 blocks tall trunk
        }

        if (worldYBase + trunkHeight + 6 >= WorldConfiguration.WORLD_HEIGHT) {
            return; // Tree would exceed world height
        }

        // Create batch placer for CCO-aware operations
        TreeBlockPlacer placer = new TreeBlockPlacer(world);

        // Place elm trunk - straight up with some branching at the top
        for (int dyTrunk = 0; dyTrunk < trunkHeight; dyTrunk++) {
            placer.placeBlock(worldXBase, worldYBase + dyTrunk, worldZBase, BlockType.ELM_WOOD_LOG);
        }

        // Add branch structure at top of trunk (characteristic elm branching)
        // Elm trees branch out near the top, creating the vase shape
        int branchLevel = worldYBase + trunkHeight - 3; // Start branching 3 blocks from top
        if (branchLevel + 3 < WorldConfiguration.WORLD_HEIGHT) {
            // Add horizontal branches for vase shape
            for (int branchY = branchLevel; branchY < branchLevel + 3; branchY++) {
                // Create 4 main branches extending outward
                placer.placeBlock(worldXBase + 1, branchY, worldZBase, BlockType.ELM_WOOD_LOG); // East branch
                placer.placeBlock(worldXBase - 1, branchY, worldZBase, BlockType.ELM_WOOD_LOG); // West branch
                placer.placeBlock(worldXBase, branchY, worldZBase + 1, BlockType.ELM_WOOD_LOG); // South branch
                placer.placeBlock(worldXBase, branchY, worldZBase - 1, BlockType.ELM_WOOD_LOG); // North branch

                // Add diagonal branches for fuller structure
                if (branchY == branchLevel + 1) { // Middle level only
                    placer.placeBlock(worldXBase + 1, branchY, worldZBase + 1, BlockType.ELM_WOOD_LOG);
                    placer.placeBlock(worldXBase + 1, branchY, worldZBase - 1, BlockType.ELM_WOOD_LOG);
                    placer.placeBlock(worldXBase - 1, branchY, worldZBase + 1, BlockType.ELM_WOOD_LOG);
                    placer.placeBlock(worldXBase - 1, branchY, worldZBase - 1, BlockType.ELM_WOOD_LOG);
                }
            }
        }

        // Place elm leaves in characteristic wide, vase-like canopy
        // Elm trees have very wide canopies - up to 80 feet spread in real life
        int leafRadius = 4; // Large canopy radius for elm's characteristic wide spread

        // Bottom leaf layer - fullest and widest (characteristic of elm's umbrella shape)
        for (int leafLayerYOffset = trunkHeight - 1; leafLayerYOffset <= trunkHeight + 2; leafLayerYOffset++) {
            int currentLeafWorldY = worldYBase + leafLayerYOffset;
            if (currentLeafWorldY >= WorldConfiguration.WORLD_HEIGHT) continue;

            for (int dxLeaf = -leafRadius; dxLeaf <= leafRadius; dxLeaf++) {
                for (int dzLeaf = -leafRadius; dzLeaf <= leafRadius; dzLeaf++) {
                    // Create the characteristic elm canopy shape - wider at edges, fuller overall
                    float distFromCenter = (float) Math.sqrt(dxLeaf * dxLeaf + dzLeaf * dzLeaf);

                    // Skip only the very far corners for a more natural rounded shape
                    if (distFromCenter > leafRadius * 0.9f) {
                        continue;
                    }

                    // Skip center column where trunk/branches are (but only in lower layers)
                    if (dxLeaf == 0 && dzLeaf == 0 && leafLayerYOffset < trunkHeight + 1) {
                        continue;
                    }

                    // Add some randomness to leaf placement for more natural look
                    boolean shouldPlaceLeaf = true;
                    if (distFromCenter > leafRadius * 0.7f) {
                        synchronized (randomLock) {
                            shouldPlaceLeaf = random.nextFloat() > 0.3f; // 70% chance for outer leaves
                        }
                    }

                    if (shouldPlaceLeaf) {
                        placer.placeBlock(worldXBase + dxLeaf, currentLeafWorldY, worldZBase + dzLeaf, BlockType.ELM_LEAVES);
                    }
                }
            }
        }

        // Upper leaf layers - gradually smaller but still maintaining elm's broad canopy
        for (int leafLayerYOffset = trunkHeight + 3; leafLayerYOffset <= trunkHeight + 5; leafLayerYOffset++) {
            int currentLeafWorldY = worldYBase + leafLayerYOffset;
            if (currentLeafWorldY >= WorldConfiguration.WORLD_HEIGHT) continue;

            int upperRadius = 3; // Slightly smaller for upper layers
            for (int dxLeaf = -upperRadius; dxLeaf <= upperRadius; dxLeaf++) {
                for (int dzLeaf = -upperRadius; dzLeaf <= upperRadius; dzLeaf++) {
                    float distFromCenter = (float) Math.sqrt(dxLeaf * dxLeaf + dzLeaf * dzLeaf);

                    if (distFromCenter > upperRadius * 0.8f) {
                        continue;
                    }

                    // Random gaps in upper canopy
                    boolean shouldPlaceLeaf;
                    synchronized (randomLock) {
                        shouldPlaceLeaf = random.nextFloat() > 0.2f; // 80% chance
                    }

                    if (shouldPlaceLeaf) {
                        placer.placeBlock(worldXBase + dxLeaf, currentLeafWorldY, worldZBase + dzLeaf, BlockType.ELM_LEAVES);
                    }
                }
            }
        }

        // Top crown - small cluster at very top
        for (int leafLayerYOffset = trunkHeight + 6; leafLayerYOffset <= trunkHeight + 6; leafLayerYOffset++) {
            int currentLeafWorldY = worldYBase + leafLayerYOffset;
            if (currentLeafWorldY >= WorldConfiguration.WORLD_HEIGHT) continue;

            for (int dxLeaf = -1; dxLeaf <= 1; dxLeaf++) {
                for (int dzLeaf = -1; dzLeaf <= 1; dzLeaf++) {
                    if (Math.abs(dxLeaf) == 1 && Math.abs(dzLeaf) == 1) continue; // Skip corners
                    placer.placeBlock(worldXBase + dxLeaf, currentLeafWorldY, worldZBase + dzLeaf, BlockType.ELM_LEAVES);
                }
            }
        }

        // Complete batch operation - CCO dirty tracking handles mesh rebuild
        placer.complete();
    }
}