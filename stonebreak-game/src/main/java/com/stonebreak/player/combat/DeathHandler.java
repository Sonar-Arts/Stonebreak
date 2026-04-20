package com.stonebreak.player.combat;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.items.Inventory;
import com.stonebreak.items.ItemStack;
import com.stonebreak.player.Camera;
import com.stonebreak.player.state.PhysicsState;
import com.stonebreak.util.DropUtil;
import com.stonebreak.world.World;
import org.joml.Vector3f;

import static com.stonebreak.player.PlayerConstants.CAMERA_EYE_OFFSET;
import static com.stonebreak.player.PlayerConstants.SPAWN_PROTECTION_DURATION;
import static com.stonebreak.player.PlayerConstants.SPAWN_X;
import static com.stonebreak.player.PlayerConstants.SPAWN_Y;
import static com.stonebreak.player.PlayerConstants.SPAWN_Z;

/**
 * Owns terminal death side effects (drop all inventory, zero velocity, set death flag)
 * and respawn logic (reset position, health, spawn protection). Polls
 * {@link HealthController#isDead()} each frame to detect death transitions.
 */
public class DeathHandler {

    private final PhysicsState state;
    private final HealthController health;
    private final Inventory inventory;
    private final Camera camera;
    private World world;
    private boolean deathHandled;

    public DeathHandler(PhysicsState state, HealthController health, Inventory inventory,
                        Camera camera, World world) {
        this.state = state;
        this.health = health;
        this.inventory = inventory;
        this.camera = camera;
        this.world = world;
    }

    public void setWorld(World world) {
        this.world = world;
    }

    /** Called each frame. Triggers die() side effects exactly once per death. */
    public void processDeathIfNeeded() {
        if (health.isDead() && !deathHandled) {
            die();
            deathHandled = true;
        }
    }

    private void die() {
        Vector3f position = state.getPosition();
        System.out.println("DEBUG: Player died at position: " + position);
        System.out.println("DEBUG: Checking inventory BEFORE dropAllItems():");
        for (int i = 0; i < Inventory.HOTBAR_SIZE; i++) {
            ItemStack stack = inventory.getHotbarSlot(i);
            if (stack != null && !stack.isEmpty()) {
                System.out.println("  Hotbar slot " + i + " HAS ITEMS: " + stack.getCount());
            }
        }

        dropAllItems();
        state.getVelocity().set(0, 0, 0);

        System.out.println("DEBUG: Player death handling complete");
    }

    private void dropAllItems() {
        System.out.println("DEBUG: dropAllItems() called - dropping all inventory items on death");
        for (int i = 0; i < Inventory.HOTBAR_SIZE; i++) {
            ItemStack stack = inventory.getHotbarSlot(i);
            if (stack != null && !stack.isEmpty()) {
                dropItemStack(stack);
                stack.clear();
            }
        }
        for (int i = 0; i < Inventory.MAIN_INVENTORY_SIZE; i++) {
            ItemStack stack = inventory.getMainInventorySlot(i);
            if (stack != null && !stack.isEmpty()) {
                dropItemStack(stack);
                stack.clear();
            }
        }
    }

    private void dropItemStack(ItemStack stack) {
        Vector3f p = state.getPosition();
        Vector3f dropPosition = new Vector3f(p.x, p.y + 0.5f, p.z);
        if (stack.isPlaceable()) {
            BlockType blockType = stack.asBlockType();
            if (blockType != null) {
                for (int j = 0; j < stack.getCount(); j++) {
                    DropUtil.createBlockDrop(world, dropPosition, blockType);
                }
            }
        } else {
            DropUtil.createItemDrop(world, dropPosition, stack.copy());
        }
    }

    public void respawn() {
        System.out.println("DEBUG: Respawn called");

        health.restoreFullHealth();
        deathHandled = false;

        state.getPosition().set(SPAWN_X, SPAWN_Y, SPAWN_Z);
        state.getVelocity().set(0, 0, 0);
        state.setPreviousY(state.getPosition().y);
        state.setWasFalling(false);

        health.enableSpawnProtection();
        System.out.println("Spawn protection enabled - fall damage disabled for " + SPAWN_PROTECTION_DURATION + " seconds");

        Vector3f p = state.getPosition();
        camera.setPosition(p.x, p.y + CAMERA_EYE_OFFSET, p.z);

        System.out.println("DEBUG: Respawn complete");
    }
}
