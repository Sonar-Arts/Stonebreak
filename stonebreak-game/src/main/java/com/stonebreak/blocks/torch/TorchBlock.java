package com.stonebreak.blocks.torch;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.items.Item;
import com.stonebreak.items.ItemType;
import com.stonebreak.world.World;
import org.joml.Vector3i;

/**
 * Game rules for the torch: the {@code stonebreak:torch} ITEM places the
 * {@code stonebreak:torch_placed} BLOCK, which must sit on top of a block that
 * can hold one ({@link BlockType#canHoldTorch()} — Ground) or hang from one
 * (Side), never under one. Torches have no collision (the animated-block
 * collision pass skips them), are washed away by water
 * ({@code FlowBlockInteraction}) and pop off when their support is removed
 * ({@link #popUnsupported}, called from the {@code World.setBlockAt} funnel for
 * every authoritative block change — issue #247).
 */
public final class TorchBlock {

    public static final String ITEM_OBJECT_ID = "stonebreak:torch";

    private TorchBlock() {}

    /** The placed torch block type. */
    public static BlockType block() {
        return BlockType.TORCH_PLACED;
    }

    /** The torch item, or {@code null} if its SBO failed to register. */
    public static ItemType item() {
        return ItemType.getByObjectId(ITEM_OBJECT_ID);
    }

    public static boolean isTorch(BlockType type) {
        return type != null && type == BlockType.TORCH_PLACED;
    }

    /** True when the held item is the torch item (which places the torch block). */
    public static boolean isTorchItem(Item item) {
        return item != null && item instanceof ItemType && item == item();
    }

    /**
     * The state a torch placed into {@code placePos} against {@code hitBlock}
     * takes, or {@code null} when the targeted face cannot hold one (the
     * underside of a block, or a support that can't hold a torch — another
     * torch, foliage, the door's swinging panel).
     */
    public static TorchState placementState(World world, Vector3i hitBlock, Vector3i placePos) {
        int nx = placePos.x - hitBlock.x;
        int ny = placePos.y - hitBlock.y;
        int nz = placePos.z - hitBlock.z;
        if (Math.abs(nx) + Math.abs(ny) + Math.abs(nz) != 1) return null;
        BlockType support = world.getBlockAt(hitBlock.x, hitBlock.y, hitBlock.z);
        if (support == null || !support.canHoldTorch()) return null;
        return TorchState.forPlacementNormal(nx, ny, nz);
    }

    /** True when the block a torch at {@code (x,y,z)} with {@code state} rests on/hangs from can hold a torch. */
    public static boolean isSupported(World world, int x, int y, int z, TorchState state) {
        BlockType support = world.getBlockAt(
                x + state.supportDx(), y + state.supportDy(), z + state.supportDz());
        return support != null && support.canHoldTorch();
    }

    /**
     * Validates a client-proposed placement state string against the server
     * world: returns the normalized state when the torch would be supported,
     * else {@code null} (the server reverts the placement).
     */
    public static TorchState validatePlacement(World world, int x, int y, int z, String proposed) {
        TorchState state = TorchState.parse(proposed);
        return isSupported(world, x, y, z, state) ? state : null;
    }

    /**
     * Torch cells adjacent to {@code (x,y,z)} that were held up by the block
     * there and are now unsupported — to be broken by the caller (authoritative
     * side) once that block has changed to something non-solid. The raw
     * {@link BlockType#isSolid()} flag (not {@link BlockType#canHoldTorch()})
     * governs the "cell still holds things up" early-return: a cell that still
     * contains a solid block — even foliage or a door, which can't hold a torch
     * at placement — must not pop the torches next to it.
     */
    public static java.util.List<Vector3i> findUnsupported(World world, int x, int y, int z) {
        return findUnsupported(world, x, y, z, false);
    }

    /**
     * {@link #findUnsupported} with {@code treatCellAsNonSupport} — the caller
     * asserts the block in the cell no longer holds things up even though its
     * TYPE is still solid. Used for state-only changes: a door toggled open
     * keeps its (solid) type, so a torch mounted on it hangs off the swung
     * panel and must pop.
     */
    public static java.util.List<Vector3i> findUnsupported(World world, int x, int y, int z,
                                                           boolean treatCellAsNonSupport) {
        BlockType now = world.getBlockAt(x, y, z);
        if (!treatCellAsNonSupport && now != null && now.isSolid()) return java.util.List.of();
        java.util.List<Vector3i> out = null;
        // Above (ground torch) and the four horizontal neighbours (side torches).
        int[][] offsets = {{0, 1, 0}, {1, 0, 0}, {-1, 0, 0}, {0, 0, 1}, {0, 0, -1}};
        for (int[] o : offsets) {
            int tx = x + o[0], ty = y + o[1], tz = z + o[2];
            if (!isTorch(world.getBlockAt(tx, ty, tz))) continue;
            TorchState state = TorchState.parse(world.getBlockStateAt(tx, ty, tz));
            // Only torches whose support IS this cell.
            if (tx + state.supportDx() != x || ty + state.supportDy() != y || tz + state.supportDz() != z) continue;
            if (out == null) out = new java.util.ArrayList<>(2);
            out.add(new Vector3i(tx, ty, tz));
        }
        return out == null ? java.util.List.of() : out;
    }

    /**
     * Pops every torch adjacent to {@code (x,y,z)} held up by the block there
     * and now unsupported (issue #247): drops the torch item authoritatively
     * (no player needed — the no-tool overload scatters like a block break),
     * removes the torch block through the {@code World.setBlockAt} funnel so
     * everything watching it (water sim, animated-block registry, mesh
     * scheduling) observes the removal, and reports the AIR write through the
     * integrated server's replication sink so remote clients see the pop
     * through the same per-section batches as sim edits. The caller on the
     * authoritative side owns the sweep; {@link #findUnsupported} self-gates on
     * solid cells, so pops of already-removed torches (e.g. the host edit
     * round-tripping through {@code applyAccepted} after the funnel sweep)
     * find nothing and are no-ops.
     *
     * @return the popped torch positions (part of the caller's bookkeeping;
     *         empty when nothing popped)
     */
    public static java.util.List<Vector3i> popUnsupported(World world, int x, int y, int z) {
        return popUnsupported(world, x, y, z, false);
    }

    /**
     * {@link #popUnsupported} with {@code treatCellAsNonSupport} — the caller
     * asserts the block in the cell no longer holds things up even though its
     * TYPE is still solid (the door toggled open: solid type, swung panel).
     */
    public static java.util.List<Vector3i> popUnsupported(World world, int x, int y, int z,
                                                          boolean treatCellAsNonSupport) {
        java.util.List<Vector3i> unsupported =
                findUnsupported(world, x, y, z, treatCellAsNonSupport);
        for (Vector3i t : unsupported) {
            com.stonebreak.util.DropUtil.handleBlockBroken(world,
                    new org.joml.Vector3f(t.x + 0.5f, t.y + 0.5f, t.z + 0.5f), block());
            world.setBlockAt(t.x, t.y, t.z, BlockType.AIR, false);
            com.stonebreak.world.ServerMutationSinks.BlockSink sink = world.serverSinks().blocks();
            if (sink != null) {
                sink.onServerBlockChange(t.x, t.y, t.z, BlockType.AIR);
            }
        }
        return unsupported;
    }
}
