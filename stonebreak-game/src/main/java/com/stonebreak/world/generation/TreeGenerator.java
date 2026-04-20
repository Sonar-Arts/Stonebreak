package com.stonebreak.world.generation;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.util.BlockPos;
import com.stonebreak.world.World;
import com.stonebreak.world.chunk.Chunk;
import com.stonebreak.world.chunk.utils.ChunkPosition;
import com.stonebreak.world.generation.features.FeatureQueue;
import com.stonebreak.world.generation.features.QueuedFeature;
import com.stonebreak.world.operations.WorldConfiguration;

/**
 * Handles generation of various tree types in the world.
 *
 * Each public {@code generateXxxTree} method either places the tree immediately (if every
 * chunk it could touch is already loaded) or queues it via {@link FeatureQueue} for deferred
 * placement once the missing neighbour chunks come online. This eliminates the race where a
 * tree placed during feature-population would silently drop blocks targeting a not-yet-loaded
 * neighbour, leaving "trunkless leaf clouds" near chunk boundaries when the player flew fast.
 */
public class TreeGenerator {

    private static final int CHUNK_SIZE = WorldConfiguration.CHUNK_SIZE;
    private static final int REGULAR_TREE_RADIUS = 2;
    private static final int PINE_TREE_RADIUS = 2;
    private static final int ELM_TREE_RADIUS = 4;

    private TreeGenerator() {}

    // ---------------------------------------------------------------------------------------
    // Public scheduling API
    // ---------------------------------------------------------------------------------------

    public static void generateTree(World world, FeatureQueue queue, Chunk originChunk,
                                    int localX, int worldYBase, int localZ) {
        int worldX = originChunk.getWorldX(localX);
        int worldZ = originChunk.getWorldZ(localZ);
        if (worldYBase + 6 >= WorldConfiguration.WORLD_HEIGHT) return;

        scheduleOrPlace(world, queue, "regular_tree", worldX, worldYBase, worldZ,
            REGULAR_TREE_RADIUS, w -> placeRegularTree(w, worldX, worldYBase, worldZ));
    }

    public static void generatePineTree(World world, FeatureQueue queue, Chunk originChunk,
                                        int localX, int worldYBase, int localZ) {
        int worldX = originChunk.getWorldX(localX);
        int worldZ = originChunk.getWorldZ(localZ);
        if (worldYBase + 8 >= WorldConfiguration.WORLD_HEIGHT) return;

        scheduleOrPlace(world, queue, "pine_tree", worldX, worldYBase, worldZ,
            PINE_TREE_RADIUS, w -> placePineTree(w, worldX, worldYBase, worldZ));
    }

    public static void generateElmTree(World world, FeatureQueue queue, Chunk originChunk,
                                       int localX, int worldYBase, int localZ,
                                       Random random, Object randomLock) {
        int worldX = originChunk.getWorldX(localX);
        int worldZ = originChunk.getWorldZ(localZ);

        // Snapshot trunk height up-front so the placement is deterministic regardless of
        // when the queued feature actually executes.
        int trunkHeight;
        synchronized (randomLock) {
            trunkHeight = 8 + random.nextInt(5);
        }
        if (worldYBase + trunkHeight + 6 >= WorldConfiguration.WORLD_HEIGHT) return;

        scheduleOrPlace(world, queue, "elm_tree", worldX, worldYBase, worldZ,
            ELM_TREE_RADIUS,
            w -> placeElmTree(w, worldX, worldYBase, worldZ, trunkHeight, random, randomLock));
    }

    // ---------------------------------------------------------------------------------------
    // Scheduling helper
    // ---------------------------------------------------------------------------------------

    private static void scheduleOrPlace(World world, FeatureQueue queue, String name,
                                        int worldX, int worldY, int worldZ, int leafRadius,
                                        com.stonebreak.world.generation.features.FeaturePlacer placer) {
        Set<ChunkPosition> required = chunksTouchedByTree(worldX, worldZ, leafRadius);
        if (allLoaded(world, required)) {
            try {
                placer.place(world);
            } catch (Exception e) {
                System.err.println("[TREE] " + name + " placement failed at ("
                    + worldX + "," + worldY + "," + worldZ + "): " + e.getMessage());
            }
        } else {
            queue.queueFeature(new QueuedFeature(
                name, new BlockPos(worldX, worldY, worldZ), required, placer));
        }
    }

