package com.stonebreak.blocks.door;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.core.Game;
import com.stonebreak.network.MultiplayerSession;
import com.stonebreak.world.World;

/**
 * Right-click handler for door blocks. The toggle is an intent sent to the
 * authoritative server ({@code BlockToggleC2S}) — every session mode runs one,
 * including singleplayer — which flips the block state and echoes the result
 * to all clients as {@code BlockStateS2C}. The AnimatedBlockRenderer notices
 * the state flip and plays the new state's clip (Open/Closed swings are
 * play-once, so the door holds its final pose).
 */
public final class DoorInteraction {

    private DoorInteraction() {
        throw new UnsupportedOperationException("Utility class");
    }

    public static void toggle(int x, int y, int z) {
        if (MultiplayerSession.sendBlockToggle(x, y, z)) {
            return; // server will echo the authoritative state
        }
        // No live connection (bare/test world) — flip locally.
        World world = Game.getWorld();
        if (world == null || world.getBlockAt(x, y, z) != BlockType.OAK_DOOR) {
            return;
        }
        DoorState next = DoorState.parse(world.getBlockStateAt(x, y, z)).toggled();
        world.setBlockStateAt(x, y, z, next.toStateString());
    }
}
