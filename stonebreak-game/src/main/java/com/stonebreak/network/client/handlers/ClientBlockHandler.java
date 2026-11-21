package com.stonebreak.network.client.handlers;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.core.Game;
import com.stonebreak.network.packet.world.BlockChangeS2C;
import com.stonebreak.network.packet.world.BlockMetaS2C;
import com.stonebreak.network.packet.world.MultiBlockChangeS2C;
import com.stonebreak.world.World;
import com.stonebreak.world.operations.WorldConfiguration;

/**
 * Client-side: applies authoritative block changes to the local world via the
 * <b>non-broadcasting</b> {@code setBlockAt(..., false)} path, so applying an inbound change
 * never feeds back out as a new edit intent. Successor of the old {@code BlockSynchronizer}
 * CLIENT path.
 */
public final class ClientBlockHandler {

    /**
     * Ordering barrier: cube records install under a per-frame budget, so any packet mutating a cube whose
     * record is still queued must wait for it - applying first would fail or be overwritten by the older
     * record.
     */
    private final ClientChunkHandler chunkHandler;

    public ClientBlockHandler(ClientChunkHandler chunkHandler) {
        this.chunkHandler = chunkHandler;
    }

    public void applyBlockChange(BlockChangeS2C s) {
        if (chunkHandler.runAfterPendingInstall(s.x(), s.y(), s.z(), () -> applyBlockChange(s))) {
            return;
        }
        World world = Game.getWorld();
        if (world == null || !WorldConfiguration.inBounds(s.y())) {
            return;
        }
        // A cube this client does not hold is simply not changed: its stream requests it when it wants it,
        // and the server sends current content then.
        world.setBlockAt(s.x(), s.y(), s.z(), resolve(s.blockTypeId()), false);
    }

    public void applyMultiBlock(MultiBlockChangeS2C m) {
        long baseX = m.sectionX() << 4;
        long baseY = m.sectionY() << 4;
        long baseZ = m.sectionZ() << 4;
        if (chunkHandler.runAfterPendingInstall(baseX, baseY, baseZ, () -> applyMultiBlock(m))) {
            return;
        }
        World world = Game.getWorld();
        if (world == null) {
            return;
        }
        for (int v : m.packed()) {
            int localPos = (v >>> 16) & 0xFFFF;
            long y = baseY + ((localPos >> 4) & 0xF);
            if (!WorldConfiguration.inBounds(y)) {
                continue;
            }
            short blockId = (short) (v & 0xFFFF);
            world.setBlockAt(baseX + ((localPos >> 8) & 0xF), y, baseZ + (localPos & 0xF), resolve(blockId), false);
        }
    }

    /**
     * Applies authoritative per-block metadata: snow layer counts or water flow levels
     * (value 0 = entry removed). Snow uses {@code putRaw} — the raw hydration path — so
     * applying a server echo never re-fires the client's own mutation listener; water
     * writes the chunk's water layer directly (the render world runs no sim).
     */
    public void applyBlockMeta(BlockMetaS2C m) {
        if (chunkHandler.runAfterPendingInstall(m.sectionX() << 4, m.sectionY() << 4, m.sectionZ() << 4,
                () -> applyBlockMeta(m))) {
            return; // the cube record carries the snow/water layers too - order matters
        }
        World world = Game.getWorld();
        if (world == null) {
            return;
        }
        switch (m.metaKind()) {
            case BlockMetaS2C.KIND_SNOW_LAYERS -> applySnowMeta(world, m);
            case BlockMetaS2C.KIND_WATER_LEVEL -> applyWaterMeta(world, m);
            default -> { }
        }
    }

    private static void applySnowMeta(World world, BlockMetaS2C m) {
        if (world.getSnowLayerManager() == null) {
            return;
        }
        long baseX = m.sectionX() << 4;
        long baseY = m.sectionY() << 4;
        long baseZ = m.sectionZ() << 4;
        for (int v : m.packed()) {
            int localPos = (v >>> 16) & 0xFFFF;
            long x = baseX + ((localPos >> 8) & 0xF);
            long y = baseY + ((localPos >> 4) & 0xF);
            long z = baseZ + (localPos & 0xF);
            if (!WorldConfiguration.inBounds(y)) {
                continue;
            }
            int layers = v & 0xFFFF;
            // SNOW layer of the cube (native remesh); putRaw never re-fires the client's mutation listener.
            if (layers == 0) {
                world.getSnowLayerManager().removeSnowLayers(x, y, z);
            } else {
                world.getSnowLayerManager().putRaw(x, y, z, layers);
            }
        }
    }

