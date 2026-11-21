package com.stonebreak.blocks.waterSystem;

import java.util.Objects;

import com.openmason.engine.voxel.world.CubeWorld;
import com.stonebreak.blocks.BlockType;
import com.stonebreak.blocks.waterSystem.handlers.FlowBlockInteraction;
import com.stonebreak.world.ServerMutationSinks;
import com.stonebreak.world.World;
import com.stonebreak.world.operations.WorldConfiguration;
import com.stonebreak.world.voxel.VoxelLayers;

/**
 * Production {@link FlowWorld} over a {@link World} on the native cube runtime (#447): blocks through
 * {@link World#getBlockAt}/{@link World#setBlockRaw}, water state in the world's WATER layer
 * ({@link VoxelLayers#WATER}, converted to the game vocabulary), and the server replication funnels for
 * sim-driven block and water-level mutations. Remeshing needs no bookkeeping: block and WATER-layer writes
 * mark cubes dirty natively and the stream re-meshes them.
 */
public final class WorldFlowWorld implements FlowWorld {

    private final World world;

    public WorldFlowWorld(World world) {
        this.world = Objects.requireNonNull(world, "world");
    }

    @Override
    public BlockType getBlock(long x, long y, long z) {
        return world.getBlockAt(x, y, z);
    }

    @Override
    public boolean isLoaded(long x, long y, long z) {
        return world.isLoaded(x, y, z);
    }

    @Override
    public void setBlock(long x, long y, long z, BlockType type) {
        if (!WorldConfiguration.inBounds(y) || !world.setBlockRaw(x, y, z, type)) {
            return;
        }
        // Report the mutation to the integrated server's replication funnel (installed on the headless
        // server world only) so flow reaches clients through the same batches as player edits.
        ServerMutationSinks.BlockSink sink = world.serverSinks().blocks();
        if (sink != null) {
            sink.onServerBlockChange(Math.toIntExact(x), Math.toIntExact(y), Math.toIntExact(z), type);
        }
    }

    @Override
    public int getWater(long x, long y, long z) {
        int value = world.getWaterValueAt(x, y, z);
        return Math.max(value, 0);
    }

    @Override
    public void setWater(long x, long y, long z, int value) {
        world.setWaterValueAt(x, y, z, value);
    }

    @Override
    public boolean isSolid(long x, long y, long z) {
        BlockType block = getBlock(x, y, z);
        return block != null && block.isSolid();
    }

    @Override
    public void dropFragile(long x, long y, long z, BlockType type) {
        FlowBlockInteraction.dropFragile(world, x, y, z, type);
    }

    @Override
    public void markWaterChanged(long x, long y, long z, int newValue) {
        // Live flow levels to clients as BlockMetaS2C (KIND_WATER_LEVEL); value 0 = removed / became
        // source. Installed on the headless server world only.
        ServerMutationSinks.WaterSink sink = world.serverSinks().water();
        if (sink != null) {
            sink.onServerWaterChange(Math.toIntExact(x), Math.toIntExact(y), Math.toIntExact(z), newValue);
        }
    }

    @Override
    public void forEachWaterValue(long cx, long cy, long cz, WaterCellVisitor visitor) {
        CubeWorld.LayerCells cells = world.voxels().scanLayer(VoxelLayers.WATER, cx, cy, cz);
        if (cells == null) {
            return;
        }
        long x0 = cx << 5, y0 = cy << 5, z0 = cz << 5;
        for (int i = 0; i < cells.count(); i++) {
            int cell = Short.toUnsignedInt(cells.index()[i]);
            int value = VoxelLayers.waterToGame(Short.toUnsignedInt(cells.value()[i]));
            if (value != 0) {
                visitor.accept(x0 + (cell & 31), y0 + ((cell >> 10) & 31), z0 + ((cell >> 5) & 31), value);
            }
        }
    }

    @Override
    public boolean readCubeIds(long cx, long cy, long cz, short[] out) {
        return world.voxels().readIds(cx, cy, cz, out);
    }
}
