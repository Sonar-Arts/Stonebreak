package com.stonebreak.fx.bindings;

import com.stonebreak.blocks.waterSystem.WaterSubmersion;
import com.stonebreak.mobs.entities.EntityType;
import com.stonebreak.player.PlayerConstants;
import com.stonebreak.world.World;
import org.joml.Vector3d;
import org.joml.Vector3dc;
import org.joml.Vector3f;

/**
 * One swimmer's water FX (Stonebreak#441): a splash as it breaks the surface and ripples trailing it while it moves
 * along the surface. Both come from the swimmer's SBE ({@value EntityFx#EVENT_SPLASH} / {@value EntityFx#EVENT_RIPPLE}
 * bindings); this class only decides <em>when</em> and <em>where</em>. The local player and every remote player
 * own one, so a remote swimmer ripples the same way from its replicated water flags.
 *
 * <p>Not thread-safe; one owner thread (the game thread). The FX themselves are handed to the main thread.
 */
public final class SwimFx {

    private float rippleTimer;

    /**
     * The swimmer broke the surface with its feet at {@code feet} (world position), falling at {@code impactSpeed}
     * blocks/s: a splash, and a first ripple ring where it went in.
     */
    public void enteredWater(EntityType type, World world, Vector3dc feet, float impactSpeed) {
        EntityFx.splash(type, feet.x(), feet.y(), feet.z(), impactSpeed);
        ripple(type, world, feet.x(), feet.y(), feet.z());
        rippleTimer = 0f;
    }

    /**
     * Advances the ripple cadence: while the body touches water with its eyes above it and moves faster than
     * {@link PlayerConstants#WATER_RIPPLE_SPEED_THRESHOLD}, a ring every
     * {@link PlayerConstants#WATER_RIPPLE_SPAWN_INTERVAL} seconds. Ripples are a surface effect: none while submerged.
     *
     * @param horizontalSpeed blocks/s across the surface
     */
    public void update(float dt, EntityType type, World world, Vector3dc feet,
                       boolean touchingWater, boolean eyesSubmerged, float horizontalSpeed) {
        if (!touchingWater || eyesSubmerged) {
            rippleTimer = 0f;
            return;
        }
        rippleTimer += dt;
        if (horizontalSpeed > PlayerConstants.WATER_RIPPLE_SPEED_THRESHOLD
                && rippleTimer >= PlayerConstants.WATER_RIPPLE_SPAWN_INTERVAL) {
            ripple(type, world, feet.x(), feet.y(), feet.z());
            rippleTimer = 0f;
        }
    }

    private static void ripple(EntityType type, World world, double x, double feetY, double z) {
        EntityFx.ripple(type, x, surfaceY(world, type, x, feetY, z), z);
    }

    /**
     * Where the water's surface cuts the body standing at {@code feet} (the ring lies on it, not at the feet of a
     * body treading water), or the feet when the body is not partly in water (no world, dry, or under).
     */
    static double surfaceY(World world, EntityType type, double x, double feetY, double z) {
        float height = type == null ? 0f : type.getHeight();
        float submerged = WaterSubmersion.fractionAt(world, x, feetY, z, height);
        return submerged > 0f && submerged < 1f ? feetY + submerged * height : feetY;
    }

    static float surfaceY(World world, EntityType type, Vector3f feet) {
        return (float) surfaceY(world, type, feet.x, feet.y, feet.z);
    }
}
