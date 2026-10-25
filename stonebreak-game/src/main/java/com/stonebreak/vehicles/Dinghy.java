package com.stonebreak.vehicles;

import com.stonebreak.mobs.entities.Entity;
import com.stonebreak.mobs.entities.EntityType;
import com.stonebreak.rendering.Renderer;
import com.stonebreak.world.World;
import org.joml.Vector3f;

/**
 * A two-seat rowing boat ({@code SB_Dinghy.sbe}). Seat 0 is the rower's thwart beside the
 * oarlocks — only a player there can row; seat 1 is the stern seat, for a second player or a mob
 * that bumps into the boat.
 *
 * <p>Authority: the server owns the boat and its seats. While a player rows it, that player's
 * client simulates the boat ({@link DinghyPhysics}) and reports where it is, the same way players
 * report their own movement; otherwise the server simulates it (drifting, settling on the bank).
 * Seats and the oars cross the wire as this entity's visual state ({@link #networkVisualState}),
 * so every client rows the oars and places the riders the same way.
 *
 * <p>Model space: the bow points to -Z at yaw 0, so the boat's forward is {@code (-sin yaw, -cos yaw)}.
 */
public class Dinghy extends Entity implements Rideable {

    public static final int DRIVER_SEAT = 0;
    public static final int PASSENGER_SEAT = 1;
    /** Bench tops in model space: the thwart and the stern seat (from SB_Dinghy's mesh). */
    private static final float[][] SEATS = {{0f, 0.406f, -0.25f}, {0f, 0.406f, 0.84f}};

    /** The entity type's maxHealth is the hit count. */
    public static final int HITS_TO_BREAK = (int) EntityType.DINGHY.getMaxHealth();
    /** Hits closer together than this count once (a held swing, a lagged double packet). */
    private static final float HIT_COOLDOWN = 0.25f;

    /** Hull extents in model space, for hit tests: beam, gunwale height, stem to transom. */
    private static final float HIT_HALF_BEAM = 0.75f;
    private static final float HIT_TOP = 0.65f;
    private static final float HIT_BOW_Z = -1.9f;
    private static final float HIT_STERN_Z = 1.25f;

    private OarInput oars = OarInput.IDLE;
    private int hits;
    private float sinceHit = HIT_COOLDOWN;

    /** Server: who sits where — a player id, or a seated entity. */
    private final int[] seatPlayer = {-1, -1};
    private final Entity[] seatEntity = new Entity[2];
    /** Client shadow: the replicated seat tokens ({@link SeatToken}) awaiting resolution. */
    private final String[] seatTokens = {SeatToken.EMPTY, SeatToken.EMPTY};

    private final OarStroke stroke = new OarStroke();
    private boolean locallyControlled;

    public Dinghy(World world, Vector3f position, float yawDeg) {
        super(world, position);
        this.width = EntityType.DINGHY.getWidth();
        this.length = EntityType.DINGHY.getLength();
        this.height = EntityType.DINGHY.getHeight();
        this.maxHealth = HITS_TO_BREAK;
        this.health = HITS_TO_BREAK;
        this.rotation.y = yawDeg;
    }

    // ─── Simulation ──────────────────────────────────────────────────────────

    @Override
    public void update(float deltaTime) {
        age += deltaTime;
        sinceHit += deltaTime;
        // A rowing player's client moves the boat; the server only simulates it unmanned.
        if (driverPlayerId() < 0) {
            oars = OarInput.IDLE;
            DinghyPhysics.step(world, this, OarInput.IDLE, 1f, deltaTime);
        }
    }

    /**
     * Client: one predicted rowing step of the boat the local player is driving, at the rower's
     * {@link DinghyPhysics#rowingPower}.
     */
    public void stepLocal(OarInput input, float rowingPower, float deltaTime) {
        oars = input;
        DinghyPhysics.step(world, this, input, rowingPower, deltaTime);
        stroke.update(input, deltaTime);
    }

    /** Server: the rowing player's client reports where the boat is now. */
    public void applyDriverMove(float x, float y, float z, float yawDeg, OarInput input) {
        position.set(x, y, z);
        rotation.y = yawDeg;
        oars = input;
    }

