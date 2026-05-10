package com.stonebreak.world.generation;

import java.util.HashSet;
import java.util.Set;

import com.stonebreak.util.BlockPos;
import com.stonebreak.world.World;
import com.stonebreak.world.chunk.Chunk;
import com.stonebreak.world.chunk.utils.ChunkPosition;
import com.stonebreak.world.generation.features.FeaturePlacer;
import com.stonebreak.world.generation.features.FeatureQueue;
import com.stonebreak.world.generation.features.QueuedFeature;
import com.stonebreak.world.generation.trees.ElmTree;
import com.stonebreak.world.generation.trees.PineTree;
import com.stonebreak.world.generation.trees.RegularTree;
import com.stonebreak.world.operations.WorldConfiguration;

/**
 * Controller that schedules tree placement and dispatches to the tree-shape classes in
 * {@link com.stonebreak.world.generation.trees}. The shape classes are responsible for what a
 * tree looks like; this class is responsible for *when* it gets placed.
 *
 * Each public {@code generateXxxTree} method either places the tree immediately (if every
 * chunk it could touch is already loaded) or queues it via {@link FeatureQueue} for deferred
 * placement once the missing neighbour chunks come online. This eliminates the race where a
 * tree placed during feature-population would silently drop blocks targeting a not-yet-loaded
 * neighbour, leaving "trunkless leaf clouds" near chunk boundaries when the player flew fast.
 */
public final class TreeGenerator {

    private static final int CHUNK_SIZE = WorldConfiguration.CHUNK_SIZE;

    private TreeGenerator() {}

    // ---------------------------------------------------------------------------------------
    // Public scheduling API
    // ---------------------------------------------------------------------------------------

    public static void generateTree(World world, FeatureQueue queue, Chunk originChunk,
                                    int localX, int worldYBase, int localZ) {
        int worldX = originChunk.getWorldX(localX);
        int worldZ = originChunk.getWorldZ(localZ);
        if (worldYBase + RegularTree.MAX_HEIGHT >= WorldConfiguration.WORLD_HEIGHT) return;

        scheduleOrPlace(world, queue, "regular_tree", worldX, worldYBase, worldZ,
            RegularTree.LEAF_RADIUS,
            w -> RegularTree.place(w, worldX, worldYBase, worldZ));
    }

    public static void generatePineTree(World world, FeatureQueue queue, Chunk originChunk,
                                        int localX, int worldYBase, int localZ) {
        int worldX = originChunk.getWorldX(localX);
        int worldZ = originChunk.getWorldZ(localZ);
        if (worldYBase + PineTree.MAX_HEIGHT >= WorldConfiguration.WORLD_HEIGHT) return;

        scheduleOrPlace(world, queue, "pine_tree", worldX, worldYBase, worldZ,
            PineTree.LEAF_RADIUS,
            w -> PineTree.place(w, worldX, worldYBase, worldZ));
    }

    public static void generateElmTree(World world, FeatureQueue queue, Chunk originChunk,
                                       int localX, int worldYBase, int localZ) {
        int worldX = originChunk.getWorldX(localX);
        int worldZ = originChunk.getWorldZ(localZ);
        if (worldYBase + ElmTree.MAX_HEIGHT >= WorldConfiguration.WORLD_HEIGHT) return;

        scheduleOrPlace(world, queue, "elm_tree", worldX, worldYBase, worldZ,
            ElmTree.LEAF_RADIUS,
            w -> ElmTree.place(w, worldX, worldYBase, worldZ));
    }

    // ---------------------------------------------------------------------------------------
    // Scheduling helpers
    // ---------------------------------------------------------------------------------------

    private static void scheduleOrPlace(World world, FeatureQueue queue, String name,
                                        int worldX, int worldY, int worldZ, int leafRadius,
                                        FeaturePlacer placer) {
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
}
