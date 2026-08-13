package com.stonebreak.world.leaves;

import java.util.Objects;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.world.ServerMutationSinks;
import com.stonebreak.world.World;
import com.stonebreak.world.chunk.utils.CachedChunkAccess;

/**
 * Production {@link LeafWorld} over a {@link World}: CCO block reads through the
 * shared {@link CachedChunkAccess} plumbing, with decay writes routed through
 * {@link World#setBlockAt} — the same block-change funnel player edits use — so
 * everything watching that funnel observes the removal: the water simulation
 * (settled water flows into the opened cell), the animated-block registry, mesh
 * rebuild scheduling where a mesh pipeline exists, and the leaf system's own
 * cascade trigger. Replication to clients goes through the integrated server's
 * mutation sink, exactly like water-flow writes.
 */
public final class WorldLeafWorld implements LeafWorld {

    private final World world;
    private final CachedChunkAccess access;

    public WorldLeafWorld(World world) {
        this.world = Objects.requireNonNull(world, "world");
        this.access = new CachedChunkAccess(world);
    }

    @Override
    public BlockType getBlock(int x, int y, int z) {
        return access.getBlock(x, y, z);
    }

    @Override
    public boolean isLoaded(int x, int y, int z) {
        return access.isLoaded(x, y, z);
    }

    @Override
    public void setBlock(int x, int y, int z, BlockType type) {
        BlockType previous = access.getBlock(x, y, z);
        if (previous == type || !access.isLoaded(x, y, z)) {
            return;
        }
        // Through the funnel, not Chunk#setBlock: bypassing it left settled
        // water beside a decayed leaf floating forever (WaterSim only wakes on
        // funnel notifications), and skipped mesh scheduling on worlds that
        // render. Not a player modification, so no client intent is emitted.
        if (!world.setBlockAt(x, y, z, type)) {
            return;
        }

        // Report the mutation to the integrated server's replication funnel
        // (installed on the headless server world only) so decay reaches clients
        // through the same per-section batches as player edits and water flow.
        ServerMutationSinks.BlockSink sink = world.serverSinks().blocks();
        if (sink != null) {
            sink.onServerBlockChange(x, y, z, type);
        }
    }

    @Override
    public void onChunkUnloaded(int chunkX, int chunkZ) {
        access.onChunkUnloaded(chunkX, chunkZ);
    }
}