    @Override
    public boolean isSelfPropelled() {
        return true; // DinghyPhysics runs inside update()
    }

    @Override
    public boolean isLocallyControlled() {
        return locallyControlled;
    }

    /** Client: take the boat off the interpolator while the local player rows it (and back). */
    public void setLocallyControlled(boolean locallyControlled) {
        this.locallyControlled = locallyControlled;
        if (!locallyControlled && getInterpolator() != null) {
            getInterpolator().seed(position.x, position.y, position.z, rotation.y, 0f);
        }
    }

    public OarInput getOars() {
        return oars;
    }

    public OarStroke getStroke() {
        return stroke;
    }

    // ─── Seats ───────────────────────────────────────────────────────────────

    @Override
    public int seatCount() {
        return SEATS.length;
    }

    @Override
    public Vector3f seatPosition(int seat, Vector3f dest) {
        float[] s = SEATS[seat];
        return rotateY(s[0], s[1], s[2], rotation.y, dest).add(position);
    }

    /** Both riders face the bow: the oar handles sit just bow-ward of the thwart. */
    @Override
    public float seatYaw(int seat) {
        return rotation.y;
    }

    /** {@code (x, y, z)} turned by Ry({@code yawDeg}), as the renderer's base matrix does; -yaw undoes it. */
    static Vector3f rotateY(float x, float y, float z, float yawDeg, Vector3f dest) {
        double yawRad = Math.toRadians(yawDeg);
        float cos = (float) Math.cos(yawRad);
        float sin = (float) Math.sin(yawRad);
        return dest.set(x * cos + z * sin, y, -x * sin + z * cos);
    }

    /** Server: seats player {@code playerId}, rower's seat first; the seat taken, or -1 when full. */
    public int boardPlayer(int playerId) {
        int current = seatOfPlayer(playerId);
        if (current >= 0) {
            return current;
        }
        for (int seat = 0; seat < SEATS.length; seat++) {
            if (isSeatFree(seat)) {
                seatPlayer[seat] = playerId;
                return seat;
            }
        }
        return -1;
    }

    /** Server: seats a mob on the stern seat — the oars are for players. False when taken. */
    public boolean boardMob(Entity mob) {
        if (!isSeatFree(PASSENGER_SEAT) || mob.isRiding()) {
            return false;
        }
        seatEntity[PASSENGER_SEAT] = mob;
        mob.setVehicle(this, PASSENGER_SEAT);
        return true;
    }

    /** Server: frees whatever seat player {@code playerId} holds. */
    public void dismountPlayer(int playerId) {
        int seat = seatOfPlayer(playerId);
        if (seat >= 0) {
            seatPlayer[seat] = -1;
            if (seat == DRIVER_SEAT) {
                oars = OarInput.IDLE;
            }
        }
    }

    /** Server: stands every rider up (the boat broke or unloaded); mobs climb out over the side. */
    public void dismountAll() {
        seatPlayer[0] = seatPlayer[1] = -1;
        for (int seat = 0; seat < seatEntity.length; seat++) {
            Entity rider = seatedEntity(seat);
            if (rider != null) {
                rider.setVehicle(null, -1);
                rider.setPosition(position.x, position.y + 0.6f, position.z);
            }
            seatEntity[seat] = null;
        }
        oars = OarInput.IDLE;
    }

    public boolean isSeatFree(int seat) {
        return seatPlayer[seat] < 0 && seatedEntity(seat) == null;
    }

    /** The mob in {@code seat}, or null — its claim lapses once it dies or is carried off. */
    private Entity seatedEntity(int seat) {
        Entity e = seatEntity[seat];
        return e != null && e.isAlive() && e.getVehicle() == this ? e : null;
    }

    public int seatOfPlayer(int playerId) {
        for (int seat = 0; seat < seatPlayer.length; seat++) {
            if (playerId >= 0 && seatPlayer[seat] == playerId) {
                return seat;
            }
        }
        return -1;
    }

    public int driverPlayerId() {
        return seatPlayer[DRIVER_SEAT];
    }

    /** Client shadow: the replicated token for {@code seat} ({@link SeatToken}). */
    public String seatToken(int seat) {
        return seatTokens[seat];
    }

