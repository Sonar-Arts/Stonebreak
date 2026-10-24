package com.stonebreak.vehicles;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.core.Game;
import com.stonebreak.items.ItemStack;
import com.stonebreak.items.ItemType;
import com.stonebreak.mobs.entities.Entity;
import com.stonebreak.mobs.entities.EntityManager;
import com.stonebreak.network.MultiplayerSession;
import com.stonebreak.network.packet.vehicle.VehicleActionC2S;
import com.stonebreak.player.Player;
import com.stonebreak.player.interaction.RaycastEngine;
import com.stonebreak.world.World;
import org.joml.Vector3f;
import org.joml.Vector3i;

/**
 * The local player's clicks on vehicles: right-click a dinghy to board it, right-click with the
 * dinghy item to put one down, left-click a dinghy to strike it. Every action is a request — the
 * server owns vehicles — but placing predicts the item leaving the hand.
 */
public final class VehicleInteraction {

    /** Reach for boarding and striking, the same as melee. */
    static final float REACH = RaycastEngine.ENTITY_ATTACK_RANGE;

    private VehicleInteraction() {
        throw new UnsupportedOperationException("Utility class");
    }

    /** Right press: board the dinghy under the crosshair. True when one was targeted. */
    public static boolean tryBoard(Player player) {
        if (player.getRiding().isRiding()) {
            return false;
        }
        Dinghy target = targeted(player);
        if (target == null) {
            return false;
        }
        player.getRiding().requestBoard(target);
        return true;
    }

    /** Left press: strike the dinghy under the crosshair. True when one was targeted. */
    public static boolean tryHit(Player player) {
        Dinghy target = targeted(player);
        if (target == null || target == player.getRiding().getVehicle()) {
            return false;
        }
        MultiplayerSession.sendVehicleAction(VehicleActionC2S.HIT, target.getNetworkId());
        return true;
    }

    /**
     * Right press with the dinghy item: put it on the water (or ground) under the crosshair, bow
     * pointing where the player looks. True when the held item is a dinghy (used or not).
     */
    public static boolean tryPlace(Player player, ItemStack held) {
        if (held.isEmpty() || held.getItem() != ItemType.DINGHY) {
            return false;
        }
        World world = Game.getWorld();
        Vector3i hit = player.getRaycastEngine().raycastIncludingWater();
        if (world == null || hit == null || player.getRiding().isRiding()) {
            return true;
        }
        Vector3f pos = placement(world, hit);
        if (pos == null) {
            return true;
        }
        Vector3f front = player.getCamera().getFront();
        float yaw = (float) Math.toDegrees(Math.atan2(-front.x, -front.z));
        if (MultiplayerSession.sendVehiclePlace(pos, yaw)) {
            player.getInventory().removeItem(ItemType.DINGHY, 1);
        }
        return true;
    }

    /** Where a dinghy goes for a crosshair hit on {@code hit}: afloat on water, else on top. */
    static Vector3f placement(World world, Vector3i hit) {
        BlockType block = world.getBlockAt(hit.x, hit.y, hit.z);
        if (block == null) {
            return null;
        }
        float x = hit.x + 0.5f, z = hit.z + 0.5f;
        if (block == BlockType.WATER) {
            float surface = DinghyPhysics.waterSurfaceY(world, x, hit.y + 0.5f, z);
            float y = Float.isNaN(surface) ? hit.y + 1f : surface;
            return new Vector3f(x, y - DinghyPhysics.WATERLINE, z);
        }
        float y = hit.y + 1f;
        return DinghyPhysics.blocked(world, x, y, z) ? null : new Vector3f(x, y, z);
    }

    /** The nearest dinghy along the crosshair within reach and not behind terrain, or null. */
    static Dinghy targeted(Player player) {
        EntityManager em = Game.getEntityManager();
        if (em == null) {
            return null;
        }
        Vector3f origin = new Vector3f(player.getCamera().getPosition());
        Vector3f dir = new Vector3f(player.getCamera().getFront()).normalize();
        Dinghy best = null;
        float bestT = REACH + thirdPersonSlack(player);
        for (Entity e : em.getAllEntities()) {
            if (e instanceof Dinghy d && d.isAlive()) {
                float t = d.rayIntersect(origin, dir);
                if (t < bestT) {
                    bestT = t;
                    best = d;
                }
            }
        }
        if (best == null || player.getRaycastEngine().distanceToFirstSolid(origin, dir, bestT) < bestT) {
            return null;
        }
        return best;
    }

    /** The third-person camera sits up to 4 blocks behind the eyes; reach is from the eyes. */
    private static float thirdPersonSlack(Player player) {
        return player.getPerspective() == Player.Perspective.THIRD_PERSON
                ? player.getCamera().getPosition().distance(player.getRaycastEngine().eyeOrigin()) : 0f;
    }
}