    private static Set<ChunkPosition> chunksTouchedByTree(int worldX, int worldZ, int leafRadius) {
        int minCx = Math.floorDiv(worldX - leafRadius, CHUNK_SIZE);
        int maxCx = Math.floorDiv(worldX + leafRadius, CHUNK_SIZE);
        int minCz = Math.floorDiv(worldZ - leafRadius, CHUNK_SIZE);
        int maxCz = Math.floorDiv(worldZ + leafRadius, CHUNK_SIZE);
        Set<ChunkPosition> chunks = new HashSet<>();
        for (int cx = minCx; cx <= maxCx; cx++) {
            for (int cz = minCz; cz <= maxCz; cz++) {
                chunks.add(new ChunkPosition(cx, cz));
            }
        }
        return chunks;
    }

    private static boolean allLoaded(World world, Set<ChunkPosition> chunks) {
        for (ChunkPosition pos : chunks) {
            if (!world.hasChunkAt(pos.getX(), pos.getZ())) {
                return false;
            }
        }
        return true;
    }

    // ---------------------------------------------------------------------------------------
    // Block placement (assumes all required chunks are loaded)
    // ---------------------------------------------------------------------------------------

    /** Batched per-chunk block placer. Caller guarantees chunks exist when invoked. */
    private static final class TreeBlockPlacer {
        private final World world;
        private final Map<Chunk, Integer> affectedChunks = new HashMap<>();

        TreeBlockPlacer(World world) {
            this.world = world;
        }

        void placeBlock(int worldX, int worldY, int worldZ, BlockType blockType) {
            if (worldY < 0 || worldY >= WorldConfiguration.WORLD_HEIGHT) return;

            int chunkX = Math.floorDiv(worldX, CHUNK_SIZE);
            int chunkZ = Math.floorDiv(worldZ, CHUNK_SIZE);
            Chunk chunk = world.getChunkAt(chunkX, chunkZ);
            if (chunk == null) return; // Defensive: scheduling guarantees this should never fire

            int localX = Math.floorMod(worldX, CHUNK_SIZE);
            int localZ = Math.floorMod(worldZ, CHUNK_SIZE);
            chunk.setBlock(localX, worldY, localZ, blockType);
            affectedChunks.merge(chunk, 1, Integer::sum);
        }

        void complete() {
            // chunk.setBlock only flips the dirty flag — it does NOT schedule the mesh
            // rebuild (compare World.setBlockAt, which explicitly calls
            // scheduleConditionalMeshBuild). For trees placed during a chunk's first feature
            // pass this didn't matter (the chunk hadn't been meshed yet), but for deferred
            // placements that fire AFTER a neighbouring chunk is already meshed, the new
            // blocks would sit in the chunk data with a stale GPU mesh until reload.
            // Explicitly trigger a rebuild for every chunk we wrote into.
            for (Chunk chunk : affectedChunks.keySet()) {
                int worldX = chunk.getChunkX() * CHUNK_SIZE;
                int worldZ = chunk.getChunkZ() * CHUNK_SIZE;
                world.triggerChunkRebuild(worldX, 0, worldZ);
            }
            affectedChunks.clear();
        }
    }

