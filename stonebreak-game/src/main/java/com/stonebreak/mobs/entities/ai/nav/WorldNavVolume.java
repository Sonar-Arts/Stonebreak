package com.stonebreak.mobs.entities.ai.nav;

import com.openmason.engine.wayfind.voxel.NavCell;
import com.openmason.engine.wayfind.voxel.NavVolume;
import com.stonebreak.blocks.BlockShape;
import com.stonebreak.blocks.BlockType;
import com.stonebreak.blocks.waterSystem.WaterHeightUtil;
import com.stonebreak.world.World;
import com.stonebreak.world.operations.WorldConfiguration;

/**
 * The game's blocks, described to the engine's navigation rules.
 *
 * <p>The whole coupling between Wayfind and Stonebreak is these two methods: everything else in the
 * search knows only {@link NavCell} flags and surface heights.
 *
 * <p>Two decisions here matter more than they look:
 *
 * <ul>
 *   <li><b>Unloaded chunks report {@link NavCell#UNKNOWN}, not air.</b> {@code World.getBlockAt}
 *       answers AIR for a chunk that is not resident, which would let mobs plan confident routes
 *       through terrain nobody has generated. The chunk residency check comes first, and it uses
 *       {@code hasChunkAt} so that asking a navigation question can never be the thing that
 *       generates a chunk.</li>
 *   <li><b>Surface height comes from {@link BlockShape}</b> — the same function the player's and
 *       the mobs' collision use. Stairs and snow are where a second opinion would quietly appear,
 *       and a mob planning over heights its own physics disagrees with reads as a stuck mob, not
 *       as a mismatch.</li>
 * </ul>
 *
 * <p>Stateless and safe to share across threads: it holds a {@link World} and does nothing but read
 * it. Wrap it per search in a {@code NavCellCache} rather than calling it directly — every probe
 * here is a chunk lookup plus a paletted block read.
 */
public final class WorldNavVolume implements NavVolume {

    private final World world;

    public WorldNavVolume(World world) {
        this.world = world;
    }

    @Override
    public int flags(int x, int y, int z) {
        if (y < 0 || y >= WorldConfiguration.WORLD_HEIGHT) {
            return NavCell.UNKNOWN;
        }
        if (!world.hasChunkAt(Math.floorDiv(x, WorldConfiguration.CHUNK_SIZE),
                Math.floorDiv(z, WorldConfiguration.CHUNK_SIZE))) {
            return NavCell.UNKNOWN;
        }

        BlockType block = world.getBlockAt(x, y, z);
        if (block == null || block == BlockType.AIR) {
            return NavCell.OPEN;
        }
        if (block == BlockType.WATER) {
            return NavCell.LIQUID;
        }
        // Flowers and the like are solid to nothing and are simply walked through.
        return block.isSolid() ? NavCell.SOLID : NavCell.OPEN;
    }

    @Override
    public float topSurface(int x, int y, int z) {
        if (world.getBlockAt(x, y, z) == BlockType.WATER) {
            // The waterline is where a body floats, and a flowing block sits lower than a source.
            float height = WaterHeightUtil.resolveSurfaceHeightFraction(world, x, y, z);
            return Float.isNaN(height) ? WaterHeightUtil.MAX_WATER_HEIGHT : height;
        }
        return BlockShape.collisionHeight(world, x, y, z);
    }
}
