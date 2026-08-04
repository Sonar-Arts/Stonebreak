package com.stonebreak.mobs.entities.ai.nav;

import com.openmason.engine.wayfind.voxel.NavProfile;
import com.stonebreak.mobs.entities.LivingEntity;

/**
 * Derives a mob's {@link NavProfile} from the physical facts it already carries, so a new mob
 * navigates correctly the moment it has dimensions and a jump — no per-mob navigation tuning, and
 * no second set of numbers to keep in sync with physics.
 *
 * <p>Every limit here traces to something the collision code actually does:
 * {@link #MAX_STEP_UP} is the auto-step {@code EntityCollision} performs, and the climb ceiling
 * comes from the mob's own jump apex rather than a constant, so a stronger jumper really does plan
 * routes a weaker one will not.
 */
public final class NavProfiles {

    /** The auto-step entity collision performs without a jump. Mirrors {@code EntityCollision}. */
    public static final float MAX_STEP_UP = 0.5f;

    /**
     * Fraction of the jump apex a route may rely on. A mob leaves the ground moving forward and
     * has to land its whole footprint on the ledge, so planning to the theoretical apex produces
     * hops that clip the edge and fail.
     */
    private static final float USABLE_JUMP_FRACTION = 0.8f;

    /** No amount of jump lets a route climb more than this in one move. */
    private static final float CLIMB_CEILING = 1.5f;

    /** Drops a mob will take on purpose. Below the player's four-block fall-damage threshold. */
    private static final float WALKER_MAX_FALL = 3.0f;
    private static final float SWIMMER_MAX_FALL = 5.0f;

    /**
     * Footprint beyond which a route demands clearance either side. No current mob reaches it —
     * the widest is the cow at 1.3 — and that is deliberate: mobs whose box slightly overhangs a
     * block still fit through the gaps players expect them to, because collision resolves the
     * overhang. The rule exists so that a genuinely large mob does not plan through a doorway it
     * cannot enter.
     */
    private static final float WIDE_FOOTPRINT = 2.0f;

    private NavProfiles() {
        throw new UnsupportedOperationException("Utility class");
    }

    /** The navigation profile for one mob, derived from its dimensions, jump and swimming ability. */
    public static NavProfile forEntity(LivingEntity entity) {
        float standingHeight = entity.getLegHeight() + entity.getHeight();
        float footprint = Math.max(entity.getWidth(), entity.getLength());
        int columnRadius = footprint > WIDE_FOOTPRINT ? 1 : 0;

        float maxClimb = Math.max(MAX_STEP_UP,
                Math.min(CLIMB_CEILING, entity.getJumpApexHeight() * USABLE_JUMP_FRACTION));
        // Leaving water is a swim stroke, which reaches higher than a standing jump.
        float waterEscapeClimb = Math.max(maxClimb,
                entity.getSwimStrokeReach() * USABLE_JUMP_FRACTION);

        if (entity.canSwim()) {
            return new NavProfile(standingHeight, columnRadius,
                    MAX_STEP_UP, maxClimb, waterEscapeClimb, SWIMMER_MAX_FALL,
                    true, 1.0f, 1.2f,
                    0.5f, 2.0f, 0.2f);
        }
        // Land mobs CAN swim — they just hate it. Marking deep water impassable instead would be
        // worse than a preference: a cow that fell in a lake would have no route out at all, and
        // would stand on the bottom until something else moved it. The multipliers are steep
        // enough that any dry route of comparable length wins, so they still walk around ponds.
        return new NavProfile(standingHeight, columnRadius,
                MAX_STEP_UP, maxClimb, waterEscapeClimb, WALKER_MAX_FALL,
                true, 3.0f, 8.0f,
                0.5f, 2.0f, 0.5f);
    }
}
