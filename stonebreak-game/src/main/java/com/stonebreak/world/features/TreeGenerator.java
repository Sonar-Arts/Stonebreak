package com.stonebreak.world.features;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.world.Chunk;
import com.stonebreak.world.World;

/**
 * Handles generation of various tree types in the world.
 */
public class TreeGenerator {
    
    /**
     * Generates a tree at the specified position.
     */
    public static void generateTree(World world, Chunk chunk, int x, int y, int z) { // x, z are local to chunk; y is worldYBase
        // Calculate world coordinates for the base of the trunk
        int worldXBase = chunk.getWorldX(x); // x is localXInOriginChunk
        int worldZBase = chunk.getWorldZ(z); // z is localZInOriginChunk
        int worldYBase = y;                  // y is worldYBase for the bottom of the trunk

        // Check if the top of the tree goes out of world bounds vertically.
        // Trunk is 5 blocks (worldYBase to worldYBase+4), leaves extend to worldYBase+6.
        if (worldYBase + 6 >= World.WORLD_HEIGHT) {
            return;
        }

        // Removed: Old check that restricted tree trunk origin (x,z) based on CHUNK_SIZE.
        // Trees can now originate near chunk edges and their parts will be placed in correct chunks.

        // Place tree trunk
        for (int dyTrunk = 0; dyTrunk < 5; dyTrunk++) { // 5 blocks high trunk
            if (worldYBase + dyTrunk < World.WORLD_HEIGHT) { // Ensure trunk block is within height limits
                 world.setBlockAt(worldXBase, worldYBase + dyTrunk, worldZBase, BlockType.WOOD);
            }
        }

        // Place leaves
        int leafRadius = 2; // Leaves spread -2 to +2 blocks from the trunk's center line
        // Leaf layers are relative to worldYBase, starting at an offset of 3 blocks up, to 6 blocks up.
        for (int leafLayerYOffset = 3; leafLayerYOffset <= 6; leafLayerYOffset++) {
            int currentLeafWorldY = worldYBase + leafLayerYOffset;

            // Ensure this leaf layer is within world height (mostly covered by initial check)
            if (currentLeafWorldY >= World.WORLD_HEIGHT) {
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

                    // Removed: Old check for leaf x+dx, z+dz being outside local chunk bounds.
                    // this.setBlockAt handles world coordinates and places blocks in correct chunks.
                    world.setBlockAt(worldXBase + dxLeaf, currentLeafWorldY, worldZBase + dzLeaf, BlockType.LEAVES);
                }
            }
        }
    }
}