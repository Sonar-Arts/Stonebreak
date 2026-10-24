package com.stonebreak.vehicles;

import com.stonebreak.core.Game;
import com.stonebreak.mobs.entities.Entity;
import com.stonebreak.mobs.entities.EntityManager;
import com.stonebreak.network.MultiplayerSession;
import com.stonebreak.network.packet.vehicle.VehicleActionC2S;
import com.stonebreak.network.packet.vehicle.VehicleMoveC2S;
import com.stonebreak.player.Player;
import com.stonebreak.world.World;
import org.joml.Vector3f;

/**
 * The local player's side of riding. Boarding is the server's call: a right-click asks for a
 * seat, and the player sits once the boat's replicated seats name it. While seated the player
 * is carried to the seat every tick instead of running its own physics; in the rower's seat its
 * WASD becomes {@link OarInput}, the boat is simulated here (like the player's own body) and
 * reported to the server at the player-state rate.
 *
 * <p>Getting out is predicted: the player stands up at once and the stale seat token that may
 * still name it for a moment is ignored.
 */
public final class PlayerRiding {

    /** A board request the server has not answered within this long is dropped. */
    private static final float BOARD_TIMEOUT = 1.5f;
    /** How long a just-left boat's seat may still name the player before it is believed again. */
    private static final float LEAVE_GRACE = 1.5f;
    /** Rower's report period — the player-state rate. */
    private static final float SEND_PERIOD = 0.05f;
    /** Once the ground has streamed in, how long a saved seat waits for its boat to replicate. */
    private static final float RESUME_TIMEOUT = 5f;
    /** How far the boat may lie from where the save had it (it drifts while unmanned). */
    private static final float RESUME_RADIUS = 2f;
    /** How much lower the eyes are seated than standing (the sitting clip's hip drop). */
    public static final float EYE_DROP = 0.6f;

    private Dinghy boat;
    private int seat = -1;
    private Dinghy pending;
    private float pendingAge;
    private Dinghy leftBoat;
    private float leftAge;
    private OarInput input = OarInput.IDLE;
    private float sendTimer;
    /** A loaded save's boat position, until that boat is found (or given up on). */
    private Vector3f resumeAt;
    private float resumeAge;
    /** The pending board request is the saved seat's: hold still until it is answered. */
    private boolean resumedBoard;

    public boolean isRiding() {
        return boat != null;
    }

    public Dinghy getVehicle() {
        return boat;
    }

    public int getSeat() {
        return seat;
    }

    public boolean isRowing() {
        return boat != null && seat == Dinghy.DRIVER_SEAT;
    }

    /** Body facing (model space) while seated. */
    public float seatYaw() {
        return boat != null ? boat.seatYaw(seat) : 0f;
    }

    /** Ask the server for a seat in {@code dinghy}. */
    public void requestBoard(Dinghy dinghy) {
        if (boat != null || dinghy == null || dinghy.getNetworkId() < 0) {
            return;
        }
        pending = dinghy;
        pendingAge = 0f;
        MultiplayerSession.sendVehicleAction(VehicleActionC2S.BOARD, dinghy.getNetworkId());
    }

    /**
     * The save had the player seated in the boat at {@code boatPosition} (null: on foot). Once
     * that boat replicates they ask for a seat in it as a right-click would, sitting still on
     * the saved seat meanwhile instead of dropping into the water.
     */
    public void resumeSeat(Vector3f boatPosition) {
        resumeAt = boatPosition == null ? null : new Vector3f(boatPosition);
        resumeAge = 0f;
        resumedBoard = false;
    }

    /** This frame's oar keys (W/S drive, A/D turn). */
    public void setKeys(boolean forward, boolean backward, boolean left, boolean right) {
        input = OarInput.fromKeys(forward, backward, left, right);
    }

    /** Get out (sneak): tells the server and stands up beside the boat at once. */
    public void requestDismount(Player player) {
        if (boat == null) {
            return;
        }
        MultiplayerSession.sendVehicleAction(VehicleActionC2S.DISMOUNT, boat.getNetworkId());
        standUp(player);
    }

