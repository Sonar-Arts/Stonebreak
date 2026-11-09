package com.stonebreak.fx.bindings;

import com.openmason.engine.entity.sbe.SbeEntityAsset;
import com.openmason.engine.format.fx.FxData;
import com.stonebreak.fx.Fx;
import com.stonebreak.fx.FxRuntime;
import com.stonebreak.mobs.entities.EntityType;
import com.stonebreak.mobs.sbe.SbeEntityRegistry;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Entity FX from the entity's own SBE ({@code fx[]}, SBE 1.6+) — the FX twin of {@code EntitySounds}
 * (Stonebreak#437): {@code hurt}, {@code death} and any custom event (a player's {@code splash}) play whatever the
 * asset binds, nothing in code names an effect. Positions are the entity's feet, which is what binding offsets are
 * authored against.
 *
 * <p>Client-side only, like all FX: server-side happenings arrive as {@code SoundEventS2C} and are replayed here by
 * the client sound handler. Callable from any thread (handed to the main thread).
 */
public final class EntityFx {

    private static final FxData NONE = new FxData(java.util.List.of());
    private static final Map<String, FxData> DATA = new ConcurrentHashMap<>();
    private static volatile Function<String, FxData> source = EntityFx::fromRegistry;

    private EntityFx() {
    }

    /**
     * Plays {@code event} of {@code type}'s SBE at the entity's feet. True when the asset binds FX to the event
     * (whether or not it played).
     */
    public static boolean play(EntityType type, String event, double feetX, double feetY, double feetZ,
                               float intensity) {
        if (type == null) {
            return false;
        }
        return play(type.getSbeObjectId(), event, feetX, feetY, feetZ, intensity);
    }

    /** {@link #play(EntityType, String, double, double, double, float)} by SBE object id. */
    public static boolean play(String sbeObjectId, String event, double feetX, double feetY, double feetZ,
                               float intensity) {
        FxData data = data(sbeObjectId);
        if (!AssetFx.binds(data, event)) {
            return false;
        }
        if (!Fx.onMainThread()) {
            Fx.runOnMainThread(() -> play(sbeObjectId, event, feetX, feetY, feetZ, intensity));
            return true;
        }
        FxRuntime runtime = Fx.runtime();
        if (runtime != null) {
            AssetFx.fire(runtime, data, event, feetX, feetY, feetZ, 0, intensity);
        }
        return true;
    }

    /**
     * A replicated entity event ({@code SoundEventS2C} carries the body's middle, {@code position.y + height/2}):
     * played at the feet below it ({@code position.y - legHeight}, where models are anchored).
     */
    public static boolean playReplicated(EntityType type, String event, float x, float midY, float z) {
        if (type == null) {
            return false;
        }
        return play(type, event, x, midY - type.getHeight() * 0.5f - type.getLegHeight(), z, AssetFx.NORMAL);
    }

    /** Custom entity event: the entity broke the water surface. */
    public static final String EVENT_SPLASH = "splash";
    /** Impact speed (blocks/s) that plays a splash at its authored intensity. */
    static final float SPLASH_REFERENCE_SPEED = 8f;

    /**
     * A player ({@code type}) broke the water surface at {@code feet} falling at {@code impactSpeed} blocks/s:
     * plays the SBE's {@value #EVENT_SPLASH} bindings, strength from the speed (0.35..1.6 x authored). True when
     * the asset binds a splash, so the caller can retire its legacy splash.
     */
    public static boolean splash(EntityType type, org.joml.Vector3f feet, float impactSpeed) {
        float intensity = Math.clamp(impactSpeed / SPLASH_REFERENCE_SPEED, 0.35f, 1.6f);
        return play(type, EVENT_SPLASH, feet.x, feet.y, feet.z, intensity);
    }

    /** {@code event} of a live entity, at its feet. */
    public static boolean playAtFeet(com.stonebreak.mobs.entities.Entity entity, String event) {
        if (entity == null || entity.getType() == null) {
            return false;
        }
        org.joml.Vector3f p = entity.getPosition();
        return play(entity.getType(), event, p.x, p.y - entity.getType().getLegHeight(), p.z, AssetFx.NORMAL);
    }

    /** The asset's bindings (no entries when it has none or is not SBE-driven). */
    public static FxData data(String sbeObjectId) {
        if (sbeObjectId == null) {
            return NONE;
        }
        FxData cached = DATA.get(sbeObjectId);
        if (cached != null) {
            return cached;
        }
        FxData loaded = source.apply(sbeObjectId);
        if (loaded == null) {
            return NONE; // registry not loaded yet: don't cache the miss
        }
        DATA.put(sbeObjectId, loaded);
        return loaded;
    }

    public static void invalidate() {
        DATA.clear();
        AssetFx.clearCache();
    }

    /** Replaces where bindings come from (tests); null restores the SBE registry. */
    public static void setSource(Function<String, FxData> next) {
        source = next == null ? EntityFx::fromRegistry : next;
        invalidate();
    }

    private static FxData fromRegistry(String objectId) {
        if (SbeEntityRegistry.size() == 0) {
            return null;
        }
        SbeEntityAsset asset = SbeEntityRegistry.get(objectId);
        if (asset == null || asset.fx() == null) {
            return NONE;
        }
        return asset.fx();
    }
}
