package com.stonebreak.rendering.models.entities;

import com.openmason.engine.rendering.RenderOrigin;
import com.openmason.engine.util.BlockPos;
import com.stonebreak.blocks.BlockType;
import com.stonebreak.blocks.meatrack.MeatRackState;
import com.stonebreak.items.ItemStack;
import com.stonebreak.items.ItemType;
import com.stonebreak.world.World;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * Draws the food hanging on meat racks: each filled hook shows its food's voxelized sprite under
 * the twine loop on the crossbar, so a rack shows what it stores.
 *
 * <p>Voxelized items draw with the world shader in the drop pass, so each one is queued on the
 * {@link SocketItemQueue} like a mob's held item. The socket frame tilts a sprite 45° (handle to
 * tip upright); the hook matrix tilts it back, so the food hangs the way its icon looks.
 */
final class MeatRackItemRenderer {

    /** Hook centres across the rack in block units, matching the twine loops in SB_Meat_Rack. */
    private static final float[] HOOK_X = {4.5f / 16f, 8.0f / 16f, 11.5f / 16f};
    /** Where a hanging sprite is centred, just under the loops. */
    private static final float HANG_Y = 8.0f / 16f;
    /** Neighbouring pieces overlap a little: the middle one hangs a hair in front. */
    private static final float[] HOOK_Z = {0.5f, 0.5f + 0.6f / 16f, 0.5f};
    /** Sprite size in blocks (the 16-pixel icon is one socket unit). */
    private static final float SCALE = 0.3f;
    /** No point drawing food the size of a pixel. */
    private static final float MAX_DISTANCE = 48.0f;
    private static final float UNTILT = (float) Math.toRadians(-45.0);

    private final SocketItemQueue queue;
    private final Matrix4f hook = new Matrix4f();

    MeatRackItemRenderer(SocketItemQueue queue) {
        this.queue = queue;
    }

    void queue(World world, Vector3f cameraPos) {
        if (world == null || cameraPos == null) {
            return;
        }
        float max2 = MAX_DISTANCE * MAX_DISTANCE;
        for (MeatRackState rack : world.getMeatRackRegistry().all()) {
            BlockPos p = rack.getPos();
            float dx = p.x() + 0.5f - cameraPos.x;
            float dy = p.y() + 0.5f - cameraPos.y;
            float dz = p.z() + 0.5f - cameraPos.z;
            if (dx * dx + dy * dy + dz * dz > max2 || rack.isEmpty()
                    // A display copy outlives a rack broken elsewhere; draw only standing racks.
                    || world.getBlockAt(p.x(), p.y(), p.z()) != BlockType.MEAT_RACK) {
                continue;
            }
            ItemStack[] hooks = rack.getSlots();
            for (int i = 0; i < hooks.length && i < HOOK_X.length; i++) {
                ItemStack food = hooks[i];
                if (food == null || food.isEmpty() || !(food.getItem() instanceof ItemType type)) {
                    continue;
                }
                RenderOrigin.modelAt(hook, p.x() + HOOK_X[i], p.y() + HANG_Y, p.z() + HOOK_Z[i])
                        .rotateZ(UNTILT)
                        .scale(SCALE);
                queue.add(type, food.getState(), hook);
            }
        }
    }
}