    /**
     * Water values (game vocabulary: 0 source, 1..7 flowing, 8 falling, 9..16 river) written into the cube's
     * WATER layer (native remesh). Dropped silently when the cube is not loaded: the cube record carries the
     * layer when the client gets it.
     */
    private static void applyWaterMeta(World world, BlockMetaS2C m) {
        long baseX = m.sectionX() << 4;
        long baseY = m.sectionY() << 4;
        long baseZ = m.sectionZ() << 4;
        for (int v : m.packed()) {
            int localPos = (v >>> 16) & 0xFFFF;
            long y = baseY + ((localPos >> 4) & 0xF);
            int value = v & 0xFFFF;
            if (value > com.stonebreak.world.chunk.ChunkWaterLayer.MAX_VALUE || !WorldConfiguration.inBounds(y)) {
                continue; // out-of-range - never write garbage into the layer
            }
            world.setWaterValueAt(baseX + ((localPos >> 8) & 0xF), y, baseZ + (localPos & 0xF), value);
        }
    }

    /**
     * Applies an authoritative per-block state string (furnace contents/progress/lit,
     * crafting-table grid, ...). Writes the chunk's state map (remesh on render-state change
     * is handled by {@code scheduleChunkRemeshAt}) and feeds the DISPLAY furnace/workbench
     * registries in place so an open furnace or crafting-table UI tracks live.
     */
    public void applyBlockState(com.stonebreak.network.packet.world.BlockStateS2C s) {
        if (chunkHandler.runAfterPendingInstall(s.x(), s.y(), s.z(), () -> applyBlockState(s))) {
            return; // the cube record replaces the cube's states - order matters
        }
        World world = Game.getWorld();
        if (world == null) {
            return;
        }
        // A state for a cube this client doesn't hold has nothing to update; creating a
        // registry entry for it would only leak (no cube unload ever clears it).
        if (!world.isLoaded(s.x(), s.y(), s.z())) {
            return;
        }
        // World.setBlockStateAt: STATE layer variant id (native remesh on change), payload in the cube,
        // physics + animated-block bookkeeping. The variant's id is the server's: the registry names
        // (BlockStateNamesS2C) are mirrored before any echo that can carry a new one.
        world.setBlockStateAt(s.x(), s.y(), s.z(), s.state());
        if (s.x() != (int) s.x() || s.y() != (int) s.y() || s.z() != (int) s.z()) {
            return; // the furnace/workbench registries are int-keyed (BlockPos)
        }
        if (world.getFurnaceRegistry() != null
                && s.state() != null
                && s.state().startsWith(com.stonebreak.blocks.furnace.FurnaceState.STATE_PREFIX)) {
            world.getFurnaceRegistry().applyAuthoritativeState(
                new com.openmason.engine.util.BlockPos((int) s.x(), (int) s.y(), (int) s.z()), s.state(), s.ack());
        }
        if (com.stonebreak.blocks.workbench.WorkbenchState.isWorkbenchState(s.state())) {
            world.getWorkbenchRegistry().applyAuthoritativeState(
                new com.openmason.engine.util.BlockPos((int) s.x(), (int) s.y(), (int) s.z()), s.state(), s.ack());
        }
        if (com.stonebreak.blocks.container.ChestState.isChestState(s.state())) {
            world.getChestRegistry().applyAuthoritativeState(
                new com.openmason.engine.util.BlockPos(Math.toIntExact(s.x()), Math.toIntExact(s.y()), Math.toIntExact(s.z())), s.state(), s.ack());
        }
        if (com.stonebreak.blocks.meatrack.MeatRackState.isMeatRackState(s.state())) {
            world.getMeatRackRegistry().applyAuthoritativeState(
                new com.openmason.engine.util.BlockPos(Math.toIntExact(s.x()), Math.toIntExact(s.y()), Math.toIntExact(s.z())), s.state(), s.ack());
        }
    }

    private static BlockType resolve(short blockTypeId) {
        BlockType type = BlockType.getById(blockTypeId & 0xFFFF);
        return type == null ? BlockType.AIR : type;
    }
}