    // ─── Breaking ────────────────────────────────────────────────────────────

    /** Server: one hit; true when this one breaks the boat. */
    public boolean hit() {
        if (sinceHit < HIT_COOLDOWN) {
            return false;
        }
        sinceHit = 0f;
        hits++;
        health = Math.max(0f, HITS_TO_BREAK - hits);
        return hits >= HITS_TO_BREAK;
    }

    public int getHits() {
        return hits;
    }

    public void setHits(int hits) {
        this.hits = Math.max(0, Math.min(HITS_TO_BREAK - 1, hits));
        this.health = HITS_TO_BREAK - this.hits;
    }

    /**
     * Distance along the ray to the hull, or {@link Float#MAX_VALUE} on a miss: a slab test in
     * the boat's own frame, so the long hull is hit where it is drawn whatever its heading.
     */
    public float rayIntersect(Vector3f origin, Vector3f dir) {
        // Inverse of Ry(yaw): into model space.
        Vector3f lo = rotateY(origin.x - position.x, origin.y - position.y, origin.z - position.z,
                -rotation.y, new Vector3f());
        Vector3f ld = rotateY(dir.x, dir.y, dir.z, -rotation.y, new Vector3f());
        float[] o = {lo.x, lo.y, lo.z};
        float[] d = {ld.x, ld.y, ld.z};
        float[] min = {-HIT_HALF_BEAM, -0.15f, HIT_BOW_Z};
        float[] max = {HIT_HALF_BEAM, HIT_TOP, HIT_STERN_Z};
        float tNear = 0f, tFar = Float.MAX_VALUE;
        for (int axis = 0; axis < 3; axis++) {
            if (Math.abs(d[axis]) < 1.0e-6f) {
                if (o[axis] < min[axis] || o[axis] > max[axis]) {
                    return Float.MAX_VALUE;
                }
                continue;
            }
            float t1 = (min[axis] - o[axis]) / d[axis];
            float t2 = (max[axis] - o[axis]) / d[axis];
            tNear = Math.max(tNear, Math.min(t1, t2));
            tFar = Math.min(tFar, Math.max(t1, t2));
            if (tNear > tFar) {
                return Float.MAX_VALUE;
            }
        }
        return tNear;
    }

    // ─── Entity plumbing ─────────────────────────────────────────────────────

    @Override
    public void render(Renderer renderer) {
        // Rendered externally by EntityRenderer (DinghyRenderer)
    }

    @Override
    public EntityType getType() {
        return EntityType.DINGHY;
    }

    // ─── Replication: "<left>,<right>;<seat0>;<seat1>" ───────────────────────

    @Override
    public String networkVisualState() {
        StringBuilder sb = new StringBuilder(24)
                .append(oars.encodeLeft()).append(',').append(oars.encodeRight());
        for (int seat = 0; seat < SEATS.length; seat++) {
            Entity mob = seatedEntity(seat);
            sb.append(';');
            if (seatPlayer[seat] >= 0) {
                sb.append(SeatToken.player(seatPlayer[seat]));
            } else if (mob != null && mob.getNetworkId() >= 0) {
                sb.append(SeatToken.entity(mob.getNetworkId()));
            } else {
                sb.append(SeatToken.EMPTY);
            }
        }
        return sb.toString();
    }

    @Override
    public void applyNetworkState(String state) {
        if (state == null) {
            return;
        }
        String[] parts = state.split(";", -1);
        String[] oarParts = parts[0].split(",", -1);
        if (oarParts.length == 2 && !locallyControlled) {
            try {
                oars = OarInput.decode(Byte.parseByte(oarParts[0].trim()), Byte.parseByte(oarParts[1].trim()));
            } catch (NumberFormatException ignored) {
                oars = OarInput.IDLE;
            }
        }
        for (int seat = 0; seat < SEATS.length; seat++) {
            seatTokens[seat] = seat + 1 < parts.length ? parts[seat + 1] : SeatToken.EMPTY;
        }
    }

    /** Shadows skip {@link #update}: row the oars from the replicated input. */
    @Override
    public void updateClientVisuals(float deltaTime) {
        age += deltaTime;
        if (!locallyControlled) {
            stroke.update(oars, deltaTime);
        }
    }
}