    private static void placeRegularTree(World world, int worldXBase, int worldYBase, int worldZBase) {
        TreeBlockPlacer placer = new TreeBlockPlacer(world);

        for (int dyTrunk = 0; dyTrunk < 5; dyTrunk++) {
            placer.placeBlock(worldXBase, worldYBase + dyTrunk, worldZBase, BlockType.WOOD);
        }

        int leafRadius = REGULAR_TREE_RADIUS;
        for (int leafLayerYOffset = 3; leafLayerYOffset <= 6; leafLayerYOffset++) {
            int currentLeafWorldY = worldYBase + leafLayerYOffset;
            if (currentLeafWorldY >= WorldConfiguration.WORLD_HEIGHT) continue;

            for (int dxLeaf = -leafRadius; dxLeaf <= leafRadius; dxLeaf++) {
                for (int dzLeaf = -leafRadius; dzLeaf <= leafRadius; dzLeaf++) {
                    if (Math.abs(dxLeaf) == leafRadius && Math.abs(dzLeaf) == leafRadius) continue;
                    if (dxLeaf == 0 && dzLeaf == 0 && leafLayerYOffset < 5) continue;
                    placer.placeBlock(worldXBase + dxLeaf, currentLeafWorldY, worldZBase + dzLeaf, BlockType.LEAVES);
                }
            }
        }
        placer.complete();
    }

    private static void placePineTree(World world, int worldXBase, int worldYBase, int worldZBase) {
        TreeBlockPlacer placer = new TreeBlockPlacer(world);

        for (int dyTrunk = 0; dyTrunk < 7; dyTrunk++) {
            placer.placeBlock(worldXBase, worldYBase + dyTrunk, worldZBase, BlockType.PINE);
        }

        // Bottom layer (offset 3-4): radius 2
        for (int leafLayerYOffset = 3; leafLayerYOffset <= 4; leafLayerYOffset++) {
            int currentLeafWorldY = worldYBase + leafLayerYOffset;
            if (currentLeafWorldY >= WorldConfiguration.WORLD_HEIGHT) continue;

            int leafRadius = 2;
            for (int dxLeaf = -leafRadius; dxLeaf <= leafRadius; dxLeaf++) {
                for (int dzLeaf = -leafRadius; dzLeaf <= leafRadius; dzLeaf++) {
                    if (Math.abs(dxLeaf) == leafRadius && Math.abs(dzLeaf) == leafRadius) continue;
                    if (dxLeaf == 0 && dzLeaf == 0) continue;
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
                    if (dxLeaf == 0 && dzLeaf == 0) continue;
                    placer.placeBlock(worldXBase + dxLeaf, currentLeafWorldY, worldZBase + dzLeaf, BlockType.PINE_LEAVES);
                }
            }
        }

        // Top crown (offset 7-8)
        for (int leafLayerYOffset = 7; leafLayerYOffset <= 8; leafLayerYOffset++) {
            int currentLeafWorldY = worldYBase + leafLayerYOffset;
            if (currentLeafWorldY >= WorldConfiguration.WORLD_HEIGHT) continue;

            for (int dxLeaf = -1; dxLeaf <= 1; dxLeaf++) {
                for (int dzLeaf = -1; dzLeaf <= 1; dzLeaf++) {
                    if (leafLayerYOffset == 8 && dxLeaf == 0 && dzLeaf == 0) continue;
                    if (leafLayerYOffset == 7 && dxLeaf == 0 && dzLeaf == 0) continue;
                    placer.placeBlock(worldXBase + dxLeaf, currentLeafWorldY, worldZBase + dzLeaf, BlockType.PINE_LEAVES);
                }
            }
        }
        placer.complete();
    }

