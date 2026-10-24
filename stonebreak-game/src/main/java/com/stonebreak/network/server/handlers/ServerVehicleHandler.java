package com.stonebreak.network.server.handlers;

import com.stonebreak.items.ItemStack;
import com.stonebreak.items.ItemType;
import com.stonebreak.mobs.entities.Entity;
import com.stonebreak.mobs.entities.EntityManager;
import com.stonebreak.mobs.entities.ItemDrop;
import com.stonebreak.network.packet.vehicle.VehicleActionC2S;
import com.stonebreak.network.packet.vehicle.VehicleMoveC2S;
import com.stonebreak.network.server.ServerPlayer;
import com.stonebreak.network.server.ServerWorldContext;
import com.stonebreak.network.server.combat.CombatRules;
import com.stonebreak.vehicles.Dinghy;
import com.stonebreak.vehicles.OarInput;
import org.joml.Vector3f;

import java.util.function.IntFunction;

/**
 * Server half of vehicles: placing a dinghy from the held item, boarding and leaving its seats,
 * breaking it back into its item, and accepting the rower's reported boat position. Seat
 * changes reach clients through the boat's replicated visual state ({@code EntityAnimS2C}), and
 * its motion through the ordinary entity move stream.
 */
public final class ServerVehicleHandler {

    /**
     * A rower's report may put the boat at most this far from where the server has the rower
     * (seat offset plus the gap between the two report streams). The rower's own reports are
     * held to its {@code MovementGuard}, so the boat can go no faster than its rower may.
     */
    private static final float MAX_ROWER_GAP = 4f;

    private final IntFunction<Entity> entityByNetworkId;

    public ServerVehicleHandler(IntFunction<Entity> entityByNetworkId) {
        this.entityByNetworkId = entityByNetworkId;
    }

    public void handleAction(ServerPlayer sp, VehicleActionC2S a, ServerWorldContext ctx) {
        EntityManager em = ctx.entityManager();
        if (em == null) {
            if (a.action() == VehicleActionC2S.PLACE) {
                sp.send(sp.inventorySync(), false);
            }
            return;
        }
        switch (a.action()) {
            case VehicleActionC2S.PLACE -> place(sp, a, em);
            case VehicleActionC2S.BOARD -> board(sp, boat(a.networkId()), em);
            case VehicleActionC2S.DISMOUNT -> dismount(sp, em);
            case VehicleActionC2S.HIT -> hit(sp, boat(a.networkId()), em);
            default -> { /* unknown action — ignore */ }
        }
    }

    public void handleMove(ServerPlayer sp, VehicleMoveC2S m) {
        Dinghy boat = boat(m.networkId());
        if (boat == null || boat.driverPlayerId() != sp.playerId()
                || !Float.isFinite(m.x()) || !Float.isFinite(m.y()) || !Float.isFinite(m.z())
                || !Float.isFinite(m.yaw())) {
            return;
        }
        if (!withinDistance(sp, m.x(), m.y(), m.z(), MAX_ROWER_GAP)) {
            return; // the next sane report carries on from where the server has it
        }
        boat.applyDriverMove(m.x(), m.y(), m.z(), m.yaw(), OarInput.decode(m.oarLeft(), m.oarRight()));
    }

    /** A leaving (or dead) player gets out of whatever they sit in. */
    public void onPeerLeft(ServerPlayer sp, ServerWorldContext ctx) {
        EntityManager em = ctx.entityManager();
        if (em != null) {
            dismount(sp, em);
        }
    }

    private void place(ServerPlayer sp, VehicleActionC2S a, EntityManager em) {
        int dinghyId = ItemType.DINGHY.getId();
        Vector3f pos = new Vector3f(a.x(), a.y(), a.z());
        if (sp.inventory().heldItemId() == dinghyId && Float.isFinite(a.yaw()) && sp.lastStateNs() != 0L
                && withinDistance(sp, pos.x, pos.y, pos.z, ServerBlockHandler.MAX_REACH)
                && em.spawnDinghy(pos, a.yaw()) != null) {
            sp.inventory().debitOneWithAutoSwitch(dinghyId);
        }
        // Answered either way: a refusal hands the predicted dinghy back.
        sp.send(sp.inventorySync(), false);
    }

    private void board(ServerPlayer sp, Dinghy boat, EntityManager em) {
        if (boat == null || !boat.isAlive() || sp.isDead() || !inMeleeReach(sp, boat)
                || seatedIn(sp, em) != null) {
            return;
        }
        if (boat.boardPlayer(sp.playerId()) >= 0) {
            sp.setBoardedVehicle(boat); // saved with the player, so a relaunch sits them back in it
        }
    }

    private void dismount(ServerPlayer sp, EntityManager em) {
        Dinghy boat = seatedIn(sp, em);
        if (boat != null) {
            boat.dismountPlayer(sp.playerId());
        }
    }

    private void hit(ServerPlayer sp, Dinghy boat, EntityManager em) {
        if (boat == null || !boat.isAlive() || sp.isDead() || !inMeleeReach(sp, boat)) {
            return;
        }
        if (boat.hit()) {
            boat.dismountAll();
            Vector3f at = boat.getPosition().add(0f, 0.5f, 0f);
            em.addEntity(ItemDrop.createDrop(em.getWorld(), at, new ItemStack(ItemType.DINGHY, 1)));
            boat.setAlive(false); // the manager removes it next tick, which replicates the despawn
        }
    }

    private Dinghy boat(int networkId) {
        return entityByNetworkId.apply(networkId) instanceof Dinghy d ? d : null;
    }

    private static Dinghy seatedIn(ServerPlayer sp, EntityManager em) {
        for (Entity e : em.getAllEntities()) {
            if (e instanceof Dinghy d && d.isAlive() && d.seatOfPlayer(sp.playerId()) >= 0) {
                return d;
            }
        }
        return null;
    }

    /** Boarding and striking reach as far as a melee swing (the client uses the same range). */
    private static boolean inMeleeReach(ServerPlayer sp, Dinghy boat) {
        return CombatRules.withinMeleeReach(sp.x(), sp.y(), sp.z(), boat.getBoundingBox());
    }

    private static boolean withinDistance(ServerPlayer sp, float x, float y, float z, float max) {
        float dx = x - sp.x(), dy = y - sp.y(), dz = z - sp.z();
        return dx * dx + dy * dy + dz * dz <= max * max;
    }
}
