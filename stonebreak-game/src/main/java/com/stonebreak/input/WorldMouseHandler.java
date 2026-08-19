package com.stonebreak.input;

import org.joml.Vector3f;
import org.joml.Vector3i;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.blocks.door.DoorInteraction;
import com.stonebreak.core.Game;
import com.stonebreak.items.ItemStack;
import com.stonebreak.items.ItemType;
import com.stonebreak.mobs.entities.EntityManager;
import com.stonebreak.mobs.entities.FishingBobber;
import com.stonebreak.mobs.entities.FishingManager;
import com.stonebreak.mobs.entities.LivingEntity;
import com.stonebreak.network.MultiplayerSession;
import com.stonebreak.network.packet.entity.ProjectileSpawnC2S;
import com.stonebreak.player.Player;

import static org.lwjgl.glfw.GLFW.GLFW_MOUSE_BUTTON_LEFT;
import static org.lwjgl.glfw.GLFW.GLFW_MOUSE_BUTTON_MIDDLE;
import static org.lwjgl.glfw.GLFW.GLFW_MOUSE_BUTTON_RIGHT;
import static org.lwjgl.glfw.GLFW.GLFW_PRESS;
import static org.lwjgl.glfw.GLFW.GLFW_RELEASE;

/**
 * World interaction clicks while PLAYING: left-click attack, right-click item
 * use (bow, staff, fishing rod, food) and block interaction (workbench,
 * furnace, door, placement), plus the bow release on right-button up.
 */
final class WorldMouseHandler {

    // Staff fire-bolt cast cooldown so right-click spam can't flood the world
    // with projectiles. Minimum 0.4s between casts.
    private static final long STAFF_CAST_COOLDOWN_NANOS = 400_000_000L;
    private long lastFireBoltCastNanos = 0L;

    // Resolves fishing catches (loot roll + drop spawn) when a bobber is reeled in.
    private final FishingManager fishingManager = new FishingManager();

    /** Handles a mouse button event that reached the world (no UI consumed it, state is PLAYING). */
    void handleMouseButton(int button, int action) {
        Player player = Game.getPlayer();
        if (player == null) {
            return;
        }

        if (action == GLFW_PRESS) {
            if (button == GLFW_MOUSE_BUTTON_LEFT) {
                handleAttack(player);
            } else if (button == GLFW_MOUSE_BUTTON_RIGHT) {
                handleUse(player);
            } else if (button == GLFW_MOUSE_BUTTON_MIDDLE) {
                // Inventory Tweaks-style: sort the inventory anywhere, even mid-world.
                player.getInventory().sortInventory();
            }
        } else if (action == GLFW_RELEASE && button == GLFW_MOUSE_BUTTON_RIGHT) {
            handleBowRelease(player);
        }
    }

    /** Left press: swing, and strike the first living entity under the crosshair. Block breaking is continuous, handled per-frame. */
    private void handleAttack(Player player) {
        player.startAttackAnimation();
        EntityManager em = Game.getEntityManager();
        if (em != null) {
            LivingEntity target = player.getRaycastEngine().raycastEntity(em.getLivingEntities());
            if (target != null) {
                player.attackEntity(target);
            }
        }
    }

    /** Right press: held-item actions first (bow/staff/rod/food), then block interaction/placement. */
    private void handleUse(Player player) {
        ItemStack held = player.getInventory().getSelectedHotbarSlot();

        // Bow → start drawing (no attack swing).
        if (!held.isEmpty() && held.getItem() == ItemType.BOW) {
            player.getBowController().startDrawing();
            return;
        }

        player.startAttackAnimation(); // Animate for interaction attempts as well

        if (!held.isEmpty() && held.getItem() == ItemType.STAFF) {
            castFireBolt(player);
            return;
        }

        if (!held.isEmpty() && held.getItem() == ItemType.FISHING_ROD) {
            useFishingRod(player, held);
            return;
        }

        // Food consumption takes priority over block placement.
        if (!held.isEmpty() && held.isFood()) {
            ItemType foodType = held.asItemType();
            if (foodType != null && foodType.getHealAmount() > 0) {
                player.heal(foodType.getHealAmount());
                held.decrementCount(1);
                if (held.getCount() <= 0) {
                    held.clear();
                }
                return;
            }
        }

        interactOrPlaceBlock(player);
    }

