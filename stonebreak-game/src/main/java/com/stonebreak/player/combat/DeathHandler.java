package com.stonebreak.player.combat;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.core.Game;
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

        // #357: tell the server first, on the reliable channel, so it stops handing drops to
        // this player before the death scatter below lands at the corpse.
        com.stonebreak.network.MultiplayerSession.sendPlayerLife(true, position);

        // #351: return panel-held stacks (cursor, crafting grids, furnace carried stack) to the
        // inventory and close the panels, so the drop below sweeps them up with everything else.
        Game.getInstance().getStateController().onPlayerDied();

        dropAllItems();
        state.getVelocity().set(0, 0, 0);

        System.out.println("DEBUG: Player death handling complete");
    }

    private void dropAllItems() {
        System.out.println("DEBUG: dropAllItems() called - dropping all inventory items on death");
        // Session play: ONE death-drain request. The server spawns every drop from its own
        // authoritative replica — no toss rate limit — and the InventorySyncS2C confirms the
        // cleared mirror; the local clear below is only the prediction.
        boolean serverDrains = com.stonebreak.network.MultiplayerSession.sendDeathDrop();
        for (int i = 0; i < Inventory.HOTBAR_SIZE; i++) {
            ItemStack stack = inventory.getHotbarSlot(i);
            if (stack != null && !stack.isEmpty()) {
                if (!serverDrains) {
                    dropItemStack(stack);
                }
                stack.clear();
            }
        }
        for (int i = 0; i < Inventory.MAIN_INVENTORY_SIZE; i++) {
            ItemStack stack = inventory.getMainInventorySlot(i);
            if (stack != null && !stack.isEmpty()) {
                if (!serverDrains) {
                    dropItemStack(stack);
                }
                stack.clear();
            }
        }
    }

    private void dropItemStack(ItemStack stack) {
        // No-session fallback only (battle-test / headless): with a live session,
        // dropAllItems sends ONE DeathDropC2S and the server scatters the replica instead.
        // The local paths spawn into the passed world's EntityManager, which on a client
        // render world is null — death drops would silently vanish in the two-world model.
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

        // Respawn at the world spawn (the host-selected/random spawn frozen into the world),
        // falling back to the fixed constant only if the world/spawn isn't available.
        Vector3f worldSpawn = (world != null) ? world.getSpawnPosition() : null;
        if (worldSpawn != null) {
            state.getPosition().set(worldSpawn);
        } else {
            state.getPosition().set(SPAWN_X, SPAWN_Y, SPAWN_Z);
        }
        state.getVelocity().set(0, 0, 0);
        state.setPreviousY(state.getPosition().y);
        state.setWasFalling(false);

        health.enableSpawnProtection();
        System.out.println("Spawn protection enabled - fall damage disabled for " + SPAWN_PROTECTION_DURATION + " seconds");

        Vector3f p = state.getPosition();
        camera.setPosition(p.x, p.y + CAMERA_EYE_OFFSET, p.z);

        // #357: alive again, AT the respawn point — the server must not resume pickups for a
        // body it still places at the corpse, beside the death drops.
        com.stonebreak.network.MultiplayerSession.sendPlayerLife(false, p);

        System.out.println("DEBUG: Respawn complete");
    }
}
