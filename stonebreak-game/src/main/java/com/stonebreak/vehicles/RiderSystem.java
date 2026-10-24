package com.stonebreak.vehicles;

import com.stonebreak.mobs.entities.Entity;
import com.stonebreak.mobs.entities.LivingEntity;
import com.stonebreak.mobs.entities.RemotePlayer;
import com.stonebreak.mobs.entities.ai.MobBehaviorState;
import org.joml.Vector3f;

import java.util.List;
import java.util.function.IntFunction;

/**
 * Per-tick seat bookkeeping, run by the EntityManager after every entity has moved:
 * <ul>
 *   <li><b>Server</b> — a mob that bumps into a dinghy with a free stern seat climbs in;</li>
 *   <li><b>Client</b> — the replicated seat tokens are bound to the shadows they name (mob
 *       shadows, remote players), and unbound when they leave;</li>
 *   <li><b>Both</b> — every seated entity is carried to its seat and faces the way it does.</li>
 * </ul>
 * The local player is not an entity; {@code player.riding.PlayerRiding} seats it.
 */
public final class RiderSystem {

    /**
     * How far below the bench top a seated player's position (its feet) sits: on the dinghy's
     * floorboards (bench 0.406, floor 0.125). The sitting clip drops the hips to match.
     */
    public static final float PLAYER_SIT_DROP = 0.28f;

    private RiderSystem() {
        throw new UnsupportedOperationException("Utility class");
    }

    /** Server tick: mobs bumping into a boat board it, then riders are seated. */
    public static void updateServer(List<Entity> entities) {
        for (Entity e : entities) {
            if (e instanceof Dinghy boat && boat.isAlive() && !boat.isNetworkShadow()
                    && boat.isSeatFree(Dinghy.PASSENGER_SEAT)) {
                boardBumpingMob(boat, entities);
            }
        }
        placeRiders(entities);
    }

    /**
     * Client tick: bind the shadows named by each boat's seat tokens, then seat them.
     * {@code entityByNetworkId} resolves a replicated entity's shadow; {@code localPlayerId}
     * is skipped (the local player seats itself).
     */
    public static void updateClient(List<Entity> entities, IntFunction<Entity> entityByNetworkId,
                                    int localPlayerId) {
        for (Entity e : entities) {
            if (e.getVehicle() instanceof Dinghy boat && !stillNamedBy(boat, e)) {
                e.setVehicle(null, -1);
            }
        }
        for (Entity e : entities) {
            if (!(e instanceof Dinghy boat) || !boat.isAlive()) {
                continue;
            }
            for (int seat = 0; seat < boat.seatCount(); seat++) {
                Entity rider = resolve(boat.seatToken(seat), entities, entityByNetworkId, localPlayerId);
                if (rider != null && rider.getVehicle() != boat) {
                    rider.setVehicle(boat, seat);
                }
            }
        }
        placeRiders(entities);
    }

    /** Carries every seated entity to its seat; stands up riders of a vanished vehicle. */
    static void placeRiders(List<Entity> entities) {
        Vector3f seatPos = new Vector3f();
        for (Entity rider : entities) {
            Rideable vehicle = rider.getVehicle();
            if (vehicle == null) {
                continue;
            }
            if (!vehicle.isAlive() || !rider.isAlive()) {
                rider.setVehicle(null, -1);
                continue;
            }
            int seat = rider.getSeatIndex();
            vehicle.seatPosition(seat, seatPos);
            rider.setPosition(seatPos.x, seatPos.y + seatOffset(rider), seatPos.z);
            rider.setVelocity(new Vector3f());
            if (!(rider instanceof RemotePlayer)) {
                // A player's yaw is its look; its figure takes the body facing from the seat.
                rider.setYaw(riderYaw(rider, vehicle.seatYaw(seat)));
            }
        }
    }

    /** Vertical offset from the bench top to a rider's position: players sit, mobs stand on it. */
    static float seatOffset(Entity rider) {
        if (rider instanceof RemotePlayer) {
            return -PLAYER_SIT_DROP;
        }
        return rider instanceof LivingEntity living ? living.getLegHeight() : 0f;
    }

    /**
     * Entity yaw that faces a rider the seat's way. Seat yaw uses the boat's convention (facing
     * {@code (-sin, -cos)}); mobs use {@code atan2(dir.x, dir.z) + modelYawOffset}.
     */
    static float riderYaw(Entity rider, float seatYaw) {
        return seatYaw + 180f + rider.getType().getModelYawOffsetDegrees();
    }

    private static void boardBumpingMob(Dinghy boat, List<Entity> entities) {
        Entity.BoundingBox hull = boat.getBoundingBox();
        for (Entity e : entities) {
            if (!(e instanceof LivingEntity mob) || e instanceof RemotePlayer || !mob.isAlive()
                    || mob.isRiding() || mob.isNetworkShadow() || mob.getAI() == null) {
                continue;
            }
            if (overlaps(hull, mob.getBoundingBox()) && boat.boardMob(mob)) {
                mob.getAI().setState(MobBehaviorState.IDLE);
                return;
            }
        }
    }

    private static boolean overlaps(Entity.BoundingBox a, Entity.BoundingBox b) {
        return a.minX < b.maxX && a.maxX > b.minX
                && a.minY < b.maxY && a.maxY > b.minY
                && a.minZ < b.maxZ && a.maxZ > b.minZ;
    }

    private static boolean stillNamedBy(Dinghy boat, Entity rider) {
        int seat = rider.getSeatIndex();
        if (!boat.isAlive() || seat < 0 || seat >= boat.seatCount()) {
            return false;
        }
        String token = boat.seatToken(seat);
        if (rider instanceof RemotePlayer rp) {
            return SeatToken.playerId(token) == rp.getPlayerId();
        }
        return SeatToken.entityId(token) == rider.getNetworkId();
    }

    private static Entity resolve(String token, List<Entity> entities,
                                  IntFunction<Entity> entityByNetworkId, int localPlayerId) {
        int entityId = SeatToken.entityId(token);
        if (entityId != SeatToken.NONE) {
            return entityByNetworkId.apply(entityId);
        }
        int playerId = SeatToken.playerId(token);
        if (playerId == SeatToken.NONE || playerId == localPlayerId) {
            return null;
        }
        for (Entity e : entities) {
            if (e instanceof RemotePlayer rp && rp.getPlayerId() == playerId
                    && !(e instanceof com.stonebreak.mobs.entities.IllusionDecoy)) {
                return rp;
            }
        }
        return null;
    }
}
