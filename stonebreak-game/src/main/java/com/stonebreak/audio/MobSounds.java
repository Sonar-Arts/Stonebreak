package com.stonebreak.audio;

import com.openmason.engine.audio.StrideFootsteps;
import com.stonebreak.blocks.BlockType;
import com.stonebreak.core.Game;
import com.stonebreak.mobs.entities.LivingEntity;
import com.stonebreak.player.Player;
import com.stonebreak.world.World;
import org.joml.Vector3f;

/**
 * Footsteps for mobs: one step per stride of ground actually covered. The timing rule is the
 * engine's {@link StrideFootsteps}; this class supplies what it cannot know — whether a mob is on
 * the ground or swimming — and which block underfoot sounds.
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
 * <p>Ticked where the mob is rendered: on a client network shadow ({@code updateClientVisuals}),
 * or on a client-local mob in a render-only world — never on the authoritative server world,
 * whose tick thread must not touch the client's mixer. A shadow runs no physics, so its ground
 * contact is read from the block under its feet instead of {@code isOnGround()}.
 */
public class MobSounds {

    /** Beyond this, a footstep is not worth the work; the 3D mixer would attenuate it to nothing. */
    private static final float MAX_AUDIBLE_DISTANCE = 30.0f;

    /** Past this much submersion a mob is swimming, and swimming has no footfalls. */
    private static final float SWIMMING_SUBMERSION = 0.5f;

    private final World world;
    private final StrideFootsteps steps;
    private final float stepVolume;

    /**
     * @param strideLength blocks of travel between footsteps — a short-legged mob has a short stride
     * @param stepVolume   gain scale applied to the block's authored step volume
     */
    public MobSounds(World world, float strideLength, float stepVolume) {
        this.world = world;
        this.steps = new StrideFootsteps(strideLength);
        this.stepVolume = stepVolume;
    }

    /**
     * Footsteps sized from the mob itself: it strides about its own body length, and a taller
     * animal lands harder — so every mob has footsteps and a new one needs no audio tuning.
     */
    public static MobSounds forEntity(World world, LivingEntity mob) {
        return new MobSounds(world, StrideFootsteps.strideFor(mob.getLength()),
                StrideFootsteps.volumeFor(mob.getLegHeight() + mob.getHeight()));
    }

    /**
     * Accumulates travel and plays a step each time the mob has covered a stride. Call once per
     * update from the owning mob.
     *
     * @return whether a footstep fired this update
     */
    public boolean updateSounds(LivingEntity mob) {
        Vector3f position = mob.getPosition();
        boolean grounded = mob.isNetworkShadow() ? standsOnSolid(mob) : mob.isOnGround();
        if (!steps.update(position.x, position.z, grounded, mob.getSubmersion() >= SWIMMING_SUBMERSION)) {
            return false;
        }
        playStep(mob);
        return true;
    }

    /** Resets the footstep state. Call when the mob is spawned or teleported. */
    public void reset() {
        steps.reset();
    }

    /** Ground contact for a shadow (no physics): a solid block just under its feet. */
    private boolean standsOnSolid(LivingEntity mob) {
        if (world == null) {
            return false;
        }
        Vector3f position = mob.getPosition();
        BlockType below = world.getBlockAt(
                (int) Math.floor(position.x),
                (int) Math.floor(position.y - mob.getLegHeight() - 0.1f),
                (int) Math.floor(position.z));
        return below != null && below.isSolid();
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
        com.stonebreak.fx.bindings.BlockFx.stepAt(ground, position.x, feetY, position.z);
    }

    /**
     * Whether anyone is close enough to hear this. Plays when there is no local player at all,
     * because then the mixer's own listener decides.
     */
    private boolean isAudible(LivingEntity mob) {
        Player player = Game.getPlayer();
        return player == null
                || mob.getPosition().distance(player.getPosition()) <= MAX_AUDIBLE_DISTANCE;
    }
}
