package com.stonebreak.network.client.handlers;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.core.Game;
import com.stonebreak.network.packet.world.BlockChangeS2C;
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
        world.setBlockAt(s.x(), s.y(), s.z(), resolve(s.blockTypeId()), false);
    }

    public void applyMultiBlock(MultiBlockChangeS2C m) {
        World world = Game.getWorld();
        if (world == null) {
            return;
        }
        int baseX = m.sectionX() * 16;
        int baseY = m.sectionY() * 16;
        int baseZ = m.sectionZ() * 16;
        for (int v : m.packed()) {
            int localPos = (v >>> 16) & 0xFFFF;
            int lx = (localPos >> 8) & 0xF;
            int ly = (localPos >> 4) & 0xF;
            int lz = localPos & 0xF;
            short blockId = (short) (v & 0xFFFF);
            world.setBlockAt(baseX + lx, baseY + ly, baseZ + lz, resolve(blockId), false);
        }
    }

    private static BlockType resolve(short blockTypeId) {
        BlockType type = BlockType.getById(blockTypeId & 0xFFFF);
        return type == null ? BlockType.AIR : type;
    }
}
