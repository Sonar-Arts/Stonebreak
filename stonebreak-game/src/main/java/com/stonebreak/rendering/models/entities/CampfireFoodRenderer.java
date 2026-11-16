package com.stonebreak.rendering.models.entities;

import com.openmason.engine.rendering.RenderOrigin;
import com.openmason.engine.util.BlockPos;
import com.stonebreak.blocks.BlockType;
import com.stonebreak.blocks.campfire.CampfireState;
import com.stonebreak.core.Game;
import com.stonebreak.crafting.SmeltingManager;
import com.stonebreak.crafting.SmeltingRecipe;
import com.stonebreak.items.Item;
import com.stonebreak.items.ItemStack;
import com.stonebreak.items.ItemType;
import com.stonebreak.world.World;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Draws what is cooking on campfires: each spot's food lies on its own stone of the ring and turns,
 * pixel by pixel, into what it cooks into ({@code VoxelizedSpriteRenderer.renderCookingSprite}).
 *
 * <p>A client holds no campfire registry — only the fire's state string, rewritten when food goes
 * on or comes off. That string carries each spot's progress when it was written; this renderer
 * notes when it last saw the string change and runs the cooking clock on from there, the same
 * fixed {@link CampfireState#COOK_SECONDS} the server cooks by.
 *
 * <p>Like the meat rack's food, the sprites go on the {@link SocketItemQueue} for the drop pass.
 */
final class CampfireFoodRenderer {

    /**
     * One stone per spot, in model units about the cell centre (SB_Campfire's Stone0/2/4/6, a
     * quarter turn apart): x, z, and the stone's top.
     */
    private static final float[][] STONES = {
            {0.367f, 0.075f, -0.3125f},
            {-0.075f, 0.367f, -0.3125f},
            {-0.367f, -0.075f, -0.375f},
            {0.075f, -0.367f, -0.3125f},
    };
    /** Food sits a little inside the stone's centre, so it overhangs the fire, not the block edge. */
    private static final float INSET = 0.035f;
    /** Clearance from the stone's top to the sprite's centre (it is about 0.02 thick). */
    private static final float LIFT = 0.014f;
    /** Sprite size in blocks (the 16-pixel icon is one socket unit). */
    private static final float SCALE = 0.3f;
    private static final float MAX_DISTANCE = 48.0f;
    /** Lays the sprite on its back: its long axis level, its face up. */
    private static final float LAY_FLAT = (float) Math.toRadians(-90.0);

    /** When this client last saw a fire's state string change. */
    private record Seen(String raw, CampfireState state, float at) {}

    /** What an item id draws as on the fire, and what it draws cooked; {@code raw} null for neither. */
    private record Food(ItemType raw, ItemType cooked) {
        static final Food NONE = new Food(null, null);
    }

    private final SocketItemQueue queue;
    private final Matrix4f spot = new Matrix4f();
    private final Map<BlockPos, Seen> seen = new HashMap<>();
    private final Map<Integer, Food> foods = new HashMap<>();

    CampfireFoodRenderer(SocketItemQueue queue) {
        this.queue = queue;
    }

    void queue(World world, Vector3f cameraPos) {
        if (world == null || cameraPos == null) {
            return;
        }
        Game game = Game.getInstance();
        SmeltingManager smelting = game != null ? game.getSmeltingManager() : null;
        float now = game != null ? game.getTotalTimeElapsed() : 0.0f;

        Set<BlockPos> positions = world.getAnimatedBlockRegistry().positions();
        if (seen.size() > positions.size()) {
            seen.keySet().retainAll(positions);
        }
        float max2 = MAX_DISTANCE * MAX_DISTANCE;
        for (BlockPos p : positions) {
            float dx = p.x() + 0.5f - cameraPos.x;
            float dy = p.y() + 0.5f - cameraPos.y;
            float dz = p.z() + 0.5f - cameraPos.z;
            if (dx * dx + dy * dy + dz * dz > max2 || world.getBlockAt(p.x(), p.y(), p.z()) != BlockType.CAMPFIRE) {
                continue;
            }
            String raw = world.getBlockStateAt(p.x(), p.y(), p.z());
            if (raw == null || !raw.contains(";cook=")) {
                seen.remove(p);
                continue;
            }
            Seen s = seen.get(p);
            if (s == null || !raw.equals(s.raw())) {
                s = new Seen(raw, CampfireState.parse(p, raw), now);
                seen.put(p, s);
            }
            queueFire(p, s, now - s.at(), smelting);
        }
    }

    private void queueFire(BlockPos p, Seen s, float elapsed, SmeltingManager smelting) {
        for (int i = 0; i < CampfireState.SLOTS && i < STONES.length; i++) {
            int id = s.state().itemAt(i);
            if (id == 0) {
                continue;
            }
            Food food = foods.get(id);
            if (food == null) {
                if (smelting == null) {
                    continue; // recipes not loaded yet; ask again next frame
                }
                food = foodFor(id, smelting);
                foods.put(id, food);
            }
            if (food.raw() == null) {
                continue; // a block cooking (sand to glass) has no flat sprite to lay down
            }
            float cooked = (s.state().progressAt(i) + elapsed) / CampfireState.COOK_SECONDS;
            float[] stone = STONES[i];
            float radius = (float) Math.sqrt(stone[0] * stone[0] + stone[1] * stone[1]);
            float pull = (radius - INSET) / radius;
            // Long axis along the ring, so food follows the stones round the fire.
            float yaw = (float) (Math.PI - Math.atan2(stone[1], stone[0]));
            RenderOrigin.modelAt(spot,
                            p.x() + 0.5f + stone[0] * pull,
                            p.y() + 0.5f + stone[2] + LIFT,
                            p.z() + 0.5f + stone[1] * pull)
                    .rotateY(yaw)
                    .rotateX(LAY_FLAT)
                    .scale(SCALE);
            queue.add(food.raw(), null, spot, food.cooked(), cooked);
        }
    }

    private static Food foodFor(int id, SmeltingManager smelting) {
        Item item = new ItemStack(id, 1).getItem();
        if (!(item instanceof ItemType raw)) {
            return Food.NONE;
        }
        SmeltingRecipe recipe = smelting.getRecipe(new ItemStack(raw, 1));
        Item out = recipe != null ? recipe.getOutput().getItem() : null;
        return new Food(raw, out instanceof ItemType cooked ? cooked : null);
    }
}
