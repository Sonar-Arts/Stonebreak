package com.stonebreak.network.client.handlers;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.core.Game;
import com.stonebreak.network.packet.world.BlockChangeS2C;
import com.stonebreak.network.packet.world.BlockMetaS2C;
import com.stonebreak.network.packet.world.MultiBlockChangeS2C;
import com.stonebreak.world.World;

/**
 * Client-side: applies authoritative block changes to the local world via the
 * <b>non-broadcasting</b> {@code setBlockAt(..., false)} path, so applying an inbound change
 * never feeds back out as a new edit intent. Successor of the old {@code BlockSynchronizer}
 * CLIENT path.
 */
public final class ClientBlockHandler {

    public void applyBlockChange(BlockChangeS2C s) {
        World world = Game.getWorld();
        if (world == null) {
            return;
        }
        if (!world.setBlockAt(s.x(), s.y(), s.z(), resolve(s.blockTypeId()), false)) {
            onApplyFailed(world, s.x(), s.z());
        }
    }

    public void applyMultiBlock(MultiBlockChangeS2C m) {
        World world = Game.getWorld();
        if (world == null) {
            return;
        }
        int baseX = m.sectionX() * 16;
        int baseY = m.sectionY() * 16;
        int baseZ = m.sectionZ() * 16;
        boolean failed = false;
        for (int v : m.packed()) {
            int localPos = (v >>> 16) & 0xFFFF;
            int lx = (localPos >> 8) & 0xF;
            int ly = (localPos >> 4) & 0xF;
            int lz = localPos & 0xF;
            short blockId = (short) (v & 0xFFFF);
            if (!world.setBlockAt(baseX + lx, baseY + ly, baseZ + lz, resolve(blockId), false)) {
                failed = true;
            }
        }
        if (failed) {
            onApplyFailed(world, baseX, baseZ);
        }
    }

    /**
     * An authoritative block apply failed — meaningful desync only when the chunk should be
     * resident (within the keep radius). Beyond it, we simply dropped the chunk already and
     * a fresh snapshot arrives when it re-enters view; requesting there would churn.
     */
    private static void onApplyFailed(World world, int x, int z) {
        var player = Game.getPlayer();
        if (player == null) {
            return;
        }
        int cx = Math.floorDiv(x, 16);
        int cz = Math.floorDiv(z, 16);
        int pcx = (int) Math.floor(player.getPosition().x / 16.0);
        int pcz = (int) Math.floor(player.getPosition().z / 16.0);
        if (Math.max(Math.abs(cx - pcx), Math.abs(cz - pcz)) <= world.clientKeepRadius()) {
            com.stonebreak.network.MultiplayerSession.requestChunkResync(cx, cz);
        }
    }

    /**
     * Applies authoritative per-block metadata (currently snow layer counts; value 0 =
     * entry removed). Uses {@code putRaw} — the raw hydration path — so applying a server
     * echo never re-fires the client's own mutation listener.
     */
    public void applyBlockMeta(BlockMetaS2C m) {
        World world = Game.getWorld();
        if (world == null || m.metaKind() != BlockMetaS2C.KIND_SNOW_LAYERS
                || world.getSnowLayerManager() == null) {
            return;
        }
        int baseX = m.sectionX() * 16;
        int baseY = m.sectionY() * 16;
        int baseZ = m.sectionZ() * 16;
        for (int v : m.packed()) {
            int localPos = (v >>> 16) & 0xFFFF;
            int x = baseX + ((localPos >> 8) & 0xF);
            int y = baseY + ((localPos >> 4) & 0xF);
            int z = baseZ + (localPos & 0xF);
            int layers = v & 0xFFFF;
            if (layers == 0) {
                world.getSnowLayerManager().removeSnowLayers(x, y, z);
            } else {
                world.getSnowLayerManager().putRaw(x, y, z, layers);
            }
            world.triggerChunkRebuild(x, y, z);
        }
    }

    /**
     * Applies an authoritative per-block state string (furnace contents/progress/lit).
     * Writes the chunk's state map (remesh on render-state change is handled by
     * {@code scheduleChunkRemeshAt}) and feeds the DISPLAY furnace registry in place so an
     * open furnace UI tracks live.
     */
    public void applyBlockState(com.stonebreak.network.packet.world.BlockStateS2C s) {
        World world = Game.getWorld();
        if (world == null) {
            return;
        }
        var chunk = world.getChunkIfLoaded(Math.floorDiv(s.x(), 16), Math.floorDiv(s.z(), 16));
        if (chunk != null) {
            String prev = chunk.getBlockStates().get(
                com.stonebreak.world.chunk.utils.LocalBlockKey.pack(
                    Math.floorMod(s.x(), 16), s.y(), Math.floorMod(s.z(), 16)));
            chunk.setBlockState(Math.floorMod(s.x(), 16), s.y(), Math.floorMod(s.z(), 16), s.state());
            // Remesh only on a render-state flip (lit↔unlit) — contents/progress changes
            // arrive every cook tick and must not re-mesh the chunk each time.
            String prevRender = com.stonebreak.blocks.furnace.FurnaceState.extractRenderState(prev);
            String newRender = com.stonebreak.blocks.furnace.FurnaceState.extractRenderState(s.state());
            if (!java.util.Objects.equals(prevRender, newRender)) {
                world.scheduleChunkRemeshAt(s.x(), s.y(), s.z());
            }
        }
        if (world.getFurnaceRegistry() != null
                && s.state() != null
                && s.state().startsWith(com.stonebreak.blocks.furnace.FurnaceState.STATE_PREFIX)) {
            world.getFurnaceRegistry().applyAuthoritativeState(
                new com.openmason.engine.util.BlockPos(s.x(), s.y(), s.z()), s.state());
        }
    }

    private static BlockType resolve(short blockTypeId) {
        BlockType type = BlockType.getById(blockTypeId & 0xFFFF);
        return type == null ? BlockType.AIR : type;
    }
}
