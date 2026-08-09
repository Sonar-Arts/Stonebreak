package com.stonebreak.audio;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.core.Game;
import com.stonebreak.mobs.entities.LivingEntity;
import com.stonebreak.player.Player;
import com.stonebreak.world.World;
import org.joml.Vector3f;

/**
 * Footsteps for mobs: one step per stride of ground actually covered.
 *
 * <p>Stepping on <em>distance</em> rather than on a timer is what makes this correct without
 * tuning. A mob ambling and the same mob bolting cover ground at different rates, so they step at
 * different rates for free — where a fixed cadence gave both the same plod and needed a per-mob
 * interval to sound right. It also removes the on/off flicker the old version defended against
 * with a second parameter: a hop-prone mob that leaves the ground for a few frames simply stops
 * accumulating distance instead of resetting a timer and stuttering. And it no longer depends on
 * a frame clock — the old version read the render frame's delta while mobs tick on the server's,
 * so its cadence was wrong wherever the two differ.
 *
 * <p>The sample itself is never chosen here — it comes from the SBO {@code sounds[]} data of the
 * block underfoot ({@link BlockSounds}), so a new block with authored step audio is heard by every
 * mob with no code change.
 *
 * <p>TODO: remote multiplayer clients hear nothing. Mobs are simulated on the authoritative server
 * world and a client sees interpolated network shadows, whose {@code update()} is skipped — so
 * these play from the server-side mob, which is audible in single-player (same JVM) and silent
 * across a connection. The fix is to tick this on the shadow in {@code updateClientVisuals}
 * instead, gated to render-only worlds so single-player does not play each step twice. Nothing
 * else needs to change: stepping on distance already works off interpolated positions.
 */
public class MobSounds {

    /** Beyond this, a footstep is not worth the work; the 3D mixer would attenuate it to nothing. */
    private static final float MAX_AUDIBLE_DISTANCE = 30.0f;

    /**
     * Ignore any single-update jump larger than this — a teleport, a network correction or a spawn
     * should not fire a burst of footsteps.
     */
    private static final float MAX_TRAVEL_PER_UPDATE = 1.5f;

    /** Past this much submersion a mob is swimming, and swimming has no footfalls. */
    private static final float SWIMMING_SUBMERSION = 0.5f;

    /**
     * Updates of tolerance for a mob that momentarily leaves the ground.
     *
     * <p>Not a nicety: a walking mob is measurably airborne about half the time — ground contact
     * flickers as it waddles — so treating "off the ground" as "not walking" silences it entirely.
     */
    private static final int GROUND_GRACE_UPDATES = 4;

    /** Derivation of stride and loudness from a mob's dimensions; see {@link #forEntity}. */
    private static final float MIN_STRIDE = 0.4f;
    private static final float VOLUME_PER_BLOCK_TALL = 0.18f;
    private static final float VOLUME_BASE = 0.12f;
    private static final float MIN_STEP_VOLUME = 0.15f;
    private static final float MAX_STEP_VOLUME = 0.4f;

    private final World world;
    private final float strideLength;
    private final float stepVolume;

    private final Vector3f lastPosition = new Vector3f();
    private boolean hasLastPosition;
    private float distanceSinceStep;
    private int groundedGrace;

    /**
     * @param strideLength blocks of travel between footsteps — a short-legged mob has a short stride
     * @param stepVolume   gain scale applied to the block's authored step volume
     */
    public MobSounds(World world, float strideLength, float stepVolume) {
        this.world = world;
        this.strideLength = Math.max(0.1f, strideLength);
        this.stepVolume = stepVolume;
    }

    /**
     * Footsteps sized from the mob itself: it strides about its own body length, and a taller
     * animal lands harder.
     *
     * <p>Derived rather than configured so that every mob has footsteps and a new one needs no
     * audio tuning — the same reason its navigation limits come from its jump rather than from a
     * table. The numbers land within a hair of the values the cow and goose were hand-tuned to.
     */
    public static MobSounds forEntity(World world, LivingEntity mob) {
        float stride = Math.max(MIN_STRIDE, mob.getLength());
        float standingHeight = mob.getLegHeight() + mob.getHeight();
        float volume = Math.min(MAX_STEP_VOLUME,
                Math.max(MIN_STEP_VOLUME, VOLUME_PER_BLOCK_TALL * standingHeight + VOLUME_BASE));
        return new MobSounds(world, stride, volume);
    }

    /**
     * Accumulates travel and plays a step each time the mob has covered a stride. Call once per
     * update from the owning mob.
     *
     * @return whether a footstep fired this update
     */
    public boolean updateSounds(LivingEntity mob) {
        Vector3f position = mob.getPosition();
        if (!hasLastPosition) {
            lastPosition.set(position);
            hasLastPosition = true;
            return false;
        }

        float dx = position.x - lastPosition.x;
        float dz = position.z - lastPosition.z;
        lastPosition.set(position);

        if (mob.isOnGround()) {
            groundedGrace = GROUND_GRACE_UPDATES;
        } else if (groundedGrace > 0) {
            groundedGrace--;
        }

        // Airborne or swimming: stop accumulating, but keep what is banked — a mob that hops
        // mid-stride carries on where it left off rather than restarting the stride.
        if (groundedGrace <= 0 || isSwimming(mob)) {
            return false;
        }

        float travelled = (float) Math.sqrt(dx * dx + dz * dz);
        if (travelled > MAX_TRAVEL_PER_UPDATE) {
            return false;
        }

        distanceSinceStep += travelled;
        if (distanceSinceStep < strideLength) {
            return false;
        }
        distanceSinceStep = 0.0f;
        playStep(mob);
        return true;
    }

    /** Resets the footstep state. Call when the mob is spawned or teleported. */
    public void reset() {
        hasLastPosition = false;
        distanceSinceStep = 0.0f;
        groundedGrace = 0;
    }

    private boolean isSwimming(LivingEntity mob) {
        return mob.getSubmersion() >= SWIMMING_SUBMERSION;
    }

    private void playStep(LivingEntity mob) {
        if (world == null || !isAudible(mob)) {
            return;
        }
        Vector3f position = mob.getPosition();
        // The block underfoot, sampled from the mob's actual feet: its origin sits a leg-length
        // above them, so for a tall mob the two are in different blocks.
        float feetY = position.y - mob.getLegHeight();
        BlockType ground = world.getBlockAt(
                (int) Math.floor(position.x),
                (int) Math.floor(feetY - 0.1f),
                (int) Math.floor(position.z));

        BlockSounds.playStepAt(ground, new Vector3f(position.x, feetY, position.z), stepVolume);
    }

    /**
     * Whether anyone is close enough to hear this. Plays when there is no local player at all,
     * because then the mixer's own listener decides — the old version treated a null
     * {@code Game.getPlayer()} as "silence every mob", which is exactly the case on a server world.
     */
    private boolean isAudible(LivingEntity mob) {
        Player player = Game.getPlayer();
        return player == null
                || mob.getPosition().distance(player.getPosition()) <= MAX_AUDIBLE_DISTANCE;
    }
}
