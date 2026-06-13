package com.stonebreak.mobs.entities;

import com.stonebreak.player.Player;
import com.stonebreak.world.World;
import org.joml.Vector3f;

/**
 * A player-shaped illusory decoy spawned by the Illusionist's Mirrored Deceit ability. It has no
 * AI of its own — {@link com.stonebreak.player.combat.illusionist.MirroredDeceitAbility} drives it
 * each frame, mirroring the owner's movement rotated by a fixed angular offset so the three figures
 * fan out around the player. With 1 HP, any hit destroys it; the ability detects the death and
 * punishes the attacker (slow + Reveal + Doubt).
 *
 * <p>Extends {@link RemotePlayer} purely to reuse the cylinder render path; it reports its own
 * {@link EntityType#ILLUSION_DECOY} so spawning and renderer dispatch can distinguish it.</p>
 */
public class IllusionDecoy extends RemotePlayer {

    /** Synthetic network id used only to seed the render tint (decoys share the illusion hue). */
    private static final int DECOY_TINT_ID = -7;

    private final Player owner;
    private final Vector3f spawnAnchor;
    private final Vector3f previousOwnerPos;
    /** Rotation offset (degrees) of this decoy's mirrored movement around the owner. */
    private final float angle;
    /** Set externally each frame to mirror the owner's attack pose. */
    private boolean fakeCasting;

    public IllusionDecoy(World world, Vector3f position, Player owner, float angle) {
        super(world, position, DECOY_TINT_ID, "");
        this.owner = owner;
        this.angle = angle;
        this.spawnAnchor = new Vector3f(position);
        this.previousOwnerPos = new Vector3f(owner.getPosition());
        // 1 HP so any contact kills it (overrides REMOTE_PLAYER's 20 HP from the super ctor).
        setMaxHealth(1f);
        setHealth(1f);
    }

    /**
     * Driven by MirroredDeceitAbility. Mirrors the owner's per-frame position delta rotated by
     * {@link #angle} about the Y axis, so the decoy tracks the player while fanning out. Yaw mirrors
     * the owner only while {@link #fakeCasting} is set.
     */
    public void update(float deltaTime, Player ownerPlayer) {
        Vector3f ownerPos = ownerPlayer.getPosition();
        float dx = ownerPos.x - previousOwnerPos.x;
        float dz = ownerPos.z - previousOwnerPos.z;
        float dy = ownerPos.y - previousOwnerPos.y;

        // Rotate the horizontal delta by the fixed offset (2D rotation on the XZ plane).
        double rad = Math.toRadians(angle);
        float cos = (float) Math.cos(rad);
        float sin = (float) Math.sin(rad);
        float rx = dx * cos - dz * sin;
        float rz = dx * sin + dz * cos;

        position.add(rx, dy, rz);
        previousOwnerPos.set(ownerPos);

        if (fakeCasting) {
            rotation.y = ownerPlayer.getCamera().getYaw() + angle;
        }
        age += deltaTime;
    }

    public Player getOwner() { return owner; }
    public Vector3f getSpawnAnchor() { return new Vector3f(spawnAnchor); }
    public float getAngle() { return angle; }
    public void setFakeCasting(boolean fakeCasting) { this.fakeCasting = fakeCasting; }

    @Override
    public EntityType getType() { return EntityType.ILLUSION_DECOY; }

    @Override
    public boolean isPersistent() {
        // Transient — decoys live ~5s and are recreated on cast, never saved.
        return false;
    }

    @Override
    public void update(float deltaTime) {
        // No autonomous physics/AI; the ability calls update(dt, owner) explicitly each frame.
        age += deltaTime;
    }
}