    /**
     * One tick, before the player's own physics. Returns true while seated — the caller then
     * skips gravity/collision; the player is already on its seat.
     */
    public boolean tick(Player player, float dt) {
        int me = MultiplayerSession.localPlayerId();
        if (leftBoat != null) {
            leftAge += dt;
            if (leftAge > LEAVE_GRACE || seatNaming(leftBoat, me) < 0) {
                leftBoat = null;
            }
        }
        if (resumeAt != null) {
            resumeAge += dt;
            Dinghy saved = nearestDinghy(resumeAt);
            if (saved != null || resumeAge > RESUME_TIMEOUT) {
                resumeAt = null;
                requestBoard(saved);
                resumedBoard = pending != null;
            }
        }
        if (pending != null) {
            pendingAge += dt;
            int s = seatNaming(pending, me);
            if (s >= 0 && pending != leftBoat) {
                boat = pending;
                seat = s;
                pending = null;
            } else if (pendingAge > BOARD_TIMEOUT || !pending.isAlive()) {
                pending = null;
            }
        }
        if (pending == null) {
            resumedBoard = false;
        }
        if (boat == null) {
            if (resumeAt != null || resumedBoard) {
                player.getVelocity().zero(); // still on the saved seat, waiting for the boat
                player.setOnGround(true);
                return true;
            }
            return false;
        }
        if (player.isDead()) {
            requestDismount(player);
            return false;
        }
        int s = seatNaming(boat, me);
        if (!boat.isAlive() || s < 0) {
            standUp(player); // the server stood us up: the boat broke, or someone else took the seat
            return false;
        }
        seat = s;

        if (seat == Dinghy.DRIVER_SEAT) {
            if (!boat.isLocallyControlled()) {
                boat.setLocallyControlled(true);
            }
            boat.stepLocal(input, dt);
            sendTimer += dt;
            if (sendTimer >= SEND_PERIOD) {
                sendTimer = 0f;
                Vector3f p = boat.getPosition();
                OarInput oars = boat.getOars();
                MultiplayerSession.sendVehicleMove(new VehicleMoveC2S(boat.getNetworkId(),
                        p.x, p.y, p.z, boat.getYaw(), oars.encodeLeft(), oars.encodeRight()));
            }
        } else {
            if (boat.isLocallyControlled()) {
                boat.setLocallyControlled(false);
            }
            // Bring the boat to this frame before sitting on it (the manager interpolates later).
            if (boat.getInterpolator() != null) {
                boat.getInterpolator().apply(boat);
            }
        }

        Vector3f seatPos = boat.seatPosition(seat, new Vector3f());
        player.setPosition(seatPos.x, seatPos.y - RiderSystem.PLAYER_SIT_DROP, seatPos.z);
        player.getVelocity().zero();
        player.setOnGround(true);
        return true;
    }

    /** Leaves the seat locally and puts the player beside the boat (or on it, when walled in). */
    private void standUp(Player player) {
        Dinghy from = boat;
        if (from.isLocallyControlled()) {
            from.setLocallyControlled(false);
        }
        boat = null;
        seat = -1;
        input = OarInput.IDLE;
        leftBoat = from;
        leftAge = 0f;
        Vector3f spot = exitSpot(from, Game.getWorld());
        player.teleport(spot.x, spot.y, spot.z);
    }

    /** Beside the hull (left, then right), else standing on it. */
    static Vector3f exitSpot(Dinghy boat, World world) {
        Vector3f base = boat.getPosition();
        for (float side : new float[]{-1.4f, 1.4f}) {
            Vector3f spot = Dinghy.rotateY(side, 0.6f, 0f, boat.getYaw(), new Vector3f()).add(base);
            if (world != null && isClear(world, spot)) {
                return spot;
            }
        }
        return new Vector3f(base.x, base.y + DinghyPhysics.HULL_HEIGHT + 0.1f, base.z);
    }

    private static boolean isClear(World world, Vector3f feet) {
        int x = (int) Math.floor(feet.x), y = (int) Math.floor(feet.y), z = (int) Math.floor(feet.z);
        for (int dy = 0; dy <= 1; dy++) {
            var block = world.getBlockAt(x, y + dy, z);
            if (block != null && block.isSolid()) {
                return false;
            }
        }
        return true;
    }

    /** The replicated dinghy nearest {@code at} within {@link #RESUME_RADIUS}, or null. */
    private static Dinghy nearestDinghy(Vector3f at) {
        EntityManager em = Game.getEntityManager();
        if (em == null) {
            return null;
        }
        Dinghy best = null;
        float bestDist = RESUME_RADIUS * RESUME_RADIUS;
        for (Entity e : em.getAllEntities()) {
            if (e instanceof Dinghy d && d.isAlive() && d.getNetworkId() >= 0) {
                float dist = d.getPosition().distanceSquared(at);
                if (dist <= bestDist) {
                    bestDist = dist;
                    best = d;
                }
            }
        }
        return best;
    }

    private static int seatNaming(Dinghy dinghy, int playerId) {
        if (playerId < 0) {
            return -1;
        }
        for (int s = 0; s < dinghy.seatCount(); s++) {
            if (SeatToken.playerId(dinghy.seatToken(s)) == playerId) {
                return s;
            }
        }
        return -1;
    }
}
