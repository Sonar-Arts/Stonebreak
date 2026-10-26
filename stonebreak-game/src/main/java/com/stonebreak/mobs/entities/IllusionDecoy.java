package com.stonebreak.mobs.entities;

import com.stonebreak.world.World;
import org.joml.Vector3f;

/**
 * A player-shaped illusory decoy spawned by the Illusionist's Mirrored Deceit ability. It has no
 * AI of its own: each update it mirrors its owner's movement rotated by a fixed angular offset so
 * the three figures fan out around the player. With 1 HP, any hit destroys it; the ability detects
 * the death and punishes the attacker (slow + Reveal + Doubt).
 *
 * <p>No hostile mob attacks exist yet, and players may not hit each other's decoys (no PvP,
 * #343/#374), so the SERVER's decoy takes no damage at all ({@link #getIncomingDamageMultiplier}):
 * a projectile, cactus or burn trail must not "destroy" it and punish a bystander mob. A decoy
 * despawn on a client is therefore always an expiry. Hostile AI that strikes decoys has to give
 * them a damage path of its own, and tell the caster's client who struck.
 *
 * <p>Two-world model (#341): the decoy is a SERVER entity, so server mobs can target it, and it
 * replicates to every client as a shadow. The server drives it from the owner's reported
 * position ({@link MirrorSource}); a shadow has no source and only follows the network.
 *
 * <p>Extends {@link RemotePlayer} purely to reuse the cylinder render path; it reports its own
 * {@link EntityType#ILLUSION_DECOY} so spawning and renderer dispatch can distinguish it.</p>
 */
public class IllusionDecoy extends RemotePlayer {

    /**
     * What a decoy mirrors: its owner's live position and facing. Backed by the server's record
     * of the owning player, or by the local player when there is no session at all.
     */
    public interface MirrorSource {
        /** The owner's current position (read, never kept). */
        Vector3f position();

        /** Model-space yaw of the owner's body. */
        float bodyYaw();

        /** Model-space yaw of the owner's look direction. */
        float lookYaw();
    }

    /** One decoy per angle (degrees), fanned out around the caster: 120° and 240°. */
    private static final float[] FAN_ANGLES = {120f, 240f};

    /** The angles one cast fans its decoys out at (a copy). */
    public static float[] fanAngles() {
        return FAN_ANGLES.clone();
    }

    /** Synthetic network id used only to seed the render tint (decoys share the illusion hue). */
    private static final int DECOY_TINT_ID = -7;

    /** Null on a client shadow: the server moves it. */
    private final MirrorSource owner;
    private final Vector3f spawnAnchor;
    private final Vector3f previousOwnerPos;
    /** Rotation offset (degrees) of this decoy's mirrored movement around the owner. */
    private final float angle;
    /** Set externally to mirror the owner's attack pose. */
    private boolean fakeCasting;

    /** Nothing may damage a decoy yet (see the class comment). */
    @Override
    public float getIncomingDamageMultiplier(DamageSource source) {
        return 0f;
    }

    public IllusionDecoy(World world, Vector3f position, MirrorSource owner, float angle) {
        super(world, position, DECOY_TINT_ID, "");
        this.owner = owner;
        this.angle = angle;
        this.spawnAnchor = new Vector3f(position);
        this.previousOwnerPos = owner != null ? new Vector3f(owner.position()) : new Vector3f(position);
        // 1 HP so any contact kills it (overrides REMOTE_PLAYER's 20 HP from the super ctor).
        setMaxHealth(1f);
        setHealth(1f);
    }

    /**
     * Mirrors the owner's position delta since the last update rotated by {@link #angle} about the
     * Y axis, so the decoy tracks the player while fanning out. Yaw mirrors the owner's look only
     * while {@link #fakeCasting} is set.
     */
    private void mirrorOwner(float deltaTime) {
        Vector3f ownerPos = owner.position();
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

        // Face the same way the owner's body does, rotated by this decoy's offset so the
        // fanned-out figures stay oriented with their mirrored movement. While the owner is
        // attacking, face the look direction instead so the cast pose reads correctly.
        // Both are MODEL-space yaw (raw camera degrees are a different angle space).
        rotation.y = (fakeCasting ? owner.lookYaw() : owner.bodyYaw()) + angle;

        // Drive the SBE model: derive walk/idle from the move just applied and advance the clip.
        updateMovementAnimation(deltaTime);
    }

    public Vector3f getSpawnAnchor() { return new Vector3f(spawnAnchor); }
    public float getAngle() { return angle; }
    public void setFakeCasting(boolean fakeCasting) { this.fakeCasting = fakeCasting; }

    @Override
    public EntityType getType() { return EntityType.ILLUSION_DECOY; }

    // A decoy's rotation.y is already MODEL-space yaw (set from the owner's body yaw /
    // converted look yaw above), unlike a networked RemotePlayer whose rotation.y is a raw
    // camera yaw. Renderers call these accessors uniformly for every player-shaped figure.
    @Override
    public float getBodyYaw() { return rotation.y; }

    @Override
    public float getHeadYaw() { return 0f; }

    @Override
    public float getHeadPitch() { return 0f; }

    /** Decoys take no hits, PvP or not (#374): there is nobody behind the figure to hurt. */
    @Override
    public boolean isPlayerAttackable() { return false; }

    @Override
    public boolean isPersistent() {
        // Transient — decoys live ~5s and are recreated on cast, never saved.
        return false;
    }

    @Override
    public void update(float deltaTime) {
        // No AI: an owned decoy mirrors its owner each tick.
        age += deltaTime;
        if (owner != null) {
            mirrorOwner(deltaTime);
        }
    }
}
