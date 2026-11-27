package com.stonebreak.mobs.sbe;

/**
 * How a mob type rides an animation graph (#460): the graph itself is the one its SBE character ({@code .omchar})
 * names in {@code animGraph}; this says where the graph's slot clips come from and which ground speeds are its walk
 * and its run.
 *
 * @param clipSource the object id of the characterized asset whose clips fill the graph's slots, retargeted onto the
 *                   mob (null: the mob's own clips, by slot name)
 * @param walkSpeed  the mob's walking pace as a multiple of its move speed: the graph's walk point
 * @param runSpeed   its running pace (a multiple of its move speed): the graph's run point
 */
public record MobGraphSpec(String clipSource, float walkSpeed, float runSpeed) {

    public MobGraphSpec {
        if (!(walkSpeed > 0f) || !(runSpeed > walkSpeed)) {
            throw new IllegalArgumentException("walk speed must be > 0 and run speed above it: " + walkSpeed + ", "
                    + runSpeed);
        }
    }
}
