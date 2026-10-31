package com.stonebreak.mobs.entities;

import com.stonebreak.world.World;
import com.stonebreak.world.generation.TerrainGenerator;
import com.stonebreak.world.generation.biomes.BiomeType;
import org.joml.Vector3f;

/**
 * When, where and how many of one hostile mob {@link HostileSpawner} puts in the world. A new night
 * creature is one more rule, not another branch in the spawner.
 *
 * @param type     what spawns
 * @param chance   probability, per spawn interval that its {@code when} allows, of spawning at the
 *                 spot it found — 1 for a mob that turns up every night, small for a rare one
 * @param groupMin smallest group
 * @param groupMax largest group
 * @param cap      most of this type alive near the players at once
 * @param minDistance least distance from the player it spawns near (on top of the spawner's own floor)
 * @param shadeClockSeconds every this many seconds a player stays in the Shade, a group comes for
 *                 them ({@link ShadeClock}); 0 spawns on the spawner's interval, near anyone
 * @param when     whether the time is right
 * @param where    whether a chosen spot (for a player at {@code anchor}) will do
 */
record HostileSpawnRule(EntityType type, Chance chance, int groupMin, int groupMax, int cap,
                        float minDistance, float shadeClockSeconds, When when, Where where) {

    /** Time gate: {@code night} from the spawner's clock, fog from the world. */
    @FunctionalInterface
    interface When {
        boolean allows(World world, boolean night);
    }

    @FunctionalInterface
    interface Where {
        boolean allows(World world, Vector3f spot, Vector3f anchor);
    }

    /** Odds a group spawns at {@code spot}, so a mob can be common in one biome and rare in another. */
    @FunctionalInterface
    interface Chance {
        float at(World world, Vector3f spot);
    }

    /** Fog at least this thick before Wisps come out: properly night, not dusk. */
    static final float WISP_MIN_FOG = 0.6f;
    /** Every lap of this many seconds a player stays in the Shade through full fog brings Wisps. */
    static final float WISP_SHADE_CLOCK_SECONDS = 90f;

    /** Each 20-second roll at a spot in the Shade has this chance: about one band a night, not one a roll. */
    static final float GOBLIN_SHADE_CHANCE = 0.02f;

    /** Night-time goblin bands, anywhere, but rarely in the Shade, which belongs to the Wisps. */
    static final HostileSpawnRule GOBLIN = new HostileSpawnRule(EntityType.GOBLIN,
            (world, spot) -> isShade(world, spot) ? GOBLIN_SHADE_CHANCE : 1f, 2, 4, 8, 0f, 0f,
            (world, night) -> night, (world, spot, anchor) -> true);

    /**
     * The Shade's Wisps: alone or a pair, never more than two about, only in the full night fog, each
     * time a player's {@link #WISP_SHADE_CLOCK_SECONDS} clock in the Shade comes round, at a spot in
     * it. They start beyond what the fog lets anyone see.
     */
    static final HostileSpawnRule WISP = new HostileSpawnRule(EntityType.WISP, (world, spot) -> 1f, 1, 2, 2, 32f,
            WISP_SHADE_CLOCK_SECONDS,
            (world, night) -> world != null && world.getNightFog() >= WISP_MIN_FOG,
            (world, spot, anchor) -> isShade(world, spot) && isShade(world, anchor));

    static boolean isShade(World world, Vector3f point) {
        TerrainGenerator terrain = world != null ? world.terrain() : null;
        return terrain != null && terrain.getBiomeAt((int) Math.floor(point.x), (int) Math.floor(point.z)) == BiomeType.SHADE;
    }
}
