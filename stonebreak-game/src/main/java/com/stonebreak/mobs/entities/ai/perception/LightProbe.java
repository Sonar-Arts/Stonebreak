package com.stonebreak.mobs.entities.ai.perception;

import com.stonebreak.world.World;
import com.stonebreak.world.lighting.WorldLightingContext;
import org.joml.Vector3f;

/**
 * How lit a spot is, 0 (pitch dark) to 1 (full daylight), for vision-based detection (#321).
 */
@FunctionalInterface
public interface LightProbe {

    /** Light reaching anything under a roof, cave ceiling or canopy. */
    float COVERED_LIGHT = 0.1f;

    float lightAt(World world, Vector3f position);

    /** Everything fully lit, for tests and worlds with no sky. */
    LightProbe FULL = (world, position) -> 1f;

    /**
     * Sky light only: the world's daylight where the column above is open, {@link #COVERED_LIGHT}
     * under cover. Torches and other block lights are not counted yet — the voxel world keeps no
     * light levels the server could read cheaply — so a lit torch does not reveal a player.
     */
    LightProbe SKY = (world, position) -> {
        if (world == null) {
            return 1f;
        }
        int x = (int) Math.floor(position.x);
        int z = (int) Math.floor(position.z);
        int column = new WorldLightingContext(world).getColumnHeight(x, z);
        boolean covered = column >= 0 && column > (int) Math.floor(position.y) + 1;
        return covered ? Math.min(COVERED_LIGHT, world.getDaylight()) : world.getDaylight();
    };
}