    private static void placeElmTree(World world, int worldXBase, int worldYBase, int worldZBase,
                                     int trunkHeight, Random random, Object randomLock) {
        TreeBlockPlacer placer = new TreeBlockPlacer(world);

        for (int dyTrunk = 0; dyTrunk < trunkHeight; dyTrunk++) {
            placer.placeBlock(worldXBase, worldYBase + dyTrunk, worldZBase, BlockType.ELM_WOOD_LOG);
        }

        int branchLevel = worldYBase + trunkHeight - 3;
        if (branchLevel + 3 < WorldConfiguration.WORLD_HEIGHT) {
            for (int branchY = branchLevel; branchY < branchLevel + 3; branchY++) {
                placer.placeBlock(worldXBase + 1, branchY, worldZBase, BlockType.ELM_WOOD_LOG);
                placer.placeBlock(worldXBase - 1, branchY, worldZBase, BlockType.ELM_WOOD_LOG);
                placer.placeBlock(worldXBase, branchY, worldZBase + 1, BlockType.ELM_WOOD_LOG);
                placer.placeBlock(worldXBase, branchY, worldZBase - 1, BlockType.ELM_WOOD_LOG);

                if (branchY == branchLevel + 1) {
                    placer.placeBlock(worldXBase + 1, branchY, worldZBase + 1, BlockType.ELM_WOOD_LOG);
                    placer.placeBlock(worldXBase + 1, branchY, worldZBase - 1, BlockType.ELM_WOOD_LOG);
                    placer.placeBlock(worldXBase - 1, branchY, worldZBase + 1, BlockType.ELM_WOOD_LOG);
                    placer.placeBlock(worldXBase - 1, branchY, worldZBase - 1, BlockType.ELM_WOOD_LOG);
                }
            }
        }

        int leafRadius = ELM_TREE_RADIUS;
        for (int leafLayerYOffset = trunkHeight - 1; leafLayerYOffset <= trunkHeight + 2; leafLayerYOffset++) {
            int currentLeafWorldY = worldYBase + leafLayerYOffset;
            if (currentLeafWorldY >= WorldConfiguration.WORLD_HEIGHT) continue;

            for (int dxLeaf = -leafRadius; dxLeaf <= leafRadius; dxLeaf++) {
                for (int dzLeaf = -leafRadius; dzLeaf <= leafRadius; dzLeaf++) {
                    float distFromCenter = (float) Math.sqrt(dxLeaf * dxLeaf + dzLeaf * dzLeaf);
                    if (distFromCenter > leafRadius * 0.9f) continue;
                    if (dxLeaf == 0 && dzLeaf == 0 && leafLayerYOffset < trunkHeight + 1) continue;

                    boolean shouldPlaceLeaf = true;
                    if (distFromCenter > leafRadius * 0.7f) {
                        synchronized (randomLock) {
                            shouldPlaceLeaf = random.nextFloat() > 0.3f;
                        }
                    }
                    if (shouldPlaceLeaf) {
                        placer.placeBlock(worldXBase + dxLeaf, currentLeafWorldY, worldZBase + dzLeaf, BlockType.ELM_LEAVES);
                    }
                }
            }
        }

        for (int leafLayerYOffset = trunkHeight + 3; leafLayerYOffset <= trunkHeight + 5; leafLayerYOffset++) {
            int currentLeafWorldY = worldYBase + leafLayerYOffset;
            if (currentLeafWorldY >= WorldConfiguration.WORLD_HEIGHT) continue;

            int upperRadius = 3;
            for (int dxLeaf = -upperRadius; dxLeaf <= upperRadius; dxLeaf++) {
                for (int dzLeaf = -upperRadius; dzLeaf <= upperRadius; dzLeaf++) {
                    float distFromCenter = (float) Math.sqrt(dxLeaf * dxLeaf + dzLeaf * dzLeaf);
                    if (distFromCenter > upperRadius * 0.8f) continue;

                    boolean shouldPlaceLeaf;
                    synchronized (randomLock) {
                        shouldPlaceLeaf = random.nextFloat() > 0.2f;
                    }
                    if (shouldPlaceLeaf) {
                        placer.placeBlock(worldXBase + dxLeaf, currentLeafWorldY, worldZBase + dzLeaf, BlockType.ELM_LEAVES);
                    }
                }
            }
        }

        int topY = worldYBase + trunkHeight + 6;
        if (topY < WorldConfiguration.WORLD_HEIGHT) {
            for (int dxLeaf = -1; dxLeaf <= 1; dxLeaf++) {
                for (int dzLeaf = -1; dzLeaf <= 1; dzLeaf++) {
                    if (Math.abs(dxLeaf) == 1 && Math.abs(dzLeaf) == 1) continue;
                    placer.placeBlock(worldXBase + dxLeaf, topY, worldZBase + dzLeaf, BlockType.ELM_LEAVES);
                }
            }
        }

        placer.complete();
    }
}