    /** Staff fire bolt, rate-limited. Server-authoritative spawn with a local fallback when sessionless. */
    private void castFireBolt(Player player) {
        long now = System.nanoTime();
        if (now - lastFireBoltCastNanos < STAFF_CAST_COOLDOWN_NANOS) {
            return;
        }
        Vector3f dir = new Vector3f(player.getCamera().getFront()).normalize();
        Vector3f spawnPos = new Vector3f(player.getCamera().getPosition());
        if (!MultiplayerSession.sendProjectileSpawn(ProjectileSpawnC2S.KIND_FIRE_BOLT, spawnPos, dir)) {
            EntityManager em = Game.getEntityManager();
            if (em != null) {
                em.spawnFireBolt(spawnPos, dir);
            }
        }
        lastFireBoltCastNanos = now;
    }

    /** Fishing rod: recall an active bobber (rolling for a catch) or cast a fresh one. */
    private void useFishingRod(Player player, ItemStack rod) {
        EntityManager em = Game.getEntityManager();
        if (em == null) {
            return;
        }
        FishingBobber existing = player.getActiveBobber();
        if (existing != null && existing.isAlive()) {
            fishingManager.tryCatch(player, existing);
            existing.setAlive(false);
            player.setActiveBobber(null);
            rod.setState(ItemType.FISHING_ROD_STATE_REELED_IN);
        } else {
            Vector3f dir = new Vector3f(player.getCamera().getFront()).normalize();
            Vector3f pos = new Vector3f(player.getCamera().getPosition());
            FishingBobber bobber = em.spawnBobber(pos, dir);
            player.setActiveBobber(bobber);
            rod.setState(ItemType.FISHING_ROD_STATE_CAST);
        }
    }

    /** Interactable blocks (workbench/furnace/door) open/toggle; anything else falls through to placement. */
    private void interactOrPlaceBlock(Player player) {
        Vector3i targetedBlockPos = player.raycast();
        if (targetedBlockPos == null) {
            // Targeting air or out of range, try to place block (normal behavior).
            player.placeBlock();
            return;
        }

        BlockType targetedBlockType = Game.getWorld().getBlockAt(targetedBlockPos.x, targetedBlockPos.y, targetedBlockPos.z);
        if (targetedBlockType == BlockType.WORKBENCH) {
            System.out.println("Player right-clicked on a Workbench block.");
            Game.getInstance().openWorkbenchScreen();
        } else if (targetedBlockType == BlockType.FURNACE) {
            Game.getInstance().openFurnaceScreen(
                    new com.openmason.engine.util.BlockPos(targetedBlockPos.x, targetedBlockPos.y, targetedBlockPos.z));
        } else if (targetedBlockType == BlockType.OAK_DOOR) {
            // Toggle the door open/closed — plays the target state's one-shot
            // clip and holds the final pose.
            DoorInteraction.toggle(targetedBlockPos.x, targetedBlockPos.y, targetedBlockPos.z);
        } else {
            player.placeBlock();
        }
    }

    /** Right release with a bow: fire if drawn long enough and an arrow is available. */
    private void handleBowRelease(Player player) {
        ItemStack held = player.getInventory().getSelectedHotbarSlot();
        if (held.isEmpty() || held.getItem() != ItemType.BOW) {
            return;
        }
        // Capture speed before releaseAndFire() resets state.
        float arrowSpeed = player.getBowController().getArrowSpeed();
        if (player.getBowController().releaseAndFire() && player.getInventory().hasItem(ItemType.ARROW)) {
            player.getInventory().removeItem(ItemType.ARROW);
            Vector3f dir = new Vector3f(player.getCamera().getFront()).normalize();
            Vector3f vel = new Vector3f(dir).mul(arrowSpeed);
            Vector3f spawnPos = new Vector3f(player.getCamera().getPosition());
            // Server-authoritative spawn (replicated to all); local fallback
            // only when there is no session at all.
            if (!MultiplayerSession.sendProjectileSpawn(ProjectileSpawnC2S.KIND_ARROW, spawnPos, vel)) {
                EntityManager em = Game.getEntityManager();
                if (em != null) {
                    em.spawnArrow(spawnPos, vel);
                }
            }
        }
    }
}
